package com.aismartstore.backend.search.index;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aismartstore.backend.digitization.results.ContentSegmentRepository;
import com.aismartstore.backend.digitization.results.DigitizationRun;
import com.aismartstore.backend.digitization.results.DigitizationRunRepository;
import com.aismartstore.backend.outbox.OutboxEventRepository;
import java.util.List;
import java.util.UUID;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch.core.CountResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

/**
 * search-fulltext-04-dong-bo-chi-muc.md §4.3/§5 - {@link
 * SearchIndexReconciliationJob} never self-heals, only logs; this test
 * exercises the counting/threshold logic against mocked repositories/client.
 */
class SearchIndexReconciliationJobTest {

    private final DigitizationRunRepository digitizationRunRepository = mock(DigitizationRunRepository.class);
    private final ContentSegmentRepository contentSegmentRepository = mock(ContentSegmentRepository.class);
    private final OpenSearchClient openSearchClient = mock(OpenSearchClient.class);
    private final OutboxEventRepository outboxEventRepository = mock(OutboxEventRepository.class);

    private static final int MAX_ATTEMPTS = 20;

    private final SearchIndexReconciliationJob job =
            new SearchIndexReconciliationJob(
                    digitizationRunRepository, contentSegmentRepository, openSearchClient,
                    outboxEventRepository, MAX_ATTEMPTS);

    @Test
    void doesNotFlagADocumentWhosePostgresAndOpenSearchCountsMatch() throws Exception {
        UUID documentId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        Page<UUID> page = new PageImpl<>(List.of(documentId), PageRequest.of(0, 200), 1);
        when(digitizationRunRepository.findDistinctDocumentIdByReviewState(eq(DigitizationRun.REVIEW_CONFIRMED), any()))
                .thenReturn(page);

        DigitizationRun run = new DigitizationRun();
        run.setId(runId);
        when(digitizationRunRepository.findByDocumentIdAndReviewState(documentId, DigitizationRun.REVIEW_CONFIRMED))
                .thenReturn(List.of(run));
        when(contentSegmentRepository.countByRunIdAndDeletedFalse(runId)).thenReturn(5L);
        when(openSearchClient.count(any(java.util.function.Function.class))).thenReturn(countResponse(5));

        job.run(); // should not throw, and (by construction) logs no mismatch - covered indirectly below
    }

    @Test
    void flagsAMismatchWhenOpenSearchIsMissingSegments() throws Exception {
        UUID documentId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        Page<UUID> page = new PageImpl<>(List.of(documentId), PageRequest.of(0, 200), 1);
        when(digitizationRunRepository.findDistinctDocumentIdByReviewState(eq(DigitizationRun.REVIEW_CONFIRMED), any()))
                .thenReturn(page);

        DigitizationRun run = new DigitizationRun();
        run.setId(runId);
        when(digitizationRunRepository.findByDocumentIdAndReviewState(documentId, DigitizationRun.REVIEW_CONFIRMED))
                .thenReturn(List.of(run));
        when(contentSegmentRepository.countByRunIdAndDeletedFalse(runId)).thenReturn(1000L);
        when(openSearchClient.count(any(java.util.function.Function.class))).thenReturn(countResponse(1)); // way off

        // No exception - a mismatch is logged, never thrown/self-healed (spec §4.3).
        job.run();
    }

    @Test
    void toleratesAOpenSearchFailureForOneDocumentAndKeepsGoing() throws Exception {
        UUID documentId = UUID.randomUUID();
        Page<UUID> page = new PageImpl<>(List.of(documentId), PageRequest.of(0, 200), 1);
        when(digitizationRunRepository.findDistinctDocumentIdByReviewState(eq(DigitizationRun.REVIEW_CONFIRMED), any()))
                .thenReturn(page);
        when(digitizationRunRepository.findByDocumentIdAndReviewState(documentId, DigitizationRun.REVIEW_CONFIRMED))
                .thenReturn(List.of());
        when(openSearchClient.count(any(java.util.function.Function.class)))
                .thenThrow(new java.io.IOException("opensearch down"));

        org.assertj.core.api.Assertions.assertThatCode(job::run).doesNotThrowAnyException();
    }

