# User Story: search-fulltext-03-dinh-vi-ket-qua

> File: `docs/02-ba/stories/search-fulltext-03-dinh-vi-ket-qua.md` — Owner: BA
> Nguồn kiến trúc: `docs/01-sa/architecture/search-fulltext.md`
> Status: Approved

> **Ghi chú nguồn (đã xử lý 2026-09-07):** `search-fulltext.md` nay đã
> **Approved**. Story này được viết dựa trên nội dung đã chốt ngày
> 2026-08-16 (xem mục 7 của file đó).

## User Story

**As a** người dùng R1/R2
**I want** mỗi kết quả tìm kiếm cho biết chính xác vị trí trong tài liệu gốc
(trang + dòng + vùng khoanh cho văn bản, mốc thời gian cho âm thanh/video) và
cho phép nhảy thẳng tới đó
**So that** tôi xác minh nội dung nhanh, không phải dò thủ công cả tài liệu
dài, đặc biệt quan trọng khi dữ liệu dùng làm chứng cứ

## Acceptance Criteria

```
Given kết quả tìm kiếm là một đoạn trích từ tài liệu văn bản đã OCR
When tôi xem đoạn trích đó trong danh sách kết quả
Then đoạn trích kèm số trang, khoảng dòng (line_from–line_to) và vùng khoanh
      (bbox) trên ảnh trang tương ứng với vị trí khớp từ khóa
```

- [ ] AC-1: Đoạn trích từ tài liệu văn bản (đã OCR) hiển thị kèm số trang,
      khoảng dòng (`line_from`–`line_to`) và có thể khoanh vùng (bbox) đúng vị
      trí khớp trên ảnh trang.
- [ ] AC-2: Bấm vào một đoạn trích văn bản, hệ thống mở trình xem tài liệu ngay
      tại trang tương ứng và khoanh sáng vùng khớp trên ảnh trang.
- [ ] AC-3: Đoạn trích từ tài liệu âm thanh/video hiển thị kèm mốc thời gian
      bắt đầu–kết thúc; bấm vào đoạn trích sẽ mở trình phát và tua tới đúng
      mốc thời gian đó, tự động phát.
- [ ] AC-4: Nếu đoạn khớp là ghi chú (note) hoặc chỉ khớp ở tên tệp/tiêu đề/nhãn
      (không có vị trí cụ thể trong nội dung), kết quả hiển thị ở **cấp tài
      liệu** kèm cảnh báo rõ ràng "chỉ định vị được ở cấp tài liệu" — không
      hiển thị số trang/dòng/mốc thời gian giả.
- [ ] AC-5: Khi một tài liệu có nhiều vị trí khớp (tối đa 3 đoạn hiển thị), thứ
      tự liệt kê theo độ liên quan (điểm số), sau đó theo số trang tăng dần
      (văn bản) hoặc theo mốc thời gian tăng dần (audio/video).
- [ ] AC-6: Nếu kết quả xuất phát từ nội dung được hệ thống tự động xác nhận
      (chưa qua người xác nhận thủ công), hiển thị rõ nhãn/badge cảnh báo để
      tôi biết cần thận trọng khi dùng làm chứng cứ.
- [ ] AC-7: Không có bước nào yêu cầu tôi tự đối chiếu số dòng với nội dung ảnh
      — vùng khoanh (bbox) trên ảnh trang là đủ để xác định đúng vị trí, kể cả
      khi việc phân dòng OCR không hoàn hảo.

## Ngoài phạm vi (Out of scope)

- Ánh xạ offset ký tự sang số dòng chính xác tuyệt đối — chủ động không làm ở
  giai đoạn này (bbox đã đủ để định vị trên ảnh).
- Cách OCR/STT sinh ra `page_no`/`line_from`/`line_to`/`bbox_polygon`/
  `time_start_ms`/`time_end_ms` — thuộc `digitization-pipeline`.
- Tìm kiếm cơ bản/nâng cao (từ khóa, bộ lọc) → xem
  `search-fulltext-01-tim-kiem-co-ban.md`,
  `search-fulltext-02-tim-kiem-nang-cao.md`.

## Phụ thuộc / Ghi chú kiến trúc liên quan

- Quyết định 4 (Ranking, highlight và trả vị trí — FR-22 + FR-25): độ mịn
  "dòng" = khoảng dòng theo block OCR kèm `bbox_polygon` (đã chốt 2026-08-16,
  không làm ánh xạ offset → dòng); vị trí audio/video = `time_start_ms`/
  `time_end_ms`; segment `note`/`title` = `position_type = none` kèm cảnh báo.
- Quyết định 2 (collapse + `inner_hits`) — cơ sở AC-5 (nhiều vị trí trong một
  tài liệu, tối đa 3, sắp theo điểm rồi theo trang/thời gian).
- Quyết định 1 (mapping) — `bbox_polygon` không lưu trong OpenSearch, lấy từ
  PostgreSQL ở bước hydrate — không phát sinh round-trip mới nhưng cần DEV lưu
  ý khi thiết kế API response.
- Ràng buộc mục 2 (`digitization-pipeline.md` QĐ 3): OCR có `page_no`,
  `line_from`/`line_to`, `bbox_polygon`, `reading_order`; STT/SED có
  `time_start_ms`/`time_end_ms`.
- Rủi ro mục 6 file SA: badge "auto-confirmed" giảm thiểu rủi ro dùng nhầm nội
  dung chưa xác nhận thủ công làm chứng cứ (cơ sở AC-6).

## Câu hỏi mở cho DEV

- [ ] Định dạng cụ thể của `bbox_polygon` trả về cho frontend (tọa độ góc hay
      danh sách điểm đa giác) để vẽ khoanh vùng chính xác trên ảnh trang có tỉ
      lệ khác nhau.
- [ ] Vị trí/UX hiển thị badge "nội dung tự động xác nhận" trên từng kết quả
      (nhất quán với cách hiển thị tương tự ở màn hình xem tài liệu, nếu có) —
      cần DEV đối chiếu với `digitization-review` khi story đó sẵn sàng.
- [ ] Cách trình bày cảnh báo "chỉ định vị được ở cấp tài liệu" (icon, tooltip,
      dòng chữ) khi kết quả là note/title — BA đề xuất nội dung, DEV/UX chốt
      hình thức hiển thị.
