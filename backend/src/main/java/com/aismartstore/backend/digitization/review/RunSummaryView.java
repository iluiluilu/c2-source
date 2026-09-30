package com.aismartstore.backend.digitization.review;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * digitization-review-01-ocr.md §3.1 - one row of the review queue ({@code
 * GET /digitization-review/queue}) and the base shape of {@code RunDetail}
 * (§3.2, see {@link RunDetailView}). {@code effectiveStatus} is computed by
 * {@link ReviewQueueRepository} at query time (never a DB column - §3.1).
 */
public record RunSummaryView(
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
        /**
         * digitization-review-06-queue-pending-status.md §2/§4 - raw {@code
         * digitization_run.processing_status} (one of {@code
         * DigitizationRun.PROCESSING_*}), always populated regardless of
         * {@code effectiveStatus}; the frontend only reads it when {@code
         * effectiveStatus == "pending"} to pick a finer-grained badge.
         */
        String processingStatus) {
}
