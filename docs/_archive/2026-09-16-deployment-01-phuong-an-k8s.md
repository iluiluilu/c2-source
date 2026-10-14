# 01 — Cài đặt cụm Kubernetes (RKE2) + hạ tầng nền của cụm

> Phụ thuộc: [`00-chuan-bi-ha-tang.md`](00-chuan-bi-ha-tang.md) đã nghiệm thu xong.

## 1. Topology

```
node-app  <NODE_APP_IP>   R760 48C/96T · 128GB · 2×3.84TB NVMe
   └── RKE2 server (control-plane + etcd + worker)
   └── Tầng dữ liệu: PostgreSQL, OpenSearch, Qdrant, SeaweedFS,
       RabbitMQ, Keycloak, Redis, ClamAV, backend, web, Kong
   └── Pool ZFS `tank` (trên LUN SAN) + LUN-BACKUP-STAGE

node-ai   <NODE_AI_IP>    R760 64C/128T · 128GB · 2× L4 24GB · 2×3.84TB NVMe
   └── RKE2 agent (worker)   [taint: workload=ai:NoSchedule]
   └── Worker AI + stack giám sát
```

> ⚠️ **Rủi ro tồn dư đã chấp nhận (quyết định 2026-09-16):** `node-app` vừa là
> control-plane duy nhất, vừa giữ toàn bộ tầng dữ liệu. Mất node này = dừng
> nghiệp vụ **và** mất khả năng điều phối cụm. Bù bằng: sao lưu etcd (§3), PV
> stateful đặt trên SAN để gắn lại được (§5), và runbook khôi phục ở
> [`07-runbook-su-co.md`](07-runbook-su-co.md). **Không** bù được bằng kiến trúc.

## 2. Air-gap: registry nội bộ + tarball

### 2.1. Vật tư cần mang qua air-gap

| Vật tư | Nguồn | Ghi chú |
|---|---|---|
| `rke2.linux-amd64.tar.gz` + `rke2-images.linux-amd64.tar.zst` + `sha256sum` | Bản phát hành RKE2 | Chọn **một** phiên bản và ghim, không tự nâng |
| `rke2-install.sh` | Script cài đặt RKE2 | |
| Driver NVIDIA `.run` | | Đã dùng ở bước 00 §6 |
| Chart + image: Harbor, kube-prometheus-stack, Loki, GPU Operator, Kong, MetalLB | | |
| Image ứng dụng: `backend`, `web` | Build nội bộ | |
| Image hạ tầng: postgres, opensearch, qdrant, seaweedfs, rabbitmq, keycloak, redis, clamav | | |
| Trọng số model: PaddleOCR, VietOCR, PhoWhisper-large, bge-m3, bge-reranker-v2-m3 | | ~10–15GB, xem `03-workload-ai.md` |
| CSDL chữ ký ClamAV (`.cvd`) | | Cập nhật định kỳ theo lịch air-gap |

**Quy trình:** tải trên máy có Internet → kiểm tra checksum → ghi ra phương
tiện được kiểm soát → quét mã độc → mang vào VLAN → nạp vào Harbor.

> ⚠️ **Khác biệt lớn so với Docker Compose:** trước đây có thể `docker save/load`
> từng image lên máy chủ. Với k8s thì kubelet **kéo image từ registry** ⇒ registry
> nội bộ là **bắt buộc**, và phải mirror cả image hệ thống của cụm (CNI, CSI,
> GPU Operator, giám sát). Thiếu một image hệ thống thì cụm không lên được.

### 2.2. Harbor

Cài Harbor **trước** RKE2, trên `node-app`, chạy bằng Docker Compose độc lập
(cố ý đặt **ngoài** cụm k8s: nếu Harbor nằm trong cụm thì khi cụm hỏng sẽ không
kéo được image để sửa cụm — vòng lặp chết).

```bash
tar xzf harbor-offline-installer-<VERSION>.tgz && cd harbor
cp harbor.yml.tmpl harbor.yml
# harbor.yml:
#   hostname: <DOMAIN_HARBOR>
#   https: { certificate: ..., private_key: ... }   # cert noi bo
#   harbor_admin_password: <HARBOR_PASSWORD>
#   data_volume: /srv/harbor
sudo ./install.sh
```

Nạp image:

```bash
for img in $(cat image-list.txt); do
  skopeo copy docker-archive:images/$(basename $img).tar \
    docker://<DOMAIN_HARBOR>/library/$(basename $img)
done
```

Khai báo mirror cho RKE2 (**cả 2 node**, tạo trước khi cài RKE2):

