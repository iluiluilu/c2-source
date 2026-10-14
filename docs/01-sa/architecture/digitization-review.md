# Architecture: digitization-review

> File: `docs/01-sa/architecture/digitization-review.md` — Owner: SA
> Status: Approved

## 1. Bối cảnh / Vấn đề

- **FR-18** (SRS mục 2.13): cán bộ (R1) rà soát, sửa **văn bản OCR**, **bản
  chuyển lời nói STT** và **mốc thời gian từng đoạn**, rồi **xác nhận**; chỉ bản
  đã xác nhận mới vào chỉ mục chính thức. Nếu người nạp không thao tác trong
  thời hạn (mặc định **24 giờ**, R3 cấu hình được — FR-01), hệ thống **tự động
  xác nhận** và đánh dấu nguồn "tự động".
- Đây là **cổng chất lượng** giữa kết quả AI (nháp) và dữ liệu nghiệp vụ chính
  thức: NFR-02 coi bước kiểm duyệt là biện pháp bù cho sai sót OCR/STT.
- Đầu vào lấy từ `digitization-pipeline.md` (**Quyết định 3 & 4**): segment
  thống nhất cho OCR/STT (`bbox`/`page_no` hoặc `time_start_ms`/`time_end_ms`),
  `confidence`, `output_quality_flag`, `auto_confirm_blocked`, sự kiện
  `digitization.completed`, `review_deadline_at`.
- **In-scope:** máy trạng thái kiểm duyệt & nguồn xác nhận; cơ chế tự xác nhận
  hết hạn; mô hình lưu bản sửa (giữ bản gốc AI, versioning); quy tắc hợp lệ khi
  sửa mốc thời gian; quyền duyệt & phạm vi FR-08; hàng chờ kiểm duyệt; hợp đồng
  sự kiện bàn giao cho lập chỉ mục; xử lý tài liệu có nhiều run.
- **Out-of-scope (dẫn chiếu, không quyết định lại):**
  - Hạ tầng AI/GPU, hàng đợi, engine OCR/STT, quy tắc phân đoạn, ngưỡng độ tin
    cậy → `digitization-pipeline.md`, `ai-search-infra.md`.
  - Mapping/truy vấn chỉ mục, highlight, định vị trang/dòng → `search-fulltext`
    (**chưa có file** — xem mục 7).
  - Cơ chế lưu/cache tham số cấu hình → `system-config.md`.
  - Hạ tầng nhật ký (chain hash, taxonomy, retention) → `audit-logging.md`.
  - Lớp filter phạm vi nhóm → `rbac-group-scope.md` (tái sử dụng nguyên vẹn).
  - **Hệ thống thông báo/nhắc việc** (email/push): backend hiện **không có**
    module notification (đã kiểm tra `backend/src/main/java/.../`), mạng cách ly
    không có SMTP ⇒ giai đoạn 1 chỉ nhắc **trong ứng dụng** (badge + danh sách
    "sắp hết hạn"), không xây kênh thông báo riêng.
  - Vòng lặp fine-tune model từ dữ liệu người sửa (chỉ **lưu dữ liệu** để dùng
    sau, không xử lý ở giai đoạn 1).

## 2. Ràng buộc (Constraints)

- Ma trận vai trò SRS mục 3: **FR-18 chỉ R1 = X**; R2/R3/R4 = "–" ⇒ lãnh đạo
  không duyệt, quản trị không được đứng trên route dữ liệu nghiệp vụ (NFR-04/07).
- **Bản gốc bất biến** (NFR-05/FR-12): sửa kết quả số hoá tuyệt đối không đụng
  tệp gốc; mọi thứ là bản phái sinh.
- **Mọi chỉnh sửa và mọi lần tự xác nhận phải ghi nhật ký** (FR-09) — dùng
  `audit_log` hiện có, tuân thủ allowlist metadata (không ghi nội dung văn bản
  vào audit).
- Backend có thể chạy **nhiều instance** (`system-config.md`, Quyết định 4) ⇒
  job nền chạy theo lịch phải chống chạy trùng.
