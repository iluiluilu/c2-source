# Tech Spec: search-fulltext-05-frontend

> File: `docs/03-dev/specs/search-fulltext-05-frontend.md` — Owner: DEV
> Nguồn story: `docs/02-ba/stories/search-fulltext-05-frontend.md`
> Status: Approved

**Đã kiểm tra trước khi viết:** `POST /search` đã implement đầy đủ và ổn
định (`backend/src/main/java/com/aismartstore/backend/search/**`,
`SearchController`, `SearchServiceImpl`, DTO ở `search/dto/*.java`). Spec
này giữ nguyên phần lớn hình dạng request/response đó, **trừ 2 field bổ
sung thêm vào response** (`SearchHit.documentDigitizationStatus`,
`SegmentHit.sourceReferences`) theo xác nhận của người dùng ngày
2026-09-09 khi review BA story — xem mục 2/3, đây là thay đổi cộng thêm
(additive, không phá vỡ tương thích ngược với client nào đang dùng
`POST /search`, hiện chưa có client nào ngoài spec này). `web/src/lib/features.ts`
đã có sẵn mục `key: 'search'` (`href: '/search'`, `roles: [CAN_BO, LANH_DAO]`,
`enabled: false`). `web/src/components/digitization/{OcrPageViewer,SegmentPlayer}.tsx`
đã tồn tại và đã test (`digitization-review-05-frontend.md`) — dùng lại
thay vì viết mới, nhưng **không tái dùng trực tiếp được** endpoint ảnh
trang mà `OcrPageViewer` đang gọi (xem mục 1, phát hiện quan trọng).

## 1. Tóm tắt kỹ thuật

- 1 trang mới `web/src/app/search/page.tsx` (bật `enabled: true` ở
  `features.ts`, không đổi `href`/`roles`): ô từ khoá + panel bộ lọc nâng
  cao + danh sách kết quả dạng card theo tài liệu + overlay xem vị trí
  (ảnh trang có bbox, hoặc player audio/video).
- **Phát hiện quan trọng khi đối chiếu code (không có trong BA gốc):**
  `OcrPageViewer` hiện gọi
  `GET /digitization-review/runs/{runId}/pages/{pageNo}/image`
  (`ReviewController.getPageImage`), endpoint này gate bằng
  `requireOwnedReviewableRun` — **chỉ chính chủ nạp tài liệu** mới xem được
  (so `document.uploaderSub` với người gọi). Kết quả tìm kiếm có thể là tài
  liệu người khác nạp nhưng đã chia sẻ nhóm (`BusinessDataScope`) — gọi
  thẳng endpoint đó cho trường hợp này sẽ nhận 403 sai. **Cần một endpoint
  mới**, phạm vi chung: `GET /documents/{id}/pages/{pageNo}/image`, dùng
  đúng `DocumentScopeGuard.requireVisible` (cùng cơ chế `GET
  /documents/{id}/content` đang dùng cho phần audio/video của
  `SegmentPlayer` — component đó *đã* dùng phạm vi chung nên tái dùng thẳng
  được, không cần sửa).
- Logic PDFBox render trang (`renderPdfPage`/`PageImage`/`readObject`) hiện
  nằm private trong `ReviewController` — tách thành 1 bean dùng chung
  (`PageImageRenderer`, package `com.aismartstore.backend.documents`) để cả
  `ReviewController` và `DocumentsController` gọi, tránh copy code.
- **2 field mới trên response `POST /search`** (đã xác nhận với người dùng
  2026-09-09, xem mục 2):
  - `SearchHit.documentDigitizationStatus` — tín hiệu chính xác cho BA Story
    4 AC-1 ("nội dung đang chờ kiểm duyệt"), thay cho suy luận sai từ
    `contentSource` của các đoạn khớp. Nguồn: bảng
    `document_digitization_status` đã có sẵn (`digitization-results-store.md`).
  - `SegmentHit.sourceReferences` — cho BA Story 3 AC-4 (liên kết từ 1 đoạn
    tóm tắt video tới đúng đoạn STT/OCR gốc), chỉ khác `null`/rỗng khi
    `contentSource = "summary"`. Nguồn: `content_segment.source_segment_ids`
    + `ReviewSegmentRepository.resolveSourceReferences` đã có sẵn
    (`digitization-review-04-sed-summary.md`).
