# Tech Spec: chunked-file-upload-03-session-cleanup

> File: `docs/03-dev/specs/chunked-file-upload-03-session-cleanup.md` — Owner: DEV
> Nguồn story: `docs/02-ba/stories/chunked-file-upload-03-session-cleanup.md`
> Nguồn spec nền tảng: `docs/03-dev/specs/chunked-file-upload-01-ingest-chunked-upload.md`
> Status: Approved

## 0. Phát hiện quan trọng từ khảo sát code (đọc trước khi review phần còn lại)

AC-5 của story giả định có sẵn "scheduled job dọn `UploadBatch` hết hạn" để
story này mở rộng. Khảo sát code thật (`grep -rn "@Scheduled"` /
`@EnableScheduling` trên toàn bộ `backend/src/main/java`) cho kết quả:
**không có bất kỳ `@Scheduled` component nào trong codebase hiện tại**, và
`BackendApplication` chỉ có `@EnableAsync`, chưa có `@EnableScheduling`.

Cơ chế "dọn" `UploadBatch` hiện có thực chất là **lazy expiry theo request**,
không phải job nền định kỳ: `UploadBatchService.loadOpenBatch()` và
`getStatus()` chỉ đánh dấu `status=EXPIRED` khi có request chạm tới batch đó
sau `expires_at`, không có vòng quét chủ động nào chạy trong nền. Ngoài ra
`UploadBatch` (entity `com.aismartstore.backend.ingest.UploadBatch`) **không
có cột `temp_file_path`** — batch chưa từng giữ 1 temp file sống qua nhiều
request (`DocumentsService.bufferToTempFile` tạo và tự xoá temp file trong
cùng 1 request qua `finally`), nên cơ chế lazy hiện tại không cần xoá file gì
cả.

**Cách xử lý trong spec này (tôn trọng đúng 2 ràng buộc của story):**

- AC-5 đòi "một job dọn dẹp chung, không phải hai cơ chế khác nhau" — tinh
  thần đó vẫn giữ được: đây sẽ là **scheduled job đầu tiên và duy nhất**
  trong toàn bộ codebase, không tạo ra job thứ hai nào khác.
- Mục "Ngoài phạm vi" của story ghi rõ "Dọn dẹp cho `UploadBatch` — cơ chế đã
  có sẵn, không thuộc phạm vi story" → spec này **không đụng vào**
  `UploadBatchService`/lazy-expiry của `UploadBatch`, không biến nó thành
  active sweep. Job mới chỉ quét `upload_session`.
- Vì không có job cũ để "mở rộng" theo đúng nghĩa đen, class mới được đặt
  tên trung lập (`UploadCleanupJob`, không phải `UploadSessionCleanupJob`)
  và đặt trong package `com.aismartstore.backend.upload` (cùng package
  entity `UploadSession`) — nếu sau này có story khác muốn chuyển
  `UploadBatch` từ lazy sang active sweep, đây là nơi tự nhiên để thêm bước
  quét thứ hai vào **cùng một `@Scheduled` method/class**, giữ đúng cam kết
  "một job" của AC-5 cho tương lai.
- Đây là điểm lệch giữa giả định của story và thực tế code — **đã xác nhận
  với người duyệt (2026-08-21): chấp nhận tạo job nền mới riêng cho
  `upload_session`** theo đúng cách tiếp cận trên, không quay lại phương án
  lazy-expiry-only (không thoả AC-1/NFR "đóng tab, không quay lại" nếu không
  có job chủ động quét).

## 1. Tóm tắt kỹ thuật

- Thêm **scheduled job mới** `UploadCleanupJob`
  (`com.aismartstore.backend.upload`, `@Scheduled(fixedDelayString =
  "${app.upload.session-cleanup-interval-ms:900000}")`, mặc định 15 phút) —
  job nền đầu tiên của hệ thống (xem mục 0). Thêm `@EnableScheduling` vào
  `BackendApplication` (hiện chỉ có `@EnableAsync`).
