# User Story: delete-restore-trash-01-xoa-mem-thung-rac

> File: `docs/02-ba/stories/delete-restore-trash-01-xoa-mem-thung-rac.md` — Owner: BA
> Nguồn kiến trúc: `docs/01-sa/architecture/delete-restore-trash.md`
> Status: Approved (2026-09-09)

> **Cập nhật 2026-09-09:** Nguồn (`delete-restore-trash.md`) đã **Approved**
> — câu hỏi mở duy nhất còn lại (đồng bộ giờ job purge với `backup-restore`)
> đã được trả lời tại `backup-restore.md` Quyết định 3 (xem mục 7 file SA).

File này gồm 4 story: (1) R1 xóa mềm tài liệu, (2) R1 xem/phục hồi thùng rác
cá nhân (bao gồm quyền của người nạp gốc), (3) R3 xem thống kê thùng rác,
(4) hiển thị dung lượng thùng rác trong UI kho. Xử lý xung đột chi tiết khi
phục hồi và job xóa cứng theo lịch được tách sang file
`delete-restore-trash-02-xoa-cung-xung-dot.md`.

---

## Story 1: R1 xóa mềm tài liệu trong kho dùng chung (FR-26)

**As a** R1 (nhân viên dùng kho dùng chung)
**I want** xóa mềm một hoặc nhiều tài liệu trong phạm vi FR-08 của mình
**So that** tài liệu không còn cần thiết biến mất khỏi kho chung ngay lập
tức, nhưng vẫn có thể khôi phục trong 365 ngày nếu xóa nhầm

### Acceptance Criteria

```
Given tôi là R1 và có quyền FR-08 trên tài liệu X (người nạp hoặc thành viên
      nhóm được chia sẻ tài liệu đó)
When  tôi chọn "Xóa" và xác nhận trên dialog
Then  document.deleted_at = now(), deleted_by_sub = tôi,
      purge_after = now() + 365 ngày được ghi trong 1 transaction cùng
      audit "document.trash"
```

- [ ] AC-1: Dialog xác nhận xóa hiển thị rõ **"N nhóm sẽ mất quyền xem tài
      liệu này"** (N = số nhóm đang có `document_group_share` hiệu lực với
      tài liệu); nếu N = 0, không hiển thị dòng cảnh báo về nhóm.
      *(Backend verified: `TrashServiceTest#previewReturnsActiveGroupShareCountPerDocumentDefaultingToZeroWhenAbsent`
      (N=0/N>0/nhiều document cùng lúc) và
      `TrashControllerIT#trashPreviewReturnsActiveGroupShareCountForEachDocument`
      (gọi thật `POST /documents/trash-preview`, assert JSON response). Phần
      UI dialog vẫn **chưa verify** — cần FE.)*
- [x] AC-2: Ngay sau khi xóa (không chờ index cập nhật), tài liệu **biến mất**
      khỏi mọi danh sách/tìm kiếm/tra cứu theo nhóm — kể cả khi kết quả trả
      về từ OpenSearch còn cache tạm thời, kết quả cuối cùng hiển thị
      cho người dùng vẫn không được chứa tài liệu đã xóa (hậu kiểm DB).
      *(Verified: `BusinessDataScopeIT#softDeletedDocumentIsInvisibleEvenToItsOwner`,
      `#softDeletedDocumentIsInvisibleEvenToAGroupMemberItWasSharedTo`,
      `TrashControllerIT#trashRemovesDocumentFromListImmediatelyAndAppearsInOwnTrash`.)*
- [x] AC-3: Mọi dữ liệu phái sinh của tài liệu (đoạn OCR, STT, nhãn, ghi chú,
      mốc thời gian) "biến mất" đồng thời — không có thao tác xóa riêng cho
      từng bảng phái sinh, chỉ dựa trên `document.deleted_at`.
      *(Verified theo thiết kế (Quyết định 2 SA): không có code path nào xóa
      riêng bảng phái sinh khi trash — `TrashService.trash()` chỉ update cờ
      trên `document`; các bảng phái sinh vẫn join qua `document.deleted_at`
      ở slug của chúng, đúng bất biến đã chốt.)*
- [x] AC-4: Bản ghi `document_group_share` liên quan **không bị xóa**, chỉ
      mất hiệu lực; sau khi phục hồi, danh sách nhóm chia sẻ trở lại đúng
      như trước khi xóa mà không cần chia sẻ lại thủ công.
      *(Verified: `TrashControllerIT#trashDoesNotRemoveGroupSharesAndRestoreKeepsThemIntact`.)*
