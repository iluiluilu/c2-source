# Tech Spec: r2-business-parity-03-web-permission-gating

> File: `docs/03-dev/specs/r2-business-parity-03-web-permission-gating.md` — Owner: DEV
> Nguồn story: `docs/02-ba/stories/r2-business-parity-03-web-permission-gating.md` (STORY-r2-business-parity-03)
> Kiến trúc: `docs/01-sa/architecture/r2-business-parity.md` (ADR-2, ADR-3; R-3, R-4, R-7)
> Status: Approved
> Spec ID: `SPEC-r2-business-parity-03-web-permission-gating`

## 1. Tóm tắt kỹ thuật

- **Backend**: 1 endpoint mới, read-only, không tham số — `GET /me/permissions`
  (`backend/.../auth/MeController.java`) đọc trực tiếp
  `Authentication#getAuthorities()` (cùng nguồn `SecurityConfig#realmRoleAuthorities`
  dùng cho `@PreAuthorize`), tách `ROLE_<x>` → `roles` (kebab-case qua
  `RoleCodes.toKeycloakRoleName`) và `PERM_<x>` → `permissions` (bỏ tiền tố).
  Không thêm bảng, không đổi cache/converter.
- **Web**: 1 module client-side (`web/src/lib/permissions.ts`, singleton store +
  hook `usePermissions()`, không cần `<Provider>` bọc cây component — nhiều
  nơi gọi hook vẫn chỉ sinh **một** chu kỳ fetch/polling, đáp ứng AC-7) +
  `can(code)` fail-closed khi `loading`/`error` (AC-8). `features.ts` thêm
  field `permissions?: readonly string[]` cho 3 feature đổi sang permission
  (`upload`, `digitization`, `trash` — đúng 5 permission của
  `r2-business-parity-01`); các nút rời feature registry (`canDelete`,
  `canWrite`) tách theo đúng permission BE tương ứng.
- **Đã grep lại toàn bộ BE** (không chỉ 32 endpoint của spec-01) để xác nhận
  loại gate thật của từng trang/nút — xem §2 "Audit gate web ↔ BE". Kết quả
  **sửa một số điểm trong danh sách sơ bộ của BA** (Dependencies của story):
  xem bảng đối chiếu bên dưới.
- **Phạm vi cố định**: chỉ 3 feature + 2 cụm nút (delete tài liệu, viết tag/ghi
  chú) đổi sang permission — đúng 5 permission của story 01. Các trang khác
  tuy cũng gate bằng `hasAuthority` ở BE (vd. `documents`→`PERM_document.read`,
  `groups`→`PERM_group.manage`, `personal`→`PERM_personal.manage`,
  `admin/accounts`→`PERM_account.read/manage`) **không đổi** trong story này:
  hành vi hiệu lực hiện tại đã khớp với gate theo `roles` (quyền đó không nằm
  trong 5 permission R3 có thể bật/tắt cho R2 ở story 01), và không AC nào của
  story 03 yêu cầu các trang đó phản ứng RBAC theo thời gian thực. Mở rộng
  thêm là backlog riêng (ghi ở §12), tránh phình phạm vi ngoài AC-1..AC-8.

## 2. Audit gate web ↔ BE (sửa danh sách sơ bộ của BA)

