# Tech Spec: search-semantic

> File: `docs/03-dev/specs/search-semantic.md` — Owner: DEV
> Nguồn story: `docs/02-ba/stories/search-semantic.md` (STORY-search-semantic)
> Nguồn kiến trúc: `docs/01-sa/architecture/search-semantic.md` (ARCH-search-semantic, QĐ 1–6; **QĐ 6 ngày 2026-10-10**), `docs/01-sa/architecture/ai-search-infra.md` (QĐ 2, 4, 5, 7, mục 5, 8 — ghi chú sửa 2026-10-10)
> Status: Approved (người dùng duyệt 2026-10-11)
> Spec ID: `SPEC-search-semantic`

File chính = mục lục + các mục dùng chung (1, 6, 9, 10, 11, 12). Chi tiết tách
theo domain (spec >300 dòng, AGENTS.md §3.8):

| File | Nội dung | Mục template |
|---|---|---|
| [`search-semantic-indexing.md`](search-semantic-indexing.md) | Mapping k-NN, embed + ghi vector vào OpenSearch (đường ghi), `folder.moved` | 2, 4, 5, 7, 8 |
| [`search-semantic-query.md`](search-semantic-query.md) | API `POST /search/semantic`, k-NN + lexical, RRF, suy giảm, audit, frontend | 2, 3, 4, 5, 7, 8 |

> **Viết lại 2026-10-10** theo kiến trúc mới (SA QĐ 6): **không còn Qdrant, không
> TEI/bge-m3**. Vector là field `knn_vector` trên index `segment` của OpenSearch;
> embedding qua endpoint REST nội bộ (Qwen3-Embedding-0.6B). Các bản spec cũ
> (Qdrant/TEI/`segment_embedding`/backfill) đã bị thay thế hoàn toàn. Module
> `search` đã có `SearchService.lexicalSearch`, `ScopeContext`,
> `ScopeFilterBuilder`, `SearchResultHydrator`, `SearchIndexOutboxPoller`,
> `SegmentIndexer`, `SearchIndexBootstrap`; chưa có dòng code embed/k-NN nào.

## 1. Tóm tắt kỹ thuật

- **Đọc (query):** `POST /search/semantic` (`SemanticSearchController`, package
  `com.aismartstore.backend.search`). `SemanticSearchService` chạy song song: embed
  câu hỏi (endpoint REST) → truy vấn **k-NN trên index `segment`** với `filter`
  đặt **trong mệnh đề `knn`** (efficient filtering, faiss) và
  `SearchService.lexicalSearch` (đã có). Hợp nhất RRF k=60, lọc ngưỡng căn cứ
  (cosine ≥ `semantic.min_score` **hoặc** có mặt ở nhánh lexical), hydrate +
  double-check ACL từ PostgreSQL, trả đoạn trích có dẫn nguồn. Không rerank, không
  LLM, luôn hybrid (request không có `mode`).
- **Ghi (index):** `SearchIndexOutboxPoller` (đã có) phát `embed.requested` vào
  queue `q.embed` sau `review.confirmed`/…; `EmbedRequestConsumer` (trong
  `backend-api`) dựng `text_embed` (segment + cửa sổ lân cận), gọi endpoint embed
  theo batch, **partial update** field `embedding` + `model_version` vào document
  `segment`. ACL/classification/trash/purge/di chuyển thư mục đi **cùng đường cập
  nhật document `segment`** của nhánh lexical — vector không cần đường riêng.
- **Frontend:** trang `/search/semantic`, menu "Tra cứu ngữ nghĩa", dùng lại
  `SearchFilterPanel`, `SearchResultCard`, `SearchPageViewer`; không sửa `/search`.
