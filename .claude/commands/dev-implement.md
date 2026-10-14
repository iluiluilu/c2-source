---
description: DEV - implement code + test theo tech spec đã chốt
model: sonnet
---
Dùng @dev subagent để thực hiện nhiệm vụ sau: đọc tech spec tại đường dẫn
được cung cấp, sau đó implement code + test theo đúng task breakdown trong
spec.

Tham số: $ARGUMENTS (đường dẫn file spec, ví dụ
`docs/03-dev/specs/<slug>.md`, kèm yêu cầu bổ sung nếu có)

Yêu cầu:
- Implement từng task theo thứ tự trong spec.
- Chạy test/lint liên quan sau khi implement (nếu project có sẵn script).
- Nếu cần sửa đổi đáng kể so với spec đã chốt, dừng lại và báo rõ lý do trước
  khi tiếp tục.
- Kết thúc bằng tóm tắt ngắn: các file đã thay đổi, kết quả test, việc còn lại
  (nếu có). Không lặp lại toàn bộ nội dung spec.
