# User Story: warehouse-structure

> File: `docs/02-ba/stories/warehouse-structure.md` — Owner: BA
> Nguồn kiến trúc: `docs/01-sa/architecture/warehouse-structure.md`
> Status: Approved

R3 quản lý danh mục kho dùng chung (FR-13): tạo/sửa cây thư mục (động, có kiểm
soát), theo dõi dung lượng, cảnh báo/chặn theo ngưỡng. Cấu trúc thư mục là
"khung bắt buộc" mà người dùng phải chọn khi nạp dữ liệu (FR-11). Cấu trúc kho
KHÔNG phải biên phân quyền — phân quyền hoàn toàn theo nhóm (FR-08).

---

## Story 1 — R3 tạo/sửa cây thư mục kho (động, có kiểm soát)

**As a** Quản trị hệ thống (R3)
**I want** tạo và chỉnh sửa cấu trúc thư mục kho theo nhu cầu tổ chức dữ liệu
**So that** người dùng có khung thư mục đúng để nạp dữ liệu vào

### Acceptance Criteria

```
Given tôi là R3 ở giao diện quản lý cấu trúc kho
When tôi tạo hoặc sửa một thư mục trong cây
Then hệ thống lưu thay đổi nếu hợp lệ và cập nhật cây hiển thị
```

- [ ] AC-1: Chỉ R3 tạo/sửa cấu trúc kho; vai trò khác bị chặn.
- [ ] AC-2: Độ sâu cây tối đa **5 cấp**; vượt quá bị từ chối.
- [ ] AC-3: Tên thư mục chỉ gồm chữ không dấu, số, gạch ngang/gạch dưới, tối đa 100 ký tự; vi phạm bị từ chối kèm thông báo.
- [ ] AC-4: Không cho trùng tên trong cùng một thư mục cha (unique theo `parent_id` + `name`).
- [ ] AC-5: Sửa cấu trúc kho KHÔNG làm gián đoạn truy cập tài liệu hiện có (NFR Availability).

---

## Story 2 — Đổi tên / di chuyển thư mục không phá tham chiếu

**As a** R3
**I want** đổi tên hoặc di chuyển nhánh thư mục đã có dữ liệu một cách an toàn
**So that** điều chỉnh tổ chức kho mà không làm hỏng đường dẫn/tham chiếu tài liệu

### Acceptance Criteria

```
Given một thư mục đã chứa dữ liệu
When tôi đổi tên (giữ vị trí) hoặc di chuyển thư mục sang thư mục cha khác
Then tham chiếu tài liệu vẫn giữ theo folder_id; đổi tên không đổi path, còn
     di chuyển thì cập nhật lại path theo vị trí mới
```

- [ ] AC-1: **Đổi tên** là soft-rename: giữ nguyên `folder_id`, chỉ đổi tên hiển thị (Quyết định 1, hệ quả / Rủi ro).
- [ ] AC-2: **Di chuyển** thư mục sang thư mục cha khác: đổi `parent_id` và **cập nhật lại thông tin path (đường dẫn) của thư mục và toàn bộ nhánh con theo vị trí mới**; `folder_id` (khóa chính) vẫn giữ nguyên.
- [ ] AC-3: Sau đổi tên hoặc di chuyển, tài liệu đang tham chiếu tới thư mục vẫn truy cập được bình thường (tham chiếu theo `folder_id`, không vỡ).
- [ ] AC-4: Đổi tên/di chuyển vẫn phải tuân thủ ràng buộc tên và unique trong thư mục cha đích (như Story 1); không cho di chuyển làm vượt độ sâu tối đa 5 cấp.

---

## Story 3 — Chặn xóa thư mục còn dữ liệu

**As a** R3
**I want** hệ thống chặn xóa thư mục vẫn còn dữ liệu
**So that** tránh mất dữ liệu ngoài ý muốn

### Acceptance Criteria

```
Given một thư mục còn chứa dữ liệu (tệp hoặc thư mục con còn dữ liệu)
When tôi cố xóa thư mục đó
Then hệ thống từ chối và yêu cầu di chuyển/dọn dữ liệu trước
```

- [ ] AC-1: Không xóa được thư mục còn dữ liệu (ràng buộc mục 2); thông báo rõ lý do.
- [ ] AC-2: Chỉ xóa được thư mục rỗng (không còn tệp và không còn thư mục con chứa dữ liệu). **"Còn dữ liệu" chỉ tính tệp đang hoạt động (chưa xóa mềm)** — tệp đã ở thùng rác (xóa mềm, chờ hết hạn `retention.trash_retention_days`) KHÔNG được tính, vì người dùng đã chủ động xóa; một thư mục chỉ còn tệp trong thùng rác được coi là rỗng và xóa được bình thường.

---

## Story 4 — Chọn thư mục đích theo khung bắt buộc khi nạp dữ liệu

**As a** người dùng nghiệp vụ (R1/R2)
**I want** chọn thư mục đích trong cấu trúc kho do R3 định nghĩa khi nạp dữ liệu
**So that** dữ liệu được tổ chức đúng khung chung, không tạo thư mục tùy tiện

