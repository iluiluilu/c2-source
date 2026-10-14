# Runbook: tra cứu ngữ nghĩa (OpenSearch k-NN, embedding REST, `q.embed`)

> Liên quan: `docs/03-dev/specs/search-semantic*.md` (SPEC-search-semantic), `docs/01-sa/architecture/search-semantic.md` QĐ 6.
> Vector nằm trong index `segment` của OpenSearch (field `embedding`); không còn Qdrant/TEI.

## 1. Kết quả spike T4 (OpenSearch 2.17.1, image `opensearch/Dockerfile`, đo 2026-10-11)

| Cấu hình thử | Kết quả | Quyết định |
|---|---|---|
| `faiss` + `hnsw` + `space_type: cosinesimil` | **Bị từ chối khi tạo mapping** ("hnsw with faiss does not support space type cosinesimil") | Dùng `innerproduct` (vector Qwen3 đã chuẩn hoá L2, norm = 1,0 ⇒ ip = cosine) |
| `faiss` + `innerproduct` + `mode: on_disk` + `compression_level: 32x` | Tạo được, nhưng **mọi truy vấn k-NN trả 500** (`NullPointerException ... fieldInfo is null`) ngay khi shard có **bất kỳ document nào không có vector** (title/note chưa embed, segment `sed`, document chưa kịp embed...) — kể cả sau `forcemerge`. Chạy tốt nếu *mọi* document đều có vector | **Không dùng** `on_disk`/nén 32x ở 2.17.1: không thể bảo đảm mọi document có vector |
| `faiss` + `innerproduct` (không `on_disk`) | Chạy tốt với shard trộn document có/không vector; filter trong `knn` hoạt động | Chọn |
| `faiss` + `innerproduct` + `encoder sq fp16` | Chạy tốt, trộn document có/không vector; điểm vẫn = `1 + ip` | **Chọn (mapping hiện tại)** — giảm một nửa bộ nhớ native so với fp32 |
| `lucene` + `cosinesimil` | Chạy tốt, trộn document có/không vector | Phương án dự phòng (đổi `KnnScore.SEGMENT_SPACE_TYPE` + công thức `2·score − 1`) |

- Điểm k-NN của `innerproduct`: `score = 1 + ip` khi `ip ≥ 0`, `score = 1/(1 − ip)` khi `ip < 0` ⇒ `cosine = score − 1` / `1 − 1/score` (`KnnScore.toCosine`).
- Mapping cuối: `backend/src/main/resources/search/segment-v2-index.json` (`knn: true`, `ef_search: 100`, `m: 16`, `ef_construction: 128`, `sq fp16`). Bộ nhớ ước tính: ~2 KB/vector (fp16) + ~130 B/vector đồ thị, off-heap; 1 triệu đoạn ≈ 2,2 GB. Nếu cần tiết kiệm hơn: nâng OpenSearch ≥ 2.19 rồi thử lại `on_disk` (cần kiểm lại lỗi trộn document không vector trước).
- Đổi mapping/`space_type` = index mới + re-embed (mục 4).

## 2. Biến môi trường & cờ

| Biến | Mặc định | Ghi chú |
|---|---|---|
| `EMBEDDING_API_URL` | (compose dev: `https://embedding.aitutor.io.vn/ai/services/v1/embedding/embed`) | **stg/prod: chỉ dịch vụ nội bộ** (NFR-04). Kiểm tra truy cập từ container backend trước khi bật cờ. |
| `SEMANTIC_ENABLED` | `false` | Che cả endpoint `/search/semantic` (404 khi tắt) và poller phát `embed.requested` + consumer `q.embed` + job đối soát. `folder.moved` **không** phụ thuộc cờ. |
| `EMBEDDING_BATCH_SIZE` / `EMBEDDING_TIMEOUT_MS` / `EMBEDDING_BATCH_TIMEOUT_MS` | `32` / `500` / `30000` | Đo 2026-10-11 qua Internet: 1 câu 0,41 s, 32 câu 0,99 s, 64 câu 1,89 s. Chờ nhóm AI xác nhận giới hạn thật + p95 mạng nội bộ. |
| `EMBEDDING_MODEL_VERSION` | `qwen3-embedding-0.6b-1024` | Đổi giá trị này = coi mọi vector cũ là sai phiên bản (k-NN lọc theo `model_version`, đối soát gửi lại). |
| `EMBEDDING_QUERY_INSTRUCTION_ENABLED` / `EMBEDDING_QUERY_INSTRUCTION` | `true` / "Given a Vietnamese question, retrieve relevant passages from administrative documents" | Chỉ bọc câu hỏi, không bọc nội dung tài liệu. |
| `SEMANTIC_QUERY_CONCURRENCY` | `8` | Semaphore toàn hệ thống (mỗi instance); vượt ⇒ 429. |
| `QUEUE_EMBED_NAME` | `q.embed` | |
| `SEMANTIC_FOLDER_MOVE_THROTTLE_RPS` | `500` | `requests_per_second` của `update_by_query` khi `folder.moved`. |
| `SEMANTIC_RECONCILIATION_INTERVAL_MS` / `SEMANTIC_RECONCILIATION_BATCH_SIZE` | `600000` / `500` | Mỗi lượt quét tối đa N tài liệu (con trỏ vòng). |
| `SEMANTIC_EMBED_CONSUMER_CONCURRENCY` / `SEMANTIC_EMBED_MAX_RETRIES` | `1` / `5` | Backoff retry: 5 s, 30 s, 2 m, 10 m, 30 m rồi `q.embed.dlq`. |

