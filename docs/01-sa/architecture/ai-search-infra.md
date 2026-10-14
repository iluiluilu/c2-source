# Architecture: ai-search-infra

> File: `docs/01-sa/architecture/ai-search-infra.md` — Owner: SA
> Status: Approved

## 1. Bối cảnh / Vấn đề

- **Slug hạ tầng nền, không map trực tiếp tới FR nào.** Mục đích: chốt **một
  chỗ duy nhất** các quyết định hạ tầng AI/search dùng chung cho 4 slug Giai
  đoạn C: `digitization-pipeline` (FR-17/19/20), `digitization-review` (FR-18),
  `search-fulltext` (FR-22/23/25), `search-semantic` (FR-24).
- **Đây là nguồn duy nhất (single source) cho:** chọn search engine, vector
  store, message broker, model serving/embedding, cách nhúng filter phạm vi
  FR-08 vào search engine, chiến lược đồng bộ DB→index, và hợp đồng interface
  cho slug hạ nguồn. Các slug 5/6/7/8 **chỉ dẫn chiếu**, không quyết định lại.
- Vấn đề cốt lõi cần giải: FR-08 đã chốt row-level filter **ở tầng query DB**
  (`rbac-group-scope.md`, Quyết định 3), nhưng OpenSearch nằm ngoài
  PostgreSQL nên cần một cơ chế phạm vi riêng, tương đương về ngữ nghĩa và
  fail-closed.
- Out-of-scope: mapping/analyzer chi tiết, DSL truy vấn, chiến lược chunking cụ
  thể, UI (thuộc `search-*`); chọn engine OCR/STT ở mức pipeline nghiệp vụ và
  phân đoạn/mốc thời gian (thuộc `digitization-pipeline`); hạ tầng vật lý, mạng,
  giám sát, sao lưu (thuộc `kien_truc_he_thong.adoc`).

## 2. Ràng buộc (Constraints)

- **Mạng cách ly hoàn toàn, xử lý cục bộ** — NFR-04 (SRS dòng 867) nêu rõ "dữ
  liệu không được xử lý hoặc gửi ra bên ngoài hệ thống (kể cả khi xử lý bằng
  AI)"; FR-17/19/24 lặp lại ràng buộc này. ⇒ **Cấm mọi API AI bên ngoài**; mô
  hình và trọng số phải tải sẵn offline vào registry/volume nội bộ.
- **Phần cứng cố định** (`kien_truc_he_thong.adoc` mục 5.3 ghi 2× GPU 24GB —
  **đã xác nhận với người dùng 2026-08-16: cấu hình thực tế là GPU 96GB** (1
  GPU lớn/gộp), không phải 2×24GB. `kien_truc_he_thong.adoc` cần được đồng bộ
  lại riêng (out-of-scope của file này); từ đây, tài liệu này dùng **96GB** làm
  cơ sở tính `ai_queue.concurrency`/`batch_size` (khớp giả định ở
  `docs/02-ba/stories/system-config.md` dòng 184–206)); 1 máy chủ quản lý
  48C/96T · 128GB RAM · 2× NVMe chứa OpenSearch + PostgreSQL. **Không
  có cụm nhiều node** ⇒ mọi thành phần stateful mặc định single-node.
- **Sản phẩm đã chốt ở tài liệu hạ tầng/đánh giá công nghệ** (OpenSearch,
  Qdrant, RabbitMQ, vLLM/TEI, PaddleOCR+VietOCR, PhoWhisper) — tài liệu này
  **dẫn chiếu, không quyết định lại** (xem Quyết định 8).
- Ba service đã có sẵn trong `source_v2/docker-compose.yml` (`opensearch:2.17.1`,
  `qdrant:v1.12.4`, `rabbitmq:3.13-management`) ở cấu hình dev single-node,
  security plugin tắt — **chỉ dùng cho dev**, production phải bật TLS/xác thực.
- Nguồn sự thật (source of truth) là **PostgreSQL**; OpenSearch là chỉ
  mục dẫn xuất, luôn có thể dựng lại từ PostgreSQL.
- Chỉ nội dung **đã xác nhận** (FR-18, thủ công hoặc tự động sau
  `ocr_stt.auto_confirm_hours`) mới vào chỉ mục chính thức.
- **Kho cá nhân không được lập chỉ mục** (`personal-storage-quota.md`): không
  OCR/STT, không vào OpenSearch — chặn ở nơi phát sự kiện, không dựa vào
  filter khi truy vấn.
- Phạm vi dữ liệu **chỉ** dựa trên nhóm/chia sẻ (FR-28/29) — không có khái niệm
  đơn vị/mức độ mật (`rbac-group-scope.md`).

## 3. NFR (Non-Functional Requirements)

SRS chỉ nêu định tính ("vài giây" — NFR-01). Các số dưới đây là đề xuất của SA
(đánh dấu `[ĐX]`), **đã được người dùng chấp nhận làm mục tiêu ban đầu
2026-08-16**, kiểm chứng lại bằng đo tải sau khi có hạ tầng thật.

