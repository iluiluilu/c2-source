# 00 — Chuẩn bị hạ tầng (OS, đĩa, mạng lưu trữ, GPU)

> Thực hiện trên **cả 2 node** trừ khi ghi rõ "chỉ node AI" / "chỉ node ứng dụng".
> Placeholder `<...>` lấy từ [`README.md`](README.md) §2.

## 1. Hệ điều hành

Cài **Ubuntu Server 24.04 LTS**, tối giản (không desktop, không snap thừa).

```bash
# Kiem tra sau khi cai
hostnamectl                 # hostname dung <NODE_APP_HOSTNAME>/<NODE_AI_HOSTNAME>
timedatectl                 # timezone Asia/Ho_Chi_Minh, NTP synchronized: yes
```

**NTP là bắt buộc, không phải tuỳ chọn.** Lệch giờ giữa 2 node làm hỏng: chứng
chỉ k8s, chuỗi hash `audit_log` (FR-09), và việc khớp thời điểm giữa dump
PostgreSQL và snapshot ZFS (`backup-restore.md` QĐ 2). Mạng cách ly không ra
được Internet ⇒ phải có **NTP server nội bộ**:

```bash
sudo apt install -y chrony
# /etc/chrony/chrony.conf
#   server <NTP_NOI_BO> iburst
sudo systemctl restart chrony && chronyc sources -v
```

## 2. Kernel & tham số hệ thống

```bash
# Tat swap VINH VIEN - PostgreSQL va OpenSearch khong duoc phep bi swap ra dia
sudo swapoff -a
sudo sed -i '/\sswap\s/s/^/#/' /etc/fstab

cat <<'SYSCTL' | sudo tee /etc/sysctl.d/99-platform.conf
# --- Docker/bridge ---
net.bridge.bridge-nf-call-iptables  = 1
net.bridge.bridge-nf-call-ip6tables = 1
net.ipv4.ip_forward                 = 1
# --- OpenSearch (bat buoc, khong service se khong khoi dong duoc) ---
vm.max_map_count                    = 262144
# --- PostgreSQL / IO nang ---
vm.swappiness                       = 1
vm.dirty_background_ratio           = 5
vm.dirty_ratio                      = 10
# --- nhieu container + nhieu ket noi ---
fs.inotify.max_user_instances       = 1024
fs.inotify.max_user_watches         = 1048576
fs.file-max                         = 2097152
net.core.somaxconn                  = 4096
SYSCTL
sudo sysctl --system

cat <<'MOD' | sudo tee /etc/modules-load.d/docker.conf
overlay
br_netfilter
MOD
sudo modprobe overlay br_netfilter
```

Giới hạn tài nguyên tiến trình:

```bash
cat <<'LIM' | sudo tee /etc/security/limits.d/99-platform.conf
*  soft  nofile  1048576
*  hard  nofile  1048576
*  soft  memlock unlimited
*  hard  memlock unlimited
LIM
```

> `memlock unlimited` cần cho `bootstrap.memory_lock=true` của OpenSearch — nếu
> thiếu, OpenSearch vẫn chạy nhưng heap bị swap ra, gây p99 tăng vọt bất thường.

## 3. Đĩa NVMe cục bộ — RAID1

Mỗi node có 2×3.84TB NVMe. **Dùng RAID1, không RAID0** (yêu cầu là ổn định,
không phải dung lượng — 3.84TB khả dụng vẫn thừa so với nhu cầu ~1.5TB).

Ưu tiên RAID phần cứng của PERC nếu có; nếu không, dùng `mdadm`:

```bash
sudo mdadm --create /dev/md0 --level=1 --raid-devices=2 /dev/nvme0n1 /dev/nvme1n1
sudo mkfs.xfs -L LOCAL /dev/md0
sudo mkdir -p /var/lib/local-pv
echo 'LABEL=LOCAL /var/lib/local-pv xfs defaults,noatime 0 2' | sudo tee -a /etc/fstab
sudo mount -a
sudo mdadm --detail --scan | sudo tee -a /etc/mdadm/mdadm.conf
sudo update-initramfs -u
```

Phân vùng thư mục theo vai (dùng `mkdir`, quản dung lượng bằng cảnh báo
Prometheus ở `04-giam-sat-va-log.md`, không cần LVM riêng):

