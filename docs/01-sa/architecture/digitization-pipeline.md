# Architecture: digitization-pipeline

> File: `docs/01-sa/architecture/digitization-pipeline.md` — Owner: SA
> Status: Approved
> Ghi chú: **Quyết định 10** (bỏ tách nguồn video ở backend, video gốc →
> `q.video`, bổ sung 2026-09-23) người dùng duyệt 2026-09-23 — các quyết định
> khác giữ nguyên trạng thái đã duyệt bên dưới. Mục 1–7 gốc Approved 2026-08-30 trở về trước; Quyết định 8
> (`q.video`, bổ sung 2026-09-14) người dùng đã duyệt 2026-09-14; Quyết định 9
> (hợp đồng hoàn tất run cho `ocr`/`stt`/`sed`) bổ sung 2026-09-22, **sửa lại
> cùng ngày** sau trao đổi trực tiếp với người dùng — cầu tạm đổi thành worker
> ghi trực tiếp `digitization_run` (xem "Cập nhật Quyết định 9" ngay sau §4).

## 1. Bối cảnh / Vấn đề

- **FR-17** (OCR ảnh/scan/PDF), **FR-19** (STT audio/video/ghi âm cuộc họp),
  **FR-20** (mốc thời gian theo đoạn để tra cứu/phát lại). Cả ba do **Hệ thống**
  thực hiện tự động, chạy bất đồng bộ (NFR-02), mô hình cục bộ (NFR-16/NFR-04).
- Vấn đề cần giải: biến một tệp vừa nhập kho chung thành **các đoạn nội dung
  (segment) có văn bản + toạ độ định vị + độ tin cậy**, một cách bền vững trên
  hạ tầng GPU hữu hạn, không chặn người dùng, không mất job, và truy vết được
  model nào sinh ra kết quả nào.
- **In-scope:** kích hoạt job từ sự kiện nhập kho; hàng đợi & điều phối GPU
  (ưu tiên, backpressure, retry/DLQ); tiền xử lý (tách trang PDF, tách audio,
  VAD, kiểm tra chất lượng đầu vào); chạy OCR/STT (+SED tuỳ chọn); phân đoạn +
  gắn mốc thời gian; chuẩn hoá & lưu kết quả; máy trạng thái job + resume theo
  lô; gắn cờ độ tin cậy thấp; hợp đồng bàn giao cho kiểm duyệt.
- **Triển khai theo pha (bổ sung 2026-08-30, theo yêu cầu nghiệp vụ cụ thể
  hoá bước "xử lý dữ liệu sau khi upload"):** tài liệu này vẫn mô tả **toàn bộ**
  kiến trúc digitization-pipeline, nhưng việc implement được chia làm 2 pha để
  giao sớm phần không phụ thuộc GPU:
  - **Pha 1 — `digitization-pipeline-01-source-dispatch`** (BA/DEV riêng):
    lưu thông tin upload (video/audio/ảnh/PDF) thành `digitization_source` và
    **đẩy yêu cầu xử lý vào hàng đợi** cho AI worker — xem **Quyết định 7**,
    **Quyết định 10**. ~~Tách nguồn video (video-only/audio-only) bằng FFmpeg
    ở backend~~ — **bỏ từ 2026-09-23** (Quyết định 10): video gốc được đẩy
    thẳng vào `q.video`, không tách. Pha 1 **không**
    xử lý việc AI worker tiêu thụ hàng đợi, chạy OCR/STT, hay ghi kết quả.
  - **Pha 2** (chưa đặt tên slug, làm sau): AI worker tiêu thụ `q.ocr`/`q.stt`,
    chạy Quyết định 1/3/5, `digitization-service` tiêu thụ `q.result`, ghi
    `content_segment`, phát `digitization.completed` — đúng theo Quyết định
    2–4 dưới đây, không đổi.
- **Out-of-scope:**
  - **FR-18 (kiểm duyệt thủ công, sửa, tự xác nhận 24h, UI hàng chờ)** →
    `digitization-review`. Xem điểm nối ở **Quyết định 4**.
  - Lựa chọn hạ tầng (RabbitMQ/OpenSearch/TEI), mô hình ACL trong chỉ
    mục, cơ chế outbox→index → `ai-search-infra.md` (**dẫn chiếu, không quyết
    định lại**).
  - Lập chỉ mục & truy vấn (FR-22–25) → `search-fulltext` / `search-semantic`.
    Pipeline **không** tự index; chỉ nội dung đã xác nhận mới vào chỉ mục.
  - FR-21 (caption ảnh/video) — tạm hoãn theo kế hoạch; tóm tắt tài liệu —
    chưa là FR.
  - Kho cá nhân **không** được số hoá (`personal-storage-quota.md`).

## 2. Ràng buộc (Constraints)

- **Air-gap tuyệt đối** (NFR-04/NFR-11/NFR-16): trọng số model đóng gói offline
  vào volume nội bộ, `HF_HUB_OFFLINE=1`; cấm mọi lệnh tải model lúc khởi động.
- **Phần cứng cố định:** 1 máy chủ AI, **GPU 96GB** (đã xác nhận 2026-08-16,
  `ai-search-infra.md` mục 2). Không có cụm scale-out ⇒ điều phối theo thời gian
  (ưu tiên/hàng đợi), không theo số node.
- **Bản gốc bất biến** (NFR-09, FR-12): worker mở tệp gốc ở chế độ **chỉ đọc**;
  mọi sản phẩm AI là bản phái sinh lưu tách biệt.
- **PostgreSQL là nguồn sự thật**; RabbitMQ chỉ là phương tiện vận chuyển job.
- Đầu vào giới hạn theo allowlist `data-ingestion.md` + NFR-14: ảnh
  JPEG/PNG/TIFF, PDF/PDF-A, audio WAV/FLAC/MP3, video MP4 (H.265).
- **Phiên âm trung thực:** không lọc/che từ tục tĩu, phương ngữ — đây là chứng
  cứ nghiệp vụ; chỉ tăng cường từ vựng để nhận dạng đúng.
- Tham số vận hành lấy từ `system-config.md` (FR-01): `ai_queue.concurrency`,
  `ai_queue.batch_size`, `ocr_stt.auto_confirm_hours` (24h).
- Chỉ tệp thuộc **kho chung** và đã qua kiểm tra hash/mã độc mới phát sinh job.

## 3. NFR (Non-Functional Requirements)

Số `[ĐX]` là đề xuất của SA, suy ra từ NFR-01/02 (SRS chỉ nêu định tính) và từ
mục 3 của `ai-search-infra.md`; kiểm chứng lại bằng đo tải trên phần cứng thật.

| Loại | Yêu cầu |
|---|---|
| Performance (thông lượng) | Kế thừa `ai-search-infra`: OCR ≥ **1.500 trang/giờ**; STT ≥ **6× thời gian thực** (tổng mọi worker). `[ĐX]` SED (nếu bật) ≥ 50× thời gian thực |
| Performance (độ trễ) | `[ĐX]` Luồng `interactive` (1 tệp do cán bộ nạp): p95 ≤ **5 phút** cho tài liệu ≤ 20 trang hoặc audio ≤ 10 phút; thời gian chờ trong hàng đợi p95 ≤ 60s khi không có backlog. Luồng `bulk`: **không cam kết độ trễ từng tệp**, chỉ cam kết thông lượng; tiến độ lô cập nhật ≤ 10s |
| Performance (không chặn) | NFR-02: mọi tác vụ chạy nền; API nạp dữ liệu trả về ngay sau khi ghi job, không chờ AI |
| Security | Model chạy cục bộ, worker **không có egress** ra ngoài VLAN 108. Worker truy cập SeaweedFS bằng credential **chỉ đọc** trên prefix bản gốc, ghi chỉ vào `derived/`. Kết quả kế thừa nguyên vẹn phạm vi FR-08 của tài liệu nguồn. Log/metric **không** chứa nội dung văn bản đã nhận dạng |
| Scalability | `[ĐX]` Chịu backlog ≥ 100.000 job trong hàng đợi mà không mất job; quy mô đích ~1 triệu tài liệu / ~20 triệu segment; lô ≥ 10.000 tệp phải checkpoint theo từng tệp và resume được |
| Availability | Worker stateless, at-least-once + idempotent theo `job_id`. Worker/GPU chết → job được redeliver, không mất. `[ĐX]` Job "treo" phát hiện trong ≤ 15 phút (lease + heartbeat). Pipeline ngừng hoạt động **không** ảnh hưởng nạp dữ liệu, tra cứu, kiểm duyệt các kết quả đã có |
| Khác | `[ĐX]` Tỷ lệ job rơi vào DLQ < 1%/tuần, DLQ khác rỗng phải sinh cảnh báo (NFR-13). Mọi bản ghi kết quả gắn `model_name` + `model_version` để truy vết/chạy lại (NFR-09) |
| Độ chính xác (nghiệm thu) | **CER ≤ 5% (OCR), WER ≤ 15% (STT)** — ngưỡng C02 xác nhận 2026-08-16 làm mục tiêu nghiệm thu; benchmark giai đoạn 0 (mục 4, QĐ 1) đo lại trên dữ liệu thật, có thể đề xuất điều chỉnh |

## 4. Phương án & Quyết định (ADR)

### Quyết định 1: Engine OCR / STT / SED cục bộ

- **Ngữ cảnh:** cần độ chính xác tiếng Việt (dấu thanh, phương ngữ 3 miền) với
  ràng buộc air-gap + GPU dùng chung; SRS chỉ nói "mô hình OCR/STT chạy cục bộ".
- **Các phương án xem xét:** A) dịch vụ đám mây (Google/Azure) — B) OCR tổng
  quát Tesseract / STT Whisper large-v3 nguyên bản — C) tổ hợp chuyên tiếng Việt.
- **Quyết định:** **C**, dẫn chiếu `danh_gia_lua_chon_cong_nghe.adoc` và
  `Phuong_an_thiet_ke_AI_OCR_STT_SED_TomTat_RAG.md`:
  - **OCR:** PaddleOCR (DB++ detector) cho phát hiện vùng chữ + **VietOCR**
    (CNN+Transformer) cho nhận dạng. Tiền xử lý CPU: deskew, khử nhiễu, phát
    hiện hướng trang.
  - **PDF (kể cả đã có sẵn lớp text):** **luôn chạy OCR đầy đủ** trên mọi
    trang — quyết định của C02 (2026-08-16): lớp text nhúng sẵn trong PDF
    **không** được dùng để bỏ qua OCR, vì cần bbox + confidence nhất quán cho
    mọi tài liệu (nghiệm thu FR-17, định vị chính xác khi tra cứu/FR-18). Nếu
    PDF có lớp text sẵn, trích thêm bằng PDFBox/Tika chỉ để **đối chiếu QA**
    (so khớp với kết quả OCR, không thay thế) — lưu ở `has_native_text_layer`
    + `native_text_match_ratio` trên bản ghi tài liệu, không tạo segment
    riêng.
  - **STT:** **PhoWhisper-large** (ưu tiên) chạy qua `faster-whisper`/CTranslate2;
    dự phòng Whisper large-v3. Kèm VAD (Silero) trước ASR.
  - **SED:** PANNs CNN14 (AudioSet), **feature flag mặc định TẮT** — xem QĐ 6.
  - **Speaker diarization** (pyannote): feature flag, mặc định TẮT.
- **Lý do:** A vi phạm NFR-04/16 (loại ngay). B rẻ hơn nhưng CER/WER tiếng Việt
  kém rõ rệt (Tesseract yếu với dấu thanh + scan mờ; Whisper gốc yếu với phương
  ngữ). C là tổ hợp phổ biến nhất cho tiếng Việt, đều open-weight, đóng gói
  offline được, tổng VRAM thường trú ~8GB — thoải mái trong 96GB.
