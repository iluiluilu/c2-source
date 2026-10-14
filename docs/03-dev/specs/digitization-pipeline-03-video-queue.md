# Tech Spec: digitization-pipeline-03-video-queue

> File: `docs/03-dev/specs/digitization-pipeline-03-video-queue.md` — Owner: DEV
> Nguồn: `docs/01-sa/architecture/digitization-pipeline.md` § Quyết định 8
> (không qua BA riêng — phạm vi đã chốt trực tiếp qua trao đổi nghiệp vụ
> 2026-09-14, xem hội thoại phiên làm việc; BA story bị bỏ qua có chủ đích).
> Status: Approved (người dùng xác nhận toàn bộ mục 8 ngày 2026-09-14)

> ⚠️ **Cập nhật 2026-09-23** bởi `digitization-pipeline-04-video-no-separation.md`
> (SA Quyết định 10): §4.1 (sửa `SourceSeparationJobRunner` publish cả
> `audio`→`q.stt` và `video`→`q.video`) và Task 7 **không còn áp dụng** —
> runner bị gỡ. `q.video` nay nhận **tệp video gốc** (`component_type=
> 'original'`, `sourceId = fileId`, **có cả audio track**, `filePath` trỏ tệp
> gốc, không phải `derived/`). Định tuyến §4.2, message §4.3, hợp đồng lease/
> `UPDATE digitization_run` §4.4, trigger/endpoint giữ nguyên.

> ⚠️ **Cập nhật 2026-09-24** bởi `digitization-pipeline-05-video-review.md`
> (V32): task `video` nay đi cùng vòng đời `stt` — hoàn tất qua `q.result`
> (hoặc cầu tạm như ocr/stt), **có qua FR-18** (hàng chờ Số hoá). §0 điểm 3,
> §2.3 (trigger `trg_sync_video_status` — **đã DROP**), §4.4 (worker chỉ
> `UPDATE digitization_run`) và §4.5 **không còn áp dụng**.

> ⚠️ Migration tiếp theo trong repo là **`V31`** (đã có `V1`–`V30`, không có
> khoảng trống). Phải merge sau `V30__trash_purge_and_conflict.sql`.

## 0. Phạm vi & giả định cần xác nhận

Các điểm dưới đây là lựa chọn kỹ thuật của DEV khi Quyết định 8 chưa chốt hết
chi tiết implementation — **người dùng đã xác nhận toàn bộ ngày 2026-09-14**
(xem mục 8, đã chốt câu trả lời từng mục):

1. **Tên task/queue:** dùng `task = 'video'`, queue `q.video` (env
   `QUEUE_VIDEO_NAME`, mặc định `q.video`) — **không** dùng lại
   `TASK_VIDEO_SUMMARY` đã có sẵn trong `DigitizationRun`, vì task đó (theo
   Quyết định 8 gốc digitization-pipeline.md — `dependsOnRunId` trỏ tới run
   `stt`) mang ngữ nghĩa khác: tóm tắt nội dung *đã phiên âm*, còn task này
   là phân tích *hình ảnh* video, không phụ thuộc STT.
2. **Client đọc trạng thái:** chưa có endpoint REST nào phù hợp cho việc
   client poll trạng thái 1 task của 1 document (endpoint gần nhất,
   `GET /runs/{runId}` ở `ReviewController`, đòi hỏi biết trước `runId` +
   quyền `PERM_digitization_review.manage` — không dành cho client thông
   thường). Spec này **thêm 1 endpoint đọc mới**, tối thiểu, không đụng
   `ReviewController` (mục 3).
3. **Đồng bộ vào `document_digitization_status`:** bảng read-model này
   (V26, `digitization-results-store.md` §2.2) có đúng 1 điểm ghi hợp lệ theo
   javadoc hiện tại — `DigitizationRunLifecycleServiceImpl` (Java). Vì worker
   video ghi thẳng SQL vào `digitization_run` (Quyết định 8), **không** đi
   qua class đó, bảng `document_digitization_status` sẽ **không tự cập nhật**
   nếu không xử lý thêm. Spec này chọn: thêm cột `video_status` +
   **DB trigger** (`AFTER UPDATE ... ON digitization_run WHEN task='video'`)
   tự upsert vào `document_digitization_status` — giữ nguyên bất biến
   "Java code chỉ có 1 điểm ghi" cho *các task khác*, nhưng cho phép task
   `video` được đồng bộ dù ghi bằng SQL thuần từ một tiến trình ngoài JVM
   backend. Phương án thay thế (worker gọi API nội bộ thay vì trigger DB) —
   xem mục 8.
