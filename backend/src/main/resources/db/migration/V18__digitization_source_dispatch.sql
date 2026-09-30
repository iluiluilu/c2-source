-- digitization-pipeline-01-source-dispatch.md §2. Runs after V17
-- (upload_session_personal_folder).

-- 2.1 Job tách nguồn video bằng FFmpeg (Story 1). Chỉ video mới có dòng ở
-- bảng này; audio/ảnh/PDF đi thẳng digitization_source (mục 2.2).
CREATE TABLE source_separation_job (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    document_id    UUID NOT NULL REFERENCES document (id),
    status         VARCHAR(16) NOT NULL DEFAULT 'queued'
        CHECK (status IN ('queued', 'running', 'succeeded', 'failed')),
    error_message  TEXT,
    attempt_count  INT NOT NULL DEFAULT 0,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
-- Một document chỉ cần tách 1 lần; listener (đồng bộ, xem §4.1) gọi lại
-- (retry ở tầng ứng dụng, hoặc do document.ingested lý thuyết bắn 2 lần)
-- không tạo job trùng.
CREATE UNIQUE INDEX UQ_source_separation_job_document
    ON source_separation_job (document_id);
CREATE INDEX IDX_source_separation_job_pollable
    ON source_separation_job (status, updated_at);

-- 2.2 Thành phần sẵn sàng để AI worker xử lý (Story 1 + Story 2).
CREATE TABLE digitization_source (
    -- video: sinh mới; audio/ảnh/PDF: = document_id (Story 2 AC2 - "sourceId
    -- chính là fileId" khi không phải video).
    id                      UUID PRIMARY KEY,
    document_id             UUID NOT NULL REFERENCES document (id),
    component_type          VARCHAR(16) NOT NULL
        CHECK (component_type IN ('video', 'audio', 'original')),
    file_path               VARCHAR(1024) NOT NULL, -- "<bucket>/<objectKey>", xem §4.4
    mime_type                VARCHAR(255) NOT NULL, -- processFileType khi dispatch
    -- component_type='video' luôn dispatchable=false (Story 1 AC3 - không tự
    -- động đẩy vào hàng đợi ở Pha 1); các loại còn lại luôn true.
    dispatchable             BOOLEAN NOT NULL DEFAULT true,
    dispatch_status          VARCHAR(16) NOT NULL DEFAULT 'pending'
        CHECK (dispatch_status IN ('pending', 'published', 'failed')),
    dispatch_attempt_count   INT NOT NULL DEFAULT 0,
    dispatched_at            TIMESTAMPTZ,
    created_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IDX_digitization_source_document ON digitization_source (document_id);
-- Job đối soát (mục 4.5) + retry dispatch (mục 4.4) đều quét theo cặp này.
CREATE INDEX IDX_digitization_source_dispatch_pollable
    ON digitization_source (dispatchable, dispatch_status, updated_at);
