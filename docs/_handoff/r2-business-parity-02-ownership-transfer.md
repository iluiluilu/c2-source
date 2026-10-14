## Handoff: r2-business-parity-02-ownership-transfer

### Đã chốt
- Story `docs/02-ba/stories/r2-business-parity-02-ownership-transfer.md` (Approved) → spec DEV đã viết (Status: **Draft**, chưa implement).
- Phạm vi: chỉ backend, một điểm — `DocumentOwnershipService.transfer()` (dòng ~83-87) chấp nhận người nhận role `can-bo` HOẶC `lanh-dao`; message lỗi mới "Người nhận phải có vai trò Cán bộ (R1) hoặc Lãnh đạo (R2)".
- Không migration (ADR-4: kiểm role, không kiểm permission). `@PreAuthorize PERM_document.transfer_ownership` giữ nguyên (AC-7).
- AC-5 (cảnh báo FE) dùng sẵn `GET /admin/rbac/roles`, không đổi BE; phần FE thuộc spec 03 (web-permission-gating).
- /resolve-questions: chốt giữ literal cục bộ `ROLE_LANH_DAO` cạnh `ROLE_CAN_BO`, không thêm hằng vào `AssignableRoles` (spec §6 Task 1, §12). Spec hết câu hỏi mở.

### Câu hỏi mở / rủi ro
- Spec còn **Draft** — agent kế tiếp phải hỏi người dùng đã đổi Approved chưa trước khi implement.
- IT `transfer_bWithoutCanBoRole_returns400` (DocumentOwnershipControllerIT ~dòng 310) đang kỳ vọng `lanh-dao` → 400; PHẢI đảo thành 200, test reject chuyển sang role `quan-tri` đơn lẻ.
- Đổi message lỗi có thể vỡ FE nếu so khớp chuỗi cũ → grep `web/src` khi implement.
- AC-5 chỉ có ý nghĩa thực tế khi story 01 (permission-grant) đã deploy — QA lưu ý.
- IT cần Docker; baseline IT trên máy đang có vấn đề load ApplicationContext (xem memory running-integration-tests).
- Chưa cập nhật `docs/00-overview/features.md` cho slug này (không thấy entry).

### File cần đọc tiếp
- docs/03-dev/specs/r2-business-parity-02-ownership-transfer.md
- docs/02-ba/stories/r2-business-parity-02-ownership-transfer.md (đối chiếu AC)

### Bước kế tiếp đề xuất
- Người dùng review spec → đổi `Status: Approved`
- /dev-implement docs/03-dev/specs/r2-business-parity-02-ownership-transfer.md
- Sau đó: /qa-review docs/02-ba/stories/r2-business-parity-02-ownership-transfer.md docs/03-dev/specs/r2-business-parity-02-ownership-transfer.md
