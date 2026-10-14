# Tech Spec: rbac-permission-editing

> File: `docs/03-dev/specs/rbac-permission-editing.md` — Owner: DEV
> Nguồn story: `docs/02-ba/stories/rbac-permission-editing.md`
> Status: Approved

> ## ⚠️ Thứ tự migration — đọc trước khi implement
>
> Migration hiện có trong repo: `V1`–`V13`. `auth-session.md` (Approved,
> **chưa implement**) đã đặt trước số `V14__init_keycloak_event_cursor.sql`.
> Spec này dùng **`V15`**. Nếu `auth-session` chưa merge trước khi spec này
> merge, DEV cần xác nhận lại thứ tự thực tế (Flyway không bật
> `out-of-order` — số hiệu bắt buộc khớp đúng thứ tự merge, không thể chèn
> số nhỏ hơn về sau) và đổi số nếu cần trước khi merge.

## 1. Tóm tắt kỹ thuật

- **Data (Story 1, 2):** tái sử dụng nguyên trạng `role`/`permission`/
  `role_permission` từ `V12__init_groups.sql` — không đổi cấu trúc bảng,
  chỉ thêm migration `V15__role_permission_audit_columns.sql` bổ sung cột
  `updated_at`, `updated_by_sub` vào `role_permission`. Bỏ comment "seed-only,
  KHÔNG có API ghi" trong migration cũ (comment lịch sử, không cần sửa file
  đã áp dụng — chỉ ghi chú lại ở đây rằng ràng buộc đó hết hiệu lực từ spec
  này).
- **Backend — module mới `com.aismartstore.backend.rbac`** (theo đúng
  pattern `accounts/`, `groups/` hiện có): `RbacController`, `RbacService`,
  `RolePermissionRepository`, `dto/UpdateRolePermissionsDto.java`.
- **Enforcement (Quyết định 2, SA):** `SecurityConfig.realmRoleAuthorities()`
  tra thêm `role_permission` (qua cache Caffeine, TTL 60 giây — trả lời câu
  hỏi mở của SA/BA) để sinh thêm authority `PERM_<code>`; đổi toàn bộ
  `@PreAuthorize("hasRole(...))`/`hasAnyRole(...)` hiện có (~17 vị trí, đã
  liệt kê trong `RolePermissionSeedTest`) sang
  `@PreAuthorize("hasAuthority('PERM_<code>')")` dùng đúng permission code
  tương ứng theo bảng ánh xạ đã có trong `V12__init_groups.sql`.
- **Guard chống lockout (Quyết định 3, SA):** `RbacService.updateRolePermissions`
  chặn cứng nếu thay đổi khiến `QUAN_TRI` không còn đồng thời
  `account.manage` và `system_config.manage`.
- **`RolePermissionSeedTest` đổi vai trò:** từ "invariant sống" (assert DB
  luôn khớp `@PreAuthorize` runtime) sang "chỉ kiểm tra đúng seed lúc
  `V12`/`V15` chạy" (test dữ liệu tại một thời điểm — sau spec này DB được
  phép lệch có chủ đích do R3 chỉnh tay).

## 2. Data model

```sql
-- V15__role_permission_audit_columns.sql
ALTER TABLE role_permission
    ADD COLUMN updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    ADD COLUMN updated_by_sub VARCHAR(255);

-- Không đổi PK (role_code, permission_code); updated_at/updated_by_sub áp
-- dụng ở mức dòng, ghi lại bởi RbacService khi thêm/xoá một mapping.
-- Seed hiện có ở V12 giữ nguyên (updated_at = thời điểm chạy V15,
-- updated_by_sub = NULL cho các dòng seed gốc, không phải R3 chỉnh).
```

```java
// com.aismartstore.backend.rbac.RolePermissionRepository
// Không cần entity JPA mới cho role/permission (đã có ở rbac-group-scope
// nếu DEV từng thêm @Immutable entity; nếu chưa có thì dùng JdbcTemplate/
// native query đơn giản vì bảng rất nhỏ — 3 role x 15 permission).
public interface RolePermissionRepository {
    List<String> findPermissionCodesByRoleCode(String roleCode);
    void deleteByRoleCodeAndPermissionCode(String roleCode, String permissionCode);
    void insert(String roleCode, String permissionCode, String updatedBySub);
    long countRolePermissions(); // dùng cho sanity check test
}
```

```java
// com.aismartstore.backend.rbac.dto.UpdateRolePermissionsDto
public record UpdateRolePermissionsDto(List<String> permissionCodes) {}
// full-replace theo đúng convention UpdateAccountDto.roles() đã có trong
// AccountsController — không có endpoint "add 1 permission"/"remove 1
// permission" riêng, tránh 2 API làm cùng 1 việc.
```

## 3. API contract

