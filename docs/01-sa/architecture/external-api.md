# Architecture: external-api

> File: `docs/01-sa/architecture/external-api.md` — Owner: SA
> Status: Draft

## 1. Bối cảnh / Vấn đề

- **FR-32** — Cung cấp API (REST/JSON) cho hệ thống ngoài (R4) truy xuất, khai
  thác dữ liệu **theo phạm vi được cấp**; mọi lời gọi phải ghi nhật ký.
- **FR-33** — R3 cấp/thu hồi khóa API, gán phạm vi, đặt **giới hạn tần suất**,
  giám sát thống kê sử dụng; **thu hồi khóa có hiệu lực tức thì**.
- R4 là tác nhân **phi tương tác**: không đăng nhập qua trình duyệt
  (SRS dòng 198), chỉ dùng credential máy-máy.
- Hiện trạng đã kiểm chứng bằng code:
  - Backend Spring Boot resource server, JWT Keycloak, `SecurityConfig`
    ánh xạ `realm_access.roles` → `ROLE_<code>` + `PERM_<code>`
    (đọc `role_permission`, cache Caffeine 60s).
  - `AccountStatusFilter` đã có **tiền lệ chặn theo trạng thái ngay trong
    request path** (Redis cache + fallback DB) — dùng lại được cho "thu hồi
    tức thì".
  - `KeycloakAdminService` đã có (tạo user, reset password qua Admin API).
  - Redis đã có sẵn (`spring-boot-starter-data-redis`, `StringRedisTemplate`,
    `application.yml` — "Redis is a cache only, Postgres là source of truth").
  - **Chưa có** bất kỳ code nào về `ApiKey` / rate-limit / API ngoài
    (grep xác nhận: 0 kết quả trong `backend/src/main/java`).
  - **Chưa có** reverse proxy / API gateway trong `docker-compose.yml`
    (chỉ Keycloak, Postgres, SeaweedFS, ClamAV, OpenSearch, RabbitMQ,
    Redis, backend, web) → mọi phương án "chặn ở gateway" đều là **thêm một
    thành phần mới**, không phải tái dùng.

**In-scope**

- Mô hình xác thực & định danh cho R4 (khóa API).
- Mô hình phân quyền/phạm vi dữ liệu của một khóa (tái dùng
  `rbac-group-scope.md`, không xây mô hình song song).
- Bề mặt dữ liệu được expose qua API ngoài (v1) và cách tách khỏi API nội bộ.
- Rate-limit & quota trên Redis.
- Giám sát/thống kê sử dụng (FR-33) và audit riêng cho actor máy
  (`audit-logging.md`).

**Out-of-scope**

- Ghi/sửa/xóa dữ liệu qua API ngoài (FR-32 chỉ nói "truy xuất, khai thác");
  nạp tài liệu vẫn đi theo FR-11–16 (`data-ingestion.md`,
  `chunked-file-upload.md`).
- UI chi tiết màn hình quản lý khóa của R3 (BA/DEV).
- Webhook/push từ hệ thống ra ngoài (không có FR).
- Federation/SSO với IdP của hệ thống ngoài.
- Không quyết định lại: `rbac-group-scope.md`, `rbac-permission-editing.md`,
  `audit-logging.md`, `auth-session.md`, `search-fulltext.md`,
  `search-semantic.md`, `ai-search-infra.md` — **dẫn chiếu**.

## 2. Ràng buộc (Constraints)

- **Mạng cách ly tuyệt đối** (NFR-04/NFR-11/NFR-16, xem
  `digitization-pipeline.md` mục 2): "hệ thống ngoài" = phần mềm nghiệp vụ
  khác **trong cùng mạng nội bộ/VLAN đã được duyệt**, không có lối ra
  Internet. API ngoài **không** được publish ra ngoài biên air-gap; expose
  trên cùng cổng backend nhưng ở prefix riêng, giới hạn nguồn gọi bằng
  allowlist IP/CIDR ở tầng hạ tầng (firewall/VLAN) — **giả định của SA**,
  cần xác nhận (mục 7).
