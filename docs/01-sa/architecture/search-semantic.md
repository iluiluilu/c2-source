# Architecture: search-semantic

> File: `docs/01-sa/architecture/search-semantic.md` — Owner: SA
> Status: Approved

## 1. Bối cảnh / Vấn đề

- Phủ **FR-24** (tra cứu ngữ nghĩa: người dùng hỏi bằng ngôn ngữ tự nhiên/mô tả
  ý, hệ thống trả về các đoạn nội dung liên quan **kèm dẫn nguồn bắt buộc**; khi
  không đủ căn cứ thì báo rõ, **không suy diễn**) — SRS mục 2.7.
- **Tầng ứng dụng/truy vấn trên hạ tầng đã chốt.** `ai-search-infra.md`
  (**Approved**) là nguồn duy nhất cho: Qdrant (QĐ 2), bge-m3 1024 chiều/cosine
  + bge-reranker-v2-m3 qua TEI (QĐ 4), phạm vi FR-24 = *retrieval + rerank +
  trích dẫn, không LLM sinh văn ở giai đoạn 1* (QĐ 4) — **reranker đã bỏ**
  2026-10-08 (QĐ 2; `ai-search-infra.md` QĐ 4 đã sửa theo), mô hình ACL trong chỉ mục
  (QĐ 5), đơn vị `segment` + `point id = segment_id` (QĐ 6), outbox→RabbitMQ→
  indexer (QĐ 7), hợp đồng interface (mục 8). File này **chỉ dẫn chiếu, không
  quyết định lại**.
- **Sửa 2026-10-10 (QĐ 6):** vector lưu trong **OpenSearch k-NN** (field vector
  trên index `segment`), **không dùng Qdrant**; embedding bằng
  **Qwen3-Embedding-0.6B** qua **endpoint REST nội bộ riêng**, không dùng
  TEI/bge-m3. `ai-search-infra.md` QĐ 2, QĐ 4, mục 5, mục 8 đã ghi chú theo. Các
  chỗ còn nhắc Qdrant/TEI/bge-m3 trong file này mà chưa sửa trực tiếp hiểu theo QĐ 6.
- **In-scope:** chiến lược chunking + sinh embedding cho nội dung đã số hoá; hình
  dạng truy vấn ngữ nghĩa (k-NN OpenSearch); kết hợp với nhánh toàn văn (hybrid/RRF)
  qua hợp đồng `lexicalSearch` của `search-fulltext.md`; ngưỡng căn cứ; áp filter quyền
  FR-08 ở tầng truy vấn vector; hợp đồng dẫn nguồn (citation) về đúng
  tài liệu/segment; xử lý "không đủ căn cứ"; tham số vận hành `semantic.top_k`,
  `semantic.min_score`, `search.query_timeout_ms`.
- **Out-of-scope:** chọn vector store/embedding model/reranker, deploy TEI–GPU,
  vLLM/LLM sinh câu trả lời (→ `ai-search-infra`); OCR/STT, phân đoạn và toạ độ
  định vị (→ `digitization-pipeline`); luồng xác nhận FR-18 (→
  `digitization-review`); mapping/analyzer/DSL toàn văn, phân trang lexical
  (→ `search-fulltext`); UI/UX chi tiết và user story (→ BA).

## 2. Ràng buộc (Constraints)

- **Kế thừa từ `ai-search-infra.md` (đã sửa 2026-10-10):** vector 1024 chiều
  nằm trên **chính document `segment`** của OpenSearch (1 vector/segment, khoá =
  `segment_id`), dùng chung các trường ACL `owner_user_id`, `scope_group_ids[]`,
  `acl_version` và trường lọc với nhánh lexical, thêm `model_version`; embedding
  qua endpoint REST nội bộ (Qwen3-Embedding-0.6B, URL qua env backend, không xác
  thực); **không có LLM ở giai đoạn 1**.
- **Không rời mạng nội bộ** (NFR-04, FR-24 nhắc lại — giữ nguyên, người dùng xác
  nhận 2026-10-10): endpoint embedding khi triển khai là **dịch vụ nội bộ**; mọi
  nội dung gửi đi embed không ra Internet. Domain công khai chỉ dùng ở dev/test.
- **Chỉ nội dung đã xác nhận (FR-18) mới được embed/index**; **kho cá nhân không
  bao giờ được index** (chặn tại producer sự kiện, không dựa vào filter).
- **Phạm vi dữ liệu FR-08** (`rbac-group-scope.md` QĐ 3): row-level filter tại
  tầng query, **fail-closed**, "chỉ người tạo/nạp thấy dữ liệu chưa chia sẻ" là
  logic hard-code trong lớp filter dùng chung. Với chỉ mục: pre-filter theo group
  id + **double-check bằng row-level filter khi hydrate**
  (`ai-search-infra.md` QĐ 5) — chỉ mục là bộ lọc thứ nhất, PostgreSQL là bộ lọc
  quyết định.
- **Dùng chung, không sao chép:** `ScopeFilterBuilder`, `SearchResultHydrator`,
  audit `search.executed` của `search-service` (`search-fulltext.md` mục 5).
  Frontend/module nghiệp vụ không gọi thẳng OpenSearch/endpoint embedding.
- **Dịch vụ embedding dùng chung** (nhóm AI vận hành, có thể chung GPU với
  OCR/STT) ⇒ embed truy vấn tương tác tranh chấp với embed nền và hàng đợi số
  hoá; backend không kiểm soát được tài nguyên phía dịch vụ, chỉ giới hạn được
  phía gọi (QĐ 5).
- Quy mô mục tiêu: ~1 triệu tài liệu, ~20 triệu segment, vector ~80GB — vượt RAM
  khả dụng nếu để HNSW float32 in-memory.
- **Hợp đồng `lexicalSearch`** lấy từ `search-fulltext.md` §5 (**Approved**):
  `lexicalSearch(query, filters, scopeCtx, topK) → List<(segment_id, bm25_score)>` — xem mục 7.
  Nhánh lexical dùng `simple_query_string` với `default_operator = AND`
  (`search-fulltext.md` QĐ 3) ⇒ một hit lexical nghĩa là **mọi** từ của câu hỏi
  đều có mặt trong segment.
- **Không dùng reranker (2026-10-08, QĐ 2):** đã đồng bộ sang
  `ai-search-infra.md` (QĐ 4, mục 3, 5, 7, 8 — reranker thành dự phòng giai
  đoạn 2, bỏ `semantic.rerank_top_n`) và tài liệu triển khai
  (`docs/00-overview/deployment/*`, `production-deployment.md`,
  `hardware-resource-allocation.md` — bỏ `tei-rerank`).

