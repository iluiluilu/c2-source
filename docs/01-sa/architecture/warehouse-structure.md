# Architecture: warehouse-structure

> File: `docs/01-sa/architecture/warehouse-structure.md` — Owner: SA
> Status: Approved

## 1. Bối cảnh / Vấn đề

- FR-13 (`docs/dac_ta_yeu_cau_chuc_nang_phi_chuc_nang.adoc` mục 2.13): R3
  quản lý danh mục kho, cấu trúc thư mục và không gian lưu trữ dùng chung —
  tạo/sửa cấu trúc kho, theo dõi dung lượng đã dùng/còn lại. Cấu trúc thư mục
  do R3 thiết lập là "khung bắt buộc" mà người dùng phải chọn khi nạp dữ liệu
  (FR-11).
- In-scope: mô hình cây thư mục kho dùng chung (tĩnh hay động), quy tắc quản
  lý dung lượng, mối liên hệ với phân quyền phạm vi (FR-08).
- Out-of-scope: không gian lưu trữ cá nhân (FR-14, xem
  `personal-storage-quota.md`); luồng upload chi tiết (FR-11, xem
  `data-ingestion.md`).

## 2. Ràng buộc (Constraints)

- Cấu trúc kho là khung bắt buộc: người dùng không được tạo thư mục ngoài
  cấu trúc R3 định nghĩa khi upload (FR-11).
- Không xoá được thư mục còn dữ liệu — phải chặn hoặc yêu cầu di chuyển
  trước (Ngoại lệ & xử lý lỗi FR-13).
- Đã có `WarehouseFolder`, `FolderNode`, `WarehouseController`/`Service`
  trong `source_v2/backend/.../warehouse/`.

## 3. NFR (Non-Functional Requirements)

| Loại | Yêu cầu |
|---|---|
| Performance | Hiển thị cây thư mục và dung lượng sử dụng phản hồi nhanh |
| Security | Chỉ R3 tạo/sửa cấu trúc kho |
| Scalability | Cây thư mục có thể mở rộng theo thời gian cùng khối lượng dữ liệu (NFR-03) |
| Availability | Sửa cấu trúc kho không được làm gián đoạn truy cập tài liệu hiện có |
| Khác | Cảnh báo ở 85%, chặn nạp mới ở 95% dung lượng tối đa hiện có của kho (đã chốt ở Quyết định 3, tham số nằm trong `system-config`) |

## 4. Phương án & Quyết định (ADR)

### Quyết định 1: Cây thư mục kho — tĩnh hay động

- **Ngữ cảnh:** SRS vừa gọi cấu trúc kho là "khung bắt buộc" vừa cho phép R3
  "tạo/sửa cấu trúc kho" — cần làm rõ đây là cây cố định lâu dài hay có thể
  thay đổi theo thời gian.
- **Các phương án xem xét:**
  - A) Cây tĩnh: định nghĩa một lần, hiếm khi đổi.
  - B) Cây động có kiểm soát: R3 tạo/sửa theo nhu cầu nghiệp vụ theo thời
    gian, nhưng không xoá được nhánh còn dữ liệu; giới hạn độ sâu tối đa.
- **Quyết định:** B — đã chốt.
- **Lý do:** "khung bắt buộc" chỉ có nghĩa là người dùng (R1) không được tự
  ý tạo thư mục ngoài cấu trúc khi upload — không có nghĩa là bản thân cấu
  trúc phải bất biến. R3 vẫn cần điều chỉnh cây theo thời gian khi nhu cầu tổ
  chức dữ liệu thay đổi, đúng với "tạo/sửa cấu trúc kho" trong Luồng xử lý
  chính FR-13.
- **Con số cụ thể (đã chốt):**
  - Độ sâu tối đa: **5 cấp** (đủ phân loại theo kiểu cơ quan/lĩnh vực/loại
    tài liệu/năm/tháng, tránh đường dẫn quá dài, dễ quản lý).
  - Quy tắc đặt tên thư mục: chữ cái không dấu, số, dấu gạch ngang/gạch dưới
    (kiểu kebab-case hoặc snake_case), tối đa 100 ký tự; **không trùng tên
    trong cùng một thư mục cha** (unique theo cặp `parent_id` + `name`).
- **Hệ quả (trade-off):** đổi tên/di chuyển nhánh sau khi đã có dữ liệu cần
  xử lý cẩn thận — không đổi khoá chính (`folder_id`) khi đổi tên hiển thị,
  để không phá vỡ tham chiếu đã lưu ở tài liệu/đường dẫn SeaweedFS.

### Quyết định 2: Liên hệ giữa cấu trúc kho và phân quyền theo nhóm (FR-08)

- **Ngữ cảnh:** cần xác nhận cấu trúc thư mục kho có phải là một biên phân
  quyền hay không.
