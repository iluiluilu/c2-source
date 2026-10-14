---
description: SA - khảo sát codebase hiện có (brownfield) trước khi ra quyết định kiến trúc
model: opus
---
Dùng @sa subagent để thực hiện nhiệm vụ sau: khảo sát codebase HIỆN CÓ ở mức
tổng quan để làm nền cho các quyết định kiến trúc sau này (dự án brownfield).

Phạm vi khảo sát (dùng grep/glob có mục tiêu, KHÔNG đọc toàn bộ repo):
- Ngôn ngữ, framework, build tool, cách chạy test/lint.
- Cấu trúc thư mục chính & module lớn.
- Data store, integration bên ngoài, entrypoint (API/CLI/UI).
- Convention code, ràng buộc kỹ thuật rõ ràng.
- Nợ kỹ thuật / rủi ro dễ thấy.

Trọng tâm (nếu có): $ARGUMENTS

Ghi kết quả ra `docs/01-sa/architecture/_codebase-overview.md` (giữ
`> Status: Draft`), ngắn gọn có cấu trúc. File này là tham chiếu chung cho SA
ở các feature sau, KHÔNG phải ADR cho một feature cụ thể.

Sau khi ghi file, chỉ trả lời bằng: đường dẫn file + tóm tắt 5-8 dòng (stack,
điểm mạnh/yếu, rủi ro chính). Không in lại toàn bộ nội dung file.
