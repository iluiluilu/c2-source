# Đánh giá phân bổ tài nguyên phần cứng — Hệ thống lưu trữ & xử lý thông tin AI

> File: `docs/00-overview/hardware-resource-allocation.md` — Owner: SA/Infra
> Status: **Draft — chờ xác nhận mục 8**
> Ngày: 2026-09-16 (bản 2, cập nhật sau phản hồi người dùng)
> Nguồn phần cứng: `stg/server.txt` — **đã được người dùng xác nhận là chính xác**
> Nền tảng triển khai production: **Docker Compose + systemd + Portainer**
> (chốt 2026-09-16 sau khi cân nhắc lại Kubernetes — xem mục 3.6)
> Đối chiếu: `docs/kien_truc_he_thong.adoc`, `docs/so do vat ly.adoc`,
> `docs/danh_gia_lua_chon_cong_nghe.adoc`,
> `source_v2/docs/01-sa/architecture/{ai-search-infra,digitization-pipeline,backup-restore}.md` (Approved).

> **Phạm vi:** đánh giá này dựa trên **phần cứng thật + thiết kế production trên
> k8s**. Môi trường `stg/` (Docker Compose, 1 VPS, Cloudflare) **không** được
> dùng làm cơ sở đánh giá — khác hoàn toàn về hạ tầng, chỉ dẫn chiếu khi rút
> bài học vận hành đã trả giá bằng sự cố thật.

---

## 1. Tóm tắt điều hành

**Kết luận: phần cứng được cấp là hợp lý và đủ cho mục tiêu hệ thống. Cách
phân bổ ở mức thô đúng.** Bản đầu nêu 6 điểm phải chốt; sau phản hồi ngày
2026-09-16, **3 điểm kiến trúc đã được quyết định** (dòng 1–3 bảng dưới),
còn **3 điểm phải xử lý bằng cấu hình** (dòng 4–6) — đều đã có phương án cụ thể
ở mục 4 và 5.4.

| # | Vấn đề | Mức | Ảnh hưởng |
|---|---|---|---|
| 1 | ✅ *Đã chốt* — nền tảng điều phối là **Docker Compose + systemd + Portainer**, không phải Kubernetes (mục 3.6) | 🟠 Cao (rủi ro tồn dư) | **Không có HA cho tầng dữ liệu** — với 2 node thì k8s cũng không cho được điều đó. Cơ chế bảo vệ là **dựng lại nhanh**: mất `node-app` là **kịch bản sự cố số 1**, bắt buộc có runbook + diễn tập |
| 2 | ✅ *Đã chốt* — dữ liệu stateful đặt trên **LUN của ME5284 qua iSCSI + multipath**, mount ở host | 🟠 Cao | Máy thay thế chỉ cần mount lại LUN là có dữ liệu — đây là thứ giữ RTO L3 ≤ 24h, thay cho HA |
| 3 | ✅ *Đã chốt* — **Kong** (DB-less) làm API Gateway, đúng `danh_gia_lua_chon_cong_nghe.adoc` §4.3 (FR-33) | — | Cơ sở cho tài liệu cài đặt tầng vào |
| 4 | **Chưa có định mức `mem_limit`/`cpus` cho service nào** | 🟠 Cao | Docker mặc định **không giới hạn** gì cả. Một service rò rỉ bộ nhớ sẽ để kernel OOM-killer chọn nạn nhân — và nạn nhân thường là tiến trình RSS lớn nhất, tức PostgreSQL |
| 5 | **NAS (~77TB dùng thật) nhỏ hơn nhiều so với SAN HDD (~205TB dùng thật)** | 🟠 Cao | NAS mới là trần thật của dung lượng hệ thống, không phải SAN |
| 6 | **Thiếu cơ chế dọn rác ở tầng hạ tầng** — SeaweedFS volume vacuum, WAL archive, snapshot ZFS, image/log đều chưa có kế hoạch | 🟠 Cao | Dung lượng "xoá rồi mà không giảm" |

**Đã đóng sau phản hồi 2026-09-16:**

- ✅ **GPU = 2× NVIDIA L4 24GB** — người dùng xác nhận `server.txt` là đúng.
  Hệ quả: **2 tài liệu đã Approved ghi sai và cần sửa** —
  `ai-search-infra.md` §2/§6/§7 và `digitization-pipeline.md` §2 đang ghi "GPU
  96GB (đã xác nhận 2026-08-16)"; `so do vat ly.adoc` §3.3 ghi "GPU0 — NVIDIA
  L40S 48GB". Chi tiết ảnh hưởng ở mục 3.2.
- ✅ **Kiểm kê phần cứng: 2 máy chủ (ứng dụng + AI) · 1 SAN · 1 NAS** — đúng
  như `server.txt`, không thiếu thiết bị. `so do vat ly.adoc` §3.2 ghi "cụm 2
  node, mỗi node R760 48C/96T" là **mô tả sai** cấu hình thật (node thứ hai là
  máy AI 64C/128T + GPU, không phải một bản sao của máy ứng dụng) — cần sửa lại
  cho khớp.
- ✅ **Không dùng `stg/` làm cơ sở đánh giá.**

**Điểm tích cực quan trọng: VRAM không phải nút thắt.** Tổng VRAM thường trú
của toàn bộ model giai đoạn 1 (OCR + STT + embedding + video; reranker đã bỏ
2026-10-08 theo `search-semantic.md` QĐ 2) chỉ khoảng **10–13GB**, vừa trong **một** L4 24GB. Có 2 card nghĩa là có dư địa
thật. Việc 2 tài liệu Approved giả định 96GB là sai, nhưng sai **theo hướng an
toàn** cho giai đoạn 1 — chỉ ảnh hưởng thật khi bật LLM ở giai đoạn 2.

---

## 2. Kiểm kê phần cứng (đã chốt)

| Vai trò | Cấu hình | Ghi chú |
|---|---|---|
| **Node ứng dụng/dữ liệu** | Dell R760 · 48C/96T · 128GB RAM · 2×3.84TB NVMe | API gw, Keycloak, api, RabbitMQ, SeaweedFS, PostgreSQL, OpenSearch (kèm vector k-NN — Qdrant đã gỡ 2026-10-10) |
| **Node AI** | Dell R760 · 64C/128T · 128GB RAM · 2×3.84TB NVMe · **2× NVIDIA L4 24GB** | OCR, STT, video process, embedding |
| **SAN** | Dell PowerVault ME5284 · 8×7.68TB SSD SAS (61.4TB raw) + 20×16TB HDD SAS (320TB raw) | Kho dữ liệu chính, dual controller |
| **NAS backup** | QNAP TS-h3087XU-RP · 6×24TB (144TB raw) + 2×960GB NVMe · QuTS hero (ZFS) | Đích sao lưu |

