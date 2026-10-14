# Tech Spec: search-fulltext-01-tim-kiem-co-ban

> File: `docs/03-dev/specs/search-fulltext-01-tim-kiem-co-ban.md` — Owner: DEV
> Nguồn story: `docs/02-ba/stories/search-fulltext-01-tim-kiem-co-ban.md`
> Status: Approved
>
> - **2026-09-21 (3) — BUG-002 đã fix trong code:** đã triển khai đúng thiết
>   kế mô tả ở mục 2.1 dưới đây — `DocumentIndexFieldsBuilder.Fields` có thêm
>   `contentVersion` (tính GREATEST từ `document.created_at` +
>   `MAX(document_tag.assigned_at)` + `MAX(document_group_share.shared_at)`
>   + `MAX(document_note.updated_at)` + `MAX(content_segment.updated_at)`,
>   quy đổi epoch-micro-giây), `SearchIndexOutboxPoller` dùng giá trị này thay
>   `event.getId()` ở cả 6 handler ghi index, `DocumentIndexer`/`SegmentIndexer`
>   đổi `VersionType.External` → `ExternalGte`. Đã có unit test khoá lại công
>   thức (`DocumentIndexFieldsBuilderTest`) và test chứng minh version không
>   còn lấy từ `event.getId()` (`SearchIndexOutboxPollerTest`) — 546 test
>   backend chạy qua, 0 failure. Chi tiết đầy đủ:
>   `bugs/bug-002-search-index-version-conflict.md`.
> - **2026-09-21 (2) — duyệt lại:** người dùng đã đọc lại đoạn sửa mục 2.1
>   (BUG-002) và chốt tiếp tục **Approved** — tại thời điểm đó bug xác nhận
>   chưa fix trong code (đã fix ở mục trên, cùng ngày).
> - **2026-09-21:** hạ từ `Approved` về `Draft` — mục 2.1 (`index_version`)
>   mô tả sai: dùng `OutboxEvent.id` làm version **không** đảm bảo "tăng đơn
>   điệu theo tài liệu" như `ai-search-infra.md` Quyết định 7 yêu cầu, vì thứ
>   tự xử lý hàng đợi (ưu tiên `acl.changed`, retry sau lỗi tạm thời, nhiều
>   instance backend) có thể khác thứ tự tạo sự kiện của cùng 1 tài liệu —
>   đã gây xung đột version thật trên stg, PARK vĩnh viễn, tài liệu mất khỏi
>   tìm kiếm không cảnh báo. Chi tiết đầy đủ + hướng fix:
>   `bugs/bug-002-search-index-version-conflict.md`. **Bug chưa fix** tại
>   thời điểm sửa tài liệu này — mục 2.1 dưới đây mô tả thiết kế đã chốt
>   hướng đi nhưng chưa triển khai trong code.

⚠️ **Ghi chú phạm vi:** 4 file spec `search-fulltext-01..04` dùng chung một
module `search-service` (package mới `com.aismartstore.backend.search.*`).
File này chốt phần nền tảng dùng chung: dependency mới, mapping OpenSearch,
`ScopeContext`/`ScopeFilterBuilder`, `SearchResultHydrator`, và API cơ bản
(FR-22, không có bộ lọc nâng cao). File `-02` mở rộng request/response này
thêm `filters`/DSL nâng cao; `-03` mở rộng phần vị trí/highlight; `-04` chốt
đồng bộ chỉ mục và chữ ký `lexicalSearch()`. Không lặp lại nội dung, chỉ dẫn
chiếu qua lại.

**Đã kiểm tra trước khi viết:** `backend/pom.xml` **chưa có** dependency
OpenSearch Java client nào (Task 1). Migration Postgres mới nhất hiện có là
`V24__document_sharing_notifications.sql`; `V25`/`V26` (bảng `content_segment`,
`digitization_run`, ...) định nghĩa ở
`docs/03-dev/specs/digitization-results-store.md` — **đã Approved 2026-09-07**,
nhưng **chưa chạy migration thật** trong repo (implement theo Task breakdown
của spec đó trước). Slug này **không** cần thêm migration Postgres (chỉ đọc
`document`/`content_segment` khi hydrate) nhưng phụ thuộc trực tiếp vào
schema đó — xem rủi ro mục 7.

