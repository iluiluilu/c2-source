# QA Report: export-reporting

> File: `docs/04-qa/reports/export-reporting.md` — Owner: QA
> Nguồn story: `docs/02-ba/stories/export-reporting.md` (STORY-export-reporting, Status: Approved)
> Nguồn spec: `docs/03-dev/specs/export-reporting.md` (SPEC-export-reporting, Status: Approved)
> Ngày review: 2026-10-08
> Commit/branch reviewed: main @ 8a24890 + working tree chưa commit (module `reporting/`, V36, resources/reports, fonts, web `/reports`)

## Kết luận

**PASS WITH ISSUES**

Mọi AC chức năng (AC-1..18, 20..26, 28..38) đều có test tự động xanh và có bằng chứng ở mã. Không có blocking. AC-19 và AC-27 là `[ĐX]`, chưa đo tải (story cho phép không chặn nghiệm thu). Còn vài việc triển khai/tài liệu chưa xong, ghi ở Non-blocking.

## Đối chiếu Acceptance Criteria

Bằng chứng test nằm trong `backend/src/test/java/com/aismartstore/backend/reporting/` (IT chạy bằng `mvn verify`). Web test nằm trong `web/src/app/reports/**/__tests__`.

| AC | Kết quả | Bằng chứng |
|---|---|---|
| AC-1 | Pass | `ReportAccessIT` (R1/R2 chạy, lưu, xuất, tải), `ReportAccessGuardTest` |
| AC-2 | Pass | `ReportAccessGuard.java`, `ReportAccessIT`, `ReportAccessGuardTest:65` (audit `report.access_denied`, metadata `permission`). Không token trả 401 theo phân định trong spec §4 bước 1 |
| AC-3 | Pass | `ReportQueryService.java:119` gọi nguyên `BusinessDataScope.forDocuments`; `ReportScopeIT` (4 test, 2 user phạm vi khác nhau) |
| AC-4 | Pass | `scopeNotice` trong `ReportResult`; `ReportApiIT`, `ReportRenderIT`, web `ReportResultView` |
| AC-5 | Pass | V36 seed `report.read`/`report.export` cho CAN_BO + LANH_DAO; `RbacReportPermissionIT` (gán cho QUAN_TRI trả 400, không đổi gì); `ReportAccessIT` (thu hồi riêng `report.export`); `RolePermissionSeedTest` (7 test pass) |
| AC-6 | Pass | `ReportTemplateRegistry`, `GET /reports/templates`, `ReportApiIT`; không có API upload/sửa mẫu |
| AC-7 | Pass | `ReportParams`/`ReportParamValidator`, `ReportTemplatesIT`, `ReportParamValidatorTest` |
| AC-8 | Pass | Web `src/lib/reports.ts` + `src/lib/__tests__/reports.test.ts`, `reports/__tests__/page.test.tsx` (33 test web pass) |
| AC-9 | Pass | `ReportParamValidatorTest` (id ngoài scope cùng thông báo với id không tồn tại), `GranularityBucketsTest` (400/401 bucket), `ReportApiIT` |
| AC-10 | Pass | `ReportTemplatesIT` (báo cáo rỗng kèm cảnh báo) |
| AC-11 | Pass | `SavedQueryServiceTest` (tên 1–100, trùng không phân biệt hoa thường, 50/người, bỏ id mất kèm cảnh báo chung), `ReportApiIT`; V36 có unique index `lower(btrim(name))` |
| AC-12 | Pass | `ReportTemplatesIT`, `ReportCountingIT` (Bảng 1 luôn cân, thời lượng không đếm trùng), `MimeGroupClassifierTest` (23 test) |
| AC-13 | Pass | Chú thích bắt buộc trong `notes[]`, `ReportTemplatesIT`, web `ReportResultView` |
| AC-14 | Pass | `ReportTemplatesIT` (3 tác vụ `ocr`/`stt`/`video_summary`, run `superseded`/`void` bị loại); `ReportScopeIT` |
| AC-15 | Pass | `ReportTemplatesIT` (ma trận chủ đề × loại, "Chưa phân loại", nhãn `active=false`) |
| AC-16 | Pass | `ReportCountingIT` (8 test: nhiều nhãn/nhóm đếm 1 lần, xoá mềm bị loại, `status` khác `clean` bị loại, `personal_file` không vào) |
| AC-17 | Pass | Truy vấn chỉ qua `ReportQueryService` (Postgres); không dùng OpenSearch/Qdrant; ghi chú trong web |
| AC-18 | Pass | `ReportApiIT` (JSON đồng bộ, phân trang, tiêu đề/kỳ/bộ lọc) |
| AC-19 | Chưa đo `[ĐX]` | Có `backend/scripts/report-perf-seed.sql` nhưng chưa có số đo và `EXPLAIN`. Story cho phép không chặn nghiệm thu |
| AC-20 | Pass | `ReportQueryService.java:181` `set_config('statement_timeout', ..., true)`; `ReportTimeoutIT` (trả 503 kèm gợi ý thu hẹp bộ lọc) |
| AC-21 | Pass | `ReportExportFlowIT.createRunDownloadHappyPath...` (202, bản ghi PENDING) |
| AC-22 | Pass | `ReportExportFlowIT` (PENDING→RUNNING→DONE, FAILED, EXPIRED); web `exports/__tests__/page.test.tsx` (poll) |
| AC-23 | Pass | `ReportExportFlowIT.jobLeftRunningWithAnExpiredLeaseIsReclaimed...`, `jobWithAValidLeaseIsNotStolen`, `tooManyAttemptsFailsTheJob`, `aReclaimedDuplicateRun...` |
| AC-24 | Pass | `ReportRenderIT` (4 test: PDF/XLSX/DOCX mở lại được bằng PDFBox/POI/XWPF), `xlsxAndDocxJobsComplete` |
| AC-25 | Pass | `ReportRenderIT` (header/footer, mã/tên mẫu, tham số, người lập, thời điểm, câu phạm vi) |
| AC-26 | Pass | `ReportRenderIT` (PDFBox đọc text có dấu tiếng Việt, font nhúng); font `.ttf` DejaVu Sans + Liberation Serif trong `src/main/resources/fonts` |
| AC-27 | Chưa đo `[ĐX]` | Như AC-19. Chức năng xuất chạy qua Jasper exporter, đúng quyết định của SA |
| AC-28 | Pass | `ReportExportFlowIT.reportWithMoreRowsThanTheLimitFailsWithoutProducingAFile`, `ReportRowCountTest` |
| AC-29 | Pass | `ReportExportFlowIT.renderFailureGivesAGenericMessageAndCanBeRetriedAsANewJob` |
| AC-30 | Pass | `ReportExportFlowIT.perUserLimitRejectsWith429_...`, `systemWideLimitRejectsWith429AndADifferentMessage`, `ExportQuotaTest` |
| AC-31 | Pass | `ReportExportFlowIT` (người khác 403 + audit `report.access_denied`) |
| AC-32 | Pass | `GET /reports/exports` chỉ của chính mình; web `exports/__tests__/page.test.tsx` |
| AC-33 | Pass | `ReportExportFlowIT.expiredFilesAreCleanedUpAndDownloadAnswers410`; bucket riêng `reports`, `ReportExportCleanupJob` |
| AC-34 | Pass | `ReportAuditor.java`, `ReportExportFlowIT` (2 bản `report.export` cùng `export_id`; 429 không sinh bản ghi), `ReportApiIT:90` |
| AC-35 | Pass | `AuditService.java:80-88` (allowlist `report.*` trong `Map.ofEntries`); `ReportApiIT:266-290` kiểm tra metadata chỉ gồm khoá allowlist, không có tên nhãn |
| AC-36 | Pass | `JrxmlGuardTest` (3 test), `JasperPrecompiler.java`, các `.jrxml` trong `resources/reports`, không có endpoint upload |
| AC-37 | Pass | `ReportScopeIT` (4), `ReportCountingIT` (8) |
| AC-38 | Pass | Module tách: runner chạy trên executor riêng, không dùng OpenSearch/Qdrant/GPU (đọc code). Không có test cô lập sự cố riêng |

