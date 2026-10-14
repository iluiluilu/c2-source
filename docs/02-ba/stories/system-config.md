# User Story: system-config

> File: `docs/02-ba/stories/system-config.md` — Owner: BA
> Nguồn kiến trúc: `docs/01-sa/architecture/system-config.md`
> Status: Approved

Tham số nghiệp vụ (bảng `system_config`) do R3 quản lý qua UI, có hiệu lực
ngay, được ghi audit và khôi phục được. Danh mục MVP + phân loại nhạy cảm lấy
theo Quyết định 3 của file SA (không lặp lại số liệu ở đây).

---

## Story 1 — Xem danh mục tham số cấu hình

**As a** Quản trị hệ thống (R3)
**I want** xem toàn bộ tham số nghiệp vụ hiện hành cùng giá trị đang áp dụng
**So that** tôi nắm được cấu hình vận hành trước khi thay đổi

### Acceptance Criteria

```
Given tôi đăng nhập với vai trò R3
When tôi mở trang "Admin > Cấu hình hệ thống"
Then hệ thống hiển thị danh sách tham số MVP kèm giá trị hiện tại, mô tả,
     đơn vị và cờ "nhạy cảm" (nếu có)
```

- [ ] AC-1: Hiển thị đủ danh mục tham số MVP ở Quyết định 3 (8 key `system_config` + danh mục `allowed_file_format`).
- [ ] AC-2: Mỗi tham số hiển thị: key, mô tả, kiểu dữ liệu (`value_type`: STRING/NUMBER/BOOLEAN/JSON), giá trị hiện hành, giá trị mặc định, cờ nhạy cảm.
- [ ] AC-3: Người dùng không phải R3 không truy cập được trang này (403/không thấy menu).
- [ ] AC-4: Nếu bảng `system_config` chưa có bản ghi cho một tham số, hiển thị giá trị mặc định tĩnh (fallback) và đánh dấu là "đang dùng mặc định".

---

## Story 2 — Sửa tham số thường (có hiệu lực ngay)

**As a** R3
**I want** sửa một tham số nghiệp vụ không nhạy cảm và lưu lại
**So that** thay đổi vận hành áp dụng ngay mà không cần khởi động lại hệ thống

### Acceptance Criteria

```
Given tôi đang xem một tham số KHÔNG nhạy cảm (vd. ngưỡng cảnh báo kho,
      hạn mức lưu trữ, giới hạn upload)
When tôi nhập giá trị hợp lệ và bấm Lưu
Then hệ thống lưu giá trị mới, áp dụng ngay cho các module liên quan và
     hiển thị giá trị mới trên UI
```

- [ ] AC-1: Giá trị mới có hiệu lực ngay, không cần restart backend.
- [ ] AC-2: Giá trị không hợp lệ (không convert được theo `value_type` của tham số, hoặc ngoài khoảng cho phép) bị từ chối kèm thông báo lỗi rõ ràng; giá trị cũ giữ nguyên.
- [ ] AC-3: Ràng buộc liên tham số được kiểm tra (vd. ngưỡng cảnh báo kho phải nhỏ hơn ngưỡng chặn).
- [ ] AC-4: Sau khi lưu (ghi DB trước), giá trị mới được ghi ngay vào **cache Redis** trong cùng request; mọi instance backend và mọi request đọc lại sau đó (kể cả tức thời) đều thấy giá trị mới, nhất quán giữa các instance (xem Câu hỏi mở cho DEV).

---

## Story 3 — Sửa tham số nhạy cảm (xác nhận 2 bước)

**As a** R3
**I want** phải xác nhận thêm một bước khi đổi tham số nhạy cảm
**So that** tránh vô tình thay đổi cấu hình có thể gây mất dữ liệu / hỏng khả năng khôi phục

### Acceptance Criteria

```
Given tôi sửa một tham số NHẠY CẢM (retention.trash_retention_days hoặc
      backup.schedule_cron)
When tôi bấm Lưu
Then hệ thống yêu cầu bước xác nhận thứ hai trước khi áp dụng, nêu rõ hệ quả
     của thay đổi
```

- [ ] AC-1: Chỉ 2 tham số `retention.trash_retention_days` và `backup.schedule_cron` yêu cầu xác nhận 2 bước.
- [ ] AC-2: Nếu hủy ở bước xác nhận, giá trị KHÔNG thay đổi.
- [ ] AC-3: Sau khi xác nhận, giá trị mới được áp dụng và ghi audit như tham số thường.
- [ ] AC-4: Các tham số không nhạy cảm KHÔNG hiển thị bước xác nhận thứ hai (giữ thao tác gọn).

---

## Story 4 — Ghi nhật ký thay đổi (audit)

**As a** R3 / kiểm toán viên
**I want** mọi thay đổi tham số được ghi vào nhật ký với giá trị cũ và mới
**So that** truy vết được ai đổi gì, khi nào (FR-09)

### Acceptance Criteria

