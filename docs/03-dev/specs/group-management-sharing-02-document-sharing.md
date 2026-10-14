# Tech Spec: group-management-sharing-02-document-sharing

> File: `docs/03-dev/specs/group-management-sharing-02-document-sharing.md` — Owner: DEV
> Nguồn story: `docs/02-ba/stories/group-management-sharing-02-document-sharing.md`
> Status: Approved

> ## ⚠️ Trạng thái so với code hiện tại — đọc trước khi implement
>
> `DocumentSharesController`/`GroupsService.shareDocument/unshareDocument/
> listShares` **đã chạy được** cho case 1 tài liệu × 1 nhóm (`rbac-group-scope`,
> Approved). Spec này bổ sung chia sẻ hàng loạt, liệt kê theo chiều ngược
> lại, sửa quyền gỡ chia sẻ, và xây mới toàn bộ module thông báo (0 dòng
> code hiện có, xác nhận bằng grep `notification` trong
> `backend/src/main/java` → không có kết quả). Phụ thuộc migration `V23`
> của `group-management-sharing-01-group-lifecycle.md` (cột
> `deleted_at`) — spec này dùng số hiệu tiếp theo `V24`.

## 1. Tóm tắt kỹ thuật

- **[MỚI]** `POST /documents/shares` — chia sẻ nhiều `documentId` vào 1
  `groupId` trong 1 request, tối đa 100 tài liệu, xử lý từng tài liệu độc
  lập (không transaction chung cho toàn bộ danh sách — mỗi tài liệu 1
  transaction nhỏ để "một tài liệu lỗi không hỏng cả lượt").
- **[MỚI]** `GET /groups/{id}/documents` — chiều dữ liệu ngược với
  `GET /documents/{id}/shares` đã có; join `document_group_share` →
  `document`, lọc tài liệu chưa soft-delete, phân trang 50.
- **[SỬA]** `GroupsService.unshareDocument` — thêm điều kiện quyền: owner
  nhóm gỡ mọi share; member chỉ gỡ share do chính mình tạo hoặc tài liệu do
  chính mình nạp (Quyết định 2 SA). Hiện tại **không** có điều kiện này —
  đây là thay đổi hành vi bảo mật, cần review kỹ.
- **[MỚI]** module `notifications` (package mới
  `com.aismartstore.backend.notifications`): entity `Notification`,
  repository, `NotificationService` (ghi ngoài transaction nghiệp vụ),
  `NotificationsController` (`GET /notifications`, `POST
  /notifications/{id}/read`).
- **[MỚI]** frontend: panel "Chia sẻ" trên `web/src/app/documents/[id]`,
  badge thông báo trong `AppShell`, tab "Tài liệu của nhóm" ở
  `web/src/app/groups/[id]`.

## 2. Data model

```sql
-- V24__document_sharing_notifications.sql
CREATE TABLE notification (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    recipient_sub VARCHAR(255) NOT NULL,
    type          VARCHAR(64) NOT NULL, -- 'group.member_added' | 'group.member_removed' | 'document.shared_to_group'
    payload_json  JSONB NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    read_at       TIMESTAMPTZ
);
CREATE INDEX IDX_notification_recipient ON notification (recipient_sub, created_at DESC);
CREATE INDEX IDX_notification_created_at ON notification (created_at); -- cho job dọn TTL 90 ngày
```

```java
// com.aismartstore.backend.notifications.Notification
@Entity
@Table(name = "notification")
public class Notification {
    @Id @GeneratedValue @UuidGenerator private UUID id;
    @Column(name = "recipient_sub", nullable = false) private String recipientSub;
    @Column(nullable = false, length = 64) private String type;
    @Column(name = "payload_json", columnDefinition = "jsonb", nullable = false) private String payloadJson;
    @Column(name = "created_at", nullable = false) private OffsetDateTime createdAt;
    @Column(name = "read_at") private OffsetDateTime readAt;
    // getters/setters
}
```

- **Không** cần bảng mới cho Story 1/2/3 — tái dùng `document_group_share`
  đã có nguyên vẹn (chỉ thêm truy vấn mới, không đổi schema).

