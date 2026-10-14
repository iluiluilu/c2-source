# Tech Spec (Part T): export-reporting — mẫu báo cáo, DTO, Jasper

> File: `docs/03-dev/specs/export-reporting-templates.md` — Owner: DEV
> Phần của: `docs/03-dev/specs/export-reporting.md` (SPEC-export-reporting) — đọc file chính trước.
> Status: Approved

## 1. Quy tắc truy vấn chung (`ReportQueryService`)

- Mọi truy vấn: `Root<Document> d`; predicate nền = `BusinessDataScope.forDocuments(sub, groupMemberRepository).toPredicate(d, query, cb)` ∧ `status='clean'` (Q1) ∧ bộ lọc.
- Bộ lọc dùng EXISTS subquery (không join nhân dòng): `groupIds` → `document_group_share.group_id IN (...)`; `topicTagIds`/`docTypeTagIds` → mỗi nhóm một EXISTS trên `DocumentTag` (nhiều id trong 1 nhóm = OR; giữa 2 nhóm = AND); `folderId` → `d.folderId IN (folder + con cháu qua warehouse_folder.path_ids)`.
- Chỉ tiêu số tài liệu: `cb.countDistinct(d.id)`. Dung lượng: tổng `size` trên tập đã distinct (tránh nhân khi join: tính trên Root<Document> không join).
- Mốc: `T0 = from 00:00 ICT`, `T1 = to+1 00:00 ICT` (UTC). Tồn kho(T) = `created_at < T`.
- Bucket: `date_trunc(granularity, timezone('Asia/Ho_Chi_Minh', created_at))` (`granularity` đã whitelist, truyền như literal enum, không từ chuỗi người dùng).
- Mỗi lần gọi: một transaction read-only, `SET LOCAL statement_timeout = <ms>`; bắt `QueryTimeoutException`/SQLState `57014` → `ServiceUnavailableException` kèm gợi ý.

## 2. Ba mẫu

