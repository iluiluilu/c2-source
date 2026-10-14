# User Story: digitization-review (phần OCR + vòng đời duyệt cấp run)

> File: `docs/02-ba/stories/digitization-review-01-ocr.md` — Owner: BA
> Nguồn kiến trúc: `docs/01-sa/architecture/digitization-review.md`
> Status: Approved

> **Lưu ý nguồn (đã xử lý 2026-09-07):** cả `digitization-review.md` và
> `digitization-pipeline.md` nay đều `Approved`; hợp đồng cấp `review_status`
> giữa hai file đã đồng bộ về cấp run (xem mục 7 file SA
> `digitization-review`). DEV có thể bắt đầu viết tech spec.

Các story trong file này áp dụng cho **cả hai loại tác vụ** (`task = ocr` và
`task = stt`) trừ khi nói rõ — vì vòng đời duyệt (`review_state`,
`confirm_source`), hàng chờ, tự xác nhận, xử lý quá hạn và xử lý nhiều run
đều ở **cấp run**, không phụ thuộc loại nội dung. Riêng thao tác **sửa nội
dung** khác nhau giữa OCR (văn bản + bbox) và STT (văn bản + mốc thời gian +
player) — phần OCR nằm ở đây, phần STT nằm ở
`digitization-review-02-stt.md`.

---

## Story 1: Xem hàng chờ kiểm duyệt (chỉ tài liệu do tôi nạp)

**As a** cán bộ (R1) đã nạp tài liệu số hoá
**I want** xem danh sách các run đang chờ/đang kiểm duyệt do chính tôi nạp,
lọc theo loại tác vụ, trạng thái, cờ chất lượng và mức độ sắp hết hạn
**So that** tôi ưu tiên xử lý đúng tài liệu quan trọng trước khi bị tự động
xác nhận

### Acceptance Criteria

```
Given tôi đã đăng nhập với vai trò R1 và đã nạp một số tài liệu
When tôi mở màn hình "Hàng chờ kiểm duyệt"
Then tôi chỉ thấy các run mà document.uploader_sub = tôi
 And tôi KHÔNG thấy tài liệu do đồng nghiệp khác nạp, dù cùng phạm vi nhóm
     theo FR-08 (không có tab "trong phạm vi của tôi" để duyệt hộ)
```

- [ ] AC-1: Danh sách lọc được theo **loại tác vụ** (`task = ocr | stt`).
- [ ] AC-2: Danh sách lọc được theo **trạng thái duyệt** (`pending`,
      `in_review`, và nhãn riêng **"quá hạn xử lý"** cho run `in_review` vượt
      `ocr_stt.review_stale_hours` — xem Story 6).
- [ ] AC-3: Danh sách lọc được theo **cờ chất lượng** (`output_quality_flag`,
      ví dụ `poor`) do pipeline gắn.
- [ ] AC-4: Danh sách lọc được theo **"sắp hết hạn"** (run có
      `review_deadline_at` gần tới, xem câu hỏi mở về ngưỡng cụ thể).
- [ ] AC-5: Kết quả trả về trong **≤ 2s p95** kể cả khi có filter, và có phân
      trang/chia lô để chịu tồn đọng **≥ 100.000 run `pending`**.
- [ ] AC-6: Mặc định sắp xếp ưu tiên rủi ro trước (nhiều segment
      `confidence` thấp / có cờ chất lượng lên trước) — hỗ trợ giảm thiểu rủi
      ro "tồn đọng hàng chờ lớn" (mục 6 file SA).
- [ ] AC-7: Với run có nhiều `run_id` cho cùng tài liệu (đổi/nâng cấp model —
      xem Story 8), danh sách phải phân biệt rõ run nào là bản mới cần rà
      soát, run nào đã `confirmed`/`superseded`.

---

## Story 2: Mở tài liệu để xem/sửa văn bản OCR (ảnh gốc + bbox)

