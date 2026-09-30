-- audit-logging.md Task 2 (spec §2.4) + part of Task 7 (RBAC seed for the
-- new PERM_audit.read_scoped permission). Spec sketch calls this V15; that
-- number is already taken (V15__role_permission_audit_columns.sql), so it is
-- V22 here, immediately after V21 - no other change from the spec.

CREATE TABLE audit_checkpoint (
    id          BIGSERIAL PRIMARY KEY,
    up_to_id    BIGINT NOT NULL,
    root_hash   VARCHAR(64) NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IDX_audit_checkpoint_up_to_id ON audit_checkpoint (up_to_id DESC);

-- Story 3 (R2 scoped audit read), audit-logging.md §4.4/Task 7: a new
-- permission, granted to LANH_DAO (R2) only - QUAN_TRI (R3) keeps
-- audit.read (unrestricted) exactly as seeded by V12, unchanged here.
INSERT INTO permission (code, description) VALUES
    ('audit.read_scoped', 'Xem nhật ký hoạt động giới hạn trong phạm vi nhóm (R2)');

INSERT INTO role_permission (role_code, permission_code) VALUES
    ('LANH_DAO', 'audit.read_scoped');
