# User Story: data-ingestion

> File: `docs/02-ba/stories/data-ingestion.md` — Owner: BA
> Nguồn kiến trúc: `docs/01-sa/architecture/data-ingestion.md`
> Status: Approved

Tiếp nhận dữ liệu đầu vào qua web (FR-11): tải tệp lẻ hoặc cả thư mục (giữ cấu
trúc con) vào thư mục đích theo cấu trúc kho; kiểm tra toàn vẹn (SHA-256), quét
mã độc, kiểm tra định dạng trước khi nhập kho (FR-12). Tệp nhiễm mã độc **tuyệt
đối không** vào kho. Định dạng cho phép và giới hạn tải là tham số cấu hình
được (nối với `system-config.md`).

---

## Story 1 — Tải tệp lẻ vào thư mục đích

**As a** người dùng nghiệp vụ (R1/R2)
**I want** tải một hoặc nhiều tệp lẻ vào một thư mục đích trong cấu trúc kho
**So that** đưa dữ liệu vào kho đúng vị trí

### Acceptance Criteria

```
Given tôi đã chọn một thư mục đích hợp lệ theo cấu trúc kho (FR-13)
When tôi chọn tệp và bắt đầu tải lên
Then hệ thống tải từng tệp, kiểm tra an toàn/toàn vẹn/định dạng rồi nhập kho
     tệp hợp lệ
```

- [ ] AC-1: **Bắt buộc** chọn thư mục đích theo cấu trúc kho trước khi tải (ràng buộc mục 2); chưa chọn thì không cho tải.
- [ ] AC-2: Tải lên không chặn các thao tác khác của người dùng (NFR-01).
- [ ] AC-3: Mỗi tệp hợp lệ sau khi nhập kho có mã băm SHA-256 lưu cùng siêu dữ liệu (NFR-05).
- [ ] AC-4: UI hiển thị tiến trình và kết quả từng tệp (thành công/thất bại + lý do).

---

## Story 2 — Tải cả một thư mục (giữ cấu trúc con)

**As a** người dùng nghiệp vụ (R1/R2)
**I want** chọn cả một thư mục để tải, giữ nguyên cấu trúc thư mục con
**So that** đưa cả cây thư mục vào kho mà không phải tải từng tệp thủ công

### Acceptance Criteria

```
Given tôi chọn một thư mục (gồm nhiều tệp và thư mục con)
When tôi bắt đầu tải lên
Then hệ thống tải từng tệp độc lập, giữ đường dẫn tương đối của thư mục con
     trong cấu trúc đích
```

- [ ] AC-1: Kéo-thả thư mục vào vùng thả, gửi từng tệp kèm đường dẫn tương đối (Quyết định 1); cấu trúc thư mục con được tái tạo trong đích.
- [ ] AC-2: Một tệp lỗi trong lượt tải **không** chặn các tệp hợp lệ còn lại (ràng buộc mục 2).
- [ ] AC-3: Kiểm tra giới hạn ở **cả client và server**: (a) client đếm số tệp + tổng dung lượng ngay khi người dùng chọn thư mục (đã có sẵn qua File API), so với 2 ngưỡng lấy từ `system-config`, chặn ngay tại trình duyệt nếu vượt (không gửi request nào, tiết kiệm băng thông); (b) server xác thực lại — client phải mở một **batch** trước khi gửi tệp (API tạo batch, khai báo số tệp + tổng dung lượng ước tính), server từ chối ngay nếu khai báo đã vượt ngưỡng; mỗi tệp sau đó gửi kèm `batchId`, server cộng dồn số tệp/dung lượng **thực nhận** theo batch đó — nếu vượt ngưỡng thực tế thì dừng nhận tệp mới trong batch (các tệp đã nhận thành công trước đó vẫn giữ nguyên). Server luôn là nguồn xác thực cuối cùng, không tin riêng kết quả đếm ở client.
- [ ] AC-4: Hai giới hạn trên đọc từ `system-config` (R3 cấu hình lại được), KHÔNG hard-code.
- [ ] AC-5: Nút "Chọn tệp" chỉ cho chọn tệp (một hoặc nhiều), **không** chọn thư mục; muốn tải thư mục người dùng kéo-thả thư mục vào vùng thả. Vùng thả nhận tệp lẻ, thư mục, hoặc cả hai trong một lần thả.
- [ ] AC-6: Khi tải thư mục **không** hiện popup xác nhận của trình duyệt.
- [ ] AC-7: UI có chữ hướng dẫn cách tải thư mục (kéo-thả vào vùng thả); trong lúc đọc cây thư mục hiển thị "Đang đọc thư mục…".
- [ ] AC-8: Đường dẫn tương đối giữ đúng quy tắc cũ (bỏ tên thư mục gốc được thả; tệp nằm trực tiếp trong gốc thì đường dẫn rỗng) để dedup/resume của Story 4 không đổi.

