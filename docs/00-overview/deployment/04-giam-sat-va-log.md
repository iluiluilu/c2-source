# 04 — Giám sát, log tập trung & cảnh báo

> Phụ thuộc: [`01-nen-tang-docker.md`](01-nen-tang-docker.md).
> Project: `/opt/aismartstore/monitoring/docker-compose.yml`
> **Toàn bộ stack đặt trên `node-ai`.**

## 1. Vì sao đặt trên `node-ai`

Khi `node-app` gặp sự cố — chính là lúc cần dashboard nhất — thì dashboard đặt ở
đó cũng chết theo, và không ai biết vì sao. `node-ai` dư CPU/RAM và là "con mắt"
độc lập.

Với hệ **không có HA**, đây không phải chi tiết nhỏ: khi `node-app` chết, thứ
duy nhất còn nói cho bạn biết chuyện gì đang xảy ra là stack giám sát ở node
kia. Nó phải sống sót.

## 2. Thay đổi so với tài liệu đánh giá công nghệ

`danh_gia_lua_chon_cong_nghe.adoc` §4.11 chọn **Fluent Bit → chính cụm
OpenSearch nghiệp vụ** với lý do "giảm số thành phần". **Tài liệu này đổi sang
Loki + Promtail**, vì đánh đổi đó sai ở ba điểm:

1. **Tranh tài nguyên.** Log spike sẽ tranh heap/IOPS với chỉ mục tìm kiếm nghiệp
   vụ. Đây không phải lo xa: đã có sự cố thật, log backend phình **72GB** do một
   scheduled job lỗi liên tục, làm đầy đĩa và khiến PostgreSQL báo
   `FATAL: could not write init file`.
2. **Sai phân tầng sao lưu.** OpenSearch nghiệp vụ ở tầng T1 (`backup-restore.md`
   QĐ 1, sửa 2026-10-10 — chứa vector). Trộn log vào làm lẫn dữ liệu có chính sách giữ khác nhau.
3. **Cùng chết một chỗ.** OpenSearch nghiệp vụ nằm trên `node-app`; node chết là
   mất luôn log cần để chẩn đoán.

Loki có footprint RAM/đĩa nhỏ hơn hẳn, Grafana đã có sẵn, `LogQL` đủ cho chẩn
đoán vận hành. Việc "giảm số thành phần" vẫn đạt vì Loki + Promtail nhẹ hơn một
cụm OpenSearch riêng.

## 3. Prometheus + Grafana + Alertmanager

```yaml
  prometheus:
    <<: *common
    image: prom/prometheus:v2.54.1
    command:
      - --config.file=/etc/prometheus/prometheus.yml
      - --storage.tsdb.path=/prometheus
      - --storage.tsdb.retention.time=90d
      - --storage.tsdb.retention.size=200GB    # chan tuyet doi, khong de dia day
      - --web.enable-lifecycle
    mem_limit: 8g
    cpus: "4"
    volumes:
      - /var/lib/local-pv/monitoring/prometheus:/prometheus
      - ./config/prometheus:/etc/prometheus:ro

  grafana:
    <<: *common
    image: grafana/grafana:11.2.0
    mem_limit: 2g
    cpus: "2"
    environment:
      GF_SECURITY_ADMIN_PASSWORD: ${GRAFANA_ADMIN_PASSWORD}
      GF_USERS_ALLOW_SIGN_UP: "false"
      GF_INSTALL_PLUGINS: ""                   # air-gap: khong tai plugin online
    volumes:
      - /var/lib/local-pv/monitoring/grafana:/var/lib/grafana
      - ./config/grafana/provisioning:/etc/grafana/provisioning:ro
      - ./config/grafana/dashboards:/var/lib/grafana/dashboards:ro

  alertmanager:
    <<: *common
    image: prom/alertmanager:v0.27.0
    mem_limit: 512m
    volumes:
      - ./config/alertmanager:/etc/alertmanager:ro
```

> `retention.size=200GB` là chốt an toàn: `retention.time=90d` một mình **không**
> chặn được đĩa đầy nếu số series tăng đột biến. Luôn đặt cả hai.

