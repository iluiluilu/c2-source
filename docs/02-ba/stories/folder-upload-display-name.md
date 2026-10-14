# User Story: folder-upload-display-name

> File: `docs/02-ba/stories/folder-upload-display-name.md` — Owner: BA
> Nguồn kiến trúc: `docs/01-sa/architecture/folder-upload-display-name.md`
> Status: Approved

## User Story

**As a** người dùng (kho chung `/upload` và kho cá nhân `/personal`)
**I want** kéo-thả nhiều thư mục và tệp lẻ trong một lượt, mỗi tệp được lưu với
một tên định danh không trùng trong vị trí lưu
**So that** tôi tải cả cây thư mục mà không gặp popup gốc của trình duyệt, không
mất cấu trúc thư mục, và tải lại tệp trùng tên không bị bỏ qua hay ghi đè.

Story phụ (cùng phạm vi):
- **As a** người dùng chuyển tệp từ kho cá nhân sang kho chung, **I want** cùng cơ chế kiểm tra trùng và đổi tên như khi tải lên.
- **As a** người dùng khôi phục tệp từ thùng rác, **I want** được hỏi "Đổi tên" hoặc "Dừng khôi phục" khi tên đã bị chiếm.

## Acceptance Criteria

### A. Kéo-thả (C1, C2, QĐ1)

- [ ] AC-1: Given trang `/upload` hoặc `/personal`, When tôi thả cùng lúc nhiều thư mục và tệp lẻ vào vùng thả, Then tất cả tệp (duyệt đệ quy) được gộp vào **1 batch** và hiện trong danh sách chờ tải.
- [ ] AC-2: Given giao diện tải lên, Then không còn nút/`<input webkitdirectory>` chọn thư mục (không hiện popup gốc "Upload N files to this site?"); nút "Chọn tệp" chỉ chọn tệp lẻ.
- [ ] AC-3: Given thả thư mục `HoSo` chứa `2024/a.pdf` và `b.pdf`, Then `relative_path` của `a.pdf` = `HoSo/2024`, của `b.pdf` = `HoSo`; tệp lẻ có `relative_path` rỗng. Chỉ để hiển thị, không tạo thư mục con thật.
- [ ] AC-4: Given thư mục có hơn 100 tệp (`readEntries` phân trang), Then mọi tệp đều được liệt kê và giao diện không bị treo.
- [ ] AC-5: Given kho cá nhân, Then tệp lưu kèm `relative_path` (trước đây bị làm phẳng).

### B. Tên lưu `display_name` và kiểm tra trùng (C3, C4, C5, QĐ3)

- [ ] AC-6: Given lượt tải, When client gọi `POST /ingest/name-check` (hoặc `/personal/name-check`) với ≤ 1000 tệp/lần (chia lô nếu nhiều hơn), Then nhận `[{taken}]` đúng thứ tự; `taken` khi tên (không phân biệt hoa/thường, sau NFC) trùng tên tệp còn sống cùng thư mục đích + `relative_path`.
- [ ] AC-7: Given tệp không trùng, Then `display_name` = tên gốc (NFC), không có hậu tố.
- [ ] AC-8: Given tệp trùng (API trả `taken`, hoặc trùng tệp khác trong cùng lượt, hoặc bị 409, hoặc API kiểm tra lỗi/timeout), Then `display_name` = `<tên gốc>_<yyyyMMddHHmmssSSS>-<6 ký tự a-z0-9>.<đuôi>` (giờ `Asia/Ho_Chi_Minh`; random từ `crypto.getRandomValues`); không dùng hậu tố "(2)".
- [ ] AC-9: Given `a.tar.gz` → đuôi `gz`; `.env` và tệp không đuôi → `<tên>_<suffix>` không đuôi; đuôi > 16 ký tự coi như không đuôi.
- [ ] AC-10: Given tên vượt 512 ký tự, Then chỉ cắt phần tên gốc (theo code point, không cắt đôi ký tự), giữ nguyên `_<suffix>` và `.<đuôi>`.
- [ ] AC-11: Given tải lại thư mục đã tải trước đó, Then mọi tệp được lưu thành bản mới có hậu tố; **không** còn trạng thái `SKIPPED ALREADY_EXISTS` theo tên; cảnh báo trùng `sha256` (`duplicatesBySha`) giữ nguyên; UI hiện số tệp bị đổi tên.
- [ ] AC-12: Given API kiểm tra lỗi/timeout, Then client không chặn tải: coi mọi tệp là trùng và dựa vào 409 khi ghi.

