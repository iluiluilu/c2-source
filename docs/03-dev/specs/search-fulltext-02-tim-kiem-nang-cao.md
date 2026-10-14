# Tech Spec: search-fulltext-02-tim-kiem-nang-cao

> File: `docs/03-dev/specs/search-fulltext-02-tim-kiem-nang-cao.md` — Owner: DEV
> Nguồn story: `docs/02-ba/stories/search-fulltext-02-tim-kiem-nang-cao.md`
> Status: Approved

Kế thừa toàn bộ nền tảng ở `search-fulltext-01-tim-kiem-co-ban.md` (mapping,
`ScopeContext`, `ScopeFilterBuilder`, `SearchResultHydrator`, `POST /search`
phần cơ bản). File này chỉ chốt phần **mở rộng**: `filters` object, cú pháp
rút gọn trong `query`, chẩn đoán 0 kết quả, và đường fallback SQL khi lọc
thuần metadata.

## 1. Tóm tắt kỹ thuật

- Mở rộng `SearchRequest.filters` (record `SearchFilters`) với 8 nhóm lọc có
  cấu trúc (`bool.filter`, đúng QĐ 3 SA) — không ảnh hưởng điểm số.
- Ô `query` dùng `simple_query_string` với flag hạn chế
  (`PHRASE|AND|OR|NOT|PRECEDENCE|WHITESPACE`), `default_operator=AND`; không
  bật `FUZZY|WILDCARD|PREFIX|REGEX` — ký tự không hỗ trợ bị bỏ qua thay vì
  báo lỗi cú pháp (đặc tính có sẵn của `simple_query_string`, không cần code
  thêm để "nuốt" lỗi).
- `total = 0` sau khi áp đủ điều kiện → chạy tối đa 5 truy vấn `count` (mỗi
  lần bỏ đúng 1 nhóm filter) qua `_msearch`, trả gợi ý `refineSuggestions`.
- Khi `query` rỗng (chỉ có `filters`) và OpenSearch không khả dụng → chạy
  qua `MetadataFallbackSearchService` (Spring Data JPA Specification trên
  `document`, không đụng OpenSearch).

## 2. Data model

Không có bảng/migration mới so với `-01`. Trường lọc dùng lại đúng mapping
`segment`/`document` đã định nghĩa ở `-01` mục 2 (`doc_type`, `topic_tags`,
`folder_id`/`folder_path_ids`, `uploaded_at`, `scope_group_ids`,
`media_type`, `content_source`, `confirm_source`).

DTO mới:

```java
package com.aismartstore.backend.search.dto;

public record SearchFilters(
        String docType,                 // 1 giá trị (AC-1) — không phải mảng
        List<String> topicTags,         // ≤ 20 (QĐ3 SA)
        String topicTagsMode,           // "AND" | "OR", mặc định "AND" (AC-2)
        LocalDate uploadedFrom,
        LocalDate uploadedTo,           // trên uploaded_at — nhãn UI "Ngày nạp kho" (AC-3)
        List<UUID> groupIds,            // ≤ 20; giao với ScopeContext.groupIds() (AC-4)
        UUID folderId,                  // lọc cả cây con qua folder_path_ids (AC-5)
        List<String> mediaType,         // ≤ 20 (AC-6)
        String confirmSource            // "manual" | "auto", optional (AC-6)
) {}
```

## 3. API contract

| Method | Endpoint | Request | Response |
|---|---|---|---|
| POST | `/search` | mở rộng `-01` §3.1 với `filters` (mục 2 ở trên) | mở rộng `-01` §3.2 với `refineSuggestions` |

### 3.1 Request đầy đủ

```json
{
  "query": "\"hợp đồng thuê\" -chấm dứt +căn hộ",
  "filters": {
    "docType": "hop_dong",
    "topicTags": ["dat_dai", "dan_su"],
    "topicTagsMode": "OR",
    "uploadedFrom": "2026-01-01",
    "uploadedTo": "2026-03-01",
    "groupIds": ["9c1f...", "a2e0..."],
    "folderId": "3d21...",
    "mediaType": ["document"],
    "confirmSource": "manual"
  },
  "sort": "relevance",
  "page": 0,
  "size": 20
}
```

### 3.2 Response mở rộng — 0 kết quả

```json
{
  "results": [],
  "totalApprox": 0,
  "page": 0, "size": 20,
  "degradedMode": "none",
  "refineSuggestions": [
    { "removedFilter": "topicTags", "estimatedCount": 42 },
    { "removedFilter": "uploadedFrom/uploadedTo", "estimatedCount": 17 }
  ]
}
```

- `refineSuggestions` chỉ chạy khi `totalApprox = 0` **và** có ít nhất 1
  filter đang áp dụng (bỏ `query` không tính là một "filter" ở đây — bỏ từ
  khóa không phải gợi ý hợp lý). Tối đa 5 gợi ý, thứ tự theo `estimatedCount`
  giảm dần (DEV chốt — BA để ngỏ thứ tự ưu tiên ở câu hỏi mở).

### 3.3 Validate (400, trước khi truy vấn)

