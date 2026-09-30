# Checklist triển khai dự án — Hệ thống lưu trữ & xử lý thông tin AI

> Tổng hợp từ: `../docs/dac_ta_yeu_cau_chuc_nang_phi_chuc_nang.adoc` (SRS v2.1,
> 35 FR + 7 NFR), `1786674480249-fr-con-lai-sa-plan.md` (kế hoạch SA), trạng
> thái thực tế các file `docs/01-sa|02-ba|03-dev/**` (dòng `Status:`), và
> khảo sát source code `backend/src/main/java`, `web/src/app`, `stg/`.
>
> **Cập nhật lần đầu:** 2026-08-31. **Cập nhật lần 2:** 2026-09-06 — rà soát
> lại toàn bộ theo thực tế file/code sau một đợt tạo hàng loạt tài liệu
> SA/BA/DEV còn thiếu; hai mục hoá ra đã xong từ trước (checklist cũ bị lệch),
> nhiều mục khác vừa có BA/DEV-spec mới (Draft, chưa duyệt). File này là bản
> chụp thời điểm — cập nhật lại (tick checkbox, đổi trạng thái) mỗi khi một
> hạng mục hoàn thành, đừng để lệch với thực tế code/docs.
>
> **Cập nhật lần 3: 2026-09-08** — sau khi 8 DEV-spec được duyệt
> (`Approved`), đã chạy `/dev-implement` lần lượt cho toàn bộ:
> `digitization-results-store`, `digitization-pipeline-02-ocr-stt-processing`,
> `digitization-review-01-ocr`, `digitization-review-02-stt`,
> `search-fulltext-01..04`. Toàn bộ code + test đã vào repo, cộng thêm một
> đợt điều tra sâu phát sinh (chi tiết mục 8) tìm ra và sửa nhiều bug thật —
> trong đó có 1 bug ảnh hưởng an toàn khởi động production
> (`SearchIndexBootstrap` treo vô hạn nếu OpenSearch chưa sẵn sàng). Xem mục
> 8 để biết đầy đủ danh sách đã sửa/còn tồn đọng.
>
> **Cập nhật lần 4: 2026-09-09** — hoàn tất `audit-logging` (mục 1 hàng #11):
> sửa 2 lỗi thật trong `AuditControllerIT` (8/8 pass), làm xong Task 11
> (lưới an toàn — `AuditInstrumentationCoverageTest`), tìm và sửa 10 route
> mutating thiếu audit thật. Duyệt cả 2 SA `delete-restore-trash.md` +
> `backup-restore.md`, cả 2 BA story và cả 2 DEV-spec của
> `delete-restore-trash`, rồi chạy `/dev-implement` 2 lượt liên tiếp — xong
> toàn bộ backend (mục 1 hàng #20, mục 2.7), migration thật `V29`/`V30`.
> Còn thiếu: UI thùng rác, và code thật cho `backup-restore` (job
> `trash-purge` đang fail-closed vĩnh viễn vì chưa có bảng `backup_run`).
>
> **Cập nhật lần 5: 2026-09-09** — bổ sung test frontend cấp trang cho toàn
> bộ 17 trang `web/src/app/**/page.tsx` trước đó chưa có test (xem mục 4):
> 153 test mới + hạ tầng test dùng chung `web/src/test-utils/`. Không sửa
> code nguồn trang nào; phát hiện 1 vấn đề accessibility thật ở
> `SearchFilterPanel.tsx` (label thiếu `htmlFor`/`id`), chưa sửa.
>
> **Cập nhật lần 6: 2026-09-11** — sửa xong lỗi accessibility
> `SearchFilterPanel.tsx` nêu trên (2026-09-09/10, giữa 2 lần cập nhật
> checklist); điều tra + sửa 4 test frontend cũ đang fail
> (`uploadOneChunked.test.ts` — không phải bug ứng dụng, 2 assertion lỗi
> thời chưa theo kịp tính năng `loadedBytes`); và chạy `/dev-implement` cho
> `delete-restore-trash-03-frontend.md` — **UI thùng rác hoàn tất**: `/trash`,
> `/admin/trash-stats`, nút xóa trên `/documents` + chi tiết tài liệu, badge
> dung lượng thùng rác trên `/admin/folders` (xem mục 2.7, mục 1 hàng #20).
> Frontend test suite giờ **257/257 pass** (32 file), `tsc`/`eslint`/`next
> build` sạch. `delete-restore-trash` coi như xong hoàn toàn, chỉ còn chờ
> `backup-restore` có code thật để job purge hết fail-closed.

## 0. Chú thích trạng thái

| Ký hiệu | Ý nghĩa |
|---|---|
| ✅ | Hoàn thành |
| 🟡 | Làm dở / một phần |
| ⬜ | Chưa bắt đầu |
| 🚫 | Bị chặn (chờ việc khác xong trước) |

Pipeline chuẩn cho mỗi tính năng (xem `AGENTS.md`): **SA → BA → DEV-spec →
DEV-implement (code + test) → Test tích hợp/UAT → Deploy**.

---

## 1. Nền tảng đã hoàn thành (Giai đoạn A + B một phần)

Toàn bộ dòng dưới đây: SA=Approved, BA=Approved, DEV-spec=Approved, có code
backend (Java/Spring) + tests (unit/IT), phần lớn có UI web tương ứng.

| # | Slug | FR | Backend module | Web UI | Test |
|---|---|---|---|---|---|
| 1 | `auth-session` | FR-02, 03, 05, 06 | `auth/`, `accounts/` | `/account/password`, `api/auth` | ✅ `SecurityConfigTest` |
| 2 | `account-lifecycle` | FR-04 | `accounts/` | `/admin/accounts` | ✅ `AccountsServiceTest`, `AccountsControllerIT`, `KeycloakAdminServiceTest`, `AccountStatusFilterTest` |
| 3 | `rbac-permission-editing` + `rbac-group-scope` | FR-07, FR-08 | `rbac/`, `groups/` (scope) | `/admin/rbac` | ✅ `RbacServiceTest`, `RbacControllerIT`, `BusinessDataScopeTest/IT`, `DocumentAccessScopeIT`, `ScopeFailClosedIT`, `RolePermissionSeedTest` |
| 4 | `warehouse-structure` | FR-13 | `warehouse/` | `/admin/folders` | ✅ `WarehouseCapacityServiceTest`, `WarehouseControllerIT`, `WarehouseFolderServiceTest` |
| 5 | `data-ingestion` | FR-11, FR-12 | `ingest/`, `upload/`, `scanning/`, `storage/` | `/upload` | ✅ `IngestControllerIT`, `UploadBatchServiceTest`, `AllowlistServiceTest` |
| 6 | `chunked-file-upload` (3 story) | hạ tầng cho FR-11/14 (tải lớn) | `upload/`, `outbox/` | `/upload`, `/personal` (chunked) | ✅ `UploadSessionServiceTest/ControllerIT`, `PersonalUploadSessionControllerIT`, `UploadCleanupJobIT`, `OutboxRetentionJobIT`, web `uploadOneChunked.test.ts` (x2) |
| 7 | `personal-storage-quota` | FR-14 | `personal/` | `/personal` | ✅ `PersonalServiceTest`, `PersonalControllerIT` |
| 8 | `classification-notes` (2 story) | FR-15, FR-16 | `tags/`, `notes/` | `/documents`, `/documents/[id]` | ✅ `TagsServiceTest/ControllerIT`, `DocTypeAssignmentServiceTest/IT`, `NotesServiceTest/ControllerIT/OutboxAtomicityIT` |
| 9 | `system-config` | FR-01 | `systemconfig/` | `/admin/system-config`, `/admin/allowed-formats` | ✅ `SystemConfigServiceTest/ControllerIT/ValidatorTest/ValueTest` |
| 10 | `group-management-sharing` (2 story) | FR-28, FR-29 | `groups/` — `GroupsController`, `DocumentSharesController`, `DocumentBulkSharesController`, `notifications/` | `/groups`, `/groups/[id]`, panel chia sẻ trên `/documents/[id]` | ✅ `GroupsServiceTest`, `GroupLifecycleIT` (+ test scope liên quan: `BusinessDataScopeTest/IT`, `DocumentAccessScopeIT`, `ScopeFailClosedIT`) |
| 11 | `audit-logging` (Story 1, 2, 4) | FR-09, phần lớn FR-10 | `audit/` — hash-chain seal job, partition theo tháng, checkpoint/retention, `AuditScopeFilter` cho R2 | `/admin/audit` | ✅ **Hoàn tất 2026-09-09**: `AuditControllerIT` sửa xong (8/8 pass) — lỗi cleanup `DELETE FROM audit_log` bị trigger append-only chặn → đổi `TRUNCATE TABLE`; 1 lỗi thật thứ hai trong chính test (`DataIntegrityViolationException` kỳ vọng sai, Spring dịch đúng thành `UncategorizedSQLException`) cũng đã sửa. Task 11 (lưới an toàn) **đã làm** — `AuditInstrumentationCoverageTest` (unit, không cần Docker) quét call-graph tĩnh mọi `@RestController` mutating; phát hiện **10 route thật sự thiếu audit** và đã bổ sung (`IngestController`/`PersonalController` init/closeBatch, 4 route `ReviewController` theo đúng `digitization-review-01-ocr.md` AC6 US2), 2 route allowlist có lý do (`UploadSessionController#putChunk`, `NotificationsController#markRead`) |
| 12 | `digitization-results-store` | hạ tầng cho FR-17/18/19/20 | `digitization/results/` — `DigitizationRun`, `DigitizationBatch`, `ContentSegment`, `SegmentRevision`, `SoundEvent`, `DocumentDigitizationStatus`, `DigitizationRunLifecycleService` | — (không có UI riêng) | ✅ Implement + test 2026-09-08 (xem mục 8.1) — migration `V25`/`V26` |
| 13 | `digitization-pipeline-02-ocr-stt-processing` (Epic B: Spring Boot tiêu thụ kết quả) | FR-17, 19, 20 | `digitization/` — dispatch/heartbeat/result consumer, lease reconciliation, DLQ alert job | — | ✅ Implement + test 2026-09-08 (xem mục 8.1) — migration `V27`. **Epic A (worker Python OCR/STT thật) vẫn CHƯA làm** — xem mục 2.1 |
| 14 | `digitization-review-01-ocr` | FR-18 | `digitization/review/` — `ReviewController` (6 endpoint), `AutoConfirmScheduler`, `ReviewStaleAlertJob` | — **CHƯA có UI** | ✅ Backend implement + test 2026-09-08 (xem mục 8.1) — migration `V28`. UI kiểm duyệt OCR/STT **chưa làm**, xem mục 8.5 |
| 15 | `digitization-review-02-stt` | FR-18 | Mở rộng `digitization/review/` — validate mốc thời gian STT, `overlapsAdjacent` | — **CHƯA có UI** | ✅ Backend implement + test 2026-09-08 (xem mục 8.1). Task 4 (frontend `web/src/app/review/`) **cố ý hoãn** — gộp chung 1 spec UI, điều kiện chờ (`-03` được duyệt) **đã thoả 2026-09-08 phiên 3**, xem mục 8.5/8.9 |
| 16 | `search-fulltext-01..05` | FR-22, FR-23, FR-25 | `search/` — `SearchController` (`POST /search`), `SearchIndexBootstrap`, `SearchQueryBuilder`, `SegmentIndexer`, `SearchIndexOutboxPoller`; endpoint mới `GET /documents/{id}/pages/{pageNo}/image` (`DocumentsController`, `PageImageRenderer`) | ✅ `/search` | ✅ Backend implement + test 2026-09-08 (mục 8.1/8.2/8.3); **UI `/search` implement + xác minh xong 2026-09-09** (mục 8.15-8.17) — tìm kiếm cơ bản/nâng cao, định vị trang+bbox/mốc thời gian, liên kết tóm tắt video, badge trạng thái. 3 bug hạ tầng nghiêm trọng phát hiện khi xác minh **đã sửa dứt điểm** (mục 8.17): outbox kẹt vĩnh viễn khi tài liệu không có thư mục, `document_digitization_status` không cập nhật khi xác nhận, tìm kiếm sập 400 khi khớp tên tệp/ghi chú. Đồng thời mở khoá `ai-search-infra` (mục 2.4). |
| 17 | `digitization-review-04-sed-summary` | FR-18 | Mở rộng `digitization/review/` — `soundEventLabel`/`sourceReferences`/`staleWarning`, validate 422/409 cho task `sed`/`video_summary` | — **CHƯA có UI** | ✅ Backend implement + test 2026-09-08 (phiên 2, xem mục 8.8) — 445 unit test + 19 IT test pass. Task 8-12 (frontend) **cố ý hoãn**, cùng lý do -02 |
| 18 | `digitization-review-03-ownership-transfer` | FR-18 | `documents/` (không phải `digitization/review/`) — `DocumentOwnershipController/Service/Repository` | — **CHƯA có UI** | ✅ Backend implement + test 2026-09-08 (phiên 4, xem mục 8.10) — 7 unit test + 11 IT test pass. Task 7 (frontend) **cố ý hoãn**, gộp chung 1 spec UI với -01/-02/-04 |
| 19 | `digitization-review-05-frontend` | FR-18 | 1 endpoint mới `GET .../runs/{runId}/pages/{pageNo}/image` (PDFBox, `ReviewController`) | ✅ `/digitization`, `/digitization/runs/[runId]`, `/admin/document-ownership` | ✅ **Implement xong toàn bộ Task 1-9** 2026-09-08 (phiên 7, xem mục 8.13) — backend: 5/5 unit test (`ReviewControllerPageImageTest`) + `mvn test` toàn hệ thống sạch; frontend: `npm run build` sạch (route lên đúng), 15/15 test mới (`digitization/`) pass |
| 20 | `delete-restore-trash` (Story 1-6, 3 file BA + 3 file DEV-spec — thêm `-03-frontend`) | FR-26, FR-27 (nhánh R1) | `trash/` + `trash/purge/` — `TrashController/Service/Batch`, `TrashPurgeJob/Service`, `PurgeStuckRecoveryJob`; mở rộng `documents/`, `groups/BusinessDataScope`, `warehouse/WarehouseService` (snapshot `deletedFolderPathIds`), `warehouse/WarehouseCapacityService` (`trashBytes`) | ✅ `/trash`, `/admin/trash-stats`, nút xóa trên `/documents` + `/documents/[id]`, badge thùng rác trên `/admin/folders` | ✅ **Backend implement xong 2026-09-09** — migration `V29` (xóa mềm, đổi từ đề xuất `V28` vì bị `digitization-review-01-ocr` chiếm trước) + `V30` (xóa cứng/xung đột, đổi từ đề xuất `V29`); ShedLock (dependency mới); đầy đủ 8 case xung đột khi phục hồi (Quyết định 7 SA). Test: `TrashServiceTest`(20)/`TrashControllerIT`(18)/`TrashRestoreConflictControllerIT`(5)/`TrashPurgeServiceTest`(9)/`TrashPurgeServiceIT`(4)/`TrashPurgeJobTest`(5)/`PurgeStuckRecoveryJobTest`(1) + test warehouse/audit liên quan cập nhật theo — toàn bộ pass, không hồi quy (mục 8.18 bổ sung 8 test đóng 4 gap AC + sửa 1 bug thật: audit `document.trash.denied` bị rollback mất do transaction, đã sửa bằng `AuditService.recordIndependently`). **UI thùng rác implement xong 2026-09-11** (xem mục 2.7) — 257/257 test frontend pass |

**Việc còn lại ở nhóm này:**
- `group-management-sharing`: không có gì thêm — coi là xong, chỉ bảo trì khi có bug. *(Trước đây checklist ghi "có backend, thiếu tài liệu BA/DEV + UI" — thông tin đó đã lỗi thời, xem mục "Đã cập nhật 2026-09-06" bên dưới.)*
- `audit-logging`: **đã xong hoàn toàn 2026-09-09** (xem hàng #11) — không còn việc gì ở đây.
- `delete-restore-trash`: backend + UI đều xong (hàng #20, mục 2.7) — còn
  thiếu duy nhất **gate sao lưu thật** cho job purge — `TrashPurgeJob` code
  đúng theo schema `backup_run` của `backup-restore.md` nhưng bảng đó chưa
  tồn tại (slug `backup-restore` chưa implement) nên job hiện luôn
  fail-closed (không bao giờ xóa cứng) cho tới khi `backup-restore` được
  code — xem mục 2.7 để biết chi tiết.

---

## 2. Đang làm dở / có khoảng trống (Giai đoạn B–C)

### 2.1. `digitization-pipeline` (FR-17, FR-19, FR-20) — 🟡 Epic B xong, Epic A (AI worker thật) vẫn chưa có

- [x] SA `Approved`
- [x] BA story `digitization-pipeline-01-source-dispatch` — Approved, có code
- [x] DEV-spec cùng tên — Approved, có code
- [x] Code Pha 1: `digitization/` — dispatch job sang hàng đợi RabbitMQ + tách nguồn
- [x] **Đã cập nhật 2026-09-08:** `digitization-pipeline-02-ocr-stt-processing`
      (BA + DEV-spec) **Approved**, **đã implement xong** ("Epic B" — Spring
      Boot tiêu thụ kết quả): dispatch/heartbeat/result consumer, lease
      reconciliation, DLQ alert job, migration `V27`. Xem mục 8.1.
- [ ] **⬜ "Epic A" — hạ tầng AI worker Python (`ocr-worker`, `stt-worker` —
      PaddleOCR/VietOCR, PhoWhisper/faster-whisper) VẪN CHƯA TỒN TẠI trong
      repo** — đây là khối lượng việc lớn nhất còn lại trong toàn bộ
      checklist, chưa động tới. Không có nó, pipeline chỉ dispatch job vào
      hàng đợi RabbitMQ rồi không ai xử lý.

### 2.2. `digitization-results-store` — ✅ Đã duyệt & implement xong (2026-09-08)

- [x] SA `Approved`
- [ ] BA story: file vẫn ghi **Status: Draft** — chưa cập nhật, dù DEV-spec
      cùng slug đã `Approved` và đã implement xong (không nhất quán trạng
      thái tài liệu, nên rà soát lại cho khớp)
- [x] DEV-spec `Approved`
- [x] **Đã cập nhật 2026-09-08:** Code lưu kết quả OCR/STT đã implement +
      test — `digitization/results/` (`DigitizationRun`, `DigitizationBatch`,
      `ContentSegment`, `SegmentRevision`, `SoundEvent`,
      `DocumentDigitizationStatus`), migration `V25`/`V26`. Xem mục 8.1.
- [x] Không còn chặn 2.3 nữa

### 2.3. `digitization-review` (FR-18) — ✅ Backend + UI xong cả 4 story (trừ `-03`/`-04` UI đã gộp chung, xem `-05`)

- [x] SA `Approved`
- [x] `digitization-review-01-ocr` (BA + DEV-spec) **Approved**, backend
      implement xong 2026-09-08 — hàng chờ, xem/sửa OCR, xác nhận,
      auto-confirm, nhiều run; migration `V28`. UI: xong ở `-05` (phiên 7).
- [x] `digitization-review-02-stt` (BA + DEV-spec) **Approved**, backend
      implement xong 2026-09-08 — validate mốc thời gian STT, overlap
      detection. UI (player): xong ở `-05` (phiên 7).
- [x] **Đã cập nhật 2026-09-08 (phiên 2):** `digitization-review-04-sed-summary`
      (BA + DEV-spec) **Approved**, **đã implement backend xong** — hiển thị
      `soundEventLabel`/`sourceReferences` (liên kết ngược video gốc)/
      `staleWarning` trên `GET /runs/{id}`; validate 422/409 riêng cho task
      `sed`/`video_summary`. Không cần migration mới (vẫn `V28`). UI: xong
      ở `-05` (phiên 7). Xem mục 8.8.
- [x] **Đã cập nhật 2026-09-08 (phiên 3):** `digitization-review-03-ownership-transfer`
      (BA + DEV-spec) **Approved** — R3 chuyển quyền sở hữu tài liệu khi
      người nạp vắng mặt. 4 điểm cần xác nhận đã chốt cùng người dùng: vị
      trí màn hình = `/admin/document-ownership` (khu vực quản trị chung);
      `reason` không bắt buộc (tối đa 500 ký tự); không ràng buộc cùng nhóm
      giữa A/B; bổ sung `sedStatus`/`videoSummaryStatus` vào
      `DocumentOwnershipRow` (phát hiện khi review). **Đã implement backend
      xong** (phiên 4, cùng ngày) — `DocumentOwnershipController/Service/
      Repository` (package `documents/`, không phải `digitization/review/`
      theo đúng đề xuất Task 2 của spec), audit key
      `document.ownership_transferred`. Không cần migration mới (vẫn `V28`).
      UI: xong ở `-05` (phiên 7). Xem mục 8.10.
- [x] **Đã cập nhật 2026-09-08 (phiên 7):** `digitization-review-05-frontend`
      — BA (7 story) + DEV-spec **Approved** (phiên 5-6, rà soát + sửa 3
      điểm trước duyệt, xem mục 8.11/8.12), **đã implement xong toàn bộ
      Task 1-9** (phiên 7, xem mục 8.13): 1 endpoint backend mới
      (`GET .../runs/{runId}/pages/{pageNo}/image`, PDFBox) + toàn bộ UI —
      `/digitization` (hàng chờ), `/digitization/runs/[runId]` (OCR ảnh+bbox,
      STT/SED player, tóm tắt video, xác nhận), `/admin/document-ownership`
      (tab mới). **Toàn bộ 4 story `digitization-review` nay có cả backend
      lẫn UI hoàn chỉnh.**

### 2.4. `ai-search-infra` — 🟡 OpenSearch đã tích hợp code, Qdrant vẫn chưa

- [x] SA `Approved` (chốt OpenSearch + Qdrant + RabbitMQ + GPU serving)
- [x] Hạ tầng trong `docker-compose.yml`: `opensearch`, `qdrant`, `rabbitmq`
- [x] RabbitMQ đã tích hợp vào code
- [x] **Đã cập nhật 2026-09-08:** **OpenSearch đã tích hợp vào code** —
      `opensearch-java` + `httpclient5` trong `backend/pom.xml`, module
      `search/` (33 file Java: `OpenSearchClientConfig`, `SearchIndexBootstrap`,
      `SearchQueryBuilder`, `SegmentIndexer`, `SearchIndexOutboxPoller`...).
      Đi kèm với việc implement `search-fulltext-01..04` (mục 2.5). Phát sinh
      và đã sửa 2 bug nghiêm trọng ở tầng dependency/client — xem mục 8.2/8.3.
- [ ] **Qdrant: vẫn chưa có client/dependency nào** trong `backend/pom.xml`
      hay code — cần cho `search-semantic` (mục 2.6)

### 2.5. `search-fulltext` (FR-22, FR-23, FR-25) — ✅ Backend + UI xong, đã xác minh trên trình duyệt thật

- [x] SA `Approved`
- [x] BA story `Approved` cả 5 file (`-01-tim-kiem-co-ban`,
      `-02-tim-kiem-nang-cao`, `-03-dinh-vi-ket-qua`, `-04-dong-bo-chi-muc`,
      `-05-frontend`)
- [x] DEV-spec `Approved` cả 5 file
- [x] **Đã cập nhật 2026-09-08:** **Backend đã implement + test xong 4 spec
      đầu** — `SearchController` (`POST /search`), scope theo
      `BusinessDataScope`, filter nâng cao, highlight/vị trí kết quả, đồng bộ
      chỉ mục qua outbox (`SegmentIndexer`, `SearchIndexOutboxPoller`,
      `AclChangeOutbox`). Xem mục 8.1.
- [x] **Đã cập nhật 2026-09-09:** **UI `/search` implement xong** (`-05`) —
      ô tìm kiếm + panel bộ lọc nâng cao (7 nhóm) + danh sách kết quả +
      nhảy tới vị trí (ảnh trang/bbox qua endpoint mới
      `GET /documents/{id}/pages/{pageNo}/image`, player audio/video dùng
      lại `SegmentPlayer`) + liên kết tóm tắt video tới run gốc. **Đã xác
      minh trên trình duyệt thật** (canbo1), phát hiện + sửa dứt điểm 3 bug
      hạ tầng nghiêm trọng trong quá trình đó (mục 8.15-8.17):
      1. Outbox đồng bộ chỉ mục kẹt **vĩnh viễn** khi tài liệu không có
         `folder_id` (NULL hợp lệ theo thiết kế) — chặn toàn bộ tài liệu
         nạp sau đó, không riêng tài liệu lỗi.
      2. `confirmRun()` không cập nhật `document_digitization_status` —
         tài liệu vừa duyệt xong vẫn hiện sai "đang chờ kiểm duyệt".
      3. Tìm kiếm sập lỗi 400 khi kết quả khớp cả tên tệp/ghi chú (ID giả
         không phải UUID) — ảnh hưởng bất kỳ từ khoá nào trùng tên tệp.
      Cả 3 đã sửa + có test hồi quy + xác minh lại trên Docker thật.
- [x] Không còn bị chặn bởi 2.4 (OpenSearch đã tích hợp)

### 2.6. `search-semantic` (FR-24) — 🟡 Đã có đủ tài liệu Draft, chưa duyệt, chưa code

- [ ] SA: **Status: Draft** → cần duyệt. Ràng buộc thứ tự duyệt "phải duyệt
      `search-fulltext` trước" **nay đã thoả** — `search-fulltext` không chỉ
      Approved mà đã implement xong (mục 2.5), nên hợp đồng `lexicalSearch()`
      giờ là code thật, không còn là spec — nên đối chiếu lại chữ ký thực tế
      trong `SearchServiceImpl`/`search/query/LexicalHit` (thêm ở
      `search-fulltext-04`, xem mục 8.1) với giả định trong SA/DEV-spec này
      trước khi duyệt, phòng trường hợp có lệch nhỏ so với bản Draft.
- [x] BA story (Draft) và DEV-spec (Draft) đã viết.
- [ ] Code, UI hỏi-đáp — chưa làm, chờ duyệt tài liệu trước
- [ ] **🚫 Vẫn chặn bởi Qdrant chưa tích hợp (mục 2.4) + chờ duyệt SA/BA/DEV**

### 2.7. `delete-restore-trash` (FR-26, FR-27) — ✅ Đã duyệt & implement xong cả backend lẫn UI (2026-09-11)

- [x] SA `Approved` (2026-09-09) — câu hỏi mở duy nhất (giờ chạy job purge
      đồng bộ với sao lưu) đã trả lời tại `backup-restore.md` Quyết định 3,
      mục 7 file SA đã cập nhật `[x]`. **Đồng thời duyệt luôn SA
      `backup-restore.md`** (Approved 2026-09-09) — file đó vẫn còn 7 câu hỏi
      mở khác chưa liên quan tới slug này (RTO/RPO chính thức, bản sao
      offsite 3-2-1, ai vận hành, xác nhận hạ tầng ZFS/NAS, kênh cảnh báo,
      xóa triệt để pháp lý, số liệu dung lượng thực tế) — **chưa implement
      code cho `backup-restore`**, chỉ mới duyệt nội dung SA.
- [x] BA story 2 file `Approved` (2026-09-09) — `-01-xoa-mem-thung-rac`,
      `-02-xoa-cung-xung-dot`. Đã dọn các ghi chú/câu hỏi mở lỗi thời (trỏ
      đúng nơi DEV-spec đã trả lời).
- [x] DEV-spec 2 file `Approved` (2026-09-09) — Task breakdown cả 2 file đã
      `[x]` hết sau khi implement.
- [x] **Code đã implement 2026-09-09** (2 lượt `/dev-implement` liên tiếp):
      - File 01: migration **`V29`** (đổi từ đề xuất `V28` — bị
        `digitization-review-01-ocr` chiếm trước, xem mục 7), module
        `trash/` (`TrashController`, `TrashService`, `TrashBatch`), 2
        endpoint `POST /documents/trash`/`trash-preview`, sửa
        `BusinessDataScope.forDocuments` thêm `deletedAt IS NULL`,
        `GET /trash`, `GET /trash/stats` (R3), `trashBytes` ở
        `GET /warehouse/capacity`.
      - File 02: migration **`V30`** (đổi từ đề xuất `V29` — bị file 01
        chiếm mất do đổi số ở trên), ShedLock (dependency mới), `TrashPurgeJob`
        + `TrashPurgeService` + `PurgeStuckRecoveryJob`, đầy đủ 8 case xung
        đột khi phục hồi ở `TrashService.restore(...)`, hook snapshot
        `deletedFolderPathIds` ở `WarehouseService.deleteFolder`.
      - Test: toàn bộ pass (xem mục 1 hàng #20), không hồi quy ngoài 3 lỗi
        đã biết từ trước không liên quan (`NotesControllerIT`,
        `NotesOutboxAtomicityIT`, `SearchIndexOutboxPollerIT`).
- [x] **UI thùng rác — implement xong 2026-09-11** theo
      `delete-restore-trash-03-frontend.md` (BA + DEV-spec đều `Approved`
      từ trước, cả 12 task đã `[x]`). File mới: `web/src/app/trash/page.tsx`
      (danh sách, 2 tab, bulk restore + báo cáo), `web/src/app/admin/trash-stats/page.tsx`
      (thống kê R3), `web/src/components/trash/RestoreDialog.tsx` (đủ 8 case
      xung đột phục hồi), `web/src/components/trash/TrashDetailPanel.tsx`.
      Sửa: `documents/page.tsx` + `documents/[id]/page.tsx` (checkbox chọn
      nhiều, nút "Xóa"/"Xóa đã chọn", confirm + xóa mềm, link sang `/trash`),
      `admin/folders/page.tsx` (dòng "Trong đó, thùng rác" + cảnh báo ≥20%),
      `lib/api.ts`/`lib/features.ts`/`AdminTabs.tsx`/`AppShell.tsx`. Test
      frontend: 257/257 pass (32 file, +45 test so với trước đợt này),
      `tsc --noEmit`/`eslint`/`next build` sạch — đã tự chạy lại xác minh.
- [ ] **Job `trash-purge` hiện luôn fail-closed** (không bao giờ xóa cứng
      thật) vì gate đọc bảng `backup_run` của `backup-restore` — slug đó
      **chưa có code**, chỉ mới duyệt SA. Cần implement `backup-restore`
      (ít nhất phần bảng `backup_run` + API `/internal/backup/runs`) trước
      khi job purge có thể chạy thật — xem mục 3 và thứ tự triển khai đã
      chốt ở SA `delete-restore-trash.md` mục 7 (backup-restore trước,
      delete-restore-trash sau — thứ tự này giờ bị ngược vì code slug này
      đã đi trước, cần theo dõi dung lượng thùng rác thủ công cho tới khi
      backup-restore xong).

---

## 3. SA vừa được bổ sung, chờ duyệt (trước là "chưa có tài liệu SA")

| Slug | FR | SA | Ghi chú |
|---|---|---|---|
| `reporting-export` | FR-30, FR-31 | 🟡 **Đã viết (Draft)** — 2026-09-06 | Chọn JasperReports (LGPL) làm engine xuất PDF/Excel/Word. **Blocker cho bước BA:** SRS không liệt kê mẫu báo cáo cụ thể nào — SA tạm giả định 6 mẫu MVP, cần nghiệp vụ cung cấp **biểu mẫu thật** (bố cục/cột/chỉ tiêu) trước khi viết được AC kiểm chứng được. |
| `external-api` | FR-32, FR-33 | 🟡 **Đã viết (Draft)** — 2026-09-06 | Chọn OAuth2 client-credentials qua Keycloak thay vì tự xây bảng API key; rate-limit bằng Redis (đã có sẵn `StringRedisTemplate`). Câu hỏi mở đáng chú ý: có cho tải file gốc qua API ngoài không, và VLAN/DMZ đặt module này ở đâu. |
| `backup-restore` | FR-34, FR-35 | ✅ **Approved** — 2026-09-09 | Chốt ranh giới SRS (mức nghiệp vụ) vs `kien_truc_he_thong.adoc` (cơ chế ZFS/snapshot/`zfs send`). Đề xuất RTO/RPO cụ thể, và đã **trả lời câu hỏi mở của `delete-restore-trash`** (đồng bộ giờ job purge 03:00 với cửa sổ sao lưu 02:00, thứ tự triển khai: backup-restore trước). Còn 7 câu hỏi mở khác chưa duyệt/xác nhận (RTO/RPO chính thức, bản sao offsite 3-2-1, ai vận hành, xác nhận hạ tầng ZFS/NAS, kênh cảnh báo, xóa triệt để pháp lý, số liệu dung lượng thực tế) — **SA đã Approved nhưng các câu hỏi này chưa được người dùng trả lời**, cần làm trước/song song khi viết BA story. **Chưa có BA/DEV-spec, chưa có code.** |

**Việc cần làm:** người dùng review + duyệt (đổi `Status: Draft` →
`Approved`) 2 file SA còn lại (`reporting-export`, `external-api`) —
`backup-restore` đã Approved 2026-09-09 nhưng còn 7 câu hỏi mở riêng chưa
trả lời (xem cột Ghi chú), nên coi là duyệt nội dung, chưa hẳn "sẵn sàng
chạy `/ba-story` mà không cần hỏi lại gì thêm". Trả lời các câu hỏi mở nêu
trong từng file (mục 7 mỗi file) trước khi chạy `/ba-story` cho từng slug.

---

## 4. Kiểm thử toàn diện (UAT / tải / bảo mật) — ⬜ Chưa bắt đầu

Theo `../docs/timeline_estimation.md`: kiểm thử UAT, kiểm thử tải, bảo mật
**cố ý để sau mốc 26/10**, không nằm trong lộ trình phát triển tính năng.
Hiện tại chỉ có unit test + integration test (`*Test.java`, `*IT.java`) theo
từng module — đủ để CI, **không thay thế được**:

> **Cập nhật 2026-09-08:** `*IT.java` (Testcontainers) giờ chạy được đúng
> chuẩn qua `mvn verify` (thêm `maven-failsafe-plugin`, xem mục 8.6) — trước
> đây không hề chạy được (migration `V21` lỗi chặn từ đầu, mục 8.2). 429
> unit test + phần lớn 27 lớp IT đã xanh; 4 lớp còn lỗi riêng biệt chưa sửa
> (mục 8.7).

- [ ] UAT theo kịch bản nghiệp vụ đầu-cuối cho từng vai trò R1/R2/R3/R4
- [ ] Kiểm thử tải/hiệu năng (NFR-01, NFR-03: tốc độ tra cứu, quy mô nhóm/dữ liệu)
- [ ] Kiểm thử bảo mật (NFR-04, NFR-05, NFR-08): pentest, TLS/mTLS, mã hoá
      lưu trữ — ghi chú cũ (`docs/feature-status.md`, bản legacy) cho biết
      môi trường dev hiện chạy HTTP thuần, TLS/mTLS chưa bật
- [ ] Kiểm thử khôi phục sao lưu định kỳ (NFR-06) — phụ thuộc `backup-restore`
      (mục 3, SA đã Draft, còn chờ duyệt + code) làm xong trước
- [x] **Test frontend cho toàn bộ trang đã triển khai — hoàn tất 2026-09-09.**
      Trước đó chỉ có 2 file `uploadOneChunked.test.ts` + vài test component
      lẻ (`digitization/`, `search/`). Đã bổ sung test cấp trang (Vitest +
      Testing Library) cho **17/17 trang** `src/app/**/page.tsx` chưa có test:
      dashboard, `account/password`, toàn bộ 6 trang `admin/*`
      (`accounts`, `allowed-formats`, `audit`, `document-ownership`,
      `folders`, `rbac`, `system-config`), `documents` + `documents/[id]`,
      `groups` + `groups/[id]`, `digitization` + `digitization/runs/[runId]`,
      `personal`, `upload`, `search` — **153 test mới, tất cả pass**, cộng
      hạ tầng dùng chung mới `web/src/test-utils/`
      (`session.ts` — fixture session theo role R1/R2/R3, `fetchMock.ts` —
      mock `global.fetch` theo URL). Mỗi trang: kiểm gating theo role
      (`AccessDenied` khi sai/thiếu quyền), render dữ liệu từ fetch mock,
      luồng thao tác chính (CRUD, confirm dialog, filter, upload), và hiển
      thị lỗi API. `npx tsc --noEmit` và `eslint` sạch trên toàn bộ test mới.
      Full suite lúc đó: 28 file / 212 test, 208 pass — 4 fail còn lại là
      lỗi cũ đã biết trong `upload/`+`personal/` `uploadOneChunked.test.ts`
      (xem mục 8.7); **đã điều tra + sửa xong ngay sau đó cùng ngày** (mục
      8.7 cập nhật) — không phải bug ứng dụng, chỉ là 2 assertion lỗi thời
      trong chính test. Full suite hiện tại: **28/28 file, 212/212 test
      pass.**
      **Phát hiện + đã sửa 1 vấn đề thật (không phải bug chức năng):**
      `web/src/components/search/SearchFilterPanel.tsx` — hầu hết `<label>`
      không có `htmlFor`/`id` liên kết với control tương ứng, ảnh hưởng khả
      năng tiếp cận cho screen reader. **Đã sửa cùng ngày:** 3 `<select>`
      đơn (loại tài liệu/thư mục/nguồn xác nhận) dùng `htmlFor`/`id`; 4 nhóm
      checkbox/date-range (nhãn chủ đề, ngày nạp kho, nhóm, loại nội dung)
      chuyển sang `<fieldset>`/`<legend>` (select AND/OR đặt trong chính
      `<legend>` — hợp lệ vì `<select>` là phrasing content — để `<legend>`
      vẫn là con trực tiếp của `<fieldset>`, điều kiện bắt buộc để nó thật
      sự gắn nhãn cho nhóm). Đã cập nhật
      `web/src/app/search/__tests__/page.test.tsx` dùng `getByLabelText`
      thay vì workaround `label.nextElementSibling`. `tsc`/`eslint` sạch,
      full suite vẫn 208/212 pass (không đổi so với trước khi sửa).

---

## 5. Deploy & triển khai hệ thống

- [x] **Kịch bản deploy staging đã viết xong** — `stg/`: `docker-compose.yml`
      (đủ service: keycloak, postgres, seaweedfs, redis, clamav, opensearch,
      qdrant, rabbitmq, backend, web, nginx), `nginx/conf.d/`, `BUILD.md`
      (build + verify + push image qua SSH), `build-and-push.sh`, `README.md`
      (hướng dẫn Cloudflare DNS, firewall, chạy `docker compose up -d`)
- [ ] **Chưa xác nhận đã deploy thực tế lên server staging** (`green0tech.site`)
      hay mới chỉ chuẩn bị kịch bản — cần người phụ trách hạ tầng xác nhận
- [ ] Chưa có kịch bản deploy **production** riêng (khác staging) — nếu môi
      trường production khác staging (ví dụ mạng cách ly hoàn toàn theo
      NFR-11), cần lập kế hoạch riêng, không dùng thẳng `stg/`
- [ ] Chưa có migration/runbook khôi phục sự cố (phụ thuộc `backup-restore`,
      mục 3 — SA đã Approved 2026-09-09 làm nền, còn chờ BA/DEV-spec + code;
      đang chặn job `trash-purge` thật của `delete-restore-trash`, mục 2.7)
- [ ] Chưa có CI/CD pipeline tự động hoá build-test-deploy (hiện là script
      chạy tay `build-and-push.sh`)

---

## 6. Thứ tự thực hiện đề xuất (theo phụ thuộc)

1. ~~Duyệt `digitization-results-store`, `digitization-review-01/02/03/04`,
   `digitization-pipeline-02-ocr-stt-processing`, `search-fulltext-01..04`~~
   — **đã duyệt và implement xong backend, toàn bộ nhóm `digitization-review`
   hoàn tất 2026-09-08** (xem mục 8, 8.10). Còn lại cần duyệt:
   - `search-semantic` (SA + BA + DEV-spec, cả 3 vẫn Draft) — ràng buộc "phải
     duyệt `search-fulltext` trước" nay đã thoả (mục 2.6).
   - ~~`delete-restore-trash` (SA + BA×2 + DEV-spec×2, vẫn Draft)~~ — **đã
     duyệt và implement backend xong 2026-09-09** (mục 1 hàng #20, mục 2.7).
     Còn thiếu UI thùng rác.
   - `reporting-export`, `external-api` (SA, mục 3) — BA/DEV chưa viết, chờ
     duyệt SA trước. `backup-restore` (SA, mục 3) **đã Approved 2026-09-09**
     nhưng còn 7 câu hỏi mở riêng chưa trả lời trước khi viết BA.
2. ~~Vá khoảng trống BA/DEV cho `group-management-sharing`~~ — **đã xong**
   (mục 1, hàng #10), không còn việc gì ở đây.
3. **Hạ tầng AI worker Python (Epic A của `digitization-pipeline`)** — việc
   lớn nhất còn lại: pipeline hiện dispatch job vào RabbitMQ nhưng
   **không có worker nào tiêu thụ** `q.ocr`/`q.stt` thật (PaddleOCR/VietOCR,
   PhoWhisper/faster-whisper chưa tồn tại trong repo). Không có nó,
   `digitization-review` (đã implement backend) sẽ mãi không có dữ liệu thật
   để duyệt.
4. ~~Tích hợp OpenSearch vào code~~ — **đã xong** cùng lúc với
   `search-fulltext` (mục 2.4/2.5, xem mục 8). **Qdrant vẫn chưa tích hợp** —
   cần trước khi code `search-semantic`.
5. ~~`audit-logging` FR-10~~ — **đã xong hoàn toàn 2026-09-09**:
   `AuditControllerIT` sửa xong 8/8 pass (2 lỗi test, không phải lỗi nghiệp
   vụ) + Task 11 (lưới an toàn) đã làm, phát hiện và sửa 10 route thiếu
   audit thật (mục 1 hàng #11).
6. ~~UI cho `digitization-review`~~ — **đã implement xong** (phiên 7, mục
   8.13). ~~UI cho `search-fulltext`~~ — **đã implement + xác minh xong**
   (2026-09-09, mục 2.5, 8.15-8.17).
7. ~~`delete-restore-trash`~~ — **backend đã implement xong 2026-09-09, UI
   đã implement xong 2026-09-11** (mục 1 hàng #20, mục 2.7). Còn lại duy
   nhất: **`backup-restore` cần có code thật** (ít nhất bảng `backup_run`)
   để job `trash-purge` thoát khỏi trạng thái fail-closed vĩnh viễn.
8. **2 slug còn lại có SA chờ duyệt (`reporting-export`, `external-api`)**
   — duyệt SA trước (mục 3), riêng `reporting-export` cần nghiệp vụ cung cấp
   mẫu báo cáo thật trước khi chạy `/ba-story`. `backup-restore` **đã
   Approved** nhưng cần trả lời 7 câu hỏi mở riêng (mục 3) trước khi chạy
   `/ba-story` — ưu tiên slug này sớm vì đang chặn job `trash-purge` thật
   (mục 7 ở trên); sau đó chạy `/ba-story` → `/dev-spec` theo đúng thứ tự đã
   đề xuất trước đây (báo cáo phụ thuộc dữ liệu nhóm; API ngoài tái dùng
   filter FR-08).
9. **Kiểm thử toàn diện (UAT/tải/bảo mật)** — sau khi đủ tính năng theo mốc
   26/10 trong `timeline_estimation.md`.
10. **Xác nhận/triển khai thực tế lên server** dùng kịch bản đã có ở `stg/`,
    sau đó lập kịch bản production riêng nếu khác staging.

---

## 7. Câu hỏi cần người dùng xác nhận

- [x] ~~`group-management-sharing`: viết BA/DEV-spec hồi tố theo code hiện có,
      hay thiết kế lại từ đầu?~~ — **đã tự giải quyết**: khi rà soát lại
      2026-09-06 phát hiện BA/DEV-spec hồi tố **đã được viết và Approved** từ
      trước (2026-09-02), khớp đúng code hiện tại. Không còn là câu hỏi mở.
- [x] ~~`digitization-results-store` (mục 2.2): 4 điểm SA mục 7 chưa chốt~~ —
      **đã giải quyết**: SA/DEV-spec nay đều `Approved` và đã implement xong
      (2026-09-08, xem mục 8). Không rõ nội dung quyết định cụ thể cho từng
      điểm — nếu cần đối chiếu, xem lịch sử file SA gốc.
- [ ] **`reporting-export`** (mục 3): cần **mẫu báo cáo thật** (bố cục, cột,
      chỉ tiêu, có cần chữ ký/đóng dấu không) — SRS không liệt kê, SA chỉ tạm
      giả định 6 mẫu MVP. Đây là blocker thật sự cho bước BA, không thể tự
      suy luận thêm.
- [x] ~~Thứ tự duyệt `search-fulltext` trước `search-semantic`~~ — **đã
      thoả**: `search-fulltext` đã Approved và implement xong 2026-09-08.
      Khi duyệt `search-semantic`, đối chiếu lại hợp đồng `lexicalSearch()`
      thật trong code (`search/query/LexicalHit`, `SearchServiceImpl`) thay
      vì bản giả định trong SA/DEV-spec Draft, xem mục 2.6.
- [x] ~~Trùng số migration đề xuất `V28`~~ — **đã giải quyết, xong hẳn
      2026-09-09**: `digitization-review-01-ocr` implement trước, chiếm
      `V28` thật (`V28__digitization_review_queue_and_permissions.sql`).
      `delete-restore-trash` đã implement sau đó, dùng đúng
      **`V29__trash_soft_delete.sql`** (file 01) và
      **`V30__trash_purge_and_conflict.sql`** (file 02) — cả 2 tài liệu spec
      đã cập nhật khớp số hiệu thật, không còn lệch.
- [ ] Môi trường production có khác staging (`stg/`) không (ví dụ mạng cách
      ly hoàn toàn theo NFR-11)? Nếu có, cần lập kế hoạch deploy riêng.
- [ ] Đã từng deploy `stg/` lên server `green0tech.site` thật chưa, hay mới
      chuẩn bị kịch bản?
- [ ] Có cần bổ sung CI/CD (thay vì script build/push chạy tay) trước khi
      vào giai đoạn kiểm thử toàn diện không?

---

## 8. Phiên triển khai 2026-09-08 — chi tiết implement + bug tìm ra/đã sửa

### 8.0. Việc đã làm

Chạy `/dev-implement` tuần tự cho 8 spec (đều `Status: Approved` lúc bắt
đầu): `digitization-results-store`, `digitization-pipeline-02-ocr-stt-processing`,
`digitization-review-01-ocr`, `digitization-review-02-stt`,
`search-fulltext-01-tim-kiem-co-ban`, `search-fulltext-02-tim-kiem-nang-cao`,
`search-fulltext-03-dinh-vi-ket-qua`, `search-fulltext-04-dong-bo-chi-muc`.
Toàn bộ code + test đã vào repo (xem các mục 2.1–2.5, 2.2/2.4 ở trên). Sau
đó, theo yêu cầu người dùng, đã điều tra sâu và xử lý dứt điểm một loạt bug
bị che khuất từ trước (không IT test nào từng chạy được do migration `V21`
lỗi) — chi tiết dưới đây.

### 8.1. Regression cleanup ở IT test (do bảng mới từ `digitization-results-store`)

`digitization_run`/`document_digitization_status` (migration `V25`/`V26`) là
bảng mới, FK-reference `digitization_source`/`document` không cascade delete.
7 file IT test có sẵn từ trước (không nằm trong 8 spec hôm nay) thiếu dọn 2
bảng này trước khi xoá bảng cha ở `@BeforeEach`, gây lỗi FK khi chạy thật lần
đầu. **Đã sửa cả 7 file:** `DigitizationRunLifecycleServiceIT`,
`ReviewControllerIT`, `IngestControllerIT`, `DocTypeAssignmentIT`,
`DigitizationDispatchIT`, `GroupLifecycleIT`, `UploadSessionControllerIT`.

### 8.2. Bug hạ tầng: migration `V21` lỗi trên database rỗng

`V21__audit_log_seal_and_partition.sql` có `SELECT setval('audit_log_id_seq',
COALESCE((SELECT MAX(id) FROM audit_log), 0), true)` — khi `audit_log` rỗng
(database mới hoàn toàn, ví dụ Testcontainers hoặc **lần deploy production
đầu tiên**), `setval` với giá trị 0 vượt ngoài `MINVALUE=1` mặc định của
sequence, ném lỗi, khiến **toàn bộ chuỗi migration dừng lại** — đây là lý do
không IT test nào từng chạy thành công trước phiên này. **Đã sửa trực tiếp
trong `V21`** (không thêm migration mới — migration sau không cứu được vì
`V21` đã lỗi trước khi tới lượt chạy): bọc `setval` trong `DO $$ IF EXISTS
(SELECT 1 FROM audit_log) THEN ... END IF $$`. **Vì đây là sửa trực tiếp một
migration đã áp dụng, checksum Flyway đổi** — nếu có Postgres local/dev đã
từng migrate qua `V21` thành công trước đó, lần khởi động kế tiếp cần
`flyway repair` hoặc xoá volume Postgres để init lại.

### 8.3. Bug nghiêm trọng: OpenSearch Java client treo vô hạn (2 nguyên nhân độc lập)

Phát hiện khi điều tra vì sao chạy IT test hàng loạt hay bị "treo" không rõ
lý do. Đã xác minh bằng Docker (container thật + `curl` đối chiếu baseline)
và bài test tái hiện riêng (30 lần gọi `bulk()`/`updateByQuery()`/
`deleteByQuery()`).

- **Nguyên nhân #1 — xung đột phiên bản `httpcore5-h2`:** `httpclient5:5.4`
  cần đúng `httpcore5-h2:5.3` (theo property `httpcore.version` trong POM gốc
  của nó), nhưng bị ghim nhầm `5.3.6` (một bản vá mới hơn, tưởng an toàn).
  Giữa 2 bản này, `ClientH2StreamMultiplexerFactory` đổi chữ ký constructor
  không tương thích ngược → `NoSuchMethodError` khi mở kết nối HTTP/2 mới,
  nhưng lỗi này xảy ra **trên I/O reactor thread nền**, không phải thread gọi
  — nên không bao giờ tới được `CompletableFuture` đang `.get()` chờ, gây
  **treo vĩnh viễn thay vì báo lỗi**. **Fix:** `backend/pom.xml` — ghim đúng
  `5.3` (không phải `5.3.6`) cho cả `httpcore5`/`httpcore5-h2`. Xác minh: bài
  test tái hiện 30/30 thành công sau khi sửa (trước đó: `NoSuchMethodError`
  ngay từ lần gọi đầu).
- **Nguyên nhân #2 — `opensearch-java` client-side deserializer quá khắt
  khe:** mapping `segment-v1-index.json` dùng `stopwords_path` (file stopword
  tiếng Việt) thay vì mảng `stopwords` inline — **hợp lệ và chạy đúng ở
  server** (xác minh qua `_analyze` API: lọc đúng từ dừng), nhưng
  `opensearch-java 2.17.0` coi `stopwords` là bắt buộc, ném
  `MissingRequiredPropertyException` khi `SearchIndexBootstrap` tạo index lúc
  khởi động app — khiến index/alias **không bao giờ được tạo**. **Fix:**
  `SearchIndexBootstrap.java` — chuyển bước tạo index sang `client.generic()`
  (gửi JSON thô, bỏ qua bộ deserialize kiểu của client) thay vì
  `client.indices().create(typed request)`.
- **⚠️ Rủi ro production đã loại bỏ:** nếu OpenSearch chưa sẵn sàng lúc app
  khởi động (chậm start, restart, downtime tạm thời), `SearchIndexBootstrap`
  **từng có thể treo vĩnh viễn cả app** ở bước startup (bug #1) do gọi
  OpenSearch không qua timeout. Đã vá thêm: mọi lời gọi trong
  `SearchIndexBootstrap` giờ bọc timeout 10s + `OpenSearchClientConfig` có
  connect/response timeout mặc định (3s/30s) làm lớp bảo vệ thứ hai.
- **Kết quả kiểm chứng cuối:** 429/429 unit test, và trong 27 lớp IT test
  (chạy qua `maven-failsafe-plugin` mới thêm, mỗi lớp 1 JVM riêng — xem 8.6),
  không còn `NoSuchMethodError`/`MissingRequiredPropertyException`/hang nào.

### 8.4. Bug logic thật tìm thấy khi IT test chạy được lần đầu (đã sửa)

- `DigitizationRunLifecycleServiceImpl.createRun`: `save()` không flush ngay
  nên exception trùng active-run không được bắt kịp trong try/catch → đổi
  `saveAndFlush`.
- `DigitizationRunLifecycleServiceImpl.reviseSegment`: PATCH chỉ gửi
  `timeStartMs`/`timeEndMs` (không gửi `text`) làm `current_text` bị ghi đè
  `null`, vi phạm NOT NULL → sửa ngữ nghĩa "field bỏ qua = giữ nguyên".
- `reviseSegment`/`deleteSegment`: 2 request đồng thời cùng
  `expectedRevisionNo` → lỗi unique-constraint DB trồi lên như HTTP 500 thay
  vì 409 → thêm bắt lỗi + dịch sang `SegmentRevisionConflictException`.
- `GroupsService.update` (rename group): cùng lỗi flush-timing như trên,
  rename trùng tên trả 500 thay vì 409 → `saveAndFlush`.
- `TagsControllerIT` (2 test): gọi `entityManager.flush()` trực tiếp (không
  qua repository proxy) nên Spring không dịch được exception — sửa expected
  exception type, không phải bug app.
- `RbacControllerIT`: 2 "magic number" lệch thời — tổng permission catalog
  15→19 (đúng vì phản ánh migration thật, không reset); CAN_BO permissionCodes
  giữ nguyên 9 (test tự reset `role_permission` về 1 fixture cứng riêng, độc
  lập migration — **đã tự sửa sai 9→11 rồi tự phát hiện và sửa lại**, ghi lại
  làm bài học).
- `DocumentSharingIT` (4 case): lỗi setup "chicken-and-egg" — cho người
  không có quyền nhìn tài liệu thực hiện share (API `shareDocument` yêu cầu
  visibility trước, đúng thiết kế bảo mật) — sửa 3/4 case bằng cách chèn
  thẳng row `DocumentGroupShare` qua repository thay vì đi qua POST.
- `OutboxRetentionJobIT`: thiếu `@MockBean ObjectStorageService` (khác
  `IngestControllerIT` đã có sẵn pattern này) — không phải thiếu cấu hình
  sandbox.

### 8.5. Quyết định đã chốt cùng người dùng

- Frontend cho review (OCR/STT/ownership/SED): **gom thành 1 spec/slug
  riêng**, làm **sau khi** `digitization-review-03-ownership-transfer` được
  duyệt — không làm rải rác từng spec để tránh phải sửa lại layout. **Điều
  kiện này đã thoả 2026-09-08 phiên 3** (xem mục 8.9) — có thể bắt đầu viết
  spec UI ngay.
- Vá bug `V21` trực tiếp: **đồng ý** (xem 8.2).

### 8.6. Hạ tầng test: thêm `maven-failsafe-plugin`

Trước phiên này, `*IT.java` (Testcontainers) **không có cách chuẩn nào để
chạy** — `mvn test` bỏ qua chúng, không có `mvn verify` binding, mọi lần
chạy phải gọi `-Dtest=ClassName` thủ công. Đã thêm `maven-failsafe-plugin`
vào `backend/pom.xml` (`reuseForks=false` — mỗi lớp IT 1 JVM/container
riêng, tránh Testcontainers/Spring context bị chia sẻ chéo giữa các lớp).
Giờ `mvn verify` chạy đúng chuẩn Maven.

### 8.7. Còn tồn đọng — CHƯA sửa, ngoài phạm vi phiên này

- [x] ~~`AuditControllerIT` (7/8 test lỗi) — test cleanup `DELETE FROM
      audit_log` trực tiếp, bị chặn bởi trigger append-only~~ — **đã sửa
      2026-09-09** (8/8 pass), xem mục 1 hàng #11.
- [x] ~~`NotesControllerIT` (1 test) — lỗi chưa điều tra, thuộc spec
      `classification-notes-02` cũ~~ — **đã điều tra + sửa 2026-09-18/20,
      deploy stg**: **bug sản phẩm thật**, không phải lỗi test.
      `ApiExceptionHandler` thiếu handler cho `ConstraintViolationException`
      (constraint `@Validated` trên tham số controller, ví dụ `@Size
      @RequestParam`, khác với `MethodArgumentNotValidException` của
      `@Valid @RequestBody` — chỉ vế sau có handler) nên request-param không
      hợp lệ thoát ra ngoài như `ServletException` chưa bắt và trả **500**
      thay vì **400**. Đã thêm `handleConstraintViolation` vào
      `ApiExceptionHandler` (xem file đó, method cùng tên) — chỉ sửa đúng lỗ
      hổng này, không đổi hành vi của request nào đang chạy đúng.
- [x] ~~`NotesOutboxAtomicityIT` (1 test) — lỗi chưa điều tra~~ — **đã sửa
      2026-09-18**: 2 nguyên nhân, cả hai đều ở bản thân test, không phải bug
      app. (1) Thiếu `@MockBean ObjectStorageService` — thiếu credential S3
      thật trong môi trường test khiến `ObjectStorageService` ném
      `NullPointerException` lúc khởi tạo, kéo sập cả `ApplicationContext`
      qua `auditRetentionJob` (bean `@Scheduled` khác dùng chung context) —
      đã thêm mock, cùng mẫu các IT khác đã có sẵn (`OutboxRetentionJobIT`,
      `SearchIndexOutboxPollerParkingIT`, `IngestControllerIT`). (2) Assertion
      `status().isInternalServerError()` không bao giờ đạt được: `MockMvc`
      rethrow exception chưa được `@ExceptionHandler` nào bắt thay vì tự
      dựng trang lỗi 500 của servlet container, và `ApiExceptionHandler`
      không bắt `RuntimeException` trần (xem `audit-logging.md` §4.7 — cân
      nhắc thêm catch-all `Exception.class` rồi cố ý quyết định không thêm,
      2026-09-20) — đổi assertion sang
      `assertThatThrownBy(...).hasRootCauseMessage(...)`, đúng với thứ test
      này thực sự cần khẳng định (rollback `document_note` cùng transaction
      với `outbox_event` ghi lỗi), không phải mã trạng thái HTTP.
- [x] ~~`SearchIndexOutboxPollerIT` (1 test) — `documentTagRepository.insert()`
      (native `@Modifying` query) gọi ngoài transaction boundary trong chính
      test, không phải bug app~~ — **chẩn đoán cũ chính xác, đã sửa
      2026-09-18**: `SimpleJpaRepository` mặc định `@Transactional(readOnly =
      true)`, nên gọi thẳng `documentTagRepository.insert(...)` (`@Modifying`
      native query) từ một test method thường không có transaction ghi bao
      quanh ném `TransactionRequiredException`. Sửa bằng bọc lời gọi trong
      `new TransactionTemplate(transactionManager).executeWithoutResult(...)`
      thay vì thêm `@Transactional` lên test method — vì poller đọc row này
      từ thread scheduler riêng, dữ liệu seed phải **commit thật**, không
      được nằm trong transaction test-scoped rồi rollback ở cuối.
- [ ] UI cho `search-fulltext` — hoàn toàn chưa có kế hoạch, xem mục 2.5.
- [ ] `web/` (submodule) đang có **rất nhiều thay đổi chưa commit** từ trước
      phiên này (commit cuối 2026-07-10, nhiều file có mtime rải rác
      08/17-09/04 — xoá `case-files/`, sửa lớn `upload/page.tsx`,
      `admin/accounts/page.tsx`, `roles.ts`, `next-auth.d.ts`...). Không
      liên quan `digitization-review-05-frontend` (đã xác nhận qua mtime),
      nhưng đáng lưu ý: `web/` chưa commit gì kể từ 07/10 dù có nhiều tính
      năng mới (personal/tags/notes, RBAC, audit, giờ thêm digitization
      review...) đã lên. ~~Có 4 test cũ đang fail trong nhóm thay đổi này
      (`upload/__tests__/uploadOneChunked.test.ts`), chưa điều tra.~~ —
      **đã điều tra + sửa xong 2026-09-09**: không phải bug ứng dụng, mà là
      2 assertion lỗi thời trong chính 2 file test (`upload/` và
      `personal/`) chưa cập nhật theo tính năng theo dõi tiến độ upload
      (`loadedBytes` trên `FileOutcome.uploading`, dùng thật để tính % ở cả
      2 trang) được thêm vào sau khi test được viết:
      (1) `toHaveBeenCalledWith(0, { phase: 'uploading' })` so khớp tuyệt
      đối, thiếu field `loadedBytes` bắt buộc → đổi sang
      `expect.objectContaining(...)`;
      (2) test AC9 (retry) giả định chuỗi phase đúng 2 phần tử
      `['uploading', 'ingested'/'done']`, nhưng thực tế có thêm 1 lần cập
      nhật `'uploading'` hợp lệ sau mỗi chunk PUT thành công (vẫn đúng tinh
      thần AC9 — không có phase 'retrying'/lạ nào chen vào) → đổi sang kiểm
      tra mọi phần tử trước phần tử cuối đều là `'uploading'`. Full suite
      frontend giờ **28/28 file, 212/212 test pass**.
- [ ] BA story `digitization-results-store` vẫn ghi `Status: Draft` dù
      DEV-spec cùng slug đã `Approved` và implement xong — cần cập nhật lại
      dòng Status cho khớp thực tế (không chặn gì, chỉ là tài liệu bị lệch).

### 8.8. Phiên 2 (cùng ngày 2026-09-08): implement `digitization-review-04-sed-summary`

Chạy `/dev-implement` cho spec cuối cùng của nhóm `digitization-review`
(DEV-spec đã Approved từ trước nhưng không nằm trong đợt batch 8 spec ở mục
8.0). Đã implement xong phần backend (Task 1-7, 13-14):

- `ReviewController` mở rộng cho task `sed`/`video_summary`: `GET
  /runs/{id}` trả thêm `soundEventLabel`, `sourceReferences` (liên kết ngược
  tới video gốc qua `SoundEventRepository.findByPromotedSegmentIdIn`),
  `staleWarning`; validate 422 (PATCH task=sed kèm mốc thời gian), 409 (POST
  task∈{sed,video_summary}), 409 (DELETE task=video_summary).
- File mới: `SourceReferenceView.java`. Không cần migration mới (vẫn `V28`).
- **Phát hiện nhỏ:** `ReviewControllerIT` thiếu dọn `sound_event`
  (`promoted_segment_id` FK không cascade tới `content_segment`, V26) — lần
  đầu bảng này được ghi trong test của module `review` — đã sửa theo đúng
  pattern cleanup đã thống nhất từ phiên trước (con trước cha).
- **Test:** 445/445 unit test, 19/19 IT test mới (`ReviewControllerIT`) pass;
  xác nhận không ảnh hưởng 223 IT test toàn hệ thống (10 lỗi cũ, đúng 4 lớp
  đã biết ở mục 8.7, không lớp nào thuộc `digitization`/`review`).
- **Frontend (Task 8-12) cố ý dừng** — cùng lý do đã nêu ở 8.5/2.3: `web/`
  chưa có route/component nào cho `digitization-review` (kể cả phần -01/-02
  đã xong backend từ trước cũng chưa có UI). Xác nhận lại khuyến nghị: 1 spec
  frontend riêng ("app shell"), làm sau khi `-03` được duyệt.

### 8.9. Phiên 3 (cùng ngày 2026-09-08): review & duyệt `digitization-review-03-ownership-transfer`

Rà soát BA story + DEV-spec (cả hai đang Draft) trước khi duyệt, đối chiếu
với schema thật thay vì chỉ đọc nội dung spec. 4 điểm cần xác nhận, đã chốt
cùng người dùng — cả 4 đều theo đúng phương án DEV/reviewer đề xuất:

1. **Vị trí màn hình:** khu vực quản trị chung `/admin/document-ownership`
   (không phải module riêng trong `digitization/review/`).
2. **`reason` khi chuyển quyền sở hữu:** không bắt buộc, tối đa 500 ký tự.
3. **Ràng buộc nhóm giữa A/B:** không có — R3 chọn tự do bất kỳ R1 đang
   hoạt động, đúng tinh thần "R3 vận hành toàn hệ thống".
4. **Phát hiện khi review (không phải câu hỏi mở sẵn có trong BA):**
   `DocumentOwnershipRow` (API `GET /admin/document-ownership`) trong bản
   DEV-spec gốc chỉ có `ocrStatus`/`sttStatus`, nhưng bảng
   `document_digitization_status` (`V26`) thật ra có **4** cột trạng thái
   (`ocr_status`, `stt_status`, `sed_status`, `video_summary_status`) — thiếu
   sót này chỉ lộ ra vì `-04-sed-summary` (mục 8.8) implement **sau** khi
   spec `-03` được viết. Đã sửa DEV-spec: bổ sung `sedStatus`/
   `videoSummaryStatus` vào contract + thêm 1 test case tương ứng trước khi
   duyệt.

**Kết quả:** cả BA story và DEV-spec đã đổi `Status: Draft` → `Approved`.
Chưa chạy `/dev-implement` — đây là việc tiếp theo cho slug này (Task 1-6
backend; Task 7 frontend cố ý hoãn theo quyết định 8.5).

### 8.10. Phiên 4 (cùng ngày 2026-09-08): implement `digitization-review-03-ownership-transfer`

Chạy `/dev-implement` ngay sau khi duyệt (phiên 3, mục 8.9). Implement xong
Task 1-6 (backend):

- File mới trong package `documents/` (theo đúng đề xuất Task 2 của spec —
  không đặt ở `digitization/review/` vì chỉ đụng cột `document.uploader_sub`,
  không phụ thuộc `digitization_run`/`content_segment`, tránh coupling
  ngược): `DocumentOwnershipController` (`GET`/`POST /admin/document-ownership...`,
  quyền `PERM_document.transfer_ownership`), `DocumentOwnershipService`
  (validate B trùng A/không tồn tại/disabled/thiếu role CAN_BO → update →
  audit, cùng 1 transaction), `DocumentOwnershipRepository` (JdbcTemplate,
  JOIN `document_digitization_status`, đủ 4 trạng thái theo chốt phiên 3),
  `DocumentOwnershipRow`, `TransferOwnershipDto`.
- Sửa `AuditService.ALLOWED_METADATA_KEYS` — thêm
  `document.ownership_transferred`. Không cần migration mới (permission
  `document.transfer_ownership` đã seed sẵn từ `V28` ở `-01-ocr`, xác nhận
  không trùng lặp).
- **Test:** 7/7 unit test (`DocumentOwnershipServiceTest`), 11/11 IT test
  (`DocumentOwnershipControllerIT` — phủ đủ AC1-AC4/AC7/AC8 + 403 role sai +
  response metadata-only). Regression toàn hệ thống: 595 test, chỉ 1 lỗi cũ
  đã biết (`NotesControllerIT`, không liên quan thay đổi hôm nay).
- **Task 7 (frontend `/admin/document-ownership`) cố ý chưa làm** — gộp
  chung 1 spec UI với -01/-02/-04 theo quyết định 8.5.

**Kết quả:** toàn bộ 4 story của `digitization-review` (FR-18) nay đã
**implement backend xong** — chỉ còn thiếu UI cho cả nhóm (xem mục 2.3).

### 8.11. Phiên 5 (cùng ngày 2026-09-08): viết BA story + DEV-spec `digitization-review-05-frontend`

Khảo sát `web/` trước khi đề xuất — quy ước hiện có: Next.js App Router,
Client Component, Tailwind thuần (không thư viện UI), `apiJson`/`apiFetch`,
`AppShell`/`AdminTabs`/`features.ts` (đã có sẵn entry `/digitization`,
`enabled:false`, chỉ cần bật). Không viết SA riêng — quy ước frontend đã là
chuẩn chung của repo, không phải quyết định kiến trúc mới.

**1 điểm cần xác nhận đã hỏi người dùng:** màn hình OCR có làm đầy đủ ảnh
trang gốc + overlay bbox ngay hay hoãn (chỉ danh sách text trước)? Đề xuất
của tôi là **hoãn** (Epic A/worker OCR thật chưa xây, chưa có dữ liệu bbox
thật để kiểm chứng; backend cũng chưa có endpoint trả ảnh trang). **Người
dùng chọn làm đầy đủ ngay** — khi kiểm tra lại, rủi ro tôi lo ngại thấp hơn
dự kiến: định dạng toạ độ `bbox_polygon` (tỉ lệ 0-1 trên trang, không phải
pixel) **đã chốt sẵn** trong migration `V26` từ 2026-09-07, nên overlay
implement được đúng theo quy ước đã có, không phải đoán.

**Kết quả — 2 file mới:**
- `docs/02-ba/stories/digitization-review-05-frontend.md` — 7 story (hàng
  chờ; OCR ảnh+bbox; STT player; SED chỉ đọc; tóm tắt video + liên kết
  nguồn + cảnh báo `staleWarning`; xác nhận; chuyển quyền sở hữu R3). Không
  lặp lại AC nghiệp vụ đã chốt ở 4 file backend nguồn — chỉ định nghĩa lớp
  AC riêng của giao diện.
- `docs/03-dev/specs/digitization-review-05-frontend.md` — route
  `/digitization`, `/digitization/runs/[runId]`, `/admin/document-ownership`
  (tab mới); component dùng chung `OcrPageViewer`/`SegmentPlayer`/
  `SegmentList`. **1 endpoint backend mới** (Task 1, duy nhất phần không
  phải frontend thuần): `GET .../runs/{runId}/pages/{pageNo}/image` — dùng
  Apache PDFBox 3.0.8 (dependency mới, Apache-2.0, pure-Java) rasterize
  trang PDF ở 150 DPI; nguồn ảnh đơn thì trả thẳng không qua PDFBox. Dùng
  lại `requireOwnedReviewableRun` đã có, không tạo permission mới.

**Cả 2 file: `Status: Draft`, chưa duyệt, chưa implement.** Việc tiếp theo
cho slug này: duyệt rồi `/dev-implement`.

### 8.12. Phiên 6 (cùng ngày 2026-09-08): review lại `digitization-review-05-frontend` trước khi duyệt

Đối chiếu trực tiếp nội dung 2 file Draft với **code Java thật** (không chỉ
spec cũ) — cách làm giống phiên review `-03-ownership-transfer` (mục 8.9).
Tìm ra 3 điểm:

1. **Lỗi mâu thuẫn nội bộ, tự sửa không cần hỏi:** DEV-spec gộp nhầm
   `video_summary` vào nhóm dùng chung `SegmentPlayer` với STT/SED — nhưng
   `V26` xác nhận `time_start_ms`/`time_end_ms` luôn `NULL` cho
   `content_source='summary'` (tóm tắt video không có mốc thời gian riêng
   để phát). BA Story 5 vốn đã đúng (chỉ có link mở run khác), chỉ DEV-spec
   sai — đã sửa route logic, mục 4.3, task breakdown, comment cấu trúc file.
2. **Phân trang segment vượt giới hạn API** (đã hỏi, chọn phương án khuyến
   nghị): `GET runs/{id}` tối đa 100 segment/lần gọi, tài liệu OCR nhiều
   trang có thể vượt quá — thiết kế gốc giả định sai 1 lần gọi là đủ. Đã
   sửa: gọi lặp `segmentPage` tới khi hết dữ liệu trước khi lọc theo trang
   vật lý ở client (mục 4.2.1 mới).
3. **Lọc theo task ở hàng chờ** (đã hỏi, chọn phương án khuyến nghị): API
   chỉ nhận 1 `task`/lần gọi — chốt dùng tab ("Tất cả" không gửi `task` +
   1 tab/loại), không làm multi-select.

**Kết quả:** cả 2 file đổi `Status: Draft` → `Approved`. Sẵn sàng
`/dev-implement` — đề xuất chia 2 lệnh theo đúng ranh giới Task 1 (backend,
độc lập) vs Task 2-9 (frontend, phụ thuộc Task 1 đã xong để có endpoint ảnh
trang gọi tới):

```
/dev-implement docs/03-dev/specs/digitization-review-05-frontend.md — chỉ làm Task 1 (backend: endpoint render ảnh trang, PDFBox)

/dev-implement docs/03-dev/specs/digitization-review-05-frontend.md — Task 1 (backend) đã xong, làm tiếp Task 2-9 (toàn bộ frontend)
```

### 8.13. Phiên 7 (cùng ngày 2026-09-08): implement `digitization-review-05-frontend` — xong toàn bộ Task 1-9

Người dùng chạy `/dev-implement` — subagent hoàn tất **cả 9 Task trong 1
lượt** (không dừng lại sau Task 1 như 2 lệnh đề xuất ở mục 8.12, nhưng kết
quả tương đương "cả 2 lệnh đã chạy xong").

**Backend (Task 1):** endpoint mới nằm ngay trong `ReviewController`
(`renderPdfPage` dùng `PDFBox.PDFRenderer.renderImageWithDPI`, đúng thiết
kế §3.1) — khớp chính xác với spec, không lệch. Test:
`ReviewControllerPageImageTest` 5/5 pass; `mvn test` toàn hệ thống (không
riêng module này) vẫn `BUILD SUCCESS`.

**Frontend (Task 2-9):** file thật đã tạo — `src/app/digitization/page.tsx`,
`src/app/digitization/runs/[runId]/page.tsx`,
`src/app/admin/document-ownership/page.tsx`,
`src/components/digitization/{OcrPageViewer,SegmentPlayer,SegmentList}.tsx`
+ test tương ứng. Xác minh: `npm run build` sạch (route `/digitization`,
`/digitization/runs/[runId]`, `/admin/document-ownership` lên đúng danh
sách route Next.js in ra); `npx vitest run` cho riêng thư mục
`digitization/` — **15/15 test pass**.

**Phát hiện khi xác minh (không phải do phiên này gây ra):** chạy
`npx vitest run` toàn bộ `web/` (không giới hạn thư mục) lộ ra 4 test fail
ở `upload/__tests__/uploadOneChunked.test.ts` — đối chiếu mtime file
(04/09, so với commit cuối `web/` là 10/07) xác nhận đây là 1 phần trong
khối lượng thay đổi lớn **chưa commit từ trước** trong submodule `web/`
(xoá `case-files/`, sửa lớn `upload/page.tsx` và nhiều file khác — xem mục
8.7), **không liên quan** tới `digitization-review-05-frontend`. Không sửa,
không hoàn tác gì — chỉ ghi nhận.

**Kết quả:** toàn bộ 4 story của `digitization-review` (FR-18) nay có **cả
backend lẫn UI hoàn chỉnh**, xác minh test xanh ở cả 2 phía. Đây là nhóm
tính năng lớn nhất trong checklist chính thức "xong" (trừ Epic A — AI
worker Python thật, vẫn cần để có dữ liệu thật thay vì fixture khi thao
tác UI).

### 8.14. Phiên 8 (2026-09-08/09): xác minh UI thật trên trình duyệt (claude-in-chrome)

Theo yêu cầu người dùng "hãy xác minh UI trên trình duyệt" — build lại +
restart container `backend`/`web` với code mới nhất, seed dữ liệu fixture
trực tiếp vào Postgres (2 documents, đủ 4 loại run `ocr/stt/sed/summary`,
kèm case biên: STT chồng lấn (`overlapsAdjacent`), OCR độ tin cậy thấp,
`staleWarning=true` cho video_summary), upload PDF thật + video mp4
(ffmpeg) lên SeaweedFS, rồi đăng nhập thật qua Keycloak và thao tác từng
màn hình.

**Kết quả — PASS toàn bộ:**
- Đăng nhập `canbo1` (R1) → Dashboard → "Số hóa OCR / STT": hàng chờ hiện
  đúng 3/4 run (OCR/STT/SED — filter tab hoạt động đúng); run `video_summary`
  đã `confirmed` nên đúng như thiết kế không nằm trong hàng chờ.
- **OCR** (`OcrPageViewer`): ảnh trang PDF render thật qua endpoint PDFBox
  mới, 3 bbox overlay khớp 3 đoạn; click-to-highlight hoạt động 2 chiều
  (card ↔ bbox đổi viền màu khi chọn).
- **STT** (`SegmentPlayer`): video load/play được, cảnh báo "Chồng lấn"
  (`overlapsAdjacent`) hiện đúng trên 2/3 đoạn theo fixture; click đoạn →
  card highlight (nền xám) + player seek.
- **SED**: danh sách sự kiện âm thanh hiện đúng nhãn AI + độ tin cậy +
  timestamp.
- **video_summary**: banner vàng staleWarning hiện đúng nội dung cảnh báo;
  đúng thiết kế đã chốt là **không có** player riêng; link tham chiếu nguồn
  ("Đoạn #0, 0:00"...) mở đúng run STT gốc ở tab mới.
- Đăng nhập `quantri1` (R3) → `/admin/document-ownership`: bảng metadata-only
  hiện đủ 4 cột trạng thái (`sedStatus`/`videoSummaryStatus` mới thêm ở
  mục 8.9 khớp đúng dữ liệu), dialog "Chuyển quyền sở hữu" lọc danh sách
  người nhận đúng AC1 (chỉ R1 đang hoạt động, loại trừ chính người nạp hiện
  tại). Thực hiện thử 1 lần chuyển thật (canbo1 → canbo2) để xác minh AC3:
  sau khi chuyển, tài liệu biến mất khỏi danh sách của canbo1 ngay lập tức
  — đúng.

**Phát hiện phụ (đã xử lý an toàn):** tài khoản nhận thử nghiệm `canbo2`
không có trong `realm-export.json` — hoá ra là tài khoản **thật** đã có sẵn
nhiều tài liệu thật trong DB (không phải tài khoản demo do phiên này tạo).
Đã **hoàn tác ngay** (chuyển ngược `canbo2 → canbo1`) sau khi xác minh xong
để không để lại thay đổi ngoài ý muốn trên dữ liệu đó.

**Nghi vấn ban đầu — đã điều tra, KHÔNG phải bug:** lần đầu thử đăng nhập
`canbo2` rồi mở `/digitization` bị chặn "Không có quyền truy cập — dành cho
Cán bộ (R1)", trong khi `/admin/document-ownership` lại liệt kê `canbo2`
như một người nhận R1 hợp lệ — tưởng là 2 nguồn xác định role bị lệch nhau.
Điều tra bằng Keycloak Admin API (`GET .../users/{id}/role-mappings/realm`)
xác nhận `canbo2` có đúng role `can-bo`, và client `backend` có
`fullScopeAllowed=true` + client scope `roles` — cấu hình phía Keycloak
hoàn toàn đúng. Thử đăng nhập lại `canbo2` lần 2, chờ xác nhận trước khi
điều hướng, thì lộ ra nguyên nhân thật: **mật khẩu `Passw0rd!` sai cho tài
khoản này** ("Tên đăng nhập hoặc mật khẩu không đúng") — lần đầu tôi đã
điều hướng thẳng sang `/digitization` ngay sau khi bấm đăng nhập mà không
đợi xác nhận, nên nhầm "đăng nhập thất bại" thành "đăng nhập thành công
nhưng thiếu quyền". Không có bug phân quyền nào ở đây; đã sửa lại nhận định
này trong checklist để không để sai thông tin.

**Kết luận:** `digitization-review-05-frontend` xác minh xong bằng trình
duyệt thật, không có bug mới nào tồn đọng (khác với các mục đã biết ở 8.7).
AC2 của Story 7 (B thấy tài liệu trong hàng chờ sau khi nhận chuyển giao)
chưa test lại bằng tài khoản demo có mật khẩu xác định (`canbo1` xác nhận
`AC3` — tài liệu biến mất khỏi hàng chờ người chuyển — cùng logic filter
`uploader_sub` mà AC2 dùng ngược lại, nên rủi ro còn lại thấp) — có thể bổ
sung sau nếu cần bằng một tài khoản demo khác (`canbo3`/`canbo_lanhdao1`).

### 8.15. Phiên 9 (2026-09-09): viết + duyệt + implement `search-fulltext-05-frontend` (UI tìm kiếm toàn văn)

Theo yêu cầu "triển khai tiếp feature nào (trừ AI)" → chọn UI cho
`search-fulltext` vì backend (`POST /search`) đã xong từ 2026-09-08 nhưng
**chưa có UI nào cả** — dead code. Không viết SA mới (`search-fulltext.md`
Approved đã tự nêu UI/UX → BA); chỉ viết BA + DEV-spec mới
(`docs/02-ba/stories/search-fulltext-05-frontend.md`,
`docs/03-dev/specs/search-fulltext-05-frontend.md`), theo đúng mẫu đã dùng
cho `digitization-review-05-frontend`.

**Review trước khi duyệt phát hiện 2 vấn đề thật, đã xác nhận với người
dùng và sửa vào tài liệu trước khi implement:**
- Tín hiệu "đang chờ kiểm duyệt" (BA Story 4 AC1) ban đầu suy luận sai từ
  `contentSource` của đoạn khớp — sửa dùng field mới
  `SearchHit.documentDigitizationStatus` (từ `document_digitization_status`
  có sẵn) cho chính xác tuyệt đối.
- Tóm tắt video khớp tìm kiếm không có liên kết ngược tới đoạn STT/OCR gốc
  — thêm field mới `SegmentHit.sourceReferences` (tái dùng
  `SourceReferenceView`/`ReviewSegmentRepository.resolveSourceReferences`
  đã có), UI hiển thị link "Đoạn #N, mm:ss" mở đúng run gốc, giống hệt
  `digitization-review`.
- Cũng phát hiện độc lập (không cần hỏi, tự sửa luôn): endpoint ảnh trang
  hiện có (`/digitization-review/runs/{runId}/pages/{pageNo}/image`) chỉ
  cho phép **chính chủ nạp** truy cập — không dùng được cho tài liệu chia
  sẻ nhóm trong kết quả tìm kiếm. DEV-spec thêm endpoint mới
  `GET /documents/{id}/pages/{pageNo}/image` (phạm vi chung qua
  `DocumentScopeGuard`), tách logic PDFBox thành bean dùng chung
  `PageImageRenderer` để không trùng code với `ReviewController`.

**Implement (`/dev-implement`, cả 10 Task trong 1 lượt):**

Backend: `PageImageRenderer` (bean mới, tách từ `ReviewController`);
`DocumentsController` thêm `GET /documents/{id}/pages/{pageNo}/image`
(404 khi ngoài phạm vi, đúng convention `DocumentScopeGuard`, khác 403 của
`ReviewController` cũ); `SearchHit`/`SegmentHit` thêm 2 field mới;
`SearchResultHydrator` hydrate cả hai theo lô (không N+1); `HighlightMapper`
thêm nhánh `content_source="summary"`. Test mới:
`DocumentsControllerPageImageTest` (5/5, gồm đúng case "chia sẻ nhóm" mà
endpoint cũ không xử lý được), `SearchResultHydratorTest` (7/7, lớp test
mới ngoài dự kiến ban đầu của spec — hydrator gốc dùng mock nên cần unit
test riêng), `HighlightMapperTest` (+5 case mới). `mvn test`: 616/617 pass
trong 1 lượt chạy đầy đủ (77/88 lớp), 1 fail
(`NotesControllerIT.ingestWithANoteLongerThan4000Characters...`, module
ghi chú không liên quan, đã biết tồn đọng từ trước — xem mục 8.7); riêng
`SearchScopeIT` (IT sát tính năng này nhất) chạy lại độc lập: **16/16
pass**.

Frontend: trang mới `web/src/app/search/page.tsx` + `SearchFilterPanel`,
`SearchResultCard`, `SearchPageViewer` (component ảnh trang **viết riêng**,
không sửa `OcrPageViewer` hiện có — đúng ưu tiên "không sửa nếu tránh
được" trong spec); `SegmentPlayer` dùng lại nguyên vẹn, không sửa. Hàm
render highlight an toàn (`search-highlight.tsx`, không
`dangerouslySetInnerHTML`) verify bằng test có input giống mã độc, render
ra text trơ. `features.ts`: bật `search.enabled = true`. `npm run build` +
`npm run lint` sạch; `npx vitest run`: toàn bộ 37/37 test cũ của
`components/digitization/__tests__` **vẫn xanh** (xác nhận không phá vỡ
UI đã có), cộng 28 test mới (search) đều pass. 4 test fail thuộc
`uploadOneChunked.test.ts` (2 file, personal + upload) — tồn đọng từ trước
(mục 8.7/8.13), không liên quan tính năng này.

**Còn lại (đã xử lý ở mục 8.16):** chưa xác minh UI thật trên trình duyệt.

### 8.16. Phiên 10 (2026-09-09): xác minh UI tìm kiếm trên trình duyệt thật — phát hiện 2 bug hạ tầng nghiêm trọng, độc lập với tính năng vừa implement

Build lại + restart `backend`/`web`. Dùng chính màn hình kiểm duyệt để xác
nhận 3 run OCR/STT/SED còn "chờ duyệt" từ fixture cũ (đăng nhập canbo1, bấm
"Xác nhận" thật qua UI — không seed SQL) nhằm có nội dung thật đã lập chỉ
mục để tìm kiếm.

**Bug #1 (Nghiêm trọng — chặn toàn bộ đồng bộ chỉ mục tìm kiếm):**
`DocumentIndexFieldsBuilder.build()` (`search/index/`, thuộc
`search-fulltext-04`) gọi thẳng
`warehouseFolderRepository.findById(document.getFolderId())` và
`document.getFolderId().toString()` mà **không kiểm tra null** — trong khi
`document.folder_id` **được phép NULL theo thiết kế** (`V9` migration, FK
`ON DELETE SET NULL` khi thư mục bị xoá). Với tài liệu có `folder_id =
NULL`, mọi lần index (xác nhận review, đổi nhãn, đổi ghi chú...) ném
`InvalidDataAccessApiUsageException: The given id must not be null`.
**Nghiêm trọng hơn:** lỗi này xảy ra ngay trong transaction đang chạy nên
làm transaction bị đánh dấu rollback-only — dẫn tới bước ghi lại "đã thử
1 lần" (`attempts`/`last_error`) ở khối catch cũng bị rollback theo
(`UnexpectedRollbackException`), khiến **`attempts` không bao giờ tăng,
event lỗi này thử lại vô hạn mỗi 2 giây, mãi mãi**. Vì hàng đợi xử lý
đúng thứ tự (FIFO theo `id`, xem `OutboxEventRepository.claimNextForIndexing`),
**1 tài liệu không có thư mục là đủ để chặn vĩnh viễn việc lập chỉ mục cho
mọi tài liệu khác nạp sau đó** — phát hiện được vì đúng lúc backend khởi
động lại, `DocTypeBackfillRunner` gán nhãn cho 1 tài liệu fixture thiếu
`folder_id`, phát sinh sự kiện `document.classification.changed` bị kẹt.
Khảo sát nhanh: chỉ 2/25 tài liệu trong DB hiện tại thiếu `folder_id`
(đúng 2 tài liệu fixture của các phiên trước) — chưa phải vấn đề lan rộng
trên dữ liệu thật, nhưng là lỗ hổng thiết kế thật, có thể xảy ra bất cứ
lúc nào một thư mục bị xoá. **Đã gỡ kẹt tạm thời** (xác nhận với người
dùng): 1 lần đánh dấu event kẹt là `processed_at` thủ công, và gán tạm
`folder_id` hợp lệ cho 2 tài liệu thiếu — **chưa sửa code**, cần một PR
riêng: (a) `DocumentIndexFieldsBuilder.build()` xử lý `folder_id = NULL`
(bỏ qua `folderPathIds`/dùng danh sách rỗng thay vì gọi `findById(null)`),
(b) tách bước ghi `attempts`/`last_error` ra khỏi transaction đã có khả
năng rollback-only (ví dụ `REQUIRES_NEW` cho riêng bước ghi lỗi, hoặc bắt
lỗi ở tầng ngoài `@Transactional`).

**Bug #2 (Trung bình — ảnh hưởng trực tiếp tính năng vừa làm hôm nay):**
`DigitizationRunLifecycleServiceImpl.confirmRun()` (được gọi khi R1 bấm
"Xác nhận" ở màn kiểm duyệt) **không gọi**
`documentDigitizationStatusRepository.upsertTaskStatus(..., STATUS_CONFIRMED)`
— chỉ cập nhật `digitization_run.review_state` và ghi outbox
`review.confirmed`, không cập nhật bảng đọc `document_digitization_status`
(bảng mà field `SearchHit.documentDigitizationStatus` mới thêm hôm nay phụ
thuộc vào, và cũng là bảng `/admin/document-ownership` — mục 8.9/8.14 —
đang hiển thị). Hệ quả: **sau khi xác nhận một run theo đúng luồng nghiệp
vụ bình thường, tài liệu vẫn hiện sai "đang chờ kiểm duyệt" trên kết quả
tìm kiếm** (đã tái hiện trực tiếp: xác nhận STT/SED qua UI thật, DB vẫn
lưu `stt_status=in_review`/`sed_status=pending_review` dù run đã
`confirmed`). Có `DocumentDigitizationStatusReconciliationJob` chạy mỗi
5 phút nhưng **chỉ phát hiện + log cảnh báo, không tự sửa** (đọc code xác
nhận: `reconcileOne()` không có bước ghi lại) — log thực tế đã bắt được
đúng 2 tài liệu lệch ngay sau khi xác nhận. **Đã sửa dữ liệu thủ công**
(2 dòng, đúng giá trị `reconcileOne()` đã tính) để tiếp tục xác minh UI —
**chưa sửa code**: cần thêm `upsertTaskStatus(documentId, task,
STATUS_CONFIRMED)` vào `confirmRun()` (và kiểm tra `reconfirmSegment`/luồng
sửa-sau-khi-đã-confirm có cùng thiếu sót không).

**Kết quả xác minh UI (sau khi gỡ kẹt) — PASS toàn bộ:**
- Tìm từ khoá tiếng Việt có dấu ra đúng kết quả, "Khoảng N kết quả", 0 kết
  quả hiển thị đúng thông báo.
- Highlight `<em>` render an toàn (đã xác nhận bằng mắt, khớp test).
- Badge "Tự động xác nhận" (`confirmSource=auto`) hiển thị đúng.
- Bộ lọc "Loại nội dung" (mediaType) lọc đúng; gợi ý 0 kết quả **"Bỏ lọc
  'Thư mục / Loại nội dung / Nguồn xác nhận' (≈1 kết quả)"** hiển thị đúng
  nhãn tiếng Việt và ước lượng đúng; bấm vào tự bỏ đúng bộ lọc và tìm lại
  thành công.
- OCR: bấm "Xem trên ảnh trang" mở đúng ảnh trang PDF (qua endpoint **mới**
  `GET /documents/{id}/pages/{pageNo}/image`) kèm khung đỏ khoanh đúng vị
  trí khớp từ khoá — xác nhận endpoint mới + `SearchPageViewer` mới đều
  đúng.
- STT/SED: bấm "Nghe/Xem đoạn này" mở đúng player (dùng lại `SegmentPlayer`
  nguyên vẹn qua `GET /documents/{id}/content`).
- Banner "Nội dung đang chờ kiểm duyệt" **đúng theo field mới**
  `documentDigitizationStatus` (đã kiểm chứng cả 2 chiều: hiện đúng khi
  status ≠ confirmed do Bug #2, biến mất đúng sau khi sửa dữ liệu) —
  **không** còn dựa vào suy luận `contentSource` như thiết kế cũ.
- Tóm tắt video (`content_source=summary`): sau khi tự tạo 1 outbox event
  `review.confirmed` thủ công để lập chỉ mục nội dung (run này được xác
  nhận trực tiếp qua SQL ở phiên trước, chưa từng qua `confirmRun()` thật
  nên chưa từng phát outbox event) — kết quả hiển thị đúng link **"Đoạn
  #0, 0:00"** thay vì badge "chỉ định vị được ở cấp tài liệu", bấm vào mở
  đúng `/digitization/runs/{sourceRunId}` (run STT gốc) ở tab mới. Xác
  nhận đầy đủ tính năng quan trọng nhất đã thêm hôm nay (Story 3 AC4).

**Minh bạch về các thay đổi dữ liệu đã thực hiện trong phiên này** (đều
trên dữ liệu fixture của chính các phiên trước, không đụng tới 23 tài liệu
thật khác): 1 outbox event đánh dấu processed thủ công, `folder_id` của 2
tài liệu fixture, `document_digitization_status` của 2 tài liệu fixture, 1
outbox event `review.confirmed` tạo thủ công cho run tóm tắt video. Không
sửa file code nào trong phiên này — chỉ sửa dữ liệu để gỡ kẹt và xác minh;
cả 3 bug code (2 ở trên + 1 phát hiện thêm) **đã sửa xong** — xem mục 8.17.

### 8.17. Phiên 11 (2026-09-09): sửa dứt điểm cả 3 bug — 2 đã biết (mục 8.16) + 1 phát hiện thêm khi kiểm chứng

Theo yêu cầu "sửa 2 bug này ngay". Sửa cả 3 (bug thứ 3 lộ ra ngay khi kiểm
chứng bug #2 xong, xem bên dưới), viết test hồi quy cho từng bug, chạy toàn
bộ `mvn test` (476/476 pass, không phá gì), rồi build lại `backend`, tái
hiện đúng kịch bản lỗi gốc trên Docker thật cho cả 3 để xác nhận đã hết —
không chỉ tin vào unit test.

**Bug #1 — `DocumentIndexFieldsBuilder.build()` NPE khi `folder_id = NULL`,
chặn vĩnh viễn hàng đợi đồng bộ (đã sửa):**
- `DocumentIndexFieldsBuilder.java`: bọc điều kiện `folderId != null` trước
  khi gọi `warehouseFolderRepository.findById`/`.toString()`; `folderId`/
  `folderPathIds` là `null`/rỗng khi tài liệu không có thư mục.
- `SearchIndexOutboxPoller.java`: tách `processOne(event)` ra
  `processOneInNewTransaction()` (`@Transactional(REQUIRES_NEW)`) — gốc rễ
  thật của việc "kẹt vĩnh viễn" là bất kỳ exception nào ném ra từ một lời
  gọi repository Spring Data (tự có `@Transactional` riêng, tham gia vào
  transaction ngoài) đều đánh dấu **toàn bộ** transaction ngoài
  rollback-only — kể cả khi `claimAndProcessOne()` bắt exception đó — nên
  bước ghi `attempts`/`last_error` sau đó luôn thất bại âm thầm
  (`UnexpectedRollbackException`, bị nuốt bởi error handler của
  scheduler). Chạy `processOne` trong transaction `REQUIRES_NEW` cô lập
  hoàn toàn — sửa tận gốc cơ chế retry, không chỉ riêng case `folder_id`.
- Test mới: `DocumentIndexFieldsBuilderTest.folderIdAndFolderPathIdsAreEmptyWhenTheDocumentHasNoFolder`
  (verify không gọi `warehouseFolderRepository` khi `folderId=null`).
- **Xác minh thật trên Docker:** null tạm `folder_id` của 1 tài liệu fixture,
  phát 2 event (`document.classification.changed`, `document.ingested`) —
  cả hai xử lý thành công, không lỗi, `folder_id`/`folder_path_ids` đúng
  `null`/`[]` trong OpenSearch. Khôi phục lại `folder_id` sau khi xác minh.

**Bug #2 — `confirmRun()` không cập nhật `document_digitization_status`
(đã sửa):**
- `DigitizationRunLifecycleServiceImpl.confirmRun()`: thêm
  `documentDigitizationStatusRepository.upsertTaskStatus(documentId, task,
  STATUS_CONFIRMED)` ngay sau khi lưu run.
- Tiện thể sửa luôn 1 chỗ thiếu tương tự phát hiện khi rà lại:
  `applyReviewStateEditSideEffect()` (nhánh `pending → in_review` khi sửa
  đoạn lần đầu) cũng chưa từng cập nhật read-model — thêm
  `upsertTaskStatus(..., STATUS_IN_REVIEW)`. Dùng thẳng hằng số thay vì
  suy qua `DigitizationStatusMapper.activeRunTaskStatus(processingStatus,
  reviewState)` — tại đúng 2 điểm này, trạng thái mới **đã biết chắc**
  (confirmed/in_review), suy qua `processingStatus` chỉ thêm 1 phụ thuộc
  không cần thiết.
- Test mới/mở rộng: `confirmRun_zeroEditedSegments_isManualUnchanged` (+
  verify `upsertTaskStatus(..., STATUS_CONFIRMED)`),
  `reviseSegment_firstEditOfAPendingRun_...` (+ verify
  `upsertTaskStatus(..., STATUS_IN_REVIEW)` đúng 1 lần, lần sửa thứ 2
  không gọi thêm).
- **Xác minh thật trên Docker:** tạo 1 run OCR giả ở trạng thái `pending`
  cho tài liệu fixture, bấm "Xác nhận" thật qua giao diện (canbo1) —
  `document_digitization_status.ocr_status` chuyển thành `confirmed`
  **ngay lập tức**, không cần đợi job reconciliation 5 phút. Xoá run test
  + khôi phục lại `ocr_status=not_applicable` sau khi xác minh.

**Bug #3 (mới phát hiện khi xác minh bug #2, có sẵn từ trước ở
`search-fulltext-01/03`, không liên quan việc vừa implement) —
`SearchResultHydrator` sập với lỗi 400 "UUID string too large" bất cứ khi
nào kết quả tìm kiếm chứa 1 đoạn ghi chú/tên tệp (segment ID dạng
`{documentId}:title`/`:note`, không phải UUID thật):**
- `SearchResultHydrator.java`: thêm hàm `tryParseUuid()` (bắt
  `IllegalArgumentException`, trả `null`), dùng thay cho gọi thẳng
  `UUID.fromString()` ở 2 chỗ (lọc `segmentIds` cần tra `content_segment`,
  và tra map theo từng segment khi build response) — 1 pseudo-id giờ được
  xử lý giống hệt "không có dòng `content_segment`" (vốn đã đúng cho
  note/title), thay vì làm sập toàn bộ response tìm kiếm.
- Mức độ nghiêm trọng: **cao thực tế** — bất kỳ từ khoá nào tình cờ khớp cả
  tên tệp/ghi chú lẫn nội dung của cùng 1 tài liệu (rất phổ biến, ví dụ tên
  tệp chứa từ khoá tìm kiếm) đều làm cả trang tìm kiếm báo lỗi, không phải
  chỉ ẩn 1 kết quả.
- Test mới: `SearchResultHydratorTest.pseudoSegmentIdFromATitleOrNoteMatchDoesNotCrashTheWholeHydrate`.
- **Xác minh thật trên Docker:** tái hiện đúng truy vấn đã gây lỗi 400
  ("kết nối máy chủ", khớp cả nội dung OCR lẫn tên tệp
  "Huong-dan-ket-noi-may-chu-A100.pdf") — sau khi sửa, trả về đúng "Khoảng
  1 kết quả" với 2 đoạn trích, đoạn khớp tên tệp hiển thị đúng badge "Chỉ
  định vị được ở cấp tài liệu" thay vì lỗi.

**Kết quả:** `mvn test` 476/476 pass (bao gồm 9 test mới/mở rộng cho 3 bug
trên). Cả 3 bug đã xác nhận hết trong Docker thật, không chỉ qua unit test
mock. Không còn bug tồn đọng nào từ phiên xác minh UI tìm kiếm (mục 8.16).

---

### 8.18. Phiên 12 (2026-09-09): rà soát AC + bổ sung 4 test còn thiếu cho `delete-restore-trash` — phát hiện và sửa 1 bug audit thật

Sau khi implement backend xong (mục 2.7, mục 1 hàng #20), rà lại AC checklist
2 file BA story dựa trên đọc thật test/code (36/41 AC verify được, 5 còn lại
là FE/hiệu năng — không phải backend). Rà soát lộ ra 4 khoảng trống test cụ
thể, đã bổ sung đủ:

1. `POST /documents/trash-preview` — có code (`TrashService.preview`) nhưng
   0 test → thêm 1 unit test (`TrashServiceTest`) + 1 integration test
   (`TrashControllerIT`).
2. Audit `document.trash.denied`/`document.restore` — code có ghi nhưng
   chưa test khẳng định `audit_log` thật có dòng đó → mở rộng 2 test
   `TrashControllerIT` có sẵn để query `audit_log` qua `AuditLogRepository`.
   **Query này lộ ra bug thật** (xem dưới).
3. `groupsLostEffect` (Story 6 AC6, cảnh báo nhóm chia sẻ đã bị xóa) — mọi
   test cũ đều stub rỗng → thêm 1 test dựng nhóm thật bị xóa mềm rồi phục
   hồi tài liệu, assert cảnh báo đúng và phục hồi không bị chặn.
4. Chưa có test JWT role R2 (LANH_DAO)/R3 (QUAN_TRI) gọi thẳng API trash để
   thấy 403 tận mắt (chỉ có `RolePermissionSeedTest` xác nhận mapping
   permission→role) — thêm helper `leader()` + 4 test 403 cho
   `/documents/trash` và `/trash/{id}/restore`.

**Bug thật phát hiện + đã sửa — `document.trash.denied` không bao giờ được
ghi lại:** `TrashService.trash()` là `@Transactional`; nhánh từ chối do
ngoài phạm vi FR-08 gọi `auditService.record(...)` (ghi `document.trash.denied`)
rồi `throw` ngay trong cùng transaction đó → rollback xóa luôn bản ghi audit
vừa insert. `AuditInterceptor`'s fallback (401/403/5xx) cũng không bù lại
được vì `AUDIT_RECORDED_ATTRIBUTE` (request attribute, không nằm trong DB
transaction) đã bị `record()` set thành `true` trước khi rollback, nên
interceptor tưởng đã có audit và bỏ qua. Kết quả: mọi lượt xóa mềm bị từ
chối do ngoài phạm vi **không để lại bất kỳ dòng audit_log nào**, dù code
nhìn như đang ghi — vi phạm trực tiếp FR-09/FR-10 cho đúng nhánh nhạy cảm
nhất (từ chối truy cập). Sửa bằng thêm
`AuditService.recordIndependently(entry)` (`Propagation.REQUIRES_NEW`, chỉ
dùng cho case denial/rejection không có mutation nào cần bảo vệ cùng —
**không** đổi `record()` gốc để giữ nguyên coupling "audit fail → business
rollback" mà Story 1 AC3 của `audit-logging` yêu cầu ở mọi nơi khác), đổi
call site này sang dùng method mới. Cùng kỹ thuật `REQUIRES_NEW` đã dùng ở
Bug #1 mục 8.17 (`SearchIndexOutboxPoller`) cho một vấn đề tương tự (transaction
ngoài bị kéo theo transaction lồng bên trong).

**Kết quả:** `TrashControllerIT` 18/18 pass sau khi sửa (trước đó 1 fail).
`mvn test` toàn hệ thống 775 test, 0 failures, 3 errors — đúng 3 lỗi cũ đã
biết (`NotesControllerIT`, `NotesOutboxAtomicityIT`, `SearchIndexOutboxPollerIT`),
không có hồi quy mới. Đã cập nhật lại chú thích AC6 Story 1 trong BA story
01 từ "bug, chưa sửa" thành "Verified" kèm mô tả fix.
