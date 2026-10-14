# QA Report: backend-multi-instance

> File: `docs/04-qa/reports/backend-multi-instance.md` — Owner: QA
> Nguồn story: `docs/02-ba/stories/backend-multi-instance.md` (STORY-backend-multi-instance)
> Nguồn spec: `docs/03-dev/specs/backend-multi-instance.md` (SPEC-backend-multi-instance)
> Ngày review: 2026-10-12
> Commit/branch reviewed: `main` @ 8a24890 + working tree chưa commit (thay đổi của story này nằm lẫn với search-semantic, export-reporting, v.v. trong cùng working tree)

## Kết luận

**PASS WITH ISSUES**

Code đáp ứng hầu hết AC; `mvn -o verify` xanh toàn bộ (1197 unit + 530 IT, 0 fail/err/skip), gồm cả các IT mới của story. Không phát hiện lỗi code chặn. Còn 1 hạng mục chưa đạt đúng chữ AC: **AC-7** (giá trị `lockAtMostFor` chưa được đo trên stg, Task 6 chưa làm, spec §6.1 còn `_đo_`). Đây là cổng phát hành bắt buộc trước khi lên prod. Ngoài ra thiếu một số IT trong test plan và chưa kiểm chứng trên stg (AC-10/12/21..28 phần runtime).

## Đối chiếu Acceptance Criteria

