# Tech Spec: digitization-results-store

> File: `docs/03-dev/specs/digitization-results-store.md` — Owner: DEV
> Nguồn story: `docs/02-ba/stories/digitization-results-store.md`
> Status: Approved

## 1. Tóm tắt kỹ thuật

Hiện thực hoá schema Postgres đã thiết kế ở
`docs/01-sa/architecture/digitization-results-store.md` mục 3 thành 2 file
migration Flyway (`V25`, `V26`, tiếp nối `V24__document_sharing_notifications.sql`
— migration mới nhất hiện có trong repo), cộng với entity/repository JPA làm
"API nội bộ" cho `digitization-pipeline` (worker ghi kết quả) và
`digitization-review` (UI duyệt đọc/ghi `review_state`). Đây là slug hạ tầng
lưu trữ, **không có REST endpoint riêng** — không có controller mới trong
phạm vi spec này.

4 điểm SA mục 7 đã được người dùng chốt 2026-09-07 (xem `docs/01-sa/
architecture/digitization-results-store.md` mục 7): giữ **1 bảng**
`digitization_job`+`digitization_run` hợp nhất; SED/`video_summary`
**bắt buộc qua FR-18** (`review_state='pending'` khi tạo, giống OCR/STT —
đã sửa lại so với bản Draft trước, xem mục 4 bước 1); enum `content_source`
mới (`ocr, stt, sed, summary`, bỏ `native_text`, đã đồng bộ vào
`ai-search-infra.md`); ngưỡng thăng hạng `sound_event` để ở cấu hình ứng
dụng (chưa có giá trị chính thức, không đổi).

Hai điểm cụ thể hoá thêm so với SA (do SA mục 7/8 để ngỏ, cần cho việc viết
DDL/entity chạy được):

- `digitization_run.source_id` FK tới `digitization_source(id)` — bảng này
  đã tồn tại thật ở `V18__digitization_source_dispatch.sql` với `id UUID`
  không tự sinh (`gen_random_uuid()` do caller set), khớp đúng kiểu FK.
- `content_segment.source_segment_ids` đổi từ `UUID[]` (SA mục 3.3) sang
  `JSONB` (mảng chuỗi UUID) — codebase hiện dùng Hibernate 6
  `@JdbcTypeCode(SqlTypes.JSON)` cho các cột JSON (xem `OutboxEvent.java`),
  chưa có tiền lệ map kiểu mảng Postgres (`UUID[]`) nào trong dự án; dùng
  JSONB để nhất quán, tránh phải thêm dependency mapping mảng riêng. Hành vi
  không đổi so với thiết kế SA.

## 2. Data model

### 2.1 `V25__digitization_run_and_batch.sql`

Tạo `digitization_batch` trước `digitization_run` (khác thứ tự trình bày ở
SA mục 3.1/3.2) vì `digitization_run.batch_id` FK tới `digitization_batch`,
Postgres yêu cầu bảng đích tồn tại trước khi tạo FK.

