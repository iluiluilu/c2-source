# User Story: classification-notes-01-tags

> File: `docs/02-ba/stories/classification-notes-01-tags.md` — Owner: BA
> Nguồn kiến trúc: `docs/01-sa/architecture/classification-notes.md`
> Status: Approved

## Bối cảnh

Domain **nhãn (tag)** của FR-15: gán/quản lý nhãn *chủ đề* (`topic`, danh mục
mở có kiểm soát) và *loại tài liệu* (`doc_type`, danh mục đóng 4 giá trị) cho
tài liệu trong **kho dùng chung**; lọc và báo cáo tài liệu theo nhãn.
Vai trò: R1 = X (toàn quyền), R2 = R (chỉ đọc), R3 = **không tham gia** (không
quản trị danh mục, không xem thống kê ngoài phạm vi của R3 nếu R3 có phạm vi).
Ghi chú (note) là domain riêng, xem `classification-notes-02-notes.md`.

## User Story 1: R1 gán/gỡ nhãn chủ đề (topic) cho tài liệu

**As a** người dùng vai trò R1
**I want** gắn một hoặc nhiều nhãn chủ đề (topic) cho một tài liệu trong kho
dùng chung, và gỡ nhãn đã gắn
**So that** tài liệu được phân loại theo chủ đề để tra cứu/báo cáo sau này

### Acceptance Criteria

```
Given tài liệu D nằm trong phạm vi truy cập của R1
  And nhãn topic "Hợp đồng" đã tồn tại trong danh mục (active = true)
When R1 gắn nhãn "Hợp đồng" vào D
Then D có thêm liên kết tới nhãn "Hợp đồng"
  And thao tác được ghi nhật ký (audit log: ai, lúc nào, trên tài liệu nào)
```

- [ ] AC-1: Một tài liệu có thể gắn **nhiều** nhãn `topic` cùng lúc (quan hệ
      n-n, không giới hạn phân cấp cha/con).
- [ ] AC-2: Gắn nhãn `topic` đã ngừng dùng (`active = false`) bị từ chối, báo
      rõ lý do ("nhãn đã ngừng sử dụng").
- [ ] AC-3: Gắn nhãn `topic` **chưa tồn tại** trong danh mục:
  - Nếu tham số cấu hình `classification.allow_adhoc_topic_tag = false`
    (mặc định): từ chối, thông báo "nhãn không tồn tại".
  - Nếu tham số này `= true`: tạo nhãn mới rồi gắn luôn.
- [ ] AC-4: Gỡ nhãn `topic` khỏi tài liệu chỉ xoá **liên kết**
      (`document_tag`), không xoá nhãn khỏi danh mục.
- [ ] AC-5: R1 thao tác (gắn/gỡ) trên tài liệu **ngoài phạm vi truy cập** của
      mình bị từ chối (403/tương đương) và được ghi nhật ký là truy cập bị từ
      chối — không được để lộ việc tài liệu có tồn tại hay không.
- [ ] AC-6: R2 gọi API gắn/gỡ nhãn bị từ chối (chỉ có quyền đọc).
- [ ] AC-7: Mọi thao tác gắn/gỡ nhãn thành công đều sinh một bản ghi audit log
      (taxonomy `<domain>.<verb>`, ví dụ `tag.assign` / `tag.unassign`) với
      metadata tối thiểu documentId, tagId — không chứa nội dung nghiệp vụ
      ngoài allowlist.

## User Story 2: Gán & điều chỉnh loại tài liệu (doc_type)

**As a** hệ thống (tự động khi nạp) / R1 (khi cần điều chỉnh)
**I want** mọi tài liệu nạp vào kho dùng chung tự động có đúng một nhãn
`doc_type` suy ra từ định dạng tệp, và R1 có thể sửa lại khi suy đoán sai
**So that** mọi tài liệu luôn phân loại được theo loại tài liệu, phục vụ báo
cáo mà không cần thao tác thủ công thừa

### Acceptance Criteria

```
Given một tệp có MIME type ánh xạ tới nhóm "hình ảnh" đang được nạp vào kho
When quá trình nạp hoàn tất
Then tài liệu được tự động gắn đúng 1 nhãn doc_type = "hình ảnh"
```

