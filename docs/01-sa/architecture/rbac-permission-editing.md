# Architecture: rbac-permission-editing

> File: `docs/01-sa/architecture/rbac-permission-editing.md` — Owner: SA
> Status: Approved
> Duyệt: người dùng đã xem xét đề xuất tại `docs/de_xuat_crud_rbac_r3.md`
> (thư mục gốc dự án, cùng cấp `dac_ta_yeu_cau_chuc_nang_phi_chuc_nang.adoc`)
> và đồng ý triển khai trong hội thoại 2026-08-17.
> Phụ thuộc: `docs/01-sa/architecture/rbac-group-scope.md` (Quyết định 1) —
> tài liệu này **mở rộng có kiểm soát** quyết định đó, không đảo ngược.

## 1. Bối cảnh / Vấn đề

- `rbac-group-scope.md` (Quyết định 1, đã Approved và implement xong —
  `V12__init_groups.sql`) chốt RBAC **tĩnh**: 3 vai trò cố định
  (`CAN_BO`/`LANH_DAO`/`QUAN_TRI`, 1-1 với Keycloak realm role), quyền của
  từng vai trò hard-code qua `@PreAuthorize` trên ~17 vị trí controller.
  Bảng `role`/`permission`/`role_permission` được tạo và seed sẵn (3 role ×
  15 permission) nhưng chỉ để **tham chiếu**, không có API ghi — mọi thay
  đổi ma trận phân quyền hiện tại bắt buộc phải sửa code + build + deploy
  lại backend.
- R3 (quản trị hệ thống) hiện **không có cách nào** tự xem hay tự chỉnh sửa
  ma trận vai trò × quyền qua UI, dù FR-07 (SRS mục 3) ghi R3 có toàn quyền
  "định nghĩa vai trò và gán quyền".
- In-scope: cho R3 xem ma trận quyền hiện có và **chỉnh sửa (bật/tắt) quyền
  của 3 vai trò có sẵn** trong danh mục 15 permission đã đóng, có hiệu lực
  gần-tức-thời khi lưu.
- Out-of-scope: tạo/xoá vai trò mới; tạo/xoá permission code mới; RBAC động
  hoàn toàn (giữ nguyên tinh thần "không mở rộng danh mục" của Quyết định 1
  cũ — xem mục 4, Quyết định 1b).

## 2. Ràng buộc (Constraints)

- Không đổi cấu trúc bảng `role`/`permission`/`role_permission` đã có ở
  `V12__init_groups.sql` — chỉ bỏ ràng buộc "không có API ghi" và bổ sung
  cột audit tối thiểu.
- Không đổi mô hình Keycloak (vai trò vẫn quản lý ở Keycloak, không đụng
  tới realm-export.json) — chỉ đổi tầng enforcement phía backend đọc thêm
  từ DB thay vì chỉ đọc `realm_access.roles`.
- Danh mục 15 permission code hiện có (`V12__init_groups.sql`) là **đóng**:
  không thêm/bớt code trong phạm vi tài liệu này, vì mỗi code phải khớp 1-1
  với một điểm enforcement thật trong code (xem Quyết định 2).
- Mạng cách ly hoàn toàn, kiến trúc hạ tầng không đổi (kế thừa
  `kien_truc_he_thong.adoc`).

## 3. NFR (Non-Functional Requirements)

| Loại | Yêu cầu |
|---|---|
| Performance | Tra cứu quyền theo vai trò không được thêm độ trễ đáng kể trên mỗi request (NFR-01) — bắt buộc cache, không query DB trực tiếp mỗi lần |
| Security | Không cho phép R3 tự khoá cứng toàn hệ thống quản trị (gỡ hết quyền tự sửa RBAC của chính `QUAN_TRI`); danh mục permission đóng để tránh cấu hình "ảo" không có tác dụng thật |
| Scalability | Không phát sinh yêu cầu quy mô mới — vẫn 3 vai trò × 15 quyền |
| Availability | Nếu cache/DB lỗi khi tra permission → fail-closed (từ chối), cùng nguyên tắc với `BusinessDataScope` (rbac-group-scope.md Quyết định 3) |
| Khác | Mọi thay đổi ma trận quyền ghi `audit_log` đầy đủ (FR-09), có `updated_at`/`updated_by` trên chính bảng `role_permission` để tra nhanh không cần join audit log |