```sql
-- digitization-results-store.md §3.2. Runs after V24
-- (document_sharing_notifications). Batch số hoá (khác upload_batch của
-- data-ingestion.md) - tạo trước digitization_run vì run.batch_id FK tới đây.
CREATE TABLE digitization_batch (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    label            VARCHAR(255),
    total_count      INT NOT NULL,
    succeeded_count  INT NOT NULL DEFAULT 0,
    failed_count     INT NOT NULL DEFAULT 0,
    created_by_sub   VARCHAR(255) NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
-- pending_count không lưu cột riêng: total_count - succeeded_count - failed_count.

-- digitization-results-store.md §3.1 - hợp nhất digitization_job (vòng đời
-- hàng đợi/GPU) + digitization_run (vòng đời kết quả + duyệt) thành 1 bảng
-- (giả định story mục "Giả định kế thừa từ SA" #1 - CHƯA Approved chính
-- thức, xem mục 7 "Rủi ro kỹ thuật" của spec này).
CREATE TABLE digitization_run (
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    document_id            UUID NOT NULL REFERENCES document (id),
    source_id              UUID NOT NULL REFERENCES digitization_source (id),
    task                   VARCHAR(16) NOT NULL
        CHECK (task IN ('ocr', 'stt', 'sed', 'video_summary')),
    batch_id               UUID REFERENCES digitization_batch (id),

    -- Vòng đời hàng đợi/GPU
    processing_status      VARCHAR(20) NOT NULL DEFAULT 'queued'
        CHECK (processing_status IN
            ('queued', 'running', 'succeeded', 'failed_retryable',
             'failed_permanent', 'cancelled')),
    attempt_count          INT NOT NULL DEFAULT 0,
    lease_expires_at       TIMESTAMPTZ,
    worker_id              VARCHAR(255),
    last_error             TEXT,

    -- Kết quả & model
    model_name             VARCHAR(255),
    model_version          VARCHAR(64),
    metrics                JSONB,
    started_at             TIMESTAMPTZ,
    completed_at           TIMESTAMPTZ,
    raw_output_path        VARCHAR(1024), -- derived/<document_id>/<run_id>.json

    -- Cờ chất lượng
    input_quality_flag     VARCHAR(16)
        CHECK (input_quality_flag IN ('ok', 'poor_scan', 'low_audio_snr')),
    output_quality_flag    VARCHAR(16) CHECK (output_quality_flag IN ('ok', 'poor')),
    low_confidence_count   INT NOT NULL DEFAULT 0,
    segment_count          INT NOT NULL DEFAULT 0,
    auto_confirm_blocked   BOOLEAN NOT NULL DEFAULT false,

    -- Duyệt - cấp run. Cả 4 task (ocr/stt/sed/video_summary) đều qua FR-18
    -- (chốt 2026-09-07, SA mục 7 điểm 2) - service luôn set 'pending' tường
    -- minh khi tạo run (mục 4 bước 1); DEFAULT 'not_applicable' ở đây chỉ là
    -- giá trị an toàn cấp cột nếu có đường ghi nào quên set, không phải giá
    -- trị thực tế cho bất kỳ task nào trong 4 task đang hỗ trợ.
    review_state           VARCHAR(16) NOT NULL DEFAULT 'not_applicable'
        CHECK (review_state IN
            ('not_applicable', 'pending', 'in_review', 'confirmed',
             'superseded', 'void')),
    review_deadline_at     TIMESTAMPTZ,
    confirm_source         VARCHAR(20)
        CHECK (confirm_source IN ('auto', 'manual_unchanged', 'manual_edited')),
    confirmed_by           VARCHAR(255),
    confirmed_at            TIMESTAMPTZ,
    first_viewed_at         TIMESTAMPTZ,
    first_viewed_by         VARCHAR(255),
    edited_segment_count    INT NOT NULL DEFAULT 0,
    first_edit_at           TIMESTAMPTZ,

    -- digitization-results-store.md §8 rủi ro 3 - liên kết run
    -- 'video_summary' phụ thuộc run 'stt' cùng document đã confirmed. Nullable:
    -- chỉ set cho task='video_summary'.
    depends_on_run_id       UUID REFERENCES digitization_run (id),

    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- "chỉ 1 run active mỗi (document_id, task)" - active = chưa superseded/void.
CREATE UNIQUE INDEX UQ_digitization_run_active
    ON digitization_run (document_id, task)
    WHERE review_state NOT IN ('superseded', 'void');

-- Poll hàng đợi GPU (worker lấy job) + job đối soát lease quá hạn.
CREATE INDEX IDX_digitization_run_pollable
    ON digitization_run (processing_status, lease_expires_at);
-- Hàng chờ kiểm duyệt (digitization-review.md) + auto-confirm sweeper.
CREATE INDEX IDX_digitization_run_review_pollable
    ON digitization_run (review_state, review_deadline_at);
CREATE INDEX IDX_digitization_run_document ON digitization_run (document_id);
CREATE INDEX IDX_digitization_run_batch ON digitization_run (batch_id) WHERE batch_id IS NOT NULL;
```

Ghi chú khác biệt so với SA mục 3.1: thêm cột `depends_on_run_id` ngay từ
`V25` (SA mục 8 rủi ro 3 đề xuất DEV bổ sung cột này "khi viết tech spec nếu
cần enforce ở DB" — thêm luôn ở đây để tránh một migration `ALTER TABLE` phụ
sau này; cột nullable, không ảnh hưởng task khác).

### 2.2 `V26__digitization_content_segments.sql`

