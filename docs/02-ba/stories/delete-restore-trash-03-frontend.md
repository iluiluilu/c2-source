# User Story: delete-restore-trash (giao diện thùng rác — xóa mềm/phục hồi)

> File: `docs/02-ba/stories/delete-restore-trash-03-frontend.md` — Owner: BA
> Nguồn kiến trúc: `docs/01-sa/architecture/delete-restore-trash.md`
> Nguồn nghiệp vụ (đã Approved, có backend): `delete-restore-trash-01-xoa-mem-thung-rac.md`,
> `delete-restore-trash-02-xoa-cung-xung-dot.md`
> Status: Approved

> **Bối cảnh:** Backend của cả 4 story nghiệp vụ (xóa mềm, xem/phục hồi
> thùng rác cá nhân, thống kê cho R3, `trashBytes` ở `GET
> /warehouse/capacity`) đã implement + test xong (2026-09-09), nhưng `web/`
> chưa có route/component nào cho thùng rác — kể cả nút "Xóa" trên
> `/documents`. Theo đúng khuôn mẫu đã dùng cho `digitization-review-05-
> frontend.md` và `search-fulltext-05-frontend.md`, giao diện được gộp
> thành **1 slug riêng** (`-03-frontend`) thay vì làm rải rác theo từng
> story backend.
>
> File này **không lặp lại** các Acceptance Criteria nghiệp vụ đã chốt ở 2
> file nguồn (quyền xóa/phục hồi, N nhóm bị ảnh hưởng, 8 case xung đột, quy
> tắc audit, retention 365 ngày...) — những AC đó đã implement đúng ở
> backend và không đổi. File này chỉ định nghĩa **lớp AC riêng của giao
> diện**: hiển thị, tương tác, trạng thái tải, dialog xung đột — phần mà
> backend không quyết định thay được.

## Bản đồ màn hình

| Route | Vai trò | Nguồn AC nghiệp vụ |
|---|---|---|
| `/trash` (mới) | R1 (CAN_BO) | Story 2 (`-01`) — xem/phục hồi thùng rác cá nhân |
| Dialog xác nhận xóa, gắn vào `/documents` và `/documents/[id]` (đã có) | R1 (CAN_BO) | Story 1 (`-01`) — xóa mềm |
| Dialog phục hồi (xung đột), mở từ `/trash` | R1 (CAN_BO) | Story 6 (`-02`) — 8 case xung đột |
| Tab mới trong `AdminTabs`, ví dụ `/admin/trash-stats` (mới) | R3 (QUAN_TRI) | Story 3 (`-01`) — thống kê thùng rác |
| Badge trong `/admin/folders` (đã có) | R3 | Story 4 (`-01`) — `trashBytes` |

`/trash` là route hoàn toàn mới, thêm vào `web/src/lib/features.ts` với
`roles: [Role.CAN_BO]` (đúng Quyết định 6 SA: chỉ R1 xóa/xem/phục hồi; R2
không có quyền xóa nên không cần thấy thùng rác; R3 chỉ xem thống kê, không
xem danh sách). Tab thống kê cho R3 là tab mới trong `AdminTabs` hiện có,
theo đúng khuôn `/admin/document-ownership`.

---

## Story 1: Màn hình `/trash` — danh sách thùng rác cá nhân (2 chế độ xem)

**As a** R1
**I want** một màn hình riêng liệt kê tài liệu tôi đã xóa và tài liệu tôi
nạp bị người khác xóa
**So that** tôi dễ dàng tìm lại và phục hồi tài liệu cần dùng, phân biệt rõ
2 trường hợp trên

### Acceptance Criteria

- [ ] AC-1: Route `/trash` có 2 **tab**: "Tôi đã xóa" (mặc định, `view=own`)
      và "Tài liệu tôi nạp bị người khác xóa" (`view=uploaded`) — gọi cùng 1
      API `GET /trash?view=own|uploaded` (đã chốt ở "Câu hỏi mở cho DEV" của
      `-01`), đổi tab → gọi lại API, không cần bấm "Áp dụng" riêng.
