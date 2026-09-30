> Status: Draft

# Định vị chính xác kết quả — trang/dòng (văn bản), mốc thời gian (âm thanh/video)

## Mục tiêu nghiệp vụ

Sau khi tìm kiếm hoặc tra soát nội dung đã số hóa, người dùng cần đi thẳng
tới đúng vị trí chứa kết quả trong tài liệu gốc thay vì phải tự dò lại toàn
bộ trang/tệp. Với văn bản (nguồn OCR), hệ thống định vị theo số trang, dòng
và khung bao (bbox) trên ảnh trang. Với âm thanh/video (nguồn STT/SED), hệ
thống định vị theo mốc thời gian bắt đầu/kết thúc để nhảy thẳng và tự phát
đúng đoạn. Thông tin vị trí được lưu cố định trên từng `ContentSegment` khi
số hóa, và được tái sử dụng ở tầng tìm kiếm để trả về cho giao diện dưới
dạng một cấu trúc `PositionInfo`/`SearchPosition` thống nhất, để frontend chỉ
cần rẽ nhánh theo `type` (`page` / `time` / `none`) mà không cần biết
`content_source` gốc là gì.

## Tác nhân

- Người dùng cuối đã đăng nhập, có quyền `PERM_document.read`, đang xem kết
  quả tìm kiếm (`/search`) trên trang web.
- Hệ thống số hóa (digitization pipeline) — nguồn ghi field vị trí vào
  `ContentSegment` khi tạo/soát đoạn (đã có sẵn, không thuộc phạm vi tài liệu
  này).
- Hệ thống tìm kiếm (search) — nguồn đọc field vị trí để dựng `PositionInfo`
  trả về cho client (đã có sẵn, không thuộc phạm vi tài liệu này).

## Phạm vi

**Trong phạm vi:**
- Các field lưu vị trí trên `ContentSegment` (page/line/bbox cho văn bản;
  time cho âm thanh/video) và ý nghĩa/đơn vị của chúng.
- Cách `HighlightMapper` (tầng search) ánh xạ các field đó sang
  `PositionInfo.type = "page" | "time" | "none"`.
- Cách frontend dùng `position.type` để quyết định hành động "nhảy tới kết
  quả": mở ảnh trang có khung bbox (`SearchPageViewer`) hoặc mở trình phát
  media rồi seek tới `timeStartMs` (`SegmentPlayer`).

**Ngoài phạm vi:**
- Thuật toán tìm kiếm/xếp hạng, truy vấn OpenSearch (tính năng tìm kiếm).
- Quy trình OCR/STT/SED sinh ra text và tọa độ/mốc thời gian ban đầu (tính
  năng OCR/STT) — tài liệu này chỉ mô tả field kết quả của các quy trình đó
  được dùng lại như thế nào.
- Luồng review/sửa đoạn (`digitization/review`), trừ phần liên quan trực
  tiếp tới việc field vị trí "hiện hành" (`current_*`) được set như thế nào.

## Luồng nghiệp vụ chính

1. Khi số hóa/soát nội dung, mỗi `ContentSegment` được ghi kèm vị trí gốc
   trong tài liệu: với đoạn văn bản (`content_source = "ocr"`) là số trang
   (`page_no`), khoảng dòng (`line_from`/`line_to`) và đa giác bao
   (`bbox_polygon`, tọa độ tỉ lệ 0–1 trên trang, không phải pixel); với đoạn
   âm thanh/video (`content_source = "stt"`/`"sed"`) là mốc thời gian bắt
   đầu/kết thúc theo mili-giây (`time_start_ms`/`time_end_ms`, tách bản
   `ai_*` bất biến và bản `current_*` cập nhật theo revision).
2. **Quy tắc:** `current_time_start_ms`/`current_time_end_ms` luôn được set
   bằng `ai_time_start_ms`/`ai_time_end_ms` ngay khi ghi kết quả AI, nên trên
   lý thuyết không bao giờ null cho một đoạn STT/SED hợp lệ; các field
   page/line/bbox không có bản `ai_*`/`current_*` song song (chỉ một cột duy
   nhất trên `ContentSegment`).
3. Khi lập chỉ mục cho tìm kiếm, các field vị trí thô (`page_no`,
   `line_from`, `line_to`, `time_start_ms`, `time_end_ms`, `position_type`)
   được đưa vào tài liệu `SegmentDocument` trên OpenSearch để phục vụ đọc
   nhanh; `bbox_polygon` không được đưa vào chỉ mục — chỉ Postgres
   `content_segment` mới là nguồn xác thực cho bbox.
