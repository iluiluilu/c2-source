# Prompt: cập nhật tài liệu User Story + sơ đồ

> Dùng cho một session Claude Code mới. Chạy session từ thư mục
> `/Users/linhnt/Documents/projects/c2` (file đích nằm ngoài repo `source_v2`).
> Ngày tạo: 2026-10-08.

---

## Nhiệm vụ

Cập nhật tài liệu `docs/user_story_use_case_with_flow.adoc` (bản v2.0, đã lỗi
thời từ 02/08) và toàn bộ sơ đồ Mermaid trong `docs/so_do_user_story/` cho khớp
với đặc tả và hệ thống hiện tại.

## Nguồn sự thật (đọc theo thứ tự ưu tiên, chỉ đọc phần liên quan, dùng grep)

1. `source_v2/dac_ta_yeu_cau_chuc_nang_phi_chuc_nang.adoc` — SRS **v2.2** (bản
   mới nhất; bản ở `docs/` có thể chưa đồng bộ, KHÔNG dùng).
2. `source_v2/docs/00-overview/tinh-nang-phan-mem.adoc` — mô tả tính năng theo
   vai trò, thuật ngữ đã chốt.
Chỉ đọc đúng các mục FR cần thiết (dùng grep theo mã FR), không đọc toàn bộ
SRS, không đọc thư mục `document/` hay các tài liệu thiết kế hạ tầng. Phần
"Bước 1" bên dưới đã tóm tắt sẵn mọi thông tin cần thiết — ưu tiên dùng nó.

Không tự phát minh tính năng. Điều gì không có căn cứ thì không ghi, hoặc hỏi
người dùng.

**Chia làm 2 phần, mỗi phần nên chạy ở 1 session riêng:**
- Phần A = Bước 0 + Bước 1 + kiểm tra nội dung (chỉ sửa file adoc).
- Phần B = Bước 2 + kiểm tra sơ đồ (chỉ làm sơ đồ, đọc file adoc đã sửa ở phần A).
Người dùng sẽ nói rõ đang chạy phần nào.

## Bước 0 — Hỏi người dùng trước khi viết (AskUserQuestion, 1 lượt)

1. Mục 4 (Lãnh đạo): viết lại theo hướng "Lãnh đạo có toàn bộ hành trình của
   Cán bộ (mục 3) + thêm theo dõi nhật ký trong phạm vi nhóm", bỏ 4.1/4.2 trùng
   lặp? (đề xuất: có)
2. Thuật ngữ: dùng "tra cứu" hay "tìm kiếm" xuyên suốt? (đề xuất: "tra cứu",
   theo tài liệu tính năng; giữ tên FR như SRS)
3. "Chuyển quyền sở hữu tài liệu" và "Thống kê thùng rác" (R3) chưa có FR trong
   SRS: chỉ ghi "chưa có FR" trong bảng đối chiếu, hay đề xuất bổ sung FR vào
   SRS? (đề xuất: chỉ ghi "chưa có FR", không sửa SRS trong session này)

## Bước 1 — Sửa nội dung `user_story_use_case_with_flow.adoc`

Giữ văn phong hiện tại: tiếng Việt, góc nhìn người dùng, không kỹ thuật, câu
ngắn. Nâng phiên bản lên **v3.0**, ghi rõ đối chiếu với SRS v2.2.

### 1a. Lỗi sai phải sửa

| Mục | Hiện tại (sai) | Sửa thành |
|---|---|---|
| 1 | Lãnh đạo "chủ yếu tra cứu và xem báo cáo" | Có toàn bộ chức năng Cán bộ + theo dõi nhật ký trong phạm vi nhóm mình quản lý |
| 3.6 | "hệ thống **trả lời** kèm trích dẫn" | Trả về **các đoạn nội dung liên quan nhất** kèm dẫn nguồn (giai đoạn 1 không có AI viết câu trả lời — `search-semantic.md` QĐ 4) |
| 3.1 | Kho cá nhân "kiểm tra định dạng" | Chỉ rà mã độc; nhận **mọi loại tệp** (FR-14) |
| 3.1 | Kho cá nhân "gắn nhãn và ghi chú lúc nạp" | Bỏ (nhãn chỉ cho kho chung — FR-15; ghi chú lúc nạp thuộc 3.2) |
| 3.1 | "gần hết dung lượng… báo trước" | "Theo dõi dung lượng đã dùng / hạn mức; vượt hạn mức thì bị chặn" (không có căn cứ cho cảnh báo trước) |
| 5.2 | "Định nghĩa các vai trò" | Không tạo vai trò mới; gán vai trò có sẵn; bật/tắt quyền từng vai trò qua ma trận; giao diện tự hiển thị theo quyền; chặn vai trò Quản trị tự mất quyền quản trị |
| 3.7 | "Sửa… kết quả nhận dạng đã kiểm duyệt" | Sửa nhãn, ghi chú; kết quả số hóa sửa ở bước kiểm duyệt (3.4) |
| 4.x | `image::` trỏ `4.1-…`, `4.2-…`, `4.3-…svg` nhưng file thực tế là `4.3-…`, `4.4-…`, `4.5-…` → 3 ảnh đang hỏng | Sửa theo cấu trúc mục 4 mới; tên file sơ đồ khớp số mục |

