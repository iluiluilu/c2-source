# Thiết kế tính năng Search hiện tại (Toàn văn — OpenSearch + PostgreSQL)

> Tài liệu mô tả **đúng theo code hiện trạng**, không phải spec dự định. Phạm
> vi: `POST /search` (FR-22/23), đã triển khai đầy đủ, chỉ dùng OpenSearch +
> PostgreSQL — **không** có Qdrant/semantic (đang hoãn, xem
> `docs/01-sa/architecture/search-semantic.md`, Status: Draft).
>
> Nguồn: `backend/src/main/java/com/aismartstore/backend/search/**`,
> `backend/src/main/resources/search/*.json`, `web/src/lib/api.ts`,
> `web/src/lib/search-request.ts`.

## 1. Kiến trúc tổng quan

```
Client (web) --POST /search--> SearchController --> SearchServiceImpl
                                                        │
                                        ┌───────────────┼────────────────┐
                                        ▼                                ▼
                                  OpenSearchClient              PostgreSQL (JPA)
                              (document-v1 / segment-v1)   - ACL double-check (BusinessDataScope)
                                        │                  - hydrate nội dung thật (SearchResultHydrator)
                                        ▼                  - fallback khi OpenSearch mất kết nối
                              SearchResultHydrator ─────────────┘
```

Hai vai trò tách biệt rõ:
- **OpenSearch** = tầng truy hồi/ranking (tìm cái gì khớp, khớp cỡ nào).
- **PostgreSQL** = nguồn sự thật cho nội dung hiển thị **và** quyết định ACL
  cuối cùng (double-check) — không bao giờ tin tuyệt đối field ACL trong index
  vì nó có thể lệch/trễ so với Postgres.

## 2. Thiết kế OpenSearch

### 2.1 Alias & physical index (`SearchIndexNames`)

| Alias (dùng để query/ghi) | Physical index hiện tại | Ghi chú |
|---|---|---|
| `document` | `document-v1` | Reindex sau này (đổi analyzer/mapping) → tạo `document-v2`, backfill, swap alias — không sửa code query. |
| `segment` | `segment-v1` | Tương tự. |

### 2.2 Index `document-v1`

File: `backend/src/main/resources/search/document-v1-index.json`. 1 shard, 0
replica (single-node), `refresh_interval=1s`.

| Field | Type | Mô tả |
|---|---|---|
| `document_id` | keyword | = `document.id` |
| `document_name` | text (`vi_text`) | = `document.original_name`, có phân tích tiếng Việt |
| `doc_type` | keyword | nhãn tag `kind=doc_type` hiện tại của tài liệu |
| `topic_tags` | keyword[] | nhãn tag `kind=topic` |
| `folder_id` | keyword | = `document.folder_id` |
| `folder_path_ids` | keyword[] | toàn bộ tổ tiên + chính nó — dùng để lọc "thư mục + toàn bộ con" |
| `uploaded_at` | date | = `document.created_at` |
| `media_type` | keyword | `document`\|`audio`\|`video`, suy ra từ `mime_type` (`MediaTypeResolver`), không phải cột riêng |
| `owner_user_id` | keyword | = `document.uploader_sub` |
| `scope_group_ids` | keyword[] | các `group_id` đã được share (denormalized từ `document_group_share`) |
| `acl_version` | integer | version để script cập nhật ACL không ghi đè ngược (xem 2.5) |
| `index_version` | long | = `outbox_event.id` sinh ra lần ghi này — dùng optimistic concurrency khi update_by_query |

### 2.3 Index `segment-v1`

File: `backend/src/main/resources/search/segment-v1-index.json`. Cùng
settings 1 shard/0 replica; có thêm 3 custom analyzer.

**Analyzer tiếng Việt:**

| Analyzer | Tokenizer | Filter | Dùng cho |
|---|---|---|---|
| `vi_text` | `icu_tokenizer` | `icu_normalizer`, `vi_stop` | Trường `text` chính — BM25 boost cao nhất |
| `vi_text_folded` | `icu_tokenizer` | `icu_normalizer`, `icu_folding`, `vi_stop` | `text.folded` — bỏ dấu, boost thấp, vớt truy vấn gõ không dấu |
| `vi_text_shingle` | `icu_tokenizer` | `icu_normalizer`, `vi_stop`, `shingle(2-3 âm tiết)` | `text.shingle` — tăng precision cụm từ (bù cho ICU chỉ tách âm tiết, không tách từ ghép) |

`vi_stop` dùng stopword tiếng Việt tự quản (`analysis/vi_stopwords.txt`),
không phụ thuộc plugin bên thứ ba.

**Mapping:**

