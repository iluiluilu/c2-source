-- data-ingestion.md §2: allowlist table (replaces MimeAllowlist.java as the
-- source of truth, MimeAllowlist stays as seed reference + fallback), the
-- upload_batch table backing the 2-step batch ingest API, and document
-- changes (relative_path, nullable sha256, dedupe index).
--
-- Runs after V9 (warehouse-structure.md) - the unique index below references
-- document.deleted_at, added by V9. See the migration order table at the top
-- of data-ingestion.md.

-- 2.1 Allowlist of accepted formats (Story 5 AC1). Seed = 1-1 copy of the
-- ALLOWED/MAGIC maps in MimeAllowlist.java (25 entries as the map stands
-- today - the "28" figure in an earlier draft of this spec was an estimate,
-- not a literal count; the source of truth is "one row per MimeAllowlist
-- entry", not a specific number).
CREATE TABLE allowed_file_format (
    mime_type    VARCHAR(255) PRIMARY KEY,
    extensions   VARCHAR(20)[] NOT NULL,
    category     VARCHAR(32) NOT NULL, -- TEXT_DOC | IMAGE | AUDIO | VIDEO
    magic_bytes  JSONB, -- [{"offset":0,"hex":"89504E47"}, ...]; NULL = no signature check
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by_sub      VARCHAR(255),
    updated_by_username VARCHAR(255)
);

INSERT INTO allowed_file_format (mime_type, extensions, category, magic_bytes) VALUES
    ('application/pdf', ARRAY['pdf'], 'TEXT_DOC', '[{"offset":0,"hex":"25504446"}]'),
    ('text/plain', ARRAY['txt','text','log','md'], 'TEXT_DOC', NULL),
    ('text/csv', ARRAY['csv'], 'TEXT_DOC', NULL),
    ('application/msword', ARRAY['doc'], 'TEXT_DOC', '[{"offset":0,"hex":"D0CF11E0"}]'),
    ('application/vnd.openxmlformats-officedocument.wordprocessingml.document', ARRAY['docx'], 'TEXT_DOC', '[{"offset":0,"hex":"504B"}]'),
    ('application/vnd.ms-excel', ARRAY['xls'], 'TEXT_DOC', '[{"offset":0,"hex":"D0CF11E0"}]'),
    ('application/vnd.openxmlformats-officedocument.spreadsheetml.sheet', ARRAY['xlsx'], 'TEXT_DOC', '[{"offset":0,"hex":"504B"}]'),
    ('application/vnd.ms-powerpoint', ARRAY['ppt'], 'TEXT_DOC', '[{"offset":0,"hex":"D0CF11E0"}]'),
    ('application/vnd.openxmlformats-officedocument.presentationml.presentation', ARRAY['pptx'], 'TEXT_DOC', '[{"offset":0,"hex":"504B"}]'),
    ('image/png', ARRAY['png'], 'IMAGE', '[{"offset":0,"hex":"89504E47"}]'),
    ('image/jpeg', ARRAY['jpg','jpeg'], 'IMAGE', '[{"offset":0,"hex":"FFD8FF"}]'),
    ('image/gif', ARRAY['gif'], 'IMAGE', '[{"offset":0,"hex":"47494638"}]'),
    ('image/webp', ARRAY['webp'], 'IMAGE', '[{"offset":0,"hex":"52494646"}]'),
    ('image/tiff', ARRAY['tif','tiff'], 'IMAGE', '[{"offset":0,"hex":"49492A00"},{"offset":0,"hex":"4D4D002A"}]'),
    ('audio/mpeg', ARRAY['mp3'], 'AUDIO', '[{"offset":0,"hex":"494433"},{"offset":0,"hex":"FFFB"},{"offset":0,"hex":"FFF3"},{"offset":0,"hex":"FFF2"}]'),
    ('audio/mp4', ARRAY['m4a'], 'AUDIO', '[{"offset":4,"hex":"66747970"}]'),
    ('audio/wav', ARRAY['wav'], 'AUDIO', '[{"offset":0,"hex":"52494646"}]'),
    ('audio/x-wav', ARRAY['wav'], 'AUDIO', '[{"offset":0,"hex":"52494646"}]'),
    ('audio/ogg', ARRAY['ogg','oga'], 'AUDIO', '[{"offset":0,"hex":"4F676753"}]'),
    ('audio/aac', ARRAY['aac'], 'AUDIO', '[{"offset":0,"hex":"FFF1"},{"offset":0,"hex":"FFF9"}]'),
    ('video/mp4', ARRAY['mp4'], 'VIDEO', '[{"offset":4,"hex":"66747970"}]'),
    ('video/mpeg', ARRAY['mpeg','mpg'], 'VIDEO', NULL),
    ('video/quicktime', ARRAY['mov'], 'VIDEO', '[{"offset":4,"hex":"66747970"},{"offset":4,"hex":"6D6F6F76"}]'),
    ('video/x-matroska', ARRAY['mkv'], 'VIDEO', '[{"offset":0,"hex":"1A45DFA3"}]'),
    ('video/webm', ARRAY['webm'], 'VIDEO', '[{"offset":0,"hex":"1A45DFA3"}]');

-- 2.2 Upload batch (Story 2 AC3): one batch = one client "open upload" round
-- (single file or a whole folder), targeting exactly one destination folder.
CREATE TABLE upload_batch (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    target_folder_id      UUID NOT NULL REFERENCES warehouse_folder (id),
    owner_sub             VARCHAR(255) NOT NULL,
    owner_username        VARCHAR(255) NOT NULL,
    declared_file_count   INT NOT NULL CHECK (declared_file_count > 0),
    declared_total_bytes  BIGINT NOT NULL CHECK (declared_total_bytes >= 0),
    actual_file_count     INT NOT NULL DEFAULT 0,
    actual_total_bytes    BIGINT NOT NULL DEFAULT 0,
    -- Additive beyond the spec's §2.2 column list: back the
    -- POST /ingest/batches/{id}/close summary (§3) promised
    -- {filesAccepted, filesSkipped, filesRejected} counts, which
    -- actual_file_count alone cannot provide (it counts every RESERVED file,
    -- clean or not - see §4.2 step 4 running before step 5's format check -
    -- and a skipped duplicate never touches it at all, per step 3). Safe/
    -- local addition: no other column, decision or test in the spec depends
    -- on these three, and they are written only by this migration's own
    -- feature code.
    accepted_file_count   INT NOT NULL DEFAULT 0,
    skipped_file_count    INT NOT NULL DEFAULT 0,
    rejected_file_count   INT NOT NULL DEFAULT 0,
    status                VARCHAR(16) NOT NULL DEFAULT 'OPEN'
        CHECK (status IN ('OPEN','CLOSED','LIMIT_EXCEEDED','EXPIRED')),
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at            TIMESTAMPTZ NOT NULL DEFAULT now() + INTERVAL '24 hours'
);
CREATE INDEX IDX_upload_batch_owner_status ON upload_batch (owner_sub, status);
-- No cleanup job for expired/closed batches in the MVP (Quyet dinh 4 muc 0) -
-- every read path lazy-expires before use; rows are tiny and accrue slowly.

-- 2.3 Document: relative path within the destination folder (Story 2 AC1,
-- Story 4 AC2) + nullable sha256 (Story 3 AC2: a file rejected for format
-- never gets hashed).
ALTER TABLE document ADD COLUMN relative_path VARCHAR(1024) NOT NULL DEFAULT '';
ALTER TABLE document ALTER COLUMN sha256 DROP NOT NULL;

-- Re-upload de-dup key = (folder_id, relative_path, original_name), counting
-- only 'clean' AND NOT soft-deleted rows (Quyet dinh 1 muc 0) - a trashed
-- document does not hold its path, so re-uploading the same name/path
-- succeeds. Must stay in lockstep with the duplicate-detection query in
-- data-ingestion.md §4.2 step 3.
CREATE UNIQUE INDEX UQ_document_folder_relpath_name_clean
    ON document (folder_id, relative_path, original_name)
    WHERE status = 'clean' AND deleted_at IS NULL;
