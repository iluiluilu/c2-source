# Architecture: backup-restore

> File: `docs/01-sa/architecture/backup-restore.md` — Owner: SA
> Status: Approved (2026-09-09)

## 1. Bối cảnh / Vấn đề

- **FR-34 — Sao lưu định kỳ** (SRS mục 2.12 & 2.13, TSKT #22): hệ thống tự động
  sao lưu **toàn bộ dữ liệu nghiệp vụ + chỉ mục + tệp gốc + cấu hình** theo lịch,
  **khớp nhau về thời điểm**, chuyển tới nơi lưu trữ đã cấu hình, kiểm tra toàn
  vẹn, ghi nhật ký kết quả.
- **FR-35 — Khôi phục** (TSKT #23): R3 chọn điểm khôi phục → khôi phục dữ liệu +
  cấu hình **cùng thời điểm** → kiểm tra toàn vẹn/đối khớp → xác nhận hoạt động,
  ghi nhật ký. Bản sao hỏng → chuyển bản khác; khôi phục dở dang → quay lui.
- **Vai trò:** ma trận SRS mục 3 — `Sao lưu & khôi phục (FR-34–35) | – | – | X
  (R3) | –` ⇒ **chỉ R3**; R1/R2/R4 không có bất kỳ chức năng nào ở slug này.
- **NFR-06:** "Sao lưu định kỳ toàn bộ dữ liệu và cấu hình; có thể khôi phục khi
  xảy ra sự cố, **kiểm thử khôi phục theo định kỳ**"; ghi chú của SRS: *"Thời
  gian khôi phục mục tiêu sẽ chốt cụ thể theo yêu cầu vận hành thực tế"* ⇒ **SRS
  cố ý bỏ trống RTO/RPO** → tài liệu này phải đề xuất số (mục 3, đánh dấu `[ĐX]`).

### 1.1. Ranh giới SRS ↔ `kien_truc_he_thong.adoc` ↔ tài liệu này (bắt buộc đọc)

Changelog SRS v1.7 (dòng 25) đã **cố ý gỡ** mọi chi tiết công nghệ khỏi FR-34/35
(PostgreSQL, SeaweedFS/filer store, OpenSearch, ZFS, `zfs send/receive`,
"đối tượng mồ côi", nguyên tắc 3-2-1) và trỏ sang `kien_truc_he_thong.adoc`.

| Tầng | Nguồn | Nội dung | Cách dùng ở đây |
|---|---|---|---|
| Nghiệp vụ (cái gì / ai / khi nào) | SRS FR-34, FR-35, NFR-06 | Lịch định kỳ, khớp thời điểm, kiểm tra toàn vẹn, ghi nhật ký, R3 là tác nhân duy nhất | **Nguồn yêu cầu** — không diễn giải lại |
| Cơ chế kỹ thuật nền (bằng gì) | `../docs/kien_truc_he_thong.adoc` mục 3 & 4 (v1.1) | ZFS trên LUN SAN qua iSCSI; ZFS native encryption at-rest; checksum + scrub; snapshot read-only COW làm lớp WORM chính; **sao lưu gia tăng sang NAS bằng `zfs send/receive`**; NAS ≥140TB là **đích sao lưu duy nhất**, nhỏ hơn SAN ⇒ **sao lưu phân tầng**; SeaweedFS tách metadata (filer store trong PostgreSQL) khỏi dữ liệu (ZFS) ⇒ **phải snapshot ZFS và dump PostgreSQL gần như đồng thời**, nếu không sinh object "mồ côi" | **Dẫn chiếu, KHÔNG quyết định lại.** Mọi ADR dưới đây chỉ *cụ thể hoá*, không đổi lựa chọn công nghệ |
| Vận hành & tích hợp ứng dụng (làm thế nào, khi nào, ai bấm) | **Tài liệu này** | RTO/RPO bằng số; **thứ tự tạo điểm nhất quán**; lịch chạy + gate với job purge của `delete-restore-trash`; phạm vi sao lưu từng kho dữ liệu; chính sách giữ bản sao (GFS); orchestrator sao lưu tách khỏi vòng đời app; ghi nhận `backup_run` + audit (FR-09); tham số trong `system_config` (FR-01); quy trình khôi phục 3 mức + **sandbox khôi phục**; kiểm thử khôi phục định kỳ; quản lý khoá mã hoá | **Phần còn thiếu — SA quyết định ở đây** |

- **In-scope:** phạm vi & phân tầng sao lưu; điểm nhất quán metadata↔object;
  lịch + đồng bộ với job purge; orchestration & báo cáo kết quả cho UI R3;
  giữ/xoay vòng bản sao; quy trình + phân mức khôi phục; kiểm thử khôi phục định
  kỳ; audit; tham số cấu hình; quản lý khoá.
- **Out-of-scope:** thiết kế hạ tầng phần cứng/mạng (đã chốt ở
  `kien_truc_he_thong.adoc`); HA/failover trực tuyến (không có node dự phòng
  trong cấu hình đã cấp — sao lưu ≠ HA); thùng rác & phục hồi cấp người dùng
  (`delete-restore-trash.md`, FR-26/27 nhánh R1); archive nhật ký hết hạn
  (`audit-logging.md` Quyết định 5 — cơ chế riêng, tài liệu này chỉ **bảo vệ**
  file archive đó); CI/CD & runbook triển khai.

## 2. Ràng buộc (Constraints)

- **On-prem, VLAN cô lập, không Internet/BCAnet** ⇒ không dùng cloud backup,
  không tải được công cụ/agent thương mại khi đang vận hành; mọi thứ phải chạy
  bằng thành phần đã có trên máy chủ quản lý.
- **Chỉ có 1 máy chủ quản lý + 1 SAN + 1 NAS** ⇒ **2 bản sao, 1 site, 0 bản
  offline**. Nguyên tắc 3-2-1 (bị gỡ khỏi SRS) **chưa được thoả** — xem Rủi ro
  và Câu hỏi mở, không được im lặng bỏ qua.
- **NAS ≥140TB < SAN (~320TB HDD + ~61TB SSD raw)** ⇒ **không thể** giữ nhiều
  bản đầy đủ của mọi kho; bắt buộc phân tầng theo giá trị dữ liệu (mục Quyết
  định 1) và gia tăng (`zfs send -i`).
- **SeaweedFS tách metadata/dữ liệu** (filer store trong PostgreSQL) ⇒ ràng buộc
  cứng về thứ tự và điểm nhất quán (Quyết định 2).
- **Tệp gốc là write-once/WORM** (NFR-05, FR-09): object không bao giờ bị sửa,
  chỉ được thêm hoặc bị xoá cứng bởi job purge ⇒ cho phép một quy tắc nhất quán
  đơn giản hơn transaction 2 pha (Quyết định 2).
- **`audit_log` append-only + hash-chain một tail** (`audit-logging.md`, Approved)
  ⇒ khôi phục DB về quá khứ làm **đứt/lùi chuỗi hash**; phải có quy trình đóng
  dấu điểm gián đoạn, không được ghi đè lịch sử.
- **NFR-04 / tách biệt vai trò:** R3 **không** mặc nhiên được xem nội dung nghiệp
  vụ ⇒ khôi phục ở mức tệp đơn lẻ không được biến thành kênh đọc dữ liệu của R3.
- **Job `trash-purge` 03:00** (`delete-restore-trash.md`, Quyết định 5) xoá cứng
  **không hoàn tác được** và xoá cả object trên SeaweedFS ⇒ phải nằm sau điểm
  nhất quán của ngày (Quyết định 3 trả lời câu hỏi mở của file đó).
- **`backup.schedule_cron`** đã được đăng ký sẵn trong `system-config.md`
  (Approved, Quyết định 3): mặc định **hàng ngày 02:00**, phân loại **"nhạy cảm"
  — sửa phải xác nhận 2 bước** ⇒ tài liệu này **kế thừa**, chỉ bổ sung tham số.
- **Chỉ mục dựng lại được từ PostgreSQL** (`ai-search-infra.md`, Approved):
  reindex 1 triệu tài liệu ≤ 12h nếu **không** re-embed, ≤ 72h nếu phải re-embed
  toàn bộ ⇒ ưu tiên sao lưu chỉ mục vector (tránh chiếm GPU nhiều ngày). **Sửa
  2026-10-10:** vector nằm trong index `segment` của OpenSearch (Qdrant đã gỡ —
  `search-semantic.md` QĐ 6) ⇒ OpenSearch lên T1.
- Stack thực tế đã có (`docker-compose.yml`): Keycloak 24,
  PostgreSQL 16, SeaweedFS 3.97, ClamAV 1.4, OpenSearch 2.17 (Qdrant 1.12 đã gỡ 2026-10-10),
  RabbitMQ 3.13, backend Spring Boot, web Next.js. **Chưa có Redis** (sẽ thêm
  theo `system-config.md`) — Redis là cache, **không** sao lưu.
- Các con số dung lượng/đấu nối trong `kien_truc_he_thong.adoc` **còn phải xác
  nhận lại với C02 và bên thiết kế hạ tầng** (ghi chú đầu tài liệu đó) ⇒ mọi số
  liệu dung lượng ở đây là **đề xuất có điều kiện**.

## 3. NFR (Non-Functional Requirements)

RTO/RPO dưới đây ban đầu là **đề xuất của SA** (`[ĐX]`) vì NFR-06 để ngỏ; đã
được khách hàng **xác nhận chính thức 2026-09-13** (mục 7), giữ nguyên số liệu,
không đổi.

| Loại | Yêu cầu |
|---|---|
| Performance | `[ĐX]` Tạo **điểm nhất quán** (dump PostgreSQL + snapshot ZFS) ≤ **15 phút** và không làm dừng dịch vụ (chấp nhận suy giảm hiệu năng ≤ 20% trong cửa sổ). Nhân bản gia tăng sang NAS chạy nền, **không** giới hạn thời gian nhưng phải xong trước cửa sổ kế tiếp; nếu quá 20h ⇒ cảnh báo. Sao lưu **không** được làm chậm giao dịch người dùng ngoài cửa sổ 02:00–06:00 |
| Security | Bản sao trên NAS **giữ nguyên mã hoá** (`zfs send --raw`, NAS không nắm khoá); NAS chỉ nhận kết nối từ máy chủ quản lý, tài khoản ghi riêng, **không** dùng chung tài khoản ứng dụng; khoá ZFS lưu ký gửi ngoại tuyến (Quyết định 6); mọi thao tác khôi phục đều audit; R3 truy cập sandbox khôi phục phải có yêu cầu được duyệt + audit (NFR-04) |
| Scalability | Kích thước sao lưu tăng ~tuyến tính theo dữ liệu nạp mới (object bất biến ⇒ increment ≈ dữ liệu mới); cảnh báo dung lượng NAS ở **70%** (cảnh báo) / **85%** (bắt đầu tự tỉa bản cũ theo chính sách GFS) `[ĐX]`; chính sách giữ bản sao thay đổi được qua cấu hình, không sửa code |
| Availability | `[ĐX]` **RPO** — metadata/cấu hình/nhật ký (PostgreSQL): **≤ 15 phút**; tệp gốc & dữ liệu object: **≤ 4 giờ**; chỉ mục tìm kiếm (OpenSearch): **≤ 7 ngày** (dựng lại được). `[ĐX]` **RTO** — (a) khôi phục 1 tài liệu/1 tập nhỏ qua sandbox: **≤ 4 giờ**; (b) khôi phục PostgreSQL (hỏng DB, phần cứng còn nguyên): **≤ 4 giờ**; (c) thảm hoạ toàn hệ (mất máy chủ quản lý hoặc SAN), **tính từ khi phần cứng thay thế sẵn sàng**: **≤ 24 giờ** cho chức năng lõi (đăng nhập, tra cứu metadata, tải tệp gốc); tìm kiếm toàn văn/ngữ nghĩa được phép **chạy suy giảm** thêm ≤ 72 giờ để dựng lại chỉ mục |
| Khác | **Kiểm thử khôi phục định kỳ (NFR-06 bắt buộc):** tự động **hàng tháng** (sandbox + đối chiếu SHA-256 trên mẫu ngẫu nhiên), diễn tập thảm hoạ có người **hàng quý** với đo RTO thực tế `[ĐX]`. Tỷ lệ sao lưu thành công ≥ **99%/tháng**; **0** bản sao "thành công giả" (mọi bản đều được verify trước khi tính là hợp lệ) |

## 4. Phương án & Quyết định (ADR)

### Quyết định 1: Phân tầng phạm vi sao lưu (cái gì được sao lưu, ở mức nào)

- **Ngữ cảnh:** NAS (≥140TB) nhỏ hơn SAN; `kien_truc_he_thong.adoc` đã yêu cầu
  "sao lưu phân tầng"; SRS FR-34 cho phép **không** sao lưu đầy đủ dữ liệu tái
  tạo được từ dữ liệu gốc.
- **Các phương án xem xét:** A) sao lưu đầy đủ mọi kho như nhau; B) chỉ sao lưu
  PostgreSQL + object, bỏ hoàn toàn chỉ mục; C) **phân tầng theo giá trị & chi
  phí tái tạo**.
