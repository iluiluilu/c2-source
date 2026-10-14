# Tech Spec: personal-storage-quota

> File: `docs/03-dev/specs/personal-storage-quota.md` — Owner: DEV
> Nguồn story: `docs/02-ba/stories/personal-storage-quota.md`
> Status: Approved

> ## ⚠️ Thứ tự triển khai chung (đã chốt) — đọc trước khi implement
>
> Flyway trong repo **không** bật `out-of-order`
> (`backend/src/main/resources/application.yml`), nên **số hiệu migration
> bắt buộc khớp đúng thứ tự merge** — không thể chèn số nhỏ hơn về sau.
> Migration đang có trong repo: `V1`–`V6`.
>
> | # | Spec | Migration |
> |---|---|---|
> | 0 | *(hạ tầng, không migration)* | Redis + RabbitMQ vào `docker-compose*.yml` + `backend/pom.xml` — dựng **một lần**, dùng chung |
> | 1 | `system-config` | `V7__init_system_config.sql` |
> | 2 | `account-lifecycle` | `V8__init_account.sql` |
> | 3 | `warehouse-structure` | `V9__warehouse_structure_v2.sql` |
> | 4 | `data-ingestion` | `V10__data_ingestion.sql` |
> | 5 | `personal-storage-quota` ⬅ **spec này** | `V11__personal_storage_quota.sql` |
> | 6 | `rbac-group-scope` | `V12__init_groups.sql` + `V13__drop_case_files.sql` |
> | 7 | `auth-session` | `V14__init_keycloak_event_cursor.sql` |
>
> `rbac-group-scope` và `auth-session` không phụ thuộc spec nào khác nên
> **được phép code song song ngay từ đầu** — bảng trên chỉ ràng buộc thời
> điểm *merge*, không ràng buộc thời điểm *phát triển*.

## 0. Quyết định đã chốt (2026-08-12)

4 điểm mở của spec này đã được người duyệt chốt; nội dung bên dưới đã phản
ánh đầy đủ. Giữ mục này để bước sau không mở lại tranh luận.

| # | Vấn đề | Quyết định |
|---|---|---|
| 1 | `data-ingestion.md` đổi sang `pending` → `putObject` → `clean`; kho cá nhân có theo không | **Không** — giữ cơ chế "ghi object rồi compensate xóa" đang chạy. `personal_file` không có cột `status`, thêm vào chỉ để đồng bộ hình thức là đắt. Khác biệt **có chủ đích**, ghi rõ ở mục 4.2 và Rủi ro. |
| 2 | Trần dung lượng từng tệp cho kho cá nhân (ClamAV dùng chung nên cùng vấn đề) | **Dùng chung key `ingest.max_file_size_bytes`** của `data-ingestion.md`/`system-config.md`. Giới hạn xuất phát từ năng lực ClamAV chứ không từ nghiệp vụ, mà ClamAV thì dùng chung — tách 2 key chỉ tạo chỗ để cấu hình lệch nhau. |
| 3 | Tệp cá nhân của tài khoản `disabled` bị đóng băng vĩnh viễn | **Chấp nhận, ghi rõ là khoảng trống nghiệp vụ.** Giữ đúng Story 1 AC-1 không nhân nhượng; không tự phát minh cơ chế bàn giao/xóa ngoài phạm vi. Cần một story riêng — xem Rủi ro mục 7. |
| 4 | Thùng rác cho `personal_file` (kho chung vừa có `document.deleted_at`) | **Chưa thêm** — không AC nào của story này yêu cầu. Cột `deleted_at` ở kho chung được thêm vì có 2 AC phụ thuộc; ở đây không có, thêm vào là xây để đó không ai dùng. Để FR-26/27 quyết chung cho cả 2 vùng. |

## 1. Tóm tắt kỹ thuật

File này **chỉ đặc tả phần mới/khác biệt**. 3 phần dùng chung, không lặp lại:

- Bảng `account` + cột `storage_quota_bytes` (snapshot mặc định theo vai trò
  lúc tạo tài khoản) → **xem `account-lifecycle.md` mục 2.1, 4.1**. Spec này
  chỉ **đọc** cột đó qua `AccountRepository`, không tạo lại schema/migration.
- Mô hình batch (mở batch → khai báo → nhận tệp → cộng dồn thực nhận) →
  **xem `data-ingestion.md` mục 2.2, 4.1–4.2, 4.5**. Spec này **mở rộng**
  bảng `upload_batch` (thêm cột phân biệt loại) thay vì tạo bảng batch
  riêng — quyết định + lý do ở mục 2.
- Cách tính "dung lượng đã dùng" = aggregate `SUM` trực tiếp trên siêu dữ
  liệu, không counter, không đọc SeaweedFS → **cùng pattern**
  `warehouse-structure.md` mục 4.4 (đã có sẵn trong code hiện tại,
  `PersonalFileRepository.sumSizeByOwnerSub`, không đổi).

