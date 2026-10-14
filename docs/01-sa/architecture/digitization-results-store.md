# Architecture: digitization-results-store

> File: `docs/01-sa/architecture/digitization-results-store.md` — Owner: SA
> Status: Approved
> Ghi chú 2026-09-23: đồng bộ các dẫn chiếu tới tách nguồn video theo
> `digitization-pipeline.md` Quyết định 10 (duyệt 2026-09-23) — không
> đổi DDL/quyết định nào của tài liệu này.

## 1. Bối cảnh / Phạm vi

- Đây là **thiết kế lưu trữ cụ thể** (Postgres DDL + chỉ mục tìm kiếm — Qdrant đã thay bằng vector k-NN trong OpenSearch, 2026-10-10) cho 4
  loại đầu ra số hoá: **STT**, **Sound Event Detection (SED)**, **OCR**, và
  **tóm tắt nội dung video**. Chưa file nào viết DDL đầy đủ cho phần này —
  `digitization-pipeline.md` (Approved) mới dừng ở mức khái niệm (Quyết định
  2/3/5/6); `digitization-review.md` (Draft) định nghĩa máy trạng thái duyệt
  nhưng cũng chưa có DDL; `ai-search-infra.md`/`search-semantic.md` (Approved)
  đã chốt chỉ mục vector ở mức hạ tầng chung cho mọi slug (từ 2026-10-10: field vector trên index `segment` của OpenSearch, Qdrant đã gỡ — `search-semantic.md` QĐ 6). Tài liệu này **hợp nhất**
  các quyết định đó thành schema triển khai được, và đề xuất phần mới (SED
  đưa vào tìm kiếm được, tóm tắt video).
- **Nguyên tắc xuyên suốt** (kế thừa, không đổi): **PostgreSQL là nguồn sự
  thật** cho cả nội dung lẫn trạng thái; **chỉ mục (OpenSearch, gồm vector) chỉ là chỉ mục dựng lại
  được** — không lưu trạng thái xử lý, không lưu bản nháp chưa xác nhận,
  không trả `text` từ chỉ mục cho UI (tra `search-semantic.md`
  Quyết định 4).
- **Không thay thế quyết định đã Approved** ở `digitization-pipeline.md`/
  `ai-search-infra.md`/`search-semantic.md` — nếu có chỗ nào ở đây khác chữ
  cách diễn đạt gốc, coi đây là **cụ thể hoá**, không phải quyết định lại; 2
  điểm thực sự cần đối chiếu/xác nhận lại được nêu rõ ở mục 7.
- **Tóm tắt nội dung video chưa phải FR** trong SRS (đã ghi rõ ở
  `digitization-pipeline.md` mục 1, out-of-scope). Phần thiết kế cho nó ở đây
  làm theo đúng tinh thần đã áp dụng cho SED ở Quyết định 6 của tài liệu đó:
  **dựng sẵn hạ tầng, feature flag tắt**, chờ xác nhận nghiệp vụ trước khi
  bật.
- Ngoài phạm vi: engine/model chạy OCR/STT/SED cụ thể (`digitization-pipeline`
  Quyết định 1); luồng duyệt/sửa/tự xác nhận chi tiết (`digitization-review`,
  chỉ tái sử dụng schema); truy vấn/API tìm kiếm (`search-fulltext`/
  `search-semantic`); intake/dispatch Pha 1 (`digitization-pipeline.md` Quyết
  định 7/10 — từ 2026-09-23 video không còn tách nguồn).

## 2. Sơ đồ quan hệ tổng quát

