# Architecture: rbac-group-scope

> File: `docs/01-sa/architecture/rbac-group-scope.md` — Owner: SA
> Status: Approved

## 1. Bối cảnh / Vấn đề

- Thiết kế phân quyền hai lớp theo
  `docs/dac_ta_yeu_cau_chuc_nang_phi_chuc_nang.adoc` mục 2.13 và mục 3 (ma
  trận vai trò):
  - FR-07: RBAC theo vai trò (R1/R2/R3/R4).
  - FR-08: phân quyền theo phạm vi dữ liệu (need-to-know) — cơ chế phạm vi
    **duy nhất** của hệ thống, dựa hoàn toàn trên quan hệ nhóm/thành
    viên/chia sẻ (FR-28/29), không dùng thêm tiêu chí nào khác (đơn vị, mức
    độ mật... đã bị loại bỏ từ SRS v2.0).
- In-scope: quyết định RBAC tĩnh hay động; thiết kế data model
  Group/Membership/Share; xác định điểm áp dụng filter phạm vi.
- Out-of-scope: UI chi tiết quản lý nhóm (thuộc BA/DEV); toàn văn FR-28/29
  (tạo nhóm, chia sẻ tài liệu) — tài liệu này chỉ thiết kế nền tảng scoping
  mà FR-28/29 sẽ dùng.

## 2. Ràng buộc (Constraints)

- Mạng cách ly hoàn toàn.
- R3 không mặc nhiên xem được nội dung nghiệp vụ (tách biệt nhiệm vụ —
  NFR-04).
- Phải áp dụng nhất quán ở mọi entrypoint đọc dữ liệu nghiệp vụ: tìm kiếm
  (FR-22–FR-25), báo cáo (FR-30/31), API (FR-32).
- NFR-03: quy mô tối đa tham khảo — vài trăm nhóm, ~100 thành viên/nhóm.
- Code hiện tại (`source_v2/backend`) có module `casefiles`
  (`CaseFile`, `CaseFileDocument`) theo mô hình SRS v1.0 (hồ sơ vụ án) —
  SRS v1.1 trở đi đã thay thế hoàn toàn bằng khái niệm "nhóm"; đồng thời
  cơ chế phân quyền phạm vi hiện tại được ghi nhận là "role-only", phần
  scoping kiểu cũ (unit/clearance) đã bị gỡ bỏ và **chưa** có group-scoping
  thay thế.

## 3. NFR (Non-Functional Requirements)

| Loại | Yêu cầu |
|---|---|
| Performance | Filter phạm vi không được làm chậm đáng kể tìm kiếm (NFR-01: phản hồi trong vài giây) |
| Security | RBAC + scoping phải được enforce ở MỌI entrypoint đọc dữ liệu nghiệp vụ, không có ngoại lệ |
| Scalability | Đáp ứng NFR-03 (vài trăm nhóm × ~100 thành viên) |
| Availability | Lỗi ở tầng scoping phải fail-closed (từ chối truy cập) chứ không fail-open |
| Khác | Truy vết — mọi truy cập ngoài phạm vi bị từ chối và ghi nhật ký (FR-09) |

## 4. Phương án & Quyết định (ADR)

### Quyết định 1: RBAC tĩnh hay động

- **Ngữ cảnh:** FR-07 mô tả "định nghĩa vai trò và gán quyền" — có thể hiểu
  là cho phép tạo vai trò tuỳ ý (RBAC động). Nhưng ma trận mục 3 của SRS chỉ
  liệt kê cố định 4 vai trò (R1–R4), và Keycloak hiện tại
  (`realm-export.json`) chỉ định nghĩa 3 role cố định
  (`can-bo`/`lanh-dao`/`quan-tri`) ánh xạ 1-1 với R1/R2/R3.
- **Các phương án xem xét:**
  - A) Giữ RBAC tĩnh: 4 vai trò cố định, quyền gắn cứng theo route/method
    (`@Roles()`/`@PreAuthorize` theo từng vai trò) — đúng như cách code hiện
    tại đang làm.
  - B) RBAC động đầy đủ: bảng `permission`, `role_permission`, cho phép R3
    tạo vai trò mới và gán quyền tuỳ ý qua UI.
- **Quyết định:** A — đã chốt. Giữ RBAC tĩnh.
- **Lý do:** ma trận mục 3 của SRS đã liệt kê đầy đủ và cố định theo 4 vai
  trò cho toàn bộ 35 FR; mô hình Keycloak-role + `@Roles()` per-method hiện
  tại đã đáp ứng đúng toàn bộ ma trận đó. Xây RBAC động là vượt phạm vi hiện
  tại của SRS, tăng effort/rủi ro không cần thiết cho giá trị nghiệp vụ chưa
  được xác nhận là cần thiết.