| Loại | Yêu cầu |
|---|---|
| Performance | `[ĐX]` Toàn văn (FR-22/23/25): p95 ≤ 1s, p99 ≤ 2s end-to-end (trong đó OpenSearch ≤ 500ms) với 20 truy vấn đồng thời. Ngữ nghĩa (FR-24, embed query + vector search + RRF với nhánh lexical, **không rerank** — sửa 2026-10-08 theo `search-semantic.md` QĐ 2): p95 ≤ 3s, p99 ≤ 5s; riêng embed 1 truy vấn ≤ 150ms. Độ trễ near-real-time: từ lúc xác nhận FR-18 tới lúc tìm được ≤ 5s (p95), ≤ 30s (p99) |
| Performance (index) | `[ĐX]` Throughput index ≥ 200 segment/s (bulk); re-index toàn bộ 1 triệu tài liệu ≤ 12 giờ khi **không** phải sinh lại embedding, ≤ 72 giờ khi phải re-embed toàn bộ |
| Performance (GPU) | `[ĐX]` OCR ≥ 1.500 trang/giờ; STT ≥ 6× thời gian thực (tổng hợp mọi worker) trên GPU xử lý nền; hàng đợi chạy nền không chặn thao tác người dùng (NFR-01) |
| Security | Mọi truy vấn tới OpenSearch **bắt buộc** kèm filter phạm vi FR-08, fail-closed (thiếu ngữ cảnh người dùng → từ chối, không trả kết quả). Không index nội dung kho cá nhân. Không egress ra Internet từ mọi service AI/search. Production: bật TLS + xác thực cho cả 3 service stateful |
| Scalability | `[ĐX]` Mục tiêu quy mô: ~1 triệu tài liệu, ~20 triệu segment, chỉ mục toàn văn ~300GB + vector ~80GB trên NVMe; NFR-03: vài trăm nhóm × ~100 thành viên |
| Availability | Single-node cho cả 3 service (ràng buộc phần cứng). RPO của chỉ mục = 0 (dựng lại được từ PostgreSQL); `[ĐX]` RTO dựng lại chỉ mục ≤ 12 giờ. Search lỗi → suy giảm chức năng (báo lỗi rõ), không được rơi về chế độ bỏ qua filter phạm vi |
| Khác | Nhất quán: sai lệch DB↔index ≤ 0,1% bản ghi, đo bằng job đối soát định kỳ; mọi thay đổi phạm vi (gỡ chia sẻ/xóa) phản ánh vào chỉ mục ≤ 5s |

## 4. Phương án & Quyết định (ADR)

### Quyết định 1: Search engine toàn văn — OpenSearch

- **Ngữ cảnh:** FR-22/23/25 cần toàn văn tiếng Việt, lọc đa tiêu chí, highlight
  đoạn liên quan và định vị trang/dòng/mốc thời gian.
- **Các phương án xem xét:** A) OpenSearch 2.x — B) Elasticsearch 8.x —
  C) PostgreSQL FTS (`tsvector` + GIN).
- **Quyết định:** A — OpenSearch 2.x (**dẫn chiếu** `kien_truc_he_thong.adoc`
  mục 5.3 và `danh_gia_lua_chon_cong_nghe.adoc` mục 3; đã có trong
  `docker-compose.yml`). Bổ sung quyết định mới của tài liệu này: **analyzer
  tiếng Việt dùng plugin chính thức `analysis-icu`** (`icu_tokenizer` +
  `icu_normalizer` + `icu_folding`) kèm bộ stopword tiếng Việt tự quản, **không**
  phụ thuộc plugin tách từ của bên thứ ba ở giai đoạn 1.
- **Lý do:** OpenSearch Apache-2.0 (Elasticsearch SSPL — rủi ro pháp lý trong môi
  trường nhà nước); có sẵn highlight/nested/aggregation phục vụ FR-22/23/25;
  PostgreSQL FTS thiếu highlight chất lượng, thiếu ranking BM25 tinh chỉnh được
  và sẽ đặt tải tìm kiếm lên chính DB nghiệp vụ. `analysis-icu` cài offline
  được, do OpenSearch bảo trì → giảm rủi ro vận hành mạng cách ly.
- **Hệ quả:** ICU chỉ tách theo âm tiết, không tách từ ghép tiếng Việt ⇒ recall
  tốt nhưng precision cụm từ kém hơn; bù bằng `match_phrase` + shingle 2–3 âm
  tiết cho cụm từ. Nếu chất lượng chưa đạt, giai đoạn 2 đánh giá plugin tách từ
  (vd. coccoc-tokenizer) — đổi analyzer **bắt buộc reindex** (đã có quy trình ở
  Quyết định 7). **Đã xác nhận với người dùng 2026-08-16:** chấp nhận
  `analysis-icu` cho giai đoạn 1, đánh giá plugin tách từ chuyên biệt ở giai
  đoạn 2 nếu cần.

### Quyết định 2: Vector store — Qdrant

> **Sửa 2026-10-10 (theo `search-semantic.md` QĐ 6, người dùng chốt):** giai
> đoạn 1 **không dùng Qdrant**. Vector lưu ngay trong OpenSearch — field
> `knn_vector` (1024 chiều, cosine/inner product, engine `faiss`, HNSW,
> efficient filtering) trên index `segment` sẵn có. Lý do: một engine duy nhất
> cho cả lexical và vector ⇒ **một** bộ filter phạm vi (`ScopeFilterBuilder`),
> **một** đường đồng bộ ACL/xoá/di chuyển thư mục, bớt một hệ stateful; OpenSearch
> 2.17 hỗ trợ pre-filter trong lúc duyệt HNSW (điều kiện của QĐ 5) và chế độ
> `on_disk` + nén vector cho quy mô ~20 triệu vector. Qdrant **gỡ khỏi toàn bộ hạ tầng**
> (người dùng chốt 2026-10-10 — không slug nào dùng; muốn dùng lại cần ADR mới);
> phần còn lại của QĐ này giữ làm lịch sử. Mọi chỗ nói "Qdrant"/"upsert vào Qdrant"/"`set_payload`" trong tài liệu
> này hiểu là "field vector trên index `segment` của OpenSearch"/"partial update
> document".

