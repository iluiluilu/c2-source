# Tech Spec: digitization-pipeline-02-ocr-stt-processing

> File: `docs/03-dev/specs/digitization-pipeline-02-ocr-stt-processing.md` — Owner: DEV
> Nguồn story: `docs/02-ba/stories/digitization-pipeline-02-ocr-stt-processing.md`
> Status: Approved

> ⚠️ **Phụ thuộc chưa Approved, viết song song theo yêu cầu deadline (review
> sáng mai), KHÔNG chặn lại chờ duyệt:**
> - Story nguồn (`digitization-pipeline-02-ocr-stt-processing.md`) — **Status:
>   Draft**, nội dung được xác nhận là đủ để viết spec.
> - `digitization-results-store.md` (spec) — **Status: Draft**, chưa có
>   migration `V25`/`V26` thật trong repo. Spec này **giả định V25/V26 được
>   implement đúng như bản Draft đó** (bảng/cột `digitization_run`,
>   `content_segment`, `sound_event`, `document_digitization_status`) và xây
>   `V27` tiếp nối. **4 điểm mục 7 của `digitization-results-store.md` đã
>   được người dùng chốt 2026-09-07** (giữ 1 bảng hợp nhất, SED/video_summary
>   bắt buộc qua FR-18, enum `content_source` đã đồng bộ `ai-search-infra.md`,
>   ngưỡng SED để ở config) — không ảnh hưởng gì tới `V27`/code ở spec này
>   (spec này chỉ xử lý `task ∈ {ocr, stt}`, không đụng SED/video_summary).
> - `digitization-pipeline.md` (SA) — **Status: Approved**, dùng làm nguồn
>   quyết định kiến trúc chính, không đổi.

## 0. Quyết định của DEV (SA chưa cụ thể hoá) — cần xác nhận khi duyệt

SA (`digitization-pipeline.md` Quyết định 2/4) mới nói "worker tiêu thụ
`q.ocr`/`q.stt`" và "`digitization-service` tiêu thụ `q.result`", chưa chốt cơ
chế giao tiếp cụ thể. Spec này quyết định:

1. **Hợp đồng `q.ocr`/`q.stt` được mở rộng thêm 1 trường `runId`** so với 5
   trường đã chốt ở Pha 1 — bắt buộc phải sửa nhỏ
   `DigitizationDispatchService.publish(...)` (đã implement, Approved ở Pha 1)
   để **tạo `digitization_run` (`processing_status='queued'`) trước khi
   publish**, rồi đưa `run.id` vào message. Lý do: cần một khoá ổn định
   (`run_id`) xuyên suốt vòng đời job — nếu để worker/consumer tự suy ra
   `run_id` từ `(document_id, task)` sẽ phải tra cứu ngược, dễ lệch khi có
   run cũ `superseded` còn tồn tại.
2. **Heartbeat & lease qua RabbitMQ, không thêm REST API nội bộ**: worker
   publish message nhẹ vào `q.heartbeat` mỗi 30s (tái dùng kết nối AMQP sẵn
   có của worker, không cần thêm HTTP client/xác thực nội bộ mới trong Python
   worker). Backend `DigitizationHeartbeatConsumer` cập nhật
   `lease_expires_at`/`worker_id`/chuyển `queued → running`.
3. **Kết quả cuối cùng (thành công/lỗi) qua `q.result`**, đúng như SA đã nêu ở
   tech stack Pha 2. JSON thô đầy đủ **không** đi qua RabbitMQ (giới hạn kích
   thước message) — worker tự ghi `derived/<document_id>/<run_id>.json` lên
   SeaweedFS (worker vốn đã có quyền ghi `derived/` theo NFR-09), message
   `q.result` chỉ mang `rawOutputPath` + segment đã chuẩn hoá.
4. **Ai tính cờ chất lượng đầu vào (câu hỏi mở của story):** worker Python
   tính `input_quality_flag` (cần đọc trực tiếp tệp ảnh/audio, đặt ở worker
   rẻ hơn tải lại tệp ở backend) và gửi kèm trong `q.result`. **Cờ chất
   lượng đầu ra** (`output_quality_flag`, `low_confidence_count`,
   `auto_confirm_blocked`) tính ở **backend**, vì phụ thuộc ngưỡng cấu hình
   (`system-config`) — đổi ngưỡng chỉ tính lại ở backend, không cần sửa/deploy
   lại worker (đúng AC-6 Story 4).
5. **Idempotency & dedupe (Story 6)** thực hiện bằng **conditional UPDATE**
   (`WHERE processing_status IN ('queued','running')`) khi ghi nhận kết quả —
   không dùng lock/khoá phân tán riêng; ai ghi trước thắng, message tới sau
   khi run đã ở trạng thái cuối bị bỏ qua (log + ack, không lỗi).
