# Tech Spec: warehouse-structure

> File: `docs/03-dev/specs/warehouse-structure.md` — Owner: DEV
> Nguồn story: `docs/02-ba/stories/warehouse-structure.md`
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
> | 3 | `warehouse-structure` ⬅ **spec này** | `V9__warehouse_structure_v2.sql` |
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
| 1 | Story 3 AC-2 / Story 5 AC-1 phụ thuộc thùng rác (FR-26/27) chưa được đặc tả | **Thêm cột `document.deleted_at` tối thiểu ngay trong `V9`** — chỉ schema + ngữ nghĩa đếm, KHÔNG xây UI thùng rác / job dọn hết hạn (để dành FR-26/27). Nhờ vậy 2 AC implement và test được ngay. |
| 2 | Quy tắc tên thư mục (Story 1 AC-3) có cho dấu cách không | **Cho phép dấu cách**: `^[A-Za-z0-9 _-]{1,100}$`. Vẫn giữ "không dấu" đúng AC-3. |
| 3 | Dữ liệu `warehouse_folder` cũ vi phạm ràng buộc mới | **Migration fail cứng, dọn tay trước** — giữ CHECK constraint ở DB, chạy script tiền kiểm (Task 0) và để R3 dọn thủ công trước khi deploy. Không tự động chuẩn hóa tên. |
| 4 | `document.folder_id` nullable + `ON DELETE SET NULL` (sinh "tài liệu mồ côi") | **Chấp nhận theo spec** — document `rejected`/`infected` không có bytes trên SeaweedFS (xác nhận tại `DocumentsService` — "bytes are never stored"), nên mất liên kết thư mục là chấp nhận được, vẫn truy vết qua `audit_log`. |

> ⚠️ **Đính chính (2026-08-12) — giá trị `document.status` là chữ THƯỜNG.**
> `DocumentStatus.java` định nghĩa `'pending'|'clean'|'infected'|'rejected'`
> (chữ thường, mirror từ backend NestJS cũ). Bản trước của spec này viết
> `'CLEAN'`/`'REJECTED'`/`'INFECTED'` — nếu code theo đúng chữ hoa thì mọi
> query sẽ **im lặng trả rỗng**: thư mục còn dữ liệu vẫn bị coi là rỗng và
> xóa được, `usedBytes` luôn bằng 0 nên ngưỡng 85/95% không bao giờ kích
> hoạt. Toàn bộ spec đã sửa sang chữ thường; khi implement phải dùng hằng
> `DocumentStatus.CLEAN` thay vì viết literal, để compiler bắt lỗi.
> **Cập nhật về `'pending'`:** giá trị thứ 4 này trước đây không nơi nào
> ghi, nhưng `data-ingestion.md` (`V10`, Quyết định 2 mục 0 của file đó)
> nay **có** ghi: pipeline đổi thành `pending` → `putObject` → `clean`, nên
> row `pending` tồn tại trong khoảng vài giây (hoặc lâu hơn nếu tiến trình
> chết giữa chừng). Ngữ nghĩa đã chốt cho spec này: `'pending'` **không**
> tính là "dữ liệu hoạt động" (không chặn xóa thư mục) và **không** tính
> vào `usedBytes` — vì tệp chưa hoàn tất, chưa phải dữ liệu người dùng. Cả
> hai query hiện tại đều lọc `status='clean'` nên **đã đúng, không cần
> sửa**. Hệ quả đã biết và chấp nhận: nếu tiến trình chết sau khi bytes đã
> lên SeaweedFS nhưng row còn `pending`, phần bytes đó chiếm chỗ thật mà
> không được tính vào `usedBytes` — cùng rủi ro orphan đã ghi ở mục 7 của
> `data-ingestion.md`, dọn bằng script đối chiếu `storage_key`.

## 1. Tóm tắt kỹ thuật

- Mở rộng `warehouse_folder` (đã có) với `path_ids` (materialized path bằng
  **mảng UUID tổ tiên**, không dùng tên) + `depth`, để enforce tối đa 5 cấp
  và cập nhật hàng loạt khi di chuyển nhánh (Story 2). Đổi tên **không** đổi
  `path_ids` (chỉ đổi cột `name`) — khớp đúng AC-1 "đổi tên không đổi path".
- Thêm bảng đơn dòng `warehouse` lưu `max_capacity_bytes` (mẫu số %, Story 5
  AC-4) — tách biệt hoàn toàn với 2 ngưỡng % (`warehouse.storage_warning_
  threshold_percent` / `warehouse.storage_block_threshold_percent`) đã đặt
  chỗ ở `system-config.md` (spec đó đang **Draft**, xem Rủi ro).
- "Dung lượng đã dùng" = `SUM(document.size) WHERE status = 'clean'`, tính
  bằng aggregate query theo yêu cầu (không đọc SeaweedFS, không duy trì
  counter) — xem lý do ở mục 4.3.
- **Giả định quan trọng (chưa có FR-26/27):** hệ thống hiện KHÔNG có cột
  trạng thái xóa mềm/thùng rác trên `document`. Spec này tạm coi
  `status='clean'` là "đang hoạt động"; khái niệm "tệp trong thùng rác" của
  Story 3 AC-2 / Story 5 AC-1 hiện **không thể xảy ra** trong hệ thống (chưa
  có cơ chế xóa mềm) — xem Rủi ro mục 7 để biết hướng migrate khi FR-26/27
  ra đời.
