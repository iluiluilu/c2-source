# Tech Spec: r2-business-parity-01-permission-grant

> File: `docs/03-dev/specs/r2-business-parity-01-permission-grant.md` — Owner: DEV
> Nguồn story: `docs/02-ba/stories/r2-business-parity-01-permission-grant.md` (STORY-r2-business-parity-01)
> Kiến trúc: `docs/01-sa/architecture/r2-business-parity.md` (ADR-1; R-1, R-2, R-5, R-6, R-7)
> Status: Approved
> Spec ID: `SPEC-r2-business-parity-01`

## 1. Tóm tắt kỹ thuật

- Cấp 5 permission cho `LANH_DAO` bằng một migration Flyway **data-only**
  `V35__r2_business_parity.sql` (V34 là số lớn nhất hiện có, grep 2026-10-01;
  grep lại trước khi merge, nếu V35 đã bị chiếm thì lấy số kế tiếp).
- **Không** sửa `@PreAuthorize` nào. Grep 2026-10-01 trên `backend/src/main/java`:
  có 32 endpoint gate bằng `hasAuthority('PERM_<x>')` cho 5 permission (ma trận
  §5.2). Không còn `hasRole`/`hasAnyRole`/check `CAN_BO` trong service/worker nào
  chặn R2 (`PersonalIngestWorker`, `PersonalToWarehouseService`, `TrashService`,
  `ReviewQueueRepository`, `UploadBatchService`, `NotificationService` chỉ dựa trên
  `sub`, không dựa trên role).
- Phần code thay đổi: sửa Javadoc lỗi thời, cập nhật fixture/test, thêm IT ma
  trận tham số hoá đọc quyền **từ DB sau Flyway** (nên kiểm được cả migration lẫn
  annotation).
- Hiệu lực sau deploy: `SecurityConfig#realmRoleAuthorities` emit `PERM_<code>`
  từ `role_permission` qua cache Caffeine in-process TTL 60s
  (`RbacCacheConfig`). Deploy restart backend nên cache rỗng, quyền mới có hiệu
  lực ngay ở request đầu tiên. Không cần R2 đăng nhập lại (permission không nằm
  trong JWT).

## 2. Data model

Không đổi schema. Bảng dùng: `role_permission(role_code, permission_code, updated_at
DEFAULT now(), updated_by_sub NULL)`, PK `(role_code, permission_code)` (V12 + V15).
Cả 5 `permission.code` đã tồn tại (V12: `document.upload`, `note.write`,
`tag.manage`; V28: `digitization_review.manage`; V29: `trash.manage`).

```sql
-- V35__r2_business_parity.sql
-- ARCH-r2-business-parity ADR-1 / SPEC-r2-business-parity-01: cấp 5 quyền nghiệp vụ
-- R1 cho R2 (LANH_DAO). Data-only, idempotent: chỉ INSERT, không DELETE/UPDATE,
-- ON CONFLICT DO NOTHING để không đụng lựa chọn R3 đã chỉnh qua màn RBAC (AC-7).
-- Liệt kê tường minh từng cặp, không SELECT từ role khác (R-2: tránh leo thang).
-- updated_by_sub = NULL: bản ghi seed, không phải R3 sửa tay.
INSERT INTO role_permission (role_code, permission_code) VALUES
    ('LANH_DAO', 'document.upload'),
    ('LANH_DAO', 'tag.manage'),
    ('LANH_DAO', 'note.write'),
    ('LANH_DAO', 'digitization_review.manage'),
    ('LANH_DAO', 'trash.manage')
ON CONFLICT (role_code, permission_code) DO NOTHING;
```

Ghi chú AC-7: Flyway chỉ chạy versioned migration **một lần** mỗi DB. Nếu R3 đã tự
thêm cặp nào trước khi deploy, `ON CONFLICT` bỏ qua (giữ nguyên `updated_by_sub` của
R3). Nếu sau deploy R3 gỡ quyền, V35 không chạy lại nên không ghi đè. Hạn chế đã
biết: R3 không thể "gỡ trước" một quyền mà R2 chưa từng có, nên nếu môi trường muốn
R2 không có quyền X thì R3 gỡ sau khi deploy.

