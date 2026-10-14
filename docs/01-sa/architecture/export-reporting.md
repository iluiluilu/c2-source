# Architecture: export-reporting

> File: `docs/01-sa/architecture/export-reporting.md` — Owner: SA
> Status: Approved

## 1. Bối cảnh / Vấn đề

- **FR-30** (`dac_ta_yeu_cau_chuc_nang_phi_chuc_nang.adoc`, mục 2.10 + đặc tả
  chi tiết): lập báo cáo thống kê **theo mẫu**, lọc theo **nhóm** (FR-28/29),
  **khoảng thời gian**, **lĩnh vực/nhãn**; số liệu **giới hạn theo phạm vi
  quyền của người lập** (FR-08); mẫu báo cáo "cấu hình được".
- **FR-31**: xuất kết quả báo cáo ra **PDF / Excel / Word** đúng bố cục để in
  hoặc chia sẻ nội bộ; áp quyền theo vai trò/nhóm/phạm vi; **ghi nhật ký**;
  ngoại lệ "báo cáo quá lớn → cảnh báo / chia phần".
- Ma trận vai trò (SRS mục 3, dòng 849): `Báo cáo & xuất tệp (FR-30–31) | X (R1) | R (R2) | – | –`. **Đã điều chỉnh theo quyết định người dùng
  (2026-10-01):** R2 có toàn bộ chức năng nghiệp vụ của R1 (xem
  `r2-business-parity.md`) → **R1 và R2 đều tạo/chạy/xuất**, R3/R4 không
  tham gia. SRS mục 3 cần cập nhật ô R2 từ `R` thành `X`.
- **Hiện trạng code:** `backend/pom.xml` **chưa có bất kỳ dependency báo cáo
  hay kết xuất tệp nào** (không có POI/JasperReports/iText/PDFBox/docx4j); thư
  mục `backend/src/main/java/.../` **chưa có module `report`/`reporting`**.
  Toàn bộ tính năng là xây mới. Hạ tầng có thể tái dùng: `ObjectStorageService`
  (SeaweedFS/S3), mẫu job nền `@Scheduled` (`SourceSeparationJobRunner`,
  `UploadCleanupJob`), `outbox`, `BusinessDataScope`, `AuditService`,
  `system_config`.

**In-scope**

- Kiến trúc tổng hợp số liệu (nguồn dữ liệu, nơi tính, chiến lược truy vấn).
- Mô hình "mẫu báo cáo" + tham số; ranh giới của từ "cấu hình được".
- Cơ chế đồng bộ/bất đồng bộ, vòng đời tệp xuất, lưu trữ và tải về.
- Lựa chọn thư viện kết xuất PDF/Excel/Word chạy được **offline**.
- Diễn giải quyền R1/R2 cho báo cáo; điểm áp phạm vi FR-08; audit.

**Out-of-scope (chỉ nêu điểm giao tiếp — contract)**


| Chủ đề                                                                          | Thuộc slug                           | Contract của slug này                                                                                                                                                                                                           |
| ---------------------------------------------------------------------------------- | ------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Phạm vi dữ liệu FR-08                                                           | `rbac-group-scope` (Approved)         | **Tái dùng nguyên vẹn** `BusinessDataScope` / `DocumentScopeGuard`; tuyệt đối không định nghĩa phạm vi mới cho báo cáo                                                                                             |
| Nhóm & chia sẻ                                                                   | `group-management-sharing` (Approved) | Là**bên tiêu thụ** `group` / `group_member` / `document_group_share`; không tạo khái niệm nhóm mới. Slug đó đã cam kết "dữ liệu truy vấn được, có index (`document_group_share(group_id, shared_at)`)"      |
| Nhãn "lĩnh vực"                                                                 | `classification-notes` (QĐ 1–3)     | "Lĩnh vực" = taxonomy nhãn đã chốt`kind ∈ {topic, doc_type}`; tiêu thụ API tổng hợp `count(document) GROUP BY tag` đã được cam kết ở mục "hợp đồng cho báo cáo" — **không định nghĩa lại taxonomy** |
| Nhật ký                                                                          | `audit-logging` (Approved, QĐ 3–4)  | Dùng taxonomy`<domain>.<verb>`, metadata theo allowlist, ghi ở tầng service trong transaction nghiệp vụ                                                                                                                      |
| Xoá mềm / thùng rác                                                            | `delete-restore-trash` (QĐ 2)        | `document.deleted_at` là nguồn sự thật duy nhất; **mọi truy vấn thống kê trên bảng phái sinh bắt buộc join `document`** — cấm đếm độc lập trên bảng phái sinh                                             |
| Tìm kiếm                                                                         | `search-fulltext` / `search-semantic` | Báo cáo**không** lấy số liệu từ OpenSearch (xem Quyết định 1). Duyệt số lượng lớn nếu cần dùng `search_after`+PIT theo `search-fulltext`                                                                 |
| Trạng thái số hoá                                                              | `digitization-results-store`          | Đọc read-model`document_digitization_status`, không tự JOIN segment                                                                                                                                                           |
| UI chi tiết màn hình báo cáo, user story, AC                                  | BA/DEV                                | —                                                                                                                                                                                                                                |
| Dashboard realtime, BI tự phục vụ, biểu đồ tùy biến do người dùng dựng | Không thuộc SRS                     | Không làm                                                                                                                                                                                                                       |