- Stack sẵn có: Spring Boot 3 + PostgreSQL 16 + Flyway (mới nhất `V13`) + Redis
  + Keycloak + Next.js. **Chưa có** `@Scheduled`/`@EnableScheduling` trong repo
  (chỉ có `@EnableAsync`) ⇒ đây là hạ tầng mới phải thêm.
- Tham số thời hạn lấy từ `system_config` key `ocr_stt.auto_confirm_hours`
  (mặc định 24h, đã có trong danh mục MVP của `system-config.md`).
- Quy mô: ~1 triệu tài liệu / ~20 triệu segment (`digitization-pipeline` mục 3)
  ⇒ UI kiểm duyệt và job quét hết hạn đều phải phân trang/chia lô.

## 3. NFR (Non-Functional Requirements)

`[ĐX]` = số do SA đề xuất (SRS chỉ nêu định tính), kiểm chứng khi đo tải.

| Loại | Yêu cầu |
|---|---|
| Performance | `[ĐX]` Hàng chờ kiểm duyệt (có filter FR-08) trả ≤ **2s p95**; mở một tài liệu để duyệt ≤ **1,5s p95** với trang 100 segment; lưu một bản sửa ≤ **500ms p95** |
| Performance (đúng hạn) | `[ĐX]` Tự xác nhận xảy ra trong vòng **≤ 5 phút** sau `review_deadline_at`; một lượt quét xử lý ≥ 5.000 run/phút mà không khoá bảng lâu |
| Security | Chỉ **R1 và chính chủ** (`document.uploader_sub = current_user`) mới **sửa/xác nhận**; mọi hành động sửa/xác nhận/tự xác nhận/chuyển quyền sở hữu đều ghi `audit_log`; nội dung văn bản **không** vào audit metadata (chỉ id + số liệu thay đổi). **Đọc nội dung đã `confirmed`** (không sửa được): theo quyền xem tài liệu (`PERM_document.read` + phạm vi nhóm/chủ sở hữu, `rbac-group-scope.md` QĐ 3) — xem addendum cuối Quyết định 5 (2026-09-22) |
| Scalability | Chịu tồn đọng ≥ **100.000 run** ở trạng thái `pending` mà hàng chờ vẫn phân trang bình thường; bảng lịch sử sửa tăng tuyến tính theo số lần sửa, không nhân bản toàn bộ segment |
| Availability | Job tự xác nhận **idempotent** + `SKIP LOCKED`: chạy trùng/chạy lại sau downtime không xác nhận hai lần, không bỏ sót run quá hạn trong thời gian backend dừng |
| Toàn vẹn | Không bao giờ mất bản gốc do AI sinh; mọi bản sửa append-only, truy ngược được "ai sửa gì, lúc nào, từ giá trị nào" |
| Khác | Tỷ lệ `confirm_source = auto` là **chỉ số vận hành** phải đo được (báo cáo/rà soát lại về sau — yêu cầu tường minh của SRS) |

## 4. Phương án & Quyết định (ADR)

### Quyết định 1: Cơ chế tự xác nhận sau 24h — scheduler quét deadline đã snapshot

- **Ngữ cảnh:** hết hạn phải kéo theo **side-effect** (đổi trạng thái, phát sự
  kiện lập chỉ mục, ghi audit), không chỉ là cách hiển thị.
- **Các phương án xem xét:**
  - A) **Lazy-check khi đọc**: tính "đã quá hạn?" mỗi lần truy vấn.
  - B) **Scheduler quét định kỳ** trong backend (Spring `@Scheduled`).
  - C) **Delayed message** RabbitMQ (TTL + DLX) — mỗi run một message hẹn giờ.
