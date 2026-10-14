# User Story: search-fulltext (giao diện tìm kiếm toàn văn)

> File: `docs/02-ba/stories/search-fulltext-05-frontend.md` — Owner: BA
> Nguồn kiến trúc: `docs/01-sa/architecture/search-fulltext.md`
> Nguồn nghiệp vụ (đã Approved, có backend): `search-fulltext-01-tim-kiem-co-ban.md`,
> `search-fulltext-02-tim-kiem-nang-cao.md`, `search-fulltext-03-dinh-vi-ket-qua.md`,
> `search-fulltext-04-dong-bo-chi-muc.md`
> Status: Approved

> **Bối cảnh:** Backend của cả 4 story trên đã implement + test xong
> (2026-09-08, `POST /search`), nhưng `web/` **chưa có route/component nào**
> cho tìm kiếm — `POST /search` hiện không ai gọi được từ giao diện. Theo
> đúng mô hình đã dùng cho `digitization-review-05-frontend` (gộp UI của
> nhiều story backend thành 1 slug riêng), file này gộp UI của cả 4 story
> `search-fulltext-01..04` thành **1 trang duy nhất** `/search`.
>
> File này **không lặp lại** các Acceptance Criteria nghiệp vụ đã chốt ở 4
> file nguồn (ranking, ACL/phạm vi, giới hạn đầu vào, cơ chế đồng bộ chỉ
> mục...) — những AC đó đã implement đúng ở backend và không đổi. File này
> chỉ định nghĩa **lớp AC riêng của giao diện**: bố cục, tương tác, copy
> tiếng Việt, cách hiển thị vị trí/badge — phần mà backend không quyết định
> thay được.
>
> Không có tài liệu SA riêng cho slug này: `search-fulltext.md` (Approved)
> đã tự nêu rõ "UI/UX chi tiết và user story → BA" là ngoài phạm vi của nó,
> và mục 5 (Tech stack) đã phác hoạ sẵn hình dạng UI ở mức cao (ô từ khoá +
> panel lọc, kết quả nhóm theo tài liệu, nút nhảy tới vị trí) — file BA này
> cụ thể hoá đúng khung đó, không quyết định lại kiến trúc.

## Bản đồ màn hình

| Route | Vai trò | Nguồn AC nghiệp vụ |
|---|---|---|
| `/search` | R1 (CAN_BO), R2 (LANH_DAO) | Story 1 (`-01`), Story 2 (`-02`), Story 3 (`-03`), Story 4 (`-04`) — gộp 1 trang |

`/search` đã có sẵn mục trong `web/src/lib/features.ts` (`key: 'search'`,
`href: '/search'`, `roles: [CAN_BO, LANH_DAO]`, hiện `enabled: false`) — chỉ
cần bật `enabled: true`, không đổi `roles`/`href`.

---

## Story 1: Tìm kiếm cơ bản và danh sách kết quả (giao diện cho `POST /search`, phần cơ bản)

**As a** người dùng R1/R2
**I want** một ô tìm kiếm ở đầu trang và danh sách kết quả rõ ràng, có phân
trang
**So that** tôi tra cứu nhanh mà không cần biết gì về cách hệ thống lập chỉ
mục hay xếp hạng

### Acceptance Criteria

- [ ] AC-1: Một ô từ khoá lớn ở đầu trang `/search`, gõ Enter hoặc bấm nút
      "Tìm kiếm" để gửi yêu cầu; không tự tìm khi đang gõ (debounce/submit
      thủ công, tránh gọi API liên tục).
- [ ] AC-2: Kết quả hiển thị dạng danh sách **card theo tài liệu** (không
      phải theo đoạn): tên tài liệu, loại tài liệu (`docType`, nhãn tiếng
      Việt nếu có), tối đa 3 đoạn trích kèm highlight — **không** hiển thị
      điểm số (`score`) thô ra giao diện, chỉ dùng nội bộ để sắp xếp.
- [ ] AC-3: Đoạn trích highlight (`segments[].highlight`, chứa markup
      `<em>...</em>` do server sinh) được hiển thị **an toàn**: chỉ nhận
      diện đúng cặp thẻ `<em>`/`</em>` để tô đậm, phần còn lại của văn bản
      luôn được escape như văn bản thường — **không** render thẳng chuỗi
      HTML thô từ server (rủi ro bảo mật, xem mục 7 DEV-spec).
