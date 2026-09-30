-- rbac-group-scope.md Task 2: group/membership/share tables (Story 5 nền
-- tảng cho scoping ở Story 3/4) + seed-only role/permission reference tables
-- (Quyet dinh 1 SA - RBAC tinh, KHONG co API ghi cho 2 bang nay).

CREATE TABLE "group" (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name         VARCHAR(255) NOT NULL,
    description  TEXT,
    owner_sub    VARCHAR(255) NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE group_member (
    group_id      UUID NOT NULL REFERENCES "group" (id) ON DELETE CASCADE,
    user_sub      VARCHAR(255) NOT NULL,
    role_in_group VARCHAR(16) NOT NULL CHECK (role_in_group IN ('owner','member')),
    joined_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (group_id, user_sub)
);
CREATE INDEX IDX_group_member_user_sub ON group_member (user_sub);
CREATE INDEX IDX_group_member_group_id ON group_member (group_id);

CREATE TABLE document_group_share (
    document_id     UUID NOT NULL REFERENCES document (id) ON DELETE CASCADE,
    group_id        UUID NOT NULL REFERENCES "group" (id) ON DELETE CASCADE,
    shared_by_sub   VARCHAR(255) NOT NULL,
    shared_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (document_id, group_id)
);
CREATE INDEX IDX_document_group_share_group_id ON document_group_share (group_id);
CREATE INDEX IDX_document_group_share_document_id ON document_group_share (document_id);

-- Seed-only reference tables (Quyet dinh 1 SA - RBAC tinh, KHONG co API ghi).
-- Not read by any @PreAuthorize check - purely a documentation/audit
-- reference kept in lockstep with the @PreAuthorize matrix by
-- RolePermissionSeedTest (rbac-group-scope.md Task 1/Task 10, Story 1 AC4).
CREATE TABLE role (
    code         VARCHAR(32) PRIMARY KEY,
    display_name VARCHAR(128) NOT NULL
);

CREATE TABLE permission (
    code         VARCHAR(128) PRIMARY KEY,
    description  VARCHAR(255) NOT NULL
);

CREATE TABLE role_permission (
    role_code       VARCHAR(32) NOT NULL REFERENCES role (code),
    permission_code VARCHAR(128) NOT NULL REFERENCES permission (code),
    PRIMARY KEY (role_code, permission_code)
);

INSERT INTO role (code, display_name) VALUES
    ('CAN_BO', 'Cán bộ'),
    ('LANH_DAO', 'Lãnh đạo'),
    ('QUAN_TRI', 'Quản trị hệ thống');

INSERT INTO permission (code, description) VALUES
    ('document.read', 'Xem danh sách/nội dung/tải xuống tài liệu, xem ghi chú và nhãn của tài liệu'),
    ('document.upload', 'Nạp (tải lên) tài liệu vào kho qua luồng batch'),
    ('note.write', 'Tạo/sửa ghi chú của tài liệu'),
    ('tag.manage', 'Tạo/xoá nhãn, gán/gỡ nhãn cho tài liệu'),
    ('folder.read', 'Xem cây thư mục kho và dung lượng sử dụng của kho'),
    ('folder.manage', 'Tạo/đổi tên/di chuyển/xoá thư mục kho, cấu hình dung lượng tối đa'),
    ('scan_allowlist.manage', 'Quản trị danh sách định dạng tệp cho phép nạp vào kho'),
    ('system_config.manage', 'Quản trị tham số vận hành hệ thống'),
    ('account.read', 'Xem danh sách tài khoản người dùng'),
    ('account.manage', 'Tạo/sửa/khoá/mở khoá/đặt lại mật khẩu và gán vai trò tài khoản'),
    ('account.self_service', 'Tự đổi mật khẩu của chính tài khoản đang đăng nhập'),
    ('audit.read', 'Xem và xác minh chuỗi nhật ký hoạt động (audit log)'),
    ('personal.manage', 'Quản lý kho lưu trữ cá nhân của chính người dùng'),
    ('group.manage', 'Tạo nhóm, quản lý thành viên của nhóm'),
    ('document.share', 'Chia sẻ/gỡ chia sẻ tài liệu tới một nhóm');

-- role_permission: khớp 1-1 với ma trận @PreAuthorize hiện có trên các
-- controller tại thời điểm viết migration này (xem RolePermissionSeedTest).
INSERT INTO role_permission (role_code, permission_code) VALUES
    ('CAN_BO', 'document.read'),
    ('CAN_BO', 'document.upload'),
    ('CAN_BO', 'note.write'),
    ('CAN_BO', 'tag.manage'),
    ('CAN_BO', 'folder.read'),
    ('CAN_BO', 'account.self_service'),
    ('CAN_BO', 'personal.manage'),
    ('CAN_BO', 'group.manage'),
    ('CAN_BO', 'document.share'),
    ('LANH_DAO', 'document.read'),
    ('LANH_DAO', 'folder.read'),
    ('LANH_DAO', 'account.self_service'),
    ('LANH_DAO', 'personal.manage'),
    ('LANH_DAO', 'group.manage'),
    ('LANH_DAO', 'document.share'),
    ('QUAN_TRI', 'folder.read'),
    ('QUAN_TRI', 'folder.manage'),
    ('QUAN_TRI', 'scan_allowlist.manage'),
    ('QUAN_TRI', 'system_config.manage'),
    ('QUAN_TRI', 'account.read'),
    ('QUAN_TRI', 'account.manage'),
    ('QUAN_TRI', 'account.self_service'),
    ('QUAN_TRI', 'audit.read'),
    ('QUAN_TRI', 'personal.manage');