4. **Migration số `V31`** và tên task `video` giả định chưa bị chiếm bởi
   migration nào khác đang phát triển song song — cần xác nhận trước khi
   merge.

## 1. Tóm tắt kỹ thuật

- Thêm queue `q.video` (+ `.retry`/`.dlq`), theo đúng khung `ai.jobs`/DLX đã
  có (`RabbitConfig`, Quyết định 2 + Quyết định 7).
- `SourceSeparationJobRunner` publish **cả 2** thành phần tách được từ video
  có audio: `audio` → `q.stt` (không đổi), `video` → `q.video` (mới —
  `dispatchable` đổi từ `false` thành `true`).
- `DigitizationDispatchService.resolveTask()` thêm nhánh `video/*` → task
  `video`; `QueueProperties` thêm `videoName`.
- Backend **vẫn tạo `digitization_run`** trước khi publish (để có `runId`
  ổn định trong message), nhưng **không** consume `q.result` cho task này —
  worker tự `UPDATE digitization_run` (cột trạng thái) bằng credential
  Postgres riêng, phạm vi ghi hẹp (role DB mới, `GRANT UPDATE` trên đúng các
  cột cần thiết, không `GRANT` bảng khác).
- Kết quả suy luận (embedding, mô tả...) worker tự ghi OpenSearch/Elasticsearch (Qdrant đã gỡ 2026-10-10) —
  ngoài phạm vi migration/entity của spec này.
- Thêm 1 endpoint REST tối thiểu để client poll trạng thái task `video` của
  1 document + trigger DB đồng bộ `document_digitization_status.video_status`.

## 2. Data model

```sql
-- V31__digitization_video_task.sql

-- 2.1 Cho phép task 'video' ở digitization_run (Quyết định 8).
ALTER TABLE digitization_run DROP CONSTRAINT digitization_run_task_check;
ALTER TABLE digitization_run ADD CONSTRAINT digitization_run_task_check
    CHECK (task IN ('ocr', 'stt', 'sed', 'video_summary', 'video'));

-- 2.2 Read-model: thêm cột trạng thái riêng cho task video (mục 0 điểm 3).
ALTER TABLE document_digitization_status
    ADD COLUMN video_status VARCHAR(20) NOT NULL DEFAULT 'not_applicable';

-- 2.3 Trigger đồng bộ digitization_run(task='video') -> document_digitization_status.
-- Cần thiết vì worker ghi thẳng SQL vào digitization_run, không qua
-- DigitizationRunLifecycleServiceImpl (điểm ghi Java duy nhất cho các task khác).
CREATE OR REPLACE FUNCTION sync_video_status_to_document() RETURNS TRIGGER AS $$
BEGIN
    IF NEW.task <> 'video' THEN
        RETURN NEW;
    END IF;
    INSERT INTO document_digitization_status (document_id, video_status, overall_status, updated_at)
    VALUES (
        NEW.document_id,
        CASE NEW.processing_status
            WHEN 'queued' THEN 'queued'
            WHEN 'running' THEN 'processing'
            WHEN 'succeeded' THEN 'confirmed' -- task video không qua FR-18, coi succeeded = confirmed
            WHEN 'failed_retryable' THEN 'processing'
            WHEN 'failed_permanent' THEN 'failed'
            WHEN 'cancelled' THEN 'failed'
            ELSE 'not_applicable'
        END,
        'not_applicable', -- overall_status: cột này do DigitizationRunLifecycleServiceImpl tính cho các task khác; trigger KHÔNG ghi đè, xem ghi chú dưới bảng
        now()
    )
    ON CONFLICT (document_id) DO UPDATE
        SET video_status = EXCLUDED.video_status,
            updated_at = now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_sync_video_status
    AFTER INSERT OR UPDATE OF processing_status ON digitization_run
    FOR EACH ROW
    WHEN (NEW.task = 'video')
    EXECUTE FUNCTION sync_video_status_to_document();

-- 2.4 Role Postgres riêng, phạm vi hẹp cho video worker (mục 0 điểm 3, rủi ro mục 7).
-- SỬA 2026-09-14: bản nháp trước dùng cú pháp biến của psql (`:'...'`) — Flyway
-- chạy qua JDBC, KHÔNG hiểu cú pháp này, migration sẽ lỗi cú pháp SQL nếu giữ
-- nguyên. Đồng thời: Flyway lưu checksum nội dung migration đã áp dụng — nếu
-- nhúng mật khẩu vào migration (kể cả qua flyway placeholder ${...}), lần sau
-- đổi/rotate mật khẩu sẽ làm checksum lệch, Flyway fail ở deploy tiếp theo.
-- => Role tạo KHÔNG có mật khẩu ở đây; đặt/rotate mật khẩu là một bước vận
-- hành RIÊNG, chạy NGOÀI Flyway (xem hướng dẫn "Task 1" trong hội thoại/README
-- vận hành — không phải file migration này).
CREATE ROLE digitization_video_worker LOGIN;
-- model_name/model_version/metrics bổ sung 2026-09-14 (xác nhận mục 8): bắt
-- buộc để không vi phạm NFR-09 (digitization-pipeline.md mục 3 — "Mọi bản ghi
-- kết quả gắn model_name + model_version để truy vết/chạy lại"). lease_expires_at
-- PHẢI được worker cập nhật định kỳ trong lúc chạy (không chỉ lúc xong) — hợp
-- đồng bắt buộc với team AI worker (mục 4.4, xác nhận mục 8), để
-- DigitizationRunLeaseReconciliationJob hiện có vẫn phát hiện được job treo.
GRANT UPDATE (processing_status, started_at, completed_at, last_error,
              attempt_count, lease_expires_at, worker_id, updated_at,
              model_name, model_version, metrics)
    ON digitization_run TO digitization_video_worker;
GRANT SELECT (id, document_id, task, processing_status) ON digitization_run
    TO digitization_video_worker;
-- KHÔNG cấp bất kỳ quyền nào trên content_segment/document/các bảng khác.
```

