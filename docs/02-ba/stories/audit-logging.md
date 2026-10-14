# User Story: audit-logging

> File: `docs/02-ba/stories/audit-logging.md` — Owner: BA
> Nguồn kiến trúc: `docs/01-sa/architecture/audit-logging.md`
> Status: Approved

Hoàn thiện FR-09 (ghi nhật ký hoạt động) và bổ sung FR-10 (tra cứu/lọc nhật
ký) trên nền hạ tầng `audit/` đã có một phần trong repo (`AuditService`,
`AuditInterceptor`, `AuditController`, bảng `audit_log` hash-chained). 4 story
dưới đây tương ứng 1-1 với 4 Quyết định (ADR) trong file kiến trúc: ghi nhận
sự kiện (ADR1/3/4), R3 tra cứu toàn quyền (ADR2), R2 tra cứu theo phạm vi
nhóm (ADR2 + NFR-07), và vận hành dài hạn — xác minh + retention (ADR5).

---

## Story 1 — Ghi nhận sự kiện nghiệp vụ đầy đủ, không trùng, không mất, không lộ PII (FR-09)

**As a** hệ thống
**I want** mỗi thao tác nghiệp vụ làm thay đổi dữ liệu được ghi đúng một lần
vào `audit_log`, kèm đủ ngữ cảnh (ai / hành động gì / trên tài nguyên nào) và
không chứa dữ liệu nhạy cảm
**So that** người quản trị truy vết được "ai đã sửa gì" một cách tin cậy, mà
không phải lo dữ liệu bí mật bị lưu vĩnh viễn vào một bảng không xoá được

### Acceptance Criteria

```
Given tôi (R1/R2/R3) thực hiện một thao tác tạo/sửa/xoá qua API
When request hoàn tất (thành công hoặc thất bại nghiệp vụ)
Then đúng một bản ghi audit_log được tạo, có action theo dạng
     "<domain>.<verb>", resource_type/resource_id xác định đúng đối tượng bị
     tác động, và giao dịch thất bại nếu bản ghi audit không ghi được
```

- [ ]  AC-1: Với mọi endpoint mutating (POST/PUT/PATCH/DELETE) đã có audit call
  thủ công ở tầng service, `AuditInterceptor` **không** ghi thêm bản ghi
  trùng cho cùng request (thu hẹp interceptor thành fallback).
- [ ]  AC-2: `AuditInterceptor` (fallback) chỉ còn ghi khi request bị từ chối
  (401/403) hoặc lỗi 5xx trên route **chưa** được instrument thủ công —
  giữ lại khả năng bắt truy cập bị từ chối phục vụ `rbac-group-scope.md`.
- [ ]  AC-3: Ghi audit của sự kiện nghiệp vụ nằm **trong cùng transaction** với
  thao tác nghiệp vụ — nếu ghi audit lỗi, toàn bộ giao dịch rollback
  (không có trạng thái "đã đổi dữ liệu nhưng không có log").
- [ ]  AC-4: Ghi audit không giữ khoá (lock) chặn các giao dịch nghiệp vụ khác
  chạy đồng thời (loại bỏ `pg_advisory_xact_lock` toàn cục khỏi đường ghi
  chính) — hash chain vẫn được tính đầy đủ nhưng có thể lệch pha vài giây
  qua một job nền riêng.
- [ ]  AC-5: `action` của mọi sự kiện tuân theo taxonomy `<domain>.<verb>` (đã
  là quy ước thực tế ở phần lớn service hiện có, vd `document.create`,
  `account.password_reset`) — không còn hành động ghi dạng tự do
  `http.POST` từ interceptor xuất hiện trong nhật ký của các route đã
  instrument.
- [ ]  AC-6: Trường `metadata` chỉ chứa các field nằm trong allowlist khai báo
  theo từng `action`; field không được phép bị loại bỏ trước khi ghi
  (không raise lỗi làm fail request), và việc loại bỏ được ghi log ứng
  dụng (không log giá trị bị loại). Không bao giờ lưu mật khẩu, token,
  nội dung file, hay toàn bộ request/response body.
- [ ]  AC-7: Nguồn sự kiện ngoài (Keycloak login/lockout qua RabbitMQ) tiếp tục
  hoạt động như đã chốt ở `auth-session.md` — không thay đổi ở story này.
