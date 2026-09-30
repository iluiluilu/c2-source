-- audit-logging.md Task 1 (spec §2.1-2.3). NOTE: the spec sketch names this
-- migration V14, but by the time this was implemented the classpath had
-- already moved on to V20 (V14 was never used, V15 is taken by
-- V15__role_permission_audit_columns.sql) - renumbered to V21, no other
-- change from what the spec describes.
--
-- Three things happen here:
--   1. audit_log gets sealed_at, and hash/prev_hash become nullable -
--      record() will insert with all three NULL from now on (4.1); a
--      background sealer (AuditSealerJob) fills them in later.
--   2. audit_log is swapped for a table PARTITION BY RANGE (created_at) via
--      rename-and-swap, since Postgres requires the partition key inside the
--      PRIMARY KEY of a partitioned table and this table already has data.
--      audit_log_legacy is kept (not dropped) until verifyChain has been run
--      against the new table and confirmed to match - cleanup is a separate,
--      manual, future migration (see the comment above the RENAME below).
--   3. A new seal-aware trigger replaces the old "always reject" one, but
--      only on the NEW table - audit_log_legacy keeps its original,
--      unconditionally-rejecting trigger untouched (renaming a table does not
--      touch its own triggers), so the historical data's immutability
--      guarantee is not weakened even in theory.

-- ---------------------------------------------------------------------
-- Step 1: add sealed_at / relax hash nullability on the (still unpartitioned)
-- table, before it gets renamed away. prev_hash is already nullable (V1).
-- ---------------------------------------------------------------------
ALTER TABLE audit_log ADD COLUMN sealed_at TIMESTAMPTZ;
ALTER TABLE audit_log ALTER COLUMN hash DROP NOT NULL;

-- ---------------------------------------------------------------------
-- Step 2: rename-and-swap. The BIGSERIAL sequence created by V1
-- (audit_log_id_seq) is NOT renamed by RENAME TABLE - it stays attached to
-- audit_log_legacy.id by internal ownership. The new table's id column
-- reuses that same sequence directly (NOT a fresh BIGSERIAL, which would try
-- to create a second sequence under the same default name and fail) so IDs
-- keep incrementing from where audit_log_legacy left off once setval() runs
-- below.
--
-- Whoever writes the future manual cleanup migration that finally drops
-- audit_log_legacy MUST first run
--   ALTER SEQUENCE audit_log_id_seq OWNED BY audit_log.id;
-- otherwise dropping audit_log_legacy will drop the sequence with it (it is
-- still marked as "owned by" audit_log_legacy.id from the original SERIAL).
-- ---------------------------------------------------------------------
ALTER TABLE audit_log RENAME TO audit_log_legacy;

-- RENAME TABLE does not rename the table's own indexes - V1's
-- IDX_audit_log_created_at/IDX_audit_log_action stay attached to
-- audit_log_legacy under their original names, which would collide with the
-- identically-named indexes step 2 below creates on the new audit_log table
-- (Postgres index names are unique per schema, not per table). Rename them
-- out of the way, same "_legacy" convention as the table itself.
ALTER INDEX IDX_audit_log_created_at RENAME TO IDX_audit_log_legacy_created_at;
ALTER INDEX IDX_audit_log_action RENAME TO IDX_audit_log_legacy_action;

CREATE TABLE audit_log (
    id            BIGINT NOT NULL DEFAULT nextval('audit_log_id_seq'),
    created_at    TIMESTAMPTZ NOT NULL,
    actor         VARCHAR(255) NOT NULL,
    actor_sub     VARCHAR(255),
    action        VARCHAR(128) NOT NULL,
    resource_type VARCHAR(128),
    resource_id   VARCHAR(255),
    outcome       VARCHAR(16) NOT NULL,
    method        VARCHAR(8),
    path          TEXT,
    status_code   INTEGER,
    ip            VARCHAR(64),
    metadata      JSONB,
    prev_hash     VARCHAR(64),
    hash          VARCHAR(64),
    sealed_at     TIMESTAMPTZ,
    PRIMARY KEY (id, created_at)
) PARTITION BY RANGE (created_at);

-- Partitions from the oldest existing audit_log_legacy row's month through
-- the current month plus two more, computed at migration run time (not
-- hard-coded literals - this migration may run against a fresh install
-- months after it was written). AuditPartitionMaintenanceJob (§4.5) keeps
-- creating the next month's partition going forward; this is only the
-- bootstrap.
--
-- The spec sketch (§2.2 step 3) only mentions "current month + 2" - that is
-- not enough on its own: this environment already has audit_log data older
-- than the current month (audit logging has been live since V1), and the
-- INSERT ... SELECT below would fail with "no partition of relation
-- audit_log found for row" for any legacy row outside that 3-month window.
-- Backfilling from the oldest legacy row's month closes that gap; a fresh
-- install with an empty audit_log_legacy just gets the same 3 months the
-- spec describes.
DO $$
DECLARE
    earliest_month date;
    latest_month date;
    month_start date;
    month_end   date;
    partition_name text;
