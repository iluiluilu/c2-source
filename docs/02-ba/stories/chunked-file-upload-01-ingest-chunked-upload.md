# User Story: chunked-file-upload-01-ingest-chunked-upload

> File: `docs/02-ba/stories/chunked-file-upload-01-ingest-chunked-upload.md` — Owner: BA
> Nguồn kiến trúc: `docs/01-sa/architecture/chunked-file-upload.md`
> Status: Approved

## User Story

**As a** người dùng nghiệp vụ đang tải tệp lên một batch ingest
(`POST /ingest/batches/{id}/files`, giao diện `web/src/app/upload/page.tsx`)
**I want** tệp lớn (> 20MB) được tải theo cơ chế chia phần (chunk) và có thể
tiếp tục (resume) từ phần đã nhận khi kết nối bị gián đoạn giữa chừng
**So that** tôi không phải tải lại toàn bộ tệp lớn từ đầu mỗi khi mạng chập
chờn, tiết kiệm thời gian và băng thông, đặc biệt trên mạng cách ly nội bộ
không ổn định.

## Acceptance Criteria

```
Given tôi chọn tải một tệp có kích thước > 20MB vào một batch ingest đang mở
When tôi bắt đầu tải
Then hệ thống dùng luồng chunked (init → gửi từng chunk → complete), không
     gửi tệp trong một request duy nhất
```

```
Given tôi chọn tải một tệp có kích thước ≤ 20MB
When tôi bắt đầu tải
Then hệ thống dùng luồng whole-file hiện có (một request multipart), hành vi
     không đổi so với trước khi có tính năng này
```

```
Given một phiên tải chunked đang diễn ra và một số chunk đã được server xác
     nhận nhận thành công
When kết nối mạng bị gián đoạn giữa chừng (mất mạng, tab treo, lỗi transient)
     và tôi thử tải lại tệp đó
Then hệ thống chỉ gửi lại các chunk chưa được xác nhận (tính từ offset đã
     nhận), không gửi lại các phần đã nhận thành công
```

```
Given tất cả chunk của một tệp đã được server nhận đủ
When tôi gọi bước hoàn tất (complete) phiên tải
Then hệ thống ráp các chunk thành tệp hoàn chỉnh rồi chạy đúng pipeline hiện
     có (buffer → check định dạng theo allowlist → tính SHA-256 → quét
     ClamAV đồng bộ → lưu SeaweedFS) — không bỏ qua hay rút gọn bước nào
```

```
Given tệp hoàn chỉnh sau khi ráp chunk bị ClamAV phát hiện nhiễm mã độc
When bước complete chạy scan
Then tệp bị từ chối, không được lưu vào kho, phản hồi lỗi rõ ràng cho người
     dùng — đúng hành vi fail-closed đã có ở luồng whole-file
```

```
Given một phiên tải chunked (`upload_session`) được mở bởi người dùng A trong
     batch của người dùng A
When người dùng B (không sở hữu/không có quyền trên batch đó) cố gửi chunk
     vào session của A
Then hệ thống từ chối request (không cho phép ghi chunk vào session không
     thuộc về người dùng đang gọi)
```

- [ ] AC-1: Tệp > 20MB tự động đi luồng chunked (init/chunk/complete); ngưỡng
      20MB đọc từ system-config, không hard-code cứng trong client/server.
- [ ] AC-2: Tệp ≤ 20MB tiếp tục đi luồng whole-file hiện có, không thay đổi
      hành vi, không thêm round-trip thừa.
- [ ] AC-3: Kích thước chunk (mặc định 8MB) đọc từ system-config (server trả
      về ở bước init), không hard-code cố định trong code client.
- [ ] AC-4: Khi resume sau gián đoạn, chỉ các chunk chưa được server xác nhận
      mới được gửi lại; không có trường hợp gửi lại toàn bộ tệp từ đầu cho
      tệp đã đi luồng chunked.
- [ ] AC-5: Bước complete luôn chạy đầy đủ pipeline scan-before-store hiện có
      (SHA-256 → ClamAV đồng bộ → lưu SeaweedFS) trên tệp đã ráp hoàn chỉnh;
      không có đường nào cho phép tệp vào kho mà chưa qua scan.