- Keycloak (realm `aismartstore`) là IdP duy nhất đã chốt ở `auth-session.md`
  — nguyên tắc: "cái gì Keycloak đã hỗ trợ thì cấu hình trong Keycloak, không
  xây lớp song song".
- Phạm vi dữ liệu **chỉ** dựa trên nhóm/thành viên/chia sẻ
  (`rbac-group-scope.md` Quyết định 3, filter ở tầng query, fail-closed) —
  cấm phát minh cơ chế scope thứ hai cho API.
- Catalog RBAC hiện là **đóng** (3 vai trò × 15 permission,
  `rbac-permission-editing.md`) → thêm R4 là **mở rộng có kiểm soát** catalog
  (SRS ma trận mục 3 vốn đã có cột R4), không phải mở RBAC động.
- Redis là **cache**, Postgres là nguồn sự thật; các module hiện tại đều
  fallback-to-DB khi Redis lỗi → thiết kế rate-limit phải nêu rõ hành vi khi
  Redis chết (không được im lặng fail-open).
- Backend có thể chạy **nhiều instance** (`system-config.md`) → bộ đếm
  rate-limit phải dùng chung, không in-memory.
- GPU dùng chung, chỉ 1 máy AI 96GB (`ai-search-infra.md`) → tải từ hệ thống
  ngoài không được cạnh tranh tài nguyên AI với người dùng nội bộ.
- Không thêm thành phần hạ tầng mới nếu tránh được (mọi image phải đóng gói
  offline).

## 3. NFR (Non-Functional Requirements)

Số `[ĐX]` là đề xuất của SA (SRS chỉ nêu định tính); chốt lại sau khi đo tải.

| Loại | Yêu cầu |
|---|---|
| Performance | `[ĐX]` Overhead của lớp API ngoài (xác thực + kiểm scope + rate-limit + audit) ≤ **15 ms p95** ngoài chi phí truy vấn nghiệp vụ. Kiểm rate-limit trên Redis ≤ **3 ms p95** (1 round-trip Lua duy nhất). Endpoint đọc metadata p95 ≤ **1 s**; endpoint tìm kiếm kế thừa `search-fulltext.md` (p95 ≤ 1 s, p99 ≤ 2 s) |
| Performance (quota) | `[ĐX]` Mặc định mỗi khóa: **60 req/phút** (burst 120), **20 req/phút** cho `/search`, **10 req/phút** cho tải file, **50.000 req/ngày**, tối đa **4 request đồng thời**/khóa, `page_size` ≤ 100. Mọi ngưỡng cấu hình được **theo từng khóa** bởi R3 (FR-33) |
| Security | Không lưu secret dạng plaintext: **Keycloak giữ client secret** (Quyết định 1); nếu buộc phải có API key opaque (fallback) thì DB chỉ lưu **hash SHA-256 + salt** cùng 6 ký tự đầu để hiển thị, plaintext chỉ hiện **một lần** lúc cấp. Bắt buộc TLS. Thu hồi có hiệu lực **≤ 60 s** (chi tiết Quyết định 1). Fail-closed ở mọi tầng (thiếu scope/lỗi Redis/lỗi filter → từ chối). Không có endpoint ghi. Không expose kho cá nhân, thùng rác, audit log, cấu hình hệ thống, dữ liệu chưa kiểm duyệt |
| Security (chống lạm dụng) | Vượt ngưỡng → HTTP **429** + `Retry-After`; vượt liên tục N cửa sổ hoặc tỉ lệ 401/403 cao bất thường → **tự động tạm khóa** + cảnh báo R3 (FR-33 "nghi ngờ lạm dụng") |
| Scalability | `[ĐX]` Quy mô mục tiêu: **≤ 20 hệ thống ngoài**, tổng ≤ 5 req/s trung bình, đỉnh 50 req/s. Bộ đếm Redis O(1)/request, dung lượng key không phụ thuộc số request |
| Availability | API ngoài là **tải phụ**: khi hệ thống quá tải hoặc Redis không sẵn sàng → **từ chối request ngoài trước** (503/429) để bảo vệ người dùng nội bộ. Không có SLA cao hơn API nội bộ |
| Truy vết | Mọi lời gọi (thành công và bị từ chối) sinh **đúng một** audit event với actor là service account của khóa, phân biệt rõ với người dùng tương tác (`audit-logging.md` Quyết định 4) |
| Khả dụng cho bên tích hợp | Hợp đồng API có **version trong đường dẫn** (`/api/external/v1`), tài liệu OpenAPI tĩnh phát hành kèm, phá vỡ tương thích chỉ khi lên `v2` |