- Đổi `document.folder_id` từ `ON DELETE RESTRICT` sang `ON DELETE SET NULL`
  (+ bỏ `NOT NULL`) để xóa thư mục vẫn thực hiện được khi chỉ còn tệp
  `rejected`/`infected`/(sau này) đã xóa mềm tham chiếu tới nó (Story 3
  AC-2) — chi tiết mục 4.2.
- Thêm endpoint di chuyển thư mục (`PATCH /folders/{id}/move`, mới) và 2
  endpoint dung lượng kho (`GET`/`PATCH /warehouse/capacity`, mới). Siết lại
  validate tên (`@Size(max=100)` + regex) trên các DTO hiện có — **breaking
  change** cho dữ liệu/FE cũ, xem mục 3.
- Nội bộ: `WarehouseCapacityService` expose 2 method Java
  (`assertIngestAllowed`, `recomputeAfterWrite`) để `data-ingestion.md` gọi
  ở đúng 2 điểm pre-check/post-check (Story 5 AC-5) — `data-ingestion.md`
  hiện chưa có DEV spec, vị trí gọi mô tả ở mục 4.4 dựa trên
  `DocumentsService.ingest()` hiện có.

## 2. Data model

```sql
-- V9__warehouse_structure_v2.sql
-- (Thứ tự đã chốt: sau V7 system_config và V8 account. Xem bảng thứ tự
--  triển khai ở đầu file.)

-- 2.1 Warehouse folder: thêm path_ids (materialized path theo id tổ tiên,
-- KHÔNG theo tên -> đổi tên không cần cập nhật path_ids), depth, audit
-- columns, siết ràng buộc tên.
ALTER TABLE warehouse_folder
    ALTER COLUMN name TYPE VARCHAR(100),
    ADD COLUMN path_ids UUID[] NOT NULL DEFAULT '{}',
    ADD COLUMN depth SMALLINT NOT NULL DEFAULT 1,
    ADD COLUMN created_by_sub VARCHAR(255),
    ADD COLUMN created_by_username VARCHAR(255),
    ADD COLUMN updated_by_sub VARCHAR(255),
    ADD COLUMN updated_by_username VARCHAR(255),
    ADD CONSTRAINT CK_warehouse_folder_depth CHECK (depth BETWEEN 1 AND 5),
    ADD CONSTRAINT CK_warehouse_folder_name_format
        CHECK (name ~ '^[A-Za-z0-9 _-]{1,100}$');

-- Backfill path_ids/depth cho dữ liệu hiện có (recursive CTE, chạy 1 lần
-- trong migration) trước khi thêm CHECK depth — nếu dữ liệu hiện tại đã
-- có nhánh > 5 cấp thì migration phải dừng và báo R3 dọn trước khi deploy.
WITH RECURSIVE tree AS (
    SELECT id, ARRAY[]::uuid[] AS path_ids, 1::smallint AS depth
    FROM warehouse_folder WHERE parent_id IS NULL
    UNION ALL
    SELECT f.id, t.path_ids || f.parent_id, t.depth + 1
    FROM warehouse_folder f JOIN tree t ON f.parent_id = t.id
)
UPDATE warehouse_folder f SET path_ids = t.path_ids, depth = t.depth
FROM tree t WHERE f.id = t.id;

CREATE INDEX IDX_warehouse_folder_path_ids ON warehouse_folder USING GIN (path_ids);

-- 2.2 Document: cho phép detach khỏi thư mục khi thư mục bị xóa (Story 3
-- AC-2) thay vì chặn cứng bằng FK RESTRICT trên MỌI document (kể cả
-- rejected/infected, vốn không phải "dữ liệu" thật).
ALTER TABLE document ALTER COLUMN folder_id DROP NOT NULL;
ALTER TABLE document DROP CONSTRAINT document_folder_id_fkey;
ALTER TABLE document ADD CONSTRAINT document_folder_id_fkey
    FOREIGN KEY (folder_id) REFERENCES warehouse_folder (id) ON DELETE SET NULL;

-- 2.3 Cột xóa mềm tối thiểu (Quyết định 1 mục 0). CHỈ schema + ngữ nghĩa
-- đếm; KHÔNG xây UI thùng rác, KHÔNG job dọn hết hạn (thuộc FR-26/27 sau).
-- NULL = đang hoạt động; NOT NULL = đã ở thùng rác (bytes VẪN còn trên
-- SeaweedFS nên vẫn tính vào dung lượng đã dùng — Story 5 AC-1).
ALTER TABLE document ADD COLUMN deleted_at TIMESTAMPTZ NULL;

-- Index phục vụ check "còn dữ liệu hoạt động" theo folder (Story 3 AC-2 —
-- partial index, chỉ hàng chưa xóa mềm) và tổng dung lượng đã dùng (Story 5
-- AC-1 — KHÔNG lọc deleted_at, thùng rác vẫn tính).
CREATE INDEX IDX_document_folder_active ON document (folder_id, status)
    WHERE deleted_at IS NULL;
CREATE INDEX IDX_document_status_size ON document (status) INCLUDE (size);

-- 2.4 Bảng đơn dòng cho "dung lượng tối đa của kho" (Story 5 AC-4) — KHÔNG
-- ở system_config vì R3 chỉnh tại màn hình quản lý kho, không phải màn
-- hình cấu hình hệ thống chung (theo BA "Ngoài phạm vi").
CREATE TABLE warehouse (
    id                  SMALLINT PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    max_capacity_bytes  BIGINT NOT NULL CHECK (max_capacity_bytes > 0),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by_sub      VARCHAR(255),
    updated_by_username VARCHAR(255)
);

-- Seed 1 dòng mặc định (placeholder, R3 chỉnh lại qua PATCH /warehouse/capacity
-- sau khi deploy) để tránh chia-cho-0 khi tính %. 10 TiB chỉ là giá trị
-- khởi tạo an toàn, KHÔNG phải số nghiệp vụ đã chốt.
INSERT INTO warehouse (id, max_capacity_bytes) VALUES (1, 10995116277760);
```

