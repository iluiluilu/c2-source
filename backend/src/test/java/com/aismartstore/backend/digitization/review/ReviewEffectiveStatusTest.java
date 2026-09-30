package com.aismartstore.backend.digitization.review;

import static org.assertj.core.api.Assertions.assertThat;

import com.aismartstore.backend.digitization.results.DigitizationRun;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;

/** digitization-review-01-ocr.md §3.1: {@code effectiveStatus} boundary conditions. */
class ReviewEffectiveStatusTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-09-08T00:00:00Z");

    @Test
    void inReview_belowStaleThreshold_staysInReview() {
        OffsetDateTime firstEditAt = NOW.minusHours(71); // < 72h
        assertThat(ReviewEffectiveStatus.compute(
                DigitizationRun.REVIEW_IN_REVIEW, firstEditAt, null, NOW, 72, 4))
                .isEqualTo(ReviewEffectiveStatus.IN_REVIEW);
    }

    @Test
    void inReview_exactlyAtStaleThreshold_isStale() {
        OffsetDateTime firstEditAt = NOW.minusHours(72); // spec: "<=" - boundary counts as stale
        assertThat(ReviewEffectiveStatus.compute(
                DigitizationRun.REVIEW_IN_REVIEW, firstEditAt, null, NOW, 72, 4))
                .isEqualTo(ReviewEffectiveStatus.STALE);
    }

    @Test
    void inReview_noFirstEditAt_neverStale() {
        assertThat(ReviewEffectiveStatus.compute(DigitizationRun.REVIEW_IN_REVIEW, null, null, NOW, 72, 4))
                .isEqualTo(ReviewEffectiveStatus.IN_REVIEW);
    }

    @Test
    void pending_beyondUpcomingDeadlineWindow_staysPending() {
        OffsetDateTime deadline = NOW.plusHours(5); // > 4h window
        assertThat(ReviewEffectiveStatus.compute(DigitizationRun.REVIEW_PENDING, null, deadline, NOW, 72, 4))
                .isEqualTo(ReviewEffectiveStatus.PENDING);
    }

    @Test
    void pending_withinUpcomingDeadlineWindow_isUpcomingDeadline() {
        OffsetDateTime deadline = NOW.plusHours(4); // spec: "<=" - boundary counts
        assertThat(ReviewEffectiveStatus.compute(DigitizationRun.REVIEW_PENDING, null, deadline, NOW, 72, 4))
                .isEqualTo(ReviewEffectiveStatus.UPCOMING_DEADLINE);
    }

    @Test
    void pending_deadlineAlreadyPast_isUpcomingDeadline() {
        OffsetDateTime deadline = NOW.minusHours(1);
        assertThat(ReviewEffectiveStatus.compute(DigitizationRun.REVIEW_PENDING, null, deadline, NOW, 72, 4))
                .isEqualTo(ReviewEffectiveStatus.UPCOMING_DEADLINE);
    }

    @Test
    void confirmed_isReturnedAsIs() {
        assertThat(ReviewEffectiveStatus.compute(DigitizationRun.REVIEW_CONFIRMED, null, null, NOW, 72, 4))
                .isEqualTo(ReviewEffectiveStatus.CONFIRMED);
    }
}
