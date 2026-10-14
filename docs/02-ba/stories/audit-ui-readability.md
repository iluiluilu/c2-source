# User Story: audit-ui-readability (màn hình Nhật ký hoạt động dễ đọc hơn)

> File: `docs/02-ba/stories/audit-ui-readability.md` — Owner: BA
> Nguồn kiến trúc: `docs/01-sa/architecture/audit-logging.md` (không phát sinh
> quyết định kiến trúc mới — thay đổi chỉ ở tầng hiển thị web)
> Bổ sung trực tiếp lên: `docs/02-ba/stories/audit-logging.md` (Story 2/3 —
> trang `/admin/audit`, `web/src/app/admin/audit/page.tsx`)
> Status: Draft

> **Bối cảnh:** Trang `/admin/audit` hiện hiển thị nguyên giá trị kỹ thuật
> của `audit_log`: bộ lọc là ô nhập tự do (người dùng phải tự biết gõ
> `document.create`, `warehouse_folder`), cột "Tài nguyên" ghép
> `resource_type / resource_id` (UUID dài), cột "Kết quả" hiện
> `failure (403)`. R2/R3 không phải kỹ thuật viên nên khó đọc và khó lọc.
> File này chỉ đổi **cách hiển thị và cách nhập bộ lọc**; không đổi dữ liệu
> được ghi, API, phân quyền hay các AC đã chốt ở `audit-logging.md` (trừ
> điểm ghi ở AC-1 dưới đây).

## User Story

**As a** Quản trị hệ thống (R3) / Lãnh đạo (R2)
**I want** chọn bộ lọc hành động và loại tài nguyên từ danh sách có sẵn, và
đọc được bảng nhật ký bằng nhãn tiếng Việt thay vì mã kỹ thuật
**So that** tôi tra cứu, truy vết thao tác nhanh mà không cần biết mã nội bộ
của hệ thống

## Acceptance Criteria

```
Given tôi là R2/R3 đang ở trang /admin/audit
When tôi mở bộ lọc, chọn Hành động và/hoặc Loại tài nguyên từ dropdown rồi bấm Tìm kiếm
Then danh sách chỉ gồm bản ghi khớp lựa chọn, và mỗi dòng hiển thị hành động,
     loại tài nguyên, kết quả bằng nhãn tiếng Việt dễ hiểu
```

**Bộ lọc**

- [ ] AC-1: Bỏ ô lọc "ID tài nguyên" khỏi giao diện. (Điều chỉnh AC-1 Story 2
  `audit-logging.md` ở tầng UI: lọc `resource_id` không còn trên màn hình;
  tham số `resourceId` của API vẫn giữ nguyên để dùng về sau.)
- [ ] AC-2: Ô "Hành động" là dropdown, các lựa chọn gom nhóm theo phân hệ
  (Tài liệu, Kho & thư mục, Tải lên, Kho cá nhân, Số hoá, Nhãn & ghi chú,
  Nhóm, Tài khoản & phân quyền, Tìm kiếm, Nhật ký & hệ thống, Bảo mật & lỗi),
  hiển thị nhãn tiếng Việt; lựa chọn đầu tiên "Tất cả hành động" = không lọc.
- [ ] AC-3: Ô "Loại tài nguyên" là dropdown nhãn tiếng Việt kèm icon; lựa chọn
  đầu tiên "Tất cả loại" = không lọc.
- [ ] AC-4: Danh sách lựa chọn của AC-2/AC-3 là **danh mục cấu hình tĩnh trong
  web project** (không gọi API lấy danh sách). Danh mục ban đầu phủ đủ mọi
  giá trị `action`/`resource_type` đang có trên stg (70 cặp, ngày
  2026-09-28) và mọi giá trị đang được ghi cố định trong code backend.
- [ ] AC-5: Giá trị gửi lên API vẫn là mã gốc (`document.create`,
  `document`) — kết quả lọc giống hệt khi gõ tay mã đó trước đây.
- [ ] AC-6: "Xóa bộ lọc" đưa cả hai dropdown về "Tất cả".

**Bảng dữ liệu**

