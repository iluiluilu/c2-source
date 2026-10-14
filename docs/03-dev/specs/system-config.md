# Tech Spec: system-config

> File: `docs/03-dev/specs/system-config.md` — Owner: DEV
> Nguồn story: `docs/02-ba/stories/system-config.md`
> Status: Approved

> ## ⚠️ Thứ tự triển khai chung (đã chốt) — đọc trước khi implement
>
> Flyway trong repo **không** bật `out-of-order`
> (`backend/src/main/resources/application.yml`), nên **số hiệu migration
> bắt buộc khớp đúng thứ tự merge** — không thể chèn số nhỏ hơn về sau.
> Migration đang có trong repo: `V1`–`V6`.
>
> | # | Spec | Migration |
> |---|---|---|
> | 0 | *(hạ tầng, không migration)* | Redis + RabbitMQ vào `docker-compose*.yml` + `backend/pom.xml` — dựng **một lần**, dùng chung |
> | 1 | `system-config` ⬅ **spec này** | `V7__init_system_config.sql` |
> | 2 | `account-lifecycle` | `V8__init_account.sql` |
> | 3 | `warehouse-structure` | `V9__warehouse_structure_v2.sql` |
> | 4 | `data-ingestion` | `V10__data_ingestion.sql` |
> | 5 | `personal-storage-quota` | `V11__personal_storage_quota.sql` |
> | 6 | `rbac-group-scope` | `V12__init_groups.sql` + `V13__drop_case_files.sql` |
> | 7 | `auth-session` | `V14__init_keycloak_event_cursor.sql` |
>
> `rbac-group-scope` và `auth-session` không phụ thuộc spec nào khác nên
> **được phép code song song ngay từ đầu** — bảng trên chỉ ràng buộc thời
> điểm *merge*, không ràng buộc thời điểm *phát triển*.

## 1. Tóm tắt kỹ thuật

- Thêm bảng `system_config` (key/value/value_type) trên PostgreSQL (migration
  Flyway `V7`), là nguồn sự thật cho tham số nghiệp vụ MVP (8 key, xem BA
  Story 1 AC-1). `allowed_file_format` là bảng riêng, đã có ở
  `data-ingestion.md` — **không** thuộc phạm vi migration của spec này, chỉ
  tái sử dụng khi hiển thị danh mục ở Story 1.
- Backend: package mới `com.aismartstore.backend.systemconfig` gồm entity
  `SystemConfig`, repository, `SystemConfigService` (đọc/ghi + fallback
  default + cache Redis), `SystemConfigController` (REST cho R3), class
  registry tĩnh `SystemConfigDefaults` chứa 8 key MVP + giá trị mặc định +
  cờ nhạy cảm + `value_type` (dữ liệu từ Quyết định 3 của SA — không lặp lại
  số liệu trong spec, tham chiếu trực tiếp bảng đó khi code).
- Đọc giá trị: các module khác (FR-13/14/11/17/19/26/27/34) gọi
  `SystemConfigService.getString/getLong/getBoolean/getJson(key, Class)`
  (theo `value_type`) — service tự đọc Redis → DB → default tĩnh, không đọc
  trực tiếp bảng.
- Ghi giá trị: ghi DB trước (transaction), sau đó ghi đồng bộ vào Redis cùng
  request, cuối cùng ghi 1 audit event (`resourceType = "system_config"`).
  Tham số nhạy cảm (2 key) yêu cầu xác nhận 2 bước ở tầng API (endpoint xác
  nhận riêng, không tự apply ngay ở lần gọi đầu).
- Hạ tầng: bổ sung service `redis` vào `docker-compose.yml` +
  `docker-compose.dev.yml`, dependency `spring-boot-starter-data-redis` vào
  `pom.xml` (dùng chung với cache `allowed_file_format` — chỉ dựng 1 lần,
  không lặp lại nếu `data-ingestion.md` đã làm trước; kiểm tra lại trước khi
  thêm để tránh trùng).
- Frontend: trang mới `web/src/app/admin/system-config/page.tsx`, thêm entry
  vào `AdminTabs.tsx` (tab "Cấu hình hệ thống" thay vì để trống/placeholder).

## 2. Data model

