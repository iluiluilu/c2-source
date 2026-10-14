# Architecture: personal-storage-quota

> File: `docs/01-sa/architecture/personal-storage-quota.md` — Owner: SA
> Status: Approved

## 1. Bối cảnh / Vấn đề

- FR-14 (`docs/dac_ta_yeu_cau_chuc_nang_phi_chuc_nang.adoc` mục 2.13): mỗi
  người dùng (R1/R2/R3) có không gian lưu trữ cá nhân riêng theo cây thư
  mục, trong hạn mức được cấp; hạn mức cấu hình riêng theo từng vai trò
  (không dùng chung một giá trị), mặc định Lãnh đạo cao hơn Cán bộ. Đây là
  lưu trữ thuần tuý: không qua OCR/STT (FR-17–20), không lập chỉ mục tìm
  kiếm — tách biệt hoàn toàn khỏi kho dùng chung.
- In-scope: cơ chế hạn mức theo vai trò, thời điểm kiểm tra vượt hạn mức,
  tách biệt dữ liệu cá nhân khỏi luồng số hóa/tìm kiếm.
- Out-of-scope: kho dùng chung (FR-13, xem `warehouse-structure.md`); nạp dữ
  liệu vào kho chính (FR-11, xem `data-ingestion.md`).

## 2. Ràng buộc (Constraints)

- Chấp nhận mọi loại tệp (không áp danh mục định dạng như kho chung).
- Tổng dung lượng ≤ hạn mức được cấp; hạn mức cấu hình riêng theo vai trò
  qua FR-01 (system-config), quản trị có thể điều chỉnh riêng cho từng tài
  khoản cụ thể nếu cần.
- Tệp cá nhân vẫn qua kiểm tra an toàn (FR-12) nhưng không kích hoạt OCR/STT
  hay lập chỉ mục nội dung.
- Đã có `PersonalController`, `PersonalFile`, `PersonalFolder`,
  `PersonalProperties`, `PersonalService` trong
  `source_v2/backend/.../personal/`.

## 3. NFR (Non-Functional Requirements)

| Loại | Yêu cầu |
|---|---|
| Performance | Kiểm tra hạn mức không làm chậm thao tác tải lên |
| Security | Không gian cá nhân tách biệt theo chủ sở hữu (owner-scoped), không ai khác truy cập được kể cả R3 |
| Scalability | Hạn mức khác nhau theo vai trò, có thể điều chỉnh riêng từng tài khoản |
| Availability | Vượt hạn mức chỉ chặn thao tác liên quan, không ảnh hưởng phần còn lại của hệ thống |
| Khác | Tệp cá nhân không xuất hiện trong kết quả tìm kiếm nội dung của kho chính |

## 4. Phương án & Quyết định (ADR)

### Quyết định 1: Giá trị hạn mức mặc định theo vai trò

- **Ngữ cảnh:** SRS chỉ nói định tính "Lãnh đạo cao hơn Cán bộ", chưa có số
  liệu cụ thể (đây là chủ ý — theo lịch sử phiên bản, không cần thống nhất
  số liệu chi tiết ngay).
- **Đã chốt** (lưu tại `system-config` theo FR-01): Cán bộ (R1) = 20 GB,
  Lãnh đạo (R2) = 50 GB, Quản trị (R3) = 20 GB.
- **Lý do:** Lãnh đạo cần dung lượng lớn hơn để phục vụ tổng hợp báo cáo/tài
  liệu chỉ huy (đúng ghi chú trong Mô tả chi tiết FR-14); Quản trị không có
  nhu cầu nghiệp vụ đặc thù nên đặt ngang Cán bộ.
- **Bổ sung đã chốt — cơ chế 2 tầng (mặc định theo vai trò + override theo
  từng tài khoản):**
  1. Mỗi tài khoản có một trường hạn mức riêng (`storage_quota_bytes`) trên
     bảng tài khoản/không gian cá nhân nội bộ — **không** phải kiểu
     "nullable, fallback về mặc định vai trò khi truy vấn".
  2. Khi **tạo tài khoản mới**, giá trị này được khởi tạo (snapshot) từ giá
     trị mặc định theo vai trò đang cấu hình tại `system-config` (FR-01)
     ở đúng thời điểm tạo.
  3. Sau đó, R3 có thể **chỉnh sửa trực tiếp** hạn mức của riêng tài khoản
     đó (override), độc lập với giá trị mặc định chung.
  4. Hệ quả cần lưu ý: đây là **snapshot một lần**, không phải tham chiếu
     động — nếu R3 sau này đổi giá trị mặc định theo vai trò ở
     `system-config`, các tài khoản **đã tồn tại** không tự động đổi theo
     (chỉ tài khoản tạo mới sau đó nhận giá trị mặc định mới). Đây là hành
     vi có chủ đích, không phải thiếu sót.