## 3. API contract

### 3.1 Chia sẻ hàng loạt — mới

| Method | Endpoint | Request | Response |
|---|---|---|---|
| POST | `/documents/shares` | `{ groupId, documentIds: UUID[] }` (≤ 100) | `200 { results: [{documentId, status: "shared"\|"already_shared"\|"rejected", reason?}] }` |

`@PreAuthorize("hasAuthority('PERM_document.share')")` — tái dùng permission
hiện có, không thêm mới.

```java
// DTO mới
public record BulkShareDto(@NotNull UUID groupId, @NotEmpty @Size(max = 100) List<UUID> documentIds) {}
public record BulkShareResultItem(UUID documentId, String status, String reason) {}
```

### 3.2 Tài liệu của một nhóm — mới

| Method | Endpoint | Request | Response |
|---|---|---|---|
| GET | `/groups/{id}/documents?page=&size=` | `size` mặc định 50, tối đa 50 | `200 Page<{documentId, title, uploaderSub, sharedBySub, sharedAt}>` |

`@PreAuthorize("hasAuthority('PERM_group.manage')")` (đọc thông tin nhóm —
tái dùng permission đã seed, khớp cách phân quyền các endpoint `/groups/**`
hiện có) + kiểm tra thành viên trong service (403/404 nếu không phải thành
viên, giống `listMembers`).

### 3.3 `DocumentSharesController` — sửa quyền gỡ chia sẻ

| Method | Endpoint | Thay đổi |
|---|---|---|
| DELETE | `/documents/{id}/shares/{groupId}` | **[SỬA]** service kiểm tra thêm: current user là owner của `groupId`, HOẶC `shared_by_sub` của share đó = current user, HOẶC `document.uploaderSub` = current user — không thoả cả 3 → `403` |
| POST, GET | `/documents/{id}/shares` | **[ĐÃ CÓ, không đổi]** |

### 3.4 `NotificationsController` — mới

| Method | Endpoint | Request | Response |
|---|---|---|---|
| GET | `/notifications?unreadOnly=` | – | `200 {id, type, payload, createdAt, readAt}[]` (mới nhất trước, giới hạn hợp lý ví dụ 100) |
| POST | `/notifications/{id}/read` | – | `204` — chỉ đánh dấu thông báo của chính mình |

`@PreAuthorize("hasAnyRole('CAN_BO','LANH_DAO')")` — không cần permission
mới riêng, đây là dữ liệu cá nhân của người gọi (giống cách `personal-storage-quota`
không cần permission cấp cao).

## 4. Luồng xử lý chính

### 4.1 Chia sẻ hàng loạt (Story 1)

1. `POST /documents/shares`: `requireGroupExists(dto.groupId())` (dùng lại
   helper hiện có) một lần cho cả request.
2. Với mỗi `documentId` trong `dto.documentIds()` (xử lý tuần tự, mỗi item
   transaction riêng — dùng `@Transactional` ở method helper riêng, **không**
   bọc `@Transactional` ở method ngoài cùng để tránh 1 item lỗi rollback cả
   danh sách):
   - `documentScopeGuard.requireVisible(user, documentId)` — bắt
     `NotFoundException` → `status="rejected", reason="not_visible"`.
   - Đếm số nhóm hiện đang chia sẻ tài liệu này
     (`documentGroupShareRepository.countByDocumentId(documentId)`) —
     `>= 50` → `status="rejected", reason="group_limit_exceeded"`.
   - Insert `document_group_share`; bắt unique violation (đã chia sẻ trước
     đó) → `status="already_shared"` (không phải lỗi).
   - Thành công → `status="shared"`, audit `document.share` riêng cho item
     này (giữ nguyên granularity hiện có).
3. Sau khi xử lý xong toàn bộ danh sách, gọi
   `notificationService.notifyDocumentsSharedToGroup(groupId, sharedDocumentIds, actorSub)`
   — **1 lần**, gộp thành 1 thông báo/nhóm/lượt (Story 4 AC-3) — gọi **ngoài**
   transaction nghiệp vụ của từng item share (best-effort, xem 4.4).