- [ ]  AC-8: Trên route đã instrument thủ công, một thao tác thất bại vì lý do
  nghiệp vụ (4xx khác 401/403, vd validate 400, conflict 409) vẫn sinh
  đúng một bản ghi audit với `outcome = failure` — không bỏ qua các lượt
  thao tác bị từ chối do nghiệp vụ (401/403 tiếp tục do
  `AuditInterceptor` fallback bắt theo AC-2).
- [ ]  AC-9: Khi gọi audit thủ công ở tầng service (không qua
  `AuditInterceptor`), các trường `outcome`, `ip`, `method`, `path`,
  `status_code` vẫn được điền đầy đủ (không để trống do tầng service
  không có sẵn `HttpServletRequest`) — phục vụ NFR "Tính giải thích"
  (ai / lúc nào / hành động gì / trên tài nguyên nào / kết quả ra sao /
  từ IP nào) trong file kiến trúc.

### Ngoài phạm vi (Out of scope story này)

- Đổi tên/migrate lại các `action` đã tồn tại đúng quy ước — chỉ chuẩn hoá
  phần còn thiếu (interceptor + rà soát 14 service gọi trùng).

---

## Story 2 — R3 tra cứu và lọc toàn bộ nhật ký hệ thống (FR-10)

**As a** Quản trị hệ thống (R3)
**I want** tìm kiếm/lọc nhật ký hoạt động theo thời gian, người thực hiện,
loại hành động, loại/định danh tài nguyên, có phân trang
**So that** tôi điều tra được sự cố hoặc truy vết một thao tác cụ thể mà
không phải đọc tuần tự toàn bộ nhật ký

### Acceptance Criteria

```
Given tôi là R3 đang ở trang quản trị nhật ký
When tôi đặt bộ lọc thời gian/actor/action/resource và bấm tìm kiếm
Then hệ thống trả về danh sách bản ghi khớp điều kiện, có phân trang, trong
     vòng 2 giây (12 tháng dữ liệu)
```

- [ ]  AC-1: Lọc kết hợp được theo: khoảng thời gian, actor (người thực hiện),
  action (hành động), resource_type + resource_id.
- [ ]  AC-2: Kết quả có phân trang; không có chế độ tải toàn bộ nhật ký một
  lần.
- [ ]  AC-3: Trang/API tra cứu nhật ký dùng **chung** cho R2 và R3 (không tách
  endpoint riêng) — backend tự áp scope filter khi actor là R2 (Story 3);
  R3 thấy toàn bộ không giới hạn; các vai trò khác (R1) bị chặn (403).
- [ ]  AC-4: Nội dung tài liệu/dữ liệu nghiệp vụ **không** hiển thị kèm trong
  màn hình đọc log (NFR-07 — R3 không đứng trên route dữ liệu nghiệp
  vụ), chỉ hiển thị các trường đã ghi trong `audit_log`.
- [ ]  AC-5: Mọi lượt truy cập vào chính nhật ký (kể cả xem, không chỉ tải
  xuống) cũng được ghi lại thành một sự kiện audit riêng.

### Ngoài phạm vi

- Xuất báo cáo CSV/PDF — chưa cần ở giai đoạn này (câu hỏi mở đã chốt trong
  file kiến trúc, mục 7).
- Tìm kiếm full-text nâng cao trên `metadata` — giới hạn ở lọc theo trường có
  cấu trúc (mục 5 file kiến trúc).

---

## Story 3 — R2 tra cứu nhật ký trong phạm vi nhóm mình quản lý (FR-10, NFR-07)

**As a** Lãnh đạo (R2)
**I want** xem nhật ký hoạt động chỉ giới hạn trong phạm vi nhóm/dữ liệu mà
tôi được thấy
**So that** tôi giám sát được hoạt động trong phạm vi phụ trách mà không
nhìn thấy dữ liệu ngoài phạm vi hoặc thao tác quản trị hệ thống

### Acceptance Criteria

