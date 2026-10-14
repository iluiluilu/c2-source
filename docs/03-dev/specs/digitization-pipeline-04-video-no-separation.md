# Tech Spec: digitization-pipeline-04-video-no-separation

> File: `docs/03-dev/specs/digitization-pipeline-04-video-no-separation.md` — Owner: DEV
> Nguồn: `docs/01-sa/architecture/digitization-pipeline.md` § Quyết định 10;
> story `docs/02-ba/stories/digitization-pipeline-01-source-dispatch.md`
> (Story 1 huỷ, Story 2 AC-1/AC-3/AC-5 cập nhật 2026-09-23)
> Status: Approved (người dùng duyệt 2026-09-23, chấp nhận các mặc định ở §8)

> Không cần migration DDL mới (repo hiện có `V1`–`V31`). Bảng
> `source_separation_job` giữ nguyên, chỉ ngừng ghi mới.

## 0. Phạm vi & giả định cần xác nhận

1. **Không `DROP` `source_separation_job`** trong đợt này (Quyết định 10):
   giữ dữ liệu lịch sử, `TrashPurgeService` vẫn xoá dòng liên quan khi purge
   — không đụng spec `delete-restore-trash-02`.
2. **Video cũ đang dở dang** lúc deploy (`source_separation_job.status IN
   ('queued','running','failed')`, chưa có `digitization_source`): sau khi bỏ
   điều kiện `NOT EXISTS source_separation_job` ở job đối soát (§4.3), các
   document này được **dispatch lại theo luồng mới** (1 message tệp gốc →
   `q.video`) một cách tự động. Kể cả job `failed` (tệp có thể hỏng) — worker
   video sẽ báo `failed_*` trên run của nó. Nếu không muốn tự động dispatch
   các video `failed` cũ, cần giữ lại điều kiện lọc riêng cho `status='failed'`
   — **người dùng chấp nhận mặc định (tự động dispatch cả `failed`),
   2026-09-23**.
3. Video đã tách thành công trước 2026-09-23 (đã có dòng `video`/`audio`):
   **không** xử lý lại, không xoá tệp `derived/<id>/video.<ext>`/`audio.wav`.
4. STT cho phần tiếng của video không còn được backend kích hoạt — câu hỏi
   mở ở SA §7, **không** thuộc phạm vi spec này.

## 1. Tóm tắt kỹ thuật

- `DigitizationIntakeService.handleIngestedDocument`: `video/*` đi chung
  nhánh `createOriginalSourceIfAbsent` với audio/ảnh/PDF — 1 dòng
  `digitization_source(component_type='original', id=document_id)` +
  `publish` ngay.
- `DigitizationDispatchService.resolveTask`: **không đổi** — `video/*` →
  task `video` → `q.video` (đã có từ spec 03).
- Gỡ `SourceSeparationJobRunner` (bỏ `@Scheduled` poll FFmpeg), gỡ phụ thuộc
  `SourceSeparationJobRepository` khỏi `DigitizationIntakeService`. Giữ entity
  `SourceSeparationJob` + repository (cần cho `TrashPurgeService`).
- `DocumentRepository.findMissedForDigitization`: bỏ điều kiện `NOT EXISTS
  source_separation_job` (§0 điểm 2).
- Bỏ cấu hình FFmpeg (`app.digitization.ffmpeg-path`, `ffmpeg-timeout-ms`,
  `job-poll-interval-ms`, `job-batch-size`) và `apk add ffmpeg` trong
  `backend/Dockerfile`.

## 2. Data model

Không đổi schema. Ngữ nghĩa sau thay đổi:

| Loại tệp | `digitization_source` | `source_separation_job` |
|---|---|---|
| video (mới) | 1 dòng `original`, `id=document_id`, `mime_type=video/*`, `file_path=<bucket>/<storage_key gốc>`, `dispatchable=true` | không tạo |
| video (trước 2026-09-23, đã tách) | 2 dòng `video`/`audio` (giữ nguyên) | `succeeded` (giữ nguyên) |
| audio/ảnh/PDF | không đổi | không tạo |

Comment ở V18 (`component_type='video' luôn dispatchable=false`) đã lỗi thời
từ spec 03; không sửa migration cũ (Flyway checksum).

## 3. API contract

Không đổi. `GET /documents/{id}/video-status` (spec 03) tiếp tục đọc
`document_digitization_status.video_status`, đồng bộ bởi trigger V31.

## 4. Luồng xử lý chính

### 4.1 Intake (sửa `DigitizationIntakeService`)

```java
if (VIDEO_MIME_TYPES.contains(mimeType) || isDirectlyDigitizable(mimeType)) {
    createOriginalSourceIfAbsent(documentId, mimeType);
}
```
— giữ `VIDEO_MIME_TYPES` (danh sách video của `MimeAllowlist`) để không nhận
`video/*` ngoài allowlist; xoá `createSeparationJobIfAbsent`.

### 4.2 Message gửi `q.video`

`DigitizationJobMessages.build(...)` không đổi. Ví dụ tệp mp4:

```json
{
  "runId": "<digitization_run.id, task='video'>",
  "fileId": "<document.id>",
  "sourceId": "<document.id>",
  "filePath": "<bucket>/<document.storage_key>",
  "originalFileType": "video/mp4",
  "processFileType": "video/mp4"
}
```
Exchange `ai.jobs`, routing key `${QUEUE_VIDEO_NAME:q.video}`, priority 5.
Không có message `q.stt` nào cho tài liệu video.

### 4.3 Job đối soát (sửa `DocumentRepository.findMissedForDigitization`)

Xoá dòng `AND NOT EXISTS (SELECT 1 FROM source_separation_job j WHERE
j.document_id = d.id)`. Điều kiện `NOT EXISTS digitization_source` vẫn đủ
chống dispatch trùng (unique `id=document_id`).

### 4.4 Worker `q.video` (ngoài repo — hợp đồng thay đổi)

- Đầu vào là **tệp gốc có cả audio track** (trước đây: video-only
  `-an -c:v copy` ở `derived/`), đọc từ đường dẫn tệp gốc thay vì
  `derived/<id>/video.<ext>`.
- Phần còn lại của hợp đồng (`UPDATE digitization_run`, lease ≤ 60s, không
  `q.result`) giữ nguyên spec 03 §4.4. **Phải báo đội worker trước khi
  deploy.**

## 5. Test plan

- Unit `DigitizationIntakeServiceTest`: `video/mp4` → tạo 1
  `digitization_source` `original` (id = documentId, mime `video/mp4`) +
  gọi `publish` đúng 1 lần; **không** gọi `SourceSeparationJobRepository`.
- Unit `DigitizationDispatchServiceTest`: source `original` `video/webm` →
  publish vào `q.video`, message có `sourceId == fileId`,
  `processFileType == originalFileType`.
- IT `DigitizationDispatchIT` / `DigitizationVideoTaskIT`: ingest 1 video →
  `q.video` nhận đúng 1 message, `q.stt` nhận 0; không có dòng
  `source_separation_job`; run `task='video'` ở `queued`.
- IT đối soát: document video có `source_separation_job(status='queued')`
  nhưng chưa có `digitization_source` → sau 1 lượt `DigitizationDispatchRetryJob`
  có 1 source `original` + 1 message `q.video`.
- Hồi quy: audio/ảnh/PDF không đổi; `TrashPurgeService` vẫn xoá
  `source_separation_job` của video cũ.
- Xoá `SourceSeparationJobRunnerTest`.

## 6. Task breakdown

- [ ] Task 1: `DigitizationIntakeService` — video đi nhánh `original` (§4.1),
      gỡ `SourceSeparationJobRepository` khỏi constructor.
- [ ] Task 2: Xoá `SourceSeparationJobRunner` + test; giữ
      `SourceSeparationJob`/`SourceSeparationJobRepository`, cập nhật javadoc
      thành "legacy, chỉ đọc/xoá". Sửa javadoc tham chiếu ở
      `DigitizationRunRepository`/`DocumentRepository` (`claimNextBatch`).
- [ ] Task 3: `DocumentRepository.findMissedForDigitization` (§4.3).
- [ ] Task 4: `DigitizationProperties` + `application.yml` — bỏ
      `ffmpeg-path`, `ffmpeg-timeout-ms`, `job-poll-interval-ms`,
      `job-batch-size`; bỏ biến env tương ứng ở `stg/`/compose nếu có.
- [ ] Task 5: `backend/Dockerfile` — bỏ `apk add --no-cache ffmpeg`.
- [ ] Task 6: Test theo §5.
- [ ] Task 7 (phối hợp ngoài repo): báo đội `video-worker` về đầu vào mới
      (§4.4) trước khi deploy.

## 7. Rủi ro kỹ thuật

- **Mất STT cho video** (SA Quyết định 10 §6) — thay đổi chức năng có chủ
  đích, cần nghiệp vụ chốt câu hỏi mở SA §7 trước nghiệm thu FR-19.
- **Worker video chưa sẵn sàng với tệp có audio / đường dẫn gốc** → run
  `failed`. Giảm thiểu: Task 7, test 1 message trên stg trước.
- **Tự động dispatch video cũ dở dang** khi deploy (§0 điểm 2) — số lượng
  có thể lớn trên stg; job đối soát giới hạn
  `dispatch-retry-batch-size` (100)/lượt nên không dồn cùng lúc.
- Queue `q.video` trên stg phải tồn tại đúng args như `RabbitConfig`
  (tránh lỗi 406 như `q.embed`) — đã có từ spec 03, không đổi.

## 8. Điểm cần người dùng xác nhận

- [x] §0 điểm 2: tự động dispatch lại video cũ có `source_separation_job`
      `failed` → **có** (chấp nhận mặc định, 2026-09-23).
- [x] Giữ bảng `source_separation_job`, không `DROP` trong đợt này
      (chấp nhận mặc định, 2026-09-23).
