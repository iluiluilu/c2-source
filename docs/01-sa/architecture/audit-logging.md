# Architecture: audit-logging

> File: `docs/01-sa/architecture/audit-logging.md` — Owner: SA
> Status: Approved

## 1. Bối cảnh / Vấn đề

- FR-09 (ghi nhật ký hoạt động) đã có **hạ tầng tối thiểu** trong repo:
  `backend/src/main/java/com/aismartstore/backend/audit/` (`AuditService`,
  `AuditInterceptor`, `AuditController`, `AuditLog`) + bảng `audit_log`
  (`db/migration/V1__init_audit_log.sql`): append-only, hash-chained
  (SHA-256, `prev_hash` → `hash`), trigger chặn UPDATE/DELETE, endpoint
  `GET /audit` + `GET /audit/verify` giới hạn cho R3.
- Khoảng trống hiện tại (đã kiểm chứng bằng đọc code):
  1. `AuditInterceptor` chỉ ghi sự kiện thô `http.POST|PUT|PATCH|DELETE`,
     không có `resource_type`/`resource_id` → không truy vết được "ai sửa
     tài liệu nào".
  2. 14 service đã tự gọi `AuditService` → **ghi trùng** với interceptor cho
     cùng một request.
  3. `AuditService.record()` là `@Transactional` + `pg_advisory_xact_lock`
     toàn cục → nối vào transaction nghiệp vụ, **serialize toàn hệ thống**.
  4. `recordAsync()` fire-and-forget → mất sự kiện nếu process chết.
  5. `verifyChain()` `findAllAscending()` nạp **toàn bộ** bảng vào RAM.
  6. Chưa có FR-10 (tìm kiếm/lọc nhật ký, quyền đọc cho R2), chưa có
     retention, chưa có chuẩn hoá tên sự kiện, chưa có quy tắc PII.
- **In-scope:** taxonomy sự kiện chuẩn hoá; điểm chèn instrumentation; mô
  hình ghi (đồng bộ/bất đồng bộ); lưu trữ & truy vấn (FR-10); tamper-evidence
  ở quy mô lớn; redaction PII; retention/archive; nguồn sự kiện ngoài
  (Keycloak login/lockout qua RabbitMQ — đã chốt ở `auth-session.md`).
- **Out-of-scope:** SIEM/alerting realtime, phân tích hành vi bất thường,
  báo cáo tuân thủ định dạng cố định, xuất bằng chứng có chữ ký số cho bên
  thứ ba, log ứng dụng (`slf4j`) và metric — khác mục đích với audit trail.
- **Người đọc log:** R3 (quản trị hệ thống) — toàn quyền đọc + verify; R2
  (lãnh đạo) — đọc theo phạm vi nghiệp vụ (FR-10); R1 — không.

## 2. Ràng buộc (Constraints)

- Bám stack sẵn có: Spring Boot 3 + PostgreSQL 16 + Flyway + Redis, Keycloak
  24 (nguồn định danh), RabbitMQ 3.13, OpenSearch 2.17, SeaweedFS (S3),
  Next.js 16 + NextAuth. `pom.xml` hiện **chưa có** client OpenSearch/AMQP —
  thêm dependency là chi phí thật, không "miễn phí vì compose đã có service".
- `audit_log` là bảng append-only cấp DB (trigger) — mọi thiết kế phải sống
  chung với việc **không được UPDATE**, kể cả UPDATE "vô hại" của Hibernate
  (đã từng gây bug, xem javadoc `normalizeMetadata`).
- NFR-07 tách biệt vai trò: R3 không được đứng trên route dữ liệu nghiệp vụ;
  do đó UI đọc log không được kèm nội dung tài liệu.
- Chuỗi hash chỉ có **một tail** → mọi cơ chế ghi song song phải giải quyết
  vấn đề tranh chấp tail.
- Triển khai on-prem, có thể chạy nhiều instance backend (xem
  `system-config.md`, Quyết định cache) → không giả định single instance.

## 3. NFR (Non-Functional Requirements)