**Quyết định tái sử dụng bảng batch:** mở rộng `upload_batch` (không tạo
bảng `personal_upload_batch` riêng) bằng cột `kind` (`WAREHOUSE`|`PERSONAL`)
+ `target_personal_folder_id` (nullable, thay cho `target_folder_id` khi
`kind='PERSONAL'`). Lý do: giữ đúng 1 khái niệm batch, 1 API pattern
(mở → nhận tệp → đóng/hết hạn), tránh 2 bộ code trùng lặp gần như hoàn
toàn. Đánh đổi: migration của spec này phải chạy **sau** migration tạo
`upload_batch` của `data-ingestion.md` **và** sau migration tạo `account`
của `account-lifecycle.md` — phụ thuộc chuỗi 2 spec Draft (xem Rủi ro).

**Khác biệt cố ý so với cơ chế reserve của `data-ingestion.md`:** batch kho
chung giới hạn theo 2 số **cố định** đọc 1 lần lúc mở batch
(`ingest.max_files_per_batch`/`max_batch_size_bytes`), nên `UPDATE ...
RETURNING` chỉ cần so với hằng số đó, không cần khóa liên-batch. Hạn mức cá
nhân là **tổng lũy kế toàn bộ tài khoản, xuyên suốt mọi batch/thư mục**
(không phải giới hạn riêng của 1 batch) — 2 batch cùng owner chạy song song
vẫn phải cộng dồn đúng vào **cùng một** hạn mức. Vì vậy bước commit 1 tệp cá
nhân **tái dùng nguyên khối khóa+re-check đã có sẵn trong code hiện tại**
(`PersonalService.commitFileInsert`: `pg_advisory_xact_lock(hashtext(owner_sub))`
rồi `SELECT SUM(size) ... FOR` re-check ngay trong transaction ghi) thay vì
pattern `UPDATE upload_batch SET actual_total_bytes = actual_total_bytes +
:size ... RETURNING` của `data-ingestion.md` — pattern đó vẫn được dùng
**song song** chỉ để cập nhật 2 cột `actual_file_count`/`actual_total_bytes`
trên `upload_batch` cho mục đích hiển thị tiến trình (Story 3 AC-1/AC-3 của
BA), **không phải** nguồn xác thực chặn vượt hạn mức (nguồn xác thực vẫn là
advisory lock + re-check `SUM(personal_file.size)`).

**Sửa lỗi hiện có (không phải tính năng mới):** `PersonalService.uploadFile()`
hiện đang gọi `MimeAllowlist.checkFormat(...)` — mâu thuẫn trực tiếp với
Story 1 AC-2 (không gian cá nhân phải nhận **mọi** loại tệp). Spec này xoá
lời gọi đó (giữ nguyên bước ClamAV).

**Breaking changes:**
- Xoá `POST /personal/files` (upload 1 bước) → thay bằng batch 2 bước.
- Xoá `PersonalProperties`/`app.personal.quotaBytes` (YAML tĩnh, 1 giá trị
  chung mọi vai trò) — nguồn hạn mức duy nhất từ nay là
  `account.storage_quota_bytes` của chính người dùng (Story 4 AC-4).

## 2. Data model

```sql
-- V11__personal_storage_quota.sql
-- BẮT BUỘC chạy SAU V8 (`account`, account-lifecycle.md) VÀ SAU V10
-- (`upload_batch`, data-ingestion.md) — 2 FK bên dưới tham chiếu tới bảng
-- của 2 spec đó. Thứ tự đã chốt: xem bảng ở đầu file.

-- 2.1 Mở rộng upload_batch (định nghĩa gốc: data-ingestion.md mục 2.2) để
-- dùng chung cho batch cá nhân, không tạo bảng batch riêng (xem mục 1).
ALTER TABLE upload_batch
    ALTER COLUMN target_folder_id DROP NOT NULL,
    ADD COLUMN kind VARCHAR(16) NOT NULL DEFAULT 'WAREHOUSE'
        CHECK (kind IN ('WAREHOUSE', 'PERSONAL')),
    ADD COLUMN target_personal_folder_id UUID REFERENCES personal_folder (id),
    ADD CONSTRAINT CK_upload_batch_kind_target CHECK (
        (kind = 'WAREHOUSE' AND target_folder_id IS NOT NULL AND target_personal_folder_id IS NULL)
        OR (kind = 'PERSONAL' AND target_folder_id IS NULL)
        -- target_personal_folder_id NULL hợp lệ khi kind=PERSONAL: nghĩa là thư mục gốc cá nhân.
    );
CREATE INDEX IDX_upload_batch_kind_owner ON upload_batch (kind, owner_sub, status);

-- 2.2 personal_file: gắn batch tạo ra tệp (truy vết + Story 3 AC-1 progress),
-- và index phục vụ SUM(size) theo owner bằng index-only scan (cùng lý do
-- IDX_document_status_size ở warehouse-structure.md mục 2.2).
ALTER TABLE personal_file
    ADD COLUMN batch_id UUID REFERENCES upload_batch (id);
CREATE INDEX IDX_personal_file_owner_size ON personal_file (owner_sub) INCLUDE (size);
-- IDX_personal_file_owner_sub hiện có (V6) giữ nguyên, không xoá (đủ cho
-- các query owner-scoped khác, ví dụ listFiles).
```

