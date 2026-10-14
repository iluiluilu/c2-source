# Architecture: system-config

> File: `docs/01-sa/architecture/system-config.md` — Owner: SA
> Status: Approved

## 1. Bối cảnh / Vấn đề

- FR-01 (`docs/dac_ta_yeu_cau_chuc_nang_phi_chuc_nang.adoc` mục 2.13): Quản trị
  hệ thống (R3) cần xem/sửa tham số vận hành qua giao diện quản trị — ngưỡng
  cảnh báo tài nguyên, chính sách lưu trữ/thời hạn, hạn mức lưu trữ cá nhân
  theo vai trò (FR-14), lịch sao lưu (FR-34), tham số hàng đợi xử lý AI, định
  dạng tệp cho phép (FR-11), tham số kết nối nội bộ. Mọi thay đổi phải có hiệu
  lực ngay, được ghi nhật ký (FR-09), và có thể khôi phục giá trị trước.
- Hiện trạng: các tham số đang nằm rải rác dạng `@ConfigurationProperties`
  tĩnh đọc từ `application.yml` (`StorageProperties`, `ScanningProperties`,
  `PersonalProperties`, `KeycloakProperties` trong
  `source_v2/backend/src/main/java/com/aismartstore/backend/config/` và các
  package tương ứng) — không sửa được qua UI, không có audit/rollback.
- In-scope: cơ chế lưu tham số cấu hình nghiệp vụ (business-level) có thể sửa
  qua UI, có audit + khôi phục; API cho R3 CRUD tham số.
- Out-of-scope: cấu hình hạ tầng cụ thể (mạng, phần cứng, công nghệ lưu trữ)
  — thuộc `kien_truc_he_thong.adoc`.

## 2. Ràng buộc (Constraints)

- Hệ thống chạy trong mạng cách ly (air-gapped) — không dùng dịch vụ config
  tập trung bên ngoài (Consul, cloud config service...).
- Phải tương thích với stack hiện có: Spring Boot + PostgreSQL + Flyway
  migration (`source_v2/backend/src/main/resources/db/migration/`).
- Một số tham số "nhạy cảm" cần xác nhận hai bước trước khi áp dụng — danh
  sách cụ thể xem Quyết định 3.
- Không được phá vỡ các module đang đọc `*Properties` tĩnh hiện có.

## 3. NFR (Non-Functional Requirements)

| Loại | Yêu cầu |
|---|---|
| Performance | Thay đổi tham số có hiệu lực ngay, không cần khởi động lại backend |
| Security | Chỉ R3 được sửa; tham số nhạy cảm cần xác nhận 2 bước |
| Scalability | Số lượng tham số nhỏ (vài chục mục), không cần tối ưu đặc biệt |
| Availability | Nếu bảng config lỗi/không đọc được, hệ thống phải fallback về giá trị mặc định tĩnh, không được sập |
| Khác | Auditability — mọi thay đổi có lịch sử, khôi phục được giá trị trước (liên kết FR-09) |

## 4. Phương án & Quyết định (ADR)

### Quyết định 1: Cơ chế lưu tham số — DB-backed vs static YAML

- **Ngữ cảnh:** phần lớn tham số hiện là `@ConfigurationProperties` tĩnh,
  nhưng FR-01 yêu cầu sửa qua UI + audit + khôi phục.
- **Các phương án xem xét:**
  - A) Giữ nguyên toàn bộ dạng YAML tĩnh, chỉ thêm giao diện đọc (read-only).
  - B) Chuyển toàn bộ sang bảng `system_config` (key/value, có version),
    override mọi `*Properties` tại runtime.
  - C) Hybrid: tham số hạ tầng (port, DB connection...) giữ YAML tĩnh (cần
    restart an toàn); tham số nghiệp vụ (ngưỡng, hạn mức, định dạng, lịch sao
    lưu...) chuyển sang bảng `system_config` đọc động.
- **Quyết định:** C — đã chốt (hybrid).
- **Lý do:** tham số hạ tầng sai có thể làm crash service, không nên cho sửa
  runtime qua UI; tham số nghiệp vụ cần đúng yêu cầu FR-01 (sửa động, có audit
  + rollback) mà không đụng tới phần hạ tầng nhạy cảm.
- **Hệ quả (trade-off):** duy trì song song 2 cơ chế cấu hình (YAML tĩnh +
  bảng DB động); cần tài liệu rõ ràng phân loại tham số nào thuộc nhóm nào.

### Quyết định 2: Cơ chế lưu lịch sử để khôi phục

- **Ngữ cảnh:** FR-01 yêu cầu "lưu giá trị cũ để khôi phục".
- **Các phương án xem xét:**
  - A) Tận dụng `audit_log` hiện có (FR-09, hash-chained, append-only) làm
    nguồn khôi phục — audit event ghi kèm `old_value`/`new_value` dạng
    structured.
  - B) Tạo bảng `system_config_history` riêng.
