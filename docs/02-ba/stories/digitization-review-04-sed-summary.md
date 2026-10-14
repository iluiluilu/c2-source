# User Story: digitization-review (phần SED + tóm tắt video)

> File: `docs/02-ba/stories/digitization-review-04-sed-summary.md` — Owner: BA
> Nguồn kiến trúc: `docs/01-sa/architecture/digitization-review.md`
> (cơ chế duyệt cấp run) + `docs/01-sa/architecture/digitization-results-store.md`
> mục 7 điểm 2 (quyết định SED/tóm tắt video bắt buộc qua FR-18) và mục 3.3,
> 3.3.1, 3.5 (schema `content_segment`, `sound_event`)
> Status: Approved

> **Bối cảnh quyết định (2026-09-07):** trước đây dự kiến SED và tóm tắt
> video **bỏ qua** luồng duyệt FR-18 (hiển thị thẳng kèm nhãn AI-generated).
> Quyết định đã đổi (`digitization-results-store.md` mục 7 điểm 2, Approved):
> cả hai **bắt buộc** đi qua đúng máy trạng thái `pending → in_review →
> confirmed` cấp run, y hệt OCR/STT.

Vòng đời duyệt chung — hàng chờ (Story 1), xác nhận (Story 4), tự xác nhận
24h (Story 5), xử lý `in_review` quá hạn 72h (Story 6), quyền chỉ chính chủ
(Quyết định 5), xử lý tài liệu có nhiều run (Story 8) — áp dụng **giống hệt**
cho `task = sed` và `task = video_summary` như đã mô tả ở
`digitization-review-01-ocr.md`; **không lặp lại** ở đây. File này chỉ mô tả
phần **đặc thù**: xem/sửa nội dung SED (nhãn sự kiện âm thanh + player) và
tóm tắt video (văn bản tóm tắt của cả tài liệu).

---

## Story 9: Duyệt nhãn sự kiện âm thanh (SED)

**As a** cán bộ (R1), chính chủ tài liệu
**I want** mở một run SED để nghe lại từng đoạn âm thanh đã được AI nhận
diện sự kiện, xem nhãn gốc (tiếng Anh) song song với nhãn tiếng Việt hiển
thị, sửa nhãn tiếng Việt hoặc đánh dấu nhãn sai
**So that** tôi đảm bảo nhãn sự kiện âm thanh đúng trước khi vào chỉ mục tìm
kiếm chính thức

### Acceptance Criteria

```
Given run SED (task=sed) ở trạng thái pending hoặc in_review,
      document.uploader_sub = tôi
When tôi mở tài liệu
Then hệ thống hiển thị player audio/video cùng danh sách các đoạn (segment)
     đã được "thăng hạng" (content_source='sed'), mỗi đoạn gồm: nhãn gốc
     tiếng Anh (chỉ đọc), nhãn tiếng Việt hiển thị (sửa được), khoảng thời
     gian time_start_ms/time_end_ms, confidence
 And khi tôi bấm vào một đoạn, player tua tới time_start_ms tương ứng để
     nghe lại đúng âm thanh đó (đồng bộ hai chiều, cùng cơ chế Story 3 của
     `digitization-review-02-stt.md`)
 And mỗi đoạn hiển thị rõ nhãn "nội dung do AI sinh"
```

- [ ] AC-1: Danh sách **chỉ** hiển thị các sự kiện đã được thăng hạng thành
      `content_segment` (`content_source='sed'`, tương ứng
      `sound_event.promoted_segment_id` khác NULL). Các detection dưới
      ngưỡng (chỉ tồn tại ở bảng `sound_event`, chưa thăng hạng) **không**
      xuất hiện trong màn hình duyệt — đây là dữ liệu phục vụ audit/QA/hiệu
      chỉnh ngưỡng, ngoài phạm vi màn hình review của cán bộ.
- [ ] AC-2: Sửa nhãn tiếng Việt hiển thị (`current_text` của segment) tuân
      theo đúng cơ chế lưu như OCR (Story 2 của `-01-ocr.md`): tạo
      `segment_revision` mới (append-only), cập nhật cột hiện hành trong
      cùng transaction, optimistic lock theo `current_revision_no` (409 khi
      lệch), lưu ≤ 500ms p95; thao tác sửa đầu tiên chuyển run từ `pending`
      sang `in_review`.