```yaml
# /etc/rancher/rke2/registries.yaml
mirrors:
  docker.io:        { endpoint: ["https://<DOMAIN_HARBOR>"] }
  quay.io:          { endpoint: ["https://<DOMAIN_HARBOR>"] }
  registry.k8s.io:  { endpoint: ["https://<DOMAIN_HARBOR>"] }
  ghcr.io:          { endpoint: ["https://<DOMAIN_HARBOR>"] }
configs:
  "<DOMAIN_HARBOR>":
    auth: { username: "robot$rke2", password: "<HARBOR_ROBOT_TOKEN>" }
    tls:  { ca_file: /etc/ssl/certs/ca-noi-bo.crt }
```

## 3. Cài RKE2 server trên `node-app`

```yaml
# /etc/rancher/rke2/config.yaml
node-name: <NODE_APP_HOSTNAME>
tls-san: [ "<NODE_APP_IP>", "<NODE_APP_HOSTNAME>" ]
cluster-cidr: <POD_CIDR>
service-cidr: <SERVICE_CIDR>
cni: canal
write-kubeconfig-mode: "0600"
profile: cis                      # CIS hardening cua RKE2
selinux: false
disable:
  - rke2-ingress-nginx            # dung Kong thay the (§8)
kubelet-arg:
  # Phan RAM/CPU danh cho OS + kubelet, KHONG cap cho pod.
  # Thieu hai dong nay thi node se bi xep pod qua tay va treo duoi tai.
  - "system-reserved=cpu=2,memory=4Gi,ephemeral-storage=20Gi"
  - "kube-reserved=cpu=1,memory=2Gi,ephemeral-storage=10Gi"
  - "eviction-hard=memory.available<2Gi,nodefs.available<10%"
  # Gioi han log container (bai hoc: log tung phinh 72GB lam day dia,
  # khien PostgreSQL "FATAL: could not write init file")
  - "container-log-max-size=10Mi"
  - "container-log-max-files=5"
  # Don image khong dung
  - "image-gc-high-threshold=70"
  - "image-gc-low-threshold=60"
etcd-snapshot-schedule-cron: "0 */6 * * *"
etcd-snapshot-retention: 28
etcd-s3: false
```

```bash
sudo mkdir -p /var/lib/rancher/rke2/agent/images/
sudo cp rke2-images.linux-amd64.tar.zst /var/lib/rancher/rke2/agent/images/
sudo INSTALL_RKE2_ARTIFACT_PATH=/root/rke2-artifacts sh rke2-install.sh
sudo systemctl enable --now rke2-server

export KUBECONFIG=/etc/rancher/rke2/rke2.yaml
export PATH=$PATH:/var/lib/rancher/rke2/bin
kubectl get nodes
```

> **`etcd-snapshot-schedule-cron` là dòng quan trọng nhất trong file này.** Với
> 1 control-plane, snapshot etcd là thứ duy nhất cho phép dựng lại cụm. Sao chép
> `/var/lib/rancher/rke2/server/db/snapshots/` sang NAS mỗi ngày — đưa vào
> `backup-agent` ở [`06-sao-luu-khoi-phuc.md`](06-sao-luu-khoi-phuc.md).

## 4. Cài RKE2 agent trên `node-ai`

```yaml
# /etc/rancher/rke2/config.yaml
server: https://<NODE_APP_IP>:9345
token: <RKE2_TOKEN>        # lay o /var/lib/rancher/rke2/server/node-token
node-name: <NODE_AI_HOSTNAME>
node-label:
  - "workload=ai"
node-taint:
  - "workload=ai:NoSchedule"
kubelet-arg:
  - "system-reserved=cpu=2,memory=4Gi,ephemeral-storage=20Gi"
  - "kube-reserved=cpu=1,memory=2Gi,ephemeral-storage=10Gi"
  - "eviction-hard=memory.available<2Gi,nodefs.available<10%"
  - "container-log-max-size=10Mi"
  - "container-log-max-files=5"
  - "image-gc-high-threshold=70"
  - "image-gc-low-threshold=60"
```

```bash
sudo INSTALL_RKE2_TYPE=agent INSTALL_RKE2_ARTIFACT_PATH=/root/rke2-artifacts sh rke2-install.sh
sudo systemctl enable --now rke2-agent
kubectl get nodes -o wide     # phai thay ca 2 node Ready
```

Gắn nhãn cho `node-app`:

```bash
kubectl label node <NODE_APP_HOSTNAME> workload=app
```