- [x] AC-5: Tệp gốc (`sha256`, `storage_key`) không bị thay đổi hay xóa khỏi
      SeaweedFS.
      *(Verified bằng đọc code: `TrashService.trash()` không đụng tới 2
      field này ở bất kỳ nhánh nào; chưa có test riêng khẳng định "giữ
      nguyên giá trị cũ" cho luồng xóa mềm — chỉ có test cho luồng xóa cứng
      (`TrashPurgeServiceTest`/`IT`) là 2 field này bị xóa sau khi purge,
      đúng như kỳ vọng khác nhau giữa 2 luồng.)*
- [x] AC-6: R1 KHÔNG có quyền FR-08 trên tài liệu (không phải người nạp,
      không thuộc nhóm được chia sẻ) → yêu cầu xóa bị từ chối (403), ghi
      audit `document.trash.denied`.
      *(403 verified: `TrashControllerIT#trashRejectsWholeBatchWhenOneDocumentIsOutOfScope`,
      `TrashServiceTest#trashRejectsWholeBatchWhenOneDocumentIsOutOfScopeAndRollsBackNothing`.
      Audit `document.trash.denied`: bổ sung assertion thật (query `audit_log`
      qua `AuditLogRepository`) đã lộ ra 1 bug thật — `TrashService.trash()`
      gọi `auditService.record(...)` rồi `throw` ngay trong cùng 1
      `@Transactional` method, transaction rollback xóa luôn bản ghi audit
      vừa insert (và `AuditInterceptor` fallback cũng không bù lại vì
      `AUDIT_RECORDED_ATTRIBUTE` đã set trước khi rollback). **Đã sửa
      2026-09-09:** thêm `AuditService.recordIndependently(...)`
      (`Propagation.REQUIRES_NEW`, chỉ dùng cho case denial không có mutation
      cần bảo vệ cùng — không đổi `record()` gốc để giữ nguyên coupling audit
      Story 1 AC-3 ở mọi nơi khác), đổi call site này sang dùng method mới.
      `TrashControllerIT` 18/18 pass sau khi sửa, không hồi quy `mvn test`
      toàn hệ thống.)*
- [ ] AC-7: R2 (Lãnh đạo), R3, R4 không thấy hành động "Xóa" trên UI; gọi
      thẳng API cũng bị từ chối 403.
      *(Phần API verified cho R2/R3 (R4 không tồn tại trong hệ thống hiện
      tại — chỉ có CAN_BO/LANH_DAO/QUAN_TRI được seed, xem
      `RolePermissionFixtures`): `TrashControllerIT#trashForbiddenForR2Leader`,
      `#trashForbiddenForR3Admin` gọi thật `POST /documents/trash` bằng JWT
      role LANH_DAO/QUAN_TRI, assert 403. Phần "không thấy nút trên UI" cần
      FE, chưa làm ở lượt này.)*
- [x] AC-8: Xóa hàng loạt (tối đa 500 tài liệu/lượt) thực hiện trong **1
      transaction**, tạo 1 bản ghi `trash_batch`; mỗi tài liệu vẫn có
      `deleted_at`/`deleted_by_sub` riêng để phục hồi từng item hoặc theo
      lô sau này.
      *(Verified: `TrashServiceTest#trashAllValidOwnedDocumentsSucceedsAndWritesOneBatchAndOneOutboxEvent`
      (1 batch, 1 outbox event, N audit record cho N document) và
      `#trashRejectsWholeBatchWhenOneDocumentIsOutOfScopeAndRollsBackNothing`
      (tất cả-hoặc-không, rollback đúng).)*
- [ ] AC-9: Hiệu năng: xóa 1 tài liệu p95 ≤ 500ms; xóa hàng loạt ≤ 500 tài
      liệu p95 ≤ 5s (theo NFR của file SA).
      *(Chưa verify: không có test đo hiệu năng thật trong lượt implement
      này — chỉ có test đúng logic, không có test tải/thời gian.)*

---

## Story 2: R1 xem và phục hồi thùng rác cá nhân (FR-27, nhánh R1)

**As a** R1
**I want** xem danh sách tài liệu tôi đã xóa (thùng rác cá nhân) và phục
hồi từng item hoặc theo lô
**So that** tôi lấy lại được tài liệu xóa nhầm hoặc cần dùng lại, trong
vòng 365 ngày kể từ khi xóa

### Acceptance Criteria

```
Given tôi là R1 và đã xóa mềm một số tài liệu
When  tôi mở màn hình "Thùng rác"
Then  tôi chỉ thấy các tài liệu có deleted_by_sub = tôi (không thấy item
      người khác xóa, kể cả cùng nhóm)
```