```
Given tôi là R2 đang ở trang nhật ký
When tôi tìm kiếm/lọc như R3
Then kết quả chỉ gồm các sự kiện liên quan tới tài nguyên tôi được phép thấy
     (cùng phạm vi với dữ liệu nghiệp vụ tôi đang xem ở nơi khác trong hệ
     thống), không có sự kiện quản trị hệ thống thuần tuý
```

- [ ]  AC-1: R2 dùng cùng bộ lọc (thời gian/actor/action/resource) như Story 2,
  nhưng tập kết quả luôn bị giới hạn bởi phạm vi nhóm của R2 — không có
  cách nào (kể cả qua tham số lọc) để R2 thấy sự kiện ngoài phạm vi.
- [ ]  AC-2: Phạm vi của R2 xác định theo **cùng quy tắc** đang dùng để lọc dữ
  liệu nghiệp vụ khác cho R2 (nhóm mà R2 là thành viên/sở hữu tài liệu),
  không định nghĩa lại một khái niệm "phạm vi" riêng cho module audit.
- [ ]  AC-3: Sự kiện không gắn với một tài nguyên có phạm vi nhóm xác định
  được (vd cấu hình hệ thống, quản lý tài khoản) — R2 **không** thấy.
- [ ]  AC-4: Nếu việc tính phạm vi lỗi (vd không lấy được danh sách nhóm), hệ
  thống trả về rỗng/lỗi chứ không rơi về "không lọc gì" (fail-closed,
  nhất quán với `rbac-group-scope.md`).
- [ ]  AC-5: Không có vai trò "kiểm toán viên" riêng ở giai đoạn này — chỉ
  R2/R3 (đã chốt ở file kiến trúc, mục 7).

### Ngoài phạm vi

- Cho R2 xem log của R2 khác / của toàn bộ lãnh đạo — chỉ phạm vi của chính
  R2 đang đăng nhập.
- **v1 (đã chốt với người dùng 2026-09-01):** R2 chỉ thấy sự kiện
  `resource_type = document` trong phạm vi nhóm — code hiện tại
  (`BusinessDataScope`) chỉ có scope tương đương cho `document`, chưa có cho
  `folder`/`group`. Mở rộng R2 thấy thêm `folder`/`group` (đã nêu ở kiến trúc
  mục 7 là cùng phạm vi document/folder/group) là việc kế tiếp, cần một
  Specification tương đương `BusinessDataScope.forDocuments` cho các loại đó
  trước khi làm — không thuộc phạm vi task breakdown hiện tại của
  `docs/03-dev/specs/audit-logging.md`.

---

## Story 4 — R3 xác minh toàn vẹn và vận hành vòng đời lưu trữ nhật ký (verify + retention)

**As a** Quản trị hệ thống (R3)
**I want** xác minh chuỗi hash của nhật ký theo khoảng thời gian (không phải
toàn bộ bảng), và hệ thống tự lưu trữ/loại bỏ dữ liệu quá hạn có kiểm chứng
**So that** tôi tin tưởng được nhật ký chưa bị chỉnh sửa, kể cả khi dữ liệu
đã lớn tới hàng chục triệu dòng, mà không phải giữ toàn bộ lịch sử mãi mãi

### Acceptance Criteria

```
Given nhật ký đã tích luỹ nhiều năm dữ liệu
When R3 chạy xác minh cho một khoảng thời gian cụ thể
Then hệ thống trả kết quả hợp lệ/không hợp lệ trong ≤ 30 giây cho 1 tháng dữ
     liệu, không cần nạp toàn bộ bảng vào bộ nhớ
```

- [ ]  AC-1: Xác minh chuỗi hash chạy theo khoảng (thời gian hoặc id), dựa trên
  checkpoint định kỳ, thay vì luôn quét lại từ dòng đầu tiên.
- [ ]  AC-2: Dữ liệu quá 24 tháng được lưu trữ (export kèm checkpoint hash)
  trước khi loại khỏi bảng đang phục vụ truy vấn (partition theo tháng).
- [ ]  AC-3: Việc lưu trữ/loại bỏ dữ liệu quá hạn **không** dùng DELETE từng
  dòng (bảng là append-only ở mức trigger) — vẫn giữ được tính bất biến
  trong suốt vòng đời partition đang hoạt động.