## 4. Phương án & Quyết định (ADR)

### Quyết định 1b: Mở rộng có kiểm soát cho Quyết định 1 (RBAC tĩnh)

- **Ngữ cảnh:** Quyết định 1 cũ chọn RBAC tĩnh hoàn toàn (không có API/UI
  nào cho R3 chỉnh sửa quyền), với lý do RBAC động đầy đủ (tạo vai trò/quyền
  tuỳ ý) vượt phạm vi SRS. Nhưng SRS mục 3 vẫn ghi R3 có toàn quyền
  (`X`) trên FR-07, và việc mọi thay đổi ma trận quyền đều cần sửa code là
  chi phí vận hành dài hạn không nhỏ.
- **Các phương án xem xét:**
  - A) Giữ nguyên hoàn toàn Quyết định 1 cũ — không làm gì thêm.
  - B) Mở rộng có kiểm soát: cho R3 chỉnh **ánh xạ** vai trò↔quyền trong
    danh mục vai trò (3) và permission (15) vẫn đóng/cố định — không tạo
    mới, không xoá vai trò/permission.
  - C) RBAC động đầy đủ (tạo vai trò/permission tuỳ ý qua UI) — đúng Option
    B đã bị loại ở Quyết định 1 cũ.
- **Quyết định:** B — đã chốt (người dùng duyệt qua
  `docs/de_xuat_crud_rbac_r3.md`).
- **Lý do:** đáp ứng đúng phần thiếu của FR-07 (R3 tự cấu hình lại quyền)
  mà không mở lại toàn bộ rủi ro của RBAC động (C) — danh mục permission vẫn
  đóng nên không có permission "ảo" không tác dụng; danh mục vai trò vẫn
  đóng nên không phát sinh lệch pha với Keycloak/`Role.java`/
  `AssignableRoles.java`.
- **Hệ quả (trade-off):** cần chuyển tầng enforcement từ hard-code
  (`@PreAuthorize("hasRole(...)")`) sang đọc `role_permission` (có cache) —
  xem Quyết định 2. Đây là thay đổi cơ học (~17 vị trí đã biết trước qua
  `RolePermissionSeedTest`), không phát sinh logic nghiệp vụ mới.
  `rbac-group-scope.md` mục "Ngoài phạm vi" (dòng "RBAC động — bảng
  `role_permission` có API ghi — đã chốt KHÔNG làm") cần được hiểu lại: chỉ
  còn đúng với phần **tạo vai trò/permission mới**, KHÔNG còn đúng với việc
  **ghi vào `role_permission`** (nay được phép trong phạm vi tài liệu này).

### Quyết định 2: Enforcement chuyển từ hard-code sang đọc DB (có cache)

- **Ngữ cảnh:** nếu chỉ thêm API ghi `role_permission` mà `@PreAuthorize`
  vẫn hard-code role name thì thay đổi của R3 không có tác dụng thật.
- **Các phương án xem xét:**
  - A) `SecurityConfig` tra `role_permission` (qua cache) theo Keycloak
    role trong JWT, sinh thêm Spring authority `PERM_<code>`; đổi
    `@PreAuthorize("hasRole(...))` → `@PreAuthorize("hasAuthority('PERM_...')")`.
  - B) Middleware/interceptor riêng kiểm tra permission thủ công trong từng
    controller (không dùng Spring Security authority).
  - C) Đẩy toàn bộ quyết định quyền sang một policy service ngoài (OPA...).
