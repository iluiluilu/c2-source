# User Story: auth-session

> File: `docs/02-ba/stories/auth-session.md` — Owner: BA
> Nguồn kiến trúc: `docs/01-sa/architecture/auth-session.md`
> Status: Approved

4 luồng xác thực/quản lý phiên cơ bản (FR-02/03/05/06) trên nền Keycloak +
NextAuth + Spring Security. Tham số lockout và thời hạn phiên cấu hình trực
tiếp trong Keycloak realm (Quyết định 1, mục 2) — KHÔNG thuộc `system-config`.
Mọi thao tác ghi `audit_log` (FR-09).

---

## Story 1 — Đăng nhập tạo phiên theo vai trò (FR-02)

**As a** người dùng hệ thống (R1/R2/R3)
**I want** đăng nhập bằng tài khoản hợp lệ và được cấp phiên theo đúng vai trò
**So that** tôi truy cập được các chức năng ứng với quyền của mình

### Acceptance Criteria

```
Given tôi nhập đúng tên đăng nhập và mật khẩu của một tài khoản đang hoạt động
When tôi bấm Đăng nhập
Then hệ thống tạo phiên (token qua Keycloak) mang đúng vai trò của tài khoản
     và điều hướng vào ứng dụng
```

- [ ] AC-1: Đăng nhập đúng thông tin → cấp token chứa vai trò (R1/R2/R3) đúng như cấu hình tài khoản.
- [ ] AC-2: Đăng nhập sai thông tin → từ chối, KHÔNG lộ chi tiết (không phân biệt "sai user" hay "sai mật khẩu").
- [ ] AC-3: Sự kiện đăng nhập thành công/thất bại được ghi vào `audit_log` (FR-09).
- [ ] AC-4: Đăng nhập phản hồi tức thời, không phụ thuộc hàng đợi xử lý nền (NFR Performance).

---

## Story 2 — Khóa tài khoản sau N lần đăng nhập sai (FR-02)

**As a** hệ thống / quản trị bảo mật
**I want** tạm khóa tài khoản sau số lần đăng nhập sai vượt ngưỡng và cảnh báo
**So that** chống dò mật khẩu (brute-force)

### Acceptance Criteria

```
Given một tài khoản đã đăng nhập sai liên tiếp đạt ngưỡng cấu hình trong Keycloak
When có thêm một lần đăng nhập (kể cả đúng mật khẩu) trong thời gian khóa
Then hệ thống từ chối và thông báo tài khoản đang tạm khóa
```

- [ ] AC-1: Ngưỡng số lần sai và thời gian khóa dùng cấu hình Brute Force Detection của Keycloak (Quyết định 1) — không có UI/đồng bộ riêng ở module quản trị.
- [ ] AC-2: Trong thời gian khóa, đăng nhập bị từ chối kèm thông báo phù hợp (không lộ chi tiết nhạy cảm).
- [ ] AC-3: Hết thời gian khóa, tài khoản đăng nhập lại bình thường với thông tin đúng.
- [ ] AC-4: Sự kiện lockout của Keycloak được đồng bộ vào `audit_log` để xuất hiện trong nhật ký nghiệp vụ (FR-09/FR-10, Rủi ro) — qua cơ chế **polling Keycloak Admin Events API → publish RabbitMQ → consumer ghi `audit_log`** (xem Câu hỏi mở cho DEV); không viết Keycloak Event Listener SPI (tránh phải build/mount code chạy trong chính Keycloak).

---

## Story 3 — Đăng xuất / hết phiên tự động (FR-03)

**As a** người dùng đã đăng nhập
**I want** chủ động đăng xuất và được cảnh báo trước khi phiên tự hết hạn
**So that** bảo vệ tài khoản và không mất dữ liệu đang thao tác dở

### Acceptance Criteria

```
Given tôi đang có phiên hợp lệ
When tôi bấm Đăng xuất
Then phiên bị chấm dứt và tôi phải đăng nhập lại để tiếp tục
```

- [ ] AC-1: Đăng xuất chấm dứt phiên; truy cập lại chức năng cần xác thực bị chặn cho tới khi đăng nhập lại.
- [ ] AC-2: Phiên tự hết hạn theo thời hạn token (JWT) của Keycloak (mục 2) — không cấu hình riêng ở `system-config`.
- [ ] AC-3: Khi access token (JWT) còn **1 phút** trước khi hết hạn, hệ thống hiển thị popup đếm ngược, với 2 lựa chọn: **"Tiếp tục"** (gọi luồng refresh token — NextAuth lấy access token mới qua Keycloak token endpoint bằng refresh token — đóng popup, đồng hồ nạp lại theo access token mới) hoặc **"Đăng xuất"** (thực hiện đăng xuất ngay, luồng chính của Story 3). Nếu hết thời gian đếm ngược mà không thao tác gì → tự động đăng xuất (áp dụng luôn nếu refresh token cũng đã hết hạn/không hợp lệ, không hiển thị lỗi kỹ thuật cho người dùng).
- [ ] AC-4: Đăng xuất và sự kiện hết phiên được ghi `audit_log`.
- [ ] AC-5: Timeout của Keycloak (SSO session) là nguồn thẩm quyền; NextAuth phản ánh theo, không lệch gây đăng xuất không nhất quán (Rủi ro).