> **Lưu ý ON CONFLICT ở 2.3:** giả định `document_digitization_status` đã có
> unique/PK trên `document_id` (đúng, xem V26/`DocumentDigitizationStatus.java`
> — `document_id` là `@Id`). Cột `overall_status` trong `INSERT` giữ nguyên
> `'not_applicable'` chỉ dùng khi **chưa tồn tại** dòng (document lần đầu có
> job video mà chưa từng có OCR/STT) — nhánh `DO UPDATE` không đụng
> `overall_status` hiện có, để không phá logic tính `overall_status` mà
> `DigitizationRunLifecycleServiceImpl` đang sở hữu cho OCR/STT/SED. Cần DEV
> review kỹ SQL này với dữ liệu thật trước khi approve — đây là điểm dễ lỗi
> nhất trong migration (mục 8).

## 3. API contract

| Method | Endpoint | Request | Response |
|---|---|---|---|
| GET | `/documents/{id}/video-status` | path: `id` | `{ "documentId": "uuid", "status": "not_applicable\|queued\|processing\|confirmed\|failed", "updatedAt": "ISO-8601" }` |

- Đặt trong `DocumentsController` (base path `/documents`, khớp convention
  các endpoint đọc document khác — `GET /{id}`, `/{id}/content`,
  `/{id}/pages/{pageNo}/image`), **không** tạo controller/path riêng.
- Đọc trực tiếp `document_digitization_status.video_status` (mục 2.2) theo
  `document_id` — **không** đọc `digitization_run` trực tiếp (tránh lộ
  `worker_id`/`last_error` nội bộ ra client).
- Quyền: **`@PreAuthorize("hasAuthority('PERM_document.read')")`** — dùng lại
  đúng permission đang áp cho mọi endpoint đọc document khác ở
  `DocumentsController` (xác nhận 2026-09-14). **Không** dùng
  `PERM_digitization_review.manage` (đó là quyền cho cán bộ kiểm duyệt FR-18,
  task `video` không thuộc FR-18).
