# Tech Spec: digitization-review-01-ocr

> File: `docs/03-dev/specs/digitization-review-01-ocr.md` — Owner: DEV
> Nguồn story: `docs/02-ba/stories/digitization-review-01-ocr.md`
> Status: Approved

> **Phụ thuộc — tất cả đã Approved (2026-09-07):** story nguồn, SA
> `digitization-review.md`, `docs/03-dev/specs/digitization-results-store.md`
> (`V25`/`V26` — `digitization_run` với `review_state`/`review_deadline_at`/
> `confirm_source`/`confirmed_by`/`confirmed_at`/`first_viewed_at`/
> `first_viewed_by`/`edited_segment_count`/`first_edit_at`, `content_segment`
> với `current_text`/`current_time_*`/`current_revision_no`/`is_edited`,
> `segment_revision`), và `docs/03-dev/specs/digitization-pipeline-02-ocr-stt-
> processing.md` (`V27`, nguồn `DigitizationRunLifecycleService`/
> `digitization.completed` qua `outbox_event`). Migration mới ở spec này vẫn
> đánh số **`V28`** — tiếp theo `V27`; chạy migration thật theo đúng thứ tự
> `V25`→`V26`→`V27`→`V28` khi implement (số cao nhất thật sự trong
> `backend/src/main/resources/db/migration/` hiện tại vẫn là `V24`).

## 0. Phát hiện quan trọng khi khảo sát code — sửa lại giả định của SA

`docs/01-sa/architecture/digitization-review.md` mục 2 ghi "**Chưa có**
`@Scheduled`/`@EnableScheduling` trong repo". Grep thực tế
(`backend/src/main/java`) cho thấy điều này **không còn đúng**:
`BackendApplication.java` đã có `@EnableScheduling`, và đã có 8
`@Scheduled` job khác (`OutboxRetentionJob`, `DigitizationDispatchRetryJob`,
`SourceSeparationJobRunner`, `AuditPartitionMaintenanceJob`,
`AuditSealerJob`, `AuditCheckpointJob`, `AuditRetentionJob`,
`NotificationService`, `UploadCleanupJob`) — hạ tầng này được thêm ở một
slug khác sau khi SA viết ADR. **Hệ quả:** task "thêm `@EnableScheduling`"
nêu ở SA mục 4/5 **không còn cần làm** — `AutoConfirmScheduler`/
`ReviewStaleAlertJob` ở spec này chỉ cần thêm class `@Scheduled` mới, theo
đúng mẫu các job kể trên (`@Scheduled(fixedDelayString =
"${app.xxx.interval-ms:default}")`).

## 1. Tóm tắt kỹ thuật

Module mới `com.aismartstore.backend.digitization.review`, đọc/ghi qua
`DigitizationRunLifecycleService` đã có sườn ở `digitization-results-store.md`
§3 (bổ sung thêm method mới ở spec này, theo đúng tiền lệ
`digitization-pipeline-02` đã làm với `completeRunSuccess`/
`completeRunFailure`). Bao gồm:

- 1 migration `V28` (permission mới + 2 cột/1 index bổ sung mà 2 spec nguồn
  chưa có — mục 2).
- `ReviewController` (REST: hàng chờ, chi tiết run, sửa/xoá/thêm segment, xác
  nhận) — chỉ phục vụ `task ∈ {ocr, stt}` (`sed`/`video_summary` không qua
  màn hình này, đúng `review_state='not_applicable'` mặc định của chúng).
- `AutoConfirmScheduler` (Quyết định 1 SA — mỗi phút, `SKIP LOCKED`, lô 500).
- `ReviewStaleAlertJob` (Quyết định 2 SA — run `in_review` quá
  `ocr_stt.review_stale_hours`).
- Phần **đặc thù OCR** (bbox) và **đặc thù STT** (mốc thời gian + player) nằm
  ở `digitization-review-02-stt.md`; file này chỉ định nghĩa cơ chế **dùng
  chung** cho cả hai loại tác vụ (đúng phạm vi story BA).

## 2. Data model

**Không tạo lại** `digitization_run`/`content_segment`/`segment_revision` —
dùng nguyên trạng `V25`/`V26`. Chỉ thêm 1 migration bổ sung những gì 2 cột dữ
liệu mà story cần nhưng chưa có bảng nguồn nào tạo:

### `V28__digitization_review_queue_and_permissions.sql`

