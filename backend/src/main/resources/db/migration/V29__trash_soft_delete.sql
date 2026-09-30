-- delete-restore-trash-01-xoa-mem-thung-rac.md §2.1.
-- DEV note: the spec (written in parallel with 2 other in-flight slugs) had
-- reserved this as V28, but a third migration
-- (V28__digitization_review_queue_and_permissions.sql) merged first and
-- already claimed that number - this migration is therefore V29 instead
-- (bumped by 1, per the spec's own §0 instruction to re-grep before running).
-- File 02 (delete-restore-trash-02-xoa-cung-xung-dot.md) must use V30, not
-- V29, when it is implemented.

-- Xóa mềm (FR-26) - Quyết định 1 SA: cờ tại chỗ trên document, không tách bảng.
ALTER TABLE document
    ADD COLUMN deleted_by_sub      VARCHAR(255),
    ADD COLUMN deleted_by_username VARCHAR(255),
    ADD COLUMN deleted_reason      VARCHAR(500),
    ADD COLUMN purge_after         TIMESTAMPTZ;

-- Một lượt xóa nhiều tài liệu -> phục hồi lại được theo lô (Quyết định 1 SA).
CREATE TABLE trash_batch (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    actor_sub      VARCHAR(255) NOT NULL,
    actor_username VARCHAR(255) NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    item_count     INT          NOT NULL
);

ALTER TABLE document
    ADD COLUMN trash_batch_id UUID REFERENCES trash_batch (id);

-- Thùng rác cá nhân: liệt kê theo deleted_by_sub, mới nhất trước (Story 2 AC1).
CREATE INDEX IDX_document_deleted_by_sub
    ON document (deleted_by_sub, deleted_at DESC)
    WHERE deleted_at IS NOT NULL;

-- "Tài liệu tôi nạp bị người khác xóa" (Story 2 AC2, view=uploaded).
CREATE INDEX IDX_document_uploader_deleted
    ON document (uploader_sub, deleted_at DESC)
    WHERE deleted_at IS NOT NULL;

INSERT INTO permission (code, description) VALUES
    ('trash.manage', 'Xóa mềm tài liệu kho dùng chung; xem và phục hồi thùng rác cá nhân'),
    ('trash.stats.read', 'Xem thống kê tổng hợp thùng rác (không xem nội dung/tên tệp)');

INSERT INTO role_permission (role_code, permission_code) VALUES
    ('CAN_BO', 'trash.manage'),
    ('QUAN_TRI', 'trash.stats.read');
