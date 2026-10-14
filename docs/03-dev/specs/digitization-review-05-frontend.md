# Tech Spec: digitization-review-05-frontend

> File: `docs/03-dev/specs/digitization-review-05-frontend.md` — Owner: DEV
> Nguồn story: `docs/02-ba/stories/digitization-review-05-frontend.md`
> Status: Approved

## 1. Tóm tắt kỹ thuật

Phần lớn là **frontend thuần** (`web/`), tái dùng 100% quy ước hiện có:
Next.js App Router, Client Component (`'use client'`), Tailwind CSS thuần
(không thư viện UI), `apiJson`/`apiFetch` (`@/lib/api`), `AppShell`/
`AdminTabs`, `useSession()`/`hasRole()`, `useConfirm()`. Không thêm thư viện
JS mới (không React Query/Zustand/component-lib — giữ đúng pattern
`documents/[id]/page.tsx`: `useState` + `useCallback` load thủ công).

**Một phần backend mới** (Task 1): endpoint render ảnh từng trang tài liệu
gốc cho Story 2 (BA, câu hỏi mở #1) — chưa tồn tại, cần thêm. Dùng Apache
PDFBox 3.0.8 (Apache-2.0, pure-Java, không phụ thuộc native) để rasterize
trang PDF; nguồn ảnh đơn (PNG/JPG) thì trả thẳng không qua PDFBox.

## 2. Data model

Không có migration mới. Không đọc/ghi bảng nào ngoài các bảng đã có sẵn từ
`digitization-results-store.md`. Endpoint ảnh trang (mục 3) chỉ đọc
`document.storage_key`/`mime_type` qua `ObjectStorageService` đã có.

## 3. API contract

### 3.1 Endpoint MỚI

| Method | Endpoint | Request | Response |
|---|---|---|---|
| GET | `/digitization-review/runs/{runId}/pages/{pageNo}/image` | — | `200 image/png` (binary) hoặc `404` (trang không tồn tại) hoặc `403` (không phải chính chủ) |

- Quyền: dùng lại `requireOwnedReviewableRun(runId, user)` đã có trong
  `ReviewController` — không cấp quyền mới, không tạo permission mới.
- Nguồn `document.mime_type`:
  - `application/pdf`: PDFBox `PDFRenderer.renderImageWithDPI(pageNo - 1,
    150, ImageType.RGB)` (0-indexed nội bộ PDFBox, `pageNo` từ URL là
    1-indexed, khớp quy ước `content_segment.page_no` đã có) → encode PNG.
    150 DPI: đủ nét để đọc chữ scan thường, dung lượng ảnh hợp lý (một
    trang A4 ở 150 DPI ~ 1275×1650px, PNG nén ~ 200-500KB tuỳ nội dung).
  - `image/png`/`image/jpeg`/... (nguồn ảnh đơn): `pageNo` phải là `1`
    (khác → `404`); trả thẳng byte gốc qua `ObjectStorageService.getObject`,
    không qua PDFBox.
  - Định dạng khác (`mimeType` không phải PDF/ảnh) → `404` (task OCR chỉ
    sinh ra từ nguồn TEXT_DOC/IMAGE theo `allowed_file_format`, không cần
    xử lý case khác).
- Header: `Cache-Control: private, max-age=3600` — ảnh trang không đổi theo
  thời gian (chỉ đoạn text/bbox có thể đổi, không phải ảnh gốc), trình
  duyệt cache lại khi đổi trang qua lại hoặc load lại danh sách đoạn.
- **Không cache phía server** (Redis hay đĩa) ở bản đầu — render lại mỗi
  request. Nếu về sau đo được đây là điểm nghẽn hiệu năng (nhiều người mở
  cùng lúc một tài liệu lớn), thêm cache là việc tối ưu riêng, không chặn
  bản đầu.

### 3.2 Endpoint ĐÃ CÓ (frontend gọi lại, không đổi backend)

| Method | Endpoint | Dùng ở màn hình |
|---|---|---|
| GET | `/digitization-review/queue` | Story 1 — hàng chờ |
| GET | `/digitization-review/runs/{runId}` | Story 2-6 — chi tiết run |
| PATCH | `/digitization-review/segments/{segmentId}` | Story 2/3/4/5 — sửa đoạn |
| DELETE | `/digitization-review/segments/{segmentId}` | Story 2/3/4 — xoá đoạn |
| POST | `/digitization-review/runs/{runId}/segments` | Story 2/3 — thêm thủ công |
| POST | `/digitization-review/runs/{runId}/confirm` | Story 6 — xác nhận |
| GET | `/documents/{id}/content` | Story 3/4/5 — file gốc cho player audio/video |
| GET | `/admin/document-ownership` | Story 7 — tìm theo `uploaderSub` |
| POST | `/admin/document-ownership/{documentId}/transfer` | Story 7 — chuyển |
| GET | `/accounts` | Story 7 — chọn người nhận |

Chi tiết field/shape của các response trên: xem `digitization-review-01-ocr.md`
§3, `-02-stt.md` §3, `-04-sed-summary.md` §3, `-03-ownership-transfer.md` §3
— không lặp lại ở đây.

## 4. Luồng xử lý chính

### 4.1 Cấu trúc file/route (mới)

```
web/src/app/digitization/page.tsx                    # Story 1: hàng chờ
web/src/app/digitization/runs/[runId]/page.tsx        # Story 2-6: chi tiết run (điều phối theo task)
web/src/app/admin/document-ownership/page.tsx         # Story 7

web/src/components/digitization/OcrPageViewer.tsx     # Story 2: ảnh + bbox overlay
web/src/components/digitization/SegmentPlayer.tsx     # Story 3/4 (STT/SED): player dùng chung + đồng bộ
web/src/components/digitization/SegmentList.tsx       # danh sách đoạn dùng chung, khác nhau qua props theo task

web/src/lib/api.ts                                    # thêm type: ReviewRunSummary, ReviewRunDetail,
                                                        # ReviewSegment, DocumentOwnershipRow, ...
web/src/lib/features.ts                                # bật enabled: true cho feature 'digitization'
web/src/components/AdminTabs.tsx                        # thêm tab 'document-ownership'
```

`runs/[runId]/page.tsx` đọc `task` từ response `GET .../runs/{runId}` rồi
chọn nhánh hiển thị:
- `task==='ocr'` → `<OcrPageViewer>` + `<SegmentList variant="ocr">`.
- `task∈{'stt','sed'}` → `<SegmentPlayer>` + `<SegmentList variant={task}>`.
- `task==='video_summary'` → **không có** `<SegmentPlayer>` — chỉ
  `<SegmentList variant="video_summary">` (mỗi dòng hiện `sourceReferences`
  dạng link `<a target="_blank">`, không có mốc thời gian/player riêng —
  xem lý do ở BA Story 5 AC-1: `timeStartMs`/`timeEndMs` luôn `null` cho
  `content_source='summary'`, chốt 2026-09-08 sau khi rà soát lại schema
  `V26`, sửa từ thiết kế nháp ban đầu có nhầm gộp `video_summary` vào nhóm
  dùng player chung).

`SegmentList` nhận `variant` để quyết định hiện/ẩn: ô mốc thời gian (chỉ
`stt`), link nguồn (chỉ `video_summary`), nút xoá (ẩn khi... không loại nào
ẩn, xoá luôn cho phép trừ theo AC riêng), nút thêm thủ công (ẩn cho `sed`/
`video_summary`, theo Story 4 AC-3/Story 5 AC-4).

### 4.2 Story 2 — Ảnh + bbox overlay

- `OcrPageViewer` nhận `runId`, `pageNo` hiện tại, danh sách segment của
  trang đó (đã lọc theo `pageNo` từ `segments` trả về ở `GET runs/{id}`).
- `<img src="/api/proxy..." />` **không** trỏ thẳng API backend (ảnh cần
  header `Authorization: Bearer` mà thẻ `<img>` không gửi được) — tải qua
  `apiFetch` lấy `Blob`, tạo `URL.createObjectURL(blob)`, gán vào `<img
  src>` (đúng pattern `download()` đã có ở `documents/page.tsx`). Nhớ
  `URL.revokeObjectURL` khi đổi trang/unmount để tránh rò rỉ bộ nhớ.
- Overlay bbox: `<div>` tuyệt đối định vị theo `%` (không phải px) —
  `left: ${x*100}%`, `top: ${y*100}%`... tính từ 4 điểm `bboxPolygon` (dùng
  bounding box của polygon, không cần vẽ polygon lồi phức tạp cho MVP: lấy
  min/max x,y trong mảng điểm, vẽ hình chữ nhật) — vì toạ độ đã là tỉ lệ
  0-1 (chốt sẵn ở `V26`), không cần biết kích thước ảnh thật/DPI để quy đổi.
- Click 2 chiều (BA AC-2): `selectedSegmentId` state ở `runs/[runId]/page.tsx`
  (cha chung của viewer + list), truyền xuống cả hai qua props — không cần
  context/store riêng, state cục bộ đủ dùng ở quy mô 1 trang này.
- Đổi trang: chỉ đổi `pageNo` state → `OcrPageViewer` tự fetch lại ảnh
  (qua `useEffect` phụ thuộc `pageNo`) — **không** gọi lại `GET runs/{id}`,
  chỉ lọc lại danh sách đoạn (đã tải đầy đủ, xem 4.2.1) theo `pageNo` ở
  phía client.

#### 4.2.1 Tải đầy đủ danh sách đoạn (vượt giới hạn phân trang API)

**Chốt 2026-09-08 (phát hiện khi review):** `GET runs/{id}` phân trang
`segments` qua `segmentPage`/`segmentSize` (tối đa `100`/lần, xem `V26`/
`ReviewController`) — một tài liệu OCR nhiều trang hoàn toàn có thể vượt
quá 100 segment (ví dụ 20 trang scan × 10-15 đoạn/trang). Thiết kế "lọc
theo `pageNo` ở client" ở trên **chỉ đúng nếu đã có toàn bộ segment của
run**, không phải chỉ trang API đầu tiên. `runs/[runId]/page.tsx` khi load
phải **gọi lặp** `segmentPage=0,1,2...` cho tới khi `content.length <
size` hoặc `page >= totalPages - 1` (hết dữ liệu), gộp toàn bộ `content`
vào 1 state `allSegments` trước khi hiển thị bất kỳ thứ gì phụ thuộc vào
phân trang vật lý (`pageNo` của OCR) hay đếm tổng số đoạn. Với tài liệu
thường (≤100 đoạn) chỉ tốn đúng 1 lần gọi như thiết kế ban đầu; tài liệu
lớn hơn tốn thêm vài lần gọi tuần tự — chấp nhận được, không cần thiết kế
lại thành fetch theo yêu cầu (on-demand) vì tăng độ phức tạp không tương
xứng với lợi ích ở quy mô dữ liệu hiện tại.

### 4.3 Story 3/4 — Player đồng bộ (KHÔNG áp dụng cho `video_summary`, xem 4.1)

- `SegmentPlayer` bọc 1 thẻ `<video>` hoặc `<audio>` (chọn theo
  `document.mimeType` bắt đầu bằng `video/` hay `audio/` — lấy qua
  `digitization_source`/`document`, cần trả kèm trong `RunDetail` hoặc gọi
  thêm `GET /documents/{documentId}`; ưu tiên cách sau, tái dùng endpoint
  đã có, không đổi `RunDetail`).
- Nguồn phát: `src` là URL tạo từ `Blob` giống Story 2 (không phát thẳng từ
  API vì cần bearer token) — tải 1 lần khi mở trang, giữ trong state, không
  tải lại khi chuyển đoạn.
- Seek theo đoạn: `videoRef.current.currentTime = timeStartMs / 1000`, sau
  đó `.play()`; lắng nghe sự kiện `timeupdate` để: (a) tự `.pause()` khi
  `currentTime >= timeEndMs/1000` của đoạn đang chọn (BA Story 3 AC-2), (b)
  tìm đoạn có `timeStartMs <= currentTime*1000 < timeEndMs` để đồng bộ
  highlight danh sách khi đang phát tự do, không phải do click (AC-3).
- `sed` dùng cùng component `SegmentPlayer` — chỉ khác `SegmentList
  variant` ẩn ô nhập mốc thời gian (chỉ đọc `timeStartMs`/`timeEndMs` để
  seek, không cho sửa — backend trả `422` nếu cố gửi).

### 4.3.1 Bố cục cuộn trong vùng (bổ sung 2026-09-23, Story 2 AC-1/AC-2, Story 3 AC-1/AC-3)

Áp dụng cho cả `/digitization/runs/[runId]` và trang xem đã xác nhận
`/documents/[id]/digitization/[runId]` (spec `-07`), task `ocr`/`stt`/`sed`;
`video_summary` giữ nguyên (không có ảnh/player cần giữ trong tầm nhìn).

- Chỉ từ `lg:` trở lên; màn hẹp (1 cột) giữ cuộn cả trang như cũ.
- Khung body: `lg:h-[calc(100vh-72px-3rem)]` (header AppShell 72px +
  padding `p-6` của `<main>`) — tiêu đề trang cuộn đi một đoạn ngắn, sau
  đó khung vừa khít cửa sổ; không cần đo chiều cao bằng JS.
- `ocr`: grid 2 cột trong khung trên, mỗi cột `min-h-0 overflow-y-auto`
  (cột ảnh cũng cuộn riêng khi ảnh trang dọc cao hơn khung).
- `stt`/`sed`: khung `flex flex-col`; `SegmentPlayer` `shrink-0` (video
  giới hạn `lg:max-h-[40vh]` thay cho `max-h-96`), `SegmentList`
  `flex-1 min-h-0 overflow-y-auto`.
- Nút "+ Thêm đoạn thủ công" nằm ngoài vùng cuộn (luôn nhìn thấy).
- Tự cuộn: `SegmentList` `useEffect([selectedSegmentId])` →
  `document.getElementById('segment-row-'+id)?.scrollIntoView?.({ block:
  'nearest' })` — phủ cả click bbox (Story 2 AC-2, trước đây chưa làm) và
  đoạn đang phát (Story 3 AC-3, `onActiveSegmentChange` đã set
  `selectedSegmentId`). Không cuộn khi đang ở chế độ sửa đoạn khác
  (`editingId !== null`) để không làm nhảy form đang nhập.

### 4.4 Story 7 — Chuyển quyền sở hữu

Component đơn giản hơn, theo đúng pattern `admin/rbac/page.tsx`/`admin/
accounts` hiện có: form filter + bảng + dialog. Không cần state phức tạp.

## 5. Test plan

**Backend (endpoint mới, Task 1):**

- Unit: PDF 1 trang/nhiều trang → render đúng trang yêu cầu (so khớp kích
  thước ảnh trả về khớp DPI 150 tính toán từ kích thước trang PDF); `pageNo`
  vượt quá số trang → `404`; nguồn `image/png` với `pageNo=1` → trả thẳng
  byte gốc; nguồn `image/png` với `pageNo=2` → `404`.
- Integration: người không phải chính chủ gọi endpoint → `403` (dùng lại
  `requireOwnedReviewableRun`, không cần viết test AC mới — chỉ xác nhận
  endpoint mới có áp dụng đúng helper).

**Frontend:**

- Test hiện tại của `web/` dùng Vitest + Testing Library (`personal/
  __tests__`, `upload/__tests__` là ví dụ) — viết test tương tự cho: bbox
  overlay tính đúng vị trí `%` từ `bboxPolygon` mẫu; player tự dừng đúng
  `timeEndMs`; `SegmentList` ẩn đúng nút theo từng `variant`; vòng lặp tải
  `segmentPage` dừng đúng khi hết dữ liệu và gộp đúng tổng số đoạn (mock
  2-3 trang API, xem 4.2.1); tab "Tất cả" ở Story 1 không gửi `task` trong
  query string.
- Không có E2E/Playwright trong repo hiện tại — không thêm ở spec này (theo
  đúng phạm vi, tránh nhập thêm công cụ test mới ngoài kế hoạch).

## 6. Task breakdown

- [x] Task 1 (Backend): endpoint `GET .../runs/{runId}/pages/{pageNo}/image`
      trong `ReviewController` — thêm dependency `pdfbox:3.0.8` vào
      `backend/pom.xml`, PDFBox rasterize PDF / trả thẳng ảnh đơn (mục 3.1).
- [x] Task 2 (Frontend): bật `enabled: true` cho feature `digitization`
      (`features.ts`); thêm các type mới vào `api.ts` (`ReviewRunSummary`,
      `ReviewRunDetail`, `ReviewSegment`, `DocumentOwnershipRow`, ...).
- [x] Task 3 (Frontend): `/digitization` — Story 1 (hàng chờ + filter +
      phân trang).
- [x] Task 4 (Frontend): `/digitization/runs/[runId]` khung chung (header,
      nút xác nhận Story 6, điều phối theo `task`).
- [x] Task 5 (Frontend): `OcrPageViewer` + tích hợp — Story 2.
- [x] Task 6 (Frontend): `SegmentPlayer` + tích hợp cho `stt`/`sed` — Story
      3/4 (KHÔNG dùng cho `video_summary` — xem 4.1/4.3).
- [x] Task 7 (Frontend): `SegmentList` dùng chung (sửa/xoá/thêm thủ công,
      link nguồn cho `video_summary`, theo `variant`) — Story 2-5.
- [x] Task 8 (Frontend): `/admin/document-ownership` + thêm tab
      `AdminTabs` — Story 7.
- [x] Task 9: Viết test theo mục 5. Backend: `ReviewControllerPageImageTest`
      (unit) + 3 case mới trong `ReviewControllerIT` (integration, không
      chạy được trong sandbox này — lý do đã ghi ở Javadoc class đó).
      Frontend: 5 file test mới dưới `__tests__/` (bbox %, player auto-pause/
      highlight, SegmentList theo variant, vòng lặp `segmentPage`, tab "Tất
      cả" không gửi `task`) — 15/15 pass.
- [x] Task 10 (Frontend, bổ sung 2026-09-23): bố cục cuộn trong vùng theo
      mục 4.3.1 cho 2 trang run (duyệt + xem đã xác nhận) + tự cuộn tới đoạn
      được chọn/đang phát trong `SegmentList`. Test: đổi `selectedSegmentId`
      → gọi `scrollIntoView` trên đúng dòng (jsdom không có hàm này — stub
      `Element.prototype.scrollIntoView = vi.fn()` trong test); đang sửa
      một đoạn → không gọi.

> **Lệch so với spec, đã ghi nhận khi implement (2026-09-08):** mục 4.1/4.3
> tham chiếu `-04-sed-summary.md` §4 bước 5 cho "Thêm/xoá đoạn thủ công"
> Story 3 AC-7 ("có thể nhập mốc thời gian khi thêm thủ công") — nhưng
> `AddSegmentRequest`/`DigitizationRunLifecycleService.addManualSegment`
> (đã implement, không đổi ở spec này) chỉ nhận `afterSegmentIndex`+`text`,
> không có `timeStartMs`/`timeEndMs`. Đã KHÔNG tự ý mở rộng backend contract
> — `SegmentList`'s "Thêm đoạn thủ công" chỉ có ô nhập text (khớp đúng
> `AddSegmentRequest` hiện có); người dùng vẫn nhập được mốc thời gian ngay
> sau đó qua thao tác "Sửa" (PATCH, gửi cả `timeStartMs`+`timeEndMs` cùng
> lúc để tránh nhánh 422 "không xác định được mốc thời gian hiện tại").
> Nếu cần đúng UX 1-bước như AC-7 viết, cần một tech-spec riêng mở rộng
> `AddSegmentRequest`/`addManualSegment` — ngoài phạm vi sửa ở đây.

## 7. Rủi ro kỹ thuật

- **PDFBox là dependency mới** (chưa dùng ở đâu khác trong repo) — Apache
  2.0, pure-Java, không cần cài thêm gì ở OS/Docker image (khác các thư
  viện native như `pdftoppm`/Ghostscript) — rủi ro thấp, nhưng cần nhớ thêm
  vào image Docker backend nếu build multi-stage có bước "chỉ copy jar cần
  thiết" (kiểm tra `Dockerfile` khi implement, không giả định trước).
- **Hiệu năng render PDF trang lớn/nhiều trang** — 150 DPI cho 1 trang đơn
  lẻ theo yêu cầu thường nhanh (<200ms trên phần cứng thường), nhưng PDF
  quét ảnh độ phân giải rất cao (ví dụ 600 DPI gốc) có thể chậm hơn — chưa
  đo thật vì Epic A (worker OCR) chưa có dữ liệu thật để benchmark; AC-1.5s
  p95 của Story 2 (BA gốc `-01`) tính cho cả trang (ảnh + segment), không
  riêng bước render — nếu benchmark thật sau này vượt ngưỡng, thêm cache
  server-side (mục 3.1) là hướng xử lý, không phải thiết kế lại.
- **`bboxPolygon` là quy ước đã chốt (`V26`, 2026-09-07) nhưng chưa có dữ
  liệu thật** (Epic A worker OCR chưa xây) — overlay implement đúng theo
  quy ước tài liệu, nhưng chỉ kiểm chứng được bằng dữ liệu giả lập
  (fixture) cho tới khi có dữ liệu OCR thật; rủi ro nhỏ nếu worker thực tế
  (khi xây) vô tình không tuân đúng quy ước tỉ lệ 0-1 — không phải rủi ro
  của spec này, nhưng đáng nhắc khi làm Epic A.
- **Không có E2E test** cho luồng ảnh+bbox+player (phụ thuộc trình duyệt
  thật để test tương tác `<video>`/`<img>` chính xác) — chấp nhận rủi ro
  này ở MVP, đúng theo mức test hiện có của `web/` (chỉ unit/component
  test), không mở rộng phạm vi công cụ test ở spec này.