## 4. Phương án & Quyết định (ADR)

### Quyết định 1: Mô hình "khóa API" — OAuth2 client-credentials qua Keycloak

- **Ngữ cảnh:** FR-33 nói "khóa API"; hệ thống đã có Keycloak làm IdP duy
  nhất và backend đã là OAuth2 resource server (`SecurityConfig`). Câu hỏi:
  tự xây cơ chế khóa riêng hay dùng cái đã có?
- **Các phương án xem xét:**
  - **A) API key tự xây:** chuỗi ngẫu nhiên opaque gửi qua header
    `X-API-Key`, bảng `api_key` lưu hash, filter riêng dựng
    `Authentication` thủ công.
  - **B) OAuth2 client-credentials qua Keycloak:** mỗi hệ thống ngoài = **một
    Keycloak client** (confidential, `serviceAccountsEnabled=true`).
    "Khóa API" mà R3 cấp = cặp `client_id` + `client_secret`. Hệ thống ngoài
    đổi lấy access token ngắn hạn rồi gọi API bằng `Authorization: Bearer`.
  - **C) Lai:** phát API key opaque, backend tự đổi ngầm sang token nội bộ.
- **Quyết định:** **B**.
- **Lý do:**
  1. **Không xây lớp xác thực song song:** token của service account cũng có
     claim `realm_access.roles` → `SecurityConfig` hiện tại **chạy nguyên xi**,
     `@PreAuthorize("hasAuthority('PERM_...')")` áp dụng được ngay.
  2. **Không tự giữ secret:** Keycloak lưu/hash client secret, hỗ trợ sẵn
     regenerate (rotate) — thỏa NFR "không lưu plaintext" mà không phải viết
     code crypto mới.
  3. **Định danh tái dùng được:** service account của client là một user thật
     trong realm (có `sub`) → nhét thẳng vào `group_member` như một thành
     viên nhóm ⇒ **tái dùng đúng mô hình scope của `rbac-group-scope.md`**,
     không có mô hình phân quyền thứ hai.
  4. R3 đã có `KeycloakAdminService` để thao tác Admin API — cấp/thu hồi khóa
     là mở rộng module này, không phải hệ thống mới.
  5. Token ngắn hạn giảm hậu quả rò rỉ so với static key sống mãi.
- **Thiết kế đi kèm (bắt buộc):**
  - Realm role mới **`he-thong-ngoai` (R4)**, chỉ gán cho service account.
    Bổ sung vào catalog `role`/`permission` (mở rộng có kiểm soát của
    `rbac-permission-editing.md`): permission mới `api.read` (R4) và
    `api_client.manage` (R3). Không cấp cho R4 bất kỳ permission ghi nào.
  - Access token lifespan cho client này đặt riêng **5 phút** trong Keycloak
    (client-level override, đúng nguyên tắc `auth-session.md`).
  - **Thu hồi tức thì:** ngoài việc disable client trong Keycloak (chặn cấp
    token mới), thêm `ExternalClientStatusFilter` theo đúng khuôn
    `AccountStatusFilter`: mỗi request kiểm tra trạng thái khóa trong Redis
    (TTL ngắn, fallback DB, fail-closed). ⇒ hiệu lực thu hồi trong **≤ 60 s**
    kể cả với token đã phát, chứ không phải chờ token hết hạn.
  - Bảng `api_client` (Postgres) là nơi R3 quản lý phần **nghiệp vụ** của
    khóa: trạng thái, mô tả, người cấp, ngày hết hạn, ngưỡng rate-limit, ánh
    xạ tới `keycloak_client_id` + `service_account_user_id`. Secret **không**
    nằm ở đây.
