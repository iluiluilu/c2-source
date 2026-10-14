# Runbook: worker OCR/STT/SED cập nhật trực tiếp `digitization_run` (cầu tạm)

> File: `docs/00-overview/runbooks/ocr-stt-worker-direct-update-cau-tam.md` — Owner: Ops/AI worker team
> Liên quan: `docs/01-sa/architecture/digitization-pipeline.md` — mục "Cập nhật
> Quyết định 9 (2026-09-22)"

## Bối cảnh

Worker `ocr`/`stt`/`sed` trên A100-Server hiện đã ghi đúng dữ liệu vào bảng
`content_segment` (đã xác nhận qua xem chi tiết run ở màn hình "Kiểm duyệt số
hoá"), nhưng **chưa báo hoàn tất** cho backend — nên tài liệu đã xử lý xong
vẫn hiển thị "Đang xử lý" thay vì "Chờ duyệt", và không bao giờ tự động xác
nhận sau 24h.

Đây là **giải pháp tạm thời**: worker tự `UPDATE` thêm vào `digitization_run`
ngay sau khi ghi xong `content_segment` cho 1 run, dùng tài khoản Postgres đã
được cấp sẵn (không phải role mới, không có migration nào đi kèm runbook
này). Đích cuối vẫn là worker publish message `q.result` qua RabbitMQ
(xem `docs/03-dev/specs/digitization-pipeline-02-ocr-stt-processing.md` §3) —
khi nào việc đó xong, câu `UPDATE` dưới đây **ngừng dùng**.

> **Áp dụng cả cho worker `video`** (từ 2026-09-24,
> `docs/03-dev/specs/digitization-pipeline-05-video-review.md`): segment ghi
> `content_source = 'video'`, có `time_start_ms/time_end_ms` như `stt`; bước
> set `digitization_source.duration_ms` bên dưới cũng áp dụng cho `video`.

## Việc cần làm: sau khi ghi xong `content_segment` cho 1 run

Chạy đúng 1 câu `UPDATE` sau, ngay sau khi đã `INSERT` xong toàn bộ
`content_segment` của run đó (không chạy trước, không chạy song song với lúc
đang insert segment):

```sql
UPDATE digitization_run
SET processing_status    = 'succeeded',
    completed_at         = now(),
    model_name            = :model_name,           -- vd 'paddleocr' / 'phowhisper'
    model_version          = :model_version,         -- vd '2.7.0'
    raw_output_path        = :raw_output_path,        -- vd 'derived/<documentId>/<runId>.json'
    metrics                = :metrics_json,           -- jsonb, vd '{"avgConfidence": 0.87}'
    input_quality_flag     = :input_quality_flag,      -- 'ok' | 'poor_scan' | 'low_audio_snr'
    segment_count           = :segment_count,           -- số dòng vừa insert vào content_segment cho run này
    low_confidence_count    = :low_confidence_count,     -- số segment có confidence < 0.60
    review_deadline_at      = now() + interval '24 hours',
    updated_at              = now()
WHERE id = :run_id
  AND processing_status IN ('queued', 'running');
```

Task `stt` và `video`, chạy thêm (chỉ khi `duration_ms` của nguồn đang `NULL` — không
ghi đè nếu đã có giá trị):

```sql
UPDATE digitization_source
SET duration_ms = :duration_ms
WHERE id = :source_id
  AND duration_ms IS NULL;
```

## Bắt buộc kiểm tra sau khi chạy

Câu `UPDATE` đầu tiên phải trả về **đúng 1 dòng bị ảnh hưởng**
(`UPDATE 1`). Nếu trả về **0 dòng** (`UPDATE 0`): nghĩa là run này đã được
đánh dấu hoàn tất bằng đường khác rồi (ví dụ đã chạy `UPDATE` này trước đó,
hoặc backend đã nhận được `q.result` cho đúng run này) — **không chạy lại**,
không có gì phải làm thêm, đây không phải lỗi. Chỉ log lại để theo dõi, không
retry.

## Tuyệt đối KHÔNG set 2 cột sau (để nguyên giá trị mặc định)

- **`output_quality_flag`** — để `NULL` (không đưa vào câu `UPDATE` ở trên).
  Lý do: hiện worker chưa có cách đo chất lượng OCR/STT đầu ra; nếu set sai
  giá trị sẽ ảnh hưởng badge "Kém"/"Tốt" hiển thị sai cho người duyệt.
- **`auto_confirm_blocked`** — để mặc định `false` (không đưa vào câu
  `UPDATE`). Hệ quả: mọi run qua cầu tạm này sẽ tự động xác nhận sau 24h dù
  chất lượng thế nào — đây là đánh đổi đã được chấp nhận cho giai đoạn tạm
  thời này, không phải thiếu sót.

## Giá trị `24h` lấy từ đâu, có đổi được không

Cố định `interval '24 hours'` ngay trong câu lệnh phía worker — **không**
đọc từ cấu hình hệ thống (`system_config.ocr_stt.auto_confirm_hours`, hiện
cũng đang là 24h, nhưng admin có thể đổi qua UI quản trị bất kỳ lúc nào).
Nếu admin đổi ngưỡng đó, **các run đi qua cầu tạm này sẽ không theo ngưỡng
mới** — đây là giới hạn đã biết, chấp nhận cho giai đoạn tạm thời. Nếu về
sau cần đổi số giờ cố định này, sửa trực tiếp câu lệnh trên (không có nơi
cấu hình tập trung nào khác cho giá trị này).

## Phạm vi quyền Postgres

Tài khoản Postgres dùng cho câu `UPDATE` trên **do người vận hành dự án cấp
trực tiếp** cho đội AI worker (không phải role mới tạo riêng qua migration).
Không cần xin thêm quyền gì khác ngoài quyền đã được cấp để chạy 2 câu lệnh
ở trên.

## Khi nào runbook này hết hiệu lực

Khi worker chuyển sang publish `q.result` qua RabbitMQ theo đúng hợp đồng ở
`docs/03-dev/specs/digitization-pipeline-02-ocr-stt-processing.md` §3 —
**ngừng chạy 2 câu `UPDATE` ở trên ngay khi đó**, để tránh 2 đường cùng cố
gắng hoàn tất 1 run song song (dù cơ chế `WHERE processing_status IN
('queued','running')` đã có sẵn chặn ghi đè/trùng segment ở mức run, chuyển
hẳn sang 1 đường duy nhất vẫn rõ ràng và an toàn hơn).