## 2. Ràng buộc (Constraints)

- **Air-gap tuyệt đối** (NFR-04/NFR-11/NFR-16, xem `digitization-pipeline.md`
  mục 2): mọi thư viện kết xuất phải là **mã nguồn mở, chạy hoàn toàn cục bộ**,
  không gọi mạng, không tải font/plugin lúc chạy; license cho phép dùng nội bộ
  không phải mua (ưu tiên Apache-2.0 / LGPL, **tránh AGPL** như iText 7).
- **Font tiếng Việt phải đóng gói offline** vào image backend (PDF cần embed
  font); không được phụ thuộc font của host.
- **Phạm vi dữ liệu là bất biến kiến trúc:** số liệu luôn tính **sau** filter
  `BusinessDataScope`, fail-closed. Không có "chế độ báo cáo toàn hệ thống".
- **R3 không được xem số liệu nghiệp vụ** (tách biệt nhiệm vụ, NFR-04) — R3
  không có quyền ở FR-30/31.
- **PostgreSQL là nguồn sự thật** cho số liệu báo cáo; chỉ mục tìm kiếm là bản
  sao eventually-consistent (share → cập nhật index bất đồng bộ ≤ 60 s,
  `group-management-sharing.md` QĐ 8) ⇒ không dùng làm nguồn con số chính thức.
- **NFR-01/NFR-02:** tác vụ nặng chạy nền, không chặn thao tác người dùng.
- Quy mô đích kế thừa các SA khác: **~1 triệu tài liệu / ~20 triệu segment**.
- Tham số vận hành phải lấy từ `system_config` (FR-01), không hard-code ngưỡng.
- Không thêm thành phần hạ tầng mới (không OLAP DB, không report server riêng)
  — mỗi thành phần mới đều là gánh nặng vận hành trong môi trường cách ly.

## 3. NFR (Non-Functional Requirements)

Số `[ĐX]` là đề xuất của SA (SRS chỉ nêu định tính), cần kiểm chứng bằng đo tải
trên dữ liệu thật.


| Loại                         | Yêu cầu                                                                                                                                                                                                                                                                                                                                                                                                                                                                             |
| ----------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Performance                   | `[ĐX]` Xem báo cáo trên UI (FR-30) **≤ 3 s p95** với khoảng thời gian ≤ 12 tháng trên ~1 triệu tài liệu; timeout cứng **15 s** → trả lỗi kèm gợi ý thu hẹp bộ lọc, không treo request. Kết xuất tệp (FR-31) **luôn chạy nền**, `[ĐX]` ≤ 60 s p95 cho báo cáo ≤ 50.000 dòng chi tiết. Truy vấn thống kê không được giữ transaction dài làm nghẽn OLTP (đặt `statement_timeout` riêng cho connection báo cáo)                     |
| Security                      | Mọi truy vấn tổng hợp bắt buộc đi qua`BusinessDataScope`, **fail-closed** (thiếu ngữ cảnh người dùng → 403, không truy vấn). Tệp xuất **chỉ người tạo job tải được** (tệp đã đóng băng số liệu theo phạm vi của người đó). **Không** cho phép người dùng upload/sửa file template (chống RCE qua expression của report engine). Số liệu tổng hợp không rò rỉ tài liệu ngoài phạm vi kể cả qua con số đếm              |
| Scalability                   | ≤**100.000 dòng/tệp xuất** (`reporting.max_rows_per_export`); vượt ngưỡng → **chỉ cảnh báo**, yêu cầu thu hẹp bộ lọc (đã chốt; đủ cho ngoại lệ FR-31 "cảnh báo/chia phần"), không cố sinh 1 tệp khổng lồ. Chia phần để dành cho mẫu danh sách chi tiết ở giai đoạn sau. ≤ 3 job xuất đang chạy/người (đã chốt), ≤ 10 job đồng thời toàn hệ thống. Sinh XLSX theo kiểu streaming để không giữ toàn bộ dòng trong RAM |
| Availability                  | Job xuất thất bại → trạng thái`FAILED` + lý do, cho **chạy lại** (đúng "lỗi kết xuất → thông báo và cho thử lại"); không mất job khi backend restart (trạng thái nằm ở DB, không ở RAM). Module báo cáo hỏng **không** ảnh hưởng nạp dữ liệu/tra cứu. Không phụ thuộc GPU, không phụ thuộc OpenSearch                                                                                                                                    |
| Khác (truy vết & tái lập) | Mỗi tệp xuất**bắt buộc** nhúng header/footer: mã & tên mẫu, đầy đủ tham số lọc, người lập, thời điểm sinh, và câu ghi rõ *"số liệu trong phạm vi truy cập của người lập"*. Mọi lượt tạo/xuất/tải ghi `audit_log` (FR-09/FR-31)                                                                                                                                                                                                                  |

## 4. Phương án & Quyết định (ADR)

### Quyết định 1: Nơi tổng hợp số liệu — truy vấn trực tiếp PostgreSQL