- **Quyết định:** **B**, cụ thể:
  - Job `auto-confirm-sweeper` chạy mỗi **1 phút**, chọn lô 500 run:
    `WHERE review_state='pending' AND auto_confirm_blocked=false AND
    review_deadline_at <= now() FOR UPDATE SKIP LOCKED` → không cần ShedLock/
    leader election, nhiều instance chạy song song vẫn an toàn.
  - Mỗi run xử lý trong transaction riêng: đổi trạng thái → `confirmed`, ghi
    `confirm_source='auto'`, `confirmed_by=null`, `confirmed_at=now()`, ghi
    `audit_log` (`digitization.auto_confirmed`), ghi `outbox_event`
    (`review.confirmed`). Idempotent nhờ điều kiện `review_state='pending'`
    trong chính câu UPDATE.
  - **Mốc thời gian:** `review_deadline_at` do pipeline ghi khi run hoàn tất
    (`completed_at + ocr_stt.auto_confirm_hours` — hợp đồng QĐ 4 của
    `digitization-pipeline`). Review **không tự tính lại** từ thời điểm khác.
  - **Snapshot, không đọc runtime:** deadline là **timestamp tuyệt đối** chốt
    tại thời điểm tạo; R3 đổi `ocr_stt.auto_confirm_hours` **chỉ áp dụng cho
    run hoàn tất sau đó**, không hồi tố các run đang chờ. Kèm một thao tác R3
    tường minh "áp lại thời hạn cho các bản đang chờ" (tính lại
    `review_deadline_at`, có xác nhận + audit) làm van xả khi cần.
  - **Kill switch** `ocr_stt.auto_confirm_enabled` (mặc định `true`) để tạm
    dừng toàn bộ tự xác nhận khi phát hiện sự cố chất lượng model.
  - Chặn tự xác nhận khi `auto_confirm_blocked = true` (tài liệu `poor` — QĐ 5
    của pipeline) và khi run đang ở `in_review` (xem Quyết định 2).
- **Lý do:** A không sinh được side-effect (tài liệu không ai mở sẽ **không bao
  giờ** vào chỉ mục — sai FR-18) và làm mọi truy vấn đọc trở thành ghi. C khiến
  deadline nằm trong message: đổi tham số, tạm dừng, hoặc huỷ khi cán bộ sửa
  đều rất khó; mất message = mất tự xác nhận, và cần plugin delayed-message.
  B giữ nguồn sự thật trong PostgreSQL, truy vấn/báo cáo/khắc phục đều dễ.
- **Hệ quả:** thêm `@EnableScheduling` (chưa có trong repo); job dừng lâu ⇒ khi
  chạy lại sẽ xác nhận hàng loạt cùng lúc (chấp nhận được, nhưng phải chia lô
  và phát sự kiện có tiết chế); "snapshot deadline" nghĩa là đổi cấu hình không
  có hiệu lực tức thì như các tham số khác — BA phải nêu rõ trong UI admin.

### Quyết định 2: Máy trạng thái kiểm duyệt & đánh dấu nguồn xác nhận

- **Ngữ cảnh:** SRS bắt buộc phân biệt bản "tự động xác nhận" để rà soát lại
  sau; đồng thời cần biết bản xác nhận thủ công có kèm sửa hay không.
- **Các phương án xem xét:** A) một cờ boolean `is_auto_confirmed` — B) trạng
  thái + trường `confirm_source` tách biệt, ở **cấp run** — C) trạng thái ở
  **cấp từng segment**.
- **Quyết định:** **B**. Đơn vị xác nhận là **một run = (document_id, task)**;
  segment không có trạng thái duyệt riêng.
  - `review_state`: `pending → in_review → confirmed`, cộng `superseded`
    (bị run mới thay thế — QĐ 6) và `void` (tài liệu bị xoá — FR-26).
  - `in_review`: xuất hiện khi có **thao tác ghi đầu tiên** (sửa hoặc "nhận
    duyệt"); ở trạng thái này **đồng hồ 24h dừng**, không tự xác nhận nữa (đúng
    tinh thần SRS "nếu không thực hiện thao tác nào"). Chỉ **mở xem** không đổi
    trạng thái, chỉ ghi `first_viewed_at`/`first_viewed_by` (nếu không, một lần
    mở nhầm sẽ treo tài liệu vĩnh viễn).
  - `confirm_source ∈ { auto, manual_unchanged, manual_edited }` — đúng 3 nguồn
    mà kế hoạch yêu cầu phân biệt; kèm `confirmed_by`, `confirmed_at`,
    `edited_segment_count`, `first_edit_at`.
  - Run ở `in_review` quá lâu: sau `ocr_stt.review_stale_hours` `[ĐX]` **72h**
    hệ thống **không** tự xác nhận mà đưa vào danh sách "quá hạn xử lý" trong
    hàng chờ + audit cảnh báo (đã có người can thiệp thì phải có người kết luận).