### 1b. Bổ sung nội dung còn thiếu

- **2.1** Tạm khóa khi sai mật khẩu nhiều lần; cảnh báo trước khi hết phiên. Có thể thêm "Bảng điều khiển" (lối tắt theo vai trò).
- **3.1** Tải tệp lớn, tải tiếp khi gián đoạn; kéo-thả thư mục; tải xuống; **chuyển tệp/thư mục cá nhân vào kho chung** (đi qua nạp + số hóa).
- **3.2** Kéo-thả một hoặc nhiều thư mục (kèm tệp lẻ) — toàn bộ tệp được ghi vào thư mục đích đã chọn (KHÔNG viết "giữ cấu trúc thư mục con", KHÔNG viết "không tạo thư mục con"); kiểm tra mã băm, mã độc, định dạng; ghi chú khi nạp; tệp lớn + tải tiếp; tệp trùng tên vẫn lưu thành bản mới; tự động gửi đi số hóa; chặn nạp khi kho đạt ngưỡng 95%.
- **3.3** Hai loại nhãn *chủ đề* và *loại tài liệu* (= "lĩnh vực" trong báo cáo); quản lý danh mục nhãn (tạo, đổi tên, ngừng dùng); lịch sử ghi chú; ghi chú tra cứu được.
- **3.4** Đổi tên "Kiểm duyệt kết quả số hóa". Hàng chờ chỉ tài liệu mình nạp; trạng thái *đang xử lý / chờ duyệt / quá hạn duyệt*; OCR xem ảnh gốc kèm khung vị trí chữ; STT phát đồng bộ từng câu, sửa văn bản + mốc thời gian; **tóm tắt nội dung ảnh, video** (FR-21: ảnh có bản tóm tắt; video có tóm tắt chung + tóm tắt theo đoạn có mốc thời gian, từ hình ảnh và lời nói); tự xác nhận sau 24 giờ (cấu hình được) **trừ kết quả bị đánh dấu chất lượng kém**; so sánh kết quả khi số hóa lại bằng mô hình mới.
- **3.5** Vai trò chủ nhóm/thành viên, đổi vai trò, tự rời nhóm; chia sẻ hàng loạt; thông báo trong ứng dụng.
- **3.6** Chỉ tìm thấy nội dung đã xác nhận (giao diện báo khi còn chờ duyệt); tìm cả tóm tắt ảnh/video và ghi chú; cú pháp rút gọn trong ô tìm kiếm.
- **3.7** Thùng rác cá nhân; phục hồi về vị trí cũ; hướng dẫn khi phục hồi bị xung đột; hiển thị dung lượng thùng rác; tự xóa vĩnh viễn sau 365 ngày.
- **3.8** 3 mẫu báo cáo: *Tổng hợp tình hình kho tài liệu theo kỳ*, *Tiến độ và chất lượng số hóa*, *Thống kê theo chủ đề và loại tài liệu*; chọn nhanh kỳ (tháng/quý/6 tháng/năm) hoặc khoảng ngày; lưu bộ tham số; số liệu theo phạm vi người lập; xuất PDF/Excel/Word chạy nền, có lịch sử xuất; chỉ người tạo tải được, tệp giữ 7 ngày.
- **4** Theo quyết định ở Bước 0.
- **5.1** Khóa kèm lý do; vô hiệu hóa dài hạn; chỉnh hạn mức lưu trữ cá nhân từng tài khoản.
- **5.3** Tham số nhạy cảm cần xác nhận 2 bước; tách "Định dạng tệp cho phép" thành mục riêng.
- **5.4** Đổi tên/di chuyển thư mục không hỏng tài liệu; chặn xóa thư mục còn dữ liệu; cảnh báo 85%, chặn nạp 95%.
- **5.5** Xác minh toàn vẹn nhật ký; luân chuyển nhật ký theo chính sách.
- **5.6** Gán phạm vi dữ liệu cho từng khóa; thu hồi có hiệu lực ngay.
- **Mục mới cho R3:** Chuyển quyền sở hữu tài liệu (khi người nạp nghỉ việc, chuyển công tác — chuyển sang bất kỳ Cán bộ/Lãnh đạo đang hoạt động); Thống kê thùng rác (số lượng, dung lượng, phân bố thời hạn — không xem nội dung).

