-- digitization-results-store.md §3.3-3.6. Runs after V25.

-- §3.3 - đơn vị nội dung thống nhất OCR/STT/SED/tóm tắt.
CREATE TABLE content_segment (
    id                       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    run_id                   UUID NOT NULL REFERENCES digitization_run (id),
    document_id              UUID NOT NULL REFERENCES document (id), -- denormalized, khớp payload Qdrant
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

    -- Bất biến mốc thời gian (story US3 AC1/AC2) - kiểm cả ai_* lẫn
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
