# stg — staging deploy

Thư mục tự chứa (chỉ cần copy `stg/` lên server + 2 image `web`/`api`) để chạy
toàn bộ stack qua `docker compose`, có nginx đứng trước, phía sau Cloudflare
lo SSL (Flexible mode, origin chỉ nói HTTP).

- `web.green0tech.site` → web (Next.js)
- `api.green0tech.site` → backend (Spring Boot)
- `auth.green0tech.site` → keycloak (SSO) — cần thêm domain này vì browser
  và backend phải cùng thấy chung một issuer URL để JWT hợp lệ (xem comment
  trong `docker-compose.yml`/`nginx/conf.d/auth.conf`).
- `files.green0tech.site` → seaweedfs S3 gateway, **chỉ cho hệ thống AI
  ngoài** đọc file từ kho dữ liệu chung (bucket `documents`), giới hạn theo
  IP whitelist — xem mục "S3 cho hệ thống AI ngoài" bên dưới.

## 1. Cloudflare DNS

Thêm 4 record loại `A` trỏ về IP của server, **Proxy status: Proxied** (đám
mây cam), cho `web.green0tech.site`, `api.green0tech.site`,
`auth.green0tech.site`, `files.green0tech.site`.

Trong Cloudflare dashboard → SSL/TLS:
- **SSL/TLS encryption mode: Flexible** (origin không có cert).
- **Always Use HTTPS: On** (để mọi request tới đều là https dù origin trả
  link http, tránh cảnh báo mixed-content khi Keycloak redirect).

## 2. Build & upload image

Xem [`BUILD.md`](BUILD.md) — lệnh build/verify/upload cho `web` và
`backend`, đọc domain trực tiếp từ `stg/.env` để đảm bảo image push lên
khớp với domain thật sẽ chạy.

## 3. Deploy trên server

```bash
scp -r stg user@SERVER:/opt/aismartstore-stg   # hoặc rsync/git, tuỳ workflow
ssh user@SERVER
cd /opt/aismartstore-stg
cp .env.example .env
vim .env   # dien domain, secret, tag image that

docker compose up -d
docker compose ps
docker compose logs -f nginx backend web
```

Firewall server mở port 80 (nginx) + 22 (SSH) + **5672/15672 (RabbitMQ),
5432 (Postgres), 6333/6334 (Qdrant)** — các port raw TCP này chỉ allowlist
IP cần dùng, KHÔNG mở 0.0.0.0/0. Cả 5 đi thẳng ra ngoài, không qua
nginx/domain/Cloudflare (Cloudflare proxy không hỗ trợ TCP tuỳ ý trừ
Spectrum, tính phí riêng), nên firewall allowlist là lớp bảo vệ duy nhất —
không có WAF/rate-limit như web/api/auth/files (vốn qua Cloudflare). Nếu IP
truy cập không cố định, cân nhắc đổi service đó sang phục vụ qua domain +
nginx thay vì IP:port trần.

> **QUAN TRỌNG — đừng chỉ dùng `ufw`.** Đã verify thực tế: `ufw` KHÔNG lọc
> được các port do Docker `ports:` publish, vì Docker tự chèn rule NAT/FORWARD
> ở chain `DOCKER-USER`/`DOCKER`, đứng trước và bỏ qua chain `INPUT` mà `ufw`
> quản lý — bật `ufw allow`/`deny` cho các port này chỉ là ảo giác an toàn,
> IP ngoài whitelist vẫn connect được bình thường. Phải chèn rule thẳng vào
> chain `DOCKER-USER` (chain Docker đảm bảo luôn được tham chiếu trước khi
> rơi vào rule accept-all của chính nó):

