# Tech Spec: trash-search-index-cleanup

> File: `docs/03-dev/specs/trash-search-index-cleanup.md` — Owner: DEV
> Nguồn: **Bug fix** (không xuất phát từ BA story). Xác minh 2026-09-20 trên
> stg (12 dòng `outbox_event` tồn đọng từ 2026-09-16, tăng dần). Tài liệu
> nguồn đã đọc:
> - `docs/03-dev/specs/search-fulltext-04-dong-bo-chi-muc.md` §4.2/§4.3/§4.4
> - `docs/03-dev/specs/delete-restore-trash-01-xoa-mem-thung-rac.md` §4.2
> Status: Approved
>
> - **2026-09-21 (3) — BUG-002 đã fix trong code:** `contentVersion` (nguồn
>   version thật từ Postgres, không phải `outbox_event.id`) và
>   `VersionType.ExternalGte` đã triển khai trong
>   `DocumentIndexFieldsBuilder`/`SearchIndexOutboxPoller`/`DocumentIndexer`/
>   `SegmentIndexer` — `handleDocumentRestored` (mục 2, mục 4 Task 4 của file
>   này) nay dùng đúng nguồn version đã sửa, không còn nguy cơ PARK oan mô tả
>   ở các mục dưới. Chi tiết: `bugs/bug-002-search-index-version-conflict.md`
>   mục 6.
> - **2026-09-21 (2) — duyệt lại:** người dùng đã đọc lại đoạn sửa mục 2 và
>   mục 4 Task 4 (BUG-002) và chốt tiếp tục **Approved** — bug xác nhận
>   **chưa fix trong code** (đã fix ở mục trên, cùng ngày), sẽ triển khai fix
>   riêng sau, không chặn ở bước tài liệu.
> - **2026-09-21:** hạ từ `Approved` về `Draft` — mục 2 và mục 4 Task 4 mô tả
>   sai: khẳng định `externalVersion = event.getId()` "tự bảo vệ không bị ghi
>   đè lùi" cho `handleDocumentRestored`. Thực tế `event.getId()` không đảm
>   bảo tăng đơn điệu theo thứ tự XỬ LÝ của cùng 1 tài liệu (hàng đợi ưu tiên,
>   retry, nhiều instance), và cơ chế "bỏ qua êm khi xung đột version" của
>   `DocumentIndexer.upsertDocument()` (mà `handleDocumentRestored` dùng lại)
>   bị hỏng do bắt sai loại exception — đã tái hiện thật trên stg 20/09/2026.
>   Chi tiết đầy đủ + hướng fix (**đã triển khai trong code**, xem entry đầu
>   file này, 2026-09-21 (3)): `bugs/bug-002-search-index-version-conflict.md`.
>   Đã sửa mục 2 và mục 4.

## 0. Tóm tắt bug

`TrashService.writeTrashOutboxEvent`/`writeRestoredOutboxEvent`
(`backend/src/main/java/com/aismartstore/backend/trash/TrashService.java:523-544`)
ghi hai loại `outbox_event`:

| event_type | Nơi ghi | Payload |
|---|---|---|
| `document.trash` | `TrashService:523` (khi chuyển 1..N tài liệu vào thùng rác) | `{documentIds: string[], changedAt}` |
| `document.restored` | `TrashService:534` (khi phục hồi 1 tài liệu) | `{documentId, changedAt}` |

Ngoài ra `TrashPurgeService.writePurgedOutboxEvent`
(`backend/src/main/java/com/aismartstore/backend/trash/purge/TrashPurgeService.java:221-231`)
ghi thêm `document.purged` (`{documentId, changedAt}`) khi hết hạn giữ và bị
xoá cứng.

