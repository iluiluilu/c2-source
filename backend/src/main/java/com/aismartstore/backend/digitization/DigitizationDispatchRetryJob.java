package com.aismartstore.backend.digitization;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import com.aismartstore.backend.config.DigitizationProperties;
import com.aismartstore.backend.documents.Document;
import com.aismartstore.backend.documents.DocumentRepository;
import java.time.OffsetDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * digitization-pipeline-01-source-dispatch.md §4.5: the reconciliation
 * safety net that stands in for a full outbox (§0 point 1) - retries a
 * publish that failed/never happened, and rescues a document whose ingest
 * listener never ran at all.
 */
@Component
public class DigitizationDispatchRetryJob {

    private static final Logger log = LoggerFactory.getLogger(DigitizationDispatchRetryJob.class);

    /** §4.5 step 1: "quá 30 giây" grace period before retrying an in-flight publish. */
    private static final long PENDING_GRACE_PERIOD_SECONDS = 30;

    /** §4.5 step 2: "d.created_at < now() - interval '2 minutes'" grace period. */
    private static final long MISSED_DOCUMENT_GRACE_PERIOD_MINUTES = 2;

    private final DigitizationSourceRepository digitizationSourceRepository;
    private final DocumentRepository documentRepository;
    private final DigitizationIntakeService digitizationIntakeService;
    private final DigitizationDispatchService digitizationDispatchService;
    private final DigitizationProperties digitizationProperties;

    public DigitizationDispatchRetryJob(
            DigitizationSourceRepository digitizationSourceRepository,
            DocumentRepository documentRepository,
            DigitizationIntakeService digitizationIntakeService,
            DigitizationDispatchService digitizationDispatchService,
            DigitizationProperties digitizationProperties) {
        this.digitizationSourceRepository = digitizationSourceRepository;
        this.documentRepository = documentRepository;
        this.digitizationIntakeService = digitizationIntakeService;
        this.digitizationDispatchService = digitizationDispatchService;
        this.digitizationProperties = digitizationProperties;
    }

    @Scheduled(fixedDelayString = "${app.digitization.dispatch-retry-interval-ms:60000}")
    @SchedulerLock(name = "digitization-dispatch-retry", lockAtMostFor = "PT5M", lockAtLeastFor = "PT55S")
    public void run() {
        int retried = retryPendingDispatch();
        int rescued = rescueMissedDocuments();
        if (retried > 0 || rescued > 0) {
            log.info("Digitization reconciliation: retriedDispatch={} rescuedDocuments={}", retried, rescued);
        }
    }

    /** §4.5 step 1. */
    private int retryPendingDispatch() {
        OffsetDateTime cutoff = OffsetDateTime.now().minusSeconds(PENDING_GRACE_PERIOD_SECONDS);
        Pageable page = PageRequest.of(0, digitizationProperties.dispatchRetryBatchSize(), Sort.by("updatedAt"));
        List<DigitizationSource> pending = digitizationSourceRepository
                .findByDispatchableTrueAndDispatchStatusAndUpdatedAtBefore(
                        DigitizationSource.DISPATCH_PENDING, cutoff, page);
        for (DigitizationSource source : pending) {
            digitizationDispatchService.publish(source);
        }
        return pending.size();
    }

    /** §4.5 step 2. */
    private int rescueMissedDocuments() {
        OffsetDateTime cutoff = OffsetDateTime.now().minusMinutes(MISSED_DOCUMENT_GRACE_PERIOD_MINUTES);
        List<Document> missed = documentRepository.findMissedForDigitization(
                cutoff, digitizationProperties.dispatchRetryBatchSize());
        for (Document document : missed) {
            digitizationIntakeService.handleIngestedDocument(document.getId(), document.getMimeType());
        }
        return missed.size();
    }
}
