# Tech Spec: chunked-file-upload-02-personal-chunked-upload

> File: `docs/03-dev/specs/chunked-file-upload-02-personal-chunked-upload.md` — Owner: DEV
> Nguồn story: `docs/02-ba/stories/chunked-file-upload-02-personal-chunked-upload.md`
> Nguồn kiến trúc: `docs/01-sa/architecture/chunked-file-upload.md`
> Status: Approved

## 0. Phạm vi spec này (phụ thuộc spec 01)

Spec này **không định nghĩa lại** entity `upload_session`, giao thức
`chunk`/`status`/`complete`, hay 2 khoá `system_config`
(`upload.chunk_threshold_bytes`/`upload.chunk_size_bytes`) — toàn bộ đã chốt ở
`docs/03-dev/specs/chunked-file-upload-01-ingest-chunked-upload.md` (mục 2, 3
phần 3 endpoint dùng chung: `PUT .../chunks`, `GET .../{id}`, `POST
.../complete`). Chỉ đặc tả **phần khác biệt cho luồng personal**:

1. Endpoint `init` riêng cho domain personal (`POST
   /personal/batches/{batchId}/upload-sessions`), tương tự
   `POST /ingest/batches/{batchId}/upload-sessions` ở spec 01 nhưng khác
   request DTO và cách nạp/kiểm tra batch.
2. Cài nhánh `PERSONAL` trong `UploadSessionService.complete()` — ở spec 01
   nhánh này mới chỉ là placeholder (`// TODO(spec 02):
   PersonalService.uploadFileInBatch`, ném `IllegalStateException`).
3. 3 điểm chỉnh nhỏ, có chủ đích, lên trên phần đã viết ở spec 01 (coi như
   migration/patch nối tiếp, **không sửa lại nội dung file spec 01** — file đó
   đã `Approved`):
   - Thêm 1 cột mới cho `upload_session` (migration nối tiếp `V17`, không đổi
     `V16`) để lưu `folderId` đích — thứ mà `PersonalService.uploadFileInBatch`
     cần nhưng `DocumentsService.ingestBatchFile` không có khái niệm tương
     đương.
   - Thêm dependency `PersonalService` vào `UploadSessionService` (constructor
     injection thêm 1 tham số) — cùng kiểu phụ thuộc `UploadSessionService` đã
     có sẵn với `DocumentsService` ở spec 01.
   - **Đính chính 1 câu mô tả ở spec 01 mục 3**: "thân response [của
     `complete`] giống hệt `POST /ingest/batches/{id}/files`" chỉ đúng khi
     `BatchKind = WAREHOUSE`. Với `BatchKind = PERSONAL`, thân response giống
     hệt `POST /personal/batches/{id}/files`
     (`PersonalController.UploadedFileView`) — xem mục 3 bên dưới. Cơ chế lưu
     `result_body` (JSONB, replay idempotent) không đổi, chỉ *nội dung* JSON
     khác theo `BatchKind`.

Đã khảo sát code hiện có trước khi viết spec (không sửa code ở bước này):
`backend/src/main/java/com/aismartstore/backend/personal/PersonalService.java`,
`PersonalController.java`, `web/src/app/personal/page.tsx`. Ghi chú quan
trọng nhất: `PersonalService.uploadFileInBatch(user, batchId, folderId,
MultipartFile)` tính quota bằng `commitFileInsert` — SUM lại toàn bộ dung
lượng đã dùng dưới advisory lock rồi mới ghi row `personal_file` — **chỉ chạy
khi hàm này được gọi**, không có bước "reserve" nào ở `openBatch` cho từng
file riêng lẻ. Vì `complete()` là nơi DUY NHẤT gọi
`PersonalService.uploadFileInBatch` cho phiên chunked, quota tự động chỉ được
tính lúc `complete` thành công — đúng AC-7, không cần thêm logic nào.

## 1. Tóm tắt kỹ thuật

- Thêm `POST /personal/batches/{batchId}/upload-sessions` (init riêng cho
  personal) trong `PersonalController`, gọi
  `UploadSessionService.initPersonal(user, batchId, dto)`.