### 2.1 `REPO_SUMMARY` (AC-12, AC-13)
- B1: `opening = count(created_at < T0)`, `ingested = count(T0 <= created_at < T1)`, `closing = count(created_at < T1)`; bất biến `opening + ingested = closing` (assert nội bộ → log ERROR nếu lệch).
- B2: trên tập `created_at < T1`: group theo `MimeGroup.of(mime_type)` (hằng số + `MimeGroupClassifierTest`) → count, `sum(size)`; thời lượng (nhóm âm thanh/video) = tổng theo tài liệu của `MAX(digitization_source.duration_ms)` — subquery `SELECT document_id, MAX(duration_ms) FROM digitization_source WHERE document_id IN (tập scope) GROUP BY document_id`, rồi cộng (chốt review #1: một tài liệu video có thể có nhiều nguồn `video`/`audio`/`original` — V18 — nên cộng thẳng sẽ tính trùng; `duration_ms` NULL bỏ qua).
- B3: tập `T0 <= created_at < T1` group theo (bucket, MimeGroup): count, size; biểu đồ cột theo bucket.
- B4: snapshot hiện tại (không lọc kỳ): `document_digitization_status.overall_status` → đã xong=`confirmed`; đang xử lý=`queued,processing`; chờ duyệt=`pending_review,in_review`; lỗi=`failed`; `not_applicable` bỏ. Ghi chú "trạng thái tại thời điểm lập báo cáo". Document không có hàng status = không đếm.
- Ghi chú cố định AC-13 + AC-17 trong `notes[]`.

### 2.2 `DIGITIZATION_PROGRESS` (AC-14)
- Nguồn `DigitizationRun r` với `r.document` ∈ tập document đã scope/lọc (EXISTS tới Root<Document> + `BusinessDataScope`); `r.reviewState NOT IN ('superseded','void')`; `T0 <= r.created_at < T1`; `task IN (ocr, stt, video_summary)`.
- Cột: processing = `processing_status IN (queued,running,failed_retryable)`; error = `failed_permanent`; pending review = `review_state IN (pending,in_review)`; overdue = pending review ∧ `review_deadline_at < now()`; confirmed tách `confirm_source` (`manual_unchanged`/`manual_edited`/`auto`). Các cột không loại trừ nhau trừ confirmed vs pending (ghi rõ trong chú thích). Đếm `count(distinct r.id)` (đơn vị là run, ghi rõ chú thích).
- Biểu đồ cột chồng theo task.

### 2.3 `DOC_BY_CLASSIFICATION` (AC-15)
- Tập: `created_at < T1`. Ma trận (topic × doc_type): truy vấn `(tagTopic.id, tagDocType.id, countDistinct(d.id))` bằng 2 root `DocumentTag` + `Tag` có điều kiện khớp `kind`; thêm dòng/cột "Chưa phân loại" bằng NOT EXISTS theo từng `kind`; ô (null,null) = không có nhãn nào. Nhãn `active=false` vẫn hiện nếu count>0 (không lọc theo active). Nếu bộ lọc `topicTagIds`/`docTypeTagIds` có → ma trận chỉ gồm các nhãn được chọn.
- Bảng phụ: theo từng topic, theo từng doc_type (+ "Chưa phân loại"); biểu đồ cột theo hai bảng phụ. Chú thích nhiều nhãn (AC-15).
- Bảng ma trận đánh dấu `paged=true` (AC-18 phân trang theo hàng).

## 3. DTO (`ReportResult`)

```
ReportResult { templateCode, title, period{from,to}, filters{...tên hiển thị...},
  scopeNotice, generatedAt, generatedBy, notes[], warnings[], empty,
  tables[ {id,title,columns[{key,label,type}],rows[Map],totalRows,paged} ],
  charts[ {id,title,type:BAR|STACKED_BAR,categories[],series[{name,values[]}]} ] }
```

- `scopeNotice` = "Số liệu trong phạm vi truy cập của <username> tại <ISO local time>" (AC-4). `empty=true` + warning "không có dữ liệu" khi mọi chỉ tiêu = 0 (AC-10).
- Tên nhãn/nhóm/thư mục chỉ có trong `filters` hiển thị; **không** đưa vào audit.
- `row_count` (kiểm giới hạn AC-28) = tổng `totalRows` của mọi bảng.

## 4. Jasper (AC-24..27, AC-36)

- Dependency (chốt Q7: **JasperReports 7.0.x**, cùng một version cho mọi module; tải online một lần — R-1): `jasperreports` (core), `jasperreports-pdf` (OpenPDF), `jasperreports-excel-poi`, `jasperreports-fonts` (DejaVu Sans), `jasperreports-charts` (JFreeChart); XLSX exporter dùng POI (đã có trong repo local). Loại các dependency không cần.
- Mẫu nằm `backend/src/main/resources/reports/*.jrxml` (+ subreport cho từng bảng; mỗi bảng nhận `JRBeanCollectionDataSource` qua parameter); precompile `.jasper` lúc build (chốt review #3): `.jrxml` theo định dạng JR7 (thiết kế bằng Jaspersoft Studio 7); plugin Maven Jasper cũ không hỗ trợ JR7 nên dùng `exec-maven-plugin` gọi `JasperCompileManager` (class tiện ích nhỏ trong module build) ở phase `process-classes`, ghi `.jasper` vào `target/classes/reports/`; cần `jasperreports-jdt` (hoặc compiler tương đương) trên classpath của bước build; runtime chỉ load `.jasper`. **Cấm `<queryString>`** (`JrxmlGuardTest`). Expression trong `.jrxml` chỉ tham chiếu field/parameter, ngôn ngữ Java đã compile.
- Font (chốt Q7: commit `.ttf` vào repo, giấy phép OFL/free kèm file LICENSE): `fonts/LiberationSerif-*.ttf`, `fonts/DejaVuSans*.ttf` + `jasperreports_extension.properties` + `fonts.xml` khai `pdfEmbedded=true`, encoding `Identity-H`; `JRPropertiesUtil` đặt `net.sf.jasperreports.awt.ignore.missing.font=false` để lỗi sớm.
- `ReportRenderService.render(ReportResult, Format, Path out)`: PDF `JRPdfExporter`, XLSX `JRXlsxExporter` (`onePagePerSheet=false`, `detectCellType=true`, `removeEmptySpaceBetweenRows`), DOCX `JRDocxExporter`; header/footer: mã+tên mẫu, bộ lọc, người lập, thời điểm, dòng phạm vi, số trang (AC-25). Biểu đồ: dựng bằng chart element Jasper từ dữ liệu `charts[]` (không ảnh ngoài).
- Không POI SXSSF ở MVP (ngoài phạm vi).
