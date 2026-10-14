# User Story: export-reporting

> File: `docs/02-ba/stories/export-reporting.md` — Owner: BA
> Nguồn kiến trúc: `docs/01-sa/architecture/export-reporting.md` (ARCH-export-reporting, Status: Approved)
> Status: Approved
> Story ID: `STORY-export-reporting`
> Priority: Must (FR-30, FR-31)
> Estimate: L

## User Story

**As a** cán bộ (R1) hoặc lãnh đạo (R2)
**I want** lập báo cáo thống kê theo mẫu có sẵn (lọc theo nhóm, khoảng thời gian, lĩnh vực/nhãn) và xuất ra PDF / Excel / Word
**So that** tôi có số liệu đúng trong phạm vi quyền truy cập của mình để in hoặc chia sẻ nội bộ phục vụ báo cáo công tác.

## Acceptance Criteria

Quy ước:
- "Phạm vi" = `BusinessDataScope` (FR-08).
- Kỳ báo cáo (cả 3 mẫu): `from`/`to` là ngày theo giờ `Asia/Ho_Chi_Minh` (DB lưu UTC), khoảng nửa mở `[from 00:00, to+1 00:00)`.
- Chỉ tiêu "số tài liệu" = `COUNT(DISTINCT document.id)`, **chỉ tính tài liệu còn trong kho lúc lập báo cáo** (`document.deleted_at IS NULL`, không ngoại lệ), dùng nguyên `BusinessDataScope.forDocuments`. Tài liệu đã xoá (kể cả đang trong thùng rác) không được tính ở bất kỳ chỉ tiêu nào; tài liệu được phục hồi được tính lại như chưa từng xoá.
- "Tồn kho tại mốc T" = tài liệu còn sống có `created_at < T`.
- Tệp kho cá nhân không bao giờ được tính.

### A. Quyền truy cập và phạm vi (ADR-5)

- [ ] **AC-1:** (ADR-5)
  Given người dùng có vai trò R1 (CAN_BO) hoặc R2 (LANH_DAO) được cấp quyền báo cáo
  When họ mở `/reports`, chạy báo cáo, lưu bộ tham số, tạo xuất tệp và tải tệp của mình
  Then tất cả thao tác thành công; R1 và R2 có quyền như nhau.
- [ ] **AC-2:** (ADR-5, NFR Security)
  Given người dùng có vai trò R3 hoặc R4 (hoặc không có ngữ cảnh người dùng)
  When gọi bất kỳ endpoint nào của module báo cáo
  Then hệ thống trả 403, không thực hiện truy vấn, và ghi `report.access_denied`.
- [ ] **AC-3:** (ADR-5, ADR-1)
  Given hai người dùng có phạm vi dữ liệu khác nhau
  When cả hai chạy cùng một mẫu với cùng tham số
  Then mỗi người chỉ nhận số liệu tính trên tài liệu trong phạm vi của mình (có thể khác nhau), và không con số nào (kể cả số đếm) phản ánh tài liệu ngoài phạm vi; không có tuỳ chọn nào bỏ qua phạm vi.
- [ ] **AC-4:** (ADR-5)
  Given một báo cáo được hiển thị trên màn hình hoặc nằm trong tệp xuất
  When người dùng xem
  Then có dòng "Số liệu trong phạm vi truy cập của <người lập> tại <thời điểm>".
- [ ] **AC-5:** (ADR-5, ADR-6)
  Given quyền báo cáo gồm 2 permission trong `role_permission`: `report.read` (xem danh mục mẫu, chạy báo cáo, lưu/mở/đổi tên/xoá báo cáo đã lưu) và `report.export` (tạo job xuất, xem trạng thái, lịch sử, tải tệp), cả hai được seed cho `CAN_BO` và `LANH_DAO`
  When R3 thu hồi hoặc cấp từng quyền cho `CAN_BO`/`LANH_DAO` qua màn RBAC
  Then quyền truy cập báo cáo của vai trò đó thay đổi tương ứng (ví dụ thu hồi riêng `report.export` thì vẫn xem được báo cáo nhưng gọi endpoint xuất/tải bị 403); không vai trò nào sửa được danh mục mẫu chung.
  Và: `report.read`/`report.export` được đánh dấu **không gán được cho `QUAN_TRI`** trong danh mục permission; cố gán qua màn RBAC/API → 400 và không thay đổi gì (bảo đảm AC-2, NFR-04 tách biệt nhiệm vụ).

