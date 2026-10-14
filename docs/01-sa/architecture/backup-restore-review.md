# Review & chốt phương án: backup-restore (DB + file storage)

> File: `docs/01-sa/architecture/backup-restore-review.md` — Owner: SA
> Status: Draft (2026-10-03)
> Bổ sung cho `backup-restore.md` (Approved 2026-09-09). File này **không**
> thay thế ADR cũ; mục 6 liệt kê chính xác chỗ cần sửa ở file Approved.

## 1. Kết luận nhanh

1. **Thiết kế SA hiện có đúng hướng** (phân tầng T0/T1/T2, gate purge, agent
   ngoài app, verify 3 lớp, ký gửi khoá, đánh dấu gián đoạn hash-chain) — giữ.
2. **Chưa có gì được hiện thực hoá**: không có story/spec/code/script; Postgres
   chưa bật WAL archive; dữ liệu nằm trên Docker named volume (không phải ZFS).
   ⇒ Hôm nay **không có bản sao lưu nào** cho DB lẫn file.
3. **4 giả định của SA sai so với code/hạ tầng thực** (mục 2.2) — trong đó 2
   cái phá vỡ điều kiện nhất quán DB↔object. Phương án chốt ở mục 3 sửa các
   điểm này.
4. **Dung lượng NAS là ràng buộc cứng**: NAS chỉ chứa được bản sao đầy đủ của
   kho object khi kho ≤ ~60 TB (mục 3.5). Cần quyết định trước go-live.

## 2. Hiện trạng (có bằng chứng)

### 2.1. Mức độ hiện thực hoá

| Hạng mục | Trạng thái | Bằng chứng |
|---|---|---|
| SA | Approved | `backup-restore.md` |
| BA / DEV spec / code / `backup-agent` | **Chưa có** | `features.md` dòng 14; không có package `backup/` |
| Bảng `backup_run` | **Chưa có** ⇒ gate purge *fail closed* ⇒ **job `trash-purge` không bao giờ chạy** (trừ khi tắt `backup.purge_gate_enabled`) | `TrashPurgeJob.java:76-82` |
| PostgreSQL WAL archive / PITR | **Chưa bật** (image mặc định, không `archive_mode`) | `stg/docker-compose.yml` service `postgres` |
| Lưu trữ | Docker named volume (`postgres_data`, `seaweedfs_data`…) trên đĩa local, **không phải dataset ZFS trên SAN** | `stg/docker-compose.yml` mục `volumes` |
| OpenSearch snapshot repo | Chưa có `path.repo` | `stg/docker-compose.yml` |
| Reindex toàn bộ từ PostgreSQL | **Chưa có** — chỉ có outbox poller; outbox đã xử lý bị dọn theo `classification.outbox_retention_days` | `SearchIndexOutboxPoller`, `OutboxRetentionJob` |

### 2.2. Giả định của SA lệch với thực tế

