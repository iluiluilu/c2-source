# User Story: backend-multi-instance

> File: `docs/02-ba/stories/backend-multi-instance.md` — Owner: BA
> Nguồn kiến trúc: `docs/01-sa/architecture/backend-multi-instance.md` (ARCH-backend-multi-instance, Status: Approved)
> Status: Approved
> Story ID: `STORY-backend-multi-instance`
> Priority: Must
> Estimate: L

## User Story

**As a** vận hành viên hệ thống (và qua đó là người dùng cuối tải/tìm kiếm tài liệu)
**I want** chạy nhiều instance API cùng một worker nền (cùng image) trên cùng một host docker compose
**So that** hệ thống chịu tải tốt hơn, tải nền (embed, job định kỳ) không làm chậm API, và không có tác động phụ do chạy trùng (upload hỏng, gửi trùng OCR/STT, cảnh báo trùng).

## Acceptance Criteria

Mỗi AC có ID để DEV/QA tham chiếu ngược (spec & report bám theo `AC-n`).

### A. Mô hình role API / worker (ADR-1)

- [ ] **AC-1 (mặc định all-in-one):**
  Given không đặt profile và không đặt các cờ mới (dev local, `mvn verify`)
  When backend khởi động
  Then `@Scheduled`, listener `q.embed`, listener digitization result/heartbeat đều chạy như hiện tại và toàn bộ IT hiện có vẫn pass.
- [ ] **AC-2 (profile `api`):**
  Given `SPRING_PROFILES_ACTIVE=api` (`APP_SCHEDULING_ENABLED`, `SEMANTIC_CONSUMER_ENABLED`, `APP_CONSUMERS_ENABLED` = false)
  When instance khởi động
  Then không job `@Scheduled` nào chạy, không consumer RabbitMQ nào đăng ký, API vẫn phục vụ bình thường (kể cả endpoint semantic khi `SEMANTIC_ENABLED=true`).
- [ ] **AC-3 (profile `worker`):**
  Given `SPRING_PROFILES_ACTIVE=worker`
  When instance khởi động
  Then job `@Scheduled`, consumer `q.embed`, consumer digitization result và heartbeat đều chạy; heartbeat consumer và `DigitizationRunLeaseReconciliationJob` luôn cùng role.
- [ ] **AC-4 (cổng semantic):**
  Given `SEMANTIC_ENABLED=false`
  When bất kỳ role nào chạy
  Then endpoint semantic trả 404, không producer/consumer/reconciliation semantic (hành vi hiện tại giữ nguyên); `SEMANTIC_ENABLED` được đặt giống nhau ở API và worker.
- [ ] **AC-5 (log role):**
  Given một instance khởi động
  When xem log
  Then có dòng ghi rõ `role` (api/worker) và mỗi dòng log mang `role` + instance id.

### B. Khoá job singleton (ADR-2)

- [ ] **AC-6 (dispatch retry):**
  Given 2 instance cùng chạy `DigitizationDispatchRetryJob` (cấu hình sai hoặc scale nhầm worker)
  When đến chu kỳ `retryPendingDispatch` và `rescueMissedDocuments`
  Then mỗi source PENDING được publish tối đa 1 lần/chu kỳ trên toàn hệ thống (không gửi trùng OCR/STT sang GPU).
- [ ] **AC-7 (nhóm S có khoá):**
  Given các job `SemanticIndexReconciliationJob`, `SearchIndexReconciliationJob`, `DocumentDigitizationStatusReconciliationJob`, `DigitizationDlqAlertJob`, `UploadCleanupJob`, `OutboxRetentionJob`, `NotificationService` retention, `ReportExportCleanupJob`
  When nhiều instance cùng đến lượt chạy
  Then tại một thời điểm chỉ một bản chạy; bản bị bỏ lượt ghi log/metric "bỏ lượt do khoá"; có dòng tương ứng trong bảng `shedlock`; `lockAtMostFor` ≥ 3 × thời lượng tick tối đa đo trên stg và không nhỏ hơn chu kỳ của job (giá trị cụ thể từng job do DEV đo, ghi trong spec).