---

## Story 4 — Người dùng tự đổi mật khẩu (FR-05)

**As a** người dùng đã đăng nhập
**I want** đổi mật khẩu sau khi xác thực mật khẩu hiện tại
**So that** tự quản lý bảo mật tài khoản của mình

### Acceptance Criteria

```
Given tôi đã đăng nhập và cung cấp đúng mật khẩu hiện tại
When tôi nhập mật khẩu mới hợp lệ và xác nhận
Then hệ thống cập nhật mật khẩu và áp dụng cho lần đăng nhập kế tiếp
```

- [ ] AC-1: Phải nhập đúng mật khẩu hiện tại mới cho đổi; sai mật khẩu cũ → từ chối.
- [ ] AC-2: Mật khẩu mới phải thỏa chính sách mật khẩu của Keycloak realm; vi phạm → báo lỗi, không đổi.
- [ ] AC-3: Đổi mật khẩu thành công được ghi `audit_log`.

---

## Story 5 — R3 đặt lại mật khẩu tài khoản bất kỳ (FR-06)

**As a** Quản trị hệ thống (R3)
**I want** đặt lại mật khẩu cho một tài khoản bất kỳ mà không cần mật khẩu cũ
**So that** hỗ trợ người dùng khi họ quên/mất mật khẩu

### Acceptance Criteria

```
Given tôi là R3 và chọn một tài khoản cần đặt lại mật khẩu
When tôi đặt mật khẩu mới (không cần nhập mật khẩu cũ)
Then hệ thống cập nhật mật khẩu qua Keycloak Admin API và tài khoản đó dùng
     mật khẩu mới ở lần đăng nhập kế tiếp
```

- [ ] AC-1: Chỉ R3 thực hiện được thao tác này; vai trò khác bị chặn.
- [ ] AC-2: Không yêu cầu mật khẩu cũ (Quyết định 2, dùng Keycloak Admin API reset-password).
- [ ] AC-3: Có tùy chọn "buộc đổi mật khẩu ở lần đăng nhập kế tiếp" (requiredActions=UPDATE_PASSWORD).
- [ ] AC-4: Thao tác đặt lại mật khẩu được ghi `audit_log` (ai đặt lại cho tài khoản nào, khi nào).

---

## Ngoài phạm vi (Out of scope)

- MFA / yếu tố xác thực bổ sung — đã chốt ngoài phạm vi giai đoạn này (mục 7).
- R4 (hệ thống ngoài) xác thực qua API key (FR-32/33) — không thuộc tài liệu này.
- Thay đổi identity provider / thiết kế lại cơ chế xác thực — giữ nguyên Keycloak realm `aismartstore`.
- Cấu hình ngưỡng lockout và thời hạn phiên qua UI `system-config` — cấu hình trực tiếp trong Keycloak.

## Phụ thuộc / Ghi chú kiến trúc liên quan

- Quyết định 1: dùng Brute Force Detection sẵn có của Keycloak (Story 2).
- Quyết định 2: dùng Keycloak Admin API `reset-password` + `UPDATE_PASSWORD` (Story 5).
- Nguyên tắc mục 2: tham số Keycloak hỗ trợ sẵn thì cấu hình trong realm, không đồng bộ `system-config`.
- Phụ thuộc `audit_log` (PostgreSQL) cho toàn bộ story; cần cơ chế đồng bộ sự
  kiện Keycloak (login/lockout) → `audit_log` qua polling + queue (xem dưới).
- Đã rà soát: `AuditLog` entity hiện có (`source_v2/backend/.../audit/AuditLog.java`)
  đủ dùng cho schema đề xuất ở Câu hỏi mở cho DEV — không cần cột/entity mới.
- Đã rà soát: `KeycloakAdminService` hiện có sẵn cơ chế gọi REST tới Keycloak
  Admin API kèm xác thực/retry — tái dùng để gọi endpoint
  `GET /admin/realms/{realm}/events`, không cần client mới.
- ⚠️ **Hạ tầng mới cần bổ sung, chưa có trong repo hiện tại:** đã kiểm tra
  `docker-compose.yml`/`docker-compose.dev.yml` (gốc source_v2) và
  `pom.xml` (`backend/`) — **chưa có RabbitMQ** ở cả hai nơi. Việc dùng
  RabbitMQ cho Story 2 AC-4 đòi hỏi: (1) thêm service `rabbitmq` vào
  docker-compose, (2) thêm dependency `spring-boot-starter-amqp` vào
  `pom.xml`. **Không cần** viết/đóng gói Keycloak Event Listener SPI (đã
  đổi sang polling — xem Câu hỏi mở cho DEV) — nhẹ hơn đáng kể so với
  phương án SPI ban đầu. RabbitMQ này cũng chính là hạ tầng hàng đợi dự
  kiến dùng cho xử lý AI (OCR/STT, tham số `ai_queue.*` ở
  `system-config.md`) — dựng một lần, dùng chung cho cả hai nhu cầu.