| # | SA giả định | Thực tế | Hệ quả |
|---|---|---|---|
| G1 | Filer store SeaweedFS nằm trong PostgreSQL | `weed server -filer` không có `filer.toml` ⇒ filer store = **leveldb2 trong `/data`**, cùng chỗ với volume | Tin tốt: 1 snapshot ZFS chụp **nguyên tử** cả filer + volume. Phải sửa mô tả SA |
| G2 | Object write-once, chỉ job purge mới xoá | **Bucket `personal` xoá cứng bất kỳ lúc nào** (`PersonalService.deleteFile`, `:609`) và khi bù trừ upload lỗi (`:483`) — không qua gate. Trên stg, identity `external-ai` đã được nâng lên Write+Delete bucket `documents` | **Phá điều kiện "tập cha"** của Quyết định 2: xoá trong cửa sổ `[T0, T0+δ]` ⇒ tham chiếu gãy khi restore |
| G3 | Purge an toàn theo thứ tự | `TrashPurgeService.purgeOne` **xoá object trước khi transaction commit** (`:205-212`, trong `@Transactional`). Commit lỗi ⇒ DB rollback, object đã mất ⇒ **tham chiếu gãy ngay trên hệ đang chạy** | Bug độc lập với backup, phải sửa |
| G4 | NAS ≥ 140 TB | QNAP TS-h3087XU-RP **6×24 TB = 144 TB thô**; RAIDZ2 ⇒ ~87 TiB, chừa 20% trống ⇒ **~65-70 TiB dùng thực** | GFS 14/8/12 cho object không khả thi khi kho > ~60 TB |
| G5 | NAS hỗ trợ `zfs receive` (đã "xác nhận") | QuTS hero là ZFS nhưng nhận stream `zfs send --raw` từ host Linux bên ngoài **không phải tính năng QNAP hỗ trợ chính thức**; tương thích feature flag encryption chưa kiểm | Phải **PoC** trước khi chốt; có phương án dự phòng (mục 3.3) |
| G6 | δ (DB point → snapshot) ≤ 15 phút | Không cần lâu vậy: `zfs snapshot` mất < 1 s; dùng **LSN + PITR** thay cho "dump tại T0" | Cửa sổ khoá xoá rút từ 15 phút xuống vài giây |

Ghi chú phụ (ngoài phạm vi nhưng ảnh hưởng backup cấu hình): `stg/config/seaweedfs/s3.json`
chứa secret thật trong git; Keycloak chạy `start-dev` (prod phải `start`).

## 3. Phương án BACKUP (chốt)

### 3.1. Bố trí lưu trữ production (điều kiện tiên quyết)

Bỏ Docker named volume, **bind-mount dataset ZFS** (mã hoá native, 1 khoá/pool):

| Dataset | Nội dung | Pool |
|---|---|---|
| `ssd/pg` | PGDATA (cluster chứa DB app + DB `keycloak`) | SSD |
| `ssd/search/opensearch`, `ssd/search/os-snap` | data OpenSearch + snapshot repo (`path.repo`) | SSD |
| `ssd/config` | `/opt/aismartstore`: compose, `.env`, nginx, `s3.json`, `clamd.conf`, realm export, **tar image Docker đúng phiên bản đang chạy**, chữ ký ClamAV, `rabbitmq definitions.json` | SSD |
| `hdd/seaweed` | `/data` của SeaweedFS: volume + master + **filerldb2** (giữ filer embedded — chốt G1) — `recordsize=1M` | HDD |

### 3.2. Từng thành phần

| Thành phần | Cơ chế | Tần suất | Giữ | RPO |
|---|---|---|---|---|
| **PostgreSQL** (app + keycloak) | **pgBackRest** (OSS, đóng gói sẵn vào image postgres custom như OpenSearch): `archive_mode=on`, `archive_command=pgbackrest archive-push`, `archive_timeout=60s`; repo trên NAS (NFS), `repo-cipher-type=aes-256-cbc` | Full CN 01:00, diff hằng ngày 01:30, WAL liên tục | PITR 14 ngày (`repo1-retention-full-type=time`, 14) | **~1 phút** |
| PostgreSQL — bản logic | `pg_dump -Fc` từng DB (app, keycloak) + SHA-256 | App: tháng; keycloak: ngày | 12 tháng / 30 bản | — |
| **Object (SeaweedFS)** | `zfs snapshot hdd/seaweed@a-<ts>` + `zfs send -w -I` sang NAS | 02:00, 06:00, 10:00, 14:00, 18:00, 22:00 | SAN 7 ngày; NAS theo 3.5 | **≤ 4 giờ** |
| Filer metadata (bổ sung) | `weed shell fs.meta.save` ra `ssd/config/seaweed-meta/` | Hằng ngày | 14 | — |
| **OpenSearch** (gồm vector k-NN — Qdrant đã gỡ 2026-10-10) | `_snapshot/fs_repo` → `ssd/search/os-snap` → ZFS send | Tuần (CN 03:30) | 4 | 7 ngày |
| Cấu hình | ZFS snapshot `ssd/config` + send | Ngày + **trước mỗi lần deploy** | 30 | 1 ngày |
| Không backup | Redis, queue RabbitMQ (chỉ giữ definitions), vùng tạm OCR/STT, log container | — | — | — |