- Job gọi 1 phương thức mới `UploadSessionService.findDueForExpiry(...)` (đọc,
  không transaction) để lấy danh sách `id` ứng viên
  (`status='OPEN' AND expires_at < now()`, giới hạn 500 dòng/lượt chạy, sắp
  theo `expires_at` tăng dần — dùng đúng index `IDX_upload_session_status_expires_at`
  đã tạo sẵn ở migration `V16` của spec 01), sau đó gọi
  `UploadSessionService.expireIfDue(id)` (mới, `@Transactional`, riêng từng
  session) cho mỗi id.
- `expireIfDue`: khoá row bằng `findByIdForUpdate` (đã có ở spec 01) rồi
  **kiểm tra lại điều kiện `status=OPEN AND expires_at < now()` trong lock**
  trước khi set `EXPIRED` — tránh race với 1 request chunk đang gia hạn
  `expires_at` đúng lúc job chạy (xem mục 4.2). Sau khi transaction DB commit,
  job xoá `temp_file_path` trên đĩa (`Files.deleteIfExists`, nuốt mọi lỗi,
  chỉ log, không throw).
- Không tạo bảng/entity mới, không sửa entity `UploadSession` hay migration
  `V16` (xem mục 2). Không sửa `UploadBatchService`/hành vi `UploadBatch`
  (ngoài phạm vi, xem mục 0).
- Endpoint `chunk`/`complete` đã có sẵn logic từ chối `status != OPEN` bằng
  `409 UPLOAD_SESSION_NOT_OPEN` (spec 01 mục 4.2/4.4) — **đủ để thoả AC-4**,
  không cần sửa gì thêm ở tầng API. Việc còn thiếu chỉ là kiểm chứng bằng
  test tích hợp end-to-end: job thật sự chạy và flip session sang `EXPIRED`,
  rồi gọi `PUT chunk` lên đúng session đó xác nhận nhận đúng `409` (spec 01
  mới test bằng cách set thẳng `status=EXPIRED` trong dữ liệu test, chưa test
  qua đường job thật).

## 2. Data model

Không tạo bảng/cột mới. Dùng nguyên `upload_session` đã định nghĩa ở
`chunked-file-upload-01-ingest-chunked-upload.md` mục 2.1 (migration `V16`),
cụ thể job này chỉ đọc/ghi các cột: `status`, `expires_at`, `temp_file_path`,
`updated_at`. Index phục vụ query của job (`IDX_upload_session_status_expires_at
ON upload_session (status, expires_at)`) đã được tạo sẵn ở `V16`, không cần
migration mới cho story này.

Thêm 1 method mới vào `UploadSessionRepository` (đã có ở spec 01, package
`com.aismartstore.backend.upload`):

```java
List<UploadSession> findByStatusOrderByExpiresAtAsc(String status, Pageable pageable);
```

(job tự lọc `expires_at < now()` ở tầng service khi lặp qua kết quả, hoặc
dùng derived query `findByStatusAndExpiresAtBeforeOrderByExpiresAtAsc(String
status, OffsetDateTime cutoff, Pageable pageable)` — ưu tiên cách thứ hai để
đẩy điều kiện `expires_at` xuống SQL, tận dụng đúng composite index).

## 3. API contract

Không có API mới — job nội bộ chạy nền (`@Scheduled`), không có endpoint HTTP
nào phơi ra ngoài. Hành vi của 3 endpoint đã có ở spec 01
(`PUT /upload-sessions/{id}/chunks`, `GET /upload-sessions/{id}`,
`POST /upload-sessions/{id}/complete`) **giữ nguyên, không sửa** — mã lỗi
`409 UPLOAD_SESSION_NOT_OPEN` đã đủ đáp ứng AC-4 khi session đã bị job này
chuyển sang `EXPIRED` (xem mục 1 và mục 5).

