# 07 — Runbook sự cố

> Dùng khi hệ thống đang có vấn đề. Viết để đọc lúc 2 giờ sáng — mỗi mục có
> **dấu hiệu → chẩn đoán → xử lý**, không giải thích dài.

## 0. Ba quy tắc trước khi làm bất cứ điều gì

1. **Không xoá dữ liệu để "giải phóng dung lượng" khi đang hoảng.** Đặc biệt:
   không `zfs destroy` snapshot, không `DROP PARTITION` bảng `audit_log`, không
   `docker/crictl prune --volumes`. Dung lượng lấy lại được; dữ liệu thì không.
2. **Ghi lại mốc thời gian và thao tác đã làm.** Cần cho việc PITR sau này và
   cho biên bản sự cố.
3. **Kiểm tra Grafana trước khi SSH vào server.** Dashboard cho biết phạm vi
   ảnh hưởng nhanh hơn nhiều so với dò từng container. Grafana chạy trên
   `node-ai` nên vẫn sống khi `node-app` chết.

## 1. Bảng tra nhanh

| Dấu hiệu | Mục |
|---|---|
| Không vào được web/API | §2 |
| `node-app` chết | §3 |
| Container bị `OOMKilled` / restart liên tục | §4 |
| Đĩa sắp đầy | §5 |
| PostgreSQL không lên | §6 |
| OpenSearch `red` / tìm kiếm lỗi | §7 |
| Hàng đợi ùn, DLQ có message | §8 |
| GPU lỗi / worker AI chết | §9 |
| iSCSI treo, path mất | §10 |
| ZFS báo lỗi checksum | §11 |
| Sao lưu không chạy | §12 |

## 2. Không vào được web/API

```bash
cd /opt/aismartstore/app && docker compose ps
docker compose logs kong --tail=100
curl -sv https://<DOMAIN_WEB>/
curl -s http://<NODE_APP_IP>:8100/status      # Kong status
```

| Nguyên nhân | Xử lý |
|---|---|
| Kong `unhealthy` | `docker compose restart kong`; kiểm tra `config/kong/kong.yml` có lỗi cú pháp không (`kong config parse`) |
| Cert TLS hết hạn | Xem alert `CertSapHetHan`. Mạng cách ly không có Let's Encrypt — ký lại bằng CA nội bộ (`02` §9) |
| `backend` restart liên tục | → §6 (thường do PostgreSQL) hoặc §8 (RabbitMQ 406) |
| Đăng nhập gãy, JWT bị từ chối | `iss` trong token phải khớp `<DOMAIN_AUTH>` **tuyệt đối** — lệch scheme/port là nguyên nhân phổ biến nhất |
| Toàn bộ không phản hồi | → §3 |

## 3. `node-app` chết — SỰ CỐ SỐ 1

> Đây là kịch bản đã biết và **chấp nhận có ý thức**: hệ chỉ có 2 node, mọi
> service stateful đều singleton, nên **không có HA** — và Kubernetes với 2 node
> cũng không cho được điều đó (xem `../hardware-resource-allocation.md` §3.6).
> Cơ chế bảo vệ là **dựng lại nhanh**, và nó chỉ hiệu quả nếu đã diễn tập.

**Dấu hiệu:** Grafana (chạy trên `node-ai`) vẫn vào được và báo `NodeDown`;
SSH tới `node-app` timeout; web/API không phản hồi.

**Worker AI trên `node-ai` vẫn chạy** nhưng sẽ không nối được RabbitMQ — chúng
sẽ retry. **Đừng vội tắt chúng**; job đang xử lý dở sẽ được redeliver khi
RabbitMQ trở lại, **không mất**.

```
Buoc 1 — Xac dinh muc do
  - Node con boot duoc khong? (iDRAC / console)
  - CON boot duoc  -> Buoc 2
  - KHONG boot duoc -> Buoc 3

Buoc 2 — Node boot duoc
  multipath -ll                      # iSCSI con path khong -> §10
  zpool status tank                  # pool co import duoc khong -> §11
  /usr/local/bin/preflight-app.sh    # PHAI pass truoc khi khoi dong
  systemctl start aismartstore-app
  docker compose ps                  # doi toi 5 phut (PostgreSQL recovery)

Buoc 3 — Node KHONG cuu duoc: dung lai (RTO muc tieu <=24h)
  -> Chi tiet o 06-sao-luu-khoi-phuc.md §9.1. Tom tat:
     1. Phan cung thay the -> cai OS + Docker        (tai lieu 00, 01)
     2. git clone <REPO> /opt/aismartstore + dien lai .env
     3. iscsiadm --login; mount /srv/*; zpool import tank; zfs load-key
        (can khoa da ky gui - xem 00 §5)
     4. preflight-app.sh -> systemctl start aismartstore-app
     5. PostgreSQL: LUN nguyen -> chay tiep; hong -> pgbackrest restore
     6. OpenSearch: chi muc mat theo NVMe -> dung lai tu PostgreSQL (<=12h)
```

