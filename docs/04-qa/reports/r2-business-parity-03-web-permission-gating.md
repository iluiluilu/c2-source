# QA Report: r2-business-parity-03-web-permission-gating

> File: `docs/04-qa/reports/r2-business-parity-03-web-permission-gating.md` — Owner: QA
> Nguồn story: `docs/02-ba/stories/r2-business-parity-03-web-permission-gating.md` (STORY-r2-business-parity-03)
> Nguồn spec: `docs/03-dev/specs/r2-business-parity-03-web-permission-gating.md` (SPEC-r2-business-parity-03-web-permission-gating)
> Ngày review: 2026-10-01
> Commit/branch reviewed: main @ 667e2a5 + thay đổi chưa commit (working tree)

## Kết luận

**PASS** (re-review lần 3, 2026-10-01)

AC-1..AC-8 đều Pass; Task 13 (ADR-3, gate chia sẻ nhóm theo `document.share`) đúng gate BE; hai non-blocking trước đã có test; `MeControllerIT` xanh (lần 2); tsc/lint/vitest (43 file, 403 test) xanh. Không còn vấn đề chặn.

## Đối chiếu Acceptance Criteria

| AC | Kết quả | Bằng chứng |
|---|---|---|
| AC-1 | Pass | `MeController.java` (`GET /me/permissions`, `@PreAuthorize("isAuthenticated()")`, chỉ nhận `Authentication`, không có tham số user). `lib/permissions.ts` fetch khi có token (`setPermissionsToken` → `doFetch`). Test `permissions.test.ts` "fetches once on login and then exposes the permissions (AC-1)". `MeControllerIT` có 3 test (`lanhDaoGetsRoleAndStory01Permissions`, `quanTriDoesNotHoldStory01Permissions`, `unauthenticatedIs401`) đã chạy xanh: `mvn -o verify -Dit.test=MeControllerIT` -> Tests run 3, Failures 0, Errors 0 (`backend/target/failsafe-reports/com.aismartstore.backend.auth.MeControllerIT.txt`). IT kiểm: LANH_DAO -> `roles[0]=lanh-dao` + `hasItems` đủ 5 quyền (document.upload, digitization_review.manage, trash.manage, tag.manage, note.write); QUAN_TRI -> `quan-tri`, không có trash.manage/document.upload; không JWT -> 401. Khớp `MeController.java` (bỏ tiền tố, sort, chỉ đọc Authentication). Hạn chế nhỏ: IT dùng `RolePermissionFixtures` (không đọc DB thật) và `roles[0]` chỉ kiểm phần tử đầu; log lỗi kết nối OpenSearch lúc khởi động context không ảnh hưởng. |
| AC-2 | Pass | `features.ts:79,117,214` (upload/digitization/trash chuyển sang `permissions`); `documents/page.tsx:70` `can('trash.manage')`; `documents/[id]/page.tsx:89-91` tách `canDeleteDoc/canManageTags/canWriteNotes`; `personal/page.tsx:251`. Test: `upload` "renders the upload form for a lanh-dao holding document.upload (AC-2)"; `documents` "shows the Xóa button for a lanh-dao (R2) ... (AC-2)"; `documents/[id]` dòng 160, 263; `trash` dòng 78; `digitization` dòng 93; `runs/[runId]` dòng 127; `app/page.tsx` dòng 71; `personalToWarehouse` dòng 121. |
| AC-3 | Pass | Gate đọc từ store, store refetch khi interval 4' (`REFETCH_INTERVAL_MS`), `focus`/`visibilitychange`, và `forbiddenBus` (`api.ts:46`, `api.ts:118`). Test thu hồi quyền → ẩn: `app/page.tsx` dòng 82; `documents` dòng 406; `trash` dòng 71; `upload` dòng 87; `personalToWarehouse` dòng 132. Test 403: `permissionsHook.test.tsx` dòng 55. Đã bổ sung `apiForbidden.test.ts` (403 -> emit) và test focus/visibilitychange/interval trong `permissionsHook.test.tsx`. |
| AC-4 | Pass | `permissions.ts` catch → `status:'error'`, `can()` = false (`canWith`). `PermissionsBanner.tsx` role="alert" + nút "Thử lại". Test `permissions.test.ts` "first-fetch failure => status error and can() stays false (AC-4)"; `PermissionsBanner.test.tsx` dòng 33. Trang gate hiện AccessDenied (fail-closed) khi error, kèm banner. |
| AC-5 | Pass | `admin/audit/page.tsx:85` `hasRole(QUAN_TRI)`; `admin/rbac`, `admin/accounts` vẫn dùng `canSeeFeature` theo role, không dùng permission. Task 12: các test admin/accounts, rbac, audit không bị sửa và xanh. |
| AC-6 | Pass | 7 file test theo §7.1 đã đảo ngược đúng nghĩa: trước R2 bị chặn, nay có assertion R2 được phép khi có permission, **và** thêm case thiếu quyền → bị chặn (không chỉ xoá assertion). Xem bảng trên (upload, documents, documents/[id], trash, digitization, runs/[runId], app/page). |
| AC-7 | Pass | Store singleton + `inFlight` + `REFETCH_DEDUPE_MS`; `permissions.test.ts` "two refetch triggers back to back call fetch only once (AC-7)", "does not refetch inside the dedupe window, but force bypasses it"; `permissionsHook.test.tsx` "many consumers share a single fetch (AC-7)" và "refetches on a 403 signal ... once even if emitted twice". Không gọi lại theo mỗi render. |
| AC-8 | Pass | `permissions.ts`: `loading`→`can` false; lỗi: `setState({...state, status:'error'})` (giữ permissions cũ nhưng `can` vẫn false theo thiết kế fail-closed); refetch nền không đổi status về loading nên giữ quyền cũ. Test "keeps the previous permissions during a background refetch (AC-8)"; banner test. |