### B. Danh mục mẫu và tham số (ADR-4)

- [ ] **AC-6:** (ADR-4)
  Given người dùng mở trang báo cáo
  When xem danh sách mẫu
  Then thấy đúng 3 mẫu: `REPO_SUMMARY`, `DIGITIZATION_PROGRESS`, `DOC_BY_CLASSIFICATION`, kèm tên, mô tả, tham số; không có chức năng upload/sửa/tạo mẫu.
- [ ] **AC-7:** (ADR-4)
  Given người dùng chọn mẫu
  When điền tham số `from`/`to` (bắt buộc), `groupIds[]`, `topicTagIds[]`, `docTypeTagIds[]`, `folderId`, `granularity` (`day|month|quarter|year`)
  Then các tham số này được chấp nhận ở cả 3 mẫu và lọc kết quả tương ứng (lọc theo nhóm bằng `groupIds[]`, không có mẫu riêng theo nhóm).
- [ ] **AC-8:** (ADR-4)
  Given màn hình chọn kỳ
  When người dùng bấm chọn nhanh Tháng / Quý / 6 tháng / Năm
  Then `from`/`to` được điền sẵn, người dùng vẫn sửa được thành khoảng ngày tuỳ ý, và `granularity` được gợi ý theo độ dài kỳ.
- [ ] **AC-9:** (ADR-4, FR-30)
  Given tham số thiếu hoặc sai (thiếu `from`/`to`, `from > to`, `granularity` không hợp lệ, id không tồn tại)
  When chạy báo cáo
  Then hệ thống trả 400 kèm thông báo chỉ rõ cách sửa và không chạy truy vấn. Id nhóm/nhãn/thư mục tồn tại nhưng nằm ngoài phạm vi của người dùng được xử lý giống hệt id không tồn tại (cùng mã lỗi, cùng thông báo), để không lộ sự tồn tại của dữ liệu ngoài phạm vi (fail-closed, ADR-5).
- [ ] **AC-10:** (ADR-4, FR-30)
  Given tham số hợp lệ nhưng không có dữ liệu trong phạm vi
  When chạy báo cáo
  Then hiển thị báo cáo rỗng kèm cảnh báo "không có dữ liệu", không phải lỗi.
- [ ] **AC-11:** (ADR-4)
  Given người dùng R1/R2 đã nhập bộ tham số
  When lưu với một tên, sau đó mở lại, đổi tên hoặc xoá
  Then bộ tham số lưu vào "báo cáo đã lưu" riêng của người đó; người khác không thấy và không dùng được; mở lại sẽ điền sẵn tham số.
  Ràng buộc: tối đa 50 báo cáo đã lưu/người (vượt → 400 kèm thông báo); tên 1–100 ký tự, duy nhất trong phạm vi một người, không phân biệt hoa thường (trùng → 400); khi mở lại mà nhóm/nhãn/thư mục được tham chiếu đã bị xoá hoặc nằm ngoài phạm vi hiện tại, hệ thống bỏ id đó khỏi tham số và hiển thị một cảnh báo chung "một số tham số không còn khả dụng và đã được bỏ", không phân biệt đã xoá hay ngoài phạm vi (nhất quán AC-9), không báo lỗi.

### C. Nội dung 3 mẫu MVP (ADR-4, ADR-1)

