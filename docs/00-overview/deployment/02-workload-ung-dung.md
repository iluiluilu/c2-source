# 02 — Tầng ứng dụng & dữ liệu (`node-app`)

> Phụ thuộc: [`01-nen-tang-docker.md`](01-nen-tang-docker.md) đã nghiệm thu.
> Project: `/opt/aismartstore/app/docker-compose.yml`

## 1. Quy tắc chung — áp cho MỌI service

### 1.1. Định mức tài nguyên

Node có 128GB; đã chừa 16GB ZFS ARC + 6GB OS/Docker + 14GB page cache ⇒ **92GB
cho container** (xem [`../hardware-resource-allocation.md`](../hardware-resource-allocation.md) §4.1).

| Service | `mem_limit` | `cpus` | `oom_score_adj` | Dữ liệu |
|---|---|---|---|---|
| PostgreSQL | 20g | 12 | **-500** | `/srv/pgdata` (LUN SAN SSD) |
| OpenSearch | 36g | 16 | **-500** | `/var/lib/local-pv/opensearch` (NVMe) — gồm cả vector k-NN (Qdrant đã gỡ 2026-10-10) |
| backend | 10g | 12 | 0 | — |
| SeaweedFS | 6g | 6 | **-500** | `/tank/object` (ZFS) |
| ClamAV | 5g | 4 | 0 | volume |
| RabbitMQ | 3g | 3 | -200 | volume trên NVMe |
| Keycloak | 3g | 3 | 0 | — (state ở PostgreSQL) |
| Redis | 3g | 2 | 0 | — (không persist) |
| web | 2g | 4 | 0 | — |
| Kong | 2g | 4 | 0 | — (DB-less) |
| pgBackRest + exporter + promtail + autoheal | 2g | 2 | 0 | — |
| **Tổng** | **92g** | **68** | | |

> ⚠️ **`mem_limit` phải đặt cho MỌI service, kể cả service nhỏ.** Docker mặc
> định không giới hạn gì. Một service rò rỉ bộ nhớ mà không có trần sẽ ăn hết
> RAM còn lại, rồi kernel OOM-killer chọn nạn nhân theo RSS — và nạn nhân thường
> là PostgreSQL. `oom_score_adj: -500` cho tầng dữ liệu là lớp bảo vệ thứ hai:
> kernel sẽ chọn chúng **sau cùng**.

### 1.2. Mẫu chung

```yaml
x-common: &common
  restart: unless-stopped
  logging:
    driver: json-file
    options: { max-size: "10m", max-file: "5" }
  networks: [app]

services:
  vi-du:
    <<: *common
    mem_limit: 20g
    cpus: "12"
    oom_score_adj: -500
    labels: { autoheal: "true" }
    healthcheck:
      test: ["CMD-SHELL", "..."]
      interval: 30s
      timeout: 10s
      retries: 3
      start_period: 120s
```

**Mọi service phải có `healthcheck`** — không có thì `autoheal` không biết gì để
chữa, và `depends_on: condition: service_healthy` cũng vô nghĩa.

## 2. PostgreSQL + pgBackRest

```yaml
  postgres:
    <<: *common
    image: postgres:16
    mem_limit: 20g
    cpus: "12"
    oom_score_adj: -500
    shm_size: 2g               # thieu -> loi "could not resize shared memory"
    volumes:
      - /srv/pgdata:/var/lib/postgresql/data
      - ./config/postgres/postgresql.conf:/etc/postgresql/postgresql.conf:ro
      - pgbackrest_conf:/etc/pgbackrest
    command: postgres -c config_file=/etc/postgresql/postgresql.conf
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U $${POSTGRES_USER} -d $${POSTGRES_DB}"]
      interval: 15s
      timeout: 10s
      retries: 10
      start_period: 300s       # rong tay: recovery sau su co co the lau
```

