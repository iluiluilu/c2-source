package com.aismartstore.backend.search.index;

import com.aismartstore.backend.config.EmbeddingProperties;
import com.aismartstore.backend.config.QueueProperties;
import com.aismartstore.backend.config.SemanticProperties;
import com.aismartstore.backend.digitization.RabbitConfig;
import com.aismartstore.backend.search.EmbeddingClient;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

/**
 * SPEC-search-semantic T8 (indexing §4, Q-2: runs inside backend-api): consumes {@code q.embed},
 * rebuilds the texts from PostgreSQL, embeds them in batches and writes the vectors into OpenSearch.
 *
 * <p>The listener only starts when {@code app.semantic.enabled=true}. It never lets an exception
 * escape (that would requeue forever): a failure is re-published to {@code q.embed.retry} with a
 * per-message TTL backoff and an {@code x-retry-count} header; past {@code embed-max-retries} the
 * message goes to {@code q.embed.dlq} (alert) - except "document not indexed yet" (404 only), which
 * is dropped with a log after the retries (the document was purged meanwhile). A vector is never
 * written unless the endpoint response passed {@link EmbeddingClient}'s validation. Text is never logged.
 */
@Component
public class EmbedRequestConsumer {

    private static final Logger log = LoggerFactory.getLogger(EmbedRequestConsumer.class);

    static final String RETRY_HEADER = "x-retry-count";
    /** Backoff per retry attempt (ms): 5s, 30s, 2m, 10m, 30m, then the last value. */
    static final long[] BACKOFF_MS = {5_000, 30_000, 120_000, 600_000, 1_800_000};

    private final EmbedSegmentLoader loader;
    private final EmbeddingClient embeddingClient;
    private final SegmentVectorWriter writer;
    private final EmbeddingProperties embeddingProperties;
    private final SemanticProperties semanticProperties;
    private final QueueProperties queueProperties;
    private final RabbitTemplate rabbitTemplate;

    public EmbedRequestConsumer(
            EmbedSegmentLoader loader,
            EmbeddingClient embeddingClient,
            SegmentVectorWriter writer,
            EmbeddingProperties embeddingProperties,
            SemanticProperties semanticProperties,
            QueueProperties queueProperties,
            RabbitTemplate rabbitTemplate) {
        this.loader = loader;
        this.embeddingClient = embeddingClient;
        this.writer = writer;
        this.embeddingProperties = embeddingProperties;
        this.semanticProperties = semanticProperties;
        this.queueProperties = queueProperties;
        this.rabbitTemplate = rabbitTemplate;
    }

    @RabbitListener(
            queues = "${app.queue.embed-name:q.embed}",
            autoStartup = "#{${app.semantic.enabled:false} and ${app.semantic.consumer-enabled:true}}",
            concurrency = "${app.semantic.embed-consumer-concurrency:1}")
    public void onMessage(EmbedRequestMessage message, @Header(name = RETRY_HEADER, required = false) Integer retryCount) {
        int attempt = retryCount == null ? 0 : retryCount;
        long started = System.nanoTime();
        try {
            int written = process(message);
            log.info("embed.requested done: eventId={} documentId={} reason={} segments={} attempt={} ms={}",
                    message.eventId(), message.documentId(), message.reason(), written, attempt,
                    (System.nanoTime() - started) / 1_000_000);
        } catch (MissingDocumentException e) {
            handleFailure(message, attempt, e, true);
        } catch (IOException | RuntimeException e) {
            handleFailure(message, attempt, e, false);
        }
    }

    /** @return number of vectors written */
    int process(EmbedRequestMessage message) throws IOException {
        List<EmbedTextBuilder.EmbedItem> items = loader.load(message);
        if (items.isEmpty()) {
            return 0; // nothing eligible (empty text, sed, document gone) - success, nothing to write
        }
        List<String> texts = items.stream().map(EmbedTextBuilder.EmbedItem::text).toList();
        List<float[]> vectors = embeddingClient.embed(texts); // validated: count + 1024 dims, or throws

        Map<String, float[]> byId = new LinkedHashMap<>();
        for (int i = 0; i < items.size(); i++) {
            byId.put(items.get(i).segmentId(), vectors.get(i));
        }
        Map<String, String> hashes = new LinkedHashMap<>();
        for (EmbedTextBuilder.EmbedItem item : items) {
            hashes.put(item.segmentId(), item.hash());
        }
        SegmentVectorWriter.WriteResult result = writer.write(byId, hashes, embeddingProperties.modelVersion());
        if (!result.failed().isEmpty()) {
            throw new IOException(result.failed().size() + " vector update(s) failed");
        }
        if (!result.missing().isEmpty()) {
            throw new MissingDocumentException(result.missing().size() + " segment document(s) not indexed yet");
        }
        return result.updated();
    }

    private void handleFailure(EmbedRequestMessage message, int attempt, Exception error, boolean onlyMissing) {
        if (attempt < semanticProperties.embedMaxRetries()) {
            long ttl = BACKOFF_MS[Math.min(attempt, BACKOFF_MS.length - 1)];
            log.warn("embed.requested failed (attempt {}), retrying in {}ms: eventId={} documentId={} cause={}",
                    attempt + 1, ttl, message.eventId(), message.documentId(), error.toString());
            rabbitTemplate.convertAndSend(RabbitConfig.EXCHANGE, queueProperties.embedName() + ".retry", message, m -> {
                m.getMessageProperties().setExpiration(Long.toString(ttl));
                m.getMessageProperties().setHeader(RETRY_HEADER, attempt + 1);
                return m;
            });
            return;
        }
        if (onlyMissing) {
            log.warn("embed.requested dropped: segment documents never appeared in the index (purged?): eventId={} documentId={}",
                    message.eventId(), message.documentId());
            return;
        }
        log.error("embed.requested exhausted {} retries - sending to the DLQ: eventId={} documentId={} cause={}",
                attempt, message.eventId(), message.documentId(), error.toString());
        rabbitTemplate.convertAndSend(
                RabbitConfig.DEAD_LETTER_EXCHANGE, queueProperties.embedName() + ".dlq", message);
    }

    /** 404 on the partial update: the lexical document has not been (or is no longer) indexed. */
    static final class MissingDocumentException extends RuntimeException {
        MissingDocumentException(String message) {
            super(message);
        }
    }
}
