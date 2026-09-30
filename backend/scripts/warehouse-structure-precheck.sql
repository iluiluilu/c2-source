-- Task 0 (docs/03-dev/specs/warehouse-structure.md, Quyet dinh 3 muc 0):
-- READ-ONLY pre-check for V9__warehouse_structure_v2.sql. Run this against
-- every environment that already has warehouse_folder data BEFORE deploying
-- V9 - it lists every row that will make the migration fail (V9 does NOT
-- auto-normalize names or truncate branches; offending rows must be cleaned
-- up by hand, e.g. by R3, before V9 runs).
--
-- Usage: psql "$DATABASE_URL" -f scripts/warehouse-structure-precheck.sql
-- (or paste into any Postgres client connected to the target database).
-- Nothing here writes to the database.

-- ---------------------------------------------------------------------
-- (a) Names that would violate V9's new constraints:
--     ALTER COLUMN name TYPE VARCHAR(100)
--     ADD CONSTRAINT CK_warehouse_folder_name_format
--         CHECK (name ~ '^[A-Za-z0-9 _-]{1,100}$')
-- Spaces are allowed; accented/Vietnamese characters and any length > 100
-- are not.
-- ---------------------------------------------------------------------
SELECT
    id,
    parent_id,
    name,
    length(name) AS name_length,
    CASE
        WHEN length(name) > 100 THEN 'too_long'
        ELSE 'invalid_characters'
    END AS violation
FROM warehouse_folder
WHERE name !~ '^[A-Za-z0-9 _-]{1,100}$'
   OR length(name) > 100
ORDER BY violation, name_length DESC;

-- ---------------------------------------------------------------------
-- (b) Branches deeper than 5 levels, which would violate V9's:
--     ADD CONSTRAINT CK_warehouse_folder_depth CHECK (depth BETWEEN 1 AND 5)
-- Reports every folder whose computed depth (root = 1) exceeds 5, along
-- with the full ancestor chain (as ids) for R3 to locate it in the UI.
-- ---------------------------------------------------------------------
WITH RECURSIVE tree AS (
    SELECT id, parent_id, name, ARRAY[id] AS chain, 1 AS depth
    FROM warehouse_folder
    WHERE parent_id IS NULL
    UNION ALL
    SELECT f.id, f.parent_id, f.name, t.chain || f.id, t.depth + 1
    FROM warehouse_folder f
    JOIN tree t ON f.parent_id = t.id
)
SELECT id, name, depth, chain AS ancestor_chain_root_to_self
FROM tree
WHERE depth > 5
ORDER BY depth DESC;

-- If BOTH queries return zero rows, the environment is safe to migrate.