```sql
-- V7__init_system_config.sql  (thứ tự đã chốt: migration đầu tiên của nhóm 7 spec)
CREATE TABLE system_config (
    key         VARCHAR(128) PRIMARY KEY,
    value       TEXT NOT NULL,
    value_type  VARCHAR(16) NOT NULL CHECK (value_type IN ('STRING','NUMBER','BOOLEAN','JSON')),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by  VARCHAR(255)
);

-- Không seed dữ liệu mặc định vào bảng (Story 1 AC-4: thiếu bản ghi -> fallback
-- tĩnh). SystemConfigDefaults (Java) là nguồn mặc định, KHÔNG phải bản ghi DB.
```

```java
// com.aismartstore.backend.systemconfig.SystemConfig (entity)
@Entity
@Table(name = "system_config")
public class SystemConfig {
    @Id
    @Column(length = 128)
    private String key;

    @Column(columnDefinition = "text", nullable = false)
    private String value;

    @Enumerated(EnumType.STRING)
    @Column(name = "value_type", length = 16, nullable = false)
    private ValueType valueType; // STRING, NUMBER, BOOLEAN, JSON

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "updated_by", length = 255)
    private String updatedBy;
    // getters/setters
}

public enum ValueType { STRING, NUMBER, BOOLEAN, JSON }

// SystemConfigDefaults — registry tĩnh trong code (không phải bảng DB),
// nguồn dữ liệu = bảng Quyết định 3 trong docs/01-sa/architecture/system-config.md
public record SystemConfigDef(
        String key, ValueType valueType, String defaultValue,
        boolean sensitive, String description, String unit) {}
// 8 entries gốc theo SA: retention.trash_retention_days,
// storage_quota.default.R1/R2/R3 (3 key riêng hoặc 1 JSON — xem Quyết định
// thiết kế bên dưới), ingest.max_files_per_batch,
// ingest.max_batch_size_bytes, ocr_stt.auto_confirm_hours,
// warehouse.storage_warning_threshold_percent,
// warehouse.storage_block_threshold_percent, ai_queue.concurrency,
// ai_queue.batch_size, backup.schedule_cron.
// + ingest.max_file_size_bytes (NUMBER, đơn vị byte, không nhạy cảm) —
//   key BỔ SUNG ngoài danh mục SA gốc, theo Quyết định 3 mục 0 của
//   data-ingestion.md: giới hạn dung lượng TỪNG tệp (khác
//   ingest.max_batch_size_bytes vốn là tổng cả batch). Dùng ở
//   data-ingestion.md mục 4.2 bước 3b và trả về qua GET /ingest/limits.
```

**Quyết định thiết kế (DEV):** `storage_quota.default.R1/R2/R3` trong bảng SA
là 3 giá trị theo vai trò nhưng SA mô tả như 1 dòng — spec này chốt lưu là
**3 key riêng** (`storage_quota.default.can_bo`,
`storage_quota.default.lanh_dao`, `storage_quota.default.quan_tri`, mỗi key
`value_type = NUMBER`, đơn vị byte) để tái dùng đúng cơ chế validate/audit
theo key đơn của toàn bộ spec, tránh phải làm riêng một nhánh JSON chỉ cho
tham số này. Vậy danh mục MVP thực tế trong `system_config` là **10 key**
(8 mục SA − 1 mục gộp quota + 3 key quota tách ra), khớp tinh thần BA AC-1
("8 key + allowed_file_format" — số 8 của BA tính theo dòng bảng SA, không
đổi phạm vi nghiệp vụ, chỉ đổi cách lưu trữ nội bộ).

## 3. API contract

Base path: `/admin/system-config`. Toàn bộ endpoint yêu cầu
`@PreAuthorize("hasRole('QUAN_TRI')")` (đồng bộ hằng số `Role.QUAN_TRI`).

