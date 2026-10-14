# Tech Spec: search-fulltext-04-dong-bo-chi-muc

> File: `docs/03-dev/specs/search-fulltext-04-dong-bo-chi-muc.md` — Owner: DEV
> Nguồn story: `docs/02-ba/stories/search-fulltext-04-dong-bo-chi-muc.md`
> Status: Approved
>
> - **2026-09-21 (3) — BUG-002 đã fix trong code:** cả 2 phần của fix mô tả
>   ở mục 4.2/4.4.2 đã triển khai — (a) `DocumentIndexFieldsBuilder.Fields`
>   có thêm `contentVersion` (độ mới dữ liệu thật, không còn `event.getId()`),
>   `SearchIndexOutboxPoller` dùng giá trị này ở cả 6 handler, `DocumentIndexer`/
>   `SegmentIndexer` đổi `VersionType.External` → `ExternalGte`; (b) mở rộng
>   thêm `catch (ResponseException e)` trong `DocumentIndexer.upsertDocument()`
>   bên cạnh `catch (OpenSearchException e)` sẵn có (mục 5.1b file bug), log
>   mức WARN kèm tag `[BUG-002]` thay vì DEBUG như nhánh cũ. Có unit test khoá
>   lại cả 2 phần (`DocumentIndexFieldsBuilderTest`, `SearchIndexOutboxPollerTest`,
>   `DocumentIndexerTest`, `SegmentIndexerTest`) + 1 test tích hợp mới trong
>   `SearchIndexOutboxPollerIT` mô phỏng đúng kịch bản tự-đụng-version khi
>   retry — 546 test backend chạy qua, 0 failure. Chi tiết đầy đủ:
>   `bugs/bug-002-search-index-version-conflict.md`.
> - **2026-09-21 (2) — duyệt lại:** người dùng đã đọc lại đoạn sửa mục 4.2,
>   4.4.2, mục 7 (BUG-002) và chốt tiếp tục **Approved** — tại thời điểm đó
>   bug xác nhận chưa fix trong code (đã fix ở mục trên, cùng ngày).
> - **2026-09-21:** hạ từ `Approved` về `Draft` — phát hiện khẳng định sai ở
>   mục 4.2 và mục 4.4.2 ("external versioning theo `outbox_event.id`... an
>   toàn khi retry, không cần thêm cơ chế nào chống ghi trùng"): câu này chỉ
>   đúng cho `SegmentIndexer` (Bulk API, lỗi trả về dưới dạng dữ liệu). Với
>   `DocumentIndexer.upsertDocument()` (API đơn lẻ, lỗi ném dưới dạng Java
>   exception), cơ chế "bỏ qua êm khi xung đột version" bị hỏng do bắt sai
>   loại exception (`OpenSearchException` thay vì
>   `org.opensearch.client.transport.httpclient5.ResponseException` thực tế
>   nhận được) — gây PARK vĩnh viễn, tài liệu mất khỏi tìm kiếm không cảnh
>   báo. Đã tái hiện thật trên stg 20/09/2026. Chi tiết đầy đủ + hướng fix
>   (**chưa triển khai trong code**): `bugs/bug-002-search-index-version-conflict.md`.
>   Đã sửa mục 4.2, 4.4.2, thêm mục rủi ro mới ở mục 7.
> - **2026-09-20 (3) — [C1] duyệt lại:** đã đọc lại toàn bộ file cùng 2 lần
>   sửa 18/09 và 20/09 bên dưới, đối chiếu với `ai-search-infra.md` Quyết định
>   7 (khớp — "Sai lệch có chủ đích khi hiện thực hoá" đúng là được ghi nhận
>   tại chính QĐ 7, kèm 4 điều kiện xem lại giống hệt) và với code thật: cả 13
>   task ở mục 6 đều đã xác minh có trong code (`claimNextForIndexing` có
>   `attempts < maxAttempts`, `SegmentIndexer` chia lô + log DEBUG round-trip,
>   `spring.task.scheduling.pool.size=4`, `testcontainers.version=1.21.4`,
>   `AclChangeOutbox`, outbox `document.ingested`, audit `search.executed`,
>   `SearchIndexReconciliationJob` đếm row park) — tick lại mục 6 cho khớp
>   thực tế, trước đó bị bỏ sót chưa tick dù code đã xong. Không phát hiện sai
>   lệch giữa spec và code cần sửa thêm. **Duyệt: Approved.**
> - **2026-09-18:** thêm mục 4.4 (chống nghẽn hàng đợi indexer) + Task 9-13;
>   đổi mục 5 (test plan), 6 (task), 7 (rủi ro). Hạ từ `Approved` về `Draft`
>   từ lần sửa này.
> - **2026-09-20:** chốt **giữ indexer trong `backend-api`**, không tách
>   deployable và không đẩy sang worker AI. Mục 4.2 bỏ khung "giải pháp tạm
>   thời", mục 1 và mục 7 sửa theo. Sai lệch so với `ai-search-infra.md`
>   QĐ 7 đã được ghi nhận tại chính QĐ 7 ("Sai lệch có chủ đích khi hiện
>   thực hoá"), kèm 4 điều kiện xem lại.
> - **2026-09-20 (2):** rà soát 4 việc nhỏ ở nghiệp vụ duyệt/đồng bộ chỉ mục
>   (xem mục 7). Đã sửa code: `edited_segment_count`/`confirm_source` không
>   được tính lại khi sửa đoạn trên run đã `confirmed` (xem
>   `digitization-review-01-ocr.md`, không phải bug của file này). Chỉ ghi
>   nhận rủi ro (chưa sửa, cần quyết định thiết kế/đo đạc trước khi sửa): audit
>   ghi bất đồng bộ ngoài transaction ở `confirmRun`, không gộp sự kiện
>   `review.reconfirmed` khi sửa nhiều đoạn liên tiếp, `bulk-chunk-size` vẫn
>   chưa đo trên dữ liệu thật (đã thêm log DEBUG round-trip/`took` ở
>   `SegmentIndexer.upsertChunk` để việc đo sau này không cần sửa code nữa).

File này chốt: (1) **chữ ký Java chính thức của `lexicalSearch()`** — hợp
đồng nội bộ cho `search-semantic` dùng; (2) cơ chế đồng bộ OpenSearch từ
Postgres (indexer, consumer sự kiện); (3) audit `search.executed`; (4) fallback
SQL chi tiết. Kế thừa mapping/`ScopeContext` ở `-01`, `filters`/
`MetadataFallbackSearchService` khung ở `-02`.

## 1. Tóm tắt kỹ thuật

- `SearchService.lexicalSearch(String query, SearchFilters filters,
  ScopeContext scopeCtx, int topK): List<LexicalHit>` — interface public duy
  nhất mà `search-semantic` được phép gọi; implementation nội bộ tái dùng
  đúng `SearchQueryBuilder`/`ScopeFilterBuilder`/`SearchResultHydrator` của
  `-01`/`-02` (không có code path riêng cho lexical-only).
- **Indexer**: `search-service` tiêu thụ sự kiện đã có sẵn trong
  `outbox_event` (bảng generic, đã tồn tại — `V19__init_outbox_event.sql`) để
  ghi/cập nhật/xoá tài liệu trong OpenSearch. **Không** tự viết lại cơ chế
  outbox→RabbitMQ→indexer chung (thuộc `ai-search-infra`). Tiến trình tiêu thụ
  outbox nằm trong `backend-api` — quyết định có chủ đích, xem mục 4.2 và
  `ai-search-infra.md` QĐ 7.
- Kho cá nhân **không bao giờ** phát sự kiện tới `search-service` (chặn ở
  producer, đã đúng thiết kế hiện có — `personal-storage-quota.md`/
  `data-ingestion.md` không ghi outbox cho tài liệu kho cá nhân).
- Fallback SQL (`MetadataFallbackSearchService`, khung đã có ở `-02`) chạy
  khi `OpenSearchUnavailableException` được ném từ tầng client.

## 2. Data model

Không thêm bảng Postgres mới. Tái dùng nguyên trạng `outbox_event`
(`event_type` TEXT + `payload` JSONB, đã có từ `V19`). Bảng liệt kê sự kiện
`search-service` cần lắng nghe/phản ứng:

| `event_type` (thực tế trong code) | Nguồn phát | Hành động ở OpenSearch |
|---|---|---|
| `document.classification.changed` | `ClassificationOutbox` (`TagsService`/`DocTypeAssignmentService`, đã tồn tại) | Đọc lại `doc_type`/`topic_tags` hiện hành của `documentId`, `update_by_query` mọi segment + document tương ứng (AC-4 story `-04`) |
| `document.note.changed` | `NotesService` (đã tồn tại) | Ghi lại **1** segment tổng hợp `content_source='note'` của tài liệu (nội dung note hiện hành — theo `classification-notes-02-notes.md` QĐ 7); nếu tài liệu có nhiều note, gộp theo cách đã chốt ở spec đó (DEV đối chiếu khi implement) |
| `document.ingested` | `data-ingestion.md` (đã Approved, cần xác nhận có ghi outbox — xem rủi ro mục 7) | Upsert **document** + segment `content_source='title'` ngay (tên tệp/nhãn tìm được ngay, chưa cần xác nhận nội dung — AC-2) |
| `review.confirmed` / `review.reconfirmed` | `digitization-review-01-ocr.md`/`-02-stt.md` (tech spec DEV đã `Approved` — `ReviewService.confirmRun`/`reviseSegment`, xem file đó §3.4) | Upsert toàn bộ `content_segment` của run vừa confirmed (`content_source ∈ {ocr, stt}`, cộng `sed`/`summary` khi story riêng cho màn duyệt 2 loại đó sẵn sàng) vào index `segment`, kèm `document` |
| `acl.changed` (chia sẻ/gỡ chia sẻ nhóm) | **Chưa tồn tại trong code** — `GroupsService`/document-sharing chưa ghi outbox nào (xác nhận bằng grep, xem rủi ro mục 7) | `update_by_query` cập nhật `scope_group_ids` trên mọi segment + document của `documentId` |

`LexicalHit` (DTO nội bộ, **không** phải entity JPA):

```java
package com.aismartstore.backend.search.query;

public record LexicalHit(UUID segmentId, UUID documentId, double bm25Score) {}
```

## 3. API contract

Không có REST endpoint mới ở file này (REST `/search` đã chốt ở `-01`/`-02`).
**Hợp đồng nội bộ (Java interface)** — chữ ký chính thức cho `search-semantic`:

```java
package com.aismartstore.backend.search;

import com.aismartstore.backend.search.dto.SearchFilters;
import com.aismartstore.backend.search.query.LexicalHit;
import com.aismartstore.backend.search.query.ScopeContext;

public interface SearchService {

    /**
     * search-fulltext.md mục 5 (hợp đồng cho search-semantic, FR-24).
     * Chạy đúng nhánh lexical của SearchQueryBuilder (không collapse theo
     * document_id - trả thẳng segment, đủ cho hợp nhất RRF theo segment_id),
     * áp ScopeFilterBuilder.build(scopeCtx) + filters như /search thường,
     * KHÔNG double-check ACL ở đây (caller - SemanticSearchService - phải tự
     * gọi SearchResultHydrator trước khi trả dữ liệu ra ngoài, đúng nguyên
     * tắc "chỉ mục là bộ lọc thứ nhất, DB là bộ lọc quyết định" dùng chung).
     * Ném ScopeResolutionException nếu scopeCtx không hợp lệ (fail-closed).
     */
    List<LexicalHit> lexicalSearch(String query, SearchFilters filters,
                                    ScopeContext scopeCtx, int topK);
}
```

- **Đối chiếu với giả định trong `docs/03-dev/specs/search-semantic.md`:**
  file đó gọi `SearchService.lexicalSearch(query, filters, scopeCtx,
  topK=50)` — **khớp hoàn toàn** về tên tham số/thứ tự. Điểm cần
  `search-semantic` xác nhận lại (chưa từng được chốt ở đó, vì lúc viết file
  đó `search-fulltext` chưa có tech spec):
  - **Package/type cụ thể, lần đầu được chốt ở đây:**
    `com.aismartstore.backend.search.SearchService`,
    `com.aismartstore.backend.search.query.{ScopeContext,LexicalHit}`,
    `com.aismartstore.backend.search.dto.SearchFilters`.
  - **Kiểu trả về là `List<LexicalHit>`** (record `segmentId`/`documentId`/
    `bm25Score`) — **không** phải `List<Tuple>`/`Map` như cách diễn đạt
    "hợp đồng khái niệm" `List<(segment_id, bm25_score)>` ở
    `search-fulltext.md` mục 5 (SA). `search-semantic.md` §1/§4 dùng đúng 2
    trường này (`segmentId`, `bm25Score`) khi mô tả `HybridFusion.rrf()` nên
    **tương thích không cần sửa** — chỉ cần đổi tên biến cục bộ nếu có, không
    đổi logic.
  - **`lexicalSearch()` không tự double-check ACL** — quyết định mới ở đây,
    chưa nêu rõ trong `search-semantic.md` (file đó giả định hydrate dùng
    chung `SearchResultHydrator` ở bước riêng, nên **phù hợp**, không mâu
    thuẫn, nhưng cần đối chiếu để chắc `SemanticSearchService` không bỏ sót
    bước gọi hydrate sau khi nhận `LexicalHit` — nếu bỏ sót, dữ liệu OpenSearch
    delay ACL có thể rò rỉ qua nhánh ngữ nghĩa).
  - **`ScopeContext` là type mới** (record `userSub`+`groupIds`, mục 1
    file `-01`) — `search-semantic.md` chỉ dùng tên biến `scopeCtx` không
    chỉ rõ type; cần `search-semantic` import đúng lớp này khi implement.
- **Không có sai lệch chữ ký nghiêm trọng nào** giữa 2 spec ở mức tham số
  đầu vào/logic nội bộ. Sai lệch **duy nhất đáng chú ý** nằm ở **API công
  khai** `/search` (response shape khác nhau giữa lexical/hybrid — đã nêu ở
  `-01` §3.2), không ảnh hưởng `lexicalSearch()`.

## 4. Luồng xử lý chính

### 4.1 `lexicalSearch()` (nội bộ)

1. `ScopeContext scopeCtx` do caller (`SemanticSearchService`) truyền vào —
   đã resolve từ trước, `SearchServiceImpl` **không** tự resolve lại.
2. Dựng cùng query như `/search` (`-01`/`-02`) nhưng: không `collapse`, `size
   = topK`, chỉ lấy `_source: false` + `fields: [segment_id, document_id]` +
   `_score` (tối ưu, không cần `text`/highlight cho nhánh này).
3. Trả `List<LexicalHit>` trực tiếp từ hit OpenSearch — **không** gọi
   `SearchResultHydrator` (double-check ACL là trách nhiệm của caller, nêu
   rõ trong Javadoc mục 3).
4. OpenSearch lỗi → ném `OpenSearchUnavailableException` (unchecked), để
   `SemanticSearchService` tự bắt và set `degradedMode=semantic_only` (đúng
   luồng đã thiết kế ở `search-semantic.md` §4 bước 2) — `lexicalSearch()`
   **không** tự làm fallback SQL (fallback SQL không có điểm BM25 nên vô
   nghĩa với RRF).

### 4.2 Indexer — tiêu thụ `outbox_event`

**Quyết định có chủ đích (chốt 2026-09-20), KHÔNG phải giải pháp tạm.**
Trước đây mục này mô tả `SearchIndexOutboxPoller` là "giải pháp tạm thời chờ
outbox-relay chung". Đã rà soát lại và chốt **giữ nguyên trong
`backend-api`** — xem `ai-search-infra.md` Quyết định 7, khung "Sai lệch có
chủ đích khi hiện thực hoá" để biết lý do đầy đủ và **bốn điều kiện xem lại**.
Tóm tắt: package `search/index` phụ thuộc 8 package domain (10
repository/entity) nên tách deployable buộc phải nhân bản domain hoặc dựng
shared library khoá chặt hai bên vào cùng schema; cộng với ràng buộc một máy
chủ / một replica thì thêm JVM không đổi lại được khả năng scale nào.

**Đừng tách chỉ vì đọc thấy hai chữ "tạm thời" ở đâu đó** — nếu cần cách ly
tài nguyên, đường rẻ hơn nhiều là cùng một JAR chạy hai chế độ bằng
`@ConditionalOnProperty` (một instance bật poller, một instance phục vụ API);
`claimNextForIndexing` đã dùng `FOR UPDATE SKIP LOCKED` nên nhiều instance là
an toàn theo thiết kế.

Hiện thực: `SearchIndexOutboxPoller` — `@Scheduled` job (chu kỳ
`[ĐX]` 2s) trong chính `search-service`, poll
`outbox_event WHERE event_type IN (...) AND processed_at IS NULL ORDER BY id
ASC LIMIT 200 FOR UPDATE SKIP LOCKED`, xử lý tuần tự theo `document_id` (đảm
bảo thứ tự đúng QĐ7 SA), set `processed_at` sau khi ghi OpenSearch thành
công (cùng transaction hoặc ngay sau — chấp nhận at-least-once, indexer dùng
external versioning để bỏ qua ghi đè lùi, đúng mục 2.1 file `-01`).
**(Sửa 2026-09-21 — BUG-002, đã fix trong code):** giá trị version dùng cho việc này
**không phải** `outbox_event.id` như mô tả gốc — xem mục 2.1 file `-01`
(đã sửa cùng ngày) và mục 4.4.2 dưới đây để biết lý do và giá trị đúng.
Lỗi ghi OpenSearch → không set `processed_at`, tăng cột
`attempts`/`last_error` (đã có sẵn trên `OutboxEvent`), tự thử lại ở vòng
poll sau (không cần DLQ riêng ở mức này — nếu `attempts` vượt ngưỡng `[ĐX]`
20, log cảnh báo mức ERROR để vận hành can thiệp thủ công).

> **Nếu** sau này một trong bốn điều kiện xem lại ở `ai-search-infra.md` QĐ 7
> xảy ra và phải chuyển sang relay→RabbitMQ chung, `SearchIndexOutboxPoller`
> được thay bằng `SearchIndexerListener` (`@RabbitListener`) — đổi
> implementation, **không đổi** interface `SegmentIndexer`/`DocumentIndexer`
> bên dưới. Package `search/index` hiện là **package lá** (không package nào
> bên ngoài import vào nó), nên đường tách vẫn để mở — giữ tính chất một
> chiều đó là đủ, không cần làm gì thêm ngay bây giờ.

1. **`document.classification.changed`** (`{documentId, changedAt,
   changedBySub}`): đọc lại `document_tag` hiện hành (`doc_type` 1 giá trị,
   `topic_tags[]`) từ Postgres, `update_by_query` trên `segment` (mọi
   segment có `document_id` này) và upsert `document` — `requests_per_second`
   throttle `[ĐX]` 500 (QĐ2 SA hệ quả — gom lô/throttle, AC-6 story `-04`).
2. **`document.note.changed`**: đọc nội dung note hiện hành, ghi lại đúng 1
   segment `content_source='note'` (xoá/upsert theo `segment_index` cố định
   dành riêng cho note, ví dụ `segment_index=-1` — quy ước nội bộ, không lộ
   ra response).
3. **`document.ingested`**: upsert `document` (metadata cơ bản) + segment
   `content_source='title'` (tên tệp + nhãn hiện có, thường rỗng lúc mới
   nạp) — cho phép tìm theo tên tệp ngay cả khi chưa gán nhãn.
4. **`review.confirmed`/`review.reconfirmed`**: đọc toàn bộ
   `content_segment` (`current_text`, toạ độ) của `run_id` vừa confirmed,
   bulk upsert vào `segment` — mục tiêu ≤ 5s (p95)/≤30s (p99) kể từ sự kiện
   (NFR SA mục 3). Segment thuộc run **cũ** bị `superseded` bởi run mới
   (theo `digitization-results-store.md`) → **xoá** khỏi index (`run_id` cũ
   không còn active).
5. **`acl.changed`**: `update_by_query` cập nhật `scope_group_ids` (đọc lại
   toàn bộ `document_group_share` hiện hành của `documentId`, không chỉ áp
   dụng riêng lẻ từng thay đổi — tránh lệch khi có nhiều thay đổi dồn dập)
   trên mọi segment + document — **ưu tiên cao hơn** các event khác trong
   hàng đợi poll (xử lý theo batch riêng, poll trước, không xen lẫn thứ tự
   FIFO chung, đúng "hàng đợi ưu tiên cao" QĐ5 SA — cách hiện thực hoá ưu
   tiên trong 1 bảng SQL đơn giản: `ORDER BY (event_type='acl.changed')
   DESC, id ASC`).
6. **`document.trash`/`document.restored`/`document.purged`** (bổ sung
   2026-09-20 bởi `trash-search-index-cleanup.md` — bug fix, không có ở lần
   viết đầu mục này): `document.trash`/`document.purged` xoá hẳn document +
   mọi segment của `documentId` (hoặc nhiều `documentId` cho `document.trash`)
   khỏi cả 2 index bằng `deleteByQuery`/`terms` (không đánh dấu field);
   `document.restored` build lại toàn bộ từ Postgres như `document.ingested`
   + `review.confirmed` + `document.note.changed` gộp lại — chi tiết đầy đủ ở
   spec đó, mục 2 và 4.
7. **Kho cá nhân**: không cần lọc gì thêm ở indexer — các producer trên đều
   chỉ ghi outbox cho tài liệu **kho chung** (đã đúng theo thiết kế hiện có
   của `personal-storage-quota.md`); nếu về sau một producer vô tình ghi
   outbox cho tài liệu cá nhân, đó là lỗi ở producer, không phải ở
   `search-service` — không thêm một lớp lọc "loại trừ cá nhân" ở indexer
   (đúng nguyên tắc SA "chặn ở nơi phát sự kiện, không dựa vào filter khi
   truy vấn").

### 4.4 Chống nghẽn hàng đợi indexer (sửa đổi 2026-09-18)

**Bối cảnh — sự cố thật trên stg 2026-09-18.** Index `segment`/`document`
chưa từng được tạo trên stg (OpenSearch thiếu plugin `analysis-icu`, xem
`stg/docker-compose.yml`), nên mọi ghi index fail bằng
`index_not_found_exception`. Quan sát được: **1 event `document.acl.changed`
retry 19.200 lần, 12 event xếp hàng phía sau đứng im 17 phút**, ~414 dòng
ERROR/phút. Sau khi tạo index, toàn bộ tự thông. Sự cố này phơi ra 3 khiếm
khuyết dưới đây — đều **không** phải lỗi của riêng tình huống thiếu index.

#### 4.4.1 Vấn đề 1 — head-of-line blocking (không có ngưỡng bỏ qua)

Câu claim hiện tại không lọc theo `attempts`, không có backoff, không có
DLQ, và `processed_at` chỉ được set khi thành công:

```sql
WHERE event_type IN (:eventTypes) AND processed_at IS NULL
ORDER BY (event_type = 'document.acl.changed') DESC, id ASC
LIMIT 1 FOR UPDATE SKIP LOCKED
```

`MAX_ATTEMPTS_BEFORE_ALERT = 20` **chỉ log ERROR, không bỏ qua row**. Nên một
row hỏng vĩnh viễn nằm mãi ở đầu hàng đợi và **mọi event phía sau không bao
giờ được claim** — chỉ mục đứng yên vô thời hạn trong khi `/search` vẫn trả
HTTP 200 với dữ liệu cũ. Hỏng mà không phát ra tín hiệu nào cho người dùng.

> Ghi chú phạm vi: **không** phải lỗ hổng bảo mật.
> `SearchResultHydrator.hydrate()` áp lại `BusinessDataScope.forDocuments(...)`
> trên Postgres và loại mọi tài liệu không qua được — fail-closed. Index lệch
> chỉ gây *false negative* (không tìm thấy tài liệu đáng lẽ thấy), không gây
> rò rỉ.

**Quyết định:** thêm `AND attempts < :maxAttempts` vào `claimNextForIndexing`.
Row vượt ngưỡng được **"park"** — giữ nguyên `processed_at IS NULL` +
`last_error` làm bằng chứng, nhưng không còn được claim, hàng đợi đi tiếp.
Ngưỡng dùng lại đúng hằng số `MAX_ATTEMPTS_BEFORE_ALERT` hiện có (`[ĐX]` 20),
cấu hình qua `app.search-index.max-attempts`.

Hệ quả phụ có lợi: log ERROR "manual operator attention needed" hiện bắn
liên tục mỗi lần retry, sau sửa chỉ bắn **đúng 1 lần** tại lần attempt chạm
ngưỡng — đúng ý định ban đầu của dòng log đó.

`OutboxRetentionJob` chỉ xoá row đã `processed_at IS NOT NULL` nên row bị
park **không bị dọn mất** — cố ý, để vận hành còn dấu vết điều tra.

**Bù lại rủi ro mới:** row bị park sẽ im lặng nằm đó mãi. Bắt buộc có đường
phát hiện → mục 4.4.4.

#### 4.4.2 Vấn đề 2 — bulk không chia lô

`SegmentIndexer.upsertSegments()` nhét **toàn bộ** segment của một run vào
**một** `BulkRequest`. Không có giới hạn số segment/run ở bất kỳ đâu, trong
khi upload cho phép tới 2GB/file (`spring.servlet.multipart.max-file-size`).
Hai trần chạm phải, theo thứ tự khả năng:

1. **`RESPONSE_TIMEOUT_SECONDS = 30`** (`OpenSearchClientConfig`) — bulk vài
   chục nghìn segment trên cụm 1 node heap 512MB (`OPENSEARCH_JAVA_OPTS` ở
   `stg/docker-compose.yml`) chạy quá 30s → client timeout → retry → lại
   timeout. Hỏng vĩnh viễn, và mỗi lần retry vẫn ghi thật một phần vào index
   trước khi bỏ cuộc.
2. **`http.max_content_length = 100mb`** (mặc định, đã xác nhận trên cụm
   stg) — cần cỡ 80.000 segment/run mới chạm, chỉ ở mức cực đoan.

**Quyết định:** chia lô trong `upsertSegments()`, `[ĐX]` 500
segment/bulk, cấu hình qua `app.search-index.bulk-chunk-size`.

An toàn khi retry cho `SegmentIndexer` (Bulk API): lô đã ghi thành công sẽ
trả `version_conflict_engine_exception` ở lần retry — `hasNonVersionConflictErrors()`
coi là lành tính và bỏ qua, đúng như mô tả gốc, không cần sửa gì thêm ở
đường này.

**(Sửa 2026-09-21 — BUG-002, xem `bugs/bug-002-search-index-version-conflict.md`,
đã fix trong code):** khẳng định "không cần
thêm cơ chế nào để chống ghi trùng" ở bản gốc **sai khi áp dụng cho
`DocumentIndexer.upsertDocument()`** (dùng API đơn lẻ `client.index()`,
không phải Bulk). Lỗi version-conflict ở đường này ném ra dưới dạng Java
exception — nhưng exception thực tế nhận được có thể là
`org.opensearch.client.transport.httpclient5.ResponseException` (tầng
transport thô), **không phải**
`org.opensearch.client.opensearch._types.OpenSearchException` mà
`DocumentIndexer.upsertDocument()` đang bắt (2 lớp này không liên quan kế
thừa gì nhau — đã decompile xác nhận). Kết quả: nhánh "bỏ qua êm" không bao
giờ được chạm tới, event bị coi là lỗi cứng, thử lại 20 lần (luôn thất bại y
hệt vì version không đổi qua các lần thử) rồi PARK vĩnh viễn — tài liệu mất
khỏi tìm kiếm, không có cảnh báo cho người dùng. Đã tái hiện thật trên stg
20/09/2026 (xem file bug mục 1). Nguồn gốc sâu hơn: dùng `outbox_event.id`
(thứ tự hàng đợi) làm đại diện cho "độ mới dữ liệu" là giả định sai — 2 khái
niệm không phải lúc nào cũng trùng nhau khi có hàng đợi ưu tiên, retry, hoặc
nhiều instance xử lý song song. Hướng fix đầy đủ: file bug mục 5.2, **đã
triển khai trong code** (xem entry đầu file này, 2026-09-21 (3)).

Hợp đồng giữ nguyên: trả `true` chỉ khi **mọi** lô thành công; một lô hỏng ⇒
`false` ⇒ row được retry nguyên vẹn.

#### 4.4.3 Vấn đề 3 — 18 job `@Scheduled` dùng chung 1 thread

Không có `spring.task.scheduling.pool.size` trong `application.yml` và không
có bean `TaskScheduler` riêng ⇒ Spring Boot mặc định pool size **1**. Đã xác
minh trên stg: log chỉ từng xuất hiện đúng một tên thread `scheduling-1`.

`poll()` lặp tới `BATCH_LIMIT = 200` lần mỗi tick. Với vấn đề 2 (mỗi lần ăn
trọn 30s timeout), một tick có thể giữ thread đó rất lâu, làm trễ toàn bộ 17
job còn lại — auto-confirm, trash purge, audit sealer, lease reconciliation...

**Quyết định:** đặt `spring.task.scheduling.pool.size` `[ĐX]` 4, cấu hình
qua biến môi trường `SCHEDULING_POOL_SIZE`.

**Đã thẩm định an toàn đồng thời trước khi chốt** (đây là thay đổi ngữ nghĩa
thật: các job khác nhau bắt đầu chạy song song):

- Spring không bao giờ cho một `@Scheduled fixedDelay` chồng lấn **chính
  nó**, bất kể pool size. Chỉ job *khác nhau* mới chạy song song.
- Các job claim việc đều dùng idiom `UPDATE ... WHERE id IN (SELECT ...
  FOR UPDATE SKIP LOCKED) RETURNING *` (`autoConfirmDueBatch`,
  `claimStaleForAlert`, `claimNextForIndexing`, ...) — nguyên tử và an toàn
  đồng thời **theo thiết kế**, không phải nhờ tình cờ có 1 thread.
- `TrashPurgeJob`/`PurgeStuckRecoveryJob` có `@SchedulerLock` riêng.
- `OutboxRetentionJob` (xoá row `processed_at IS NOT NULL`) và
  `SearchIndexOutboxPoller` (claim row `IS NULL`) thao tác trên 2 tập rời
  nhau.
- Kiến trúc vốn đã tuyên bố hỗ trợ nhiều instance (javadoc
  `claimNextForIndexing`: *"a second poller instance (horizontal scaling)"*),
  tức mức đồng thời này đã nằm trong giả định sẵn có.

#### 4.4.4 Đường phát hiện row bị park (bắt buộc, đi kèm 4.4.1)

Không có mục này thì 4.4.1 chỉ đổi "nghẽn ồn ào" thành "mất dữ liệu im lặng".

`SearchIndexReconciliationJob` (mục 4.3, cron 03:00) đếm thêm số row
`processed_at IS NULL AND attempts >= maxAttempts` và log **ERROR** kèm danh
sách `id` (giới hạn `[ĐX]` 20 id đầu) nếu > 0. Job này vốn đã là bộ dò lệch
read-only nên đây đúng chỗ, không phát sinh job mới.

### 4.3 Đối soát định kỳ

`SearchIndexReconciliationJob` (mẫu tương tự
`DocumentDigitizationStatusReconciliationJob` đề xuất ở
`digitization-results-store.md`): so số lượng `content_segment` đã confirmed
theo `document_id` với số segment tương ứng trong index `segment`
(`_count` theo `terms(document_id)`), log lệch > 0,1% (NFR SA mục 3), không
tự sửa — chạy ngoài giờ cao điểm.

## 5. Test plan

- Unit test `SearchServiceImpl.lexicalSearch()`: không gọi
  `SearchResultHydrator` (verify bằng mock, đảm bảo tách bạch trách nhiệm);
  trả đúng `topK` hit sắp theo `bm25Score` giảm dần.
- **Test hồi quy phạm vi cho `lexicalSearch()` khi dùng từ `search-semantic`
  (rủi ro Cao, nhắc lại theo yêu cầu chéo 2 slug):** gọi `lexicalSearch()`
  trực tiếp (không qua `/search`) với `scopeCtx` của user A, dữ liệu chứa
  segment của user B (khác nhóm) khớp từ khóa mạnh → segment của B **vẫn**
  xuất hiện trong `List<LexicalHit>` thô (đúng thiết kế — chưa double-check),
  nhưng test phải khẳng định rõ ràng trong docstring/comment: **caller bắt
  buộc gọi hydrate trước khi trả ra ngoài** — thêm 1 test tích hợp ở mức
  `SemanticSearchService` (thuộc spec `search-semantic`, không phải ở đây)
  xác nhận điều này, ghi chú tham chiếu chéo trong cả hai bộ test.
- Integration test indexer (Testcontainers Postgres + OpenSearch):
  - `document.classification.changed` → `topic_tags`/`doc_type` trên mọi
    segment của tài liệu cập nhật đúng trong ≤ 5s (poll interval + độ trễ
    `update_by_query`, dùng `Awaitility`).
  - `review.confirmed` → nội dung tìm được trong ≤ 30s (test với `Awaitility
    atMost`), nội dung **chưa** confirmed không xuất hiện (AC-1 story `-04`).
  - `acl.changed`: gỡ chia sẻ nhóm → tài liệu biến mất khỏi kết quả tìm kiếm
    của thành viên nhóm đó trong ≤ 5s; tài liệu **không bao giờ** xuất hiện
    lại dù index cập nhật trễ (double-check ACL ở `/search` là lưới an toàn
    cuối, test kết hợp cả bằng `/search` thật lẫn bằng đọc thẳng OpenSearch
    để phân biệt "chỉ mục trễ" và "rò rỉ").
  - External versioning: gửi 2 sự kiện `review.confirmed` cho cùng
    `document_id` theo thứ tự đảo ngược (`outbox_event.id` lớn xử lý trước
    do giả lập race condition) → bản ghi cuối trong OpenSearch phải khớp sự
    kiện có `id` lớn hơn, không bị ghi đè lùi (idempotent).
  - Đối soát AC-6 (gán nhãn hàng loạt): giả lập 500 sự kiện
    `document.classification.changed` liên tiếp → tìm kiếm của user khác
    (không liên quan tới batch này) vẫn phản hồi trong ngân sách `p95 ≤ 1s`
    (đo bằng test tải nhẹ song song, không cần công cụ load-test riêng, đủ
    ở mức unit/integration để phát hiện regression thô).
- Fallback: OpenSearch tắt hẳn khi indexer đang chạy → `SearchIndexOutboxPoller`
  không đánh dấu `processed_at` (retry ở vòng sau), không làm crash job
  (bọc try/catch quanh từng batch).

- **Mục 4.4.1 — park row hỏng:** seed 3 `outbox_event` cùng loại; làm row
  đầu hỏng vĩnh viễn (mock indexer ném `IOException`). Chạy poller tới khi
  row đầu đạt `maxAttempts`. Khẳng định: (a) row đầu `processed_at IS NULL`
  và `attempts == maxAttempts`, **không** tăng thêm ở các tick sau; (b) row 2
  và 3 **đã** `processed_at IS NOT NULL` — chứng minh hàng đợi không còn bị
  chặn; (c) log ERROR "manual operator attention needed" xuất hiện **đúng 1
  lần**.
- **Mục 4.4.1 — hồi quy:** row hỏng *tạm thời* (fail 2 lần rồi thành công)
  vẫn phải được xử lý bình thường, không bị park oan.
- **Mục 4.4.2 — chia lô:** `upsertSegments()` với `2 * chunkSize + 1` segment
  ⇒ đúng 3 lần gọi `client.bulk()`, mỗi lần ≤ `chunkSize` operation, và mọi
  `segment_id` đều có mặt đúng 1 lần trên toàn bộ các lô.
- **Mục 4.4.2 — một lô hỏng:** lô thứ 2 trả lỗi không phải version-conflict
  ⇒ `upsertSegments()` trả `false` (row sẽ được retry), nhưng lô 1 và 3 vẫn
  đã được gửi đi.
- **Mục 4.4.2 — retry idempotent:** gọi lại `upsertSegments()` cùng
  `externalVersion` ⇒ toàn bộ trả `version_conflict_engine_exception`, hàm
  vẫn trả `true`.
- **Mục 4.4.4:** có row bị park ⇒ `SearchIndexReconciliationJob` log ERROR
  kèm số lượng và id; không có row nào bị park ⇒ không log ERROR.

## 6. Task breakdown

- [x] Task 1: `SearchService` interface + `SearchServiceImpl.lexicalSearch()`.
- [x] Task 2: `SegmentIndexer`/`DocumentIndexer` (bulk upsert/update_by_query/
      delete helper dùng chung bởi cả indexer thật lẫn test fixture).
- [x] Task 3: `SearchIndexOutboxPoller` (`@Scheduled`, `FOR UPDATE SKIP
      LOCKED`, xử lý 5 loại sự kiện mục 4.2, throttle `update_by_query`).
- [x] Task 4: **Thêm outbox write cho `acl.changed`** (đã chốt phương án
      2026-09-07, xác nhận bằng đọc code — `GroupsService`/document-sharing
      chưa ghi outbox nào khi chia sẻ/gỡ chia sẻ tài liệu). Thêm class
      `AclChangeOutbox` (static helper, copy đúng cấu trúc
      `com.aismartstore.backend.tags.ClassificationOutbox`), event_type
      `document.acl.changed`, payload `{documentId, groupId, action:
      "shared"|"unshared", changedAt, changedBySub}`. Gọi ở **cả 3 điểm ghi/
      xoá `DocumentGroupShare`** trong `GroupsService`: `shareDocument`
      (sau `documentGroupShareRepository.save`), `unshareDocument` (sau
      `documentGroupShareRepository.delete`), `shareDocumentBulkItem` (sau
      `save` trong nhánh thành công). **Việc này sửa code thuộc slug
      `group-management-sharing`** — cần phối hợp/xin xác nhận phạm vi PR
      trước khi code, ghi rõ trong mô tả PR tham chiếu spec này.
- [x] Task 5: **Thêm outbox write cho `document.ingested`** (đã chốt phương
      án 2026-09-07, xác nhận bằng đọc code —
      `DocumentsService.ingestBatchFile` hiện chỉ publish
      `DocumentIngestedEvent`, một Spring `ApplicationEvent` **nội bộ** để
      trigger `DigitizationDispatchListener`, **không** ghi `outbox_event`).
      Thêm 1 outbox write riêng (payload `{documentId, changedAt}`) ngay sau
      bước `docTypeAssignmentService.assignOnIngest(...)`, cùng vị trí với
      chỗ publish `DocumentIngestedEvent` hiện có — 2 cơ chế song song, không
      thay thế nhau (event nội bộ phục vụ pipeline số hoá, outbox phục vụ
      `search-service`). **Việc này sửa code thuộc slug `data-ingestion`** —
      cần phối hợp/xin xác nhận phạm vi PR trước khi code.
- [x] Task 6: `SearchIndexReconciliationJob`.
- [x] Task 7: Audit `search.executed` — thêm entry vào
      `AuditService.ALLOWED_METADATA_KEYS`:
      `"search.executed", Set.of("query", "filters", "resultCount",
      "sort", "droppedGroupIds")` (đúng pattern `note.create`/`note.update`
      hiện có) — `query` đã cắt ≤ 200 ký tự **trước khi** đưa vào metadata
      (cắt ở `SearchController`, không dựa vào allowlist để cắt bớt độ dài).
      Gọi `AuditService.recordAsync(...)` sau khi trả response (không chặn
      latency truy vấn — đúng NFR "double-check ACL ≤ 100ms" không tính audit).
- [x] Task 8: Toàn bộ test plan mục 5.
- [x] Task 9 (mục 4.4.1): thêm `AND attempts < :maxAttempts` vào
      `OutboxEventRepository.claimNextForIndexing`, tham số hoá ngưỡng qua
      `app.search-index.max-attempts` (mặc định 20, dùng lại giá trị của
      `MAX_ATTEMPTS_BEFORE_ALERT`). Kèm Task 11 — **không** được tách rời.
- [x] Task 10 (mục 4.4.2): chia lô trong `SegmentIndexer.upsertSegments()`
      theo `app.search-index.bulk-chunk-size` (mặc định 500); giữ nguyên hợp
      đồng trả `true` chỉ khi mọi lô thành công. Không đổi
      `DocumentIndexer` (mỗi lần chỉ ghi 1 document, không có vấn đề lô).
- [x] Task 11 (mục 4.4.4): `SearchIndexReconciliationJob` đếm + log ERROR số
      row bị park. Thêm `countParked`/`findParkedIds` vào
      `OutboxEventRepository`.
- [x] Task 12 (mục 4.4.3): `spring.task.scheduling.pool.size:
      ${SCHEDULING_POOL_SIZE:4}` trong `application.yml`.
- [x] Task 13 (phát sinh khi test Task 9-12): nâng
      `<testcontainers.version>` lên `1.21.4` trong `pom.xml`, đè bản 1.19.8
      mà `spring-boot-starter-parent` 3.3.4 quản lý. 1.19.8 kéo theo
      docker-java 3.3.6 thương lượng Docker Engine API **1.32**, trong khi
      Docker Engine 29 đã bỏ mọi API dưới **1.44** — nên trên host Docker đời
      mới **toàn bộ 33 IT class của dự án chết trước khi chạy** với
      `Could not find a valid Docker environment ... client version 1.32 is
      too old`. Phát hiện thật trên máy stg (Engine 29.2.1) ngày 2026-09-18
      khi chạy IT của Task 9.

      Chỉ thuộc scope `test` nên **không đụng tới artifact chạy thật** — đã
      kiểm chứng bằng cách diff `mvn dependency:list -DincludeScope=runtime`
      trước/sau: giống hệt, 162 mục, không có testcontainers. Không lấy 2.x
      (major, có breaking change).

      Cạm bẫy: lỗi này **vô hình trên Docker đời cũ** (Mac dev dùng Engine
      27.4.0, MinAPI 1.24 — bản 1.19.8 chạy bình thường) trong khi stg hỏng
      hoàn toàn. Đừng gỡ dòng override này chỉ vì "test local vẫn xanh".

## 7. Rủi ro kỹ thuật

- **(Phát hiện 2026-09-21 — BUG-002, đã fix trong code) Xung đột version giả trên
  `DocumentIndexer.upsertDocument()` khiến event PARK oan, dù không hề có
  event nào khác thật sự "mới hơn" xen vào.** Khác với mục park ở dòng dưới
  (dành cho lỗi ghi OpenSearch thật sự, ví dụ mất kết nối kéo dài), đây là
  PARK **giả** — do bắt sai loại exception khi OpenSearch từ chối ghi vì lý
  do version (xem mục 4.2/4.4.2 đã sửa cùng ngày). Kích hoạt bởi: (a) trục
  trặc thoáng qua ngay giữa 2 bước ghi document+segment của cùng 1 event
  (khả năng cao nhất, không cần điều kiện đặc biệt); (b) xoá rồi khôi phục
  rất nhanh cùng 1 tài liệu (đụng tombstone 60s mặc định của OpenSearch);
  (c) nhiều instance backend xử lý song song (chưa xảy ra được trên stg —
  hiện chỉ 1 instance — nhưng có thật nếu scale production). Hậu quả: tài
  liệu mất khỏi tìm kiếm vĩnh viễn, không có lỗi trả về cho người dùng ở bất
  kỳ API nào (upload/duyệt/khôi phục đều báo thành công bình thường). Chi
  tiết đầy đủ, bằng chứng tái hiện trên stg, và hướng fix đã rà soát (đổi
  nguồn version từ `outbox_event.id` sang độ mới dữ liệu thật + `ExternalGte`):
  `bugs/bug-002-search-index-version-conflict.md`. **Đã fix trong code** (xem
  entry đầu file này, 2026-09-21 (3)).
- **(Sửa đổi 2026-09-18) Row bị park là đánh đổi có chủ ý, không phải giải
  pháp trọn vẹn.** Sau Task 9, một event hỏng vĩnh viễn **không** còn chặn
  hàng đợi, nhưng bản thân nó vẫn **không bao giờ được index** — chỉ mục sẽ
  thiếu đúng tài liệu đó cho tới khi có người xử lý tay. Đây là đổi "hỏng
  toàn hệ thống, im lặng" lấy "hỏng 1 tài liệu, có log ERROR + có đếm ở job
  đối soát". **DLQ thật + backoff vẫn là việc còn nợ** — và sau quyết định
  2026-09-20 (giữ indexer trong `backend-api`), việc đó **không còn tự đến
  kèm outbox-relay chung nữa**: nếu muốn có, phải chủ động làm ngay trong
  `backend-api`. Đừng coi Task 9 là đã đóng vấn đề.
- **(Sửa đổi 2026-09-18) `bulk-chunk-size` 500 là con số `[ĐX]`,** chọn theo
  suy luận chứ chưa đo trên dữ liệu thật: chưa có run nào đủ lớn trên stg để
  đo. Cần đo lại p95 thời gian bulk khi có tài liệu vài trăm trang thật, rồi
  chỉnh — biến môi trường đã sẵn, không cần build lại.
  **(Bổ sung 2026-09-20)** `SegmentIndexer.upsertChunk` giờ log DEBUG mỗi lần
  gọi `client.bulk()` — số operation, thời gian round-trip (client-side, đối
  chiếu trực tiếp với `RESPONSE_TIMEOUT_SECONDS=30`) và `took` (server-side,
  do chính OpenSearch trả về, tách riêng khỏi độ trễ mạng). Bật DEBUG cho
  `com.aismartstore.backend.search.index.SegmentIndexer` trên stg khi có tài
  liệu vài trăm trang thật đi qua là đủ dữ liệu để tính p95 — không cần sửa
  code thêm để làm phép đo này nữa, chỉ còn thiếu **dữ liệu thật** để bật lên
  và đọc log.
- **(Ghi nhận 2026-09-20, chưa sửa) Audit ghi bất đồng bộ, ngoài transaction
  của `confirmRun`.** `auditService.recordAsync(...)` (`@Async`) chạy trên
  thread khác, không tham gia transaction Spring của `confirmRun`/
  `reviseSegment`/`deleteSegment`/`addManualSegment` — khác với
  `outbox_event`, luôn ghi **cùng transaction** nên không bao giờ lệch. Nếu
  transaction rollback sau thời điểm gọi `recordAsync` (ví dụ lỗi flush ở
  commit), `audit_log` vẫn còn một dòng ghi nhận một hành động **chưa từng
  xảy ra thật**. Không sửa ở file này: `recordAsync` là quy ước dùng chung
  toàn bộ 19 nơi trong codebase (`GroupsService`, `TagsService`,
  `DocumentsService`, ...), không phải lỗi cục bộ của
  `DigitizationRunLifecycleServiceImpl` — sửa một chỗ sẽ tạo lệch quy ước với
  18 chỗ còn lại. Cần quyết định ở tầm kiến trúc (ví dụ
  `TransactionSynchronizationManager.registerSynchronization` chạy sau
  commit, hoặc chấp nhận đánh đổi hiện tại là có chủ đích cho toàn hệ thống)
  trước khi đổi, không phải một patch tại chỗ.
- **(Ghi nhận 2026-09-20, chưa sửa) Không gộp sự kiện `review.reconfirmed`.**
  Mỗi lần sửa/xoá/thêm đoạn trên một run đã `confirmed` ghi đúng 1
  `outbox_event('review.reconfirmed')` (`applyReviewStateEditSideEffect`), và
  `SearchIndexOutboxPoller.handleReviewConfirmed` index lại **toàn bộ**
  segment của run đó (`findByRunIdOrderBySegmentIndex` + một `upsertSegments`
  cho cả run) — không phải chỉ đoạn vừa sửa. Sửa 10 đoạn liên tiếp trên cùng
  1 run = 10 lần index lại toàn bộ run đó. Không sai (external versioning +
  `hasNonVersionConflictErrors` vẫn giữ idempotent/nhất quán), chỉ lãng phí
  bulk request/băng thông OpenSearch tỉ lệ với `segmentCount × số lần sửa`
  thay vì chỉ `số lần sửa`. Chưa sửa vì chưa có phương án an toàn rõ ràng:
  gộp sự kiện (dedup theo `run_id` tại thời điểm ghi hoặc tại thời điểm
  claim) cần tránh race với chính cơ chế `claimNextForIndexing` (`FOR UPDATE
  SKIP LOCKED`) đang dùng, để không vô tình bỏ sót một lần sửa chưa kịp phản
  ánh; đổi sang index từng đoạn một (thay vì cả run) lại đổi ngữ nghĩa `-
  reconfirmed` khác với `-confirmed` (cần thêm `segmentId` vào payload). Cả
  hai hướng đều cần quyết định thiết kế trước khi làm, không phải sửa tại
  chỗ. Ảnh hưởng thực tế hiện nay thấp: chỉ tốn thêm khi một run đã confirmed
  bị sửa nhiều lần liên tiếp trong thời gian ngắn — không phải luồng chính
  (luồng chính sửa khi còn `pending`/`in_review`, chỉ 1 lần index lúc
  confirm).
- **(Sửa đổi 2026-09-18) Tăng pool size là thay đổi ngữ nghĩa đồng thời**
  dù đã thẩm định ở mục 4.4.3. Rủi ro còn lại: một job *tương lai* không dùng
  idiom claim nguyên tử sẽ hỏng theo cách khó tái hiện. Quy ước bổ sung: mọi
  job `@Scheduled` mới **phải** hoặc dùng `FOR UPDATE SKIP LOCKED`, hoặc gắn
  `@SchedulerLock` — không được ngầm dựa vào việc scheduler chỉ có 1 thread.
- **(Cập nhật 2026-09-20) Không dùng RabbitMQ cho đường index — đã chốt, còn
  nợ phần DLQ.** `ai-search-infra.md` QĐ 7 thiết kế outbox→RabbitMQ→indexer;
  bản hiện thực bỏ khâu RabbitMQ, poll thẳng bảng (mục 4.2, đã ghi nhận sai
  lệch tại chính QĐ 7). Phần **cơ chế nhất quán** của QĐ 7 giữ nguyên
  (cùng transaction, external versioning, eventually consistent, job đối
  soát). Phần **chưa đạt chuẩn QĐ 7** và phải theo dõi như nợ kỹ thuật thật:
  không có retry backoff theo bậc (5s/30s/5m), không có DLQ, không có công cụ
  xem/replay — hiện chỉ có park sau 20 lần + log ERROR + đếm ở job đối soát
  (mục 4.4.1/4.4.4).
  Rủi ro còn lại: nếu về sau có **consumer thứ hai** cần đọc cùng bảng
  `outbox_event` cho mục đích khác, hai slug sẽ tự làm hai kiểu tiêu thụ khác
  nhau trên cùng một bảng. Khi đó mới là lúc cân nhắc relay chung — không
  phải bây giờ.
- **Thiếu producer `acl.changed`** (Task 4) là lỗ hổng thật đã xác nhận trong
  code, **phương án đã chốt** (helper `AclChangeOutbox`, 3 điểm gọi trong
  `GroupsService`) — nếu không làm, AC-4/AC-6 (chia sẻ/gỡ chia sẻ phản ánh vào
  tìm kiếm) sẽ không hoạt động dù toàn bộ phần OpenSearch đã đúng. Rủi ro
  **Cao** vì ảnh hưởng đúng đắn của phạm vi hiển thị (gỡ chia sẻ chậm phản
  ánh ⇒ tài liệu vẫn "tìm thấy" qua chỉ mục cũ — dù double-check ACL ở
  hydrate vẫn chặn được rò rỉ thật sự, chỉ gây "trễ ẩn" khó phát hiện nếu
  không test kỹ); còn lại là rủi ro phối hợp liên-slug (PR sửa code thuộc
  `group-management-sharing`).
- **`document.ingested` chưa ghi outbox** — đã xác nhận bằng đọc code
  (`DocumentsService`, mục 4.2 bước 3/Task 5): hiện chỉ có
  `DocumentIngestedEvent` (Spring event nội bộ), **phương án đã chốt** (thêm
  outbox write riêng, cùng vị trí). Rủi ro còn lại thuần là phối hợp
  liên-slug (`data-ingestion`), không còn điểm kỹ thuật chưa rõ.
- ~~Phụ thuộc `digitization-results-store.md`/`digitization-review.md` còn
  Draft~~ — cả 2 đã `Approved` 2026-09-07, cùng `digitization-review-01-
  ocr.md`/`-02-stt.md` (tech spec DEV, cũng Approved): cấu trúc sự kiện
  `review.confirmed` đã chốt cụ thể ở `digitization-review-01-ocr.md` §3.4
  (`confirmRun`) — payload khớp giả định ở mục 4.2 bước 4, không cần rà lại.
- **`SearchIndexOutboxPoller` dùng chung bảng `outbox_event` với các consumer
  khác** (nếu `classification-notes`/`notes` cũng tự poll bảng này cho mục
  đích riêng của họ) — cần đảm bảo mỗi consumer lọc đúng `event_type` của
  mình và không tranh chấp khoá `FOR UPDATE SKIP LOCKED` một cách không cần
  thiết; kiểm tra chéo với các slug khác khi implement.
