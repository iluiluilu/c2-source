## Handoff: search-semantic

### Đã chốt
- SA/story/spec `Approved`; DEV implement xong T1–T22 (2026-10-11); QA `PASS WITH ISSUES`, AC-1…AC-8 Pass, không blocking, không rò rỉ quyền (`docs/04-qa/reports/search-semantic.md`).
- Lệch spec đã được QA chấp nhận: mapping `faiss`+`innerproduct`+`sq fp16` (không `on_disk`: NPE 500 trên 2.17.1), cos = `score − 1`; segment id `String`; `semantic.min_score` kiểu STRING (0–1); `SemanticResultCard` riêng; query chỉ giới hạn 256 ký tự; bootstrap không tạo alias `segment` nếu đã có index khác.
- Sửa sau QA: `EmbeddingClient` tự chuẩn hoá L2 (norm 0/NaN → lỗi); nhánh lexical `must_not content_source=sed`. Backend đã rebuild.
- Flag `SEMANTIC_ENABLED=false` mặc định.

### Câu hỏi mở / rủi ro
- Metric Micrometer (spec §7, QA N6): cần duyệt thêm dependency `spring-boot-starter-actuator`.
- Link mở tài liệu cho kết quả `title`/`note` (QA N4): cần quyết định sản phẩm về đích điều hướng.
- QA N5 (unit test `SemanticResultCard`), N7 (giới hạn/đặt tên thread `branchExecutor`): chưa làm.
- `SemanticIndexingIT.movingAFolder…` fail 1 lần trong full verify, lần chạy lại full verify pass 520/520 → flaky (đọc index không qua Awaitility), nên thêm Awaitility.
- T23: hiệu chỉnh `min_score`/`top_k`/instruction bằng tập vàng (nghiệp vụ/QA) — điều kiện go-live.
- Rollout: reindex `segment-v1`→`segment-v2` + swap alias trước khi bật flag (local và stg); stg cần `EMBEDDING_API_URL` nội bộ, kiểm tra args `q.embed`.
- Qdrant local (`source_v2-qdrant-1`, volume `source_v2_qdrant_data`) chưa xoá — người dùng tự chạy lệnh xoá; stg chưa gỡ.
- Tài liệu cũ còn ghi `on_disk`: `docs/00-overview/hardware-resource-allocation.md` (~dòng 144), file SA.
- Checkbox DoD §11 / security §8 trong spec chưa tick.

### File cần đọc tiếp
- docs/04-qa/reports/search-semantic.md
- docs/00-overview/runbooks/search-semantic-opensearch-knn.md
- docs/03-dev/specs/search-semantic.md (§9 deploy, §11 DoD)

### Bước kế tiếp đề xuất
- Chốt N4/N6 với người dùng → `/dev-implement docs/03-dev/specs/search-semantic.md` (chỉ phần còn lại) → `/qa-review` lại.
- Rollout theo runbook, rồi T23 với tập vàng.
