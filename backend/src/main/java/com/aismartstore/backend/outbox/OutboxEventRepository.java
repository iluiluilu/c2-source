package com.aismartstore.backend.outbox;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    /** classification-notes-01-tags.md §4.6 step 3: backlog check (oldest unprocessed row). */
    Optional<OutboxEvent> findFirstByProcessedAtIsNullOrderByCreatedAtAsc();

    /**
     * search-fulltext-04-dong-bo-chi-muc.md §4.2 - {@code
     * SearchIndexOutboxPoller}'s own claim query: one unprocessed row of a
     * type it understands, {@code document.acl.changed} always ahead of
     * every other type ("hàng đợi ưu tiên cao", QĐ5 SA), FIFO by {@code id}
     * within each of those two priority tiers. {@code FOR UPDATE SKIP
     * LOCKED} so a second poller instance (horizontal scaling) never blocks
     * on, or double-processes, the same row.
     *
     * <p>§4.4.1 (2026-09-18) - {@code attempts < :maxAttempts} is what keeps
     * one permanently-failing row from wedging the entire queue. Without it
     * there is no attempts filter, no backoff and no DLQ anywhere on this
     * path, and {@code processed_at} is only ever set on success - so a row
     * that always fails stays at the head of this ORDER BY forever and
     * <b>every event behind it is never claimed again</b>. Observed for real
     * on stg 2026-09-18: one {@code document.acl.changed} retried 19,200
     * times while 12 events queued behind it made zero progress for 17
     * minutes, and {@code /search} kept answering 200 OK off a silently
     * frozen index the whole time.
     *
     * <p>A row past the threshold is <b>parked</b>, not dropped: it keeps
     * {@code processed_at IS NULL} plus its {@code last_error} as evidence,
     * and {@code OutboxRetentionJob} (which only deletes rows with {@code
     * processed_at IS NOT NULL}) will not clean it away. It is simply no
     * longer claimable, so the queue moves on. Parking is a deliberate
     * trade - "one document missing from the index, loudly" instead of "the
     * whole index frozen, silently" - and it is only safe because {@link
     * #countParkedForIndexing}/{@link #findParkedIdsForIndexing} give
     * operations a way to find these rows (§4.4.4). A real DLQ with backoff
     * is still owed once the shared outbox-relay exists (§7).
     */
    @Query(
            value = "SELECT * FROM outbox_event WHERE event_type IN (:eventTypes) AND processed_at IS NULL "
                    + "AND attempts < :maxAttempts "
                    + "ORDER BY (event_type = 'document.acl.changed') DESC, id ASC LIMIT 1 FOR UPDATE SKIP LOCKED",
            nativeQuery = true)
    Optional<OutboxEvent> claimNextForIndexing(
            @Param("eventTypes") List<String> eventTypes, @Param("maxAttempts") int maxAttempts);

    /**
     * search-fulltext-04-dong-bo-chi-muc.md §4.4.4 - how many rows {@link
     * #claimNextForIndexing} has given up on. Read by {@code
     * SearchIndexReconciliationJob}; a non-zero count means the index is
     * knowingly missing data and needs a human.
     */
    @Query(
            value = "SELECT count(*) FROM outbox_event WHERE event_type IN (:eventTypes) "
                    + "AND processed_at IS NULL AND attempts >= :maxAttempts",
            nativeQuery = true)
    long countParkedForIndexing(@Param("eventTypes") List<String> eventTypes, @Param("maxAttempts") int maxAttempts);

    /** §4.4.4 - the first {@code :limit} parked ids, so the alert names something actionable. */
    @Query(
            value = "SELECT id FROM outbox_event WHERE event_type IN (:eventTypes) "
                    + "AND processed_at IS NULL AND attempts >= :maxAttempts ORDER BY id LIMIT :limit",
            nativeQuery = true)
    List<Long> findParkedIdsForIndexing(
            @Param("eventTypes") List<String> eventTypes,
            @Param("maxAttempts") int maxAttempts,
            @Param("limit") int limit);

    /**
     * classification-notes-01-tags.md §4.6 step 3 (Task 9b): only rows
     * already {@code processed_at IS NOT NULL} and past the retention
     * window are deleted - an unprocessed row is never touched here.
     */
    @Modifying
    @Query("delete from OutboxEvent o where o.processedAt is not null and o.processedAt < :cutoff")
    int deleteProcessedBefore(@Param("cutoff") OffsetDateTime cutoff);
}
