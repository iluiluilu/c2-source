---
description: DEV - viết tech spec chi tiết dựa trên user story của BA
model: opus
---
Dùng @dev subagent để thực hiện nhiệm vụ sau: đọc user story tại đường dẫn
được cung cấp, sau đó viết tech spec (data model, API, luồng xử lý, test plan,
task breakdown) và ghi ra `docs/03-dev/specs/<slug>.md` (dùng cùng `<slug>`
với file BA).

Dùng template @docs/templates/spec-template.md làm khung.

**Chưa implement code ở bước này.**

Tham số: $ARGUMENTS (đường dẫn file story, ví dụ
`docs/02-ba/stories/<slug>.md`, kèm yêu cầu bổ sung nếu có)

Sau khi ghi file, chỉ trả lời bằng: đường dẫn file đã tạo + tóm tắt task
breakdown chính + rủi ro kỹ thuật (nếu có). Không in lại toàn bộ nội dung file.