**Quyết định materialized path:** dùng mảng UUID tổ tiên (`path_ids`), không
dùng chuỗi tên. Lý do: (a) đổi tên không phải cập nhật lại path của bất kỳ
thư mục nào (khớp AC-1); (b) so sánh/chặn cycle khi di chuyển chỉ là kiểm
tra `folder_id = ANY(target.path_ids)` (có GIN index); (c) tính "toàn bộ
nhánh con" của X là `WHERE path_ids @> ARRAY[X]` (có GIN index) thay vì
LIKE trên chuỗi tên dễ vỡ khi tên trùng ký tự đặc biệt. Breadcrumb tên hiển
thị (nếu UI cần) build tại tầng service bằng cách map `path_ids` sang tên
trong tập folder đã load 1 lần (cây tối đa 5 cấp, rẻ, không cần cột riêng).

## 3. API contract

Base path thư mục: `/folders` (giữ nguyên, đã có). Base path dung lượng
kho: `/warehouse` (mới).

| Method | Endpoint | Request | Response | Ghi chú |
|---|---|---|---|---|
| GET | `/folders/tree` | – | `200 [{id, name, depth, children:[...]}, ...]` | **Additive, không breaking** — thêm field `depth` vào `FolderNode`. Story 4. |
| POST | `/folders` | `{name, parentId?}` | `200 WarehouseFolder{id,name,parentId,depth,createdAt,...}` | **Breaking (validate)**: `name` giờ `@Size(max=100)` + regex `^[A-Za-z0-9 _-]+$` — cho phép dấu cách, không cho chữ có dấu (Quyết định 2 mục 0; trước là max 255, không regex). `409 CONFLICT` nếu trùng tên cùng cha; `409 MAX_DEPTH_EXCEEDED` nếu `parent.depth + 1 > 5`. Story 1. |
| PATCH | `/folders/{id}` | `{name}` | `200 WarehouseFolder` | **Breaking (validate)** như trên. Chỉ đổi tên, KHÔNG đổi `parentId`/`path_ids`. `409` nếu trùng tên cùng cha. Story 2 AC-1/AC-4. |
| PATCH | `/folders/{id}/move` | `{newParentId: UUID \| null}` | `200 WarehouseFolder{...,parentId,depth,path_ids}` | **Mới.** `newParentId=null` = chuyển về gốc. `404` nếu id/newParentId không tồn tại; `409 CYCLE_DETECTED` nếu `newParentId` là chính nó hoặc thuộc nhánh con của nó; `409 MAX_DEPTH_EXCEEDED` nếu nhánh con sâu nhất sau di chuyển > 5 cấp; `409 CONFLICT` nếu trùng tên với thư mục cùng cấp ở cha đích. Story 2 AC-2/AC-4. |
| DELETE | `/folders/{id}` | – | `204` | **Breaking (behavior)**: trước đây bị FK chặn nếu còn BẤT KỲ row `document` nào trỏ tới; giờ chỉ chặn nếu còn `document.status='clean'` (trực tiếp hoặc trong bất kỳ thư mục con nào) **hoặc** còn thư mục con (bất kỳ, do self-FK RESTRICT không đổi). `409 FOLDER_NOT_EMPTY` kèm lý do (`hasActiveDocuments` \| `hasChildFolders`). Story 3. |
| GET | `/folders/{id}/breadcrumb` | – | `200 [{id,name}, ...]` (root → self) | Mới, tiện ích tuỳ chọn cho UI hiển thị đường dẫn; không bắt buộc cho AC nào — bỏ nếu không cần trong phạm vi FE hiện có. |
| GET | `/warehouse/capacity` | – | `200 {usedBytes, maxCapacityBytes, usedPercent, warningThresholdPercent, blockThresholdPercent, status: "OK"\|"WARNING"\|"BLOCKED"}` | Mới. `@PreAuthorize` mọi role đã đăng nhập cho phần hiển thị cơ bản; UI quản trị (R3) dùng cùng endpoint. Story 5 AC-1/AC-2/AC-3. |
| PATCH | `/warehouse/capacity` | `{maxCapacityBytes: number}` | `200` (shape như GET) | Mới, `@PreAuthorize("hasRole('QUAN_TRI')")`. `400 INVALID_VALUE` nếu `<= 0`. **Không** validate so với `usedBytes` hiện tại (Story 5 AC-6 — vẫn lưu, trạng thái cập nhật ngay trong response). |

