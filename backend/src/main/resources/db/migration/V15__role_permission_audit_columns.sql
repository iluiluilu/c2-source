-- rbac-permission-editing.md Task 1: role_permission becomes editable
-- (Story 1/2/3). V12's comment "seed-only, KHONG co API ghi" no longer
-- applies from this migration onward - RbacService (com.aismartstore.
-- backend.rbac) now writes this table through /admin/rbac/roles/{roleCode}
-- /permissions. updated_at/updated_by_sub are row-level audit columns,
-- separate from the append-only audit_log chain (FR-09), so R3 can see at a
-- glance which mappings were hand-edited vs still at their V12 seed state.
ALTER TABLE role_permission
    ADD COLUMN updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    ADD COLUMN updated_by_sub VARCHAR(255);

-- Seed rows inserted by V12 keep updated_at = the moment V15 runs and
-- updated_by_sub = NULL (nobody - it's the original seed, not an R3 edit).
