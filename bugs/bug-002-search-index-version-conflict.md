# BUG-002 — Xung đột phiên bản OpenSearch khiến tài liệu bị "PARK" vĩnh viễn, mất khỏi tìm kiếm

> File này được viết để một agent/lập trình viên **khác, chưa có ngữ cảnh gì
> trước đó** có thể đọc và xử lý độc lập. Mọi khẳng định trong file đều đã
> được xác minh qua đọc code và/hoặc tái hiện trực tiếp trên môi trường stg
> (không phải suy đoán chưa kiểm chứng) — chỗ nào còn là giả thuyết/mức độ
> tin cậy thấp hơn sẽ được ghi rõ.

- **Trạng thái:** ✅ Đã fix trong code, đã deploy lên stg, đã test lại và xác
  nhận hết lỗi (21/09/2026) — xem mục 5.4 để biết chi tiết bằng chứng test
  trên stg, mục 6 để biết phạm vi thay đổi và test coverage.
- **Mức độ nghiêm trọng:** Trung bình → Trung bình-cao. Không mất dữ liệu ở
  Postgres, nhưng gây **mất dữ liệu âm thầm, vĩnh viễn** ở tầng chỉ mục tìm
  kiếm (OpenSearch) — không có lỗi trả về cho người dùng, khó phát hiện.
- **Phát hiện lúc:** 20/09/2026, trong lúc chạy `prompt-test-stg-20260920.md`
  (đợt test toàn diện trên stg) — phát hiện tình cờ khi dựng lại dữ liệu mock
  nhiều lần liên tiếp, không phải test case được thiết kế sẵn để tìm ra nó.
