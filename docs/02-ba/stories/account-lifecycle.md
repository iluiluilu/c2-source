# User Story: account-lifecycle

> File: `docs/02-ba/stories/account-lifecycle.md` — Owner: BA
> Nguồn kiến trúc: `docs/01-sa/architecture/account-lifecycle.md`
> Status: Approved

R3 quản lý vòng đời tài khoản người dùng (FR-04): tạo, sửa, khóa (tạm thời),
vô hiệu hóa nghỉ việc (dài hạn), mở khóa, và chỉnh hạn mức lưu trữ riêng theo
từng tài khoản. KHÔNG xóa cứng tài khoản có lịch sử thao tác. Danh tính quản
qua Keycloak Admin API; metadata nghiệp vụ (lý do khóa, hạn mức riêng) ở
PostgreSQL. Mọi thao tác ghi `audit_log` (FR-09).

---

## Story 1 — Tạo tài khoản mới

**As a** Quản trị hệ thống (R3)
**I want** tạo tài khoản người dùng mới với vai trò tương ứng
**So that** người dùng mới có thể truy cập hệ thống theo quyền được cấp

### Acceptance Criteria

```
Given tôi là R3 và nhập thông tin tài khoản hợp lệ (định danh, vai trò...)
When tôi lưu tạo mới
Then hệ thống tạo tài khoản trên Keycloak và ghi metadata nghiệp vụ tương ứng
```

- [ ] AC-1: Chỉ R3 tạo được tài khoản; vai trò khác bị chặn.
- [ ] AC-2: Tài khoản mới được tạo với trạng thái `active`.
- [ ] AC-3: Hạn mức lưu trữ được snapshot từ mặc định theo vai trò (tầng 1, `system-config`) vào cột `storage_quota_bytes` **tại đúng thời điểm tạo tài khoản** (theo `personal-storage-quota.md`) — áp dụng cho mọi đường tạo tài khoản (tạo đơn lẻ; import hàng loạt nếu tính năng này được bổ sung sau).
- [ ] AC-4: Thông tin trùng (định danh đã tồn tại) bị từ chối kèm thông báo rõ ràng.
- [ ] AC-5: Thao tác tạo được ghi `audit_log`.

---

## Story 2 — Sửa thông tin tài khoản

**As a** R3
**I want** chỉnh sửa thông tin của một tài khoản
**So that** giữ dữ liệu tài khoản chính xác, cập nhật

### Acceptance Criteria

```
Given tôi là R3 và chọn một tài khoản
When tôi sửa thông tin hợp lệ và lưu
Then hệ thống cập nhật đồng bộ ngay với Keycloak và/hoặc metadata nội bộ
```

- [ ] AC-1: Chỉ R3 sửa được; thay đổi đồng bộ ngay (NFR Performance).
- [ ] AC-2: Dữ liệu không hợp lệ bị từ chối, giá trị cũ giữ nguyên.
- [ ] AC-3: Thao tác sửa được ghi `audit_log`.

---

## Story 3 — Khóa / mở khóa tài khoản (tạm thời)

**As a** R3
**I want** khóa tạm thời một tài khoản kèm lý do và mở khóa lại khi cần
**So that** tạm ngưng truy cập (vd. vi phạm, tạm ngưng công tác) mà vẫn có thể đảo ngược nhanh

### Acceptance Criteria

```
Given tôi là R3 và chọn một tài khoản đang active
When tôi khóa tài khoản kèm lý do
Then trạng thái chuyển sang "locked", phiên hiện hành bị chấm dứt ngay và
     lý do khóa được lưu
```

- [ ] AC-1: Trạng thái `locked` ánh xạ `enabled=false` ở Keycloak nhưng phân biệt với `disabled` bằng metadata (Quyết định 1).
- [ ] AC-2: Lý do khóa (`status_reason`) là **bắt buộc** và hiển thị cho **mọi R3** xem, không chỉ người thực hiện.
- [ ] AC-3: Khi khóa, phiên đang hoạt động của tài khoản bị chấm dứt **ngay** — cơ chế 2 lớp: (1) gọi Keycloak Admin API logout user để revoke refresh token/SSO session, KHÔNG chỉ set `enabled=false`; (2) filter kiểm tra trạng thái tài khoản trên mỗi request để chặn cả access token cũ còn hạn (xem Câu hỏi mở cho DEV).
- [ ] AC-4: Mở khóa đưa tài khoản về `active` và cho đăng nhập lại.
- [ ] AC-5: Khóa và mở khóa đều được ghi `audit_log`.

---

## Story 4 — Vô hiệu hóa tài khoản nghỉ việc (dài hạn)