### 3.3. Đích NAS — 2 đường, chọn sau PoC (G5)

- **A (ưu tiên nếu PoC pass):** `zfs send -w -I | ssh nas zfs receive -u`; tài
  khoản NAS chỉ có quyền receive, không destroy.
- **B (dự phòng, chắc chắn chạy):** NAS xuất **1 LUN iSCSI** → host import thành
  pool `bkp` → `zfs receive` cục bộ. Chống xoá (ransomware) bằng **snapshot
  phía QNAP trên LUN** (host không có quyền xoá), giữ 14 ngày.
- Repo pgBackRest: share NFS riêng trên NAS, user riêng, bật snapshot QNAP.
- **PoC phải chứng minh:** receive raw-encrypted stream, `zfs send -I` gia tăng,
  restore ngược về host đọc được dữ liệu, tốc độ ≥ 300 MB/s.

### 3.4. Điểm nhất quán DB ↔ object (thay Quyết định 2)

Mỗi snapshot object (6 lần/ngày) đều là một **bộ khôi phục** `(S, L_S)`:

1. Agent lấy advisory lock `backup_window` trong Postgres (`pg_advisory_lock`).
2. `SELECT pg_current_wal_lsn(), now()` ⇒ `L_S`.
3. `zfs snapshot hdd/seaweed@a-<ts>` (+ `ssd/config` nếu tới kỳ) ⇒ `S`.
4. Nhả lock, ghi `backup_run(snapshot=S, lsn=L_S, consistency_point_at=now())`.

Bước 1–4 < 5 giây. Đúng vì: mọi upload **ghi object trước rồi mới commit
row** (`DocumentsService`, `PersonalService`) ⇒ row commit ≤ `L_S` thì object
đã có trong `S` — **với điều kiện không có xoá object nào trong cửa sổ**. Do đó:

- **Mọi lệnh xoá object** (purge, personal delete, bù trừ upload) chuyển sang
  **hàng đợi `object_delete_queue`** (ghi trong cùng transaction với việc xoá
  row); một sweeper xoá object **sau khi commit**, và giữ
  `pg_advisory_lock_shared(backup_window)` khi xoá ⇒ tự đứng chờ khi agent đang
  chụp. Sửa luôn G2 + G3 + rò rỉ object khi crash.
- Identity `external-ai` trên prod: **chỉ Read+List** bucket `documents`; nếu AI
  cần ghi, dùng bucket riêng không được DB tham chiếu.
- Gate purge giữ nguyên ý SA (cần `backup_run` hôm nay có `consistency_point_at`)
  — nay snapshot 02:00 là điều kiện đủ.

### 3.5. Dung lượng & chính sách giữ trên NAS

Object gần như bất biến ⇒ dung lượng NAS ≈ **kích thước kho object hiện tại +
object đã xoá còn nằm trong snapshot được giữ + churn do vacuum SeaweedFS** +
repo PG (nhỏ). Số lượng bản giữ gần như **không** làm tăng dung lượng; cái
quyết định là **kích thước kho**.

| Kho object (live) | Chính sách | Ghi chú |
|---|---|---|
| ≤ 45 TB | GFS 14 ngày / 8 tuần / 12 tháng | Đầy đủ như SA |
| 45–60 TB | 14 ngày / 4 tuần / 3 tháng | Cảnh báo R3, lập kế hoạch mở rộng NAS |
| > 60 TB | **NAS không đủ** | Phải mở rộng NAS (thêm đĩa/kệ) hoặc loại bucket `personal` khỏi backup — **quyết định của khách hàng** (câu hỏi Q1) |

Giảm churn: `garbageThreshold` vacuum 0.3, chạy vacuum **1 lần/tuần** (CN 04:00)
thay vì mặc định, vì mỗi lần compact viết lại volume 1 GB ⇒ phình incremental.

