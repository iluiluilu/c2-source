# QA Report: r2-business-parity-01-permission-grant

> File: `docs/04-qa/reports/r2-business-parity-01-permission-grant.md` — Owner: QA
> Nguồn story: `docs/02-ba/stories/r2-business-parity-01-permission-grant.md` (STORY-r2-business-parity-01)
> Nguồn spec: `docs/03-dev/specs/r2-business-parity-01-permission-grant.md` (SPEC-r2-business-parity-01)
> Ngày review: 2026-10-01 (cập nhật sau khi DEV đính chính §13 + QA re-verify AC-10 riêng)
> Commit/branch reviewed: `main` — **uncommitted working tree** (no commit hash;
> `git rev-parse --short HEAD` = `667e2a5`, nhưng toàn bộ thay đổi của story này
> chưa commit, nằm trong working tree cùng với thay đổi của ít nhất 1 feature
> khác chưa liên quan — xem "Kiểm tra thêm" bên dưới).

## Kết luận

**PASS**

AC-1..AC-9: Pass, bằng chứng từ integration test chạy thật (Docker/Testcontainers
bật). AC-10: ma trận 32×3 pass đầy đủ; yêu cầu "bộ integration test phải xanh"
**đạt** sau khi re-verify — 2 lỗi phát hiện ở lần chạy `mvn -o verify` toàn bộ
đầu tiên (`TagsControllerIT`, `SearchScopeIT`) đều **không tái lập khi chạy
riêng lẻ** (QA tự chạy độc lập, không chỉ tin báo cáo của DEV): `TagsControllerIT`
17/17 pass, `SearchScopeIT` 16/16 pass — xác nhận là flaky do tranh chấp tài
nguyên Docker khi chạy liên tục 35 lớp IT (`reuseForks=false`, ~29 phút), không
phải lỗi logic của 5-permission grant. Spec §13 đã được DEV đính chính đúng số
liệu thật (không còn "1004/1004 pass" sai lệch). Hạ xuống non-blocking, đề xuất
tách task riêng điều tra flaky hạ tầng test.

## Đối chiếu Acceptance Criteria