## 3. API contract

Không thêm/sửa endpoint, request hay response. Thay đổi duy nhất về hành vi: R2
(token có realm role `lanh-dao`) không còn nhận 403 ở 32 endpoint trong §5.2; R3
vẫn 403 như trước.

## 4. Luồng xử lý chính

```mermaid
sequenceDiagram
  participant W as R2 client
  participant F as Spring Security filter
  participant C as Caffeine cache (TTL 60s)
  participant DB as Postgres role_permission
  participant M as @PreAuthorize (controller)
  W->>F: request + JWT (realm_access.roles=[lanh-dao])
  F->>C: get("LANH_DAO")
  C->>DB: SELECT permission_code WHERE role_code='LANH_DAO' (cache miss)
  DB-->>C: {..., document.upload, tag.manage, note.write, digitization_review.manage, trash.manage}
  F-->>M: authorities ROLE_LANH_DAO + PERM_*
  M-->>W: vào controller (không 403); "chính chủ" vẫn kiểm theo sub ở service
```

1. Flyway chạy V35 lúc backend khởi động (trước khi nhận request).
2. Request của R2 resolve quyền từ DB như trên; kiểm tra "chính chủ"
   (`uploader_sub = current sub`) ở review/trash giữ nguyên, áp dụng cho R2 giống R1.

## 5. Test plan

### 5.1 Baseline

Bộ IT Testcontainers chưa chạy lại toàn bộ sau lần sửa ApplicationContext
(2026-09-29, test yml chuyển sang `src/test/resources/config/application.yml`).
Task 1 chạy `mvn -o test` (cần Docker) **trước** mọi thay đổi, ghi lại danh sách
test đỏ có sẵn để không nhầm với hồi quy của story này.

### 5.2 Ma trận endpoint × vai trò (AC-10)

Kỳ vọng mọi dòng: **R1 pass (giữ nguyên), R2 pass (mới), R3 403 (giữ nguyên)**.
"Pass" = status khác 403 (2xx/400/404/409 đều chấp nhận được với dữ liệu giả).

| # | Permission | Method | Path | Controller |
|---|---|---|---|---|
| 1 | document.upload | GET | /ingest/limits | IngestController |
| 2 | document.upload | GET | /ingest/allowed-formats | IngestController |
| 3 | document.upload | POST | /ingest/batches | IngestController |
| 4 | document.upload | POST | /ingest/batches/{batchId}/files (multipart) | IngestController |
| 5 | document.upload | POST | /ingest/name-check | IngestController |
| 6 | document.upload | POST | /ingest/batches/{batchId}/upload-sessions | IngestController |
| 7 | document.upload | POST | /ingest/batches/{batchId}/close | IngestController |
| 8 | document.upload | GET | /ingest/batches/{batchId} | IngestController |
| 9 | document.upload | POST | /ingest/from-personal | IngestController |
| 10 | document.upload | POST | /ingest/from-personal/name-check | IngestController |
| 11 | document.upload | PUT | /ingest/from-personal/{batchId}/items/{itemId}/display-name | IngestController |
| 12 | document.upload | GET | /ingest/from-personal/{batchId} | IngestController |
| 13 | tag.manage | POST | /tags | TagsController |
| 14 | tag.manage | PATCH | /tags/{id} | TagsController |
| 15 | tag.manage | POST | /tags/{id}/deactivate | TagsController |
| 16 | tag.manage | POST | /documents/{documentId}/tags | DocumentTagsController |
| 17 | tag.manage | DELETE | /documents/{documentId}/tags/{tagId} | DocumentTagsController |
| 18 | note.write | POST | /documents/{documentId}/notes | NotesController |
| 19 | note.write | PATCH | /documents/{documentId}/notes/{noteId} | NotesController |
| 20 | digitization_review.manage | GET | /digitization-review/queue | ReviewController |
| 21 | digitization_review.manage | GET | /digitization-review/runs/{runId} | ReviewController |
| 22 | digitization_review.manage | PATCH | /digitization-review/segments/{segmentId} | ReviewController |
| 23 | digitization_review.manage | DELETE | /digitization-review/segments/{segmentId} | ReviewController |
| 24 | digitization_review.manage | POST | /digitization-review/runs/{runId}/segments | ReviewController |
| 25 | digitization_review.manage | POST | /digitization-review/runs/{runId}/confirm | ReviewController |
| 26 | digitization_review.manage | GET | /digitization-review/runs/{runId}/pages/{pageNo}/image | ReviewController |
| 27 | trash.manage | POST | /documents/trash | DocumentsController |
| 28 | trash.manage | POST | /documents/trash-preview | DocumentsController |
| 29 | trash.manage | GET | /trash | TrashController |
| 30 | trash.manage | GET | /trash/{id} | TrashController |
| 31 | trash.manage | POST | /trash/{id}/restore | TrashController |
| 32 | trash.manage | POST | /trash/restore-batch | TrashController |

