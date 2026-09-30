package com.aismartstore.backend.digitization;

import com.aismartstore.backend.config.QueueProperties;
import com.aismartstore.backend.documents.Document;
import com.aismartstore.backend.documents.DocumentRepository;
import com.aismartstore.backend.digitization.results.DigitizationRun;
import com.aismartstore.backend.digitization.results.DigitizationRunLifecycleService;
import com.aismartstore.backend.digitization.results.DigitizationRunRepository;
import java.time.OffsetDateTime;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

/**
 * digitization-pipeline-01-source-dispatch.md §4.4, extended by
 * digitization-pipeline-02-ocr-stt-processing.md §4.1: builds and publishes
 * the {@code q.ocr}/{@code q.stt} job message for one {@link
 * DigitizationSource} row, routing by {@code processFileType} (= {@code
 * source.mimeType}). §4.1 step 1 (mục 0 điểm 1): a {@code digitization_run}
 * (status {@code queued}) is created - or, if this is a re-publish of a
 * source whose earlier publish attempt already created one (retry job/
 * source-separation runner calling this again), reused - *before* the
 * message is built, so the message can carry a stable {@code runId}.
 */
@Service
public class DigitizationDispatchService {

    private static final Logger log = LoggerFactory.getLogger(DigitizationDispatchService.class);

    private final DigitizationSourceRepository digitizationSourceRepository;
    private final DocumentRepository documentRepository;
    private final RabbitTemplate rabbitTemplate;
    private final QueueProperties queueProperties;
    private final DigitizationRunLifecycleService digitizationRunLifecycleService;
    private final DigitizationRunRepository digitizationRunRepository;

    public DigitizationDispatchService(
            DigitizationSourceRepository digitizationSourceRepository,
            DocumentRepository documentRepository,
            RabbitTemplate rabbitTemplate,
            QueueProperties queueProperties,
            DigitizationRunLifecycleService digitizationRunLifecycleService,
            DigitizationRunRepository digitizationRunRepository) {
        this.digitizationSourceRepository = digitizationSourceRepository;
        this.documentRepository = documentRepository;
        this.rabbitTemplate = rabbitTemplate;
        this.queueProperties = queueProperties;
        this.digitizationRunLifecycleService = digitizationRunLifecycleService;
        this.digitizationRunRepository = digitizationRunRepository;
    }

    /**
     * Best-effort, immediate publish (§4.4 step 5): a RabbitMQ failure is
     * caught and recorded on {@code source} (attempt count bumped,
     * {@code dispatch_status} left {@code pending}) rather than propagated -
     * {@link DigitizationDispatchRetryJob} is the actual delivery guarantee.
     * Never throws for a routing/connectivity failure; callers (the ingest
     * listener, the source-separation runner, the retry job itself) can call
     * this without wrapping it.
     */
    public void publish(DigitizationSource source) {
        Document document = documentRepository.findById(source.getDocumentId()).orElse(null);
        if (document == null) {
            // FK guarantees this cannot happen for a row this code itself wrote;
            // only reachable if the document was hard-deleted out from under us.
            log.error(
                    "digitization_source {} references missing document {} - leaving dispatch_status=pending",
                    source.getId(), source.getDocumentId());
            return;
        }

        String task = resolveTask(source.getMimeType());
        if (task == null) {
            // §4.4 step 4: "programming error" - §4.2/4.3 should never create a
            // dispatchable row with a mimeType outside audio/video/image/pdf
            // (digitization-pipeline-03-video-queue.md §4.2 adds video/*).
            // Leave it pending so the reconciliation job's re-publish attempts
            // surface it repeatedly in logs rather than silently dropping it.
            log.error(
                    "digitization_source {} has unroutable processFileType '{}' - leaving dispatch_status=pending",
                    source.getId(), source.getMimeType());
            return;
        }
        String queueName = queueProperties.forTask(task);

        // digitization-pipeline-02-ocr-stt-processing.md §4.1 step 1 (mục 0
        // điểm 1): batchId is always null here - the interactive/bulk source
        // (data-ingestion.md, out of this spec's scope) does not exist yet
        // (§7 rủi ro); every dispatch is priority=5 until it does.
        DigitizationRun run;
        try {
            run = digitizationRunLifecycleService.createRun(document.getId(), source.getId(), task, null, null);
        } catch (IllegalStateException alreadyActive) {
            run = digitizationRunRepository.findActiveByDocumentAndTask(document.getId(), task)
                    .orElseThrow(() -> alreadyActive);
        }

        Map<String, Object> message = DigitizationJobMessages.build(
                run.getId(), document.getId(), source.getId(), source.getFilePath(),
                document.getMimeType(), source.getMimeType());
        int priority = run.getBatchId() != null ? 1 : 5;

        OffsetDateTime now = OffsetDateTime.now();
        try {
            rabbitTemplate.convertAndSend(RabbitConfig.EXCHANGE, queueName, message, m -> {
                m.getMessageProperties().setPriority(priority);
                return m;
            });
            source.setDispatchStatus(DigitizationSource.DISPATCH_PUBLISHED);
            source.setDispatchedAt(now);
        } catch (AmqpException e) {
            log.warn(
                    "Publish failed for digitization_source {} (queue={}) - will retry via reconciliation job",
                    source.getId(), queueName, e);
            source.setDispatchAttemptCount(source.getDispatchAttemptCount() + 1);
            // dispatch_status intentionally left as-is (pending).
        }
        source.setUpdatedAt(now);
        digitizationSourceRepository.save(source);
    }

    private static String resolveTask(String processFileType) {
        if (processFileType == null) {
            return null;
        }
        if (processFileType.startsWith("video/")) {
            return DigitizationRun.TASK_VIDEO;
        }
        if (processFileType.startsWith("audio/")) {
            return DigitizationRun.TASK_STT;
        }
        if (processFileType.startsWith("image/") || processFileType.equals("application/pdf")) {
            return DigitizationRun.TASK_OCR;
        }
        return null;
    }
}