| Method | Endpoint | Request | Response |
|---|---|---|---|
| GET | `/admin/system-config` | – | `200` `[{key, description, valueType, unit, value, defaultValue, usingDefault, sensitive}]` — Story 1 |
| GET | `/admin/system-config/{key}/history` | – | `200` `[{oldValue, newValue, actor, createdAt}]` (đọc từ `audit_log` theo `resourceType=system_config`, `resourceId={key}`, mới nhất trước) — Story 5 AC-1 |
| PATCH | `/admin/system-config/{key}` | `{ "value": <string|number|boolean|object> }` | `200 {key, value, updatedAt}` nếu tham số **không nhạy cảm** (áp dụng ngay) — Story 2. `409 {code: "CONFIRMATION_REQUIRED", confirmationToken}` nếu tham số **nhạy cảm** (chưa áp dụng) — Story 3 |
| POST | `/admin/system-config/{key}/confirm` | `{ "confirmationToken": string, "value": <...> }` | `200 {key, value, updatedAt}` — áp dụng giá trị đã xác nhận (Story 3 AC-3), hoặc `410` nếu token hết hạn/không khớp |
| POST | `/admin/system-config/{key}/rollback` | `{ "toValue": <...>, "confirmationToken"?: string }` | `200 {key, value, updatedAt}` — Story 5. Nếu `key` thuộc nhóm nhạy cảm và thiếu `confirmationToken` hợp lệ → `409` giống PATCH (Story 5 AC-2) |
| GET | `/admin/system-config/{key}/format-options` *(tuỳ chọn, tái dùng)* | – | Không làm ở spec này — `allowed_file_format` giữ API riêng ở `data-ingestion.md`, chỉ hiển thị lồng ghép ở UI Story 1 bằng cách gọi API sẵn có đó. |

Lỗi validate chung (Story 2 AC-2/AC-3):
`400 {code: "INVALID_VALUE"|"CONSTRAINT_VIOLATION", message, key}`.

`confirmationToken`: chuỗi ngắn hạn (TTL 5 phút, lưu trong Redis, key
`sysconfig:confirm:{key}:{token}` → giá trị đề xuất), sinh ở lần PATCH đầu
cho tham số nhạy cảm, tiêu thụ một lần ở `/confirm`. Không dùng session HTTP
để tránh phụ thuộc trạng thái phía client giữa 2 bước.

## 4. Luồng xử lý chính

### 4.1 Đọc danh mục (Story 1)

1. `GET /admin/system-config` → `SystemConfigService.listAll()`.
2. Với mỗi entry trong `SystemConfigDefaults` (10 key), tìm bản ghi DB theo
   `key`; nếu có → `usingDefault=false`, value từ DB; nếu không → dùng
   `defaultValue` tĩnh, `usingDefault=true` (AC-4).
3. Trộn thêm 1 dòng tổng hợp cho `allowed_file_format` (gọi API/service sẵn
   có ở `data-ingestion.md`, không đọc lại bảng ở đây) để UI hiển thị đủ 1
   danh mục duy nhất theo AC-1.
4. Trả về, sắp theo nhóm nghiệp vụ để UI render.

### 4.2 Sửa tham số không nhạy cảm (Story 2)

1. `PATCH /{key}` → kiểm tra `key` có trong registry; nếu không → `404`.
2. Nếu `sensitive == true` → dừng, trả `409 CONFIRMATION_REQUIRED` kèm
   `confirmationToken` mới (xem 4.3), **không ghi DB**.
3. Convert `value` theo `valueType` (`SystemConfigValue` wrapper đã chốt ở
   BA); convert lỗi → `400 INVALID_VALUE`.
4. Chạy validate khoảng giá trị riêng của key (vd. `ingest.max_files_per_batch
   > 0`) và validate liên tham số (AC-3: ví dụ
   `warehouse.storage_warning_threshold_percent <
   warehouse.storage_block_threshold_percent`; và
   `ingest.max_file_size_bytes <= ingest.max_batch_size_bytes` — một tệp
   đơn lẻ không thể lớn hơn trần của cả batch, nếu không sẽ có tệp vĩnh
   viễn không tải được) → lỗi → `400 CONSTRAINT_VIOLATION`, **không ghi
   DB**, giá trị cũ giữ nguyên.
5. Trong 1 transaction: upsert `system_config` (value/value_type/updated_at/
   updated_by).
6. Sau khi transaction commit thành công: ghi đồng bộ giá trị mới vào Redis
   (`SET sysconfig:{key} <value> EX <ttl_dự_phòng>`, ví dụ TTL 1 giờ làm lớp
   dự phòng — không phải cơ chế chính).
7. Ghi 1 audit event qua `AuditService.record(...)`:
   `resourceType="system_config"`, `resourceId=key`,
   `metadata={"key":key,"oldValue":...,"newValue":...}`.