- **Hệ quả (trade-off):**
  - Hệ thống ngoài phải biết gọi endpoint token OAuth2 (2 bước thay vì 1) —
    rào cản với phần mềm cũ chỉ gửi được header tĩnh. **Giảm thiểu:** cung
    cấp tài liệu + ví dụ curl/Java/.NET; nếu thực tế gặp client không làm
    được, mở phương án C ở pha sau (khóa opaque đổi lấy token nội bộ) mà
    **không** phải sửa mô hình phạm vi/audit (mục 7).
  - Keycloak trở thành phụ thuộc runtime của luồng API ngoài (Keycloak chết →
    không cấp token mới; token đang sống vẫn dùng được do verify offline).
  - Quản lý khóa phải thao tác 2 nơi (Keycloak + `api_client`) → cần bọc
    trong một service transaction-aware, có job đối soát lệch.

### Quyết định 2: Phạm vi dữ liệu expose qua API v1 — đọc, tập con có kiểm soát

- **Ngữ cảnh:** SRS FR-32 chỉ nói "truy xuất dữ liệu theo phạm vi được cấp",
  không liệt kê endpoint. Cần SA chốt bề mặt để BA/DEV không tự suy diễn.
- **Các phương án xem xét:**
  - **A) Chỉ metadata:** danh sách/chi tiết tài liệu, cây thư mục, nhãn.
  - **B) Metadata + tìm kiếm toàn văn + nội dung đã số hóa + tải bản gốc.**
  - **C) Mirror toàn bộ API nội bộ** (kể cả tìm kiếm ngữ nghĩa FR-24, báo
    cáo FR-30/31, ghi dữ liệu).
- **Quyết định:** **B** cho v1, **read-only**.
- **Bề mặt v1 (chốt):**
  - `GET /api/external/v1/documents` (lọc, phân trang) và `/{id}`
  - `GET /api/external/v1/folders` (cây thư mục **trong phạm vi**)
  - `GET /api/external/v1/tags`
  - `GET /api/external/v1/search` — **chỉ toàn văn** (FR-22/23), qua đúng
    `ScopeFilterBuilder` của `search-fulltext.md`
  - `GET /api/external/v1/documents/{id}/segments` — nội dung OCR/STT
    **đã được kiểm duyệt/xác nhận** (`digitization-review.md`), có mốc thời
    gian/toạ độ
  - `GET /api/external/v1/documents/{id}/content` — tải bản gốc (bản gốc bất
    biến, chỉ đọc)
- **Loại trừ rõ ràng khỏi v1:** tìm kiếm ngữ nghĩa (FR-24), kho cá nhân
  (`personal-storage-quota.md` — không số hóa, không chia sẻ), thùng rác/khôi
  phục, nhật ký kiểm toán, cấu hình hệ thống, quản lý tài khoản/nhóm, báo cáo
  thống kê (FR-30/31), mọi thao tác ghi.
- **Lý do:**
  - A quá hẹp: "khai thác dữ liệu" mà không đọc được nội dung/tìm kiếm thì
    không đạt tiêu chí chấp nhận FR-32.
  - C bị loại: (i) tìm kiếm ngữ nghĩa tiêu thụ **GPU dùng chung duy nhất**
    (96GB, `ai-search-infra.md`) → traffic máy-máy có thể bỏ đói người dùng
    nội bộ; (ii) mở API ghi làm vòng qua kiểm tra mã độc/hạn ngạch/kiểm duyệt
    của FR-11–18; (iii) mirror API nội bộ khóa cứng hợp đồng ngoài vào
    refactor nội bộ.
  - Chỉ trả nội dung **đã kiểm duyệt** tránh việc hệ thống ngoài lan truyền
    kết quả AI chưa xác nhận như dữ liệu chính thức.
- **Hệ quả (trade-off):** nếu bên tích hợp cần FR-24, phải mở ở v1.1 sau
  đánh giá tải GPU, kèm permission riêng (`api.semantic_search`) và
  rate-limit chặt hơn hẳn (mục 7). Endpoint tải file có thể tạo tải I/O lớn →
  phải có quota băng thông riêng.

### Quyết định 3: Phạm vi của một khóa = service account là thành viên nhóm

- **Ngữ cảnh:** FR-32/FR-33 nói "phạm vi truy cập do R3 cấp"; nguy cơ lớn
  nhất là DEV tự phát minh bảng `api_key_scope` song song với nhóm.