| AC | Kết quả | Bằng chứng (file:line / test / log) |
|---|---|---|
| AC-1 (document.upload, kể cả from-personal) | Pass | `IngestControllerIT` 27/27 (gồm `leaderCanOpenBatchAndReadLimits` dòng 726); `PersonalToWarehouseIT` 20/20 (gồm `leaderGets202ForTheirOwnPersonalFile` dòng 357); `R2PermissionMatrixIT` case #1-12 trong 97/97 pass. |
| AC-2 (tag.manage) | Pass | `TagsControllerIT#leaderCanCreateAndAttachTag` (dòng 493, mới) — lớp 17/17 pass khi chạy riêng lẻ; `R2PermissionMatrixIT` case #13-17. |
| AC-3 (note.write) | Pass | `NotesControllerIT` 14/14 (đảo `postAndPatchByANonOfficerAreForbidden` → leader 2xx + admin 403); `R2PermissionMatrixIT` case #18-19. |
| AC-4 (digitization_review.manage) | Pass | `ReviewControllerIT` 29/29 (gồm `queue_forR2OnlyListsRunsUploadedByTheLeader` dòng 319); `R2PermissionMatrixIT` case #20-26. |
| AC-5 (trash.manage) | Pass | `TrashControllerIT` 19/19 (`trashSucceedsForR2LeaderOwner`/`restoreSucceedsForR2LeaderOwner` dòng 568, 590); `R2PermissionMatrixIT` case #27-32. |
| AC-6 (R3 vẫn 403, không leo thang) | Pass | `R2PermissionMatrixIT` 97/97 (32×3, mọi case `role=QUAN_TRI` → 403); `TagsControllerIT`/`IngestControllerIT`/`NotesControllerIT`/`ReviewControllerIT` giữ assertion `admin()`→403 theo bảng §5.3 của spec. |
| AC-7 (migration idempotent khi R3 đã chỉnh trước) | Pass | `backend/src/main/resources/db/migration/V35__r2_business_parity.sql` dùng `ON CONFLICT (role_code, permission_code) DO NOTHING`, không UPDATE/DELETE; `RolePermissionSeedTest#v35ReRunIsNoopWhenR3AlreadyEditedThatPair` (dòng 194-222) pass — set `updated_by_sub='r3-sub-1'` trước, chạy lại câu INSERT của V35, xác nhận `updated_by_sub` giữ nguyên + không có dòng trùng. |
| AC-8 (hạn mức batch/capacity không phân biệt vai trò) | Pass | `IngestControllerIT` có `leaderOpenBatchAboveDeclaredFileCountLimitGetsTheSameErrorAsOfficer`/`leaderWarehouseCapacityBlockedGetsTheSameErrorAsOfficer` (trong 27/27 pass), cùng mã lỗi `BATCH_LIMIT_EXCEEDED`/`WAREHOUSE_CAPACITY_EXCEEDED` như officer. |
| AC-9 (luồng phụ thuộc uploader đúng với R2) | Pass | `ReviewControllerIT#queue_forR2OnlyListsRunsUploadedByTheLeader`, `TrashControllerIT#leaderSeesOwnTrashedDocumentAndAnotherOfficerDoesNot`, `AuditControllerIT#leaderSeesTagAndNoteEventsOnTheirOwnDocument` (dòng 342), `PersonalToWarehouseIT#leaderInitiatedIngestProducesADocumentUploadedByTheLeader` — tất cả pass trong các lớp nêu trên. Giới hạn `ingest.batch.*` không scoped được đã ghi chú đúng (xem "Kiểm tra thêm"). |
| AC-10 (ma trận đầy đủ + bộ IT xanh trước QA) | **Pass** | Ma trận: `R2PermissionMatrixIT` đúng 32 endpoint × 3 role = 96 case + 1 test meta đếm `@PreAuthorize` khớp 32 (dòng 308-330) — Pass. "Bộ integration test xanh": lần chạy `mvn -o verify` toàn bộ đầu tiên có 1 failure (`TagsControllerIT`) + 1 error (`SearchScopeIT`); QA tự re-run riêng từng lớp (`mvn -o verify -Dtest=NoSuchTest -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=<Lớp>`): `TagsControllerIT` **17/17 pass**, `SearchScopeIT` **16/16 pass** — cả 2 không tái lập khi chạy độc lập → flaky hạ tầng (tranh chấp tài nguyên Docker trong lần chạy 35 lớp liên tục ~29 phút), không phải lỗi của 5-permission grant. Mọi lớp IT thuộc phạm vi story (bảng dưới) xanh 100% ở cả 2 lần chạy. |

## Kết quả test/lint đã chạy

```
$ cd backend && mvn -o test
→ Tests run: 570, Failures: 0, Errors: 0, Skipped: 0 (chỉ chạy surefire —
  *IT.java KHÔNG được thực thi; xác nhận qua pom.xml: maven-failsafe-plugin
  bind goal integration-test/verify vào phase integration-test/verify, không
  phải phase test. Build 47s — quá nhanh so với 35 lớp Testcontainers IT,
  khớp với việc IT không chạy). Lưu ý: spec §13 đã được DEV đính chính, không
  còn dùng con số từ lệnh này để khẳng định "xanh" nữa.

$ cd backend && mvn -o verify   # lệnh đúng để chạy cả unit + 35 lớp IT
→ Unit (surefire): Tests run: 570, Failures: 0, Errors: 0, Skipped: 0
→ IT (failsafe):   Tests run: 414, Failures: 1, Errors: 1, Skipped: 0
  [ERROR] TagsControllerIT.assigningToADocumentOutOfScopeReturns404AndRecordsAnAccessDeniedAuditEntry:435
  [ERROR] SearchScopeIT » ContainerLaunch Container startup failed for image
    aismartstore-opensearch-icu-test:latest — Timed out waiting for
    /_cluster/health sau 180s.
→ BUILD FAILURE, total time 28:57 min.

# QA tự re-run riêng từng lớp để xác nhận flaky (không chỉ tin báo cáo của DEV):
$ mvn -o verify -Dtest=NoSuchTest -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=TagsControllerIT
→ Tests run: 17, Failures: 0, Errors: 0, Skipped: 0 — BUILD SUCCESS (1 lần chạy, pass ngay).

$ mvn -o verify -Dtest=NoSuchTest -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=SearchScopeIT
→ Tests run: 16, Failures: 0, Errors: 0, Skipped: 0 — BUILD SUCCESS (1 lần chạy, pass ngay;
  không cần lần 2 — khác với lần DEV re-run trước đó fail ở lần 1 rồi pass lần 2,
  củng cố thêm giả thuyết flaky/tranh chấp tài nguyên chứ không phải lỗi quyết định).

Các lớp IT liên quan trực tiếp tới 5 permission của story — đều xanh trong
cả 2 lần chạy `mvn -o verify` toàn bộ và riêng lẻ:
  R2PermissionMatrixIT        97/97
  IngestControllerIT          27/27
  NotesControllerIT           14/14
  TrashControllerIT           19/19
  ReviewControllerIT          29/29
  PersonalToWarehouseIT       20/20
  AuditControllerIT            9/9
  RolePermissionSeedTest       7/7  (gồm AC-7 case biên)
  AccountsServiceTest         14/14 (fix ngoài phạm vi, xem bên dưới)
  AccountsControllerIT         8/8
  SearchIndexOutboxPollerIT    7/7  (cùng ảnh Docker opensearch với SearchScopeIT)
  TagsControllerIT            17/17 khi chạy riêng (16/17 trong lần full-suite đầu)
```

