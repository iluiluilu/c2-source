# 03 — Workload AI (`node-ai`, 2× NVIDIA L4 24GB)

> Phụ thuộc: [`01-nen-tang-docker.md`](01-nen-tang-docker.md) và
> [`02-workload-ung-dung.md`](02-workload-ung-dung.md) (RabbitMQ + SeaweedFS S3
> trên `node-app` phải sẵn sàng và mở đúng cổng cho `<NODE_AI_IP>`).
> Project: `/opt/aismartstore/ai/docker-compose.yml`

## 1. Phân bổ GPU

**Phần cứng thật: 2× NVIDIA L4 24GB** (đã xác nhận 2026-09-16). Lưu ý ba đặc
tính của L4 chi phối toàn bộ mục này:

- **Không MIG** (Ada) ⇒ nhiều tiến trình dùng chung một card thì **không cách ly VRAM**.
- **Không NVLink** ⇒ không gộp 2×24GB thành 48GB; trần một model là 24GB.
- **Băng thông ~300GB/s** (L40S ~864GB/s) ⇒ ảnh hưởng rõ khi chạy LLM ở giai đoạn 2.

### 1.1. Ngân sách VRAM (giai đoạn 1 — KHÔNG có LLM)

| GPU | Tiến trình | Ngân sách VRAM | Ép bằng |
|---|---|---|---|
| **GPU0** | `embedding-service` (Qwen3-Embedding-0.6B, nhóm AI vận hành — thay `tei-embed`/bge-m3 2026-10-10) | ~3GB | cấu hình batch/concurrency phía dịch vụ |
| | `ocr-worker-0` *(ưu tiên thấp)* | 5GB | `batch_size`, `PADDLE_GPU_MEM` |
| | **dự phòng** | **15GB** | ← chỗ dành sẵn cho LLM giai đoạn 2 |
| **GPU1** | `stt-worker` (PhoWhisper-large, CT2 `int8_float16`) | 5GB | `num_workers=1`, `beam_size` |
| | `ocr-worker-1` | 5GB | như `ocr-worker-0` |
| | `video-worker` (FFmpeg NVDEC/NVENC) | 2GB | số luồng decode đồng thời |
| | **dự phòng** | **12GB** | |