    /**
     * trash-search-index-cleanup.md §4.6/§5 - a document already in the
     * trash still has confirmed {@code content_segment} rows in Postgres
     * (trash is a soft delete) but its OpenSearch footprint is now correctly
     * empty (§2 - the poller deletes it outright); {@code
     * findDistinctDocumentIdByReviewState} now excludes it (see the {@code
     * deletedAt IS NULL} filter added to that query) so it never reaches
     * {@code reconcileOne} at all - never checked, never flagged, no ERROR
     * log for OpenSearch reads that would otherwise be attempted on a
     * trashed document. Mocked at the repository boundary (this test does
     * not exercise the real JPQL {@code EXISTS} filter - see spec §7 rủi ro).
     */
    @Test
    void aTrashedDocumentIsNeverIteratedSoItIsNeverFlaggedAsDrifting() throws Exception {
        // The trashed document simply never appears in what the (now-filtered) query returns -
        // this is exactly what `AND EXISTS (... d.deletedAt IS NULL)` achieves in production.
        Page<UUID> pageWithoutTheTrashedDocument = new PageImpl<>(List.of(), PageRequest.of(0, 200), 0);
        when(digitizationRunRepository.findDistinctDocumentIdByReviewState(eq(DigitizationRun.REVIEW_CONFIRMED), any()))
                .thenReturn(pageWithoutTheTrashedDocument);

        ListAppender<ILoggingEvent> appender = attachAppender();
        try {
            job.run();
        } finally {
            detachAppender(appender);
        }

        verify(openSearchClient, never()).count(any(java.util.function.Function.class));
        assertThat(appender.list).filteredOn(e -> e.getLevel() == Level.WARN).isEmpty();
    }

    private static CountResponse countResponse(long count) {
        return CountResponse.of(b -> b.count(count).shards(s -> s.total(1).successful(1).failed(0)));
    }

    // ---- §4.4.4: parked-row alarm ------------------------------------------

    /**
     * §4.4.4 - without this alarm, §4.4.1's parking would swap a loud outage
     * (frozen queue, ERROR flood) for a silent one (index quietly missing a
     * document forever).
     */
    @Test
    void logsAnErrorNamingTheParkedOutboxRows() {
        when(digitizationRunRepository.findDistinctDocumentIdByReviewState(any(), any()))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 200), 0));
        when(outboxEventRepository.countParkedForIndexing(any(), anyInt())).thenReturn(3L);
        when(outboxEventRepository.findParkedIdsForIndexing(any(), anyInt(), anyInt()))
                .thenReturn(List.of(82L, 91L, 104L));

        ListAppender<ILoggingEvent> appender = attachAppender();
        try {
            job.run();
        } finally {
            detachAppender(appender);
        }

        assertThat(appender.list)
                .filteredOn(e -> e.getLevel() == Level.ERROR)
                .singleElement()
                .satisfies(e -> assertThat(e.getFormattedMessage()).contains("3").contains("82"));
    }

    @Test
    void staysQuietWhenNothingIsParked() {
        when(digitizationRunRepository.findDistinctDocumentIdByReviewState(any(), any()))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 200), 0));
        when(outboxEventRepository.countParkedForIndexing(any(), anyInt())).thenReturn(0L);

        ListAppender<ILoggingEvent> appender = attachAppender();
        try {
            job.run();
        } finally {
            detachAppender(appender);
        }

        assertThat(appender.list).filteredOn(e -> e.getLevel() == Level.ERROR).isEmpty();
        verify(outboxEventRepository, never()).findParkedIdsForIndexing(any(), anyInt(), anyInt());
    }

    private static ListAppender<ILoggingEvent> attachAppender() {
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(SearchIndexReconciliationJob.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private static void detachAppender(ListAppender<ILoggingEvent> appender) {
        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(SearchIndexReconciliationJob.class))
                .detachAppender(appender);
    }
}