- **Ngữ cảnh:** cần đếm/nhóm trên ~1 triệu tài liệu, luôn phải áp filter phạm
  vi theo từng người dùng (phạm vi khác nhau ⇒ khó cache kết quả chung).
- **Các phương án xem xét:**
  - A) Truy vấn tổng hợp trực tiếp trên bảng OLTP PostgreSQL, mỗi lần chạy.
  - B) Read-model/bảng rollup (`report_*_daily`) cập nhật theo sự kiện hoặc job
    đêm, báo cáo đọc từ rollup.
  - C) Kho OLAP riêng (ClickHouse/DuckDB) đồng bộ từ Postgres.
  - D) Dùng aggregation của OpenSearch (chỉ mục tìm kiếm đã có `group_ids`).
- **Quyết định:** **A** cho phiên bản hiện tại, với **thiết kế mở sẵn cho B**
  (tách một `ReportQueryService` là điểm duy nhất sinh truy vấn tổng hợp, để sau
  này thay nguồn mà không đụng tầng render/export).
- **Lý do:**
  - Aggregation `COUNT/GROUP BY` trên vài triệu dòng với index đúng là bài toán
    **trong tầm PostgreSQL**; chưa có bằng chứng cần OLAP.
  - **D bị loại vì đúng đắn, không phải vì hiệu năng:** chỉ mục là bản sao trễ
    (≤ 60 s khi chia sẻ) và `total` của search được chốt là **giá trị gần đúng**
    (`search-fulltext.md`) ⇒ không thể dùng làm con số chính thức trong báo cáo
    đem đi in và ký.
  - C thêm một hệ quản trị dữ liệu nữa vào môi trường air-gap (backup, vá lỗi,
    đồng bộ, thêm điểm rò rỉ ACL) cho giá trị chưa chứng minh được.
  - B bị hoãn vì rollup **phải được rollup theo phạm vi**, mà phạm vi thay đổi
    động theo thành viên nhóm/chia sẻ ⇒ rollup an toàn duy nhất là ở mức
    "sự kiện thô + `document_id`", gần như không giảm được chi phí join phạm vi.
- **Hệ quả (trade-off):** phụ thuộc chất lượng index; cần rà soát `EXPLAIN` cho
  từng mẫu báo cáo trước khi phát hành mẫu mới. Bắt buộc bổ sung index phục vụ
  thống kê (đề xuất: `document(created_at)`, `document(created_by, created_at)`,
  `document_tag(tag_id)` (đã có), `document_group_share(group_id, shared_at)`
  (đã cam kết ở `group-management-sharing`), và partial index
  `WHERE deleted_at IS NULL`). Nếu đo tải vượt NFR → kích hoạt phương án B cho
  đúng mẫu bị chậm, không đổi kiến trúc tổng thể.
- **Ràng buộc bắt buộc kèm theo:** mọi thống kê trên dữ liệu phái sinh (segment,
  nhãn, ghi chú, share) **phải join `document`** với `deleted_at IS NULL` —
  hệ quả trực tiếp của `delete-restore-trash.md` QĐ 2. Tệp kho **cá nhân**
  (`personal_file`, FR-14) **không** vào bất kỳ báo cáo nào.

### Quyết định 2: Xem báo cáo đồng bộ — xuất tệp luôn bất đồng bộ

- **Ngữ cảnh:** FR-30 là xem trên màn hình (kết quả tổng hợp nhỏ), FR-31 là kết
  xuất tệp (có thể nặng: render layout, ảnh, hàng chục nghìn dòng). NFR-01 cấm
  chặn thao tác người dùng.
- **Các phương án xem xét:** A) cả hai đồng bộ; B) cả hai bất đồng bộ; C) xem
  đồng bộ + xuất bất đồng bộ; D) hybrid theo ngưỡng (xuất nhỏ thì đồng bộ).
- **Quyết định:** **C**.
  - FR-30: `POST /reports/{templateCode}/run` → trả JSON số liệu ngay (có
    timeout cứng + phân trang phần chi tiết).
  - FR-31: `POST /reports/exports` → **202** + `exportId`; worker nền sinh tệp;
    client poll `GET /reports/exports/{id}`; tải qua
    `GET /reports/exports/{id}/download`.
- **Lý do:** D (hybrid theo ngưỡng) buộc phải nuôi **hai code path** cho cùng
  một chức năng — nguồn lỗi kinh điển và khó test; chi phí một vòng poll nhỏ hơn
  nhiều so với rủi ro request treo/timeout gateway khi báo cáo lớn. B làm hỏng
  trải nghiệm xem nhanh vốn phải tức thời.
- **Hệ quả (trade-off):** UX xuất tệp có thêm bước "đang tạo tệp…"; cần trạng
  thái job + màn hình lịch sử xuất. BA phải viết AC cho trạng thái
  `PENDING/RUNNING/DONE/FAILED/EXPIRED`.
- **Cơ chế chạy nền:** dùng bảng `report_export` + runner `@Scheduled` với
  claim theo lease (đúng mẫu `SourceSeparationJobRunner` đã có), **không** thêm
  queue RabbitMQ mới — tải của báo cáo thấp và không cần fan-out worker.

### Quyết định 3: Thư viện kết xuất — JasperReports làm engine duy nhất