- **Lý do:** A không phân biệt được "duyệt mà không sửa" với "sửa rồi duyệt" —
  hai mức độ tin cậy rất khác nhau khi rà soát chứng cứ. C tạo hàng chục triệu
  bản ghi trạng thái, và nghiệp vụ vốn xác nhận cả tài liệu, không từng dòng.
- **Hệ quả:** đã đồng bộ với `digitization-pipeline` (2026-09-07): QĐ 4 mục 1
  của file đó sửa lại để `content_segment` **không** mang field trạng thái
  duyệt — `review_status`/`confirm_source` chỉ tồn tại ở bảng
  `digitization_review` (cấp run) mô tả tại đây. Xác nhận là thao tác "tất cả
  hoặc không" cho một run: không cho phép xác nhận một phần.

### Quyết định 3: Lưu bản sửa — giữ nguyên bản AI, lịch sử append-only + cột hiện hành

- **Ngữ cảnh:** cần (a) đối chiếu AI vs người (cam kết QĐ 4 mục 3 của pipeline),
  (b) lưu vết chỉnh sửa làm chứng cứ (FR-09/NFR-05), (c) đọc nhanh khi lập chỉ
  mục và khi hiển thị.
- **Các phương án xem xét:** A) UPDATE thẳng lên `content_segment`, lịch sử chỉ
  nằm ở `audit_log` — B) **bảng `segment_revision` append-only + cột "hiện
  hành" denormalized trên segment** — C) snapshot toàn bộ tập segment mỗi lần
  xác nhận (versioning cấp tài liệu).
- **Quyết định:** **B**.
  - `content_segment` giữ **nguyên vẹn giá trị do AI sinh** (`ai_text`,
    `ai_time_start_ms`, `ai_time_end_ms`) — bất biến sau khi run kết thúc.
  - `segment_revision (segment_id, revision_no, text, time_start_ms,
    time_end_ms, edited_by, edited_at, reason)` — append-only, `revision_no`
    tăng dần; sửa lại = thêm dòng mới, **không** UPDATE/DELETE.
  - Cột hiện hành trên `content_segment` (`current_text`, `current_time_*`,
    `current_revision_no`, `is_edited`) cập nhật **trong cùng transaction** với
    revision ⇒ lập chỉ mục và hiển thị chỉ đọc một bảng, không JOIN "latest".
  - **Xoá đoạn / thêm đoạn** cũng là revision (`deleted=true` / segment mới có
    `origin='manual'`), không xoá vật lý.
  - Chống ghi đè lẫn nhau: **optimistic lock** theo `current_revision_no` gửi
    kèm request; lệch ⇒ 409, buộc tải lại. Không dùng khoá bi quan/checkout.
- **Lý do:** A làm mất bản gốc AI (không đối chiếu, không fine-tune được) và
  đẩy nội dung nghiệp vụ vào bảng audit — vi phạm allowlist của
  `audit-logging.md` (QĐ 4). C nhân bản tới hàng nghìn segment mỗi lần sửa một
  chữ, phình vô ích ở quy mô 20 triệu segment.
- **Hệ quả:** trùng lặp dữ liệu có kiểm soát (bản AI + bản hiện hành + lịch sử);
  cần kiểm thử bảo đảm hai nơi luôn nhất quán; audit chỉ lưu `segment_id`,
  `revision_no`, số ký tự thay đổi và **trỏ** sang `segment_revision` cho nội
  dung chi tiết.

### Quyết định 4: Chặn mốc thời gian vượt độ dài tệp gốc

- **Ngữ cảnh:** SRS FR-18 (Ngoại lệ): "mốc thời gian chỉnh sửa vượt quá độ dài
  tệp âm thanh/video gốc → **từ chối và giữ giá trị cũ**".
- **Các phương án xem xét:** A) chỉ chặn ở frontend — B) chỉ chặn ở service —
  C) **nhiều tầng, DB là chốt chặn cuối**.