### 1c. Phạm vi KHÔNG đưa vào

- **Không** có nhận dạng/nhận diện sự kiện âm thanh (SED) — đã chốt bỏ.
- Không đưa tên khách hàng, bối cảnh nghiệp vụ cụ thể của khách hàng, cấu hình phần cứng, tên công nghệ vào tài liệu này — viết ở mức chung cho một cơ quan quản lý tài liệu.

### 1d. Bảng đối chiếu (mục 7)

Cập nhật số mục theo cấu trúc mới; 3.4 thêm **FR-21**; 3.1 thêm FR-11/FR-12
(chuyển sang kho chung); hai mục R3 mới ghi theo quyết định Bước 0.

### 1e. Thuật ngữ

"số hóa" (không "số hoá"), "kết quả số hóa" (thay "nhận dạng tự động"),
"tra cứu"/"tìm kiếm" theo Bước 0, "mốc thời gian", "kho dữ liệu dùng chung"
thống nhất một cách gọi trong toàn tài liệu.

## Bước 2 — Sơ đồ (`docs/so_do_user_story/`)

- Đọc `README.md` trong thư mục để nắm quy ước (Mermaid flowchart, tiếng Việt,
  hình dạng nút, mỗi sơ đồ có `<tên>.md` + `svg/` + `png/` + `pdf/`, mục dùng
  chung luồng thì chỉ giữ một bộ tệp và trỏ sang).
- Vẽ lại/cập nhật nguồn `.md` cho mọi mục thay đổi luồng: tối thiểu **3.1, 3.2,
  3.4, 3.6, 3.7, 3.8, mục 4, 5.2, 5.4**, cộng sơ đồ cho các mục R3 mới. Rà các
  sơ đồ còn lại cho khớp nội dung bổ sung.
- Đổi tên file theo số mục mới; xóa file sơ đồ không còn dùng (cả 4 định dạng).
- Xuất lại ảnh trong thư mục `so_do_user_story/`:
  ```bash
  npx -y @mermaid-js/mermaid-cli -i <tên>.md -o svg/<tên>.svg
  npx -y @mermaid-js/mermaid-cli -i <tên>.md -o png/<tên>.png -b white -s 2
  npx -y @mermaid-js/mermaid-cli -i <tên>.md -o pdf/<tên>.pdf
  ```
  (mermaid-cli xuất từ `.md` sẽ thêm hậu tố `-1` vào tên file nếu nguồn có
  nhiều khối — kiểm tra và đổi tên cho đúng). Nếu không xuất được (thiếu
  mạng/Chromium), dừng lại báo người dùng, không bỏ qua im lặng.
- Cập nhật danh sách trong `README.md` của thư mục sơ đồ (và sửa link tới
  `../user_story_use_case.adoc` nếu tên tài liệu gốc khác).

## Bước 3 — Kiểm tra trước khi báo xong

- Mọi `image::` trong adoc trỏ tới file SVG có thật:
  `grep -o 'so_do_user_story/svg/[^[]*' docs/user_story_use_case_with_flow.adoc | xargs -I{} test -f docs/{} || echo THIẾU`.
- Không còn: "sự kiện âm thanh", "SED", "trả lời kèm trích dẫn", "giữ cấu trúc
  thư mục con", "chủ yếu tra cứu và xem báo cáo", "Định nghĩa các vai trò".
- Mở thử 2–3 ảnh PNG vừa xuất (Read) để chắc sơ đồ hiển thị đúng tiếng Việt.

## Kết quả cần báo lại cho người dùng (ngắn)

Danh sách mục đã sửa/thêm, danh sách sơ đồ đã vẽ lại/đổi tên/xóa, các điểm
không có căn cứ đã bỏ qua hoặc cần người dùng quyết định thêm.