- [ ] AC-3: Nhãn gốc do AI sinh (`ai_text` — nhãn tiếng Việt ban đầu AI gán,
      cùng với nhãn AudioSet gốc tiếng Anh) luôn xem được song song với bản
      hiện hành (diff AI ↔ bản sửa), không bao giờ bị mất/ghi đè — giống
      AC-4 Story 2 của `-01-ocr.md`.
- [ ] AC-4: "Đánh dấu nhãn sai" dùng lại đúng cơ chế **xoá đoạn** đã có ở OCR
      (AC-5, Story 2 của `-01-ocr.md`): tạo `segment_revision(deleted=true)`,
      không xoá vật lý dữ liệu, không thêm cột/trạng thái mới cho riêng SED.
      Đoạn bị đánh dấu sai không được đưa vào chỉ mục khi run được xác nhận.
- [ ] AC-5: Không có thao tác sửa `time_start_ms`/`time_end_ms` của đoạn SED
      trong story này (khác STT) — biên thời gian của sự kiện do model xác
      định, cán bộ chỉ xác nhận **đúng/sai nhãn**, không hiệu chỉnh biên sự
      kiện (xem Out of scope).
- [ ] AC-6: Người không phải `document.uploader_sub` mở tài liệu → từ chối
      (403), đúng quy tắc chung (Quyết định 5, Story 1/2 của `-01-ocr.md`).
- [ ] AC-7: Nhãn "nội dung do AI sinh" hiển thị nhất quán trong toàn bộ vòng
      đời tại màn hình duyệt (`pending`, `in_review`, `confirmed`) để cán bộ
      luôn phân biệt được với nội dung do người thêm tay (`origin='manual'`,
      nếu có).

---

## Story 10: Duyệt tóm tắt video

**As a** cán bộ (R1), chính chủ tài liệu
**I want** mở một run tóm tắt video để đọc đoạn văn bản tóm tắt do AI sinh
(dựa trên bản chuyển lời nói STT đã xác nhận), sửa văn bản nếu cần, và xem
các đoạn STT làm căn cứ
**So that** tôi đảm bảo bản tóm tắt chính xác trước khi vào chỉ mục tìm
kiếm chính thức

### Acceptance Criteria

```
Given run tóm tắt video (task=video_summary) ở trạng thái pending hoặc
      in_review, document.uploader_sub = tôi
When tôi mở tài liệu
Then hệ thống hiển thị đúng 1 đoạn văn bản tóm tắt (content_segment,
     content_source='summary' — mỗi run video_summary chỉ có 1 segment
     duy nhất cho cả tài liệu, không phân đoạn theo trang/thời gian)
 And đoạn tóm tắt hiển thị rõ nhãn "nội dung do AI sinh", cho phép sửa toàn
     bộ văn bản
 And tôi xem được danh sách "nguồn tham chiếu" — các đoạn transcript STT
     (source_segment_ids) mà bản tóm tắt dựa vào — ở chế độ chỉ đọc
```

- [ ] AC-1: Sửa văn bản tóm tắt tuân theo đúng cơ chế lưu như OCR (Story 2
      của `-01-ocr.md`): `segment_revision` append-only, cập nhật cột hiện
      hành trong cùng transaction, optimistic lock theo `current_revision_no`
      (409 khi lệch), lưu ≤ 500ms p95; thao tác sửa đầu tiên chuyển run từ
      `pending` sang `in_review`.
- [ ] AC-2: Vì mỗi run chỉ có đúng 1 segment (`digitization-results-store.md`
      mục 3.3.1), thao tác sửa luôn là sửa nguyên văn bản của đoạn duy nhất
      đó — **không** có thao tác "xoá đoạn"/"thêm đoạn" như OCR/STT trong
      story này (không có khái niệm nhiều đoạn trong một run tóm tắt).
- [ ] AC-3: Bản AI sinh gốc (`ai_text`) luôn xem được song song với bản hiện
      hành (diff AI ↔ bản sửa), không bao giờ bị mất/ghi đè — giống AC-4
      Story 2 của `-01-ocr.md`.