## 4. Luồng xử lý chính

### 4.1 `UploadCleanupJob` (mới, `com.aismartstore.backend.upload`)

```java
@Component
public class UploadCleanupJob {
    private static final Logger log = LoggerFactory.getLogger(UploadCleanupJob.class);
    private static final int BATCH_SIZE = 500;

    private final UploadSessionService uploadSessionService;

    @Scheduled(fixedDelayString = "${app.upload.session-cleanup-interval-ms:900000}")
    public void run() {
        long startedAt = System.currentTimeMillis();
        List<UUID> candidateIds = uploadSessionService.findExpiredSessionIds(BATCH_SIZE);
        int expired = 0, filesDeleted = 0, filesMissing = 0, fileErrors = 0;
        for (UUID id : candidateIds) {
            UploadSessionService.ExpiryOutcome outcome = uploadSessionService.expireIfDue(id);
            if (outcome == null) continue; // đã bị request khác xử lý trước (race, mục 4.2)
            expired++;
            switch (outcome.deleteTempFile()) {
                case DELETED -> filesDeleted++;
                case ALREADY_MISSING -> filesMissing++;
                case DELETE_FAILED -> fileErrors++;
            }
        }
        log.info(
            "upload_session cleanup: scanned={} expired={} tempFilesDeleted={} tempFilesAlreadyMissing={} tempFileDeleteErrors={} durationMs={}",
            candidateIds.size(), expired, filesDeleted, filesMissing, fileErrors,
            System.currentTimeMillis() - startedAt);
    }
}
```

Log 1 dòng `INFO` duy nhất mỗi lần job chạy — kể cả khi `scanned=0` (chạy mà
không có gì để dọn vẫn log, để phân biệt "job có chạy nhưng không có rác" với
"job không chạy" khi vận hành theo dõi) — thoả AC-6. Theo đúng convention log
đã có (`DocumentsService` dùng `LoggerFactory.getLogger` + SLF4J, không có
logging framework nào khác trong repo).

### 4.2 `UploadSessionService` — 2 method mới (bổ sung vào service đã định
nghĩa ở spec 01 mục 4, không sửa `init`/`appendChunk`/`getStatus`/`complete`
đã có)

```java
/** Đọc, không transaction — chỉ lấy id, tránh giữ lock trong lúc job lặp gọi expireIfDue. */
public List<UUID> findExpiredSessionIds(int limit) {
    return uploadSessionRepository
        .findByStatusAndExpiresAtBeforeOrderByExpiresAtAsc(
            UploadSessionStatus.OPEN, OffsetDateTime.now(), PageRequest.of(0, limit))
        .stream().map(UploadSession::getId).toList();
}

public enum TempFileDeleteResult { DELETED, ALREADY_MISSING, DELETE_FAILED }
public record ExpiryOutcome(String tempFilePath) {
    TempFileDeleteResult deleteTempFile() { ... }
}

/**
 * Race an toàn với chunk PUT đang gia hạn expires_at (spec 01 mục 4.2): khoá
 * row bằng findByIdForUpdate rồi re-check điều kiện NGAY TRONG lock. Nếu một
 * request chunk đã kịp gia hạn expires_at (hoặc complete đã đổi status)
 * trước khi job giành được lock, method này bỏ qua (trả null), không đánh
 * rớt oan một session vừa "sống lại" hợp lệ.
 */
@Transactional
public ExpiryOutcome expireIfDue(UUID sessionId) {
    UploadSession s = uploadSessionRepository.findByIdForUpdate(sessionId).orElse(null);
    if (s == null) return null;
    if (!UploadSessionStatus.OPEN.equals(s.getStatus())) return null;
    if (!OffsetDateTime.now().isAfter(s.getExpiresAt())) return null; // vừa được gia hạn, bỏ qua
    s.setStatus(UploadSessionStatus.EXPIRED);
    s.setUpdatedAt(OffsetDateTime.now());
    uploadSessionRepository.save(s);
    return new ExpiryOutcome(s.getTempFilePath());
}
```