- [ ] **AC-8 (nhóm C không đổi):**
  Given AutoConfirm, `ReviewStaleAlertJob` (UPDATE có điều kiện đặt `stale_alerted` + audit cùng transaction = claim), `DigitizationRunLeaseReconciliationJob`, `ReportExportJobRunner`, Audit*Job, TrashPurge, PurgeStuckRecovery, PersonalIngestCleanup
  When chạy ở nhiều instance
  Then cơ chế khoá hiện có giữ nguyên hành vi và được DEV xác nhận lại từng job (kết quả ghi trong spec/test).
- [ ] **AC-9 (reconciliation semantic không embed lại không cần thiết):**
  Given tài liệu đã có yêu cầu embed trong vòng cửa sổ ân hạn (mặc định 60 phút, cấu hình qua env, ví dụ `SEMANTIC_RECONCILIATION_GRACE_MS`)
  When `SemanticIndexReconciliationJob` chạy (kể cả chỉ 1 instance)
  Then tài liệu đó bị bỏ qua, không phát `embed.requested` mới; quá cửa sổ ân hạn mới được yêu cầu lại. Dấu thời điểm yêu cầu embed lưu ở **cột DB** (vd. `embed_requested_at`, migration Flyway), cập nhật mỗi lần publish `embed.requested` (từ `SearchIndexOutboxPoller` và `SemanticIndexReconciliationJob`).

### C. Upload chunk dùng volume chung (ADR-3)

- [ ] **AC-10 (chunk qua nhiều instance):**
  Given một phiên upload chia chunk và N API replica sau nginx round-robin
  When các chunk `init`/append/`complete` được định tuyến tới các instance khác nhau
  Then 0 chunk hỏng; tệp hoàn chỉnh khớp hash/kích thước; mã lỗi `CHUNK_OFFSET_MISMATCH` và các hợp đồng API khác giữ nguyên.
- [ ] **AC-11 (thư mục tạm cấu hình được):**
  Given biến `UPLOAD_TEMP_DIR` (`app.upload.temp-dir`) trỏ tới volume `upload-tmp`
  When tạo tệp chunk
  Then tệp nằm trong thư mục đó; volume được mount vào cả `backend` và `backend-worker`; không mount vào service khác và không dùng bucket `documents`.
- [ ] **AC-12 (sống qua deploy):**
  Given một phiên upload OPEN được tạo sau khi đã chuyển sang volume chung
  When container backend bị thay thế (deploy)
  Then phiên tiếp tục upload và `complete` thành công.
- [ ] **AC-13 (dọn tệp tạm):**
  Given phiên upload hết hạn (24h)
  When `UploadCleanupJob` chạy ở worker
  Then tệp tạm trên volume và phiên bị dọn.
- [ ] **AC-14 (phiên cũ lúc chuyển đổi):**
  Given phiên OPEN có `temp_file_path` trỏ `/tmp` của container cũ tại thời điểm chuyển đổi
  When client tiếp tục upload
  Then nhận lỗi để tải lại (chấp nhận hỏng một lần, không di chuyển tệp); phiên hết hạn và được dọn sau 24h.
- [ ] **AC-15 (giám sát dung lượng):**
  Given volume `upload-tmp`
  When vận hành cần theo dõi
  Then có cảnh báo khi volume dùng > 80% (tài liệu hoá trong runbook/compose); không thêm giới hạn số phiên OPEN/người dùng trong story này; tệp tạm khác (single upload, report, audit export) vẫn dùng `/tmp`.

### D. Thứ tự outbox và ghi vector (ADR-5)