```
document (đã có — data-ingestion.md)
  │
  ├─ digitization_source (Pha 1 — 1 dòng 'original' cho mọi loại; video cũ
  │                       trước 2026-09-23: 2 dòng video/audio đã tách)
  │     │
  │     └─ digitization_run (N — một dòng / lần chạy AI cho 1 (source, task))
  │           │                 task ∈ {ocr, stt, sed, video_summary}
  │           ├─ content_segment (N — OCR: theo block; STT: theo câu;
  │           │     │              SED promoted + video_summary: 0..1 dòng đặc biệt)
  │           │     └─ segment_revision (N — lịch sử sửa, append-only)
  │           │
  │           └─ sound_event (N — chỉ khi task=sed; MỌI detection thô,
  │                            kể cả dưới ngưỡng, phục vụ audit/QA)
  │
  └─ document_digitization_status (1 — read-model tổng hợp trạng thái 4 task)

digitization_batch (0..1 — nếu run phát sinh từ một lượt nạp hàng loạt)
  └─ digitization_run.batch_id (FK, nullable)

OpenSearch index segment (đã có, dùng chung; từ 2026-10-10 mang thêm field
vector — thay Qdrant segment-vec-v1) ← chỉ nhận content_segment đã
review_state='confirmed' (qua review.confirmed/review.reconfirmed,
digitization-review Quyết định 6) — KHÔNG có bảng Postgres mới nào cho chỉ
mục, chỉ mở rộng trường (mục 4).
```

## 3. Thiết kế PostgreSQL

### 3.1 `digitization_run` — hợp nhất `digitization_job` + `digitization_run`

