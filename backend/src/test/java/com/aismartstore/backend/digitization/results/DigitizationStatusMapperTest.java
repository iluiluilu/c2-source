package com.aismartstore.backend.digitization.results;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * digitization-results-store.md §5 ("overall_status: dựng 4 cột task ở nhiều
 * tổ hợp giá trị, xác nhận overall_status tính đúng theo thứ tự ưu tiên"),
 * US4 AC-2. Pure unit test of the Java mirror of the priority order also
 * implemented in SQL by {@link DocumentDigitizationStatusRepository#upsertTaskStatus}
 * - {@code DigitizationResultsLifecycleServiceIT#overallStatusPriorityAppliesEndToEnd}
 * cross-checks the two agree against a real DB.
 */
class DigitizationStatusMapperTest {

    private static final String NA = DocumentDigitizationStatus.STATUS_NOT_APPLICABLE;
    private static final String QUEUED = DocumentDigitizationStatus.STATUS_QUEUED;
    private static final String PROCESSING = DocumentDigitizationStatus.STATUS_PROCESSING;
    private static final String FAILED = DocumentDigitizationStatus.STATUS_FAILED;
    private static final String PENDING_REVIEW = DocumentDigitizationStatus.STATUS_PENDING_REVIEW;
    private static final String IN_REVIEW = DocumentDigitizationStatus.STATUS_IN_REVIEW;
    private static final String CONFIRMED = DocumentDigitizationStatus.STATUS_CONFIRMED;

    static Stream<org.junit.jupiter.params.provider.Arguments> priorityCases() {
        return Stream.of(
                arguments(NA, NA, NA, NA, NA),
                arguments(FAILED, CONFIRMED, CONFIRMED, CONFIRMED, FAILED),
                arguments(CONFIRMED, CONFIRMED, CONFIRMED, FAILED, FAILED),
                arguments(PROCESSING, QUEUED, CONFIRMED, NA, PROCESSING),
                arguments(QUEUED, CONFIRMED, NA, NA, QUEUED),
                arguments(IN_REVIEW, PENDING_REVIEW, CONFIRMED, NA, IN_REVIEW),
                arguments(PENDING_REVIEW, CONFIRMED, NA, NA, PENDING_REVIEW),
                arguments(CONFIRMED, CONFIRMED, CONFIRMED, CONFIRMED, CONFIRMED),
                arguments(CONFIRMED, NA, NA, NA, CONFIRMED));
    }

    @ParameterizedTest
    @MethodSource("priorityCases")
    void overallStatusRespectsThePriorityOrder(
            String ocr, String stt, String sed, String videoSummary, String expected) {
        assertThat(DigitizationStatusMapper.overallStatus(ocr, stt, sed, videoSummary)).isEqualTo(expected);
    }

    @Test
    void activeRunTaskStatus_succeededDefersToReviewState() {
        assertThat(DigitizationStatusMapper.activeRunTaskStatus(
                        DigitizationRun.PROCESSING_SUCCEEDED, DigitizationRun.REVIEW_PENDING))
                .isEqualTo(PENDING_REVIEW);
        assertThat(DigitizationStatusMapper.activeRunTaskStatus(
                        DigitizationRun.PROCESSING_SUCCEEDED, DigitizationRun.REVIEW_IN_REVIEW))
                .isEqualTo(IN_REVIEW);
        assertThat(DigitizationStatusMapper.activeRunTaskStatus(
                        DigitizationRun.PROCESSING_SUCCEEDED, DigitizationRun.REVIEW_CONFIRMED))
                .isEqualTo(CONFIRMED);
    }

    @Test
    void activeRunTaskStatus_queuedRunningFailedMapDirectly() {
        assertThat(DigitizationStatusMapper.activeRunTaskStatus(DigitizationRun.PROCESSING_QUEUED, NA)).isEqualTo(QUEUED);
        assertThat(DigitizationStatusMapper.activeRunTaskStatus(DigitizationRun.PROCESSING_RUNNING, NA))
                .isEqualTo(PROCESSING);
        assertThat(DigitizationStatusMapper.activeRunTaskStatus(DigitizationRun.PROCESSING_FAILED_RETRYABLE, NA))
                .isEqualTo(FAILED);
        assertThat(DigitizationStatusMapper.activeRunTaskStatus(DigitizationRun.PROCESSING_FAILED_PERMANENT, NA))
                .isEqualTo(FAILED);
    }
}
