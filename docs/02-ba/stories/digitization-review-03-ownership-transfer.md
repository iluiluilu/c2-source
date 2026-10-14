# User Story: digitization-review (chuyển quyền sở hữu tài liệu — thao tác vận hành R3)

> File: `docs/02-ba/stories/digitization-review-03-ownership-transfer.md` — Owner: BA
> Nguồn kiến trúc: `docs/01-sa/architecture/digitization-review.md`
> Status: Approved

> **Cập nhật 2026-09-08:** SA nguồn nay đã `Approved`. Cả 4 câu hỏi mở cho
> DEV (mục cuối file) đã được người dùng xác nhận — xem trả lời ngay tại mỗi
> câu bên dưới. DEV-spec tương ứng cũng đã cập nhật theo và chuyển
> `Approved`, sẵn sàng `/dev-implement`.

Story này **không** nằm trong 8 yêu cầu gốc do SA phác thảo trực tiếp, mà là
story mà **chính SA yêu cầu BA bổ sung**: Quyết định 5 của file kiến trúc
chọn phương án "chỉ người nạp tài liệu được duyệt, một cấp duyệt" và ghi rõ ở
mục "Hệ quả" cũng như mục 6 (Rủi ro, mức **Cao**, đã được C02 chấp nhận):

> "khi người nạp nghỉ/chuyển công tác/vắng mặt dài ngày, tài liệu của họ
> **không ai duyệt được** cho tới khi tự xác nhận sau `ocr_stt.auto_confirm_hours`
> [...] Cần bổ sung công cụ vận hành cho R3: **chuyển quyền sở hữu tài
> liệu** (đổi `document.uploader_sub`) khi cần, có ghi `audit_log`."

---

## Story 7: R3 chuyển quyền sở hữu tài liệu

**As a** quản trị viên (R3)
**I want** chuyển quyền sở hữu (người nạp) của một tài liệu từ cán bộ A sang
cán bộ B
**So that** tài liệu vẫn được rà soát/xác nhận kịp thời khi người nạp gốc
vắng mặt, nghỉ việc hoặc chuyển công tác, thay vì phải phụ thuộc hoàn toàn
vào tự động xác nhận sau 24h

### Acceptance Criteria

```
Given tài liệu có document.uploader_sub = A, có ít nhất một run ở trạng thái
      pending hoặc in_review, và A hiện được xác định là vắng mặt/nghỉ việc
When R3 thực hiện thao tác "Chuyển quyền sở hữu tài liệu", chọn người nhận B
Then document.uploader_sub đổi thành B
 And hệ thống ghi audit_log: document_id, from_uploader (A), to_uploader (B),
     thực hiện bởi R3, thời điểm thực hiện
```

- [ ] AC-1: **Điều kiện hợp lệ của B**: B phải là một tài khoản đang hoạt
      động, có vai trò R1 (không bị vô hiệu hoá/xoá), và khác A. Yêu cầu vi
      phạm → từ chối kèm thông báo rõ lý do.
- [ ] AC-2: Ngay sau khi chuyển, khi B mở "Hàng chờ kiểm duyệt", tài liệu xuất
      hiện trong danh sách "tài liệu do tôi nạp" của B (theo đúng điều kiện
      `document.uploader_sub = current_user` của Quyết định 5).
- [ ] AC-3: Ngay sau khi chuyển, A **không còn** thấy tài liệu đó trong hàng
      chờ của mình, và không còn đọc/sửa/xác nhận được (403 nếu cố truy cập
      trực tiếp bằng ID).
- [ ] AC-4: Chuyển quyền sở hữu **không** thay đổi `review_deadline_at` của
      các run đang chờ — deadline vẫn giữ nguyên theo cơ chế snapshot của
      Quyết định 1 (file `digitization-review.md`). Đây là một thao tác
      **khác** với "áp lại thời hạn cho các bản đang chờ" — hai thao tác độc
      lập, không tự động kèm theo nhau.
