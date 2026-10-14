# Tech Spec: digitization-pipeline-01-source-dispatch

> File: `docs/03-dev/specs/digitization-pipeline-01-source-dispatch.md` — Owner: DEV
> Nguồn story: `docs/02-ba/stories/digitization-pipeline-01-source-dispatch.md`
> Status: Approved

> ⚠️ **Bị thay thế một phần từ 2026-09-23** bởi
> `digitization-pipeline-04-video-no-separation.md` (SA Quyết định 10):
> §0 điểm 2–3, nhánh `video/*` ở §1, §4.2 (`SourceSeparationJobRunner`,
> FFmpeg), điều kiện `source_separation_job` ở §4.5 bước 2, và cấu hình
> `ffmpeg-*`/`job-*` ở §4.6 **không còn áp dụng**. Video nay đi đúng §4.3
> (`component_type='original'`) và được định tuyến vào `q.video`. Bảng
> `source_separation_job` (§2.1) chỉ còn dữ liệu lịch sử. Phần còn lại giữ
> nguyên hiệu lực.

> ⚠️ Migration tiếp theo trong repo là **`V18`** (đã có `V1`–`V13`, `V15`–`V17`;
> `V14` được dự trù cho `auth-session.md` nhưng chưa implement — bỏ trống, spec
> này **không** được chiếm số đó). Flyway không bật `out-of-order`, migration
> này phải merge sau `V17__upload_session_personal_folder.sql`.

## 0. Phạm vi & giả định cần xác nhận

Đây là bản Draft đầu tiên — 3 điểm dưới đây là lựa chọn kỹ thuật của DEV khi
chưa có đủ dữ kiện, cần người duyệt xác nhận trước khi coi spec là Approved
(xem thêm mục 8):