- Không có bảng/migration Postgres mới. 2 field trên chỉ đọc dữ liệu đã có
  sẵn qua repository đã có sẵn — không thêm bảng, không thêm write path.

## 2. Data model

Không có bảng/migration Postgres mới. Tham chiếu nguyên trạng các DTO thật
đã tồn tại (không lặp lại nội dung, chỉ dẫn đường dẫn), cộng 2 field mới:

- `backend/.../search/dto/SearchRequest.java` — `query`, `filters`, `sort`,
  `page`, `size`.
- `backend/.../search/dto/SearchFilters.java` — `docType`, `topicTags`,
  `topicTagsMode`, `uploadedFrom`, `uploadedTo`, `groupIds`, `folderId`,
  `mediaType`, `confirmSource`.
- `backend/.../search/dto/SearchResponse.java` — `results`, `totalApprox`,
  `page`, `size`, `degradedMode`, `refineSuggestions`.
- `backend/.../search/dto/SearchHit.java` — **sửa thêm 1 field**:
  `documentDigitizationStatus` (`String`, nullable) — record hiện tại
  `(UUID documentId, String documentName, String docType, double score,
  List<SegmentHit> segments)` → thêm `String documentDigitizationStatus`
  vào cuối. Giá trị:
  - `null` khi tài liệu **chưa từng có** dòng nào trong
    `document_digitization_status` (chưa có run nào được tạo — ví dụ tài
    liệu loại không qua số hoá, hoặc vừa nạp xong, dispatch chưa kịp tạo
    run). **Không** hiển thị thông điệp "đang chờ kiểm duyệt" cho trường
    hợp này (BA Story 4 AC-1) — không có bằng chứng nó đang thực sự chờ số
    hoá.
  - Nguyên văn `overall_status` (`queued`/`processing`/`failed`/
    `pending_review`/`in_review`/`confirmed`/`not_applicable`, xem
    `DocumentDigitizationStatus` javadoc) khi có dòng.
- `backend/.../search/dto/SegmentHit.java` — **sửa thêm 1 field**:
  `sourceReferences` (`List<SourceReferenceView>`, nullable/rỗng) — record
  hiện tại `(String segmentId, String contentSource, String confirmSource,
  String highlight, PositionInfo position)` → thêm
  `List<SourceReferenceView> sourceReferences` vào cuối. Tái dùng thẳng
  `com.aismartstore.backend.digitization.review.SourceReferenceView`
  (`sourceSegmentId`, `sourceRunId`, `segmentIndex`, `timeStartMs`,
  `sourceRunReviewState`) — **không** định nghĩa DTO mới trùng lặp. Chỉ
  khác `null`/rỗng khi `contentSource = "summary"` **và**
  `content_segment.source_segment_ids` giải quyết được ít nhất 1 tham
  chiếu hợp lệ; mọi `contentSource` khác luôn `null`.
- `backend/.../search/dto/PositionInfo.java`, `RefineSuggestion.java` —
  không đổi.

Frontend thêm 1 file type mới `web/src/lib/api.ts` (bổ sung vào file hiện
có, không tạo file riêng — đúng convention hiện tại của `ReviewSegment` v.v.):
`SearchRequestBody`, `SearchResult` (= `SearchHit`, gồm cả
`documentDigitizationStatus`), `SearchResultSegment` (= `SegmentHit`, gồm
cả `sourceReferences`), `SearchPosition` (= `PositionInfo`),
`RefineSuggestion` — `SearchResultSegment.sourceReferences` dùng lại type
`ReviewSourceReference` đã có sẵn trong `api.ts` (từ
`digitization-review-05-frontend.md`), không định nghĩa type trùng. Đặt
tên field khớp JSON (`camelCase`, Jackson mặc định, giống toàn bộ `api.ts`
hiện có).

## 3. API contract

