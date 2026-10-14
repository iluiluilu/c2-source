# QA Report: folder-upload-display-name

> File: `docs/04-qa/reports/folder-upload-display-name.md` — Owner: QA
> Nguồn story: `docs/02-ba/stories/folder-upload-display-name.md` (STORY-folder-upload-display-name)
> Nguồn spec: `docs/03-dev/specs/folder-upload-display-name.md` (SPEC-folder-upload-display-name)
> Ngày review: 2026-09-30
> Commit/branch reviewed: HEAD (667e2a5) + working tree chưa commit (48 file thay đổi; `web/` bị .gitignore ở repo gốc nên không hiện trong git status)

## Kết luận

**PASS WITH ISSUES**

Toàn bộ 29 AC có code và test tương ứng; backend unit + IT liên quan (171 test) và web (363 test) đều xanh, `tsc` và `lint` sạch. Không có lỗi chặn. Còn vài lệch nhỏ so với spec và vài khoảng hở test (mục Non-blocking). Trước khi chốt cần một lượt test tay kéo-thả trên trình duyệt thật (spec mục 5, R6). Việc này không tự động hoá được nên chưa có bằng chứng.

## Đối chiếu Acceptance Criteria

Bằng chứng: test tự động đã chạy (xanh) cộng đọc code. AC về giao diện dựa vào test vitest, chưa kiểm tay trên trình duyệt.