6. **Ưu tiên interactive/bulk (Story 3 AC-6/AC-7):** dùng thuộc tính `priority`
   chuẩn của AMQP (`x-max-priority`) trên `q.ocr`/`q.stt`, set khi publish dựa
   trên việc dispatch có gắn `batch_id` (`digitization_batch`, xem
   `digitization-results-store.md` §2.1) hay không. **Nguồn của `batch_id`
   (luồng nạp thư mục/hàng loạt nào tạo `digitization_batch` và truyền xuống
   `DigitizationDispatchService.publish`) thuộc `data-ingestion.md`, ngoài
   phạm vi spec này** — tạm thời publish mọi dispatch hiện có (Pha 1 chưa
   phân biệt interactive/bulk) với `priority=5` (interactive) cho tới khi
   `data-ingestion` nối cờ bulk; xem rủi ro mục 7.
7. **Retry/backoff tái dùng đúng hạ tầng `.retry`/`.dlq` đã khai báo ở Pha 1**
   (`RabbitConfig`, chưa có logic tiêu thụ) — spec này bổ sung logic đọc.

## 1. Tóm tắt kỹ thuật

- `ocr-worker`/`stt-worker` (Python, Docker riêng, đã mô tả tech stack ở
  `digitization-pipeline.md` mục 5) tiêu thụ `q.ocr`/`q.stt` (6 trường, mục 0
  điểm 1), auto-ack ngay khi nhận (Postgres là nguồn sự thật, không dựa vào
  ack RabbitMQ để biết "đang chạy" — đúng tinh thần Quyết định 2 SA).
- Worker chạy: input-quality-check nhanh → publish heartbeat đầu (backend
  chuyển `queued → running`) → tiền xử lý (deskew/VAD) → OCR (PaddleOCR+
  VietOCR) hoặc STT (PhoWhisper/faster-whisper) → phân đoạn theo Quyết định 3
  → ghi JSON thô lên `derived/` → publish `q.result`.
- `digitization-service` (Spring Boot) có 3 consumer mới:
  `DigitizationHeartbeatConsumer` (`q.heartbeat`), `DigitizationResultConsumer`
  (`q.result`), và 2 `@Scheduled` job: `DigitizationRunLeaseReconciliationJob`
  (phát hiện job treo, mục 4.4) và `DigitizationDlqAlertJob` (Story 3 AC-5).
- Ghi kết quả dùng lại `DigitizationRunLifecycleService` đã thiết kế ở
  `digitization-results-store.md` §3, **bổ sung 2 method mới**
  (`completeRunSuccess`, `completeRunFailure` — mục 3) vì service gốc chưa có
  method nào gộp đủ bước "chuyển trạng thái + set `review_deadline_at` + ghi
  segment + tính cờ chất lượng" trong 1 transaction.
- `digitization.completed` (Quyết định 4 SA) phát qua **`outbox_event` đã có
  sẵn** (`V19__init_outbox_event.sql`, entity `OutboxEvent` — trả lời câu hỏi
  mở "outbox cho `digitization.completed` đã có ở Pha 1 hay chưa": **đã có ở
  tầng hạ tầng chung từ trước**, không phải do Pha 1 tạo riêng, chỉ cần ghi
  thêm 1 dòng `event_type='digitization.completed'` cùng transaction hoàn tất
  run) — không cần bảng outbox mới, không cần quyết định "outbox đầy đủ hay
  publish trực tiếp" như BA nêu ở câu hỏi mở.

## 2. Data model

**Không tạo lại DDL của `digitization_run`/`content_segment`/`sound_event`/
`document_digitization_status`** — dùng nguyên trạng `V25`/`V26` đã chốt ở
`digitization-results-store.md` §2. Spec này chỉ thêm **1 migration bổ sung**:

### `V27__digitization_source_duration_and_time_check.sql`

```sql
-- Chạy sau V26 (digitization-results-store.md). Bổ sung cho Story 3 (AC-4 của
-- digitization-pipeline-02, "chặn mốc thời gian vượt độ dài tệp gốc ở tầng
-- DB, không chỉ ứng dụng") - digitization-results-store.md §4 đã nêu rõ đây
-- là việc "ngoài phạm vi" của spec đó, để lại cho spec dùng dữ liệu này.

-- digitization_source (V18, Pha 1) chưa có duration_ms - cần cho STT/audio.
ALTER TABLE digitization_source ADD COLUMN duration_ms INT;
-- Chỉ audio (component_type IN ('audio','original') với mime_type audio/*)
-- mới có giá trị; ảnh/PDF để NULL. Worker gửi giá trị này trong q.result
-- (mục 3), backend ghi vào digitization_source khi hoàn tất run STT.

-- Hàm + trigger: mọi INSERT/UPDATE content_segment có time_end_ms phải thoả
-- time_end_ms <= duration_ms của digitization_source tương ứng (qua
-- digitization_run.source_id), nếu duration_ms đã biết (NOT NULL). Áp dụng
-- cho cả cột current_time_end_ms (sửa bởi cán bộ ở digitization-review) để
-- không lọt qua đường sửa tay.
CREATE FUNCTION fn_check_segment_within_source_duration()
RETURNS TRIGGER AS $$
DECLARE
    v_duration_ms INT;
BEGIN
    IF NEW.time_end_ms IS NULL AND NEW.current_time_end_ms IS NULL THEN
        RETURN NEW;
    END IF;

    SELECT ds.duration_ms INTO v_duration_ms
    FROM digitization_run dr
    JOIN digitization_source ds ON ds.id = dr.source_id
    WHERE dr.id = NEW.run_id;

    IF v_duration_ms IS NOT NULL THEN
        IF NEW.time_end_ms IS NOT NULL AND NEW.time_end_ms > v_duration_ms THEN
            RAISE EXCEPTION
                'content_segment.time_end_ms (%) vuot qua digitization_source.duration_ms (%) cho run %',
                NEW.time_end_ms, v_duration_ms, NEW.run_id;
        END IF;
        IF NEW.current_time_end_ms IS NOT NULL AND NEW.current_time_end_ms > v_duration_ms THEN
            RAISE EXCEPTION
                'content_segment.current_time_end_ms (%) vuot qua digitization_source.duration_ms (%) cho run %',
                NEW.current_time_end_ms, v_duration_ms, NEW.run_id;
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_content_segment_within_duration
    BEFORE INSERT OR UPDATE ON content_segment
    FOR EACH ROW EXECUTE FUNCTION fn_check_segment_within_source_duration();
```

