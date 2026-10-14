# Rà soát UI/UX trên stg — 2026-10-13

> Status: Draft

- **Phạm vi:** web stg (`web.green0tech.site`), 3 vai trò: `quantri1` (R3), `canbo1` (R1), `lanhdao1` (R2).
- **Cách làm:** thao tác thật trên Chrome ở 1512×743. Dữ liệu test có tiền tố `uxtest-`: thư mục `uxtest-folder`, 2 tệp `.txt`, 1 ghi chú, nhóm `uxtest-nhom` (canbo1 là chủ nhóm, lanhdao1 là thành viên, đã chia sẻ 1 tệp vào nhóm).
- **Chưa kiểm tra:** màn hình hẹp/mobile (cửa sổ không đổi kích thước được), luồng duyệt OCR/STT (hàng chờ trống), xuất tệp báo cáo, các tab admin chưa làm (API, Sao lưu, Hạn mức).

Mức ưu tiên: **P0** = lỗi chức năng hoặc gây sai lệch dữ liệu; **P1** = cản trở thao tác hằng ngày; **P2** = trau chuốt.

## 1. Lỗi cần sửa trước (P0)

| # | Vấn đề | Ở đâu | Đề xuất |
|---|---|---|---|
| 1 | Chuông thông báo vẫn hiện với R3, trong khi R3 không có quyền `/notifications`. Mỗi 60s nhận 403 → nhật ký đầy dòng "Truy cập bị từ chối" (13 dòng trong ~10 phút test), dropdown lại báo "Không có thông báo nào". | Header, mọi trang | Ẩn chuông khi không có quyền (gating theo `/me/permissions`), không gọi API. |
| 2 | Cột IP trong nhật ký luôn là `172.18.0.12` (IP proxy), không phải IP người dùng. | Nhật ký hoạt động | Đọc `X-Forwarded-For` / `X-Real-IP` từ nginx (cấu hình trusted proxy ở backend). |
| 3 | Modal xem ảnh trang: ảnh cao hơn màn hình đẩy tiêu đề và nút "Đóng ✕" ra ngoài khung nhìn; Esc không đóng được. | Tìm kiếm / Tra cứu ngữ nghĩa → "Xem trên ảnh trang" | Giới hạn modal `max-height: 90vh`, ảnh cuộn bên trong, header cố định; đóng bằng Esc và bấm nền. |
| 4 | Sidebar sáng **2 mục** cùng lúc ("Quản trị hệ thống" và "Nhật ký hoạt động") khi ở `/admin/audit`. | Sidebar (R3) | So khớp route chính xác thay vì theo tiền tố `/admin`. |
| 5 | Nội dung bị cắt mép phải: cột "Gỡ" (Định dạng cho phép), nút "Gỡ" thành viên (chi tiết nhóm). | Admin, Nhóm | Sửa layout bảng (`table-layout`, cuộn ngang trong card). |
| 6 | Không có thông báo khi tài liệu được chia sẻ vào nhóm, dù đã có loại `document.shared_to_group`. | Chuông (thành viên nhóm) | Kiểm tra lại nơi phát sự kiện khi chia sẻ. |

## 2. Vấn đề chung toàn hệ thống (P1)