| Điều kiện | Lỗi |
|---|---|
| `query` rỗng **và** `filters` rỗng hoàn toàn | 400 "nhập từ khóa hoặc chọn ít nhất một bộ lọc" (AC-7 story `-01`) |
| `query` > 256 ký tự hoặc > 32 token (whitespace-split) | 400 (AC-13) |
| `topicTags`/`groupIds`/`mediaType` > 20 phần tử | 400 (AC-13) |
| `docType`/`confirmSource` không thuộc danh mục đóng | 400 |

## 4. Luồng xử lý chính

1. `SearchQueryBuilder.buildLexicalQuery(request, ctx)` (mở rộng `-01` §4.1.3):
   1. `bool.must` = `simple_query_string` trên `["text^3", "text.shingle^2",
      "text.folded^0.5"]`, `flags=PHRASE|AND|OR|NOT|PRECEDENCE|WHITESPACE`,
      `default_operator=AND` (đúng AC-7–AC-11 story `-02`: `"..."` → PHRASE,
      `-x` → NOT, `+x` → AND bắt buộc, `OR` → OR; ký tự lạ như `*`/`~`/
      `field:value` bị `simple_query_string` bỏ qua tự nhiên, không cần xử lý
      thêm — AC-11).
   2. `bool.filter` = `ScopeFilterBuilder.build(ctx)` **+** với mỗi nhóm lọc
      có giá trị:
      - `doc_type`: `term(doc_type, filters.docType())`.
      - `topic_tags`: `topicTagsMode="AND"` → nhiều `term` lồng trong
        `bool.filter` (mỗi tag một `term`, AND tự nhiên vì cùng nằm trong
        `filter[]`); `"OR"` → một `terms(topic_tags, [...])`.
      - `uploaded_at`: `range(uploaded_at, gte=from, lte=to)`.
      - `group_ids`: **giao với phạm vi trước khi lọc** —
        `effectiveGroupIds = filters.groupIds() ∩ ctx.groupIds()`; phần tử bị
        loại (không thuộc `ctx.groupIds()`) **không** gây lỗi, chỉ bị bỏ âm
        thầm + ghi audit riêng field `droppedGroupIds` (AC-4); nếu
        `effectiveGroupIds` rỗng sau giao mà `filters.groupIds()` không rỗng
        ban đầu → vẫn chạy truy vấn (coi như không lọc theo nhóm thêm, vì
        `ScopeFilterBuilder` đã tự giới hạn theo `ctx.groupIds()` rồi) chứ
        **không** trả 0 kết quả giả.
      - `folder_id`: `terms(folder_path_ids, [filters.folderId()])`.
      - `media_type`: `terms(media_type, filters.mediaType())`.
      - `confirm_source`: `term(confirm_source, filters.confirmSource())`
        (giá trị đã chuẩn hoá `manual`/`auto` — xem `-01` mục 2.1).
   3. Nếu `query` rỗng: bỏ `bool.must` (`match_all`), truy vấn thẳng index
      `document` (không collapse — QĐ 2 SA "truy vấn không từ khóa đi thẳng
      index document"), `sort=uploaded_at desc` mặc định.
2. **Chẩn đoán 0 kết quả** (chỉ khi `query` không rỗng hoặc có filter, và kết
   quả bước 1 = 0 tài liệu sau hydrate):
   - Với mỗi nhóm filter đang có giá trị (tối đa 5 nhóm áp dụng đầu tiên theo
     thứ tự cố định: `docType`, `topicTags`, `uploadedAt`, `groupIds`,
     `folderId`/`mediaType`/`confirmSource` gộp — DEV chốt thứ tự, không phải
     quyết định nghiệp vụ), chạy 1 truy vấn `count` bỏ đúng nhóm đó, gom
     thành 1 lệnh `_msearch`.
   - Map kết quả → `refineSuggestions`, lọc bỏ suggestion có `estimatedCount
     = 0` (không gợi ý vô nghĩa).
3. **Fallback lọc thuần metadata khi OpenSearch lỗi** (AC-14 story `-02`, AC-7
   story `-04`):
   - Điều kiện kích hoạt: `query` rỗng **và** OpenSearch ném lỗi kết nối/
     timeout ở tầng client (không phải business-timeout của 1 truy vấn).
   - `MetadataFallbackSearchService.search(filters, ctx, sort, page, size)`:
     dựng `Specification<Document>` = `BusinessDataScope.forDocuments(
     ctx.userSub(), groupMemberRepository)` `.and(docTypeEq).and(topicTagsIn)
     .and(uploadedAtBetween).and(groupIdsIn).and(folderPathPrefix).and(...)`
     — **không** collapse/`inner_hits` (không có nội dung/segment để trả),
     `results[].segments = []`, `degradedMode="opensearch_unavailable"`.
   - `topic_tags`/`folder` lọc bằng JOIN Postgres tương ứng
     (`document_tag`, `warehouse_folder.path_ids`) — tái dùng
     `DocumentSpecifications` hiện có nếu đã có sẵn predicate tương tự
     (kiểm tra khi implement, tránh viết trùng).

## 5. Test plan

- Unit test `SearchQueryBuilder`: mỗi cú pháp rút gọn (`"..."`, `-x`, `+x`,
  `OR`) sinh đúng cấu trúc `simple_query_string` (so khớp JSON request body
  gửi tới OpenSearch, không cần chạy thật); ký tự `*`/`~`/`field:value`
  không làm builder ném exception.
- Unit test giao `group_ids`: `filters.groupIds()` chứa 1 id ngoài
  `ctx.groupIds()` → id đó bị loại khỏi query filter, xuất hiện trong
  `droppedGroupIds` để audit, response vẫn 200 (không lỗi "nhóm không tồn
  tại" — AC-4).
- Unit test validate: query 257 ký tự / 33 token / 21 `topicTags` → 400
  trước khi gọi OpenSearch (verify không có lời gọi client).
- Integration test (Testcontainers OpenSearch + Postgres):
  - AC-1–AC-3, AC-5, AC-6: mỗi bộ lọc áp riêng lẻ và kết hợp AND đúng ngữ nghĩa
    (seed dữ liệu vàng nhỏ, dùng chung fixture với `-01`).
  - AC-12: seed sao cho query+filter ra 0 kết quả nhưng bỏ 1 filter ra >0 →
    `refineSuggestions` chứa đúng gợi ý, `estimatedCount` khớp truy vấn
    `count` thủ công.
  - AC-14/AC-7(`-04`): tắt OpenSearch container, gọi `/search` chỉ có
    `filters` (không `query`) → vẫn trả đúng kết quả theo Postgres,
    `degradedMode="opensearch_unavailable"`; gọi kèm `query` không rỗng khi
    OpenSearch tắt → `results=[]` + thông báo suy giảm (không chạy fallback
    SQL cho có từ khóa, đúng `-01` mục 4 bước 2).
- Case biên: `folderId` trỏ tới thư mục có 5 cấp con — tài liệu ở cấp con thứ
  5 vẫn khớp filter (giới hạn cây tối đa 5 cấp theo `warehouse-structure.md`,
  không cần test cấp thứ 6 vì DB không cho tạo).

## 6. Task breakdown

- [x] Task 1: `SearchFilters` DTO + validate (Bean Validation hoặc validate
      thủ công trong service — chốt khi implement theo pattern controller
      hiện có).
- [x] Task 2: Mở rộng `SearchQueryBuilder` — cú pháp rút gọn
      (`simple_query_string` flags) + toàn bộ nhóm filter mục 4.1.
- [x] Task 3: Logic giao `group_ids ∩ ctx.groupIds()` + ghi
      `droppedGroupIds` vào audit metadata (`search.executed`, phối hợp
      allowlist ở `-04`).
- [x] Task 4: Chẩn đoán 0 kết quả (`_msearch` 5 truy vấn count) +
      `RefineSuggestion` DTO.
- [x] Task 5: `MetadataFallbackSearchService` (Specification JPA trên
      `Document`, tái dùng `BusinessDataScope`/`DocumentSpecifications`
      hiện có nếu phù hợp).
- [x] Task 6: Circuit-breaker/health-check đơn giản để phân biệt "OpenSearch
      lỗi kết nối" (kích hoạt fallback) với "1 truy vấn timeout" (AC-10
      story `-01`, không kích hoạt fallback, chỉ báo lỗi truy vấn đó) — ví
      dụ bắt riêng `ConnectException`/`ConnectTimeoutException` khác với
      `SocketTimeoutException` theo từng request.
- [x] Task 7: Toàn bộ test plan mục 5 (unit test đầy đủ, chạy pass trong
      sandbox; integration test Testcontainers viết đủ theo mục 5 nhưng
      không chạy được trong sandbox này — xem ghi chú V21 đã biết ở
      `SearchScopeIT`/`AGENTS.md` môi trường).

## 7. Rủi ro kỹ thuật

- **Phân biệt "OpenSearch lỗi hẳn" vs "1 truy vấn quá chậm"** dễ nhầm ở tầng
  code (cùng ném timeout exception) — nếu implement sai, có thể vô tình kích
  hoạt fallback SQL cho cả trường hợp có từ khóa (SQL không hỗ trợ full-text
  tiếng Việt tương đương, sẽ trả kết quả sai/rỗng gây hiểu nhầm) — cần review
  kỹ Task 6.
- **`_msearch` 5 truy vấn count thêm tải** khi 0 kết quả xảy ra thường xuyên
  (ví dụ người dùng dò từ khóa) — giám sát `search thread pool queue` (đã
  nêu ở rủi ro SA mục 6); có thể thêm rate-limit riêng cho nhánh chẩn đoán
  nếu cần ở giai đoạn sau.
- **`folder_path_ids` phụ thuộc `WarehouseFolder.pathIds` được cập nhật đúng
  khi di chuyển thư mục** (`moveFolder` ở `WarehouseService`) — nếu
  `warehouse-structure` có bug không cập nhật `path_ids` khi move, filter
  thư mục con ở đây sẽ sai theo; không thuộc phạm vi sửa của spec này nhưng
  cần test tích hợp chéo khi cả hai slug đã implement.