| AC | Kết quả | Bằng chứng (file:line / test / log) |
|---|---|---|
| AC-1 | Pass | `config/SchedulingConfig.java` (`@ConditionalOnProperty matchIfMissing=true`), `@EnableScheduling` đã gỡ khỏi `BackendApplication`. `RoleFlagsDefaultIT` + toàn bộ 530 IT cũ xanh. Log `role=all ... messagingConsumers=true`. |
| AC-2 | Pass | `application-api.yml` (3 cờ = false); `application.yml` map env `APP_SCHEDULING_ENABLED`, `APP_CONSUMERS_ENABLED`, `SEMANTIC_CONSUMER_ENABLED`. `RoleFlagsApiIT`, `RoleFlagsEnvFalseIT` (không dùng profile) xanh: không `ScheduledAnnotationBeanPostProcessor`, 3 container không start. Log: `role=api scheduling=false semanticConsumer=false messagingConsumers=false semantic=true`. Endpoint semantic không phụ thuộc cờ consumer. |
| AC-3 | Pass | `application-worker.yml`; `RoleFlagsWorkerIT` xanh; log `role=worker scheduling=true ...`. Heartbeat consumer (`DigitizationHeartbeatConsumer:31`) và `DigitizationRunLeaseReconciliationJob` cùng cờ role (cùng ở worker, job nằm trong allowlist `ScheduledJobLockPolicyTest`). |
| AC-4 | Pass | `EmbedRequestConsumer.java:66` `autoStartup="#{${app.semantic.enabled:false} and ...}"`; `SemanticIndexReconciliationJob.run()` return sớm khi `!enabled`; `QueueBacklogAlertJob` chỉ check q.embed khi enabled. `SEMANTIC_ENABLED` dùng chung anchor `x-backend-env` (stg compose). Giữ nguyên hành vi 404 cũ (`SemanticSearchControllerIT` xanh). |
| AC-5 | Pass | `config/RoleStartupLogger.java` (dòng `role=... scheduling=... semantic=...`); `application.yml` `logging.pattern.level: "%5p role=${app.role:all} inst=${HOSTNAME:local}"`. Log test thực tế mang `role=api inst=local`. |
| AC-6 | Pass (IT mức job-lock; chưa test 2 instance lệch pha) | `DigitizationDispatchRetryJob.java:54` `@SchedulerLock(name="digitization-dispatch-retry", lockAtMostFor=PT5M, lockAtLeastFor=PT55S)`; 1 name cho cả `retryPendingDispatch` và `rescueMissedDocuments`. `SchedulerLockIT` chứng minh cơ chế (lock giữ -> bỏ lượt; `lockAtLeastFor` chặn instance lệch pha) trên `upload-cleanup`, `DigitizationDispatchIT` xanh. Test "B gọi sau A ~30s -> publish 1 lần" cho đúng job dispatch-retry theo Task 4 chưa viết (Non-blocking). |
| AC-7 | **Partial** | Có: `@SchedulerLock` ở cả 8 job nhóm S (`OutboxRetentionJob:55`, `DigitizationDlqAlertJob:34`, `DocumentDigitizationStatusReconciliationJob:43`, `SemanticIndexReconciliationJob:99`, `SearchIndexReconciliationJob:87`, `UploadCleanupJob:44`, `NotificationService:138`, `ReportExportCleanupJob:47`); `LoggingLockProvider` ghi `job skipped: lock held name=...` + counter `scheduler.lock.skipped{job}` (`LoggingLockProviderTest`, log thật); `SchedulerLockIT` xác nhận dòng `shedlock`; `ScheduledJobLockPolicyTest` chốt mọi `@Scheduled` phải có khoá hoặc nằm allowlist. **Thiếu:** `lockAtMostFor` ≥ 3 x tick max đo trên stg KHÔNG có số đo (Task 6 `[ ]`, spec §6.1 cột "tick max đo" còn `_đo_`); các giá trị hiện tại chỉ là đề xuất tạm. |
| AC-8 | Pass (xác nhận bằng đọc code, thiếu test 2 luồng) | Spec §6.2 ghi kết quả đọc code: `AutoConfirmScheduler`, `ReviewStaleAlertJob` (`DigitizationRunRepository:206-218`), `DigitizationRunLeaseReconciliationJob` (`:131`), `ReportExportJobRunner` (`ReportExportRepository:37`) đều claim SKIP LOCKED / UPDATE có điều kiện; Audit* dùng `AuditAdvisoryLock`; TrashPurge/PurgeStuck/PersonalIngestCleanup có `@SchedulerLock` (grep xác nhận). Allowlist trong `ScheduledJobLockPolicyTest`. DEV tự nêu chưa có test 2 luồng — đã kiểm chứng: đúng, không có. |
| AC-9 | Pass | `V37__embed_requested_at.sql` (cột `document.embed_requested_at`); `EmbedRequestPublisher.publish` gọi `documentRepository.markEmbedRequested` (`DocumentRepository:205-208`, có `@Transactional`) sau `convertAndSend` thành công — phủ cả poller lẫn reconciliation; `SemanticProperties.reconciliationGraceMs` (`SEMANTIC_RECONCILIATION_GRACE_MS`, mặc định 3600000); `SemanticIndexReconciliationJob.java:122-134` bỏ qua tài liệu trong grace. Test: `SemanticIndexReconciliationJobTest.aDocumentRequestedWithinTheGraceWindowIsSkipped` / `aDocumentRequestedLongAgoIsRequestedAgain`. `SemanticIndexingIT` không có assert riêng cho `embed_requested_at` (spec test plan có) — Non-blocking. |
| AC-10 | Pass (suy từ code; chưa test thực nghiệm 2 instance) | Không còn state cục bộ trong `upload/` (grep không thấy ConcurrentHashMap/cache); append dùng `findByIdForUpdate` + kiểm offset (`UploadSessionService:218`, `CHUNK_OFFSET_MISMATCH :573`); tempfile ở thư mục cấu hình được. `UploadSessionControllerIT` KHÔNG được mở rộng cho 2 `UploadSessionService` như spec (xác nhận, không có thay đổi). Cần kiểm tay trên stg (upload 2GB qua nginx RR). |
| AC-11 | Pass | `upload/UploadProperties.java` (`app.upload.temp-dir`, `UPLOAD_TEMP_DIR`); `UploadSessionService.createTempFile()` -> `Files.createTempFile(dir,...)` + `createDirectories`; `stg/docker-compose.yml`: volume `upload-tmp` mount `/data/upload-tmp` chỉ ở `backend` và `backend-worker`; không dùng bucket `documents`. Test: `UploadSessionServiceTest.initCreatesTheTempFileInsideTheConfiguredTempDirAndCreatesTheDirectory`. |
| AC-12 | Pass (thiết kế); chưa kiểm runtime | Tempfile nằm trên named volume sống qua thay container; `Dockerfile` `mkdir -p /data/upload-tmp && chown app:app` trước `USER app`. Chưa có test/kiểm tay deploy thực tế. |
| AC-13 | Pass | `UploadCleanupJob` chạy ở worker (`@Scheduled` + `@SchedulerLock`), xoá tệp qua `uploadSessionService`; `UploadCleanupJobIT.anOverdueOpenSessionIsExpiredAndItsTempFileIsDeletedFromDisk` xanh. |
| AC-14 | Pass | `UploadSessionService.requireUsableTempFile` (gọi ở append `:228`, complete `:316`) -> `notOpen()` 409 `UPLOAD_SESSION_NOT_OPEN`, không ghi, không đổi trạng thái. Tests: `appendChunkOnASessionWhoseTempFileIsOutsideTheTempDirIsRejectedAs409AndWritesNothing`, `appendChunkWhenTheTempFileIsGoneIsRejectedAndDoesNotRecreateIt`, `completeOnASessionWhoseTempFileIsOutsideTheTempDirIsRejected`. |
| AC-15 | Pass | `UploadCleanupJob.checkTempDirUsage()` WARN `upload-tmp usage=NN%` khi > 80%; runbook `stg/README.md` mục "Theo dõi cảnh báo" (`df -h`, grep key). Không thêm giới hạn phiên OPEN (đúng out-of-scope). Không có test riêng cho ngưỡng 80% — Non-blocking. |
| AC-16 | Pass | `search/index/SearchIndexPollerLock.java` (`pg_try_advisory_lock(4915070)` trên Connection riêng, unlock/abort ở `close()`); `SearchIndexOutboxPoller.poll():186-201` bọc vòng claim; thứ tự claim giữ nguyên. `OutboxPollerAdvisoryLockIT`: instance 2 không vào khi instance 1 giữ lock; kill connection -> nhả lock. Số đo độ trễ lexical ≤5s / vector ≤60s chưa đo (cần stg). |
| AC-17 | Pass (unit); thiếu IT | `SegmentVectorWriter.CONDITIONAL_SCRIPT` (noop khi `embed_text_hash` lệch; null = cho ghi), `WriteResult.skipped`, counter `semantic.vector.write.skipped`; hash chung `EmbedTextHash.of` dùng ở `EmbedTextBuilder.EmbedItem` (consumer, `EmbedRequestConsumer:96-100`) và `EmbedTextHash.annotate` trong `SearchIndexOutboxPoller` (`:352,388,418,533`); mapping thêm `embed_text_hash` (`segment-v2-index.json:160`, put-mapping additive `SearchIndexBootstrap:77-115`). Test: `SegmentVectorWriterTest.sendsAConditionalScriptWithTheHashAndCountsNoopAsSkipped`, `EmbedTextHashTest`. Thiếu: IT thật với OpenSearch cho trường hợp hash lệch -> noop (spec test plan: `SemanticIndexingIT`), kiểm chứng script Painless chỉ qua mock. |
| AC-18 | Pass | `stg/docker-compose.yml`: `SEMANTIC_QUERY_CONCURRENCY: "4"` + comment "Ngan sach semantic query 8 / so replica api (2) = 4". |
| AC-19 | Pass | `displayname/NameCheckRateLimiter.java`: key `namecheck:{sub}:{endpoint}:{now/60000}`, `INCR` + `EXPIRE 120s` khi count==1. Test `NameCheckRateLimiterTest.usesTheSharedRedisCounterWhenAvailable`. |
| AC-20 | Pass | Lỗi/timeout Redis (300ms riêng, `orTimeout`) -> fallback cửa sổ cục bộ + WARN giới hạn tần suất 30s, không log `sub`. Tests `redisFailureFailsOpenToTheLocalWindow`, `aHangingRedisFallsBackAfterTheOwnTimeout`. |
| AC-21 | Pass (stg); prod = snippet | `stg/docker-compose.yml`: anchor `x-backend-env`/`x-backend-depends`; `backend` (profile `api`, `deploy.replicas: 2`, không `ports`), `backend-worker` (profile `worker`, 1 replica), `restart: unless-stopped`. Prod compose nằm ngoài repo -> chỉ có snippet trong `stg/README.md` (đã chốt Q4). Chưa chạy `docker compose config`/up thực tế trên stg. |
| AC-22 | Pass (stg); prod = snippet | stg: api `mem_limit: 1g` x2, worker `1536m`, `JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=65`, không `-Xmx`. Prod (3g/4cpu x2 + 4g/4cpu) chỉ ghi trong runbook, chưa áp dụng được trong repo. |
| AC-23 | Pass | `stg/nginx/conf.d/00-common.conf:45` `resolver 127.0.0.11 valid=10s`; `api.conf` dùng biến upstream (không sticky) + `proxy_next_upstream error timeout; proxy_next_upstream_tries 2;`. Chưa kiểm RR thực tế trên stg. |
| AC-24 | Pass | `pom.xml` thêm `spring-boot-starter-actuator`; `application.yml`: `management.server.port=3002`, `exposure.include=health`, probes, readiness=`readinessState,db`; `Dockerfile` `HEALTHCHECK ... wget -qO- http://localhost:3002/actuator/health/readiness`; `SecurityConfig` permitAll chỉ `EndpointRequest.to(HealthEndpoint)`. `ActuatorManagementPortIT` (3 test) xanh. Container dev đang chạy `source_v2-backend-1` báo `(healthy)` và inspect cho thấy đúng lệnh HEALTHCHECK. Port 3002 không publish (compose stg không có `ports`; nginx không proxy). Xem lưu ý 401 ở mục Non-blocking. |
| AC-25 | Pass (cấu hình); chưa có IT | `server.shutdown: graceful`, `spring.lifecycle.timeout-per-shutdown-phase: 320s`, `stop_grace_period: 330s` (api + worker). Hành vi "message chưa ack trả lại queue" dựa trên mặc định Spring AMQP; `GracefulShutdownIT` KHÔNG tồn tại (xác nhận) và chưa kiểm tay. |
| AC-26 | Pass (cấu hình) | Queue `q.embed`/result durable (`RabbitConfig`); `QueueBacklogAlertJob` (`@SchedulerLock queue-backlog-alert`) log ERROR `queue backlog ...` khi depth > ngưỡng hoặc 0 consumer > N tick; `QueueBacklogAlertJobTest` (unit). `QueueBacklogAlertJobIT` không có (xác nhận) — unit test mock `AmqpAdmin` thay thế. Chưa test `docker stop` worker trên stg. |
| AC-27 | Pass | Không thay đổi tham số queue hiện hữu; q.embed khai báo trong `RabbitConfig` (cho cả hai role, `RabbitAdmin` vẫn declare khi `autoStartup=false`); `SemanticIndexingIT.theEmbedQueuesAreDeclaredExactlyAsOnStg` xanh. Chưa khởi động thật api+worker trên stg để loại 406. |
| AC-28 | Pass (tài liệu) | `stg/README.md:300-303,320-365` (deploy `docker compose up -d backend backend-worker web`, volume `upload-tmp`, runbook cảnh báo, snippet prod), `stg/BUILD.md:108-109`. Đề xuất cập nhật `production-deployment.md` thuộc SA — chưa kiểm. Chưa deploy stg. |