### 4.2 Tài liệu của một nhóm (Story 2)

1. `GET /groups/{id}/documents`: `requireGroupExists` (đã lọc `deleted_at
   IS NULL` theo Task 6 của spec `-01-group-lifecycle`) →
   `groupMemberRepository.existsByGroupIdAndUserSub` (403/404 nếu không
   phải thành viên) → query join `document_group_share` ⋈ `document` WHERE
   `group_id = :id AND document.deleted_at IS NULL` (cột soft-delete tài
   liệu thuộc `delete-restore-trash`, DEV xác nhận tên cột chính xác khi
   code) ORDER BY `shared_at DESC` phân trang.
2. Trả `title`, `uploaderSub` (từ `document`), `sharedBySub`, `sharedAt`
   (từ `document_group_share`).

### 4.3 Gỡ chia sẻ theo quyền (Story 3)

```java
// GroupsService.unshareDocument — sửa
@Transactional
public void unshareDocument(KeycloakUser user, UUID documentId, UUID groupId) {
    Document document = documentScopeGuard.requireVisible(user, documentId);
    DocumentGroupShare share = documentGroupShareRepository
            .findByDocumentIdAndGroupId(documentId, groupId) // method mới trên repository
            .orElseThrow(() -> new NotFoundException("Document is not shared to this group"));

    boolean isGroupOwner = groupMemberRepository.findByGroupIdAndUserSub(groupId, user.sub())
            .map(m -> GroupRole.OWNER.equals(m.getRoleInGroup())).orElse(false);
    boolean isSharer = share.getSharedBySub().equals(user.sub());
    boolean isUploader = document.getUploaderSub().equals(user.sub());
    if (!isGroupOwner && !isSharer && !isUploader) {
        auditService.recordAsync(/* document.unshare, outcome=failure, reason=forbidden */);
        throw new ForbiddenException("Not allowed to unshare this document from this group");
    }

    documentGroupShareRepository.delete(share);
    auditService.recordAsync(/* document.unshare, outcome=success */);
}
```

### 4.4 Thông báo — ghi best-effort ngoài transaction (Story 4)

1. `NotificationService.notifyAsync(List<String> recipientSubs, String type,
   Map<String,Object> payload)` — `@Async` (Spring `@EnableAsync` đã có
   trong dự án? DEV kiểm tra; nếu chưa có thì thêm cấu hình thread pool tối
   giản, **không** dùng message broker — Quyết định 3 SA cấm thêm hạ tầng).
2. Bọc toàn bộ thân hàm trong try/catch `RuntimeException` → log lỗi, không
   throw ra ngoài — đảm bảo lỗi ghi thông báo không bao giờ ảnh hưởng
   luồng gọi (best-effort thật sự, không chỉ tách transaction).
3. Người nhận: với `document.shared_to_group` → toàn bộ `group_member` của
   nhóm **trừ** người thực hiện chia sẻ; với `group.member_added`/
   `group.member_removed` → chính người bị thêm/gỡ.
4. `GroupsService.addMember`/`removeMember`/`leaveGroup` (spec `-01`) gọi
   `notificationService.notifyAsync(...)` sau khi transaction chính commit
   (dùng `TransactionSynchronizationManager.registerSynchronization` hoặc
   gọi sau khi method `@Transactional` return — DEV chọn cách nào khớp
   pattern hiện có của `auditService.recordAsync` trong cùng service, vì
   `recordAsync` đã giải quyết vấn đề tương tự cho audit).

### 4.5 Đọc/đánh dấu thông báo

1. `GET /notifications`: query `notification WHERE recipient_sub =
   :currentUserSub ORDER BY created_at DESC` (thêm `AND read_at IS NULL`
   nếu `unreadOnly=true`).
2. `POST /notifications/{id}/read`: `UPDATE notification SET read_at =
   now() WHERE id = :id AND recipient_sub = :currentUserSub` — 0 dòng ảnh
   hưởng (không tồn tại hoặc không phải của mình) → `404`.
