# User Story: chunked-file-upload-03-session-cleanup

> File: `docs/02-ba/stories/chunked-file-upload-03-session-cleanup.md` — Owner: BA
> Nguồn kiến trúc: `docs/01-sa/architecture/chunked-file-upload.md`
> Status: Approved

## User Story

**As a** quản trị viên vận hành hệ thống
**I want** các phiên tải chunked bị bỏ dở (client đóng tab, mất mạng vĩnh
viễn, không quay lại) tự động được dọn dẹp cả ở PostgreSQL (`upload_session`)
lẫn temp file trên đĩa server sau một thời gian không hoạt động
**So that** đĩa server và dữ liệu trạng thái không bị tích rác vô thời hạn,
tránh sự cố đầy đĩa hoặc dữ liệu mồ côi khó dọn thủ công.

## Acceptance Criteria

```
Given một `upload_session` ở trạng thái OPEN không nhận thêm chunk nào trong
     24 giờ kể từ lần nhận chunk gần nhất
When job dọn dẹp định kỳ chạy
Then session được chuyển sang trạng thái EXPIRED (hoặc xoá) và temp file
     tương ứng trên đĩa server bị xoá
```

```
Given một `upload_session` đang OPEN và liên tục nhận chunk mới trong vòng
     24 giờ (chưa từng im lặng quá 24h)
When job dọn dẹp định kỳ chạy
Then session này KHÔNG bị dọn, `expires_at` được gia hạn mỗi khi có chunk mới
     tới
```

```
Given một `upload_session` đã hoàn tất (trạng thái COMPLETED) sau khi gọi
     complete thành công
When job dọn dẹp định kỳ chạy
Then session COMPLETED không bị coi là rác cần dọn (đã xử lý xong, không còn
     temp file cần giữ vì đã ráp và chuyển vào pipeline lưu trữ)
```

```
Given client gửi chunk tới một `upload_session` đã EXPIRED hoặc đã bị job dọn
When request chunk đó tới server
Then hệ thống từ chối với lỗi rõ ràng (phiên đã hết hạn), không âm thầm tạo
     lại session hoặc ghi chunk vào file đã bị xoá
```

- [ ] AC-1: Session `OPEN` không nhận chunk mới trong 24h bị job dọn định kỳ
      chuyển trạng thái/xoá, kèm xoá temp file trên đĩa — không để lại rác.
- [ ] AC-2: `expires_at` của session được gia hạn (không cố định từ lúc tạo)
      mỗi khi server nhận thêm một chunk hợp lệ cho session đó.
- [ ] AC-3: Session `COMPLETED` hoặc `ABORTED` không bị job dọn xử lý lại như
      rác cần xoá thêm lần nữa (tránh xoá nhầm tệp đã lưu thành công, hoặc xử
      lý dư thừa với session đã đóng).
- [ ] AC-4: Chunk gửi tới session đã hết hạn/không tồn tại bị từ chối với
      thông báo lỗi rõ ràng cho client, không tạo trạng thái không nhất quán.
- [ ] AC-5: Job dọn dẹp `upload_session` là phần **mở rộng của scheduled job
      dọn `UploadBatch` hết hạn đã có** (cùng một job, thêm bước quét
      `upload_session`), không phải job/scheduled task riêng biệt — để một
      đội vận hành chỉ cần theo dõi một loại job dọn dẹp. (Quyết định xác
      nhận với người duyệt.)
- [ ] AC-6: Có log/dấu vết khi job dọn dẹp chạy và số lượng session/temp file
      đã xoá mỗi lần chạy, phục vụ theo dõi vận hành.

## Ngoài phạm vi (Out of scope)

- Cảnh báo/alert tự động khi job dọn dẹp không chạy đúng chu kỳ — thuộc phạm
  vi vận hành hệ thống nói chung, không phải quyết định kiến trúc của tính
  năng này (ghi nhận ở mục 6 file SA, không phải AC bắt buộc ở đây).
- Thời gian chờ 24h là giá trị đề xuất ban đầu của SA, không phải kết quả đo
  thử — nếu cần đổi, đó là thay đổi tham số cấu hình, không phải thay đổi
  logic của story này.
- Dọn dẹp cho `UploadBatch` (khác với `upload_session`) — cơ chế đã có sẵn
  trước tính năng này, không thuộc phạm vi story.

## Phụ thuộc / Ghi chú kiến trúc liên quan

- Tham chiếu Quyết định 3 (Cleanup phiên bỏ dở) ở
  `docs/01-sa/architecture/chunked-file-upload.md`: mirror cơ chế `expiresAt`
  của `UploadBatch`, job định kỳ quét và xoá session + temp file quá hạn.
- Tham chiếu rủi ro "Temp file/upload_session bỏ dở tích tụ nếu job cleanup
  chạy sai hoặc bị tắt" ở mục 6 file SA (mức Thấp).
- Phụ thuộc vào entity `upload_session` được định nghĩa ở Quyết định 2 (cột
  `status`, `expires_at`) — story này không tạo entity mới, chỉ định nghĩa
  hành vi dọn dẹp trên entity đã có trong
  `chunked-file-upload-01-ingest-chunked-upload.md`.

## Câu hỏi mở cho DEV

Không còn câu hỏi mở chặn duyệt cho story này. Đã xác nhận với người duyệt
(2026-08-21):

- File SA nguồn (`docs/01-sa/architecture/chunked-file-upload.md`) đã
  `Status: Approved` — không phải Draft như ghi nhận trước đó, đã xác minh
  lại trực tiếp trên file.
- Job dọn `upload_session`: mở rộng job dọn `UploadBatch` hiện có (cùng một
  scheduled task) — đã đưa vào AC-5 ở trên, không cần hỏi thêm. DEV vẫn cần
  đọc code hiện tại của job dọn `UploadBatch` khi viết tech spec để xác định
  điểm mở rộng cụ thể.
