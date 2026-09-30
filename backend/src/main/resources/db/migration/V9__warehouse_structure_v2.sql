-- Warehouse folder tree v2 (FR-13): materialized path (path_ids, depth),
-- audit columns, tighter name validation, document.folder_id detach on
-- folder delete, minimal soft-delete column, and a singleton warehouse
-- capacity row. See docs/03-dev/specs/warehouse-structure.md for the full
-- rationale (§2 in particular). Run
-- backend/scripts/warehouse-structure-precheck.sql against any environment
-- with existing warehouse_folder data BEFORE this migration - it is not
-- idempotent/self-healing on purpose (Quyet dinh 3 muc 0).

-- 2.1 Warehouse folder: add path_ids (materialized path by ANCESTOR ID, not
-- name -> renaming never touches path_ids), depth, audit columns, and
-- tighten the name constraint (still allows spaces, still no accents).
ALTER TABLE warehouse_folder
    ALTER COLUMN name TYPE VARCHAR(100),
    ADD COLUMN path_ids UUID[] NOT NULL DEFAULT '{}',
    ADD COLUMN depth SMALLINT NOT NULL DEFAULT 1,
    ADD COLUMN created_by_sub VARCHAR(255),
    ADD COLUMN created_by_username VARCHAR(255),
    ADD COLUMN updated_by_sub VARCHAR(255),
    ADD COLUMN updated_by_username VARCHAR(255),
    ADD CONSTRAINT CK_warehouse_folder_depth CHECK (depth BETWEEN 1 AND 5),
    ADD CONSTRAINT CK_warehouse_folder_name_format
        CHECK (name ~ '^[A-Za-z0-9 _-]{1,100}$');

-- Backfill path_ids/depth for existing rows (recursive CTE, one-time) BEFORE
-- the depth CHECK above would otherwise reject a row with the DEFAULT
-- depth=1 that is actually deeper - the ADD CONSTRAINT statement runs first
-- so any pre-existing branch deeper than 5 levels makes this migration fail
-- outright (by design - see backend/scripts/warehouse-structure-precheck.sql).
WITH RECURSIVE tree AS (
    SELECT id, ARRAY[]::uuid[] AS path_ids, 1::smallint AS depth
    FROM warehouse_folder WHERE parent_id IS NULL
    UNION ALL
    SELECT f.id, t.path_ids || f.parent_id, (t.depth + 1)::smallint
    FROM warehouse_folder f JOIN tree t ON f.parent_id = t.id
)
UPDATE warehouse_folder f SET path_ids = t.path_ids, depth = t.depth
FROM tree t WHERE f.id = t.id;

CREATE INDEX IDX_warehouse_folder_path_ids ON warehouse_folder USING GIN (path_ids);

-- 2.2 Document: allow detaching from a folder when the folder is deleted
-- (Story 3 AC2) instead of a hard RESTRICT on every document row (including
-- rejected/infected rows, which never had bytes on SeaweedFS anyway).
ALTER TABLE document ALTER COLUMN folder_id DROP NOT NULL;
ALTER TABLE document DROP CONSTRAINT document_folder_id_fkey;
ALTER TABLE document ADD CONSTRAINT document_folder_id_fkey
    FOREIGN KEY (folder_id) REFERENCES warehouse_folder (id) ON DELETE SET NULL;

-- 2.3 Minimal soft-delete column (Quyet dinh 1 muc 0). Schema + counting
-- semantics ONLY - no trash UI, no expiry job (FR-26/27, out of scope here).
-- NULL = active; NOT NULL = in trash (bytes still live on SeaweedFS, so it
-- still counts toward used storage - Story 5 AC1).
ALTER TABLE document ADD COLUMN deleted_at TIMESTAMPTZ NULL;

-- Partial index for "still has active data" checks per folder (Story 3 AC2 -
-- only rows not soft-deleted) and an index-only-scan-friendly index for the
-- total-used-bytes aggregate (Story 5 AC1 - deliberately NOT filtered by
-- deleted_at, trash still counts).
CREATE INDEX IDX_document_folder_active ON document (folder_id, status)
    WHERE deleted_at IS NULL;
CREATE INDEX IDX_document_status_size ON document (status) INCLUDE (size);

-- 2.4 Singleton row for the warehouse's configured maximum capacity (Story 5
-- AC4). Kept out of system_config on purpose: R3 edits this from the
-- warehouse management screen, not the general system-config screen.
CREATE TABLE warehouse (
    id                  SMALLINT PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    max_capacity_bytes  BIGINT NOT NULL CHECK (max_capacity_bytes > 0),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by_sub      VARCHAR(255),
    updated_by_username VARCHAR(255)
);

-- Seed a safe placeholder (10 TiB) so usedPercent never divides by zero; R3
-- is expected to set the real value via PATCH /warehouse/capacity after
-- deploy. Not a business-approved number.
INSERT INTO warehouse (id, max_capacity_bytes) VALUES (1, 10995116277760);
