# User Story: rbac-group-scope

> File: `docs/02-ba/stories/rbac-group-scope.md` — Owner: BA
> Nguồn kiến trúc: `docs/01-sa/architecture/rbac-group-scope.md`
> Status: Approved

Nền tảng phân quyền hai lớp: FR-07 (RBAC theo 4 vai trò cố định R1/R2/R3/R4)
và FR-08 (phạm vi dữ liệu need-to-know dựa **duy nhất** trên quan hệ
nhóm/thành viên/chia sẻ). Đây là nền tảng scoping mà FR-28/29 sẽ dùng — story
này mô tả hành vi phân quyền, KHÔNG mô tả UI chi tiết quản lý nhóm.

**Diễn giải quan trọng (Quyết định 1):** RBAC là **tĩnh** — "định nghĩa/gán
vai trò" (FR-07) nghĩa là R3 **gán một trong 4 vai trò có sẵn** cho tài khoản,
KHÔNG phải tạo vai trò mới. **Đã chốt cho phiên bản hiện tại, không cần xác
nhận lại với khách hàng.**

---

## Story 1 — Enforce RBAC theo vai trò (FR-07)

**As a** hệ thống / quản trị bảo mật
**I want** mỗi chức năng chỉ cho phép đúng các vai trò được ma trận SRS mục 3 quy định
**So that** người dùng không truy cập được chức năng ngoài quyền của mình

### Acceptance Criteria

```
Given một người dùng có vai trò X gọi một chức năng
When chức năng đó không dành cho vai trò X theo ma trận vai trò SRS mục 3
Then hệ thống từ chối truy cập (403) và ghi nhật ký
```

- [ ] AC-1: Quyền được enforce đúng ma trận 4 vai trò (R1/R2/R3/R4) cho toàn bộ FR liên quan.
- [ ] AC-2: R3 (quản trị) KHÔNG mặc nhiên xem được nội dung nghiệp vụ (tách biệt nhiệm vụ, NFR-04).
- [ ] AC-3: Truy cập bị từ chối được ghi `audit_log` (FR-09).
- [ ] AC-4: Danh mục quyền theo chức năng và ánh xạ role–permission được seed vào bảng `role`/`permission` (chỉ đọc, làm nguồn tham chiếu tập trung); KHÔNG có API tạo/sửa vai trò mới.

---

## Story 2 — R3 gán vai trò có sẵn cho tài khoản (FR-07)

**As a** Quản trị hệ thống (R3)
**I want** gán một trong các vai trò có sẵn (R1/R2/R3/R4) cho tài khoản
**So that** tài khoản có đúng quyền theo nhiệm vụ

### Acceptance Criteria

```
Given tôi là R3 và chọn một tài khoản
When tôi gán cho tài khoản đó một vai trò trong danh sách vai trò có sẵn
Then tài khoản áp dụng quyền theo vai trò mới và thay đổi được ghi nhật ký
```

- [ ] AC-1: Chỉ chọn được vai trò trong tập cố định (R1/R2/R3/R4 qua `AssignableRoles`); KHÔNG có tùy chọn tạo vai trò mới (RBAC tĩnh).
- [ ] AC-2: Chỉ R3 gán được vai trò.
- [ ] AC-3: Thao tác gán vai trò được ghi `audit_log`.

---

## Story 3 — Phạm vi dữ liệu need-to-know theo nhóm (FR-08)

**As a** người dùng nghiệp vụ (R1/R2)
**I want** chỉ thấy được dữ liệu mình tạo/nạp hoặc được chia sẻ qua nhóm mình là thành viên
**So that** dữ liệu chỉ đến đúng người cần biết (need-to-know)

### Acceptance Criteria

```
Given tôi là thành viên của một số nhóm nhất định
When tôi truy cập dữ liệu nghiệp vụ (tìm kiếm, báo cáo, API)
Then tôi chỉ thấy dữ liệu do chính tôi tạo/nạp HOẶC được chia sẻ tới nhóm tôi
     là thành viên; dữ liệu ngoài phạm vi không xuất hiện
```

- [ ] AC-1: Phạm vi dựa **duy nhất** trên quan hệ nhóm/thành viên/chia sẻ (FR-28/29); KHÔNG dùng tiêu chí đơn vị/mức độ mật (đã loại bỏ từ SRS v2.0).
- [ ] AC-2: Dữ liệu **chưa chia sẻ tới nhóm nào** chỉ hiển thị cho chính người tạo/nạp (quy tắc hard-code trong lớp filter, không phải tham số `system-config`).
- [ ] AC-3: Filter phạm vi được áp dụng nhất quán ở **mọi** entrypoint đọc dữ liệu nghiệp vụ: tìm kiếm (FR-22–25), báo cáo (FR-30/31), API (FR-32) — không có ngoại lệ.
- [ ] AC-4: Truy cập dữ liệu ngoài phạm vi bị từ chối và ghi `audit_log`.
- [ ] AC-5: Filter không làm chậm đáng kể tìm kiếm (NFR-01: phản hồi trong vài giây).

---

## Story 4 — Fail-closed khi lỗi tầng scoping (FR-08)

**As a** hệ thống / quản trị bảo mật
**I want** khi tầng phân quyền phạm vi gặp lỗi thì mặc định từ chối truy cập
**So that** một lỗi kỹ thuật không vô tình để lộ dữ liệu ngoài phạm vi