| Method | Endpoint | Ghi chú |
|---|---|---|
| POST | `/search` | Đã có — **sửa thêm 2 field response** (mục 2), không đổi request |
| GET | `/tags?kind=doc_type` | Đã có — nguồn dữ liệu dropdown "Loại tài liệu" |
| GET | `/tags?kind=topic` | Đã có — nguồn dữ liệu multi-select "Nhãn chủ đề" |
| GET | `/groups` | Đã có — nguồn dữ liệu multi-select "Nhóm" |
| GET | `/folders/tree` | Đã có — nguồn dữ liệu cây thư mục |
| GET | `/documents/{id}/content` | Đã có (`DocumentsController`) — audio/video, `SegmentPlayer` dùng thẳng |
| GET | `/documents/{id}/pages/{pageNo}/image` | **MỚI** — xem 3.1 |

### 3.1 Endpoint mới: `GET /documents/{id}/pages/{pageNo}/image`

- Thêm vào `DocumentsController` (không phải `ReviewController`).
  `@PreAuthorize("hasAuthority('PERM_document.read')")` — permission đã
  dùng cho mọi endpoint đọc khác của controller này, **không** thêm
  permission key mới.
- Luồng xử lý (mirror `ReviewController.getPageImage`, khác đúng 1 điểm —
  cách lấy `Document`):
  1. `Document document = documentScopeGuard.requireVisible(user, id)` —
     404 nếu không tồn tại **hoặc** ngoài phạm vi (không phân biệt, đúng
     convention hiện có của `DocumentScopeGuard`, khác `ReviewController`
     vốn trả 403 — **giữ nguyên 404** ở endpoint mới này để nhất quán với
     mọi endpoint khác của `DocumentsController`, không bắt chước 403 của
     `ReviewController`).
  2. Còn lại **giống hệt** `ReviewController.getPageImage`: PDF →
     `PageImageRenderer.renderPdfPage` (150 DPI, PNG); ảnh đơn (`image/*`)
     → trả thẳng nếu `pageNo == 1`; mime khác → 404 "Not a page-renderable
     mime type".
  3. Response: `Cache-Control: private, max-age=3600` — giống nguyên bản.
- `pageNo` 1-indexed trên wire, khớp `content_segment.page_no` — không đổi
  quy ước.

### 3.2 Request `POST /search` (build từ state UI)

```json
{
  "query": "hợp đồng thuê nhà",
  "filters": {
    "docType": "hop_dong",
    "topicTags": ["tài chính"],
    "topicTagsMode": "AND",
    "uploadedFrom": "2026-01-01",
    "uploadedTo": "2026-09-01",
    "groupIds": ["..."],
    "folderId": "...",
    "mediaType": ["document"],
    "confirmSource": "manual"
  },
  "sort": "relevance",
  "page": 0,
  "size": 20
}
```

`filters` gửi `null`/lược bỏ hoàn toàn khi không chọn gì (không gửi object
rỗng lẫn field rỗng lộn xộn) — khớp `SearchFilters.isEmpty()` phía backend.

### 3.3 Response `POST /search` — 2 field mới (đã xác nhận với người dùng 2026-09-09)

```json
{
  "results": [
    {
      "documentId": "b6f1...",
      "documentName": "Hop_dong_thue_nha_2024.pdf",
      "docType": "hop_dong",
      "score": 12.34,
      "documentDigitizationStatus": "confirmed",
      "segments": [
        {
          "segmentId": "...", "contentSource": "ocr", "confirmSource": "manual",
          "highlight": "...<em>thuê nhà</em>...",
          "position": { "type": "page", "pageNo": 3, "lineFrom": 10, "lineTo": 12,
            "bboxPolygon": [[0.12,0.30],[0.62,0.30],[0.62,0.35],[0.12,0.35]] },
          "sourceReferences": null
        }
      ]
    },
    {
      "documentId": "c7a2...",
      "documentName": "Phong-van-ghi-am.mp4",
      "docType": null,
      "score": 8.1,
      "documentDigitizationStatus": "confirmed",
      "segments": [
        {
          "segmentId": "...", "contentSource": "summary", "confirmSource": "manual",
          "highlight": "Chủ đề chính là dự án <em>số hoá</em>...",
          "position": { "type": "none" },
          "sourceReferences": [
            { "sourceSegmentId": "...", "sourceRunId": "7e00f104-...", "segmentIndex": 1,
              "timeStartMs": 2000, "sourceRunReviewState": "confirmed" }
          ]
        }
      ]
    }
  ],
  "totalApprox": 2,
  "page": 0,
  "size": 20,
  "degradedMode": "none",
  "refineSuggestions": []
}
```