> ⚠️ **Bước 4 — không bỏ qua `preflight-app.sh`.** Nếu khởi động khi LUN chưa
> mount, PostgreSQL sẽ `initdb` một database rỗng lên thư mục trống và hệ sẽ
> chạy trên database trắng trong khi dữ liệu thật vẫn nằm nguyên trên LUN. Rất
> dễ bị hiểu nhầm là "mất dữ liệu" và dẫn tới thao tác khôi phục sai.

> **Nếu SAN còn nguyên thì phần lớn dữ liệu chưa mất.** Chỉ mất OpenSearch (tái
> tạo được) và thời gian dựng node. Đừng vội khôi phục từ NAS trước khi kiểm tra
> SAN — khôi phục từ NAS mất nhiều giờ tới nhiều ngày.

**Trong lúc chờ:** thông báo người dùng rằng tra cứu và nạp liệu tạm dừng.

## 4. Container bị `OOMKilled` / restart liên tục

```bash
docker ps -a --filter "status=exited" --format "table {{.Names}}\t{{.Status}}"
docker inspect <ct> --format '{{.State.OOMKilled}} {{.RestartCount}} {{.HostConfig.Memory}}'
docker stats --no-stream
free -h && arc_summary | head -20
```

| Trường hợp | Xử lý |
|---|---|
| Service tầng dữ liệu bị OOM-kill | **Bất thường nghiêm trọng.** Kiểm tra `mem_limit` có đúng bảng `02` §1.1 và `oom_score_adj: -500` có được áp không (`docker inspect ... OomScoreAdj`) |
| Tổng `mem_limit` vượt 92GB | Rà lại bảng `02` §1.1 — có ai đó tăng limit mà không tính lại tổng |
| ZFS ARC ăn quá phần | `arc_summary`; kiểm tra `zfs_arc_max=16G` còn hiệu lực sau lần reboot gần nhất (`cat /sys/module/zfs/parameters/zfs_arc_max`) |
| Một service rò rỉ bộ nhớ | Xem xu hướng RSS trong Grafana 7 ngày. Restart là biện pháp tạm, phải tìm nguyên nhân |
| **Restart liên tục** (alert `ContainerRestartLienTuc`) | Thường do `healthcheck` quá gắt + `autoheal` giết giữa lúc khởi động. Nới `start_period`. Xem §6 nếu là PostgreSQL |

> Đây là loại sự cố **cấu hình sai**, không phải hỏng hóc. Nếu lặp lại, vấn đề
> nằm ở bảng phân bổ chứ không ở container bị giết.

## 5. Đĩa sắp đầy

```bash
df -h                                   # tren ca 2 node
zfs list -o name,used,avail tank
du -sh /var/lib/docker/* | sort -h | tail
```

**Thứ tự xử lý — làm từ trên xuống, dừng khi đủ:**

| # | Việc | Thu hồi | An toàn |
|---|---|---|---|
| 1 | `docker image prune -af --filter "until=168h"` | vài GB–vài chục GB | ✅ An toàn. ⚠️ Air-gap: image xoá nhầm phải mang lại qua air-gap |
| 2 | Garbage Collection của registry nội bộ (nếu có) | tuỳ số tag cũ | ✅ An toàn |
| 3 | **SeaweedFS `volume.vacuum`** (`05` §2) | **thường nhiều nhất** | ✅ An toàn, nhưng tốn I/O — cân nhắc giờ |
| 4 | Giảm retention Loki/Prometheus tạm thời | chục–trăm GB | ✅ An toàn |
| 5 | Xoá snapshot ZFS cũ **theo đúng GFS** | nhiều | ⚠️ Chỉ xoá bản đã hết hạn theo chính sách. **Không** xoá bản mới nhất |

> ⚠️ **Tuyệt đối không:** xoá WAL archive (phá PITR), xoá partition `audit_log`
> (gãy chuỗi hash FR-09), `docker system prune --volumes` (xoá volume dữ liệu).

**Nếu đĩa của PostgreSQL đầy:** PostgreSQL sẽ dừng ghi và có thể không khởi động
lại được (`FATAL: could not write init file` — đã xảy ra thật). Mở rộng
`LUN-PG` trên ME5284 (tier SSD còn ~33TB dư địa) rồi `xfs_growfs`, thay vì cố
xoá dữ liệu.

