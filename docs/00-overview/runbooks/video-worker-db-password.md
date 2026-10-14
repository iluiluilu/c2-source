# Runbook: đặt/rotate mật khẩu role `digitization_video_worker`

> File: `docs/00-overview/runbooks/video-worker-db-password.md` — Owner: Ops/DEV
> Liên quan: `docs/03-dev/specs/digitization-pipeline-03-video-queue.md` mục
> 2.4/6 (Task 1b), `docs/01-sa/architecture/digitization-pipeline.md` Quyết
> định 8.

> ⚠️ **2026-09-24:** worker video nay hoàn tất run như ocr/stt (`q.result`
> hoặc runbook `ocr-stt-worker-direct-update-cau-tam.md`) — xem
> `docs/03-dev/specs/digitization-pipeline-05-video-review.md`. Role
> `digitization_video_worker` không còn cần cho luồng mới; giữ lại đến khi
> worker chuyển xong rồi thu hồi.

## Vì sao có runbook riêng, không đặt trong migration

Migration `V31__digitization_video_task.sql` chỉ `CREATE ROLE
digitization_video_worker LOGIN;` — **không** kèm mật khẩu. Lý do:

- Cú pháp biến của `psql` (`:'var'`) không chạy được qua Flyway (Flyway dùng
  JDBC, không phải psql client).
- Kể cả dùng đúng cú pháp Flyway placeholder (`${...}`), Flyway vẫn lưu
  checksum nội dung migration đã áp dụng — đổi/rotate mật khẩu sau này sẽ làm
  checksum lệch, khiến Flyway fail ở lần deploy kế tiếp.

=> Đặt/rotate mật khẩu là một thao tác **vận hành, ngoài Flyway**, chạy runbook
này mỗi khi cần (lần đầu tạo môi trường, hoặc rotate định kỳ).

## Khi nào chạy

- Ngay sau khi migration `V31` chạy xong lần đầu ở một môi trường (dev/stg/prod).
- Mỗi khi rotate mật khẩu định kỳ theo chính sách bảo mật của dự án.
- Khi nghi ngờ mật khẩu bị lộ.

## Các bước

1. **Sinh mật khẩu mạnh:**
   ```bash
   openssl rand -base64 24
   ```

2. **Gán mật khẩu cho role** (chạy trực tiếp trên Postgres, dùng kết nối có
   quyền `ALTER ROLE` — thường là user superuser/admin của DB, **không** phải
   role `digitization_video_worker` tự đổi mật khẩu của chính nó):
   ```bash
   psql "$DATABASE_URL" -c \
     "ALTER ROLE digitization_video_worker WITH PASSWORD '<mật khẩu vừa sinh>';"
   ```
   Lệnh này chạy lại được nhiều lần (idempotent) — không ảnh hưởng
   `flyway_schema_history`, không có checksum nào bị đổi.

3. **Lưu mật khẩu như một secret**, cùng nơi dự án đang lưu
   `POSTGRES_PASSWORD`/`RABBITMQ_DEFAULT_PASS` (ví dụ `stg/.env` cho staging,
   secret manager cho production — theo đúng cơ chế hiện có của dự án, không
   tạo cơ chế mới).

4. **Đưa mật khẩu vào cấu hình của video worker** qua biến
   `VIDEO_WORKER_DB_PASSWORD`. Lưu ý: đây là cấu hình của **video worker**
   (service/tiến trình riêng, ngoài backend Spring Boot) — không đặt vào
   `application.yml`/env của `backend`.

5. **Thêm bước 2 vào script/runbook deploy hiện có của dự án** (chỗ đang seed
   `POSTGRES_PASSWORD`/`RABBITMQ_DEFAULT_PASS` khi dựng môi trường mới), để
   không bị quên khi tạo môi trường dev/stg/prod mới sau này.

## Kiểm tra sau khi chạy

```bash
psql "postgresql://digitization_video_worker:<mật khẩu>@<host>:<port>/<db>" \
  -c "UPDATE digitization_run SET updated_at = now() WHERE false;" # dry-run cú pháp, không đổi dữ liệu
```
Kết nối thành công (không báo lỗi auth) = mật khẩu đã đúng. Không cần thêm
kiểm tra quyền ở đây — quyền `GRANT` đã được test ở integration test của
`digitization-pipeline-03-video-queue.md` mục 5.
