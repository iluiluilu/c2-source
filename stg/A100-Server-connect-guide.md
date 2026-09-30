# A100-Server — hướng dẫn kết nối tới server 14.225.222.72

Tài liệu này dành để gửi cho đội vận hành A100-Server (`test01@bright92`,
qua jump host `proxy-server` / `119.17.198.75`) — mô tả cách kết nối tới 4
dịch vụ trên server staging `14.225.222.72`: Postgres, Qdrant, RabbitMQ
(queue), và S3 (get file).

**Chứa secret thật (password/access key)** — gửi qua kênh nội bộ an toàn,
không đăng lên nơi công khai/chat chung.

## 0. Whitelist IP (đã áp dụng)

Cả 4 dịch vụ dưới đây chỉ nhận kết nối từ IP đã whitelist ở tầng firewall
(`iptables` chain `DOCKER-USER` cho Postgres/Qdrant/RabbitMQ, nginx `allow`
cho S3) — kết nối từ IP khác sẽ bị timeout/từ chối.

Đã verify **thực tế** (test kết nối thật từ A100-Server) — IP outbound thật
của A100-Server khi ra Internet là:

```
119.17.198.77
```

(khác với `119.17.198.75` — IP SSH của proxy-server — và khác `172.18.104.104`
— IP nội bộ/private của chính A100-Server, không route ra Internet được).
Cả 3 IP này đều đã được thêm vào whitelist, nhưng **chỉ `119.17.198.77` là
IP thật sự khớp traffic** theo test đã chạy. Nếu sau này đổi hạ tầng mạng
(NAT, proxy khác) và các dịch vụ dưới đây bỗng dưng không connect được nữa,
việc đầu tiên cần kiểm tra là IP outbound thật đã đổi hay chưa (chạy
`curl https://ifconfig.me` từ A100-Server để biết IP hiện tại).

## 1. Postgres

| | |
|---|---|
| Host | `14.225.222.72` |
| Port | `5432` |
| User | `aismartstore` |
| Password | `5wuN9q3hy1CQOyy4` |
| Database | `aismartstore` |
| SSL | Không bật (kết nối thuần TCP nội bộ qua firewall, không phải Internet công khai không kiểm soát) |

Connection string:

```
postgresql://aismartstore:5wuN9q3hy1CQOyy4@14.225.222.72:5432/aismartstore
```

Test nhanh (nếu máy có `psql`):

```bash
psql "postgresql://aismartstore:5wuN9q3hy1CQOyy4@14.225.222.72:5432/aismartstore" -c "select 1;"
```

Python (`psycopg2`/`psycopg`):

```python
import psycopg2
conn = psycopg2.connect(
    host="14.225.222.72", port=5432,
    user="aismartstore", password="5wuN9q3hy1CQOyy4",
    dbname="aismartstore",
)
```

> User `aismartstore` là tài khoản **chung với backend chính**, có toàn
> quyền trên database — không phải tài khoản scope riêng cho A100. Nếu cần
> tách quyền (chỉ đọc, chỉ 1 vài bảng), báo lại để tạo user Postgres riêng.

## 2. Qdrant (vector DB)

| | |
|---|---|
| Host | `14.225.222.72` |
| REST port | `6333` |
| gRPC port | `6334` |
| Auth | **Không có** — Qdrant hiện không bật API key, chỉ dựa vào firewall IP whitelist để bảo vệ. Bất kỳ ai connect được từ IP đã whitelist đều có full quyền (đọc/ghi/xoá collection). |

Test nhanh:

```bash
curl http://14.225.222.72:6333/collections
```

Python (`qdrant-client`):

```python
from qdrant_client import QdrantClient

client = QdrantClient(host="14.225.222.72", port=6333, grpc_port=6334)
print(client.get_collections())
```

## 3. RabbitMQ (queue)

| | |
|---|---|
| Host | `14.225.222.72` |
| AMQP port | `5672` |
| Management UI | `http://14.225.222.72:15672` |
| User | `aismartstore` |
| Password | `5wuN9q3hy1CQOyy4` |
| Vhost | `/` |