- **Ngữ cảnh:** FR-24 cần truy hồi ngữ nghĩa theo vector, có lọc phạm vi FR-08
  và trả về dẫn nguồn chính xác tới segment.
- **Các phương án xem xét:** A) Qdrant — B) pgvector trong PostgreSQL sẵn có —
  C) Milvus/Weaviate.
- **Quyết định:** A — Qdrant (**dẫn chiếu** tài liệu hạ tầng; đã có trong
  `docker-compose.yml`).
- **Lý do:** hỗ trợ **pre-filter trên payload** ngay trong quá trình duyệt HNSW —
  điều kiện tiên quyết để Quyết định 5 giữ đúng top-k sau khi lọc phạm vi;
  Apache-2.0, nhẹ (Rust), phù hợp một máy chủ; Milvus/Weaviate cần nhiều thành
  phần phụ trợ, thừa cho quy mô này; pgvector đơn giản hơn nhưng đặt tải ANN lên
  DB nghiệp vụ và kém linh hoạt về quantization/on-disk index ở mức 20 triệu
  vector.
- **Hệ quả:** thêm một hệ stateful phải sao lưu/nâng cấp riêng (rủi ro mục 6);
  bù lại tách tải AI khỏi DB nghiệp vụ.

### Quyết định 3: Message broker — RabbitMQ

- **Ngữ cảnh:** FR-17/19/20 chạy nền theo lô trên GPU, cần "đưa lại hàng đợi"
  khi lỗi; số lượng job vừa phải nhưng job dài (phút–chục phút).
- **Các phương án xem xét:** A) RabbitMQ — B) Kafka — C) Redis Streams (Redis đã
  có sẵn).
- **Quyết định:** A — RabbitMQ (**dẫn chiếu** tài liệu hạ tầng), cấu hình:
  quorum queue, `manual ack`, `prefetch=1` cho worker GPU, dead-letter exchange +
  retry queue có TTL (backoff), priority queue cho sự kiện thay đổi phạm vi.
- **Lý do:** mô hình task-queue với ack/DLQ/priority là đúng nhu cầu; Kafka mạnh
  về log stream throughput cao nhưng nặng vận hành (ZooKeeper/KRaft, phân vùng)
  và không có redelivery per-message tự nhiên; Redis Streams thiếu DLQ/priority
  gốc, độ bền phụ thuộc cấu hình persistence — rủi ro mất job dài.
- **Hệ quả:** thêm một hệ stateful; cần runbook cho DLQ (xem/replay/xoá) và giám
  sát độ sâu hàng đợi.

### Quyết định 4: Model serving & embedding cục bộ

> **Sửa 2026-10-10 (theo `search-semantic.md` QĐ 6, người dùng chốt):** embedding
> giai đoạn 1 là **Qwen/Qwen3-Embedding-0.6B** (dense 1024 chiều, vector đã chuẩn
> hoá L2), phục vụ bởi **một endpoint REST nội bộ riêng** do nhóm AI vận hành —
> **không** dùng TEI/bge-m3. Hợp đồng: `POST {"inputs": [string...], "mode":
> "dense"}` → `[{"dense": [float×1024]}, ...]` cùng thứ tự `inputs`; không xác
> thực; địa chỉ cấu hình qua env của backend. NFR-04 giữ nguyên: khi triển khai
> endpoint là **dịch vụ nội bộ**, không gọi ra Internet. Đổi model ⇒ re-embed
> toàn bộ (hệ quả bên dưới vẫn đúng). Phần reranker/LLM dưới đây không đổi.

- **Ngữ cảnh:** NFR-04 bắt buộc chạy offline. Cần embedding tiếng Việt cho
  FR-24, (đã cân nhắc) reranker để tăng chất lượng dẫn nguồn, và (tuỳ
  `search-semantic`) một LLM để tổng hợp câu trả lời.
- **Các phương án xem xét:** A) Một service Python duy nhất tự nạp model —
  B) Triton Inference Server — C) TEI (embedding/reranker) + vLLM (LLM), hai
  tiến trình tách biệt.