Ngoài ma trận (đã kiểm, không gate bởi 5 permission): `UploadSessionController`
(`isAuthenticated()` + chủ session, bước upload chunk của luồng nạp), các endpoint
`PERM_document.read` của review/tags/notes, `/trash/stats` (`trash.stats.read`, R3).

### 5.3 Integration test

- **Mới** `backend/src/test/java/com/aismartstore/backend/rbac/R2PermissionMatrixIT.java`:
  `@ParameterizedTest` + `@MethodSource` (32 endpoint × 3 role = 96 case). Authorities
  dựng **từ DB sau Flyway** (`SELECT permission_code FROM role_permission WHERE
  role_code=?` → `ROLE_<x>` + `PERM_<code>`), **không** dùng
  `RolePermissionFixtures`, để kiểm cả V35. Testcontainers Postgres + Redis, mock
  `ObjectStorageService`/`ClamdService` theo mẫu `IngestControllerIT`.
  Lưu ý kỹ thuật: argument resolution (`@RequestBody`/`@Valid`, chuyển đổi UUID ở
  path) chạy **trước** proxy `@PreAuthorize`, nên request phải có path UUID hợp lệ
  và body JSON/multipart hợp lệ tối thiểu; nếu không R3 nhận 400 thay vì 403 và test
  sai ý. Mỗi case khai báo body mẫu riêng.
  Thêm 1 test meta: đếm `@PreAuthorize` chứa 5 permission bằng reflection trên
  `@RestController` beans (`RequestMappingHandlerMapping`) và so khớp với 32 dòng
  của ma trận, để endpoint mới thêm sau này không lọt khỏi ma trận.
- **Sửa** test đang khẳng định R2 bị 403 (đảo ngược, R-7) — giữ phần R3 403:

| File | Test hiện có | Thay đổi |
|---|---|---|
| ingest/IngestControllerIT.java | `nameCheckRequiresTheUploadPermission` (leader→403) | đổi sang `admin()`; thêm case leader→200 |
| ingest/IngestControllerIT.java | `nonOfficerIsForbiddenOnIngestEndpoints` (leader→403) | đổi sang `admin()`; thêm `leaderCanOpenBatchAndReadLimits` |
| personal/ingest/PersonalToWarehouseIT.java | `canBoGets202ButLeaderAndAdminAre403` | tách: leader (chủ file cá nhân) → 202; admin → 403 |
| notes/NotesControllerIT.java | `postAndPatchByANonOfficerAreForbidden` (leader) | đổi sang leader→2xx; thêm case admin→403 |
| trash/TrashControllerIT.java | `trashForbiddenForR2Leader`, `restoreForbiddenForR2Leader` | đổi thành R2 trash/restore tài liệu của chính R2 → 200; giữ test R3 |
| digitization/review/ReviewControllerIT.java | `missingPermission_returns403` (leader) | đổi sang `admin()`; thêm R2 chính chủ xem queue/confirm |
| groups/RolePermissionSeedTest.java | `EXPECTED` | 5 permission thêm `LANH_DAO` |
| testsupport/RolePermissionFixtures.java | `LANH_DAO` set | thêm 5 permission + comment V35 |

  Tags: `TagsControllerIT` hiện không có test leader→403 cho `tag.manage`; thêm
  `leaderCanCreateAndAttachTag`. `RbacControllerIT` tự reset seed riêng, không đổi.
  `SecurityConfigTest` dùng mock repository, không đổi.