3. Job dọn TTL 90 ngày: `@Scheduled` chạy hằng ngày,
   `DELETE FROM notification WHERE created_at < now() - interval '90 days'`
   — đặt trong `NotificationService`, log số dòng đã xoá.

### 4.6 Frontend

1. Panel "Chia sẻ" (`web/src/app/documents/[id]`): gọi
   `GET /documents/{id}/shares` hiện có để hiển thị danh sách nhóm; nút
   "Chia sẻ thêm" mở chọn nhóm (multi-select) → gọi `POST
   /documents/{id}/shares` lặp theo từng nhóm (đơn giản hơn cho 1 tài liệu ×
   nhiều nhóm — xem câu hỏi mở BA) hoặc endpoint hàng loạt nếu DEV thấy phù
   hợp hơn.
2. Badge thông báo (`AppShell`): polling `GET /notifications?unreadOnly=true`
   mỗi 60 s (dùng `setInterval`/`SWR`/`react-query` — theo pattern polling
   đã dùng ở đâu đó trong `web/` nếu có, nếu chưa có thì dùng `setInterval`
   tối giản).
3. Tab "Tài liệu của nhóm" (`web/src/app/groups/[id]`): gọi `GET
   /groups/{id}/documents`, phân trang.

### 4.7 Chia sẻ hàng loạt từ trang danh sách tài liệu (Story 6 BA — mới)

Không cần API mới — dùng lại `POST /documents/shares` (mục 3.1/4.1). Chỉ
sửa `web/src/app/documents/page.tsx`:

1. Thêm `const [myGroups, setMyGroups] = useState<GroupRow[]>([])` + fetch
   `GET /groups` trong `useEffect` hiện có (cùng nhịp với fetch `folders`/
   `tags`).
2. Thêm state `shareGroupId` (giống pattern đã dùng ở
   `documents/[id]/page.tsx:57`) và một dropdown chọn nhóm, đặt trong thanh
   hành động hiện có (điều kiện hiển thị `selectedIds.size > 0`, cạnh nút
   "Xóa đã chọn").
3. Hành động "Chia sẻ vào nhóm" hiển thị khi
   `hasAnyRole(session, [Role.CAN_BO, Role.LANH_DAO])` — về logic thì mọi
   người vào được trang này đã thỏa điều kiện này (trang yêu cầu
   `canSeeFeature(session, 'documents')`), nhưng viết rõ điều kiện để không
   vô tình tái dùng biến `canDelete` (`hasRole(CAN_BO)` only, dùng cho
   xóa mềm) cho hành động chia sẻ — hai quyền khác nhau (Story 6 BA AC-2).
4. Nút bị disable nếu `selectedIds.size > 100` (Story 6 AC-6) — hiện tooltip/
   text "Tối đa 100 tài liệu/lượt".
5. Bấm nút → `useConfirm` xác nhận đơn giản: `` `Xác nhận chia sẻ
   ${selectedIds.size} tài liệu vào nhóm "${group.name}"?` `` (Story 6 AC-4 —
   không preview chi tiết, không gọi thêm API kiểm tra trước).
6. Xác nhận → `apiJson<BulkShareResponse>('/documents/shares', session,
   { method: 'POST', body: JSON.stringify({ groupId: shareGroupId,
   documentIds: [...selectedIds] }) })`.
7. Dựng câu tóm tắt từ `results[]` theo `status` (đếm `shared`/
   `already_shared`/`rejected`), theo đúng tinh thần
   `confirmAndTrash` đang dựng `message` từ `TrashPreviewItem[]` — ví dụ:
   `"Đã chia sẻ 8 tài liệu vào nhóm X. 1 tài liệu đã được chia sẻ từ
   trước. 1 tài liệu bị từ chối (vượt giới hạn 50 nhóm/tài liệu)."`
8. Sau khi xử lý xong (kể cả có item `rejected`), reset `selectedIds` và
   `shareGroupId` — khớp hành vi `confirmAndTrash` đang làm sau khi xóa
   (Story 6 AC-8).

## 5. Test plan

