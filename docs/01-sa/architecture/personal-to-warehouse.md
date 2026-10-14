# Architecture: personal-to-warehouse

> File: `docs/01-sa/architecture/personal-to-warehouse.md` — Owner: SA
> Status: Approved

## 1. Bối cảnh / Vấn đề

- Yêu cầu mới (ngoài SRS gốc): cho phép người dùng **bổ sung tài liệu từ
  không gian lưu trữ cá nhân (FR-14) vào kho dữ liệu dùng chung (FR-11/FR-13)**
  mà không phải tải lại tệp từ máy trạm.
- Hiện trạng: hai không gian tách biệt hoàn toàn
  (`personal-storage-quota.md` §1): cá nhân = lưu trữ thuần tuý, mọi định dạng,
  không OCR/STT, không chỉ mục; kho chung = bắt buộc chọn thư mục theo cây R3,
  allowlist định dạng, quét mã độc, số hoá, chỉ mục, phân quyền theo nhóm.
  Muốn đưa một tệp cá nhân lên kho, người dùng phải tải xuống rồi nạp lại qua
  `/ingest` — tốn băng thông, dễ sai sót.
- Bản chất nghiệp vụ: đây là **một kênh nạp mới của FR-11** (nguồn là tệp đã
  nằm trên server thay vì trình duyệt), **không** phải một thao tác trên
  không gian cá nhân. Mọi quy tắc FR-11/FR-12/FR-13 phải giữ nguyên.
- In-scope: chọn tệp/thư mục cá nhân → chọn thư mục đích kho chung → kiểm tra →
  tạo `document` → đi tiếp pipeline số hoá như tài liệu nạp thường; nhật ký.
- Out-of-scope: chiều ngược lại (kho chung → cá nhân); chia sẻ nhóm ngay lúc
  bổ sung (làm sau qua FR-29); gán nhãn (FR-15) lúc bổ sung.

## 2. Ràng buộc (Constraints)

- Chỉ vai trò có `PERM_document.upload` mới nạp được vào kho — hiện seed chỉ
  `CAN_BO` (R1) có quyền này (`V12__init_groups.sql`), trong khi cả R1/R2/R3
  đều có không gian cá nhân.
- Không gian cá nhân owner-scoped tuyệt đối: chỉ **chính chủ** chọn được tệp
  của mình để bổ sung; không ai khác (kể cả R3) kéo tệp cá nhân của người khác.
- Tệp cá nhân chấp nhận mọi định dạng → một phần tệp sẽ **không đủ điều kiện**
  vào kho (ngoài allowlist `allowed_file_format`, vượt `ingest.max_file_size_bytes`).
- Người dùng không được tạo thư mục ngoài cây kho (`warehouse-structure.md`
  Quyết định 1); cấu trúc thư mục con giữ bằng `relative_path` như FR-11.
- Ngưỡng dung lượng kho: chặn nạp mới ở 95% (`warehouse-structure.md` QĐ 3).
- Giới hạn lượt: 500 tệp / 5 GB mỗi lượt (`data-ingestion.md` câu hỏi mở #2).
- Kho chung chịu NFR-09 (xoá qua thùng rác/purge, không xoá tay).

## 3. NFR (Non-Functional Requirements)


| Loại        | Yêu cầu                                                                                                                                                                     |
| ------------ | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Performance  | Sao chép phía server (SeaweedFS → SeaweedFS), không đi qua trình duyệt; xử lý bất đồng bộ, không chặn UI với lượt nhiều tệp                               |
| Security     | Chỉ chính chủ + có`PERM_document.upload`; quét lại mã độc bằng signature hiện hành trước khi vào kho; tệp nhiễm không vào kho                              |
| Scalability  | Tái dùng cơ chế batch của`/ingest` (500 tệp / 5 GB)                                                                                                                     |
| Availability | Một tệp lỗi không làm hỏng cả lượt; lượt bị gián đoạn chạy lại được, tệp đã vào bị SKIPPED theo luật trùng hiện có                               |
| Khác        | Toàn vẹn: đối chiếu SHA-256 đã lưu ở`personal_file` với nội dung đọc lại; truy vết nguồn gốc (tài liệu kho biết được bổ sung từ tệp cá nhân nào) |

## 4. Phương án & Quyết định (ADR)

### Quyết định 1: Sao chép hay di chuyển

- **Ngữ cảnh:** sau khi bổ sung, tệp cá nhân gốc còn giữ lại không?
- **Các phương án:** A) Sao chép — tệp cá nhân giữ nguyên, kho có bản độc lập.
  B) Di chuyển — xoá tệp cá nhân sau khi vào kho. C) A mặc định + tuỳ chọn
  "xoá bản cá nhân sau khi bổ sung thành công".
- **Quyết định:** A — đã chốt (2026-09-29). Không có tuỳ chọn xoá bản cá nhân;
  người dùng tự xoá qua thao tác FR-14 nếu muốn giải phóng hạn mức.