8. Trả `200` với giá trị mới.
9. Nếu bước 6 (Redis) lỗi: log cảnh báo, **không rollback DB** (DB là nguồn
   sự thật — Quyết định 4 SA); TTL dự phòng + lần đọc kế tiếp sẽ tự phục hồi
   cache khi cache-miss đọc lại từ DB.

### 4.3 Sửa tham số nhạy cảm — bước 1: yêu cầu xác nhận (Story 3)

1. `PATCH /{key}` với `key` nhạy cảm → convert + validate giá trị đề xuất
   (giống 4.2 bước 3–4) **trước khi** sinh token, để tránh yêu cầu xác nhận
   cho một giá trị vốn đã invalid.
2. Sinh `confirmationToken` (UUID), lưu vào Redis kèm giá trị đề xuất đã
   convert, TTL 5 phút.
3. Trả `409 {code: "CONFIRMATION_REQUIRED", confirmationToken}` — không ghi
   DB, không ghi audit (AC-2/BA Story3-AC-2 ngầm định: chưa xác nhận = chưa
   đổi).

### 4.4 Xác nhận bước 2 (Story 3 AC-3)

1. `POST /{key}/confirm` với `confirmationToken` → tìm trong Redis; không có
   / hết hạn → `410`.
2. Lấy giá trị đề xuất đã lưu, thực hiện lại bước 5–8 của mục 4.2 (ghi DB →
   Redis chính → audit).
3. Xoá token khỏi Redis (dùng một lần).

### 4.5 Rollback (Story 5)

1. `GET /{key}/history` → query `audit_log` theo `resourceType=system_config
   AND resourceId=key`, map `metadata.oldValue/newValue/createdAt/actor`,
   sắp mới → cũ.
2. `POST /{key}/rollback` với `toValue`:
   - Nếu `key` nhạy cảm và thiếu `confirmationToken` hợp lệ → trả `409`
     giống 4.3 (tạo token cho `toValue`), yêu cầu FE gọi lại `/confirm` với
     token đó áp dụng đúng luồng 2 bước (tái dùng, không tạo API riêng).
   - Nếu không nhạy cảm hoặc token hợp lệ → chạy lại bước 5–8 của 4.2 với
     `newValue = toValue`, nhưng `action` audit ghi rõ
     `"system_config.rollback"` (khác `"system_config.update"` của case
     thường) để phân biệt khi truy vết (AC-3: không xoá lịch sử cũ, chỉ
     append thêm — đúng bản chất append-only sẵn có của `audit_log`).

### 4.6 Fallback khi bảng lỗi (Story 6)

1. Mọi lần đọc qua `SystemConfigService.get*` bọc trong `try/catch`
   quanh cả Redis lẫn DB.
2. Thứ tự đọc: Redis (`sysconfig:{key}`) → nếu miss, đọc DB → nếu DB lỗi
   (exception) hoặc bản ghi không tồn tại → dùng `defaultValue` tĩnh trong
   `SystemConfigDefaults`, ghi `log.warn` (AC-2), **không throw** ra module
   gọi.
3. Tham số hạ tầng (`*Properties` hiện có) không đi qua service này —
   Quyết định 1 SA, giữ nguyên YAML tĩnh (AC-3).

## 5. Test plan

- **Unit test** (`SystemConfigServiceTest`):
  - Convert giá trị đúng/sai theo từng `value_type` (STRING/NUMBER/BOOLEAN/
    JSON) — Story 2 AC-2.
  - Validate liên tham số (ngưỡng cảnh báo < ngưỡng chặn) chặn đúng, giá trị
    hợp lệ đi qua — Story 2 AC-3.
  - Fallback: DB throw exception / bản ghi không tồn tại → trả default,
    không throw — Story 6 AC-1/AC-2.
  - Sinh/tiêu thụ `confirmationToken`: đúng token → apply; sai/hết hạn →
    lỗi; không được phép áp dụng khi thiếu token cho key nhạy cảm — Story 3
    AC-1/AC-2.