- [ ] **AC-16 (poller single-active):**
  Given `SearchIndexOutboxPoller` và nhiều instance
  When đến tick
  Then chỉ một instance (worker) giữ advisory lock phiên suốt tick; instance khác bỏ lượt; lock tự nhả khi mất kết nối; thứ tự sự kiện theo tài liệu giữ như hiện tại; độ trễ lexical ≤ 5s, vector ≤ 60s.
- [ ] **AC-17 (ghi vector có điều kiện):**
  Given 2 message embed cùng tài liệu xử lý song song
  When ghi vector vào index `segment`
  Then vector cũ không ghi đè vector mới: message embed mang **hash `text_embed`**, khi ghi so với hash đang lưu trên segment, khác thì bỏ ghi (update có điều kiện/noop); điều kiện này có trước khi tăng `embed-consumer-concurrency` > 1.

### E. Giới hạn toàn cục (ADR-6)

- [ ] **AC-18 (semantic query theo instance):**
  Given ngân sách toàn cục 8 và 2 API replica
  When cấu hình compose
  Then `SEMANTIC_QUERY_CONCURRENCY=4` mỗi replica, có ghi chú quy ước "ngân sách / số replica" trong compose.
- [ ] **AC-19 (rate limit tên qua Redis):**
  Given `NameCheckRateLimiter` và N API replica
  When một người dùng `(sub, endpoint)` gọi vượt ngưỡng trong cửa sổ
  Then giới hạn được tính toàn cục qua Redis (INCR + EXPIRE), không nhân N theo số replica.
- [ ] **AC-20 (Redis lỗi fail-open):**
  Given Redis không khả dụng
  When gọi kiểm tra tên
  Then request không bị chặn vì hạ tầng phụ; limiter rơi về bộ nhớ cục bộ và ghi log WARN.

### F. Topology compose, health, shutdown, deploy (ADR-7)

- [ ] **AC-21 (topology):**
  Given compose stg và prod
  When triển khai
  Then có `backend` (profile `api`, 2 replica, không publish port) và `backend-worker` (profile `worker`, 1 replica, không nhận traffic nginx); dùng YAML anchor cho env hạ tầng; `restart: unless-stopped`.
- [ ] **AC-22 (tài nguyên):**
  Given ngân sách ADR-7
  When cấu hình
  Then prod: api 2×(3GB/4 CPU) + worker (4GB/4 CPU) = 10GB/12 CPU; stg: api 2×1GB + worker 1.5GB; `-XX:MaxRAMPercentage=65`, không dùng `-Xmx` cứng.
- [ ] **AC-23 (nginx cân bằng tải):**
  Given nhiều replica `backend`
  When nginx định tuyến
  Then dùng `resolver 127.0.0.11 valid=10s` + biến upstream để round-robin theo A record, không sticky; có `proxy_next_upstream error timeout` cho request idempotent.
- [ ] **AC-24 (health/readiness):**
  Given Actuator chỉ expose `health` (liveness, readiness) trên management port riêng `3002` (`management.server.port`)
  When truy cập từ bên ngoài qua nginx
  Then không truy cập được; Docker `HEALTHCHECK` dùng readiness qua `wget -qO- http://localhost:3002/actuator/health/readiness` (busybox wget có sẵn trong image alpine, không cài curl); readiness của API chỉ UP sau khi Flyway xong và DB kết nối được.
- [ ] **AC-25 (graceful shutdown):**
  Given `server.shutdown=graceful` và `stop_grace_period: 330s` (nginx timeout 300s + 30s dư, cho request dài nhất chunk/complete 2GB)
  When container được dừng
  Then request đang chạy hoàn tất, listener RabbitMQ dừng nhận trước và message chưa ack trả lại queue.
- [ ] **AC-26 (worker chết):**
  Given worker bị dừng
  When message tồn ở `q.embed`/result
  Then message không mất (durable) và được xử lý sau khi worker dựng lại; có cảnh báo/giám sát tồn queue.