- `account.storage_quota_bytes` (đã có ở `account-lifecycle.md`) là **nguồn
  hạn mức duy nhất** — spec này chỉ đọc qua `AccountRepository` (entity
  `Account`, đã định nghĩa ở `account-lifecycle.md` mục 2.1), không thêm
  cột hạn mức nào ở `personal_folder`/`personal_file`.
- JPA: `UploadBatch` (từ `data-ingestion.md`) thêm field `kind`
  (`BatchKind` enum `WAREHOUSE`|`PERSONAL`), `targetPersonalFolderId`.
  `PersonalFile` thêm field `batchId`.

## 3. API contract

Base path giữ nguyên `/personal`. Toàn bộ endpoint owner-scoped qua
`@CurrentUser` — **không có tham số id tài khoản trong path ở bất kỳ đâu**,
loại trừ khả năng truy cập chéo qua path traversal; không có route
`/admin/personal/**` nào tồn tại (đã grep xác nhận).

| Method | Endpoint | Request | Response | Ghi chú |
|---|---|---|---|---|
| GET | `/personal/quota` | – | `200 {quotaBytes, usedBytes, remainingBytes}` | **Đổi nguồn dữ liệu** (breaking ở tầng service, không đổi shape response): `quotaBytes` đọc từ `account.storage_quota_bytes` của người gọi thay vì `PersonalProperties`. |
| POST | `/personal/batches` | `{folderId?: UUID, declaredFileCount, declaredTotalBytes}` | `201 {batchId, status:"OPEN", expiresAt}` | **Mới**, thay cho phần "mở lượt tải" ngầm định trước đây. `folderId` omit/`null` = gốc. Lỗi: `404` folder không tồn tại/không thuộc sở hữu; `409 {code:"QUOTA_EXCEEDED"}` nếu `declaredTotalBytes > quotaBytes - usedBytes` hiện tại. |
| POST | `/personal/batches/{batchId}/files` | multipart `file`, `folderId?` (giữ đúng semantics hiện có, KHÔNG dùng `relativePath` — không có yêu cầu tạo cây thư mục tự động từ path như kho chung) | `201 {status:"STORED", file: PersonalFile}` | **Thay thế** `POST /personal/files` (xoá). Lỗi: `404 BATCH_NOT_FOUND`; `409 {code:"BATCH_CLOSED"}`/`{code:"BATCH_EXPIRED"}`; `409 {code:"QUOTA_EXCEEDED", usedBytes, quotaBytes, remainingBytes}` (vượt giữa chừng — tệp này bị từ chối, các tệp trước giữ nguyên); `422 {code:"FILE_TOO_LARGE"}` (vượt `ingest.max_file_size_bytes` — key dùng chung với kho chung, Quyết định 2 mục 0); `422 {code:"MALWARE_DETECTED"}`; `503 {code:"SCANNER_UNAVAILABLE"}`. **Không có** `422 INVALID_FORMAT` — cá nhân nhận mọi định dạng (Story 1 AC-2), pipeline không gọi bước kiểm tra định dạng. |
| POST | `/personal/batches/{batchId}/close` | – | `200 {batchId, status:"CLOSED", summary:{filesStored, actualTotalBytes}}` | Mới, tuỳ chọn (như `data-ingestion.md`). |
| GET | `/personal/batches/{batchId}` | – | `200 {batchId, status, declaredFileCount, declaredTotalBytes, actualFileCount, actualTotalBytes, expiresAt}` | Mới — Story 3 AC-1 truy vấn tiến trình. |
| GET | `/personal/folders/tree` | – | như hiện tại | Không đổi. |
| POST | `/personal/folders` | `{name, parentId?}` | như hiện tại | Không đổi. |
| PATCH \| DELETE | `/personal/folders/{id}` | như hiện tại | như hiện tại | Không đổi. |
| GET | `/personal/files` | – | như hiện tại | Không đổi (mỗi `PersonalFile` nay có thêm `batchId`, additive). |
| PATCH \| DELETE | `/personal/files/{id}` | như hiện tại | như hiện tại | Không đổi. |
| GET | `/personal/files/{id}/content` | – | như hiện tại | Không đổi. |
| ~~POST~~ | ~~`/personal/files`~~ | – | – | **Xoá** (breaking). |