### 3.6. Verify & giám sát

- Sau mỗi lần send: so GUID snapshot nguồn/đích. Hằng ngày: `pgbackrest
  verify`. Tuần: `zpool scrub` SAN; tháng: scrub NAS.
- `backup_run.status`: `STARTED → SNAPSHOTTED → REPLICATING → VERIFIED | FAILED | CORRUPTED`.
- Banner `admin/backup` (theo SA): tuổi bản VERIFIED gần nhất > 26 h; WAL archive
  trễ > 15 phút (`pg_stat_archiver.last_failed_time`); NAS > 70 %.

## 4. Phương án RESTORE (chốt)

### 4.1. Nguyên tắc

1. **Chụp hiện trạng trước khi khôi phục** (`zfs snapshot …@pre-restore-<ts>`,
   đổi tên PGDATA cũ) ⇒ mọi lần khôi phục đều **quay lui được**.
2. Bật **chế độ bảo trì**: nginx trả 503, dừng backend + toàn bộ job
   (`trash-purge`, sweeper, outbox poller). Giữ `backup.purge_gate_enabled=true`
   — purge chỉ chạy lại sau khi đã có bộ khôi phục mới tạo **sau** restore.
3. **Quy tắc ghép:** DB phải ở thời điểm **≤ snapshot object**. Nếu chủ động
   cho DB mới hơn snapshot (để giảm mất metadata), bắt buộc chạy đối soát và
   đánh dấu tài liệu `MISSING_OBJECT` (mục 4.2) — không bao giờ để tham chiếu
   gãy im lặng.
4. Nếu DB hỏng còn đọc được: **export `audit_log` + danh sách sự kiện
   `trash.purge`/`personal.file.delete` sau điểm khôi phục** trước khi ghi đè
   (dùng để áp lại xoá cứng ở bước sau, tránh "hồi sinh" dữ liệu đã xoá hợp lệ).
5. Sau restore: ghi `system.restore` (tail hash trước/sau, khoảng thời gian
   mất) theo Quyết định 7 SA.

### 4.2. Checklist sau mọi lần khôi phục (DoD)

1. Postgres lên, `flyway info` khớp phiên bản image backend đang chạy.
2. **Đối soát 2 chiều** DB ↔ S3 (`documents`, `personal`, `audit-archive`):
   row có `storage_key` không có object ⇒ `MISSING_OBJECT` + danh sách cho R3;
   object không có row ⇒ báo cáo mồ côi (không tự xoá).
3. Mẫu 100 tài liệu: tải object, SHA-256 khớp `document.sha256`.
4. Áp lại các purge/delete đã export ở 4.1-4.
5. Keycloak: đăng nhập 1 user mỗi role R1–R4.
6. RabbitMQ: nạp `definitions.json` (tránh lỗi 406 do lệch queue args).
7. Chỉ mục: restore snapshot OpenSearch + **reindex phần chênh** từ
   PostgreSQL theo `updated_at > thời điểm snapshot chỉ mục`; số doc OpenSearch
   = số document ACTIVE.
8. Kích lại các lượt số hoá đang dở (run `RUNNING` tại điểm khôi phục).
9. Tạo ngay 1 bộ khôi phục mới, VERIFIED ⇒ mở dịch vụ, gỡ bảo trì.

## 5. Kịch bản cụ thể

Giả định chung: sự cố phát hiện lúc **14:20**; snapshot object gần nhất
**14:00** (`L_14`), trước đó 10:00; WAL liên tục tới 14:19.