### 5.4 AC-8 / AC-9 (R-6) — chỉ thêm test, không sửa logic

Đã xác minh: `UploadBatchService` kiểm `INGEST_MAX_FILES_PER_BATCH`,
`INGEST_MAX_BATCH_SIZE_BYTES`, `WarehouseCapacityService.assertIngestAllowed()` không
tham chiếu role; `ReviewQueueRepository` lọc `d.uploader_sub = ?`; `TrashService`
kiểm `isUploader/isDeleter` theo sub; `NotificationService` theo `recipient_sub`
(chỉ GroupsService phát thông báo); audit scoped (`AuditController`,
`PERM_audit.read_scoped`) lọc theo tài liệu của chính/được chia sẻ.

- AC-8 (`IngestControllerIT`): leader mở batch vượt `declaredFileCount` /
  `declaredTotalBytes` → cùng mã lỗi như officer; capacity exceeded → cùng lỗi.
- AC-9:
  - `ReviewControllerIT`: tài liệu `uploader_sub=leader-1` hiện trong
    `/digitization-review/queue` của leader, **không** hiện với officer khác.
  - `TrashControllerIT`: leader xoá mềm tài liệu của chính mình, thấy trong `/trash`,
    khôi phục được; officer khác không thấy.
  - `AuditControllerIT`: sự kiện ingest/tag/note trên tài liệu do leader nạp xuất
    hiện trong `GET /audit` của chính leader (scoped).
  - `PersonalToWarehouseIT`: leader nạp từ kho cá nhân, document tạo ra có
    `uploader_sub=leader-1`.

### 5.5 Case biên

- R3 không có 5 permission → 403 ở cả 32 endpoint (AC-6, R-2).
- R2 thao tác trên tài liệu của người khác ở review/trash → vẫn 403/404 như R1
  (permission chỉ là điều kiện cần).
- Migration chạy trên DB đã có sẵn 1 cặp `('LANH_DAO','tag.manage')` do R3 thêm:
  không lỗi, `updated_by_sub` cũ giữ nguyên (thêm case vào `RolePermissionSeedTest`
  hoặc unit SQL test: pre-insert trước khi chạy lại statement V35).

## 6. Task breakdown (map tới AC)

- [x] **Task 1** → AC-10: Chạy toàn bộ `mvn -o test` (Docker bật), ghi baseline test
      đỏ có sẵn. Nếu IT đỏ do hạ tầng (ApplicationContext/OpenSearch host): lỗi nhỏ
      (cấu hình test, yml, Testcontainers) thì sửa luôn trong story này và ghi vào
      "Ghi chú implement"; lỗi lớn hoặc cần sửa code ngoài phạm vi thì DỪNG và báo
      người dùng trước khi làm tiếp. AC-10 yêu cầu IT xanh trước QA.
- [x] **Task 2** → AC-1..AC-5, AC-6, AC-7: Tạo `backend/src/main/resources/db/migration/V35__r2_business_parity.sql`
      đúng §2 (grep lại số version). Review SQL: chỉ 5 cặp `LANH_DAO`, không DELETE/UPDATE.
- [x] **Task 3** → AC-1..AC-5: Grep lại `hasRole|hasAnyRole|"can-bo"|Role.CAN_BO|CAN_BO`
      trong `backend/src/main/java` để xác nhận không còn chặn R2 (bỏ qua
      `DocumentOwnershipService`, thuộc story 02). Sửa Javadoc lỗi thời:
      `ingest/IngestController.java:45-52` ("requires CAN_BO", "hasRole('CAN_BO')"),
      `digitization/review/ReviewController.java:61` ("role CAN_BO"),
      `auth/Role.java:15` ("read-only"). Chuyển thành "gate bằng `PERM_<x>`;
      mặc định CAN_BO + LANH_DAO (V35), R3 chỉnh được qua RBAC".