- **Quyết định kỹ thuật DEV chốt thêm (ngoài SA):**
  1. **Một bộ filter duy nhất:** `filter` trong `knn` = `ScopeFilterBuilder.build(ctx)`
     + clause FR-23 của `SearchQueryBuilder`/`FilterGroup` (tách method dùng chung nếu
     đang private). Không có `QdrantFilterBuilder`, không có test "hai bộ filter tương
     đương"; thay bằng test: cùng `Query` filter được truyền cho cả hai nhánh. **Cấm
     `post_filter`** (test cấu trúc request).
  2. **Đổi mapping = index mới** `segment-v2` (có `index.knn: true`) + reindex + đổi
     alias `segment` bằng cơ chế hiện có của `SearchIndexBootstrap`/`SearchIndexNames`.
     Dữ liệu khởi điểm trắng ⇒ dev/stg chỉ cần tạo `segment-v2` mới (xem §9).
  3. **Bỏ bảng `segment_embedding`** (không có migration V37). Idempotency = ghi đè cùng
     vector (partial update idempotent) + `model_version` trên document; đối soát bằng
     truy vấn OpenSearch (`exists` vector) so với tập segment đủ điều kiện ở PostgreSQL.
     Lý do bỏ: text_hash chỉ để tiết kiệm lượt embed, mà sự kiện embed hiếm và rẻ; thêm
     một nguồn sự thật thứ hai dễ lệch với chỉ mục.
  4. **Lưu ý then chốt:** `SegmentIndexer.upsertSegments` dùng thao tác `index` (ghi đè
     cả document) ⇒ mỗi lần lexical re-index một segment, **vector của segment đó mất**.
     Vì vậy mọi sự kiện làm lexical re-index segment đều phải kèm `embed.requested` cho
     đúng tập segment đó (poller §4.1 indexing), và job đối soát vá những segment thiếu
     vector (T9). Các thao tác `updateByQuery` (ACL, folder) giữ nguyên vector.
  5. Dùng lại `common.TooManyRequestsException` (429), `ApiException`.

## 6. Task breakdown (map tới AC)

Thứ tự: nền tảng → đường ghi → đường đọc → FE → test/vận hành. Mã `Tn` dùng chung ba file.

**Nền tảng**
- [x] **T1** (hạ tầng, → AC-4, AC-5): `application.yml`: `app.embedding.*` (`url: ${EMBEDDING_API_URL}`,
      `batch-size` 32, `timeout-ms` 500 (embed câu hỏi), `batch-timeout-ms` 30000 (embed nền) — Q-7,
      `model-name: Qwen/Qwen3-Embedding-0.6B`, `model-version`, `dimension: 1024`,
      `chunk-size-tokens` 512, `chunk-overlap-tokens` 64, `query-instruction-enabled` = `true`,
      `query-instruction` = `Given a Vietnamese question, retrieve relevant passages from administrative documents` — Q-8), `app.queue.embed-name=q.embed`, `app.semantic.enabled`,
      `app.semantic.query-concurrency=8`; `EmbeddingProperties`. Không thêm dependency mới.
- [x] **T2** (hạ tầng, → AC-5): **gỡ Qdrant**: xoá service `qdrant`, volume `qdrant_data`,
      `depends_on: qdrant`, env `QDRANT_HOST/PORT/URL` của backend ở `docker-compose.yml` và
      `docker-compose.dev.yml`; grep `QDRANT|qdrant` trong `backend/` (tài liệu triển khai `docs/00-overview/**`
      đã cập nhật 2026-10-10, chỉ rà lại); thêm env `EMBEDDING_API_URL` cho backend
      (dev: `https://embedding.aitutor.io.vn/ai/services/v1/embedding/embed`; stg/prod: URL dịch
      vụ nội bộ). Không thêm `tei-embed`.
- [x] **T3** (→ AC-3, AC-7): `SystemConfigDefaults` thêm `semantic.top_k`=50 (≤200),
      `semantic.min_score`=0,50 (∈[0,1], giá trị khởi điểm cho Qwen3 — Q-9; hiệu chỉnh bằng tập vàng ở T23) + validate;
      R3 sửa được như `search.*`. `AuditService.ALLOWED_METADATA_KEYS` thêm `mode`, `degradedMode`,
      `minScore` cho `search.executed`.
- [x] **T4** (→ AC-1, AC-4): mapping `segment-v2-index.json` (thêm `embedding` knn_vector 1024 +
      `model_version` keyword + `index.knn`), cập nhật `SearchIndexNames`/`SearchIndexBootstrap`,
      runbook reindex v1→v2 + swap alias (indexing §2.1, §9). Spike bắt buộc: xác nhận
      `space_type`/`mode: on_disk`/`compression_level` chạy được trên OpenSearch 2.17.1 trong
      `opensearch/Dockerfile` (xem R2).
