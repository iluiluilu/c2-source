# Tech Spec: account-lifecycle

> File: `docs/03-dev/specs/account-lifecycle.md` — Owner: DEV
> Nguồn story: `docs/02-ba/stories/account-lifecycle.md`
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
> | 2 | `account-lifecycle` ⬅ **spec này** | `V8__init_account.sql` |
> | 3 | `warehouse-structure` | `V9__warehouse_structure_v2.sql` |
> | 4 | `data-ingestion` | `V10__data_ingestion.sql` |
> | 5 | `personal-storage-quota` | `V11__personal_storage_quota.sql` |
> | 6 | `rbac-group-scope` | `V12__init_groups.sql` + `V13__drop_case_files.sql` |
> | 7 | `auth-session` | `V14__init_keycloak_event_cursor.sql` |
>
> `rbac-group-scope` và `auth-session` không phụ thuộc spec nào khác nên
> **được phép code song song ngay từ đầu** — bảng trên chỉ ràng buộc thời
> điểm *merge*, không ràng buộc thời điểm *phát triển*.

## 0. Quyết định đã chốt (2026-08-12)

4 điểm mở của spec này đã được người duyệt chốt; nội dung bên dưới đã phản
ánh đầy đủ. Giữ mục này để bước sau không mở lại tranh luận.

| # | Vấn đề | Quyết định |
|---|---|---|
| 1 | Tài khoản nhiều vai trò thì snapshot quota theo giá trị nào (không AC nào chốt) | **Lấy MAX** các vai trò. Hệ quả có chủ đích: với số hiện tại (R1=20GB, R2=50GB, R3=20GB), tài khoản R2+R3 nhận 50GB — nhiều hơn R3 đơn lẻ. |
| 2 | Tài khoản Keycloak `enabled=false` có trước bảng `account` bị hiển thị `active` | **Task vận hành thủ công sau deploy** (Task 12): R3 tự rà soát và gán lại `status` + `reason`. Chấp nhận cửa sổ hiển thị lệch; **không** backfill tự động theo cờ `enabled` vì không phân biệt được `locked` cũ với `disabled` cũ. |
| 3 | Cache của `AccountStatusFilter` (lớp 2) | **Chuyển sang Redis dùng chung** (đã dựng ở `V7`). Lý do cũ của spec — "không thêm dependency Redis theo NFR-11" — không còn đúng. Xóa key có hiệu lực trên mọi instance, hết cửa sổ trễ 30–60s. |
| 4 | Khóa/vô hiệu hóa R3 `active` cuối cùng | **Chặn**, trả `409 LAST_ADMIN`. Tránh tình huống không còn ai mở khóa được, phải sửa tay trong DB. |

## 1. Tóm tắt kỹ thuật

- Mở rộng module `accounts` hiện có (`AccountsController`/`AccountsService`/
  `KeycloakAdminService` — hiện **100% Keycloak, không có JPA entity nào cho
  "account"**, đã verify bằng grep) thêm:
  1. Bảng PostgreSQL mới `account` (khóa = `keycloak_user_id`) chứa
     `status`/`status_reason`/`storage_quota_bytes`. **Bảng này dùng chung
     với `personal-storage-quota.md`** (cột `storage_quota_bytes`) — spec
     này định nghĩa schema một lần duy nhất; spec kia chỉ tham chiếu lại,
     không tạo bảng/migration trùng.
  2. `KeycloakAdminService.logoutUser(id)` — method mới, gọi
     `POST /admin/realms/{realm}/users/{id}/logout` (đã verify: **chưa tồn
     tại** trong code hiện tại).
  3. `AccountStatusFilter` — filter Spring Security mới, chèn sau
     `BearerTokenAuthenticationFilter` trong `SecurityConfig` (đã verify:
     `SecurityConfig` hiện chỉ xác thực JWT offline, không có bước nào kiểm
     tra trạng thái tài khoản mỗi request).
  4. Endpoint mới: `PATCH /accounts/{id}/status` (đổi contract, gộp
     khóa/mở khóa/vô hiệu hóa) và `PATCH /accounts/{id}/quota` (Story 5).
- Cơ chế chấm dứt phiên "ngay" dùng **2 lớp**: (1) Keycloak Admin logout
  (best-effort, revoke refresh/SSO) + (2) filter tra bảng `account` mỗi
  request, cache bằng **Redis dùng chung** (TTL 45s làm chặn trên, xóa key
  chủ động khi đổi status) — đây là nguồn xác thực chính, vì lớp 1 không vô
  hiệu hóa access token JWT đang xài. Redis đã có sẵn từ `V7`
  (`system-config.md`), không thêm hạ tầng mới — Quyết định 3 mục 0.