## Vấn đề chặn (Blocking)

Không còn vấn đề chặn nào sau khi re-verify.

## Đề xuất cải thiện (Non-blocking)

- [ ] **Flaky hạ tầng test khi chạy toàn bộ `mvn -o verify` (~29 phút, 35 lớp
      IT, `reuseForks=false`):** `TagsControllerIT.assigningToADocumentOutOfScopeReturns404AndRecordsAnAccessDeniedAuditEntry`
      và `SearchScopeIT` (container OpenSearch test timeout `/_cluster/health`
      180s, "Unexpected end of file from server", không OOM) thỉnh thoảng fail
      khi chạy trong bộ đầy đủ nhưng xanh 100% khi chạy riêng lẻ (QA tự xác
      nhận độc lập: `TagsControllerIT` 17/17, `SearchScopeIT` 16/16, mỗi lớp
      chỉ 1 lần chạy là pass). Cả 2 lớp **không bị story này sửa logic**
      (`TagsControllerIT` chỉ được thêm 1 test mới ở cuối file, không đụng
      setup/fixture dùng chung; `SearchScopeIT` dùng path Dockerfile đã sửa
      đúng — xem mục dưới). Đề xuất tách một task riêng (ngoài phạm vi
      SPEC-r2-business-parity-01) để điều tra nguyên nhân gốc (resource
      contention Docker Desktop khi chạy liên tục nhiều Testcontainers, hoặc
      tăng wait-strategy timeout của `SearchScopeIT` / cô lập transaction cho
      audit-log assertion của `TagsControllerIT`) — không chặn merge story
      này.
- [ ] **`AccountsService.setStatus` bỏ bắt buộc `reason` khi mở khoá** (`backend/
      src/main/java/com/aismartstore/backend/accounts/AccountsService.java:326`:
      `if (!active && (dto.reason() == null || dto.reason().isBlank()))`) —
      thay đổi hợp lý, nhất quán với dòng `setStatusReason(active ? null :
      dto.reason())` ngay bên dưới, có test xác nhận (`AccountsServiceTest`
      14/14, `AccountsControllerIT` 8/8 pass). Đây là thay đổi **ngoài phạm vi**
      5 permission của SPEC-r2-business-parity-01 (module `accounts`, không
      thuộc 32 endpoint §5.2) — ghi chú implement nói đã được người dùng xác
      nhận trực tiếp khi DEV dừng lại hỏi. Không chặn, nhưng nên tách thành
      commit/PR riêng về mặt quy trình (vì không map tới AC nào của story này)
      để lịch sử thay đổi rõ ràng, và BA/PO nên biết hành vi nghiệp vụ "mở khoá
      không cần lý do" đã thay đổi.