**As a** cán bộ (R1), chính chủ tài liệu
**I want** mở một run OCR để xem ảnh trang gốc kèm khung toạ độ (bbox) song
song với văn bản OCR tương ứng, và sửa văn bản nếu cần
**So that** tôi hiệu đính sai sót nhận dạng trước khi dữ liệu được xác nhận
vào chỉ mục chính thức

### Acceptance Criteria

```
Given run ở trạng thái pending hoặc in_review và document.uploader_sub = tôi
When tôi mở tài liệu để xem
Then hệ thống hiển thị ảnh trang gốc + overlay bbox + ô văn bản OCR tương ứng
     theo từng segment, có phân trang, trả về trong ≤ 1,5s p95 cho trang 100 segment
 And chỉ ghi first_viewed_at/first_viewed_by
 And review_state KHÔNG đổi (vẫn pending/in_review như trước khi tôi mở)
 And đồng hồ tự xác nhận 24h KHÔNG dừng chỉ vì tôi mở xem
```

- [ ] AC-1: Người không phải `document.uploader_sub` mở tài liệu → từ chối
      (403). R2 không có đường vào màn hình duyệt (chỉ đọc sau khi đã xác
      nhận, qua tìm kiếm/báo cáo). R3 không đọc được nội dung.
- [ ] AC-2: Khi tôi sửa văn bản của một segment và lưu:
  - Hệ thống tạo một dòng mới trong lịch sử sửa (`segment_revision`,
    `revision_no` tăng dần), **không** ghi đè/xoá bản trước — append-only.
  - Cột "hiện hành" của segment (`current_text`, `is_edited`,
    `current_revision_no`) cập nhật trong cùng transaction.
  - Lưu một bản sửa hoàn tất trong **≤ 500ms p95**.
  - Nếu đây là thao tác ghi đầu tiên trên run (đang `pending`) → run chuyển
    sang `in_review`, đồng hồ tự xác nhận dừng lại.
- [ ] AC-3: Nếu tôi gửi sửa kèm `current_revision_no` đã cũ (bị chính tôi ở
      tab khác, hoặc — về lý thuyết — người khác sửa trước, dù chỉ chính chủ
      mới có quyền) → hệ thống trả **409**, không ghi đè, yêu cầu tải lại bản
      mới nhất trước khi sửa tiếp.
- [ ] AC-4: Bản gốc do AI sinh (`ai_text`) luôn xem được song song với bản
      hiện hành (chức năng diff AI ↔ bản sửa), không bao giờ bị mất/ghi đè.
- [ ] AC-5: Xoá một đoạn hoặc thêm đoạn mới cũng tạo revision tương ứng
      (`deleted=true` / đoạn mới có nguồn `manual`), không xoá vật lý dữ
      liệu.
- [ ] AC-6: Nội dung văn bản chỉnh sửa **không** được ghi vào `audit_log`
      (chỉ ghi `segment_id`, `revision_no`, số ký tự thay đổi, người/thời
      điểm sửa — audit trỏ sang `segment_revision` để xem chi tiết).

---

## Story 4: Xác nhận run (có sửa hoặc không sửa)

**As a** cán bộ (R1), chính chủ tài liệu
**I want** xác nhận một run — dù tôi có chỉnh sửa gì hay không — để đưa dữ
liệu vào chỉ mục chính thức
**So that** kết quả số hoá đã qua rà soát của tôi có thể được tra cứu

### Acceptance Criteria

```
Given run ở trạng thái pending hoặc in_review, document.uploader_sub = tôi
When tôi bấm "Xác nhận"
Then review_state chuyển sang confirmed
 And confirm_source được ghi đúng một trong ba giá trị:
     - manual_unchanged: nếu tôi không sửa segment nào (edited_segment_count = 0)
     - manual_edited: nếu tôi đã sửa ít nhất một segment
 And confirmed_by = tôi, confirmed_at = thời điểm xác nhận
 And hệ thống ghi audit_log
 And hệ thống phát sự kiện review.confirmed (document_id, run_id, task,
     confirm_source, confirmed_at, segment_count) cho lập chỉ mục
```

