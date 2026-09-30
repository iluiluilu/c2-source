-- digitization-pipeline-02-ocr-stt-processing.md mục 2. Runs after V26
-- (digitization-results-store.md) - Story 3 AC4: "chặn mốc thời gian vượt độ
-- dài tệp gốc ở tầng DB, không chỉ ứng dụng" - digitization-results-store.md
-- §4 đã nêu rõ đây là việc "ngoài phạm vi" của spec đó, để lại cho spec này.

-- digitization_source (V18, Pha 1) chưa có duration_ms - cần cho STT/audio.
ALTER TABLE digitization_source ADD COLUMN duration_ms INT;
-- Chỉ audio (component_type IN ('audio','original') với mime_type audio/*)
-- mới có giá trị; ảnh/PDF để NULL. Worker gửi giá trị này trong q.result
-- (mục 3), backend ghi vào digitization_source khi hoàn tất run STT.

-- Hàm + trigger: mọi INSERT/UPDATE content_segment có time_end_ms phải thoả
-- time_end_ms <= duration_ms của digitization_source tương ứng (qua
-- digitization_run.source_id), nếu duration_ms đã biết (NOT NULL). Áp dụng
-- cho cả cột current_time_end_ms (sửa bởi cán bộ ở digitization-review) để
-- không lọt qua đường sửa tay.
CREATE FUNCTION fn_check_segment_within_source_duration()
RETURNS TRIGGER AS $$
DECLARE
    v_duration_ms INT;
BEGIN
    IF NEW.time_end_ms IS NULL AND NEW.current_time_end_ms IS NULL THEN
        RETURN NEW;
    END IF;

    SELECT ds.duration_ms INTO v_duration_ms
    FROM digitization_run dr
    JOIN digitization_source ds ON ds.id = dr.source_id
    WHERE dr.id = NEW.run_id;

    IF v_duration_ms IS NOT NULL THEN
        IF NEW.time_end_ms IS NOT NULL AND NEW.time_end_ms > v_duration_ms THEN
            RAISE EXCEPTION
                'content_segment.time_end_ms (%) vuot qua digitization_source.duration_ms (%) cho run %',
                NEW.time_end_ms, v_duration_ms, NEW.run_id;
        END IF;
        IF NEW.current_time_end_ms IS NOT NULL AND NEW.current_time_end_ms > v_duration_ms THEN
            RAISE EXCEPTION
                'content_segment.current_time_end_ms (%) vuot qua digitization_source.duration_ms (%) cho run %',
                NEW.current_time_end_ms, v_duration_ms, NEW.run_id;
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_content_segment_within_duration
    BEFORE INSERT OR UPDATE ON content_segment
    FOR EACH ROW EXECUTE FUNCTION fn_check_segment_within_source_duration();

-- Ghi chú: trigger chấp nhận duration_ms IS NULL (bỏ qua kiểm tra) - trường
-- hợp OCR (không có mốc thời gian) hoặc run STT trước khi cột này có giá trị.
-- Không hồi tố dữ liệu cũ (không có dữ liệu cũ ở thời điểm viết migration).