- Client tự poll endpoint này (không có push/WebSocket) — đúng theo Quyết
  định 8 ("client refresh để fetch trạng thái").

## 4. Luồng xử lý chính

### 4.1 Dispatch (sửa `SourceSeparationJobRunner`) — ~~thay thế 2026-09-23~~, xem spec 04 §4.1

1. Sau khi tách xong `ffmpeg` (không đổi, xem
   `digitization-pipeline-01-source-dispatch.md` §4.2 bước 4), tạo
   `videoSource` với `dispatchable = true` (đổi từ `false`).
2. Sau `digitizationDispatchService.publish(audioSource)` (không đổi), gọi
   thêm `digitizationDispatchService.publish(videoSource)`.
3. Không đổi behaviour cho tệp video **không có audio** track (nếu có, ffmpeg
   audio-only sẽ lỗi/tạo file rỗng) — **ngoài phạm vi spec này**, giữ nguyên
   hành vi hiện tại (không xử lý riêng edge case này ở đây).

### 4.2 Định tuyến (sửa `DigitizationDispatchService.resolveTask`)

```java
if (processFileType.startsWith("video/")) {
    return "video";
}
```
— thêm trước nhánh `audio/`/`image/`+`pdf` hiện có, không đổi thứ tự ưu
tiên (không xung đột vì mimeType không overlap).

### 4.3 Publish message

Dùng lại nguyên `DigitizationJobMessages.build(...)` (5 trường: `runId`,
`fileId`, `sourceId`, `filePath`, `originalFileType`, `processFileType`) —
**không** thêm trường mới. `processFileType` = mimeType video gốc (mp4/mov/
mkv/webm), giống `videoSource.mimeType` đã lưu ở Pha 1.

### 4.4 Worker tiêu thụ (ngoài repo này — chỉ mô tả hợp đồng)

- Worker consume `q.video`, ngay khi bắt đầu xử lý:
  1. `UPDATE digitization_run SET processing_status='running', worker_id=:id,
     lease_expires_at=now() + interval '2 minutes', updated_at=now() WHERE
     id=:runId`.
  2. **Bắt buộc** (xác nhận 2026-09-14, mục 8): lặp lại `UPDATE ...
     SET lease_expires_at=now() + interval '2 minutes', updated_at=now()`
     mỗi ≤ 60 giây trong suốt lúc xử lý (cùng tinh thần heartbeat 30s của
     Quyết định 2, chu kỳ dài hơn vì không dùng `q.heartbeat`) — đây là điều
     kiện bắt buộc để `DigitizationRunLeaseReconciliationJob` hiện có vẫn
     phát hiện được job treo cho task này (xem rủi ro mục 7).
  3. Xử lý xong: `UPDATE digitization_run SET processing_status=...,
     model_name=..., model_version=..., metrics=..., completed_at=now(),
     updated_at=now() WHERE id=:runId` bằng credential
     `digitization_video_worker` (mục 2.4).
  4. Ghi kết quả suy luận vào OpenSearch/Elasticsearch (Qdrant đã gỡ 2026-10-10) (ngoài phạm vi spec này).
- Worker **không** publish gì vào `q.result`/`q.heartbeat` cho task `video`
  (khác toàn bộ worker OCR/STT khác) — bước 2 ở trên là cơ chế thay thế,
  dùng chung cột `lease_expires_at` thay vì message `q.heartbeat`.

### 4.5 Đồng bộ read-model

Trigger `trg_sync_video_status` (mục 2.3) tự chạy khi worker `UPDATE`
`digitization_run` — không cần code Java thêm.

## 5. Test plan

- **Unit test:**
  - `DigitizationDispatchServiceTest`: `video/mp4` (và các mimeType video còn
    lại) → `resolveTask` trả về `"video"`, publish đúng `QUEUE_VIDEO_NAME`.
  - `SourceSeparationJobRunnerTest`: sau `processJob` thành công, verify
    `publish()` được gọi **2 lần** (audio + video), không còn assert
    "video never published" như test hiện tại — cập nhật lại test case cũ.