| File | Nút/feature | Gate BE thật (grep 2026-10-01) | Quyết định |
|---|---|---|---|
| `lib/features.ts` `upload` | menu + trang | `IngestController` toàn bộ `hasAuthority('PERM_document.upload')` | → `permissions: ['document.upload']` |
| `lib/features.ts` `digitization` | menu + trang | `ReviewController` toàn bộ `PERM_digitization_review.manage` | → `permissions: ['digitization_review.manage']` |
| `lib/features.ts` `trash` | menu + trang | `TrashController`/`DocumentsController` `PERM_trash.manage` | → `permissions: ['trash.manage']` |
| `app/documents/page.tsx` `canDelete` | nút "Xóa"/"Xóa đã chọn" | `POST /documents/trash` → `PERM_trash.manage` | `can('trash.manage')` (không còn qua feature registry) |
| `app/documents/[id]/page.tsx` `canWrite` | nút "Xóa" tài liệu | `POST /documents/trash` → `PERM_trash.manage` | tách thành `canDeleteDoc = can('trash.manage')` |
| … | gắn/gỡ/tạo nhãn | `TagsController`/`DocumentTagsController` → `PERM_tag.manage` | `canManageTags = can('tag.manage')` |
| … | thêm/sửa ghi chú | `NotesController` → `PERM_note.write` | `canWriteNotes = can('note.write')` |
| … | form "Chia sẻ với nhóm" | `DocumentSharesController` → `PERM_document.share` (**không** nằm trong 5 permission story 01; `LANH_DAO` đã có từ V12) | **Đổi** `canShareGroup = can('document.share')` (trước: `hasRole(CAN_BO)`) — sửa bug UX có sẵn (R2 đã có `document.share` nhưng UI chưa cho); người dùng chốt gộp 2026-10-01, map ADR-3 (không map AC) — Task 13 |
| `app/upload/page.tsx` canUpload, `app/personal/page.tsx` canAddToWarehouse | đều đọc feature `upload` | như trên | theo `canSeeFeature(..., perms)` sau khi `upload` đổi permission |
| `app/admin/document-ownership/page.tsx` `recipientOptions` | lọc người nhận | ADR-4 (story 02): BE kiểm **role** `can-bo`/`lanh-dao`, không phải permission | **Giữ role**, sửa filter thêm `LANH_DAO` (§6 Task 9) |
| `app/documents/page.tsx` canView (`documents`), `search`, `groups`, `personal` canView, `account/password` | | `PERM_document.read`/`PERM_group.manage`/`PERM_personal.manage`/`PERM_account.self_service` — **không phải** `hasAnyRole` như BA ghi sơ bộ, nhưng mặc định đã cấp cho đúng role hiện tại và không thuộc 5 permission story 01 | **Giữ role**, không đổi (ngoài phạm vi, xem §1) |
| `app/admin/*`, `app/admin/audit` canVerify | | `RbacController` thật sự `hasRole('QUAN_TRI')`; `AccountsController`/`AuditController` dùng `PERM_account.*`/`PERM_audit.*` nhưng chỉ `QUAN_TRI` có các quyền đó | **Giữ role** (AC-5); admin/accounts, admin/rbac, admin/audit test **không cần sửa** (sửa lại ước đoán "8 file" của BA) |

## 3. Data model

Không đổi schema (chỉ đọc authorities hiện có của request).

## 4. API contract

| Method | Endpoint | Request | Response | Lỗi/Status codes |
|---|---|---|---|---|
| GET | `/me/permissions` | — (`isAuthenticated()`, không tham số — AC-1) | `{ "roles": string[], "permissions": string[] }` (permission không tiền tố `PERM_`) | 401 nếu chưa đăng nhập/token hết hạn |

```java
@RestController
@RequestMapping("/me")
public class MeController {
  public record MePermissionsView(List<String> roles, List<String> permissions) {}

  @GetMapping("/permissions")
  @PreAuthorize("isAuthenticated()")
  public MePermissionsView permissions(Authentication authentication) {
    List<String> roles = authoritiesStartingWith(authentication, "ROLE_")
        .map(a -> RoleCodes.toKeycloakRoleName(a.substring(5))).toList();
    List<String> perms = authoritiesStartingWith(authentication, "PERM_")
        .map(a -> a.substring(5)).toList();
    return new MePermissionsView(roles, perms);
  }
}
```

## 5. Luồng xử lý chính (web)

```mermaid
sequenceDiagram
  participant Hook as usePermissions() (1..n components)
  participant Store as module singleton store
  participant Bus as forbiddenBus
  participant Api as GET /me/permissions
  Hook->>Store: subscribe (useSyncExternalStore)
  Store->>Api: fetch khi login / mỗi 4' / focus tab / nhận tín hiệu 403
  Api-->>Store: 200 {roles, permissions} hoặc lỗi
  Store->>Store: status = ready|error; giữ permissions cũ nếu đang refetch nền (AC-8)
  Note over Bus: apiFetch/apiJson gọi Bus.emit() khi ApiError.status===403
  Bus-->>Store: trigger refetch (debounce ≥2s để không đôi cuộc gọi — AC-7)
  Store-->>Hook: snapshot mới → can(code) re-evaluate
```