```ini
# config/postgres/postgresql.conf — theo bang phan bo muc 1.1
shared_buffers              = 6GB
effective_cache_size        = 16GB
work_mem                    = 32MB
maintenance_work_mem        = 2GB
max_connections             = 200
random_page_cost            = 1.5      # SSD qua iSCSI
effective_io_concurrency    = 200
wal_compression             = on
archive_mode                = on
archive_command             = 'pgbackrest --stanza=main archive-push %p'
archive_timeout             = 900      # 15 phut = dung RPO metadata
shared_preload_libraries    = 'pg_stat_statements'
log_min_duration_statement  = 1000     # log truy van > 1s de chan doan
log_line_prefix             = '%m [%p] %q%u@%d '
```

### pgBackRest — lo PITR và WAL archiving

```ini
# /etc/pgbackrest/pgbackrest.conf
[global]
repo1-type              = s3
repo1-s3-endpoint       = <NODE_APP_IP>:8333       # SeaweedFS
repo1-s3-bucket         = backup
repo1-s3-region         = <S3_REGION>
repo1-s3-uri-style      = path                     # BAT BUOC voi SeaweedFS
repo1-path              = /pg
repo1-retention-full    = 14                       # khop PITR 14 ngay cua GFS
repo1-retention-archive-type = full
process-max             = 4
compress-type           = zst
start-fast              = y

[main]
pg1-path = /var/lib/postgresql/data
```

> ⚠️ **`repo1-s3-uri-style = path`**: SeaweedFS không hỗ trợ virtual-hosted
> style. Thiếu dòng này thì pgBackRest báo lỗi DNS rất khó hiểu.

> ⚠️ **Thứ tự khởi tạo có ràng buộc vòng:** pgBackRest ghi repo vào SeaweedFS,
> mà SeaweedFS dùng PostgreSQL làm filer store. Khi dựng mới: khởi động
> PostgreSQL **trước** (chưa bật `archive_mode`) → khởi động SeaweedFS → tạo
> bucket `backup` → bật `archive_mode` → `pgbackrest stanza-create`. Ghi rõ
> trong runbook dựng lại, nếu không sẽ gặp deadlock khởi động.

```bash
pgbackrest --stanza=main stanza-create
pgbackrest --stanza=main check              # phai PASS truoc khi di tiep
```

### PgBouncer

```yaml
  pgbouncer:
    <<: *common
    image: edoburu/pgbouncer:1.23
    environment:
      DB_HOST: postgres
      POOL_MODE: transaction
      MAX_CLIENT_CONN: "500"
      DEFAULT_POOL_SIZE: "50"
    mem_limit: 1g
```

Backend trỏ `pgbouncer:6432`, **không** trỏ thẳng `postgres:5432`.

> ⚠️ `POOL_MODE=transaction` **không tương thích** với prepared statement mức
> session. Backend Spring Boot (HikariCP + PostgreSQL JDBC) phải có
> `prepareThreshold=0` trong JDBC URL — nếu không sẽ gặp lỗi
> `prepared statement "S_1" already exists` rải rác, rất khó chẩn đoán.

## 3. OpenSearch

```yaml
  opensearch:
    <<: *common
    image: opensearchproject/opensearch:2.17.1
    mem_limit: 36g   # 24g + 12g của Qdrant cũ cho k-NN off-heap (sửa 2026-10-10)
    cpus: "16"
    oom_score_adj: -500
    ulimits:
      memlock: { soft: -1, hard: -1 }
      nofile:  { soft: 65536, hard: 65536 }
    environment:
      - discovery.type=single-node
      - OPENSEARCH_JAVA_OPTS=-Xms10g -Xmx10g
      - bootstrap.memory_lock=true
      - OPENSEARCH_INITIAL_ADMIN_PASSWORD=${OPENSEARCH_INITIAL_ADMIN_PASSWORD}
    volumes:
      - /var/lib/local-pv/opensearch:/usr/share/opensearch/data
    healthcheck:
      test: ["CMD-SHELL", "curl -sfk -u admin:$${OPENSEARCH_INITIAL_ADMIN_PASSWORD} https://localhost:9200/_cluster/health | grep -v '\"status\":\"red\"'"]
      interval: 30s
      start_period: 300s
```

