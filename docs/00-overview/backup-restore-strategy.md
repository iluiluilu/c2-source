# Chiến lược Backup & Restore — Phân tích hiện trạng & Lộ trình triển khai

> File: `docs/00-overview/backup-restore-strategy.md` — Owner: SA/PM
> Ngày: 2026-09-13
> Đây **không** phải artifact SA/BA/DEV mới trong pipeline (xem `AGENTS.md`) —
> đây là tài liệu tổng hợp: phân tích khoảng cách hiện trạng vs kiến trúc đã
> Approved, và đề xuất lộ trình đóng khoảng cách đó. Nguồn quyết định kỹ thuật
> vẫn là `docs/01-sa/architecture/backup-restore.md` (Approved 2026-09-09) —
> tài liệu này **không lặp lại**, chỉ tóm tắt và bổ sung góc nhìn triển khai.

## 1. Tóm tắt điều hành

- Kiến trúc backup/restore đã được **SA thiết kế đầy đủ và Approved**
  (2026-09-09), trả lời đúng FR-34, FR-35, NFR-06 của SRS.
- **Chưa có một dòng code nào** cho slug này: không BA story, không DEV spec,
  không bảng `backup_run`/`restore_run`, không `backup-agent`, không UI admin.
- Hệ quả **đang xảy ra ngay bây giờ**: job `trash-purge` (đã implement xong,
  đang chạy) có gate kiểm tra bảng `backup_run` — vì bảng chưa tồn tại, gate
  **fail-closed vĩnh viễn** ⇒ hệ thống **không bao giờ xoá cứng** dữ liệu
  trong thùng rác quá hạn. An toàn dữ liệu được giữ, nhưng dung lượng không
  bao giờ được giải phóng và mục tiêu FR-27 (dọn thùng rác) không đạt.
- **Cập nhật 2026-09-13:** cả 7 câu hỏi mở của SA đã được khách hàng xác nhận
  phương án (xem mục 2.5) — không còn gì chặn việc viết BA story/DEV spec ở
  cấp quyết định nghiệp vụ. Đáng chú ý: NAS **đã xác nhận** hỗ trợ `zfs
  receive` ⇒ thiết kế `backup-agent` giữ nguyên không đổi; và khách hàng
  **chấp nhận rủi ro** không có bản sao offsite (chính sách an ninh cấm mang
  thiết bị ra ngoài).
- **Khuyến nghị chính:** tách triển khai thành 2 lớp độc lập theo thời gian —
  (a) một **MVP mỏng** chỉ đủ để gate purge có dữ liệu thật để đọc (ưu tiên
  cao nhất, không phụ thuộc hạ tầng), và (b) **backup-agent đầy đủ** theo đúng
  7 quyết định của SA (nay không còn phụ thuộc câu trả lời khách hàng, có thể
  bắt đầu song song). Không chờ (b) xong mới làm (a) — hiện đang mất dữ liệu
  vận hành (dung lượng) mỗi ngày vì chờ.

## 2. Phân tích hiện trạng

### 2.1. Yêu cầu nguồn

| Yêu cầu | Nội dung | Trạng thái |
|---|---|---|
| FR-34 | Sao lưu định kỳ toàn bộ dữ liệu + chỉ mục + tệp gốc + cấu hình, khớp thời điểm, kiểm tra toàn vẹn, ghi nhật ký | Thiết kế xong, **chưa** implement |
| FR-35 | Khôi phục theo điểm, kiểm tra toàn vẹn, rollback nếu dở dang | Thiết kế xong, **chưa** implement |
| NFR-06 | Sao lưu định kỳ + khôi phục khi sự cố + kiểm thử khôi phục định kỳ; RTO/RPO cố ý để ngỏ | SA đã đề xuất số cụ thể (`[ĐX]`), **chưa** được khách hàng xác nhận |

### 2.2. Đã có — kiến trúc Approved (`backup-restore.md`)