**As a** R3
**I want** vô hiệu hóa dài hạn một tài khoản (nghỉ việc/chuyển công tác) kèm lý do
**So that** ngừng truy cập lâu dài mà vẫn giữ nguyên lịch sử thao tác để truy vết

### Acceptance Criteria

```
Given tôi là R3 và chọn một tài khoản
When tôi vô hiệu hóa với lý do nghỉ việc/chuyển công tác
Then trạng thái chuyển sang "disabled", phiên hiện hành bị chấm dứt ngay và
     lịch sử thao tác của tài khoản được giữ nguyên
```

- [ ] AC-1: `disabled` là nghiệp vụ khác `locked` (Quyết định 1) — UI hiển thị nhãn phân biệt rõ + lý do (Rủi ro).
- [ ] AC-2: Lý do vô hiệu hóa (`status_reason`) bắt buộc, hiển thị cho mọi R3.
- [ ] AC-3: Phiên đang hoạt động bị chấm dứt ngay khi vô hiệu hóa (cơ chế 2 lớp giống Story 3 AC-3).
- [ ] AC-4: **Hệ thống không cung cấp thao tác xóa cứng tài khoản** ở bất kỳ đâu (UI/API) — chỉ hỗ trợ khóa/vô hiệu hóa; lịch sử thao tác luôn được giữ nguyên vì không có đường nào xóa được tài khoản (NFR Integrity, ràng buộc mục 2; đã chốt).
- [ ] AC-5: Thao tác vô hiệu hóa được ghi `audit_log`.

---

## Story 5 — Chỉnh hạn mức lưu trữ riêng theo từng tài khoản

**As a** R3
**I want** chỉnh hạn mức lưu trữ cá nhân cho một tài khoản cụ thể (override mặc định theo vai trò)
**So that** đáp ứng nhu cầu lưu trữ đặc thù của từng người dùng

### Acceptance Criteria

```
Given tôi là R3 và chọn một tài khoản
When tôi đặt giá trị hạn mức lưu trữ riêng và lưu
Then hệ thống ghi giá trị vào cột storage_quota_bytes của tài khoản đó và áp
     dụng cho các lần upload tiếp theo
```

- [ ] AC-1: Ghi thẳng vào cột `storage_quota_bytes` dùng chung với `personal-storage-quota.md` — KHÔNG tạo bảng/API trùng lặp (Quyết định 2, hệ quả).
- [ ] AC-2: Đổi hạn mức KHÔNG hồi tố xóa tệp đã lưu vượt hạn mức mới; chỉ chặn upload tiếp theo nếu đã vượt.
- [ ] AC-3: Chỉ R3 thực hiện được; dùng chung entrypoint/quyền với các thao tác quản lý tài khoản khác.
- [ ] AC-4: Giá trị không hợp lệ (âm, sai kiểu) bị từ chối.
- [ ] AC-5: Thao tác chỉnh hạn mức được ghi `audit_log`.

---

## Ngoài phạm vi (Out of scope)

- Tự đổi mật khẩu (FR-05) và R3 đặt lại mật khẩu (FR-06) — thuộc `auth-session.md`.
- Phân quyền RBAC (FR-07) — thuộc `rbac-group-scope.md`.
- Giá trị hạn mức **mặc định theo vai trò** (tầng 1) — thuộc `system-config.md` (FR-01).
- Xóa cứng tài khoản — **không hỗ trợ trong MVP dưới mọi hình thức** (đã chốt);
  chỉ có khóa/vô hiệu hóa.
- Import hàng loạt tài khoản (bulk import) — **đã chốt: chưa cần xây ở giai
  đoạn này**, chỉ là ghi chú dự phòng cho tương lai. Nếu về sau cần triển
  khai, chỉ cần đảm bảo đường tạo tài khoản mới đó (dù bằng cơ chế nào) cũng
  áp dụng đúng quy tắc snapshot hạn mức ở Story 1 AC-3.

## Phụ thuộc / Ghi chú kiến trúc liên quan

- Quyết định 1: mô hình 3 trạng thái `active`/`locked`/`disabled`, thêm cột `status_reason` (bắt buộc, hiển thị cho mọi R3).
- Quyết định 2: chỉnh hạn mức riêng thuộc FR-04, dùng chung cột `storage_quota_bytes` với `personal-storage-quota.md`.
- Quản lý danh tính qua Keycloak Admin API (`KeycloakAdminService`); metadata nghiệp vụ ở PostgreSQL.
- Phụ thuộc `audit_log` (FR-09) cho toàn bộ story.
- Đã rà soát `source_v2/backend/.../config/SecurityConfig.java`: backend xác
  thực JWT hoàn toàn offline (chữ ký + hạn dùng), không gọi lại Keycloak hay
  kiểm tra trạng thái tài khoản trên mỗi request — đây là lý do Story 3
  AC-3/Story 4 AC-3 cần thêm filter kiểm tra trạng thái (lớp 2), không thể chỉ
  dựa vào Keycloak logout (lớp 1).