1. **Mã kỹ thuật lộ ra giao diện:** FR-xx/NFR-xx/"Story 4"/"Quyết định 1 SA" trong tiêu đề và mô tả; vai trò R1/R2/R3, `LANH-DAO`, `TEXT_DOC`; "PostgreSQL", "OpenSearch", "outbox", "VLAN 108". → Bỏ khỏi UI, chỉ để trong tài liệu. Vai trò hiển thị "Cán bộ / Lãnh đạo / Quản trị".
2. **Thông báo kết quả không nhất quán:** khi thì ở đáy form (ngoài tầm mắt), khi thì banner chèn đầu trang đẩy nội dung xuống, khi thì không có gì (tạo nhóm, phục hồi). → Dùng một hệ thống **toast** chung, góc màn hình, không đẩy layout.
3. **Layout shift:** thanh thao tác hàng loạt ở Kho tài liệu và banner thành công ở Nhóm chèn lên đầu, làm bảng dịch xuống ~74px. Hệ quả: dễ bấm nhầm (đã gặp trong lúc test). → Dành sẵn chỗ hoặc dùng thanh nổi (sticky/floating).
4. **3 kiểu cây thư mục khác nhau** (Nạp dữ liệu, Kho tài liệu, Quản trị) và **2 kiểu tải lên** (Nạp dữ liệu và Lưu trữ cá nhân). → Gom thành một component cây (có icon, thu gọn/mở rộng, highlight mục chọn, breadcrumb) và một component tải lên.
5. **Tiêu đề không khớp:** header ghi "Tìm kiếm & tra cứu" trong khi menu đang ở "Tra cứu ngữ nghĩa"; ở admin, header và H1 đổi qua lại giữa "Quản trị hệ thống", "Phân quyền (RBAC)", "Định dạng tệp cho phép". → Header lấy theo mục menu, H1 theo tab.
6. **Độ rộng trang không đồng đều:** Nhóm, chi tiết tài liệu, Thống kê thùng rác hẹp hơn các trang khác, nên thanh tab admin bị dàn lại. → Dùng chung một container.
7. **Định dạng ngày:** báo cáo dùng `2026-10-01`, còn lại `dd/mm/yyyy`. → Thống nhất `dd/mm/yyyy`.
8. **Dashboard** chỉ có thẻ "Mở chức năng →" và chỉ số "Chức năng khả dụng: N" (không mang lại thông tin gì). → Theo vai trò:
   - Cán bộ: việc cần làm (chờ duyệt số hoá, sắp hết hạn), tài liệu mới được chia sẻ, tài liệu gần đây, hạn mức cá nhân.
   - Lãnh đạo: tài liệu nạp mới trong tuần, tiến độ số hoá, quá hạn duyệt, lối tắt sang Báo cáo.
   - Quản trị: tài khoản bị khoá, dung lượng kho, job lỗi/DLQ, cảnh báo hệ thống.
9. **Trang đăng nhập:** phải bấm thêm nút "Đăng nhập" mới tới form Keycloak; dòng "VLAN 108 · NFR-08" vô nghĩa với người dùng. → Chuyển thẳng tới Keycloak (hoặc giữ landing nhưng bỏ dòng kỹ thuật). Ghi nhận tốt: đăng xuất xong thì không bị tự đăng nhập lại.

## 3. Theo màn hình

### 3.1 Cán bộ — luồng chính

