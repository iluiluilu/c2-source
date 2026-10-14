# Tech Spec: rbac-group-scope

> File: `docs/03-dev/specs/rbac-group-scope.md` — Owner: DEV
> Nguồn story: `docs/02-ba/stories/rbac-group-scope.md`
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
> | 6 | `rbac-group-scope` ⬅ **spec này** | `V12__init_groups.sql` + `V13__drop_case_files.sql` |
> | 7 | `auth-session` | `V14__init_keycloak_event_cursor.sql` |
>
> `rbac-group-scope` và `auth-session` không phụ thuộc spec nào khác nên
> **được phép code song song ngay từ đầu** — bảng trên chỉ ràng buộc thời
> điểm *merge*, không ràng buộc thời điểm *phát triển*.

## 1. Tóm tắt kỹ thuật

- **RBAC tĩnh (Story 1, 2):** giữ nguyên cơ chế `@PreAuthorize("hasRole(...)")`
  hiện có (đã đúng ma trận SRS cho R1/R2/R3 qua Keycloak). Bổ sung 2 bảng
  seed-only `role`/`permission` (PostgreSQL) làm nguồn tham chiếu tập trung
  — **không** thay cơ chế enforce hiện tại, không có API ghi. Việc gán vai
  trò (Story 2) đã có sẵn ở `AccountsService`/`AssignableRoles` — chỉ cần
  audit đã có, không cần code mới cho AC-1–AC-3 (đã đáp ứng), chỉ bổ sung
  test xác nhận hành vi.
- **Group/Membership/Share (Story 5):** package mới
  `com.aismartstore.backend.groups` (đã tồn tại thư mục rỗng + `dto/` trong
  repo — dùng lại, không tạo package mới) với entity `Group`, `GroupMember`,
  `DocumentGroupShare`; migration `V12__init_groups.sql` (tạo bảng mới) +
  `V13__drop_case_files.sql` (xoá `casefiles`, xoá thẳng vì toàn bộ là dữ
  liệu demo — theo Quyết định 2 SA/BA, không di trú).
- **Row-level scope filter (Story 3, 4):** class dùng chung
  `BusinessDataScope` (Spring Data JPA `Specification`) tạo điều kiện SQL:
  `uploader = current_user OR document.id IN (SELECT document_id FROM
  document_group_share WHERE group_id IN (SELECT group_id FROM group_member
  WHERE user_id = current_user))`. Mọi entrypoint đọc dữ liệu nghiệp vụ
  (hiện tại: `DocumentsService.list/findOne/getContent`,
  `NotesService.listForDocument`, `TagsController` gián tiếp qua document)
  phải áp dụng specification này qua base repository chung.
- **Fail-closed (Story 4):** `BusinessDataScope` throw
  `ScopeResolutionException` (unchecked) nếu không lấy được nhóm của người
  dùng hiện tại (lỗi DB, lỗi token...); một `@ControllerAdvice` handler map
  exception này thành `403`, ghi log — không bao giờ trả specification rỗng
  (rỗng ⇒ có thể vô tình khớp "tất cả" tuỳ ORM, phải throw để chặn cứng).
- **Xoá `casefiles` hoàn toàn:** xoá package backend `casefiles/` (bao gồm
  `CaseFile`, `CaseFileDocument`, `CaseFilesController`, `CaseFilesService`,
  repository, DTO), xoá trang frontend `web/src/app/case-files/**`, gỡ liên
  kết ở `AppShell.tsx`/`lib/api.ts`/`lib/features.ts`, xoá bảng qua
  migration Flyway mới (không sửa `V3` cũ — Flyway migration đã áp dụng thì
  không sửa lại).

## 2. Data model

