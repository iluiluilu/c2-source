# Tech Spec: group-management-sharing-01-group-lifecycle

> File: `docs/03-dev/specs/group-management-sharing-01-group-lifecycle.md` — Owner: DEV
> Nguồn story: `docs/02-ba/stories/group-management-sharing-01-group-lifecycle.md`
> Status: Approved

> ## ⚠️ Trạng thái so với code hiện tại — đọc trước khi implement
>
> Package `com.aismartstore.backend.groups` **đã tồn tại và chạy được**
> (migration `V12__init_groups.sql`, entity `Group`/`GroupMember`,
> `GroupsController`/`GroupsService`) nhưng chỉ ở mức tối thiểu đủ cho
> `BusinessDataScope` hoạt động (`rbac-group-scope`, Approved). Spec này
> **không viết lại** các phần đã đúng — chỉ bổ sung method/endpoint/migration
> còn thiếu, đánh dấu rõ **[MỚI]** vs **[SỬA]** vs **[ĐÃ CÓ, không đổi]** ở
> từng mục. Migration tiếp theo trong repo là `V23` (đã có `V1`–`V22`, xem
> `backend/src/main/resources/db/migration/`); Flyway không bật
> `out-of-order` nên số hiệu phải khớp đúng thứ tự merge.

## 1. Tóm tắt kỹ thuật

- **[SỬA]** `Group`: thêm cột/field `deleted_at` (nullable). Mọi truy vấn
  nhóm hiện có (`GroupRepository.existsById`, `findAllById`,
  `listForCurrentUser`...) phải lọc `deleted_at IS NULL` — tập trung logic
  này trong `GroupRepository`/`GroupsService`, **không** truy vấn `group`
  trực tiếp ở nơi khác (đúng khuyến nghị rủi ro "Cao" của SA).
- **[MỚI]** 4 endpoint trong `GroupsController`: `PATCH /groups/{id}`
  (Story 1), `PATCH /groups/{id}/members/{userSub}` (Story 2, đổi vai trò),
  `POST /groups/{id}/leave` (Story 3), `DELETE /groups/{id}` (Story 4, soft
  delete).
- **[MỚI]** hàm dùng chung `requireNotLastOwner(groupId, userSub)` trong
  `GroupsService`, gọi từ Story 2 (hạ vai trò), Story 3 (rời nhóm), và
  **[SỬA]** `removeMember` hiện có (gỡ owner cuối hiện tại **không** bị chặn
  — đây là hành vi sai cần vá, không phải tính năng mới).
- **[MỚI]** package/module danh bạ người dùng — controller mới
  `UserDirectoryController` (`GET /users/directory`), đọc từ bảng account
  hiện có (`account-lifecycle`), **không** tạo bảng mới.
- **[MỚI]** kiểm tra giới hạn cứng trong `GroupsService.create`/`addMember`/
  `changeMemberRole`: 200 thành viên/nhóm, 200 nhóm/user, 1.000 nhóm hệ
  thống — hằng số `static final int` trong `GroupsService`, không đọc từ
  `system-config` (đã chốt).
- **[MỚI]** frontend `web/src/app/groups/**` (danh sách, tạo, chi tiết) +
  component tìm người dùng dùng lại ở form thêm thành viên.

## 2. Data model

```sql
-- V23__group_lifecycle.sql
ALTER TABLE "group" ADD COLUMN deleted_at TIMESTAMPTZ;

-- Unique tên nhóm, không phân biệt hoa/thường, chỉ áp cho nhóm chưa xoá
-- (Quyết định 5 SA) - partial unique index.
CREATE UNIQUE INDEX UQ_group_name_active ON "group" (lower(name)) WHERE deleted_at IS NULL;

-- Story 6 AC-3: index để liệt kê nhóm active nhanh (đã có PK trên id, thêm
-- index lọc theo deleted_at cho các truy vấn GET /groups quy mô lớn).
CREATE INDEX IDX_group_deleted_at ON "group" (deleted_at);
```