- **Các phương án xem xét:**
  - A) Gắn phân quyền theo nhánh cây (mỗi thư mục có quyền riêng).
  - B) Không gắn — cấu trúc kho chỉ là tổ chức vật lý/logic; phân quyền
    hoàn toàn dựa trên cơ chế nhóm (FR-08, xem `rbac-group-scope.md`).
- **Quyết định:** B — đã chốt.
- **Lý do:** SRS đã minh định FR-08 là cơ chế phạm vi *duy nhất*, không dùng
  thêm tiêu chí phạm vi nào khác (nguyên tắc đã chốt từ v2.0 khi loại bỏ khái
  niệm "đơn vị" khỏi phạm vi dữ liệu). Gắn phân quyền theo nhánh cây sẽ tạo
  ra hai khái niệm phạm vi chồng chéo, đi ngược nguyên tắc "giữ mô hình đơn
  giản và khả thi khi hiện thực" đã nêu trong Mô tả chi tiết FR-08.

### Quyết định 3: Ngưỡng cảnh báo và chặn dung lượng kho

- **Ngữ cảnh:** FR-13 yêu cầu cảnh báo khi gần đến ngưỡng dung lượng đã cấu
  hình. `system-config.md` (Quyết định 3) đã đặt chỗ tham số
  `warehouse.storage_warning_threshold_percent` = 85% (đề xuất tạm, ghi chú
  "số cuối chốt ở `warehouse-structure.md`") — quyết định này chốt số cuối
  và trả lời có cần nhiều mức hay không.
- **Quyết định (đã chốt):** 2 mức, tính theo **% dung lượng tối đa hiện có
  (đã cấp/phân bổ) cho kho dùng chung** — không phải số tuyệt đối (GB/TB):
  - **Cảnh báo:** 85% — hiển thị cảnh báo cho R3 trên giao diện quản trị,
    không chặn thao tác nạp dữ liệu.
  - **Chặn:** 95% — chặn nạp dữ liệu mới (FR-11) vào kho; vẫn cho phép
    xoá/di chuyển dữ liệu để giải phóng dung lượng trong lúc chờ R3 khắc
    phục (mở rộng hạ tầng hoặc dọn dẹp).
- **Lý do:** hai mức giúp có thời gian phản ứng sớm (cảnh báo ở 85%) trước
  khi xảy ra sự cố nghiêm trọng do kho đầy hoàn toàn (chặn ở 95%, chừa
  buffer 5% dự phòng cho các thao tác hệ thống khác — nhật ký, chỉ mục...).
  Tính theo % dung lượng tối đa hiện có thay vì số tuyệt đối để ngưỡng tự
  thích ứng khi hạ tầng lưu trữ được mở rộng, không cần đổi số tay mỗi lần
  nâng cấp.
- **Hệ quả:** cả hai ngưỡng là tham số trong `system_config` (không
  hard-code), R3 chỉnh được qua UI đúng theo cơ chế đã chốt ở
  `system-config.md`; không thuộc nhóm tham số "nhạy cảm" (đổi sai dễ nhận
  ra ngay qua cảnh báo/hành vi chặn, không gây mất dữ liệu).

## 5. Tech stack đề xuất

- Backend: PostgreSQL (`WarehouseFolder` — đã có), SeaweedFS cho lưu trữ vật
  lý.
- Frontend: `source_v2/web/src/components/FolderTree.tsx` (đã có).
- Infra/Deploy: không đổi.

## 6. Rủi ro

| Rủi ro | Mức độ | Giảm thiểu |
|---|---|---|
| R3 đổi cấu trúc cây sau khi đã có dữ liệu lớn → ảnh hưởng đường dẫn đang dùng | Trung bình | Soft-rename giữ nguyên `folder_id`, chỉ đổi tên hiển thị, không đổi khoá chính |
| Ngưỡng chặn 95% quá sát 100% ở kho có tốc độ tăng dữ liệu nhanh → có thể chạm 100% giữa hai lần kiểm tra | Thấp | Kiểm tra ngưỡng đồng bộ ngay tại thời điểm ghi (không chỉ theo lịch định kỳ), cảnh báo tần suất cao hơn khi đã vượt 85% |

## 7. Câu hỏi mở

_Đã chốt cả 2 câu hỏi mở trước đó:_
- ~~Độ sâu tối đa và quy tắc đặt tên thư mục kho — con số cụ thể?~~ →
  **Chốt ở Quyết định 1: độ sâu tối đa 5 cấp; tên chỉ chữ không dấu/số/gạch
  ngang-dưới, tối đa 100 ký tự, không trùng tên trong cùng thư mục cha.**
- ~~Ngưỡng cảnh báo dung lượng cụ thể (%) — một mức hay nhiều mức?~~ →
  **Chốt ở Quyết định 3: 2 mức theo % dung lượng tối đa hiện có của kho —
  cảnh báo ở 85%, chặn nạp mới ở 95%.**

Không còn câu hỏi mở nào cho warehouse-structure ở bước SA.
