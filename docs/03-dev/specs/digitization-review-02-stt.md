# Tech Spec: digitization-review-02-stt

> File: `docs/03-dev/specs/digitization-review-02-stt.md` — Owner: DEV
> Nguồn story: `docs/02-ba/stories/digitization-review-02-stt.md`
> Status: Approved

> **Phụ thuộc — tất cả đã Approved (2026-09-07):** story nguồn, SA
> `digitization-review.md`, `docs/03-dev/specs/digitization-review-01-ocr.md`
> (**bắt buộc đọc trước** file này: toàn bộ vòng đời duyệt cấp run, endpoint
> `PATCH/DELETE /digitization-review/segments/{id}`,
> `DigitizationRunLifecycleService.reviseSegment` dùng chung OCR/STT đều
> định nghĩa ở đó — file này chỉ mô tả phần bổ sung cho `task=stt`), và
> `docs/03-dev/specs/digitization-pipeline-02-ocr-stt-processing.md`
> (nguồn migration `V27` — cột `duration_ms` + trigger chặn mốc thời gian).

## 1. Tóm tắt kỹ thuật

**Sửa lại một điểm quan trọng so với chữ trong story BA**: story
`digitization-review-02-stt.md` viết nhiều lần "`document.duration_ms`",
nhưng theo tech spec đã chốt trước đó
(`digitization-pipeline-02-ocr-stt-processing.md` §2, migration `V27`), cột
độ dài tệp gốc thực tế nằm ở **`digitization_source.duration_ms`** (đo bằng
`ffprobe`, ghi khi `completeRunSuccess`), truy cập qua
`digitization_run.source_id`, **không phải** trên bảng `document`. `V27`
cũng đã tự cài đặt **trigger DB** `fn_check_segment_within_source_duration`
chặn `content_segment.time_end_ms`/`current_time_end_ms` vượt
`duration_ms` — đây chính là "tầng PostgreSQL" của Quyết định 4 SA, **đã có
sẵn**, spec này không tạo migration mới, chỉ thêm **tầng service** (tier 2)
phía trước để trả lỗi 422 thân thiện thay vì để lộ exception thô từ trigger.

Không có endpoint STT riêng: `PATCH /digitization-review/segments/{id}`
(đã định nghĩa ở file -01) nhận thêm 2 trường `timeStartMs`/`timeEndMs` khi
segment thuộc run `task=stt`; validate bổ sung nằm trong
`ReviewController`/`ReviewService` trước khi gọi
`DigitizationRunLifecycleService.reviseSegment`.

## 2. Data model

Không có migration mới trong file này. Bảng liên quan (đã tạo ở nơi khác):

- `digitization_source.duration_ms` (`V27`, `digitization-pipeline-02...md`).
- `content_segment.time_start_ms`/`time_end_ms` (bản AI, bất biến, `V26`),
  `current_time_start_ms`/`current_time_end_ms` (bản hiện hành, `V26`).
- Trigger `trg_content_segment_within_duration` (`V27`) — chốt chặn cuối,
  áp dụng cho cả `INSERT` (worker ghi kết quả) và `UPDATE` (cán bộ sửa qua
  `reviseSegment`).

## 3. API contract

Mở rộng `PATCH /digitization-review/segments/{segmentId}` (định nghĩa gốc ở
`digitization-review-01-ocr.md` §3):

| Method | Endpoint | Request (bổ sung cho `task=stt`) | Response |
|---|---|---|---|
| PATCH | `/digitization-review/segments/{segmentId}` | `{ "expectedRevisionNo": int, "text"?: string, "timeStartMs"?: int, "timeEndMs"?: int, "reason"?: string }` | `200 SegmentView` (thêm `overlapsAdjacent: boolean`, xem 3.2) hoặc `409` (optimistic lock) hoặc **`422`** (vi phạm mốc thời gian — mục 4) |

`timeStartMs`/`timeEndMs` là **optional cùng nhau**: nếu cả hai đều vắng mặt
trong request → chỉ sửa văn bản (luôn cho phép, kể cả khi `duration_ms`
NULL — US3 AC-4). Nếu một trong hai có mặt → validate theo mục 4.

### 3.1 `RunDetail` (mở rộng cho `task=stt`)

Đã có `sourceDurationMs` ở file -01 §3.2 (nullable) — FE dùng trực tiếp
trường này để kẹp giá trị nhập trong `[0, sourceDurationMs]` (tier 1,
frontend, chỉ UX).

### 3.2 `SegmentView` (mở rộng cho `task=stt`)