## Câu hỏi mở cho DEV

_Đã chốt cả 3 câu hỏi mở trước đó:_

- ~~Cơ chế cảnh báo trước khi phiên hết hạn?~~ → **Chốt: popup đếm ngược ở
  web khi JWT còn 1 phút, "Tiếp tục" → gọi refresh token, "Đăng xuất" hoặc
  hết countdown không thao tác → đăng xuất.** Chi tiết đã cập nhật vào
  Story 3 AC-3.
- ~~Event listener hay polling?~~ → **Chốt: polling, kết hợp queue ở khâu
  phân phối** — tách 2 quyết định độc lập:
  1. **Lấy sự kiện ra khỏi Keycloak: polling**, không viết Keycloak Event
     Listener SPI. Bật "Save events" trong cấu hình realm Keycloak (chỉ là
     cấu hình, không phải code); một `@Scheduled` job trong backend (vd.
     mỗi 30 giây) gọi `GET /admin/realms/{realm}/events?dateFrom=<cursor>`
     qua `KeycloakAdminService` hiện có, tự giữ con trỏ (cursor) theo
     timestamp của sự kiện mới nhất đã xử lý để không đọc trùng/bỏ sót.
     Tránh được việc phải build/đóng gói/bảo trì một Java provider chạy
     bên trong chính Keycloak — đây là phần tốn công nhất nếu dùng SPI,
     và không giảm đi dù có sẵn queue hay không.
  2. **Phân phối tới nơi ghi `audit_log`: qua RabbitMQ.** Job polling sau
     khi lấy được sự kiện mới thì publish vào RabbitMQ (queue riêng, vd.
     `auth-events`) thay vì ghi thẳng; một `@RabbitListener` tiêu thụ và
     ghi vào `audit_log` qua `AuditService` hiện có. Hợp lý vì RabbitMQ dù
     sao cũng cần dựng cho hàng đợi xử lý AI (`ai_queue.*`,
     `system-config.md`) — dùng chung hạ tầng, đồng thời có lợi retry/DLQ
     nếu ghi `audit_log` tạm thời lỗi, nhất quán với các pattern xử lý bất
     đồng bộ khác trong hệ thống.
  - Đánh đổi: độ trễ tối đa ~30 giây (chu kỳ polling) trước khi sự kiện
    xuất hiện trong `audit_log` — chấp nhận được vì đây là nhật ký, không
    phải luồng cần phản hồi tức thời cho người dùng.
- ~~Định dạng bản ghi `audit_log` cho sự kiện auth?~~ → **Đề xuất schema
  thống nhất** (dựa trên các cột có sẵn của `AuditLog` — đã rà soát code,
  không cần cột mới, dùng `metadata` jsonb cho phần đặc thù từng loại sự
  kiện):

  | Trường | Giá trị cho nhóm sự kiện auth |
  |---|---|
  | `action` | `auth.login.success` / `auth.login.failure` / `auth.logout` / `auth.logout.auto` (tự động hết phiên) / `auth.password.change` (Story 4) / `auth.password.reset` (Story 5, do R3 thực hiện) / `auth.lockout` (Story 2) |
  | `actor` | username người thực hiện hành động (với `auth.password.reset`, actor là R3, không phải tài khoản đích) |
  | `actorSub` | Keycloak user id (`sub`) của actor, nếu có |
  | `resourceType` | `"account"` |
  | `resourceId` | username/user id của tài khoản **bị tác động** (tài khoản đăng nhập, hoặc tài khoản đích khi R3 reset mật khẩu — có thể khác `actor`) |
  | `outcome` | `"success"` / `"failure"` |
  | `ip` | IP nguồn của request (Keycloak event cung cấp sẵn) |
  | `metadata` (jsonb) | chi tiết riêng từng loại: `auth.login.failure` → `{"reason": "invalid_credentials"}`; `auth.lockout` → `{"failedAttempts": N, "lockoutDurationSeconds": X}`; `auth.password.reset` → `{"targetUsername": ..., "forceChangeOnNextLogin": true|false}` |

  Áp dụng thống nhất cho cả sự kiện tự sinh từ backend (Story 3/4/5, gọi
  `AuditService` trực tiếp) lẫn sự kiện đồng bộ từ Keycloak qua queue
  (Story 1/2, action `auth.login.*` và `auth.lockout`) — cùng một schema,
  khác nguồn ghi.

Không còn câu hỏi mở nào cho DEV ở bước BA.