## Kết quả test/lint đã chạy

```
cd web && npx tsc --noEmit                 -> 0 lỗi
cd web && npm run lint                      -> sạch
cd web && npx vitest run --coverage         -> Test Files 43 passed (43); Tests 403 passed (403)
  coverage (stmts/branch/funcs/lines): tổng 81.38/75.13/73.04/83.61
  permissions.ts 93.54/82.92/90/100; api.ts 86/71.87/77.77/89.13; features.ts 84.61/75/75/88.88
cd backend && mvn -o -q compile             -> exit 0
MeControllerIT (lần 2): mvn -o verify -Dit.test=MeControllerIT -> 3 test, 0 fail/error/skip
Smoke (theo DEV): GET :3001/me/permissions không JWT -> 401; web :3000 -> 200
```

Lưu ý: số tổng DEV ghi trong spec §13 (lines 80.55 / branches 72.71) thấp hơn số QA đo lại (lines 83.61 / branches 75.13; cột lines từng file khớp). Chênh lệch có lẽ do đo trước khi thêm test mới; nên cập nhật spec. Không có ngưỡng/baseline nên không ảnh hưởng kết luận. Backend không đo coverage (không có jacoco).

## Task 13 / ADR-3 (chia sẻ nhóm)

- Code: `documents/[id]/page.tsx:91` `canShareGroup = perms.can('document.share')`; `documents/page.tsx:72` `canBulkShare = perms.can('document.share')`. Khớp BE `hasAuthority('PERM_document.share')` (`DocumentSharesController.java:33,40,46`, `DocumentBulkSharesController.java:37`). Fail-closed theo `can()` khi loading/error.
- Test: `documents/[id]` "shows tag/note/share controls ... (AC-2)" (có quyền) và "hides the group-share control without document.share, keeping other controls (ADR-3)"; `documents` "Chia sẻ vào nhóm" có `document.share` (dòng ~410) và "hides bulk ... without document.share (ADR-3)" (dòng ~427). Có/thiếu quyền đủ ở cả 2 trang.
- `test-utils/permissions.ts:14` `DEFAULT_TEST_PERMISSIONS` = 5 quyền + `document.share`: hợp lý.
- Spec: Task 13, §2, §11, §12, §14 (đã chốt), nhãn AC-9 đã sửa, DoD coverage đã tick đều khớp.