- **Hệ quả:** phải chạy **benchmark giai đoạn 0** (CER/WER trên **bộ mẫu tổng
  hợp** do SA/DEV tự chuẩn bị tạm thời — chưa có dữ liệu thật của C02, thay
  thế bằng dữ liệu thật khi C02 bàn giao) trước khi chốt; chữ viết tay và cấu
  trúc bảng **không** được cam kết ở giai đoạn 1; mỗi engine là một image
  Docker riêng + bảng `ai_model_registry` (model, version, task, eval_metrics,
  status) để đổi/rollback model. Việc luôn-OCR-toàn-bộ-PDF (kể cả có text
  layer) làm **tăng tải GPU** so với phương án tối ưu ban đầu — xem lại NFR
  thông lượng mục 3 và rủi ro mục 6.

### Quyết định 2: Hàng đợi & điều phối job GPU (ưu tiên / backpressure / retry)

- **Ngữ cảnh:** job dài (phút–chục phút), GPU là tài nguyên khan hiếm dùng chung
  với embedding (rerank đã bỏ 2026-10-08, `search-semantic.md` QĐ 2); phải phục vụ cả tệp đơn (interactive) lẫn lô lớn (bulk),
  và phải "đưa lại hàng đợi" khi lỗi (FR-17/19 — Ngoại lệ).
- **Các phương án xem xét:** A) dùng chính RabbitMQ làm nơi lưu trạng thái job —
  B) **bảng `digitization_job` trong PostgreSQL làm máy trạng thái + RabbitMQ chỉ
  vận chuyển** — C) orchestrator ngoài (Airflow/Temporal).
- **Quyết định:** **B**, cụ thể:
  - Hàng đợi riêng theo tác vụ trên exchange `ai.jobs` (`ai-search-infra` mục 8):
    `q.ocr`, `q.stt`, `q.sed`, mỗi queue có `.retry` (TTL backoff) và `.dlq`.
  - **Ưu tiên:** dùng priority của RabbitMQ, 2 mức — `interactive` (nạp lẻ qua
    FR-11) > `bulk` (nạp thư mục/USB). Chống đói (starvation): dành hạn ngạch
    tối thiểu `[ĐX]` 20% slot worker cho `bulk`.
  - **Backpressure:** `prefetch=1` cho worker GPU, số worker = `ai_queue.
    concurrency`; khi độ sâu hàng đợi hoặc VRAM vượt ngưỡng, producer **không**
    dừng phát job (job nằm bền vững trong queue) nhưng lô mới bị điều tiết
    (rate-limit khi enqueue) để hàng đợi không phình vô hạn.
  - **Retry:** tối đa **3 lần**, backoff 1m/5m/30m qua `.retry` queue, sau đó
    vào `.dlq` + cảnh báo (NFR-13). Phân biệt lỗi **tạm thời** (OOM GPU, timeout,
    I/O) → retry, và lỗi **vĩnh viễn** (tệp hỏng, codec không hỗ trợ) → chuyển
    thẳng `failed_permanent`, không retry.
  - **Máy trạng thái job:** `queued → running → succeeded | failed_retryable |
    failed_permanent | cancelled`; job có **lease + heartbeat** (worker cập nhật
    mỗi 30s), quá hạn lease ⇒ coi như treo, requeue.
  - **Lô (batch):** bảng `digitization_batch` với tiến độ tổng/xong/lỗi/đang chờ;
    checkpoint ở mức từng tệp ⇒ tạm dừng/tiếp tục không phải chạy lại từ đầu.
  - **Idempotency:** khoá `(document_id, task, model_version)`; job trùng lặp
    (redelivery) ghi đè cùng `run_id`, không nhân bản kết quả.
- **Lý do:** A không quan sát/truy vấn được trạng thái (UI cần bảng tiến độ, cán
  bộ cần biết tệp của mình đang ở đâu) và không resume được lô. C thêm một hệ
  stateful lớn, thừa cho một máy chủ và mâu thuẫn với QĐ 3 của `ai-search-infra`.
- **Hệ quả:** trạng thái tồn tại ở 2 nơi (DB + queue) ⇒ cần job đối soát định kỳ
  (job `running` quá lease, job `queued` không có message tương ứng) và công cụ
  vận hành replay DLQ.

### Quyết định 3: Phân đoạn, mốc thời gian và mô hình lưu kết quả

- **Ngữ cảnh:** FR-20 yêu cầu "mỗi đoạn nội dung gắn mốc thời gian, nhảy đúng
  vị trí"; FR-25 cần trang/dòng cho văn bản; `ai-search-infra` QĐ 6 đã chốt đơn
  vị lập chỉ mục là **segment**.
- **Các phương án xem xét:** A) lưu 1 blob JSON kết quả/tài liệu — B) **bảng
  segment quan hệ chuẩn hoá + blob JSON thô lưu kèm** — C) chỉ lưu trong chỉ mục
  tìm kiếm.
- **Quyết định:** **B**.
  - **Một khái niệm segment duy nhất** cho cả OCR và STT (khớp `segment` của
    `ai-search-infra`), phân biệt bằng `content_source` (`ocr | stt | sed`) —
    **không** có giá trị `native_text`: PDF luôn tạo segment qua OCR (QĐ 1);
    lớp text nhúng sẵn (nếu có) chỉ là siêu dữ liệu QA trên `document`, không
    sinh segment:
    - OCR: `page_no`, `bbox_polygon` (**tỉ lệ 0–1 trên trang**, `[[x,y], ...]`
      — không phải pixel tuyệt đối, để không phụ thuộc kích thước ảnh/DPI
      scan; chốt 2026-09-07, xem `search-fulltext-03-dinh-vi-ket-qua.md`
      mục 2), `reading_order`, `line_from`/`line_to`.
    - STT/SED: `time_start_ms`, `time_end_ms` (**số nguyên mili-giây**, tương đối
      từ đầu media), `speaker_label` (nullable).
  - **Quy tắc phân đoạn STT (FR-20):** lấy word-level timestamp từ model → gộp
    thành câu theo dấu câu + khoảng lặng VAD; ràng buộc độ dài đoạn `[ĐX]`
    **3–30 giây** (cắt cưỡng bức nếu vượt) để vừa dễ phát lại vừa hợp lý cho
    chunking embedding.
  - **Quy tắc phân đoạn OCR:** đoạn = block văn bản do detector trả về, sắp theo
    reading order; **không** dựng lại cấu trúc bảng ở giai đoạn 1.
  - **Ràng buộc bất biến (invariant):** `0 ≤ time_start_ms < time_end_ms ≤
    duration_ms`, với `duration_ms` đo bằng `ffprobe` và lưu ở `document`. Mọi
    ghi (kể cả bản sửa của FR-18) đều bị kiểm tra ràng buộc này ở tầng DB
    (CHECK/trigger) — trực tiếp phục vụ yêu cầu "chặn mốc thời gian vượt độ dài
    tệp gốc" nêu trong kế hoạch.
  - **Nhiều lần chạy:** bảng `digitization_run` (`run_id`, `task`, `model_name`,
    `model_version`, `started_at`, `metrics`); chỉ **một** run `active` cho mỗi
    `(document_id, task)`. Chạy lại khi đổi model tạo run mới, không xoá run cũ.
  - **JSON thô đầy đủ** (theo schema mục 3 của phương án AI) lưu SeaweedFS prefix
    `derived/<document_id>/<run_id>.json` — tái lập được, không cần backup đầy đủ.
    Audio chuẩn hoá 16kHz mono cũng nằm ở `derived/`.
- **Lý do:** A không truy vấn/sửa từng đoạn được (FR-18 sửa theo đoạn) và không
  ràng buộc được tính hợp lệ của mốc thời gian; C vi phạm nguyên tắc
  "PostgreSQL là nguồn sự thật, chỉ mục dựng lại được".
- **Hệ quả:** số bản ghi segment rất lớn (~20 triệu) ⇒ cần partition theo
  `document_id`/thời gian và index hợp lý; DEV chốt DDL chi tiết ở tech spec.

### Quyết định 4: Hợp đồng bàn giao cho `digitization-review` (FR-18)

- **Ngữ cảnh:** pipeline dừng ở "kết quả nháp"; review là slug riêng. Cần một
  ranh giới rõ để hai bên phát triển song song.
- **Quyết định — pipeline cam kết đúng những điều sau, review chỉ tiêu thụ:**
  1. Khi run hoàn tất: `content_segment` **không mang field trạng thái duyệt**
     (`review_status`/`confirm_source`) — trạng thái duyệt chỉ tồn tại **ở cấp
     run**, trong bảng `digitization_review` do `digitization-review` sở hữu
     (tạo khi consume sự kiện ở mục 2, khớp Quyết định 2 của
     `digitization-review.md`: đơn vị xác nhận là một run, không phải từng
     segment). Pipeline tính `review_deadline_at = completed_at +
     ocr_stt.auto_confirm_hours` (`system-config`) và đưa vào payload sự kiện
     mục 2 để review lưu, không ghi field này lên segment. Áp dụng **như nhau
     cho OCR và STT** — C02 xác nhận (2026-08-16) STT dùng chung tham số
     `ocr_stt.auto_confirm_hours` với OCR, không có ngưỡng riêng.
  2. Phát sự kiện **`digitization.completed`** qua outbox (schema sự kiện dùng
     chung ở `ai-search-infra` mục 8), payload gồm: `document_id`, `run_id`,
     `task`, `segment_count`, `low_confidence_count`, `quality_flag`,
     `auto_confirm_blocked` (bool), `review_deadline_at`.
  3. **Pipeline không bao giờ ghi đè bản sửa của cán bộ:** bản sửa nằm ở bảng
     riêng (`segment_revision`), segment gốc do AI sinh giữ nguyên để so sánh
     (phục vụ vòng lặp fine-tune sau này).
  4. **Pipeline không phát `index.upsert`.** Chỉ `review.confirmed` (thủ công
     hoặc tự động, do `digitization-review` phát) mới kích hoạt lập chỉ mục.
  5. Chạy lại (re-run) một tài liệu **đã xác nhận** phải tạo run mới ở trạng
     thái `pending` và **không** tự thay thế bản đã xác nhận — quyết định thay
     thế thuộc `digitization-review`.
- **Hệ quả:** kết quả OCR/STT không tìm kiếm được cho tới khi được xác nhận (tối
  đa 24h) — đây là hệ quả của FR-18, cần nêu rõ trong UI; `digitization-review`
  phải xử lý trường hợp một tài liệu có nhiều run.

### Quyết định 5: Gắn cờ chất lượng và ngưỡng độ tin cậy (2 lớp)

- **Ngữ cảnh:** FR-17/19 yêu cầu "chất lượng kém → đánh dấu độ tin cậy thấp".
- **Quyết định:** hai lớp gắn cờ, ngưỡng lưu ở cấu hình (không hard-code):
  - **Lớp đầu vào** (rẻ, chạy trước GPU): độ nét ảnh (phương sai Laplacian), độ
    phân giải, SNR audio ước lượng, tỷ lệ khoảng lặng → `input_quality_flag ∈
    {ok, poor_scan, low_audio_snr}`. Tệp cờ xấu **vẫn xử lý** nhưng hạ ưu tiên.
  - **Lớp đầu ra:** `confidence` từng segment + tỷ lệ segment thấp trên toàn
    tài liệu → `output_quality_flag`. Ngưỡng khởi điểm `[ĐX]`: OCR block < 0.60,
    STT segment < 0.55, `no_speech_prob > 0.8` ⇒ nghi ảo giác (giữ lại nhưng gắn
    cờ, không tự xoá); > 30% segment thấp ⇒ tài liệu `poor`.
  - Tài liệu `poor` được đặt cờ **`auto_confirm_blocked = true`** trong hợp đồng
    QĐ 4 — pipeline chỉ *đặt cờ*, hành vi chặn tự-xác-nhận-24h do
    `digitization-review` thực thi. **C02 xác nhận (2026-08-16): tài liệu
    `poor` bị chặn tự-xác-nhận**, bắt buộc cán bộ duyệt thủ công.