Còn thiếu thông tin (không chặn đánh giá, chặn tài liệu cài đặt): thiết bị mạng
lõi, số cổng 25GbE mỗi node, RAM của NAS, UPS. Xem mục 8 nhóm B.

---

## 3. Đánh giá theo từng trục tài nguyên

### 3.1. Node ứng dụng/dữ liệu (48C/96T · 128GB)

Phải cùng lúc gánh 8 service nêu trong `server.txt`, cộng gateway, exporter và
agent thu log.

- **CPU 48C/96T: dư thoải mái.** Tải nặng CPU ở node này chỉ có ClamAV (quét
  tệp upload) và OpenSearch lúc bulk-index. Không thấy rủi ro.
- **RAM 128GB: đủ nhưng KHÔNG dư** — và đây là điểm phải quản chặt. Hai hộ tiêu
  thụ lớn (PostgreSQL, OpenSearch — OpenSearch giờ gánh cả vector k-NN, Qdrant đã gỡ 2026-10-10) đều là hệ "ăn RAM tới khi chạm
  trần". Cộng thêm ZFS ARC nếu node này giữ pool ZFS cho SeaweedFS
  (`kien_truc_he_thong.adoc` §4) — ARC mặc định lấy tới 50% RAM host, **nằm
  ngoài mọi giới hạn cgroup của container**. Không đặt `zfs_arc_max` thì ARC và
  các container sẽ tranh nhau phần RAM còn lại.
- Hai thứ bắt buộc phải đặt, nếu không node sẽ mất ổn định dưới tải:
  `zfs_arc_max`, và `mem_limit` cho **mọi** service (kể cả service nhỏ).

→ **Cần định mức cụ thể.** Bảng đề xuất ở mục 4.1.

### 3.2. Node AI (64C/128T · 128GB · 2× L4 24GB)

**VRAM giai đoạn 1** (không có LLM — chốt tại `ai-search-infra.md` Quyết định 4):

| Model | Runtime | VRAM thường trú (ước tính) |
|---|---|---|
| PaddleOCR DB++ (detection) | ONNX/Paddle GPU | ~1GB |
| VietOCR (CNN+Transformer) | PyTorch fp16 | ~1–2GB |
| PhoWhisper-large | faster-whisper / CT2 `int8_float16` | ~3–4GB |
| Qwen3-Embedding-0.6B (embedding, sửa 2026-10-10 — thay bge-m3/TEI; dịch vụ do nhóm AI vận hành) | fp16 | ~2–3GB (kể cả batch) |
| FFmpeg decode/encode | NVDEC/NVENC | ~1–2GB |
| **Tổng** | | **~10–13GB** |

**Toàn bộ giai đoạn 1 vừa trong MỘT L4 24GB.** Hai card là dư địa thật.

Ba hệ quả kỹ thuật của việc GPU là **L4** (chứ không phải L40S như sơ đồ vật lý
ghi), đều cần ghi vào tài liệu vận hành:

1. **L4 (Ada) không hỗ trợ MIG.** Không có cách chia cứng GPU bằng phần cứng.
   Muốn nhiều pod dùng chung một GPU thì phải bật **time-slicing** hoặc **MPS**
   trong NVIDIA GPU Operator — cả hai đều **không cách ly VRAM**, phải tự tính
   ngân sách VRAM cho từng tiến trình và tự chịu trách nhiệm không tràn.
2. **Không có NVLink** ⇒ không gộp 2×24GB thành 48GB. Trần cho LLM giai đoạn 2
   là **≤ 24GB trên một card**: Qwen2.5-14B-Instruct AWQ 4-bit (~9–10GB trọng
   số) vẫn vừa, còn ~12GB cho KV cache.
3. **Băng thông bộ nhớ L4 ~300GB/s** (L40S ~864GB/s) ⇒ tốc độ sinh token của
   LLM 14B sẽ ở mức ~20–30 token/s một luồng. Đủ cho tóm tắt RAG một phiên,
   **không** đủ cho nhiều phiên đồng thời. Cần ghi nhận trước khi cam kết phạm
   vi FR-24 giai đoạn 2.

**Cách chia vai GPU trong tài liệu cần sửa.** `so do vat ly.adoc` §3.3 chia
GPU0 (L40S 48GB) = trực tuyến, GPU1 (L4 24GB) = theo lô — hợp lý khi hai card
**không cân sức**. Thực tế là **hai L4 giống hệt nhau và giai đoạn 1 không có
LLM**: giữ "GPU0 chỉ phục vụ trực tuyến" nghĩa là dành trọn một card cho tác vụ
chỉ tốn ~150ms mỗi truy vấn, trong khi nút thắt thật của hệ là OCR — càng nặng
hơn sau quyết định của C02 (`digitization-pipeline.md` QĐ 1) là **luôn OCR toàn
bộ PDF kể cả khi đã có lớp text**, mà chính tài liệu đó đã ghi "xem lại NFR
thông lượng". Đề xuất chia lại ở mục 4.2.

**RAM 128GB / CPU 64C:** thoải mái cho ingestion worker (Tika, PyMuPDF, FFmpeg,
chunking). Đây cũng là nơi nên đặt stack giám sát (mục 5.2).

### 3.3. SAN — Dell PowerVault ME5284

| Tier | Raw | Đề xuất RAID | Khả dụng |
|---|---|---|---|
| SSD SAS | 8×7.68TB = 61.4TB | RAID6 (6+2) hoặc ADAPT | ~46TB |
| HDD SAS | 20×16TB = 320TB | 2× RAID6 (8+2) hoặc ADAPT | ~256TB → ZFS nên dừng ở 80% ⇒ **~205TB dùng thật** |

1. **Tier SSD đang cấp thừa khoảng một bậc độ lớn.** 46TB khả dụng cho
   PostgreSQL + OpenSearch (~300GB lexical + ~80GB vector k-NN float32, giảm
   mạnh nếu bật `on_disk` + nén vector như `search-semantic.md` QĐ 6 — sửa 2026-10-10, không còn Qdrant). Dư địa
   ~40TB này nên dùng có chủ đích: đặt **vùng staging sao lưu** và **sandbox
   khôi phục** (`backup-restore.md` QĐ 5 có yêu cầu nhưng chưa chỉ định chỗ)
   lên đây, thay vì xin thêm thiết bị.