- [x] **Task 4** → AC-6, AC-7, AC-10: Cập nhật `RolePermissionSeedTest.EXPECTED` và
      `RolePermissionFixtures.LANH_DAO`; thêm case biên idempotent (§5.5).
- [x] **Task 5** → AC-1..AC-6, AC-10: Viết `R2PermissionMatrixIT` (§5.3) + test meta
      đếm endpoint.
- [x] **Task 6** → AC-1..AC-6 (R-7): Đảo ngược/tách các test R2→403 trong bảng §5.3,
      mỗi chỗ giữ một assertion R3→403.
- [x] **Task 7** → AC-8: Test hạn mức batch/capacity với uploader R2 (§5.4).
- [x] **Task 8** → AC-9: Test luồng phụ thuộc uploader với R2 (§5.4).
- [x] **Task 9** → AC-10: Chạy lại toàn bộ `mvn -o test`; **toàn bộ test (kể cả IT)
      phải xanh** (AC-10), không chỉ là "không đỏ mới so với baseline"; ghi kết quả
      vào phần "Ghi chú implement" cuối file.

## 7. Observability

- Log: không thêm log mới. Flyway log `Migrating schema ... to version "35 - r2
  business parity"` khi khởi động là dấu hiệu triển khai.
- Kiểm tra sau deploy (stg): `SELECT * FROM role_permission WHERE role_code='LANH_DAO'
  ORDER BY permission_code;` phải có đủ 5 cặp (trừ khi R3 gỡ sau deploy).
- Audit: request 403 của R3 vẫn đi qua `ApiExceptionHandler#recordAccessDeniedAudit`
  như cũ; R2 không còn sinh `role_mismatch` ở 32 endpoint.

## 8. Security checklist

- [x] Validate & sanitize input — không có input mới.
- [x] AuthN/AuthZ: không thêm endpoint; mở rộng quyền đúng 5 cặp tường minh, không
      đụng `QUAN_TRI` (R-2); test R3 403 ở cả 32 endpoint.
- [x] Không log dữ liệu nhạy cảm — không thêm log.
- [ ] Rate limit — giữ nguyên (`nameCheckRateLimiter` áp theo sub, áp dụng cho R2).
- [ ] Review: R2 giờ có thể xoá mềm tài liệu kho chung của chính mình/được chia sẻ
      (`trash.manage`) — đúng phạm vi story, xác nhận với QA.

## 9. Migration & Rollback plan

- Migration: V35 data-only, backward-compatible (code cũ vẫn chạy với bảng có
  thêm bản ghi). Deploy BE trước story 03 (web).
- Rollback: **không** sửa/xoá V35 đã áp (Flyway checksum, xem bài học stg
  2026-09-14). Nếu cần quay lui, thêm migration bù `V<n>__revert_r2_business_parity.sql`:
  `DELETE FROM role_permission WHERE role_code='LANH_DAO' AND permission_code IN
  (5 code) AND updated_by_sub IS NULL;`. `RbacService.updateRolePermissions` chỉ
  `save` dòng **mới thêm** với `updated_by_sub = <sub R3>`, dòng giữ nguyên không bị
  ghi lại, nên `updated_by_sub IS NULL` tách được dòng seed V35 với dòng R3 tự cấp
  (trước deploy, hoặc gỡ rồi cấp lại sau deploy) và giữ lại dòng của R3. Trade-off:
  xoá vô điều kiện thì đơn giản hơn nhưng xoá luôn lựa chọn của R3 (vi phạm tinh thần
  AC-7). Phương án nhanh hơn, không cần deploy: R3 gỡ 5 quyền trên màn RBAC (cache bị
  evict ngay). **Chốt:** không viết sẵn migration bù; mặc định quay lui bằng màn
  RBAC; câu SQL trên chỉ dùng khi thực sự cần.
- Feature flag: không có; RBAC chính là cơ chế bật/tắt.

## 10. Rủi ro kỹ thuật

- Baseline IT chưa xanh (Task 1) có thể chặn AC-10 vì lý do ngoài phạm vi story.
- Test ma trận dễ cho kết quả sai nếu body/path không hợp lệ (400 trước 403) —
  bắt buộc body mẫu hợp lệ cho từng endpoint.