- [ ] AC-1: Xác nhận là thao tác **"tất cả hoặc không"** cho toàn bộ run —
      không có API/luồng nào cho phép xác nhận một phần segment.
- [ ] AC-2: Sau khi một run đã `confirmed`, nếu tôi tiếp tục sửa thêm (đính
      chính) và lưu → hệ thống phát `review.reconfirmed` (không phải
      `review.confirmed`) để indexer upsert theo `segment_id`, run vẫn ở
      `confirmed` (không quay lại `pending`/`in_review`).
- [ ] AC-3: Người không phải chính chủ không xác nhận được (403), kể cả R2/R3.
- [ ] AC-4: Sau khi xác nhận, kết quả tra cứu hiển thị rõ `confirm_source`
      (đặc biệt khi là `auto` — xem Story 5) để phân biệt mức độ tin cậy.

---

## Story 5: Hệ thống tự động xác nhận sau khi hết hạn (24h mặc định)

**As a** hệ thống (background job, thay mặt quy trình khi R1 không thao tác)
**I want** tự động xác nhận các run `pending` đã quá `review_deadline_at` mà
không bị chặn
**So that** dữ liệu vẫn được đưa vào chỉ mục đúng SRS ngay cả khi người nạp
không xử lý kịp, đồng thời đánh dấu rõ đây là bản "tự động" để rà soát sau

### Acceptance Criteria

```
Given run review_state = pending, auto_confirm_blocked = false,
      review_deadline_at <= now(), và ocr_stt.auto_confirm_enabled = true
When job auto-confirm-sweeper quét (chu kỳ 1 phút, lô 500 run)
Then run chuyển review_state = confirmed
 And confirm_source = auto, confirmed_by = null, confirmed_at = now()
 And ghi audit_log (digitization.auto_confirmed)
 And phát outbox event review.confirmed
 And toàn bộ diễn ra trong vòng ≤ 5 phút sau review_deadline_at
```

- [ ] AC-1: Run có `auto_confirm_blocked = true` (tài liệu bị gắn cờ `poor`
      bởi pipeline) → **không bao giờ** tự xác nhận, dù đã quá hạn; vẫn hiện
      trong hàng chờ, cần R1 chủ động xử lý.
- [ ] AC-2: Khi `ocr_stt.auto_confirm_enabled = false` (kill switch tắt) →
      không có run nào bị tự xác nhận, bất kể đã quá hạn bao lâu.
- [ ] AC-3: Chạy trên nhiều instance backend cùng lúc, hoặc chạy lại sau
      downtime dài, **không** xác nhận trùng một run hai lần và **không** bỏ
      sót run đã quá hạn trong thời gian dừng (idempotent qua điều kiện
      `review_state='pending'` ngay trong câu UPDATE + `SKIP LOCKED`).
- [ ] AC-4: Xử lý theo lô (≤ 500 run/lượt, ≥ 5.000 run/phút) để không khoá
      bảng lâu và không dội tải đột ngột lên indexer khi có tồn đọng lớn sau
      downtime.
- [ ] AC-5: `review_deadline_at` là **giá trị chốt tại thời điểm pipeline tạo
      run** (snapshot). Nếu R3 đổi `ocr_stt.auto_confirm_hours` sau đó, các
      run đang chờ **không** tự đổi deadline — chỉ áp dụng cho run hoàn tất
      sau khi đổi cấu hình. Có thao tác riêng cho R3 "áp lại thời hạn cho các
      bản đang chờ" (tính lại `review_deadline_at` hàng loạt, có xác nhận +
      audit) — UI admin phải nêu rõ hành vi snapshot này để tránh hiểu nhầm
      "đổi ngay có hiệu lực".