| Node | Đường dẫn | Dùng cho |
|---|---|---|
| Ứng dụng | `/var/lib/local-pv/opensearch` | Local PV cho OpenSearch (T1 từ 2026-10-10 — chứa vector; snapshot tuần ra NAS) |
| Ứng dụng | `/var/lib/docker` | Image + layer của Docker |
| AI | `/var/lib/local-pv/models` | Trọng số model (đọc-only khi chạy) |
| AI | `/var/lib/local-pv/scratch` | Vùng làm việc OCR/STT (ephemeral) |
| AI | `/var/lib/local-pv/monitoring` | Prometheus TSDB + Loki chunk |

> **Lưu ý thiết kế:** vùng làm việc OCR/STT đặt ở **NVMe cục bộ của node AI**,
> **không** phải SAN tier SSD như `kien_truc_he_thong.adoc` §3 đang ghi. Lý do:
> file tạm, vòng đời ngắn, IOPS cao — đẩy qua iSCSI là lãng phí băng thông
> 25GbE vốn đang dùng cho luồng object. Cần sửa lại tài liệu kiến trúc.

## 4. Mạng lưu trữ — iSCSI + multipath tới ME5284

**Chỉ làm trên node ứng dụng trước; node AI cần cấu hình y hệt** để PV của
PostgreSQL gắn lại được khi phải chuyển node (đây chính là điều kiện để
failover hoạt động — xem `../hardware-resource-allocation.md` §3.6b).

### 4.1. Chuẩn bị mạng

```bash
# NIC iSCSI dat trong <VLAN_ISCSI>, MTU 9000 - phai DONG BO ca chang:
#   NIC -> switch -> controller SAN. Lech mot chang = "iSCSI cham bat thuong".
sudo ip link set dev <IF_ISCSI> mtu 9000
ping -M do -s 8972 <SAN_PORTAL_A>    # phai thanh cong, khong duoc fragment
```

### 4.2. Initiator + multipath

```bash
sudo apt install -y open-iscsi multipath-tools
echo "InitiatorName=<IQN_NODE_APP>" | sudo tee /etc/iscsi/initiatorname.iscsi
sudo systemctl enable --now iscsid

cat <<'MP' | sudo tee /etc/multipath.conf
defaults {
    user_friendly_names     yes
    find_multipaths         yes
    polling_interval        5
}
devices {
    device {
        vendor              "DellEMC"
        product             "ME5"
        path_grouping_policy group_by_prio
        path_selector       "service-time 0"
        prio                alua
        failback            immediate
        no_path_retry       18
    }
}
MP
sudo systemctl enable --now multipathd

# Dang nhap ca 2 portal (2 controller) - day la diem mau chot cua HA tang SAN
for p in <SAN_PORTAL_A> <SAN_PORTAL_B>; do
  sudo iscsiadm -m discovery -t sendtargets -p $p
  sudo iscsiadm -m node -T <SAN_IQN> -p $p --login
done
sudo iscsiadm -m node -T <SAN_IQN> -o update -n node.startup -v automatic

sudo multipath -ll     # moi LUN phai thay DU 2 path, status active/enabled
```

### 4.3. LUN cần tạo trên ME5284

Tạo bằng giao diện quản trị của ME5284, **trước khi** cài k8s:

| LUN | Tier | Dung lượng đề xuất | Dùng cho | Gắn vào |
|---|---|---|---|---|
| `LUN-PG` | SSD | 2TB | PostgreSQL data + WAL | PV tĩnh `pv-postgres` |
| `LUN-BACKUP-STAGE` | SSD | 10TB | Staging sao lưu + sandbox khôi phục | Host mount node ứng dụng |
| `LUN-ZFS-OBJECT` | HDD | Toàn bộ tier | Pool ZFS chứa volume SeaweedFS | Host mount node ứng dụng |

> Tier SSD còn dư ~33TB sau khi cấp 3 LUN trên (bỏ `LUN-QDRANT` — Qdrant đã gỡ khỏi hạ tầng 2026-10-10). Giữ lại làm dư địa, **không**
> cấp hết ngay — mở rộng LUN dễ hơn thu hẹp.

**Map LUN cho CẢ HAI initiator** (`<IQN_NODE_APP>` và `<IQN_NODE_AI>`) với
`LUN-PG`. Không map sẵn thì khi mất node ứng dụng, pod sẽ kẹt
`Pending` và mọi lợi ích của k8s ở đây đều mất.

