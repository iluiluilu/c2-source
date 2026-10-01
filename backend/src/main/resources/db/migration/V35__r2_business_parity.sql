-- ARCH-r2-business-parity ADR-1 / SPEC-r2-business-parity-01: cấp 5 quyền nghiệp vụ
-- R1 cho R2 (LANH_DAO). Data-only, idempotent: chỉ INSERT, không DELETE/UPDATE,
-- ON CONFLICT DO NOTHING để không đụng lựa chọn R3 đã chỉnh qua màn RBAC (AC-7).
-- Liệt kê tường minh từng cặp, không SELECT từ role khác (R-2: tránh leo thang).
-- updated_by_sub = NULL: bản ghi seed, không phải R3 sửa tay.
INSERT INTO role_permission (role_code, permission_code) VALUES
    ('LANH_DAO', 'document.upload'),
    ('LANH_DAO', 'tag.manage'),
    ('LANH_DAO', 'note.write'),
    ('LANH_DAO', 'digitization_review.manage'),
    ('LANH_DAO', 'trash.manage')
ON CONFLICT (role_code, permission_code) DO NOTHING;