2. **⚠️ ZFS trên LUN RAID phần cứng chỉ PHÁT HIỆN được hỏng ngầm, KHÔNG tự sửa
   được.** `kien_truc_he_thong.adoc` §4 ghi ZFS đảm nhận "toàn vẹn dữ liệu bằng
   checksum và scrub định kỳ chống bit-rot". Chỉ đúng một nửa: ZFS chỉ
   **self-heal** khi bản thân nó có bản dư thừa (mirror/raidz/`copies=2`). Nếu
   host chỉ thấy **một LUN** do RAID6 phần cứng xuất ra, `zfs scrub` sẽ báo lỗi
   checksum nhưng **không có nguồn nào để phục hồi**.
   → **Khuyến nghị:** chấp nhận "phát hiện, không tự sửa" cho dữ liệu (mọi
   object đều bất biến và đã có bản sao ở NAS), **nhưng phải ghi rõ trong
   runbook** rằng quy trình xử lý khi scrub báo lỗi là khôi phục file đó từ
   NAS. Không nên ghép `zpool mirror` 2 LUN (mất một nửa dung lượng), cũng
   không nên bỏ RAID phần cứng chuyển JBOD (đánh mất dual-controller HA).

### 3.4. NAS backup — QNAP TS-h3087XU-RP

| Hạng mục | Số liệu |
|---|---|
| Raw | 6×24TB = 144TB |
| RAID-Z2 (4 data + 2 parity) | ~96TB khả dụng |
| Ngưỡng ZFS an toàn 80% | **~77TB dùng thật** |
| SAN HDD tier dùng thật | **~205TB** |

**NAS mới là trần thật của hệ thống, không phải SAN.** NAS chứa được ~77TB
trong khi SAN cho phép nạp tới ~205TB. Cộng chính sách GFS ở
`backup-restore.md` QĐ 1 (object: 14 daily / 8 weekly / **12 monthly** ở NAS —
tức dữ liệu đã purge vẫn nằm trong bản sao tới ~12 tháng):

> Dung lượng dữ liệu gốc (SeaweedFS) nên **không vượt quá ~55–60TB** thì chuỗi
> giữ bản 12 tháng mới còn chỗ.

Vì object bất biến, `zfs send -i` chỉ truyền phần mới ⇒ 12 tháng snapshot
**không** nhân 12 lần dung lượng, chỉ cộng thêm phần đã xoá mà snapshot còn
giữ. Nhưng cần con số tăng trưởng thật để tính — xem Q6.

**2×960GB NVMe của NAS:** đích sao lưu chủ yếu là ghi tuần tự lớn
(`zfs receive`) nên dùng làm **L2ARC gần như vô ích**. Khuyến nghị dùng làm
**special vdev (metadata + small blocks) dạng mirror** — làm nhanh rõ rệt các
thao tác duyệt/scrub/prune snapshot vốn nặng metadata. **Bắt buộc mirror**:
mất special vdev = mất cả pool.

### 3.5. Mạng

25GbE + dual switch là đúng và cần thiết (iSCSI + luồng object giữa 2 node).
Ba điểm phải chốt trong tài liệu cài đặt:

- **Multipath bắt buộc** — ME5284 dual controller chỉ có giá trị nếu host cấu
  hình `multipath-tools`/DM-MPIO. Không có multipath thì failover controller
  vẫn làm treo I/O.
- **Tách VLAN iSCSI khỏi VLAN nghiệp vụ**, bật jumbo frame (MTU 9000) đồng bộ
  NIC → switch → SAN. Lệch MTU một chặng là nguyên nhân phổ biến nhất của
  "iSCSI chậm bất thường".
- Tối thiểu **2 cổng 25GbE/node** (1 iSCSI, 1 nghiệp vụ), lý tưởng 4. Xem Q7.

### 3.6. Chọn nền tảng điều phối — đã cân nhắc k8s, **chốt Docker Compose**

`danh_gia_lua_chon_cong_nghe.adoc` §4.10 đã chọn **Docker Compose + systemd +
Portainer CE** và loại Kubernetes vì "tạo gánh nặng vận hành không tương xứng ở
quy mô 2 máy chủ". Ngày 2026-09-16, phương án k8s được đưa ra xem xét lại và
**giữ nguyên quyết định gốc**. Dưới đây là lý do, để lần sau không phải mở lại.

#### (a) Với 2 node, k8s không cho được HA cho tầng dữ liệu

Lý do mạnh nhất để dùng k8s là **tự chuyển workload stateful sang node còn
sống**. Điều đó đòi hỏi control-plane phải sống sót — mà etcd cần số node lẻ
≥ 3 để có quorum. Với đúng 2 node vật lý:

| Phương án | Kết quả |
|---|---|
| 1 control-plane trên `node-app` | Mất `node-app` ⇒ **etcd + API server chết cùng lúc với tầng dữ liệu**. Không còn scheduler để xếp lại PostgreSQL sang `node-ai`. **Tự phục hồi = 0** đúng trong kịch bản cần nó nhất |
| 2 control-plane | Mất bất kỳ node nào cũng mất quorum — **tệ hơn** 1 node |
| 3 control-plane | Có HA thật, nhưng **phải mua/cấp thêm thiết bị** |

Thêm hai ràng buộc khiến phần "di chuyển được" còn rất ít: SeaweedFS bị ghim
vào node giữ pool ZFS (hostPath), và OpenSearch đặt local PV. Thực tế chỉ
PostgreSQL di chuyển được (Qdrant đã gỡ 2026-10-10), và chỉ khi control-plane còn sống.

#### (b) Chi phí k8s trong môi trường air-gap là chi phí thường trực

- Phải mirror **toàn bộ** image hệ thống (CNI, CSI, GPU Operator, giám sát) vào
  registry nội bộ; mỗi lần nâng cấp cụm là một chiến dịch air-gap.
- Nâng cấp cụm là việc định kỳ trong suốt vòng đời 5–10 năm; xoay chứng chỉ
  không có Internet để cứu.
- ME5284 **không có CSI driver của Dell** ⇒ vẫn phải tạo LUN bằng tay, mất luôn
  phần cấp phát động vốn là điểm mạnh nhất của k8s ở tầng lưu trữ.
- Bề mặt chẩn đoán rộng hơn hẳn: "vì sao pod Pending" có khoảng 15 nguyên nhân
  khả dĩ, so với ~3 nguyên nhân của "vì sao container không lên".

#### (c) Yếu tố quyết định: RTO phụ thuộc vào tốc độ dựng lại

Vì **không** có HA ở cả hai phương án, thứ thật sự bảo vệ hệ là **dựng lại
nhanh**. Mục tiêu RTO L3 là ≤ 24h cho chức năng lõi.

