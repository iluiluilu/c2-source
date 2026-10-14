# User Story: digitization-review-06-queue-pending-status (phân biệt "chưa có kết quả" vs "đã sẵn sàng duyệt" trong hàng chờ)

> File: `docs/02-ba/stories/digitization-review-06-queue-pending-status.md` — Owner: BA
> Nguồn kiến trúc: `docs/01-sa/architecture/digitization-review.md`
> Bổ sung trực tiếp lên: `docs/02-ba/stories/digitization-review-05-frontend.md` (Story 1 —
> hàng chờ kiểm duyệt, trang `/digitization`, `web/src/app/digitization/page.tsx`)
> Status: Approved

> **Bối cảnh:** Trang "Kiểm duyệt số hoá" hiện chỉ đọc `review_state`/
> `effectiveStatus` của `digitization_run` để chọn nhãn hiển thị. Nhãn "Đang
> chờ" (`pending`) hiện dùng chung cho cả hai tình huống hoàn toàn khác nhau
> về việc cần làm:
> 1. Run vừa tạo, worker OCR/STT/SED/tóm tắt video **chưa xử lý xong**
>    (`processing_status ∈ {queued, running}`) — chưa có gì để xem/duyệt.
> 2. Run đã xử lý AI xong (`processing_status = succeeded`), `review_state`
>    vẫn `pending` — **đã có kết quả**, thực sự đang chờ cán bộ vào duyệt.
>
> Dữ liệu phân biệt hai tình huống này đã tồn tại (`processing_status` trên
> entity `DigitizationRun`; `DigitizationStatusMapper.activeRunTaskStatus` đã
> map trường hợp 2 thành `pending_review` cho bảng
> `document_digitization_status`, hiện chỉ hiển thị ở
> `/admin/document-ownership`). File này chỉ yêu cầu **đưa khả năng phân biệt
> đó ra trang `/digitization`** — không lặp lại các AC nghiệp vụ đã chốt ở
> `-01-ocr.md` đến `-05-frontend.md` (ai được xem, quy tắc optimistic lock,
> ý nghĩa `review_state`/`effectiveStatus`, v.v.).

## User Story

**As a** cán bộ (R1)
**I want** trên trang "Kiểm duyệt số hoá" (`/digitization`), phân biệt được
tài liệu **chưa có kết quả xử lý** (worker đang chạy) với tài liệu **đã có
kết quả, đang thực sự chờ tôi duyệt**, thay vì cả hai đều hiện chung một
nhãn "Đang chờ"
**So that** tôi biết ngay việc nào cần vào duyệt bây giờ và việc nào chưa
cần thao tác gì (chỉ cần chờ), không mất công bấm mở từng tài liệu để kiểm
tra

## Acceptance Criteria

```
Given một run có effectiveStatus = 'pending' (review_state = 'pending', chưa
      quá hạn duyệt)
When  processing_status của run là 'queued' hoặc 'running' (worker chưa xử
      lý xong)
Then  trang /digitization hiển thị cho cán bộ biết run này CHƯA có kết quả
      để duyệt, phân biệt trực quan với trường hợp đã sẵn sàng (AC-2)
```

```
Given một run có effectiveStatus = 'pending'
When  processing_status của run là 'succeeded' (đã xử lý AI xong, review_state
      vẫn đang chờ người dùng xác nhận)
Then  trang /digitization hiển thị cho cán bộ biết run này ĐÃ có kết quả và
      đang thực sự chờ họ vào duyệt, phân biệt trực quan với trường hợp AC-1
```

- [ ] AC-1: Với run có `effectiveStatus = 'pending'` và `processing_status ∈
      {queued, running}`, trang hàng chờ hiển thị badge Trạng thái với nội
      dung "chưa có kết quả" (tinh thần "Đang xử lý"), **khác** với badge
      dùng cho AC-2 và AC-6 cả về màu sắc lẫn nội dung chữ. Đây là badge Trạng
      thái **duy nhất** của dòng đó (không thêm badge phụ bên cạnh) — chốt
      2026-09-21: sửa trực tiếp nội dung/màu badge hiện có theo
      `processingStatus`, giữ nguyên 1-badge-per-row như thiết kế hiện tại.
- [ ] AC-2: Với run có `effectiveStatus = 'pending'` và `processing_status =
      succeeded`, badge Trạng thái của dòng đó đổi thành "đã sẵn sàng, đang
      chờ duyệt" — dùng thuật ngữ nhất quán với nhãn "Chờ duyệt" đã dùng sẵn
      ở `/admin/document-ownership` cho đúng tình huống này, tránh gây hiểu
      hai nghĩa khác nhau cho cùng một cụm từ giữa hai trang.
- [ ] AC-3: Người dùng phân biệt được các tình huống trên **chỉ bằng cách
      nhìn** bảng/danh sách hàng chờ, không cần click mở từng tài liệu để
      biết worker đã xử lý xong, đang chờ duyệt, hay đã lỗi.
- [ ] AC-4: Việc bổ sung phân biệt này **chỉ** áp dụng khi `effectiveStatus =
      'pending'`. Các `effectiveStatus` khác đã có nhãn riêng từ trước
      (`in_review`, `stale`, `upcoming_deadline`, `confirmed`) **giữ nguyên
      hành vi hiện tại**, không thay đổi.