- [ ] AC-6: Kết quả tra cứu hiển thị rõ ràng khi `confirm_source = auto` để
      phân biệt với bản người xác nhận thủ công.

---

## Story 6: Đưa run "đang xử lý dở" quá hạn vào danh sách quá hạn xử lý

**As a** cán bộ (R1) / người vận hành theo dõi hàng chờ
**I want** run ở trạng thái `in_review` bị bỏ dở quá
`ocr_stt.review_stale_hours` (mặc định 72h) được đưa vào danh sách "quá hạn
xử lý" thay vì tự động xác nhận
**So that** không có tài liệu bị bỏ dở âm thầm vào chỉ mục chính thức mà chưa
ai kết luận rõ ràng

### Acceptance Criteria

```
Given run review_state = in_review đã quá ocr_stt.review_stale_hours (72h)
      kể từ khi chuyển vào in_review, chưa được xác nhận
When job quét hạn chạy
Then run KHÔNG tự chuyển sang confirmed (khác với run pending quá hạn)
 And run được gắn cờ/đưa vào danh sách "quá hạn xử lý"
 And hệ thống ghi audit cảnh báo
```

- [ ] AC-1: Danh sách "quá hạn xử lý" hiển thị trong hàng chờ (Story 1, filter
      trạng thái), phân biệt rõ với "sắp hết hạn" (run `pending` sắp bị tự
      xác nhận).
- [ ] AC-2: Run bị gắn cờ "quá hạn xử lý" vẫn cho phép chính chủ tiếp tục
      sửa/xác nhận bình thường bất cứ lúc nào — không bị khoá.
- [ ] AC-3: Không có cơ chế nào tự động xác nhận run ở trạng thái này; chỉ
      thoát khỏi trạng thái "quá hạn xử lý" khi R1 chủ động xác nhận.

---

## Story 8: Xử lý tài liệu có nhiều run (đổi/nâng cấp model)

**As a** cán bộ (R1), chính chủ tài liệu
**I want** khi tài liệu có run mới do đổi/nâng cấp model, thấy giao diện so
sánh cạnh nhau giữa bản đã xác nhận cũ và kết quả run mới, và xác nhận độc
lập
**So that** tôi không mất công sức hiệu đính đã làm trước đó, và người dùng
tra cứu luôn có dữ liệu phục vụ liên tục

### Acceptance Criteria

```
Given tài liệu đã có run cũ ở review_state = confirmed
      và có run mới ở review_state = pending (do pipeline chạy lại/đổi model)
When tôi mở tài liệu
Then giao diện hiển thị so sánh cạnh nhau: bản đã xác nhận cũ (chỉ đọc) và
     bản run mới (có thể sửa)
 And bản run cũ (confirmed) vẫn tiếp tục phục vụ tra cứu bình thường cho tới
     khi run mới được xác nhận
```

- [ ] AC-1: Run mới mặc định `auto_confirm_blocked = true` — **không** tự
      động xác nhận theo đồng hồ 24h dù có `review_deadline_at` hay không
      (không có khái niệm "người nạp đang chờ" cho trường hợp re-run).
- [ ] AC-2: Khi tôi xác nhận run mới: run cũ chuyển `review_state =
      superseded`; indexer thay thế dữ liệu theo `run_id` (thông qua sự kiện
      `review.confirmed` của run mới).
- [ ] AC-3: Bản sửa tay của run cũ **không** tự động chuyển sang run mới (do
      phân đoạn/segment khác nhau giữa hai run) — cán bộ phải hiệu đính lại
      trên run mới nếu cần.
- [ ] AC-4: Trước khi xác nhận run mới, tôi vẫn tìm/lọc được nội dung trong
      chính màn hình duyệt (không qua search engine, vì chưa vào chỉ mục).
- [ ] AC-5: Hàng chờ (Story 1) phải phân biệt rõ run nào là bản mới đang chờ
      rà soát khi tài liệu có nhiều run.