- **Ghi chú quan trọng:** Trong quá trình phân tích ban đầu, có 1 giả thuyết
  về nguyên nhân đã bị **đính chính lại** (xem mục "Giả thuyết đã bị loại
  trừ" ở cuối file) — người đọc file này lưu ý đừng lặp lại nhầm lẫn đó.

---

## 1. Hiện tượng quan sát được (bằng chứng thật, đã tái hiện)

Khi rebuild lại dữ liệu mock trên stg (xoá rồi seed lại tài liệu
`00000000-0000-4000-8000-0000000000a1`), backend log xuất hiện:

```
2026-09-20T12:07:45.792Z  WARN 1 --- [backend] [   scheduling-4] c.a.b.s.index.SearchIndexOutboxPoller
  : outbox_event id=159 eventType=document.ingested indexing attempt 9 failed, will retry:
  org.opensearch.client.transport.httpclient5.ResponseException: method [PUT], host [https://opensearch:9200],
  URI [/document/_doc/00000000-0000-4000-8000-0000000000a1?version_type=external&refresh=true&version=159],
  status line [HTTP/1.1 409 Conflict]

{"error":{"root_cause":[{"type":"version_conflict_engine_exception",
  "reason":"[00000000-0000-4000-8000-0000000000a1]: version conflict, current version [159] is higher or equal
  to the one provided [159]", ...}], "status":409}

... (lặp lại y hệt tới lần thứ 20) ...

2026-09-20T12:07:45.966Z ERROR 1 --- [backend] [   scheduling-4] c.a.b.s.index.SearchIndexOutboxPoller
  : outbox_event id=159 eventType=document.ingested failed 20 times - PARKED, will no longer be claimed;
  the search index is now knowingly missing this change and needs manual operator attention
```

Sau đó, kiểm tra trực tiếp OpenSearch:
```
GET /segment/_doc/00000000-0000-4000-8000-0000000000a1:title
→ {"found": false}
```
→ Đoạn "tiêu đề" (title-segment, dùng để tìm tài liệu theo tên file) của tài
liệu này **biến mất khỏi chỉ mục vĩnh viễn**, dù bản ghi ở Postgres hoàn toàn
bình thường. Đã xác nhận:
```sql
SELECT id, event_type, attempts, processed_at FROM outbox_event WHERE id=159;
-- id=159 | document.ingested | attempts=20 | processed_at=NULL   (PARK, không tự hồi phục)
```

Khắc phục tạm thời cho môi trường test: xoá dòng `outbox_event id=159`, bơm
lại 1 event `document.ingested` mới cho tài liệu đó → xử lý thành công bình
thường. **Đây chỉ là vá tạm dữ liệu, không phải fix code.**

---

## 2. Nguyên nhân gốc (đã xác minh qua đọc code)

### 2.1 Cơ chế đang dùng

File: `backend/src/main/java/com/aismartstore/backend/search/index/SearchIndexOutboxPoller.java`

```java
// dòng 205
long externalVersion = event.getId();   // <-- LÀ NGUỒN GỐC VẤN ĐỀ
```

Số này (`externalVersion`) được dùng làm **"version" cho OpenSearch** ở 2
kiểu ghi khác nhau trong cùng file này:

**(a) Ghi toàn bộ tài liệu (kiểu ghi dễ vỡ — đây là chỗ có bug):**
- `handleDocumentIngested` (dòng ~280) → `indexDocumentAndTitleSegment` (dòng
  ~295) → `documentIndexer.upsertDocument(documentSource, externalVersion)`
- `handleReviewConfirmed` (dòng ~315, xử lý cả `review.confirmed` lẫn
  `review.reconfirmed`) → cũng gọi `documentIndexer.upsertDocument(...)`
- `handleDocumentRestored` (dòng ~382) → cũng gọi
  `indexDocumentAndTitleSegment` → cũng `upsertDocument(...)`

File `backend/src/main/java/com/aismartstore/backend/search/index/DocumentIndexer.java`:
```java
// dòng ~50-56, method upsertDocument()
client.index(idx -> idx
    .index(SearchIndexNames.DOCUMENT_ALIAS)
    .id(documentId)
    .document(source)
    .versionType(VersionType.External)   // <-- yêu cầu version PHẢI lớn hơn version đang lưu
    .version(externalVersion)
    .refresh(Refresh.True));
```
Đây là **API ghi đơn lẻ** (`client.index()`, không phải bulk) — lỗi ném ra
dưới dạng **Java exception**.

**(b) Ghi 1 phần / cập nhật field (kiểu ghi AN TOÀN — không có bug):**
- `handleAclChanged`, `handleClassificationChanged`, `handleNoteChanged`
  dùng `update_by_query` với 1 script Painless tự kiểm tra version nằm
  **trong chính nội dung tài liệu** (field `index_version`), xem hàm
  `versionedSetScript()` (dòng ~559):
  ```java
  script.append("if (params.newVersion > (ctx._source.index_version == null ? 0L : ctx._source.index_version)) { ");
  ```
  Cách này **không** dùng cơ chế version gốc của OpenSearch, và **không làm
  gì cả nếu tài liệu chưa tồn tại** (update_by_query không tìm thấy gì để
  update) — nên nhóm 3 handler này **không thể** là nguyên nhân trực tiếp
  gây xung đột version với nhóm (a).
- Việc ghi đoạn nội dung (`SegmentIndexer.upsertSegments`, dùng cho OCR/STT
  segment thật) dùng **Bulk API** — lỗi trả về dưới dạng **dữ liệu trong
  response** (`BulkResponseItem`), không phải exception. File
  `SegmentIndexer.java` đã tự xử lý đúng ("hasNonVersionConflictErrors...
  deliberately treats as benign" — xem dòng ~79-80, ~179) → **nhóm segment
  KHÔNG dính bug này.**

### 2.2 Chỗ bug thật sự nằm ở đâu

File `DocumentIndexer.java`, method `upsertDocument()` (dòng ~44-66):
```java
public boolean upsertDocument(Map<String, Object> source, long externalVersion) throws IOException {
    String documentId = String.valueOf(source.get("document_id"));
    try {
        client.index(idx -> idx....versionType(VersionType.External).version(externalVersion).refresh(Refresh.True));
        return true;
    } catch (OpenSearchException e) {          // <-- CHỈ bắt loại exception này
        if (isVersionConflict(e)) {
            log.debug("document {} upsert skipped by external versioning (a newer version is already indexed)", documentId);
            return false;   // ý định: coi xung đột version là chuyện bình thường, bỏ qua êm
        }
        throw e;
    }
}
```

Code **đã chủ động lường trước** tình huống xung đột version là bình thường
và viết catch để bỏ qua êm — **thiết kế đúng**. Nhưng khối `catch` chỉ nhận
diện `org.opensearch.client.opensearch._types.OpenSearchException` (lớp
exception cấp cao, khi client parse được JSON lỗi theo schema chuẩn). Lỗi
thật sự ném ra ở log mục 1 là
`org.opensearch.client.transport.httpclient5.ResponseException` — **một lớp
hoàn toàn khác, không kế thừa từ `OpenSearchException`** (đã decompile class
`OpenSearchException.class` trong
`opensearch-java-2.17.0.jar` để xác nhận: `public class OpenSearchException
extends java.lang.RuntimeException` — không liên quan gì tới
`ResponseException`).

→ Vì bắt sai loại exception, nhánh "bỏ qua êm" **không bao giờ được chạm
tới**. Exception thoát ra ngoài như 1 lỗi cứng → `SearchIndexOutboxPoller`
tăng `attempts`, thử lại. Vì `externalVersion = event.getId()` **cố định,
không đổi qua các lần thử lại**, mọi lần thử lại đều thất bại **y hệt nhau**
→ chắc chắn đạt tới ngưỡng 20 lần → PARK.

### 2.3 Vì sao xung đột version thật sự lại phát sinh (khi nào 2 lần ghi cùng
version/version thấp hơn lại chạm nhau)

Tất cả các trường hợp dưới đây đều dẫn tới cùng 1 kết quả: `DocumentIndexer.upsertDocument()`
được gọi 2 lần cho cùng 1 `document_id`, mà lần sau dùng version **nhỏ hơn
hoặc bằng** version đã lưu.

**(A) — Khả năng cao nhất, không cần điều kiện đặc biệt gì:** trục trặc
thoáng qua NGAY GIỮA 2 bước ghi không nguyên tử của `indexDocumentAndTitleSegment()`
(dòng 295-307):
```java
private void indexDocumentAndTitleSegment(...) {
    documentIndexer.upsertDocument(documentSource, externalVersion);   // bước 1
    ...
    segmentIndexer.upsertSegments(List.of(titleSegment), externalVersion); // bước 2
}
```
Nếu bước 1 **thành công** nhưng bước 2 gặp trục trặc tạm thời (mạng chậm,
OpenSearch bận, timeout) → toàn bộ event bị coi là thất bại → thử lại toàn
bộ, kể cả bước 1 (đã thành công từ trước) → bước 1 lần thử lại dùng lại đúng
version cũ → tự đụng vào chính version mình đã ghi trước đó → conflict.
**Đây là trục trặc hạ tầng bình thường, không đòi hỏi hệ thống "có vấn đề
gì" — có thể xảy ra trên 1 hệ thống hoàn toàn khoẻ mạnh.**

**(B) — Đã xác nhận là nguyên nhân của lần tái hiện trong mục 1:** xoá rồi
tạo lại rất nhanh cùng 1 `document_id` (trong lúc test, đã chạy
`_delete_by_query` rồi seed lại nhiều lần liên tiếp trong vài chục giây).
OpenSearch mặc định giữ "dấu vết" (tombstone) version của tài liệu vừa xoá
trong khoảng **60 giây** (tham số `index.gc_deletes`) để chống dữ liệu cũ
"hồi sinh" nhầm tài liệu đã xoá. Nếu tạo lại quá nhanh với version trùng/thấp
hơn tombstone, bị từ chối. Ứng với thao tác thật: **xoá mềm rồi khôi phục
cùng 1 tài liệu nhiều lần rất nhanh liên tiếp** (dưới ~60 giây).

**(C) — Có thật về kiến trúc, CHƯA xảy ra được trên stg hiện tại (stg chỉ
chạy đúng 1 container backend):** nếu chạy nhiều bản sao backend song song
(horizontal scaling). Code có chủ đích hỗ trợ nhiều poller cùng lúc — xem
comment trong `OutboxEventRepository.java` (`claimNextForIndexing`, dùng
`FOR UPDATE SKIP LOCKED` "so a second poller instance (horizontal scaling)
never blocks on, or double-processes, the same row"). Nếu 2 tiến trình xử lý
song song 2 sự kiện khác nhau của cùng 1 tài liệu, tiến trình xử lý xong
event có id nhỏ hơn **sau** tiến trình xử lý event id lớn hơn (do tiến trình
kia đang bận thử lại (A)) → ghi version thấp hơn tới sau → conflict.

---

## 3. Giả thuyết đã bị loại trừ (đừng lặp lại nhầm lẫn này)

**Giả thuyết ban đầu (SAI, đã đính chính):** *"`document.acl.changed` (chia
sẻ tài liệu vào nhóm) được ưu tiên xử lý trước trong hàng đợi (xem
`OutboxEventRepository.claimNextForIndexing`, `ORDER BY (event_type =
'document.acl.changed') DESC, id ASC`), nên nếu chia sẻ tài liệu ngay sau khi
upload, acl.changed sẽ chen lên xử lý trước `document.ingested`, gây xung đột
version."*

**Vì sao sai:** đã đọc lại `handleAclChanged` (mục 2.1(b) ở trên) và xác nhận
nó dùng `update_by_query` với script kiểm tra field `index_version` **trong
nội dung tài liệu** — **không đụng tới cơ chế version gốc của OpenSearch**,
và **không làm gì cả nếu tài liệu chưa tồn tại trong index** (vì
update_by_query tìm document theo `document_id`, không tìm thấy thì không có
gì để update). Vậy `acl.changed` không thể là nguyên nhân trực tiếp chặn
`document.ingested`. Đúng là `acl.changed` được ưu tiên trong hàng đợi — đó
là quyết định kiến trúc có chủ đích ("QĐ5 SA", xem comment trong
`OutboxEventRepository.java`), **không phải bug**, và nó **vô hại** với vấn
đề này. **"Upload xong rồi chia sẻ ngay" KHÔNG phải là trigger của bug này.**

---

## 4. Đánh giá: có cần hệ thống "bất thường" mới xảy ra không?

**Không.** Đây là lỗi dạng race condition — không cần hệ thống lỗi/hỏng ở
đâu cả:
- Trường hợp (A) chỉ cần 1 lần trục trặc mạng/hiệu năng thoáng qua — chuyện
  bình thường của bất kỳ hệ thống phân tán nào chạy đủ lâu.
- Trường hợp (B) là 1 thao tác người dùng hợp lệ (xoá/khôi phục nhanh liên
  tiếp), không phải lỗi sử dụng.
- Trường hợp (C) là hệ quả của việc scale hệ thống lên (dấu hiệu phát triển
  tốt, không phải hỏng hóc).

---

## 5. Đề xuất hướng xử lý

### 5.1 Fix nhanh (tactical, rủi ro thấp, KHÔNG triệt để)

Mở rộng `isVersionConflict`/khối catch trong `DocumentIndexer.upsertDocument()`
(file `DocumentIndexer.java`, dòng ~44-66) để nhận diện **cả**
`org.opensearch.client.transport.httpclient5.ResponseException` (kiểm tra
status code 409 + message chứa `version_conflict_engine_exception`), coi là
benign giống hệt nhánh `OpenSearchException` đã có.

- **Ưu điểm:** 1 chỗ sửa, không đổi hành vi khác, không migration.
- **Nhược điểm còn tồn tại sau khi fix (đã phân tích kỹ ở phiên trao đổi
  trước, tóm tắt lại):**
  1. Dựa vào 1 giả định KHÔNG được code ép buộc: "mọi handler ghi toàn bộ
     tài liệu luôn đọc lại đầy đủ trạng thái mới nhất từ Postgres" (hiện tại
     đúng — đã xác minh cả 3 handler đều gọi
     `DocumentIndexFieldsBuilder.build()` đọc Postgres live — nhưng không
     có gì ngăn 1 handler tương lai vi phạm giả định này).
  2. Sự kiện bị bỏ qua vẫn được đánh dấu `processed_at` (coi là thành công)
     → **mất luôn tín hiệu cảnh báo PARK + log ERROR hằng ngày** nếu giả
     định ở (1) có ngày bị vi phạm — từ "lỗi ồn ào" thành "lỗi im lặng
     hoàn toàn".
  3. Không sửa gốc rễ: vẫn dùng `event.getId()` làm version, vẫn dễ vỡ với
     bất kỳ tình huống thứ tự bất lợi nào trong tương lai.