Tham số chỉnh không cần deploy (Cấu hình hệ thống, R3): `semantic.top_k` (50, 1–200), `semantic.min_score` (`0.50`, số thập phân 0–1 — kiểu chuỗi vì cấu hình số chỉ nhận nguyên). Hiệu chỉnh bằng tập truy vấn vàng trước go-live (T23).

## 3. Queue `q.embed` (khớp stg đã tạo tay 2026-09-15)

`RabbitConfig` khai báo: `q.embed` (durable, `x-max-priority=9`, DLX `ai.jobs.dlx`/key `q.embed.dlq`), `q.embed.retry` (durable, DLX `ai.jobs`/key `q.embed`, không TTL queue-level), `q.embed.dlq` (durable, không tham số). Trước deploy stg: `rabbitmqctl list_queues name arguments` đối chiếu; lệch ⇒ `406 PRECONDITION_FAILED` làm crash-loop.

## 4. Triển khai: tạo `segment-v2` và đổi alias

1. Đặt `EMBEDDING_API_URL`; `curl` thử từ container backend.
2. Deploy backend với `SEMANTIC_ENABLED=false`. `SearchIndexBootstrap` tạo `segment-v2`:
   - Môi trường mới (chưa có alias `segment`): `segment-v2` giữ alias `segment`, xong.
   - Môi trường đã có `segment-v1` giữ alias: `segment-v2` được tạo **không alias** (log WARN) — làm tiếp bước 3.
3. Reindex + đổi alias nguyên tử (giữ `index_version` làm external version để upsert sau đó không bị từ chối):
   ```
   POST _reindex?wait_for_completion=false
   { "source": {"index": "segment-v1"},
     "dest":   {"index": "segment-v2", "version_type": "external"} }
   # sau khi xong và đếm khớp:
   POST _aliases
   { "actions": [ {"remove": {"index": "segment-v1", "alias": "segment"}},
                  {"add":    {"index": "segment-v2", "alias": "segment"}} ] }
   ```
   Document cũ chưa có vector: k-NN bỏ qua, lexical không ảnh hưởng. Rollback: trỏ alias lại `segment-v1` (mất vector, lexical không ảnh hưởng).
4. Bật `SEMANTIC_ENABLED=true`: dữ liệu mới có vector (không backfill — dữ liệu khởi điểm trắng); `SemanticIndexReconciliationJob` vá dần các document đủ điều kiện còn thiếu vector.
5. Khi đối soát đạt (log `semantic reconciliation: ... missingVectors=0`), bật menu (web đã có mục "Tra cứu ngữ nghĩa").
6. Gỡ Qdrant: `docker compose up -d --remove-orphans`; xoá volume `qdrant_data` **chỉ khi người dùng xác nhận**.

## 5. Vận hành

- Đối soát vector (thủ công): số segment đủ điều kiện có `embedding` = `GET segment/_count {"query":{"exists":{"field":"embedding"}}}` so với tập segment đã xác nhận ở PostgreSQL; tự động bởi `SemanticIndexReconciliationJob` (WARN khi lệch > 0,1%).
- Cảnh báo: log ERROR `semantic embed DLQ alert` khi `q.embed.dlq` > 0 (replay: `rabbitmqadmin` shovel thủ công — consumer idempotent); log ERROR `outbox_event ... PARKED` (job `SearchIndexReconciliationJob`) khi poller bỏ cuộc.
- Lệnh kiểm vector một segment: `GET segment/_doc/<segment_id>?_source_includes=model_version,folder_path_ids`.
- Bất biến R4: mọi lần lexical re-index một segment (`SegmentIndexer.upsertSegments`) làm mất vector của segment đó; poller luôn phát `embed.requested` cho đúng tập đó — đừng thêm đường gọi `upsertSegments` mới mà không kèm `embed.requested`.
- Đổi model/chunking: index mới + re-embed toàn bộ + đổi alias (SA QĐ 7); đổi `EMBEDDING_MODEL_VERSION`.

## 6. Hiệu chỉnh `semantic.min_score` / `semantic.top_k` (T23, điều kiện go-live)

1. Nghiệp vụ/QA cung cấp tập truy vấn vàng tiếng Việt: `<câu hỏi>\t<segmentId kỳ vọng,...>` (cột 2 trống = câu hỏi phải trả "không đủ căn cứ"), trên tài liệu thuộc phạm vi của một tài khoản R1/R2 thử nghiệm.
2. Tạm đặt `semantic.min_score` = 0.30 (Cấu hình hệ thống) để endpoint trả đủ ứng viên, rồi chạy `TOKEN=<bearer> python3 backend/scripts/semantic-calibrate.py golden.tsv --sweep 0.35,0.40,0.45,0.50,0.55,0.60` — in recall@10, precision@5 và tỷ lệ rỗng cho từng ngưỡng.
3. Chọn ngưỡng (khởi điểm 0,50: đo thử Qwen3 cho đoạn liên quan 0,60–0,70, không liên quan ≤ 0,40, mẫu nhỏ), ghi vào `semantic.min_score`; quyết định bật/tắt instruction (`EMBEDDING_QUERY_INSTRUCTION_ENABLED`) bằng cách chạy lại với hai giá trị. Giám sát tỷ lệ kết quả rỗng sau go-live.