## Kết quả test/lint đã chạy

```
cd backend && mvn -o -q verify      -> EXIT=0
  surefire (unit):  1197 tests, 0 failures, 0 errors, 0 skipped
  failsafe (IT):     530 tests, 0 failures, 0 errors, 0 skipped   (kể cả TagsControllerIT, SearchScopeIT,
                                                                   SemanticIndexingIT - flaky đã biết không tái hiện lần này)
  IT mới của story chạy & xanh: RoleFlagsDefaultIT, RoleFlagsApiIT, RoleFlagsEnvFalseIT, RoleFlagsWorkerIT,
    SchedulerLockIT, ActuatorManagementPortIT, OutboxPollerAdvisoryLockIT
  Unit mới: LoggingLockProviderTest, ScheduledJobLockPolicyTest, QueueBacklogAlertJobTest,
    NameCheckRateLimiterTest, SemanticIndexReconciliationJobTest, SegmentVectorWriterTest, EmbedTextHashTest,
    UploadSessionServiceTest (case temp-dir / phiên cũ)
  Compile: xanh (verify đã build, gồm JasperPrecompiler).
Web (tsc/lint): không áp dụng - story không đổi web.
```

Kiểm chứng điểm DEV tự báo:
- Task 6 chưa làm: **đúng** (spec Task 6 `[ ]`, §6.1 còn `_đo_`).
- Thiếu `GracefulShutdownIT`, `QueueBacklogAlertJobIT`: **đúng** (find không thấy; chỉ có `QueueBacklogAlertJobTest` unit).
- `UploadSessionControllerIT` không có case 2 instance: **đúng** (không có diff/không có tham chiếu temp-dir).
- `SemanticIndexingIT` không có case hash lệch / `embed_requested_at`: **đúng** (grep không thấy); chỉ có unit test.
- Test 2 luồng nhóm C: **đúng**, không có.
- `/actuator/env` trên :3002 trả 401 thay vì 404: **đúng** theo thiết kế `ActuatorManagementPortIT` (chấp nhận 401 hoặc 404); không lộ dữ liệu (không expose, cần auth) nhưng lệch chữ spec "404".