## Kết quả test/lint đã chạy

```
cd backend
mvn -o -q test -Dtest='com.aismartstore.backend.reporting.*Test,RolePermissionSeedTest'
  -> Unit (surefire-reports): MimeGroupClassifier 23, PeriodTest 4, GranularityBuckets 4, JrxmlGuard 3,
     ReportAccessGuard 7, ExportQuota 4, ReportRowCount 2, ReportExportJobRunnerTest 3,
     ReportParamValidator 7, SavedQueryService 6, RolePermissionSeedTest 7 -> 0 fail, 0 error

mvn -o verify -Dit.test='com.aismartstore.backend.reporting.*IT,RbacControllerIT' (Testcontainers)
  -> Tests run: 62, Failures: 0, Errors: 0, Skipped: 0 -> BUILD SUCCESS
     ReportExportFlowIT 13, ReportApiIT 10, ReportCountingIT 8, ReportAccessIT 7, RbacControllerIT 7,
     ReportScopeIT 4, ReportRenderIT 4, RbacReportPermissionIT 4, ReportTemplatesIT 4, ReportTimeoutIT 1

cd web
npx tsc --noEmit                 -> 0 lỗi
npm run lint                     -> 0 lỗi, 1 warning (web/coverage/block-navigation.js, file sinh ra, không liên quan)
npx vitest run src/app/reports src/lib/__tests__/reports.test.ts src/app/admin/rbac -> 4 file, 33 test pass
```