| Loại        | Yêu cầu                                                                                                                                                                                                                                                                        |
| ------------ | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Performance  | Ghi audit thêm ≤ 10 ms p95 vào request path;**không** giữ lock toàn cục trong transaction nghiệp vụ. Truy vấn/lọc FR-10 trả kết quả ≤ 2 s p95 trên 12 tháng dữ liệu, có phân trang. `GET /audit/verify` chạy theo khoảng, ≤ 30 s cho 1 tháng dữ liệu. |
| Security     | Bất biến (append-only + trigger + hash chain); chỉ R3/R2 đọc theo phân quyền;**không** lưu mật khẩu, token, nội dung file, request body đầy đủ; metadata theo allowlist (deny-by-default); mọi truy cập vào chính nhật ký cũng được ghi lại.          |
| Scalability  | Giả định 20k–50k sự kiện/ngày (≈ 18M/năm) → partition theo tháng; thiết kế phải chịu được tăng 10× mà không đổi kiến trúc lưu trữ.                                                                                                                   |
| Availability | Lỗi ghi audit của**sự kiện nghiệp vụ** làm fail giao dịch (ưu tiên toàn vẹn nhật ký); lỗi ghi của sự kiện hạ tầng/ngoài (Keycloak) chỉ retry, không chặn người dùng. Mất tối đa 0 sự kiện nghiệp vụ sau khi giao dịch đã commit.            |
| Khác        | Tính giải thích: mỗi bản ghi trả lời được "ai / lúc nào / hành động gì / trên tài nguyên nào / kết quả ra sao / từ IP nào". Retention mặc định 24 tháng (chờ xác nhận), archive trước khi xoá partition.                                       |

## 4. Phương án & Quyết định (ADR)

### Quyết định 1: Mô hình ghi — đồng bộ trong transaction, tách hash-chain khỏi request path

- **Ngữ cảnh:** hiện `record()` vừa ghi vừa tính chain dưới advisory lock
  toàn cục, còn `recordAsync()` thì có thể mất sự kiện. Cả hai đều không đạt
  NFR ở trên.
- **Các phương án xem xét:**
  - A) Giữ nguyên `@Async` fire-and-forget + advisory lock mỗi lần ghi.
  - B) INSERT audit **đồng bộ trong chính transaction nghiệp vụ** (không lock,
    không tính hash), một job `@Scheduled` ("sealer") chain-hash các dòng
    chưa seal theo `id` tăng dần.
  - C) Transactional outbox trong Postgres + consumer RabbitMQ ghi `audit_log`.
- **Quyết định:** **B** cho sự kiện nghiệp vụ nội bộ; **C** chỉ dùng cho
  nguồn ngoài (Keycloak Admin Events → RabbitMQ, đã chốt ở `auth-session.md`).
- **Lý do:** B cho atomicity thật (audit và dữ liệu cùng commit/rollback → không
  bao giờ lệch), bỏ được lock toàn cục khỏi hot path, và vẫn giữ tamper-evidence
  vì chain vẫn được tính đầy đủ, chỉ lệch pha vài giây. C thêm một hop và một
  dependency AMQP vào mọi thao tác ghi — không xứng với khối lượng dự kiến.
- **Hệ quả (trade-off):** cửa sổ vài giây các dòng "chưa seal" chưa được bảo
  vệ bởi chain (bù bằng trigger append-only + quyền DB); cần cột trạng thái
  seal + xử lý gap khi transaction rollback làm hụt `id` (BIGSERIAL không
  liên tục) → chain phải chain theo **thứ tự `id` thực tế đọc được**, không
  giả định liên tục; lỗi ghi audit sẽ làm fail nghiệp vụ (chấp nhận, có
  chủ đích).

### Quyết định 2: Nơi lưu trữ & truy vấn (FR-10)

- **Ngữ cảnh:** FR-10 cần lọc theo thời gian, người dùng, loại hành động,
  tài nguyên; dữ liệu tích luỹ nhiều năm.
- **Các phương án xem xét:**
  - A) Chỉ PostgreSQL: partition `audit_log` theo tháng (declarative
    partitioning) + index tổ hợp `(created_at)`, `(actor, created_at)`,
    `(resource_type, resource_id, created_at)`, GIN trên `metadata`.
  - B) Postgres là system of record + đẩy sang OpenSearch để tìm kiếm.
  - C) Chuyển hẳn audit trail sang OpenSearch.
- **Quyết định:** **A**, kèm ngưỡng chuyển sang B: khi > 100M dòng hoặc khi
  yêu cầu full-text/aggregation vượt khả năng SQL.
- **Lý do:** khối lượng dự kiến nằm thoải mái trong tầm Postgres; A giữ
  **một** system of record nên hash chain vẫn là bằng chứng duy nhất, không
  phải đồng bộ hai kho. C phá vỡ tamper-evidence (OpenSearch cho phép sửa/
  xoá document) và phá vỡ atomicity ở Quyết định 1.
- **Hệ quả (trade-off):** tìm kiếm full-text trên `metadata` yếu hơn
  OpenSearch; truy vấn ad-hoc phức tạp phải viết SQL; migration sang bảng
  partitioned cần một Flyway migration có downtime ngắn (bảng append-only,
  không sửa dữ liệu cũ nên rủi ro thấp).

### Quyết định 3: Điểm chèn instrumentation