### 5.1b Fix nhanh — bản cải tiến (khuyến nghị dùng bản này thay vì 5.1 trần)

Giống hệt 5.1 (bắt thêm `ResponseException`, coi conflict là benign, trả
`false`), nhưng vá luôn nhược điểm #2 của 5.1: **đổi mức log của CẢ HAI
nhánh catch (`OpenSearchException` lẫn `ResponseException`) từ DEBUG lên
WARN/INFO**, kèm tag rõ ràng để nhận diện đây là hành vi đã biết của
BUG-002, không phải sự cố mới:

```java
} catch (OpenSearchException e) {
    if (isVersionConflict(e)) {
        log.warn("[BUG-002] document {} upsert skipped - version {} already superseded "
            + "(benign per bugs/bug-002-search-index-version-conflict.md)", documentId, externalVersion);
        return false;
    }
    throw e;
} catch (ResponseException e) {   // org.opensearch.client.transport.httpclient5.ResponseException
    if (isVersionConflict(e)) {   // cần thêm overload kiểm tra status 409 + message chứa "version_conflict_engine_exception"
        log.warn("[BUG-002] document {} upsert skipped via transport-level ResponseException - "
            + "version {} already superseded (see bugs/bug-002-search-index-version-conflict.md)",
            documentId, externalVersion);
        return false;
    }
    throw e;
}
```

