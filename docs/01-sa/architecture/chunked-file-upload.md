# Architecture: chunked-file-upload

> File: `docs/01-sa/architecture/chunked-file-upload.md` — Owner: SA
> Status: Approved

## 1. Bối cảnh / Vấn đề

- Luồng ingest hiện tại (`data-ingestion.md`, đã Approved) tải mỗi tệp bằng
  **một request multipart nguyên khối**: `POST /ingest/batches/{id}/files`.
  Giới hạn kích thước tệp hiện tại lên tới 2 GB
  (`app.upload.max_file_size_bytes`, mặc định qua `SystemConfigDefaults`).
- Retry hiện có ở client (`web/src/app/upload/page.tsx`, hàm `uploadOne`)
  chỉ xử lý lỗi transient (503/network), tối đa 3 lần, nhưng **mỗi lần retry
  gửi lại toàn bộ tệp từ đầu** — không resume theo offset. Với tệp lớn (hàng
  trăm MB–2 GB) trên mạng không ổn định (đúng bối cảnh mạng cách ly nêu ở
  `data-ingestion.md` §2), một lần đứt giữa chừng gần cuối tệp coi như mất
  toàn bộ công sức tải, ảnh hưởng trực tiếp NFR "gián đoạn → tải lại được"
  đã cam kết ở `data-ingestion.md` §3 (Availability).
- Vấn đề cần giải quyết: đổi cơ chế truyền tải tệp lớn sang **chunked,
  resumable** — chia tệp thành nhiều phần nhỏ, resume từ phần đã nhận thay
  vì tải lại từ đầu — mà **không phá vỡ pipeline xử lý an toàn/toàn vẹn đã
  chốt** (hash SHA-256 → quét ClamAV → lưu SeaweedFS, chạy trên tệp đã hoàn
  chỉnh, fail-closed khi scanner không khả dụng).
- In-scope (đã chốt sau review, mở rộng so với bản Draft đầu tiên):
  - Luồng ingest chính: `POST /ingest/batches/{id}/files` và
    `web/src/app/upload/page.tsx`.
  - Luồng `personal/batches/...` (FR-14, xem `personal-storage-quota.md`) —
    gộp làm cùng đợt implement này, không tách riêng sau. Khả thi vì
    `PersonalService.uploadFileInBatch` đã dùng chung entity `UploadBatch`
    (qua `BatchKind`) và cùng pipeline buffer→SHA-256→ClamAV→
    `ObjectStorageService.putObject` với luồng ingest — chỉ khác là bỏ qua
    bước check allowlist định dạng (xem ghi chú ở Quyết định 2).
  - Giao thức chunk/resume phía transport, entity phiên tải mới, cleanup
    phiên bỏ dở, ngưỡng chuyển whole-file/chunked — dùng chung cho cả hai
    luồng trên.
- Out-of-scope:
  - Thay đổi cơ chế lưu trữ SeaweedFS/S3 API (đã chốt ở `data-ingestion.md`
    §5) — chunk chỉ tối ưu tầng transport client↔server, không đổi cách ghi
    object storage.
  - Quét mã độc theo từng phần (partial scan) — không xem xét, vì phá vỡ
    invariant fail-closed đã chốt.

## 2. Ràng buộc (Constraints)

- **Không đổi invariant "scan trước khi lưu"** đã chốt ở
  `data-ingestion.md`: ClamAV phải chạy trên tệp đã hoàn chỉnh, đồng bộ,
  fail-closed; tệp nhiễm mã độc tuyệt đối không vào kho (FR-12).
- Pipeline `DocumentsService.ingestBatchFile` (buffer → check định dạng →
  SHA-256 → ClamAV → `ObjectStorageService.putObject`) là logic nghiệp vụ đã
  đúng và đã kiểm thử — chunk hoá phải là lớp **thêm vào phía trước**, không
  viết lại pipeline này.