```sql
-- digitization-results-store.md §3.3-3.6. Runs after V25.

-- §3.3 - đơn vị nội dung thống nhất OCR/STT/SED/tóm tắt.
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
    bbox_polygon              JSONB, -- [[x,y],...] tỉ lệ 0-1 trên trang, không phải pixel (chốt 2026-09-07)
    reading_order             INT,
    line_from                 INT,
    line_to                   INT,

    -- STT / SED-promoted (content_source='stt'|'sed'); NULL cho 'summary'
    time_start_ms            INT,
    time_end_ms               INT,
    speaker_label              VARCHAR(64), -- diarization, feature flag tắt mặc định

    -- 'summary' (content_source='summary') - segment STT/OCR/SED làm căn cứ
    -- dẫn nguồn. JSONB (mảng chuỗi UUID) thay vì UUID[] - xem mục 1.
    source_segment_ids        JSONB,

    -- Bản AI sinh - BẤT BIẾN sau khi run kết thúc.
    ai_text                   TEXT NOT NULL,
    ai_time_start_ms          INT,
    ai_time_end_ms            INT,

    -- Bản hiện hành - cập nhật cùng transaction với segment_revision.
    current_text              TEXT NOT NULL,
    current_time_start_ms     INT,
    current_time_end_ms       INT,
    current_revision_no       INT NOT NULL DEFAULT 0,
    is_edited                 BOOLEAN NOT NULL DEFAULT false,

    confidence                 NUMERIC(4, 3), -- 0..1; NULL cho 'summary'

    created_at                 TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                 TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- Bất biến mốc thời gian (story US3 AC-1/AC-2) - kiểm cả ai_* lẫn
    -- current_* vì cả 2 đều được đọc bởi các luồng khác nhau.
    CONSTRAINT CK_content_segment_ai_time
        CHECK (ai_time_start_ms IS NULL OR ai_time_end_ms IS NULL
               OR ai_time_start_ms < ai_time_end_ms),
    CONSTRAINT CK_content_segment_current_time
        CHECK (current_time_start_ms IS NULL OR current_time_end_ms IS NULL
               OR current_time_start_ms < current_time_end_ms)
);

CREATE INDEX IDX_content_segment_run ON content_segment (run_id, segment_index);
CREATE INDEX IDX_content_segment_document ON content_segment (document_id);
-- Job đối soát chất lượng quét theo run + confidence thấp.
CREATE INDEX IDX_content_segment_low_confidence
    ON content_segment (run_id) WHERE confidence < 0.60;

-- §3.4 - lịch sử sửa, append-only.
CREATE TABLE segment_revision (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    segment_id      UUID NOT NULL REFERENCES content_segment (id),
    revision_no     INT NOT NULL,
    text            TEXT,
    time_start_ms   INT,
    time_end_ms     INT,
    deleted         BOOLEAN NOT NULL DEFAULT false,
    edited_by       VARCHAR(255) NOT NULL,
    edited_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    reason          TEXT,
    -- Cùng bất biến mốc thời gian với content_segment (story US3 phạm vi áp
    -- dụng cho "content_segment hoặc sound_event" - segment_revision là log
    -- phụ trợ của content_segment nên áp cùng ràng buộc để không lọt dữ liệu
    -- sai qua đường revision).
    CONSTRAINT CK_segment_revision_time
        CHECK (time_start_ms IS NULL OR time_end_ms IS NULL
               OR time_start_ms < time_end_ms)
);
CREATE UNIQUE INDEX UQ_segment_revision ON segment_revision (segment_id, revision_no);

-- §3.5 - detection thô của SED, mọi detection đều được ghi.
CREATE TABLE sound_event (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    run_id               UUID NOT NULL REFERENCES digitization_run (id), -- task='sed'
    document_id          UUID NOT NULL REFERENCES document (id),
    label                VARCHAR(100) NOT NULL, -- nhãn AudioSet gốc (tiếng Anh)
    label_vi             VARCHAR(200),          -- mô tả tiếng Việt để hiển thị/tìm kiếm
    confidence           NUMERIC(4, 3) NOT NULL,
    time_start_ms        INT NOT NULL,
    time_end_ms           INT NOT NULL,
    promoted_segment_id   UUID REFERENCES content_segment (id), -- NULL nếu dưới ngưỡng
    verified              BOOLEAN NOT NULL DEFAULT false, -- cán bộ xác minh thủ công
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT CK_sound_event_time CHECK (time_start_ms < time_end_ms)
);
CREATE INDEX IDX_sound_event_run ON sound_event (run_id);
CREATE UNIQUE INDEX UQ_sound_event_promoted_segment
    ON sound_event (promoted_segment_id) WHERE promoted_segment_id IS NOT NULL;

-- §3.6 - read-model trạng thái tổng hợp cấp tài liệu. Không có DEFAULT
-- gen_random_uuid() vì PK = document_id (1-1 với document, tạo lazy khi có
-- run đầu tiên - xem mục 4 "Luồng xử lý chính").
CREATE TABLE document_digitization_status (
    document_id            UUID PRIMARY KEY REFERENCES document (id),
    ocr_status              VARCHAR(20) NOT NULL DEFAULT 'not_applicable',
    stt_status               VARCHAR(20) NOT NULL DEFAULT 'not_applicable',
    sed_status                VARCHAR(20) NOT NULL DEFAULT 'not_applicable',
    video_summary_status      VARCHAR(20) NOT NULL DEFAULT 'not_applicable',
    -- Giá trị dùng chung cho 4 cột trên: not_applicable | queued | processing
    -- | failed | pending_review | in_review | confirmed
    overall_status             VARCHAR(20) NOT NULL DEFAULT 'not_applicable',
    updated_at                  TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

`UQ_sound_event_promoted_segment` là bổ sung so với SA mục 3.5 (không có ở
bản gốc): thực thi bất biến "1 `content_segment` chỉ được thăng hạng từ đúng
1 `sound_event`" (1-1) ở tầng DB thay vì chỉ dựa vào code không tạo trùng.

## 3. API contract

Không có REST endpoint mới. "API nội bộ" là các Java interface dưới package
mới `com.aismartstore.backend.digitization.results` (tách khỏi package
`com.aismartstore.backend.digitization` hiện có của Pha 1 — source dispatch —
để không lẫn 2 phạm vi trách nhiệm), dùng bởi `digitization-pipeline` (worker
ghi kết quả) và `digitization-review` (UI duyệt đọc/ghi `review_state`).

**Entity JPA** (1 file/bảng, theo đúng tên cột migration ở mục 2):
`DigitizationBatch`, `DigitizationRun`, `ContentSegment`, `SegmentRevision`,
`SoundEvent`, `DocumentDigitizationStatus`.

**Repository (Spring Data JPA)**:

| Repository | Method trọng tâm |
|---|---|
| `DigitizationRunRepository` | `findPollableForProcessing(...)` (theo `processing_status`, `lease_expires_at` — dùng `IDX_digitization_run_pollable`); `findPollableForReview(...)` (theo `review_state`, `review_deadline_at` — dùng `IDX_digitization_run_review_pollable`); `findActiveByDocumentAndTask(documentId, task)` (dùng `UQ_digitization_run_active`) |
| `DigitizationBatchRepository` | CRUD chuẩn + `incrementSucceeded`/`incrementFailed` (native update, tránh lost-update khi nhiều worker cùng cập nhật 1 batch) |
| `ContentSegmentRepository` | `findByRunIdOrderBySegmentIndex`; `findByDocumentIdAndContentSource` |
| `SegmentRevisionRepository` | `findLatestBySegmentId`; `save` (append-only, không có method update/delete expose ra ngoài) |
| `SoundEventRepository` | `findByRunId`; `findUnpromotedAboveThreshold(runId, threshold)` (dùng cho job hiệu chỉnh ngưỡng lại sau này) |
| `DocumentDigitizationStatusRepository` | `findById(documentId)`; upsert thực hiện qua service, không expose trực tiếp `save` tuỳ tiện (xem mục 4) |

**Service (điểm ghi duy nhất, đúng AC-4 User Story 4 — "không luồng nào khác
được ghi trực tiếp `document_digitization_status`")**:

```java
package com.aismartstore.backend.digitization.results;

