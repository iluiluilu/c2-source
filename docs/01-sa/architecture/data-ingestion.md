# Architecture: data-ingestion

> File: `docs/01-sa/architecture/data-ingestion.md` — Owner: SA
> Status: Approved

## 1. Bối cảnh / Vấn đề

- `docs/dac_ta_yeu_cau_chuc_nang_phi_chuc_nang.adoc` mục 2.13:
  - FR-11: tiếp nhận dữ liệu đầu vào qua giao diện web, chọn thư mục đích
    theo cấu trúc kho (FR-13), tải theo tệp lẻ hoặc cả một thư mục (giữ cấu
    trúc thư mục con).
  - FR-12: kiểm tra toàn vẹn (mã băm SHA-256), quét mã độc, kiểm tra định
    dạng trước khi nhập kho; tệp nhiễm mã độc tuyệt đối không vào kho.
- In-scope: rà soát/hoàn thiện luồng upload (tệp lẻ + thư mục) và kiểm tra an
  toàn/toàn vẹn; chốt allowlist định dạng, giới hạn kích thước, cơ chế tải
  lại khi gián đoạn.
- Out-of-scope: quản lý cấu trúc kho (FR-13, xem `warehouse-structure.md`);
  OCR/STT (FR-17–20).

## 2. Ràng buộc (Constraints)

- Mạng cách ly hoàn toàn — cập nhật signature ClamAV phải qua kênh ngoại
  tuyến định kỳ, không tự động pull từ Internet.
- SeaweedFS là object storage đã chốt (`source_v2/seaweedfs/`).
- Một tệp lỗi trong lượt tải (kể cả tải theo thư mục) không được chặn các
  tệp hợp lệ còn lại.
- Bắt buộc chọn thư mục đích theo cấu trúc kho (FR-13) trước khi tải lên.

## 3. NFR (Non-Functional Requirements)

| Loại | Yêu cầu |
|---|---|
| Performance | Tải lên và kiểm tra không chặn thao tác khác của người dùng (NFR-01) |
| Security | Quét mã độc bằng bộ chữ ký cập nhật ngoại tuyến (NFR-04), tệp nhiễm mã độc tuyệt đối không vào kho |
| Scalability | Hỗ trợ tải cả thư mục nhiều tệp/thư mục con trong một lượt |
| Availability | Gián đoạn khi tải → tải lại được, tiếp tục từ tệp chưa hoàn tất |
| Khác | Toàn vẹn — mọi tệp sạch có mã băm SHA-256 lưu cùng siêu dữ liệu (NFR-05) |

## 4. Phương án & Quyết định (ADR)

### Quyết định 1: Cơ chế tải theo thư mục

- **Ngữ cảnh:** FR-11/FR-14 yêu cầu chọn cả một thư mục để tải, giữ nguyên
  cấu trúc thư mục con tương đối, mỗi tệp xử lý độc lập.
- **Các phương án xem xét:**
  - A) Kéo-thả thư mục vào vùng thả, duyệt cây bằng
    `DataTransferItem.webkitGetAsEntry()` (đệ quy `FileSystemDirectoryEntry`,
    lấy relative path từ `fullPath` của từng entry), gửi từng tệp kèm
    relative path lên API upload hiện có (nhiều request nhỏ). Nút "Chọn tệp"
    chỉ chọn tệp lẻ, không chọn thư mục; tải thư mục **chỉ qua kéo-thả**.
  - B) Nén thành archive ở client, giải nén ở server.
  - Đã loại:
    - Giữ `<input webkitdirectory>` (phương án A cũ) — Chrome/Edge hiện
      popup xác nhận gốc của trình duyệt ("Upload N files to this site?"),
      không tắt được bằng JS.
    - `showDirectoryPicker()` (File System Access API) — vẫn có prompt xin
      quyền, chỉ Chromium hỗ trợ, cần HTTPS.
- **Quyết định:** A — đã chốt (đổi từ `<input webkitdirectory>` sang kéo-thả
  + `webkitGetAsEntry()` để loại popup xác nhận gốc của trình duyệt, giữ
  nguyên nghiệp vụ tải thư mục).
- **Lý do:** không phát sinh popup gốc của trình duyệt; đơn giản, tái sử dụng endpoint upload tệp lẻ đã có
  (`DocumentsController`), không cần cơ chế giải nén archive (giảm bề mặt
  tấn công — tránh rủi ro zip bomb/path traversal khi giải nén); khớp đúng
  yêu cầu "một tệp lỗi không làm hỏng cả lượt tải".