- Server hiện ghi tệp xuống đĩa local 2 lần trước khi lên SeaweedFS (Tomcat
  multipart spool + `DocumentsService.bufferToTempFile()` copy lại) — nằm
  ngoài phạm vi tối ưu của tài liệu này, chỉ ghi nhận làm rủi ro (mục 6).
- Mạng cách ly hoàn toàn (đã nêu ở `data-ingestion.md` §2) → không thể dùng
  dịch vụ resumable-upload của bên thứ ba (vd Cloudflare, S3 Transfer
  Acceleration); giải pháp phải tự lưu trữ (self-hosted), tương thích hạ
  tầng SeaweedFS/PostgreSQL/Redis đã có.
- Access token Keycloak sống ngắn (5 phút) — một phiên tải chunked có thể
  kéo dài hơn thời gian sống của một token trên mạng chậm/tệp rất lớn.

## 3. NFR (Non-Functional Requirements)

| Loại | Yêu cầu |
|---|---|
| Performance | Chunk size đủ lớn để không tăng đáng kể số round-trip so với hiện tại, đủ nhỏ để resume không phải tải lại nhiều dữ liệu khi đứt kết nối |
| Security | Giữ nguyên invariant scan-before-store fail-closed (NFR-04, đã chốt); phiên tải phải gắn với đúng người dùng đã mở batch, không cho phép ghi chunk vào session của người khác |
| Scalability | Không tăng đáng kể tải hệ thống với batch nhiều tệp nhỏ — file nhỏ vẫn đi đường whole-file cũ |
| Availability | Gián đoạn kết nối giữa chừng tệp lớn → resume từ offset đã nhận, không tải lại từ đầu (nới rộng NFR đã cam kết ở `data-ingestion.md` §3, vốn mới chỉ đúng ở mức "tệp", giờ đúng ở mức "phần của tệp") |
| Khác | Không để rác temp file/phiên bỏ dở tích tụ trên đĩa server khi client đóng tab giữa chừng |

## 4. Phương án & Quyết định (ADR)

### Quyết định 1: Giao thức chunk/resume

- **Ngữ cảnh:** Cần chọn giao thức truyền tải chunk giữa client và server,
  đảm bảo resumable và tương thích với pipeline scan-before-store hiện có.
- **Các phương án xem xét:**
  - A) **Session-based, offset-based append (tự thiết kế, tối giản)**: init
    phiên → gửi từng chunk theo offset → server append vào một temp file →
    complete để trigger pipeline hiện có trên temp file đã ghép đủ.
  - B) Áp dụng chuẩn **tus.io resumable upload protocol** (HTTP `PATCH` +
    header `Upload-Offset`/`Upload-Length`, có thư viện client/server có
    sẵn).
  - C) **S3 Multipart Upload API** (`createMultipartUpload`/`uploadPart`/
    `completeMultipartUpload`) ghi thẳng từng phần lên SeaweedFS qua S3 SDK
    đã dùng sẵn (`ObjectStorageService`).
- **Quyết định:** A — session-based, offset-based append, tự thiết kế theo
  mô hình init/chunk/status/complete.
- **Lý do:**
  - C bị loại ngay: S3 multipart ghi từng part thẳng vào object storage
    trước khi có tệp hoàn chỉnh để scan — **xung đột trực tiếp với invariant
    scan-before-store fail-closed**. Muốn dùng C vẫn phải tải toàn bộ part
    về để ráp và scan ở đâu đó trước khi coi là "vào kho", nên không tiết
    kiệm được gì so với A/B mà lại phức tạp hơn (phải quản lý cả SeaweedFS
    multipart state lẫn temp file cục bộ).
  - B (tus.io) là lựa chọn hợp lý về mặt kỹ thuật (chuẩn mở, đã có client JS
    `tus-js-client`), nhưng kéo theo phụ thuộc thư viện mới ở cả client
    (Next.js) và server (Spring Boot chưa có tus server implementation
    chính thức cho Java, phải tự viết phần lớn logic dù theo chuẩn) — với
    quy mô yêu cầu hiện tại (một luồng nghiệp vụ nội bộ, không phải nền
    tảng upload đa khách hàng) chưa đủ lợi ích để đánh đổi thêm dependency
    và đường cong học tập.
  - A tái sử dụng tối đa hạ tầng đã có: pattern `UploadBatch` +
    `expiresAt`, temp-file buffering đã có sẵn trong
    `DocumentsService.bufferToTempFile`, và endpoint `complete` chỉ cần gọi
    lại `ingestBatchFile` hiện có — thay đổi tối thiểu, rủi ro thấp nhất.
