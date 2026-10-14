# Tech Spec: delete-restore-trash-02-xoa-cung-xung-dot

> File: `docs/03-dev/specs/delete-restore-trash-02-xoa-cung-xung-dot.md` — Owner: DEV
> Nguồn story: `docs/02-ba/stories/delete-restore-trash-02-xoa-cung-xung-dot.md`
> Status: Approved (2026-09-09)

> Phạm vi: Story 5 (job `trash-purge`) và Story 6 (8 case xung đột khi phục
> hồi). Data model/API cơ bản của xóa mềm + phục hồi không xung đột nằm ở
> `docs/03-dev/specs/delete-restore-trash-01-xoa-mem-thung-rac.md` (file
> 01) — file này **mở rộng** cùng 2 endpoint `POST /trash/{id}/restore` và
> `POST /trash/restore-batch` đã khai báo ở file 01 §3, không tạo endpoint
> mới cho phục hồi.

## 0. Đã khảo sát code hiện có

- **Cập nhật sau khi file 01 implement xong (2026-09-09):** file 01 dự tính
  dùng V28 nhưng thực tế **đã đổi sang V29** — một slug thứ 3
  (`V28__digitization_review_queue_and_permissions.sql`) không được khảo
  sát trong §0 gốc đã chiếm V28 trước. **File này (02) phải dùng V30**,
  không phải V29 như dự tính ban đầu. Vẫn **grep lại**
  `backend/src/main/resources/db/migration` ngay trước khi tạo file thật —
  số hiệu có thể tiếp tục dịch lên nếu có slug khác merge trước.
- **`ShedLock` chưa có** trong `backend/pom.xml` (`net.javacrumbs.shedlock`
  — không match) — phải thêm dependency mới (Task 1).
- **Chưa có `@Scheduled`/scheduler nào** trong backend — job `trash-purge`
  là scheduler đầu tiên của hệ thống này; cần bật
  `@EnableScheduling`/`@EnableSchedulerLock` ở lớp cấu hình chính (chưa có
  sẵn, phải thêm).
- `ObjectStorageService.deleteObject(String key, String bucket)` (overload
  2 tham số) đã tồn tại và **không** bị khoá theo bucket ở tầng kỹ thuật —
  chỉ có comment "Never call this against the warehouse bucket: originals
  there are immutable (NFR-09)". Quyết định 5 SA yêu cầu "một phương thức
  riêng, chỉ gọi được từ job purge, có audit" — Task 6 bọc thêm một
  phương thức mới thay vì gọi thẳng overload có sẵn, để giữ nguyên ý nghĩa
  "immutable" cho mọi caller khác và có 1 điểm audit duy nhất.
- `document.folder_id` có `ON DELETE SET NULL` (V9);
  `warehouse_folder.parent_id` có `ON DELETE RESTRICT` và **chỉ cho xoá
  thư mục lá, rỗng dữ liệu active** —
  `WarehouseService.deleteFolder(id)` (backend/src/main/java/.../warehouse/
  WarehouseService.java dòng ~187) đã kiểm tra `existsByParentId` (chặn nếu
  còn thư mục con) và `existsByFolderIdAndStatusAndDeletedAtIsNull` (chặn
  nếu còn tài liệu active) — tài liệu **đã xóa mềm** không chặn xóa thư
  mục, đúng kịch bản AC-1 Story 6 ("thư mục cha đã bị xóa" trong khi tài
  liệu đang nằm trong thùng rác).
- `WarehouseFolder` đã có sẵn cột **`path_ids UUID[]`** (materialized path
  theo tổ tiên, set ở `WarehouseService.createFolder`/`moveFolder`) — đây
  là dữ liệu có sẵn dùng để trả lời câu hỏi mở "cơ chế gợi ý thư mục gần
  nhất còn tồn tại" (§2.2, §4.3), **không cần** parse lại `relativePath`
  dạng chuỗi.