Thêm `overlapsAdjacent: boolean` — `true` nếu khoảng
`[currentTimeStartMs, currentTimeEndMs]` của segment này chồng lấn với
segment liền trước **hoặc** liền sau theo `segment_index` (chỉ so 2 lân
cận, không phải toàn bộ run — đủ cho cảnh báo UX theo US3 AC-5, chi phí
O(1)/segment thay vì O(n) toàn run). Tính ở tầng đọc (`GET /runs/{id}`),
không lưu cột riêng — giá trị chỉ có ý nghĩa hiển thị, không chặn ghi.

## 4. Luồng xử lý chính

1. **Validate tier 2 (service, trước khi gọi `reviseSegment`)** — trả lời
   trực tiếp US3 AC-2/AC-3/AC-4 bằng thông báo lỗi rõ ràng (thay vì để lộ lỗi
   trigger Postgres thô):
   ```
   if (timeStartMs != null || timeEndMs != null) {
       durationMs = segmentRepository.findSourceDurationMs(segmentId); // join
                     // content_segment -> digitization_run -> digitization_source
       if (durationMs == null) {
           throw 422 "Chưa xác định được độ dài tệp gốc, không thể sửa mốc thời gian";
       }
       effectiveStart = timeStartMs != null ? timeStartMs : segment.currentTimeStartMs;
       effectiveEnd   = timeEndMs   != null ? timeEndMs   : segment.currentTimeEndMs;
       if (effectiveStart < 0 || effectiveStart >= effectiveEnd || effectiveEnd > durationMs) {
           throw 422 "Mốc thời gian không hợp lệ (0 ≤ start < end ≤ độ dài tệp gốc)";
       }
   }
   ```
   Validate **toàn bộ trước khi mở transaction ghi** → vi phạm thì
   **không có write nào xảy ra**, giá trị cũ giữ nguyên đúng nghĩa đen US3
   AC-2 ("rollback toàn bộ thao tác sửa của request").
2. **Ghi (nếu hợp lệ)**: gọi `reviseSegment` (file -01 §3.4) như OCR, chỉ
   khác ở việc truyền thêm `newTimeStartMs`/`newTimeEndMs`. Trigger `V27` là
   lưới chặn cuối cho các đường ghi khác (job sửa lỗi, migration) — ở luồng
   API bình thường sẽ không bao giờ chạm tới trigger vì bước 1 đã chặn trước
   (US3 AC-3, "nhiều tầng").
3. **Chồng lấn giữa các đoạn**: **không kiểm tra, không chặn** ở bất kỳ tầng
   nào (US3 AC-5) — chỉ tính `overlapsAdjacent` khi đọc (mục 3.2).
4. **Player đồng bộ (US3 given/then)**: thuần frontend, không có API riêng.
   Đề xuất kỹ thuật (câu hỏi mở BA): dùng thẳng phần tử HTML5
   `<audio>`/`<video>` (API `currentTime`/`seeked`/`timeupdate` có sẵn của
   trình duyệt) thay vì thêm thư viện waveform ngoài — đáp ứng đúng tinh
   thần "không tự dựng waveform editor, dùng thư viện sẵn có" mà không cần
   thêm dependency: bấm vào 1 đoạn → `audioRef.currentTime =
   segment.currentTimeStartMs / 1000`; lắng nghe `timeupdate` → tìm segment
   có `currentTimeStartMs ≤ t*1000 < currentTimeEndMs` để highlight. Nếu về
   sau cần hiển thị waveform trực quan, có thể thêm `wavesurfer.js` overlay
   lên trên (không thay thế cơ chế đồng bộ này), nhưng **không** nằm trong
   phạm vi MVP.
5. **Giới hạn ký tự khi sửa văn bản** (câu hỏi mở BA): **không** giới hạn
   cứng ở backend (SA không yêu cầu, tránh chặn nhầm trường hợp hợp lệ) —
   chỉ cảnh báo mềm ở frontend khi văn bản một đoạn vượt một ngưỡng UX (ví
   dụ 500 ký tự), không phải bất biến dữ liệu.

## 5. Test plan

**Unit test (mở rộng `ReviewServiceTest` của file -01):**

- `timeStartMs`/`timeEndMs` đều null → chỉ gọi `reviseSegment` với text,
  không query `duration_ms` (tối ưu, không bắt buộc nghiệp vụ).
- `durationMs = null`, gửi `timeStartMs=1000` → 422, `reviseSegment` KHÔNG
  được gọi (verify mock).