- [x] AC-1: Danh sách thùng rác hiển thị tên tài liệu, ngày xóa, hạn xóa
      cứng (`purge_after`), dung lượng; phân trang p95 ≤ 1s.
      *(Dữ liệu verified: `TrashService.TrashItem` trả đủ
      `originalName/deletedAt/purgeAfter/size`;
      `TrashControllerIT#trashRemovesDocumentFromListImmediatelyAndAppearsInOwnTrash`
      gọi `GET /trash` thật và assert `total`/`items[0].documentId` (chưa
      assert từng field còn lại qua jsonPath, nhưng field có trong DTO).
      Phần hiệu năng p95 ≤ 1s **chưa verify** — không có test đo thời
      gian.)*
- [x] AC-2: **Người nạp tài liệu gốc** (`uploader_sub`) cũng được phép **phục
      hồi** tài liệu đó dù không phải người đã xóa nó (ví dụ: đồng nghiệp
      cùng nhóm là người bấm xóa). Quyền phục hồi kiểm tra
      `deleted_by_sub = self OR uploader_sub = self`.
      *(Verified: `TrashServiceTest#restoreAllowedWhenCallerIsTheUploaderEvenIfSomeoneElseDeletedIt`,
      `#restoreAllowedWhenCallerIsTheDeleterEvenIfSomeoneElseUploadedIt`,
      `TrashControllerIT#restoreAllowedForUploaderEvenWhenSomeoneElseDeletedIt`
      (end-to-end, có cả nhóm chia sẻ). API `GET /trash?view=own|uploaded`
      đúng như đã chốt.)*
      > Cách hiển thị trên UI: 2 tab ("Tôi đã xóa" / "Tài liệu tôi nạp bị
      > người khác xóa") gọi cùng 1 API `GET /trash?view=own|uploaded` — đã
      > chốt, xem "Câu hỏi mở cho DEV" bên dưới.
- [x] AC-3: Phục hồi 1 item không có xung đột (thư mục cha còn tồn tại,
      không trùng tên tại đích) → `deleted_at = NULL`, `purge_after = NULL`,
      hiệu lực share được khôi phục, tài liệu xuất hiện lại đúng vị trí cũ
      trong danh sách/tìm kiếm; audit `document.restore` được ghi.
      *(Verified: `TrashControllerIT#restoreWithTargetFolderIdMovesTheDocument`,
      `TrashServiceTest#restoreOverridesFolderIdWhenTargetFolderIdProvided`.
      Audit `document.restore`: test IT trên đã bổ sung assertion thật (query
      `audit_log` qua `AuditLogRepository`, khớp `action`/`resourceId`) và
      **pass** — khác với AC-6 Story 1, nhánh restore-thành-công không throw
      giữa transaction nên audit row không bị rollback.)*
- [x] AC-4: Phục hồi theo lô (dựa trên `trash_batch` hoặc chọn nhiều item
      tùy ý) — mỗi item xử lý độc lập, một item lỗi (xung đột) không chặn
      các item còn lại; kết quả trả về là báo cáo theo từng item (thành
      công / cần xử lý xung đột / lỗi).
      *(Verified: `TrashServiceTest#restoreBatchReportsErrorForForbiddenItemsButRestoresTheRest`
      — item lỗi không chặn item còn lại, kết quả trả theo từng item.)*
- [x] AC-5: R2, R3, R4 không có quyền phục hồi (theo Quyết định 6 của SA).
      *(Verified cho R2/R3 (R4 không tồn tại trong hệ thống hiện tại):
      `TrashControllerIT#restoreForbiddenForR2Leader`,
      `#restoreForbiddenForR3Admin` gọi thật `POST /trash/{id}/restore`
      bằng JWT role LANH_DAO/QUAN_TRI, assert 403.)*
- [x] AC-6: R3 không thấy danh sách tên tệp trong thùng rác (xem Story 3).
      *(Verified: `TrashControllerIT#statsForbiddenForR1AndDoesNotLeakBusinessFields`
      + `#detailForbiddenForR3` — xem Story 3.)*
- [ ] AC-7: Hiệu năng: phục hồi 1 tài liệu ≤ 1s.
      *(Chưa verify: không có test đo hiệu năng thật trong lượt implement
      này.)*

> Các trường hợp xung đột cụ thể khi phục hồi (thư mục cha đã xóa, trùng
> tên, item quá hạn/đã xóa cứng, đang `purging`...) được đặc tả chi tiết ở
> Story 6, file `delete-restore-trash-02-xoa-cung-xung-dot.md`.

---

## Story 3: R3 xem thống kê thùng rác (không xem nội dung)

**As a** R3 (quản trị hệ thống)
**I want** xem số lượng, tổng dung lượng và phân bố hạn xóa cứng của toàn
bộ thùng rác
**So that** tôi theo dõi được dung lượng kho bị rác chiếm dụng và cân nhắc
điều chỉnh retention nếu cần, mà không xâm phạm nội dung nghiệp vụ của
người dùng

### Acceptance Criteria

```
Given tôi là R3
When  tôi mở màn hình thống kê thùng rác
Then  tôi thấy: tổng số item, tổng dung lượng, phân bố theo khoảng hạn xóa
      cứng (ví dụ: sắp hết hạn trong 30 ngày tới)
```

- [x] AC-1: Màn hình/API thống kê **không** hiển thị tên tệp, đường dẫn, nội
      dung, hay danh tính người đã xóa/đã nạp của từng item.
      *(Verified: `TrashControllerIT#statsForbiddenForR1AndDoesNotLeakBusinessFields`
      — assert `originalName`/`deletedBySub` không tồn tại trong response.)*
- [x] AC-2: R3 gọi trực tiếp API chi tiết item trong thùng rác (của R1 bất
      kỳ) → bị từ chối 403.
      *(Verified: `TrashControllerIT#detailForbiddenForR3`.)*
- [x] AC-3: R3 vẫn giữ quyền điều chỉnh `retention.trash_retention_days`
      (tham số nhạy cảm, yêu cầu xác nhận 2 bước) — thao tác này thuộc
      `system-config`, chỉ dẫn chiếu ở đây, không đặc tả chi tiết.
      *(Không thuộc phạm vi implement của slug này — tham số đã tồn tại
      sẵn ở `system-config` từ trước; AC này chỉ là dẫn chiếu, không có gì
      mới cần verify ở đây.)*
- [x] AC-4 (bổ sung 2026-09-09, trả lời hệ quả Quyết định 6 của SA): Given
      tồn tại item trong thùng rác mà **cả** người xóa (`deleted_by_sub`)
      **và** người nạp gốc (`uploader_sub`) đều đang ở trạng thái tài khoản
      khác `active` (`locked`/`disabled`, theo `account-lifecycle.md`) —
      nghĩa là **không còn ai** đủ quyền tự phục hồi theo Quyết định 6 SA —
      When R3 mở màn hình thống kê thùng rác, Then thấy riêng một số liệu
      "item không còn ai phục hồi được" (số lượng + hạn xóa cứng gần nhất
      trong nhóm đó), **không** hiển thị tên tệp hay danh tính cụ thể (giữ
      nguyên NFR-04); R3 dùng số liệu này để quyết định có cần kích hoạt lại
      1 trong 2 tài khoản trước khi hết hạn 365 ngày hay không.
      *(Verified: `TrashControllerIT#unrecoverableAccountsCountsOnlyWhenBothDeleterAndUploaderAreDisabled`
      — case chỉ 1 bên bị khóa → không tính; cả 2 bên bị khóa → tính đúng
      `count=1`; response không lộ tên tệp/danh tính.)*

---

## Story 4: Hiển thị dung lượng thùng rác trong màn hình kho

**As a** R1
**I want** thấy rõ dung lượng đang bị thùng rác của tôi chiếm dụng, tách
biệt khỏi dung lượng tài liệu đang hoạt động
**So that** tôi hiểu vì sao kho gần đầy dù tôi tưởng đã "xóa" bớt dữ liệu,
và biết cách xử lý (đợi xóa cứng tự động, không có nút xóa cứng thủ công)

### Acceptance Criteria

- [x] AC-1: Given màn hình kho đang hiển thị % dung lượng đã dùng, When tôi
      xem chi tiết, Then có một dòng/badge riêng "Dung lượng thùng rác:
      X GB" tách biệt khỏi dung lượng tài liệu đang hoạt động.
      *(Phần dữ liệu verified: `GET /warehouse/capacity` trả field
      `trashBytes` riêng —
      `WarehouseControllerIT#trashBytesReflectsOnlySoftDeletedCleanDocumentsAndUsedBytesStaysUnchanged`.
      Phần "badge riêng trên UI" cần FE, chưa làm ở lượt này.)*
- [x] AC-2: Cảnh báo ngưỡng 85% / chặn nạp mới ở 95% (theo
      `warehouse-structure.md`) vẫn tính cả dung lượng rác; thông điệp
      cảnh báo gợi ý "thùng rác đang chiếm X GB, sẽ tự động xóa cứng theo
      lịch" — không gợi ý sai rằng người dùng có thể "xóa cứng ngay" (MVP
      không có thao tác này).
      *(Phần "vẫn tính cả rác" verified:
      `WarehouseCapacityServiceTest#snapshotUsesRepositorySumDirectlyIncludingTrashedCleanDocuments`
      + `#snapshotExposesTrashBytesSeparatelyFromUsedBytesWithoutAffectingUsedPercent`
      (formula `usedPercent` không đổi khi có rác). Phần nội dung thông
      điệp cảnh báo là FE text, chưa làm ở lượt này.)*
