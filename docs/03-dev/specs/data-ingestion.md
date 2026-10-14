# Tech Spec: data-ingestion

> File: `docs/03-dev/specs/data-ingestion.md` — Owner: DEV
> Nguồn story: `docs/02-ba/stories/data-ingestion.md`
> Status: Draft

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
> | 4 | `data-ingestion` ⬅ **spec này** | `V10__data_ingestion.sql` |
> | 5 | `personal-storage-quota` | `V11__personal_storage_quota.sql` |
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
| 1 | Unique index chống trùng đường dẫn không tính cột `deleted_at` của `V9` | **Thêm `AND deleted_at IS NULL`** vào cả partial unique index lẫn bước nhận diện trùng (mục 4.2 bước 3). Tệp trong thùng rác không giữ chỗ đường dẫn nữa. |
| 2 | Object mồ côi trên SeaweedFS khi tiến trình chết giữa `putObject` và `save` | **Đảo thứ tự: insert row `pending` trước → `putObject` → update `clean`.** Tận dụng giá trị `pending` đã có sẵn trong `DocumentStatus` nhưng chưa dùng. Rác trở thành row tìm được bằng SQL thay vì object vô hình. |
| 3 | Không có giới hạn dung lượng từng tệp; ClamAV timeout hard-code 120s | **Thêm key `ingest.max_file_size_bytes`** (`system_config`, R3 chỉnh được) + **đưa `SOCKET_TIMEOUT_MS` thành tham số cấu hình**. Chặn sớm bằng con số rõ ràng thay vì để người dùng chờ rồi nhận `503`. |
| 4 | Batch quá hạn chỉ kiểm tra lazy, không có job dọn | **Chấp nhận cho MVP** — nợ kỹ thuật có chủ đích. Rác tích lũy rất chậm và không ảnh hưởng đúng đắn nghiệp vụ; thêm `@Scheduled` sau nếu bảng phình. |

> ⚠️ **Quyết định 3 tạo phụ thuộc ngược lên `system-config.md`**: key
> `ingest.max_file_size_bytes` là key **mới**, chưa có trong danh mục của
> spec đó (`V7`). Phải bổ sung vào `system-config.md` trước khi implement
> Task 4/5 ở đây, nếu không `SystemConfigService.getLong` sẽ không có giá
> trị để đọc.

## 1. Tóm tắt kỹ thuật

- Thay luồng upload đơn `POST /documents` (multipart, `DocumentsController`/
  `DocumentsService.ingest()` hiện có) bằng luồng **batch 2 bước**: client mở
  batch (`POST /ingest/batches`) rồi gửi từng tệp kèm `batchId` + đường dẫn
  tương đối (`POST /ingest/batches/{batchId}/files`) — dùng cho **cả** tệp lẻ
  (Story 1, batch 1 tệp) lẫn thư mục (Story 2), để có **một** pipeline kiểm
  tra/giới hạn duy nhất. **Breaking change**: bỏ endpoint `POST /documents`
  hiện có ở `DocumentsController`, FE `web/src/app/upload/page.tsx` phải viết
  lại theo batch API (xem mục 3).
- Đảo lại thứ tự kiểm tra trong `DocumentsService.ingest()` hiện có: code cũ
  tính `sha256` **trước** `MimeAllowlist.checkFormat` (dòng 93/95-96 hiện
  tại) — spec này đổi thành **định dạng → SHA-256 → ClamAV** (Story 3 AC-1),
  đồng thời nới `document.sha256` thành nullable vì tệp bị từ chối do sai
  định dạng không còn được tính hash (AC-2).
- Bảng `allowed_file_format` thay `MimeAllowlist.java` (chuyển vai trò
  `MimeAllowlist` thành seed data + fallback khi Redis/DB lỗi), cache bằng
  Redis (dùng chung hạ tầng Redis với `system-config.md` — xem Task hạ tầng,
  chỉ dựng 1 lần).
- Bảng `upload_batch` (mới) làm nguồn xác thực server-side cho 2 ngưỡng
  `ingest.max_files_per_batch`/`ingest.max_batch_size_bytes` (đọc qua
  `SystemConfigService.getLong`, xem `system-config.md`), cộng dồn **thực
  nhận** bằng 1 câu `UPDATE ... WHERE ... RETURNING` atomic (an toàn
  concurrency, không cần lock ứng dụng).
- Gọi `WarehouseCapacityService.assertIngestAllowed()` /
  `recomputeAfterWrite()` (đã định nghĩa interface ở `warehouse-structure.md`
  mục 3) đúng 2 điểm: mở batch, và đầu mỗi lượt nhận tệp (pre-check) + ngay
  sau khi lưu `document` (post-check) — xem mục 4.2.
- Mở rộng `document`: thêm `relative_path`, nới `sha256` nullable, thêm
  unique index phục vụ nhận diện trùng khi tải lại (Story 4 AC-2).
- Hạ tầng: Redis **chưa có** trong repo — cần thêm vào
  `docker-compose.yml`/`docker-compose.dev.yml` và
  `backend/pom.xml` (đã xác nhận, xem BA + rà soát dưới đây); phối hợp với
  `system-config.md` để không thêm trùng.

## 2. Data model

