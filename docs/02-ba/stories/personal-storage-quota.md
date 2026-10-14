# User Story: personal-storage-quota

> File: `docs/02-ba/stories/personal-storage-quota.md` — Owner: BA
> Nguồn kiến trúc: `docs/01-sa/architecture/personal-storage-quota.md`
> Status: Approved

Mỗi người dùng (R1/R2/R3) có không gian lưu trữ cá nhân riêng theo cây thư mục,
trong hạn mức được cấp (FR-14). Là lưu trữ thuần túy: KHÔNG OCR/STT, KHÔNG lập
chỉ mục tìm kiếm, tách biệt hoàn toàn khỏi kho dùng chung. Hạn mức theo cơ chế
2 tầng: mặc định theo vai trò (`system-config`) snapshot khi tạo tài khoản +
override riêng từng tài khoản (nối `account-lifecycle.md`).

---

## Story 1 — Lưu trữ tệp/thư mục cá nhân riêng tư (owner-scoped)

**As a** người dùng (R1/R2/R3)
**I want** lưu tệp và thư mục vào không gian cá nhân của riêng tôi
**So that** tôi có nơi lưu trữ riêng tư, tách biệt khỏi kho dùng chung

### Acceptance Criteria

```
Given tôi đã đăng nhập
When tôi tải tệp / tạo thư mục trong không gian cá nhân
Then dữ liệu được lưu vào không gian của riêng tôi và chỉ tôi truy cập được
```

- [ ] AC-1: Không gian cá nhân tách biệt theo chủ sở hữu (owner-scoped); **không ai khác truy cập được, kể cả R3** (NFR Security).
- [ ] AC-2: Chấp nhận **mọi loại tệp** (không áp danh mục định dạng như kho chung).
- [ ] AC-3: Tệp/thư mục cá nhân tổ chức theo cây thư mục riêng của người dùng.
- [ ] AC-4: Dữ liệu cá nhân KHÔNG xuất hiện trong kết quả tìm kiếm nội dung của kho chung; KHÔNG kích hoạt OCR/STT/lập chỉ mục (NFR Khác).

---

## Story 2 — Kiểm tra an toàn tệp cá nhân (không số hóa)

**As a** hệ thống / quản trị bảo mật
**I want** tệp cá nhân vẫn qua kiểm tra an toàn nhưng không kích hoạt số hóa/chỉ mục
**So that** dữ liệu cá nhân an toàn mà vẫn giữ tính tách biệt khỏi luồng nghiệp vụ

### Acceptance Criteria

```
Given tôi tải một tệp vào không gian cá nhân
When hệ thống xử lý tệp
Then tệp qua kiểm tra an toàn (FR-12) nhưng KHÔNG qua OCR/STT và KHÔNG lập chỉ
     mục nội dung
```

- [ ] AC-1: Tệp cá nhân vẫn qua kiểm tra an toàn (FR-12); tệp không an toàn bị từ chối. **Tái dùng chung `ClamdService` (quét mã độc) với `data-ingestion.md`** — không xây pipeline scan riêng. Chỉ tái dùng phần quét mã độc; **KHÔNG áp phần kiểm tra định dạng/allowlist** của `data-ingestion.md` (đã chốt ở Story 1 AC-2 **của chính tài liệu này**: không gian cá nhân chấp nhận mọi loại tệp).
- [ ] AC-2: KHÔNG kích hoạt OCR/STT (FR-17–20) và KHÔNG lập chỉ mục tìm kiếm cho tệp cá nhân.

---

## Story 3 — Chặn khi vượt hạn mức lưu trữ cá nhân

**As a** người dùng
**I want** hệ thống chặn tải lên khi tổng dung lượng vượt hạn mức được cấp
**So that** tôi không vượt quá dung lượng cho phép của mình

### Acceptance Criteria

```
Given tôi có một hạn mức lưu trữ cá nhân xác định
When tôi tải lên khiến tổng dung lượng vượt hạn mức
Then hệ thống chặn phần vượt và thông báo rõ, KHÔNG ảnh hưởng phần còn lại của
     hệ thống
```

- [ ] AC-1: Kiểm tra **trước khi bắt đầu** lượt tải: client ước tính tổng dung lượng các tệp sắp tải (qua `File` API trình duyệt) so với hạn mức còn lại, chặn sớm ở client nếu chắc chắn vượt (Quyết định 2 phương án A) — chỉ để UX nhanh, **không phải nguồn xác thực**. Server xác thực lại theo cùng mô hình **batch** đã dùng ở `data-ingestion.md` (Story 2 AC-3): client mở batch khai báo tổng dung lượng ước tính, server đối chiếu với `(storage_quota_bytes − dung lượng đã dùng hiện tại)` của người dùng, từ chối batch ngay nếu khai báo đã vượt.
- [ ] AC-2: Kiểm tra **sau mỗi tệp** theo dung lượng thực tế phía server — server cộng dồn dung lượng thực nhận vào batch, nếu vượt hạn mức giữa chừng thì dừng nhận tệp mới trong batch đó, **giữ lại các tệp đã tải thành công trước đó** (Quyết định 2 phương án B, nguyên tắc "một tệp lỗi không hỏng cả lượt"). "Dung lượng đã dùng" = tổng `size` các `PersonalFile` của người dùng, cộng dồn từ siêu dữ liệu trong database — **không đọc report từ SeaweedFS** (nhất quán với cách tính ở `warehouse-structure.md` Story 5 AC-1).
- [ ] AC-3: Kiểm tra hạn mức KHÔNG làm chậm đáng kể thao tác tải lên (NFR Performance).
- [ ] AC-4: Vượt hạn mức chỉ chặn thao tác liên quan, không ảnh hưởng phần còn lại của hệ thống (NFR Availability).