- **Không** có endpoint xóa cứng tài khoản ở bất kỳ đâu (đã bỏ hẳn khỏi
  MVP theo story).
- FE `web/src/app/admin/accounts/page.tsx` hiện gọi
  `PATCH /accounts/{id}/status` với body `{enabled: boolean}` — **breaking
  change**, cần cập nhật FE cùng lúc (xem Task breakdown + Rủi ro).

## 2. Data model

### 2.1 Bảng `account` (mới, dùng chung với `personal-storage-quota.md`)

```sql
-- V8__init_account.sql
-- Thứ tự đã chốt: chạy SAU V7 (system_config). Lưu ý migration này KHÔNG
-- phụ thuộc schema system_config; chỉ code ở tầng service phụ thuộc
-- SystemConfigService (đọc storage_quota.default.* để snapshot, mục 4.1).
CREATE TABLE account (
    keycloak_user_id     VARCHAR(255) PRIMARY KEY,
    status                VARCHAR(16)  NOT NULL DEFAULT 'active',
    status_reason         VARCHAR(500),
    storage_quota_bytes  BIGINT       NOT NULL,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT CK_account_status CHECK (status IN ('active','locked','disabled')),
    -- Quyết định 1 (BA): status_reason bắt buộc khi khác 'active'.
    CONSTRAINT CK_account_status_reason CHECK (status = 'active' OR status_reason IS NOT NULL),
    -- Story 5 AC-4 / personal-storage-quota Story 4 AC-2: snapshot 1 lần, không âm.
    CONSTRAINT CK_account_quota_nonneg CHECK (storage_quota_bytes >= 0)
);

CREATE INDEX IDX_account_status ON account (status);
```

- `keycloak_user_id` = Keycloak `sub` (khóa tự nhiên, 1-1 với user Keycloak;
  không sinh UUID nội bộ riêng để tránh 1 tầng ánh xạ thừa) — cùng kiểu
  `VARCHAR(255)` như `personal_folder.owner_sub`/`personal_file.owner_sub`
  để tiện join sau này nếu cần.
- Không lưu `username`/`email` trùng lặp trong bảng này — nguồn thật vẫn là
  Keycloak, tránh trôi dữ liệu (drift); `AccountsService.list()` vẫn đọc
  profile từ Keycloak như hiện tại, chỉ **join thêm** `status`/
  `status_reason`/`storage_quota_bytes` từ bảng `account`.
- `updated_at`: set thủ công ở tầng service mỗi lần UPDATE (không dùng DB
  trigger — nhất quán với các bảng V1–V6 hiện có, không bảng nào dùng
  trigger cho `updated_at`).
- JPA: `Account` entity (`@Table(name = "account")`, `@Id` = `keycloakUserId`,
  `hibernate.ddl-auto=validate` — nhất quán convention hiện có), `AccountRepository extends JpaRepository<Account, String>`.

### 2.2 Backfill tài khoản Keycloak có sẵn trước khi bảng `account` tồn tại

Không có cách backfill hàng loạt từ SQL thuần (Keycloak là nguồn danh tính,
không nằm trong Postgres). Dùng **lazy get-or-create**: mọi thao tác cần đọc
bảng `account` (list, xem chi tiết, đổi status, đổi quota) gọi
`AccountsService.getOrCreateAccountRow(keycloakUserId, currentRoles)`:

- Nếu có row → dùng luôn.
- Nếu chưa có row (tài khoản tạo trước migration này) → INSERT với
  `status='active'`, `status_reason=NULL`, `storage_quota_bytes =
  MAX(default quota theo currentRoles đọc từ system_config tại thời điểm
  backfill)` — cùng quy tắc MAX với lúc tạo mới (Quyết định 1 mục 0). Giới
  hạn đã biết và **đã chấp nhận**: cách này không phân biệt được `locked`
  cũ vs `disabled` cũ nếu Keycloak `enabled=false` từ trước — đóng lại bằng
  Task 12, xem Quyết định 2 mục 0 và Rủi ro mục 7.
- `AccountStatusFilter` (lớp 2) **không** ghi/backfill — chỉ đọc; nếu
  không thấy row thì coi là `active` (an toàn: chưa từng bị khóa qua tính
  năng mới thì không thể đã bị chặn bởi nó).

## 3. API contract

