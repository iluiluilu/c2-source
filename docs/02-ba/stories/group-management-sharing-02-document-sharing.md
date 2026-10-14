# User Story: group-management-sharing-02-document-sharing

> File: `docs/02-ba/stories/group-management-sharing-02-document-sharing.md` — Owner: BA
> Nguồn kiến trúc: `docs/01-sa/architecture/group-management-sharing.md`
> Status: Approved

Phần **FR-29 còn thiếu** (chia sẻ hàng loạt, tra cứu tài liệu theo nhóm,
thông báo thành viên), xây trên nền `DocumentGroupShare` đã có ở
`rbac-group-scope` (Approved, đã implement). Vòng đời nhóm/thành viên nằm ở
`docs/02-ba/stories/group-management-sharing-01-group-lifecycle.md`.

## ⚠️ Đối chiếu với code hiện tại (bắt buộc đọc trước khi làm DEV spec)

`DocumentSharesController`/`GroupsService` (`backend/src/main/java/.../groups/`)
**đã có**:

| Đã implement | Ghi chú |
|---|---|
| `POST /documents/{id}/shares` — `ShareDocumentDto{groupId}` | Chỉ **1 tài liệu × 1 nhóm/request** — KHÔNG hỗ trợ chia sẻ nhiều tài liệu một lần (Story 1) |
| `DELETE /documents/{id}/shares/{groupId}` — gỡ chia sẻ | Quyền hiện tại: bất kỳ ai `requireVisible` được tài liệu đều gỡ được — **chưa** phân biệt "owner nhóm gỡ mọi share" vs "member chỉ gỡ share do chính mình tạo hoặc tài liệu do chính mình nạp" (Quyết định 2 SA, mismatch cần sửa — Story 3) |
| `GET /documents/{id}/shares` — liệt kê **nhóm nào đang thấy 1 tài liệu** | Chiều dữ liệu ngược với Story 2 (liệt kê **tài liệu của 1 nhóm**) — endpoint đó chưa tồn tại |
| Chia sẻ chỉ tạo bản ghi `document_group_share` (liên kết siêu dữ liệu) | Đã khớp FR-29 "không di chuyển/sao chép tệp" — không cần sửa |
| Audit `document.share`/`document.unshare` | Đã ghi, không cần sửa |

**Chưa implement (xác nhận bằng grep toàn `backend/src/main`, 0 kết quả)**:
chia sẻ nhiều tài liệu/request, `GET /groups/{id}/documents`, phân biệt
quyền gỡ chia sẻ theo owner/member, bảng `notification` và mọi cơ chế thông
báo, panel "Chia sẻ" trên UI tài liệu, badge thông báo trên UI.

---

## Story 1 — Chia sẻ nhiều tài liệu vào một nhóm cùng lúc (FR-29)

**As a** thành viên nhóm (owner hoặc member)
**I want** chọn nhiều tài liệu và chia sẻ cùng lúc vào một nhóm
**So that** tôi không phải lặp lại thao tác chia sẻ từng tài liệu một

### Acceptance Criteria

```
Given tôi thuộc một nhóm và đang thấy được N tài liệu trong kho dùng chung
When tôi chọn nhiều tài liệu và chia sẻ vào nhóm đó trong một lượt
Then mọi tài liệu tôi đang thấy được đều được liên kết tới nhóm; tài liệu
     nào tôi không thấy được thì bị từ chối riêng, không làm hỏng cả lượt
```