```sql
-- V12__init_groups.sql
CREATE TABLE "group" (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name         VARCHAR(255) NOT NULL,
    description  TEXT,
    owner_sub    VARCHAR(255) NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE group_member (
    group_id      UUID NOT NULL REFERENCES "group" (id) ON DELETE CASCADE,
    user_sub      VARCHAR(255) NOT NULL,
    role_in_group VARCHAR(16) NOT NULL CHECK (role_in_group IN ('owner','member')),
    joined_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (group_id, user_sub)
);
CREATE INDEX IDX_group_member_user_sub ON group_member (user_sub);
CREATE INDEX IDX_group_member_group_id ON group_member (group_id);

CREATE TABLE document_group_share (
    document_id     UUID NOT NULL REFERENCES document (id) ON DELETE CASCADE,
    group_id        UUID NOT NULL REFERENCES "group" (id) ON DELETE CASCADE,
    shared_by_sub   VARCHAR(255) NOT NULL,
    shared_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (document_id, group_id)
);
CREATE INDEX IDX_document_group_share_group_id ON document_group_share (group_id);
CREATE INDEX IDX_document_group_share_document_id ON document_group_share (document_id);

-- Seed-only reference tables (Quyết định 1 SA — RBAC tĩnh, KHÔNG có API ghi)
CREATE TABLE role (
    code         VARCHAR(32) PRIMARY KEY,   -- 'CAN_BO' | 'LANH_DAO' | 'QUAN_TRI'
    display_name VARCHAR(128) NOT NULL
);

CREATE TABLE permission (
    code         VARCHAR(128) PRIMARY KEY,  -- vd. 'document.upload', 'account.manage'
    description  VARCHAR(255) NOT NULL
);

CREATE TABLE role_permission (
    role_code       VARCHAR(32) NOT NULL REFERENCES role (code),
    permission_code VARCHAR(128) NOT NULL REFERENCES permission (code),
    PRIMARY KEY (role_code, permission_code)
);

-- Seed data (INSERT tĩnh trong cùng migration, khớp ma trận SRS mục 3):
-- role: CAN_BO, LANH_DAO, QUAN_TRI
-- permission: liệt kê theo route @PreAuthorize hiện có (document.upload,
--   document.read, note.write, tag.manage, folder.manage, account.manage,
--   audit.read, group.manage, ... — DEV rà soát toàn bộ @PreAuthorize hiện
--   tại khi viết seed để đảm bảo khớp 100%, xem Task 3).
-- role_permission: map theo đúng annotation hiện có trên controller.

-- V13__drop_case_files.sql
-- Toàn bộ dữ liệu case_file/case_file_document là dữ liệu demo (đã chốt ở
-- SA/BA) — xoá thẳng, không migrate.
DROP TABLE IF EXISTS case_file_document;
DROP TABLE IF EXISTS case_file;
```

```java
// com.aismartstore.backend.groups.Group
@Entity
@Table(name = "\"group\"")
public class Group {
    @Id @GeneratedValue @UuidGenerator
    private UUID id;
    @Column(nullable = false, length = 255) private String name;
    @Column(columnDefinition = "text") private String description;
    @Column(name = "owner_sub", nullable = false) private String ownerSub;
    @Column(name = "created_at", nullable = false) private OffsetDateTime createdAt;
    @Column(name = "updated_at", nullable = false) private OffsetDateTime updatedAt;
    // getters/setters
}

// com.aismartstore.backend.groups.GroupMember (composite key: GroupMemberId)
@Entity
@Table(name = "group_member")
@IdClass(GroupMemberId.class)
public class GroupMember {
    @Id @Column(name = "group_id") private UUID groupId;
    @Id @Column(name = "user_sub") private String userSub;
    @Column(name = "role_in_group", nullable = false, length = 16) private String roleInGroup; // owner|member
    @Column(name = "joined_at", nullable = false) private OffsetDateTime joinedAt;
}

// com.aismartstore.backend.groups.DocumentGroupShare (composite key)
@Entity
@Table(name = "document_group_share")
@IdClass(DocumentGroupShareId.class)
public class DocumentGroupShare {
    @Id @Column(name = "document_id") private UUID documentId;
    @Id @Column(name = "group_id") private UUID groupId;
    @Column(name = "shared_by_sub", nullable = false) private String sharedBySub;
    @Column(name = "shared_at", nullable = false) private OffsetDateTime sharedAt;
}

// Seed-only, read path — không cần entity nếu chỉ tra cứu tĩnh; tuỳ chọn có
// entity JPA read-only (@Immutable) nếu cần hiển thị lại ở UI sau này.
// com.aismartstore.backend.rbac.RoleEntity / PermissionEntity (@Immutable)
```