### Acceptance Criteria

```
Given lớp filter phạm vi gặp lỗi khi xác định nhóm/quyền của người dùng
When người dùng truy cập dữ liệu nghiệp vụ
Then hệ thống từ chối truy cập (fail-closed), KHÔNG trả về dữ liệu
```

- [ ] AC-1: Lỗi ở tầng scoping → từ chối truy cập, không fail-open (NFR Availability).
- [ ] AC-2: Sự kiện từ chối do lỗi được ghi log để truy vết.
- [ ] AC-3: Entrypoint mới thêm sau này bắt buộc dùng lớp filter chung — thiếu filter thì không truy cập được dữ liệu (fail-closed by design).

---

## Story 5 — Nền tảng dữ liệu nhóm (Group/Membership/Share) thay thế case-files

**As a** hệ thống
**I want** có mô hình dữ liệu nhóm/thành viên/chia sẻ tài liệu độc lập
**So that** làm nền cho scoping (FR-08) và các tính năng nhóm/chia sẻ (FR-28/29)

### Acceptance Criteria

```
Given hệ thống cần lưu quan hệ nhóm và chia sẻ tài liệu
When khởi tạo schema qua migration
Then tồn tại các bảng group / group_member / document_group_share theo mô hình
     đã chốt, và module casefiles cũ bị gỡ bỏ hoàn toàn
```

- [ ] AC-1: Có bảng `group`, `group_member` (role_in_group: owner|member), `document_group_share` theo Quyết định 2.
- [ ] AC-2: `document_group_share` chỉ lưu **liên kết** tới tài liệu, KHÔNG di chuyển/sao chép tệp (FR-29).
- [ ] AC-3: Module `casefiles` (`CaseFile`, `CaseFileDocument`, bảng liên quan) bị **xóa hoàn toàn**, KHÔNG migrate, KHÔNG giữ song song (Quyết định 2, hệ quả). **Đã chốt: dữ liệu `casefiles` hiện tại toàn bộ là dữ liệu demo — xóa thẳng cùng migration dọn dẹp, không cần bước xử lý/di trú dữ liệu.**
- [ ] AC-4: Có index phù hợp (`group_id`, `user_id`) để đáp ứng quy mô NFR-03.

---

## Ngoài phạm vi (Out of scope)

- UI chi tiết quản lý nhóm và luồng tạo nhóm / chia sẻ tài liệu (toàn văn FR-28/29) — story riêng cho FR-28/29.
- RBAC động (tạo vai trò tùy ý) — vẫn KHÔNG làm. **Cập nhật 2026-08-17:**
  phần "bảng `role_permission` có API ghi" của dòng này **không còn đúng** —
  xem `docs/02-ba/stories/rbac-permission-editing.md` (Quyết định 1b, SA):
  R3 nay được ghi vào `role_permission` để chỉnh quyền của 3 vai trò có sẵn,
  trong danh mục permission vẫn đóng (không tạo permission mới).
- Tiêu chí phạm vi theo đơn vị / mức độ mật — đã loại bỏ từ SRS v2.0.
- Màn hình cấu hình quy tắc hiển thị dữ liệu chưa chia sẻ — không có; quy tắc hard-code trong lớp filter.

## Phụ thuộc / Ghi chú kiến trúc liên quan

- Quyết định 1: RBAC tĩnh, 4 vai trò cố định; bảng `role`/`permission` seed chỉ đọc.
- Quyết định 2: xây mới `Group`/`GroupMember`/`DocumentGroupShare`, xóa hẳn `casefiles`.
- Quyết định 3: filter phạm vi ở tầng query (row-level) qua lớp filter dùng chung (base repository/specification).
- Vai trò do Keycloak quản (nối với `auth-session.md`, `account-lifecycle.md`).
- Nền tảng cho FR-28/29 (nhóm, chia sẻ) và mọi entrypoint đọc dữ liệu (tìm kiếm, báo cáo, API).

## Quyết định đã chốt (trả lời câu hỏi mở trước đó)

- **RBAC tĩnh (FR-07):** chốt cho phiên bản hiện tại — R3 chỉ gán một trong 4
  vai trò có sẵn, KHÔNG tạo vai trò mới. Không cần xác nhận lại với khách hàng.
  **Cập nhật 2026-08-17:** R3 nay được phép chỉnh sửa **quyền của từng vai
  trò có sẵn** (ma trận vai trò × quyền) — xem story riêng
  `docs/02-ba/stories/rbac-permission-editing.md`. Việc **gán vai trò cho
  tài khoản** (nội dung story này) không đổi.
- **Xóa `casefiles` (Story 5 AC-3):** toàn bộ dữ liệu hiện tại là dữ liệu demo →
  xóa thẳng cùng migration dọn dẹp, KHÔNG cần migration/xử lý dữ liệu.
- **Cơ chế row-level filter (Quyết định 3):** cho phép DEV chọn 1 trong 2
  (Spring Data JPA Specification hoặc Querydsl) tùy độ phức tạp của từng query.

## Câu hỏi mở cho DEV

- [ ] Danh sách đầy đủ **mọi entrypoint đọc dữ liệu nghiệp vụ** + kế hoạch bắt
      buộc dùng lớp filter chung (base repository/specification) — **bổ sung sau
      khi các API được hoàn thiện**; giữ làm checklist review để phòng rủi ro
      "bỏ sót entrypoint" (mức Cao).