- **Hệ quả:** ngưỡng là tham số vận hành ⇒ đưa vào `system-config` nhóm
  `ocr_stt.*`; đổi ngưỡng **không** làm chạy lại model, chỉ tính lại cờ.

### Quyết định 6: Phạm vi SED và diarization ở giai đoạn 1

- **Ngữ cảnh:** SED **không** có FR trong SRS; diarization không nằm trong tiêu
  chí chấp nhận FR-19. Cả hai đều được đề xuất trong phương án thiết kế AI.
- **Quyết định:** hiện thực **hạ tầng** cho cả hai (queue `q.sed`, bảng
  `sound_event`, trường `speaker_label`) nhưng **feature flag mặc định TẮT**.
  **C02 xác nhận (2026-08-16): cả SED và diarization đều để giai đoạn 2**,
  không nằm trong cam kết 20/12/2026. Nếu bật ở giai đoạn sau: SED chạy
  zero-shot với ngưỡng confidence cao, kết quả gắn nhãn "gợi ý — cần cán bộ
  xác minh", không tính vào cam kết độ chính xác.
- **Lý do:** giữ cửa mở mà không kéo rủi ro/khối lượng vào cam kết 20/12/2026;
  chi phí thiết kế trước rẻ hơn nhiều so với sửa schema sau.
- **Hệ quả:** có mã/nghiệp vụ chưa dùng tới; cần đảm bảo tính năng tắt không
  sinh job rác.

### Quyết định 7: Tách nguồn (source separation) chạy ở backend; hợp đồng dispatch chi tiết (bổ sung 2026-08-30)

> ⚠️ **Đã bị thay thế một phần bởi Quyết định 10 (2026-09-23):** nhánh
> **Video** bên dưới (`source_separation_job`, FFmpeg, 2 dòng
> `digitization_source` `video`/`audio`) **không còn áp dụng** cho tài liệu
> nhập kho từ 2026-09-23. Nhánh audio/ảnh/PDF, hợp đồng message, tên queue
> từ env, idempotency/retry của bước dispatch **giữ nguyên**.

- **Ngữ cảnh:** yêu cầu nghiệp vụ 2026-08-30 chốt rõ: tách audio khỏi video
  bằng FFmpeg là một **job của backend** (Spring Boot, có bảng trạng thái
  riêng), **không phải** bước tiền xử lý bên trong `stt-worker` Python như
  phác thảo ban đầu ở Quyết định 1. Đây là phạm vi của **Pha 1** (mục 1) —
  tách nguồn + lưu DB + đẩy hàng đợi, chưa xử lý nhận kết quả.
- **Các phương án xem xét:** A) giữ nguyên: `stt-worker` tự chạy FFmpeg tách
  audio từ video khi nhận job — B) **backend chạy FFmpeg tách nguồn ngay sau
  khi nhập kho, trước khi có bất kỳ job GPU nào** — C) một service Python
  riêng chỉ làm tách nguồn (thêm một thành phần hạ tầng mới).
- **Quyết định: B.**
  - **Video:** khi tệp video qua nhập kho (`document.ingested`, chỉ kho
    chung), backend tạo 1 dòng `source_separation_job` (trạng thái
    `queued → running → succeeded | failed`, chạy trong tiến trình backend —
    không cần lease/heartbeat kiểu GPU của Quyết định 2, vì đây là tác vụ
    CPU/I/O ngắn), gọi `ffmpeg` (command-line) tách thành 2 tệp phái sinh, ghi
    SeaweedFS theo đúng prefix `derived/` đã chốt ở Quyết định 3:
    `derived/<document_id>/video.<ext>` và `derived/<document_id>/audio.<ext>`.
    Sau đó ghi **2 dòng** `digitization_source` (`component_type = video |
    audio`). **Chỉ dòng `audio`** được đẩy vào hàng đợi (`q.stt`); dòng
    `video` được **lưu lại, không tự động đẩy vào bất kỳ queue nào** ở Pha 1 —
    dự phòng cho FR-21 (caption/video, đang hoãn — mục 1 out-of-scope gốc).
  - **Audio/ảnh/PDF:** không cần tách — ghi **1 dòng** `digitization_source`
    với `component_type = original`, **`id = document_id`** (đúng yêu cầu
    nghiệp vụ "sourceId chính là fileId" khi không phải video, không sinh id
    mới), rồi đẩy thẳng vào hàng đợi tương ứng. Không qua
    `source_separation_job` vì không có xử lý biến đổi nào.
  - **Định tuyến hàng đợi theo `processFileType`:** `audio/*` → `q.stt`;
    `image/*`, `application/pdf` → `q.ocr`. Không dùng `q.sed` ở Pha 1 (SED
    vẫn tắt theo Quyết định 6).
  - **Hợp đồng message** — cụ thể hoá trường dữ liệu cho sự kiện
    `ocr.requested`/`stt.requested` mà `ai-search-infra.md` mục 8 đã liệt kê ở
    mức khái niệm (tài liệu đó chỉ định nghĩa loại sự kiện, không chốt
    trường):
    ```json
    {
      "fileId": "uuid",           // id tài liệu gốc (document.id)
      "sourceId": "uuid",         // id thành phần; = fileId nếu component_type=original
      "filePath": "string",       // đường dẫn SeaweedFS để worker stream (derived/... hoặc path gốc)
      "originalFileType": "string", // mimetype tệp gốc (document.mime_type)
      "processFileType": "string"   // mimetype tệp thực sự gửi xử lý (audio/*, image/*, application/pdf)
    }
    ```
  - **Tên queue không hard-code**: đọc từ biến môi trường của backend
    (`QUEUE_OCR_NAME`, `QUEUE_STT_NAME` — đặt cạnh các biến kết nối RabbitMQ
    đã có sẵn ở hạ tầng `stg/.env`/`docker-compose.yml`, xem
    Quyết định 2). Vẫn theo đúng quy ước `<queue>.retry`/`<queue>.dlq` của
    Quyết định 2 — Pha 1 chỉ publish, không định nghĩa lại quy ước đó.
  - **Idempotency & retry của bước dispatch** (khác idempotency
    `(document_id, task, model_version)` của Quyết định 2, vốn áp cho *kết
    quả* AI, không áp cho bước *gửi yêu cầu*): mỗi `digitization_source` có
    `dispatch_status` (`pending → published | failed`). Nếu backend chết giữa
    lúc ghi DB và publish, một job đối soát (cùng tinh thần Quyết định 2) quét
    các dòng `pending` quá hạn để publish lại — không sinh dòng
    `digitization_source` trùng, chỉ publish lại message.
- **Lý do:** tách nguồn là tác vụ CPU/I/O-bound (chạy `ffmpeg` CLI), không cần
  GPU — đặt ở backend (Java, đã có sẵn hạ tầng job/status) đơn giản hơn kéo
  Python worker vào chỉ để chạy một lệnh hệ thống; tách rời khỏi `stt-worker`
  giúp worker chỉ nhận audio đã chuẩn hoá sẵn, không phải tự xử lý container
  video.
- **Hệ quả (trade-off):**
  - **Quyết định 1** (tech stack) cần hiểu lại: `stt-worker` không còn cần tự
    chạy FFmpeg để **tách** audio từ video (đầu vào đã là audio riêng); vẫn có
    thể giữ FFmpeg cho các bước chuẩn hoá khác (sample rate, mono hoá) nếu
    cần, việc đó thuộc Pha 2.
  - Backend cần cài đặt binary `ffmpeg` trong môi trường deploy (dẫn chiếu
    `kien_truc_he_thong.adoc`, không quyết định lại theo Quyết định 8 của
    `ai-search-infra.md`).
  - Thêm 2 bảng mới (`digitization_source`, `source_separation_job`) — DDL chi
    tiết do DEV chốt ở tech spec của `digitization-pipeline-01-source-dispatch`;
    **không** dùng chung schema với `digitization_job`/`digitization_batch`
    (Quyết định 2) vì hai bảng đó phục vụ vòng đời job GPU (Pha 2), còn đây là
    vòng đời chuẩn bị dữ liệu đầu vào (Pha 1) — nhập nhằng hai khái niệm sẽ
    gây khó khi Pha 2 cần thêm cột lease/heartbeat mà Pha 1 không cần.
  - Backend cần thêm dependency RabbitMQ client (`spring-boot-starter-amqp`)
    — hạ tầng RabbitMQ đã có sẵn trong `docker-compose.yml` (dev) và
    `stg/docker-compose.yml` (staging), chỉ chưa wiring vào `backend/pom.xml`.

### Quyết định 8: Xử lý video-only qua `q.video`, worker tự sở hữu vòng đời kết quả (bổ sung 2026-09-14)

> ⚠️ **Cập nhật 2026-09-24 (người dùng yêu cầu trực tiếp, DEV sửa nhanh —
> `docs/03-dev/specs/digitization-pipeline-05-video-review.md`):** ý "worker
> tự sở hữu vòng đời kết quả / không qua FR-18" **không còn đúng**. Run
> `video` nay đi cùng vòng đời `ocr`/`stt` (Quyết định 9: `q.result`, hoặc cầu
> tạm ghi thẳng DB), vào hàng chờ Số hoá, tự xác nhận theo
> `ocr_stt.auto_confirm_hours`. Trigger `trg_sync_video_status` đã gỡ (V32).

> ⚠️ **Cập nhật bởi Quyết định 10 (2026-09-23):** ý "Dispatch" bên dưới
> (`SourceSeparationJobRunner` publish cả `audio` → `q.stt` lẫn `video` →
> `q.video`) **không còn đúng**. `q.video` nay nhận **tệp video gốc**
> (`component_type='original'`, **có cả âm thanh**), không còn nhận thành
> phần video-only đã tách; không còn message `q.stt` nào cho video. Các ý còn
> lại (task `video`, worker tự ghi `digitization_run`/kết quả, không qua
> `q.result`/review) **giữ nguyên**.

- **Ngữ cảnh:** nghiệp vụ 2026-09-14 xác nhận cần chạy AI trên **phần hình
  ảnh (không âm thanh)** của video — đúng phạm vi từng đánh dấu FR-21
  (caption/video) ở Quyết định 7, lúc đó tạm hoãn. Nay được kích hoạt: dòng
  `digitization_source(component_type='video')` (Quyết định 7) không còn "lưu
  lại, không tự động đẩy vào bất kỳ queue nào" nữa.
- **Các phương án xem xét:** A) giữ nguyên Quyết định 7, video vẫn
  out-of-scope — B) dispatch video vào `q.video` mới, nhưng kết quả đi đúng
  khuôn Quyết định 2–4 hiện có cho OCR/STT (`q.result` → `content_segment` →
  hợp đồng bàn giao review FR-18) — C) dispatch vào `q.video` mới, nhưng
  **worker tự sở hữu toàn bộ vòng đời kết quả** (ghi OpenSearch/Elasticsearch/
  Postgres của riêng nó — Qdrant đã gỡ khỏi hạ tầng 2026-10-10), backend chỉ theo dõi trạng thái job ở mức tối
  thiểu.