- [ ] **AC-12:** (ADR-4) Mẫu `REPO_SUMMARY`
  Given kỳ `[from, to+1)` hợp lệ
  When chạy mẫu
  Then kết quả gồm: Bảng 1 (số tài liệu đầu kỳ, nạp mới, cuối kỳ); Bảng 2 (theo nhóm loại tệp suy từ `mime_type`: văn bản/PDF, ảnh, âm thanh, video, khác — số lượng, dung lượng, tổng thời lượng âm thanh/video); Bảng 3 + biểu đồ (nạp mới theo `granularity`: số lượng, dung lượng, theo nhóm loại tệp); Bảng 4 (số hoá: đã xong / đang xử lý / chờ duyệt / lỗi từ `document_digitization_status.overall_status`).
  Định nghĩa (kỳ và "tồn kho tại mốc T" theo Quy ước):
  - Bảng 1: đầu kỳ = tồn kho tại `from 00:00`; cuối kỳ = tồn kho tại `to+1 00:00`; nạp mới = tài liệu còn sống có `created_at` trong kỳ. Bảng 1 luôn cân: đầu kỳ + nạp mới = cuối kỳ. Không có chỉ tiêu "xoá trong kỳ".
  - Bảng 2 thống kê trên tồn kho cuối kỳ (cùng tập tài liệu với số cuối kỳ của Bảng 1). Nhóm loại tệp theo tiền tố MIME (hằng số trong code, có unit test): văn bản/PDF = `application/pdf`, `text/*`, `application/msword`, `application/vnd.ms-*`, `application/vnd.openxmlformats-*`, `application/vnd.oasis.opendocument.*`, `application/rtf`; ảnh = `image/*`; âm thanh = `audio/*`; video = `video/*`; khác = phần còn lại.
  - Bảng 4 là ảnh chụp trạng thái số hoá **tại thời điểm chạy báo cáo** (không lọc theo kỳ, chỉ tài liệu còn sống trong phạm vi và bộ lọc), có ghi chú "trạng thái tại thời điểm lập báo cáo". Ánh xạ: đã xong = `confirmed`; đang xử lý = `queued`, `processing`; chờ duyệt = `pending_review`, `in_review`; lỗi = `failed`; `not_applicable` không được tính.
- [ ] **AC-13:** (ADR-4)
  Given mẫu `REPO_SUMMARY`
  When hiển thị hoặc xuất
  Then có chú thích: "Số liệu chỉ tính tài liệu đang còn trong kho lúc lập báo cáo; tài liệu đã xoá (kể cả đang trong thùng rác hoặc đã xoá vĩnh viễn) không được tính, tài liệu được phục hồi được tính lại như chưa từng xoá. Vì vậy số liệu của kỳ quá khứ có thể thay đổi giữa các lần lập."
- [ ] **AC-14:** (ADR-4) Mẫu `DIGITIZATION_PROGRESS`
  Given kỳ hợp lệ (lọc theo `digitization_run.created_at`)
  When chạy mẫu
  Then hàng = tác vụ `ocr`, `stt`, `video_summary` (không có `sed`); cột = đang xử lý (`queued|running|failed_retryable`), lỗi (`failed_permanent`), chờ duyệt (`pending|in_review`), quá hạn duyệt (chờ duyệt và `review_deadline_at < now()`), đã xác nhận tách theo `confirm_source` (duyệt giữ nguyên / duyệt có sửa / tự xác nhận); run `superseded`/`void` bị loại; có biểu đồ cột chồng theo tác vụ.
- [ ] **AC-15:** (ADR-4) Mẫu `DOC_BY_CLASSIFICATION`
  Given kỳ hợp lệ (thống kê trên tồn kho cuối kỳ, tức tồn kho tại `to+1 00:00` theo Quy ước; nhãn gắn trên tài liệu lấy theo trạng thái hiện tại vì hệ thống không lưu lịch sử gắn nhãn)
  When chạy mẫu
  Then bảng chính là ma trận chủ đề (`kind=topic`) × loại tài liệu (`kind=doc_type`) có dòng/cột "Chưa phân loại"; bảng phụ theo từng chủ đề, theo từng loại + biểu đồ; nhãn `active=false` vẫn hiển thị nếu có tài liệu; có chú thích "một tài liệu có thể gắn nhiều nhãn nên tổng các dòng có thể lớn hơn tổng số tài liệu".
- [ ] **AC-16:** (ADR-1)
  Given tài liệu có nhiều nhãn / nhóm / segment, hoặc đang ở thùng rác
  When tính chỉ tiêu "số tài liệu"
  Then mỗi tài liệu chỉ đếm một lần (`COUNT(DISTINCT document.id)`), tài liệu đã xoá mềm luôn bị loại trừ ở mọi chỉ tiêu, và tệp kho cá nhân không được tính.