- **Quyết định:** C (**dẫn chiếu** `danh_gia_lua_chon_cong_nghe.adoc` mục
  4.4–4.5):
  - Embedding: **BAAI/bge-m3** (dense 1024 chiều, cosine, chuẩn hoá L2, ngữ cảnh
    tới 8192 token) phục vụ qua **TEI**.
  - Reranker — **dự phòng, KHÔNG triển khai ở giai đoạn 1** (sửa 2026-10-08
    theo `search-semantic.md` QĐ 2): lựa chọn đã đánh giá là
    **BAAI/bge-reranker-v2-m3** qua TEI (tiến trình riêng). FR-24 xếp hạng bằng
    RRF và chặn "không đủ căn cứ" bằng ngưỡng cosine + khớp từ khoá; chỉ bật lại
    reranker bằng ADR mới nếu precision trên tập vàng không đạt.
  - LLM — **dự phòng, KHÔNG triển khai ở giai đoạn 1:** đã xác nhận với người
    dùng 2026-08-16, phạm vi FR-24 chốt ở **retrieval + trích dẫn** (ban đầu
    "retrieval + rerank + trích dẫn"; bỏ rerank 2026-10-08),
    không LLM sinh văn (an toàn hơn về ảo giác, đúng ràng buộc "không suy diễn
    khi thiếu căn cứ"). `search-semantic` **không** cần vLLM/Qwen2.5-7B-Instruct
    ở giai đoạn 1. Giữ lại lựa chọn đã đánh giá (Qwen2.5-7B-Instruct qua vLLM,
    API tương thích OpenAI, chỉ nghe nội bộ) cho giai đoạn 2 nếu nghiệp vụ sau
    này yêu cầu RAG đầy đủ — GPU 96GB (đã xác nhận) đủ VRAM chạy song song với
    embedding (và reranker) nếu cần bật lại.
  - Trọng số model tải sẵn vào volume nội bộ; **cấm** tự tải từ Internet lúc
    khởi động (`HF_HUB_OFFLINE=1` hoặc tương đương).
- **Lý do:** TEI/vLLM là runtime chuyên biệt, cấu hình đơn giản hơn Triton nhiều
  cho đúng 2 loại workload này; tách tiến trình giúp giám sát/giới hạn VRAM từng
  phần và một tiến trình lỗi không kéo sập phần còn lại; bge-m3 đa ngữ có tiếng
  Việt, Apache-2.0, ngữ cảnh dài phù hợp đoạn OCR/STT.
- **Hệ quả:** phiên bản model + tham số chunking trở thành **một phần khoá của
  chỉ mục vector** (xem `model_version` ở mục 8) — đổi model ⇒ phải re-embed
  toàn bộ; đây là lý do các tham số này để ở cấu hình triển khai
  (`application.yml`), **không** đưa vào UI cấu hình runtime của FR-01.

### Quyết định 5: Nhúng filter phạm vi FR-08 vào search engine *(quyết định trọng tâm)*

- **Ngữ cảnh:** `rbac-group-scope.md` chốt row-level filter tại tầng query DB.
  OpenSearch không thấy được `group_member`, nên phải có cơ chế tương
  đương, đúng ngữ nghĩa và fail-closed, áp cho **cả hai** engine.
- **Các phương án xem xét:**
  - A) **Nhồi ACL vào document + filter trong truy vấn** — mỗi document/segment
    mang `owner_user_id` và `scope_group_ids[]`; truy vấn luôn kèm
    `filter: scope_group_ids ∈ groupIds(user) OR owner_user_id = user`.
  - B) **Post-filter theo danh sách ID lấy từ PostgreSQL** — truy vấn không lọc,
    lọc lại kết quả ở tầng ứng dụng (hoặc truyền danh sách ID vào truy vấn).
  - C) **Index/collection riêng theo từng nhóm.**
- **Quyết định:** **A** — cho cả OpenSearch và Qdrant, với ràng buộc: ACL lưu
  theo **group id, không lưu user id**.
- **Lý do:**
  - Đúng ngữ nghĩa FR-08 (phạm vi = nhóm + tài liệu do chính mình nạp) và giữ
    được phân trang/điểm số chính xác; với Qdrant là **pre-filter**, nên top-k
    sau lọc vẫn đủ và đúng (B sẽ làm rỗng top-k khi người dùng có phạm vi hẹp).
  - Lưu group id (không fan-out ra user id) khiến **thay đổi thành viên nhóm —
    thao tác thường xuyên nhất — không cần đụng tới chỉ mục** (danh sách nhóm
    của người dùng được đọc từ DB/cache tại thời điểm truy vấn). Chỉ khi
    **chia sẻ/gỡ chia sẻ tài liệu với nhóm** mới cần cập nhật ACL của đúng tài
    liệu đó.
  - B bị loại: sai phân trang/xếp hạng, danh sách ID có thể rất lớn, và dễ bị bỏ
    sót khi thêm entrypoint mới. C bị loại: bùng nổ số index/collection (vài trăm
    nhóm), tài liệu chia sẻ nhiều nhóm bị nhân bản, mọi thay đổi chia sẻ đều phải
    di chuyển dữ liệu.
- **Cập nhật phạm vi & độ trễ nhất quán (chốt):**
  - Sự kiện `acl.changed` (chia sẻ/gỡ chia sẻ/xoá tài liệu) đi vào **hàng đợi ưu
    tiên cao**; indexer cập nhật `scope_group_ids` bằng `update_by_query` trên
    các segment của tài liệu (OpenSearch) và `set_payload` theo filter
    `document_id` (Qdrant). Mục tiêu ≤ 5s.
  - **Thu hồi quyền phải an toàn ngay lập tức, không chờ index:** bước hydrate
    kết quả (lấy metadata hiển thị từ PostgreSQL) **bắt buộc** áp lại lớp
    row-level filter dùng chung của `rbac-group-scope.md`; hit nào không qua được
    thì loại khỏi kết quả. Chỉ mục là bộ lọc thứ nhất (hiệu năng), DB là bộ lọc
    quyết định (đúng đắn) ⇒ cửa sổ trễ chỉ có thể gây "thiếu kết quả tạm thời",
    không bao giờ gây rò rỉ.
  - Hệ quả của double-check: tổng số hit hiển thị có thể nhỏ hơn `total` do engine
    trả về — `search-fulltext` phải xử lý phân trang theo hướng "lọc sau khi lấy
    dư" và không hiển thị `total` chính xác tuyệt đối.
- **Hệ quả (trade-off):** bắt buộc có **một `ScopeFilterBuilder` dùng chung**;
  cấm mọi module gọi thẳng OpenSearch không qua lớp này (checklist review
  giống `rbac-group-scope.md`). Dữ liệu kho cá nhân **không** dựa vào filter mà
  đơn giản là không bao giờ được phát sự kiện index. **Đã xác nhận với người
  dùng 2026-08-16:** chấp nhận đánh đổi `total` gần đúng ngay sau khi phạm vi
  thay đổi, đổi lấy fail-closed mà không phải post-filter toàn bộ.