```java
// com.aismartstore.backend.groups.Group — [SỬA] thêm field
@Column(name = "deleted_at")
private OffsetDateTime deletedAt;
// getter/setter deletedAt; helper isDeleted() { return deletedAt != null; }
```

- **Không** cần bảng mới cho danh bạ người dùng (Story 5) — đọc trực tiếp
  bảng `account` đã có ở `account-lifecycle` (`AccountRepository`, cột
  `active`, `display_name`, `username`, `sub`... theo entity `Account`
  hiện có — DEV kiểm tra tên cột chính xác khi code, không đoán).

## 3. API contract

### 3.1 `GroupsController` — bổ sung

| Method | Endpoint | Request | Response | Trạng thái |
|---|---|---|---|---|
| PATCH | `/groups/{id}` | `{ name?, description? }` | `200 Group` — chỉ `owner`, tên trùng (không phân biệt hoa/thường, trong nhóm chưa xoá) → `409` | **[MỚI]** |
| PATCH | `/groups/{id}/members/{userSub}` | `{ roleInGroup }` | `204` — chỉ `owner`; hạ owner cuối → `409` | **[MỚI]** |
| POST | `/groups/{id}/leave` | – | `204` — owner cuối rời → `409` | **[MỚI]** |
| DELETE | `/groups/{id}` | – | `204` — chỉ `owner` (bất kỳ owner nào), soft delete | **[MỚI]** |
| POST | `/groups/{id}/members` | `{ userSub, roleInGroup }` | `204` | **[SỬA]** thêm check giới hạn 200 thành viên/nhóm, 200 nhóm/user → `422` nếu vượt |
| DELETE | `/groups/{id}/members/{userSub}` | – | `204` | **[SỬA]** gỡ owner cuối → `409` (trước đây không chặn) |
| POST | `/groups` | `{ name, description? }` | `201 Group` | **[SỬA]** thêm check ≤ 1.000 nhóm hệ thống → `422`; check unique tên (trước đây không có, dựa vào DB constraint ném lỗi 500 chưa xử lý) |
| GET | `/groups` | – | – | **[ĐÃ CÓ, không đổi]** — chỉ thêm điều kiện `deleted_at IS NULL` ngầm qua repository |
| GET | `/groups/{id}/members` | – | `200 GroupMemberView[]` — `{userSub, username, displayName, roleInGroup, joinedAt}` | **[SỬA]** (2026-09-19) trước đây trả thẳng entity `GroupMember` (chỉ có `userSub`, không có tên) khiến màn hình chi tiết nhóm hiển thị UUID thay vì tên người dùng — nay `GroupsService#listMembers` join thêm `username`/`displayName` qua `KeycloakAdminService.getUser` (cùng cách `UserDirectoryService` làm ở Story 5). Lookup lỗi (Keycloak down / tài khoản đã xoá) → `username`/`displayName` = `null`, không làm fail cả request; FE fallback về `userSub`. |

Toàn bộ endpoint mới giữ nguyên `@PreAuthorize("hasAuthority('PERM_group.manage')")`
đã dùng ở `GroupsController` — **không** thêm permission mới, tái dùng permission
`group.manage` đã seed sẵn.

### 3.2 `UserDirectoryController` — mới

| Method | Endpoint | Request | Response |
|---|---|---|---|
| GET | `/users/directory?q={keyword}` | `q` ≥ 2 ký tự | `200 {sub, username, displayName, role}[]` (≤ 20 phần tử) |

`@PreAuthorize("hasAnyRole('CAN_BO','LANH_DAO')")` — **không** dùng permission
`account.read` hiện có (permission đó gắn với `QUAN_TRI`, sẽ vi phạm NFR-04
nếu tái dùng); tạo permission mới `user.directory_search`, seed cho `CAN_BO`/
`LANH_DAO` trong cùng migration `V23`.

```sql
-- tiếp V23__group_lifecycle.sql
INSERT INTO permission (code, description) VALUES
    ('user.directory_search', 'Tìm kiếm tài khoản tối giản để chọn thành viên nhóm');
INSERT INTO role_permission (role_code, permission_code) VALUES
    ('CAN_BO', 'user.directory_search'),
    ('LANH_DAO', 'user.directory_search');
```

