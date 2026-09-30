package com.aismartstore.backend.digitization.review;

import java.util.UUID;

/**
 * digitization-review-04-sed-summary.md §3.2 - one resolved element of a
 * {@code video_summary} segment's {@code source_segment_ids} (§2.2), as
 * exposed on {@link SegmentView#sourceReferences()}. {@code
 * sourceRunReviewState} can be any value including {@code superseded} (Story
 * 10 AC-4) - the deep-link always opens the exact run the summary was built
 * from, never auto-resolving to a newer one.
 */
public record SourceReferenceView(
        UUID sourceSegmentId, UUID sourceRunId, int segmentIndex, Integer timeStartMs, String sourceRunReviewState) {
}
