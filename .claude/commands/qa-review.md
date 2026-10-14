---
description: QA - nghiệm thu code so với AC của story và task breakdown của spec
model: sonnet
---
Dùng @qa subagent để thực hiện nhiệm vụ sau: đọc user story và tech spec tại
các đường dẫn được cung cấp, sau đó nghiệm thu code đã implement so với
Acceptance Criteria (story) và task breakdown / test plan (spec).

Dùng template @docs/templates/qa-report-template.md làm khung.

Tham số: $ARGUMENTS (đường dẫn file story và/hoặc spec, ví dụ
`docs/02-ba/stories/<slug>.md docs/03-dev/specs/<slug>.md`)

Yêu cầu:
- Định vị code liên quan tới từng AC bằng grep/glob, không đọc toàn repo.
- Chạy test/lint có sẵn của project để lấy bằng chứng thực tế.
- Với mỗi AC: đánh dấu Pass/Fail kèm bằng chứng (file:line, tên test, log).
- **Không sửa code** — chỉ ghi report ra `docs/04-qa/reports/<slug>.md`.
- Sau khi ghi file, chỉ trả lời bằng: đường dẫn report + kết luận
  (PASS / PASS WITH ISSUES / FAIL) + danh sách vấn đề chặn (nếu có). Không in
  lại toàn bộ report.