```
Given một tham số vừa được thay đổi thành công
When tôi xem nhật ký hệ thống
Then có một bản ghi audit chứa: key tham số, giá trị cũ, giá trị mới,
     người thực hiện và thời điểm
```

- [ ] AC-1: Mỗi lần lưu thành công tạo đúng một audit event trong `audit_log` (tái dùng cơ chế FR-09, không dùng bảng history riêng — Quyết định 2), với `resourceType = "system_config"` và `resourceId = <key tham số>`.
- [ ] AC-2: `old_value`/`new_value` được ghi vào cột `metadata` (jsonb) có sẵn của `audit_log` — dạng `{"key": ..., "oldValue": ..., "newValue": ...}` — không cần entity/cột mới (đã rà soát, xem Câu hỏi mở cho DEV), đủ để truy vấn lại phục vụ Story 5.
- [ ] AC-3: Thay đổi thất bại (validation lỗi, hủy xác nhận) KHÔNG tạo audit event thay đổi.

---

## Story 5 — Khôi phục giá trị trước đó (rollback)

**As a** R3
**I want** khôi phục một tham số về giá trị trước khi thay đổi
**So that** nhanh chóng sửa sai cấu hình mà không cần nhớ giá trị cũ

### Acceptance Criteria

```
Given một tham số đã từng được thay đổi và có lịch sử trong audit_log
When tôi chọn khôi phục về một giá trị trước đó
Then hệ thống đặt lại tham số về giá trị đã chọn, áp dụng ngay và ghi thêm
     một audit event cho hành động khôi phục
```

- [ ] AC-1: Có thể xem lịch sử giá trị của một tham số (từ `audit_log`).
- [ ] AC-2: Khôi phục về giá trị nhạy cảm cũng phải qua xác nhận 2 bước (áp dụng lại quy tắc Story 3).
- [ ] AC-3: Hành động khôi phục tự sinh audit event mới (không xóa/ghi đè lịch sử cũ — `audit_log` append-only).

---

## Story 6 — Fallback về mặc định khi bảng config lỗi

**As a** hệ thống / R3
**I want** hệ thống dùng giá trị mặc định tĩnh khi không đọc được bảng `system_config`
**So that** một sự cố ở bảng cấu hình không làm sập toàn hệ thống (NFR Availability)

### Acceptance Criteria

```
Given bảng system_config không đọc được hoặc thiếu bản ghi cho một tham số
When một module cần đọc giá trị tham số đó
Then hệ thống dùng giá trị mặc định tĩnh đã định nghĩa và tiếp tục hoạt động
     bình thường (không văng lỗi chặn nghiệp vụ)
```

- [ ] AC-1: Mọi tham số MVP có giá trị mặc định tĩnh (theo bảng Quyết định 3) dùng làm fallback.
- [ ] AC-2: Khi fallback được kích hoạt, hệ thống không crash và ghi cảnh báo vào log kỹ thuật.
- [ ] AC-3: Tham số hạ tầng (port, DB connection...) vẫn nằm ở YAML tĩnh, KHÔNG chịu ảnh hưởng của Story này (Quyết định 1 — hybrid).

---

## Ngoài phạm vi (Out of scope)

- Cấu hình timeout phiên/token và ngưỡng khóa đăng nhập sai — đã chốt cấu hình
  trực tiếp trong Keycloak (`auth-session.md`), không thuộc `system_config`.
- Cấu hình hạ tầng cụ thể (mạng, phần cứng, công nghệ lưu trữ) — thuộc
  `kien_truc_he_thong.adoc`.
- Chi tiết chức năng sao lưu (FR-34) ngoài chính tham số `backup.schedule_cron`
  — ngoài phạm vi nhóm SA hiện tại.
- Thiết kế chi tiết seed danh mục `allowed_file_format` — đã chốt ở
  `data-ingestion.md`, story này chỉ quản lý việc hiển thị/sửa.

## Phụ thuộc / Ghi chú kiến trúc liên quan

- Quyết định 1 (hybrid YAML tĩnh + bảng `system_config`): chỉ tham số nghiệp
  vụ mới sửa qua UI.
- Quyết định 2 (tái dùng `audit_log`, không tạo bảng history riêng): nền tảng
  cho Story 4 và Story 5.
- Quyết định 3 (danh mục MVP, phân loại nhạy cảm, giá trị mặc định): nguồn cho
  toàn bộ AC về danh mục/validation/fallback.
- Phụ thuộc chéo: hạn mức FR-14 (`personal-storage-quota.md`), ngưỡng kho FR-13
  (`warehouse-structure.md`), giới hạn upload/định dạng FR-11
  (`data-ingestion.md`) — các module này phải đọc giá trị từ `system_config`.
- Giá trị mặc định do SA đề xuất (retention 365 ngày, cảnh báo kho 85%, sao lưu
  02:00) chưa được khách hàng xác nhận chính thức — nhưng sửa được qua UI nên
  không chặn go-live.