- **Unit test:**
  - `unshareDocument`: owner nhóm gỡ share người khác tạo → pass; member
    gỡ share người khác tạo (không phải uploader) → `ForbiddenException`;
    member gỡ share do chính mình tạo → pass; member gỡ share tài liệu do
    chính mình nạp (nhưng share do người khác tạo) → pass.
  - Chia sẻ hàng loạt: item vượt giới hạn 50 nhóm/tài liệu → `rejected` mà
    không throw, các item khác trong cùng request vẫn xử lý tiếp.
  - `NotificationService.notifyAsync`: repository ném exception → không
    exception nào thoát ra khỏi hàm (best-effort thật).
- **Integration test:**
  - Story 1: request 100 `documentId` gồm cả hợp lệ/không tồn tại/ngoài
    phạm vi/đã chia sẻ trước → response đúng trạng thái từng item, các item
    hợp lệ vẫn được lưu dù có item lỗi.
  - Story 2: thành viên nhóm gọi `GET /groups/{id}/documents` → đúng danh
    sách + phân trang; người ngoài nhóm → 403/404; tài liệu đã vào thùng
    rác (soft-delete) → biến mất khỏi danh sách nhưng `document_group_share`
    vẫn còn trong DB.
  - Story 3: 3 case quyền gỡ (owner/sharer/uploader) → 204; case còn lại
    (member thường, không phải sharer/uploader) → 403 + audit failure.
  - Story 4: chia sẻ 1 tài liệu vào nhóm 5 thành viên → 4 thông báo được
    tạo (trừ người chia sẻ) trong ≤ 60 s (test có thể gọi trực tiếp service
    đồng bộ thay vì chờ async thật); chia sẻ hàng loạt 10 tài liệu cùng lúc
    → chỉ **1** thông báo `document.shared_to_group`/người nhận (không phải
    10); lỗi giả lập ở `NotificationService` (mock throw) → thao tác chia
    sẻ vẫn `200`/`204` bình thường.
  - `POST /notifications/{id}/read` với `id` của người khác → 404 (không
    lộ thông tin thông báo người khác tồn tại).
- **Case biên/lỗi cần cover:**
  - Chia sẻ hàng loạt với danh sách rỗng → 400 (validation `@NotEmpty`).
  - Chia sẻ hàng loạt 101 tài liệu → 400 (validation `@Size(max=100)`).
  - Tài liệu bị **hard-delete** trong lúc đang hiển thị ở `GET
    /groups/{id}/documents` (race hiếm) — chấp nhận độ trễ tới lần load
    lại tiếp theo, không cần realtime (khớp NFR Availability SA, không có
    AC riêng).
  - TTL job chạy khi bảng `notification` rỗng — không lỗi.
  - Story 6 (frontend): chọn > 100 tài liệu trên `/documents` → nút "Chia
    sẻ vào nhóm" bị disable, không gọi API; chọn tài liệu gồm cả loại
    `rejected` (không nhìn thấy được/vượt giới hạn nhóm) và `shared` trong
    cùng lượt → tóm tắt kết quả hiển thị đúng cả 2 nhóm trạng thái, không
    chỉ báo "thành công" chung chung.

## 6. Task breakdown

- [x] Task 1: Migration `V24__document_sharing_notifications.sql` (bảng
      `notification`).
- [x] Task 2: Entity + repository `Notification`; `NotificationService`
      (`notifyAsync`, TTL job `@Scheduled`). Lưu ý: `Notification.payload`
      dùng `Map<String, Object>` + `@JdbcTypeCode(SqlTypes.JSON)` thay vì
      `String` như snippet ở mục 2 — khớp idiom `jsonb` đã dùng nhất quán ở
      `AuditLog.metadata`/`Document.scanResult`; một `String` trần không bind
      đúng vào cột `jsonb` với Hibernate của dự án này.
- [x] Task 3: `NotificationsController` (`GET /notifications`, `POST
      /notifications/{id}/read`).
- [x] Task 4: `BulkShareDto` + `GroupsService.shareDocumentsBulk` +
      endpoint `POST /documents/shares` — tách controller mới
      `DocumentBulkSharesController` (route không có `{id}` nên không gắn
      được vào `DocumentSharesController`).
