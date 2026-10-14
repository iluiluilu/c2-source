# 06 — Sao lưu & khôi phục

> Nguồn quyết định: [`../../01-sa/architecture/backup-restore.md`](../../01-sa/architecture/backup-restore.md)
> (**Approved** 2026-09-09). Tài liệu này **không quyết định lại**, chỉ hiện
> thực hoá trên hạ tầng k8s + phần cứng thật.
> Bối cảnh hiện trạng: [`../backup-restore-strategy.md`](../backup-restore-strategy.md).

## 0. Cảnh báo hiện trạng — đọc trước

> ⚠️ **Thiết kế đã Approved nhưng CHƯA CÓ MỘT DÒNG CODE NÀO.** Không có bảng
> `backup_run`/`restore_run`, không có `backup-agent`, không có UI admin. Hệ quả
> đang xảy ra: `trash-purge` fail-closed vĩnh viễn ⇒ không bao giờ xoá cứng dữ
> liệu quá hạn.
>
> **Tài liệu này mô tả đích đến.** Trước khi go-live, tối thiểu phải có **MVP
> bảng `backup_run`** để gate purge có dữ liệu thật đọc. Không chờ `backup-agent`
> đầy đủ mới làm MVP đó.

## 1. Mục tiêu (từ `backup-restore.md` §3, đã được khách hàng xác nhận)

| Chỉ tiêu | Giá trị |
|---|---|
| **RPO** metadata/cấu hình/nhật ký (PostgreSQL) | ≤ **15 phút** |
| **RPO** tệp gốc & object | ≤ **4 giờ** |
| **RPO** chỉ mục (OpenSearch) | ≤ **7 ngày** (dựng lại được) |
| **RTO** L1 — khôi phục 1 tài liệu qua sandbox | ≤ **4 giờ** |
| **RTO** L2 — khôi phục PostgreSQL (phần cứng còn nguyên) | ≤ **4 giờ** |
| **RTO** L3 — thảm hoạ, tính từ khi có phần cứng thay thế | ≤ **24 giờ** cho chức năng lõi; tìm kiếm được chạy suy giảm thêm ≤ 72 giờ |
| Tỉ lệ sao lưu thành công | ≥ **99%/tháng**, **0** bản "thành công giả" |

## 2. Phân tầng phạm vi (QĐ 1)

| Tầng | Dữ liệu | Cơ chế | Tần suất | Giữ (GFS) |
|---|---|---|---|---|
| **T0** | PostgreSQL: metadata, **filer store SeaweedFS**, tài khoản, `audit_log`, `system_config`, kết quả OCR/STT đã kiểm duyệt | Base backup + **WAL archiving liên tục** (PITR) | Base 02:00; WAL ≤15 phút | 14 ngày PITR; base 14 daily / 8 weekly / 12 monthly |
| **T0** | Object trên `tank/object` (ZFS) | `zfs snapshot` + `zfs send -i --raw` | Mỗi **4 giờ** | SAN 7 ngày; NAS 14 daily / 8 weekly / 12 monthly |
| **T0** | Cấu hình: Keycloak realm + DB, toàn bộ `/opt/aismartstore` (compose, config, script), chữ ký ClamAV đang dùng | Export + dump + `tar` (và đã có trong Git) | Hằng ngày | 30 bản |
| **T1** | OpenSearch (index `segment` chứa vector — re-embed tốn tới 72h GPU; thay Qdrant đã gỡ 2026-10-10) | Snapshot API → ghi ra dataset ZFS → đi cùng luồng T0 | Hàng tuần | 4 bản |
| — | Redis, RabbitMQ | **Không sao lưu** | — | — |

> **Không còn hạng mục "snapshot etcd"** như phương án k8s đòi hỏi. Toàn bộ
> "trạng thái điều phối" của hệ này chỉ là các file trong `/opt/aismartstore`,
> vốn đã nằm trong Git. Đây là một phần lý do việc dựng lại nhanh hơn hẳn
> (xem `../hardware-resource-allocation.md` §3.6c).

## 3. Điểm nhất quán: "DB trước, snapshot sau" (QĐ 2)

SeaweedFS tách metadata (PostgreSQL) khỏi dữ liệu (ZFS). Sai thứ tự ⇒ object
"mồ côi" khi khôi phục.

```
T0     : dat window_open = true
T0+1s  : PostgreSQL - tao diem nhat quan (pg_backup_start / checkpoint)
T0+δ   : zfs snapshot tank/object@<timestamp>     <-- SAU khi DB xong
T0+δ+  : dat window_open = false, ghi backup_run.consistency_point_at
```

Mục tiêu: toàn bộ bước tạo điểm nhất quán ≤ **15 phút**.