- [ ] AC-1: Ngay khi nạp thành công, tài liệu được tự động gắn **đúng một**
      nhãn `doc_type` thuộc 4 giá trị cố định: văn bản / hình ảnh / âm thanh /
      video (suy từ bảng ánh xạ MIME → doc_type).
- [ ] AC-2: `doc_type` là **bắt buộc** — không tồn tại trạng thái "tài liệu
      chưa phân loại doc_type" sau khi nạp thành công.
- [ ] AC-3: R1 sửa lại `doc_type` của một tài liệu đã nạp (ví dụ: PDF chứa ảnh
      scan, muốn đổi từ "văn bản" sang "hình ảnh"): thao tác thay thế nhãn
      `doc_type` cũ bằng nhãn mới, vẫn đảm bảo đúng 1 `doc_type`/tài liệu tại
      mọi thời điểm.
- [ ] AC-4: R2 không sửa được `doc_type` (chỉ đọc).
- [ ] AC-5: Với tệp có MIME **không map được** vào 4 nhóm doc_type tại thời
      điểm nạp: nạp tệp vẫn thành công (không chặn FR-11), nhưng tài liệu
      được đưa vào danh sách "cần R1 phân loại thủ công" — hệ thống **không**
      tự đoán/gán bừa một giá trị doc_type.
- [ ] AC-6 (dữ liệu đã có trước tính năng — one-off): tài liệu đã nạp trước
      khi ràng buộc "doc_type bắt buộc" có hiệu lực phải được backfill suy ra
      từ MIME đã lưu sẵn; tài liệu có MIME không map được phải xuất hiện
      trong một danh sách để R1 xử lý thủ công trước khi coi việc chuẩn hoá
      dữ liệu là hoàn tất. (Đây là công việc vận hành một lần, không phải API
      lặp lại cho người dùng cuối — DEV tự quyết định hình thức: script/báo
      cáo/màn hình.)

## User Story 3: R1 quản lý danh mục nhãn chủ đề (topic)

**As a** người dùng vai trò R1
**I want** tạo mới, đổi tên và ngừng dùng nhãn trong danh mục `topic`
**So that** danh mục nhãn chủ đề luôn gọn gàng, nhất quán, không phân mảnh

### Acceptance Criteria

- [ ] AC-1: R1 tạo nhãn `topic` mới với tên (label) chưa tồn tại (so khớp
      không phân biệt hoa/thường, đã chuẩn hoá khoảng trắng) — trùng tên bị
      từ chối.
- [ ] AC-2: R1 đổi tên nhãn `topic` đã có; các liên kết tài liệu-nhãn hiện có
      giữ nguyên, không bị mất.
- [ ] AC-3: R1 **ngừng dùng** (deactivate) một nhãn `topic`: nhãn không còn
      gắn được cho tài liệu mới, nhưng **không bị xoá** — vẫn hiển thị trên
      tài liệu đã gắn trước đó và vẫn tính trong báo cáo lịch sử.
- [ ] AC-4: Không tồn tại API xoá cứng (hard delete) nhãn `topic` đã từng được
      gắn cho tài liệu.
- [ ] AC-5: R2 và R3 không quản lý được danh mục nhãn (không tạo/đổi
      tên/ngừng dùng) — chỉ R1.
- [ ] AC-6: Không có API tạo/đổi/ngừng dùng cho nhãn `doc_type` — 4 giá trị
      là cố định, quản lý ở cấu hình hệ thống, không qua màn hình danh mục.

## User Story 4: Lọc danh sách tài liệu theo nhãn

**As a** người dùng vai trò R1 hoặc R2
**I want** lọc danh sách tài liệu trong kho dùng chung theo nhãn chủ đề
và/hoặc loại tài liệu
**So that** tìm nhanh tài liệu thuộc một chủ đề/loại cụ thể

### Acceptance Criteria

- [ ] AC-1: Lọc theo một nhãn `topic` trả về đúng các tài liệu có gắn nhãn đó
      **và** nằm trong phạm vi truy cập của người gọi.
- [ ] AC-2: Lọc theo `doc_type` trả về đúng các tài liệu thuộc loại đó, cũng
      giới hạn trong phạm vi truy cập của người gọi.
- [ ] AC-3: Danh mục nhãn hiển thị cho người dùng chọn lọc (`GET` danh mục)
      **không** yêu cầu lọc theo phạm vi — mọi R1/R2 thấy cùng danh sách tên
      nhãn.
