# Tech Spec: delete-restore-trash-01-xoa-mem-thung-rac

> File: `docs/03-dev/specs/delete-restore-trash-01-xoa-mem-thung-rac.md` — Owner: DEV
> Nguồn story: `docs/02-ba/stories/delete-restore-trash-01-xoa-mem-thung-rac.md`
> Status: Approved (2026-09-09)

> Phạm vi: Story 1 (xóa mềm), Story 2 (xem + phục hồi cơ bản, không xung
> đột), Story 3 (thống kê R3), Story 4 (badge dung lượng thùng rác). Xử lý
> xung đột chi tiết khi phục hồi (AC-1–AC-8 của Story 6) và job `trash-purge`
> nằm ở `docs/03-dev/specs/delete-restore-trash-02-xoa-cung-xung-dot.md`
> (file 02) — file này chỉ đặc tả đường "happy path" của phục hồi, file 02
> mở rộng cùng 2 endpoint `POST /trash/{id}/restore` và
> `POST /trash/restore-batch` với bảng mã lỗi xung đột đầy đủ.

## 0. Đã khảo sát code hiện có (trước khi viết spec)

- Migration mới nhất: **V24** (`V24__document_sharing_notifications.sql`).
  Hai slug khác đang chạy song song trong cùng phiên đã đặt trước **V25,
  V26** (`digitization-results-store.md`) và **V27**
  (`digitization-pipeline-02-ocr-stt-processing.md`) — xác nhận bằng grep
  `docs/03-dev/specs/*.md`. Slug này dùng tiếp **V28** (file này) và **V29**
  (file 02). Trước khi chạy migration thật, DEV phải grep lại
  `backend/src/main/resources/db/migration` một lần nữa — nếu một trong hai
  slug kia đã merge trước, số hiệu có thể cần dịch lên.
- `document.deleted_at` đã có từ **V9** (`V9__warehouse_structure_v2.sql`),
  hiện là "schema-only" — `Document.java` có comment xác nhận "no code path
  writes this yet". Cột này cùng migration cũng đổi
  `document.folder_id` sang `ON DELETE SET NULL` và tạo
  `IDX_document_folder_active ... WHERE deleted_at IS NULL`.
- **`ShedLock` chưa có** trong `backend/pom.xml` (grep
  `net.javacrumbs.shedlock` không ra kết quả) — xem Task 1 của file 02.
- **Lớp filter dùng chung** (Quyết định 1/2 SA) là
  `com.aismartstore.backend.groups.BusinessDataScope.forDocuments(...)` —
  **hiện chưa có `deletedAt IS NULL`** trong predicate của nó (chỉ có
  `uploaderSub = :sub OR id IN (share theo nhóm)`). Đây là chỗ **bắt buộc
  phải sửa** để AC-2 (Story 1) đúng — xem Task 3.
- `WarehouseCapacityService.getSnapshot()` (dùng cho Story 4 AC-1–AC-3) gọi
  `documentRepository.sumSizeByStatus(DocumentStatus.CLEAN)` — **cố ý không**
  filter `deleted_at` (đã có comment "trash still occupies real bytes").
  Badge "dung lượng thùng rác" ở Story 4 phải dùng **cùng nguồn** này (tổng
  `size` của `document` có `status='clean' AND deleted_at IS NOT NULL`), để
  khớp với AC-3 (không lệch số liệu giữa 2 màn hình).
- Chưa có bất kỳ `@Scheduled`/Quartz/ShedLock nào trong backend (đúng như SA
  ghi nhận).
- Không có module `search`/OpenSearch/indexer nào tồn tại trong code
  hiện tại (`search-fulltext.md`, `search-semantic.md` còn Draft, chưa có
  code). Nghĩa là AC-2 ("biến mất khỏi tìm kiếm ngay") ở giai đoạn này được
  đảm bảo **hoàn toàn** bởi việc thêm `deletedAt IS NULL` vào
  `BusinessDataScope` (mọi entrypoint đọc hiện có đều đi qua đây hoặc qua
  các query đã có sẵn `deleted_at IS NULL` như
  `DocumentRepository.findMissedForDigitization`). Sự kiện outbox
  `document.trash`/`document.restored` được phát ra **để sẵn** cho pha sau
  khi `ai-search-infra`/`search-*` có indexer thật — pha này chưa có gì tiêu
  thụ chúng, không có hậu kiểm OpenSearch cụ thể để viết test tích
  hợp; test hồi quy tập trung vào tầng DB/`BusinessDataScope`.