> **Dashboard và datasource khai báo bằng file provisioning trong Git**, không
> cấu hình bằng tay trên UI — cấu hình tay sẽ mất khi container tạo lại, và
> không rollback được.

Scrape config tĩnh — hệ chỉ có ~15 target cố định, không cần service discovery:

```yaml
# config/prometheus/prometheus.yml
global: { scrape_interval: 30s, evaluation_interval: 30s }
rule_files: ["/etc/prometheus/rules/*.yml"]
alerting:
  alertmanagers: [{ static_configs: [{ targets: ["alertmanager:9093"] }] }]
scrape_configs:
  - job_name: node
    static_configs:
      - targets: ["<NODE_APP_IP>:9100", "<NODE_AI_IP>:9100"]
  - job_name: cadvisor
    static_configs:
      - targets: ["<NODE_APP_IP>:8080", "<NODE_AI_IP>:8080"]
  - job_name: postgres
    static_configs: [{ targets: ["<NODE_APP_IP>:9187"] }]
  - job_name: opensearch
    static_configs: [{ targets: ["<NODE_APP_IP>:9114"] }]
  - job_name: rabbitmq
    static_configs: [{ targets: ["<NODE_APP_IP>:15692"] }]
  - job_name: redis
    static_configs: [{ targets: ["<NODE_APP_IP>:9121"] }]
  - job_name: kong
    static_configs: [{ targets: ["<NODE_APP_IP>:8100"] }]
  - job_name: dcgm
    static_configs: [{ targets: ["dcgm-exporter:9400"] }]
  - job_name: blackbox
    metrics_path: /probe
    params: { module: [http_2xx] }
    static_configs:
      - targets: ["https://<DOMAIN_WEB>", "https://<DOMAIN_API>", "https://<DOMAIN_AUTH>"]
    relabel_configs:
      - { source_labels: [__address__], target_label: __param_target }
      - { source_labels: [__param_target], target_label: instance }
      - { target_label: __address__, replacement: "blackbox-exporter:9115" }
  - job_name: snmp                              # SAN, NAS, switch
    static_configs:
      - targets: ["<SAN_MGMT_IP>", "<NAS_IP>", "<SWITCH_IP_1>", "<SWITCH_IP_2>"]
    metrics_path: /snmp
    params: { module: [if_mib] }
    relabel_configs:
      - { source_labels: [__address__], target_label: __param_target }
      - { source_labels: [__param_target], target_label: instance }
      - { target_label: __address__, replacement: "snmp-exporter:9116" }
```

## 4. Loki + Promtail

```yaml
  loki:
    <<: *common
    image: grafana/loki:3.1.1
    command: ["-config.file=/etc/loki/loki.yml"]
    mem_limit: 8g
    cpus: "4"
    volumes:
      - /var/lib/local-pv/monitoring/loki:/loki
      - ./config/loki/loki.yml:/etc/loki/loki.yml:ro
```

```yaml
# config/loki/loki.yml
limits_config:
  retention_period: 720h              # 30 ngay
  ingestion_rate_mb: 20
  ingestion_burst_size_mb: 40         # chan log spike lam day dia
  max_streams_per_user: 10000
compactor:
  working_directory: /loki/compactor
  retention_enabled: true             # BAT BUOC - xem canh bao duoi
  delete_request_store: filesystem
```

> ⚠️ `compactor.retention_enabled: true` là dòng dễ quên nhất. Thiếu nó, Loki
> **không bao giờ xoá** log cũ dù đã khai `retention_period` — đĩa đầy sau vài
> tháng.

**Promtail chạy ở cả 2 node** (một container trong mỗi project Compose):

```yaml
  promtail:
    <<: *common
    image: grafana/promtail:3.1.1
    command: ["-config.file=/etc/promtail/promtail.yml"]
    mem_limit: 512m
    volumes:
      - /var/lib/docker/containers:/var/lib/docker/containers:ro
      - /var/run/docker.sock:/var/run/docker.sock:ro
      - ./config/promtail/promtail.yml:/etc/promtail/promtail.yml:ro
```

