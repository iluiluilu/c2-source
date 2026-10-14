# Tech Spec: digitization-pipeline-05-video-review

> File: `docs/03-dev/specs/digitization-pipeline-05-video-review.md` — Owner: DEV
> Nguồn: yêu cầu trực tiếp của người dùng 2026-09-24 (sửa nhanh, không qua
> SA/BA): "nghiệp vụ mới của `q.video` tương tự `ocr`/`stt`, AI worker tự xử
> lý trực tiếp với video; dữ liệu cũ bỏ qua".
> Thay thế một phần: `digitization-pipeline-03-video-queue.md` §0 điểm 3,
> §2.3, §4.4, §4.5; SA `digitization-pipeline.md` Quyết định 8 (ý "worker tự
> sở hữu vòng đời kết quả, không qua FR-18").
> Status: Draft — **đã implement + deploy stg 2026-09-24** (Flyway V32).

## 1. Tóm tắt kỹ thuật

- Lỗi gốc: run `task='video'` không hiện ở `/digitization` vì
  `ReviewQueueRepository`/`ReviewController` chỉ cho phép
  `ocr/stt/sed/video_summary`; trigger V31 còn map `succeeded → confirmed`
  (bỏ qua FR-18).
- Nay run `video` đi **cùng vòng đời với `stt`**: hoàn tất qua `q.result`
  (hoặc cầu tạm ghi thẳng DB như ocr/stt — runbook
  `ocr-stt-worker-direct-update-cau-tam.md`), vào hàng chờ Số hoá, duyệt/sửa
  segment + sửa mốc thời gian, tự xác nhận sau `ocr_stt.auto_confirm_hours`,
  index tìm kiếm khi confirmed.
- Worker video xử lý thẳng tệp gốc (Quyết định 10), không đổi message `q.video`.

## 2. Data model

`V32__digitization_video_review.sql`:

```sql
-- content_segment.content_source thêm 'video'
CHECK (content_source IN ('ocr','stt','sed','summary','video'))
-- bỏ trigger đồng bộ V31 §2.3
DROP TRIGGER IF EXISTS trg_sync_video_status ON digitization_run;
DROP FUNCTION IF EXISTS sync_video_status_to_document();
```

- `document_digitization_status.video_status`: nay do Java ghi
  (`upsertTaskStatus` có nhánh `task='video'`) và tham gia tính
  `overall_status` — giống mọi cột task khác.
- Role `digitization_video_worker` (V31 §2.4) giữ nguyên, không còn bắt buộc
  dùng; thu hồi sau khi worker chuyển hẳn sang `q.result`.

## 3. API contract

| Method | Endpoint | Thay đổi |
|---|---|---|
| GET | `/digitization-review/queue?task=video` | `task` nhận thêm `video`; `task` rỗng lấy cả 5 task |
| GET/PATCH/DELETE/POST | `/digitization-review/runs/{id}…`, `/segments/{id}` | Chấp nhận run `video`; PATCH `timeStartMs/timeEndMs` cho `stt` **và** `video` (cùng kiểm tra `duration_ms`) |
| GET | `/documents/{id}/video-status` | Không đổi shape; giá trị theo `activeRunTaskStatus` (`pending_review`/`in_review`/`confirmed`…) thay vì `succeeded→confirmed` |

Message `q.result` cho video: **y hệt `stt`** (spec 02 §3.4) — segment có
`timeStartMs/timeEndMs`, `durationMs` = độ dài video (ms), `confidence` nếu có.

## 4. Luồng xử lý chính

1. Dispatch không đổi: video gốc → `q.video` (spec 04).
2. Worker xong → `q.result` → `completeRunSuccess`: segment ghi
   `content_source='video'`; set `digitization_source.duration_ms` (như stt);
   cờ chất lượng dùng ngưỡng STT; `review_deadline_at`, `pending_review`.
3. Hàng chờ `/digitization` hiển thị run → cán bộ duyệt như STT.
4. Confirm → `review.confirmed` → index: segment `video` là `position_type=time`.

## 5. Test plan

- Unit: `DigitizationRunLifecycleServiceImplTest#completeRunSuccess_video_writesVideoSegmentsAndSourceDuration` (pass).
- IT: `DigitizationVideoTaskIT` sửa theo V32 (trigger không còn đồng bộ) —
  chưa chạy được do lỗi load ApplicationContext chung của toàn bộ IT (đã biết từ 2026-09-21).
- Stg: V32 applied, `trg_sync_video_status` đã gỡ, backend khởi động OK.

## 6. Task breakdown

- [x] Backend: review queue/controller, lifecycle, read-model, reconciliation, search time-position, V32.
- [x] **Frontend** (deploy stg 2026-09-24): `ReviewTask`/`SegmentListVariant`
      thêm `video`; `/digitization` thêm tab **"Video"** (tách khỏi tab "Tóm tắt
      video" = task `video_summary`); trang chi tiết run (duyệt + xem đã xác
      nhận) dùng `SegmentPlayer` (`<video>`) + danh sách như `stt`: tua theo
      đoạn, sửa mốc thời gian, thêm/xoá đoạn; ô text 8 dòng, giới hạn mềm 4000.
- [ ] AI worker: gửi kết quả video theo `q.result` (hoặc cầu tạm như ocr/stt, `content_source='video'`).
- [ ] `DigitizationDlqAlertJob`: cân nhắc thêm `q.video.dlq` (stg đang có 13 message).

## 7. Rủi ro kỹ thuật

- Dữ liệu cũ (người dùng chọn bỏ qua, không backfill): các run `video` `pending` trước V32 giờ
  **cũng hiện** trong hàng chờ; `video_status` cũ còn giá trị `confirmed` từ
  trigger → `DocumentDigitizationStatusReconciliationJob` sẽ log drift cho các tài liệu này.
- Stg có `content_source='video'` được thêm thủ công trước V32 — V32 dùng
  `DROP CONSTRAINT IF EXISTS` nên chạy được cả trên DB sạch lẫn stg.
