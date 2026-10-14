# Tech Spec: chunked-file-upload-01-ingest-chunked-upload

> File: `docs/03-dev/specs/chunked-file-upload-01-ingest-chunked-upload.md` — Owner: DEV
> Nguồn story: `docs/02-ba/stories/chunked-file-upload-01-ingest-chunked-upload.md`
> Nguồn kiến trúc: `docs/01-sa/architecture/chunked-file-upload.md`
> Status: Approved

## 0. Ghi chú phạm vi cho 2 spec kế tiếp

Đây là spec **nền tảng**: định nghĩa entity `upload_session` (PostgreSQL) và
giao thức `init → chunk → status → complete` dùng chung cho cả luồng ingest
(spec này) và luồng personal
(`chunked-file-upload-02-personal-chunked-upload.md`). Endpoint `init` được
đặt riêng theo domain (`/ingest/...`), còn `chunk`/`status`/`complete` đặt ở
prefix trung lập `/upload-sessions/...` (không phụ thuộc `BatchKind`) để spec
02 tái dùng nguyên vẹn, chỉ cần thêm nhánh `PERSONAL` ở bước dispatch của
`complete`. Migration, entity, index đều tạo ở spec này; spec 03 (cleanup)
chỉ đọc lại cột `status`/`expires_at` đã có, không sửa schema.

**Quyết định phạm vi (đã xác nhận với người duyệt, 2026-08-21):** "Resume"
trong AC-3/AC-4 chỉ hiện thực ở mức **trong một lượt `submit()` đang chạy**
(mất mạng giữa chừng → retry loop tự đồng bộ lại `receivedBytes` qua endpoint
`status` rồi gửi tiếp từ đó, không gửi lại từ đầu). Việc đóng hẳn tab rồi mở
lại và tự động nhận diện lại tệp cũ để resume **không implement ở spec này**
(đúng sát nghĩa "mất mạng, tab treo" ở AC-3 — không phải "đóng hẳn tab") —
endpoint `status` vẫn được thiết kế để dùng được cho việc đó sau này nếu có
story riêng.

## 1. Tóm tắt kỹ thuật

- Thêm bảng `upload_session` (PostgreSQL, migration `V16`) lưu trạng thái một
  phiên tải chunk: offset đã nhận, đường dẫn temp file đang ráp, người tạo,
  hạn dùng — theo đúng Quyết định 2 của SA, dùng chung cho ingest + personal.
- Thêm 4 endpoint mới quanh entity này: `POST
  /ingest/batches/{batchId}/upload-sessions` (init, riêng cho domain ingest),
  `PUT /upload-sessions/{sessionId}/chunks` (nhận 1 chunk), `GET
  /upload-sessions/{sessionId}` (đọc offset hiện tại, phục vụ resume), `POST
  /upload-sessions/{sessionId}/complete` (ráp file + dispatch đúng service
  nghiệp vụ theo `BatchKind` — spec này chỉ cài nhánh `WAREHOUSE`, gọi lại
  `DocumentsService.ingestBatchFile` y hệt luồng whole-file, không viết lại
  pipeline).
- Thêm 2 khoá `system_config` mới: `upload.chunk_threshold_bytes` (20MB) và
  `upload.chunk_size_bytes` (8MB), theo đúng Quyết định 4.
- Frontend (`web/src/app/upload/page.tsx`): tệp > ngưỡng đi nhánh
  `uploadOneChunked` mới (chia bằng `Blob.slice()`, gửi tuần tự, retry ở mức
  "toàn bộ phần còn lại của tệp" giống hệt cơ chế 3-lần/backoff hiện có của
  `uploadOne`), tệp ≤ ngưỡng giữ nguyên `uploadOne`. Dùng chung
  `FileOutcome`/`OutcomeBadge`, không thêm state UI mới (AC-8/AC-9). Mỗi
  request trong nhánh chunked lấy token qua `getSession()` (imperative API
  của NextAuth) ngay trước khi gọi, không dùng biến `session` đóng khung từ
  đầu `submit()` (Quyết định 5).
- Không đổi `DocumentsService.ingestBatchFile`, không đổi luồng whole-file
  hiện có (AC-2).