---

## Story 3 — Kiểm tra an toàn & toàn vẹn trước khi nhập kho (FR-12)

**As a** hệ thống / quản trị bảo mật
**I want** kiểm tra toàn vẹn, quét mã độc và kiểm tra định dạng mọi tệp trước khi nhập kho
**So that** chỉ tệp sạch và hợp lệ mới vào kho

### Acceptance Criteria

```
Given một tệp đang được tải lên
When hệ thống xử lý tệp trước khi nhập kho
Then hệ thống kiểm tra định dạng, tính SHA-256, quét mã độc (ClamAV) theo
     đúng thứ tự đó, dừng ngay ở bước đầu tiên fail; chỉ nhập kho nếu vượt
     qua cả 3 bước
```

- [ ] AC-1: **Thứ tự kiểm tra cố định: (1) định dạng → (2) SHA-256 → (3) quét mã độc**, xếp theo độ phức tạp xử lý tăng dần (định dạng chỉ cần đối chiếu allowlist trong cache, rẻ nhất; SHA-256 cần đọc toàn bộ tệp; quét mã độc gọi ClamAV, đắt nhất). Fail ở bước nào thì **dừng ngay**, không chạy các bước sau — tránh tốn tài nguyên quét mã độc cho tệp đã sai định dạng.
- [ ] AC-2: Tệp có định dạng ngoài allowlist bị từ chối kèm thông báo rõ ràng; KHÔNG tính SHA-256 hay quét mã độc cho tệp này.
- [ ] AC-3: Tệp **nhiễm mã độc tuyệt đối không vào kho**; bị từ chối và báo lý do (chỉ quét tới bước này nếu đã qua định dạng + SHA-256).
- [ ] AC-4: Quét mã độc dùng bộ chữ ký ClamAV (cập nhật ngoại tuyến — vận hành ngoài phạm vi story này).
- [ ] AC-5: Tệp không qua được kiểm tra ở bất kỳ bước nào được ghi nhận (lý do từ chối, đúng bước fail) để người dùng biết và truy vết.
- [ ] AC-6: Kiểm tra định dạng đọc allowlist từ **cache Redis** (dùng chung với Story 5 AC-3), không query DB mỗi lượt upload (Quyết định 2, cập nhật cơ chế cache).

---

## Story 4 — Tải lại khi gián đoạn

**As a** người dùng nghiệp vụ
**I want** tải lại và tiếp tục từ các tệp chưa hoàn tất khi lượt tải bị gián đoạn
**So that** không phải tải lại từ đầu cả thư mục lớn

### Acceptance Criteria

```
Given một lượt tải (tệp lẻ hoặc thư mục) bị gián đoạn giữa chừng
When tôi tải lại
Then hệ thống tiếp tục với các tệp chưa hoàn tất, không bắt tải lại các tệp đã
     nhập kho thành công
```