- [x] **T5** (→ AC-5, AC-1): `EmbeddingClient` (POST theo hợp đồng, validate số phần tử = số inputs và
      mỗi vector đúng 1024 chiều, lỗi ⇒ `EmbeddingException`), bọc instruction Qwen3 cho câu hỏi
      (cấu hình, tắt được), cache Redis `hash(instruction+query)+model_version` TTL 10'.

**Đường ghi (chi tiết: indexing)**
- [x] **T6** (→ AC-2.4, AC-2.5): `RabbitConfig` khai báo `q.embed`/`.retry`/`.dlq` đúng tham số đã tạo
      tay trên stg (indexing §9); `EmbedRequestMessage`.
- [x] **T7** (→ AC-2.4, AC-2.5, AC-4.3): `SearchIndexOutboxPoller` phát `embed.requested` cho
      `review.confirmed`/`review.reconfirmed`/`document.ingested`(title)/`document.note.changed`/
      `document.restored` — đúng tập segment vừa lexical re-index; loại `sed`; kho cá nhân không bao giờ.
- [x] **T8** (→ AC-2.4, AC-2.5): `EmbedTextBuilder` (cửa sổ lân cận; segment quá dài embed nguyên nội
      dung, không sub-point) + `EmbedRequestConsumer` + `SegmentVectorWriter` (bulk partial `update`
      field `embedding`+`model_version`).
- [x] **T9** (→ AC-2.4): `SemanticIndexReconciliationJob` (segment đủ điều kiện thiếu vector/sai
      `model_version` ⇒ republish `embed.requested`), cảnh báo `q.embed.dlq`, outbox parked.
- [x] **T10** (→ AC-4, AC-4.3, AC-1.1): outbox event `folder.moved` từ `WarehouseService.moveFolder` +
      poller `updateByQuery` cập nhật `folder_path_ids` cho document/segment trong cây con (sửa luôn
      lỗ hổng của nhánh fulltext, Q-3).

**Đường đọc (chi tiết: query)**
- [x] **T11** (→ AC-4, AC-4.1, AC-4.3): `KnnSegmentSearcher` — truy vấn `knn` với filter trong mệnh đề,
      dùng `ScopeFilterBuilder` + FR-23 chung, fail-closed; chuyển điểm k-NN → cosine.
- [x] **T12** (→ AC-1, AC-1.3, AC-3): `HybridFusion` (RRF k=60, ngưỡng căn cứ).
- [x] **T13** (→ AC-1, AC-3, AC-5, AC-5.1, AC-5.2, AC-6): `SemanticSearchService` (luồng song song, suy
      giảm từng phần, phân trang ≤2 trang, `insufficientEvidence`).
- [x] **T14** (→ AC-5.3): `SemanticQueryLimiter` — một `Semaphore(8)` toàn hệ thống (per instance), vượt ⇒ 429.
- [x] **T15** (→ AC-2, AC-2.1, AC-2.5, AC-8, AC-8.1): `SemanticResultHydrator` — dựng `SemanticResult` từ
      `ContentSegment` + `Document` + trạng thái số hoá; loại `sed`.
- [x] **T16** (→ AC-4.2, AC-5.2, AC-5.3, AC-6): `SemanticSearchController` + DTO + mã lỗi (403
      `SCOPE_UNRESOLVED`, 503 `SEARCH_UNAVAILABLE`, 429 `SEMANTIC_BUSY`), `PERM_document.read`.
- [x] **T17** (→ AC-7, AC-7.1, AC-7.2): audit `search.executed` async, kể cả 403/503/429.

**Frontend (chi tiết: query §4.3)**
- [x] **T18** (→ AC-1.4): `features.ts` mục "Tra cứu ngữ nghĩa" (R1/R2) + route `/search/semantic`.
- [x] **T19** (→ AC-1, AC-1.1, AC-1.2, AC-1.3, AC-2, AC-2.1, AC-2.2, AC-2.3, AC-2.5, AC-8): trang + ô nhập +
      gợi ý dẫn `/search` + danh sách "Các đoạn nội dung liên quan", badge tự động, nhãn "Khớp từ khoá"/
      "Tóm tắt nội dung video", ghi chú chờ kiểm duyệt.
