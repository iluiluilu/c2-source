# User Story: digitization-results-store

> File: `docs/02-ba/stories/digitization-results-store.md` — Owner: BA
> Nguồn kiến trúc: `docs/01-sa/architecture/digitization-results-store.md`
> Status: Draft

> **Lưu ý quan trọng:** File kiến trúc nguồn
> (`digitization-results-store.md`) đang **Status: Draft** với **4 điểm chưa
> chốt** ở mục 7 (hợp nhất bảng job+run; SED/tóm tắt video có qua luồng duyệt
> FR-18 hay không; đồng bộ enum `content_source` với `ai-search-infra.md`;
> ngưỡng thăng hạng `sound_event`). Story này được viết theo **phương án mặc
> định/an toàn mà chính SA đã đề xuất** cho từng điểm (xem bảng ở mục "Giả
> định kế thừa từ SA" bên dưới), để không chặn tiến độ chuẩn bị story. **Cần
> người dùng duyệt lại cả file SA lẫn các giả định này trước khi DEV viết
> tech spec** — xem thêm "Câu hỏi mở cho DEV".

## Bối cảnh

`digitization-results-store` là thiết kế lưu trữ (Postgres + chỉ mục OpenSearch — vector k-NN thay Qdrant từ 2026-10-10) cho kết
quả của 4 loại số hoá: OCR, STT, Sound Event Detection (SED), và tóm tắt nội
dung video. Đây **không phải** một tính năng có UI/thao tác người dùng trực
tiếp — nó là hạ tầng dữ liệu mà các luồng khác (`digitization-pipeline`,
`digitization-review`, `search-fulltext`/`search-semantic`) đọc/ghi vào. Vì
vậy các story dưới đây viết ở góc độ **hệ thống**, với acceptance criteria mô
tả hành vi lưu trữ có thể kiểm chứng bằng truy vấn/kiểm thử DB, không phải
thao tác UI.

## Giả định kế thừa từ SA (mục 7 file kiến trúc — chưa Approved)

| # | Điểm chưa chốt | Phương án mặc định áp dụng trong story này |
|---|---|---|
| 1 | Hợp nhất `digitization_job` + `digitization_run` | **Hợp nhất thành 1 bảng** `digitization_run` (như thiết kế mục 3.1) |
| 2 | SED/tóm tắt video có qua luồng duyệt FR-18 không | **Không** — mặc định `review_state='not_applicable'`, hiển thị ngay kèm nhãn "AI-generated", không qua `pending→in_review→confirmed` |
| 3 | Enum `content_source` lệch giữa 2 tài liệu Approved | Dùng enum **mới** (`ocr, stt, sed, summary`), bỏ `native_text` — cần đồng bộ `ai-search-infra.md` mục 8 riêng, không nằm trong scope story này |
| 4 | Ngưỡng thăng hạng `sound_event` → `content_segment` | **Chưa có con số** — để ngỏ, AC chỉ kiểm tra cơ chế "có ngưỡng, có promote", không kiểm tra giá trị cụ thể |

---

## User Story 1 — Trạng thái run hợp nhất (xử lý + duyệt trong 1 thực thể)

**As a** hệ thống số hoá tài liệu
**I want** lưu trạng thái xử lý (queue/GPU) và trạng thái duyệt nội dung của
một lần chạy AI trong **cùng một bản ghi** `digitization_run`
**So that** các dịch vụ khác (worker, UI kiểm duyệt, read-model trạng thái)
truy vấn trạng thái của một lần chạy mà không phải JOIN 2 bảng, và không có
nguy cơ 2 khoá chính lệch nhau.

### Acceptance Criteria

```
Given một tài liệu vừa được nạp và đã có digitization_source (Pha 1)
When hệ thống enqueue một lần chạy AI cho task 'ocr' (hoặc 'stt')
Then một dòng digitization_run được tạo với processing_status='queued'
  và review_state mặc định phù hợp với task đó (xem User Story 3)
  — không có bảng "digitization_job" riêng nào được tạo/ghi song song
```

- [ ] AC-1: `digitization_run` chứa cả cột vòng đời xử lý
      (`processing_status`, `attempt_count`, `lease_expires_at`, `worker_id`,
      `last_error`) lẫn cột vòng đời duyệt (`review_state`,
      `review_deadline_at`, `confirm_source`, `confirmed_by`, `confirmed_at`)
      trong cùng một bảng — không tồn tại bảng `digitization_job` tách rời.
- [ ] AC-2: Với mỗi cặp `(document_id, task)`, tại một thời điểm chỉ có **tối
      đa 1** run đang "active" (`review_state` không thuộc
      `{superseded, void}`) — được đảm bảo ở tầng DB (constraint/unique
      index), không chỉ kiểm tra ở tầng ứng dụng.
- [ ] AC-3: Lịch sử các run cũ (đã `superseded`/`void`) của cùng
      `(document_id, task)` vẫn được giữ lại, không bị xoá — phục vụ tra
      cứu lịch sử số hoá lại.
- [ ] AC-4: Worker lấy job để xử lý (poll hàng đợi) và màn hình kiểm duyệt lấy
      hàng chờ duyệt đều truy vấn được hiệu quả trên cùng bảng
      `digitization_run` (có index hỗ trợ riêng cho từng nhu cầu poll:
      theo `processing_status`, và theo `review_state`).

---

## User Story 2 — Segment giữ song song bản AI gốc và bản hiện hành

**As a** hệ thống lưu trữ nội dung số hoá
**I want** mỗi `content_segment` lưu **bản AI sinh ra ban đầu** (`ai_text`,
`ai_time_start_ms`, `ai_time_end_ms`) tách biệt và bất biến, song song với
**bản hiện hành** (`current_text`, `current_time_start_ms`,
`current_time_end_ms`) có thể bị sửa qua thời gian
**So that** hệ thống kiểm duyệt/đối chiếu có thể so sánh "AI sinh gì" với
"cán bộ đã sửa thành gì" bất kỳ lúc nào, kể cả sau nhiều lần sửa.

### Acceptance Criteria

- [ ] AC-1: Ngay sau khi một run hoàn tất (`processing_status='succeeded'`),
      với mỗi `content_segment` được tạo ra: `ai_text = current_text` và
      `ai_time_* = current_time_*` (bản hiện hành ban đầu trùng bản AI).
- [ ] AC-2: Sau khi cán bộ sửa nội dung một segment (ghi một dòng
      `segment_revision` mới), `current_text`/`current_time_*` được cập nhật
      theo bản sửa, nhưng `ai_text`/`ai_time_*` của segment đó **không đổi**
      — vẫn giữ nguyên giá trị AI sinh ra lần đầu, bất kể sửa bao nhiêu lần.
- [ ] AC-3: `is_edited` chuyển thành `true` và `current_revision_no` tăng lên
      đúng bằng số lần sửa đã ghi nhận trong `segment_revision` của segment
      đó.
- [ ] AC-4: Lịch sử sửa (`segment_revision`) là append-only — không có thao
      tác nào UPDATE/DELETE một dòng `segment_revision` đã ghi.

---

## User Story 3 — Ràng buộc mốc thời gian được chặn ở tầng DB

**As a** hệ thống lưu trữ nội dung số hoá (STT/SED)
**I want** ràng buộc `time_start_ms < time_end_ms` (và khi cần, nằm trong
`[0, duration_ms]` của nguồn) được thực thi ở tầng cơ sở dữ liệu
**So that** không thể tồn tại dữ liệu mốc thời gian sai lệch dù lỗi phát sinh
từ bug ứng dụng, thao tác tay, hay script migrate dữ liệu.

### Acceptance Criteria

```
Given một content_segment hoặc sound_event có gắn mốc thời gian
When một câu lệnh INSERT/UPDATE cố ghi time_start_ms >= time_end_ms
Then câu lệnh đó bị Postgres từ chối bằng lỗi vi phạm CHECK constraint
  — không phải lỗi được bắt/validate ở tầng ứng dụng rồi mới chặn
```

- [ ] AC-1: `content_segment` có CHECK constraint chặn
      `ai_time_start_ms >= ai_time_end_ms` khi cả hai không NULL.
- [ ] AC-2: `content_segment` có CHECK constraint tương tự cho
      `current_time_start_ms`/`current_time_end_ms` (bản hiện hành cũng phải
      thoả bất biến, không chỉ bản AI gốc).
- [ ] AC-3: `sound_event` có CHECK constraint chặn
      `time_start_ms >= time_end_ms` (bắt buộc NOT NULL cho cả 2 cột, vì mọi
      detection SED đều có mốc thời gian).
- [ ] AC-4: Với các loại nội dung không gắn thời gian (OCR, tóm tắt video),
      các cột `time_start_ms`/`time_end_ms` cho phép NULL và không bị ràng
      buộc trên — CHECK constraint chỉ áp dụng khi giá trị không NULL.
- [ ] AC-5 (mở, cần DEV xác nhận khi viết tech spec): nếu nghiệp vụ cần kiểm
      tra thêm `time_end_ms <= duration_ms` của `digitization_source`, việc
      này đòi hỏi truy vấn chéo bảng (không thể làm bằng CHECK constraint
      đơn bảng) — DEV cân nhắc trigger hoặc kiểm tra ở tầng ứng dụng khi ghi
      run, ghi rõ lựa chọn trong tech spec.

---

## User Story 4 — Read-model trạng thái tổng hợp cấp tài liệu

**As a** hệ thống hiển thị danh sách tài liệu (kho chung)
**I want** một bảng `document_digitization_status` tổng hợp sẵn trạng thái 4
task (OCR/STT/SED/tóm tắt), cập nhật cùng transaction với mỗi thay đổi trạng
thái của `digitization_run`
**So that** màn hình danh sách tài liệu hiển thị badge trạng thái số hoá cho
hàng trăm nghìn/triệu dòng mà không phải JOIN + GROUP BY `digitization_run`
mỗi lần render.

### Acceptance Criteria

```
Given một tài liệu có digitization_run(task='stt') đang processing_status='running'
When run đó chuyển sang processing_status='succeeded' và review_state='pending'
Then document_digitization_status.stt_status của tài liệu đó được cập nhật
  thành 'pending_review' trong CÙNG transaction với việc cập nhật run
  — không có độ trễ/eventual consistency giữa 2 bảng
```

- [ ] AC-1: Mỗi khi `digitization_run.processing_status` hoặc `review_state`
      đổi giá trị, cột tương ứng trong `document_digitization_status`
      (`ocr_status`/`stt_status`/`sed_status`/`video_summary_status`) được
      upsert trong cùng transaction — không có job nền/async cập nhật trễ.
- [ ] AC-2: `overall_status` được tính lại theo đúng thứ tự ưu tiên đã định:
      `failed` > `processing`/`queued` > `in_review` > `pending_review` >
      `confirmed` > `not_applicable`, dựa trên giá trị hiện tại của 4 cột
      task.
- [ ] AC-3: Với tài liệu chưa từng số hoá (chưa có `digitization_run` nào),
      truy vấn `document_digitization_status` (nếu có dòng) trả về toàn bộ
      `not_applicable`, hoặc không có dòng nào — hành vi cụ thể (có tạo dòng
      mặc định khi document được tạo hay tạo lazy khi có run đầu tiên) để
      DEV quyết định khi viết tech spec, miễn nhất quán.
- [ ] AC-4: Không có luồng nào khác (ngoài luồng cập nhật `digitization_run`
      đã liệt kê) được phép ghi trực tiếp vào
      `document_digitization_status` — tránh 2 nguồn ghi cùng 1 bảng dẫn đến
      lệch dữ liệu.
- [ ] AC-5: Có cơ chế đối soát định kỳ (job/query) so khớp
      `document_digitization_status` với trạng thái thực tế tổng hợp từ
      `digitization_run`, để phát hiện lệch nếu một đường ghi nào đó quên
      upsert (rủi ro đã nêu ở file SA mục 8).

---

## User Story 5 — SED: lưu mọi detection, chỉ detection vượt ngưỡng mới thăng hạng

**As a** hệ thống số hoá âm thanh (Sound Event Detection)
**I want** ghi lại **toàn bộ** kết quả detection (kể cả dưới ngưỡng tin cậy)
vào `sound_event`, và chỉ tạo `content_segment` tìm-kiếm-được cho những
detection **vượt ngưỡng**
**So that** dữ liệu thô đầy đủ luôn sẵn có để audit/QA hoặc hiệu chỉnh lại
ngưỡng sau này, mà không làm nhiễu kết quả tìm kiếm bằng các detection có độ
tin cậy thấp.

### Acceptance Criteria

```
Given một run task='sed' hoàn tất với N detection thô từ model
When hệ thống ghi kết quả
Then cả N dòng sound_event được ghi (không lọc bớt dòng nào)
  và chỉ những dòng có confidence >= ngưỡng thăng hạng mới có thêm
  một content_segment(content_source='sed') tương ứng, với
  sound_event.promoted_segment_id trỏ tới segment đó
```

- [ ] AC-1: Số dòng `sound_event` được ghi cho một run SED bằng đúng số
      detection thô mà model trả về — không có bước lọc bỏ detection dưới
      ngưỡng trước khi ghi.
- [ ] AC-2: Với detection dưới ngưỡng, `sound_event.promoted_segment_id` là
      NULL — không tạo `content_segment` cho detection đó.
- [ ] AC-3: Với detection vượt ngưỡng (thăng hạng), tồn tại đúng 1
      `content_segment(content_source='sed')` tương ứng và
      `sound_event.promoted_segment_id` trỏ đúng tới segment đó.
- [ ] AC-4: Ngưỡng thăng hạng cụ thể **chưa được chốt** ở bước SA (file kiến
      trúc mục 7 điểm 4) — AC này chỉ xác nhận **cơ chế** hai tầng
      (lưu-toàn-bộ / thăng-hạng-có-điều-kiện) tồn tại; con số ngưỡng cụ thể
      là câu hỏi mở, DEV/SA cần chốt trước hoặc trong lúc viết tech spec,
      cùng thời điểm bật feature flag SED.
- [ ] AC-5: Trường `verified` trên `sound_event` cho phép cán bộ đánh dấu đã
      xác minh thủ công một detection (gợi ý AI cần xác minh) — mặc định
      `false`, không ảnh hưởng tới việc detection đó đã được thăng hạng hay
      chưa (2 khái niệm độc lập).

---

## User Story 6 — Chỉ mục (OpenSearch, gồm vector) chỉ nhận nội dung đã xác nhận, không lưu trạng thái/bản nháp

> Sửa 2026-10-10: Qdrant đã gỡ khỏi hạ tầng; vector nằm trên document
> `segment` của OpenSearch (`search-semantic.md` QĐ 6). Mọi chỗ "Qdrant" trong
> story này đã đổi thành "chỉ mục".

**As a** hệ thống tìm kiếm ngữ nghĩa
**I want** index `segment` (OpenSearch, gồm field vector) chỉ chứa các `content_segment`
đã có `digitization_run.review_state='confirmed'`, không lưu trạng thái xử
lý hay bản nháp chưa xác nhận
**So that** kết quả tìm kiếm không bao giờ trả về nội dung chưa được xác
nhận là đúng, và chỉ mục luôn có thể dựng lại hoàn toàn từ Postgres
nếu bị mất/hỏng.

### Acceptance Criteria

```
Given một content_segment thuộc run có review_state='pending' (chưa duyệt)
When hệ thống tìm kiếm ngữ nghĩa truy vấn chỉ mục
Then segment đó KHÔNG xuất hiện trong kết quả vì chưa từng được ghi
  vào chỉ mục
```

- [ ] AC-1: Chỉ segment thuộc run có `review_state='confirmed'` mới được
      ghi vào chỉ mục (text lẫn vector) — không có đường ghi nào đưa segment ở trạng thái
      khác (`pending`, `in_review`, `superseded`, `void`) vào chỉ mục.
- [ ] AC-2: Document `segment` trong chỉ mục không chứa bất kỳ trường nào
      biểu diễn `processing_status`/`review_state`/trạng thái duyệt — chỉ
      giữ khoá (`segment_id`), ACL, trường lọc phục vụ truy vấn (`content_
      source`, `document_id`, `page_no`, `time_start_ms`, `time_end_ms`,
      v.v. theo mục 4 file SA), text cho nhánh toàn văn và vector; nội
      dung hiển thị/trích dẫn luôn lấy từ Postgres, không từ chỉ mục.
- [ ] AC-3: Theo giả định mục 7 điểm 2 (SED/tóm tắt video không qua FR-18):
      với `content_source in ('sed', 'summary')`, điều kiện vào chỉ mục là
      một sự kiện tương đương "đã hoàn tất và sẵn sàng hiển thị" (ví dụ
      `sed.promoted`/`summary.generated`) — **không** đợi `review_state=
      'confirmed'` vì các loại này mặc định `not_applicable`. Document chỉ
      mục của các segment này set `confirm_source='manual_unchanged'` theo quy ước
      tạm đã nêu ở file SA mục 4 — DEV cần định nghĩa rõ tên/điều kiện sự
      kiện này khi viết tech spec (câu hỏi mở, phụ thuộc việc chốt mục 7
      điểm 2).
- [ ] AC-4: Nếu một segment đã `confirmed` sau đó bị sửa lại (tạo revision
      mới) làm run chuyển sang trạng thái yêu cầu duyệt lại
      (`digitization-review.md` — reconfirm), chỉ mục phải được cập nhật
      (ghi lại text + embed lại vector) để phản ánh `current_text` mới nhất, không giữ bản cũ.
- [ ] AC-5: Xoá/void một run hoặc một segment ở Postgres phải kèm xoá điểm
      tương ứng khỏi chỉ mục (không để "mồ côi") — cơ chế cụ thể (đồng bộ hay
      qua hàng đợi) để DEV quyết định khi viết tech spec.

---

## Ngoài phạm vi (Out of scope)

- Engine/model cụ thể chạy OCR/STT/SED (thuộc `digitization-pipeline.md`).
- Chi tiết luồng duyệt/sửa/tự xác nhận 24h cho OCR/STT (thuộc
  `digitization-review.md`) — story này chỉ dùng lại `review_state` như một
  trường trạng thái, không định nghĩa lại máy trạng thái duyệt.
- API/truy vấn tìm kiếm full-text/semantic cụ thể (thuộc
  `search-fulltext.md`/`search-semantic.md`).
- Intake/dispatch Pha 1 (từ 2026-09-23 video không còn tách nguồn —
  `digitization-pipeline.md` Quyết định 10).
- Bật thật sự tính năng SED và tóm tắt video cho người dùng cuối (đang sau
  feature flag tắt, chờ xác nhận nghiệp vụ/FR riêng) — story này chỉ đảm bảo
  **hạ tầng lưu trữ đã sẵn sàng**, không yêu cầu bật flag.
- Sửa file `ai-search-infra.md` mục 8 (đồng bộ enum `content_source`) — đây
  là thay đổi trên một tài liệu Approved khác, cần được duyệt riêng, không
  nằm trong story này.
- DDL/migration chi tiết, tên index, kiểu partition — thuộc tech spec của
  DEV, story chỉ mô tả hành vi phải đúng.

## Phụ thuộc / Ghi chú kiến trúc liên quan

- Tham chiếu chính: `docs/01-sa/architecture/digitization-results-store.md`
  mục 1, 2, 3, 5, 6, 7.
- Phụ thuộc các quyết định đã Approved: `digitization-pipeline.md` (Quyết
  định 1/2/3/5/6), `digitization-review.md` (Quyết định 1/2/3/4/6, hiện
  đang **Draft** — cần kiểm tra Status trước khi DEV dùng làm căn cứ),
  `ai-search-infra.md` (Quyết định 6), `search-semantic.md` (Quyết định 1/4).
- User Story 5 (SED) và phần "summary" của User Story 6 phụ thuộc trực tiếp
  vào việc bật feature flag tương ứng (`digitization-pipeline.md` Quyết định
  6) — chưa bật ở thời điểm viết story này.

## Câu hỏi mở cho DEV

- [ ] **File SA nguồn đang Draft, chưa Approved.** 4 điểm ở mục 7 của
      `digitization-results-store.md` (hợp nhất bảng job+run; SED/tóm tắt
      video có qua FR-18 hay không; đồng bộ enum `content_source`; ngưỡng
      thăng hạng `sound_event`) **chưa được người dùng chốt**. Story này đã
      viết theo phương án mặc định/an toàn mà SA tự đề xuất cho từng điểm
      (xem bảng "Giả định kế thừa từ SA" ở đầu file) để không chặn tiến độ,
      nhưng **cần người dùng duyệt lại cả file SA lẫn các giả định này**
      trước khi viết tech spec — nếu người dùng chọn phương án khác (ví dụ
      giữ 2 bảng job/run riêng, hoặc bắt SED/summary qua FR-18), một số AC
      ở User Story 1 và User Story 6 cần viết lại.
- [ ] Ngưỡng confidence cụ thể để "thăng hạng" `sound_event` →
      `content_segment` (User Story 5, AC-4) chưa có con số — cần chốt cùng
      lúc bật feature flag SED.
- [ ] Cơ chế đóng vai trò "sự kiện xác nhận" cho SED/tóm tắt video khi không
      qua FR-18 (`sed.promoted`/`summary.generated` hay tên khác — User
      Story 6 AC-3) chưa được định nghĩa, phụ thuộc câu trả lời cho mục 7
      điểm 2.
- [ ] Việc đồng bộ enum `content_source` ở `ai-search-infra.md` mục 8 (bỏ
      `native_text`, thêm `sed`/`summary`) là một thay đổi trên tài liệu
      Approved khác — cần người dùng duyệt riêng trước khi DEV dùng trường
      chỉ mục mới trong tech spec.
- [ ] Ràng buộc `time_end_ms <= duration_ms` (User Story 3, AC-5) là kiểm tra
      chéo bảng — DEV cần quyết định làm bằng trigger hay validate ở tầng
      ứng dụng khi viết tech spec.
- [ ] Cách khởi tạo dòng `document_digitization_status` cho tài liệu mới
      (lazy khi có run đầu tiên, hay tạo sẵn khi document được tạo — User
      Story 4 AC-3) cần DEV quyết định và ghi rõ trong tech spec.