Ghi chú: trigger chấp nhận `duration_ms IS NULL` (bỏ qua kiểm tra) — trường
hợp OCR (không có mốc thời gian) hoặc run STT cũ trước khi cột này tồn tại.
Không hồi tố dữ liệu cũ (không có dữ liệu cũ ở thời điểm viết spec).

## 3. API contract

Không có REST endpoint public mới. 3 message contract nội bộ qua RabbitMQ
(exchange `ai.jobs`, hạ tầng khai báo ở Pha 1 `RabbitConfig`, mở rộng thêm 2
queue mới `q.heartbeat`/`q.result` + `x-max-priority` cho `q.ocr`/`q.stt`):

### 3.1 `q.ocr` / `q.stt` (mở rộng từ Pha 1, +1 trường)

```json
{
  "runId": "uuid",              // MỚI — id digitization_run, processing_status='queued' tại lúc publish
  "fileId": "uuid",
  "sourceId": "uuid",
  "filePath": "string",
  "originalFileType": "string",
  "processFileType": "string"
}
```

AMQP property `priority` (0–9, queue khai báo `x-max-priority=9`): `5` mặc
định (interactive), `1` khi dispatch gắn `batch_id` (bulk — xem mục 0 điểm 6).

### 3.2 `q.heartbeat` (mới, fire-and-forget, không cần ack/retry)

```json
{
  "runId": "uuid",
  "workerId": "string",   // hostname:pid hoặc uuid instance worker
  "task": "ocr | stt",
  "timestamp": "2026-09-05T10:00:00Z"
}
```

### 3.3 `q.result` (mới) — thành công

```json
{
  "runId": "uuid",
  "documentId": "uuid",
  "sourceId": "uuid",
  "task": "ocr | stt",
  "status": "succeeded",
  "modelName": "string",
  "modelVersion": "string",
  "workerId": "string",
  "startedAt": "ISO-8601",
  "completedAt": "ISO-8601",
  "durationMs": 125300,            // độ dài audio gốc (ms); null nếu task=ocr
  "inputQualityFlag": "ok | poor_scan | low_audio_snr",
  "rawOutputPath": "derived/<documentId>/<runId>.json",
  "metrics": { "avgConfidence": 0.87, "pageCount": 12 },
  "segments": [
    {
      "segmentIndex": 0,
      "text": "string",
      "confidence": 0.91,
      "pageNo": 1, "bboxPolygon": [[0.08,0.10],[0.55,0.10],[0.55,0.14],[0.08,0.14]],
      "readingOrder": 0, "lineFrom": 1, "lineTo": 3,
      "timeStartMs": null, "timeEndMs": null, "speakerLabel": null,
      "noSpeechProb": null
    }
  ]
}
```

`pageNo/bboxPolygon/readingOrder/lineFrom/lineTo` chỉ có khi `task=ocr`;
`timeStartMs/timeEndMs/speakerLabel/noSpeechProb` chỉ có khi `task=stt`
(`noSpeechProb` dùng để backend tính nghi ngờ "ảo giác" — Story 4 AC-3, ngưỡng
`ocr_stt.no_speech_prob_threshold`).

`bboxPolygon` **là tỉ lệ 0–1 trên trang** (`x`/`y` ∈ [0,1], gốc góc trên-trái),
**không phải pixel tuyệt đối** (chốt 2026-09-07, đồng bộ với QĐ 3 SA và
`search-fulltext-03-dinh-vi-ket-qua.md` mục 2) — worker OCR phải tự chia toạ
độ pixel thô cho `page_width`/`page_height` của ảnh trang trước khi trả JSON
này; không cần cột `page_width`/`page_height` ở Postgres vì FE chỉ dùng tỉ lệ.