| Method | Endpoint | Request | Response | Ghi chú |
|---|---|---|---|---|
| `POST` | `/accounts` | `CreateAccountDto` (như hiện tại) | `201 AccountRow` (thêm `status`, `statusReason`, `storageQuotaBytes`) | Story 1. Giữ nguyên hành vi Keycloak hiện có, thêm bước snapshot quota + insert `account` row (mục 4.1). |
| `PATCH` | `/accounts/{id}` | `UpdateAccountDto` (như hiện tại) | `200 AccountRow` | Story 2. Không đổi bảng `account`; role đổi **không** re-snapshot quota (nhất quán Quyết định "snapshot 1 lần"). |
| `GET` | `/accounts` | – | `200 AccountRow[]` | Mở rộng: join `account` (get-or-create theo mục 2.2) để trả `status`/`statusReason`/`storageQuotaBytes`. |
| `PATCH` | `/accounts/{id}/status` | `{ "status": "active"\|"locked"\|"disabled", "reason"?: string }` | `204` | **Thay contract cũ** `{enabled: boolean}`. Gộp Story 3 (lock/unlock) + Story 4 (disable). `reason` bắt buộc nếu `status != "active"`. |
| `PATCH` | `/accounts/{id}/quota` | `{ "storageQuotaBytes": number }` | `200 { storageQuotaBytes }` | Story 5. Ghi thẳng cột dùng chung với `personal-storage-quota.md`, không tạo API/bảng riêng (AC-1). |
| `PUT` | `/accounts/{id}/password` | như hiện tại | như hiện tại | Không đổi — ngoài phạm vi story này. |
| — | *(không có)* | — | — | **Không có endpoint xóa cứng tài khoản** — chủ đích, khớp Story 4 AC-4. |

### Mã lỗi

| Case | HTTP | Điều kiện |
|---|---|---|
| Định danh trùng | `409` | Story 1 AC-4 — username/email đã tồn tại (map từ Keycloak, đã có sẵn `ConflictException`). |
| Role không hợp lệ | `400` | Role ngoài `AssignableRoles.ALL`. |
| `status` không hợp lệ | `400` | Ngoài `active`/`locked`/`disabled`. |
| Thiếu `reason` khi khóa/vô hiệu hóa | `400` | Story 3 AC-2 / Story 4 AC-2 — `reason` rỗng/null khi `status != active`. |
| Tự khóa/vô hiệu hóa chính mình | `400` | Giữ nguyên rule hiện có (`id == user.sub()`), áp dụng cho cả `locked` và `disabled`. |
| Khóa/vô hiệu hóa R3 `active` cuối cùng | `409` `{code: "LAST_ADMIN"}` | Quyết định 4 mục 0 — tránh trạng thái không còn ai mở khóa được, chỉ cứu được bằng sửa tay trong DB. |
| Đổi sang status hiện tại (no-op) | `409` | VD khóa tài khoản **đã** `locked` — tránh audit rác, tín hiệu rõ cho FE (case biên yêu cầu ở test plan). |
| `storageQuotaBytes` âm/sai kiểu | `400` | Story 5 AC-4 — Bean Validation `@Min(0)` + kiểu số nguyên. |
| Không tìm thấy tài khoản | `404` | Keycloak user không tồn tại. |
| Keycloak không khả dụng | `503` | Giữ nguyên `ServiceUnavailableException` hiện có. |
| Token còn hạn nhưng tài khoản không `active` | `401` `{code: "ACCOUNT_LOCKED"|"ACCOUNT_DISABLED"}` | Do `AccountStatusFilter` (lớp 2), không phải do `AccountsService`. |

## 4. Luồng xử lý chính

### 4.1 Tạo tài khoản (Story 1) — snapshot hạn mức

1. Validate DTO (role hợp lệ) — như hiện tại.
2. **Snapshot quota**: với mỗi role trong `dto.roles()`, đọc
   `SystemConfigService.getLong("storage_quota.default.<role>")` (key theo
   `system-config.md`: `can_bo`/`lanh_dao`/`quan_tri`). Nếu tài khoản có
   **nhiều role**, lấy **MAX** các giá trị (Quyết định 1 mục 0 — đã chốt,
   không còn là giả định). Ghi cả giá trị snapshot lẫn danh sách role dùng
   để tính vào `audit_log.metadata` của `account.create`, để nếu sau này
   quy tắc đổi thì truy ra được tài khoản nào bị ảnh hưởng.
3. Gọi `kc.createUser(...)` (như hiện tại) → lấy `id`.
4. Trong cùng khối try hiện có (đặt mật khẩu + gán role): **thêm bước cuối**
   — insert `account(keycloak_user_id=id, status='active', status_reason=NULL,
   storage_quota_bytes=<snapshot bước 2>)` trong 1 DB transaction.