## 3. NFR (Non-Functional Requirements)

Kế thừa `ai-search-infra.md` mục 3 (đã chấp nhận 2026-08-16). Các số riêng của
tầng truy vấn ngữ nghĩa là đề xuất SA (`[ĐX]`), kiểm chứng bằng đo tải.

| Loại | Yêu cầu |
|---|---|
| Performance | FR-24 end-to-end p95 ≤ 3s, p99 ≤ 5s (kế thừa). `[ĐX]` Ngân sách thời gian mỗi bước: embed truy vấn ≤ 150ms (endpoint nội bộ; đo lại khi có môi trường thật — lần gọi thử qua Internet 2026-10-10 ~0,6s) · k-NN OpenSearch ≤ 1.500ms (`search.query_timeout_ms`) · `lexicalSearch` ≤ 1.500ms (**chạy song song** với embed + vector) · RRF + ngưỡng (CPU) không đáng kể · hydrate + double-check ACL ≤ 100ms ⇒ tổng ≈ 1,8s; trường hợp xấu nhất theo timeout (embed 500ms) ≈ 2,1s |
| Performance (index) | Embed nền không chặn thao tác người dùng; re-embed toàn bộ ≤ 72 giờ (kế thừa). `[ĐX]` Throughput embed ≥ 150 segment/s (gọi endpoint theo batch danh sách string, ngoài giờ cao điểm) — phụ thuộc năng lực dịch vụ embedding, xác nhận với nhóm AI |
| Latency dữ liệu | Từ `review.confirmed` tới lúc tra cứu ngữ nghĩa được: `[ĐX]` ≤ 60s (p95) — **chậm hơn toàn văn (≤ 5s)** vì phải qua bước embed; `acl.changed` phản ánh ≤ 5s (kế thừa — vector nằm chung document nên đi cùng cập nhật ACL của nhánh lexical, không cần đường đồng bộ riêng) |
| Security | Mọi truy vấn k-NN **bắt buộc** kèm pre-filter phạm vi do `ScopeFilterBuilder` sinh; thiếu ngữ cảnh người dùng → 403, không truy vấn. Kết quả luôn double-check bằng row-level filter khi hydrate. Không có chế độ "bỏ filter để tăng recall" |
| Security (audit) | Mỗi truy vấn ghi `search.executed` với `mode = semantic\|hybrid`, câu hỏi nguyên văn **cắt ≤ 200 ký tự**, `result_count`, `min_score` áp dụng (quy tắc dùng chung `search-fulltext.md` QĐ 5) |
| Scalability | ~20 triệu vector; `[ĐX]` field `knn_vector` engine `faiss` HNSW với `mode: on_disk` + nén (vd. `compression_level` 8x–16x; RAM giảm tương ứng so với ~80GB float32), rescore khi truy vấn; heap/off-heap OpenSearch phải tăng so với dev (512MB) — đo lại sau POC |
| Availability | Endpoint embedding lỗi/timeout, hoặc truy vấn k-NN lỗi → **suy giảm có kiểm soát**: rơi về lexical-only kèm cảnh báo rõ trên UI, tuyệt đối không bỏ filter phạm vi. OpenSearch lỗi → semantic-only kèm cảnh báo. Cả hai lỗi → 503. RPO chỉ mục = 0 (dựng lại từ PostgreSQL), RTO re-embed toàn bộ ≤ 72h |
| Khác | **Chất lượng**: `[ĐX]` recall@10 ≥ 0,8 và precision@5 ≥ 0,6 (đo thêm vì không còn rerank) trên tập truy vấn vàng tiếng Việt do nghiệp vụ/QA cung cấp; tỷ lệ dẫn nguồn sai (đoạn trích không thuộc tài liệu được trỏ) = **0** — là lỗi chặn go-live |

## 4. Phương án & Quyết định (ADR)

### Quyết định 1: Chiến lược chunking — đơn vị embed = `segment`, phủ ngữ cảnh bằng cửa sổ lân cận

- **Ngữ cảnh:** `ai-search-infra.md` QĐ 6 đã chốt `point id = segment_id` (ánh
  xạ 1-1 với index toàn văn — điều kiện để hợp nhất hybrid trên cùng khoá). Nhưng
  segment do `digitization-pipeline` sinh có kích thước rất lệch: block OCR có
  thể chỉ vài từ, câu STT ~5–15 giây; embed thẳng sẽ nghèo ngữ cảnh, còn cắt lại
  theo kích thước cố định sẽ phá ánh xạ 1-1 và làm dẫn nguồn kém chính xác.
- **Các phương án xem xét:**
  - A) **Chunk cố định** ~512 token / overlap 64, cắt ngang ranh giới segment.
  - B) **1 vector cho đúng 1 segment**, embed nguyên văn segment, không thêm gì.
  - C) **1 vector cho đúng 1 segment + cửa sổ ngữ cảnh lân cận khi embed**:
    `text_embed = tail(prev_segment, overlap) + text(segment) + head(next_segment,
    overlap)`, chỉ trong cùng tài liệu và cùng đơn vị vật lý (cùng trang với OCR,
    liền kề thời gian với STT); cắt tổng thể ở `embedding.chunk_size_tokens`.
- **Quyết định:** **C**, với tham số (đã có sẵn ở `application.yml` theo
  `ai-search-infra.md` mục 5, **không** đưa vào UI FR-01):
  `embedding.chunk_size_tokens` `[ĐX]` 512 (ngưỡng cắt của `text_embed`),
  `embedding.chunk_overlap_tokens` `[ĐX]` 64 (độ dài đuôi/đầu lân cận mỗi bên).
  - Segment `title`/`note`/`summary` (`position_type = none`) vẫn được embed như mọi
    segment khác, **không** ghép cửa sổ lân cận (không có "lân cận" ngữ nghĩa).
  - Segment vượt `chunk_size_tokens` sau khi ghép: cắt phần lân cận trước; nếu
    riêng nội dung segment vẫn vượt → ~~tách sub-point~~ **(sửa 2026-10-10, QĐ 6)**
    embed nguyên nội dung segment, không ghép lân cận, **không** tách sub-point
    (vector nằm trên document `segment` nên chỉ có 1 vector/segment; Qwen3-Embedding
    nhận ngữ cảnh tới 32k token nên không mất nội dung).
  - **Câu hỏi được bọc instruction, nội dung tài liệu thì không** (khuyến nghị
    của Qwen3-Embedding, bổ sung 2026-10-10): `Instruct: <mô tả tác vụ>\nQuery:
    <câu hỏi>`; chuỗi instruction đặt ở `application.yml`, là một phần của
    `model_version` (đổi ⇒ cache embedding câu hỏi mất hiệu lực, không cần
    re-embed tài liệu). **Chốt 2026-10-11:** bật mặc định, chuỗi `Given a Vietnamese
    question, retrieve relevant passages from administrative documents`, tắt được;
    xác nhận lại bằng tập truy vấn vàng.