Bốn điểm bắt buộc:

1. **Heap 10GB, KHÔNG phải 512MB.** Giá trị `-Xms512m` ở môi trường staging là
   cấu hình dev; với mục tiêu ~20 triệu segment và chỉ mục ~300GB
   (`ai-search-infra.md` §3) nó sẽ gây circuit-breaker liên tục. Rủi ro này đã
   được chính `ai-search-infra.md` §6 nêu ("cấu hình dev bị mang lên production").
2. **Heap ≤ 50% RAM container và < ~30.5GB** (ngưỡng compressed oops). 10g/36g
   thoả cả hai; phần còn lại để OS cache segment Lucene và bộ nhớ native k-NN.
3. **Bật security plugin** (TLS + xác thực). Production **không** được
   `plugins.security.disabled=true`.
4. **Cài `analysis-icu`** — quyết định analyzer tiếng Việt ở `ai-search-infra.md`
   QĐ 1, cài offline vào image.

> ⚠️ **Đổi analyzer sau khi đã có dữ liệu ⇒ BẮT BUỘC reindex toàn bộ.** Chốt
> analyzer trước khi nạp dữ liệu thật.

> `memlock: -1` chỉ có tác dụng nếu host đã đặt `limits.conf` ở
> [`00-chuan-bi-ha-tang.md`](00-chuan-bi-ha-tang.md) §2. Thiếu thì OpenSearch
> vẫn chạy nhưng heap bị swap ra, gây p99 tăng vọt bất thường.

## 4. Vector k-NN trong OpenSearch (thay Qdrant — sửa 2026-10-10)

Qdrant **đã gỡ khỏi hạ tầng** (`search-semantic.md` QĐ 6, `ai-search-infra.md`
QĐ 2). Vector 1024 chiều (Qwen3-Embedding-0.6B) nằm trên field `knn_vector` của
index `segment` trong OpenSearch — k-NN plugin có sẵn trong image
`opensearchproject/opensearch:2.17.1`, **không** cần cài thêm plugin.

- Ngân sách RAM của Qdrant cũ (12g) gộp vào OpenSearch (mục 3: `mem_limit`
  36g). Heap giữ 10GB; phần còn lại cho bộ nhớ native của k-NN (faiss, off-heap).
- Mapping field vector do backend tạo khi bootstrap index (không tạo tay):
  engine `faiss`, HNSW, `space_type: cosinesimil`, **`mode: on_disk`** + nén
  (`compression_level`) — bắt buộc, vì 20 triệu vector × 1024 × float32 ≈
  **80GB**, vượt xa RAM cấp cho container.
- Giới hạn bộ nhớ k-NN: `knn.memory.circuit_breaker.limit` ~60% phần off-heap;
  theo dõi `_plugins/_knn/stats` (mục giám sát).
- **Đo lại recall sau POC** — nén vector làm giảm độ chính xác chút ít (bù bằng
  rescore lúc truy vấn).

## 5. SeaweedFS

```yaml
  seaweedfs:
    <<: *common
    image: chrislusf/seaweedfs:4.44
    mem_limit: 6g
    cpus: "6"
    oom_score_adj: -500
    command: >
      server -ip.bind=0.0.0.0 -dir=/data -master.volumeSizeLimitMB=30000
      -filer -filer.store=postgres -s3 -s3.port=8333
      -s3.config=/etc/seaweedfs/s3.json
    volumes:
      - /tank/object:/data
      - ./config/seaweedfs/s3.json:/etc/seaweedfs/s3.json:ro
    ports: ["<NODE_APP_IP>:8333:8333"]
    depends_on:
      postgres: { condition: service_healthy }
```

