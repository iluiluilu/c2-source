# Runbook: báo cáo & xuất tệp (Jasper, font, bucket)

> Liên quan: `docs/03-dev/specs/export-reporting.md` (SPEC-export-reporting), `...-templates.md` (Part T).

## 1. Build JasperReports 7

- Dependency: `jasperreports`, `-pdf`, `-excel-poi`, `-fonts`, `-charts` cùng version `${jasperreports.version}` (pom.xml). Lần build đầu **cần online** (hoặc mirror nội bộ); sau đó `mvn -o` chạy được từ `~/.m2`.
- `.jrxml` (định dạng **JR7**, Jaspersoft Studio 7; không mở được bằng Studio 6) nằm ở `backend/src/main/resources/reports/`. Chúng được precompile thành `.jasper` ở phase `process-classes` bằng `exec-maven-plugin` gọi `com.aismartstore.backend.reporting.build.JasperPrecompiler` (cần `jasperreports-jdt` làm plugin dependency). Runtime chỉ nạp `.jasper` từ `target/classes/reports/`.
- Cấm khai báo query trong `.jrxml` (`<query>`/`<queryString>`): `JasperPrecompiler` và `JrxmlGuardTest` (chạy ở `mvn test`) làm hỏng build. Dữ liệu chỉ vào Jasper qua `JRBeanCollectionDataSource` (xem `ReportRenderService`).
- Thêm/sửa mẫu: sửa `.jrxml`, thêm mã mẫu vào `ReportTemplateRegistry` + dòng seed trong `report_template` (migration mới), thêm nhánh trong `ReportQueryService`. Không có API upload mẫu.

## 2. Font (AC-26)

- File `.ttf` đã commit ở `backend/src/main/resources/fonts/` (DejaVu Sans = họ "AISS Sans" cho nội dung, Liberation Serif cho tiêu đề; giấy phép ở `fonts/LICENSE.txt`). `fonts/fonts.xml` khai báo họ font, `pdfEmbedded=true`, `Identity-H`; `jasperreports_extension.properties` đăng ký file đó; `jasperreports.properties` đặt `net.sf.jasperreports.awt.ignore.missing.font=false` để thiếu font là lỗi ngay thay vì thay font không có glyph tiếng Việt.
- Thêm font mới: bỏ `.ttf` vào `fonts/`, thêm `<fontFamily>` vào `fonts.xml`, dùng tên họ đó trong `.jrxml`. Font phải có glyph tiếng Việt (kiểm bằng `ReportRenderIT`: PDFBox đọc lại chữ có dấu + font nhúng).
- Image runtime cần `fontconfig freetype ttf-dejavu` (Alpine; Ubuntu: `fontconfig libfreetype6 fonts-dejavu-core`) vì AWT headless vẫn cần chúng để dựng biểu đồ và nạp TTF. Đã thêm vào `backend/Dockerfile` và `Dockerfile.dev`. Thiếu -> lần xuất đầu lỗi `Fontconfig head is null`/`UnsatisfiedLinkError libfreetype`.

## 3. Lưu trữ tệp xuất (Q2)

- Bucket riêng `reports` (cấu hình `S3_REPORTS_BUCKET`, mặc định `reports`), key `<exportId>.<ext>` (không lặp tiền tố bucket). Backend tự `ensureBuckets` khi khởi động.
- **Trước deploy stg/prod:** identity S3 của backend phải có Read/Write/Delete/List trên bucket `reports`. `seaweedfs/s3.json` dev đã cấp `Admin` cho identity `backend`; stg/prod kiểm tra lại. Bucket `documents` vẫn WORM (NFR-09), không dùng cho tệp xuất.
- Rollback: xoá tay cả bucket `reports` (không ảnh hưởng `documents`).

## 4. Vận hành

- Cấu hình (System Config, đổi không cần deploy): `reporting.export_ttl_days` (7), `reporting.max_rows_per_export` (100000), `reporting.max_concurrent_exports` (10), `reporting.max_exports_per_user` (3), `reporting.query_timeout_seconds` (15), `reporting.max_buckets` (400).
- Property tắt worker (test/ops): `app.reporting.runner-enabled=false`, `app.reporting.cleanup-enabled=false`; chu kỳ: `app.reporting.runner-poll-ms` (5000), `app.reporting.cleanup-interval-ms` (3600000).
- Log: `report.run ...`, `report.export id= status= ...`, `report export cleanup: scanned= expired= errors=`; WARN khi có job PENDING > 10 phút, job bị reclaim, timeout truy vấn.
- Migration V36 tạo index thường (không CONCURRENTLY) trên `document`/`digitization_run`: khoá ghi vài giây–chục giây ở ~1M dòng; deploy ngoài giờ cao điểm. Cần kiểm tra checksum Flyway trên stg (đã có tiền lệ drift).
- Đo hiệu năng `[ĐX]` (AC-19/AC-27): `backend/scripts/report-perf-seed.sql` seed ~1M document + danh sách `EXPLAIN (ANALYZE, BUFFERS)` cho các truy vấn lõi; ghi kết quả vào PR.
