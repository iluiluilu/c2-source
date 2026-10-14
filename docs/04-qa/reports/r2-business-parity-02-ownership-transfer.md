# QA Report: r2-business-parity-02-ownership-transfer

> File: `docs/04-qa/reports/r2-business-parity-02-ownership-transfer.md` — Owner: QA
> Nguồn story: `docs/02-ba/stories/r2-business-parity-02-ownership-transfer.md` (STORY-r2-business-parity-02)
> Nguồn spec: `docs/03-dev/specs/r2-business-parity-02-ownership-transfer.md` (SPEC-r2-business-parity-02-ownership-transfer)
> Ngày review: 2026-10-01
> Commit/branch reviewed: main @ 667e2a5 (+ working tree chưa commit)

## Kết luận

**PASS WITH ISSUES**

Phần backend (phạm vi của spec này) đạt AC-1..AC-4, AC-6..AC-8, test unit và IT xanh. AC-5 là phần FE (cảnh báo thiếu `document.upload`) và spec này giao cho story 03. Code web hiện tại chưa làm phần đó, và dropdown người nhận vẫn chỉ lọc R1, nên người dùng chưa thể chọn R2 qua UI. Không có blocking ở phạm vi backend.

## Đối chiếu Acceptance Criteria

| AC | Kết quả | Bằng chứng |
|---|---|---|
| AC-1 | Pass (BE) | `DocumentOwnershipService.java:87-88` anyMatch `can-bo` hoặc `lanh-dao`. Unit `transferSucceedsWhenBIsAnActiveLanhDaoAccount`. IT `transfer_bIsLeaderRole_returns200AndUpdatesUploader` (assert uploaderSub = leader-x). Qua UI chưa dùng được, xem Issue N-1. |
| AC-2 | Pass | Unit `transferSucceedsWhenBIsAnActiveCanBoAccount`. IT `transfer_validB_updatesUploaderAndReturns200`. |
| AC-3 | Pass | `DocumentOwnershipService.java:89-90`, message đúng nguyên văn. Unit `transferRejectsWhenBHasNeitherCanBoNorLanhDaoRole`. IT `transfer_bAdminOnlyRole_returns400`. |
| AC-4 | Pass | Unit `transferSucceedsWhenBHasQuanTriPlusLanhDaoRoles`. IT `transfer_bHasQuanTriPlusLanhDao_returns200`. |
| AC-5 | Pass phần BE, FE chưa có | BE không kiểm permission (không có code kiểm `document.upload`), đúng ADR-4. Cảnh báo FE chưa được implement: `web/src/app/admin/document-ownership/page.tsx` không gọi `/admin/rbac/roles` (grep `rbac`/`document.upload`/`permissionCodes` không có kết quả). Spec giao phần này cho story 03, xem Issue N-1. |
| AC-6 | Pass | Unit `transferRejectsWhenDocumentNotFound`, `...BIsSameAsCurrentUploader`, `...BDoesNotExistInKeycloak`, `...BIsDisabled`. IT `transfer_bSameAsCurrentUploader_returns400`, `transfer_documentNotFound_returns404`. |
| AC-7 | Pass | IT `transfer_nonAdminRoles_return403`. Endpoint giữ `PERM_document.transfer_ownership`, không đổi. |
| AC-8 | Pass | IT `transfer_chainedTwice_eachAuditRowHasItsOwnFromUploaderSub` assert metadata `fromUploaderSub`/`toUploaderSub`. Unit assert audit ở case R2 và case không có reason. Lời gọi `AuditService.record` không đổi. Chưa có IT audit riêng cho người nhận `lanh-dao`, xem N-2. |

## Kết quả test/lint đã chạy

```
mvn -o -q verify -Dtest=DocumentOwnershipServiceTest -Dit.test=DocumentOwnershipControllerIT
- DocumentOwnershipServiceTest (surefire): Tests run: 9, Failures: 0, Errors: 0
- DocumentOwnershipControllerIT (failsafe): Tests run: 13, Failures: 0, Errors: 0
  (log cuối chạy có stack trace Hikari/Lettuce "Connection closed" khi container Testcontainers tắt, không ảnh hưởng kết quả)
grep "vai trò Cán bộ" web/src -> không có chỗ so khớp chuỗi lỗi cũ.
Không chạy tsc/lint web vì không có thay đổi web trong phạm vi spec này.
Không chạy TagsControllerIT/SearchScopeIT (flaky đã biết, ngoài phạm vi).
```

## Vấn đề chặn (Blocking)

Không có.

## Đề xuất cải thiện (Non-blocking)

- [ ] N-1: Trước khi release tính năng cho người dùng cuối, cần hoàn tất story 03. Dropdown tại `web/src/app/admin/document-ownership/page.tsx:107` vẫn chỉ lọc `Role.CAN_BO`, cần thêm `Role.LANH_DAO`. Cảnh báo AC-5 chưa có: gọi `GET /admin/rbac/roles` một lần, lỗi API thì bỏ cảnh báo, không chặn. AC-1 và AC-5 chỉ đạt end-to-end khi story 03 xong. Nên nghiệm thu lại phần FE ở QA của story 03.
- [ ] N-2: Bổ sung vào IT một assert audit cho trường hợp người nhận `lanh-dao`, như spec §5.2 đã gợi ý. Hiện chỉ unit test và IT với người nhận `can-bo` cover audit.
- [ ] N-3: `docs/03-dev/specs/...` §11 DoD chưa tick. Cần cập nhật khi chốt. Trong `git status`, thư mục `web/src/app/admin/document-ownership/` hiển thị untracked (`??`), nên kiểm tra việc commit.

## Kiểm tra thêm

- [x] Security checklist trong spec đã thoả. `@PreAuthorize` không đổi, chỉ mở rộng đối tượng người nhận. Việc R2 thiếu `document.upload` vẫn nhận được quyền sở hữu là chủ đích của ADR-4, QA xác nhận chấp nhận.
- [x] Observability: không thêm log, audit giữ nguyên format như spec.
- [x] Migration/rollback: không có migration, rollback bằng revert deploy backend.