- [ ] AC-4: Danh sách "nguồn tham chiếu" chỉ hiển thị, không cho sửa ở màn
      hình này — sửa nội dung STT nguồn phải thực hiện ở màn duyệt STT
      (`-02-stt.md`) của chính run STT đó, không phải qua màn tóm tắt.
      **Đã chốt 2026-09-07:** mỗi nguồn tham chiếu là 1 liên kết — bấm vào
      sẽ mở màn duyệt STT của run nguồn, cuộn/nhảy tới đúng đoạn
      (`segment_index` tương ứng trong `source_segment_ids`) và tua player
      audio tới `time_start_ms` của đoạn đó (tái dùng cơ chế "bấm vào 1 đoạn
      → `audioRef.currentTime`" đã có ở Story 3 `-02-stt.md` mục 4 bước 4);
      nếu run STT nguồn đã `superseded` (Story 8 `-01-ocr.md`), liên kết mở
      **run `superseded` đó** (không tự chuyển sang run mới) — đúng dữ liệu
      lịch sử mà bản tóm tắt thực sự dựa vào.
      *(Bổ sung 2026-09-23)* Mở từ màn **xem nội dung đã xác nhận**
      (`-07`, người xem có thể không phải chính chủ): liên kết mở màn xem
      chỉ đọc của run nguồn (cũng cuộn + tua tới đúng đoạn); run nguồn
      không còn `confirmed` (ví dụ `superseded`) → hiện dạng chữ, không bấm
      được, kèm chú thích lý do. Player chỉ tua tới đoạn, **không tự phát**
      (trình duyệt chặn tự phát ở tab mới).
- [ ] AC-5: Nếu run STT nguồn (mà `source_segment_ids` trỏ tới) tiếp tục
      được sửa/re-run **sau khi** bản tóm tắt đã `confirmed`, bản tóm tắt
      đã xác nhận **không** tự động cập nhật lại theo nội dung STT mới.
      **Đã chốt 2026-09-07:** hiển thị badge cảnh báo nhẹ "có thể đã lỗi
      thời" trên bản tóm tắt khi `digitization_run.updated_at` (hoặc thời
      điểm segment nguồn được sửa lần gần nhất) của run STT nguồn muộn hơn
      `confirmed_at` của run tóm tắt — chỉ so sánh mốc thời gian, **không**
      tự động đồng bộ lại nội dung tóm tắt.
- [ ] AC-6: Người không phải `document.uploader_sub` mở tài liệu → từ chối
      (403), đúng quy tắc chung (Quyết định 5).
- [ ] AC-7: Nhãn "nội dung do AI sinh" hiển thị nhất quán trong toàn bộ vòng
      đời tại màn hình duyệt (`pending`, `in_review`, `confirmed`), giống
      Story 9 AC-7.

---

## Ngoài phạm vi (Out of scope)

- Vòng đời duyệt chung (hàng chờ, xác nhận, tự xác nhận, xử lý quá hạn, xử
  lý nhiều run, quyền chỉ chính chủ) — xem `digitization-review-01-ocr.md`
  (Story 1, 4, 5, 6, 8), áp dụng nguyên vẹn cho `task = sed` và
  `task = video_summary`.
- Sửa biên thời gian (`time_start_ms`/`time_end_ms`) của đoạn SED — nghiệp
  vụ trong story này chỉ yêu cầu xác nhận đúng/sai nhãn, không hiệu chỉnh
  biên sự kiện; nếu sau này cần, phải là story riêng.
- Ngưỡng "thăng hạng" `sound_event` → `content_segment` và thời điểm bật
  feature flag SED/tóm tắt video — thuộc `digitization-pipeline.md` Quyết
  định 6 và `digitization-results-store.md` mục 7 điểm 4 (chưa chốt số cụ
  thể); story này mô tả UI cho thời điểm flag đã bật.
- Ràng buộc thứ tự chạy run tóm tắt video sau khi run STT của cùng tài liệu
  đã `confirmed` (`depends_on_run_id`, orchestration giữa các run) — thuộc
  tầng điều phối/pipeline (`digitization-results-store.md` mục 5, mục 8 rủi
  ro), không phải màn hình duyệt.
- Tự động tạo lại/cập nhật bản tóm tắt khi transcript STT nguồn thay đổi sau
  khi tóm tắt đã xác nhận — chưa có yêu cầu, xem câu hỏi mở.
