# User Story: search-fulltext-02-tim-kiem-nang-cao

> File: `docs/02-ba/stories/search-fulltext-02-tim-kiem-nang-cao.md` — Owner: BA
> Nguồn kiến trúc: `docs/01-sa/architecture/search-fulltext.md`
> Status: Approved

> **Ghi chú nguồn (đã xử lý 2026-09-07):** `search-fulltext.md` nay đã
> **Approved**. Story này được viết dựa trên nội dung đã chốt ngày
> 2026-08-16 (xem mục 7 của file đó).

## User Story

**As a** người dùng R1/R2
**I want** kết hợp từ khóa với các bộ lọc nâng cao (loại tài liệu, nhãn, thời
gian nạp, nhóm, loại media, nguồn xác nhận) và dùng cú pháp rút gọn ngay trong
ô từ khóa (cụm từ chính xác, loại trừ, bắt buộc, OR)
**So that** tôi thu hẹp kết quả tìm kiếm chính xác theo nhu cầu nghiệp vụ thay
vì phải lọc thủ công trong danh sách dài

## Acceptance Criteria

```
Given tôi đã nhập từ khóa và/hoặc chọn ít nhất một bộ lọc nâng cao (loại tài
      liệu, nhãn, khoảng thời gian nạp, nhóm, loại media, nguồn xác nhận)
When tôi bấm tìm
Then hệ thống trả về danh sách tài liệu thỏa mãn đồng thời tất cả tiêu chí đã
      chọn (kết hợp AND giữa các nhóm bộ lọc)
```

- [ ] AC-1: Bộ lọc "Loại tài liệu" (`doc_type`) chỉ cho chọn **một** giá trị
      trong danh mục đóng.
- [ ] AC-2: Bộ lọc "Nhãn chủ đề" (`topic_tags`) cho chọn nhiều giá trị, có tùy
      chọn kết hợp AND hoặc OR giữa các nhãn đã chọn.
- [ ] AC-3: Bộ lọc "Thời gian nạp" (`uploaded_at`) cho chọn khoảng từ–đến; nhãn
      hiển thị trên UI ghi rõ **"Ngày nạp kho"** (không phải ngày ban hành tài
      liệu) để tránh hiểu nhầm.
- [ ] AC-4: Bộ lọc "Nhóm" cho chọn nhiều nhóm; nếu tôi chọn một nhóm mà tôi
      không phải thành viên, giá trị đó bị loại âm thầm khỏi bộ lọc (kết quả
      không lỗi, không báo "nhóm không tồn tại") và được ghi vào audit.
- [ ] AC-5: Bộ lọc "Thư mục" áp dụng theo tiền tố đường dẫn — chọn một thư mục
      thì lọc luôn cả các thư mục con (tối đa 5 cấp).
- [ ] AC-6: Bộ lọc "Loại media" và "Nguồn xác nhận" (tự động/đã xác nhận thủ
      công) hoạt động độc lập, kết hợp AND với các bộ lọc khác.
- [ ] AC-7: Trong ô từ khóa, cụm từ đặt trong dấu ngoặc kép (`"cụm từ"`) chỉ
      khớp đúng cụm liền kề, không tách rời từng từ.
- [ ] AC-8: Trong ô từ khóa, từ có tiền tố `-` bị loại trừ khỏi kết quả (tài
      liệu chứa từ đó không hiển thị).
- [ ] AC-9: Trong ô từ khóa, từ có tiền tố `+` bắt buộc phải xuất hiện trong
      kết quả.
- [ ] AC-10: Trong ô từ khóa, dùng `OR` giữa hai cụm/từ để khớp một trong hai.
- [ ] AC-11: Nếu tôi gõ ký tự không thuộc cú pháp hỗ trợ (ví dụ `*`, `~`, hay
      cú pháp trường `field:value`), hệ thống **không** báo lỗi cú pháp — coi
      như ký tự thường hoặc bỏ qua, vẫn trả kết quả hợp lý.
- [ ] AC-12: Nếu kết hợp từ khóa + bộ lọc trả về **0 kết quả**, hệ thống gợi ý
      tối đa 5 phương án "bỏ bớt một điều kiện sẽ có N kết quả" (mỗi gợi ý bỏ
      đúng một bộ lọc) để tôi dễ điều chỉnh.
- [ ] AC-13: Nếu từ khóa vượt quá 256 ký tự/32 token, hoặc một bộ lọc được chọn
      quá 20 giá trị, hệ thống báo lỗi validate rõ ràng trước khi thực hiện
      tìm kiếm (không âm thầm cắt bớt).
- [ ] AC-14: Nếu tôi chỉ dùng bộ lọc (không nhập từ khóa) và công cụ tìm kiếm
      toàn văn đang gặp sự cố, hệ thống vẫn trả kết quả đúng theo bộ lọc (chạy
      qua đường dự phòng), không báo lỗi toàn bộ chức năng ngừng hoạt động.

## Ngoài phạm vi (Out of scope)

- Bộ lọc theo ngày ban hành tài liệu (`document_date`) — chưa có ở giai đoạn
  này, để ngỏ cho giai đoạn sau nếu nghiệp vụ yêu cầu.
- Cú pháp truy vấn mở rộng kiểu `field:value`, wildcard (`*`), fuzzy (`~`) —
  chủ động không hỗ trợ vì lý do an toàn/hiệu năng (xem Quyết định 3 file SA).
- Tìm kiếm toàn văn cơ bản (không bộ lọc) → xem
  `search-fulltext-01-tim-kiem-co-ban.md`.
- Định vị chính xác vị trí kết quả → xem
  `search-fulltext-03-dinh-vi-ket-qua.md`.

## Phụ thuộc / Ghi chú kiến trúc liên quan

- Quyết định 3 (API/DSL tìm kiếm nâng cao) — cơ sở cho toàn bộ AC-1–AC-13:
  bộ lọc có cấu trúc (`bool.filter`), `simple_query_string` với flags giới
  hạn (`PHRASE|AND|OR|NOT|PRECEDENCE|WHITESPACE`), giao `group_ids` với phạm
  vi người dùng, chẩn đoán 0 kết quả bằng tối đa 5 truy vấn `count`.
- Quyết định 5 (ScopeFilterBuilder) — cơ sở AC-4 (lọc nhóm ngoài phạm vi bị bỏ
  âm thầm + audit).
- NFR mục 3, dòng "Availability" (lọc thuần metadata vẫn chạy bằng SQL khi
  chỉ mục hỏng) — cơ sở AC-14.
- Rủi ro mục 6 của file SA: "Chỉ có bộ lọc thời gian theo `uploaded_at`" —
  nhắc BA/UX phải đặt nhãn rõ ràng (AC-3).

## Câu hỏi mở cho DEV

- [ ] Copy chính xác cho khối gợi ý "0 kết quả, thử bỏ bộ lọc X" (thứ tự ưu
      tiên hiển thị nếu nhiều gợi ý có N > 0).
- [ ] UI cho tùy chọn AND/OR giữa các nhãn chủ đề (`topic_tags`) — dạng toggle
      hay checkbox riêng — không phải quyết định kiến trúc, do DEV/UX chốt
      theo hệ thống thiết kế hiện có.
- [ ] Cần tài liệu hướng dẫn cú pháp rút gọn (`"..."`, `-`, `+`, `OR`) hiển thị
      ở đâu trong UI (tooltip cạnh ô tìm kiếm hay trang trợ giúp riêng) — BA sẽ
      soạn nội dung hướng dẫn khi DEV xác nhận vị trí đặt.