Mã lỗi dùng chung format `{code, message}` (đồng bộ `ConflictException`/
`NotFoundException`/`UnprocessableEntityException` hiện có trong
`com.aismartstore.backend.common`), không thêm exception type mới ngoài 2
subtype `ConflictException` với `code` khác nhau (`MAX_DEPTH_EXCEEDED`,
`CYCLE_DETECTED`, `FOLDER_NOT_EMPTY`, `WAREHOUSE_CAPACITY_EXCEEDED`).

**Nội bộ (không phải HTTP, dùng bởi `data-ingestion.md`):**

```java
public interface WarehouseCapacityService {
    // Pre-check: gọi khi tạo batch / nhận tệp đầu tiên. Throw ConflictException("WAREHOUSE_CAPACITY_EXCEEDED") nếu usedPercent >= block threshold.
    void assertIngestAllowed();
    // Post-check: gọi ngay sau mỗi document.save() thành công. Tính lại % nhưng KHÔNG chặn tệp vừa ghi (đã ghi rồi) — chỉ cập nhật trạng thái cảnh báo/chặn cho các tệp/batch TIẾP THEO.
    WarehouseCapacitySnapshot recomputeAfterWrite();
}
```

## 4. Luồng xử lý chính

### 4.1 Tạo / sửa tên thư mục (Story 1)

1. `@PreAuthorize("hasRole('QUAN_TRI')")` chặn role khác ở tầng controller
   (AC-1) — giữ nguyên cơ chế hiện có.
2. Validate `name` qua Bean Validation (`@Size(max=100)`, `@Pattern`) trước
   khi chạm DB — lỗi trả `400` kèm message rõ (AC-3).
3. Nếu `parentId != null`: load parent, nếu không tồn tại → `404`; nếu
   `parent.depth + 1 > 5` → `409 MAX_DEPTH_EXCEEDED` (AC-2).
4. `path_ids = parent.path_ids + [parent.id]` (hoặc `{}` nếu root),
   `depth = parent.depth + 1` (hoặc `1` nếu root).
5. `INSERT`; bắt `DataIntegrityViolationException` do
   `UQ_warehouse_folder_sibling_name` → `409 CONFLICT` (AC-4) — logic hiện có
   giữ nguyên, chỉ đổi message/code.
6. Sửa tên: `UPDATE ... SET name=?, updated_at=now(), updated_by_*=?` —
   KHÔNG đụng `path_ids`/`depth`/`parent_id` của bất kỳ hàng nào (AC-5: các
   thư mục khác và mọi `document.folder_id` không bị ảnh hưởng, không gián
   đoạn truy cập).

### 4.2 Di chuyển thư mục (Story 2 AC-2/AC-4)

1. Load `folder` (404 nếu không có) và `newParent` (404 nếu `newParentId`
   khác null và không tồn tại).
2. **Chặn cycle:** nếu `newParentId == folder.id` hoặc
   `folder.id = ANY(newParent.path_ids)` → `409 CYCLE_DETECTED` (không cho
   thả một nhánh vào chính con cháu của nó).
3. **Chặn trùng tên ở cha đích:** kiểm `EXISTS` `(newParentId, folder.name)`
   trong `warehouse_folder` (khác `folder.id`) → `409 CONFLICT`.
4. **Chặn vượt 5 cấp sau di chuyển:**
   - `deltaDepth = (newParent.depth + 1) - folder.depth` (hoặc `1 -
     folder.depth` nếu về root).
   - `maxDepthInSubtreeOld = SELECT MAX(depth) FROM warehouse_folder WHERE
     id = folder.id OR path_ids @> ARRAY[folder.id]`.
   - Nếu `maxDepthInSubtreeOld + deltaDepth > 5` → `409
     MAX_DEPTH_EXCEEDED`.
5. Trong 1 transaction:
   - `newPrefix = newParent.path_ids + [newParentId]` (hoặc `{}` nếu root).
   - `UPDATE warehouse_folder SET parent_id=newParentId, path_ids=newPrefix,
     depth=length(newPrefix)+1, updated_at=now(), updated_by_*=? WHERE
     id=folder.id`.
   - Cập nhật **toàn bộ nhánh con**: với mỗi hàng `d` mà
     `path_ids @> ARRAY[folder.id]`, giữ nguyên phần "đuôi" phía sau
     `folder.id` trong `path_ids` cũ của `d`, chỉ thay phần "đầu" (từ root
     tới `folder.id`) bằng `newPrefix + [folder.id]`:
     `new_path_ids(d) = newPrefix || folder.id || old_path_ids(d)[(len(old_folder.path_ids)+2):]`,
     `new_depth(d) = old_depth(d) + deltaDepth`. Thực hiện bằng 1 câu
     `UPDATE ... FROM (SELECT id, path_ids FROM warehouse_folder WHERE
     path_ids @> ARRAY[:folderId]) sub` dùng array slicing của Postgres —
     không cần vòng lặp ứng dụng.
   - `folder_id` của mọi `document` KHÔNG đổi (AC-3 — tham chiếu vẫn theo
     `folder_id`, không đụng tới).
6. `document`/subfolder vẫn truy cập được xuyên suốt vì transaction là
   atomic và không có bước xóa/insert lại document nào (NFR Availability).