### 3.4 `q.result` (mới) — thất bại

```json
{
  "runId": "uuid",
  "documentId": "uuid",
  "sourceId": "uuid",
  "task": "ocr | stt",
  "status": "failed_retryable | failed_permanent",
  "errorCode": "OOM_GPU | TIMEOUT | IO_ERROR | CORRUPT_FILE | UNSUPPORTED_CODEC | UNKNOWN",
  "errorMessage": "string",
  "workerId": "string",
  "inputQualityFlag": "ok | poor_scan | low_audio_snr"
}
```

Phân loại `errorCode → status` do **worker** quyết định (worker biết rõ nhất
nguyên nhân lỗi: OOM/timeout/IO → `failed_retryable`; corrupt/unsupported
codec → `failed_permanent`), đúng phân loại tạm thời/vĩnh viễn của Quyết định
2 SA.

### 3.5 Bổ sung `DigitizationRunLifecycleService` (đã có sườn ở
`digitization-results-store.md` §3)

```java
public interface DigitizationRunLifecycleService {
    // ... (createRun, transitionProcessingStatus, transitionReviewState,
    //      writeSegments, reviseSegment, writeSoundEvents — đã định nghĩa)

    // MỚI: 1 transaction — transitionProcessingStatus(succeeded) + set
    // completed_at/model_name/model_version/raw_output_path/metrics +
    // set digitization_source.duration_ms (nếu task=stt) + writeSegments +
    // tính output_quality_flag/low_confidence_count/auto_confirm_blocked
    // (đọc ngưỡng từ system-config) + set review_deadline_at = completed_at
    // + ocr_stt.auto_confirm_hours + ghi outbox_event('digitization.completed').
    // Bỏ qua (no-op, trả về run hiện tại) nếu processing_status không còn
    // 'queued'/'running' (dedupe — Story 6 AC-1/AC-2).
    DigitizationRun completeRunSuccess(UUID runId, RunResultPayload payload);

    // MỚI: 1 transaction — tăng attempt_count, quyết định requeue (retryable,
    // còn lượt) / failed_permanent (hết lượt hoặc lỗi vĩnh viễn) + set
    // last_error. Bỏ qua nếu đã ở trạng thái cuối (dedupe).
    DigitizationRun completeRunFailure(UUID runId, String status,
                                        String errorCode, String errorMessage);
}
```

## 4. Luồng xử lý chính

### 4.1 Dispatch (sửa Pha 1) — tạo run trước khi publish

1. `DigitizationDispatchService.publish(source)` (đã có, Pha 1) gọi thêm
   `digitizationRunLifecycleService.createRun(documentId, sourceId, task,
   batchId, null)` **trước** khi build message — nếu `createRun` ném
   `IllegalStateException` (đã có run active cùng `(document_id, task)`, ví
   dụ dispatch bị gọi lại bởi job đối soát Pha 1 mục 4.5 sau khi run đã tồn
   tại), **bỏ qua tạo run mới**, tra `findActiveByDocumentAndTask` để lấy lại
   `runId` hiện có rồi publish tiếp (idempotent, không phá vỡ job đối soát
   Pha 1 đã có).
2. Set `priority` AMQP theo `batchId != null` (mục 0 điểm 6), publish message
   6 trường (mục 3.1) vào `q.ocr`/`q.stt`.

### 4.2 Worker chạy job

1. Nhận message, ack ngay (auto-ack).
2. Input-quality-check (đọc tệp qua `filePath`) → giữ tạm trong bộ nhớ worker,
   gửi kèm ở bước cuối, **không** cần một message riêng cho bước này.
3. Publish `q.heartbeat` lần đầu ngay khi bắt đầu xử lý thật (sau input
   check) → backend chuyển `queued → running`.
4. Tiếp tục publish `q.heartbeat` mỗi 30s cho tới khi xong (dùng timer song
   song, không chặn luồng xử lý chính).
5. Chạy OCR/STT + phân đoạn (Quyết định 1/3 SA) → build danh sách segment.
6. Ghi JSON thô đầy đủ lên `derived/<documentId>/<runId>.json`.
7. Publish `q.result` (thành công — mục 3.3, hoặc thất bại — mục 3.4).

### 4.3 Backend tiêu thụ heartbeat

`DigitizationHeartbeatConsumer` (`@RabbitListener(queues = "q.heartbeat")`):

```
UPDATE digitization_run
SET processing_status = 'running', worker_id = :workerId,
    lease_expires_at = now() + interval '90 seconds'
WHERE id = :runId AND processing_status IN ('queued', 'running')
```

90s = 3× chu kỳ heartbeat (30s) — dung sai đủ cho 1–2 lần mất gói mà không bị
coi là treo oan. Không cập nhật `document_digitization_status` ở bước này
(giữ nguyên `'processing'` đã set khi `createRun`/dispatch — tránh ghi DB dư
thừa mỗi 30s cho một bảng đọc nhiều).