5. Nếu **bất kỳ** bước nào ở (4) lỗi (kể cả insert DB) → rollback theo
   pattern đã có: `kc.deleteUser(id)` best-effort + log lỗi nếu cleanup
   cũng thất bại → rethrow lỗi đã map. (Không để lại tài khoản Keycloak mồ
   côi không có row `account`.)
6. Ghi `audit_log` `account.create` (giữ nguyên, `metadata` thêm
   `storageQuotaBytes`).

### 4.2 Sửa thông tin (Story 2)

Không đổi so với `AccountsService.update()` hiện tại — chỉ Keycloak, không
đụng bảng `account`.

### 4.3 Khóa / Mở khóa / Vô hiệu hóa (Story 3 + 4) — saga 2 bước, DB là nguồn xác thực

Thiết kế: **DB (`account.status`) là nguồn xác thực chính** (vì lớp 2 filter
đọc từ đây) → ghi DB trước, đồng bộ Keycloak sau; lỗi Keycloak ở bước
"enabled flag" thì compensate (revert DB), lỗi ở bước "logout" thì chấp
nhận best-effort (không revert) vì lớp 2 đã đủ chặn truy cập ứng dụng.

| Bước | Hành động | Nếu lỗi |
|---|---|---|
| 1 | Validate: không tự thao tác chính mình; `status` hợp lệ; `reason` không rỗng nếu `status != active`; `status` mới ≠ status hiện tại (nếu không → `409`); **guard R3 cuối cùng** (xem ngay dưới bảng). | Trả lỗi ngay, không chạm DB/Keycloak. |
| 2 | `UPDATE account SET status=?, status_reason=?, updated_at=now() WHERE keycloak_user_id=?` (get-or-create nếu chưa có row, mục 2.2), commit. | Lỗi DB (kết nối...) → abort, trả `503`, không gọi Keycloak. |
| 3 | `DEL` key Redis `account:status:{id}` ngay lập tức (đồng bộ, không chờ TTL) — có hiệu lực trên **mọi** instance backend cùng lúc. | **Không** abort thao tác nếu Redis lỗi: DB đã commit ở bước 2 và DB mới là nguồn xác thực. Log WARN; key tự hết hạn sau TTL 45s, và instance nào mất Redis vốn đã query thẳng DB (mục 4.5 bước 4b) nên vẫn đọc đúng trạng thái mới. |
| 4 | Gọi Keycloak `updateUser(id, {enabled: status == 'active'})`. | **Compensate**: `UPDATE account` trả về status/reason cũ, evict cache lần nữa, trả `503` cho client (đồng bộ enabled-flag với Keycloak là bắt buộc để không lệch trạng thái nếu có hệ thống khác dùng chung realm Keycloak). |
| 5 | Nếu `status` mới là `locked`/`disabled`: gọi `kc.logoutUser(id)` (revoke refresh token + SSO session — lớp 1). | **Best-effort**: log lỗi (`log.error`), **không** compensate/rollback — lớp 2 (bước 2–3, đã commit) đã đủ để chặn "ngay" mọi request tiếp theo tới backend này, đúng lý do lớp 2 tồn tại. Đánh dấu `logoutCallFailed: true` trong audit metadata để R3 biết cần theo dõi. |
| 6 | Ghi `audit_log` (`account.lock`/`account.unlock`/`account.disable`), metadata gồm `reason`, `logoutCallFailed` (nếu có). | Không rollback nghiệp vụ nếu audit async lỗi (hành vi hiện có). |

- Nhãn `locked` vs `disabled` hiển thị khác nhau ở FE (Story 4 AC-1) — cả hai
  đều map `enabled=false` ở Keycloak (Quyết định 1 BA), chỉ khác `status` ở
  bảng nội bộ.
- **Guard "R3 active cuối cùng" (Quyết định 4 mục 0)** — chạy ở bước 1,
  trước khi chạm DB/Keycloak. Chỉ kích hoạt khi `status` mới ≠ `active`
  **và** tài khoản đích có vai trò `QUAN_TRI`:
  1. Lấy danh sách user trong role quản trị từ Keycloak bằng method **đã
     có sẵn** `kc.listRoleUsers("quan-tri", max)` — bảng `account`
     **không** lưu vai trò nên bắt buộc phải hỏi Keycloak.
     ⚠️ Tham số là tên role **Keycloak** (`quan-tri`, kebab-case theo
     `AssignableRoles.ALL`), **không** phải authority Spring
     (`QUAN_TRI`/`ROLE_QUAN_TRI` do `SecurityConfig` sinh ra từ
     `realm_access.roles`). Truyền nhầm chuỗi sẽ trả danh sách rỗng →
     guard tưởng "không còn R3 nào" và chặn nhầm mọi thao tác.
  2. Đối chiếu với `account.status`, đếm số R3 còn `active` (tài khoản chưa
     có row `account` tính là `active`, nhất quán mục 2.2).
  3. Nếu số đó `<= 1` (tức đích chính là người cuối cùng) → `409
     {code: "LAST_ADMIN"}`, không thực hiện gì thêm.