> ⚠️ **Rủi ro phải hiểu rõ:** map một LUN `ReadWriteOnce` vào 2 initiator nghĩa
> là **về mặt SAN, cả 2 node đều mount được cùng lúc**. Hệ tệp XFS/ext4 không
> chịu được điều đó — mount song song sẽ **hỏng dữ liệu**. k8s đảm bảo chỉ 1 pod
> attach tại một thời điểm, nhưng thao tác `mount` bằng tay trên host thì không
> ai chặn. **Cấm mount thủ công `LUN-PG` trên host.** Ghi điều này
> vào `07-runbook-su-co.md`.

### 4.4. Format LUN (chạy MỘT LẦN, trên node ứng dụng)

```bash
sudo mkfs.xfs -L PGDATA  /dev/mapper/<WWID_LUN_PG>
sudo mkfs.xfs -L BKPSTG  /dev/mapper/<WWID_LUN_BACKUP_STAGE>
```

Không thêm vào `/etc/fstab` cho 2 LUN đầu — k8s sẽ tự mount khi attach PV.
Riêng `LUN-BACKUP-STAGE` thì mount cố định:

```bash
sudo mkdir -p /srv/backup-stage
echo 'LABEL=BKPSTG /srv/backup-stage xfs defaults,noatime,_netdev 0 2' | sudo tee -a /etc/fstab
```

> `_netdev` là bắt buộc với mọi mount trên iSCSI — thiếu nó thì systemd sẽ cố
> mount trước khi mạng sẵn sàng và boot sẽ rơi vào emergency mode.

## 5. ZFS cho tầng object (chỉ node ứng dụng)

Pool ZFS đặt trên `LUN-ZFS-OBJECT`, là nền cho SeaweedFS + toàn bộ chiến lược
sao lưu (`zfs send/receive`).

```bash
sudo apt install -y zfsutils-linux

# Tao pool tren LUN cua SAN. Luu y: khong co redundancy o tang ZFS
# (RAID6 do phan cung SAN lo) => scrub PHAT HIEN duoc loi nhung KHONG tu sua.
sudo zpool create -o ashift=12 \
  -O compression=lz4 -O atime=off -O xattr=sa -O acltype=posixacl \
  tank /dev/mapper/<WWID_LUN_ZFS_OBJECT>

# Dataset cho SeaweedFS, co ma hoa native (NFR-04) + sao luu bang zfs send --raw
sudo zfs create -o encryption=aes-256-gcm \
  -o keyformat=raw -o keylocation=file:///etc/zfs/keys/object.key \
  -o recordsize=1M \
  tank/object
```

**Quản lý khoá mã hoá** (`backup-restore.md` QĐ 6):

```bash
sudo mkdir -p /etc/zfs/keys && sudo chmod 700 /etc/zfs/keys
sudo dd if=/dev/urandom of=/etc/zfs/keys/object.key bs=32 count=1
sudo chmod 400 /etc/zfs/keys/object.key
```

> ⚠️ **Khoá này phải được ký gửi ngoại tuyến ngay.** `zfs send --raw` gửi dữ
> liệu **vẫn mã hoá** sang NAS — mất khoá thì bản sao ở NAS trở thành rác không
> giải mã được, tức mất toàn bộ khả năng khôi phục. Quy trình ký gửi: 2 bản in
> mã hex đặt ở 2 két khác nhau, có biên bản bàn giao. **Không** lưu khoá cùng
> chỗ với bản sao lưu.

```bash
# Scrub dinh ky - PHAT HIEN hong ngam (khong tu sua, xem tren)
sudo systemctl enable --now zfs-scrub-weekly@tank.timer

# GIOI HAN ARC - bat buoc. Mac dinh ZFS lay toi 50% RAM, nam NGOAI moi gioi han
# cgroup cua container => se tranh RAM voi PostgreSQL/OpenSearch.
echo "options zfs zfs_arc_max=17179869184" | sudo tee /etc/modprobe.d/zfs.conf  # 16GB
sudo update-initramfs -u
```

## 6. GPU (chỉ node AI)