> Taint `workload=ai:NoSchedule` đảm bảo **chỉ** workload AI + giám sát lên node
> AI. Không có taint thì scheduler sẽ rải pod ứng dụng sang đó, làm GPU worker
> tranh RAM với chúng.

## 5. PersistentVolume tĩnh trên iSCSI

Không dùng CSI (Dell CSM không hỗ trợ dòng ME5). Khai báo PV trỏ thẳng LUN đã
tạo ở `00-chuan-bi-ha-tang.md` §4.3.

```yaml
# pv-postgres.yaml
apiVersion: v1
kind: PersistentVolume
metadata:
  name: pv-postgres
spec:
  capacity: { storage: 2Ti }
  accessModes: [ReadWriteOnce]
  persistentVolumeReclaimPolicy: Retain      # KHONG bao gio Delete voi du lieu T0
  storageClassName: san-ssd
  iscsi:
    targetPortal: <SAN_PORTAL_A>:3260
    portals: ["<SAN_PORTAL_B>:3260"]
    iqn: <SAN_IQN>
    lun: 0
    fsType: xfs
    readOnly: false
---
# pv-qdrant.yaml — tuong tu, lun: 1, capacity 500Gi, storageClassName: san-ssd
```

```yaml
# sc-san-ssd.yaml — StorageClass "gia", chi de rang buoc PVC voi PV tinh
apiVersion: storage.k8s.io/v1
kind: StorageClass
metadata: { name: san-ssd }
provisioner: kubernetes.io/no-provisioner
volumeBindingMode: WaitForFirstConsumer
reclaimPolicy: Retain
```

Local PV cho OpenSearch và stack giám sát:

```yaml
# sc-local-nvme.yaml
apiVersion: storage.k8s.io/v1
kind: StorageClass
metadata: { name: local-nvme }
provisioner: kubernetes.io/no-provisioner
volumeBindingMode: WaitForFirstConsumer
---
apiVersion: v1
kind: PersistentVolume
metadata: { name: pv-opensearch }
spec:
  capacity: { storage: 1Ti }
  accessModes: [ReadWriteOnce]
  storageClassName: local-nvme
  persistentVolumeReclaimPolicy: Retain
  local: { path: /var/lib/local-pv/opensearch }
  nodeAffinity:
    required:
      nodeSelectorTerms:
        - matchExpressions:
            - { key: kubernetes.io/hostname, operator: In, values: ["<NODE_APP_HOSTNAME>"] }
```

> **`nodeAffinity` trên local PV không phải tuỳ chọn.** Nó là thứ ghim pod vào
> đúng node có dữ liệu. Hệ quả đã biết và đã chấp nhận: mất `node-app` thì pod
> OpenSearch kẹt `Pending` cho tới khi node quay lại, hoặc phải dựng lại chỉ mục
> từ PostgreSQL (≤12h, đúng NFR — OpenSearch là tầng backup T2).

**POC bắt buộc trước khi đi tiếp** — kiểm chứng PV trên SAN thật sự chuyển node
được:

```bash
# 1. Tao pod test mount pv-postgres tren node-app, ghi 1 file
# 2. cordon + drain node-app
kubectl cordon <NODE_APP_HOSTNAME>
kubectl delete pod <pod-test>
# 3. Pod phai duoc xep lai tren node-ai va DOC DUOC dung file do
kubectl uncordon <NODE_APP_HOSTNAME>
```

Không qua bài test này thì **toàn bộ lập luận "k8s giúp tự phục hồi" là vô
hiệu** — phải quay lại xem lại việc map LUN cho `<IQN_NODE_AI>`.

## 6. MetalLB (cấp IP cho Kong)

```yaml
apiVersion: metallb.io/v1beta1
kind: IPAddressPool
metadata: { name: pool-ingress, namespace: metallb-system }
spec:
  addresses: ["<VIP_INGRESS>/32"]
---
apiVersion: metallb.io/v1beta1
kind: L2Advertisement
metadata: { name: l2, namespace: metallb-system }
spec:
  ipAddressPools: [pool-ingress]
```

## 7. Quản lý bí mật

**Không commit secret thật vào Git.** Dùng **Sealed Secrets** (đơn giản, phù
hợp quy mô 2 node, không cần dựng Vault):

```bash
kubeseal --cert pub-cert.pem --format yaml < secret.yaml > sealed-secret.yaml
# sealed-secret.yaml commit duoc; chi controller trong cum giai ma duoc
```

> ⚠️ Khoá riêng của Sealed Secrets controller nằm trong cụm. **Sao lưu nó cùng
> etcd** — mất khoá thì mọi sealed secret đã commit trở thành vô dụng và phải
> tạo lại toàn bộ mật khẩu.

