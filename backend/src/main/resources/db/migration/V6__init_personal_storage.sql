-- Personal storage (FR-14): per-user folder tree + files. Java port of the
-- NestJS backend's migrations/1783750000000-InitPersonalStorage.ts.
--
-- Sibling name uniqueness is per owner: composite unique on
-- (owner_sub, parent_id, name) plus a partial unique index for root folders
-- (Postgres treats NULL parent_id as distinct, same trick as
-- UQ_warehouse_folder_root_name). Both the folder self-FK and
-- personal_file.folder_id are ON DELETE RESTRICT: "delete non-empty folder"
-- is blocked by the constraint itself. storage_key is NOT NULL: rejected
-- uploads persist no row (personal files are not evidence), so every row is
-- a stored clean file.

CREATE TABLE personal_folder (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_sub       VARCHAR(255) NOT NULL,
    owner_username  VARCHAR(255) NOT NULL,
    name            VARCHAR(255) NOT NULL,
    parent_id       UUID REFERENCES personal_folder (id) ON DELETE RESTRICT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT UQ_personal_folder_sibling_name UNIQUE (owner_sub, parent_id, name)
);

CREATE UNIQUE INDEX UQ_personal_folder_root_name ON personal_folder (owner_sub, name) WHERE parent_id IS NULL;
CREATE INDEX IDX_personal_folder_owner_sub ON personal_folder (owner_sub);

-- sha256 uses VARCHAR(64), not CHAR(64), matching the same
-- Hibernate-ddl-auto=validate convention established for document.sha256.
CREATE TABLE personal_file (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_sub       VARCHAR(255) NOT NULL,
    owner_username  VARCHAR(255) NOT NULL,
    folder_id       UUID REFERENCES personal_folder (id) ON DELETE RESTRICT,
    original_name   VARCHAR(512) NOT NULL,
    mime_type       VARCHAR(255) NOT NULL,
    size            BIGINT NOT NULL,
    sha256          VARCHAR(64) NOT NULL,
    storage_key     VARCHAR(512) NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IDX_personal_file_owner_sub ON personal_file (owner_sub);
CREATE INDEX IDX_personal_file_folder_id ON personal_file (folder_id);