---

## Ngoài phạm vi (Out of scope)

- Hạ tầng AI/GPU, engine OCR, ngưỡng `confidence`, quy tắc phân đoạn — thuộc
  `digitization-pipeline.md`.
- Mapping/truy vấn/highlight trong chỉ mục tìm kiếm — thuộc `search-fulltext`
  (chưa có file SA).
- Thông báo email/push nhắc hết hạn — giai đoạn 1 chỉ nhắc trong ứng dụng
  (badge + filter "sắp hết hạn"), không xây kênh thông báo riêng.
- Duyệt hàng loạt (bulk confirm) không nằm trong 8 story gốc; nếu cần, phải
  là story riêng và tuân thủ ràng buộc "không có tài liệu cờ chất lượng trong
  lô duyệt hàng loạt" theo rủi ro đã nêu ở file SA mục 6.
- Thao tác "áp lại thời hạn cho các bản đang chờ" (R3) chỉ được nhắc tới như
  điều kiện của Story 5 (AC-5); nếu cần chi tiết hoá thành story riêng cho R3,
  làm ở bước sau, không gộp vào đây.
- Thao tác "chuyển quyền sở hữu tài liệu" (R3) — xem
  `digitization-review-03-ownership-transfer.md`.
- Phần sửa văn bản/mốc thời gian cho STT + player đồng bộ — xem
  `digitization-review-02-stt.md`.

## Phụ thuộc / Ghi chú kiến trúc liên quan

- Quyết định 1 (scheduler tự xác nhận, snapshot deadline, kill switch) — Story 5.
- Quyết định 2 (máy trạng thái `review_state`/`confirm_source` cấp run, mở
  xem không dừng đồng hồ, `in_review` quá hạn) — Story 1, 2, 6.
- Quyết định 3 (bản gốc AI bất biến, `segment_revision` append-only,
  optimistic lock) — Story 2.
- Quyết định 5 (chỉ chính chủ mới đọc/sửa/xác nhận, một cấp duyệt) — Story 1, 2, 4.
- Quyết định 6 (chỉ index sau xác nhận, `review.confirmed`/`review.reconfirmed`,
  xử lý nhiều run) — Story 4, 8.
- **Đã xử lý (2026-09-07)**: hợp đồng cấp `review_status` giữa file này và
  `digitization-pipeline.md` (nay đã `Approved`) đã đồng bộ về **cấp run** —
  `content_segment` không mang field trạng thái duyệt, chỉ bảng
  `digitization_review` (mô tả ở file SA `digitization-review.md`) mới có.
  **Phụ thuộc còn chưa chốt**: hợp đồng sự kiện với `search-fulltext` — chưa
  có file SA.

## Câu hỏi mở cho DEV

- [ ] Ngưỡng cụ thể cho filter "sắp hết hạn" (Story 1, AC-4) chưa được SA/BA
      chốt số giờ — cần đề xuất mặc định (ví dụ ≤ 4h trước
      `review_deadline_at`) và xác nhận có nên cấu hình qua `system_config`
      hay để cứng trong UI.
- [ ] Tên cột/trường chính xác đánh dấu "thời điểm bắt đầu tính 72h khi vào
      `in_review`" (Story 6) chưa được đặt tên trong ADR (`first_edit_at`?
      thời điểm chuyển trạng thái?) — cần DEV chốt khi thiết kế schema.
- [ ] UX có bắt buộc phải "mở xem" tối thiểu một lần trước khi cho phép bấm
      "Xác nhận" trực tiếp không (Story 4), hay cho phép xác nhận thẳng từ
      danh sách hàng chờ mà không cần mở chi tiết?
- [ ] Xác nhận lại khi `digitization-pipeline.md` chuyển `Approved`: cấp của
      `review_status` phải khớp cấp run theo Quyết định 2 của file SA này.