- **Quyết định: C** (xác nhận trực tiếp qua trao đổi nghiệp vụ 2026-09-14).
  - **Dispatch:** khi video có audio, `SourceSeparationJobRunner` publish **cả
    hai** thành phần từ cùng một `source_separation_job` — `audio` → `q.stt`
    (như cũ, Quyết định 7), `video` → `q.video` (mới). Dòng
    `digitization_source(component_type='video')` chuyển `dispatchable=true`
    — đảo ngược quy ước cũ của Quyết định 7.
  - **Định tuyến:** `processFileType` bắt đầu bằng `video/*` → task mới
    `video`, queue `q.video` (đọc tên từ env, cùng quy ước `QUEUE_OCR_NAME`/
    `QUEUE_STT_NAME` ở Quyết định 7 — ví dụ `QUEUE_VIDEO_NAME`, mặc định
    `q.video`), vẫn theo đúng khuôn `<queue>.retry`/`<queue>.dlq` của Quyết
    định 2.
  - **`digitization_run` vẫn được backend tạo trước khi publish** (để message
    mang `runId` ổn định, đúng khuôn Quyết định 2/§4.1 của
    `digitization-pipeline-02-ocr-stt-processing.md`), task `video` — nhưng
    đây là **task duy nhất không đi qua `q.result`**: worker tự cập nhật
    `digitization_run.processing_status` (`running` → `succeeded` /
    `failed_retryable` / `failed_permanent`) **trực tiếp vào PostgreSQL** bằng
    credential riêng, không qua `DigitizationResultConsumer`/RabbitMQ.
  - **Worker tự ghi dữ liệu suy luận** (embedding/segment/mô tả video...) vào
    kho dữ liệu riêng của nó (OpenSearch/Elasticsearch, hoặc bảng Postgres mới
    do worker sở hữu; **không** còn Qdrant — gỡ khỏi hạ tầng 2026-10-10) — **không** dùng mô hình `content_segment` thống nhất
    của Quyết định 3 (mô hình đó giả định luồng ghi đi qua backend).
  - **Không đi qua hợp đồng bàn giao review ở Quyết định 4**: task `video`
    không phát `digitization.completed`, không tạo `digitization_review` —
    nằm ngoài phạm vi FR-18 cho tới khi có yêu cầu nghiệp vụ khác xác nhận
    ngược lại.
  - **Client (FE) đọc trạng thái bằng poll/refresh** qua API đọc
    `digitization_run` hiện có — không có push realtime cho task này.
- **Lý do:** đơn giản hoá nhanh nhất cho MVP; kết quả suy luận video (dạng
  dữ liệu, embedding, nơi lưu) chưa đủ rõ để mở rộng schema
  `content_segment`/review dùng chung cho một loại tác vụ khác hẳn OCR/STT.
- **Hệ quả (trade-off):**
  - **Phá vỡ ranh giới "PostgreSQL là nguồn sự thật do backend ghi"** vốn áp
    dụng cho mọi task khác (constraint mục 2, NFR Security mục 3 — worker
    trước nay chỉ có quyền đọc SeaweedFS + không hề có quyền ghi Postgres).
    Task `video` là **ngoại lệ duy nhất**: cần credential Postgres **riêng,
    phạm vi hẹp** cho worker (chỉ `UPDATE` cột trạng thái của
    `digitization_run`, hoặc một bảng trạng thái riêng — **không** cấp quyền
    ghi `content_segment`/`document`/các bảng khác). Đây là rủi ro bảo mật
    cần review riêng trước khi implement — xem mục 6.
  - **Không có heartbeat/lease/retry-DLQ chuẩn ở phần "báo kết quả"** như
    Quyết định 2 (phần "nhận job" qua `q.video`/`.retry`/`.dlq` vẫn có, nhưng
    không có `q.result` để backend biết job đã xong). Nếu worker chết giữa
    chừng sau khi consume nhưng trước khi ghi DB, job có thể kẹt ở
    `running` — `DigitizationRunLeaseReconciliationJob` chỉ phát hiện được
    nếu worker **vẫn tuân thủ cập nhật `lease_expires_at`** đúng chuẩn cột đó
    dù ghi trực tiếp thay vì qua `q.result`; DEV cần xác nhận điều này ở tech
    spec, nếu không sẽ mất khả năng đối soát job treo cho riêng task này.
  - `digitization_run.task` CHECK constraint (V25, Quyết định 2/3) cần thêm
    giá trị `'video'` — migration mới.
  - `digitization_source(component_type='video')` không còn bất biến
    "luôn `dispatchable=false`" như comment gốc ở V18 — cần cập nhật lại.

### Quyết định 9: Hợp đồng hoàn tất run cho `ocr`/`stt`/`sed` — worker phải báo qua `q.result`, không ghi thẳng `content_segment` (bổ sung 2026-09-22)

- **Ngữ cảnh:** người dùng dự án xác nhận trực tiếp (2026-09-22) rằng worker AI
  đang chạy thật trên **A100-Server** cho các task `ocr`/`stt`/`sed` **chỉ
  INSERT thẳng vào bảng `content_segment`**: không publish message lên
  `q.result`, không gọi API backend nào. Đây **không** phải thiết kế đã
  Approved — Quyết định 2–4 quy định worker báo kết quả qua `q.result`, backend
  mới là nơi ghi `content_segment` và chuyển trạng thái run. Quyết định 8 từng
  tự nêu trong rủi ro của chính nó rằng việc worker ghi thẳng Postgres là
  "*lần đầu tiên một worker AI không đi qua backend để ghi trạng thái, phá vỡ
  ranh giới 'PostgreSQL là nguồn sự thật do backend ghi' áp dụng cho mọi task
  khác*" và coi đó là **ngoại lệ chỉ dành cho `video`**. Thực tế cho thấy
  `ocr`/`stt`/`sed` cũng đang đi theo đúng kiểu vi phạm đó — nhưng **âm thầm**,
  không có ADR, không có role DB phạm vi hẹp, không có trigger đồng bộ.
- **Hệ quả kỹ thuật đã verify bằng đọc code, không phải suy đoán:**
  - `DigitizationRunLifecycleServiceImpl.writeSegments()` (điểm ghi
    `content_segment` duy nhất phía Java) **chỉ** được gọi từ
    `completeRunSuccess()`; `completeRunSuccess()` **chỉ** được gọi từ
    `DigitizationResultConsumer.onResult()` (consumer của `q.result`). Grep
    toàn repo xác nhận không có đường ghi `content_segment` nào khác.
  - ⇒ Worker ghi thẳng SQL (bỏ `q.result`) khiến
    `digitization_run.processing_status` **kẹt vĩnh viễn ở `queued`/`running`**,
    không bao giờ thành `succeeded`/`failed_*`, dù `content_segment` đã có dữ
    liệu thật; `digitization_run.segment_count` luôn `0` (chỉ `writeSegments()`
    cập nhật cột này); `output_quality_flag`, `low_confidence_count`,
    `completed_at`, `model_name`/`model_version`/`metrics` (NFR-09) đều **không
    được tính**.
  - ⇒ Kéo theo: `digitization.completed` **không bao giờ được phát** ⇒ không
    tạo `digitization_review` ⇒ **toàn bộ hợp đồng FR-18 (Quyết định 4) đứt**:
    không tự-xác-nhận-24h, không `review.confirmed`, **không bao giờ index**
    (Quyết định 4 điểm 4) ⇒ FR-22–25 không bao giờ thấy nội dung đã số hoá.
  - ⇒ UI: cột "Số đoạn" ở hàng chờ kiểm duyệt (`/digitization`) luôn `0`; badge
    trạng thái mới (`digitization-review-06-queue-pending-status.md`, dựa trên
    `processing_status`) **luôn hiện "Đang xử lý"**, không bao giờ chuyển "Chờ
    duyệt"/"Lỗi xử lý" — sai lệch thông tin, tệ hơn hành vi trước khi có story
    đó.
  - Xác nhận trên DB stg (`aismartstore-stg-postgres-1`): **0 dòng**
    `digitization_run` có `processing_status IN ('succeeded',
    'failed_retryable','failed_permanent')` dù có run `pending` review từ lâu —
    khớp chính xác giả thuyết trên.
  - **Phát hiện bảo mật kèm theo:** role `digitization_video_worker` (V31 §2.4)
    **không** được cấp bất kỳ quyền nào trên `content_segment`. Việc worker
    `ocr`/`stt`/`sed` INSERT được vào bảng đó nghĩa là nó đang dùng **một
    credential rộng hơn** (nhiều khả năng chính role ứng dụng của backend) —
    vi phạm NFR Security mục 3 nặng hơn cả ngoại lệ có kiểm soát của Quyết
    định 8.
- **Các phương án xem xét:**
  - **A. Sửa worker để publish `q.result`** đúng thiết kế đã Approved cho
    `ocr`/`stt`/`sed` (RabbitMQ → `DigitizationResultConsumer` →
    `completeRunSuccess`), thu hồi mọi quyền ghi Postgres của worker.
    - *Ưu:* khôi phục nguyên vẹn ranh giới "Postgres là nguồn sự thật do
      backend ghi" (ràng buộc mục 2) cho mọi task trừ `video`; **không cần sửa
      một dòng nào trong `backend/`** (code nhận kết quả đã tồn tại và đã có
      test); tự động khôi phục toàn bộ chuỗi Quyết định 4 (quality flag →
      `digitization.completed` → review → index) và NFR-09 (model_name/version)
      mà không phải viết lại logic ở tầng khác; giữ retry/DLQ/idempotency của
      Quyết định 2.
    - *Nhược:* công việc nằm **ngoài repo `backend/` này** — thuộc code worker
      trên A100-Server, do team/repo khác sở hữu ⇒ phụ thuộc lịch và quyền
      truy cập của bên đó; cần một đợt backfill cho các run đang kẹt.
    - *NFR liên quan:* Security (thu hồi quyền ghi DB của worker),
      Availability (lease/heartbeat + DLQ vẫn dùng được), NFR-09 (truy vết
      model), NFR-13 (DLQ khác rỗng ⇒ cảnh báo).
    - *Rủi ro:* nếu team worker không sửa kịp, hàng chờ kiểm duyệt tiếp tục
      hiển thị sai trong suốt thời gian chờ.
  - **B. Mở rộng ngoại lệ Quyết định 8 sang `ocr`/`stt`/`sed`** — hợp thức hoá
    việc worker ghi thẳng `content_segment`, thêm DB trigger đồng bộ ngược
    (`content_segment` INSERT → `digitization_run.processing_status`/
    `segment_count`), kèm role Postgres phạm vi hẹp kiểu
    `digitization_video_worker` (chỉ INSERT trên `content_segment`).
    - *Ưu:* không cần đụng vào code worker ⇒ sửa được ngay trong repo backend,
      không phụ thuộc team khác.
    - *Nhược (nặng):*
      1. Trigger **không biết khi nào run kết thúc**. `trg_sync_video_status`
         đơn giản vì nó phản ứng với **một sự kiện kết thúc rõ ràng** (UPDATE
         `processing_status`); ở đây chuỗi INSERT segment không có "segment
         cuối cùng" — trigger buộc phải đoán bằng heuristic (debounce theo thời
         gian/idle timeout), tức là **đặt một máy trạng thái vào trong plpgsql**
         — sai chỗ về kiến trúc và cực khó test/quan sát.
      2. `completeRunSuccess()` làm nhiều hơn set status: tính
         `output_quality_flag`/`low_confidence_count` theo ngưỡng
         `system-config` (Quyết định 5 — ngưỡng **thay đổi được lúc chạy**,
         trigger SQL sẽ phải đọc bảng config trong plpgsql), set `completed_at`,
         ghi `model_name`/`model_version`/`metrics` (NFR-09) — mà những giá trị
         này **không có trong bản ghi `content_segment`**, trigger không thể
         tái tạo ⇒ buộc phải cắt bớt ⇒ **vi phạm NFR-09** (mất truy vết model)
         và làm sai Quyết định 5.
      3. Phát `digitization.completed` qua outbox (Quyết định 4 điểm 2) từ
         trigger đồng nghĩa với việc sinh sự kiện nghiệp vụ từ plpgsql —
         nhân đôi logic nghiệp vụ ở hai ngôn ngữ, mất transaction boundary
         của Java, cực dễ lệch khi Quyết định 4/5 đổi.
      4. Nhân bản ngoại lệ bảo mật ra **3 task nữa**, và lần này là quyền ghi
         vào chính bảng nội dung nghiệp vụ (`content_segment`) chứ không phải
         vài cột trạng thái — rủi ro cao hơn hẳn Quyết định 8.
    - *NFR liên quan:* vi phạm NFR-09; mâu thuẫn ràng buộc "PostgreSQL là
      nguồn sự thật **do backend ghi**" mục 2; Security xuống cấp rõ rệt.
  - **C. (SA đề xuất thêm) A là đích + một "cầu tạm" phía backend, có thời
    hạn:** giữ nguyên A làm trạng thái đích, đồng thời backend tự bổ sung một
    **job đối soát tạm thời** (`DigitizationOrphanSegmentReconciliationJob`,
    cùng tinh thần job đối soát đã có ở Quyết định 2/7): quét các
    `digitization_run` task `ocr`/`stt`/`sed` đang `running`/`queued` nhưng đã
    có `content_segment` và không phát sinh segment mới quá `[ĐX]` 10 phút, rồi
    gọi **đúng code Java hiện có** để hoàn tất run (đếm `segment_count`, tính
    cờ chất lượng theo Quyết định 5 trên segment đã có, set `completed_at`,
    phát `digitization.completed`), đánh dấu run là hoàn tất bằng **đường đối
    soát** (`completion_source = 'reconciled'`) để phân biệt với đường
    `q.result` chuẩn.
    - *Ưu:* khôi phục UI/hàng chờ/review/index **ngay**, không chờ team worker;
      **mọi thao tác ghi vẫn nằm trong backend Java** ⇒ **không phá ranh giới
      mục 2**, không cần cấp thêm quyền DB cho worker, không nhồi logic vào
      plpgsql; tái dùng nguyên logic Quyết định 4/5 thay vì viết lại.
    - *Nhược:* `model_name`/`model_version`/`metrics` **vẫn không có** (worker
      không gửi) ⇒ các run đi đường đối soát vi phạm NFR-09 — phải ghi nhận
      minh bạch bằng `completion_source='reconciled'` và coi là **nợ kỹ thuật
      có thời hạn**; heuristic "idle 10 phút" có thể chốt sớm một run đang
      chạy dở (giảm thiểu: ngưỡng idle > thời gian xử lý một segment ở mức
      p99, và cho phép run được hoàn tất lại/ghi đè khi `q.result` đến sau).
    - *NFR liên quan:* Availability (job treo được gỡ), Security (không mở
      thêm quyền), NFR-09 (**suy giảm có kiểm soát, có đánh dấu**).

