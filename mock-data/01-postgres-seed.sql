-- Mock data: 1 tài liệu OCR (PDF) + 1 tài liệu Audio (MP3), coi như đã đi hết
-- pipeline số hoá và đã được duyệt (FR-18), đã chia sẻ vào nhóm.
-- Chạy trên DB đã migrate đủ tới V31. Xem mock-data/README.md để biết cách dùng.

BEGIN;

-- =========================================================================
-- 1. Nhóm (group) + thành viên
-- =========================================================================

INSERT INTO "group" (id, name, description, owner_sub, created_at, updated_at) VALUES
    ('00000000-0000-4000-8000-000000000001', 'Phòng Hành chính - Tổng hợp',
     'Nhóm minh hoạ cho mock data', 'sub-nguyen-van-an', now(), now()),
    ('00000000-0000-4000-8000-000000000002', 'Ban Giám đốc',
     'Nhóm minh hoạ cho mock data', 'sub-pham-lan-huong', now(), now());

INSERT INTO group_member (group_id, user_sub, role_in_group, joined_at) VALUES
    ('00000000-0000-4000-8000-000000000001', 'sub-nguyen-van-an', 'owner', now()),
    ('00000000-0000-4000-8000-000000000001', 'sub-tran-thi-binh', 'member', now()),
    ('00000000-0000-4000-8000-000000000002', 'sub-pham-lan-huong', 'owner', now()),
    ('00000000-0000-4000-8000-000000000002', 'sub-nguyen-van-an', 'member', now());

-- =========================================================================
-- 2. Thư mục kho (warehouse_folder)
-- =========================================================================

INSERT INTO warehouse_folder (id, name, parent_id, created_at, updated_at) VALUES
    ('00000000-0000-4000-8000-0000000000f0', 'Kho số hoá minh hoạ', NULL, now(), now());
INSERT INTO warehouse_folder (id, name, parent_id, created_at, updated_at) VALUES
    ('00000000-0000-4000-8000-0000000000f1', 'Hợp đồng',
     '00000000-0000-4000-8000-0000000000f0', now(), now()),
    ('00000000-0000-4000-8000-0000000000f2', 'Ghi âm cuộc họp',
     '00000000-0000-4000-8000-0000000000f0', now(), now());

-- =========================================================================
-- 3. Tag (doc_type đã seed sẵn ở V20 - chỉ thêm 2 nhãn topic mới)
-- =========================================================================

INSERT INTO tag (kind, label, created_by_sub, created_by_username, created_at, active)
VALUES
    ('topic', 'Tài chính', 'sub-nguyen-van-an', 'nguyen.van.an', now(), true),
    ('topic', 'Nội bộ', 'sub-pham-lan-huong', 'pham.lan.huong', now(), true)
ON CONFLICT (kind, lower(label)) DO NOTHING;

-- =========================================================================
-- 4. Tài liệu 1 - OCR (PDF)
-- =========================================================================

INSERT INTO document (
    id, original_name, mime_type, size, sha256, storage_key, folder_id,
    uploader_sub, uploader_username, status, scan_result, created_at
) VALUES (
    '00000000-0000-4000-8000-0000000000a1',
    'Hợp đồng thuê nhà số 07-2026-HĐTN.pdf',
    'application/pdf',
    245678,
    'a1a1a1a1b2b2b2b2c3c3c3c3d4d4d4d4e5e5e5e5f6f6f6f6a7a7a7a7b8b8b8b8',
    'documents/00000000-0000-4000-8000-0000000000b1',
    '00000000-0000-4000-8000-0000000000f1',
    'sub-nguyen-van-an', 'nguyen.van.an',
    'clean',
    '{"engine":"clamav","result":"clean","scannedAt":"2026-09-10T08:15:00+07:00"}'::jsonb,
    '2026-09-10T08:15:00+07:00'
);

INSERT INTO document_tag (document_id, tag_id, assigned_by_sub, assigned_by_username, assigned_at)
SELECT '00000000-0000-4000-8000-0000000000a1', id, 'sub-nguyen-van-an', 'nguyen.van.an', now()
FROM tag WHERE kind = 'doc_type' AND label = 'văn bản';

