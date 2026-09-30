-- chunked-file-upload-01-ingest-chunked-upload.md §2.1: upload_session backs
-- the init -> chunk -> status -> complete protocol shared by the ingest
-- (this spec, kind=WAREHOUSE) and personal (chunked-file-upload-02, kind=
-- PERSONAL) chunked-upload flows. Runs after V15 (role_permission_audit_columns).

CREATE TABLE upload_session (
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    batch_id               UUID NOT NULL REFERENCES upload_batch (id),
    original_name          VARCHAR(1024) NOT NULL,
    relative_path          VARCHAR(1024) NOT NULL DEFAULT '',
    declared_size_bytes    BIGINT NOT NULL CHECK (declared_size_bytes > 0),
    received_bytes         BIGINT NOT NULL DEFAULT 0 CHECK (received_bytes >= 0),
    -- Snapshot of system-config `upload.chunk_size_bytes` at init time (DEV
    -- addition beyond the SA column list): if an admin changes the config
    -- while a session is in flight, the client still resumes with the chunk
    -- size it committed to at init, instead of picking up a value that could
    -- make its already-sent offset land mid-chunk.
    chunk_size_bytes       BIGINT NOT NULL CHECK (chunk_size_bytes > 0),
    -- DEV addition: mirrors the `mimeType`/`note` parameters the whole-file
    -- flow (`DocumentsService.ingestBatchFile`) receives directly from the
    -- request - chunked upload needs somewhere to carry them from init to
    -- complete.
    declared_mime_type     VARCHAR(255),
    note                   VARCHAR(4000),
    temp_file_path         VARCHAR(1024) NOT NULL,
    status                 VARCHAR(16) NOT NULL DEFAULT 'OPEN'
        CHECK (status IN ('OPEN', 'COMPLETED', 'ABORTED', 'EXPIRED')),
    -- Result of complete(), kept so a retried complete() call on an already-
    -- COMPLETED session can replay it idempotently (§4.4) instead of running
    -- ingestBatchFile a second time.
    result_body            JSONB,
    -- Mirrors owner_sub/owner_username on upload_batch (V10) for consistency.
    created_by_sub         VARCHAR(255) NOT NULL,
    created_by_username    VARCHAR(255) NOT NULL,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at             TIMESTAMPTZ NOT NULL DEFAULT now() + INTERVAL '24 hours'
);

CREATE INDEX IDX_upload_session_batch_id ON upload_session (batch_id);
-- Serves the cleanup job in chunked-file-upload-03-session-cleanup.md - created
-- here since the table itself is defined by this migration.
CREATE INDEX IDX_upload_session_status_expires_at ON upload_session (status, expires_at);