- Đã rà soát `KeycloakAdminService.java`: chưa có method gọi endpoint logout
  user — DEV cần bổ sung mới.
- ⚠️ Đã rà soát `source_v2/backend/.../accounts/`: package này chỉ có
  Controller/Service/DTO, **chưa có JPA entity/bảng DB nào cho "account"**
  (danh tính hiện quản lý 100% qua Keycloak). Bảng tài khoản nội bộ chứa
  `status_reason` (Quyết định 1) và `storage_quota_bytes` (Quyết định 2)
  **cần tạo mới hoàn toàn** — công việc dùng chung với
  `personal-storage-quota.md`, chỉ làm một lần (xem ghi chú tương ứng ở
  file đó).
- Import hàng loạt tài khoản: đã xác nhận với người duyệt — chỉ là ghi chú dự
  phòng, không xây ở giai đoạn này (xem Ngoài phạm vi).
- Bỏ hẳn tính năng xóa cứng tài khoản khỏi MVP: đã xác nhận với người duyệt
  (khớp đúng FR-04 gốc trong SRS — bản tóm tắt chưa từng liệt kê "xóa" trong
  danh sách thao tác tài khoản).

## Câu hỏi mở cho DEV

_Đã chốt cả 3 câu hỏi mở trước đó:_

- ~~Snapshot hạn mức khi tạo tài khoản — nguồn đọc và thời điểm?~~ →
  **Chốt: đọc giá trị mặc định theo vai trò từ `system_config` tại đúng
  thời điểm tài khoản được khởi tạo.** Hiện chỉ có một đường tạo tài khoản
  (Story 1, R3 tạo qua form); import hàng loạt chỉ là ghi chú dự phòng cho
  tương lai, chưa xây ở giai đoạn này (xem Ngoài phạm vi).
- ~~Cách chấm dứt phiên ngay khi khóa/vô hiệu hóa?~~ → **Chốt cơ chế 2
  lớp** (đã kiểm tra code hiện tại — `SecurityConfig.java` xác thực JWT
  hoàn toàn offline, chưa có bước nào gọi lại Keycloak hay kiểm tra trạng
  thái tài khoản mỗi request):
  1. **Lớp 1 (dùng cơ chế Keycloak có sẵn):** gọi Keycloak Admin API
     `POST /admin/realms/{realm}/users/{id}/logout` ngay khi khóa/vô hiệu
     hóa — revoke refresh token và kết thúc SSO session, chặn được việc
     cấp/làm mới access token sau đó. Bổ sung method này vào
     `KeycloakAdminService` (hiện chưa có, đã rà soát code).
  2. **Lớp 2 (bắt buộc bổ sung — vì lớp 1 có giới hạn):** access token đã
     cấp trước đó (JWT, xác thực offline theo chữ ký + hạn dùng) **không**
     bị vô hiệu hóa ngay bởi lệnh logout ở lớp 1 — nó vẫn dùng được cho tới
     khi tự hết hạn (theo thời hạn token đã chốt ở `auth-session.md`). Vì
     Story 3 AC-3/Story 4 AC-3 yêu cầu chấm dứt "ngay", cần thêm một
     filter/interceptor trong Spring Security kiểm tra trạng thái tài
     khoản (`active`/`locked`/`disabled`) trên mỗi request đã xác thực —
     tra bảng tài khoản nội bộ (có cache TTL ngắn ~30–60 giây để không ảnh
     hưởng hiệu năng) — từ chối request (401) nếu tài khoản không còn
     `active`, bất kể access token còn hạn kỹ thuật.
- ~~Định nghĩa "có lịch sử thao tác" để chặn xóa cứng?~~ → **Chốt: bỏ hẳn
  khái niệm "xóa cứng tài khoản" khỏi MVP** — không cung cấp thao tác này
  ở bất kỳ đâu trong UI/API, chỉ có khóa/vô hiệu hóa (đã xác nhận, khớp
  đúng FR-04 gốc trong SRS). Nhờ vậy không cần định nghĩa "có lịch sử thao
  tác" nữa vì không có gì để chặn.

Không còn câu hỏi mở nào cho DEV ở bước BA.