## 1. Tóm tắt kỹ thuật

- Module mới `search-service` bên trong `backend` (không tách service riêng):
  package `com.aismartstore.backend.search`, dùng OpenSearch Java client mới
  thêm vào `pom.xml` để tạo/ghi/đọc 2 index `document`/`segment` (alias, theo
  `ai-search-infra.md` QĐ 6/8).
- `POST /search` (FR-22): nhận `query` (bắt buộc ở file này — bộ lọc nâng cao
  thuộc `-02`), trả danh sách tài liệu (collapse theo `document_id`) kèm tối
  đa 3 đoạn trích/tài liệu (`inner_hits`), sắp theo điểm liên quan hoặc thời
  gian.
- Mọi truy vấn bắt buộc qua `ScopeContext` + `ScopeFilterBuilder` (một điểm
  dựng duy nhất) rồi double-check ACL khi hydrate từ PostgreSQL — đúng
  `ai-search-infra.md` QĐ 5 và `rbac-group-scope.md` QĐ 3.
- `groupIds(user)` ở `ai-search-infra.md`/`search-fulltext.md` **không** phải
  một API mới trong `rbac-group-scope.md` — tái dùng thẳng
  `GroupMemberRepository.findGroupIdsByUserSub(sub)` (repository JPA đã có ở
  `com.aismartstore.backend.groups`), đúng logic mà `BusinessDataScope
  .forDocuments` đang dùng cho mọi entrypoint Postgres khác. `ScopeContext`
  chỉ là một wrapper mỏng gọi lại đúng method này để hai đường Postgres/
  OpenSearch không lệch định nghĩa "phạm vi của tôi".

## 2. Data model

### 2.1 Index `segment` (alias `segment-v1`)

Theo `search-fulltext.md` QĐ 1 (mapping/analyzer) + QĐ 2 (denormalize metadata
lọc vào segment) + `ai-search-infra.md` mục 8 (trường ACL/xuất xứ bắt buộc).

```json
PUT /segment-v1
{
  "settings": {
    "number_of_shards": 1,
    "number_of_replicas": 0,
    "refresh_interval": "1s",
    "analysis": {
      "filter": {
        "vi_stop": { "type": "stop", "stopwords_path": "analysis/vi_stopwords.txt" }
      },
      "analyzer": {
        "vi_text": {
          "type": "custom",
          "char_filter": [],
          "tokenizer": "icu_tokenizer",
          "filter": ["icu_normalizer", "vi_stop"]
        },
        "vi_text_folded": {
          "type": "custom",
          "tokenizer": "icu_tokenizer",
          "filter": ["icu_normalizer", "icu_folding", "vi_stop"]
        },
        "vi_text_shingle": {
          "type": "custom",
          "tokenizer": "icu_tokenizer",
          "filter": ["icu_normalizer", "vi_stop", "shingle_2_3"]
        }
      },
      "filter_extra": {
        "shingle_2_3": { "type": "shingle", "min_shingle_size": 2, "max_shingle_size": 3, "output_unigrams": false }
      }
    }
  },
  "mappings": {
    "properties": {
      "segment_id":        { "type": "keyword" },
      "document_id":       { "type": "keyword" },
      "segment_index":     { "type": "integer" },

      "text": {
        "type": "text",
        "analyzer": "vi_text",
        "index_options": "offsets",
        "fields": {
          "folded":  { "type": "text", "analyzer": "vi_text_folded", "index_options": "offsets" },
          "shingle": { "type": "text", "analyzer": "vi_text_shingle" }
        }
      },

      "content_source":    { "type": "keyword" },
      "confirm_source":    { "type": "keyword" },
      "run_id":            { "type": "keyword" },

      "position_type":     { "type": "keyword" },
      "page_no":           { "type": "integer" },
      "line_from":         { "type": "integer" },
      "line_to":           { "type": "integer" },
      "time_start_ms":     { "type": "integer" },
      "time_end_ms":       { "type": "integer" },

      "doc_type":          { "type": "keyword" },
      "topic_tags":        { "type": "keyword" },
      "folder_id":         { "type": "keyword" },
      "folder_path_ids":   { "type": "keyword" },
      "uploaded_at":       { "type": "date" },
      "media_type":        { "type": "keyword" },

      "owner_user_id":     { "type": "keyword" },
      "scope_group_ids":   { "type": "keyword" },
      "acl_version":       { "type": "integer" },

      "index_version":     { "type": "long" }
    }
  }
}
```