INSERT INTO document_tag (document_id, tag_id, assigned_by_sub, assigned_by_username, assigned_at)
SELECT '00000000-0000-4000-8000-0000000000a1', id, 'sub-nguyen-van-an', 'nguyen.van.an', now()
FROM tag WHERE kind = 'topic' AND label = 'Tài chính';

INSERT INTO document_group_share (document_id, group_id, shared_by_sub, shared_at) VALUES
    ('00000000-0000-4000-8000-0000000000a1', '00000000-0000-4000-8000-000000000001',
     'sub-nguyen-van-an', now());

INSERT INTO digitization_source (
    id, document_id, component_type, file_path, mime_type,
    dispatchable, dispatch_status, dispatch_attempt_count, dispatched_at, created_at, updated_at
) VALUES (
    '00000000-0000-4000-8000-0000000000a1', '00000000-0000-4000-8000-0000000000a1',
    'original', 'documents/documents/00000000-0000-4000-8000-0000000000b1', 'application/pdf',
    true, 'published', 1, '2026-09-10T08:15:05+07:00', '2026-09-10T08:15:00+07:00', '2026-09-10T08:15:05+07:00'
);

INSERT INTO digitization_run (
    id, document_id, source_id, task, batch_id,
    processing_status, attempt_count, lease_expires_at, worker_id, last_error,
    model_name, model_version, metrics, started_at, completed_at, raw_output_path,
    input_quality_flag, output_quality_flag, low_confidence_count, segment_count, auto_confirm_blocked,
    review_state, review_deadline_at, confirm_source, confirmed_by, confirmed_at,
    first_viewed_at, first_viewed_by, edited_segment_count, first_edit_at, depends_on_run_id,
    created_at, updated_at
) VALUES (
    '00000000-0000-4000-8000-0000000000c1', '00000000-0000-4000-8000-0000000000a1',
    '00000000-0000-4000-8000-0000000000a1', 'ocr', NULL,
    'succeeded', 1, NULL, 'ocr-worker-01', NULL,
    'PaddleOCR+VietOCR', 'v2.7-vi', '{"avgConfidence":0.94,"pages":2}'::jsonb,
    '2026-09-10T08:15:10+07:00', '2026-09-10T08:16:40+07:00',
    'derived/00000000-0000-4000-8000-0000000000a1/00000000-0000-4000-8000-0000000000c1.json',
    'ok', 'ok', 0, 4, false,
    'confirmed', '2026-09-11T08:16:40+07:00', 'manual_edited', 'sub-pham-lan-huong', '2026-09-10T10:05:00+07:00',
    '2026-09-10T09:50:00+07:00', 'sub-pham-lan-huong', 1, '2026-09-10T09:55:00+07:00', NULL,
    '2026-09-10T08:16:40+07:00', '2026-09-10T10:05:00+07:00'
);