> ⚠️ **Cấm `trash-purge` chạy trong `[T0, T0+δ]`.** Xoá cứng giữa lúc DB đã chốt
> mà snapshot chưa chụp sẽ tạo đúng loại object mồ côi mà thứ tự này sinh ra để
> tránh.

## 4. `backup-agent` — systemd mức host

`backup-restore.md` QĐ 4 (Approved) chốt agent chạy **systemd timer mức host**,
tách khỏi vòng đời ứng dụng, báo cáo ngược về backend qua API nội bộ
`/internal/backup/*`. **Triển khai đúng nguyên bản, không cần điều chỉnh gì** —
đây là một lợi ích trực tiếp của việc không dùng k8s.

| Phần việc | Cách chạy |
|---|---|
| `zfs snapshot`, `zfs send/receive`, `zfs scrub`, prune GFS | systemd trên `node-app` |
| Base backup PostgreSQL + WAL | **pgBackRest** (`02` §2) — agent chỉ gọi `pgbackrest backup` |
| Snapshot OpenSearch (gọi API) | Cùng script agent |
| `tar` cấu hình + export realm Keycloak | Cùng script agent |
| Ghi `backup_run` qua `/internal/backup/*` | Cùng script agent |
| Verify checksum, đối chiếu GUID snapshot | Cùng script agent |

```ini
# /etc/systemd/system/backup-agent.service (node-app)
[Unit]
Description=Backup agent - tao diem nhat quan + nhan ban sang NAS
After=zfs.target network-online.target aismartstore-app.service

[Service]
Type=oneshot
ExecStart=/opt/aismartstore/scripts/backup-agent.py --mode=daily
TimeoutStartSec=20h
```

```ini
# /etc/systemd/system/backup-agent.timer
[Timer]
OnCalendar=*-*-* 02:00:00
Persistent=true       # chay bu neu may tat luc 02:00
```

Snapshot object 4 giờ/lần (không tạo điểm nhất quán DB, không gate purge):

```ini
# backup-agent-object.timer
[Timer]
OnCalendar=*-*-* 06,10,14,18,22:00:00
```

> **Agent phải giữ bản sao lịch cục bộ.** Nguồn sự thật là `system_config` (đọc
> qua `GET /internal/backup/schedule`), nhưng nếu backend chết thì agent vẫn
> phải chạy được — đúng tinh thần QĐ 4 "tách khỏi vòng đời ứng dụng". Đây cũng
> là lý do agent chạy bằng systemd chứ không phải là một container trong cùng
> project Compose với backend.

## 5. Lịch chuẩn (QĐ 3)

| Giờ | Việc | Ràng buộc |
|---|---|---|
| **02:00** | Tạo **điểm nhất quán** của ngày (≤15 phút) | `window_open=true` suốt bước này |
| 02:15 → xong | Nhân bản gia tăng sang NAS + verify (có thể nhiều giờ) | Chạy trên snapshot đóng băng ⇒ **không** chặn nghiệp vụ, **không** chặn purge |
| **03:00** | `trash-purge` | **Gate:** chỉ chạy nếu có `backup_run` của **hôm nay** với `consistency_point_at IS NOT NULL`, `status IN (REPLICATING, VERIFIED)` và `window_open=false`. Không thoả ⇒ **bỏ lượt** + audit `trash.purge.skipped` + cảnh báo |
| 04:00 (T7) | Kiểm thử khôi phục tự động hằng tháng | Sandbox, không đụng production |
| 06,10,14,18,22:00 | Snapshot + gửi gia tăng object (RPO 4h) | Không tạo điểm nhất quán DB |

> **Vì sao gate theo snapshot chứ không theo "đã tới NAS":** cái bảo vệ dữ liệu
> khỏi purge là **snapshot đã tồn tại** trên SAN, không phải việc bản sao đã tới
> NAS. Nhân bản có thể kéo nhiều giờ; gate theo nó sẽ làm purge chết đói và tích
> tụ rác vĩnh viễn.

> ⚠️ **Hệ quả phải ghi vào tài liệu vận hành:** dữ liệu đã "xoá vĩnh viễn" bằng
> purge **vẫn tồn tại trong bản sao** tới ~12 tháng theo GFS ⇒ (a) dung lượng
> **không** được giải phóng ngay sau purge; (b) nếu có yêu cầu "xoá triệt để
> theo yêu cầu pháp lý" thì phải có quy trình riêng.

## 6. Nhân bản sang NAS

