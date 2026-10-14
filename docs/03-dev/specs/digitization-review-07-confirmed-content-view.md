# Tech Spec: digitization-review-07-confirmed-content-view

> File: `docs/03-dev/specs/digitization-review-07-confirmed-content-view.md` — Owner: DEV
> Nguồn: phát hiện qua kiểm tra thủ công trên stg (tài liệu `doc-d.m4a`, đã
> duyệt xong nhưng không còn màn hình nào xem lại), không có story BA riêng —
> xác nhận phạm vi trực tiếp với người dùng trong phiên làm việc 2026-09-22.
> Status: Approved

## 0. Bối cảnh & phạm vi đã chốt

Sau khi một `digitization_run` được xác nhận (`review_state = confirmed`),
không có màn hình nào trong UI cho phép xem lại nội dung đã số hoá:

- `GET /digitization-review/queue` **cố tình** loại bỏ vĩnh viễn run đã
  confirm (`ReviewQueueRepository` dòng comment "a confirmed run … never
  reappears here, regardless of the caller's status filter") — theo thiết kế,
  không phải bug.
- Trang hàng chờ (`/digitization`) không có filter trạng thái "Đã xác nhận".
- Trang chi tiết tài liệu (`/documents/[id]`) không có liên kết nào tới
  digitization run.
- Trang chi tiết run (`/digitization/runs/[runId]`) vẫn hiển thị đúng nội
  dung đã confirm nếu biết `runId`, nhưng gate quyền là
  `PERM_digitization_review.manage` + "chính chủ" (Quyết định 5 SA gốc,
  `docs/01-sa/architecture/digitization-review.md` §3 NFR Security) — tức
  chỉ **R1 và chính chủ tài liệu**.

**Quyết định đã xác nhận với người dùng (phiên 2026-09-22, thay thế một phần
Quyết định 5 SA cũ — xem §7):**

1. **Phạm vi:** MỞ RỘNG quyền đọc nội dung đã confirm theo **quyền xem tài
   liệu** (`PERM_document.read` + `DocumentScopeGuard`, tức chủ tài liệu HOẶC
   thành viên nhóm được chia sẻ tài liệu đó) — không giới hạn theo vai trò
   R1/R2/R3 hay theo "chính chủ" như luồng duyệt/sửa. R3 (`QUAN_TRI`) hiện
   không có `PERM_document.read` (V12) nên tự động vẫn không đọc được, khớp
   với ý "R3 không đọc được nội dung" của ADR gốc — không cần thêm điều kiện
   loại trừ tường minh.
2. **Entry point:** CHỈ trang chi tiết tài liệu (`/documents/[id]`) — thêm
   mục "Nội dung số hoá". KHÔNG đổi `/digitization` (hàng chờ của R1 giữ
   nguyên, không thêm filter "Đã xác nhận").
3. **Chế độ hiển thị:** Màn hình xem MỚI, tách biệt hoàn toàn khỏi
   `/digitization/runs/[runId]` — chỉ đọc (không có nút sửa/xoá/thêm đoạn,
   không có nút "Xác nhận"), chỉ cho xem run đã `confirmed`. Không đụng tới
   permission/hành vi hiện có của màn hình review cũ (R1 vẫn dùng y nguyên).

## 1. Tóm tắt kỹ thuật

- Backend: thêm 3 endpoint mới trong `ReviewController` (tái dùng repository
  và DTO sẵn có, tránh nhân bản logic build `RunDetailView`), gate bằng
  `PERM_document.read` + `DocumentScopeGuard.requireVisible` thay vì
  `PERM_digitization_review.manage` + "chính chủ".
- Frontend: 1 route mới `/documents/[id]/digitization/[runId]` (đọc), 1 mục
  mới trên trang chi tiết tài liệu liệt kê các run đã confirm, và thêm prop
  `readOnly` cho `SegmentList`/`OcrPageViewer` để tái dùng 3 component hiện
  có (`OcrPageViewer`, `SegmentList`, `SegmentPlayer`) thay vì viết lại.

## 2. Data model

Không có migration. Dùng lại các bảng/cột sẵn có (`digitization_run.review_state
= 'confirmed'`, `digitization_run.confirmed_at`).

## 3. API contract

| Method | Endpoint | Auth | Request | Response |
|---|---|---|---|---|
| GET | `/digitization-review/documents/{documentId}/confirmed-runs` | `PERM_document.read` + doc visible | - | `RunSummaryView[]` — chỉ các run `review_state='confirmed'` của `documentId` (tái dùng `findByDocumentIdAndReviewState`) |
| GET | `/digitization-review/runs/{runId}/view` | `PERM_document.read` + doc visible + run phải `confirmed` (404 nếu không) | `segmentPage`, `segmentSize` (giống `/runs/{runId}`) | `RunDetailView` (giống hệt `GET /runs/{runId}`, KHÔNG gọi `markFirstViewed`) |
| GET | `/digitization-review/runs/{runId}/view/pages/{pageNo}/image` | như trên | - | ảnh trang (giống `/runs/{runId}/pages/{pageNo}/image`) |

404 khi run không tồn tại / không `confirmed` / tài liệu ngoài phạm vi xem —
dùng chung 1 thông điệp, không phân biệt lý do (giữ nguyên tinh thần
`DocumentScopeGuard` "không rò rỉ tồn tại").

## 4. Luồng xử lý chính

### Backend (`ReviewController`)

1. Thêm dependency `DocumentScopeGuard documentScopeGuard` vào constructor.
2. Extract phần thân `getRun()` (từ sau `markFirstViewed` tới hết) thành
   private method `buildRunDetail(DigitizationRun run, Document document, int
   segmentPage, int segmentSize)` — thuần refactor, không đổi hành vi.
   `getRun()` gọi lại y hệt.
3. Thêm private helper:
   ```java
   private DigitizationRun requireVisibleConfirmedRun(UUID runId, KeycloakUser user) {
       DigitizationRun run = digitizationRunRepository.findById(runId)
               .filter(r -> REVIEWABLE_TASKS.contains(r.getTask()))
               .filter(r -> DigitizationRun.REVIEW_CONFIRMED.equals(r.getReviewState()))
               .orElseThrow(() -> new NotFoundException("digitization_run not found: " + runId));
       documentScopeGuard.requireVisible(user, run.getDocumentId());
       return run;
   }
   ```
4. 3 endpoint mới gọi `requireVisibleConfirmedRun`/`documentScopeGuard.requireVisible`
   thay vì `requireOwnedReviewableRun`, còn lại tái dùng nguyên logic
   (`buildRunDetail`, `pageImageRenderer.render`, `toSummary`).
5. Endpoint list confirmed-runs: `documentScopeGuard.requireVisible(user,
   documentId)` rồi `digitizationRunRepository.findByDocumentIdAndReviewState(documentId,
   "confirmed").stream().map(r -> toSummary(r, document)).toList()`.

### Frontend

1. `lib/api.ts`: không cần type mới — `ReviewRunDetail`/`ReviewSegment`/`RunSummaryView`-equivalent
   (`ReviewRunSummary`) đã đúng shape cho cả 3 response trên.
2. `components/digitization/SegmentList.tsx`: thêm prop `readOnly?: boolean`
   (mặc định `false`, không đổi hành vi hiện có). Khi `true`: ẩn khối nút
   "Sửa"/"Xoá" (dòng 367-394) và khối "+ Thêm đoạn thủ công" (dòng 401-440);
   giữ nguyên "Xem bản gốc AI" và link nguồn tham chiếu (đều chỉ đọc).
3. `components/digitization/OcrPageViewer.tsx`: thêm prop `readOnly?: boolean`
   (mặc định `false`, giữ nguyên endpoint ảnh cũ) - khi `true` fetch
   `.../view/pages/{pageNo}/image` thay vì `.../pages/{pageNo}/image`. Dùng
   boolean thay vì truyền hẳn URL/callback để tránh identity của hàm callback
   làm `useEffect` fetch ảnh chạy lại mỗi lần component cha re-render.
4. Trang mới `app/documents/[id]/digitization/[runId]/page.tsx`: copy khung
   từ `app/digitization/runs/[runId]/page.tsx` nhưng:
   - fetch `GET /digitization-review/runs/{runId}/view` thay vì
     `/digitization-review/runs/{runId}`.
   - Không có nút "Xác nhận" / không gọi `doConfirm`.
   - Không cần logic phân trang segment lặp tới khi hết dữ liệu để đơn giản
     hoá — giữ nguyên `loadFullRunDetail` pattern (dùng lại y hệt, chỉ đổi
     URL) vì run confirm cũng có thể nhiều segment.
   - Truyền `readOnly` cho `SegmentList`, truyền endpoint ảnh mới cho
     `OcrPageViewer`.
   - *(Bổ sung 2026-09-23)* Dùng cùng bố cục cuộn trong vùng như màn duyệt
     (`-05-frontend.md` mục 4.3.1, Task 10) — ảnh/player luôn nhìn thấy,
     danh sách đoạn cuộn riêng.
   - *(Bổ sung 2026-09-23)* Link "nguồn tham chiếu" của tóm tắt video trỏ
     về màn xem này (không phải màn duyệt) + `?highlightSegmentIndex=`; màn
     này cũng đọc tham số đó — xem `-04-sed-summary.md` §4 bước 5.
   - `canView` không dùng `canSeeFeature(session, 'digitization')` (đó là
     gate cho luồng review R1) — bỏ qua check phía client, để backend
     `PERM_document.read` + `DocumentScopeGuard` tự trả 403/404 nếu không có
     quyền (giống cách `/documents/[id]` hiện xử lý).
5. `app/documents/[id]/page.tsx`: thêm mục "Nội dung số hoá" (fetch `GET
   /digitization-review/documents/{id}/confirmed-runs` khi trang load), mỗi
   run hiện `TASK_LABELS[task]` + số đoạn, link tới
   `/documents/{id}/digitization/{runId}`. Không hiện gì nếu mảng rỗng (tài
   liệu chưa có run nào được duyệt) — không thêm heading rỗng gây rối UI.

## 5. Test plan

- Unit test (backend): `requireVisibleConfirmedRun` — 404 khi run không tồn
  tại, 404 khi run tồn tại nhưng chưa `confirmed`, 404 khi tài liệu ngoài
  `DocumentScopeGuard` scope của user, 200 khi user là thành viên nhóm được
  chia sẻ tài liệu (không phải người nạp, không có `digitization_review.manage`).
- Integration test: gọi cả 3 endpoint mới bằng 1 user KHÔNG có
  `digitization_review.manage` nhưng CÓ quyền xem tài liệu qua nhóm — phải
  200; gọi endpoint review cũ (`/runs/{runId}`, PATCH segment, `/confirm`)
  bằng cùng user đó — phải vẫn 403 (đảm bảo không vô tình nới lỏng luồng
  sửa/duyệt).
- Case biên: run đang `pending`/`in_review` (chưa duyệt) → endpoint `.../view`
  trả 404 dù user có quyền xem tài liệu (đúng "chỉ đọc sau khi xác nhận").
- Frontend: `SegmentList` với `readOnly=true` không render nút Sửa/Xoá/Thêm
  đoạn cho cả 4 variant; trang view mới render đúng cho `ocr`/`stt`/`sed`/`video_summary`.

## 6. Task breakdown

- [x] Backend: `DocumentScopeGuard` injected vào `ReviewController`, extract
      `buildRunDetail`, thêm `requireVisibleConfirmedRun`.
- [x] Backend: 3 endpoint mới (`confirmed-runs`, `runs/{id}/view`,
      `runs/{id}/view/pages/{n}/image`).
- [x] Backend: unit test theo §5 (`ReviewControllerConfirmedContentViewTest`,
      8 case - 404 chưa confirmed/không tồn tại/ngoài phạm vi, 200 cho user
      không phải chính chủ nhưng xem được tài liệu, không gọi
      `markFirstViewed`, `confirmedRuns` chỉ liệt kê run `confirmed`).
      **Chưa** có integration test thật (Testcontainers) - baseline IT của
      repo đang lỗi từ trước (`ApplicationContext` load fail, không liên
      quan thay đổi này - xem ghi chú vận hành `project_running_integration_tests`).
- [x] Frontend: `SegmentList` prop `readOnly`.
- [x] Frontend: `OcrPageViewer` prop `readOnly` (đổi từ `imagePath` dự kiến
      ban đầu sang boolean để tránh vấn đề identity của callback trong
      `useEffect` deps).
- [x] Frontend: route `app/documents/[id]/digitization/[runId]/page.tsx`.
- [x] Frontend: mục "Nội dung số hoá" trên `app/documents/[id]/page.tsx`.
- [x] Frontend: test cho `SegmentList` readOnly (3 case: ocr/stt/video_summary).
      **Chưa** có test riêng cho trang view mới (route page component) - có
      thể bổ sung sau nếu cần, page tái dùng logic đã test qua `SegmentList`/
      `OcrPageViewer`/loadFullRunDetail pattern sẵn có.
- [x] Build + lint + unit test (backend `mvn test -Dtest='!*IT'`: 556 tests,
      0 failure ngoài 6 lỗi ApplicationContext load tiền tồn tại không liên
      quan; frontend `npm run build`/`lint`/`vitest`: sạch). Deploy stg để
      người dùng tự kiểm tra `doc-d.m4a` - **chưa deploy**, chờ người dùng
      xác nhận trước khi đẩy lên stg.

## 7. Rủi ro kỹ thuật

- **Lệch với ADR gốc:** `docs/01-sa/architecture/digitization-review.md` §3
  NFR Security ghi "Chỉ R1 và chính chủ mới đọc/sửa/xác nhận; R2 chỉ đọc sau
  khi xác nhận, R3 không đọc được nội dung" — spec này mở rộng "đọc" (không
  đổi "sửa/xác nhận") sang mọi user có quyền xem tài liệu, không chỉ R1/R2.
  Cần cập nhật lại dòng NFR đó trong ADR (SA) để không lệch tài liệu — xem
  việc cập nhật ADR ở cuối task breakdown, làm cùng đợt này.
- Nếu sau này `document.read` được cấp thêm cho `QUAN_TRI` (qua
  rbac-permission-editing.md, permission có thể chỉnh qua UI), R3 sẽ tự động
  đọc được nội dung số hoá — ADR cần ghi rõ đây là hệ quả có chủ đích của
  quyết định mới, không phải lỗ hổng.
- `buildRunDetail` extract từ `getRun` phải giữ nguyên thứ tự side-effect
  hiện có (không có side-effect nào trong phần được extract — `markFirstViewed`
  nằm ngoài, trước khi gọi — cần double-check khi refactor không vô tình kéo
  `markFirstViewed` vào trong).