1. **Cơ chế kích hoạt Pha 1** (BA câu hỏi mở #1): chưa có `outbox_event`/
   `document.ingested` trong code. Spec này chọn phương án **không xây outbox
   đầy đủ ngay** — gọi trực tiếp `DigitizationDispatchService` bằng
   `@TransactionalEventListener(phase = AFTER_COMMIT)` trong cùng JVM, ngay
   sau khi `DocumentsService` commit dòng `document` với `status='clean'`.
   Lý do: outbox+relay của `ai-search-infra.md` Quyết định 7 giải quyết bài
   toán đồng bộ **nhiều service/nhiều consumer bên ngoài** — ở đây chỉ có một
   consumer duy nhất, cùng tiến trình. Việc backend crash giữa lúc commit
   `document` và lúc listener chạy được xử lý bằng **job đối soát định kỳ**
   (mục 4.5) quét `document.status='clean'` chưa có `digitization_source`
   tương ứng — bù đắp đúng phần "tin cậy" mà outbox lẽ ra cung cấp, không cần
   thêm bảng/relay riêng. Nếu sau này có thêm consumer khác cần
   `document.ingested`, nâng cấp lên outbox thật là thay đổi cục bộ trong
   `DocumentsService`, không ảnh hưởng bảng ở spec này.
2. **Định dạng audio sau khi tách khỏi video**: chuẩn hoá về **WAV (PCM
   16-bit)** — `ffmpeg -i <input> -vn -acodec pcm_s16le <output>.wav` — để
   tránh phụ thuộc codec/container gốc (mp4/mkv/mov/webm) khi AI worker (Pha
   2, chưa code) đọc tệp. `processFileType` gửi vào hàng đợi cho trường hợp
   này là `audio/wav`.
3. **Bucket lưu thành phần đã tách**: dùng chung bucket `documents`
   (`app.storage.bucket`, đã có ở `ObjectStorageService`), object key theo
   prefix `derived/<document_id>/video.<ext>` / `derived/<document_id>/
   audio.wav` — đúng quy ước `derived/` đã chốt ở `digitization-pipeline.md`
   Quyết định 3, dùng lại `ObjectStorageService.putObject` hiện có (không cần
   client S3 mới).

## 1. Tóm tắt kỹ thuật

- Thêm 2 bảng: `source_separation_job` (trạng thái job FFmpeg, chỉ áp dụng
  video) và `digitization_source` (thành phần đã sẵn sàng để AI worker xử lý
  — 1 dòng cho audio/ảnh/PDF trực tiếp, 2 dòng cho video).
- Thêm `RabbitConfig`: khai báo exchange `ai.jobs` (topic) + DLX `ai.jobs.dlx`,
  2 queue `q.ocr`/`q.stt` (tên đọc từ env, xem mục 4.6) mỗi queue có
  `.retry`/`.dlq` — đúng khung đã chốt ở `digitization-pipeline.md` Quyết
  định 2 (Pha 2 sẽ tiêu thụ cùng queue này, không định nghĩa lại).
- Sau khi `DocumentsService` ghi `document.status='clean'` thành công (batch
  ingest, `data-ingestion.md` mục 4.2 bước 8c), một listener chạy sau khi
  transaction commit, phân loại theo `mimeType`:
  - `video/*` → tạo `source_separation_job(status='queued')`.
  - `audio/*`, `image/*`, `application/pdf` → tạo thẳng
    `digitization_source(component_type='original', id=document_id,
    dispatch_status='pending')`.
  - Loại khác (office docs, text, csv...) → **không tạo gì**, không thuộc
    phạm vi số hoá (FR-17/FR-19 chỉ nói ảnh/scan/PDF/audio/video).
- `SourceSeparationJobRunner` (`@Scheduled`, poll `queued`/`running` quá hạn)
  chạy `ffmpeg` tách video/audio, ghi 2 `digitization_source`, đặt
  `dispatchable=false` cho dòng `video` (không bao giờ bị publish tự động).
- `DigitizationDispatchService.publish(source)` gửi message 5 trường (mục
  4.4) vào `q.ocr`/`q.stt` theo `processFileType`; publish trực tiếp ngay sau
  khi ghi dòng `dispatchable=true`, có `DigitizationDispatchRetryJob`
  (`@Scheduled`) quét `dispatch_status='pending' AND dispatchable=true` quá
  hạn để gửi lại — bù cho lỗi kết nối RabbitMQ tạm thời hoặc backend chết
  giữa lúc ghi DB và publish.
- Hạ tầng: thêm `spring-boot-starter-amqp` vào `backend/pom.xml`; RabbitMQ đã
  có sẵn trong `docker-compose.yml`/`stg/docker-compose.yml`, chỉ cần cấu
  hình `spring.rabbitmq.*` (host/port/user/pass) trỏ vào đó.

## 2. Data model

```sql
-- V18__digitization_source_dispatch.sql

-- 2.1 Job tách nguồn video bằng FFmpeg (Story 1). Chỉ video mới có dòng ở
-- bảng này; audio/ảnh/PDF đi thẳng digitization_source (mục 2.2).
CREATE TABLE source_separation_job (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    document_id    UUID NOT NULL REFERENCES document (id),
    status         VARCHAR(16) NOT NULL DEFAULT 'queued'
        CHECK (status IN ('queued', 'running', 'succeeded', 'failed')),
    error_message  TEXT,
    attempt_count  INT NOT NULL DEFAULT 0,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
-- Một document chỉ cần tách 1 lần; listener AFTER_COMMIT gọi lại (retry ở
-- tầng ứng dụng, hoặc do document.ingested lý thuyết bắn 2 lần) không tạo
-- job trùng.
CREATE UNIQUE INDEX UQ_source_separation_job_document
    ON source_separation_job (document_id);
CREATE INDEX IDX_source_separation_job_pollable
    ON source_separation_job (status, updated_at);

-- 2.2 Thành phần sẵn sàng để AI worker xử lý (Story 1 + Story 2).
CREATE TABLE digitization_source (
    -- video: sinh mới; audio/ảnh/PDF: = document_id (Story 2 AC-2 — "sourceId
    -- chính là fileId" khi không phải video).
    id                      UUID PRIMARY KEY,
    document_id             UUID NOT NULL REFERENCES document (id),
    component_type          VARCHAR(16) NOT NULL
        CHECK (component_type IN ('video', 'audio', 'original')),
    file_path               VARCHAR(1024) NOT NULL, -- "<bucket>/<objectKey>", xem mục 4.4
    mime_type                VARCHAR(255) NOT NULL, -- processFileType khi dispatch
    -- component_type='video' luôn dispatchable=false (Story 1 AC-3 — không tự
    -- động đẩy vào hàng đợi ở Pha 1); các loại còn lại luôn true.
    dispatchable             BOOLEAN NOT NULL DEFAULT true,
    dispatch_status          VARCHAR(16) NOT NULL DEFAULT 'pending'
        CHECK (dispatch_status IN ('pending', 'published', 'failed')),
    dispatch_attempt_count   INT NOT NULL DEFAULT 0,
    dispatched_at            TIMESTAMPTZ,
    created_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IDX_digitization_source_document ON digitization_source (document_id);
-- Job đối soát (mục 4.5) + retry dispatch (mục 4.4) đều quét theo cặp này.
CREATE INDEX IDX_digitization_source_dispatch_pollable
    ON digitization_source (dispatchable, dispatch_status, updated_at);
```

## 3. API contract

Không có endpoint HTTP mới ở pha này — toàn bộ luồng chạy nội bộ (listener +
`@Scheduled` job), đúng phạm vi story ("chưa cần UI/API xem trạng thái").
`source_separation_job`/`digitization_source` chỉ truy vấn được qua DB (vận
hành/debug), không expose REST.

## 4. Luồng xử lý chính

### 4.1 Kích hoạt sau nhập kho

1. `DocumentsService.ingestBatchFile(...)` (đã có, `data-ingestion.md` mục
   4.2 bước 8c) commit `document.status='clean'` như hiện tại — **không sửa**
   logic đó.
2. Publish một application event nội bộ (`DocumentIngestedEvent(documentId,
   folderId, mimeType)`) ngay trước khi method trả về (`ApplicationEventPublisher`,
   Spring sẵn có).
3. `DigitizationDispatchListener` (`@TransactionalEventListener(phase =
   TransactionPhase.AFTER_COMMIT)`) nhận event — chạy **sau khi** transaction
   ghi `document` đã commit, tránh dispatch một document chưa thực sự tồn tại
   nếu transaction rollback.
4. Kho cá nhân **không** phát event này — `DocumentsService.ingestBatchFile`
   chỉ dùng cho kho chung (đúng theo `personal-storage-quota.md`, luồng kho cá
   nhân dùng service khác) nên không cần thêm điều kiện lọc.

### 4.2 Nhánh video (Story 1) — ~~ĐÃ HUỶ 2026-09-23~~, xem spec 04

1. `mimeType` thuộc nhóm `video/*` (theo `MimeAllowlist`: `video/mp4`,
   `video/mpeg`, `video/quicktime`, `video/x-matroska`, `video/webm`) →
   `INSERT source_separation_job(document_id, status='queued')` — bỏ qua nếu
   đã tồn tại (unique index mục 2.1, `ON CONFLICT DO NOTHING`).
2. `SourceSeparationJobRunner` (`@Scheduled(fixedDelay =
   ${app.digitization.job-poll-interval-ms:10000})`): mỗi lượt lấy tối đa N
   job (`status='queued' OR (status='running' AND updated_at < now() -
   interval 'X minutes')` — dòng `running` quá hạn coi như job cũ chết cùng
   JVM trước, xử lý lại) `ORDER BY created_at LIMIT
   ${app.digitization.job-batch-size:5} FOR UPDATE SKIP LOCKED`, set
   `status='running'`.
3. Với mỗi job: `objectStorageService.getObject(document.storageKey)` tải
   tệp gốc về file tạm cục bộ (chỉ đọc, đúng NFR-09).
4. Chạy `ffmpeg` qua `ProcessBuilder` (đường dẫn binary từ
   `app.digitization.ffmpeg-path:ffmpeg`, PATH mặc định):
   - Video-only: `ffmpeg -y -i <tmp_in> -an -c:v copy <tmp_video_out>`.
   - Audio-only: `ffmpeg -y -i <tmp_in> -vn -acodec pcm_s16le
     <tmp_audio_out>.wav` (mục 0, điểm 2).
   - Timeout tiến trình: `app.digitization.ffmpeg-timeout-ms:600000` (10
     phút) — quá hạn thì `destroyForcibly()`, coi là lỗi.
5. **Thành công cả 2 lệnh:**
   - `putObject("derived/<document_id>/video.<ext gốc>", tmp_video_out,
     <mime gốc>)`, `putObject("derived/<document_id>/audio.wav",
     tmp_audio_out, "audio/wav")`.
   - `INSERT digitization_source` 2 dòng:
     - `id=gen_random_uuid()`, `component_type='video'`,
       `dispatchable=false`, `mime_type=<mime video gốc>`.
     - `id=gen_random_uuid()`, `component_type='audio'`, `dispatchable=true`,
       `mime_type='audio/wav'`.
   - `UPDATE source_separation_job SET status='succeeded'`.
   - Gọi `digitizationDispatchService.publish(audioSource)` ngay (mục 4.4).
   - Xoá file tạm cục bộ (finally block, kể cả khi lỗi).
6. **Lỗi** (ffmpeg thoát khác 0, timeout, lỗi tải tệp gốc): `UPDATE
   source_separation_job SET status='failed', error_message=...,
   attempt_count=attempt_count+1`. **Không** retry tự động trong phạm vi Pha
   1 (khác với retry 3 lần của Quyết định 2 — đó là retry job **AI GPU**, ở
   đây là lỗi tách nguồn, thường là lỗi vĩnh viễn — codec không hỗ trợ/tệp
   hỏng; để lại `failed` cho vận hành xem xét thủ công, tránh vòng lặp
   ffmpeg thất bại vô hạn).

### 4.3 Nhánh audio/ảnh/PDF (Story 2)

1. `mimeType` thuộc `audio/*`, `image/*`, hoặc `application/pdf` →
   `INSERT digitization_source(id=document_id, document_id=document_id,
   component_type='original', file_path=<bucket>/<document.storageKey>,
   mime_type=document.mimeType, dispatchable=true, dispatch_status='pending')`
   — `ON CONFLICT (id) DO NOTHING` (idempotent nếu listener chạy lại).
2. Gọi `digitizationDispatchService.publish(source)` ngay trong cùng lượt xử
   lý event (không cần job riêng — không có bước biến đổi tốn thời gian).

### 4.4 Publish message + định tuyến hàng đợi

`DigitizationDispatchService.publish(DigitizationSource source)`:

1. Tính `filePath = "<app.storage.bucket>/<source.filePath thô đã lưu>"` —
   *(lưu ý: cột `file_path` ở mục 2.2 đã lưu sẵn dạng `<bucket>/<key>`, bước
   này chỉ đọc lại, không tính toán thêm)*.
2. Đọc `document` liên quan để lấy `originalFileType`.
3. Dựng message:
   ```json
   {
     "fileId": "<document_id>",
     "sourceId": "<source.id>",
     "filePath": "<bucket>/<objectKey>",
     "originalFileType": "<document.mimeType>",
     "processFileType": "<source.mimeType>"
   }
   ```
4. Định tuyến theo `source.mimeType` (=`processFileType`): bắt đầu bằng
   `audio/` → publish vào queue tên `${QUEUE_STT_NAME}`; bắt đầu bằng
   `image/` hoặc bằng đúng `application/pdf` → `${QUEUE_OCR_NAME}`. Loại
   khác → lỗi lập trình (không nên xảy ra vì mục 4.2/4.3 đã lọc trước),
   log lỗi + giữ `dispatch_status='pending'` để job đối soát bắt lại.
5. `rabbitTemplate.convertAndSend(exchange="ai.jobs", routingKey=<tên queue>,
   message)` — publish thành công → `UPDATE digitization_source SET
   dispatch_status='published', dispatched_at=now()`. Ném exception (mất kết
   nối, timeout) → `UPDATE ... SET dispatch_attempt_count=dispatch_attempt_count+1`,
   giữ `dispatch_status='pending'`, không throw tiếp lên listener (tránh làm
   fail request nhập kho của người dùng — publish là **best-effort ngay lập
   tức**, job đối soát mục 4.5 đảm bảo **rồi sẽ** gửi được).

### 4.5 Job đối soát (retry dispatch + phát hiện document bị bỏ sót)

`DigitizationDispatchRetryJob` (`@Scheduled(fixedDelay =
${app.digitization.dispatch-retry-interval-ms:60000})`):

1. Quét `digitization_source WHERE dispatchable=true AND dispatch_status='pending'
   AND updated_at < now() - interval '30 seconds' LIMIT
   ${app.digitization.dispatch-retry-batch-size:100}` → gọi lại
   `publish(...)` cho từng dòng.
2. Quét `document d WHERE d.status='clean' AND d.deleted_at IS NULL AND
   d.mime_type LIKE ANY(...) AND NOT EXISTS (SELECT 1 FROM digitization_source
   ds WHERE ds.document_id = d.id) AND NOT EXISTS (SELECT 1 FROM
   source_separation_job j WHERE j.document_id = d.id) AND d.created_at <
   now() - interval '2 minutes'` — bắt trường hợp backend chết đúng giữa lúc
   commit `document` và listener `AFTER_COMMIT` chạy (mục 0, điểm 1). Với mỗi
   dòng tìm được, chạy lại đúng logic mục 4.2/4.3 tương ứng theo mimeType.

### 4.6 Cấu hình (env, `application.yml`)

```yaml
app:
  digitization:
    ffmpeg-path: ${FFMPEG_PATH:ffmpeg}
    ffmpeg-timeout-ms: ${FFMPEG_TIMEOUT_MS:600000}
    job-poll-interval-ms: ${DIGITIZATION_JOB_POLL_INTERVAL_MS:10000}
    job-batch-size: ${DIGITIZATION_JOB_BATCH_SIZE:5}
    dispatch-retry-interval-ms: ${DIGITIZATION_DISPATCH_RETRY_INTERVAL_MS:60000}
    dispatch-retry-batch-size: ${DIGITIZATION_DISPATCH_RETRY_BATCH_SIZE:100}
  queue:
    ocr-name: ${QUEUE_OCR_NAME:q.ocr}
    stt-name: ${QUEUE_STT_NAME:q.stt}

spring:
  rabbitmq:
    host: ${RABBITMQ_HOST:localhost}
    port: ${RABBITMQ_PORT:5672}
    username: ${RABBITMQ_DEFAULT_USER:aismartstore}
    password: ${RABBITMQ_DEFAULT_PASS:aismartstore}
    virtual-host: /
```

`RabbitConfig` khai báo (dùng `TopicExchange`, `Queue`, `Binding` bean chuẩn
Spring AMQP):
- Exchange `ai.jobs` (topic, durable) + DLX `ai.jobs.dlx` (fanout hoặc topic,
  durable).
- Queue `${app.queue.ocr-name}` và `${app.queue.stt-name}`, mỗi queue:
  `x-dead-letter-exchange=ai.jobs.dlx`, `x-dead-letter-routing-key=<queue>.dlq`.
  Queue `<queue>.retry` (TTL backoff) và `<queue>.dlq` cũng khai báo ở đây
  theo đúng khung Quyết định 2 — **nhưng Pha 1 chỉ publish vào queue chính**;
  cơ chế requeue-khi-lỗi-xử-lý (đọc từ `.retry`) thuộc về AI worker (Pha 2,
  chưa code) tiêu thụ, Pha 1 không có logic đọc lại các queue này.

## 5. Test plan

- **Unit test:**
  - `DigitizationDispatchServiceTest`: `audio/wav` → publish đúng
    `QUEUE_STT_NAME`; `image/png`/`application/pdf` → đúng `QUEUE_OCR_NAME`;
    message JSON đúng 5 trường, đúng giá trị (`fileId != sourceId` cho video,
    `fileId == sourceId` cho audio/ảnh/PDF trực tiếp).
  - Publish ném exception → `dispatch_status` giữ `pending`,
    `dispatch_attempt_count` tăng, **không** propagate exception ra ngoài
    (verify bằng mock `RabbitTemplate` throw, gọi từ listener không fail).
  - `SourceSeparationJobRunnerTest` (mock `ProcessBuilder`/tách logic build
    lệnh ra hàm riêng để test được): build đúng 2 lệnh ffmpeg theo mục 4.2
    bước 4; ffmpeg exit code khác 0 → job `failed`, không tạo
    `digitization_source`.
  - Phân loại mimetype (mục 4.2/4.3 điều kiện lọc): video/audio/image/pdf →
    đúng nhánh; office docs/text/csv → không tạo job/source nào.
- **Integration test (Testcontainers Postgres + RabbitMQ):**
  - Nhập kho 1 tệp `image/png` → sau khi request trả về, `digitization_source`
    có đúng 1 dòng `id=document_id`, `dispatch_status='published'`; queue
    `q.ocr` nhận đúng 1 message đúng 5 trường.
  - Nhập kho 1 tệp `video/mp4` (fixture ngắn có cả audio+video) → sau khi
    `SourceSeparationJobRunner` chạy (đợi qua poll interval hoặc gọi trực
    tiếp trong test), có 2 `digitization_source` (`video`
    `dispatchable=false`, `audio` `dispatchable=true`
    `dispatch_status='published'`); queue `q.stt` nhận đúng 1 message,
    `sourceId != fileId`, `processFileType='audio/wav'`; **không** có message
    nào cho thành phần video.
  - Nhập kho 1 tệp `video/mp4` hỏng (không phải video thật) → job
    `source_separation_job.status='failed'`, không có `digitization_source`
    nào được tạo, không publish gì.
  - RabbitMQ down khi publish (dừng container test) → `dispatch_status` giữ
    `pending`; bật lại RabbitMQ, chạy `DigitizationDispatchRetryJob` thủ công
    trong test → message được publish, `dispatch_status='published'`.
  - Giả lập "document bị bỏ sót" (insert thẳng `document.status='clean'`
    bằng SQL, không qua listener) → chạy `DigitizationDispatchRetryJob`, xác
    nhận job đối soát mục 4.5 tự tạo `digitization_source`/
    `source_separation_job` tương ứng và publish đúng.
  - Nhập kho tệp `application/msword` (docx/doc...) → không có
    `digitization_source`/`source_separation_job` nào được tạo, không
    publish gì (ngoài phạm vi số hoá).

## 6. Task breakdown

- [x] Task 0 (hạ tầng): thêm `spring-boot-starter-amqp` vào `backend/pom.xml`;
      cấu hình `spring.rabbitmq.*` trong `application.yml` trỏ vào RabbitMQ
      đã có ở `docker-compose.yml`/`stg/.env` (không tạo hạ tầng mới). DEV
      note: `docker-compose.yml`/`stg/docker-compose.yml` chỉ có `RABBITMQ_URL`
      (không được `spring.rabbitmq.*` đọc) — đã bổ sung thêm
      `RABBITMQ_HOST`/`RABBITMQ_PORT` (và `RABBITMQ_DEFAULT_USER/PASS` ở stg)
      vào biến môi trường của service `backend` ở cả hai file, giữ nguyên
      `RABBITMQ_URL` cũ.
- [x] Task 1: Migration `V18__digitization_source_dispatch.sql` (mục 2).
- [x] Task 2: Entity `SourceSeparationJob`, `DigitizationSource` +
      repository JPA tương ứng.
- [x] Task 3: `RabbitConfig` — exchange `ai.jobs`/DLX, 2 queue chính +
      `.retry`/`.dlq` (mục 4.6).
- [x] Task 4: `DocumentIngestedEvent` + publish trong
      `DocumentsService.ingestBatchFile` + `DigitizationDispatchListener`
      phân loại theo mimeType, gọi đúng nhánh 4.2/4.3. **Lệch có chủ đích so
      với mục 4.1 bước 3**: dùng `@EventListener` thường (đồng bộ, không
      transactional) thay vì `@TransactionalEventListener(AFTER_COMMIT)` —
      quyết định của DEV + người dùng (2026-08-30), lý do: xem Javadoc của
      `DigitizationDispatchListener`. Tóm tắt: `ingestBatchFile` không có
      `@Transactional` bao ngoài (mỗi `documentRepository.save()` tự commit
      transaction riêng), nên tại thời điểm publish event không còn transaction
      nào đang active — `@TransactionalEventListener(AFTER_COMMIT)` với
      `fallbackExecution=false` (mặc định) sẽ bị Spring âm thầm bỏ qua ở
      **mọi** lần ingest, không phải edge case. `@EventListener` thường tương
      đương "after commit" ở đây vì event chỉ được publish sau khi save() của
      bước 8c đã return (tức transaction của nó đã commit xong).
- [x] Task 5: `SourceSeparationJobRunner` (`@Scheduled`) — tải tệp gốc, chạy
      `ffmpeg` qua `ProcessBuilder`, ghi kết quả (mục 4.2).
- [x] Task 6: `DigitizationDispatchService.publish(...)` (mục 4.4).
- [x] Task 7: `DigitizationDispatchRetryJob` (`@Scheduled`, mục 4.5) — retry
      publish + quét document bị bỏ sót.
- [x] Task 8: Test theo mục 5 — unit test đầy đủ; integration test
      (Testcontainers Postgres+RabbitMQ) cho image/docx/video-ok/video-hỏng/
      reconciliation; **chưa** viết test kịch bản "RabbitMQ down rồi phục hồi"
      ở mức integration (chỉ có ở mức unit) — xem Javadoc của
      `DigitizationDispatchIT` để biết lý do.

## 7. Rủi ro kỹ thuật

- **`ffmpeg` chạy đồng bộ trong tiến trình backend** (không phải worker
  Python riêng) — job dài (video lớn) chiếm 1 thread poll trong lúc chạy.
  Giảm thiểu: `job-batch-size` nhỏ (mặc định 5), `ffmpeg-timeout-ms` chặn
  tiến trình treo vô hạn; nếu backlog video lớn ảnh hưởng backend, tách
  `SourceSeparationJobRunner` sang tiến trình/instance riêng là việc của giai
  đoạn sau, không phải thay đổi schema.
- **`@TransactionalEventListener(AFTER_COMMIT)` chỉ hoạt động trong cùng
  JVM** — nếu sau này backend chạy nhiều instance, event publish ở instance A
  không tự động kích hoạt listener ở instance B. Không phải vấn đề ở phạm vi
  hiện tại (một instance backend, theo giả định NFR toàn hệ thống), nhưng job
  đối soát mục 4.5 vẫn là lưới an toàn đúng nghĩa cho cả trường hợp multi-
  instance sau này (bất kỳ instance nào chạy job đối soát cũng bắt được
  document bị bỏ sót, bất kể instance nào ghi document đó).
- **Không retry tự động khi ffmpeg lỗi** (mục 4.2 bước 6) — nếu nguyên nhân
  là lỗi tạm thời (I/O đọc SeaweedFS chập chờn) thay vì lỗi vĩnh viễn (codec
  lạ), tệp đó sẽ kẹt `failed` mãi mãi mà không ai biết trừ khi có người tra
  DB. Chấp nhận cho MVP — thêm cảnh báo/UI xem `failed` job là việc của pha
  sau; nợ kỹ thuật có chủ đích, cùng nhóm với các nợ kỹ thuật MVP khác đã
  chấp nhận ở `data-ingestion.md`.
- **Định dạng audio chuẩn hoá WAV** (mục 0 điểm 2) là giả định của DEV, chưa
  có xác nhận từ thiết kế AI worker (Pha 2 chưa code) — nếu `stt-worker` cần
  định dạng khác (ví dụ giữ nguyên AAC để tự xử lý), đổi 1 dòng lệnh ffmpeg ở
  mục 4.2 bước 4, không ảnh hưởng schema/message contract (`processFileType`
  vẫn phản ánh đúng mimetype thật đã tạo ra).

## 8. Điểm cần người duyệt xác nhận trước khi Approve

- [ ] Đồng ý phương án **không xây outbox đầy đủ** cho Pha 1, dùng
      `@TransactionalEventListener(AFTER_COMMIT)` + job đối soát thay thế
      (mục 0 điểm 1)?
- [ ] Đồng ý **chuẩn hoá audio tách từ video về WAV 16-bit PCM** làm mặc định
      (mục 0 điểm 2), có thể đổi khi Pha 2 (AI worker) chốt yêu cầu đầu vào
      cụ thể?
- [ ] Đồng ý **không retry tự động** khi job tách nguồn (`ffmpeg`) thất bại —
      để `failed` cho vận hành xử lý thủ công (mục 4.2 bước 6, mục 7)?
- [ ] Tên biến môi trường `QUEUE_OCR_NAME`/`QUEUE_STT_NAME` (mục 4.6) — giữ
      nguyên hay đổi tên khác cho khớp quy ước đặt tên env hiện có của dự án?