BEGIN
    SELECT date_trunc('month', COALESCE(MIN(created_at), now()))::date INTO earliest_month
    FROM audit_log_legacy;
    latest_month := (date_trunc('month', now()) + interval '2 months')::date;

    month_start := earliest_month;
    WHILE month_start <= latest_month LOOP
        month_end := (month_start + interval '1 month')::date;
        partition_name := 'audit_log_' || to_char(month_start, 'YYYY_MM');
        EXECUTE format(
            'CREATE TABLE IF NOT EXISTS %I PARTITION OF audit_log FOR VALUES FROM (%L) TO (%L)',
            partition_name, month_start, month_end);
        month_start := month_end;
    END LOOP;
END $$;

-- Preserve ids and historical hash chain as-is. Rows copied from the legacy
-- table already have hash/prev_hash computed the old synchronous way, so
-- they are already "sealed" in effect - back-fill sealed_at = created_at for
-- them (AuditSealerJob only ever touches rows where hash IS NULL, so this is
-- just bookkeeping, not required for correctness, but keeps
-- "hash IS NOT NULL" and "sealed_at IS NOT NULL" consistent for the copied
-- rows instead of leaving sealed_at NULL on already-hashed data).
INSERT INTO audit_log (
    id, created_at, actor, actor_sub, action, resource_type, resource_id,
    outcome, method, path, status_code, ip, metadata, prev_hash, hash, sealed_at)
SELECT
    id, created_at, actor, actor_sub, action, resource_type, resource_id,
    outcome, method, path, status_code, ip, metadata, prev_hash, hash,
    CASE WHEN hash IS NOT NULL THEN created_at ELSE NULL END
FROM audit_log_legacy
ORDER BY id;

-- Guarded: on a brand-new database audit_log is still empty at this point
-- (no audit event has ever been recorded), and setval() with a value of 0 is
-- out of bounds for a sequence whose default MINVALUE is 1. Skipping the
-- call on an empty table is safe - the sequence is already correctly
-- positioned at its default start.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM audit_log) THEN
        PERFORM setval('audit_log_id_seq', (SELECT MAX(id) FROM audit_log), true);
    END IF;
END $$;

CREATE INDEX IDX_audit_log_created_at ON audit_log (created_at);
CREATE INDEX IDX_audit_log_actor_created_at ON audit_log (actor, created_at);
CREATE INDEX IDX_audit_log_resource_created_at ON audit_log (resource_type, resource_id, created_at);
CREATE INDEX IDX_audit_log_metadata_gin ON audit_log USING GIN (metadata);
-- Kept from V1/V7 for action-only and (resource_type,resource_id) id-order
-- lookups that predate this migration - harmless to keep alongside the new
-- created_at-based indexes above.
CREATE INDEX IDX_audit_log_action ON audit_log (action);
CREATE INDEX IDX_audit_log_resource_id_desc ON audit_log (resource_type, resource_id, id DESC);

-- ---------------------------------------------------------------------
-- Step 3: seal-aware trigger, new table only (see file header).
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION audit_log_seal_or_reject() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'UPDATE'
       AND OLD.hash IS NULL AND OLD.sealed_at IS NULL
       AND NEW.hash IS NOT NULL AND NEW.sealed_at IS NOT NULL
       AND NEW.created_at IS NOT DISTINCT FROM OLD.created_at
       AND NEW.actor IS NOT DISTINCT FROM OLD.actor
       AND NEW.actor_sub IS NOT DISTINCT FROM OLD.actor_sub
       AND NEW.action IS NOT DISTINCT FROM OLD.action
       AND NEW.resource_type IS NOT DISTINCT FROM OLD.resource_type
       AND NEW.resource_id IS NOT DISTINCT FROM OLD.resource_id
       AND NEW.outcome IS NOT DISTINCT FROM OLD.outcome
       AND NEW.method IS NOT DISTINCT FROM OLD.method
       AND NEW.path IS NOT DISTINCT FROM OLD.path
       AND NEW.status_code IS NOT DISTINCT FROM OLD.status_code
       AND NEW.ip IS NOT DISTINCT FROM OLD.ip
       AND NEW.metadata IS NOT DISTINCT FROM OLD.metadata
    THEN
        RETURN NEW; -- exactly one valid seal: only prev_hash/hash/sealed_at change, from NULL
    END IF;
    RAISE EXCEPTION 'audit_log is append-only; % is not permitted', TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER audit_log_seal_aware_immutable
    BEFORE UPDATE OR DELETE ON audit_log
    FOR EACH ROW EXECUTE FUNCTION audit_log_seal_or_reject();
