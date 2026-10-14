## Handoff: r2-business-parity (spec 01 xong → implement 01 / spec 02, 03)

### Đã chốt
- SA + 3 story (01 permission-grant, 02 ownership-transfer, 03 web-permission-gating) đều **Approved**, hết câu hỏi mở.
- Spec 01 `docs/03-dev/specs/r2-business-parity-01-permission-grant.md` **Approved**, hết câu hỏi mở: migration `V35__r2_business_parity.sql` (data-only, 5 cặp `LANH_DAO`, `ON CONFLICT DO NOTHING`); 32 endpoint × 3 vai trò trong `R2PermissionMatrixIT`; đảo 7 test cũ khẳng định R2 bị 403; 9 task map AC-1..AC-10.
- Spec 01: IT đỏ vì hạ tầng thì lỗi nhỏ sửa trong story, lỗi lớn DỪNG báo người dùng; Task 9/DoD yêu cầu **toàn bộ** test kể cả IT xanh (AC-10). Rollback: không viết sẵn migration bù, quay lui bằng màn RBAC (SQL mẫu ở §9).
- Story 02: cảnh báo FE dùng `GET /admin/rbac/roles`; mở bộ lọc `web/src/app/admin/document-ownership/page.tsx:107` cho `LANH_DAO`.
- Story 03: web dùng đúng loại gate của endpoint BE; AC-8 provider có một trạng thái toàn cục loading/ready/error, fail-closed.

### Câu hỏi mở / rủi ro
- Bộ IT Testcontainers chưa chạy lại toàn bộ từ 2026-09-29 → Task 1 spec 01 phải chạy baseline trước.
- `V34__folder_upload_display_name.sql` đang chưa commit (thuộc phần display-name) → nguy cơ trùng V35; grep lại số version trước khi tạo/merge.
- Test ma trận: body/UUID không hợp lệ cho 400 trước 403 → mỗi case cần body mẫu hợp lệ.
- Spec 02/03 chưa viết. File spec đặt cùng tên với story (có hậu tố `-02/-03`).
- DEV spec 03 cần xác nhận permission BE đứng sau `canDelete` ở `web/src/app/documents/page.tsx`; AC-6 story 03 ghi ~7 file test nhưng grep ra 8.

### File cần đọc tiếp
- docs/03-dev/specs/r2-business-parity-01-permission-grant.md (implement 01)
- docs/02-ba/stories/r2-business-parity-02-ownership-transfer.md
- docs/02-ba/stories/r2-business-parity-03-web-permission-gating.md

### Bước kế tiếp đề xuất
- /dev-implement docs/03-dev/specs/r2-business-parity-01-permission-grant.md
- /dev-spec docs/02-ba/stories/r2-business-parity-02-ownership-transfer.md
- /dev-spec docs/02-ba/stories/r2-business-parity-03-web-permission-gating.md (implement sau 01)
- Cập nhật `docs/00-overview/features.md`: 01 ở bước implement, 02/03 ở bước DEV-spec.