Chưa chạy toàn bộ `mvn verify`. Hai IT nghi flaky đã biết (TagsControllerIT, SearchScopeIT) nằm ngoài phạm vi feature này nên không chạy.

## Vấn đề chặn (Blocking)

- Không có.

## Đề xuất cải thiện (Non-blocking)

- [ ] AC-19/AC-27: chưa đo tải ~1M tài liệu và chưa có `EXPLAIN` cho 3 mẫu (DoD spec §11 yêu cầu). Cần chạy `backend/scripts/report-perf-seed.sql` và ghi số liệu vào PR trước khi lên prod.
- [ ] Quyền identity SeaweedFS cho bucket `reports` trên stg/prod chưa cấp (spec §13 ghi là kiểm tra thủ công trước deploy). Nếu thiếu, job xuất sẽ FAILED trên môi trường thật. Xem `docs/00-overview/runbooks/export-reporting-jasper-fonts.md`.
- [ ] Spec §5 liệt kê `ReportAuditMetadataIT` nhưng không có file riêng. Phần kiểm tra allowlist nằm trong `ReportApiIT:266-290`, nên đã đủ bằng chứng cho AC-35. Nên đồng bộ lại tên trong spec.
- [ ] AC-38 chưa có test chứng minh module lỗi không ảnh hưởng ingest/search. Hiện chỉ dựa vào thiết kế (executor riêng, không phụ thuộc OpenSearch/GPU).
- [ ] `docs/00-overview/features.md:56` đang ghi "Done (chờ QA)". Cập nhật sau khi report này được chấp nhận.
- [ ] Mục Security checklist (spec §8) và DoD (§11) vẫn là `[ ]` chưa tick, cần DEV tick sau khi xác nhận.
- [ ] Code chưa commit (working tree). Cần commit trước khi đóng feature để report gắn được hash cụ thể.
- [ ] V36 tạo index thường (không CONCURRENTLY) trên `document` và `digitization_run`. Cần deploy ngoài giờ cao điểm và kiểm tra checksum Flyway trên stg, như spec §9 đã ghi.

## Kiểm tra thêm

- [x] Security checklist trong spec: đạt về mặt code và test. Scope chỉ qua `BusinessDataScope`; chỉ chủ tệp mới tải được (403 + audit); không có `<queryString>` trong mẫu; có escape công thức Excel (`ReportRenderService.java:231`, `escapeFormulas`); `Content-Disposition` dùng tên cố định; có quota và 429. Các ô checkbox trong spec chưa được tick.
- [x] Observability: spec yêu cầu log một dòng cho mỗi lần chạy/xuất/cleanup; chỉ đối chiếu qua test, không soi từng dòng log.
- [x] Migration/rollback: V36 additive, Flyway áp dụng thành công trong IT (Testcontainers); có kế hoạch rollback ở spec §9.