```java
// Lớp filter dùng chung (Quyết định 3 SA) — Spring Data JPA Specification.
// com.aismartstore.backend.groups.BusinessDataScope
public final class BusinessDataScope {
    /**
     * Trả về Specification<Document> (hoặc entity nghiệp vụ tương ứng) khớp
     * đúng "do chính currentUserSub tạo/nạp HOẶC được chia sẻ tới nhóm mà
     * currentUserSub là thành viên". Throw ScopeResolutionException nếu
     * không xác định được nhóm của user (fail-closed, Story 4).
     */
    public static Specification<Document> forDocuments(String currentUserSub, GroupMemberRepository groupMemberRepo) {
        // implement bằng subquery, KHÔNG load hết group rồi filter ở Java
        // (Quyết định 3: filter ở tầng query để đáp ứng NFR-01).
    }
}

public class ScopeResolutionException extends RuntimeException { /* ... */ }
```

## 3. API contract

### 3.1 RBAC (Story 1, 2) — không có endpoint mới

Story 1/2 không cần endpoint mới: `@PreAuthorize` hiện có trên
`DocumentsController`, `CaseFilesController` (sẽ xoá), `AccountsController`,
`TagsController`, `NotesController`, `WarehouseController`,
`AuditController` đã đúng ma trận SRS — chỉ **rà soát lại** (Task 1) để đối
chiếu với seed `role_permission`, không sửa route nếu đã đúng.
`AccountsController` (`PATCH /accounts/{id}`, `POST /accounts`) đã enforce
`hasRole('QUAN_TRI')` + validate qua `AssignableRoles.ALL` — đáp ứng đủ
Story 2 AC-1/AC-2; audit `account.create`/`account.update` đã ghi — đáp ứng
AC-3.

### 3.2 Group / Membership / Share (nền tảng cho FR-28/29 — API tối giản, đủ cho scoping hoạt động; UI chi tiết ngoài phạm vi)

| Method | Endpoint | Request | Response |
|---|---|---|---|
| POST | `/groups` | `{ name, description? }` | `201 Group` (creator = owner, tự thêm vào `group_member`) |
| GET | `/groups` | – | `200 Group[]` (chỉ nhóm mà current user là thành viên) |
| GET | `/groups/{id}/members` | – | `200 {userSub, roleInGroup, joinedAt}[]` — 403 nếu current user không phải thành viên |
| POST | `/groups/{id}/members` | `{ userSub, roleInGroup }` | `204` — chỉ `owner` của nhóm hoặc `QUAN_TRI`* thực hiện được (*QUAN_TRI chỉ quản trị quan hệ, không đọc nội dung tài liệu — NFR-04) |
| DELETE | `/groups/{id}/members/{userSub}` | – | `204` — chỉ `owner` |
| POST | `/documents/{id}/shares` | `{ groupId }` | `204` — chỉ người tạo/nạp tài liệu hoặc thành viên nhóm hiện đang sở hữu quyền truy cập tài liệu đó (đã pass qua `BusinessDataScope`) |
| DELETE | `/documents/{id}/shares/{groupId}` | – | `204` |
| GET | `/documents/{id}/shares` | – | `200 {groupId, groupName, sharedBySub, sharedAt}[]` |

Toàn bộ endpoint `/groups/**`, `/documents/{id}/shares/**` dùng
`@PreAuthorize("hasAnyRole('CAN_BO','LANH_DAO')")` (không có `QUAN_TRI`
cho phần đọc/ghi liên quan tới nội dung — trừ endpoint quản lý thành viên
có thể cho `QUAN_TRI` theo ghi chú ở trên; DEV quyết định cụ thể khi code,
miễn giữ đúng NFR-04: R3 không đọc được **nội dung** tài liệu).