```sql
-- V10__data_ingestion.sql
-- (Thứ tự đã chốt: sau V9 warehouse_structure_v2 — spec này gọi
--  WarehouseCapacityService do warehouse-structure.md định nghĩa. Xem
--  bảng thứ tự triển khai ở đầu file.)

-- 2.1 Allowlist định dạng (Story 5 AC-1) — seed từ MimeAllowlist.ALLOWED/MAGIC
-- hiện có (28 mime type: văn bản/office, ảnh, âm thanh, video).
CREATE TABLE allowed_file_format (
    mime_type    VARCHAR(255) PRIMARY KEY,
    extensions   VARCHAR(20)[] NOT NULL,
    category     VARCHAR(32) NOT NULL, -- TEXT_DOC | IMAGE | AUDIO | VIDEO
    magic_bytes  JSONB, -- [{"offset":0,"hex":"89504E47"}, ...]; NULL = không
                         -- kiểm chữ ký (text/plain, text/csv, video/mpeg —
                         -- giống MimeAllowlist.MAGIC hiện tại không có entry)
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by_sub      VARCHAR(255),
    updated_by_username VARCHAR(255)
);
-- INSERT 28 dòng seed, 1-1 với Map ALLOWED/MAGIC trong MimeAllowlist.java
-- hiện tại (copy nguyên giá trị, không đổi danh mục).

-- 2.2 Batch tải lên (Story 2 AC-3) — 1 batch = 1 lượt "mở form tải" của
-- client (1 tệp lẻ hoặc cả thư mục), gắn 1 thư mục đích duy nhất.
CREATE TABLE upload_batch (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    target_folder_id      UUID NOT NULL REFERENCES warehouse_folder (id),
    owner_sub             VARCHAR(255) NOT NULL,
    owner_username        VARCHAR(255) NOT NULL,
    declared_file_count   INT NOT NULL CHECK (declared_file_count > 0),
    declared_total_bytes  BIGINT NOT NULL CHECK (declared_total_bytes >= 0),
    actual_file_count     INT NOT NULL DEFAULT 0,
    actual_total_bytes    BIGINT NOT NULL DEFAULT 0,
    status                VARCHAR(16) NOT NULL DEFAULT 'OPEN'
        CHECK (status IN ('OPEN','CLOSED','LIMIT_EXCEEDED','EXPIRED')),
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at            TIMESTAMPTZ NOT NULL DEFAULT now() + INTERVAL '24 hours'
);
CREATE INDEX IDX_upload_batch_owner_status ON upload_batch (owner_sub, status);
-- Dọn batch cũ: KHÔNG cần job riêng cho MVP — hết hạn kiểm tra "lazy" (mục
-- 4.3); có thể thêm @Scheduled xoá CLOSED/EXPIRED > 30 ngày sau nếu bảng
-- phình to (Task tuỳ chọn, không chặn Story nào).

-- 2.3 Document: đường dẫn tương đối trong thư mục đích (Story 2 AC-1, Story 4
-- AC-2) + nới sha256 nullable (Story 3 AC-2: tệp sai định dạng không tính hash).
ALTER TABLE document ADD COLUMN relative_path VARCHAR(1024) NOT NULL DEFAULT '';
ALTER TABLE document ALTER COLUMN sha256 DROP NOT NULL;

-- Nhận diện trùng khi tải lại = (folder_id, relative_path, original_name)
-- CHỈ tính tệp đã "hoàn tất" (status='clean' — Document.status hiện dùng
-- chữ thường theo DocumentStatus.java, không phải 'CLEAN' như minh hoạ ở
-- warehouse-structure.md). Partial unique index (chỉ chặn trùng ở tệp clean,
-- REJECTED/INFECTED được phép có nhiều bản ghi cho cùng đường dẫn — mỗi lần
-- thử lại là 1 evidence row riêng, đúng NFR-09 append-only).
-- `deleted_at IS NULL` (cột do V9/warehouse-structure.md tạo): tệp đã ở
-- thùng rác KHÔNG giữ chỗ đường dẫn, người dùng tải lại được bình thường
-- (Quyết định 1 mục 0). Điều kiện này phải khớp đúng với bước nhận diện
-- trùng ở mục 4.2 bước 3 — hai nơi lệch nhau sẽ sinh lỗi ghi 500 do vi
-- phạm constraint mà tầng service tưởng là hợp lệ.
CREATE UNIQUE INDEX UQ_document_folder_relpath_name_clean
    ON document (folder_id, relative_path, original_name)
    WHERE status = 'clean' AND deleted_at IS NULL;
```

> ⚠️ **Phụ thuộc thứ tự bắt buộc:** index này tham chiếu `document.deleted_at`
> do `V9` (`warehouse-structure.md`) tạo, nên `V10` **không thể** merge
> trước `V9`. Đã khớp với bảng thứ tự ở đầu file.

## 3. API contract

Base path mới: `/ingest`. Toàn bộ yêu cầu `hasRole('CAN_BO')` trừ chỗ ghi rõ.
**Breaking**: xoá `POST /documents` (multipart) khỏi `DocumentsController`;
`GET /documents`, `GET /documents/{id}`, `GET /documents/{id}/content` giữ
nguyên.

