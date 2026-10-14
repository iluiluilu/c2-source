# Tech Spec: audit-logging

> File: `docs/03-dev/specs/audit-logging.md` — Owner: DEV
> Nguồn story: `docs/02-ba/stories/audit-logging.md`
> Status:　Approved`

## 1. Tóm tắt kỹ thuật

Mở rộng module `audit/` hiện có (không viết lại): bỏ advisory lock toàn cục
khỏi đường ghi chính, tách việc tính hash-chain ra một job nền ("sealer"),
thu hẹp `AuditInterceptor` thành fallback, thêm lọc/phân trang cho `GET /audit` (FR-10, có giới hạn phạm vi cho R2), chuyển `audit_log` sang bảng
partition theo tháng, và thêm checkpoint + retention/archive. Giữ nguyên
`AuditService.AuditEntry`/`record()`/`recordAsync()` làm API nội bộ cho 14
service đang gọi — không đổi chữ ký, chỉ đổi hành vi bên trong.

## 2. Data model

### 2.1 Cột mới trên `audit_log` (Flyway `V14__audit_log_seal_and_partition.sql`)

```sql
ALTER TABLE audit_log ADD COLUMN sealed_at TIMESTAMPTZ;
ALTER TABLE audit_log ALTER COLUMN prev_hash DROP NOT NULL; -- đã nullable
ALTER TABLE audit_log ALTER COLUMN hash DROP NOT NULL; -- trước là NOT NULL, giờ NULL khi chưa seal
```

`record()` giờ INSERT với `hash = NULL`, `prev_hash = NULL`, `sealed_at = NULL`. Một dòng "chưa seal" chưa được chain, nhưng vẫn bất biến ở mức
UPDATE/DELETE tuỳ ý (trigger mới ở 2.3 chỉ mở đúng một khe hở cho chính thao
tác seal).

### 2.2 Chuyển sang bảng partition theo tháng

Postgres yêu cầu cột partition key (`created_at`) nằm trong PRIMARY KEY của
bảng partitioned. Vì `audit_log` đã có dữ liệu, thực hiện theo kiểu
rename-and-swap trong cùng một migration (không downtime dữ liệu, khoá bảng
ngắn khi swap):

1. `ALTER TABLE audit_log RENAME TO audit_log_legacy;`
2. Tạo `audit_log` mới: `CREATE TABLE audit_log (... giống cột cũ + sealed_at ...) PARTITION BY RANGE (created_at)`, `PRIMARY KEY (id, created_at)`.
3. Tạo partition cho tháng hiện tại + 2 tháng kế tiếp (`audit_log_2026_09`,
   ...); một job tạo partition tháng kế tiếp tự động (xem §4.5).
4. `INSERT INTO audit_log SELECT * FROM audit_log_legacy;` (giữ nguyên `id`
   — không dùng lại sequence, `ALTER SEQUENCE audit_log_id_seq RESTART WITH <max(id)+1>` sau khi copy).
5. Giữ `audit_log_legacy` lại (không DROP) tới khi đã chạy `verifyChain`
   toàn bộ trên bảng mới và xác nhận khớp — dọn ở một migration sau, thủ
   công, không tự động hoá trong Flyway.
6. Index trên bảng mới: `(created_at)`, `(actor, created_at)`,
   `(resource_type, resource_id, created_at)`, GIN `(metadata)`.

Rủi ro & cách kiểm chứng: xem §7 và test IT ở §5 (Testcontainers, cùng cách
`SystemConfigControllerIT` đã dùng).

### 2.3 Trigger: mở một khe hở duy nhất cho thao tác "seal"

```sql
CREATE OR REPLACE FUNCTION audit_log_immutable() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'UPDATE'
       AND OLD.hash IS NULL AND OLD.sealed_at IS NULL
       AND NEW.hash IS NOT NULL AND NEW.sealed_at IS NOT NULL
       AND NEW.created_at IS NOT DISTINCT FROM OLD.created_at
       AND NEW.actor IS NOT DISTINCT FROM OLD.actor
       AND NEW.actor_sub IS NOT DISTINCT FROM OLD.actor_sub
       AND NEW.action IS NOT DISTINCT FROM OLD.action
       AND NEW.resource_type IS NOT DISTINCT FROM OLD.resource_type
       AND NEW.resource_id IS NOT DISTINCT FROM OLD.resource_id
       AND NEW.outcome IS NOT DISTINCT FROM OLD.outcome
       AND NEW.method IS NOT DISTINCT FROM OLD.method
       AND NEW.path IS NOT DISTINCT FROM OLD.path
       AND NEW.status_code IS NOT DISTINCT FROM OLD.status_code
       AND NEW.ip IS NOT DISTINCT FROM OLD.ip
       AND NEW.metadata IS NOT DISTINCT FROM OLD.metadata
    THEN
        RETURN NEW; -- seal hợp lệ: chỉ set prev_hash/hash/sealed_at một lần
    END IF;
    RAISE EXCEPTION 'audit_log is append-only; % is not permitted', TG_OP;