Tóm tắt 7 quyết định đã chốt (chi tiết xem file gốc, không lặp lại ở đây):

1. **Phân tầng phạm vi** — T0 (PostgreSQL + object + audit_log + config,
   không tái tạo được) / T1 (OpenSearch — chứa vector từ 2026-10-10, tái tạo tốn GPU;
   trước: Qdrant T1 + OpenSearch T2) / không sao lưu (Redis, RabbitMQ).
2. **Thứ tự nhất quán "DB trước, snapshot sau"** — tránh tham chiếu gãy giữa
   PostgreSQL (metadata) và ZFS (object SeaweedFS).
3. **Lịch 02:00 sao lưu → 03:00 purge**, gate theo "điểm nhất quán đã tạo
   xong" chứ không chờ nhân bản NAS.
4. **`backup-agent` mức host** (systemd timer, ngoài vòng đời backend), báo
   cáo qua API nội bộ `/internal/backup/*`.
5. **3 mức khôi phục** (L1 trích xuất chọn lọc qua sandbox / L2 khôi phục một
   kho / L3 thảm hoạ) + kiểm thử khôi phục tự động hàng tháng, diễn tập quý.
6. **Mã hoá ZFS native + `zfs send --raw`**, quản lý khoá ký gửi ngoại tuyến.
7. **Audit taxonomy + tham số `system_config`** + quy trình xử lý đứt
   hash-chain của `audit_log` khi khôi phục về quá khứ.

Đây là bộ quyết định **chất lượng cao, đã lường trước hầu hết rủi ro thực tế**
(object mồ côi, purge đua với backup, ransomware xoá bản sao, audit hash-chain
đứt). Không cần SA làm lại — vấn đề duy nhất là **chưa ai implement**.

### 2.3. Chưa có — khoảng cách với thực tế implement

| Thành phần | Trạng thái |
|---|---|
| BA story (`docs/02-ba/stories/backup-restore*.md`) | Không tồn tại |
| DEV spec (`docs/03-dev/specs/backup-restore*.md`) | Không tồn tại |
| Bảng `backup_run`, `restore_run` (migration Flyway) | Không tồn tại |
| API `/internal/backup/*`, `/admin/backup/*` | Không tồn tại |
| `backup-agent` (script + systemd timer) | Không tồn tại |
| Trang `admin/backup` (Next.js) | Không tồn tại |
| Sandbox khôi phục (compose profile riêng) | Không tồn tại |

Đối chứng trong code: `TrashPurgeJob.backupGateOpen()`
(`backend/src/main/java/.../trash/purge/TrashPurgeJob.java`) đã cài sẵn logic
đọc bảng `backup_run` đúng theo thiết kế Quyết định 3, có kiểm tra
`information_schema.tables` để phát hiện bảng chưa tồn tại và **fail-closed
có chủ đích** kèm log rõ ràng — đây là điểm tích hợp duy nhất đã có code, và
nó đang ở trạng thái chờ.

### 2.4. Tác động hiện tại (đang xảy ra, không phải rủi ro tương lai)

- `trash-purge` chạy hàng ngày nhưng **luôn bỏ lượt** ⇒ dữ liệu thùng rác quá
  hạn tích tụ vô hạn, dung lượng SeaweedFS không bao giờ được giải phóng.
  Đây là hệ quả trực tiếp của thứ tự triển khai bị đảo ngược so với khuyến
  nghị của chính SA (`backup-restore` phải xong **trước** `delete-restore-trash`
  để job purge có gate thật ngay khi bật) — thực tế `delete-restore-trash` đã
  implement và chạy trước.
- Chưa có bảo vệ nào cho dữ liệu nghiệp vụ nếu PostgreSQL/SeaweedFS gặp sự cố
  ngay bây giờ — **không có bản sao lưu thật nào tồn tại**, bất kể thiết kế đã
  duyệt. Đây là rủi ro cao nhất của toàn dự án ở thời điểm hiện tại.

### 2.5. Câu hỏi mở — đã xác nhận 2026-09-13