### 4.3 Xóa thư mục rỗng (Story 3)

1. Kiểm tra thư mục con: nếu tồn tại bất kỳ `warehouse_folder` nào có
   `parent_id = id` → để nguyên hành vi hiện có (self-FK `ON DELETE
   RESTRICT` sẽ tự chặn ở bước DELETE) — không cần query trước, nhưng nên
   query trước để trả `409 FOLDER_NOT_EMPTY {reason:"hasChildFolders"}`
   thay vì lộ lỗi FK thô.
2. Kiểm tra dữ liệu hoạt động: `EXISTS (SELECT 1 FROM document WHERE
   folder_id = :id AND status = 'clean' AND deleted_at IS NULL)` → nếu có
   → `409 FOLDER_NOT_EMPTY {reason:"hasActiveDocuments"}` (AC-1).
   - `deleted_at IS NULL` chính là phần thực thi AC-2: thư mục chỉ còn tệp
     đã xóa mềm (`deleted_at NOT NULL`) được coi là rỗng và xóa được bình
     thường. Cột do `V9` tạo (mục 2.3) nên nhánh AC-2 **test được ngay** —
     fixture set thẳng `deleted_at`, không phải chờ FR-26/27 (Quyết định 1
     mục 0).
3. Nếu qua cả 2 kiểm tra: `DELETE FROM warehouse_folder WHERE id = :id`.
   Do FK `document.folder_id` đã đổi sang `ON DELETE SET NULL` (mục 2), mọi
   `document` còn sót (status `rejected`/`infected`, hoặc sau này đã xóa
   mềm) tự động có `folder_id = NULL`, không chặn lệnh xóa, không mất dữ
   liệu (NFR-09 vẫn giữ — chỉ mất liên kết thư mục, không mất bản ghi).

### 4.4 Tính & enforce dung lượng kho (Story 5)

1. **Tính "đã dùng" (AC-1):** `usedBytes = SELECT COALESCE(SUM(size),0) FROM
   document WHERE status = 'clean'` — **cố ý KHÔNG lọc `deleted_at`**: tệp
   trong thùng rác vẫn chiếm bytes thật trên SeaweedFS cho tới khi bị xóa
   cứng, nên vẫn phải tính vào dung lượng đã dùng (AC-1). Đây là khác biệt
   **có chủ đích** so với query "còn dữ liệu hoạt động" ở mục 4.3 — hai quy
   tắc độc lập, phục vụ 2 mục đích khác nhau. **Aggregate query mỗi lần
   gọi**, có
   index `IDX_document_status_size` (`INCLUDE (size)`, index-only scan).
   - **Trade-off đã chọn (aggregate vs. counter):** chọn aggregate vì
     `document` là bảng insert-only/immutable (NFR-09) nên không có rủi ro
     lệch số do update/xóa cứng; counter cộng dồn (vd. cột `warehouse.
     used_bytes`) nhanh hơn về lý thuyết (O(1)) nhưng cần cập nhật atomic ở
     MỌI điểm ghi `document` (kể cả các luồng tương lai: xóa mềm, khôi phục,
     xóa cứng sau retention) — rủi ro lệch số cao hơn lợi ích hiệu năng ở
     quy mô MVP. Nếu sau này profiling cho thấy aggregate chậm khi
     `document` phình to, chuyển sang counter hoặc materialized view refresh
     định kỳ là điểm mở rộng rõ ràng, không đổi API.
2. **`usedPercent = usedBytes * 100.0 / warehouse.max_capacity_bytes`.**
3. **`status`:** `>= blockThresholdPercent` → `BLOCKED`; else `>=
   warningThresholdPercent` → `WARNING`; else `OK`. 2 ngưỡng đọc qua
   `SystemConfigService.getLong("warehouse.storage_warning_threshold_percent")`
   / `"warehouse.storage_block_threshold_percent"` (key đã chốt ở
   `system-config.md`, spec đó đang Draft — xem Rủi ro).
4. **Điểm chèn pre-check (AC-5a):** trong luồng nạp dữ liệu của
   `data-ingestion.md`, ngay tại bước tạo **batch** (API mở batch, cùng vị
   trí với pre-check `ingest.max_files_per_batch`/`max_batch_size_bytes` ở
   Story 2 AC-3 của `data-ingestion.md`) — gọi
   `WarehouseCapacityService.assertIngestAllowed()` trước khi trả về
   `batchId`; nếu `status=BLOCKED` → từ chối mở batch, không nhận tệp nào.
   (Batch API chưa tồn tại trong code hiện tại — `DocumentsService` hiện
   ingest từng tệp độc lập; `data-ingestion.md` sẽ định nghĩa endpoint này,
   spec đó cần gọi lại method trên đúng vị trí.)
5. **Điểm chèn post-check (AC-5b):** trong `DocumentsService.ingest()` hiện
   có, ngay sau dòng `documentRepository.save(document);` (khi status đã
   là `clean`, tức đúng thời điểm `usedBytes` tăng) — gọi
   `WarehouseCapacityService.recomputeAfterWrite()`. Method này KHÔNG chặn
   tệp vừa ghi (đã ghi xong) — chỉ cập nhật `status` để lần gọi
   `assertIngestAllowed()`/GET tiếp theo thấy đúng trạng thái mới nhất, bắt
   kịp race condition nhiều lượt tải đồng thời (Rủi ro BA đã nêu).