Toàn bộ endpoint dưới `@PreAuthorize("hasRole('QUAN_TRI')")` — dùng
`hasRole` (không phải `hasAuthority('PERM_...')`) cho chính module RBAC vì
đây là bề mặt quản trị RBAC, phải luôn mở cho `QUAN_TRI` bất kể cấu hình
`role_permission` hiện tại (tránh vòng lặp: nếu tự khoá bằng permission thì
không ai sửa lại được — lý do Quyết định 3).

| Method | Endpoint | Request | Response |
|---|---|---|---|
| GET | `/admin/rbac/roles` | – | `200 [{code, displayName, permissionCodes[]}]` — Story 1 |
| GET | `/admin/rbac/permissions` | – | `200 [{code, description}]` — Story 1 |
| PUT | `/admin/rbac/roles/{roleCode}/permissions` | `{ permissionCodes: string[] }` | `200 {roleCode, permissionCodes[], affectedAccountCount}` — Story 2 |
| GET | `/admin/rbac/roles/{roleCode}/preview-impact?permissionCodes=...` | query param | `200 {affectedAccountCount, permissionsAdded[], permissionsRemoved[]}` — Story 2 AC-5, gọi trước khi bấm lưu để hiển thị cảnh báo |

- `roleCode` ∈ `{CAN_BO, LANH_DAO, QUAN_TRI}` — 400 nếu ngoài danh mục (không
  tạo role mới, Story ngoài phạm vi).
- `permissionCodes` phần tử phải nằm trong bảng `permission` hiện có — 400
  nếu có code lạ (đóng danh mục, Story 2 AC-1).
- `PUT .../permissions` trả `409` (dùng `ConflictException` sẵn có trong
  `common/`) nếu vi phạm guard lockout (Quyết định 3, Story 3) — body theo
  đúng pattern `lastAdminConflict()` trong `AccountsService` (có `code`,
  `message`).

## 4. Luồng xử lý chính

### 4.1 Xem ma trận vai trò × quyền (Story 1)

1. `GET /admin/rbac/roles` → `RbacService.listRoles()` join `role` +
   `role_permission` → trả về từng role kèm danh sách permission code đang
   bật.
2. `GET /admin/rbac/permissions` → trả nguyên bảng `permission` (15 dòng,
   không lọc theo role).

### 4.2 Xem trước tác động rồi lưu (Story 2)

1. R3 tick/untick permission trên UI (dạng bảng checkbox: hàng = permission,
   cột = 3 role) → khi mở 1 role để sửa, FE gọi
   `GET /admin/rbac/roles/{roleCode}/preview-impact?permissionCodes=...`
   với danh sách permission **mong muốn** (không phải diff) →
   `RbacService.previewImpact` so với `role_permission` hiện tại, tính
   `permissionsAdded`/`permissionsRemoved`, và gọi
   `kc.listRoleUsers(keycloakRoleNameOf(roleCode), ADMIN_ROLE_LOOKUP_MAX)`
   (tái dùng `KeycloakAdminService` đã có, cùng cách `AccountsService` đang
   đếm) để trả `affectedAccountCount`.
   - Ánh xạ `roleCode` (UPPER_SNAKE, DB) ↔ Keycloak role name (kebab-case):
     tái dùng đúng `switch` đã có kiểu `defaultQuotaKey` trong
     `AccountsService` (`CAN_BO`→`can-bo`, `LANH_DAO`→`lanh-dao`,
     `QUAN_TRI`→`quan-tri`) — viết 1 hàm dùng chung, không lặp lại switch
     này lần thứ 3 trong codebase (cân nhắc rút ra `Role` hoặc 1 mapper nhỏ
     dùng chung giữa `accounts` và `rbac`).
2. R3 xác nhận lưu → `PUT /admin/rbac/roles/{roleCode}/permissions` với
   `permissionCodes` mong muốn.
3. `RbacService.updateRolePermissions`:
   a. Validate `roleCode` ∈ 3 role cố định, mọi `permissionCodes` ∈ bảng
      `permission` (400 nếu sai — Story 2 AC-1).
   b. Diff với `role_permission` hiện tại → `added`/`removed`.
   c. **Guard lockout (Quyết định 3):** nếu `roleCode == "QUAN_TRI"` và tập
      quyền sau khi áp không còn cả `account.manage` lẫn
      `system_config.manage` → ném `ConflictException` (409), không ghi gì.
   d. Trong 1 transaction: `DELETE` các dòng bị bỏ, `INSERT` các dòng mới
      (kèm `updated_at = now()`, `updated_by_sub = currentUser.sub()`).
   e. Evict cache permission-per-role (mục 4.3) cho đúng `roleCode` vừa sửa.
   f. `auditService.recordAsync(...)` với `action =
      "rbac.role_permission_update"`, `resourceType = "role"`,
      `resourceId = roleCode`, `metadata = {permissionsAdded, permissionsRemoved}`
      — cùng shape với audit entry trong `AccountsService.update` (Story 2
      AC-3).
   g. Trả `{roleCode, permissionCodes, affectedAccountCount}` (đếm lại 1 lần
      cuối cho response, không bắt buộc phải khớp con số ở bước preview nếu
      có race — chấp nhận được ở quy mô hệ thống này).

