-- SPEC-export-reporting §2: báo cáo thống kê (FR-30) + xuất tệp (FR-31).
-- Additive, backward-compatible: cột có DEFAULT, bảng mới, permission mới, index.
-- CHÚ Ý: CREATE INDEX thường (không CONCURRENTLY) vì Flyway chạy trong transaction;
-- khoá ghi ngắn trên document/digitization_run khi deploy - deploy ngoài giờ cao điểm.

-- 2.1 Cờ permission không gán được cho QUAN_TRI (AC-5)
ALTER TABLE permission ADD COLUMN admin_assignable BOOLEAN NOT NULL DEFAULT true;
INSERT INTO permission (code, description, admin_assignable) VALUES
  ('report.read',   'Xem danh mục mẫu, chạy báo cáo, lưu/mở/đổi tên/xoá báo cáo đã lưu', false),
  ('report.export', 'Tạo job xuất báo cáo, xem trạng thái/lịch sử, tải tệp xuất của mình', false);
INSERT INTO role_permission (role_code, permission_code) VALUES
  ('CAN_BO','report.read'),('CAN_BO','report.export'),
  ('LANH_DAO','report.read'),('LANH_DAO','report.export')
ON CONFLICT (role_code, permission_code) DO NOTHING;

-- 2.2 Danh mục mẫu (chỉ đọc, seed). Nguồn sự thật cho validate là ReportTemplateRegistry (code).
CREATE TABLE report_template (
  code VARCHAR(48) PRIMARY KEY,
  name VARCHAR(200) NOT NULL,
  description TEXT NOT NULL,
  jasper_path VARCHAR(255) NOT NULL,
  formats VARCHAR(64) NOT NULL DEFAULT 'PDF,XLSX,DOCX');
INSERT INTO report_template (code, name, description, jasper_path) VALUES
 ('REPO_SUMMARY', 'Tổng hợp tình hình kho tài liệu theo kỳ',
  'Tồn kho đầu kỳ/cuối kỳ, số tài liệu nạp mới, cơ cấu theo nhóm định dạng, nạp mới theo thời gian và tình trạng số hoá.',
  'reports/repo_summary.jasper'),
 ('DIGITIZATION_PROGRESS', 'Tiến độ và chất lượng số hoá',
  'Số lần chạy OCR/STT/tóm tắt video theo trạng thái xử lý, chờ duyệt, quá hạn và nguồn xác nhận.',
  'reports/digitization_progress.jasper'),
 ('DOC_BY_CLASSIFICATION', 'Thống kê theo chủ đề và loại tài liệu',
  'Ma trận chủ đề x loại tài liệu và thống kê theo từng chiều, gồm mục chưa phân loại.',
  'reports/doc_by_classification.jasper');

-- 2.3 Báo cáo đã lưu (riêng từng người, AC-11)
CREATE TABLE report_saved_query (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  owner_sub VARCHAR(255) NOT NULL,
  template_code VARCHAR(48) NOT NULL REFERENCES report_template (code),
  name VARCHAR(100) NOT NULL CHECK (char_length(btrim(name)) BETWEEN 1 AND 100),
  params_json JSONB NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now());
CREATE UNIQUE INDEX UQ_report_saved_query_owner_name ON report_saved_query (owner_sub, lower(btrim(name)));
CREATE INDEX IDX_report_saved_query_owner ON report_saved_query (owner_sub, updated_at DESC);

-- 2.4 Job xuất tệp (AC-21..33)
CREATE TABLE report_export (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  template_code VARCHAR(48) NOT NULL REFERENCES report_template (code),
  params_json JSONB NOT NULL,
  format VARCHAR(8) NOT NULL CHECK (format IN ('PDF','XLSX','DOCX')),
  status VARCHAR(10) NOT NULL DEFAULT 'PENDING'
    CHECK (status IN ('PENDING','RUNNING','DONE','FAILED','EXPIRED')),
  requested_by_sub VARCHAR(255) NOT NULL,
  requested_by_username VARCHAR(255) NOT NULL,
  retry_of UUID REFERENCES report_export (id),
  object_key VARCHAR(512),
  byte_size BIGINT,
  row_count INT,
  error_message VARCHAR(500),
  attempt_count INT NOT NULL DEFAULT 0,
  lease_expires_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  started_at TIMESTAMPTZ,
  finished_at TIMESTAMPTZ,
  expires_at TIMESTAMPTZ);
CREATE INDEX IDX_report_export_pollable ON report_export (status, lease_expires_at, created_at)
  WHERE status IN ('PENDING','RUNNING');
CREATE INDEX IDX_report_export_owner ON report_export (requested_by_sub, created_at DESC);
CREATE INDEX IDX_report_export_expiry ON report_export (expires_at) WHERE status = 'DONE';

-- 2.5 Index thống kê (ADR-1): partial theo đúng predicate báo cáo.
CREATE INDEX IDX_document_active_created ON document (created_at)
  WHERE deleted_at IS NULL AND status = 'clean';
CREATE INDEX IDX_document_uploader_created_active ON document (uploader_sub, created_at)
  WHERE deleted_at IS NULL AND status = 'clean';
CREATE INDEX IDX_digitization_run_created_active ON digitization_run (created_at, task)
  WHERE review_state NOT IN ('superseded','void');
