-- personal-storage-quota.md §2: extends upload_batch (data-ingestion.md,
-- V10) to also back personal-storage batches, and personal_file (V6) with a
-- batch_id trace column + an owner-scoped SUM(size) index-only-scan index.
-- Runs after V8 (`account`, account-lifecycle.md) and V10 (`upload_batch`,
-- data-ingestion.md) - see the migration order table in personal-storage-quota.md.

-- 2.1 Mở rộng upload_batch (định nghĩa gốc: data-ingestion.md mục 2.2) để
-- dùng chung cho batch cá nhân, không tạo bảng batch riêng (xem mục 1).
ALTER TABLE upload_batch
    ALTER COLUMN target_folder_id DROP NOT NULL,
    ADD COLUMN kind VARCHAR(16) NOT NULL DEFAULT 'WAREHOUSE'
        CHECK (kind IN ('WAREHOUSE', 'PERSONAL')),
    ADD COLUMN target_personal_folder_id UUID REFERENCES personal_folder (id),
    ADD CONSTRAINT CK_upload_batch_kind_target CHECK (
        (kind = 'WAREHOUSE' AND target_folder_id IS NOT NULL AND target_personal_folder_id IS NULL)
        OR (kind = 'PERSONAL' AND target_folder_id IS NULL)
        -- target_personal_folder_id NULL hợp lệ khi kind=PERSONAL: nghĩa là thư mục gốc cá nhân.
    );
CREATE INDEX IDX_upload_batch_kind_owner ON upload_batch (kind, owner_sub, status);

-- 2.2 personal_file: gắn batch tạo ra tệp (truy vết + Story 3 AC1 progress),
-- và index phục vụ SUM(size) theo owner bằng index-only scan (cùng lý do
-- IDX_document_status_size ở warehouse-structure.md mục 2.2).
ALTER TABLE personal_file
    ADD COLUMN batch_id UUID REFERENCES upload_batch (id);
CREATE INDEX IDX_personal_file_owner_size ON personal_file (owner_sub) INCLUDE (size);
-- IDX_personal_file_owner_sub hiện có (V6) giữ nguyên, không xoá (đủ cho
-- các query owner-scoped khác, ví dụ listFiles).
