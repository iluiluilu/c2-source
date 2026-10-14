# Architecture: account-lifecycle

> File: `docs/01-sa/architecture/account-lifecycle.md` — Owner: SA
> Status: Approved

## 1. Bối cảnh / Vấn đề

- FR-04 (`docs/dac_ta_yeu_cau_chuc_nang_phi_chuc_nang.adoc` mục 2.13): R3
  quản lý vòng đời tài khoản người dùng — tạo, sửa, khoá, mở khoá; không xoá
  cứng tài khoản có lịch sử thao tác (chỉ khoá/vô hiệu hoá để giữ truy vết).
- In-scope: rà soát module tài khoản hiện có, đối chiếu enum trạng thái tài
  khoản với đặc tả, làm rõ ranh giới giữa "khoá" và "vô hiệu hoá nghỉ việc";
  chỉnh sửa hạn mức lưu trữ cá nhân riêng cho từng tài khoản (override —
  phần "tầng 2" của cơ chế hạn mức đã chốt ở `personal-storage-quota.md`,
  Quyết định 1).
- Out-of-scope: tự đổi mật khẩu (FR-05), đặt lại mật khẩu (FR-06), phân
  quyền RBAC (FR-07) — xem `auth-session.md` và `rbac-group-scope.md`; giá
  trị hạn mức **mặc định theo vai trò** (tầng 1) — thuộc `system-config.md`
  (FR-01).

## 2. Ràng buộc (Constraints)

- Quản lý tài khoản qua Keycloak Admin API (đã có
  `KeycloakAdminService.java`), backend proxy qua service account của client
  `backend`.
- Không xoá cứng tài khoản đã có lịch sử thao tác (ràng buộc nghiệp vụ, phục
  vụ truy vết/kiểm toán).

## 3. NFR (Non-Functional Requirements)

| Loại | Yêu cầu |
|---|---|
| Performance | Thao tác tạo/sửa/khoá phản hồi nhanh, đồng bộ ngay với Keycloak |
| Security | Chỉ R3 thực hiện; khoá tài khoản phải chấm dứt phiên hiện hành ngay |
| Scalability | Không đáng kể ở quy mô một cơ quan/tổ chức |
| Availability | Thao tác quản lý tài khoản không phụ thuộc các module nghiệp vụ khác |
| Khác | Integrity — giữ nguyên lịch sử thao tác của tài khoản dù đã khoá/vô hiệu hoá |

## 4. Phương án & Quyết định (ADR)

### Quyết định 1: Mô hình trạng thái tài khoản

- **Ngữ cảnh:** SRS dùng cả hai cách diễn đạt — Tên chức năng ghi "khoá/mở
  khoá tài khoản", Mô tả chi tiết lại thêm "vô hiệu hoá tài khoản nghỉ việc".
  Chưa rõ đây là một hay hai khái niệm khác nhau.
- **Các phương án xem xét:**
  - A) 2 trạng thái: `active` / `locked` (dùng chung cho cả khoá tạm thời và
    nghỉ việc, chỉ dựa vào cờ `enabled` của Keycloak).
  - B) 3 trạng thái: `active` / `locked` (khoá tạm, có thể tự mở lại — vd. do
    vi phạm, tạm ngưng công tác) / `disabled` (vô hiệu hoá dài hạn — nghỉ
    việc, chuyển công tác), cả hai đều ánh xạ `enabled=false` ở Keycloak
    nhưng phân biệt bằng metadata "lý do" ở tầng backend.
- **Quyết định:** B — đã chốt. "Khoá" (tạm thời, có thể tự mở lại) và "vô
  hiệu hoá nghỉ việc" (dài hạn) là hai nghiệp vụ khác nhau.
- **Lý do:** hai khái niệm trong SRS có ngữ nghĩa nghiệp vụ khác nhau (khoá
  tạm thời có thể đảo ngược nhanh; nghỉ việc là quyết định dài hạn, thường
  đi kèm quy trình khác như thu hồi thiết bị/quyền truy cập vật lý) — tách
  biệt giúp báo cáo/audit rõ ràng hơn.
