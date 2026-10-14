# User Story: r2-business-parity-02-ownership-transfer

> File: `docs/02-ba/stories/r2-business-parity-02-ownership-transfer.md` — Owner: BA
> Nguồn kiến trúc: `docs/01-sa/architecture/r2-business-parity.md` (ARCH-r2-business-parity, ADR-4)
> Status: Approved
> Story ID: `STORY-r2-business-parity-02`
> Priority: Must
> Estimate: S

## User Story

**As a** Quản trị (R3 — `QUAN_TRI`)
**I want** chuyển quyền sở hữu tài liệu cho bất kỳ tài khoản R1 (Cán bộ) hoặc
R2 (Lãnh đạo) đang hoạt động, không chỉ giới hạn ở R1 như hiện tại
**So that** nghiệp vụ bàn giao tài liệu phản ánh đúng thực tế R2 nay cũng sở
hữu và xử lý tài liệu như R1

## Acceptance Criteria

- [ ] **AC-1:** Given một tài khoản đang hoạt động (enabled), có vai trò
      `lanh-dao` (R2), khác với người nạp/sở hữu hiện tại, When R3 chọn tài
      khoản này làm người nhận và thực hiện chuyển quyền sở hữu, Then thao tác
      thành công (trước đây sẽ bị từ chối vì chỉ R1 được chấp nhận).
- [ ] **AC-2:** Given một tài khoản chỉ có vai trò `can-bo` (R1), When R3
      chuyển quyền sở hữu cho tài khoản đó, Then thao tác vẫn thành công như
      hành vi hiện tại (không có regression).
- [ ] **AC-3:** Given một tài khoản chỉ có vai trò `quan-tri` (R3), không có
      `can-bo` lẫn `lanh-dao`, When R3 chọn tài khoản đó làm người nhận, Then
      bị từ chối với thông báo "Người nhận phải có vai trò Cán bộ (R1) hoặc
      Lãnh đạo (R2)".
- [ ] **AC-4:** Given một tài khoản có đồng thời vai trò `quan-tri` và
      `lanh-dao` (hoặc `can-bo`), When R3 chọn tài khoản đó làm người nhận,
      Then thao tác vẫn thành công (chỉ cần có ít nhất một trong hai vai trò
      `can-bo`/`lanh-dao`).
- [ ] **AC-5:** Given người nhận là tài khoản R2 nhưng đã bị R3 gỡ quyền
      `document.upload` khỏi vai trò `lanh-dao` qua RBAC, When R3 thực hiện
      chuyển quyền sở hữu cho tài khoản này, Then backend vẫn cho phép (chỉ
      kiểm vai trò, không kiểm permission); Then giao diện web hiển thị cảnh
      báo khi chọn người nhận này (không chặn). Cách xác định: trang chuyển
      quyền (chỉ R3) gọi `GET /admin/rbac/roles` một lần để lấy
      `permissionCodes` của từng vai trò. Người nhận được coi là có
      `document.upload` nếu ít nhất một vai trò của họ có quyền này (vai trò
      lấy từ danh sách tài khoản sẵn có). Nếu gọi API lỗi thì bỏ qua cảnh báo,
      không chặn thao tác.
- [ ] **AC-6:** Given các điều kiện từ chối hiện có (người nhận không tồn tại,
      tài khoản bị vô hiệu hoá, người nhận trùng người nạp hiện tại), When thử
      chuyển quyền sở hữu, Then vẫn bị từ chối như hành vi hiện tại (không có
      regression từ thay đổi này).
- [ ] **AC-7:** Given người thực hiện không có vai trò R3 (`quan-tri`), When
      gọi API chuyển quyền sở hữu, Then vẫn bị chặn như hiện tại — chỉ R3 được
      thực hiện hành động này (không đổi).
- [ ] **AC-8:** Given một lượt chuyển quyền sở hữu thành công (sang người nhận
      R1 hoặc R2), When kiểm tra nhật ký kiểm toán, Then sự kiện
      `document.ownership_transferred` được ghi với định dạng không đổi so
      với trước (actor = R3 thực hiện, resource = tài liệu, người nhận mới).

## Ngoài phạm vi (Out of scope)

- Cho phép R2 tự thực hiện chuyển quyền sở hữu — chỉ R3 được thực hiện hành
  động này, không đổi.
- Thay đổi điều kiện chặn ở backend để kiểm permission `document.upload` của
  người nhận — ĐÃ CHỐT dùng kiểm vai trò (ADR-4), không kiểm permission.
- Đổi luồng/phạm vi `audit.read_scoped`.

## Phụ thuộc (Dependencies)

- Độc lập với story `r2-business-parity-01-permission-grant` (không yêu cầu
  R2 đã có 5 permission nghiệp vụ mới để thực hiện story này — AC-5 áp dụng
  đúng cả khi R2 bị gỡ `document.upload`).
- Story `r2-business-parity-03-web-permission-gating` cần danh sách người
  nhận hợp lệ (R1 ∪ R2 đang hoạt động) để lọc dropdown chọn người nhận ở FE —
  phối hợp khi viết spec FE. Bộ lọc hiện tại ở
  `web/src/app/admin/document-ownership/page.tsx:107` chỉ nhận `Role.CAN_BO`,
  cần mở thêm `Role.LANH_DAO`.
- AC-5 dùng endpoint sẵn có `GET /admin/rbac/roles` (`hasRole('QUAN_TRI')`,
  `RoleView.permissionCodes`), không cần API mới.
- Tham chiếu quyết định kiến trúc: ADR-4; Câu hỏi mở Q3, Q4 đã chốt trong
  ARCH-r2-business-parity §9.

## Câu hỏi mở cho DEV

- [x] Khi R2 là người nhận nhưng thiếu `document.upload` (AC-5), "nếu xác
      định được" cảnh báo FE — xác nhận cơ chế xác định (gọi `/me/permissions`
      cho từng ứng viên là không khả thi/không an toàn theo NFR Security của
      ARCH; cần nguồn dữ liệu khác, vd. danh sách permission theo role lấy
      một lần từ BE, hay bỏ qua cảnh báo nếu không khả thi — đề xuất khi viết
      spec, BA sẽ xác nhận lại).
      → **Chốt:** FE (trang chỉ R3) dùng `GET /admin/rbac/roles` lấy permissionCodes theo vai trò, đối chiếu với vai trò người nhận; API lỗi thì bỏ cảnh báo, không chặn (cập nhật: AC-5, Phụ thuộc)