| | Dựng lại `node-app` |
|---|---|
| **Compose** | Cài OS → cài Docker → copy thư mục compose từ Git → `zpool import` → `docker compose up -d`. Đo bằng **giờ** |
| **k8s** | Cài OS → cài RKE2 → khôi phục etcd từ snapshot → dựng lại CNI/CSI → kiểm tra mirror image đủ → gắn lại PV → chờ scheduler. Đo bằng **nhiều giờ tới ngày**, dưới áp lực, trong môi trường không có Internet để tra cứu |

Với hệ thống mà cơ chế ổn định chính là "hỏng thì dựng lại thật nhanh", đây là
điểm quyết định.

#### (d) Cái bị mất khi không dùng k8s — và cách bù

| Mất | Bù bằng |
|---|---|
| Liveness probe tự restart container **treo** (Compose chỉ restart khi tiến trình thoát hẳn) | `healthcheck` đầy đủ cho mọi service + container **`autoheal`** giám sát trạng thái `unhealthy` |
| Operator CloudNativePG (PITR, WAL archiving ra S3, verify backup sẵn có) | **pgBackRest** với repo S3 trỏ SeaweedFS — cùng cơ chế, cấu hình tay nhiều hơn |
| kube-prometheus-stack tự phát hiện target | Scrape config tĩnh — hệ chỉ có ~15 target cố định |
| Rolling update không downtime cho web/backend | Hệ nội bộ, chấp nhận cửa sổ vài giây; hoặc script blue-green đơn giản |
| Đường mở rộng thêm node về sau | Phần cứng đã cố định, không có kế hoạch thêm node |

**Docker Swarm** (phương án trung gian nêu ở §4.10) **không** được chọn: gần như
ở chế độ duy trì, và với 2 node thì hầu như không hơn gì Compose.

#### (e) Cái được thêm khi quay về Compose

- `backup-restore.md` QĐ 4 (đã Approved) thiết kế `backup-agent` chạy **systemd
  mức host**. Dùng Compose là khớp nguyên bản — **không phải sửa tài liệu đã
  duyệt**, và câu hỏi mở về việc tách agent thành CronJob tự biến mất.
- Bài học firewall đã trả giá ở môi trường trước (`ufw` không lọc được cổng do
  Docker publish, phải chèn rule vào chain `DOCKER-USER`) áp dụng trực tiếp,
  không phải học lại trên mô hình mạng mới.

#### (f) Hệ quả kiến trúc: mạng giữa 2 node

Không còn mạng phẳng của pod. Service trên `node-app` mà worker AI cần gọi
(RabbitMQ, SeaweedFS S3, OpenSearch) phải **publish cổng bind vào IP của
VLAN nội bộ** — không `0.0.0.0`, không `127.0.0.1` — và **bắt buộc** giới hạn
bằng firewall chỉ cho `<NODE_AI_IP>`. Chi tiết ở `deployment/01-nen-tang-docker.md` §5.

#### (g) Điều kiện để mở lại quyết định này

Xem xét lại k8s **chỉ khi** có ít nhất một trong hai: (1) cấp thêm node thứ 3
làm quorum, hoặc (2) đội vận hành có kinh nghiệm k8s production air-gapped
thực tế. Không có điều kiện nào thì giữ Compose.

## 4. Bảng phân bổ tài nguyên đề xuất

> Các con số dưới đây là `mem_limit` / `cpus` trong `docker-compose.yml`.
> `mem_limit` là **giới hạn cgroup cứng** — vượt là container bị OOM-kill, không
> lan sang service khác. Kèm theo: đặt `oom_score_adj` thấp cho tầng dữ liệu để
> kernel OOM-killer chọn chúng **sau cùng**, và giới hạn `zfs_arc_max` ở host.

### 4.1. Node ứng dụng/dữ liệu — 128GB RAM / 48 core

Chừa **16GB cho ZFS ARC** (`zfs_arc_max=16G` — bắt buộc đặt), **6GB** cho OS +
Docker daemon, **14GB** page cache (OpenSearch/PostgreSQL phụ thuộc nặng vào
cache của OS) ⇒ còn **~92GB** cho container.

| Workload | `mem_limit` | `cpus` | Tinh chỉnh bắt buộc |
|---|---|---|---|
| PostgreSQL | 20GB | 12 | `shared_buffers=6GB`, `effective_cache_size=16GB`, `work_mem=32MB`, `maintenance_work_mem=2GB` + **PgBouncer** |
| OpenSearch | 36GB | 16 | `-Xms10g -Xmx10g`, `bootstrap.memory_lock=true`, `memlock: unlimited`; phần còn lại cho k-NN native (faiss, off-heap): field vector `mode: on_disk` + nén, `knn.memory.circuit_breaker.limit` ~60% — gộp ngân sách 12GB của Qdrant cũ (gỡ 2026-10-10) |
| backend (Spring Boot) | 10GB | 12 | `-XX:MaxRAMPercentage=65` thay vì `-Xmx` cứng |
| SeaweedFS (master+volume+filer+s3) | 6GB | 6 | `-volume.index=leveldb`; filer store trỏ PostgreSQL; **ghim node** (mục 3.6e) |
| ClamAV | 5GB | 4 | CSDL chữ ký nạp RAM ~2.5GB; `ConcurrentDatabaseReload no` |
| RabbitMQ | 3GB | 3 | `vm_memory_high_watermark.relative=0.6`, `disk_free_limit.absolute=10GB` |
| Keycloak | 3GB | 3 | `-Xmx2g`; chạy chế độ `start` (production), không `start-dev` |
| Redis | 3GB | 2 | `maxmemory 2gb`, `maxmemory-policy allkeys-lru` |
| web (Next.js) | 2GB | 4 | |
| Ingress controller / API gateway | 2GB | 4 | Xem Q9 (Kong hay ingress-nginx) |
| Exporter + log agent | 2GB | 2 | |
| **Tổng** | **92GB** | **72 / 48 core** | CPU `limits` là trần mềm, cho phép over-subscribe; RAM thì không |

### 4.2. Node AI — 128GB RAM / 64 core / 2× L4 24GB

**Đề xuất chia lại vai GPU** (khác `so do vat ly.adoc` §3.3 — lý do ở mục 3.2):

| GPU | Tiến trình | Ngân sách VRAM | Lý do |
|---|---|---|---|
| **GPU0** — trực tuyến + OCR phụ | embedding-service Qwen3-Embedding-0.6B (~3GB, nhóm AI vận hành — sửa 2026-10-10) + **ocr-worker #1 (5GB, ưu tiên thấp)** | ~9 / 24GB | Tải trực tuyến giai đoạn 1 (chỉ embed, không rerank) quá nhỏ để chiếm trọn một card. ~15GB còn lại là **chỗ dành sẵn cho LLM giai đoạn 2** |
| **GPU1** — xử lý nền | stt-worker (5GB) + ocr-worker #2 (5GB) + video/FFmpeg NVDEC (2GB) | ~12 / 24GB | Giữ đúng tinh thần tách tải nền khỏi tải tương tác |