## 1. Tóm tắt kỹ thuật

- Thêm cột `deleted_by_sub`, `deleted_by_username`, `deleted_reason`,
  `purge_after`, `trash_batch_id` lên `document`; bảng mới `trash_batch`
  (V28).
- Thêm `deletedAt IS NULL` vào `BusinessDataScope.forDocuments(...)` — sửa
  **một chỗ duy nhất**, áp dụng ngay cho mọi entrypoint hiện có
  (`GET /documents`, `GET /documents/unclassified-doc-type`, mọi API tương
  lai dùng scope này) mà không cần sửa từng service.
- Module mới `com.aismartstore.backend.trash`: `TrashController`,
  `TrashService`, `TrashBatchRepository`; mở rộng
  `DocumentRepository`/`DocumentSpecifications` với các query lọc theo
  `deletedBySub`/`uploaderSub` cho thùng rác cá nhân.
  `DocumentsController`/`DocumentsService` hiện có được mở rộng thêm 2
  endpoint xóa mềm (`POST /documents/trash`,
  `POST /documents/trash-preview`) — đặt trong `documents/` package vì
  cùng entity `Document`, không tách package cho 2 endpoint này.
- Xóa mềm ghi `outbox_event` (`document.trash`) + `AuditService.record(...)`
  (đồng bộ, Quyết định 8 SA) trong cùng transaction.
- `WarehouseCapacityController`/`-Service` (hoặc `TrashService`, xem Task 9)
  bổ sung field `trashBytes` vào response hiện có của
  `GET /warehouse/capacity` cho Story 4 — **không** tạo endpoint/cơ chế tính
  dung lượng riêng.
- `GET /trash/stats` (R3) là aggregate query riêng, không tái dùng
  `WarehouseCapacityService` (khác nguồn phân trang theo hạn xóa).

## 2. Data model

### 2.1 `V28__trash_soft_delete.sql`

```sql
-- Xóa mềm (FR-26) - Quyết định 1 SA: cờ tại chỗ trên document, không tách bảng.
ALTER TABLE document
    ADD COLUMN deleted_by_sub      VARCHAR(255),
    ADD COLUMN deleted_by_username VARCHAR(255),
    ADD COLUMN deleted_reason      VARCHAR(500),
    ADD COLUMN purge_after         TIMESTAMPTZ;

-- Một lượt xóa nhiều tài liệu -> phục hồi lại được theo lô (Quyết định 1 SA).
CREATE TABLE trash_batch (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    actor_sub      VARCHAR(255) NOT NULL,
    actor_username VARCHAR(255) NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    item_count     INT          NOT NULL
);

ALTER TABLE document
    ADD COLUMN trash_batch_id UUID REFERENCES trash_batch (id);

-- Thùng rác cá nhân: liệt kê theo deleted_by_sub, mới nhất trước (Story 2 AC-1).
CREATE INDEX IDX_document_deleted_by_sub
    ON document (deleted_by_sub, deleted_at DESC)
    WHERE deleted_at IS NOT NULL;

-- "Tài liệu tôi nạp bị người khác xóa" (Story 2 AC-2, view=uploaded).
CREATE INDEX IDX_document_uploader_deleted
    ON document (uploader_sub, deleted_at DESC)
    WHERE deleted_at IS NOT NULL;

INSERT INTO permission (code, description) VALUES
    ('trash.manage', 'Xóa mềm tài liệu kho dùng chung; xem và phục hồi thùng rác cá nhân'),
    ('trash.stats.read', 'Xem thống kê tổng hợp thùng rác (không xem nội dung/tên tệp)');

INSERT INTO role_permission (role_code, permission_code) VALUES
    ('CAN_BO', 'trash.manage'),
    ('QUAN_TRI', 'trash.stats.read');
```

- `deleted_by_username` lưu kèm (theo đúng khuôn mẫu
  `uploader_sub`/`uploader_username` đã có trên `document`) để hiển thị UI
  không cần join thêm bảng account.
