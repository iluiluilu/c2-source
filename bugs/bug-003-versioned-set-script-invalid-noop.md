# BUG-003 — `versionedSetScript()` dùng `ctx.op = 'none'` (giá trị không hợp lệ) khiến `document.classification.changed`/`document.acl.changed` PARK vĩnh viễn khi version bị trùng

> File này được viết để một agent/lập trình viên **khác, chưa có ngữ cảnh gì
> trước đó** có thể đọc và xử lý độc lập. Mọi khẳng định đã được xác minh
> trực tiếp trên môi trường stg (không phải suy đoán).

- **Trạng thái:** ✅ Đã fix, đã deploy lên stg, đã verify lại và xác nhận hết
  lỗi (21/09/2026 21:24 +07) — xem mục 7 để biết chi tiết bằng chứng.
- **Mức độ nghiêm trọng:** Cao đối với 2 event type bị ảnh hưởng
  (`document.classification.changed`, `document.acl.changed`) — hậu quả là
  một dạng PARK vĩnh viễn khác, tương tự BUG-002 về triệu chứng (tài liệu
  ngừng nhận cập nhật tag/ACL trong search index) nhưng khác hẳn nguyên nhân.
  **Quan trọng: bản fix của BUG-002 (đã deploy lên stg 21/09/2026) làm bug
  này DỄ xảy ra hơn nhiều so với trước** — xem mục 3.
- **Phát hiện lúc:** 21/09/2026, trong lúc chạy bộ test case đầy đủ để xác
  nhận BUG-002 đã hết trên stg (test case cho `document.classification.changed`
  và `document.acl.changed`), không phải test case được thiết kế sẵn để tìm
  bug này.

---

## 1. Hiện tượng quan sát được (bằng chứng thật, đã tái hiện trên stg)

Chèn 2 dòng `outbox_event` liên tiếp, loại `document.classification.changed`,
cùng 1 `documentId`, không đổi gì ở Postgres giữa 2 lần (mô phỏng 1 kịch bản
rất thực tế: 2 sự kiện xảy ra sát nhau mà dữ liệu tại thời điểm build lại
index là như nhau). Cả 2 event đều PARK sau 20 lần thử:

```
attempts = 20, last_error =
org.opensearch.client.opensearch._types.OpenSearchException: Request failed:
[illegal_argument_exception] Operation type [none] not allowed, only [noop, index, delete] are allowed
```

Y hệt với `document.acl.changed` (cùng cơ chế, cùng script dùng chung).

Đối chứng: 4 event type còn lại cũng test với đúng kịch bản "2 event liên
tiếp, version trùng nhau" (`document.ingested`, `document.note.changed`,
`review.confirmed`, `document.restored`) đều **PASS** — vì các handler đó
dùng `documentIndexer.upsertDocument()`/`segmentIndexer.upsertSegments()`
với `VersionType.ExternalGte` (chấp nhận version bằng nhau, không lỗi).

## 2. Nguyên nhân gốc (đã đọc code xác nhận)

`SearchIndexOutboxPoller.versionedSetScript()`
(`backend/src/main/java/com/aismartstore/backend/search/index/SearchIndexOutboxPoller.java:571-581`),
dùng bởi `handleClassificationChanged` và `handleAclChanged` cho cả
`documentIndexer.updateByQuery()` lẫn `segmentIndexer.updateByQuery()`:

```java
private static String versionedSetScript(Set<String> fieldNames) {
    StringBuilder script = new StringBuilder();
    script.append("if (params.newVersion > (ctx._source.index_version == null ? 0L : ctx._source.index_version)) { ");
    for (String field : fieldNames) {
        String paramName = camelCase(field);
        script.append("ctx._source.").append(field).append(" = params.").append(paramName).append("; ");
    }
    script.append("ctx._source.index_version = params.newVersion; ");
    script.append("} else { ctx.op = 'none'; }");   // <-- SAI: phải là 'noop'
    return script.toString();
}
```

Đây là script Painless dùng trong `_update_by_query`. OpenSearch (giống
Elasticsearch) **chỉ chấp nhận 3 giá trị** cho `ctx.op` trong ngữ cảnh này:
`"noop"`, `"index"`, `"delete"`. Giá trị `"none"` không tồn tại trong tập
hợp lệ này — khi Painless gán `ctx.op = 'none'`, OpenSearch từ chối ngay khi
áp dụng operation cho document đó, ném
`illegal_argument_exception: Operation type [none] not allowed`.