| Method | Endpoint | Request | Response |
|---|---|---|---|
| GET | `/ingest/limits` | – | `200 {maxFilesPerBatch, maxBatchSizeBytes, maxFileSizeBytes}` — đọc `SystemConfigService`, cho client tự pre-check (Story 2 AC-3a); không cần role admin vì 3 key này không nhạy cảm. `maxFileSizeBytes` là key mới theo Quyết định 3 mục 0 — FE dùng để chặn sớm từng tệp, không chỉ chặn theo tổng batch. |
| GET | `/ingest/allowed-formats` | – | `200 [{mimeType, extensions}]` — cho FE dựng `accept` attribute động thay vì hard-code (Story 5). |
| POST | `/ingest/batches` | `{targetFolderId, declaredFileCount, declaredTotalBytes}` | `201 {batchId, status:"OPEN", expiresAt}`. Lỗi: `404` folder không tồn tại; `409 {code:"BATCH_LIMIT_EXCEEDED"}` nếu khai báo vượt `ingest.max_files_per_batch`/`max_batch_size_bytes`; `409 {code:"WAREHOUSE_CAPACITY_EXCEEDED"}` nếu kho ≥ ngưỡng chặn (gọi `WarehouseCapacityService.assertIngestAllowed()`). |
| POST | `/ingest/batches/{batchId}/files` | multipart: `file`, `relativePath` (optional, mặc định `""`), `note` (optional) | `201 {status:"INGESTED", document, duplicateOf}` — tệp sạch, vào kho. `200 {status:"SKIPPED", reason:"ALREADY_EXISTS", documentId}` — đã tồn tại (Story 4 AC-2). Lỗi tệp: `422 {code:"INVALID_FORMAT", message}`; `422 {code:"FILE_TOO_LARGE", message}` (vượt `ingest.max_file_size_bytes`, Quyết định 3 mục 0); `422 {code:"MALWARE_DETECTED", message}`; `409 {code:"BATCH_LIMIT_EXCEEDED"}` (thực nhận vượt ngưỡng); `409 {code:"WAREHOUSE_CAPACITY_EXCEEDED"}`; `503 {code:"SCANNER_UNAVAILABLE"}`; `503 {code:"ALLOWLIST_UNAVAILABLE"}` (Redis + DB cùng lỗi — fail-closed). Lỗi batch: `404 BATCH_NOT_FOUND`; `409 {code:"BATCH_CLOSED"}` / `409 {code:"BATCH_EXPIRED"}`. |
| POST | `/ingest/batches/{batchId}/close` | – | `200 {batchId, status:"CLOSED", summary:{filesAccepted, filesSkipped, filesRejected, actualTotalBytes}}` — tổng hợp tiện cho UI, không bắt buộc gọi (client có thể để batch tự hết hạn). |
| GET | `/ingest/batches/{batchId}` | – | `200 {batchId, status, declaredFileCount, declaredTotalBytes, actualFileCount, actualTotalBytes, expiresAt}` — Story 2 AC-3 truy vấn tiến trình. |
| GET | `/admin/allowed-file-formats` | – | `200 [{mimeType, extensions, category, updatedAt}]` — R3, Story 5. |
| POST | `/admin/allowed-file-formats` | `{mimeType, extensions[], category, magicBytes?}` | `201` bản ghi mới. `409 CONFLICT` nếu `mimeType` đã tồn tại. R3, ghi Redis đồng bộ + audit (Story 5 AC-3/AC-4). |
| DELETE | `/admin/allowed-file-formats/{mimeType}` | – | `204`. R3, ghi Redis đồng bộ + audit. |

Mọi lỗi dùng format chung `{code, message}` (đồng bộ
`com.aismartstore.backend.common`, thêm subtype `ConflictException`
`code=BATCH_LIMIT_EXCEEDED|WAREHOUSE_CAPACITY_EXCEEDED|BATCH_CLOSED|
BATCH_EXPIRED`, `UnprocessableEntityException`
`code=INVALID_FORMAT|MALWARE_DETECTED`, `ServiceUnavailableException`
`code=SCANNER_UNAVAILABLE|ALLOWLIST_UNAVAILABLE`).

## 4. Luồng xử lý chính

### 4.1 Mở batch (Story 2 AC-3b, Story 5 AC-5 pre-check)

1. Validate `targetFolderId` tồn tại (404 nếu không).
2. `declaredFileCount > SystemConfigService.getLong("ingest.max_files_per_batch")`
   hoặc `declaredTotalBytes > getLong("ingest.max_batch_size_bytes")` →
   `409 BATCH_LIMIT_EXCEEDED`, không tạo batch.
3. `WarehouseCapacityService.assertIngestAllowed()` (từ
   `warehouse-structure.md`) → throw nếu kho ≥ ngưỡng chặn → `409
   WAREHOUSE_CAPACITY_EXCEEDED`, không tạo batch.
4. `INSERT upload_batch(status='OPEN', ...)`, trả `batchId`.

### 4.2 Nhận 1 tệp trong batch (Story 1/2/3/4, thứ tự cố định)

1. Load batch; `status != 'OPEN'` hoặc `now() > expires_at` → cập nhật
   `EXPIRED` nếu hết hạn, trả `404`/`409 BATCH_CLOSED`/`409 BATCH_EXPIRED`
   tương ứng.
