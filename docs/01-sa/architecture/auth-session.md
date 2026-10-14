# Architecture: auth-session

> File: `docs/01-sa/architecture/auth-session.md` — Owner: SA
> Status: Approved

## 1. Bối cảnh / Vấn đề

- Chuẩn hoá và rà soát 4 luồng xác thực/quản lý phiên cơ bản
  (`docs/dac_ta_yeu_cau_chuc_nang_phi_chuc_nang.adoc` mục 2.13):
  FR-02 (đăng nhập tạo phiên theo vai trò), FR-03 (đăng xuất/chấm dứt phiên),
  FR-05 (tự đổi mật khẩu sau khi xác thực mật khẩu hiện tại), FR-06 (quản trị
  R3 đặt lại mật khẩu tài khoản bất kỳ không cần mật khẩu cũ). Mọi thao tác
  ghi nhật ký (FR-09).
- In-scope: đối chiếu luồng hiện tại (Keycloak) với đặc tả, bổ sung phần còn
  thiếu (khoá sau N lần đăng nhập sai, tự động hết phiên theo cấu hình FR-01).
- Out-of-scope: R4 (hệ thống ngoài) xác thực qua API key (FR-32/33), không
  thuộc phạm vi tài liệu này; thay đổi identity provider; MFA (đã chốt:
  ngoài phạm vi giai đoạn này, xem mục 7).

## 2. Ràng buộc (Constraints)

- Mạng cách ly hoàn toàn — không SSO ra ngoài, không phụ thuộc dịch vụ auth
  bên ngoài.
- Giữ nguyên Keycloak realm đã có (`aismartstore`,
  `source_v2/keycloak/realm-export.json`) — không đề xuất lại
  cơ chế xác thực.
- Web dùng NextAuth `KeycloakProvider` (authorization code + PKCE); backend
  validate bearer token offline qua `nest-keycloak-connect`/Spring Security.
- **Nguyên tắc đã chốt:** tham số vận hành mà Keycloak đã hỗ trợ cấu hình sẵn
  (brute-force lockout, thời hạn token/session...) thì cấu hình **trực tiếp
  trong Keycloak realm**, không xây thêm lớp đồng bộ với `system-config`
  (FR-01).

## 3. NFR (Non-Functional Requirements)

| Loại | Yêu cầu |
|---|---|
| Performance | Đăng nhập/đăng xuất phản hồi tức thời (không phụ thuộc hàng đợi xử lý nền) |
| Security | Chống brute-force, không lộ chi tiết lỗi khi xác thực sai, mọi thao tác ghi nhật ký |
| Scalability | Không đáng kể ở quy mô một cơ quan/tổ chức (NFR-03) |
| Availability | Session hết hạn không làm mất dữ liệu người dùng đang thao tác dở (cần cảnh báo trước khi hết hạn) |
| Khác | Timeout phiên theo cấu hình thời hạn token (JWT) của Keycloak — không đồng bộ với FR-01/`system-config` |

## 4. Phương án & Quyết định (ADR)

### Quyết định 1: Khoá tài khoản sau N lần đăng nhập sai

- **Ngữ cảnh:** FR-02 yêu cầu "vượt số lần thử cho phép → tạm khoá và cảnh
  báo", nhưng cơ chế cụ thể chưa có trong code hiện tại.
- **Các phương án xem xét:**
  - A) Dùng cơ chế Brute Force Detection có sẵn của Keycloak (cấu hình trong
    realm).
  - B) Tự cài logic đếm số lần sai ở tầng backend.
- **Quyết định:** A — đã chốt. Dùng cơ chế Brute Force Detection có sẵn của
  Keycloak.
- **Lý do:** giảm effort, đã được kiểm chứng, tập trung quản lý tài khoản tại
  một nơi (Keycloak) thay vì phân tán logic bảo mật.
- **Hệ quả (trade-off):** tham số ngưỡng khoá (số lần sai, thời gian khoá)
  cấu hình **trực tiếp trong Keycloak realm** (admin console) — đã chốt
  KHÔNG xây adapter đồng bộ với FR-01 (system-config), theo nguyên tắc ở
  mục 2.

### Quyết định 2: FR-06 — đặt lại mật khẩu không cần mật khẩu cũ

- **Ngữ cảnh:** đã có `KeycloakAdminService`
  (`source_v2/backend/.../accounts/KeycloakAdminService.java`) gọi Keycloak
  Admin API.
- **Các phương án xem xét:**
  - A) Dùng Keycloak Admin API `reset-password` sẵn có, bổ sung
    `requiredActions=UPDATE_PASSWORD` khi cần buộc đổi mật khẩu ở lần đăng
    nhập kế tiếp.
  - B) Xây cơ chế đặt lại mật khẩu riêng ngoài Keycloak.
- **Quyết định:** A.
- **Lý do:** đã có sẵn hạ tầng, chỉ cần rà soát bổ sung tuỳ chọn "buộc đổi ở
  lần đăng nhập kế tiếp" đúng theo luồng xử lý FR-06.

## 5. Tech stack đề xuất

- Backend: Keycloak (identity provider, đã chốt), Spring Security resource
  server (đã chốt).
- Frontend: NextAuth `KeycloakProvider` (đã chốt).
- Data store: Keycloak owns identity/session state; `audit_log` (PostgreSQL)
  cho lịch sử đăng nhập/đăng xuất/đổi mật khẩu.
- Infra/Deploy: không đổi.

## 6. Rủi ro

| Rủi ro | Mức độ | Giảm thiểu |
|---|---|---|
| Session timeout giữa Keycloak (SSO session) và NextAuth (JWT expiry) lệch nhau → người dùng bị đăng xuất không nhất quán | Trung bình | Thống nhất Keycloak làm nguồn cấu hình thẩm quyền (authoritative), NextAuth chỉ phản ánh theo |
| Sự kiện brute-force lockout của Keycloak không xuất hiện trong nhật ký nghiệp vụ (FR-09/FR-10) | Thấp | Đồng bộ Keycloak event → `audit_log` qua event listener |

## 7. Câu hỏi mở

_Đã chốt cả 3 câu hỏi mở trước đó (xem Quyết định 1 và mục 2):_
- ~~MFA ("yếu tố xác thực bổ sung nếu được cấu hình") có trong phạm vi giai
  đoạn hiện tại không?~~ → **Bỏ, ngoài phạm vi giai đoạn này.**
- ~~Số lần đăng nhập sai tối đa trước khi khoá và thời gian khoá?~~ →
  **Cấu hình trực tiếp trong Keycloak (Brute Force Detection), không xây
  UI/đồng bộ riêng ở module quản trị hệ thống.**
- ~~Thời gian timeout phiên mặc định và cơ chế đồng bộ với `system-config`?~~
  → **Dùng theo cấu hình thời hạn token (JWT) của Keycloak, không cấu hình
  riêng ở module quản trị hệ thống.**

Không còn câu hỏi mở nào cho auth-session ở bước SA.
