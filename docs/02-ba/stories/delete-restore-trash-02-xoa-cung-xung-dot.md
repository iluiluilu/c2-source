# User Story: delete-restore-trash-02-xoa-cung-xung-dot

> File: `docs/02-ba/stories/delete-restore-trash-02-xoa-cung-xung-dot.md` — Owner: BA
> Nguồn kiến trúc: `docs/01-sa/architecture/delete-restore-trash.md`
> Status: Approved (2026-09-09)

> **Cập nhật 2026-09-09:** Nguồn (`delete-restore-trash.md`) đã **Approved**
> — cả 3 câu hỏi mở cho DEV bên dưới đã có câu trả lời trong tech spec
> (`docs/03-dev/specs/delete-restore-trash-02-xoa-cung-xung-dot.md`), xem
> chi tiết ở từng mục.

File này gồm 2 story: (5) hệ thống tự động xóa cứng theo lịch (retention
365 ngày), (6) xử lý xung đột khi phục hồi tài liệu từ thùng rác. Xóa mềm
và luồng phục hồi cơ bản (không xung đột) xem file
`delete-restore-trash-01-xoa-mem-thung-rac.md`.

---

## Story 5: Hệ thống tự động xóa cứng tài liệu hết hạn thùng rác (FR-27, retention)

**As a** hệ thống (job nền `trash-purge`)
**I want** tự động xóa cứng các tài liệu đã ở trong thùng rác quá 365 ngày
**So that** dữ liệu không cần thiết không tồn đọng vĩnh viễn, tuân thủ
chính sách lưu trữ, và giải phóng dung lượng kho — mà không cần thao tác
thủ công nào

### Acceptance Criteria

```
Given tài liệu có purge_state = 'deleted' và purge_after <= now()
When  job trash-purge chạy theo lịch hằng ngày (dùng ShedLock để chỉ 1
      instance chạy dù backend scale nhiều instance)
Then  tài liệu được đưa vào lô xử lý 200 bản ghi, chuyển purge_state =
      'purging' ngay khi được chọn (khóa hàng, commit ngay)
```

- [x] AC-1: Trong lô xử lý, với mỗi tài liệu: xóa dữ liệu phái sinh liên
      quan + bản ghi `document_group_share` + phát outbox `document.purged`
      để gỡ khỏi chỉ mục tìm kiếm, **trước khi** đụng tới object trên
      SeaweedFS.
      *(Verified: `TrashPurgeServiceTest#purgeOneDeletesDerivativesPurgesObjectAndWritesBiaMo`
      (thứ tự gọi: xóa tag/note/share/digitization source trước, rồi mới
      `purgeWarehouseObject`), `TrashPurgeServiceIT#runOnceTransitionsThroughPurgingToPurgedAndWritesTheFullBiaMo`
      (thật trên Postgres, có outbox `document.purged`).)*
- [x] AC-2: Object trên SeaweedFS chỉ bị xóa khi **không còn** bản ghi
      `document` sống nào khác trỏ tới cùng `storage_key` (bảo vệ trường
      hợp khử trùng lặp theo `sha256`); nếu object đã bị xóa từ trước (do
      lượt chạy trước đó bị gián đoạn) thì coi là **thành công**
      (idempotent), không báo lỗi.
      *(Verified cả 2 chiều: `TrashPurgeServiceTest#purgeOneNeverPurgesObjectWhenAnotherNonPurgedDocumentSharesSha256`,
      `TrashPurgeServiceIT#dedupBySha256_objectSurvivesUntilTheLastReferencingDocumentIsPurged`
      + `#purgeOneNeverPurgesObjectWhileAnotherDocumentStillReferencesIt` (test cả 2 thứ tự
      purge, đúng yêu cầu §7 rủi ro của spec). Idempotent khi object đã mất
      trước: `TrashPurgeServiceTest#purgeOneIsANoOpWhenAlreadyPurgedByAResumedAttempt`.)*
