## Handoff: r2-business-parity-03-web-permission-gating (implement + QA xong)

### Đã chốt
- Spec `Approved`, Task 1–13 xong; QA report kết luận **PASS** (re-review lần 3, 2026-10-01), không còn blocking.
- BE: `GET /me/permissions` (`auth/MeController.java`); `MeControllerIT` 3/3 pass (`mvn -o verify -Dit.test=MeControllerIT`, cần Docker).
- Web: gate theo permission qua `lib/permissions.ts` (`usePermissions`/`can()`, fail-closed), `forbiddenBus` (403 → refetch), `PermissionsBanner`; `features.ts` + các trang upload/digitization/trash/documents/personal dùng `perms`.
- Lệch spec đã chấp nhận (§7): 4 trang trả `null` khi có session mà permissions đang loading; thêm `personalToWarehouse.test.tsx` vào §7.1.
- Câu hỏi mở `canShareGroup` đã chốt (người dùng, 2026-10-01): `canShareGroup` (documents/[id]) và `canBulkShare` (documents) → `can('document.share')`, Task 13 map ADR-3.
- Test: tsc/lint sạch, vitest 43 file / 403 test pass; coverage web lines 83.61% / branches 75.13% (ghi ở DoD §13, không có baseline); BE không đo (không có jacoco).
- Đã rebuild docker backend rồi web (`docker compose up -d --build`); smoke `/me/permissions` không JWT → 401, web :3000 → 200.

### Câu hỏi mở / rủi ro
- CHƯA commit. `web/` là git repo riêng: commit `package.json` + `package-lock.json` (thêm devDep `@vitest/coverage-v8@4.1.11`, lock regenerate trên Linux +171 dòng — người dùng cần duyệt diff) cùng code web; BE + docs commit ở repo `source_v2`.
- Non-blocking (QA): trang trắng vô hạn nếu session có nhưng thiếu `accessToken` → nên thêm fallback; comment lỗi thời `documents/[id]/__tests__/page.test.tsx:14`; IT dùng `RolePermissionFixtures` (không đọc DB thật), chỉ kiểm `roles[0]`.
- Backlog §12 còn: chuyển các feature còn lại (`documents`, `search`, `groups`, `personal`, `account-password`) từ role sang permission ("web permission gating v2").
- Chưa thử end-to-end trên trình duyệt với tài khoản LANH_DAO/QUAN_TRI thật.
- `docs/00-overview/features.md` chỉ có dòng gộp `r2-business-parity`, chưa cập nhật tiến độ 01/02/03 (01 QA PASS, 02 QA PASS WITH ISSUES, 03 QA PASS).

### File cần đọc tiếp
- docs/04-qa/reports/r2-business-parity-03-web-permission-gating.md
- docs/03-dev/specs/r2-business-parity-03-web-permission-gating.md (§7, §12, §13)
- docs/04-qa/reports/r2-business-parity-02-ownership-transfer.md (còn issues)

### Bước kế tiếp đề xuất
- Người dùng review diff + commit (repo `web/` và `source_v2`); cập nhật `docs/00-overview/features.md`.
- Tuỳ chọn: /sa-spec hoặc /ba-story cho "web permission gating v2" (backlog §12); xử lý non-blocking của report 02.