- **Integration test (Testcontainers Postgres + RabbitMQ):**
  - Nhập kho `video/mp4` có audio → sau khi `SourceSeparationJobRunner` chạy,
    `q.stt` **và** `q.video` đều nhận đúng 1 message; 2
    `digitization_run` (task `stt`, task `video`) đều `dispatchable=true`
    ở `digitization_source` tương ứng.
  - Giả lập worker: `UPDATE digitization_run SET processing_status='succeeded'
    WHERE task='video'` bằng SQL thuần (không qua Java) → verify
    `document_digitization_status.video_status = 'confirmed'` sau khi trigger
    chạy, **không** đổi `overall_status` hiện có của document đó.
  - Verify role `digitization_video_worker` **không** có quyền
    `UPDATE`/`INSERT`/`DELETE` trên `content_segment`, `document`,
    `digitization_source` (test bảo mật — kết nối bằng role đó, assert
    `permission denied` khi thử ghi các bảng ngoài phạm vi).
  - `GET /documents/{id}/video-status` trước khi có run → `not_applicable`;
    sau khi worker update `succeeded` → `confirmed`; user không có
    `PERM_document.read` trên document đó → `403`.
  - Job `video` không cập nhật `lease_expires_at` quá hạn →
    `DigitizationRunLeaseReconciliationJob` vẫn phát hiện + requeue đúng như
    task khác (verify cơ chế heartbeat qua cột dùng chung, mục 4.4 bước 2).

## 6. Task breakdown

- [ ] Task 1: Migration `V31__digitization_video_task.sql` (mục 2) — bao gồm
      CHECK constraint, cột `video_status`, trigger, role DB (**không** đặt
      mật khẩu trong migration — xem sửa lỗi mục 2.4) + GRANT
      `model_name`/`model_version`/`metrics`.
  - [ ] Task 1b (vận hành, **người dùng tự làm sau khi migration chạy xong**,
        không thuộc code): đặt mật khẩu cho role `digitization_video_worker`
        theo đúng runbook
        [`docs/00-overview/runbooks/video-worker-db-password.md`](../../00-overview/runbooks/video-worker-db-password.md).
- [ ] Task 2: `DigitizationRun.TASK_VIDEO` constant + thêm vào `VALID_TASKS`
      (`DigitizationRunLifecycleServiceImpl`).
- [ ] Task 3: `QueueProperties.videoName` + `forTask()`; `application.yml`
      thêm `video-name: ${QUEUE_VIDEO_NAME:q.video}`.
- [ ] Task 4: `RabbitConfig` — bean queue/retry/dlq/binding cho `q.video`
      (copy pattern khối `q.stt`).
- [ ] Task 5: `DigitizationDlqAlertJob` — thêm `checkDlq(videoName + ".dlq")`.
- [ ] Task 6: `DigitizationDispatchService.resolveTask` — nhánh `video/*`
      (mục 4.2).