- **Lý do:** giữ nguyên ánh xạ 1-1 nên (i) hợp nhất hybrid trên `segment_id` là
  phép hợp tập hợp đơn giản, (ii) dẫn nguồn trỏ về **đúng** segment đã có toạ độ
  trang/dòng/thời gian (FR-25 tái sử dụng được cho FR-24), (iii) cập nhật ACL
  bằng `set_payload` theo `document_id` không đổi. Cửa sổ lân cận lấy lại phần
  lớn lợi ích ngữ cảnh của chunk lớn mà **không sinh thêm điểm** (giữ ~20 triệu
  vector thay vì tăng 1,5–2×). A bị loại vì buộc map ngược chunk→segment khi
  trích dẫn (nguồn sai lệch là rủi ro chặn go-live) và phá khoá hợp nhất hybrid.
  B bị loại vì câu STT/block OCR ngắn cho embedding gần như vô nghĩa.
- **Hệ quả (trade-off):**
  - Vector "nhoè" nhẹ ranh giới ⇒ có thể trả về segment lân cận thay vì segment
    chứa câu trả lời trực tiếp; **không còn reranker bù** (QĐ 2) — chấp nhận, vì
    dẫn nguồn vẫn trỏ đúng segment có thật và người dùng mở ra xem ngữ cảnh;
    hiệu chỉnh `chunk_overlap_tokens` bằng tập truy vấn vàng nếu tỷ lệ lệch cao.
    Cosine dùng cho ngưỡng (QĐ 2) là cosine trên `text_embed` (có cửa sổ lân cận).
  - Re-embed phải đọc theo **tài liệu** (cần lân cận), không embed lẻ từng
    segment ⇒ sự kiện `embed.requested` mang `document_id` + danh sách segment.
  - Sửa một segment ở FR-18 ⇒ phải embed lại chính nó **và 2 segment kề**.
  - Đổi `chunk_size_tokens`/`chunk_overlap_tokens` ⇒ re-embed toàn bộ (đã có quy
    trình alias ở `ai-search-infra.md` QĐ 7).

### Quyết định 2: Hybrid mặc định, hợp nhất bằng RRF, ngưỡng căn cứ bằng cosine + khớp từ khoá (không rerank)

- **Ngữ cảnh:** FR-24 hỏi bằng ngôn ngữ tự nhiên nhưng nghiệp vụ vẫn cần bắt đúng
  số hiệu văn bản/tên riêng — thứ mà vector kém còn BM25 rất mạnh. Điểm BM25
  (không chặn trên, lệch theo truy vấn) và cosine (0–1) **không cùng thang**.
- **Các phương án xem xét:**
  - A) **Tách riêng**: FR-24 chỉ chạy vector, để người dùng tự chọn tab
    "toàn văn" hay "ngữ nghĩa".
  - B) **Hybrid + RRF** trên `segment_id`: `score = Σ 1/(k + rank_i)`, k=60.
  - C) **Hybrid + chuẩn hoá điểm có trọng số** (min-max/z-score, `α·norm(bm25) +
    (1-α)·cosine`).
  - D) **B + rerank cross-encoder** bge-reranker-v2-m3 trên top-N sau RRF, ngưỡng
    `min_score` theo điểm reranker (phương án chốt trước 2026-10-08).
- **Quyết định:** **B** (đổi từ D ngày 2026-10-08 theo quyết định người dùng).
  Pipeline chốt:
  1. Embed câu hỏi (endpoint REST nội bộ, QĐ 6) **song song** với gọi
     `lexicalSearch(query, filters, scopeCtx, topK)` (hợp đồng
     `search-fulltext.md` mục 5).
  2. Truy vấn k-NN OpenSearch trên index `segment`, `k = semantic.top_k` `[ĐX]`
     **50** (kèm điểm cosine); lexical cũng lấy `top_k` = 50. Hợp nhất RRF
     (k=60) trên `segment_id` ở tầng ứng dụng; thứ tự kết quả = điểm RRF. (Sửa
     2026-10-10: không còn sub-point nên không cần bước gộp.)
  3. **Ngưỡng căn cứ:** giữ một ứng viên khi **(a)** cosine ≥ `semantic.min_score`
     `[ĐX]` **0,50** (sửa 2026-10-11 từ 0,55 cho Qwen3-Embedding, theo số đo thử;
     **bắt buộc** hiệu chỉnh lại bằng tập vàng), **hoặc
     (b)** nó có mặt ở nhánh lexical (khớp đủ mọi từ — mục 2). Ứng viên chỉ có ở
     nhánh vector mà cosine < ngưỡng bị loại.
  4. Hydrate + double-check ACL theo thứ tự RRF; trả tối đa
     `search.max_results_per_page`.
  - API: một endpoint riêng cho FR-24 (`mode = hybrid` mặc định, `semantic` và
    `lexical` là tuỳ chọn gỡ lỗi/suy giảm), **dùng lại nguyên bộ lọc có cấu trúc
    của FR-23** (`search-fulltext.md` QĐ 3) để lọc kết hợp với câu hỏi tự nhiên.
- **Lý do:** RRF chỉ dùng **thứ hạng** nên miễn nhiễm với chênh lệch thang điểm
  và không phải tuning lại mỗi khi đổi analyzer/model — quan trọng trong môi
  trường cách ly, ít cơ hội A/B. C cần calibrate `α` + tham số chuẩn hoá theo
  từng truy vấn, dễ hỏng âm thầm khi phân phối điểm đổi. A bị loại vì đẩy gánh
  nặng "chọn công cụ đúng" sang người dùng và cho recall kém nhất ở truy vấn hỗn
  hợp (mô tả ý + mã văn bản). D bị loại (2026-10-08): thêm một lần gọi GPU mỗi
  truy vấn (tranh chấp với OCR/STT), ~1s độ trễ và thêm một điểm lỗi cần chính
  sách suy giảm riêng; đổi lại chấp nhận precision top đầu thấp hơn. Ngưỡng
  (a)/(b) giữ được yêu cầu "không đủ căn cứ" của FR-24 mà không cần reranker:
  vector luôn trả đủ `top_k` láng giềng nên **bắt buộc** có ngưỡng cosine, còn
  khớp đủ mọi từ ở nhánh lexical là bằng chứng tường minh.
