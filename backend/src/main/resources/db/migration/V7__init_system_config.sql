-- System configuration (FR-01 / Story system-config): key/value store for the
-- MVP business parameters an admin (R3) can tune at runtime. See
-- docs/03-dev/specs/system-config.md and docs/01-sa/architecture/system-config.md
-- (Quyết định 3) for the catalogue.
--
-- Intentionally NOT seeded: a missing row means "use the static default" from
-- SystemConfigDefaults (Java), so the app degrades to sane defaults when the
-- table is empty or a single key was never edited (Story 1 AC4, Story 6). The
-- static registry - not a DB row - is the source of default values.
--
-- Postgres remains the source of truth; Redis is only a cache in front of this
-- table (Quyết định 4). value_type is constrained so the service can convert
-- the TEXT value back to the right runtime type.

CREATE TABLE system_config (
    key         VARCHAR(128) PRIMARY KEY,
    value       TEXT NOT NULL,
    value_type  VARCHAR(16) NOT NULL CHECK (value_type IN ('STRING', 'NUMBER', 'BOOLEAN', 'JSON')),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by  VARCHAR(255)
);

-- Story 5 AC1 reads the per-key change history straight from the append-only
-- audit_log (resource_type = 'system_config', resource_id = <key>). This index
-- keeps that lookup cheap as the audit table grows; it is created here rather
-- than in V1 because system-config is the first feature to query audit_log by
-- (resource_type, resource_id).
CREATE INDEX IDX_audit_log_resource ON audit_log (resource_type, resource_id, id DESC);