- **Ngữ cảnh:** cần **một** báo cáo ra được **ba** định dạng "đúng bố cục"
  (FR-31), offline, mã nguồn mở, và FR-30 nói mẫu "cấu hình được".
- **Các phương án xem xét:**


  | PA | Thành phần                                                                          | Ưu                                                                                                                                                       | Nhược                                                                                                                                                |
  | -- | ------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------ |
  | A  | **JasperReports Library** (LGPL) — 1 template `.jrxml` → PDF/XLSX/DOCX qua exporter | Một định nghĩa bố cục cho cả 3 định dạng; có bộ thiết kế mẫu (Jaspersoft Studio) phục vụ "mẫu cấu hình được"; thuần Java, offline | Dependency nặng (~30–40 MB kèm phụ thuộc); lịch sử CVE liên quan expression/Groovy; DOCX/XLSX từ layout dạng band có thể lệch tinh chỉnh |
  | B  | **Apache POI** (XLSX+DOCX, Apache-2.0) + **OpenPDF** hoặc **PDFBox** (PDF)           | Nhẹ, kiểm soát hoàn toàn, POI có SXSSF streaming rất tốt cho Excel lớn                                                                           | Phải**code bố cục ba lần** cho mỗi mẫu báo cáo → chi phí tuyến tính theo số mẫu, dễ lệch giữa các định dạng                       |
  | C  | Sinh HTML → convert (openhtmltopdf cho PDF)                                          | Nhanh cho PDF, tái dùng CSS                                                                                                                             | Đường HTML→DOCX/XLSX chất lượng kém, coi như không dùng được                                                                             |
  | D  | LibreOffice headless convert từ ODT/template                                         | Bố cục Word chuẩn nhất                                                                                                                                | Thêm một container/binary nặng vào air-gap, quản lý vòng đời process, khó kiểm soát lỗi/timeout                                           |
  | E  | iText 7                                                                               | Mạnh về PDF                                                                                                                                             | **AGPL** — loại thẳng vì ràng buộc license                                                                                                       |
- **Quyết định:** **A — JasperReports Library** là engine kết xuất chính cho cả
  **PDF, Excel, Word**, với các ràng buộc bắt buộc:

  1. Template `.jrxml` **nằm trong repo**, được **precompile thành `.jasper` lúc
     build** (không compile expression lúc runtime, không cần compiler trên
     production).
  2. **Cấm tuyệt đối người dùng upload/sửa template** qua ứng dụng. Thêm/sửa mẫu
     = thay đổi code + review + release.
  3. Font tiếng Việt (đề xuất **DejaVu Sans** + **Liberation Serif** — thay thế
     tương thích Times New Roman, license tự do) đóng gói vào image và khai báo
     bằng JR font extension để PDF embed font.
  4. Truyền dữ liệu vào Jasper bằng `JRBeanCollectionDataSource` từ DTO đã tổng
     hợp — **không** để Jasper tự mở JDBC connection và chạy SQL trong template
     (nếu không, filter phạm vi FR-08 sẽ bị đi vòng — đây là rủi ro bảo mật
     nghiêm trọng nhất của mô hình Jasper).

  - **Ngoại lệ có kiểm soát:** với báo cáo dạng **danh sách dữ liệu thô rất lớn**
    (> ~20.000 dòng), dùng **Apache POI SXSSF** trực tiếp để stream XLSX thay vì
    exporter của Jasper (Jasper giữ toàn bộ trang trong RAM). POI vốn đã là phụ
    thuộc bắc cầu của Jasper nên không thêm dependency mới.
- **Lý do:** với ~5–10 mẫu báo cáo × 3 định dạng, B tốn khoảng 15–30 lần code bố
  cục và chắc chắn phân kỳ giữa các định dạng theo thời gian; A trả lời trực
  tiếp yêu cầu "đúng mẫu" và "mẫu cấu hình được" của SRS. D bị loại vì chi phí
  vận hành trong môi trường cách ly. E bị loại vì license.
- **Word (đã chốt):** giữ Jasper DOCX exporter cho cả 3 định dạng — 3 mẫu MVP
  chỉ có bảng + biểu đồ đơn giản, thể thức tối giản nên rủi ro lệch bố cục
  thấp. Chỉ chuyển sang POI XWPF cho DOCX nếu nghiệm thu phát hiện lệch.
- **Hệ quả (trade-off):** phải theo dõi CVE của JasperReports và cập nhật thủ
  công offline; kích thước image backend tăng; DEV cần học `.jrxml`. Đổi lại,
  yêu cầu "in ấn đúng bố cục" được đáp ứng bằng công cụ thiết kế trực quan thay
  vì code layout tay.

### Quyết định 4: "Mẫu báo cáo cấu hình được" = danh mục đóng + tham số động

- **Ngữ cảnh:** FR-30 ghi "mẫu báo cáo cấu hình được" nhưng **không liệt kê mẫu
  nào**; ma trận vai trò không giao quyền quản trị mẫu cho R3.
- **Các phương án xem xét:** A) danh mục mẫu **đóng**, định nghĩa trong code +
  seed migration, người dùng chỉ chọn mẫu và nhập tham số; B) mẫu do người dùng
  tạo/upload (report designer trong ứng dụng); C) hybrid — mẫu đóng nhưng cho
  R1/R2 lưu **bộ tham số** thành "báo cáo đã lưu" của riêng mình.