### 4.4 Chỉnh hạn mức riêng (Story 5)

1. Validate `storageQuotaBytes >= 0` (Bean Validation).
2. `getOrCreateAccountRow` nếu chưa có row (mục 2.2).
3. `UPDATE account SET storage_quota_bytes=?, updated_at=now()` — 1 transaction,
   không đụng Keycloak (thuần nội bộ).
4. Ghi `audit_log` `account.quota_update` (`oldValue`/`newValue`).
5. **Không hồi tố** — tệp đã lưu vượt hạn mức mới vẫn giữ nguyên (AC-2); chỉ
   `personal-storage-quota.md` (kiểm tra lúc upload) đọc giá trị mới ở lượt
   tải tiếp theo.

### 4.5 `AccountStatusFilter` — lớp 2 (chặn access token cũ còn hạn)

- Đăng ký `http.addFilterAfter(accountStatusFilter, BearerTokenAuthenticationFilter.class)`
  trong `SecurityConfig.filterChain()` — chạy **sau** khi JWT đã xác thực
  offline (chữ ký + hạn), **trước** khi vào `@PreAuthorize`/controller.
- Logic:
  1. Nếu `SecurityContext` không có `Authentication` (route public) → bỏ qua,
     `chain.doFilter()`.
  2. Lấy `sub` từ `Jwt` principal.
  3. Tra cache **Redis**, key `account:status:{sub}`, value = `status`
     (Quyết định 3 mục 0). Dùng chung instance Redis đã dựng ở `V7` cho
     `system-config`/allowlist — KHÔNG thêm hạ tầng mới. TTL 45s (trong
     khoảng 30–60s theo yêu cầu) chỉ là **chặn trên**; hiệu lực thật đến từ
     lệnh `DEL` chủ động ở mục 4.3 bước 3.
  4. Cache miss/hết hạn → `AccountRepository.findStatusById(sub)`; không có
     row → coi `active` (mục 2.2); ghi kết quả lại vào Redis kèm TTL.
  4b. **Redis không khả dụng** → xử lý như cache miss và query thẳng DB cho
     mọi request. Tuyệt đối **không** fail-open (cho qua khi không đọc
     được) và **không** rơi về cache in-process. Đánh đổi đã chấp nhận:
     đúng về bảo mật và đăng nhập vẫn chạy, nhưng tải DB tăng trong thời
     gian Redis chết (mỗi request đã xác thực = 1 point-lookup theo khóa
     chính, rẻ). Log WARN có rate-limit để không ngập log.
  5. Nếu `status != active` → trả `401`
     `{code: "ACCOUNT_LOCKED"|"ACCOUNT_DISABLED", message}` ngay, **không**
     gọi `chain.doFilter()` tiếp.
  6. Ngược lại → `chain.doFilter()`.
- Key bị xóa chủ động ở mục 4.3 bước 3 khi status đổi. Vì Redis dùng chung,
  lệnh xóa có hiệu lực **trên mọi instance backend cùng lúc** — không còn
  cửa sổ 30–60s như phương án cache in-process, đúng yêu cầu "chấm dứt
  phiên ngay" của Story 3 AC-3 / Story 4 AC-3 kể cả khi chạy nhiều instance.

## 5. Test plan

- **Unit test — `AccountsServiceTest`:**
  - `create()`: snapshot đúng MAX quota khi nhiều role; snapshot đúng quota
    khi 1 role; DB insert lỗi → `kc.deleteUser` được gọi (rollback), lỗi
    surface đúng.
  - `create()`: username/email trùng → `409`, không insert `account` row.
  - `setStatus()`: thiếu `reason` khi khóa/vô hiệu hóa → `400`; tự thao tác
    chính mình → `400`; đổi sang status hiện tại → `409`.
  - `setStatus()` guard R3 cuối cùng (Quyết định 4 mục 0): khóa R3 khi **còn**
    R3 `active` khác → thành công; khóa R3 khi đó là người `active` cuối
    cùng → `409 LAST_ADMIN`, DB/Keycloak không bị đụng. Thêm case: tài
    khoản đích có R3 nhưng `status` mới là `active` (mở khóa) → guard
    **không** kích hoạt.
  - `setStatus()`: Keycloak `updateUser` (enabled flag) lỗi → DB được
    compensate về giá trị cũ, cache evict lại, `503`.
  - `setStatus()`: `kc.logoutUser` lỗi nhưng `updateUser` thành công → thao
    tác vẫn trả thành công, DB đã đổi status, audit có
    `logoutCallFailed=true`.
  - `setQuota()`: giá trị âm → `400`; get-or-create khi chưa có row.
