# 01 — Nền tảng Docker, systemd, mạng giữa 2 node

> Phụ thuộc: [`00-chuan-bi-ha-tang.md`](00-chuan-bi-ha-tang.md) đã nghiệm thu.
> Thực hiện trên **cả 2 node** trừ khi ghi rõ.

## 1. Topology

```
node-app  <NODE_APP_IP>   R760 48C/96T · 128GB · 2×3.84TB NVMe
  /opt/aismartstore/app/docker-compose.yml
    Kong · web · backend · Keycloak · PostgreSQL(+pgBackRest) · OpenSearch
    SeaweedFS · RabbitMQ · Redis · ClamAV · autoheal · exporter
  Mount: /srv/pgdata /srv/backup-stage (LUN SAN)
         /tank/object (ZFS trên LUN SAN HDD)
         /var/lib/local-pv/opensearch (NVMe RAID1)

node-ai   <NODE_AI_IP>    R760 64C/128T · 128GB · 2× L4 24GB · 2×3.84TB NVMe
  /opt/aismartstore/ai/docker-compose.yml
    embedding-service (Qwen3-Embedding-0.6B, nhóm AI vận hành) · ocr-worker-0/1 · stt-worker · video-worker
    tika · ingest-worker · autoheal
  /opt/aismartstore/monitoring/docker-compose.yml
    Prometheus · Grafana · Alertmanager · Loki · exporter
```

**Hai project Compose độc lập, không có scheduler.** Điều này là có chủ ý: mỗi
node có một tập service cố định, biết trước, không có gì "tự chuyển chỗ". Đổi
lại tính linh hoạt, ta được tính tiên đoán — thứ quan trọng hơn với hệ thống
này (xem [`../hardware-resource-allocation.md`](../hardware-resource-allocation.md) §3.6).

## 2. Docker Engine

```bash
# Cai tu goi .deb da mang qua air-gap (khong dung script get.docker.com)
sudo dpkg -i containerd.io_*.deb docker-ce_*.deb docker-ce-cli_*.deb \
             docker-buildx-plugin_*.deb docker-compose-plugin_*.deb
```

```json
// /etc/docker/daemon.json
{
  "log-driver": "json-file",
  "log-opts": { "max-size": "10m", "max-file": "5" },
  "default-address-pools": [
    { "base": "<DOCKER_BRIDGE_CIDR>", "size": 24 }
  ],
  "live-restore": true,
  "storage-driver": "overlay2",
  "default-ulimits": {
    "nofile": { "Name": "nofile", "Soft": 1048576, "Hard": 1048576 }
  }
}
```

> ⚠️ **`log-opts` ở đây là lưới an toàn quan trọng nhất của file này.** Docker
> mặc định **không giới hạn** dung lượng log. Đây chính là nguyên nhân sự cố
> thật đã xảy ra: log backend phình **72GB** làm đầy đĩa, khiến PostgreSQL báo
> `FATAL: could not write init file`. Đặt ở `daemon.json` nghĩa là **mọi**
> service — kể cả service thêm sau này mà ai đó quên khai `logging:` — đều dính
> giới hạn.

> ⚠️ **`default-address-pools`**: Docker mặc định dùng `172.17.0.0/16`. Nếu mạng
> nội bộ của đơn vị trùng dải này thì container sẽ không gọi được ra ngoài, và
> triệu chứng rất khó đoán. Chọn `<DOCKER_BRIDGE_CIDR>` không trùng — hỏi đội
> mạng trước.

> **`live-restore: true`**: container tiếp tục chạy khi daemon Docker restart
> (ví dụ lúc nâng cấp Docker). Với hệ không có HA, đây là thứ tránh được một
> lần downtime không đáng có.

```bash
sudo systemctl enable --now docker
docker info | grep -E "Storage Driver|Logging Driver|Live Restore"
```

## 3. Đưa image qua air-gap

Với Compose thì **không bắt buộc** phải có registry nội bộ — `docker save/load`
là đủ. (Đây là một trong những khác biệt thực tế lớn nhất so với k8s.)