- [ ]  AC-4: Thao tác lưu trữ/loại bỏ partition quá hạn tự sinh một sự kiện
  audit (ai/khi nào/khoảng dữ liệu nào đã được archive).
- [ ]  AC-5: Sau khi dữ liệu quá hạn đã được archive, R3 vẫn xác minh được
  tính toàn vẹn của phần đã archive dựa trên checkpoint + file lưu trữ
  (không cần dữ liệu còn nằm trong bảng phục vụ truy vấn).

### Ngoài phạm vi

- Ký số ra hệ thống ngoài (TSA/blockchain) — không có yêu cầu pháp lý bắt
  buộc ở giai đoạn này (đã chốt, mục 7).
- SIEM/alerting realtime, phân tích hành vi bất thường, báo cáo tuân thủ định
  dạng cố định — ngoài phạm vi toàn bộ tài liệu kiến trúc.

---

## Phụ thuộc / Ghi chú kiến trúc liên quan

- Quyết định 1 (SA): ghi đồng bộ trong transaction nghiệp vụ, tách hash-chain
  (sealer job) — Story 1 AC-3/AC-4.
- Quyết định 2 (SA): PostgreSQL partition theo tháng là system of record duy
  nhất (không đẩy sang OpenSearch ở giai đoạn này) — Story 2/3.
- Quyết định 3 (SA): instrumentation ở tầng service là chính, interceptor chỉ
  còn là fallback — Story 1 AC-1/AC-2.
- Quyết định 4 (SA): taxonomy `<domain>.<verb>` + allowlist `metadata` —
  Story 1 AC-5/AC-6. Đã rà soát: phần lớn service hiện có (`document.*`,
  `account.*`, `group.*`, `personal.*`, `folder.*`, `note.*`, ...) đã theo
  đúng quy ước này; khoảng trống thực tế nằm ở `AuditInterceptor` (ghi
  `http.POST|PUT|PATCH|DELETE`) và ở việc `ALLOWED_METADATA_KEYS` trong
  `AuditService` hiện chỉ khai báo cho `note.create`/`note.update`.
- Quyết định 5 (SA): checkpoint + verify theo khoảng + DROP PARTITION sau
  export — Story 4.
- Đã rà soát code hiện có: `AuditService` (`record`/`recordAsync`/
  `verifyChain`/`list`), `AuditController` (`GET /audit`, `GET /audit/verify`,
  quyền `PERM_audit.read`), `AuditInterceptor`, migration
  `V1__init_audit_log.sql` (chưa partition). 14 service đang tự gọi
  `AuditService` (đã liệt kê trong file kiến trúc) — cần rà lại để loại ghi
  trùng với interceptor (Story 1 AC-1).
- Phạm vi nhóm cho R2 (Story 3) nên tái dùng cơ chế đã có ở
  `groups/BusinessDataScope` + `GroupMemberRepository` (dùng cho
  `DocumentScopeGuard`, xem `rbac-group-scope.md`) thay vì định nghĩa lại —
  xem câu hỏi mở cho DEV.

## Câu hỏi mở cho DEV

- [X]  Story 3 (R2 scope) — đã chốt với người dùng (2026-09-01): `audit_log`
  không có cột group/owner trực tiếp, R2 chỉ thấy audit event của đúng 3
  `resource_type` có khái niệm phạm vi nhóm rõ ràng — `document`,
  `folder`, `group` — join qua cùng cơ chế `BusinessDataScope` /
  `GroupMemberRepository` đã dùng cho `DocumentScopeGuard`
  (`rbac-group-scope.md`). Mọi `resource_type` khác (`account`,
  `system_config`, `personal`, `note`, ...) ẩn hoàn toàn với R2 theo AC-3
  — không mở rộng thêm ở giai đoạn này. DEV cần thiết kế cụ thể cách
  join này ở tech spec.
- [ ]  Story 1 AC-1/AC-2: rà lại toàn bộ 14 service đang gọi `AuditService` để
  xác nhận route nào đã có audit thủ công đầy đủ (interceptor có thể tắt
  hẳn ghi thành công cho route đó) và route nào còn thiếu (vẫn cần
  interceptor ghi tối thiểu) — việc rà soát chi tiết từng service để DEV
  thực hiện ở bước viết tech spec/implement, không lặp lại ở BA.