| Màn hình | Vấn đề | Đề xuất | Mức |
|---|---|---|---|
| Nạp dữ liệu | Xong vẫn hiện "Đã chọn 2 tệp", dễ bấm tải trùng; kết quả không có liên kết mở tài liệu; panel kết quả hiện "Đang chờ…" ngay cả khi chưa bấm tải; không bỏ được từng tệp đã chọn. | Reset sau khi xong; thêm nút "Mở thư mục"/"Xem tài liệu"; danh sách tệp chờ có nút ✕ từng tệp. | P1 |
| Kho tài liệu | Không có ô tìm theo tên, không sắp xếp cột, không phân trang hiển thị, không có "chọn tất cả"; bấm lại thư mục đang chọn thì bỏ lọc mà không có dấu hiệu; ở 1512px các cột xuống 2 dòng; thiếu cột trạng thái số hoá. | Thêm ô lọc tên + sắp xếp + checkbox chọn tất cả; breadcrumb và nút "Nạp vào thư mục này"; gọn cột (gộp kích thước/thời gian, cho phép ẩn cột). | P1 |
| Chi tiết tài liệu | **Không có nút Tải xuống** (chỉ có Xóa); không xem trước nội dung; không biết tài liệu nằm thư mục nào; SHA-256 chiếm chỗ nổi bật; không liên kết sang kết quả số hoá. | Hàng nút chính: Tải xuống, Chia sẻ, Xóa (Xóa để cuối, tách màu); thêm vùng xem trước (PDF/ảnh/văn bản); breadcrumb thư mục; thu SHA-256 vào "Thông tin kỹ thuật". | P1 |
| Xóa / Thùng rác | Hộp xác nhận không nói tài liệu "vào thùng rác, khôi phục được trong 365 ngày"; Phục hồi không có thông báo, dòng biến mất và dòng dưới trượt lên đúng chỗ con trỏ (dễ phục hồi nhầm); không biết thư mục gốc; không có tìm kiếm. | Thêm câu giải thích vào hộp xác nhận; toast "Đã phục hồi về <thư mục>" kèm "Hoàn tác"; thêm cột thư mục gốc và ô tìm. | P1 |
| Tìm kiếm toàn văn | Từ khoá **không được tô sáng** trong đoạn trích; kết quả thiếu thư mục/ngày/người nạp; tệp vừa nạp chưa số hoá thì không tìm được và không có giải thích; khi không có kết quả thì không gợi ý gì. | Bật highlight; thêm metadata vào thẻ kết quả; ghi chú "Tài liệu mới nạp sẽ tìm được sau khi số hoá xong"; gợi ý thử tra cứu ngữ nghĩa hoặc nới bộ lọc. | P1 |
| Tra cứu ngữ nghĩa | Cùng một tài liệu lặp lại 3 thẻ; tách thành 2 trang tìm kiếm với bộ lọc trùng nhau. | Gộp đoạn theo tài liệu ("3 đoạn khớp"); cân nhắc gộp 2 trang thành 1 ô tìm có công tắc "Từ khoá / Ngữ nghĩa". | P2 |
| Nhóm chia sẻ | Danh sách thiếu số thành viên/số tài liệu/vai trò của tôi; tạo nhóm xong không có thông báo và không mở nhóm luôn; bảng tài liệu của nhóm không có thao tác gỡ chia sẻ. | Thêm cột; tạo xong mở thẳng trang nhóm; thêm "Gỡ chia sẻ" cho chủ nhóm/người chia sẻ. | P1 |
| Kiểm duyệt số hoá | Tab "OCR / STT" là thuật ngữ kỹ thuật; khi trống không dẫn tới lịch sử đã duyệt. | Đổi thành "Văn bản / Ghi âm / Video"; thêm liên kết "Xem các lần đã duyệt". | P2 |
| Báo cáo | Chỉ có bảng; liệt kê 31 dòng theo ngày toàn số 0; kết quả nằm dưới form dài nhưng không tự cuộn tới. | Thêm biểu đồ cột/đường; tự chọn độ chi tiết theo khoảng thời gian (tháng thì theo ngày, năm thì theo tháng), ẩn dòng 0; tự cuộn tới kết quả. Ghi nhận tốt: các nút chọn nhanh khoảng thời gian. | P2 |
| Lưu trữ cá nhân | Ô chọn "Chuyển vào — Chọn —" ở mỗi dòng khó hiểu; với R3 hiện câu "Chưa có thư mục nào trong kho" (nhầm với kho chung). | Đổi thành nút "Di chuyển…" mở hộp chọn thư mục; sửa câu chữ. | P2 |

### 3.2 Lãnh đạo

| Vấn đề | Đề xuất | Mức |
|---|---|---|
| Thông báo "Bạn đã được thêm vào một nhóm." không có tên nhóm/người thêm; bấm vào chỉ đánh dấu đã đọc. | Nêu tên nhóm và người thêm; bấm thì mở trang nhóm. | P1 |
| Trong Kho thấy tài liệu được chia sẻ nhưng không biết qua nhóm nào; vẫn có nút Xóa với tài liệu người khác nạp; cây hiện mọi thư mục dù trong phạm vi chỉ có 1 tài liệu. | Gắn nhãn "Chia sẻ qua <nhóm>"; xác nhận lại quy tắc ai được xoá; tuỳ chọn ẩn thư mục rỗng. | P1 |
| Dòng nhật ký không bấm được; ID tài nguyên là hash bị cắt. | Hiển thị tên tài liệu/thư mục, bấm để mở. | P2 |

### 3.3 Quản trị