- [x] **T20** (→ AC-3, AC-3.1, AC-3.2, AC-3.3, AC-4.2, AC-5.2, AC-5.3, AC-5.4): trạng thái rỗng trung tính,
      banner đỏ 403/503, vàng 429 + "Thử lại", vàng suy giảm 2 chế độ.
- [x] **T21** (→ AC-6, AC-6.1, AC-6.2): phân trang tối đa 2 trang + gợi ý thu hẹp, tổng gần đúng.

**Kiểm thử/vận hành**
- [x] **T22** (→ AC-1…AC-8): toàn bộ test plan ở indexing §5 và query §5 (kèm test hồi quy rò rỉ AC-4.1 — chặn go-live).
- [x] **T23** (→ AC-3, điều kiện go-live): script/tài liệu hiệu chỉnh `min_score`/`top_k` và quyết định dùng
      instruction cho câu hỏi bằng tập truy vấn vàng (nghiệp vụ/QA cung cấp) — không chặn DEV.
      *(Đã làm phần công cụ: `backend/scripts/semantic-calibrate.py` + runbook mục 6; chạy với tập vàng thật còn chờ nghiệp vụ/QA.)*

> **Kết quả implement 2026-10-11 (lệch so với spec, theo đúng điều kiện "chốt sau spike T4"):** mapping `embedding` dùng
> `faiss` + `innerproduct` + `sq fp16` — **không** `on_disk`/`compression_level` (OpenSearch 2.17.1 trả 500 cho mọi truy vấn k-NN khi
> shard có document không có vector) và **không** `cosinesimil` (faiss hnsw không hỗ trợ). Chi tiết: `docs/00-overview/runbooks/search-semantic-opensearch-knn.md` §1.
> `EmbedRequestMessage.segmentIds` là `List<String>` (có id tổng hợp `<docId>:title|note`); `LexicalHit.segmentId` đổi từ `UUID` sang `String` cùng lý do.

## 9. Migration & Rollback plan

- **PostgreSQL:** **không có migration mới** (bỏ `segment_embedding`; mới nhất vẫn V36). Outbox dùng
  bảng `outbox_event` sẵn có; chỉ thêm `event_type` mới `folder.moved`.
- **OpenSearch:** tạo `segment-v2` (mapping thêm `embedding` + `model_version`, `index.knn: true`), reindex
  `segment-v1 → segment-v2`, đổi alias `segment` (atomic `_aliases`) theo quy trình ở `ai-search-infra.md` QĐ 7.
  Dữ liệu khởi điểm trắng ⇒ dev/stg đơn giản là bootstrap tạo `segment-v2`; nếu môi trường đã có dữ liệu
  `segment-v1` thì reindex (document cũ chưa có vector, k-NN bỏ qua, lexical không ảnh hưởng). Reindex cần
  giữ `index_version` (external version) để bulk upsert sau đó không bị từ chối.
- **Queue `q.embed` trên stg đã tồn tại** (tạo tay 2026-09-15): `@Bean Queue` phải khớp tham số y hệt, nếu không
  `406 PRECONDITION_FAILED` làm crash ApplicationContext (indexing §9).
- **Thứ tự deploy:** (1) đặt `EMBEDDING_API_URL`, kiểm tra truy cập endpoint từ container backend → (2) deploy
  backend với `app.semantic.enabled=false` (bootstrap `segment-v2`, đổi alias) → (3) bật cờ cho poller phát
  `embed.requested` + consumer (dữ liệu mới có vector; không backfill) → (4) đối soát đạt → (5) bật endpoint + menu.
  Cờ `app.semantic.enabled` che cả endpoint (404 khi tắt) và poller/consumer embed.
- **Gỡ Qdrant (T2):** xoá khỏi compose; trên stg `docker compose up -d --remove-orphans` + xoá volume
  `qdrant_data` khi người dùng xác nhận (không tự xoá dữ liệu).
- **Rollback:** tắt cờ ⇒ endpoint 404 (trang hiện banner lỗi); muốn ẩn hẳn menu thì deploy lại web với `enabled: false`
  (mục xám, không điều hướng được); poller ngừng phát; tin `q.embed` tồn đọng
  bỏ qua an toàn (consumer idempotent). Alias có thể trỏ lại `segment-v1` (mất vector, lexical không ảnh hưởng).
