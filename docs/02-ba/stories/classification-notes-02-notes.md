# User Story: classification-notes-02-notes

> File: `docs/02-ba/stories/classification-notes-02-notes.md` — Owner: BA
> Nguồn kiến trúc: `docs/01-sa/architecture/classification-notes.md`
> Status: Approved

## Bối cảnh

Domain **ghi chú (note)** của FR-16: ghi chú văn bản gắn với một tài liệu
trong kho dùng chung — thêm ngay khi nạp (liên kết FR-11), hoặc thêm/sửa sau
đó cho bất kỳ tài liệu nào trong phạm vi quyền. Vai trò: R1 = X (thêm/sửa),
R2 = R (chỉ đọc), R3 = **không đọc được nội dung ghi chú**. Một tài liệu có
thể có **nhiều** ghi chú; **không có** thao tác xoá ghi chú. Nhãn (tag) là
domain riêng, xem `classification-notes-01-tags.md`.

## User Story 1: Thêm ghi chú ngay khi nạp tài liệu

**As a** người dùng vai trò R1
**I want** thêm một ghi chú ngay trong lúc nạp tài liệu vào kho dùng chung
**So that** ghi lại bối cảnh/thông tin bổ sung về tài liệu ngay từ đầu, không
phải quay lại thao tác riêng

### Acceptance Criteria

```
Given R1 đang thực hiện nạp một tệp vào kho dùng chung (FR-11)
  And R1 nhập kèm nội dung ghi chú không vượt quá 4000 ký tự
When quá trình nạp hoàn tất thành công
Then tài liệu được tạo kèm ghi chú đã nhập, gắn với người tạo và thời điểm tạo
```

- [ ] AC-1: Trường ghi chú khi nạp là **tuỳ chọn** — nạp tài liệu không kèm ghi
      chú vẫn thành công bình thường.
- [ ] AC-2: Nếu nạp thất bại (ví dụ vi phạm định dạng/kích thước ở FR-11/12),
      ghi chú đã nhập **không** được lưu lại thành bản ghi mồ côi.
- [ ] AC-3: Ghi chú nhập khi nạp vượt quá 4000 ký tự bị từ chối kèm thông báo
      rõ ràng, không làm hỏng phần còn lại của thao tác nạp.
- [ ] AC-4: Ghi chú tạo lúc nạp được ghi nhật ký (`note.create`) với metadata
      tối thiểu documentId, noteId — **không** đưa nội dung ghi chú vào audit
      log.

## User Story 2: Thêm ghi chú mới cho tài liệu đã có, trong phạm vi quyền

**As a** người dùng vai trò R1
**I want** thêm một ghi chú mới cho bất kỳ tài liệu nào nằm trong phạm vi
truy cập của mình, tại bất kỳ thời điểm nào sau khi tài liệu đã tồn tại
**So that** bổ sung thông tin cho tài liệu khi có phát sinh, mà không mất
ghi chú cũ

### Acceptance Criteria

- [ ] AC-1: Một tài liệu chấp nhận **nhiều** ghi chú (không giới hạn 1
      ghi-chú/tài-liệu); mỗi ghi chú độc lập, có người tạo và thời điểm tạo
      riêng.
- [ ] AC-2: R1 thêm ghi chú cho tài liệu **ngoài phạm vi truy cập** của mình
      bị từ chối, ghi nhật ký truy cập bị từ chối, không tiết lộ tài liệu có
      tồn tại hay không.
- [ ] AC-3: R2 không thêm được ghi chú (chỉ đọc); R3 không thêm được và cũng
      không đọc được nội dung ghi chú.
- [ ] AC-4: Nội dung ghi chú tối đa 4000 ký tự; vượt quá bị từ chối trước khi
      lưu.
- [ ] AC-5: Số lượng ghi chú mục tiêu cho một tài liệu ở quy mô hiện tại: tối
      đa 20 ghi chú/tài liệu (ngưỡng vận hành tham khảo, không phải giới hạn
      cứng chặn người dùng).

## User Story 3: Sửa ghi chú đã có, bất kỳ ai trong phạm vi quyền

**As a** người dùng vai trò R1
**I want** sửa nội dung một ghi chú đã tồn tại của một tài liệu trong phạm vi
truy cập của mình — kể cả ghi chú đó do người R1 khác tạo
**So that** cập nhật/chỉnh sửa thông tin ghi chú khi cần, không phụ thuộc vào
việc ai là tác giả ban đầu

### Acceptance Criteria

```
Given ghi chú N của tài liệu D đang có expectedUpdatedAt = T1
  And R1 gửi yêu cầu sửa N kèm expectedUpdatedAt = T1
When không có ai khác sửa N kể từ T1
Then N được cập nhật, updated_by/updated_at đổi theo người sửa và thời điểm hiện tại
```

- [ ] AC-1: Bất kỳ R1 nào có D trong phạm vi truy cập đều sửa được ghi chú
      của D, không giới hạn theo người tạo ra ghi chú đó.
- [ ] AC-2: Sửa ghi chú của tài liệu **ngoài phạm vi truy cập** bị từ chối,
      ghi nhật ký truy cập bị từ chối.
- [ ] AC-3: Nếu ghi chú đã bị người khác sửa sau thời điểm client lấy dữ liệu
      (`expectedUpdatedAt` không khớp giá trị hiện tại), yêu cầu sửa bị từ
      chối với mã lỗi xung đột (409) kèm thông báo rõ ràng — không tự động
      ghi đè.
