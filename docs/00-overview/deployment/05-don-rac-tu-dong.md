# 05 — Cơ chế dọn rác tự động

> Phụ thuộc: [`02-workload-ung-dung.md`](02-workload-ung-dung.md),
> [`03-workload-ai.md`](03-workload-ai.md).
> Mọi việc định kỳ ở đây chạy bằng **systemd timer** trên host, không phải
> container — để chúng vẫn chạy được cả khi Docker có vấn đề.

## 1. Bối cảnh

Hệ thống có **3 tầng sinh rác độc lập**, và hiện chỉ tầng đầu có cơ chế:

| Tầng | Trạng thái |
|---|---|
| **Ứng dụng** (job trong backend) | ✅ Đã có code: `UploadCleanupJob`, `AuditRetentionJob`, `AuditPartitionMaintenanceJob`, `OutboxRetentionJob`, `TrashPurgeJob` |
| **Hạ tầng** (log, image, snapshot, WAL, volume) | ❌ **Chưa có gì** — nội dung của tài liệu này |
| **Object store** (SeaweedFS) | ❌ **Chưa có gì, và là mục dễ bị bỏ sót nhất** — xem §2 |

> ⚠️ **Ràng buộc chéo quan trọng:** `TrashPurgeJob` đang **fail-closed vĩnh
> viễn** vì bảng `backup_run` chưa tồn tại ⇒ hệ thống **không bao giờ xoá cứng**
> dữ liệu thùng rác quá hạn. Toàn bộ tầng ứng dụng ở trên **chỉ là lý thuyết**
> cho tới khi bảng đó được implement (xem `README.md` §4). Đây là điều kiện tiên
> quyết của mọi thứ trong tài liệu này.

## 2. SeaweedFS volume vacuum — MỤC QUAN TRỌNG NHẤT

**Xoá object trong SeaweedFS chỉ đánh dấu, KHÔNG giải phóng dung lượng.** Dung
lượng chỉ trả về sau khi chạy `volume.vacuum`. Thao tác này **không xuất hiện
trong bất kỳ tài liệu kiến trúc nào** — nếu bỏ qua, dung lượng chỉ tăng bất kể
người dùng xoá bao nhiêu, và mọi con số dung lượng ở các tài liệu khác đều sai.

```ini
# /etc/systemd/system/seaweedfs-vacuum.service  (node-app)
[Unit]
Description=SeaweedFS volume vacuum - giai phong dung luong object da xoa
After=aismartstore-app.service

[Service]
Type=oneshot
ExecStart=/usr/bin/docker compose -f /opt/aismartstore/app/docker-compose.yml \
  exec -T seaweedfs sh -c 'echo "volume.vacuum -garbageThreshold=0.3" | weed shell'
TimeoutStartSec=6h
```

```ini
# /etc/systemd/system/seaweedfs-vacuum.timer
[Timer]
OnCalendar=Sun *-*-* 04:00:00      # ngoai gio, sau cua so sao luu
Persistent=true
RandomizedDelaySec=600
```

> `-garbageThreshold=0.3`: chỉ nén volume có >30% rác. Ngưỡng thấp hơn khiến
> vacuum chạy quá thường xuyên và tốn I/O; cao hơn thì dung lượng trả về chậm.

> ⚠️ **Vacuum đọc/ghi lại toàn bộ volume** ⇒ tốn I/O nặng trên tier HDD. Bắt
> buộc chạy ngoài giờ và **không** chồng lấn cửa sổ sao lưu 02:00 hay cửa sổ
> nhân bản NAS. Giám sát thời gian chạy; nếu vượt 4 tiếng thì giảm ngưỡng xuống
> để chia nhỏ theo nhiều lượt.

## 3. Snapshot ZFS

Prune theo GFS thuộc về `backup-agent` (`backup-restore.md` QĐ 6) — xem
[`06-sao-luu-khoi-phuc.md`](06-sao-luu-khoi-phuc.md) §5. Ở đây chỉ nêu **lưới an
toàn** phòng khi agent lỗi:

```bash
# /etc/systemd/system/zfs-snapshot-guard.service  (tren node-app)
# Canh bao neu so snapshot vuot nguong bat thuong -> agent co the da chet
[Service]
Type=oneshot
ExecStart=/usr/local/bin/zfs-snapshot-guard.sh
```