### C. Server kiểm tra và xung đột (QĐ2, QĐ4)

- [ ] AC-13: Given `displayName` rỗng/không NFC/> 512 code point/chứa `/`, `\`, ký tự điều khiển, U+2028/2029, ký tự bidi, hoặc chỉ khoảng trắng/`.`/`..`, hoặc không bằng `NFC(original_name)` cũng không khớp mẫu hậu tố hợp lệ, Then server trả `422 INVALID_DISPLAY_NAME` kèm lý do.
- [ ] AC-14: Given hai tệp cùng `(thư mục, relative_path, lower(display_name))` còn sống, When ghi tệp thứ hai, Then `409 DISPLAY_NAME_CONFLICT` riêng tệp đó (các tệp khác trong lượt không bị ảnh hưởng); client tự sinh lại tên, tối đa **3 lần/tệp**, quá thì tệp báo lỗi.
- [ ] AC-15: Given tải chunked bị 409 ở commit, Then commit lại với tên mới, không tải lại chunk; với multipart thì gửi lại cả tệp.
- [ ] AC-16: Given tên trùng chỉ với tệp trong thùng rác (`deleted_at` không null), Then không tính là trùng.
- [ ] AC-17: Given name-check gọi vào thư mục không có quyền ghi, Then `403`; API chỉ trả `taken`, có giới hạn số tệp và rate limit.

### D. Kho cá nhân sang kho chung (QĐ5)

- [ ] AC-18: Given chọn tệp/thư mục kho cá nhân để chuyển, When client gọi `POST /ingest/from-personal/name-check`, Then nhận `[{personalFileId, relativePath, originalName, taken}]`; sau đó gọi `from-personal` kèm `displayNames`.
- [ ] AC-19: Given `relative_path` kho chung = đường dẫn cây thư mục cá nhân từ thư mục được chọn, nối thêm `personal_file.relative_path` nếu khác rỗng (`A/B/HoSo/2024`).
- [ ] AC-20: Given `displayName` sai → item `FAILED INVALID_DISPLAY_NAME`; thiếu → `FAILED DISPLAY_NAME_MISSING`; worker vướng unique → `FAILED DISPLAY_NAME_CONFLICT`, client `PUT .../items/{itemId}/display-name` đưa item về `PENDING` (tối đa 3 lần/item). Không còn `SKIPPED ALREADY_EXISTS` theo tên.

### E. Khôi phục thùng rác (QĐ6)

- [ ] AC-21: Given `display_name` của tệp khôi phục đã có ở vị trí đích (tệp còn sống), Then `409 NAME_CONFLICT` và UI hiện popup đúng 2 lựa chọn: "Đổi tên" / "Dừng khôi phục" (đóng popup = dừng, tệp ở lại thùng rác). Không còn ghi đè hay chọn thư mục khác khi trùng tên (luồng `FOLDER_MISSING` giữ nguyên).
- [ ] AC-22: Given chọn "Đổi tên", Then server gán cả `display_name` và `original_name` = tên có hậu tố `_<timestamp>-<random6>`; vướng unique thì sinh lại tối đa 3 lần rồi báo lỗi; tìm kiếm và tải về dùng tên mới sau reindex; audit ghi tên cũ/mới.
- [ ] AC-23: Given khôi phục nhiều tệp có nhiều tệp trùng, Then hiện **1 popup** nêu số tệp trùng, 2 nút và ô "Áp dụng cho tất cả"; bỏ chọn ô thì hỏi từng tệp.

### F. Hiển thị, tìm kiếm, tải về (C6, C7, QĐ8, QĐ9)

- [ ] AC-24: Given danh sách/chi tiết/tìm kiếm, Then cột tên hiện `original_name`; tooltip hiện thư mục đích + `relative_path` và `display_name`; `storage_key` không hiện cho bất kỳ vai trò nào.
- [ ] AC-25: Given tìm kiếm theo tên, Then chỉ khớp `original_name`.
- [ ] AC-26: Given tải tệp về (cả 2 kho), Then `Content-Disposition` dùng `original_name` (`filename*=UTF-8''`).
- [ ] AC-27: Given màn hình tải lên, Then mỗi dòng hiện: path trong máy, tên gốc, trạng thái; `display_name` ở tooltip, cập nhật khi `201` (đổi nếu 409).

### G. Bổ sung (chốt 2026-09-30, `/resolve-questions`)

- [ ] AC-28: Given `original_name` > 512 code point, When init upload session / upload multipart / from-personal, Then server trả `422 INVALID_DISPLAY_NAME` lý do `ORIGINAL_NAME_TOO_LONG` **trước khi tải chunk** (không để lỗi DB ở commit); client cũng chặn trước và báo lỗi riêng dòng đó.
- [ ] AC-29: Given một người dùng gọi các API name-check (`/ingest/name-check`, `/personal/name-check`, `/ingest/from-personal/name-check`) quá **30 request/phút/endpoint**, Then server trả `429` và log WARN; client coi 429 như API kiểm tra lỗi (AC-12). Giới hạn cấu hình được qua `application.yml`.

## Ngoài phạm vi (Out of scope)

- Đổi tên thủ công; tạo thư mục con thật từ `relative_path`.
- Đổi `/ingest/batches*` và giao thức chunked ngoài `displayName`/`relativePath`.
- Retry/backoff mạng, pipeline quét, AC-3a.
- Dữ liệu cũ (chưa go live: không backfill).
- Dedup `sha256`; logic giữ object của `TrashPurgeService` (R12: object mồ côi cùng `sha256` → ghi bug riêng trong `bugs/`, không sửa trong feature này).
- Tải thư mục trên thiết bị cảm ứng (chấp nhận, R5).

## Phụ thuộc / Ghi chú kiến trúc liên quan

- QĐ1 kéo-thả; QĐ2 `V34` + bỏ dedup theo tên (drop `UQ_document_folder_relpath_name_clean`); QĐ3 name-check + sinh tên client; QĐ4 `DisplayNameValidator`; QĐ5 from-personal; QĐ6 khôi phục; QĐ7 giữ `display_name` tách `storage_key`; QĐ8 OpenSearch thêm field `display_name`.
- Rủi ro cần lưu ý: R4 (tải lại tạo bản sao tốn dung lượng), R8 (lệch JS–Java, dùng test vector JSON chung), R13, R14 (unique trên `md5`).
- Các story/spec cũ bị thay thế: xem mục 7 file SA (BA `data-ingestion` AC-8 và AC trùng tên, `personal-to-warehouse` AC-5, `delete-restore-trash`).
- Spec DEV gộp 1 file `docs/03-dev/specs/folder-upload-display-name.md` (< 300 dòng), task chia nhóm BE/FE (SPEC Q1; thay quyết định tách 2 file theo SA R2).
- Spec backend phải có task bắt buộc sửa `IngestControllerIT` (~353), `PersonalToWarehouseIT` (~502), `TrashServiceTest`, `TrashRestoreConflictControllerIT` theo phương án B/QĐ6 (SA mục 7).
- Purge (bia mộ): giữ nguyên `display_name` (không rỗng hoá, thoả `NOT NULL`); có `deleted_at` nên nằm ngoài unique partial, không chiếm tên (SA mục 8, QĐ6).

## Câu hỏi mở cho DEV

- [x] Tách spec theo SA R2: `-01-backend`, `-02-frontend`. → **Chốt:** tách 2 file spec (cập nhật: Phụ thuộc) — *đã thay bởi SPEC-folder-upload-display-name Q1 (2026-09-30): gộp 1 file.*
- [x] `original_name` > 512: `upload_session` cho 1024, cần xác định kết quả lỗi khi vượt. → **Chốt:** `422 INVALID_DISPLAY_NAME` / `ORIGINAL_NAME_TOO_LONG` chặn sớm trước khi tải chunk (cập nhật: AC-28)
- [x] Sửa các test `IngestControllerIT` (~353), `PersonalToWarehouseIT` (~502), `TrashServiceTest`, `TrashRestoreConflictControllerIT` theo phương án B/QĐ6. → **Chốt:** task bắt buộc trong spec backend (cập nhật: Phụ thuộc)
- [x] Kiểm tra `TrashPurgeService` (R12) về object mồ côi cùng `sha256`. → **Chốt:** ngoài phạm vi, ghi bug riêng (cập nhật: Ngoài phạm vi)
- [x] Giá trị cụ thể của giới hạn rate limit name-check (SA chưa nêu số). → **Chốt:** 30 request/phút/người/endpoint, in-memory, `429`, cấu hình qua yml (cập nhật: AC-29)
- [x] Bia mộ sau purge phải vẫn thoả `display_name NOT NULL`. → **Chốt:** giữ nguyên `display_name`, không chiếm tên nhờ `deleted_at` (cập nhật: Phụ thuộc)