- **Quyết định:** **C** —

  | Tầng | Dữ liệu | Cơ chế | Tần suất | Giữ (GFS) `[ĐX]` |
  |---|---|---|---|---|
  | **T0 — không thể tái tạo** | PostgreSQL: metadata nghiệp vụ, **filer store của SeaweedFS**, tài khoản/nhãn/ghi chú, **`audit_log`**, `system_config`, kết quả OCR/STT **đã kiểm duyệt** (FR-18) | Base backup + **WAL archiving liên tục** (PITR) | Base hằng ngày 02:00; WAL ≤ 15 phút | 14 ngày PITR; base: 14 daily / 8 weekly / 12 monthly |
  | **T0 — không thể tái tạo** | Tệp gốc/object trên dataset ZFS (SeaweedFS volume) | ZFS snapshot + `zfs send -i --raw` | Mỗi **4 giờ** (02:00 là mốc nhất quán của ngày) | Snapshot tại SAN: 7 ngày; tại NAS: 14 daily / 8 weekly / 12 monthly |
  | **T1 — tái tạo tốn kém** | **OpenSearch** (toàn văn + vector k-NN trên index `segment`; re-embed toàn bộ tốn tới 72h GPU) — sửa 2026-10-10, thay Qdrant T1 + OpenSearch T2 | Snapshot repository → cùng luồng T0 | Hàng tuần | 4 bản |
  | **T0 — cấu hình** | **Keycloak realm/DB** (IAM/RBAC — mất là mất toàn bộ định danh), file cấu hình hạ tầng (`docker-compose*.yml`, `application.yml`, cấu hình SeaweedFS/OpenSearch), **danh sách chữ ký ClamAV đang dùng** | Export realm + dump DB + `tar` thư mục cấu hình (có version) | Hằng ngày | 30 bản |
  | **Không sao lưu** | Redis (cache), RabbitMQ (hàng đợi vận chuyển, có outbox làm nguồn), vùng làm việc OCR/STT tạm, artefact build | — | — | — |