## 4. Luồng xử lý chính

1. `app/search/page.tsx` (Client Component, `'use client'`, đúng convention
   dự án — không dùng state-management library):
   1. Mount: gọi song song `GET /tags?kind=doc_type`, `GET /tags?kind=topic`,
      `GET /groups`, `GET /folders/tree` (đổ dữ liệu panel lọc — pattern
      giống hệt `documents/page.tsx`).
   2. Submit (Enter hoặc bấm "Tìm kiếm"): validate client-side (BA AC-4
      Story 2: độ dài/số token/số giá trị mỗi filter) trước khi gọi
      `POST /search`; validate "cả 2 đều rỗng" (BA AC-8 Story 1) trước khi
      gọi.
   3. Render `results[]` thành card; mỗi `segments[]` render highlight an
      toàn (mục 4 bước 2) + badge theo `position.type`/`confirmSource`
      (BA Story 3).
   4. `totalApprox === 0` → hiển thị `refineSuggestions[]` nếu có (nút bấm,
      mỗi nút set lại state filter bỏ đúng nhóm rồi tự gọi lại `POST /search`).
   5. `degradedMode !== "none"` → banner tương ứng (BA Story 1 AC-10).
   6. `documentDigitizationStatus` khác `"confirmed"` và khác `null` → dòng
      ghi chú "đang chờ kiểm duyệt" dưới tên tài liệu (BA Story 4 AC-1,
      **không** kiểm tra `contentSource` của segment nào nữa).
   7. Với mỗi `segments[]`: `sourceReferences` có ít nhất 1 phần tử → hiển
      thị danh sách link "Đoạn #{segmentIndex}, {mm:ss}" (BA Story 3 AC-4);
      ngược lại (`null`/rỗng) và `position.type === "none"` → badge "Chỉ
      định vị được ở cấp tài liệu" (BA Story 3 AC-3).
2. **Render highlight an toàn** (BA AC-3 Story 1 — chống stored-XSS, xem rủi
   ro mục 7): parse chuỗi `segments[].highlight` bằng regex/split tách theo
   `<em>`/`</em>` literal, escape toàn bộ phần còn lại (React tự escape khi
   render text node — **không** dùng `dangerouslySetInnerHTML` trên chuỗi
   gốc), bọc phần giữa 2 thẻ trong `<mark>`/`<strong>` React thật.
3. **Nhảy tới vị trí văn bản** (BA AC-1 Story 3): bấm "Xem trên ảnh trang" mở
   modal chứa `OcrPageViewer`, nhưng:
   - Đổi nguồn ảnh: `OcrPageViewer` hiện hard-code gọi qua `runId` tới
     endpoint review-only. Viết 1 biến thể/prop mới cho phép truyền thẳng
     URL nguồn ảnh (`GET /documents/{documentId}/pages/{pageNo}/image`,
     mục 3.1) thay vì `runId` — **hoặc** (lựa chọn tương đương, DEV chọn khi
     implement) một component nhỏ riêng `SearchPageViewer` chỉ vẽ ảnh + 1
     bbox (không cần toàn bộ tương tác 2 chiều với danh sách đoạn của
     `OcrPageViewer`, vốn dành cho màn hình sửa OCR) — ưu tiên phương án
     **không sửa** `OcrPageViewer`/`digitization-review` hiện có nếu đơn
     giản hơn.
   - Dùng lại `boundingBoxPercent()` (export sẵn từ `OcrPageViewer.tsx`) để
     vẽ khung từ `position.bboxPolygon`.
