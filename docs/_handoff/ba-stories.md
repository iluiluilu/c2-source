## Handoff: ba-stories (7 story cho toàn bộ SA đã Approved)

### Đã chốt
- Đã viết đủ 7 story BA từ 7 file SA (đều Approved), mỗi story bám ADR gốc: auth-session, account-lifecycle, rbac-group-scope, data-ingestion, system-config, warehouse-structure, personal-storage-quota.
- Tất cả story hiện `Status: Draft` — người dùng cần review và tự đổi sang `Approved` trước bước DEV.
- Ranh giới chéo đã thống nhất: cột `storage_quota_bytes` dùng chung giữa account-lifecycle (Story 5) và personal-storage-quota; ngưỡng kho 85%/95% và giới hạn upload 500 tệp/5GB nằm ở system-config; allowlist định dạng DB-backed (bảng riêng); casefiles bị xóa hẳn, thay bằng group/group_member/document_group_share.

### Câu hỏi mở / rủi ro
- rbac-group-scope: diễn giải "RBAC tĩnh" (FR-07 = gán vai trò có sẵn, KHÔNG tạo vai trò mới) CẦN khách hàng xác nhận trước khi Approve (SA đánh dấu rủi ro Trung bình) — không tự giả định.
- Giá trị mặc định do SA đề xuất (retention 365 ngày, sao lưu 02:00, ngưỡng kho 85/95%) chưa được khách hàng xác nhận chính thức; sửa được qua UI nên không chặn go-live nhưng cần hỏi lại.
- Nhiều câu hỏi kỹ thuật để dành cho DEV-spec (không phải quyết định BA): cơ chế invalidate cache system-config/allowlist; đồng bộ event Keycloak -> audit_log; schema audit_log chung; nguồn tính dung lượng kho & cá nhân (SeaweedFS vs cộng dồn metadata); cách nhận diện tệp đã nhập kho để resume upload; liệt kê mọi entrypoint đọc dữ liệu cho lớp filter FR-08; kiểm tra dữ liệu casefiles trước khi xóa.

### File cần đọc tiếp
- docs/02-ba/stories/*.md (7 file mới, đối chiếu với docs/01-sa/architecture/*.md cùng slug)
- Câu hỏi mở cho DEV nằm ở cuối mỗi file story.

### Bước kế tiếp đề xuất
- Người dùng review + đổi Status Draft->Approved cho từng story cần làm trước.
- Với slug đã Approved: /dev-spec docs/02-ba/stories/<slug>.md
- Ưu tiên nền tảng trước: rbac-group-scope (sau khi chốt diễn giải RBAC tĩnh) và system-config, vì nhiều story khác phụ thuộc.