- **Hệ quả (trade-off):** nhiều request nhỏ thay vì một request lớn — cần UI
  hiển thị tiến trình từng tệp và hỗ trợ tải lại đúng các tệp chưa hoàn tất
  (đúng Ngoại lệ & xử lý lỗi của FR-11). Không còn nút chọn thư mục; thiết
  bị cảm ứng (không kéo-thả được) không tải thư mục được, chỉ chọn tệp lẻ;
  UI phải có chữ hướng dẫn rõ ("Kéo-thả thư mục vào đây để tải cả thư
  mục").

### Quyết định 2: Danh sách định dạng cho phép (allowlist) và cơ chế lưu trữ

- **Ngữ cảnh:** FR-11 nói "hỗ trợ các định dạng được hệ thống cấu hình" —
  danh sách cụ thể chưa chốt trong SRS (thuộc phạm vi FR-01/system-config).
  `MimeAllowlist.java` hiện tại là danh sách **tĩnh, hard-code trong code**,
  không sửa được qua UI.
- **Quyết định (đã chốt):**
  1. Đồng ý danh sách tham khảo dưới đây làm **dữ liệu khởi tạo (seed)**:
     - Văn bản: `pdf, doc, docx, xls, xlsx, ppt, pptx, txt`
     - Ảnh: `jpg, jpeg, png, tiff, bmp`
     - Âm thanh: `mp3, wav, m4a`
     - Video: `mp4, avi, mkv, mov`
  2. Danh sách này phải **cấu hình được qua giao diện quản trị (R3)** —
     KHÔNG còn hard-code trong `MimeAllowlist.java`. Bổ sung bảng
     `allowed_file_format` (PostgreSQL) nếu chưa có — đúng theo mô hình
     hybrid đã chốt ở `system-config.md` (Quyết định 1): tham số nghiệp vụ
     lưu DB, sửa được qua UI, có audit.
  3. Vì kiểm tra định dạng chạy trên mọi lượt upload (đường dẫn nóng), KHÔNG
     query DB trực tiếp mỗi lần — đọc `allowed_file_format` từ **cache
     Redis** (thay cho phương án cache in-process/Caffeine đã xem xét ban
     đầu), ghi đồng bộ vào Redis ngay khi R3 đổi cấu hình. Đồng bộ với cơ
     chế cache chung đã chốt lại ở `system-config.md` (Quyết định 4).
- **Lý do:** danh sách đủ dùng cho hồ sơ nghiệp vụ (văn bản/scan/ghi
  âm/video) theo mô tả FR-11/FR-17/FR-19; chuyển sang DB-backed đáp ứng đúng
  yêu cầu "được hệ thống cấu hình" trong FR-11 (ngụ ý sửa được, không phải
  chỉ đọc); cache tránh regression hiệu năng so với cách tra cứu tĩnh hiện
  tại.
- **Hệ quả (trade-off):** `MimeAllowlist.java` chuyển vai trò từ "nguồn dữ
  liệu" thành "seed migration" ban đầu; cần thêm API quản trị (CRUD định
  dạng) và cơ chế cache-invalidation — tăng nhẹ độ phức tạp so với danh sách
  tĩnh, nhưng bắt buộc để đáp ứng đúng SRS.

## 5. Tech stack đề xuất

- Backend: SeaweedFS (object storage, đã chốt), ClamAV (malware scanner, đã
  chốt — `ClamdService.java`), Spring Boot multipart upload.
- Frontend: Next.js, kéo-thả thư mục (`webkitGetAsEntry`) cho tải thư mục
  (`source_v2/web/src/app/upload/`).
- Data store: PostgreSQL (siêu dữ liệu tệp, mã băm, bảng
  `allowed_file_format` mới); **Redis** cho cache danh sách định dạng, ghi
  đồng bộ khi R3 đổi cấu hình.
- Infra/Deploy: bổ sung service Redis (chưa có trong
  `docker-compose.yml`/`pom.xml` hiện tại — xem `system-config.md`
  Quyết định 4 để biết chi tiết hạ tầng cần thêm).

## 6. Rủi ro

| Rủi ro | Mức độ | Giảm thiểu |
|---|---|---|
| Tải thư mục lớn (hàng nghìn tệp) qua nhiều request nhỏ → nghẽn/timeout | Trung bình | Giới hạn số tệp/lượt và tổng dung lượng/lượt (xem Câu hỏi mở #2 — đã chốt số liệu), hàng đợi phía client, retry có backoff |
| Cache danh sách định dạng không được invalidate đúng lúc khi R3 đổi cấu hình → định dạng mới chưa nhận ngay hoặc định dạng đã gỡ vẫn còn hiệu lực tạm thời | Thấp | Đã giảm đáng kể nhờ chuyển sang Redis (shared cache, ghi đồng bộ) thay vì cache in-process — không còn rủi ro lệch giữa nhiều instance |
| Signature ClamAV lỗi thời do mạng cách ly, cập nhật thủ công trễ → bỏ lọt mã độc mới | Trung bình | Rủi ro vận hành, không phải quyết định kiến trúc — xử lý bằng SOP/runbook (xem mục 7, câu hỏi #3) |

## 7. Câu hỏi mở

_Đã chốt cả 3 câu hỏi mở trước đó:_

- ~~Danh sách định dạng cho phép chính thức?~~ → **Chốt theo Quyết định 2:
  danh sách tham khảo làm seed, lưu DB (`allowed_file_format`), sửa được
  qua UI quản trị, có cache.**
- ~~Giới hạn số lượng tệp / tổng dung lượng tối đa mỗi lượt tải theo thư
  mục?~~ → **Đề xuất mặc định: tối đa 500 tệp và 5 GB tổng dung lượng mỗi
  lượt tải theo thư mục.** Hai giá trị này là tham số nghiệp vụ, đưa vào
  `system-config` (FR-01) để R3 cấu hình lại được — không hard-code. Vượt
  ngưỡng: chặn bắt đầu lượt tải kèm thông báo rõ đang vượt tiêu chí nào
  (số tệp hay dung lượng).
- ~~Chu kỳ cập nhật signature ClamAV ngoại tuyến — có cần chốt trong tài
  liệu này không?~~ → **Không cần.** Đây là quy trình vận hành hạ tầng
  (SOP/runbook), không phải quyết định kiến trúc ứng dụng — không thuộc
  phạm vi ADR của tài liệu SA này. Theo đúng quy ước đã có ở SRS (nội dung
  triển khai hạ tầng cụ thể để ở `kien_truc_he_thong.adoc`), chu kỳ cập
  nhật signature nên được ghi trong runbook vận hành ClamAV, không phải ở
  đây. Giữ lại như một rủi ro cần lưu ý (mục 6) nhưng không chặn duyệt tài
  liệu này.

Không còn câu hỏi mở nào cho data-ingestion ở bước SA.