- [x] AC-3: Sau khi xóa, giữ lại **dòng "bia mộ" tối thiểu** trên
      `document`: `id`, `sha256`, `original_name`, `deleted_at`,
      `purged_at`; các cột nội dung/metadata khác bị xóa/rỗng hóa.
      *(Verified: cả `TrashPurgeServiceTest#purgeOneDeletesDerivativesPurgesObjectAndWritesBiaMo`
      và `TrashPurgeServiceIT#runOnceTransitionsThroughPurgingToPurgedAndWritesTheFullBiaMo`
      assert từng cột theo đúng bảng §2.2 của tech spec.)*
- [x] AC-4: Ghi audit `document.purge` với `actor = system` cho mỗi tài liệu
      bị xóa cứng; **tuyệt đối không** xóa các bản ghi audit đã có liên
      quan đến tài liệu đó (bao gồm cả `document.trash`, `document.restore`
      trước đây nếu có).
      *(Ghi audit `document.purge` verified: `TrashPurgeServiceTest` (assert
      `auditCaptor`) + `TrashPurgeServiceIT` (query `audit_log` thật).
      "Không xóa audit cũ" verified bằng đọc code — `TrashPurgeService`
      không có câu lệnh `DELETE`/xóa nào nhắm vào `audit_log`; chưa có test
      riêng dựng sẵn `document.trash` cũ rồi purge để khẳng định nó còn
      nguyên sau đó.)*
- [x] AC-5: Job **idempotent + resumable**: nếu tiến trình chết giữa chừng
      khi một số bản ghi đang ở `purging`, lượt chạy kế tiếp phải xử lý
      tiếp mà không xóa nhầm/không bỏ sót; cần cơ chế đưa các bản ghi kẹt ở
      `purging` quá lâu trở lại `deleted` để được chọn xử lý lại (tham số
      thời gian chờ cụ thể — xem "Câu hỏi mở cho DEV").
      *(Verified: `TrashPurgeServiceIT#recoverStuckPurgingResetsOnlyThoseOlderThanTheThreshold`
      (chỉ đưa về `deleted` bản ghi kẹt quá `STUCK_THRESHOLD`, không đụng
      bản ghi mới), `PurgeStuckRecoveryJobTest#recoversUsingA2HourThreshold`.)*
- [x] AC-6: Lỗi khi xử lý 1 tài liệu trong lô **không** chặn việc xử lý các
      tài liệu còn lại trong cùng lô; có log/metric số lô lỗi để cảnh báo
      vượt ngưỡng.
      *(Verified: `TrashPurgeServiceTest#runOnceCountsOneFailureButKeepsProcessingTheRestOfTheBatch`
      — 1 document lỗi, document còn lại trong lô vẫn `PURGED`, `summary.errors()`
      đếm đúng.)*
- [x] AC-7: **Không có** thao tác xóa cứng thủ công ở MVP — R1 và R2 không
      có quyền này dưới bất kỳ hình thức nào (không có nút "Xóa vĩnh viễn"
      trên UI, không có API tương ứng cho người dùng cuối). R3 chỉ được
      chỉnh tham số `retention.trash_retention_days` (xác nhận 2 bước,
      thuộc `system-config`, ngoài phạm vi story này).
      *(Verified bằng đọc code: `TrashController` chỉ có
      `list/stats/findOne/restore/restoreBatch`, không có endpoint xóa
      cứng nào; job `TrashPurgeJob` chỉ chạy qua `@Scheduled`, không expose
      API. Tham số retention thuộc `system-config`, ngoài phạm vi.)*
- [x] AC-8: Job chạy ngoài giờ cao điểm nghiệp vụ (mặc định 03:00 hằng
      ngày), không làm chậm giao dịch của người dùng đang thao tác trên hệ
      thống trong lúc job chạy.
      *(Verified bằng đọc code: `TrashPurgeJob` có
      `@Scheduled(cron = "${app.trash.purge-cron:0 0 3 * * *}")` +
      `@SchedulerLock`. "Không làm chậm giao dịch người dùng" là hệ quả
      thiết kế (mỗi document 1 transaction riêng, không giữ transaction
      dài) — không có test đo tải thực tế.)*