2. **Pre-check kho (lại, mỗi tệp)** — `assertIngestAllowed()`: kho có thể
   vượt 95% **giữa batch** do các tệp khác/batch khác ghi xong
   (`recomputeAfterWrite()` ở bước 8 cập nhật snapshot mới nhất) → nếu
   `BLOCKED`, từ chối **tệp này** (`409 WAREHOUSE_CAPACITY_EXCEEDED`), batch
   và các tệp đã nhận trước đó giữ nguyên, không đóng batch.
3. **Nhận diện trùng (Story 4 AC-2)** — trước cả format/hash/scan vì đây là
   kiểm tra rẻ nhất (chỉ 1 query, không đọc nội dung tệp), đúng tinh thần
   "rẻ trước, đắt sau" của Story 3 AC-1: `SELECT ... FROM document WHERE
   folder_id=:targetFolderId AND relative_path=:relativePath AND
   original_name=:name AND status='clean' AND deleted_at IS NULL`. Có → trả
   `200 SKIPPED ALREADY_EXISTS`, **không** cộng dồn vào batch, **không**
   chạy pipeline.
   - `deleted_at IS NULL` **bắt buộc khớp đúng** điều kiện của
     `UQ_document_folder_relpath_name_clean` (mục 2.3, Quyết định 1 mục 0):
     tệp đang ở thùng rác không tính là "đã tồn tại", nên người dùng tải
     lại được và bản mới không đụng constraint.
3b. **Giới hạn dung lượng tệp (Quyết định 3 mục 0)** — so `file.size` với
   `SystemConfigService.getLong("ingest.max_file_size_bytes")`; vượt →
   `422 {code:"FILE_TOO_LARGE"}`, **không** cộng dồn vào batch (đặt trước
   bước 4 có chủ đích: không tiêu quota batch cho tệp chắc chắn bị từ
   chối), không chạy pipeline. Kiểm ở đây vì chỉ cần đọc metadata
   multipart, chưa phải đọc nội dung.
4. **Giữ chỗ ngưỡng batch (Story 2 AC-3, atomic, an toàn concurrency)**:
   ```sql
   UPDATE upload_batch
   SET actual_file_count = actual_file_count + 1,
       actual_total_bytes = actual_total_bytes + :fileSize,
       updated_at = now()
   WHERE id = :batchId AND status = 'OPEN'
     AND actual_file_count + 1 <= :maxFiles
     AND actual_total_bytes + :fileSize <= :maxBytes
   RETURNING actual_file_count, actual_total_bytes;
   ```
   0 dòng trả về → `UPDATE upload_batch SET status='LIMIT_EXCEEDED' WHERE
   id=:batchId` (idempotent), trả `409 BATCH_LIMIT_EXCEEDED`; các tệp đã
   nhận trước đó giữ nguyên (AC-3), tệp mới trong batch bị từ chối luôn từ
   đây. `:maxFiles`/`:maxBytes` = giá trị `system_config` hiện tại (đọc lại
   mỗi request, không cache trong batch — cho phép R3 đổi ngưỡng giữa
   chừng).
5. **Định dạng** — đọc allowlist qua `AllowlistService.checkFormat(...)`
   (đọc Redis, mục 4.3), sai → `persistFailure(status='rejected',
   sha256=null)`, `422 INVALID_FORMAT`. **Dừng ở đây, không sang bước 6/7.**
6. **SHA-256** — tính hash toàn bộ tệp.
7. **ClamAV** — `ClamdService.scanStream`; nhiễm → `persistFailure(status=
   'infected', sha256=<đã tính>)`, `422 MALWARE_DETECTED`. Scanner không
   sẵn sàng/timeout → `503 SCANNER_UNAVAILABLE` (giữ fail-closed hiện có ở
   `ClamdService.ping()`).
   - **Timeout nay là tham số cấu hình** (Quyết định 3 mục 0): đưa
     `SOCKET_TIMEOUT_MS` (đang hard-code 120s trong `ClamdService`) ra
     `application.yml` (`app.clamav.socket-timeout-ms`) để chỉnh theo trần
     `ingest.max_file_size_bytes` mà không phải sửa code. Đặt giá trị sao
     cho quét hết một tệp bằng đúng trần đó vẫn kịp — cần đo thực tế trên
     hạ tầng thật, spec không đoán số.
8. **Lưu — 3 bước, DB trước object** (Quyết định 2 mục 0, **đảo** so với
   code hiện tại):
   - **8a.** `documentRepository.save(document{status='pending',
     relative_path, sha256, storage_key=<UUID đã sinh>, size, ...})` và
     commit. Dùng đúng giá trị `DocumentStatus.PENDING` đã có sẵn nhưng
     chưa nơi nào dùng — không thêm enum mới.
   - **8b.** `objectStorageService.putObject(storageKey, ...)` lên
     SeaweedFS. Lỗi → xoá row `pending` vừa tạo (best-effort, cùng
     `storageKey` nên không đụng dữ liệu khác) rồi rethrow; nếu xoá cũng
     lỗi thì để nguyên row `pending`, nó là dấu vết truy được bằng SQL.
   - **8c.** `UPDATE document SET status='clean' WHERE id=?` — đây mới là
     thời điểm tệp "hoàn tất" theo Story 4 AC-2; audit `document.create`;
     note nếu có.
   - **Vì sao đảo thứ tự:** nếu tiến trình chết giữa 8a và 8c, thứ còn lại
     là một row `pending` **tìm được bằng một câu SQL**, thay vì một object
     vô hình trên SeaweedFS không ai biết tồn tại. Row `pending` không lọt
     vào bất kỳ đường nào khác: bước 3 chỉ tính `clean` nên không chặn tải
     lại; `usedBytes` (mục 4.4 `warehouse-structure.md`) chỉ cộng `clean`
     nên không sai số dung lượng; unique index cũng chỉ áp trên `clean`.