```sql
-- Chạy sau V27 (digitization-pipeline-02-ocr-stt-processing.md). Bổ sung cho
-- digitization-review (cả 3 file -01/-02/-03) - dùng chung 1 migration để
-- tránh 3 migration nhỏ lẻ chồng chéo cùng phạm vi "review".

-- Story 6: đánh dấu run in_review đã được cảnh báo "quá hạn xử lý" để
-- ReviewStaleAlertJob không ghi audit lặp lại mỗi chu kỳ quét cho cùng 1 run
-- (staleness tính từ first_edit_at đã có sẵn ở V25, không cần cột thời điểm
-- mới - chỉ cần cờ đã-cảnh-báo).
ALTER TABLE digitization_run ADD COLUMN stale_alerted BOOLEAN NOT NULL DEFAULT false;

-- Story 2 AC-5: "xoá đoạn" cần một cách để UI/API biết segment đang ở trạng
-- thái đã xoá mà KHÔNG phải join segment_revision mới nhất (đúng tinh thần
-- "cột hiện hành" của Quyết định 3 SA - digitization-results-store.md §2.2
-- chỉ có deleted trên segment_revision, chưa có trên content_segment).
ALTER TABLE content_segment ADD COLUMN is_deleted BOOLEAN NOT NULL DEFAULT false;

-- Poll cho ReviewStaleAlertJob (Story 6).
CREATE INDEX IDX_digitization_run_stale_unalerted
    ON digitization_run (first_edit_at)
    WHERE review_state = 'in_review' AND stale_alerted = false;

-- RBAC (rbac-group-scope.md V12 mẫu: permission tĩnh + role_permission).
-- 'digitization_review.manage' dùng chung cho hàng chờ/xem/sửa/xác nhận
-- (Quyết định 5 SA: chỉ R1 + chính chủ - permission chỉ là điều kiện cần,
-- kiểm tra uploader_sub=current_user vẫn làm ở tầng service/query).
-- 'document.transfer_ownership' dùng ở digitization-review-03 (Story 7).
INSERT INTO permission (code, description) VALUES
    ('digitization_review.manage', 'Xem hàng chờ, sửa, xác nhận kết quả số hoá (OCR/STT) của tài liệu do chính mình nạp'),
    ('document.transfer_ownership', 'Chuyển quyền sở hữu (người nạp) của một tài liệu');

INSERT INTO role_permission (role_code, permission_code) VALUES
    ('CAN_BO', 'digitization_review.manage'),
    ('QUAN_TRI', 'document.transfer_ownership');
```

**`system_config` (chỉ đăng ký ở `SystemConfigDefaults` Java, KHÔNG cần
migration** — bảng `system_config` cố ý không seed, xem comment ở
`V7__init_system_config.sql`):

| Key | Type | Default | Ghi chú |
|---|---|---|---|
| `ocr_stt.auto_confirm_enabled` | BOOLEAN | `true` | Kill switch Quyết định 1 SA |
| `ocr_stt.review_stale_hours` | NUMBER | `72` | Quyết định 2 SA |
| `ocr_stt.review_upcoming_deadline_hours` | NUMBER | `4` | **Đề xuất của DEV** trả lời câu hỏi mở BA (ngưỡng "sắp hết hạn", Story 1 AC-4) — cấu hình được thay vì hard-code UI, nhất quán với các tham số `ocr_stt.*` khác |

(`ocr_stt.auto_confirm_hours` đã có sẵn — `SystemConfigDefaults.
OCR_STT_AUTO_CONFIRM_HOURS`, không cần thêm.)

## 3. API contract

Base path `/digitization-review` (theo mẫu không tiền tố `/api` của các
controller hiện có, ví dụ `/documents`, `/groups`). Mọi endpoint yêu cầu
`@PreAuthorize("hasAuthority('PERM_digitization_review.manage')")` (role
`CAN_BO` duy nhất theo `V28`) **cộng thêm** kiểm tra tường minh trong service
`document.uploaderSub == currentUser.sub` — permission chỉ là điều kiện cần
(FR-08 chung), "chính chủ" là điều kiện đủ riêng của review (Quyết định 5
SA); vi phạm điều kiện đủ → 403 dù có permission.