- [ ] AC-1: Gián đoạn khi tải → có thể tải lại (NFR Availability).
- [ ] AC-2: **Đơn vị hoàn tất là cả tệp, không resume theo byte/chunk** — một tệp bị dừng giữa chừng (ở bất kỳ bước nào: đang upload, đang scan, hay chưa lưu xong metadata) được coi là **chưa hoàn tất** và khi tải lại sẽ tải lại **từ đầu** tệp đó. "Đã nhập kho thành công" = tồn tại bản ghi tài liệu tương ứng trong DB (upload + scan + lưu metadata đã hoàn tất trọn vẹn — không có trạng thái "dở dang" lưu DB, chỉ có "xong" hoặc "chưa có gì"). Khi tải lại đúng thư mục, server nhận diện tệp trùng (theo thư mục đích + đường dẫn tương đối + tên tệp) đã có bản ghi hoàn tất thì trả về "đã tồn tại, bỏ qua" thay vì xử lý lại hoặc tạo bản ghi trùng.
- [ ] AC-3: Client hỗ trợ hàng đợi + retry có backoff cho tệp thất bại tạm thời (Rủi ro).

---

## Story 5 — R3 quản lý danh mục định dạng cho phép (allowlist)

**As a** Quản trị hệ thống (R3)
**I want** thêm/bớt định dạng tệp cho phép qua giao diện quản trị
**So that** điều chỉnh loại dữ liệu được nhập kho mà không cần sửa code/deploy lại

### Acceptance Criteria

```
Given tôi là R3 ở giao diện quản trị định dạng cho phép
When tôi thêm hoặc gỡ một định dạng và lưu
Then danh mục allowlist được cập nhật và áp dụng cho các lượt upload tiếp theo
```

- [ ] AC-1: Allowlist lưu ở bảng `allowed_file_format` (DB), KHÔNG hard-code trong `MimeAllowlist.java` (chuyển vai trò thành seed migration).
- [ ] AC-2: Seed ban đầu gồm các định dạng đã chốt (văn bản/ảnh/âm thanh/video ở Quyết định 2).
- [ ] AC-3: Allowlist cache bằng **Redis** (không phải cache in-process riêng từng instance) — khi R3 lưu thay đổi, backend ghi thẳng giá trị mới vào Redis ngay trong cùng request (đồng bộ), mọi instance backend đọc cùng một giá trị từ Redis nên định dạng mới/đã gỡ có hiệu lực ngay lập tức và nhất quán giữa các instance (không có độ trễ như cơ chế TTL đơn thuần). Vẫn đặt TTL trên key Redis làm lớp dự phòng nếu lỡ ghi.
- [ ] AC-4: Chỉ R3 sửa được; thao tác được ghi audit (nối `system-config.md`, FR-09).

---

## Ngoài phạm vi (Out of scope)

- Quản lý cấu trúc kho / chọn thư mục đích ở mức thiết kế (FR-13) — thuộc `warehouse-structure.md`.
- OCR/STT sau khi nhập kho (FR-17–20) — story riêng.
- Nén/giải nén archive ở client-server (phương án B đã loại) — không làm.
- Chu kỳ cập nhật signature ClamAV — quy trình vận hành (SOP/runbook), không thuộc story này.
- Chi tiết màn hình cấu hình 2 giới hạn tải (số tệp/dung lượng) — thuộc `system-config.md`; story này chỉ tiêu thụ giá trị.

## Phụ thuộc / Ghi chú kiến trúc liên quan

- Quyết định 1: tải thư mục chỉ qua kéo-thả vào vùng thả (duyệt cây bằng `webkitGetAsEntry()`), nút "Chọn tệp" chỉ chọn tệp; mỗi tệp một request, giữ đường dẫn tương đối.
- Quyết định 2: allowlist DB-backed + cache; seed từ `MimeAllowlist.java`.
- Phụ thuộc `system-config.md`: `ingest.max_files_per_batch` (500), `ingest.max_batch_size_bytes` (5 GB), `allowed_file_format` (bảng riêng).
- Phụ thuộc `warehouse-structure.md`: chọn thư mục đích theo cấu trúc kho (FR-13);
  luồng nạp tệp còn phải gọi kiểm tra ngưỡng dung lượng kho ở 2 điểm (trước
  khi nhận batch, sau khi mỗi tệp ghi xong) — xem `warehouse-structure.md`
  Story 5 AC-5, cần thống nhất vị trí chèn cụ thể ở bước DEV-spec.