- **Quyết định: C — A là trạng thái đích bắt buộc; cầu tạm của C chỉ được bật
  có điều kiện và có ngày hết hạn. B bị loại.**
  1. **Hợp đồng chuẩn không đổi:** `ocr`/`stt`/`sed` **phải** báo kết quả qua
     `q.result`; `content_segment` **chỉ** được ghi bởi backend
     (`writeSegments()`). Ngoại lệ "worker ghi thẳng Postgres" của Quyết định 8
     **vẫn chỉ áp dụng cho task `video`**, **không** mở rộng — mọi task ghi
     thẳng `content_segment` là **lỗi cần sửa**, không phải thiết kế.
  2. **Thu hồi quyền:** credential mà worker `ocr`/`stt`/`sed` đang dùng để
     INSERT `content_segment` phải được rà soát và thu hồi ngay khi A hoàn tất
     (xem rủi ro mục 6 và câu hỏi mở mục 7). Worker các task này **không** được
     cấp role DB kiểu `digitization_video_worker`.
  3. **Cầu tạm (C) chỉ bật nếu** câu hỏi mở về quyền sở hữu/timeline code
     worker (mục 7) cho thấy A **không** giao được trong `[ĐX]` 2 tuần. Cầu
     tạm phải: (a) nằm sau một feature flag tắt mặc định; (b) đánh dấu
     `completion_source='reconciled'` trên run; (c) **idempotent với
     `q.result`** — khi worker được sửa và message thật đến sau, run đã
     reconciled phải được cập nhật lại đầy đủ (kể cả `model_name`/
     `model_version`), không nhân bản segment (khoá idempotency Quyết định 2);
     (d) có ticket xoá kèm ngày hết hạn.
  4. **Backfill:** các run đang kẹt trên stg/prod phải được hoàn tất một lần
     (qua cầu tạm hoặc script vận hành gọi đúng code Java), **không** UPDATE
     tay trực tiếp vào `digitization_run` — giữ nguyên nguyên tắc mọi chuyển
     trạng thái đi qua `DigitizationRunLifecycleService`.
  5. **Không sửa badge/UI để né vấn đề:** badge của
     `digitization-review-06-queue-pending-status.md` giữ nguyên nguồn dữ liệu
     `processing_status` — nó đang phản ánh **đúng** trạng thái DB; vấn đề nằm
     ở luồng ghi, không ở UI.
- **Lý do chọn C (mà không chọn thẳng A hay B):** A một mình là đúng về kiến
  trúc nhưng **không kiểm soát được lịch** vì code worker nằm ở repo/team
  khác, trong khi thiệt hại đang xảy ra trên môi trường thật (hàng chờ kiểm
  duyệt sai, không có gì được index). B giải quyết được lịch nhưng phải nhét
  máy trạng thái + logic ngưỡng cấu hình + phát sự kiện outbox vào plpgsql,
  chắc chắn cắt bớt NFR-09 và nhân bản ngoại lệ bảo mật ra 3 task — cái giá
  kiến trúc vĩnh viễn cho một vấn đề nhất thời. C giữ **đúng một** điểm ghi
  (backend Java), không mở thêm quyền DB, tái dùng nguyên Quyết định 4/5, và
  tự triệt tiêu khi A hoàn tất.
- **Hệ quả (trade-off):**
  - Trong giai đoạn cầu tạm, một số run có `model_name`/`model_version` rỗng ⇒
    **không chạy lại/truy vết được theo model** (NFR-09). Cần liệt kê được
    các run này (`completion_source='reconciled'`) để chạy lại khi A xong.
  - Thêm một cột (`completion_source`) + một scheduled job + feature flag vào
    backend ⇒ migration mới; DEV chốt DDL/ngưỡng idle ở tech spec.
  - Hợp đồng `q.result` cho `ocr`/`stt`/`sed` cần được viết lại thành tài liệu
    **đối diện team worker** (payload, ví dụ message, ngữ nghĩa
    `failed_retryable` vs `failed_permanent`, yêu cầu cập nhật
    `lease_expires_at`) — hiện chỉ tồn tại dưới dạng code Java
    (`ResultMessage`/`RunResultPayload`), team worker không có nguồn tham
    chiếu ⇒ đây có thể chính là **nguyên nhân gốc** của sai lệch này.
  - Nếu cả A lẫn C đều không khả thi (không sửa được worker **và** không chấp
    nhận nợ NFR-09), phải quay lại xin quyết định nghiệp vụ — **không** tự
    chuyển sang B mà không có ADR mới.

### Cập nhật Quyết định 9 (2026-09-22, sau trao đổi trực tiếp với người dùng): cầu tạm là worker ghi trực tiếp `digitization_run`, chưa dùng `q.result`

- **2 sửa lại so với phân tích gốc của Quyết định 9** (phát hiện khi đào sâu
  thêm cùng người dùng, đọc trực tiếp code — không phải suy đoán):
  1. **Hợp đồng `q.result` ĐÃ có tài liệu đối diện team worker, Status:
     Approved** — `docs/03-dev/specs/digitization-pipeline-02-ocr-stt-processing.md`
     §3.1-3.4, đầy đủ JSON ví dụ cho `q.ocr`/`q.stt`/`q.heartbeat`/`q.result`
     (thành công + thất bại), kể cả lưu ý `bboxPolygon` là tỉ lệ 0-1 không
     phải pixel. Nhận định "chưa có tài liệu, chỉ tồn tại trong code Java"
     ở bản gốc Quyết định 9 (§4 "Hệ quả" bullet cuối, §6 rủi ro cuối) **sai**
     — SA lúc đó chưa tìm hết `docs/03-dev/specs/`. Không còn coi đây là
     nguyên nhân gốc.
  2. **`outbox_event('digitization.completed')` hiện KHÔNG có consumer nào.**
     `SearchIndexOutboxPoller` (poller outbox duy nhất trong repo) không có
     case nào xử lý event type này (rơi vào nhánh `default -> log.warn`).
     Index tìm kiếm thực ra phụ thuộc `review.confirmed`/`review.reconfirmed`
     — do `AutoConfirmScheduler` (quét định kỳ theo `review_deadline_at`,
     **không** đọc outbox) hoặc luồng xác nhận thủ công tự ghi riêng, hoàn
     toàn độc lập với `digitization.completed`. Claim "không ghi
     `digitization.completed` ⇒ đứt FR-18/không index" ở bản gốc §6 dòng đầu
     Quyết định 9 **sai, đã sửa bên dưới**. Chỉ cần `digitization_run.
     review_deadline_at` + `review_state` đúng là auto-confirm/index vẫn
     chạy — không cần ghi thêm outbox event nào.