- [ ] AC-4: Có lựa chọn sắp xếp "Liên quan nhất" (mặc định) / "Mới nạp nhất"
      (`sort=relevance|recent`), dạng dropdown hoặc nút chuyển đổi đơn giản.
- [ ] AC-5: Phân trang chuẩn theo đúng pattern đã dùng ở `admin/audit`/
      `digitization` (trước/sau hoặc số trang), `size` mặc định 20.
- [ ] AC-6: Số lượng kết quả hiển thị đúng nguyên văn dạng ước lượng: **"Khoảng
      {totalApprox} kết quả"** — không hiển thị con số như thể chính xác
      tuyệt đối (ví dụ không ghi "Tìm thấy đúng N kết quả").
- [ ] AC-7: `totalApprox = 0` và không có gợi ý nới lỏng bộ lọc nào (xem
      Story 2) → thông báo **"Không tìm thấy kết quả phù hợp với từ khoá/bộ
      lọc đã chọn."**
- [ ] AC-8: Bấm tìm khi ô từ khoá rỗng **và** chưa chọn bộ lọc nào ở
      Story 2 → báo lỗi inline ngay dưới ô từ khoá: **"Vui lòng nhập từ khoá
      hoặc chọn ít nhất một bộ lọc."** (nguyên văn thông báo 400 của
      backend) — không gửi request.
- [ ] AC-9: Backend trả lỗi timeout (504) → banner đỏ phía trên danh sách kết
      quả: **"Tìm kiếm quá thời gian cho phép, vui lòng thu hẹp bộ lọc hoặc
      thử lại sau."** (nguyên văn), danh sách kết quả cũ (nếu có) bị xoá,
      không hiển thị kết quả một phần.
- [ ] AC-10: `degradedMode = "opensearch_unavailable"` → banner **vàng**
      (khác màu với lỗi ở AC-9, đây là suy giảm chức năng có kiểm soát, không
      phải lỗi): **"Công cụ tìm kiếm toàn văn đang tạm thời không khả dụng.
      Bạn vẫn có thể lọc theo tiêu chí ở bảng bên dưới."** — nếu người dùng
      đang tìm chỉ bằng bộ lọc (không từ khoá) thì kết quả vẫn hiển thị bình
      thường qua đường dự phòng (Story 4 AC-2), banner này chỉ xuất hiện khi
      có từ khoá.

## Ngoài phạm vi (Out of scope) — Story 1

- Hiển thị điểm số/thuật toán xếp hạng ra người dùng cuối.
- Tìm kiếm gợi ý/autocomplete khi đang gõ — không có trong 4 story nguồn.

---

## Story 2: Bộ lọc nâng cao và cú pháp rút gọn (giao diện cho `filters` + cú pháp trong ô từ khoá)

**As a** người dùng R1/R2
**I want** một panel bộ lọc rõ ràng cạnh ô tìm kiếm, và biết cách dùng cú
pháp rút gọn ngay trong ô từ khoá
**So that** tôi thu hẹp kết quả nhanh mà không phải đọc tài liệu hướng dẫn
riêng

### Acceptance Criteria