**Quyết định hợp nhất (cần xác nhận, xem mục 7):** `digitization-pipeline.md`
Quyết định 2 đặt tên `digitization_job` cho vòng đời **hàng đợi/GPU**
(`queued→running→succeeded|failed_retryable|failed_permanent|cancelled`,
lease+heartbeat, retry) và Quyết định 3 đặt tên `digitization_run` cho vòng
đời **kết quả** (`run_id`, `model_name/version`, `metrics`, "chỉ 1 run active
mỗi `(document_id, task)`"). Hai vòng đời này mô tả **cùng một thực thể** ở
hai thời điểm (đang xử lý ↔ đã có kết quả) — tách hai bảng buộc phải JOIN mọi
truy vấn trạng thái và đồng bộ 2 khoá chính. Thiết kế này **hợp nhất thành
một bảng `digitization_run`**, mang cả cột vận hành (queue) lẫn cột kết quả
(model) lẫn cột duyệt (review, theo **cấp run** — đã chốt ở
`digitization-review.md` Quyết định 2, khác với "cấp segment" ghi tạm ở bản
nháp cũ của `digitization-pipeline.md` Quyết định 4 mục 1).

```sql
CREATE TABLE digitization_run (
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    document_id            UUID NOT NULL REFERENCES document (id),
    source_id              UUID NOT NULL REFERENCES digitization_source (id),
    task                   VARCHAR(16) NOT NULL
        CHECK (task IN ('ocr', 'stt', 'sed', 'video_summary')),
    batch_id               UUID REFERENCES digitization_batch (id),

    -- Vòng đời hàng đợi/GPU (digitization-pipeline.md Quyết định 2)
    processing_status      VARCHAR(20) NOT NULL DEFAULT 'queued'
        CHECK (processing_status IN
            ('queued', 'running', 'succeeded', 'failed_retryable',
             'failed_permanent', 'cancelled')),
    attempt_count          INT NOT NULL DEFAULT 0,
    lease_expires_at       TIMESTAMPTZ,
    worker_id              VARCHAR(255),
    last_error             TEXT,

    -- Kết quả & model (Quyết định 3)
    model_name             VARCHAR(255),
    model_version          VARCHAR(64),
    metrics                JSONB,
    started_at             TIMESTAMPTZ,
    completed_at           TIMESTAMPTZ,
    raw_output_path        VARCHAR(1024), -- derived/<document_id>/<run_id>.json

    -- Cờ chất lượng (Quyết định 5) — input đo trước GPU, output đo sau khi có confidence
    input_quality_flag     VARCHAR(16)
        CHECK (input_quality_flag IN ('ok', 'poor_scan', 'low_audio_snr')),
    output_quality_flag    VARCHAR(16) CHECK (output_quality_flag IN ('ok', 'poor')),
    low_confidence_count   INT NOT NULL DEFAULT 0,
    segment_count          INT NOT NULL DEFAULT 0,
    auto_confirm_blocked   BOOLEAN NOT NULL DEFAULT false,

    -- Duyệt — CẤP RUN (digitization-review.md Quyết định 1/2). 'not_applicable'
    -- cho task không qua FR-18 (sed, video_summary — xem mục 7 điểm 2).
    review_state           VARCHAR(16) NOT NULL DEFAULT 'not_applicable'
        CHECK (review_state IN
            ('not_applicable', 'pending', 'in_review', 'confirmed',
             'superseded', 'void')),
    review_deadline_at     TIMESTAMPTZ,
    confirm_source          VARCHAR(20)
        CHECK (confirm_source IN ('auto', 'manual_unchanged', 'manual_edited')),
    confirmed_by            VARCHAR(255),
    confirmed_at            TIMESTAMPTZ,
    first_viewed_at         TIMESTAMPTZ,
    first_viewed_by         VARCHAR(255),
    edited_segment_count    INT NOT NULL DEFAULT 0,
    first_edit_at           TIMESTAMPTZ,

    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- "chỉ 1 run active mỗi (document_id, task)" (Quyết định 3) — active = chưa
-- superseded/void. Cho phép nhiều run supersed/void song song (lịch sử).
CREATE UNIQUE INDEX UQ_digitization_run_active
    ON digitization_run (document_id, task)
    WHERE review_state NOT IN ('superseded', 'void');

-- Poll hàng đợi GPU (worker lấy job) + job đối soát (lease quá hạn)
CREATE INDEX IDX_digitization_run_pollable
    ON digitization_run (processing_status, lease_expires_at);
-- Hàng chờ kiểm duyệt (digitization-review.md) + auto-confirm sweeper
CREATE INDEX IDX_digitization_run_review_pollable
    ON digitization_run (review_state, review_deadline_at);
CREATE INDEX IDX_digitization_run_document ON digitization_run (document_id);
```

### 3.2 `digitization_batch` — tiến độ xử lý theo lô

Cho lượt nạp thư mục/hàng loạt cần thanh tiến độ (`digitization-pipeline.md`
Quyết định 2 mục "Lô"). **Không** trùng với `upload_batch` của
`data-ingestion.md` (đó là batch **tải lên**; đây là batch **số hoá**, có thể
gồm tài liệu từ nhiều `upload_batch` khác nhau nếu người dùng chọn "số hoá lại
cả thư mục").

```sql
CREATE TABLE digitization_batch (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    label            VARCHAR(255),
    total_count      INT NOT NULL,
    succeeded_count  INT NOT NULL DEFAULT 0,
    failed_count     INT NOT NULL DEFAULT 0,
    created_by_sub   VARCHAR(255) NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);
-- pending_count không lưu cột riêng — tính = total_count - succeeded_count -
-- failed_count để tránh 2 nơi cùng giữ một số phải đồng bộ tay.
```

### 3.3 `content_segment` — đơn vị nội dung thống nhất (OCR/STT/SED/tóm tắt)

Giữ nguyên khái niệm "một segment duy nhất" của `digitization-pipeline.md`
Quyết định 3 (khớp đơn vị index của `ai-search-infra.md` Quyết định 6), **mở
rộng `content_source`** thêm `sed` (đã có trong văn bản Quyết định 3 gốc,
nay hiện thực) và `summary` (mới — xem mục 3.3.1).

```sql
CREATE TABLE content_segment (
    id                       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    run_id                   UUID NOT NULL REFERENCES digitization_run (id),
    document_id              UUID NOT NULL REFERENCES document (id), -- denormalized, khớp trường document_id của chỉ mục (OpenSearch)
    segment_index            INT NOT NULL, -- thứ tự trong run

    content_source           VARCHAR(16) NOT NULL
        CHECK (content_source IN ('ocr', 'stt', 'sed', 'summary')),
    origin                   VARCHAR(8) NOT NULL DEFAULT 'ai'
        CHECK (origin IN ('ai', 'manual')), -- 'manual' = cán bộ tự thêm đoạn khi duyệt

    -- OCR (content_source='ocr')
    page_no                  INT,
    bbox_polygon             JSONB,
    reading_order            INT,
    line_from                INT,
    line_to                  INT,

    -- STT / SED-promoted (content_source='stt'|'sed'); NULL cho 'summary'
    -- (position_type=none, cùng quy ước với title/note ở ai-search-infra.md
    -- mục 8) — số nguyên mili-giây, tương đối từ đầu media (Quyết định 3).
    time_start_ms            INT,
    time_end_ms              INT,
    speaker_label            VARCHAR(64), -- diarization, feature flag tắt mặc định

    -- 'summary' (content_source='summary') — các segment STT/OCR/SED làm căn
    -- cứ, để dẫn nguồn/chống ảo giác (cùng nguyên tắc "mọi câu sinh ra phải
    -- neo vào segment_id" đã đặt ra ở search-semantic.md Quyết định 4).
    source_segment_ids       UUID[],

    -- Bản AI sinh — BẤT BIẾN sau khi run kết thúc (digitization-review.md
    -- Quyết định 3)
    ai_text                  TEXT NOT NULL,
    ai_time_start_ms         INT,
    ai_time_end_ms           INT,

    -- Bản hiện hành — cập nhật cùng transaction với segment_revision
    current_text             TEXT NOT NULL,
    current_time_start_ms    INT,
    current_time_end_ms      INT,
    current_revision_no      INT NOT NULL DEFAULT 0,
    is_edited                 BOOLEAN NOT NULL DEFAULT false,

    confidence                NUMERIC(4, 3), -- 0..1; NULL cho 'summary' (LLM, không có điểm tin cậy per-token)

    created_at                TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- Bất biến mốc thời gian (digitization-review.md Quyết định 4, tái dùng
    -- nguyên vẹn) — kiểm cả ai_* lẫn current_* vì cả 2 đều có thể được đọc.
    CONSTRAINT CK_content_segment_ai_time
        CHECK (ai_time_start_ms IS NULL OR ai_time_start_ms < ai_time_end_ms),
    CONSTRAINT CK_content_segment_current_time
        CHECK (current_time_start_ms IS NULL OR current_time_start_ms < current_time_end_ms)
);

CREATE INDEX IDX_content_segment_run ON content_segment (run_id, segment_index);
CREATE INDEX IDX_content_segment_document ON content_segment (document_id);
-- Job đối soát chất lượng (Quyết định 5) quét theo run + confidence thấp
CREATE INDEX IDX_content_segment_low_confidence
    ON content_segment (run_id) WHERE confidence < 0.60;
```

**3.3.1 Vì sao tóm tắt video KHÔNG có bảng riêng, mà là 1 dòng
`content_segment`:** tóm tắt là văn bản gắn với **cả tài liệu**, không gắn
toạ độ trang/thời gian — đúng hệt precedent đã có sẵn cho segment `title`/
`note` ở `ai-search-infra.md` mục 8 (`position_type = none`). Dùng lại
nguyên khái niệm segment nghĩa là tóm tắt **tự động** có: lịch sử sửa
(`segment_revision`), luồng duyệt/tự-xác-nhận 24h (`digitization_run.
review_state`), và index tìm kiếm — không phải xây riêng một bộ máy vòng đời
thứ hai chỉ cho 1 loại nội dung. Đánh đổi: mỗi tài liệu video chỉ có **đúng 1
segment tóm tắt** (không phân đoạn) — nếu sau này cần tóm tắt theo từng
chương/đoạn video, tách thành nhiều dòng `content_segment` cùng `run_id`,
schema không đổi.

### 3.4 `segment_revision` — lịch sử sửa (append-only)

Nguyên bản từ `digitization-review.md` Quyết định 3, không đổi:

```sql
CREATE TABLE segment_revision (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    segment_id      UUID NOT NULL REFERENCES content_segment (id),
    revision_no     INT NOT NULL,
    text            TEXT,
    time_start_ms   INT,
    time_end_ms      INT,
    deleted           BOOLEAN NOT NULL DEFAULT false,
    edited_by          VARCHAR(255) NOT NULL,
    edited_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    reason                 TEXT
);
CREATE UNIQUE INDEX UQ_segment_revision ON segment_revision (segment_id, revision_no);
```

### 3.5 `sound_event` — detection thô của SED

Tách khỏi `content_segment` theo đúng tinh thần Quyết định 6 của
`digitization-pipeline.md`: **mọi** detection (kể cả dưới ngưỡng) được ghi ở
đây để audit/QA/hiệu chỉnh ngưỡng sau này; chỉ những detection **vượt
ngưỡng** mới được "thăng hạng" (promote) thành 1 dòng `content_segment`
(`content_source='sed'`) để có thể tìm kiếm được (mục 4).

```sql
CREATE TABLE sound_event (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    run_id           UUID NOT NULL REFERENCES digitization_run (id), -- task='sed'
    document_id      UUID NOT NULL REFERENCES document (id),
    label            VARCHAR(100) NOT NULL, -- nhãn AudioSet gốc (tiếng Anh)
    label_vi         VARCHAR(200),          -- mô tả tiếng Việt để hiển thị/tìm kiếm
    confidence       NUMERIC(4, 3) NOT NULL,
    time_start_ms        INT NOT NULL,
    time_end_ms          INT NOT NULL,
    promoted_segment_id  UUID REFERENCES content_segment (id), -- NULL nếu dưới ngưỡng
    verified             BOOLEAN NOT NULL DEFAULT false, -- cán bộ xác minh thủ công (Quyết định 6: "gợi ý — cần xác minh")
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT CK_sound_event_time CHECK (time_start_ms < time_end_ms)
);
CREATE INDEX IDX_sound_event_run ON sound_event (run_id);
```

### 3.6 `document_digitization_status` — read-model trạng thái tổng hợp

**Vì sao cần bảng riêng thay vì tính động:** ở quy mô ~1 triệu tài liệu
(`digitization-pipeline.md` mục 3), một danh sách tài liệu (UI kho chung)
hiển thị badge trạng thái số hoá cho **mỗi dòng** — nếu tính bằng cách JOIN
+ GROUP BY `digitization_run` mỗi lần render danh sách sẽ rất tốn ở quy mô
này. Đây là **denormalized read-model**, cập nhật (upsert) trong cùng
transaction mỗi khi một `digitization_run` đổi `processing_status` hoặc
`review_state` — cùng kiểu đánh đổi đã dùng ở `warehouse-structure.md` cho
snapshot dung lượng kho.

```sql
CREATE TABLE document_digitization_status (
    document_id       UUID PRIMARY KEY REFERENCES document (id),
    ocr_status            VARCHAR(20) NOT NULL DEFAULT 'not_applicable',
    stt_status            VARCHAR(20) NOT NULL DEFAULT 'not_applicable',
    sed_status             VARCHAR(20) NOT NULL DEFAULT 'not_applicable',
    video_summary_status   VARCHAR(20) NOT NULL DEFAULT 'not_applicable',
    -- Giá trị dùng chung cho 4 cột trên:
    -- not_applicable | queued | processing | failed | pending_review |
    -- in_review | confirmed
    overall_status         VARCHAR(20) NOT NULL DEFAULT 'not_applicable',
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

`overall_status` (mục 6) là tổng hợp của 4 cột trên theo quy tắc ưu tiên cố
định, tính ở tầng ứng dụng khi upsert (không phải cột generated — quy tắc
"tệ nhất thắng" dễ đổi hơn khi để ở code): `failed` > `processing`/`queued` >
`in_review` > `pending_review` > `confirmed` > `not_applicable`.

## 4. Thiết kế chỉ mục vector (trước: Qdrant)

> **Sửa 2026-10-10:** Qdrant đã gỡ khỏi hạ tầng. Vector nằm trên field
> `knn_vector` (1024 chiều, cosine) của **chính document `segment`** trong
> OpenSearch, khoá = `segment_id`, 1 vector/segment (không sub-point) —
> `search-semantic.md` QĐ 1, QĐ 6. "Trường payload" dưới đây hiểu là trường
> của document `segment`. Segment `sed` vào index cho nhánh toàn văn nhưng
> **không** được embed (`search-semantic.md` QĐ 4).

**Không tạo index mới.** Toàn bộ 4 loại đầu ra dùng chung index `segment`
đã chốt ở `ai-search-infra.md` Quyết định 6.

**Thay đổi cần đưa vào `ai-search-infra.md` mục 8 (đây là hợp đồng dùng
chung, không sửa ở file này — xem mục 7 điểm 3):**

| Trường (document `segment`) | Giá trị hiện có | Bổ sung theo thiết kế này |
|---|---|---|
| `content_source` | `ocr \| stt \| native_text \| note \| title` | thêm **`sed`**, **`summary`**; bỏ `native_text` (đã loại ở `digitization-pipeline.md` Quyết định 1 cập nhật 2026-08-16 — chỗ này 2 tài liệu đang lệch nhau, xem mục 7 điểm 3) |
| Trường định vị | `document_id, segment_index, page_no, line_from, line_to, time_start_ms, time_end_ms` | không đổi — `sed`/`summary` để `page_no`/`time_*` = `null`, `position_type = none` (đúng precedent `title`/`note`) |
| `confirm_source` | `manual \| auto` | không đổi — nhưng `sed` không thuộc FR-18 nên **không có** giai đoạn `auto` thật sự (xem mục 7 điểm 2); tạm quy ước `confirm_source = 'manual_unchanged'` cho segment `sed`/`summary` được index (đồng bộ enum 3 giá trị ở `digitization-review.md` Quyết định 2) |

**Điều kiện vào chỉ mục — không đổi cho cả 4 loại:** chỉ segment thuộc
`digitization_run.review_state = 'confirmed'` mới được `index.upsert` (đúng
`digitization-review.md` Quyết định 6, "chỉ bên duy nhất phát `review.
confirmed` mới index"). Với `sed`/`summary` nếu quyết định **không** bắt qua
review (mục 7 điểm 2 — chọn phương án "review nhẹ"), cần một sự kiện tương
đương (ví dụ `sed.promoted`/`summary.generated`) đóng đúng vai trò
`review.confirmed` cho 2 loại này — **chưa quyết định ở đây**, phụ thuộc câu
trả lời mục 7 điểm 2.

## 5. Ánh xạ theo từng loại đầu ra

| Đầu ra | Bảng Postgres ghi kết quả | Vào chỉ mục (OpenSearch, gồm vector) khi nào | Ghi chú |
|---|---|---|---|
| **OCR** | `digitization_run(task='ocr')` + N `content_segment(content_source='ocr')` mỗi block, `bbox_polygon`/`page_no` bắt buộc | `review_state='confirmed'` | Nguồn: `digitization_source(component_type='original', mime_type=image/*\|application/pdf)` |
| **STT** | `digitization_run(task='stt')` + N `content_segment(content_source='stt')` mỗi câu, `time_start_ms`/`time_end_ms` bắt buộc | `review_state='confirmed'` | Nguồn: `digitization_source(component_type='original', mime_type=audio/*)` (audio upload trực tiếp). `component_type='audio'` (tách từ video) chỉ còn ở dữ liệu trước 2026-09-23 — video mới không còn đi `q.stt` (`digitization-pipeline.md` QĐ 10) |
| **Sound Event Detection** | `digitization_run(task='sed')` + N `sound_event` (mọi detection) + 0..N `content_segment(content_source='sed')` (chỉ detection vượt ngưỡng, đã "thăng hạng") | Tuỳ mục 7 điểm 2 — mặc định **feature flag tắt** (`digitization-pipeline.md` Quyết định 6, chưa đổi) | `sound_event.promoted_segment_id` nối 2 bảng; nguồn cùng `digitization_source` với STT |
| **Tóm tắt nội dung video** | `digitization_run(task='video_summary')` + **đúng 1** `content_segment(content_source='summary')`, `source_segment_ids[]` trỏ về các segment STT làm căn cứ | Tuỳ mục 7 điểm 2 — **chưa có FR**, mặc định **feature flag tắt** | Nguồn: chạy sau khi `task='stt'` của cùng document đã `confirmed` (cần toàn bộ transcript làm input cho model tóm tắt) — ràng buộc thứ tự giữa 2 run, DEV cần cụ thể hoá khi viết tech spec |

## 6. Trạng thái tài liệu sau khi số hoá

- **Cấp run** (`digitization_run`): kết hợp `processing_status` (đang xử
  lý/lỗi) và `review_state` (đang chờ duyệt/đã xác nhận) — đây là **nguồn sự
  thật**, đủ chi tiết cho màn hình kiểm duyệt và vận hành.
- **Cấp tài liệu** (`document_digitization_status`): read-model cho danh
  sách/badge, tính lại `overall_status` mỗi khi có run đổi trạng thái (mục
  3.6). Ví dụ luồng cho một tài liệu video đầy đủ 3 task (stt/sed/summary,
  sed+summary đang tắt flag — **chỉ đúng với video trước 2026-09-23**; từ
  QĐ 10 video mới không còn run `stt`): tách nguồn xong (Pha 1) → `stt_status=queued`
  → `processing` → `pending_review` (hết hạn 24h) hoặc `in_review`/
  `confirmed` (có người duyệt) → `overall_status` phản ánh theo quy tắc ưu
  tiên đã nêu.
- **Không** có "trạng thái số hoá" nào sống trong chỉ mục — mất/hỏng chỉ mục
  không mất trạng thái, dựng lại chỉ mục từ Postgres là đủ (đúng nguyên tắc
  mục 1).

## 7. Điểm cần xác nhận trước khi Approve

1. ~~**Hợp nhất `digitization_job` + `digitization_run` thành một bảng?**~~
   — **đã chốt 2026-09-07: giữ 1 bảng** như thiết kế ở mục 3.1 (tên gốc 2
   bảng ở `digitization-pipeline.md` chỉ là nhãn sơ bộ ở phần "Triển khai
   theo pha", chưa phải quyết định ADR chính thức về schema — hợp nhất
   không vi phạm quyết định nào đã chốt).
2. ~~**SED và tóm tắt video có cần đi qua luồng duyệt FR-18?**~~ — **đã chốt
   2026-09-07: CÓ**, bắt buộc qua cùng cơ chế `pending → in_review →
   confirmed` như OCR/STT (nhất quán trải nghiệm, tránh tóm tắt/nhãn âm
   thanh sai lọt ra ngoài chỉ mục chính thức mà không ai kiểm). `review_state`
   mặc định khi tạo run đổi từ `not_applicable` sang `pending` cho **cả 4**
   task (`ocr`, `stt`, `sed`, `video_summary`) — xem `digitization-results-
   store.md` (spec DEV) mục 4 bước 1 đã cập nhật.
   **Hệ quả phạm vi cần lưu ý:** `digitization-review-01-ocr.md`/`-02-stt.md`
   (đã Approved) hiện chỉ định nghĩa màn hình duyệt cho `task ∈ {ocr, stt}`
   — cần **story/spec riêng** cho màn hình duyệt SED/tóm tắt video (nội dung
   khác hẳn: danh sách nhãn âm thanh có timestamp thay vì văn bản/bbox) trước
   khi 2 task này thực sự có người duyệt được; cho tới lúc đó, run SED/
   video_summary sẽ nằm ở `pending` chờ tự-xác-nhận sau `ocr_stt.
   auto_confirm_hours` mà không ai xem qua được (không có UI), giống hệt cơ
   chế "lưới đỡ cuối" đã thiết kế cho OCR/STT.
3. ~~**Đồng bộ lại `content_source` ở `ai-search-infra.md` mục 8**~~ — **đã
   xử lý 2026-09-07**: sửa trực tiếp `ai-search-infra.md` mục 8 (bỏ
   `native_text`, thêm `sed`/`summary`), khớp `digitization-pipeline.md`
   QĐ 1 và `V26` của spec DEV file này.
4. **Ngưỡng "thăng hạng" `sound_event` → `content_segment`**: chưa có con số
   — đề xuất dùng chung ngưỡng confidence cao đã nhắc ở Quyết định 6 của
   `digitization-pipeline.md` ("SED chạy zero-shot với ngưỡng confidence
   cao"), số cụ thể để DEV/SA chốt cùng lúc bật feature flag SED.

## 8. Rủi ro

| Rủi ro | Mức độ | Giảm thiểu |
|---|---|---|
| Hợp nhất job+run (mục 3.1) sai nếu thực ra có nhu cầu giữ **nhiều job/lần thử** cho cùng 1 run (ví dụ muốn xem lịch sử 3 lần retry trước khi thành công) | Trung bình | `attempt_count` + `last_error` trên `digitization_run` đủ cho nhu cầu vận hành cơ bản; nếu cần log chi tiết từng lần thử, thêm bảng `digitization_run_attempt` con (append-only) sau, không cần đổi bảng chính |
| `content_segment` gộp 4 loại nội dung rất khác nhau (OCR/STT/SED/summary) vào 1 bảng với nhiều cột NULL theo loại | Thấp | Đúng tinh thần "một đơn vị index duy nhất" đã chốt ở `ai-search-infra.md`; partition theo `document_id`/thời gian nếu bảng phình (đã cảnh báo ở `digitization-pipeline.md` mục 6) |
| Tóm tắt video phụ thuộc STT đã `confirmed` xong mới chạy được (mục 5) — thêm một ràng buộc thứ tự giữa các run mà `digitization_run` hiện chưa mô hình hoá tường minh (không có cột "phụ thuộc run nào") | Trung bình | DEV bổ sung cột `depends_on_run_id` (nullable) khi viết tech spec nếu cần enforce ở DB; trước mắt có thể xử lý ở tầng điều phối (chỉ enqueue `video_summary` sau khi nhận `review.confirmed` của `stt`) |
| `document_digitization_status` là read-model — lệch với `digitization_run` nếu code quên upsert ở một đường ghi nào đó | Trung bình | Không có API/luồng nào khác được phép sửa `digitization_run` ngoài các service đã liệt kê; thêm job đối soát định kỳ so khớp 2 bảng (cùng mẫu với job đối soát của Pha 1) |
| `ai-search-infra.md` mục 8 cần sửa (mục 7 điểm 3) nhưng đó là tài liệu **Approved**, thuộc slug khác | Thấp | Việc sửa nhỏ (bỏ 1 giá trị enum lỗi thời + thêm 2 giá trị mới), không đổi cấu trúc — nêu rõ để người dùng duyệt riêng, không tự ý sửa file Approved trong tài liệu này |
