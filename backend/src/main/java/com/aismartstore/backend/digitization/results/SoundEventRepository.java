package com.aismartstore.backend.digitization.results;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SoundEventRepository extends JpaRepository<SoundEvent, UUID> {

    List<SoundEvent> findByRunId(UUID runId);

    /**
     * digitization-review-04-sed-summary.md §2.1/§4 bước 1 (AC-1/AC-3 Story 9):
     * bulk lookup for {@code GET /runs/{id}} - one query for a whole page of
     * {@code content_source='sed'} segments instead of one per segment.
     * Guaranteed at most one row per id (1-1, {@code
     * UQ_sound_event_promoted_segment}).
     */
    List<SoundEvent> findByPromotedSegmentIdIn(List<UUID> promotedSegmentIds);

    /**
     * digitization-results-store.md §7 rủi ro 4: starting point for a future
     * re-promotion job if {@code digitization.sed.promotion-threshold}
     * changes after detections already exist - detections at/above the new
     * threshold that were never promoted (job itself is out of scope of this
     * spec).
     */
    @Query("SELECT se FROM SoundEvent se WHERE se.runId = :runId AND se.promotedSegmentId IS NULL "
            + "AND se.confidence >= :threshold")
    List<SoundEvent> findUnpromotedAboveThreshold(
            @Param("runId") UUID runId, @Param("threshold") BigDecimal threshold);
}