- **Ngữ cảnh:** đang tồn tại song song hai cơ chế (global interceptor + gọi
  thủ công ở 14 service) gây trùng lặp và thiếu ngữ nghĩa.
- **Các phương án xem xét:**
  - A) Chỉ `HandlerInterceptor` toàn cục theo HTTP verb (hiện tại).
  - B) Gọi `AuditService` thủ công ở tầng service, kèm annotation `@Audited`
    + AOP cho các trường hợp lặp lại; interceptor thu hẹp thành **fallback**
      chỉ ghi khi request bị từ chối (401/403) hoặc lỗi 5xx trên route chưa
      được instrument.
  - C) DB trigger / CDC (Debezium) trên các bảng nghiệp vụ.
- **Quyết định:** **B**.
- **Lý do:** chỉ tầng service mới biết `resource_type`/`resource_id`/giá trị
  cũ-mới và mới nằm trong đúng transaction (điều kiện của Quyết định 1). C bị
  loại: tầng DB không biết actor (Keycloak `sub`), không biết ý định nghiệp vụ,
  và thêm Debezium là một hệ thống mới. A giữ lại đúng phần nó làm tốt: bắt
  các truy cập bị từ chối (phục vụ FR-09 cho `rbac-group-scope.md`).
- **Hệ quả (trade-off):** phụ thuộc kỷ luật lập trình viên — sót chỗ gọi là
  sót audit; cần checklist review + test bắt buộc "mỗi endpoint mutating có
  đúng 1 audit event". Phải rà lại 14 service đang gọi để loại ghi trùng.

### Quyết định 4: Schema sự kiện chuẩn hoá + xử lý PII

- **Ngữ cảnh:** `action` hiện là chuỗi tự do (`http.POST`, tên miền tuỳ
  service), `metadata` JSONB không ràng buộc → nguy cơ rò rỉ dữ liệu nhạy cảm
  và không lọc được theo loại hành động.
- **Các phương án xem xét:**
  - A) Giữ tự do, quy ước bằng tài liệu.
  - B) Taxonomy bắt buộc `action = <domain>.<verb>` (vd `document.upload`,
    `account.reset_password`, `system_config.update`) khai báo trong một
    catalog hằng số + `metadata` theo **allowlist field/deny-by-default** +
    lớp redaction (mask token/mật khẩu, cắt chuỗi > 1KB).
  - C) Lưu toàn bộ request/response body để tối đa khả năng điều tra.
- **Quyết định:** **B**.
- **Lý do:** FR-10 cần lọc theo "loại hành động" → `action` phải là tập hữu
  hạn; allowlist là cách duy nhất đảm bảo PII/secret không lọt vào một bảng
  **không xoá được** (rủi ro pháp lý cao hơn nhiều so với việc thiếu chi
  tiết). C bị loại thẳng vì mâu thuẫn với NFR Security và với append-only.
- **Hệ quả (trade-off):** phải bảo trì catalog sự kiện và cập nhật khi thêm
  feature; một số sự cố sẽ thiếu chi tiết để điều tra → bù bằng log ứng dụng
  (có retention ngắn) chứ không bằng audit trail.

### Quyết định 5: Tamper-evidence ở quy mô lớn + retention

- **Ngữ cảnh:** `verifyChain()` nạp toàn bộ bảng → OOM khi dữ liệu lớn; xoá
  dữ liệu hết hạn bằng DELETE bị trigger chặn.
- **Các phương án xem xét:**
  - A) Giữ verify toàn bộ, chấp nhận giới hạn.
  - B) Checkpoint: mỗi N dòng (hoặc mỗi ngày) lưu một `audit_checkpoint`
    (root hash + khoảng id); verify chạy theo khoảng/stream, xoá hết hạn bằng
    **DROP PARTITION** sau khi đã export ra SeaweedFS (S3) kèm checkpoint hash.
  - C) Ký số định kỳ ra hệ thống ngoài (TSA/blockchain nội bộ).
- **Quyết định:** **B**; C để ngỏ nếu có yêu cầu tuân thủ bắt buộc.
- **Lý do:** B giải quyết cả verify lẫn retention bằng một cơ chế; DROP
  PARTITION không kích hoạt trigger row-level nên hợp lệ về kỹ thuật, và bản
  thân thao tác xoá cũng được ghi lại như một audit event.
- **Hệ quả (trade-off):** thêm bảng checkpoint và job định kỳ; sau khi drop
  partition, verify chỉ còn dựa vào checkpoint + file archive → cần quy trình
  bảo vệ file archive (bucket riêng, quyền ghi một chiều).

## 5. Tech stack đề xuất