- **Hệ quả (trade-off):**
  - RRF **bỏ qua độ lớn điểm** ⇒ một kết quả áp đảo bị hạ bậc, và **không còn
    lớp rerank sửa thứ hạng** ⇒ precision top đầu thấp hơn D. Đo precision@5 trên
    tập vàng (mục 3); nếu không đạt, bật lại rerank bằng **ADR mới** (không tự
    bật bằng cấu hình).
  - Cosine của model embedding (trước là bge-m3, nay Qwen3 — cần đo lại) thường dồn trong dải hẹp (đoạn không liên quan vẫn có thể ~0,4–0,5)
    ⇒ `min_score` nhạy hơn ngưỡng reranker cũ; sai lệch nhỏ đã đổi nhiều kết quả.
  - Câu hỏi dài bằng ngôn ngữ tự nhiên hiếm khi khớp đủ mọi từ (AND) ⇒ nhánh
    lexical chủ yếu đóng góp cho truy vấn ngắn/số hiệu/tên riêng; với câu hỏi
    dài, ngưỡng gần như chỉ dựa vào cosine.
  - Mỗi truy vấn FR-24 chạm **OpenSearch 2 lần (lexical + k-NN) + 1 lần gọi
    embedding** (sửa 2026-10-10, trước: OpenSearch + Qdrant + GPU) ⇒
    chi phí gấp ~2 lần FR-22; phải giới hạn tốc độ truy vấn/người dùng.
  - `total` gần đúng và trang cuối có thể ngắn (kế thừa hệ quả double-check ACL);
    FR-24 **không** hỗ trợ deep paging — chỉ trả 1–2 trang đầu, muốn nữa thì thu
    hẹp bộ lọc.

### Quyết định 3: Áp filter quyền FR-08 — pre-filter trong Qdrant + double-check khi hydrate

> **Sửa 2026-10-10 (QĐ 6):** pre-filter áp trong truy vấn **k-NN của OpenSearch**
> (efficient filtering — `filter` đặt bên trong mệnh đề `knn`, engine `faiss`),
> dùng **đúng** clause phạm vi + FR-23 mà `ScopeFilterBuilder` đã sinh cho nhánh
> lexical — không có bộ dịch filter thứ hai. `acl.changed`, xoá/purge, di chuyển
> thư mục cập nhật document `segment` như nhánh lexical (vector đi cùng document),
> không cần `set_payload` riêng. Các ý A/B/C, lý do và hệ quả dưới đây giữ nguyên,
> đọc "Qdrant" là "k-NN OpenSearch", "payload index" là mapping keyword sẵn có,
> "`full_scan_threshold`/`hnsw_ef`" là `ef_search` và ngưỡng chuyển sang exact
> search của k-NN plugin. **Không được** đặt filter ở `post_filter` (tương đương
> phương án B bị loại).

- **Ngữ cảnh:** `ai-search-infra.md` QĐ 5 đã chốt mô hình ACL denormalized theo
  **group id**. Câu hỏi còn lại của slug này: áp ở đâu trong vòng đời truy vấn
  ANN, vì với vector store thứ tự lọc ảnh hưởng trực tiếp tới việc **có kết quả
  hay không**.
- **Các phương án xem xét:**
  - A) **Pre-filter** (`filter` trong `search` của Qdrant, duyệt HNSW có ràng
    buộc payload).
  - B) **Post-filter** ở `search-service`: lấy top-k không lọc rồi bỏ hit ngoài
    phạm vi.
  - C) **Over-fetch mù**: lấy `k × 10` rồi lọc, coi như xấp xỉ pre-filter.
- **Quyết định:** **A**, cụ thể hoá:
  - Filter bắt buộc: `should[ terms(scope_group_ids, groupIds(user)),
    match(owner_user_id, user) ]` + các bộ lọc FR-23 người dùng chọn (chuyển sang
    payload filter tương đương), sinh **một chỗ duy nhất** bởi `ScopeFilterBuilder`.
  - Bắt buộc tạo **payload index** cho `scope_group_ids`, `owner_user_id`,
    `document_id` (và các trường lọc FR-23 dùng thường xuyên) để Qdrant lọc trong
    lúc duyệt đồ thị thay vì full scan; đặt `full_scan_threshold` hợp lý.
  - `groupIds(user)` đọc từ Redis cache; không lấy được → **403 fail-closed**,
    không truy vấn.
  - Vẫn **double-check** bằng row-level filter khi hydrate; over-fetch
    `size × 1,5 + 10` như nhánh lexical.
  - `acl.changed` cập nhật payload bằng `set_payload` theo filter `document_id`
    (kế thừa) — **không** re-embed.
- **Lý do:** với người dùng có phạm vi hẹp (vài nhóm trên 1 triệu tài liệu),
  B/C gần như luôn trả **rỗng** vì top-k toàn cầu bị chiếm bởi tài liệu ngoài
  phạm vi — sai chức năng chứ không chỉ chậm. Đây cũng chính là lý do
  `ai-search-infra.md` QĐ 2 chọn Qdrant (pre-filter trong HNSW). Double-check giữ
  tính đúng đắn tuyệt đối khi ACL trong chỉ mục còn trễ.
- **Hệ quả (trade-off):** pre-filter chọn lọc rất cao có thể khiến Qdrant rơi
  sang full scan → độ trễ tăng phi tuyến khi corpus lớn (rủi ro mục 6); phải
  giám sát và tinh chỉnh `hnsw_ef`/`full_scan_threshold`. Payload index tốn thêm
  RAM. Cấm tuyệt đối "nới filter khi ít kết quả".

### Quyết định 4: Dẫn nguồn (citation) — Qdrant chỉ giữ khoá + ACL, nội dung trích dẫn lấy từ PostgreSQL

> **Sửa 2026-10-10 (QĐ 6):** quyết định B giữ nguyên với chỉ mục là OpenSearch:
> kết quả k-NN chỉ dùng `segment_id` + điểm; `text`/toạ độ/metadata hiển thị vẫn
> lấy từ PostgreSQL ở bước hydrate (dù document `segment` có chứa text cho nhánh
> lexical, **không** trả text từ chỉ mục cho UI).

- **Ngữ cảnh:** FR-24 bắt buộc dẫn nguồn; dữ liệu có tính chứng cứ nên trích dẫn
  sai nội dung/sai tài liệu là lỗi nghiêm trọng hơn cả việc không tìm ra.