- **Hệ quả (trade-off):** Giao thức tự thiết kế không có client library
  sẵn (phải tự viết logic chia chunk + resume ở `upload/page.tsx`), và
  không tương thích công cụ debug/monitoring có sẵn cho tus.io. Đánh đổi
  chấp nhận được vì đổi lại là bề mặt thay đổi nhỏ, dễ review, khớp đúng
  pipeline hiện có.

### Quyết định 2: Entity `UploadSession` và nơi lưu trạng thái

- **Ngữ cảnh:** Cần một nơi lưu trạng thái "đã nhận tới offset nào" cho mỗi
  phiên tải chunk, sống trong một batch đã mở (`UploadBatch`).
- **Các phương án xem xét:**
  - A) Bảng PostgreSQL mới `upload_session`, theo đúng pattern
    `upload_batch` hiện có (entity JPA, quan hệ FK tới `upload_batch`).
  - B) Lưu hoàn toàn trong Redis (key theo `sessionId`, TTL tự động).
  - C) Kết hợp: metadata phiên (sessionId, batchId, fileName, expectedSize,
    trạng thái) ở PostgreSQL để bền vững/audit được; **offset đã nhận** ở
    Redis (cập nhật mỗi chunk, tần suất ghi cao) để tránh ghi DB liên tục
    trên đường dẫn nóng.
- **Quyết định (đã chốt):** A — bảng PostgreSQL `upload_session`, không
  tách riêng offset sang Redis.
- **Lý do:** `data-ingestion.md` đã chốt dùng Redis riêng cho cache
  allowlist định dạng (đọc nhiều/đổi hiếm) — bối cảnh khác với offset chunk
  (ghi thường xuyên nhưng chỉ đọc lại khi resume, không phải đường dẫn nóng
  của toàn hệ thống, chỉ nóng trong phạm vi một phiên tải). Việc offset
  nằm trong PostgreSQL cùng bảng với các cột trạng thái khác giữ cho logic
  đơn giản, không cần đồng bộ hai nguồn dữ liệu (DB cho metadata + Redis
  cho offset) trong 1 transaction. Quyết định chờ đo tải thực tế trước khi
  cân nhắc tách sang Redis — đã xác nhận với người duyệt, không còn là câu
  hỏi mở (xem mục 7).
  Schema đề xuất `upload_session`: `id (UUID)`,
  `batch_id (FK upload_batch)` — dùng chung cho cả hai `BatchKind` (ingest
  và personal, vì `PersonalService` đã tái sử dụng entity `UploadBatch`,
  không cần bảng batch riêng), `original_name`, `relative_path`,
  `declared_size_bytes`, `received_bytes` (offset hiện tại),
  `temp_file_path`, `status` (`OPEN`/`COMPLETED`/`ABORTED`/`EXPIRED`),
  `created_by`, `expires_at`, `created_at`, `updated_at` — cùng kiểu cột
  `expires_at` đã dùng ở `upload_batch`.