- [ ] AC-1: `POST /documents/shares` (endpoint mới, mức container "nhiều tài
      liệu") nhận `{ groupId, documentIds[] }`, tối đa **100 tài liệu/request**
      (NFR Performance SA).
- [ ] AC-2: Mỗi `documentId` được kiểm tra `requireVisible` **độc lập** — một
      tài liệu ngoài phạm vi/không tồn tại không làm rollback các tài liệu
      hợp lệ khác trong cùng request (giống nguyên tắc "một tệp lỗi không
      hỏng cả lượt" đã dùng ở FR-11/FR-14).
- [ ] AC-3: Tài liệu đã từng chia sẻ vào nhóm đó (trùng) → bỏ qua êm (không
      lỗi, không tạo bản ghi trùng), không làm fail cả request.
- [ ] AC-4: Response trả về danh sách kết quả theo từng `documentId`: thành
      công / đã chia sẻ trước đó / bị từ chối (kèm lý do).
- [ ] AC-5: Giới hạn ≤ 50 nhóm/tài liệu (đếm tổng số nhóm mà 1 tài liệu đang
      được chia sẻ tới, kể cả từ các lượt chia sẻ đơn lẻ trước đó) — vượt →
      tài liệu đó bị từ chối riêng trong response (không fail cả request).
- [ ] AC-6: Một bản ghi audit `document.share` cho **mỗi** tài liệu chia sẻ
      thành công (giữ nguyên granularity audit hiện có ở API chia sẻ đơn
      lẻ, không gộp).
- [ ] AC-7: `POST /documents/{id}/shares` (API đơn lẻ hiện có) **giữ nguyên**,
      không breaking change — API mới là bổ sung.

---

## Story 2 — Liệt kê tài liệu của một nhóm (FR-29)

**As a** thành viên nhóm
**I want** xem danh sách tài liệu đã được chia sẻ vào nhóm của tôi
**So that** tôi tra cứu được tài liệu chung của nhóm mà không cần biết
trước ID từng tài liệu

### Acceptance Criteria

```
Given tôi là thành viên của một nhóm có tài liệu đã được chia sẻ vào
When tôi gọi danh sách tài liệu của nhóm đó
Then tôi thấy đúng các tài liệu đang chia sẻ tới nhóm, có phân trang, và
     không thấy được nếu tôi không phải thành viên
```

- [ ] AC-1: `GET /groups/{id}/documents` — chỉ thành viên của nhóm gọi được;
      không phải thành viên → 403/404 (theo quy tắc "không lộ tồn tại" đã
      dùng nhất quán trong `DocumentScopeGuard`).
- [ ] AC-2: Phân trang 50 tài liệu/trang; p95 ≤ 800 ms với 10.000 tài liệu
      trong nhóm (NFR Performance SA).
- [ ] AC-3: Tài liệu đã bị **soft-delete** (thùng rác, `delete-restore-trash`)
      → ẩn khỏi danh sách này nhưng **giữ** bản ghi `document_group_share`
      (đúng contract đã ghi ở SA mục "Out-of-scope"); tài liệu bị **hard
      delete** → không còn xuất hiện (share đã bị xoá theo).
- [ ] AC-4: Mỗi dòng kết quả kèm `sharedBySub`/`sharedAt` — mọi thành viên
      trong nhóm đều xem được ai đã chia sẻ tài liệu đó (đã chốt với khách
      hàng, xem SA mục 7).
- [ ] AC-5: Nhóm đã bị xoá (`deleted_at`, xem story lifecycle) → 404 cho mọi
      người kể cả cựu thành viên.

---

## Story 3 — Gỡ chia sẻ đúng ma trận quyền owner/member (FR-29)

**As a** chủ nhóm hoặc người nạp tài liệu
**I want** gỡ một liên kết chia sẻ tài liệu-nhóm
**So that** tài liệu không còn hiển thị cho nhóm đó nữa, đúng phạm vi quyền
của tôi

### Acceptance Criteria

```
Given một tài liệu đang được chia sẻ tới một nhóm
When tôi gỡ chia sẻ đó
Then hệ thống chỉ cho phép nếu tôi là owner của nhóm, hoặc là người tạo
     chính share đó, hoặc là người đã nạp tài liệu đó; các trường hợp khác
     bị từ chối
```

- [ ] AC-1: `owner` của nhóm gỡ được **mọi** share của nhóm mình (Quyết định
      2 SA) — hành vi hiện có đã đủ rộng cho case này, không cần sửa thêm.
- [ ] AC-2: `member` (không phải owner) chỉ gỡ được share mà **chính mình**
      là `shared_by_sub`, **hoặc** tài liệu mà chính mình là `uploaderSub`
      — đây là **thay đổi bắt buộc** so với hành vi hiện tại (hiện tại bất
      kỳ ai thấy được tài liệu đều gỡ được share bất kỳ của tài liệu đó,
      không phân biệt owner/member).
- [ ] AC-3: `member` gỡ share không thuộc 2 điều kiện AC-2 → 403 + ghi audit
      thất bại do thiếu quyền.
- [ ] AC-4: Gỡ chia sẻ có hiệu lực **ngay trong cùng transaction** ở tầng
      DB (0 s trễ) — hành vi hiện có đã đúng, giữ nguyên.
- [ ] AC-5: Ghi audit `document.unshare` (thành công và thất bại).

---

## Story 4 — Thông báo thành viên khi có chia sẻ/thay đổi thành viên (FR-29)

**As a** thành viên nhóm
**I want** nhận thông báo trong ứng dụng khi có tài liệu mới chia sẻ vào
nhóm của tôi, hoặc khi tôi được thêm/gỡ khỏi một nhóm
**So that** tôi biết có tài liệu/thay đổi mới mà không phải tự kiểm tra
định kỳ

### Acceptance Criteria

```
Given tôi là thành viên của một nhóm
When có tài liệu được chia sẻ vào nhóm đó, hoặc tôi được thêm/gỡ khỏi một
     nhóm
Then tôi thấy thông báo trong vòng tối đa 60 giây qua giao diện web (không
     email/SMS/push ngoài — mạng cách ly)
```

- [ ] AC-1: Bảng `notification (id, recipient_sub, type, payload_json,
      created_at, read_at)` — 3 loại sự kiện: `group.member_added`,
      `group.member_removed`, `document.shared_to_group` (Quyết định 3 SA).
- [ ] AC-2: Web polling **60 giây**; không WebSocket/SSE (không thêm hạ
      tầng — Quyết định 3 SA).
- [ ] AC-3: Chia sẻ hàng loạt (Story 1) tạo **1** thông báo/nhóm/lượt chia
      sẻ (gộp), không phải 1 thông báo/tài liệu.
- [ ] AC-4: Ghi thông báo là **best-effort, ngoài transaction nghiệp vụ** —
      lỗi ghi thông báo **không** làm rollback thao tác chia sẻ/thêm/gỡ
      thành viên (NFR Availability SA).
- [ ] AC-5: Thông báo cũ hơn 90 ngày bị dọn (TTL job).
- [ ] AC-6: Đánh dấu đã đọc (`read_at`) khi người dùng xem — endpoint riêng,
      không bắt buộc phải đọc hết mới thấy thông báo mới.

---

## Story 5 — Panel "Chia sẻ" và badge thông báo trên UI (FR-29, UI)

**As a** người dùng R1/R2 đang xem một tài liệu trong kho dùng chung
**I want** một panel để chia sẻ tài liệu đó vào nhóm và xem nhóm nào đang
thấy tài liệu
**So that** tôi thao tác chia sẻ ngay tại trang tài liệu, không cần rời
sang trang khác

### Acceptance Criteria

```
Given tôi đang xem một tài liệu tôi có quyền thấy trong web/src/app/documents
When tôi mở panel "Chia sẻ"
Then tôi thấy danh sách nhóm đang được chia sẻ (kèm người chia sẻ), chọn
     thêm nhóm mới để chia sẻ, và gỡ chia sẻ theo đúng quyền của mình
```

- [ ] AC-1: Panel hiển thị "N nhóm đang thấy tài liệu này" trước khi xác
      nhận chia sẻ mới (giảm thiểu rủi ro "member vô tình phơi bày tài liệu
      nhạy cảm" — Quyết định 2 SA, mục Rủi ro).
- [ ] AC-2: Chọn nhóm đích từ danh sách nhóm tôi là thành viên; hỗ trợ chọn
      nhiều nhóm cùng lúc cho 1 tài liệu (ngược chiều với Story 1 — 1 tài
      liệu × nhiều nhóm, dùng lại API đơn lẻ hiện có lặp theo từng nhóm hoặc
      API mới tuỳ DEV).
- [ ] AC-3: Nút gỡ chia sẻ chỉ hiện cho các dòng tôi có quyền gỡ theo Story
      3 (ẩn trên UI, không dựa vào lỗi API để ẩn).
- [ ] AC-4: Badge thông báo (Story 4) trên `AppShell` — số thông báo chưa
      đọc, polling 60 s, click vào để xem danh sách và đánh dấu đã đọc.
- [ ] AC-5: Trang `/groups/{id}` (story lifecycle, Story 7) có tab/link
      "Tài liệu của nhóm" gọi `GET /groups/{id}/documents` (Story 2).

---

## Story 6 — Chia sẻ hàng loạt tài liệu từ trang danh sách kho tài liệu (FR-29, UI)

**As a** người dùng R1/R2 (Cán bộ hoặc Lãnh đạo) đang xem trang danh sách
kho tài liệu (`/documents`)
**I want** chọn nhiều tài liệu cùng lúc và chia sẻ tất cả vào 1 nhóm
**So that** tôi không phải mở từng tài liệu để chia sẻ lần lượt

> Bổ sung sau khi rà soát (2026-09-16): Story 1 đã xây API `POST
> /documents/shares` (nhiều tài liệu × 1 nhóm) nhưng chưa từng có UI nào gọi
> tới (xác nhận bằng grep toàn `web/src`, 0 kết quả) — Story 5 chỉ làm
> chiều ngược lại (1 tài liệu × nhiều nhóm, ở trang chi tiết). Story này
> lấp khoảng trống đó, không cần API mới.

### Acceptance Criteria

```
Given tôi đang ở trang danh sách kho tài liệu và có quyền document.share
When tôi chọn nhiều tài liệu rồi chọn 1 nhóm để chia sẻ
Then tất cả tài liệu tôi thấy được đều được liên kết tới nhóm đó trong 1
     lượt, tôi thấy tóm tắt kết quả theo từng trạng thái
```

- [ ] AC-1: Tái dùng cơ chế chọn nhiều dòng (checkbox) đã có sẵn cho tính
      năng "xóa hàng loạt" (`delete-restore-trash-01`) trên cùng trang
      `/documents` — khi có ≥ 1 tài liệu được chọn, hiện thêm hành động
      "Chia sẻ vào nhóm" cạnh hành động "Xóa đã chọn" hiện có.
- [ ] AC-2: Hành động này hiển thị cho cả **Cán bộ và Lãnh đạo** — khớp
      quyền `document.share` đã seed cho cả 2 role (`V12__init_groups.sql`),
      không giới hạn theo `canWrite`/CAN_BO-only như panel chia sẻ đơn lẻ ở
      trang chi tiết tài liệu (lưu ý: panel đơn lẻ đó đang hẹp hơn quyền
      backend cho phép — không thuộc phạm vi story này, không sửa).
- [ ] AC-3: Danh sách nhóm để chọn giới hạn ở các nhóm mà người dùng hiện là
      thành viên (khớp hành vi dropdown ở panel chia sẻ đơn lẻ hiện có,
      `GET /groups`) — không hiển thị toàn bộ nhóm hệ thống.
- [ ] AC-4: Trước khi gửi yêu cầu, hiện dialog xác nhận đơn giản dạng "Xác
      nhận chia sẻ N tài liệu vào nhóm X?" (tái dùng `useConfirm` đã có,
      không cần preview chi tiết từng tài liệu/nhóm đã share trước đó).
- [ ] AC-5: Khi xác nhận, gọi `POST /documents/shares` (Story 1, đã có) với
      toàn bộ `documentId` đã chọn + `groupId` đã chọn trong 1 request.
- [ ] AC-6: Nếu số tài liệu đã chọn > 100 (giới hạn của API Story 1), chặn ở
      phía client trước khi gọi API (disable hành động hoặc báo lỗi rõ
      ràng) — không gửi request để rồi nhận lỗi 400 chung chung.
- [ ] AC-7: Sau khi API trả về, hiển thị tóm tắt theo từng trạng thái (dựa
      vào mảng `results` có sẵn trong response): số tài liệu chia sẻ thành
      công, số đã từng chia sẻ trước đó (bỏ qua êm, không phải lỗi), số bị
      từ chối kèm lý do — không chỉ hiện 1 thông báo chung "thành công" nếu
      có tài liệu bị từ chối.
- [ ] AC-8: Sau khi xử lý xong (dù thành công một phần), bỏ chọn toàn bộ tài
      liệu đã chọn — khớp hành vi hiện có của "xóa hàng loạt" sau khi xóa
      xong (`setSelectedIds` reset).

---

## Ngoài phạm vi (Out of scope)

- Vòng đời nhóm/thành viên — `group-management-sharing-01-group-lifecycle.md`.
- Cập nhật chỉ mục tìm kiếm khi chia sẻ/gỡ chia sẻ (field `group_ids[]`,
  Quyết định 8 SA) — **không áp dụng được ở phiên bản này**: module tìm
  kiếm (`search-fulltext`, `ai-search-infra`) chưa tồn tại trong code (xác
  nhận bằng grep, 0 kết quả); đây là **điểm giao tiếp (contract)**, khi 2
  slug đó implement thì phải tự đọc `document_group_share` để cập nhật
  index đồng bộ ở nhánh gỡ chia sẻ — không thuộc phạm vi story này.
- Báo cáo/thống kê theo nhóm (FR-30) — slug `export-reporting`, module
  hiện chưa tồn tại trong code.
- Chia sẻ trực tiếp cho cá nhân (không qua nhóm) — chốt không làm.

## Phụ thuộc / Ghi chú kiến trúc liên quan

- `docs/01-sa/architecture/group-management-sharing.md`: Quyết định 1 (mô
  hình share theo nhóm), Quyết định 2 (ma trận quyền gỡ chia sẻ), Quyết
  định 3 (thông báo in-app), Quyết định 6 (không đổi quota khi chia sẻ),
  Quyết định 8 (contract với search).
- `docs/01-sa/architecture/rbac-group-scope.md`: `DocumentScopeGuard.
  requireVisible`, `BusinessDataScope` — tái dùng nguyên vẹn cho AC-1 Story 1
  và mọi kiểm tra "đang thấy được".
- `docs/01-sa/architecture/delete-restore-trash.md`: quy tắc soft-delete
  giữ share, hard-delete xoá share (Story 2 AC-3).
- `docs/01-sa/architecture/personal-storage-quota.md`: chia sẻ không đổi
  quota người nạp (Quyết định 6 SA) — không có AC riêng vì không cần code
  mới, chỉ là ràng buộc "không được đổi" cần DEV không vô tình vi phạm.

## Câu hỏi mở cho DEV

- [x] Story 1 AC-1: xác nhận endpoint mức container là `POST
      /documents/shares` (đề xuất) hay `POST /groups/{id}/documents/shares`
      — chọn theo cách nào khớp REST convention hiện có trong
      `DocumentSharesController`/`GroupsController` hơn.
      **Đã chốt (2026-09-02): `POST /documents/shares`** — cùng nhóm route
      với `/documents/{id}/shares` hiện có; xem
      `docs/03-dev/specs/group-management-sharing-02-document-sharing.md`
      mục 3.1.
- [x] Story 5 AC-2: xác nhận UI gọi API hàng loạt (Story 1, nhiều tài liệu ×
      1 nhóm) hay lặp API đơn lẻ hiện có (1 tài liệu × nhiều nhóm) khi
      người dùng chọn nhiều nhóm cho 1 tài liệu — cả 2 hướng đều hợp lệ về
      dữ liệu, DEV chọn theo cái nào đơn giản hơn để implement UI.
      **Đã chốt (2026-09-02): lặp API đơn lẻ hiện có** (`POST
      /documents/{id}/shares`, gọi theo từng nhóm được chọn) — không cần
      API mới cho chiều "1 tài liệu × nhiều nhóm"; xem
      `docs/03-dev/specs/group-management-sharing-02-document-sharing.md`
      mục 4.6 (Frontend), điểm 1.
- [x] Story 6 AC-2: quyền hiển thị hành động "Chia sẻ vào nhóm" hàng loạt —
      chỉ Cán bộ (khớp `canWrite` của panel đơn lẻ) hay cả Cán bộ và Lãnh
      đạo?
      **Đã chốt (2026-09-16): cả Cán bộ và Lãnh đạo** — khớp
      `role_permission` đã seed thực tế (`document.share` cấp cho cả 2 role
      ở `V12__init_groups.sql`); panel đơn lẻ ở trang chi tiết đang hẹp hơn
      quyền backend cho phép nhưng không thuộc phạm vi story này.
- [x] Story 6 AC-4: có cần preview chi tiết (giống AC-1 Story 5: "N nhóm đang
      thấy tài liệu này") trước khi xác nhận chia sẻ hàng loạt không?
      **Đã chốt (2026-09-16): không** — chỉ cần dialog xác nhận đơn giản
      (số lượng tài liệu + tên nhóm), không gọi thêm API kiểm tra từng tài
      liệu trước, giữ độ phức tạp thấp.