## 6. PostgreSQL không lên

```bash
cd /opt/aismartstore/app
docker compose logs postgres --tail=200
docker compose exec postgres pg_isready
mountpoint /srv/pgdata                       # LUN da mount chua?
```

| Dấu hiệu trong log | Xử lý |
|---|---|
| `could not write init file` | Đĩa đầy → §5 |
| `PANIC: could not locate a valid checkpoint record` | Hỏng WAL → `pgbackrest restore --type=time` (`06` §8 mức L2). **Không** chạy `pg_resetwal` — mất dữ liệu im lặng |
| Database rỗng, dữ liệu "biến mất" | ⚠️ Gần như chắc chắn là **khởi động khi chưa mount LUN** → `docker compose stop postgres`, mount `/srv/pgdata`, xoá thư mục rỗng vừa initdb, khởi động lại. **Dữ liệu thật vẫn còn trên LUN.** Xem §3 |
| Không mount được `/srv/pgdata` | → §10 (iSCSI) |
| Restart vòng lặp ngay sau khi lên | `healthcheck` quá gắt — nới `start_period` lên 600s (recovery có thể lâu) |
| `prepared statement "S_1" already exists` | PgBouncer `transaction` mode — thêm `prepareThreshold=0` vào JDBC URL (`02` §2) |
| pgBackRest lỗi `unable to find a valid repository` | SeaweedFS chưa lên, hoặc bucket `backup` chưa tồn tại. Nhớ ràng buộc thứ tự khởi tạo ở `02` §2 |

## 7. OpenSearch `red` / tìm kiếm lỗi

```bash
docker compose exec opensearch curl -sk -u admin:$OPENSEARCH_INITIAL_ADMIN_PASSWORD \
  https://localhost:9200/_cluster/health?pretty
```

| Dấu hiệu | Xử lý |
|---|---|
| `status: red` | Xem shard nào chưa allocate: `_cluster/allocation/explain` |
| Circuit breaker liên tục | Heap có đúng 10GB không? (`02` §3) |
| Không khởi động, `max virtual memory areas too low` | `sysctl vm.max_map_count=262144` (`00` §2) |
| Chỉ mục hỏng hẳn | **Restore snapshot tuần của OpenSearch** (T1 từ 2026-10-10 — chứa vector) rồi reindex/re-embed phần chênh từ PostgreSQL. Không có snapshot ⇒ dựng lại toàn bộ: toàn văn ≤12h, vector phải re-embed (tới 72h) |

> **Quan trọng:** tìm kiếm lỗi phải **báo lỗi rõ ràng**, tuyệt đối **không** rơi
> về chế độ bỏ qua filter phạm vi FR-08 (`ai-search-infra.md` §3 Security —
> fail-closed). Nếu thấy kết quả trả về mà không có filter phạm vi, **dừng dịch
> vụ tìm kiếm ngay** — đó là rò rỉ dữ liệu.

## 8. Hàng đợi ùn / DLQ có message

```bash
docker compose exec rabbitmq rabbitmqctl list_queues name messages
```

**Queue ùn (`q.ocr` > 10.000):**

| Nguyên nhân | Xử lý |
|---|---|
| Worker chết | → §9 |
| Worker không đủ | Kiểm tra `ai_queue.concurrency`; cân nhắc bật `ocr-worker-0` trên GPU0 (`03` §1.2) |
| Nạp dữ liệu hàng loạt | Bình thường — theo dõi tốc độ giảm; backpressure đã có (`prefetch=1`) |

**DLQ có message** — job thất bại vĩnh viễn:

```
1. Xem message: RabbitMQ management UI (<NODE_APP_IP>:15672) -> queue .dlq
                -> Get messages (an "Requeue: Yes" de khong mat)
2. Phan loai nguyen nhan:
   - Tep hong/khong doc duoc  -> ghi nhan, bao nghiep vu, KHONG replay
   - Loi tam thoi da khac phuc -> replay vao queue chinh
   - Loi code                  -> tao issue, giu message lai
3. Replay: dung shovel plugin chuyen tu .dlq ve queue chinh
```

> ⚠️ **Không bao giờ purge DLQ hàng loạt.** Mỗi message là một tài liệu của
> người dùng chưa được xử lý.

**Backend `CrashLoopBackOff` ngay sau khi sửa cấu hình RabbitMQ:** gần như chắc
chắn là `PRECONDITION_FAILED (406)` — argument queue khai báo trong code lệch
với queue đang tồn tại. Xem log backend để biết argument nào lệch, rồi **hoặc**
sửa code cho khớp, **hoặc** xoá queue và để backend tạo lại (chỉ khi queue rỗng).