- **Quyết định:** **C**:
  1. **Frontend** (UX): thanh trượt/ô nhập kẹp trong `[0, duration]`, cảnh báo
     tức thời — không tính là biện pháp an toàn.
  2. **Service** (nguồn thông báo lỗi nghiệp vụ): validate
     `0 ≤ start < end ≤ document.duration_ms`, vi phạm ⇒ **422** kèm thông báo,
     **toàn bộ thao tác sửa rollback**, giá trị cũ giữ nguyên (đúng SRS).
  3. **PostgreSQL** CHECK/trigger trên `segment_revision` — tái dùng invariant
     đã chốt ở QĐ 3 của `digitization-pipeline`, chặn cả đường ghi qua script.
  - **Nguồn `duration_ms`:** cột trên `document`, do pipeline đo bằng `ffprobe`
    lúc tiền xử lý (pipeline QĐ 3). Review **không** tự đo lại.
  - Nếu `duration_ms` NULL (tài liệu ảnh/PDF, hoặc ffprobe lỗi): **từ chối mọi
    thao tác sửa mốc thời gian** (422 "chưa xác định độ dài tệp gốc") thay vì
    đoán hoặc bỏ qua kiểm tra — fail-closed.
  - **Không** ép các đoạn rời nhau: cho phép chồng lấn (nhiều người nói cùng
    lúc), chỉ cảnh báo trên UI; chỉ ràng buộc `start < end` cho từng đoạn.
- **Lý do:** A dễ vượt qua bằng gọi API trực tiếp; B đúng nhưng không bảo vệ
  được các đường ghi khác (job sửa lỗi, migration); C khiến ràng buộc trở thành
  bất biến dữ liệu, chi phí thấp vì invariant đã có sẵn từ pipeline.
- **Hệ quả:** phụ thuộc chất lượng `duration_ms`; ffprobe sai/thiếu sẽ chặn oan
  việc sửa ⇒ cần công cụ vận hành đo lại độ dài cho một tài liệu.

### Quyết định 5: Ai được duyệt — chỉ người nạp tài liệu, một cấp duyệt

- **Ngữ cảnh:** SRS chỉ nói "cán bộ" và gắn đồng hồ 24h với **người nạp**; ma
  trận vai trò cho FR-18 chỉ R1 = X.
- **Các phương án xem xét:** A) **chỉ người nạp** (`document.uploader_sub`) mới
  được đọc/sửa/xác nhận — B) mọi R1 nhìn thấy tài liệu theo filter FR-08 — C)
  thêm cấp duyệt thứ hai của R2 (lãnh đạo).
- **Quyết định:** **A**, một cấp duyệt (C02 xác nhận 2026-08-16).
  - Quyền đọc/sửa/xác nhận trong màn hình review = **R1** ∧
    `document.uploader_sub = current_user` — hẹp hơn lớp filter phạm vi nhóm
    dùng chung ở `rbac-group-scope.md` (QĐ 3): filter nhóm là điều kiện cần,
    "chính chủ" là điều kiện đủ bổ sung riêng cho review.
  - Hàng chờ chỉ hiển thị **"tài liệu do tôi nạp"** — **không có** tab "trong
    phạm vi của tôi" cho đồng nghiệp duyệt hộ.
  - R2 (lãnh đạo): **chỉ đọc** kết quả sau khi đã xác nhận qua tìm kiếm/báo
    cáo, không có thao tác duyệt. R3: không truy cập nội dung.
  - **Không có 2 cấp duyệt.**
  - **Rủi ro đã cảnh báo và được C02 chấp nhận:** khi người nạp nghỉ/chuyển
    công tác/vắng mặt dài ngày, tài liệu của họ **không ai duyệt được** cho
    tới khi tự xác nhận sau `ocr_stt.auto_confirm_hours` — auto-confirm trở
    thành lưới đỡ duy nhất, không còn là trường hợp hiếm. Cần bổ sung công cụ
    vận hành cho R3: **chuyển quyền sở hữu tài liệu** (đổi
    `document.uploader_sub`) khi cần, có ghi `audit_log`.