| Field | Type | Mô tả |
|---|---|---|
| `segment_id` | keyword | = `content_segment.id`, hoặc id giả `"<documentId>:title"` / `"<documentId>:note"` cho 2 pseudo-segment (xem 2.5) |
| `document_id` | keyword | denormalized từ `content_segment.document_id` |
| `segment_index` | integer | = `content_segment.segment_index`; `-1` = note, `-2` = title (hằng số nội bộ, không lộ ra API) |
| `text` | text (`vi_text`) + sub-field `folded`, `shingle` | = `content_segment.current_text` (bản hiện hành, **không phải** `ai_text`) — `index_options=offsets` để highlight chính xác |
| `content_source` | keyword | `ocr`\|`stt`\|`sed`\|`summary`\|`note`\|`title` (2 giá trị cuối là pseudo-segment, không có trong CHECK constraint của Postgres) |
| `confirm_source` | keyword | `auto`\|`manual` (rút gọn từ 3 giá trị Postgres — xem 4.3) |
| `run_id` | keyword | = `content_segment.run_id`; `null` cho note/title |
| `position_type` | keyword | `page`\|`time`\|`none` |
| `page_no`, `line_from`, `line_to` | integer | vị trí OCR |
| `time_start_ms`, `time_end_ms` | integer | vị trí STT/SED |
| `doc_type`, `topic_tags`, `folder_id`, `folder_path_ids`, `uploaded_at`, `media_type` | (giống document-v1) | copy cùng giá trị của tài liệu chứa segment |
| `owner_user_id`, `scope_group_ids`, `acl_version` | (giống document-v1) | ACL |
| `index_version` | long | như trên |

### 2.4 Dữ liệu ghi vào index — nguồn & thời điểm

Không ghi trực tiếp trong request nghiệp vụ; đi qua **outbox pattern** (đã mô
tả ở phiên trước — xem `SearchIndexOutboxPoller`, poll mỗi 2s theo
`app.search-index.poll-interval-ms`):

| `outbox_event.event_type` | Hành động lên index |
|---|---|
| `document.ingested` | Upsert `document-v1` + 1 pseudo-segment `content_source='title'` (`segment_index=-2`) — tài liệu tìm được theo TÊN ngay cả khi chưa OCR/STT xong |
| `review.confirmed` / `review.reconfirmed` | Upsert toàn bộ `content_segment` (chưa xoá mềm) của run vừa duyệt vào `segment-v1`, upsert lại `document-v1`; xoá segment của run bị supersede |
| `document.note.changed` | Gộp toàn bộ `document_note` thành 1 pseudo-segment `content_source='note'` (`segment_index=-1`) |
| `document.classification.changed` | `update_by_query` cả 2 index: `doc_type`, `topic_tags` |
| `document.acl.changed` | `update_by_query` cả 2 index: `scope_group_ids` (đọc lại toàn bộ share hiện tại, không áp delta) |

Mọi `update_by_query` dùng script versioned bằng `index_version` để tránh ghi
đè ngược khi event xử lý không đúng thứ tự.

## 3. Bảng PostgreSQL liên quan đến search

Search **không có bảng riêng** — dùng lại các bảng nghiệp vụ đã tồn tại, với
4 vai trò:

| Vai trò | Bảng | Cột được dùng |
|---|---|---|
| **Hydrate nội dung tài liệu** | `document` | `id, original_name, folder_id, uploader_sub, deleted_at` |
| **Hydrate nội dung segment thật** | `content_segment` | `id, document_id, run_id, content_source, current_text, page_no, bbox_polygon, line_from, line_to, current_time_start_ms/end_ms, is_edited, source_segment_ids` |
| **ACL double-check (quyết định cuối)** | `document_group_share` | `document_id, group_id` |
| | `group_member` | resolve `groupIds(user)` cho `ScopeContext` |
| **Trạng thái hiển thị** | `document_digitization_status` | `overall_status` (map vào `SearchHit.documentDigitizationStatus`) |
| **Dẫn nguồn của đoạn tóm tắt** | `content_segment` (self-join qua `source_segment_ids`) | dùng `ReviewSegmentRepository.resolveSourceReferences` |
| **Validate danh mục đóng** | `tag` | `kind='doc_type'`/`'topic'`, `label` — kiểm `filters.docType`/`topicTags` có tồn tại trước khi query |
| **Fallback khi OpenSearch mất kết nối** | `document`, `document_tag`, `tag`, `warehouse_folder` | truy vấn thuần metadata qua JPA Specification, không có full-text |

### 3.1 Predicate ACL double-check (`BusinessDataScope.forDocuments`)