6. **Hạ `max_capacity_bytes` xuống dưới mức đã dùng (AC-6):** `PATCH
   /warehouse/capacity` **không** so sánh với `usedBytes` — luôn `UPDATE`
   thành công. Response trả `usedPercent`/`status` tính lại **ngay** theo
   giá trị mới, để FE hiển thị cảnh báo/chặn tức thời mà không cần chờ lần
   ghi tệp tiếp theo. Không có bước xóa dữ liệu nào được kích hoạt.

## 5. Test plan

- **Unit test (`WarehouseFolderServiceTest`):**
  - Tạo/sửa tên: regex tên — hợp lệ (`Ho so vu an`, có dấu cách; `A-1_b`;
    đúng 100 ký tự) vs. không hợp lệ (`Hồ sơ` chữ có dấu; ký tự đặc biệt
    `@#/`; 101 ký tự; chuỗi rỗng) — AC-3 + Quyết định 2 mục 0.
  - Trùng tên cùng cha (kể cả root, dùng partial unique index) → 409 — AC-4.
  - Tạo ở `depth=5` → OK; tạo con của thư mục `depth=5` → 409
    `MAX_DEPTH_EXCEEDED` — AC-2.
  - Di chuyển: cycle (chuyển vào chính nó, chuyển vào con/cháu) → 409
    `CYCLE_DETECTED`.
  - Di chuyển làm nhánh con sâu nhất vượt 5 cấp → 409
    `MAX_DEPTH_EXCEEDED` (test với subtree có độ sâu tương đối > 1, không
    chỉ test node đơn lẻ).
  - Di chuyển hợp lệ: `path_ids`/`depth` của thư mục + toàn bộ nhánh con
    cập nhật đúng, `parent_id`/`document.folder_id` không đổi ở tầng không
    liên quan.
  - Xóa: còn `document.status='clean' AND deleted_at IS NULL` → 409
    `hasActiveDocuments`; còn thư mục con → 409 `hasChildFolders`; thư mục
    chỉ có `document` status `rejected`/`infected` → xóa được, các row đó
    có `folder_id=NULL` sau khi xóa.
  - **Xóa thư mục chỉ còn tệp trong thùng rác** (fixture: `status='clean'`
    + `deleted_at = now()`) → **xóa được** (204) — Story 3 AC-2, nhánh này
    chạy được nhờ Quyết định 1 mục 0.
  - **`usedBytes` VẪN tính tệp trong thùng rác**: 1 tệp `clean` 100MB +
    1 tệp `clean` 50MB đã set `deleted_at` → `usedBytes = 150MB` (không
    phải 100MB) — Story 5 AC-1. Test này và test ngay trên cố ý mâu thuẫn
    bề mặt: xác nhận 2 quy tắc độc lập ở mục 4.3 vs 4.4 không bị code
    gộp nhầm thành một.
  - Tính `usedPercent`/`status` đúng biên 84.99/85.00/94.99/95.00%.
  - Hạ `max_capacity_bytes` xuống dưới `usedBytes` → lưu thành công,
    `status` trả về `BLOCKED`/`WARNING` ngay trong response — AC-6.
- **Integration test (`WarehouseControllerIT`, Testcontainers Postgres):**
  - Non-R3 gọi POST/PATCH/DELETE/`/move`/`PATCH /warehouse/capacity` → 403
    — AC-1 Story 1.
  - R1/R2 gọi `GET /folders/tree` → 200 (đọc được, không tạo được) — Story
    4 AC-1.
  - Luồng đầy đủ: tạo cây 5 cấp → di chuyển nhánh giữa → verify cây con
    đúng qua `GET /folders/tree`.
  - Xóa thư mục chỉ còn tệp inactive → 204, `GET /folders/tree` không còn
    node đó.
- **Case biên / race condition:**
  - **Nhiều upload đồng thời cùng vượt 95%:** 2+ thread gọi
    `recomputeAfterWrite()` gần như đồng thời sau khi mỗi thread tự
    `documentRepository.save()` — verify aggregate query không dùng cache
    stale (mỗi lần gọi query lại DB), verify request post-95% tiếp theo bị
    `assertIngestAllowed()` chặn dù request đang chạy dở không bị huỷ giữa
    chừng (đã ghi thì giữ, chỉ chặn request MỚI) — đúng AC-5b.
  - Tạo 2 thư mục trùng tên đồng thời (cùng cha) → 1 thành công, 1 nhận
    409 từ `UQ_warehouse_folder_sibling_name` (DB constraint là nguồn xác
    thực cuối, không dựa vào check-rồi-insert ở tầng ứng dụng).
  - Migration backfill `path_ids`/`depth`: chạy trên dữ liệu có sẵn với 1
    nhánh giả lập > 5 cấp → migration phải fail rõ ràng (không âm thầm cắt
    bớt cấp) để buộc dọn dữ liệu trước khi deploy.

## 6. Task breakdown