---

## Story 4 — Hạn mức mặc định theo vai trò, snapshot khi tạo tài khoản

**As a** hệ thống
**I want** khởi tạo hạn mức của tài khoản mới từ giá trị mặc định theo vai trò tại thời điểm tạo
**So that** mỗi tài khoản có hạn mức đúng vai trò mà vẫn cho phép override riêng sau này

### Acceptance Criteria

```
Given giá trị mặc định theo vai trò đang cấu hình ở system-config
When một tài khoản mới được tạo
Then trường storage_quota_bytes của tài khoản được snapshot từ giá trị mặc
     định theo vai trò tại đúng thời điểm tạo
```

- [ ] AC-1: Giá trị mặc định theo vai trò: R1 = 20 GB, R2 = 50 GB, R3 = 20 GB (đã chốt, lưu ở `system-config`).
- [ ] AC-2: `storage_quota_bytes` là **snapshot một lần** khi tạo tài khoản — KHÔNG nullable fallback động (Quyết định 1).
- [ ] AC-3: R3 đổi giá trị mặc định theo vai trò sau này KHÔNG tự động đổi hạn mức tài khoản **đã tồn tại**; chỉ tài khoản tạo mới sau đó nhận giá trị mới (hành vi có chủ đích).
- [ ] AC-4: `PersonalProperties` chỉ dùng cho giá trị mặc định vai trò đọc từ `system-config`, KHÔNG còn là nguồn hạn mức thực tế của từng tài khoản.

---

## Ngoài phạm vi (Out of scope)

- Kho dùng chung và cấu trúc thư mục kho (FR-13) — thuộc `warehouse-structure.md`.
- Nạp dữ liệu vào kho chính + số hóa (FR-11/12/17–20) — thuộc `data-ingestion.md`.
- **Thao tác R3 chỉnh hạn mức riêng cho một tài khoản** (override tầng 2) — chi tiết ở `account-lifecycle.md` (Story 5), story này chỉ định nghĩa cơ chế/hành vi hạn mức.
- Cấu hình giá trị mặc định theo vai trò qua UI — thuộc `system-config.md`.
- Thao tác quản trị "áp lại hàng loạt" hạn mức cho tài khoản cũ — ngoài phạm vi MVP (Rủi ro).

## Phụ thuộc / Ghi chú kiến trúc liên quan

- Quyết định 1: cơ chế 2 tầng — mặc định theo vai trò (`system-config`) snapshot lúc tạo + override riêng (`account-lifecycle`); cột `storage_quota_bytes` dùng chung với `account-lifecycle.md`.
- Quyết định 2: kiểm tra vượt hạn mức kết hợp trước lượt tải (ước tính) + sau mỗi tệp (thực tế server).
- Đã có `PersonalController`/`PersonalFile`/`PersonalFolder`/`PersonalService` (backend), `web/src/app/personal/`.
- SeaweedFS cho lưu trữ vật lý; PostgreSQL cho siêu dữ liệu.
- Cột `storage_quota_bytes` nằm trên **bảng tài khoản nội bộ** (cùng bảng
  chứa `status_reason` mà `account-lifecycle.md` Quyết định 1 yêu cầu),
  KHÔNG nằm trên `PersonalFolder`/`PersonalFile` — vì đây là thuộc tính
  gắn với người dùng (1 dòng/tài khoản), không phải với từng tệp/thư mục.
- ⚠️ **Phát hiện quan trọng:** đã rà soát `source_v2/backend/.../accounts/`
  — package này **chỉ có Controller/Service/DTO, chưa có JPA entity/bảng
  DB nào cho "account"** (toàn bộ danh tính hiện quản lý 100% qua
  Keycloak, backend không có bản sao nội bộ). Nghĩa là bảng tài khoản nội
  bộ nói trên (chứa `status_reason` + `storage_quota_bytes`) **chưa tồn
  tại, cần tạo mới hoàn toàn** (entity + migration Flyway) — đây là công
  việc chung, phục vụ cả `account-lifecycle.md` lẫn story này, nên làm
  một lần duy nhất. Ghi nhận ở đây để DEV không làm trùng lặp khi viết
  `dev-spec` cho 1 trong 2 tài liệu.

## Câu hỏi mở cho DEV

_Đã chốt cả 4 câu hỏi mở trước đó:_

- ~~Vị trí cột `storage_quota_bytes`?~~ → **Chốt: bảng tài khoản nội bộ**
  (cùng bảng với `status_reason` của `account-lifecycle.md`), không phải
  bảng không gian cá nhân. ⚠️ Phát hiện: bảng này **chưa tồn tại trong
  code** — xem mục "Phụ thuộc".
- ~~Cách tính "tổng dung lượng đã dùng"?~~ → **Chốt: cộng dồn từ siêu dữ
  liệu `PersonalFile` trong database, không đọc report SeaweedFS** — nhất
  quán với cách tính ở `warehouse-structure.md` (Story 5 AC-1).
- ~~Đối chiếu ước tính client với thực tế server?~~ → **Chốt: dùng lại mô
  hình batch của `data-ingestion.md`** — client chỉ ước tính để UX nhanh,
  server luôn là nguồn xác thực cuối cùng qua batch (khai báo trước, cộng
  dồn thực nhận sau). Chi tiết đã cập nhật vào Story 3 AC-1/AC-2.
- ~~Dùng lại pipeline ClamAV hay đường riêng?~~ → **Chốt: tái dùng chung
  `ClamdService`** với `data-ingestion.md` (chỉ phần quét mã độc), KHÔNG
  áp phần kiểm tra định dạng/allowlist (khác với kho chung, vì không gian
  cá nhân chấp nhận mọi loại tệp).

Không còn câu hỏi mở nào cho DEV ở bước BA.