## 2. Data model

### 2.1 Bảng `upload_session` (migration mới `V16__upload_session.sql`)

Theo đúng convention Flyway hiện có (`V10`…`V15`), chạy sau `V15`.

```sql
-- chunked-file-upload.md Quyết định 2: một phiên tải chunk, sống trong một
-- upload_batch đã mở (dùng chung cho BatchKind.WAREHOUSE và .PERSONAL, xem
-- ghi chú áp dụng cho cả hai luồng ở Quyết định 2).
CREATE TABLE upload_session (
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    batch_id               UUID NOT NULL REFERENCES upload_batch (id),
    original_name          VARCHAR(1024) NOT NULL,
    relative_path          VARCHAR(1024) NOT NULL DEFAULT '',
    declared_size_bytes    BIGINT NOT NULL CHECK (declared_size_bytes > 0),
    received_bytes         BIGINT NOT NULL DEFAULT 0 CHECK (received_bytes >= 0),
    -- Snapshot của system-config `upload.chunk_size_bytes` tại thời điểm init
    -- (DEV addition ngoài danh sách cột gốc ở Quyết định 2): nếu admin đổi
    -- config giữa lúc phiên đang chạy, client vẫn resume đúng bằng chunk
    -- size đã cam kết từ đầu thay vì đọc lại giá trị mới có thể lệch offset.
    chunk_size_bytes       BIGINT NOT NULL CHECK (chunk_size_bytes > 0),
    -- DEV addition: tái tạo đúng tham số `mimeType`/`note` mà luồng
    -- whole-file (`ingestBatchFile`) nhận trực tiếp từ request - chunked cần
    -- một nơi giữ lại 2 giá trị này từ init tới complete.
    declared_mime_type     VARCHAR(255),
    note                   VARCHAR(4000),
    temp_file_path         VARCHAR(1024) NOT NULL,
    status                 VARCHAR(16) NOT NULL DEFAULT 'OPEN'
        CHECK (status IN ('OPEN', 'COMPLETED', 'ABORTED', 'EXPIRED')),
    -- Kết quả của complete(), lưu lại để complete() có thể replay idempotent
    -- (xem mục 4.4) thay vì chạy lại ingestBatchFile lần 2 nếu client retry
    -- sau khi mất response của lần gọi complete thành công trước đó.
    result_body            JSONB,
    -- SA đề xuất 1 cột "created_by"; tách sub/username theo đúng pattern
    -- owner_sub/owner_username đã dùng ở upload_batch (V10) để nhất quán.
    created_by_sub         VARCHAR(255) NOT NULL,
    created_by_username    VARCHAR(255) NOT NULL,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at             TIMESTAMPTZ NOT NULL DEFAULT now() + INTERVAL '24 hours'
);

CREATE INDEX IDX_upload_session_batch_id ON upload_session (batch_id);
-- Phục vụ job dọn dẹp ở chunked-file-upload-03-session-cleanup.md - tạo sẵn
-- ở đây vì bảng được định nghĩa ở spec này (Quyết định 2/3 file SA).
CREATE INDEX IDX_upload_session_status_expires_at ON upload_session (status, expires_at);
```

Không thêm cột `kind` riêng — dispatch ở `complete` đọc `BatchKind` qua
`upload_session.batch_id → upload_batch.kind` (JOIN), tránh 2 nguồn sự thật
lệch nhau nếu ai đó sửa nhầm 1 trong 2 cột.

### 2.2 Entity JPA `UploadSession`

Package mới `com.aismartstore.backend.upload` (không đặt trong `ingest` vì
entity dùng chung cho cả `ingest` và `personal` - domain trung lập, đúng tinh
thần Quyết định 2). Field tương ứng 1-1 với các cột trên, enum
`UploadSessionStatus { OPEN, COMPLETED, ABORTED, EXPIRED }` (String constants,
theo đúng pattern `UploadBatchStatus` hiện có - không dùng `@Enumerated`).