### 3.3 Entrypoint hiện có cần áp `BusinessDataScope` (Story 3 AC-3)

| Entrypoint hiện tại | Thay đổi |
|---|---|
| `GET /documents`, `GET /documents/{id}`, `GET /documents/{id}/content` | Thêm điều kiện scope vào `DocumentRepository.search`/`findById` (chuyển sang `Specification` hoặc thêm điều kiện native query) |
| `GET /documents/{documentId}/notes` | Join qua `document` để áp scope (ghi chú thuộc về đúng 1 tài liệu — không có phạm vi riêng) |
| Tìm kiếm (FR-22–25), báo cáo (FR-30/31), API ngoài (FR-32) | **Chưa có module hiện tại trong code** (rà soát repo xác nhận không tồn tại `search`/`report` module) — ghi vào checklist "câu hỏi mở cho DEV" của BA: áp `BusinessDataScope` **bắt buộc** ngay khi các module đó được implement, không phải việc của spec này |

## 4. Luồng xử lý chính

### 4.1 Enforce RBAC + audit khi từ chối (Story 1)

1. Request tới route có `@PreAuthorize` → Spring Security đánh giá trước khi
   vào controller method.
2. Nếu role không khớp → Spring ném `AccessDeniedException` → xử lý ở
   `ApiExceptionHandler` (rà soát file hiện có, bổ sung nếu chưa ghi audit):
   map thành `403` **và** gọi `AuditService.recordAsync` với
   `action = "access.denied"`, `outcome = "failure"`, `resourceType`/`path`
   từ request (Story 1 AC-3).
3. R3 không có route nghiệp vụ nào cấp quyền (đã đúng ở
   `DocumentsController`/`NotesController`/`TagsController` — không có
   `QUAN_TRI` trong `@PreAuthorize` của các route đó) — Story 1 AC-2 đã đáp
   ứng bởi code hiện tại, chỉ cần test xác nhận.

### 4.2 Gán vai trò (Story 2) — dùng lại code hiện có

1. R3 gọi `POST /accounts` hoặc `PATCH /accounts/{id}` với `roles` ⊂
   `AssignableRoles.ALL` (`can-bo`/`lanh-dao`/`quan-tri`).
2. `AccountsService` validate role hợp lệ (đã có, ném `BadRequestException`
   nếu sai) → gọi Keycloak Admin API gán/gỡ role → ghi audit
   `account.create`/`account.update` với `roles`/`rolesAdded`/`rolesRemoved`
   trong `metadata` (đã có).
3. Không có thay đổi code — chỉ bổ sung integration test khẳng định hành
   vi khớp AC-1–AC-3.

### 4.3 Tạo nhóm, thêm thành viên, chia sẻ tài liệu (Story 5 nền tảng)

1. `POST /groups`: tạo `Group` (owner_sub = current user), insert
   `group_member(group_id, user_sub=current, role_in_group='owner')` trong
   1 transaction. Audit `group.create`.
2. `POST /groups/{id}/members`: kiểm tra current user là `owner` của group
   (hoặc `QUAN_TRI` nếu cho phép quản trị quan hệ) → insert `group_member`.
   Audit `group.add_member`.
3. `POST /documents/{id}/shares`: kiểm tra current user đang thấy được
   `document` đó (đi qua `BusinessDataScope` khi load document — nếu
   load được nghĩa là có quyền) → insert `document_group_share`. Audit
   `document.share`.

### 4.4 Áp scope filter khi đọc dữ liệu nghiệp vụ (Story 3)

1. Mọi service đọc dữ liệu nghiệp vụ (`DocumentsService.list/findOne/
   getContent`, `NotesService.listForDocument`) nhận thêm tham số
   `KeycloakUser currentUser` (nếu chưa có) và gọi
   `BusinessDataScope.forDocuments(currentUser.sub(), groupMemberRepository)`
   để lấy `Specification`, kết hợp với điều kiện filter hiện có
   (`folderId`/`tagId`) qua `Specification.where(...).and(...)`.