```bash
# Tren may co Internet
docker pull postgres:16
docker save postgres:16 | zstd -o postgres-16.tar.zst
sha256sum postgres-16.tar.zst > postgres-16.sha256

# Sau khi mang qua, tren node dich
sha256sum -c postgres-16.sha256
zstd -d -c postgres-16.tar.zst | docker load
```

Nếu đã có **Harbor/GitLab Registry** nội bộ thì vẫn nên dùng — quản lý phiên bản
và rollback dễ hơn nhiều so với một thư mục đầy file `.tar.zst`.

**Danh sách vật tư cần mang qua** (giữ một file `manifest.txt` có checksum):

| Nhóm | Nội dung |
|---|---|
| Image hạ tầng | postgres, pgbackrest, opensearch, seaweedfs, rabbitmq, keycloak, redis, clamav, kong, nginx |
| Image ứng dụng | `backend`, `web` (build nội bộ) |
| Image AI | embedding-service (nhóm AI cung cấp), ocr-worker, stt-worker, video-worker, tika |
| Image giám sát | prometheus, grafana, alertmanager, loki, promtail, các exporter, cadvisor, autoheal, portainer |
| Trọng số model | PaddleOCR, VietOCR, PhoWhisper-large, Qwen3-Embedding-0.6B (~8–12GB; thay bge-m3 2026-10-10; không còn bge-reranker-v2-m3 — bỏ 2026-10-08) |
| Chữ ký ClamAV | `.cvd` — cập nhật định kỳ theo lịch air-gap |

> **Ghim tag tuyệt đối, không dùng `latest`.** Trong môi trường air-gap, `latest`
> nghĩa là "cái gì đó đã load lần gần nhất" — không ai biết là gì sau 6 tháng.
> Tốt nhất là ghim theo digest (`image@sha256:...`).

## 4. Bố trí thư mục và Git

```
/opt/aismartstore/
├── app/                      # project Compose cua node-app
│   ├── docker-compose.yml
│   ├── .env                  # KHONG commit
│   └── config/               # postgres/, opensearch/, kong/, clamav/, seaweedfs/
├── ai/                       # project Compose cua node-ai
├── monitoring/
└── scripts/                  # backup-agent, don rac
```

**Toàn bộ thư mục này nằm trong Git** (trừ `.env` và bí mật), triển khai bằng
**Ansible**. Đây là cơ chế rollback cấu hình: `git revert` + chạy lại playbook.

> Với hệ không có HA, khả năng **dựng lại từ Git trong vài phút** là thứ thay
> thế cho việc "cụm tự phục hồi". Cho nên: không sửa cấu hình trực tiếp trên
> server. Mọi thay đổi đi qua Git.

## 5. Mạng giữa 2 node — điểm cần cẩn thận nhất

Không còn mạng phẳng. Worker AI trên `node-ai` phải gọi được 3 service trên
`node-app`: RabbitMQ, SeaweedFS S3, OpenSearch.

```yaml
# Trong docker-compose.yml cua node-app: bind vao IP noi bo, KHONG 0.0.0.0
services:
  rabbitmq:
    ports: ["<NODE_APP_IP>:5672:5672"]
  seaweedfs:
    ports: ["<NODE_APP_IP>:8333:8333"]
  opensearch:
    ports: ["<NODE_APP_IP>:9200:9200"]
```

Trên `node-ai`, worker trỏ tới `<NODE_APP_IP>:<port>`.

Chiều ngược lại (sửa 2026-10-10): `backend` trên `node-app` gọi
`embedding-service` trên `node-ai` qua env `EMBEDDING_API_URL`
(`http://<NODE_AI_IP>:<port>/...`, bind IP nội bộ, không xác thực ⇒ chỉ mở cho
`<NODE_APP_IP>`). Qdrant đã gỡ khỏi hạ tầng — không còn cổng 6333/6334.

> ⚠️ **BÀI HỌC ĐÃ TRẢ GIÁ — đọc kỹ.** `ufw` **không** lọc được cổng do Docker
> `ports:` publish. Docker chèn rule NAT/FORWARD ở chain `DOCKER-USER`/`DOCKER`,
> đứng **trước** và bỏ qua chain `INPUT` mà `ufw` quản. Bật `ufw allow`/`deny`
> cho các cổng này chỉ là **ảo giác an toàn** — IP ngoài whitelist vẫn kết nối
> được bình thường. Đã verify thực tế.