- **Ghi chú áp dụng cho cả hai luồng (ingest + personal):** bước `complete`
  phải dispatch đúng service xử lý theo `BatchKind` của `upload_batch` liên
  kết — `DocumentsService.ingestBatchFile` (có check allowlist định dạng)
  cho batch kind ingest, `PersonalService.uploadFileInBatch` (bỏ qua check
  allowlist, theo đúng hành vi hiện tại — Story 1 AC-2 của
  `personal-storage-quota.md`) cho batch kind personal. Lớp chunk-transport
  KHÔNG được tự ý thêm/bớt bước xử lý nghiệp vụ giữa hai luồng — chỉ đóng
  vai trò ráp tệp rồi gọi đúng service đã có, giữ nguyên khác biệt hành vi
  đã chốt trước đó giữa hai luồng.
- **Hệ quả (trade-off):** Ghi `received_bytes` vào PostgreSQL mỗi chunk
  tăng số lần UPDATE so với phương án Redis-only; chấp nhận được vì chunk
  size dự kiến đủ lớn (xem Quyết định 4) nên tần suất UPDATE mỗi tệp không
  cao (vài chục lần cho tệp 2GB, không phải hàng nghìn).

### Quyết định 3: Cleanup phiên bỏ dở

- **Ngữ cảnh:** Client đóng tab/mất mạng vĩnh viễn giữa chừng để lại
  `upload_session` ở trạng thái `OPEN` và temp file trên đĩa server mãi mãi
  nếu không dọn.
- **Các phương án xem xét:**
  - A) Mirror đúng cơ chế `expiresAt` của `UploadBatch`: mỗi `UploadSession`
    có `expires_at` (đề xuất: 24h kể từ lần nhận chunk gần nhất, gia hạn mỗi
    khi có chunk mới tới), job định kỳ (scheduled task, đã có pattern tương
    tự cho batch hết hạn) quét và xoá session + temp file quá hạn.
  - B) Không có job riêng, dọn "lazy" khi có request mới chạm tới session đã
    hết hạn.
- **Quyết định:** A.
- **Lý do:** B để lại rác vĩnh viễn nếu không có request nào chạm lại
  session đó (đúng kịch bản "đóng tab, không quay lại") — không giải quyết
  được vấn đề đầy đĩa nêu ở NFR. A tái dùng đúng pattern đã có cho
  `UploadBatch`, nhất quán về vận hành (một job dọn dẹp chung, không phải
  hai cơ chế khác nhau cho hai loại thực thể tạm thời).
- **Hệ quả (trade-off):** Cần thêm một scheduled job (hoặc mở rộng job dọn
  batch hết hạn hiện có, nếu đã tồn tại, để xử lý luôn `upload_session`) —
  chi phí vận hành nhỏ, chấp nhận được.

### Quyết định 4: Ngưỡng chuyển whole-file vs chunked

- **Ngữ cảnh:** Không phải mọi tệp cần đi đường chunk — tệp nhỏ đi round-trip
  init/complete thêm là overhead không cần thiết.
- **Quyết định (đã chốt, không qua POC đo thử):** Ngưỡng **20 MB**. Tệp ≤
  20MB tiếp tục dùng đường whole-file hiện có (`POST
  /ingest/batches/{id}/files` và tương đương ở `/personal/batches/...`,
  không đổi); tệp > 20MB dùng đường chunked (`init` → `chunks` →
  `complete`), áp dụng cho cả hai luồng ingest và personal. Chunk size
  **8 MB** cố định (server trả về ở bước `init`, có thể chỉnh qua
  system-config giống mô hình `ingest.max_file_size_bytes` đã có, không
  hard-code) — nhưng bản thân giá trị 8MB là hard-code mặc định ban đầu,
  không phải kết quả đo thử.
- **Lý do:** 20MB đủ nhỏ để một request whole-file thất bại giữa chừng
  không gây tốn kém đáng kể khi client retry lại từ đầu (đúng hành vi retry
  hiện có, không cần đổi cho nhóm tệp này); đủ lớn để không đẩy phần lớn tệp
  văn bản/scan thông thường (theo allowlist ở `data-ingestion.md`) sang
  đường chunk phức tạp hơn không cần thiết. 8MB/chunk cân bằng giữa số
  round-trip (tệp 2GB ≈ 256 chunk) và lượng dữ liệu phải gửi lại khi resume
  một chunk lỗi (tối đa 8MB, không phải toàn bộ tệp).