Cả ba loại đều **không** nằm trong `HANDLED_EVENT_TYPES` của
`SearchIndexOutboxPoller` (file:
`backend/src/main/java/com/aismartstore/backend/search/index/SearchIndexOutboxPoller.java:53-59`,
hiện chỉ có `document.classification.changed`, `document.note.changed`,
`document.ingested`, `review.confirmed`, `review.reconfirmed`,
`document.acl.changed`) → rơi vào nhánh `default -> log.warn(...)` ở
`processOne` (dòng 200) **thực ra không bao giờ chạy tới nhánh đó**, vì
`claimNextForIndexing` chỉ claim theo `HANDLED_EVENT_TYPES` — các dòng
`document.trash`/`document.restored`/`document.purged` không bao giờ được
claim, tồn đọng vĩnh viễn với `processed_at IS NULL`, `attempts = 0` (không
bao giờ tăng, không bao giờ bị "parked", nên `SearchIndexReconciliationJob`
hiện tại — vốn chỉ đếm parked rows — cũng im lặng bỏ qua).

Nguyên nhân gốc: `search-fulltext-04-dong-bo-chi-muc.md` được viết **sau**
`delete-restore-trash-01` (tài liệu đó ghi rõ ở §4.2: "Không có module
`search`/OpenSearch/... nào tồn tại trong code hiện tại" tại thời điểm viết),
nên danh sách `HANDLED_EVENT_TYPES` không bao giờ được đối chiếu lại với các
event trash/restore/purge đã có sẵn.

Hệ quả: chỉ mục OpenSearch (`document`, `segment`) chỉ tăng, không bao giờ
giảm khi tài liệu bị xoá mềm/xoá cứng. Hiện **không rò rỉ dữ liệu** ra người
dùng cuối nhờ 2 lớp lọc độc lập:
- `SearchResultHydrator` (`backend/.../search/SearchResultHydrator.java`) —
  hydrate lại kết quả từ Postgres, tài liệu không còn tồn tại/đã xoá bị loại.
- `BusinessDataScope` — điều kiện `cb.isNull(deletedAt)` áp cho mọi truy vấn
  nghiệp vụ.

Đây là lớp bảo vệ **duy nhất**; spec này vá lỗ hổng ở gốc (dọn index đúng
lúc) thay vì tiếp tục phụ thuộc hoàn toàn vào 2 lớp lọc trên.

## 1. Tóm tắt kỹ thuật

Bổ sung 3 event type còn thiếu vào `SearchIndexOutboxPoller`:

- `document.trash` → xoá hẳn mọi `segment` + `document` của từng
  `documentId` trong payload khỏi index (không đánh dấu field, xem §2 lý do).
- `document.restored` → tái tạo lại index cho `documentId` bằng đúng logic
  hiện có của `document.ingested`/`review.confirmed` (đọc lại Postgres, upsert
  từ đầu) — **không** cố "un-delete" bằng cách gỡ một field, vì index đã bị
  xoá hẳn ở bước trash.
- `document.purged` → xoá hẳn (cùng code path với `document.trash`, phòng
  hờ trường hợp tài liệu bị purge trực tiếp mà không qua bước trash trước đó,
  và để dọn sạch trường hợp event `document.trash` bị lỡ nhịp trước khi
  purge diễn ra — idempotent nên gọi lại vô hại).

Đồng thời sửa `SearchIndexReconciliationJob` để không báo "drift" giả với
tài liệu đã bị trash (Postgres vẫn còn `content_segment` nhưng index nay
rỗng theo đúng thiết kế mới), và viết migration/script backfill xử lý 12
dòng tồn đọng trên stg.

## 2. Data model

**Không cần thay đổi mapping/schema của index `document`/`segment`.** Chọn
chiến lược **xoá hẳn document khỏi index** (không thêm field
`deletedAt`/`isDeleted` để lọc ở query time).

Lý do (trade-off):

| Phương án | Ưu | Nhược |
|---|---|---|
| **Xoá hẳn (chọn)** | Không đổi mapping; tái dùng nguyên xi `SegmentIndexer.deleteByRunId` (đổi query field) + 1 method mới tương tự cho `document` index; không cần sửa `SearchServiceImpl`/query time; nhất quán với cách `review.confirmed` đã "xoá run cũ" | Khi restore phải index lại từ đầu (chấp nhận được: tần suất restore thấp hơn nhiều so với search, và logic re-index đã có sẵn, tái dùng nguyên) |
| Đánh dấu field + lọc ở query | Restore nhanh (không cần re-index) | Phải sửa mapping (thêm field, reindex toàn bộ dữ liệu cũ để field có giá trị mặc định), sửa mọi query hiện có (`SearchServiceImpl`) để luôn thêm điều kiện lọc — thêm một lớp lọc thứ 3 dễ quên, đúng loại rủi ro spec này đang muốn giảm chứ không muốn thêm |

Chọn xoá hẳn cho cả `document.trash` và `document.purged`. Cho
`document.restored`, xử lý đối xứng: **không** có "un-delete" — poller build
lại toàn bộ document + segment từ Postgres (tái dùng
`DocumentIndexFieldsBuilder` + `contentSegmentRepository` như
`handleReviewConfirmed`/`handleDocumentIngested` đã làm).

**(Sửa 2026-09-21 — BUG-002, xem `bugs/bug-002-search-index-version-conflict.md`,
đã fix trong code):** câu gốc "dùng `externalVersion = event.getId()` như
mọi handler khác nên không cần lo ghi đè lùi" **sai** — `event.getId()`
không đảm bảo tăng đơn điệu theo thứ tự xử lý thật của cùng 1 tài liệu, và
cơ chế "bỏ qua êm khi xung đột version" của `DocumentIndexer.upsertDocument()`
(mà `handleDocumentRestored` gọi lại) bị hỏng do bắt sai loại exception. Nếu
1 tài liệu bị xoá rồi khôi phục rất nhanh (đụng tombstone 60s của
OpenSearch), hoặc event trước đó của cùng tài liệu tự đụng version của chính
nó khi retry, `handleDocumentRestored` **trước đây** có thể bị PARK vĩnh
viễn — tài liệu không bao giờ được khôi phục lại trong tìm kiếm dù thao tác
"khôi phục" báo thành công. Đã fix: `externalVersion` nay lấy từ
`contentVersion` (độ mới dữ liệu thật trong Postgres) + `VersionType.ExternalGte`
trên `DocumentIndexer`/`SegmentIndexer` — xem file bug mục 5.2.

## 3. API contract

Không có thay đổi API công khai. Đây là thay đổi nội bộ ở
`SearchIndexOutboxPoller`/`SegmentIndexer`/`DocumentIndexer`.

| Method | Endpoint | Request | Response |
|---|---|---|---|
| — | — | — | — |

## 4. Luồng xử lý chính

### 4.1 `SearchIndexOutboxPoller` — event types mới

```java
static final String EVENT_DOCUMENT_TRASHED = "document.trash";
static final String EVENT_DOCUMENT_RESTORED = "document.restored";
static final String EVENT_DOCUMENT_PURGED = "document.purged";

static final List<String> HANDLED_EVENT_TYPES = List.of(
        EVENT_CLASSIFICATION_CHANGED, EVENT_NOTE_CHANGED, EVENT_DOCUMENT_INGESTED,
        EVENT_REVIEW_CONFIRMED, EVENT_REVIEW_RECONFIRMED, EVENT_ACL_CHANGED,
        EVENT_DOCUMENT_TRASHED, EVENT_DOCUMENT_RESTORED, EVENT_DOCUMENT_PURGED);
```

`processOne` switch thêm 3 nhánh:

```java
case EVENT_DOCUMENT_TRASHED -> handleDocumentTrashed(payload);
case EVENT_DOCUMENT_RESTORED -> handleDocumentRestored(payload, externalVersion);
case EVENT_DOCUMENT_PURGED -> handleDocumentPurged(payload);
```

### 4.2 `handleDocumentTrashed` (event `document.trash`)

1. Đọc `payload.documentIds` (List<String>, có thể nhiều id trong 1 event —
   khác mọi handler khác hiện có chỉ xử lý 1 `documentId`/event; tối đa
   `TrashService.MAX_BATCH_SIZE` = 500 id/event).
2. Gọi `deleteDocumentsFromIndex(documentIds)` (helper dùng chung với
   `handleDocumentPurged`, xem §4.5) — **xoá theo lô bằng 1 câu `terms`
   query cho cả danh sách**, KHÔNG loop gọi API riêng cho từng id:
   a. `segmentIndexer.deleteByDocumentIds(documentIds)` — 1 lời gọi
      `deleteByQuery` với `terms` filter trên `document_id` cho toàn bộ
      danh sách, xoá mọi segment (content/note/title) của tất cả document
      trong batch khỏi index `segment`.
   b. `documentIndexer.deleteByIds(documentIds)` — 1 lời gọi `deleteByQuery`
      tương tự trên index `document` (field `document_id` cũng tồn tại ở
      đây, xem `documentFields()`), xoá toàn bộ document trong batch.
3. Không cần đọc `DocumentIndexFieldsBuilder` (không upsert gì, chỉ xoá) —
   khác các handler khác.
4. Không cần kiểm tra `externalVersion`/version conflict vì `deleteByQuery`
   không tranh chấp version như `upsertDocument`/`upsertSegments`
   (idempotent tự nhiên: xoá 1 document đã không còn tồn tại trong index là
   no-op an toàn, `terms` filter chứa id đã xoá rồi cũng không lỗi gì).
5. **Lý do chọn batch bằng `terms` query thay vì loop per-id (quyết định đã
   chốt sau review):** loop per-id x 2 lời gọi/id với batch tối đa 500 id sẽ
   tạo tới 1000 round-trip HTTP tuần tự trong CÙNG 1 event/transaction —
   đúng loại "poison event" mô tả ở §7 rủi ro 3 (một timeout giữa chừng bắt
   retry lại từ đầu toàn bộ 1000 lời gọi). Gộp thành 2 lời gọi tổng (1 cho
   segment, 1 cho document) loại bỏ rủi ro này gần như hoàn toàn — kích
   thước 1 `terms` filter với 500 UUID string là không đáng kể so với giới
   hạn request của OpenSearch.
6. Nếu OpenSearch lỗi ở 1 trong 2 lời gọi (segment hoặc document) → toàn bộ
   event fail, retry nguyên event ở vòng poll sau (idempotent nên an toàn
   chạy lại `terms` query dù một phần đã xoá thành công lượt trước).

### 4.3 `handleDocumentRestored` (event `document.restored`)

1. Đọc `payload.documentId`.
2. Tái sử dụng đúng logic `handleDocumentIngested` +
   `handleReviewConfirmed` để build lại toàn bộ dữ liệu tìm kiếm của tài
   liệu từ Postgres:
   - `fieldsBuilder.build(documentId)` — nếu rỗng (tài liệu đã bị purge sau
     khi restore, race hiếm) → no-op, return.
   - Upsert `document` (giống `handleDocumentIngested`).
   - Với mỗi `DigitizationRun` có `reviewState = REVIEW_CONFIRMED` của
     document này (có thể nhiều `task`: OCR/STT/SED...), upsert lại toàn bộ
     `content_segment` không bị `isDeleted()` (giống thân vòng lặp trong
     `handleReviewConfirmed`).
   - Upsert lại segment `content_source='note'` nếu có note hiện hành
     (giống `handleNoteChanged`).
   - Upsert lại segment `content_source='title'` (giống
     `handleDocumentIngested`).
3. Trích xuất phần dùng chung của `handleDocumentIngested`/
   `handleReviewConfirmed`/`handleNoteChanged` thành các private helper
   (`indexDocumentAndTitleSegment`, `indexConfirmedSegmentsForAllTasks`,
   `indexNoteSegmentIfAny`) để `handleDocumentRestored` gọi lại, tránh copy
   nguyên văn 3 khối logic.
4. `externalVersion` như thường lệ (giá trị chính xác: xem mục 2 ở trên, đã
   sửa 2026-09-21 — không phải `event.getId()`) — nếu một
   `document.classification.changed`/`acl.changed` khác đến sau nhưng được
   xử lý trước (race hiếm), external versioning tự bảo vệ không bị ghi đè
   lùi **với điều kiện dùng đúng nguồn version đã sửa**; xem
   `bugs/bug-002-search-index-version-conflict.md` cho lý do
   `event.getId()` (bản gốc) không đủ an toàn.

### 4.4 `handleDocumentPurged` (event `document.purged`)

Giống hệt bước xoá của `handleDocumentTrashed` nhưng payload chỉ có 1
`documentId` (không có mảng `documentIds`):

1. Gọi `deleteDocumentsFromIndex(List.of(documentId))` — dùng chung helper
   §4.2/§4.5 (danh sách 1 phần tử vẫn chạy qua đúng `terms` query, không
   cần nhánh code riêng cho "1 id").
2. Idempotent với `handleDocumentTrashed` đã chạy trước đó (document đã
   không còn trong index → `terms` query không match gì, no-op) — cover cả
   trường hợp lỡ nhịp `document.trash` (poller down đúng lúc) mà
   `document.purged` vẫn dọn sạch được sau 30 ngày.

### 4.5 Method mới trên `SegmentIndexer`/`DocumentIndexer`

- `SegmentIndexer.deleteByDocumentIds(List<UUID> documentIds)` — 1
  `deleteByQuery` với `terms` filter trên `document_id` cho toàn bộ danh
  sách (khác `deleteByRunId` hiện có là `term` đơn — ở đây dùng `terms` để
  gộp nhiều id trong 1 câu query), `refresh(true)` như `deleteByRunId`.
- `DocumentIndexer.deleteByIds(List<UUID> documentIds)` — tương tự, 1
  `deleteByQuery` trên index `document` với `terms` filter trên field
  `document_id` (field này đã có sẵn trong mapping, xem `documentFields()`
  — **không** dùng API `delete` đơn lẻ theo `_id` để tránh phải loop).
  Lưu ý triển khai: API `deleteByQuery` của OpenSearch client **không ném
  exception** khi không có document nào khớp filter — trả về response bình
  thường với `deleted=0` — nên không cần try/catch "not found" nào cả
  (khác suy đoán ban đầu ở bản nháp trước của spec này).
- `handleDocumentTrashed`/`handleDocumentPurged` dùng chung 1 private
  helper `deleteDocumentsFromIndex(List<UUID> documentIds)` trong
  `SearchIndexOutboxPoller` gọi cả 2 method trên, tránh lặp code.

### 4.6 `SearchIndexReconciliationJob` — tránh false positive drift

Vấn đề: job hiện tại (`run()` dòng 85-114) lấy
`findDistinctDocumentIdByReviewState(REVIEW_CONFIRMED, ...)` — **không lọc
theo `document.deletedAt`** — rồi so `expected` (đếm `content_segment` chưa
xoá trong Postgres, không đổi khi trash) với `actual` (đếm trong OpenSearch,
nay sẽ về 0 sau khi tài liệu bị trash). Nếu không sửa, mọi tài liệu bị trash
nhưng còn `purgeAfter` chưa tới sẽ bị báo "drift" mỗi đêm.

Sửa: thêm điều kiện lọc `document.deletedAt IS NULL` vào câu query
`findDistinctDocumentIdByReviewState` (đổi từ `@Query` JPQL thuần
`DigitizationRun` sang join `Document` bằng `documentId`, hoặc thêm
`@Query` mới `findDistinctDocumentIdByReviewStateAndNotTrashed` — ưu tiên
sửa tại chỗ vì không nơi nào khác gọi method cũ, kiểm tra bằng
`grep -rn findDistinctDocumentIdByReviewState`). Tài liệu đã bị trash được
loại khỏi vòng lặp đối soát hoàn toàn — không so sánh, không có gì để báo.

## 5. Test plan

### Unit test (`SearchIndexOutboxPollerTest` hoặc file tương đương hiện có)

- `document.trash` với 1 `documentId` trong payload → verify
  `segmentIndexer.deleteByDocumentIds`/`documentIndexer.deleteByIds` được
  gọi đúng 1 lần với danh sách `[documentId]`.
- `document.trash` với nhiều `documentId` (batch xoá cùng lúc từ UI, kể cả
  case gần MAX_BATCH_SIZE=500) → verify cả hai method trên vẫn chỉ được
  gọi **đúng 1 lần mỗi method**, với đúng toàn bộ danh sách id (không loop
  gọi API riêng cho từng id).
- `document.trash` khi document không tồn tại trong index (chưa từng được
  index, ví dụ trash ngay sau khi ingest fail) → không throw, `terms` query
  không match gì, coi là no-op.
- `document.restored` → verify document + mọi segment (title, note, mỗi
  `content_segment` confirmed chưa xoá của mọi `task`) được upsert lại đúng
  như `handleDocumentIngested`/`handleReviewConfirmed`/`handleNoteChanged`
  đơn lẻ đã test trước đó (so sánh field-by-field).
- `document.restored` khi `fieldsBuilder.build()` rỗng (document đã bị purge
  ngay sau khi restore — race hiếm) → no-op, không throw.
- `document.purged` → verify cùng hành vi xoá như `document.trash`
  (dùng chung helper).
- `document.purged` gọi sau khi `document.trash` đã xoá thành công (trường
  hợp bình thường) → `deleteByQuery` với `terms` filter không match gì, vẫn
  trả response bình thường (`deleted=0`), không throw.
- `HANDLED_EVENT_TYPES` chứa đủ 9 event type (test danh sách tường minh, để
  lần sau thêm event type BA/DEV không quên cập nhật).

### Integration test (Testcontainers OpenSearch, theo `project_running_integration_tests` — cần Docker daemon)

- Seed 1 document + segment trong index thật → gửi event `document.trash` →
  poll → assert `GET` cả 2 index trả `not_found`.
- Seed lại (test case trên) → gửi tiếp `document.restored` → poll → assert
  document + segment xuất hiện lại đúng nội dung hiện hành trong Postgres.
- `document.purged` trên document chưa từng bị trash (purge trực tiếp, race
  hiếm nhưng có thể xảy ra nếu vận hành chỉnh `purgeAfter` thủ công) → assert
  index bị xoá sạch.

### Backfill 12 dòng tồn đọng trên stg

- Trước khi deploy, đếm riêng theo từng `event_type` (không chỉ gộp chung)
  để biết quy mô thực tế của cả 3 loại — `document.trash` (đã biết 12 dòng),
  `document.restored`, và `document.purged` (§7 — có thể lớn hơn, chưa từng
  được xử lý từ trước tới nay):
  `SELECT event_type, count(*) FROM outbox_event WHERE event_type IN
  ('document.trash','document.restored','document.purged') AND processed_at
  IS NULL GROUP BY event_type`.
- Test/script khô (dry-run) đếm tổng số dòng `outbox_event` có
  `event_type IN ('document.trash','document.restored','document.purged')
  AND processed_at IS NULL` trước và sau khi poller mới lên — kỳ vọng về 0
  sau tối đa `poll-interval-ms * (số dòng / BATCH_LIMIT)`.
- Case dòng `document.trash` tồn đọng nhưng `documentId` bên trong đã bị
  `document.purged` từ lâu (document không còn tồn tại theo nghĩa nào, ví dụ
  bị purge thủ công ngoài luồng) → xử lý vẫn phải no-op, không throw, không
  chặn các dòng tồn đọng khác trong cùng batch.
- Case tồn đọng có cả `document.trash` và `document.restored` cho **cùng
  1 documentId** (trash rồi restore trong lúc bug còn tồn tại, cả 2 event
  đều chưa được xử lý) — id claim theo thứ tự `id ASC` nên `document.trash`
  luôn được xử lý trước `document.restored` tương ứng (ghi outbox tuần tự
  theo thời gian xảy ra) → kết quả cuối cùng phải là **document tồn tại
  trong index** (đúng trạng thái hiện tại ở Postgres), verify bằng cách chạy
  poller tới khi hàng đợi rỗng rồi kiểm tra index.

### `SearchIndexReconciliationJob`

- Document có run `REVIEW_CONFIRMED` nhưng `deletedAt IS NOT NULL` (đã
  trash) → không xuất hiện trong tập iterate của job (assert bằng cách mock/
  spy `findDistinctDocumentIdByReviewState...` hoặc test repository trực
  tiếp) → không log "drift" dù index rỗng.
- Document bình thường (không trash) vẫn được đối soát như cũ (regression
  test cho hành vi hiện có, không được đổi).

## 6. Task breakdown

- [x] Task 1: Thêm `EVENT_DOCUMENT_TRASHED`/`EVENT_DOCUMENT_RESTORED`/
      `EVENT_DOCUMENT_PURGED` vào hằng số + `HANDLED_EVENT_TYPES` của
      `SearchIndexOutboxPoller`.
- [x] Task 2: Viết `SegmentIndexer.deleteByDocumentIds(List<UUID>)` và
      `DocumentIndexer.deleteByIds(List<UUID>)` — mỗi method 1 `deleteByQuery`
      với `terms` filter cho cả danh sách (không loop per-id) — kèm unit test
      riêng cho 2 method này, mock `OpenSearchClient` theo đúng style test
      hiện có của `deleteByRunId`/`upsertDocument`.
- [x] Task 3: Viết helper `deleteDocumentsFromIndex(List<UUID>)` +
      `handleDocumentTrashed` + `handleDocumentPurged` (dùng chung helper đó)
      trong `SearchIndexOutboxPoller`, nối vào `processOne`.
- [x] Task 4: Refactor `handleDocumentIngested`/`handleReviewConfirmed`/
      `handleNoteChanged` để trích phần logic dùng chung thành helper, viết
      `handleDocumentRestored` gọi lại các helper đó.
- [x] Task 5: Sửa `DigitizationRunRepository.findDistinctDocumentIdByReviewState`
      (hoặc thêm method mới) để loại tài liệu đã trash (`deletedAt IS NOT
      NULL`) khỏi vòng lặp `SearchIndexReconciliationJob`; cập nhật job nếu
      cần đổi tên method gọi.
- [ ] Task 6: Backfill dòng tồn đọng trên stg (thao tác vận hành thủ công,
      chưa thực hiện — chỉ áp dụng sau khi deploy lên stg) — **không cần script
      migration riêng**: một khi Task 1-4 deploy lên stg, `poll()` tự nhặt
      lại các dòng `document.trash`/`document.restored`/`document.purged` cũ
      (chúng vẫn nằm trong `outbox_event` với `processed_at IS NULL`,
      `attempts=0`, chưa từng bị claim vì trước đó không nằm trong
      `HANDLED_EVENT_TYPES`) — chỉ cần: (a) trước khi deploy, chạy câu
      `SELECT event_type, count(*) ... GROUP BY event_type` (xem §5) để xác
      nhận số dòng thực tế của **cả 3 loại event** (không chỉ 12 dòng
      `document.trash` đã biết — `document.restored`/`document.purged` cũng
      chưa từng được xử lý nên cần biết quy mô trước khi deploy) và nội dung
      `documentId` hợp lý (không có id lạ); (b) sau khi deploy, theo dõi log
      + chạy lại câu đếm gộp `WHERE event_type IN (...) AND processed_at IS
      NULL` để xác nhận về 0; (c) nếu có dòng xử lý lỗi (ví dụ document đã bị
      xoá cứng ngoài luồng trước khi có `document.purged` event, dữ liệu
      Postgres không nhất quán) → xử lý thủ công theo log ERROR "PARKED"
      hiện có, không cần cơ chế mới.
- [x] Task 7: Unit + integration test theo §5 (bao gồm test
      `HANDLED_EVENT_TYPES` liệt kê đủ 9 loại, test reconciliation job).
      Integration test Testcontainers OpenSearch chạy được trong môi trường
      này (Docker sẵn có) — đã bổ sung 3 test cho document.trash/restored/
      purged vào `SearchIndexOutboxPollerIT`, cả 6 test trong lớp đó pass.
- [x] Task 8: Cập nhật comment Javadoc đầu `SearchIndexOutboxPoller`
      (dòng 31-38, hiện liệt kê "5 event types") và comment ở
      `search-fulltext-04-dong-bo-chi-muc.md` §4.2 nếu cần đối chiếu lại số
      lượng loại event (chỉ sửa phần liệt kê, không viết lại spec đó).

## 7. Rủi ro kỹ thuật

- **Hai lớp lọc bảo vệ (`SearchResultHydrator`, `BusinessDataScope`) là
  tuyến phòng thủ duy nhất cho tới khi spec này được deploy.** Nếu một
  endpoint search mới trong tương lai bỏ qua hydrate-lại-từ-Postgres (ví dụ
  trả thẳng field từ OpenSearch để tối ưu độ trễ) hoặc `BusinessDataScope`
  bị sửa/bỏ điều kiện `isNull(deletedAt)` (vô tình khi refactor), dữ liệu đã
  xoá sẽ lộ ra ngay lập tức qua search — không có tuyến phòng thủ thứ 3.
  Vá lỗ hổng này (Task 1-4) nên được ưu tiên cao, không nên coi là "dọn dẹp
  kỹ thuật" thông thường.
- **Backfill dòng tồn đọng có thể xử lý sai thứ tự nếu có concurrent write
  mới trong lúc poller đang "đuổi kịp".** `claimNextForIndexing` KHÔNG phải
  thuần `ORDER BY id ASC` — nó ưu tiên `document.acl.changed` lên trước MỌI
  event type khác bất kể id (`ORDER BY (event_type = 'document.acl.changed')
  DESC, id ASC`, xem `OutboxEventRepository`). Với `document.trash`/
  `document.restored`/`document.purged` so với nhau, thứ tự vẫn là `id ASC`
  đúng như suy luận ở test plan (không loại nào trong 3 loại này được ưu
  tiên). Nhưng nếu 1 trong các documentId tồn đọng vừa bị đổi ACL (share
  group) ngay trong lúc deploy, event `document.acl.changed` mới đó (id lớn
  hơn) có thể được claim và xử lý **trước** `document.trash`/`document.
  restored` cũ hơn của cùng document đó. Rủi ro thực tế thấp vì
  `handleAclChanged` tự bảo vệ bằng `fieldsBuilder.build()` (trả rỗng nếu
  `deletedAt` hiện đang khác null) nên hầu hết trở thành no-op an toàn —
  nhưng đây là hành vi đã tồn tại từ trước (không phải rủi ro riêng của spec
  này), chỉ cần lưu ý khi điều tra log nếu thấy thứ tự xử lý "lệch" so với
  id trong lúc backfill.
- **`handleDocumentTrashed` xử lý nhiều `documentId` trong 1 transaction/1
  event** — khác mọi handler khác (luôn 1 document/event). Bản nháp đầu của
  spec này định loop gọi API riêng cho từng id (tới 1000 round-trip tuần tự
  cho batch 500 id) — rủi ro "poison event" y hệt sự cố
  `document.acl.changed` ở `search-fulltext-04` §4.4. **Đã chốt sau review:
  dùng `terms` query gộp cả batch (§4.2/§4.5) thay vì loop**, giảm còn 2 lời
  gọi/event bất kể batch lớn nhỏ — rủi ro timeout gần như loại bỏ. Chỉ còn
  cần idempotent khi retry nguyên event (đã đúng, vì `deleteByQuery` không
  tranh chấp version).
- **Sửa `findDistinctDocumentIdByReviewState`** là thay đổi ở method dùng
  chung — Task 5 cần `grep -rn findDistinctDocumentIdByReviewState` trước
  khi sửa để chắc chắn không nơi nào khác đang gọi method này với kỳ vọng
  hành vi cũ (bao gồm cả tài liệu đã trash).
- **`document.purged` chưa từng được tiêu thụ từ trước tới nay** (không chỉ
  riêng bug lần này) — nghĩa là mọi tài liệu đã bị **xoá cứng** trong quá
  khứ (không chỉ 12 dòng trash) vẫn còn nguyên trong index. Cần chạy thêm 1
  câu đếm riêng trên stg
  (`SELECT count(*) FROM outbox_event WHERE event_type='document.purged'`)
  trước khi deploy để biết quy mô thực tế (có thể lớn hơn 12 dòng đã xác
  minh cho `document.trash`) — nếu số lượng lớn, cân nhắc tách backfill
  `document.purged` thành một bước theo dõi riêng do khối lượng xoá index
  một lần có thể tạo tải đột biến lên OpenSearch.