9. **Post-check kho** — ngay sau `documentRepository.save(document)`:
   `WarehouseCapacityService.recomputeAfterWrite()` (vị trí đúng như
   `warehouse-structure.md` mục 4.4 bước 5, KHÔNG chặn tệp vừa ghi, chỉ cập
   nhật snapshot cho bước 2 của tệp/batch tiếp theo).
10. Trả `201 INGESTED` kèm `document` + `duplicateOf` (nội dung trùng —
    khác khái niệm "trùng vị trí" ở bước 3, giữ nguyên logic
    `findBySha256AndStatus` hiện có).

### 4.3 Cache allowlist Redis + fallback (Story 3 AC-6, Story 5 AC-3)

- Key `ingest:allowlist` (Redis STRING, JSON array toàn bộ danh mục), TTL 6h
  làm lớp dự phòng (đồng bộ tinh thần TTL dự phòng của `system-config.md`).
- Ghi: `POST`/`DELETE /admin/allowed-file-formats` → ghi DB (transaction) →
  đọc lại **toàn bộ** danh mục từ DB → ghi đè `ingest:allowlist` (đồng bộ,
  cùng request) → audit. Ghi đè cả danh mục (không patch từng phần) để loại
  bỏ đúng định dạng vừa gỡ (AC-3).
- Đọc (mỗi lượt upload, bước 5 mục 4.2): `AllowlistService.checkFormat`
  → Redis `GET ingest:allowlist` → nếu **hit**: dùng ngay (không đụng DB).
    → nếu **miss** (TTL hết/cache lạnh): đọc DB, ghi lại Redis, dùng kết quả
      DB.
    → nếu Redis **lỗi kết nối**: fallback đọc DB trực tiếp (KHÔNG allow-all)
      — vẫn kiểm tra đúng, chỉ chậm hơn.
    → nếu **cả Redis và DB đều lỗi**: **fail-closed** — từ chối toàn bộ
      upload (`503 ALLOWLIST_UNAVAILABLE`), tuyệt đối không coi "không đọc
      được allowlist" là "cho qua" (bảo mật ưu tiên, nhất quán với
      `ClamdService.ping()` fail-closed hiện có khi scanner không sẵn sàng).
- Nếu bảng `allowed_file_format` rỗng lúc khởi tạo hệ thống (chưa chạy seed)
  — coi như lỗi cấu hình, không fallback về `MimeAllowlist.java` tĩnh (bảng
  seed migration đã cover đủ 28 entry ban đầu).

### 4.4 Tải lại khi gián đoạn (Story 4)

- Không có API resume theo byte/chunk. Client (hàng đợi + retry backoff,
  FE) khi phát hiện lượt tải cũ dở dang: mở **batch mới** (mục 4.1) rồi gửi
  lại **toàn bộ danh sách tệp** dự định (kể cả tệp đã xong) — server tự lọc
  qua bước 3 mục 4.2 (đã tồn tại → skip, không tính vào ngưỡng batch mới,
  không tạo bản ghi trùng). Batch cũ (nếu còn `OPEN`) tự hết hạn theo
  `expires_at`, không cần API huỷ.
- "Hoàn tất" = tồn tại `document` với `status='clean'` **và**
  `deleted_at IS NULL` khớp `(folder_id, relative_path, original_name)`.
- Row `status='pending'` (mục 4.2 bước 8a) **không** phải "hoàn tất" và
  cũng không phải "trạng thái dở dang do người dùng thấy" — nó chỉ là dấu
  vết kỹ thuật của một lần ghi chưa xong, vô hình với mọi luồng nghiệp vụ.
  Tải lại sẽ tạo row mới và bỏ qua row `pending` cũ (khớp AC-2: đơn vị hoàn
  tất là cả tệp, không resume dở dang).

### 4.5 Concurrency

- Nhiều tệp cùng batch tải song song (nhiều request `/files` đồng thời): mỗi
  request có transaction DB riêng; cộng dồn counter dùng câu `UPDATE ...
  RETURNING` atomic ở bước 4 mục 4.2 (Postgres row lock trên `upload_batch`,
  không cần lock tầng ứng dụng, không có race "đọc-rồi-ghi").
- Nhiều batch khác nhau cùng ghi gần ngưỡng kho: `recomputeAfterWrite()`
  luôn tính lại bằng aggregate query (theo thiết kế `warehouse-structure.md`
  mục 4.4, không cache số liệu), nên lần `assertIngestAllowed()` kế tiếp của
  batch khác luôn thấy số mới nhất — chấp nhận vượt nhẹ ngưỡng nếu 2 tệp ghi
  đúng cùng thời điểm (giống rủi ro race đã nêu ở `warehouse-structure.md`,
  không lặp lại giải pháp ở đây).

