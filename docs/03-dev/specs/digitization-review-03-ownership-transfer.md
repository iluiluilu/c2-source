# Tech Spec: digitization-review-03-ownership-transfer

> File: `docs/03-dev/specs/digitization-review-03-ownership-transfer.md` — Owner: DEV
> Nguồn story: `docs/02-ba/stories/digitization-review-03-ownership-transfer.md`
> Status: Approved

> **Cập nhật 2026-09-08:** toàn bộ phụ thuộc nay đã Approved và implement
> xong — SA `digitization-review.md`, story nguồn, `digitization-review-01-ocr.md`
> (migration `V28` đã seed permission `document.transfer_ownership` như dự
> kiến, không có xung đột). 3 câu hỏi mở của BA (vị trí màn hình, `reason`
> bắt buộc hay không, ràng buộc nhóm A/B) đã được người dùng xác nhận theo
> đúng phương án DEV đề xuất ở mục 7 — không đổi nội dung kỹ thuật. Riêng
> `DocumentOwnershipRow` (mục 3.1) đã cập nhật thêm `sedStatus`/
> `videoSummaryStatus` sau khi rà soát lại với schema thật + spec `-04` đã
> implement (xem ghi chú tại mục 3.1). Sẵn sàng `/dev-implement`.

## 1. Tóm tắt kỹ thuật

Thao tác vận hành cho R3, **không đụng tới nội dung số hoá** (AC-5) — chỉ
đổi 1 cột đã có sẵn: `document.uploader_sub` (`V2__init_warehouse_and_
documents.sql`). Không cần bảng/cột mới. Trọng tâm kỹ thuật là **validate B
hợp lệ** (AC-1: tài khoản đang hoạt động, có vai trò R1, khác A) bằng cách
tái dùng đúng cơ chế đọc Keycloak mà `AccountsService` đang dùng (`kc.
getUser(id)`, danh sách user theo role), và **audit đầy đủ** mỗi lần chuyển
(AC-8), kể cả chuyển liên tiếp A→B→C.