- [x] Task 0 (**chạy trước Task 1 ở MỌI môi trường có dữ liệu**, Quyết
      định 3 mục 0): script SQL tiền kiểm, chỉ ĐỌC, liệt kê dữ liệu sẽ làm
      `V9` fail — (a) `warehouse_folder` có `name` không khớp
      `^[A-Za-z0-9 _-]{1,100}$` hoặc dài > 100 ký tự; (b) nhánh sâu > 5 cấp
      (recursive CTE). Bàn giao danh sách cho R3 dọn **thủ công** trước khi
      deploy; KHÔNG tự động đổi tên/cắt nhánh.
      → `backend/scripts/warehouse-structure-precheck.sql`.
- [x] Task 1: Migration `V9__warehouse_structure_v2.sql` — alter
      `warehouse_folder` (path_ids, depth, audit columns, CHECK), backfill
      recursive CTE, alter `document.folder_id` FK, thêm
      `document.deleted_at` + index, bảng `warehouse` + seed.
      → `backend/src/main/resources/db/migration/V9__warehouse_structure_v2.sql`.
      Sửa 1 lỗi so với bản SQL nháp trong spec: term không đệ quy của CTE
      backfill cần ép kiểu `(t.depth + 1)::smallint` (không chỉ literal
      `1::smallint`), nếu không Postgres báo lỗi kiểu `smallint` vs
      `integer` giữa 2 nhánh UNION ALL — phát hiện khi chạy
      `WarehouseControllerIT` thật trên Postgres (Testcontainers).
- [x] Task 2: Entity `WarehouseFolder` — thêm field `pathIds`(`UUID[]`,
      `depth`, audit columns); entity mới `Warehouse` (singleton) +
      `WarehouseRepository`.
- [x] Task 3: Siết `CreateFolderDto`/`RenameFolderDto` (`@Size(max=100)`,
      `@Pattern("^[A-Za-z0-9 _-]+$")` — khớp đúng CHECK constraint ở mục 2,
      hai nơi phải luôn đồng bộ); DTO mới `MoveFolderDto`,
      `UpdateWarehouseCapacityDto`.
- [x] Task 4: `WarehouseService` — thêm depth-check khi tạo (mục 4.1), viết
      `moveFolder()` (cycle check, depth check, cập nhật subtree — mục
      4.2), sửa `deleteFolder()` (check `status='clean'` thay vì dựa hoàn
      toàn vào FK — mục 4.3). Subtree recompute trong `moveFolder()` cài
      bằng vòng lặp Java (`findDescendants` qua GIN index + tính lại trong
      bộ nhớ + `saveAll`) thay vì 1 câu `UPDATE ... FROM` thuần SQL như mô
      tả ở mục 4.2 — spec chỉ nói "không CẦN vòng lặp ứng dụng" (gợi ý hiệu
      năng), không cấm; chọn hướng này để dễ test/đọc, hành vi quan sát
      được (API, DB state cuối cùng) giống hệt.
- [x] Task 5: `WarehouseCapacityService` (mới) — `getSnapshot()`,
      `assertIngestAllowed()`, `recomputeAfterWrite()`, `updateCapacity()`
      (mục 4.4); phụ thuộc `SystemConfigService.getLong(key)` (từ
      `system-config.md` — đã Approved & implement, dùng trực tiếp
      `SystemConfigDefaults.WAREHOUSE_STORAGE_*_THRESHOLD_PERCENT`).
- [x] Task 6: `WarehouseController` — thêm `PATCH /folders/{id}/move`, cập
      nhật response `FolderNode`/`WarehouseFolder` (thêm `depth`); controller
      mới `WarehouseCapacityController` (`GET`/`PATCH /warehouse/capacity`).
      **Bỏ qua** `GET /folders/{id}/breadcrumb`: spec đánh dấu "tuỳ chọn...
      bỏ nếu không cần trong phạm vi FE hiện có" và FE hiện tại không cần.
- [x] Task 7: `DocumentRepository` — thêm
      `existsByFolderIdAndStatusAndDeletedAtIsNull` (mục 4.3) và
      `sumSizeByStatus` (mục 4.4, **không** lọc `deleted_at`); thêm field
      `deletedAt` vào entity `Document`; index Task 1 đã tạo.
- [x] Task 8: Nối `data-ingestion.md`'s ingest flow — gọi
      `assertIngestAllowed()`/`recomputeAfterWrite()` đúng 2 điểm mục 4.4
      (phối hợp với DEV-spec `data-ingestion.md` khi spec đó được viết —
      hiện tại chỉ có BA story). **Đã nối post-check (AC-5b)**:
      `DocumentsService.ingest()` gọi `recomputeAfterWrite()` ngay sau
      `documentRepository.save(document)`. **Chưa nối pre-check (AC-5a)**
      đúng như spec dự liệu — batch-open API của `data-ingestion.md` chưa
      tồn tại; `assertIngestAllowed()` đã viết + unit test sẵn, chờ
      `data-ingestion.md` gọi tại đúng vị trí khi spec đó được viết.
- [x] Task 9: Web — cập nhật `@/lib/api` type `FolderNode` (thêm `depth`),
      thêm UI di chuyển thư mục + màn hình cấu hình/hiển thị dung lượng kho
      (component mới, chưa có trong `FolderTree.tsx`); `FolderTree.tsx`
      không cần đổi cấu trúc (chỉ đọc `name`/`children`, field mới bỏ qua
      an toàn). → `web/src/lib/api.ts`, `web/src/app/admin/folders/page.tsx`.