- [ ] AC-5: Ý nghĩa và giá trị trả về của `review_state`/`effectiveStatus`
      trong `GET /digitization-review/queue` **không đổi** — đây vẫn là API
      contract hiện có, đang được dùng ở nhiều nơi khác ngoài trang này.
      `processingStatus` là field **mới, cộng thêm** vào response
      (`RunSummaryView`/`GET /digitization-review/queue`), lấy thẳng từ
      `DigitizationRun.processingStatus` có sẵn trên entity — không cần cột
      DB mới, không cần migration.
- [ ] AC-6 (chốt 2026-09-21, gộp vào story này thay vì tách riêng): với run
      có `effectiveStatus = 'pending'` và `processing_status ∈
      {failed_retryable, failed_permanent}`, badge Trạng thái của dòng đó
      hiển thị nội dung "Lỗi xử lý" (màu đỏ/cảnh báo, khác AC-1 và AC-2). Lý
      do: kiểm tra code xác nhận khi worker lỗi vĩnh viễn, `review_state`
      **không** tự chuyển sang trạng thái khác — run nằm lại `pending` vô
      thời hạn (`DigitizationRunLifecycleServiceImpl`, không có nhánh nào
      set `review_state` khi `processing_status` chuyển
      `failed_retryable`/`failed_permanent`); chỉ có
      `DigitizationDlqAlertJob` báo cho admin qua kênh khác, cán bộ xem
      trang này không có cách nào biết run đã lỗi nếu không có AC-6. Không
      cần AC riêng cho `failed_retryable` (đang tự động retry) so với
      `failed_permanent` (hết lượt) — cùng 1 badge "Lỗi xử lý" cho cả hai,
      phân biệt 2 loại đó không phải mục tiêu của story này.

## Ngoài phạm vi (Out of scope)

- Đổi ý nghĩa/giá trị của `review_state` hoặc `effectiveStatus` hiện có, hay
  đổi máy trạng thái đã chốt ở `digitization-review.md` Quyết định 2 — story
  này chỉ bổ sung một lớp hiển thị phụ dựa trên dữ liệu đã tồn tại.
- Đổi hành vi hoặc nhãn hiện có ở `/admin/document-ownership` (đã có sẵn
  nhãn "Chờ duyệt" đúng tình huống AC-2, giữ nguyên).
- Phân biệt `failed_retryable` với `failed_permanent` trên UI (cả hai dùng
  chung badge "Lỗi xử lý" ở AC-6) — nghiệp vụ không cần cán bộ biết run đang
  tự retry hay đã hết lượt, chỉ cần biết "chưa xong, có vấn đề".
- Cơ chế cho cán bộ retry/huỷ run lỗi từ trang này — AC-6 chỉ yêu cầu
  **hiển thị**, không yêu cầu hành động mới; xử lý run lỗi (nếu có) là
  nghiệp vụ khác, ngoài phạm vi story này.
- Thêm kênh thông báo (email/push) khi run chuyển từ "chưa có kết quả" sang
  "sẵn sàng duyệt" — đã chốt out-of-scope ở `digitization-review.md` mục 1
  (giai đoạn 1 chỉ nhắc trong ứng dụng).
- Thêm option lọc/sắp xếp riêng theo sub-status mới này trong bộ lọc hàng
  chờ (Story 1 AC-2 của `-05-frontend.md`) — story này chỉ yêu cầu **hiển
  thị** phân biệt; có cần filter riêng hay không là quyết định thêm sau, tuỳ
  effort.
- Quyết định kỹ thuật cụ thể việc lấy `processing_status` ra API (thêm field
  mới vào response, hay cách khác) — đây là việc của DEV khi viết tech spec,
  không phải quyết định kiến trúc mới của BA/SA.

## Phụ thuộc / Ghi chú kiến trúc liên quan

- `docs/01-sa/architecture/digitization-review.md` Quyết định 2: `review_state`
  là nguồn sự thật duy nhất cho trạng thái duyệt cấp run; story này không
  đụng tới máy trạng thái đó, chỉ thêm một lớp hiển thị dựa trên
  `processing_status` (đã có sẵn trên entity `DigitizationRun`, không cần
  cột DB mới).
- `docs/02-ba/stories/digitization-review-05-frontend.md` Story 1 (AC-1): mô
  tả các badge `effectiveStatus` hiện có trên trang `/digitization` — story
  này bổ sung trực tiếp vào đúng bảng đó, không tạo màn hình mới.
- Backend: `DigitizationStatusMapper.activeRunTaskStatus` — đã có logic map
  `processing_status = succeeded` + `review_state = pending` →
  `pending_review` cho bảng `document_digitization_status`; đây là tham
  chiếu ngữ nghĩa (không phải yêu cầu tái sử dụng y nguyên code) cho AC-2.
- `web/src/app/admin/document-ownership/page.tsx`: nơi nhãn "Chờ duyệt" đã
  tồn tại cho đúng tình huống AC-2 — dùng để giữ nhất quán thuật ngữ giữa hai
  trang (AC-2).

## Câu hỏi mở cho DEV

Đã chốt cùng người dùng 2026-09-21 (không còn mở):

- ~~Cách đưa `processing_status` ra API~~ → thêm field `processingStatus`
  mới vào response `GET /digitization-review/queue` (xem AC-5).
- ~~Cách trình bày UI~~ → đổi nội dung/màu badge Trạng thái chính, không
  thêm badge phụ (xem AC-1).
- ~~Case `processing_status = failed`~~ → gộp vào story này làm AC-6, không
  tách story riêng.

Không còn câu hỏi mở cho DEV. DEV vẫn tự quyết định các chi tiết triển khai
không ảnh hưởng hành vi (tên biến, cấu trúc SQL CASE, class Tailwind cụ thể
cho badge, ...).