## 5. Test plan

- **Unit test (`IngestPipelineTest`/`AllowlistServiceTest`):**
  - Sai định dạng → `checkFormat` fail sớm; mock `sha256`/`ClamdService`
    verify **không bị gọi** (đúng AC-2, chứng minh bằng mock, không chỉ bằng
    kết quả cuối).
  - Nhiễm mã độc → không gọi `objectStorageService.putObject`/
    `documentRepository.save` với `status='clean'`.
  - `AllowlistService`: Redis hit/miss/lỗi kết nối → đúng nguồn dữ liệu
    từng case; Redis + DB cùng lỗi → throw `ServiceUnavailableException`
    (fail-closed), không trả `true` mặc định.
  - Batch counter: `UPDATE ... RETURNING` không có dòng khi vượt ngưỡng →
    service trả `BATCH_LIMIT_EXCEEDED`, không tăng counter (test bằng cách
    đọc lại `actual_file_count` không đổi).
- **Integration test (`IngestControllerIT`, Testcontainers Postgres + Redis
  hoặc fake Redis theo pattern đã chọn ở `system-config.md`):**
  - Mở batch vượt ngưỡng khai báo (đếm/dung lượng) → `409
    BATCH_LIMIT_EXCEEDED`, không tạo `upload_batch`.
  - Batch hợp lệ, gửi N tệp trong đó 1 tệp sai định dạng → tệp lỗi trả
    `422`, **N-1 tệp còn lại vẫn** `201 INGESTED` (AC-2 Story 2 — 1 tệp lỗi
    không chặn tệp khác).
  - Gửi tệp vượt ngưỡng thực nhận (dù khai báo ban đầu hợp lệ, ví dụ do
    dung lượng thực tế lớn hơn ước tính client) → tệp đó `409
    BATCH_LIMIT_EXCEEDED`, các tệp trước giữ nguyên trong kho.
  - Kho đạt ngưỡng chặn **giữa batch** (giả lập bằng cách hạ
    `warehouse.max_capacity_bytes` sau khi batch đã nhận vài tệp) → tệp
    tiếp theo trong cùng batch bị `409 WAREHOUSE_CAPACITY_EXCEEDED`, tệp đã
    nhận trước đó không bị ảnh hưởng.
  - Tải lại tệp đã có bản ghi `clean` cùng
    `(folder_id, relative_path, originalName)` → `200 SKIPPED
    ALREADY_EXISTS`, không tạo document mới, không cộng vào batch counter.
  - Tải lại tệp trước đó bị `rejected`/`infected` (chưa có bản ghi `clean`)
    → xử lý lại **từ đầu** qua đủ pipeline (không bị coi là trùng).
  - **Tải lại tệp mà bản `clean` cũ đã ở thùng rác** (`deleted_at NOT
    NULL`) → **không** bị coi là trùng, đi hết pipeline và tạo bản ghi mới
    thành công **không vi phạm unique index** (Quyết định 1 mục 0). Test
    này chốt việc điều kiện ở mục 4.2 bước 3 và ở index khớp nhau.
  - **Tệp vượt `ingest.max_file_size_bytes`** → `422 FILE_TOO_LARGE`, và
    `actual_file_count`/`actual_total_bytes` của batch **không đổi** (đọc
    lại để xác nhận), pipeline không chạy (mock `checkFormat` verify không
    bị gọi) — Quyết định 3 mục 0.
  - **Chết giữa chừng khi lưu** (mock `putObject` throw): row `pending`
    không được tính là "đã tồn tại" ở lần tải lại; `usedBytes` không cộng
    row `pending`; lần tải lại thành công tạo bản ghi `clean` bình thường —
    Quyết định 2 mục 0.
  - Redis down khi upload → allowlist đọc fallback DB, upload vẫn thành
    công đúng luật; Redis + DB cùng down (giả lập) → `503
    ALLOWLIST_UNAVAILABLE`.
  - ClamAV timeout/không phản hồi → `503 SCANNER_UNAVAILABLE`, không có
    `document` nào được tạo cho tệp đó (kể cả REJECTED — vì fail xảy ra
    trước khi có verdict để ghi nhận, khác case INFECTED).
  - R3 thêm/gỡ định dạng qua `/admin/allowed-file-formats` → upload ngay
    sau đó (không cần restart) phản ánh đúng danh mục mới — verify hiệu lực
    tức thời (Story 5 AC-3).
  - Non-`CAN_BO` gọi `/ingest/batches*` → 403; non-`QUAN_TRI` gọi
    `/admin/allowed-file-formats*` → 403.

- **Frontend (kéo-thả thư mục thay `webkitdirectory`):** xem mục 5 của
  `docs/_archive/2026-09-30-data-ingestion-web-drop.md` (đã archive, thay bằng slug `folder-upload-display-name`).

## 6. Task breakdown

