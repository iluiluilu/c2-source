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