END;
$$ LANGUAGE plpgsql;
```

`DELETE` vẫn luôn bị chặn (archive dùng `DROP PARTITION`, không DELETE).
Sealer job (§4.2) phải dùng UPDATE SQL trực tiếp (native query / JDBC), KHÔNG
qua entity Hibernate quản lý — tránh lặp lại bug round-trip JSON đã ghi trong
javadoc `normalizeMetadata` (dirty-check tự ý phát UPDATE lần hai sẽ bị
trigger chặn vì không khớp điều kiện "seal lần đầu").

### 2.4 Bảng mới `audit_checkpoint`

```sql
CREATE TABLE audit_checkpoint (
    id          BIGSERIAL PRIMARY KEY,
    up_to_id    BIGINT NOT NULL,       -- id lớn nhất đã seal tại thời điểm checkpoint
    root_hash   VARCHAR(64) NOT NULL,  -- = hash của dòng up_to_id
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IDX_audit_checkpoint_up_to_id ON audit_checkpoint (up_to_id DESC);
```

Append-only theo quy ước (không trigger riêng — bảng nhỏ, chỉ job hệ thống
ghi, không expose qua API ghi).

## 3. API contract


| Method | Endpoint        | Request                                                                                                                                                              | Response                                                                                                                                                                                                     |
| ------ | --------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| GET    | `/audit`        | Query:`from`, `to` (ISO-8601, optional), `actor`, `action`, `resourceType`, `resourceId` (optional, kết hợp AND), `page` (default 0), `size` (default 50, max 200) | `Page<AuditLog>` (`content`, `totalElements`, `page`, `size`) — quyền `PERM_audit.read` (R3, không giới hạn) hoặc `PERM_audit.read_scoped` (R2, tự động áp thêm điều kiện phạm vi, xem §4.4) |
| GET    | `/audit/verify` | Query:`from`, `to` (**bắt buộc** — không còn verify toàn bộ không tham số, tránh lặp lại sự cố OOM)                                                    | `ChainVerification { ok, count, brokenAtId, reason }` — chỉ `PERM_audit.read` (R3)                                                                                                                         |

Cả hai endpoint tự ghi một sự kiện `audit.view` / `audit.verify`
(`resourceType="audit_log"`) sau khi phục vụ request (Story 2 AC-5), qua
`recordAsync` — không nằm trong transaction đọc, không ảnh hưởng độ trễ trả
kết quả.

`PERM_audit.read_scoped` là quyền mới — thêm vào seed/migration RBAC hiện có
(cùng chỗ định nghĩa `PERM_audit.read`), gán cho vai trò R2; `PERM_audit.read`
giữ nguyên chỉ gán cho R3.

## 4. Luồng xử lý chính

### 4.1 Ghi đồng bộ, không lock (Story 1 AC-3/AC-4)

- `AuditService.record()`: bỏ khối `pg_advisory_xact_lock`; bỏ việc đọc
  `findTopByOrderByIdDesc()` và tính `hash`/`prevHash`. Chỉ còn: chuẩn hoá
  metadata (allowlist + JSON round-trip, giữ nguyên) → INSERT với
  `hash=NULL, prev_hash=NULL, sealed_at=NULL`. Vẫn `@Transactional`, vẫn nằm
  trong transaction nghiệp vụ của caller (lỗi ghi audit vẫn fail giao dịch).
- `recordAsync()` giữ nguyên hành vi (log lỗi, không throw), chỉ khác ở việc
  `record()` bên trong giờ không lock/không tính hash.

### 4.2 Sealer job (tách hash-chain khỏi request path)

- `@Scheduled(fixedDelay = 5000)` trong `AuditSealerJob` (class mới,
  package `audit/`):
  1. `pg_try_advisory_lock(AUDIT_SEAL_LOCK_KEY)` — nếu không lấy được (một
     instance khác đang chạy), bỏ qua tick này. Đây là **lock duy nhất còn
     lại**, chỉ giữ trong batch job nền, không còn nằm trong hot path của
     request nào.
  2. Đọc dòng cuối đã seal: `SELECT hash FROM audit_log WHERE sealed_at IS NOT NULL ORDER BY id DESC LIMIT 1` (NULL nếu chưa có dòng nào — dòng
     genesis).
  3. `SELECT * FROM audit_log WHERE hash IS NULL ORDER BY id ASC LIMIT 500`
     (batch, tránh nạp lớn). Với từng dòng theo đúng thứ tự `id` đọc được
     (không giả định liên tục — id có thể có khoảng trống do rollback):
     tính `hash` bằng đúng `computeHash()` hiện có, `UPDATE audit_log SET prev_hash=?, hash=?, sealed_at=now() WHERE id=? AND hash IS NULL`
     (native SQL, điều kiện `hash IS NULL` để idempotent nếu job bị gọi
     chồng).
  4. `pg_advisory_unlock`.
- Cửa sổ "chưa seal" (vài giây) vẫn được bảo vệ bởi trigger append-only —
  không ai sửa/xoá được dù chưa có hash (đúng trade-off đã chốt ở SA ADR1).

### 4.3 Interceptor thu hẹp thành fallback (Story 1 AC-1/AC-2)

- Thêm `AuditService.markRecordedOnCurrentRequest()`: nếu
  `RequestContextHolder.getRequestAttributes() != null`, set attribute
  `AUDIT_RECORDED = true` (scope REQUEST). Gọi từ bên trong `record()`
  (đồng bộ) — mọi audit call thủ công trong cùng request tự động đánh dấu.
- `AuditInterceptor.afterCompletion()`:
  1. Nếu method không mutating → bỏ qua (giữ nguyên).
  2. Nếu request attribute `AUDIT_RECORDED` đã set → bỏ qua (đã có audit
     thủ công cho request này, tránh ghi trùng).
  3. Ngược lại, chỉ ghi khi `response.getStatus() == 401 || 403 || >= 500`
     (fallback cho route chưa instrument hoặc bị chặn trước khi vào
     service) — không còn ghi cho mọi request thành công như hiện tại.

### 4.4 Phạm vi đọc cho R2 (Story 3)

- `AuditScopeFilter` (class mới, package `groups/`, cạnh
  `BusinessDataScope`): với R2, chỉ trả về sự kiện có `resource_type = 'document'` AND `resource_id` nằm trong tập tài liệu R2 nhìn thấy được —
  tái dùng đúng subquery mô tả trong `BusinessDataScope.forDocuments`
  (uploader = sub OR chia sẻ qua nhóm R2 là thành viên), viết thành
  `Specification<AuditLog>` với subquery tương đương (không load danh sách
  document id vào Java — bounded nhưng có thể lớn hơn số nhóm).
- **Giới hạn đã biết ở v1 (đã xác nhận với người dùng 2026-09-01, xem
  `docs/02-ba/stories/audit-logging.md` Story 3 "Ngoài phạm vi"):**
  `folder`/`group`/`note`/`account`/`system_config`... chưa có cơ chế phạm vi
  nhóm tương đương `document` trong code hiện tại (đã kiểm tra, không có
  `Specification` nào cho `FolderNode`/group ngoài
  `BusinessDataScope.forDocuments`) → R2 **không** thấy các `resource_type`
  này ở v1 (khớp Story 3 AC-3 — an toàn hơn là hiển thị sai phạm vi). Mở rộng
  sang folder/group cần thêm `Specification` tương đương cho các loại đó
  trước — là việc kế tiếp, không thuộc phạm vi task breakdown dưới đây.
- Áp dụng: `AuditController.list()` kiểm tra quyền — có `PERM_audit.read`
  (R3) → không filter; chỉ có `PERM_audit.read_scoped` (R2) → áp
  `AuditScopeFilter`. Lỗi khi resolve phạm vi (giống
  `ScopeResolutionException`) → trả lỗi, không fallback về không lọc
  (fail-closed, Story 3 AC-4).

### 4.5 Checkpoint, verify theo khoảng, retention/archive (Story 4)

- **Checkpoint job**: `@Scheduled(cron = "0 0 2 * * *")` (hàng ngày 02:00),
  cùng advisory-lock pattern như §4.2. Lấy dòng đã seal mới nhất
  (`sealed_at IS NOT NULL ORDER BY id DESC LIMIT 1`), ghi một dòng
  `audit_checkpoint(up_to_id, root_hash)` nếu `up_to_id` mới hơn checkpoint
  gần nhất.
- **`verifyChain(from, to)`** (thay `verifyChain()` không tham số):
  1. Tìm checkpoint gần nhất có `up_to_id < from` (nếu có) → lấy
     `root_hash` làm `expectedPrev` khởi điểm; nếu không có checkpoint nào
     trước `from`, bắt đầu từ `expectedPrev = null` và `from` thực tế là
     dòng đầu tiên của bảng.
  2. Stream (không load hết vào List) các dòng `id` trong khoảng
     `[max(from, checkpoint.up_to_id+1), to]`, theo batch (vd `Slice`/
     `Stream` qua `@QueryHints` fetch size), kiểm tra từng dòng như logic
     hiện có của `verifyChain()`.
  3. Trả `ChainVerification` như cũ.
- **Partition tạo trước**: `@Scheduled(cron = "0 0 3 1 * *")` (đầu mỗi
  tháng) tạo partition cho tháng kế tiếp nếu chưa có (`CREATE TABLE audit_log_YYYY_MM PARTITION OF audit_log FOR VALUES FROM (...) TO (...)`) — native SQL qua `JdbcTemplate`, không qua Flyway (dữ liệu vận
  hành, không phải schema versioning).
- **Retention/archive job**: `@Scheduled(cron = "0 30 3 1 * *")` hàng
  tháng, cho mỗi partition cũ hơn 24 tháng:
  1. Đảm bảo đã có checkpoint với `up_to_id >= id lớn nhất của partition đó`
     (chạy checkpoint job thủ công nếu chưa, không archive dữ liệu chưa có
     checkpoint bảo vệ).
  2. Export toàn bộ dòng của partition ra một file JSONL, upload lên
     SeaweedFS (S3) bucket riêng, quyền ghi một chiều (bucket audit-archive,
     tái dùng client S3 hiện có cho SeaweedFS).
  3. Ghi một audit event `audit.archive` (`metadata`: partition name,
     khoảng id, đường dẫn file, checkpoint hash liên quan) — Story 4 AC-4.
  4. `ALTER TABLE audit_log DETACH PARTITION audit_log_YYYY_MM;` rồi `DROP TABLE audit_log_YYYY_MM;` (tương đương DROP PARTITION, không qua
     trigger row-level — Story 4 AC-3).

### 4.6 `AuditRequestContext` — điền outcome/ip/method/path/status_code cho call thủ công (Story 1 AC-9)

- Bean mới `AuditRequestContext` (package `audit/`), request-scoped qua
  `RequestContextHolder`: đọc `HttpServletRequest` hiện tại (nếu có) và expose
  `ip()`, `method()`, `path()` — service gọi `AuditService.record(...)` chỉ
  cần truyền `outcome` (do chính service biết thao tác thành công/thất bại)
  và lấy 3 trường còn lại từ bean này thay vì tự đọc `HttpServletRequest`.
  `status_code` do caller tại điểm gọi `record()` (thường trước khi
  controller trả response) không biết chắc mã cuối cùng — mặc định
  `outcome=success` → `200`, `outcome=failure` → giữ `null` (điền lại bởi
  §4.7 exception handler, nơi status code là chắc chắn).
- Áp dụng lại cho `ApiExceptionHandler.recordAccessDeniedAudit()` hiện tại
  (đang truyền `ip = null`) — sửa dùng bean này, khắc phục lỗ hổng đã phát
  hiện khi review.
- 14 service gọi `AuditService` thủ công (liệt kê ở file BA) đổi sang dùng
  `AuditRequestContext` thay vì tự inject `HttpServletRequest` — gộp vào Task
  5 dưới đây (rà soát 1 lần, sửa luôn).

### 4.7 Audit hoá lỗi nghiệp vụ 4xx trên route đã instrument (Story 1 AC-8)

- Mở rộng `ApiExceptionHandler`: thêm một `@ExceptionHandler` bắt các lỗi
  nghiệp vụ 4xx còn lại chưa được audit hôm nay — `ApiException` (khi
  `status` là 4xx nghiệp vụ, không phải 401/403),
  `MethodArgumentNotValidException`, `IllegalArgumentException`,
  `MethodArgumentTypeMismatchException`. Logic dùng chung
  `recordBusinessFailureAudit(request, action, resourceType, resourceId, reason)`:
  - Chỉ ghi nếu request attribute `AUDIT_RECORDED` (4.3) **chưa** được set —
    tránh ghi trùng nếu service đã tự ghi audit trước khi throw.
  - `action` suy ra từ route đã biết (map `HandlerMethod` → action, tái dùng
    catalog taxonomy ở Quyết định 4 SA) khi có thể xác định; nếu không xác
    định được `action` cụ thể (exception xảy ra trước khi vào đúng handler,
    vd bind lỗi ở tầng framework), dùng `action = "<domain đoán từ path>.request_rejected"` — best-effort, không chặn response (giống
    `recordAccessDeniedAudit` hiện tại: `recordAsync`, nuốt lỗi ghi audit).
  - `resourceType`/`resourceId`: lấy từ path variable nếu có (vd
    `{documentId}` trong URI), `null` nếu không xác định được — chấp nhận
    kém chi tiết hơn audit thành công, vẫn tốt hơn không có bản ghi.
- **Giới hạn đã biết:** với route có nhiều bước validate lồng nhau, "reason"
  suy luận tự động có thể không mô tả chính xác bằng service tự ghi — nếu
  một domain cụ thể cần audit thất bại chi tiết hơn (vd lý do nghiệp vụ cụ
  thể, không chỉ "validation failed"), service đó nên tự gọi `record()` với
  `outcome=failure` trước khi throw (khi đó marker AUDIT_RECORDED chặn handler
  chung ghi trùng) — không bắt buộc cho toàn bộ 14 service ở v1 này.
- **Cố ý KHÔNG mở rộng danh sách trên sang `Exception.class` (500 bất kỳ) —
  đã cân nhắc lại 2026-09-20, quyết định: KHÔNG thêm.** Lý do:
  1. Khoảng trống này **đã có người che** — `AuditInterceptor.afterCompletion`
     (§4.3 quy tắc 3) đọc `response.getStatus()` sau khi response đã hoàn
     tất, chạy **bất kể** exception có được một `@ExceptionHandler` bắt hay
     không (kể cả khi container/Boot tự render trang lỗi mặc định). Một 500
     chưa từng qua `ApiExceptionHandler` **vẫn** được audit fallback hôm nay
     — thêm catch-all không lấp một lỗ hổng audit đang tồn tại, nó chỉ đổi
     nơi audit xảy ra.
  2. `server.error.include-message` mặc định `never` (chưa từng bị đổi trong
     `application.yml`) nên response 500 mặc định của Boot **đã** không có
     `message` — `web/src/lib/api.ts` đọc `body.message` nhưng đã tự fallback
     an toàn về `"${status} ${statusText}"` khi field đó vắng mặt. Không có
     rò rỉ đang xảy ra cần vá.
  3. Lợi ích thực của catch-all chỉ là **hình dạng response nhất quán**
     (`{statusCode, message, timestamp}` thay vì shape mặc định của Boot
     `{timestamp, status, error, path}`) — không phải sửa lỗi, không phải
     đóng lỗ hổng bảo mật.
  4. Đổi lại, rủi ro **có thật và dễ tái phạm**: mọi handler khác trong
     `ApiExceptionHandler` hiện đều truyền thẳng `ex.getMessage()` vào body
     (`handleApiException`, `handleIllegalArgument`, ...) — một tiền lệ rất
     mạnh khiến người viết catch-all sau này gần như chắc chắn copy y hệt
     pattern đó cho case 500, biến "an toàn khi viết đúng lần đầu" thành một
     lỗ hổng rò rỉ nội bộ (message exception thật, có thể chứa đường dẫn
     file/tên bảng/tham số SQL) chỉ lộ ra khi có crash thật trong production,
     không phải lúc review code. Cùng rủi ro cho marker
     `AUDIT_RECORDED_ATTRIBUTE`: nếu handler mới lỡ set marker mà không tự
     ghi audit tương đương, quy tắc 2 (§4.3) sẽ khiến quy tắc 3 (fallback)
     không còn chạy — mất audit của **mọi** lỗi 500, âm thầm.
  5. Tiền lệ đã có: `NotesOutboxAtomicityIT`
     (`aFailedOutboxInsertRollsBackTheDocumentNoteInsertInTheSameTransaction`)
     từng cố tình chọn assert exception bị ném ra (`assertThatThrownBy`) thay
     vì `status().isInternalServerError()`, với comment giải thích rõ: không
     "bịa" một catch-all chỉ để một test dễ đọc hơn.

  **Nếu sau này có lý do đủ mạnh để thêm** (ví dụ một API consumer bên ngoài
  cần shape lỗi nhất quán tuyệt đối, không chỉ nội bộ `web/`), bắt buộc đồng
  thời cả ba: (a) message cố định, không bao giờ `ex.getMessage()`; (b)
  `log.error(..., ex)` đầy đủ stack trace; (c) tự gọi
  `auditService.recordAsync(...)` với action/status/actor tương đương những
  gì `AuditInterceptor` fallback ghi hôm nay **trước khi** (nếu có) set
  marker — và đổi `NotesOutboxAtomicityIT` về lại
  `mockMvc.perform(...).andExpect(status().isInternalServerError())` vì
  MockMvc sẽ không còn rethrow exception nữa một khi có handler bắt nó. Cập
  nhật thêm `classification-notes-02-notes.md` (mục rà soát rò rỉ — kết luận
  "không có đường rò rỉ `body`" giả định trước tình trạng không có catch-all)
  và đối chiếu lại `group-management-sharing-01-group-lifecycle.md` §4.6 mục
  4 (tham chiếu format lỗi của `ApiExceptionHandler`).

## 5. Test plan

- Unit test: `AuditService.record()` không còn gọi advisory lock (verify
  qua mock `EntityManager`/`doWork`); `enforceMetadataAllowlist`/
  `normalizeMetadata` không đổi hành vi.
- Unit test: `AuditInterceptor` — set `AUDIT_RECORDED` request attribute →
  không ghi thêm; không set + status 200 → không ghi; không set + status
  403/500 → ghi fallback.
- Integration test (Testcontainers Postgres, theo mẫu
  `SystemConfigControllerIT`):
  - Trigger cho phép đúng một lần UPDATE seal hợp lệ; UPDATE lần hai (re-seal)
    hoặc UPDATE đổi field khác → bị chặn (`RAISE EXCEPTION`).
  - Sealer job: ghi N dòng đồng thời (nhiều thread giả lập nhiều instance),
    chạy sealer, xác nhận chain liên tục đúng theo thứ tự `id` kể cả khi có
    `id` bị khuyết (rollback một transaction giữa chừng).
  - Migration rename-and-swap (2.2): dữ liệu cũ trong `audit_log_legacy`
    khớp 100% với `audit_log` mới sau copy; `verifyChain` toàn bộ dữ liệu
    cũ (đã seal hết) vẫn `ok = true` sau khi chuyển bảng.
  - `verifyChain(from, to)` dùng checkpoint: kết quả giống hệt verify toàn
    bộ trên cùng dữ liệu (so sánh hai cách trên tập test nhỏ).
  - `GET /audit` với R2: chỉ trả `resource_type=document` trong phạm vi
    nhóm; không trả `account.*`/`system_config.*`; R3 trả đầy đủ.
  - `GET /audit/verify` thiếu `from`/`to` → 400, không rơi về verify toàn
    bộ.
  - Một request gọi route đã instrument nhưng bị `MethodArgumentNotValidException`
    (400): đúng 1 bản ghi audit `outcome=failure` được tạo (Story 1 AC-8);
    request khác mà service đã tự ghi audit trước khi throw: không bị ghi
    trùng bởi `ApiExceptionHandler` (marker `AUDIT_RECORDED` chặn đúng).
  - `AuditRequestContext` trong một request thật (Testcontainers +
    `MockMvc`/thật HTTP): `ip`/`method`/`path` khớp request gửi lên, không
    còn `null` như hành vi cũ của `recordAccessDeniedAudit` (Story 1 AC-9).
- **Test lưới an toàn (Story 1 AC-1/AC-2, theo rủi ro SA mục 6 "sót
  instrumentation"):** một test quét toàn bộ `@RestController` trong
  `backend/src/main/java` (reflection, không giới hạn 14 service đã biết ở
  file BA) tìm mọi method có `@PostMapping/@PutMapping/@PatchMapping/ @DeleteMapping`, xác nhận với mỗi route: hoặc gọi `AuditService.record*`
  đâu đó trong call chain (kiểm bằng cách chạy request thật qua
  Testcontainers + assert có đúng 1 dòng `audit_log` mới với `resource_type`
  khớp), hoặc route đó nằm trong một allowlist tường minh (vd endpoint chỉ
  đọc dữ liệu qua verb non-idempotent, không thay đổi state) — test **fail**
  nếu có route mutating mới không thuộc 1 trong 2 nhóm trên. Đây là lưới an
  toàn thay thế cho việc interceptor không còn tự động bắt mọi request
  thành công.
- Case biên/lỗi cần cover: ghi audit lỗi (giả lập constraint vi phạm) khiến
  transaction nghiệp vụ rollback theo (Story 1 AC-3); sealer job chạy khi
  không có dòng nào chưa seal (no-op an toàn); retention job chạy khi chưa
  đủ 24 tháng dữ liệu (no-op, không tạo file rỗng).

## 6. Task breakdown

- [x]  Task 1: Migration `V14` — cột `sealed_at`, trigger seal-aware (2.3),
  rename-and-swap sang bảng partitioned + index (2.2), giữ
  `audit_log_legacy`. **Thực hiện như `V21` (không phải `V14`/`V15` như bản
  nháp — hai số đó đã dùng/bỏ qua trong nhánh hiện tại, xem
  `V21__audit_log_seal_and_partition.sql`). Đã bổ sung tạo partition lùi về
  tháng cũ nhất của dữ liệu hiện có, không chỉ tháng hiện tại + 2 (bản nháp
  thiếu bước này, INSERT sẽ lỗi "no partition found" với dữ liệu cũ hơn 1
  tháng).**
- [x]  Task 2: Bảng `audit_checkpoint` (2.4) cùng migration hoặc migration kế
  tiếp `V15`. **Thực hiện như `V22__audit_checkpoint_and_scoped_read.sql`
  (cùng lý do đổi số ở Task 1); gộp luôn seed quyền `audit.read_scoped` của
  Task 7 vào migration này.**
- [x]  Task 3: Sửa `AuditService.record()` bỏ lock/hash đồng bộ (4.1); thêm
  `markRecordedOnCurrentRequest()`.
- [x]  Task 4: `AuditSealerJob` (4.2) + advisory-lock helper dùng chung với
  checkpoint/partition/retention job (cùng một lock key theo từng loại
  job, tránh đụng nhau). **`AuditAdvisoryLock` dùng
  `pg_try_advisory_xact_lock` (transaction-scoped) thay vì
  `pg_try_advisory_lock`/`pg_advisory_unlock` (session-scoped) như bản nháp —
  cặp lock/unlock session-scoped qua `JdbcTemplate` không đảm bảo cùng một
  connection vật lý khi dùng connection pool.**
- [x]  Task 5: Sửa `AuditInterceptor` theo (4.3); rà lại 14 service gọi
  `AuditService` (đã liệt kê ở file BA) để xác nhận route nào đã đủ audit
  thủ công (interceptor sẽ tự im lặng nhờ marker, không cần sửa từng
  service) — đổi các service này sang dùng `AuditRequestContext` (4.6)
  khi sửa. **13 service/controller thực tế đang gọi `AuditService` (rà lại
  bằng grep, không phải 14) đã chuyển hết sang `AuditRequestContext`.**
- [x]  Task 6: `verifyChain(from, to)` streaming + dùng checkpoint (4.5);
  cập nhật `AuditController.verify()` bắt buộc tham số.
- [x]  Task 7: `GET /audit` thêm filter + phân trang (mục 3); `AuditScopeFilter`
  cho R2 (4.4); thêm quyền `PERM_audit.read_scoped` vào RBAC seed, gán R2.
- [x]  Task 8: Checkpoint job + tạo-partition-trước + retention/archive job
  (4.5), export SeaweedFS, event `audit.archive`.
- [x]  Task 9: Trang `web/src/app/admin/audit/` (bộ lọc + phân trang + nút
  verify chỉ hiện với R3) theo khuôn `admin/system-config`,
  `admin/accounts` hiện có. Build + lint + typecheck frontend đã chạy sạch;
  **chưa chạy thử trên trình duyệt thật với backend/Keycloak thật.**
- [x]  Task 10: `AuditRequestContext` bean (4.6); mở rộng `ApiExceptionHandler`
  audit hoá lỗi nghiệp vụ 4xx (4.7), sửa `recordAccessDeniedAudit` dùng
  `AuditRequestContext` thay vì `ip=null`.
- [x]  Task 11: Test quét toàn bộ endpoint mutating (lưới an toàn, §5) —
  **Xong (2026-09-09).** `AuditInstrumentationCoverageTest` (unit test,
  không cần Docker) — phân tích call-graph tĩnh bằng `org.springframework.asm`
  (đã có sẵn qua spring-core, không thêm dependency mới): với mỗi
  `@PostMapping/@PutMapping/@PatchMapping/@DeleteMapping` (reflection),
  kiểm tra `AuditService.record`/`recordAsync` có nằm trong call-graph tĩnh
  hay không (bắc cầu qua interface dispatch + lambda cùng lớp), fail nếu
  không và route không có trong `ALLOWLIST` tường minh kèm lý do. Chạy thật
  phát hiện **10 route thiếu audit thật sự** (không phải lỗi phân tích) —
  đã xử lý:
  - 8 route thiếu audit → đã bổ sung: `IngestController#initUploadSession/
    closeBatch`, `PersonalController#initUploadSession/closeBatch`
    (`UploadSessionService`/`PersonalService`), 4 route của
    `ReviewController` (`reviseSegment/deleteSegment/addSegment/confirmRun`
    → `DigitizationRunLifecycleServiceImpl`, đúng
    `digitization-review-01-ocr.md` §4 bước 3/4 và BA AC-6 US2 — action
    `digitization_review.segment_revised/deleted/added/confirmed`, đăng ký
    thêm vào `ALLOWED_METADATA_KEYS` để chặn rò rỉ nội dung văn bản).
  - 2 route allowlist (không cần audit, có lý do trong code):
    `UploadSessionController#putChunk` (plumbing byte-range, đã audit ở
    ranh giới init/complete), `NotificationsController#markRead` (hành
    động cá nhân, không có giá trị FR-09/FR-10).
- [x]  Task 12: Test plan §5 còn lại — unit test (AuditService,
  AuditInterceptor, AuditSealerJob, ApiExceptionHandler) đã chạy xanh
  (không cần Docker). Testcontainers IT (`AuditControllerIT`) **đã chạy
  xanh 8/8** (2026-09-09, môi trường có Docker) sau khi sửa 2 lỗi trong
  chính test (không phải lỗi nghiệp vụ): `resetState()` dùng `DELETE FROM
  audit_log` bị chặn bởi trigger append-only → đổi sang `TRUNCATE TABLE
  audit_log` (không kích hoạt trigger `FOR EACH ROW`); 1 test kỳ vọng
  `DataIntegrityViolationException` cho `RAISE EXCEPTION` thô (SQLSTATE
  P0001) nhưng Spring dịch đúng sang `UncategorizedSQLException` → sửa lại
  kỳ vọng. Toàn bộ `mvn test` (639 test khác trong repo) cũng đã chạy, 2
  lỗi còn lại (`NotesControllerIT`, `SearchIndexOutboxPollerIT`) đã xác
  nhận **có từ trước, không liên quan tới audit-logging** — ngoài phạm vi
  slug này.

## 7. Rủi ro kỹ thuật

- Migration rename-and-swap (2.2) là thao tác một lần, không rollback tự
  động nếu copy dữ liệu sai — **bắt buộc** chạy trên bản sao/staging trước,
  giữ `audit_log_legacy` cho tới khi xác nhận `verifyChain` khớp trên môi
  trường thật, dọn ở migration riêng sau (không gộp vào V14).
- Composite PK `(id, created_at)` khác PK hiện tại `(id)` — cần xác nhận
  Hibernate `ddl-auto=validate` không fail khi khởi động backend sau
  migration (entity `AuditLog` vẫn chỉ khai báo `@Id id`); nếu validate kén,
  cân nhắc chuyển entity đó sang chỉ đọc qua native query thay vì JPA
  `@Entity` cho phần liên quan tới PK.
- Trigger seal-aware (2.3) là một điều kiện UPDATE khá dài — sai một điều
  kiện `IS NOT DISTINCT FROM` có thể vô tình mở khe hở sửa dữ liệu đã ghi;
  cần test IT riêng cho từng trường hợp cố tình sửa sai (đã liệt kê ở §5).
- Nhiều backend instance cùng chạy `@Scheduled` — advisory lock
  (`pg_try_advisory_lock`) xử lý đúng cho single-DB, nhưng nếu tương lai
  sharding DB thì cơ chế này không còn đúng (ngoài phạm vi hiện tại, NFR chỉ
  yêu cầu multi-instance cùng một DB).
- Phạm vi R2 (4.4) chỉ phủ `resource_type=document` ở v1 — đã xác nhận với
  người dùng (2026-09-01) là chấp nhận được cho v1, `folder`/`group` (nằm
  trong phạm vi Story 3 đã duyệt) là việc kế tiếp, cần thêm `Specification`
  tương đương `BusinessDataScope.forDocuments` cho các loại đó trước khi DEV
  mở rộng `AuditScopeFilter`.
- Handler audit hoá lỗi 4xx (4.7) suy luận `action`/`resourceType` từ route
  khi không có sẵn — cho một số domain có thể ra `action` chung chung
  (`request_rejected`) thay vì taxonomy chuẩn `<domain>.<verb>` (Quyết định 4
  SA); chấp nhận được cho trường hợp lỗi (khác với sự kiện thành công, vốn
  luôn phải đúng taxonomy theo Story 1 AC-5), nhưng cần lưu ý khi viết
  `AuditScopeFilter`/UI lọc theo action không giả định mọi `action` đều khớp
  taxonomy.
- Archive job phụ thuộc SeaweedFS còn hoạt động tại thời điểm chạy — nếu
  upload lỗi, **không** được `DROP PARTITION` (thứ tự bắt buộc: export
  thành công → ghi audit event → detach/drop); cần retry/alert riêng cho
  bước upload, không tự động drop khi chưa chắc export xong.