| # | Kịch bản | Mức | Cách xử lý | Mất dữ liệu | RTO mục tiêu |
|---|---|---|---|---|---|
| K1 | R1 xoá nhầm tài liệu, còn trong thùng rác | App | Khôi phục từ thùng rác (FR-27), không động tới backup | 0 | phút |
| K2 | Tài liệu đã bị purge 03:00 hôm nay, chủ sở hữu cần lại | L1 | Phiếu R1→R2 duyệt; `zfs clone` snapshot 02:00 + PITR Postgres tới `L_02` vào **sandbox** (cổng riêng, mạng tách); lấy object, kiểm SHA-256; nạp lại qua ingest như tài liệu mới; huỷ sandbox ≤ 24 h | 0 (metadata phụ như tag/ghi chú phải nhập lại) | ≤ 4 h |
| K3 | Admin chạy SQL sai lúc 10:37, sửa/xoá hàng loạt metadata (vd. xoá `document_group_share`) | L2 / L1 | **Ưu tiên khôi phục từng phần:** PITR vào sandbox tới 10:36, export đúng các bảng/row bị ảnh hưởng, nạp lại vào DB sống (script có review). **Chỉ** PITR toàn DB tới 10:36 khi phạm vi lỗi không xác định được — khi đó object sống giữ nguyên (DB cũ hơn object ⇒ chỉ sinh mồ côi), mọi thay đổi 10:36→14:20 mất và phải thông báo | Từng phần: 0; toàn DB: ~3,7 h metadata | ≤ 4 h |
| K4 | Ổ SSD/PGDATA hỏng (corruption, `PANIC`), object nguyên vẹn | L2 | Restore pgBackRest full+diff mới nhất + replay WAL tới hết (`--type=default`) lên dataset mới; object **không** rollback | ≤ 1 phút | ≤ 4 h |
| K5 | Pool HDD hỏng (mất > 2 đĩa trong một nhóm RAID), Postgres nguyên vẹn | L2 | Tạo pool mới, `zfs receive` snapshot 14:00 từ NAS (hoặc snapshot trên SAN nếu còn). **Giữ DB hiện tại** (không PITR) ⇒ tài liệu tải lên 14:00→14:20 thành `MISSING_OBJECT`, gửi danh sách cho người tải để tải lại | Tệp của ≤ 4 h gần nhất (metadata còn) | 4 h + thời gian truyền (≈ 1 h / 3 TB ở 300 MB/s ⇒ **50 TB ≈ 2 ngày**, xem rủi ro) |
| K6 | Ransomware / máy chủ bị chiếm quyền, dữ liệu bị mã hoá lúc ~13:00 | L3 | Cô lập mạng; **không tin host** — cài lại OS từ media sạch; xác định thời điểm xâm nhập từ audit/log; khôi phục từ **snapshot QNAP phía NAS** (host không xoá được) **trước** thời điểm đó, vd. 10:00 + PITR tới `L_10`; đổi toàn bộ secret (DB, S3, Keycloak client, `.env`) | ≥ 4 h (tới điểm sạch) | ≤ 24 h + chỉ mục |
| K7 | Mất toàn bộ SAN hoặc máy chủ quản lý (cháy bo mạch, SAN controller) | L3 | Chờ phần cứng → tạo pool, mở khoá ZFS từ ký gửi (2 người) → receive `ssd/config` (lấy compose, `.env`, image tar) → receive `hdd/seaweed@14:00` → pgBackRest PITR tới `L_14` (**đúng điểm ghép**) → Keycloak/RabbitMQ definitions → mở dịch vụ suy giảm (chưa có tìm kiếm) → restore snapshot OpenSearch + reindex phần chênh | ≤ 4 h (cả DB, để khớp object) | ≤ 24 h lõi + ≤ 72 h tìm kiếm |
| K8 | Bản sao mới nhất verify FAIL / không mở được | — | Đánh dấu `CORRUPTED`, lùi về bộ trước (`S`, `L_S` liền trước); với Postgres có thể đi tiếp WAL từ base cũ hơn tới `L` mới hơn nếu chuỗi WAL liền mạch | +4 h mỗi bậc lùi | +1–2 h |
| K9 | Khôi phục đang chạy thì lỗi giữa chừng | — | Quay lui về `@pre-restore-<ts>` + PGDATA cũ (nguyên tắc 4.1-1); phân tích rồi chạy lại | — | — |
| K10 | Chỉ OpenSearch hỏng | T1 | Restore snapshot tuần + reindex phần chênh từ PostgreSQL; segment thiếu vector ⇒ đẩy lại lệnh embed cho tài liệu `updated_at >` snapshot (A100); không đụng DB/object | 0 (dữ liệu gốc) | ≤ 12 h toàn văn; ngữ nghĩa theo khối lượng re-embed |
| K11 | Upgrade lỗi (Flyway migration hỏng giữa chừng, như sự cố checksum trên stg) | L2 | **Bắt buộc** trước mỗi deploy: tạo bộ khôi phục thủ công `(S, L)` + snapshot `ssd/config`. Lỗi ⇒ PITR tới `L`, rollback `ssd/config` (image cũ), object giữ nguyên | 0 (đã dừng ghi khi deploy) | ≤ 2 h |
| K12 | Admin Keycloak xoá nhầm hàng loạt user/role | L1 | Restore `pg_dump` DB `keycloak` đêm qua (hoặc PITR cluster vào sandbox) sang instance Keycloak tạm, export user/role bị xoá, import lại qua Admin API. Không rollback DB app | Thay đổi IAM sau bản dump | ≤ 4 h |
| K13 | Mất khoá mã hoá ZFS trên máy chủ (thay máy) | — | Lấy khoá từ ký gửi (USB niêm phong / bản in), 2 người; lập biên bản. Mất cả 2 bản ký gửi = **mất toàn bộ backup** | — | — |