Việc xoá file thật (`Files.deleteIfExists`) chạy **sau khi transaction của
`expireIfDue` đã commit** (gọi từ `UploadCleanupJob`, ngoài transaction) — để
không giữ row lock/transaction DB trong lúc chờ I/O đĩa. Hệ quả chấp nhận
được: nếu xoá file thất bại, session vẫn đã ở `EXPIRED` (đúng AC-1 phần
trạng thái), file rác còn lại được ghi nhận qua `fileErrors` trong log (AC-6)
để vận hành phát hiện — ghi ở rủi ro mục 7.

`deleteTempFile()`:
```java
try {
    boolean existed = Files.deleteIfExists(Path.of(tempFilePath));
    return existed ? DELETED : ALREADY_MISSING;
} catch (IOException e) {
    log.warn("Failed to delete temp file {} for expired upload_session", tempFilePath, e);
    return DELETE_FAILED; // không throw - job phải tiếp tục với session kế tiếp
}
```

### 4.3 AC-2 (gia hạn `expires_at` mỗi chunk) — không có logic mới ở đây

Hành vi này **đã được implement ở spec 01 mục 4.2 bước 4**
(`expires_at = now() + 24h` mỗi lần `appendChunk` ghi thành công). Story 03
không viết lại logic này — chỉ cần 1 test tích hợp ở spec này xác nhận qua
lăng kính "job cleanup": session liên tục nhận chunk trong vòng 24h thì
không bao giờ lọt vào tập kết quả của `findExpiredSessionIds` (mục 5).

### 4.4 AC-4 — kiểm chứng lại logic đã có, không sửa code

1. Test tích hợp: tạo `upload_session` với `expires_at` trong quá khứ (giả
   lập qua fixture/`@Sql` set thẳng cột, vì để chờ đúng 24h thật trong test
   là không khả thi).
2. Gọi `UploadCleanupJob.run()` (hoặc trực tiếp
   `UploadSessionService.expireIfDue(id)` để test nhanh hơn ở mức unit) →
   xác nhận `status=EXPIRED` trong DB.
3. Gọi `PUT /upload-sessions/{id}/chunks?offset=0` lên session đó → xác nhận
   `409 { code: "UPLOAD_SESSION_NOT_OPEN" }` (logic có sẵn từ spec 01, đường
   dẫn code không đổi) — đây là bước đóng vòng lặp end-to-end mà spec 01
   chưa test (spec 01 chỉ test bằng cách set thẳng status, chưa test qua job
   thật).
4. Tương tự cho `POST .../complete` → `409 UPLOAD_SESSION_NOT_OPEN`.

## 5. Test plan

### Backend

- Unit (`UploadSessionServiceTest`, mở rộng file đã có từ spec 01 nếu tồn
  tại lúc implement, hoặc file test riêng nếu spec 01 implement sau):
  - `findExpiredSessionIds`: chỉ trả session `OPEN` có `expires_at < now()`;
    session `OPEN` với `expires_at` tương lai KHÔNG xuất hiện (AC-2, kiểm tra
    gián tiếp qua việc job không đụng tới); session `COMPLETED`/`ABORTED` dù
    `expires_at` quá khứ KHÔNG xuất hiện (AC-3).
  - `expireIfDue`: session đủ điều kiện → trả `ExpiryOutcome` khác null,
    `status` DB chuyển `EXPIRED`; gọi lại lần 2 trên cùng id (đã `EXPIRED`)
    → trả `null`, không update thêm lần nữa (idempotent theo lần chạy job).
  - Race AC-1/AC-2: gọi `expireIfDue(id)` ngay sau khi giả lập
    `appendChunk`-style gia hạn `expires_at` về tương lai (cùng id, set lại
    `expires_at = now + 24h` trước khi gọi) → trả `null`, session vẫn `OPEN`
    (đây là test **bắt buộc**, verify đúng cơ chế mô tả ở mục 4.2).
  - `deleteTempFile`: file tồn tại → `DELETED`; file không tồn tại (đã bị
    xoá trước đó / đường dẫn sai) → `ALREADY_MISSING`, không throw; file
    không có quyền xoá (mock `IOException`) → `DELETE_FAILED`, không throw.

