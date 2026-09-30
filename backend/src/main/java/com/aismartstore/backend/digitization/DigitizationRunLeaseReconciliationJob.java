package com.aismartstore.backend.digitization;

import com.aismartstore.backend.config.DigitizationProperties;
import com.aismartstore.backend.config.QueueProperties;
import com.aismartstore.backend.digitization.results.DigitizationRun;
import com.aismartstore.backend.digitization.results.DigitizationRunRepository;
import com.aismartstore.backend.documents.Document;
import com.aismartstore.backend.documents.DocumentRepository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * digitization-pipeline-02-ocr-stt-processing.md §4.4 (Story 3 AC-4) - detects
 * a job whose worker died mid-processing (heartbeat stopped, lease expired)
 * and re-queues it. With a 60s scan interval + 90s lease, a stuck job is
 * caught within ~150s - well inside the SA's 15-minute NFR.
 *
 * <p>Deliberately does NOT bump {@code attempt_count}: being re-handed to a
 * (possibly different) worker after a crash is not counted as a failed
 * attempt the way a {@code failed_retryable} {@code q.result} is (§4.4).
 */
@Component
public class DigitizationRunLeaseReconciliationJob {

    private static final Logger log = LoggerFactory.getLogger(DigitizationRunLeaseReconciliationJob.class);

    private final DigitizationRunRepository digitizationRunRepository;
    private final DigitizationSourceRepository digitizationSourceRepository;
    private final DocumentRepository documentRepository;
    private final RabbitTemplate rabbitTemplate;
    private final QueueProperties queueProperties;
    private final DigitizationProperties digitizationProperties;

    public DigitizationRunLeaseReconciliationJob(
            DigitizationRunRepository digitizationRunRepository,
            DigitizationSourceRepository digitizationSourceRepository,
            DocumentRepository documentRepository,
            RabbitTemplate rabbitTemplate,
            QueueProperties queueProperties,
            DigitizationProperties digitizationProperties) {
        this.digitizationRunRepository = digitizationRunRepository;
        this.digitizationSourceRepository = digitizationSourceRepository;
        this.documentRepository = documentRepository;
        this.rabbitTemplate = rabbitTemplate;
        this.queueProperties = queueProperties;
        this.digitizationProperties = digitizationProperties;
    }

    @Scheduled(fixedDelayString = "${app.digitization.lease-check-interval-ms:60000}")
    @Transactional
    public void run() {
        List<DigitizationRun> expired = digitizationRunRepository.findExpiredLeasesForUpdate(
                OffsetDateTime.now(), digitizationProperties.leaseCheckBatchSize());
        for (DigitizationRun run : expired) {
            reclaim(run);
        }
        if (!expired.isEmpty()) {
            log.info("Digitization lease reconciliation: reclaimed {} stuck run(s)", expired.size());
        }
    }

    private void reclaim(DigitizationRun run) {
        OffsetDateTime now = OffsetDateTime.now();
        run.setProcessingStatus(DigitizationRun.PROCESSING_QUEUED);
        run.setWorkerId(null);
        run.setLeaseExpiresAt(null);
        run.setUpdatedAt(now);
        digitizationRunRepository.save(run);

        DigitizationSource source = digitizationSourceRepository.findById(run.getSourceId()).orElse(null);
        Document document = documentRepository.findById(run.getDocumentId()).orElse(null);
        if (source == null || document == null) {
            log.error(
                    "Reclaimed run {} but cannot republish - source {} or document {} missing",
                    run.getId(), run.getSourceId(), run.getDocumentId());
            return;
        }

        Map<String, Object> message = DigitizationJobMessages.build(
                run.getId(), document.getId(), source.getId(), source.getFilePath(),
                document.getMimeType(), source.getMimeType());
        String queueName = queueProperties.forTask(run.getTask());
        int priority = run.getBatchId() != null ? 1 : 5;
        rabbitTemplate.convertAndSend(RabbitConfig.EXCHANGE, queueName, message, m -> {
            m.getMessageProperties().setPriority(priority);
            return m;
        });
    }
}