- **Quyết định thực tế người dùng chọn cho cầu tạm** (thay cho cầu tạm 9-C ở
  bản gốc — 9-C gốc là *backend tự đối soát bằng job đọc `content_segment`*;
  quyết định mới là **worker tự `UPDATE` trực tiếp `digitization_run`** các
  cột nó biết, không qua job đối soát, không qua `q.result`). Phương án B bị
  loại ở bản gốc vì lý do "trigger không biết khi nào run kết thúc + không
  tái tạo được `model_name`/`model_version`" — biến thể này **không** vướng
  2 lý do đó (worker tự biết khi nó xong, và tự cung cấp `model_name`/
  `model_version` trong chính câu `UPDATE`), nên là một lựa chọn khác, không
  phải quay lại B nguyên trạng:

  1. **Worker được cấp quyền `UPDATE` trực tiếp trên `digitization_run`**
     (mở rộng ngoại lệ Quyết định 8 sang `ocr`/`stt`/`sed`, chỉ các cột liệt
     kê dưới đây — cùng tinh thần role phạm vi hẹp `digitization_video_worker`),
     điều kiện `WHERE id = :runId AND processing_status IN ('queued','running')`
     (khớp `conditionallyTransitionProcessingStatus` để không ghi đè run đã
     hoàn tất qua đường khác).
  2. **Nhóm cột worker set thẳng** (đã biết, không cần đọc bảng nào khác):
     `processing_status='succeeded'`, `completed_at`, `model_name`,
     `model_version`, `raw_output_path`, `metrics`, `input_quality_flag`,
     `segment_count` (đếm segment vừa insert), `low_confidence_count` (đếm
     `confidence < 0.60` — ngưỡng cố định), `updated_at`. Task `stt` thêm:
     `digitization_source.duration_ms` set-if-absent (chỉ set khi đang NULL).
  3. **`review_deadline_at` = một giá trị số giờ CỐ ĐỊNH nằm trong code
     worker** (không đọc `system_config.ocr_stt.auto_confirm_hours` lúc
     chạy). **Trade-off chấp nhận**: admin đổi `auto_confirm_hours` qua UI
     **không có tác dụng** với run hoàn tất qua cầu tạm này, chỉ áp dụng cho
     run hoàn tất qua `q.result` thật (sau khi 9-A xong) — cần ghi rõ giới
     hạn này ở tài liệu vận hành.
  4. **`output_quality_flag`/`auto_confirm_blocked`: KHÔNG tính** — lý do
     người dùng nêu trực tiếp: hiện chưa có cách đo chất lượng số hoá phía
     worker. Để `output_quality_flag = NULL` (cột cho phép NULL, `CHECK` chỉ
     ràng buộc khi có giá trị) và `auto_confirm_blocked = false` (default
     cột, không cần set tường minh). **Hệ quả nghiệp vụ chấp nhận**: mọi run
     qua cầu tạm này **không bao giờ bị chặn auto-confirm vì chất lượng
     kém** — cờ chất lượng tự động (Story 4/5) **tạm thời vô hiệu** cho
     `ocr`/`stt`/`sed` trong giai đoạn cầu tạm. Đây là nợ nghiệp vụ có thời
     hạn, không phải nợ kỹ thuật che giấu.
  5. ~~Đánh dấu nguồn hoàn tất bằng cột mới `completion_source`~~ —
     **người dùng quyết định bỏ (2026-09-22): không thêm migration/cột mới.**
     Lý do nêu ra: `UPDATE` trực tiếp set `processing_status='succeeded'`
     đã tự nằm trong diện `conditionallyTransitionProcessingStatus` chặn
     được ghi đè/trùng — không cần thêm cờ đánh dấu riêng cho cầu tạm này
     để đạt mục tiêu chức năng trước mắt. **Rủi ro chấp nhận, ghi lại rõ để
     không quên**: không có cách nào về sau phân biệt "run hoàn tất qua cầu
     tạm (thiếu `model_name`/`model_version` thật, quality gate vô hiệu)"
     với "run hoàn tất qua `q.result` thật" chỉ bằng nhìn `digitization_run`
     — khi triển khai 9-A thật, phải tự lập danh sách run đã qua cầu tạm
     bằng cách khác (ví dụ lọc theo khoảng thời gian cầu tạm còn hiệu lực)
     nếu cần chạy lại để lấy đủ `model_name`/quality thật.
  6. **`q.heartbeat` vẫn nên được worker gửi song song nếu có thể** (khuyến
     nghị, không bắt buộc cho cầu tạm) — nếu không, run không bao giờ qua
     `running` và `DigitizationRunLeaseReconciliationJob` không áp dụng được
     cho các run này trong giai đoạn cầu tạm; chấp nhận được vì cầu tạm giả
     định worker đã ghi xong `content_segment` trước khi `UPDATE` (không có
     "job đang chạy dở" cần theo dõi ở bước này).

- **Không có migration cho cầu tạm này (quyết định 2026-09-22).** Mọi cột
  cần dùng (Nhóm A/B ở mục 2-4) **đã tồn tại sẵn** từ V25 — cầu tạm không
  cần cột mới. Về quyền truy cập: **người dùng tự cấp** tài khoản Postgres
  hiện có cho đội AI worker (không tạo role Postgres phạm vi hẹp riêng qua
  Flyway như đã cân nhắc ban đầu) — nghĩa là worker dùng credential có quyền
  ghi rộng hơn mức tối thiểu cần thiết cho cầu tạm này. Chấp nhận có thời
  hạn, cùng loại rủi ro đã ghi ở rủi ro "worker dùng credential rộng hơn mức
  cần" (§6) — không mở rộng phạm vi rủi ro đó, chỉ xác nhận lại nó vẫn còn
  hiệu lực trong lúc cầu tạm chạy.
  Nội dung cụ thể (câu `UPDATE` mẫu, giá trị `24h`, cột nào set/không set)
  được viết thành tài liệu bàn giao riêng cho đội AI worker:
  `docs/00-overview/runbooks/ocr-stt-worker-direct-update-cau-tam.md`.

- **Có kiểm soát, có giới hạn phạm vi — không phải kiến trúc đích.** Cầu tạm
  này phải được coi là nợ kỹ thuật/nghiệp vụ có thời hạn; mục tiêu cuối vẫn
  là Quyết định 9-A (worker publish `q.result`, thu hồi **mọi** quyền ghi
  Postgres của worker `ocr`/`stt`/`sed`, kể cả quyền mới cấp ở đây). Chưa đặt
  ngày hết hạn cụ thể (người dùng chưa cung cấp timeline sửa worker để publish
  `q.result`) — xem câu hỏi mở cập nhật ở §7.

### Quyết định 10: Bỏ tách nguồn video ở backend — video gốc đẩy thẳng vào `q.video` (bổ sung 2026-09-23)

- **Ngữ cảnh:** yêu cầu nghiệp vụ 2026-09-23: khi upload tệp video vào kho
  dữ liệu chung, backend **không cần tách source** (video-only/audio-only)
  nữa, chỉ gửi **1 message** vào hàng đợi giống cách xử lý tài liệu audio;
  queue đích cho video là **`q.video`** (người dùng chốt 2026-09-23).
- **Các phương án xem xét:** A) giữ Quyết định 7/8 (tách bằng FFmpeg, 2
  message `q.stt` + `q.video`) — B) **bỏ tách, video đi đúng nhánh
  `original` như audio/ảnh/PDF, 1 message vào `q.video`** — C) bỏ tách, 1
  message video gốc vào `q.stt`.
- **Quyết định: B.**
  - **Intake:** `video/*` (đúng danh sách video của `MimeAllowlist`) được xử
    lý như audio/ảnh/PDF: ghi **1 dòng** `digitization_source` với
    `component_type='original'`, **`id = document_id`**, `file_path =
    <bucket>/<storage_key của tệp gốc>`, `mime_type = document.mime_type`,
    `dispatchable = true`, rồi publish ngay trong cùng lượt. **Không** tạo
    `source_separation_job`, **không** chạy FFmpeg, **không** ghi gì vào
    `derived/` ở backend.
  - **Định tuyến:** giữ nguyên quy tắc theo `processFileType` (Quyết định 8):
    `video/*` → task `video` → `q.video` (env `QUEUE_VIDEO_NAME`); `audio/*` →
    `q.stt`; `image/*`, `application/pdf` → `q.ocr`. Không còn message
    `q.stt` nào sinh ra từ một tài liệu video.
  - **Hợp đồng message:** không đổi 6 trường (`runId`, `fileId`, `sourceId`,
    `filePath`, `originalFileType`, `processFileType`). Với video: `sourceId =
    fileId`, `filePath` trỏ tệp **gốc** (không phải `derived/...`),
    `originalFileType = processFileType = video/*`.
  - **Vòng đời kết quả của task `video`:** giữ nguyên Quyết định 8 (backend
    tạo `digitization_run(task='video')` trước khi publish; worker tự cập
    nhật trạng thái/kết quả, không qua `q.result`, không qua review FR-18).
  - **Dữ liệu cũ:** các video đã tách trước 2026-09-23 giữ nguyên các dòng
    `digitization_source` `video`/`audio`, các run đã tạo, và tệp phái sinh
    ở `derived/<document_id>/video.<ext>`/`audio.wav` — **không** migrate,
    **không** xử lý lại. Bảng `source_separation_job` **giữ lại** (chỉ còn
    dữ liệu lịch sử, không ghi mới; luồng purge thùng rác vẫn xoá dòng liên
    quan như cũ) — không `DROP` trong đợt này để tránh đụng
    `delete-restore-trash`.
- **Lý do:** yêu cầu nghiệp vụ trực tiếp; bớt một job nền CPU/I/O nặng ở
  backend (FFmpeg có thể chạy tới 10 phút/tệp, không retry tự động), bớt
  binary `ffmpeg` trong image backend, bớt tệp phái sinh trùng dung lượng
  video gốc; worker video vốn chạy trên node GPU có FFmpeg/NVDEC nên tự xử lý
  container video được.
- **Hệ quả (trade-off):**
  - **FR-19 (STT cho audio của video) không còn được backend kích hoạt.**
    Trước đây phần tiếng của video đi `q.stt` → `content_segment` → review
    FR-18 → index. Nay chỉ còn `q.video`, mà task `video` (Quyết định 8)
    không ghi `content_segment`, không qua review/index. Nếu vẫn cần phiên âm
    + kiểm duyệt cho video thì phải chốt thêm (xem §7) — **đây là mất tính
    năng so với trước, không phải thay đổi trung tính.**
  - `video-worker` nhận **tệp gốc có cả audio track** (không còn video-only
    `-an -c:v copy`) — worker phải tự bỏ qua/tách track âm thanh nếu không
    dùng. Đội worker cần được thông báo trước khi deploy backend.
  - Backend không còn cần binary `ffmpeg` (bỏ `apk add ffmpeg` trong
    `backend/Dockerfile`, bỏ `app.digitization.ffmpeg-*`) — thu hồi ý "Backend
    cần cài đặt binary `ffmpeg`" của Quyết định 7.
  - `SourceSeparationJobRunner`/`SourceSeparationJob*` trở thành code chết,
    DEV gỡ ở tech spec.
  - Không cần sửa trigger V27 (`duration_ms`): trigger chỉ áp cho
    `content_segment`, mà task `video` không ghi `content_segment`.
  - Hạ tầng: `stt-worker` không còn nhận audio tách từ video; tải `q.stt`
    giảm, tải `q.video` giữ nguyên số message (1/video) nhưng kích thước tệp
    lớn hơn (có audio).

## 5. Tech stack đề xuất

- **Backend điều phối:** Spring Boot `digitization-service`:
  - **Pha 1 (Quyết định 7, Quyết định 10):** nhận `document.ingested`, với
    video/audio/ảnh/PDF → ghi `digitization_source` trực tiếp
    (`component_type=original`, không tách nguồn video từ 2026-09-23);
    publish vào `q.video`/`q.stt`/`q.ocr` (exchange `ai.jobs`, tên queue đọc
    từ env `QUEUE_VIDEO_NAME`/`QUEUE_STT_NAME`/`QUEUE_OCR_NAME`).
  - **Pha 2 (chưa implement):** tạo `digitization_job`/`batch`, tiêu thụ
    `q.result`, ghi segment, phát `digitization.completed` qua outbox.
- **Worker AI (Python 3.11, Docker, GPU):** `ocr-worker` (PaddleOCR + VietOCR +
  OpenCV + PyMuPDF), `stt-worker` (faster-whisper/PhoWhisper + Silero VAD —
  chỉ nhận tệp audio gốc; không nhận video, xem Quyết định 10), `video-worker`
  (nhận tệp video gốc có cả audio track qua `q.video`, Quyết định 8/10),
  `sed-worker` (PANNs — tắt mặc định). Model thường trú trong VRAM, không nạp
  lại theo job. **Các worker này publish kết quả vào `q.result` và KHÔNG có
  quyền ghi PostgreSQL** (Quyết định 9) — khác với `video` worker ở Quyết
  định 8.
- **Frontend:** không thuộc slug này (UI tiến độ/hàng chờ ở `digitization-review`);
  chỉ cần API đọc trạng thái job/batch.
- **Data store:** PostgreSQL — Pha 1: `digitization_source`
  (`source_separation_job` chỉ còn dữ liệu lịch sử, không ghi mới — Quyết
  định 10); Pha 2: `digitization_job`,
  `digitization_batch`, `digitization_run`, `content_segment`, `sound_event`,
  `ai_model_registry`, `outbox_event`. SeaweedFS prefix `derived/` (JSON
  thô/ảnh trang từ Pha 2; video/audio tách từ Pha 1 chỉ còn ở dữ liệu trước
  2026-09-23).
