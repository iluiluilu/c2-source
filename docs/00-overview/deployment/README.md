# Tài liệu triển khai hệ thống — Index

> Owner: SA/Infra · Ngày: 2026-09-16 · Status: **Draft**
> Cơ sở: [`../hardware-resource-allocation.md`](../hardware-resource-allocation.md)
> (đánh giá phân bổ tài nguyên + 5 quyết định kiến trúc đã chốt 2026-09-16).

## 0. Quyết định nền đã chốt

| Hạng mục | Quyết định |
|---|---|
| Nền tảng | **Docker Compose + systemd + Portainer CE**, air-gap |
| | *Kubernetes đã được cân nhắc lại và **loại** ngày 2026-09-16 — lý do đầy đủ ở [`../hardware-resource-allocation.md`](../hardware-resource-allocation.md) §3.6. Bản phác thảo k8s lưu ở `docs/_archive/2026-09-16-deployment-01-phuong-an-k8s.md`* |
| Topology | 2 project Compose độc lập: `node-app` (ứng dụng + dữ liệu), `node-ai` (worker AI + giám sát) |
| Lưu trữ stateful | **LUN của ME5284 qua iSCSI + multipath**, mount ở host |
| OpenSearch | **NVMe cục bộ** của `node-app` (T1 từ 2026-10-10 vì chứa vector: snapshot tuần ra NAS; toàn văn dựng lại ≤12h, vector re-embed tới 72h) |
| PostgreSQL PITR | **pgBackRest**, repo S3 trỏ SeaweedFS |
| API Gateway | **Kong** chế độ DB-less |
| Giám sát | Prometheus + Grafana + Alertmanager + Loki, **đặt trên `node-ai`** |
| GPU | 2× L4 24GB, ghim container vào từng card bằng `device_ids` |

> ⚠️ **Hệ này không có HA.** Hai node vật lý, mọi service stateful đều singleton.
> Mất `node-app` là dừng nghiệp vụ cho tới khi dựng lại. Cơ chế bảo vệ duy nhất
> mà cấu hình phần cứng này cho phép là **dựng lại nhanh** — đó là tiêu chí chi
> phối mọi quyết định trong bộ tài liệu này.

## 1. Thứ tự thực hiện

| # | Tài liệu | Nội dung | Phụ thuộc |
|---|---|---|---|
| 0 | [`00-chuan-bi-ha-tang.md`](00-chuan-bi-ha-tang.md) | OS, kernel, RAID1 NVMe, multipath/iSCSI, ZFS, driver NVIDIA | Phần cứng đã lắp, mạng đã đấu |
| 1 | [`01-nen-tang-docker.md`](01-nen-tang-docker.md) | Docker Engine, systemd unit, Portainer, mạng giữa 2 node, air-gap, bí mật | 0 |
| 2 | [`02-workload-ung-dung.md`](02-workload-ung-dung.md) | PostgreSQL+pgBackRest, OpenSearch, SeaweedFS, RabbitMQ, Keycloak, Redis, ClamAV, backend, web, Kong | 1 |
| 3 | [`03-workload-ai.md`](03-workload-ai.md) | embedding-service (Qwen3, nhóm AI), OCR, STT, video, ingest worker | 1, 2 (RabbitMQ + S3 phải sẵn) |
| 4 | [`04-giam-sat-va-log.md`](04-giam-sat-va-log.md) | Prometheus, Grafana, Alertmanager, Loki, exporter, SNMP, alert rule | 1 |
| 5 | [`05-don-rac-tu-dong.md`](05-don-rac-tu-dong.md) | systemd timer dọn rác, giới hạn log, prune snapshot ZFS | 2, 3 |
| 6 | [`06-sao-luu-khoi-phuc.md`](06-sao-luu-khoi-phuc.md) | backup-agent, lịch, 3 mức khôi phục | 2, 5 |
| 7 | [`07-runbook-su-co.md`](07-runbook-su-co.md) | Xử lý sự cố, thứ tự khởi động lại, diễn tập | 2–6 |

**Không nhảy cóc.** Mỗi tài liệu có mục "Nghiệm thu" ở cuối — phải qua hết mới
sang bước sau.

## 2. Bảng tham số đặc thù hiện trường — ĐIỀN TRƯỚC KHI BẮT ĐẦU

Mọi tài liệu dùng placeholder dạng `<TÊN_THAM_SỐ>`. Điền bảng này một lần, rồi
thay nhất quán. **Không commit giá trị thật của nhóm "bí mật" vào Git** — dùng
Sealed Secrets hoặc Vault (xem `01-cai-dat-cum-k8s.md` §7).

### 2.1. Mạng & máy chủ

