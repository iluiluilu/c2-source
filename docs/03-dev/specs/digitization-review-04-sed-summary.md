# Tech Spec: digitization-review-04-sed-summary

> File: `docs/03-dev/specs/digitization-review-04-sed-summary.md` — Owner: DEV
> Nguồn story: `docs/02-ba/stories/digitization-review-04-sed-summary.md`
> Status: Approved

> **Phụ thuộc — đọc trước file này:**
> `docs/03-dev/specs/digitization-review-01-ocr.md` (**bắt buộc** — toàn bộ
> vòng đời duyệt cấp run, `ReviewController`, `DigitizationRunLifecycleService.
> reviseSegment`/`deleteSegment`/`confirmRun`, `SegmentView`/`RunDetail`, đều
> định nghĩa ở đó; file này chỉ mô tả phần bổ sung cho
> `task ∈ {sed, video_summary}`) và `digitization-review-02-stt.md` (layout
> "danh sách + player đồng bộ" tái dùng nguyên vẹn cho SED). Data model tham
> chiếu `docs/03-dev/specs/digitization-results-store.md` §2.2 (`V26`,
> `content_segment`/`segment_revision`/`sound_event`) — bảng đã `Approved`,
> **không sửa** ở spec này.

## 1. Tóm tắt kỹ thuật

Đây thuần tuý là **mở rộng** luồng review đã có (file -01/-02) sang
`task='sed'` và `task='video_summary'`, không phải luồng mới. Không có
migration mới, không có `ReviewController` mới.

**Tái dùng nguyên vẹn (không đổi code):**

- `ReviewController.confirmRun` (`POST /runs/{id}/confirm`) — máy trạng thái
  `pending → in_review → confirmed` áp dụng y hệt (đã set `review_state=
  'pending'` khi `createRun` cho cả 4 task, `digitization-results-store.md`
  §4 bước 1).
- `DigitizationRunLifecycleService.reviseSegment`/`deleteSegment` — cơ chế
  `segment_revision` append-only + optimistic lock `current_revision_no`
  (409) dùng cho cả sửa nhãn SED và sửa văn bản tóm tắt.
- `ReviewController.getRunDetail` (`GET /runs/{id}`) + `markFirstViewed` —
  không đổi hành vi lõi, chỉ mở rộng dữ liệu trả về (mục 3).
- FE: component "danh sách segment + player đồng bộ" của Story 3
  (`-02-stt.md` mục 4 bước 4, `audioRef.currentTime`) — dùng lại cho SED,
  chỉ đổi cột nội dung + ẩn control sửa mốc thời gian.
- Quyền chính chủ 403 (`document.uploaderSub == currentUser.sub`), permission
  `digitization_review.manage` — không đổi.