### Quyết định 6: Đơn vị lập chỉ mục dùng chung — segment

- **Ngữ cảnh:** FR-25 đòi định vị trang/dòng (văn bản) và mốc thời gian
  (âm thanh/video); FR-22 đòi trích đoạn liên quan; FR-24 cần đơn vị vector.
- **Các phương án xem xét:** A) chỉ index cấp tài liệu — B) nested field chứa các
  đoạn trong một document — C) **hai index phẳng: `document` + `segment`**, dùng
  chung `segment_id` với Qdrant.
- **Quyết định:** C. `document` giữ metadata cho lọc FR-23; `segment` là đơn vị
  nhỏ nhất chứa text + toạ độ định vị + ACL denormalized; point id trong Qdrant
  **chính là** `segment_id` ⇒ ánh xạ 1-1, dễ hợp nhất kết quả toàn văn + ngữ
  nghĩa (hybrid) và cùng một cơ chế ACL.
- **Lý do:** A không đáp ứng FR-25; B khiến mỗi lần sửa một đoạn (FR-18) phải ghi
  lại cả document lớn và làm highlight/phân trang theo đoạn phức tạp.
- **Hệ quả:** ACL bị lặp ở mọi segment ⇒ cập nhật phạm vi là `update_by_query`
  chứ không phải update 1 bản ghi (đã tính ở Quyết định 5); số bản ghi index lớn
  hơn nhiều lần số tài liệu (ước tính mục 3).

### Quyết định 7: Chiến lược đồng bộ PostgreSQL → chỉ mục

- **Ngữ cảnh:** PostgreSQL là nguồn sự thật; cần đưa dữ liệu đã xác nhận sang
  OpenSearch, chịu được lỗi và dựng lại được toàn bộ.
- **Các phương án xem xét:** A) dual-write trong cùng service — B) **transactional
  outbox + relay + indexer worker** — C) CDC (Debezium).
- **Quyết định:** B.
  - Ghi nghiệp vụ và bản ghi `outbox_event` nằm **trong cùng một transaction**.
  - Relay đọc outbox theo thứ tự, publish vào RabbitMQ; indexer worker tiêu thụ,
    ghi bulk vào OpenSearch và upsert vào Qdrant.
  - **Idempotent + chống ghi đè lùi:** mỗi sự kiện có `event_id` (khoá idempotency)
    và `version` tăng đơn điệu theo tài liệu; indexer dùng external versioning để
    bỏ qua sự kiện cũ. Thứ tự chỉ đảm bảo theo từng `document_id`.
  - **Lỗi:** retry backoff qua retry-queue có TTL (vd. 5s/30s/5m), quá số lần →
    DLQ; có công cụ vận hành xem/replay DLQ.
  - **Reindex toàn bộ:** job đọc theo lô từ PostgreSQL ghi vào index/collection
    phiên bản mới (`segment-v2`), xong thì chuyển alias (`segment`) — không
    downtime. Vector chỉ re-embed khi `model_version`/tham số chunking đổi; nếu
    không, chép lại vector cũ theo `segment_id`.
  - **Đối soát:** job định kỳ so số lượng + checksum theo lô giữa DB và chỉ mục,
    tự phát lại outbox cho phần lệch.
- **Lý do:** dual-write mất nhất quán khi một trong hai bên lỗi (không có
  transaction chung) — không chấp nhận được với dữ liệu có yếu tố chứng cứ; CDC
  đúng về lý thuyết nhưng kéo theo Kafka + Kafka Connect, mâu thuẫn với Quyết
  định 3 và với ràng buộc một máy chủ quản lý.
- **Hệ quả:** thêm bảng `outbox_event` + tiến trình relay; chỉ mục là
  **eventually consistent** (mục tiêu ≤ 5s) — UI FR-18/FR-22 phải chấp nhận độ
  trễ này và không hứa "tìm được ngay lập tức".

> #### Sai lệch có chủ đích khi hiện thực hoá (chốt 2026-09-20)
>
> **Phần "relay + indexer worker" của quyết định này hiện KHÔNG chạy như hai
> tiến trình riêng.** Cả hai nằm trong `backend-api`, dưới dạng một
> `@Scheduled` bean là `SearchIndexOutboxPoller`: nó poll thẳng bảng
> `outbox_event` (không qua RabbitMQ) rồi ghi bulk vào OpenSearch.
>
> **Phần cốt lõi của Quyết định 7 vẫn giữ nguyên và không được phép bỏ:** ghi
> nghiệp vụ và `outbox_event` **cùng một transaction**; idempotent + chống ghi
> đè lùi bằng external versioning; chỉ mục eventually consistent; job đối soát
> định kỳ. Lý do chọn B thay vì dual-write (mục "Lý do" ngay trên) vẫn đúng
> nguyên vẹn — sai lệch ở đây chỉ là **nơi đặt tiến trình tiêu thụ**, không
> phải cơ chế bảo đảm nhất quán.
>
> **Vì sao chọn vậy:** package `search/index` phụ thuộc vào 8 package domain
> (10 repository/entity: `document`, `content_segment`, `document_tag`/`tag`,
> `document_group_share`, `warehouse_folder`, `document_note`, `outbox_event`,
> `digitization_run`). Tách thành deployable riêng buộc phải hoặc nhân bản
> chừng đó repository/entity, hoặc dựng shared library khiến cả hai deployable
> khoá chặt vào cùng bộ entity JPA và cùng schema — gánh chi phí của
> microservice mà không có sự độc lập của microservice. Cộng với ràng buộc
> **một máy chủ, một replica backend** (Quyết định 3, NFR-04): thêm một JVM là
> thêm bộ nhớ thường trực mà không đổi lại được khả năng scale nào.
>
> **Điều kiện xem lại** — tách khi **một trong bốn** điều sau xảy ra, không
> tách vì lý do thẩm mỹ kiến trúc:
> 1. Rời khỏi mô hình một máy chủ / cần nhiều replica backend.
> 2. **Đo được** việc index làm trễ đường phục vụ request.
> 3. Cần scale việc index độc lập (vd. reindex toàn bộ theo lịch).
> 4. Nhịp thay đổi của code index khác hẳn nhịp của API.
>
> **Đường tách rẻ nhất khi cần** (ưu tiên trước khi nghĩ tới deployable mới):
> cùng một JAR, chạy hai chế độ bằng `@ConditionalOnProperty` — một instance
> bật poller, một instance phục vụ API. `claimNextForIndexing` đã dùng
> `FOR UPDATE SKIP LOCKED` nên chạy nhiều instance là an toàn theo thiết kế.
>
> Chi tiết hiện thực: `docs/03-dev/specs/search-fulltext-04-dong-bo-chi-muc.md`
> mục 4.2.

