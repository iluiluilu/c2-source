package com.aismartstore.backend.digitization;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * digitization-pipeline-04-video-no-separation.md §1/task 2: legacy,
 * read/delete-only as of this spec - {@link SourceSeparationJob} is no
 * longer written to (video intake creates {@code digitization_source}
 * directly), and this repository's write-side query below is unused now
 * that its poller has been removed. Kept for historical rows and {@code
 * TrashPurgeService#purge}.
 */
public interface SourceSeparationJobRepository extends JpaRepository<SourceSeparationJob, UUID> {

    boolean existsByDocumentId(UUID documentId);

    /** delete-restore-trash-02-xoa-cung-xung-dot.md §4.2 bước 2 ({@code TrashPurgeService}): see {@code DocumentTagRepository#deleteByDocumentId}'s javadoc for why this is explicit. */
    @Modifying
    int deleteByDocumentId(UUID documentId);

    /**
     * digitization-pipeline-01-source-dispatch.md §4.2 step 2: atomically
     * claims up to {@code limit} jobs (queued, or running past {@code
     * runningCutoff} - treated as orphaned by a JVM that died mid-job) by
     * flipping them to {@code running} in the same statement as the {@code
     * FOR UPDATE SKIP LOCKED} select (a single-statement {@code UPDATE ...
     * RETURNING}, executed as its own implicit Postgres transaction) - lets
     * multiple pollers run concurrently without either double-claiming a row
     * or needing an application-level transaction boundary around the
     * select+update pair (see the Spring self-invocation pitfall this
     * deliberately avoided: a two-step {@code @Transactional} claim method
     * called from the poller's own {@code run()} in the same class would
     * silently run with no transaction at all).
     *
     * <p>Legacy as of digitization-pipeline-04-video-no-separation.md §1:
     * its poller ({@code SourceSeparationJobRunner}) was removed, so this
     * query is no longer called in production - kept only as the {@code
     * FOR UPDATE SKIP LOCKED} idiom reference cited by {@code
     * DigitizationRunRepository}/{@code DocumentRepository}'s own claim
     * queries.
     */
    @Query(
            value = "UPDATE source_separation_job SET status = 'running', updated_at = now() "
                    + "WHERE id IN ("
                    + "  SELECT id FROM source_separation_job "
                    + "  WHERE status = 'queued' OR (status = 'running' AND updated_at < :runningCutoff) "
                    + "  ORDER BY created_at "
                    + "  LIMIT :limit "
                    + "  FOR UPDATE SKIP LOCKED"
                    + ") "
                    + "RETURNING *",
            nativeQuery = true)
    List<SourceSeparationJob> claimNextBatch(
            @Param("runningCutoff") OffsetDateTime runningCutoff, @Param("limit") int limit);
}