- **Unit test — `KeycloakAdminServiceTest`:** `logoutUser()` gọi đúng
  `POST /users/{id}/logout`, map lỗi 404/5xx như các method khác.
- **Unit test — `AccountStatusFilterTest`:**
  - Request không có `Authentication` → bỏ qua filter.
  - Status `active` (cache miss → DB) → cho qua, cache được set.
  - Status `locked`/`disabled` → `401` đúng `code`, không gọi
    `chain.doFilter`.
  - Cache Redis còn hạn → không query DB lần 2 (verify `AccountRepository`
    gọi 1 lần cho 2 request liên tiếp trong TTL).
  - Cache hết hạn (TTL ngắn trong test) → query DB lại.
  - **Redis ném lỗi** (mock throw) → filter **không** cho qua mù quáng:
    query thẳng DB và vẫn chặn đúng tài khoản `locked` (mục 4.5 bước 4b).
    Đây là test fail-closed trọng tâm của Quyết định 3.
  - `DEL` key ở mục 4.3 bước 3 → request kế tiếp đọc DB và thấy status mới
    **ngay**, không phải chờ hết TTL.
- **Integration test (backend):**
  - `POST /accounts` username trùng → `409`, không tạo row `account` mồ côi
    (query DB xác nhận không có row).
  - `POST /accounts` role R2 → `storage_quota_bytes` đúng giá trị mặc định
    R2 từ `system_config` (hoặc `SystemConfigDefaults` nếu chưa có bản ghi
    override) — Story 1 AC-3.
  - `PATCH /accounts/{id}/status {status: locked, reason: "..."}` → `204`,
    `GET /accounts` sau đó trả `status=locked`, `statusReason` đúng, hiển
    thị được cho mọi R3 (không chỉ người thực hiện) — Story 3 AC-1/AC-2/AC-5.
  - `PATCH /accounts/{id}/status` không có `reason` → `400`.
  - `PATCH /accounts/{id}/status {status: locked}` khi tài khoản **đã**
    `locked` → `409` (case biên yêu cầu).
  - Sau khi khóa: gọi lại API bằng **access token cũ đã cấp trước đó, còn
    hạn kỹ thuật** → `401 ACCOUNT_LOCKED` (xác nhận lớp 2 hoạt động, không
    phụ thuộc việc Keycloak revoke access token) — Story 3 AC-3 case biên
    trọng tâm.
  - Trong khoảng TTL cache Redis (< 45s) request thứ 2 vẫn `401` mà không
    query lại DB (kiểm bằng log/metric hoặc mock repository counting nếu
    dùng slice test); sau khi hết TTL vẫn `401` (vì DB vẫn `locked`, không
    phải test "hết hạn thì cho qua" — TTL chỉ ảnh hưởng thời điểm re-check,
    kết quả không đổi vì trạng thái không đổi).
  - **Đa instance** (2 context cùng trỏ 1 Redis + 1 DB): khóa tài khoản qua
    instance A → request tới instance B **ngay sau đó** đã `401`, không chờ
    TTL. Đây là điều phương án cache in-process cũ không làm được và là lý
    do chính của Quyết định 3.
  - `PATCH /accounts/{id}/status {status: active}` (mở khóa) → `204`, request
    kế tiếp bằng token mới hợp lệ trở lại — Story 3 AC-4.
  - `PATCH /accounts/{id}/quota {storageQuotaBytes: -1}` → `400`.
  - `PATCH /accounts/{id}/quota` role khác `QUAN_TRI` gọi → `403`.
  - Mọi thao tác ở Story 1–5 đều tạo đúng 1 `audit_log` entry tương ứng
    (`account.create`/`update`/`lock`/`unlock`/`disable`/`quota_update`).
  - Xác nhận **không tồn tại** route `DELETE /accounts/{id}` ở bất kỳ đâu
    (test 404/405 hoặc kiểm tra `RequestMappingHandlerMapping` không có
    mapping DELETE cho `/accounts/**`).

## 6. Task breakdown

- [x] Task 1: Migration `V8__init_account.sql` (bảng `account`, mục 2.1)
      + JPA `Account` entity + `AccountRepository`.