- **Lý do:** đúng tinh thần "dữ liệu tái tạo được có thể không cần sao lưu đầy
  đủ" (FR-34) và ràng buộc dung lượng NAS; chỉ mục vector (nay là OpenSearch, trước là Qdrant) được nâng lên T1 vì
  `ai-search-infra.md` đã cảnh báo re-embed toàn bộ chiếm trọn GPU nhiều ngày.
  Keycloak được nêu **tường minh** vì SRS FR-34 chỉ nói "cấu hình" — dễ bị bỏ
  sót và hậu quả là không ai đăng nhập được sau khôi phục.
- **Hệ quả:** sau khôi phục thảm hoạ, tìm kiếm chạy **suy giảm** đến khi chỉ mục
  bắt kịp (đã tính vào RTO mục 3); RabbitMQ không được khôi phục ⇒ mọi sự kiện
  dở dang phải **phát lại từ outbox trong PostgreSQL** (đúng thiết kế
  `ai-search-infra.md` Quyết định 7).

### Quyết định 2: Điểm nhất quán metadata ↔ object — thứ tự "DB trước, snapshot sau"

- **Ngữ cảnh:** ràng buộc lớn nhất của slug (FR-34/35: "khớp nhau về thời điểm";
  `kien_truc_he_thong.adoc`: snapshot ZFS và dump PostgreSQL "gần như đồng thời",
  lệch sẽ sinh object mồ côi). "Gần như đồng thời" là chưa đủ để implement.
- **Các phương án xem xét:** A) đóng băng ghi (đưa hệ thống về read-only vài phút)
  rồi chụp cả hai; B) chụp song song không đóng băng, chấp nhận lệch; C) **ép
  thứ tự: chốt điểm PostgreSQL trước (T0), chụp snapshot ZFS sau (T0+δ)**, kèm
  cấm thao tác xoá object trong khoảng δ.
- **Quyết định:** **C**.
  1. `T0`: ghi bản ghi `backup_run(status=STARTED)`; **đặt cờ `backup.window_open
     = true`** (chặn job purge — Quyết định 3); lấy **điểm nhất quán PostgreSQL**
     (base backup / `pg_dump` nhất quán MVCC — không cần khoá bảng).
  2. `T0+δ` (δ ≤ 15 phút): `zfs snapshot` dataset object (+ dataset chứa export
     OpenSearch/Keycloak nếu tới kỳ). Ghi tên snapshot + LSN/thời điểm DB
     vào `backup_run` ⇒ **cặp (DB point, snapshot) là một "bộ khôi phục" duy nhất**.
  3. Gỡ cờ `window_open`, chuyển sang nhân bản `zfs send -i --raw` sang NAS
     (chạy trên snapshot đã đóng băng, dài bao lâu cũng không ảnh hưởng nhất quán).