- [ ] AC-2: Bảng liệt kê các cột: tên tài liệu (`originalName`), ngày xóa
      (`deletedAt`), hạn xóa cứng (`purgeAfter`, định dạng tương đối, ví dụ
      "còn 12 ngày"; **đỏ/cảnh báo** khi còn ≤ 30 ngày), dung lượng (`size`,
      định dạng người-đọc-được). Tab "own" ẩn cột người xóa (chính là mình);
      tab "uploaded" thêm cột "Người xóa" (`deletedByUsername`).
- [ ] AC-3: Phân trang chuẩn (theo `limit`/`offset`, giống pattern
      `admin/audit`/`documents`).
- [ ] AC-4: Danh sách rỗng → thông báo phù hợp theo tab đang chọn (ví dụ
      "Thùng rác của bạn đang trống." / "Không có tài liệu nào bạn nạp bị
      người khác xóa."), không phải bảng trống im lặng.
- [ ] AC-5: Mỗi dòng có nút "Phục hồi" (mở luồng phục hồi — xem Story 3);
      chọn nhiều dòng (checkbox) → nút "Phục hồi đã chọn" ở trên bảng, gọi
      `POST /trash/restore-batch`.
- [ ] AC-6: Click một dòng (ngoài vùng nút) → mở panel/dialog chi tiết
      (`GET /trash/{id}`) hiển thị đầy đủ thông tin, bao gồm gợi ý thư mục
      còn tồn tại nếu có (`suggestedFolderId`) — không bắt buộc phải mở chi
      tiết mới phục hồi được (AC-5 đã đủ), đây là lối xem thêm.
- [ ] AC-7: Route `/trash` được đăng ký trong `features.ts`
      (`roles: [Role.CAN_BO]`), không hiển thị/điều hướng được với R2/R3
      (đã có route guard chung của `web/`, không phải làm mới).

## Ngoài phạm vi (Out of scope) — Story 1

- Tìm kiếm/lọc theo tên tài liệu trong thùng rác — backend chưa có tham số
  lọc theo tên ở `GET /trash` (chỉ `view`/`limit`/`offset`), không tự thêm.
- Sắp xếp cột tùy chỉnh — mặc định `deletedAt DESC` theo backend, đủ dùng.

---

## Story 2: Dialog xác nhận xóa (gắn vào `/documents`, `/documents/[id]`)

**As a** R1
**I want** một dialog xác nhận rõ ràng trước khi xóa, cho biết có bao nhiêu
nhóm sẽ mất quyền xem tài liệu
**So that** tôi không xóa nhầm tài liệu đang được chia sẻ mà không biết hậu
quả

### Acceptance Criteria

- [ ] AC-1: Nút "Xóa" xuất hiện trên `/documents` (mỗi dòng + chọn nhiều →
      "Xóa đã chọn") và trên `/documents/[id]` (nút chi tiết) — chỉ hiện với
      R1 (CAN_BO); R2 (LANH_DAO, hiện cũng thấy `/documents` theo
      `features.ts`) **không** thấy nút này dù cùng xem được trang.
- [ ] AC-2: Bấm "Xóa" → gọi `POST /documents/trash-preview` với danh sách id
      đã chọn trước, lấy `activeGroupShareCount` từng tài liệu, rồi hiện
      dialog xác nhận dùng `useConfirm()`/`ConfirmDialog` (pattern đã có ở
      `documents/[id]/page.tsx`).
- [ ] AC-3: Dialog hiển thị tổng số nhóm bị ảnh hưởng — cộng
      `activeGroupShareCount` của tất cả tài liệu đã chọn. **N > 0** → dòng
      cảnh báo rõ ràng dạng "N nhóm sẽ mất quyền xem (các) tài liệu này".
      **N = 0** → **ẩn hẳn dòng cảnh báo về nhóm** (đề xuất BA, xem "Câu hỏi
      mở cho DEV" — đây là câu hỏi mở đã có sẵn từ `-01`, BA giữ nguyên đề
      xuất ẩn để tránh dialog rườm rà khi không có gì đáng cảnh báo, nhưng
      đánh dấu là đề xuất, DEV/PM xác nhận trước khi code).
- [ ] AC-4: Xóa nhiều tài liệu cùng lúc: dialog liệt kê tên từng tài liệu
      (hoặc số lượng nếu quá nhiều, ví dụ >10 thì rút gọn "và N tài liệu
      khác") kèm tổng số nhóm bị ảnh hưởng chung (không tách riêng từng tài
      liệu, để dialog gọn — theo đúng tinh thần AC-1 Story 1 `-01`: cảnh báo
      ở mức lượt xóa, không phải từng file).
- [ ] AC-5: Xác nhận → gọi `POST /documents/trash` (đơn hoặc mảng id) →
      thành công: tài liệu biến mất khỏi danh sách `/documents` ngay lập
      tức (không cần tải lại trang), thông báo ngắn (toast/banner) "Đã
      chuyển N tài liệu vào thùng rác", có liên kết nhanh tới `/trash`.
- [ ] AC-6: Xóa thất bại (403 — ngoài phạm vi FR-08) → thông báo lỗi rõ ràng,
      không đóng dialog im lặng; nếu là thao tác hàng loạt mà 1 tài liệu lỗi
      → cả lượt bị từ chối (đúng AC-6 Story 1 `-01`: tất cả-hoặc-không), báo
      rõ tài liệu nào gây lỗi.
- [ ] AC-7: `POST /documents/trash-preview` lỗi/chậm — dialog vẫn mở được
      với thông báo "Không tải được số nhóm bị ảnh hưởng", cho phép người
      dùng vẫn xác nhận xóa nếu muốn (không chặn thao tác xóa chỉ vì preview
      lỗi — preview chỉ là thông tin hỗ trợ quyết định, không phải điều
      kiện).

---

## Story 3: Luồng phục hồi — xử lý đủ 8 case xung đột (`-02` Quyết định 7)

**As a** R1 đang phục hồi tài liệu từ `/trash`
**I want** giao diện hướng dẫn rõ ràng cho từng loại xung đột khi phục hồi
**So that** tôi luôn biết chính xác cần làm gì tiếp theo, không gặp lỗi mơ
hồ hay mất dữ liệu

### Acceptance Criteria

Ánh xạ trực tiếp 8 case đã chốt ở Quyết định 7 SA / Story 6 `-02`:

- [ ] AC-1 — **Thư mục cha đã xóa (409 `FOLDER_MISSING`):** dialog phục hồi
      hiện chọn thư mục đích (cây thư mục kho), **tự động gợi ý** thư mục
      gần nhất còn tồn tại nếu API trả `suggestedFolderId` (đã chọn sẵn
      trong cây, người dùng có thể đổi); nếu `suggestedFolderId = null`
      (toàn bộ cây tổ tiên đã bị xóa) → không chọn sẵn, bắt buộc người dùng
      tự chọn thư mục bất kỳ, không cho bấm "Phục hồi" tới khi đã chọn.
- [ ] AC-2 — **Trùng tên tại đích (409 `NAME_CONFLICT`):** dialog hiện 3 lựa
      chọn rõ ràng (radio/button): (a) "Đổi tên tự động" — hiện trước tên đề
      xuất từ `suggestedName`, gửi lại `onNameConflict=AUTO_RENAME`; (b)
      "Chọn thư mục khác" — quay lại bước chọn thư mục ở AC-1; (c) "Hủy" —
      đóng dialog, không phục hồi. **Không** có lựa chọn "ghi đè" (đúng chốt
      backend: không bao giờ tự động ghi đè).
- [ ] AC-3 — **Kho đầy (≥95%):** không có xử lý UI riêng — phục hồi diễn ra
      bình thường, không hiện cảnh báo/chặn nào liên quan tới dung lượng khi
      phục hồi (đúng AC-3 Story 6 `-02`: dung lượng rác đã tính vào kho từ
      trước).
- [ ] AC-4 — **Hạn mức cá nhân:** không áp dụng, không có UI liên quan (đúng
      AC-4 Story 6 `-02`).
- [ ] AC-5 — **Người nạp/người xóa đã bị vô hiệu hóa:** không có xử lý UI
      riêng — phục hồi diễn ra như bình thường, không hiện cảnh báo về trạng
      thái tài khoản (đúng AC-5 Story 6 `-02`: danh tính chỉ mang tính lịch
      sử, không chặn).
- [ ] AC-6 — **Nhóm được chia sẻ đã bị xóa:** phục hồi thành công (200) kèm
      `groupsLostEffect` không rỗng → **sau khi phục hồi thành công**, hiện
      thông báo dạng cảnh báo (không phải lỗi) "Phục hồi thành công. N nhóm
      từng chia sẻ tài liệu này đã bị xóa nên không còn hiệu lực chia sẻ" —
      không chặn, không yêu cầu xác nhận thêm.
- [ ] AC-7 — **Đã quá hạn/đã xóa cứng (410 `GONE`):** dòng tương ứng trong
      `/trash` (nếu còn hiển thị do dữ liệu client cũ) hoặc phản hồi khi bấm
      "Phục hồi" → thông báo rõ "Tài liệu này đã bị xóa vĩnh viễn, không thể
      phục hồi từ thùng rác. Liên hệ quản trị viên nếu cần phục hồi từ bản
      sao lưu." — không có nút/hành động nào tiếp theo trong dialog này (đây
      là điểm dừng, phù hợp vì `backup-restore` ngoài phạm vi).