- **Quyết định:** **C** (A + lưu bộ tham số). Bảng `report_template` (chỉ đọc,
  seed) mô tả metadata mẫu: `code`, tên, mô tả, khai báo tham số, đường dẫn
  `.jasper`, định dạng hỗ trợ. Bảng `report_saved_query` lưu bộ tham số do R1/R2
  đặt tên, **riêng theo người dùng**.
- **Lý do:** B mở đường cho người dùng đưa expression vào engine (rủi ro RCE) và
  cho người dùng tự viết truy vấn — phá vỡ đảm bảo phạm vi FR-08; đồng thời SRS
  không giao quyền quản trị mẫu cho vai trò nào. C vẫn thoả mãn nghĩa thực tế
  của "cấu hình được" (lọc theo nhóm/thời gian/lĩnh vực tuỳ ý, lưu lại dùng lại).
- **Bộ mẫu MVP — ĐÃ CHỐT (người dùng, 2026-10-01): 3 mẫu**, đều là bảng
  **tổng hợp** (vài chục–vài trăm dòng). Mọi chỉ tiêu "số tài liệu" dùng
  `COUNT(DISTINCT document.id)`, chỉ tính `document.deleted_at IS NULL`
  (**không ngoại lệ**); luôn sau `BusinessDataScope`.
  **Cập nhật (người dùng, 2026-10-08, khi chốt `STORY-export-reporting`):**
  bỏ chỉ tiêu "xoá trong kỳ" và không đếm tài liệu đã xoá mềm ở bất kỳ chỉ
  tiêu nào, để dùng nguyên `BusinessDataScope.forDocuments` (vốn luôn loại
  `deleted_at IS NOT NULL`) mà không cần biến thể scope mới. Số liệu = tài
  liệu còn trong kho lúc lập báo cáo; "tồn kho tại mốc T" = tài liệu còn sống
  có `created_at < T`. Bảng 1 luôn cân (đầu kỳ + nạp mới = cuối kỳ); Bảng 2
  và `DOC_BY_CLASSIFICATION` tính trên tồn kho cuối kỳ; Bảng 4 là ảnh chụp
  tại thời điểm chạy. Kỳ tính theo giờ `Asia/Ho_Chi_Minh`, nửa mở
  `[from, to+1)`. Hệ quả chấp nhận: xoá/phục hồi làm số liệu kỳ quá khứ thay
  đổi (có chú thích bắt buộc). Chi tiết: Quy ước + AC-12/13/15/16 của story.

  1. **`REPO_SUMMARY` — Tổng hợp tình hình kho tài liệu theo kỳ** (báo cáo công
     tác định kỳ; gộp luôn "diễn biến nạp theo thời gian").
     - Bảng 1 — tổng hợp: số tài liệu **đầu kỳ**, **nạp mới** trong kỳ,
       **cuối kỳ** (chỉ tài liệu còn sống; đã bỏ "xoá trong kỳ", 2026-10-08).
     - Bảng 2 — theo nhóm loại tệp (suy từ `document.mime_type`: văn bản/PDF,
       ảnh, âm thanh, video, khác): số lượng, dung lượng (`size`), tổng thời
       lượng âm thanh/video (`digitization_source.duration_ms`).
     - Bảng 3 + biểu đồ — diễn biến nạp mới theo `granularity`: số lượng,
       dung lượng, theo nhóm loại tệp.
     - Bảng 4 — tóm tắt số hoá: đã xong / đang xử lý / chờ duyệt / lỗi (đọc
       `document_digitization_status.overall_status`).
     - Chú thích bắt buộc: số liệu chỉ tính tài liệu còn trong kho lúc lập
       báo cáo; tài liệu đã xoá (trong thùng rác hoặc đã purge) không được
       tính, tài liệu phục hồi được tính lại → số liệu kỳ quá khứ có thể thay
       đổi giữa các lần lập.
  2. **`DIGITIZATION_PROGRESS` — Tiến độ và chất lượng số hoá.** Nguồn
     `digitization_run`, bỏ `review_state ∈ {superseded, void}` (chỉ run hiệu
     lực), join `document`; lọc kỳ theo `digitization_run.created_at`.
     - Hàng = tác vụ (`ocr`, `stt`, `video_summary`; **không** thống kê `sed` —
       nhận diện sự kiện âm thanh không thuộc phạm vi sản phẩm); cột: đang xử lý
       (`processing_status ∈ {queued, running, failed_retryable}`), lỗi
       (`failed_permanent`), chờ duyệt (`review_state ∈ {pending, in_review}`),
       **quá hạn duyệt** (chờ duyệt và `review_deadline_at < now()`), đã xác
       nhận tách theo `confirm_source` (*duyệt giữ nguyên* / *duyệt có sửa* /
       *tự xác nhận*).
     - Biểu đồ cột chồng theo tác vụ.
  3. **`DOC_BY_CLASSIFICATION` — Thống kê theo chủ đề và loại tài liệu
     ("lĩnh vực").** "Lĩnh vực" = nhãn `kind ∈ {topic, doc_type}` (đã chốt, xem
     mục 7).
     - Bảng chính: ma trận **chủ đề × loại tài liệu**, cộng dòng/cột **"Chưa
       phân loại"** (tài liệu không có nhãn của `kind` tương ứng); nhãn
       `active = false` vẫn hiển thị nếu có tài liệu.
     - Bảng phụ: số tài liệu theo từng chủ đề, theo từng loại tài liệu + biểu đồ.
     - Chú thích bắt buộc: một tài liệu có thể gắn nhiều nhãn nên tổng các dòng
       có thể lớn hơn tổng số tài liệu.

  - Lọc "theo nhóm" (FR-30) đáp ứng bằng tham số `groupIds[]` có ở **cả 3
    mẫu** — không làm mẫu riêng theo nhóm.
  - **Để giai đoạn sau** (không làm ở MVP): danh mục tài liệu chi tiết (danh
    sách dòng lớn, cần chia phần), kết quả công tác theo cán bộ (nhạy cảm và
    số liệu dễ hiểu sai do phạm vi FR-08: Lãnh đạo chỉ thấy tài liệu đã chia sẻ
    vào nhóm), thống kê lượt xem/tải/tìm kiếm (nguồn `audit_log`, quy tắc phạm
    vi khác).
