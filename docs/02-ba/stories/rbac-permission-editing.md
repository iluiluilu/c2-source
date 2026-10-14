# User Story: rbac-permission-editing

> File: `docs/02-ba/stories/rbac-permission-editing.md` — Owner: BA
> Nguồn kiến trúc: `docs/01-sa/architecture/rbac-permission-editing.md`
> Status: Approved

Bổ sung cho FR-07: R3 tự xem và chỉnh sửa **ánh xạ quyền của 3 vai trò có
sẵn** (`CAN_BO`/`LANH_DAO`/`QUAN_TRI`) trong danh mục 15 permission đã đóng,
thay vì phải sửa code + deploy lại backend mỗi khi ma trận phân quyền cần
điều chỉnh. Đây là **mở rộng có kiểm soát** cho Quyết định 1 (RBAC tĩnh) ở
`docs/02-ba/stories/rbac-group-scope.md` — KHÔNG cho tạo/xoá vai trò hay
permission mới (xem "Ngoài phạm vi").

---

## Story 1 — R3 xem ma trận vai trò × quyền hiện tại

**As a** Quản trị hệ thống (R3)
**I want** xem danh mục vai trò, danh mục quyền và ma trận vai trò × quyền
hiện tại
**So that** tôi biết mỗi vai trò đang làm được gì trước khi quyết định sửa

### Acceptance Criteria

```
Given tôi là R3 đăng nhập vào màn hình quản trị RBAC
When tôi mở màn hình ma trận vai trò × quyền
Then hệ thống hiển thị đúng 3 vai trò, đủ 15 quyền kèm mô tả, và trạng thái
     bật/tắt hiện tại của từng ô (vai trò × quyền)
```

- [ ] AC-1: Hiển thị đủ 3 vai trò (`CAN_BO`, `LANH_DAO`, `QUAN_TRI`) và tên
      hiển thị tiếng Việt.
- [ ] AC-2: Hiển thị đủ 15 permission code kèm mô tả (từ bảng `permission`).
- [ ] AC-3: Trạng thái từng ô khớp đúng dữ liệu hiện tại trong `role_permission`.
- [ ] AC-4: Chỉ R3 xem được màn hình này.

---

## Story 2 — R3 chỉnh sửa tập quyền của một vai trò

**As a** Quản trị hệ thống (R3)
**I want** bật/tắt một hoặc nhiều quyền cho một vai trò trong danh mục có sẵn
**So that** tôi điều chỉnh được phân quyền theo nhu cầu thực tế mà không cần
chờ sửa code

### Acceptance Criteria

```
Given tôi là R3 đang xem ma trận vai trò × quyền
When tôi bật/tắt một hoặc nhiều quyền cho một vai trò rồi lưu
Then hệ thống cập nhật `role_permission`, ghi nhật ký thay đổi, và quyền
     tương ứng có hiệu lực gần như ngay lập tức cho mọi tài khoản đang giữ
     vai trò đó — không cần tài khoản đó đăng xuất/đăng nhập lại
```

- [ ] AC-1: Chỉ chọn được permission code nằm trong danh mục 15 quyền đã có
      (đóng danh mục) — không có ô nhập tự do/tạo quyền mới.
- [ ] AC-2: Chỉ R3 thực hiện được thao tác này.
- [ ] AC-3: Thay đổi được ghi `audit_log` (action `rbac.role_permission_update`)
      kèm vai trò, danh sách quyền thêm/bớt, người thực hiện, thời điểm.
- [ ] AC-4: Thay đổi có hiệu lực cho tài khoản đang hoạt động trong vòng thời
      gian cache ngắn (xem câu hỏi mở SA) — không yêu cầu đăng xuất/đăng
      nhập lại.
- [ ] AC-5: Trước khi lưu, hệ thống hiển thị số lượng tài khoản đang giữ vai
      trò đó sẽ bị ảnh hưởng (thêm/mất quyền gì).

---

## Story 3 — Chặn thao tác tự khoá hệ thống quản trị

**As a** hệ thống / quản trị bảo mật
**I want** chặn R3 lưu một thay đổi khiến vai trò `QUAN_TRI` mất quyền tự
quản trị tối thiểu
**So that** không ai vô tình khoá cứng khả năng tự sửa lại RBAC/tài khoản
của toàn hệ thống

### Acceptance Criteria

```
Given R3 đang chỉnh sửa quyền của vai trò QUAN_TRI
When thay đổi sẽ khiến QUAN_TRI không còn đồng thời quyền account.manage
     và system_config.manage
Then hệ thống từ chối lưu, báo rõ lý do, không thực hiện thay đổi
```

- [ ] AC-1: Từ chối lưu nếu sau thay đổi, `QUAN_TRI` thiếu `account.manage`
      hoặc `system_config.manage`.
- [ ] AC-2: Thông báo lỗi nêu rõ quyền nào bắt buộc phải giữ lại.
- [ ] AC-3: Guard áp dụng ở tầng service (không chỉ ẩn nút ở UI) — gọi thẳng
      API cũng bị chặn.

---

## Ngoài phạm vi (Out of scope)

- Tạo vai trò mới hoặc xoá vai trò hiện có — vẫn cố định 3 vai trò
  (Quyết định 1b, SA).
- Tạo permission code mới hoặc xoá permission code hiện có — vẫn cố định 15
  quyền; một permission code chỉ có tác dụng khi đã có sẵn điểm enforcement
  tương ứng trong code.
- Gán/thu hồi vai trò cho một tài khoản cụ thể — đã có sẵn, thuộc
  `rbac-group-scope.md` Story 2 (`AccountsController`), không lặp lại ở đây.
- Tự động khôi phục ("rollback") cấu hình trước đó — bản này chỉ có audit
  log để R3 tự đối chiếu và bật/tắt lại thủ công.
- Cấu hình TTL cache qua UI — TTL cố định trong code (DEV chọn giá trị cụ
  thể theo câu hỏi mở của SA).

## Phụ thuộc / Ghi chú kiến trúc liên quan

- Quyết định 1 (`rbac-group-scope.md`): RBAC tĩnh, 3 vai trò cố định — vẫn
  giữ nguyên phần *danh mục* (không tạo vai trò/permission mới).
- Quyết định 1b (`rbac-permission-editing.md`, SA): mở rộng có kiểm soát —
  cho ghi vào `role_permission` trong danh mục đóng.
- Quyết định 2 (SA): enforcement chuyển từ `@PreAuthorize("hasRole(...))`
  sang `@PreAuthorize("hasAuthority('PERM_...')")` đọc từ DB có cache.
- Quyết định 3 (SA): guard chống lockout `QUAN_TRI`.
- **Cập nhật dòng "Ngoài phạm vi" cũ ở `rbac-group-scope.md`:** dòng "RBAC
  động (tạo vai trò tùy ý, bảng `role_permission` có API ghi) — đã chốt
  KHÔNG làm ở giai đoạn này" nay chỉ còn đúng với phần **tạo vai trò/permission
  mới**; phần **ghi vào `role_permission`** trong danh mục có sẵn được cho
  phép bởi story này.

## Câu hỏi mở cho DEV

- [ ] Giá trị TTL cache cụ thể (SA đề xuất 30–60 giây) — chọn theo mức độ
      chấp nhận độ trễ hiệu lực, cân nhắc có nhiều instance backend hay không.
- [ ] Cách đếm "số tài khoản bị ảnh hưởng" (Story 2 AC-5) nên gọi trực tiếp
      Keycloak Admin API (`listRoleUsers`, đã có sẵn trong
      `KeycloakAdminService`) tại thời điểm xem trước khi lưu, không cần lưu
      cache riêng.