- [ ] AC-8 — **Đang `purging` (409 `PURGING`):** thông báo "Tài liệu đang
      trong quá trình xóa vĩnh viễn, vui lòng thử lại sau vài phút." —
      không tự động thử lại (retry) ở UI, người dùng tự bấm lại.
- [ ] AC-9 — **Phục hồi hàng loạt** (`POST /trash/restore-batch`): kết quả
      trả về theo từng item (`RESTORED`/`CONFLICT`/`ERROR`) → hiện báo cáo
      tổng hợp sau khi chạy xong: số item thành công, danh sách item cần xử
      lý xung đột (mỗi item hiện `code` tương ứng, cho phép người dùng bấm
      "Xử lý" mở lại dialog xung đột **cho từng item đó** — không cố tự động
      xử lý hàng loạt cho case trùng tên/thiếu thư mục vì mỗi item có thể
      cần thư mục đích khác nhau); item lỗi khác hiện thông báo lỗi tương
      ứng. Danh sách `/trash` tự cập nhật, loại bỏ các item đã `RESTORED`.
- [ ] AC-10: Toàn bộ dialog xung đột dùng chung 1 component (không tách 8
      dialog riêng biệt) — nhận `code` từ response 409/410 để quyết định
      hiển thị bước nào, giữ luồng nhất quán và dễ bảo trì.

