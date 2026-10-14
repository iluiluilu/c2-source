package com.aismartstore.backend.search.index;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import com.aismartstore.backend.config.EmbeddingProperties;
import com.aismartstore.backend.config.QueueProperties;
import com.aismartstore.backend.config.SemanticProperties;
import com.aismartstore.backend.digitization.results.ContentSegment;
import com.aismartstore.backend.digitization.results.ContentSegmentRepository;
import com.aismartstore.backend.digitization.results.DigitizationRun;
import com.aismartstore.backend.digitization.results.DigitizationRunRepository;
import com.aismartstore.backend.documents.Document;
import com.aismartstore.backend.documents.DocumentRepository;
import com.aismartstore.backend.notes.DocumentNoteRepository;
import com.aismartstore.backend.search.SearchIndexNames;
import java.io.IOException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.FieldValue;
import org.opensearch.client.opensearch._types.query_dsl.Query;
import org.opensearch.client.opensearch.core.SearchRequest;
import org.opensearch.client.opensearch.core.SearchResponse;
import org.opensearch.client.opensearch.core.search.Hit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * SPEC-search-semantic T9 (indexing §4.5): periodic, bounded reconciliation between PostgreSQL (the
 * embeddable segments of each document: title, merged note, non-deleted segments of confirmed runs
 * with a source in {@link EmbedTextBuilder#EMBEDDABLE_SOURCES}) and the {@code segment} index (has an
 * {@code embedding} of the current {@code model_version}?). Anything missing is re-requested with
 * {@code embed.requested(reason=reconcile)} - this heals vectors lost by a lexical re-index (R4), a
 * lost message, or a model change. Each tick scans {@code reconciliation-batch-size} documents from an
 * in-memory cursor that wraps around, so the cost per tick is bounded. Also raises an ERROR when
 * {@code q.embed.dlq} is non-empty (the parked-outbox alarm is {@code SearchIndexReconciliationJob}).
 */
@Component
public class SemanticIndexReconciliationJob {

    private static final Logger log = LoggerFactory.getLogger(SemanticIndexReconciliationJob.class);

    /** Mismatch ratio above which the tick logs a WARN (target <= 0.1%). */
    private static final double DRIFT_THRESHOLD_RATIO = 0.001;
    private static final int OS_QUERY_CHUNK = 500;

    private final DocumentRepository documentRepository;
    private final DigitizationRunRepository digitizationRunRepository;
    private final ContentSegmentRepository contentSegmentRepository;
    private final DocumentNoteRepository documentNoteRepository;
    private final OpenSearchClient openSearchClient;
    private final EmbedRequestPublisher publisher;
    private final AmqpAdmin amqpAdmin;
    private final QueueProperties queueProperties;
    private final EmbeddingProperties embeddingProperties;
    private final SemanticProperties semanticProperties;

    private int cursorPage = 0;

    public SemanticIndexReconciliationJob(
            DocumentRepository documentRepository,
            DigitizationRunRepository digitizationRunRepository,
            ContentSegmentRepository contentSegmentRepository,
            DocumentNoteRepository documentNoteRepository,
            OpenSearchClient openSearchClient,
            EmbedRequestPublisher publisher,
            AmqpAdmin amqpAdmin,
            QueueProperties queueProperties,
            EmbeddingProperties embeddingProperties,
            SemanticProperties semanticProperties) {
        this.documentRepository = documentRepository;
        this.digitizationRunRepository = digitizationRunRepository;
        this.contentSegmentRepository = contentSegmentRepository;
        this.documentNoteRepository = documentNoteRepository;
        this.openSearchClient = openSearchClient;
        this.publisher = publisher;
        this.amqpAdmin = amqpAdmin;
        this.queueProperties = queueProperties;
        this.embeddingProperties = embeddingProperties;
        this.semanticProperties = semanticProperties;
    }

    @Scheduled(fixedDelayString = "${app.semantic.reconciliation-interval-ms:600000}")
    @SchedulerLock(name = "semantic-reconciliation", lockAtMostFor = "PT30M", lockAtLeastFor = "PT30S")
    public void run() {
        if (!semanticProperties.enabled()) {
            return;
        }
        try {
            reconcileBatch();
        } catch (IOException | RuntimeException e) {
            log.warn("semantic reconciliation tick failed: {}", e.toString());
        }
        reportDlq();
    }

    /** One bounded tick; package-visible for tests. */
    void reconcileBatch() throws IOException {
        Specification<Document> notDeleted = (root, query, cb) -> cb.isNull(root.get("deletedAt"));
        Page<Document> page = documentRepository.findAll(
                notDeleted, PageRequest.of(cursorPage, Math.max(1, semanticProperties.reconciliationBatchSize()), Sort.by("id")));
        cursorPage = page.hasNext() ? cursorPage + 1 : 0;

        Map<UUID, List<String>> expectedByDocument = new LinkedHashMap<>();
        // backend-multi-instance Task 8 (AC-9): a document whose embed.requested was published within the grace
        // window is still in flight (q.embed backlog / worker busy) - re-requesting it would only duplicate work.
        OffsetDateTime graceCutoff = OffsetDateTime.now().minus(Duration.ofMillis(semanticProperties.reconciliationGraceMs()));
        int skippedInGrace = 0;
        for (Document document : page) {
            OffsetDateTime requestedAt = document.getEmbedRequestedAt();
            if (requestedAt != null && requestedAt.isAfter(graceCutoff)) {
                skippedInGrace++;
                continue;
            }
            expectedByDocument.put(document.getId(), expectedSegmentIds(document));
        }
        if (skippedInGrace > 0) {
            log.info("semantic reconciliation: skipped {} document(s) inside the {} ms grace window",
                    skippedInGrace, semanticProperties.reconciliationGraceMs());
        }

        List<String> allExpected = expectedByDocument.values().stream().flatMap(List::stream).toList();
        Set<String> withVector = new HashSet<>();
        for (int from = 0; from < allExpected.size(); from += OS_QUERY_CHUNK) {
            withVector.addAll(idsWithCurrentVector(allExpected.subList(from, Math.min(from + OS_QUERY_CHUNK, allExpected.size()))));
        }

        int missingTotal = 0;
        for (Map.Entry<UUID, List<String>> entry : expectedByDocument.entrySet()) {
            List<String> missing = entry.getValue().stream().filter(id -> !withVector.contains(id)).toList();
            if (!missing.isEmpty()) {
                missingTotal += missing.size();
                publisher.publish(entry.getKey(), missing, EmbedRequestMessage.REASON_RECONCILE, 0L);
            }
        }
        double ratio = allExpected.isEmpty() ? 0.0 : missingTotal / (double) allExpected.size();
        if (ratio > DRIFT_THRESHOLD_RATIO) {
            log.warn("semantic reconciliation: documents={} expected={} missingVectors={} ({}%) - re-requested",
                    expectedByDocument.size(), allExpected.size(), missingTotal, String.format("%.2f", ratio * 100));
        } else {
            log.info("semantic reconciliation: documents={} expected={} missingVectors={}",
                    expectedByDocument.size(), allExpected.size(), missingTotal);
        }
    }

    private List<String> expectedSegmentIds(Document document) {
        List<String> ids = new ArrayList<>();
        UUID documentId = document.getId();
        if (document.getOriginalName() != null && !document.getOriginalName().isBlank()) {
            ids.add(documentId + ":title");
        }
        if (!documentNoteRepository.findByDocumentIdOrderByCreatedAtDesc(documentId).isEmpty()) {
            ids.add(documentId + ":note");
        }
        for (DigitizationRun run : digitizationRunRepository.findByDocumentIdAndReviewState(
                documentId, DigitizationRun.REVIEW_CONFIRMED)) {
            for (ContentSegment segment : contentSegmentRepository.findByRunIdOrderBySegmentIndex(run.getId())) {
                if (!segment.isDeleted()
                        && EmbedTextBuilder.isEmbeddable(segment.getContentSource())
                        && segment.getCurrentText() != null
                        && !segment.getCurrentText().isBlank()) {
                    ids.add(segment.getId().toString());
                }
            }
        }
        return ids;
    }

    /** ids (of those asked) that already have an embedding of the CURRENT model_version. */
    private Set<String> idsWithCurrentVector(List<String> ids) throws IOException {
        if (ids.isEmpty()) {
            return Set.of();
        }
        Query query = Query.of(q -> q.bool(b -> b.filter(
                Query.of(i -> i.ids(x -> x.values(ids))),
                Query.of(e -> e.exists(x -> x.field("embedding"))),
                Query.of(t -> t.term(x -> x.field("model_version").value(FieldValue.of(embeddingProperties.modelVersion())))))));
        SearchRequest request = new SearchRequest.Builder()
                .index(SearchIndexNames.SEGMENT_ALIAS)
                .size(ids.size())
                .source(s -> s.fetch(false))
                .query(query)
                .build();
        SearchResponse<Void> response = openSearchClient.search(request, Void.class);
        Set<String> found = new HashSet<>();
        for (Hit<Void> hit : response.hits().hits()) {
            found.add(hit.id());
        }
        return found;
    }

    private void reportDlq() {
        try {
            Properties props = amqpAdmin.getQueueProperties(queueProperties.embedName() + ".dlq");
            if (props != null && props.get(RabbitAdmin.QUEUE_MESSAGE_COUNT) instanceof Integer count && count > 0) {
                log.error("semantic embed DLQ alert: queue={}.dlq pendingMessages={}", queueProperties.embedName(), count);
            }
        } catch (RuntimeException e) {
            log.warn("could not read the embed DLQ depth: {}", e.toString());
        }
    }
}
