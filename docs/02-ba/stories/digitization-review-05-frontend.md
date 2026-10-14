# User Story: digitization-review (giao diện kiểm duyệt số hoá — OCR/STT/SED/tóm tắt video + chuyển quyền sở hữu)

> File: `docs/02-ba/stories/digitization-review-05-frontend.md` — Owner: BA
> Nguồn kiến trúc: `docs/01-sa/architecture/digitization-review.md`
> Nguồn nghiệp vụ (đã Approved, có backend): `digitization-review-01-ocr.md`,
> `digitization-review-02-stt.md`, `digitization-review-03-ownership-transfer.md`,
> `digitization-review-04-sed-summary.md`
> Status: Approved

> **Bối cảnh:** Backend của cả 4 story trên đã implement + test xong
> (2026-09-08), nhưng `web/` chưa có route/component nào cho
> `digitization-review` — kể cả phần đã xong backend từ lâu. Theo quyết định
> đã chốt cùng người dùng, giao diện được gộp thành **1 slug riêng** thay vì
> làm rải rác theo từng story backend, để tránh phải sửa lại layout khi thêm
> từng phần.
>
> File này **không lặp lại** các Acceptance Criteria nghiệp vụ đã chốt ở 4
> file nguồn (ai được xem, khi nào review_state đổi, quy tắc optimistic
> lock...) — những AC đó đã implement đúng ở backend và không đổi. File này
> chỉ định nghĩa **lớp AC riêng của giao diện**: hiển thị, tương tác, trạng
> thái tải, đồng bộ player, overlay ảnh — phần mà backend không quyết định
> thay được.

## Bản đồ màn hình

| Route | Vai trò | Nguồn AC nghiệp vụ |
|---|---|---|
| `/digitization` | R1 (CAN_BO) | Story 1 (`-01-ocr.md`) — hàng chờ, dùng chung cho cả 4 loại task |
| `/digitization/runs/[runId]` | R1 (CAN_BO), chính chủ | Story 2/4 (`-01`), Story 3 (`-02`), Story 9/10 (`-04`) |
| `/admin/document-ownership` | R3 (QUAN_TRI) | Story 7 (`-03`) |

`/digitization` đã có sẵn mục trong `web/src/lib/features.ts` (`enabled:
false`) — chỉ cần bật `enabled: true`, không đổi `roles`/`href`.
`/admin/document-ownership` là tab mới trong `AdminTabs` hiện có.

---

## Story 1: Hàng chờ kiểm duyệt (giao diện cho `GET /digitization-review/queue`)

**As a** cán bộ (R1)
**I want** xem danh sách tài liệu tôi cần duyệt, lọc theo loại (OCR/STT/SED/
tóm tắt video), trạng thái, cờ chất lượng
**So that** tôi biết việc gì cần làm trước, đặc biệt các tài liệu sắp hết
hạn hoặc đang treo lâu

### Acceptance Criteria

