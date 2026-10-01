-- folder-upload-display-name (SPEC-folder-upload-display-name): display_name + relative_path,
-- bo dedup theo original_name, unique theo (folder, relative_path, lower(display_name)).
-- Chua go live: du lieu dev/stg (neu co) duoc backfill display_name = original_name, cac ban trung
-- (truoc day khong bi chan) duoc them hau to 8 ky tu cua id de unique moi tao duoc.

-- ---- document ----
ALTER TABLE document ADD COLUMN display_name VARCHAR(512);
UPDATE document SET display_name = original_name WHERE display_name IS NULL;

UPDATE document d
SET display_name = left(d.original_name, 500) || '_' || substr(replace(d.id::text, '-', ''), 1, 8)
FROM (
    SELECT id,
           row_number() OVER (
               PARTITION BY COALESCE(folder_id, '00000000-0000-0000-0000-000000000000'::uuid),
                            relative_path, lower(display_name)
               ORDER BY created_at, id) AS rn
    FROM document
    WHERE deleted_at IS NULL AND status <> 'rejected'
) x
WHERE d.id = x.id AND x.rn > 1;

ALTER TABLE document ALTER COLUMN display_name SET NOT NULL;

-- V10: la UNIQUE INDEX (khong phai constraint).
DROP INDEX IF EXISTS UQ_document_folder_relpath_name_clean;

-- Dau phan cach '/' (chr(0) khong hop le trong Postgres): display_name cam '/' nen relative_path || '/' || name
-- khong bao gio nhap nhang. md5 tranh vuot gioi han btree (R14). Moi status chiem ten (Q3), tru 'rejected' (ban ghi bang chung,
-- khong co noi dung, khong the "sua" bang cach doi ten) va tep trong thung rac.
CREATE UNIQUE INDEX UQ_document_display_name_alive ON document
    (COALESCE(folder_id, '00000000-0000-0000-0000-000000000000'::uuid),
     md5(relative_path || '/' || lower(display_name)))
    WHERE deleted_at IS NULL AND status <> 'rejected';

-- ---- personal_file ----
ALTER TABLE personal_file ADD COLUMN relative_path VARCHAR(1024) NOT NULL DEFAULT '';
ALTER TABLE personal_file ADD COLUMN display_name VARCHAR(512);
UPDATE personal_file SET display_name = original_name WHERE display_name IS NULL;

UPDATE personal_file f
SET display_name = left(f.original_name, 500) || '_' || substr(replace(f.id::text, '-', ''), 1, 8)
FROM (
    SELECT id,
           row_number() OVER (
               PARTITION BY owner_sub,
                            COALESCE(folder_id, '00000000-0000-0000-0000-000000000000'::uuid),
                            relative_path, lower(display_name)
               ORDER BY created_at, id) AS rn
    FROM personal_file
) x
WHERE f.id = x.id AND x.rn > 1;

ALTER TABLE personal_file ALTER COLUMN display_name SET NOT NULL;

CREATE UNIQUE INDEX UQ_personal_file_display_name ON personal_file
    (owner_sub,
     COALESCE(folder_id, '00000000-0000-0000-0000-000000000000'::uuid),
     md5(relative_path || '/' || lower(display_name)));

-- ---- upload_session / personal_ingest_item ----
ALTER TABLE upload_session ADD COLUMN display_name VARCHAR(512) NULL;
ALTER TABLE personal_ingest_item ADD COLUMN display_name VARCHAR(512) NULL;
ALTER TABLE personal_ingest_item ADD COLUMN name_retry_count INT NOT NULL DEFAULT 0;