- **Lý do:** đúng sát nghĩa đen SRS, đơn giản nhất để triển khai và quy trách
  nhiệm rõ ràng (không mập mờ kiểu "ai cũng duyệt được thì không ai chịu
  trách nhiệm"). C khiến ma trận vai trò (R1 = X, R2 = "–") sai và làm chậm
  gấp đôi thời gian dữ liệu vào chỉ mục mà không tăng bảo đảm thực chất (một
  cấp đã có thể bị auto-confirm bỏ qua sau 24h thì cấp hai cũng vậy).
- **Hệ quả:** thêm thao tác vận hành mới **"chuyển quyền sở hữu tài liệu"**
  (ngoài phạm vi ADR này — BA cần bổ sung story riêng); `confirmed_by` luôn
  trùng `document.uploader_sub` tại thời điểm xác nhận (trừ `confirm_source
  = auto`); nếu khách hàng sau này đổi ý muốn đồng nghiệp duyệt hộ, quay lại
  phương án B.

**Addendum (2026-09-22, xác nhận trực tiếp với người dùng — không qua BA,
xem `docs/03-dev/specs/digitization-review-07-confirmed-content-view.md`):**
sau khi triển khai, phát hiện không có màn hình nào để xem lại nội dung đã
`confirmed` — dòng 216 gốc ("R2 chỉ đọc sau khi xác nhận") giả định đường đọc
là qua tìm kiếm/báo cáo, nhưng chính người nạp (R1) cũng cần xem lại tài liệu
mình đã duyệt và không có đường nào ngoài đó. Quyết định sửa cho riêng
**đường đọc** (không đổi đường sửa/xác nhận, vẫn giữ nguyên "R1 + chính chủ"
ở trên):

- Đọc nội dung `confirmed` (chỉ đọc, không sửa được) mở rộng theo **quyền xem
  tài liệu** (`PERM_document.read` + phạm vi nhóm/chủ sở hữu, không còn giới
  hạn theo vai trò R1/R2/R3 hay "chính chủ"). R3 hiện không có
  `PERM_document.read` (V12) nên trên thực tế vẫn không đọc được — nếu sau
  này permission đó được cấp thêm cho R3 (qua `rbac-permission-editing.md`),
  R3 sẽ đọc được theo đúng tinh thần quyết định mới này (không phải lỗ hổng).
- Chỉ áp dụng cho run **đã `confirmed`** — run `pending`/`in_review` vẫn chỉ
  R1 chính chủ đọc/sửa được qua màn hình review như cũ.
- Không đổi hàng chờ kiểm duyệt (`/digitization`, `PERM_digitization_review.manage`)
  — vẫn của riêng R1 chính chủ như Quyết định 5 gốc.

### Quyết định 6: Quan hệ với lập chỉ mục + xử lý tài liệu có nhiều run

- **Ngữ cảnh:** SRS: "chỉ bản đã xác nhận mới vào chỉ mục chính"; pipeline QĐ 4
  mục 4–5 giao cho review việc phát sự kiện index và quyết định thay thế run.
- **Các phương án xem xét:** A) index ngay khi có kết quả, gắn cờ `pending` và
  lọc khi truy vấn — B) **chỉ index sau khi xác nhận** — C) index vào một chỉ
  mục "nháp" riêng.
- **Quyết định:** **B**.
  - `digitization-review` là **bên duy nhất** phát `review.confirmed` (qua
    `outbox_event`, schema sự kiện dùng chung ở `ai-search-infra`), payload:
    `document_id`, `run_id`, `task`, `confirm_source`, `confirmed_at`,
    `segment_count`.
  - Sửa **sau khi đã xác nhận** vẫn cho phép (đính chính) ⇒ phát
    `review.reconfirmed` → indexer upsert theo `segment_id`.
  - **Nhiều run:** run mới (do đổi/nâng cấp model) vào `pending`; bản
    `confirmed` cũ **vẫn phục vụ tìm kiếm** cho tới khi run mới được xác nhận,
    lúc đó run cũ chuyển `superseded` và indexer thay thế theo `run_id`.
  - Run **re-run không áp auto-confirm** (`auto_confirm_blocked=true` mặc
    định): không có "người nạp đang chờ", và tự động thay bản người đã sửa
    bằng bản AI mới là rủi ro mất công sức hiệu đính. Màn hình duyệt hiển thị
    **so sánh cạnh nhau** bản đã xác nhận cũ và kết quả run mới; bản sửa tay cũ
    **không** tự chuyển sang run mới (phân đoạn khác nhau).
  - Trước khi xác nhận, cán bộ vẫn tìm/lọc được nội dung **trong màn hình
    duyệt** (truy vấn thẳng PostgreSQL theo tài liệu), không qua search engine.