- **Các phương án xem xét:**
  - **A) Bảng scope riêng cho khóa** (`api_key_scope(api_key_id, folder_id/
    tag/...)`).
  - **B) Service account là user thật, R3 thêm nó vào N nhóm qua đúng UI/luồng
    `group-management-sharing.md`**; filter phạm vi dùng nguyên
    `rbac-group-scope.md` Quyết định 3.
  - **C) Lai:** B + tùy chọn thu hẹp thêm (allowlist tập con nhóm) ở tầng
    khóa.
- **Quyết định:** **B**.
- **Lý do:** `rbac-group-scope.md` đã chốt nhóm là **cơ chế phạm vi duy
  nhất** và filter chạy ở tầng query cho **mọi** entrypoint, trong đó FR-32
  được nêu đích danh. B khiến API ngoài tự động đúng phạm vi mà không cần một
  dòng logic scope mới, và mọi thay đổi chia sẻ (FR-28/29) có hiệu lực ngay
  với API. A tạo lớp phân quyền thứ hai — chính là rủi ro "bỏ sót entrypoint"
  đã ghi trong bảng rủi ro của `rbac-group-scope.md`.
- **Hệ quả (trade-off):**
  - Service account xuất hiện trong danh sách thành viên nhóm → UI phải hiển
    thị rõ nhãn "hệ thống ngoài" để người dùng biết dữ liệu chia sẻ vào nhóm
    sẽ ra ngoài (BA cần AC cho điểm này).
  - Quy tắc "chỉ người tạo/nạp thấy tài liệu chưa chia sẻ" áp dụng nguyên vẹn
    ⇒ service account **không bao giờ** tự "sở hữu" tài liệu nào; nếu nhóm
    trống thì API trả rỗng chứ không lỗi.
  - Không hỗ trợ scope mịn hơn nhóm ở v1 (chấp nhận có chủ đích).

### Quyết định 4: Rate-limit — token bucket trên Redis bằng Lua script

- **Ngữ cảnh:** FR-33 bắt buộc "chặn khi vượt ngưỡng"; backend nhiều instance;
  chưa có gateway; Redis đã có sẵn và đang dùng qua `StringRedisTemplate`.
- **Các phương án xem xét:**
  - **A) Fixed-window counter** (`INCR` + `EXPIRE`).
  - **B) Token bucket** (rate + burst) hiện thực bằng **một Lua script atomic**
    trên Redis, key theo `client_id` (+ theo lớp endpoint).
  - **C) Thư viện Bucket4j + Redis.**
  - **D) Chặn ở API gateway** (Nginx `limit_req`, Kong, APISIX).
- **Quyết định:** **B**, đặt trong một `HandlerInterceptor`/filter chỉ áp cho
  prefix `/api/external/**`. Nhiều tầng hạn mức cùng lúc: **per-second burst →
  per-minute → per-day**, cộng thêm semaphore giới hạn concurrency mỗi khóa.
- **Lý do:** B cho phép burst hợp lý mà vẫn giữ tốc độ trung bình (A gây hiệu
  ứng "biên cửa sổ" cho phép gấp đôi ngưỡng), chỉ **một** round-trip Redis
  atomic (đạt NFR ≤ 3 ms), không thêm dependency (quan trọng với air-gap).
  C tương đương về chức năng nhưng thêm một thư viện cho ~30 dòng Lua — để
  ngỏ nếu DEV thấy tự viết rủi ro. D bị loại: thêm hẳn một thành phần hạ tầng
  mới vào compose, và gateway **không biết** `client_id` sau khi verify JWT
  nên hạn mức theo khóa sẽ kém chính xác.
- **Hành vi lỗi (chốt):** Redis không sẵn sàng → **fail-closed cho API ngoài**
  (503 + `Retry-After`), khác với các module nội bộ đang fallback-to-DB. Lý
  do: rate-limit là biện pháp bảo vệ, và theo NFR Availability, tải ngoài phải
  hy sinh trước tải nội bộ. Sự kiện này ghi log cảnh báo cho R3.