- [x] AC-9: Xử lý theo lô 200 bản ghi/lượt, có nghỉ giữa các lô, không giữ
      transaction dài; thời gian chạy job tăng tuyến tính theo số bản ghi
      đến hạn, không có bước nào có độ phức tạp vượt tuyến tính.
      *(Verified: `TrashPurgeService.BATCH_SIZE = 200` (đọc code);
      `TrashPurgeServiceTest#runOnceStopsAfterAPartialBatch` +
      `#runOnceKeepsPollingWhileBatchesAreFull` xác nhận đúng cơ chế lô +
      dừng khi lô không đầy. Nghỉ giữa lô qua `Thread.sleep(batchPauseMs)`
      (đọc code, test dùng `batchPauseMs=0` để chạy nhanh nên không đo
      thời gian nghỉ thật).)*

---

## Story 6: Xử lý xung đột khi phục hồi tài liệu từ thùng rác (FR-27, nhánh R1)

**As a** R1 đang phục hồi tài liệu từ thùng rác
**I want** được hướng dẫn rõ ràng khi gặp xung đột (thư mục cha đã bị xóa,
trùng tên, tài liệu đã hết hạn...)
**So that** tôi không gặp lỗi mơ hồ, có lựa chọn xử lý phù hợp, và không
bao giờ bị mất dữ liệu hoặc bị ghi đè nhầm

### Acceptance Criteria

- [x] AC-1 — **Thư mục cha đã bị xóa:**
  ```
  Given tài liệu cần phục hồi có folder_id = NULL (thư mục cha đã bị xóa
        trước đó, theo ON DELETE SET NULL)
  When  tôi bấm "Phục hồi"
  Then  hệ thống KHÔNG tự phục hồi về "chỗ cũ"; API yêu cầu tôi chọn
        targetFolderId; UI gợi ý thư mục gần nhất còn tồn tại (dựa theo
        đường dẫn cũ) để tôi chọn nhanh
  ```
  *(Verified end-to-end: `TrashRestoreConflictControllerIT#folderMissingSuggestsNearestSurvivingAncestorAndTargetFolderIdResolvesIt`
  (xóa 2 cấp thư mục lồng nhau, restore trả đúng `suggestedFolderId`, sau
  đó phục hồi thành công với `targetFolderId` đó);
  `TrashServiceTest#restoreRejectsWithFolderMissingWhenOriginalFolderGoneAndNoTargetGiven`
  + `#restoreSuggestsNearestSurvivingAncestorWhenFolderMissing`;
  `WarehouseFolderServiceTest#deleteSnapshotsAncestorPathOntoEveryDocumentFiledHereRegardlessOfStatus`
  (hook snapshot ở `WarehouseService.deleteFolder`, không ghi đè snapshot
  cũ).)*
- [x] AC-2 — **Trùng tên tại đích:**
  ```
  Given vị trí đích (thư mục hiện tại hoặc thư mục tôi vừa chọn) đã có một
        tài liệu đang hoạt động cùng tên (vi phạm
        UQ_document_folder_relpath_name_clean)
  When  tôi phục hồi
  Then  hệ thống trả 409 kèm 3 lựa chọn: (a) đổi tên tự động
        "<tên> (khôi phục yyyyMMdd-HHmm)", (b) chọn thư mục khác, (c) hủy;
        hệ thống KHÔNG bao giờ tự động ghi đè tài liệu đang tồn tại
  ```
  *(Verified: `TrashRestoreConflictControllerIT#nameConflictOnRestoreIsRejectedUnlessAutoRenameRequested`
  (409 + `suggestedName`, rồi `AUTO_RENAME` phục hồi thành công với tên
  mới), `TrashServiceTest#restoreRejectsWithNameConflictWhenNotAutoRenamed`
  + `#restoreAutoRenamesWhenNameConflictAndAutoRenameRequested`.)*
- [x] AC-3 — **Kho đầy (≥95%):**
  ```
  Given kho dùng chung đang ở ngưỡng chặn nạp mới (≥95%)
  When  tôi phục hồi một tài liệu từ thùng rác
  Then  thao tác KHÔNG bị chặn — dung lượng của item trong thùng rác đã
        được tính vào dung lượng đã dùng từ trước, phục hồi không làm
        tăng dung lượng thực tế
  ```
  *(Verified bằng đọc code: `TrashService.restore()`/`restoreBatch()`
  không gọi `WarehouseCapacityService`/`assertIngestAllowed()` ở bất kỳ
  nhánh nào — đúng ý "không chặn". Chưa có test end-to-end dựng kho ở
  ≥95% rồi restore để thấy tận mắt không bị chặn.)*
