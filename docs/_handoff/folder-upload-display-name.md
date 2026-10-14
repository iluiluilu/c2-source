## Handoff: folder-upload-display-name

### Đã chốt
- QA đã nghiệm thu xong (2026-10-01): kết luận **PASS WITH ISSUES**, 29/29 AC Pass (AC-22 kèm lưu ý N3), không có vấn đề chặn.
- Test đã chạy: web `tsc` + `lint` sạch, vitest 363 xanh; backend unit 102 xanh; 6 IT thuộc phạm vi feature xanh (69 test).
- Số liệu trên do subagent `qa` báo, chưa được kiểm lại độc lập trong session chính.

### Câu hỏi mở / rủi ro (non-blocking, cần người dùng/DEV quyết)
- N1: name-check kho chung chỉ kiểm quyền `document.upload` + thư mục tồn tại, chưa kiểm quyền ghi theo thư mục. DEV xác nhận kho chung không có ACL ghi theo thư mục.
- N2: `V34` lệch spec có chủ đích (dấu phân cách `chr(0)` → `'/'`; unique loại thêm `status='rejected'`). Cập nhật spec mục 2 và Q3 cho khớp code.
- N3: `TrashService.restore` thua race unique sau `saveAndFlush` thì trả 409, không sinh lại tên; spec yêu cầu retry tối đa 3 lần.
- N4: IT rate limit 429 chỉ phủ `/ingest/name-check`; 2 endpoint còn lại chỉ có unit test.
- N5: DoD (mục 11) và security checklist (mục 8) trong spec chưa tick; chưa có metric Micrometer (mục 7).
- N6: chưa test tay kéo-thả trên Chrome/Edge/Firefox/Safari (R6); AC-1, AC-2, AC-27 mới chỉ có bằng chứng vitest.
- Chưa chạy toàn bộ 33 IT; 4 IT khác có sửa đổi chưa chạy: DigitizationDispatchIT, NotesControllerIT, DocTypeAssignmentIT, UploadCleanupJobIT.

### File cần đọc tiếp
- docs/04-qa/reports/folder-upload-display-name.md
- docs/03-dev/specs/folder-upload-display-name.md
- docs/02-ba/stories/folder-upload-display-name.md

### Bước kế tiếp đề xuất
- Người dùng quyết N1–N6 cái nào cần sửa trước khi merge (đặc biệt N3, N1).
- Nếu sửa: `/dev-implement docs/03-dev/specs/folder-upload-display-name.md` (chỉ các mục N đã chọn), rồi `/qa-review` lại.
- Chạy full 33 IT + 4 IT chưa chạy trước khi merge; test tay trình duyệt (N6).
- Cập nhật `docs/00-overview/features.md` cho slug này.
