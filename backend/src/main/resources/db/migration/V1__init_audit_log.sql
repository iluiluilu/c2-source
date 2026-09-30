-- Append-only, hash-chained activity log (FR-09 / NFR-09). Java port of the
-- NestJS backend's migrations/1783600000000-InitAuditLog.ts.
--
-- prev_hash/hash use VARCHAR(64) rather than the original CHAR(64): the
-- AuditLog entity maps them as a plain @Column(length = 64) String, and
-- Hibernate's ddl-auto=validate expects varchar for that mapping. Semantically
-- identical for fixed-length hex hashes.
CREATE TABLE audit_log (
    id            BIGSERIAL PRIMARY KEY,
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
    hash          VARCHAR(64) NOT NULL
);

CREATE INDEX IDX_audit_log_action ON audit_log (action);
CREATE INDEX IDX_audit_log_created_at ON audit_log (created_at);

-- Row-level immutability: the strongest app-managed guarantee short of a
-- dedicated append-only DB role. DROP TABLE is not blocked by this trigger.
CREATE OR REPLACE FUNCTION audit_log_immutable() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'audit_log is append-only; % is not permitted', TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER audit_log_no_update_delete
    BEFORE UPDATE OR DELETE ON audit_log
    FOR EACH ROW EXECUTE FUNCTION audit_log_immutable();
