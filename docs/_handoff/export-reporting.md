## Handoff: export-reporting

### Đã chốt
- SA, BA, DEV-spec đều `Approved` (spec duyệt 2026-10-08). Slug `export-reporting` (đổi từ `reporting-export` vì guard chặn tên file bắt đầu bằng "report").
- Spec gồm 2 file: chính + Part T (truy vấn 3 mẫu, DTO `ReportResult`, Jasper). 17 task T1–T17, mỗi task map AC-n; phủ AC-1..AC-38.
- 7 câu hỏi mở Q1–Q7 đã chốt: chỉ đếm `status='clean'`; bucket riêng `reports`; `folderId` gồm thư mục con; timeout 503 / hết hạn 410 / chưa DONE 409; `reporting.max_buckets=400`; thu hồi quyền giữa chừng thì job vẫn chạy xong, tải cần quyền hiện tại; JasperReports 7.0.x + commit font DejaVu Sans/Liberation Serif.
- 8 điểm review (#1–#8) đã chốt, ghi cuối §12 spec: thời lượng = MAX theo tài liệu; audit `report.export` 2 bản/job; ghi chú build JR7; AC-2 không token = 401, thiếu `sub` = 403; gia hạn lease + cập nhật kết thúc có điều kiện; key `<exportId>.<ext>`; AC-16 kho cá nhân; index partial thêm `status='clean'`.
- Migration mới là V36; code dùng `uploader_sub` (không phải `created_by`).

### Câu hỏi mở / rủi ro
- Không còn câu hỏi mở trong spec.
- R-1: Jasper chưa có trong `~/.m2` → cần build online một lần; `.jrxml` phải theo định dạng JR7.
- R-2: rò rỉ phạm vi → chỉ `ReportQueryService` đụng DB; IT `ReportScopeIT`/`ReportCountingIT` là gate (chỉ chạy bằng `mvn verify`).
- R-3/R-4: Criteria + hàm Postgres trên Hibernate 6; hiệu năng ~1M tài liệu → cần `EXPLAIN`.
- Hạ tầng (người dùng làm tay): tạo bucket `reports` + cấp quyền identity backend trên SeaweedFS stg/prod trước khi deploy.
- `docs/00-overview/features.md` dòng `export-reporting` chưa cập nhật cột DEV-spec → hỏi người dùng/ cập nhật khi chuyển bước.

### File cần đọc tiếp
- docs/03-dev/specs/export-reporting.md
- docs/03-dev/specs/export-reporting-templates.md
- docs/02-ba/stories/export-reporting.md (chỉ khi cần đối chiếu AC)

### Bước kế tiếp đề xuất
- /dev-implement docs/03-dev/specs/export-reporting.md (session mới)
- Sau đó: /qa-review docs/02-ba/stories/export-reporting.md docs/03-dev/specs/export-reporting.md