### Quyết định 8: Ranh giới với `kien_truc_he_thong.adoc`

- **Ngữ cảnh:** tránh hai tài liệu chốt khác nhau về cùng một thứ.
- **Quyết định:**
  - **Dẫn chiếu, không quyết định lại:** lựa chọn sản phẩm (OpenSearch, Qdrant,
    RabbitMQ, vLLM/TEI, PaddleOCR+VietOCR, PhoWhisper/faster-whisper, Tika,
    FFmpeg), định cỡ phần cứng/GPU, phân bổ máy chủ, lưu trữ SAN/NAS, giám sát
    (Prometheus/Grafana/DCGM), sao lưu (`backup-restore`).
  - **Tài liệu này quyết định (mức ứng dụng):** mô hình ACL trong chỉ mục
    (QĐ 5), đơn vị lập chỉ mục (QĐ 6), cơ chế đồng bộ/retry/reindex (QĐ 7),
    analyzer tiếng Việt (QĐ 1), hợp đồng interface cho slug hạ nguồn (mục 8),
    và các tham số cấu hình đề xuất.
- **Hệ quả:** nếu hai tài liệu mâu thuẫn về nhóm hạng mục thứ nhất →
  `kien_truc_he_thong.adoc` thắng; về nhóm thứ hai → tài liệu này thắng.

## 5. Tech stack đề xuất

- Backend: Spring Boot — `search-service` (facade truy vấn, chứa
  `ScopeFilterBuilder` dùng chung), `indexer-worker` (tiêu thụ RabbitMQ, ghi
  OpenSearch), `outbox-relay`. Worker AI (OCR/STT/embedding) bằng Python
  (theo `danh_gia_lua_chon_cong_nghe.adoc` mục 3).
  **Lưu ý (2026-09-20):** `indexer-worker` và `outbox-relay` hiện **không** là
  tiến trình riêng — cả ba thành phần Spring Boot trên nằm chung trong
  `backend-api`. Xem "Sai lệch có chủ đích khi hiện thực hoá" ở Quyết định 7.
- Frontend: không thay đổi ở slug này (UI thuộc `search-*`, `digitization-*`).
- Data store: PostgreSQL (nguồn sự thật + `outbox_event`), OpenSearch 2.x
  (`document`, `segment` — `segment` mang thêm field vector 1024 chiều từ
  2026-10-10, thay Qdrant `segment-vec`), Redis
  (cache danh sách nhóm của người dùng cho filter phạm vi).
- AI serving: embedding Qwen3-Embedding-0.6B qua endpoint REST nội bộ riêng
  (sửa 2026-10-10, thay TEI bge-m3) — chỉ nghe mạng nội bộ. Dự phòng
  giai đoạn 2, **không** triển khai giai đoạn 1: TEI bge-reranker-v2-m3, vLLM
  (Qwen2.5-7B-Instruct).
- Infra/Deploy: RabbitMQ (quorum queue + DLX). Production bổ sung so với
  `docker-compose.yml` hiện tại: bật security plugin/TLS cho OpenSearch, API key
  cho Qdrant, user/vhost riêng cho RabbitMQ, tăng heap OpenSearch (dev đang
  512MB).
- Tham số cấu hình đề xuất:
  - Vào `system-config` (FR-01, R3 sửa được): `search.max_results_per_page`,
    `search.query_timeout_ms`, `semantic.top_k`, `semantic.min_score` (ngưỡng
    cosine; `semantic.rerank_top_n` bỏ cùng reranker 2026-10-08). (`ocr_stt.auto_confirm_hours`, `ai_queue.concurrency`,
    `ai_queue.batch_size` đã có ở `system-config.md`.)
  - **Không** đưa vào UI runtime (để ở `application.yml`, đổi ⇒ reindex):
    `embedding.model_name`, `embedding.model_version`, `embedding.chunk_size_tokens`,
    `embedding.chunk_overlap_tokens`, tên analyzer, `index.refresh_interval`.

## 6. Rủi ro