**Phát hiện quan trọng khi khảo sát schema (giảm việc phải làm):** theo
`digitization-results-store.md` §4 bước 6, worker **chỉ** INSERT
`content_segment(content_source='sed')` cho detection đã **vượt ngưỡng**
thăng hạng — detection dưới ngưỡng chỉ tồn tại ở `sound_event`, không bao giờ
có dòng `content_segment` tương ứng. Vì vậy AC-1 Story 9 ("danh sách chỉ hiển
thị đoạn đã thăng hạng") **tự động đúng** với đúng câu query hiện có
`SELECT ... FROM content_segment WHERE run_id=:runId ORDER BY segment_index`
(giống hệt OCR/STT) — **không cần filter thêm** ở tầng review, không cần join
`sound_event.promoted_segment_id` để lọc (chỉ cần join để lấy thêm nhãn gốc
tiếng Anh, mục 3.3).

**Phải mới:**

- Mở rộng `SegmentView` (thêm `soundEventLabel` cho `sed`, `sourceReferences`
  cho `summary`), `RunDetail` (thêm `staleWarning` cho `video_summary`).
- Validate mới ở tầng service: chặn sửa mốc thời gian cho `task=sed` (422),
  chặn thêm/xoá đoạn cho `task=video_summary` (409), chặn thêm đoạn cho
  `task=sed` (409, không có nghiệp vụ "thêm sự kiện âm thanh thủ công").
- Logic resolve "nguồn tham chiếu" + tính `staleWarning` (mục 2, 4).
- FE: màn tóm tắt (1 khối văn bản, cảnh báo mềm độ dài), danh sách nguồn tham
  chiếu dạng liên kết deep-link, badge "có thể đã lỗi thời".

## 2. Data model

**Không thêm bảng/cột/migration mới.** Dùng nguyên trạng `V26`
(`content_segment`, `segment_revision`, `sound_event`) — xem
`digitization-results-store.md` §2.2. Chỉ định nghĩa lại **cách đọc**:

### 2.1 Nhãn gốc tiếng Anh cho SED

`content_segment` không có cột lưu nhãn AudioSet gốc — nhãn này chỉ có ở
`sound_event.label`. Vì `UQ_sound_event_promoted_segment` đảm bảo quan hệ
1-1 giữa `sound_event.promoted_segment_id` và `content_segment.id`, truy vấn
ngược 1 `content_segment(content_source='sed')` → `sound_event` là join theo
khoá duy nhất, không cần index mới:

```sql
SELECT cs.*, se.label AS sound_event_label
FROM content_segment cs
JOIN sound_event se ON se.promoted_segment_id = cs.id
WHERE cs.run_id = :runId
ORDER BY cs.segment_index;
```

### 2.2 Nguồn tham chiếu cho tóm tắt (`source_segment_ids`)

`content_segment.source_segment_ids` là `JSONB` (mảng chuỗi UUID, không phải
FK — xem lý do ở `digitization-results-store.md` §1). Resolve bằng 1 query
`IN`/`= ANY` trên PK, không cần index mới (số phần tử mỗi tóm tắt thường
≤ vài chục segment nguồn):

```sql
SELECT src.id, src.segment_index, src.current_time_start_ms,
       src.run_id AS source_run_id, dr.review_state AS source_run_review_state,
       src.updated_at AS source_segment_updated_at
FROM content_segment src
JOIN digitization_run dr ON dr.id = src.run_id
WHERE src.id = ANY(:sourceSegmentIds); -- parse từ JSONB ở tầng service
```

Segment nguồn tham chiếu tới id không còn tồn tại (không nên xảy ra vì cơ chế
xoá luôn là mềm — `is_deleted`, không xoá vật lý) → bỏ qua phần tử đó khi
build response, không ném lỗi 500 (mục 7).

### 2.3 Mốc thời gian cho `staleWarning` (AC-5 Story 10)

**Quyết định DEV** — không dùng riêng `digitization_run.updated_at` của run
STT nguồn (không đủ chính xác: cột này chỉ chắc chắn được cập nhật khi bản
thân dòng `digitization_run` bị UPDATE trực tiếp — ví dụ chuyển
`review_state`/`confirmed_at` — nhưng lần sửa segment thứ 2+ sau khi run đã
`in_review` không nhất thiết đụng tới dòng `digitization_run`, theo đúng
`reviseSegment` mục 3.4 của file -01: chỉ đổi `review_state` ở **lần sửa đầu
tiên**). Thay vào đó tính:

```
staleTimestamp = GREATEST(
  MAX(content_segment.updated_at)   -- các segment cụ thể được tham chiếu
    FOR id IN source_segment_ids,   -- (bump mỗi lần reviseSegment/deleteSegment)
  MAX(digitization_run.updated_at)  -- run nguồn bị thay thế bởi run STT mới
    FOR run_id IN (distinct source run ids) WHERE review_state = 'superseded'
)
staleWarning = (staleTimestamp IS NOT NULL AND summaryRun.reviewState = 'confirmed'
                AND staleTimestamp > summaryRun.confirmedAt)
```

Lý do dùng `content_segment.updated_at` (không phải toàn bộ run): đo đúng
"segment nguồn được sửa lần gần nhất" theo đúng chữ AC-5 — tránh false positive
khi có đoạn *khác* trong cùng run STT bị sửa nhưng không nằm trong
`source_segment_ids` của bản tóm tắt này. Vế `review_state='superseded'`
bắt case "run STT nguồn bị **re-run** thay vì sửa tại chỗ" (Story 8
`-01-ocr.md`) — trường hợp `content_segment` gốc không đổi nhưng dữ liệu đã
lỗi thời vì có run mới hơn thay thế. **Giả định cần xác nhận khi implement:**
2 cột `updated_at` này thực sự được bump tự động (JPA `@UpdateTimestamp` hoặc
service set tường minh) mỗi lần UPDATE — nếu entity hiện tại không cấu hình
vậy, phải set thủ công trong `reviseSegment`/`deleteSegment`/`confirmRun`,
nếu không `staleWarning` sẽ có false negative (rủi ro, mục 7).

Không cần index mới: cả 2 vế đều là `MAX(...)` trên tập nhỏ hàng đã được lọc
theo PK/FK sẵn có (`content_segment.id`, `digitization_run.id`).

## 3. API contract

Base path `/digitization-review` (đã định nghĩa ở file -01). Không có
endpoint mới — chỉ mở rộng payload của endpoint đã có.

| Method | Endpoint | Thay đổi so với file -01/-02 |
|---|---|---|
| GET | `/digitization-review/queue` | **Tái dùng, không đổi code** — chỉ thêm 2 giá trị hợp lệ cho `task` (`sed`, `video_summary`) vào enum validate (nếu có whitelist tường minh) |
| GET | `/digitization-review/runs/{runId}` | **Tái dùng** — `RunDetail`/`SegmentView` **mở rộng field mới** (mục 3.1, 3.2) khi `task ∈ {sed, video_summary}` |
| PATCH | `/digitization-review/segments/{segmentId}` | **Tái dùng** cơ chế; **thêm validate**: `task='sed'` mà request có `timeStartMs`/`timeEndMs` → `422` "SED không hỗ trợ sửa mốc thời gian sự kiện" (AC-5 Story 9) |
| DELETE | `/digitization-review/segments/{segmentId}` | **Tái dùng** nguyên vẹn cho `task ∈ {ocr, stt, sed}` (AC-4 Story 9: đánh dấu nhãn sai = xoá mềm); **thêm validate**: `task='video_summary'` → `409` "Tóm tắt video không hỗ trợ xoá đoạn" (AC-2 Story 10) |
| POST | `/digitization-review/runs/{runId}/segments` | **Thêm validate**: `task ∈ {sed, video_summary}` → `409` "Không hỗ trợ thêm đoạn thủ công cho tác vụ này" (Story 9 không có AC thêm sự kiện thủ công; AC-2 Story 10 chặn tường minh) |
| POST | `/digitization-review/runs/{runId}/confirm` | **Tái dùng, không đổi** |

### 3.1 `RunDetail` — field mới

```json
{
  "staleWarning": false
}
```

`staleWarning` chỉ có ý nghĩa khi `task='video_summary'`; luôn `false` cho
`ocr`/`stt`/`sed`. Tính theo công thức mục 2.3, chỉ tính khi
`reviewState='confirmed'` (chưa `confirmed` thì chưa có mốc so sánh).

### 3.2 `SegmentView` — field mới

```json
{
  "soundEventLabel": "Dog bark",
  "sourceReferences": [
    {
      "sourceSegmentId": "uuid",
      "sourceRunId": "uuid",
      "segmentIndex": 3,
      "timeStartMs": 12000,
      "sourceRunReviewState": "confirmed"
    }
  ]
}
```

- `soundEventLabel`: nhãn AudioSet gốc tiếng Anh, **chỉ đọc**, non-null khi
  `content_source='sed'`, null cho các loại khác (AC-1 Story 9 — hiển thị song
  song `aiText`/`currentText` là nhãn tiếng Việt).
- `sourceReferences`: non-null (mảng, có thể rỗng) khi `content_source=
  'summary'`, null cho các loại khác; mỗi phần tử resolve từ
  `source_segment_ids` (mục 2.2). `sourceRunReviewState` có thể là bất kỳ giá
  trị nào kể cả `superseded` (AC-4 Story 10 — link vẫn mở đúng run lịch sử,
  không tự chuyển sang run mới).

## 4. Luồng xử lý chính

1. **Mở run SED** — `GET /runs/{runId}`: query segment không đổi (`content_
   segment WHERE run_id=:runId`, tự động chỉ có đoạn đã thăng hạng — mục 1);
   build `SegmentView` join thêm `sound_event` theo `promoted_segment_id` để
   lấy `soundEventLabel` + `confidence` (field `confidence` đã có sẵn ở
   `content_segment`, copy từ `sound_event` lúc thăng hạng).
2. **Sửa nhãn tiếng Việt** — `PATCH /segments/{id}` chỉ gửi `text`, không gửi
   `timeStartMs`/`timeEndMs` (FE ẩn control này cho `task=sed`, mục 6 FE);
   service validate tier 2 chặn 422 nếu FE lỗi vẫn gửi kèm mốc thời gian; gọi
   `reviseSegment` y hệt OCR (409 khi lệch `expectedRevisionNo`, chuyển
   `pending → in_review` ở lần sửa đầu).
3. **Đánh dấu nhãn sai** — `DELETE /segments/{id}?expectedRevisionNo=...` gọi
   thẳng `deleteSegment` (file -01 mục 3.4), không có logic riêng cho SED.
4. **Mở run tóm tắt** — `GET /runs/{runId}`: `segments` luôn có đúng 1 phần
   tử (`writeSegments` chỉ ghi 1 dòng cho `video_summary`, không phải ràng
   buộc DB — xem rủi ro mục 7); build `sourceReferences` bằng query mục 2.2;
   tính `staleWarning` bằng công thức mục 2.3.
5. **Deep-link nguồn tham chiếu → màn duyệt STT** — thuần FE, không có API
   riêng: mỗi phần tử `sourceReferences` render thành link
   `/review/runs/{sourceRunId}?highlightSegmentIndex={segmentIndex}`; màn STT
   (đã có, `-02-stt.md`) gọi lại đúng `GET /runs/{sourceRunId}` (hoạt động cả
   khi `reviewState='superseded'`, đã hỗ trợ sẵn từ cơ chế Story 8 "so sánh
   run cũ", file -01 mục 4 bước 7 — FE tự khoá form sửa nếu không phải
   `pending`/`in_review`), tự tính trang chứa `segmentIndex` (`page =
   floor(segmentIndex / segmentSize)`), cuộn tới đoạn, gọi `audioRef.
   currentTime = timeStartMs / 1000` — tái dùng nguyên hàm đã viết cho Story
   3 STT (`-02-stt.md` mục 4 bước 4), không viết hàm mới.
   **Chỉnh 2026-09-23 (khi implement):**
   - Route thật là `/digitization/runs/{sourceRunId}?highlightSegmentIndex=…`
     (không có `/review/runs`).
   - Không cần tính trang chứa `segmentIndex`: FE đã tải hết segment của run
     (`-05-frontend.md` §4.2.1) → tìm theo `segmentIndex` trong
     `allSegments` (bỏ qua nếu đoạn đã xoá/không tồn tại, không báo lỗi),
     chọn đoạn → danh sách tự cuộn (`-05` §4.3.1).
   - Tua player: `SegmentSeekRequest.play=false` (chỉ đặt `currentTime`,
     không `.play()` — trình duyệt chặn tự phát ở tab mới); nếu thẻ media
     chưa sẵn sàng (blob còn đang tải) thì giữ yêu cầu tua chờ, áp dụng khi
     thẻ media mount.
   - Màn xem đã xác nhận (`-07`): link trỏ
     `/documents/{documentId}/digitization/{sourceRunId}?highlightSegmentIndex=…`
     (run nguồn luôn thuộc cùng document — `digitization-results-store.md`
     §7) khi `sourceRunReviewState='confirmed'`; trạng thái khác → hiện chữ
     không bấm được (endpoint `/runs/{id}/view` chỉ trả run `confirmed`).

## 5. Ngưỡng cảnh báo mềm độ dài văn bản tóm tắt

**Chốt: 4000 ký tự** (nằm giữa khoảng BA giao 3000–5000). Lý do:

- STT dùng ngưỡng 500 ký tự cho **1 câu/đoạn ngắn** (`-02-stt.md` mục 4 bước
  5); tóm tắt là **1 đoạn văn dài cho cả video**, tương đương vài đoạn văn
  (≈ 600-700 từ tiếng Việt) — đủ cho một bản tóm tắt chi tiết mà vẫn cảnh báo
  nếu AI/người sửa viết quá dài, khó đọc lướt.
- Chọn giữa khoảng thay vì biên trên/dưới để có dư địa 2 chiều: đủ thấp để
  cảnh báo sớm tránh tóm tắt lan man, đủ cao để không làm phiền các tài liệu
  dài (hội thảo, phỏng vấn) cần tóm tắt chi tiết hơn mức tối thiểu.
- **Cảnh báo mềm, chỉ ở FE**, giống cách STT xử lý ngưỡng 500 ký tự — không
  thêm validate backend, không chặn lưu (đúng AC-1/AC-2 Story 10: sửa luôn được
  lưu qua `reviseSegment`, không có điều kiện độ dài).

## 6. Test plan

**Unit test (mở rộng `ReviewServiceTest` đã có ở file -01/-02):**

- Build `SegmentView` cho `content_source='sed'` → `soundEventLabel` đúng
  giá trị `sound_event.label` join qua `promoted_segment_id`; segment không
  thăng hạng (chỉ tồn tại ở `sound_event`, không có `content_segment`) không
  xuất hiện trong danh sách trả về (verify query không lộ dữ liệu chưa promote).
- PATCH `task='sed'` kèm `timeStartMs` → 422, `reviseSegment` **không** được
  gọi (verify mock).
- POST thêm đoạn với `task='sed'`/`task='video_summary'` → 409, không tạo
  `content_segment` mới.
- DELETE với `task='video_summary'` → 409; DELETE với `task='sed'` → vẫn gọi
  `deleteSegment` bình thường (không bị chặn nhầm).
- `staleWarning`: `summaryRun.reviewState != 'confirmed'` → luôn `false`
  (không tính công thức); `staleTimestamp` bằng đúng `confirmedAt` (biên) →
  `false`; `staleTimestamp = confirmedAt + 1ms` → `true`; source run
  `superseded` nhưng segment tham chiếu không bị sửa → vẫn `true` (nhánh
  `GREATEST` thứ 2); cả 2 vế đều cũ hơn `confirmedAt` → `false`.
- Resolve `sourceReferences` với 1 id trong `source_segment_ids` không tìm
  thấy trong DB → bỏ qua phần tử đó, không ném exception.

**Integration test (Testcontainers Postgres, migration đến `V28`):**

- Seed run `task=sed` với N `sound_event` (M < N đã thăng hạng) → `GET
  /runs/{id}` trả đúng M `SegmentView`, mỗi cái có `soundEventLabel` khớp
  `sound_event.label` tương ứng; không có tham chiếu nào tới N-M detection
  chưa thăng hạng.
- 2 request PATCH đồng thời cùng `segmentId` (sed, sửa nhãn tiếng Việt) cùng
  `expectedRevisionNo` → đúng 1 request 200, request còn lại 409 (tái dùng
  test pattern optimistic lock của file -01).
- User B (không phải `uploader_sub`) gọi bất kỳ endpoint nào ở mục 3 cho
  `task ∈ {sed, video_summary}` → 403 (tái dùng test pattern file -01, thêm
  2 giá trị task).
- Seed run `task=video_summary` đã `confirmed`, `source_segment_ids` trỏ tới
  2 segment thuộc 1 run STT đã bị `superseded` sau đó → `GET /runs/{id}`
  trả `staleWarning=true`, `sourceReferences[].sourceRunReviewState=
  'superseded'`; gọi tiếp `GET /runs/{sourceRunId}` (run superseded đó) →
  vẫn `200`, trả đúng dữ liệu lịch sử (không tự động resolve sang run mới).
- POST `/runs/{id}/segments` và DELETE `/segments/{id}` trên run `task=
  video_summary` → cả 2 trả `409`, số dòng `content_segment` của run không
  đổi (vẫn đúng 1).

## 7. Task breakdown

**Backend:**

- [x] Task 1: Cho phép giá trị `task=sed|video_summary` ở validate query
      `GET /queue` (nếu có whitelist tường minh trong controller/DTO).
- [x] Task 2: Mở rộng builder `SegmentView` — join `sound_event` theo
      `promoted_segment_id` để lấy `soundEventLabel` (mục 3.2).
- [x] Task 3: Repository resolve `sourceReferences` từ `source_segment_ids`
      (mục 2.2) + service tính `staleWarning` (mục 2.3).
- [x] Task 4: Validate 422 chặn sửa mốc thời gian khi `task=sed` trong PATCH
      `/segments/{id}`.
- [x] Task 5: Validate 409 chặn POST thêm đoạn khi `task ∈ {sed,
      video_summary}`.
- [x] Task 6: Validate 409 chặn DELETE đoạn khi `task=video_summary`.
- [x] Task 7: Xử lý an toàn khi resolve `sourceReferences` gặp id không tồn
      tại (bỏ qua, không throw).

**Frontend:**

> Chưa implement (2026-09-08) — `web/` có sẵn khung Next.js app chung (routes
> khác: `documents`, `personal`, `admin`, ...) nhưng **chưa có** bất kỳ
> route/component nào cho `digitization-review` (không có Story 1-8 ở file
> -01/-02 để tái dùng "component danh sách + player đồng bộ" theo đúng mục
> 1/mục 6 spec này). Tự dựng route + component cho cả 4 story (1-10) trong
> spec cuối cùng của nhóm sẽ đi trước và không nhất quán với FE thật của các
> story trước — cần một spec FE riêng cho khung `digitization-review`
> (routing, state, danh sách+player dùng chung) bắt đầu từ Story 1-3, không
> phải tự ý thiết kế ở đây. Backend (Task 1-7) + test (Task 13-14) đã xong và
> không phụ thuộc quyết định này.

- [ ] Task 8: Màn SED — tái dùng component danh sách + player (Story 3
      STT), đổi cột nội dung (nhãn thay đoạn văn bản dài), ẩn control sửa
      mốc thời gian, hiển thị `soundEventLabel` (chỉ đọc) cạnh nhãn tiếng
      Việt sửa được.
- [ ] Task 9: Màn tóm tắt — 1 khối văn bản (textarea) sửa toàn bộ, cảnh báo
      mềm khi vượt 4000 ký tự (không chặn lưu).
- [x] Task 10: Danh sách "nguồn tham chiếu" chỉ đọc, mỗi phần tử là link
      deep-link (`highlightSegmentIndex`) mở màn STT của `sourceRunId`, tự
      cuộn + tua player tới đúng đoạn (kể cả run `superseded`).
- [ ] Task 11: Badge cảnh báo "có thể đã lỗi thời" khi `staleWarning=true`
      trên màn duyệt tóm tắt.
- [ ] Task 12: Đảm bảo nhãn "nội dung do AI sinh" hiển thị nhất quán cho cả
      `sed`/`summary` ở mọi `reviewState` (rà lại component dùng chung từ
      OCR/STT, không cần thay đổi nếu đã tổng quát theo `origin='ai'`).

**Test:**

- [x] Task 13: Viết unit test theo mục 6.
- [x] Task 14: Viết integration test theo mục 6.

## 8. Rủi ro kỹ thuật

- **AC-1 Story 9 phụ thuộc bất biến "chỉ promoted mới có `content_segment`"**
  của `digitization-results-store.md` §4 bước 6 — nếu pipeline SED sau này
  đổi cách ghi (ví dụ ghi cả detection dưới ngưỡng kèm cờ riêng để phục vụ
  audit UI), phải thêm filter tường minh `content_source='sed' AND ...` ở
  đúng chỗ hiện đang không cần filter (mục 1); cần theo dõi nếu spec pipeline
  đó thay đổi sau khi file này Approved.
- **`staleWarning` phụ thuộc `updated_at` được bump đúng** trên cả
  `content_segment` và `digitization_run` — nếu entity JPA hiện tại không tự
  động cập nhật cột này (không có `@UpdateTimestamp` hoặc set thủ công), phải
  bổ sung khi implement `reviseSegment`/`deleteSegment`/`confirmRun`, nếu sót
  sẽ dẫn tới false negative (không cảnh báo dù dữ liệu nguồn đã đổi) — cần
  xác minh code thật của 2 entity trước khi implement Task 3.
- **Không có ràng buộc DB nào chặn 1 run `video_summary` có >1
  `content_segment`** (chỉ là quy ước ở tầng `writeSegments`, không phải
  CHECK/UNIQUE constraint) — Task 5/6 (chặn thêm/xoá) giảm rủi ro drift ở
  đường API duyệt, nhưng nếu có đường ghi khác (job sửa lỗi thủ công, bug
  pipeline) vẫn có thể tạo ra 2 segment; nếu cần chặn cứng ở DB, cần một
  quyết định SA riêng (unique index có điều kiện) — ngoài phạm vi spec này.
- **Ngưỡng 4000 ký tự (mục 5) là quyết định DEV**, không phải số nghiệp vụ đã
  chốt — có thể cần điều chỉnh theo phản hồi người dùng thật; chỉ là hằng số
  FE, không ảnh hưởng schema/API.
- **Ngưỡng thăng hạng SED và thời điểm bật feature flag** (câu hỏi mở BA còn
  để ngỏ, `digitization-results-store.md` §7 điểm 4) chưa chốt — spec này giả
  định flag đã bật, đúng quy ước các slug khác đang áp dụng; không ảnh hưởng
  thiết kế API/data model ở đây.