- [ ] AC-5: R3 thực hiện được thao tác này **mà không cần và không được** đọc
      nội dung tài liệu (văn bản OCR/STT) — đúng ràng buộc "R3 không truy cập
      nội dung" (Quyết định 5); màn hình chuyển quyền sở hữu chỉ hiển thị
      metadata tài liệu (tên file, người nạp hiện tại, trạng thái các run),
      không hiển thị nội dung đã số hoá.
- [ ] AC-6: Thao tác này chỉ đổi `document.uploader_sub`; **không** thay đổi
      phạm vi nhóm/quyền truy cập khác của tài liệu (không đụng tới lớp
      filter phạm vi nhóm dùng chung ở `rbac-group-scope.md`).
- [ ] AC-7: Chuyển quyền sở hữu vẫn thực hiện được với tài liệu mà tất cả các
      run hiện tại đã `confirmed` (không có run đang chờ) — có ý nghĩa cho
      các run tương lai (ví dụ re-run đổi model) sẽ thuộc về B.
- [ ] AC-8: Mọi lần chuyển quyền sở hữu đều ghi `audit_log`, kể cả khi thực
      hiện nhiều lần liên tiếp trên cùng một tài liệu (A→B→C...).

---

## Ngoài phạm vi (Out of scope)

- Chuyển quyền sở hữu hàng loạt (bulk transfer) cho nhiều tài liệu cùng lúc —
  SA chỉ mô tả công cụ cho từng tài liệu; nếu nghiệp vụ cần bulk (ví dụ một
  người nghỉ việc với hàng trăm tài liệu tồn đọng), cần story riêng sau khi
  xác nhận nhu cầu thực tế.
- Quy trình xác định "ai đang vắng mặt/nghỉ việc" (tích hợp HR, workflow phê
  duyệt nghỉ phép...) — ngoài phạm vi hệ thống số hoá, R3 tự xác định bằng
  quy trình nghiệp vụ ngoài hệ thống.
- Thông báo cho B khi được chuyển giao tài liệu (email/push) — giai đoạn 1
  không có kênh thông báo (xem out-of-scope của file SA gốc); B chỉ biết khi
  tự mở hàng chờ kiểm duyệt.
- Thay đổi vai trò/quyền hạn của A hoặc B trong hệ thống — chỉ đổi trường sở
  hữu tài liệu, không liên quan tới quản lý người dùng/Keycloak.

## Phụ thuộc / Ghi chú kiến trúc liên quan

- Quyết định 5 (`digitization-review.md`): chỉ chính chủ được duyệt, một cấp
  duyệt, rủi ro "người nạp vắng mặt" — nguồn gốc trực tiếp của story này.
- Mục 6 (Rủi ro) file SA: rủi ro **Cao** "người nạp vắng mặt", biện pháp giảm
  thiểu là chính công cụ này cộng với lưới đỡ auto-confirm 24h.
- Quyết định 1 (`digitization-review.md`): cơ chế snapshot deadline — giải
  thích vì sao AC-4 nói chuyển quyền sở hữu không tự đổi hạn.
- `rbac-group-scope.md`: lớp filter phạm vi nhóm dùng chung, không bị thay
  đổi bởi thao tác này (AC-6).

## Câu hỏi mở cho DEV

- [x] ~~Màn hình "Chuyển quyền sở hữu tài liệu" đặt ở đâu~~ — **Đã chốt
      2026-09-08:** khu vực quản trị chung, `/admin/document-ownership`
      (cùng nhóm route với `/admin/rbac`, `/admin/system-config`).
- [x] ~~Có bắt buộc nhập lý do (free text) không~~ — **Đã chốt 2026-09-08:**
      không bắt buộc, tối đa 500 ký tự.
- [x] ~~Có cần ràng buộc B phải cùng phạm vi nhóm với A không~~ — **Đã chốt
      2026-09-08:** không ràng buộc, R3 chọn tự do bất kỳ R1 đang hoạt động.
- [x] ~~Có cần tìm kiếm/lọc tài liệu theo "người nạp"~~ — **Đã chốt (nằm sẵn
      trong DEV-spec):** có, `GET /admin/document-ownership?uploaderSub=...`.