- [x] Task 0 (hạ tầng, **chặn mọi task khác cần Redis**): đã có sẵn từ
      `system-config.md`/`account-lifecycle.md` (Redis trong
      `docker-compose*.yml`, `spring-boot-starter-data-redis` trong
      `backend/pom.xml`, `spring.data.redis.*` trong `application.yml`) —
      dùng chung, không thêm trùng.
- [x] Task 0b (**phối hợp `system-config.md`, làm trước Task 4/5**): key
      `ingest.max_file_size_bytes` đã có sẵn trong
      `SystemConfigDefaults.java` (2 GiB mặc định) khi bắt đầu task này —
      không cần thêm.
- [x] Task 1: Migration `V10__data_ingestion.sql` (bảng
      `allowed_file_format` + seed 25 dòng từ `MimeAllowlist.java` — con số
      "28" trong bản nháp spec là ước lượng, map thực tế có 25 entry, đã ghi
      chú trong migration —, bảng `upload_batch`, alter `document` thêm
      `relative_path` + nới `sha256` nullable + unique index **có
      `deleted_at IS NULL`**). **Deviation nhỏ (đã ghi chú trong file
      migration)**: thêm 3 cột `accepted_file_count`/`skipped_file_count`/
      `rejected_file_count` vào `upload_batch` ngoài danh mục cột ở mục 2.2 —
      cần để hiện thực đúng `summary` trả về ở `POST
      /ingest/batches/{id}/close` (mục 3), thứ mà `actual_file_count`/
      `actual_total_bytes` một mình không tính ra được (một tệp bị skip
      không hề chạm `actual_file_count`, còn một tệp bị reject sau bước 4 có
      chạm). Không ảnh hưởng migration order, index, hay bất kỳ quyết định
      nào ở mục 0.
- [x] Task 2: Entity `AllowedFileFormat`, `UploadBatch`, repository JPA
      tương ứng. **Deviation nhỏ**: `UploadBatchRepository.reserve()` dùng
      `@Modifying` trả `int` (rowcount) thay vì `UPDATE ... RETURNING` —
      atomic tính đúng như mô tả, chỉ khác là không lấy lại
      `actual_file_count`/`actual_total_bytes` mới nhất trong cùng round
      trip (không có downstream nào cần giá trị đó ngay lập tức).
- [x] Task 3: `AllowlistService` (`backend/.../scanning/AllowlistService.java`)
      — đọc Redis→DB→fail-closed (mục 4.3), `checkFormat(...)` thay thế lời
      gọi `MimeAllowlist.checkFormat` cũ trong pipeline (giữ
      `MimeAllowlist.java` làm nguồn seed, không xoá).
- [x] Task 4: `UploadBatchService` — `open()`, `reserve()`, `close()`,
      `getStatus()`, lazy-expire khi đọc.
- [x] Task 5: Viết lại `DocumentsService.ingest()` → `ingestBatchFile(...)`
      nhận `batchId`/`relativePath`; đúng thứ tự bước 1–10 mục 4.2, gồm cả 3
      thay đổi nêu trong task này.
- [x] Task 5b: Đưa timeout ClamAV ra cấu hình. **Deviation nhỏ**: key thực
      tế là `app.scanning.socket-timeout-ms` (không phải
      `app.clamav.socket-timeout-ms` như bản nháp) — khớp prefix
      `app.scanning` đã dùng sẵn cho `clamd-host`/`clamd-port` trong
      `ScanningProperties`/`application.yml`, tránh tạo thêm một prefix cấu
      hình rời rạc thứ hai. Giữ mặc định 120000ms hiện tại; **chưa đo trên
      hạ tầng thật** (đúng như rủi ro đã nêu ở mục 7) — việc chốt giá trị
      theo `ingest.max_file_size_bytes` thực tế vẫn còn treo, để lại cho khi
      có hạ tầng ClamAV thật.
- [x] Task 6: `IngestController` (`backend/.../ingest/IngestController.java`,
      base `/ingest`) — đủ 6 endpoint. Đã xoá `POST /documents` khỏi
      `DocumentsController`.
- [x] Task 7: `AllowedFileFormatController` — GET/POST/DELETE, ghi Redis
      đồng bộ + audit.
- [x] Task 8: FE `web/src/app/upload/page.tsx` viết lại theo batch API (mở
      batch, chọn tệp lẻ qua nút; tải thư mục qua kéo-thả, pre-check
      `GET /ingest/limits`, hàng đợi tuần tự + retry backoff cho lỗi 503,
      hiển thị trạng thái từng tệp kể cả `SKIPPED`).
- [x] Task 9: FE admin `web/src/app/admin/allowed-formats/page.tsx` (mới) +
      gắn tab vào `AdminTabs.tsx`.
- [x] Task 10: Unit + integration test theo mục 5 — `UploadBatchServiceTest`
      (11 case), `AllowlistServiceTest` (7 case), `IngestControllerIT` (13
      case, Testcontainers Postgres+Redis). Một số case ở mục 5 chỉ có cover
      unit-level chứ không lặp lại ở IT (Redis down/DB down cùng lúc →
      503 ALLOWLIST_UNAVAILABLE) vì đã được `AllowlistServiceTest` verify
      trực tiếp và khó mô phỏng tin cậy bằng Testcontainers.