| Rủi ro | Mức độ | Giảm thiểu |
|---|---|---|
| **Đã giải quyết — mâu thuẫn định cỡ GPU:** `kien_truc_he_thong.adoc` ghi 2× GPU 24GB, nhưng người dùng xác nhận 2026-08-16 cấu hình thực tế là **96GB**, khớp giả định `concurrency=4, batch_size=8` ở `docs/02-ba/stories/system-config.md`. `kien_truc_he_thong.adoc` còn sai lệch, cần đồng bộ lại (ngoài phạm vi file này) | Thấp (còn lại: đồng bộ tài liệu) | Đội hạ tầng cập nhật `kien_truc_he_thong.adoc` mục 5.3 cho khớp 96GB; theo dõi khi triển khai thật nếu VRAM không đạt như xác nhận |
| Chất lượng tiếng Việt của analyzer ICU (không tách từ ghép) và của bge-m3 (đa ngữ, không chuyên tiếng Việt) | Trung bình | Bộ truy vấn mẫu + tập vàng do nghiệp vụ cung cấp, đo recall/precision trước go-live; dự phòng: plugin tách từ tiếng Việt (giai đoạn 2), bật lại reranker (ADR mới) bù precision |
| **Rò rỉ dữ liệu qua chỉ mục** — ACL lệch, quên filter ở một entrypoint, hoặc tệp kho cá nhân lọt vào index | **Cao** | `ScopeFilterBuilder` bắt buộc + double-check bằng row-level filter khi hydrate (QĐ 5); chặn kho cá nhân ngay tại producer sự kiện; test hồi quy "người dùng A không thấy tài liệu của B"; audit truy vấn search (FR-09) |
| Bộ nhớ vector: ~20 triệu vector × 1024 chiều float32 ≈ 80GB — vượt RAM khả dụng nếu để HNSW in-memory | Trung bình | Bật scalar quantization (int8) + `on_disk` payload/vector của Qdrant; đo lại sau POC; giới hạn số segment/tài liệu bằng chunking hợp lý |
| Chi phí vận hành thêm 3 hệ stateful (OpenSearch, RabbitMQ) trên hạ tầng cách ly: nâng cấp, sao lưu, giám sát | Trung bình | Chỉ mục dựng lại được từ PostgreSQL ⇒ sao lưu ưu tiên thấp hơn; snapshot định kỳ để rút ngắn RTO; đưa vào cùng runbook/giám sát chung (`kien_truc_he_thong.adoc`) |
| Lệch dữ liệu DB ↔ chỉ mục (sự kiện rơi vào DLQ và bị quên) | Trung bình | Job đối soát định kỳ + cảnh báo khi DLQ khác rỗng hoặc độ trễ outbox vượt ngưỡng; công cụ replay; reindex theo alias là phương án cuối |
| Re-embed toàn bộ khi đổi model/chunking chiếm trọn GPU nhiều ngày | Trung bình | Gắn `model_version` vào mọi vector; chạy reindex vào collection mới ở giờ thấp điểm rồi đổi alias; giữ text đã xác nhận trong PostgreSQL để **không** phải OCR/STT lại |
| Cấu hình dev trong `docker-compose.yml` (không TLS, không xác thực, heap 512MB) bị mang lên production | Trung bình | Tách file compose/profile production; checklist go-live bắt buộc bật bảo mật cả 3 service |

## 7. Câu hỏi mở

Tất cả đã được người dùng xác nhận ngày 2026-08-16:

- [x] **GPU thực tế: 96GB** (không phải 2×24GB như `kien_truc_he_thong.adoc`
      mục 5.3 nêu) — khớp giả định `concurrency=4, batch_size=8` ở
      `docs/02-ba/stories/system-config.md`. Hành động còn lại (ngoài phạm vi
      file này): đồng bộ lại `kien_truc_he_thong.adoc`.
- [x] **Phạm vi FR-24: retrieval + rerank + trích dẫn**, không LLM sinh văn ở
      giai đoạn 1 (xem Quyết định 4). **Sửa 2026-10-08:** bỏ rerank — phạm vi
      còn **retrieval + trích dẫn** (`search-semantic.md` QĐ 2). vLLM/Qwen2.5-7B-Instruct giữ làm lựa chọn
      dự phòng giai đoạn 2.
- [x] **Các số NFR ở mục 3 được chấp nhận làm mục tiêu ban đầu**, kiểm chứng
      lại bằng đo tải khi có hạ tầng thật.
- [x] **Chấp nhận `analysis-icu`** cho giai đoạn 1; đánh giá plugin tách từ
      tiếng Việt chuyên biệt ở giai đoạn 2 nếu cần.
- [x] **Chấp nhận `total` gần đúng** ngay sau khi phạm vi vừa thay đổi (hệ quả
      double-check ACL ở Quyết định 5), đổi lấy fail-closed đúng đắn.
- [x] Trạng thái file input: `rbac-group-scope.md`, `system-config.md`,
      `personal-storage-quota.md`, `data-ingestion.md` đều đã `Approved` — không
      có phụ thuộc `Draft` nào. (Ghi chú: `docs/02-ba/stories/system-config.md`
      là artifact BA, chỉ dùng để đối chiếu giả định GPU.)

**Việc còn lại (không thuộc phạm vi file này):** đồng bộ `kien_truc_he_thong.adoc`
mục 5.3 để phản ánh đúng GPU 96GB.

## 8. Hợp đồng dành cho slug hạ nguồn (downstream contract)

Bốn slug `digitization-pipeline`, `digitization-review`, `search-fulltext`,
`search-semantic` **được phép giả định** những điều dưới đây và **không** được
quyết định lại. Mức khái niệm — tên trường/DSL chi tiết do DEV chốt ở tech spec.

