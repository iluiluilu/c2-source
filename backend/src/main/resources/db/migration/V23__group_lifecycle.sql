-- group-management-sharing-01-group-lifecycle.md Task 1: soft delete for
-- "group", partial unique index on active group names (Quyết định 5 SA), and
-- the new user.directory_search permission (Story 5).
--
-- No existing INSERT INTO "group" seed data in this repo (grepped every
-- migration before writing this one), so the partial unique index below is
-- safe to add without a dedup step.

ALTER TABLE "group" ADD COLUMN deleted_at TIMESTAMPTZ;

-- Unique tên nhóm, không phân biệt hoa/thường, chỉ áp cho nhóm chưa xoá
-- (Quyết định 5 SA) - partial unique index.
CREATE UNIQUE INDEX UQ_group_name_active ON "group" (lower(name)) WHERE deleted_at IS NULL;

-- Story 6 AC3: index để liệt kê nhóm active nhanh (đã có PK trên id, thêm
-- index lọc theo deleted_at cho các truy vấn GET /groups quy mô lớn).
CREATE INDEX IDX_group_deleted_at ON "group" (deleted_at);

-- Story 5 (danh bạ người dùng): permission mới, tách biệt account.read
-- (QUAN_TRI, đầy đủ thông tin tài khoản) - tránh vi phạm NFR-04 nếu tái dùng.
INSERT INTO permission (code, description) VALUES
    ('user.directory_search', 'Tìm kiếm tài khoản tối giản để chọn thành viên nhóm');
INSERT INTO role_permission (role_code, permission_code) VALUES
    ('CAN_BO', 'user.directory_search'),
    ('LANH_DAO', 'user.directory_search');