- [x] Task 2: `KeycloakAdminService.logoutUser(id)` + unit test.
- [x] Task 3: `AccountsService.getOrCreateAccountRow()` (get-or-create +
      backfill, mục 2.2) — phụ thuộc Task 1.
- [x] Task 4: Cập nhật `AccountsService.create()` — snapshot quota (đọc
      `SystemConfigService`, phụ thuộc `system-config.md` đã implement) +
      insert `account` row + rollback path (mục 4.1) — phụ thuộc Task 1, 3.
- [x] Task 5: `AccountsService.setStatus()` mới (thay hàm `enabled`
      boolean hiện tại) theo saga mục 4.3 — phụ thuộc Task 2, 3.
- [x] Task 6: `AccountsService.setQuota()` (Story 5, mục 4.4) — phụ thuộc
      Task 3.
- [x] Task 7: Cập nhật `AccountsController` — đổi route
      `PATCH /accounts/{id}/status` sang DTO mới (`SetAccountStatusDto`),
      thêm `PATCH /accounts/{id}/quota` (`SetAccountQuotaDto`), mở rộng
      `AccountRow`/`GET /accounts` — phụ thuộc Task 4–6.
- [x] Task 8: `AccountStatusFilter` (cache **Redis**, mục 4.5) + đăng ký
      trong `SecurityConfig` — phụ thuộc Task 1 (đọc `AccountRepository`)
      **và hạ tầng Redis** (Task 0 hạ tầng / `system-config.md` Task 2):
      Redis phải sẵn sàng trước, xem bảng thứ tự đầu file. Gồm cả nhánh
      Redis-lỗi → query thẳng DB (mục 4.5 bước 4b).
- [x] Task 9: Cập nhật FE `web/src/app/admin/accounts/page.tsx` — đổi call
      `PATCH /accounts/{id}/status` sang contract mới `{status, reason}`,
      thêm UI nhập `reason`, hiển thị `status`/`statusReason`, thêm form
      chỉnh `storageQuotaBytes` — phụ thuộc Task 7.
- [x] Task 10: Unit test + integration test theo mục 5 — phụ thuộc Task
      1–9 tương ứng. **Lưu ý:** `AccountsControllerIT`/`SystemConfigControllerIT`
      (Testcontainers) không chạy trong `mvn test` mặc định (không có
      failsafe plugin trong `pom.xml` — gap có từ trước, không phải do spec
      này) — chạy tường minh bằng `mvn test -Dtest=AccountsControllerIT`.
      Case "đa instance" (2 context cùng Redis/DB) trong mục 5 chưa có test
      tự động, xem mục "việc còn lại" khi bàn giao.
- [x] Task 11: Cập nhật trạng thái task trong file spec này (đánh dấu
      done) sau khi implement — không viết lại toàn bộ spec.
- [ ] Task 12 (**vận hành, chạy SAU khi deploy `V8`**, Quyết định 2 mục 0):
      R3 rà soát danh sách tài khoản Keycloak đang `enabled=false`, phân
      loại `locked` vs `disabled` và nhập `reason` qua UI mới. Cho tới khi
      làm xong, các tài khoản này hiển thị `active` dù Keycloak vẫn chặn
      login — cửa sổ lệch **đã được chấp nhận** (xem Rủi ro mục 7). Ghi vào
      runbook/checklist phát hành, không phải task code.

## 7. Rủi ro kỹ thuật

- ~~Multi-role quota = MAX là giả định chưa chốt~~ → **đã chốt** (Quyết
  định 1 mục 0). Vẫn giữ biện pháp truy vết: ghi giá trị snapshot + danh
  sách role dùng để tính vào `audit_log.metadata`, để nếu quy tắc đổi về
  sau thì xác định được tài khoản nào bị ảnh hưởng.
  **Đa vai trò là tình huống có thật, không giả định:** realm seed
  (`keycloak/realm-export.json`) đã có sẵn tài khoản
  `canbo_lanhdao1` mang đồng thời `can-bo` + `lanh-dao` — quy tắc MAX sẽ
  được kích hoạt ngay ở dữ liệu mẫu, cho ra 50GB. Test tích hợp nên dùng
  đúng tài khoản này thay vì dựng dữ liệu mới.
  **Điểm cần biết:** với số hiện tại R3 (20GB) thấp hơn R2 (50GB), nên tài
  khoản R2+R3 nhận 50GB — cao hơn R3 đơn lẻ. Đây là hệ quả trực tiếp của
  MAX và đã được chấp nhận, nhưng nếu về sau R3 được nâng lên thành vai trò
  có quota cao nhất thì nên rà lại xem MAX còn hợp lý không.