public interface DigitizationRunLifecycleService {

    // Tạo run mới cho (documentId, task). Ném IllegalStateException nếu đã
    // có run active cùng cặp (document_id, task) — bọc quanh
    // UQ_digitization_run_active để trả lỗi nghiệp vụ rõ ràng thay vì lộ
    // DataIntegrityViolationException lên caller.
    DigitizationRun createRun(UUID documentId, UUID sourceId, String task,
                               UUID batchId, UUID dependsOnRunId);

    // Đổi processing_status (queued->running->succeeded/failed_*/cancelled).
    // Cùng transaction: upsert document_digitization_status tương ứng.
    DigitizationRun transitionProcessingStatus(UUID runId, String newStatus,
                                                String lastError);

    // Đổi review_state (pending->in_review->confirmed, hoặc ->superseded/void).
    // Cùng transaction: upsert document_digitization_status tương ứng.
    DigitizationRun transitionReviewState(UUID runId, String newReviewState,
                                           String confirmSource, String actorSub);

    // Ghi N content_segment cho 1 run (sau khi processing_status='succeeded'),
    // set ai_text=current_text, ai_time_*=current_time_* (US2 AC-1).
    List<ContentSegment> writeSegments(UUID runId, List<NewSegment> segments);

    // Ghi 1 segment_revision mới + cập nhật current_*/is_edited/
    // current_revision_no trên content_segment, cùng 1 transaction (US2 AC-2/AC-3).
    ContentSegment reviseSegment(UUID segmentId, String newText,
                                  Integer newTimeStartMs, Integer newTimeEndMs,
                                  String editedBy, String reason);