- Hạ tầng đã chốt: SeaweedFS (object storage), ClamAV (`ClamdService.java`), Spring Boot multipart.
- ⚠️ **Hạ tầng mới cần bổ sung — Redis chưa có trong repo hiện tại:** đã
  kiểm tra `docker-compose.yml`/`docker-compose.dev.yml`
  (gốc source_v2) và `pom.xml` (`backend/`) — không có Redis ở cả hai
  nơi (giống RabbitMQ ở `auth-session.md`, cũng phải bổ sung mới). Cần: (1)
  thêm service `redis` vào docker-compose, (2) thêm dependency
  `spring-boot-starter-data-redis` vào `pom.xml`.
- Cache đã đồng bộ với `system-config.md` (Quyết định 4): toàn bộ tham số
  `system_config` (kể cả `allowed_file_format`) đều dùng chung cơ chế
  cache **Redis**, không còn Caffeine/in-process — một cơ chế cache duy
  nhất cho mọi tham số do R3 quản lý.
- **Thứ tự kiểm tra khác SRS gốc — đã xác nhận:** FR-12 trong SRS mô tả
  Luồng xử lý chính theo thứ tự "1) SHA-256 → 2) quét mã độc → 3) kiểm tra
  định dạng". Story 3 ở đây dùng thứ tự **định dạng → SHA-256 → mã độc**
  theo đúng yêu cầu tối ưu của bạn — không vi phạm Tiêu chí chấp nhận gốc
  của FR-12 (chỉ yêu cầu "tệp nhiễm mã độc bị từ chối; tệp sạch được tính
  mã băm... lưu cùng siêu dữ liệu", không ràng buộc thứ tự); khác câu chữ
  Luồng xử lý chính là quyết định tối ưu có chủ đích, đã được xác nhận.

## Câu hỏi mở cho DEV

_Đã chốt cả 4 câu hỏi mở trước đó:_

- ~~Cơ chế "tiếp tục từ tệp chưa hoàn tất"?~~ → **Chốt: retry nguyên tệp từ
  đầu (không resume byte/chunk); "hoàn tất" = có bản ghi tài liệu trong DB
  (upload + scan + lưu metadata đã xong trọn vẹn).** Chi tiết đã cập nhật
  vào Story 4 AC-2, gồm cả cách server nhận diện tệp trùng khi tải lại.
- ~~Kiểm giới hạn số tệp/dung lượng ở đâu?~~ → **Chốt: cả client và
  server**, server là nguồn xác thực cuối cùng qua khái niệm **batch**
  (client khai báo trước, server đối chiếu ngưỡng + cộng dồn thực nhận).
  Chi tiết đã cập nhật vào Story 2 AC-3.
- ~~Cơ chế invalidate cache allowlist?~~ → **Chốt: chuyển sang Redis**
  (không dùng cache in-process/Caffeine như bản trước) — mọi instance
  backend đọc chung một giá trị từ Redis, ghi đồng bộ ngay khi R3 lưu thay
  đổi. Chi tiết đã cập nhật vào Story 3 AC-6 và Story 5 AC-3. Redis **chưa
  có** trong hạ tầng hiện tại (xem "Phụ thuộc"); đã đồng bộ luôn quyết
  định cache của `system-config.md` sang Redis (Quyết định 4 của file đó).
- ~~Thứ tự kiểm tra và điểm dừng khi fail?~~ → **Chốt theo độ phức tạp xử
  lý, dễ/rẻ làm trước: định dạng → SHA-256 → mã độc**, dừng ngay ở bước
  đầu fail. Chi tiết đã cập nhật vào Story 3 AC-1–AC-5. Thứ tự này khác câu
  chữ Luồng xử lý chính của FR-12 gốc trong SRS (SHA-256 → mã độc → định
  dạng) nhưng không vi phạm Tiêu chí chấp nhận gốc — đã xác nhận là chủ
  đích tối ưu (xem "Phụ thuộc").

Không còn câu hỏi mở nào cho DEV ở bước BA.