- **Lý do:** hai bên có vòng đời khác nhau — tài liệu kho có thể bị xoá mềm/
  purge/chuyển quyền sở hữu, bị số hoá và sửa nội dung; nếu "di chuyển" thì khi
  tài liệu kho bị xoá, người dùng mất luôn bản gốc cá nhân và phục hồi thùng
  rác không trả về không gian cá nhân. Sao chép cũng không làm rối quota.
- **Hệ quả:** không giải phóng quota cá nhân (người dùng tự xoá nếu muốn);
  tốn gấp đôi dung lượng vật lý cho các tệp được bổ sung.

### Quyết định 2: Đi qua đầy đủ kiểm tra của FR-11/FR-12

- **Ngữ cảnh:** tệp cá nhân đã qua ClamAV lúc tải lên — có bỏ qua được không?
- **Quyết định:** **không bỏ qua** — đã chốt. Mỗi tệp đi đúng các bước của
  `DocumentsService.ingestBatchFile` theo thứ tự hiện có: nhận diện trùng vị
  trí `(folder_id, relative_path, original_name)` → định dạng allowlist →
  kích thước → dung lượng kho → mã băm (đối chiếu với `personal_file.sha256`)
  → **quét lại mã độc** → ghi object mới → `document` trạng thái `clean` →
  phát sự kiện số hoá.
- **Lý do:** signature có thể đã cập nhật từ lúc tải lên cá nhân (mạng cách ly,
  cập nhật ngoại tuyến); kho chung có yêu cầu an toàn cao hơn không gian cá
  nhân. Tái dùng đúng một đường nạp giúp không phát sinh luật riêng.
- **Hệ quả:** thêm chi phí quét; kết quả từng tệp dùng chung mã trạng thái của
  `/ingest` (`INGESTED`, `SKIPPED ALREADY_EXISTS`, `INVALID_FORMAT`,
  `FILE_TOO_LARGE`, `MALWARE_DETECTED`, `WAREHOUSE_CAPACITY_EXCEEDED`...).

### Quyết định 3: Người nạp và luồng số hoá sau khi bổ sung

- **Quyết định (đã chốt):** `document.uploader_sub` = người thực hiện bổ sung (chính chủ
  tệp cá nhân). Sau đó tài liệu đi y hệt tài liệu nạp thường: OCR/STT →
  người nạp rà soát/xác nhận (`digitization-review.md` QĐ 5) hoặc tự xác nhận
  sau `ocr_stt.auto_confirm_hours` → lập chỉ mục → hiển thị theo phạm vi nhóm
  (FR-08). Mặc định chỉ người nạp thấy; chia sẻ nhóm làm sau qua FR-29.
- **Vai trò (đã chốt):** chỉ R1 — tái dùng `PERM_document.upload`, **không**
  thêm permission mới, không đổi ma trận quyền. R2/R3 không thấy thao tác này.
- **Thông tin nhập kèm (đã chốt):** chỉ `note` tuỳ chọn như `/ingest`; nhãn
  (FR-15) và chia sẻ nhóm (FR-29) thao tác sau như tài liệu nạp thường.
- **Lý do:** không tạo thêm loại "chủ sở hữu" mới, tái dùng toàn bộ quy tắc
  quyền đã chốt.

### Quyết định 4: Đơn vị bổ sung và giữ cấu trúc thư mục

- **Quyết định (đã chốt):** hỗ trợ (a) một/nhiều tệp chọn tay và (b) cả một thư mục cá nhân
  (kèm thư mục con). Với (b), đường dẫn tương đối trong cây cá nhân (tính từ
  thư mục được chọn) được ghi vào `relative_path`, **không** tạo thêm
  `warehouse_folder` — đúng cách FR-11 xử lý tải thư mục.
- **Hệ quả:** tên thư mục cá nhân có thể có dấu/ký tự tự do — chỉ nằm trong
  `relative_path` nên không vi phạm luật đặt tên thư mục kho.
- **Bổ sung lại (đã chốt):** cho phép bổ sung cùng một tệp cá nhân nhiều lần;
  áp đúng luật trùng của `/ingest`: trùng vị trí `(folder_id, relative_path, original_name)` → `SKIPPED ALREADY_EXISTS`; trùng nội dung ở vị trí khác →
  vẫn nạp, kèm cảnh báo `duplicateOf`. UI kho cá nhân đánh dấu tệp "đã bổ
  sung" (dựa trên `source_personal_file_id` của tài liệu kho chưa bị xoá).

### Quyết định 5: Lưu vết nguồn gốc & nhật ký