---

## Story 4: Thống kê thùng rác cho R3

**As a** R3
**I want** một màn hình thống kê thùng rác toàn hệ thống
**So that** tôi theo dõi dung lượng thùng rác chiếm dụng và số item không
còn ai phục hồi được, mà không xem được nội dung/tên tệp

### Acceptance Criteria

- [ ] AC-1: Tab mới trong `AdminTabs`, ví dụ "Thùng rác" (`/admin/trash-stats`,
      `roles: [Role.QUAN_TRI]`, `hidden: true` trong `features.ts` — vào qua
      tab, giống `/admin/document-ownership`), gọi `GET /trash/stats`.
- [ ] AC-2: Hiển thị: tổng số item, tổng dung lượng, phân bố theo khoảng hạn
      xóa cứng (ví dụ nhóm "≤ 30 ngày", "31-90 ngày", "> 90 ngày" — cụ thể
      hóa từ mô tả "phân bố theo khoảng hạn" của SA, số khoảng và ranh giới
      do FE tự quyết định hợp lý nếu response trả dạng buckets có sẵn; nếu
      response chỉ trả danh sách item thô theo hạn thì FE tự nhóm — **cần
      xác nhận hình dạng response thực tế của `GET /trash/stats`** khi làm
      DEV-spec, xem "Câu hỏi mở cho DEV").
