-- export-reporting AC-19 / AC-27 [ĐX]: đo tải RIÊNG (không chặn nghiệm thu chức năng).
-- CHỈ chạy trên DB thử nghiệm (không phải stg/prod có dữ liệu thật). Ghi kết quả EXPLAIN vào PR.
--   psql -h <host> -U aismartstore -d aismartstore -f backend/scripts/report-perf-seed.sql
BEGIN;

-- 1 thư mục + 1M tài liệu rải đều 24 tháng, 5 nhóm MIME, 50 người nạp, ~5% xoá mềm.
INSERT INTO warehouse_folder (id, name, path_ids, depth, created_at, updated_at)
VALUES ('00000000-0000-0000-0000-00000000f001', 'perf-root', '{}', 1, now(), now())
ON CONFLICT DO NOTHING;

INSERT INTO document (id, original_name, display_name, mime_type, size, sha256, storage_key, folder_id,
                      uploader_sub, uploader_username, status, created_at, deleted_at, relative_path)
SELECT gen_random_uuid(), 'perf-' || g, 'perf-' || g,
       (ARRAY['application/pdf','image/png','audio/mpeg','video/mp4','application/zip'])[1 + g % 5],
       (g % 5000000) + 1000, md5(g::text) || md5((g + 1)::text), 'perf/' || g,
       '00000000-0000-0000-0000-00000000f001'::uuid,
       '00000000-0000-0000-0000-' || lpad((g % 50)::text, 12, '0'), 'perf-user-' || (g % 50),
       'clean', now() - (g % 730) * interval '1 day' - (g % 86400) * interval '1 second',
       CASE WHEN g % 20 = 0 THEN now() END, ''
FROM generate_series(1, 1000000) AS g;
COMMIT;
ANALYZE document;

-- Truy vấn lõi (tương đương SQL do ReportQueryService sinh ra: scope = chủ sở hữu, status='clean', chưa xoá).
-- Thay :sub bằng một uploader_sub ở trên, ví dụ '00000000-0000-0000-0000-000000000007'.

-- B1: đầu kỳ / nạp mới / cuối kỳ (12 tháng)
EXPLAIN (ANALYZE, BUFFERS)
SELECT count(DISTINCT CASE WHEN created_at < now() - interval '12 months' THEN id END),
       count(DISTINCT CASE WHEN created_at >= now() - interval '12 months' THEN id END),
       count(DISTINCT id)
FROM document
WHERE deleted_at IS NULL AND status = 'clean'
  AND uploader_sub = '00000000-0000-0000-0000-000000000007'
  AND created_at < now();

-- B2: theo nhóm MIME trên tồn kho cuối kỳ
EXPLAIN (ANALYZE, BUFFERS)
SELECT mime_type, count(DISTINCT id), sum(size) FROM document
WHERE deleted_at IS NULL AND status = 'clean' AND uploader_sub = '00000000-0000-0000-0000-000000000007'
  AND created_at < now() GROUP BY mime_type;

-- B3: nạp mới theo tháng (timezone ICT)
EXPLAIN (ANALYZE, BUFFERS)
SELECT to_char(date_trunc('month', timezone('Asia/Ho_Chi_Minh', created_at)), 'YYYY-MM-DD'), mime_type,
       count(DISTINCT id), sum(size)
FROM document
WHERE deleted_at IS NULL AND status = 'clean' AND uploader_sub = '00000000-0000-0000-0000-000000000007'
  AND created_at >= now() - interval '12 months' AND created_at < now()
GROUP BY 1, 2;

-- Toàn kho (người dùng có nhiều nhóm chia sẻ) - dùng IDX_document_active_created
EXPLAIN (ANALYZE, BUFFERS)
SELECT count(DISTINCT id) FROM document
WHERE deleted_at IS NULL AND status = 'clean' AND created_at >= now() - interval '12 months' AND created_at < now();
