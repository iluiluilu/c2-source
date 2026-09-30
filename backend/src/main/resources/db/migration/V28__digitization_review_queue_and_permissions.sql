-- digitization-review-01-ocr.md mục 2. Chạy sau V27
-- (digitization-pipeline-02-ocr-stt-processing.md). Bổ sung cho
-- digitization-review (cả 3 file -01/-02/-03) - dùng chung 1 migration để
-- tránh 3 migration nhỏ lẻ chồng chéo cùng phạm vi "review".

-- Story 6: đánh dấu run in_review đã được cảnh báo "quá hạn xử lý" để
-- ReviewStaleAlertJob không ghi audit lặp lại mỗi chu kỳ quét cho cùng 1 run
-- (staleness tính từ first_edit_at đã có sẵn ở V25, không cần cột thời điểm
-- mới - chỉ cần cờ đã-cảnh-báo).
ALTER TABLE digitization_run ADD COLUMN stale_alerted BOOLEAN NOT NULL DEFAULT false;

-- Story 2 AC5: "xoá đoạn" cần một cách để UI/API biết segment đang ở trạng
-- thái đã xoá mà KHÔNG phải join segment_revision mới nhất (đúng tinh thần
-- "cột hiện hành" của Quyết định 3 SA - digitization-results-store.md §2.2
-- chỉ có deleted trên segment_revision, chưa có trên content_segment).
ALTER TABLE content_segment ADD COLUMN is_deleted BOOLEAN NOT NULL DEFAULT false;

-- Poll cho ReviewStaleAlertJob (Story 6).
CREATE INDEX IDX_digitization_run_stale_unalerted
    ON digitization_run (first_edit_at)
    WHERE review_state = 'in_review' AND stale_alerted = false;

-- RBAC (rbac-group-scope.md V12 mẫu: permission tĩnh + role_permission).
-- 'digitization_review.manage' dùng chung cho hàng chờ/xem/sửa/xác nhận
-- (Quyết định 5 SA: chỉ R1 + chính chủ - permission chỉ là điều kiện cần,
-- kiểm tra uploader_sub=current_user vẫn làm ở tầng service/query).
-- 'document.transfer_ownership' dùng ở digitization-review-03 (Story 7).
INSERT INTO permission (code, description) VALUES
    ('digitization_review.manage', 'Xem hàng chờ, sửa, xác nhận kết quả số hoá (OCR/STT) của tài liệu do chính mình nạp'),
    ('document.transfer_ownership', 'Chuyển quyền sở hữu (người nạp) của một tài liệu');

INSERT INTO role_permission (role_code, permission_code) VALUES
    ('CAN_BO', 'digitization_review.manage'),
    ('QUAN_TRI', 'document.transfer_ownership');