- [x] AC-3: Dung lượng thùng rác hiển thị khớp với dữ liệu dùng để tính
      ngưỡng cảnh báo/chặn (cùng một nguồn số liệu, không lệch nhau giữa
      hai màn hình).
      *(Verified: `WarehouseCapacityServiceTest#snapshotExposesTrashBytesSeparatelyFromUsedBytesWithoutAffectingUsedPercent`
      — `trashBytes` và `usedBytes` cùng lấy từ `DocumentRepository`, khác
      điều kiện `deleted_at`, không lệch nguồn.)*

---

## Ngoài phạm vi (Out of scope)

- Xử lý xung đột chi tiết khi phục hồi (thư mục cha bị xóa, trùng tên, hết
  hạn...) — xem file `delete-restore-trash-02-xoa-cung-xung-dot.md`.
- Job xóa cứng theo lịch (`trash-purge`) — xem file
  `delete-restore-trash-02-xoa-cung-xung-dot.md`.
- Xóa cứng thủ công (không có ở MVP).
- Phục hồi từ bản sao lưu (nhánh R3 của FR-27) — thuộc slug `backup-restore`
  (chưa tồn tại tại thời điểm viết story này).
- Xóa mềm ở mức thư mục kho, xóa tài liệu cá nhân (FR-14), xóa nhóm, xóa
  tài khoản — đã chốt ở các slug khác, không thuộc phạm vi này.