- **Integration test** (`SystemConfigControllerIT`, dùng Testcontainers
  Postgres + Redis nếu stack test hiện có hỗ trợ, hoặc embedded/fake Redis
  nếu chưa — kiểm tra pattern test hiện có trước khi thêm dependency mới):
  - R3 gọi PATCH tham số thường → 200, đọc lại GET thấy giá trị mới, có audit
    event mới nhất khớp key/old/new — Story 2 AC-1/AC-4, Story 4 AC-1/AC-2.
  - Non-R3 gọi bất kỳ endpoint `/admin/system-config/**` → 403 — Story 1 AC-3.
  - PATCH tham số nhạy cảm không kèm confirm → 409, DB/audit không đổi;
    confirm với token đúng → 200, audit ghi 1 event — Story 3 AC-1/AC-3/AC-4
    (test cả việc tham số thường KHÔNG trả 409).
  - PATCH giá trị invalid (sai kiểu, ngoài khoảng) → 400, giá trị cũ không
    đổi, không tạo audit — Story 2 AC-2, Story 4 AC-3.
  - `GET /{key}/history` sau nhiều lần sửa → thứ tự mới→cũ đúng — Story 5
    AC-1.
  - Rollback về giá trị cũ (kể cả key nhạy cảm, phải qua confirm) → giá trị
    áp dụng đúng, audit event mới thêm vào (không sửa/xoá event cũ) — Story
    5 AC-2/AC-3.
  - Bảng `system_config` không có bản ghi cho 1 key → GET danh mục vẫn trả
    default kèm `usingDefault=true` — Story 1 AC-4.
- **Case biên/lỗi cần cover:**
  - Race condition: 2 request PATCH cùng key gần đồng thời — DB
    unique key + transaction đảm bảo tuần tự, request sau ghi đè giá trị
    trước (last-write-wins theo yêu cầu BA, không cần optimistic lock cho
    MVP).
  - Redis down khi PATCH: DB ghi thành công, Redis ghi lỗi → response vẫn
    200 (DB là nguồn sự thật), log cảnh báo, không audit lỗi giả.
  - Redis down khi GET (đọc runtime bởi module khác): fallback DB, nếu DB
    cũng lỗi → fallback default tĩnh (test riêng ở service dùng, ví dụ
    `PersonalService` đọc `storage_quota.default.*` — cần audit lại code
    hiện có, xem Rủi ro).
  - `confirmationToken` dùng lại lần 2 (đã tiêu thụ) → lỗi rõ ràng, không
    apply lại.

## 6. Task breakdown

- [x] Task 1: Migration `V7__init_system_config.sql` (bảng `system_config`,
      không seed dữ liệu).
- [x] Task 2: Hạ tầng Redis — thêm service `redis` vào
      `docker-compose.yml`/`docker-compose.dev.yml`, dependency
      `spring-boot-starter-data-redis` vào `pom.xml`, cấu hình
      `spring.data.redis.*` trong `application.yml`/`application-dev.yml`
      **(kiểm tra trước, tránh trùng nếu `data-ingestion.md` đã làm)**.
- [x] Task 3: Entity `SystemConfig`, `ValueType` enum, repository JPA.
- [x] Task 4: `SystemConfigDefaults` (10 key MVP, default value, sensitive
      flag, value_type, description, unit — copy từ bảng Quyết định 3 SA) +
      `SystemConfigValue` wrapper (`asString/asLong/asBoolean/asJson`).
      Bổ sung khi rà soát lại: registry đang triển khai thiếu key
      `backup.schedule_cron` (SA Quyết định 3 — key nhạy cảm thứ 2, cùng
      nhóm với `retention.trash_retention_days`) — đã thêm (STRING, cron,
      default `"0 2 * * *"`, sensitive=true) + validate 5-field cron shape
      trong `SystemConfigValidator`. Danh mục thực tế hiện là 13 key.
- [x] Task 5: `SystemConfigService` — `listAll`, `get*` (Redis→DB→default,
      Story 6), `update` (validate + DB + Redis + audit, Story 2), sinh/xác
      thực `confirmationToken` (Story 3), `rollback` (Story 5), đọc
      `history` từ `audit_log` (Story 5 AC-1).
- [x] Task 6: Validate liên tham số (ngưỡng cảnh báo kho < ngưỡng chặn — mở
      rộng thêm rule khác nếu phát hiện khi code) tách riêng thành
      `SystemConfigValidator` để dễ unit test độc lập.
- [x] Task 7: `SystemConfigController` (`GET`, `PATCH`, `POST /confirm`,
      `POST /rollback`, `GET /history`), `@PreAuthorize("hasRole('QUAN_TRI')")`
      trên toàn bộ endpoint (Story 1 AC-3).