- **Hệ quả (trade-off):** Hai đường xử lý client song song
  (`upload/page.tsx` cần nhánh rẽ theo kích thước tệp) — tăng nhẹ độ phức
  tạp code so với một đường duy nhất, nhưng tránh việc mọi tệp nhỏ đều phải
  trả giá 2 round-trip thêm (init + complete). Vì bỏ qua bước đo thử, chấp
  nhận rủi ro phải điều chỉnh lại 8MB/20MB sau khi có dữ liệu thực tế trên
  mạng nội bộ hệ thống — ghi nhận ở mục 6 (Rủi ro), không phải câu hỏi mở
  chặn duyệt tài liệu.

### Quyết định 5: Access token hết hạn giữa phiên tải dài

- **Ngữ cảnh:** Access token Keycloak sống 5 phút; một phiên chunked cho
  tệp 2GB ở mạng chậm có thể kéo dài hơn 5 phút, nên các chunk request cuối
  có thể mang token đã hết hạn nếu không xử lý.
- **Quyết định:** Không cần cơ chế xác thực riêng cho endpoint chunk. Mỗi
  request chunk là một HTTP request độc lập, tự mang bearer token **đọc lại
  tại thời điểm gửi** (không cache token ở đầu phiên tải) — `apiFetch`
  (`web/src/lib/api.ts`) đã nhận `session` làm tham số ở mỗi lần gọi, và
  `SessionProvider` của NextAuth tự refresh access token nền theo interval
  mặc định. Yêu cầu duy nhất với DEV khi implement: client phải đọc
  `session.accessToken` mới nhất ngay trước mỗi lần gửi chunk (qua
  `useSession()`), không giữ token đã lấy một lần từ đầu hàm `submit`.
- **Lý do:** Đây là cách xử lý nhất quán với toàn bộ luồng ingest hiện tại
  (mỗi request tới `apiFetch` đều mang token tại thời điểm gọi) — không cần
  cơ chế đặc biệt (vd token riêng cho phiên upload, hay endpoint chunk miễn
  xác thực tạm thời) vốn sẽ mở thêm bề mặt tấn công không cần thiết.
- **Hệ quả (trade-off):** Đây là **giả định cần DEV xác nhận bằng test thực
  tế** (không phải điều đã kiểm chứng): phải chứng minh được rằng trong một
  vòng lặp gửi hàng trăm chunk liên tục, `useSession()`/JWT callback của
  NextAuth thực sự refresh kịp và component gọi API đọc được token mới chứ
  không giữ token đã đóng băng từ đầu vòng lặp (rủi ro cụ thể ở
  `useEffect`/closure trong React nếu code không cẩn thận). Giữ lại như rủi
  ro mức Trung bình ở mục 6, không phải câu hỏi mở chặn duyệt tài liệu này
  — vì hướng giải quyết đã rõ, chỉ cần verify khi implement.

## 5. Tech stack đề xuất

- Backend: Spring Boot (đã có), bảng PostgreSQL mới `upload_session` (JPA
  entity theo pattern `UploadBatch`), scheduled job dọn session hết hạn
  (mở rộng job dọn batch hết hạn nếu đã có, hoặc job riêng cùng cơ chế).
  Không thêm dependency mới (không dùng thư viện tus.io server).
- Frontend: Next.js (đã có), `Blob.slice()` (Web API chuẩn, không cần thư
  viện ngoài) để chia chunk trong `web/src/app/upload/page.tsx`.
- Data store: PostgreSQL cho `upload_session` (metadata + offset, xem
  Quyết định 2). Không cần thêm Redis cho tính năng này.