- Backend: Spring Boot 3 (Java) — module `audit/` mở rộng; Spring `@Scheduled`
  cho sealer/checkpoint/retention job; Spring AOP (`@Audited`) cho
  instrumentation lặp lại; Spring Security + Keycloak JWT cho actor & phân
  quyền đọc; RabbitMQ (spring-boot-starter-amqp — **cần thêm dependency**)
  chỉ cho luồng Keycloak events.
- Frontend: Next.js 16 + NextAuth — thêm trang `web/src/app/admin/audit/`
  theo đúng khuôn các trang admin hiện có (`admin/system-config`,
  `admin/accounts`): bộ lọc thời gian/actor/action/resource + phân trang +
  nút verify (chỉ R3).
- Data store: PostgreSQL 16 — `audit_log` partition theo tháng + bảng
  `audit_checkpoint`; Flyway migration mới (tiếp sau `V13`); SeaweedFS (S3)
  cho archive partition hết hạn. OpenSearch **chưa dùng** ở giai đoạn này.
- Infra/Deploy: Docker Compose (gốc source_v2); vai trò DB riêng chỉ có
  quyền INSERT/SELECT trên `audit_log` cho application user.

## 6. Rủi ro


| Rủi ro                                                                                                        | Mức độ   | Giảm thiểu                                                                                  |
| -------------------------------------------------------------------------------------------------------------- | ----------- | --------------------------------------------------------------------------------------------- |
| Advisory lock toàn cục hiện tại nằm trong transaction nghiệp vụ → thắt cổ chai/deadlock khi tải cao | Cao         | Quyết định 1 (bỏ lock khỏi write path, sealer job)                                       |
| Ghi trùng sự kiện (interceptor + gọi thủ công) làm nhật ký nhiễu, khó đối soát                   | Cao         | Quyết định 3: thu hẹp interceptor thành fallback; rà soát 14 service                   |
| Lọt PII/secret vào bảng không xoá được                                                                 | Cao         | Allowlist metadata, lớp redaction, review bắt buộc khi thêm event mới                    |
| Sự kiện bị mất do`recordAsync` fire-and-forget khi process chết                                           | Trung bình | Ghi đồng bộ cùng transaction cho sự kiện nghiệp vụ                                    |
| `verifyChain()` OOM / timeout khi dữ liệu lớn                                                               | Trung bình | Verify theo khoảng + checkpoint (Quyết định 5)                                            |
| Bảng phình không kiểm soát, làm chậm mọi truy vấn                                                     | Trung bình | Partition theo tháng, retention + archive, index có chủ đích                             |
| Sót instrumentation ở endpoint mới                                                                          | Trung bình | Test bắt buộc cho mỗi endpoint mutating; fallback interceptor bắt phần còn lại         |
| Migration`audit_log` → bảng partitioned trên môi trường đã có dữ liệu                               | Thấp       | Bảng append-only nên copy an toàn; verify chain trước/sau migration                      |
| Đồng hồ lệch giữa instance làm sai thứ tự thời gian                                                   | Thấp       | Dùng`id` (BIGSERIAL) làm thứ tự chuẩn của chain, `created_at` chỉ để hiển thị/lọc |

## 7. Câu hỏi mở

Tất cả câu hỏi mở đã được người dùng chốt (2026-08-16):

- [X]  **Tuân thủ:** không có yêu cầu pháp lý/chuẩn bên thứ ba bắt buộc — hash
  chain nội bộ là đủ. Không cần triển khai phương án C (TSA/ký số) ở
  Quyết định 5.
- [X]  **Retention:** 24 tháng trong Postgres (partition), sau đó archive sang
  SeaweedFS/S3 kèm checkpoint hash rồi `DROP PARTITION` — như Quyết định 5
  đã đề xuất.
- [X]  **Quyền đọc:** R3 toàn quyền; R2 (lãnh đạo) chỉ đọc theo phạm vi nhóm
  mình quản lý (khớp NFR-07 và `rbac-group-scope.md`), **không** thêm vai
  trò "kiểm toán viên" riêng ở giai đoạn này.
- [X]  **Audit hành vi đọc:** chỉ audit **tải xuống** tài liệu, không audit
  hành vi xem/preview — giữ khối lượng sự kiện trong tầm dự kiến, không
  cần đánh giá lại Quyết định 2 ở giai đoạn này.
- [X]  **Khối lượng:** giữ giả định 20k–50k sự kiện/ngày như NFR mục 3 đã nêu.
- [X]  **Xuất báo cáo:** chưa cần CSV/PDF ở giai đoạn này — chỉ xem qua UI lọc
  + phân trang (mục 5, trang `admin/audit`).
- [X]  **Keycloak events:** dùng consumer RabbitMQ, độ trễ gần thời gian thực
  (vài giây) — đúng như Quyết định 1 đã chốt (nguồn ngoài dùng outbox/
  consumer), liên kết `auth-session.md` AC-4.