INSERT INTO content_segment (
    id, run_id, document_id, segment_index, content_source, origin,
    page_no, bbox_polygon, reading_order, line_from, line_to,
    time_start_ms, time_end_ms, speaker_label, source_segment_ids,
    ai_text, ai_time_start_ms, ai_time_end_ms,
    current_text, current_time_start_ms, current_time_end_ms, current_revision_no, is_edited,
    confidence, created_at, updated_at
) VALUES
    ('00000000-0000-4000-8000-0000000000d1', '00000000-0000-4000-8000-0000000000c1',
     '00000000-0000-4000-8000-0000000000a1', 0, 'ocr', 'ai',
     1, '[[0.08,0.08],[0.92,0.08],[0.92,0.14],[0.08,0.14]]'::jsonb, 0, 1, 2,
     NULL, NULL, NULL, NULL,
     E'HỢP ĐỒNG THUÊ NHÀ\nSố: 07/2026/HĐTN', NULL, NULL,
     E'HỢP ĐỒNG THUÊ NHÀ\nSố: 07/2026/HĐTN', NULL, NULL, 0, false,
     0.970, '2026-09-10T08:16:40+07:00', '2026-09-10T08:16:40+07:00'),

    ('00000000-0000-4000-8000-0000000000d2', '00000000-0000-4000-8000-0000000000c1',
     '00000000-0000-4000-8000-0000000000a1', 1, 'ocr', 'ai',
     1, '[[0.08,0.20],[0.92,0.20],[0.92,0.28],[0.08,0.28]]'::jsonb, 1, 5, 9,
     NULL, NULL, NULL, NULL,
     'Bên cho thuê: Nguyễn Văn An, CCCD 0123456789O, thường trú tại Hà Nội.', NULL, NULL,
     'Bên cho thuê: Nguyễn Văn An, CCCD 012345678901, thường trú tại Hà Nội.', NULL, NULL, 1, true,
     0.910, '2026-09-10T08:16:40+07:00', '2026-09-10T09:55:00+07:00'),

    ('00000000-0000-4000-8000-0000000000d3', '00000000-0000-4000-8000-0000000000c1',
     '00000000-0000-4000-8000-0000000000a1', 2, 'ocr', 'ai',
     1, '[[0.08,0.32],[0.92,0.32],[0.92,0.40],[0.08,0.40]]'::jsonb, 2, 11, 15,
     NULL, NULL, NULL, NULL,
     'Bên thuê: Trần Thị Bình, CCCD 0987654321, thường trú tại Hà Nội.', NULL, NULL,
     'Bên thuê: Trần Thị Bình, CCCD 0987654321, thường trú tại Hà Nội.', NULL, NULL, 0, false,
     0.950, '2026-09-10T08:16:40+07:00', '2026-09-10T08:16:40+07:00'),

    ('00000000-0000-4000-8000-0000000000d4', '00000000-0000-4000-8000-0000000000c1',
     '00000000-0000-4000-8000-0000000000a1', 3, 'ocr', 'ai',
     2, '[[0.08,0.10],[0.92,0.10],[0.92,0.20],[0.08,0.20]]'::jsonb, 3, 1, 6,
     NULL, NULL, NULL, NULL,
     E'Điều 3. Giá thuê và phương thức thanh toán\nGiá thuê: 12.000.000 đồng/tháng, thanh toán trước ngày 05 hàng tháng.',
     NULL, NULL,
     E'Điều 3. Giá thuê và phương thức thanh toán\nGiá thuê: 12.000.000 đồng/tháng, thanh toán trước ngày 05 hàng tháng.',
     NULL, NULL, 0, false,
     0.930, '2026-09-10T08:16:40+07:00', '2026-09-10T08:16:40+07:00');

INSERT INTO segment_revision (
    id, segment_id, revision_no, text, time_start_ms, time_end_ms, deleted, edited_by, edited_at, reason
) VALUES (
    '00000000-0000-4000-8000-0000000000e1', '00000000-0000-4000-8000-0000000000d2', 1,
    'Bên cho thuê: Nguyễn Văn An, CCCD 012345678901, thường trú tại Hà Nội.', NULL, NULL, false,
    'sub-pham-lan-huong', '2026-09-10T09:55:00+07:00', 'Sửa lỗi OCR nhận nhầm chữ O thành số 0 trong CCCD'
);

INSERT INTO document_digitization_status (
    document_id, ocr_status, stt_status, sed_status, video_summary_status, video_status, overall_status, updated_at
) VALUES (
    '00000000-0000-4000-8000-0000000000a1', 'confirmed', 'not_applicable', 'not_applicable',
    'not_applicable', 'not_applicable', 'confirmed', '2026-09-10T10:05:00+07:00'
);

INSERT INTO outbox_event (event_type, payload, created_at, processed_at, attempts) VALUES
    ('document.ingested',
     '{"documentId":"00000000-0000-4000-8000-0000000000a1"}'::jsonb,
     '2026-09-10T08:15:01+07:00', '2026-09-10T08:15:03+07:00', 0),
    ('review.confirmed',
     '{"documentId":"00000000-0000-4000-8000-0000000000a1","runId":"00000000-0000-4000-8000-0000000000c1","task":"ocr"}'::jsonb,
     '2026-09-10T10:05:00+07:00', '2026-09-10T10:05:02+07:00', 0);