- **Các phương án xem xét:**
  - A) Lưu `text` của segment trong payload Qdrant, trả thẳng cho UI (nhanh, 1
    round-trip).
  - B) **Payload chỉ chứa khoá + ACL + trường lọc**; `text`, toạ độ
    (`page_no`/`line_from`/`line_to`/`bbox_polygon`/`time_*`) và metadata hiển
    thị lấy từ PostgreSQL ở bước hydrate dùng chung.
  - C) LLM tổng hợp câu trả lời có chú thích nguồn.
- **Quyết định:** **B**. Mỗi kết quả FR-24 **bắt buộc** trả:
  `segment_id`, `document_id`, tên/loại tài liệu, `content_source`
  (`ocr|stt|note|title|summary` — `summary` là tóm tắt video, đã qua FR-18
  theo `digitization-results-store.md` §7 điểm 2; segment `sed` **không**
  được embed/trả về ở FR-24 — sửa 2026-10-08), `confirm_source` (`manual|auto` — UI phải hiển thị cờ
  "tự động"), `model_version`, `rrf_score`, `cosine_score` (null nếu chỉ có ở
  nhánh lexical), `matched_lexical` (bool), và vị trí theo đúng quy ước FR-25
  (`page_no` + `line_from`/`line_to` + `bbox_polygon`; hoặc
  `time_start_ms`/`time_end_ms`; hoặc `position_type = none` cho `title`/`note`/`summary`).
  - **Không đủ căn cứ:** nếu sau ngưỡng căn cứ (QĐ 2 bước 3) không còn ứng viên
    nào → trả **danh sách rỗng + thông điệp "không đủ căn cứ
    trong phạm vi dữ liệu của bạn"** (đúng ngoại lệ FR-24). Cấm hạ ngưỡng ngầm,
    cấm trả "kết quả gần đúng nhất" không đạt ngưỡng.
  - **Không bôi vàng giả:** giai đoạn 1 **không highlight** trong đoạn trích
    FR-24 — hợp đồng `lexicalSearch` không trả vị trí khớp; mọi kết quả hiển
    thị nguyên đoạn, kết quả có `matched_lexical = true` chỉ mang nhãn "Khớp
    từ khoá" (sửa 2026-10-08 theo story `search-semantic` AC-2.3).
  - C được ghi nhận là **đã bị loại ở `ai-search-infra.md` QĐ 4** cho giai đoạn 1
    (rủi ro ảo giác); nếu bật lại ở giai đoạn 2, mọi câu sinh ra phải neo vào
    danh sách `segment_id` do bước này trả về.
- **Lý do:** PostgreSQL là nguồn sự thật; nội dung segment **có thể bị sửa** ở
  luồng kiểm duyệt FR-18 và payload chỉ mục là eventually consistent ⇒ A có cửa
  sổ hiển thị **văn bản đã lỗi thời kèm dẫn nguồn trông rất thuyết phục** — dạng
  sai nguy hiểm nhất với dữ liệu chứng cứ. B còn giảm dung lượng payload
  (~20 triệu đoạn text không nằm trong Qdrant) và tái dùng nguyên
  `SearchResultHydrator` đã có (không phát sinh round-trip mới, vì hydrate vốn
  bắt buộc để double-check ACL).
- **Hệ quả (trade-off):** kết quả FR-24 phụ thuộc PostgreSQL sẵn sàng — DB chậm
  thì cả tìm kiếm chậm (đã tính 100ms trong ngân sách mục 3); segment bị xoá
  giữa chừng → hit biến mất khỏi kết quả (đúng, chấp nhận được).

### Quyết định 5: Ngân sách thời gian, tham số vận hành và suy giảm chức năng

- **Ngữ cảnh:** FR-24 gọi OpenSearch (lexical + k-NN), endpoint embedding và
  PostgreSQL (sửa 2026-10-10, trước: OpenSearch, Qdrant, TEI-embed); cần biên rõ ràng để không kéo sập trải nghiệm
  chung, và tham số phải khớp `system-config` (FR-01).
- **Quyết định:**
  - Tham số **có trong UI FR-01** (kế thừa danh sách ở `ai-search-infra.md`
    mục 5, **trừ** `semantic.rerank_top_n` — bỏ cùng reranker): `semantic.top_k`
    = 50, `semantic.min_score` = 0,50 (ngưỡng cosine, QĐ 2; sửa 2026-10-11 từ 0,55),
    `search.query_timeout_ms` = 1.500 (dùng chung với `search-fulltext`),
    `search.max_results_per_page` = 20 (tối đa 50). Ràng buộc hợp lệ:
    `top_k ≤ 200`, `min_score ∈ [0, 1]`.
  - **`search.query_timeout_ms` là timeout của *mỗi lời gọi engine*** (một truy
    vấn lexical hoặc một truy vấn k-NN), không phải của toàn request FR-24 —
    thống nhất với `search-fulltext.md` QĐ 6; embed có timeout riêng
    (`[ĐX]` 500ms) ở `application.yml`. Endpoint embedding lỗi/timeout ⇒ coi như
    nhánh vector lỗi (suy giảm về lexical-only như dưới).
  - **Suy giảm từng phần:** một nhánh lỗi/timeout → vẫn trả kết quả nhánh còn lại
    kèm cờ `degraded_mode` để UI cảnh báo "kết quả chỉ theo từ khoá / chỉ theo ngữ
    nghĩa"; **không bao giờ** suy giảm bằng cách bỏ filter phạm vi.
  - **Chống quá tải dịch vụ embedding:** giới hạn đồng thời truy vấn ngữ nghĩa
    `[ĐX]` ≤ 8, **toàn hệ thống** (một semaphore phía backend, chốt 2026-10-10);
    hàng đợi truy vấn tương tác **ưu tiên cao hơn** job embed nền (job nền dùng
    batch, có thể tạm dừng); vượt hạn mức → 429 kèm thông điệp rõ.
  - Cache kết quả embed câu hỏi trong Redis theo `hash(query) + model_version`
    `[ĐX]` TTL 10 phút (không cache kết quả tìm kiếm — kết quả phụ thuộc phạm vi
    người dùng, cache dễ thành đường rò rỉ).
- **Lý do:** tách ngân sách theo bước là cách duy nhất giữ p95 ≤ 3s khi 3 hệ nối
  tiếp/song song; cache **embedding** an toàn vì không phụ thuộc người dùng, còn
  cache **kết quả** thì có.
- **Hệ quả:** người dùng có thể thấy hai chất lượng kết quả khác nhau giữa các
  lần (chế độ suy giảm) ⇒ BA phải thiết kế thông điệp rõ ràng; giá trị mặc định
  của `min_score`/`top_k` chỉ là điểm khởi đầu, phải hiệu chỉnh bằng tập truy vấn
  vàng.