**Vì sao nên chọn bản này thay vì 5.1 trần:** đã đánh giá riêng (trao đổi
21/09/2026) — 5.1 trần đổi "lỗi ồn ào có dấu vết" thành "lỗi im lặng hoàn
toàn" (event vẫn được đánh dấu `processed_at`, không còn vào danh sách PARK,
không còn cách nào phát hiện nếu giả định "handler luôn ghi lại toàn bộ
trạng thái mới nhất" — nhược điểm #1 — có ngày bị vi phạm). Nâng mức log +
gắn tag giữ lại được khả năng giám sát (grep log theo tag `[BUG-002]`, có
thể dựng alert theo tần suất) mà không tăng thêm rủi ro hay effort đáng kể so
với 5.1 trần — cùng 1 chỗ sửa (`DocumentIndexer.upsertDocument()`), chỉ khác
2 dòng log level.

**Vẫn còn nhược điểm #1 và #3 của 5.1** (dựa vào giả định ngầm không được
code ép buộc; không sửa gốc rễ dùng `event.getId()` làm version) — đây vẫn
chỉ là fix tạm thời (interim), không thay thế được 5.2.

### 5.2 Fix dứt điểm (architectural, không nợ kỹ thuật, effort lớn hơn)

**Ý tưởng cốt lõi:** đổi nguồn của "version" từ *thứ tự xử lý hàng đợi*
(`event.getId()`) sang *độ mới thật của dữ liệu* (tính từ các mốc thời gian
đã đọc sẵn trong Postgres), và đổi kiểu kiểm tra version từ `External`
(bắt buộc `>`) sang `ExternalGte` (chấp nhận `>=`, vốn sinh ra cho đúng tình
huống retry an toàn).

**Đã rà soát phạm vi chính xác 21/09/2026 (đọc trực tiếp entity/repository,
không còn ước lượng "~"). 3 phát hiện quan trọng so với bản phác thảo đầu
tiên:**

**Phát hiện 1 — đính chính:** bảng `document` **không có cột `updated_at`**
(chỉ có `created_at`, gần như bất biến vì không có tính năng đổi tên/di
chuyển tài liệu độc lập ngoài lúc upload hoặc lúc khôi phục — đã kiểm tra
`grep setFolderId` toàn backend, chỉ 2 nơi set: lúc tạo document mới và lúc
restore, cả 2 đều đã có event outbox riêng). Không dùng được `document.updated_at`
làm nền tảng công thức như bản phác thảo đầu.