- **Lý do:** B là yêu cầu tường minh của SRS và của NFR-02 (chỉ mục chính thức
  không chứa dữ liệu chưa rà soát). A/C tạo hai chất lượng dữ liệu trong cùng
  hệ tra cứu, rủi ro cán bộ trích dẫn nhầm bản nháp làm chứng cứ.
- **Hệ quả:** nội dung mới không tra cứu được **tối đa 24h** — phải nêu rõ
  trong UI và trong tài liệu bàn giao; hợp đồng sự kiện với `search-fulltext`
  là **dependency chưa chốt** (chưa có file SA).

## 5. Tech stack đề xuất

- **Backend:** Spring Boot 3, module mới `digitization/review/` — `ReviewService`
  (đọc hàng chờ, ghi revision, xác nhận), `AutoConfirmScheduler`
  (`@Scheduled` + `SKIP LOCKED`; cần thêm `@EnableScheduling` vào
  `BackendApplication`), consumer `digitization.completed`, publisher outbox.
  Tái dùng: `AuditService`, lớp filter phạm vi FR-08, `SystemConfigService`
  (Redis cache).
- **Frontend:** Next.js — trang `web/src/app/review/`: (1) hàng chờ có filter
  (task, trạng thái, cờ chất lượng, sắp hết hạn, người nạp), (2) màn OCR
  "ảnh gốc + overlay bbox ↔ ô sửa văn bản", (3) màn STT "player đồng bộ +
  danh sách đoạn có thể sửa text và mốc thời gian", (4) diff AI ↔ bản sửa.
- **Data store (PostgreSQL, Flyway tiếp sau `V13`):** `digitization_review`
  (trạng thái/nguồn xác nhận theo run), `segment_revision` (append-only), cột
  `current_*`/`is_edited` bổ sung trên `content_segment`, `duration_ms` trên
  `document` (do pipeline ghi), `outbox_event` (dùng chung).
- **Tham số `system_config` bổ sung:** `ocr_stt.auto_confirm_enabled` (bool,
  mặc định `true`), `ocr_stt.review_stale_hours` (`[ĐX]` 72).
  `ocr_stt.auto_confirm_hours` đã có. Chu kỳ quét của scheduler là tham số hạ
  tầng ⇒ để `application.yml`, không đưa vào UI (theo QĐ 1 của `system-config`).
- **Infra/Deploy:** không thêm thành phần hạ tầng mới (không RabbitMQ delayed
  plugin, không ShedLock); tận dụng Prometheus/Grafana cho các chỉ số hàng chờ.

## 6. Rủi ro