- Nạp/khoá batch ở bước init bằng cách **tái dùng nguyên vẹn** cơ chế đã có
  của `PersonalService` (owner-scoped + kind-scoped:
  `findByIdAndOwnerSubAndKind(id, sub, PERSONAL)`), thay vì
  `UploadBatchService.loadOpenBatch` (không owner-scoped) mà spec 01 dùng cho
  ingest. Nhờ vậy việc "phòng thủ sai `BatchKind`" (rủi ro mục 6 file SA) đến
  miễn phí từ query có sẵn — không cần thêm assert `kind == PERSONAL` tường
  minh như spec 01 phải làm với `kind == WAREHOUSE`. Cần mở rộng 2 method
  hiện đang `private` trong `PersonalService`
  (`findOwnedOpenBatch`/`findOwnedFolder`) thành các wrapper `public` mới để
  `UploadSessionService` (khác package) gọi được — không đổi logic bên trong.
- Cài nhánh `PERSONAL` (đã có placeholder ở spec 01) trong
  `UploadSessionService.complete()`: bọc `temp_file_path` bằng
  `PathMultipartFile` (tái dùng nguyên vẹn class đã viết ở spec 01, Task B4)
  rồi gọi `personalService.uploadFileInBatch(user, batch.getId(),
  session.getTargetPersonalFolderId(), wrapped)` — không check allowlist định
  dạng (đúng hành vi hiện tại, spec 01 AC-2 của `personal-storage-quota.md`).
- Mở rộng `PersonalService.QuotaInfo` (record trả về ở `GET /personal/quota`,
  đã được `personal/page.tsx` gọi sẵn lúc load trang) thêm field
  `chunkThresholdBytes` — vì không có endpoint `/personal/limits` nào khả
  dụng cho user chỉ có `PERM_personal.manage` (endpoint `/ingest/limits` yêu
  cầu `PERM_document.upload`, một quyền khác), và story yêu cầu ngưỡng dùng
  chung giá trị `system-config` với ingest (AC-1) — cách rẻ nhất là đọc lại từ
  `SystemConfigService` ngay trong `getQuota()`, không cần round-trip thêm.
- Frontend: thêm nhánh chunked trong `web/src/app/personal/page.tsx`
  (`uploadOneChunked`), cấu trúc theo đúng mẫu đã thiết kế ở spec 01 mục 4.5
  (retry 3 lần/backoff, resume-trong-1-lượt qua `GET status`, đọc token bằng
  `getSession()` ngay trước mỗi request — Quyết định 5 SA) nhưng **khác ở
  bước map kết quả `complete`**: dùng shape `UploadedFileView` cục bộ của
  trang personal (`status: 'STORED'`), không phải `IngestFileResult` của
  trang ingest — 2 trang này vốn đã có 2 kiểu `FileOutcome` cục bộ độc lập
  (đã xác minh, không dùng chung type).

## 2. Data model

### 2.1 Migration nối tiếp `V17__upload_session_personal_folder.sql`

```sql
-- chunked-file-upload-02: PersonalService.uploadFileInBatch(user, batchId,
-- folderId, file) cần folderId đích - khái niệm không tồn tại ở
-- DocumentsService.ingestBatchFile (WAREHOUSE dùng relative_path cho việc
-- này). Cột luôn NULL với phiên BatchKind=WAREHOUSE.
ALTER TABLE upload_session
    ADD COLUMN target_personal_folder_id UUID NULL
        REFERENCES personal_folder (id);
```

Không đổi bất kỳ cột nào khác của `upload_session` (mục 2.1 spec 01). Với
phiên personal: `relative_path` giữ giá trị mặc định `''` (không dùng), `note`
để `NULL` (không dùng — `PersonalService.uploadFileInBatch` không có tham số
`note`).

### 2.2 Entity `UploadSession` (mở rộng, không phải bảng mới)

Thêm field `targetPersonalFolderId` (`UUID`, nullable) vào entity
`com.aismartstore.backend.upload.UploadSession` đã có ở spec 01, cùng
getter/setter theo đúng convention hiện có của class này. Không đổi
`UploadSessionRepository`.

### 2.3 `PersonalService.QuotaInfo` (mở rộng record hiện có)