- **Filer store trên PostgreSQL** (`kien_truc_he_thong.adoc` §4) ⇒ metadata vị
  trí tệp nằm trong luồng sao lưu T0 cùng CSDL nghiệp vụ.
- **Không bật SSE của SeaweedFS** — mã hoá at-rest đã do ZFS lo. Mã hoá 2 lần
  chỉ tốn CPU.
- Bật **Object Lock/WORM + versioning** cho bucket bản gốc (FR-09, NFR-05).

> ⚠️ **Hệ quả vận hành:** SeaweedFS tách metadata (PostgreSQL) khỏi dữ liệu
> (ZFS). Khi sao lưu/khôi phục, **hai phần phải khớp thời điểm** — nếu không sẽ
> sinh object "mồ côi". Đây là lý do `backup-restore.md` QĐ 2 chốt thứ tự "DB
> trước, snapshot sau".

> ⚠️ **Phiên bản ≥ 4.44.** Bản 3.97 có lỗi: identity S3 không phải Admin (quyền
> Read/List/Write riêng lẻ, kể cả scope theo bucket) bị từ chối `AccessDenied`
> cho mọi thao tác — đã xác minh bằng test trực tiếp. Lỗi này chặn việc cấp
> quyền chỉ-đọc cho hệ thống AI ngoài.

## 6. RabbitMQ

```yaml
  rabbitmq:
    <<: *common
    image: rabbitmq:3.13-management-alpine
    mem_limit: 3g
    cpus: "3"
    oom_score_adj: -200
    environment:
      RABBITMQ_VM_MEMORY_HIGH_WATERMARK: "0.6"
      RABBITMQ_DEFAULT_USER: ${RABBITMQ_DEFAULT_USER}
      RABBITMQ_DEFAULT_PASS: ${RABBITMQ_DEFAULT_PASS}
    ports: ["<NODE_APP_IP>:5672:5672", "<NODE_APP_IP>:15672:15672"]
    healthcheck:
      test: ["CMD", "rabbitmq-diagnostics", "-q", "ping"]
```

Bật plugin: `rabbitmq_management`, `rabbitmq_prometheus`.

Cấu hình queue theo `ai-search-infra.md` QĐ 3 và `digitization-pipeline.md` QĐ 2:

| Hạng mục | Giá trị |
|---|---|
| Loại queue | **Quorum queue** |
| Ack | `manual ack` |
| Prefetch worker GPU | `1` |
| Queue | `q.ocr`, `q.stt`, `q.video`, `q.embed`, `q.result` + `.retry` + `.dlq` mỗi loại |
| Retry | Dead-letter exchange + TTL backoff |
| Priority | Queue ưu tiên cho sự kiện `acl.changed` |

> ⚠️ **Argument queue phải khớp TUYỆT ĐỐI với code backend.** RabbitMQ trả
> `PRECONDITION_FAILED (406)` nếu ứng dụng khai báo lại queue đã tồn tại với
> argument khác — và backend sẽ vào vòng lặp crash. **Đây là lỗi đã gặp thật.**
> Chốt argument một lần, ghi vào tài liệu, không sửa lệch một bên.

**Tài khoản cho hệ thống AI ngoài:** tạo user riêng, **không** dùng tài khoản
admin. Ví dụ `ai-consumer` chỉ quyền `read` trên đúng `q.ocr`/`q.stt`, không
`configure`/`write`, không user tag (⇒ không đăng nhập được management UI).

## 7. Keycloak

```yaml
  keycloak:
    <<: *common
    image: quay.io/keycloak/keycloak:24.0.0
    command: ["start"]          # KHONG "start-dev"
    mem_limit: 3g
    cpus: "3"
    environment:
      KC_DB: postgres
      KC_DB_URL: jdbc:postgresql://pgbouncer:6432/keycloak
      KC_HOSTNAME: <DOMAIN_AUTH>
      KC_PROXY_HEADERS: xforwarded
      KC_METRICS_ENABLED: "true"
      JAVA_OPTS_APPEND: "-Xmx2g"
```

