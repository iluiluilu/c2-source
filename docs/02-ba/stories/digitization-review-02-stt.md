# User Story: digitization-review (phần STT + player đồng bộ)

> File: `docs/02-ba/stories/digitization-review-02-stt.md` — Owner: BA
> Nguồn kiến trúc: `docs/01-sa/architecture/digitization-review.md`
> Status: Approved

> **Lưu ý nguồn (đã xử lý 2026-09-07):** cả `digitization-review.md` và
> `digitization-pipeline.md` nay đều `Approved`. DEV có thể bắt đầu viết tech
> spec.

Vòng đời duyệt chung (hàng chờ, xác nhận, tự xác nhận, xử lý quá hạn `in_review`,
xử lý nhiều run) áp dụng **giống hệt** cho `task = stt` như đã mô tả ở
`digitization-review-01-ocr.md` (Story 1, 4, 5, 6, 8) — không lặp lại ở đây.
File này chỉ mô tả phần **đặc thù của STT**: sửa văn bản phiên âm kèm mốc
thời gian, đồng bộ với player.

---

## Story 3: Sửa văn bản/mốc thời gian STT với player đồng bộ

**As a** cán bộ (R1), chính chủ tài liệu
**I want** mở một run STT để nghe/xem player đồng bộ với danh sách đoạn văn
bản phiên âm, sửa văn bản và mốc thời gian bắt đầu/kết thúc của từng đoạn
**So that** tôi hiệu đính bản chuyển lời nói chính xác trước khi xác nhận vào
chỉ mục chính thức

### Acceptance Criteria

```
Given run STT ở trạng thái pending hoặc in_review, document.uploader_sub = tôi
When tôi mở tài liệu
Then hệ thống hiển thị player audio/video cùng danh sách đoạn (segment) văn
     bản phiên âm, phân trang, trả về ≤ 1,5s p95 cho trang 100 segment
 And khi tôi bấm vào một đoạn trong danh sách, player tua tới
     time_start_ms tương ứng
 And khi player đang phát, đoạn tương ứng với thời điểm hiện tại được
     highlight trong danh sách (đồng bộ hai chiều)
```

- [ ] AC-1: Sửa văn bản của một đoạn tuân theo đúng cơ chế lưu như OCR (Story
      2 của `digitization-review-01-ocr.md`): tạo `segment_revision` mới
      (append-only), cập nhật cột hiện hành trong cùng transaction,
      optimistic lock theo `current_revision_no` (409 khi lệch), lưu ≤ 500ms
      p95, thao tác sửa đầu tiên chuyển run từ `pending` sang `in_review`.

```
Given tôi sửa mốc thời gian (start, end) của một đoạn
When tôi nhập start = 1200ms, end = 4500ms, document.duration_ms = 60000
When tôi lưu
Then service kiểm tra 0 ≤ start < end ≤ duration_ms → hợp lệ → lưu thành công (200)
```

- [ ] AC-2: Nếu vi phạm ràng buộc (`start ≥ end`, `end > duration_ms`,
      `start < 0`) → service trả **422** kèm thông báo lỗi rõ ràng, **không
      lưu**, **giữ nguyên giá trị mốc thời gian cũ** trước request đó (rollback
      toàn bộ thao tác sửa của request, đúng nghiệp vụ FR-18 Ngoại lệ).
- [ ] AC-3: Ràng buộc được kiểm tra ở nhiều tầng: frontend kẹp giá trị nhập
      trong `[0, duration]` (chỉ hỗ trợ UX, không phải biện pháp an toàn),
      service validate và trả 422, và ràng buộc CHECK/trigger ở PostgreSQL
      trên `segment_revision` chặn cả các đường ghi khác (job sửa lỗi,
      migration).
- [ ] AC-4: Nếu `document.duration_ms` là `NULL` (chưa xác định được độ dài
      tệp gốc — ví dụ `ffprobe` lỗi) → hệ thống **từ chối toàn bộ thao tác
      sửa mốc thời gian** (422 "chưa xác định độ dài tệp gốc"), nhưng vẫn cho
      phép sửa **văn bản** của đoạn đó bình thường.
- [ ] AC-5: Hai đoạn có mốc thời gian **chồng lấn** nhau (ví dụ nhiều người
      nói cùng lúc) **không** bị chặn khi lưu — chỉ hiển thị cảnh báo trên
      UI; ràng buộc duy nhất bắt buộc là `start < end` cho **từng đoạn** độc
      lập, không ép các đoạn phải rời nhau.
- [ ] AC-6: Không có ràng buộc nào yêu cầu độ dài tệp gốc phải đo lại trong
      màn hình review — giá trị `duration_ms` do pipeline ghi (bằng
      `ffprobe`); nếu sai/thiếu, đây là vấn đề vận hành (công cụ đo lại độ
      dài), không xử lý trong story này.

---

## Ngoài phạm vi (Out of scope)

- Vòng đời duyệt chung (hàng chờ, xác nhận, tự xác nhận, xử lý quá hạn, xử lý
  nhiều run) — xem `digitization-review-01-ocr.md` (Story 1, 4, 5, 6, 8), áp
  dụng nguyên vẹn cho `task = stt`.
- Tự dựng waveform editor — giai đoạn 1 dùng thư viện player sẵn có.
- Công cụ vận hành "đo lại độ dài tệp gốc" khi `duration_ms` sai/thiếu —
  thuộc phạm vi vận hành/`digitization-pipeline`, không phải màn hình review.

## Phụ thuộc / Ghi chú kiến trúc liên quan

- Quyết định 3 (`digitization-review.md`): lưu bản sửa append-only +
  optimistic lock — áp dụng chung cho text và mốc thời gian.
- Quyết định 4 (`digitization-review.md`): chặn mốc thời gian vượt độ dài
  gốc, nhiều tầng (frontend/service/DB), fail-closed khi `duration_ms` NULL,
  cho phép chồng lấn giữa các đoạn.
- `duration_ms` do `digitization-pipeline.md` (Quyết định 3, đo bằng
  `ffprobe`) ghi trên `document` — review không tự đo lại.

## Câu hỏi mở cho DEV

- [ ] Thư viện player cụ thể dùng cho giai đoạn 1 (SA chỉ nói "dùng thư viện
      sẵn có, không tự dựng waveform editor") — DEV chọn khi viết tech spec.
- [ ] Mức độ/hình thức cảnh báo UI khi hai đoạn chồng lấn thời gian (màu sắc,
      icon, tooltip...) chưa được đặc tả — cần làm rõ ở tech spec hoặc UI spec.
- [ ] Có cần giới hạn số ký tự tối đa khi sửa văn bản một đoạn STT không (để
      tránh người dùng gộp nhiều câu vào một đoạn có mốc thời gian ngắn)?
      SA chưa đề cập.
