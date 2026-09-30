# Build & push image — web + backend

Chạy trên máy build local (không phải server), ở gốc repo `source_v2/`.
Tất cả lệnh dưới đây đọc domain/tag từ `stg/.env` — không hardcode domain
tay, tránh build nhầm domain khác với domain thật sự chạy trên server.

**Cách nhanh:** `./build-and-push.sh` (xem [build-and-push.sh](build-and-push.sh))
gộp toàn bộ bước 1-4 bên dưới thành 1 lệnh — build cả 2 image, verify domain,
rồi push thẳng lên server qua SSH (`docker save | ssh | docker load`, mặc
định host `linhhost`). Các bước chi tiết dưới đây dùng khi cần chạy tay/debug
từng bước, hoặc build xong muốn push theo cách khác (registry).

## 0. Load biến từ .env

```bash
cd stg
[ -f .env ] || cp .env.example .env   # neu chua co, dien domain/secret that truoc
set -a; source .env; set +a
cd ..   # ve goc repo source_v2/, cac lenh docker build ben duoi chay tu day
```

## 1. Build image `web`

`NEXT_PUBLIC_*` bị Next.js "bake" cứng vào bundle **lúc build**, không đọc
được ở runtime — đây là chỗ hay sai nhất: build thiếu `--build-arg` hoặc
build-arg trỏ nhầm domain (vd còn `localhost`) thì web chạy trên server vẫn
gọi nhầm API, phải build lại image mới sửa được (sửa `.env` trên server
không có tác dụng với 2 biến này).

```bash
docker build -t "$WEB_IMAGE" \
  --build-arg NEXT_PUBLIC_KEYCLOAK_ISSUER="http://${AUTH_DOMAIN}/realms/${KEYCLOAK_REALM}" \
  --build-arg NEXT_PUBLIC_API_BASE_URL="https://${API_DOMAIN}" \
  ./web
```

## 2. Build image `backend`

Backend đọc `KEYCLOAK_AUTH_SERVER_URL`, `S3_*`, `DB_*`, ... ở **runtime**
(qua `environment:` trong `docker-compose.yml`), không có build-arg nào gắn
với domain — build bình thường, domain được set khi `docker compose up` trên
server.

```bash
docker build -t "$API_IMAGE" ./backend
```

## 3. Verify domain đã bake đúng vào image `web`

Kiểm tra ngay trên máy build, trước khi upload — rẻ hơn nhiều so với phát
hiện ở browser sau khi đã deploy:

```bash
docker run --rm "$WEB_IMAGE" sh -c "grep -rl \"$API_DOMAIN\" .next/static 2>/dev/null | head -1"
```

Có output (đường dẫn file `.js`) = build-arg đã vào bundle đúng domain.
Không có output = build-arg sai/thiếu hoặc build dùng cache cũ — build lại
với `--no-cache` rồi kiểm tra lại:

```bash
docker build --no-cache -t "$WEB_IMAGE" \
  --build-arg NEXT_PUBLIC_KEYCLOAK_ISSUER="http://${AUTH_DOMAIN}/realms/${KEYCLOAK_REALM}" \
  --build-arg NEXT_PUBLIC_API_BASE_URL="https://${API_DOMAIN}" \
  ./web
```

## 4. Upload lên server

```bash
# Cach A: docker save/scp/load - khong can registry
docker save "$WEB_IMAGE" "$API_IMAGE" | gzip | \
  ssh user@SERVER 'gunzip | docker load'

# Cach B: push len registry rieng roi pull tren server
docker tag "$WEB_IMAGE" registry.example.com/aismartstore-web:stg
docker push registry.example.com/aismartstore-web:stg
docker tag "$API_IMAGE" registry.example.com/aismartstore-api:stg
docker push registry.example.com/aismartstore-api:stg
# ... roi sua WEB_IMAGE/API_IMAGE trong stg/.env tren server cho khop tag/registry
```

## 5. Verify domain hoạt động thật sau khi deploy trên server

Chạy sau `docker compose up -d` trên server (xem `README.md` mục 3):

```bash
# web tra ve trang (200/3xx)
curl -sI "https://${WEB_DOMAIN}" | head -1

# api len
curl -sI "https://${API_DOMAIN}" | head -1

# issuer cua keycloak phai KHOP TUYET DOI voi KEYCLOAK_AUTH_SERVER_URL/realms
# ma backend dang dung (xem docker-compose.yml) - lech 1 ky tu (vd thieu/thua
# dau /, sai scheme http/https) la login se loi "issuer mismatch"
curl -s "http://${AUTH_DOMAIN}/realms/${KEYCLOAK_REALM}/.well-known/openid-configuration" \
  | grep -o '"issuer":"[^"]*"'
# ky vong: "issuer":"http://auth.green0tech.site/realms/aismartstore"
```

## Khi đổi domain

1. Sửa domain trong `stg/.env`.
2. Build lại **image `web`** (bước 1) — bắt buộc, vì domain đã bake cứng.
3. `backend` không cần build lại, chỉ cần `docker compose up -d` lại trên
   server để đọc `.env` mới (xem README.md mục 4).