### Quyết định 6: Vector trong OpenSearch k-NN + embedding qua endpoint REST nội bộ (2026-10-10)

- **Ngữ cảnh:** người dùng chốt 2026-10-10 (khi review tech spec): dịch vụ
  embedding do nhóm AI cung cấp dưới dạng endpoint REST riêng (Qwen3-Embedding-0.6B)
  và vector lưu vào OpenSearch, thay cho TEI/bge-m3 + Qdrant trong thiết kế cũ.
  Backend vẫn là nơi điều phối việc embed sau khi nội dung được duyệt (FR-18).
- **Các phương án xem xét:**
  - A) Giữ Qdrant làm vector store, chỉ đổi nguồn embedding.
  - B) **Vector là field `knn_vector` trên index `segment` của OpenSearch**.
  - C) Index vector riêng trong OpenSearch (`segment-vec`), tách khỏi `segment`.
- **Quyết định:** **B**.
  - **Luồng ghi:** người dùng duyệt nội dung số hoá (`review.confirmed`, thủ
    công hoặc tự động) → backend lưu PostgreSQL + outbox → message `embed.requested`
    (theo `document_id`) vào queue `q.embed` → consumer **trong backend** dựng
    `text_embed` (QĐ 1), gọi endpoint theo batch danh sách string, nhận danh sách
    vector cùng thứ tự → partial update field vector + `model_version` vào document
    `segment` tương ứng. Segment `sed` không embed (QĐ 4).
  - **Hợp đồng endpoint:** `POST <EMBEDDING_API_URL>` body
    `{"inputs": [string...], "mode": "dense"}` → `[{"dense": [float×1024]}...]`
    cùng thứ tự `inputs`; vector đã chuẩn hoá L2 (đo 2026-10-10: norm = 1,0);
    không xác thực; URL lấy từ env backend. Kích thước batch tối đa và timeout
    đặt ở `application.yml` `[ĐX]`.
  - **Mapping:** `knn_vector` dimension 1024, `space_type` `cosinesimil` (hoặc
    `innerproduct` — tương đương vì vector đã chuẩn hoá), engine `faiss`, HNSW,
    `mode: on_disk` + nén (NFR Scalability). Thêm field vào mapping qua quy trình
    alias/reindex của `ai-search-infra.md` QĐ 7 (đổi mapping knn cần index mới).
- **Lý do:** một engine cho cả hai nhánh ⇒ **một** bộ filter phạm vi
  (`ScopeFilterBuilder`), **một** đường cập nhật ACL/xoá/di chuyển thư mục —
  loại bỏ rủi ro "hai bộ filter lệch nhau" (rủi ro cao nhất mục 6); bớt một hệ
  stateful; OpenSearch 2.17.1 (đang dùng) đã có k-NN plugin với efficient
  filtering và chế độ on-disk. A bị loại vì giữ nguyên chi phí hai engine mà
  không còn lý do (Qdrant chỉ hơn ở quantization/payload index, bù được bằng
  on-disk mode). C bị loại vì phải đồng bộ ACL sang hai index — mất đúng lợi ích
  chính của B.
- **Hệ quả (trade-off):**
  - Index `segment` nặng hơn (vector + đồ thị HNSW) ⇒ cần tăng tài nguyên
    OpenSearch, lexical và k-NN tranh chấp cùng node; reindex `segment` lâu hơn.
  - Document `segment` có hai trạng thái: đã có text (lexical tìm được ngay ≤ 5s)
    nhưng chưa có vector (k-NN chưa thấy, ≤ 60s) — chấp nhận, đúng NFR Latency.
  - Chất lượng phụ thuộc Qwen3-Embedding-0.6B (nhỏ, đa ngữ) — `min_score` và việc
    dùng instruction cho câu hỏi (QĐ 1) phải hiệu chỉnh lại bằng tập vàng.
  - Backend phụ thuộc một dịch vụ ngoài phạm vi kiểm soát (không xác thực, chỉ dựa
    vào cô lập mạng nội bộ); thay đổi hợp đồng/model phía AI ⇒ re-embed toàn bộ.
  - Dữ liệu khởi điểm trắng ⇒ không cần job backfill ban đầu (chốt 2026-10-10);
    re-embed toàn bộ chỉ khi đổi model/chunking.

## 5. Tech stack đề xuất

- Backend: Spring Boot — mở rộng `search-service` (không tạo service mới):
  `SemanticSearchService`, `EmbeddingClient` (REST tới endpoint embedding nội bộ,
  URL qua env — QĐ 6), `HybridFusion`
  (RRF k=60 + ngưỡng căn cứ), dùng chung `ScopeFilterBuilder`,
  `SearchResultHydrator`, audit `search.executed`. Truy vấn k-NN dùng client
  OpenSearch sẵn có (không thêm client Qdrant — sửa 2026-10-10).
  `indexer-worker` bổ sung consumer `embed.requested` (batch theo `document_id`)
  **trong `backend-api`** (chốt 2026-10-10; `indexer-worker` vốn nằm ở đó —
  `ai-search-infra.md` QĐ 7, "Sai lệch có chủ đích khi hiện thực hoá").
- Frontend: trang riêng `/search/semantic` (menu "Tra cứu ngữ nghĩa", không
  sửa trang `/search`); một ô hỏi bằng ngôn ngữ tự nhiên + panel bộ lọc FR-23 dùng lại; kết
  quả là **danh sách đoạn trích có dẫn nguồn** (nút nhảy tới trang/mốc thời
  gian, khoanh bbox), cờ "nội dung tự xác nhận (auto)", cờ `degraded_mode`, và
  trạng thái rỗng "không đủ căn cứ".
- Data store: OpenSearch index `segment` — nhánh lexical (qua `lexicalSearch`)
  **và** field `knn_vector` 1024 chiều + `model_version` cho nhánh vector (QĐ 6;
  thay Qdrant `segment-vec-v1`); PostgreSQL (nguồn sự thật cho
  text/toạ độ/hydrate); Redis (cache `groupIds(user)`, cache embedding câu hỏi).
- AI serving: Qwen3-Embedding-0.6B qua endpoint REST nội bộ do nhóm AI vận hành
  (QĐ 6, thay TEI bge-m3). **Không** dùng reranker (QĐ 2); **không** vLLM/LLM ở
  giai đoạn 1.