| # | Câu hỏi (tóm tắt) | Quyết định |
|---|---|---|
| 1 | RTO/RPO chính thức | **Chấp nhận** bộ số `[ĐX]` của SA nguyên trạng (RPO 15p/4h, RTO 4h/24h+72h) |
| 2 | Bản sao thứ ba/offsite (3-2-1) | Chính sách an ninh **cấm** mang thiết bị ra ngoài ⇒ **chấp nhận rủi ro**, không làm bản sao lạnh offsite |
| 3 | Ai vận hành `backup-agent`/giữ khoá | **Đội hạ tầng C02** vận hành + giữ khoá; đội dự án chỉ nhận báo cáo qua UI |
| 4 | Xác nhận hạ tầng ZFS (SSD) / NAS hỗ trợ `zfs receive` hay chỉ NFS/SMB | **NAS xác nhận hỗ trợ `zfs receive`** ⇒ thiết kế nhân bản giữ nguyên, không cần đổi |
| 5 | Kênh cảnh báo (UI-only hay SMTP/syslog/SNMP) | **Chỉ banner UI** cho MVP; R3/C02 cam kết kiểm tra hàng ngày |
| 6 | Xoá triệt để theo yêu cầu pháp lý | Chưa có yêu cầu pháp lý cụ thể; thêm tham số `backup.legal_hold_purge_enabled=false` để ngỏ khả năng bật sau, cơ chế thực thi để DEV spec quyết định khi cần |
| 7 | Số liệu dung lượng thực tế (TB/tháng) | Chưa có số; giữ kế hoạch chạy 3 tháng rồi hiệu chỉnh GFS 14/8/12 |

Chi tiết đầy đủ đã cập nhật trực tiếp vào mục 7 của
`docs/01-sa/architecture/backup-restore.md`. **Không còn câu hỏi mở nào chặn
BA story/DEV spec ở cấp quyết định nghiệp vụ** — điểm đáng mừng nhất là câu
#4 (từng được đánh giá là quan trọng nhất) đã xác nhận đúng theo thiết kế gốc
của SA, nghĩa là Giai đoạn 2 (mục 3.2) có thể bắt đầu ngay mà không cần thiết
kế lại cơ chế nhân bản.

## 3. Đề xuất chiến lược

### 3.1. Nguyên tắc chỉ đạo (kế thừa từ SA, không đổi)

Giữ nguyên toàn bộ 7 quyết định ở mục 2.2. Chiến lược triển khai dưới đây chỉ
thêm **thứ tự và cách chia nhỏ** để đưa được giá trị sớm mà không vi phạm bất
kỳ quyết định nào đã duyệt.

### 3.2. Lộ trình theo giai đoạn

```
Giai đoạn 0  →  Giai đoạn 1 (MVP gate)  →  Giai đoạn 2 (backup-agent đầy đủ)
                                        ↘
                                          Giai đoạn 3 (Restore L1/L2)  →  Giai đoạn 4 (DR L3 + kiểm thử định kỳ)  →  Giai đoạn 5 (UI admin đầy đủ + cảnh báo)
```

**Giai đoạn 0 — Chốt câu hỏi hạ tầng — ✅ Hoàn tất 2026-09-13**
- Cả 7 câu hỏi mở đã được xác nhận (mục 2.5); không còn gì cần chốt thêm ở
  cấp quyết định nghiệp vụ trước khi bắt đầu Giai đoạn 1 và 2 song song.
- Việc còn lại thuộc vận hành, không phải quyết định: bàn giao chính thức
  runbook L3 cho đội C02 (đã được xác nhận là đội vận hành) khi runbook có ở
  Giai đoạn 4.

**Giai đoạn 1 — MVP: unblock `trash-purge` (ưu tiên cao nhất, làm ngay)**
- Mục tiêu duy nhất: bảng `backup_run` tồn tại và được ghi bản ghi thật, để
  gate hết fail-closed vĩnh viễn.
