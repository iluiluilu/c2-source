# Tech Spec: auth-session

> File: `docs/03-dev/specs/auth-session.md` — Owner: DEV
> Nguồn story: `docs/02-ba/stories/auth-session.md`
> Status: Approved

> ## ⚠️ Thứ tự triển khai chung (đã chốt) — đọc trước khi implement
>
> Flyway trong repo **không** bật `out-of-order`
> (`backend/src/main/resources/application.yml`), nên **số hiệu migration
> bắt buộc khớp đúng thứ tự merge** — không thể chèn số nhỏ hơn về sau.
> Migration đang có trong repo: `V1`–`V6`.
>
> | # | Spec | Migration |
> |---|---|---|
> | 0 | *(hạ tầng, không migration)* | Redis + RabbitMQ vào `docker-compose*.yml` + `backend/pom.xml` — dựng **một lần**, dùng chung |
> | 1 | `system-config` | `V7__init_system_config.sql` |
> | 2 | `account-lifecycle` | `V8__init_account.sql` |
> | 3 | `warehouse-structure` | `V9__warehouse_structure_v2.sql` |
> | 4 | `data-ingestion` | `V10__data_ingestion.sql` |
> | 5 | `personal-storage-quota` | `V11__personal_storage_quota.sql` |
> | 6 | `rbac-group-scope` | `V12__init_groups.sql` + `V13__drop_case_files.sql` |
> | 7 | `auth-session` ⬅ **spec này** | `V14__init_keycloak_event_cursor.sql` |
>
> `rbac-group-scope` và `auth-session` không phụ thuộc spec nào khác nên
> **được phép code song song ngay từ đầu** — bảng trên chỉ ràng buộc thời
> điểm *merge*, không ràng buộc thời điểm *phát triển*.

## 0. Kết quả verify Keycloak + nợ kỹ thuật (2026-08-12)

Đã kiểm tra trực tiếp trên repo (Keycloak **24.0.0**,
`keycloak/realm-export.json`, `KeycloakAdminService.java`).

### 0.1 Đã chốt

| # | Vấn đề | Kết luận |
|---|---|---|
| 1 | Realm chưa bật events + brute force | ⛔ **Chặn happy path.** `realm-export.json` **không có** `eventsEnabled` lẫn `bruteForceProtected` (đều mặc định `false`). Phải đưa vào file này — xem 0.2. |
| 2 | Service account thiếu quyền đọc events | ⚠️ **Đính chính:** bản trước của mục này ghi service account *không có quyền gì* — **sai**, do kiểm nhầm trường (`realmRoles` trên user thay vì `clientRoles`). Thực tế user `service-account-backend` đã có `realm-management`: `view-realm`, `view-users`, `manage-users`, `query-users` — đủ cho toàn bộ FR-04/05/06 và cho `logoutUser`. Thứ **thật sự thiếu** chỉ là **`view-events`**, cần cho `GET /admin/realms/{realm}/events` (mục 4.2); thiếu nó thì poller nhận `403`. |
| 3 | Tên event type của lockout | ✅ **ĐÃ VERIFY THỰC NGHIỆM** trên KC 24.0.0 (2026-08-12), không còn phải đoán — xem mục 0.3 để lấy payload thật. Kết quả: **`USER_DISABLED_BY_TEMPORARY_LOCKOUT` CÓ tồn tại trên KC 24** và là event nên dùng. Hai phỏng đoán trước đó (`LOGIN_ERROR` + `details.error='user_temporarily_disabled'`, và "type riêng chỉ có ở bản sau") **đều sai**. |
| 4 | `temporary=true` có tự set `UPDATE_PASSWORD` không | **Có** — không cần thêm lệnh `PUT` nào. Bằng chứng ngay trong code: `KeycloakAdminService.verifyPassword` có nhánh `NOT_FULLY_SET_UP` với comment *"a pending UPDATE_PASSWORD required action after an FR-06 temporary reset"*, và `changeOwnPassword` có message "Tài khoản đang chờ đổi mật khẩu bắt buộc". Nhánh đó không thể tồn tại nếu hành vi này không đúng. Hạ từ rủi ro xuống **1 smoke test**. |
| 5 | Reset mật khẩu không chấm dứt phiên | **Thêm `logoutUser(id)` sau khi reset** (mục 4.5). Đổi mật khẩu mà không cắt phiên thì việc thu hồi chỉ làm một nửa: R3 reset để giành lại tài khoản bị chiếm, nhưng phiên kẻ tấn công vẫn sống tới khi token hết hạn. |

### 0.3 Payload event thật (đo trên KC 24.0.0, 2026-08-12)

Dựng Keycloak sạch từ `realm-export.json` đã sửa, đăng nhập sai 6 lần cho
`canbo1` rồi đọc `GET /admin/realms/aismartstore/events`. Kết quả nguyên văn:

```jsonc
// Sự kiện khoá — DÙNG CÁI NÀY cho Story 2 AC-4
{
  "type": "USER_DISABLED_BY_TEMPORARY_LOCKOUT",
  "userId": "e82192ea-...", "ipAddress": "172.22.0.1",
  "details": {
    "reason": "brute_force_attack detected",
    "not_before": "2026-08-12T09:54:29",  // thời điểm hết khoá -> đưa vào audit metadata
    "num_failures": "2"
  }
}

// Đăng nhập sai thường (qua direct grant / password)
{
  "type": "LOGIN_ERROR",
  "userId": "e82192ea-...", "ipAddress": "172.22.0.1",
  "details": {
    "auth_method": "openid-connect", "grant_type": "password",
    "client_auth_method": "client-secret", "username": "canbo1"
  }
}
```

**Ba điều rút ra, ảnh hưởng trực tiếp tới `AuthEventsConsumer`:**

1. **Phân biệt bằng `type`, KHÔNG bằng `details.error`.** `LOGIN_ERROR` qua
   đường password grant **không hề có trường `error`** trong `details` —
   mọi phương án map dựa trên `details.error` sẽ không bao giờ khớp. Lockout
   là một `type` riêng biệt, cứ so `type` là đủ.
2. **`userId` luôn có, `username` thì không.** Event lockout **không** chứa
   `username`; muốn ghi tên vào `audit_log` phải tra thêm
   `kc.getUser(userId)`, hoặc chấp nhận chỉ lưu `userId`.
3. **Chỉ mới verify đường direct grant** (`grant_type=password`, đúng đường
   `verifyPassword` dùng). Đăng nhập thật của người dùng đi qua NextAuth =
   authorization code flow, `details` của `LOGIN_ERROR` ở đó **có thể khác**
   (thường có thêm `error`). Cần đo thêm một lần khi FE sẵn sàng — không
   chặn việc code mapping cho lockout.

### 0.2 Nợ kỹ thuật — chưa quyết, đánh giá sau

Không mục nào dưới đây chặn happy path MVP.

| # | Vấn đề | Vì sao hoãn |
|---|---|---|
| N1 | **R3 reset mật khẩu chính mình qua endpoint admin** — `AccountsService.resetPassword` không có self-check, khác `setStatus` (chặn rõ ở dòng 234). Endpoint admin **không yêu cầu mật khẩu cũ**, nên ai chiếm được phiên R3 có thể đổi mật khẩu chính tài khoản đó mà không cần biết mật khẩu hiện tại. | Chỉ là phòng thủ theo chiều sâu, **giá trị vừa phải**: kẻ tấn công đã có phiên R3 thì có thể `POST /accounts` tạo admin mới, nên đây không phải mắt xích yếu nhất. Lưu ý: thêm `logoutUser` (mục 5 ở trên) **không** đóng được lỗ này — kẻ tấn công đăng nhập lại ngay bằng mật khẩu vừa đặt. Cách đóng thật là chặn `id == user.sub()` và hướng sang `PUT /accounts/me/password` (có xác minh mật khẩu cũ). |
| N2 | **Tự đổi mật khẩu qua `/accounts/me/password` có nên logout không** | Keycloak Admin logout cắt **toàn bộ** phiên, không chừa phiên hiện tại — người vừa đổi mật khẩu sẽ bị đăng xuất ngay. Nhiều hệ thống làm vậy, nhưng đây là thay đổi UX cần xác nhận, không phải hệ quả kỹ thuật bắt buộc. |
| N3 | **`temporary` mặc định `true`** khi R3 reset (`dto.temporary() == null \|\| dto.temporary()`) — nếu N1 được mở cho phép tự reset thì R3 sẽ tự đẩy mình vào trạng thái buộc đổi mật khẩu. | Phụ thuộc N1; quyết cùng lúc. |

## 1. Tóm tắt kỹ thuật

- **Story 1 (đăng nhập):** không cần code mới ở backend/luồng token —
  NextAuth `KeycloakProvider` + `[...nextauth]/route.ts` đã cấp token đúng
  vai trò (AC-1), Keycloak tự trả lỗi generic khi sai thông tin (AC-2, không
  cần code phân biệt). Việc còn thiếu là **audit sự kiện login** (AC-3) —
  xem cơ chế polling ở mục 4.2. AC-4 (phản hồi tức thời) đã đúng vì luồng
  đăng nhập không đụng hàng đợi nền.
- **Story 2 (lockout):** cấu hình Brute Force Detection trực tiếp trong
  Keycloak realm (không code, chỉ cấu hình/tài liệu vận hành — AC-1–AC-3 do
  Keycloak tự xử lý). Việc cần code là đồng bộ sự kiện lockout vào
  `audit_log` (AC-4) — dùng chung cơ chế polling của Story 1.
