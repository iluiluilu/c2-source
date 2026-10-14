package com.aismartstore.backend.systemconfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class SystemConfigValidatorTest {

    /** Effective values for cross-parameter rules, seeded from defaults. */
    private Function<String, SystemConfigValue> lookup(Map<String, String> raw) {
        Map<String, SystemConfigValue> values = new HashMap<>();
        for (SystemConfigDef def : SystemConfigDefaults.all().values()) {
            String v = raw.getOrDefault(def.key(), def.defaultValue());
            values.put(def.key(), SystemConfigValue.fromStored(def.valueType(), v));
        }
        return values::get;
    }

    @Test
    void warningBelowBlockPasses() {
        assertThatCode(() -> SystemConfigValidator.validate(
                        SystemConfigDefaults.WAREHOUSE_STORAGE_WARNING_THRESHOLD_PERCENT,
                        SystemConfigValue.of(ValueType.NUMBER, 80),
                        lookup(Map.of())))
                .doesNotThrowAnyException();
    }

    @Test
    void warningNotBelowBlockIsRejected() {
        // block default is 95; propose warning=95 -> violation
        assertThatThrownBy(() -> SystemConfigValidator.validate(
                        SystemConfigDefaults.WAREHOUSE_STORAGE_WARNING_THRESHOLD_PERCENT,
                        SystemConfigValue.of(ValueType.NUMBER, 95),
                        lookup(Map.of())))
                .isInstanceOf(SystemConfigValidator.ConstraintViolationException.class);
    }

    @Test
    void blockNotAboveWarningIsRejected() {
        // warning default is 85; propose block=80 -> violation
        assertThatThrownBy(() -> SystemConfigValidator.validate(
                        SystemConfigDefaults.WAREHOUSE_STORAGE_BLOCK_THRESHOLD_PERCENT,
                        SystemConfigValue.of(ValueType.NUMBER, 80),
                        lookup(Map.of())))
                .isInstanceOf(SystemConfigValidator.ConstraintViolationException.class);
    }

    @Test
    void perFileLargerThanBatchIsRejected() {
        // batch default 5GB; propose per-file 6GB -> violation
        assertThatThrownBy(() -> SystemConfigValidator.validate(
                        SystemConfigDefaults.INGEST_MAX_FILE_SIZE_BYTES,
                        SystemConfigValue.of(ValueType.NUMBER, 6L * 1024 * 1024 * 1024),
                        lookup(Map.of())))
                .isInstanceOf(SystemConfigValidator.ConstraintViolationException.class);
    }

    @Test
    void percentOutOfRangeIsRejected() {
        assertThatThrownBy(() -> SystemConfigValidator.validate(
                        SystemConfigDefaults.WAREHOUSE_STORAGE_WARNING_THRESHOLD_PERCENT,
                        SystemConfigValue.of(ValueType.NUMBER, 150),
                        lookup(Map.of())))
                .isInstanceOf(SystemConfigValidator.ConstraintViolationException.class);
    }

    @Test
    void nonPositiveCountIsRejected() {
        assertThatThrownBy(() -> SystemConfigValidator.validate(
                        SystemConfigDefaults.INGEST_MAX_FILES_PER_BATCH,
                        SystemConfigValue.of(ValueType.NUMBER, 0),
                        lookup(Map.of())))
                .isInstanceOf(SystemConfigValidator.ConstraintViolationException.class);
    }

    @Test
    void validCountPasses() {
        assertThatCode(() -> SystemConfigValidator.validate(
                        SystemConfigDefaults.INGEST_MAX_FILES_PER_BATCH,
                        SystemConfigValue.of(ValueType.NUMBER, 1000),
                        lookup(Map.of())))
                .doesNotThrowAnyException();
    }

    @Test
    void validCronPasses() {
        assertThatCode(() -> SystemConfigValidator.validate(
                        SystemConfigDefaults.BACKUP_SCHEDULE_CRON,
                        SystemConfigValue.of(ValueType.STRING, "0 2 * * *"),
                        lookup(Map.of())))
                .doesNotThrowAnyException();
    }

    @Test
    void cronWithWrongFieldCountIsRejected() {
        assertThatThrownBy(() -> SystemConfigValidator.validate(
                        SystemConfigDefaults.BACKUP_SCHEDULE_CRON,
                        SystemConfigValue.of(ValueType.STRING, "0 2 * *"),
                        lookup(Map.of())))
                .isInstanceOf(SystemConfigValidator.ConstraintViolationException.class);
    }

    @Test
    void cronWithInvalidFieldIsRejected() {
        assertThatThrownBy(() -> SystemConfigValidator.validate(
                        SystemConfigDefaults.BACKUP_SCHEDULE_CRON,
                        SystemConfigValue.of(ValueType.STRING, "abc 2 * * *"),
                        lookup(Map.of())))
                .isInstanceOf(SystemConfigValidator.ConstraintViolationException.class);
    }

    // ---- search-semantic T3: semantic.top_k / semantic.min_score ----------------------------

    @Test
    void semanticTopKMustBeBetween1And200() {
        for (long ok : new long[] {1, 50, 200}) {
            assertThatCode(() -> SystemConfigValidator.validate(
                            SystemConfigDefaults.SEMANTIC_TOP_K, SystemConfigValue.of(ValueType.NUMBER, ok), lookup(Map.of())))
                    .doesNotThrowAnyException();
        }
        for (long bad : new long[] {0, -1, 201}) {
            assertThatThrownBy(() -> SystemConfigValidator.validate(
                            SystemConfigDefaults.SEMANTIC_TOP_K, SystemConfigValue.of(ValueType.NUMBER, bad), lookup(Map.of())))
                    .isInstanceOf(SystemConfigValidator.ConstraintViolationException.class);
        }
    }

    @Test
    void semanticMinScoreMustBeADecimalBetween0And1() {
        for (String ok : new String[] {"0", "0.50", "0.5", "1", "1.0"}) {
            assertThatCode(() -> SystemConfigValidator.validate(
                            SystemConfigDefaults.SEMANTIC_MIN_SCORE, SystemConfigValue.of(ValueType.STRING, ok), lookup(Map.of())))
                    .as(ok).doesNotThrowAnyException();
        }
        for (String bad : new String[] {"-0.1", "1.01", "abc", "", "NaN"}) {
            assertThatThrownBy(() -> SystemConfigValidator.validate(
                            SystemConfigDefaults.SEMANTIC_MIN_SCORE, SystemConfigValue.of(ValueType.STRING, bad), lookup(Map.of())))
                    .as(bad).isInstanceOf(SystemConfigValidator.ConstraintViolationException.class);
        }
    }

    @Test
    void semanticDefaultsMatchTheSpec() {
        assertThat(SystemConfigDefaults.get(SystemConfigDefaults.SEMANTIC_TOP_K).defaultValue()).isEqualTo("50");
        assertThat(SystemConfigDefaults.get(SystemConfigDefaults.SEMANTIC_MIN_SCORE).defaultValue()).isEqualTo("0.50");
        assertThat(SystemConfigDefaults.get(SystemConfigDefaults.SEMANTIC_MIN_SCORE).sensitive()).isFalse();
    }
}