```bash
# vi du them 1 IP moi cho ca 5 port - lap lai cho tung IP can them
IP=203.0.113.10
for port in 5672 15672 5432 6333 6334; do
  sudo iptables -I DOCKER-USER 1 -p tcp --dport $port -s $IP -j RETURN
done
# dam bao MOI port co 1 dong DROP cuoi cung (sau tat ca dong RETURN cua no) -
# neu port da co san dong DROP tu truoc thi KHONG can them nua, chi can
# chen dong RETURN moi vao TRUOC dong DROP do (dung "iptables -I DOCKER-USER <vi tri>"
# voi vi tri dung, xem "sudo iptables -L DOCKER-USER -n --line-numbers" truoc
# de biet DROP cua port do dang o dong nao).
sudo netfilter-persistent save   # luu lai, khong thi mat sau khi reboot server
```

Kiểm tra `sudo iptables -L DOCKER-USER -n --line-numbers` để xác nhận thứ tự
đúng: các dòng `RETURN` (allow) của 1 port phải nằm **trước** dòng `DROP` của
port đó — iptables khớp từ trên xuống, dòng nào khớp trước thắng.

`iptables-persistent`/`netfilter-persistent` **xung đột package với `ufw`** —
cài cái này thì apt tự gỡ `ufw`. Server hiện tại KHÔNG dùng `ufw`, chỉ dùng
`iptables` trực tiếp + `netfilter-persistent save` để rule sống qua reboot.
Đừng cài lại `ufw` (sẽ bị gỡ lần nữa, hoặc conflict cài đặt).

Không dùng `RABBITMQ_DEFAULT_USER`/`RABBITMQ_DEFAULT_PASS` (tài khoản admin)
cho bên ngoài — tạo user riêng, chỉ cấp quyền trên vhost/queue họ cần:

```bash
docker compose exec rabbitmq rabbitmqctl add_user external_producer 'STRONG_PASSWORD'
docker compose exec rabbitmq rabbitmqctl set_permissions -p / external_producer \
  '^(amq\.gen.*|external\.queue)$' '^(amq\.gen.*|external\.queue)$' '^(amq\.gen.*|external\.queue)$'
```

(chỉnh pattern queue/exchange cho khớp queue thật họ dùng — 3 regex theo thứ
tự là configure/write/read).

Các port debug còn lại (Redis, SeaweedFS port trực tiếp 8333) vẫn chỉ bind
`127.0.0.1` trong `docker-compose.yml` — vào bằng SSH tunnel, ví dụ:

```bash
ssh -L 5432:127.0.0.1:5432 user@SERVER   # Postgres
```

## RabbitMQ cho hệ thống AI ngoài (nhận job OCR/STT)

Đã tạo user riêng `ai-consumer` (2026-09-04, theo mẫu ở mục 3 phía trên) —
**chỉ quyền `read`**, giới hạn đúng 2 queue `q.ocr`/`q.stt` (không
`configure`/`write` — không tạo/xoá/publish được gì, kể cả trên chính 2 queue
đó), không có user tag nên **không** đăng nhập được vào RabbitMQ management
UI (`:15672`), chỉ dùng được qua AMQP (`:5672`):

```bash
docker compose exec rabbitmq rabbitmqctl list_permissions -p /
# ai-consumer   ^$   ^$   ^(q\.ocr|q\.stt)$
```

- **Host/port**: `<IP server>:5672` (raw AMQP, không qua domain/Cloudflare —
  xem whitelist IP ở mục 3 phía trên, đã mở sẵn cho `119.17.198.75`/`.77`).
- **Virtual host**: `/`.
- **Username**: `ai-consumer` — **Password**: gửi riêng cho đội AI qua kênh
  an toàn (không gửi qua chat/email thường), không ghi vào file này.
- **Queue**: `q.ocr` (ảnh + PDF), `q.stt` (audio đã chuẩn hoá WAV 16-bit
  PCM) — đọc theo đúng hợp đồng message 5 trường ở
  `digitization-pipeline-01-source-dispatch.md` §4.4 (`fileId`, `sourceId`,
  `filePath`, `originalFileType`, `processFileType`). `filePath` là
  `<bucket>/<objectKey>` — dùng chung với S3 identity `external-ai` bên dưới
  để tải file thật.