-- =========================================================================
-- 5. Tài liệu 2 - Audio (MP3, STT)
-- =========================================================================

INSERT INTO document (
    id, original_name, mime_type, size, sha256, storage_key, folder_id,
    uploader_sub, uploader_username, status, scan_result, created_at
) VALUES (
    '00000000-0000-4000-8000-0000000000a2',
    'Ghi âm cuộc họp giao ban 15-09-2026.mp3',
    'audio/mpeg',
    5824512,
    'b2b2b2b2c3c3c3c3d4d4d4d4e5e5e5e5f6f6f6f6a7a7a7a7b8b8b8b8c9c9c9c9',
    'documents/00000000-0000-4000-8000-0000000000b2',
    '00000000-0000-4000-8000-0000000000f2',
    'sub-pham-lan-huong', 'pham.lan.huong',
    'clean',
    '{"engine":"clamav","result":"clean","scannedAt":"2026-09-15T14:02:00+07:00"}'::jsonb,
    '2026-09-15T14:02:00+07:00'
);

INSERT INTO document_tag (document_id, tag_id, assigned_by_sub, assigned_by_username, assigned_at)
SELECT '00000000-0000-4000-8000-0000000000a2', id, 'sub-pham-lan-huong', 'pham.lan.huong', now()
FROM tag WHERE kind = 'doc_type' AND label = 'âm thanh';

INSERT INTO document_tag (document_id, tag_id, assigned_by_sub, assigned_by_username, assigned_at)
SELECT '00000000-0000-4000-8000-0000000000a2', id, 'sub-pham-lan-huong', 'pham.lan.huong', now()
FROM tag WHERE kind = 'topic' AND label = 'Nội bộ';

INSERT INTO document_group_share (document_id, group_id, shared_by_sub, shared_at) VALUES
    ('00000000-0000-4000-8000-0000000000a2', '00000000-0000-4000-8000-000000000002',
     'sub-pham-lan-huong', now());

INSERT INTO digitization_source (
    id, document_id, component_type, file_path, mime_type,
    dispatchable, dispatch_status, dispatch_attempt_count, dispatched_at, created_at, updated_at
) VALUES (
    '00000000-0000-4000-8000-0000000000a2', '00000000-0000-4000-8000-0000000000a2',
    'original', 'documents/documents/00000000-0000-4000-8000-0000000000b2', 'audio/mpeg',
    true, 'published', 1, '2026-09-15T14:02:05+07:00', '2026-09-15T14:02:00+07:00', '2026-09-15T14:02:05+07:00'
);

INSERT INTO digitization_run (
    id, document_id, source_id, task, batch_id,
    processing_status, attempt_count, lease_expires_at, worker_id, last_error,
    model_name, model_version, metrics, started_at, completed_at, raw_output_path,
    input_quality_flag, output_quality_flag, low_confidence_count, segment_count, auto_confirm_blocked,
    review_state, review_deadline_at, confirm_source, confirmed_by, confirmed_at,
    first_viewed_at, first_viewed_by, edited_segment_count, first_edit_at, depends_on_run_id,
    created_at, updated_at
) VALUES (
    '00000000-0000-4000-8000-0000000000c2', '00000000-0000-4000-8000-0000000000a2',
    '00000000-0000-4000-8000-0000000000a2', 'stt', NULL,
    'succeeded', 1, NULL, 'stt-worker-01', NULL,
    'PhoWhisper', 'medium-v1', '{"avgConfidence":0.90,"durationMs":186000}'::jsonb,
    '2026-09-15T14:02:10+07:00', '2026-09-15T14:05:40+07:00',
    'derived/00000000-0000-4000-8000-0000000000a2/00000000-0000-4000-8000-0000000000c2.json',
    'ok', 'ok', 0, 3, false,
    'confirmed', '2026-09-16T14:05:40+07:00', 'manual_unchanged', 'sub-nguyen-van-an', '2026-09-15T15:30:00+07:00',
    '2026-09-15T15:20:00+07:00', 'sub-nguyen-van-an', 0, NULL, NULL,
    '2026-09-15T14:05:40+07:00', '2026-09-15T15:30:00+07:00'
);

