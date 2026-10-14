# User Story: search-fulltext-04-dong-bo-chi-muc

> File: `docs/02-ba/stories/search-fulltext-04-dong-bo-chi-muc.md` — Owner: BA
> Nguồn kiến trúc: `docs/01-sa/architecture/search-fulltext.md`
> Status: Approved

> **Ghi chú nguồn (đã xử lý 2026-09-07):** `search-fulltext.md` nay đã
> **Approved**. Story này được viết dựa trên nội dung đã chốt ngày
> 2026-08-16 (xem mục 7 của file đó).

## User Story

**As a** hệ thống tìm kiếm (thay mặt người dùng R1/R2 và người chịu trách
nhiệm dữ liệu)
**I want** chỉ đưa vào chỉ mục tìm kiếm những nội dung đã được xác nhận qua
kiểm duyệt (FR-18), tự động đồng bộ chỉ mục khi tài liệu được xác nhận/đổi
nhãn/chia sẻ, và không bao giờ đưa nội dung kho cá nhân vào chỉ mục
**So that** kết quả tìm kiếm luôn đáng tin cậy và đúng phạm vi, trong khi
người dùng vẫn định vị được tài liệu vừa nạp qua tên tệp/nhãn ngay lập tức

## Acceptance Criteria

```
Given một tài liệu vừa được nạp vào kho nhưng chưa qua bước xác nhận nội dung
      (FR-18)
When người dùng tìm theo từ khóa xuất hiện trong nội dung tài liệu đó
Then tài liệu đó chưa xuất hiện trong kết quả tìm nội dung, nhưng vẫn tìm
      được ngay nếu người dùng tìm theo tên tệp hoặc nhãn, kèm ghi chú rõ
      "đang chờ kiểm duyệt nội dung"
```

- [ ] AC-1: Tài liệu chưa được xác nhận (chưa có sự kiện `review.confirmed`
      hoặc `review.reconfirmed`) **không** xuất hiện trong kết quả tìm theo
      nội dung toàn văn.
- [ ] AC-2: Ngay sau khi nạp (trước khi xác nhận), tài liệu vẫn tìm được qua
      tên tệp hoặc nhãn (tìm ở cấp tài liệu), kèm ghi chú UI rõ ràng "Nội dung
      đang chờ kiểm duyệt, có thể mất tới 24 giờ để tìm được theo nội dung
      đầy đủ".
- [ ] AC-3: Sau khi tài liệu được xác nhận, nội dung đầy đủ trở nên tìm được
      trong vòng vài giây tới tối đa vài chục giây (không phải chờ tiếp đến
      hạn 24h) — trải nghiệm người dùng là "gần như ngay lập tức" sau khi
      kiểm duyệt xong.
- [ ] AC-4: Khi người dùng đổi nhãn, sửa/thêm ghi chú, hoặc thay đổi phạm vi
      chia sẻ (nhóm) của một tài liệu, kết quả tìm kiếm/bộ lọc phản ánh đúng
      thay đổi đó trong thời gian ngắn (vài giây), không cần chờ đến chu kỳ
      xác nhận lại.
- [ ] AC-5: Nội dung trong kho cá nhân của người dùng **không bao giờ** xuất
      hiện trong kết quả tìm kiếm toàn văn/nâng cao, dù người dùng đó đang tìm
      trong chính kho của mình.
- [ ] AC-6: Khi có thao tác gắn nhãn/chia sẻ hàng loạt ảnh hưởng nhiều tài
      liệu, việc cập nhật chỉ mục không làm tìm kiếm của người dùng khác bị
      chậm vượt quá thời gian phản hồi cam kết (xem NFR trong file SA).
- [ ] AC-7: Nếu chỉ mục tìm kiếm gặp sự cố (hỏng/mất), người dùng vẫn tìm được
      theo bộ lọc metadata thuần (không từ khóa) qua đường dự phòng; tìm theo
      nội dung/từ khóa hiển thị thông báo suy giảm chức năng rõ ràng thay vì
      trả 0 kết quả không giải thích.

## Ngoài phạm vi (Out of scope)

- Luồng xác nhận/kiểm duyệt nội dung chi tiết (ai xác nhận, quy trình review)
  — thuộc `digitization-review`.
- Cơ chế outbox → RabbitMQ → indexer, thiết kế đồng bộ chi tiết tầng hạ tầng —
  thuộc `ai-search-infra`.
- Cách sinh nội dung/segment từ OCR/STT — thuộc `digitization-pipeline`.
- Trải nghiệm tìm kiếm cơ bản/nâng cao/định vị kết quả → xem các story
  `search-fulltext-01`, `-02`, `-03`.

## Phụ thuộc / Ghi chú kiến trúc liên quan

- Mục 2 (Ràng buộc) file SA: "Chỉ nội dung đã xác nhận (FR-18) mới có trong
  chỉ mục chính thức" — cơ sở AC-1/AC-2/AC-3; "Kho cá nhân không bao giờ được lập
  chỉ mục" — cơ sở AC-5.
- Quyết định 2, hệ quả 2 (segment tổng hợp `content_source = 'title'` cho
  tên tệp/tiêu đề/nhãn) — cơ sở AC-2 (tìm được ngay dù chưa xác nhận nội dung).
- Quyết định 2, hệ quả trade-off (đổi nhãn/ghi chú/chia sẻ ⇒ `update_by_query`
  trên mọi segment của tài liệu, cần gom lô/throttle) — cơ sở AC-4/AC-6.
- NFR mục 3, dòng "Latency dữ liệu" — từ `review.confirmed` tới tìm được
  ≤ 5s (p95)/≤ 30s (p99); từ đổi nhãn/ghi chú/chia sẻ tới phản ánh vào bộ lọc
  ≤ 5s — cơ sở AC-3/AC-4.
- NFR mục 3, dòng "Availability" — fallback SQL khi chỉ mục hỏng — cơ sở AC-7.
- Rủi ro mục 6 file SA: "Người dùng không tìm thấy nội dung vừa nạp (trễ tới
  24h) và quy cho tìm kiếm lỗi" — giảm thiểu bằng thông báo UI rõ ràng (AC-2);
  "Gắn nhãn/chia sẻ hàng loạt sinh bão `update_by_query`" — giảm thiểu bằng
  gom lô/throttle (AC-6).

## Câu hỏi mở cho DEV

- [ ] Copy chính xác cho thông báo "đang chờ kiểm duyệt nội dung" (AC-2) và vị
      trí hiển thị (ngay trên kết quả cấp tài liệu hay banner riêng) — cần
      phối hợp với story `digitization-review` khi story đó sẵn sàng, để dùng
      thống nhất một thông điệp về trạng thái xác nhận trên toàn hệ thống.
- [ ] Ngưỡng cụ thể để coi một thao tác là "hàng loạt" cần gom lô/throttle
      (AC-6) — số lượng tài liệu bao nhiêu thì kích hoạt cơ chế gom lô — đây là
      chi tiết kỹ thuật, DEV quyết định khi viết tech spec, không phải quyết
      định nghiệp vụ.
- [ ] Có cần hiển thị tiến độ ước tính ("còn khoảng X giờ để tìm được đầy đủ")
      hay chỉ cần thông báo chung "trong tối đa 24 giờ" — BA đề xuất dùng
      thông báo chung để tránh cam kết sai; DEV xác nhận tính khả thi.