## 4. Luồng xử lý chính

### 4.1 Cập nhật nhóm (Story 1)

1. `PATCH /groups/{id}`: load `Group` (404 nếu `deleted_at IS NOT NULL` hoặc
   không tồn tại) → `requireOwner` (tái dùng helper `requireOwner` đã có
   trong `GroupsService`) → set `name`/`description` nếu có mặt trong
   request → `updated_at = now()` → save. Bắt
   `DataIntegrityViolationException` từ unique index mới → `ConflictException`
   (pattern giống `addMember`/`shareDocument` hiện có, tái dùng `SqlErrors`).
2. Audit `group.update` với `oldName`/`newName` trong metadata nếu tên đổi.

### 4.2 Đổi vai trò thành viên (Story 2)

1. `PATCH /groups/{id}/members/{userSub}`: `requireOwner` → nếu
   `roleInGroup` mới là `member` **và** thành viên hiện là owner duy nhất
   → gọi `requireNotLastOwner` → `409`.
2. Update `role_in_group`, audit `group.change_member_role`.

### 4.3 Bất biến "≥ 1 owner" dùng chung (Story 2, 3, sửa Story 4-cũ)

```java
// GroupsService — hàm mới, gọi từ changeMemberRole/leaveGroup/removeMember
private void requireNotLastOwner(UUID groupId, String userSub) {
    GroupMember member = groupMemberRepository.findByGroupIdAndUserSub(groupId, userSub)
            .orElseThrow(() -> new NotFoundException("User is not a member of this group"));
    if (!GroupRole.OWNER.equals(member.getRoleInGroup())) {
        return; // không phải owner, không cần kiểm tra
    }
    long ownerCount = groupMemberRepository.countByGroupIdAndRoleInGroup(groupId, GroupRole.OWNER);
    if (ownerCount <= 1) {
        throw new ConflictException("Cannot remove the last owner of the group");
    }
}
```

- `removeMember` hiện có: thêm 1 dòng gọi `requireNotLastOwner(groupId, userSub)`
  trước khi `deleteByGroupIdAndUserSub` — đây là **sửa bug/hành vi thiếu**,
  không phải tính năng mới, cần review kỹ vì thay đổi hành vi API đã tồn tại.

### 4.4 Rời nhóm (Story 3)

1. `POST /groups/{id}/leave`: lấy `user.sub()` làm `userSub` mục tiêu →
   `requireNotLastOwner(id, user.sub())` → `deleteByGroupIdAndUserSub` →
   audit `group.leave`.
2. **Không** động vào `document_group_share` (Quyết định 4 SA, AC-3 Story 3)
   — không viết thêm logic dọn share khi rời nhóm.

### 4.5 Xoá nhóm (Story 4)