```bash
# Gia tang, giu nguyen ma hoa (--raw): NAS KHONG can khoa de nhan
zfs send --raw -i tank/object@<snap_truoc> tank/object@<snap_moi> \
  | ssh <NAS_IP> "zfs receive <NAS_DATASET>"

# Doi chieu GUID nguon/dich sau moi lan gui
zfs get -H -o value guid tank/object@<snap_moi>
ssh <NAS_IP> "zfs get -H -o value guid <NAS_DATASET>@<snap_moi>"
```

> ⚠️ **`--raw` nghĩa là NAS chứa dữ liệu vẫn mã hoá.** Mất khoá ZFS (ký gửi ở
> `00-chuan-bi-ha-tang.md` §5) ⇒ bản sao ở NAS thành rác không giải mã được, tức
> **mất toàn bộ khả năng khôi phục**. Kiểm tra định kỳ rằng khoá ký gửi vẫn đọc
> được — không chỉ rằng nó vẫn còn ở đó.

**Giới hạn băng thông ban ngày** để không cạnh tranh với nghiệp vụ:

```bash
zfs send ... | pv -L 200M | ssh <NAS_IP> "zfs receive ..."
```

**Cảnh báo:** nếu lượt nhân bản chưa xong trước **22:00** thì sẽ chồng lấn lượt
kế tiếp và RPO tụt. Alert riêng cho việc này; xử lý bằng cách tách lượt "full"
ra cuối tuần.

## 7. Dung lượng NAS — ⚠️ CHỜ Q6

| Hạng mục | Số liệu |
|---|---|
| NAS raw | 144TB (6×24TB) |
| RAID-Z2 (4+2) | ~96TB khả dụng |
| Ngưỡng ZFS an toàn 80% | **~77TB dùng thật** |
| SAN HDD dùng thật | ~205TB |

**NAS là trần thật của hệ thống, không phải SAN.** Với GFS giữ 12 bản hàng
tháng, ước tính dữ liệu gốc nên **không vượt ~55–60TB**.

> **Chưa thể chốt chính sách giữ bản sao** cho tới khi có số liệu thật (Q6: TB
> ban đầu + TB/tháng). Nếu vượt, ba lựa chọn: giảm retention (ví dụ 6 monthly
> thay vì 12), mở rộng NAS, hoặc thu hẹp phạm vi sao lưu.

Cảnh báo dung lượng NAS: **70%** (cảnh báo) / **85%** (bắt đầu tự tỉa theo GFS).

**Dùng 2×960GB NVMe của NAS làm `special vdev` dạng mirror** (metadata + small
blocks), không phải L2ARC — đích sao lưu chủ yếu ghi tuần tự lớn nên L2ARC gần
như vô ích, còn special vdev làm nhanh rõ rệt scrub/prune/duyệt snapshot.
**Bắt buộc mirror**: mất special vdev = mất cả pool.

## 8. Ba mức khôi phục (QĐ 5)

| Mức | Tình huống | Cách làm | RTO |
|---|---|---|---|
| **L1** | Khôi phục 1 tài liệu / tập nhỏ | Dựng **sandbox** từ snapshot trên `LUN-BACKUP-STAGE` → trích xuất chọn lọc → đối chiếu SHA-256 → nạp lại | ≤4h |
| **L2** | Hỏng PostgreSQL, phần cứng còn nguyên | `pgbackrest restore --type=time --target="<consistency_point_at>"`, rồi `zfs rollback` object về cùng mốc | ≤4h |
| **L3** | Thảm hoạ (mất `node-app` hoặc mất SAN) | Xem §9 | ≤24h cho chức năng lõi |

### Sandbox khôi phục

Đặt trên `LUN-BACKUP-STAGE` (SAN tier SSD, 10TB — dư địa đã cấp ở
`00-chuan-bi-ha-tang.md` §4.3). Chạy như **một project Compose riêng**
(`/opt/aismartstore/sandbox/`) với network Docker riêng, không nối vào network
`app`.

> ⚠️ **Sandbox phải hoàn toàn cách ly.** Một lần khôi phục nhầm vào production
> là mất dữ liệu thật. Ba lớp bảo vệ: project Compose riêng, network riêng, và
> **credential S3 khác** (chỉ đọc bucket backup, không ghi được vào bucket
> production). Đặt tên container có tiền tố `sandbox-` để nhìn `docker ps` là
> biết ngay mình đang ở đâu.

## 9. L3 — Kịch bản thảm hoạ

### 9.1. Mất `node-app` (nghiêm trọng nhất)

Mất đồng thời: toàn bộ tầng dữ liệu và tầng ứng dụng. Nghiệp vụ dừng.