**Phát hiện 2 — quan trọng nhất, tự phát hiện ra 1 lỗ hổng trong chính bản
phác thảo đầu tiên của mình:** công thức ban đầu (chỉ GREATEST của
nhãn/chia sẻ/ghi chú) **KHÔNG ĐỦ** để bảo vệ đúng cho `review.confirmed`/
`review.reconfirmed` — 2 sự kiện này còn ghi cả **nội dung đoạn OCR/STT**
(`content_segment`) bằng chung 1 version với tài liệu. Nếu ai đó sửa nội
dung 1 đoạn 2 lần liên tiếp mà không đổi gì ở nhãn/chia sẻ/ghi chú, cả 2 lần
sửa sẽ tính ra CÙNG 1 version — với `ExternalGte` (chấp nhận `>=`), 1 bản ghi
cũ hơn nhưng tới muộn (do delay xử lý) vẫn có thể được chấp nhận đè lên bản
mới hơn. **Bắt buộc phải thêm `content_segment.updated_at` vào công thức**
(đã xác nhận cột này tồn tại, có `documentId` trực tiếp trên entity).
Đã rà thêm 1 lượt nữa (kiểm tra tính năng "di chuyển tài liệu sang thư mục
khác" độc lập — không tồn tại, `folder_id` chỉ đổi qua 2 sự kiện đã biết) và
không tìm thêm lỗ hổng nào khác — nhưng **việc tự bỏ sót 1 bảng ngay lần
phác thảo đầu là bằng chứng thực tế cho rủi ro #1 dưới đây, không phải giả
thuyết suông.**

**Phát hiện 3 — tin tốt, phạm vi Java nhỏ hơn ước lượng ban đầu:**
`externalVersion` hiện được tính **1 lần duy nhất** ở đầu `processOne()`
(dòng 205) rồi truyền xuyên suốt qua tham số cho ~15 hàm trong file (kể cả
các hàm phụ trợ như `putCommonFields`, `documentFields`, `baseSegmentFields`).
Vì vậy **không cần sửa chữ ký của ~10 hàm phụ trợ** — chỉ cần sửa đúng 6 hàm
xử lý chính + điểm dispatch, xem bảng dưới.

**Bảng phạm vi đã xác minh:**

| File | Việc cần làm |
|---|---|
| `DocumentIndexFieldsBuilder.java` | Inject thêm `DocumentNoteRepository`, `ContentSegmentRepository`; thêm field `contentVersion` (long, epoch-micro-giây) vào `Fields` record; tính = `COALESCE(GREATEST(document.createdAt, maxTagAssignedAt, maxShareSharedAt, maxNoteUpdatedAt, maxSegmentUpdatedAt), document.createdAt)` |
| `DocumentTagRepository.java` | Thêm `@Query("select max(dt.assignedAt) from DocumentTag dt where dt.documentId = :id")` |
| `DocumentNoteRepository.java` | Thêm `@Query("select max(n.updatedAt) from DocumentNote n where n.documentId = :id")` |
| `ContentSegmentRepository.java` | Thêm `@Query("select max(cs.updatedAt) from ContentSegment cs where cs.documentId = :id")` (entity có `documentId` trực tiếp) |
| `SearchIndexOutboxPoller.java` | (a) Xoá dòng `long externalVersion = event.getId();` ở `processOne()` (dòng 205); (b) bỏ tham số `externalVersion` khỏi lệnh gọi 6 handler trong switch (dòng 208-214); (c) bỏ tham số `long externalVersion` khỏi chữ ký của đúng 6 hàm: `handleClassificationChanged`, `handleNoteChanged`, `handleDocumentIngested`, `handleReviewConfirmed`, `handleAclChanged`, `handleDocumentRestored`; (d) trong mỗi hàm đó, ngay sau `fields.get()`, thêm `long externalVersion = fields.get().contentVersion();` (giữ nguyên tên biến → **không cần sửa ~10 hàm phụ trợ bên dưới**, chúng vẫn nhận đúng kiểu `long externalVersion` như cũ) |
| `DocumentIndexer.java` | Dòng 54: `VersionType.External` → `VersionType.ExternalGte` |
| `SegmentIndexer.java` | Dòng 102: tương tự, đồng bộ kiểu version |
| Test | Mở rộng `SearchIndexOutboxPollerIT`, `SearchIndexOutboxPollerParkingIT` (có sẵn, cùng thư mục) — thêm case gọi handler theo thứ tự CỐ Ý đảo ngược; thêm test riêng cho `DocumentIndexFieldsBuilder` verify `contentVersion` đúng khi tag/share/note/segment thay đổi (test cụ thể cho Phát hiện 2, để khoá lại không tái phạm) |

**Phát hiện 4 (21/09/2026) — xác nhận cuối cùng, mức độ tin cậy cao hơn hẳn
Phát hiện 1-3:** đã đổi phương pháp rà soát — thay vì liệt kê "bảng nào có vẻ
liên quan" (cách đã để lọt Phát hiện 2), lần này **truy ngược từng dòng
`source.put(...)` thực tế trong `documentFields`, `baseSegmentFields`,
`contentSegmentFields`, `putCommonFields`, `indexNoteSegmentIfAny`** — tức
đi từ đầu ra (field ghi vào OpenSearch) về đầu vào (bảng Postgres), duyệt
hết toàn bộ, không còn chỗ để bỏ sót vì đã hết field để duyệt. Cũng đã xác
nhận `SearchIndexOutboxPoller`/`DocumentIndexer`/`SegmentIndexer` là **nơi
duy nhất** ghi vào 2 index `document`/`segment` (grep toàn backend, không
có writer nào khác dùng version kiểu khác).

Kết quả: **đúng 5 nguồn** phủ 100% field hiện có trong pipeline, không sót:

| Field ghi vào OpenSearch | Nguồn Postgres | Tín hiệu độ mới |
|---|---|---|
| `document_name`, `uploaded_at`, `media_type`, `owner_user_id` | `document` (bất biến sau khi tạo) | `document.createdAt` |
| `folder_id`, `folder_path_ids` | `document.folder_id` (chỉ đổi qua 2 sự kiện đã có outbox riêng: ingested, restored — không có tính năng "di chuyển" độc lập nào khác, đã grep xác nhận) | không cần cột riêng, đã đi qua sự kiện có sẵn |
| `doc_type`, `topic_tags` | `document_tag` | `MAX(assigned_at)` |
| `scope_group_ids` | `document_group_share` | `MAX(shared_at)` |
| nội dung đoạn ghi chú | `document_note.body` | `MAX(updated_at)` |
| `text`/`confirm_source`/`page_no`/`line_from`/`line_to`/`time_start_ms`/`time_end_ms` của đoạn OCR/STT | `content_segment` (cùng 1 dòng, cùng 1 lần UPDATE) | `MAX(updated_at)` |
| `acl_version` | hằng số cố định `1`, không phải dữ liệu thật | không áp dụng |

**Kết luận (trong phạm vi nghiệp vụ hiện tại của hệ thống, không tính các
tính năng tương lai chưa tồn tại):** với đúng 5 nguồn ở bảng "Bảng phạm vi
đã xác minh" phía trên (`document.createdAt` + `MAX` của 4 bảng còn lại), đủ
thông tin để triển khai fix dứt điểm ngay — không cần điều tra thêm gì nữa
trước khi bắt tay code. Vẫn nên viết test riêng khẳng định đúng 5 nguồn này
(xem dòng Test ở bảng trên) để khoá lại, phòng khi có ai sau này sửa 1 trong
5 chỗ mà quên cập nhật công thức.

**Vì sao bắt buộc đổi đồng bộ, không làm dần từng phần:** field
`index_version` dùng chung cho cả 2 kiểu ghi (`acl.changed`/`classification.changed`/
`note.changed` dùng thang đo mới qua script, 3 handler "ghi toàn bộ" dùng
thang đo mới qua `ExternalGte`) — nếu chỉ đổi 1 nhóm mà để nhóm kia dùng tiếp
`event.getId()` (số nhỏ) → trộn lẫn 2 thang đo trên cùng field → tạo ra 1 bug
MỚI cùng dạng.

**Không cần migration DB** cho hướng "tính động" này: dữ liệu đang có sẵn
mang version kiểu cũ (số nhỏ); lần ghi đầu tiên sau khi đổi luôn dùng số
kiểu mới (epoch-micro-giây, ~1.7×10^15, lớn hơn rất nhiều) nên luôn được
chấp nhận ngay, không cần backfill.

**Phương án thay thế đáng cân nhắc nghiêm túc hơn ban đầu (do Phát hiện 2):**
thay vì tính động (liệt kê tay nhiều bảng, rủi ro bỏ sót đã CHỨNG MINH là có
thật), có thể thêm 1 cột `document.index_version` (hoặc `updated_at`) được
duy trì bằng **DB trigger** trên mọi bảng liên quan (`document_tag`,
`document_group_share`, `document_note`, `content_segment`) — trigger tự
động "chạm" cột này mỗi khi có thay đổi, thay vì phụ thuộc vào việc nhớ liệt
kê đúng và đủ trong code Java. Đánh đổi: cần 1 migration + vài trigger, so
với không cần migration của hướng tính động — nhưng an toàn hơn về lâu dài,
đặc biệt khi hệ thống có thêm bảng/tính năng mới ảnh hưởng tới nội dung
index sau này.

**Rủi ro cần lường trước:**
1. Công thức "độ mới" (dù chọn hướng tính động hay hướng trigger) đều là
   logic MỚI hoàn toàn — sai sót (bỏ sót 1 nguồn dữ liệu, như đã tự phát
   hiện ở Phát hiện 2) có thể gây ra đúng loại lỗi đang cố tránh. Bắt buộc
   viết test riêng cho từng nguồn dữ liệu (tag/share/note/segment) trước khi
   deploy, không chỉ test tổng quát.
2. Đụng vào đường xử lý CHUNG của cả 9 loại sự kiện outbox — phạm vi ảnh
   hưởng rộng hơn nhiều so với fix 5.1/5.1b.
3. Effort ước tính (đã cập nhật sau rà soát): phần sửa `SearchIndexOutboxPoller.java`
   nhỏ hơn dự kiến (không đụng ~10 hàm phụ trợ), nhưng phần
   `DocumentIndexFieldsBuilder.java` + 3 repository mới + test riêng cho
   từng nguồn dữ liệu bù lại — tổng effort không đổi nhiều so với ước lượng
   ban đầu, vẫn nặng hơn đáng kể so với fix nhanh.
4. Rủi ro lý thuyết, không đáng kể trong hạ tầng hiện tại: công thức dựa vào
   đồng hồ/timestamp của Postgres — chỉ có vấn đề nếu đọc dữ liệu qua 1
   read-replica bị trễ đồng bộ hoặc lệch giờ so với primary. Hiện tại hệ
   thống chỉ dùng 1 Postgres primary, không có replica, nên không áp dụng —
   ghi chú lại phòng khi sau này đổi hạ tầng.

**Đã kiểm chứng (21/09/2026): phương án này đóng được cả 3 kịch bản ở mục 2.3,
không chỉ giảm khả năng xảy ra:**
- **(A) Tự đụng vào chính mình khi thử lại:** thử lại đọc lại Postgres, dữ
  liệu không đổi → tính ra đúng version y hệt lần trước → `ExternalGte`
  (chấp nhận `>=`) chấp nhận ghi lại → không còn tự xung đột.
- **(B) Xoá/tạo lại nhanh, đụng tombstone 60s của OpenSearch:** thời gian
  thật không thể đi lùi, nên version tính lại lúc khôi phục luôn ≥ version
  đã có trước khi xoá → `ExternalGte` chấp nhận → không còn xung đột.
- **(C) Nhiều bản backend chạy song song:** version giờ gắn với *độ mới dữ
  liệu thật* (đọc lúc nào ra version đó), không phụ thuộc *thứ tự tới
  OpenSearch* — nên dữ liệu cũ hơn luôn mang version nhỏ hơn dữ liệu mới
  hơn, bất kể ai ghi tới trước về mặt đồng hồ thực → bị từ chối đúng, không
  có hiện tượng dữ liệu cũ đè dữ liệu mới.

**Cái còn lại sau fix này KHÔNG cùng loại "nợ kỹ thuật không thể tránh"** như
2 phương án bị loại ở mục 5.2b dưới đây — chỉ còn yêu cầu: bất kỳ handler
MỚI nào sau này dùng lại cơ chế ghi-toàn-bộ-tài-liệu này đều phải tính
version đúng cách (đọc lại đầy đủ trạng thái Postgres, không lấy tắt 1
phần) — đây là kỷ luật code bình thường, không phải lỗ hổng thiết kế còn sót
lại dù làm đúng.

### 5.2b Hai phương án thay thế đã cân nhắc và LOẠI BỎ — đừng đề xuất lại

**(i) Đặt version = giờ hệ thống (`System.currentTimeMillis()`) tại đúng lúc
gọi OpenSearch, thay vì đọc từ Postgres.**
Nghe hợp lý (cũng "tính lại lúc gọi", không đóng băng từ lúc tạo), nhưng
**sai về bản chất**: "giờ tôi gọi API" ≠ "dữ liệu tôi đang gửi mới tới đâu".
Ví dụ phản chứng: xử lý A đọc Postgres lúc 10:00:00 (dữ liệu CŨ) nhưng bị
delay mạng, mãi 10:00:05 mới gọi tới OpenSearch; xử lý B đọc Postgres lúc
10:00:02 (dữ liệu MỚI hơn, vì có gì đó vừa đổi) và gọi ngay lúc 10:00:02.5.
Nếu dùng giờ-lúc-gọi làm version: A (version=10:00:05) sẽ **ghi đè lên** B
(version=10:00:02.5) dù A cầm dữ liệu cũ hơn — **dữ liệu cũ đè dữ liệu mới,
âm thầm, không ai biết** — đây là hậu quả nặng hơn bug hiện tại (hiện tại
chỉ là "ghi không được", còn cách này là "ghi sai mà tưởng đúng"). **Không
làm theo hướng này.**

**(ii) Dùng khoá (lock) để chặn 2 xử lý ghi OpenSearch cho cùng 1 tài liệu
chạy song song.**
Chỉ giải quyết được kịch bản (C) — hoàn toàn bất lực với (A) và (B):
- (A) không phải 2 xử lý chạy cùng lúc — là 1 xử lý DUY NHẤT tự thử lại
  sau khi đã thất bại và giải phóng mọi khoá từ lâu; lúc thử lại chẳng có ai
  để "khoá" cả.
- (B) là bộ nhớ tombstone **nội bộ của OpenSearch** theo thời gian thực,
  hoàn toàn độc lập với việc backend có tổ chức khoá cẩn thận tới đâu.

Ngoài ra còn tốn thêm: cần khoá phân tán (không chỉ khoá trong 1 JVM, vì
cần đúng cả khi chạy nhiều bản backend), thêm độ trễ mỗi lần ghi, thêm rủi ro
mới (deadlock, quên nhả khoá khi crash, cần tinh chỉnh timeout) — tự nó là 1
dạng nợ kỹ thuật mới trong lúc cố xử lý nợ kỹ thuật cũ. **Không ưu tiên
hướng này**, trừ khi sau này thật sự cần thêm 1 lớp phòng thủ bổ sung riêng
cho kịch bản (C) sau khi đã có 5.2.

### 5.3 Khuyến nghị

Nếu cần chặn hậu quả gấp: làm **5.1b** trước (rủi ro thấp, nhanh, giữ được
khả năng giám sát mà 5.1 trần sẽ mất). Sau đó lên kế hoạch riêng, có thời
gian test kỹ, để làm 5.2 (giải quyết tận gốc, không nợ kỹ thuật). Không nên
coi 5.1/5.1b là điểm dừng cuối cùng vì nhược điểm #1 và #3 vẫn còn tồn tại
sau khi áp dụng.

### 5.4 Trạng thái triển khai (21/09/2026) và việc còn lại

**Đã làm:**
1. Cả 5.2 (fix dứt điểm) và 5.1b (phòng thủ) đã triển khai trong code cùng
   lúc — xem danh sách file ở mục 6 dưới. 546 unit test qua, 0 failure (6
   lỗi context-load khác là giới hạn sandbox có từ trước, không liên quan —
   xem lý do ở việc 1 bên dưới).
2. **Đã build + deploy lên stg (21/09/2026 21:02 +07):**
   `docker build --platform linux/amd64 -t aismartstore-api:stg ./backend`
   → `docker save | ssh linhhost docker load` → `docker compose up -d
   backend` (đúng flow của `stg/build-and-push.sh`, chỉ làm tay riêng phần
   `api` vì `web` không đổi). Container mới khởi động sạch (Flyway, Hikari,
   RabbitMQ, 2 OpenSearch index đều OK), không lỗi ở log startup.
3. **Đã test lại trên stg, đủ cả 6 event type có dùng version, tái hiện
   đúng kịch bản "2 event liên tiếp cùng version" cho từng loại (21/09/2026,
   dùng doc mock `...a1`/`...a2`, xoá sạch dữ liệu test sau khi xong):

   | Event type | Kịch bản test | Kết quả |
   |---|---|---|
   | `document.ingested` | 2 event liên tiếp, Postgres không đổi (mô phỏng đúng self-retry đã thấy trong log thật 20/09, event tự đụng version chính nó) | ✅ PASS — cả 2 `processed_at` có giá trị, `attempts=0`, không `version_conflict_engine_exception` |
   | `review.confirmed` | 2 event liên tiếp, cùng `runId` | ✅ PASS — `attempts=0` |
   | `document.note.changed` | 2 event liên tiếp (có tạo 1 note test tạm) | ✅ PASS — `attempts=0` |
   | `document.restored` | Trash rồi restore ngay lập tức cùng 1 tài liệu (đụng thẳng cửa sổ tombstone 60s của OpenSearch — đúng kịch bản (b) đã liệt kê ở mục 7 file `-04`) | ✅ PASS — restore `attempts=0`, tài liệu hiện lại đầy đủ trong `_search` |
   | `document.classification.changed` | 2 event liên tiếp, Postgres không đổi | ❌ **FAIL — PARK sau 20 lần thử**, lỗi `illegal_argument_exception: Operation type [none] not allowed` |
   | `document.acl.changed` | 2 event liên tiếp, Postgres không đổi | ❌ **FAIL — PARK sau 20 lần thử**, lỗi giống hệt trên |

   4/6 event type PASS, xác nhận phần lõi của fix BUG-002 (`contentVersion` +
   `ExternalGte`) hoạt động đúng trên dữ liệu thật. **2/6 event type FAIL —
   nhưng vì một nguyên nhân KHÁC, không phải version-source hay `External`
   vs `ExternalGte`:** đã lập hồ sơ riêng, xem
   `bugs/bug-003-versioned-set-script-invalid-noop.md`. Bằng chứng cho
   `document.ingested` cụ thể: `_version` trên OpenSearch nhảy từ `171`
   (scheme cũ, theo `outbox_event.id`) sang `1789906063859553` (scheme mới,
   epoch-micros theo dữ liệu Postgres thật).

**Chưa làm (không thuộc phạm vi yêu cầu tính đến 21/09/2026, liệt kê để agent
sau biết còn thiếu gì trước khi coi bug này là đóng hẳn):**
1. Chạy `SearchIndexOutboxPollerIT`/`SearchIndexOutboxPollerParkingIT` bằng
   `mvn verify` trong môi trường có Docker daemon hoạt động bình thường với
   Testcontainers (sandbox lúc fix bug này bị lỗi bind `app.opensearch.host`
   từ trước, không liên quan tới thay đổi của bug này — xem javadoc lớp
   `SearchIndexOutboxPollerIT`).
2. ~~Fix BUG-003~~ — ✅ **đã fix, đã deploy, đã verify trên stg (21/09/2026
   21:24 +07)**, xem `bugs/bug-003-versioned-set-script-invalid-noop.md`
   mục 6.
3. Theo dõi log production/stg vài ngày để xác nhận không còn PARK thật nào
   phát sinh tự nhiên ngoài 2 kịch bản đã test trực tiếp ở việc 3
   (self-retry cho `document.ingested`/`review.confirmed`/
   `document.note.changed`, và tombstone cho `document.restored`) — kịch
   bản đa-instance (3 kịch bản kích hoạt đã liệt kê ở mục 7 file
   `search-fulltext-04-...`) chưa test được trên stg vì hiện chỉ chạy 1
   instance.
4. Sau khi (1)-(3) ổn định, cân nhắc dọn `[BUG-002]` log tag ở
   `DocumentIndexer` (mục 5.1b) nếu không còn cần theo dõi riêng nữa.

---

## 6. Tài liệu SA/BA/DEV cần cập nhật kèm fix (rà soát 21/09/2026)

**Đã rà soát toàn bộ `docs/01-sa`, `docs/02-ba`, `docs/03-dev` liên quan tới
search/indexer. Kết luận: 3 tài liệu DEV cần sửa, SA và BA không cần.**

**✅ TÀI LIỆU ĐÃ CẬP NHẬT, ĐÃ DUYỆT, VÀ CODE ĐÃ FIX (21/09/2026).** Cả 3 file
DEV dưới đây đã được sửa đúng như mô tả (thêm dòng ghi chú ngày + lý do, sửa
đúng đoạn khẳng định sai, tham chiếu chéo về file bug này), người dùng đã đọc
lại và xác nhận **Approved**. Sau đó code đã được triển khai đúng thiết kế
mục 5.2 (`contentVersion` từ Postgres thay cho `outbox_event.id` +
`VersionType.ExternalGte` trên `DocumentIndexer`/`SegmentIndexer`, cộng phòng
thủ `ResponseException` ở mục 5.1b) — cả 3 file DEV đã được quay lại xoá cụm
"chưa fix"/"chưa triển khai trong code" và thay bằng ghi chú "đã fix trong
code" kèm entry revision-history mới ngày 21/09/2026. 546 unit test đang chạy
qua (0 failure). **Còn thiếu:** integration test (`SearchIndexOutboxPollerIT`)
chưa chạy được trong sandbox này (giới hạn môi trường Testcontainers/OpenSearch
đã biết, không liên quan tới code fix), và **chưa deploy + test lại trên
stg** — hai việc này chưa được yêu cầu, xem mục 5.4 (mới) để biết checklist
khi triển khai.

### Cần sửa

1. **`docs/03-dev/specs/search-fulltext-01-tim-kiem-co-ban.md` mục 2.1**
   (dòng 152-154) — nơi **gốc** chọn `OutboxEvent.id` làm `index_version`,
   lý do ghi rõ: *"tăng đơn điệu, đã có sẵn, không cần cột version mới ở
   Postgres"*. Cần sửa lại mô tả nguồn version theo thiết kế fix mới (độ mới
   dữ liệu thật — mục 5.2 file này), không còn là `OutboxEvent.id`.