Bổ sung 1 endpoint đọc metadata (không phải nội dung) để R3 tìm tài liệu
theo người nạp hiện tại khi cần xử lý hàng loạt tài liệu tồn đọng của một
người (câu hỏi mở BA #4) — đặt cùng nhóm route quản trị `/admin/...` như
`/admin/rbac`, `/admin/system-config` đã có, **không** cấp thêm quyền
`document.read` cho R3 (giữ đúng ràng buộc "R3 không đọc nội dung").

## 2. Data model

Không có migration mới trong file này. Cột dùng: `document.uploader_sub`
(đã có, `V2`). Permission `document.transfer_ownership` đã seed ở
`digitization-review-01-ocr.md` §2 (`V28`), gán cho role `QUAN_TRI`.

`audit_log` allowlist metadata (theo mẫu `ALLOWED_METADATA_KEYS` đã có ở
`AuditService`) — thêm 1 entry mới:

```java
"document.ownership_transferred", Set.of("fromUploaderSub", "toUploaderSub", "reason")
```

## 3. API contract

Base path `/admin/document-ownership` (theo mẫu `/admin/rbac`,
`/admin/system-config`, `/admin/allowed-file-formats` hiện có), quyền
`@PreAuthorize("hasAuthority('PERM_document.transfer_ownership')")`
(role `QUAN_TRI` duy nhất theo `V28`).

| Method | Endpoint | Request | Response |
|---|---|---|---|
| GET | `/admin/document-ownership` | Query: `uploaderSub` (bắt buộc), `page`, `size` | `Page<DocumentOwnershipRow>` — metadata-only, xem 3.1 |
| POST | `/admin/document-ownership/{documentId}/transfer` | `{ "toUserSub": "string", "reason"?: string }` | `200 { documentId, fromUploaderSub, toUploaderSub, transferredAt }` hoặc `400` (B không hợp lệ — AC-1) hoặc `404` (document không tồn tại) |

### 3.1 `DocumentOwnershipRow` (metadata-only, KHÔNG có nội dung số hoá)

```json
{
  "documentId": "uuid", "originalName": "string",
  "uploaderSub": "string",
  "digitizationStatus": {
    "ocrStatus": "not_applicable|queued|processing|failed|pending_review|in_review|confirmed",
    "sttStatus": "...",
    "sedStatus": "...",
    "videoSummaryStatus": "..."
  }
}
```

> **Chốt 2026-09-08 (review trước khi duyệt):** bổ sung `sedStatus`/
> `videoSummaryStatus` — bảng `document_digitization_status` (`V26`) thật ra
> có **4** cột trạng thái (`ocr_status`, `stt_status`, `sed_status`,
> `video_summary_status`), không phải 2 như bản nháp ban đầu. Sau khi
> `digitization-review-04-sed-summary.md` implement xong (2026-09-08), SED/
> tóm tắt video cũng có trạng thái `pending_review`/`in_review` thật cần R3
> nhìn thấy trước khi quyết định chuyển quyền sở hữu — thiếu 2 trường này sẽ
> khiến R3 ra quyết định thiếu thông tin.

Nguồn dữ liệu: `document` JOIN `document_digitization_status` (read-model
đã có ở `V26`, không JOIN `content_segment`/`digitization_run` trực tiếp —
đủ để R3 biết "còn run đang chờ hay không" mà không lộ nội dung). Lấy cả 4
cột trạng thái của `document_digitization_status`, không chỉ 2 cột đầu.

### 3.2 Request `toUserSub`

Dùng Keycloak `sub` (id tài khoản) — **không** dùng username/email để tránh
nhập nhằng khi 2 tài khoản trùng tên hiển thị, nhất quán với cách
`AccountsService`/`GroupsController` định danh người dùng nội bộ hiện có.

## 4. Luồng xử lý chính

1. **Validate B (AC-1)**: `DocumentOwnershipService.transfer(documentId,
   toUserSub, reason, actor)`:
   - `toUserSub == document.uploaderSub` → 400 "Người nhận trùng người nạp
     hiện tại".
   - `kc.getUser(toUserSub)` không tồn tại hoặc `enabled=false` → 400
     "Tài khoản người nhận không tồn tại hoặc đã bị vô hiệu hoá" (tái dùng
     helper đã có ở `AccountsService`, tách thành method dùng chung nếu
     tiện — không bắt buộc, có thể gọi lại logic tương đương).
   - B không có role `CAN_BO` (kiểm qua danh sách role Keycloak của B,
     cùng cách `AccountsService.list()` build `rolesByUserId`) → 400
     "Người nhận phải có vai trò Cán bộ (R1)".
2. **Cập nhật (trong 1 transaction)**:
   `UPDATE document SET uploader_sub = :toUserSub WHERE id = :documentId`
   — **không** động tới `digitization_run.review_deadline_at`/bất kỳ cột
   nào khác của run (AC-4 — deadline vẫn snapshot theo Quyết định 1 SA,
   không có logic tính lại ở đây).
3. **Audit (cùng transaction)**: `auditService.record(new AuditEntry(...,
   action="document.ownership_transferred", resourceType="document",
   resourceId=documentId, metadata={fromUploaderSub: A, toUploaderSub: B,
   reason}))` — ghi đồng bộ (không dùng `recordAsync`) vì đây là thao tác
   quản trị tần suất thấp, ưu tiên đảm bảo audit không bị mất hơn là độ trễ
   response (khác với các `record` tần suất cao dùng async trong codebase).
4. **Hiệu lực tức thì với B**: không cần thêm bước nào — hàng chờ của B
   (`GET /digitization-review/queue`, file -01 §3) đã lọc theo
   `document.uploader_sub = currentUser.sub` truy vấn trực tiếp, tự động
   thấy tài liệu ngay lần gọi kế tiếp (AC-2); A mất quyền ngay (AC-3) vì cùng
   điều kiện lọc, kể cả gọi thẳng `GET /digitization-review/runs/{id}` bằng
   ID cũng bị 403 do không còn khớp `uploader_sub`.
5. **Nhiều lần chuyển liên tiếp (AC-8)**: không có ràng buộc đặc biệt — mỗi
   lần gọi endpoint là một bản ghi audit độc lập, `fromUploaderSub` luôn là
   giá trị `uploader_sub` **tại thời điểm gọi** (đọc lại từ DB ngay trước
   UPDATE, không cache).
6. **Tài liệu không có run đang chờ (AC-7)**: không có điều kiện chặn nào
   dựa trên trạng thái run — validate ở bước 1 hoàn toàn độc lập với
   `digitization_run`.

## 5. Test plan

**Unit test:**

- B trùng A → 400; B không tồn tại → 400; B bị vô hiệu hoá
  (`enabled=false`) → 400; B không có role `CAN_BO` (ví dụ chỉ có
  `LANH_DAO`) → 400; B hợp lệ → cập nhật thành công.
- `reason` không truyền (null) → vẫn thành công, `metadata.reason` không có
  trong audit (hoặc null) — xác nhận trường này thực sự optional.

**Integration test (Testcontainers Postgres + mock Keycloak admin client
theo mẫu test hiện có của `AccountsServiceTest`):**

- Chuyển A→B thành công → `document.uploader_sub = B`; có đúng 1 dòng
  `audit_log(action='document.ownership_transferred')` với
  `metadata.fromUploaderSub=A, toUploaderSub=B`.
- Chuyển liên tiếp A→B rồi B→C → 2 dòng audit riêng biệt, dòng 2 có
  `fromUploaderSub=B` (không phải A).
- Sau khi chuyển A→B: đăng nhập là B, gọi `GET /digitization-review/queue`
  → thấy tài liệu; đăng nhập là A, gọi `GET /digitization-review/runs/
  {runId}` của tài liệu đó → 403.
- Chuyển quyền sở hữu tài liệu có run đã `confirmed`, không có run
  `pending`/`in_review` nào → vẫn thành công (AC-7).
- Chuyển quyền sở hữu → `digitization_run.review_deadline_at` của các run
  đang chờ **không đổi** (so sánh trước/sau, AC-4).
- R1/R2 gọi `POST /admin/document-ownership/{id}/transfer` → 403 (không có
  permission `document.transfer_ownership`).
- `GET /admin/document-ownership?uploaderSub=A` → chỉ trả metadata
  (`originalName`, `uploaderSub`, `digitizationStatus`), không có trường
  nội dung nào (kiểm bằng cách assert response JSON không chứa khoá
  `text`/`segments`/`currentText`...).
- `digitizationStatus` trong response có đủ 4 trường
  (`ocrStatus`/`sttStatus`/`sedStatus`/`videoSummaryStatus`) — seed một
  document có `sed_status='pending_review'` trong `document_digitization_status`
  rồi assert giá trị đó xuất hiện đúng trong response.

## 6. Task breakdown

- [ ] Task 1: Cập nhật `AuditService.ALLOWED_METADATA_KEYS` thêm
      `document.ownership_transferred` (mục 2).
- [ ] Task 2: `DocumentOwnershipService` (validate B + update + audit, mục
      4 bước 1–3) — package mới
      `com.aismartstore.backend.digitization.review` (dùng chung với file
      -01) hoặc `com.aismartstore.backend.documents` (vì chỉ đụng bảng
      `document`) — đề xuất đặt ở `documents` package vì không phụ thuộc gì
      vào `digitization_run`/`content_segment`, tránh coupling ngược.
- [ ] Task 3: `DocumentOwnershipController`
      (`/admin/document-ownership`, 2 endpoint mục 3).
- [ ] Task 4: `DocumentOwnershipRepository` — query metadata JOIN
      `document_digitization_status` (mục 3.1) cho `GET` danh sách.
- [ ] Task 5: Cập nhật `RolePermissionSeedTest` (đã tự cập nhật ở
      `digitization-review-01-ocr.md` Task 8 cho permission
      `document.transfer_ownership` — xác nhận không tạo trùng ở đây).
- [ ] Task 6: Viết test theo mục 5.
- [ ] Task 7 (Frontend): Màn hình `/admin/document-ownership` (Next.js) —
      form nhập `documentId`/tìm theo `uploaderSub`, chọn B từ danh sách
      tài khoản R1 đang hoạt động (tái dùng API `GET /accounts` hiện có,
      lọc `roles` chứa `CAN_BO` và `status` active ở tầng FE hoặc thêm query
      param lọc ở `AccountsController` nếu cần).

## 7. Rủi ro kỹ thuật

- **Không có ràng buộc cùng phạm vi nhóm giữa A và B** (câu hỏi mở BA #3) —
  quyết định của DEV: **không** thêm ràng buộc, R3 được chọn tự do bất kỳ
  R1 đang hoạt động nào, đúng tinh thần "R3 là người vận hành toàn hệ
  thống, không bị giới hạn bởi `rbac-group-scope.md`" (AC-6 xác nhận thao
  tác này không đụng lớp filter phạm vi nhóm). **Đã được người dùng xác
  nhận 2026-09-08.** Nếu nghiệp vụ sau này cần ràng buộc này, đây là một
  điều kiện validate thêm ở bước 1 (mục 4), không ảnh hưởng schema.
- **Không bulk transfer** (ngoài phạm vi, theo story) — nếu một người nghỉ
  việc có hàng trăm tài liệu tồn đọng, R3 phải gọi lần lượt qua
  `GET /admin/document-ownership?uploaderSub=A` rồi transfer từng
  `documentId`; UI (Task 7) nên hỗ trợ chọn nhiều dòng + gọi API tuần tự
  (không phải 1 API bulk thật) để giảm thao tác thủ công mà không cần thiết
  kế lại contract.
- **`reason` không bắt buộc** (câu hỏi mở BA #2) — quyết định DEV: để
  optional, tối đa 500 ký tự (validate ở DTO); nếu về sau nghiệp vụ yêu cầu
  bắt buộc, đổi `@NotBlank` là thay đổi nhỏ, không ảnh hưởng schema/audit
  key đã đăng ký. **Đã được người dùng xác nhận 2026-09-08.**
- **Đọc role/trạng thái Keycloak của B đồng bộ trong request** (mục 4 bước
  1) — cùng rủi ro độ trễ Keycloak admin API như `AccountsService` đã chấp
  nhận từ trước (không phải rủi ro mới do spec này tạo ra).
