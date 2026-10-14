## Handoff: backend-multi-instance

### Đã chốt
- SA `ARCH-backend-multi-instance` Approved; BA `STORY-backend-multi-instance` Approved (28 AC); DEV `SPEC-backend-multi-instance` **Approved** 2026-10-12 (19 task + bước 16a, 5/5 câu hỏi mở đã chốt, đã review và sửa 8 điểm).
- Q1/AC-17: phương án A — `SegmentIndexer` ghi `embed_text_hash` lên segment; writer dùng Painless, noop khi hash lệch. Hai bên dùng chung hàm hash và chung cấu hình chunk/overlap.
- Q2/AC-7: `lockAtMostFor = max(3×tick, chu kỳ)`; `search-index-reconciliation` dùng PT24H. Job dispatch và DLQ-alert dùng `lockAtLeastFor = chu kỳ − 5s` (PT55S, PT4M55S) vì `fixedDelay` lệch pha giữa các instance.
- Q3/AC-14: phiên cũ trỏ `/tmp` trả 409 `UPLOAD_SESSION_NOT_OPEN`. KHÔNG đánh dấu EXPIRED trong `appendChunk` (sẽ bị rollback); `UploadCleanupJob` dọn sau 24h.
- Q4: compose prod `/srv/data/config/aismartstore/docker-compose.prod.yml` nằm ngoài repo. Chỉ sửa `stg/docker-compose.yml`; snippet prod ghi vào runbook (Task 19).
- Q5: cảnh báo = log WARN/ERROR có key cố định + runbook, không thêm Prometheus.
- Env theo AC-2 map tường minh trong `application.yml`: `APP_SCHEDULING_ENABLED`, `SEMANTIC_CONSUMER_ENABLED`, `APP_CONSUMERS_ENABLED`.
- Redis: giữ timeout chung 2s; riêng limiter dùng timeout 300ms.
- Thứ tự task: 1 → 2 → 3 → 16a (thêm actuator/Micrometer, Task 5 cần) → 4..9 → 10..14 → 15..19.

### Câu hỏi mở / rủi ro
- KHÔNG sửa `RabbitConfig` queue args (lệch với stg → 406 crash-loop).
- `lockAtMostFor` trong §6.1 mới là giá trị tạm; Task 6 phải đo tick trên stg rồi điền số thật.
- R5b: đổi chunk/overlap/model embed → bắt buộc reindex lexical toàn bộ, nếu không mọi lần ghi vector đều noop.
- Advisory lock của poller giữ 1 kết nối Hikari suốt tick; Actuator có thể bị Spring Security chặn; worker là điểm đơn.
- IT chỉ chạy bằng `mvn verify`; IT flaky đã biết: TagsControllerIT, SearchScopeIT, SemanticIndexingIT.movingAFolder.
- Sau mỗi lần sửa code: rebuild docker service liên quan.

### File cần đọc tiếp
- docs/03-dev/specs/backend-multi-instance.md
- docs/02-ba/stories/backend-multi-instance.md (đối chiếu AC)

### Bước kế tiếp đề xuất
- /dev-implement docs/03-dev/specs/backend-multi-instance.md
- Sau đó: /qa-review docs/02-ba/stories/backend-multi-instance.md docs/03-dev/specs/backend-multi-instance.md