```bash
#!/bin/bash
# /usr/local/bin/zfs-snapshot-guard.sh
COUNT=$(zfs list -t snapshot -o name -H tank/object | wc -l)
# GFS toi da: 14 daily + 8 weekly + 12 monthly + 42 snapshot 4h cua 7 ngay = ~76
if [ "$COUNT" -gt 120 ]; then
  logger -p daemon.crit "zfs-snapshot-guard: $COUNT snapshot - vuot xa GFS, backup-agent co the da hong"
  exit 1
fi
```

> **Cố ý chỉ cảnh báo, không tự xoá.** Xoá snapshot tự động khi không rõ nguyên
> nhân là cách nhanh nhất để mất khả năng khôi phục.

## 4. PostgreSQL — WAL và autovacuum

**WAL archive**: **pgBackRest** tự quản theo `repo1-retention-full = 14` đã đặt
ở [`02-workload-ung-dung.md`](02-workload-ung-dung.md) §2 — khớp PITR 14 ngày
của GFS. Không cần `pg_archivecleanup` thủ công.

> ⚠️ Repo pgBackRest nằm trên **SeaweedFS**. Nghĩa là sau khi pgBackRest tỉa WAL
> cũ, dung lượng vẫn chưa được trả về cho tới lượt `volume.vacuum` ở §2. Hai cơ
> chế dọn rác xếp chồng nhau — đừng hoảng khi thấy dung lượng chưa giảm ngay.

**Autovacuum**: bảng `audit_log` và `outbox` ghi rất nhiều ⇒ chỉnh riêng:

```sql
ALTER TABLE audit_log SET (autovacuum_vacuum_scale_factor = 0.02,
                           autovacuum_analyze_scale_factor = 0.01);
ALTER TABLE outbox    SET (autovacuum_vacuum_scale_factor = 0.01,
                           autovacuum_vacuum_cost_delay = 0);
```

> ⚠️ **`audit_log` có chuỗi hash chống sửa (FR-09).** `AuditPartitionMaintenanceJob`
> và `AuditRetentionJob` đã xử lý việc cắt/giữ partition đúng cách. **Tuyệt đối
> không** `DROP PARTITION` bằng tay — sẽ làm gãy chuỗi hash và mất khả năng
> chứng minh toàn vẹn nhật ký.

## 5. Docker — log và image

Đã cấu hình ở [`01-nen-tang-docker.md`](01-nen-tang-docker.md) §2:

| Tham số (`/etc/docker/daemon.json`) | Giá trị | Tác dụng |
|---|---|---|
| `log-opts.max-size` | `10m` | Trần log mỗi container |
| `log-opts.max-file` | `5` | ⇒ tối đa 50MB/container |

> ⚠️ **Docker mặc định KHÔNG giới hạn log.** Khác với kubelet (vốn có mặc định
> 10Mi × 5), ở đây không đặt là không có gì cả. Đây chính là nguyên nhân sự cố
> thật: log backend phình **72GB** làm đầy đĩa, khiến PostgreSQL báo
> `FATAL: could not write init file`. Đặt ở `daemon.json` (chứ không chỉ ở từng
> service) là để service thêm sau cũng dính.

**Dọn image không dùng** — Docker **không** tự dọn như kubelet:

```ini
# /etc/systemd/system/docker-prune.service
[Service]
Type=oneshot
# CHI image dangling/khong dung, cu hon 7 ngay.
# TUYET DOI khong dung `docker system prune --volumes` - se xoa volume du lieu.
ExecStart=/usr/bin/docker image prune -af --filter "until=168h"
ExecStartPost=/usr/bin/docker builder prune -af --filter "until=168h"
```

```ini
# docker-prune.timer
[Timer]
OnCalendar=Sun *-*-* 03:30:00
Persistent=true
```

> ⚠️ `docker image prune -a` xoá **mọi** image không có container đang dùng.
> Trong môi trường air-gap, image xoá nhầm **không tải lại được từ Internet** —
> phải mang lại qua air-gap. Vì vậy: giữ bản sao `.tar.zst` của mọi image
> production ở `/srv/images/` và **loại trừ thư mục đó khỏi mọi lệnh prune**.

## 6. Vùng scratch OCR/STT

Worker mount `/var/lib/local-pv/scratch` dùng chung. Container bị giết đột ngột
có thể để lại file mồ côi:

```
# /etc/tmpfiles.d/aismartstore-scratch.conf  (node-ai)
# Xoa file khong duoc dung toi trong 48 gio.
# 48h du dai de khong xoa nham file cua job dang chay lau (job OCR/STT co the
# toi hang chuc phut, nhung khong toi 48 gio).
d /var/lib/local-pv/scratch 0755 root root 2d
```