- **Đổi model/chunking sau này:** ngoài phạm vi spec — index mới + re-embed toàn bộ + đổi alias (SA QĐ 7).

## 10. Rủi ro kỹ thuật

| # | Rủi ro | Mức | Giảm thiểu trong spec |
|---|---|---|---|
| R1 | **Rò rỉ phạm vi** qua truy vấn ngữ nghĩa (quên filter trong `knn`, đặt nhầm `post_filter`, ACL trong chỉ mục lệch, cache kết quả) | Cao | Fail-closed 403; filter = `ScopeFilterBuilder` (điểm duy nhất); double-check Postgres bắt buộc; cấm cache kết quả; test cấu trúc "filter nằm trong knn, không post_filter"; test hồi quy AC-4.1 (T22) |
| R2 | **k-NN on_disk/cosinesimil chưa kiểm chứng trên 2.17.1** (hỗ trợ space_type với compression; efficient filtering trên faiss; recall khi quantize 32x) | Cao | Spike ở T4 trước khi chốt mapping; fallback `innerproduct` (tương đương vì vector đã chuẩn hoá L2); hàm đổi điểm→cosine có unit test theo space_type |
| R3 | **Chất lượng Qwen3-Embedding-0.6B tiếng Việt + không rerank**, `min_score` nhạy | Cao | `min_score` đọc từ `system_config`; T23 đo recall@10/precision@5; giám sát tỷ lệ rỗng (Q-8, Q-9) |
| R4 | **Lexical re-index ghi đè document làm mất vector** (`SegmentIndexer` dùng `index`) ⇒ segment tìm được lexical nhưng k-NN không thấy | Cao | Poller luôn kèm `embed.requested` cho đúng tập segment re-index; job đối soát vá thiếu (T9); test IT "re-index lexical rồi chạy consumer ⇒ có vector" |
| R5 | **Phụ thuộc endpoint embedding ngoài kiểm soát** (không xác thực, hợp đồng/giới hạn batch chưa rõ, dev dùng domain công khai) | TB | Validate số phần tử + 1024 chiều ⇒ lỗi/retry/DLQ, không ghi; timeout riêng; suy giảm lexical-only; prod chỉ dịch vụ nội bộ (NFR-04); Q-7 |
| R6 | **Độ trễ k-NN khi filter chọn lọc cao** (exact search) + k-NN tranh chấp node với lexical | Cao | Efficient filtering + `ef_search` cấu hình; timeout `search.query_timeout_ms`; suy giảm; đo tải hồ sơ phạm vi hẹp |
| R7 | **Index `segment` phình** (vector + HNSW), reindex lâu | TB | `on_disk` + nén; tài nguyên OpenSearch; kiểm dung lượng ở spike T4 |
| R8 | **Tranh chấp dịch vụ embedding** giữa truy vấn tương tác và embed nền | TB | Semaphore ≤ 8, timeout embed câu hỏi → lexical-only; consumer nền batch nhỏ, concurrency thấp, tạm dừng bằng cờ |
| R9 | **Đếm token xấp xỉ** (không có tokenizer Qwen3 phía Java) | Thấp | Heuristic bảo thủ; ngưỡng 512 ≪ 32k của model; segment quá dài embed nguyên nội dung |
| R10 | **`folder.moved` mới**: cây con lớn ⇒ `updateByQuery` nặng; đổi path nhiều document | TB | Chạy async qua poller, `requests_per_second` cấu hình (tái dùng `SegmentIndexer.updateByQuery`), idempotent; test cây con nhiều tầng |
| R11 | Khoảng trễ hai trạng thái (có text, chưa có vector ≤60s) | Thấp | Chấp nhận (SA QĐ 6); lexical vẫn tìm thấy |

## 11. Definition of Done

