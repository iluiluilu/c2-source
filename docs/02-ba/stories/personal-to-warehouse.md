# User Story: personal-to-warehouse

> File: `docs/02-ba/stories/personal-to-warehouse.md` — Owner: BA
> Nguồn kiến trúc: `docs/01-sa/architecture/personal-to-warehouse.md`
> Status: Approved

## User Story

**As a** Cán bộ (R1, có `PERM_document.upload`)
**I want** chọn tệp/thư mục trong không gian cá nhân của mình và bổ sung thẳng vào một thư mục của kho dữ liệu chung
**So that** tôi không phải tải xuống rồi nạp lại từ máy trạm, tiết kiệm băng thông và tránh sai sót.

## Acceptance Criteria

```
Given tôi là R1 đang xem không gian cá nhân
When tôi chọn một/nhiều tệp (hoặc một thư mục kèm thư mục con), chọn thư mục đích trong cây kho (FolderTree), nhập ghi chú (tuỳ chọn) và xác nhận
Then hệ thống tạo lượt bổ sung bất đồng bộ và trả về kết quả từng tệp
```

### Quyền và phạm vi

- [ ]  AC-1: Thao tác "Bổ sung vào kho" chỉ hiển thị/cho phép với người có `PERM_document.upload` (hiện chỉ R1); R2/R3 không thấy, gọi API trực tiếp bị từ chối 403.
- [ ]  AC-2: Chỉ chính chủ chọn được tệp cá nhân của mình; dùng id tệp của người khác (kể cả R3) bị từ chối, không lộ sự tồn tại của tệp.
- [ ]  AC-3: Không thêm permission mới, không thay đổi ma trận quyền hiện có.

### Chọn nguồn và đích

- [ ]  AC-4: Hỗ trợ (a) một/nhiều tệp chọn tay và (b) cả thư mục cá nhân kèm thư mục con.
- [ ]  AC-5: Với thư mục, đường dẫn tương đối **gồm cả tên thư mục được chọn** được ghi vào `relative_path` (chọn `A` chứa `B/x.pdf` -> `A/B`; tệp trực tiếp trong `A` -> `A`; tệp chọn lẻ -> rỗng; giống tải thư mục FR-11, chốt 2026-09-29); không tạo thêm `warehouse_folder`; tên có dấu/ký tự tự do vẫn hợp lệ.
- [ ]  AC-6: Bắt buộc chọn thư mục đích có sẵn trong cây kho; người dùng không tạo được thư mục mới ngoài cây.
- [ ]  AC-7: Trước khi gửi, UI hiển thị danh sách tệp + thư mục đích để xác nhận; tệp ngoài allowlist (`/ingest/allowed-formats`) hoặc vượt kích thước tối đa được lọc/đánh dấu "không đủ điều kiện" rõ ràng phía client.
- [ ]  AC-8: Một lượt tối đa 500 tệp / 5 GB, tính trên các tệp đủ điều kiện sau khi server lọc; vượt thì cả lượt bị từ chối (`400 BATCH_LIMIT_EXCEEDED`) kèm thông báo rõ.
- [ ]  AC-9: Chỉ nhập được `note` tuỳ chọn; không có nhãn (FR-15) hay chia sẻ nhóm (FR-29) lúc bổ sung.

### Kiểm tra từng tệp (dùng chung đường nạp FR-11/FR-12)

- [ ]  AC-10: Mỗi tệp đi đúng thứ tự: trùng vị trí `(folder_id, relative_path, original_name)` -> định dạng allowlist -> kích thước -> dung lượng kho -> đối chiếu SHA-256 với `personal_file.sha256` -> quét lại mã độc -> ghi object mới -> `document` trạng thái `clean` -> phát sự kiện số hoá.
- [ ]  AC-11: Kết quả từng tệp dùng mã trạng thái của `/ingest`: `INGESTED`, `SKIPPED ALREADY_EXISTS`, `INVALID_FORMAT`, `FILE_TOO_LARGE`, `MALWARE_DETECTED`, `WAREHOUSE_CAPACITY_EXCEEDED`, và mã mới `SOURCE_NOT_FOUND` khi tệp cá nhân đã bị xoá lúc tới lượt xử lý.
- [ ]  AC-12: Tệp nhiễm mã độc (quét lại) không vào kho dù đã qua ClamAV lúc tải lên cá nhân.
- [ ]  AC-13: SHA-256 đọc lại không khớp `personal_file.sha256` thì tệp đó thất bại, không tạo `document`.
- [ ]  AC-14: Dung lượng kho >= 95% thì chặn nạp mới (`WAREHOUSE_CAPACITY_EXCEEDED`); >= 85% có cảnh báo như luồng hiện có.
- [ ]  AC-15: Một tệp lỗi không làm hỏng cả lượt; lượt bị gián đoạn chạy lại được, tệp đã vào bị `SKIPPED` theo luật trùng.

### Sao chép, hậu kiểm và số hoá