- `RolePermissionFixtures` hardcode quyền nên IT cũ không kiểm DB; chỉ
  `R2PermissionMatrixIT` và `RolePermissionSeedTest` chứng minh V35 thực sự áp.
- Xung đột số version nếu story khác (02/03) hoặc nhánh khác cũng thêm migration
  V35 — grep lại trước merge.
- Môi trường stg/prod: thời điểm hiệu lực phụ thuộc restart; nếu chạy nhiều instance
  rolling, instance cũ có cache tối đa 60s nhưng đã đọc cùng DB nên vẫn nhất quán sau TTL.

## 11. Definition of Done

- [ ] Tất cả AC pass (đối chiếu QA report)
- [ ] Toàn bộ test unit/integration xanh (AC-10; baseline Task 1 chỉ để đối chiếu)
- [ ] Lint/format sạch
- [ ] Không giảm coverage đáng kể
- [ ] Javadoc lỗi thời đã sửa (Task 3)
- [ ] Đã cân nhắc migration & rollback

## 12. Câu hỏi mở

- [x] Có viết sẵn migration bù (§9, điều kiện `updated_by_sub IS NULL`) không?
      Đề xuất: không viết sẵn, ưu tiên R3 gỡ qua màn RBAC; chỉ viết khi thực sự cần.
      → **Chốt:** Không viết sẵn; quay lui bằng màn RBAC, SQL mẫu giữ ở §9 (cập nhật: §9)
- [x] Nếu Task 1 phát hiện IT đỏ do hạ tầng không liên quan, sửa trong story này
      hay tách task riêng? Đề xuất: sửa ở đây nếu nhỏ (cấu hình test), ngược lại báo
      người dùng.
      → **Chốt:** Lỗi nhỏ thì sửa trong story, lớn thì dừng báo người dùng; Task 9/DoD đổi thành toàn bộ IT xanh theo AC-10 (cập nhật: Task 1, Task 9, §11)

## 13. Ghi chú implement

- **Task 1 (baseline `mvn -o test`, 2026-10-01, Docker bật):** 569 test
  methods / 103 lớp chạy. 3 lỗi:
  - `SearchScopeIT`, `SearchIndexOutboxPollerIT`: `ContainerFetchException`
    ("Cannot locate specified Dockerfile") — lỗi hạ tầng nhỏ, do đường dẫn
    build image OpenSearch test vẫn trỏ `../main-workspace/opensearch` (cấu
    trúc cũ) trong khi repo đã chuyển hạ tầng lên gốc (`<repo>/opensearch/`,
    xem memory "Stg deploy infra drift"). **Đã sửa**: đổi 2 chỗ
    `Paths.get(user.dir, "..", "main-workspace", "opensearch")` →
    `Paths.get(user.dir, "..", "opensearch")` trong `SearchScopeIT.java` và
    `SearchIndexOutboxPollerIT.java` (+ sửa Javadoc tương ứng). Đã verify lại
    riêng 2 lớp này: `SearchScopeIT` 16/16 pass, `SearchIndexOutboxPollerIT`
    7/7 pass (các dòng "HikariPool ... Connection refused" sau đó trong log
    chỉ là nhiễu teardown của scheduled poller sau khi context đã đóng,
    không phải test fail).
  - `AccountsServiceTest.setStatusGuardDoesNotActivateWhenUnlockingAnAdmin`
    (unit test, không phải IT): fail cả khi chạy riêng lẻ (không phải
    flaky/thứ tự). `AccountsService.setStatus` hiện luôn throw
    `BadRequestException("Cần nêu lý do khi thay đổi trạng thái tài khoản")`
    nếu `dto.reason()` null/blank, kể cả khi mở khoá (`active`); test lại kỳ
    vọng mở khoá với `reason = null` thành công. Đây là bug/khoảng lệch logic
    nghiệp vụ có sẵn trong baseline, **không liên quan** tới
    permission-grant (module `accounts`, không nằm trong 32 endpoint §5.2),
    và sửa nó đòi hỏi quyết định nghiệp vụ (có cho phép mở khoá không cần lý
    do hay không) ngoài phạm vi spec này.
    → Theo §12 (câu hỏi mở đã chốt) và theo yêu cầu khi giao việc: đây là lỗi
    **lớn/ngoài phạm vi** (sai logic nghiệp vụ module khác, không phải cấu
    hình/yml/Testcontainers), nên đã **DỪNG Task 1 và báo người dùng** trước
    khi chạy tiếp Task 5-9.
    **Người dùng chốt (ngoài phạm vi SPEC-r2-business-parity-01, module
    `accounts`):** sửa `AccountsService.setStatus` để `reason` chỉ bắt buộc
    khi status **không phải** `active` (khi khoá/disable), không bắt buộc khi
    mở khoá - nhất quán với dòng `account.setStatusReason(active ? null :
    dto.reason())` ngay bên dưới (vốn đã bỏ `reason` khi mở khoá). Thay đổi
    tối thiểu: điều kiện `if (dto.reason() == null || ...)` đổi thành
    `if (!active && (dto.reason() == null || ...))`. Đã kiểm tra lại toàn bộ
    `AccountsServiceTest`/`AccountsControllerIT`: không còn test nào gửi
    `status=active, reason=null/blank` kỳ vọng 400, test nhánh khoá/disable
    (luôn kèm `reason`) không đổi hành vi. `AccountsServiceTest` chạy lại:
    xanh toàn bộ.

