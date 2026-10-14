# Tech Spec: classification-notes-01-tags

> File: `docs/03-dev/specs/classification-notes-01-tags.md` — Owner: DEV
> Nguồn story: `docs/02-ba/stories/classification-notes-01-tags.md`
> Nguồn kiến trúc: `docs/01-sa/architecture/classification-notes.md` (Quyết
> định 1, 2, 3, 4, 6, 7)
> Status: Approved

## 1. Tóm tắt kỹ thuật

Mở rộng module `tags/` hiện có (`Tag`, `DocumentTag`, `TagsService`,
`TagsController`, `DocumentTagsController`) thay vì viết mới:

- **Thu hẹp `TagKinds`** còn đúng `topic`/`doc_type` (bỏ `unit`, `secrecy`,
  đổi `doc-type` → `doc_type`); migration xoá thẳng dữ liệu 2 kind cũ,
  KHÔNG cần bước kiểm kê/export CSV trước xoá (dữ liệu demo, tương tự trường
  hợp `casefiles` ở V13 — **đã chốt**, xem mục 8 Câu hỏi mở #1).
- **Thêm `active` (boolean) vào `tag`** cho vòng đời "ngừng dùng" của
  `topic` (Quyết định 2) — không hard-delete nhãn đã gắn.
- **Thêm `tag_kind` (denormalize) vào `document_tag`** + partial unique
  index để ép "đúng 1 `doc_type`/tài liệu" ở tầng DB (Quyết định 3), tránh
  race condition nếu chỉ kiểm tra ở tầng service.
- **Bắt buộc `DocumentScopeGuard.requireVisible()`** ở
  `TagsService.assign/unassign/listForDocument` — hiện đang thiếu (lỗ hổng
  đã ghi trong SA Quyết định 4/Rủi ro mục 6).
- **Tự động gán `doc_type` khi nạp** trong `DocumentsService.ingestBatchFile`
  (nối tiếp chỗ đã gọi `notesService.createForDocument`), suy từ
  `AllowedFileFormat.category` đã có sẵn (`TEXT_DOC/IMAGE/AUDIO/VIDEO`) —
  **đã chốt tái dùng cột này, không tạo bảng ánh xạ riêng** (xem mục 8 Câu
  hỏi mở #2 cho cách xử lý MIME không map được).
- **Thêm `classification.allow_adhoc_topic_tag`** vào
  `SystemConfigDefaults` (BOOLEAN, default `false`), dùng
  `SystemConfigService.getBoolean(...)` giống các key khác.
- **Thêm API quản lý danh mục `topic`** (tạo/đổi tên/ngừng dùng — thay
  `DELETE /tags/{id}` hard-delete bằng deactivate) và **API báo cáo tổng hợp
  theo nhãn** (`GET /tags/report`) luôn lọc sau `BusinessDataScope`.
- **Sự kiện `document.classification.changed`** phát khi gán/gỡ nhãn hoặc
  sửa `doc_type`, ghi vào bảng **`outbox_event` dùng chung (generic)** —
  **đã chốt** dùng chung 1 bảng với sự kiện `document.note.changed` của
  `classification-notes-02-notes.md` thay vì bảng riêng
  `classification_event_outbox` (xem mục 8 Câu hỏi mở #3 và mục 2 Data
  model cho chi tiết quyền tạo migration/số hiệu Flyway). Giai đoạn này
  **chỉ ghi outbox đúng contract**, chưa xây job publish/retry thật (chưa
  có consumer/indexer) — out-of-scope, để lại việc sau (xem Task breakdown
  mục 6, Task 9).
- **Migration backfill `doc_type`** cho tài liệu cũ (one-off) — hình thức
  runner cụ thể (script Flyway riêng/`CommandLineRunner`/job) theo câu hỏi
  mở của story (chưa lặp lại ở đây), nhưng **nội dung ánh xạ MIME →
  doc_type đã chốt dùng chung cột `allowed_file_format.category`** (mục 8
  Câu hỏi mở #2).

Không đổi: mô hình `document_note`/`NotesService` (thuộc
`classification-notes-02-notes.md`), n-n phẳng của `document_tag`, không
phân cấp nhãn, danh mục toàn cục.

## 2. Data model

```sql
-- Migration V20__classification_tags.sql (sau V19__init_outbox_event.sql —
-- xem ghi chú thứ tự Flyway ở bước 6 bên dưới; đã CHỐT, không còn là đề xuất)
-- Đã CHỐT (Câu hỏi mở #1, mục 8): xoá thẳng dữ liệu 2 kind cũ, không cần
-- export CSV/kiểm kê trước xoá (dữ liệu demo, tương tự `casefiles` V13).

-- 1. Xoá liên kết + nhãn của 2 kind bị loại bỏ.
DELETE FROM document_tag USING tag
    WHERE document_tag.tag_id = tag.id AND tag.kind IN ('unit', 'secrecy');
DELETE FROM tag WHERE kind IN ('unit', 'secrecy');

-- 2. Đổi 'doc-type' -> 'doc_type' cho nhất quán (Quyết định 1).
UPDATE tag SET kind = 'doc_type' WHERE kind = 'doc-type';

-- 3. Thu hẹp ràng buộc kind còn 2 giá trị + thêm active cho vòng đời topic.
ALTER TABLE tag ADD COLUMN active BOOLEAN NOT NULL DEFAULT true;
ALTER TABLE tag ADD CONSTRAINT CHK_tag_kind CHECK (kind IN ('topic', 'doc_type'));

-- 4. Chuẩn hoá + unique không phân biệt hoa/thường trên (kind, label) cho
--    topic (Rủi ro mục 6 SA — chống phân mảnh danh mục). label lưu bản đã
--    trim/gộp khoảng trắng ở tầng service; unique index dùng lower(label)
--    để bắt các bản ghi cũ chưa chuẩn hoá.
DROP INDEX IF EXISTS ...; -- xem ghi chú: UQ_tag_kind_label (V4) đã tồn tại
                          -- trên (kind, label) phân biệt hoa/thường; cần
                          -- CREATE UNIQUE INDEX mới trên (kind, lower(label))
                          -- và drop UQ cũ, sau khi xử lý trùng lặp hiện có
                          -- (nếu có) — chi tiết xử lý dữ liệu trùng để DEV
                          -- quyết định khi implement (không phải câu hỏi mở
                          -- bắt buộc vì không ảnh hưởng AC nghiệp vụ).

-- 5. document_tag: denormalize tag_kind, ép đúng 1 doc_type/tài liệu.
ALTER TABLE document_tag ADD COLUMN tag_kind VARCHAR(16);
UPDATE document_tag dt SET tag_kind = t.kind FROM tag t WHERE t.id = dt.tag_id;
ALTER TABLE document_tag ALTER COLUMN tag_kind SET NOT NULL;
CREATE UNIQUE INDEX UQ_document_tag_one_doc_type
    ON document_tag (document_id)
    WHERE tag_kind = 'doc_type';

-- 6. Outbox cho document.classification.changed — ĐÃ CHỐT (Câu hỏi mở #3,
--    mục 8): dùng bảng GENERIC `outbox_event` dùng chung với
--    `document.note.changed` (classification-notes-02-notes.md), KHÔNG tạo
--    bảng riêng `classification_event_outbox` như đề xuất ban đầu.
--
--    Quyết định thứ tự Flyway (để tránh xung đột số hiệu giữa 2 spec):
--    - `V19__init_outbox_event.sql` tạo bảng `outbox_event` — thuộc
--      `classification-notes-02-notes.md` (spec đó đã đề xuất DDL này ở
--      mục 2.2, giữ nguyên không đổi). Spec này KHÔNG tạo lại bảng, chỉ
--      tham chiếu/insert vào bảng đã tồn tại.
--    - `V20__classification_tags.sql` (chính migration của spec này, đã
--      đổi số hiệu từ V19 → V20 để nhường V19 cho outbox) chạy SAU khi
--      `outbox_event` đã tồn tại.
--    - Nếu 2 dev implement song song: người implement `outbox_event`
--      trước tạo migration V19; người còn lại chỉ tái sử dụng bảng, không
--      tạo lại.
--
--    DDL bảng outbox_event (tham khảo, KHÔNG lặp lại tạo ở migration này):
--    outbox_event(id BIGSERIAL PK, event_type VARCHAR(128), payload JSONB,
--      created_at TIMESTAMPTZ, processed_at TIMESTAMPTZ NULL,
--      attempts INT DEFAULT 0, last_error TEXT)
--    Ghi 1 row với event_type = 'document.classification.changed',
--    payload = {documentId, changedAt, changedBySub}.

-- 7. Backfill doc_type cho tài liệu cũ — ánh xạ MIME → doc_type ĐÃ CHỐT
--    dùng cột `allowed_file_format.category` (Câu hỏi mở #2, mục 8), không
--    tạo bảng ánh xạ riêng. SQL/script cụ thể (batch, runner) làm ở bước
--    implement (Task 7, mục 6) — không phải câu hỏi mở nữa.
```

```
Entity changes:
- Tag: + boolean active (getter/setter), kind giới hạn còn topic|doc_type.
- DocumentTag: + String tagKind.
- TagKinds: TOPIC="topic", DOC_TYPE="doc_type" (bỏ UNIT, SECRECY; ALL = List.of(TOPIC, DOC_TYPE)).
- Không có entity ánh xạ MIME→doc_type riêng (đã chốt tái dùng
  AllowedFileFormat.category — Câu hỏi mở #2).
- OutboxEvent (entity dùng chung, package `com.aismartstore.backend.outbox`,
  định nghĩa ở `classification-notes-02-notes.md` cùng migration V19):
  spec này chỉ dùng lại `OutboxEventRepository.save(...)` để ghi
  `event_type='document.classification.changed'`, không định nghĩa lại
  entity.
```

## 3. API contract

| Method | Endpoint | Request | Response | Ghi chú |
|---|---|---|---|---|
| `GET` | `/tags?kind=` | query `kind` optional | `Tag[]` (không lọc phạm vi — AC-3 US4) | Giữ nguyên, không đổi quyền |
| `POST` | `/tags` | `{kind, label}` | `Tag` | Chỉ còn dùng để tạo `topic` (validate `kind == "topic"`; tạo `doc_type` bị từ chối 400 — US3 AC-6) |
| `PATCH` | `/tags/{id}` | `{label}` | `Tag` | **Mới** — đổi tên `topic` (US3 AC-2); 404 nếu không tồn tại; 400 nếu `kind != topic`; 409 nếu trùng tên (chuẩn hoá) |
| `POST` | `/tags/{id}/deactivate` | (không body) | `Tag` (active=false) | **Thay thế** `DELETE /tags/{id}` cũ (US3 AC-3/AC-4); 400 nếu `kind != topic` |
| `POST` | `/documents/{documentId}/tags` | `{tagId}` | 201 | Thêm `DocumentScopeGuard.requireVisible` trước khi gán; nếu `tagKind=doc_type` và tài liệu đã có 1 `doc_type` khác → thay thế (xoá cũ, gán mới trong 1 transaction, US2 AC-3) thay vì 409 |
| `DELETE` | `/documents/{documentId}/tags/{tagId}` | — | 204 | Thêm `DocumentScopeGuard.requireVisible`; nếu gỡ `doc_type` mà không gán lại → chặn (400, "doc_type là bắt buộc") theo US2 AC-2, trừ khi gọi kèm gán mới cùng lúc (xem luồng 4.3) |
| `GET` | `/documents/{documentId}/tags` | — | `Tag[]` | Thêm `DocumentScopeGuard.requireVisible` |
| `GET` | `/documents?tagId=&...` | (đã có) | — | Không đổi, đã lọc phạm vi qua `BusinessDataScope` (`DocumentsService.list`) |
| `GET` | `/tags/report` | query `kind` (`topic`\|`doc_type`, optional), `from`, `to` (ISO date, optional), `folderId` (optional) | `[{tagId, kind, label, count}]` | **Mới** (US5) — luôn `BusinessDataScope` trước `GROUP BY tag`; R3 → 403 (`@PreAuthorize` permission mới hoặc tái dùng permission đọc tài liệu + chặn role ở controller, xem Task breakdown) |
| `GET` | `/documents/unclassified-doc-type` | phân trang | `Document[]` (tối thiểu `id, originalName, mimeType`) | **Mới** (US2 AC-5/AC-6) — danh sách tài liệu (mới nạp lẫn backfill) đang thiếu `doc_type` do MIME không map được, để R1 xử lý thủ công. Hình thức chính xác (route riêng vs. cờ lọc trong `GET /documents`) chốt ở bước implement theo "Câu hỏi mở cho DEV" của story (không phải câu hỏi mở bắt buộc của spec này) |

Quyền (`@PreAuthorize`):
- `PERM_tag.manage`: tạo/đổi tên/ngừng dùng `topic`, assign/unassign
  (giữ nguyên permission hiện có — R1=X theo ma trận FR-15).
- `PERM_document.read`: `GET /tags`, `GET /documents/{id}/tags`,
  `GET /documents?tagId=`.
- Báo cáo (`GET /tags/report`): cần permission mới hoặc kiểm tra vai trò
  ở tầng controller để chặn R3 (US5 AC-3) — permission model cụ thể (map
  R1/R2 vào permission nào) làm rõ khi đọc `rbac-group-scope.md`/permission
  catalogue lúc implement, không phải quyết định nghiệp vụ mới.

## 4. Luồng xử lý chính

### 4.1 Gán/gỡ nhãn `topic` (US1)

1. `DocumentTagsController.assign` nhận `{documentId, tagId}`.
2. `TagsService.assign`: gọi `documentScopeGuard.requireVisible(user, documentId)`
   trước (mới) — 404 đồng nhất nếu ngoài phạm vi (AC-5), audit
   `access.denied` tự động qua guard.
3. Tải `Tag` theo `tagId`; nếu không tồn tại → 404 ("nhãn không tồn tại",
   AC-3 nhánh từ chối). Nếu `active = false` → 400 ("nhãn đã ngừng sử dụng",
   AC-2).
4. `documentTagRepository.insert(...)` kèm `tagKind = tag.getKind()`; unique
   violation trên `(document_id, tag_id)` → 409 (đã gán rồi).
5. Audit `tag.assign` với metadata `{documentId, tagId}` (đã có, giữ
   nguyên).
6. Ghi 1 row vào bảng dùng chung `outbox_event`
   (`event_type='document.classification.changed'`,
   `payload={documentId, changedAt, changedBySub}`) trong cùng transaction
   (đã chốt thiết kế, xem mục 2 Data model + mục 8 Câu hỏi mở #3).

Gỡ nhãn (`unassign`): tương tự bước 2 (guard), sau đó
`deleteByDocumentIdAndTagId`; nếu `tagKind = doc_type`, chặn nếu đây là thao
tác gỡ đơn lẻ không kèm gán thay thế (US2 AC-2) — xem 4.3.

### 4.2 Tạo nhãn ad-hoc lúc gán (US1 AC-3, US3)

1. Nếu FE gửi `label` thay vì `tagId` (hoặc endpoint riêng — quyết định UI
   ở BA, DEV chọn 1 trong 2 khi implement): đọc
   `classification.allow_adhoc_topic_tag` qua `SystemConfigService.getBoolean(...)`.
2. `false` (mặc định) → 400 "nhãn không tồn tại".
3. `true` → tạo `Tag(kind=topic, label, active=true)` (chuẩn hoá trim/gộp
   khoảng trắng, check unique không phân biệt hoa/thường) rồi gán luôn,
   cùng 1 transaction với bước gán.

### 4.3 Tự động gán `doc_type` khi nạp (US2 AC-1/AC-2/AC-5)

1. Trong `DocumentsService.ingestBatchFile`, ngay sau khi
   `document.setMimeType(mimeType)` và document đã persist (chỗ hiện đang
   gọi `notesService.createForDocument`), gọi
   `docTypeAssignmentService.assignOnIngest(document)`.
2. Tra `mimeType` → nhóm `doc_type` qua `AllowedFileFormat.category` (đã
   chốt tái dùng, Câu hỏi mở 2). Nếu map được: tìm/khởi tạo
   `Tag(kind=doc_type, label=<nhóm>)` (4
   nhãn seed cố định, không tạo mới) rồi `documentTagRepository.insert(...)`
   với `tagKind=doc_type` — không qua `DocumentScopeGuard` (context nội bộ
   hệ thống, không phải request người dùng cuối).
3. Nếu **không map được**: không tạo dòng `document_tag`; thay vào đó ghi
   nhận tài liệu vào cơ chế "cần R1 phân loại thủ công" (bảng cờ hoặc query
   động `NOT EXISTS document_tag WHERE tag_kind=doc_type` — chọn ở Task
   breakdown) — **không** tự đoán giá trị (AC-5).
4. R1 sửa `doc_type` (US2 AC-3): endpoint gán (4.1) với `tagKind=doc_type` —
   service tự động gỡ dòng `document_tag` cũ có `tag_kind=doc_type` của
   tài liệu đó (nếu có) trong cùng transaction trước khi insert dòng mới,
   đảm bảo bất biến "đúng 1 doc_type" mà không cần người gọi tự gỡ trước.

### 4.4 Migration backfill `doc_type` cho tài liệu cũ (US2 AC-6)

1. Chạy sau migration thêm ràng buộc (V20, mục 2 data model) nhưng **trước
   khi coi chuẩn hoá dữ liệu là hoàn tất** — hình thức (script Flyway
   riêng/`CommandLineRunner`/job) và cách chạy theo batch quyết định ở
   bước implement (câu hỏi mở của story, không phải của spec này).
2. Với mỗi `document` chưa có `document_tag(tag_kind=doc_type)`: tra
   `mimeType` qua `AllowedFileFormat.category` (đã chốt, cùng ánh xạ ở
   4.3); map được → insert; không map được → thêm vào danh sách "cần R1 xử
   lý thủ công" (US2 AC-6) — **cùng cơ chế hiển thị** với danh sách ở 4.3
   bước 3 (một nguồn dữ liệu duy nhất, không tạo 2 luồng riêng).

### 4.5 Báo cáo tổng hợp theo nhãn (US5)

1. `GET /tags/report`: resolve `BusinessDataScope.forDocuments(user.sub(), ...)`
   trước.
2. Query: `document_tag JOIN tag JOIN document` với predicate scope ở
   trên + filter `kind`, `createdAt BETWEEN from/to`, `folderId` (nếu có).
3. `GROUP BY tag.id, tag.kind, tag.label` → trả `count`.
4. Không cache kết quả theo user (số liệu phụ thuộc phạm vi từng người
   gọi — Quyết định 4 SA); chỉ danh mục nhãn (`GET /tags`) mới cache Redis.

### 4.6 Outbox → sự kiện `document.classification.changed` (ĐÃ CHỐT)

1. Mọi điểm ghi/xoá `document_tag` (assign, unassign, auto-assign lúc nạp,
   sửa `doc_type`) ghi thêm 1 row vào bảng **dùng chung** `outbox_event`
   (`event_type='document.classification.changed'`,
   `payload={documentId, changedAt, changedBySub}`, `processed_at=NULL`)
   trong cùng transaction nghiệp vụ — bảng này dùng chung với
   `document.note.changed` của `classification-notes-02-notes.md` (xem mục
   2 Data model cho quyết định số hiệu migration).
2. **Out-of-scope giai đoạn này:** không xây job publish/retry (poll
   `outbox_event`, gửi ra message broker/consumer) — chưa có
   consumer/indexer downstream để tiêu thụ sự kiện. Đây KHÔNG phải việc bị
   blocked chờ quyết định, mà là quyết định rõ ràng: để lại cho giai đoạn
   sau khi có consumer. Task breakdown (mục 6, Task 9) chỉ bao gồm việc ghi
   đúng row, không bao gồm job publish.
3. **Retention (trong scope giai đoạn này):** thêm tham số `system_config`
   mới `classification.outbox_retention_days` (mặc định `30`). Một job
   định kỳ (mirror `UploadCleanupJob`) xoá các row `outbox_event` có
   `processed_at IS NOT NULL` và `processed_at` quá hạn theo tham số này.
   **Không** tự xoá row `processed_at IS NULL` (chưa xử lý) — chỉ log cảnh
   báo nếu row chưa xử lý cũ nhất vượt ngưỡng backlog (đề xuất 24h), để vận
   hành phát hiện backlog do chưa có consumer. Job retention này dùng
   chung cho cả 2 event type, implement 1 lần (ưu tiên bởi spec nào
   implement trước, spec còn lại tái sử dụng — tương tự cách chia migration
   ở mục 2).

## 5. Test plan

- **Unit test**
  - `TagsService`: gán/gỡ topic hợp lệ; gán nhãn `active=false` → 400; gán
    nhãn không tồn tại với `allow_adhoc=false` → 400, với `=true` → tạo +
    gán; gán ngoài phạm vi → 404 (mock `DocumentScopeGuard` ném
    `NotFoundException`); đổi tên `topic` giữ nguyên `document_tag`; ngừng
    dùng `topic` không xoá liên kết; các thao tác trên `kind=doc_type` qua
    API danh mục (`POST/PATCH/deactivate`) → 400.
  - `DocTypeAssignmentService` (mới): MIME map được → đúng 1 nhãn; MIME
    không map được → không tạo `document_tag`, xuất hiện ở danh sách cần xử
    lý thủ công; sửa `doc_type` thay thế đúng 1 nhãn cũ bằng mới trong 1
    transaction.
  - Chuẩn hoá tên `topic` (trim, gộp khoảng trắng, so khớp không phân biệt
    hoa/thường) khi tạo/đổi tên.
- **Integration test** (Testcontainers Postgres, theo mẫu `*IT` hiện có)
  - Partial unique index `UQ_document_tag_one_doc_type`: insert 2
    `doc_type` cho cùng `document_id` ở tầng DB → vi phạm ràng buộc (test
    race condition không bị service-layer che khuất).
  - `DocumentScopeGuard` ở `assign/unassign/listForDocument`: user ngoài
    phạm vi nhận 404 giống hệt "tài liệu không tồn tại" + có bản ghi audit
    `access.denied`.
  - Migration V20: sau khi chạy trên dataset có `kind IN ('unit','secrecy')`,
    bảng `tag`/`document_tag` không còn dòng nào thuộc 2 kind đó; ràng buộc
    CHECK có hiệu lực (không cần dữ liệu export/kiểm kê, xoá thẳng theo
    quyết định đã chốt).
  - `GET /tags/report`: 2 user khác phạm vi thấy count khác nhau cho cùng
    nhãn; R3 gọi → 403.
  - `GET /tags` (danh mục): không lọc phạm vi, R1/R2 thấy cùng danh sách.
  - Outbox: assign/unassign/sửa `doc_type` → có đúng 1 row `outbox_event`
    (`event_type='document.classification.changed'`, `processed_at=NULL`)
    trong cùng transaction; **không** test job publish (out-of-scope giai
    đoạn này); retention job xoá đúng row `processed_at` quá hạn, không xoá
    row chưa xử lý, log cảnh báo backlog.
  - Backfill: chạy migration/job trên tài liệu cũ có MIME map được (qua
    `allowed_file_format.category`) và MIME không map được → đúng kỳ vọng
    ở mục 4.4.
- **Case biên / lỗi cần cover**
  - Gán `doc_type` khi tài liệu đã có `doc_type` khác → thay thế, không
    lỗi 409 (khác hành vi với `topic` là 409 khi gán trùng).
  - Gỡ `doc_type` đơn lẻ (không kèm gán thay thế) → chặn (400), vì
    `doc_type` bắt buộc luôn tồn tại (US2 AC-2).
  - Tên `topic` trùng sau chuẩn hoá (khác hoa/thường, thừa khoảng trắng) →
    409 khi tạo hoặc đổi tên.
  - Insert `outbox_event` thất bại (giả lập lỗi ràng buộc DB) → toàn bộ
    transaction rollback, cả `document_tag` lẫn `outbox_event` đều không
    được lưu (tính atomic, cùng pattern với `classification-notes-02-notes.md`);
    không có khái niệm "publish lỗi làm fail request" vì giai đoạn này
    chưa có bước publish (out-of-scope).

## 6. Task breakdown

- [ ] Task 1: Migration `V20__classification_tags.sql` (chạy sau
      `V19__init_outbox_event.sql`, xem mục 2) — thu hẹp `TagKinds`, thêm
      `active`, denormalize `tag_kind` + partial unique index, xoá thẳng
      `unit`/`secrecy` (không cần export/kiểm kê — đã chốt, Câu hỏi mở 1).
- [ ] Task 2: Cập nhật `Tag`, `DocumentTag`, `TagKinds`, `TagRepository`,
      `DocumentTagRepository` cho các cột/kind mới.
- [ ] Task 3: Thêm `DocumentScopeGuard.requireVisible` vào
      `TagsService.assign/unassign/listForDocument`.
- [ ] Task 4: API quản lý danh mục `topic` — `PATCH /tags/{id}`,
      `POST /tags/{id}/deactivate`, validate `kind` ở `POST /tags`.
- [ ] Task 5: Thêm `classification.allow_adhoc_topic_tag` vào
      `SystemConfigDefaults` + luồng tạo ad-hoc lúc gán.
- [ ] Task 6: `DocTypeAssignmentService` — tự động gán khi nạp (nối
      `DocumentsService.ingestBatchFile`), thay thế khi R1 sửa, danh sách
      "cần phân loại thủ công"; ánh xạ MIME→doc_type dùng
      `AllowedFileFormat.category` (đã chốt, Câu hỏi mở 2).
- [ ] Task 7: Migration/script backfill `doc_type` cho tài liệu cũ, dùng
      cùng ánh xạ `AllowedFileFormat.category` ở Task 6 (đã chốt bảng ánh
      xạ, Câu hỏi mở 2); hình thức runner cụ thể theo câu hỏi mở của story.
- [ ] Task 8: `GET /tags/report` — query tổng hợp theo `BusinessDataScope`,
      chặn R3.
- [ ] Task 9: Outbox — ghi 1 row vào bảng dùng chung `outbox_event`
      (`event_type='document.classification.changed'`) trong cùng
      transaction tại các điểm assign/unassign/auto-assign/sửa `doc_type`.
      Nếu migration `V19__init_outbox_event.sql` chưa tồn tại (chưa được
      tạo bởi `classification-notes-02-notes.md`), Task này tự tạo migration
      đó trước (đã chốt DDL ở mục 2 Data model). **KHÔNG** bao gồm job
      publish/retry (out-of-scope giai đoạn này, không phải blocked).
- [ ] Task 9b: `OutboxRetentionJob` (mirror `UploadCleanupJob`, dùng chung
      cho cả 2 event type) xoá row `outbox_event` đã `processed_at` quá hạn
      theo `system_config` mới `classification.outbox_retention_days`
      (mặc định 30); log cảnh báo backlog nếu row chưa xử lý cũ nhất vượt
      ngưỡng (đề xuất 24h). Nếu job này đã được tạo bởi
      `classification-notes-02-notes.md`, Task này chỉ cần bổ sung test
      cho event type của spec này, không tạo job trùng.
- [ ] Task 10: Test plan mục 5 (unit + integration).

## 7. Rủi ro kỹ thuật

- Đổi `DELETE /tags/{id}` (hard-delete) sang `POST /tags/{id}/deactivate`
  là **breaking change** cho FE hiện có — cần rà soát toàn bộ nơi gọi API
  cũ trước khi xoá route.
- Backfill quy mô lớn (toàn bộ tài liệu cũ) chạy đồng bộ trong migration
  Flyway có thể khoá bảng `document`/`document_tag` lâu nếu dữ liệu lớn —
  cân nhắc batch + chạy ngoài giờ cao điểm (chi tiết ở câu hỏi mở của story,
  không lặp lại ở đây).
- Outbox mới hoàn toàn, chưa có tiền lệ trong codebase (khác
  `digitization` dùng job đối soát thay outbox) — **đã chốt** dùng bảng
  generic `outbox_event` dùng chung với `classification-notes-02-notes.md`
  (Câu hỏi mở 3). Rủi ro còn lại: migration `V19__init_outbox_event.sql`
  chỉ được tạo **một lần** dù 2 spec cùng phụ thuộc — nếu 2 dev implement
  song song mà không phối hợp, có thể tạo trùng migration/entity; giảm
  thiểu bằng cách kiểm tra `git log`/branch của nhau trước khi tạo migration
  V19, và Task 9/9b (mục 6) đã ghi rõ "nếu đã tồn tại thì tái sử dụng".
  Rủi ro phụ: chưa xây job publish/consumer ở giai đoạn này nghĩa là sự
  kiện `document.classification.changed` chưa thực sự được tiêu thụ bởi
  downstream (search/indexer) cho tới khi có ticket riêng — chấp nhận được
  vì đây là quyết định phạm vi rõ ràng (out-of-scope), không phải thiếu sót.
- Reuse `allowed_file_format.category` cho `doc_type` tạo phụ thuộc ngầm
  giữa 2 slug (`data-ingestion` và `classification-notes`) — nếu
  `data-ingestion` đổi giá trị `category` trong tương lai mà không báo,
  mapping `doc_type` gãy theo; cần ghi chú phụ thuộc này ở cả hai spec khi
  implement.

## 8. Câu hỏi mở — ĐÃ DUYỆT (giữ lại làm hồ sơ quyết định)

Cả 3 câu hỏi dưới đây **đã được người dùng duyệt**; không còn là điều kiện
chặn implement. Nội dung câu hỏi gốc được giữ nguyên để tra cứu, kèm
**Quyết định** ngay bên dưới mỗi câu.

- [x] **1. Quy trình migration xoá `kind IN ('unit','secrecy')` ở môi
      trường đang chạy.** SA Rủi ro mục 6 yêu cầu "kiểm kê số liên kết
      trước khi xoá; export CSV các liên kết bị xoá kèm migration". Cần
      người dùng xác nhận: (a) có bắt buộc chạy script kiểm
      kê/export CSV trước mỗi lần deploy migration lên môi trường có
      dữ liệu thật không, hay chỉ áp dụng cho môi trường hiện tại (được
      xác nhận là dữ liệu demo, tương tự trường hợp `casefiles` ở V13);
      (b) nếu cần export, lưu file CSV ở đâu (đính kèm ticket vận hành,
      hay ghi vào một bảng audit riêng); (c) có cần một bước "dry-run"
      (đếm số dòng bị ảnh hưởng, không xoá) tách biệt khỏi migration Flyway
      thật hay gộp chung.

      > **Quyết định:** BỎ bước export CSV/kiểm kê bắt buộc — coi dữ liệu
      > hiện tại là dữ liệu demo, giống trường hợp `casefiles` ở V13; chạy
      > xoá thẳng trong migration `V20__classification_tags.sql` (không có
      > bước 0 kiểm kê riêng, không dry-run tách biệt). Không cần lưu
      > CSV/bảng audit riêng cho lần xoá này.

- [x] **2. Bảng ánh xạ MIME → `doc_type` cụ thể + xử lý MIME không map
      được.** Đề xuất kỹ thuật: tái dùng cột `allowed_file_format.category`
      đã có (`TEXT_DOC→văn bản, IMAGE→hình ảnh, AUDIO→âm thanh,
      VIDEO→video`) thay vì tạo bảng ánh xạ mới, vì mọi tài liệu nạp mới
      đã bắt buộc đi qua allowlist này (FR-12). Cần người dùng xác nhận:
      (a) đồng ý dùng lại `category` thay vì bảng ánh xạ độc lập; (b) với
      **tài liệu cũ** (backfill) có `mime_type` **không còn tồn tại** trong
      `allowed_file_format` hiện tại (MIME đã bị gỡ khỏi allowlist, hoặc
      lưu ở định dạng khác trước đây, ví dụ chữ hoa/thường, alias MIME) —
      liệt kê rõ các MIME này (cần chạy một truy vấn kiểm kê thực tế trên
      dữ liệu hiện có trước khi implement) để R1 xử lý thủ công, không tự
      suy đoán; (c) hình thức hiển thị danh sách "cần phân loại thủ công"
      (API riêng `GET /documents/unclassified-doc-type` như đề xuất ở mục
      3, hay tích hợp vào màn hình/API khác).

      > **Quyết định:** CHỐT tái dùng cột `allowed_file_format.category`
      > (mục (a)); KHÔNG tạo bảng ánh xạ riêng mới — đã cập nhật đồng bộ ở
      > mục 2 (Data model) và mục 3 (API contract). MIME không map được (cả
      > lúc nạp mới và lúc backfill) đưa vào danh sách "cần R1 xử lý thủ
      > công" qua `GET /documents/unclassified-doc-type` (mục (c) — giữ đề
      > xuất route riêng ở mục 3 API contract). Việc chạy truy vấn kiểm kê
      > thực tế các MIME không map được trên dữ liệu hiện có (mục (b)) vẫn
      > thực hiện ở bước implement Task 7 (mục 6), không phải quyết định
      > kiến trúc cần chờ thêm.

- [x] **3. Cơ chế outbox cụ thể cho `document.classification.changed`.**
      Đề xuất khung bảng `classification_event_outbox` ở mục 2 (data
      model) — cần người dùng xác nhận trước khi implement: (a) tên/cấu
      trúc bảng outbox có chấp nhận được không, hay dùng cơ chế khác (ví
      dụ tái dùng một bảng outbox dùng chung nếu dự án có kế hoạch outbox
      tổng cho nhiều domain, tránh mỗi slug tự tạo 1 bảng outbox riêng);
      (b) chính sách retry (số lần tối đa, backoff cố định hay tăng dần,
      ngưỡng chuyển sang `failed` để cảnh báo vận hành thay vì retry vô
      hạn); (c) retention cho bản ghi `published` (giữ bao lâu trước khi
      dọn) và cho bản ghi `failed` quá hạn (có cảnh báo/alert riêng không);
      (d) cơ chế publish thực tế — job polling định kỳ (mẫu
      `DigitizationDispatchRetryJob`) có đủ đáp ứng NFR "eventual
      consistency" (Quyết định 7 SA) hay cần hàng đợi (RabbitMQ, đã có
      `RabbitConfig` cho digitization) ngay từ đầu.

      > **Quyết định:** (a) BÁC BỎ bảng riêng `classification_event_outbox`
      > — dùng 1 bảng **generic** dùng chung `outbox_event(event_type,
      > payload JSONB, created_at, processed_at, attempts, last_error)` cho
      > cả `document.classification.changed` (spec này) và
      > `document.note.changed` (`classification-notes-02-notes.md`). Thứ
      > tự Flyway: `V19__init_outbox_event.sql` tạo bảng (đã cập nhật ở mục
      > 2 Data model, kèm quy tắc "ai implement trước tạo migration, người
      > sau tái sử dụng"); migration riêng của spec này đổi số hiệu thành
      > `V20__classification_tags.sql` để tránh xung đột.
      > (b)+(d) Chính sách retry/publish thật (job polling, backoff,
      > `failed` status) **chưa xây ở giai đoạn này** — chưa có
      > consumer/indexer để tiêu thụ sự kiện. Đây là quyết định phạm vi
      > (out-of-scope giai đoạn này), không phải bị blocked; để lại việc
      > sau (đã ghi rõ ở Task 9, mục 6). Vì vậy bảng cũng không cần cột
      > `status`/`failed` như đề xuất ban đầu — chỉ cần `processed_at
      > NULL/NOT NULL` là đủ cho giai đoạn này.
      > (c) Retention: tham số `system_config` mới
      > `classification.outbox_retention_days` (mặc định 30 ngày) — job
      > định kỳ chỉ xoá row đã xử lý (`processed_at IS NOT NULL`) và quá
      > hạn; **không** tự xoá row chưa xử lý, chỉ log cảnh báo backlog nếu
      > row chưa xử lý cũ nhất vượt ngưỡng (đề xuất 24h). Job này dùng
      > chung cho cả 2 event type (Task 9b, mục 6).