- [ ] **AC-17:** (ADR-1)
  Given số liệu báo cáo và số kết quả trên màn tìm kiếm có thể lệch nhau
  When người dùng so sánh
  Then báo cáo luôn lấy từ PostgreSQL (không từ OpenSearch) và UI có ghi chú rằng đây là số chính thức, khác với `total` gần đúng của tìm kiếm.

### D. Xem báo cáo FR-30 (ADR-2, NFR Performance)

- [ ] **AC-18:** (ADR-2)
  Given tham số hợp lệ
  When người dùng chạy báo cáo (`POST /reports/{templateCode}/run`)
  Then số liệu trả về đồng bộ dạng JSON, phần chi tiết (nếu có) phân trang, và hiển thị tiêu đề, kỳ báo cáo, bộ lọc đã chọn.
- [ ] **AC-19:** (NFR Performance) `[ĐX]`
  Given khoảng thời gian ≤ 12 tháng trên ~1 triệu tài liệu
  When chạy báo cáo
  Then p95 ≤ 3 giây.
  Ghi chú: đây là mục tiêu, được đo khi kiểm thử tải; không chặn nghiệm thu chức năng.
- [ ] **AC-20:** (NFR Performance)
  Given truy vấn vượt `reporting.query_timeout_seconds` (mặc định 15 giây)
  When đến timeout
  Then request kết thúc với lỗi kèm gợi ý thu hẹp bộ lọc, không treo, và truy vấn không giữ transaction dài trên OLTP.

### E. Xuất tệp FR-31 (ADR-2, ADR-3, ADR-6)

- [ ] **AC-21:** (ADR-2)
  Given người dùng đã chạy/đặt tham số báo cáo hợp lệ
  When chọn định dạng PDF, Excel (XLSX) hoặc Word (DOCX) và bấm xuất (`POST /reports/exports`)
  Then hệ thống trả 202 kèm `exportId`, tạo bản ghi `report_export` trạng thái `PENDING`, không chặn thao tác khác.
- [ ] **AC-22:** (ADR-2, ADR-6)
  Given job xuất tồn tại
  When worker nền xử lý
  Then trạng thái đi qua `PENDING → RUNNING → DONE`, hoặc `FAILED` (kèm lý do), hoặc `EXPIRED`; UI poll `GET /reports/exports/{id}` và hiển thị đúng từng trạng thái.
- [ ] **AC-23:** (ADR-2, NFR Availability)
  Given backend khởi động lại khi job đang `PENDING`/`RUNNING`
  When runner chạy lại (claim theo lease)
  Then job không bị mất và được xử lý tiếp (trạng thái nằm trong DB).
- [ ] **AC-24:** (ADR-3, FR-31)
  Given job `DONE`
  When người tạo tải (`GET /reports/exports/{id}/download`)
  Then nhận tệp đúng định dạng đã chọn, bố cục đúng mẫu, mở được bằng phần mềm văn phòng thông dụng.
- [ ] **AC-25:** (ADR-3, NFR Khác)
  Given tệp xuất bất kỳ
  When mở tệp
  Then có header/footer gồm mã và tên mẫu, đầy đủ tham số lọc, người lập, thời điểm sinh, câu "số liệu trong phạm vi truy cập của người lập"; thể thức tối giản, không có tên cơ quan, chỗ ký, số/ký hiệu văn bản.
- [ ] **AC-26:** (ADR-3, rủi ro font)
  Given báo cáo chứa tiếng Việt có dấu
  When xuất PDF, XLSX, DOCX
  Then toàn bộ ký tự hiển thị đúng (PDF embed font DejaVu Sans / Liberation Serif đóng gói trong image, không phụ thuộc font host).