4. **Nhảy tới vị trí audio/video** (BA AC-2 Story 3): bấm "Nghe/Xem đoạn này"
   mở modal chứa `SegmentPlayer`, truyền `documentId` thật + 1 mảng
   `ReviewSegment` tối giản dựng từ `SegmentHit`/`PositionInfo` của **cùng
   tài liệu** đó (chỉ cần `segmentId`, `timeStartMs`, `timeEndMs` khớp đúng
   field `SegmentPlayer` dùng — các field khác của `ReviewSegment` không
   dùng tới trong component này, điền giá trị mặc định hợp lệ). `SegmentPlayer`
   đã gọi `GET /documents/{id}/content` (phạm vi chung) — **dùng thẳng,
   không cần sửa**.
5. Backend: `PageImageRenderer` (bean mới) nhận `Document` + `pageNo`, trả
   `PageImage(bytes, contentType)` — `ReviewController` và mới
   `DocumentsController` đều gọi qua bean này thay vì code riêng.
6. Backend — `SearchResultHydrator.hydrate(...)` (đã có, sửa thêm 2 bước,
   cùng chỗ đang batch-lấy `Document`/`content_segment` theo lô, **không**
   thêm round-trip N+1 mới):
   1. Sau khi có danh sách `documentId` đã qua double-check ACL: 1 lần gọi
      `documentDigitizationStatusRepository.findAllById(documentIds)`
      (phương thức có sẵn của `JpaRepository`, không cần viết query mới) →
      map `documentId → overallStatus`; thiếu trong map = `null` (mục 2).
   2. `HighlightMapper.map(...)` — thêm 1 nhánh `case
      ContentSegment.SOURCE_SUMMARY` (hiện đang rơi vào `default -> none()`):
      parse `segment.getSourceSegmentIds()` (`List<String>` JSONB, đã có
      sẵn, xem `ReviewController.parseSourceSegmentIds` để tham khảo cách
      parse/bỏ qua UUID hỏng) → gọi
      `reviewSegmentRepository.resolveSourceReferences(ids)` (đã có sẵn, 1
      lần cho **cả lô** `summary` segment trong trang kết quả, không gọi
      từng segment một) → map sang `SourceReferenceView`. **Vẫn trả
      `position = none()`** (không đổi `PositionInfo`, chỉ thêm
      `sourceReferences` cạnh nó trên `SegmentHit`) — đúng quyết định đã
      chốt với người dùng: badge/link là 2 khái niệm độc lập, không gộp vào
      `position.type`.
   3. Ghép `documentDigitizationStatus` vào từng `SearchHit` khi build
      response cuối (bước 8 cũ, không đổi số thứ tự các bước khác).

## 5. Test plan

- **Frontend unit test** (Vitest, đúng pattern các test hiện có ở
  `components/digitization/__tests__`):
  - Hàm parse/escape highlight: input có `<em>khớp</em>` render đúng phần
    tô đậm; input chứa `<script>`/thẻ HTML lạ trong phần văn bản thường
    (mô phỏng nội dung ghi chú độc hại) render ra **text thuần**, không bị
    thực thi/không lọt qua `dangerouslySetInnerHTML`.
  - Build `SearchRequest` từ state filter: filter rỗng hoàn toàn → không
    gửi field `filters` (hoặc gửi `null`, khớp `SearchFilters.isEmpty()`).
  - Validate client-side: từ khoá > 256 ký tự / > 32 token, > 20 giá trị 1
    filter → chặn trước khi gọi API, đúng thông báo.
  - Card kết quả: `documentDigitizationStatus = null` hoặc `"confirmed"` →
    **không** hiển thị dòng "đang chờ kiểm duyệt"; mọi giá trị khác → có
    hiển thị.
  - Segment `contentSource = "summary"` với `sourceReferences` có phần tử →
    render danh sách link, **không** render badge "chỉ định vị được ở cấp
    tài liệu"; `sourceReferences` rỗng/`null` → render badge đó (không có
    link).