## Vấn đề chặn (Blocking)

Không có lỗi code chặn. Một cổng phát hành bắt buộc trước khi lên prod:

- [ ] **AC-7 / Task 6**: đo tick tối đa từng job nhóm S trên stg, điền §6.1 và chốt `lockAtMostFor = max(3 x tick_max, chu kỳ)`; hiện giá trị chỉ là đề xuất tạm, chưa chứng minh đáp ứng AC. Cần deploy stg trước (việc đo phụ thuộc vào đó). Dev phải cập nhật spec §6.1 (và DoD) sau khi đo.

## Đề xuất cải thiện (Non-blocking)

- [ ] Bổ sung IT còn thiếu theo test plan: `GracefulShutdownIT` (AC-25), `QueueBacklogAlertJobIT` hoặc ít nhất giữ unit test (AC-26), `UploadSessionControllerIT` init/append/complete qua 2 `UploadSessionService` (AC-10), `SemanticIndexingIT` cho `embed_requested_at` + ghi vector noop khi hash lệch với OpenSearch thật (AC-9/17: hiện script Painless chỉ được kiểm bằng mock, rủi ro cú pháp/param chỉ lộ ở runtime), test dispatch-retry 2 instance lệch pha (AC-6), test 2 luồng cho nhóm C (AC-8).
- [ ] Siết `ActuatorManagementPortIT.onlyTheHealthEndpointIsExposed`/`actuatorIsNotServedOnTheApplicationPort`: hiện chấp nhận 401 hoặc 404; nên thêm case có JWT để chứng minh 404 thật và tránh "pass nhầm".
- [ ] Test ngưỡng `upload-tmp usage` > 80% (AC-15) và log WARN khi `df` không đọc được.
- [ ] Thay vì rely mặc định Spring AMQP, ghi lại bằng chứng kiểm tay `docker compose stop backend`/`stop backend-worker` (AC-25/26) vào checklist stg.
- [ ] Rủi ro R10 chưa xử lý: worker gom toàn bộ job vào pool scheduler (`spring.task.scheduling.pool.size`) trong khi poller giữ vòng đến 200 x 30s; cân nhắc tăng pool riêng ở worker.
- [ ] Working tree chứa lẫn nhiều thay đổi ngoài story này (search-semantic, export-reporting, bỏ qdrant khỏi `docker-compose.yml`, UI Keycloak...). Nên tách commit theo story để dễ rollback/diff khi deploy.
- [ ] `EmbedRequestPublisher` ghi `embed_requested_at` sau `convertAndSend` thành công; nếu UPDATE DB lỗi sau khi đã publish thì exception đẩy lại hàng outbox (publish trùng, vô hại do idempotent) — nên ghi chú vào spec.

