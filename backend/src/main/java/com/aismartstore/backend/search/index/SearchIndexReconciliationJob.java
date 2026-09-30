package com.aismartstore.backend.search.index;

import com.aismartstore.backend.digitization.results.DigitizationRun;
import com.aismartstore.backend.digitization.results.DigitizationRunRepository;
import com.aismartstore.backend.digitization.results.ContentSegmentRepository;
import com.aismartstore.backend.outbox.OutboxEventRepository;
import com.aismartstore.backend.search.SearchIndexNames;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.FieldValue;
import org.opensearch.client.opensearch.core.CountResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * search-fulltext-04-dong-bo-chi-muc.md §4.3 (modeled on {@code
 * DocumentDigitizationStatusReconciliationJob}) - read-only drift detector
 * between the Postgres source of truth (confirmed, non-deleted {@code
 * content_segment} rows) and the {@code segment} OpenSearch index. Never
 * self-heals (a mismatch here is either an indexer bug or a temporary lag -
 * operations investigates, this job only surfaces it); scheduled to run
 * outside peak hours (§4.3's "chạy ngoài giờ cao điểm" - the default cron
 * below is 03:00 local time).
 */
@Component
public class SearchIndexReconciliationJob {

    private static final Logger log = LoggerFactory.getLogger(SearchIndexReconciliationJob.class);

    /** §4.3 (NFR SA mục 3): log a drift once it exceeds this fraction of the expected count. */
    private static final double DRIFT_THRESHOLD_RATIO = 0.001;

    private static final int PAGE_SIZE = 200;

    /** §4.4.4 - enough ids to start an investigation, not enough to flood a log line. */
    private static final int PARKED_IDS_IN_ALERT = 20;

    private final DigitizationRunRepository digitizationRunRepository;
    private final ContentSegmentRepository contentSegmentRepository;
    private final OpenSearchClient openSearchClient;
    private final OutboxEventRepository outboxEventRepository;
    private final int maxAttempts;

    public SearchIndexReconciliationJob(
            DigitizationRunRepository digitizationRunRepository,
            ContentSegmentRepository contentSegmentRepository,
            OpenSearchClient openSearchClient,
            OutboxEventRepository outboxEventRepository,
            @Value("${app.search-index.max-attempts:20}") int maxAttempts) {
        this.digitizationRunRepository = digitizationRunRepository;
        this.contentSegmentRepository = contentSegmentRepository;
        this.openSearchClient = openSearchClient;
        this.outboxEventRepository = outboxEventRepository;
        this.maxAttempts = maxAttempts;
    }

    /**
     * search-fulltext-04-dong-bo-chi-muc.md §4.4.4 - the counterpart this job
     * owes to §4.4.1's parking behaviour. Parking stops one poisoned row from
     * freezing the whole queue, but the row itself is then never indexed; with
     * no alarm attached that would just trade a loud outage for a quiet one.
     * A non-zero count here means the index is knowingly missing data.
     */
    private void reportParkedOutboxEvents() {
        long parked = outboxEventRepository.countParkedForIndexing(
                SearchIndexOutboxPoller.HANDLED_EVENT_TYPES, maxAttempts);
        if (parked == 0) {
            return;
        }
        List<Long> ids = outboxEventRepository.findParkedIdsForIndexing(
                SearchIndexOutboxPoller.HANDLED_EVENT_TYPES, maxAttempts, PARKED_IDS_IN_ALERT);
        log.error(
                "search index: {} outbox_event row(s) parked after >= {} failed attempts - these changes are "
                        + "NOT in the index and never will be without manual intervention. First ids: {}{}",
                parked, maxAttempts, ids, parked > ids.size() ? " (truncated)" : "");
    }

    @Scheduled(cron = "${app.search-index.reconciliation-cron:0 0 3 * * *}")
    public void run() {
        int checked = 0;
        int mismatches = 0;
        int page = 0;
        Page<UUID> batch;
        do {
            batch = digitizationRunRepository.findDistinctDocumentIdByReviewState(
                    DigitizationRun.REVIEW_CONFIRMED, PageRequest.of(page, PAGE_SIZE));
            for (UUID documentId : batch) {
                checked++;
                try {
                    if (reconcileOne(documentId)) {
                        mismatches++;
                    }
                } catch (IOException e) {
                    log.warn("search index reconciliation: could not read OpenSearch count for document {}: {}",
                            documentId, e.toString());
                }
            }
            page++;
        } while (batch.hasNext());

        log.info("search index reconciliation: checked={} mismatched={}", checked, mismatches);

        // Deliberately after the drift scan and outside its try/catch: an
        // OpenSearch outage must not hide the parked-row alarm, since the two
        // failures tend to arrive together.
        reportParkedOutboxEvents();
    }

    /** @return true if a drift beyond the threshold was found (and logged). */
    private boolean reconcileOne(UUID documentId) throws IOException {
        List<DigitizationRun> confirmedRuns =
                digitizationRunRepository.findByDocumentIdAndReviewState(documentId, DigitizationRun.REVIEW_CONFIRMED);
        long expected = 0;
        for (DigitizationRun run : confirmedRuns) {
            expected += contentSegmentRepository.countByRunIdAndDeletedFalse(run.getId());
        }

        long actual = countIndexedSegments(documentId);

        long diff = Math.abs(expected - actual);
        boolean mismatch = expected == 0 ? actual != 0 : (diff / (double) expected) > DRIFT_THRESHOLD_RATIO;
        if (mismatch) {
            log.warn("search index drift for document {}: expected={} (Postgres, confirmed) actual={} (OpenSearch segment)",
                    documentId, expected, actual);
        }
        return mismatch;
    }

    private long countIndexedSegments(UUID documentId) throws IOException {
        CountResponse response = openSearchClient.count(c -> c
                .index(SearchIndexNames.SEGMENT_ALIAS)
                .query(q -> q.term(t -> t.field("document_id").value(FieldValue.of(documentId.toString())))));
        return response.count();
    }
}