- [ ] AC-4: Kết quả lọc trả về trong ≤ 2 giây (p95) ở quy mô danh mục hiện tại
      (≤ ~500 nhãn topic, ≤ 10 nhãn/tài liệu).

## User Story 5: Báo cáo tổng hợp số lượng tài liệu theo nhãn

**As a** người dùng vai trò R1 hoặc R2
**I want** xem số lượng tài liệu theo từng nhãn (theo `topic` hoặc theo
`doc_type`), lọc được theo khoảng thời gian và theo thư mục kho
**So that** đánh giá được cơ cấu tài liệu đang có theo chủ đề/loại

### Acceptance Criteria

- [ ] AC-1: Báo cáo trả về số lượng tài liệu theo từng nhãn, có thể lọc theo
      `kind` (topic hoặc doc_type), theo khoảng thời gian, theo thư mục kho.
- [ ] AC-2: Số đếm trong báo cáo **luôn được tính sau khi áp dụng phạm vi
      truy cập của người gọi báo cáo** — hai người dùng khác phạm vi xem
      cùng một nhãn có thể thấy số liệu khác nhau; **đây là hành vi đúng**,
      không phải lỗi (cần ghi rõ trong tài liệu hướng dẫn người dùng, tránh
      báo cáo lỗi nhầm).
- [ ] AC-3: R3 không truy cập được báo cáo này (không thuộc ma trận vai trò
      FR-15).

## Ngoài phạm vi (Out of scope)

- Nhãn **không phải** cơ chế phân quyền; không có AC nào về việc ẩn/hiện tài
  liệu dựa trên "mức độ" của nhãn. Phạm vi truy cập chỉ do nhóm quyết định
  (`rbac-group-scope.md`).
- Không phục hồi lại `kind` "mật"/"đơn vị" dưới bất kỳ tên gọi nào.
- Nhãn cho tệp cá nhân (FR-14) — không áp dụng.
- Phân cấp nhãn (cây chủ đề cha/con) — không thuộc phạm vi.
- Danh mục nhãn theo từng kho/thư mục/nhóm riêng — danh mục luôn toàn cục.
- Engine/mapping tìm kiếm toàn văn theo nhãn, định dạng chi tiết màn hình báo
  cáo/export — thuộc `ai-search-infra`/`search-fulltext`/`export-reporting`.
- Ghi chú (note) — xem story riêng `classification-notes-02-notes.md`.

## Phụ thuộc / Ghi chú kiến trúc liên quan

- ADR 1 (typed tag, 2 kind cố định `topic`/`doc_type`).
- ADR 2 (danh mục đóng/mở có kiểm soát, tham số `classification.allow_adhoc_topic_tag`).
- ADR 3 (quan hệ n-n phẳng, toàn cục, đúng 1 doc_type/tài liệu).
- ADR 4 (bắt buộc `DocumentScopeGuard` ở mọi endpoint tags; danh mục không lọc
  phạm vi nhưng số đếm báo cáo phải lọc).
- ADR 6 (gán doc_type tự động khi nạp, bắt buộc NOT NULL, cần backfill dữ
  liệu cũ).
- ADR 7 (phần liên quan tới báo cáo: API tổng hợp theo nhãn, luôn lọc sau
  phạm vi người gọi).
- Rủi ro liên quan (mục 6 file SA): rò rỉ metadata do thiếu guard; nhãn bị
  hiểu nhầm là mức độ mật; phân mảnh danh mục topic; mất liên kết khi migrate
  xoá `unit`/`secrecy`; tài liệu cũ thiếu/lệch doc_type.

## Câu hỏi mở cho DEV

- [ ] Hình thức cụ thể để R1 xử lý "danh sách tài liệu MIME không map được
      doc_type" (AC-5, User Story 2) — màn hình riêng, mục trong trang quản lý
      kho, hay báo cáo xuất file — DEV tự chọn theo tech spec, miễn đáp ứng
      "không tự đoán giá trị".
- [ ] Ngưỡng/khối lượng cụ thể để chạy migration backfill doc_type (chạy
      nền, giới hạn batch...) — thuộc quyết định kỹ thuật của DEV, không ảnh
      hưởng tới AC nghiệp vụ ở trên.