| Method | Endpoint | Request | Response |
|---|---|---|---|
| GET | `/digitization-review/queue` | Query: `task=ocr\|stt`, `status=pending,in_review,stale,upcoming_deadline` (nhiều giá trị), `qualityFlag=poor`, `page`, `size` (≤100) | `Page<RunSummary>` — xem 3.1 |
| GET | `/digitization-review/runs/{runId}` | Query: `segmentPage`, `segmentSize` (≤100, mặc định 100) | `RunDetail` — xem 3.2. Side-effect: set `first_viewed_at`/`first_viewed_by` nếu đang NULL |
| PATCH | `/digitization-review/segments/{segmentId}` | `{ "expectedRevisionNo": int, "text"?: string, "reason"?: string }` (OCR chỉ dùng `text`; STT thêm `timeStartMs`/`timeEndMs` — xem `digitization-review-02-stt.md` §3) | `200 SegmentView` hoặc `409 { latestRevisionNo, latestText, latestTimeStartMs, latestTimeEndMs }` |
| DELETE | `/digitization-review/segments/{segmentId}` | Query: `expectedRevisionNo` | `200 SegmentView (isDeleted=true)` hoặc `409` (như trên) |
| POST | `/digitization-review/runs/{runId}/segments` | `{ "afterSegmentIndex"?: int, "text": string }` (thêm đoạn thủ công, `origin='manual'`) | `201 SegmentView` |
| POST | `/digitization-review/runs/{runId}/confirm` | `{}` | `200 RunSummary` (đã `confirmed`) hoặc `409` nếu `review_state` không phải `pending`/`in_review` |

Chuyển quyền sở hữu tài liệu (R3) nằm ở `digitization-review-03-ownership-
transfer.md` §3, dùng permission `document.transfer_ownership` (đã seed ở
`V28` mục 2 trên).

### 3.1 `RunSummary`

```json
{
  "runId": "uuid", "documentId": "uuid", "documentName": "string",
  "task": "ocr|stt",
  "reviewState": "pending|in_review|confirmed",
  "effectiveStatus": "pending|in_review|stale|upcoming_deadline|confirmed",
  "outputQualityFlag": "ok|poor",
  "lowConfidenceCount": 0, "segmentCount": 42,
  "reviewDeadlineAt": "ISO-8601|null",
  "firstEditAt": "ISO-8601|null",
  "isReplacingConfirmedRun": false
}
```

`effectiveStatus` là nhãn tính toán (không phải cột DB) dùng cho filter Story
1 AC-2/AC-4: `stale` = `review_state='in_review' AND first_edit_at <= now() -
ocr_stt.review_stale_hours`; `upcoming_deadline` = `review_state='pending' AND
review_deadline_at <= now() + ocr_stt.review_upcoming_deadline_hours`.
`isReplacingConfirmedRun` = tồn tại run khác cùng `(document_id, task)` với
`review_state='confirmed'` (Story 8 AC-5).

### 3.2 `RunDetail`