- Phạm vi tối thiểu: migration Flyway tạo `backup_run` (đúng schema
  `TrashPurgeJob` đang query: `consistency_point_at`, `status`,
  `window_open`), một job `@Scheduled` **tạm thời trong backend** (chấp nhận
  vi phạm nhẹ Quyết định 4 — "không thực thi lệnh hạ tầng từ backend" — có
  ghi chú rõ đây là **giải pháp tạm**, sẽ thay bằng `backup-agent` mức host ở
  Giai đoạn 2) chạy `pg_dump`/`pg_basebackup` tối thiểu để có một điểm nhất
  quán thật, ghi `backup_run(status=VERIFIED, window_open=false)`.
- Đây **không** phải backup sản xuất đầy đủ, chỉ là "đủ thật để gate không
  còn nói dối". Phải ghi rõ trong BA story rằng đây là nợ kỹ thuật có chủ đích,
  có hạn thay thế bằng Giai đoạn 2.
- Vì đây là thay đổi nhỏ, có thể đi thẳng `/dev-spec` + `/dev-implement` với
  một BA story rất ngắn, không cần chờ 7 câu hỏi mở.

**Giai đoạn 2 — `backup-agent` đầy đủ theo đúng Quyết định 1, 2, 4**
- Triển khai đúng thiết kế: ZFS snapshot + `zfs send -i --raw` sang NAS,
  `pg_basebackup` + WAL archiving (PITR), snapshot OpenSearch, export
  Keycloak + cấu hình, systemd timer, API `/internal/backup/*`.
- Phụ thuộc cứng vào câu hỏi #4 (cơ chế nhân bản NAS).
- Sau giai đoạn này, tắt job tạm của Giai đoạn 1.

**Giai đoạn 3 — Khôi phục L1 (sandbox trích xuất chọn lọc) + L2 (PITR)**
- L1 phục vụ trực tiếp nhu cầu thực tế gần nhất: dữ liệu đã purge/hết hạn
  thùng rác cần lấy lại (đúng kịch bản nêu ở Quyết định 5) — ưu tiên trước L2.
- L2 (khôi phục một kho khi hỏng logic/DB) làm cùng đợt vì dùng chung cơ chế
  PITR đã có ở Giai đoạn 2.

**Giai đoạn 4 — Runbook thảm hoạ L3 + kiểm thử khôi phục định kỳ**
- Runbook L3 là sản phẩm bắt buộc (SA đã ghi nhận đang thiếu ở checklist mục 5).
- Kiểm thử tự động hàng tháng cần chạy được **ít nhất một lần** trước khi coi
  slug này là "xong" — NFR-06 yêu cầu kiểm thử định kỳ, không phải chỉ có khả
  năng khôi phục trên giấy.
- Diễn tập có người hàng quý — phụ thuộc câu hỏi #3 (ai vận hành).

**Giai đoạn 5 — UI admin đầy đủ + cảnh báo vận hành**
- Trang `admin/backup`, tích hợp kênh cảnh báo theo câu hỏi #5.
- Có thể làm song song Giai đoạn 3-4 vì không phụ thuộc kỹ thuật, chỉ cần API
  đọc đã có từ Giai đoạn 1-2.

### 3.3. Vì sao tách Giai đoạn 1 khỏi Giai đoạn 2 (điểm mấu chốt của đề xuất)