- **Hệ quả (trade-off):** phải bảo trì một Lua script (test kỹ, gồm case đồng
  hồ lệch giữa instance — dùng `TIME` của Redis làm nguồn thời gian). Ngưỡng
  đọc từ `api_client` nên cần cache (Caffeine/Redis, TTL ~60 s) để không truy
  DB mỗi request; đổi ngưỡng có hiệu lực trễ ≤ 60 s.

### Quyết định 5: Giám sát sử dụng (FR-33) — đếm ở Redis, chốt sổ theo giờ vào Postgres

- **Ngữ cảnh:** FR-33 yêu cầu "thống kê sử dụng"; `audit-logging.md` đã ghi
  mọi lời gọi vào `audit_log` (append-only, partition tháng). Có nên thống kê
  bằng cách quét `audit_log` không?
- **Các phương án xem xét:**
  - **A) Truy vấn aggregate trực tiếp trên `audit_log`** mỗi lần R3 mở màn
    hình thống kê.
  - **B) Bộ đếm Redis (đã có sẵn từ Quyết định 4) + job `@Scheduled` chốt sổ
    theo giờ vào bảng `api_usage_stat(client_id, hour, endpoint_class,
    calls, denied_429, denied_403, bytes_out, latency_p95)`.**
  - **C) Đẩy metric sang Prometheus/Grafana.**
- **Quyết định:** **B**. `audit_log` vẫn là bằng chứng pháp lý cho từng lời
  gọi; `api_usage_stat` chỉ là dữ liệu tổng hợp phục vụ màn hình FR-33.
- **Lý do:** A tạo truy vấn aggregate nặng trên bảng đang được tối ưu cho lọc
  theo actor/thời gian, và mâu thuẫn NFR "FR-10 ≤ 2 s". B tái dùng chính bộ
  đếm rate-limit (gần như miễn phí). C là hệ thống mới, ngoài phạm vi SRS.
- **Hệ quả (trade-off):** thống kê trễ tối đa 1 giờ (chấp nhận; có thể đọc
  bộ đếm Redis để hiển thị "giờ hiện tại" gần realtime); mất dữ liệu chốt sổ
  nếu Redis chết giữa giờ → chấp nhận sai số thống kê, không ảnh hưởng audit.

### Quyết định 6: Tách bề mặt API ngoài khỏi API nội bộ

- **Ngữ cảnh:** cám dỗ lớn nhất là cho hệ thống ngoài gọi thẳng
  `/documents`, `/search` hiện có bằng token R4.
- **Các phương án xem xét:**
  - **A) Dùng chung controller nội bộ**, chỉ khác role.
  - **B) Module riêng `externalapi/` với prefix `/api/external/v1`, DTO
    riêng, controller riêng — gọi lại service nghiệp vụ (không copy logic).**
- **Quyết định:** **B**.
- **Lý do:** (i) hợp đồng với bên thứ ba phải ổn định trong khi DTO nội bộ
  thay đổi liên tục; (ii) tập trung toàn bộ chính sách của R4 (rate-limit,
  audit actor máy, ẩn field nội bộ như đường dẫn lưu trữ/`storage_key`, trạng
  thái job AI, thông tin người dùng nội bộ) vào **một** nơi dễ review bảo mật;
  (iii) chỉ cần một matcher `/api/external/**` để gắn filter chuỗi chính sách.
- **Hệ quả (trade-off):** có lặp lại lớp mapping DTO; bù lại rủi ro rò rỉ
  field nội bộ giảm mạnh. Bắt buộc kèm **spec OpenAPI** cho v1 (viết tay hoặc
  thêm `springdoc-openapi` — hiện `pom.xml` chưa có, xem mục 7).

## 5. Tech stack đề xuất

- **Backend:** Spring Boot 3, module mới `externalapi/` (controller + DTO +
  `ExternalApiRateLimitFilter` + `ExternalClientStatusFilter`); Spring
  Security OAuth2 resource server (tái dùng `SecurityConfig`, không đổi);
  Spring `@Scheduled` cho job chốt sổ thống kê; module `apiclients/` (quản trị
  khóa cho R3) mở rộng `KeycloakAdminService`.