INSERT INTO content_segment (
    id, run_id, document_id, segment_index, content_source, origin,
    page_no, bbox_polygon, reading_order, line_from, line_to,
    time_start_ms, time_end_ms, speaker_label, source_segment_ids,
    ai_text, ai_time_start_ms, ai_time_end_ms,
    current_text, current_time_start_ms, current_time_end_ms, current_revision_no, is_edited,
    confidence, created_at, updated_at
) VALUES
    ('00000000-0000-4000-8000-0000000000d5', '00000000-0000-4000-8000-0000000000c2',
     '00000000-0000-4000-8000-0000000000a2', 0, 'stt', 'ai',
     NULL, NULL, NULL, NULL, NULL,
     0, 8200, NULL, NULL,
     'Xin chào các đồng chí, chúng ta bắt đầu cuộc họp giao ban tuần này.', 0, 8200,
     'Xin chào các đồng chí, chúng ta bắt đầu cuộc họp giao ban tuần này.', 0, 8200, 0, false,
     0.930, '2026-09-15T14:05:40+07:00', '2026-09-15T14:05:40+07:00'),

    ('00000000-0000-4000-8000-0000000000d6', '00000000-0000-4000-8000-0000000000c2',
     '00000000-0000-4000-8000-0000000000a2', 1, 'stt', 'ai',
     NULL, NULL, NULL, NULL, NULL,
     8200, 25400, NULL, NULL,
     'Về tiến độ số hoá tài liệu, phòng hành chính đã hoàn thành tám mươi phần trăm khối lượng được giao trong quý ba.',
     8200, 25400,
     'Về tiến độ số hoá tài liệu, phòng hành chính đã hoàn thành tám mươi phần trăm khối lượng được giao trong quý ba.',
     8200, 25400, 0, false,
     0.880, '2026-09-15T14:05:40+07:00', '2026-09-15T14:05:40+07:00'),

    ('00000000-0000-4000-8000-0000000000d7', '00000000-0000-4000-8000-0000000000c2',
     '00000000-0000-4000-8000-0000000000a2', 2, 'stt', 'ai',
     NULL, NULL, NULL, NULL, NULL,
     25400, 41000, NULL, NULL,
     'Đề nghị các đơn vị rà soát lại tài liệu chưa gắn nhãn phân loại trước ngày ba mươi tháng chín.', 25400, 41000,
     'Đề nghị các đơn vị rà soát lại tài liệu chưa gắn nhãn phân loại trước ngày ba mươi tháng chín.', 25400, 41000, 0, false,
     0.900, '2026-09-15T14:05:40+07:00', '2026-09-15T14:05:40+07:00');

INSERT INTO document_digitization_status (
    document_id, ocr_status, stt_status, sed_status, video_summary_status, video_status, overall_status, updated_at
) VALUES (
    '00000000-0000-4000-8000-0000000000a2', 'not_applicable', 'confirmed', 'not_applicable',
    'not_applicable', 'not_applicable', 'confirmed', '2026-09-15T15:30:00+07:00'
);

INSERT INTO outbox_event (event_type, payload, created_at, processed_at, attempts) VALUES
    ('document.ingested',
     '{"documentId":"00000000-0000-4000-8000-0000000000a2"}'::jsonb,
     '2026-09-15T14:02:01+07:00', '2026-09-15T14:02:03+07:00', 0),
    ('review.confirmed',
     '{"documentId":"00000000-0000-4000-8000-0000000000a2","runId":"00000000-0000-4000-8000-0000000000c2","task":"stt"}'::jsonb,
     '2026-09-15T15:30:00+07:00', '2026-09-15T15:30:02+07:00', 0);

COMMIT;
