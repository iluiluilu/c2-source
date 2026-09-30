package com.aismartstore.backend.systemconfig;

import java.util.Map;

/**
 * Per-key range checks and cross-parameter invariants (Story 2 AC-2/AC-3).
 * Kept separate from the service so the rules can be unit-tested in isolation.
 *
 * <p>Single-key checks run against the value being written; cross-parameter
 * checks compare the proposed value against the current effective value of a
 * related key (a {@code currentValues} lookup the service supplies, resolved
 * from DB-or-default). Violations throw {@link ConstraintViolationException},
 * mapped to {@code 400 CONSTRAINT_VIOLATION}.
 */
public final class SystemConfigValidator {

    /**
     * Loose 5-field cron shape check (minute hour day-of-month month
     * day-of-week). Not a full cron grammar - just enough to catch obviously
     * malformed schedules before they are saved (Story 2 AC-2/AC-3 for
     * {@code backup.schedule_cron}).
     */
    private static final java.util.regex.Pattern CRON_FIELD =
            java.util.regex.Pattern.compile("^(\\*|[0-9]+)(/[0-9]+)?((,|-)(\\*|[0-9]+)(/[0-9]+)?)*$");

    /** Thrown when a value is out of range or breaks a cross-parameter rule. */
    public static class ConstraintViolationException extends RuntimeException {
        public ConstraintViolationException(String message) {
            super(message);
        }
    }

    private SystemConfigValidator() {
    }

    /**
     * Validate {@code proposed} for {@code key}.
     *
     * @param key           the key being changed
     * @param proposed      the already-type-converted proposed value
     * @param currentValues resolver returning the current effective
     *                      {@link SystemConfigValue} for any related key (used
     *                      by cross-parameter rules); never returns null for a
     *                      known key because callers fall back to defaults
     */
    public static void validate(
            String key,
            SystemConfigValue proposed,
            java.util.function.Function<String, SystemConfigValue> currentValues) {

        validateSingle(key, proposed);
        validateCrossParameter(key, proposed, currentValues);
    }

    private static void validateSingle(String key, SystemConfigValue v) {
        switch (key) {
            case SystemConfigDefaults.RETENTION_TRASH_RETENTION_DAYS,
                    SystemConfigDefaults.INGEST_MAX_FILES_PER_BATCH,
                    SystemConfigDefaults.OCR_STT_AUTO_CONFIRM_HOURS,
                    SystemConfigDefaults.OCR_STT_MAX_RETRY,
                    SystemConfigDefaults.OCR_STT_SEGMENT_MAX_SECONDS,
                    SystemConfigDefaults.AI_QUEUE_CONCURRENCY,
                    SystemConfigDefaults.AI_QUEUE_BATCH_SIZE,
                    SystemConfigDefaults.CLASSIFICATION_OUTBOX_RETENTION_DAYS,
                    SystemConfigDefaults.SEARCH_MAX_RESULTS_PER_PAGE,
                    SystemConfigDefaults.SEARCH_QUERY_TIMEOUT_MS -> requirePositive(key, v.asLong());

            case SystemConfigDefaults.STORAGE_QUOTA_DEFAULT_CAN_BO,
                    SystemConfigDefaults.STORAGE_QUOTA_DEFAULT_LANH_DAO,
                    SystemConfigDefaults.STORAGE_QUOTA_DEFAULT_QUAN_TRI,
                    SystemConfigDefaults.INGEST_MAX_BATCH_SIZE_BYTES,
                    SystemConfigDefaults.INGEST_MAX_FILE_SIZE_BYTES -> requirePositive(key, v.asLong());

            case SystemConfigDefaults.WAREHOUSE_STORAGE_WARNING_THRESHOLD_PERCENT,
                    SystemConfigDefaults.WAREHOUSE_STORAGE_BLOCK_THRESHOLD_PERCENT,
                    SystemConfigDefaults.OCR_STT_MIN_CONFIDENCE_OCR_PERCENT,
                    SystemConfigDefaults.OCR_STT_MIN_CONFIDENCE_STT_PERCENT,
                    SystemConfigDefaults.OCR_STT_POOR_RATIO_THRESHOLD_PERCENT,
                    SystemConfigDefaults.OCR_STT_NO_SPEECH_PROB_THRESHOLD_PERCENT ->
                    requirePercent(key, v.asLong());

            case SystemConfigDefaults.BACKUP_SCHEDULE_CRON -> requireCronShape(key, v.asString());

            default -> {
                // No specific single-key rule; the type conversion already
                // guaranteed the value parses for its declared value_type.
            }
        }
    }