### 4.4 Phát hiện job treo (Story 3 AC-4)

`DigitizationRunLeaseReconciliationJob`
(`@Scheduled(fixedDelay = ${app.digitization.lease-check-interval-ms:60000})`):

```
SELECT * FROM digitization_run
WHERE processing_status = 'running' AND lease_expires_at < now()
FOR UPDATE SKIP LOCKED
```

Với mỗi dòng: `UPDATE ... SET processing_status='queued', worker_id=NULL,
lease_expires_at=NULL` (không tăng `attempt_count` — AC-4 không coi đây là một
lần thử lỗi, chỉ là job được giao lại), rồi dựng lại message 6 trường (đọc từ
`digitization_run` + `digitization_source` + `document`) và publish lại vào
đúng `q.ocr`/`q.stt` theo `task`. Với chu kỳ quét 60s + lease 90s, phát hiện
tối đa trong ~150s — vượt xa yêu cầu `[ĐX]` ≤ 15 phút của SA (mục 3 NFR), an
toàn hơn ngưỡng SA đề xuất.

### 4.5 Backend tiêu thụ kết quả (Story 5, Story 6)

`DigitizationResultConsumer` (`@RabbitListener(queues = "q.result")`):

1. Parse message, xác định `status`.
2. `status='succeeded'` → gọi `completeRunSuccess(runId, payload)`:
   - Conditional update `processing_status` (mục 0 điểm 5) — nếu 0 dòng bị
     ảnh hưởng (run đã ở trạng thái cuối), log `WARN` "duplicate/late result
     for run {runId}, ignoring" và **return sớm** (Story 6 AC-1/AC-2).
   - Nếu cập nhật thành công: set `completed_at=now()`, `model_name`,
     `model_version`, `raw_output_path`, `metrics`; nếu `task=stt` và
     `durationMs != null`, `UPDATE digitization_source SET duration_ms =
     :durationMs WHERE id = source_id` (lần đầu set; V27 trigger dùng giá trị
     này để chặn segment vượt độ dài — mục 2).
   - `writeSegments(runId, segments)` — mỗi segment: `ai_text=text`,
     `current_text=text`, `ai_time_*=current_time_*=timeStart/EndMs`.
   - Tính `low_confidence_count` = số segment có `confidence <
     ocr_stt.min_confidence_ocr` (task=ocr) hoặc `< ocr_stt.min_confidence_stt`
     (task=stt); tính tỷ lệ `low_confidence_count / segment_count`; nếu vượt
     `ocr_stt.poor_ratio_threshold` → `output_quality_flag='poor'`,
     `auto_confirm_blocked=true` (Story 4 AC-2/AC-4/AC-5, Quyết định 5 SA);
     ngược lại `output_quality_flag='ok'`.
   - Segment `task=stt` có `noSpeechProb > ocr_stt.no_speech_prob_threshold`
     (0.8) → **giữ lại**, không loại khỏi `writeSegments`, chỉ tính vào
     `low_confidence_count` như segment thấp thông thường (Story 4 AC-3 — nghi
     "ảo giác" vẫn giữ, gắn cờ qua đường `output_quality_flag`/tỷ lệ thấp
     chung, không cần cột riêng).
   - `review_deadline_at = completed_at + ocr_stt.auto_confirm_hours`.
   - Upsert `document_digitization_status` (task tương ứng →
     `pending_review`, tính lại `overall_status` — theo đúng cơ chế đã định ở
     `digitization-results-store.md` §4 bước 2/5).
   - `INSERT outbox_event(event_type='digitization.completed', payload={
     documentId, runId, task, segmentCount, lowConfidenceCount, qualityFlag:
     output_quality_flag, autoConfirmBlocked, reviewDeadlineAt})` — đúng 8
     trường Quyết định 4 SA (Story 5 AC-2).