Format lỗi chung `{code, message}` (đồng bộ `com.aismartstore.backend.common`,
tái dùng đúng subtype `ConflictException`(`QUOTA_EXCEEDED`|`BATCH_CLOSED`|
`BATCH_EXPIRED`), `UnprocessableEntityException`(`MALWARE_DETECTED`),
`ServiceUnavailableException`(`SCANNER_UNAVAILABLE`) đã định nghĩa ở
`data-ingestion.md` mục 3 — không tạo mã lỗi trùng ý nghĩa khác tên).

## 4. Luồng xử lý chính

### 4.1 Owner-scoped enforcement (Story 1 AC-1)

- Mọi endpoint dùng `@CurrentUser` lấy `sub` từ JWT; mọi query
  `findByIdAndOwnerSub`/`findAllByOwnerSubOrderBy...` — id không thuộc sở
  hữu → `404` (không phải `403`, giữ nguyên nguyên tắc hiện có: "existence
  must not leak"). Áp dụng **y hệt** cho `batchId` mới:
  `uploadBatchRepository.findByIdAndOwnerSub(batchId, currentSub)`.
- **Không có đường vòng cho R3**: không có endpoint admin nào đọc
  `personal_file`/`personal_folder`/batch `kind='PERSONAL'` của người khác
  (đã rà soát toàn bộ package `personal`, không có controller thứ 2; audit
  log của R3 chỉ ghi lại **hành động**, không expose nội dung/API đọc trực
  tiếp). Vai trò trong `@PreAuthorize` chỉ quyết định "được vào `/personal`
  hay không" (mọi role được), **không** quyết định "xem của ai" — điều đó
  luôn cố định = chính `sub` gọi request, không nhận tham số owner từ
  client dưới bất kỳ hình thức nào (path/query/body).

### 4.2 Pipeline nhận 1 tệp trong batch cá nhân (Story 2, Story 3)

1. Load batch qua `findByIdAndOwnerSub`; `status != OPEN` hoặc hết hạn →
   `404`/`409 BATCH_CLOSED`/`409 BATCH_EXPIRED` (tái dùng lazy-expire logic
   của `data-ingestion.md` mục 4.2 bước 1).
2. Nếu `folderId` được truyền: `findOwnedFolder` (404 nếu không thuộc sở
   hữu) — không đổi so với code hiện tại.
2b. **Trần dung lượng tệp (Quyết định 2 mục 0)** — so `file.size` với
   `SystemConfigService.getLong("ingest.max_file_size_bytes")`; vượt →
   `422 {code:"FILE_TOO_LARGE"}`, **không** cộng batch counter, **không**
   gọi ClamAV. Dùng **chung** key với kho chung (`data-ingestion.md` mục
   4.2 bước 3b) vì trần này sinh ra từ giới hạn của `ClamdService` — thứ cả
   hai pipeline dùng chung — chứ không phải từ quy tắc nghiệp vụ riêng của
   kho cá nhân. Đặt trước bước 4 có chủ đích: quét mã độc là bước đắt nhất,
   không tiêu tài nguyên cho tệp chắc chắn bị từ chối.
3. **Bỏ qua hoàn toàn bước định dạng/allowlist** — không gọi
   `MimeAllowlist.checkFormat`/`AllowlistService` (khác `data-ingestion.md`
   bước 5 mục 4.2 của spec đó; đây là điểm rẽ nhánh chính giữa 2 pipeline).
4. **ClamAV** (tái dùng `ClamdService`, y hệt code hiện có): `ping()`
   fail-closed trước, `scanStream` sau khi buffer ra temp file; nhiễm →
   không lưu row, `422 MALWARE_DETECTED`; scanner lỗi/timeout → `503
   SCANNER_UNAVAILABLE`.
5. Tính `sha256`/`size` (giữ nguyên, dùng nội bộ, sha256 KHÔNG dùng để dedupe
   như kho chung — cá nhân không có khái niệm "trùng nội dung thì skip").
6. `objectStorageService.putObject(...)` vào `storageProperties.personalBucket()`
   (không đổi).
   - **Khác `data-ingestion.md` có chủ đích (Quyết định 1 mục 0):** kho
     chung nay dùng `pending` → `putObject` → `clean`; kho cá nhân **giữ**
     "ghi object trước, insert sau, lỗi thì xóa object bù trừ". Lý do:
     `personal_file` không có cột `status` và nhánh thất bại phổ biến nhất
     ở đây (vượt hạn mức) đã được compensate xử lý đúng — thêm một cột
     trạng thái chỉ để trông giống kho chung là chi phí không đổi lại được
     giá trị tương ứng. Rủi ro còn lại (tiến trình chết giữa bước 6 và 7)
     ghi ở mục 7.
7. **Commit có khoá (nguồn xác thực chặn vượt hạn mức — mục 1 đã giải
   thích lý do khác `data-ingestion.md`):** trong 1 transaction —
   `pg_advisory_xact_lock(hashtext(owner_sub))` → đọc
   `account.storage_quota_bytes` (get-or-create qua
   `AccountsService.getOrCreateAccountRow`, tái dùng nguyên hàm của
   `account-lifecycle.md` mục 2.2, không viết lại logic backfill) →
   `used = SUM(personal_file.size) WHERE owner_sub=?` → nếu
   `used + size > quota` → rollback (không insert), object đã `putObject`
   ở bước 6 bị xoá bù trừ (giữ nguyên cơ chế compensate hiện có), trả
   `409 QUOTA_EXCEEDED {usedBytes, quotaBytes, remainingBytes}` — **batch và
   các tệp đã lưu trước đó trong batch giữ nguyên** (Story 3 AC-2/AC-4). Nếu
   qua được: `INSERT personal_file(..., batch_id=batchId)` +
   `UPDATE upload_batch SET actual_file_count=actual_file_count+1,
   actual_total_bytes=actual_total_bytes+size, updated_at=now() WHERE
   id=batchId` (2 câu trong cùng transaction đã khoá — không cần
   `RETURNING` điều kiện vì khoá advisory đã loại race, khác cách
   `data-ingestion.md` dùng `UPDATE ... RETURNING` để tự loại race không
   cần lock ứng dụng).
8. Audit `personal.file.create` (không đổi).

### 4.3 Kiểm tra ở bước khai báo batch (Story 3 AC-1)

1. `POST /personal/batches`: đọc `account.storage_quota_bytes` (get-or-create
   như bước 4.2.7) + `used = SUM(personal_file.size)` hiện tại (KHÔNG khoá —
   chỉ là ước tính sớm, chặn rõ ràng nhất có thể trước khi client tốn thời
   gian tải; nguồn xác thực cuối cùng vẫn là bước 4.2.7 mỗi tệp).
2. `declaredTotalBytes > quota - used` → `409 QUOTA_EXCEEDED`, không tạo
   batch.
3. Ngược lại: `INSERT upload_batch(kind='PERSONAL', target_personal_folder_id=folderId,
   owner_sub, owner_username, declared_file_count, declared_total_bytes,
   status='OPEN', expires_at=now()+24h)` — tái dùng nguyên cột/mặc định của
   `data-ingestion.md` (TTL 24h, lazy-expire, không cần job dọn riêng cho
   MVP — xem `data-ingestion.md` mục 2.2/4.4, không lặp lại ở đây).

### 4.4 Không kích hoạt OCR/STT/lập chỉ mục (Story 1 AC-4, Story 2 AC-2)

- Không cần thay đổi gì — `PersonalService`/`PersonalController` từ trước
  đến nay không có bất kỳ dependency nào tới package OCR/STT/search/
  `DocumentsService` (đã verify bằng grep chéo, mục "Rà soát code"). Giữ
  nguyên tính cô lập này khi thêm batch — `UploadBatchService` (tái dùng
  từ `data-ingestion.md`) chỉ được gọi cho phần **bookkeeping** (mở/đóng/
  đếm), không gọi bất kỳ hàm nào của `DocumentsService`/pipeline OCR khi
  `kind='PERSONAL'`. Test plan mục 5 verify bằng kiểm tra dependency, không
  chỉ bằng kết quả cuối.

### 4.5 Concurrency (Story 3 "Concurrency")

- **Trong cùng 1 batch, nhiều tệp song song:** mỗi request có transaction
  riêng, tất cả tranh cùng 1 advisory lock theo `owner_sub` (mục 4.2.7) →
  tuần tự hoá đúng, không có 2 transaction cùng đọc `used` cũ rồi cùng
  insert (khác lỗi race kinh điển "check-then-act" nếu không khoá).
- **Nhiều batch khác nhau cùng owner song song:** vẫn dùng chung 1 khoá
  `hashtext(owner_sub)` (không phải theo `batchId`) — nên tuần tự hoá đúng
  **giữa các batch**, đây chính là lý do KHÔNG dùng pattern `UPDATE ...
  RETURNING` giới hạn theo 1 số cố định của `data-ingestion.md` (pattern đó
  chỉ đúng khi giới hạn gắn với 1 batch, không đúng khi giới hạn là tổng
  toàn tài khoản xuyên nhiều batch).
- **Khác owner song song:** không tranh chấp khoá (hash khác nhau), không
  ảnh hưởng lẫn nhau.
- **Postgres advisory lock là cluster-wide** (không phải in-memory
  per-instance như cache của `account-lifecycle.md` mục 4.5) — đúng ngay cả
  khi backend scale nhiều instance, không có lỗ hổng tương tự rủi ro cache
  TTL của spec đó.

## 5. Test plan

- **Unit test (`PersonalServiceTest`):**
  - Upload tệp định dạng bất kỳ (vd. `.exe`, không MIME trong allowlist
    kho chung) → **không** gọi `MimeAllowlist.checkFormat`/`AllowlistService`
    (verify bằng mock, không chỉ bằng kết quả) — Story 1 AC-2.
  - Nhiễm mã độc → không insert `personal_file`, không cộng batch counter,
    `422`.
  - Quota còn đủ → insert thành công, `used` sau đó tăng đúng `size`.
  - Quota vượt ở bước commit (mock `SUM` gần sát hạn mức) → không insert,
    object đã `putObject` bị xoá bù trừ, batch counter không đổi, `409
    QUOTA_EXCEEDED` đúng số liệu.
  - `openBatch()`: `declaredTotalBytes` vượt `quota - used` → `409`, không
    insert `upload_batch`.
  - **Tệp vượt `ingest.max_file_size_bytes`** → `422 FILE_TOO_LARGE`;
    verify `ClamdService` **không bị gọi** (bằng mock) và batch counter
    không đổi — Quyết định 2 mục 0.
  - **Định dạng bất kỳ vẫn qua được trần dung lượng**: tệp `.exe` nhỏ hơn
    trần → không bị `FILE_TOO_LARGE`, cũng không bị `INVALID_FORMAT`
    (2 quy tắc độc lập; trần là dung lượng, không phải định dạng).
  - Verify `uploadFileInBatch(...)` không gọi bất kỳ bean nào thuộc
    OCR/STT/search/`DocumentsService` (kiểm tra qua dependency injection
    của `PersonalService` — không có field nào trỏ tới các bean đó) —
    Story 1 AC-4, Story 2 AC-2.
- **Integration test (`PersonalControllerIT`, Testcontainers Postgres):**
  - R1 mở batch, tải 1 tệp → `201`, `GET /personal/files` thấy tệp,
    `GET /personal/quota` phản ánh đúng `usedBytes` mới.
  - R3 gọi `GET /personal/files/{id}/content` với `id` thuộc về tài khoản
    khác → `404` (không phải `403`) — Story 1 AC-1, case biên trọng tâm của
    yêu cầu ("kể cả R3"). Lặp lại với `batchId`/`folderId` của người khác.
  - Batch hợp lệ, gửi N tệp trong đó tệp thứ k làm vượt hạn mức (mock hạn
    mức nhỏ) → tệp 1..k-1 vẫn `STORED` và **vẫn tải xuống được sau đó**,
    tệp k trả `409 QUOTA_EXCEEDED`, batch **không** bị đóng — Story 3 AC-2.
  - Khai báo batch với `declaredTotalBytes` vượt hạn mức còn lại ngay từ
    đầu → `409 QUOTA_EXCEEDED`, không có `upload_batch` row nào được tạo
    (query DB xác nhận) — Story 3 AC-1.
  - 2 request tải tệp song song (2 thread/`CompletableFuture`), tổng 2 tệp
    vừa đúng bằng phần còn trống + 1 byte → đúng 1 trong 2 tệp `201`, tệp
    kia `409`; `SUM(personal_file.size)` cuối cùng **không** vượt
    `storage_quota_bytes` — case biên "upload song song vượt hạn mức".
  - Tệp cá nhân **không** xuất hiện trong bất kỳ kết quả `GET /documents`
    hoặc endpoint tìm kiếm kho chung nào (query riêng, xác nhận bảng
    `document` không có row nào trỏ tới `storage_key`/`sha256` của tệp cá
    nhân vừa tạo) — Story 1 AC-4.
  - Tệp nhiễm mã độc (EICAR hoặc mock `ClamdService`) → `422
    MALWARE_DETECTED`, không có `personal_file` row.
  - `GET /personal/quota` sau khi role tài khoản đổi nhưng **không** tạo
    lại tài khoản → hạn mức **không đổi** (đọc thẳng `account.storage_quota_bytes`
    hiện tại, không tính lại theo vai trò mới) — khớp Story 4 AC-3, phối
    hợp với test tương ứng ở `account-lifecycle.md`.

## 6. Task breakdown

- [x] Task 1 (**phụ thuộc `account-lifecycle.md` Task 1** — bảng `account` +
      `Account`/`AccountRepository` phải tồn tại trước): xác nhận
      `AccountsService.getOrCreateAccountRow()` khả dụng để tái dùng ở Task 5;
      không viết lại logic get-or-create.
- [x] Task 2 (**phụ thuộc `data-ingestion.md` Task 1–2** — bảng `upload_batch`
      + entity `UploadBatch`/`UploadBatchRepository` phải tồn tại trước):
      xác nhận cấu trúc cột thật khớp mục 2 spec này trước khi viết migration
      ALTER (nếu `data-ingestion.md` đổi tên cột lúc implement, cập nhật lại
      mục 2 ở đây trước).
- [x] Task 3: Migration `V11__personal_storage_quota.sql` (ALTER
      `upload_batch` thêm `kind`/`target_personal_folder_id`, ALTER
      `personal_file` thêm `batch_id` + index `INCLUDE(size)`) — phụ thuộc
      Task 1, 2.
- [x] Task 4: Cập nhật entity `UploadBatch` (field `kind`, `BatchKind` enum,
      `targetPersonalFolderId`), `PersonalFile` (field `batchId`).
- [x] Task 5: `PersonalService` — xoá lời gọi `MimeAllowlist.checkFormat`
      (bug fix); thay `personalProperties.quotaBytes()` bằng đọc
      `account.storage_quota_bytes` qua `AccountRepository`/
      `getOrCreateAccountRow` (Task 1); thêm `openBatch()`, `getBatch()`,
      `closeBatch()`, đổi `uploadFile()` → `uploadFileInBatch(batchId, ...)`
      theo mục 4.2/4.3 (advisory lock, KHÔNG dùng pattern `UPDATE ...
      RETURNING` của `data-ingestion.md`) — phụ thuộc Task 3, 4.
- [x] Task 5b: Thêm bước kiểm `ingest.max_file_size_bytes` vào
      `uploadFileInBatch` (mục 4.2 bước 2b) — đọc qua `SystemConfigService`,
      **không** thêm key mới; phụ thuộc `system-config.md` đã có key này
      (xem `data-ingestion.md` Task 0b).
- [x] Task 6: Xoá `PersonalProperties` + key `app.personal.quotaBytes` khỏi
      `application.yml`/`application-dev.yml` (breaking, dọn cấu hình chết).
- [x] Task 7: `PersonalController` — thêm `POST /personal/batches`,
      `POST /personal/batches/{id}/files`, `POST /personal/batches/{id}/close`,
      `GET /personal/batches/{id}`; xoá `POST /personal/files`; sửa
      `GET /personal/quota` đọc nguồn mới — phụ thuộc Task 5.
- [x] Task 8: FE `web/src/app/personal/page.tsx` + `web/src/lib/api.ts` —
      đổi luồng upload sang mở batch trước rồi gửi tệp kèm `batchId` (cùng
      mẫu hình đổi FE của `data-ingestion.md` Task 8 cho `/upload`), thêm
      xử lý lỗi `QUOTA_EXCEEDED` (đã có UI hiển thị `remainingBytes` từ
      trước, chỉ đổi field đọc từ response 413 cũ sang 409 mới) — phụ thuộc
      Task 7.
- [x] Task 9: Unit + integration test theo mục 5 — phụ thuộc Task 1–8.
- [x] Task 10: Cập nhật trạng thái task trong file spec này (đánh dấu done)
      sau khi implement — không viết lại toàn bộ spec.

## 7. Rủi ro kỹ thuật

- ⚠️ **Phụ thuộc chuỗi cứng vào 2 spec Draft cùng lúc** (`account-lifecycle.md`
  bảng `account`, `data-ingestion.md` bảng `upload_batch`) — nặng hơn 3 spec
  kia (mỗi spec đó chỉ phụ thuộc 1 spec Draft khác). Migration/Task 3 của
  file này **không thể bắt đầu** tới khi cả 2 đã `Approved` và có schema
  thật khớp mục 2. Nếu 1 trong 2 đổi tên cột lúc implement (vd.
  `upload_batch.owner_sub`, `account.storage_quota_bytes`), phải cập nhật
  lại spec này trước khi code.
- ~~Xung đột số hiệu migration V7~~ → **đã giải quyết**: spec này nhận
  `V11`, là migration cuối của chuỗi phụ thuộc (ALTER 2 bảng do `V8` và
  `V10` tạo) — xem bảng thứ tự ở đầu file. Rủi ro còn lại: merge lệch thứ
  tự sẽ làm Flyway fail (`out-of-order` không bật).
- **Race condition hạn mức:** đã giảm thiểu bằng
  `pg_advisory_xact_lock(hashtext(owner_sub))` bao trùm cả read-SUM lẫn
  insert trong 1 transaction (mục 4.2.7/4.5) — đây là cơ chế **đã có sẵn
  trong code hiện tại** trước cả spec này, chỉ mở rộng để cộng dồn thêm
  batch counter cùng lúc. Rủi ro còn lại: lock theo `hashtext(owner_sub)`
  có xác suất (rất nhỏ) đụng độ hash với `owner_sub` khác — chấp nhận được
  (đã là hành vi hiện tại, không phải rủi ro mới do spec này gây ra).
- **Story 4 AC-3 không hồi tố:** đổi giá trị mặc định vai trò ở
  `system-config` (hoặc chỉnh quota riêng qua `account-lifecycle.md` Story 5)
  **không** tự áp dụng cho tài khoản cũ — vì spec này chỉ **đọc**
  `account.storage_quota_bytes` tại thời điểm request, không có cơ chế nào
  ở đây tính lại/ghi đè. Không có thao tác "áp lại hàng loạt" trong MVP
  (đúng phạm vi BA đã loại trừ) — nếu sau này cần, đó là việc của
  `account-lifecycle.md`, không phải spec này.
- **Đã tìm thấy mâu thuẫn với hành vi code hiện tại (không phải giữa 2
  spec):** `PersonalService.uploadFile()` hiện tại gọi
  `MimeAllowlist.checkFormat(...)`, trái với Story 1 AC-2 của chính story
  này. Spec này coi đây là lỗi cần sửa (mục 1, Task 5) — không phải một
  quyết định kiến trúc mới cần bàn thêm.
- **Object mồ côi khi tiến trình chết giữa bước 6 và 7** (Quyết định 1
  mục 0 — chấp nhận khác `data-ingestion.md`): bytes đã lên SeaweedFS
  nhưng chưa có row `personal_file`, nên không ai thấy và **không** tính
  vào `usedBytes` (vốn là `SUM(personal_file.size)`). Người dùng không mất
  dữ liệu (tải lại tạo `storage_key` mới), nhưng rác chiếm chỗ thật.
  Khác kho chung ở chỗ: bên đó row `pending` truy được bằng SQL, còn ở đây
  **không có dấu vết nào trong DB** — muốn dọn phải quét bucket
  `personalBucket` rồi đối chiếu `personal_file.storage_key`. Giảm thiểu:
  log `storageKey` trước khi `putObject`; gom vào cùng script/SOP dọn rác
  với kho chung, đừng viết riêng.
- ⚠️ **Kho cá nhân của tài khoản `disabled` bị đóng băng vĩnh viễn —
  khoảng trống nghiệp vụ đã biết** (Quyết định 3 mục 0). Story 1 AC-1 cấm
  mọi người khác truy cập, **kể cả R3**; `account-lifecycle.md` Story 4 lại
  không cho xóa cứng tài khoản. Hệ quả tổ hợp: khi một người nghỉ việc,
  tệp cá nhân của họ **không ai đọc được, không ai bàn giao được, không ai
  xóa được**, và vẫn chiếm dung lượng lưu trữ vật lý mãi mãi.
  Đây là quyết định **có ý thức** (giữ đúng AC-1, không tự phát minh cơ chế
  ngoài phạm vi), không phải sơ suất. Cần **một story riêng** cho việc bàn
  giao/lưu trữ dữ liệu cá nhân khi nghỉ việc — nêu ở đây để BA không quên;
  spec này không tự giải quyết.
- **Chưa có thùng rác cho `personal_file`** (Quyết định 4 mục 0): xóa tệp
  cá nhân hiện là **xóa cứng**, khác kho chung vốn vừa có
  `document.deleted_at`. Chủ đích, vì không AC nào của story này yêu cầu.
  Hệ quả tiện lợi ở hiện tại: `usedBytes = SUM(personal_file.size)` luôn
  đúng, không phải bàn "tệp trong thùng rác có tính vào hạn mức không".
  **Khi FR-26/27 được đặc tả**, phải quyết cùng lúc cho cả 2 vùng và trả
  lời đúng câu đó — nếu thùng rác cá nhân tính vào hạn mức thì người dùng
  hết chỗ mà không hiểu vì sao; nếu không tính thì dung lượng vật lý thật
  bị dùng mà không ai chịu trách nhiệm. Đừng chọn khác nhau giữa 2 vùng chỉ
  vì implement ở 2 thời điểm khác nhau.
- **Không tìm thấy mâu thuẫn schema/giá trị** giữa file này với
  `account-lifecycle.md` (cùng cột `storage_quota_bytes`, cùng bảng
  `account`) hay với `system-config.md` (key `storage_quota.default.can_bo/
  lanh_dao/quan_tri`, R1=20GB/R2=50GB/R3=20GB — spec này không đọc trực
  tiếp các key đó, chỉ đọc `account.storage_quota_bytes` đã snapshot).
  Điểm cần theo dõi: nếu `data-ingestion.md` đổi ý nghĩa cột
  `upload_batch.target_folder_id` (vd. thêm `ON DELETE CASCADE` khác với
  giả định `SET NULL`/không đổi ở đây) thì ràng buộc
  `CK_upload_batch_kind_target` ở mục 2 phải rà soát lại theo.