- [ ] **AC-27 (queue RabbitMQ khớp):**
  Given API và worker dùng chung image/cấu hình queue
  When cả hai khởi động
  Then không có lỗi 406 do lệch tham số queue.
- [ ] **AC-28 (quy trình deploy):**
  Given deploy trong khung bảo trì
  When thực hiện theo `production-deployment.md` (`docker compose up -d backend backend-worker web`)
  Then dịch vụ trở lại bình thường; tài liệu deploy stg (`reference_stg_backend_deploy`) và prod được cập nhật cho service `backend-worker` và volume `upload-tmp`.

## Ngoài phạm vi (Out of scope)

- Tách repo/service riêng cho embed (chỉ giữ tiêu chí ADR-4); service compose thứ 3 "embed-worker" chưa làm.
- HA đa máy chủ, K8s, S3 staging cho upload chunk (ADR-3 phương án B), cluster OpenSearch/Postgres.
- Zero-downtime/rolling deploy (Q2 chấp nhận khung bảo trì).
- Poller đa instance serialize theo `document_id` (ADR-5 phương án B).
- Giới hạn số phiên upload OPEN đồng thời theo người dùng (chỉ giám sát dung lượng volume).
- Semaphore phân tán Redis cho `SemanticQueryLimiter`.
- Thay đổi hợp đồng API cho web.
- Di chuyển tệp tạm của phiên upload cũ.

## Phụ thuộc (Dependencies)

- Story/feature khác phải xong trước: không bắt buộc; liên quan `search-semantic` (q.embed, `SEMANTIC_ENABLED`) và `production-deployment.md`, `hardware-resource-allocation.md` §4.1.
- Hạ tầng sẵn có: ShedLock JDBC, `AuditAdvisoryLock`, Redis, RabbitMQ.
- Tham chiếu quyết định kiến trúc liên quan: ADR-1 đến ADR-7 trong ARCH-backend-multi-instance.

## Câu hỏi mở cho DEV

- [x] Đo thời lượng tick tối đa thực tế của từng job nhóm S để đặt `lockAtMostFor` (ADR-2 `[ĐX]`). → **Chốt:** quy tắc `lockAtMostFor` ≥ 3 × tick tối đa đo trên stg, không nhỏ hơn chu kỳ job; DEV đo và ghi giá trị vào spec (cập nhật: AC-7)
- [x] Cơ chế ghi nhận "đã yêu cầu embed gần đây" (cột DB hay key Redis TTL) cho AC-9. → **Chốt:** cột DB (vd. `embed_requested_at`), cập nhật mỗi lần publish `embed.requested` (cập nhật: AC-9)
- [x] Cơ chế ghi vector có điều kiện cho AC-17 (hash `text_embed` hay version segment). → **Chốt:** hash `text_embed`, bỏ ghi nếu hash trên segment khác (cập nhật: AC-17)
- [x] Giá trị `stop_grace_period` phù hợp (AC-25) và số phiên OPEN đồng thời tối đa/ngưỡng cảnh báo dung lượng volume (AC-15, `[ĐX]`). → **Chốt:** `stop_grace_period: 330s`; cảnh báo volume > 80%; không giới hạn phiên OPEN/người dùng (cập nhật: AC-15, AC-25, Ngoài phạm vi)
- [x] Management port cụ thể (gợi ý 3002) và cách `HEALTHCHECK` gọi readiness trong image hiện tại. → **Chốt:** port 3002, `HEALTHCHECK` dùng busybox `wget` gọi `/actuator/health/readiness` (cập nhật: AC-24)
- [x] `ReviewStaleAlertJob`: tách phần gửi cảnh báo khỏi phần claim để khoá đúng phạm vi (AC-7). → **Chốt:** không tách; job đã là claim (UPDATE có điều kiện `stale_alerted` + audit cùng transaction) ⇒ chuyển sang nhóm C (cập nhật: AC-7, AC-8)
