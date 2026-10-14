---
description: BA - viết user story + AC dựa trên file kiến trúc của SA
model: sonnet
---
Dùng @ba subagent để thực hiện nhiệm vụ sau: đọc file kiến trúc tại đường dẫn
được cung cấp, sau đó viết user story + acceptance criteria và ghi ra
`docs/02-ba/stories/<slug>.md` (dùng cùng `<slug>` với file SA).

Dùng template @docs/templates/story-template.md làm khung.

Tham số: $ARGUMENTS (đường dẫn file kiến trúc, ví dụ
`docs/01-sa/architecture/<slug>.md`, kèm yêu cầu bổ sung nếu có)

Sau khi ghi file, chỉ trả lời bằng: đường dẫn file đã tạo + tóm tắt story
chính + câu hỏi mở cho DEV (nếu có). Không in lại toàn bộ nội dung file.