1. `usePermissions()` không cần `<PermissionsProvider>` bọc cây — là 1 module
   singleton (`useSyncExternalStore`), nên nhiều nơi gọi (Sidebar + trang) vẫn
   chỉ có 1 bộ hẹn giờ/listener thực tế (dedupe bằng cờ "đang fetch" +
   "lastFetchAt", không phải 1 timer/consumer).
2. Khi `useSession()` chuyển sang `authenticated` lần đầu → fetch ngay (AC-1).
   Khi `unauthenticated`/logout → reset về `{status:'loading', roles:[],
   permissions:[]}`.
3. `can(code) = status === 'ready' && permissions.includes(code)`; mọi nơi
   khác (`loading`/`error`) trả `false` (AC-8).
4. Banner lỗi (`PermissionsBanner`, mount trong `AppShell`) hiện khi
   `status === 'error'`; nút "Thử lại" gọi `refetch()` (bỏ qua debounce).

## 6. Task breakdown (map tới AC)

- [x] **Task 1** (IT đã pass 2026-10-01: `mvn -o verify -Dit.test=MeControllerIT` → 3 test, 0 fail/error; `target/failsafe-reports/com.aismartstore.backend.auth.MeControllerIT.txt`) → AC-1: `backend/src/main/java/com/aismartstore/backend/auth/MeController.java`
      (§4). Test `backend/src/test/java/com/aismartstore/backend/auth/MeControllerIT.java`
      (pattern theo `RbacControllerIT`: `jwt().authorities(RolePermissionFixtures.authoritiesFor("LANH_DAO"))`
      → 200 `roles=["lanh-dao"]`, `permissions` ⊇ 5 permission story 01; không
      JWT → 401; `authoritiesFor("QUAN_TRI")` → permissions không chứa 5 code đó).
- [x] **Task 2** → AC-1, AC-7, AC-8: `web/src/lib/forbiddenBus.ts` (pub/sub rỗng,
      không phụ thuộc) + `web/src/lib/permissions.ts` (singleton store,
      `usePermissions()`, `can()`, dedupe fetch ≥2s (hằng số nội bộ
      `REFETCH_DEDUPE_MS = 2000`, không phải NFR — §14), interval 4' giống
      `Providers.tsx` `SESSION_REFETCH_INTERVAL_SECONDS`, listener
      `visibilitychange`/`focus`, listener `forbiddenBus`). Test riêng
      `web/src/lib/__tests__/permissions.test.ts` (renderHook hoặc gọi trực
      tiếp store API): loading→false, error giữ permissions cũ khi đã có dữ
      liệu (AC-8), 2 lệnh refetch sát nhau chỉ gọi fetch 1 lần (AC-7), gọi lại
      khi `forbiddenBus.emit()`.
