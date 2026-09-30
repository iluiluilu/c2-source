package com.aismartstore.backend.digitization.review;

import com.aismartstore.backend.audit.AuditService;
import com.aismartstore.backend.digitization.results.DigitizationRun;
import com.aismartstore.backend.digitization.results.DigitizationRunLifecycleService;
import com.aismartstore.backend.outbox.OutboxEvent;
import com.aismartstore.backend.outbox.OutboxEventRepository;
import com.aismartstore.backend.systemconfig.SystemConfigDefaults;
import com.aismartstore.backend.systemconfig.SystemConfigService;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * digitization-review-01-ocr.md §4 bước 5 (Quyết định 1 SA, Story 5): sweeps
 * {@code pending} runs whose {@code review_deadline_at} has passed into
 * {@code confirmed}/{@code confirm_source='auto'}.
 *
 * <p>Each {@link #processBatch(long)} call - i.e. each up-to-{@link
 * #BATCH_SIZE} claim - is its own transaction (via {@link TransactionTemplate},
 * not the class-level {@code @Transactional} idiom used elsewhere in this
 * package, because {@link #run()} loops over several batches per tick and a
 * self-invoked {@code @Transactional} private method would silently run
 * outside any transaction - Spring's proxy-based AOP does not intercept
 * calls that do not go through the bean proxy). §7 rủi ro 2: this means one
 * failing row's failure only rolls back its own 500-row batch, not every
 * batch already committed earlier in the same tick.
 */
@Component
public class AutoConfirmScheduler {

    private static final Logger log = LoggerFactory.getLogger(AutoConfirmScheduler.class);

    /** digitization-review-01-ocr.md §4 bước 5 (Quyết định 1 SA): lô 500. */
    static final int BATCH_SIZE = 500;
    /** Story 5 AC-4 ("≥5.000 run/phút" khi có tồn đọng): 20 x 500 = 10.000 run/tick. */
    static final int MAX_BATCHES_PER_TICK = 20;

    static final String OUTBOX_EVENT_REVIEW_CONFIRMED = "review.confirmed";
    static final String AUDIT_ACTION_AUTO_CONFIRMED = "digitization.auto_confirmed";

    private final DigitizationRunLifecycleService lifecycleService;
    private final SystemConfigService systemConfigService;
    private final AuditService auditService;
    private final OutboxEventRepository outboxEventRepository;
    private final TransactionTemplate transactionTemplate;

    public AutoConfirmScheduler(
            DigitizationRunLifecycleService lifecycleService,
            SystemConfigService systemConfigService,
            AuditService auditService,
            OutboxEventRepository outboxEventRepository,
            PlatformTransactionManager transactionManager) {
        this.lifecycleService = lifecycleService;
        this.systemConfigService = systemConfigService;
        this.auditService = auditService;
        this.outboxEventRepository = outboxEventRepository;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Scheduled(fixedDelayString = "${app.digitization.auto-confirm-interval-ms:60000}")
    public void run() {
        // Story 5 AC-2: kill switch.
        if (!systemConfigService.getBoolean(SystemConfigDefaults.OCR_STT_AUTO_CONFIRM_ENABLED)) {
            return;
        }

        int totalConfirmed = 0;
        for (int i = 0; i < MAX_BATCHES_PER_TICK; i++) {
            int confirmedInBatch = transactionTemplate.execute(status -> processBatch());
            totalConfirmed += confirmedInBatch;
            if (confirmedInBatch < BATCH_SIZE) {
                break;
            }
        }
        if (totalConfirmed > 0) {
            log.info("AutoConfirmScheduler: auto-confirmed {} run(s) this tick", totalConfirmed);
        }
    }

    /** One claimed batch + its audit/outbox rows, all in the transaction {@link #run} opened for it. */
    private int processBatch() {
        List<DigitizationRun> confirmed = lifecycleService.autoConfirmDueBatch(BATCH_SIZE);
        for (DigitizationRun run : confirmed) {
            auditService.record(new AuditService.AuditEntry(
                    "system", null, AUDIT_ACTION_AUTO_CONFIRMED, "digitization_run", run.getId().toString(),
                    "success", null, null, null, null,
                    Map.of("documentId", run.getDocumentId().toString(), "task", run.getTask())));
            writeReviewConfirmedOutboxEvent(run);
        }
        return confirmed.size();
    }

    private void writeReviewConfirmedOutboxEvent(DigitizationRun run) {
        OffsetDateTime now = OffsetDateTime.now();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("documentId", run.getDocumentId().toString());
        payload.put("runId", run.getId().toString());
        payload.put("task", run.getTask());
        payload.put("confirmSource", DigitizationRun.CONFIRM_AUTO);
        payload.put("confirmedAt", run.getConfirmedAt() != null ? run.getConfirmedAt().toString() : now.toString());
        payload.put("segmentCount", run.getSegmentCount());

        OutboxEvent event = new OutboxEvent();
        event.setEventType(OUTBOX_EVENT_REVIEW_CONFIRMED);
        event.setPayload(payload);
        event.setCreatedAt(now);
        outboxEventRepository.save(event);
    }
}