- **Backend test** — `SearchServiceTest`/`SearchScopeIT` (đã có, bổ sung
  case mới, không tạo lớp test mới):
  - `SearchResultHydrator` trả đúng `documentDigitizationStatus`: tài liệu
    có dòng `document_digitization_status` với `overall_status="confirmed"`
    → field `"confirmed"`; tài liệu **không có dòng nào** → field `null`
    (không phải `"not_applicable"` hay lỗi).
  - `HighlightMapper`: segment `content_source="summary"` có
    `source_segment_ids` hợp lệ → `sourceReferences` đúng số phần tử, đúng
    `sourceRunId`/`segmentIndex`/`timeStartMs`; `source_segment_ids` rỗng
    hoặc toàn UUID không giải quyết được → `sourceReferences = null`/rỗng,
    **không** ném lỗi làm hỏng cả kết quả tìm kiếm.
- **Backend test** — `DocumentsControllerPageImageTest` (mirror
  `ReviewControllerPageImageTest` đã có, `digitization-review-05-frontend.md`):
  - User A xem được ảnh trang tài liệu do **chính A nạp**.
  - User A xem được ảnh trang tài liệu do **B nạp nhưng đã chia sẻ nhóm**
    mà A là thành viên (**case mới, không tồn tại ở `ReviewControllerPageImageTest`**
    — đây chính là lỗi endpoint cũ mà endpoint mới phải sửa).
  - User A **không** xem được ảnh trang tài liệu của B khi không chia sẻ
    nhóm nào — 404 (không phải 403, đúng `DocumentScopeGuard` convention).
  - `pageNo` vượt số trang PDF thật → 404.
  - Mime không phải PDF/`image/*` → 404.
- **E2E thủ công trên trình duyệt thật** (theo đúng cách đã làm ở
  `checklist-trien-khai-du-an.md` mục 8.14): seed 2 tài liệu (1 văn bản OCR
  có bbox, 1 audio/video có STT), 2 tài khoản R1 khác nhau chia sẻ nhóm
  chung — xác minh: tìm từ khoá ra đúng kết quả, click "Xem trên ảnh trang"
  mở đúng trang + khoanh đúng vùng dù tài liệu **không phải do người đang
  xem nạp**, click "Nghe/Xem đoạn này" tua đúng mốc và tự phát, banner
  timeout/degraded hiển thị đúng khi giả lập lỗi.

## 6. Task breakdown

- [x] Task 1: `PageImageRenderer` — tách `renderPdfPage`/`PageImage`/
      `readObject` từ `ReviewController` thành bean dùng chung
      (`com.aismartstore.backend.documents.PageImageRenderer`); sửa
      `ReviewController` gọi qua bean này (hành vi không đổi, chỉ refactor).
- [x] Task 2: `DocumentsController` — thêm `GET /documents/{id}/pages/{pageNo}/image`
      (mục 3.1), dùng `documentScopeGuard.requireVisible` + `PageImageRenderer`.
- [x] Task 3: `DocumentsControllerPageImageTest` (mục 5) — bao gồm case
      "chia sẻ nhóm" mà endpoint cũ không có.
- [x] Task 3b: **Sửa response `POST /search`** (đã xác nhận với người dùng
      2026-09-09, mục 2/3.3/4 bước 6) — thêm `SearchHit.documentDigitizationStatus`
      (hydrate qua `DocumentDigitizationStatusRepository.findAllById`, batch,
      không N+1) và `SegmentHit.sourceReferences` (thêm nhánh
      `SOURCE_SUMMARY` vào `HighlightMapper`, tái dùng
      `ReviewSegmentRepository.resolveSourceReferences`) + test tương ứng
      (mục 5). Làm **trước** Task 4/7/8 vì frontend phụ thuộc trực tiếp vào
      2 field này.
- [x] Task 4: `web/src/app/search/page.tsx` — khung trang, ô từ khoá, gọi
      `POST /search`, hiển thị danh sách card cơ bản (BA Story 1, trừ
      highlight an toàn — Task 5).
- [x] Task 5: Hàm/`component` render highlight an toàn (mục 4 bước 2) + unit
      test (mục 5) — làm **trước** khi ghép vào Task 4 để không có lúc nào
      hiển thị highlight chưa qua kiểm tra.
- [x] Task 6: Panel bộ lọc nâng cao (BA Story 2) — 7 nhóm filter, tooltip cú
      pháp rút gọn, validate client-side, khối gợi ý `refineSuggestions`.