- **Cơ chế polling + queue (Story 1 AC-3, Story 2 AC-4):** thêm
  `@Scheduled` job (`KeycloakEventsPoller`, chu kỳ 30s) gọi
  `GET /admin/realms/{realm}/events` qua `KeycloakAdminService` (mở rộng
  thêm 1 method), publish sự kiện thô vào RabbitMQ queue `auth-events`;
  `@RabbitListener` (`AuthEventsConsumer`) tiêu thụ, map sang schema
  `AuditService.AuditEntry` đã chốt ở BA, ghi qua `AuditService.record`.
  Cần bật "Save events" trong Keycloak realm (cấu hình, không phải code).
- **Hạ tầng RabbitMQ:** thêm service `rabbitmq` vào `docker-compose.yml`/
  `docker-compose.dev.yml`, dependency `spring-boot-starter-amqp` vào
  `pom.xml` — dùng chung với hàng đợi xử lý AI (`ai_queue.*`,
  `system-config.md`), chỉ dựng 1 lần (rà soát trước khi thêm để tránh
  trùng nếu một spec khác đã làm).
- **Story 3 (đăng xuất/hết phiên):** đăng xuất chủ động dùng `signOut()`
  của NextAuth (đã có hạ tầng, chưa có nút — cần thêm UI). Cảnh báo trước
  khi hết phiên (AC-3) là **component mới hoàn toàn** ở frontend
  (`SessionExpiryWarning`), dựa trên `expiresAt` đã có sẵn trong JWT/session
  callback. Ghi audit logout chủ động qua endpoint mới `POST /auth/logout`
  (backend, vì NextAuth chạy phía Next.js server, không tự có quyền ghi
  `audit_log` của Spring Boot); auto-logout do hết phiên ghi qua polling
  event `LOGOUT`/`REVOKE_GRANT` của Keycloak giống Story 1/2 (không cần gọi
  ngược từ frontend).
- **Story 4 (tự đổi mật khẩu):** đã có sẵn 100% —
  `PUT /accounts/me/password` (`AccountsController.changeOwnPassword`) +
  `AccountsService.changeOwnPassword` đáp ứng AC-1–AC-3. Chỉ cần bổ sung UI
  frontend (trang đổi mật khẩu) nếu chưa có — rà soát khi implement.
- **Story 5 (R3 reset mật khẩu):** đã có sẵn 100% —
  `PUT /accounts/{id}/password` (`AccountsController.resetPassword`) +
  `ResetPasswordDto(password, temporary)` đáp ứng AC-1/AC-2/AC-4;
  `temporary=true` (mặc định) tương đương buộc đổi ở lần đăng nhập kế tiếp
  (Keycloak tự set `requiredActions=UPDATE_PASSWORD` khi `temporary=true`
  qua endpoint `reset-password` — cần xác nhận hành vi chính xác này khi
  code, xem Rủi ro). Chỉ cần UI R3 quản lý accounts gọi đúng endpoint này
  (rà soát khi implement, trang `admin/accounts` đã tồn tại).

## 2. Data model

