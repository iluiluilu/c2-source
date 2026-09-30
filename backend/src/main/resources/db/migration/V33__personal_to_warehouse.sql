-- personal-to-warehouse.md §2: provenance columns on document + per-file result table.
ALTER TABLE document
    ADD COLUMN source_kind VARCHAR(16) NULL CHECK (source_kind IN ('personal')),
    ADD COLUMN source_personal_file_id UUID NULL;   -- KHONG FK (AC22)

CREATE INDEX IDX_document_source_personal_file
    ON document (source_personal_file_id)
    WHERE deleted_at IS NULL AND source_personal_file_id IS NOT NULL;

-- Ket qua tung tep cua mot luot (poll + audit UI). Khong luu ten/duong dan thu muc ca nhan.
CREATE TABLE personal_ingest_item (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    batch_id UUID NOT NULL REFERENCES upload_batch (id),
    personal_file_id UUID NOT NULL,
    original_name VARCHAR(512) NOT NULL,
    size BIGINT NOT NULL,
    relative_path VARCHAR(1024) NOT NULL DEFAULT '',
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    code VARCHAR(48) NULL,
    document_id UUID NULL,
    duplicate_of UUID[] NULL,
    processed_at TIMESTAMPTZ NULL
);
CREATE INDEX IDX_personal_ingest_item_batch ON personal_ingest_item (batch_id);