- [ ] Task 7: `SourceSeparationJobRunner` — `dispatchable=true` cho
      `videoSource` + gọi `publish(videoSource)` (mục 4.1); cập nhật lại
      Javadoc/comment mô tả hành vi cũ (dòng nói "video is never
      auto-published").
- [ ] Task 8: Endpoint `GET /documents/{id}/video-status` trong
      `DocumentsController` (mục 3), `@PreAuthorize("hasAuthority('PERM_document.read')")`.
- [ ] Task 9: Test theo mục 5.
- [x] Task 10 (phối hợp ngoài repo, không phải code backend): bàn giao hợp
      đồng mục 4.4 cho team AI worker — credential `digitization_video_worker`,
      tên cột cần update (bao gồm `model_name`/`model_version`/`metrics`),
      **yêu cầu bắt buộc cập nhật `lease_expires_at` mỗi ≤ 60s trong lúc xử
      lý** (mục 4.4 bước 2), tên queue `q.video`. **Đã thông báo cho team AI
      worker (xác nhận 2026-09-14)** — format message giống hệt `q.stt` hiện
      tại (5 trường `runId`/`fileId`/`sourceId`/`filePath`/`originalFileType`/
      `processFileType`, mục 4.3), không có gì khác biệt cần họ học thêm
      ngoài hợp đồng DB ở mục 4.4.

## 7. Rủi ro kỹ thuật

- **Worker có quyền ghi trực tiếp PostgreSQL** — lần đầu tiên trong pipeline
  này một worker AI không đi qua backend để ghi trạng thái (mọi task khác
  đều qua `q.result` → `DigitizationResultConsumer`). Giảm thiểu: role DB
  phạm vi cực hẹp (mục 2.4), review bảo mật riêng trước khi merge migration
  (mục 8) — **đây là rủi ro cao nhất của toàn bộ thay đổi**, không chỉ vấn
  đề kỹ thuật thuần tuý.
- **Đối soát job treo phụ thuộc worker tuân thủ hợp đồng heartbeat** (mục
  4.4 bước 2, xác nhận 2026-09-14): khác OCR/STT (báo qua `q.heartbeat`,
  backend chủ động ghi `lease_expires_at`), task `video` yêu cầu **chính
  worker** tự cập nhật `lease_expires_at` mỗi ≤ 60s — `DigitizationRunLeaseReconciliationJob`
  hiện có vẫn hoạt động đúng nếu worker làm đúng, nhưng nếu team AI worker
  quên implement bước này (chỉ update 1 lần lúc xong, như bản nháp ban đầu),
  job chết giữa chừng sẽ kẹt `running` vĩnh viễn mà không ai phát hiện. Giảm
  thiểu: Task 10 (mục 6) nêu rõ yêu cầu này trong hợp đồng bàn giao; test
  tích hợp mục 5 verify riêng cơ chế này trước khi coi worker đã tuân thủ
  đúng hợp đồng.
- **Trigger DB (mục 2.3) là logic nghiệp vụ nằm ngoài tầm kiểm soát/test
  Java thông thường** — khó debug hơn code Java, dễ bị quên khi có người sau
  này sửa `digitization_run` mà không biết trigger tồn tại. Giảm thiểu: test
  integration mục 5 cover trigger trực tiếp bằng SQL thuần (không qua Java)
  để không phụ thuộc giả định ai gọi.
- **Tên cột/kiểu dữ liệu worker sẽ `UPDATE`** (mục 2.4 GRANT list, đã bổ sung
  `model_name`/`model_version`/`metrics` 2026-09-14) vẫn là giả định của DEV
  dựa trên các cột đã có sẵn ở `digitization_run` cho các task khác — cần
  xác nhận lại với team AI worker khi bàn giao hợp đồng (Task 10, mục 6) họ
  có cần ghi thêm cột nào khác không phát sinh trong danh sách hiện tại.

## 8. Điểm người dùng đã xác nhận (2026-09-14)

- [x] **Task `video` riêng, không dùng lại `TASK_VIDEO_SUMMARY`** (mục 0
      điểm 1) — đồng ý giữ nguyên đề xuất.
- [x] **Dùng DB trigger** để đồng bộ `document_digitization_status`, không
      thêm API nội bộ song song (mục 0 điểm 3, mục 2.3/4.5) — đồng ý.
- [x] **Bổ sung `model_name`/`model_version`/`metrics`** vào GRANT UPDATE
      list (mục 2.4) — để không vi phạm NFR-09 (truy vết model), đã cập nhật
      migration + hợp đồng worker (mục 4.4 bước 3).
- [x] **Mật khẩu role DB qua biến môi trường** `VIDEO_WORKER_DB_PASSWORD`,
      cùng cơ chế `RABBITMQ_DEFAULT_PASS` hiện có ở
      `docker-compose.yml`/`stg/.env` — không cần công cụ secret mới.
- [x] **Chấp nhận rủi ro mất đối soát job treo, kèm mitigation bắt buộc**:
      worker phải tự cập nhật `lease_expires_at` mỗi ≤ 60s trong lúc xử lý
      (mục 4.4 bước 2) để `DigitizationRunLeaseReconciliationJob` hiện có vẫn
      phát hiện được job treo — không chấp nhận phương án "chỉ update 1 lần
      lúc xong" ở bản nháp đầu.
- [x] **Endpoint mới dùng `PERM_document.read`**, đặt trong
      `DocumentsController` tại `GET /documents/{id}/video-status` (mục 3) —
      đúng convention các endpoint đọc document khác, không dùng
      `PERM_digitization_review.manage`.
- [x] **(ADR) Role DB riêng, ghi trực tiếp** — không chuyển sang proxy nội bộ
      (xem `digitization-pipeline.md` mục 7 câu hỏi mở đã cập nhật).
