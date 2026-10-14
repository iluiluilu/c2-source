# User Story: r2-business-parity-01-permission-grant

> File: `docs/02-ba/stories/r2-business-parity-01-permission-grant.md` — Owner: BA
> Nguồn kiến trúc: `docs/01-sa/architecture/r2-business-parity.md` (ARCH-r2-business-parity, ADR-1)
> Status: Approved
> Story ID: `STORY-r2-business-parity-01`
> Priority: Must
> Estimate: M

## User Story

**As a** Lãnh đạo (R2 — `LANH_DAO`)
**I want** được cấp đầy đủ 5 quyền nghiệp vụ hiện chỉ Cán bộ (R1) có — nạp tài
liệu (kể cả nạp từ kho cá nhân), quản lý tag, ghi chú, xử lý hàng chờ số hoá,
quản lý thùng rác
**So that** tôi có thể thực hiện toàn bộ nghiệp vụ như R1 mà không bị chặn bởi
giới hạn vai trò cũ, trong khi Quản trị (R3) vẫn có thể bật/tắt từng quyền qua
màn RBAC

## Acceptance Criteria

- [ ] **AC-1:** Given tài khoản R2 đang hoạt động và mặc định chưa bị R3 chỉnh
      sửa RBAC, When gọi API nạp tài liệu (`document.upload`), bao gồm nạp từ
      kho cá nhân (`/ingest/from-personal`), Then request thành công (không
      trả 403), hành vi giống hệt R1.
- [ ] **AC-2:** Given tài khoản R2, When gắn/sửa/xoá tag trên tài liệu
      (`tag.manage`), Then thao tác thành công như R1.
- [ ] **AC-3:** Given tài khoản R2, When thêm/sửa ghi chú tài liệu
      (`note.write`), Then thao tác thành công như R1.
- [ ] **AC-4:** Given tài khoản R2, When duyệt/xử lý mục trong hàng chờ số hoá
      (`digitization_review.manage`), Then thao tác thành công như R1.
- [ ] **AC-5:** Given tài khoản R2, When thao tác thùng rác trong phạm vi
      được phép (xem/khôi phục/xoá — `trash.manage`), Then thao tác thành công
      như R1.
- [ ] **AC-6:** Given tài khoản R3 (Quản trị) không nằm trong danh sách được
      cấp 5 quyền trên, When gọi các API ở AC-1..AC-5, Then vẫn bị chặn 403
      đúng như hành vi trước khi thay đổi (không có leo thang đặc quyền ngoài
      ý định).
- [ ] **AC-7:** Given môi trường (vd. stg/prod) mà R3 đã từng chỉnh
      `role_permission` khác với mặc định (vd. gỡ bớt một trong 5 quyền của
      `LANH_DAO`), When migration cấp quyền chạy lại hoặc chạy lần đầu, Then
      không ghi đè/ xoá lựa chọn hiện tại của R3 — chỉ bổ sung các bản ghi
      chưa tồn tại (idempotent).
- [ ] **AC-8:** Given R2 nạp tài liệu vào kho chung, When kiểm tra giới hạn
      số lượng file/dung lượng theo lô (`ingest.max_files_per_batch`,
      `max_batch_size_bytes`, dung lượng kho chung), Then áp dụng đúng cơ chế
      hạn mức hiện có, không phân biệt vai trò (có test riêng xác nhận với
      uploader là R2).
- [ ] **AC-9:** Given R2 đã nạp tài liệu/duyệt hàng chờ/thao tác thùng rác,
      When kiểm tra các luồng phụ thuộc uploader (thông báo, audit scoped,
      hiển thị trong hàng chờ/thùng rác của chính R2), Then các luồng này hoạt
      động đúng với uploader là R2, không còn giả định ngầm uploader luôn là
      R1.
- [ ] **AC-10:** Given bộ test BE, When chạy test phân quyền cho 5 permission,
      Then có ma trận đầy đủ **mọi** endpoint gate bởi `document.upload`,
      `tag.manage`, `note.write`, `digitization_review.manage`, `trash.manage`
      × 3 vai trò (R1 pass — giữ nguyên; R2 pass — mới; R3 403 — giữ nguyên),
      cho phép dùng test tham số hoá. DEV liệt kê ma trận trong spec để QA đối
      chiếu, và bộ integration test phải xanh trước khi sang QA.

## Ngoài phạm vi (Out of scope)

- Thêm vai trò mới, permission mới ngoài 5 quyền đã chốt.
- Sửa cơ chế/ phạm vi `audit.read_scoped` (ĐÃ CHỐT ở ARCH §1: không sửa; sự
  kiện trên tài liệu của R2 tự hiện theo phạm vi hiện có; sự kiện có
  `resource_type` khác `document` vẫn giữ giới hạn v1).
- Thay đổi quota lưu trữ theo vai trò — R2 dùng đúng hạn mức hệ thống hiện có.
- Cho R2 thực hiện chuyển quyền sở hữu tài liệu (xem story
  `r2-business-parity-02-ownership-transfer`).
- Gate giao diện web theo quyền mới (xem story
  `r2-business-parity-03-web-permission-gating`).

## Phụ thuộc (Dependencies)

- Không phụ thuộc story khác để triển khai (độc lập, có thể làm trước).
- Story `r2-business-parity-03-web-permission-gating` phụ thuộc vào kết quả
  story này (5 permission phải tồn tại trong `role_permission` trước khi FE
  gate theo permission có ý nghĩa).
- Tham chiếu quyết định kiến trúc: ADR-1 (migration `V35`, data-only, không
  sửa `@PreAuthorize`); Rủi ro R-1, R-2, R-5, R-6, R-7 trong ARCH-r2-business-parity.
- Kết quả rà R-1 (grep BE ngày 2026-10-01): mọi endpoint của 5 permission đều
  đã gate bằng `hasAuthority('PERM_...')`; `PersonalIngestWorker`/
  `PersonalToWarehouseService` không kiểm role; các chỗ
  `hasAnyRole('CAN_BO','LANH_DAO')` (notifications, user directory) đã gồm R2.
  Vì vậy AC-1..AC-5 chỉ cần cấp permission (ADR-1). Còn Javadoc lỗi thời ghi
  "requires CAN_BO" ở `IngestController.java:49`, `ReviewController.java:61`:
  DEV sửa khi implement và grep lại để xác nhận. (`"can-bo"` trong
  `DocumentOwnershipService` thuộc story 02.)

## Câu hỏi mở cho DEV

- [x] Trước khi viết spec, rà soát toàn bộ `hasRole/hasAnyRole/"can-bo"`
      (backend) và các điểm còn kiểm role cứng trong service/worker (đặc biệt
      `PersonalIngestWorker`/`PersonalToWarehouseService` theo R-1) để xác
      nhận không còn chỗ nào chặn R2 ngoài 5 permission đã cấp — nếu tìm thấy,
      báo lại BA/SA trước khi coi AC-1..AC-5 là đủ.
      → **Chốt:** Không còn chỗ nào chặn R2 bằng role cứng; chỉ cần sửa Javadoc lỗi thời (cập nhật: Phụ thuộc)
- [x] Xác nhận danh sách test BE hiện có (và cần bổ sung) theo ma trận
      endpoint × vai trò (R1 giữ pass, R2 pass mới, R3 vẫn 403) cho từng AC-1
      đến AC-5 — liệt kê trong spec để QA đối chiếu.
      → **Chốt:** Ma trận đầy đủ mọi endpoint × R1/R2/R3, test tham số hoá, IT phải xanh trước QA (cập nhật: AC-10)
