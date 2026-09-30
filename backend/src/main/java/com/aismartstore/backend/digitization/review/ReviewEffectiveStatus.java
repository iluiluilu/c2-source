package com.aismartstore.backend.digitization.review;

import com.aismartstore.backend.digitization.results.DigitizationRun;
import java.time.OffsetDateTime;

/**
 * digitization-review-01-ocr.md §3.1: {@code effectiveStatus} is a computed
 * label, never a DB column - {@code stale}/{@code upcoming_deadline} are a
 * refinement of {@code in_review}/{@code pending} respectively, so every run
 * has exactly one of {@code pending|in_review|stale|upcoming_deadline|
 * confirmed} at any instant. Used by {@link ReviewController} for the
 * single-row {@code GET /runs/{runId}} path (Java, one row - no need for
 * SQL); {@link ReviewQueueRepository} re-implements the same two boundary
 * conditions as a SQL {@code CASE} expression for the paginated queue query
 * (kept in sync by hand - see that class's Javadoc), since that path filters/
 * sorts across a whole table and cannot afford to fetch every row into Java
 * first.
 */
public final class ReviewEffectiveStatus {

    public static final String PENDING = DigitizationRun.REVIEW_PENDING;
    public static final String IN_REVIEW = DigitizationRun.REVIEW_IN_REVIEW;
    public static final String STALE = "stale";
    public static final String UPCOMING_DEADLINE = "upcoming_deadline";
    public static final String CONFIRMED = DigitizationRun.REVIEW_CONFIRMED;

    private ReviewEffectiveStatus() {
    }

    /**
     * @param staleHours {@code ocr_stt.review_stale_hours}
     * @param upcomingDeadlineHours {@code ocr_stt.review_upcoming_deadline_hours}
     */
    public static String compute(
            String reviewState,
            OffsetDateTime firstEditAt,
            OffsetDateTime reviewDeadlineAt,
            OffsetDateTime now,
            long staleHours,
            long upcomingDeadlineHours) {
        if (IN_REVIEW.equals(reviewState)) {
            if (firstEditAt != null && !firstEditAt.isAfter(now.minusHours(staleHours))) {
                return STALE;
            }
            return IN_REVIEW;
        }
        if (PENDING.equals(reviewState)) {
            if (reviewDeadlineAt != null && !reviewDeadlineAt.isAfter(now.plusHours(upcomingDeadlineHours))) {
                return UPCOMING_DEADLINE;
            }
            return PENDING;
        }
        // confirmed/superseded/void/not_applicable - not expected on the review
        // queue/detail screens (§4 bước 7 excludes them from the queue query),
        // but returned as-is rather than throwing so a caller never crashes on
        // an unexpected review_state.
        return reviewState;
    }
}