```sql
document.deleted_at IS NULL
AND (
  document.uploader_sub = :currentUserSub
  OR document.id IN (
    SELECT document_id FROM document_group_share
    WHERE group_id IN (:groupIds(user))
  )
)
```

Đây là **bộ lọc quyết định** — OpenSearch chỉ lọc sơ bộ theo `owner_user_id`/
`scope_group_ids` (có thể lệch nếu index chưa kịp đồng bộ ACL); mọi
`documentId` OpenSearch trả về đều bị áp lại predicate này trước khi vào
response, tài liệu không qua được thì bị **loại âm thầm** (không báo lỗi,
không lộ số lượng thật).

## 4. Luồng truy vấn (`SearchServiceImpl.search`)

1. Resolve `ScopeContext` (userSub + groupIds hiện tại) — lỗi → **403**
   (`ScopeResolutionException`), không chạm OpenSearch.
2. Validate: phải có `query` hoặc `filters` (không được cùng rỗng); validate
   độ dài/số từ `query`, số lượng từng nhóm filter, `docType`/`confirmSource`
   thuộc danh mục đóng.
3. `effectiveGroupIds = filters.groupIds ∩ ctx.groupIds` — id ngoài phạm vi bị
   loại âm thầm, ghi vào audit (`droppedGroupIds`), không lỗi.
4. Build query (`SearchQueryBuilder`):
   - Có `query` → `simple_query_string` trên `segment` alias, field
     `text^3, text.folded^0.5, text.shingle^2`, flags
     `PHRASE|AND|OR|NOT|PRECEDENCE|WHITESPACE` (tắt fuzzy/wildcard/regex),
     `default_operator=AND`; `collapse` theo `document_id` +
     `inner_hits` (tối đa 3 đoạn/tài liệu, sort theo score rồi
     `page_no`/`time_start_ms` tăng dần) + highlight (`unified`, 1 fragment,
     200 ký tự, cắt theo câu).
   - Không có `query` (chỉ filters) → query thẳng `document` alias, không
     collapse, sort `uploaded_at desc`.
   - Mọi query đều `filter` bắt buộc `ScopeFilterBuilder.build(ctx)`:
     `owner_user_id = user OR scope_group_ids ∩ groupIds(user) ≠ ∅`
     (`bool.should`, `minimum_should_match=1`).
5. **Over-fetch + double-check**: `fetchSize = ceil(size × 1.5) + 10`; hydrate
   qua Postgres; nếu số kết quả sau double-check chưa đủ `size`, lặp thêm 1
   vòng (`from += fetchSize`), tối đa 2 vòng — bù cho việc tài liệu bị ACL
   double-check loại bớt.
6. Nếu `totalApprox == 0` và có ít nhất 1 filter đang bật → chạy thêm
   `_msearch` chẩn đoán (mỗi query bỏ đúng 1 nhóm filter) để sinh
   `refineSuggestions`.
7. Ghi audit `search.executed` (query đã cắt còn tối đa 200 ký tự, filters,
   resultCount, sort, droppedGroupIds).

### 4.1 Xử lý lỗi/suy giảm

| Tình huống | HTTP | Hành vi |
|---|---|---|
| 1 request OpenSearch timeout (`search.query_timeout_ms=1500ms`) | **504** | Lỗi rõ ràng, **không** trả kết quả một phần |
| OpenSearch mất kết nối (`IOException` tầng transport) + có `query` | **200** | `degradedMode="opensearch_unavailable"`, `results=[]` — không có fallback full-text |
| OpenSearch mất kết nối + chỉ có `filters` (không `query`) | **200** | Fallback sang `MetadataFallbackSearchService` (JPA thuần trên Postgres) — vẫn áp đủ mọi filter, nhưng `segments=[]`, `score=0`, `documentDigitizationStatus=null`, `totalApprox` là số chính xác (không phải ước lượng) |
| Lỗi OpenSearch khác (không phải connectivity/timeout) | **500** | |
| Thiếu quyền `PERM_document.read` | **403** | Spring Security `@PreAuthorize` |
| Scope resolution fail (không đọc được group membership) | **403** | |

## 5. API Contract

### 5.1 `POST /search`

**Quyền:** `PERM_document.read` (đã cấp cho R1/R2, không cấp R3 — giống
`DocumentsController`).

#### Request body