Điều kiện để rơi vào nhánh `else` (và do đó lộ ra bug): `params.newVersion`
(giá trị version mới tính được, xem `bugs/bug-002-...md` mục 5.2) **không
lớn hơn** giá trị `index_version` đang lưu trong document/segment — tức là
**bằng hoặc nhỏ hơn**. Đây chính xác là nhánh "không có gì để cập nhật, bỏ
qua êm" mà thiết kế gốc muốn có (giống `hasNonVersionConflictErrors()` coi
version-conflict là lành tính ở `SegmentIndexer`) — nhưng cách hiện thực hoá
bị sai cú pháp Painless/OpenSearch, biến "bỏ qua êm" thành "lỗi cứng".

## 3. Vì sao BUG-002 làm bug này dễ xảy ra hơn hẳn

Trước khi fix BUG-002, `externalVersion` = `outbox_event.id` — một số tăng
đơn điệu **duy nhất cho mỗi event**. Hai event khác nhau gần như không bao
giờ có cùng version, nên nhánh `else` (skip) của `versionedSetScript()` chỉ
bị chạm trong trường hợp hiếm: retry đúng cùng 1 event sau khi nó đã áp dụng
thành công (mà bản thân case đó cũng đã là 1 dạng bug, chỉ là hiếm gặp hơn).

Sau khi fix BUG-002, `externalVersion` = `contentVersion` — giá trị tính từ
**độ mới dữ liệu thật trong Postgres** (mục 5.2 file BUG-002), giống nhau
cho **mọi event xử lý cùng 1 tài liệu trong cùng 1 khoảng thời gian không có
thay đổi dữ liệu nào khác chen vào**. Điều này đúng là mục tiêu thiết kế của
BUG-002 (và hoàn toàn đúng cho các handler dùng `ExternalGte`) — nhưng nó
khiến 2 event **khác nhau thực sự** (không phải chỉ là retry) có version
bằng nhau trở thành tình huống **bình thường, thường xuyên**, ví dụ:
- Người dùng đổi tag 2 lần liên tiếp nhanh mà không có xử lý bất đồng bộ nào
  khác chen giữa (2 lần đổi cho ra cùng 1 trạng thái Postgres tại thời điểm
  build index).
- Một thao tác chia sẻ nhóm (ACL) sinh nhiều `document.acl.changed` cho cùng
  tài liệu (ví dụ do batch xử lý nhiều nhóm) — các event xử lý gần nhau khi
  Postgres chưa kịp đổi gì thêm.
- Bất kỳ lần retry nào của chính `document.classification.changed`/
  `document.acl.changed` (kể cả retry vì lý do khác, không liên quan version).

Nói cách khác: **fix BUG-002 (đã deploy) khiến BUG-003 chuyển từ "gần như
không bao giờ xảy ra" sang "khá dễ xảy ra trong vận hành bình thường"** cho
đúng 2 event type dùng `update_by_query` này. Đây là lý do bug này cần được
coi là **có mức ưu tiên cao, nên fix sớm sau khi BUG-002 đã lên stg**, không
nên để tồn đọng lâu.

## 4. Phạm vi ảnh hưởng

- **Bị ảnh hưởng:** `document.classification.changed` (đổi `doc_type`/
  `topic_tags`), `document.acl.changed` (đổi `scope_group_ids`) — cả 2 nhánh
  `documentIndexer.updateByQuery()` và `segmentIndexer.updateByQuery()` bên
  trong (dùng chung `versionedSetScript()`).
- **Không bị ảnh hưởng** (đã test xác nhận, xem `bugs/bug-002-...md` mục
  5.4): `document.ingested`, `document.note.changed`, `review.confirmed`/
  `review.reconfirmed`, `document.restored` (dùng `ExternalGte` trực tiếp,
  không qua script này); `document.trash`/`document.purged` (không dùng
  version, chỉ `deleteByQuery`).