```yaml
# config/promtail/promtail.yml - dung docker_sd de co nhan tu container
scrape_configs:
  - job_name: docker
    docker_sd_configs:
      - host: unix:///var/run/docker.sock
        refresh_interval: 15s
    relabel_configs:
      - { source_labels: ['__meta_docker_container_name'], target_label: 'container' }
      - { source_labels: ['__meta_docker_container_label_com_docker_compose_project'], target_label: 'project' }
      - { target_label: 'node', replacement: '<NODE_APP_HOSTNAME>' }
    pipeline_stages:
      - json: { expressions: { level: level, trace_id: trace_id } }
      - labels: { level: "", trace_id: "" }
```

> Promtail trên `node-ai` gửi về Loki qua `localhost`; Promtail trên `node-app`
> gửi qua `<NODE_AI_IP>:3100` — nhớ mở cổng này trong `DOCKER-USER` **theo chiều
> ngược lại** so với §5 của `01-nen-tang-docker.md`.

### 4.1. Yêu cầu về log của ứng dụng

- **Log có cấu trúc (JSON)** với `trace_id` xuyên suốt
  Kong → backend → RabbitMQ → AI worker. Không có `trace_id` thì việc truy "một
  tài liệu đi đâu mất" phải dò tay qua 4 hệ.
- **Cấm ghi nội dung văn bản đã nhận dạng vào log** — `digitization-pipeline.md`
  §3 (Security). Kiểm tra lại khi cấu hình pipeline Promtail.
- Giới hạn log Docker đã đặt ở `01-nen-tang-docker.md` §2 (`max-size=10m`,
  `max-file=5`, đặt trong `/etc/docker/daemon.json` để **mọi** service đều dính,
  kể cả service thêm sau mà ai đó quên khai `logging:`).

## 5. Exporter — danh sách đầy đủ

| Exporter | Đối tượng | Cách cài |
|---|---|---|
| `node-exporter` | CPU/RAM/đĩa/nhiệt độ cả 2 node | Container ở **cả 2** project |
| **cAdvisor** | Tài nguyên + trạng thái health từng container | Container riêng ở **cả 2** node — Compose không có sẵn như kubelet |
| `dcgm-exporter` | **GPU**: VRAM, tải, nhiệt độ, ECC | Container trên `node-ai`, cần `--gpus all` |
| `postgres_exporter` | PostgreSQL | Container cạnh `postgres` |
| `redis_exporter` | Redis | Container riêng |
| `opensearch-exporter` | OpenSearch | Container riêng |
| k-NN stats OpenSearch (`_plugins/_knn/stats`: bộ nhớ native, `circuit_breaker_triggered`, số truy vấn) | OpenSearch | Qua `opensearch-exporter` hoặc script; thay `/metrics` của Qdrant (đã gỡ 2026-10-10) |
| `rabbitmq_prometheus` | RabbitMQ (độ sâu queue, DLQ) | Plugin built-in |
| Kong `/metrics` | Gateway: RPS, độ trễ, mã lỗi | Plugin `prometheus` (bật ở `02` §8) |
| Keycloak metrics | Đăng nhập, phiên | `KC_METRICS_ENABLED=true` |
| `blackbox_exporter` | Probe tổng hợp từ ngoài vào (web/api/auth) | Deployment riêng |
| **`snmp_exporter`** | **SAN ME5284, NAS QNAP, switch lõi** | Deployment riêng |
| ZFS metrics | Pool `tank`: dung lượng, lỗi checksum, scrub | Có trong `node-exporter` |

> ⚠️ **`snmp_exporter` là mục dễ bị bỏ sót nhất và quan trọng nhất.** SAN, NAS
> và switch hiện **không nằm trong bất kỳ kế hoạch giám sát nào** của các tài
> liệu trước. Hỏng một đĩa trong SAN mà không ai biết, tới khi hỏng đĩa thứ hai
> trong cùng nhóm RAID6 thì mất dữ liệu — đây là rủi ro lớn nhất của cả hệ và
> nó hoàn toàn im lặng nếu không giám sát.