Ghi chú:

- `bbox_polygon` **không** có trong mapping (đúng QĐ 1 SA) — lấy ở
  `SearchResultHydrator` từ `content_segment.bbox_polygon` (Postgres).
- `folder_path_ids` = mảng `pathIds` (ancestor, `WarehouseFolder.pathIds`) +
  chính `folder_id` của tài liệu (không phải chuỗi path theo tên — tên đổi
  không phải reindex). Lọc "thư mục + toàn bộ con" ở `-02` = `terms
  (folder_path_ids, [folderId])`, **không** dùng `prefix` query trên chuỗi.
- `media_type` suy ra từ `document.mime_type` tại thời điểm index (không có
  cột `media_type` sẵn trong `Document`): `application/pdf`, `image/*` →
  `document`; `audio/*` → `audio`; `video/*` → `video`. Bảng ánh xạ đầy đủ do
  DEV implement viết ở `MediaTypeResolver`, tái dùng nếu
  `digitization-pipeline` đã có phân loại tương tự (kiểm tra khi implement,
  tránh trùng logic).
- `confirm_source`: hợp đồng `ai-search-infra.md` mục 8 chỉ có 2 giá trị
  (`manual`/`auto`), nhưng `digitization-results-store.md` (Draft, `V25`) có
  3 giá trị DB (`auto`/`manual_unchanged`/`manual_edited`). **Chuẩn hoá ở thời
  điểm index:** `manual_unchanged`/`manual_edited` → `manual` khi ghi vào
  `segment`. DB vẫn giữ nguyên 3 giá trị làm nguồn sự thật; OpenSearch chỉ cần
  đúng 2 giá trị cho badge AC-6 (story `-03`).
- `index_version`: dùng cho **external versioning** khi bulk upsert
  (`ai-search-infra.md` QĐ 7, "idempotent + chống ghi đè lùi").
  **(Sửa 2026-09-21 — BUG-002, đã fix trong code, xem
  `bugs/bug-002-search-index-version-conflict.md`):** giá trị **không** lấy
  từ `OutboxEvent.id` như bản gốc mô tả — số này tăng đơn điệu toàn cục
  (đúng thứ tự TẠO sự kiện) nhưng **không** đảm bảo tăng đơn điệu theo thứ
  tự XỬ LÝ của cùng 1 tài liệu (hàng đợi ưu tiên `acl.changed`, retry sau
  lỗi tạm thời tự đụng version của chính nó, nhiều instance backend xử lý
  song song) — đã gây xung đột version thật trên stg, PARK vĩnh viễn. Giá
  trị đúng phải phản ánh **độ mới thật của dữ liệu tại thời điểm đọc**
  (GREATEST của `document.created_at`, `MAX(document_tag.assigned_at)`,
  `MAX(document_group_share.shared_at)`, `MAX(document_note.updated_at)`,
  `MAX(content_segment.updated_at)` cho `document_id` đang xử lý — công
  thức đầy đủ đã rà soát ở file bug mục 5.2), tính lại mỗi lần
  `DocumentIndexFieldsBuilder.build()` chạy — không phải hằng số cố định từ
  lúc tạo sự kiện. Đi kèm: đổi kiểu kiểm tra OpenSearch từ `External` (bắt
  buộc `>`) sang `ExternalGte` (chấp nhận `>=`) để retry an toàn.

### 2.2 Index `document` (alias `document-v1`)

Dùng cho truy vấn lọc thuần metadata (không từ khóa, `-02` AC hiện không
collapse) và cho `cardinality` aggregation ước lượng số tài liệu ở `-02`.

