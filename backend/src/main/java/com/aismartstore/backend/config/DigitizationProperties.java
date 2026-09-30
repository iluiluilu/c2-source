package com.aismartstore.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds {@code app.digitization.*} (digitization-pipeline-01-source-dispatch.md
 * §4.6).
 *
 * <p>{@code sedPromotionThreshold} - digitization-results-store.md Task 6:
 * TODO giá trị tạm ({@code 0.85}), chưa có ngưỡng chính thức (spec §7 rủi ro
 * 4) - phải sửa trước khi bật feature flag SED thật.
 * {@code statusReconciliationIntervalMs} - Task 7, cùng mẫu {@code
 * dispatchRetryIntervalMs}.
 *
 * <p>{@code leaseCheckIntervalMs} - digitization-pipeline-02-ocr-stt-processing.md
 * Task 7 ({@code DigitizationRunLeaseReconciliationJob}, §4.4). {@code
 * dlqAlertIntervalMs} - Task 9 ({@code DigitizationDlqAlertJob}, §4.6).
 */
@ConfigurationProperties(prefix = "app.digitization")
public record DigitizationProperties(
        long dispatchRetryIntervalMs,
        int dispatchRetryBatchSize,
        double sedPromotionThreshold,
        long statusReconciliationIntervalMs,
        int statusReconciliationBatchSize,
        long leaseCheckIntervalMs,
        int leaseCheckBatchSize,
        long dlqAlertIntervalMs) {
}