4. Khi trả kết quả tìm kiếm, với mỗi segment khớp, `HighlightMapper.toPosition`
   đọc bản ghi `ContentSegment` tương ứng từ Postgres (không phải từ
   OpenSearch) và dựng một `PositionInfo`:
   - `content_source = "ocr"` → `type = "page"`, kèm `pageNo`, `lineFrom`,
     `lineTo`, `bboxPolygon`, và offset ký tự của đoạn khớp
     (`highlightOffsetStart`/`highlightOffsetEnd`, tính từ vị trí thẻ
     `<em>` do OpenSearch highlighter trả về, đối chiếu lại với
     `current_text`).
   - `content_source = "stt"` hoặc `"sed"` → `type = "time"`, kèm
     `timeStartMs`/`timeEndMs` (ưu tiên `current_time_*`, dự phòng
     `ai_time_*` nếu `current_time_*` null).
   - Các `content_source` khác (ví dụ `"summary"`, hoặc không tìm thấy
     `ContentSegment` khớp — ví dụ pseudo-segment `note`/`title`) → `type =
     "none"`, mọi field vị trí đều null.
5. Frontend nhận `SearchResultSegment.position` (kiểu `SearchPosition`, mirror
   1-1 của `PositionInfo`) cho từng đoạn khớp trong kết quả tìm kiếm, và
   `SearchResultCard` hiển thị nút hành động khác nhau theo `position.type`:
   - `"page"` → nút "Xem trên ảnh trang {pageNo}", gọi `onViewPage`.
   - `"time"` → nút "Nghe/Xem đoạn này", gọi `onPlaySegment`.
   - `"none"` → nếu có `sourceReferences` (chỉ với `content_source =
     "summary"`) thì hiển thị liên kết sang các đoạn nguồn
     (`/digitization/runs/{sourceRunId}`); nếu không thì chỉ hiển thị nhãn
     tĩnh "Chỉ định vị được ở cấp tài liệu".
6. Click "Xem trên ảnh trang": trang `/search` lưu `{documentId, pageNo,
   bboxPolygon}` vào state, mở `SearchPageViewer` trong modal. Component này
   gọi `GET /documents/{id}/pages/{pageNo}/image` (endpoint scope chung,
   khác endpoint review-only) để tải ảnh trang dạng Blob, sau đó vẽ khung đỏ
   đè lên ảnh tại vị trí `boundingBoxPercent(bboxPolygon)` (chuyển tọa độ tỉ
   lệ 0–1 sang phần trăm `left/top/width/height` của ảnh).
7. Click "Nghe/Xem đoạn này": trang `/search` dựng một `ReviewSegment` tối
   giản (`toReviewSegmentStub`) chỉ mang `segmentId`/`timeStartMs`/
   `timeEndMs` thật, mở `SegmentPlayer` trong modal kèm `seekRequest =
   {segmentId, nonce}` (nonce tăng mỗi lần click để ép seek lại kể cả khi
   click cùng một đoạn). `SegmentPlayer` tải media gốc qua `GET
   /documents/{id}/content` dạng Blob, khi `seekRequest` đổi thì set
   `media.currentTime = timeStartMs / 1000` và tự `play()`; khi phát tới
   `timeEndMs` thì tự dừng (auto-pause một lần, không lặp lại nếu người dùng
   chủ động bấm play tiếp).

## Sequence diagram

```mermaid
sequenceDiagram
    participant User as Người dùng
    participant SearchResultCard
    participant SearchPage as SearchPage (app/search/page.tsx)
    participant SearchPageViewer
    participant SegmentPlayer
    participant DocumentsController
    participant PageImageRenderer

    User->>SearchResultCard: click "Xem trên ảnh trang {pageNo}"
    SearchResultCard->>SearchPage: onViewPage(result, segment)
    SearchPage->>SearchPage: setPageViewer({documentId, pageNo, bboxPolygon})
    SearchPage->>SearchPageViewer: render(documentId, pageNo, bboxPolygon)
    SearchPageViewer->>DocumentsController: GET /documents/{id}/pages/{pageNo}/image
    DocumentsController->>PageImageRenderer: render(document, pageNo)
    PageImageRenderer-->>DocumentsController: PageImage (bytes + contentType)
    DocumentsController-->>SearchPageViewer: 200 image bytes
    SearchPageViewer->>SearchPageViewer: boundingBoxPercent(bboxPolygon)
    SearchPageViewer-->>User: hiển thị ảnh trang + khung đỏ tại bbox

    User->>SearchResultCard: click "Nghe/Xem đoạn này"
    SearchResultCard->>SearchPage: onPlaySegment(result, segment)
    SearchPage->>SearchPage: toReviewSegmentStub(segment); setSegmentPlayer(...); setSeekNonce(n+1)
    SearchPage->>SegmentPlayer: render(segments=[stub], seekRequest={segmentId, nonce})
    SegmentPlayer->>DocumentsController: GET /documents/{id}/content (Blob, load-once)
    DocumentsController-->>SegmentPlayer: 200 media bytes
    SegmentPlayer->>SegmentPlayer: media.currentTime = timeStartMs/1000; media.play()
    SegmentPlayer-->>User: phát từ timeStartMs, tự dừng tại timeEndMs
```