| Tham số | Ý nghĩa | Giá trị |
|---|---|---|
| `<NODE_APP_HOSTNAME>` / `<NODE_APP_IP>` | Node ứng dụng (R760 48C) | |
| `<NODE_AI_HOSTNAME>` / `<NODE_AI_IP>` | Node AI (R760 64C, 2×L4) | |
| `<VLAN_NGHIEP_VU>` | VLAN người dùng/nghiệp vụ | |
| `<VLAN_ISCSI>` | VLAN riêng cho iSCSI (tách khỏi nghiệp vụ) | |
| `<DOCKER_BRIDGE_CIDR>` | Dải cho bridge Docker — **phải khác** mạng nội bộ (mặc định `172.17.0.0/16` hay xung đột) | |
| `<DNS_NOI_BO>` | DNS nội bộ phân giải các domain dưới | |

### 2.2. Domain nội bộ

| Tham số | Dịch vụ |
|---|---|
| `<DOMAIN_WEB>` | Giao diện web |
| `<DOMAIN_API>` | Backend API |
| `<DOMAIN_AUTH>` | Keycloak |
| `<DOMAIN_GRAFANA>` | Grafana |
| `<DOMAIN_HARBOR>` | Registry nội bộ |

> ⚠️ `<DOMAIN_AUTH>` phải là **cùng một URL** mà browser và backend đều dùng —
> lệch issuer là nguyên nhân lỗi đăng nhập đã gặp ở môi trường trước (xem
> `stg/docker-compose.yml`, comment ở service `keycloak`).

### 2.3. SAN / NAS

| Tham số | Ý nghĩa |
|---|---|
| `<SAN_PORTAL_A>` / `<SAN_PORTAL_B>` | 2 portal iSCSI (2 controller) của ME5284 |
| `<SAN_IQN>` | IQN của target trên ME5284 |
| `<IQN_NODE_APP>` / `<IQN_NODE_AI>` | IQN initiator của từng node |
| `<NAS_IP>` | IP của QNAP TS-h3087XU-RP |
| `<NAS_DATASET>` | Dataset đích nhận `zfs send` |

### 2.4. Bí mật (KHÔNG commit)

`<PG_PASSWORD>`, `<KEYCLOAK_ADMIN_PASSWORD>`, `<OPENSEARCH_ADMIN_PASSWORD>`,
`<RABBITMQ_PASSWORD>`, `<S3_ACCESS_KEY>` / `<S3_SECRET_KEY>`,
`<ZFS_ENCRYPTION_KEY>`, `<HARBOR_PASSWORD>`, `<GRAFANA_ADMIN_PASSWORD>`.

## 3. Còn chờ xác nhận

| Mã | Nội dung | Chặn phần nào |
|---|---|---|
| **Q5** | Duyệt bảng phân bổ `mem_limit`/`cpus` (mục 4.1/4.2 của tài liệu đánh giá) | `02`, `03` (đã viết theo bảng đề xuất) |
| **Q6** | Dung lượng dữ liệu ban đầu + tăng trưởng (TB, TB/tháng) | `06` §chính sách giữ bản sao |
| **Q7** | Số cổng 25GbE mỗi node, VLAN, dải IP, dải bridge Docker | `00`, `01` (placeholder §2) |
| **Q8** | Quy trình đưa image + model + chữ ký ClamAV qua air-gap; đã có registry nội bộ chưa | `01` §3 |
| **Q10** | OS + phiên bản, RAM của NAS, UPS, kênh cảnh báo Alertmanager | `00`, `04`, `06` |

> **Q4 (backup-agent) đã tự giải quyết** khi bỏ k8s: agent chạy systemd mức host
> đúng như `backup-restore.md` QĐ 4 (Approved) — không cần sửa tài liệu đã duyệt.

**Giả định đang dùng khi viết** (sửa lại nếu Q10 trả lời khác):
**Ubuntu Server 24.04 LTS**, Docker Engine từ repo chính thức, không dùng `ufw`
(dùng `iptables`/`nftables` trực tiếp — xem `00` §7).

## 4. Việc thuộc backlog DEV, không thuộc tài liệu này

- Thêm `spring-boot-starter-actuator` + `micrometer-registry-prometheus` vào
  `backend/pom.xml` — không có thì **không có metric ứng dụng** và **không có
  `/actuator/health` cho liveness/readiness probe**.
- Implement tối thiểu bảng `backup_run` để gỡ `trash-purge` khỏi trạng thái
  fail-closed vĩnh viễn.
- Sửa `ai-search-infra.md`, `digitization-pipeline.md`, `so do vat ly.adoc` cho
  khớp GPU thật (2× L4 24GB) và kiểm kê phần cứng thật.