Ba ràng buộc bắt buộc:

1. Ghim từng container vào đúng card bằng `device_ids: ['0']` / `['1']` trong
   `deploy.resources.reservations.devices`. L4 **không có MIG** ⇒ không cách ly
   được VRAM giữa các tiến trình cùng card; ngân sách trên phải ép bằng cấu hình
   runtime của từng service.
2. `ocr-worker #1` trên GPU0 phải có **cờ tắt khẩn cấp**
   (`ai_queue.gpu0_batch_enabled`, mặc định `true`) — nếu đo thấy p95 embed
   truy vấn vượt 150ms thì tắt, GPU0 trở về đúng vai "chỉ trực tuyến".
3. Khi bật LLM giai đoạn 2: tắt `ocr-worker #1`, GPU0 = LLM + embedding-service, GPU1 giữ
   nguyên. **Không cần mua thêm phần cứng.**

| Workload | `mem_limit` | `cpus` |
|---|---|---|
| ocr-worker ×2 | 12GB × 2 | 8 × 2 |
| stt-worker | 12GB | 8 |
| video-worker (FFmpeg) | 10GB | 12 |
| embedding-service (Qwen3-Embedding-0.6B, thay tei-embed 2026-10-10) | 6GB | 4 |
| tika-server | 8GB | 8 |
| ingest-worker (giải nén, PDF/Office, chunking) | 12GB | 16 |
| **Prometheus + Alertmanager** | 8GB | 4 |
| **Grafana** | 2GB | 2 |
| **Loki** (log tập trung — mục 5.2) | 8GB | 4 |
| Exporter (DCGM qua GPU Operator, node, kube-state) | 2GB | 2 |
| **Tổng** | **~100GB** | **~74 / 64 core** |

Còn ~28GB cho OS, page cache và vùng scratch. Hai node chạy **hai project
Compose riêng biệt** — không có scheduler nào rải nhầm workload sang node kia.

### 4.3. Đề xuất đặt dữ liệu

Nguyên tắc: **cái gì không tái tạo được thì đặt trên LUN của SAN** (máy thay
thế mount lại là có dữ liệu — đây là thứ giữ RTO L3, mục 3.6c); **cái gì tái
tạo được thì đặt NVMe cục bộ** (nhanh, và mất cũng không sao).

| Dữ liệu | Tầng backup | Vị trí đề xuất | Lý do |
|---|---|---|---|
| PostgreSQL data + WAL | T0 | **LUN SAN tier SSD** (mount `/srv/pgdata`) | Không tái tạo được. Máy thay thế mount lại LUN là chạy tiếp — khác biệt giữa "vài giờ" và "khôi phục toàn bộ từ NAS" |
| OpenSearch | **T1** (sửa 2026-10-10: index `segment` chứa vector, Qdrant đã gỡ) | **NVMe cục bộ** (chấp nhận mất node) + **snapshot hàng tuần** ra NAS, giữ 4 bản | Phần lexical dựng lại từ PostgreSQL ≤12h, nhưng vector phải re-embed (tới 72h GPU — `backup-restore.md` §1) ⇒ khôi phục từ snapshot rồi reindex/re-embed phần chênh; giữ NVMe cho độ trễ p95 ≤ 1s |
| Volume SeaweedFS | T0 | **Dataset ZFS** `tank/object` (pool trên LUN SAN tier HDD) | Đúng thiết kế gốc |
| OS, image Docker, scratch | — | NVMe cục bộ (**RAID1**) | |
| Vùng làm việc OCR/STT (scratch) | không sao lưu | **NVMe cục bộ của node AI** | ⚠️ Khác `kien_truc_he_thong.adoc` §3 (đang ghi SAN tier SSD). File tạm, vòng đời ngắn, IOPS cao — đẩy qua iSCSI là lãng phí băng thông 25GbE vốn đang dùng cho luồng object |
| Staging sao lưu + sandbox khôi phục | — | **SAN tier SSD** (dùng dư địa ~40TB) | `backup-restore.md` QĐ 5 cần sandbox nhưng chưa chỉ định chỗ |

**Cảnh báo cần đo:** PostgreSQL trên iSCSI có độ trễ ~0.3–0.5ms so với NVMe cục
bộ ~0.08ms. Với `shared_buffers=6GB` và phần lớn truy vấn là metadata đã cache,
đây là đánh đổi chấp nhận được để lấy khả năng dựng lại nhanh. **Phải đo
`pg_stat_statements` sau triển khai**; nếu không đạt NFR thì phương án lùi là
chuyển về NVMe cục bộ và chấp nhận RTO L3 dài hơn.

Cả 2 cặp NVMe (mỗi node 2×3.84TB) đề xuất **RAID1**, không RAID0 — yêu cầu đầu
bài là ổn định, không phải dung lượng (3.84TB khả dụng vẫn thừa so với nhu cầu
~1.5TB).

**Khuyến nghị bổ sung — dùng pgBackRest cho PostgreSQL**: repo đặt trên S3 của
chính SeaweedFS, lo base backup + WAL archiving liên tục + PITR + verify. Đây là
thứ phục vụ trực tiếp mục tiêu **RPO ≤ 15 phút** của `backup-restore.md` §3, và
là phần thay thế cho operator CloudNativePG mà phương án k8s từng có.

---

## 5. Đối chiếu với 5 yêu cầu vận hành của người dùng

### 5.1. "Ổn định cao nhất khi vận hành"

| Việc | Cơ chế |
|---|---|
| Định mức tài nguyên | Bảng mục 4.1/4.2 — `mem_limit` + `cpus` cho **mọi** service. `mem_limit` là giới hạn cgroup cứng: vượt thì container đó chết, không lan sang service khác |
| Ưu tiên khi thiếu RAM | `oom_score_adj: -500` cho tầng dữ liệu (PostgreSQL, OpenSearch, SeaweedFS) ⇒ kernel OOM-killer chọn chúng **sau cùng** |
| Phát hiện service **treo** | `healthcheck` cho **mọi** service + container **`autoheal`** tự restart container `unhealthy`. ⚠️ Đây là khoảng trống thật của Compose: `restart: unless-stopped` chỉ kích hoạt khi tiến trình **thoát hẳn**, không kích hoạt khi deadlock/GC thrash. Không có `autoheal` thì service treo sẽ treo mãi |
| Tự khởi động sau reboot | **systemd unit** bọc `docker compose up -d`, `After=zfs.target iscsi.service` |
| Swap | **Tắt swap** trên cả 2 node (PostgreSQL/OpenSearch không được swap) |
| Mất node | **Không tự phục hồi** — và k8s với 2 node cũng không cho được (mục 3.6a). Cơ chế bù là **dựng lại nhanh**: state nằm trên LUN SAN, mount lại là có dữ liệu |
| Quản trị trực quan | **Portainer CE** — R3 xem log, restart, xem tài nguyên container không cần SSH |

