package com.aismartstore.backend.reporting;

import com.aismartstore.backend.common.ApiException;
import com.aismartstore.backend.config.StorageProperties;
import com.aismartstore.backend.reporting.dto.ReportResult;
import com.aismartstore.backend.storage.ObjectStorageService;
import com.aismartstore.backend.systemconfig.SystemConfigDefaults;
import com.aismartstore.backend.systemconfig.SystemConfigService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * SPEC T13 (AC-22, 23, 27, 28, 29, 34) + T15 (AC-38): background worker for {@code report_export}.
 *
 * <p>Claim = one {@code UPDATE .. FOR UPDATE SKIP LOCKED RETURNING} (PENDING, or RUNNING with an expired
 * lease), so several instances never take the same job and a job survives a restart (AC-23). Job bodies run on a
 * PRIVATE small executor, never on the shared scheduler pool or the request threads, and every failure is
 * caught: the reporting module cannot stall ingest/search (AC-38; it touches no OpenSearch/GPU).
 *
 * <p>Lease renewal before render and conditional finishing ({@code WHERE status='RUNNING'}, row count checked) make a
 * duplicate run caused by a reclaim harmless: only one run can record DONE, the loser deletes the object it wrote
 * (review #5). Per Q6 the runner does not re-check {@code report.export}: an accepted job runs to completion;
 * download always goes through the guard with the CURRENT permission.
 */
@Component
public class ReportExportJobRunner {

    private static final Logger log = LoggerFactory.getLogger(ReportExportJobRunner.class);

    static final int LEASE_SECONDS = 300;
    static final int MAX_ATTEMPTS = 3;
    static final int CLAIM_BATCH = 2;
    static final String MSG_TOO_LARGE = "Báo cáo quá lớn, hãy thu hẹp khoảng thời gian/bộ lọc";
    static final String MSG_TOO_MANY_ATTEMPTS = "Quá số lần thử, hãy tạo lại báo cáo";
    static final String MSG_GENERIC = "Không thể tạo tệp báo cáo, hãy thử lại sau";
    static final String MSG_PARAMS_INVALID = "Tham số báo cáo không còn hợp lệ, hãy tạo lại báo cáo";

    private final ReportExportRepository repository;
    private final ReportTemplateRegistry registry;
    private final ReportParamValidator validator;
    private final ReportQueryService queryService;
    private final ReportRenderService renderService;
    private final ReportAuditor auditor;
    private final SystemConfigService systemConfigService;
    private final ObjectStorageService storage;
    private final StorageProperties storageProperties;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate tx;
    private final boolean enabled;

    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(
            1, CLAIM_BATCH, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(CLAIM_BATCH), r -> {
                Thread t = new Thread(r, "report-export");
                t.setDaemon(true);
                return t;
            });
    private final AtomicLong lastStaleCheckMillis = new AtomicLong();

    public ReportExportJobRunner(
            ReportExportRepository repository,
            ReportTemplateRegistry registry,
            ReportParamValidator validator,
            ReportQueryService queryService,
            ReportRenderService renderService,
            ReportAuditor auditor,
            SystemConfigService systemConfigService,
            ObjectStorageService storage,
            StorageProperties storageProperties,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager,
            @Value("${app.reporting.runner-enabled:true}") boolean enabled) {
        this.repository = repository;
        this.registry = registry;
        this.validator = validator;
        this.queryService = queryService;
        this.renderService = renderService;
        this.auditor = auditor;
        this.systemConfigService = systemConfigService;
        this.storage = storage;
        this.storageProperties = storageProperties;
        this.objectMapper = objectMapper;
        this.tx = new TransactionTemplate(transactionManager);
        this.enabled = enabled;
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    /** Scheduler entrypoint: claims quickly, hands the heavy work to the private executor, never throws. */
    @Scheduled(fixedDelayString = "${app.reporting.runner-poll-ms:5000}")
    public void poll() {
        if (!enabled) {
            return;
        }
        try {
            warnIfStuck();
            if (executor.getQueue().remainingCapacity() == 0 || executor.getActiveCount() >= CLAIM_BATCH) {
                return; // busy: do not claim more than we can start
            }
            for (ReportExport job : repository.claimNextBatch(LEASE_SECONDS, CLAIM_BATCH)) {
                try {
                    executor.execute(() -> safeProcess(job));
                } catch (RejectedExecutionException e) {
                    log.warn("report.export id={} claimed but executor is full - will be reclaimed after lease", job.getId());
                }
            }
        } catch (Throwable t) { // a failing reporting poll must never hurt any other scheduled job
            log.error("report export poll failed", t);
        }
    }

    /**
     * Test/ops hook: claims and processes synchronously on the calling thread (what the scheduler does, minus the executor).
     *
     * @return number of jobs processed
     */
    public int runOnce() {
        List<ReportExport> claimed = repository.claimNextBatch(LEASE_SECONDS, CLAIM_BATCH);
        claimed.forEach(this::safeProcess);
        return claimed.size();
    }

    private void safeProcess(ReportExport job) {
        try {
            process(job);
        } catch (Throwable t) {
            log.error("report.export id={} crashed unexpectedly", job.getId(), t);
        }
    }

    private void warnIfStuck() {
        long now = System.currentTimeMillis();
        long last = lastStaleCheckMillis.get();
        if (now - last < 60_000 || !lastStaleCheckMillis.compareAndSet(last, now)) {
            return;
        }
        long stale = repository.countStalePending(OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(10));
        if (stale > 0) {
            log.warn("report.export: {} job(s) PENDING for more than 10 minutes", stale);
        }
    }

    void process(ReportExport job) {
        long started = System.nanoTime();
        if (job.getAttemptCount() > 1) {
            log.warn("report.export id={} reclaimed (attempt {})", job.getId(), job.getAttemptCount());
        }
        if (job.getAttemptCount() > MAX_ATTEMPTS) {
            fail(job, MSG_TOO_MANY_ATTEMPTS, null, started);
            return;
        }
        Path temp = null;
        Integer rows = null;
        try {
            ReportTemplateRegistry.Template template = registry.find(job.getTemplateCode())
                    .orElseThrow(() -> new IllegalStateException("template missing"));
            ReportParams params = validator.validate(
                    objectMapper.convertValue(job.getParamsJson(), ReportParams.class), template,
                    job.getRequestedBySub());
            ReportResult result = queryService.run(
                    job.getRequestedBySub(), job.getRequestedByUsername(), job.getTemplateCode(), params);
            long rowCount = result.rowCount();
            rows = (int) Math.min(rowCount, Integer.MAX_VALUE);
            long max = systemConfigService.getLong(SystemConfigDefaults.REPORTING_MAX_ROWS_PER_EXPORT);
            if (rowCount > max) {
                fail(job, MSG_TOO_LARGE, rows, started);
                return;
            }
            repository.renewLease(job.getId(), LEASE_SECONDS);

            temp = Files.createTempFile("report-" + job.getId() + "-", "." + ReportExportService.extension(job.getFormat()));
            renderService.render(result, job.getFormat(), temp);

            String key = job.getId() + "." + ReportExportService.extension(job.getFormat());
            String bucket = storageProperties.reportsBucket();
            storage.putObject(key, temp.toFile(), ReportExportService.mediaType(job.getFormat()).toString(), bucket);
            long bytes = Files.size(temp);
            int ttlDays = (int) systemConfigService.getLong(SystemConfigDefaults.REPORTING_EXPORT_TTL_DAYS);
            final int finalRows = rows;
            Boolean recorded = tx.execute(status -> {
                int updated = repository.markDone(job.getId(), key, bytes, finalRows, ttlDays);
                if (updated == 0) {
                    return false;
                }
                auditor.exportFinished(job, true, finalRows, elapsedMs(started));
                return true;
            });
            if (!Boolean.TRUE.equals(recorded)) {
                // Lost the race to another run of the same job (reclaim after a lease expiry). The object key is
                // deterministic (<id>.<ext>), so when the winner recorded DONE for that very key the file in the
                // bucket is the winner's - deleting it would destroy a finished export. Only remove our output
                // when no run owns the key (the other run FAILED / the job is no longer DONE).
                boolean keyOwnedByWinner = repository.findById(job.getId())
                        .map(j -> ReportExport.DONE.equals(j.getStatus()) && key.equals(j.getObjectKey()))
                        .orElse(false);
                if (!keyOwnedByWinner) {
                    safeDelete(key, bucket);
                }
                log.warn("report.export id={} finished by another run, discarded duplicate result", job.getId());
                return;
            }
            log.info(
                    "report.export id={} status=DONE format={} rows={} bytes={} durationMs={}",
                    job.getId(), job.getFormat(), rows, bytes, elapsedMs(started));
        } catch (ApiException e) { // our own, user-safe messages (e.g. 503 timeout hint, 400 params)
            log.warn("report.export id={} failed: {}", job.getId(), e.getMessage());
            fail(job, e.getStatus().is4xxClientError() ? MSG_PARAMS_INVALID : e.getMessage(), rows, started);
        } catch (RuntimeException | IOException e) {
            log.error("report.export id={} status=FAILED", job.getId(), e); // detail only in the log
            fail(job, MSG_GENERIC, rows, started);
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException e) {
                    log.warn("could not delete temp file {}", temp);
                }
            }
        }
    }

    private void fail(ReportExport job, String message, Integer rows, long started) {
        try {
            tx.executeWithoutResult(status -> {
                int updated = repository.markFailed(job.getId(), message, rows);
                if (updated > 0) {
                    auditor.exportFinished(job, false, rows, elapsedMs(started));
                }
            });
            log.warn("report.export id={} status=FAILED format={} durationMs={}", job.getId(), job.getFormat(), elapsedMs(started));
        } catch (RuntimeException e) {
            log.error("report.export id={} could not be marked FAILED", job.getId(), e);
        }
    }

    private void safeDelete(String key, String bucket) {
        try {
            storage.deleteObject(key, bucket);
        } catch (RuntimeException e) {
            log.warn("could not delete duplicate report object {}", key, e);
        }
    }

    private static long elapsedMs(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }
}