- **Tham số chuẩn dùng chung:** `from`/`to` (**bắt buộc**), `groupIds[]`,
  `topicTagIds[]`, `docTypeTagIds[]`, `folderId`, `granularity`
  (`day|month|quarter|year`). UI có **chọn nhanh kỳ** Tháng / Quý / 6 tháng /
  Năm (chỉ là tiện ích điền sẵn `from`/`to`), vẫn cho chọn khoảng ngày tuỳ ý;
  `granularity` tự gợi ý theo độ dài kỳ. Tham số sai/thiếu → **400 kèm thông
  báo sửa** (đúng ngoại lệ FR-30); không có dữ liệu → trả **báo cáo rỗng có
  cảnh báo**, không phải lỗi.
- **Thể thức mẫu in — tối giản (đã chốt):** tên báo cáo, kỳ báo cáo, bộ lọc
  đã chọn, người lập, thời điểm lập, dòng phạm vi (Quyết định 5). **Không** có
  tên cơ quan, chỗ ký, số/ký hiệu văn bản.
- **Hệ quả:** thêm mẫu mới là một release — chấp nhận, đổi lấy an toàn và tính
  đúng đắn của số liệu.

### Quyết định 5: Phạm vi & quyền — tái dùng nguyên vẹn FR-08, R1 = R2

- **Ngữ cảnh:** ma trận SRS gốc cho R1 = `X`, R2 = `R`; người dùng đã chốt
  (2026-10-01) R2 có toàn bộ chức năng nghiệp vụ của R1 → coi R2 = `X`.
  FR-30/31 đều yêu cầu số liệu giới hạn theo phạm vi người lập.
- **Quyết định (diễn giải chốt để BA dùng khi viết AC):**
  - **R1 và R2 (quyền như nhau)**: chạy báo cáo, lưu bộ tham số, xuất tệp,
    tải tệp của mình. Không vai trò nào được sửa danh mục mẫu chung
    (Quyết định 4). Quyền gán qua `role_permission` cho cả `CAN_BO` và
    `LANH_DAO` (R3 chỉnh được qua màn RBAC, như `rbac-permission-editing.md`).
  - **R3/R4**: không có quyền truy cập bất kỳ endpoint nào của module này
    (fail-closed, kể cả R3 — NFR-04 tách biệt nhiệm vụ).
  - Số liệu **luôn** tính sau `BusinessDataScope`; **không có** flag bỏ qua
    phạm vi cho bất kỳ vai trò nào.
- **Hệ quả quan trọng về nghiệp vụ:** hai người chạy **cùng một mẫu, cùng tham
  số** có thể ra **số khác nhau** (phạm vi khác nhau). Đây là hệ quả bắt buộc
  của FR-08, không phải lỗi. Bắt buộc: tệp xuất và màn hình đều hiển thị dòng
  *"Số liệu trong phạm vi truy cập của <người lập> tại <thời điểm>"*. BA cần đưa
  điều này vào AC để tránh bị báo lỗi sai.
- **Hệ quả kỹ thuật:** vì con số phụ thuộc người gọi, **không cache kết quả báo
  cáo dùng chung**; nếu cần cache thì key phải gồm dấu vân tay phạm vi của
  người dùng, TTL ngắn (≤ 60 s) — coi là tối ưu về sau, không làm ở MVP.

### Quyết định 6: Vòng đời tệp xuất và quyền tải về