> ⚠️ **Backend chưa có metric.** `backend/pom.xml` hiện **không có**
> `spring-boot-starter-actuator` + `micrometer-registry-prometheus`. Trước khi
> bổ sung, không có: độ trễ endpoint, connection pool, độ sâu job nội bộ — và
> cũng không có `/actuator/health` cho `healthcheck`, nên `autoheal` **không**
> phát hiện được backend treo. Xem `README.md` §4.

## 6. Dashboard Grafana cần có

| Dashboard | Nội dung chính | Phục vụ |
|---|---|---|
| **Tổng quan hệ thống** | Trạng thái 2 node, pod lỗi, dung lượng 4 tầng lưu trữ, cảnh báo đang mở | Xem đầu ca |
| **Tầng dữ liệu** | PostgreSQL (TPS, độ trễ, connection, độ trễ WAL), OpenSearch (heap, GC, độ trễ truy vấn, bộ nhớ k-NN) | NFR-01 |
| **GPU** | VRAM/tải/nhiệt độ từng card, thông lượng OCR/STT | `03` §1, NFR thông lượng |
| **Hàng đợi** | Độ sâu `q.ocr`/`q.stt`/`q.video`/`q.embed`, **số message trong DLQ**, tuổi message cũ nhất | `digitization-pipeline.md` QĐ 2 |
| **Tìm kiếm** | p95/p99 toàn văn và ngữ nghĩa, tỉ lệ lỗi | NFR p95 ≤1s / ≤3s |
| **Sao lưu** | Lần sao lưu gần nhất, trạng thái, dung lượng NAS, tuổi snapshot | FR-34/35 |
| **Container** | Trạng thái `healthy`/`unhealthy`, số lần restart, số lần `autoheal` can thiệp | Thay cho dashboard pod của k8s |
| **Lưu trữ vật lý** | SNMP: đĩa SAN/NAS, trạng thái controller, nhiệt độ, lỗi ZFS checksum | Rủi ro im lặng |

Đặt file JSON dashboard trong `config/grafana/dashboards/` + file provisioning,
tất cả trong Git, để được nạp tự động — **không** cấu hình bằng tay trên UI (mất
khi container tạo lại, và không rollback được).

## 7. Alert rule tối thiểu

```yaml
groups:
  - name: ha-tang
    rules:
      - alert: NodeDown
        expr: up{job="node-exporter"} == 0
        for: 2m
        labels: { severity: critical }

      - alert: DungLuongSapDay
        # Ap cho MOI tang: NVMe, SAN SSD, SAN HDD, NAS
        expr: (node_filesystem_avail_bytes / node_filesystem_size_bytes) < 0.30
        for: 10m
        labels: { severity: warning }
        annotations: { summary: "{{ $labels.mountpoint }} con <30% (nguong canh bao 70% da dung)" }

      - alert: DungLuongNguyKich
        expr: (node_filesystem_avail_bytes / node_filesystem_size_bytes) < 0.15
        for: 5m
        labels: { severity: critical }

      - alert: ZfsLoiChecksum
        # ZFS tren LUN RAID phan cung: PHAT HIEN duoc nhung KHONG tu sua duoc
        # => canh bao nay la critical, phai khoi phuc file tu NAS bang tay
        expr: increase(node_zfs_zpool_cksum_errors[1h]) > 0
        labels: { severity: critical }

      - alert: ContainerBiOomKill
        expr: increase(container_oom_events_total[15m]) > 0
        labels: { severity: critical }
        annotations: { summary: "Container bi OOM-kill - kiem tra mem_limit va oom_score_adj" }

      - alert: ContainerRestartLienTuc
        # Dau hieu vong lap khoi dong - thuong do healthcheck qua gat
        expr: increase(container_start_time_seconds[30m]) > 3
        labels: { severity: critical }

      - alert: ContainerUnhealthy
        expr: container_last_seen{health_status="unhealthy"} > 0
        for: 5m
        labels: { severity: warning }
        annotations: { summary: "Container unhealthy - autoheal se restart; lap lai thi co van de that" }

      - alert: CertSapHetHan
        # Mang cach ly khong co Let's Encrypt - cert het han la su co toan he
        expr: (probe_ssl_earliest_cert_expiry - time()) / 86400 < 30
        labels: { severity: warning }

  - name: du-lieu
    rules:
      - alert: PostgresWalKhongArchive
        # Truc tiep de doa RPO 15 phut
        expr: pg_stat_archiver_last_failed_time > pg_stat_archiver_last_archived_time
        for: 5m
        labels: { severity: critical }

      - alert: OpenSearchHeapCao
        expr: opensearch_jvm_mem_heap_used_percent > 85
        for: 10m
        labels: { severity: warning }

      - alert: SaoLuuKhongChay
        # Gate cua trash-purge phu thuoc dieu nay
        expr: time() - backup_last_success_timestamp > 93600   # 26 gio
        labels: { severity: critical }

  - name: hang-doi
    rules:
      - alert: DlqCoMessage
        expr: rabbitmq_queue_messages{queue=~".*\\.dlq"} > 0
        for: 5m
        labels: { severity: warning }
        annotations: { summary: "Co job that bai vinh vien - xem runbook DLQ" }

      - alert: HangDoiUnDong
        expr: rabbitmq_queue_messages{queue=~"q\\..*"} > 10000
        for: 30m
        labels: { severity: warning }

  - name: gpu
    rules:
      - alert: VramSapDay
        # Time-slicing KHONG cach ly VRAM: mot tien trinh tran keo ca card chet
        expr: DCGM_FI_DEV_FB_USED / DCGM_FI_DEV_FB_TOTAL > 0.85
        for: 5m
        labels: { severity: critical }

      - alert: GpuQuaNong
        expr: DCGM_FI_DEV_GPU_TEMP > 85
        for: 5m
        labels: { severity: warning }

      - alert: EmbedChamHonNfr
        # Co so de quyet dinh tat co ai_queue.gpu0_batch_enabled (xem 03 §1.2)
        # Do phia backend (EmbeddingClient, ten metric chot o SPEC-search-semantic muc 7);
        # tei-embed da thay bang embedding-service 2026-10-10
        expr: histogram_quantile(0.95, rate(<embedding_client_latency>_seconds_bucket[5m])) > 0.15
        for: 10m
        labels: { severity: warning }
```