- Integration (`UploadCleanupJobIT`, Testcontainers Postgres — theo đúng
  pattern `UploadBatchServiceTest`/`IngestControllerIT` hiện có):
  - **AC-1**: tạo session `OPEN` với `expires_at` quá khứ + temp file thật
    trên đĩa (tempdir test) → chạy `UploadCleanupJob.run()` → session
    `EXPIRED` trong DB, file không còn tồn tại trên đĩa.
  - **AC-2**: tạo session `OPEN`, gọi `PUT .../chunks` thật (qua MockMvc,
    payload nhỏ) để trigger gia hạn `expires_at` → chạy job ngay sau đó →
    session vẫn `OPEN`, không bị dọn.
  - **AC-3**: tạo 1 session `COMPLETED` và 1 session `ABORTED`, cả hai có
    `expires_at` quá khứ (giả lập dữ liệu cũ) → chạy job → cả hai không đổi
    `status`, không có thao tác xoá file nào được gọi cho 2 session này
    (spy/verify).
  - **AC-4**: nối tiếp AC-1 — sau khi job chạy xong, gọi `PUT .../chunks` và
    `POST .../complete` lên session vừa bị `EXPIRED` → cả hai trả `409
    UPLOAD_SESSION_NOT_OPEN`.
  - **AC-6**: capture log output (hoặc dùng `ListAppender` SLF4J test util)
    khi job chạy → có đúng 1 dòng log chứa `scanned=`, `expired=`,
    `tempFilesDeleted=` với số liệu khớp dữ liệu test đã set (2 session dọn
    được, 1 file lỗi giả lập → `tempFileDeleteErrors=1`).
  - Case biên: `findExpiredSessionIds` với > `BATCH_SIZE` session đủ điều
    kiện (test với `BATCH_SIZE` nhỏ tạm thời qua property override trong
    test, không cần tạo 500 dòng thật) → xác nhận job không throw, số còn
    lại được dọn ở lượt chạy kế tiếp (gọi `run()` 2 lần liên tiếp trong test,
    tổng số dọn = tổng số đủ điều kiện).

### Cấu hình bổ sung cần cho test chạy được

- `@EnableScheduling` thêm vào `BackendApplication` — nếu không thêm,
  `UploadCleanupJobIT` gọi `run()` trực tiếp vẫn test được logic, nhưng job
  sẽ **không tự chạy khi lên production** nếu thiếu annotation này (task bắt
  buộc, xem mục 6).
- Test không nên chờ `fixedDelay` thật (15 phút) chạy — gọi trực tiếp
  `uploadCleanupJob.run()` trong test thay vì chờ scheduler tự trigger.

## 6. Task breakdown

- [x] Task 1: Thêm `@EnableScheduling` vào `BackendApplication`
      (`backend/src/main/java/com/aismartstore/backend/BackendApplication.java`)
      — bắt buộc, hiện chưa có bất kỳ `@Scheduled` nào trong codebase (mục 0).
- [x] Task 2: Thêm property `app.upload.session-cleanup-interval-ms` (mặc
      định `900000` = 15 phút) vào `application.yml`, cùng nhóm `app.upload`
      đã có (`app.upload.max-bytes`).
- [x] Task 3: `UploadSessionRepository` (package
      `com.aismartstore.backend.upload`, đã tạo ở spec 01) — thêm
      `findByStatusAndExpiresAtBeforeOrderByExpiresAtAsc(String, OffsetDateTime, Pageable)`.
