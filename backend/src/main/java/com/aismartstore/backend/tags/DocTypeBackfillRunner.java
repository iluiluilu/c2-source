package com.aismartstore.backend.tags;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

/**
 * classification-notes-01-tags.md §4.4 (Task 7) - one-off backfill of
 * {@code doc_type} for documents that existed before this migration/feature.
 *
 * <p>Runner choice (DEV decision - explicitly left open by the spec, not a
 * blocking question): a {@link CommandLineRunner}, not a separate Flyway
 * migration or a manually-triggered admin job. Rationale:
 * <ul>
 *   <li>Running it as a Flyway migration would hold {@code
 *   document}/{@code document_tag} under one long migration transaction on a
 *   large dataset (Rủi ro kỹ thuật, mục 7 của spec explicitly warns against
 *   this) - a batched, resumable application-level pass avoids that.</li>
 *   <li>It is idempotent and cheap to no-op: {@link
 *   DocTypeAssignmentService#backfillPage} only ever selects documents still
 *   missing a {@code doc_type} link ({@code
 *   DocumentTagRepository#findDocumentIdsMissingDocType}), so re-running it
 *   on every application startup (instead of a fragile "ran once" flag) is
 *   safe and self-healing for any document ingested through a path that
 *   somehow skipped auto-assignment.</li>
 *   <li>Batched (same {@link #BATCH_SIZE} as {@code UploadCleanupJob}) so a
 *   very large backlog is drained over multiple passes within this one
 *   startup call, each in its own short transaction ({@link
 *   DocTypeAssignmentService#backfillPage} is {@code @Transactional} per
 *   page), rather than one unbounded transaction.</li>
 * </ul>
 */
@Component
public class DocTypeBackfillRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DocTypeBackfillRunner.class);
    private static final int BATCH_SIZE = 500;
    /** Hard cap on pages per startup run, so a pathological loop can't hang application boot forever. */
    private static final int MAX_PAGES_PER_RUN = 1000;

    private final DocTypeAssignmentService docTypeAssignmentService;

    public DocTypeBackfillRunner(DocTypeAssignmentService docTypeAssignmentService) {
        this.docTypeAssignmentService = docTypeAssignmentService;
    }

    @Override
    public void run(String... args) {
        long startedAt = System.currentTimeMillis();
        int totalScanned = 0;
        int totalAssigned = 0;
        int totalUnmapped = 0;

        for (int page = 0; page < MAX_PAGES_PER_RUN; page++) {
            DocTypeAssignmentService.BackfillOutcome outcome = docTypeAssignmentService.backfillPage(BATCH_SIZE);
            totalScanned += outcome.scanned();
            totalAssigned += outcome.assigned();
            totalUnmapped += outcome.stillUnmapped();
            if (outcome.scanned() < BATCH_SIZE) {
                break;
            }
        }

        if (totalScanned > 0) {
            log.info(
                    "doc_type backfill: scanned={} assigned={} unmapped={} durationMs={}",
                    totalScanned,
                    totalAssigned,
                    totalUnmapped,
                    System.currentTimeMillis() - startedAt);
        }
    }
}