- FK cascade hiện có: `document_tag`, `document_group_share`,
  `document_note` đều `ON DELETE CASCADE` theo `document_id` — nhưng vì mô
  hình "bia mộ" (Quyết định 5 SA) **không xóa row `document`**, các FK này
  **không tự cascade**; job purge phải tự `DELETE` các bảng phái sinh này
  (và `document_group_share`) bằng tay ở bước 2. `digitization_source`/
  `source_separation_job` (V18) hiện **không có `ON DELETE` action** trên
  `document_id` — không ảnh hưởng vì document row vẫn còn, nhưng job vẫn
  phải xóa các row này theo đúng AC-1 Story 5 ("xóa dữ liệu phái sinh liên
  quan").
- `GroupMemberRepository.findGroupIdsByUserSub` **đã có sẵn**
  `g.deletedAt IS NULL` trong query — nghĩa là AC-6 Story 6 ("nhóm đã xóa tự
  mất hiệu lực qua lớp filter, không cần xử lý riêng") **đã đúng theo code
  hiện tại**, không cần sửa gì thêm ở tầng scope; việc còn lại chỉ là tính
  danh sách cảnh báo hiển thị (đã đặc tả ở file 01 §4.3).
- `DocumentStatus` hiện có 4 giá trị (`pending/clean/infected/rejected`,
  cột `VARCHAR(16)`) — cần thêm `PURGED = "purged"` cho dòng bia mộ (Task
  7); giá trị mới không dài quá 16 ký tự, không cần đổi kiểu cột.

## 1. Tóm tắt kỹ thuật

- Thêm ShedLock (`shedlock-spring` + `shedlock-provider-jdbc-template`),
  bảng `shedlock` (V30), bật `@EnableSchedulerLock`.
- Thêm `purge_state`, `purged_at`, `deleted_folder_path_ids` lên `document`
  (V30).
- Module mới `com.aismartstore.backend.trash.purge`:
  `TrashPurgeJob` (`@Scheduled` cron, đọc giờ chạy + gate từ
  `system-config`/`backup_run` theo `backup-restore.md` Quyết định 3),
  `TrashPurgeService` (logic 5 bước theo Quyết định 5 SA, transaction ngắn
  theo từng document trong lô), `PurgeStuckRecoveryJob` (đưa `purging` kẹt
  quá hạn về `deleted`).
- `WarehouseService.deleteFolder(...)` được thêm 1 hook nhỏ: trước khi
  `repository.deleteById(id)`, snapshot `path_ids ++ [id]` của thư mục sắp
  xóa vào `document.deleted_folder_path_ids` cho mọi document đang có
  `folder_id = id` (bất kể trạng thái) — phục vụ gợi ý thư mục gần nhất khi
  phục hồi (§2.2, §4.3). Đây là thay đổi chạm vào domain
  `warehouse-structure.md` — DEV cần đối chiếu lại spec đó khi implement để
  không phá hành vi Story 3 hiện có của nó.
- `TrashService.restore(...)` (file 01) được bổ sung bảng kiểm tra xung đột
  đầy đủ 8 case (Quyết định 7 SA) trước khi thực hiện đảo cờ.

## 2. Data model

### 2.1 `V30__trash_purge_and_conflict.sql`

```sql
-- Máy trạng thái xóa cứng (Quyết định 5 SA): deleted -> purging -> purged.
-- 'active' = giá trị mặc định cho tài liệu chưa từng vào thùng rác.
ALTER TABLE document
    ADD COLUMN purge_state VARCHAR(16) NOT NULL DEFAULT 'active',
    ADD COLUMN purged_at   TIMESTAMPTZ,
    ADD COLUMN deleted_folder_path_ids UUID[];

UPDATE document SET purge_state = 'deleted' WHERE deleted_at IS NOT NULL;

ALTER TABLE document
    ADD CONSTRAINT CK_document_purge_state
    CHECK (purge_state IN ('active', 'deleted', 'purging', 'purged'));

-- Job purge quét theo (purge_state, purge_after); WHERE thu hẹp bằng
-- deleted_at IS NOT NULL để index nhỏ, chỉ chứa các dòng từng vào thùng rác.
CREATE INDEX IDX_document_purge_state_after
    ON document (purge_state, purge_after)
    WHERE deleted_at IS NOT NULL;

-- Bảng khóa chuẩn của ShedLock (JdbcTemplateLockProvider mặc định).
CREATE TABLE shedlock (
    name       VARCHAR(64) PRIMARY KEY,
    lock_until TIMESTAMPTZ NOT NULL,
    locked_at  TIMESTAMPTZ NOT NULL,
    locked_by  VARCHAR(255) NOT NULL
);
```

- `deleted_folder_path_ids`: mảng `UUID` = `path_ids` của thư mục bị xóa
  cộng thêm chính id thư mục đó, theo thứ tự gốc → lá (giống ngữ nghĩa
  `WarehouseFolder.pathIds`). `NULL` nếu tài liệu chưa từng bị ảnh hưởng
  bởi việc xóa thư mục (đa số trường hợp) — không phải cột bắt buộc.
- **Giới hạn đã biết:** nếu thư mục bị xóa **trước khi** migration này chạy
  (dữ liệu lịch sử), cột này sẽ `NULL` cho các document đã có `folder_id =
  NULL` từ trước — AC-1 Story 6 vẫn đúng (chặn phục hồi "về chỗ cũ", bắt
  chọn `targetFolderId`), chỉ là không có gợi ý thư mục gần nhất (UI fallback
  về thư mục gốc của kho). Ghi vào rủi ro §7, không coi là bug.

### 2.2 Bia mộ (`TrashPurgeService`, bước 4 của Quyết định 5)

Sau khi xóa dữ liệu phái sinh + object, `UPDATE document SET`:

| Cột | Giá trị sau purge | Lý do |
|---|---|---|
| `id`, `sha256`, `original_name`, `deleted_at`, `purged_at` | **giữ nguyên** | bia mộ tối thiểu (AC-3 Story 5), audit `resource_id` vẫn tra được |
| `purge_state` | `'purged'` | đánh dấu đã xử lý xong |
| `status` | `'purged'` (giá trị mới, `DocumentStatus.PURGED`) | tách khỏi mọi query lọc theo `status='clean'` sẵn có (capacity, folder-empty check...) mà không cần sửa các query đó |
| `mime_type`, `uploader_sub`, `uploader_username` | `''` (rỗng hóa, cột `NOT NULL` giữ nguyên ràng buộc) | không tạo migration đổi `NOT NULL`, tránh rủi ro không cần thiết |
| `size` | `0` | dung lượng giải phóng khỏi `sumSizeByStatus` (chỉ đếm `status='clean'`, `purged` không còn được đếm — dung lượng thùng rác/kho giảm đúng sau purge) |
| `storage_key`, `scan_result`, `relative_path` | `NULL`/`''`/`NULL` | không còn object, không còn cần path |
| `folder_id` | `NULL` | không còn thuộc cây thư mục |
| `deleted_by_sub`, `deleted_by_username`, `deleted_reason`, `purge_after`, `trash_batch_id`, `deleted_folder_path_ids` | `NULL` | không còn ý nghĩa sau khi đã purge; danh tính người xóa vẫn tra được qua `audit_log` (`document.trash`/`document.purge`), không cần giữ trùng ở `document` |

Vì `status` đổi thành `'purged'` (không còn là `'clean'`), mọi query hiện
có lọc `status = DocumentStatus.CLEAN` (capacity, folder-empty check,
`findFirstByFolderIdAndRelativePathAndOriginalNameAndStatusAndDeletedAtIsNull`)
tự động loại trừ dòng bia mộ **mà không cần sửa thêm chỗ nào khác** ngoài
Task 7.

## 3. API contract

Mở rộng 2 endpoint đã khai báo ở file 01 §3 — không có endpoint HTTP mới
cho job (chạy nội bộ qua `@Scheduled`, không có trigger thủ công ở MVP theo
AC-7 Story 5).

| Method | Endpoint | Mở rộng | Mã lỗi thêm |
|---|---|---|---|
| POST | `/trash/{id}/restore` | nhận thêm `targetFolderId`, `onNameConflict` (đã khai ở file 01) | 409 `FOLDER_MISSING`, 409 `NAME_CONFLICT`, 409 `PURGING`, 410 `GONE` |
| POST | `/trash/restore-batch` | mỗi phần tử `results[]` có thể có `status='CONFLICT'` với `code` tương ứng | cùng bộ mã ở trên, theo từng item |

### 3.1 Bảng mã xung đột (nguồn: Quyết định 7 SA, AC-1–AC-8 Story 6)

| Case | Điều kiện phát hiện | HTTP/`code` | Payload thêm |
|---|---|---|---|
| AC-1 Thư mục cha đã xóa | `document.folder_id IS NULL` và request không có `targetFolderId` | 409 `FOLDER_MISSING` | `suggestedFolderId` (từ `deleted_folder_path_ids`, §4.3) |
| AC-2 Trùng tên tại đích | Tồn tại document khác `folder_id = đích, relative_path, original_name, status='clean', deleted_at IS NULL` | 409 `NAME_CONFLICT` | `suggestedName` = `"<tên> (khôi phục yyyyMMdd-HHmm)"` |
| AC-3 Kho đầy ≥95% | — | **không kiểm tra** (không gọi `WarehouseCapacityService.assertIngestAllowed()` trong luồng restore) | — |
| AC-4 Hạn mức cá nhân | — | **không áp dụng** — restore không gọi bất kỳ code path nào của `personal-storage-quota.md` | — |
| AC-5 Người nạp/xóa bị vô hiệu hóa | — | **không kiểm tra** trạng thái active của account trong luồng restore | — |
| AC-6 Nhóm chia sẻ đã xóa | `document_group_share` có `group.deleted_at IS NOT NULL` | không chặn — trả về `groupsLostEffect` trong response 200 (file 01 §4.3) | — |
| AC-7 Đã quá hạn/đã xóa cứng | `purge_state = 'purged' OR (purge_state='deleted' AND purge_after <= now())` | 410 `GONE` | thông điệp hướng dẫn liên hệ R3 (backup-restore) |
| AC-8 Đang `purging` | `purge_state = 'purging'` | 409 `PURGING` | — |

Thứ tự kiểm tra trong `TrashService.restore(...)` (dừng ở điều kiện đầu
tiên khớp): tồn tại & đã bị xóa mềm (404 nếu không) → quyền (403) →
`purging` (409) → quá hạn/đã purge (410) → thư mục thiếu & không có
`targetFolderId` (409) → `targetFolderId` không tồn tại (400) → trùng tên &
`onNameConflict` không phải `AUTO_RENAME` (409) → thực hiện phục hồi
(200, kèm `groupsLostEffect` nếu có).

## 4. Luồng xử lý chính

### 4.1 Job `trash-purge` — lịch chạy & gate (đồng bộ với `backup-restore.md`)

Theo `docs/01-sa/architecture/backup-restore.md` Quyết định 3 (đã chốt,
trả lời đúng câu hỏi mở của SA slug này):

- Cron **giữ nguyên 03:00** (`@Scheduled(cron = "0 0 3 * * *")`), sau
  `@SchedulerLock(name = "trash-purge", lockAtMostFor = ...)`.
- **Gate bắt buộc** trước khi xử lý bất kỳ lô nào: chỉ chạy nếu tồn tại
  `backup_run` của **ngày hôm nay** với `consistency_point_at IS NOT NULL`
  và `status IN ('REPLICATING','VERIFIED')` **và** `window_open = false`
  (đọc tham số `backup.purge_gate_enabled` từ `system_config`, mặc định
  `true`). Không thỏa → **bỏ lượt**, ghi audit `trash.purge.skipped`,
  cảnh báo R3, thử lại ở lượt kế tiếp (không lùi giờ, chỉ đơn giản không
  làm gì hôm đó).
- Việc đọc `backup_run`/`window_open` phụ thuộc bảng/entity của slug
  `backup-restore` (Approved 2026-09-09; implement chi tiết bảng
  `backup_run` không thuộc phạm vi slug này, chỉ dẫn chiếu) — **đã chốt thứ
  tự triển khai (§7): `backup-restore` code trước**, nên `TrashPurgeJob`
  code thẳng theo gate thật ở trên. Nếu vì lý do vận hành nào đó
  `delete-restore-trash` vẫn bị code trước (lệch kế hoạch), gate vẫn phải
  **fail-closed** khi bảng `backup_run` chưa tồn tại (coi như không thỏa,
  bỏ lượt) như một an toàn dự phòng, không phải hành vi mong đợi.
- Không giữ transaction dài; xử lý theo lô 200 (`LIMIT 200`), có
  `Thread.sleep`/tách vòng lặp nhỏ nghỉ giữa lô (tham số cấu hình, mặc định
  vài trăm ms) để không chiếm hết connection pool.

### 4.2 5 bước xử lý mỗi lô (Quyết định 5 SA)

1. **Chọn & khóa lô:** trong 1 transaction ngắn,
   `SELECT id FROM document WHERE deleted_at IS NOT NULL AND purge_after
   <= now() AND purge_state = 'deleted' ORDER BY purge_after LIMIT 200 FOR
   UPDATE SKIP LOCKED`, rồi `UPDATE ... SET purge_state = 'purging'` ngay,
   commit. `FOR UPDATE SKIP LOCKED` là lớp bảo vệ **thứ hai** (ShedLock đã
   đảm bảo chỉ 1 instance chạy job, nhưng giữ `SKIP LOCKED` để an toàn nếu
   sau này có thao tác thủ công/nhánh khác chạm cùng bảng).
2. **Xóa phái sinh** (transaction riêng theo từng document, không theo cả
   lô, để 1 document lỗi không khoá các document khác): `DELETE FROM
   document_tag/document_note/document_group_share/digitization_source/
   source_separation_job WHERE document_id = :id`; ghi 1 `outbox_event`
   (`document.purged`, payload `{documentId}`).
3. **Xóa object SeaweedFS:** nếu `NOT EXISTS (SELECT 1 FROM document WHERE
   sha256 = :sha256 AND id != :id AND status != 'purged')` (bảo vệ khử
   trùng lặp) → gọi phương thức mới
   `ObjectStorageService.purgeWarehouseObject(storageKey)` (Task 6); nếu
   SeaweedFS trả "not found" → coi là thành công (idempotent, AC-2).
4. **Rỗng hóa + bia mộ:** `UPDATE document SET ...` theo bảng §2.2,
   `purge_state = 'purged'`, `purged_at = now()`.
5. **Audit:** `auditService.record(actor='system', action='document.purge',
   resourceId=documentId, metadata={sha256, originalName})` — **không**
   được xóa các bản ghi audit cũ (`document.trash`, `document.restore`)
   của tài liệu này (Quyết định 8 SA) — job không có bất kỳ câu lệnh
   `DELETE FROM audit_log` nào.

Lỗi ở bước 2/3/4 cho 1 document: log + tăng biến đếm lỗi của lô, **không**
rollback các document khác đã xử lý xong trong lô (mỗi document là 1
transaction độc lập); document lỗi **giữ nguyên `purge_state = 'purging'`**
để `PurgeStuckRecoveryJob` xử lý lại.

### 4.3 Phục hồi khỏi kẹt (`purging` quá hạn) — trả lời câu hỏi mở AC-5 Story 5

- Ngưỡng chốt: **2 giờ** kể từ khi chuyển sang `purging`
  (`updated_at`/cột theo dõi riêng — thêm `purging_since TIMESTAMPTZ` nếu
  cần, hoặc tái dùng thời điểm job set state kèm timestamp trong log; đơn
  giản nhất: thêm cột `purging_started_at TIMESTAMPTZ` cùng V30). Lý do
  chọn 2 giờ: 1 lô 200 bản ghi theo NFR không được giữ transaction dài
  (mỗi document là transaction riêng, thao tác nặng nhất là 1 lần gọi
  SeaweedFS xóa object) — 2 giờ đủ dư cho cả trường hợp SeaweedFS chậm bất
  thường, đồng thời đủ ngắn để không để tài liệu "treo" quá lâu không phục
  hồi được (khi đang `purging`, AC-8 chặn phục hồi).
- `PurgeStuckRecoveryJob` (`@Scheduled` mỗi 30 phút, cùng `@SchedulerLock`
  riêng tên `trash-purge-recovery`): `UPDATE document SET purge_state =
  'deleted', purging_started_at = NULL WHERE purge_state = 'purging' AND
  purging_started_at <= now() - interval '2 hours'`. Không cần xử lý gì
  thêm — lượt `trash-purge` kế tiếp sẽ chọn lại các dòng này bình thường
  (idempotent vì bước 2–4 dùng `DELETE ... WHERE document_id = :id`,
  `NOT EXISTS` — chạy lại trên dữ liệu đã xóa một phần không lỗi).

### 4.4 Gợi ý thư mục gần nhất còn tồn tại (AC-1 Story 6) — trả lời câu hỏi mở

- Tại thời điểm **xóa thư mục** (không phải tại thời điểm restore):
  `WarehouseService.deleteFolder(id)` — ngay trước
  `repository.deleteById(id)` — chạy thêm:
  `documentRepository` bulk update mọi `document` có `folder_id = id` để
  `deleted_folder_path_ids = folder.getPathIds() ++ [id]` (nếu đang
  `NULL`; không ghi đè nếu đã có giá trị từ lần xóa thư mục trước đó ở một
  cấp cao hơn — cực hiếm nhưng an toàn).
- Tại thời điểm **restore**, nếu `folder_id IS NULL` và có
  `deleted_folder_path_ids`: duyệt mảng từ **cuối về đầu** (lá → gốc), với
  mỗi id gọi `warehouseService.folderExists(id)` (method đã có sẵn), trả
  về **id đầu tiên còn tồn tại** làm `suggestedFolderId`. Không tìm thấy id
  nào còn tồn tại (cả cây tổ tiên đã bị xóa) → `suggestedFolderId = null`,
  UI fallback gợi ý thư mục gốc của kho.
- Đây là **truy vấn ngược trên dữ liệu đã snapshot tại thời điểm xóa
  thư mục** (không phải suy luận từ cây hiện tại theo tên) — chọn cách này
  vì tên thư mục có thể bị đổi/tái sử dụng sau khi xóa (thư mục mới trùng
  tên nhưng khác `id`), suy luận theo tên sẽ sai; theo `id` qua `path_ids`
  luôn chính xác.

## 5. Test plan

- **Integration — máy trạng thái:** tài liệu ở `deleted`, `purge_after`
  đã qua → job chọn, chuyển `purging`, xử lý xong → `purged`, dòng bia mộ
  đúng bảng §2.2 (assert từng cột).
- **Integration — idempotent/resumable:** giả lập crash giữa bước 2 và 3
  (dừng thủ công sau khi xóa phái sinh) → chạy lại toàn bộ luồng bước
  2–4 trên cùng document (đang ở `purging`) → không lỗi, kết quả cuối
  giống hệt chạy suôn sẻ 1 lần; `PurgeStuckRecoveryJob` đưa `purging` quá
  2 giờ về `deleted`, lượt `trash-purge` kế tiếp xử lý lại thành công.
- **Integration — khử trùng lặp `sha256`:** 2 document cùng `storage_key`
  (dedup), purge document A trước → object **không** bị xóa (còn B sống);
  purge B sau → object bị xóa; nếu B bị purge trước A do thứ tự lô, kết
  quả cuối vẫn đúng object chỉ mất khi cả hai đã purge (test cả 2 thứ tự).
- **Integration — ShedLock:** khởi 2 "instance" giả lập (2 thread gọi
  `TrashPurgeService` cùng lúc với cùng `@SchedulerLock`) → chỉ 1 chạy,
  instance kia bỏ qua ngay (không đợi rồi chạy lại lô đã xử lý).
- **Integration — gate với backup:** `backup.purge_gate_enabled=true` và
  không có `backup_run` hợp lệ của hôm nay → job bỏ lượt, ghi
  `trash.purge.skipped`, không document nào đổi state; có `backup_run`
  `VERIFIED` + `window_open=false` → job chạy bình thường.
- **Unit — 8 case xung đột (§3.1):** mỗi case 1 test độc lập, cộng thêm 1
  test thứ tự ưu tiên khi 2 điều kiện cùng đúng (vd. vừa `purging` vừa
  trùng tên → phải trả `PURGING`, không lộ thông tin trùng tên).
- **Unit — gợi ý thư mục (§4.4):** xóa 3 cấp thư mục lồng nhau lần lượt từ
  lá lên gốc (mỗi lần xóa 1 cấp, đúng ràng buộc "chỉ xóa thư mục lá") →
  `suggestedFolderId` trả về đúng thư mục tổ tiên gần nhất **còn tồn tại**
  ở mỗi lần restore thử tương ứng.
- **Case biên:** lô purge có 1 document lỗi ở bước 3 (SeaweedFS timeout giả
  lập) → document đó ở lại `purging`, 199 document còn lại trong lô vẫn
  chuyển `purged` bình thường; metric/log số lỗi tăng đúng.

## 6. Task breakdown

- [x] Task 1: Thêm dependency `net.javacrumbs.shedlock:shedlock-spring`
      + `shedlock-provider-jdbc-template` vào `backend/pom.xml`; bật
      `@EnableSchedulerLock` + `@EnableScheduling` ở cấu hình chính. —
      **xong (2026-09-09)**: `@EnableScheduling` đã có sẵn ở
      `BackendApplication`; `@EnableSchedulerLock` thêm ở
      `config/SchedulerLockConfig.java` (bean `LockProvider` riêng).
- [x] Task 2: Viết `V30__trash_purge_and_conflict.sql` (§2.1, bao gồm
      `purging_started_at`). — **xong**: re-grep xác nhận V30 vẫn trống lúc
      viết, không cần đổi số.
- [x] Task 3: Thêm `DocumentStatus.PURGED` (§2.2 Task 7 gộp vào đây). —
      **xong**.
- [x] Task 4: `TrashPurgeJob` + `TrashPurgeService` — 5 bước (§4.2), gate
      backup (§4.1), lô 200 + nghỉ giữa lô. — **xong**: gate hiện luôn
      fail-closed trong thực tế vì bảng `backup_run` (slug `backup-restore`)
      chưa tồn tại trong codebase này (đúng như kịch bản dự phòng ở §7 rủi
      ro 1) — code đã viết sẵn theo gate thật (kiểm tra
      `information_schema.tables` trước, rồi mới query `backup_run` nếu
      bảng đã có), không phải nhánh tạm.
- [x] Task 5: `PurgeStuckRecoveryJob` (§4.3). — **xong**.
- [x] Task 6: `ObjectStorageService.purgeWarehouseObject(String key)` —
      phương thức mới, gọi `deleteObject(key, properties.bucket())` nội
      bộ + ghi log/audit riêng; **không** expose qua controller nào. —
      **xong**.
- [x] Task 7: Hook snapshot `deleted_folder_path_ids` trong
      `WarehouseService.deleteFolder(...)` (§4.4) — phối hợp/đối chiếu lại
      `docs/01-sa/architecture/warehouse-structure.md` trước khi sửa để
      không phá test hiện có của Story 3 (xóa thư mục). — **xong**: test
      Story 3 hiện có (`WarehouseFolderServiceTest`, `WarehouseControllerIT`)
      vẫn xanh sau khi sửa `deleteFolder` để fetch full entity + snapshot.
- [x] Task 8: Mở rộng `TrashService.restore(...)` (file 01) với bảng kiểm
      tra 8 case (§3.1, §3 thứ tự ưu tiên). — **xong**: cũng tiện sửa
      `TrashService.trash()`/`getOne()` để set/đọc `purge_state` thật (file
      01 để tạm `"active"` hard-code vì chưa có migration V30) — cần thiết
      để job purge ở Task 4 tìm đúng các document `deleted`.
- [x] Task 9: Toàn bộ test ở §5. — **phần lớn xong** (máy trạng thái,
      idempotent/resumable, dedup 2 thứ tự, gate backup, 8 case xung đột +
      thứ tự ưu tiên, gợi ý thư mục, case biên 1 lỗi giữa lô — xem
      `TrashPurgeServiceTest`/`TrashPurgeServiceIT`/`TrashPurgeJobTest`/
      `PurgeStuckRecoveryJobTest`/`TrashServiceTest`/
      `TrashRestoreConflictControllerIT`). **Còn thiếu**: test "ShedLock: 2
      instance giả lập cùng lúc" (2 thread thật gọi cùng
      `@SchedulerLock`) — độ phức tạp thiết lập không tương xứng với giá trị
      thêm (thư viện ShedLock tự có test riêng cho đúng cơ chế này); để lại
      như rủi ro chấp nhận được, không phải bug.
- [x] Task 10: Cập nhật `docs/01-sa/architecture/delete-restore-trash.md`
      mục 7 — **xong (2026-09-09)**: cả 2 file SA (`delete-restore-trash.md`,
      `backup-restore.md`) đã Approved, câu hỏi mở về giờ chạy job đã đánh
      dấu `[x]` trỏ sang Quyết định 3.

## 7. Rủi ro kỹ thuật

- **Đã xác nhận (2026-09-09):** `backup-restore` triển khai **trước**,
  `delete-restore-trash` (đặc biệt `TrashPurgeJob`/Task 4) triển khai
  **sau**. Gate purge phụ thuộc bảng `backup_run` + API
  `/internal/backup/runs` của `backup-restore` — với thứ tự này, Task 4 code
  thẳng theo gate thật ở §4.1 mà không cần nhánh fail-closed tạm thời làm
  đường chính. Vẫn giữ hành vi fail-closed khi `backup_run` của **ngày hôm
  nay** chưa có/chưa `VERIFIED` (trường hợp vận hành bình thường, không phải
  trường hợp "bảng chưa tồn tại") như một safety net, không phải như
  workaround cho thứ tự triển khai.
- `deleted_folder_path_ids` không phủ được dữ liệu lịch sử (thư mục đã bị
  xóa trước khi migration này chạy) — đã ghi ở §2.1, chấp nhận được vì chỉ
  ảnh hưởng tới UX gợi ý (không ảnh hưởng tính đúng đắn/an toàn dữ liệu).
- Rỗng hóa `mime_type`/`uploader_sub`/`uploader_username` thành chuỗi rỗng
  thay vì `NULL` (để tránh alter `NOT NULL`) — nếu sau này có báo cáo/thống
  kê nào vô tình đếm các dòng `purged` (vd. quên thêm điều kiện loại trừ
  `status != 'purged'`), sẽ ra dữ liệu rỗng gây khó hiểu thay vì lỗi rõ
  ràng; giảm thiểu bằng cách mọi query mới trong slug này đều tường minh
  lọc `status = 'clean'` như quy ước sẵn có, không dựa vào suy luận ngầm.
- Xóa object SeaweedFS là **không thể hoàn tác** — lỗi logic ở bước kiểm
  tra khử trùng lặp (`NOT EXISTS ... status != 'purged'`) sẽ xóa nhầm
  object đang được tài liệu khác dùng chung; test bắt buộc phải cover cả
  2 thứ tự purge (§5) trước khi coi Task 4/6 là hoàn thành.
- `purging_started_at` + ngưỡng 2 giờ là số DEV tự chọn (SA không chốt) —
  nếu vận hành thực tế cho thấy 1 lô thường xuyên mất hơn 2 giờ (vd.
  SeaweedFS chậm bất thường ở một số object lớn), cần điều chỉnh tham số
  này thành cấu hình `system-config` thay vì hằng số cứng — MVP để hằng số
  cứng trong code, ghi rõ trong Javadoc để dễ tìm khi cần đổi.