- `UploadSessionRepository extends JpaRepository<UploadSession, UUID>`
  - `findByIdAndCreatedBySub(UUID id, String createdBySub): Optional<UploadSession>`
    — dùng cho check quyền sở hữu ở mọi endpoint sau `init` (AC-7).
  - `@Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select s from UploadSession s where s.id = :id") findByIdForUpdate(UUID id): Optional<UploadSession>`
    — khoá row khi ghi `received_bytes` (mục 4.2) để 2 request chunk trùng
    lặp (client gửi trùng do lỗi mạng ambiguous) không tính offset sai.

### 2.3 `system_config` — 2 khoá mới (`SystemConfigDefaults`)

| Key | Type | Default | Ghi chú |
|---|---|---|---|
| `upload.chunk_threshold_bytes` | NUMBER | `20 * 1024 * 1024` (20MB) | Quyết định 4 - ngưỡng chuyển whole-file/chunked |
| `upload.chunk_size_bytes` | NUMBER | `8 * 1024 * 1024` (8MB) | Quyết định 4 - kích thước 1 chunk, trả về ở `init` |

Thêm hằng `MB = 1024L * 1024L` cạnh `GB` hiện có trong
`SystemConfigDefaults`. Không đánh dấu `sensitive` (giống các khoá
`ingest.*` khác) — sai giá trị chỉ ảnh hưởng hiệu năng, không gây mất dữ liệu.

## 3. API contract

Tất cả endpoint yêu cầu Bearer JWT hợp lệ (Spring Security resource-server,
như toàn bộ API hiện có). Cột "Auth" ghi rõ tầng kiểm tra bổ sung.

| Method | Endpoint | Auth | Request | Response |
|---|---|---|---|---|
| `POST` | `/ingest/batches/{batchId}/upload-sessions` | `@PreAuthorize("hasAuthority('PERM_document.upload')")` (giống `POST .../files`); batch phải `OPEN` (`loadOpenBatch`) và `kind == WAREHOUSE` (404 `BATCH_NOT_FOUND` nếu không, phòng thủ mục 6 rủi ro "sai `BatchKind`") | `{ originalName, relativePath, declaredSizeBytes, mimeType? }` | `201 { sessionId, chunkSizeBytes, receivedBytes: 0, expiresAt }` |
| `PUT` | `/upload-sessions/{sessionId}/chunks?offset={n}` | Bất kỳ JWT hợp lệ (`isAuthenticated()`) **+** `session.createdBySub == user.sub()` (403 nếu không - AC-7) | Body nhị phân thô (`Content-Type: application/octet-stream`), độ dài ≤ `chunk_size_bytes` của phiên | `200 { sessionId, receivedBytes, declaredSizeBytes }` |
| `GET` | `/upload-sessions/{sessionId}` | `isAuthenticated()` + ownership như trên | — | `200 { sessionId, status, receivedBytes, declaredSizeBytes, chunkSizeBytes, expiresAt }` |
| `POST` | `/upload-sessions/{sessionId}/complete` | `isAuthenticated()` + ownership như trên | — (không cần body) | `201`/`200` — **shape phụ thuộc `BatchKind` của batch liên kết**: với `WAREHOUSE` (spec này), thân response giống hệt `POST /ingest/batches/{id}/files` (`{status: INGESTED, document, duplicateOf}` hoặc `{status: SKIPPED, reason, documentId}`), để frontend tái dùng nguyên `IngestFileResult`; với `PERSONAL`, xem `chunked-file-upload-02-personal-chunked-upload.md` (giống hệt `POST /personal/batches/{id}/files` — `UploadedFileView`, KHÔNG phải `IngestFileResult`) |

Lý do endpoint `chunk`/`status`/`complete` không gắn `@PreAuthorize` theo
permission cụ thể của từng domain: `init` (theo domain, ví dụ
`PERM_document.upload` ở ingest, `PERM_personal.manage` ở personal — spec 02)
đã là cửa kiểm soát ai được TẠO phiên; sau đó danh giới bảo mật duy nhất cần
cho AC-7 là "đúng người đã tạo phiên", nên 3 endpoint dùng chung chỉ cần xác
thực JWT hợp lệ + so khớp `createdBySub`, tránh phải biết trước phiên thuộc
domain nào để chọn đúng permission annotation.