- **Infra/Deploy:** RabbitMQ (`ai.jobs` topic + DLX, quorum queue, priority) —
  hạ tầng đã có trong `docker-compose.yml`/`stg/docker-compose.yml`, backend
  cần thêm `spring-boot-starter-amqp` (Quyết định 7); ~~binary `ffmpeg` trong
  môi trường deploy backend~~ (bỏ theo Quyết định 10); Prometheus/Grafana + DCGM cho GPU
  (NFR-13, Pha 2); registry ảnh nội bộ + volume trọng số model đóng gói
  offline (Pha 2).
- **Tham số mới đề xuất đưa vào `system-config` (FR-01):**
  `ocr_stt.min_confidence_ocr`, `ocr_stt.min_confidence_stt`,
  `ocr_stt.poor_ratio_threshold`, `ocr_stt.max_retry`,
  `ocr_stt.segment_max_seconds`, `ocr_stt.sed_enabled`,
  `ocr_stt.diarization_enabled`, `ocr_stt.cer_threshold` (0.05),
  `ocr_stt.wer_threshold` (0.15). (`ai_queue.concurrency`, `ai_queue.batch_size`,
  `ocr_stt.auto_confirm_hours` đã có.)
- **Biến môi trường mới cho Pha 1 (Quyết định 7, backend `.env`/
  `application.yml`, KHÔNG đưa vào `system-config` vì đây là định danh hạ
  tầng/deploy, không phải tham số nghiệp vụ R3 chỉnh):** `QUEUE_OCR_NAME`,
  `QUEUE_STT_NAME` (tên queue AMQP đích, ví dụ mặc định `q.ocr`/`q.stt` —
  chốt số cụ thể ở tech spec Pha 1); tham số kết nối RabbitMQ
  (`RABBITMQ_URL`/host/port/user/pass) dùng chung với hạ tầng đã có ở
  `docker-compose.yml`/`stg/.env`, không định nghĩa lại.
- **Biến môi trường mới cho `q.video` (Quyết định 8):** `QUEUE_VIDEO_NAME`
  (mặc định `q.video`, cùng quy ước với `QUEUE_OCR_NAME`/`QUEUE_STT_NAME`).
  Ngoài ra cần một credential Postgres **riêng, phạm vi ghi hẹp** cấp cho
  video worker (khác hẳn mọi worker khác — xem rủi ro mục 6); tên biến/vị trí
  lưu secret do DEV chốt ở tech spec, không tái dùng credential ứng dụng của
  backend.
- **Data store bổ sung cho `video`worker (Quyết định 8):** OpenSearch/
  Elasticsearch (Qdrant đã gỡ 2026-10-10) do worker tự ghi (không qua backend) — không mở rộng
  `content_segment`; cụ thể collection/index nằm ngoài phạm vi slug này, dẫn
  chiếu `ai-search-infra.md`.
- ~~Bổ sung cho Quyết định 9 (2026-09-22): cột `completion_source` + feature
  flag job đối soát + ngưỡng idle~~ — **đã thay thế, xem "Cập nhật Quyết định
  9" §4.** Cầu tạm cuối cùng chọn (worker `UPDATE` trực tiếp) không cần cột
  mới, không cần feature flag, không cần job đối soát/ngưỡng idle — chỉ cần
  1 tài liệu bàn giao cho đội AI worker
  (`docs/00-overview/runbooks/ocr-stt-worker-direct-update-cau-tam.md`).
  Hợp đồng `q.result` cho đích cuối 9-A **đã có sẵn**, Approved, tại
  `docs/03-dev/specs/digitization-pipeline-02-ocr-stt-processing.md` §3 —
  không cần viết mới.
- **Giá trị khởi điểm tạm thời cho `ai_queue.*`** (C02 chọn đặt số ngay thay vì
  để trống, 2026-08-16 — điều chỉnh lại sau khi đo tải thật trên GPU 96GB):
  `ai_queue.concurrency = 4` (4 worker GPU đồng thời — VRAM thường trú các
  model ~8GB, còn nhiều dư địa để tăng sau đo tải), `ai_queue.batch_size = 50`
  (số tệp enqueue mỗi lần cho một lô, để tiến độ lô cập nhật mượt mà theo
  NFR mục 3).

## 6. Rủi ro

| Rủi ro | Mức độ | Giảm thiểu |
|---|---|---|
| **Chất lượng OCR với chữ viết tay, mẫu biểu có bảng, mộc/chữ ký đè chữ** thấp hơn kỳ vọng nghiệp vụ | **Cao** | Benchmark giai đoạn 0 trên mẫu thật; không cam kết viết tay/bảng ở giai đoạn 1; giữ confidence thấp + bbox để cán bộ đối chiếu bản gốc (FR-18); dự phòng model handwriting ở giai đoạn 2 |
| **Luôn OCR toàn bộ PDF kể cả có sẵn text layer** (QĐ 1, quyết định C02) làm tăng tải GPU/thời gian xử lý so với phương án trích thẳng | Trung bình | Ưu tiên hoá theo NFR mục 3; theo dõi throughput OCR thực tế so với mục tiêu 1.500 trang/giờ sau benchmark; cân nhắc lại nếu backlog vượt ngưỡng ở giai đoạn sau |
| **Ảo giác của họ Whisper** trên đoạn im lặng/nhiễu → sinh câu không có thật trong chứng cứ | **Cao** | VAD cắt khoảng lặng trước ASR; ngưỡng `no_speech_prob`; gắn cờ thay vì xoá; **không** index khi chưa xác nhận (QĐ 4) |
| **Backlog lô lớn chiếm trọn GPU**, tệp lẻ của cán bộ chờ hàng giờ | Trung bình | Priority queue 2 mức + hạn ngạch tối thiểu cho bulk; lên lịch lô nặng giờ thấp điểm; cảnh báo khi độ sâu hàng đợi/thời gian chờ vượt ngưỡng |
| **Job dài bị treo** (worker/GPU chết giữa chừng) → tài liệu kẹt `running` vĩnh viễn | Trung bình | Lease + heartbeat 30s, quét job quá hạn → requeue; idempotent theo `(document_id, task, model_version)`; giới hạn 3 lần rồi vào DLQ |
| **Đổi/nâng cấp model ⇒ phải chạy lại toàn kho**, chiếm GPU nhiều ngày | Trung bình | `run_id` + `model_version` trên mọi kết quả; giữ JSON thô ở `derived/`; chạy lại có chọn lọc (ưu tiên tài liệu `poor`/low-confidence) vào giờ thấp điểm; rollback qua `ai_model_registry` |
| **Kết quả pending bị lộ/được dùng như dữ liệu chính thức** hoặc worker ghi nhầm lên bản gốc | Trung bình | Credential SeaweedFS chỉ đọc trên prefix gốc, chỉ ghi `derived/`; kết quả kế thừa ACL tài liệu nguồn; chỉ `review.confirmed` mới index |
| **SED false positive** trên dữ liệu ngoài miền AudioSet (nếu bật) | Trung bình | Mặc định TẮT (QĐ 6); nếu bật: ngưỡng cao, nhãn "gợi ý cần xác minh", không dùng làm căn cứ độc lập |
| Số bản ghi segment rất lớn (~20 triệu) làm chậm truy vấn/kiểm duyệt | Thấp–TB | Partition + index theo `document_id`; JSON thô để ngoài DB; phân trang bắt buộc ở API |
| **(Quyết định 10) Video không còn được STT/kiểm duyệt/index nội dung lời nói** — phần tiếng của video trước đây đi `q.stt` → `content_segment` → FR-18 → tìm kiếm; nay chỉ đi `q.video` (không ghi `content_segment`) | **Cao** | Chốt câu hỏi mở §7 (video-worker có tự STT không, kết quả về đâu); cho tới khi chốt, ghi rõ FR-19 cho video là **chưa đáp ứng** trong tài liệu nghiệm thu |
| **(Quyết định 10) `video-worker` nhận tệp gốc có audio track** thay vì video-only đã chuẩn hoá — worker hiện tại có thể giả định đầu vào không có âm thanh | Trung bình | Thông báo đội worker trước khi deploy backend; test 1 message `q.video` với tệp có audio trên stg |
| **(Quyết định 8) Video worker có quyền ghi trực tiếp PostgreSQL** — lần đầu tiên một worker AI không đi qua backend để ghi trạng thái, phá vỡ ranh giới "PostgreSQL là nguồn sự thật do backend ghi" áp dụng cho mọi task khác | **Cao** | Credential Postgres riêng, phạm vi ghi hẹp nhất có thể (không dùng chung role với backend); review bảo mật riêng trước khi implement; cân nhắc thay bằng một API nội bộ tối thiểu (thay vì DB access trực tiếp) nếu review phát hiện rủi ro quá cao |
| **(Quyết định 8) Job `video` không có cơ chế đối soát job-treo chuẩn** vì không còn `q.result` để backend xác nhận hoàn tất | Trung bình | DEV xác nhận worker vẫn cập nhật `lease_expires_at`/heartbeat đúng chuẩn Quyết định 2 dù ghi kết quả trực tiếp; nếu không, cân nhắc thêm một tín hiệu tối thiểu (heartbeat-only, không cần payload kết quả) |
| **(Quyết định 9, sửa 2026-09-22) Worker `ocr`/`stt`/`sed` đang INSERT thẳng `content_segment`** ⇒ run kẹt `queued`/`running` vĩnh viễn, `segment_count = 0` (đã xác nhận trên stg: 0 run `succeeded`). ~~`digitization.completed` không bao giờ phát ⇒ đứt FR-18/index~~ **— sai, đã sửa**: event đó hiện không có consumer nào, index thực phụ thuộc `review.confirmed` (không phụ thuộc `digitization.completed`) | **Cao** | Cầu tạm 2026-09-22: worker `UPDATE` trực tiếp `digitization_run` (xem "Cập nhật Quyết định 9" ở §4) để `review_deadline_at`/`review_state` đúng ngay, auto-confirm/index vẫn chạy bình thường qua đường đó; đích cuối vẫn là 9-A (worker publish `q.result`) |
| **(Quyết định 9) Worker `ocr`/`stt`/`sed` đang dùng credential Postgres rộng hơn mức cần** (role `digitization_video_worker` không hề có quyền trên `content_segment` ⇒ worker đang dùng credential khác, nhiều khả năng role ứng dụng của backend) | **Cao** | Rà soát ngay credential worker đang dùng trên A100-Server (`pg_stat_activity`/config worker); thu hồi khi 9-A hoàn tất. **Cập nhật 2026-09-22**: người dùng quyết định cầu tạm cũng dùng chung credential rộng này để `UPDATE digitization_run` (không tạo role Postgres phạm vi hẹp riêng qua migration) — rủi ro này **không thu hẹp** trong lúc cầu tạm chạy, chỉ xác nhận vẫn còn hiệu lực |
| **(Quyết định 9, cầu tạm 2026-09-22) Cờ chất lượng tự động (`output_quality_flag`/`auto_confirm_blocked`) vô hiệu cho mọi run qua cầu tạm** — worker chưa có cách đo chất lượng, để `NULL`/`false` mặc định | Trung bình | Chấp nhận có thời hạn (nợ nghiệp vụ, không phải nợ kỹ thuật) — người dùng quyết định không thêm cột đánh dấu (`completion_source`) để lọc lại sau, nên **không có cách tự động** liệt kê những run này về sau; nếu cần chấm lại chất lượng, phải xác định bằng mốc thời gian cầu tạm còn hiệu lực |
| **(Quyết định 9, cầu tạm 2026-09-22) `review_deadline_at` dùng giá trị giờ cố định trong code worker (`T + 24h`), không đọc `system_config.ocr_stt.auto_confirm_hours`** ⇒ admin đổi ngưỡng qua UI không có tác dụng với các run này | Thấp | Ghi rõ giới hạn ở tài liệu bàn giao cho đội worker (`docs/00-overview/runbooks/ocr-stt-worker-direct-update-cau-tam.md`); hiện trùng giá trị default (24h) nên chưa có sai lệch thực tế |
| **(Quyết định 9, cầu tạm 2026-09-22) Không có cờ đánh dấu để phân biệt run hoàn tất qua cầu tạm với run hoàn tất qua `q.result` thật** (người dùng quyết định bỏ cột `completion_source`, 2026-09-22) | Trung bình | `processing_status='succeeded'` do cầu tạm set vẫn được `conditionallyTransitionProcessingStatus` coi là trạng thái cuối nên **chặn được trùng segment** nếu 9-A sau này gửi `q.result` cho đúng `run_id` đó — rủi ro còn lại chỉ là **không truy vết được** run nào thiếu `model_name`/quality thật để chủ động chạy lại; nếu cần, xác định bằng mốc thời gian (run hoàn tất trong giai đoạn cầu tạm còn hiệu lực) |