```
1. Dung phan cung thay the, cai OS + Docker      (tai lieu 00, 01)
2. git clone cau hinh:
     git clone <REPO> /opt/aismartstore
     # dien lai .env tu ban sao luu cau hinh (T0)
3. Gan lai LUN cua SAN:
     iscsiadm --login; multipath -ll        # du 2 path
     mount /srv/pgdata /srv/backup-stage
     zpool import tank
     zfs load-key tank/object               <-- can khoa da ky gui
4. Chay preflight roi khoi dong:
     /usr/local/bin/preflight-app.sh
     systemctl start aismartstore-app
5. PostgreSQL: neu LUN nguyen -> chay tiep
                neu hong      -> PITR tu pgBackRest
6. OpenSearch: chi muc mat theo NVMe cuc bo -> DUNG LAI tu PostgreSQL (<=12h)
7. Xac minh theo checklist 07-runbook-su-co.md §14
```

> **Điểm mấu chốt:** nếu **SAN còn nguyên** thì phần lớn dữ liệu chưa mất — chỉ
> mất OpenSearch (tái tạo được) và thời gian dựng node. Đây chính là lý do đặt
> PostgreSQL/object trên SAN chứ không trên NVMe cục bộ.

> **Bước 2–4 đo bằng giờ, không phải ngày** — vì "trạng thái hệ thống" chỉ là
> một thư mục trong Git cộng với các LUN trên SAN. Đây là cơ chế bảo vệ chính
> của hệ này, thay cho HA mà cấu hình 2 node không cho phép. **Phải diễn tập
> hằng quý**, nếu không thì con số ≤24h chỉ là giả định.

### 9.2. Mất SAN

Nặng hơn nhiều: phải khôi phục toàn bộ từ NAS.

```
1. Thay the/dung lai SAN, tao lai LUN, format
2. zfs receive tu NAS ve tank/object  (nhieu gio -> nhieu ngay tuy dung luong)
3. PITR PostgreSQL tu base + WAL luu tren NAS
4. Restore snapshot OpenSearch tu NAS (co vector) + reindex/re-embed phan chenh tu PostgreSQL
5. Doi chieu SHA-256 tren mau ngau nhien truoc khi mo lai dich vu
```

> ⚠️ **Không có bản sao offsite** — khách hàng đã **chấp nhận rủi ro** này
> (chính sách an ninh cấm mang thiết bị ra ngoài, ghi ở
> `backup-restore-strategy.md` §1). Nghĩa là: **cháy/ngập phòng máy = mất toàn
> bộ dữ liệu**, vì SAN và NAS đặt cùng một nơi. Đây là rủi ro tồn dư lớn nhất
> của hệ thống, phải được lãnh đạo biết rõ chứ không chỉ nằm trong tài liệu kỹ thuật.

## 10. Kiểm thử khôi phục định kỳ (NFR-06 — bắt buộc)

| Loại | Tần suất | Nội dung |
|---|---|---|
| **Tự động** | Hằng tháng (04:00 T7) | Dựng sandbox từ bộ khôi phục **mới nhất** → đối chiếu SHA-256 trên mẫu ngẫu nhiên → ghi kết quả vào `backup_run` |
| **Diễn tập có người** | Hằng quý | Kịch bản L3, **đo RTO thực tế**, cập nhật lại số ở §1 nếu lệch |

> ⚠️ **"0 bản thành công giả".** Mọi bản sao phải được verify trước khi tính là
> hợp lệ. Một bản sao chưa từng được thử khôi phục thì **không phải** bản sao —
> đó là một file có kích thước lớn.

## 11. Nghiệm thu bước 6

- [ ] Bảng `backup_run`/`restore_run` tồn tại và có dữ liệu thật
- [ ] `systemctl list-timers | grep backup-agent` — timer đã lên lịch đúng
- [ ] `trash-purge` chạy được (không còn audit `trash.purge.skipped`)
- [ ] `SELECT * FROM pg_stat_archiver`: WAL archive liên tục, `last_failed_time` rỗng
- [ ] Đo thực tế: thời gian tạo điểm nhất quán ≤ 15 phút
- [ ] Bản `tar` cấu hình + export realm Keycloak có mặt trên NAS
- [ ] Đối chiếu GUID snapshot nguồn/đích khớp sau mỗi lần `zfs send`
- [ ] **Diễn tập L1 thật**: xoá 1 tài liệu → khôi phục qua sandbox → SHA-256 khớp
- [ ] **Diễn tập L2 thật**: `pgbackrest restore --type=time` về một mốc quá khứ
      trong sandbox, kiểm tra dữ liệu đúng mốc đó
- [ ] **Diễn tập L3 trên giấy** (bàn tròn) trước go-live; L3 thật trong quý đầu
- [ ] Kiểm tra khoá ZFS ký gửi: **đọc được**, không chỉ là còn tồn tại
- [ ] Alert `SaoLuuKhongChay` đã bắn thử thành công