- **Lý do:** object là **write-once** ⇒ tập object ở `T0+δ` luôn là **tập cha**
  của tập object mà metadata ở `T0` tham chiếu. Hệ quả: chỉ có thể sinh **object
  thừa** (mồ côi, vô hại, thu hồi được bằng job đối soát) chứ **không bao giờ
  sinh tham chiếu gãy** (metadata trỏ tới object không tồn tại — hỏng dữ liệu
  thật). Phương án A gây gián đoạn dịch vụ không cần thiết; B không có bảo đảm
  nào về chiều lệch nên **có thể** sinh tham chiếu gãy.
- **Điều kiện hiệu lực (bắt buộc):** trong khoảng `[T0, T0+δ]` **không** được
  phép **xoá** object (đó là thao tác duy nhất phá vỡ tính "tập cha") ⇒ khoá cứng
  với job purge, xem Quyết định 3.
- **Quy tắc khôi phục đối ngẫu:** khi khôi phục, **luôn chọn snapshot object có
  thời điểm ≥ điểm DB**; nếu buộc phải ghép chéo bộ, ưu tiên object mới hơn
  metadata, **cấm** ngược lại.
- **Hệ quả:** cần job "đối soát mồ côi" sau khôi phục (liệt kê object trên ZFS
  không có bản ghi filer/`document` tương ứng → báo cáo cho R3, xoá thủ công có
  audit, **không** tự xoá).

### Quyết định 3: Lịch chạy + gate với job `trash-purge` (trả lời câu hỏi mở của `delete-restore-trash.md`)

- **Ngữ cảnh:** `delete-restore-trash.md` mục 7 để mở: *"Chốt giờ chạy job purge
  và mức song song với cửa sổ sao lưu 02:00 — xoá cứng phải nằm **sau** khi bản
  sao lưu của ngày đã hoàn tất"*. Vấn đề thực: nhân bản sang NAS có thể kéo dài
  nhiều giờ ⇒ nếu định nghĩa "hoàn tất" = "đã sao chép xong sang NAS" thì purge
  gần như **không bao giờ** chạy được vào 03:00.
- **Các phương án xem xét:** A) purge chờ toàn bộ quá trình sao lưu sang NAS xong
  mới chạy; B) purge chạy cứng 03:00 không quan tâm sao lưu; C) **purge bị gate
  bởi "điểm nhất quán của ngày đã tạo xong", không phải bởi việc nhân bản**.
- **Quyết định:** **C** — chốt lịch chuẩn:

  | Giờ | Việc | Ràng buộc |
  |---|---|---|
  | **02:00** | `backup.schedule_cron` (đã có ở `system-config.md`): tạo **điểm nhất quán** của ngày (Quyết định 2, ≤ 15 phút) | Đặt `window_open=true` trong suốt bước này |
  | 02:15 → xong | Nhân bản gia tăng sang NAS + verify (có thể tới nhiều giờ) | Chạy trên snapshot đóng băng ⇒ **không** chặn nghiệp vụ, **không** chặn purge |
  | **03:00** | `trash-purge` (`delete-restore-trash.md` Quyết định 5) — giữ nguyên 03:00 | **Gate:** chỉ chạy nếu tồn tại `backup_run` của **ngày hôm nay** có `consistency_point_at IS NOT NULL` và `status IN (REPLICATING, VERIFIED)` **và** `window_open=false`. Không thoả ⇒ **bỏ lượt**, ghi audit `trash.purge.skipped` + cảnh báo R3, thử lại lượt sau |
  | 04:00 (T7) | Kiểm thử khôi phục tự động hằng tháng (Quyết định 5) | Chạy trên sandbox, không đụng production |
  | 06:00, 10:00, 14:00, 18:00, 22:00 | Snapshot + gửi gia tăng object (RPO 4h) | Không tạo điểm nhất quán DB, không gate purge |

- **Lý do:** cái thực sự bảo vệ dữ liệu khỏi purge là **snapshot đã tồn tại**
  (dữ liệu bị xoá cứng vẫn nằm trong snapshot trên SAN), chứ không phải việc bản
  sao đã tới NAS hay chưa. Gate theo snapshot vừa an toàn vừa khả thi về thời
  gian. Phương án A làm purge chết đói và tích tụ rác; B đúng đúng trường hợp
  xấu nhất mà `delete-restore-trash.md` sợ (xoá cứng dữ liệu chưa từng được sao
  lưu, mất vĩnh viễn).
- **Song song:** purge **được phép** chạy song song với nhân bản NAS (khác thiết
  bị, khác I/O path: purge ghi SSD/HDD qua SeaweedFS, replication đọc snapshot);
  nhưng **cấm** chạy trong `[T0, T0+δ]` (điều kiện của Quyết định 2). Giới hạn
  song song đề xuất: purge tối đa **1 lô 200 bản ghi tại một thời điểm** (đã có),
  và tạm dừng nếu tải I/O SAN > 80% `[ĐX]`.
- **Hệ quả quan trọng phải ghi vào tài liệu vận hành:** dữ liệu đã "xoá vĩnh
  viễn" bằng purge **vẫn tồn tại trong các bản sao lưu** cho tới khi snapshot
  chứa nó hết hạn (tối đa ~12 tháng theo GFS ở Quyết định 1) ⇒ (a) dung lượng
  không được giải phóng ngay sau purge — `warehouse-structure.md`/
  `delete-restore-trash.md` phải hiểu rằng "xoá cứng" giải phóng dung lượng
  **trễ**; (b) nếu sau này có yêu cầu "xoá triệt để theo yêu cầu pháp lý", phải
  bổ sung quy trình riêng (mục 7).

### Quyết định 4: Ai chạy sao lưu — agent mức máy chủ, tách khỏi vòng đời ứng dụng

- **Ngữ cảnh:** FR-34 yêu cầu chạy theo lịch cấu hình được (FR-01) và **ghi nhật
  ký kết quả** (FR-09) để R3 xem trên UI; nhưng thao tác thật (`zfs`,
  `pg_basebackup`, gửi sang NAS) nằm ngoài tầm với của container ứng dụng, và
  sao lưu **phải chạy được ngay cả khi backend hỏng**.