⚠️ **Nói thẳng về giới hạn:** hệ này **không có HA**. Phần cứng chỉ có 2 node,
mọi service stateful đều singleton. Điều duy nhất phương án nào cũng phải làm là
rút ngắn thời gian dựng lại. Compose thắng ở đúng điểm đó (mục 3.6c) — đó là lý
do nó được chọn, không phải vì nó "nhiều tính năng hơn".

### 5.2. "Công cụ monitor trực quan, dễ tiếp cận"

- **Đặt Prometheus + Grafana + Alertmanager trên node AI**, chạy như một project
  Compose riêng. Lý do: khi node ứng dụng gặp sự cố, chính là lúc cần dashboard
  nhất; đặt ở đó thì dashboard chết theo. Node AI dư CPU/RAM và là "con mắt"
  độc lập.
- **Không dùng cụm OpenSearch nghiệp vụ để chứa log.**
  `danh_gia_lua_chon_cong_nghe.adoc` §4.11 đề xuất Fluent Bit → OpenSearch sẵn
  có để "giảm số thành phần". Đánh đổi này sai ở ba điểm: (a) log spike tranh
  heap/IOPS với chỉ mục tìm kiếm nghiệp vụ; (b) OpenSearch nghiệp vụ ở tầng
  backup T2, trộn log vào làm sai phân tầng; (c) node chết thì mất luôn log.
  → Dùng **Loki + Promtail trên node AI** (Promtail chạy ở cả 2 node).
- **Exporter cần có** (chưa tài liệu nào liệt kê): `node-exporter`, **cAdvisor**
  (container riêng — Compose không có sẵn như kubelet), `postgres_exporter`,
  `redis_exporter`, `opensearch-exporter` (kèm k-NN stats),
  RabbitMQ `rabbitmq_prometheus` (built-in), Kong metrics, Keycloak metrics,
  **`dcgm-exporter`** (GPU), **`blackbox_exporter`**, và **`snmp_exporter`** cho
  **SAN ME5284 + NAS QNAP + switch lõi** — ba thiết bị này hiện **không nằm
  trong bất kỳ kế hoạch giám sát nào**, dù hỏng đĩa SAN im lặng là rủi ro lớn
  nhất của cả hệ.
- ⚠️ **Backend Spring Boot hiện KHÔNG có `spring-boot-starter-actuator` /
  `micrometer-registry-prometheus`** (đã kiểm tra `backend/pom.xml`). Không có
  hai dependency này thì không có metric ứng dụng nào — và cũng **không có
  `/actuator/health` để làm `healthcheck`**, tức mục 5.1 cũng hụt theo. Xem Q11.

### 5.3. "Log để dễ phân tích chẩn đoán lỗi"

- Promtail chạy ở cả 2 node đọc log container → Loki, **retention 30 ngày**,
  bật `compactor.retention_enabled`.
- ⚠️ **Giới hạn log Docker là bắt buộc, không phải tuỳ chọn.** Docker mặc định
  **KHÔNG** giới hạn dung lượng log — đây chính là nguyên nhân sự cố thật: log
  backend phình **72GB** làm đầy đĩa, khiến PostgreSQL `FATAL: could not write
  init file`. Đặt `max-size=10m` + `max-file=5` ở **mọi** service, **và** đặt
  mặc định trong `/etc/docker/daemon.json` để service thêm sau cũng dính.
- **Log có cấu trúc (JSON) + `trace_id`** xuyên suốt Kong → backend → RabbitMQ →
  AI worker. Không có `trace_id` thì việc truy "một tài liệu đi đâu mất" phải dò
  tay qua 4 hệ.
- **Cấm ghi nội dung văn bản đã nhận dạng vào log** — `digitization-pipeline.md`
  §3 (Security). Kiểm tra lại khi cấu hình Promtail.

### 5.4. "Cơ chế tự động dọn rác"

Tầng **ứng dụng đã có** (đã kiểm tra code): `UploadCleanupJob`,
`AuditRetentionJob`, `AuditPartitionMaintenanceJob`, `OutboxRetentionJob`,
`TrashPurgeJob`. Tầng **hạ tầng thì chưa có gì**:

| Đối tượng | Cơ chế đề xuất | Lịch |
|---|---|---|
| **SeaweedFS volume vacuum** ⚠️ | `weed shell -c "volume.vacuum -garbageThreshold=0.3"` qua systemd timer — **không có thao tác này thì xoá object KHÔNG giải phóng dung lượng**. Hiện không tài liệu nào nhắc tới | Hàng tuần, ngoài giờ |
| Snapshot ZFS quá hạn | Prune theo GFS trong `backup-agent` (`backup-restore.md` QĐ 6 đã thiết kế, **chưa implement**) | Hàng ngày |
| WAL archive PostgreSQL | **pgBackRest** tự quản theo `repo1-retention-full` (14 ngày PITR) | Hàng ngày |
| Log container | `max-size=10m`/`max-file=5` ở mọi service **và** trong `/etc/docker/daemon.json` | Liên tục |
| Log Loki | `compactor` retention 30 ngày | Liên tục |
| Metric Prometheus | `--storage.tsdb.retention.time=90d` + `--storage.tsdb.retention.size=200GB` | Liên tục |
| Image cũ trong registry nội bộ | Chính sách giữ tag + Garbage Collection của registry | Hàng tuần |
| Scratch OCR/STT | `systemd-tmpfiles` xoá file > 48h trong thư mục làm việc | Hàng ngày |
| Chữ ký ClamAV cũ | Dọn `.cvd` cũ sau khi đồng bộ offline | Theo lịch air-gap |
| Snapshot OpenSearch cũ | OpenSearch SLM giữ 4 bản (T1 từ 2026-10-10 vì chứa vector; trước: 2 bản, Qdrant 4 bản) | Hàng tuần |
| **Cảnh báo dung lượng** | Alert Prometheus ở **70%** / **85%** cho NVMe, SAN SSD, SAN HDD, NAS — đúng ngưỡng `backup-restore.md` §3 | Liên tục |

### 5.5. "Dễ khôi phục khi dịch vụ lỗi"

