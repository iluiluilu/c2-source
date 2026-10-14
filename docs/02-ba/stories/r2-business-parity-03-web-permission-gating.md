# User Story: r2-business-parity-03-web-permission-gating

> File: `docs/02-ba/stories/r2-business-parity-03-web-permission-gating.md` — Owner: BA
> Nguồn kiến trúc: `docs/01-sa/architecture/r2-business-parity.md` (ARCH-r2-business-parity, ADR-2, ADR-3)
> Status: Approved
> Story ID: `STORY-r2-business-parity-03`
> Priority: Must
> Estimate: M

## User Story

**As a** người dùng đã đăng nhập (R1 Cán bộ, R2 Lãnh đạo, hoặc R3 Quản trị)
**I want** giao diện web chỉ hiển thị menu/route/nút tương ứng đúng với quyền
nghiệp vụ hiện tại của tôi (không chỉ dựa theo vai trò hard-code)
**So that** tôi không thấy chức năng mà mình sẽ bị backend từ chối (403), và
khi Quản trị thay đổi phân quyền RBAC, giao diện phản ánh đúng quyền mới mà
không cần sửa code web

## Acceptance Criteria

- [ ] **AC-1:** Given người dùng đã đăng nhập, When ứng dụng web khởi tạo,
      Then web gọi `GET /me/permissions` và nhận về đúng `roles` + `permissions`
      của chính người dùng đó (API không nhận tham số chỉ định user khác).
- [ ] **AC-2:** Given tài khoản R2 ở trạng thái mặc định (đã có 5 permission
      nghiệp vụ theo story `r2-business-parity-01-permission-grant`), When
      đăng nhập vào web, Then các menu/nút cho nạp tài liệu, quản lý tag, ghi
      chú, xử lý hàng chờ số hoá, thùng rác hiển thị cho R2 giống hệt R1.
- [ ] **AC-3:** Given Quản trị (R3) gỡ một trong 5 permission khỏi vai trò
      `LANH_DAO` qua màn RBAC, When tối đa 5 phút trôi qua, hoặc người dùng R2
      focus lại tab trình duyệt, hoặc một API trả về 403 cho hành động đó,
      Then web ẩn menu/nút tương ứng với permission đã bị gỡ mà không cần
      người dùng đăng xuất/đăng nhập lại.
- [ ] **AC-4:** Given lỗi khi gọi `GET /me/permissions` (mất mạng, lỗi 5xx,
      timeout), When render giao diện, Then web áp dụng fail-closed — ẩn các
      chức năng cần quyền và hiển thị thông báo cho phép thử lại, không hiển
      thị nhầm các chức năng đó (không fail-open).
- [ ] **AC-5:** Given các trang/chức năng quản trị RBAC hiện gate theo vai trò
      `hasRole('QUAN_TRI')` ở backend, When kiểm tra gate ở web cho các trang
      này, Then vẫn dùng kiểm tra theo vai trò `QUAN_TRI` như trước (không đổi
      sang permission).
- [ ] **AC-6:** Given các test web hiện có (~7 file) đang khẳng định R2 bị
      chặn không thấy/không dùng được các chức năng thuộc 5 permission mới,
      When cập nhật theo thay đổi này, Then test được sửa đảo ngược đúng ý
      nghĩa nghiệp vụ mới (R2 được phép), không bị "sửa cho xanh" làm mất ý
      nghĩa kiểm thử ban đầu (QA đối chiếu từng test đã đổi theo danh sách cụ
      thể trong spec DEV).
- [ ] **AC-7:** Given quyền hiệu lực đã đổi (từ AC-3), When người dùng không
      thao tác gì thêm (không điều hướng, không có action gọi API), Then web
      không tăng số lần gọi `GET /me/permissions` ngoài chu kỳ làm mới đã định
      (đăng nhập, chu kỳ session refetch ~4 phút, khi focus tab, khi gặp 403)
      — không gọi lại mỗi lần render.
- [ ] **AC-8:** Given provider quyền phía client, When đang tải lần đầu
      (`loading`) hoặc tải lỗi (`error`), Then `can()` trả `false` ở mọi nơi
      (fail-closed toàn cục). Khi `error` thì hiện một banner có nút thử lại.
      Khi refetch nền (chu kỳ/focus/403) thì giữ quyền đã có cho tới khi có
      kết quả mới, menu/nút không nháy ẩn/hiện.