- **Các phương án xem xét:** A) Spring `@Scheduled` trong backend gọi script; B)
  **agent mức host (systemd timer/cron) + báo cáo ngược về backend qua API nội
  bộ**; C) thuần vận hành thủ công, không có gì trên UI.
- **Quyết định:** **B**.
  - `backup-agent` (script trên máy chủ quản lý, chạy bằng systemd timer) thực
    thi toàn bộ Quyết định 1–3. Nguồn lịch: đọc `GET /internal/backup/schedule`
    (chỉ localhost + token), **fallback** sang file cấu hình cache cục bộ nếu
    backend chết ⇒ backend chết **không** làm mất bản sao lưu.
  - Agent báo cáo từng bước qua `POST /internal/backup/runs` → backend ghi bảng
    `backup_run` + phát audit event; nếu backend không sẵn sàng, agent **spool
    cục bộ** và gửi lại sau (at-least-once, `run_id` idempotent).
  - Backend cung cấp cho R3: lịch sử `backup_run`, tuổi bản sao thành công gần
    nhất, dung lượng NAS, kết quả verify/kiểm thử khôi phục, và **cảnh báo khi
    không có bản sao thành công > `backup.max_age_alert_hours` (mặc định 26h)**.
- **Lý do:** A biến backend thành điểm chết của khả năng khôi phục (đúng lúc cần
  sao lưu nhất là lúc ứng dụng đang hỏng) và đòi container quyền `zfs`/root — vi
  phạm nguyên tắc tối thiểu đặc quyền. C không thoả FR-34 (ghi nhật ký kết quả,
  cấu hình lịch qua FR-01) và không cho R3 nhìn thấy hệ thống có đang được bảo
  vệ hay không.
- **Hệ quả:** thêm một thành phần ngoài ứng dụng cần đóng gói/triển khai/giám sát
  (script + systemd unit + tài liệu vận hành, lưu trong repo hạ tầng); cần
  endpoint nội bộ có xác thực riêng (không dùng token người dùng), chỉ bind
  loopback; **lịch trong `system_config` là nguồn sự thật, nhưng agent giữ bản
  sao cục bộ** ⇒ đổi lịch trên UI có độ trễ tới lần đồng bộ kế tiếp (≤ 5 phút).

### Quyết định 5: Phân mức khôi phục + "sandbox khôi phục" + kiểm thử định kỳ

- **Ngữ cảnh:** FR-35 nói "chọn điểm khôi phục" như một thao tác; thực tế có 3
  tình huống rất khác nhau. Đặc thù kỹ thuật: SeaweedFS lưu object trong **volume
  file**, nên **không thể** lấy 1 tài liệu ra khỏi snapshot ZFS bằng cách copy
  file thông thường — phải có filer metadata tương ứng mới đọc được.
- **Quyết định:** ba mức, dùng chung một cơ chế **sandbox**:

  | Mức | Tình huống | Cách làm | RTO `[ĐX]` |
  |---|---|---|---|
  | **L1 — Trích xuất chọn lọc** | Cần lại vài tài liệu (vd. FR-27 nhánh R3: dữ liệu đã hết hạn thùng rác/đã purge) | `zfs clone` snapshot cần thiết → dựng **SeaweedFS + PostgreSQL tạm (read-only, cổng riêng, không nối vào hệ chính)** → lấy object qua S3, đối chiếu SHA-256 → **nạp lại như tài liệu mới qua luồng ingest bình thường** (FR-11) | ≤ 4h |
  | **L2 — Khôi phục một kho** | Hỏng logic/hỏng DB, phần cứng còn nguyên | PITR PostgreSQL về mốc chọn; rollback dataset object về snapshot ≥ mốc DB (Quyết định 2); phát lại outbox để chỉ mục bắt kịp | ≤ 4h |
  | **L3 — Thảm hoạ** | Mất máy chủ quản lý hoặc SAN | Dựng lại hạ tầng → `zfs receive` từ NAS → khôi phục PostgreSQL + Keycloak + cấu hình → kiểm tra đối khớp → mở dịch vụ ở chế độ suy giảm → dựng lại chỉ mục | ≤ 24h + ≤ 72h chỉ mục |

- **Vì sao L1 "nạp lại như tài liệu mới" thay vì chèn thẳng bản ghi vào DB
  sống:** chèn tay bản ghi cũ vào DB đang chạy sẽ phá quan hệ khoá ngoại, phá
  dòng "bia mộ" của purge (`delete-restore-trash.md` Quyết định 5) và tạo đường
  ghi dữ liệu không đi qua kiểm soát nào; nạp lại qua ingest giữ được toàn bộ
  kiểm soát quyền, quét virus, audit, và **SHA-256 vẫn khớp bản gốc** nên giá
  trị chứng cứ không mất.
- **Bảo vệ NFR-04 ở L1:** R3 làm thao tác kỹ thuật nhưng **không** được tự ý mở
  nội dung: yêu cầu khôi phục phải có **phiếu yêu cầu của chủ dữ liệu (R1) được
  R2 duyệt** `[ĐX]`; sandbox tự động huỷ sau **24 giờ**; mọi truy cập sandbox ghi
  audit `restore.sandbox.open` / `restore.export`.
- **Kiểm thử khôi phục định kỳ (NFR-06):**
  - **Hàng tháng, tự động:** dựng sandbox từ bộ khôi phục **mới nhất** → kiểm tra
    PostgreSQL khởi động + `pg_verifybackup` OK → lấy **ngẫu nhiên 100 tài liệu**
    `[ĐX]`, tải object, **tính lại SHA-256 và so với metadata** → kiểm tra không
    có tham chiếu gãy trên mẫu → ghi kết quả vào `backup_run` + audit
    `backup.restore_test`; **thất bại ⇒ cảnh báo mức cao cho R3**.
  - **Hàng quý, có người:** diễn tập L3 theo runbook trên môi trường staging, **đo
    RTO thực tế** và cập nhật lại số ở mục 3 nếu lệch.
