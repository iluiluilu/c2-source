package com.aismartstore.backend.systemconfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class SystemConfigValueTest {

    @Test
    void numberAcceptsIntegerJsonAndString() {
        assertThat(SystemConfigValue.of(ValueType.NUMBER, 5).raw()).isEqualTo("5");
        assertThat(SystemConfigValue.of(ValueType.NUMBER, "42").raw()).isEqualTo("42");
        assertThat(SystemConfigValue.of(ValueType.NUMBER, 5).asLong()).isEqualTo(5L);
    }

    @Test
    void numberRejectsNonInteger() {
        assertThatThrownBy(() -> SystemConfigValue.of(ValueType.NUMBER, 1.5))
                .isInstanceOf(SystemConfigValue.InvalidValueException.class);
        assertThatThrownBy(() -> SystemConfigValue.of(ValueType.NUMBER, "abc"))
                .isInstanceOf(SystemConfigValue.InvalidValueException.class);
    }

    @Test
    void booleanAcceptsBoolAndString() {
        assertThat(SystemConfigValue.of(ValueType.BOOLEAN, true).asBoolean()).isTrue();
        assertThat(SystemConfigValue.of(ValueType.BOOLEAN, "false").asBoolean()).isFalse();
        assertThatThrownBy(() -> SystemConfigValue.of(ValueType.BOOLEAN, "maybe"))
                .isInstanceOf(SystemConfigValue.InvalidValueException.class);
    }

    @Test
    void stringRejectsNonString() {
        assertThat(SystemConfigValue.of(ValueType.STRING, "0 2 * * *").asString()).isEqualTo("0 2 * * *");
        assertThatThrownBy(() -> SystemConfigValue.of(ValueType.STRING, 5))
                .isInstanceOf(SystemConfigValue.InvalidValueException.class);
    }

    @Test
    void jsonNormalizesObjectAndValidatesString() {
        SystemConfigValue v = SystemConfigValue.of(ValueType.JSON, java.util.Map.of("a", 1));
        assertThat(v.raw()).contains("\"a\"");
        assertThatThrownBy(() -> SystemConfigValue.of(ValueType.JSON, "{not json"))
                .isInstanceOf(SystemConfigValue.InvalidValueException.class);
    }

    @Test
    void nullIsRejected() {
        assertThatThrownBy(() -> SystemConfigValue.of(ValueType.STRING, null))
                .isInstanceOf(SystemConfigValue.InvalidValueException.class);
    }
}