```java
// trước: public record QuotaInfo(long quotaBytes, long usedBytes, long remainingBytes)
public record QuotaInfo(long quotaBytes, long usedBytes, long remainingBytes, long chunkThresholdBytes) {}
```

`getQuota(user)` đọc thêm
`systemConfigService.getLong(SystemConfigDefaults.UPLOAD_CHUNK_THRESHOLD_BYTES)`
(khoá đã được tạo ở spec 01 Task B2) và truyền vào record. Không thêm khoá
`system_config` riêng cho personal (đúng AC-1: "dùng chung giá trị
system-config với luồng ingest").

### 2.4 DTO mới `com.aismartstore.backend.personal.dto.OpenPersonalUploadSessionDto`

Theo đúng convention package DTO hiện có của `personal` (cạnh
`OpenPersonalBatchDto`, `CreatePersonalFolderDto`):

```java
public record OpenPersonalUploadSessionDto(
        @NotBlank String originalName,
        @Positive long declaredSizeBytes,
        UUID folderId,       // optional, null = root — giống tham số folderId của POST .../files
        String mimeType) {}  // optional
```

Không có `relativePath`/`note` (không có khái niệm tương ứng ở personal).

## 3. API contract

Endpoint mới, riêng domain personal:

| Method | Endpoint | Auth | Request | Response |
|---|---|---|---|---|
| `POST` | `/personal/batches/{batchId}/upload-sessions` | `@PreAuthorize("hasAuthority('PERM_personal.manage')")` (giống `POST .../files`, hằng số `ALL_ROLES` có sẵn trong `PersonalController`) | `OpenPersonalUploadSessionDto` (mục 2.4) | `201 { sessionId, chunkSizeBytes, receivedBytes: 0, expiresAt }` — cùng shape response `init` đã định nghĩa ở spec 01 mục 3 |

3 endpoint còn lại (`PUT /upload-sessions/{id}/chunks`, `GET
/upload-sessions/{id}`, `POST /upload-sessions/{id}/complete`) **dùng nguyên
vẹn từ spec 01**, không đổi route/DTO request. Chỉ đính chính phần *response*
của `complete` cho trường hợp `BatchKind = PERSONAL`:

| Batch kind | Thân response `POST /upload-sessions/{id}/complete` (thành công) |
|---|---|
| `WAREHOUSE` (spec 01) | Giống hệt `POST /ingest/batches/{id}/files` — `{status: INGESTED, document, duplicateOf}` hoặc `{status: SKIPPED, reason, documentId}` |
| `PERSONAL` (spec này) | Giống hệt `POST /personal/batches/{id}/files` — `PersonalController.UploadedFileView { status: "STORED", file: PersonalFile }`, HTTP 201 |

Cả hai đều được lưu nguyên văn vào `result_body` (JSONB) để replay idempotent
(spec 01 mục 4.4 bước 2) — không đổi cơ chế, chỉ nội dung JSON khác nhau theo
nhánh dispatch.

### Mã lỗi

Không thêm mã lỗi mới. Áp dụng nguyên văn quy tắc "lỗi nghiệp vụ khi ráp xong
giữ nguyên, không bọc lại" ở spec 01 mục 3, nhưng **mở rộng danh sách lỗi**
cho đúng những gì `PersonalService.uploadFileInBatch`/`findOwnedOpenBatch`
thực sự có thể ném (khác tập lỗi của `DocumentsService.ingestBatchFile`):
`QUOTA_EXCEEDED` (409, kèm `usedBytes`/`quotaBytes`/`remainingBytes`),
`BATCH_CLOSED` (409), `BATCH_EXPIRED` (409), `FILE_TOO_LARGE` (422),
`MALWARE_DETECTED` (422), `SCANNER_UNAVAILABLE` (503) — không có
`INVALID_FORMAT` (personal không check allowlist, đúng AC-3). Init riêng cho
personal tự ném `FILE_TOO_LARGE` sớm (trước khi tạo `upload_session`), đúng
tinh thần "fail sớm" đã áp dụng ở spec 01 mục 4.1 bước 2.

## 4. Luồng xử lý chính

### 4.1 Init (personal)

1. `PersonalController.initUploadSession` (route mới) → gọi
   `uploadSessionService.initPersonal(user, batchId, dto)`.
2. `UploadSessionService.initPersonal`:
   - `personalService.loadOwnedOpenBatch(user, batchId)` (wrapper `public`
     mới, delegate tới `findOwnedOpenBatch` hiện có — owner-scoped +
     kind-scoped + lazy-expire, ném `NotFoundException`/`BATCH_CLOSED`/
     `BATCH_EXPIRED` y hệt whole-file).
   - Nếu `dto.folderId() != null`: `personalService.assertOwnedFolder(user,
     folderId)` (wrapper `public` mới, delegate `findOwnedFolder`) — fail
     sớm trước khi nhận byte nào, thay vì để tới `complete` mới phát hiện
     folder không tồn tại/không thuộc về mình (trải nghiệm tốt hơn, đúng tinh
     thần AC-1 "giống hệt trải nghiệm ingest"). `uploadFileInBatch` vẫn tự
     check lại folder ở thời điểm `complete` (không đổi code hiện có của nó)
     — phòng trường hợp folder bị xoá giữa lúc phiên đang chạy; đây là
     double-check hợp lệ, không phải lỗi.
   - `maxFileSizeBytes = systemConfigService.getLong(INGEST_MAX_FILE_SIZE_BYTES)`
     (đúng khoá `PersonalService.uploadFileInBatch` đang dùng, dòng 381) —
     `declaredSizeBytes > maxFileSizeBytes` → `422 FILE_TOO_LARGE`, không tạo
     row. **Không** check quota ở bước này (đúng AC-7 — quota chỉ tính ở
     `complete`, giống hệt whole-file không "reserve" quota lúc `openBatch`
     cho từng file).
   - Tạo temp file rỗng, đọc `upload.chunk_size_bytes`, lưu 1 row
     `upload_session` với `batch_id`, `original_name`, `relative_path=''`,
     `declared_size_bytes`, `declared_mime_type = dto.mimeType()`, `note =
     NULL`, `target_personal_folder_id = dto.folderId()`, `status=OPEN`,
     `expires_at = now + 24h` — cùng pattern spec 01 mục 4.1 bước 3.
   - Trả `sessionId`, `chunkSizeBytes`, `receivedBytes=0`, `expiresAt`.

### 4.2 Chunk / 4.3 Status

Dùng nguyên vẹn logic spec 01 mục 4.2/4.3 — không có gì khác biệt theo
`BatchKind` ở 2 bước này (chỉ đọc/ghi `received_bytes`, không đụng tới nghiệp
vụ personal/ingest).

### 4.4 Complete — cài nhánh `PERSONAL`

Thay thế đúng đoạn `PERSONAL` (placeholder) ở spec 01 mục 4.4 bước 5:

```java
switch (batch.getKind()) {
    case WAREHOUSE -> { /* đã cài ở spec 01, không đổi */ }
    case PERSONAL -> {
        MultipartFile wrapped = new PathMultipartFile(
                session.getTempFilePath(), session.getOriginalName(), session.getDeclaredMimeType());
        PersonalFile saved = personalService.uploadFileInBatch(
                user, batch.getId(), session.getTargetPersonalFolderId(), wrapped);
        resultBody = Map.of("status", "STORED", "file", saved);
    }
}
```

Toàn bộ phần còn lại của bước `complete` (idempotent replay ở bước 2, xoá
temp file ở `finally` bước 6, phân loại lỗi nghiệp vụ vs lỗi hạ tầng ở bước
7) áp dụng **y hệt** spec 01 mục 4.4 — chỉ tập lỗi nghiệp vụ được coi là
"tệp đã xử lý xong, `status=COMPLETED`, lưu `result_body` dạng `REJECTED`"
mở rộng thêm `QUOTA_EXCEEDED`/`BATCH_CLOSED`/`BATCH_EXPIRED` cho nhánh
personal (mục 3 - Mã lỗi). Lưu ý: vì temp file luôn bị xoá ở `finally` dù
thành công hay lỗi, một phiên bị từ chối vì `QUOTA_EXCEEDED` **không thể**
retry lại bằng cách gọi `complete` lần nữa sau khi người dùng dọn bớt quota —
client phải mở phiên mới và gửi lại toàn bộ chunk. Đây là hệ quả đã có sẵn từ
thiết kế chung ở spec 01 (không phải vấn đề riêng của personal), ghi nhận ở
mục 7.

`UploadSessionService` cần thêm `PersonalService personalService` vào
constructor (dependency mới, cùng kiểu với `DocumentsService` đã có).

### 4.5 Frontend (`web/src/app/personal/page.tsx`)

1. `QuotaInfo` (type ở `web/src/lib/api.ts`) thêm field
   `chunkThresholdBytes: number` (khớp mục 2.3).
2. Trong `submitUpload()`, với mỗi file trong `queue`: `file.size >
   quota.chunkThresholdBytes` → `uploadOneChunked(batchId, item, index)`,
   ngược lại giữ nguyên `uploadOne` hiện có (AC-1, không đổi hành vi tệp nhỏ).
3. `uploadOneChunked` — **sao chép cấu trúc** `uploadOneChunked` của spec 01
   mục 4.5 bước 3 (vòng lặp ngoài 3 lần thử/backoff `500ms * attempt`, resume
   trong-1-lượt qua `GET status`, `Blob.slice()` theo `chunkSizeBytes`, gọi
   `POST .../upload-sessions` của **personal** ở lần thử đầu thay vì
   `/ingest/...`) — khác duy nhất ở bước map response `complete`:
   ```ts
   const result = await apiJson<{ status: 'STORED'; file: PersonalFile }>(
     `/upload-sessions/${sessionId}/complete`, session, { method: 'POST' },
   );
   updateOutcome(index, { phase: 'done' }); // khớp FileOutcome cục bộ của trang personal
   ```
   Lỗi `QUOTA_EXCEEDED` map qua `isQuotaExceeded(e.body)` — tái dùng đúng hàm
   helper đã có sẵn trong trang (dùng chung với nhánh `uploadOne` whole-file,
   dòng 232-239), không viết logic hiển thị lỗi quota mới.
4. Áp dụng nguyên vẹn Quyết định 5 (SA) + Task F3 của spec 01: mọi request
   trong `uploadOneChunked` đọc token bằng `getSession()` ngay trước khi gọi,
   không dùng biến `session` đóng khung từ đầu `submitUpload()`.

## 5. Test plan

### Backend

- Unit (`UploadSessionServiceTest`, mở rộng file test đã tạo ở spec 01, dùng
  Mockito):
  - `initPersonal`: batch `kind=WAREHOUSE` (dù cùng chủ sở hữu) → 404 (qua
    `findOwnedOpenBatch`/`findByIdAndOwnerSubAndKind` không tìm thấy) — test
    "phòng thủ sai `BatchKind`" chiều ngược lại spec 01 (AC-5); batch của
    user khác → 404; `folderId` không thuộc về user → 404 (`assertOwnedFolder`
    ném, không tạo row); `declaredSizeBytes > max_file_size_bytes` →
    `FILE_TOO_LARGE`, không tạo row, **không** check quota (assert không gọi
    tới bất kỳ method liên quan `AccountsService`/`sumSizeByOwnerSub`).
  - `complete` (nhánh `PERSONAL`, **AC-5 - test bắt buộc**): session của batch
    `kind=PERSONAL` → verify gọi đúng 1 lần
    `personalService.uploadFileInBatch(...)` với đúng `folderId` lấy từ
    `target_personal_folder_id`, **verify KHÔNG gọi**
    `documentsService.ingestBatchFile` (Mockito `verify(documentsService,
    never())...`); ngược lại, session của batch `kind=WAREHOUSE` → verify
    **không gọi** `personalService.uploadFileInBatch` (đối xứng, khẳng định
    dispatch không lẫn chiều nào — đúng AC-4/AC-5).
  - `complete` (**AC-7 - test bắt buộc**): quota gần đầy → `init` (không lỗi,
    không check quota) → gửi đủ chunk → `complete` → `QUOTA_EXCEEDED` (409);
    assert **không có row `personal_file` mới**, `upload_batch` counters
    (`actual_file_count`/`actual_total_bytes`) không đổi — khẳng định quota
    chỉ tính tại thời điểm `complete`, không "reserve" lúc `init`/gửi chunk.
  - `complete` (**AC-6**): mock `ClamdService.scanStream` trả `INFECTED` cho
    session personal → response `MALWARE_DETECTED` giống hệt shape lỗi của
    whole-file, không tạo `personal_file`, temp file bị xoá sau đó.

- Integration (`UploadSessionControllerIT`, mở rộng file spec 01, mock
  `ClamdService`/`ObjectStorageService`):
  - Full flow personal: `POST /personal/batches/{id}/upload-sessions` → 3
    chunk (chunk cuối lệch kích thước) → `POST .../complete` → `GET
    /personal/files` thấy đúng 1 file, `sha256` khớp toàn bộ payload gốc,
    `folderId` đúng giá trị đã khai ở `init`.
  - AC-5 (integration, bổ sung cho unit): mở đồng thời 1 batch `WAREHOUSE` và
    1 batch `PERSONAL` của cùng user, chạy đủ 2 luồng chunked song song →
    `GET /documents` thấy đúng 1 document (từ batch warehouse), `GET
    /personal/files` thấy đúng 1 file (từ batch personal) — không lẫn.
  - `GET /personal/quota` trả thêm `chunkThresholdBytes` đúng giá trị mặc
    định (20MB) khi chưa override `system_config`.
  - Ownership (AC-2 story 1, áp dụng lại cho session gốc từ batch personal):
    JWT user B gọi `PUT chunks`/`GET status`/`POST complete` lên session do
    user A tạo (dù batch là `PERSONAL`) → `403` — xác nhận cơ chế ownership
    dùng chung của spec 01 hoạt động đúng khi áp cho session gốc personal,
    không chỉ session gốc ingest.

### Frontend

- `uploadOneChunked` (trang `personal/page.tsx`) với file giả lập lớn hơn
  `chunkThresholdBytes`: assert gọi đúng chuỗi `fetch` tới
  `/personal/batches/{id}/upload-sessions` (không phải `/ingest/...`), N
  chunk, rồi `.../complete`; kết quả map đúng `outcome.phase = 'done'`
  (không phải `'ingested'`/`'skipped'` — 2 kiểu `FileOutcome` độc lập).
- Lỗi `QUOTA_EXCEEDED` ở bước `complete` → `outcome.phase = 'error'` với
  message chứa `remainingBytes` (dùng lại `isQuotaExceeded` hiện có).
- Case biên: file đúng bằng `chunkThresholdBytes` vẫn đi whole-file (`<=`,
  đồng nhất cách so sánh đã chốt ở spec 01 mục 5).

## 6. Task breakdown

### Backend

- [x] Task B1: Migration `V17__upload_session_personal_folder.sql` (mục 2.1).
- [x] Task B2: Entity `UploadSession` thêm field `targetPersonalFolderId`
      (mục 2.2).
- [x] Task B3: `PersonalService.QuotaInfo` thêm `chunkThresholdBytes`, cập
      nhật `getQuota()` (mục 2.3) — kiểm tra không có nơi nào khác dựng
      `QuotaInfo` bằng constructor 3-tham số cần sửa theo.
- [x] Task B4: `PersonalService` — thêm 2 wrapper `public`:
      `loadOwnedOpenBatch(user, batchId)`, `assertOwnedFolder(user, folderId)`,
      delegate nguyên vẹn tới 2 method `private` hiện có, không đổi logic.
- [x] Task B5: DTO `OpenPersonalUploadSessionDto` (mục 2.4).
- [x] Task B6: `UploadSessionService.initPersonal` (mục 4.1) + thêm
      `PersonalService` vào constructor.
- [x] Task B7: Cài nhánh `PERSONAL` trong `UploadSessionService.complete()`
      (mục 4.4), thay placeholder đã có ở spec 01.
- [x] Task B8: `PersonalController` — thêm method
      `initUploadSession` (`POST /personal/batches/{batchId}/upload-sessions`,
      `@PreAuthorize(ALL_ROLES)`).
- [x] Task B9: Unit + integration test (mục 5 - Backend), **bắt buộc gồm**
      test dispatch AC-5 (cả 2 chiều) và test quota-chỉ-tính-lúc-complete AC-7.

### Frontend

- [x] Task F1: `web/src/lib/api.ts` — thêm `chunkThresholdBytes` vào
      `QuotaInfo`; thêm type response cho `init` personal (tái dùng
      `OpenUploadSessionResponse`/`ChunkAckResponse`/
      `UploadSessionStatusResponse` đã thêm ở spec 01 Task F1 nếu đủ tổng
      quát — không tạo type trùng nếu shape giống hệt).
- [x] Task F2: Hàm `uploadOneChunked` trong `personal/page.tsx` (mục 4.5) —
      sao chép cấu trúc retry/resume của spec 01 F2, đổi endpoint init và
      cách map response `complete` sang `UploadedFileView`.
- [x] Task F3: Áp dụng `getSession()` (thay vì biến `session` đóng khung) cho
      mọi request trong `uploadOneChunked` — review theo đúng yêu cầu Quyết
      định 5 SA, cùng chuẩn spec 01 Task F3.
- [x] Task F4: Rẽ nhánh whole-file/chunked trong `submitUpload()` theo
      `quota.chunkThresholdBytes` (AC-1), không đổi hành vi `uploadOne` hiện
      có.
- [x] Task F5: Thêm test cho `uploadOneChunked` của trang personal (mục 5 -
      Frontend), tái dùng test runner đã thêm ở spec 01 Task F5 (Vitest +
      Testing Library) — không cài lại runner.

## 7. Rủi ro kỹ thuật

- **Ghi đè temp-file 2 lần cho luồng personal**:
  `PersonalService.uploadFileInBatch` tự gọi `bufferToTempFile(file)` bên
  trong (copy `PathMultipartFile` sang 1 temp file khác trước khi hash/scan),
  cộng thêm việc `complete()` đã ráp file từ các chunk vào 1 temp file khác
  trước đó — tổng cộng 1 tệp đi qua đĩa 2-3 lần cho luồng chunked (nhiều hơn
  whole-file). Đây là rủi ro **đã được SA ghi nhận và chấp nhận** ở
  `docs/01-sa/architecture/chunked-file-upload.md` mục 2 ("nằm ngoài phạm vi
  tối ưu của tài liệu này") cho `DocumentsService.ingestBatchFile`; áp dụng
  tương tự (không tệ hơn) cho `PersonalService.uploadFileInBatch` — không xử
  lý thêm ở spec này.
- **Phiên bị `QUOTA_EXCEEDED` ở `complete` không retry được** (đã nêu ở mục
  4.4): vì temp file luôn bị xoá trong `finally`, client phải mở phiên mới +
  gửi lại toàn bộ chunk nếu muốn thử lại sau khi dọn quota. Hệ quả tất yếu
  của thiết kế chung ở spec 01 (không phải lỗi riêng của spec này) — chấp
  nhận được vì cùng hành vi với các lỗi nghiệp vụ khác (`MALWARE_DETECTED`,
  `FILE_TOO_LARGE`) đã chấp nhận ở spec 01.
- **Coupling package mới**: `UploadSessionService` (package `upload`, trung
  lập domain) giờ phụ thuộc trực tiếp vào `PersonalService` (package
  `personal`) — cùng kiểu phụ thuộc nó đã có với `DocumentsService` (package
  `ingest`) từ spec 01, nên không phải rủi ro kiến trúc mới, chỉ ghi nhận:
  package `upload` không còn "chỉ biết giao thức transport" thuần tuý mà biết
  cả 2 domain gọi nó — đúng như thiết kế Quyết định 2 (SA) đã dự liệu ("bước
  complete phải dispatch đúng service xử lý theo BatchKind").
- **2 lần load batch ở `complete`** cho nhánh personal (1 lần qua
  `UploadBatchService.loadOpenBatch` chung ở đầu bước 5 spec 01, 1 lần nữa
  qua `findOwnedOpenBatch` bên trong `uploadFileInBatch`) — dư 1
  round-trip DB, không gây sai lệch dữ liệu (cùng transaction, cùng kết quả),
  chấp nhận được để giữ `uploadFileInBatch` không đổi.