Phải chèn rule thẳng vào `DOCKER-USER`:

```bash
# Chi cho <NODE_AI_IP> goi 4 cong nay
for port in 5672 8333 6333 6334 9200; do
  sudo iptables -I DOCKER-USER 1 -p tcp --dport $port -s <NODE_AI_IP> -j RETURN
  sudo iptables -A DOCKER-USER   -p tcp --dport $port -j DROP
done
sudo netfilter-persistent save
sudo iptables -L DOCKER-USER -n --line-numbers   # RETURN phai TRUOC DROP
```

**Kiểm chứng bằng cách thử thật**, không tin vào việc "đã thêm rule":

```bash
# Tu mot may KHAC node-ai trong cung VLAN - phai timeout
nc -zv -w3 <NODE_APP_IP> 5672
```

> `iptables-persistent` **xung đột package với `ufw`** — cài cái này thì apt tự
> gỡ `ufw`. Đó là hành vi mong muốn. Đừng cài lại `ufw`.

## 6. systemd unit — tự khởi động sau reboot

```ini
# /etc/systemd/system/aismartstore-app.service  (node-app)
[Unit]
Description=AI SmartStore - tang ung dung va du lieu
Requires=docker.service
After=docker.service zfs.target iscsi.service remote-fs.target
# iscsi/zfs phai san sang TRUOC: PostgreSQL va SeaweedFS doc du lieu tu do

[Service]
Type=oneshot
RemainAfterExit=yes
WorkingDirectory=/opt/aismartstore/app
ExecStartPre=/usr/local/bin/preflight-app.sh
ExecStart=/usr/bin/docker compose up -d --remove-orphans
ExecStop=/usr/bin/docker compose stop
TimeoutStartSec=900

[Install]
WantedBy=multi-user.target
```

```bash
#!/bin/bash
# /usr/local/bin/preflight-app.sh - CHAN khoi dong khi du lieu chua san sang.
# Khoi dong PostgreSQL voi thu muc rong (vi LUN chua mount) la kich ban
# nguy hiem: no se initdb mot database rong len cho cua database that.
set -e
mountpoint -q /srv/pgdata      || { echo "LUN-PG chua mount"; exit 1; }
zfs get -H -o value keystatus tank/object | grep -q available \
  || { echo "tank/object chua load-key"; exit 1; }
```

> ⚠️ **`preflight-app.sh` không phải thủ tục thừa.** Nếu `docker compose up`
> chạy khi LUN chưa mount, Docker sẽ tạo thư mục rỗng ở `/srv/pgdata` và
> PostgreSQL sẽ `initdb` một database trắng lên đó. Khi LUN mount lại sau, dữ
> liệu thật vẫn còn nhưng hệ đang chạy trên database rỗng — rất dễ nhầm là "mất
> dữ liệu". Chặn từ đầu rẻ hơn nhiều so với xử lý hậu quả.

Tương tự cho `aismartstore-ai.service` và `aismartstore-monitoring.service`.

```bash
sudo systemctl enable aismartstore-app.service
```

## 7. `autoheal` — bù khoảng trống lớn nhất của Compose

`restart: unless-stopped` chỉ kích hoạt khi tiến trình **thoát hẳn**. Service
**treo** (deadlock, GC thrash, mất kết nối DB mà không chết) sẽ treo mãi. Đây là
điểm k8s hơn Compose, và `autoheal` là cách đóng khoảng cách đó:

```yaml
  autoheal:
    image: willfarrell/autoheal:latest
    restart: unless-stopped
    environment:
      AUTOHEAL_CONTAINER_LABEL: autoheal      # chi restart container co nhan nay
      AUTOHEAL_INTERVAL: "15"
      AUTOHEAL_START_PERIOD: "300"            # bo qua 5 phut dau sau khi khoi dong
    volumes:
      - /var/run/docker.sock:/var/run/docker.sock
    mem_limit: 128m
```