- **Quyết định:** A — đã chốt.
- **Lý do:** tận dụng đúng cơ chế Spring Security sẵn có
  (`JwtAuthenticationConverter`, `@PreAuthorize`), thay đổi cơ học tại từng
  annotation, không cần thêm hạ tầng mới (loại C quá nặng cho quy mô hệ
  thống này — mạng cách ly, ít nhân sự vận hành).
- **Hệ quả (trade-off):** cache permission theo vai trò (TTL ngắn, đề xuất
  30–60 giây) để tránh query DB mỗi request; phải evict cache chủ động khi
  ghi để giảm độ trễ hiệu lực. `RolePermissionSeedTest` đổi vai trò từ
  "invariant sống" (DB phải luôn khớp code) sang "chỉ kiểm tra đúng seed ban
  đầu lúc migration chạy" — vì sau quyết định này DB được phép lệch khỏi
  seed gốc do R3 chỉnh tay.

### Quyết định 3: Guard chống tự khoá hệ thống (lockout)

- **Ngữ cảnh:** R3 có thể vô tình gỡ hết quyền `account.manage`/
  `system_config.manage` khỏi chính vai trò `QUAN_TRI`, khiến không còn ai
  sửa lại được RBAC/tài khoản — tương tự rủi ro "khoá R3 active cuối cùng"
  đã có guard ở `AccountsService.guardLastActiveAdmin`.
- **Quyết định:** chặn cứng ở service layer: không cho lưu nếu sau khi áp
  thay đổi, vai trò `QUAN_TRI` không còn giữ đồng thời `account.manage` và
  `system_config.manage`.
- **Lý do:** đây là 2 quyền tối thiểu để R3 còn tự khôi phục lại cấu hình
  đúng; chặn ở service layer (không chỉ ở UI) vì đây là bất biến bảo mật,
  không phải gợi ý.
- **Hệ quả:** thêm 1 rule cố định trong `RbacService`, không cấu hình được
  qua UI (giống cách `guardLastActiveAdmin` không tắt được).

## 5. Tech stack đề xuất

- Backend: Spring Boot (module mới `com.aismartstore.backend.rbac`,
  cùng pattern `accounts/`, `groups/`); cache trong tiến trình (Caffeine)
  cho permission-per-role, TTL ngắn.
- Data store: PostgreSQL — tái sử dụng `role`/`permission`/`role_permission`
  đã có, thêm 2 cột audit tối thiểu.
- Auth: giữ nguyên Keycloak cho danh tính/vai trò; chỉ thêm bước tra permission
  ở tầng backend sau khi xác thực JWT.
- Infra/Deploy: không đổi.

## 6. Rủi ro

| Rủi ro | Mức độ | Giảm thiểu |
|---|---|---|
| R3 tự khoá cứng hệ thống quản trị (gỡ hết quyền tự sửa RBAC) | Cao nếu không có guard | Quyết định 3 — chặn cứng ở service layer |
| Refactor ~17 vị trí `@PreAuthorize` sang permission-based dễ sót/sai | Trung bình | Dùng đúng danh sách đã đối chiếu sẵn trong `RolePermissionSeedTest` làm checklist |
| Cache khiến thay đổi có độ trễ hiệu lực, admin tưởng đã áp dụng | Thấp | TTL ngắn (30–60s) + evict chủ động khi ghi |
| Hiểu nhầm phạm vi — người dùng tưởng đây là RBAC động đầy đủ | Thấp | Ghi rõ trong BA story + UI: chỉ chỉnh ánh xạ trong danh mục đóng, không tạo vai trò/permission mới |

## 7. Câu hỏi mở

- [ ] TTL cache cụ thể (đề xuất 30–60 giây) — cần BA/DEV xác nhận có chấp
      nhận được độ trễ hiệu lực này không, hay cần evict qua cơ chế
      pub-sub nếu backend chạy nhiều instance.
- [ ] Có cần chức năng "khôi phục cấu hình trước đó" tự động (giống FR-01)
      ngay ở bản này, hay audit log thủ công là đủ cho giai đoạn này? (Đề
      xuất: chưa cần, để lại phiên bản sau.)