- [x] Task 7: Overlay xem vị trí văn bản (bbox) + audio/video (BA Story 3) —
      quyết định cụ thể theo mục 4 bước 3/4 (sửa nhẹ `OcrPageViewer` hoặc
      component riêng; dùng thẳng `SegmentPlayer`).
- [x] Task 8: Badge/thông điệp trạng thái (BA Story 3 AC-3/AC-4/AC-5, Story 4
      AC-1) — "chỉ định vị được ở cấp tài liệu", danh sách link "Đoạn #N,
      mm:ss" cho tóm tắt video (mở `/digitization/runs/{sourceRunId}` tab
      mới), "tự động xác nhận", "đang chờ kiểm duyệt" (dựa
      `documentDigitizationStatus`, **không** dựa `contentSource`).
- [x] Task 9: Bật `enabled: true` cho mục `search` ở `web/src/lib/features.ts`.
- [x] Task 10: Toàn bộ test plan mục 5 (frontend unit + backend + xác minh
      thủ công trên trình duyệt thật, theo đúng cách đã làm ở phiên trước).

## 7. Rủi ro kỹ thuật

- **Phụ thuộc chéo package `search` → `digitization.review`** (Task 3b, tái
  dùng `ReviewSegmentRepository.resolveSourceReferences`): trước đây
  `search` chỉ phụ thuộc `digitization.results` (`ContentSegment`), chưa
  từng phụ thuộc `digitization.review`. Chấp nhận được (không phải ranh
  giới bảo mật, chỉ là 1 câu truy vấn đọc dữ liệu dùng chung) nhưng nếu DEV
  muốn giữ ranh giới module sạch hơn, có thể chuyển
  `resolveSourceReferences` xuống `digitization.results` (nơi
  `ContentSegment` đã ở) và cho `ReviewSegmentRepository` gọi lại — không
  bắt buộc, ghi nhận làm lựa chọn khi implement.
- **`documentDigitizationStatus`/`sourceReferences` thêm round-trip
  Postgres mới vào đường `POST /search`** (2 truy vấn theo lô, không phải
  N+1) — cần đo lại NFR p95 ≤ 1s (mục 3 `search-fulltext.md`) sau khi thêm,
  dù cả hai đều là truy vấn theo khoá chính/chỉ mục có sẵn, dự kiến chi phí
  nhỏ so với chính truy vấn OpenSearch.
- **Stored XSS qua `segments[].highlight`** (mức độ **Cao** nếu bỏ qua):
  nội dung nguồn có thể là ghi chú tự do (FR-16) hoặc văn bản OCR — nếu ai
  đó từng nhập chuỗi giống HTML/script vào ghi chú, và frontend render
  thẳng `highlight` bằng `dangerouslySetInnerHTML`, chuỗi đó thực thi được
  trong trình duyệt người xem kết quả tìm kiếm. Giảm thiểu: Task 5 bắt buộc
  làm trước, không có đường tắt "làm nhanh rồi sửa sau".
- **Endpoint ảnh trang trùng lặp logic PDFBox** nếu không refactor thành
  bean dùng chung (Task 1) — 2 bản sao cùng logic dễ lệch nhau khi sửa sau
  này (ví dụ đổi DPI, thêm cache).
- **`GET /tags` không phân trang** — nếu số nhãn `topic`/`doc_type` tăng
  lớn, dropdown/multi-select cần searchable thay vì liệt kê phẳng; chấp
  nhận ở MVP (số nhãn hiện tại nhỏ), ghi nhận rủi ro UX cho giai đoạn sau.
- **Không có polling/real-time** (BA Story 4 AC-3, đã chấp nhận) — người
  dùng có thể nhầm "chưa duyệt xong" với "tìm kiếm bị lỗi" nếu không đọc kỹ
  thông điệp Story 4 AC-1; giảm thiểu bằng đúng copy đã chốt, không cần cơ
  chế kỹ thuật thêm.
- **`OcrPageViewer` sửa đổi (nếu chọn phương án sửa thay vì component
  riêng ở Task 7) có thể ảnh hưởng `digitization-review`** — bắt buộc chạy
  lại toàn bộ test hiện có của `components/digitization/__tests__` sau khi
  sửa, không chỉ test mới của slug này.