> ⚠️ **Issuer phải khớp tuyệt đối.** Browser, backend và web đều phải thấy
> **cùng một** `<DOMAIN_AUTH>` — lệch issuer (kể cả chỉ khác `http`/`https` hoặc
> có/không port) làm JWT bị từ chối và đăng nhập gãy. Khác với môi trường
> staging trước đây (Cloudflare Flexible, origin chỉ nói HTTP), ở đây **có TLS
> nội bộ suốt tuyến** nên đặt `https` đồng nhất ở cả 3 nơi.

Cấu hình theo `danh_gia_lua_chon_cong_nghe.adoc` §4.15: Authorization Code Flow
+ PKCE (FR-02); Client Credentials Grant + API key ở Kong (FR-32/33); realm
roles (FR-07); custom claim nhóm cho FR-08.

## 8. Redis, ClamAV, backend, web, Kong

```yaml
  redis:
    <<: *common
    image: redis:7-alpine
    command: ["redis-server","--maxmemory","2gb","--maxmemory-policy","allkeys-lru",
              "--save","","--appendonly","no"]
    mem_limit: 3g
    cpus: "2"
```
Không persist — Redis thuộc nhóm "không sao lưu" (`backup-restore.md` QĐ 1).

```yaml
  clamav:
    <<: *common
    image: clamav/clamav-debian:1.4
    mem_limit: 5g
    cpus: "4"
    volumes:
      - clamav_data:/var/lib/clamav
      - ./config/clamav/clamd.conf:/etc/clamav/clamd.conf:ro
```
`ConcurrentDatabaseReload no` trong `clamd.conf` — CSDL chữ ký ~2.5GB nạp RAM;
reload đồng thời làm nhân đôi và đây là nguyên nhân phổ biến khiến ClamAV bị
OOM-kill. Cập nhật chữ ký qua **mirror `freshclam` nội bộ**, đồng bộ thủ công
theo lịch air-gap (NFR-10).

```yaml
  backend:
    <<: *common
    image: <REGISTRY>/backend:<TAG>
    mem_limit: 10g
    cpus: "12"
    labels: { autoheal: "true" }
    environment:
      JAVA_TOOL_OPTIONS: "-XX:MaxRAMPercentage=65 -XX:+UseG1GC"
      DB_HOST: pgbouncer
      DB_PORT: "6432"
      S3_ENDPOINT: http://seaweedfs:8333
      OPENSEARCH_HOST: opensearch
      EMBEDDING_API_URL: http://<NODE_AI_IP>:<port>/ai/services/v1/embedding/embed  # embedding-service nội bộ (sửa 2026-10-10, thay QDRANT_URL)
      RABBITMQ_HOST: rabbitmq
      REDIS_HOST: redis
      CLAMD_HOST: clamav
    depends_on:
      postgres:   { condition: service_healthy }
      opensearch: { condition: service_healthy }
      rabbitmq:   { condition: service_healthy }
      seaweedfs:  { condition: service_healthy }
```

> `-XX:MaxRAMPercentage` thay vì `-Xmx` cứng: JVM tự đọc giới hạn cgroup, nên
> sửa `mem_limit` là JVM tự theo, không phải sửa 2 chỗ và rủi ro lệch.

> ⚠️ **Chặn ở đây:** backend hiện **chưa có** `spring-boot-starter-actuator` +
> `micrometer-registry-prometheus` ⇒ **không có `/actuator/health`** để làm
> `healthcheck`, và **không có metric ứng dụng nào**. Trước khi có hai dependency
> đó, `healthcheck` phải tạm dùng:
> ```yaml
> test: ["CMD-SHELL", "nc -z localhost 3001 || exit 1"]
> ```
> — chỉ biết tiến trình còn mở cổng, **không** biết ứng dụng còn phục vụ được
> hay không. Tức `autoheal` cũng không phát hiện được service treo. Xem
> [`README.md`](README.md) §4.

