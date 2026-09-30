-- Warehouse folder tree + immutable document metadata (FR-11/FR-12). Java
-- port of the NestJS backend's migrations/1783700000000-InitWarehouseAndDocuments.ts,
-- written directly in its final shape (post 1783710000000-RemoveDocumentUnitClearance.ts
-- -- no unit/clearance columns), since this migration history starts fresh.

CREATE TABLE warehouse_folder (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name       VARCHAR(255) NOT NULL,
    parent_id  UUID REFERENCES warehouse_folder (id) ON DELETE RESTRICT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT UQ_warehouse_folder_sibling_name UNIQUE (parent_id, name)
);

CREATE INDEX IDX_warehouse_folder_parent_id ON warehouse_folder (parent_id);

-- Postgres treats NULL as distinct, so the sibling-name UNIQUE constraint
-- above does not cover root folders (parent_id IS NULL) - a partial unique
-- index is needed for root-level name uniqueness.
CREATE UNIQUE INDEX UQ_warehouse_folder_root_name ON warehouse_folder (name) WHERE parent_id IS NULL;

-- Document metadata is immutable once written (NFR-09): no update/delete
-- code path exists. sha256/hash-like columns use VARCHAR(64), not CHAR(64),
-- to match Hibernate's ddl-auto=validate expectation for a @Column(length=64)
-- String field (same reasoning as V1's audit_log hash columns).
CREATE TABLE document (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    original_name     VARCHAR(512) NOT NULL,
    mime_type         VARCHAR(255) NOT NULL,
    size              BIGINT NOT NULL,
    sha256            VARCHAR(64) NOT NULL,
    storage_key       VARCHAR(512),
    folder_id         UUID NOT NULL REFERENCES warehouse_folder (id) ON DELETE RESTRICT,
    uploader_sub      VARCHAR(255) NOT NULL,
    uploader_username VARCHAR(255) NOT NULL,
    status            VARCHAR(16) NOT NULL,
    scan_result       JSONB,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IDX_document_sha256 ON document (sha256);
CREATE INDEX IDX_document_folder_id ON document (folder_id);
CREATE INDEX IDX_document_created_at ON document (created_at);