Không có bảng nghiệp vụ mới. Toàn bộ trạng thái session/lockout do Keycloak
quản lý (đúng Quyết định 1 SA — "tham số Keycloak hỗ trợ sẵn thì cấu hình
trực tiếp trong Keycloak, không đồng bộ `system-config`"). `audit_log`
(bảng có sẵn, không cần cột/migration mới) là nơi lưu vết — schema
`metadata` theo đúng bảng đã chốt ở BA:

```
action:        auth.login.success | auth.login.failure | auth.logout |
               auth.logout.auto | auth.password.change | auth.password.reset |
               auth.lockout
actor:         username thực hiện hành động
actorSub:      Keycloak sub của actor
resourceType:  "account"
resourceId:    username/id tài khoản bị tác động
outcome:       success | failure
ip:            IP nguồn (từ Keycloak event hoặc request hiện tại)
metadata:      {"reason": ...} | {"failedAttempts": N, "lockoutDurationSeconds": X} |
               {"targetUsername": ..., "forceChangeOnNextLogin": bool}
```

Cần một cấu trúc **con trỏ polling** (cursor) để tránh đọc trùng/bỏ sót sự
kiện Keycloak — lưu trong PostgreSQL (bảng mới, nhỏ, 1 dòng):

```sql
-- V14__init_keycloak_event_cursor.sql
CREATE TABLE keycloak_event_cursor (
    id                BOOLEAN PRIMARY KEY DEFAULT true CHECK (id),  -- singleton row
    last_event_time   BIGINT NOT NULL DEFAULT 0  -- epoch millis của event mới nhất đã publish
);
INSERT INTO keycloak_event_cursor (id, last_event_time) VALUES (true, 0);
```

*(Số `V14` theo thứ tự triển khai đã chốt ở đầu file — migration cuối
của nhóm 7 spec. Spec này độc lập nên được phép code song song ngay từ
đầu, chỉ cần merge sau `V13`.)*

## 3. API contract

### 3.1 Endpoint mới

| Method | Endpoint | Request | Response |
|---|---|---|---|
| POST | `/auth/logout` | – (dùng bearer token hiện tại) | `204` — ghi audit `auth.logout` cho user hiện tại, KHÔNG gọi Keycloak revoke (Keycloak session vẫn do NextAuth quản lý qua `signOut()` phía client; endpoint này chỉ đảm bảo có audit event tức thời — Story 3 AC-1/AC-4) |

Không cần endpoint reset-password/change-password mới — dùng lại
`PUT /accounts/{id}/password` (Story 5) và `PUT /accounts/me/password`
(Story 4) đã có.

### 3.2 Nội bộ (không expose ra ngoài, chạy trong tiến trình backend)

| Thành phần | Vai trò |
|---|---|
| `KeycloakAdminService.listEvents(long dateFromEpochMs)` (method mới) | Gọi `GET /admin/realms/{realm}/events?dateFrom=<ISO date>&max=100`, trả `List<KcEvent>` |
| `KeycloakEventsPoller` (`@Scheduled(fixedDelay = 30000)`) | Đọc cursor DB → gọi `listEvents` → publish từng event mới vào RabbitMQ `auth-events` → cập nhật cursor (chỉ cập nhật sau khi publish thành công toàn bộ batch, để tránh mất event nếu publish giữa batch lỗi) |
| `AuthEventsConsumer` (`@RabbitListener(queues = "auth-events")`) | Map `KcEvent.type` → `AuditService.record` theo schema mục 2. Trên **KC 24** (đã đo thực tế, mục 0.3): `LOGIN`, `LOGOUT`, `LOGIN_ERROR` (đăng nhập sai) và **`USER_DISABLED_BY_TEMPORARY_LOCKOUT`** (bị khoá — Story 2 AC-4). Phân biệt hoàn toàn bằng **`type`**; `details.error` **không tồn tại** ở đường password grant nên không được dựa vào. Event lockout không có `username`, chỉ có `userId` + `details.reason`/`not_before`/`num_failures`. |

Keycloak event types cần xác nhận chính xác tên khi code (rà soát output
thật của `/admin/realms/{realm}/events` — tên sự kiện lockout có thể là
`LOGIN_ERROR` với `error=user_temporarily_disabled` trong `details`, chứ
không phải một type riêng — xem Rủi ro).

### 3.3 Frontend

| Thành phần | Vai trò |
|---|---|
| `SessionExpiryWarning` (component mới, mount ở layout gốc cạnh `SessionErrorGuard`) | Theo dõi `session` (từ `useSession()`), tính `msUntilExpiry = expiresAt*1000 - Date.now()`; khi còn ≤ 60s, hiện modal đếm ngược. "Tiếp tục" → gọi `getSession()`/force refresh (NextAuth tự refresh khi gọi lại `jwt` callback do token gần hết hạn — cần trigger bằng `update()` của `useSession` hoặc gọi 1 API bất kỳ để trigger round-trip). "Đăng xuất" hoặc hết đếm ngược → `signOut()` (đồng thời gọi `POST /auth/logout` trước khi signOut để đảm bảo audit — Story 3 AC-3/AC-4) |
| Nút "Đăng xuất" ở `AppShell`/header (nếu chưa có — rà soát khi implement) | Gọi `POST /auth/logout` rồi `signOut({ callbackUrl: '/' })` |

## 4. Luồng xử lý chính

### 4.1 Đăng nhập (Story 1) — luồng có sẵn, chỉ thêm audit gián tiếp

1. User submit form đăng nhập (NextAuth `KeycloakProvider`, authorization
   code + PKCE) → Keycloak xác thực → trả token → `jwt` callback lưu
   `accessToken`/`refreshToken`/`expiresAt`/`roles` vào JWT session
   (đã có, không đổi).
2. Sự kiện login thành công/thất bại **không** ghi trực tiếp từ web/backend
   tại thời điểm này (không có request nào tới Spring Boot trong bước
   này) — được đồng bộ **gián tiếp** qua polling (mục 4.2) trong vòng tối
   đa 30 giây (AC-3, chấp nhận độ trễ theo BA).
3. AC-4 (phản hồi tức thời): không đổi luồng hiện tại, do đăng nhập không
   qua backend Spring Boot/hàng đợi.

### 4.2 Polling Keycloak events → RabbitMQ → audit_log (Story 1 AC-3, Story 2 AC-4)

1. `KeycloakEventsPoller.poll()` chạy mỗi 30s: đọc `last_event_time` từ
   `keycloak_event_cursor`.
2. Gọi `KeycloakAdminService.listEvents(lastEventTime)` → nhận danh sách
   event mới (sắp theo thời gian tăng dần để xử lý đúng thứ tự).
3. Với mỗi event: publish message vào RabbitMQ queue `auth-events` (payload
   = event thô dạng JSON, để consumer tự map — tách rời phần đọc Keycloak
   và phần ghi audit, dễ retry độc lập).
4. Sau khi publish hết batch thành công: `UPDATE keycloak_event_cursor SET
   last_event_time = <timestamp event cuối cùng>`.
5. Nếu publish lỗi giữa batch: **không** cập nhật cursor → lần poll kế tiếp
   đọc lại từ vị trí cũ → có thể publish trùng một số event → `
   AuthEventsConsumer` cần idempotent hoặc chấp nhận trùng audit hiếm gặp
   (audit_log là append-only, trùng 1 dòng hiếm khi lỗi mạng không phải vấn
   đề nghiêm trọng — ghi rõ trong test).
6. `AuthEventsConsumer` nhận message → map `type`/`details` → gọi
   `AuditService.record(...)` với `action` tương ứng (`auth.login.success`,
   `auth.login.failure`, `auth.lockout`...). Lỗi ghi DB → RabbitMQ tự
   redeliver theo cấu hình retry/DLQ (Task 3).

### 4.3 Đăng xuất chủ động (Story 3 AC-1/AC-4)

1. User bấm "Đăng xuất" → frontend gọi `POST /auth/logout` (bearer token
   hiện tại) → backend ghi audit `auth.logout` (actor = user hiện tại,
   outcome = success) đồng bộ (không cần async vì đây là hành động rời hệ
   thống, không cần phản hồi nhanh cho hành động kế tiếp).
2. Frontend gọi `signOut({ callbackUrl: '/' })` (NextAuth) → xoá session
   cookie → JWT phía server bị vô hiệu ở lần dùng kế tiếp (không set lại
   `token`).
3. Truy cập lại route cần xác thực → NextAuth/middleware chuyển hướng
   login (AC-1 — cơ chế đã có, xác nhận bằng test).

### 4.4 Cảnh báo hết phiên + refresh/tự đăng xuất (Story 3 AC-3)

1. `SessionExpiryWarning` theo dõi `session.expiresAt` (cần expose field
   này ra `Session` type nếu chưa có — hiện `next-auth.d.ts` mới có ở
   `JWT`, chưa có ở `Session`; bổ sung `expiresAt` vào `session` callback
   và type — xem Task 5).
2. Khi còn ≤ 60s: hiện modal đếm ngược 60 → 0.
3. Nhánh "Tiếp tục": gọi `update()` của `useSession()` (built-in NextAuth,
   trigger lại `jwt` callback phía server → do `expiresAt` gần hết hạn nên
   callback tự gọi `refreshAccessToken` — logic đã có, không cần code mới
   trong `route.ts`) → đóng modal, đồng hồ tính lại theo `expiresAt` mới.
4. Nhánh "Đăng xuất" hoặc hết đếm ngược không thao tác: gọi
   `POST /auth/logout` (nếu access token còn hợp lệ; nếu đã hết hạn hẳn,
   bỏ qua bước này, không hiển thị lỗi kỹ thuật theo AC-3) rồi `signOut()`.
5. Nếu refresh token cũng hết hạn (nhánh "Tiếp tục" thất bại): `jwt`
   callback trả `error: "RefreshAccessTokenError"` (đã có) →
   `SessionErrorGuard` (component có sẵn) tự `signOut()` — không cần code
   thêm, chỉ cần đảm bảo `SessionExpiryWarning` không che mất luồng này
   (test tích hợp 2 component cùng hoạt động).
6. Auto-logout do hết phiên (không phải do đóng tab) được **audit gián
   tiếp** qua sự kiện `LOGOUT`/session-expire từ Keycloak polling (mục 4.2)
   — action `auth.logout.auto`, không phải do endpoint `/auth/logout` gọi
   trực tiếp (vì trường hợp mất kết nối/đóng trình duyệt trước khi kịp gọi
   API).

### 4.5 Đổi mật khẩu (Story 4) và R3 reset mật khẩu (Story 5)

Dùng lại `AccountsService.changeOwnPassword` (verify qua Keycloak password
grant → reset nếu đúng → audit) và `AccountsService.resetPassword` (R3 gọi
`reset-password` Admin API, audit kèm `targetUsername`/`temporary`).

**Thay đổi duy nhất (mục 0.1 điểm 5): `AccountsService.resetPassword` gọi
thêm `kc.logoutUser(id)` ngay sau khi reset thành công**, trước bước audit:

1. `kc.getUser(id)` → lấy `username` (giữ nguyên).
2. `kc.resetPassword(id, dto.password(), temporary)` (giữ nguyên).
3. **Mới:** `kc.logoutUser(id)` — chấm dứt mọi phiên của tài khoản vừa bị
   đổi mật khẩu. **Best-effort**: lỗi thì `log.error` + đánh dấu
   `logoutCallFailed: true` trong audit metadata, **không** rollback mật
   khẩu (mật khẩu mới đã có hiệu lực, revert sẽ tệ hơn). Cùng quy ước
   best-effort với `account-lifecycle.md` mục 4.3 bước 5.
4. Audit `account.password_reset` kèm `targetUsername`/`temporary`
   (+ `logoutCallFailed` nếu có).

**Phạm vi:** chỉ áp cho endpoint FR-06 (`AccountsService.resetPassword`).
**KHÔNG** áp cho lời gọi `kc.resetPassword` trong `create()` — tài khoản
vừa tạo chưa có phiên nào, gọi logout ở đó là lãng phí một lượt Admin API.

`kc.logoutUser(id)` do `account-lifecycle.md` Task 2 định nghĩa — spec này
**tái dùng**, không tự thêm method.

Chưa quyết: có chặn R3 tự reset chính mình không (N1), và
`/accounts/me/password` có logout không (N2) — xem mục 0.2.

## 5. Test plan

- **Unit test:**
  - `KeycloakEventsPoller`: cursor chỉ cập nhật sau khi publish hết batch
    thành công; nếu publish lỗi giữa batch, cursor giữ nguyên (test bằng
    mock RabbitTemplate ném exception ở event thứ 2/3).
  - `AuthEventsConsumer`: map đúng từng `KcEvent.type` sang `action` audit
    tương ứng theo bảng schema mục 2; test riêng case lockout (`LOGIN_ERROR`
    với `details.error = "user_temporarily_disabled"` hoặc type thực tế xác
    nhận được khi code) → `action = "auth.lockout"`.
  - `changeOwnPassword`/`resetPassword`: đã có thể có test sẵn — rà soát,
    bổ sung nếu thiếu case `NOT_FULLY_SET_UP`/`DISABLED` (Story 4 AC-1/AC-2).
- **Integration test (backend):**
  - `POST /auth/logout` với token hợp lệ → `204` + 1 audit event
    `auth.logout` mới nhất khớp actor — Story 3 AC-1/AC-4.
  - `POST /auth/logout` không có token → `401` (không audit vì không xác
    định được actor).
  - `PUT /accounts/me/password` sai mật khẩu hiện tại → `400` + audit
    `outcome=failure` — Story 4 AC-1.
  - `PUT /accounts/me/password` mật khẩu mới vi phạm policy Keycloak →
    lỗi từ Keycloak được surface đúng, audit không ghi (vì lỗi xảy ra
    trước khi tới bước ghi audit thành công) — Story 4 AC-2.
  - `PUT /accounts/{id}/password` chỉ R3 gọi được (`403` cho role khác) +
    audit `account.password_reset` chứa `targetUsername`/`temporary` —
    Story 5 AC-1/AC-2/AC-3/AC-4.
  - Poller + consumer end-to-end (dùng Testcontainers RabbitMQ + mock/stub
    Keycloak Admin Events response): 1 batch event giả → đúng số lượng
    audit event mới xuất hiện trong `audit_log`, đúng `action`/`metadata`.
- **Manual/E2E cần cover (không tự động hoá đầy đủ do phụ thuộc Keycloak
  thật):**
  - Đăng nhập sai 1 vài lần rồi đúng → không lộ chi tiết phân biệt sai
    user/sai password (Story 1 AC-2) — kiểm bằng cách xác nhận Keycloak trả
    thông báo generic (cấu hình có sẵn, không phải code tự viết).
  - Đăng nhập sai đủ ngưỡng cấu hình Brute Force → tài khoản bị khoá, thử
    đăng nhập đúng trong thời gian khoá vẫn bị từ chối; hết thời gian khoá
    → đăng nhập lại được — Story 2 AC-1–AC-3 (do Keycloak xử lý, test bằng
    thao tác tay + xác nhận audit event `auth.lockout` xuất hiện trong ≤ 30s
    sau đó).
  - Modal đếm ngược xuất hiện đúng lúc còn 60s, "Tiếp tục" làm mới access
    token và tắt modal, "Đăng xuất"/hết giờ tự đăng xuất — Story 3 AC-3.
- **Case biên/lỗi cần cover:**
  - RabbitMQ tạm thời down khi publish: job polling ném lỗi → cursor không
    cập nhật → lần sau tự retry đọc lại đúng batch (không mất event).
  - RabbitMQ down khi consumer đang chạy: message tồn lại trong queue,
    không mất (do broker), consumer tự xử lý lại khi kết nối lại.
  - Refresh token đã hết hạn khi người dùng bấm "Tiếp tục": không hiển thị
    lỗi kỹ thuật, tự chuyển sang luồng đăng xuất (Story 3 AC-3, nhánh "nếu
    refresh token cũng đã hết hạn").
  - Reset mật khẩu cho chính tài khoản của R3 qua `PUT /accounts/{id}/
    password` (id = chính R3) — xác nhận có bị chặn hay không (BA không có
    AC nào chặn việc này, khác với `setStatus` đã chặn tự khoá chính mình —
    cần quyết định khi code, xem Rủi ro).

## 6. Task breakdown

- [x] **Task 0 — ĐÃ LÀM (2026-08-12)**: `keycloak/realm-export.json`
      đã thêm ở cấp realm `eventsEnabled: true`, `eventsExpiration: 604800`,
      `adminEventsEnabled: true`, `adminEventsDetailsEnabled: true`,
      `bruteForceProtected: true`, `permanentLockout: false`,
      `failureFactor: 5`, `waitIncrementSeconds: 60`,
      `maxFailureWaitSeconds: 900`, `minimumQuickLoginWaitSeconds: 60`,
      `quickLoginCheckMilliSeconds: 1000`, `maxDeltaTimeSeconds: 43200`;
      và thêm `view-events` vào `clientRoles.realm-management` của user
      `service-account-backend`. JSON đã validate.
      - `permanentLockout: false` là **bắt buộc** cho Story 2 AC-3 ("hết thời
        gian khóa, đăng nhập lại bình thường") — nó cũng quyết định
        `details.error` là `user_temporarily_disabled` (mục 0.1 điểm 3) thay
        vì `user_disabled`.
      - `enabledEventTypes` **cố ý không set**: để trống thì Keycloak ghi bộ
        type mặc định (đã bao gồm `LOGIN`/`LOGIN_ERROR`/`LOGOUT`). Liệt kê
        tường minh sẽ chính xác hơn nhưng rủi ro gõ sai tên type khiến event
        bị bỏ im lặng — cân nhắc siết lại sau khi Task 4 xác nhận tên thật.
      - ⚠️ **`failureFactor: 5` là giá trị DEV tự chọn** — BA AC-1 chỉ nói
        "dùng cấu hình Brute Force Detection của Keycloak", không chốt số.
        Mặc định gốc của Keycloak là 30 (quá lỏng cho hệ thống nội bộ). Cần
        người duyệt xác nhận 5 lần / tối đa 15 phút có phù hợp không.
      - ⚠️ **`failureFactor` KHÔNG phải cơ chế duy nhất gây khoá** — phát
        hiện khi verify: thử sai 6 lần liên tiếp bằng script, tài khoản bị
        khoá ở `num_failures: 2`, tức **`quickLoginCheckMilliSeconds: 1000`
        + `minimumQuickLoginWaitSeconds: 60`** kích hoạt trước: hai lần sai
        cách nhau dưới 1 giây bị coi là tấn công và khoá ngay 60 giây, bất
        kể `failureFactor`. Người dùng gõ tay hiếm khi chạm, nhưng **test tự
        động và script chắc chắn chạm** — khi viết integration test cho
        Story 2 phải chèn `sleep` giữa các lần thử, nếu không sẽ test nhầm
        nhánh quick-login thay vì nhánh `failureFactor`.
      - ⚠️ **`--import-realm` là no-op nếu realm đã tồn tại** (ghi rõ trong
        chính `docker-compose.yml`): thay đổi trên **chỉ** có tác dụng với
        môi trường dựng mới, hoặc sau `docker compose down -v`. Môi trường
        đang chạy phải áp tay qua Admin Console/Admin API — **phải ghi vào
        runbook phát hành**, đừng giả định file là đủ.
- [ ] Task 1: Hạ tầng RabbitMQ — thêm service `rabbitmq` vào
      `docker-compose.yml`/`docker-compose.dev.yml`, dependency
      `spring-boot-starter-amqp` vào `pom.xml`, cấu hình
      `spring.rabbitmq.*` (`application.yml`/`application-dev.yml`)
      **(rà soát trước, tránh trùng nếu `system-config`/AI-queue đã thêm)**.
- [ ] Task 2: Migration `keycloak_event_cursor` (bảng singleton-row).
- [ ] Task 3: `KeycloakAdminService.listEvents(dateFrom)` (method mới) +
      khai báo queue/exchange `auth-events` (kèm DLQ theo pattern chuẩn của
      Spring AMQP) trong `RabbitMqConfig` (class mới).
- [ ] Task 4: `KeycloakEventsPoller` (`@Scheduled`, đọc/ghi cursor, publish
      RabbitMQ) + `AuthEventsConsumer` (`@RabbitListener`, map event →
      `AuditService.record`) — xác nhận chính xác tên/field các Keycloak
      event type liên quan (login success/failure/lockout/logout) trước
      khi hoàn thiện mapping.
- [ ] Task 5: Endpoint `POST /auth/logout` (`AuthController` mới, package
      `com.aismartstore.backend.auth` hoặc `accounts`) — ghi audit
      `auth.logout` đồng bộ.
- [ ] Task 5b: `AccountsService.resetPassword` gọi thêm `kc.logoutUser(id)`
      (mục 4.5) — phụ thuộc `account-lifecycle.md` Task 2 đã có
      `logoutUser`. Chỉ sửa nhánh FR-06, không đụng `create()`.
- [ ] Task 6: Frontend — thêm `expiresAt` vào `Session` type
      (`next-auth.d.ts`) + `session` callback (`route.ts`); component
      `SessionExpiryWarning` (modal đếm ngược, nút Tiếp tục/Đăng xuất);
      mount cạnh `SessionErrorGuard` ở layout gốc.
- [ ] Task 7: Frontend — nút "Đăng xuất" gọi `POST /auth/logout` rồi
      `signOut()` (rà soát `AppShell`/header, bổ sung nếu thiếu).
- [ ] Task 8: Rà soát UI đổi mật khẩu (Story 4) và UI R3 reset mật khẩu
      (Story 5, trang `admin/accounts`) — bổ sung nếu thiếu màn hình gọi
      đúng 2 endpoint đã có sẵn ở backend.
- [ ] Task 9: ~~Cấu hình realm ghi vào tài liệu vận hành~~ → **đã nâng
      thành Task 0** (cấu hình version-controlled, chặn happy path). Giữ
      lại phần tài liệu: ghi vào runbook cách áp cấu hình cho môi trường
      **đã tồn tại** (nơi `--import-realm` không chạy).
- [ ] Task 10: Unit test + integration test theo mục 5.
- [ ] Task 11: Cập nhật trạng thái task trong file spec này (đánh dấu
      done) sau khi implement — không viết lại toàn bộ spec.

## 7. Rủi ro kỹ thuật

- ~~Tên/field Keycloak event chưa xác nhận~~ → ✅ **đã đo thực nghiệm**
  (mục 0.3), payload thật đã có trong spec. Bài học: **hai vòng suy đoán
  trước đó đều sai** — cả `USER_DISABLED_BY_TEMPORARY_LOCKOUT` "chỉ có ở
  bản sau" lẫn "phân biệt bằng `details.error`". Đừng suy luận tiếp về
  schema event Keycloak mà không chạy thử.
  **Rủi ro còn lại (hẹp):** mới đo đường **direct grant**; `LOGIN_ERROR`
  qua authorization code flow (đường NextAuth thật) có thể có `details`
  khác. Không chặn Story 2 AC-4 (lockout đã chắc chắn), chỉ ảnh hưởng độ
  giàu thông tin của audit đăng nhập sai ở Story 1.
- ⛔ **Hỏng âm thầm nếu thiếu Task 0** — rủi ro nghiêm trọng nhất của spec
  này. Với `eventsEnabled=false`, `GET /admin/realms/{realm}/events` trả
  mảng **rỗng và HTTP 200**: poller chạy trơn tru, cursor tiến đều,
  RabbitMQ im lặng, `audit_log` trống, test tích hợp mock-based vẫn xanh.
  Không có tín hiệu lỗi nào. Tương tự, `bruteForceProtected=false` khiến
  **toàn bộ Story 2 không tồn tại** chứ không chỉ thiếu phần audit — spec
  bản trước mô tả AC-1–AC-3 là "do Keycloak tự xử lý", nhưng nó đang tắt.
  Giảm thiểu: Task 0 + ít nhất 1 test end-to-end chạm Keycloak thật (không
  mock) xác nhận có event chảy vào `audit_log`.
- **Độ trễ tối đa 30s của polling** — đã được BA chấp nhận, nhưng cần đảm
  bảo `@Scheduled` job không chồng lấp nếu 1 lần poll chạy lâu hơn chu kỳ
  (dùng `fixedDelay` thay vì `fixedRate`, hoặc thêm lock, để tránh 2 phiên
  polling đọc/ghi cursor đồng thời).
- **RabbitMQ là hạ tầng mới** — cần phối hợp với `system-config.md`
  (`ai_queue.*`) để tránh 2 PR khác nhau cùng thêm cấu hình RabbitMQ gây
  conflict; nên làm Task 1 dùng chung nếu 2 feature implement gần nhau.
- **`temporary=true` trong `reset-password` Admin API có set
  `requiredActions=UPDATE_PASSWORD` tự động hay cần gọi thêm bước riêng?**
  — cần xác nhận hành vi thực tế của Keycloak (tài liệu Keycloak Admin API
  không hoàn toàn rõ ràng giữa các version) trước khi khẳng định Story 5
  AC-3 đã đáp ứng 100% bởi code hiện có; nếu Keycloak không tự set, cần
  thêm 1 lệnh PUT cập nhật `requiredActions` trên user representation.
- **R3 tự reset mật khẩu chính mình qua `PUT /accounts/{id}/password`**
  chưa có AC nào từ BA chặn/cho phép rõ — khác với `setStatus` (đã chặn tự
  khoá chính mình); DEV cần quyết định nhất quán khi code (khuyến nghị:
  cho phép, vì không có rủi ro tương đương "tự khoá tài khoản duy nhất còn
  quyền admin").
- **`POST /auth/logout` ghi audit đồng bộ (không async)** khác với pattern
  `recordAsync` dùng ở hầu hết nơi khác trong codebase — chọn đồng bộ vì
  đây là request cuối cùng trước khi mất token, muốn đảm bảo ghi thành
  công trước khi trả response; cần đo lại nếu `AuditService.record` (đồng
  bộ) có độ trễ đáng kể do cơ chế advisory lock (`pg_advisory_xact_lock`)
  trong `AuditService` hiện tại — rủi ro thấp ở quy mô NFR-03 nhưng nên
  test thời gian phản hồi thực tế.