**Đã verify thực tế** (không chỉ đọc code): connect bằng `pika` với đúng
user/pass trên, `basic_get` từ `q.ocr` nhận đúng message backend đã publish,
thử `queue_declare` (hành động cần quyền `configure`) bị broker từ chối như
kỳ vọng.

Đội AI **tự đọc lại `.retry`/`.dlq`** của queue mình nếu cần requeue/xem lỗi
(quyền `read` đã khớp cả `q.ocr`/`q.stt` gốc; nếu cần đọc thêm
`q.ocr.retry`/`q.ocr.dlq`/`q.stt.retry`/`q.stt.dlq`, mở rộng regex `read` ở
`set_permissions` cho khớp — hiện tại **chưa** cấp, vì Pha 1 (backend) không
tự requeue vào các queue này, đó là việc của AI worker tiêu thụ `.retry`/
`.dlq` theo đúng khung đã chốt ở `digitization-pipeline.md` Quyết định 2).

## S3 cho hệ thống AI ngoài (stream file từ kho dữ liệu chung)

Hệ thống AI ngoài đọc file qua S3 API chuẩn (tương thích boto3/aws-sdk/rclone
...), **read-only**, chỉ scope bucket `documents` (kho dữ liệu chung,
immutable/WORM theo NFR-09) — không đụng được bucket `personal`, không
Write/Delete/Admin. Xem identity `external-ai` trong
`config/seaweedfs/s3.json`.

> **Image SeaweedFS đã bump lên `4.44`** (từ `3.97`) — bản `3.97` có bug xác
> nhận thực tế: mọi identity không phải `Admin` đều bị `AccessDenied` cho dù
> action đúng cú pháp (`Read`, `List`, kể cả scope theo bucket
> `Read:documents`). Xem comment tại service `seaweedfs` trong
> `docker-compose.yml`. Nếu dựng thêm identity mới, nhớ kiểm tra vẫn hoạt
> động đúng — đừng chỉ tin vào việc `docker compose up` không báo lỗi.

- **Endpoint**: `https://files.green0tech.site` (path-style, vd
  `GET /documents/<key>` — không dùng virtual-hosted style
  `documents.files.green0tech.site`).
- **Access key / Secret key**: xem `config/seaweedfs/s3.json` (identity
  `external-ai`) — gửi riêng cho đội AI qua kênh an toàn, không gửi qua chat/email thường.
- **Region**: giá trị bất kỳ cũng được (SeaweedFS không strict theo region
  AWS), có thể dùng `us-east-1` cho khớp SDK mặc định.

**curl mẫu cho đội AI** (cần curl >= 7.75, co san `--aws-sigv4`; kiem tra
bang `curl --version`):

```bash
# Liet ke file trong kho (list toan bo bucket documents)
curl --aws-sigv4 "aws:amz:us-east-1:s3" \
  --user "ai-ef36d4311bdd4850:ai9ugWjymcp0WTGoJ8wu7EhktWj43sWjD3VdNXqG" \
  "https://files.green0tech.site/documents/"

# Tai 1 file cu the ve, thay <OBJECT_KEY> bang key that (vd lay tu ket qua
# list o tren, dang "documents/<uuid>")
curl --aws-sigv4 "aws:amz:us-east-1:s3" \
  --user "ai-ef36d4311bdd4850:ai9ugWjymcp0WTGoJ8wu7EhktWj43sWjD3VdNXqG" \
  -o output_file \
  "https://files.green0tech.site/<OBJECT_KEY>"

# Stream theo Range (doc tung phan, khong tai het file mot luc - vd file lon)
curl --aws-sigv4 "aws:amz:us-east-1:s3" \
  --user "ai-ef36d4311bdd4850:ai9ugWjymcp0WTGoJ8wu7EhktWj43sWjD3VdNXqG" \
  -H "Range: bytes=0-1048575" \
  "https://files.green0tech.site/<OBJECT_KEY>" -o chunk1
```

