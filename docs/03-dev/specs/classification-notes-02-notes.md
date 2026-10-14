# Tech Spec: classification-notes-02-notes

> File: `docs/03-dev/specs/classification-notes-02-notes.md` — Owner: DEV
> Nguồn story: `docs/02-ba/stories/classification-notes-02-notes.md`
> Status: Approved

## 1. Tóm tắt kỹ thuật

Domain ghi chú (FR-16) đã có phần lớn hạ tầng trong code hiện tại (bảng
`document_note`, `NotesService`/`NotesController`, add/edit không xoá,
`expectedUpdatedAt` → 409, `NOTE_MAX_LENGTH = 4000`, gọi từ
`DocumentsService.ingest`). Spec này **không viết lại từ đầu** mà:

1. **Vá 2 lỗ hổng** khiến `NotesService.createForDocument`/`update` chưa đi
   qua `DocumentScopeGuard` (chỉ `listForDocument` đã có) — vi phạm Story 2
   AC-2 / Story 3 AC-2 (thêm/sửa ghi chú ngoài phạm vi phải bị từ chối +ghi
   `access.denied`).
2. **Bổ sung ghi outbox** `document.note.changed` trong cùng transaction với
   mỗi lần tạo/sửa ghi chú thành công, đúng ADR 7 — cơ chế đã **CHỐT** (xem
   mục 2.2, mục 8 câu hỏi #1 — đã trả lời).
3. **Xác nhận lại** (không sửa code) rằng nội dung ghi chú không lọt vào
   `audit_log` qua bất kỳ đường nào, **và bổ sung allowlist validator tự
   động** ở tầng `AuditService` — xem mục 8, câu hỏi mở #2 (đã trả lời).

Không đổi API surface, không đổi bảng `document_note`, không thêm bảng
revision, không thêm route DELETE, không đổi ma trận quyền
(`note.write`/`document.read` hiện có đã đúng: R1=CAN_BO có `note.write`, R2=
LANH_DAO chỉ có `document.read`, R3=QUAN_TRI không có `document.read` nên đã
tự động không đọc được nội dung ghi chú — xác nhận ở mục 4, không cần đổi).

## 2. Data model

### 2.1 `document_note` — giữ nguyên (đã có, `V5__init_document_notes.sql`)

Không có migration mới cho bảng này. Nhắc lại cấu trúc hiện có để đối chiếu:

```sql
document_note (
  id UUID PK, document_id UUID FK -> document(id) ON DELETE CASCADE,
  body TEXT NOT NULL,
  created_by_sub, created_by_username VARCHAR NOT NULL,
  updated_by_sub, updated_by_username VARCHAR NULL,
  created_at, updated_at TIMESTAMPTZ NOT NULL
)
-- optimistic lock: so sánh dto.expectedUpdatedAt với updated_at hiện tại
```

### 2.2 `outbox_event` — CHỐT (xem câu hỏi mở #1, mục 8 — đã trả lời)

**Quyết định sở hữu migration:** tại thời điểm spec này được duyệt, spec chị
em `docs/03-dev/specs/classification-notes-01-tags.md` **chưa chốt** việc ai
tạo bảng outbox (bản đọc gần nhất của tags-01 vẫn đề xuất bảng riêng
`classification_event_outbox` trong migration V19 của chính nó, đang chờ trả
lời câu hỏi mở #3 của tags-01). Do đó, theo nguyên tắc "ai chưa quyết định
thì spec này tự nhận trách nhiệm", **spec `classification-notes-02-notes`
(notes) là nơi tạo migration `outbox_event` dùng chung** (generic, 1 bảng
cho cả `document.note.changed` và `document.classification.changed`).
`classification-notes-01-tags.md` khi được cập nhật **phải bỏ** bảng
`classification_event_outbox` riêng của nó và **tham chiếu tới bảng
`outbox_event` này** (không tạo migration trùng, không tự tạo bảng thứ hai) —
nếu tags-01 được implement trước notes-02 trên cùng nhánh code, người
implement tags-01 cần kiểm tra migration `outbox_event` đã tồn tại chưa
trước khi đánh số Flyway tiếp theo, tránh trùng `V19`.

Migration `V19__init_outbox_event.sql` (điều chỉnh số hiệu nếu tại thời điểm
implement đã có `V19` khác), package mới `com.aismartstore.backend.outbox`
(hạ tầng dùng chung, không nằm trong `notes/` hay `tags/`):

```sql
CREATE TABLE outbox_event (
    id              BIGSERIAL PRIMARY KEY,
    event_type      VARCHAR(128) NOT NULL,   -- 'document.note.changed' | 'document.classification.changed'
    payload         JSONB NOT NULL,          -- {documentId, changedAt, changedBySub}
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    processed_at    TIMESTAMPTZ,             -- NULL = chưa được consumer/indexer tiêu thụ
    attempts        INT NOT NULL DEFAULT 0,
    last_error      TEXT
);

CREATE INDEX IDX_outbox_event_unprocessed
    ON outbox_event (event_type, created_at) WHERE processed_at IS NULL;
CREATE INDEX IDX_outbox_event_processed_at ON outbox_event (processed_at);
```

- Payload đúng hợp đồng ADR 7: `{documentId, changedAt, changedBySub}` —
  **không** chứa `body` ghi chú (indexer tự đọc lại `notes[]` từ DB theo
  `documentId` khi cần).
- Ghi 1 row/lần `note.create` hoặc `note.update` thành công, trong cùng
  `@Transactional` với việc lưu `document_note` (atomic ở tầng DB — không
  phải retry/queue cho consumer, chỉ đảm bảo không mất sự kiện nếu ghi chú
  đã lưu).
- **Chưa xây job consumer/publish** trong spec này (thuộc
  `ai-search-infra`/`search-fulltext`, ngoài phạm vi) — spec này chỉ đảm bảo
  ghi đúng contract.
- Retention (CHỐT): job định kỳ (mirror `UploadCleanupJob`/
  `DigitizationDispatchRetryJob` pattern có sẵn) xoá row có `processed_at` cũ
  hơn ngưỡng cấu hình qua `system_config`, key `classification.outbox_retention_days`
  (mặc định 30 ngày, **dùng chung cho cả 2 `event_type`, không tách theo
  domain**) — **chỉ xoá row đã xử lý**, row `processed_at IS NULL` không tự
  xoá (chưa có consumer) nhưng log cảnh báo nếu row cũ nhất chưa xử lý vượt
  ngưỡng (đề xuất 24h) để vận hành phát hiện backlog.
- **Ngoài phạm vi giai đoạn này (CHỐT):** chưa xây job publish/retry thật ra
  consumer/indexer — spec này chỉ đảm bảo ghi đúng row vào `outbox_event`
  trong transaction; polling/publish job để lại cho giai đoạn sau khi có
  consumer/indexer thật.

## 3. API contract

Không có endpoint mới. Bảng dưới liệt kê lại 3 route hiện có (không đổi
chữ ký), chỉ đổi hành vi nội bộ (mục 4):

| Method | Endpoint | Auth | Request | Response |
|---|---|---|---|---|
| GET | `/documents/{documentId}/notes` | `PERM_document.read` | — | `200` `DocumentNote[]` (đã sắp theo `createdAt` giảm dần); `404` nếu ngoài phạm vi/không tồn tại |
| POST | `/documents/{documentId}/notes` | `PERM_note.write` | `{ body: string(≤4000) }` | `200` `DocumentNote`; `400` body rỗng/quá 4000; `404` nếu ngoài phạm vi/không tồn tại (**mới**, xem mục 4) |
| PATCH | `/documents/{documentId}/notes/{noteId}` | `PERM_note.write` | `{ body: string(≤4000), expectedUpdatedAt?: OffsetDateTime }` | `200` `DocumentNote`; `400` validation; `404` ghi chú/tài liệu không thuộc phạm vi (**mới cho phần document**, xem mục 4) hoặc note không tồn tại; `409` `expectedUpdatedAt` không khớp |
| — | (không có `DELETE`) | — | — | Cố ý không tồn tại — test hồi quy xác nhận `405`/`404` |

Không có endpoint mới cho outbox (bảng nội bộ, không có route CRUD công khai
trong spec này).

## 4. Luồng xử lý chính

1. **Thêm ghi chú lúc nạp (Story 1).** `DocumentsController`/`IngestController`
   đã validate `note` ≤ 4000 ký tự qua `@Size` (đã có, `@Validated` cấp
   controller → `HandlerMethodValidationException` → `400`, không rơi vào
   nhánh tạo `document_note`). `DocumentsService.ingest` chỉ gọi
   `notesService.createForDocument(...)` **sau khi** tài liệu đã lưu/scan
   xong (dòng ~274-276, đã có) → nếu ingest thất bại ở bất kỳ bước trước đó,
   `createForDocument` không bao giờ được gọi → không có ghi chú mồ côi
   (AC-2 đã đúng theo code hiện tại, chỉ cần test hồi quy, không cần sửa).
2. **Thêm ghi chú cho tài liệu đã có (Story 2).**
   `NotesController.create` → **[MỚI]** gọi
   `documentScopeGuard.requireVisible(user, documentId)` trước khi tạo (thay
   vì chỉ `documentRepository.existsById`) → `404` giống hệt "không tồn
   tại" khi ngoài phạm vi (không tiết lộ, đúng pattern `DocumentScopeGuard`
   đã dùng ở `listForDocument`), tự động ghi `access.denied` qua guard →
   lưu `document_note` + **[MỚI]** ghi 1 row `outbox_event`
   (`event_type=document.note.changed`)
   trong cùng `@Transactional` → audit `note.create` (async, ngoài
   transaction, giữ nguyên như hiện tại, đi qua allowlist validator mới) với metadata
   `{documentId, noteId}` — **không** đưa `body` vào.
3. **Sửa ghi chú (Story 3).** `NotesController.update` → **[MỚI]** gọi
   `requireVisible(documentId)` **trước** khi tra `noteId` (để 404 không
   phân biệt "tài liệu ngoài phạm vi" và "ghi chú không tồn tại") → tra
   `document_note` theo `(id, documentId)` → so `expectedUpdatedAt` (đã có)
   → nếu khớp: cập nhật `body/updatedBy/updatedAt` + **[MỚI]** ghi
   outbox (`event_type=document.note.changed`) trong cùng `@Transactional`
   → audit `note.update` metadata
   `{documentId, noteId}` (đã đúng, giữ nguyên, đi qua allowlist validator mới).
4. **Xem ghi chú (Story 4).** `NotesController.list` → `requireVisible` (đã
   có, không đổi) → trả toàn bộ `document_note` của tài liệu. R3 (QUAN_TRI)
   **không có** `PERM_document.read` trong `role_permission` hiện tại
   (`V12__init_groups.sql`) nên bị `403` ở tầng `@PreAuthorize` trước khi
   vào service — **đã thoả AC-3, không cần sửa code**, chỉ thêm test hồi quy
   chống trôi dạt ma trận quyền về sau (nếu ai đó lỡ cấp `document.read` cho
   `QUAN_TRI` ở domain khác, test sẽ đỏ ngay).
5. **Đồng bộ tìm kiếm (Story 5).** Ghi/sửa outbox (bước 2, 3) là tất cả
   những gì domain này cam kết — consumer đọc `notes[] = {noteId, body,
   createdAt, updatedAt}` trực tiếp từ `document_note` theo `documentId` khi
   xử lý sự kiện (thuộc phạm vi khác). Vì outbox insert nằm **cùng
   transaction DB** với insert/update `document_note`, lỗi ở outbox = lỗi
   cùng tầng DB với chính thao tác ghi chú (rollback cả hai cùng lúc) —
   không vi phạm AC-2 Story 5 ("không thất bại chỉ vì hệ thống đồng bộ gặp sự
   cố"), vì AC-2 nói về consumer/indexer downstream, không phải về chính
   transaction ghi outbox.
6. **Allowlist validator cho `AuditService` (CHỐT, xem mục 8 câu hỏi #2).**
   Thêm ở `AuditService` (hoặc lớp gọi ngay trước khi ghi) một hằng số
   `Map<String, Set<String>> ALLOWED_METADATA_KEYS` khai báo tường minh theo
   `eventType`, ví dụ `"note.create" -> {"documentId", "noteId"}`,
   `"note.update" -> {"documentId", "noteId"}`. Trước khi build bản ghi
   `audit_log`: nếu `eventType` có mặt trong map này (opt-in — chỉ áp dụng
   cho các event đã khai báo, không phá vỡ các event khác trong hệ thống
   chưa được rà soát) và `metadata.keySet()` chứa khoá **không** nằm trong
   tập cho phép → **log `ERROR`** kèm `eventType` + danh sách khoá lạ (không
   log giá trị, tránh chính log lại rò rỉ dữ liệu) và **loại bỏ khoá đó khỏi
   `metadata` trước khi ghi** (không chặn toàn bộ audit — do
   `recordAsync` chạy async, không được phép ném lỗi làm ảnh hưởng luồng
   chính; drop-and-alert an toàn hơn throw-and-lose-audit). Việc này bổ sung
   cho, không thay thế, test hồi quy ở Task 5/6 mục 6.

## 5. Test plan

- **Unit — `NotesServiceTest`:**
  - `createForDocument` ném `NotFoundException` khi `documentId` ngoài
    phạm vi của `user` (mock `DocumentScopeGuard` ném); vẫn thành công khi
    trong phạm vi.
  - `update` gọi `requireVisible` trước khi tra `noteId`; ném
    `NotFoundException` khi ngoài phạm vi kể cả nếu `noteId` tồn tại.
  - `update` giữ nguyên hành vi 409 khi `expectedUpdatedAt` lệch (regression).
  - Audit entry của cả `note.create`/`note.update`: `metadata.keySet()` chỉ
    gồm `documentId`, `noteId` — không chứa `body` dưới bất kỳ khoá nào.
  - Sau `create`/`update` thành công, đúng 1 row
    `outbox_event` được ghi với `event_type=document.note.changed`,
    `payload` chỉ gồm `documentId/changedAt/changedBySub` — không có `body`.
  - Nếu insert `outbox_event` ném lỗi (giả lập ràng
    buộc DB), toàn bộ transaction rollback → `document_note` cũng không được
    lưu (kiểm chứng tính atomic).
- **Unit — `AuditServiceTest` (allowlist validator, mới):**
  - Gọi `recordAsync("note.create", Map.of("documentId", d, "noteId", n))` →
    ghi đủ 2 khoá, không log `ERROR`.
  - Gọi với metadata có thêm khoá lạ (ví dụ `"body"`) cho `eventType` đã khai
    báo trong `ALLOWED_METADATA_KEYS` → khoá lạ bị loại khỏi bản ghi đã lưu,
    có log `ERROR` chứa tên khoá lạ (không chứa giá trị), audit vẫn được ghi
    với các khoá hợp lệ còn lại.
  - `eventType` chưa khai báo trong `ALLOWED_METADATA_KEYS` (event của domain
    khác) → không bị validator can thiệp (giữ nguyên hành vi hiện có, xác
    nhận opt-in không phá vỡ event ngoài phạm vi notes).
- **Integration (Testcontainers, theo pattern `SystemConfigControllerIT`):**
  - `POST /documents/{id}/notes` bởi R1 với tài liệu ngoài phạm vi → `404`
    + 1 row `audit_log` `access.denied` (`reason=out_of_scope`).
  - `POST`/`PATCH` bởi R2 (LANH_DAO, không có `note.write`) → `403`.
  - `GET /documents/{id}/notes` bởi R3 (QUAN_TRI) → `403` (regression cho
    AC-3 Story 4).
  - `PATCH` với `expectedUpdatedAt` cũ → `409`, thông báo rõ ràng, `body`
    không đổi trong DB.
  - `PATCH` thành công → response có `updatedBySub/updatedByUsername/
    updatedAt` mới; UI hiển thị "sửa lần cuối bởi …" dựa trên field này.
  - `DELETE /documents/{id}/notes/{noteId}` → không có route khớp (`404`
    Spring MVC mặc định, không phải lỗi ứng dụng) — khẳng định lại AC-5
    Story 3.
  - Ingest kèm `note` > 4000 ký tự → `400`, không có row `document_note` nào
    được tạo cho batch đó (AC-2/AC-3 Story 1).
  - Case biên: tài liệu có 0 ghi chú → `GET` trả mảng rỗng, không lỗi.
- **Case biên/lỗi cần cover thêm:** `documentId`/`noteId` là UUID hợp lệ
  nhưng không tồn tại (khác với ngoài phạm vi) — cả hai đều phải trả `404`
  cùng dạng thông báo, không phân biệt được từ phía client.

## 6. Task breakdown

- [x] Task 1: Thêm `documentScopeGuard.requireVisible(user, documentId)` vào
      đầu `NotesService.createForDocument` (bỏ `documentRepository.existsById`
      cũ) và đầu `NotesService.update`, trước khi tra `noteId`.
- [x] Task 2: Bọc `createForDocument`/`update` bằng `@Transactional` để chuẩn
      bị cho việc ghi outbox atomic (Task 3) — làm được độc lập, không phụ
      thuộc câu hỏi mở, vì bản thân việc gói 2 lần `save()` hiện tại vào 1
      transaction không đổi hành vi quan sát được.
- [x] Task 3 (CHỐT — câu hỏi mở #1 mục 8 đã trả lời): **[cập nhật thực thi]**
      migration `outbox_event` (nay là `V19__init_outbox_event.sql`) và
      entity/repository `OutboxEvent` trong package `outbox` đã được tạo
      trước bởi spec chị em `classification-notes-01-tags.md` (implement
      trước notes-02 trên nhánh code này) — tái dùng nguyên trạng, không tạo
      trùng. `NotesService.createForDocument`/`update` ghi 1 row
      `outbox_event` (`event_type=document.note.changed`,
      `payload={documentId, changedAt, changedBySub}`, không có `body`)
      trong cùng `@Transactional` với việc lưu `document_note`.
- [x] Task 4 (CHỐT — câu hỏi mở #1 đã trả lời): **[cập nhật thực thi]**
      `OutboxRetentionJob` (`@Scheduled`, mirror `UploadCleanupJob`) đã được
      tạo trước bởi `classification-notes-01-tags.md` và dùng chung cho mọi
      `event_type` (không tách theo domain) — đã xác nhận áp dụng đúng cho
      `document.note.changed` mà không cần sửa logic job; không xây job
      publish/consumer thật ở giai đoạn này (ngoài phạm vi).
- [x] Task 5 (CHỐT — câu hỏi mở #2 đã trả lời): bổ sung `noteId` còn thiếu
      vào metadata audit hiện tại (`{documentId, noteId}`), thêm allowlist
      validator tự động ở `AuditService` (xem mục 4 bước 6, thiết kế
      `ALLOWED_METADATA_KEYS` theo `eventType`) + test khẳng định không có
      đường nào đưa `body` vào `audit_log`.
- [x] Task 6: Viết test hồi quy mục 5 không phụ thuộc outbox (scope guard,
      409, không có DELETE, R3 403, ingest note quá dài) — làm được ngay,
      không chờ câu hỏi mở.

## 7. Rủi ro kỹ thuật

| Rủi ro | Mức độ | Giảm thiểu |
|---|---|---|
| Thêm `requireVisible` vào `createForDocument` có thể ảnh hưởng luồng ingest (gọi ngay sau khi tài liệu vừa được tạo) | Thấp | Tài liệu vừa tạo có `uploaderSub = user.sub()` → `requireVisible` luôn khớp nhánh "chủ sở hữu", không throw; vẫn cần test tích hợp ingest kèm note để xác nhận không hồi quy |
| Spec `tags-01` chưa cập nhật tại thời điểm duyệt spec này — nếu bị implement trước mà không tham chiếu quyết định outbox dùng chung, có thể tạo trùng bảng (`classification_event_outbox` riêng + `outbox_event` chung) | Trung bình | Đã ghi rõ ở mục 2.2: notes-02 sở hữu migration `outbox_event`; tags-01 khi cập nhật phải bỏ bảng riêng và tham chiếu bảng này — người review/điều phối 2 spec cần xác nhận thứ tự implement hoặc merge hạ tầng outbox trước |
| `@Transactional` mới trên `update`/`createForDocument` gộp chung với insert outbox có thể làm transaction dài hơn nếu outbox insert chậm | Thấp | Cả hai đều insert đơn giản, không gọi I/O ngoài (không network call) trong transaction |
| Allowlist validator mới ở `AuditService` là opt-in theo `eventType` — nếu quên đăng ký `eventType` mới vào `ALLOWED_METADATA_KEYS`, validator không bảo vệ event đó | Thấp | Chỉ ảnh hưởng event ngoài phạm vi notes (hành vi hiện tại không đổi cho các event chưa đăng ký); ghi chú rõ trong code để domain khác tự đăng ký khi cần |

## 8. Câu hỏi mở — hồ sơ quyết định (đã được người dùng duyệt)

> Cả 2 câu hỏi dưới đây **đã được trả lời và chốt**. Giữ nguyên nội dung câu
> hỏi gốc làm hồ sơ tham chiếu (không xoá), chỉ thêm mục "**Quyết định**" ở
> cuối mỗi câu. Không còn mục nào BLOCKED trong mục 6 Task breakdown.

### Câu hỏi #1: Cơ chế outbox cho `document.note.changed` — ĐÃ TRẢ LỜI

Đây là điểm **phải đối chiếu** với spec chị em
`docs/03-dev/specs/classification-notes-01-tags.md` (đang được viết song
song bởi dev khác cho sự kiện `document.classification.changed`) — codebase
hiện **chưa có** bất kỳ bảng/cơ chế outbox nào (đã kiểm tra, không có kết
quả `grep -ri outbox`), nên đây là quyết định mới hoàn toàn, không phải kế
thừa.

- **Đề xuất của spec này:** một bảng `outbox_event` **generic** dùng chung
  cho cả 2 sự kiện (`event_type`, `payload JSONB`, `created_at`,
  `processed_at`, `attempts`, `last_error`) thay vì 2 bảng riêng — xem mục
  2.2 cho DDL đầy đủ.
- **Cần người dùng xác nhận (hoặc chọn phương án khác):**
  1. Dùng chung 1 bảng generic (đề xuất) **hay** mỗi domain một bảng riêng
     (`note_outbox_event` / `classification_outbox_event`)?
  2. Nếu dùng chung: **ai implement trước sẽ tạo migration** — cần đồng bộ
     với dev đang viết `classification-notes-01-tags.md` để tránh 2 migration
     trùng tên bảng/số hiệu Flyway. Đề xuất: người review 2 spec quyết định
     thứ tự implement, hoặc gộp migration outbox vào một PR hạ tầng dùng
     chung trước khi 2 domain implement riêng.
  3. **Retry:** spec này đề xuất **không xây dựng job publish/retry** ở giai
     đoạn này (chưa có consumer/indexer) — chỉ ghi row trong transaction.
     Xác nhận điều này đúng cho cả sự kiện `document.classification.changed`
     (tức là tags-01 cũng không cần retry job ở giai đoạn này)?
  4. **Retention cho bản ghi đã xử lý/quá hạn:** đề xuất tham số
     `system_config` mới `classification.outbox_retention_days` (mặc định
     30 ngày), job định kỳ xoá `processed_at` cũ hơn ngưỡng; **không** tự
     xoá row chưa xử lý (`processed_at IS NULL`), chỉ log cảnh báo khi
     backlog vượt 24h. Xác nhận giá trị mặc định và việc không tự xoá row
     chưa xử lý là chấp nhận được.

**Quyết định (đã duyệt):**
1. Dùng chung **1 bảng generic `outbox_event`** cho cả `document.note.changed`
   và `document.classification.changed` (không tách 2 bảng riêng).
2. **Ai tạo migration:** spec `classification-notes-02-notes` (spec này) tự
   nhận trách nhiệm tạo migration `outbox_event` (xem mục 2.2), vì tại thời
   điểm duyệt, spec chị em `classification-notes-01-tags.md` chưa chốt việc
   này. `classification-notes-01-tags.md` khi cập nhật phải bỏ bảng
   `classification_event_outbox` riêng của nó và tham chiếu bảng
   `outbox_event` — không tạo migration trùng.
3. **Retry/publish:** xác nhận **chưa xây job publish/retry thật** ở giai
   đoạn này cho cả 2 sự kiện (chưa có consumer/indexer) — chỉ ghi đúng row
   trong transaction; polling/publish job để lại cho giai đoạn sau.
4. **Retention:** tham số `system_config` `classification.outbox_retention_days`
   (mặc định 30 ngày, dùng chung không tách theo domain); chỉ xoá row đã xử
   lý và quá hạn; không tự xoá row chưa xử lý, chỉ log cảnh báo backlog.

### Câu hỏi #2: Audit log chỉ ghi metadata allowlist (`documentId`, `noteId`) — ĐÃ TRẢ LỜI

Đã rà soát code hiện có (`NotesService`, `ApiExceptionHandler`,
`AuditService`) và xác nhận **hiện tại không có đường nào** đưa nội dung
ghi chú (`body`) vào `audit_log`:

- `NotesService.createForDocument`/`update` chỉ gọi
  `auditService.recordAsync(...)` với `Map.of("documentId", ...)` — hard-code
  literal, không có `noteId` trong map hiện tại (chỉ `documentId`) mặc dù
  story yêu cầu cả `documentId` **và** `noteId`. → **cần sửa** để thêm
  `noteId` vào metadata (không phải câu hỏi mở, sẽ làm ở Task 1/6), nhưng
  nêu ở đây để xác nhận allowlist đúng là **chỉ** `{documentId, noteId}`,
  không thêm trường nào khác (ví dụ không thêm độ dài `body` hay hash của
  `body`).
- `ApiExceptionHandler.handleValidation` (bắt lỗi `@Size`/`@NotBlank`) chỉ
  ghép `field + ": " + defaultMessage` (message template cố định của
  Bean Validation, ví dụ "body: size must be between 0 and 4000") — **không**
  chèn giá trị thực tế của field vào message, nên không có đường rò rỉ `body`
  qua thông báo lỗi validate.
- Không tìm thấy exception nào trong luồng notes có message hoặc
  `getCause()` lồng nguyên văn `body` (không có custom exception nào bắt
  `body` làm tham số message).
- **(Ghi chú 2026-09-20) Kết luận "không có đường rò rỉ" ở trên giả định
  `ApiExceptionHandler` không có catch-all `Exception.class`** — đã cân nhắc
  thêm một cái (xem `audit-logging.md` §4.7) và **quyết định không thêm**,
  đúng lúc vì một catch-all dùng `ex.getMessage()` (pattern mọi handler khác
  trong file đó đang dùng) sẽ mở đúng đường rò rỉ này: nếu `NotesService`/
  `DocumentNoteRepository` ném một exception mà message chứa nguyên văn
  `body` (ví dụ một constraint DB tương lai in giá trị cột trong thông báo
  lỗi), catch-all sẽ vô tình trả nó thẳng cho người dùng. Nếu sau này có ai
  thêm catch-all, phải quay lại rà soát mục này.
- **Cần người dùng xác nhận:**
  1. Xác nhận việc dựa vào **review code thủ công + test hồi quy** (Task 5,
     mục 6: test khẳng định `audit_log.metadata` chỉ có 2 khoá) là đủ, hay
     cần thêm **cơ chế phòng vệ tự động** ở tầng `AuditService` (ví dụ một
     allowlist validator chặn ghi nếu metadata chứa khoá lạ, hoặc giới hạn
     độ dài giá trị metadata để một lỗi lập trình tương lai không thể vô
     tình nhét `body` 4000 ký tự vào audit log)?
  2. Xác nhận việc bổ sung `noteId` vào metadata hiện tại (đang thiếu) là
     đúng ý định ban đầu (story AC-4/AC-7 đều yêu cầu `documentId, noteId`).

**Quyết định (đã duyệt):**
1. **Cần bổ sung cơ chế phòng vệ tự động** — không chỉ dựa vào review code +
   test hồi quy. Thiết kế cụ thể (mục 4 bước 6, Task 5 mục 6): một allowlist
   validator ở tầng `AuditService`, hằng số
   `Map<String, Set<String>> ALLOWED_METADATA_KEYS` khai báo theo
   `eventType` (`"note.create"`/`"note.update"` → `{"documentId", "noteId"}`),
   áp dụng opt-in (chỉ chặn các `eventType` đã đăng ký); khoá lạ bị loại bỏ
   khỏi bản ghi + log `ERROR` (không log giá trị) thay vì ném lỗi làm mất
   toàn bộ audit entry (vì `recordAsync` chạy async).
2. Xác nhận bổ sung `noteId` vào metadata hiện tại là đúng ý định ban đầu —
   đã đưa vào Task 5 mục 6.