## Chất lượng test mới

- `apiForbidden.test.ts`: assert có ý nghĩa (403 emit đúng 1 lần cho `apiFetch` và XHR `apiUploadJson`; 200/401/500 không emit), không dùng timer nên không flaky.
- `permissionsHook.test.tsx`: focus, visibilitychange visible/hidden, interval 4', cleanup khi unmount; dùng `shouldAdvanceTime` + `setSystemTime` vượt dedupe và `advanceTimersByTimeAsync`, `afterEach` reset store/timers nên ổn định (suite chạy xanh 2 lần liên tiếp trong các lần re-review). Test unmount khẳng định không refetch sau khi gỡ. 

## Đánh giá 3 điểm DEV báo lệch spec

1. **Trả `null` khi permissions đang `loading` ở 4 trang** (upload, digitization, runs/[runId], trash; dòng `if (status === 'loading' || (session && perms.status === 'loading')) return null`) — **Chấp nhận**. Tránh nháy AccessDenied trong lúc tải lần đầu, không vi phạm fail-closed (không render nội dung nhạy cảm). Rủi ro nhỏ: nếu session `authenticated` nhưng không có `accessToken`, store ở `loading` mãi và trang trắng; nên được ghi vào spec (và cân nhắc timeout/fallback). Non-blocking.
2. **Sửa thêm `personal/__tests__/personalToWarehouse.test.tsx` ngoài §7.1** — **Chấp nhận, hợp lý và cần thiết**: `personal/page.tsx:251` `canAddToWarehouse` đọc feature `upload` đã chuyển sang permission, nên test này bắt buộc cập nhật. Spec §11 đã dự liệu ("bổ sung vào §7.1 nếu phát hiện thêm"). Test đảo đúng nghĩa (R2 có `document.upload` thấy nút, thiếu quyền thì ẩn). Đề nghị cập nhật §7.1 cho đầy đủ truy vết.
3. **Tick `[x]` Task 1 dù IT chưa chạy** — **Đã giải quyết**: IT nay đã chạy xanh, Task 1 ghi kèm bằng chứng. Điểm 1 và 2 cũng đã được ghi vào spec (§7, §7.1).

## Vấn đề chặn (Blocking)

- Không còn. (Mục chặn trước đó — thiếu bằng chứng `MeControllerIT` — đã đóng.)

## Đề xuất cải thiện (Non-blocking)

- [ ] Cập nhật số coverage tổng trong spec §13 theo số mới (lines 83.61 / branches 75.13).
- [ ] Trang `loading` vô hạn nếu có session nhưng thiếu `accessToken` (null-khi-loading ở 4 trang) — thêm fallback.
- [ ] Comment đầu `documents/[id]/__tests__/page.test.tsx:14` ("R2 is read-only") có thể còn lỗi thời.
- [ ] Spec §13 mục "AC-1..AC-8 pass" chờ tick theo report này; `web/package.json`/`package-lock.json` đổi (lock regenerate trên Linux, +171 dòng): xác nhận người dùng chấp nhận diff lock.
- [ ] IT dùng `RolePermissionFixtures` (không đọc DB thật) và `roles[0]` chỉ kiểm phần tử đầu.

## Kiểm tra thêm

- [x] Security checklist trong spec: endpoint chỉ đọc authorities của chính caller; không mở `@PreAuthorize` rộng hơn; fail-closed ở client (mục rate limit không áp dụng).
- [x] Observability: không thêm log, không log nội dung permissions ra console (grep `permissions.ts` không có console.*).
- [x] Migration/rollback: không có migration; web fail-closed nếu BE chưa có endpoint, deploy BE trước web.