## 9. GPU lỗi / worker AI chết

```bash
nvidia-smi                                    # tren node-ai
cd /opt/aismartstore/ai && docker compose logs <service> --tail=200
```

| Dấu hiệu | Xử lý |
|---|---|
| `CUDA out of memory` | **Nhiều tiến trình cùng card cùng chết** — không có cơ chế nào cách ly VRAM. `nvidia-smi` tìm tiến trình vượt ngân sách; giảm `batch_size`/`max-batch-tokens` (`03` §1.1) |
| GPU quá nhiệt (>85°C) | Kiểm tra luồng gió/quạt của R760. Giảm tải tạm bằng cách scale worker về 0 |
| `Xid` error trong `dmesg` | Lỗi phần cứng/driver. Reset GPU: `nvidia-smi -r -i <id>` (cần không tiến trình nào đang dùng). Lặp lại nhiều lần ⇒ bảo hành |
| p95 embed > 150ms kéo dài | `docker compose stop ocr-worker-0` — GPU0 về đúng vai "chỉ trực tuyến" (`03` §1.2) |
| Worker chết giữa job | Bình thường — job có lease + heartbeat, được redeliver. Kiểm tra job "treo" được phát hiện trong ≤15 phút |

## 10. iSCSI treo / mất path

```bash
multipath -ll                       # moi LUN phai co DU 2 path
iscsiadm -m session
dmesg | grep -i "iscsi\|connection.*error"
```

| Dấu hiệu | Xử lý |
|---|---|
| Còn 1/2 path | Một controller ME5284 hoặc một đường mạng có vấn đề. **Chưa ảnh hưởng dịch vụ** nhưng mất dự phòng — xử lý trong giờ hành chính |
| Mất cả 2 path | I/O treo. Kiểm tra switch, VLAN iSCSI, trạng thái ME5284. Đăng nhập lại: `iscsiadm -m node --login` |
| I/O chậm bất thường | Kiểm tra MTU: `ping -M do -s 8972 <SAN_PORTAL_A>`. Lệch MTU một chặng là nguyên nhân phổ biến nhất |

> ⚠️ **CHỈ MỘT node được mount `LUN-PG` tại một thời điểm.** Cả 2
> node đều được map các LUN này để máy còn lại có thể tiếp quản khi cần. XFS
> **không** chịu được mount song song từ 2 host — sẽ **hỏng dữ liệu ngay lập
> tức**, không có cảnh báo. Trước khi mount ở node thứ hai, **phải chắc chắn**
> node thứ nhất đã `umount` (hoặc đã tắt hẳn). Không có phần mềm nào chặn việc
> này giúp bạn.

## 11. ZFS báo lỗi checksum

```bash
zpool status -v tank
```

> **Đọc kỹ trước khi hành động:** pool `tank` nằm trên **LUN RAID6 của phần
> cứng**, ZFS **không có bản dư thừa của riêng nó** ⇒ scrub **phát hiện** được
> hỏng ngầm nhưng **KHÔNG tự sửa được**. Đây là hệ quả đã biết và đã chấp nhận
> (xem `../hardware-resource-allocation.md` §3.3).

```
1. Ghi lai danh sach file loi tu `zpool status -v`
2. Kiem tra SAN: co dia nao hong/dang rebuild khong (giao dien ME5284, hoac
   canh bao SNMP trong Grafana)
3. Khoi phuc TUNG FILE loi tu NAS:
     zfs send <NAS_DATASET>@<snapshot chua file tot> | ...
   hoac lay truc tiep tu snapshot cu tren SAN neu ban do chua bi hong
4. `zpool clear tank` SAU KHI da khoi phuc xong, khong phai truoc
5. Chay `zpool scrub tank` lai de xac nhan sach
```

> Lỗi checksum lặp lại ⇒ vấn đề ở tầng SAN hoặc đường iSCSI, **không** phải ở
> ZFS. Kiểm tra đĩa và cáp trước khi đổ cho phần mềm.

## 12. Sao lưu không chạy

```bash
systemctl status backup-agent.service
journalctl -u backup-agent -n 200
systemctl list-timers | grep -E 'backup-agent|vacuum|prune'
```

