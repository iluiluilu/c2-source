package com.aismartstore.backend.digitization.results;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DigitizationRunRepository extends JpaRepository<DigitizationRun, UUID> {

    /**
     * bugs/bug-004-trash-purge-missing-child-table-cleanup.md - {@code
     * TrashPurgeService#purgeOne} must clear every run's self-reference
     * ({@code depends_on_run_id}, e.g. a source-separation audio run
     * depending on the combined run) before deleting any run of the
     * document, otherwise deleting the depended-on run first violates
     * {@code digitization_run_depends_on_run_id_fkey}.
     */
    @Modifying
    @Query("UPDATE DigitizationRun r SET r.dependsOnRunId = null WHERE r.documentId = :documentId")
    void clearDependsOnRunIdByDocumentId(@Param("documentId") UUID documentId);

    /**
     * bugs/bug-004-trash-purge-missing-child-table-cleanup.md -
     * {@code TrashPurgeService#purgeOne} bước 2: phải xoá {@code
     * digitization_run} trước {@code digitization_source} (FK {@code
     * digitization_run_source_id_fkey}, {@code ON DELETE NO ACTION}), và
     * sau khi {@code content_segment} của document đã bị xoá (FK {@code
     * content_segment_run_id_fkey}).
     */
    @Modifying
    int deleteByDocumentId(UUID documentId);

    /**
     * digitization-results-store.md §3: rows a worker can pick up next -
     * either freshly {@code queued}, or {@code running} with an expired
     * lease (a worker died mid-job and needs to be reclaimed). Backed by
     * {@code IDX_digitization_run_pollable (processing_status,
     * lease_expires_at)}.
     */
    @Query("SELECT r FROM DigitizationRun r WHERE r.processingStatus = 'queued' "
            + "OR (r.processingStatus = 'running' AND r.leaseExpiresAt < :leaseExpiredBefore) "
            + "ORDER BY r.createdAt")
    List<DigitizationRun> findPollableForProcessing(
            @Param("leaseExpiredBefore") OffsetDateTime leaseExpiredBefore, Pageable pageable);

    /**
     * digitization-results-store.md §3/§4 bước 8: runs whose {@code
     * review_deadline_at} has passed while still {@code pending}/{@code
     * in_review} - candidates for the auto-confirm sweeper (FR-18). Backed by
     * {@code IDX_digitization_run_review_pollable (review_state,
     * review_deadline_at)}.
     */
    @Query("SELECT r FROM DigitizationRun r WHERE r.reviewState IN ('pending', 'in_review') "
            + "AND r.reviewDeadlineAt IS NOT NULL AND r.reviewDeadlineAt <= :deadlineBefore "
            + "ORDER BY r.reviewDeadlineAt")
    List<DigitizationRun> findPollableForReview(
            @Param("deadlineBefore") OffsetDateTime deadlineBefore, Pageable pageable);

    /**
     * digitization-results-store.md §3: the single "active" run (not {@code
     * superseded}/{@code void}) for a {@code (document_id, task)} pair -
     * mirrors {@code UQ_digitization_run_active}.
     */
    @Query("SELECT r FROM DigitizationRun r WHERE r.documentId = :documentId AND r.task = :task "
            + "AND r.reviewState NOT IN ('superseded', 'void')")
    Optional<DigitizationRun> findActiveByDocumentAndTask(
            @Param("documentId") UUID documentId, @Param("task") String task);

    /**
     * digitization-pipeline-02-ocr-stt-processing.md §4.3: worker->backend
     * heartbeat, {@code queued -> running} (first heartbeat) or lease
     * refresh (subsequent ones). Bypasses {@link
     * DigitizationRunLifecycleService#transitionProcessingStatus} on purpose
     * (see that spec section: heartbeats never touch {@code
     * document_digitization_status}, to avoid a DB write every 30s on a
     * heavily-read table). The {@code WHERE processing_status IN (...)} guard
     * makes a heartbeat for an already-terminal run (e.g. a very late/
     * duplicate one after the result was already processed) a silent no-op.
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE DigitizationRun r SET r.processingStatus = 'running', r.workerId = :workerId, "
            + "r.leaseExpiresAt = :leaseExpiresAt WHERE r.id = :runId AND r.processingStatus IN ('queued', 'running')")
    int heartbeat(
            @Param("runId") UUID runId, @Param("workerId") String workerId,
            @Param("leaseExpiresAt") OffsetDateTime leaseExpiresAt);

    /**
     * digitization-pipeline-02-ocr-stt-processing.md mục 0 điểm 5 (Story 6
     * AC-1/AC-2) - the sole idempotency guard for {@code
     * DigitizationRunLifecycleServiceImpl#completeRunSuccess}: an atomic,
     * single-statement conditional UPDATE rather than a
     * read-then-compare-then-write, so two concurrent deliveries for the same
     * {@code runId} can never both "win" (whichever's UPDATE commits first
     * makes the other's {@code WHERE} clause match 0 rows).
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE DigitizationRun r SET r.processingStatus = :newStatus, r.updatedAt = :now "
            + "WHERE r.id = :runId AND r.processingStatus IN ('queued', 'running')")
    int conditionallyTransitionProcessingStatus(
            @Param("runId") UUID runId, @Param("newStatus") String newStatus, @Param("now") OffsetDateTime now);

    /**
     * Same idempotency guard as {@link #conditionallyTransitionProcessingStatus},
     * used by {@code completeRunFailure} instead - a failure's *final*
     * {@code processing_status} (retry {@code queued} vs {@code
     * failed_permanent}) depends on {@code attempt_count}, only knowable
     * after this claim has taken the row lock, so this variant only touches
     * {@code updated_at} as the claim marker; the caller sets the real final
     * state in the same transaction right after.
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE DigitizationRun r SET r.updatedAt = :now "
            + "WHERE r.id = :runId AND r.processingStatus IN ('queued', 'running')")
    int claimActiveForCompletion(@Param("runId") UUID runId, @Param("now") OffsetDateTime now);

    /**
     * digitization-pipeline-02-ocr-stt-processing.md §4.4 (Story 3 AC-4): rows
     * a worker died mid-job on - {@code running} with an expired lease.
     * {@code FOR UPDATE SKIP LOCKED} so two overlapping runs of {@link
     * com.aismartstore.backend.digitization.DigitizationRunLeaseReconciliationJob}
     * (e.g. during a slow scan) never double-requeue the same row.
     */
    @Query(
            value = "SELECT * FROM digitization_run WHERE processing_status = 'running' "
                    + "AND lease_expires_at < :cutoff ORDER BY lease_expires_at LIMIT :limit FOR UPDATE SKIP LOCKED",
            nativeQuery = true)
    List<DigitizationRun> findExpiredLeasesForUpdate(@Param("cutoff") OffsetDateTime cutoff, @Param("limit") int limit);

    /**
     * digitization-review-01-ocr.md §3.2/§4 bước 7 (Story 8): the other
     * "active" run for a {@code (document_id, task)} pair that is already
     * {@code confirmed} - at most one can exist thanks to {@code
     * UQ_digitization_run_active}. Used both for {@code RunDetail.
     * previousConfirmedRunId} and by {@code confirmRun} to know which run to
     * supersede (US8 AC-2).
     */
    Optional<DigitizationRun> findByDocumentIdAndTaskAndReviewState(
            UUID documentId, String task, String reviewState);

    /**
     * search-fulltext-04-dong-bo-chi-muc.md §4.3 - every confirmed run of a
     * document across all tasks (ocr/stt/sed/summary), for {@code
     * SearchIndexReconciliationJob}'s expected-segment-count computation.
     */
    List<DigitizationRun> findByDocumentIdAndReviewState(UUID documentId, String reviewState);

    /**
     * search-fulltext-04-dong-bo-chi-muc.md §4.3 - paginated distinct
     * document ids with at least one confirmed run, the reconciliation
     * job's iteration set. trash-search-index-cleanup.md §4.6 - excludes
     * documents already trashed ({@code document.deleted_at IS NOT NULL}):
     * their {@code content_segment} rows still exist in Postgres (trash is a
     * soft delete) but their OpenSearch footprint is now correctly empty
     * (§2 - {@code handleDocumentTrashed} deletes it outright), so comparing
     * them here would be a permanent false-positive drift for every trashed
     * document still inside its retention window. Grepped for other callers
     * before editing in place (§7 rủi ro) - {@code
     * SearchIndexReconciliationJob} is the only one.
     */
    @Query("SELECT DISTINCT r.documentId FROM DigitizationRun r WHERE r.reviewState = :reviewState "
            + "AND EXISTS (SELECT 1 FROM Document d WHERE d.id = r.documentId AND d.deletedAt IS NULL) "
            + "ORDER BY r.documentId")
    Page<UUID> findDistinctDocumentIdByReviewState(@Param("reviewState") String reviewState, Pageable pageable);

    /**
     * digitization-review-01-ocr.md §3.4/§4 bước 5 (Quyết định 1 SA, US5) -
     * used by {@code AutoConfirmScheduler}: a single {@code UPDATE ...
     * RETURNING}, atomically claiming up to {@code batchSize} {@code
     * pending} runs whose {@code review_deadline_at} has passed and are not
     * {@code auto_confirm_blocked} (US5 AC-1) - same {@code WHERE id IN
     * (... FOR UPDATE SKIP LOCKED)} idiom as {@code
     * SourceSeparationJobRepository#claimNextBatch} (now legacy/unused, see
     * digitization-pipeline-04-video-no-separation.md §1), so two overlapping
     * scheduler ticks never double-confirm the same row. Deliberately
     * {@code review_state = 'pending'} only (not {@code in_review}) - a run
     * someone has already started editing is left for {@code
     * ReviewStaleAlertJob} instead (mục 0 điểm... see spec §4 bước 5/6).
     */
    @Query(
            value = "UPDATE digitization_run SET review_state = 'confirmed', confirm_source = 'auto', "
                    + "confirmed_at = now(), updated_at = now() "
                    + "WHERE id IN ("
                    + "  SELECT id FROM digitization_run "
                    + "  WHERE review_state = 'pending' AND auto_confirm_blocked = false "
                    + "    AND review_deadline_at IS NOT NULL AND review_deadline_at <= now() "
                    + "  ORDER BY review_deadline_at "
                    + "  LIMIT :batchSize "
                    + "  FOR UPDATE SKIP LOCKED"
                    + ") "
                    + "RETURNING *",
            nativeQuery = true)
    List<DigitizationRun> autoConfirmDueBatch(@Param("batchSize") int batchSize);

    /**
     * digitization-review-01-ocr.md §3.4/§4 bước 6 (Quyết định 2 SA, Story
     * 6) - used by {@code ReviewStaleAlertJob}: atomically claims up to
     * {@code batchSize} {@code in_review} runs stuck past {@code cutoff}
     * (i.e. {@code first_edit_at <= now() - review_stale_hours}, computed by
     * the caller) that have not already been flagged, setting {@code
     * stale_alerted = true} so the next tick does not re-select (and
     * re-audit) the same row. Same {@code FOR UPDATE SKIP LOCKED} idiom as
     * {@link #autoConfirmDueBatch}.
     */
    @Query(
            value = "UPDATE digitization_run SET stale_alerted = true, updated_at = now() "
                    + "WHERE id IN ("
                    + "  SELECT id FROM digitization_run "
                    + "  WHERE review_state = 'in_review' AND stale_alerted = false "
                    + "    AND first_edit_at IS NOT NULL AND first_edit_at <= :cutoff "
                    + "  ORDER BY first_edit_at "
                    + "  LIMIT :batchSize "
                    + "  FOR UPDATE SKIP LOCKED"
                    + ") "
                    + "RETURNING *",
            nativeQuery = true)
    List<DigitizationRun> flagStaleInReviewBatch(@Param("cutoff") OffsetDateTime cutoff, @Param("batchSize") int batchSize);
}