## Kiểm tra thêm

- [x] Security checklist trong spec đã thoả? Phần lớn: `temp_file_path` bị ép nằm dưới temp-dir đã `normalize()` (`requireUsableTempFile`); chỉ expose `health`, 3002 không publish/không proxy; `sub` không bị log; Redis fail-open có chủ đích; volume chỉ mount ở `backend` & `backend-worker`. Lưu ý 401 thay vì 404 trên endpoint không expose (không lộ dữ liệu). Các ô checklist trong spec §8 vẫn chưa tick (DEV cần cập nhật).
- [x] Observability (log/metric) như spec? Có: `role=`/`inst=` trên mọi dòng log, dòng khởi động, `job skipped`, counter `scheduler.lock.skipped`, `semantic.vector.write.skipped`, WARN Redis fallback, WARN `upload-tmp usage=`, ERROR `queue backlog`. Chỉ expose `health` nên counter chỉ để nội bộ (đúng Q5).
- [x] Migration/rollback an toàn? V37 additive nullable; put-mapping additive; rollback = image/compose cũ, không cần down-migration. Lưu ý rủi ro R5b: đổi `chunkSizeTokens`/`overlapTokens`/model làm mọi ghi vector bị noop cho tới khi reindex lexical (đã ghi trong runbook `stg/README.md`).
- [ ] Chưa deploy/kiểm tay trên stg: upload 2GB qua nginx RR (AC-10), `docker stop` worker (AC-26), graceful stop (AC-25), `curl :3002` từ ngoài, khởi động api+worker không 406 (AC-27), đo độ trễ outbox (AC-16), đo Task 6 (AC-7).