- **Quyết định (đã chốt):** thêm cột tham chiếu nguồn trên `document` (vd.
  `source_kind = 'personal'`, `source_personal_file_id`, không FK cứng vì tệp
  cá nhân có thể bị xoá sau đó). Ghi `audit_log` hành động bổ sung (id tệp
  nguồn, **tên tệp**, kích thước, id tài liệu mới, thư mục đích, kết quả) —
  **không** ghi đường dẫn/tên thư mục cá nhân (dữ liệu riêng tư; tên tệp thì
  đã hiển thị công khai trong kho nên ghi được).
- **Lý do:** truy vết được tài liệu kho đến từ đâu (FR-09, NFR-05) và hiển
  thị "đã bổ sung vào kho" cho tệp cá nhân tương ứng.

### Quyết định 6: Dung lượng

- **Quyết định (đã chốt):** bản kho tính vào **dung lượng kho chung**
  (ngưỡng cảnh báo 85% / chặn 95%); bản cá nhân vẫn tính vào hạn mức cá nhân
  (`storage_quota_bytes`) cho tới khi người dùng xoá. Tài liệu kho **không**
  trừ vào hạn mức cá nhân của người nạp — khớp code hiện tại
  (`PersonalFileRepository.sumSizeByOwnerSub` chỉ cộng `personal_file`).
- **Hệ quả:** thao tác bổ sung không bao giờ bị chặn vì hạn mức cá nhân, chỉ
  bị chặn vì dung lượng kho chung.

## 5. Tech stack đề xuất

- Backend: tái dùng `ingest` (`UploadBatch`, `BatchKind` — thêm loại nguồn
  `PERSONAL`), `DocumentsService.ingestBatchFile` tách phần xử lý từ
  `InputStream` để nhận nguồn là object SeaweedFS; `PersonalService` chỉ cung
  cấp đọc tệp theo owner. Sao chép object SeaweedFS phía server (key mới trong
  vùng kho, không dùng chung key với tệp cá nhân).
- Frontend: `web/src/app/personal/` — chọn tệp/thư mục → hộp thoại chọn thư mục
  đích (`FolderTree.tsx`) → bảng kết quả từng tệp; đánh dấu tệp đã bổ sung.
- Data store: PostgreSQL (cột nguồn trên `document`); không thêm hạ tầng.
- Infra/Deploy: không đổi.

## 6. Rủi ro


| Rủi ro                                                                                                                                               | Mức độ   | Giảm thiểu                                                                                                                                |
| ----------------------------------------------------------------------------------------------------------------------------------------------------- | ----------- | ------------------------------------------------------------------------------------------------------------------------------------------- |
| Người dùng chọn nhiều tệp ngoài allowlist → thất bại hàng loạt, trải nghiệm kém                                                        | Trung bình | Lọc trước phía client theo`/ingest/allowed-formats` + kích thước, hiển thị rõ tệp không đủ điều kiện trước khi gửi      |
| Rò rỉ dữ liệu cá nhân nhạy cảm lên kho do chọn nhầm                                                                                        | Trung bình | Bước xác nhận hiển thị danh sách tệp + thư mục đích; tài liệu mới mặc định chỉ người nạp thấy (chưa chia sẻ nhóm) |
| Nhân đôi dung lượng vật lý (bản cá nhân + bản kho)                                                                                         | Thấp       | Chấp nhận (QĐ 1); tuỳ chọn xoá bản cá nhân là mở rộng sau                                                                       |
| Lượt thư mục lớn chiếm ClamAV/pipeline số hoá                                                                                                 | Trung bình | Giữ giới hạn 500 tệp/5 GB và hàng đợi hiện có                                                                                     |
| `group-management-sharing.md` QĐ 6 viết "tài liệu kho tính vào quota người nạp", nhưng code chỉ tính `personal_file` vào quota cá nhân | Thấp       | Đã chốt (QĐ 6 dưới đây) và đã thêm ghi chú làm rõ vào`group-management-sharing.md` QĐ 6                                    |

## 7. Câu hỏi mở

_Đã chốt cả 6 câu hỏi mở (2026-09-29):_

- ~~Vai trò nào được bổ sung?~~ → **Chỉ R1** (`PERM_document.upload`), không thêm permission (QĐ 3).
- ~~Sao chép hay có tuỳ chọn xoá bản cá nhân?~~ → **Chỉ sao chép** (QĐ 1).
- ~~Bổ sung lại cùng một tệp?~~ → **Cho phép**, theo luật trùng `/ingest`, UI đánh dấu "đã bổ sung" (QĐ 4).
- ~~Mức ghi nhật ký?~~ → **Id + tên tệp**, không ghi đường dẫn thư mục cá nhân (QĐ 5).
- ~~Tính dung lượng?~~ → **Chỉ dung lượng kho chung**, không trừ hạn mức cá nhân (QĐ 6).
- ~~Thông tin nhập kèm?~~ → **Chỉ ghi chú** tuỳ chọn (QĐ 3).

Không còn câu hỏi mở nào cho personal-to-warehouse ở bước SA.
