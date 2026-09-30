package com.aismartstore.backend.digitization.review;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * digitization-review-01-ocr.md §3.2 - {@code RunSummary} + fields specific
 * to {@code GET /digitization-review/runs/{runId}}. A flat record (not a
 * composed/{@code @JsonUnwrapped} wrapper around {@link RunSummaryView}) so
 * the JSON shape matches the spec's "RunSummary + ..." description exactly
 * without relying on Jackson unwrap semantics.
 *
 * <p>digitization-review-04-sed-summary.md §3.1 - {@code staleWarning} is
 * only ever {@code true} for {@code task=video_summary} (§2.3); always
 * {@code false} for {@code ocr}/{@code stt}/{@code sed}.
 */
public record RunDetailView(
        UUID runId,
        UUID documentId,
        String documentName,
        String task,
        String reviewState,
        String effectiveStatus,
        String outputQualityFlag,
        int lowConfidenceCount,
        int segmentCount,
        OffsetDateTime reviewDeadlineAt,
        OffsetDateTime firstEditAt,
        boolean isReplacingConfirmedRun,
        Integer sourceDurationMs,
        UUID previousConfirmedRunId,
        SegmentPageView segments,
        boolean staleWarning) {

    public static RunDetailView of(
            RunSummaryView summary, Integer sourceDurationMs, UUID previousConfirmedRunId, SegmentPageView segments,
            boolean staleWarning) {
        return new RunDetailView(
                summary.runId(), summary.documentId(), summary.documentName(), summary.task(),
                summary.reviewState(), summary.effectiveStatus(), summary.outputQualityFlag(),
                summary.lowConfidenceCount(), summary.segmentCount(), summary.reviewDeadlineAt(),
                summary.firstEditAt(), summary.isReplacingConfirmedRun(), sourceDurationMs,
                previousConfirmedRunId, segments, staleWarning);
    }
}