- [ ] **AC-27:** (ADR-3, NFR Performance) `[ĐX]`
  Given báo cáo ≤ 50.000 dòng chi tiết
  When xuất tệp
  Then job hoàn tất p95 ≤ 60 giây.
  Ghi chú: đây là mục tiêu, được đo khi kiểm thử tải; không chặn nghiệm thu chức năng. Cả 3 mẫu MVP đều xuất qua Jasper exporter, không dùng nhánh POI SXSSF.
- [ ] **AC-28:** (ADR-6, FR-31)
  Given số dòng vượt `reporting.max_rows_per_export` (mặc định 100.000)
  When job chạy
  Then job chuyển `FAILED` với thông báo "báo cáo quá lớn, hãy thu hẹp khoảng thời gian/bộ lọc"; không sinh tệp, không chia phần.
- [ ] **AC-29:** (ADR-6, FR-31)
  Given lỗi kết xuất (ví dụ lỗi render)
  When job `FAILED`
  Then người dùng thấy thông báo lỗi và có thể bấm chạy lại (retry) từ lịch sử xuất; chạy lại tạo job mới (`exportId` mới, cùng mẫu/tham số/định dạng) và có bản ghi audit `report.export` riêng, job cũ giữ nguyên `FAILED`; job mới tính vào giới hạn của AC-30.
- [ ] **AC-30:** (ADR-6, NFR Scalability)
  Given người dùng đã có 3 job đang chạy (`reporting.max_exports_per_user`) hoặc toàn hệ thống đã có 10 job đồng thời (`reporting.max_concurrent_exports`)
  When yêu cầu xuất thêm
  Then yêu cầu bị từ chối ngay với HTTP 429, không tạo bản ghi `report_export`, kèm thông báo rõ ràng phân biệt hai trường hợp ("bạn đang có <N> tệp đang tạo, chờ xong rồi thử lại", với N lấy từ `reporting.max_exports_per_user` / "hệ thống đang bận, thử lại sau"). Không ghi audit `report.export` cho yêu cầu bị từ chối 429 vì không có job nào được tạo. Job đếm vào giới hạn là job ở trạng thái `PENDING` hoặc `RUNNING`.
- [ ] **AC-31:** (ADR-6)
  Given tệp xuất của người A
  When người B (kể cả cùng nhóm hoặc cùng vai trò) gọi tải hoặc xem trạng thái
  Then trả 403 và ghi `report.access_denied`; chỉ `requested_by` tải được.
- [ ] **AC-32:** (ADR-6)
  Given màn `/reports/exports`
  When người dùng mở
  Then thấy lịch sử xuất của chính mình (mẫu, định dạng, trạng thái, thời điểm, hạn tải) và tải được tệp `DONE`.
- [ ] **AC-33:** (ADR-6)
  Given tệp quá `reporting.export_ttl_days` (mặc định 7 ngày)
  When job dọn định kỳ chạy
  Then object trong `reports/` bị xoá, bản ghi chuyển `EXPIRED`, tải về trả thông báo hết hạn; tệp xuất không tính vào hạn mức kho chung/cá nhân và không xuất hiện trong kho tài liệu.

### F. Nhật ký (ADR-7)

- [ ] **AC-34:** (ADR-7)
  Given người dùng chạy báo cáo, yêu cầu xuất, tải tệp hoặc bị từ chối
  When thao tác hoàn tất
  Then `audit_log` có bản ghi `report.generate`, `report.export` (kèm kết quả `SUCCESS/FAILED`), `report.download`, `report.access_denied` tương ứng, ghi trong transaction nghiệp vụ. Yêu cầu xuất bị từ chối 429 (AC-30) không sinh bản ghi `report.export`.
- [ ] **AC-35:** (ADR-7)
  Given bản ghi audit của module báo cáo
  When kiểm tra metadata
  Then chỉ chứa allowlist (`template_code`, `format`, tham số đã chuẩn hoá, `row_count`, `duration_ms`, `export_id`); không chứa nội dung số liệu, tên tài liệu hay chuỗi tự do dài.

### G. Bảo mật mẫu và kiểm thử bắt buộc (ADR-3, ADR-4)