```json
PUT /document-v1
{
  "settings": { "number_of_shards": 1, "number_of_replicas": 0, "refresh_interval": "1s" },
  "mappings": {
    "properties": {
      "document_id":       { "type": "keyword" },
      "document_name":     { "type": "text", "analyzer": "vi_text" },
      "doc_type":          { "type": "keyword" },
      "topic_tags":        { "type": "keyword" },
      "folder_id":         { "type": "keyword" },
      "folder_path_ids":   { "type": "keyword" },
      "uploaded_at":       { "type": "date" },
      "media_type":        { "type": "keyword" },
      "owner_user_id":     { "type": "keyword" },
      "scope_group_ids":   { "type": "keyword" },
      "acl_version":       { "type": "integer" },
      "index_version":     { "type": "long" }
    }
  }
}
```

Không có bảng Postgres mới. Nguồn dữ liệu để index: `document` (đã có),
`content_segment`/`digitization_run` (schema Draft ở `digitization-results-store.md`
— xem rủi ro mục 7), `document_tag`/`tag` (`classification-notes-01-tags.md`),
`document_note` (`classification-notes-02-notes.md`), `document_group_share`
(`rbac-group-scope.md`).

## 3. API contract

| Method | Endpoint | Request | Response |
|---|---|---|---|
| POST | `/search` | xem 3.1 | xem 3.2 |

### 3.1 Request (phần cơ bản — `filters` mở rộng ở spec `-02`)

```json
{
  "query": "hợp đồng thuê nhà",
  "sort": "relevance",
  "page": 0,
  "size": 20
}
```

- `query`: bắt buộc ở file này nếu không có `filters` nào (`-02` mới có
  `filters`) — rỗng ⇒ 400 (AC-7 story `-01`).
- `sort`: `"relevance"` (mặc định) | `"recent"` (theo `uploaded_at` giảm dần).
- `page`/`size`: `size` mặc định/tối đa đọc từ `system-config`
  (`search.max_results_per_page`, mặc định 20, tối đa 50 — xem Task 8);
  `from = page * size`; `from + size > 10000` → 400 kèm thông báo thu hẹp bộ
  lọc (QĐ 6 SA).

### 3.2 Response

```json
{
  "results": [
    {
      "documentId": "b6f1...",
      "documentName": "Hop_dong_thue_nha_2024.pdf",
      "docType": "hop_dong",
      "score": 12.34,
      "segments": [
        { "segmentId": "...", "contentSource": "ocr", "confirmSource": "manual", "highlight": "...<em>thuê nhà</em>...",
          "position": { "type": "page", "pageNo": 3, "lineFrom": 10, "lineTo": 12, "bboxPolygon": [[0.12,0.30],[0.62,0.30],[0.62,0.35],[0.12,0.35]] } }
      ]
    }
  ],
  "totalApprox": 128,
  "page": 0,
  "size": 20,
  "degradedMode": "none"
}
```

- `totalApprox`: ước lượng số **tài liệu** (aggregation `cardinality` trên
  `document_id`, không phải số segment khớp — QĐ 2 hệ quả).
- `segments`: chi tiết field `position`/`highlight`/`confirmSource` (cấp
  segment, không phải cấp tài liệu — một tài liệu có thể vừa có segment
  `manual` vừa có segment `auto`) chốt ở spec `-03`.
- `degradedMode`: `"none"` | `"opensearch_unavailable"` (chi tiết ở `-04`).

**Đã chốt 2026-09-07 — tách route riêng với `search-semantic`:** `POST
/search` (route này, FR-22/23) giữ nguyên response gộp theo tài liệu
(`results[].segments[]`, `totalApprox`, `page`/`size` tới `from+size≤10000`)
như mô tả ở trên — **không đổi gì ở spec này**. `search-semantic.md` chuyển
sang route riêng **`POST /search/semantic`** với response phẳng theo đoạn
trích (`SemanticSearchResult[]`, `mode`, `insufficientEvidence`) — xem
`search-semantic.md` §3 (đã cập nhật cùng ngày). `lexicalSearch()` nội bộ ở
`-04` không đổi, vẫn dùng chung cho cả hai route.

## 4. Luồng xử lý chính