- **Task 2** (`V35__r2_business_parity.sql`), **Task 3** (Javadoc 3 chỗ:
  `IngestController`, `ReviewController`, `auth/Role`): xong, đúng §2/Task 3,
  không đổi logic.

- **Task 4** (`RolePermissionSeedTest.EXPECTED` + `RolePermissionFixtures.
  LANH_DAO` + case biên idempotent): xong. Thêm test
  `v35ReRunIsNoopWhenR3AlreadyEditedThatPair` - giả lập R3 đã chỉnh
  `(LANH_DAO, tag.manage)` trước, thực thi lại đúng câu SQL của V35 (Flyway
  không re-run được versioned migration thật trong cùng DB), xác nhận
  `updated_by_sub` giữ nguyên + không có dòng trùng. Tự khôi phục
  `updated_by_sub = NULL` ở `finally` vì `postgres` container là `@Container
  static` dùng chung giữa các test method trong lớp (không transactional) -
  tránh ảnh hưởng `v12SeededRolePermissionRowsHaveNullUpdatedBySub` chạy sau.
  `RolePermissionSeedTest`: 7/7 pass.

- **Task 5** (`R2PermissionMatrixIT`): mới, 32 endpoint × 3 role (96 case,
  `@ParameterizedTest`/`@MethodSource`) + 1 test meta đếm `@PreAuthorize`
  bằng reflection trên `RequestMappingHandlerMapping` (khớp đúng 32). Authorities
  dựng từ `SELECT permission_code FROM role_permission WHERE role_code = ?`
  (DB thật sau Flyway V35), không dùng `RolePermissionFixtures`. Mỗi case
  dùng UUID hợp lệ-nhưng-không-tồn-tại ở path + body JSON/multipart hợp lệ
  tối thiểu theo từng DTO's bean validation (tránh 400 che mất 403 ở R3, theo
  đúng lưu ý kỹ thuật §5.3). `R2PermissionMatrixIT`: 97/97 pass.

- **Task 6** (đảo ngược/tách test R2→403 theo bảng §5.3): xong cho cả 6 file
  trong bảng + `TagsControllerIT` (thêm `leaderCanCreateAndAttachTag`, vì
  file này trước đó chưa có test leader→403 riêng cho `tag.manage`).
  `RbacControllerIT`/`SecurityConfigTest`: không đổi (đúng như spec).
  `IngestControllerIT` 27/27, `PersonalToWarehouseIT` 20/20 (tách thành
  `canBoGets202` + `leaderGets202ForTheirOwnPersonalFile` +
  `adminIsStillForbidden`), `NotesControllerIT` 14/14, `TrashControllerIT`
  19/19 (sau cả Task 8), `ReviewControllerIT` 29/29 (sau cả Task 8),
  `TagsControllerIT` 17/17 - tất cả pass.

