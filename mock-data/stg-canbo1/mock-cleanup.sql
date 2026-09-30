-- Gỡ toàn bộ mock dữ liệu số hoá (4 tài liệu + nhóm + thư mục) khỏi stg.
-- Không đụng tới dữ liệu thật: mọi id mock đều có tiền tố 00000000-0000-4000-8000-.
-- Lưu ý: file trên SeaweedFS phải xoá riêng (xem README).
BEGIN;

DELETE FROM segment_revision WHERE segment_id IN
    (SELECT id FROM content_segment WHERE document_id::text LIKE '00000000-0000-4000-8000-0000000000a%');
DELETE FROM content_segment        WHERE document_id::text LIKE '00000000-0000-4000-8000-0000000000a%';
DELETE FROM sound_event            WHERE document_id::text LIKE '00000000-0000-4000-8000-0000000000a%';
DELETE FROM digitization_run       WHERE document_id::text LIKE '00000000-0000-4000-8000-0000000000a%';
DELETE FROM digitization_batch     WHERE id::text LIKE '00000000-0000-4000-8000-0000000000d%';
DELETE FROM digitization_source    WHERE document_id::text LIKE '00000000-0000-4000-8000-0000000000a%';
DELETE FROM document_digitization_status WHERE document_id::text LIKE '00000000-0000-4000-8000-0000000000a%';
DELETE FROM document_group_share   WHERE document_id::text LIKE '00000000-0000-4000-8000-0000000000a%';
DELETE FROM document_tag           WHERE document_id::text LIKE '00000000-0000-4000-8000-0000000000a%';
DELETE FROM document_note          WHERE document_id::text LIKE '00000000-0000-4000-8000-0000000000a%';
DELETE FROM document               WHERE id::text LIKE '00000000-0000-4000-8000-0000000000a%';

DELETE FROM warehouse_folder WHERE id::text LIKE '00000000-0000-4000-8000-0000000000f%';
DELETE FROM group_member     WHERE group_id::text IN
    ('00000000-0000-4000-8000-000000000001','00000000-0000-4000-8000-000000000002');
DELETE FROM "group"          WHERE id::text IN
    ('00000000-0000-4000-8000-000000000001','00000000-0000-4000-8000-000000000002');
DELETE FROM tag WHERE kind = 'topic'
  AND label IN ('Hợp đồng','Họp giao ban','Báo cáo định kỳ','Tuyển dụng');

-- outbox_event của mock (nếu chưa xử lý thì bỏ luôn, đã xử lý thì cũng xoá cho sạch)
DELETE FROM outbox_event WHERE payload->>'documentId' LIKE '00000000-0000-4000-8000-0000000000a%';

COMMIT;