- **Quyết định:**
  - Tệp xuất ghi vào object storage qua `ObjectStorageService`, prefix riêng
    `reports/` — **không** ghi vào kho chung/kho cá nhân, **không** tính vào hạn
    mức kho (`warehouse-structure` / `personal-storage-quota`).
  - Bảng `report_export (id, template_code, params_json, format, status, requested_by, object_key, byte_size, row_count, error_message, created_at, finished_at, expires_at)`.
  - **Chỉ `requested_by` được tải** tệp; người khác → 403 kể cả khi cùng nhóm.
    Lý do: tệp là **ảnh chụp số liệu theo phạm vi của người tạo** — cho người
    khác tải sẽ vòng qua FR-08. Muốn chia sẻ thì tải về rồi chia sẻ ngoài hệ
    thống, hoặc người kia tự chạy lại mẫu (số của họ theo phạm vi của họ).
  - TTL mặc định **7 ngày** (`system_config: reporting.export_ttl_days`, đã
    chốt), job dọn định kỳ xoá object + đánh dấu `EXPIRED` (mẫu
    `UploadCleanupJob`).
  - Vượt ngưỡng dòng → job chuyển `FAILED` với thông báo "báo cáo quá lớn, hãy
    thu hẹp khoảng thời gian/bộ lọc" — đúng ngoại lệ FR-31. **Không** chia
    phần ở MVP (đã chốt).
  - Không có báo cáo định kỳ tự động chạy theo lịch (đã chốt, SRS không yêu
    cầu) — TTL ngắn phù hợp vì tệp luôn do người dùng chủ động tạo.
- **Trade-off:** không chia sẻ được tệp trong hệ thống (chấp nhận, đổi lấy đảm
  bảo phạm vi); tốn dung lượng tạm cho tệp xuất (giới hạn bằng TTL + quota job).

### Quyết định 7: Nhật ký (FR-09/FR-31)

- **Quyết định:** bổ sung vào catalog sự kiện của `audit-logging.md` (taxonomy
  `<domain>.<verb>`, ghi ở tầng service, đồng bộ trong transaction):
  - `report.generate` — mỗi lần chạy báo cáo (FR-30).
  - `report.export` — mỗi lần yêu cầu kết xuất tệp (FR-31), ghi cả kết quả
    `SUCCESS/FAILED`.
  - `report.download` — mỗi lần tải tệp về.
  - `report.access_denied` — truy cập ngoài phạm vi/sai vai trò (fail-closed).
- **Metadata theo allowlist (deny-by-default):** `template_code`, `format`,
  các tham số **đã chuẩn hoá** (id nhóm/nhãn, khoảng thời gian, granularity),
  `row_count`, `duration_ms`, `export_id`. **Không** ghi nội dung số liệu, tên
  tài liệu, hay bất kỳ chuỗi tự do dài nào vào `audit_log`.

## 5. Tech stack đề xuất

- **Backend:** Spring Boot — module mới `backend/src/main/java/.../reporting/`
  (`ReportTemplateRegistry`, `ReportQueryService`, `ReportRenderService`,
  `ReportExportJobRunner`, `ReportsController`). Truy vấn tổng hợp bằng JPQL/
  native SQL **luôn bọc trong `BusinessDataScope`**; connection/`statement_timeout`
  riêng cho truy vấn báo cáo.
- **Kết xuất:** JasperReports Library (LGPL, precompiled `.jasper`) cho PDF/
  XLSX/DOCX; Apache POI SXSSF cho ngoại lệ Excel dòng lớn; font DejaVu Sans +
  Liberation Serif đóng gói trong image.
- **Frontend:** Next.js (`web/`) — `/reports` (chọn mẫu + tham số + xem kết
  quả), `/reports/exports` (lịch sử & tải tệp), poll trạng thái job.
- **Data store:** PostgreSQL — bảng mới `report_template` (seed, chỉ đọc),
  `report_saved_query`, `report_export`; bổ sung index phục vụ thống kê
  (Quyết định 1). Object storage SeaweedFS/S3 prefix `reports/` cho tệp xuất.
- **Chạy nền:** `@Scheduled` runner + lease trên `report_export` (mẫu
  `SourceSeparationJobRunner`). **Không thêm** queue/broker/OLAP/report server.
- **Cấu hình (`system_config`, FR-01):** `reporting.export_ttl_days` (7),
  `reporting.max_rows_per_export` (100.000), `reporting.max_concurrent_exports`
  (10), `reporting.max_exports_per_user` (3), `reporting.query_timeout_seconds`
  (15).

## 6. Rủi ro