AMQP URL:

```
amqp://aismartstore:5wuN9q3hy1CQOyy4@14.225.222.72:5672/
```

Python (`pika`):

```python
import pika

params = pika.URLParameters("amqp://aismartstore:5wuN9q3hy1CQOyy4@14.225.222.72:5672/")
connection = pika.BlockingConnection(params)
channel = connection.channel()
```

> **Đang dùng tài khoản admin** của toàn bộ RabbitMQ (`aismartstore`), có
> quyền trên mọi queue/exchange trong hệ thống, không phải tài khoản scope
> riêng — dùng tạm để kết nối được ngay. Khi biết chính xác tên
> queue/exchange A100 sẽ dùng, nên tạo user riêng chỉ có quyền trên đúng
> queue đó (lệnh mẫu có sẵn ở `README.md` mục firewall/RabbitMQ) để tránh 1
> credential lộ là ảnh hưởng toàn hệ thống.

## 4. Get file (S3 — kho dữ liệu chung)

| | |
|---|---|
| Endpoint | `https://files.green0tech.site` |
| Access key | `ai-ef36d4311bdd4850` |
| Secret key | `ai9ugWjymcp0WTGoJ8wu7EhktWj43sWjD3VdNXqG` |
| Region | `us-east-1` (giá trị bất kỳ cũng được) |
| Bucket | `documents` (kho dùng chung — **read-only**, không Write/Delete) |
| Addressing | **path-style bắt buộc** (`GET /documents/<key>`) — SeaweedFS không hỗ trợ virtual-hosted-style |

Python (`boto3`, khuyên dùng — không phụ thuộc version `curl` trên máy):

```python
import boto3
from botocore.config import Config

s3 = boto3.client(
    "s3",
    endpoint_url="https://files.green0tech.site",
    aws_access_key_id="ai-ef36d4311bdd4850",
    aws_secret_access_key="ai9ugWjymcp0WTGoJ8wu7EhktWj43sWjD3VdNXqG",
    region_name="us-east-1",
    config=Config(s3={"addressing_style": "path"}),  # bat buoc
)

resp = s3.list_objects_v2(Bucket="documents")
for obj in resp.get("Contents", []):
    print(obj["Key"], obj["Size"])

obj = s3.get_object(Bucket="documents", Key="<OBJECT_KEY>")
with open("output_file", "wb") as f:
    for chunk in obj["Body"].iter_chunks(chunk_size=1024 * 1024):
        f.write(chunk)
```

curl (chỉ dùng để test tay — **cần curl >= 7.75**, có `--aws-sigv4`; A100-Server
hiện có curl 7.68, KHÔNG chạy được lệnh dưới, phải dùng boto3 ở trên):

```bash
curl --aws-sigv4 "aws:amz:us-east-1:s3" \
  --user "ai-ef36d4311bdd4850:ai9ugWjymcp0WTGoJ8wu7EhktWj43sWjD3VdNXqG" \
  "https://files.green0tech.site/documents/"
```

> **Giới hạn phạm vi**: object key trong bucket `documents` là phẳng
> (`documents/<uuid>`), không phản ánh cây thư mục trên UI quản trị — key
> này đọc được toàn bộ bucket, không giới hạn được theo từng thư mục.

## Tóm tắt bảo mật hiện tại

- Postgres, Qdrant, RabbitMQ: bảo vệ bằng **firewall IP whitelist** (không
  đi qua Cloudflare, không có WAF/rate-limit).
- Qdrant: **không có credential** ngoài IP whitelist — nếu IP nào đó trong
  whitelist bị xâm nhập, Qdrant coi như mở hoàn toàn.
- Postgres, RabbitMQ: đang dùng chung tài khoản **admin** với hệ thống
  chính, chưa tách quyền riêng cho A100.
- S3: **đã** tách riêng identity `external-ai`, chỉ Read+List bucket
  `documents`, không đụng được bucket `personal`, không Write/Delete/Admin —
  đây là dịch vụ duy nhất trong 4 dịch vụ trên có phân quyền đúng least-privilege.