- Infra/Deploy: không thêm thành phần mới; **không** cần Qdrant, `tei-embed` cho
  slug này (gỡ khỏi compose hay giữ lại là việc của DEV/vận hành). Thêm env
  `EMBEDDING_API_URL` cho backend; tăng tài nguyên OpenSearch cho k-NN. Tham số
  ảnh hưởng chỉ mục (`embedding.model_name`, `model_version`,
  `chunk_size_tokens`, `chunk_overlap_tokens`) ở `application.yml` — đổi ⇒
  re-embed toàn bộ theo quy trình alias.

## 6. Rủi ro

| Rủi ro | Mức độ | Giảm thiểu |
|---|---|---|
| **Chất lượng embedding tiếng Việt** — Qwen3-Embedding-0.6B (từ 2026-10-10; trước là bge-m3) là model nhỏ, đa ngữ, không chuyên tiếng Việt hành chính/pháp lý; truy vấn mô tả ý có thể trả đoạn "na ná" nhưng sai nghiệp vụ; **không còn reranker** chấm lại (QĐ 2) | **Cao** | Hybrid (QĐ 2) + ngưỡng cosine bù precision; đo recall@10/precision@5 trên **tập truy vấn vàng do nghiệp vụ/QA cung cấp** trước go-live; hiệu chỉnh `min_score`; giữ lối thoát đổi model (kèm re-embed) hoặc bật lại rerank bằng ADR mới |
| **Rò rỉ nội dung ngoài phạm vi qua kết quả ngữ nghĩa** — nguy hiểm hơn toàn văn vì người dùng **không cần biết từ khoá** vẫn moi được nội dung; nguyên nhân: quên filter trong mệnh đề k-NN (hoặc đặt nhầm ở `post_filter`), ACL trong chỉ mục lệch, cache kết quả sai khoá — rủi ro "hai bộ filter lệch nhau" đã giảm vì dùng chung một engine (QĐ 6) | **Cao** | Pre-filter bắt buộc qua `ScopeFilterBuilder` + double-check khi hydrate (QĐ 3); **cấm cache kết quả tìm kiếm**; test hồi quy "A không moi được nội dung của B bằng câu hỏi mô tả"; audit mọi truy vấn (FR-09) |
| **Chi phí re-embed khi đổi model/chunking** — 20 triệu segment chiếm trọn dịch vụ embedding tới ~72h, tranh chấp với hàng đợi OCR/STT | **Cao** | `model_version` gắn vào mọi document `segment`; chạy vào index mới + đổi alias, ngoài giờ cao điểm; text đã xác nhận nằm ở PostgreSQL nên **không** phải OCR/STT lại; coi việc đổi model là quyết định cấp dự án, không phải cấu hình runtime |
| **Độ trễ tăng phi tuyến khi corpus lớn + filter chọn lọc cao** (k-NN rơi sang exact search với người dùng phạm vi hẹp; k-NN + lexical tranh chấp cùng node OpenSearch) | **Cao** | Efficient filtering (faiss) + tinh chỉnh `ef_search`; tăng tài nguyên OpenSearch; đo tải với hồ sơ người dùng phạm vi hẹp/rộng; timeout 1.5s + suy giảm về lexical-only |
| **Trích dẫn lệch nội dung** nếu sau này ai đó tối ưu bằng cách trả text từ chỉ mục thay vì PostgreSQL (vi phạm QĐ 4) | Trung bình | Ghi thành ràng buộc kiến trúc + checklist review; test: sửa segment ở FR-18 rồi tìm lại phải thấy **văn bản mới** |
| **Kỳ vọng sai của người dùng**: FR-24 giai đoạn 1 **không** trả lời bằng câu văn, chỉ trả đoạn liên quan có dẫn nguồn | Trung bình | BA viết rõ thông điệp/nhãn UI ("các đoạn liên quan", không phải "câu trả lời"); nêu trong tài liệu người dùng; giai đoạn 2 mới cân nhắc LLM |
| **Ngưỡng `min_score` sai** → hoặc luôn "không đủ căn cứ" (người dùng mất niềm tin), hoặc đầy nhiễu (mất giá trị dẫn nguồn); cosine bge-m3 dồn dải hẹp nên nhạy hơn điểm reranker | **Cao** | Hiệu chỉnh bằng tập vàng trước go-live; tham số sửa được ở FR-01 (R3); giám sát tỷ lệ truy vấn trả rỗng |
| **Tranh chấp dịch vụ embedding** giữa truy vấn tương tác và embed nền/hàng đợi số hoá | Trung bình | Ưu tiên truy vấn tương tác, batch job nền tạm dừng được; giới hạn ≤ 8 truy vấn ngữ nghĩa đồng thời toàn hệ thống (QĐ 5); thống nhất năng lực/giám sát với nhóm AI |
| **Phụ thuộc dịch vụ embedding ngoài phạm vi backend** (QĐ 6): không xác thực, hợp đồng/model có thể đổi phía nhóm AI; độ trễ thực tế chưa đo trong mạng nội bộ | Trung bình | Chỉ mở trong mạng nội bộ (NFR-04); kiểm tra số chiều vector = 1024 và số phần tử trả về = số `inputs` mỗi lần gọi, lệch → lỗi/DLQ, không ghi; `model_version` cấu hình phía backend, đổi model phải thông báo + re-embed; đo p95 embed trước go-live |
| **Câu hỏi ngôn ngữ tự nhiên trong nhật ký audit** có thể chứa thông tin nhạy cảm dài hơn truy vấn từ khoá | Trung bình | Cắt ≤ 200 ký tự (dùng chung quy tắc `search-fulltext.md`); `audit_log` append-only, chỉ tra cứu theo FR-10 |
| **Hợp đồng `lexicalSearch` thay đổi về sau** (`search-fulltext.md` đã Approved, chữ ký ổn định) | Thấp | Chỉ dùng đúng chữ ký ở `search-fulltext.md` §5; nếu tài liệu đó được mở lại để sửa hợp đồng, rà lại QĐ 2 (fusion) và QĐ 5 (ngân sách thời gian) |
| **Trễ 60s + trễ 24h của FR-18** khiến nội dung vừa nạp không tra cứu ngữ nghĩa được | Thấp | UI nêu trạng thái "chờ kiểm duyệt/đang lập chỉ mục"; segment `title` giúp tìm theo tên ngay ở nhánh lexical |

## 7. Câu hỏi mở

Đã chốt với người dùng 2026-08-16:

- [x] **Sub-point cho segment vượt `embedding.chunk_size_tokens`** (QĐ 1):
      **chọn sửa hợp đồng.** `ai-search-infra.md` mục 8 đã được bổ sung — cho
      phép point id = `UUIDv5(segment_id, sub_index)` khi một segment vượt
      ngưỡng sau khi ghép cửa sổ lân cận, payload vẫn mang `segment_id` gốc
      (dẫn nguồn không đổi); mặc định vẫn là ánh xạ 1-1.