Lưu ý: `<OBJECT_KEY>` đã bao gồm sẵn tiền tố `documents/` (vd
`documents/3fa85f64-....pdf`) — không lặp lại `documents/documents/...`.
Nếu curl máy đội AI cũ hơn 7.75 (không có `--aws-sigv4`) — vd đã gặp thực tế
trên A100-Server, curl 7.68 — dùng SDK ngôn ngữ nào cũng được, curl chỉ là
tool test tay, không liên quan gì đến code thật. **Python: dùng `boto3`**
(`pip install boto3`), tự ký SigV4 hoàn toàn độc lập với curl hệ thống:

```python
import boto3
from botocore.config import Config

s3 = boto3.client(
    "s3",
    endpoint_url="https://files.green0tech.site",
    aws_access_key_id="ai-ef36d4311bdd4850",
    aws_secret_access_key="ai9ugWjymcp0WTGoJ8wu7EhktWj43sWjD3VdNXqG",
    region_name="us-east-1",
    config=Config(s3={"addressing_style": "path"}),  # BAT BUOC - SeaweedFS
                                                        # chi ho tro path-style,
                                                        # thieu dong nay boto3 co
                                                        # the tu chon virtual-hosted-
                                                        # style va bi loi.
)

# Liet ke file trong kho
resp = s3.list_objects_v2(Bucket="documents")
for obj in resp.get("Contents", []):
    print(obj["Key"], obj["Size"])

# Stream 1 file ve (khong load het vao RAM cung luc)
obj = s3.get_object(Bucket="documents", Key="<OBJECT_KEY>")
with open("output_file", "wb") as f:
    for chunk in obj["Body"].iter_chunks(chunk_size=1024 * 1024):
        f.write(chunk)
```

**Giới hạn phạm vi quan trọng**: object key trong bucket `documents` là
phẳng (`documents/<uuid>`), KHÔNG phản ánh cây thư mục admin thấy trên UI
(`WarehouseFolder`) — key này đọc được **toàn bộ** bucket, không giới hạn
được theo từng thư mục ở tầng S3. Nếu sau này cần giới hạn theo thư mục, phải
làm qua 1 API riêng của backend, không phải truy cập S3 trực tiếp.

**Whitelist IP** (`nginx/conf.d/files.conf`, dòng `allow`/`deny`) — hiện đang
allow `119.17.198.75` và `172.18.104.104` (IP hệ thống AI) + 1 IP tạm dùng
lúc setup/test. Thêm IP mới (sửa file rồi):

```bash
docker compose exec nginx nginx -t && docker compose exec nginx nginx -s reload
```

> `172.18.104.104` là IP **private** (dải `172.18.x.x`, không route được qua
> Internet công khai) — nếu server A100 đứng sau NAT/jump host
> `119.17.198.75`, traffic thật tới đây rất có thể xuất hiện với source IP là
> `119.17.198.75` (IP của jump host), không phải `172.18.104.104`. Rule cho
> `172.18.104.104` gần như chắc chắn sẽ không bao giờ khớp traffic thật —
> giữ lại cũng không hại gì, nhưng đừng ngạc nhiên nếu A100 gọi được hoàn
> toàn nhờ rule `119.17.198.75`, không phải rule kia.

Lưu ý: whitelist này **chỉ hoạt động đúng vì đã cấu hình `real_ip_header
CF-Connecting-IP`** trong `nginx/conf.d/00-common.conf` (dải IP Cloudflare) —
thiếu phần này thì `$remote_addr` luôn là IP edge của Cloudflare (giống
nhau cho mọi client), làm allowlist theo IP vô nghĩa.

## OpenSearch cho hệ thống AI ngoài

