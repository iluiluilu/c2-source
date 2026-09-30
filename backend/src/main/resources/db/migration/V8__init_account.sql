-- Local lifecycle/quota snapshot for accounts (FR-04 lock/disable, FR-14
-- quota). Keycloak stays the source of truth for identity - this table only
-- adds status/status_reason/storage_quota_bytes, which Keycloak has no
-- concept of. Shared with docs/03-dev/specs/personal-storage-quota.md (which
-- reads/writes storage_quota_bytes but does not own this schema); see
-- docs/03-dev/specs/account-lifecycle.md §2.1 for the full rationale.
--
-- No seed/backfill here: rows for accounts created before this migration are
-- lazily get-or-created by AccountsService.getOrCreateAccountRow() (spec
-- §2.2) the first time they're touched, not by this migration.

CREATE TABLE account (
    keycloak_user_id     VARCHAR(255) PRIMARY KEY,
    status                VARCHAR(16)  NOT NULL DEFAULT 'active',
    status_reason         VARCHAR(500),
    storage_quota_bytes  BIGINT       NOT NULL,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT CK_account_status CHECK (status IN ('active','locked','disabled')),
    -- Quyết định 1 (BA): status_reason bắt buộc khi khác 'active'.
    CONSTRAINT CK_account_status_reason CHECK (status = 'active' OR status_reason IS NOT NULL),
    -- Story 5 AC4 / personal-storage-quota Story 4 AC2: snapshot 1 lần, không âm.
    CONSTRAINT CK_account_quota_nonneg CHECK (storage_quota_bytes >= 0)
);

CREATE INDEX IDX_account_status ON account (status);