| Mức | Cơ chế |
|---|---|
| Container lỗi | `restart: unless-stopped` |
| Container **treo** | `healthcheck` + **`autoheal`** (mục 5.1) |
| Cấu hình sai | Toàn bộ `docker-compose.yml` + cấu hình service quản lý bằng **Git**, triển khai bằng **Ansible** ⇒ rollback = `git revert` + chạy lại playbook |
| Rollback phiên bản | Đổi tag image trong compose file rồi `docker compose up -d <service>` |
| Mất node | Cài lại OS + Docker → `git clone` cấu hình → `zpool import` + mount LUN → `docker compose up -d`. **Đo bằng giờ** (mục 3.6c) |
| Dữ liệu hỏng/mất | 3 mức khôi phục L1/L2/L3 ở `backup-restore.md` QĐ 5 — **nhưng chưa có một dòng code nào** (xem `backup-restore-strategy.md` §1). Đây là khoảng trống lớn nhất về khả năng khôi phục hiện nay |
| Quản trị trực quan | **Portainer CE** |

⚠️ **Ràng buộc chéo phải biết trước khi triển khai:** job `trash-purge` đang
**fail-closed vĩnh viễn** vì bảng `backup_run` chưa tồn tại — hệ thống hiện
**không bao giờ xoá cứng** dữ liệu thùng rác quá hạn. Go-live mà chưa vá điểm
này nghĩa là cơ chế dọn rác nghiệp vụ **không hoạt động**, dung lượng chỉ tăng.

---

## 6. Rủi ro

| Rủi ro | Mức | Giảm thiểu |
|---|---|---|
| **Hệ không có HA** — mất `node-app` là dừng toàn bộ nghiệp vụ | **Cao** | Chấp nhận có ý thức (2 node, mọi stateful đều singleton). Bù bằng: state trên LUN SAN, runbook dựng lại, diễn tập hằng quý, hợp đồng bảo hành đổi trả nhanh |
| Service **treo** mà không chết ⇒ `restart: unless-stopped` không kích hoạt | **Cao** | `healthcheck` đầy đủ + container `autoheal`; kiểm chứng bằng cách cố tình treo một service |
| Chưa có code `backup-agent` ⇒ chưa có khôi phục thật, dù thiết kế đã Approved | **Cao** | Ưu tiên MVP bảng `backup_run` trước go-live |
| Trần NAS ~77TB < SAN ~205TB | **Cao** | Q6 — chốt trần dữ liệu + GFS; alert 70/85% |
| Nhiều tiến trình dùng chung một GPU ⇒ một worker tràn VRAM kéo cả card chết | Trung bình | Ép ngân sách VRAM ở tầng runtime; giám sát DCGM; `prefetch=1` |
| OCR không đạt 1.500 trang/giờ do quyết định "luôn OCR toàn bộ PDF" | Trung bình | Benchmark giai đoạn 0 trên L4 thật; cho phép OCR worker #2 trên GPU0 (mục 4.2) |
| `zfs scrub` phát hiện hỏng nhưng không tự sửa được trên LUN RAID | Trung bình | Mục 3.3 + runbook khôi phục từ NAS |
| PostgreSQL trên iSCSI không đạt NFR độ trễ | Trung bình | Đo `pg_stat_statements`; phương án lùi là NVMe cục bộ |
| 2 tài liệu Approved ghi sai GPU 96GB | Trung bình | Sửa 2 file, tính lại `ai_queue.concurrency`/`batch_size` |
| LLM giai đoạn 2 bị chặn trần 24GB/card, băng thông L4 thấp | Trung bình | Ghi nhận trước; nếu FR-24 GĐ2 cần RAG nhiều phiên thì phải bổ sung GPU |
| Lệch MTU/thiếu multipath làm iSCSI chậm hoặc treo khi failover | Trung bình | Mục 3.5; nghiệm thu bằng test failover controller **có tải** |

---

## 7. Kết luận mục 1 của yêu cầu

**Phần cứng đủ và phân bổ ở mức thô là đúng.** Bản đầu nêu 6 điểm; 3 điểm kiến
trúc đã chốt (mục 8 nhóm A), 3 điểm còn lại xử lý bằng cấu hình — đều đã có
phương án cụ thể ở mục 4 và 5.4.

Quyết định nền tảng: **Docker Compose + systemd + Portainer**, giữ nguyên lựa
chọn đã có ở `danh_gia_lua_chon_cong_nghe.adoc` §4.10 sau khi cân nhắc lại
Kubernetes (mục 3.6). Lý do quyết định: với **2 node cố định**, k8s **không**
cho được HA cho tầng dữ liệu, trong khi chi phí vận hành trong môi trường
air-gap là thường trực và làm **chậm** việc dựng lại hệ — mà dựng lại nhanh
chính là cơ chế ổn định duy nhất mà cấu hình phần cứng này cho phép.

Ba điều kiện kèm theo khi triển khai:

1. Các giá trị đặc thù hiện trường (IP, VLAN, IQN, hostname, mật khẩu) để dạng
   placeholder trong tài liệu, điền khi triển khai — xem `deployment/README.md`.
2. Phần **chính sách giữ bản sao** ở `06-sao-luu-khoi-phuc.md` chờ Q6 (số liệu
   dung lượng thật).
3. Rủi ro tồn dư lớn nhất — mất `node-app` làm dừng toàn bộ nghiệp vụ — được
   chấp nhận có ý thức, bù bằng runbook + diễn tập + state đặt trên SAN,
   **không** bằng kiến trúc.

---

## 8. Câu hỏi cần xác nhận trước khi viết tài liệu cài đặt

### Nhóm A — ĐÃ CHỐT (2026-09-16)

- [x] **Q0. Nền tảng điều phối** → **Docker Compose + systemd + Portainer CE**,
      giữ nguyên `danh_gia_lua_chon_cong_nghe.adoc` §4.10. Kubernetes đã được
      xem xét lại và **loại** — lý do đầy đủ ở mục 3.6. Tóm tắt: với 2 node,
      k8s không cho được HA cho tầng dữ liệu, trong khi làm **chậm** việc dựng
      lại hệ (thứ duy nhất thật sự bảo vệ hệ này) và thêm chi phí vận hành
      thường trực trong môi trường air-gap.
      **Điều kiện mở lại:** có node thứ 3 làm quorum, **hoặc** đội vận hành có
      kinh nghiệm k8s production air-gapped thực tế.
