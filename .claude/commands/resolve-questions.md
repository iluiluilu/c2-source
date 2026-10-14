---
description: Review "Câu hỏi mở" của một artifact SA/BA/DEV-spec, đề xuất câu trả lời, hỏi xác nhận rồi cập nhật tài liệu
argument-hint: <đường dẫn file SA/BA/DEV-spec> [ghi chú thêm]
model: opus
---
Tham số: $ARGUMENTS

Chạy **trực tiếp trong session này**, KHÔNG giao cho subagent (`@sa`/`@ba`/`@dev`)
vì subagent không hỏi được người dùng giữa chừng.

## Các bước

1. **Kiểm tra đầu vào.** File phải nằm trong `docs/01-sa/architecture/`,
   `docs/02-ba/stories/` hoặc `docs/03-dev/specs/`; khác → dừng và báo. Nếu dòng
   `> Status:` đã là `Approved` → hỏi người dùng có muốn mở lại không (thay đổi
   sau khi duyệt cần duyệt lại).
2. **Thu thập câu hỏi.** Lấy các mục chưa đánh `[x]` trong mục câu hỏi mở của
   file ("Câu hỏi mở" ở SA/spec, "Câu hỏi mở cho DEV" ở BA), cộng các chỗ ghi
   `TBD`/`TODO`/`???` trong thân file. Không có gì → báo và dừng.
3. **Tìm căn cứ, đọc có mục tiêu.** Chỉ đọc: file tầng trên ghi ở header (nguồn
   kiến trúc / story), ADR liên quan trong
   `docs/01-sa/architecture/_codebase-overview.md` (nếu có) hoặc các file SA
   liên quan, và tài liệu yêu cầu/kỹ thuật được tham chiếu (dùng Grep, không
   đọc toàn bộ `docs/`).
4. **Đề xuất.** Với mỗi câu hỏi, chuẩn bị: câu trả lời đề xuất, lý do + nguồn
   (tài liệu + mục, ADR-n, AC-n), 1–2 phương án khác kèm trade-off. Phân loại:
   - **(a) Có căn cứ rõ** trong tài liệu đã chốt.
   - **(b) Cần người dùng quyết định** (không có căn cứ, hoặc là lựa chọn sở thích/chi phí).
   - **(c) Thuộc tầng trên**: câu trả lời làm tài liệu yêu cầu gốc hoặc file của bước trước
     sai/thiếu → không chốt tại file này, đưa sang bước 7.
   Không tự bịa yêu cầu mới; câu (b) không có căn cứ thì nói rõ là không có.
5. **Hỏi xác nhận.** Dùng tool AskUserQuestion, tối đa 4 câu mỗi lượt, câu ảnh
   hưởng lớn hỏi trước. Phương án đề xuất đặt đầu tiên, gắn "(Đề xuất)"; phần
   description ghi lý do + nguồn. Hỏi nhiều lượt nếu còn câu. Câu người dùng
   muốn để lại → giữ nguyên là câu hỏi mở. **Không ghi file trước khi được xác nhận.**
6. **Cập nhật file.**
   - Đưa quyết định vào đúng phần nội dung: SA → ADR/NFR/Ràng buộc/Rủi ro;
     BA → AC/Ngoài phạm vi/Phụ thuộc; spec → Data model/API/Luồng/Test plan/Task.
   - Giữ ID ổn định: không đánh số lại `ADR-n`/`AC-n` đã có; thêm mới ở cuối.
   - Đánh dấu câu hỏi: `- [x] <câu hỏi> → **Chốt:** <trả lời ngắn> (cập nhật: <mục>)`.
   - **Không** đổi dòng `Status`. **Không** sửa file nào khác ở bước này.
7. **Tầng trên.** Nếu có câu loại (c): liệt kê `file — mục — nội dung đề xuất`,
   hỏi người dùng có sửa ngay không (từng file). Chỉ sửa khi được đồng ý; nếu file
   tầng trên đang `Approved`, nhắc người dùng review lại file đó.
8. **Trả lời cuối** (ngắn, không in lại file): số câu đã chốt / còn mở / chuyển
   tầng trên, các mục đã sửa, bước kế tiếp: review file → đổi `Status: Approved`
   → `/handoff`.