- [x] Task 8: Cập nhật `AuditService` call-site — dùng `action` phân biệt
      `system_config.update` vs `system_config.rollback` (Story 4, Story 5
      AC-3).
      **Phát hiện khi viết integration test (xem mục 7 "Rủi ro"):**
      `AuditService`/`AuditLog` có một lỗi thật (insert xong Hibernate tự
      phát sinh thêm 1 UPDATE giống hệt, bị trigger append-only chặn) chỉ lộ
      ra khi ghi qua Postgres thật. Ban đầu không sửa (ngoài phạm vi task);
      **sau khi user xác nhận cho phép**, đã sửa dứt điểm trong
      `AuditService.record()` (chuẩn hoá `metadata` qua JSON round-trip
      trước khi gán vào entity — root cause + chi tiết fix ở mục 7).
      `AuditLog` giữ nguyên, không đổi.
- [x] Task 9: Rà soát & migrate các nơi đang đọc giá trị tĩnh tương ứng sang
      `SystemConfigService` — `PersonalService` (`quotaBytes` theo vai trò,
      FR-14) đã dùng `SystemConfigService`. Ngưỡng kho FR-13
      (`WarehouseService`) và giới hạn upload FR-11 (module ingest) **chưa áp
      dụng được** vì `warehouse-structure.md`/`data-ingestion.md` (V9/V10)
      chưa implement ở codebase hiện tại (chỉ có migration đến V7) — cần làm
      lại rà soát này khi 2 spec đó implement.
- [x] Task 10: Frontend `web/src/app/admin/system-config/page.tsx` — bảng
      danh mục (Story 1), form sửa tham số thường (Story 2), modal xác nhận
      2 bước cho tham số nhạy cảm (Story 3), tab lịch sử + nút khôi phục
      (Story 5); cập nhật `AdminTabs.tsx` để trỏ tab "Cấu hình hệ thống" vào
      trang mới thay vì để trống. (Đã tồn tại sẵn khi bắt đầu implement,
      không cần sửa — danh mục hiển thị động theo response GET nên key mới
      `backup.schedule_cron` tự xuất hiện, không cần đổi code FE.)
- [x] Task 11: Unit test (`SystemConfigServiceTest`, `SystemConfigValidatorTest`,
      `SystemConfigValueTest` — 30 test, pass) + integration test
      (`SystemConfigControllerIT`, Testcontainers Postgres+Redis, 10 test,
      pass — kể cả PATCH thường, confirm 2 bước, rollback, history, sau khi
      fix `AuditService` ở Task 8; chạy lặp lại nhiều lần để xác nhận không
      flaky).
- [x] Task 12: Cập nhật trạng thái task trong file spec này (đánh dấu done)
      sau khi implement — không viết lại toàn bộ spec.

## 7. Rủi ro kỹ thuật