- **Bổ sung đã chốt:** dù RBAC tĩnh (không có API/UI cho R3 tạo vai trò
  mới), hệ thống vẫn tạo bảng `role` và `permission` trong PostgreSQL để lưu
  **dữ liệu tĩnh** (danh mục quyền theo từng chức năng/FR, ánh xạ
  role–permission cố định đúng theo ma trận mục 3 của SRS) làm nguồn tham
  chiếu tập trung — thay vì chỉ rải rác trong `@Roles()` annotation không có
  nơi tra cứu chung. Hai bảng này chỉ đọc (seed data khi migration), không
  có API ghi/tạo vai trò mới.
- **Hệ quả (trade-off):** "Tạo/sửa vai trò" trong tiêu chí chấp nhận FR-07
  được diễn giải là "quản trị gán một trong các vai trò có sẵn cho tài
  khoản" (đã có qua `CreateAccountDto`/`AssignableRoles`), KHÔNG phải tạo
  role mới — BA cần nêu rõ diễn giải này khi viết user story/AC.

### Quyết định 2: Data model Group / Membership / Share (thay thế case-files)

- **Ngữ cảnh:** module `casefiles` hiện có gắn với khái niệm "hồ sơ vụ án"
  (có `case_number`, `status` OPEN/CLOSED) của SRS v1.0 — không còn phù hợp
  với khái niệm "nhóm" của SRS v1.1+ (nhóm không có số hồ sơ hay trạng thái
  vụ án).
- **Các phương án xem xét:**
  - A) Migrate trực tiếp: đổi tên bảng/entity `case_files` → `groups`, giữ
    cấu trúc many-to-many hiện tại.
  - B) Xây mới hoàn toàn `Group`/`GroupMember`/`DocumentGroupShare` độc lập;
    đánh dấu `casefiles` là legacy, xoá sau khi xác nhận không còn phụ thuộc.
  - C) Giữ song song hai module trong giai đoạn chuyển tiếp.
- **Quyết định:** B — đã chốt.
- **Lý do:** bản chất dữ liệu khác nhau — `case_file` có `case_number`,
  `status` không map 1-1 sang "nhóm" (nhóm không có trạng thái vụ án); ép
  migrate sẽ để lại field thừa/vô nghĩa và gây nhầm lẫn về sau. Xây mới sạch
  giúp đúng đặc tả FR-28/29 hiện hành.
- **Hệ quả (đã chốt):** **bỏ hẳn phần quản lý vụ án** — xoá toàn bộ module
  `casefiles` (`CaseFile`, `CaseFileDocument` và các bảng liên quan), KHÔNG
  migrate, KHÔNG giữ song song. Xây mới hoàn toàn tính năng nhóm
  (`Group`/`GroupMember`/`DocumentGroupShare`) độc lập, không kế thừa gì từ
  `casefiles`. DEV cần kiểm tra dữ liệu `casefiles` hiện có trước khi xoá
  (nếu chỉ là dữ liệu dev/demo thì xoá thẳng cùng migration dọn dẹp).
- **Data model đề xuất:**
  ```
  group (id, name, description, owner_user_id, created_at, updated_at)
  group_member (group_id, user_id, role_in_group[owner|member], joined_at)
  document_group_share (document_id, group_id, shared_by_user_id, shared_at)
  ```
  `document_group_share` chỉ lưu liên kết (đúng theo FR-29: "liên kết đường
  dẫn trên siêu dữ liệu, không di chuyển hoặc sao chép tệp").

### Quyết định 3: Điểm áp dụng filter phạm vi (need-to-know)

- **Ngữ cảnh:** FR-08 yêu cầu áp dụng nhất quán ở tìm kiếm, báo cáo, API.
- **Các phương án xem xét:**
  - A) Row-level filter tại tầng query (mọi query JOIN/WHERE qua
    `group_member` của người dùng hiện tại + điều kiện "do chính mình
    tạo/nạp").
  - B) Application-level post-filter (lấy hết dữ liệu rồi lọc ở service).
  - C) Chặn ở tầng middleware/API gateway.
- **Quyết định:** A — đã chốt. Filter ở tầng query.
- **Lý do:** đảm bảo hiệu năng (NFR-01, tránh tải dư dữ liệu rồi lọc sau);
  giảm rủi ro rò rỉ dữ liệu do lỗi lọc ở tầng ứng dụng (post-filter dễ bị bỏ
  sót khi thêm entrypoint mới).
- **Bổ sung đã chốt (quy tắc mặc định khi tài liệu chưa chia sẻ tới nhóm
  nào):** không có màn hình cấu hình riêng cho R3. Quy tắc "chỉ người
  tạo/nạp thấy được dữ liệu chưa chia sẻ" được **hard-code thẳng trong logic
  của lớp filter dùng chung** (cùng chỗ với điều kiện `group_member`), không
  phải một tham số đọc từ `system-config` (FR-01).