- [ ] **Sửa path Dockerfile test OpenSearch** (`main-workspace/opensearch` →
      `opensearch/` ở gốc repo, trong `SearchScopeIT.java`/
      `SearchIndexOutboxPollerIT.java`) — hợp lý, khớp việc hạ tầng đã chuyển
      lên gốc repo (xem memory "Stg deploy infra drift"); xác nhận ảnh build
      đúng (container tạo được, cả 2 lớp pass khi chạy riêng/trong bộ). Ngoài
      phạm vi 5 permission nhưng là sửa hạ tầng test nhỏ, chấp nhận được theo
      đúng quyết định đã chốt ở Task 1/câu hỏi mở đã duyệt của spec (§12).

## Kiểm tra thêm

- [x] Security checklist trong spec đã thoả? Có — không thêm endpoint mới,
      không đụng `QUAN_TRI`, R3 vẫn 403 ở cả 32 endpoint (`R2PermissionMatrixIT`),
      rate limiter theo sub giữ nguyên (không có thay đổi code liên quan,
      `nameCheckRateLimiter` không đổi), review mục "R2 giờ xoá mềm được tài
      liệu của chính mình/được chia sẻ" đúng phạm vi story (`trash.manage`) —
      xác nhận qua `TrashControllerIT`.
- [x] Observability như spec? Có — không log mới; Flyway log "Migrating schema
      ... to version '35 - r2 business parity'" xuất hiện trong mọi lần khởi
      động test (`mvn -o verify` log, xác nhận migrate tới "v35" + "Successfully
      applied 34 migrations"). Audit 403 của R3 vẫn qua
      `ApiExceptionHandler#recordAccessDeniedAudit` như cũ (không đổi code).
- [x] Migration/rollback an toàn? Có — `V35__r2_business_parity.sql` chỉ
      INSERT + `ON CONFLICT DO NOTHING`, không DELETE/UPDATE; khớp đúng §2 của
      spec 100% (đối chiếu từng dòng SQL); không có migration bù viết sẵn,
      đúng quyết định đã chốt ở §9/§12.
- [x] Giới hạn `audit.read_scoped` (`ingest.batch.*` không scope được) — xác
      nhận ĐÚNG như lưu ý đã biết trong đề bài: `AuditControllerIT` chỉ test
      `tag.assign`/`note.create`, có comment rõ ràng giải thích giới hạn
      (`AuditControllerIT.java:333-339`), không coi là lỗi của story này.
- [x] Trước khi review đã kiểm tra `> Status:` của story và spec — cả 2 đều
      `Approved`, không cần hỏi lại người dùng.
- [x] Spec §13 đã được DEV đính chính sau phản hồi QA lần 1: xác nhận lệnh đúng
      là `mvn -o verify` (không phải `mvn -o test`), ghi đúng số liệu thật
      (570 unit + 414 IT, 1 failure + 1 error ở lần full-suite đầu), và nêu rõ
      kết quả re-run riêng lẻ. QA đã tự chạy lại độc lập (không chỉ tin báo cáo
      DEV) và xác nhận khớp.
- [~] Working tree sạch cho riêng story này? **Không hoàn toàn** — `git status`
      cho thấy nhiều file ngoài phạm vi SPEC-r2-business-parity-01 cũng đang
      bị sửa/chưa commit cùng lúc (vd. `Document.java`, `DocumentRepository.java`,
      `UploadSession*.java`, `PersonalFile*.java`, thư mục `displayname/` mới,
      migration `V34__folder_upload_display_name.sql`) — có vẻ thuộc một
      feature "folder upload display name" khác, không phải của story này. QA
      chỉ đối chiếu các file có map trực tiếp tới AC/spec của
      SPEC-r2-business-parity-01 (migration V35, `R2PermissionMatrixIT`, các
      IT trong bảng §5.3/§5.4, `RolePermissionSeedTest`,
      `RolePermissionFixtures`, Javadoc 3 chỗ, `AccountsService`, 2
      Dockerfile-path IT) và không review nội dung các file thuộc feature
      khác. Đề nghị commit tách bạch theo story trước khi merge, để tránh lẫn
      lộn khi rollback/audit sau này.