- [x] Task 11: Task breakdown trong file này đã cập nhật (mục này).
- [ ] Task 12 (FE, bỏ `webkitdirectory` → kéo-thả thư mục): tách sang
      `docs/_archive/2026-09-30-data-ingestion-web-drop.md` (đã archive, thay bằng slug `folder-upload-display-name`) (task breakdown mục 6 của file đó).

## 7. Rủi ro kỹ thuật

- **Kéo-thả thư mục (Task 12)**: xem mục 7 của `docs/_archive/2026-09-30-data-ingestion-web-drop.md` (đã archive, thay bằng slug `folder-upload-display-name`).

- ⚠️ **Redis là hạ tầng hoàn toàn mới** (chưa có trong `docker-compose`/
  `pom.xml`) — trùng nhu cầu với `system-config.md`; nếu 2 PR cùng thêm sẽ
  conflict. Cần xác nhận ai làm Task 0 trước, PR sau chỉ tái sử dụng.
- ~~Xung đột số hiệu migration V7~~ → **đã giải quyết**: spec này nhận
  `V10`, merge sau `warehouse-structure` (`V9`) — xem bảng thứ tự ở đầu
  file. Rủi ro còn lại: merge lệch thứ tự sẽ làm Flyway fail
  (`out-of-order` không bật).
- ⚠️ **Phụ thuộc chéo vào 2 spec đang Draft**: `WarehouseCapacityService`
  (interface + 2 method) và `SystemConfigService.getLong` đều do
  `warehouse-structure.md`/`system-config.md` định nghĩa, cả hai hiện
  **Draft**, chưa implement. Task 4/5 (mục 6) không thể bắt đầu tới khi 2
  spec đó Approved và có code khả dụng — nếu tên method/chữ ký đổi khi
  implement, spec này phải cập nhật theo, không tự suy diễn thêm.
- ~~Orphan object vô hình trên SeaweedFS~~ → **đã giảm mạnh** (Quyết định
  2 mục 0): đảo thành `pending` → `putObject` → `clean`, nên tiến trình
  chết giữa chừng để lại một row `pending` **truy được bằng SQL**
  (`SELECT * FROM document WHERE status='pending' AND created_at < ...`)
  thay vì object không ai biết tồn tại.
  **Rủi ro còn lại:** (a) vẫn có thể còn object thật trên SeaweedFS ứng với
  row `pending` nếu chết đúng sau 8b — nhưng giờ luôn có `storage_key`
  trong DB để đối chiếu, nên script dọn là một câu join thay vì phải quét
  toàn bucket; (b) row `pending` tích lũy nếu không dọn — cùng nhóm nợ kỹ
  thuật với Quyết định 4, thêm `@Scheduled` sau nếu cần; (c) thêm 1 lượt
  ghi DB cho mỗi tệp (insert + update thay vì 1 insert) — chi phí nhỏ so
  với việc đọc/hash/quét toàn bộ tệp ngay trước đó.
- **ClamAV quét tệp lớn** → **đã giảm thiểu một phần** (Quyết định 3 mục
  0): nay có trần `ingest.max_file_size_bytes` chặn sớm ở bước 3b (trước cả
  khi đọc nội dung) và `SOCKET_TIMEOUT_MS` thành tham số cấu hình, nên
  không còn cảnh tệp lớn âm thầm timeout sau khi đã hash xong.
  **Rủi ro còn lại:** vẫn là mô hình quét **đồng bộ trong request HTTP** —
  một tệp sát trần sẽ giữ request (và 1 luồng backend + 1 kết nối ClamAV)
  rất lâu. Chưa có số liệu thật để chốt cặp giá trị (trần dung lượng,
  timeout) nên **phải đo trên hạ tầng thật** ở Task 5b trước khi phát hành;
  đặt trần quá cao sẽ tái tạo đúng vấn đề cũ. Nếu vận hành cho thấy cần tệp
  lớn hơn mức mô hình đồng bộ chịu được thì phải chuyển sang hàng đợi quét
  bất đồng bộ — **ngoài phạm vi MVP**, ghi nhận để không quên.
- **Đảo thứ tự sha256/format so với code hiện tại**: `sha256` chuyển từ
  `NOT NULL` sang nullable — cần rà soát mọi nơi đang đọc `document.sha256`
  giả định luôn có giá trị (ví dụ hiển thị FE, `findBySha256AndStatus`) để
  xử lý `null` đúng (chỉ xảy ra ở record `status='rejected'` do sai định
  dạng, không ảnh hưởng `clean`/`infected`).
- **Batch hết hạn kiểm tra kiểu lazy, không có job quét định kỳ** — **nợ
  kỹ thuật đã chấp nhận có chủ đích** (Quyết định 4 mục 0). Batch `OPEN`
  quá hạn mà không ai gọi lại sẽ nằm ở DB với status `OPEN` tới lần truy
  cập kế tiếp. Không ảnh hưởng đúng đắn nghiệp vụ (mọi đường đọc đều
  lazy-expire trước khi dùng) và mỗi dòng chỉ vài trăm byte.
  Cùng nhóm với row `document` `status='pending'` sinh ra từ Quyết định 2 —
  nếu sau này thêm `@Scheduled` dọn rác thì nên gom **một job xử lý cả
  hai**, đừng viết 2 job rời rạc.