## Luồng ngoại lệ / trường hợp đặc biệt

- **`position.type = "none"` không có `sourceReferences`:** đoạn khớp
  (thường là `note`/`title` cấp tài liệu) không có vị trí chi tiết nào — UI
  chỉ hiển thị nhãn tĩnh "Chỉ định vị được ở cấp tài liệu", không có hành
  động nhảy tới.
- **`position.type = "none"` kèm `sourceReferences`:** riêng
  `content_source = "summary"`, không có vị trí trực tiếp nhưng có danh sách
  đoạn nguồn (`sourceSegmentIds` trỏ tới các `ContentSegment` OCR/STT đã
  dùng để tóm tắt); UI hiển thị các liên kết "Đoạn #N, mm:ss" mở sang trang
  review của run nguồn (`/digitization/runs/{sourceRunId}`) ở tab mới, không
  nhảy trực tiếp trong modal tìm kiếm.
- **Không tìm thấy fragment highlight khớp verbatim trong `current_text`:**
  `HighlightMapper.computeHighlightOffsets` trả về `null` một cách phòng
  thủ; `highlightOffsetStart/End` là `null` nhưng `pageNo`/`bboxPolygon` vẫn
  được trả bình thường — không chặn việc nhảy tới trang.
- **`current_time_start_ms` null bất thường:** `resolveTimeStart`/`
  resolveTimeEnd` dự phòng bằng `ai_time_start_ms`/`ai_time_end_ms`; nếu cả
  hai đều null thì `SegmentPlayer` không seek (điều kiện `segment.timeStartMs
  == null` chặn sớm trong `useEffect`, giữ nguyên vị trí phát hiện tại).
- **Lỗi tải ảnh trang / media:** `SearchPageViewer` và `SegmentPlayer` bắt
  lỗi fetch (`apiFetch`/`apiJson` ném lỗi khi response không OK, bao gồm
  403/404 nếu tài liệu ngoài phạm vi truy cập của người dùng —
  `DocumentScopeGuard.requireVisible` trả 404 khi ngoài scope, nhất quán với
  các endpoint đọc khác của `DocumentsController`) và hiển thị thông báo lỗi
  nội tuyến ("Không tải được ảnh trang: …") thay vì để modal trống.
- **Click lại cùng một đoạn thời gian:** `seekNonce` tăng mỗi lần click
  (kể cả cùng `segmentId`), nên `SegmentPlayer` luôn seek lại từ đầu đoạn dù
  người dùng đã tua đi nơi khác trong lúc modal đang mở.

## Dữ liệu liên quan

| Entity/bảng DB | Vai trò | File code |
|---|---|---|
| `ContentSegment` (bảng `content_segment`) | Nguồn xác thực (source of truth) cho vị trí: `pageNo`, `lineFrom`/`lineTo`, `bboxPolygon` (văn bản); `currentTimeStartMs`/`currentTimeEndMs`, `aiTimeStartMs`/`aiTimeEndMs` (âm thanh/video) | `backend/src/main/java/com/aismartstore/backend/digitization/results/ContentSegment.java` |
| `SegmentRevision` (bảng `segment_revision`) | Lịch sử sửa đoạn, mang `timeStartMs`/`timeEndMs` của từng revision (append-only); không phải nguồn đọc trực tiếp cho `PositionInfo` khi tìm kiếm | `backend/src/main/java/com/aismartstore/backend/digitization/results/SegmentRevision.java` |
| `SegmentDocument` (tài liệu OpenSearch index `segment`) | Bản sao vị trí thô (`page_no`, `line_from`, `line_to`, `time_start_ms`, `time_end_ms`, `position_type`) phục vụ tìm kiếm; không mang `bbox_polygon` | `backend/src/main/java/com/aismartstore/backend/search/SegmentDocument.java` |
| `PositionInfo` (DTO, không phải bảng) | Cấu trúc vị trí thống nhất trả về qua API tìm kiếm, dựng lại từ `ContentSegment` (không phải từ `SegmentDocument`) | `backend/src/main/java/com/aismartstore/backend/search/dto/PositionInfo.java` |
| `SegmentHit` (DTO) | Một đoạn khớp trong kết quả tìm kiếm, bọc `PositionInfo` + `sourceReferences` | `backend/src/main/java/com/aismartstore/backend/search/dto/SegmentHit.java` |
| `SearchPosition`/`SearchResultSegment` (TypeScript interface, frontend) | Mirror 1-1 của `PositionInfo`/`SegmentHit` phía client | `web/src/lib/api.ts` |