2. **`docs/03-dev/specs/search-fulltext-04-dong-bo-chi-muc.md`** — hiện
   `Status: Approved`. Có **khẳng định sai đã qua duyệt**, cần sửa:
   - Dòng 184 (mục 4.2): "external versioning theo `outbox_event.id`... đúng
     mục 2.1 file `-01`" — cập nhật theo nguồn version mới.
   - Dòng 306-309 (mục 4.4.2): *"An toàn khi retry: ...
     `hasNonVersionConflictErrors()` **đã** coi là lành tính và bỏ qua.
     **Không cần thêm cơ chế nào để chống ghi trùng.**"* — câu này **chỉ
     đúng cho `SegmentIndexer`** (Bulk API, lỗi trả về dưới dạng dữ liệu, đã
     xử lý đúng từ đầu). Tài liệu nhầm áp dụng luôn cho
     `DocumentIndexer.upsertDocument()` (API đơn lẻ, lỗi ném dưới dạng Java
     exception) — **đúng chỗ có BUG-002**. Cần sửa lại cho chính xác + dẫn
     chiếu file bug này.
   - Mục 7 (rủi ro kỹ thuật): thêm 1 mục rủi ro mới cho BUG-002, theo đúng
     format các mục rủi ro khác đã có trong file (có ngày, có mô tả, có
     trạng thái đã sửa/chưa sửa).
   - **Làm theo đúng quy trình đã có sẵn trong chính file này**: hạ
     `Status` từ `Approved` xuống `Draft` kèm 1 dòng ghi chú ngày + lý do
     (đúng pattern các lần sửa 2026-09-18/2026-09-20 ở đầu file), rồi duyệt
     lại thành `Approved` sau khi review xong theo AGENTS.md mục 3.9.

