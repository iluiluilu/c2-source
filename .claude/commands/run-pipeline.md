---
description: Chạy bán tự động cả pipeline SA→BA→DEV→QA cho một feature (có cổng duyệt)
model: sonnet
---
Dùng @orchestrator subagent để điều phối cả pipeline SA → BA → DEV → QA cho
feature được mô tả dưới đây, delegate lần lượt cho `@sa`, `@ba`, `@dev`, `@qa`.

Feature: $ARGUMENTS

Bắt buộc tuân theo (như định nghĩa agent orchestrator):
- Tự đặt `<slug>` kebab-case; nếu mô tả mơ hồ, hỏi người dùng.
- DỪNG ở mỗi cổng sau SA, BA, DEV-spec để người dùng review và đổi
  `> Status: Draft → Approved`. KHÔNG tự đổi Status. Chỉ tiếp tục khi được xác nhận.
- Ở mỗi cổng, nếu file vừa tạo còn câu hỏi mở chưa `[x]`: báo số câu và in lệnh
  `/resolve-questions <đường dẫn file>` để người dùng chạy trước khi Approved
  (không tự chạy command này).
- Sau implement, gọi `@qa`. Nếu FAIL/blocking → gọi lại `@dev` sửa rồi `@qa`
  lại, tối đa 2 vòng; vẫn FAIL thì dừng và báo cáo.
- Kết thúc: cập nhật `docs/00-overview/features.md` và tổng kết đường dẫn
  artifact từng tầng + kết luận QA.

Giữ phản hồi ngắn gọn ở mỗi bước.