## 6. Việc cần làm (theo thứ tự ưu tiên)

1. **[P0] Sửa bug G3** (purge xoá object trước commit) + chuyển mọi xoá object
   sang `object_delete_queue` (G2) — làm được ngay, độc lập hạ tầng.
2. **[P0] PoC NAS** (3.3) với C02: đường A hay B.
3. **[P0] Bật pgBackRest + WAL archive** (kể cả trên stg — hiện stg không có
   bản sao lưu nào).
4. [P1] Chuyển prod sang bind-mount dataset ZFS (3.1); khoá `external-ai` về
   Read+List trên prod.
5. [P1] `backup-agent` + bảng `backup_run`/`restore_run` + API báo cáo (SA QĐ4)
   — đồng thời mở lại job purge (đang bị gate chặn).
6. [P1] Công cụ: đối soát DB↔S3; reindex OpenSearch từ PostgreSQL theo
   mốc thời gian; profile compose sandbox.
7. [P2] Runbook K1–K13 cho C02; diễn tập quý đầu tiên đo RTO thật (đặc biệt K5/K7
   — thời gian truyền 50 TB từ NAS có thể vượt RTO 24 h).
8. Cập nhật `backup-restore.md`: QĐ1 (pgBackRest, filer embedded, thêm
   `definitions.json`/image tar), QĐ2 (thay bằng 3.4), số dung lượng NAS (G4),
   RPO DB 1 phút. Sau đó `/ba-story docs/01-sa/architecture/backup-restore.md`.

## 7. Câu hỏi mở

- [ ] Q1. Khi kho object > 60 TB: mở rộng NAS (số khay trống trên TS-h3087XU-RP?)
      hay loại bucket `personal` khỏi backup?
- [ ] Q2. RTO 24 h cho K7 có tính cả thời gian kéo ~50 TB+ từ NAS (~2 ngày ở
      300 MB/s) không? Nếu có, cần chấp nhận "mở dịch vụ trước, tệp cũ về dần"
      (SeaweedFS tải volume theo thứ tự mới→cũ) hoặc nâng đường truyền 25 GbE.
- [ ] Q3. Đồng ý dùng pgBackRest (OSS) thay cho script `pg_basebackup` tự viết?
- [ ] Q4. Đồng ý ở K5 giữ DB hiện tại + đánh dấu `MISSING_OBJECT` thay vì PITR
      DB lùi về 14:00?