> ⚠️ **Không có cơ chế nào ép được VRAM ở tầng điều phối.** Docker (cũng như
> k8s) chỉ cấp quyền truy cập card, không chia được bộ nhớ của nó. Nếu một tiến
> trình xin quá ngân sách thì **cả card OOM và mọi tiến trình trên card đó cùng
> chết**. Ngân sách trên phải ép ở tầng runtime của từng service (cột "Ép
> bằng"), và giám sát bằng `dcgm-exporter` với cảnh báo ở 85% VRAM.

### 1.2. Vì sao cho OCR chạy trên GPU0

`so do vat ly.adoc` §3.3 chia GPU0 = trực tuyến, GPU1 = theo lô — hợp lý khi hai
card **không cân sức** (L40S 48GB + L4 24GB). Thực tế là **2× L4 giống hệt nhau
và giai đoạn 1 không có LLM**: tải trực tuyến chỉ là embed 1 truy vấn (≤150ms)
— không rerank (bỏ 2026-10-08, `search-semantic.md` QĐ 2), quá nhỏ để chiếm trọn một card. Trong khi đó **OCR là nút thắt
thật**, càng nặng hơn sau quyết định của C02 (`digitization-pipeline.md` QĐ 1):
**luôn OCR toàn bộ PDF kể cả khi đã có lớp text**.

**Cờ tắt khẩn cấp bắt buộc:** biến `AI_QUEUE_GPU0_BATCH_ENABLED` trong `.env`
(mặc định `true`).

Quy trình: nếu Grafana báo p95 embed truy vấn vượt 150ms kéo dài > 10 phút →
`docker compose stop ocr-worker-0`. GPU0 trở về đúng vai "chỉ trực tuyến". Ghi
vào [`07-runbook-su-co.md`](07-runbook-su-co.md).

**Khi bật LLM ở giai đoạn 2:** tắt `ocr-worker-0`, GPU0 = LLM + embedding-service, GPU1 giữ
nguyên. **Không cần mua thêm phần cứng.**

### 1.3. Ghim container vào đúng GPU

```yaml
  embedding-service:
    deploy:
      resources:
        reservations:
          devices:
            - driver: nvidia
              device_ids: ['0']      # '1' cho cac service tren GPU1
              capabilities: [gpu]
```

Cần `nvidia-container-toolkit` trên `node-ai`:

```bash
sudo dpkg -i nvidia-container-toolkit_*.deb
sudo nvidia-ctk runtime configure --runtime=docker
sudo systemctl restart docker
docker run --rm --gpus all <REGISTRY>/cuda:12.4-base nvidia-smi
```

> **Ưu điểm so với k8s ở đúng điểm này:** `device_ids` chỉ định card **tường
> minh**, không cần GPU Operator, không cần cấu hình time-slicing replica. Đơn
> giản hơn và dễ suy luận hơn khi đọc file cấu hình.

## 2. Trọng số model — nạp offline

**Cấm tải từ Internet lúc khởi động** (NFR-04, `ai-search-infra.md` QĐ 4).

```bash
# Tren node-ai, sau khi mang qua air-gap
sudo mkdir -p /var/lib/local-pv/models/{paddleocr,vietocr,phowhisper,qwen3-embedding-0.6b}
# giai nen trong so vao dung thu muc, chmod 444
```

```yaml
# Dung chung cho moi worker, mount chi doc
x-models: &models
  - /var/lib/local-pv/models:/models:ro

environment:
  HF_HUB_OFFLINE: "1"
  TRANSFORMERS_OFFLINE: "1"
```

| Model | Đường dẫn | Dung lượng ~ |
|---|---|---|
| PaddleOCR DB++ (detection) | `/models/paddleocr` | ~50MB |
| VietOCR (recognition) | `/models/vietocr` | ~150MB |
| PhoWhisper-large (CT2) | `/models/phowhisper` | ~3GB |
| Qwen3-Embedding-0.6B (thay bge-m3 2026-10-10) | `/models/qwen3-embedding-0.6b` | ~1.2GB |

> **Bảng `ai_model_registry`** (`digitization-pipeline.md` QĐ 1) ghi model /
> version / task / eval_metrics / status — đây là cơ chế đổi và rollback model.
> Đường dẫn trong bảng phải khớp với thư mục ở trên.

## 3. Embedding — `embedding-service` (sửa 2026-10-10, thay TEI/bge-m3)

Dịch vụ embedding **do nhóm AI xây dựng và vận hành** (Qwen/Qwen3-Embedding-0.6B,
dense 1024 chiều, vector đã chuẩn hoá L2). Tài liệu này chỉ chốt phần giao tiếp
với hệ thống (`search-semantic.md` QĐ 6, `ai-search-infra.md` QĐ 4):

- Chạy trên `node-ai`, GPU0 (`device_ids: ['0']`), ngân sách VRAM ~3GB, `mem_limit`
  6g; trọng số nạp offline từ `/models/qwen3-embedding-0.6b` (mục 2,
  `HF_HUB_OFFLINE=1`).
- Hợp đồng: `POST /ai/services/v1/embedding/embed` body
  `{"inputs": ["..."], "mode": "dense"}` → `[{"dense": [float×1024]}, ...]` cùng
  thứ tự `inputs`. **Không xác thực** ⇒ chỉ bind IP nội bộ của `node-ai`, chỉ cho
  `<NODE_APP_IP>` (backend) gọi tới — mở rule tương ứng ở mục 7 phía `node-app`.
- Backend trỏ tới dịch vụ qua env `EMBEDDING_API_URL`.
- Có healthcheck + `autoheal: "true"` như mọi service khác (mục 1 quy tắc chung).
- Giới hạn batch/concurrency và p95 trong mạng nội bộ: **chờ nhóm AI xác nhận**
  (câu hỏi mở `search-semantic.md` mục 7).

**Không triển khai `tei-rerank`** ở giai đoạn 1 — FR-24 xếp hạng bằng RRF, không
rerank (`search-semantic.md` QĐ 2, `ai-search-infra.md` QĐ 4, sửa 2026-10-08).

> **NFR liên quan:** embed 1 truy vấn ≤ 150ms; ngữ nghĩa end-to-end p95 ≤ 3s
> (`ai-search-infra.md` §3). Service này phục vụ tải **tương tác** nên khi
> tài nguyên căng, ưu tiên nó hơn worker chạy nền — cụ thể: `ocr-worker-0`
> đặt `cpus` thấp hơn và bị dừng trước (§1.2).

> ⚠️ **`model_version` là một phần khoá của chỉ mục vector.** Đổi model embedding
> ⇒ **phải re-embed toàn bộ** (tới 72h GPU theo `ai-search-infra.md` §6). Vì vậy
> tham số này nằm ở cấu hình triển khai, **không** đưa vào UI cấu hình runtime
> của FR-01. Đổi model phải theo quy trình: tạo collection mới → re-embed vào đó
> ở giờ thấp điểm → đổi alias.

## 4. OCR worker

```yaml
  ocr-worker-0:
    <<: *common
    image: <REGISTRY>/ocr-worker:<TAG>
    mem_limit: 12g
    cpus: "8"
    environment:
      RABBITMQ_HOST: <NODE_APP_IP>
      RABBITMQ_QUEUE: q.ocr
      RABBITMQ_PREFETCH: "1"            # backpressure, QD 2
      BATCH_SIZE: ${AI_QUEUE_BATCH_SIZE}
      S3_ENDPOINT: http://<NODE_APP_IP>:8333
      HF_HUB_OFFLINE: "1"
    volumes:
      - /var/lib/local-pv/models:/models:ro
      - /var/lib/local-pv/scratch:/scratch
    deploy:
      resources:
        reservations:
          devices: [{ driver: nvidia, device_ids: ['0'], capabilities: [gpu] }]
```

`ocr-worker-1` giống hệt, đổi `device_ids: ['1']`.

Pipeline: tiền xử lý CPU (deskew, khử nhiễu, phát hiện hướng trang) → PaddleOCR
DB++ phát hiện vùng chữ → VietOCR nhận dạng.

**Quyền truy cập dữ liệu** (`digitization-pipeline.md` §3 Security):
- Credential S3 **chỉ đọc** trên prefix bản gốc; **chỉ ghi** vào `derived/`.
- Mở tệp gốc ở chế độ **read-only** (NFR-09, FR-12 — bản gốc bất biến).
- **Không egress** ra ngoài VLAN (§7).
- **Không ghi nội dung văn bản đã nhận dạng vào log.**

**Idempotent + lease:** at-least-once theo `job_id`; job có lease + heartbeat,
phát hiện job "treo" trong ≤15 phút. Worker/GPU chết ⇒ job được redeliver.

> ⚠️ **Chỉ tiêu cần đo ở benchmark giai đoạn 0:** OCR ≥ **1.500 trang/giờ**.
> Quyết định "luôn OCR toàn bộ PDF" làm tăng tải đáng kể so với thiết kế gốc —
> chính `digitization-pipeline.md` QĐ 1 đã ghi "xem lại NFR thông lượng". Nếu
> một L4 không đạt, đó là lý do tồn tại của `ocr-worker-0` trên GPU0.

## 5. STT worker + video worker

```yaml
  stt-worker:
    <<: *common
    image: <REGISTRY>/stt-worker:<TAG>
    mem_limit: 12g
    cpus: "8"
    environment:
      RABBITMQ_HOST: <NODE_APP_IP>
      RABBITMQ_QUEUE: q.stt
      MODEL_PATH: /models/phowhisper
      COMPUTE_TYPE: int8_float16        # EP VRAM ~5GB
      VAD_FILTER: "true"                # Silero VAD truoc ASR
    volumes:
      - /var/lib/local-pv/models:/models:ro
      - /var/lib/local-pv/scratch:/scratch
    deploy:
      resources:
        reservations:
          devices: [{ driver: nvidia, device_ids: ['1'], capabilities: [gpu] }]
```

> **Backend không tách/chuẩn hoá media (từ 2026-09-23, `digitization-pipeline.md`
> QĐ 10 thay QĐ 7).** `stt-worker` nhận **tệp audio gốc** người dùng upload
> (mp3/m4a/wav/ogg/aac — không phải WAV đã chuẩn hoá), nên tự chuẩn hoá
> (resample 16kHz mono) nếu model cần. `video-worker` nhận **tệp video gốc có
> cả audio track** qua `q.video` (không còn bản video-only ở `derived/`);
> video không còn đi `q.stt`.

```yaml
  video-worker:
    <<: *common
    image: <REGISTRY>/video-worker:<TAG>
    mem_limit: 10g
    cpus: "12"
    environment:
      RABBITMQ_QUEUE: q.video
    deploy:
      resources:
        reservations:
          devices: [{ driver: nvidia, device_ids: ['1'], capabilities: [gpu] }]
```

`digitization-pipeline.md` QĐ 8: worker **tự sở hữu toàn bộ vòng đời kết quả**,
không đi qua `q.result`. L4 có NVENC/NVDEC (hỗ trợ cả AV1) — dùng `-hwaccel cuda`
để không chiếm CPU của ingest worker.

**SED (PANNs CNN14)** và **speaker diarization (pyannote)**: feature flag, **mặc
định TẮT** (`digitization-pipeline.md` QĐ 6).

## 6. Ingest worker (CPU, không GPU)

```yaml
  tika:
    <<: *common
    image: <REGISTRY>/tika:<TAG>
    mem_limit: 8g
    cpus: "8"

  ingest-worker:
    <<: *common
    image: <REGISTRY>/ingest-worker:<TAG>
    mem_limit: 12g
    cpus: "16"
    volumes:
      - /var/lib/local-pv/scratch:/scratch
```

Giải nén, phân tích PDF/Office (PyMuPDF), chuẩn hoá định dạng, chia đoạn, trích
khung hình video.

## 7. Chặn egress (NFR-04)

Trên k8s việc này làm bằng `NetworkPolicy`. Với Compose, làm ở tầng host bằng
`iptables` — chặn `FORWARD` từ subnet của Docker ra ngoài, trừ đúng các đích cần:

```bash
# Subnet cua network "ai" trong compose
AI_NET=$(docker network inspect ai_default -f '{{(index .IPAM.Config 0).Subnet}}')

# Cho phep goi 3 service tren node-app (Qdrant 6333 da go 2026-10-10)
for port in 5672 8333 9200; do
  sudo iptables -I DOCKER-USER 1 -s $AI_NET -d <NODE_APP_IP> -p tcp --dport $port -j RETURN
done
# Cho phep DNS noi bo
sudo iptables -I DOCKER-USER 1 -s $AI_NET -d <DNS_NOI_BO> -p udp --dport 53 -j RETURN
# CHAN moi thu con lai di ra ngoai
sudo iptables -A DOCKER-USER -s $AI_NET -j DROP
sudo netfilter-persistent save
```

> Đây là hiện thực kỹ thuật của ràng buộc NFR-04 ("dữ liệu không được xử lý hoặc
> gửi ra bên ngoài hệ thống, kể cả khi xử lý bằng AI"). Không có rule này thì
> ràng buộc chỉ tồn tại trên giấy.

> ⚠️ Subnet của Docker network **đổi khi network bị xoá và tạo lại**. Ghim nó
> bằng `ipam.config.subnet` trong compose file để rule iptables không bị lệch
> sau một lần `docker compose down`.

## 8. Tổng hợp tài nguyên `node-ai`

| Service | `mem_limit` | `cpus` | GPU |
|---|---|---|---|
| `ocr-worker-0` / `ocr-worker-1` | 12g × 2 | 8 × 2 | GPU0 / GPU1 |
| `stt-worker` | 12g | 8 | GPU1 |
| `video-worker` | 10g | 12 | GPU1 |
| `embedding-service` | 6g | 4 | GPU0 |
| `tika` | 8g | 8 | — |
| `ingest-worker` | 12g | 16 | — |
| Prometheus + Alertmanager *(xem 04)* | 8g | 4 | — |
| Grafana *(xem 04)* | 2g | 2 | — |
| Loki *(xem 04)* | 8g | 4 | — |
| Exporter (DCGM, node, cAdvisor) | 2g | 2 | — |
| **Tổng** | **~100g** | **~74** | |

Còn ~28GB cho OS, page cache và vùng scratch.

## 9. Nghiệm thu bước 3

- [ ] `docker compose ps` — mọi service `Up (healthy)`
- [ ] `nvidia-smi` trên `node-ai`: GPU0 ~12GB, GPU1 ~12GB, **không card nào >20GB**
- [ ] `nvidia-smi` cho thấy đúng tiến trình nào trên card nào (khớp §1.1)
- [ ] Thử `HF_HUB_OFFLINE=1` + ngắt mạng ngoài: mọi worker vẫn khởi động được
      (chứng tỏ không có đường tải model ngầm)
- [ ] Đẩy 1 job test vào `q.ocr` → có kết quả ghi vào `derived/`, bản gốc
      **không** bị sửa (kiểm tra mtime + SHA-256)
- [ ] Đẩy 1 job test vào `q.stt` → có transcript + timestamp
- [ ] Giết container worker giữa chừng → job được redeliver, **không mất**
- [ ] **Benchmark giai đoạn 0**: đo OCR trang/giờ và STT × thời gian thực trên
      bộ mẫu thật, đối chiếu NFR (≥1.500 trang/giờ; ≥6× thời gian thực)
- [ ] Kiểm chứng chặn egress: từ trong container worker, `curl` ra một IP ngoài
      VLAN phải **timeout**; nhưng gọi `<NODE_APP_IP>:5672` thì **thành công**
- [ ] Grep log worker: **không** có nội dung văn bản đã nhận dạng