- **Hệ quả (trade-off):** cần rà soát kỹ toàn bộ entrypoint đọc dữ liệu nghiệp
  vụ hiện có và tương lai để đảm bảo không sót — rủi ro cao nếu triển khai
  không đồng bộ; nên có một lớp filter dùng chung (base
  repository/specification) bắt buộc mọi module nghiệp vụ sử dụng.

## 5. Tech stack đề xuất

- Backend: Spring Boot + Spring Data JPA Specification (hoặc Querydsl) cho
  row-level filter dùng chung; PostgreSQL cho bảng `role`/`permission`
  (RBAC tĩnh, seed data) và `group`/`group_member`/`document_group_share`.
- Auth: giữ nguyên Keycloak cho vai trò (RBAC tĩnh).
- Infra/Deploy: không đổi.

## 6. Rủi ro

| Rủi ro | Mức độ | Giảm thiểu |
|---|---|---|
| Bỏ sót entrypoint chưa áp filter phạm vi (vd. API mới thêm sau) → rò rỉ dữ liệu ngoài phạm vi | Cao | Lớp filter dùng chung bắt buộc (base repository/specification) + checklist review bắt buộc khi thêm entrypoint mới |
| Xoá hẳn `casefiles` (đã chốt) làm mất dữ liệu nếu đã phát sinh thật ở môi trường đang chạy | Thấp | **Đã chốt: dữ liệu `casefiles` hiện tại toàn bộ là dữ liệu demo — xoá thẳng cùng migration dọn dẹp, không cần bước kiểm tra/di trú dữ liệu** |
| Hiệu năng JOIN qua `group_member` ở quy mô NFR-03 (vài trăm nhóm × 100 thành viên) | Thấp | Index đúng (`group_id`, `user_id`), đo tải trước khi go-live |
| Hiểu nhầm "định nghĩa vai trò" (FR-07) là RBAC động khi duyệt AC | Thấp | BA nêu rõ diễn giải ở Quyết định 1 khi viết user story. **Đã chốt RBAC tĩnh cho phiên bản hiện tại, không cần xác nhận lại với khách hàng** |

## 7. Câu hỏi mở

_Đã chốt cả 3 câu hỏi mở trước đó (xem Quyết định 1–3 ở mục 4):_
- ~~Xác nhận quyết định "RBAC tĩnh" (Quyết định 1) — có chấp nhận không hỗ
  trợ tạo vai trò tuỳ ý ở giai đoạn này?~~ → **Xác nhận RBAC tĩnh (phương án
  A); vẫn tạo bảng `role`/`permission` để lưu dữ liệu tĩnh làm nguồn tham
  chiếu, không có API tạo vai trò mới. Chốt cho phiên bản hiện tại, không cần
  xác nhận lại với khách hàng.**
- ~~Xử lý dữ liệu `casefiles` hiện có: xoá hẳn hay migrate?~~ → **Bỏ hẳn
  phần quản lý vụ án (xoá module `casefiles`, không migrate), xây mới hoàn
  toàn tính năng nhóm (phương án B, Quyết định 2). Toàn bộ dữ liệu `casefiles`
  hiện tại là dữ liệu demo — xoá thẳng cùng migration dọn dẹp, không cần kiểm
  tra/di trú dữ liệu.**
- ~~"Quy tắc mặc định về hiển thị dữ liệu chưa chia sẻ" — có cần màn hình
  cấu hình riêng không?~~ → **Không cần màn hình cấu hình; quy tắc "chỉ
  người tạo/nạp thấy" được chặn bằng logic hard-code trong lớp filter
  (Quyết định 3), không phải tham số ở `system-config`.**
- ~~Cơ chế row-level filter: Spring Data JPA Specification hay Querydsl?~~ →
  **Cho phép DEV chọn 1 trong 2 tuỳ độ phức tạp của từng query.**

Không còn câu hỏi mở nào cho rbac-group-scope ở bước SA.

## 8. Cập nhật sau khi duyệt (phụ lục)

- **2026-08-17 — Quyết định 1b:** Quyết định 1 ở trên (RBAC tĩnh, bảng
  `role`/`permission`/`role_permission` chỉ đọc, không có API ghi) được
  **mở rộng có kiểm soát**, không đảo ngược, bởi
  `docs/01-sa/architecture/rbac-permission-editing.md`: R3 nay được phép
  chỉnh **ánh xạ** vai trò↔quyền (ghi vào `role_permission`) trong danh mục
  3 vai trò × 15 permission vẫn giữ nguyên **đóng** (không tạo/xoá vai trò
  hay permission mới). Xem file đó để biết chi tiết ADR, và
  `docs/03-dev/specs/rbac-permission-editing.md` để biết cách enforcement
  chuyển từ hard-code sang đọc DB có cache.