    private static void validateCrossParameter(
            String key,
            SystemConfigValue proposed,
            java.util.function.Function<String, SystemConfigValue> currentValues) {

        switch (key) {
            case SystemConfigDefaults.WAREHOUSE_STORAGE_WARNING_THRESHOLD_PERCENT -> {
                long warning = proposed.asLong();
                long block = currentValues
                        .apply(SystemConfigDefaults.WAREHOUSE_STORAGE_BLOCK_THRESHOLD_PERCENT)
                        .asLong();
                if (warning >= block) {
                    throw new ConstraintViolationException(
                            "Ngưỡng cảnh báo (" + warning + "%) phải nhỏ hơn ngưỡng chặn (" + block + "%)");
                }
            }
            case SystemConfigDefaults.WAREHOUSE_STORAGE_BLOCK_THRESHOLD_PERCENT -> {
                long block = proposed.asLong();
                long warning = currentValues
                        .apply(SystemConfigDefaults.WAREHOUSE_STORAGE_WARNING_THRESHOLD_PERCENT)
                        .asLong();
                if (warning >= block) {
                    throw new ConstraintViolationException(
                            "Ngưỡng chặn (" + block + "%) phải lớn hơn ngưỡng cảnh báo (" + warning + "%)");
                }
            }
            case SystemConfigDefaults.INGEST_MAX_FILE_SIZE_BYTES -> {
                long perFile = proposed.asLong();
                long perBatch = currentValues
                        .apply(SystemConfigDefaults.INGEST_MAX_BATCH_SIZE_BYTES)
                        .asLong();
                if (perFile > perBatch) {
                    throw new ConstraintViolationException(
                            "Dung lượng tối đa mỗi tệp (" + perFile + " byte) không được lớn hơn tổng dung lượng"
                                    + " mỗi lượt tải (" + perBatch + " byte)");
                }
            }
            case SystemConfigDefaults.INGEST_MAX_BATCH_SIZE_BYTES -> {
                long perBatch = proposed.asLong();
                long perFile = currentValues
                        .apply(SystemConfigDefaults.INGEST_MAX_FILE_SIZE_BYTES)
                        .asLong();
                if (perFile > perBatch) {
                    throw new ConstraintViolationException(
                            "Tổng dung lượng mỗi lượt tải (" + perBatch + " byte) không được nhỏ hơn dung lượng"
                                    + " tối đa mỗi tệp (" + perFile + " byte)");
                }
            }
            default -> {
                // no cross-parameter rule for this key
            }
        }
    }

    private static void requirePositive(String key, long value) {
        if (value <= 0) {
            throw new ConstraintViolationException(key + " phải là số nguyên dương");
        }
    }

    private static void requirePercent(String key, long value) {
        if (value < 0 || value > 100) {
            throw new ConstraintViolationException(key + " phải nằm trong khoảng 0–100");
        }
    }

    private static void requireCronShape(String key, String value) {
        String[] fields = value == null ? new String[0] : value.trim().split("\\s+");
        if (fields.length != 5) {
            throw new ConstraintViolationException(
                    key + " phải là biểu thức cron 5 trường (phút giờ ngày tháng thứ)");
        }
        for (String field : fields) {
            if (!CRON_FIELD.matcher(field).matches()) {
                throw new ConstraintViolationException(key + " có trường cron không hợp lệ: '" + field + "'");
            }
        }
    }

    /** Convenience for callers that hold values in a plain map (e.g. tests). */
    public static java.util.function.Function<String, SystemConfigValue> lookupFrom(
            Map<String, SystemConfigValue> values) {
        return values::get;
    }
}