Từ 2026-09-16, OpenSearch bật security plugin thật (trước đó
`plugins.security.disabled=true` — không có auth) + mở port `9200` ra
`0.0.0.0`, giới hạn bằng cùng firewall allowlist với Postgres/Qdrant/RabbitMQ
(mục 3 phía trên). `backend` đã sửa code (`OpenSearchClientConfig.java`,
`OpenSearchProperties.java`) để dùng HTTPS + Basic Auth thay vì HTTP trần —
đã build lại image, deploy, verify auth enforce đúng (401 khi không auth,
200 khi đúng user/pass) và `backend` connect lại được bình thường trước khi
đổi.

- **Host/port**: `<IP server>:9200`, **scheme: HTTPS** (đổi từ HTTP).
- **Cert**: self-signed (demo cert đi kèm OpenSearch khi bật security) —
  **không có CA công khai**, client phải bỏ qua verify cert
  (`verify_certs=False` / `-k` / trust-all), giống hệt cách `backend` tự làm
  trong code (xem comment trong `OpenSearchClientConfig.java` — chủ đích,
  không phải thiếu sót, vì đây là service nội bộ không qua domain/Cloudflare).
- **Username**: `admin` — **Password**: `OPENSEARCH_INITIAL_ADMIN_PASSWORD`
  trong `.env` — gửi riêng cho đội AI qua kênh an toàn, không gửi qua
  chat/email thường.

Test nhanh:
```bash
curl -sk -u admin:<password> https://<IP server>:9200/_cluster/health
```

Python (`opensearch-py`):
```python
from opensearchpy import OpenSearch

client = OpenSearch(
    hosts=[{"host": "<IP server>", "port": 9200}],
    http_auth=("admin", "<password>"),
    use_ssl=True,
    verify_certs=False,
)
print(client.cluster.health())
```

**Chưa có UI (OpenSearch Dashboards)** — stack chỉ có REST API. Đội AI muốn
xem trực quan thì tự chạy Dashboards ở máy họ, trỏ
`OPENSEARCH_HOSTS=https://<IP server>:9200` (không cần deploy thêm gì trên
server, Dashboards chỉ là client).

## 4. Cập nhật lên version mới

```bash
# build + upload image mới (bước 2), tag mới hoặc đè tag :stg
ssh user@SERVER 'cd /opt/aismartstore-stg && docker compose up -d web backend'
```

`docker compose up -d web backend` chỉ recreate 2 service dùng image mới,
không đụng tới Postgres/Keycloak/dữ liệu.

Không cần tự `nginx -s reload` sau lệnh trên: `nginx/conf.d/00-common.conf`
đã khai bao `resolver 127.0.0.11 valid=10s;` + moi `proxy_pass` dung bien
(`set $upstream_xxx ...; proxy_pass http://$upstream_xxx;`) de nginx tu
resolve lai IP container trong ~10s, khong bi cache IP cu gay 502 sau khi
recreate (bug thuc te da gap khi test). Chi can `nginx -s reload` tay khi
**tu doi noi dung file trong `nginx/conf.d/`** (vd whitelist IP, them domain
moi) — do la thay doi config, khong phai IP container.

## Ghi chú

- `config/` là bản copy các file hạ tầng từ thư mục gốc source_v2 (`keycloak/`, `postgres/`, `seaweedfs/`, `clamav/`; realm
  Keycloak, init script Postgres, config SeaweedFS S3, ClamAV) — nếu sửa gốc
  ở thư mục gốc, nhớ đồng bộ lại đây trước khi deploy.
- Đổi `realm-export.json` chỉ có tác dụng lần import đầu (`docker compose
  down -v` để Keycloak import lại, hoặc sửa tay qua Admin Console).
- `config/seaweedfs/s3.json` là file JSON tĩnh, không đọc biến `.env` — nếu
  đổi `S3_ACCESS_KEY_ID`/`S3_SECRET_ACCESS_KEY` trong `.env` phải sửa cả file
  này cho khớp.