- [x] AC-4 — **Hạn mức cá nhân (FR-14):** không áp dụng cho luồng này — kho
      dùng chung không tính vào quota cá nhân của người dùng; DEV không
      được áp nhầm rule kiểm tra quota cá nhân vào API phục hồi.
      *(Verified bằng đọc code: không có tham chiếu nào tới
      `PersonalService`/quota cá nhân trong `TrashService`.)*
- [x] AC-5 — **Người nạp/người xóa đã bị vô hiệu hóa:**
  ```
  Given tài khoản của người đã xóa tài liệu (deleted_by_sub) đã bị vô hiệu
        hóa
  When  người nạp gốc (uploader_sub) truy cập thùng rác và bấm phục hồi
  Then  phục hồi vẫn diễn ra bình thường — hệ thống KHÔNG kiểm tra trạng
        thái active của deleted_by_sub/uploader_sub, danh tính chỉ mang
        tính lịch sử/audit
  ```
  *(Verified bằng đọc code: `requireRestorePermission()` chỉ so khớp
  `sub`, không tra `account.status`. Có test tương tự về mặt dữ liệu ở
  `TrashControllerIT#unrecoverableAccountsCountsOnlyWhenBothDeleterAndUploaderAreDisabled`
  (Story 3 AC-4) nhưng đó là test cho thống kê, không phải cho chính thao
  tác restore với tài khoản bị khóa — chưa có test restore trực tiếp với
  tài khoản `locked`.)*
- [x] AC-6 — **Nhóm được chia sẻ đã bị xóa:**
  ```
  Given một nhóm từng được chia sẻ tài liệu này đã bị xóa (group.deleted_at
        IS NOT NULL)
  When  tôi phục hồi tài liệu
  Then  phục hồi vẫn thành công; hiệu lực share tới nhóm đó tự động không
        còn (qua lớp filter dùng chung, không cần xử lý riêng); kết quả trả
        về API/UI có một cảnh báo (warning) liệt kê (các) nhóm đã mất hiệu
        lực — KHÔNG báo lỗi và KHÔNG chặn thao tác phục hồi
  ```
  *(Verified: `TrashControllerIT#restoreReturnsGroupsLostEffectWhenSharedGroupWasSoftDeleted`
  — dựng thật 1 nhóm, share tài liệu vào nhóm, xóa mềm tài liệu, soft-delete
  chính nhóm đó (`group.deletedAt`), rồi phục hồi: response 200 (không
  chặn), `groupsLostEffect` chứa đúng id nhóm đã bị xóa. Nhánh "có nhóm bị
  mất hiệu lực" nay đã đi qua code thật, không còn chỉ verify bằng đọc
  code.)*
- [x] AC-7 — **Item đã quá hạn / đã xóa cứng:**
  ```
  Given item có purge_state = 'purged' (đã được job trash-purge xử lý
        xong) hoặc purge_after đã qua và đang chờ job xử lý
  When  tôi cố gắng phục hồi
  Then  hệ thống trả 410 Gone kèm thông điệp hướng dẫn rõ ràng: dữ liệu
        không còn khôi phục được từ thùng rác, cần liên hệ quản trị (R3)
        để xử lý theo quy trình phục hồi từ sao lưu (slug `backup-restore`,
        chưa triển khai tại thời điểm viết story này)
  ```
  *(Verified: `TrashRestoreConflictControllerIT#restoreRejectedWithPurgingThenGoneAsPurgeStateAdvances`
  (410 khi `PURGED`), `#restoreRejectedWithGoneOncePurgeAfterHasElapsed`
  (410 khi quá `purgeAfter`); `TrashServiceTest#restoreRejectsWithGoneWhenAlreadyPurged`
  + `#restoreRejectsWithGoneWhenPastPurgeAfter`.)*