- `AuditLog` (`source_v2/backend/.../audit/AuditLog.java`) đã có cột
  `metadata` (jsonb, `Map<String, Object>`) đủ linh hoạt để lưu
  `old_value`/`new_value` của `system_config` — không cần entity/migration
  mới cho nhu cầu của story này.
- Cơ chế cache (Story 2 AC-4) đã đổi sang **Redis** (đồng bộ với
  `data-ingestion.md`/allowlist) — không còn giới hạn "chỉ đúng khi 1
  instance" như phương án in-process trước đó, vì Redis là shared cache
  giữa mọi instance backend.
- ⚠️ **Hạ tầng mới cần bổ sung:** đã kiểm tra `docker-compose.yml`/
  `docker-compose.dev.yml` (gốc source_v2) và `pom.xml` (`backend/`) —
  **chưa có Redis** ở cả hai nơi. Cần: (1) thêm service `redis` vào
  docker-compose, (2) thêm dependency `spring-boot-starter-data-redis` vào
  `pom.xml`. Đây là hạ tầng dùng chung với `data-ingestion.md` (cache
  allowlist) — chỉ cần dựng một lần.
- `ai_queue.concurrency = 4`, `ai_queue.batch_size = 8` là giá trị khởi điểm
  cho GPU 96GB (ước tính theo VRAM), không phải số đo thực nghiệm — chỉnh
  lại qua UI khi có tải thực tế, không cần đổi code hay xin duyệt lại spec.

## Câu hỏi mở cho DEV

_Đã chốt cả 4 câu hỏi mở trước đó:_

- ~~Cơ chế invalidate cache: TTL ngắn hay event-based refresh?~~ →
  **Chốt lại: dùng Redis (shared cache) thay vì cache in-process.** Khi R3
  lưu thành công một tham số: ghi DB trước (nguồn sự thật), sau đó ghi
  ngay giá trị mới vào Redis trong cùng request — mọi instance backend đọc
  chung một giá trị từ Redis nên nhất quán tức thời **kể cả khi scale
  nhiều instance** (khác với phương án in-process/Caffeine ban đầu, vốn
  chỉ đúng cho 1 instance). Vẫn đặt TTL trên key Redis làm lớp dự phòng
  nếu lỡ một lần ghi. Quyết định này đồng bộ với cache `allowed_file_format`
  ở `data-ingestion.md` — dùng chung một cơ chế cho mọi tham số do R3
  quản lý, tránh 2 kiểu cache khác nhau trong cùng hệ thống.
- ~~`ai_queue.concurrency` / `ai_queue.batch_size` — giá trị mặc định?~~ →
  **Chốt cho GPU 96GB: `concurrency = 4`, `batch_size = 8`.** Giả định mỗi
  worker OCR/STT chiếm khoảng 8–12GB VRAM lúc cao điểm (model weights +
  activation + buffer batch); 4 worker chạy song song ≈ 32–48GB, chừa
  ~48–64GB cho mô hình tra cứu ngữ nghĩa/embedding (FR-24) chạy đồng thời
  trên cùng GPU và overhead driver/OS. Đây là giá trị khởi điểm an toàn,
  không phải số đo thực nghiệm — bạn tự tinh chỉnh lại qua UI
  (`system_config`) khi biết chính xác model OCR/STT sử dụng và tải thực
  tế, không cần đổi code.
- ~~`AuditLog` entity đã đủ trường lưu `old_value`/`new_value` chưa?~~ →
  **Đã rà soát code (`source_v2/backend/.../audit/AuditLog.java`): đủ,
  không cần sửa entity.** Bảng đã có sẵn cột `metadata` kiểu `jsonb`
  (`Map<String, Object>`, không cố định schema) — ghi trực tiếp
  `{"key": "<tên tham số>", "oldValue": ..., "newValue": ...}` vào đó khi
  tạo audit event cho Story 4/5, dùng `resourceType = "system_config"` và
  `resourceId = key` để truy vấn lại lịch sử theo từng tham số. Không cần
  migration hay entity mới.
- ~~Định dạng lưu giá trị trong `system_config`?~~ → **Chốt theo đúng yêu
  cầu của bạn:** cột `key` kiểu string (khóa chính/duy nhất); cột `value`
  lưu dạng **text** (chứa chuỗi số, boolean, chuỗi thường, hoặc chuỗi JSON
  tùy tham số); thêm cột `value_type` (enum: `STRING` / `NUMBER` /
  `BOOLEAN` / `JSON`) để backend biết cách convert. Dùng một class wrapper
  dùng chung (vd. `SystemConfigValue`) với các phương thức
  `asString()/asLong()/asBoolean()/asJson(Class<T>)`, bên trong dùng
  Jackson `ObjectMapper` cho nhánh `JSON`, parse trực tiếp cho các kiểu
  còn lại — giúp mỗi module đọc tham số ra đúng kiểu Java mong muốn mà
  không cần biết chi tiết lưu trữ.

Không còn câu hỏi mở nào cho DEV ở bước BA.