- **Service & giao thức (nội bộ, không lộ ra ngoài):**
  | Thành phần | Địa chỉ khái niệm | Giao thức |
  |---|---|---|
  | OpenSearch | `opensearch:9200` | HTTP REST |
  | ~~Qdrant~~ — **không dùng giai đoạn 1** (sửa 2026-10-10, vector nằm trong OpenSearch) | `qdrant:6333` (REST) / `6334` (gRPC) | REST/gRPC |
  | RabbitMQ | `rabbitmq:5672` | AMQP 0-9-1 |
  | Embedding (Qwen3-Embedding-0.6B, sửa 2026-10-10) | endpoint REST nội bộ, URL qua env backend — `POST {inputs, mode:"dense"}` | HTTP, không xác thực |
  | Reranker (TEI) — **dự phòng, không triển khai giai đoạn 1** (bỏ 2026-10-08) | `tei-rerank:8081` — `/rerank` | HTTP |
  | LLM (vLLM) — **dự phòng giai đoạn 2, không triển khai giai đoạn 1** | `vllm:8000` — API tương thích OpenAI | HTTP |
  - Frontend và module nghiệp vụ **không** gọi thẳng OpenSearch; mọi truy
    vấn đi qua `search-service` (nơi áp `ScopeFilterBuilder`).
- **Đơn vị index (QĐ 6):**
  - OpenSearch: index `document-v1` (alias `document`), `segment-v1` (alias
    `segment`).
  - **Vector (sửa 2026-10-10):** field `knn_vector` 1024 chiều ngay trên
    document của index `segment` (1 vector/segment, không sub-point), kèm
    `model_version`; ACL/trường lọc dùng chung với nhánh lexical. Gạch đầu dòng
    Qdrant ngay dưới chỉ còn giá trị lịch sử.
  - Qdrant: collection `segment-vec-v1` (alias `segment-vec`), 1024 chiều,
    cosine, point id = `segment_id` — **trừ trường hợp một segment vượt
    `embedding.chunk_size_tokens` sau khi ghép cửa sổ lân cận** (bổ sung
    2026-08-16 theo yêu cầu của `search-semantic`, QĐ 1): khi đó cho phép tách
    thành nhiều point với id = `UUIDv5(segment_id, sub_index)`, payload vẫn
    mang `segment_id` gốc để dẫn nguồn không đổi. Mặc định vẫn là 1-1; sub-point
    chỉ phát sinh ở phần rất nhỏ segment vượt ngưỡng.
  - Trường ACL bắt buộc ở **cả hai**: `owner_user_id`, `scope_group_ids[]`,
    `acl_version`. (Không có trường nào cho kho cá nhân — dữ liệu đó không tồn
    tại trong chỉ mục.)
  - Trường định vị (FR-25) ở `segment`: `document_id`, `segment_index`,
    `page_no`, `line_from`, `line_to`, `time_start_ms`, `time_end_ms`.
  - Trường lọc (FR-23) ở `document`: loại tài liệu, thời gian, nhãn, nhóm.
  - Trường xuất xứ: `content_source` (`ocr` | `stt` | `sed` | `summary` |
    `note` | `title`), `confirm_source` (`manual` | `auto`), `model_version`.
    **Đã sửa 2026-09-07:** bỏ `native_text` (loại khỏi thiết kế từ
    `digitization-pipeline.md` QĐ 1, 2026-08-16 — "PDF luôn OCR toàn bộ,
    không dùng lớp text sẵn để bỏ qua"), thêm `sed`/`summary` (theo
    `digitization-results-store.md` §3.3) — tài liệu này trước đó chưa được
    cập nhật theo kịp 2 quyết định trên.
    - **`title`** (bổ sung 2026-08-16 theo yêu cầu của `search-fulltext`, QĐ 2):
      segment tổng hợp chứa tiêu đề/tên tệp/nhãn của tài liệu. Cùng với `note`,
      đây là các segment **không có toạ độ trang/thời gian** ⇒ mọi trường định
      vị để `null` và kết quả tìm kiếm trả `position_type = none` (định vị ở cấp
      tài liệu — đúng ngoại lệ FR-25). Mục đích: mọi văn bản tìm kiếm được đều
      nằm trong **một** index `segment`, đi qua **một** đường ACL duy nhất.
  - **Chỉ segment có trạng thái đã xác nhận (FR-18) mới nằm trong index chính
    thức**; bản nháp lưu ở PostgreSQL. (Ngoại lệ tự nhiên: segment `title` sinh
    từ metadata tài liệu, không qua kiểm duyệt OCR/STT.)
- **Event schema (mức khái niệm):**
  `{ event_id, event_type, occurred_at, version, document_id, segment_ids?, actor_id, payload }`
  — `event_id` là khoá idempotency; thứ tự đảm bảo theo `document_id`.
  - Loại sự kiện dùng chung: `document.ingested` (do `data-ingestion` phát, **chỉ**
    cho kho chung), `ocr.requested` / `stt.requested`, `digitization.completed`,
    `review.confirmed` (thủ công hoặc tự động), `index.upsert`, `index.delete`,
    `acl.changed` (ưu tiên cao), `embed.requested`.
  - Exchange `ai.jobs` (topic) + DLX `ai.jobs.dlx`, mỗi queue có `<queue>.retry`
    và `<queue>.dlq`.
- **Điểm nối với slug đã Approved:**
  - `data-ingestion.md`: sau khi tệp qua kiểm tra toàn vẹn/mã độc và vào **kho
    chung**, phát `document.ingested` vào outbox. Kho cá nhân không phát sự kiện
    (`personal-storage-quota.md`).
  - `rbac-group-scope.md`: `groupIds(user)` lấy từ `group_member`; mọi thay đổi
    `document_group_share` phát `acl.changed`.
  - `system-config.md`: các tham số runtime lấy theo mục 5; tham số ảnh hưởng
    chỉ mục không đưa vào UI.