## File code tham chiếu

- `backend/src/main/java/com/aismartstore/backend/digitization/results/ContentSegment.java` dòng 54–75 (`pageNo`, `bboxPolygon`, `lineFrom`/`lineTo`, `timeStartMs`/`timeEndMs`), dòng 92–105 (`aiTimeStartMs`/`aiTimeEndMs`, `currentTimeStartMs`/`currentTimeEndMs`).
- `backend/src/main/java/com/aismartstore/backend/digitization/results/SegmentRevision.java` dòng 36–40 (`timeStartMs`/`timeEndMs` của một revision).
- `backend/src/main/java/com/aismartstore/backend/search/dto/PositionInfo.java` toàn bộ (record + hằng số `TYPE_PAGE`/`TYPE_TIME`/`TYPE_NONE`).
- `backend/src/main/java/com/aismartstore/backend/search/dto/SegmentHit.java` toàn bộ.
- `backend/src/main/java/com/aismartstore/backend/search/HighlightMapper.java` dòng 127–177 (`toPosition`, `resolveTimeStart`/`resolveTimeEnd`), dòng 193–212 (`computeHighlightOffsets`).
- `backend/src/main/java/com/aismartstore/backend/search/SegmentDocument.java` dòng 35–51 (field vị trí trong index).
- `backend/src/main/java/com/aismartstore/backend/documents/DocumentsController.java` dòng 145–156 (`GET /{id}/pages/{pageNo}/image`).
- `backend/src/main/java/com/aismartstore/backend/search/SearchController.java` dòng 40–44 (`POST /search`).
- `web/src/lib/api.ts` dòng 759–784 (`SearchPosition`, `SearchResultSegment`).
- `web/src/components/search/SearchResultCard.tsx` dòng 62–99 (rẽ nhánh theo `position.type` để chọn hành động).
- `web/src/components/search/SearchPageViewer.tsx` toàn bộ (tải ảnh trang + vẽ bbox).
- `web/src/components/digitization/OcrPageViewer.tsx` dòng 12–30 (`boundingBoxPercent`, hàm dùng chung để đổi tọa độ tỉ lệ sang phần trăm).
- `web/src/components/digitization/SegmentPlayer.tsx` dòng 74–105 (`seekRequest` → `media.currentTime`, auto-pause tại `timeEndMs`, đồng bộ đoạn đang phát).
- `web/src/app/search/page.tsx` dòng 57–83 (`toReviewSegmentStub`), dòng 283–337 (state `pageViewer`/`segmentPlayer`, gắn `onViewPage`/`onPlaySegment`, render modal).

## Điểm cần xác nhận thêm

- Với văn bản (OCR), không có field "trạng thái đã sửa vị trí" tách biệt —
  nếu người review sửa `bboxPolygon`/`pageNo`/`lineFrom`/`lineTo` của một
  đoạn (nếu tính năng review cho phép việc đó — tài liệu này không xác nhận),
  thì có một bản `ai_*` dự phòng cho các field này hay không, khác với time
  (có `ai_time_*` tách biệt `current_time_*`). Cần xác nhận với đội
  digitization-results-store nếu cần audit "vị trí gốc AI" cho văn bản.
- Tài liệu không xác nhận hành vi khi `bboxPolygon` hợp lệ nhưng
  `pageNo` bị null (ví dụ dữ liệu cũ/không nhất quán) — theo code hiện tại,
  `onViewPage` dùng `segment.position.pageNo ?? 1` (mặc định về trang 1) khi
  giá trị null, có thể gây lệch trang hiển thị so với đoạn thực tế; đây là
  hành vi đã có trong code, không phải giả định thêm của tài liệu này.