### Mã lỗi mới (body `{code, message}` qua `ApiException`, theo đúng pattern `UploadBatchService`/`DocumentsService`)

| Code | HTTP | Khi nào |
|---|---|---|
| `UPLOAD_SESSION_NOT_FOUND` | 404 | `sessionId` không tồn tại |
| `UPLOAD_SESSION_FORBIDDEN` | 403 | `createdBySub` khác user gọi (AC-7) |
| `UPLOAD_SESSION_NOT_OPEN` | 409 | Gửi chunk khi `status != OPEN` |
| `CHUNK_OFFSET_MISMATCH` | 409 | `offset` (query param) khác `received_bytes` hiện tại — client phải gọi lại `GET status` để đồng bộ rồi gửi lại |
| `CHUNK_OVERFLOW` | 422 | `received_bytes + len(body) > declared_size_bytes` (phòng thủ, client đúng sẽ không bao giờ gặp) |
| `UPLOAD_SESSION_INCOMPLETE` | 409 | Gọi `complete` khi `received_bytes != declared_size_bytes` |

Các lỗi nghiệp vụ khi ráp xong (`INVALID_FORMAT`, `MALWARE_DETECTED`,
`FILE_TOO_LARGE`, `SCANNER_UNAVAILABLE`, `STORAGE_WRITE_FAILED`) **giữ
nguyên** từ `DocumentsService.ingestBatchFile` — `complete` không bọc lại,
để nguyên response/status code như luồng whole-file (AC-5/AC-6).

## 4. Luồng xử lý chính

### 4.1 Init

1. `IngestController` thêm phương thức mới, gọi `UploadSessionService.init(user, batchId, dto)`.
2. `UploadSessionService.init`: `uploadBatchService.loadOpenBatch(batchId)` (tái
   dùng nguyên logic lazy-expire đã có) → assert `batch.getKind() ==
   WAREHOUSE` → assert `declaredSizeBytes <= ingest.max_file_size_bytes`
   (dùng lại `fileTooLarge()`-style check, fail sớm trước khi nhận byte nào).
3. Tạo temp file rỗng (`Files.createTempFile("upload-session-", ".tmp")`),
   đọc `upload.chunk_size_bytes` hiện tại từ `SystemConfigService`, lưu 1 bản
   ghi `upload_session` với `status=OPEN`, `expires_at = now + 24h` (đúng
   pattern `UploadBatch.expiresAt`).
4. Trả `sessionId`, `chunkSizeBytes`, `receivedBytes=0`, `expiresAt`.

### 4.2 Chunk

1. `UploadSessionController.putChunk`: load session qua
   `findByIdAndCreatedBySub` (403 nếu không khớp/không tồn tại — không phân
   biệt "không tồn tại" và "không phải của bạn" trong response để tránh dò
   session id của người khác, giống style `NotFoundException` ẩn thông tin
   hiện có ở các domain khác).
2. Trong 1 `@Transactional`: `findByIdForUpdate` (khoá row) → nếu `status !=
   OPEN` → `409 UPLOAD_SESSION_NOT_OPEN`; nếu `offset != receivedBytes` →
   `409 CHUNK_OFFSET_MISMATCH`.
3. Ghi phần thân request (đọc thẳng từ `HttpServletRequest.getInputStream()`,
   không buffer toàn bộ chunk vào `byte[]` trước — tránh cộng dồn thêm bộ nhớ
   ngoài áp lực ghi-đĩa-2-lần đã ghi nhận ở SA mục 2) vào `temp_file_path`
   bằng `Files.newOutputStream(path, APPEND)`, đếm số byte thực ghi được.
4. Nếu `receivedBytes + written > declaredSizeBytes` → rollback, `422
   CHUNK_OVERFLOW`. Ngược lại cập nhật `received_bytes += written`,
   `updated_at = now()`, `expires_at = now() + 24h` (gia hạn mỗi chunk, đúng
   Quyết định 3 - job dọn ở spec 03 chỉ đọc cột này, không tự gia hạn).
5. Trả `receivedBytes` mới để client tính offset chunk kế tiếp.

### 4.3 Status (resume)