- [x] **Q1. Không có HA — chấp nhận có ý thức.** Mất `node-app` ⇒ dừng toàn bộ
      nghiệp vụ cho tới khi dựng lại. Đây là **kịch bản sự cố số 1**, bắt buộc
      có runbook riêng + diễn tập hằng quý (`07-runbook-su-co.md`). Bù bằng:
      state đặt trên LUN SAN (mount lại là có dữ liệu), cấu hình trong Git +
      Ansible, và **hợp đồng bảo hành đổi trả nhanh** cho phần cứng.
- [x] **Q2. Lưu trữ stateful** → **LUN của ME5284 qua iSCSI + multipath**, mount
      trực tiếp ở host. Không cần CSI (Dell CSM không hỗ trợ dòng ME5 — vấn đề
      này biến mất cùng với k8s).
- [x] **Q3. OpenSearch** → **NVMe cục bộ**. Chấp nhận mất chỉ mục khi mất node,
      dựng lại từ PostgreSQL ≤ 12h (đúng NFR, tầng backup T2). *(Sửa 2026-10-10: index `segment` chứa vector ⇒ OpenSearch lên T1, thêm snapshot tuần ra NAS để khỏi re-embed — vẫn giữ NVMe cục bộ.)* Đổi lại được độ
      trễ thấp nhất cho p95 ≤ 1s.
- [x] **Q4. `backup-agent`** → **systemd mức host**, đúng nguyên bản
      `backup-restore.md` QĐ 4. **Không cần sửa tài liệu đã Approved** — đây là
      một lợi ích trực tiếp của việc bỏ k8s.
- [x] **Q9. API Gateway** → **Kong** chế độ DB-less, theo
      `danh_gia_lua_chon_cong_nghe.adoc` §4.3 (`key-auth`/`rate-limiting`/`acl`
      cho FR-33). Phương án lùi nếu FR-32/33 đổi phạm vi: nginx thuần.

### Nhóm A' — Còn lại, KHÔNG chặn việc viết tài liệu cài đặt

- [ ] **Q5. Duyệt bảng phân bổ RAM/CPU mục 4.1 + 4.2.** Tài liệu cài đặt viết
      theo đúng bảng này; nếu duyệt khác thì sửa số, không sửa cấu trúc.
- [ ] **Q6. Dung lượng dữ liệu dự kiến và tốc độ tăng trưởng** (TB ban đầu +
      TB/tháng). Chỉ chặn phần **chính sách giữ bản sao** ở
      `06-sao-luu-khoi-phuc.md` — các phần còn lại không phụ thuộc. Cần để kiểm
      chứng NAS ~77TB có đủ cho GFS 12 tháng không.

### Nhóm B — Cần bổ sung thông tin

- [ ] **Q7. Mạng:** thiết bị switch lõi là gì, đã có chưa? Mỗi node có mấy cổng
      25GbE? Đã có VLAN iSCSI riêng chưa? Dải IP/VLAN của production? (k8s còn
      cần thêm: dải IP cho pod/service CIDR không trùng mạng nội bộ, và cách
      cấp IP cho `LoadBalancer` — MetalLB hay NodePort + VIP?)
- [ ] **Q8. Quy trình air-gap:** đưa image Docker + trọng số model + chữ ký
      ClamAV vào VLAN cách ly bằng cách nào? Với Compose thì `docker save/load`
      qua phương tiện được kiểm soát là đủ — **không bắt buộc** phải có registry
      nội bộ (khác hẳn k8s). Nếu đã có Harbor/GitLab Registry thì vẫn nên dùng
      cho tiện quản lý phiên bản.
- [ ] **Q10. Hạ tầng cơ bản:** hệ điều hành + phiên bản (Ubuntu 22.04/24.04 LTS
      hay RHEL 9)? RAM của NAS QNAP? Có UPS cho cả 2 node + SAN + NAS + switch
      không, có auto-shutdown khi UPS sắp cạn không? Kênh cảnh báo của
      Alertmanager trong mạng cách ly (SMTP nội bộ, webhook, hay chỉ màn hình
      NOC)?

### Nhóm C — Vào backlog DEV, không chặn hạ tầng

- [ ] **Q11.** Thêm `spring-boot-starter-actuator` + `micrometer-registry-prometheus`
      vào `backend/pom.xml` — không có thì vừa không có metric ứng dụng, vừa
      không có `/actuator/health` cho liveness/readiness probe.
- [ ] **Q12.** Implement tối thiểu bảng `backup_run` để gỡ `trash-purge` khỏi
      trạng thái fail-closed.
- [ ] **Q13.** Sửa `ai-search-infra.md` §2/§6/§7 và `digitization-pipeline.md`
      §2 (GPU 96GB → 2×L4 24GB), `so do vat ly.adoc` §3.2/§3.3 (cụm 2 node
      giống nhau → 1 node ứng dụng + 1 node AI; L40S 48GB → L4 24GB), và tính
      lại `ai_queue.concurrency`/`batch_size` trong `system-config.md`.
- [ ] **Q14.** `danh_gia_lua_chon_cong_nghe.adoc` §4.11 (log → cụm OpenSearch
      nghiệp vụ) cần đổi sang **Loki** — lý do ở mục 5.2.

---

## 9. Tài liệu cài đặt (mục 2 của yêu cầu)

Đã viết, xem `docs/00-overview/deployment/`:

1. `00-chuan-bi-ha-tang.md` — OS, kernel tuning, tắt swap, RAID1 NVMe, NVIDIA
   driver, multipath/iSCSI, ZFS pool + dataset, firewall.
2. `01-nen-tang-docker.md` — Docker Engine, systemd unit, Portainer, mạng giữa
   2 node, đưa image qua air-gap, quản lý bí mật.
3. `02-workload-ung-dung.md` — compose + `mem_limit`/`cpus` mục 4.1,
   healthcheck, `autoheal`, pgBackRest, bật TLS + xác thực cho 3 service stateful.
4. `03-workload-ai.md` — phân bổ GPU mục 4.2, embedding-service/OCR/STT/video worker, nạp
   trọng số model offline.
5. `04-giam-sat-va-log.md` — Prometheus + Grafana + Loki + toàn bộ exporter (kể
   cả SNMP cho SAN/NAS/switch), dashboard và alert rule.
6. `05-don-rac-tu-dong.md` — systemd timer cho từng mục ở bảng 5.4.
7. `06-sao-luu-khoi-phuc.md` — vận hành `backup-agent` (phụ thuộc Q6).
8. `07-runbook-su-co.md` — quy trình xử lý từng loại sự cố, thứ tự khởi động
   lại, kịch bản diễn tập.

> Bản phác thảo theo phương án Kubernetes được lưu ở
> `docs/_archive/2026-09-16-deployment-01-phuong-an-k8s.md` để tra cứu nếu điều
> kiện ở mục 3.6g được thoả.