- **Cửa sổ hiển thị lệch sau khi deploy `V8` — rủi ro đã chấp nhận có chủ
  đích** (Quyết định 2 mục 0). Tài khoản Keycloak `enabled=false` tạo trước
  migration được get-or-create thành `status='active'`, nên `GET /accounts`
  hiển thị `active` dù Keycloak vẫn chặn login.
  **Không lệch quyền truy cập thực**: Keycloak vẫn chặn ở tầng đăng nhập,
  và lớp 2 chỉ chặn thêm chứ không nới ra — sai lệch thuần túy ở thông tin
  R3 nhìn thấy. Đóng lại bằng Task 12.
  Đã cân nhắc backfill tự động theo cờ `enabled` và **chủ động không chọn**:
  vì Keycloak chỉ có `enabled=false` chung, gán máy móc thành `disabled` sẽ
  tạo dữ liệu sai nghiệp vụ (tài khoản chỉ bị khóa tạm thời bị ghi thành
  nghỉ việc) — khó phát hiện hơn là để R3 tự phân loại.
- ~~Cache per-instance khiến tài khoản đã khóa vẫn qua được instance khác
  tới 60s~~ → **đã giải quyết** (Quyết định 3 mục 0): chuyển sang Redis
  dùng chung. Lý do cũ ("không thêm dependency Redis theo NFR-11") không
  còn đúng — Redis đã được chốt đưa vào từ `V7` cho `system-config` và
  allowlist của `data-ingestion`, nên đây là tái dùng chứ không phải thêm
  hạ tầng.
  **Rủi ro mới phát sinh:** đường xác thực nay chạm Redis ở mỗi request.
  Đã giảm thiểu bằng quy tắc "Redis lỗi = cache miss, query thẳng DB" (mục
  4.5 bước 4b) nên Redis chết KHÔNG làm sập đăng nhập và KHÔNG fail-open về
  bảo mật. Đánh đổi còn lại là tải DB tăng đột biến khi Redis chết kéo dài
  (mỗi request đã xác thực = 1 point-lookup theo khóa chính). Cần cảnh báo
  giám sát khi tỷ lệ lỗi Redis tăng, thay vì để nó âm thầm.
- **Guard "R3 cuối cùng" phải hỏi Keycloak** (Quyết định 4 mục 0): bảng
  `account` không lưu vai trò nên mỗi lần khóa/vô hiệu hóa một R3 phải gọi
  `kc.listRoleUsers("quan-tri", max)` — method này **đã tồn tại**, không
  cần thêm mới. Chấp nhận được vì đây là thao tác quản trị tần suất rất
  thấp. Lưu ý `max`: phải đủ lớn để không cắt cụt danh sách R3, nếu không
  guard sẽ đếm thiếu và chặn nhầm.
  **Rủi ro còn lại:** hai R3 khóa lẫn nhau **đồng thời** vẫn có thể lọt qua
  guard, vì bước đếm và bước ghi DB không nằm trong cùng một khóa. Xác suất
  rất thấp và hậu quả vẫn khắc phục được bằng sửa DB thủ công; muốn chặn
  tuyệt đối phải dùng advisory lock trên phạm vi "tập R3" — **không làm ở
  MVP**, ghi nhận để cân nhắc nếu vận hành thực tế gặp.
- **Keycloak logout (lớp 1) là best-effort, không rollback khi lỗi** — nếu
  Keycloak Admin API lỗi ở đúng bước logout (nhưng bước enabled-flag đã
  thành công), refresh token/SSO session của Keycloak có thể vẫn hợp lệ ở
  tầng IdP dù backend đã chặn hoàn toàn qua lớp 2. Chấp nhận được vì AC chỉ
  yêu cầu chặn truy cập ứng dụng "ngay" — nhưng cần giám sát log
  `logoutCallFailed` để không tích tụ session Keycloak rác.
- **Breaking API change** `PATCH /accounts/{id}/status`: FE hiện gọi
  `{enabled: boolean}` (`web/src/app/admin/accounts/page.tsx:187-190`) —
  phải deploy đồng bộ BE+FE (Task 7+9), tránh cửa sổ FE cũ gọi API mới gây
  lỗi 400 hàng loạt.
- ~~Xung đột số hiệu migration Flyway~~ → **đã giải quyết**: thứ tự
  triển khai và số hiệu `V7`–`V14` đã chốt cho cả 7 spec (xem bảng đầu
  file). Spec này giữ `V8__init_account.sql`, merge sau `system-config`
  (`V7`). Rủi ro còn lại: nếu merge lệch thứ tự đã chốt, Flyway sẽ fail vì
  `out-of-order` không được bật — phải giữ đúng thứ tự merge.