`GET` đơn giản: load session theo ownership, trả các trường hiện tại. Không
đổi state. Dùng bởi client để (a) đồng bộ lại `receivedBytes` sau một lần
gửi chunk lỗi mơ hồ (mất response nhưng không rõ server đã ghi hay chưa —
mục 4.5), và (b) làm nền cho tính năng resume-sau-khi-đóng-tab nếu được bổ
sung AC ở bước sau (xem mục 0).

### 4.4 Complete

1. Load session theo ownership.
2. Nếu `status == COMPLETED`: trả lại **nguyên văn** `result_body` đã lưu
   (idempotent replay) — không gọi lại `ingestBatchFile` lần 2. Đây là điều
   kiện bắt buộc: nếu client gọi `complete` thành công ở server nhưng mất
   response (network drop ngay sau khi server đã tạo `Document`/tăng batch
   counter), một lần gọi lại `complete` không được tạo thêm 1 document thứ
   hai.
3. Nếu `status == ABORTED` hoặc `EXPIRED` → `409 UPLOAD_SESSION_NOT_OPEN`.
4. Nếu `status == OPEN` và `received_bytes != declared_size_bytes` → `409
   UPLOAD_SESSION_INCOMPLETE`.
5. Nếu `status == OPEN` và đã đủ byte: load `upload_batch` qua
   `uploadBatchService.loadOpenBatch(session.batchId)` (batch có thể đã hết
   hạn/đóng giữa lúc chunk đang chạy — lỗi 409 `BATCH_EXPIRED`/`BATCH_CLOSED`
   y hệt whole-file), `switch (batch.getKind())`:
   - `WAREHOUSE` (duy nhất được cài trong spec này): bọc `temp_file_path`
     bằng `PathMultipartFile` (originalName, `declaredMimeType`) rồi gọi
     **nguyên vẹn** `documentsService.ingestBatchFile(user, batchId,
     wrapped, session.relativePath, session.note)`.
   - `PERSONAL`: không xảy ra ở spec này (init đã chặn ở bước 4.1), nhưng để
     `switch` tường minh — ném `IllegalStateException` rõ ràng thay vì rơi
     xuống mặc định im lặng, đánh dấu `// TODO(spec 02): PersonalService.uploadFileInBatch`.
6. Trong `finally`: **luôn** xoá `temp_file_path` khỏi đĩa (đây là bản copy
   của tính năng chunk, tách biệt bản copy nội bộ mà
   `DocumentsService.bufferToTempFile` tự tạo và tự xoá — không xoá ở đây sẽ
   rò đĩa dù `ingestBatchFile` thành công hay ném lỗi).
7. Nếu bước 5 thành công (`Ingested`/`Skipped`) hoặc ném `ApiException`
   nghiệp vụ hợp lệ (format/malware/size — tệp coi như đã "được xử lý xong",
   dù bị từ chối): set `status=COMPLETED`, lưu `result_body` (thành công) hoặc
   để `result_body = null` và set `status=COMPLETED` trước khi rethrow lỗi
   (không replay được lỗi cũ, nhưng gọi lại `complete` trên 1 session đã
   `COMPLETED`-mà-`result_body=null` phải trả lại y hệt lỗi loại đó — thực
   hiện bằng cách vẫn lưu đủ thông tin lỗi vào `result_body` dưới dạng
   `{status: "REJECTED", code, message}` thay vì để null, để replay nhất
   quán ở bước 2). Nếu ném lỗi hạ tầng không mong đợi (IOException đọc temp
   file...) → set `status=ABORTED`, không lưu `result_body`, ném `500`.

### 4.5 Frontend (`web/src/app/upload/page.tsx`)

1. `IngestLimits` (kiểu TS ở `web/src/lib/api.ts`) thêm field
   `chunkThresholdBytes` (đọc từ `GET /ingest/limits` đã mở rộng).
2. Trong `submit()`, với mỗi file: `file.size > limits.chunkThresholdBytes`
   → `uploadOneChunked`, ngược lại giữ nguyên `uploadOne` hiện có (AC-2).