### 4.3 Cache permission-per-role + enforcement (Quyết định 2, SA)

1. Thêm bean `Cache<String, Set<String>> rolePermissionCache` (Caffeine,
   `expireAfterWrite(60, SECONDS)`), key = role code UPPER_SNAKE, value =
   tập permission code.
2. `SecurityConfig.realmRoleAuthorities(Jwt jwt)`:
   - Giữ nguyên logic sinh `ROLE_<role>` như hiện tại.
   - Với mỗi Keycloak role trong JWT, map sang role code UPPER_SNAKE (dùng
     hàm mapper dùng chung ở 4.2), tra `rolePermissionCache` (load từ
     `role_permission` nếu cache miss), sinh thêm authority
     `PERM_<permission_code>` cho từng permission trong tập.
   - **Lưu ý:** hàm này chạy đồng bộ trong luồng xác thực JWT — vì đã có
     cache TTL 60s, worst case chỉ 1 query DB nhỏ mỗi 60 giây cho mỗi role
     (không phải mỗi request), chấp nhận được cho NFR-01.
3. Đổi từng `@PreAuthorize("hasRole('X')")` /
   `hasAnyRole('X','Y')` hiện có (danh sách đầy đủ trong
   `RolePermissionSeedTest`) sang `@PreAuthorize("hasAuthority('PERM_<code>')")`
   / `hasAnyAuthority('PERM_<code1>','PERM_<code2>')` đúng permission code
   ánh xạ sẵn trong seed `V12`. Module `rbac/` (mục 3) tiếp tục dùng
   `hasRole('QUAN_TRI')` thẳng (không qua permission) — lý do đã nêu ở mục 3.
4. `RbacService.updateRolePermissions` evict đúng key `roleCode` khỏi
   `rolePermissionCache` sau khi ghi DB (bước 4.2.e) — không chờ hết TTL.

### 4.4 Guard lockout — chi tiết

```java
private void guardLockout(String roleCode, Set<String> newPermissionCodes) {
    if (!"QUAN_TRI".equals(roleCode)) return;
    if (newPermissionCodes.contains("account.manage")
            && newPermissionCodes.contains("system_config.manage")) return;
    throw lockoutConflict(); // 409, code="RBAC_LOCKOUT", cùng pattern lastAdminConflict()
}
```

## 5. Test plan

- **Unit test:**
  - `RbacService.updateRolePermissions`: diff đúng added/removed; validate
    permission code lạ → 400; validate role code ngoài 3 role → 400 —
    Story 2 AC-1.
  - `guardLockout`: gỡ `account.manage` hoặc `system_config.manage` khỏi
    `QUAN_TRI` → 409; gỡ quyền khác của `QUAN_TRI` (vd. `folder.manage`) →
    cho phép — Story 3 AC-1/AC-2.
  - `SecurityConfig.realmRoleAuthorities`: JWT có role `can-bo` → sinh đúng
    `ROLE_CAN_BO` + đúng tập `PERM_*` khớp `role_permission` hiện tại (mock
    repository) — Quyết định 2.
- **Integration test:**
  - R1 (`CAN_BO`) gọi `GET /admin/rbac/roles` → 403 (chỉ `QUAN_TRI`) —
    Story 1 AC-4.
  - R3 xem ma trận → đúng 3 role, 15 permission, trạng thái khớp seed ban
    đầu — Story 1 AC-1–AC-3.
  - R3 gỡ 1 quyền của `CAN_BO` (không phải quyền bắt buộc) → lưu thành công
    → tài khoản `CAN_BO` gọi endpoint tương ứng ngay sau đó (không re-login)
    trong vòng TTL cache → nhận `403` — Story 2 AC-4, xác nhận hiệu lực
    gần-tức-thời qua evict cache chủ động (không cần chờ hết TTL nếu test
    gọi lại ngay sau khi evict).
  - R3 thử gỡ `account.manage` khỏi `QUAN_TRI` → 409, dữ liệu
    `role_permission` không đổi — Story 3 AC-1/AC-3 (gọi thẳng API, không qua
    UI, để xác nhận guard ở tầng service).
  - `PUT .../permissions` với `permissionCodes` chứa code không tồn tại
    (vd. `"document.delete_all"`) → 400 — Story 2 AC-1.
  - `preview-impact` trả đúng `affectedAccountCount` khớp số tài khoản đang
    giữ vai trò đó trong Keycloak (dùng test container/mock Keycloak Admin
    API đã có sẵn cho `AccountsService` test) — Story 2 AC-5.
  - Thay đổi `role_permission` được ghi `audit_log` với action
    `rbac.role_permission_update`, metadata đúng added/removed — Story 2 AC-3.