- [x] **Task 3** → AC-4: sửa `web/src/lib/api.ts` (`toApiError`): khi
      `res.status === 403`, gọi `forbiddenBus.emit()` (áp cho cả `apiFetch`/`apiJson`
      lẫn `apiUploadJson`'s `xhr.onload` nhánh 403).
- [x] **Task 4** → AC-8: `web/src/components/PermissionsBanner.tsx` (banner +
      nút "Thử lại" khi `status==='error'`) + mount trong
      `web/src/components/AppShell.tsx`. Test
      `web/src/components/__tests__/PermissionsBanner.test.tsx` (mock
      `usePermissions`, assert banner/ẩn banner, click gọi `refetch`).
- [x] **Task 5** → AC-2, AC-3, AC-6: `web/src/lib/features.ts` — thêm field
      `permissions?: readonly string[]` vào `Feature`; set cho `upload`
      (`document.upload`), `digitization` (`digitization_review.manage`),
      `trash` (`trash.manage`); đổi `visibleFeatures(session, perms)` /
      `canSeeFeature(session, key, perms?)` — nếu `feature.permissions` tồn
      tại thì gate bằng `perms.can`, ngược lại giữ `hasAnyRole(session,
      feature.roles)` như cũ (không đổi với các feature khác — không cần sửa
      lời gọi ở 13+ trang còn lại).
- [x] **Task 6** → AC-2, AC-3: sửa `web/src/components/AppShell.tsx` (Sidebar
      gọi `usePermissions()`, truyền vào `visibleFeatures`);
      `web/src/app/upload/page.tsx` (`canUpload`), `web/src/app/personal/page.tsx`
      (`canAddToWarehouse`), `web/src/app/digitization/page.tsx`,
      `web/src/app/digitization/runs/[runId]/page.tsx`,
      `web/src/app/trash/page.tsx` — gọi `usePermissions()` + truyền `perms`
      vào `canSeeFeature`.
- [x] **Task 7** → AC-2, AC-3: `web/src/app/documents/page.tsx` —
      `canDelete = can('trash.manage')` (bỏ `hasRole(CAN_BO)`); giữ
      `canBulkShare = can('document.share')` (Task 13, ADR-3).
- [x] **Task 8** → AC-2, AC-3: `web/src/app/documents/[id]/page.tsx` — tách
      `canWrite` thành `canDeleteDoc`/`canManageTags`/`canWriteNotes` (§2);
      `canShareGroup = can('document.share')` (Task 13, ADR-3).
- [x] **Task 9** → Dependencies/ADR-4 (không phải AC-n riêng của story này):
      `web/src/app/admin/document-ownership/page.tsx` — `recipientOptions`
      filter `a.roles.includes(Role.CAN_BO) || a.roles.includes(Role.LANH_DAO)`;
      thêm cảnh báo khi người nhận là `LANH_DAO` thiếu `document.upload` —
      fetch 1 lần `GET /admin/rbac/roles` (đã có kiểu `RbacRoleRow`), so
      `permissionCodes` của role `LANH_DAO`.
- [x] **Task 10** → AC-6 (đảo ngược đúng 7 file, không phải 8 — §7): sửa
      `web/src/app/__tests__/page.test.tsx`, `upload/__tests__/page.test.tsx`,
      `documents/__tests__/page.test.tsx`, `documents/[id]/__tests__/page.test.tsx`,
      `trash/__tests__/page.test.tsx`, `digitization/__tests__/page.test.tsx`,
      `digitization/runs/[runId]/__tests__/page.test.tsx` theo §7.
- [x] **Task 11** → hỗ trợ AC-6 (test util): thêm `web/src/test-utils/permissions.ts`
      (helper mock `usePermissions`) + sửa `web/src/test-utils/fetchMock.ts`
      (default `/me/permissions` → `{roles:[],permissions:[]}`, cùng mẫu với
      default `/notifications` đã có).
- [x] **Task 13** → ADR-3 (không map AC; người dùng chốt 2026-10-01): gate nút
      chia sẻ nhóm theo `can('document.share')` ở `app/documents/[id]/page.tsx`
      (`canShareGroup`) và `app/documents/page.tsx` (`canBulkShare`), trùng
      `@PreAuthorize` `PERM_document.share` của `DocumentSharesController` /
      `DocumentBulkSharesController`. Test: mỗi trang có case có/thiếu
      `document.share`; `test-utils/permissions.ts` default thêm `document.share`.
- [x] **Task 12** → regression AC-5: chạy lại
      `admin/accounts|rbac|audit/__tests__/page.test.tsx` **không sửa**, xác
      nhận vẫn xanh (chứng minh gate role admin không bị đụng).

## 7. Test plan

> Lệch spec đã chấp nhận (implement): ở 4 trang `upload`, `trash`, `digitization`,
> `digitization/runs/[runId]`, trang trả `null` khi đã có session mà permissions
> còn `loading` (vẫn fail-closed, tránh nháy AccessDenied); không session thì vẫn
> hiện AccessDenied.

### 7.1 Sửa 8 file test hiện có (AC-6 — 7 file ban đầu + `personal`, xem dòng cuối bảng)

Mỗi file: thêm `/me/permissions` vào `installFetchMock`, chuyển `it(...)` còn
thiếu thành `async` + `await waitFor(...)` quanh assertion phụ thuộc quyền
(permission fetch là async), và **đảo ngược đúng ý nghĩa** (R2 giờ được phép)
chứ không chỉ xoá assertion cũ:

| File | Assertion cũ (R2 bị chặn) | Assertion mới |
|---|---|---|
| `app/__tests__/page.test.tsx` | — (chưa test riêng) | can-bo: mock `/me/permissions` đủ 5 quyền, giữ assertion hiện có nhưng `await waitFor`; thêm case lanh-dao thấy "Nạp dữ liệu" ở Truy cập nhanh |
| `upload/__tests__/page.test.tsx` | lanh-dao → AccessDenied | lanh-dao có `document.upload` → thấy trang; thêm case lanh-dao thiếu quyền (R3 gỡ) → AccessDenied |
| `documents/__tests__/page.test.tsx` | lanh-dao không thấy nút Xóa | lanh-dao có `trash.manage` → thấy nút Xóa/Xóa đã chọn; case thiếu quyền → ẩn nút (giữ `canBulkShare` test cũ không đổi) |
| `documents/[id]/__tests__/page.test.tsx` (dòng ~155, ~240) | lanh-dao không thấy nút xóa/tag/note | lanh-dao có 3 quyền → thấy nút; case thiếu từng quyền riêng lẻ ẩn đúng nút đó (không ảnh hưởng phần "Chia sẻ nhóm", giữ nguyên) |
| `trash/__tests__/page.test.tsx` (dòng ~66) | lanh-dao → AccessDenied | lanh-dao có `trash.manage` → thấy trang; case thiếu quyền → AccessDenied |
| `digitization/__tests__/page.test.tsx` (dòng ~81) | lanh-dao → AccessDenied | lanh-dao có `digitization_review.manage` → thấy trang |
| `digitization/runs/[runId]/__tests__/page.test.tsx` (dòng ~115) | lanh-dao → AccessDenied | tương tự |
| `personal/__tests__/personalToWarehouse.test.tsx` (bổ sung khi implement) | R2 không thấy nút "Bổ sung vào kho" | `canAddToWarehouse` đọc feature `upload` (đã chuyển sang permission): R2 có `document.upload` → thấy nút; R2 thiếu quyền → ẩn |

### 7.2 Case biên / AC riêng

- AC-4: `usePermissions` fetch lỗi 5xx/timeout lần đầu → `can()` luôn `false`,
  banner hiện (test ở `permissions.test.ts` + `PermissionsBanner.test.tsx`).
- AC-7: 403 + focus cùng lúc → chỉ 1 lệnh fetch (debounce, test ở
  `permissions.test.ts`); không fetch thêm khi không có hành động nào khác
  trong khoảng giữa 2 chu kỳ.
- AC-8: refetch nền (403/focus/chu kỳ) khi đã có permissions cũ → giữ
  nguyên permissions trong lúc `fetch` đang chạy, không nhảy về `false` giữa
  chừng (test giả lập fetch chậm bằng promise chưa resolve).
- ADR-4 (Task 9): test mới cho `admin/document-ownership` — recipient list có
  `lanh-dao`; cảnh báo xuất hiện khi role đó thiếu `document.upload` (mock
  `/admin/rbac/roles` tương ứng).

## 8. Observability

- Không log PII. Browser: không log nội dung `permissions` ra console.
- Backend: không thêm log mới (request `/me/permissions` đi qua access log
  chung nếu có).

## 9. Security checklist

- [x] `GET /me/permissions` chỉ đọc từ `Authentication` hiện tại, không nhận
      tham số định danh user khác (AC-1, chống đọc quyền người khác).
- [x] Không mở `@PreAuthorize` mới nào rộng hơn gate hiện có — thuần hiển thị.
- [x] Fail-closed ở client khi loading/error (AC-4, AC-8) — không fail-open.
- [ ] Rate limit: không cần (gọi vài lần/phiên, không phải theo hành động người dùng).

## 10. Migration & Rollback plan

- Migration: không có (không đổi schema).
- Rollback: revert riêng từng phần — BE (`MeController`) có thể gỡ độc lập
  (web sẽ fail-closed nếu 404/lỗi — vẫn an toàn vì fail-closed, không fail-open);
  web nên deploy **sau** BE (cần endpoint tồn tại) — khớp §7 Tech stack của
  ARCH ("deploy BE trước rồi web").
- Feature flag: không có.

## 11. Rủi ro kỹ thuật

- Nếu một trang gọi `usePermissions()` nhưng quên dùng kết quả (ví dụ do thao
  tác thủ công khi merge), ESLint `react-hooks` không bắt được lỗi logic
  "quên truyền `perms` vào `canSeeFeature`" — cần review kỹ diff Task 6.
- `installFetchMock` default mới cho `/me/permissions` (empty permissions)
  ảnh hưởng **mọi** test dùng `AppShell` dù không liên quan — đã rà soát
  không có test khác (ngoài 7 file ở §7.1) assert hiển thị/đếm nav
  upload/digitization/trash; nếu phát hiện thêm khi chạy `npm test`, bổ sung
  vào §7.1 trước khi coi Task 10 hoàn tất.
- Debounce 2s (AC-7) là hằng số nội bộ (đã chốt §14, không phải NFR) — nếu
  QA thấy quá ngắn/dài, có thể chỉnh mà không cần spec mới, không ảnh hưởng AC nào khác (không hard-code trong
  test theo giá trị tuyệt đối, test theo "chỉ 1 lần gọi").
- `canShareGroup`/`canBulkShare` đã gộp vào story này (Task 13, ADR-3): hai nút
  chia sẻ giờ phụ thuộc `/me/permissions` như các gate khác (fail-closed khi
  loading/lỗi).

## 12. Backlog ngoài phạm vi (không implement trong story này)

- Đổi toàn bộ các feature còn lại (`documents`, `search`, `groups`,
  `personal`, `account-password`) từ `roles` sang `permissions` tương ứng
  (`document.read`, `group.manage`, `personal.manage`, `account.self_service`)
  để nhất quán tuyệt đối với ADR-3 — không có AC nào yêu cầu, để lại cho một
  "web permission gating v2" nếu cần.

## 13. Definition of Done

- [x] Tất cả AC-1..AC-8 pass (đối chiếu QA report theo bảng §7) — `docs/04-qa/reports/r2-business-parity-03-web-permission-gating.md` kết luận PASS (2026-10-01), kèm Task 13/ADR-3
- [x] `MeControllerIT` xanh (chạy bằng `mvn -o verify -Dit.test=MeControllerIT`: 3 test, 0 fail; IT chỉ chạy ở `verify`)
- [x] `cd web && npx tsc --noEmit && npm run lint && npm test` xanh (toàn bộ,
      không chỉ 7 file đã sửa — Task 12 xác nhận admin/* không hồi quy)
- [x] Coverage đã đo (web, `@vitest/coverage-v8`; không có ngưỡng trong spec và không có baseline để so sánh; backend không đo — không có jacoco): tổng web (QA đo lại 2026-10-01, sau khi thêm test mới, 43 file / 403 test) statements 81.38% / branches 75.13% / functions 73.04% / lines 83.61%; `lib/permissions.ts` 100/82.92; `lib/forbiddenBus.ts` 100/100; `lib/api.ts` 89.13/71.87; `lib/features.ts` 88.88/75; `components/PermissionsBanner.tsx` 100/100
- [x] Đã cân nhắc migration & rollback (§10; deploy backend trước, web sau — đã làm đúng thứ tự)

## 14. Câu hỏi mở

- [x] Debounce 2s cho dedupe refetch (§5, §11) — giữ hằng số nội bộ không
      cần duyệt riêng, hay cần ghi thành NFR tường minh? → **Chốt:** hằng số
      nội bộ `REFETCH_DEDUPE_MS` trong `permissions.ts`, không phải NFR; đổi
      không cần spec mới; test assert "chỉ 1 lần gọi", không assert giá trị
      tuyệt đối (cập nhật: §6 Task 2, §11)
- [x] `canShareGroup`/`document.share` (§2) — **Chốt:** gộp cả 2 trang (`canShareGroup` ở documents/[id], `canBulkShare` ở documents), task map ADR-3 (không map AC), người dùng chốt 2026-10-01 (cập nhật: §2, §6 Task 13, §11).