## 8. Kênh cảnh báo

⚠️ **Chưa chốt (Q10).** Mạng cách ly hoàn toàn nên không dùng được kênh ngoài
(Telegram/Slack/email Internet). Ba lựa chọn:

| Phương án | Ghi chú |
|---|---|
| **SMTP nội bộ** | Tốt nhất nếu đơn vị đã có mail server nội bộ |
| **Webhook tới hệ thống trực ban nội bộ** | Nếu có |
| **Chỉ hiển thị Grafana + màn hình NOC** | ⚠️ Cảnh báo dung lượng/hỏng đĩa sẽ **không ai thấy ngoài giờ** — chỉ chấp nhận được nếu có người trực |

Cấu hình tạm thời (chỉ ghi vào Alertmanager UI) cho tới khi Q10 có lời giải —
**và phải ghi vào runbook rằng phải kiểm tra Alertmanager thủ công mỗi ngày**.

## 9. Nghiệm thu bước 4

- [ ] `docker compose ps` trong `monitoring/` — mọi service `Up (healthy)`
      **trên `node-ai`**
- [ ] Grafana truy cập được qua `<DOMAIN_GRAFANA>`, đăng nhập được
- [ ] `Status → Targets` của Prometheus: **mọi** target `UP` (kể cả SNMP của
      SAN/NAS/switch)
- [ ] Log của cả 2 node đến được Loki, lọc theo `namespace`/`pod`/`level` được
- [ ] Đủ 8 dashboard ở §6, nạp tự động từ file provisioning trong Git
- [ ] **Test cảnh báo thật:** tạo file lớn cho đĩa vượt ngưỡng → alert bắn đúng
      kênh trong ≤10 phút. *Alert chưa từng bắn thử = alert không tồn tại.*
- [ ] **Tắt `node-app` → Grafana vẫn truy cập được và báo `NodeDown`** — đây là
      lý do đặt stack ở `node-ai`, và là bài test bắt buộc, không phải tuỳ chọn
- [ ] `docker logs` của một worker AI: **không** chứa nội dung văn bản nhận dạng
