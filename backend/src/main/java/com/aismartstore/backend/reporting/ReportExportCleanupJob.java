package com.aismartstore.backend.reporting;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import com.aismartstore.backend.config.StorageProperties;
import com.aismartstore.backend.storage.ObjectStorageService;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * SPEC T14 (AC-33): hourly sweep - DONE jobs past {@code expires_at} get their object deleted from the
 * {@code reports} bucket and flip to EXPIRED. Idempotent (an already-missing object is not an error; S3 delete
 * of an absent key succeeds) and safe with several instances ({@code FOR UPDATE SKIP LOCKED}, LIMIT 200 per sweep).
 * One log line per sweep, like {@code UploadCleanupJob}.
 */
@Component
public class ReportExportCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(ReportExportCleanupJob.class);
    static final int BATCH = 200;

    private final ReportExportRepository repository;
    private final ObjectStorageService storage;
    private final StorageProperties storageProperties;
    private final TransactionTemplate tx;
    private final boolean enabled;

    public ReportExportCleanupJob(
            ReportExportRepository repository,
            ObjectStorageService storage,
            StorageProperties storageProperties,
            PlatformTransactionManager transactionManager,
            @Value("${app.reporting.cleanup-enabled:true}") boolean enabled) {
        this.repository = repository;
        this.storage = storage;
        this.storageProperties = storageProperties;
        this.tx = new TransactionTemplate(transactionManager);
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${app.reporting.cleanup-interval-ms:3600000}", initialDelayString = "${app.reporting.cleanup-initial-delay-ms:60000}")
    @SchedulerLock(name = "report-export-cleanup", lockAtMostFor = "PT3H", lockAtLeastFor = "PT30S")
    public void scheduled() {
        if (!enabled) {
            return;
        }
        try {
            sweep();
        } catch (RuntimeException e) {
            log.error("report export cleanup failed", e);
        }
    }

    /** One sweep; returns the number of jobs moved to EXPIRED. */
    public int sweep() {
        int[] counts = new int[3]; // scanned, expired, errors
        tx.executeWithoutResult(status -> {
            List<ReportExport> due = repository.findExpiredDoneForUpdate(BATCH);
            counts[0] = due.size();
            for (ReportExport job : due) {
                try {
                    if (job.getObjectKey() != null) {
                        storage.deleteObject(job.getObjectKey(), storageProperties.reportsBucket());
                    }
                    job.setStatus(ReportExport.EXPIRED);
                    repository.save(job);
                    counts[1]++;
                } catch (RuntimeException e) {
                    counts[2]++;
                    log.warn("report export cleanup: could not expire id={}", job.getId(), e);
                }
            }
        });
        log.info("report export cleanup: scanned={} expired={} errors={}", counts[0], counts[1], counts[2]);
        return counts[1];
    }
}