3. **`docs/03-dev/specs/trash-search-index-cleanup.md`** — lặp lại y hệt
   khẳng định sai ở trên, áp dụng riêng cho `document.restored` (1 trong 3
   handler có bug):
   - Dòng 90-96 (mục 2): *"dùng `externalVersion = event.getId()` như mọi
     handler khác nên không cần lo ghi đè lùi nếu có event mới hơn xen
     giữa."*
   - Dòng 187-188 (mục 4, Task 4): *"external versioning tự bảo vệ không bị
     ghi đè lùi."*
   - Cần sửa cả 2 chỗ theo thiết kế fix mới.

### Không cần sửa

- **`docs/01-sa/architecture/ai-search-infra.md` Quyết định 7** (tầng SA):
  nguyên văn chỉ yêu cầu *"version **tăng đơn điệu theo tài liệu**"* — đây là
  yêu cầu kiến trúc **đúng đắn**, không chỉ định phải lấy từ `event.getId()`.
  Lỗi nằm ở tầng hiện thực hoá (DEV chọn sai nguồn cụ thể để thoả yêu cầu
  này), không phải bản thân quyết định kiến trúc. Fix ở mục 5.2 thực chất là
  làm cho hiện thực hoá đúng với đúng ý định ban đầu của SA, không đi ngược
  lại nó — **không cần sửa Quyết định 7**.