1. `SearchController.search(request, KeycloakUser user)`:
   1. `ScopeContext ctx = ScopeContext.resolve(user.sub(), groupMemberRepository)`
      — ném `ScopeResolutionException` (tái dùng class có sẵn ở
      `com.aismartstore.backend.groups`) nếu thiếu `sub` hoặc lỗi DB → map
      sang HTTP 403 ở `@ExceptionHandler` chung, **không** gọi OpenSearch
      (AC-5 story `-01`).
   2. Validate `query`/`filters` (giới hạn ký tự/token/mảng — chi tiết `-02`).
   3. `SearchQueryBuilder.buildLexicalQuery(query, filters, sort)` dựng truy
      vấn `bool` trên index `segment`: `must` = `simple_query_string` (chi
      tiết `-02`), `filter` = `ScopeFilterBuilder.build(ctx)` **+** filter
      nâng cao (nếu có, `-02`), `collapse` theo `document_id` với
      `inner_hits` (size 3, sort theo `_score` rồi `page_no`/`time_start_ms`
      — chi tiết `-03`).
   4. Gọi OpenSearch với `timeout` = `system-config.search.query_timeout_ms`
      (mặc định 1500ms); quá hạn → bắt `OpenSearchException`/timeout, trả lỗi
      rõ ràng (AC-10 story `-01`), **không** trả kết quả một phần.
   5. Over-fetch: lấy `size × 1,5 + 10` hit (đúng QĐ 5 SA) từ OpenSearch.
   6. `SearchResultHydrator.hydrate(hitDocumentIds, hitSegmentIds, ctx)`:
      - 1 truy vấn PostgreSQL theo lô lấy `Document` qua
        `BusinessDataScope.forDocuments(ctx.userSub(), groupMemberRepository)`
        `.and(idIn(hitDocumentIds))` — tài liệu nào **không** nằm trong tập
        này bị loại khỏi kết quả (double-check ACL, đúng QĐ 5 SA).
      - 1 truy vấn theo lô lấy `content_segment` (bảng Draft ở
        `digitization-results-store.md`, xem rủi ro mục 7) theo
        `hitSegmentIds` để lấy `bbox_polygon`, `current_text`
        (highlight fallback nếu cần), `page_no`, `line_from/to`,
        `time_start_ms/end_ms`.
      - Cắt về đúng `size`; thiếu (do bị loại ở bước double-check) → lấy
        thêm trang kế tiếp, tối đa 2 vòng lặp (QĐ 5 SA).
   7. Ghi audit `search.executed` (async, không chặn response) — chi tiết
      mục 5/Task 8 file `-04`.
   8. Trả `SearchResponse`.
2. **Fallback khi OpenSearch lỗi/không khả dụng** (không phải timeout một
   truy vấn — mà là service down hẳn, ví dụ `ConnectException`): nếu `query`
   không rỗng → trả `degradedMode="opensearch_unavailable"`, HTTP 200 với
   `results=[]` kèm thông điệp UI rõ ràng (AC-11 story `-01`); **không** thử
   chạy fallback SQL cho tìm theo từ khóa (SQL không có full-text tiếng Việt
   tương đương). Fallback SQL **chỉ** áp dụng cho truy vấn lọc thuần metadata
   (không có `query`) — chi tiết đầy đủ ở spec `-02`/`-04`.

## 5. Test plan

- Unit test: `ScopeFilterBuilder.build(ctx)` sinh đúng `bool.should
  (terms(scope_group_ids, ctx.groupIds()), term(owner_user_id, ctx.userSub()))`
  với `minimum_should_match=1`; `ctx.groupIds()` rỗng vẫn sinh đúng vế
  `owner_user_id` (không NPE/không bỏ sót filter).
- Unit test: `ScopeContext.resolve()` ném `ScopeResolutionException` khi
  `sub` null/blank và khi `groupMemberRepository` ném `RuntimeException`
  (mirror test đã có cho `BusinessDataScope.forDocuments`).