- **Hậu quả khi PARK:** đúng như BUG-002 — tài liệu không nhận được cập nhật
  tag/phân loại hoặc thay đổi quyền chia sẻ trong search index (dữ liệu ACL
  thật trong Postgres vẫn đúng, chỉ riêng bản sao trong OpenSearch bị "đứng
  hình" ở trạng thái cũ), không có lỗi hiển thị cho người dùng ở bất kỳ API
  nào.
- **Khác biệt quan trọng so với BUG-002:** đây không phải mất tài liệu khỏi
  search (document vẫn tìm được), mà là **thông tin tag/ACL trong search bị
  lỗi thời vĩnh viễn** kể từ event đầu tiên bị PARK — ví dụ: thu hồi quyền
  truy cập 1 nhóm khỏi tài liệu (ACL) có thể **không bao giờ được phản ánh
  trong search** nếu event `document.acl.changed` tương ứng bị PARK do đụng
  bug này — rủi ro bảo mật nhẹ (tài liệu vẫn hiện ra trong kết quả tìm kiếm
  cho nhóm đã bị thu hồi quyền, dù ACL thật ở tầng truy cập file/API vẫn
  đúng nếu `SearchServiceImpl` không dựa 100% vào `scope_group_ids` trong
  OpenSearch — **cần kiểm tra thêm điểm này**, chưa xác minh trong lần phát
  hiện này).

## 5. Đề xuất fix

Sửa đúng 1 chỗ, không cần đổi kiến trúc:

```java
script.append("} else { ctx.op = 'noop'; }");
```

Không có rủi ro/đánh đổi nào khác — `'noop'` là giá trị đúng theo tài liệu
OpenSearch/Elasticsearch Painless cho `_update_by_query`/`_update`, có nghĩa
chính xác là "không làm gì, giữ nguyên document", đúng ý định thiết kế gốc.

**Việc cần làm kèm theo:**
1. Sửa dòng trên trong `SearchIndexOutboxPoller.java`.
2. Thêm test tái hiện đúng kịch bản này (2 lần gọi `versionedSetScript`-based
   handler với cùng version, hoặc version thứ 2 nhỏ hơn/bằng) cho cả
   `handleClassificationChanged` và `handleAclChanged` — hiện
   `SearchIndexOutboxPollerTest.java` **chưa có test nào phủ nhánh `else`
   này** (đã kiểm tra: không có assertion nào liên quan `ctx.op`/skip-branch
   cho 2 handler này) — đây là lý do bug tồn tại từ trước mà không bị test
   đơn vị bắt được.
3. Sau khi fix, rebuild + deploy lại lên stg, chạy lại đúng 2 test case đã
   PARK trong lần phát hiện này (`document.classification.changed`,
   `document.acl.changed`, mỗi loại 2 event liên tiếp cùng version) để xác
   nhận hết PARK.
4. Cân nhắc rà soát nhanh xem có PARK thật nào đã xảy ra ngoài đời do bug
   này trước ngày 21/09/2026 hay không (khả năng thấp vì trước đó version
   theo `event.getId()` hiếm khi trùng, nhưng nên kiểm tra `outbox_event`
   với `attempts >= max` và `event_type` thuộc 2 loại này để chắc chắn).

## 6. Đã fix, đã deploy, đã verify (21/09/2026)

1. **Code:** sửa đúng dòng ở mục 5 (`SearchIndexOutboxPoller.java:579`,
   `'none'` → `'noop'`). Thêm test mới
   `classificationChangedAndAclChangedScriptsUseTheValidNoopSentinelOnTheSkipBranch`
   trong `SearchIndexOutboxPollerTest.java` — capture nội dung script thật,
   assert chứa `"ctx.op = 'noop'"` và không chứa `"ctx.op = 'none'"`. 547
   unit test qua (thêm 1 so với trước), 0 failure (6 lỗi context-load khác
   vẫn là giới hạn sandbox cũ, không liên quan).
2. **Deploy lên stg (21/09/2026 21:24 +07):** cùng flow đã dùng cho BUG-002
   (`docker build --platform linux/amd64` → `docker save | ssh | docker
   load` → `docker compose up -d backend`). Khởi động sạch.
3. **Verify lại trên stg — đúng 2 kịch bản đã PARK trước khi fix, cộng 1
   kịch bản đối chứng của BUG-002:** chèn 2 event liên tiếp/loại, cùng
   `documentId` (`...a1`), Postgres không đổi giữa các lần:

   | Event type | Trước fix (hôm nay, cùng ngày) | Sau fix |
   |---|---|---|
   | `document.classification.changed` | ❌ PARK, `illegal_argument_exception` | ✅ `attempts=0`, không lỗi |
   | `document.acl.changed` | ❌ PARK, `illegal_argument_exception` | ✅ `attempts=0`, không lỗi |
   | `document.ingested` (đối chứng BUG-002) | ✅ đã pass từ trước | ✅ vẫn `attempts=0` — không bị fix này ảnh hưởng |

   Log backend trong suốt thời gian test: không có `illegal_argument`,
   `version_conflict`, hay PARK nào. Tài liệu `...a1` sau test vẫn nhất quán
   trên OpenSearch (`_version` khớp `index_version`, `found=true`, đủ
   field). Đã xoá 6 dòng `outbox_event` test khỏi Postgres sau khi xác nhận
   xong.

## 7. Tài liệu cần cập nhật

`docs/03-dev/specs/search-fulltext-04-dong-bo-chi-muc.md` mục 4.2 (bước 1/5,
mô tả `handleClassificationChanged`/`handleAclChanged`) nên thêm 1 mục rủi
ro mới giống format BUG-002 đã có, dẫn chiếu file này — **chưa làm**, để
người dùng quyết định thời điểm cập nhật (theo đúng quy trình đã áp dụng
cho BUG-002: cập nhật tài liệu là 1 bước tách riêng, làm khi được yêu cầu).