3. `uploadOneChunked(batchId, item, index)` — 1 vòng lặp ngoài tối đa 3 lần
   thử (giống hệt hằng số/backoff `500ms * attempt` của `uploadOne`, không
   thêm tham số cấu hình mới):
   - Lần thử đầu: gọi `init` → lưu `sessionId`, `chunkSizeBytes`.
   - Các lần thử sau (sau khi 1 request transient lỗi): nếu đã có
     `sessionId`, gọi `GET status` để đồng bộ lại `receivedBytes` thay vì
     gửi lại từ đầu (AC-4) — đây chính là cơ chế resume trong-1-lượt.
   - Từ `offset` hiện tại, lặp `file.slice(offset, offset + chunkSizeBytes)`
     gửi tuần tự bằng `PUT /upload-sessions/{id}/chunks?offset={offset}`,
     cập nhật `offset` từ `receivedBytes` trong response.
   - Khi `offset === declaredSizeBytes`: gọi `POST .../complete`, map kết
     quả vào `updateOutcome` y hệt `uploadOne` (`ingested`/`skipped`) — dùng
     lại nguyên `IngestFileResult` (AC-8).
   - Bất kỳ lỗi transient nào (network hoặc `503`, theo đúng
     `TRANSIENT_STATUSES` hiện có) trong toàn bộ quá trình trên → thoát vòng
     lặp trong, `sleep(500 * attempt)`, sang lần thử ngoài kế tiếp. Hết 3
     lần thử ngoài → `updateOutcome(index, {phase: 'error', ...})` (AC-9: chỉ
     đúng 1 lần chuyển trạng thái badge sang lỗi, không có state trung
     gian "đang thử lại lần N").
4. **Mỗi** lệnh gọi API trong `uploadOneChunked` (init/chunk/status/complete)
   lấy token bằng `await getSession()` (import `getSession` từ
   `next-auth/react`, gọi ngay trước từng `apiFetch`) — **không** dùng biến
   `session` lấy từ `useSession()` ở đầu component (Quyết định 5: biến đó bị
   đóng khung tại thời điểm submit() bắt đầu chạy, không tự cập nhật khi
   `SessionProvider` refresh token nền giữa hàng trăm lần gọi của 1 tệp lớn).
   Luồng `uploadOne` (whole-file) giữ nguyên dùng `session` như cũ — không
   nằm trong phạm vi AC nào của story này.

## 5. Test plan

### Backend

- Unit (`UploadSessionServiceTest`, Mockito, theo pattern test unit hiện có
  nếu có, hoặc `@SpringBootTest` nhẹ nếu service phụ thuộc nhiều repository):
  - `init`: batch không `OPEN` → lỗi đúng như `loadOpenBatch`; batch
    `kind=PERSONAL` → `BATCH_NOT_FOUND`; `declaredSizeBytes >
    max_file_size_bytes` → `FILE_TOO_LARGE` không tạo row.
  - `chunk`: offset khớp → `receivedBytes` tăng đúng; offset lệch →
    `CHUNK_OFFSET_MISMATCH`, không ghi đè file; ghi vượt `declaredSizeBytes`
    → `CHUNK_OVERFLOW`; gửi khi `status != OPEN` → `UPLOAD_SESSION_NOT_OPEN`.
  - `complete`: đủ byte → gọi đúng 1 lần `ingestBatchFile` với đúng
    `relativePath`/`note`/mimeType; gọi `complete` lần 2 trên session đã
    `COMPLETED` → **không** gọi lại `ingestBatchFile` (verify Mockito
    `times(1)`), trả lại đúng `result_body` đã lưu; thiếu byte →
    `UPLOAD_SESSION_INCOMPLETE`; sau `complete` (dù thành công hay lỗi
    nghiệp vụ) → temp file trên đĩa đã bị xoá.
  - Ownership: `findByIdAndCreatedBySub` với sub khác → 403 ở cả 3 endpoint
    chunk/status/complete (AC-7 - đây là test case **bắt buộc**, map trực
    tiếp AC-7 của story).

