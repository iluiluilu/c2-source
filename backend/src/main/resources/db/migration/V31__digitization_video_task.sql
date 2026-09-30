-- digitization-pipeline-03-video-queue.md §2 - task 'video' (Quyết định 8):
-- thêm digitization_run task mới, cột read-model video_status + trigger đồng
-- bộ (worker ghi thẳng digitization_run, không qua
-- DigitizationRunLifecycleServiceImpl - xem javadoc class đó), và role DB
-- phạm vi hẹp cho worker đó.

-- 2.1 Cho phép task 'video' ở digitization_run (Quyết định 8).
ALTER TABLE digitization_run DROP CONSTRAINT digitization_run_task_check;
ALTER TABLE digitization_run ADD CONSTRAINT digitization_run_task_check
    CHECK (task IN ('ocr', 'stt', 'sed', 'video_summary', 'video'));

-- 2.2 Read-model: thêm cột trạng thái riêng cho task video (mục 0 điểm 3).
ALTER TABLE document_digitization_status
    ADD COLUMN video_status VARCHAR(20) NOT NULL DEFAULT 'not_applicable';

-- 2.3 Trigger đồng bộ digitization_run(task='video') -> document_digitization_status.
-- Cần thiết vì worker ghi thẳng SQL vào digitization_run, không qua
-- DigitizationRunLifecycleServiceImpl (điểm ghi Java duy nhất cho các task khác).
CREATE OR REPLACE FUNCTION sync_video_status_to_document() RETURNS TRIGGER AS $$
BEGIN
    IF NEW.task <> 'video' THEN
        RETURN NEW;
    END IF;
    INSERT INTO document_digitization_status (document_id, video_status, overall_status, updated_at)
    VALUES (
        NEW.document_id,
        CASE NEW.processing_status
            WHEN 'queued' THEN 'queued'
            WHEN 'running' THEN 'processing'
            WHEN 'succeeded' THEN 'confirmed' -- task video không qua FR-18, coi succeeded = confirmed
            WHEN 'failed_retryable' THEN 'processing'
            WHEN 'failed_permanent' THEN 'failed'
            WHEN 'cancelled' THEN 'failed'
            ELSE 'not_applicable'
        END,
        'not_applicable', -- overall_status: cột này do DigitizationRunLifecycleServiceImpl tính cho các task khác; trigger KHÔNG ghi đè, xem ghi chú §2 tech spec
        now()
    )
    ON CONFLICT (document_id) DO UPDATE
        SET video_status = EXCLUDED.video_status,
            updated_at = now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_sync_video_status
    AFTER INSERT OR UPDATE OF processing_status ON digitization_run
    FOR EACH ROW
    WHEN (NEW.task = 'video')
    EXECUTE FUNCTION sync_video_status_to_document();

-- 2.4 Role Postgres riêng, phạm vi hẹp cho video worker (mục 0 điểm 3, rủi ro mục 7).
-- SỬA 2026-09-14: bản nháp trước dùng cú pháp biến của psql (`:'...'`) - Flyway
-- chạy qua JDBC, KHÔNG hiểu cú pháp này, migration sẽ lỗi cú pháp SQL nếu giữ
-- nguyên. Đồng thời: Flyway lưu checksum nội dung migration đã áp dụng - nếu
-- nhúng mật khẩu vào migration (kể cả qua cơ chế placeholder của Flyway), lần sau
-- đổi/rotate mật khẩu sẽ làm checksum lệch, Flyway fail ở deploy tiếp theo.
-- => Role tạo KHÔNG có mật khẩu ở đây; đặt/rotate mật khẩu là một bước vận
-- hành RIÊNG, chạy NGOÀI Flyway (xem docs/00-overview/runbooks/video-worker-db-password.md).
CREATE ROLE digitization_video_worker LOGIN;
-- model_name/model_version/metrics bổ sung 2026-09-14 (xác nhận mục 8): bắt
-- buộc để không vi phạm NFR-09 (digitization-pipeline.md mục 3 - "Mọi bản ghi
-- kết quả gắn model_name + model_version để truy vết/chạy lại"). lease_expires_at
-- PHẢI được worker cập nhật định kỳ trong lúc chạy (không chỉ lúc xong) - hợp
-- đồng bắt buộc với team AI worker (mục 4.4, xác nhận mục 8), để
-- DigitizationRunLeaseReconciliationJob hiện có vẫn phát hiện được job treo.
GRANT UPDATE (processing_status, started_at, completed_at, last_error,
              attempt_count, lease_expires_at, worker_id, updated_at,
              model_name, model_version, metrics)
    ON digitization_run TO digitization_video_worker;
GRANT SELECT (id, document_id, task, processing_status) ON digitization_run
    TO digitization_video_worker;
-- KHÔNG cấp bất kỳ quyền nào trên content_segment/document/các bảng khác.
