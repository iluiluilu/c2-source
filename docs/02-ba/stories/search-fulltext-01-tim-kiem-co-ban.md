# User Story: search-fulltext-01-tim-kiem-co-ban

> File: `docs/02-ba/stories/search-fulltext-01-tim-kiem-co-ban.md` — Owner: BA
> Nguồn kiến trúc: `docs/01-sa/architecture/search-fulltext.md`
> Status: Approved

> **Ghi chú nguồn (đã xử lý 2026-09-07):** `search-fulltext.md` nay đã
> **Approved**. Story này được viết dựa trên nội dung đã chốt ngày
> 2026-08-16 (xem mục 7 của file đó).

## User Story

**As a** người dùng R1 (tra cứu) / R2 (tra cứu + nạp dữ liệu)
**I want** tìm kiếm toàn văn trong kho tài liệu đã số hóa bằng từ khóa
**So that** tôi nhanh chóng tìm được các tài liệu liên quan kèm đoạn trích nổi
bật (highlight) mà không phải mở từng tài liệu để tìm thủ công

## Acceptance Criteria

```
Given tôi đã đăng nhập với vai trò R1/R2 và có quyền xem ít nhất một phạm vi
      dữ liệu (nhóm/tài liệu do tôi nạp)
When tôi nhập một từ khóa hợp lệ (không rỗng, ≤ 256 ký tự, ≤ 32 token) vào ô
      tìm kiếm và bấm tìm
Then hệ thống trả về danh sách tài liệu khớp, mỗi tài liệu kèm tối đa 3 đoạn
      trích liên quan có highlight từ khóa, sắp xếp theo độ liên quan (mặc
      định) hoặc theo thời gian nếu tôi chọn
```

- [ ] AC-1: Từ khóa hợp lệ trả về danh sách tài liệu, mỗi tài liệu kèm **tối đa
      3 đoạn trích** (segment) có highlight; đoạn trích được chọn theo điểm
      liên quan cao nhất trong tài liệu đó.
- [ ] AC-2: Từ khóa gõ không dấu (ví dụ "khong dau") vẫn tìm ra tài liệu có nội
      dung dấu đầy đủ, nhưng kết quả có dấu chính xác được xếp hạng cao hơn.
- [ ] AC-3: Cụm từ liền kề trong từ khóa (không dùng cú pháp nâng cao) được ưu
      tiên khớp cụm hơn khớp rời rạc từng từ (đúng thứ tự, ưu tiên xếp hạng).
- [ ] AC-4: Kết quả trả về **chỉ** gồm tài liệu tôi sở hữu (đã nạp) hoặc đã
      được chia sẻ tới nhóm tôi là thành viên — không bao giờ thấy tài liệu
      ngoài phạm vi, kể cả khi từ khóa khớp rất mạnh.
- [ ] AC-5: Nếu hệ thống không xác định được ngữ cảnh phạm vi của tôi (ví dụ
      lỗi phiên/cache), tìm kiếm bị từ chối (403) thay vì trả kết quả không
      lọc phạm vi.
- [ ] AC-6: Mỗi lượt tìm kiếm được ghi lại (audit) gồm người thực hiện, thời
      điểm, bộ lọc áp dụng, số kết quả và từ khóa (cắt tối đa 200 ký tự); nội
      dung đoạn trích/ghi chú **không** được ghi vào nhật ký.
- [ ] AC-7: Nếu để trống từ khóa **và** không chọn bộ lọc nào, hệ thống báo lỗi
      rõ ràng yêu cầu nhập từ khóa hoặc chọn ít nhất một bộ lọc, không thực
      hiện truy vấn.
- [ ] AC-8: Số lượng kết quả hiển thị dạng ước lượng, ví dụ "khoảng N kết quả"
      — không cam kết là con số tuyệt đối chính xác.
- [ ] AC-9: Danh sách kết quả phân trang, mặc định 20 kết quả/trang, tối đa 50
      (cấu hình hệ thống có thể điều chỉnh trong khoảng cho phép).
- [ ] AC-10: Nếu truy vấn tìm kiếm quá lâu (vượt ngưỡng xử lý), hệ thống hiển
      thị thông báo lỗi rõ ràng ("tìm kiếm quá thời gian chờ, vui lòng thử lại
      hoặc thu hẹp từ khóa") — **không** hiển thị một phần kết quả chưa được
      lọc phạm vi.
- [ ] AC-11: Nếu công cụ tìm kiếm toàn văn tạm thời không khả dụng, hệ thống
      hiển thị thông báo suy giảm chức năng rõ ràng (không phải lỗi chung
      chung, không giả vờ 0 kết quả).

## Ngoài phạm vi (Out of scope)

- Bộ lọc nâng cao (loại tài liệu, nhãn, thời gian, nhóm, media, nguồn xác
  nhận) và cú pháp rút gọn (cụm từ chính xác/loại trừ/bắt buộc/OR) → xem
  `search-fulltext-02-tim-kiem-nang-cao.md`.
- Định vị chính xác trang/dòng/bbox hoặc mốc thời gian, nút "nhảy tới" → xem
  `search-fulltext-03-dinh-vi-ket-qua.md`.
- Trạng thái "chờ kiểm duyệt" của nội dung mới nạp và đồng bộ chỉ mục → xem
  `search-fulltext-04-dong-bo-chi-muc.md`.
- Tra cứu ngữ nghĩa/embedding (FR-24) — thuộc `search-semantic`, không nằm
  trong story này.
- Chi tiết OCR/STT sinh nội dung — thuộc `digitization-pipeline`.

## Phụ thuộc / Ghi chú kiến trúc liên quan

- Quyết định 1 (mapping/analyzer tiếng Việt — multi-field `text`/`text.folded`
  /`text.shingle`) — lý do AC-2/AC-3 hoạt động được dù không tách từ ghép hoàn
  hảo.
- Quyết định 2 (collapse theo `document_id` + `inner_hits`) — cơ sở cho AC-1
  (tối đa 3 đoạn/tài liệu) và AC-8 (`total` là ước lượng).
- Quyết định 4 (ranking BM25 + highlight `unified`) — cơ sở AC-1/AC-3.
- Quyết định 5 (ScopeFilterBuilder + double-check khi hydrate, fail-closed) —
  cơ sở AC-4/AC-5; audit theo cùng quyết định — cơ sở AC-6.
- Quyết định 6 (phân trang, timeout 1.500ms) — cơ sở AC-9/AC-10.
- NFR mục 3 (Availability: suy giảm chức năng có kiểm soát, không bỏ filter
  phạm vi) — cơ sở AC-11.

## Câu hỏi mở cho DEV

- [ ] Nội dung chính xác của thông báo "khoảng N kết quả" và thông báo lỗi
      timeout/suy giảm chức năng (copy UI tiếng Việt) — BA đề xuất nhưng cần
      DEV/UX chốt vị trí hiển thị (banner, toast, hay inline).
- [ ] Cách hiển thị sắp xếp mặc định "theo độ liên quan" vs "theo thời gian"
      trên UI (toggle, dropdown) — không phải quyết định kiến trúc, DEV/UX tự
      quyết theo hệ thống thiết kế hiện có.