- **Auth:** Keycloak 24, realm `aismartstore` — thêm role `he-thong-ngoai`,
  mỗi hệ thống ngoài là một confidential client bật service account, access
  token lifespan 5 phút (client-level override).
- **Frontend:** Next.js — trang `admin/api-clients` (chỉ R3): cấp khóa, hiện
  secret một lần, rotate, thu hồi, gán nhóm (phạm vi), đặt ngưỡng tần suất,
  xem thống kê sử dụng.
- **Data store:** PostgreSQL 16 (Flyway) — `api_client`, `api_usage_stat`;
  không thêm bảng scope (dùng `group_member` sẵn có). Redis — token bucket +
  bộ đếm sử dụng + cache trạng thái/ngưỡng khóa (đều là dữ liệu phái sinh/
  ngắn hạn, đúng nguyên tắc "Redis là cache").
- **Infra/Deploy:** không thêm service mới vào `docker-compose.yml`. Kiểm soát
  nguồn gọi bằng allowlist IP/CIDR ở firewall/VLAN + (tùy chọn) allowlist IP
  theo khóa trong `api_client`. TLS bắt buộc.
- **Tài liệu tích hợp:** OpenAPI 3 cho `/api/external/v1` + ví dụ lấy token.

## 6. Rủi ro

| Rủi ro | Mức độ | Giảm thiểu |
|---|---|---|
| Rò rỉ dữ liệu ngoài phạm vi do endpoint ngoài quên áp filter nhóm | Cao | Bắt buộc đi qua service/`ScopeFilterBuilder` chung (`rbac-group-scope.md`); test tự động "mỗi endpoint ngoài phải trả rỗng khi service account không thuộc nhóm nào" |
| Lộ `client_secret` phía hệ thống ngoài (lưu trong file cấu hình bên thứ ba) | Cao | Token ngắn hạn; rotate định kỳ do R3; allowlist IP theo khóa; thu hồi ≤ 60 s; audit đầy đủ để truy vết |
| DEV tự làm bảng scope riêng cho khóa vì thấy "dễ hơn" | Cao | Quyết định 3 nêu tường minh + BA/DEV review bắt buộc; không tạo bảng `api_key_scope` trong migration |
| Hệ thống ngoài quét dữ liệu hàng loạt (scraping toàn kho trong phạm vi) | Trung bình | Quota ngày + hạn mức riêng cho endpoint tải file + cảnh báo bất thường cho R3; `page_size` ≤ 100 |
| Field nội bộ lọt ra DTO ngoài (đường dẫn lưu trữ, thông tin người dùng, trạng thái AI) | Trung bình | DTO riêng ở `externalapi/` (Quyết định 6) + review bảo mật hợp đồng OpenAPI trước khi phát hành |
| Redis chết → fail-closed làm hệ thống ngoài mất kết nối đột ngột | Trung bình | Ghi rõ trong hợp đồng API (503 + `Retry-After`); giám sát Redis; đây là lựa chọn có chủ đích |
| Lệch trạng thái giữa Keycloak client và bảng `api_client` (tạo/xóa nửa chừng) | Trung bình | Thao tác bọc trong một service, ưu tiên tạo Keycloak trước rồi mới commit DB; job đối soát định kỳ + báo cáo lệch cho R3 |
| Hệ thống ngoài (phần mềm cũ) không hỗ trợ OAuth2 client-credentials | Trung bình | Tài liệu + ví dụ; nếu chặn thật thì mở phương án C của Quyết định 1 ở pha sau, giữ nguyên scope/audit |
| Tải API ngoài ảnh hưởng hiệu năng người dùng nội bộ | Trung bình | Không expose FR-24 (GPU); hạn mức concurrency mỗi khóa; ưu tiên hy sinh tải ngoài khi quá tải |
| Audit phình do mỗi lời gọi API sinh 1 event (vượt giả định 20k–50k/ngày của `audit-logging.md`) | Thấp–Trung bình | Ước lượng lại theo quota ngày; nếu vượt, cân nhắc audit theo phiên/gộp cho endpoint đọc — cần đánh giá lại cùng `audit-logging.md` (mục 7) |
| Thêm role R4 làm sai lệch catalog RBAC "đóng" | Thấp | Ghi phụ lục mở rộng vào `rbac-group-scope.md`/`rbac-permission-editing.md` khi doc này được duyệt; R4 chỉ có permission đọc |
| Lua script rate-limit sai ở biên (đồng hồ, TTL) | Thấp | Dùng `TIME` của Redis; test đồng thời nhiều instance; hoặc chuyển sang Bucket4j nếu thấy rủi ro |