- [ ] AC-4: Sau khi sửa thành công, hệ thống cập nhật hiển thị "sửa lần cuối
      bởi ... vào lúc ..." dựa trên `updated_by`/`updated_at`.
- [ ] AC-5: **Không có** thao tác xoá ghi chú — không tồn tại API/route xoá,
      dưới bất kỳ vai trò nào.
- [ ] AC-6: Nội dung ghi chú **cũ trước khi sửa đè không được khôi phục lại**
      — đây là giới hạn đã được xác nhận (không có bảng lưu phiên bản nội
      dung). Người dùng cần hướng dẫn "thêm ghi chú mới" thay vì sửa đè nếu
      muốn giữ lại nội dung cũ.
- [ ] AC-7: Mọi lần sửa ghi chú thành công sinh một bản ghi audit log
      (`note.update`) với metadata tối thiểu documentId, noteId — không chứa
      nội dung ghi chú. Lịch sử "ai/lúc nào đã sửa ghi chú nào" tra cứu được
      qua audit log, không qua bảng riêng.

## User Story 4: Xem ghi chú của tài liệu

**As a** người dùng vai trò R1 hoặc R2
**I want** xem toàn bộ ghi chú của một tài liệu trong phạm vi truy cập của
mình
**So that** nắm được các thông tin bổ sung đã được ghi nhận cho tài liệu đó

### Acceptance Criteria

- [ ] AC-1: R1/R2 xem được đầy đủ danh sách ghi chú (nội dung, người tạo/sửa,
      thời điểm) của tài liệu nằm trong phạm vi truy cập.
- [ ] AC-2: Xem ghi chú của tài liệu ngoài phạm vi truy cập bị từ chối, không
      tiết lộ tài liệu có tồn tại hay không.
- [ ] AC-3: R3 **không** xem được nội dung ghi chú của bất kỳ tài liệu nào
      (kể cả nếu R3 có phạm vi truy cập tài liệu đó ở khía cạnh khác).

## User Story 5: Ghi chú và nhãn góp phần vào tìm kiếm/đồng bộ dữ liệu

**As a** người dùng vai trò R1 hoặc R2
**I want** tài liệu tìm được qua nội dung ghi chú của nó khi tôi tìm kiếm
trong phạm vi truy cập của mình
**So that** không phải nhớ chính xác tên tài liệu, chỉ cần nhớ nội dung đã
ghi chú

### Acceptance Criteria

- [ ] AC-1: Sau khi thêm/sửa ghi chú thành công, thay đổi được đồng bộ để
      phục vụ tìm kiếm, nhưng **không tức thời tuyệt đối** — có thể có độ
      trễ vài giây đến vài phút trước khi tìm kiếm phản ánh đúng nội dung mới
      nhất (eventual consistency). Đây là hành vi được chấp nhận, không phải
      lỗi hệ thống.
- [ ] AC-2: Thao tác thêm/sửa ghi chú của người dùng **không bao giờ thất bại
      chỉ vì** hệ thống đồng bộ dữ liệu tìm kiếm đang gặp sự cố — ghi chú vẫn
      được lưu thành công.
- [ ] AC-3: Kết quả tìm kiếm theo nội dung ghi chú chỉ trả về tài liệu nằm
      trong phạm vi truy cập của người tìm kiếm tại thời điểm tìm kiếm (áp
      dụng lại quyền, không dựa vào dữ liệu đã lập chỉ mục từ trước).

## Ngoài phạm vi (Out of scope)

- Ghi chú cho tệp cá nhân (FR-14) — không áp dụng.
- Khôi phục nội dung ghi chú cũ (bảng revision lịch sử nội dung) — đã xác
  nhận không cần ở giai đoạn này.
- API xoá ghi chú — cố ý không có, dưới mọi vai trò.
- Engine tìm kiếm toàn văn cụ thể (phân tích tiếng Việt, xếp hạng kết quả,
  giao diện tìm kiếm) — thuộc `ai-search-infra`/`search-fulltext`, chỉ chốt
  hợp đồng dữ liệu ở đây.
- Nhãn (tag) — xem story riêng `classification-notes-01-tags.md`.

## Phụ thuộc / Ghi chú kiến trúc liên quan

- ADR 5 (mô hình nhiều-ghi-chú/tài liệu, quyền sửa theo phạm vi không giới
  hạn tác giả, không xoá, lịch sử dựa audit log, optimistic lock 409).
- ADR 7 (hợp đồng outbox `document.note.changed` cho lập chỉ mục tìm kiếm;
  eventual consistency; lỗi kênh đồng bộ không được chặn thao tác người
  dùng).
- Ràng buộc NFR: ghi chú không làm thay đổi tệp gốc (NFR-05); nội dung ghi
  chú không vào audit_log; R3 không đọc nội dung ghi chú (NFR-04/07).
- Rủi ro liên quan (mục 6 file SA): sửa đè mất nội dung cũ; ghi chú chứa dữ
  liệu nhạy cảm bị lộ qua tìm kiếm nếu chỉ mục không áp lại phạm vi truy cập.

## Câu hỏi mở cho DEV

- [ ] Không có câu hỏi nghiệp vụ còn mở — toàn bộ điểm mở của FR-16 đã được
      SA chốt và người dùng xác nhận (mục 7 file SA, ngày 2026-08-16). DEV
      tham chiếu trực tiếp ADR 5 và ADR 7 khi viết tech spec.