- [x] Task 5: `GroupsService.listGroupDocuments` + `GET
      /groups/{id}/documents` trong `GroupsController`.
- [x] Task 6: **[SỬA — rà soát bảo mật]** `GroupsService.unshareDocument`
      thêm điều kiện quyền owner/sharer/uploader.
- [x] Task 7: Nối `addMember`/`removeMember`/`leaveGroup` (spec `-01`) và
      `shareDocumentsBulk` với `NotificationService.notifyAsync`.
- [x] Task 8: Unit + integration test theo mục 5 (`GroupsServiceTest`,
      `NotificationServiceTest`, `DocumentSharingIT`). IT dùng Testcontainers
      — compile OK, chưa chạy được trong môi trường không có Docker.
- [x] Task 9: Frontend — panel "Chia sẻ" trên trang tài liệu, badge thông
      báo trong `AppShell`, mục "Tài liệu của nhóm" trong `web/src/app/groups/[id]`
      (một section trong cùng trang, không tách UI tab riêng — khớp phong
      cách hiện có của trang, không có tab component sẵn trong `web/`).
- [x] Task 10: Cập nhật `docs/01-sa/architecture/group-management-sharing.md`
      mục "Out-of-scope — contract" nếu Task 4-5 đổi hình dạng response so
      với dự kiến ban đầu của SA — đối chiếu xong: response shape của Task
      4-5 khớp đúng §3.1/§3.2 đã chốt, mục "Out-of-scope — contract" không
      đề cập hình dạng response của 2 endpoint này nên không cần sửa.
- [x] Task 11 (mới, Story 6 BA): Frontend — hành động "Chia sẻ vào nhóm"
      hàng loạt trên `web/src/app/documents/page.tsx` (mục 4.7): tái dùng
      `selectedIds` đã có (tính năng xóa hàng loạt), gọi `POST
      /documents/shares` đã có (Task 4), tóm tắt kết quả theo `results[]`,
      giới hạn client-side 100 tài liệu/lượt, hiển thị cho cả CAN_BO và
      LANH_DAO. Không cần đổi backend/DB.

## 7. Rủi ro kỹ thuật

- **Sửa quyền gỡ chia sẻ (Task 6) là thay đổi hành vi bảo mật trên API đã
  Approved** — trước đây bất kỳ ai thấy được tài liệu đều gỡ được share
  bất kỳ; sau khi sửa, một số client/thao tác trước đây thành công có thể
  bắt đầu nhận `403`. Cần rà soát xem có UI/luồng nào đang phụ thuộc hành
  vi cũ trước khi merge (hiện tại UI chưa tồn tại — Task 9 làm sau Task 6
  nên nguy cơ thấp, nhưng phải đảm bảo thứ tự này).
- **Chia sẻ hàng loạt xử lý từng item 1 transaction riêng** — nếu vô tình
  bọc `@Transactional` ở method ngoài cùng thì 1 item lỗi sẽ rollback toàn
  bộ, vi phạm trực tiếp AC-2 Story 1; cần test tích hợp xác nhận rõ ràng
  (không chỉ tin vào code review).
- **Thông báo best-effort ngoài transaction** — nếu implement sai (ví dụ
  gọi trong cùng `@Transactional` rồi catch exception ở tầng service) có
  thể vẫn vô tình rollback transaction cha nếu exception là
  `RuntimeException` không được catch đúng chỗ (Spring mặc định rollback
  cho unchecked exception dù đã catch ở method khác trong cùng transaction
  nếu đánh dấu rollback-only) — ưu tiên tách hẳn ra ngoài transaction bằng
  `TransactionSynchronization`/gọi sau khi transaction commit, không chỉ
  try/catch đơn thuần.
- **`@Async`/`@Scheduled` cần `@EnableAsync`/`@EnableScheduling`** — kiểm
  tra cấu hình Spring Boot hiện có trước khi thêm, tránh thêm annotation
  trùng lặp hoặc xung đột với executor khác nếu dự án đã cấu hình sẵn cho
  mục đích khác (ví dụ `AuditService.recordAsync`).