### Acceptance Criteria

```
Given tôi đang nạp dữ liệu (FR-11)
When tôi chọn thư mục đích
Then tôi chỉ chọn được trong cấu trúc kho R3 đã định nghĩa, KHÔNG tạo được thư
     mục mới ngoài khung
```

- [ ] AC-1: Người dùng KHÔNG tạo được thư mục ngoài cấu trúc R3 định nghĩa khi upload (ràng buộc mục 2).
- [ ] AC-2: Cây thư mục hiển thị để chọn phản hồi nhanh (NFR Performance).

---

## Story 5 — Theo dõi dung lượng, cảnh báo 85% và chặn nạp mới 95%

**As a** R3
**I want** theo dõi dung lượng kho và được cảnh báo/chặn theo ngưỡng cấu hình
**So that** phản ứng sớm trước khi kho đầy, tránh sự cố ngừng nạp dữ liệu đột ngột

> **Ghi chú làm rõ phạm vi:** Ngưỡng 85%/95% ở đây là giới hạn **cấp toàn kho
> dùng chung**, phục vụ R3/vận hành theo dõi và chặn khi kho gần đầy. **KHÔNG
> phải quota theo từng user** — kho dùng chung không cấp hạn mức riêng cho mỗi
> user; user (R1/R2) nạp vào kho chung không bị trừ theo một hạn mức cá nhân
> nào. Hạn mức theo từng user chỉ áp cho **kho cá nhân** (FR-14,
> `personal-storage-quota.md`). Kho chung và kho cá nhân là hai vùng lưu trữ
> độc lập, mỗi vùng có cơ chế giới hạn riêng.

### Acceptance Criteria

```
Given dung lượng kho dùng chung thay đổi khi nạp/xóa dữ liệu
When dung lượng đã dùng đạt các ngưỡng cấu hình
Then ở 85% hệ thống cảnh báo R3 (không chặn nạp), ở 95% hệ thống chặn nạp mới
```

- [ ] AC-1: Hiển thị dung lượng đã dùng / còn lại của kho, phản hồi nhanh. **Dung lượng đã dùng = tổng size các tệp thuộc kho dùng chung, cộng dồn từ siêu dữ liệu tệp lưu trong database (KHÔNG đọc report từ SeaweedFS).** Bao gồm cả tệp đang ở thùng rác (đã xóa mềm, chờ hết hạn `retention.trash_retention_days`) — dữ liệu vật lý vẫn chiếm dung lượng lưu trữ thật cho tới khi bị xóa cứng, nên vẫn phải tính vào dung lượng đã dùng để không làm sai lệch cảnh báo/chặn ngưỡng (Story 5 AC-2/AC-3).
- [ ] AC-2: Đạt **85%** dung lượng tối đa hiện có → cảnh báo R3 trên giao diện quản trị, KHÔNG chặn nạp dữ liệu.
- [ ] AC-3: Đạt **95%** → chặn nạp dữ liệu mới (FR-11), NHƯNG vẫn cho phép **xóa** dữ liệu để giải phóng dung lượng. (Lưu ý: di chuyển thư mục *trong cùng kho chung* KHÔNG làm giảm dung lượng đã dùng — chỉ xóa mới giải phóng; nên chỉ thao tác xóa được coi là cách giải phóng.)
- [ ] AC-4: Hai **ngưỡng %** (85/95) đọc từ `system_config` (R3 chỉnh được), không hard-code. **Con số "dung lượng tối đa của kho chung" (mẫu số để tính %) lưu ở một cột riêng trong bảng warehouse, tính bằng **bytes** (nhất quán với `storage_quota_bytes`), là giá trị trong database do R3 chỉnh, KHÔNG liên quan năng lực phần cứng SeaweedFS.** Chỉ có **một** con số dung lượng tối đa cho **toàn kho dùng chung**, KHÔNG đặt hạn mức riêng theo từng nhánh/thư mục.
- [ ] AC-5: Kiểm tra ngưỡng ở **2 điểm** trong luồng nạp dữ liệu (`data-ingestion.md`): (a) **trước** khi bắt đầu nhận tệp/batch — nếu dung lượng đã dùng hiện tại đã ≥95%, chặn ngay từ đầu, không nhận tệp mới (cùng vị trí với pre-check số tệp/dung lượng batch ở Story 2 AC-3 của `data-ingestion.md`); (b) **sau** khi mỗi tệp ghi xong (đúng thời điểm "dung lượng đã dùng" tăng lên theo AC-1) — tính lại % và chuyển trạng thái cảnh báo/chặn ngay nếu vừa vượt ngưỡng, để bắt kịp trường hợp nhiều lượt tải đồng thời cùng đẩy dung lượng vượt ngưỡng giữa hai lần kiểm tra đầu batch (Rủi ro).
- [ ] AC-6: Khi R3 hạ "dung lượng tối đa" xuống **dưới mức đã dùng** (tỷ lệ vọt lên ≥ ngưỡng), hệ thống **vẫn cho lưu cấu hình** nhưng chuyển ngay sang trạng thái tương ứng: ≥85% → cảnh báo, ≥95% → chặn nạp mới (vẫn cho xóa để giải phóng). KHÔNG xóa dữ liệu hiện có để "vừa" hạn mức mới.