Gắn nhãn cho service muốn được tự chữa:

```yaml
  backend:
    labels: { autoheal: "true" }
    healthcheck:
      test: ["CMD-SHELL", "curl -fsS http://localhost:3001/actuator/health || exit 1"]
      interval: 30s
      timeout: 10s
      retries: 3
      start_period: 120s
```

> ⚠️ **Cân nhắc kỹ trước khi gắn `autoheal` cho tầng dữ liệu.** PostgreSQL và
> OpenSearch khi khởi động lại sau sự cố có thể mất vài phút để recovery. Nếu
> `healthcheck` quá gắt, `autoheal` sẽ giết pod giữa chừng và tạo vòng lặp khởi
> động vô tận — biến sự cố nhỏ thành sự cố lớn. Với 3 service này: đặt
> `start_period` rất rộng (600s), `retries` cao (10), hoặc **không** gắn nhãn
> `autoheal` và để con người xử lý.

> ⚠️ `autoheal` cần `docker.sock` ⇒ có quyền tương đương root trên host. Chấp
> nhận được trong VLAN cách ly, nhưng phải biết rõ điều đó.

## 8. Portainer CE — quản trị trực quan

```yaml
  portainer:
    image: portainer/portainer-ce:2.21.4
    restart: unless-stopped
    ports: ["<NODE_APP_IP>:9443:9443"]
    volumes:
      - /var/run/docker.sock:/var/run/docker.sock
      - portainer_data:/data
    mem_limit: 512m
```

Cho R3 (quản trị hệ thống) xem log, restart service, xem tài nguyên container
mà không cần SSH và không cần biết lệnh `docker`. Thêm `node-ai` làm environment
thứ hai qua Portainer Agent.

## 9. Quản lý bí mật

Không commit secret vào Git. Hai lựa chọn, chọn theo mức độ sẵn có của đội:

| Cách | Khi nào |
|---|---|
| **`.env` với `chmod 600`** + ghi lại vị trí trong tài liệu bàn giao | Đơn giản nhất, đủ cho VLAN cách ly có kiểm soát truy cập vật lý |
| **`sops` + `age`** — file mã hoá commit được vào Git, giải mã lúc deploy | Tốt hơn nếu có nhiều người cùng chạm vào repo |

```bash
chmod 600 /opt/aismartstore/app/.env
chown root:root /opt/aismartstore/app/.env
```

> ⚠️ **Khoá mã hoá ZFS** (`00-chuan-bi-ha-tang.md` §5) **không** thuộc nhóm này
> — nó phải được **ký gửi ngoại tuyến**, không nằm trong Git dù đã mã hoá. Mất
> khoá đó là mất toàn bộ khả năng khôi phục từ NAS.

## 10. Nghiệm thu bước 1

- [ ] `docker info` báo đúng log driver, address pool, live-restore
- [ ] `docker compose version` ≥ v2
- [ ] **Thử reboot cả 2 node**: iSCSI login → ZFS import → `preflight` qua →
      service tự lên, không can thiệp tay
- [ ] **Thử `preflight-app.sh` khi chưa mount LUN**: phải **chặn** khởi động
- [ ] Từ `node-ai`: `nc -zv <NODE_APP_IP> 5672` **thành công**
- [ ] Từ máy khác trong VLAN: `nc -zv <NODE_APP_IP> 5672` **timeout**
      (chứng tỏ rule `DOCKER-USER` có tác dụng thật)
- [ ] `iptables -L DOCKER-USER -n --line-numbers`: mọi `RETURN` đứng **trước**
      `DROP` của cùng cổng
- [ ] Reboot rồi kiểm tra lại firewall — rule còn sống (`netfilter-persistent`)
- [ ] **Test `autoheal`**: gửi `SIGSTOP` cho tiến trình trong một container có
      nhãn → healthcheck chuyển `unhealthy` → autoheal restart trong ≤60s
- [ ] Portainer truy cập được, thấy cả 2 node
- [ ] `git status` trong `/opt/aismartstore` sạch (không sửa trực tiếp trên server)