- **Hệ quả (trade-off):** cần thêm cột `status_reason` ở bảng tài khoản nội
  bộ (backend) thay vì chỉ dựa vào cờ `enabled` của Keycloak, vì Keycloak
  không phân biệt 2 lý do khoá. Lý do khoá (reason) bắt buộc được lưu và
  **hiển thị cho mọi R3 khác xem** (đã chốt), không chỉ người thực hiện thao
  tác.

### Quyết định 2: Chỉnh hạn mức lưu trữ cá nhân riêng theo từng tài khoản

- **Ngữ cảnh:** `personal-storage-quota.md` (FR-14, Quyết định 1) đã chốt cơ
  chế 2 tầng — hạn mức mặc định theo vai trò (`system-config`/FR-01) được
  snapshot vào cột `storage_quota_bytes` khi tạo tài khoản mới; sau đó cần
  một nơi cho R3 chỉnh trực tiếp giá trị này cho từng tài khoản cụ thể.
- **Quyết định:** thao tác chỉnh hạn mức riêng cho một tài khoản thuộc phạm
  vi FR-04 (module quản lý tài khoản này), không phải `system-config`.
- **Lý do:** đây là một hành động quản lý trên **một tài khoản cụ thể**
  (giống tạo/sửa/khoá/mở khoá đã có ở FR-04), không phải cấu hình áp dụng
  cho toàn hệ thống — nhất quán với các thao tác khác trong cùng module,
  dùng chung entrypoint quản trị tài khoản và cùng mô hình quyền (chỉ R3).
- **Hệ quả (trade-off):** `UpdateAccountDto` (hoặc DTO tương đương) cần bổ
  sung trường `storageQuotaBytes`; API ghi thẳng vào cột `storage_quota_bytes`
  đã định nghĩa ở `personal-storage-quota.md` — **dùng chung một cột/nguồn
  dữ liệu**, không tạo bảng hoặc API trùng lặp giữa hai tài liệu. Đổi hạn
  mức không ảnh hưởng tệp đã lưu vượt hạn mức mới (không hồi tố xoá dữ
  liệu), chỉ chặn upload tiếp theo nếu đã vượt.

## 5. Tech stack đề xuất

- Backend: Keycloak Admin API (đã chốt, qua `KeycloakAdminService`),
  PostgreSQL (cột `status_reason` lưu lý do khoá — bắt buộc, hiển thị cho
  mọi R3; cột `storage_quota_bytes` — dùng chung với `personal-storage-quota.md`,
  chỉnh qua API quản lý tài khoản của module này).
- Frontend: `source_v2/web/src/app/admin` (đã có `AdminTabs.tsx`).
- Data store: Keycloak (identity), PostgreSQL (metadata nghiệp vụ: lý do
  khoá, hạn mức lưu trữ riêng từng tài khoản).
- Infra/Deploy: không đổi.

## 6. Rủi ro

| Rủi ro | Mức độ | Giảm thiểu |
|---|---|---|
| Khoá tài khoản đang có phiên hoạt động không chấm dứt phiên ngay (token còn hiệu lực tới khi hết hạn) | Trung bình | Gọi Keycloak Admin API logout user ngay khi khoá, không chỉ set `enabled=false` |
| Nhầm lẫn giữa 2 trạng thái `locked`/`disabled` nếu không thống nhất rõ với người dùng cuối (R3) | Thấp | UI hiển thị rõ nhãn + lý do khoá |

## 7. Câu hỏi mở

_Đã chốt cả 2 câu hỏi mở trước đó (xem Quyết định 1):_
- ~~Xác nhận "khoá" và "vô hiệu hoá nghỉ việc" là 1 hay 2 khái niệm khác
  nhau về hành vi nghiệp vụ.~~ → **2 nghiệp vụ khác nhau.**
- ~~Có cần lưu và hiển thị lý do khoá (reason) cho các R3 khác xem không?~~
  → **Có, bắt buộc lưu và hiển thị lý do khoá cho mọi R3.**

Không còn câu hỏi mở nào cho FR-04 ở bước SA.