```bash
sudo systemd-tmpfiles --create
systemctl status systemd-tmpfiles-clean.timer   # chay hang ngay, san co
```

> Dùng `systemd-tmpfiles` thay vì viết một job riêng: ít thành phần hơn, và nó
> đã có sẵn trên mọi hệ systemd.

## 7. RabbitMQ — DLQ và queue retry

```bash
# Policy: gioi han do dai queue retry, tranh phinh vo han khi loi lien tuc
rabbitmqctl set_policy retry-limit "^q\..*\.retry$" \
  '{"max-length":100000,"overflow":"reject-publish"}' --apply-to queues
```

> ⚠️ **KHÔNG đặt TTL tự xoá cho `.dlq`.** DLQ chứa job thất bại vĩnh viễn —
> phải có người xem, phân loại, quyết định replay hay bỏ. Tự xoá là mất dữ liệu
> nghiệp vụ trong im lặng. Thay vào đó: alert `DlqCoMessage` ở
> [`04-giam-sat-va-log.md`](04-giam-sat-va-log.md) §7 và runbook xử lý DLQ ở
> [`07-runbook-su-co.md`](07-runbook-su-co.md).

## 8. Chữ ký ClamAV

```bash
# Sau moi lan dong bo offline, don .cvd cu
find /var/lib/clamav -name "*.cvd.old" -mtime +30 -delete
```

Danh sách chữ ký đang dùng thuộc tầng sao lưu T0 cấu hình (`backup-restore.md`
QĐ 1) — dọn bản cũ nhưng **phải giữ bản đang dùng** trong luồng sao lưu.

## 9. Snapshot OpenSearch

| Hệ | Chính sách | Cơ chế |
|---|---|---|
| OpenSearch (T1 từ 2026-10-10 — chứa vector, thay Qdrant đã gỡ) | Giữ **4 bản** (hàng tuần) | SLM policy trên snapshot repository |

## 10. Bảng tổng hợp lịch

| Giờ | Việc | Nơi chạy |
|---|---|---|
| **02:00** hằng ngày | Tạo điểm nhất quán sao lưu (`06`) | host `node-app` |
| **03:00** hằng ngày | `trash-purge` (gate theo `backup_run`) | backend |
| 04:00 T7 hằng tháng | Kiểm thử khôi phục tự động (`06`) | sandbox |
| 03:30 Chủ nhật | Dọn image Docker cũ | systemd timer |
| **04:00 Chủ nhật** | **SeaweedFS volume vacuum** | systemd timer |
| 05:00 hằng ngày | Dọn scratch OCR/STT | `systemd-tmpfiles` |
| 06:00 hằng ngày | Guard đếm snapshot ZFS | systemd timer |
| Hằng tuần | `zfs scrub` (SAN), GC registry, prune snapshot OpenSearch | systemd timer |
| Hằng tháng | `zfs scrub` (NAS) | NAS |
| Liên tục | Giới hạn log Docker, Loki compactor, Prometheus retention | Docker / container |

> ⚠️ **Không chồng lịch.** Vacuum (04:00 CN) đọc/ghi nặng tier HDD; nhân bản NAS
> bắt đầu 02:15 và có thể kéo nhiều giờ. Nếu cả hai cùng chạy, băng thông SAN
> sẽ bão hoà và cả hai đều chậm. Nếu cửa sổ nhân bản thường vượt 04:00, dời
> vacuum sang 06:00 Chủ nhật.

## 11. Nghiệm thu bước 5

- [ ] `systemctl list-timers` — mọi timer ở bảng §10 đều có `NEXT`/`LAST` hợp lệ
- [ ] **Test vacuum thật:** upload 100 tệp → xoá → đo dung lượng `tank/object`
      trước/sau khi chạy vacuum ⇒ dung lượng **phải giảm**. *Đây là bài test
      quan trọng nhất của bước này.*
- [ ] `zfs list -t snapshot tank/object | wc -l` nằm trong ngưỡng GFS
- [ ] `SELECT * FROM pg_stat_archiver` — không có WAL tồn đọng bất thường
- [ ] Đĩa của cả 4 tầng (NVMe ×2, SAN SSD, SAN HDD, NAS) đều ổn định sau 1 tuần
      chạy, không có xu hướng tăng đơn điệu
- [ ] Alert `DungLuongSapDay` đã được bắn thử và tới đúng kênh
- [ ] Xác nhận `backup_run` đã có dữ liệu thật ⇒ `trash-purge` **thoát khỏi**
      trạng thái fail-closed (kiểm tra audit `trash.purge.skipped` không còn xuất hiện)