```jsonc
{
  "query": "hợp đồng thuê nhà",       // string, optional* — tối đa 256 ký tự, tối đa 32 từ (tách whitespace)
  "filters": {                        // object, optional* — *query và filters không được cùng rỗng (400)
    "docType": "Hợp đồng",            // string, 1 giá trị, phải thuộc tag kind=doc_type (400 nếu sai)
    "topicTags": ["Tài chính"],       // string[], tối đa 20, nhãn tag kind=topic
    "topicTagsMode": "AND",           // "AND" | "OR", mặc định "AND"; giá trị khác tự rơi về "AND"
    "uploadedFrom": "2026-08-01",     // date (yyyy-MM-dd), inclusive
    "uploadedTo": "2026-08-31",       // date, inclusive (tự cộng 23:59:59.999 cuối ngày)
    "groupIds": ["<uuid>"],           // UUID[], tối đa 20; giao với nhóm thật của user, phần dư bị bỏ âm thầm
    "folderId": "<uuid>",             // UUID, lọc cả thư mục con
    "mediaType": ["document"],        // ["document"|"audio"|"video"], tối đa 20
    "confirmSource": "manual"         // "manual" | "auto"
  },
  "sort": "relevance",                // "relevance" (mặc định) | "recent"; giá trị khác tự rơi về "relevance"
  "page": 0,                          // integer >= 0, mặc định 0
  "size": 20                          // 1..50 (HARD_MAX=50); mặc định = system-config search.max_results_per_page (20); from+size ≤ 10.000
}
```

#### Response body — `200 OK`

```jsonc
{
  "results": [
    {
      "documentId": "<uuid>",
      "documentName": "Hợp đồng thuê nhà 2026.pdf",
      "docType": "Hợp đồng",                 // nullable
      "score": 12.34,                         // BM25 score; 0.0 ở nhánh fallback Postgres
      "segments": [                           // tối đa 3, rỗng nếu query rỗng hoặc đang ở fallback
        {
          "segmentId": "<uuid>",              // hoặc "<documentId>:title" / ":note" cho pseudo-segment
          "contentSource": "ocr",             // ocr | stt | sed | summary | note | title
          "confirmSource": "manual",          // manual | auto
          "highlight": "...<em>thuê nhà</em>...",  // nullable
          "position": {
            "type": "page",                   // page | time | none
            "pageNo": 3,
            "lineFrom": 12,
            "lineTo": 15,
            "bboxPolygon": [[0.1,0.2],["..."]],
            "highlightOffsetStart": 120,
            "highlightOffsetEnd": 140,
            "timeStartMs": null,
            "timeEndMs": null
          },
          "sourceReferences": null            // chỉ có giá trị khi contentSource="summary"
        }
      ],
      "documentDigitizationStatus": "confirmed" // nullable — null = chưa từng có run số hoá nào
    }
  ],
  "totalApprox": 137,               // số TÀI LIỆU khớp (ước lượng ở nhánh OpenSearch, chính xác ở nhánh fallback)
  "page": 0,
  "size": 20,
  "degradedMode": "none",           // "none" | "opensearch_unavailable"
  "refineSuggestions": [            // chỉ có khi totalApprox=0 và có filter đang bật
    { "removedFilter": "topicTags", "estimatedCount": 8 }
  ]
}
```

`removedFilter` chỉ nhận 1 trong 5 giá trị cố định:
`docType`, `topicTags`, `uploadedFrom/uploadedTo`, `groupIds`,
`folderId/mediaType/confirmSource` (3 filter cuối gộp chung 1 nhóm chẩn đoán).

#### Lỗi

| Status | Khi nào |
|---|---|
| 400 | `query` và `filters` cùng rỗng; `query` > 256 ký tự hoặc > 32 từ; 1 nhóm filter > 20 giá trị; `docType`/`confirmSource` không thuộc danh mục đóng; `page < 0`; `size` ≤ 0 hoặc > 50; `from + size > 10000` |
| 403 | Thiếu `PERM_document.read`; không resolve được scope (group membership) |
| 504 | Truy vấn OpenSearch vượt `search.query_timeout_ms` (1500ms) |
| 500 | Lỗi OpenSearch không xác định |

### 5.2 Tham số vận hành (`system-config`, chỉnh được qua UI FR-01)

| Key | Giá trị mặc định | Ghi chú |
|---|---|---|
| `search.max_results_per_page` | 20 | Trần cứng `size` không đổi được là **50** (`HARD_MAX_SIZE`, hằng code) |
| `search.query_timeout_ms` | 1500 | Timeout mỗi lần gọi OpenSearch (không phải toàn request) |

Hằng số cố định trong code (không lộ ra `system-config`): `MAX_FROM_PLUS_SIZE
= 10000`, `OVER_FETCH_FACTOR = 1.5`, `OVER_FETCH_PADDING = 10`,
`MAX_HYDRATE_ROUNDS = 2`, `MAX_QUERY_LENGTH = 256`, `MAX_QUERY_TOKENS = 32`,
`MAX_FILTER_VALUES = 20`, `INNER_HITS_SIZE = 3`.
