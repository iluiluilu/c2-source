-- delete-restore-trash-02-xoa-cung-xung-dot.md §2.1.
-- DEV note: re-grepped db/migration right before writing this file (spec §0
-- instruction) - V29 (delete-restore-trash-01) is still the latest, so this
-- is V30 as planned, no further bump needed.

-- Máy trạng thái xóa cứng (Quyết định 5 SA): deleted -> purging -> purged.
-- 'active' = giá trị mặc định cho tài liệu chưa từng vào thùng rác.
ALTER TABLE document
    ADD COLUMN purge_state           VARCHAR(16) NOT NULL DEFAULT 'active',
    ADD COLUMN purged_at             TIMESTAMPTZ,
    ADD COLUMN purging_started_at    TIMESTAMPTZ,
    ADD COLUMN deleted_folder_path_ids UUID[];

UPDATE document SET purge_state = 'deleted' WHERE deleted_at IS NOT NULL;

ALTER TABLE document
    ADD CONSTRAINT CK_document_purge_state
    CHECK (purge_state IN ('active', 'deleted', 'purging', 'purged'));

-- Job purge quét theo (purge_state, purge_after); WHERE thu hẹp bằng
-- deleted_at IS NOT NULL để index nhỏ, chỉ chứa các dòng từng vào thùng rác.
CREATE INDEX IDX_document_purge_state_after
    ON document (purge_state, purge_after)
    WHERE deleted_at IS NOT NULL;

-- PurgeStuckRecoveryJob (§4.3): quét document đang 'purging' quá hạn.
CREATE INDEX IDX_document_purging_started_at
    ON document (purging_started_at)
    WHERE purge_state = 'purging';

-- Bảng khóa chuẩn của ShedLock (JdbcTemplateLockProvider mặc định).
CREATE TABLE shedlock (
    name       VARCHAR(64) PRIMARY KEY,
    lock_until TIMESTAMPTZ NOT NULL,
    locked_at  TIMESTAMPTZ NOT NULL,
    locked_by  VARCHAR(255) NOT NULL
);