- **Hệ quả:** cần dung lượng dự phòng cho `zfs clone` (COW nên chỉ tốn phần ghi
  mới, ước tính ≤ 1% `[ĐX]`) và một profile compose riêng cho sandbox; runbook
  L3 là **sản phẩm bắt buộc** của slug này (checklist mục 5 đang thiếu).

### Quyết định 6: Toàn vẹn, mã hoá và quản lý khoá của bản sao

- **Quyết định:**
  - **Mã hoá:** dùng nguyên ZFS native encryption đã chốt ở
    `kien_truc_he_thong.adoc`; nhân bản bằng **`zfs send --raw`** ⇒ NAS chỉ giữ
    dữ liệu đã mã hoá, **không** cần và **không** được có khoá.
  - **Quản lý khoá (rủi ro sống-còn):** mất khoá = mất toàn bộ bản sao. Bắt buộc
    **ký gửi khoá ngoại tuyến**: 2 bản (USB niêm phong + bản in) cất ở 2 két khác
    nhau, thủ tục mở cần **2 người** `[ĐX]`; kiểm tra khoá mở được **mỗi quý**
    trong buổi diễn tập; **cấm** lưu khoá trên chính NAS hoặc trong repo.
  - **Kiểm tra toàn vẹn (FR-34 bước 4):** `zfs scrub` **hàng tuần** trên SAN,
    **hàng tháng** trên NAS `[ĐX]`; đối chiếu GUID snapshot nguồn/đích sau mỗi
    lần `receive`; `pg_verifybackup` + SHA-256 cho mọi artefact dump; **chỉ khi
    tất cả pass** thì `backup_run.status = VERIFIED`; fail ⇒ đánh dấu
    `CORRUPTED` (không dùng để khôi phục — đúng FR-34 "bản sao hỏng → đánh dấu
    không dùng") + cảnh báo + tự động chạy lại một lượt sao lưu đầy đủ.
  - **Chống xoá bản sao:** tài khoản nhân bản trên NAS **không** có quyền xoá
    dataset/snapshot; việc tỉa bản cũ theo GFS do một tiến trình riêng có
    credential khác thực hiện (giảm thiệt hại nếu máy chủ quản lý bị chiếm quyền
    — ransomware xoá luôn bản sao là kịch bản phổ biến nhất).
- **Hệ quả:** không thể kiểm tra nội dung bản sao trực tiếp trên NAS (đã mã hoá)
  ⇒ mọi xác minh nội dung phải qua sandbox (Quyết định 5) — đó là lý do kiểm thử
  khôi phục định kỳ là **bắt buộc**, không phải tuỳ chọn.

### Quyết định 7: Nhật ký, tham số cấu hình và ảnh hưởng lên chuỗi hash của `audit_log`

- **Nhật ký (theo `audit-logging.md` Quyết định 4 — taxonomy `<domain>.<verb>`,
  metadata allowlist):** `backup.run.started`, `backup.run.succeeded`,
  `backup.run.failed`, `backup.verify.failed`, `backup.restore_test`,
  `backup.pruned`, `restore.started`, `restore.completed`, `restore.aborted`,
  `restore.sandbox.open`, `restore.export`, `trash.purge.skipped` (gate ở Quyết
  định 3). `actor = system` cho việc tự động, `actor = <sub của R3>` cho thao
  tác tay. Metadata allowlist: `runId`, `type`, `tier`, `snapshotName`,
  `dbPoint`, `sizeBytes`, `durationMs`, `target`, `resultCode`, `sampleSize`,
  `mismatchCount` — **không** ghi tên tệp/nội dung nghiệp vụ (NFR-04).
- **Tham số bổ sung vào `system_config`** (mở rộng bảng ở `system-config.md`
  Quyết định 3; tất cả đều **nhạy cảm — xác nhận 2 bước**, vì sai giá trị làm
  mất khả năng khôi phục):

  | Key | Mặc định `[ĐX]` |
  |---|---|
  | `backup.schedule_cron` | `0 0 2 * * *` (**đã có**, giữ nguyên 02:00) |
  | `backup.object_incremental_cron` | mỗi 4 giờ (`0 0 6,10,14,18,22 * * *`) |
  | `backup.retention_daily / weekly / monthly` | 14 / 8 / 12 |
  | `backup.wal_archive_timeout_seconds` | 900 (RPO 15 phút) |
  | `backup.max_age_alert_hours` | 26 |
  | `backup.nas_usage_warn_percent / prune_percent` | 70 / 85 |
  | `backup.restore_test_cron` | 04:00 ngày 7 hằng tháng |
  | `backup.purge_gate_enabled` | `true` (**không nên tắt** — xem Quyết định 3) |
  | `backup.legal_hold_purge_enabled` | `false` (mục 7 — bật khi có yêu cầu pháp lý thật; cơ chế thực thi rút ngắn giữ/xoá bổ sung do DEV spec thiết kế lúc bật, **chưa** có ở MVP) |

- **Ảnh hưởng lên `audit_log` (quan trọng, dễ bỏ sót):** khôi phục DB về quá khứ
  làm **mất các bản ghi audit** phát sinh sau điểm khôi phục và làm **tail của
  hash-chain lùi lại**. Quy tắc bắt buộc:
  1. Trước khi khôi phục, nếu còn đọc được DB hỏng ⇒ **export `audit_log` thô ra
     file, lưu như bằng chứng** (kèm hash tail).
  2. Sau khi khôi phục, ghi ngay một sự kiện `system.restore` mô tả **điểm gián
     đoạn** (tail hash trước sự cố, tail hash sau khôi phục, khoảng thời gian bị
     mất) ⇒ chuỗi mới **nối tiếp có ghi chú**, không giả vờ liền mạch.
  3. `audit_checkpoint` + file archive trên S3 (`audit-logging.md` Quyết định 5)
     là bằng chứng độc lập cho giai đoạn bị mất ⇒ **bucket archive đó phải nằm
     trong phạm vi sao lưu T0**.
- **Hệ quả:** `GET /audit/verify` phải hiểu và chấp nhận điểm gián đoạn hợp lệ
  (được đánh dấu bằng `system.restore`), thay vì báo "chuỗi hỏng" — ràng buộc
  thiết kế gửi ngược cho slug `audit-logging` khi implement.

## 5. Tech stack đề xuất

- **Backend (Spring Boot 3):** module `backup/` **chỉ làm mặt điều phối/hiển thị**
  — bảng `backup_run`, `restore_run`; API nội bộ `/internal/backup/*` cho agent;
  API quản trị `/admin/backup/*` (lịch sử, trạng thái, tuổi bản sao, kích hoạt
  kiểm thử khôi phục); hook gate cho job `trash-purge`; `AuditService` (đã có);
  `@Scheduled` + ShedLock (đã đưa vào theo `delete-restore-trash.md`) cho các
  kiểm tra/cảnh báo phía ứng dụng. **Không** thực thi lệnh hạ tầng từ backend.
- **`backup-agent` (mức host):** shell/systemd timer trên máy chủ quản lý dùng
  `zfs snapshot|send|receive|hold`, `pg_basebackup` + `archive_command` (WAL),
  `pg_verifybackup`, `pg_dump` cho Keycloak, snapshot API của OpenSearch,
  `sha256sum`. Không thêm sản phẩm backup thương mại (mạng cách ly).
- **Frontend (Next.js):** trang `admin/backup` theo khuôn các trang admin hiện có
  — trạng thái bản sao gần nhất, lịch sử run, kết quả verify & kiểm thử khôi
  phục, dung lượng NAS, nút "chạy sao lưu ngay"/"chạy kiểm thử khôi phục"; sửa
  tham số đi qua màn hình `admin/system-config` (2 bước) chứ không nhân bản form.
- **Data store:** PostgreSQL 16 (`backup_run`, `restore_run`; Flyway migration
  mới); dataset ZFS trên SAN (nguồn snapshot); NAS (đích `zfs receive` + kho WAL/
  dump); SeaweedFS/S3 cho archive nhật ký (đã có).
- **Infra/Deploy:** systemd unit + timer; profile compose riêng cho **sandbox
  khôi phục** (PostgreSQL + SeaweedFS tạm, cổng riêng, mạng tách biệt); runbook
  L3 + checklist diễn tập trong repo hạ tầng; giám sát: metric số giờ kể từ bản
  sao thành công gần nhất, % dung lượng NAS, kết quả scrub.

## 6. Rủi ro

| Rủi ro | Mức độ | Giảm thiểu |
|---|---|---|
| **Chỉ có 2 bản sao, cùng 1 phòng máy, không có bản offline** (cháy/ngập/ransomware/mất trộm ⇒ mất trắng); nguyên tắc 3-2-1 đã bị gỡ khỏi SRS nhưng vấn đề thì không biến mất | **Cao — rủi ro đã được khách hàng chấp nhận có ghi nhận, 2026-09-13 (mục 7)** | Chính sách an ninh cấm mang thiết bị ra khỏi phòng máy ⇒ **không** làm bản sao lạnh offsite; giảm thiểu còn lại thuần kỹ thuật: tài khoản NAS không có quyền xoá snapshot; snapshot ZFS read-only + `hold` |
| Mất/hỏng khoá mã hoá ZFS ⇒ **toàn bộ bản sao vô dụng** | Cao | Ký gửi khoá ngoại tuyến 2 bản/2 két, thủ tục 2 người, kiểm tra mở khoá mỗi quý (Quyết định 6) |
| "Bản sao thành công giả" — job báo OK nhưng không khôi phục được | Cao | Chỉ `VERIFIED` sau khi verify đủ 3 lớp; kiểm thử khôi phục tự động hằng tháng có đối chiếu SHA-256; cảnh báo khi kiểm thử fail |
| Job purge xoá cứng dữ liệu **chưa từng** được đưa vào snapshot ⇒ mất vĩnh viễn | Cao | Gate bắt buộc ở Quyết định 3 (`backup.purge_gate_enabled=true`), bỏ lượt + cảnh báo thay vì chạy liều |
| Metadata mới hơn object khi khôi phục ⇒ **tham chiếu gãy**, tài liệu mở không được | Cao | Thứ tự "DB trước, snapshot sau" + quy tắc "object ≥ metadata" (Quyết định 2); job đối soát mồ côi sau khôi phục |
| NAS 140TB không đủ cho GFS đề xuất khi dữ liệu tiến gần dung lượng SAN | Trung bình | Phân tầng (Quyết định 1) + cảnh báo 70%/tự tỉa 85%; đo tốc độ tăng thực tế sau 3 tháng rồi hiệu chỉnh chính sách giữ; nâng dung lượng NAS là quyết định hạ tầng (C02) |
| Cửa sổ nhân bản kéo dài quá 20h ⇒ chồng lấn lượt kế tiếp, tụt RPO | Trung bình | Gửi gia tăng (`-i`) trên dữ liệu bất biến; giới hạn băng thông ban ngày; cảnh báo khi run chưa xong trước 22:00; tách lượt "full" ra cuối tuần |
| **Máy chủ quản lý là điểm chết đơn (SPOF)** — sao lưu không giải quyết được thời gian chết | Trung bình | Nêu rõ RTO L3 phụ thuộc thời gian có phần cứng thay thế; đề xuất hợp đồng bảo hành/đổi trả nhanh hoặc máy dự phòng (ngoài phạm vi slug) |
| Khôi phục DB làm đứt hash-chain, mất bản ghi audit của giai đoạn cuối | Trung bình | Quy trình 3 bước ở Quyết định 7 (export trước, đánh dấu `system.restore`, dựa vào `audit_checkpoint` đã archive) |
| Dung lượng không được giải phóng ngay sau purge (block còn bị snapshot giữ) khiến kho chạm ngưỡng 95% | Trung bình | Ghi rõ trong tài liệu vận hành + UI; tính dung lượng snapshot vào báo cáo dung lượng của R3; điều chỉnh số ngày giữ snapshot tại SAN |
| Sandbox khôi phục trở thành kênh để R3 đọc dữ liệu nghiệp vụ (vi phạm NFR-04) | Trung bình | Yêu cầu phiếu R1 đề nghị → R2 duyệt; sandbox tự huỷ 24h; audit mọi truy cập/xuất dữ liệu |
| `backup-agent` là thành phần ngoài ứng dụng, dễ bị quên khi triển khai/nâng cấp | Thấp | Đưa unit file + script vào repo hạ tầng, có kiểm tra "tuổi bản sao gần nhất" hiển thị ngay trên UI R3 (không im lặng thất bại) |

## 7. Câu hỏi mở

> **Cập nhật 2026-09-13:** toàn bộ 7 câu hỏi mở đã được khách hàng/PM xác nhận
> phương án (qua trao đổi ngoài luồng, ghi lại tại đây). Các số/tham số bị đánh
> `[ĐX]` ở mục 3, 4, 5 nay được coi là **chính thức**, không còn là đề xuất chờ
> duyệt.

- [x] **RTO/RPO chính thức:** **đã xác nhận** — chấp nhận nguyên bộ số `[ĐX]`
      ở mục 3: RPO 15 phút (metadata) / 4 giờ (tệp gốc), RTO 4 giờ (L1, L2) /
      24 giờ (L3, tính từ khi có phần cứng thay thế) + 72 giờ dựng lại chỉ mục.
      Khách hàng **không** yêu cầu RTO thảm hoạ < 24h ⇒ **không** cần đầu tư
      máy chủ dự phòng ở giai đoạn này; số liệu sẽ được đo lại thực tế ở diễn
      tập quý đầu tiên (Quyết định 5) và hiệu chỉnh nếu lệch.
- [x] **Bản sao thứ ba/offsite (3-2-1):** **đã xác nhận** — chính sách an ninh
      **cấm** mang thiết bị lưu trữ ra khỏi phòng máy ⇒ **giữ nguyên 2 bản sao,
      1 site, 0 bản offline**. Rủi ro mất trắng khi có sự cố diện rộng (cháy,
      ngập, ransomware diện rộng) **được khách hàng chấp nhận có ghi nhận**
      (xem lại rủi ro đầu tiên ở mục 6 — mức độ giữ nguyên **Cao**, không giảm
      thiểu thêm được bằng thiết kế phần mềm). Không triển khai bản sao lạnh
      hằng quý đã đề xuất ở mục 6.
- [x] **Ai vận hành:** **đã xác nhận** — **đội hạ tầng C02** chịu trách nhiệm
      vận hành `backup-agent`, giữ khoá mã hoá ký gửi (Quyết định 6), và thực
      hiện diễn tập DR hằng quý (Quyết định 5). Đội dự án (backend/frontend)
      **chỉ** cung cấp API/UI báo cáo (`admin/backup`) và **không** nắm khoá.
      Ranh giới bàn giao: runbook L3 (mục 5) là tài liệu bàn giao chính thức
      cho C02, không phải cho đội dự án vận hành.
- [x] **Xác nhận với bên hạ tầng:** **đã xác nhận** — **NAS hỗ trợ `zfs
      receive`** ⇒ thiết kế nhân bản `zfs send -i --raw` ở Quyết định 1, 2, 6
      **giữ nguyên không đổi**, không cần chuyển sang file-stream. (Câu hỏi phụ
      về tier SSD có phải ZFS không **không ảnh hưởng** thiết kế hiện tại vì
      PostgreSQL dùng base backup + WAL, không phụ thuộc ZFS của SAN — để ngỏ,
      không chặn triển khai.)
- [x] **Cảnh báo trong mạng cách ly:** **đã xác nhận** — **chỉ banner trên UI
      `admin/backup`** cho MVP, không tích hợp SMTP/syslog/SNMP. Đội vận hành
      (R3/C02) **cam kết đăng nhập kiểm tra hằng ngày**; nếu sau này có hệ giám
      sát nội bộ sẵn sàng, bổ sung tích hợp qua tham số cấu hình mới, không cần
      sửa lại thiết kế này.
- [x] **Xoá triệt để theo yêu cầu:** **đã xác nhận** — hiện **chưa** có yêu cầu
      pháp lý cụ thể nào buộc xoá khỏi bản sao lưu, nên **không** làm ADR
      crypto-shredding chi tiết ở giai đoạn này. Thay vào đó, khách hàng muốn
      **để ngỏ khả năng cấu hình**: bổ sung một tham số quản trị (xem mục 4,
      `backup.legal_hold_purge_enabled`) cho phép R3 **bật** một quy trình rút
      ngắn thời hạn giữ/xoá bổ sung cho một phạm vi dữ liệu cụ thể khi phát
      sinh yêu cầu pháp lý thật, thay vì phải chờ sửa code. Cơ chế thực thi chi
      tiết (crypto-shredding theo dataset hay xoá thủ công có audit) **để DEV
      spec quyết định khi tính năng này được đưa vào phạm vi** — chưa vào MVP.
- [x] **Số liệu dung lượng thực tế:** **đã xác nhận** — hiện **chưa có** số đo
      thực tế; giữ nguyên kế hoạch "chạy 3 tháng rồi hiệu chỉnh chính sách GFS
      14/8/12" (tham số đã sẵn trong `system_config`, sửa được không cần đổi
      code). Cần lịch nhắc rà soát lại sau 3 tháng vận hành kể từ ngày
      `backup-agent` (Quyết định 4) chạy sản xuất lần đầu.
- [x] **Giờ chạy job purge vs cửa sổ sao lưu** (câu hỏi mở của
      `delete-restore-trash.md` mục 7): **đã trả lời tại Quyết định 3** — giữ
      purge 03:00, gate theo "điểm nhất quán của ngày đã tạo xong" (không chờ
      nhân bản NAS); cấm purge trong cửa sổ `[02:00, 02:00+δ]`; không thoả gate
      ⇒ bỏ lượt + cảnh báo. Cần cập nhật lại mục 7 của `delete-restore-trash.md`
      khi file này được duyệt.