## 7. Câu hỏi mở

Toàn bộ câu hỏi đã được C02 xác nhận **2026-08-16**. Không còn câu hỏi mở chặn
`/ba-story digitization-pipeline`; các mục còn "có điều kiện" (benchmark giai
đoạn 0, dữ liệu thật) là công việc tiếp theo, không phải quyết định treo.
**Bổ sung 2026-09-22 (Quyết định 9, sửa cùng ngày sau khi chốt cầu tạm mới —
xem "Cập nhật Quyết định 9" §4):** cầu tạm đã chốt là worker `UPDATE` trực
tiếp `digitization_run`, không còn chờ quyết định "có bật cầu tạm C hay
không" — 2 câu hỏi CHẶN cũ (ai sở hữu code worker / timeline sửa worker) vẫn
còn **liên quan cho đích cuối 9-A**, nhưng **không còn chặn việc viết tech
spec cho cầu tạm** (cầu tạm không cần sửa worker theo hướng q.result). Cả 2
câu hỏi từng CHẶN tech spec cầu tạm (giá trị `review_deadline_at`, schema
`content_segment`) đã được người dùng xác nhận cùng ngày — **không còn câu
hỏi nào chặn `/dev-spec` cho cầu tạm**. Còn lại (không chặn):

- [x] **Giá trị giờ cố định cho `review_deadline_at`?** → **Chốt 2026-09-22:
      `T + 24h`** (`T` = thời điểm worker `UPDATE`, tức `completed_at`).
      Trùng với default hiện tại của `system_config.ocr_stt.auto_confirm_hours`
      ("24", xem `SystemConfigDefaults.java`) — chỉ là trùng lúc chốt, cầu
      tạm **không** đọc bảng `system_config`, nên nếu admin đổi
      `auto_confirm_hours` sau này, 2 giá trị sẽ lệch nhau (đã ghi ở rủi ro
      §6) cho tới khi cầu tạm bị gỡ.
- [x] **Worker có ghi `content_segment` đúng schema/ngữ nghĩa Quyết định 3
      không?** → **Xác nhận 2026-09-22 bởi người dùng**: đã kiểm tra trực
      tiếp, nội dung OCR/STT hiển thị đúng khi xem chi tiết run ở màn hình
      "Kiểm duyệt số hoá" (`/digitization/runs/{runId}`) — tức dữ liệu
      `content_segment` hiện có đã đúng đủ để review (text, đoạn, mốc thời
      gian hiển thị hợp lý). Không cần kế hoạch dọn dữ liệu. Không phải xác
      nhận từng trường (`bbox_polygon` tỉ lệ 0-1, `confidence`...) ở mức
      byte-for-byte — nếu sau này review phát hiện lệch vị trí bbox trên
      OCR viewer, quay lại kiểm tra điểm này.
- [ ] **(không chặn cầu tạm, chặn 9-A) Ai sở hữu code worker `ocr`/`stt`/`sed`
      trên A100-Server?** Repo nào, team nào, ta có quyền sửa/PR không hay
      chỉ có quyền vận hành hạn chế? Cần cho đích cuối 9-A (thu hồi quyền,
      chuyển hẳn sang `q.result`), không cần để triển khai cầu tạm.
- [ ] **(không chặn cầu tạm, chặn 9-A) Timeline sửa worker để publish
      `q.result`?** Quyết định khi nào cầu tạm được coi là hết hạn/gỡ bỏ.
- [x] **Worker đang dùng credential Postgres nào để INSERT `content_segment`
      (và giờ cả `UPDATE digitization_run` cho cầu tạm)?** → **Chốt
      2026-09-22**: người dùng tự cấp tài khoản Postgres hiện có cho đội AI
      worker, không tạo role Postgres phạm vi hẹp riêng qua migration —
      xem "Cập nhật Quyết định 9" §4. Vẫn cần thu hồi/thay bằng credential
      hẹp hơn khi 9-A hoàn tất (§6 rủi ro).
- [ ] **(không chặn) Có bao nhiêu run đang kẹt trên stg/prod và có cần
      backfill toàn bộ không** (chạy cầu tạm 1 lần cho các run cũ), hay chỉ
      backfill từ một mốc thời gian? Ops xác nhận trước khi chạy.
- [x] *Engine OCR/STT cục bộ nào?* → Quyết định 1 (PaddleOCR+VietOCR;
      PhoWhisper/faster-whisper), **có điều kiện**: chốt cuối sau benchmark
      giai đoạn 0.
- [x] *Cách phân đoạn + mốc thời gian (FR-20)?* → Quyết định 3 (segment thống
      nhất, ms nguyên, 3–30s, invariant `end_ms ≤ duration_ms`).
- [x] *Đánh dấu độ tin cậy thấp?* → Quyết định 5 (2 lớp cờ, ngưỡng cấu hình).
- [x] **`ai-search-infra.md`** đã đổi sang `Status: Approved`.
- [x] **Ngưỡng nghiệm thu:** CER ≤ 5% (OCR), WER ≤ 15% (STT) — xem mục 3 NFR;
      benchmark giai đoạn 0 có thể đề xuất điều chỉnh.
- [x] **STT áp cơ chế tự-xác-nhận 24h giống OCR**, dùng chung
      `ocr_stt.auto_confirm_hours` — xem Quyết định 4.
- [x] **Tài liệu chất lượng kém (`poor`) bị chặn tự-xác-nhận-24h**
      (`auto_confirm_blocked = true`) — xem Quyết định 5.
- [x] **SED và diarization để giai đoạn 2**, không vào cam kết 20/12/2026 —
      xem Quyết định 6. Danh sách nhãn SED sẽ chốt khi mở phạm vi giai đoạn 2.
- [x] **OCR chữ viết tay:** không đầu tư model riêng ở giai đoạn 1; giữ
      confidence thấp + bbox để cán bộ đối chiếu (xem rủi ro mục 6).
- [x] **PDF đã có lớp text vẫn phải OCR toàn bộ**, không dùng lớp text sẵn có
      để bỏ qua OCR — xem Quyết định 1 (đã cập nhật) và Quyết định 3
      (`content_source` không còn giá trị `native_text`).
- [x] ~~**Video giai đoạn 1 chỉ tách audio để STT**, không OCR khung hình,
      không phân tích hình ảnh — xác nhận đúng phạm vi ban đầu.~~ **Đã thay
      bằng Quyết định 8 (2026-09-14):** video component nay được dispatch
      vào `q.video` để phân tích hình ảnh; phần audio→`q.stt` giữ nguyên
      không đổi. **Cập nhật 2026-09-23 (Quyết định 10):** không còn tách —
      video gốc (có audio) → `q.video`, không còn audio→`q.stt` cho video.
- [x] **(Quyết định 8) Phạm vi credential Postgres cấp cho video worker** —
      xác nhận 2026-09-14: **role DB riêng, ghi trực tiếp** (không dùng proxy
      nội bộ), phạm vi cột hẹp — chi tiết GRANT ở
      `digitization-pipeline-03-video-queue.md` mục 2.4 (đã bổ sung
      `model_name`/`model_version`/`metrics` để không vi phạm NFR-09 truy vết
      model, và yêu cầu worker cập nhật `lease_expires_at` định kỳ để
      `DigitizationRunLeaseReconciliationJob` vẫn phát hiện job treo).
      **Cập nhật 2026-09-22 (Quyết định 9):** ngoại lệ này **chỉ** áp dụng cho
      task `video`, **không** mở rộng sang `ocr`/`stt`/`sed`.
- [x] **Bộ mẫu benchmark giai đoạn 0:** dùng bộ mẫu tổng hợp (synthetic/public)
      do SA/DEV tự chuẩn bị tạm thời, thay bằng dữ liệu thật của C02 khi có —
      xem Quyết định 1 (Hệ quả).
- [x] **`ai_queue.concurrency = 4`, `ai_queue.batch_size = 50`** đặt làm giá
      trị khởi điểm ngay — xem mục 5; điều chỉnh lại sau khi đo tải GPU 96GB
      thật.
- [x] **Tách nguồn video (FFmpeg) chạy ở backend, không phải AI worker; đẩy
      dispatch trực tiếp từ backend cho audio/ảnh/PDF** (xác nhận 2026-08-30)
      → Quyết định 7. **Thay thế 2026-09-23 bởi Quyết định 10** (không tách,
      video gốc → `q.video`). Triển khai tách pha:
      `digitization-pipeline-01-source-dispatch` (BA/DEV riêng, chỉ tách
      nguồn + lưu DB + đẩy queue) trước, phần tiêu thụ hàng đợi/OCR/STT/ghi
      kết quả (Quyết định 1–4, Pha 2) làm sau, chưa đặt tên slug.
- [x] **Queue theo loại tác vụ, không dùng 1 queue chung** (xác nhận
      2026-08-30): audio → `q.stt`, ảnh/PDF → `q.ocr`, đúng thiết kế gốc của
      Quyết định 2, không đổi. Tên queue lấy từ env `QUEUE_OCR_NAME`/
      `QUEUE_STT_NAME` (xem mục 5).
- [x] **(Quyết định 10) Video có còn tách nguồn ở backend không, gửi queue
      nào?** → **Chốt 2026-09-23**: không tách; 1 message duy nhất, tệp gốc,
      vào `q.video`.
- [ ] **(Quyết định 10, CHẶN nghiệm thu FR-19 cho video, không chặn
      implement backend) Phần lời nói trong video có còn cần STT + kiểm
      duyệt + tìm kiếm không?** Nếu có: `video-worker` tự STT và ghi kết quả
      ở đâu (vào `content_segment` qua `q.result` như Quyết định 9-A, hay kho
      riêng của worker)? Nếu không: ghi nhận FR-19 chỉ áp cho tệp audio.
- [ ] **(Quyết định 10, không chặn) Có cần chạy lại video đã tách trước
      2026-09-23 qua luồng mới (1 message tệp gốc vào `q.video`) không?**
      Mặc định: không.
- [x] **(Quyết định 9) `ocr`/`stt`/`sed` có được hợp thức hoá việc ghi thẳng
      `content_segment` như `video` không?** → **KHÔNG** (2026-09-22): hợp
      đồng `q.result` giữ nguyên, phương án trigger đồng bộ ngược (B) bị loại
      vì phải nhét máy trạng thái + ngưỡng cấu hình + phát outbox vào plpgsql
      và chắc chắn vi phạm NFR-09 — xem Quyết định 9.
