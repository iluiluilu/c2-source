package com.aismartstore.backend.digitization.review;

import com.aismartstore.backend.audit.AuditService;
import com.aismartstore.backend.digitization.results.DigitizationRun;
import com.aismartstore.backend.digitization.results.DigitizationRunLifecycleService;
import com.aismartstore.backend.systemconfig.SystemConfigDefaults;
import com.aismartstore.backend.systemconfig.SystemConfigService;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * digitization-review-01-ocr.md §4 bước 6 (Quyết định 2 SA, Story 6): flags
 * (audit-only, no state transition - US6 AC-3) {@code in_review} runs stuck
 * past {@code ocr_stt.review_stale_hours} since {@code first_edit_at}.
 *
 * <p>Same per-batch-transaction rationale as {@link AutoConfirmScheduler} -
 * see that class's Javadoc.
 */
@Component
public class ReviewStaleAlertJob {

    private static final Logger log = LoggerFactory.getLogger(ReviewStaleAlertJob.class);

    /** digitization-review-01-ocr.md §4 bước 6: lô 500. */
    static final int BATCH_SIZE = 500;

    static final String AUDIT_ACTION_REVIEW_STALE = "digitization.review_stale";

    private final DigitizationRunLifecycleService lifecycleService;
    private final SystemConfigService systemConfigService;
    private final AuditService auditService;
    private final TransactionTemplate transactionTemplate;

    public ReviewStaleAlertJob(
            DigitizationRunLifecycleService lifecycleService,
            SystemConfigService systemConfigService,
            AuditService auditService,
            PlatformTransactionManager transactionManager) {
        this.lifecycleService = lifecycleService;
        this.systemConfigService = systemConfigService;
        this.auditService = auditService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Scheduled(fixedDelayString = "${app.digitization.review-stale-check-interval-ms:300000}")
    public void run() {
        int staleHours = (int) systemConfigService.getLong(SystemConfigDefaults.OCR_STT_REVIEW_STALE_HOURS);

        int totalFlagged = 0;
        int flaggedInBatch;
        do {
            flaggedInBatch = transactionTemplate.execute(status -> processBatch(staleHours));
            totalFlagged += flaggedInBatch;
        } while (flaggedInBatch == BATCH_SIZE);

        if (totalFlagged > 0) {
            log.info("ReviewStaleAlertJob: flagged {} run(s) as stale this tick", totalFlagged);
        }
    }

    private int processBatch(int staleHours) {
        List<DigitizationRun> stale = lifecycleService.flagStaleInReviewBatch(staleHours, BATCH_SIZE);
        for (DigitizationRun run : stale) {
            auditService.record(new AuditService.AuditEntry(
                    "system", null, AUDIT_ACTION_REVIEW_STALE, "digitization_run", run.getId().toString(),
                    "success", null, null, null, null,
                    Map.of("documentId", run.getDocumentId().toString(), "task", run.getTask())));
        }
        return stale.size();
    }
}