- **Case biên/lỗi cần cover:**
  - R3 lưu permission set **giống hệt** hiện tại (không có gì thay đổi) →
    vẫn trả `200`, nhưng **không** ghi audit log mới (tránh audit log rác) —
    và **không** evict cache (tối ưu, không bắt buộc).
  - Hai R3 sửa đồng thời cùng 1 role (race) → chấp nhận "last write wins"
    (full-replace theo `permissionCodes` gửi lên) — không cần optimistic
    locking cho quy mô hệ thống này; ghi rõ trong code comment để tránh hiểu
    nhầm sau này.
  - Cache miss đồng thời từ nhiều thread cho cùng 1 role (cold start) →
    Caffeine tự xử lý (load 1 lần), không cần code thêm.

## 6. Task breakdown

- [x] Task 1: Migration `V15__role_permission_audit_columns.sql` (2 cột
      `updated_at`/`updated_by_sub` trên `role_permission`).
- [x] Task 2: Package mới `com.aismartstore.backend.rbac` —
      `RolePermissionRepository`, `RbacService` (list, updateRolePermissions,
      previewImpact, guardLockout), `dto/UpdateRolePermissionsDto.java`.
- [x] Task 3: `RbacController` — 4 endpoint ở mục 3, `@PreAuthorize("hasRole('QUAN_TRI')")`.
- [x] Task 4: Hàm mapper dùng chung role code (UPPER_SNAKE) ↔ Keycloak role
      name (kebab-case) — rút từ switch đã lặp lại 2 lần trong
      `AccountsService`, dùng chung cho `rbac/` (tránh lặp lần thứ 3).
- [x] Task 5: Bean cache Caffeine `rolePermissionCache` (TTL 60s) +
      `SecurityConfig.realmRoleAuthorities` sinh thêm authority `PERM_<code>`.
- [x] Task 6: Rà soát và đổi toàn bộ `@PreAuthorize("hasRole(...))`/
      `hasAnyRole(...)` hiện có (danh sách theo `RolePermissionSeedTest`)
      sang `hasAuthority('PERM_...')`/`hasAnyAuthority(...)`, **trừ** module
      `rbac/` (giữ `hasRole('QUAN_TRI')`, xem mục 3).
- [x] Task 7: Evict cache chủ động trong `RbacService.updateRolePermissions`
      sau khi ghi DB thành công.
- [x] Task 8: Cập nhật `RolePermissionSeedTest` — đổi từ assert DB luôn khớp
      code (invariant sống) sang assert đúng seed **tại thời điểm migration
      V12/V15** (test dữ liệu tĩnh một lần, không còn là invariant runtime).
- [x] Task 9: Unit test + integration test theo mục 5.
- [x] Task 10: Frontend — màn hình bảng ma trận vai trò × quyền (checkbox),
      hiển thị cảnh báo `affectedAccountCount` trước khi lưu (Story 2 AC-5),
      hiển thị lỗi 409 lockout rõ ràng (Story 3 AC-2).

## 7. Rủi ro kỹ thuật

- **Refactor ~17 vị trí `@PreAuthorize` cùng lúc** (Task 6): rủi ro sót/sai
  vị trí — giảm thiểu bằng cách dùng đúng danh sách đã đối chiếu sẵn trong
  `RolePermissionSeedTest` làm checklist, review từng file một, chạy lại
  toàn bộ integration test của `rbac-group-scope.md` (Story 1) sau khi đổi
  để đảm bảo hành vi phân quyền không đổi ngoài ý muốn.
- **`SecurityConfig.realmRoleAuthorities` chạy trong luồng xác thực** —
  nếu cache miss đồng loạt (vd. sau khi restart backend) có thể tạo spike
  query DB ngắn; chấp nhận được vì bảng rất nhỏ (3×15 dòng), nhưng nên đo
  thử dưới tải trước khi go-live.
- **Guard lockout chỉ chặn đúng 2 quyền cố định** (`account.manage`,
  `system_config.manage`) — nếu sau này thêm permission mới quan trọng
  tương đương (ngoài phạm vi spec này) mà quên thêm vào guard, có thể lọt
  một kiểu lockout khác; cần review guard này mỗi khi danh mục permission
  thay đổi (dù hiện đang đóng).
- **`RolePermissionSeedTest` đổi ý nghĩa** (Task 8) là thay đổi quan trọng
  về mặt "hợp đồng" của test này — cần comment rõ trong code lý do đổi, để
  người đọc sau không hiểu nhầm test cũ đã bị xoá vì không cần thiết.