Thứ tự triển khai đã duyệt ban đầu ("backup-restore xong trước, rồi mới
delete-restore-trash") đã bị đảo ngược trong thực tế. Chờ toàn bộ Giai đoạn 2
(phụ thuộc câu hỏi #4 chưa trả lời) mới unblock purge nghĩa là rác tiếp tục
tích tụ vô thời hạn trong lúc chờ đội hạ tầng xác nhận NAS. Giai đoạn 1 giải
quyết đúng nút thắt hiện tại (gate) bằng chi phí thấp nhất, không cần chờ câu
trả lời hạ tầng, và không mâu thuẫn với thiết kế đích — nó chỉ là bước đệm.
Rủi ro chấp nhận: Giai đoạn 1 tạm vi phạm nguyên tắc "backend không thực thi
lệnh hạ tầng" (Quyết định 4) trong phạm vi hẹp (`pg_dump` nội bộ, không đụng
ZFS) — chấp nhận được vì có hạn thay thế rõ ràng.

### 3.4. Rủi ro hàng đầu cần theo dõi (đã có trong SA, nhắc lại vì ảnh hưởng lộ trình)

| Rủi ro | Ảnh hưởng tới lộ trình |
|---|---|
| Chỉ 2 bản sao, cùng site, không bản offline (3-2-1 chưa thoả) | Không chặn Giai đoạn 1-2, nhưng phải nêu rõ với khách hàng **trước khi** công bố slug "hoàn thành" — tránh hiểu nhầm là đã an toàn hoàn toàn |
| Mất khoá mã hoá ZFS = mất toàn bộ bản sao | Thủ tục ký gửi khoá phải có **trước khi** Giai đoạn 2 tạo bản sao thật đầu tiên trên NAS, không phải làm sau |
| "Backup thành công giả" | Giai đoạn 1 (MVP) đặc biệt dễ mắc lỗi này vì đơn giản hoá — bắt buộc verify (`pg_verifybackup` tối thiểu) trước khi set `VERIFIED`, không set mặc định |
| Máy chủ quản lý là SPOF | Không giải quyết được ở slug này — cần nêu ở cấp quyết định dự án (ngoài phạm vi kỹ thuật) |

### 3.5. Hành động ngay (đề xuất tuần này)

1. Chạy `/ba-story docs/01-sa/architecture/backup-restore.md` với phạm vi
   **giới hạn ở Giai đoạn 1** (MVP gate) làm story đầu tiên — làm ngay, không
   còn gì chặn. Sau khi Approved → `/dev-spec` → `/dev-implement` để bảng
   `backup_run` tồn tại sớm nhất có thể — mỗi ngày trễ là một ngày dung lượng
   thùng rác tiếp tục không được giải phóng.
2. Song song, chạy `/ba-story` phạm vi Giai đoạn 2 (`backup-agent` đầy đủ) —
   nay có thể viết trọn vẹn vì cơ chế nhân bản (`zfs send/receive`) đã được
   hạ tầng xác nhận, không còn là giả định.
3. Thông báo chính thức cho đội hạ tầng C02 về trách nhiệm vận hành
   `backup-agent`, giữ khoá ký gửi, và lịch diễn tập DR hàng quý (đã xác nhận
   ở mục 2.5 #3) — cần thời gian chuẩn bị nhân sự/quy trình phía họ trước khi
   Giai đoạn 2 triển khai xong.
4. Ghi biên bản chính thức với khách hàng về việc chấp nhận rủi ro "không có
   bản sao offsite" (mục 2.5 #2) — nên có xác nhận bằng văn bản, không chỉ ở
   mức trao đổi, vì đây là rủi ro **Cao** còn tồn tại vĩnh viễn trong thiết kế.

## 4. Tài liệu tham chiếu

- `docs/01-sa/architecture/backup-restore.md` — kiến trúc gốc, Approved
  2026-09-09 (nguồn quyết định kỹ thuật duy nhất, tài liệu này không thay thế).
- `docs/01-sa/architecture/delete-restore-trash.md` — bên tiêu thụ gate purge.
- `docs/01-sa/architecture/system-config.md` — tham số `backup.*`.
- `docs/01-sa/architecture/audit-logging.md` — taxonomy audit, hash-chain.
- `backend/src/main/java/com/aismartstore/backend/trash/purge/TrashPurgeJob.java`
  — điểm tích hợp code duy nhất đã tồn tại (gate fail-closed).
- `checklist-trien-khai-du-an.md` mục 2.7, hàng #20 — nhật ký trạng thái
  triển khai thực tế tại thời điểm phân tích.