| AC | Kết quả | Bằng chứng |
|---|---|---|
| AC-1 | Pass | `web/src/lib/dropEntries.ts`; `dropEntries.test.ts` "walks several roots plus loose files" (gộp 1 batch ở `app/upload/page.tsx`, `app/personal/page.tsx`). Chưa test tay trình duyệt. |
| AC-2 | Pass | `grep webkitdirectory\|dirInputRef` ở `web/src` chỉ còn 1 dòng comment trong `dropEntries.ts:4`; không còn input thư mục. |
| AC-3 | Pass | `dropEntries.test.ts` "strips the leading slash and the file name", "keeping the dropped folder name in relativePath". |
| AC-4 | Pass | `dropEntries.test.ts` "reads every page when a folder has more than 100 entries (AC-4)". |
| AC-5 | Pass | `V34` thêm `personal_file.relative_path`; `PersonalControllerIT` (relativePath); `PersonalService.java:427`. |
| AC-6 | Pass | `IngestController.java:200`, `PersonalController.java:206`; IT `nameCheckReportsTakenNamesInOrderIgnoringCaseAndTrash`, `nameCheckRejectsMoreThan1000FilesAndUnknownFolder`; FE `uploadFlow.ts:20` `NAME_CHECK_BATCH_SIZE=1000`. |
| AC-7 | Pass | `web/src/lib/displayName.ts` + `displayName.test.ts` (vector chung); `IngestControllerIT` tải lại bằng tên gốc. |
| AC-8 | Pass | `displayName.ts` sinh hậu tố; `displayNameFlow.test.ts` (upload, personal); `DisplayNameValidatorTest`. |
| AC-9 | Pass | Vector chung `display-name-vectors.json` (2 bản giống hệt, `diff` SAME) chạy ở `DisplayNameValidatorTest` và `displayName.test.ts`. |
| AC-10 | Pass | Cùng vector; IT `nameOf512CodePointsInAPathOf1024CodePointsIsAcceptedAndStillUnique`. |
| AC-11 | Pass | `IngestOutcome.java:8` đã bỏ `ALREADY_EXISTS`; grep không còn dùng; IT `reuploadWithTheSameDisplayNameIsAConflictNotASkip`, `reuploadWithASuffixedDisplayNameCreatesANewCopy`. |
| AC-12 | Pass | `uploadFlow.ts:61-66` (lỗi/sai độ dài kết quả thì coi tất cả `taken`); `displayNameFlow.test.ts`. |
| AC-13 | Pass | `DisplayNameValidator.java`; `DisplayNameValidatorTest`; IT `invalidDisplayNameIsRejectedWith422AndReason`. |
| AC-14 | Pass | Unique `UQ_document_display_name_alive` (V34); `DisplayNameValidator.java:198` 409 `DISPLAY_NAME_CONFLICT`; FE `MAX_SUFFIX_RETRIES`. Test FE 3 lần nằm trong `displayNameFlow.test.ts`. |
| AC-15 | Pass | IT `completeWithATakenDisplayNameIs409KeepsTheSessionAndACommitAgainNeedsNoNewChunks` (`UploadSessionControllerIT`); `uploadOneChunked.test.ts`. |
| AC-16 | Pass | Index chỉ tính `deleted_at IS NULL`; IT `nameCheckReportsTakenNamesInOrderIgnoringCaseAndTrash`, `reuploadOverATrashedCleanDocumentIsNotADuplicate`. |
| AC-17 | Pass | `@PreAuthorize document.upload` (403 với leader/admin: IT `nameCheckRequiresTheUploadPermission`); giới hạn 1000 (`NameCheckSupport.java`); rate limit. Xem Non-blocking N1 về 403 theo thư mục. |
| AC-18 | Pass | `IngestController.java:264`; `PersonalToWarehouseIT.nameCheckExpandsTheSelectionLikeStartAndReportsTakenNames`. |
| AC-19 | Pass | IT `relativePathIsFolderTreePathPlusThePersonalFilesOwnRelativePath`, `folderSelectionKeepsSelectedFolderNameInRelativePath...`. |
| AC-20 | Pass | `PersonalToWarehouseService.java:255-261, 295-305`; IT `missingOrInvalidDisplayNameFailsOnlyThatItem`, `conflictItemCanBeRenamedThreeTimesAndTheFourthIsRejected`, `renameRetryCountIsCappedAtThreeByTheServer`. |
| AC-21 | Pass | `TrashService.java:381-395` (409 `NAME_CONFLICT` theo `display_name`); IT `nameConflictOnRestoreIsRejectedUnlessAutoRenameRequested`; `RestoreDialog.tsx:246-258` chỉ 2 nút; `RestoreDialog.test.tsx` NAME_CONFLICT. |
| AC-22 | Pass, còn lưu ý | `TrashService.java:387-405` gán cả 2 tên, thử lại tối đa 3 lần, audit `oldDisplayName`/`newDisplayName` (dòng ~440), outbox reindex. Lưu ý N3 về race sau khi lưu. |
| AC-23 | Pass | `RestoreDialog.tsx:233` ô "Áp dụng cho tất cả (N tệp trùng tên)"; `app/trash/page.tsx:197`; `RestoreDialog.test.tsx` "NAME_CONFLICT with other conflicting files" và "without other conflicts". |
| AC-24 | Pass | `lib/nameTooltip.ts`; DTO không có `storageKey` (grep `storageKey` ở `web/` không có kết quả); IT kiểm response không lộ `storageKey`. |
| AC-25 | Pass | `document-v1-index.json:26-27`: `document_name` giữ `original_name`, `display_name` là `index:false`; `SearchIndexOutboxPoller.java:474`; `SearchIndexOutboxPollerTest` xanh. |
| AC-26 | Pass | `DocumentsController.java:112` và `PersonalController.java:274` dùng `getOriginalName()` với `filename*=UTF-8''`; IT `contentDispositionUsesOriginalNameAndNotDisplayName`. |
| AC-27 | Pass (chỉ qua test tự động) | `app/upload/__tests__/page.test.tsx`, `displayNameFlow.test.ts`; `nameTooltip.ts`. Chưa kiểm tay giao diện. |
| AC-28 | Pass | `DisplayNameValidator.java:97` `ORIGINAL_NAME_TOO_LONG`; IT `initRejectsAnOverlongOriginalNameBeforeAnyChunkAndABadDisplayName`; `PersonalToWarehouseService.java:259`; FE `uploadFlow.ts:34`. |
| AC-29 | Pass | `NameCheckRateLimiter.java` (cửa sổ 1 phút, 429, log WARN, cấu hình `app.name-check.rate-limit-per-minute` tại `application.yml:82`); `NameCheckRateLimiterTest`; IT `nameCheckIsRateLimitedPerUserAndEndpointWith429` (chỉ endpoint `/ingest`). |

## Kết quả test/lint đã chạy