```bash
sudo apt install -y build-essential dkms
# Driver tai san ve tu ben ngoai, dua vao qua air-gap (xem 01-cai-dat-cum-k8s.md §2)
sudo sh NVIDIA-Linux-x86_64-<VERSION>.run --dkms --silent

nvidia-smi    # phai thay DUNG 2 card L4, moi card 24564MiB
```

Kỳ vọng đầu ra:

```
| 0  NVIDIA L4   ... |  0MiB / 24564MiB |   0%  Default |
| 1  NVIDIA L4   ... |  0MiB / 24564MiB |   0%  Default |
```

**Chưa cài `nvidia-container-toolkit` bằng tay** — NVIDIA GPU Operator sẽ lo ở
bước `01`. Cài trùng dễ gây xung đột runtime.

Bật persistence mode để tránh độ trễ khởi tạo context ở job đầu tiên:

```bash
sudo nvidia-smi -pm 1
sudo systemctl enable nvidia-persistenced
```

## 7. Tường lửa

Mạng đã cách ly hoàn toàn (NFR-11), nhưng vẫn cần chặn giữa 2 node và với
thiết bị khác trong VLAN theo nguyên tắc tối thiểu.

| Cổng | Giao thức | Từ | Tới |
|---|---|---|---|
| 22 | TCP | Máy quản trị | Cả 2 node |
| 5672 | TCP | Node AI | Node ứng dụng (RabbitMQ AMQP) |
| 8333 | TCP | Node AI | Node ứng dụng (SeaweedFS S3) |
| `<port embedding-service>` | TCP | Node ứng dụng (backend) | Node AI (embedding-service, sửa 2026-10-10 — thay 6333/6334 Qdrant đã gỡ) |
| 9200 | TCP | Node AI | Node ứng dụng (OpenSearch) |
| 3100 | TCP | Node ứng dụng | Node AI (Loki nhận log) |
| 9100, 8080 | TCP | Node AI | Cả 2 node (Prometheus scrape exporter) |
| 3260 | TCP | Cả 2 node | `<SAN_PORTAL_A>`, `<SAN_PORTAL_B>` |
| 80/443 | TCP | VLAN nghiệp vụ | Node ứng dụng (Kong) |
| 15672, 9443 | TCP | Máy quản trị | Node ứng dụng (RabbitMQ UI, Portainer) |

> ⚠️ **Bài học đã trả giá ở môi trường trước:** `ufw` **không** lọc được cổng do
> container publish, vì Docker/CNI chèn rule ở chain `DOCKER-USER`/`FORWARD`
> đứng trước chain `INPUT` mà `ufw` quản. **Không cài `ufw`** — dùng
> `iptables` trực tiếp, chèn rule vào chain `DOCKER-USER` (chi tiết ở
> [`01-nen-tang-docker.md`](01-nen-tang-docker.md) §5), và kiểm chứng bằng cách
> thử kết nối từ một IP ngoài allowlist. **Không** tin vào việc "đã thêm rule".

## 8. Nghiệm thu bước 0

- [ ] `swapon --show` trống trên cả 2 node
- [ ] `sysctl vm.max_map_count` = 262144
- [ ] `chronyc sources` báo synchronized, lệch giờ 2 node < 50ms
- [ ] `cat /proc/mdstat` báo RAID1 `[UU]` trên cả 2 node
- [ ] `sudo multipath -ll` thấy **đủ 2 path** cho mỗi LUN
- [ ] `ping -M do -s 8972 <SAN_PORTAL_A>` thành công (MTU 9000 thông suốt)
- [ ] **Test failover controller CÓ TẢI**: chạy `fio` ghi liên tục lên LUN, rút
      (hoặc reboot) 1 controller của ME5284 → I/O **không đứt**, `multipath -ll`
      chuyển path. *Đây là bài test quan trọng nhất của bước này — bỏ qua thì
      không biết dual-controller có thật sự hoạt động cho tới lúc sự cố.*
- [ ] `sudo zpool status tank` báo `ONLINE`, `sudo zfs get encryption tank/object`
      báo `aes-256-gcm`
- [ ] Khoá ZFS **đã ký gửi ngoại tuyến**, có biên bản
- [ ] `nvidia-smi` thấy đủ 2× L4 24GB trên node AI
- [ ] Reboot thử cả 2 node: iSCSI tự login, multipath tự lên, ZFS pool tự
      import, không rơi vào emergency mode