1. `DELETE /groups/{id}`: `requireOwner` (bất kỳ owner nào, không cần "mọi
   owner đồng ý") → `group.setDeletedAt(now())` → save. **Không** xoá
   `group_member`/`document_group_share` (Quyết định 5 SA).
2. `BusinessDataScope.forDocuments` (ở `rbac-group-scope`) hiện lấy
   `memberGroupIds` qua `GroupMemberRepository.findGroupIdsByUserSub` —
   **[SỬA bắt buộc]** thêm join/điều kiện `group.deleted_at IS NULL` vào
   query đó (native query hoặc thêm `@Query` mới), nếu không thì nhóm đã
   xoá vẫn cấp quyền xem tài liệu — đây là lỗ hổng bảo mật nếu bỏ sót
   (đúng rủi ro "Cao" SA đã cảnh báo).
3. Audit `group.delete`.

### 4.6 Giới hạn số lượng (Story 6)

1. Hằng số trong `GroupsService`: `MAX_MEMBERS_PER_GROUP=200`,
   `MAX_GROUPS_PER_USER=200`, `MAX_GROUPS_SYSTEM=1000`.
2. `create`: `groupRepository.countByDeletedAtIsNull() >= MAX_GROUPS_SYSTEM`
   → `422`; đồng thời kiểm tra `groupMemberRepository.countByUserSub(user.sub())
   >= MAX_GROUPS_PER_USER` (người tạo tự thêm mình).
3. `addMember`/`changeMemberRole` (chỉ khi thêm mới, không áp khi đổi vai
   trò của thành viên đã có): `groupMemberRepository.countByGroupId(groupId)
   >= MAX_MEMBERS_PER_GROUP` → `422`; `groupMemberRepository.countByUserSub(dto.userSub())
   >= MAX_GROUPS_PER_USER` → `422`.
4. Mã lỗi trả về dạng `{ code: "GROUP_LIMIT_EXCEEDED", detail: "..." }` —
   tái dùng cấu trúc lỗi 422 hiện có của hệ thống (kiểm tra
   `ApiExceptionHandler` để khớp format, không tạo format lỗi riêng).

### 4.7 Danh bạ người dùng (Story 5)

1. `GET /users/directory?q=`: validate `q.length() >= 2` (400 nếu không).
2. Query `AccountRepository` (LIKE không phân biệt hoa/thường trên
   `username`/`display_name`, `active = true`), `LIMIT 20`.
3. Map sang DTO tối giản `{sub, username, displayName, role}` — **không**
   trả field khác của entity `Account` (rò rỉ dữ liệu quản trị).
4. Audit `user.directory_search` với `q` trong metadata.

## 5. Test plan

- **Unit test:**
  - `requireNotLastOwner`: owner duy nhất → throw; owner không duy nhất →
    pass; member (không phải owner) → pass (không áp dụng).
  - Giới hạn số lượng: mock repository trả đúng ngưỡng - 1 / đúng ngưỡng /
    ngưỡng + 1 cho cả 3 loại giới hạn (Story 6 AC-1-3).
- **Integration test:**
  - Story 1: owner sửa tên → 200; member sửa → 403; đổi tên trùng tên nhóm
    khác (khác hoa/thường) → 409; đổi tên trùng tên **nhóm đã xoá** → cho
    phép (200) — xác nhận partial unique index hoạt động đúng.
  - Story 2: owner hạ owner-duy-nhất xuống member → 409; owner hạ 1-trong-2-owner
    xuống member → 200, group vẫn còn owner còn lại.
  - Story 3: member rời nhóm bất kỳ lúc → 204; owner-duy-nhất rời → 409;
    owner rời khi còn owner khác → 204; sau khi rời, share do người đó tạo
    trước đây **vẫn còn** cho thành viên còn lại xem được (AC-3).
  - Story 4: owner xoá nhóm → 204, `GET /groups` không còn thấy nhóm đó
    (kể cả với thành viên khác); tài liệu chỉ chia sẻ qua nhóm đó (không
    phải người nạp) → biến mất khỏi `GET /documents` của thành viên cũ ngay
    sau khi xoá (kiểm chứng trực tiếp lỗ hổng ở mục 4.5.2 đã được vá).
  - Story 5: `q` 1 ký tự → 400; tài khoản `locked` không xuất hiện trong
    kết quả; R3 gọi → 403.
  - Story 6: tạo nhóm thứ 1.001 (sau khi seed 1.000 nhóm active) → 422;
    thêm thành viên thứ 201 vào 1 nhóm → 422; thêm 1 người vào nhóm thứ 201
    của chính họ → 422.
- **Case biên/lỗi cần cover:**
  - Xoá nhóm rồi tạo nhóm mới trùng tên hệt tên nhóm đã xoá — phải cho
    phép (Story 4 AC-4).
  - Gọi `PATCH`/`DELETE`/`leave` trên `groupId` đã `deleted_at` → 404 (không
    phân biệt với "chưa từng tồn tại").
  - `removeMember` hiện có (đã Approved) bị thay đổi hành vi (thêm chặn
    owner cuối) — cần test hồi quy xác nhận **không** phá behavior cũ khi
    gỡ member thường.

## 6. Task breakdown

- [x] Task 1: Migration `V23__group_lifecycle.sql` (`deleted_at`, partial
      unique index tên nhóm, permission `user.directory_search`).
- [x] Task 2: `Group` entity + repository — thêm field `deletedAt`, các
      query method lọc `deleted_at IS NULL` (`countByDeletedAtIsNull`,
      sửa `existsById`/`findAllById` dùng nếu cần custom query).
- [x] Task 3: `GroupsService.update`, `changeMemberRole`, `leaveGroup`,
      `deleteGroup` + hàm dùng chung `requireNotLastOwner`.
- [x] Task 4: Sửa `GroupsService.removeMember` gọi `requireNotLastOwner`
      trước khi xoá (vá hành vi thiếu ở code Approved trước đó).
- [x] Task 5: Thêm giới hạn số lượng vào `create`/`addMember`/`changeMemberRole`.
- [x] Task 6: **[QUAN TRỌNG — rủi ro bảo mật]** sửa
      `BusinessDataScope`/`GroupMemberRepository.findGroupIdsByUserSub` (ở
      package `rbac-group-scope` đã có) để loại nhóm `deleted_at IS NOT NULL`
      khỏi phạm vi nhìn thấy tài liệu.
- [x] Task 7: `UserDirectoryController`/`UserDirectoryService`, đọc
      `AccountRepository` hiện có, audit `user.directory_search`. **[LỆCH SO
      VỚI SPEC — xem báo cáo implement]**: bảng `account` cục bộ không có cột
      `username`/`display_name`/`sub`/`active` như mô tả ở mục 2/4.7 — các
      trường đó chỉ tồn tại trong Keycloak. Đã đổi sang gọi
      `KeycloakAdminService.searchUsers` (mới, dùng tham số `search` của
      Keycloak Admin API) rồi lọc theo `account.status` cục bộ, cùng pattern
      `AccountsService#list()` đã dùng — contract API/response giữ nguyên
      100% theo mục 3.2.
- [x] Task 8: 4 endpoint mới trong `GroupsController` (mục 3.1).
- [x] Task 9: Unit + integration test theo mục 5 — `GroupsServiceTest`,
      `UserDirectoryServiceTest` chạy được (không cần Docker) và pass.
      `GroupLifecycleIT`, `UserDirectoryControllerIT` (Testcontainers) đã
      viết + compile được nhưng **chưa chạy được trong môi trường implement
      này** (không có Docker) — cần CI/máy có Docker chạy xác nhận trước khi
      coi Task 9 là "Done" đầy đủ.
- [x] Task 10: Frontend `web/src/app/groups/` — danh sách, tạo nhóm, chi
      tiết nhóm (sửa tên/mô tả, thành viên, đổi vai trò, rời, xoá) + form
      tìm người dùng dùng lại `GET /users/directory`.
- [x] Task 11: `AppShell.tsx`/`lib/api.ts`/`lib/features.ts` — thêm liên
      kết `/groups`, ẩn với R3.

## 7. Rủi ro kỹ thuật

- **Bỏ sót cập nhật `BusinessDataScope` sau khi thêm soft delete (Task 6)**
  là rủi ro **Cao nhất** của spec này — nhóm bị xoá vẫn cấp quyền xem tài
  liệu nếu quên. Bắt buộc có test tích hợp riêng xác nhận (mục 5, Story 4).
- **Thay đổi hành vi `removeMember` đã Approved** (Task 4) — cần đảm bảo
  không breaking test hiện có của `rbac-group-scope`; chạy lại toàn bộ test
  suite của package `groups` sau khi sửa, không chỉ test mới.
- **Partial unique index tên nhóm** có thể fail migration nếu dữ liệu demo
  hiện tại đã có tên trùng (không phân biệt hoa/thường) — kiểm tra dữ liệu
  trước khi chạy `V23` ở môi trường có sẵn dữ liệu, thêm bước dọn trùng vào
  migration nếu cần (không giả định DB trống).
- **Giới hạn số lượng hard-code** dễ lệch nếu sau này chuyển sang
  `system-config` — chấp nhận rủi ro thấp vì đã chốt với khách hàng ở MVP.