- **`docs/02-ba/stories/search-fulltext-04-dong-bo-chi-muc.md`** (tầng BA):
  không đề cập cơ chế version nội bộ (đúng bản chất tài liệu BA, chỉ mô tả
  nghiệp vụ/AC) — không cần sửa.

## 7. Tham chiếu nhanh

- Backend image lúc phát hiện bug: `aismartstore-api:stg`
  (`sha256:85d3fa1188835b339b9c5c21930ded232ec99a50361bcc31fa7ce27a5c465073`)
- File log đầy đủ lúc tái hiện: xem lịch sử chat phiên test 20/09/2026 (không
  còn lưu file log riêng — nội dung chính đã trích dẫn ở mục 1)
- File test liên quan đã có sẵn trong repo:
  `backend/src/test/java/com/aismartstore/backend/search/index/SearchIndexOutboxPollerTest.java`,
  `SearchIndexOutboxPollerIT.java`, `SearchIndexOutboxPollerParkingIT.java`
- Bug liên quan đã fix riêng (không liên quan tới bug này):
  BUG-001 — `POST/PATCH /groups` trùng tên trả 500 thay vì 409 (đã fix, đã
  deploy stg, đã verify UI+API — xem lịch sử chat nếu cần, không có file
  riêng trong thư mục này trừ khi được yêu cầu ghi thêm).