---

## Ngoài phạm vi (Out of scope)

- Không gian lưu trữ cá nhân theo tài khoản (FR-14) — thuộc `personal-storage-quota.md`.
- Luồng upload chi tiết + kiểm tra an toàn/toàn vẹn (FR-11/12) — thuộc `data-ingestion.md`.
- Gắn phân quyền theo nhánh cây thư mục — đã chốt KHÔNG làm; phân quyền hoàn toàn theo nhóm FR-08 (Quyết định 2).
- Cấu hình **2 ngưỡng %** (85/95) — thuộc `system-config.md`; story này chỉ tiêu thụ và enforce. (Con số **dung lượng tối đa của kho** KHÔNG ở system-config mà cấu hình tại màn hình quản lý kho — xem Story 5 AC-4.)

## Phụ thuộc / Ghi chú kiến trúc liên quan

- Quyết định 1: cây động có kiểm soát — tối đa 5 cấp, quy tắc tên, unique theo `parent_id`+`name`, soft-rename giữ `folder_id`.
- Quyết định 2: cấu trúc kho KHÔNG là biên phân quyền; phân quyền theo nhóm (`rbac-group-scope.md`, FR-08).
- Quyết định 3: ngưỡng cảnh báo 85% / chặn 95% (2 ngưỡng % trong `system_config`, `system-config.md`, không nhạy cảm); con số dung lượng tối đa của kho lưu ở cột riêng bảng warehouse.
- **Phân biệt giới hạn:** kho dùng chung chỉ có giới hạn **cấp toàn kho** (85/95% cho R3/vận hành), KHÔNG có quota per-user; quota per-user chỉ tồn tại ở kho cá nhân (FR-14, `personal-storage-quota.md`). Hai vùng độc lập.
- Phụ thuộc `data-ingestion.md`: Story 4 (chọn đích) và AC chặn nạp ở 95% (Story 5) liên kết với luồng upload FR-11.
- Đã có `WarehouseFolder`/`FolderNode`/`WarehouseController` (backend), `FolderTree.tsx` (web).

## Quyết định đã chốt (trả lời câu hỏi mở trước đó)

- **Nguồn "dung lượng đã dùng" (Story 5 AC-1):** cộng dồn size các tệp thuộc kho
  dùng chung từ siêu dữ liệu tệp lưu trong database — KHÔNG đọc report SeaweedFS.
- **Nguồn "dung lượng tối đa của kho" (Story 5 AC-4):** con số lưu ở cột riêng
  trong bảng warehouse (giá trị trong database, R3 chỉnh được), không liên quan
  năng lực phần cứng SeaweedFS. Hai ngưỡng % (85/95) vẫn ở `system_config`.
- **"Di chuyển thư mục" (Story 2):** phân biệt với đổi tên — di chuyển đổi
  `parent_id` và **cập nhật lại path** của thư mục + toàn bộ nhánh con theo vị
  trí mới; `folder_id` giữ nguyên nên tham chiếu tài liệu không vỡ.
- **Điểm chèn kiểm tra ngưỡng dung lượng kho (Story 5 AC-5):** kiểm ở 2 điểm
  trong luồng nạp dữ liệu — trước khi nhận tệp/batch (pre-check, chặn sớm
  nếu đã ≥95%) và sau khi mỗi tệp ghi xong (post-check, bắt kịp race
  condition khi nhiều lượt tải đồng thời). Cần thống nhất vị trí chèn cụ
  thể với `data-ingestion.md` ở bước DEV-spec (tham chiếu Story 2 AC-3 của
  tài liệu đó cho pattern pre-check tương tự).
- **Tệp trong thùng rác với Story 3 (chặn xóa) và Story 5 (dung lượng):**
  KHÔNG tính là "còn dữ liệu" để chặn xóa thư mục (người dùng đã chủ động
  xóa) nhưng VẪN tính vào "dung lượng đã dùng" (vẫn chiếm dung lượng vật lý
  thật cho tới khi hết hạn retention). Hai quy tắc độc lập, không mâu thuẫn
  vì phục vụ hai mục đích khác nhau (UX xóa thư mục vs. cảnh báo dung
  lượng thật).

## Câu hỏi mở cho DEV

Không còn câu hỏi mở nào cho DEV ở bước BA.

⚠️ **Lưu ý phụ thuộc chéo cho DEV:** 2 quyết định về thùng rác ở trên giả
định trước cơ chế xóa mềm/thùng rác của FR-26/27 (chưa có SA-spec/BA-story
riêng trong bộ 7 tài liệu hiện tại — nhóm FR-01–07, FR-11–14 không bao gồm
FR-26/27). Khi FR-26/27 được đặc tả chính thức, cần đối chiếu lại 2 quyết
định này có còn khớp với mô hình dữ liệu thực tế của thùng rác hay không
(vd. tên trường trạng thái xóa mềm, cách join để loại trừ tệp đã xóa khi
đếm "còn dữ liệu").