- [ ]  AC-16: Là sao chép: tệp cá nhân gốc giữ nguyên; bản kho là object SeaweedFS mới (key trong vùng kho, không dùng chung key); không có tuỳ chọn xoá bản cá nhân.
- [ ]  AC-17: Sao chép thực hiện phía server, không đi qua trình duyệt; UI không bị chặn với lượt nhiều tệp.
- [ ]  AC-18: `document.uploader_sub` = người thực hiện bổ sung; mặc định chỉ người nạp thấy tài liệu.
- [ ]  AC-19: Tài liệu sau đó đi y hệt tài liệu nạp thường: OCR/STT -> rà soát/xác nhận (hoặc tự xác nhận sau `ocr_stt.auto_confirm_hours`) -> lập chỉ mục -> hiển thị theo phạm vi nhóm.

### Bổ sung lại và đánh dấu

- [ ]  AC-20: Cho phép bổ sung lại cùng một tệp cá nhân; trùng vị trí -> `SKIPPED ALREADY_EXISTS`; trùng nội dung ở vị trí khác -> vẫn nạp, kèm cảnh báo `duplicateOf`.
- [ ]  AC-21: UI kho cá nhân đánh dấu tệp "đã bổ sung" dựa trên `source_personal_file_id` của tài liệu kho chưa bị xoá; xoá tài liệu kho thì dấu biến mất.

### Truy vết, nhật ký, dung lượng

- [ ]  AC-22: `document` lưu nguồn (`source_kind = 'personal'`, `source_personal_file_id`, không FK cứng); xoá tệp cá nhân sau đó không ảnh hưởng tài liệu kho.
- [ ]  AC-23: `audit_log` ghi mỗi hành động bổ sung: id tệp nguồn, tên tệp, kích thước, id tài liệu mới, thư mục đích, kết quả; **không** ghi đường dẫn/tên thư mục cá nhân.
- [ ]  AC-24: Bản kho tính vào dung lượng kho chung, không trừ hạn mức cá nhân; thao tác không bao giờ bị chặn vì hạn mức cá nhân; bản cá nhân vẫn tính hạn mức cho tới khi người dùng xoá.

## Ngoài phạm vi (Out of scope)

- Chiều ngược lại (kho chung -> cá nhân).
- Chia sẻ nhóm ngay lúc bổ sung (làm sau qua FR-29); gán nhãn (FR-15).
- Tuỳ chọn "xoá bản cá nhân sau khi bổ sung" (mở rộng sau).
- Thay đổi ma trận quyền, tạo thư mục kho mới.

## Phụ thuộc / Ghi chú kiến trúc liên quan

- ADR 1 (sao chép), 2 (đủ kiểm tra FR-11/12), 3 (người nạp, chỉ R1, chỉ `note`), 4 (tệp/thư mục, bổ sung lại), 5 (nguồn gốc + audit), 6 (dung lượng).
- Phụ thuộc: `warehouse-structure.md` (cây thư mục, ngưỡng 85/95%), `data-ingestion.md` (batch 500 tệp/5 GB), `personal-storage-quota.md`, `digitization-review.md` QĐ 5, `group-management-sharing.md` QĐ 6.
- Tái dùng `DocumentsService.ingestBatchFile`, `UploadBatch`/`BatchKind` (thêm `PERSONAL`).

## Câu hỏi mở cho DEV (đã chốt với người dùng 2026-09-29)

- [X]  Endpoint: **mới** `POST /api/ingest/from-personal`, body `{targetFolderId, note?, fileIds[], folderIds[]}`; server tự duyệt đệ quy thư mục, tính `relative_path`, kiểm tra sở hữu.
- [X]  Migration: Flyway thêm `source_kind` VARCHAR NULL + `source_personal_file_id` UUID NULL (không FK) + partial index `(source_personal_file_id) WHERE deleted_at IS NULL`; API danh sách cá nhân trả cờ `addedToWarehouse` qua `EXISTS` theo trang, không denormalize lên `personal_file`.
- [X]  Sao chép: **streaming qua backend** (đọc object cá nhân → SHA-256 + ClamAV song song → ghi key kho mới), không dùng S3 CopyObject; tách phần lõi của `ingestBatchFile` nhận `Supplier<InputStream>` + metadata.
- [X]  Tệp cá nhân bị xoá giữa lượt: kiểm tra `deleted_at` khi bắt đầu xử lý từng tệp → mã mới `SOURCE_NOT_FOUND` (FAILED), lượt vẫn chạy tiếp; xoá mềm xảy ra khi đang stream thì vẫn nạp.
- [X]  Giới hạn 500 tệp / 5 GB: tính **sau khi server lọc** (tệp ngoài allowlist/quá cỡ ghi `INVALID_FORMAT`/`FILE_TOO_LARGE`); vượt thì từ chối cả lượt `400 BATCH_LIMIT_EXCEEDED` trước khi tạo batch; client cảnh báo sớm theo cùng cách tính.