### Quyết định 2: Thời điểm kiểm tra vượt hạn mức

- **Ngữ cảnh:** khi tải cả một thư mục, tệp xử lý độc lập ("một tệp lỗi
  không làm hỏng cả lượt tải") nhưng tổng dung lượng phải nằm trong hạn mức.
- **Các phương án xem xét:**
  - A) Chỉ kiểm tra trước khi bắt đầu (ước tính tổng dung lượng của lượt tải
    so với hạn mức còn lại).
  - B) Chỉ kiểm tra sau mỗi tệp, dừng giữa chừng nếu vượt.
- **Quyết định:** kết hợp A + B — đã chốt.
- **Lý do:** ước tính trước giúp chặn sớm, tránh lãng phí băng thông khi
  chắc chắn vượt; kiểm tra lại sau mỗi tệp vì kích thước thực tế phía server
  có thể khác ước tính phía client, đồng thời tôn trọng nguyên tắc "một tệp
  lỗi không làm hỏng cả lượt" khi phát hiện vượt hạn mức giữa chừng (dừng ở
  đúng tệp gây vượt, giữ lại các tệp đã tải thành công trước đó).

## 5. Tech stack đề xuất

- Backend: PostgreSQL (`PersonalFile`/`PersonalFolder` — đã có), SeaweedFS
  cho lưu trữ vật lý. Bổ sung cột `storage_quota_bytes` trên bảng tài khoản/
  không gian cá nhân nội bộ (snapshot từ `system-config` lúc tạo tài khoản,
  R3 chỉnh trực tiếp sau đó — xem Quyết định 1). `PersonalProperties` chỉ
  còn dùng cho giá trị mặc định vai trò đọc từ `system-config`, không còn
  là nguồn hạn mức thực tế của từng tài khoản.
- Frontend: `source_v2/web/src/app/personal/`.
- Infra/Deploy: không đổi.

## 6. Rủi ro

| Rủi ro | Mức độ | Giảm thiểu |
|---|---|---|
| Ước tính dung lượng phía client sai lệch so với thực tế | Thấp | Luôn đối chiếu lại dung lượng thực tế phía server sau khi nhận từng tệp |
| Snapshot hạn mức lúc tạo tài khoản không tự cập nhật khi R3 đổi giá trị mặc định theo vai trò sau này → tài khoản cũ và mới có hạn mức khác nhau dù cùng vai trò | Thấp | Hành vi đã chốt (có chủ đích); nếu cần áp lại hàng loạt, để lại như một thao tác quản trị riêng ngoài phạm vi MVP |

## 7. Câu hỏi mở

_Đã chốt cả 2 câu hỏi mở trước đó (xem Quyết định 1):_
- ~~Xác nhận số liệu hạn mức mặc định cụ thể theo từng vai trò?~~ → **Chốt
  như đề xuất: Cán bộ 20 GB, Lãnh đạo 50 GB, Quản trị 20 GB.**
- ~~Quản trị điều chỉnh hạn mức riêng cho từng tài khoản — thuộc UI
  `system-config` hay `account-lifecycle`?~~ → **Cả hai cùng tồn tại theo
  cơ chế 2 tầng: `system-config` (FR-01) quản lý giá trị mặc định theo vai
  trò, áp dụng khi khởi tạo tài khoản mới; sau đó hạn mức của từng tài
  khoản cụ thể chỉnh sửa được trực tiếp (nằm ở phía quản lý tài khoản,
  gần `account-lifecycle`/FR-04 hơn vì thao tác trên một tài khoản cụ thể,
  không phải cấu hình chung).**

Không còn câu hỏi mở nào cho personal-storage-quota ở bước SA.