- Danh sách hàng chờ lọc theo `task = sed | video_summary` — dùng chung màn
  hình/API của Story 1 (`-01-ocr.md`), chỉ cần thêm 2 giá trị lọc, không mô
  tả lại ở đây.

## Phụ thuộc / Ghi chú kiến trúc liên quan

- `digitization-results-store.md` mục 7 điểm 2 (Approved 2026-09-07): SED và
  tóm tắt video bắt buộc qua cùng máy trạng thái `pending → in_review →
  confirmed` như OCR/STT — nguồn quyết định chính cho toàn bộ file này.
- Quyết định 2 (`digitization-review.md`): máy trạng thái `review_state`/
  `confirm_source` cấp run — áp dụng nguyên vẹn cho `task ∈ {sed,
  video_summary}`.
- Quyết định 3 (`digitization-review.md`): bản gốc AI bất biến,
  `segment_revision` append-only, optimistic lock — áp dụng cho cả nhãn SED
  và văn bản tóm tắt (Story 9 AC-2/AC-3, Story 10 AC-1/AC-3).
- Quyết định 5 (`digitization-review.md`): chỉ chính chủ đọc/sửa/xác nhận —
  Story 9 AC-6, Story 10 AC-6.
- `digitization-results-store.md` mục 3.3 (`content_segment`,
  `content_source ∈ {sed, summary}`) và mục 3.3.1 (vì sao tóm tắt video chỉ
  có đúng 1 segment/run) — Story 10 AC-2.
- `digitization-results-store.md` mục 3.5 (`sound_event`: `label`, `label_vi`,
  `promoted_segment_id`, `verified`) — Story 9 AC-1.
- `digitization-results-store.md` mục 5 và mục 8 (rủi ro `depends_on_run_id`,
  tóm tắt chạy sau khi STT đã `confirmed`) — bối cảnh cho Story 10, không
  phải phạm vi xử lý của story này.

## Câu hỏi mở cho DEV

4 câu dưới đây đã được người dùng chốt ngày **2026-09-07** — giữ lại như
decision log:

- [x] **UI danh sách nhãn SED + player:** chốt **dùng chung layout** "danh
      sách + player đồng bộ" đã xây cho STT (Story 3, `-02-stt.md`), chỉ đổi
      cột nội dung (nhãn thay vì đoạn văn bản dài) và ẩn control sửa mốc
      thời gian (AC-5 Story 9) — tái dùng component, giảm effort FE.
- [x] **Giới hạn độ dài văn bản tóm tắt video:** chốt **cảnh báo mềm với
      ngưỡng riêng, lớn hơn STT** (ví dụ 3000–5000 ký tự — DEV chốt số cụ
      thể khi viết tech spec, không phải quyết định nghiệp vụ) — không dùng
      chung ngưỡng nhỏ của STT (500 ký tự/đoạn) vì tóm tắt là 1 đoạn văn dài
      cho cả video, không phải 1 câu ngắn.
- [x] **Hiển thị "nguồn tham chiếu":** chốt **liên kết nhảy sang đúng đoạn**
      trong màn duyệt STT (đã đặc tả cụ thể ở Story 10 AC-4) — không dùng
      phương án liệt kê tĩnh.
- [x] **Cảnh báo tóm tắt lỗi thời:** chốt **có**, thêm badge cảnh báo nhẹ
      khi STT nguồn bị sửa sau khi tóm tắt đã `confirmed` (đã đặc tả cụ thể
      ở Story 10 AC-5) — chỉ so sánh mốc thời gian, không tự đồng bộ nội
      dung.

Còn 1 câu **chưa chốt** — cần số liệu, không phải lựa chọn UX:

- [ ] Ngưỡng "thăng hạng" `sound_event` → `content_segment` và thời điểm bật
      feature flag SED/tóm tắt video (`digitization-results-store.md` mục 7
      điểm 4) — chờ kết quả benchmark (`Epic A6`,
      `digitization-pipeline-02-ocr-stt-processing.md`) trước khi chốt số
      cụ thể; ảnh hưởng thời điểm story này thực sự được dùng trong sản
      xuất, không chặn việc viết tech spec (giả định flag đã bật khi viết
      story, đúng quy ước các slug khác đang áp dụng).