- [ ] AC-1: Panel bộ lọc (thu/mở được, không chiếm màn hình khi không dùng)
      gồm đúng 7 nhóm theo `SearchFilters`: Loại tài liệu (`docType`, dropdown
      1 giá trị, nguồn dữ liệu `GET /tags?kind=doc_type`), Nhãn chủ đề
      (`topicTags`, multi-select + toggle AND/OR, nguồn `GET /tags?kind=topic`),
      Thời gian nạp (`uploadedFrom`/`uploadedTo`, date range, nhãn rõ **"Ngày
      nạp kho"**), Nhóm (`groupIds`, multi-select, nguồn `GET /groups`), Thư
      mục (`folderId`, chọn 1 nút trên cây thư mục — dùng lại cây từ
      `GET /folders/tree` như `documents/page.tsx`), Loại media
      (`mediaType`, checkbox: Văn bản/Âm thanh/Video), Nguồn xác nhận
      (`confirmSource`, dropdown: Tất cả/Đã xác nhận thủ công/Tự động xác
      nhận).
- [ ] AC-2: Đổi bất kỳ bộ lọc nào **không** tự động tìm lại ngay (khác
      `documents/page.tsx`) — người dùng bấm "Tìm kiếm" sau khi chọn xong,
      vì tìm kiếm toàn văn tốn chi phí hơn lọc danh sách tài liệu đơn thuần.
- [ ] AC-3: Cạnh ô từ khoá có một icon/link "Cú pháp tìm kiếm" — bấm vào hiện
      tooltip/popover (không phải trang riêng) liệt kê: `"cụm từ"` (khớp
      chính xác cụm liền kề), `-từ` (loại trừ), `+từ` (bắt buộc), `OR` (khớp
      một trong hai).
- [ ] AC-4: Chọn quá 20 giá trị cho một bộ lọc dạng nhiều-giá-trị, hoặc gõ từ
      khoá vượt 256 ký tự/32 từ, bị chặn/báo lỗi **ngay ở giao diện** trước
      khi gửi request (không chờ backend trả 400).
- [ ] AC-5: Khi `totalApprox = 0` **và** backend trả về `refineSuggestions`
      (ít nhất 1 gợi ý), hiển thị ngay dưới thông báo "Không tìm thấy kết
      quả..." (Story 1 AC-7) dưới dạng các nút bấm, mỗi nút một gợi ý: **"Bỏ
      {nhãn bộ lọc} → khoảng {estimatedCount} kết quả"**. Bấm vào một gợi ý
      sẽ bỏ đúng nhóm bộ lọc đó và tìm lại ngay. Nhãn tiếng Việt cho từng
      `removedFilter`:
      - `docType` → "Loại tài liệu"
      - `topicTags` → "Nhãn chủ đề"
      - `uploadedFrom/uploadedTo` → "Khoảng thời gian nạp"
      - `groupIds` → "Nhóm"
      - `folderId/mediaType/confirmSource` → "Thư mục / loại media / nguồn
        xác nhận"
- [ ] AC-6: Gõ ký tự ngoài cú pháp hỗ trợ (`*`, `~`, `field:value`...) vào ô
      từ khoá **không** bị chặn/báo lỗi cú pháp ở giao diện — gửi nguyên văn
      lên backend như văn bản thường (đúng hành vi `simple_query_string`).

## Ngoài phạm vi (Out of scope) — Story 2

- Trang trợ giúp cú pháp riêng biệt — dùng tooltip/popover inline ở giai
  đoạn 1.
- Lưu bộ lọc đã dùng gần đây / tìm kiếm đã lưu — không có trong story nguồn.

---

## Story 3: Định vị và nhảy tới vị trí kết quả (giao diện cho `segments[].position`)

**As a** người dùng R1/R2
**I want** bấm vào một đoạn trích để xem đúng chỗ trong tài liệu gốc (trang
+ khoanh vùng cho văn bản, mốc thời gian cho audio/video)
**So that** tôi xác minh nhanh, đặc biệt khi dữ liệu dùng làm chứng cứ

### Acceptance Criteria

- [ ] AC-1: Đoạn trích có `position.type = "page"` hiển thị kèm dòng nhỏ
      "Trang {pageNo}, dòng {lineFrom}–{lineTo}" và một nút **"Xem trên ảnh
      trang"**. Bấm vào mở khung xem ảnh trang gốc (overlay/modal) tại đúng
      trang, có khoanh sáng vùng khớp (dùng `bboxPolygon`) — cùng kiểu trải
      nghiệm khoanh vùng đã có ở màn `digitization/runs/[runId]` (Story 2,
      `digitization-review-05-frontend.md`), không phát minh lại kiểu hiển
      thị mới.
- [ ] AC-2: Đoạn trích có `position.type = "time"` hiển thị kèm "Từ
      {mm:ss} đến {mm:ss}" và một nút **"Nghe/Xem đoạn này"**. Bấm vào mở
      trình phát audio/video (overlay/modal), tự động tua tới đúng mốc và
      **tự động phát** — cùng kiểu trải nghiệm player đã có ở
      `digitization/runs/[runId]` (Story 3/4, `digitization-review-05-frontend.md`).
- [ ] AC-3: Đoạn trích có `position.type = "none"` và **không có**
      `sourceReferences` nào (`contentSource` là `note` hoặc `title` —
      cũng như trường hợp `summary` mà mọi tham chiếu nguồn đều rỗng, xem
      AC-4) hiển thị badge rõ ràng **"Chỉ định vị được ở cấp tài liệu"** —
      **không** có nút nhảy tới, **không** hiển thị số trang/mốc thời gian
      giả.
- [ ] AC-4 (mới, đã xác nhận với người dùng 2026-09-09): Đoạn trích tóm tắt
      video (`contentSource = "summary"`) có ít nhất 1 `sourceReferences[]`
      **không** hiển thị badge "Chỉ định vị được ở cấp tài liệu" (AC-3) —
      thay vào đó hiển thị danh sách liên kết **"Đoạn #{segmentIndex},
      {mm:ss}"** (một liên kết cho mỗi tham chiếu, bỏ `, {mm:ss}` nếu
      `timeStartMs = null`), bấm vào mở `/digitization/runs/{sourceRunId}`
      ở tab mới — **đúng nguyên trải nghiệm đã có và đã xác minh trên trình
      duyệt thật** ở màn `digitization/runs/[runId]` (Story 10,
      `digitization-review-05-frontend.md`, checklist mục 8.14), không thiết
      kế lại. Không cần hiển thị `sourceRunReviewState` trên card kết quả
      tìm kiếm — người dùng thấy trạng thái run đó khi đã mở link.