- [ ] AC-7: Thứ tự cột: Thời điểm | Người thực hiện | Hành động | Loại tài
  nguyên | ID tài nguyên | Kết quả | IP.
- [ ] AC-8: Cột "Hành động" hiện nhãn tiếng Việt dạng text thường (không
  badge/tô màu). Rê chuột hiện mã gốc.
- [ ] AC-9: Cột "Loại tài nguyên" chỉ hiện nhãn tiếng Việt + icon của
  `resource_type`, **không** ghép ID. Không có giá trị → `—`.
- [ ] AC-10: Cột "ID tài nguyên" là cột riêng: ID dạng UUID hiện rút gọn 8 ký
  tự đầu + `…` (font monospace), rê chuột hiện đầy đủ, có nút copy ID đầy
  đủ; ID ngắn (không phải UUID) hiện nguyên; không có → `—`.
- [ ] AC-11: Cột "Kết quả" là text tiếng Việt (không badge/tô màu) theo bảng
  sau, rê chuột hiện mã HTTP (nếu có):

  | outcome | statusCode | Nhãn |
  |---|---|---|
  | success | bất kỳ | Thành công |
  | failure | 401 | Chưa đăng nhập |
  | failure | 403 | Không có quyền |
  | failure | 404 | Không tìm thấy |
  | failure | 409 | Xung đột dữ liệu |
  | failure | 4xx khác | Yêu cầu không hợp lệ |
  | failure | 5xx | Lỗi hệ thống |
  | failure | null | Thất bại |
  | skipped | bất kỳ | Bỏ qua |

- [ ] AC-12: Giá trị `action`/`resource_type`/`outcome` **không có trong
  danh mục** vẫn hiển thị (bằng mã gốc) — trang không lỗi,
  không để trống. Riêng action dạng `<x>.request_rejected` chưa có trong
  danh mục hiện "Yêu cầu bị từ chối (<x>)".
- [ ] AC-13: Dòng mở rộng (bấm vào một bản ghi) giữ Method/Path và trạng thái
  niêm phong, thêm "Mã hành động" (mã gốc); `metadata` hiện dạng danh sách
  "Nhãn: giá trị" (nhãn tiếng Việt cho khoá phổ biến, khoá lạ giữ nguyên
  tên), có nút bật/tắt "Xem JSON gốc".

## Ngoài phạm vi (Out of scope)

- Hiển thị **tên** tài nguyên (tên tài liệu, tên nhóm…) — cần backend tra
  cứu theo ID, để giai đoạn sau nếu có nhu cầu.
- API lấy danh sách giá trị động (`/audit/facets`) — đã chốt dùng danh mục
  tĩnh ở web.
- Lọc theo Kết quả, lọc bản ghi không có loại tài nguyên (`IS NULL`), lọc
  theo cả nhóm hành động (`document.*`).
- Chuẩn hoá `resource_type` suy ra từ URL trong
  `ApiExceptionHandler.domainFromPath` (`ingest`, `personal`, `search`…).
- Đổi tên người thực hiện (username → họ tên).
- Mọi thay đổi backend, dữ liệu `audit_log`, hash chain, phân quyền R2/R3,
  khối "Xác minh toàn vẹn chuỗi", phân trang.

## Phụ thuộc / Ghi chú kiến trúc liên quan

- `audit_log` chỉ-thêm (append-only, NFR-09): nhãn chỉ áp ở tầng hiển thị,
  không sửa dữ liệu đã ghi.
- Một phần `resource_type`/`action` là giá trị **suy ra từ tiền tố URL**
  (`<domain>.request_rejected`) — cố định khi bộ route cố định, nên danh mục
  tĩnh chấp nhận được; route mới cần bổ sung danh mục (xem Câu hỏi mở).
- AC-4 Story 2 (không hiển thị nội dung nghiệp vụ trên màn hình log) vẫn giữ:
  chỉ hiển thị các trường đã có trong `audit_log`.

## Câu hỏi mở cho DEV

- [ ] Cơ chế nhắc cập nhật danh mục khi backend thêm mã audit mới (ghi chú
  checklist hay script đối chiếu `SELECT DISTINCT` trên stg)?