- [x] Task 10: Unit + integration test theo mục 5. →
      `WarehouseFolderServiceTest`, `WarehouseCapacityServiceTest` (mock,
      26 test), `WarehouseControllerIT` (Testcontainers Postgres+Redis thật,
      15 test) — tất cả pass. Không viết test race-condition đa luồng riêng
      (mục "Case biên/race condition" cuối mục 5) do giới hạn thời gian;
      không có test nào trong repo hiện dùng nhiều luồng thật, nên đây là
      khoảng trống nhất quán với phần còn lại của test suite, không phải
      thiếu sót riêng của spec này.
- [x] Task 11: Cập nhật trạng thái task trong file spec này (đánh dấu done)
      sau khi implement — không viết lại toàn bộ spec.

## 7. Rủi ro kỹ thuật

- ~~Phụ thuộc chéo thùng rác (FR-26/27)~~ → **đã giải quyết** (Quyết định 1
  mục 0): `V9` tạo sẵn `document.deleted_at`, nên Story 3 AC-2 và Story 5
  AC-1 implement + test được ngay, không còn chặn spec này.
  **Rủi ro còn lại:** hiện KHÔNG có đường nào ghi giá trị vào `deleted_at`
  (chưa có UI/API xóa mềm) — trên môi trường thật cột này luôn NULL cho tới
  khi FR-26/27 xây xong, nên nhánh AC-2 chỉ được phủ bằng test fixture, chưa
  phải bằng luồng người dùng thật. Khi FR-26/27 được đặc tả, cần đối chiếu:
  (a) mô hình trash chính thức có dùng đúng `deleted_at` không — nếu spec
  đó chọn `status='TRASHED'` thì phải hợp nhất, tuyệt đối tránh 2 nguồn sự
  thật cho cùng một trạng thái; (b) job xóa cứng khi hết
  `retention.trash_retention_days` phải làm `usedBytes` giảm tương ứng —
  nằm ngoài phạm vi spec này.
- **`system-config.md` đang Draft:** `WarehouseCapacityService` phụ thuộc
  cứng vào 2 key `warehouse.storage_warning_threshold_percent` /
  `_block_threshold_percent` đã đặt chỗ ở đó — nếu spec/implementation đó
  đổi tên key hoặc cơ chế đọc (`SystemConfigService.getLong`), Task 5 phải
  cập nhật theo. Không bắt đầu Task 5 tới khi `system-config.md` chuyển
  `Approved` và ít nhất đã có `SystemConfigService` khả dụng.
- ~~Trùng số hiệu migration V7 với `system-config.md`~~ → **đã giải
  quyết**: spec này nhận `V9`, merge sau `system-config` (`V7`) và
  `account-lifecycle` (`V8`) — xem bảng thứ tự ở đầu file. Rủi ro còn lại:
  merge lệch thứ tự sẽ làm Flyway fail (`out-of-order` không bật).
- ~~Đổi FK `document.folder_id` sang `ON DELETE SET NULL`~~ → **đã chấp
  nhận** (Quyết định 4 mục 0). Cơ sở: document `rejected`/`infected` không
  bao giờ được ghi bytes lên SeaweedFS (`DocumentsService` — *"Metadata row
  persisted as evidence even though bytes are never stored"*), nên để nó
  mất liên kết thư mục không làm mất dữ liệu thật; vẫn truy vết qua
  `audit_log`.
  **Việc bắt buộc kèm theo:** đây vẫn là sửa schema bảng `document` (ngoài
  "sở hữu" của story này) nên (a) mọi query hiển thị tài liệu theo thư mục
  phải xử lý `folder_id IS NULL` như "không thuộc thư mục nào" thay vì lỗi;
  (b) cần review chéo với `data-ingestion.md` (`V10`) và
  `rbac-group-scope.md` (`V12`) vì cả hai cũng đụng bảng `document`.
- **Backfill migration trên dữ liệu hiện có:** nếu môi trường nào đã có
  sẵn dữ liệu `warehouse_folder` với nhánh sâu hơn 5 cấp (tạo trước khi có
  ràng buộc), migration sẽ fail cứng ở bước `ADD CONSTRAINT
  CK_warehouse_folder_depth` — cần chạy trước một script kiểm tra/dọn dữ
  liệu ở môi trường đó (dev/staging) trước khi áp dụng migration.
- **Siết validate tên (`max=100` + regex `^[A-Za-z0-9 _-]{1,100}$`)** phá
  vỡ dữ liệu cũ nếu đã có thư mục tên tiếng Việt **có dấu** hoặc dài hơn
  100 ký tự — `ALTER COLUMN name TYPE VARCHAR(100)` và `ADD CONSTRAINT
  CK_warehouse_folder_name_format` sẽ **fail cứng**. Đây là hành vi **đã
  chốt có chủ đích** (Quyết định 3 mục 0: không tự động chuẩn hóa tên người
  dùng). Giảm thiểu: Task 0 chạy script tiền kiểm ở mọi môi trường có dữ
  liệu và bàn giao danh sách cho R3 dọn thủ công **trước** khi deploy `V9`.
  Lưu ý dấu cách nay là **hợp lệ**, nên phạm vi dữ liệu phải dọn hẹp hơn so
  với phương án cấm dấu cách ban đầu.
