package com.aismartstore.backend.search.index;

import com.aismartstore.backend.config.QueueProperties;
import com.aismartstore.backend.digitization.RabbitConfig;
import com.aismartstore.backend.documents.DocumentRepository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/** SPEC-search-semantic T7: publishes {@code embed.requested} to {@code ai.jobs} / {@code q.embed}. */
@Component
public class EmbedRequestPublisher {

    /** Interactive-ish: re-embeds after a human edit should beat a bulk backlog. */
    private static final int PRIORITY_DEFAULT = 5;
    private static final int PRIORITY_RECONCILE = 1;

    private final RabbitTemplate rabbitTemplate;
    private final QueueProperties queueProperties;
    private final DocumentRepository documentRepository;

    public EmbedRequestPublisher(
            RabbitTemplate rabbitTemplate, QueueProperties queueProperties, DocumentRepository documentRepository) {
        this.rabbitTemplate = rabbitTemplate;
        this.queueProperties = queueProperties;
        this.documentRepository = documentRepository;
    }

    /** @throws org.springframework.amqp.AmqpException when the broker rejects/loses the publish (caller retries the outbox row) */
    public void publish(UUID documentId, List<String> segmentIds, String reason, long outboxVersion) {
        EmbedRequestMessage message =
                new EmbedRequestMessage(UUID.randomUUID(), documentId, segmentIds, reason, outboxVersion);
        int priority = EmbedRequestMessage.REASON_RECONCILE.equals(reason) ? PRIORITY_RECONCILE : PRIORITY_DEFAULT;
        rabbitTemplate.convertAndSend(RabbitConfig.EXCHANGE, queueProperties.embedName(), message, m -> {
            m.getMessageProperties().setPriority(priority);
            return m;
        });
        // After a successful publish only: lets semantic reconciliation skip documents still in flight.
        documentRepository.markEmbedRequested(documentId, OffsetDateTime.now());
    }
}