- [x] AC-8 — **Đang trong trạng thái `purging`:**
  ```
  Given item đang được job trash-purge xử lý dở dang (purge_state =
        'purging')
  When  tôi cố gắng phục hồi
  Then  hệ thống trả 409, yêu cầu thử lại sau; KHÔNG cho phép phục hồi một
        item đang bị xóa cứng dở dang (tránh trạng thái không nhất quán)
  ```
  *(Verified: `TrashRestoreConflictControllerIT#restoreRejectedWithPurgingThenGoneAsPurgeStateAdvances`,
  `TrashServiceTest#restoreRejectsWithPurgingWhenDocumentIsBeingPurged`.)*
- [x] AC-9 — **Giao dịch phục hồi (áp dụng chung mọi trường hợp thành
      công):** phục hồi luôn thực hiện trong **1 transaction**: đảo cờ
      (`deleted_at = NULL`, xóa `purge_after`), khôi phục hiệu lực share,
      phát outbox `document.restored` để lập chỉ mục lại (không cần chạy
      lại OCR/STT nhờ Quyết định 2 của SA), ghi audit `document.restore`.
      *(Verified bằng đọc code: `TrashService.restore()` có `@Transactional`,
      thực hiện đủ các bước trong 1 method. Ghi outbox/audit có test gián
      tiếp qua các AC khác ở trên (chưa có 1 test riêng khẳng định "tất cả
      trong 1 transaction" bằng cách giả lập lỗi giữa chừng và kiểm tra
      rollback toàn bộ, giống cách `TrashServiceTest` đã làm cho luồng xóa
      mềm ở Story 1 AC-8).)*

---

## Ngoài phạm vi (Out of scope)

- Xóa cứng thủ công theo yêu cầu (kể cả dạng "R1 đề nghị → R2 duyệt") —
  không có ở MVP, chỉ đề cập như hướng mở rộng tương lai trong file SA.
- Phục hồi từ bản sao lưu (nhánh R3 của FR-27) — thuộc slug
  `backup-restore` (chưa tồn tại/chưa duyệt tại thời điểm viết story này).
- Cấu hình/điều chỉnh `retention.trash_retention_days` — thuộc
  `system-config`, chỉ dẫn chiếu.
- Luồng xóa mềm, xem thùng rác cơ bản, phục hồi không xung đột, thống kê
  cho R3, hiển thị dung lượng thùng rác — xem file
  `delete-restore-trash-01-xoa-mem-thung-rac.md`.

## Phụ thuộc / Ghi chú kiến trúc liên quan

- Quyết định 5 (SA): xóa cứng theo lịch — máy trạng thái
  `deleted → purging → purged`, ShedLock, xử lý theo lô 200, bảo vệ object
  dùng chung theo `sha256`, giữ dòng "bia mộ", job 03:00 mặc định sau cửa
  sổ sao lưu 02:00.
- Quyết định 6 (SA): ma trận quyền — không có xóa cứng thủ công ở MVP.
- Quyết định 7 (SA): bảng xử lý xung đột khi phục hồi — nguồn trực tiếp
  cho AC-1–AC-8 của Story 6.
- Quyết định 8 (SA): audit hash-chain append-only, xóa cứng không được xóa
  bản ghi audit liên quan.

## Câu hỏi mở cho DEV

- [x] Giờ chạy job purge (03:00) đối chiếu với `backup-restore.md`: **đã trả
      lời** tại Quyết định 3 của file đó (Approved 2026-09-09) — giữ 03:00,
      gate theo điểm nhất quán sao lưu đã tạo xong; xem SA mục 7 và tech
      spec §4.1 (`delete-restore-trash-02-xoa-cung-xung-dot.md`).
- [x] Ngưỡng "kẹt" của `purge_state='purging'` (Story 5 – AC-5): **DEV đã
      chốt 2 giờ** (`purging_started_at` + `PurgeStuckRecoveryJob` mỗi 30
      phút) — xem tech spec §4.3, lý do chọn số ghi trong file đó.
- [x] Cơ chế "gợi ý thư mục gần nhất còn tồn tại" (Story 6 – AC-1): **DEV đã
      chốt** dùng snapshot `deleted_folder_path_ids` tại thời điểm xóa thư
      mục (không suy luận theo tên) — xem tech spec §4.4.
