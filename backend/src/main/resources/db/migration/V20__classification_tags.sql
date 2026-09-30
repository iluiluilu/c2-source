-- classification-notes-01-tags.md §2. Runs after V19 (outbox_event must
-- already exist since this migration does not create it - see V19's header).
-- Đã CHỐT (Câu hỏi mở #1, mục 8 của spec): xoá thẳng dữ liệu 2 kind cũ
-- (unit/secrecy) không cần export CSV/kiểm kê trước xoá (dữ liệu demo,
-- tương tự trường hợp `casefiles` ở V13).

-- 1. Xoá liên kết + nhãn của 2 kind bị loại bỏ.
DELETE FROM document_tag USING tag
    WHERE document_tag.tag_id = tag.id AND tag.kind IN ('unit', 'secrecy');
DELETE FROM tag WHERE kind IN ('unit', 'secrecy');

-- 2. Đổi 'doc-type' -> 'doc_type' cho nhất quán (Quyết định 1).
UPDATE tag SET kind = 'doc_type' WHERE kind = 'doc-type';

-- 3. Thu hẹp ràng buộc kind còn 2 giá trị + thêm active cho vòng đời topic.
-- (Chạy trước bước 2b để cột `active` đã tồn tại khi seed insert bên dưới.)
ALTER TABLE tag ADD COLUMN active BOOLEAN NOT NULL DEFAULT true;
ALTER TABLE tag ADD CONSTRAINT CHK_tag_kind CHECK (kind IN ('topic', 'doc_type'));

-- 2b. Seed 4 nhãn doc_type cố định (Câu hỏi mở #2, mục 8: ánh xạ MIME ->
-- doc_type dùng allowed_file_format.category, TEXT_DOC/IMAGE/AUDIO/VIDEO ->
-- 4 nhãn tiếng Việt cố định bên dưới - xem DocTypeAssignmentService). Chạy
-- trước khi đổi ràng buộc unique (kind, label) -> (kind, lower(label)) ở
-- bước 4 nên vẫn dùng ON CONFLICT trên constraint cũ UQ_tag_kind_label.
INSERT INTO tag (kind, label, created_by_sub, created_by_username, created_at, active)
VALUES
    ('doc_type', 'văn bản', 'system', 'system', now(), true),
    ('doc_type', 'hình ảnh', 'system', 'system', now(), true),
    ('doc_type', 'âm thanh', 'system', 'system', now(), true),
    ('doc_type', 'video', 'system', 'system', now(), true)
ON CONFLICT ON CONSTRAINT UQ_tag_kind_label DO NOTHING;

-- 4. Chuẩn hoá whitespace + xử lý trùng lặp (kind, lower(label)) hiện có
-- trước khi tạo unique index case-insensitive (Rủi ro mục 6 SA - chống phân
-- mảnh danh mục). Cách xử lý trùng lặp (không phải câu hỏi mở bắt buộc, DEV
-- tự quyết định khi implement - xem spec mục 2): giữ bản ghi cũ nhất theo
-- (created_at, id) làm "canonical", remap document_tag của các bản trùng
-- sang canonical (bỏ liên kết nếu tài liệu đã có sẵn canonical, tránh vi
-- phạm PK (document_id, tag_id)), rồi xoá các bản trùng.
UPDATE tag SET label = btrim(regexp_replace(label, '\s+', ' ', 'g'));

WITH ranked AS (
    SELECT id, kind, lower(label) AS norm_label,
           row_number() OVER (PARTITION BY kind, lower(label) ORDER BY created_at, id) AS rn
    FROM tag
), canonical AS (
    SELECT kind, norm_label, id AS canonical_id FROM ranked WHERE rn = 1
), losers AS (
    SELECT r.id AS loser_id, c.canonical_id
    FROM ranked r JOIN canonical c ON c.kind = r.kind AND c.norm_label = r.norm_label
    WHERE r.rn > 1
)
DELETE FROM document_tag dt
USING losers l
WHERE dt.tag_id = l.loser_id
  AND EXISTS (
      SELECT 1 FROM document_tag dt2
      WHERE dt2.document_id = dt.document_id AND dt2.tag_id = l.canonical_id
  );

WITH ranked AS (
    SELECT id, kind, lower(label) AS norm_label,
           row_number() OVER (PARTITION BY kind, lower(label) ORDER BY created_at, id) AS rn
    FROM tag
), canonical AS (
    SELECT kind, norm_label, id AS canonical_id FROM ranked WHERE rn = 1
), losers AS (
    SELECT r.id AS loser_id, c.canonical_id
    FROM ranked r JOIN canonical c ON c.kind = r.kind AND c.norm_label = r.norm_label
    WHERE r.rn > 1
)
UPDATE document_tag dt SET tag_id = l.canonical_id
FROM losers l
WHERE dt.tag_id = l.loser_id;

WITH ranked AS (
    SELECT id, kind, lower(label) AS norm_label,
           row_number() OVER (PARTITION BY kind, lower(label) ORDER BY created_at, id) AS rn
    FROM tag
)
DELETE FROM tag WHERE id IN (SELECT id FROM ranked WHERE rn > 1);

ALTER TABLE tag DROP CONSTRAINT UQ_tag_kind_label;
CREATE UNIQUE INDEX UQ_tag_kind_lower_label ON tag (kind, lower(label));

-- 5. document_tag: denormalize tag_kind, ép đúng 1 doc_type/tài liệu.
ALTER TABLE document_tag ADD COLUMN tag_kind VARCHAR(16);
UPDATE document_tag dt SET tag_kind = t.kind FROM tag t WHERE t.id = dt.tag_id;
ALTER TABLE document_tag ALTER COLUMN tag_kind SET NOT NULL;
CREATE UNIQUE INDEX UQ_document_tag_one_doc_type
    ON document_tag (document_id)
    WHERE tag_kind = 'doc_type';

-- 6. Outbox cho document.classification.changed dùng bảng generic
-- outbox_event tạo ở V19 (xem header file này + V19) - không tạo bảng riêng.

-- 7. Backfill doc_type cho tài liệu cũ: thực hiện ở tầng ứng dụng
-- (DocTypeBackfillRunner, Task 7) chứ không trong migration Flyway này, để
-- tránh khoá bảng document/document_tag lâu khi chạy trên dữ liệu lớn (Rủi
-- ro kỹ thuật mục 7 của spec).