    // Ghi N sound_event (mọi detection, US5 AC-1) + thăng hạng detection vượt
    // ngưỡng thành content_segment (US5 AC-2/AC-3). Ngưỡng đọc từ
    // DigitizationProperties (mục 4), không hardcode.
    List<SoundEvent> writeSoundEvents(UUID runId, List<NewSoundEvent> detections);
}
```

## 4. Luồng xử lý chính

1. **Tạo run**: `digitization-pipeline` gọi
   `DigitizationRunLifecycleService.createRun(...)` sau khi
   `digitization_source` sẵn sàng — 1 INSERT `digitization_run`
   (`processing_status='queued'`, `review_state='pending'` khi tạo — **cho
   cả 4 task** `ocr`/`stt`/`sed`/`video_summary`, chốt 2026-09-07: cả 4 đều
   đi qua FR-18, không còn nhánh `'not_applicable'` theo task; service luôn
   set tường minh, không dựa vào DEFAULT cột) trong cùng transaction với upsert
   `document_digitization_status` (`INSERT ... ON CONFLICT (document_id) DO
   UPDATE` — trả lời AC-3 User Story 4: dòng `document_digitization_status`
   được tạo **lazy** ở lần `createRun` đầu tiên của tài liệu, không tạo sẵn
   khi `document` được tạo, vì phần lớn tài liệu tải lên đợi hàng giờ/ngày
   mới tới lượt số hoá).
2. **Cập nhật vòng đời xử lý**: worker gọi `transitionProcessingStatus`; mỗi
   lần đổi `processing_status`, cùng transaction tính lại cột task tương ứng
   của `document_digitization_status` (`queued`/`running` → `queued`/
   `processing`; `failed_retryable`/`failed_permanent` → `failed`;
   `succeeded` → giữ nguyên, chờ bước 3/4 set theo `review_state`) rồi tính
   lại `overall_status` theo thứ tự ưu tiên đã chốt (`failed` >
   `processing`/`queued` > `in_review` > `pending_review` > `confirmed` >
   `not_applicable`).
3. **Ghi segment**: khi `processing_status` chuyển `succeeded`, worker gọi
   `writeSegments` trong cùng transaction với `transitionProcessingStatus`
   (một lệnh gọi service duy nhất bọc cả 2 thao tác) — mỗi `NewSegment` được
   INSERT với `ai_text = current_text`, `ai_time_start_ms =
   current_time_start_ms`, `ai_time_end_ms = current_time_end_ms` (US2 AC-1).
4. **Sửa segment (từ `digitization-review`)**: `reviseSegment` INSERT 1 dòng
   `segment_revision` (`revision_no = current_revision_no + 1`), rồi UPDATE
   `content_segment` set `current_text`/`current_time_*` theo bản sửa,
   `is_edited = true`, `current_revision_no += 1` — cùng 1 transaction, không
   đụng tới `ai_text`/`ai_time_*` (US2 AC-2/AC-3).
5. **Duyệt run**: `transitionReviewState` cập nhật `review_state` (+
   `confirmed_by`/`confirmed_at`/`confirm_source` khi chuyển `confirmed`),
   cùng transaction upsert `document_digitization_status` (US4 AC-1) —
   `pending`/`in_review` → `pending_review`/`in_review`; `confirmed` →
   `confirmed`; `superseded`/`void` → không đổi cột task (run cũ bị thay thế,
   `overall_status` phản ánh theo run active mới, không theo run vừa
   superseded).
6. **SED**: `writeSoundEvents` INSERT toàn bộ N detection vào `sound_event`
   (US5 AC-1), sau đó với mỗi detection có `confidence >= ngưỡng` (đọc từ cấu
   hình `digitization.sed.promotion-threshold`, xem mục 7 — **chưa có giá trị
   chính thức**, đặt placeholder `0.85` trong `application.yml`/
   `DigitizationProperties`, phải sửa trước khi bật flag SED thật) thì INSERT
   thêm 1 `content_segment(content_source='sed')` và UPDATE
   `sound_event.promoted_segment_id` — cùng transaction với bước INSERT
   `sound_event` ban đầu.
7. **Tóm tắt video**: điều phối (ngoài phạm vi service này, thuộc
   `digitization-pipeline`) chỉ `createRun(task='video_summary',
   dependsOnRunId=<run stt đã confirmed>)` sau khi nhận sự kiện run `stt`
   cùng `document_id` chuyển `review_state='confirmed'` — enforce mềm ở tầng
   ứng dụng; cột `depends_on_run_id` chỉ dùng để truy vết/hiển thị, không có
   trigger DB chặn tạo run "trái phép" (giữ đơn giản, rủi ro thấp vì chỉ có 1
   caller nội bộ tạo run).
8. **Đối soát `document_digitization_status`** (US4 AC-5): job định kỳ
   (tương tự `DigitizationDispatchRetryJob` đã có ở Pha 1) so khớp mỗi
   `document_digitization_status` với `MAX`/tổng hợp thực tế từ
   `digitization_run` active cùng `document_id`, log lệch (không tự sửa âm
   thầm) để điều tra nguyên nhân quên upsert.

**Quyết định AC-5 User Story 3** (ràng buộc `time_end_ms <= duration_ms`):
`digitization_source` (bảng thật, `V18`) **không có cột `duration_ms`** —
không tồn tại dữ liệu để so sánh chéo bảng ở thời điểm này. Spec này **không**
thêm trigger hay cột `duration_ms` mới (ngoài phạm vi story, cần một
quyết định SA riêng về việc thêm cột đó vào `digitization_source`). Việc kiểm
tra `time_end_ms <= duration_ms` — nếu cần — nên làm ở tầng ứng dụng
(validate trong `writeSegments`/`writeSoundEvents` khi có `duration_ms`),
không phải trigger DB, để tránh phụ thuộc một cột chưa tồn tại.

## 5. Test plan

**Migration / constraint (test tại tầng DB, dùng Testcontainers Postgres như
các migration trước)**:

- `V25`/`V26` chạy sạch trên DB rỗng đã có `V1`..`V24` (bao gồm `document`,
  `digitization_source`).
- CHECK constraint thời gian: INSERT `content_segment` với
  `ai_time_start_ms >= ai_time_end_ms` → bị từ chối (US3 AC-1); tương tự cho
  `current_time_*` (AC-2); INSERT `sound_event` với `time_start_ms >=
  time_end_ms` → bị từ chối (AC-3); INSERT với `time_start_ms`/`time_end_ms`
  đều NULL trên `content_segment` (mô phỏng OCR) → thành công (AC-4).
- `UQ_digitization_run_active`: tạo run 1 (`review_state='pending'`) cho
  `(document_id, task)`, tạo run 2 cùng cặp cùng `review_state` khác
  `superseded/void` → vi phạm unique index; chuyển run 1 sang `superseded`
  rồi tạo run 2 → thành công; xác nhận cả run 1 (đã `superseded`) và run 2
  vẫn còn trong bảng (US1 AC-2/AC-3 — không bị xoá).
- `UQ_sound_event_promoted_segment`: 2 `sound_event` cùng
  `promoted_segment_id` → vi phạm.

**Integration test (service, Spring Boot Test + Testcontainers)**:

- `createRun` cho cả 4 `task` (`ocr`/`stt`/`sed`/`video_summary`) →
  `review_state='pending'` (không còn nhánh `'not_applicable'` theo task,
  chốt 2026-09-07).
- `writeSegments` sau `transitionProcessingStatus(succeeded)`: mọi segment có
  `ai_text = current_text` và `ai_time_* = current_time_*` ngay sau khi ghi
  (US2 AC-1).
- `reviseSegment` gọi 2 lần liên tiếp trên cùng segment: `current_text`
  đổi theo lần sửa mới nhất, `ai_text` không đổi qua cả 2 lần,
  `current_revision_no = 2`, `is_edited = true`, có đúng 2 dòng
  `segment_revision` (US2 AC-2/AC-3/AC-4) — thử UPDATE trực tiếp 1 dòng
  `segment_revision` bằng SQL thô trong test để xác nhận không có code path
  ứng dụng nào làm việc này (kiểm bằng review code/Repository không expose
  method update, không phải kiểm bằng DB permission).
- `transitionProcessingStatus`/`transitionReviewState`: sau mỗi lần gọi,
  `document_digitization_status` được cập nhật (query lại trong cùng
  transaction test, dùng `TestEntityManager.flush()` — không qua transaction
  riêng, đúng US4 AC-1 "cùng transaction").
- `overall_status`: dựng 4 cột task ở nhiều tổ hợp giá trị, xác nhận
  `overall_status` tính đúng theo thứ tự ưu tiên (US4 AC-2) — bảng test
  tham số hoá (`@ParameterizedTest`) liệt kê các tổ hợp biên (ví dụ 1 task
  `failed` + 3 task `confirmed` → `overall_status='failed'`).
- `writeSoundEvents`: N detection thô (mix trên/dưới ngưỡng cấu hình) →
  đúng N dòng `sound_event`; số dòng có `promoted_segment_id != NULL` bằng
  đúng số detection `confidence >= threshold`; mỗi dòng thăng hạng có đúng 1
  `content_segment(content_source='sed')` tương ứng (US5 AC-1/AC-2/AC-3);
  `verified` mặc định `false`, độc lập với việc có `promoted_segment_id` hay
  không (AC-5).
- Case lỗi: `createRun` khi đã có run active cùng `(document_id, task)` →
  ném `IllegalStateException` có thông điệp rõ ràng (không lộ
  `DataIntegrityViolationException`).

## 6. Task breakdown

- [x] Task 1: Viết `V25__digitization_run_and_batch.sql`, chạy migration
      trên DB dev, xác nhận không lỗi thứ tự FK. Xác nhận thủ công bằng
      `psql` trực tiếp trên Postgres 16 thật (V1..V24 rồi V25) - không lỗi
      thứ tự FK (xem ghi chú Testcontainers ở cuối mục này).
- [x] Task 2: Viết `V26__digitization_content_segments.sql`. Xác nhận thủ
      công tương tự Task 1 (V26 sau V25).
- [x] Task 3: Tạo package `com.aismartstore.backend.digitization.results`
      với 6 entity JPA (`DigitizationBatch`, `DigitizationRun`,
      `ContentSegment`, `SegmentRevision`, `SoundEvent`,
      `DocumentDigitizationStatus`), map đúng tên cột/kiểu dữ liệu đã tạo ở
      Task 1/2 (chạy `ddl-auto=validate` để tự kiểm khớp).
- [x] Task 4: Tạo 6 Repository Spring Data JPA theo bảng mục 3, gồm các
      query method tuỳ biến (native query cho `IDX_digitization_run_pollable`
      / `IDX_digitization_run_review_pollable`, `incrementSucceeded`/
      `incrementFailed` trên batch).
- [x] Task 5: Implement `DigitizationRunLifecycleService`
      (`DigitizationRunLifecycleServiceImpl`) — `createRun`,
      `transitionProcessingStatus`, `transitionReviewState`,
      `writeSegments`, `reviseSegment`, `writeSoundEvents`, cùng logic upsert
      `document_digitization_status`/tính `overall_status` nội bộ (private
      method, không expose repository ghi trực tiếp bảng này ra ngoài
      service).
- [x] Task 6: Thêm `digitization.sed.promotion-threshold` (double, default
      `0.85`, đánh dấu TODO trong `DigitizationProperties`/`application.yml`
      — "giá trị tạm, cần chốt cùng lúc bật feature flag SED").
- [x] Task 7: Job đối soát định kỳ `DocumentDigitizationStatusReconciliationJob`
      (theo mẫu `DigitizationDispatchRetryJob` hiện có) — log lệch, không tự
      sửa.
- [x] Task 8: Viết test theo mục 5 (migration/constraint test +
      integration test service), chạy trên Testcontainers Postgres.
      **Lưu ý môi trường (không thuộc phạm vi spec này)**: trong sandbox
      hiện tại, mọi test `@SpringBootTest` + Testcontainers trong TOÀN BỘ
      repo (kể cả các IT có sẵn không liên quan, ví dụ
      `BusinessDataScopeIT`) đều fail khi khởi động context vì
      `V21__audit_log_seal_and_partition.sql` gọi
      `setval('audit_log_id_seq', COALESCE((SELECT MAX(id) FROM audit_log),
      0), true)` - `setval(..., 0, true)` bị Postgres từ chối
      ("value 0 is out of bounds") khi `audit_log` rỗng, đúng trạng thái một
      DB Testcontainers mới tinh luôn có. Đây là bug tiền nhiệm trong V21,
      không phải do spec này, nhưng chặn việc chạy
      `DigitizationSchemaConstraintsIT`/`DigitizationRunLifecycleServiceIT`
      (đã viết, compile sạch) bằng `mvn test` ở đây. Đã xác minh thay thế:
      áp `V1`..`V26` bằng `psql` thẳng vào Postgres 16 thật (bản vá tạm chỉ
      trong bản sao ở `/tmp`, không đụng file trong repo) - V25/V26 chạy
      sạch, và test thủ công câu lệnh `INSERT ... ON CONFLICT DO UPDATE`
      của `upsertTaskStatus` cho ra đúng `overall_status`. Đề xuất: sửa
      `V21` (đổi `is_called` theo `(SELECT MAX(id) FROM audit_log) IS NOT
      NULL` thay vì hardcode `true`) ở một task/spec riêng - KHÔNG tự sửa ở
      đây vì đó là migration đã duyệt thuộc phạm vi khác
      (audit-logging.md), sửa file migration đã áp dụng có rủi ro lệch
      checksum Flyway ở môi trường đã chạy.
- [x] Task 9: ~~Cập nhật `ai-search-infra.md` mục 8~~ — đã sửa trực tiếp
      2026-09-07 (bỏ `native_text`, thêm `sed`/`summary`).

## 7. Rủi ro kỹ thuật

Bốn điểm SA mục 7 (`docs/01-sa/architecture/digitization-results-store.md`)
**đã được người dùng chốt 2026-09-07** — cập nhật spec theo đúng quyết định:

1. ~~Hợp nhất `digitization_job` + `digitization_run`?~~ — **giữ 1 bảng**
   như spec đã viết (mục 2.1), không đổi gì.
2. ~~SED/`video_summary` có qua FR-18 không?~~ — **có, bắt buộc**. Đã sửa
   mục 4 bước 1: `review_state='pending'` khi tạo cho cả 4 task, không còn
   nhánh `'not_applicable'`. **Hệ quả tích cực cho câu hỏi "sự kiện xác nhận
   nào bắn ra để `search-semantic` biết mà index SED/summary"** (trước đây
   chưa thiết kế): nay dùng **đúng cơ chế `transitionReviewState` →
   `review.confirmed`/`review.reconfirmed`** sẵn có cho OCR/STT, không cần
   cơ chế riêng — mục 4 bước 6 (`writeSoundEvents`) chỉ tạo `content_segment`
   ở trạng thái chờ duyệt như bình thường, **không** tự vào chỉ mục ngay khi
   thăng hạng. Lưu ý: UI duyệt SED/tóm tắt video chưa có story riêng (xem SA
   mục 7 điểm 2) — cho tới khi có, run này chỉ ra khỏi `pending` qua auto-
   confirm 24h, không ai duyệt thủ công được.
3. ~~Enum `content_source` lệch `ai-search-infra.md`~~ — **đã sửa
   `ai-search-infra.md` mục 8 trực tiếp 2026-09-07** (bỏ `native_text`, thêm
   `sed`/`summary`), khớp enum `V26`. Không còn rủi ro.
4. **Ngưỡng thăng hạng `sound_event`** — spec này để ngưỡng ở cấu hình ứng
   dụng (`digitization.sed.promotion-threshold`, mặc định `0.85`, Task 6),
   **không** hardcode trong DDL/CHECK constraint, để có thể chỉnh mà không
   cần migration khi có con số chính thức. Rủi ro: nếu ngưỡng đổi sau khi đã
   có dữ liệu SED thật, các detection đã thăng hạng/chưa thăng hạng theo
   ngưỡng cũ cần một job hiệu chỉnh lại thủ công (chưa thiết kế job này,
   ngoài phạm vi — chỉ `SoundEventRepository.findUnpromotedAboveThreshold`
   mục 3 là điểm khởi đầu cho job đó sau này).

Rủi ro kỹ thuật khác (không phụ thuộc 4 điểm trên):

- `content_segment.source_segment_ids` đổi từ `UUID[]` (SA) sang `JSONB`
  (mục 1) — nếu về sau cần query hiệu quả "segment nào được tóm tắt tham
  chiếu tới", `JSONB` không có index GIN sẵn trong `V26`; thêm
  `CREATE INDEX ... USING GIN (source_segment_ids)` sau nếu cần, không phải
  thay đổi kiểu cột.
- `depends_on_run_id` (mục 2.1) chỉ enforce mềm ở tầng ứng dụng (mục 4 bước
  7), không có trigger DB chặn tạo run `video_summary` khi run `stt` chưa
  `confirmed` — chấp nhận được vì chỉ có 1 caller nội bộ, nhưng nếu sau này
  có thêm caller (ví dụ script vận hành tay), cần thêm kiểm tra rõ ràng hơn.
</content>