- `durationMs = 60000`, `start=1200, end=4500` → hợp lệ, gọi `reviseSegment`
  đúng tham số (US3 AC-1 example).
- `start >= end`, `end > duration`, `start < 0` → mỗi case 422, thông báo
  lỗi khác nhau theo loại vi phạm (dễ debug/QA).
- Chỉ gửi `timeEndMs` (không gửi `timeStartMs`) → dùng
  `currentTimeStartMs` hiện tại làm `effectiveStart` để validate — test rõ
  case "chỉ sửa 1 đầu mốc thời gian".

**Integration test (Testcontainers Postgres, `V1..V28` + `V27`):**

- PATCH hợp lệ trên segment `task=stt` có `duration_ms` xác định → 200,
  `current_time_start_ms`/`current_time_end_ms` cập nhật đúng, có dòng
  `segment_revision` mới.
- PATCH với `end > duration_ms` qua thẳng service (bỏ qua tier 1 frontend,
  mô phỏng gọi API trực tiếp) → 422, `current_time_*` **không đổi** (query
  lại xác nhận giá trị cũ).
- 2 segment liền kề có mốc thời gian chồng lấn → PATCH thành công (không bị
  chặn), `GET /runs/{id}` trả `overlapsAdjacent=true` cho cả hai.
- Segment `task=ocr` gửi kèm `timeStartMs` (dữ liệu sai định dạng từ FE lỗi)
  → service bỏ qua/từ chối rõ ràng (400) thay vì âm thầm ghi giá trị vô
  nghĩa vào cột `time_*` của OCR.

## 6. Task breakdown

- [x] Task 1: Thêm validate tier 2 (mục 4 bước 1) vào `ReviewService`
      (cùng class/service đã viết ở file -01, không tạo service riêng cho
      STT). — implemented as `ReviewController.validateSttTimeBounds`
      (không có class `ReviewService` riêng ở file -01, logic nằm trong
      `ReviewController` như OCR).
- [x] Task 2: `ReviewSegmentRepository.findSourceDurationMs(segmentId)`
      (native query join `content_segment -> digitization_run ->
      digitization_source`).
- [x] Task 3: Tính `overlapsAdjacent` khi build `SegmentView` trong
      `GET /runs/{id}` (mục 3.2).
- [ ] Task 4: Frontend `web/src/app/review/` màn STT — player HTML5 native
      + danh sách đoạn đồng bộ hai chiều (mục 4 bước 4), kẹp input mốc thời
      gian trong `[0, sourceDurationMs]`. — chưa làm ở lượt implement này
      (backend-only pass); cần một lượt implement riêng cho FE.
- [x] Task 5: Viết test theo mục 5 (unit: `ReviewControllerSttTimeValidationTest`,
      `SegmentViewTest`; integration: mở rộng `ReviewControllerIT`, không
      chạy được trong sandbox này — xem ghi chú Javadoc của lớp đó).

## 7. Rủi ro kỹ thuật

- **Lệch tên cột với story BA** (`document.duration_ms` → thực tế
  `digitization_source.duration_ms`) — đã ghi rõ ở mục 1; nếu người dùng
  review sáng mai muốn cột này thực sự nằm trên `document` (ví dụ để dùng
  chung cho các luồng khác ngoài digitization), cần một quyết định SA riêng
  và một migration di chuyển dữ liệu — không sửa ở spec này.
- **Phụ thuộc trigger `V27`** (`digitization-pipeline-02`, đã `Approved`,
  không đổi) — tầng service (mục 4 bước 1) vẫn đứng độc lập được (không phụ
  thuộc trigger để hoạt động đúng ở luồng API bình thường), trigger chỉ là
  "lưới đỡ cuối" cho các đường ghi khác (job sửa lỗi thủ công).
- **`overlapsAdjacent` chỉ so 2 lân cận, không phải toàn run** — nếu cán bộ
  sắp xếp lại thứ tự đoạn hoặc chèn đoạn thủ công ở giữa (US2 AC-5, file -01),
  cảnh báo chồng lấn có thể bỏ sót cặp không liền kề theo `segment_index`
  nhưng chồng lấn thời gian thực tế; chấp nhận được vì đây chỉ là cảnh báo
  UX (US3 AC-5 không yêu cầu chặn), không phải bất biến dữ liệu.
- **Không giới hạn ký tự backend** (mục 4 bước 5) — nếu về sau phát sinh vấn
  đề UX thật (một đoạn quá dài làm player khó dùng), thêm giới hạn là thay
  đổi nhỏ ở tầng validate, không ảnh hưởng schema.
