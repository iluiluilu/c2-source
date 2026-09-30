-- digitization-pipeline-05-video-review.md: task 'video' đi cùng luồng với
-- ocr/stt - worker gửi kết quả qua q.result, backend
-- (DigitizationRunLifecycleServiceImpl) ghi digitization_run/content_segment/
-- document_digitization_status, run qua FR-18 (hàng chờ Số hoá) như ocr/stt.
-- Dữ liệu run 'video' cũ (ghi thẳng SQL theo V31) giữ nguyên, không backfill.

-- 1. content_segment của run video dùng content_source = 'video'.
--    DROP IF EXISTS + ADD: stg đã được thêm 'video' thủ công ngoài Flyway.
ALTER TABLE content_segment DROP CONSTRAINT IF EXISTS content_segment_content_source_check;
ALTER TABLE content_segment ADD CONSTRAINT content_segment_content_source_check
    CHECK (content_source IN ('ocr', 'stt', 'sed', 'summary', 'video'));

-- 2. Bỏ trigger V31 §2.3: video_status giờ do Java ghi (upsertTaskStatus), và
--    trigger cũ map succeeded -> confirmed (bỏ qua FR-18) - sai với luồng mới.
DROP TRIGGER IF EXISTS trg_sync_video_status ON digitization_run;
DROP FUNCTION IF EXISTS sync_video_status_to_document();