- **Quyết định:** A — đã chốt. Tái sử dụng `audit_log`.
- **Lý do:** tránh trùng lặp cơ chế lưu lịch sử; `audit_log` đã có sẵn
  hash-chain đảm bảo toàn vẹn (NFR-05), đúng tinh thần "mọi thay đổi phải
  được ghi nhật ký (xem FR-09)" nêu trong Quy tắc nghiệp vụ FR-01.
- **Hệ quả (trade-off):** cần rà soát `AuditLog` entity hiện tại
  (`source_v2/backend/.../audit/AuditLog.java`) có đủ trường lưu giá trị cũ/
  mới dạng truy vấn lại được hay chưa; nếu chưa, cần bổ sung.

### Quyết định 3: Danh mục tham số MVP, mức nhạy cảm và giá trị mặc định

- **Ngữ cảnh:** rà soát toàn bộ `docs/dac_ta_yeu_cau_chuc_nang_phi_chuc_nang.adoc`
  (Mô tả chi tiết FR-01 và các FR có ghi "do quản trị cấu hình") kết hợp các
  quyết định đã chốt ở `auth-session.md`, `data-ingestion.md`,
  `personal-storage-quota.md`, `account-lifecycle.md` để chốt một danh mục
  tham số MVP — đủ dùng, không thêm tham số chưa có nhu cầu thực tế.
- **Loại trừ khỏi `system-config` (đã chốt ở nơi khác, KHÔNG lặp lại ở đây):**
  timeout phiên/token và ngưỡng khoá đăng nhập sai — dù SRS FR-03 có nhắc
  "theo cấu hình FR-01", `auth-session.md` đã chốt các tham số này cấu hình
  trực tiếp trong Keycloak, không đưa vào `system_config`.
- **Danh mục tham số nghiệp vụ (bảng `system_config`, trừ dòng có ghi chú
  "bảng riêng"):**

  | Key | Mô tả | FR liên quan | Giá trị mặc định đề xuất | Nhạy cảm (2 bước)? |
  |---|---|---|---|---|
  | `retention.trash_retention_days` | Số ngày giữ dữ liệu trong thùng rác trước khi xoá cứng | FR-26/27 | 365 ngày | **Có** |
  | `storage_quota.default.R1/R2/R3` | Hạn mức lưu trữ cá nhân mặc định theo vai trò | FR-14 | 20 GB / 50 GB / 20 GB (đã chốt) | Không |
  | `ingest.max_files_per_batch` | Số tệp tối đa mỗi lượt tải theo thư mục | FR-11 | 500 (đã chốt) | Không |
  | `ingest.max_batch_size_bytes` | Tổng dung lượng tối đa mỗi lượt tải | FR-11 | 5 GB (đã chốt) | Không |
  | `allowed_file_format` (**bảng riêng**, không phải key-value) | Danh mục định dạng tệp cho phép | FR-11/12 | Danh sách seed đã chốt ở `data-ingestion.md` | Không |
  | `ocr_stt.auto_confirm_hours` | Thời hạn tự động xác nhận kết quả OCR/STT | FR-18 | 24 giờ (theo đúng SRS) | Không |
  | `warehouse.storage_warning_threshold_percent` / `warehouse.storage_block_threshold_percent` | Ngưỡng cảnh báo / chặn nạp mới theo dung lượng kho | FR-13 | 85% (cảnh báo) / 95% (chặn) — đã chốt ở `warehouse-structure.md` (Quyết định 3) | Không |
  | `ai_queue.concurrency`, `ai_queue.batch_size` | Tham số hàng đợi xử lý AI (OCR/STT) | FR-17/19 | Chưa chốt số — để DEV-spec đề xuất theo năng lực GPU thực tế | Không |
  | `backup.schedule_cron` | Lịch sao lưu định kỳ | FR-34 (ngoài phạm vi nhóm SA hiện tại) | Đề xuất hàng ngày 02:00 | **Có** |

- **Tiêu chí xác định "nhạy cảm":** chỉ đánh dấu 2 bước cho tham số mà đặt
  sai giá trị có thể gây **mất dữ liệu không thể khôi phục** hoặc **gián
  đoạn khả năng khôi phục sự cố** — cụ thể là chính sách xoá cứng
  (`retention.trash_retention_days`) và lịch sao lưu (`backup.schedule_cron`).
  Các tham số còn lại chỉ ảnh hưởng giới hạn/hiệu năng, dễ sửa lại ngay nếu
  cấu hình sai → không cần xác nhận 2 bước, giữ thao tác gọn cho R3.
- **Hệ quả:** danh mục trên là MVP — DEV có thể bổ sung tham số mới vào
  bảng `system_config` sau này mà không cần đổi kiến trúc (schema key/value
  đã tổng quát), miễn tuân thủ đúng phân loại hạ tầng/nghiệp vụ ở Quyết
  định 1.

### Quyết định 4: Cơ chế cache cho toàn bộ `system_config` — Redis