- **Test hồi quy bắt buộc "A không thấy tài liệu của B" (rủi ro Cao SA)** —
  chạy cho **mọi** `content_source` (`ocr`, `stt`, `note`, `title`):
  - Testcontainers OpenSearch (`opensearchproject/opensearch:2.17.1`, khớp
    `docker-compose.yml`) + Testcontainers Postgres.
  - Seed: tài liệu D thuộc user B, không chia sẻ nhóm nào, có segment mỗi
    loại `content_source` chứa từ khóa X. User A (khác nhóm, không sở hữu D)
    tìm từ khóa X → `results` không chứa `documentId=D` ở **bất kỳ**
    `content_source` nào.
  - Giả lập double-check ACL "thất bại" (ACL trong OpenSearch bị lệch —
    seed thẳng segment của D với `scope_group_ids` chứa nhóm của A trong
    OpenSearch nhưng Postgres không có `document_group_share` tương ứng) →
    vẫn phải bị loại ở bước hydrate (khẳng định DB là "bộ lọc quyết định").
  - Ngược lại: xoá field ACL trong OpenSearch (mô phỏng lỗi index cũ) nhưng
    Postgres có chia sẻ hợp lệ → **không** hiển thị (chỉ mục là bộ lọc thứ
    nhất, thiếu ở đó thì mất tạm thời — chấp nhận theo QĐ 5 SA, không phải
    rò rỉ).
- Integration test `SearchControllerIT` (theo mẫu `*ControllerIT` hiện có):
  AC-5 (thiếu `sub` hợp lệ → 403, không gọi OpenSearch — verify qua mock/spy
  không có lời gọi client); AC-7 (query rỗng + không filter → 400); AC-9/AC-10
  (size vượt max → 400 hoặc clamp theo quyết định Task 8; timeout giả lập →
  lỗi rõ, không trả `results` một phần); AC-11 (OpenSearch down giả lập bằng
  tắt container → `degradedMode="opensearch_unavailable"`).
- Case biên: `from + size > 10000` → 400; segment trùng `document_id` ở
  nhiều trang (do over-fetch cắt giữa chừng) không bị lặp tài liệu.

## 6. Task breakdown

- [x] Task 1: Thêm dependency OpenSearch Java client vào `backend/pom.xml`
      (`org.opensearch.client:opensearch-java` + transport HttpClient5, pin
      version tương thích server `2.17.1`, xác nhận tải được ở mirror nội bộ
      — môi trường cách ly, không kéo trực tiếp Maven Central production).
      Cấu hình `application.yml`: `opensearch.host`, `opensearch.port=9200`.
      **Đã implement:** `opensearch-java:2.17.0` + `httpclient5:5.4`;
      `app.opensearch.host`/`app.opensearch.port` (env `OPENSEARCH_HOST`/
      `OPENSEARCH_PORT`, thêm cạnh `OPENSEARCH_URL` có sẵn — cùng pattern
      `RABBITMQ_URL` vs `RABBITMQ_HOST`/`PORT`).
- [x] Task 2: Cài plugin `analysis-icu` cho OpenSearch (offline, theo
      `ai-search-infra.md` QĐ 1) — cập nhật `docker-compose.yml`/Dockerfile
      OpenSearch nếu build image tuỳ chỉnh; xác nhận `_cat/plugins` thấy
      `analysis-icu`.
      **Đã implement:** `opensearch/Dockerfile` (build từ
      `opensearchproject/opensearch:2.17.1` + `opensearch-plugin install
      analysis-icu` + copy `vi_stopwords.txt` vào `config/analysis/`);
      `docker-compose.yml` đổi service `opensearch` sang `build:` thay vì
      `image:` trực tiếp. Đã xác nhận `_cat/plugins` thấy `analysis-icu` và
      mapping mục 2.1 tạo index thành công (test thủ công qua container
      standalone, xem tóm tắt implement).
- [x] Task 3: Tạo package `com.aismartstore.backend.search` +
      `OpenSearchClientConfig` (bean client), `SearchIndexNames` (hằng số
      alias `document`/`segment`), script/bootstrap tạo index theo mapping
      mục 2 (Java code chạy 1 lần lúc khởi động dev/hoặc script vận hành —
      quyết định cách bootstrap khi implement, không phải Flyway).
      **Đã implement:** `SearchIndexBootstrap` (`ApplicationRunner`, idempotent
      qua `indices().exists`); mapping JSON ở
      `src/main/resources/search/{segment,document}-v1-index.json` (đã gộp
      `filter_extra` của spec vào `filter` — JSON gốc trong spec không phải
      cấu trúc OpenSearch hợp lệ, xem tóm tắt implement).
