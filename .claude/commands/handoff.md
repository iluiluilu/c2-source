---
description: Tóm tắt ngắn phiên hiện tại để chuyển sang agent/bước kế tiếp
---
Tóm tắt cuộc hội thoại hiện tại thành một bản **handoff ≤ 30 dòng**, dùng
đúng cấu trúc sau (markdown):

```
## Handoff: <slug hoặc tên feature>

### Đã chốt
- ...

### Câu hỏi mở / rủi ro
- ...

### File cần đọc tiếp
- docs/.../<file>.md

### Bước kế tiếp đề xuất
- /ba-story ... | /dev-spec ... | /dev-implement ...
```

Yêu cầu:
- Không lặp lại toàn bộ nội dung file đã tạo trong phiên này, chỉ tham chiếu
  đường dẫn.
- Nếu có quyết định chưa chốt, ghi rõ vào "Câu hỏi mở / rủi ro" để agent kế
  tiếp hỏi lại người dùng, không tự giả định.
- Ghi bản tóm tắt này ra file `docs/_handoff/<slug>.md` (ghi đè nếu đã tồn
  tại) VÀ in trực tiếp trong câu trả lời — bước kế tiếp thường mở ở
  session/agent mới nên sẽ không thấy lại đoạn chat này.
- Khi mở session mới cho bước kế tiếp, nếu không có sẵn đoạn handoff được
  dán vào, đọc lại `docs/_handoff/<slug>.md` trước.