2. Nếu `documentId` không nằm trong scope (không phải chủ sở hữu, không
   thuộc nhóm được chia sẻ) → repository trả rỗng → service ném
   `NotFoundException` (**không** phân biệt "không tồn tại" với "không có
   quyền", tránh rò rỉ thông tin tồn tại của tài liệu) + ghi audit
   `access.denied` khi phát hiện tài liệu tồn tại nhưng ngoài scope (Story 3
   AC-4) — DEV cần phân tách 2 case này rõ trong code (load without scope
   chỉ để log, không trả data).
3. `DocumentRepository.search` (hiện là native query) chuyển sang dùng
   `Specification` (`JpaSpecificationExecutor`) hoặc thêm điều kiện scope
   trực tiếp vào SQL native bằng cách nối thêm `AND (uploader_sub = :sub OR
   d.id IN (subquery chia sẻ))` — DEV chọn theo độ phức tạp (Quyết định 3
   SA cho phép cả Specification/Querydsl; native SQL nối chuỗi điều kiện
   cũng chấp nhận được nếu giữ tham số hoá đầy đủ chống SQL injection).

### 4.5 Fail-closed (Story 4)

1. `BusinessDataScope.forDocuments(...)` bọc toàn bộ logic build điều kiện
   trong try/catch; bất kỳ lỗi (DB lỗi khi tra `group_member`, token thiếu
   `sub`...) → throw `ScopeResolutionException` (unchecked).
2. `ApiExceptionHandler` bắt `ScopeResolutionException` → trả `403` (không
   trả `500`, để không gợi ý lỗi hệ thống có thể khai thác), ghi log ERROR
   kèm stack trace để truy vết kỹ thuật (Story 4 AC-2).
3. Thiết kế API: mọi phương thức repository đọc dữ liệu nghiệp vụ **bắt
   buộc** nhận `Specification`/điều kiện scope làm tham số không có giá trị
   mặc định "không lọc" — code review checklist (Story 4 AC-3) đảm bảo
   entrypoint mới không quên gọi `BusinessDataScope`.

### 4.6 Xoá casefiles

1. Xoá thư mục `backend/src/main/java/.../casefiles/**` (toàn bộ file).
2. Thêm migration `V13__drop_case_files.sql` (`DROP TABLE`).
3. Xoá `web/src/app/case-files/**`, gỡ liên kết trong `AppShell.tsx`,
   `lib/api.ts`, `lib/features.ts`.
4. Grep toàn repo `case-file|caseFile|CaseFile` sau khi xoá để đảm bảo
   không còn tham chiếu treo (import lỗi build).

## 5. Test plan

- **Unit test:**
  - `BusinessDataScope`: sinh đúng điều kiện SQL cho 3 case — (a) tài liệu
    do chính user tạo, (b) tài liệu chia sẻ tới nhóm user là thành viên,
    (c) tài liệu ngoài cả 2 điều kiện → không khớp. Story 3 AC-1/AC-2.
  - `BusinessDataScope` throw `ScopeResolutionException` khi
    `groupMemberRepository` ném lỗi (mock DB exception) — Story 4 AC-1.
  - Seed `role_permission` khớp 100% với danh sách `@PreAuthorize` hiện có
    trên toàn bộ controller (test đối chiếu tĩnh, có thể viết dạng test so
    khớp danh sách permission mong đợi) — Story 1 AC-4.
- **Integration test:**
  - R1 (`CAN_BO`) chỉ thấy tài liệu do mình tạo + tài liệu chia sẻ tới nhóm
    mình tham gia; không thấy tài liệu của người khác chưa chia sẻ — Story
    3 AC-1/AC-2/AC-3.
  - R2 (`LANH_DAO`) áp dụng đúng scope tương tự R1 (đọc), không có quyền
    ghi (đã có `@PreAuthorize` phân role) — Story 1 AC-1.
  - R3 gọi `GET /documents`, `GET /documents/{id}/content` → 403 (không có
    route nào cấp `QUAN_TRI`) — Story 1 AC-2.
  - Truy cập route sai vai trò → 403 + có audit event `access.denied` mới
    nhất khớp actor/path — Story 1 AC-3.
  - R3 gán role qua `POST /accounts`/`PATCH /accounts/{id}` với role ngoài
    `AssignableRoles.ALL` → 400; với role hợp lệ → 200 + audit — Story 2
    AC-1–AC-3.
  - Tạo nhóm, thêm thành viên, chia sẻ tài liệu → thành viên mới thấy được
    tài liệu đã chia sẻ ngay (không cần thêm bước gì) — Story 3, Story 5.
  - Giả lập lỗi tầng scoping (mock `GroupMemberRepository` ném exception
    trong 1 test riêng dùng `@MockBean`) → toàn bộ entrypoint đọc dữ liệu
    nghiệp vụ trả 403, không trả data — Story 4 AC-1/AC-2.
  - Sau migration `V8`, bảng `case_file`/`case_file_document` không còn tồn
    tại (query information_schema) — Story 5 AC-3.
  - `document_group_share` chỉ chứa `document_id`/`group_id` (không có cột
    lưu bản sao nội dung/đường dẫn tệp riêng) — Story 5 AC-2.
- **Case biên/lỗi cần cover:**
  - Tài liệu chưa chia sẻ tới nhóm nào: chỉ người tạo/nạp thấy, người khác
    (kể cả cùng phòng/nhóm khác) không thấy, dù không có exception nào xảy
    ra (đường "happy path" của rule mặc định, không phải lỗi) — Story 3
    AC-2.
  - Người dùng bị xoá khỏi nhóm sau khi tài liệu đã chia sẻ tới nhóm đó:
    mất quyền truy cập ngay ở lần đọc kế tiếp (không cache phía server) —
    edge case nên test rõ vì dễ bị bỏ sót khi tối ưu hiệu năng sau này.
  - `owner` duy nhất của nhóm bị xoá tài khoản (Keycloak) — nhóm vẫn tồn
    tại, các `member` khác vẫn còn quyền qua `document_group_share`; không
    có race điều kiện engine.
  - Build backend sau khi xoá `casefiles` không còn tham chiếu treo (biên
    dịch pass) — kiểm bằng `mvn compile` trong CI.

## 6. Task breakdown

- [x] Task 1: Rà soát toàn bộ `@PreAuthorize` hiện có trên các controller
      (`documents`, `notes`, `tags`, `accounts`, `warehouse`, `audit`, và
      controller mới ở Task 6) → lập danh sách `permission` đầy đủ khớp ma
      trận SRS mục 3.
- [x] Task 2: Migration `V12__init_groups.sql` (bảng `group`,
      `group_member`, `document_group_share`, `role`, `permission`,
      `role_permission` + seed data theo Task 1).
- [x] Task 3: Migration `V13__drop_case_files.sql`
      (`DROP TABLE case_file_document`, `case_file`).
- [x] Task 4: Xoá package backend `casefiles/**` (entity, repository,
      service, controller, DTO) — build lại đảm bảo không còn tham chiếu.
- [x] Task 5: Xoá frontend `web/src/app/case-files/**`; gỡ liên kết ở
      `AppShell.tsx`, `lib/api.ts`, `lib/features.ts`.
- [x] Task 6: Entity + repository `Group`, `GroupMember`,
      `DocumentGroupShare` (package `com.aismartstore.backend.groups`,
      tái dùng thư mục có sẵn); `GroupsController`/`GroupsService` cho API
      ở mục 3.2 (tạo nhóm, thêm/xoá thành viên, chia sẻ/gỡ chia sẻ tài
      liệu, list share).
- [x] Task 7: `BusinessDataScope` (Specification dùng chung) +
      `ScopeResolutionException` + xử lý ở `ApiExceptionHandler` (map
      `403`, log ERROR) — Story 3, Story 4.
- [x] Task 8: Áp `BusinessDataScope` vào `DocumentsService`
      (`list`/`findOne`/`getContent`) và `NotesService.listForDocument`;
      sửa `DocumentRepository.search` để nhận điều kiện scope.
- [x] Task 9: Bổ sung ghi audit `access.denied` khi (a) `AccessDeniedException`
      từ `@PreAuthorize` (Story 1 AC-3), (b) truy cập tài liệu tồn tại nhưng
      ngoài scope (Story 3 AC-4) — rà soát `ApiExceptionHandler` hiện có,
      chỉ bổ sung phần thiếu.
- [x] Task 10: Unit test + integration test theo mục 5 (bao gồm test seed
      role/permission khớp `@PreAuthorize`, test fail-closed bằng mock lỗi
      DB, test R3 không đọc được nội dung nghiệp vụ).
- [x] Task 11: Grep toàn repo `case-file|caseFile|CaseFile` sau khi xoá,
      xác nhận build backend (`mvn compile`) và frontend (`next build` hoặc
      tương đương) pass.
- [x] Task 12: Cập nhật trạng thái task trong file spec này (đánh dấu
      done) sau khi implement — không viết lại toàn bộ spec.

## 7. Rủi ro kỹ thuật

- **Bỏ sót entrypoint chưa áp `BusinessDataScope`** (rủi ro Cao theo SA):
  hiện tại chỉ có `documents`/`notes` là entrypoint đọc dữ liệu nghiệp vụ
  thực sự tồn tại trong code; các module tìm kiếm/báo cáo/API ngoài
  (FR-22–25, FR-30/31, FR-32) **chưa được implement** — checklist ở BA
  "Câu hỏi mở cho DEV" cần được giữ và cập nhật liên tục mỗi khi thêm
  entrypoint mới, không thể đóng ở spec này.
- **`DocumentRepository.search` hiện là native SQL** — chuyển sang
  `Specification`/thêm điều kiện scope cần cẩn trọng tránh SQL injection
  khi nối điều kiện động; nên ưu tiên `Specification` (JPA Criteria, có
  tham số hoá tự động) hơn nối chuỗi native SQL thủ công.
- **Phân biệt "404 không tồn tại" vs "403/404 ngoài scope"**: quyết định
  trả `404` cho cả 2 case (để không rò rỉ thông tin tồn tại) yêu cầu code
  phải load "raw" (không scope) chỉ để ghi audit khi phát hiện tài liệu có
  tồn tại nhưng ngoài phạm vi — dễ nhầm lẫn logic giữa "audit-only load" và
  "authorized load", cần review kỹ để không vô tình trả dữ liệu qua nhánh
  audit.
- **Xoá `casefiles` là thay đổi phá vỡ (breaking) cả backend và frontend**
  — cần xoá đồng bộ 2 phía trong cùng 1 lần merge để tránh trạng thái nửa
  vời (frontend gọi API đã xoá). Không có bước rollback dữ liệu (đã chốt
  không cần).
- **Seed `role`/`permission` là dữ liệu tĩnh nhưng dễ lệch với code** nếu
  sau này thêm `@PreAuthorize` mới mà quên cập nhật seed — vì bảng này chỉ
  mang tính tham chiếu (không được code đọc để enforce), rủi ro là seed
  "nói dối" so với hành vi thực tế của hệ thống theo thời gian; nên có test
  đối chiếu tự động (Task 1/Task 10) để phát hiện sớm khi seed lệch.
  **Cập nhật 2026-08-17:** rủi ro này được giải quyết trực tiếp bởi
  `docs/03-dev/specs/rbac-permission-editing.md` — `role_permission` chuyển
  từ "chỉ tham chiếu" sang **được code đọc để enforce thật** (qua cache),
  nên seed không còn thể "nói dối" so với hành vi runtime; `role`/`permission`
  không còn seed-only kể từ spec đó.
- **Hiệu năng subquery `group_member`/`document_group_share`** ở quy mô
  NFR-03 (vài trăm nhóm × 100 thành viên) — rủi ro thấp với index đã định
  nghĩa ở Task 2, nhưng cần đo lại khi có nhiều entrypoint cùng dùng
  `BusinessDataScope` đồng thời (search/report sau này) để đảm bảo NFR-01
  (phản hồi vài giây) không bị vi phạm khi tải tăng.