- `purge_after` **chốt cứng tại thời điểm xóa** = `deleted_at +
  retention.trash_retention_days` (đọc từ `SystemConfigService`, đã có sẵn
  pattern dùng ở `WarehouseCapacityService`) — không hồi tố khi R3 đổi tham
  số retention sau này (Quyết định 1 SA).
- Vai trò trong migration: `CAN_BO` = R1, `LANH_DAO` = R2, `QUAN_TRI` = R3.
  Không có role thứ 4 nào được seed trong `role` table hiện tại (grep
  `V12__init_groups.sql`) — "R4" trong ma trận SRS/story không map tới role
  nào trong hệ thống hiện có, không cần permission riêng cho nó.

### 2.2 Sửa `BusinessDataScope` (không phải migration, nhưng là thay đổi data-model-adjacent bắt buộc)

```java
// groups/BusinessDataScope.java — forDocuments(...)
Predicate notDeleted = cb.isNull(root.get("deletedAt"));
Predicate ownedByCurrentUser = cb.equal(root.get("uploaderSub"), currentUserSub);
...
return cb.and(notDeleted, cb.or(ownedByCurrentUser, sharedToCurrentUsersGroup));
```

Tương thích với `DocumentRepositoryImpl.search(spec, limit, offset)`,
`DocumentsService.list(...)`, `unclassifiedDocType(...)` — không đổi chữ ký,
chỉ đổi predicate. **Trash module KHÔNG dùng `BusinessDataScope`** (vì mục
đích ngược lại: chỉ hiện item đã xóa) — dùng specification/query riêng có
`deletedAt IS NOT NULL` (xem §3, §4).

### 2.3 `Document.java` — field mới

`deletedBySub`, `deletedByUsername`, `deletedReason`, `purgeAfter`,
`trashBatchId` (kiểu `UUID`, cột `trash_batch_id`, không map quan hệ JPA đầy
đủ tới `TrashBatch` — chỉ cần id để không kéo N+1 khi liệt kê).

## 3. API contract

Tất cả endpoint dưới đây yêu cầu `hasAuthority('PERM_trash.manage')` trừ khi
ghi chú khác. Base path tài liệu vẫn `/documents` (module hiện có); thùng
rác dùng path mới `/trash`.

| Method | Endpoint | Request | Response |
|---|---|---|---|
| POST | `/documents/trash` | `{ documentIds: UUID[] (1..500), reason?: string }` | 200 `{ batchId, itemCount, purgeAfter }` |
| POST | `/documents/trash-preview` | `{ documentIds: UUID[] (1..500) }` | 200 `[{ documentId, activeGroupShareCount }]` |
| GET | `/trash` | query `view=own\|uploaded` (default `own`), `limit`, `offset` | 200 `{ items: [{ documentId, originalName, deletedAt, purgeAfter, size, deletedBySub, deletedByUsername, uploaderSub, uploaderUsername }], total }` |
| GET | `/trash/{id}` | — | 200 chi tiết 1 item trong trash (xem §3.1) |
| POST | `/trash/{id}/restore` | `{ targetFolderId?: UUID, onNameConflict?: 'AUTO_RENAME'\|'CANCEL' }` | 200 `{ documentId, restoredFolderId, restoredName, groupsLostEffect: UUID[] }`; mã lỗi 4xx/409/410 xem file 02 §3 |
| POST | `/trash/restore-batch` | `{ documentIds: UUID[] (1..500), targetFolderId?: UUID, onNameConflict?: 'AUTO_RENAME'\|'CANCEL' }` | 200 `{ results: [{ documentId, status: 'RESTORED'\|'CONFLICT'\|'ERROR', code?, restoredFolderId?, groupsLostEffect? }] }` |
| GET | `/trash/stats` | — (`PERM_trash.stats.read`, R3) | 200 `{ totalItems, totalBytes, byExpiryBucket: [{ bucket, count, bytes }], unrecoverableAccounts: { count, nearestPurgeAfter } }` |
| GET | `/warehouse/capacity` (mở rộng, không phải endpoint mới) | — | thêm field `trashBytes` vào `WarehouseCapacityService.Snapshot` hiện có |

### 3.1 `GET /trash/{id}` — response