- [ ] AC-5 (trước là AC-4): Đoạn trích có `confirmSource = "auto"` hiển thị
      badge cảnh báo màu vàng cạnh đoạn trích: **"Nội dung tự động xác nhận
      — cân nhắc kỹ khi dùng làm chứng cứ"** (cùng tông màu banner cảnh báo
      đã dùng ở `staleWarning` của `digitization/runs/[runId]`: nền vàng
      nhạt, viền vàng, chữ vàng đậm).
- [ ] AC-6 (trước là AC-5): Thứ tự các đoạn trích trong một tài liệu giữ
      **nguyên** thứ tự backend trả về (đã sắp theo điểm liên quan rồi theo
      trang/thời gian tăng dần) — giao diện không tự sắp xếp lại; thứ tự
      `sourceReferences[]` trong AC-4 cũng giữ nguyên thứ tự backend trả về.

## Ngoài phạm vi (Out of scope) — Story 3

- Sửa nội dung/toạ độ ngay từ màn hình tìm kiếm — chỉ xem, việc sửa (nếu
  cần) thực hiện ở màn `digitization/runs/[runId]` như hiện có.
- Ánh xạ offset ký tự sang số dòng chính xác — bbox đã đủ, đúng quyết định
  đã chốt ở `search-fulltext.md` QĐ 4.
- Hiển thị `sourceRunReviewState` (ví dụ "đã bị thay thế") ngay trên card
  kết quả tìm kiếm (AC-4) — chỉ cần link mở đúng run, xem chi tiết trạng thái
  ở màn hình đó.

---

## Story 4: Trạng thái đồng bộ chỉ mục trên giao diện (nội dung chờ kiểm duyệt, suy giảm chức năng)

**As a** người dùng R1/R2
**I want** biết rõ khi nào một tài liệu vừa nạp chưa tìm được đầy đủ theo
nội dung, và khi công cụ tìm kiếm gặp sự cố
**So that** tôi không nhầm "chưa tìm được" với "tìm kiếm bị lỗi"

### Acceptance Criteria

- [ ] AC-1 (đã sửa 2026-09-09 theo xác nhận người dùng): Một kết quả tài
      liệu có `documentDigitizationStatus` **khác `null` và khác
      `"confirmed"`** (tức đang thực sự có ít nhất 1 tác vụ số hoá ở trạng
      thái `queued`/`processing`/`pending_review`/`in_review`/`failed`) hiển
      thị thêm dòng ghi chú màu xám ngay dưới tên tài liệu: **"Nội dung
      đang chờ kiểm duyệt, có thể mất tới 24 giờ để tìm được theo nội dung
      đầy đủ."** — đây là thông tin, **không** phải cảnh báo lỗi (không
      dùng màu đỏ/vàng). `documentDigitizationStatus = null` (tài liệu chưa
      từng có run nào — xem DEV-spec mục 2) **không** hiển thị dòng này.
      **Không** dựa vào `contentSource` của các đoạn trích khớp để suy luận
      (cách cũ có thể sai: một tài liệu đã duyệt xong nhưng từ khoá chỉ
      tình cờ khớp đúng tên tệp vẫn sẽ có mọi đoạn trích `contentSource =
      "title"`).
- [ ] AC-2: Khi `degradedMode = "opensearch_unavailable"` **và** người dùng
      tìm chỉ bằng bộ lọc (không nhập từ khoá), kết quả vẫn hiển thị bình
      thường (đi qua đường dự phòng SQL của backend) — giao diện không cần
      biết/hiển thị gì khác thường trong trường hợp này, chỉ áp dụng banner
      cảnh báo (Story 1 AC-10) khi có từ khoá.