## 8. Kong Ingress Controller (DB-less)

Theo `danh_gia_lua_chon_cong_nghe.adoc` §4.3 — Kong có sẵn `key-auth`,
`rate-limiting`, `acl` phục vụ trực tiếp FR-33 (cấp/thu hồi khoá API, giới hạn
tần suất, giám sát sử dụng).

```bash
helm install kong kong/kong -n kong --create-namespace \
  --set ingressController.enabled=true \
  --set env.database=off \
  --set proxy.type=LoadBalancer \
  --set proxy.annotations."metallb\.universe\.tf/address-pool"=pool-ingress
```

Chế độ **DB-less**: cấu hình lấy từ CRD của k8s, không cần PostgreSQL riêng cho
Kong — bớt một hệ stateful phải sao lưu.

## 9. PriorityClass

```yaml
apiVersion: scheduling.k8s.io/v1
kind: PriorityClass
metadata: { name: tier-data }
value: 1000000
globalDefault: false
description: "PostgreSQL, OpenSearch, Qdrant, SeaweedFS - evict sau cung"
---
apiVersion: scheduling.k8s.io/v1
kind: PriorityClass
metadata: { name: tier-app }
value: 100000
---
apiVersion: scheduling.k8s.io/v1
kind: PriorityClass
metadata: { name: tier-batch }
value: 1000
description: "Worker AI chay nen - evict truoc tien khi thieu tai nguyen"
```

> Đây là cơ chế quyết định **ai chết trước** khi node cạn RAM. Không đặt thì
> kubelet chọn theo QoS + mức vượt request — và pod RSS lớn nhất thường là
> PostgreSQL.

## 10. NVIDIA GPU Operator + time-slicing

```bash
helm install gpu-operator nvidia/gpu-operator -n gpu-operator --create-namespace \
  --set driver.enabled=false \          # driver da cai o buoc 00 §6
  --set toolkit.enabled=true \
  --set dcgmExporter.enabled=true       # metric GPU cho Prometheus
```

Mặc định k8s cấp **trọn 1 GPU cho 1 pod** ⇒ 2 GPU chỉ chạy được 2 pod, trong
khi giai đoạn 1 cần 5 (2 OCR + STT + embed + rerank). Bật time-slicing:

```yaml
apiVersion: v1
kind: ConfigMap
metadata: { name: time-slicing-config, namespace: gpu-operator }
data:
  any: |-
    version: v1
    flags: { migStrategy: none }      # L4 (Ada) KHONG ho tro MIG
    sharing:
      timeSlicing:
        resources:
          - name: nvidia.com/gpu
            replicas: 3               # 3 tien trinh / GPU
```

```bash
kubectl patch clusterpolicy/cluster-policy -n gpu-operator --type merge \
  -p '{"spec":{"devicePlugin":{"config":{"name":"time-slicing-config","default":"any"}}}}'
kubectl describe node <NODE_AI_HOSTNAME> | grep nvidia.com/gpu   # phai thay 6
```

> ⚠️ **Time-slicing KHÔNG cách ly VRAM.** k8s chỉ chia lượt tính toán; nếu một
> tiến trình xin quá nhiều VRAM thì cả card OOM và **mọi** tiến trình trên card
> đó cùng chết. Ngân sách VRAM ở [`03-workload-ai.md`](03-workload-ai.md) phải
> được ép bằng cấu hình của từng runtime (`--max-batch-tokens` của TEI,
> `batch_size` của OCR/STT), **không** bằng k8s.

## 11. Nghiệm thu bước 1

- [ ] `kubectl get nodes` — cả 2 node `Ready`, đúng label/taint
- [ ] `kubectl get pods -A` — không pod nào `ImagePullBackOff` (chứng tỏ mirror
      Harbor đã đủ image)
- [ ] `kubectl describe node <NODE_AI_HOSTNAME> | grep nvidia.com/gpu` = **6**
- [ ] **POC chuyển node của `pv-postgres` đã qua** (§5) — bài test quan trọng nhất
- [ ] `kubectl get pv` — `pv-postgres`, `pv-qdrant`, `pv-opensearch` đều `Available`
- [ ] Snapshot etcd đã sinh ra trong `/var/lib/rancher/rke2/server/db/snapshots/`
- [ ] Kong nhận được `<VIP_INGRESS>`, `curl http://<VIP_INGRESS>` trả 404 của Kong
- [ ] Reboot cả 2 node: cụm tự lên, không can thiệp tay