```yaml
  web:
    <<: *common
    image: <REGISTRY>/web:<TAG>
    mem_limit: 2g
    cpus: "4"
```
> `NEXT_PUBLIC_*` được "bake" vào image lúc build — khai báo lại trong compose
> **không** đổi được giá trị client-side. Phải build image riêng cho từng môi
> trường có domain khác nhau.

```yaml
  kong:
    <<: *common
    image: kong:3.8
    mem_limit: 2g
    cpus: "4"
    environment:
      KONG_DATABASE: "off"                      # DB-less
      KONG_DECLARATIVE_CONFIG: /kong/kong.yml
      KONG_PROXY_LISTEN: "0.0.0.0:80, 0.0.0.0:443 ssl"
      KONG_STATUS_LISTEN: "0.0.0.0:8100"
    volumes:
      - ./config/kong/kong.yml:/kong/kong.yml:ro
      - ./config/kong/tls:/kong/tls:ro
    ports: ["80:80", "443:443"]
```
Chế độ **DB-less**: cấu hình từ file khai báo trong Git — bớt một hệ stateful
phải sao lưu. Bật plugin `key-auth`, `rate-limiting`, `acl` (FR-33) và
`prometheus`.

## 9. TLS nội bộ

Toàn hệ trong VLAN cách ly nhưng vẫn phải có TLS (NFR-04, và Keycloak issuer
`https`). Dùng **CA nội bộ**: tạo một CA riêng, ký cert cho `<DOMAIN_WEB>`,
`<DOMAIN_API>`, `<DOMAIN_AUTH>`, `<DOMAIN_GRAFANA>`; phân phối CA root vào
trust store của máy người dùng và của cả 2 node.

> ⚠️ **Đặt lịch nhắc gia hạn cert.** Không có Let's Encrypt trong mạng cách ly.
> Cert hết hạn là sự cố toàn hệ, và là loại sự cố hoàn toàn tránh được. Đưa vào
> alert Prometheus (`blackbox_exporter` có metric `probe_ssl_earliest_cert_expiry`).

## 10. Nghiệm thu bước 2

- [ ] `docker compose ps` — mọi service `Up (healthy)`
- [ ] Tổng `mem_limit` ≤ 92g: `docker stats --no-stream`
- [ ] `docker inspect <ct> | grep OomScoreAdj` = -500 cho 3 service tầng dữ liệu
- [ ] OpenSearch: `_cluster/health` = `green`; `_nodes/jvm` báo heap **10GB**
- [ ] OpenSearch: `_cat/plugins` có `analysis-icu`
- [ ] OpenSearch k-NN: `_cat/plugins` có `opensearch-knn`; mapping index `segment` có field `knn_vector` 1024 chiều `mode: on_disk`; `_plugins/_knn/stats` không báo `circuit_breaker_triggered`
- [ ] backend gọi được `EMBEDDING_API_URL` (trả 1024 chiều)
- [ ] **pgBackRest**: `pgbackrest --stanza=main check` PASS;
      `SELECT * FROM pg_stat_archiver` không có `last_failed_time`
- [ ] **Test PITR thật**: `pgbackrest backup` → tạo bảng → restore về mốc trước
      đó trong sandbox → bảng không tồn tại
- [ ] SeaweedFS: upload/download 1 tệp qua S3 API, SHA-256 khớp
- [ ] RabbitMQ: queue đúng argument, **backend khởi động không gặp 406**
- [ ] Keycloak: đăng nhập được qua `<DOMAIN_AUTH>`, JWT có `iss` đúng
- [ ] TLS: cert hợp lệ, `probe_ssl_earliest_cert_expiry` có trong Prometheus
- [ ] Thử `docker compose restart <service>` từng cái — dữ liệu còn nguyên
- [ ] **Thử tắt nguồn đột ngột `node-app`** rồi bật lại: preflight qua,
      PostgreSQL recovery sạch, ZFS import tự động, mọi service tự lên