- Integration (`UploadSessionControllerIT`, Testcontainers Postgres + Redis,
  MockMvc + `SecurityMockMvcRequestPostProcessors.jwt(...)`, mock
  `ObjectStorageService`/`ClamdService` — theo đúng pattern
  `IngestControllerIT` hiện có):
  - Full flow: `init` → 3 chunk (chunk cuối lệch kích thước, không chia hết
    `chunkSizeBytes`) → `complete` → `GET /documents` thấy đúng 1 document,
    `sha256` khớp SHA-256 của toàn bộ payload gốc (khẳng định ráp đúng thứ
    tự byte).
  - AC-5/AC-6: mock `ClamdService` trả `INFECTED` cho tệp đã ráp ở `complete`
    → response giống hệt shape lỗi của whole-file (`MALWARE_DETECTED`,
    không có document `clean` nào được tạo, `upload_batch.rejected_file_count`
    tăng).
  - AC-7: JWT của user B gọi `PUT chunks`/`GET status`/`POST complete` lên
    session do user A tạo → `403` ở cả 3 endpoint.
  - `GET /ingest/limits` trả thêm `chunkThresholdBytes` đúng giá trị default
    (20MB) khi chưa có override trong `system_config`.

### Frontend (thêm test runner - xem mục 6, chưa có sẵn trong repo)

- `uploadOneChunked` với file giả lập lớn hơn ngưỡng: assert gọi đúng chuỗi
  `fetch`: 1 init, N chunk (N = ceil(size/chunkSize)), 1 complete; tổng số
  byte gửi qua các chunk PUT == kích thước file (không thiếu/thừa).
- **Test bắt buộc theo mục Phụ thuộc của story (Quyết định 5 SA):** mock
  `getSession()` trả về 2 giá trị `accessToken` khác nhau theo thời gian
  (giả lập NextAuth refresh giữa chừng, ví dụ đổi giá trị sau lần gọi thứ
  5); chạy `uploadOneChunked` với file đủ lớn để vượt 5 chunk; assert header
  `Authorization` của các request PUT chunk **sau** thời điểm đổi mang token
  mới, không phải token đã "đóng băng" từ đầu `submit()`. Test này verify
  đúng hệ quả Quyết định 5 đã ghi ở SA mục 6 (rủi ro mức Trung bình) — không
  coi AC-4 hoàn thành trên tệp rất lớn nếu thiếu test này (yêu cầu tường minh
  từ story).
- AC-9: mock 2 lần lỗi transient liên tiếp trên cùng 1 chunk rồi thành công ở
  lần thử ngoài thứ 3 → assert chuỗi `outcome.phase` chỉ gồm
  `queued → uploading → ingested` (không có state "retrying" trung gian nào
  được set qua `updateOutcome`); mock lỗi transient vượt quá số lần thử →
  assert `outcome.phase` chuyển thẳng `uploading → error` (đúng 1 lần
  chuyển, không nhấp nháy qua state khác).
- Case biên: file đúng bằng ngưỡng (`size === chunkThresholdBytes`) đi
  đường nào cần chốt rõ (đề xuất: `<=` ngưỡng vẫn đi whole-file, khớp AC-2
  dùng chữ "≤ 20MB" - so sánh `file.size > limits.chunkThresholdBytes`, đã
  viết đúng ở mục 4.5 bước 2).

## 6. Task breakdown

### Backend

- [x] Task B1: Migration `V16__upload_session.sql` (bảng + 2 index, theo mục 2.1).
- [x] Task B2: 2 khoá `system_config` mới trong `SystemConfigDefaults`
      (`upload.chunk_threshold_bytes`, `upload.chunk_size_bytes`) + thêm
      `chunkThresholdBytes` vào `IngestController.LimitsView`/
      `UploadBatchService.Limits`.
- [x] Task B3: Package `com.aismartstore.backend.upload` — entity
      `UploadSession`, `UploadSessionStatus` (String constants), repository
      `UploadSessionRepository` (kèm `findByIdForUpdate` khoá pessimistic).
- [x] Task B4: `PathMultipartFile` (implements `MultipartFile` từ 1 `Path` +
      `originalName`/`contentType` khai báo) — dùng lại nguyên vẹn ở spec 02.