- [ ] AC-3: Hiển thị riêng số liệu "item không còn ai phục hồi được"
      (`unrecoverableAccounts`, Story 3 AC-4 `-01`) — số lượng + hạn xóa cứng
      gần nhất trong nhóm đó, có chú thích ngắn giải thích ý nghĩa (cả người
      xóa lẫn người nạp gốc đều đã bị khóa/vô hiệu hóa).
- [ ] AC-4: Màn hình này **tuyệt đối không** hiện tên tệp, đường dẫn, hay
      danh tính người xóa/người nạp của bất kỳ item nào (đúng AC-1 Story 3
      `-01` — response backend vốn đã không có các field này, UI không tự ý
      gọi thêm API khác để bù đắp).
- [ ] AC-5: Không có nút/liên kết nào dẫn tới chi tiết từng item (đúng AC-2
      Story 3 `-01`: `GET /trash/{id}` trả 403 cho R3).

---

## Story 5: Hiển thị dung lượng thùng rác (`trashBytes`) ở màn hình kho

**As a** R3 xem `/admin/folders`
**I want** thấy rõ dung lượng thùng rác tách biệt khỏi dung lượng tài liệu
đang hoạt động
**So that** tôi hiểu vì sao kho gần đầy dù nhiều tài liệu đã được "xóa"

> Phạm vi ban đầu có cả R1 ở `/documents` (banner dung lượng kho) — đã bỏ
> khỏi slug này theo xác nhận của người dùng, xem AC-3.

### Acceptance Criteria

- [ ] AC-1: `/admin/folders` (đã có khối hiển thị `usedBytes`/
      `maxCapacityBytes`/`usedPercent`, xem `capacity.status`) — thêm 1 dòng
      "Trong đó, thùng rác: X" lấy từ field `trashBytes` (đã có sẵn ở `GET
      /warehouse/capacity`), đặt ngay dưới thanh tiến trình hiện có, không
      đổi cách tính `usedPercent`/ngưỡng cảnh báo hiện tại.
- [ ] AC-2: Thông điệp cảnh báo 85%/chặn 95% (đã có ở `/admin/folders`) khi
      `trashBytes` chiếm tỷ trọng đáng kể (ví dụ ≥ 20% `usedBytes` — ngưỡng
      cụ thể do FE/DEV tự quyết định hợp lý, không phải quyết định kiến
      trúc) → bổ sung gợi ý "phần lớn dung lượng đang dùng là thùng rác, sẽ
      tự động giải phóng theo lịch xóa cứng" — không gợi ý có thể "xóa cứng
      ngay" (MVP không có thao tác này, đúng AC-2 Story 4 `-01`).
- [x] AC-3 — **Đã xác nhận với người dùng (không đưa vào slug này):** banner
      dung lượng kho ở `/documents` cho R1 bị loại khỏi phạm vi
      `delete-restore-trash-03-frontend` — để `warehouse-structure` (FR-13)
      tự quyết định UI dung lượng kho cho `/documents` sau này, tránh 2 slug
      cùng đụng vào 1 trang. Slug này chỉ làm AC-1/AC-2 (badge ở
      `/admin/folders`, đã có route).

## Ngoài phạm vi (Out of scope)

- Mọi Acceptance Criteria nghiệp vụ đã chốt ở `-01`/`-02` (ai được xóa/phục
  hồi, quy tắc audit, retention 365 ngày, máy trạng thái `purging`/`purged`,
  thứ tự ưu tiên lỗi khi phục hồi) — không lặp lại, không đổi.