| Màn hình | Vấn đề | Đề xuất | Mức |
|---|---|---|---|
| Thanh tab | 10 tab, chữ xuống 2 dòng; 3 tab chưa làm vẫn hiện (mờ). | Ẩn tab chưa làm; chuyển sang menu dọc bên trái trong khu Quản trị hoặc nhóm tab lại (Người dùng & quyền / Kho & dữ liệu / Hệ thống). | P1 |
| Cấu trúc kho | Chọn trên cây bên trái, thao tác ở form bên phải; ô "Tên thư mục" dùng chung cho tạo và đổi tên; thư mục vừa tạo không được chọn; nút "Di chuyển" vẫn bật khi đích trùng vị trí hiện tại; không có menu chuột phải/kéo-thả. | Hành động ngay trên từng nút cây (menu ⋯: Thêm con / Đổi tên / Di chuyển / Xóa), đổi tên tại chỗ; chọn và cuộn tới thư mục mới tạo; tắt "Di chuyển" khi đích không đổi. | P1 |
| Dung lượng kho | Ô "Đặt dung lượng tối đa" trống thay vì điền sẵn giá trị hiện tại. | Điền sẵn và hiện "hiện tại: 5120 GB". | P2 |
| Tài khoản | 33 tài khoản không có tìm kiếm/lọc/phân trang; 5 hành động mỗi dòng, Khóa/Vô hiệu hóa nằm sát nhau; tạo tài khoản không có nút sinh mật khẩu/hiện mật khẩu và không đặt hạn mức. | Ô tìm + lọc vai trò/trạng thái; giữ "Sửa" ngoài, gom phần còn lại vào menu ⋯; nút "Sinh mật khẩu" + 👁. | P1 |
| Cấu hình hệ thống | ~36 tham số trong một danh sách phẳng; thuật ngữ nội bộ; tham số bật/tắt phải gõ tay "true/false"; byte thô hiện cạnh GB; giá trị đã khác mặc định không nổi bật. | Nhóm theo mục (Lưu trữ, Nạp, Số hoá, Tìm kiếm, Báo cáo, Sao lưu) + ô tìm; công tắc cho bật/tắt, ô số kèm đơn vị; badge "Đã thay đổi" + "Khôi phục mặc định". | P1 |
| Định dạng cho phép | Phải gõ MIME type bằng tay; nhóm hiển thị mã `TEXT_DOC`. | Nhập theo đuôi tệp (`.docx`) rồi tự suy MIME; nhãn nhóm tiếng Việt. | P2 |
| Phân quyền (RBAC) | 23 quyền trong danh sách phẳng; không thấy nút Lưu (hàng cuối toàn "—"). | Nhóm quyền theo chức năng; thanh "Có N thay đổi chưa lưu — Lưu / Huỷ" cố định ở đáy. | P1 |
| Chuyển quyền sở hữu | Chọn người nạp từ dropdown 33 người, không tìm được; không tìm theo tên tài liệu. | Combobox có tìm kiếm (dùng lại UserPicker); thêm ô tìm tài liệu. | P2 |
| Nhật ký hoạt động | Bộ lọc và khối "Xác minh toàn vẹn chuỗi" chiếm hết màn hình đầu, bảng nằm dưới; ô người thực hiện là text tự do. | Thu bộ lọc thành một hàng; chuyển "Xác minh chuỗi" sang tab/nút riêng; ô người thực hiện có gợi ý. | P2 |

## 4. Đề xuất thứ tự làm

1. **Đợt 1 (nhanh, ít rủi ro):** toàn bộ mục P0 ở phần 1; bỏ mã FR/NFR/R1–R3 khỏi UI; toast chung + bỏ layout shift; nút Tải xuống ở trang chi tiết; highlight từ khoá.
2. **Đợt 2:** dashboard theo vai trò; component cây thư mục và tải lên dùng chung; tìm/lọc/sắp xếp ở Kho và Tài khoản; nhóm Cấu hình hệ thống và RBAC.
3. **Đợt 3:** xem trước tài liệu; gộp hai trang tìm kiếm; biểu đồ báo cáo; kiểm tra responsive.

## 5. Dữ liệu test còn lại trên stg

`uxtest-folder` (kho) với `uxtest-quy-dinh.txt` (có 1 ghi chú) và `uxtest-bien-ban.txt` (đã xoá rồi phục hồi); nhóm `uxtest-nhom`. Chưa dọn.
