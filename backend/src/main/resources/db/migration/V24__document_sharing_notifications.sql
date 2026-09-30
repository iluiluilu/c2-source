-- group-management-sharing-02-document-sharing.md Task 1: in-app
-- notification inbox (Story 4) - bulk document share fan-out, group
-- member added/removed. No changes to "document_group_share" (reused
-- as-is for Story 1-3).

CREATE TABLE notification (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    recipient_sub VARCHAR(255) NOT NULL,
    type          VARCHAR(64) NOT NULL, -- 'group.member_added' | 'group.member_removed' | 'document.shared_to_group'
    payload_json  JSONB NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    read_at       TIMESTAMPTZ
);

-- GET /notifications: most recent first, scoped to the caller.
CREATE INDEX IDX_notification_recipient ON notification (recipient_sub, created_at DESC);

-- TTL cleanup job (90 days) - see NotificationService#cleanupExpired.
CREATE INDEX IDX_notification_created_at ON notification (created_at);