- **Task 7** (AC-8, `IngestControllerIT`): thêm
  `leaderOpenBatchAboveDeclaredFileCountLimitGetsTheSameErrorAsOfficer` +
  `leaderWarehouseCapacityBlockedGetsTheSameErrorAsOfficer` - cùng mã lỗi
  `BATCH_LIMIT_EXCEEDED`/`WAREHOUSE_CAPACITY_EXCEEDED` như officer. Thêm
  overload `openBatchAs`/`uploadPngAs` tham số hoá theo role (giữ nguyên
  `openBatch`/`uploadPng` gọi qua `officer()` như cũ, không đổi test hiện có).

- **Task 8** (AC-9): thêm 4 test theo đúng §5.4 -
  `ReviewControllerIT#queue_forR2OnlyListsRunsUploadedByTheLeader`,
  `TrashControllerIT#leaderSeesOwnTrashedDocumentAndAnotherOfficerDoesNot`,
  `AuditControllerIT#leaderSeesTagAndNoteEventsOnTheirOwnDocument`,
  `PersonalToWarehouseIT#leaderInitiatedIngestProducesADocumentUploadedByTheLeader`.
  Lưu ý phát sinh khi viết `AuditControllerIT`: `AuditScopeFilter` (V22, có từ
  trước story này) có giới hạn v1 đã ghi chú sẵn trong Javadoc của chính nó -
  chỉ scope được `resource_type='document'`, nên sự kiện `ingest.batch.*`
  (`resource_type='upload_batch'`) **không bao giờ** lọt vào `GET /audit`
  scoped của R2/R3-scoped dù uploader là ai - đây là giới hạn đã biết, không
  phải lỗi của story này, nên test chỉ kiểm `tag.assign`/`note.create` (cả
  hai `resource_type='document'`), không kiểm `ingest.*`. `awaitClosed` trong
  `PersonalToWarehouseIT` được tách thành `awaitClosedAs(role, batchId)` vì
  `GET /ingest/from-personal/{batchId}` scope theo owner - không dùng được
  `officer()` cứng cho batch do leader mở.

- **Task 9 — đính chính (2026-10-01, sau QA):** con số "1004/1004 pass, gồm
  34 IT" ghi trước đó **không chính xác**. `mvn -o test` chỉ chạy unit test
  (surefire); các lớp `*IT` chạy bởi failsafe ở phase
  `integration-test`/`verify` (xem `backend/pom.xml`), nên lệnh đúng cho AC-10
  là `mvn -o verify`.
  - `mvn -o verify` toàn bộ (QA chạy, ~29 phút, Docker bật): unit **570/570
    pass**; IT **414 test / 35 lớp: 1 failure + 1 error**:
    - `TagsControllerIT.assigningToADocumentOutOfScopeReturns404AndRecordsAnAccessDeniedAuditEntry`
      (test không bị story này sửa).
    - `SearchScopeIT`: `ContainerLaunchException` — container OpenSearch test
      không trả lời `/_cluster/health` trong 180s.
  - Chạy lại riêng lẻ (`mvn -o verify -Dtest=NoSuchTest
    -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=<Lớp>`):
    - `TagsControllerIT`: **17/17 pass** → flaky khi chạy cả bộ.
    - `SearchScopeIT`: lần 1 **fail** (container khởi động nhưng không trả lời
      health check, "Unexpected end of file from server", không OOM); lần 2
      **16/16 pass**. Image `aismartstore-opensearch-icu-test` khởi động tay
      healthy sau ~40s. Lỗi chập chờn ở hạ tầng test, chưa rõ nguyên nhân gốc,
      **không** liên quan V35 hay 32 endpoint của story; đề xuất tách task
      riêng để điều tra.
  - Kết luận AC-10: mọi test thuộc phạm vi story xanh; 2 lỗi ở lần chạy toàn
    bộ đều pass khi chạy riêng (flaky, ngoài phạm vi). Chờ QA xác nhận lại.