- **[ĐÃ XỬ LÝ — trước là BLOCKING] `AuditService.record()` từng lỗi thật khi
  ghi vào Postgres thật.** Integration test (`SystemConfigControllerIT`,
  Testcontainers) cho thấy mọi lần ghi `audit_log` qua `SystemConfigService`
  (PATCH thường, confirm nhạy cảm, rollback) — dù `INSERT` thành công — đều
  bị Hibernate tự phát sinh thêm một `UPDATE` giống hệt ngay sau đó trên
  cùng dòng vừa insert, bị trigger append-only (`audit_log_immutable()`)
  chặn, làm cả request lỗi 500.
  **Root cause chính xác (đã xác nhận bằng cô lập nhị phân từng bước):**
  `AuditLog.metadata` là `Map<String, Object>` map `@JdbcTypeCode(SqlTypes.JSON)`.
  Hibernate 6 dùng snapshot qua JSON round-trip (serialize rồi deserialize
  bằng Jackson) để so sánh dirty-check sau insert; Jackson đọc lại một số
  nguyên JSON nhỏ thành `Integer`, không phải `Long` gốc. `SystemConfigService`
  đưa `Long` (từ `SystemConfigValue.asLong()`) vào `metadata` (`oldValue`/
  `newValue`), nên `Long.equals(Integer)` luôn `false` → Hibernate thấy row
  "dirty" ngay sau insert → tự flush thêm 1 `UPDATE` giống hệt → bị trigger
  chặn. Đã loại trừ (thử và không hết lỗi): self-invocation của `persist()`
  (dù vậy vẫn sửa vì là bug thật, xem `SystemConfigService.self`),
  `GenerationType.SEQUENCE` thay cho `IDENTITY`,
  `hibernate.jdbc.use_get_generated_keys=false`. Cô lập chính xác bằng cách
  bisect: metadata đơn giản (`Map.of("a", 1)`, toàn `Integer`) → không lỗi;
  metadata có `Long` (giống hệt cấu trúc `apply()` thật) → lỗi tái hiện
  100%; thêm select/persist khác trước đó không ảnh hưởng.
  **Fix:** `AuditService.record()` chuẩn hoá `metadata` qua đúng 1 lượt
  JSON round-trip (`ObjectMapper.writeValueAsString` rồi `readValue`) trước
  khi gán vào entity, để giá trị "sống" khớp sẵn với snapshot mà Hibernate
  sẽ tạo — không đổi kiểu dữ liệu thực lưu trong DB (Hibernate vốn đã
  serialize `metadata` sang JSON khi ghi, fix chỉ đồng bộ bản sao trong bộ
  nhớ). Không sửa `AuditLog` (giữ nguyên `GenerationType.IDENTITY`, đã thử
  đổi và không phải nguyên nhân). Đã bật lại toàn bộ 5 test bị `@Disabled`
  trong `SystemConfigControllerIT` — 10/10 pass (chạy lặp lại 3 lần để xác
  nhận không phải flaky). Rà soát 7 module khác đang gọi
  `AuditService.recordAsync` (`PersonalService`, `TagsService`,
  `NotesService`, `AccountsService`, `CaseFilesService`,
  `WarehouseController`, `DocumentsService`) — không module nào có test
  riêng hiện tại nên không có gì để chạy lại; fix an toàn cho các module đó
  vì không đổi dữ liệu thực sự được lưu, chỉ đồng bộ bộ nhớ với những gì
  Hibernate vốn đã serialize.
- **Redis là hạ tầng mới hoàn toàn** (chưa có trong `docker-compose`/
  `pom.xml`) — cần xác nhận `data-ingestion.md` (cache `allowed_file_format`)
  có triển khai trước hay chưa để tránh 2 PR cùng thêm Redis gây conflict;
  nên làm Task 2 trong 1 lần dùng chung cho cả 2 feature.
- **Race điều kiện khi rollback tham số nhạy cảm:** luồng rollback tái dùng
  cơ chế `confirmationToken` của Story 3 nhưng với `toValue` lấy từ lịch sử
  — cần đảm bảo giá trị `toValue` gửi lên khớp đúng giá trị đã có trong
  audit (không cho set tuỳ ý một giá trị "rollback" chưa từng tồn tại)
  để giữ đúng ngữ nghĩa "khôi phục", nếu không sẽ nhầm với PATCH thường —
  cần review kỹ ở code review, chưa có AC nào của BA ép rõ ràng.
- **Đọc `audit_log` cho `history`** hiện chỉ có `AuditController` với
  `GET /audit` (không filter theo `resourceId`) — cần thêm query method mới
  ở `AuditLogRepository` (`findByResourceTypeAndResourceIdOrderByIdDesc`),
  rủi ro thấp nhưng cần kiểm tra index trên `(resource_type, resource_id)`
  nếu bảng audit lớn dần (chưa có migration index riêng — có thể cần thêm ở
  V7 hoặc migration riêng nếu performance kém khi audit_log phình to).
- **Tách `storage_quota.default.R1/R2/R3` thành 3 key riêng** (quyết định
  DEV ở mục 2) khác cách trình bày 1-dòng của bảng SA — cần đồng bộ lại với
  `personal-storage-quota.md` khi implement Task 9 để tránh 2 spec mô tả
  khác cấu trúc lưu trữ cho cùng tham số; nên xác nhận lại nếu
  `personal-storage-quota.md` đã có quyết định lưu trữ khác.
- **`last-write-wins` không có optimistic lock:** đủ cho MVP (vài chục
  tham số, sửa không thường xuyên) nhưng nếu 2 admin sửa đồng thời cùng
  key, người sau thắng mà không cảnh báo — rủi ro thấp, chấp nhận được theo
  NFR Scalability của SA ("số lượng tham số nhỏ").