- Xóa cứng thủ công (không có ở MVP, không có nút nào trên UI).
- Phục hồi từ bản sao lưu (`backup-restore`, R3) — chưa có UI/API tương ứng.
- Thông báo in-app chủ động khi tài liệu chia sẻ bị xóa — chỉ có ở audit
  log (`/admin/audit`), không làm banner/notification riêng.
- Thùng rác dùng chung theo nhóm — MVP chỉ có `/trash` cá nhân.
- Vẽ biểu đồ trực quan hóa cho thống kê R3 (Story 4) — bảng số liệu đơn
  giản là đủ ở MVP, không bắt buộc chart.

## Phụ thuộc / Ghi chú kiến trúc liên quan

- Không có SA riêng cho phần frontend — quy ước kỹ thuật (Next.js App
  Router, Client Component, Tailwind thuần, `apiJson`/`apiFetch`,
  `AppShell`/`AdminTabs`/`features.ts`, `useConfirm()`/`ConfirmDialog`) đã
  là chuẩn chung của `web/`, áp dụng nguyên trạng, không phải quyết định
  kiến trúc mới.
- Quyết định 6 (SA, `delete-restore-trash.md`): chỉ R1 xóa/xem/phục hồi;
  R3 chỉ thống kê, không xem nội dung — nguồn trực tiếp cho việc `/trash`
  chỉ gắn `roles: [Role.CAN_BO]` và tab thống kê R3 tách biệt hoàn toàn,
  không dùng chung component danh sách.
- Quyết định 7 (SA): bảng 8 case xung đột khi phục hồi — nguồn trực tiếp
  cho toàn bộ AC của Story 3 ở đây.
- API contract dùng trong story này lấy từ `docs/03-dev/specs/
  delete-restore-trash-01-xoa-mem-thung-rac.md` §3 (bảng endpoint,
  `GET /trash`, `POST /trash/{id}/restore`, `POST /trash/restore-batch`,
  `GET /trash/{id}`, `GET /warehouse/capacity` mở rộng `trashBytes`) và
  `docs/03-dev/specs/delete-restore-trash-02-xoa-cung-xung-dot.md` §3
  (bảng mã lỗi 409/410 theo từng case xung đột) — DEV-spec của story này chỉ
  cần dẫn chiếu lại, không cần định nghĩa API mới trừ khi hình dạng response
  `GET /trash/stats` chưa đủ cho AC-2 Story 4 (xem "Câu hỏi mở cho DEV").

## Câu hỏi mở cho DEV

- [ ] Định dạng dialog xác nhận xóa khi N = 0 nhóm bị ảnh hưởng (kế thừa từ
      `-01`, chưa được trả lời): BA đề xuất **ẩn hẳn** dòng cảnh báo về
      nhóm khi N = 0 (xem AC-3 Story 2 ở trên) — cần DEV chốt khi làm UI,
      hoặc xác nhận đổi sang phương án hiển thị "không chia sẻ nhóm nào"
      nếu muốn nhất quán hơn về mặt UX (luôn có 1 dòng, chỉ đổi nội dung).
- [ ] Hình dạng response thực tế của `GET /trash/stats` (buckets có sẵn hay
      danh sách thô để FE tự nhóm theo khoảng hạn xóa cứng) — ảnh hưởng
      trực tiếp AC-2 Story 4; cần đọc lại response thật hoặc test IT tương
      ứng khi viết DEV-spec.
- [x] ~~Story 5 – AC-3 (banner dung lượng kho ở `/documents` cho R1)~~ — **đã
      trả lời**: loại khỏi phạm vi slug này, để `warehouse-structure` (FR-13)
      xử lý riêng nếu cần (xem AC-3 Story 5 ở trên).
- [ ] Ngưỡng "chiếm tỷ trọng đáng kể" ở AC-2 Story 5 (ví dụ ≥ 20%) là số BA
      tạm đề xuất cho có tiêu chí cụ thể — không phải số đã chốt với khách
      hàng, DEV có thể điều chỉnh tự do khi implement mà không cần quay lại
      hỏi BA.