```json
{
  "documentId": "...",
  "originalName": "...",
  "size": 12345,
  "deletedAt": "...",
  "purgeAfter": "...",
  "deletedBySub": "...",
  "uploaderSub": "...",
  "folderId": null,
  "folderMissing": true,
  "suggestedFolderId": "... | null",
  "activeGroupShareCount": 2,
  "purgeState": "deleted"
}
```

`folderMissing`/`suggestedFolderId`/`purgeState` liên quan trực tiếp tới xử
lý xung đột phục hồi — đặc tả cơ chế tính ở file 02 §2/§4 (dựa trên
`deleted_folder_path_ids`, cột thêm ở V29). File này chỉ định nghĩa hình
dạng response.

### 3.2 Quy tắc chung cho `POST /documents/trash` (batch)

- Toàn bộ `documentIds` được kiểm tra **trước** khi mở transaction: mỗi id
  phải (a) tồn tại, (b) đang active (`deleted_at IS NULL`), (c) nằm trong
  phạm vi FR-08 của người gọi (`uploaderSub = self OR id IN (share theo
  nhóm của self)` — dùng lại đúng logic `BusinessDataScope.forDocuments`,
  **trước khi** thêm điều kiện `deletedAt IS NULL`, để không tự mâu thuẫn).
- Nếu **bất kỳ** id nào không thỏa (a)/(b)/(c) → từ chối **toàn bộ request**
  (400/403/404 tùy loại lỗi đầu tiên gặp, kèm danh sách id lỗi), ghi
  `document.trash.denied` cho các id thuộc nhóm (c) bị từ chối. Lý do chọn
  "tất cả hoặc không" thay vì lọc âm thầm: khớp nghĩa đen AC-8 ("thực hiện
  trong 1 transaction") và tránh người dùng tưởng đã xóa hết nhưng thực ra
  thiếu vài file mà không để ý.
- Nếu tất cả hợp lệ: 1 transaction — với mỗi document: `deletedAt = now()`,
  `deletedBySub/Username`, `deletedReason`, `purgeAfter = now() +
  retention`, `trashBatchId = batch.id`; ghi 1 `trash_batch` row
  (`itemCount = documentIds.size()`); ghi 1 `outbox_event`
  (`document.trash`, payload có `documentIds` — 1 event cho cả lô, không
  phải N event, để không làm phình bảng outbox khi xóa 500 tài liệu) trong
  cùng transaction; ghi 1 `AuditService.record(...)` per document (audit
  log vốn ghi theo `resourceId` = 1 tài liệu, không gộp được — chấp nhận N
  bản ghi audit cho N tài liệu, đây là chi phí cố định của mô hình audit
  hiện có, không phải hồi quy mới).

## 4. Luồng xử lý chính

### 4.1 Xóa mềm (`TrashService.trash(user, documentIds, reason)`)

1. Validate size `1..500` (400 nếu vượt, khớp `ingest.max_files_per_batch`
   theo NFR).
2. Bulk-load các `Document` theo id (`documentRepository.findAllById`),
   kiểm tra tồn tại + `deletedAt == null`.
3. Với mỗi document, kiểm tra scope FR-08 bằng `BusinessDataScope` (tái sử
   dụng field `memberGroupIds` đã resolve 1 lần cho cả batch, tránh N truy
   vấn group membership).
4. Nếu có vi phạm ở bước 2/3 → dừng, không side-effect, trả lỗi (xem §3.2).
5. `@Transactional`: update từng `Document`, insert `TrashBatch`, insert 1
   `OutboxEvent` (`document.trash`), gọi `auditService.record(...)` cho
   từng document (`action=document.trash`, `outcome=success`,
   `resourceType=document`, `resourceId=documentId`, `metadata` gồm
   `folderId`, `batchId`, `groupIds` đang có share hiệu lực tại thời điểm
   xóa, `purgeAfter`).
6. Trả `{ batchId, itemCount, purgeAfter }`.

### 4.2 Xem thùng rác (`GET /trash`)

- `view=own`: `WHERE deleted_by_sub = :self ORDER BY deleted_at DESC` (dùng
  `IDX_document_deleted_by_sub`).
- `view=uploaded`: `WHERE uploader_sub = :self AND deleted_by_sub != :self
  AND deleted_at IS NOT NULL` (dùng `IDX_document_uploader_deleted`) — trả
  lời câu hỏi mở của BA: **1 API, phân biệt bằng query param `view`**, vì cả
  hai chỉ khác `WHERE`, dùng chung cấu trúc phân trang/response; tách 2 API
  sẽ trùng lặp code không cần thiết. FE hiển thị 2 tab, gọi cùng 1 endpoint
  với `view` khác nhau.
- Phân trang bằng `limit`/`offset` giống `DocumentRepositoryCustom.search`
  (không dùng `Pageable` để nhất quán với phần còn lại của `documents/`).

### 4.3 Phục hồi cơ bản, không xung đột (`POST /trash/{id}/restore`)

(File 02 mở rộng đầy đủ 8 case xung đột; ở đây chỉ đặc tả nhánh thành công.)

1. Load document theo id; 404 nếu không tồn tại hoặc `deletedAt == null`.
2. Kiểm tra quyền: `deletedBySub == self OR uploaderSub == self` (Quyết
   định 6 SA) → 403 nếu không thỏa.
3. (File 02) Kiểm tra `purgeState`/`purgeAfter`/`folderId`/tên trùng — nếu
   không có xung đột nào, tiếp tục bước 4.
4. `@Transactional`: `deletedAt = NULL`, `deletedBySub/Username = NULL`,
   `deletedReason = NULL`, `purgeAfter = NULL`, `trashBatchId` giữ nguyên
   (lịch sử, không dọn); ghi 1 `OutboxEvent` (`document.restored`); tính
   `groupsLostEffect` = các `group_id` trong `document_group_share` của tài
   liệu này mà `group.deletedAt IS NOT NULL` (query nhỏ, chỉ để trả về
   warning — hiệu lực share tự động đúng nhờ `BusinessDataScope`/
   `GroupMemberRepository.findGroupIdsByUserSub` đã có sẵn điều kiện
   `g.deletedAt IS NULL`, **không cần code riêng để "ẩn" share tới nhóm đã
   xóa** — chỉ cần code để **liệt kê cảnh báo**); gọi
   `auditService.record(...)` (`action=document.restore`).
5. Trả `{ documentId, restoredFolderId, restoredName, groupsLostEffect }`.

### 4.4 Badge dung lượng thùng rác (Story 4)

- `WarehouseCapacityService.Snapshot` thêm field `trashBytes`:
  `documentRepository.sumSizeByStatus(...)` hiện tính tổng mọi `clean`
  không phân biệt rác — thêm 1 query mới
  `sumSizeByStatusAndDeletedAtNotNull(String status)` để tách riêng phần
  rác (dùng cùng bảng, cùng `status='clean'`, khác điều kiện
  `deleted_at`). `usedBytes` ở snapshot hiện tại **không đổi** (đã bao gồm
  rác theo V10, giữ nguyên hành vi cảnh báo 85%/95%) — `trashBytes` chỉ là
  một field hiển thị thêm để UI tách riêng, không ảnh hưởng công thức tính
  `usedPercent`/`status` hiện có.

### 4.5 `unrecoverableAccounts` trong `GET /trash/stats` (Story 3 – AC-4, bổ sung 2026-09-09)

- Query: `SELECT count(*), min(purge_after) FROM document d WHERE
  d.deleted_at IS NOT NULL AND d.purge_state = 'deleted' AND EXISTS (SELECT
  1 FROM account a WHERE a.sub = d.deleted_by_sub AND a.status != 'active')
  AND EXISTS (SELECT 1 FROM account a WHERE a.sub = d.uploader_sub AND
  a.status != 'active')` — cả 2 điều kiện `EXISTS` phải đúng (Quyết định 6
  SA: chỉ "mồ côi" khi **không còn ai** trong 2 người đủ quyền phục hồi).
- Dùng bảng `account` từ `account-lifecycle.md` (Approved) — join theo
  `sub`, không cần thêm cột nào lên `document`.
- Không trả `documentId`/`originalName`/`deletedBySub`/`uploaderSub` — chỉ
  2 số (`count`, `nearestPurgeAfter`), giữ đúng NFR-04 giống các field khác
  của `/trash/stats`.
- Đây là aggregate query riêng (không tái dùng `BusinessDataScope`/
  `TrashRepository` đã có) — thêm 1 method mới
  `DocumentRepository.countUnrecoverableInTrash()`.

## 5. Test plan

- **Unit — `BusinessDataScope`:** sau khi thêm `deletedAt IS NULL`, mọi test
  hiện có của `rbac-group-scope.md` phải xanh; thêm test mới: tài liệu đã
  xóa mềm không xuất hiện trong `forDocuments(...)` dù `uploaderSub` hoặc
  share nhóm khớp.
- **Integration — hồi quy "biến mất khỏi tìm kiếm/tra cứu":** xóa mềm 1 tài
  liệu, gọi `GET /documents` (folder cũ), `GET
  /documents/unclassified-doc-type` bằng cả người nạp và thành viên nhóm
  được chia sẻ → tài liệu không còn trong kết quả ngay sau commit (không có
  độ trễ, vì đây là truy vấn DB trực tiếp).
- **Unit — `TrashService.trash`:** batch có 1 id ngoài phạm vi FR-08 → toàn
  bộ batch bị từ chối, không document nào bị đổi `deletedAt` (rollback
  đúng); batch 500 id hợp lệ → 1 `trash_batch`, 1 `outbox_event`, N
  `audit_log`.
- **Integration — AC-4 Quyết định 3 SA:** xóa mềm tài liệu có
  `document_group_share` → share **không bị xóa** (`SELECT COUNT(*) FROM
  document_group_share WHERE document_id = ...` không đổi trước/sau); phục
  hồi → tài liệu xuất hiện lại đúng danh sách nhóm cũ mà không cần
  gọi lại API share.
- **Unit — quyền phục hồi (Quyết định 6):** `deletedBySub` khác `self` và
  `uploaderSub` khác `self` → 403; `uploaderSub == self` dù `deletedBySub`
  là người khác → cho phép (Story 2 AC-2).
- **Integration — Story 3 (R3 stats):** gọi `GET /trash/stats` bằng R1 →
  403 (thiếu `PERM_trash.stats.read`); response của R3 không chứa field
  `originalName`/`deletedBySub` ở bất kỳ cấp nào (test assert bằng cách
  parse JSON và kiểm tra whitelist key).
- **Integration — Story 3 AC-4 (`unrecoverableAccounts`):** item có
  `deleted_by_sub` disabled nhưng `uploader_sub` còn `active` → **không**
  tính vào `count` (còn người phục hồi được); cả 2 đều `locked`/`disabled`
  → tính vào `count`, `nearestPurgeAfter` đúng giá trị nhỏ nhất trong nhóm
  đó.
- **Integration — Story 4:** `trashBytes` từ `GET /warehouse/capacity`
  bằng đúng `SUM(size) WHERE status='clean' AND deleted_at IS NOT NULL`;
  `usedBytes`/`usedPercent`/ngưỡng 85%/95% không đổi so với trước khi thêm
  field.
- **Case biên:** `documentIds` rỗng → 400; `documentIds` > 500 → 400; xóa
  tài liệu đã bị xóa trước đó (double trash) → 409/404 tùy thiết kế lỗi
  chung của API (dùng lại `ApiException` pattern có sẵn).

## 6. Task breakdown

- [x] Task 1: Viết `V29__trash_soft_delete.sql` (§2.1) — **renumbered from
      V28 to V29**: by implementation time, `V28` had already been claimed
      by `V28__digitization_review_queue_and_permissions.sql` (a 3rd
      in-flight slug, not one of the 2 the spec's §0 anticipated). Re-grepped
      `backend/src/main/resources/db/migration` per §0's instruction before
      writing the real migration. File 02 must use **V30**, not V29, when it
      is implemented. Cập nhật `Document.java` với field mới (§2.3) - done.
- [x] Task 2: Sửa `BusinessDataScope.forDocuments(...)` thêm `deletedAt IS
      NULL` (§2.2); toàn bộ test suite hiện có của `rbac-group-scope.md`/
      `classification-notes-*` vẫn xanh (không hồi quy).
- [x] Task 3: Tạo package `trash/` — `TrashController`, `TrashService`,
      specification lọc theo `deletedBySub`/`uploaderSub` trong
      `DocumentSpecifications` (không có `TrashRepository` riêng - dùng
      `DocumentRepository` mở rộng, xem §3, §4.1–§4.3).
- [x] Task 4: Thêm `POST /documents/trash`, `POST
      /documents/trash-preview` vào `DocumentsController` (§3.2, §4.1) -
      logic thực tế nằm ở `TrashService.trash`/`preview` (được gọi trực
      tiếp từ `DocumentsController`, không qua `DocumentsService`, vì spec
      §4.1 đã đặt tên phương thức ở `TrashService`).
- [x] Task 5: `RolePermissionSeedTest` cập nhật để cover `trash.manage`/
      `trash.stats.read`; cũng cập nhật `RolePermissionFixtures` (test
      support dùng bởi mọi `*ControllerIT`) và `RbacControllerIT`'s permission
      count (19 → 21) - cả 2 phụ thuộc trực tiếp vào 2 permission mới.
- [x] Task 6: `GET /trash/stats` — query aggregate theo bucket hạn xóa,
      permission `trash.stats.read`, kiểm tra không rò rỉ field nghiệp vụ
      (§3, §5); bao gồm `unrecoverableAccounts` (§4.5). **Lệch nhỏ so với
      §4.5**: bỏ điều kiện `d.purge_state = 'deleted'` khỏi query - cột
      `purge_state` chưa tồn tại ở migration này (thuộc V30/file 02, cùng lý
      do như Task 9 dưới); mọi item trong thùng rác ở giai đoạn này coi như
      đã "deleted" (chưa có sub-state `purging` vì chưa có job xóa cứng).
- [x] Task 7: Mở rộng `WarehouseCapacityService.Snapshot` với `trashBytes`
      (§4.4); thêm `DocumentRepository.sumSizeByStatusAndDeletedAtIsNotNull`.
- [x] Task 8: Test ở §5 - unit (`TrashServiceTest`, 11 case) + integration
      (`TrashControllerIT`, 12 case, Testcontainers) + bổ sung vào
      `BusinessDataScopeIT`/`WarehouseCapacityServiceTest`/
      `WarehouseControllerIT` hiện có. Không viết riêng test cho "case biên
      > 500 phần tử" ở tầng HTTP ngoài `TrashControllerIT`
      (`trashRejectsEmptyOrOversizedDocumentIdsWith400`) + unit
      (`TrashServiceTest`).
- [x] Task 9 (phối hợp file 02): các trường `folderMissing`/
      `suggestedFolderId`/`purgeState` trong `GET /trash/{id}` (§3.1) trả về
      `null`/`null`/`"active"` cố định cho tới khi V30 (file 02) chạy, đúng
      như ghi chú.

## 7. Rủi ro kỹ thuật

- Sửa `BusinessDataScope` là thay đổi **dùng chung**, ảnh hưởng mọi
  entrypoint hiện có (`documents`, `classification-notes`, tương lai là
  `search-*`) — rủi ro hồi quy diện rộng nếu một nơi nào đó đang ngầm dựa
  vào việc thấy được tài liệu đã xóa (chưa phát hiện trường hợp nào khi
  khảo sát, nhưng cần chạy full regression suite, không chỉ test của slug
  này).
- **Đã xác nhận (2026-09-09):** giữ "tất cả hoặc không" cho batch xóa mềm
  (§3.2) — không đổi sang "lọc âm thầm + báo cáo". Quyết định này ban đầu
  do DEV tự chọn (SA/BA không chốt hành vi), đã được xác nhận chính thức;
  không cần xác nhận lại khi hoàn thiện UI trừ khi phát sinh phản hồi UX
  tiêu cực sau go-live.
- `groupsLostEffect` ở phục hồi chỉ tính nhóm đã `deleted_at IS NOT NULL`
  tại thời điểm phục hồi — không cảnh báo nhóm mà người dùng hiện tại
  không còn là thành viên (khác với "nhóm đã xóa"); đây là đúng phạm vi
  AC-6 Story 6 (chỉ nói về `group.deleted_at`), không mở rộng thêm.
- Trường `trashBytes` là aggregate query mỗi lần gọi (giống
  `usedBytes` hiện tại) — nếu thùng rác tích lũy rất lớn (gần 1 năm dữ
  liệu), chi phí query tăng tuyến tính; chấp nhận theo đúng tradeoff đã ghi
  nhận ở `warehouse-structure.md` cho `usedBytes`.