- [x] Task 4: `ScopeContext` (record + `resolve(sub, groupMemberRepository)`)
      và `ScopeFilterBuilder` (sinh `Query` OpenSearch).
- [x] Task 5: `SearchQueryBuilder` (phần cơ bản — `simple_query_string` +
      collapse/inner_hits khung, chưa gồm filter nâng cao của `-02`).
- [x] Task 6: `SearchResultHydrator` (double-check ACL qua
      `BusinessDataScope.forDocuments` + lấy `content_segment` theo lô).
- [x] Task 7: `SearchController` + DTO (`SearchRequest`, `SearchResponse`,
      `SearchHit`, `SegmentHit`) — endpoint `POST /search` (chỉ phần cơ bản,
      `-02` bổ sung `filters`). **Đã implement:** tái dùng
      `PERM_document.read` (không thêm permission key mới), xem `SearchController`.
- [x] Task 8: Đăng ký 2 khoá `system-config` mới trong
      `SystemConfigDefaults`: `SEARCH_MAX_RESULTS_PER_PAGE`
      (`search.max_results_per_page`, NUMBER, default 20) và
      `SEARCH_QUERY_TIMEOUT_MS` (`search.query_timeout_ms`, NUMBER, default
      1500) — theo đúng pattern các khoá hiện có; validate `size` request so
      với giá trị tối đa cấu hình (mặc định cứng 50 theo SA, không đọc
      "tối đa" từ config riêng — chỉ giá trị mặc định/size thực tế đọc từ
      config). **Quyết định DEV:** `size` vượt 50 → 400 (từ chối rõ ràng,
      không tự ý clamp).
- [x] Task 9: Toàn bộ test plan mục 5 (bao gồm Testcontainers OpenSearch).
      **Đã implement:** `ScopeContextTest`, `ScopeFilterBuilderTest`,
      `SearchServiceTest` (pure Mockito, chạy được trong sandbox này, 15/15
      pass) + `SearchScopeIT` (Testcontainers Postgres+Redis+OpenSearch,
      build image Task 2 qua `ImageFromDockerfile` — bị chặn bởi lỗi môi
      trường `V21__audit_log_seal_and_partition.sql` đã biết, xem tóm tắt
      implement; đã xác nhận container OpenSearch tự build/start thành công,
      chỉ Flyway/Postgres bị chặn).

## 7. Rủi ro kỹ thuật

- ~~Phụ thuộc schema Draft~~: `content_segment`/`digitization_run`
  (`digitization-results-store.md`) — **đã Approved 2026-09-07**, 4 điểm
  mục 7 file đó đã chốt (giữ 1 bảng, enum `content_source` đồng bộ
  `ai-search-infra.md`). `V25`/`V26` vẫn **chưa chạy migration thật** trong
  repo — Task 6 (`SearchResultHydrator`) chỉ nên bắt đầu sau khi migration
  đó được implement theo Task breakdown của spec đó.
- **Enum `content_source`/`confirm_source` lệch giữa `ai-search-infra.md`
  (Approved) và `digitization-results-store.md` (Draft)** — đã xử lý bằng
  chuẩn hoá ở thời điểm index (mục 2.1), nhưng nếu `ai-search-infra.md` được
  cập nhật để khớp enum mới (`sed`, `summary`) thì phải bỏ bước chuẩn hoá này
  và cập nhật mapping `content_source` cho khớp.
- **OpenSearch Java client + môi trường mạng cách ly:** cần xác nhận có thể
  tải dependency qua mirror Maven nội bộ trước khi Task 1 chạy được — nếu
  chưa có mirror, cần phối hợp hạ tầng vendor JAR thủ công.
- **Testcontainers OpenSearch chưa có tiền lệ trong repo** (mới chỉ có
  Testcontainers Postgres) — cần thêm image `opensearchproject/opensearch`
  vào bộ test, tăng thời gian chạy CI; cân nhắc dùng 1 container dùng chung
  cho cả 4 spec `search-fulltext-*` (`@Testcontainers` static field) thay vì
  khởi tạo lại mỗi lớp test.