- **Ngữ cảnh:** ban đầu (mục Rủi ro) chỉ nêu chung "TTL ngắn hoặc
  event-based refresh" mà chưa chốt công nghệ cụ thể. Khi xử lý cache cho
  `allowed_file_format` (một tham số cùng nhóm nghiệp vụ, xem
  `data-ingestion.md`), đã quyết định dùng Redis thay vì cache in-process
  (Caffeine). Quyết định này đồng bộ hoá: **toàn bộ cache của
  `system_config`** (không riêng allowlist) đều dùng Redis, tránh hai cơ
  chế cache khác nhau cho các tham số có cùng bản chất "R3 sửa xong áp
  dụng ngay".
- **Các phương án xem xét:**
  - A) Cache in-process (Caffeine/Spring Cache) + event-based refresh + TTL
    ngắn dự phòng — phương án ban đầu.
  - B) Redis (shared cache) — mọi instance backend đọc chung một giá trị.
- **Quyết định:** B — Redis cho toàn bộ tham số `system_config` lẫn bảng
  riêng `allowed_file_format`.
- **Lý do:** nhất quán kiến trúc (một cơ chế cache duy nhất cho toàn bộ
  tham số cấu hình do R3 quản lý, thay vì DEV phải cài đặt 2 kiểu cache
  song song); giải quyết luôn giới hạn của phương án A đã nêu trong rủi ro
  trước đây (cache in-process chỉ đúng khi backend chạy 1 instance, không
  lan sang instance khác nếu scale ngang) — Redis là shared cache nên
  không có vấn đề này.
- **Hệ quả (trade-off):** thêm Redis vào hạ tầng — **chưa có** trong
  `docker-compose.yml`/`docker-compose.dev.yml` (gốc source_v2) và
  `pom.xml` (`backend/`) hiện tại (đã kiểm tra). Cần bổ sung: (1) service
  `redis` trong docker-compose, (2) dependency
  `spring-boot-starter-data-redis` trong `pom.xml`. Đổi tham số vẫn ghi
  DB trước (nguồn sự thật), sau đó ghi đồng bộ vào Redis trong cùng
  request (không còn phân biệt "TTL ngắn" hay "event-based" — Redis ghi
  đồng bộ đã bao hàm cả hai mục tiêu đó); vẫn đặt TTL trên key Redis làm
  lớp dự phòng nếu lỡ một lần ghi.

## 5. Tech stack đề xuất

- Backend: Spring Boot (đã có) + bảng `system_config` mới trên PostgreSQL,
  migration Flyway kế tiếp (V7).
- Frontend: trang "Admin > Cấu hình hệ thống" trong `source_v2/web`
  (Next.js, tận dụng `AdminTabs.tsx` đã có).
- Data store: PostgreSQL (đã có, nguồn sự thật); **Redis** (cache, mới —
  xem Quyết định 4).
- Infra/Deploy: bổ sung service Redis (chưa có trong
  `docker-compose.yml`/`pom.xml` hiện tại).

## 6. Rủi ro

| Rủi ro | Mức độ | Giảm thiểu |
|---|---|---|
| Giá trị mặc định đề xuất ở Quyết định 3 (retention 365 ngày, ngưỡng cảnh báo 85%, lịch sao lưu 02:00) chưa được khách hàng xác nhận chính thức | Thấp | Đánh dấu rõ trong BA-story là giá trị mặc định có thể đổi qua UI, không hard-code, nên sai lệch ban đầu không chặn go-live |
| Tham số thay đổi không đồng bộ real-time tới các module đang cache giá trị cũ (vd. hạn mức FR-14) | Thấp | Đã giảm nhờ Quyết định 4 (Redis, ghi đồng bộ, shared giữa các instance) |
| Duy trì song song 2 cơ chế cấu hình (YAML + DB) gây nhầm lẫn khi vận hành | Thấp | Tài liệu vận hành phân loại rõ tham số nào sửa ở đâu |

## 7. Câu hỏi mở

_Đã chốt cả 3 câu hỏi mở trước đó (xem Quyết định 3 — bảng danh mục tham
số MVP):_
- ~~Danh sách tham số MVP cụ thể?~~ → **9 tham số (8 key trong
  `system_config` + 1 bảng riêng `allowed_file_format`), xem bảng ở Quyết
  định 3.**
- ~~Tham số nào "nhạy cảm", cần xác nhận hai bước?~~ → **Chỉ 2:
  `retention.trash_retention_days` và `backup.schedule_cron`** (tiêu chí:
  sai giá trị có thể gây mất dữ liệu không thể khôi phục). Các tham số còn
  lại không cần 2 bước, giữ thao tác gọn.
- ~~Giá trị mặc định cụ thể cho từng tham số?~~ → **Đã đề xuất đầy đủ trong
  bảng ở Quyết định 3** (một số đã chốt ở file khác — hạn mức, giới hạn
  upload; một số SA tự đề xuất — retention, ngưỡng cảnh báo kho, lịch sao
  lưu — đều sửa được qua UI nên không chặn tiến độ nếu khách hàng muốn đổi
  sau).

Không còn câu hỏi mở nào cho system-config ở bước SA.