| Dấu hiệu | Xử lý |
|---|---|
| Alert `SaoLuuKhongChay` | Chạy tay: `systemctl start backup-agent.service`, theo dõi log |
| `trash-purge` bỏ lượt liên tục | Gate không thoả. Kiểm tra `backup_run` hôm nay có `consistency_point_at` và `window_open=false` chưa |
| Nhân bản NAS chưa xong trước 22:00 | Chồng lấn lượt sau. Tạm giới hạn băng thông ban ngày; tách lượt "full" ra cuối tuần |
| NAS đầy >85% | Tự tỉa theo GFS đã bật chưa? Nếu vẫn đầy → xem lại trần dung lượng (`06` §7) |
| `zfs receive` lỗi "incremental source not found" | Snapshot nguồn đã bị prune trước khi gửi. Phải gửi **full** lại — nhiều giờ. Kiểm tra lại logic prune |

## 13. Thứ tự khởi động lại toàn hệ

Khi phải khởi động lại có kế hoạch (bảo trì, mất điện dài):

```
TAT (nguoc chieu phu thuoc)
  Tren node-ai:
    1. docker compose -f ai/docker-compose.yml stop        # worker AI truoc
       (job dang chay se duoc redeliver - khong mat)
    2. docker compose -f monitoring/docker-compose.yml stop
  Tren node-app:
    3. docker compose stop kong web                        # ngung nhan request
    4. docker compose stop backend
    5. docker compose stop keycloak redis clamav rabbitmq
    6. docker compose stop opensearch                      # doi flush xong
    7. docker compose stop seaweedfs
    8. docker compose stop postgres                        # CUOI CUNG
    9. systemctl stop aismartstore-app
   10. zpool export tank
   11. umount /srv/pgdata /srv/backup-stage
   12. iscsiadm -m node --logoutall=all
   13. Tat node-ai, roi node-app

BAT (xuoi chieu phu thuoc)
    1. SAN san sang -> bat node-app
    2. iSCSI tu login + multipath  -> KIEM TRA `multipath -ll` DU 2 PATH
    3. mount /srv/*; zpool import tank; zfs load-key tank/object
    4. /usr/local/bin/preflight-app.sh        <-- PHAI pass
    5. systemctl start aismartstore-app
       (depends_on + healthcheck lo dung thu tu ben trong: postgres ->
        seaweedfs/opensearch/rabbitmq -> backend -> web -> kong)
    6. Bat node-ai -> systemctl start aismartstore-monitoring
    7.               systemctl start aismartstore-ai
    8. Kiem tra theo checklist §14
```

> **Bước 3 là chỗ dễ sai nhất.** Quên `zfs load-key` thì SeaweedFS khởi động với
> dataset chưa mở khoá và báo lỗi khó hiểu về đường dẫn. `preflight-app.sh`
> (bước 4) sinh ra chính là để chặn cả lỗi này lẫn lỗi quên mount LUN.

> **Ưu điểm của Compose ở đây:** thứ tự khởi động bên trong một node do
> `depends_on: condition: service_healthy` lo, khai báo tường minh trong một
> file duy nhất. Không cần suy luận qua scheduler.

## 14. Checklist sau mọi sự cố

- [ ] Đăng nhập được qua `<DOMAIN_AUTH>`
- [ ] Tra cứu metadata trả kết quả
- [ ] Tải được một tệp gốc, SHA-256 khớp
- [ ] Tìm kiếm toàn văn trả kết quả **có filter phạm vi FR-08**
- [ ] Upload thử một tệp → ClamAV quét → vào hàng đợi
- [ ] `docker compose ps` trên cả 2 node: mọi service `Up (healthy)`
- [ ] Grafana: không alert nào đang mở
- [ ] `zpool status tank` = `ONLINE`, không lỗi
- [ ] `multipath -ll` đủ 2 path mỗi LUN
- [ ] Sao lưu lượt gần nhất `VERIFIED`
- [ ] **Ghi biên bản sự cố**: mốc thời gian, triệu chứng, thao tác, nguyên nhân
      gốc, việc cần làm để không lặp lại

## 15. Diễn tập định kỳ

| Bài | Tần suất | Mục tiêu |
|---|---|---|
| Khôi phục L1 (1 tài liệu qua sandbox) | Hằng tháng (tự động) | SHA-256 khớp |
| Failover controller SAN **có tải** | Hằng quý | I/O không đứt |
| Khôi phục L2 (PITR PostgreSQL) | Hằng quý | ≤4h |
| **Dựng lại `node-app`** (§3 bước 3) | Hằng quý | ≤24h, đo RTO thật |
| Chuyển PV giữa 2 node | Hằng quý | PostgreSQL chạy được trên `node-ai` |

> **Bài chưa từng diễn tập = bài sẽ thất bại lúc cần.** Đặc biệt là việc dựng
> lại `node-app`: đó là kịch bản khả dĩ nhất và cũng là kịch bản tốn thời gian nhất.