`RunSummary` + `sourceDurationMs` (từ `digitization_source.duration_ms` qua
`digitization_run.source_id` — xem lưu ý quan trọng ở
`digitization-review-02-stt.md` §1 về khác biệt so với chữ "document.
duration_ms" trong story BA) + `previousConfirmedRunId` (nullable, Story 8) +
`segments: Page<SegmentView>`.

### 3.3 `SegmentView`

```json
{
  "segmentId": "uuid", "segmentIndex": 0, "origin": "ai|manual",
  "aiText": "string", "currentText": "string",
  "currentRevisionNo": 3, "isEdited": true, "isDeleted": false,
  "confidence": 0.87,
  "pageNo": 1, "bboxPolygon": [[0,0]], "readingOrder": 0,
  "timeStartMs": null, "timeEndMs": null
}
```

### 3.4 Bổ sung `DigitizationRunLifecycleService`

Đã có sườn ở `digitization-results-store.md` §3. Spec này **đổi chữ ký**
`reviseSegment` (chưa implement, còn Draft — an toàn để sửa) và thêm 5
method mới, cùng tiền lệ "bổ sung method mới" mà `digitization-pipeline-02`
đã làm với `completeRunSuccess`/`completeRunFailure`:

```java
public interface DigitizationRunLifecycleService {
    // ... createRun, transitionProcessingStatus, writeSegments,
    //     writeSoundEvents, completeRunSuccess, completeRunFailure (đã có)

    // ĐỔI CHỮ KÝ (thêm expectedRevisionNo cho optimistic lock — US2 AC-3):
    // ném SegmentRevisionConflictException (-> 409) nếu
    // content_segment.current_revision_no != expectedRevisionNo. Nếu
    // run.review_state='pending' -> chuyển 'in_review' + first_edit_at=now()
    // (US2 AC-2). Nếu run.review_state='confirmed' -> KHÔNG đổi review_state,
    // ghi outbox_event('review.reconfirmed') thay vì không làm gì (US4 AC-2).
    ContentSegment reviseSegment(UUID segmentId, int expectedRevisionNo,
                                  String newText, Integer newTimeStartMs,
                                  Integer newTimeEndMs, String editedBy,
                                  String reason);

    // MỚI: xoá mềm — segment_revision(deleted=true) + content_segment.
    // is_deleted=true. Cùng quy tắc chuyển review_state như reviseSegment.
    ContentSegment deleteSegment(UUID segmentId, int expectedRevisionNo,
                                  String editedBy, String reason);

    // MỚI: thêm đoạn thủ công (US2 AC-5) — origin='manual', ai_text=""
    // (sentinel, cột NOT NULL nhưng không có bản AI để đối chiếu),
    // segment_index chèn sau afterSegmentIndex (hoặc cuối run nếu null).
    // Cùng quy tắc chuyển review_state như reviseSegment.
    ContentSegment addManualSegment(UUID runId, Integer afterSegmentIndex,
                                     String text, String createdBySub);

    // MỚI: chỉ ghi first_viewed_at/by nếu đang NULL, KHÔNG đổi review_state
    // (US2 AC — mở xem không dừng đồng hồ, Quyết định 2 SA).
    void markFirstViewed(UUID runId, String viewerSub);

    // MỚI: xác nhận thủ công (US4). Validate review_state IN
    // ('pending','in_review'); tính edited_segment_count = COUNT(*)
    // content_segment WHERE run_id=:runId AND is_edited=true; confirm_source
    // = edited_segment_count>0 ? 'manual_edited' : 'manual_unchanged';
    // set confirmed_by/confirmed_at; ghi outbox_event('review.confirmed');
    // nếu có run khác cùng (document_id, task) đang 'confirmed' -> chuyển
    // run đó 'superseded' cùng transaction (US8 AC-2).
    DigitizationRun confirmRun(UUID runId, String confirmedBySub);

    // MỚI (dùng bởi AutoConfirmScheduler) — 1 lệnh UPDATE...FROM subquery
    // FOR UPDATE SKIP LOCKED LIMIT :batchSize, review_state='pending' ->
    // 'confirmed', confirm_source='auto'. Trả về danh sách run đã xác nhận
    // để caller ghi audit/outbox trong CÙNG transaction.
    List<DigitizationRun> autoConfirmDueBatch(int batchSize);

    // MỚI (dùng bởi ReviewStaleAlertJob) — SELECT ... WHERE
    // review_state='in_review' AND first_edit_at <= now() - stale_hours AND
    // stale_alerted=false LIMIT :batchSize, rồi set stale_alerted=true.
    List<DigitizationRun> flagStaleInReviewBatch(int staleHours, int batchSize);
}
```

`SegmentRevisionConflictException` map sang HTTP 409 qua
`ApiExceptionHandler` hiện có (bổ sung 1 `@ExceptionHandler` mới, cùng mẫu
các exception nghiệp vụ khác).

## 4. Luồng xử lý chính

1. **Hàng chờ (Story 1)**: `ReviewController.queue` gọi
   `ReviewQueueRepository.search(currentUserSub, task, statusFilters,
   qualityFlag, pageable)` — luôn ép `document.uploader_sub =
   currentUserSub` trong `WHERE` (không tái dùng `BusinessDataScope` vì đó là
   "uploader HOẶC group-share", còn ở đây chỉ "uploader", hẹp hơn — đúng
   Quyết định 5 SA). Sắp theo rủi ro trước (US1 AC-6): `ORDER BY (CASE WHEN
   output_quality_flag='poor' THEN 0 ELSE 1 END), low_confidence_count DESC,
   review_deadline_at ASC`. Dùng `IDX_digitization_run_review_pollable` (đã
   có ở `V25`) cho phần lọc `review_state`/`review_deadline_at`, kết hợp
   index mới `IDX_digitization_run_stale_unalerted` cho nhánh `stale`.
2. **Mở xem (Story 2/3)**: `GET /runs/{runId}` kiểm tra ownership → gọi
   `markFirstViewed` (best-effort, không rollback response nếu lỗi ghi) →
   trả `RunDetail` (phân trang segment theo `segment_index`).
3. **Sửa/xoá/thêm segment (Story 2 OCR text, Story 3 STT text + mốc thời
   gian — quy tắc time-bound chi tiết ở file -02)**: controller gọi
   `reviseSegment`/`deleteSegment`/`addManualSegment` trong 1 transaction;
   409 khi lệch `expectedRevisionNo`, nội dung văn bản KHÔNG ghi vào
   `audit_log` — chỉ ghi `{segmentId, revisionNo, charsChanged, editedBy,
   editedAt}` (AC-6 US2), trỏ sang `segment_revision` cho chi tiết.
4. **Xác nhận (Story 4)**: `POST /runs/{runId}/confirm` → kiểm tra ownership
   → `confirmRun` → ghi `audit_log(digitization_review.confirmed)` (actor,
   documentId, runId, confirmSource) → response `RunSummary`. Sau khi
   `confirmed`, nếu R1 sửa tiếp qua endpoint PATCH ở bước 3, service tự phát
   `review.reconfirmed` (không có endpoint confirm riêng cho trường hợp này —
   đúng US4 AC-2, tránh 2 API cho cùng ý định).
5. **Tự xác nhận (Story 5)**: `AutoConfirmScheduler`
   (`@Scheduled(fixedDelayString =
   "${app.digitization.auto-confirm-interval-ms:60000}")`), theo mẫu
   `DigitizationRunLeaseReconciliationJob` đã có (1 `@Transactional` cho cả
   batch, không phải transaction riêng từng run — lựa chọn nhất quán với job
   tương tự đã viết ở `digitization-pipeline-02`, xem rủi ro mục 7):
   - Đọc `ocr_stt.auto_confirm_enabled`; nếu `false` → bỏ qua toàn bộ
     (US5 AC-2).
   - Lặp gọi `autoConfirmDueBatch(500)` tới khi trả về rỗng hoặc đã lặp 20
     lần/tick (10.000 run — đáp ứng "≥5.000 run/phút" khi có tồn đọng sau
     downtime, US5 AC-4).
   - Với mỗi run trả về: ghi `audit_log(digitization.auto_confirmed)` +
     `INSERT outbox_event('review.confirmed', {documentId, runId, task,
     confirmSource:'auto', confirmedAt, segmentCount})` — cùng transaction
     với UPDATE (US5 given/then).
6. **Quá hạn "đang xử lý dở" (Story 6)**: `ReviewStaleAlertJob`
   (`@Scheduled(fixedDelayString =
   "${app.digitization.review-stale-check-interval-ms:300000}")`, 5 phút —
   không cần sát 1 phút như auto-confirm vì đây chỉ là cảnh báo, không có
   side-effect chuyển trạng thái) gọi `flagStaleInReviewBatch(72, 500)` lặp
   tới khi rỗng; mỗi run trả về → ghi `audit_log(digitization.review_stale)`.
   Không tự xác nhận (US6 AC-3) — chỉ đổi `stale_alerted`, `effectiveStatus`
   ở hàng chờ tự suy ra lại từ `first_edit_at` (không phụ thuộc
   `stale_alerted`, cột này chỉ chống spam audit).
7. **Nhiều run (Story 8)**: hàng chờ (bước 1) chỉ liệt kê run
   `pending`/`in_review` (run `confirmed` cũ không xuất hiện, vẫn phục vụ tra
   cứu ngoài màn hình review — đúng Quyết định 6 SA); `RunDetail` trả thêm
   `previousConfirmedRunId` để FE gọi lại chính API `GET /runs/{id}` (chỉ
   đọc, FE tự khoá form sửa) dựng màn so sánh cạnh nhau, không cần endpoint
   riêng.

## 5. Test plan

**Unit test:**

- `DigitizationRunLifecycleServiceImplTest.reviseSegment`: đúng
  `expectedRevisionNo` → ghi `segment_revision` mới, `current_*` cập nhật,
  `ai_text` không đổi; sai `expectedRevisionNo` → ném
  `SegmentRevisionConflictException`, không có dòng `segment_revision` mới
  được tạo (rollback). `review_state='pending'` → chuyển `in_review` +
  `first_edit_at` set; gọi lần 2 → `first_edit_at` không đổi.
- `confirmRun`: 0 segment sửa → `manual_unchanged`; ≥1 segment `is_edited` →
  `manual_edited`; có run cũ `confirmed` cùng `(document_id, task)` → run cũ
  chuyển `superseded`; gọi trên run đã `confirmed`/`superseded` → lỗi
  nghiệp vụ rõ ràng (409).
- `autoConfirmDueBatch`: run có `auto_confirm_blocked=true` → không được
  chọn dù quá hạn (US5 AC-1); `review_deadline_at` chưa tới → không chọn;
  `ocr_stt.auto_confirm_enabled=false` → scheduler không gọi hàm này (test ở
  tầng scheduler, mock service).
- `flagStaleInReviewBatch`: run `in_review` đúng ngưỡng → được chọn 1 lần;
  gọi lại lần 2 (đã `stale_alerted=true`) → không được chọn lại.

**Integration test (Testcontainers Postgres, migration `V1..V28`):**

- `IDX_digitization_run_stale_unalerted`/permission `V28`: migration chạy
  sạch; `RolePermissionSeedTest` (mẫu đã có ở `rbac-group-scope.md`) mở rộng
  khớp `@PreAuthorize` mới.
- 2 request PATCH đồng thời (giả lập 2 tab) cùng `segmentId`,
  `expectedRevisionNo` giống nhau → đúng 1 request 200, request còn lại 409
  (US2 AC-3, kiểm optimistic lock thật dưới tải).
- `AutoConfirmScheduler` chạy trên 600 run `pending` quá hạn → sau 2 lần gọi
  `autoConfirmDueBatch(500)` (giả lập scheduler loop), toàn bộ 600 run
  `confirmed`, đúng 600 dòng `outbox_event(review.confirmed)`.
- 403: user B (không phải `uploader_sub`) gọi `GET /runs/{runId}` của
  document do A nạp → 403; R2/R3 gọi bất kỳ endpoint nào ở controller này →
  403 (không có permission `digitization_review.manage`).
- Hàng chờ 100.000 run `pending` (seed test) → `GET /queue?status=pending`
  trả trong thời gian hợp lý (đo thời gian, không assert cứng số p95 trong
  CI, chỉ cảnh báo nếu vượt ngưỡng lớn — theo mẫu benchmark hiện có của dự
  án).

## 6. Task breakdown

- [x] Task 1: Viết `V28__digitization_review_queue_and_permissions.sql`
      (mục 2) — chạy sau `V25`–`V27` được implement.
- [x] Task 2: Thêm 3 key vào `SystemConfigDefaults`
      (`ocr_stt.auto_confirm_enabled`, `ocr_stt.review_stale_hours`,
      `ocr_stt.review_upcoming_deadline_hours`).
- [x] Task 3: Sửa chữ ký `reviseSegment` + thêm 5 method mới vào
      `DigitizationRunLifecycleService`/`Impl` (mục 3.4).
- [x] Task 4: `SegmentRevisionConflictException` + handler trong
      `ApiExceptionHandler` (409).
- [x] Task 5: `ReviewQueueRepository` (query hàng chờ, sort rủi ro, filter
      `effectiveStatus`) + `ReviewController` (6 endpoint mục 3, không tính
      endpoint transfer-ownership ở file -03).
- [x] Task 6: `AutoConfirmScheduler` (mục 4 bước 5).
- [x] Task 7: `ReviewStaleAlertJob` (mục 4 bước 6).
- [x] Task 8: Cập nhật `RolePermissionSeedTest` (đã có ở
      `rbac-group-scope.md`) cho 2 permission mới. (Đã cập nhật thêm
      `RolePermissionFixtures` dùng chung cho các Controller IT khác.)
- [x] Task 9: Viết test theo mục 5. Unit test (Mockito, chạy xanh trong sandbox
      này) + Testcontainers IT (`ReviewControllerIT`,
      `DigitizationRunLifecycleServiceIT` bổ sung, `DigitizationSchemaConstraintsIT`
      bổ sung) — các IT này không chạy được trong sandbox hiện tại do bug môi
      trường V21 đã biết (xem ghi chú trong `DigitizationRunLifecycleServiceIT`
      class Javadoc mới); SQL của `ReviewQueueRepository` đã verify thủ công
      qua psql (container Postgres tạm, không qua Flyway).
- [x] Task 10: Xác nhận KHÔNG cần thêm `@EnableScheduling` (đã có sẵn — mục
      0) trước khi bàn giao, tránh một dev khác thêm trùng gây lỗi Spring
      context.

## 7. Rủi ro kỹ thuật

- ~~Phụ thuộc trực tiếp `digitization-results-store.md`/
  `digitization-pipeline-02` (cả hai Draft)~~ — cả 2 đã `Approved`
  2026-09-07, giữ 1 bảng `digitization_run` như spec này giả định; không
  cần sửa `ReviewQueueRepository`/`DigitizationRunLifecycleService`.
- **1 transaction cho cả batch 500 run** trong `AutoConfirmScheduler`/
  `ReviewStaleAlertJob` (thay vì "transaction riêng từng run" như câu chữ
  Quyết định 1 SA) — chọn theo đúng mẫu `DigitizationRunLeaseReconciliationJob`
  đã viết ở `digitization-pipeline-02` để nhất quán code base; vẫn an toàn
  nhờ `SKIP LOCKED` + điều kiện `review_state='pending'` trong câu UPDATE
  (không có instance nào xác nhận trùng), nhưng nếu 1 dòng trong batch gây
  lỗi (ví dụ ghi `outbox_event` thất bại), **toàn bộ 500 dòng rollback** —
  chấp nhận được ở quy mô hiện tại (job chạy lại đúng 1 phút sau), nhưng cần
  theo dõi log lỗi để không lặp mãi cùng 1 batch lỗi.
- **`edited_segment_count` tính lại (COUNT) tại thời điểm `confirmRun`**
  thay vì tăng dần mỗi lần sửa — tránh lỗi tăng đếm trùng khi sửa lại cùng 1
  segment nhiều lần, nhưng tốn 1 query COUNT mỗi lần xác nhận (chấp nhận
  được, chỉ chạy 1 lần/run).
- **`ocr_stt.review_upcoming_deadline_hours` là giá trị `[ĐX]` của DEV**
  (câu hỏi mở BA chưa có số chính thức) — có thể cần điều chỉnh sau khi có
  phản hồi người dùng thật, chỉ ảnh hưởng dữ liệu cấu hình, không ảnh hưởng
  schema/code.
- **`content_segment.is_deleted` (V28) không có ở `digitization-results-
  store.md`** — nếu spec đó khi duyệt tự thêm cột tương đương (ví dụ đặt tên
  khác), cần hợp nhất 2 migration để tránh cột trùng ý nghĩa khác tên.
- **US8 ("thay run đã duyệt bằng run số hoá lại") CHƯA được nối dây — bẫy
  đang chờ, xác minh lại code thật 2026-09-20:**
  - `UQ_digitization_run_active` (`V25`, mục 2 ở trên) là `UNIQUE (document_id,
    task) WHERE review_state NOT IN ('superseded', 'void')` — một run
    `confirmed` và một run `pending` **không thể** cùng tồn tại cho cùng
    `(document_id, task)`.
  - `createRun` (`DigitizationRunLifecycleServiceImpl`) **không** tự
    supersede run cũ trước khi tạo run mới — nó chỉ bắt
    `DataIntegrityViolationException` do vi phạm `UQ_digitization_run_active`
    rồi ném lại `IllegalStateException`.
  - Nhánh "supersede run cũ" ở mục 3.4 (`confirmRun` — "nếu có run khác cùng
    `(document_id, task)` đang `confirmed` → chuyển run đó `superseded` cùng
    transaction") **chưa từng chạy trong production**: đường ghi
    `review_state='superseded'` duy nhất khác là `transitionReviewState`, và
    method này **không có caller production nào** — chỉ
    `ReviewControllerIT`/`DocumentOwnershipControllerIT` gọi trực tiếp để mô
    phỏng ("superseded by a re-run - simulated directly", xem comment tại
    `ReviewControllerIT` dòng ~613). Hệ quả: **chưa có bất kỳ luồng thật nào
    tạo ra một run `superseded`** → nhánh `SearchIndexOutboxPoller` xử lý
    supersede (`deleteByRunId` khi có run `superseded` khác cùng
    `document_id`/`task`) cũng là code chết trong thực tế hiện nay, dù bản
    thân code đó không sai.
  - **Bẫy nằm ở `DigitizationDispatchService.publish()`**: khi `createRun`
    ném `IllegalStateException` (đã có run active), `publish()` bắt exception
    này rồi gọi `findActiveByDocumentAndTask(documentId, task)` để lấy lại
    run hiện có và dispatch tiếp vào đó. Nhưng
    `findActiveByDocumentAndTask` chỉ loại `superseded`/`void` — **không loại
    `confirmed`**. Vậy nếu sau này có chức năng "số hoá lại" (re-run OCR/STT
    cho một document đã có run `confirmed`) mà không tự supersede run cũ
    trước, `publish()` sẽ **dùng lại chính run đã được cán bộ xác nhận**, và
    kết quả AI mới ghi đè `content_segment` của run đó — xoá mất nội dung đã
    hiệu đính thủ công, không qua bất kỳ cảnh báo/409 nào.
  - **Chưa phải lỗi đang xảy ra** — hiện chưa có caller nào chạm tới nhánh
    này: `DigitizationDispatchRetryJob` (retry) chỉ lấy
    `dispatch_status='pending'`, còn cơ chế "rescue tài liệu bị bỏ sót" chỉ
    lấy document **chưa có** `digitization_source` nào — cả hai đều không
    đụng tới document đã có run `confirmed`. Bẫy chỉ kích hoạt khi có tính
    năng "số hoá lại" mới gọi thẳng `DigitizationDispatchService.publish()`
    (hoặc `createRun`) cho một `(document_id, task)` đã `confirmed`.
  - **Việc cần làm trước khi triển khai "số hoá lại"**: (1) quyết định điểm
    supersede run cũ (khi bắt đầu số hoá lại, không phải khi confirm run mới
    — thứ tự ngược lại so với comment hiện tại ở mục 3.4), (2) sửa
    `findActiveByDocumentAndTask` hoặc thêm biến thể loại trừ cả `confirmed`
    cho đúng ngữ cảnh "sắp tạo run mới", (3) nối lại nhánh supersede trong
    `confirmRun`/`SearchIndexOutboxPoller` với luồng thật thay vì chỉ có
    test mô phỏng trực tiếp qua `transitionReviewState`.
- **(Đã sửa 2026-09-20) `edited_segment_count`/`confirm_source` không được
  tính lại khi sửa đoạn trên run đã `confirmed`.**
  `applyReviewStateEditSideEffect` (`DigitizationRunLifecycleServiceImpl`), ở
  nhánh `review_state='confirmed'`, trước đây chỉ ghi
  `outbox_event('review.reconfirmed')` với `run.getConfirmSource()` **cũ**
  (giá trị `confirmRun()` tính một lần duy nhất lúc xác nhận), không tính lại
  hai trường này. Hệ quả trước khi sửa: duyệt khi chưa sửa gì
  (`manual_unchanged`, 0), sau đó sửa một đoạn → `digitization_run` vẫn ghi
  `manual_unchanged`/0 vĩnh viễn — trong khi
  `SearchIndexOutboxPoller.normalizeConfirmSource()` suy `confirm_source`
  **theo từng segment** từ `content_segment.is_edited` và ghi đúng `'manual'`
  vào OpenSearch cho riêng segment đó. Hai nguồn lệch nhau, và
  `MetadataFilterSpecifications.hasConfirmSource()` (filter tìm kiếm theo
  `confirm_source=manual|auto`) đọc thẳng cột Postgres nên nhận giá trị cũ.
  **Đã sửa:** trích chung công thức tính (`ConfirmSourceComputation`/
  `computeConfirmSource`, dùng lại đúng logic `confirmRun()` đã có -
  `editedSegmentCount = COUNT content_segment WHERE run_id=... AND
  is_edited=true`, `confirmSource = editedSegmentCount>0 ? manual_edited :
  manual_unchanged`) và gọi lại đúng công thức đó trong nhánh `confirmed` của
  `applyReviewStateEditSideEffect`, trước khi ghi `review.reconfirmed`. Tác
  dụng phụ có chủ đích: một run từng `auto`-confirm mà sau đó bị người dùng
  sửa tay sẽ chuyển `confirm_source` thành `manual_edited` (không còn giữ
  `auto`) — đúng tinh thần "một khi người dùng đã chạm vào nội dung thì không
  còn là tự động nữa", nhất quán với cách OpenSearch tính theo segment. Test:
  `DigitizationRunLifecycleServiceImplTest` (`reviseSegment_onAConfirmedRun...`
  2 test case, bao gồm case xuất phát từ `auto`).