- [ ] **AC-36:** (ADR-3, ADR-4)
  Given danh mục mẫu đóng
  When xét hệ thống
  Then không có API upload/sửa template; template `.jrxml` nằm trong repo, precompile thành `.jasper` lúc build; build fail nếu `.jrxml` có `<queryString>`; dữ liệu vào Jasper chỉ qua `JRBeanCollectionDataSource` từ DTO đã tổng hợp.
- [ ] **AC-37:** (rủi ro cao ARCH mục 6)
  Given bộ test hồi quy
  When chạy cho từng mẫu với hai người dùng khác phạm vi và dữ liệu có chủ đích (tài liệu nhiều nhãn/nhóm, đã xoá mềm)
  Then số liệu khác nhau đúng kỳ vọng theo phạm vi, fail-closed khi thiếu ngữ cảnh người dùng, và không đếm trùng.
- [ ] **AC-38:** (NFR Availability)
  Given module báo cáo lỗi hoặc job xuất bị kẹt
  When người dùng nạp dữ liệu, tra cứu, tìm kiếm
  Then các chức năng này không bị ảnh hưởng; báo cáo không phụ thuộc GPU/OpenSearch.

## Ngoài phạm vi (Out of scope)

- Mẫu báo cáo do người dùng tạo/upload; report designer trong ứng dụng.
- Danh mục tài liệu chi tiết (danh sách dòng lớn, chia phần), kết quả công tác theo cán bộ, thống kê lượt xem/tải/tìm kiếm (nguồn `audit_log`) — để giai đoạn sau.
- Báo cáo định kỳ tự động theo lịch; dashboard realtime, BI tự phục vụ, biểu đồ tuỳ biến.
- Chia sẻ tệp xuất trong hệ thống hoặc lưu tệp xuất vào kho chung; chia phần tệp quá lớn.
- Cache kết quả báo cáo, bảng rollup, OLAP (tối ưu về sau nếu đo tải vượt NFR).
- Thống kê tác vụ `sed`; thống kê tệp kho cá nhân.
- Nhánh xuất XLSX bằng Apache POI SXSSF (chỉ dành cho mẫu danh sách thô lớn ở giai đoạn sau, theo Quyết định 3 SA).
- Xếp hàng job xuất khi vượt giới hạn (MVP từ chối 429, xem AC-30).
- Cập nhật SRS mục 3 (ô R2: `R` thành `X`) — việc tài liệu, ngoài story này.

## Phụ thuộc (Dependencies)

- `rbac-permission-editing`: danh mục permission cần hỗ trợ cờ "không gán được cho `QUAN_TRI`" cho `report.*` (AC-5).
- Story/feature khác phải xong trước: `rbac-group-scope` (`BusinessDataScope`), `group-management-sharing` (group, `document_group_share`), `classification-notes` (nhãn `topic`/`doc_type`), `audit-logging` (catalog sự kiện), `delete-restore-trash` (`deleted_at`), `digitization-results-store` (`document_digitization_status`), `r2-business-parity`, `rbac-permission-editing`.
- Hạ tầng tái dùng: `ObjectStorageService`, mẫu `SourceSeparationJobRunner` / `UploadCleanupJob`, `system_config` (`reporting.*`).
- Index thống kê cần bổ sung: `document(created_at)`, `document(created_by, created_at)`, partial index `WHERE deleted_at IS NULL`.
- Tham chiếu quyết định kiến trúc: ADR-1 (PostgreSQL trực tiếp), ADR-2 (xem đồng bộ, xuất bất đồng bộ), ADR-3 (JasperReports + POI SXSSF), ADR-4 (danh mục mẫu đóng), ADR-5 (phạm vi, R1=R2), ADR-6 (vòng đời tệp), ADR-7 (audit) trong ARCH-export-reporting.

## Câu hỏi mở cho DEV

