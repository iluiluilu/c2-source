# Danh sách fix UI/UX — từ rà soát stg 2026-10-13

> Status: Draft
>
> Nguồn: `docs/04-qa/reports/ux-review-stg-20261013.md`. Đánh dấu `[x]` khi đã sửa và test (unit test web + typecheck + lint).
> Mục có ⚠ cần người dùng quyết định trước khi sửa.

## Đợt 1 — Lỗi (P0) và sửa nhanh

- [x] **UX-01** Ẩn chuông thông báo (và không gọi `/notifications`) khi người dùng không có quyền xem thông báo (R3 bị 403 mỗi 60s, ghi rác vào nhật ký).
- [x] **UX-02** Nhật ký ghi IP thật của người dùng (đọc `X-Forwarded-For` / `X-Real-IP` từ proxy) thay vì IP proxy `172.18.0.12`. _(Bật `server.forward-headers-strategy: native`; cần kiểm chứng trên stg sau deploy.)_
- [x] **UX-03** Modal xem ảnh trang: giới hạn chiều cao theo màn hình, header + nút Đóng luôn thấy, đóng bằng Esc và bấm nền.
- [x] **UX-04** Sidebar không sáng 2 mục cùng lúc (`/admin/audit` sáng cả "Quản trị hệ thống").
- [x] **UX-05** Nút "Gỡ" bị cắt mép phải (Định dạng cho phép, thành viên nhóm).
- [x] **UX-06** Thông báo cho thành viên khi tài liệu được chia sẻ vào nhóm. _(Luồng chia sẻ từng tài liệu trước đây không gửi; payload thêm `groupName`, `actorUsername`.)_
- [x] **UX-07** Bỏ mã FR/NFR/Story/"Quyết định SA"/tên công nghệ khỏi giao diện. _(Câu chữ trong web; mô tả tham số cấu hình lấy từ DB xử lý ở UX-21.)_
- [x] **UX-08** Vai trò hiển thị tên ("Cán bộ/Lãnh đạo/Quản trị") thay vì R1/R2/R3/`LANH-DAO`; nhóm định dạng hiển thị tên tiếng Việt thay vì `TEXT_DOC`.
- [x] **UX-09** Toast chung cho thông báo kết quả; thay banner chèn đầu trang (gây layout shift) và các thông báo ở đáy form; thêm thông báo cho thao tác đang im lặng (tạo nhóm, phục hồi).
- [x] **UX-10** Kho tài liệu: thanh thao tác hàng loạt không đẩy bảng xuống (layout shift).
- [x] **UX-11** Trang chi tiết tài liệu: thêm nút Tải xuống, hiển thị thư mục chứa, thu SHA-256 vào phần thông tin kỹ thuật.
- [x] **UX-12** Tô sáng từ khoá trong đoạn trích kết quả tìm kiếm toàn văn.
- [x] **UX-13** Hộp xác nhận xoá nói rõ "chuyển vào thùng rác, khôi phục được trong N ngày".
- [x] **UX-14** Thùng rác: thông báo sau khi phục hồi; thêm cột thư mục gốc.
- [x] **UX-15** Nạp dữ liệu: reset lựa chọn sau khi tải xong; liên kết mở thư mục đã nạp; không hiện "Đang chờ…" trước khi bấm tải; bỏ được từng tệp đã chọn.
- [x] **UX-16** Định dạng ngày thống nhất `dd/mm/yyyy` (báo cáo đang `yyyy-mm-dd`).
- [x] **UX-17** Tiêu đề header khớp mục menu (Tra cứu ngữ nghĩa; các tab admin).
- [x] **UX-18** Thông báo "được thêm vào nhóm" nêu tên nhóm, bấm vào mở trang nhóm.

## Đợt 2 — Cải thiện thao tác hằng ngày (P1)