- [x] **Ngữ nghĩa của `search.query_timeout_ms`** (QĐ 5): **giữ nguyên** —
      timeout của **mỗi lời gọi engine** (OpenSearch/Qdrant riêng lẻ), kế thừa
      `search-fulltext.md` QĐ 6. Không tách thêm `semantic.total_timeout_ms`;
      tổng request FR-24 có thể tới ~2,8s do cộng dồn các bước (mục 3).
- [x] **Giá trị mặc định `semantic.top_k` = 50 / `rerank_top_n` = 20 /
      `min_score` = 0,35`** *(lỗi thời từ 2026-10-08: bỏ `rerank_top_n`,
      `min_score` thành ngưỡng cosine 0,55 — xem bên dưới)*: **chấp nhận tạm thời** (`[ĐX]`), không chặn
      pipeline BA/DEV. Trách nhiệm cung cấp tập truy vấn vàng tiếng Việt để
      hiệu chỉnh lại các số này trước go-live vẫn thuộc về nghiệp vụ/QA
      (`search-fulltext.md`).
- [x] **UI chọn chế độ tìm kiếm:** **luôn hybrid**, không cho người dùng chọn
      từ khoá/ngữ nghĩa riêng — đúng với QĐ 2 (tách riêng cho recall kém hơn ở
      truy vấn hỗn hợp). BA thiết kế story theo hướng này, không cần UI chọn
      chế độ (trừ cờ `degraded_mode` khi hệ thống tự suy giảm).
- [x] **Audit truy vấn ngữ nghĩa:** **dùng chung** `search.executed` với các
      field `mode = semantic|hybrid`, `min_score`, `degraded_mode` — không tạo
      loại sự kiện audit riêng.
- [x] **Truy vấn cross-lingual (Anh↔Việt):** **không yêu cầu**, ngoài phạm vi
      giai đoạn 1. Tập truy vấn vàng chỉ cần tiếng Việt.
- [x] **Giai đoạn 2 — LLM sinh câu trả lời:** **chưa xác định thời điểm kích
      hoạt**, giữ nguyên là hướng mở rộng backlog (đã hoãn ở `ai-search-infra.md`
      QĐ 4). Khi cần sẽ mở ADR/slug riêng; nếu bật, kết quả của slug này là đầu
      vào bắt buộc (mọi câu sinh ra phải neo vào `segment_id`).

Chốt 2026-10-08:

- [x] **Trạng thái phụ thuộc `search-fulltext.md`** (hợp đồng `lexicalSearch`
      cho QĐ 2, QĐ 5) → **Chốt:** đã giải quyết — `search-fulltext.md` đã
      `Status: Approved`, §5 giữ nguyên chữ ký `lexicalSearch(query, filters,
      scopeCtx, topK) → List<(segment_id, bm25_score)>`; không cần sửa QĐ 2/QĐ 5,
      không còn chặn `/ba-story`/`/dev-spec` (cập nhật: mục 2, mục 6).
- [x] **Bỏ rerank cross-encoder** → **Chốt:** bỏ; xếp hạng theo RRF, ngưỡng căn
      cứ = cosine ≥ `semantic.min_score` (`[ĐX]` 0,55) **hoặc** khớp đủ từ ở nhánh
      lexical; bỏ `semantic.rerank_top_n` (cập nhật: mục 1, 2, 3, QĐ 1, QĐ 2,
      QĐ 4, QĐ 5, mục 5, mục 6). Đã đồng bộ `ai-search-infra.md`,
      `search-fulltext.md`, `digitization-pipeline.md`, tài liệu triển khai,
      story BA và tech spec DEV cùng ngày.
- [x] **Đồng bộ với review story BA** → **Chốt:** QĐ 4 — kết quả FR-24 gồm
      `summary` (tóm tắt video), loại `sed`; không highlight ở giai đoạn 1,
      chỉ nhãn "Khớp từ khoá"; mục 5 — trang riêng `/search/semantic`
      (cập nhật: QĐ 4, mục 5).

Chốt 2026-10-10 (qua `/resolve-questions` tech spec DEV, loại "thuộc tầng trên"):

- [x] **Vector store + embedding** → **Chốt:** vector là field `knn_vector` trên
      index `segment` OpenSearch, không dùng Qdrant; embedding Qwen3-Embedding-0.6B
      qua endpoint REST nội bộ riêng (không xác thực, URL qua env backend), không
      dùng TEI/bge-m3; backend điều phối embed sau `review.confirmed` bằng consumer
      `q.embed` trong `backend-api` (cập nhật: mục 1, 2, 3, QĐ 1, QĐ 2, QĐ 3, QĐ 4,
      QĐ 5, **QĐ 6 mới**, mục 5, mục 6; `ai-search-infra.md` QĐ 2, QĐ 4, mục 5, 8).
- [x] **NFR-04** → **Chốt:** giữ nguyên; endpoint embedding khi triển khai là
      dịch vụ nội bộ (cập nhật: mục 2).
- [x] **Sub-point cho segment dài** → **Chốt (đề xuất SA, chờ review):** bỏ
      sub-point; segment quá dài embed nguyên nội dung, không ghép lân cận (QĐ 1).
- [x] **Backfill ban đầu** → **Chốt:** không cần, dữ liệu khởi điểm trắng (QĐ 6).
- [x] **Giới hạn 8 truy vấn đồng thời** → **Chốt:** toàn hệ thống (QĐ 5).
- [x] **Instruction cho câu hỏi (Qwen3)** → **Chốt 2026-10-11:** bật mặc định,
      chuỗi `Given a Vietnamese question, retrieve relevant passages from
      administrative documents`, tắt được. Đo thử (4 câu × 6 đoạn, endpoint dev):
      có instruction cosine đoạn liên quan 0,60–0,70, không liên quan ≤ 0,40 ⇒
      `min_score` khởi điểm 0,50 (cập nhật: QĐ 1, QĐ 2, QĐ 5).
- [x] **Giới hạn batch & timeout endpoint embedding** → **Chốt tạm 2026-10-11:**
      batch 32, timeout 500ms (câu hỏi) / 30s (embed nền); đo qua Internet 1 câu
      0,41s, 32 câu 0,99s. Giới hạn thật + p95 mạng nội bộ vẫn chờ nhóm AI xác
      nhận — chỉ chỉnh cấu hình, không đổi thiết kế (QĐ 6).