- Ghi chú trạng thái: file kiến trúc đã `Status: Approved`, mọi câu hỏi mở của SA đã chốt.
- [x] AC-30: khi vượt giới hạn job/người hoặc toàn hệ thống, chọn từ chối ngay (HTTP 429/409) hay xếp hàng `PENDING`? → **Chốt:** từ chối cả hai trường hợp với 429, thông báo phân biệt; không xếp hàng (cập nhật: AC-30, Ngoài phạm vi)
- [x] AC-12: định nghĩa chính xác "đầu kỳ"/"cuối kỳ" và "chờ duyệt" của `overall_status` → **Chốt:** giờ `Asia/Ho_Chi_Minh`, khoảng nửa mở; đầu/cuối kỳ = tồn kho tài liệu còn sống tại mốc (đã thay bằng quyết định "review cuối" bên dưới); ánh xạ `overall_status` theo spec `digitization-results-store` (cập nhật: AC-12, AC-16)
- [x] AC-12: cách phân loại `mime_type` vào 5 nhóm → **Chốt:** theo tiền tố MIME, hằng số trong code có unit test (cập nhật: AC-12)
- [x] AC-27/AC-19: số NFR `[ĐX]` và ngưỡng POI SXSSF → **Chốt:** giữ làm mục tiêu đo khi kiểm thử tải, không chặn nghiệm thu chức năng; không làm nhánh SXSSF cho MVP (cập nhật: AC-19, AC-27, Ngoài phạm vi)
- [x] AC-11: quy tắc đặt tên, giới hạn "báo cáo đã lưu", tham chiếu bị xoá → **Chốt:** tối đa 50/người, tên 1–100 ký tự duy nhất theo người (không phân biệt hoa thường), id mất thì bỏ và cảnh báo (cập nhật: AC-11)
- [x] AC-29: "chạy lại" tạo job mới hay reset job cũ; có tính vào giới hạn không → **Chốt:** tạo job mới (`exportId` mới), job cũ giữ `FAILED`, có tính vào giới hạn (cập nhật: AC-29)
- [x] AC-5: tên permission và seed cho `CAN_BO`/`LANH_DAO` → **Chốt:** `report.read` + `report.export`, seed cho cả `CAN_BO` và `LANH_DAO`, theo quy ước `<domain>.<verb>` (cập nhật: AC-5)
- [x] (review cuối) Đầu/cuối kỳ Bảng 1 và việc đếm tài liệu đã xoá mềm (`BusinessDataScope` luôn loại `deleted_at IS NOT NULL`) → **Chốt:** bỏ hoàn toàn việc đếm tài liệu đã xoá mềm, bỏ chỉ tiêu "xoá trong kỳ"; Bảng 1 = đầu kỳ + nạp mới = cuối kỳ trên tài liệu còn sống; dùng nguyên `BusinessDataScope` (cập nhật: Quy ước, AC-12, AC-13, AC-16; SA Quyết định 4)
- [x] (review cuối) Kỳ áp dụng cho Bảng 2, Bảng 4, `DOC_BY_CLASSIFICATION` → **Chốt:** Bảng 2 và `DOC_BY_CLASSIFICATION` tính trên tồn kho cuối kỳ (tài liệu còn sống `created_at < to+1`); Bảng 4 là ảnh chụp tại thời điểm chạy (cập nhật: AC-12, AC-15)
- [x] (review cuối) Phục hồi từ thùng rác làm đổi số liệu kỳ cũ → **Chốt:** chỉ thêm chú thích bắt buộc (cập nhật: AC-13)
- [x] (review cuối) Chặn R3 tự gán `report.*` cho `QUAN_TRI` → **Chốt:** đánh dấu không gán được cho `QUAN_TRI`, cố gán → 400 (cập nhật: AC-5, Phụ thuộc)
- [x] (review cuối) Ký hiệu kỳ AC-12 và cảnh báo AC-11 → **Chốt:** `[from, to+1)`; cảnh báo chung không phân biệt (cập nhật: AC-11, AC-12)
- [x] (review cuối) Định nghĩa kỳ chỉ nằm ở AC-12 → **Chốt:** chuyển lên Quy ước chung cho cả 3 mẫu (cập nhật: Quy ước)
- [x] (review cuối) Id ngoài phạm vi ở AC-9 → **Chốt:** xử lý giống id không tồn tại (cập nhật: AC-9)
- [x] (review cuối) Thông báo AC-30 và audit khi 429 → **Chốt:** số job lấy từ cấu hình; 429 không ghi `report.export` (cập nhật: AC-30, AC-34)