- [x] **UX-19** Kho tài liệu: ô lọc theo tên, sắp xếp cột, checkbox "chọn tất cả", breadcrumb + nút "Nạp vào thư mục này"; bấm lại thư mục đang chọn không âm thầm bỏ lọc. _(Thêm `?folder=` cho Kho và Nạp dữ liệu; đường dẫn thư mục đang lọc luôn hiện trên bảng.)_
- [x] **UX-20** Tài khoản (admin): ô tìm + lọc vai trò/trạng thái; gom hành động phụ vào menu ⋯; nút sinh mật khẩu + hiện/ẩn mật khẩu.
- [x] **UX-21** Cấu hình hệ thống: nhóm tham số theo mục + ô tìm; công tắc cho tham số bật/tắt; đánh dấu "Đã thay đổi".
- [x] **UX-22** Phân quyền: nhóm quyền theo chức năng; thanh "N thay đổi chưa lưu — Lưu / Huỷ" dễ thấy.
- [x] **UX-23** Cấu trúc kho (admin): tách ô đổi tên khỏi ô tạo mới; chọn thư mục vừa tạo; tắt "Di chuyển" khi đích trùng vị trí hiện tại; điền sẵn dung lượng tối đa hiện tại.
- [x] **UX-24** Thanh tab admin: ẩn tab chưa làm (API, Sao lưu, Hạn mức); chữ tab không xuống dòng.
- [x] **UX-25** Nhóm chia sẻ: danh sách có số thành viên/số tài liệu/vai trò của tôi; tạo nhóm xong mở luôn trang nhóm; gỡ chia sẻ tài liệu ngay trong nhóm.
- [x] **UX-26** Kho (lãnh đạo/thành viên nhóm): nhãn "Chia sẻ qua <nhóm>". _(API `/documents` trả thêm `sharedViaGroups`.)_
- [ ] **UX-26b** ⚠ Xác nhận quy tắc ai được xoá tài liệu do người khác nạp (lãnh đạo đang thấy nút Xóa với tài liệu được chia sẻ).
  - _Đánh giá 2026-10-14 (`TrashService.trash` / `requireRestorePermission`):_ quy tắc thực tế là **mọi thành viên** của nhóm được chia sẻ (cán bộ hoặc lãnh đạo, không phân biệt chủ nhóm) có quyền `trash.manage` đều xoá được. Không mất dữ liệu ngay: xoá là xoá mềm, có ghi nhật ký (người xoá, lý do, nhóm, hạn xoá cứng), người nạp hoặc người xoá phục hồi được nguyên trạng trong thời hạn lưu (mặc định 365 ngày), các lượt chia sẻ vẫn được giữ.
  - Rủi ro: (1) một thành viên xoá là tài liệu biến mất với **tất cả** các nhóm và cả với người nạp; (2) người nạp **không được thông báo**, chỉ thấy nếu tự mở tab "Tài liệu tôi nạp bị người khác xóa"; quá hạn lưu thì bị xoá cứng vĩnh viễn; (3) nếu cả người nạp và người xoá đều nghỉ việc/bị vô hiệu hoá thì không còn ai phục hồi được (quản trị không có chức năng phục hồi); (4) trên stg đang tắt cổng sao lưu trước xoá cứng (`backup.purge_gate_enabled=false`).
- [x] **UX-27** Tìm kiếm: thẻ kết quả có thư mục/ngày/người nạp; trạng thái rỗng có gợi ý; ghi chú tài liệu mới cần số hoá xong mới tìm được.
- [x] **UX-28** Tra cứu ngữ nghĩa: gộp các đoạn cùng tài liệu vào 1 thẻ.
- [x] **UX-29** Chuyển quyền sở hữu: chọn người nạp bằng ô có tìm kiếm.
- [x] **UX-30** Nhật ký hoạt động: thu gọn bộ lọc, chuyển "Xác minh chuỗi" xuống dưới/thu gọn; hiển thị tên tài nguyên khi có. _(Không hiện tên tài liệu cho quản trị — giữ tách biệt nhiệm vụ; người xem được tài liệu bấm ID để mở.)_
- [x] **UX-31** Dashboard theo vai trò (việc cần làm, tài liệu gần đây, hạn mức…) thay cho "Chức năng khả dụng: N".

- [x] **UX-40** _(phát hiện khi sửa)_ Kho tài liệu chỉ hiện 50 tài liệu đầu (API mặc định `limit=50`, UI không phân trang): thêm "Tải thêm tài liệu"; lọc tên gửi lên server (`q`).

## Đợt 3 — Trau chuốt / tính năng lớn (P2)

- [x] **UX-32** Báo cáo: tự cuộn tới kết quả; ẩn dòng toàn 0 / chọn độ chi tiết hợp lý; biểu đồ.
- [x] **UX-33** Kiểm duyệt số hoá: đổi tên tab OCR/STT dễ hiểu; liên kết lịch sử đã duyệt khi hàng chờ trống.
- [x] **UX-34** Lưu trữ cá nhân: thay dropdown "Chuyển vào" bằng nút "Di chuyển…"; sửa câu "Chưa có thư mục nào trong kho".
- [x] **UX-35** Định dạng cho phép: nhập theo đuôi tệp, tự suy MIME.
- [x] **UX-36** Xem trước nội dung tài liệu (PDF/ảnh/văn bản) trên trang chi tiết.
- [x] **UX-37** Gộp component cây thư mục / tải lên dùng chung; thống nhất độ rộng trang. _(Cây thư mục + độ rộng xong. Khung tải lên của Kho và Lưu trữ cá nhân giữ riêng: hai luồng nạp khác nhau — batch kho/kiểm tra định dạng vs lưu trữ cá nhân.)_
- [x] **UX-38** Trang đăng nhập: chuyển thẳng tới Keycloak (giữ nút "Đăng nhập" dự phòng nếu chuyển hướng không chạy).
- [ ] **UX-39** Kiểm tra giao diện màn hình hẹp (chưa test được trên stg).