## Ngoài phạm vi (Out of scope)

- Gate theo data-scope ở FE (vd. "chỉ tài liệu của tôi") — FE chỉ gate ở mức
  permission/role hiển thị menu-nút; ràng buộc theo bản ghi vẫn do backend xử
  lý.
- Cơ chế đẩy real-time (SSE/WebSocket) khi RBAC thay đổi — đã chốt dùng
  polling theo chu kỳ hiện có + làm mới khi focus/403.
- Thêm thư viện JWT phía client hoặc đổi cấu trúc session NextAuth.
- Đổi gate của các trang quản trị R3 hiện dùng `hasRole('QUAN_TRI')`.

## Phụ thuộc (Dependencies)

- Phụ thuộc story `r2-business-parity-01-permission-grant` (5 permission của
  R2 phải tồn tại trong `role_permission` trước khi AC-2 có thể kiểm chứng
  đầy đủ trên môi trường mặc định).
- Phối hợp với story `r2-business-parity-02-ownership-transfer` cho phần lọc
  danh sách người nhận hợp lệ ở FE màn chuyển quyền sở hữu (nếu có hiển thị
  cảnh báo theo AC-5 của story đó).
- Tham chiếu quyết định kiến trúc: ADR-2 (endpoint `/me/permissions`), ADR-3
  (gate UI theo permission); Rủi ro R-3, R-4, R-7 trong ARCH-r2-business-parity.
- Quy tắc phân loại gate web (ADR-3): gate web dùng **đúng loại gate của
  endpoint BE tương ứng**. BE dùng `hasAuthority('PERM_x')` thì web dùng
  `can('x')`; BE dùng `hasRole`/`hasAnyRole` thì web giữ `roles`. Danh sách sơ
  bộ (grep ngày 2026-10-01), DEV xác nhận từng dòng trong spec:
  - Chuyển sang permission: `lib/features.ts` mục `upload`
    (`document.upload`), `digitization` (`digitization_review.manage`),
    `trash` (`trash.manage`); `app/documents/page.tsx` `canDelete`;
    `app/documents/[id]/page.tsx` `canWrite` (`tag.manage`/`note.write`);
    các trang `app/upload`, `app/digitization` (kể cả `runs/[runId]`),
    `app/trash`.
  - Giữ role: các trang `app/admin/*` (R3); `app/admin/audit` `canVerify`
    (`QUAN_TRI`); nhãn vai trò ở `app/admin/accounts`; bộ lọc người nhận ở
    `app/admin/document-ownership` (theo ADR-4); các mục `documents`,
    `search`, `groups`, `personal` (BE dùng `hasAnyRole`, đã gồm R2).
  - Test web liên quan (8 file `__tests__/page.test.tsx`): `admin/accounts`,
    `admin/audit`, `admin/rbac`, `digitization`, `digitization/runs/[runId]`,
    `documents/[id]`, `documents`, `trash`; cùng `test-utils/session.ts`
    (chuyển mock `roles` sang `permissions`).

## Câu hỏi mở cho DEV

- [x] Khi liệt kê lại ~21 file web đang dùng
      `canSeeFeature/hasAnyRole/hasRole`, xác nhận cụ thể file nào chuyển sang
      `permissions` và file nào giữ nguyên theo `roles` (trang R3) — đưa vào
      spec để BA/QA đối chiếu khi review AC-5, AC-6.
      → **Chốt:** Gate web theo đúng loại gate của BE (PERM_ thì permission, role thì giữ role); danh sách sơ bộ đã ghi, DEV xác nhận trong spec (cập nhật: Phụ thuộc)
- [x] Xác nhận cơ chế "provider quyền phía client" (context/hook) có cần expose
      trạng thái loading riêng biệt cho từng phần UI, hay một cờ loading toàn
      cục là đủ để đáp ứng AC-4 (fail-closed khi đang tải).
      → **Chốt:** Một trạng thái toàn cục loading/ready/error; `can()` trả false khi chưa ready; refetch nền giữ quyền cũ (cập nhật: AC-8)