- [ ] Tất cả AC-1…AC-8 pass (đối chiếu QA report), gồm test hồi quy rò rỉ AC-4.1
- [ ] `mvn -o test` + IT (`mvn verify`) xanh, test mới có hậu tố `IT` cho luồng Postgres/Redis/RabbitMQ/OpenSearch
- [ ] `npx tsc --noEmit`, `npm run lint` sạch; đọc `web/AGENTS.md` trước khi viết code web
- [ ] Không giảm coverage đáng kể; `/search` (FR-22/23) không đổi hành vi
- [ ] Không còn tham chiếu Qdrant/TEI/`QDRANT_*` trong code, compose, `application.yml`
- [ ] Runbook cập nhật: `EMBEDDING_API_URL`, reindex `segment-v2`, đối soát vector, tham số `semantic.*`
- [ ] Cờ `app.semantic.enabled` + khai báo `q.embed` khớp stg đã rà; spike k-NN (T4) ghi kết quả
- [ ] Điều kiện go-live (không chặn DEV): hiệu chỉnh `min_score`/`top_k`/instruction bằng tập vàng

## 12. Câu hỏi mở

Đã chốt (2026-10-10, người dùng đồng ý):

- [x] **Q-1** Qdrant REST hay gRPC → **Chốt:** không còn áp dụng — bỏ Qdrant, vector trong OpenSearch k-NN (SA QĐ 6).
- [x] **Q-2** Vị trí `EmbedRequestConsumer` → **Chốt:** trong `backend-api` (không tách worker).
- [x] **Q-3** Sự kiện outbox khi di chuyển thư mục → **Chốt:** THÊM event `folder.moved` từ `WarehouseService.moveFolder`;
      poller cập nhật `folder_path_ids` document/segment trong cây con ở OpenSearch (sửa luôn nhánh fulltext) — T10.
- [x] **Q-4** Quyền endpoint → **Chốt:** `PERM_document.read` như `SearchController`.
- [x] **Q-5** Giới hạn 8 truy vấn đồng thời → **Chốt:** một semaphore toàn hệ thống (per instance), vượt ⇒ 429 `TooManyRequestsException`.
- [x] **Q-6** Backfill → **Chốt:** KHÔNG làm job backfill (dữ liệu khởi điểm trắng); bỏ task backfill. Bảng `segment_embedding`
      cũng bỏ (xem §1.3).
- [x] **Q-QD** Gỡ Qdrant khỏi hạ tầng → **Chốt:** gỡ hẳn khỏi compose + env (T2); thêm `EMBEDDING_API_URL`.

Chốt 2026-10-11 (`/resolve-questions`; số đo thử endpoint dev qua Internet, 4 câu hỏi × 6 đoạn tiếng Việt —
mẫu nhỏ, chỉ để đặt giá trị khởi điểm):

- [x] **Q-7** Batch/timeout endpoint embedding → **Chốt (tạm):** `batch-size` 32, `timeout-ms` 500 (câu hỏi, SA QĐ 5),
      `batch-timeout-ms` 30000 (embed nền). Đo: 1 câu 0,41s; 8 câu 0,83s; 32 câu 0,99s; 64 câu 1,89s. Giới hạn thật + p95
      mạng nội bộ vẫn chờ nhóm AI xác nhận — chỉnh cấu hình, không đổi thiết kế (cập nhật: T1).
- [x] **Q-8** Instruction Qwen3 cho câu hỏi → **Chốt:** bật mặc định, chuỗi `Given a Vietnamese question, retrieve
      relevant passages from administrative documents`, tắt được; nội dung tài liệu không bọc. Đo: có instruction cosine
      đoạn liên quan 0,60–0,70, không liên quan ≤ 0,40; không có instruction đoạn liên quan xuống 0,45 (cập nhật: T1).
- [x] **Q-9** `semantic.min_score` cho Qwen3 → **Chốt:** khởi điểm **0,50** (giữa 0,40 và 0,60 đo được), hiệu chỉnh bằng tập
      vàng trước go-live (T23). Đổi điểm k-NN `cosinesimil` → cosine: `cos = 2·score − 1` (OpenSearch trả
      `score = (1 + cos)/2`); `innerproduct` theo công thức của plugin — unit test vector mẫu (cập nhật: T3, query §4.2).
- [x] **Q-10** Hợp đồng `folder.moved` → **Chốt:** payload chỉ `folderId` (+ `movedAt`, `movedBySub`); poller tính lại
      `folder_path_ids` từ PostgreSQL cho mọi document trong cây con, như cách `document.acl.changed` tính lại
      `scope_group_ids` (`SearchIndexOutboxPoller`) — không tin path trong payload (cập nhật: indexing §4.4).

Còn mở: không.