| Rủi ro                                                                                                                  | Mức độ   | Giảm thiểu                                                                                                                                                                                                                                           |
| ------------------------------------------------------------------------------------------------------------------------ | ----------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| Truy vấn tổng hợp bỏ sót filter phạm vi (viết SQL tay cho từng mẫu) → rò rỉ số liệu ngoài phạm vi FR-08  | **Cao**     | Cấm mọi mẫu tự viết truy vấn rời rạc: mọi mẫu phải đi qua`ReportQueryService` áp `BusinessDataScope` ở một chỗ; test hồi quy fail-closed cho **từng** mẫu (2 người dùng khác phạm vi phải ra số khác nhau đúng kỳ vọng) |
| Template Jasper tự mở JDBC và chạy SQL riêng, đi vòng qua filter phạm vi                                         | **Cao**     | Quyết định 3: chỉ dùng`JRBeanCollectionDataSource`; cấm khai báo `<queryString>` trong `.jrxml`; kiểm tra tự động lúc build (fail build nếu phát hiện)                                                                                  |
| Cho phép upload template → RCE qua expression của report engine                                                       | **Cao**     | Quyết định 4: danh mục mẫu đóng, template precompile trong repo, không có API upload                                                                                                                                                          |
| Tệp xuất bị người khác tải (số liệu vượt phạm vi người tải)                                               | **Cao**     | Quyết định 6: chỉ`requested_by` tải được; audit `report.download`                                                                                                                                                                              |
| Số liệu báo cáo lệch với màn hình tìm kiếm (index trễ ≤ 60 s,`total` gần đúng) → người dùng báo lỗi | Trung bình | Quyết định 1: báo cáo**chỉ** lấy số từ PostgreSQL; tài liệu hoá rõ sự khác biệt cho BA/UI                                                                                                                                              |
| Đếm trùng/sai do join bảng phái sinh (1 tài liệu ↔ n nhãn/n nhóm/n segment)                                    | Trung bình | Quy tắc bắt buộc: mọi chỉ tiêu "số tài liệu" dùng`COUNT(DISTINCT document.id)`; đặc tả rõ ý nghĩa từng chỉ tiêu trong metadata mẫu; test dữ liệu mẫu có chủ đích                                                            |
| Truy vấn báo cáo nặng làm chậm OLTP (nghẽn kết nối, lock)                                                       | Trung bình | Timeout truy vấn 15 s, pool/`statement_timeout` riêng, giới hạn job đồng thời; nếu vẫn nghẽn → read replica hoặc kích hoạt rollup (phương án B, QĐ 1)                                                                                |
| Jasper CVE trong môi trường không vá được nhanh (air-gap)                                                        | Trung bình | Precompile (không cần compiler runtime), không nhận input template từ người dùng, theo dõi CVE và lên lịch cập nhật offline định kỳ                                                                                                   |
| PDF thiếu font → mất dấu tiếng Việt khi in                                                                         | Trung bình | Đóng gói font vào image + JR font extension; test kết xuất có dấu là AC bắt buộc                                                                                                                                                            |
| Tệp xuất tích tụ đầy đĩa                                                                                         | Thấp       | TTL 7 ngày + job dọn + giới hạn job đồng thời/người                                                                                                                                                                                           |
| Bộ 3 mẫu MVP chưa đủ nhu cầu nghiệp vụ thực tế → phải bổ sung mẫu                                          | Thấp       | Đã chốt 3 mẫu với người dùng (Quyết định 4); kiến trúc tách`ReportQueryService`/template nên thêm mẫu (vd. các mẫu "để giai đoạn sau") là chi phí tuyến tính nhỏ                                                           |

## 7. Câu hỏi mở

- [X]  **Danh sách "mẫu báo cáo" chính thức.** SRS FR-30 không liệt kê mẫu nào.
  → **Chốt (người dùng, 2026-10-01):** MVP gồm 3 mẫu `REPO_SUMMARY`,
  `DIGITIZATION_PROGRESS`, `DOC_BY_CLASSIFICATION`; thể thức in tối giản
  (không tên cơ quan/chỗ ký/số văn bản); kỳ báo cáo = chọn nhanh
  Tháng/Quý/6 tháng/Năm + khoảng ngày tuỳ ý. Các mẫu khác để giai đoạn sau
  (cập nhật: Quyết định 4).
- [X]  **"Lĩnh vực" có đúng là nhãn `topic`/`doc_type` không?** → **Chốt:**
  đúng, dùng taxonomy nhãn hiện có theo `classification-notes.md` QĐ 7;
  không thêm `kind` mới (cập nhật: Quyết định 4, mẫu `DOC_BY_CLASSIFICATION`).
- [X]  **Ngưỡng "báo cáo quá lớn".** → **Chốt:** 100.000 dòng/tệp, vượt ngưỡng
  **chỉ cảnh báo** yêu cầu thu hẹp bộ lọc, không chia phần ở MVP (cập nhật:
  NFR Scalability, Quyết định 6).
- [X]  **Báo cáo định kỳ tự động** → **Chốt:** không làm ở phiên bản này
  (cập nhật: Quyết định 6).
- [X]  **R2 có được xuất tệp không?** **Đã chốt (người dùng, 2026-10-01):** R2
  tạo, chạy, lưu bộ tham số và xuất tệp như R1 (Quyết định 5).
- [X]  **Tệp xuất có được lưu vào kho chung để chia sẻ nội bộ không?** →
  **Chốt:** không; chỉ người tạo tải được, chia sẻ bằng cách tải về
  (giữ nguyên Quyết định 6).
- [X]  **Chấp nhận việc hai người ra số khác nhau** cho cùng mẫu + tham số? →
  **Chốt:** chấp nhận, là hệ quả của FR-08; màn hình và tệp xuất luôn ghi
  dòng phạm vi (giữ nguyên Quyết định 5).
- [X]  **TTL 7 ngày cho tệp xuất** và **giới hạn 3 job/người** → **Chốt:** giữ
  làm giá trị mặc định trong `system_config` (`reporting.export_ttl_days`,
  `reporting.max_exports_per_user`) (cập nhật: NFR Scalability, Quyết
  định 6, mục 5).
- [X]  **Định dạng Word có thực sự cần bố cục phức tạp không?** → **Chốt:** giữ
  Jasper DOCX exporter cho cả 3 định dạng; chỉ chuyển POI XWPF nếu nghiệm
  thu thấy lệch bố cục (cập nhật: Quyết định 3).
