# User Story: chunked-file-upload-02-personal-chunked-upload

> File: `docs/02-ba/stories/chunked-file-upload-02-personal-chunked-upload.md` — Owner: BA
> Nguồn kiến trúc: `docs/01-sa/architecture/chunked-file-upload.md`
> Status: Approved

## User Story

**As a** người dùng đang tải tệp lên khu vực lưu trữ cá nhân
(`personal/batches/...`, xem `personal-storage-quota.md` FR-14)
**I want** tệp lớn (> 20MB) được tải theo cơ chế chia phần (chunk) và có thể
resume từ phần đã nhận, giống hệt trải nghiệm ở luồng ingest
**So that** tôi cũng được hưởng lợi ích chống mất công tải lại khi tải tệp cá
nhân lớn trên mạng không ổn định, không phải chịu hành vi kém hơn chỉ vì đây
là luồng cá nhân thay vì luồng ingest.

## Acceptance Criteria

```
Given tôi chọn tải một tệp cá nhân có kích thước > 20MB
When tôi bắt đầu tải
Then hệ thống dùng luồng chunked (init → chunk → complete) — cùng cơ chế và
     cùng ngưỡng/chunk size với luồng ingest
```

```
Given một phiên tải chunked đang diễn ra ở luồng personal và bị gián đoạn
When tôi thử tải lại
Then hệ thống chỉ gửi lại các chunk chưa được xác nhận, không tải lại từ đầu
```

```
Given tất cả chunk của một tệp cá nhân đã nhận đủ
When bước complete được gọi
Then hệ thống ráp tệp rồi gọi đúng `PersonalService.uploadFileInBatch` —
     giữ nguyên hành vi hiện tại của luồng personal (bỏ qua bước check
     allowlist định dạng), vẫn chạy SHA-256 → ClamAV đồng bộ → lưu
     SeaweedFS như luồng ingest
```

```
Given một `upload_session` được tạo cho batch kind `personal`
When bước complete xử lý phiên này
Then hệ thống dispatch đúng theo `BatchKind` của `upload_batch` liên kết,
     KHÔNG gọi nhầm `DocumentsService.ingestBatchFile` (luồng ingest, có
     check allowlist) cho phiên personal
```

- [ ] AC-1: Tệp cá nhân > 20MB đi luồng chunked; ≤ 20MB tiếp tục đi luồng
      whole-file hiện có của `personal/batches/...`, không đổi hành vi.
      Ngưỡng và chunk size dùng chung giá trị system-config với luồng ingest
      (không cấu hình riêng cho personal).
- [ ] AC-2: Resume sau gián đoạn ở luồng personal hoạt động đúng như AC-4 của
      story ingest (`chunked-file-upload-01`) — chỉ gửi lại phần chưa nhận.
- [ ] AC-3: Bước complete cho phiên `BatchKind = personal` gọi
      `PersonalService.uploadFileInBatch`, **không** áp dụng bước check
      allowlist định dạng — đúng hành vi hiện tại đã chốt ở Story 1 AC-2 của
      `personal-storage-quota.md`.
- [ ] AC-4: Bước complete cho phiên `BatchKind = ingest` vẫn gọi
      `DocumentsService.ingestBatchFile` (có check allowlist) — không bị ảnh
      hưởng bởi việc thêm luồng personal.
- [ ] AC-5: Có test tích hợp xác nhận việc dispatch theo `BatchKind` là đúng
      cho cả hai trường hợp (không dispatch nhầm chiều nào).
- [ ] AC-6: Tệp nhiễm mã độc ở luồng personal bị từ chối giống hệt hành vi
      fail-closed hiện có (không lưu, báo lỗi rõ ràng).
- [ ] AC-7: Quota lưu trữ cá nhân chỉ được tính (cộng vào dung lượng đã dùng)
      sau khi phiên `complete` thành công — giống hệt hành vi hiện tại của
      luồng whole-file. Việc mở phiên `init` hoặc gửi chunk KHÔNG đặt trước
      (reserve) quota. (Quyết định xác nhận với người duyệt — chấp nhận rủi
      ro nhiều phiên lớn mở đồng thời có thể tạm thời vượt quota trước khi
      complete; không xử lý trong story này.)

## Ngoài phạm vi (Out of scope)

- Thay đổi quy tắc quota lưu trữ cá nhân — không thuộc phạm vi tính năng
  chunk, xem `personal-storage-quota.md` cho quy tắc quota hiện có (nếu có
  xung đột giữa quota và cơ chế chunk, đó là câu hỏi mở cho DEV bên dưới).
- Thêm/bớt bước check allowlist định dạng cho luồng personal — giữ nguyên
  hành vi bỏ qua allowlist đã chốt trước đó, tính năng chunk không thay đổi
  quyết định nghiệp vụ này.
- Cleanup phiên bỏ dở — xem `chunked-file-upload-03-session-cleanup.md`.

## Phụ thuộc / Ghi chú kiến trúc liên quan

- Tham chiếu ghi chú "áp dụng cho cả hai luồng (ingest + personal)" ở
  Quyết định 2, file
  `docs/01-sa/architecture/chunked-file-upload.md`: lớp chunk-transport
  KHÔNG được tự ý thêm/bớt bước xử lý nghiệp vụ giữa hai luồng, chỉ ráp tệp
  rồi gọi đúng service đã có.
- Tham chiếu rủi ro "Bước complete xử lý sai BatchKind" ở mục 6 file SA (mức
  Trung bình) — trực tiếp là AC-5 của story này.
- Phụ thuộc vào story `chunked-file-upload-01-ingest-chunked-upload.md` cho
  phần giao thức chunk/resume dùng chung (init/chunk/status/complete) — hai
  story nên được DEV lên spec/implement cùng một đợt vì dùng chung entity
  `upload_session` và giao thức transport.

## Câu hỏi mở cho DEV

Không còn câu hỏi mở chặn duyệt cho story này. Đã xác nhận với người duyệt
(2026-08-21):

- File SA nguồn (`docs/01-sa/architecture/chunked-file-upload.md`) đã
  `Status: Approved` — không phải Draft như ghi nhận trước đó, đã xác minh
  lại trực tiếp trên file.
- Thời điểm tính quota lưu trữ cá nhân: chỉ tính khi `complete` — đã đưa vào
  AC-7 ở trên, không cần hỏi thêm.
