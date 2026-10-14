# Việc AI worker (OCR/STT/SED) cần làm thêm (ngoài lưu segment — phần đó đã xong)

> Chi tiết đầy đủ + lý do: `docs/00-overview/runbooks/ocr-stt-worker-direct-update-cau-tam.md`

## Thời điểm thực hiện

Ngay sau khi đã `INSERT` xong **toàn bộ** `content_segment` của 1 run — chạy
1 lần, không chạy trước, không chạy song song lúc đang insert segment.

## SQL cần chạy — bắt buộc, mọi task (ocr/stt/sed)

```sql
UPDATE digitization_run
SET processing_status    = 'succeeded',
    completed_at         = now(),
    model_name            = :model_name,
    model_version          = :model_version,
    raw_output_path        = :raw_output_path,
    metrics                = :metrics_json,
    input_quality_flag     = :input_quality_flag,
    segment_count           = :segment_count,
    low_confidence_count    = :low_confidence_count,
    review_deadline_at      = now() + interval '24 hours',
    updated_at              = now()
WHERE id = :run_id
  AND processing_status IN ('queued', 'running');
```

| Tham số | Giá trị |
|---|---|
| `:run_id` | id của `digitization_run` đang xử lý (worker đã nhận từ message `q.ocr`/`q.stt` lúc nhận job) |
| `:model_name` | tên model worker đang chạy, vd `'paddleocr'`, `'phowhisper'` |
| `:model_version` | version model, vd `'2.7.0'` |
| `:raw_output_path` | đường dẫn file JSON thô đã ghi lên SeaweedFS, dạng `derived/<documentId>/<runId>.json` |
| `:metrics_json` | jsonb tuỳ chọn, vd `'{"avgConfidence": 0.87}'` — có thể để `'{}'` nếu chưa có |
| `:input_quality_flag` | 1 trong `'ok'`, `'poor_scan'` (ocr), `'low_audio_snr'` (stt) |
| `:segment_count` | đếm số dòng vừa insert vào `content_segment` cho đúng `run_id` này |
| `:low_confidence_count` | đếm số segment trong đó có `confidence < 0.60` |

**Không đưa 2 cột sau vào câu `UPDATE`** (để nguyên mặc định `NULL`/`false`):
`output_quality_flag`, `auto_confirm_blocked` — worker chưa có cách đo chất
lượng, không tự set.

## SQL cần chạy — chỉ khi task = `stt`

```sql
UPDATE digitization_source
SET duration_ms = :duration_ms
WHERE id = :source_id
  AND duration_ms IS NULL;
```

| Tham số | Giá trị |
|---|---|
| `:source_id` | `sourceId` của run (worker đã nhận từ message `q.stt`) |
| `:duration_ms` | độ dài audio gốc, tính bằng mili-giây |

## Kiểm tra sau khi chạy

Câu `UPDATE digitization_run` phải báo **`UPDATE 1`**. Nếu báo `UPDATE 0` —
run đã được đánh dấu hoàn tất từ trước rồi, **không chạy lại**, không phải
lỗi, chỉ log lại để theo dõi.