3. `status IN ('failed_retryable','failed_permanent')` → gọi
   `completeRunFailure(runId, status, errorCode, errorMessage)`:
   - Conditional update tương tự (dedupe).
   - `status='failed_permanent'` → set thẳng `processing_status=
     'failed_permanent'`, `last_error`; publish 1 message vào
     `<queue>.dlq` tương ứng (để quan sát tập trung qua hàng đợi, đúng hạ
     tầng `.dlq` đã khai báo Pha 1) + đánh dấu cần cảnh báo (mục 4.6).
   - `status='failed_retryable'` → `attempt_count += 1`;
     - nếu `attempt_count <= ocr_stt.max_retry` (3): set
       `processing_status='queued'`, publish lại job vào `<queue>.retry`
       (TTL backoff theo lần thử: 1 → 1 phút, 2 → 5 phút, 3 → 30 phút — dead-
       letter về lại `q.ocr`/`q.stt` sau TTL, đúng khung Pha 1);
     - nếu vượt quá (`attempt_count > 3`): set
       `processing_status='failed_permanent'` (mượn state cuối vì DB không
       có trạng thái riêng "hết lượt retry" — `last_error` ghi rõ "exhausted
       3 retries, last error: ..."), publish vào `.dlq` + cần cảnh báo, đúng
       Story 3 AC-2.

### 4.6 Cảnh báo DLQ (Story 3 AC-5)

`DigitizationDlqAlertJob` (`@Scheduled(fixedDelay =
${app.digitization.dlq-alert-interval-ms:300000})`): passive-declare 4 queue
`q.ocr.dlq`/`q.stt.dlq` (dùng `RabbitAdmin.getQueueProperties`, không cần
quản lý bảng riêng), nếu `messageCount > 0` → log `ERROR` có cấu trúc (dễ bắt
bởi alerting stack hiện có, NFR-13) kèm số lượng; **không** tự động replay —
việc replay DLQ là thao tác vận hành thủ công, ngoài phạm vi spec.

## 5. Test plan

**Unit test:**

- `DigitizationDispatchServiceTest` (mở rộng): `publish` tạo `digitization_run`
  trước, message có `runId` đúng giá trị vừa tạo; gọi `publish` lần 2 cho
  cùng `(document_id, task)` khi run cũ vẫn active → không tạo run mới,
  message dùng lại `runId` cũ.
- `DigitizationResultConsumerTest`: message `succeeded` hợp lệ →
  `completeRunSuccess` được gọi đúng tham số; message có `runId` không tồn
  tại/đã `succeeded` trước đó → không ghi thêm segment nào (mock repository
  verify `writeSegments` không được gọi).
- `DigitizationRunLifecycleServiceImplTest.completeRunSuccess`: tính đúng
  `output_quality_flag='poor'` khi tỷ lệ segment thấp > ngưỡng cấu hình (test
  tham số hoá theo nhiều tỷ lệ biên); `auto_confirm_blocked` khớp
  `output_quality_flag`; `review_deadline_at = completed_at +
  auto_confirm_hours` chính xác tới giây.
- `completeRunFailure`: `attempt_count` tăng đúng; `attempt_count=3` +
  1 lỗi retryable nữa → chuyển `failed_permanent`; lỗi `failed_permanent`
  ngay từ đầu → không tăng `attempt_count` theo hướng "tốn lượt thử" (AC-3 —
  vẫn có thể tăng counter để log nhưng không dùng nó để quyết định retry).
- `DigitizationRunLeaseReconciliationJobTest`: run `running` với
  `lease_expires_at` quá hạn → chuyển `queued`, publish lại đúng
  `q.ocr`/`q.stt` theo `task`; run `running` còn hạn → không đụng tới.
- Backoff TTL: chọn đúng queue `.retry` theo `attempt_count` (1/5/30 phút).

**Migration/constraint test (Testcontainers Postgres, chạy trên nền `V1..V26`
theo `digitization-results-store.md`):**

- `V27` chạy sạch sau `V26`.
- INSERT `content_segment` với `time_end_ms > digitization_source.duration_ms`
  (đã set) → bị trigger từ chối; `time_end_ms <= duration_ms` → thành công;
  `duration_ms IS NULL` (OCR hoặc STT chưa set) → không bị chặn bởi trigger
  này (constraint `CK_content_segment_ai_time`/`current_time` của `V26` vẫn
  áp dụng độc lập).
- UPDATE `current_time_end_ms` (mô phỏng cán bộ sửa ở review) vượt
  `duration_ms` → bị trigger từ chối.

**Integration test (Testcontainers Postgres + RabbitMQ, end-to-end):**

- Dispatch 1 tệp ảnh → `digitization_run` tạo `queued` → giả lập worker gửi
  1 heartbeat rồi 1 `q.result` thành công với 3 segment → xác nhận
  `processing_status='succeeded'`, `review_state='pending'`,
  `review_deadline_at` đúng, 3 dòng `content_segment`, 1 dòng
  `outbox_event(event_type='digitization.completed')` đúng 8 trường payload.
- Gửi lại **đúng `q.result` thành công đó lần 2** (mô phỏng redelivery) →
  không có thêm segment nào được tạo, `segment_count` không đổi (Story 6
  AC-1).
- Giả lập 2 message `q.result` thành công cùng `runId` gửi gần như đồng thời
  (2 thread) → chỉ 1 lần `writeSegments` thực sự chạy, không có race tạo
  segment đôi (Story 6 AC-2) — assert bằng đếm dòng `content_segment`.
- Không gửi heartbeat/`q.result` gì trong 3 phút sau dispatch → chạy
  `DigitizationRunLeaseReconciliationJob` thủ công trong test → run trở về
  `queued`, có 1 message mới trong `q.ocr`/`q.stt` (Story 3 AC-4).
- `q.result` thất bại `failed_retryable` 3 lần liên tiếp (cùng `runId`, mỗi
  lần backend tự chuyển lại `queued` rồi test gửi `failed_retryable` tiếp) →
  lần thứ 4 (`attempt_count=4`) → `processing_status='failed_permanent'`, có
  message trong `q.ocr.dlq`/`q.stt.dlq`.
- `q.result` thất bại `failed_permanent` ngay lần đầu → chuyển thẳng, không
  qua `.retry`.
- Tỷ lệ segment `confidence` thấp vượt `ocr_stt.poor_ratio_threshold` →
  `output_quality_flag='poor'`, `auto_confirm_blocked=true` trong payload
  outbox.

## 6. Task breakdown

### 6.A. Hạ tầng AI worker Python (effort lớn — đề xuất tách epic riêng, GPU/model, không breakdown chi tiết ở spec backend này)

- [ ] Epic A1: Docker image `ocr-worker` (base CUDA, PaddleOCR + VietOCR +
      OpenCV + PyMuPDF, model weights đóng gói offline theo NFR-04/16).
- [ ] Epic A2: Docker image `stt-worker` (faster-whisper/PhoWhisper +
      Silero VAD, model weights offline).
- [ ] Epic A3: Pipeline tiền xử lý + suy luận (deskew/denoise/orientation,
      VAD cắt khoảng lặng, gộp câu theo dấu câu, cắt cưỡng bức đoạn > 30s,
      tính `input_quality_flag`, `no_speech_prob`).
- [ ] Epic A4: Tích hợp RabbitMQ client (pika/aio-pika) — consume
      `q.ocr`/`q.stt`, publish `q.heartbeat`/`q.result` theo hợp đồng mục 3.
- [ ] Epic A5: Tích hợp SeaweedFS (S3 API, credential ghi-chỉ-`derived/`) —
      ghi JSON thô + (tuỳ chọn) audio chuẩn hoá.
- [ ] Epic A6: Benchmark giai đoạn 0 (CER/WER trên bộ mẫu tổng hợp, hệ quả
      Quyết định 1 SA) để chốt/điều chỉnh ngưỡng `ocr_stt.min_confidence_*`.
- [ ] Epic A7: CI/CD build & publish image lên registry nội bộ (air-gap).

> Ước lượng: đây là khối lượng công việc AI/ML + hạ tầng GPU độc lập, nên lập
> thành **epic/spec riêng** (ví dụ `docs/03-dev/specs/ai-worker-ocr-stt.md`
> hoặc theo BA tách slug) khi bắt đầu implement — spec hiện tại chỉ mô tả
> **hợp đồng giao tiếp** (mục 3) mà phần backend cần worker tuân thủ.

### 6.B. Spring Boot tiêu thụ kết quả (effort vừa phải)

- [x] Task 1: Migration `V27__digitization_source_duration_and_time_check.sql`
      (mục 2) — chạy sau khi `V25`/`V26` của `digitization-results-store.md`
      được implement. Verify thủ công qua psql (V21 Testcontainers bug, xem
      Task 11) trên V1..V27: chạy sạch, trigger chặn `time_end_ms`/
      `current_time_end_ms` vượt `duration_ms`, bỏ qua khi `duration_ms IS
      NULL`.
- [x] Task 2: Sửa `DigitizationDispatchService.publish` (Pha 1) — gọi
      `createRun`/`findActiveByDocumentAndTask`, thêm `runId` vào message,
      set AMQP `priority` (mục 4.1).
- [x] Task 3: Sửa `RabbitConfig` (Pha 1) — thêm `x-max-priority=9` cho
      `q.ocr`/`q.stt`; khai báo mới `q.heartbeat` (durable, không cần
      `.retry`/`.dlq`) và `q.result` (durable, không cần `.retry`/`.dlq` —
      lỗi xử lý message này là lỗi lập trình, xử lý bằng
      `DefaultErrorHandler` + log, không phải lỗi nghiệp vụ cần requeue).
      Bỏ luôn `x-message-ttl` cố định trên queue `.retry` (thay bằng TTL theo
      từng message, xem Task 8) và đăng ký `JavaTimeModule` cho message
      converter.
- [x] Task 4: Bổ sung `completeRunSuccess`/`completeRunFailure` vào
      `DigitizationRunLifecycleService`/`Impl` (mục 3.5, mục 4.5).
- [x] Task 5: `DigitizationHeartbeatConsumer` (mục 4.3).
- [x] Task 6: `DigitizationResultConsumer` (mục 4.5).
- [x] Task 7: `DigitizationRunLeaseReconciliationJob` (mục 4.4).
- [x] Task 8: Logic backoff `.retry`/`.dlq` khi `failed_retryable`/hết lượt
      (trong `completeRunFailure`, mục 4.5 bước 3).
- [x] Task 9: `DigitizationDlqAlertJob` (mục 4.6).
- [x] Task 10: Thêm seed `system-config`: `ocr_stt.min_confidence_ocr`,
      `ocr_stt.min_confidence_stt`, `ocr_stt.poor_ratio_threshold`,
      `ocr_stt.no_speech_prob_threshold` (lưu dạng số nguyên phần trăm
      0-100 vì `ValueType.NUMBER` chỉ chứa `long` — không có kiểu thập phân
      trong hạ tầng `system-config` hiện có; DEV gap-fill, giá trị hiệu dụng
      giữ đúng 0.60/0.55/0.30/0.80), `ocr_stt.max_retry=3`,
      `ocr_stt.segment_max_seconds=30` — thêm vào `SystemConfigDefaults`/
      `SystemConfigValidator` (registry Java, đúng pattern hiện có của
      `ocr_stt.auto_confirm_hours`, không phải seed migration riêng).
- [x] Task 11: Viết test theo mục 5 — unit (`DigitizationDispatchServiceTest`
      mở rộng, `DigitizationRunLifecycleServiceImplTest`,
      `DigitizationResultConsumerTest`, `DigitizationHeartbeatConsumerTest`,
      `DigitizationRunLeaseReconciliationJobTest`, `DigitizationDlqAlertJobTest`)
      đều pass. Migration/constraint verify thủ công qua psql (không phải
      JUnit) vì bug đã biết ở `V21__audit_log_seal_and_partition.sql`
      (`setval(...)` lỗi trên DB rỗng) khiến mọi Testcontainers Postgres IT
      trong repo (kể cả các IT có từ trước, không riêng spec này) không khởi
      động được `ApplicationContext` trong sandbox này — xem ghi chú ở đầu
      task. Integration test Testcontainers Postgres+RabbitMQ end-to-end
      (dispatch → heartbeat → result → outbox, dedupe, lease reconciliation,
      retry/DLQ) **chưa viết** vì cùng lý do (không verify được ở sandbox
      này) — nên bổ sung khi hạ tầng CI thật chạy được Testcontainers.

## 7. Rủi ro kỹ thuật

- ~~Phụ thuộc trực tiếp vào `digitization-results-store.md` (Draft, chưa
  implement)~~ — **4 điểm mục 7 đã chốt 2026-09-07**, giữ 1 bảng
  `digitization_run` như spec này đã giả định; mục 4.1–4.5 không cần sửa.
  Vẫn còn phụ thuộc **thứ tự migration** (`V25`/`V26` phải chạy trước `V27`)
  cho tới khi `digitization-results-store.md` chuyển `Approved` thật.
- **Nguồn `batch_id`/cờ interactive-bulk chưa tồn tại** (mục 0 điểm 6) — Story
  3 AC-6/AC-7 (ưu tiên + hạn ngạch bulk) chỉ thực thi đúng khi `data-ingestion`
  nối được cờ này vào `DigitizationDispatchService.publish`; cho tới lúc đó,
  mọi job đều publish `priority=5`, hạn ngạch tối thiểu cho bulk (Quyết định 2
  SA, `[ĐX]` 20% slot) **chưa có hiệu lực thực tế** dù hạ tầng priority đã có.
- **`q.heartbeat` tần suất cao khi `ai_queue.concurrency` tăng** — ở mức 4
  worker đồng thời (giá trị khởi điểm SA), 4 message/30s là không đáng kể;
  nếu tăng concurrency nhiều ở giai đoạn sau, cân nhắc gộp heartbeat hoặc
  giãn chu kỳ, không phải vấn đề ở quy mô hiện tại.
- **Trigger `V27` join 2 bảng (`digitization_run`, `digitization_source`)
  trên mỗi INSERT/UPDATE `content_segment`** — với ~20 triệu segment dự kiến
  (SA mục 6 rủi ro), chi phí join thêm mỗi lần ghi cần đo lại khi có tải
  thật; nếu là bottleneck, có thể denormalize `duration_ms` xuống
  `digitization_run` (copy 1 lần khi `completeRunSuccess`) để tránh join —
  chưa làm ở spec này vì chưa có số liệu tải thật để quyết định.
- **`attempt_count > max_retry` mượn trạng thái `failed_permanent`** (mục 4.5
  bước 3) thay vì có state riêng "hết lượt retry" — chấp nhận được vì
  `last_error` phân biệt được qua nội dung text, nhưng UI kiểm duyệt/vận hành
  cần đọc `last_error` để phân biệt "lỗi vĩnh viễn thật" và "hết lượt thử" —
  nếu cần phân biệt cứng ở tầng dữ liệu, thêm 1 cột `retry_exhausted BOOLEAN`
  là thay đổi nhỏ, không ảnh hưởng thiết kế tổng thể.
- **Worker cần quyền publish trực tiếp vào `.retry`/`.dlq`? Không** — mọi
  logic backoff/DLQ nằm ở backend (`completeRunFailure`), worker chỉ publish
  `q.result`; giảm rủi ro worker (ít tin cậy hơn, chạy air-gap, khó vá lỗi
  nhanh) thao túng trực tiếp hàng đợi retry.
- **Ngưỡng chất lượng khởi điểm** (Task 10) là `[ĐX]` của SA, cùng rủi ro nêu
  ở story — benchmark giai đoạn 0 (Epic A6) có thể yêu cầu đổi trước go-live,
  không ảnh hưởng code, chỉ ảnh hưởng dữ liệu cấu hình.
</content>