- [x] Task B5: `UploadSessionService` — `init`/`appendChunk`/`getStatus`/
      `complete`, đúng logic mục 4.1-4.4, tái dùng
      `uploadBatchService.loadOpenBatch` và `documentsService.ingestBatchFile`
      không sửa 2 hàm đó.
- [x] Task B6: `UploadSessionController` (`/upload-sessions/**`) + thêm
      method `initUploadSession` vào `IngestController`
      (`/ingest/batches/{batchId}/upload-sessions`).
- [x] Task B7: Unit test `UploadSessionServiceTest` + integration test
      `UploadSessionControllerIT` (mục 5 - Backend), bao gồm test ownership
      AC-7 và test idempotent-replay của `complete`.

### Frontend

- [x] Task F1: `web/src/lib/api.ts` — thêm `chunkThresholdBytes` vào
      `IngestLimits`; thêm type cho response của 4 endpoint mới
      (`OpenUploadSessionResponse`, `ChunkAckResponse`,
      `UploadSessionStatusResponse`) — `complete` tái dùng `IngestFileResult`
      có sẵn, không cần type mới.
- [x] Task F2: Hàm `uploadOneChunked` trong `upload/page.tsx` theo mục 4.5 -
      dùng `Blob.slice()`, vòng lặp resume qua `GET status`, retry
      3-lần/backoff giống `uploadOne`, dùng chung `updateOutcome`/
      `FileOutcome`/`OutcomeBadge` (không thêm outcome state mới - AC-8/AC-9).
- [x] Task F3: Đổi mọi lệnh gọi API bên trong `uploadOneChunked` sang đọc
      token qua `getSession()` (import `next-auth/react`) thay vì biến
      `session` đóng khung (Quyết định 5) — **review kỹ điểm này**, đây là
      điều kiện để AC-4 đúng trên tệp rất lớn.
- [x] Task F4: Rẽ nhánh whole-file/chunked trong `submit()` theo
      `limits.chunkThresholdBytes` (AC-1/AC-2), không đổi hành vi nhánh
      whole-file hiện có.
- [x] Task F5: Thêm test runner cho `web/` (chưa có sẵn trong repo - đề xuất
      Vitest + Testing Library, phù hợp Next.js App Router hơn Jest) +
      2 test bắt buộc ở mục 5 (chuỗi request chunk, refresh-token giữa
      vòng lặp) + test AC-9.

## 7. Rủi ro kỹ thuật

- Resume chỉ hoạt động trong 1 lượt `submit()` đang chạy, không sống sót qua
  việc đóng hẳn tab/tải lại trang (xem mục 0) — **đã xác nhận với người
  duyệt: giữ đúng phạm vi này**, không mở rộng. Endpoint `status` vẫn thiết
  kế đủ để làm resume-sau-khi-đóng-tab sau này qua một story riêng nếu cần,
  không phải đổi schema.
- **Idempotent-replay của `complete`** (mục 4.4) là điểm phức tạp nhất trong
  spec này — sai ở đây có thể tạo document trùng lặp khi client retry sau
  khi mất response. Bắt buộc có test unit riêng (mục 5) trước khi coi Task
  B5 là xong.
- `web/` chưa có test runner nào (khảo sát `package.json` xác nhận) — **đã
  xác nhận với người duyệt: thêm Vitest trong đợt implement này (Task F5)**,
  không defer, để có test tự động cho 2 test bắt buộc theo Quyết định 5.
- Chunk PUT dùng body nhị phân thô (`application/octet-stream`), không qua
  `spring.servlet.multipart.*` (vốn giới hạn 2048MB cho multipart) — cần
  xác minh Tomcat không có giới hạn ẩn khác (`server.tomcat.max-swallow-size`
  mặc định 2MB có thể áp cho phần "swallow" khi lỗi, không phải request body
  bình thường, nhưng nên verify bằng 1 chunk 8MB thật trong integration
  test thay vì suy luận lý thuyết).
- Ghi `received_bytes` mỗi chunk (khoá row `PESSIMISTIC_WRITE`) tăng tải ghi
  DB nếu nhiều người tải tệp lớn đồng thời — đã chấp nhận ở SA mục 6 (rủi ro
  Thấp), không xử lý thêm ở spec này.