- [ ] AC-6: Tệp nhiễm mã độc bị từ chối ở bước complete giống hệt hành vi
      fail-closed hiện có ở luồng whole-file (không lưu, báo lỗi rõ ràng).
- [ ] AC-7: Chunk chỉ được ghi vào đúng phiên tải của người dùng đã mở phiên
      đó; request ghi chunk vào phiên không thuộc về mình bị từ chối.
- [ ] AC-8: Kết quả hiển thị cho người dùng (thành công/thất bại/tiến độ) ở
      giao diện `upload/page.tsx` nhất quán giữa luồng whole-file và chunked
      (dùng chung cơ chế hiển thị `FileOutcome`/`OutcomeBadge` đã có).
- [ ] AC-9: Khi một chunk gửi lỗi và đang tự động retry, giao diện KHÔNG hiển
      thị chỉ báo trạng thái retry theo thời gian thực — giữ nguyên hành vi
      "retry ngầm" hiện có của whole-file. Badge chỉ chuyển sang trạng thái
      lỗi (`OutcomeBadge` hiện có) nếu chunk đó retry hết số lần cho phép mà
      vẫn thất bại. (Quyết định xác nhận với người duyệt — không cần thêm UI
      state mới.)

## Ngoài phạm vi (Out of scope)

- Quét mã độc theo từng phần (partial scan) trên chunk riêng lẻ — không làm,
  vì phá vỡ invariant fail-closed đã chốt ở kiến trúc.
- Thay đổi cơ chế lưu trữ SeaweedFS/S3 API — chunk chỉ tối ưu tầng transport
  client↔server.
- Cơ chế xác thực riêng cho endpoint chunk (vd token riêng cho phiên upload)
  — không làm; dùng lại cơ chế bearer token hiện có, đọc lại token mới nhất ở
  mỗi request chunk (xem mục Phụ thuộc bên dưới).
- Cleanup phiên tải bỏ dở (job dọn `upload_session`/temp file hết hạn) — tách
  riêng ở `chunked-file-upload-03-session-cleanup.md`.
- Luồng `personal/batches/...` — tách riêng ở
  `chunked-file-upload-02-personal-chunked-upload.md`.

## Phụ thuộc / Ghi chú kiến trúc liên quan

- Tham chiếu Quyết định 1 (giao thức chunk/resume tự thiết kế, session-based
  offset-based append) và Quyết định 4 (ngưỡng 20MB, chunk size 8MB, cả hai
  đưa vào system-config) ở `docs/01-sa/architecture/chunked-file-upload.md`.
- Tham chiếu Quyết định 2 (entity `upload_session` ở PostgreSQL, không tách
  Redis) và ghi chú áp dụng chung cho cả ingest + personal.
- Tham chiếu Quyết định 5 (access token 5 phút, phải đọc `session.accessToken`
  mới nhất ở mỗi lần gửi chunk, không cache token đầu phiên) — đây là điều
  kiện bắt buộc để AC-4 hoạt động đúng trên tệp lớn/mạng chậm.
- Không đổi pipeline nghiệp vụ `DocumentsService.ingestBatchFile` — chunk hoá
  chỉ là lớp thêm vào phía trước (theo ràng buộc ở mục 2 file SA).

## Câu hỏi mở cho DEV

Không còn câu hỏi mở chặn duyệt cho story này. Các điểm sau đã được xác nhận
với người duyệt (2026-08-21) và chuyển thành yêu cầu bắt buộc khi implement:

- File SA nguồn (`docs/01-sa/architecture/chunked-file-upload.md`) đã
  `Status: Approved` — không phải Draft như ghi nhận trước đó, đã xác minh
  lại trực tiếp trên file.
- UX retry chunk lỗi: xác nhận phương án "retry ngầm, chỉ báo lỗi khi hết
  lượt" — đã đưa vào AC-9 ở trên, không cần hỏi thêm.
- NextAuth refresh token trong vòng lặp gửi chunk (Quyết định 5 file SA) vẫn
  là một **yêu cầu test bắt buộc** khi DEV implement (không phải câu hỏi mở
  cần quyết định sản phẩm) — xem mục Phụ thuộc bên dưới. DEV phải viết
  test/verify thực tế trước khi coi AC-4 là hoàn thành trên tệp rất lớn.