- Thùng rác dùng chung theo nhóm (MVP chỉ có thùng rác cá nhân theo người
  xóa/người nạp).
- Thông báo in-app chủ động tới thành viên nhóm khi tài liệu chia sẻ bị
  xóa — chỉ ghi audit log (FR-10), không có notification.

## Phụ thuộc / Ghi chú kiến trúc liên quan

- Quyết định 1 (SA): cờ soft-delete tại chỗ trên `document`, không tách
  bảng/di chuyển object — mọi AC trên giả định model này.
- Quyết định 2 (SA): `document.deleted_at` là nguồn sự thật duy nhất cho
  phái sinh; các slug `digitization-*`, `search-*`,
  `classification-notes`, `export-reporting` phải tuân theo khi phái sinh
  dữ liệu của mình.
- Quyết định 3 (SA): giữ nguyên `document_group_share` khi xóa mềm, chỉ
  mất hiệu lực.
- Quyết định 4 (SA): gỡ chỉ mục search bất đồng bộ qua outbox + hậu kiểm
  bắt buộc ở DB — AC-2/Story 1 dựa vào cơ chế hậu kiểm này để đảm bảo "biến
  mất ngay lập tức" dù chỉ mục có độ trễ.
- Quyết định 6 (SA): ma trận quyền xóa/xem thùng rác/phục hồi; hệ quả của
  quyết định này ("cần báo cáo item mà tài khoản liên quan đã bị vô hiệu
  hóa") được cụ thể hóa thành Story 3 – AC-4 (bổ sung 2026-09-09).
- `account-lifecycle.md` (Approved): nguồn của khái niệm `account.status IN
  ('active','locked','disabled')` dùng ở Story 3 – AC-4 — Story 6 – AC-5 của
  file `delete-restore-trash-02-xoa-cung-xung-dot.md` đã dùng khái niệm
  tương đương ("tài khoản đã bị vô hiệu hóa") nhưng chưa trỏ rõ nguồn; AC-4
  ở đây là lần đầu tài liệu này tham chiếu trực tiếp tới bảng `account`.

## Câu hỏi mở cho DEV

- [x] UX cụ thể cho trường hợp uploader khác người xóa (Story 2 – AC-2): đã
      trả lời ở `docs/03-dev/specs/delete-restore-trash-01-xoa-mem-thung-rac.md`
      §4.2 — **1 API `GET /trash`, phân biệt bằng query param `view=own|
      uploaded`**, FE hiển thị 2 tab như BA đề xuất, gọi cùng 1 endpoint.
- [ ] Định dạng dialog xác nhận xóa khi N = 0 nhóm bị ảnh hưởng (Story 1 –
      AC-1): ẩn hẳn dòng cảnh báo hay hiển thị "không chia sẻ nhóm nào"? BA
      đề xuất ẩn, cần DEV chốt khi làm UI.