| Rủi ro | Mức độ | Giảm thiểu |
|---|---|---|
| **Tự xác nhận biến thành mặc định**: đa số tài liệu vào chỉ mục mà không ai đọc, sai sót OCR/STT thành "dữ liệu chính thức" | **Cao** | Chặn tự xác nhận với tài liệu `poor` (pipeline QĐ 5); `confirm_source='auto'` hiển thị rõ trên kết quả tra cứu; đo tỷ lệ auto hằng tuần; bộ lọc "rà soát lại bản tự xác nhận"; kill switch |
| **Người nạp vắng mặt** (nghỉ/chuyển công tác/nghỉ dài ngày) ⇒ không ai duyệt được tài liệu của họ, chỉ còn chờ tự xác nhận (QĐ 5, phương án A — rủi ro đã được C02 chấp nhận) | **Cao** | Auto-confirm 24h là lưới đỡ cuối; bổ sung công cụ vận hành R3 "chuyển quyền sở hữu tài liệu" (`document.uploader_sub`, có audit) — **ngoài phạm vi ADR này, BA cần thêm story** |
| Tồn đọng hàng chờ lớn (lô nhập hàng nghìn tệp) khiến cán bộ bỏ qua toàn bộ | **Cao** | Sắp xếp theo rủi ro (nhiều segment confidence thấp lên trước); duyệt hàng loạt **chỉ** cho tài liệu không có cờ chất lượng; lọc theo "sắp hết hạn" |
| Deadline snapshot ⇒ R3 đổi `auto_confirm_hours` tưởng có hiệu lực ngay nhưng không | Trung bình | Nêu rõ trên UI admin; cung cấp thao tác "áp lại thời hạn cho bản đang chờ" có audit |
| Hai người sửa cùng một tài liệu, ghi đè công sức của nhau | Trung bình | Optimistic lock theo `current_revision_no` (409); hiển thị người đang mở duyệt |
| Backend dừng lâu ⇒ khi khởi động lại tự xác nhận hàng loạt cùng lúc, dội tải indexer | Trung bình | Xử lý theo lô 500/lượt; outbox publish có tiết chế; cảnh báo khi số run quá hạn > ngưỡng |
| `duration_ms` sai/thiếu ⇒ chặn oan việc sửa mốc thời gian | Trung bình | Fail-closed + công cụ vận hành đo lại độ dài; log rõ lý do từ chối |
| Bảng `segment_revision` phình theo khối lượng hiệu đính | Thấp–TB | Chỉ lưu đoạn có sửa (không snapshot toàn bộ); partition theo thời gian nếu vượt ngưỡng |
| ~~Lệch hợp đồng với `digitization-pipeline` (review_status cấp segment vs cấp run)~~ — đã đồng bộ 2026-09-07, `content_segment` không còn field trạng thái duyệt | Đã xử lý | QĐ 4 mục 1 của `digitization-pipeline.md` đã sửa khớp cấp run theo QĐ 2 của file này |
| UI STT (player đồng bộ + sửa mốc thời gian) tốn effort frontend hơn dự kiến | Trung bình | BA tách story OCR trước, STT sau; giai đoạn 1 dùng thư viện player sẵn có, không tự dựng waveform editor |

## 7. Câu hỏi mở

Toàn bộ câu hỏi dưới đây đã được C02 trả lời ngày 2026-08-16; giữ lại như
bản ghi quyết định (decision log), không còn điểm nào chờ chốt.

- [x] **Cấp của trạng thái duyệt:** chốt **cấp run** (QĐ 2). **Đã xử lý
      2026-09-07:** `digitization-pipeline.md` (nay đã `Approved`) được sửa lại
      QĐ 4 mục 1 cho khớp cấp run — `content_segment` không còn field
      `review_status`, trạng thái duyệt chỉ tồn tại ở bảng `digitization_review`
      mô tả tại file này.
- [x] **Thứ tự duyệt file:** `digitization-pipeline.md` đã `Approved`; việc
      đồng bộ cấp trạng thái duyệt đã hoàn tất cùng ngày rà soát file này
      (2026-09-07). Mọi dẫn chiếu sang QĐ 3/4/5 của pipeline (segment,
      `review_deadline_at`, `auto_confirm_blocked`, `duration_ms`, invariant
      mốc thời gian) nay là **chính thức**, không còn tạm thời.
- [x] **`ocr_stt.review_stale_hours = 72`** (run `in_review` bỏ dở): giữ
      nguyên 72h; xác nhận trường hợp này **không** tự xác nhận, chỉ đưa vào
      danh sách "quá hạn xử lý" + audit cảnh báo.
- [x] **Ai được duyệt:** đổi quyết định sang **phương án A — chỉ người nạp
      tài liệu** (Quyết định 5), không phải mọi R1 trong phạm vi FR-08 như
      đề xuất ban đầu; **không cần 2 cấp duyệt**. Kèm rủi ro mới "người nạp
      vắng mặt" (mục 6) và thao tác vận hành mới "chuyển quyền sở hữu tài
      liệu" cần BA bổ sung story.
- [x] **Mở xem có dừng đồng hồ 24h không?** Xác nhận **không dừng** — chỉ
      thao tác ghi (sửa hoặc "nhận duyệt") mới chuyển `in_review` và dừng
      đồng hồ.
- [x] **`search-fulltext` chưa có file SA:** giữ nguyên đề xuất hợp đồng sự
      kiện một phía ở QĐ 6, chốt lại khi làm `/sa-spec search-fulltext`.
- [x] **Nhắc việc trước hạn:** xác nhận giai đoạn 1 chỉ nhắc trong ứng dụng
      (badge + danh sách "sắp hết hạn"), không cần email/push.