```
cd web && npx tsc --noEmit                 -> exit 0, không lỗi
cd web && npm run lint                     -> sạch (eslint không báo lỗi)
cd web && npx vitest run                   -> 39 file, 363 test passed
cd backend && mvn -o test -Dtest='DisplayNameValidatorTest,NameCheckRateLimiterTest,TrashServiceTest,
   PersonalToWarehouseServiceTest,PersonalServiceTest,UploadSessionServiceTest,
   SearchIndexOutboxPollerTest,AuditInstrumentationCoverageTest'
                                            -> Tests run: 102, Failures: 0, Errors: 0
cd backend && mvn -o test -Dtest='IngestControllerIT,PersonalControllerIT,PersonalUploadSessionControllerIT,
   PersonalToWarehouseIT,TrashRestoreConflictControllerIT,UploadSessionControllerIT'
   (Docker sẵn có, Testcontainers chạy được)
                                            -> Tests run: 69, Failures: 0, Errors: 0; BUILD SUCCESS
   (IngestControllerIT 23, PersonalToWarehouseIT 17, PersonalControllerIT 10, TrashRestoreConflictControllerIT 7,
    UploadSessionControllerIT 7, PersonalUploadSessionControllerIT 5)
```

Không chạy toàn bộ 33 IT của repo, chỉ các IT thuộc phạm vi feature. Các IT khác bị sửa nhưng chưa chạy: `DigitizationDispatchIT`, `NotesControllerIT`, `DocTypeAssignmentIT`, `UploadCleanupJobIT`. Ghi nhận đầu vào từ bộ nhớ dự án: baseline IT từng hỏng vì nạp ApplicationContext, nhưng ở lần chạy này các IT trên nạp và chạy bình thường.

## Vấn đề chặn (Blocking)

Không có.

## Đề xuất cải thiện (Non-blocking)

- [ ] N1 (AC-17): name-check kho chung chỉ kiểm quyền `document.upload` và thư mục tồn tại. Spec nói "403 khi không có quyền ghi thư mục đích". Nếu kho chung không có ACL ghi theo thư mục thì đã đủ, cần DEV xác nhận. `UploadBatchService` cũng chỉ kiểm `folderExists`, nên hai luồng nhất quán.
- [ ] N2 (lệch spec, có ghi chú trong migration): `V34` đổi dấu phân cách `chr(0)` thành `'/'` (Postgres không cho `chr(0)`), hợp lý. Unique `document` còn loại thêm `status <> 'rejected'` ngoài `deleted_at IS NULL`. Spec Q3 và SA QĐ2 nói "mọi status". IT `aFileFlaggedInfectedStillHoldsItsNameButARejectedFormatDoesNot` xác nhận có chủ đích. Nên cập nhật spec mục 2/Q3 cho khớp code.
- [ ] N3 (AC-22): sau khi `saveAndFlush` trong `restore`, nếu thua race bị vi phạm unique thì trả 409 luôn, chưa sinh lại tên (chỉ retry ở bước kiểm tra trước khi lưu). Spec: "vướng unique thì sinh lại tối đa 3 lần". Khả năng xảy ra thấp, cần cân nhắc bọc vòng lặp quanh cả bước lưu.
- [ ] N4 (AC-29): IT rate limit chỉ phủ `/ingest/name-check`; `/personal/name-check` và `/ingest/from-personal/name-check` chỉ có ở unit `NameCheckRateLimiterTest`. Nên thêm case 429 cho hai endpoint còn lại.
- [ ] N5: các ô Definition of Done của spec (mục 11) chưa được tick. Còn thiếu: gắn "Đã thay thế bởi `folder-upload-display-name`" vào file cũ (SA mục 7) và số liệu coverage. Cả hai thuộc DEV/BA sau khi Approved.
- [ ] N6: story/spec ghi rõ cần test tay kéo-thả trên Chrome/Edge/Firefox/Safari (R6) và xác nhận không hiện popup gốc. QA chưa thực hiện được (không có trình duyệt).

## Kiểm tra thêm

- [x] Security checklist trong spec: validate `displayName`/`originalName` ở mọi điểm nhận (ingest, personal, upload-session, from-personal, PUT đổi tên); name-check chỉ trả `taken`, ≤1000, rate limit; `storage_key` không có trong DTO web; `Content-Disposition` dùng `filename*=UTF-8''`; SQL dùng tham số. Các ô checklist mục 8 trong spec chưa được DEV tick.
- [x] Observability: log INFO name-check (endpoint, số tệp, số taken, không log tên tệp), WARN 429 (không log tên), INFO log restore, audit restore ghi tên cũ/mới. Chưa thấy metric Micrometer (`name_check_requests_total`, ...); spec ghi "nếu đã có", nên không chặn.
- [x] Migration/rollback: `V34` backfill `display_name = original_name`, tự gắn hậu tố 8 ký tự id cho bản trùng để tạo được unique mới, `DROP INDEX` cho V10; chạy được trong IT (Flyway áp dụng V34 trên Testcontainers). Rollback vẫn là khôi phục backup như spec.