- Infra/Deploy: Không thay đổi hạ tầng (không thêm service mới) — chỉ thêm
  migration DB và endpoint mới trong service Spring Boot hiện có.

## 6. Rủi ro

| Rủi ro | Mức độ | Giảm thiểu |
|---|---|---|
| Access token hết hạn giữa phiên chunk dài, client vẫn gửi token cũ do lỗi giữ token "đóng băng" trong code (xem Quyết định 5) | Trung bình | DEV phải viết test/verify thực tế luồng refresh token trong vòng lặp gửi chunk trước khi coi là xong; review kỹ code đọc `session.accessToken` ở mỗi lần gọi |
| Temp file/`upload_session` bỏ dở tích tụ nếu job cleanup (Quyết định 3) chạy sai hoặc bị tắt | Thấp | Job cleanup dùng đúng pattern đã kiểm chứng của `UploadBatch.expiresAt`; thêm alert/log khi job không chạy đúng chu kỳ (thuộc phạm vi vận hành, không phải quyết định kiến trúc mới) |
| Ghi `received_bytes` vào PostgreSQL mỗi chunk (Quyết định 2) tạo tải ghi DB không cần thiết nếu người dùng tải nhiều tệp lớn đồng thời | Thấp | Chunk size 8MB giữ tần suất UPDATE thấp; nếu sau này đo được là điểm nghẽn, chuyển offset sang Redis (đã ghi ở câu hỏi mở) |
| Hai đường xử lý client (whole-file / chunked) phân kỳ logic theo thời gian, khó bảo trì nếu không viết chung phần retry/hiển thị kết quả | Thấp | Chỉ tách nhánh ở tầng gửi request (chunk hay không), giữ chung `FileOutcome`/`OutcomeBadge` và cơ chế hiển thị kết quả đã có ở `upload/page.tsx` |
| Chunk size 8MB / ngưỡng 20MB chốt không qua POC — có thể sai lệch so với thực tế mạng nội bộ hệ thống (quá nhỏ → quá nhiều round-trip; quá lớn → resume vẫn tốn kém) | Trung bình | Cả hai giá trị đưa vào system-config (không hard-code trong code), chỉnh được không cần deploy lại; theo dõi số liệu thực tế sau khi lên production và điều chỉnh nếu cần |
| Bước `complete` xử lý sai `BatchKind`, gọi nhầm `DocumentsService.ingestBatchFile` cho batch personal hoặc ngược lại → áp sai/thiếu bước check allowlist định dạng | Trung bình | Test tích hợp riêng cho cả hai luồng (ingest + personal) ở bước `complete`, review kỹ điểm dispatch theo `BatchKind` khi implement |

## 7. Câu hỏi mở

_Đã chốt cả 3 câu hỏi mở sau khi người duyệt xác nhận trực tiếp:_

- ~~Có cần tách `received_bytes` sang Redis (phương án C ở Quyết định 2)
  ngay từ đầu?~~ → **Chốt: giữ ở PostgreSQL, không tách Redis.** Chờ đo tải
  thực tế sau khi lên production rồi mới cân nhắc tách nếu cần.
- ~~Có mở rộng cùng cơ chế cho luồng `personal/batches/...` (FR-14) trong
  cùng đợt implement?~~ → **Chốt: có, gộp cùng đợt** (đã cập nhật mục 1 —
  personal-storage chuyển từ out-of-scope sang in-scope).
- ~~Chunk size 8MB và ngưỡng 20MB — chốt luôn hay cần POC đo thử?~~ →
  **Chốt luôn 8MB/20MB, không qua POC.** Rủi ro sai lệch với thực tế mạng
  nội bộ ghi nhận ở mục 6; giảm thiểu bằng cách đưa cả hai giá trị vào
  system-config để chỉnh được không cần deploy lại.

Không còn câu hỏi mở nào cho chunked-file-upload ở bước SA.