- [x] Task 4: `UploadSessionService` — thêm `findExpiredSessionIds(int)`,
      `expireIfDue(UUID)`, `ExpiryOutcome`/`TempFileDeleteResult` theo mục
      4.2. Không sửa `init`/`appendChunk`/`getStatus`/`complete` đã có.
- [x] Task 5: `UploadCleanupJob` mới (`@Component`, `@Scheduled`) theo mục
      4.1 — nếu spec 01 chưa implement xong tại thời điểm này, class này
      biên dịch được ngay khi `UploadSessionService`/`UploadSessionRepository`
      của spec 01 đã tồn tại (phụ thuộc cứng vào Task breakdown B3/B5 của
      spec 01).
- [x] Task 6: Unit test `UploadSessionServiceTest` (mở rộng) — các case ở
      mục 5, đặc biệt test race AC-1/AC-2 (bắt buộc).
- [x] Task 7: Integration test `UploadCleanupJobIT` — 6 case AC-1/AC-2/AC-3/AC-4
      /AC-6 + case biên `BATCH_SIZE` ở mục 5. Case biên implemented bằng cách
      tạo thật 501 dòng `upload_session` (bulk `saveAll`) thay vì "override
      `BATCH_SIZE` qua property" như gợi ý ở mục 5 — `BATCH_SIZE` trong
      `UploadCleanupJob` là hằng số `private static final int` đúng theo mẫu
      code ở mục 4.1, không phải property; tạo đủ số dòng thật vẫn kiểm
      chứng đúng ý AC (job không throw, phần dư dọn ở lượt chạy kế tiếp) mà
      không cần sửa lại thiết kế đã chốt.

## 7. Rủi ro kỹ thuật

- AC-5 dựa trên giả định sai về code hiện tại (mục 0): không có scheduled job
  nào để "mở rộng" — đây là job nền đầu tiên trong toàn bộ backend. **Đã xác
  nhận với người duyệt**: chấp nhận tạo một job mới, đặt tên/vị trí trung lập
  (`UploadCleanupJob`, package `com.aismartstore.backend.upload`) để tương
  lai có thể gộp thêm `UploadBatch` vào cùng job nếu cần, dù đây là khác biệt
  so với câu chữ AC-5 trong story.
- Xoá temp file diễn ra **ngoài transaction** DB (mục 4.2) — nếu quá trình
  xoá file thất bại (quyền, đĩa lỗi...), session đã `EXPIRED` trong DB nhưng
  file vẫn còn trên đĩa; không có cơ chế tự động thử lại file đó ở lượt chạy
  sau (vì session đã không còn `OPEN`, không còn lọt vào query nữa) — chỉ
  phát hiện qua log `tempFileDeleteErrors` (AC-6), cần vận hành theo dõi log
  này thủ công. Chấp nhận được vì đây đúng bằng mức độ mô tả ở SA mục 6 (rủi
  ro "Thấp"), không mở rộng xử lý thêm.
- Job chạy `fixedDelay` toàn cục (không có leader-election) — nếu backend
  chạy nhiều instance (hiện chưa rõ có scale ngang hay không, ngoài phạm vi
  khảo sát của story này), mỗi instance sẽ tự chạy job riêng; do
  `expireIfDue` idempotent qua `findByIdForUpdate` + re-check trong lock nên
  không có nguy cơ dữ liệu sai (instance chạy sau chỉ thấy `null`), chỉ có
  chi phí query dư thừa nhẹ — chấp nhận được ở quy mô hiện tại.
- Task 5 (`UploadCleanupJob`) phụ thuộc cứng vào các class của spec 01
  (`UploadSessionService`, `UploadSessionRepository`, `UploadSession`) —
  tại thời điểm viết spec này, spec 01 **chưa được implement** (khảo sát xác
  nhận không có package `com.aismartstore.backend.upload` trong code); thứ
  tự implement bắt buộc là spec 01 xong trước, spec 03 sau.