- [ ] AC-3: Không có cơ chế tự làm mới/polling — người dùng phải tự bấm tìm
      lại để thấy nội dung vừa được duyệt/đổi nhãn/chia sẻ. Đây là hành vi
      **đã chấp nhận**, không phải thiếu sót cần bổ sung sau.

## Ngoài phạm vi (Out of scope) — Story 4

- Thông báo đẩy/real-time khi tài liệu được duyệt xong — không có kênh
  thông báo ở giai đoạn 1 (đúng quyết định đã chốt ở SA gốc).
- Ngưỡng "thao tác hàng loạt" cần gom lô ở tầng đồng bộ chỉ mục — thuần kỹ
  thuật backend, không có gì hiển thị ở giao diện.

---

## Phụ thuộc / Ghi chú kiến trúc liên quan

- `search-fulltext.md` mục 5 (Tech stack): khung UI cấp cao — ô từ khoá +
  panel lọc, kết quả nhóm theo tài liệu, nút nhảy tới vị trí — file này cụ
  thể hoá đúng khung đó.
- `-01` AC-1/AC-2/AC-3/AC-7-AC-11 — cơ sở Story 1.
- `-02` AC-1-AC-14 — cơ sở Story 2 (bộ lọc + cú pháp + gợi ý 0 kết quả).
- `-03` AC-1-AC-7 — cơ sở Story 3 (định vị, badge tự động xác nhận).
- `-04` AC-1-AC-2, AC-7 — cơ sở Story 4 (thông điệp chờ kiểm duyệt, suy giảm
  chức năng qua bộ lọc thuần).
- `digitization-review-05-frontend.md` Story 2 (`OcrPageViewer`)/Story 3-4
  (`SegmentPlayer`)/Story 10 (liên kết `sourceReferences` tới run gốc) —
  trải nghiệm khoanh vùng/player/liên kết tóm tắt video đã có, Story 3 file
  này chủ động dùng lại thay vì thiết kế mới.
- `digitization-results-store.md` (`document_digitization_status`,
  `overall_status`) — nguồn dữ liệu chính xác cho Story 4 AC-1, thay cho
  suy luận từ `contentSource` (đã sửa 2026-09-09, xác nhận với người dùng).
- `digitization-review-04-sed-summary.md` §3.2 (`SourceReferenceView`,
  `content_segment.source_segment_ids`) — nguồn dữ liệu cho Story 3 AC-4
  (đã xác nhận với người dùng 2026-09-09).
- `web/src/lib/features.ts` — mục `search` đã tồn tại (`enabled: false`),
  chỉ cần bật.

## Câu hỏi mở cho DEV

- [ ] **Quan trọng:** `OcrPageViewer` hiện tại gọi
      `GET /digitization-review/runs/{runId}/pages/{pageNo}/image`, endpoint
      này chỉ cho phép **chính chủ nạp tài liệu** truy cập
      (`requireOwnedReviewableRun`, so `document.uploaderSub` với người gọi).
      Kết quả tìm kiếm (Story 3) có thể là tài liệu người khác nạp nhưng đã
      **chia sẻ nhóm** — endpoint hiện tại sẽ trả 403 sai cho trường hợp này.
      Cần DEV thiết kế lại đường truy cập ảnh trang theo phạm vi chung
      (`BusinessDataScope`/`DocumentScopeGuard`, giống `GET
      /documents/{id}/content` đang dùng), không phải phạm vi "chính chủ".
- [ ] Cách tái dùng cụ thể `OcrPageViewer`/`SegmentPlayer` (props hiện nhận
      `ReviewSegment[]`, khác hình dạng `SegmentHit`/`PositionInfo` của
      `/search`) — viết adapter chuyển đổi hay tổng quát hoá lại props hai
      component đó — quyết định kỹ thuật thuần, DEV tự chọn theo mức ảnh
      hưởng tới `digitization-review` hiện có (ưu tiên không sửa 2 component
      đó nếu tránh được).
- [ ] Vị trí hiển thị panel bộ lọc (Story 2 AC-1) — cột trái cố định (giống
      `documents/page.tsx`) hay khay trượt ẩn/hiện — không phải quyết định
      nghiệp vụ, DEV/UX chọn theo hệ thống thiết kế hiện có.
