package com.aismartstore.backend.digitization.results;

/**
 * digitization-results-store.md §4 bước 2/5 + §5 ("overall_status: ... xác
 * nhận overall_status tính đúng theo thứ tự ưu tiên") - the mapping from one
 * active {@link DigitizationRun}'s {@code processing_status}/{@code
 * review_state} to its {@code document_digitization_status} task column
 * value, and the priority order used to fold the 4 task columns into {@code
 * overall_status}. Shared by {@link DigitizationRunLifecycleServiceImpl}
 * (writes, via the native {@code upsertTaskStatus} query which recomputes
 * {@code overall_status} the same way in SQL) and {@link
 * DocumentDigitizationStatusReconciliationJob} (an independent Java-side
 * cross-check, so a bug in the SQL expression would also be caught).
 */
final class DigitizationStatusMapper {

    private DigitizationStatusMapper() {
    }

    /**
     * §4 bước 2: {@code queued}/{@code running} map directly;
     * {@code failed_retryable}/{@code failed_permanent} both map to {@code
     * failed}; {@code succeeded} defers to {@code review_state} (bước 5).
     * {@code cancelled} is not covered by the spec's mapping table - treated
     * as {@code not_applicable} (a cancelled run carries no pending work),
     * a DEV gap-filling decision, not a "chốt" one.
     */
    static String activeRunTaskStatus(String processingStatus, String reviewState) {
        return switch (processingStatus) {
            case DigitizationRun.PROCESSING_QUEUED -> DocumentDigitizationStatus.STATUS_QUEUED;
            case DigitizationRun.PROCESSING_RUNNING -> DocumentDigitizationStatus.STATUS_PROCESSING;
            case DigitizationRun.PROCESSING_FAILED_RETRYABLE, DigitizationRun.PROCESSING_FAILED_PERMANENT ->
                    DocumentDigitizationStatus.STATUS_FAILED;
            case DigitizationRun.PROCESSING_CANCELLED -> DocumentDigitizationStatus.STATUS_NOT_APPLICABLE;
            case DigitizationRun.PROCESSING_SUCCEEDED -> switch (reviewState) {
                case DigitizationRun.REVIEW_PENDING -> DocumentDigitizationStatus.STATUS_PENDING_REVIEW;
                case DigitizationRun.REVIEW_IN_REVIEW -> DocumentDigitizationStatus.STATUS_IN_REVIEW;
                case DigitizationRun.REVIEW_CONFIRMED -> DocumentDigitizationStatus.STATUS_CONFIRMED;
                default -> DocumentDigitizationStatus.STATUS_NOT_APPLICABLE;
            };
            default -> DocumentDigitizationStatus.STATUS_NOT_APPLICABLE;
        };
    }

    /**
     * US4 AC-2, chốt 2026-09-07: {@code failed > processing/queued >
     * in_review > pending_review > confirmed > not_applicable}. {@code
     * processing} over {@code queued} within that tier is a DEV refinement
     * (see {@link DocumentDigitizationStatusRepository#upsertTaskStatus}).
     *
     * @param taskStatuses one status per task column (ocr, stt, sed,
     *     video_summary, video - order does not matter)
     */
    static String overallStatus(String... taskStatuses) {
        if (isAny(DocumentDigitizationStatus.STATUS_FAILED, taskStatuses)) {
            return DocumentDigitizationStatus.STATUS_FAILED;
        }
        if (isAny(DocumentDigitizationStatus.STATUS_PROCESSING, taskStatuses)) {
            return DocumentDigitizationStatus.STATUS_PROCESSING;
        }
        if (isAny(DocumentDigitizationStatus.STATUS_QUEUED, taskStatuses)) {
            return DocumentDigitizationStatus.STATUS_QUEUED;
        }
        if (isAny(DocumentDigitizationStatus.STATUS_IN_REVIEW, taskStatuses)) {
            return DocumentDigitizationStatus.STATUS_IN_REVIEW;
        }
        if (isAny(DocumentDigitizationStatus.STATUS_PENDING_REVIEW, taskStatuses)) {
            return DocumentDigitizationStatus.STATUS_PENDING_REVIEW;
        }
        if (isAny(DocumentDigitizationStatus.STATUS_CONFIRMED, taskStatuses)) {
            return DocumentDigitizationStatus.STATUS_CONFIRMED;
        }
        return DocumentDigitizationStatus.STATUS_NOT_APPLICABLE;
    }

    private static boolean isAny(String target, String... values) {
        for (String value : values) {
            if (target.equals(value)) {
                return true;
            }
        }
        return false;
    }
}