- [ ] AC-1: Bảng liệt kê run với các cột: tên tài liệu, loại task (nhãn tiếng
      Việt: "OCR", "STT", "Âm thanh sự kiện", "Tóm tắt video"), trạng thái
      hiệu lực (`effectiveStatus`: đang chờ / đang sửa / quá hạn sửa / sắp
      hết hạn / đã xác nhận — mỗi trạng thái một màu badge riêng biệt, "quá
      hạn"/"sắp hết hạn" tô màu cảnh báo), cờ chất lượng (nếu `poor`), số
      đoạn, hạn duyệt (định dạng tương đối, ví dụ "còn 3 giờ" khi gần hạn).
- [ ] AC-2: Bộ lọc loại task dạng **tab** — "Tất cả" (không gửi `task` trong
      query, API trả mọi loại khi bỏ trống) + 1 tab riêng cho từng loại
      (`ocr`/`stt`/`sed`/`video_summary`, khớp đúng API chỉ nhận 1 giá trị
      `task`/lần gọi — chốt 2026-09-08, không làm multi-select). Ngoài ra:
      chọn trạng thái (multi), cờ chất lượng (checkbox). Đổi filter → gọi
      lại API, không cần bấm nút "Áp dụng" riêng (theo đúng pattern
      `documents/page.tsx` hiện có).
- [ ] AC-3: Phân trang chuẩn (giống `admin/audit` — có `page`/`size`, nút
      trước/sau hoặc số trang).
- [ ] AC-4: Click một dòng → điều hướng `/digitization/runs/{runId}`.
- [ ] AC-5: Danh sách rỗng → thông báo "Không có tài liệu nào cần duyệt.",
      không phải bảng trống im lặng.
- [ ] AC-6: Badge riêng cho run đang **thay thế run đã confirmed**
      (`isReplacingConfirmedRun=true`, Story 8 `-01`) — cảnh báo ngắn gọn để
      R1 biết đây không phải lần duyệt đầu.

---

## Story 2: Xem/sửa văn bản OCR (ảnh trang gốc + overlay bbox)

**As a** cán bộ (R1), chính chủ tài liệu
**I want** xem ảnh trang gốc kèm khung toạ độ (bbox) song song với văn bản
OCR, sửa văn bản nếu cần
**So that** tôi hiệu đính đúng vị trí, không nhầm đoạn này với đoạn khác khi
văn bản OCR không đủ ngữ cảnh để phân biệt

### Acceptance Criteria

- [ ] AC-1: Mở run OCR → hiển thị ảnh trang hiện tại (mặc định trang của
      đoạn đầu tiên) bên trái, danh sách đoạn văn bản bên phải; mỗi đoạn có
      1 khung bbox overlay lên đúng vị trí trên ảnh (toạ độ tỉ lệ 0-1 theo
      `content_segment.bbox_polygon`, đã chốt trong schema — không phải
      pixel, xem `V26`). *(Bổ sung 2026-09-23)* Màn hình rộng (≥ `lg`):
      danh sách đoạn cố định chiều cao theo cửa sổ, cuộn riêng trong vùng
      đó — ảnh trang luôn nhìn thấy khi cuộn danh sách dài; màn hẹp (1 cột)
      giữ cuộn cả trang như cũ.
- [ ] AC-2: Click vào một đoạn trong danh sách → khung bbox tương ứng trên
      ảnh được làm nổi bật (đổi màu viền); click vào một khung bbox trên
      ảnh → đoạn tương ứng trong danh sách được cuộn tới và làm nổi bật —
      2 chiều.
- [ ] AC-3: Đoạn không có `bboxPolygon` (null — ví dụ đoạn thêm thủ công)
      → không vẽ khung, chỉ hiện trong danh sách text như bình thường,
      không lỗi.
- [ ] AC-4: Tài liệu nhiều trang (PDF) → có điều hướng đổi trang (trước/sau
      hoặc chọn số trang); đổi trang chỉ tải lại ảnh trang đó + lọc đoạn
      thuộc trang đó trong danh sách, không tải lại toàn bộ danh sách đoạn.
- [ ] AC-5: Sửa văn bản một đoạn: ô nhập text hiện `currentText`, có nút xem
      `aiText` gốc để đối chiếu (ẩn/hiện, không phải luôn hiện cả hai — theo
      thiết kế gọn), lưu qua `PATCH .../segments/{id}` với
      `expectedRevisionNo` (optimistic lock — đã có ở backend).
- [ ] AC-6: Lưu thành công → cập nhật `currentText`/`currentRevisionNo` tại
      chỗ, không tải lại toàn trang. Lưu thất bại do `409` (revision cũ) →
      thông báo rõ, tự tải lại đoạn đó với bản mới nhất, người dùng sửa lại
      trên bản mới.
- [ ] AC-7: Xoá đoạn (`DELETE`) → xác nhận trước khi xoá (dialog, theo pattern
      `useConfirm()` đã có), đoạn biến mất khỏi danh sách VÀ khung bbox biến
      mất khỏi ảnh ngay.
- [ ] AC-8: Thêm đoạn thủ công (`POST .../segments`) → không có bbox (đoạn
      thủ công không gắn vị trí ảnh), chỉ hiện trong danh sách text, không
      vẽ trên ảnh.
- [ ] AC-9: Ảnh trang tải lỗi hoặc quá chậm (>3s) → hiện thông báo, danh sách
      đoạn văn bản vẫn dùng được bình thường (sửa/xoá/thêm không phụ thuộc
      ảnh tải được hay không — ảnh chỉ hỗ trợ đối chiếu vị trí, không chặn
      luồng chính).

## Ngoài phạm vi (Out of scope) — Story 2

- Vẽ/sửa lại khung bbox bằng tay (chỉ xem, không chỉnh toạ độ) — nếu AI xác
  định sai vị trí, R1 sửa text hoặc xoá đoạn, không có công cụ vẽ lại khung.
- Zoom/pan ảnh nâng cao (kiểu Photoshop) — chỉ cần phóng to/thu nhỏ cơ bản
  (trình duyệt native hoặc nút +/- đơn giản), không cần thao tác kéo-thả
  phức tạp.

---

## Story 3: Xem/sửa văn bản STT (player đồng bộ + mốc thời gian)

**As a** cán bộ (R1), chính chủ tài liệu
**I want** nghe/xem lại đúng đoạn âm thanh/video tương ứng với từng đoạn văn
bản khi hiệu đính, và sửa mốc thời gian nếu bị lệch
**So that** tôi xác nhận đúng nội dung đã nói, không chỉ dựa vào chữ

### Acceptance Criteria

- [ ] AC-1: Mở run STT → hiển thị player (`<audio>` nếu nguồn chỉ có âm
      thanh, `<video>` nếu có hình — theo `document.mimeType` của
      `digitization_source`) phía trên, danh sách đoạn văn bản phía dưới,
      mỗi đoạn có mốc `[timeStartMs → timeEndMs]` hiển thị dạng `mm:ss`.
      *(Bổ sung 2026-09-23)* Danh sách đoạn cố định chiều cao, cuộn riêng —
      player luôn nhìn thấy (cùng quy tắc Story 2 AC-1).
- [ ] AC-2: Click một đoạn → player nhảy tới (`seek`) đúng `timeStartMs` và
      phát; đang phát tới hết `timeEndMs` của đoạn đang chọn → tự dừng
      (không phát tràn sang đoạn kế tiếp), trừ khi người dùng chủ động bấm
      play tiếp.
- [ ] AC-3: Player đang phát → đoạn văn bản tương ứng với thời điểm hiện tại
      được làm nổi bật trong danh sách (đồng bộ theo thời gian thực, không
      cần click); *(bổ sung 2026-09-23)* danh sách tự cuộn để đoạn đang
      phát luôn nằm trong vùng nhìn thấy.
- [ ] AC-4: Sửa mốc thời gian: ô nhập `timeStartMs`/`timeEndMs` (dạng `mm:ss`
      hoặc số giây, quy đổi 2 chiều), kẹp giá trị trong
      `[0, sourceDurationMs]` ở tầng UI trước khi gửi (tier 1 theo DEV-spec
      `-02`, chỉ là UX, backend vẫn validate lại).
- [ ] AC-5: Đoạn có `overlapsAdjacent=true` → hiển thị cảnh báo trực quan
      (icon/màu), không chặn lưu (chỉ cảnh báo).
- [ ] AC-6: Sửa chỉ văn bản (không đổi mốc thời gian) → gửi request không kèm
      `timeStartMs`/`timeEndMs`, luôn thành công kể cả khi
      `sourceDurationMs` là `null` (AC-4 backend).
- [ ] AC-7: Thêm/xoá đoạn thủ công — cùng hành vi Story 2 AC-7/8, không có
      bbox nhưng có thể nhập mốc thời gian khi thêm thủ công.

---

## Story 4: Xem âm thanh sự kiện đã gắn nhãn (SED, chỉ đọc phần lớn)

**As a** cán bộ (R1), chính chủ tài liệu
**I want** nghe lại đúng đoạn âm thanh của một sự kiện đã được AI gắn nhãn
(ví dụ "chó sủa"), xác nhận nhãn đúng hay đánh dấu sai
**So that** chỉ mục chỉ chứa các sự kiện âm thanh đã qua xác minh

### Acceptance Criteria

- [ ] AC-1: Mở run SED → player + danh sách sự kiện, mỗi dòng hiện
      `soundEventLabel` (nhãn AI gốc tiếng Anh, ví dụ "Dog bark") song song
      `currentText` (nhãn tiếng Việt hiện hành, có thể sửa) và mốc thời gian
      — click để nghe, giống hành vi player Story 3 AC-1-3.
- [ ] AC-2: **Không có** ô nhập mốc thời gian (SED không cho sửa thời gian —
      `422` nếu cố gửi, theo backend) — chỉ sửa được text nhãn.
- [ ] AC-3: **Không có** nút "Thêm đoạn thủ công" cho run loại SED (backend
      trả `409` nếu gọi — ẩn nút này ở UI thay vì để người dùng bấm rồi gặp
      lỗi).
- [ ] AC-4: Xoá (đánh dấu nhãn sai) — cùng hành vi Story 2 AC-7.

---

## Story 5: Xem tóm tắt video (chỉ đọc, có liên kết nguồn)

**As a** cán bộ (R1), chính chủ tài liệu
**I want** đọc lại tóm tắt video AI sinh ra, xem đoạn nào trong video gốc
được dùng làm căn cứ
**So that** tôi xác nhận tóm tắt phản ánh đúng nội dung trước khi công khai

### Acceptance Criteria

- [ ] AC-1: Mở run tóm tắt video → hiển thị văn bản tóm tắt; mỗi đoạn có
      `sourceReferences` (danh sách liên kết tới đoạn gốc: OCR/STT/SED đã
      dùng làm căn cứ) — mỗi liên kết hiện tóm tắt ngắn (ví dụ "STT #3,
      01:15") và khi click, mở `/digitization/runs/{sourceRunId}` **ở tab
      mới** (không điều hướng rời khỏi màn hình tóm tắt đang xem). **Màn
      hình này không có player riêng** — đoạn tóm tắt không có mốc thời
      gian của chính nó (`timeStartMs`/`timeEndMs` luôn `null` cho
      `content_source='summary'`, xem `V26`); nghe/xem đoạn gốc là việc của
      màn hình `sourceRunId` mở ra (Story 3/4, đã có player riêng ở đó).
- [ ] AC-2: `sourceRunReviewState` của liên kết là `superseded` → vẫn mở
      được bình thường (không có gì đặc biệt phải xử lý ở UI ngoài việc
      không tự động chuyển hướng sang run mới hơn — đúng AC-4 Story 10
      backend).
- [ ] AC-3: `staleWarning=true` → banner cảnh báo rõ ràng phía trên tóm tắt
      ("Tóm tắt này có thể đã lỗi thời — có thay đổi mới hơn ở [các nguồn]
      sau khi tóm tắt được xác nhận"), không chặn xem/đọc.
- [ ] AC-4: **Không có** nút xoá đoạn hoặc thêm đoạn thủ công (backend trả
      `409` cho cả hai — ẩn nút, giống Story 4 AC-3).
- [ ] AC-5: Sửa văn bản tóm tắt — **có**, dùng chung cơ chế `PATCH` như các
      loại khác (SA không giới hạn sửa text cho `video_summary`, chỉ giới
      hạn xoá/thêm thủ công).

---

## Story 6: Xác nhận run (dùng chung cho cả 4 loại)

**As a** cán bộ (R1), chính chủ tài liệu
**I want** một nút "Xác nhận" rõ ràng ở màn hình duyệt, dù tôi có sửa gì hay
không
**So that** tôi chủ động đưa dữ liệu vào chỉ mục chính thức thay vì chỉ chờ
tự động xác nhận sau 24h

### Acceptance Criteria

- [ ] AC-1: Nút "Xác nhận" luôn hiện ở header màn hình `runs/[runId]` khi
      `reviewState ∈ {pending, in_review}`; ẩn/đổi thành badge "Đã xác nhận"
      khi `reviewState = confirmed`.
- [ ] AC-2: Bấm xác nhận → dialog xác nhận ngắn (không cần nhập gì thêm) →
      gọi `POST .../confirm` → thành công thì cập nhật trạng thái tại chỗ +
      thông báo, điều hướng về `/digitization` sau 1-2 giây hoặc để người
      dùng tự bấm quay lại (không tự động ngay lập tức, tránh cảm giác giật).
- [ ] AC-3: Xác nhận thất bại (`409` — trạng thái đã đổi bởi request khác,
      ví dụ auto-confirm vừa chạy) → thông báo rõ, tải lại trạng thái mới
      nhất.

---

## Story 7: Chuyển quyền sở hữu tài liệu (R3)

**As a** quản trị viên (R3)
**I want** một màn hình quản trị để tìm tài liệu theo người nạp và chuyển
quyền sở hữu
**So that** tôi xử lý nhanh khi một người nghỉ việc/vắng mặt có nhiều tài
liệu tồn đọng

### Acceptance Criteria

- [ ] AC-1: Tab "Chuyển quyền sở hữu" trong khu vực quản trị
      (`/admin/document-ownership`, thêm vào `AdminTabs`); form tìm theo
      `uploaderSub` — chọn từ danh sách tài khoản (tái dùng dữ liệu đã có ở
      `/admin/accounts`, lọc còn hoạt động), không gõ tay UUID.
- [ ] AC-2: Bảng kết quả: tên tài liệu, 4 trạng thái số hoá (OCR/STT/SED/tóm
      tắt video, mỗi cột 1 badge nhỏ) — **không hiển thị bất kỳ nội dung số
      hoá nào** (đúng ràng buộc "R3 không đọc nội dung", metadata-only đã
      chốt ở `-03` §3.1).
- [ ] AC-3: Mỗi dòng có nút "Chuyển quyền sở hữu" → dialog chọn người nhận
      (danh sách tài khoản R1 đang hoạt động, tái dùng `GET /accounts`) +
      ô lý do (tuỳ chọn, tối đa 500 ký tự, đúng chốt `-03`).
- [ ] AC-4: Chuyển thành công → dòng đó cập nhật `uploaderSub` mới tại chỗ,
      thông báo rõ; vì đang lọc theo `uploaderSub` cũ, dòng này nên tự động
      biến mất khỏi danh sách đang xem (đã không còn thuộc người nạp đang
      lọc) sau khi chuyển thành công.
- [ ] AC-5: Không hỗ trợ chọn nhiều dòng để chuyển hàng loạt trong 1 lần bấm
      (bulk transfer ngoài phạm vi, theo `-03`) — nhưng UI nên đủ nhanh để
      chuyển lần lượt nhiều dòng liên tiếp (dialog đóng lại sau mỗi lần
      chuyển, không phải thao tác lại từ đầu).

---

## Phụ thuộc / Ghi chú kiến trúc liên quan

- Không có SA riêng cho phần frontend — quy ước kỹ thuật (Next.js App
  Router, Client Component, Tailwind thuần, không thư viện UI, `apiJson`/
  `apiFetch`, `AppShell`/`AdminTabs`/`features.ts`) đã là chuẩn chung của
  `web/`, áp dụng nguyên trạng, không phải quyết định kiến trúc mới.
- Quyết định 5 (`digitization-review.md`): R2 **không** có đường vào các màn
  hình này (chỉ đọc sau xác nhận qua tìm kiếm/báo cáo — chưa xây); R3 không
  đọc nội dung — chỉ Story 7.
- `V26__digitization_content_segments.sql`: `bbox_polygon` là tỉ lệ 0-1 trên
  trang (đã chốt 2026-09-07) — Story 2 dựa trực tiếp vào quy ước này.

## Câu hỏi mở cho DEV

- [x] ~~Story 2 cần 1 endpoint backend MỚI để render ảnh từng trang~~ — **đã
      chốt trong DEV-spec** (§3.1): Apache PDFBox 3.0.8, 150 DPI, PNG.
- [x] ~~Story 3/4/5 dùng chung 1 component player~~ — **đã chốt** sau khi rà
      soát lại 2026-09-08: chỉ Story 3 (STT) + Story 4 (SED) dùng chung
      `SegmentPlayer` (cả hai có mốc thời gian thật). Story 5 (tóm tắt
      video) **không có player** — `timeStartMs`/`timeEndMs` luôn `null`
      cho `content_source='summary'` (xem AC-1 Story 5 đã sửa).