## 7. Câu hỏi mở

> Người dùng review sau; các mục dưới đây đã có **giả định mặc định** để BA/DEV
> không bị chặn — chỉ cần xác nhận hoặc sửa.

- [ ] **Danh sách hệ thống ngoài thực tế:** hiện có bao nhiêu, là phần mềm gì,
      chúng có hỗ trợ OAuth2 client-credentials không? *(Giả định: ≤ 20 hệ
      thống, đều nằm trong VLAN nội bộ, có thể gọi token endpoint. Nếu sai →
      xem lại Quyết định 1, mở phương án C.)*
- [ ] **Vị trí mạng:** API ngoài đặt ở VLAN nào, có cần DMZ/reverse proxy
      riêng tách khỏi web nội bộ không? *(Giả định: cùng mạng nội bộ đã cách
      ly, chỉ hạn chế bằng firewall/allowlist IP, không thêm proxy.)*
- [ ] **Phạm vi dữ liệu (Quyết định 2)** có đúng nhu cầu nghiệp vụ không? Bên
      tích hợp có cần **tìm kiếm ngữ nghĩa (FR-24)**, **báo cáo (FR-30/31)**,
      hay **API ghi/đồng bộ ngược** không? *(Giả định: không, v1 read-only,
      không có FR-24.)*
- [ ] **Nội dung chưa kiểm duyệt:** có trường hợp nào hệ thống ngoài cần đọc
      kết quả OCR/STT **trước** khi được xác nhận không? *(Giả định: không —
      chỉ trả nội dung đã xác nhận.)*
- [ ] **Tải bản gốc qua API:** có được phép tải file gốc ra hệ thống ngoài
      không, hay chỉ metadata + văn bản đã số hóa? Đây là điểm nhạy cảm nhất
      về rò rỉ. *(Giả định: được phép, có quota riêng và audit từng lượt tải.)*
- [ ] **Ngưỡng rate-limit mặc định** (60 req/phút, 50k/ngày…) có phù hợp
      không? *(Giả định: dùng số `[ĐX]` ở mục 3, R3 chỉnh được theo từng khóa.)*
- [ ] **Hạn khóa:** khóa API có bắt buộc ngày hết hạn (vd. 12 tháng) và bắt
      buộc rotate định kỳ không? *(Giả định: có `expires_at` tùy chọn, mặc
      định 12 tháng, cảnh báo trước 30 ngày.)*
- [ ] **Khối lượng audit:** nếu API ngoài chạy sát quota, số event/ngày có thể
      vượt giả định 20k–50k của `audit-logging.md` → có chấp nhận tăng
      retention cost, hay gộp event cho endpoint đọc? *(Giả định: giữ 1 event/
      lời gọi, đánh giá lại sau 1 tháng vận hành.)*
- [ ] **Thêm role R4 vào catalog RBAC đóng** — xác nhận đây là mở rộng hợp lệ
      (SRS ma trận mục 3 vốn có cột R4), cần cập nhật phụ lục cho
      `rbac-group-scope.md` / `rbac-permission-editing.md`.
- [ ] **`springdoc-openapi`**: chấp nhận thêm dependency để sinh OpenAPI tự
      động, hay viết spec tay? *(Giả định: thêm dependency, giới hạn chỉ quét
      package `externalapi`, không expose Swagger UI ở môi trường thật.)*
- [ ] **Ngưỡng "nghi ngờ lạm dụng"** để tự động tạm khóa (FR-33): bao nhiêu
      lần 429/403 liên tiếp thì khóa và báo R3? *(Giả định: ≥ 3 cửa sổ phút
      liên tiếp bị 429, hoặc > 50 lần 403 trong 10 phút → tạm khóa 15 phút +
      thông báo R3.)*
