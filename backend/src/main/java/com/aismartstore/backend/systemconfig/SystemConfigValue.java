package com.aismartstore.backend.systemconfig;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Type-safe view over a raw {@code system_config} TEXT value, interpreted per
 * its {@link ValueType}. Conversion failures throw {@link InvalidValueException}
 * so the service/controller can map them to a {@code 400 INVALID_VALUE}
 * response (Story 2 AC-2).
 *
 * <p>The canonical textual form (what gets persisted and cached) is produced by
 * {@link #of(ValueType, Object)} from whatever JSON the client sent, so a
 * client may send {@code 5}, {@code "5"}, {@code true} etc. and the store keeps
 * a normalized string.
 */
public final class SystemConfigValue {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final ValueType valueType;
    private final String raw;

    private SystemConfigValue(ValueType valueType, String raw) {
        this.valueType = valueType;
        this.raw = raw;
    }

    /** Thrown when a value cannot be interpreted as its declared {@link ValueType}. */
    public static class InvalidValueException extends RuntimeException {
        public InvalidValueException(String message) {
            super(message);
        }
    }

    /**
     * Wrap an already-stored textual value. Assumes {@code raw} is well-formed
     * for {@code valueType} (it went through {@link #of} when written); still
     * validates lazily on the {@code as*} accessors.
     */
    public static SystemConfigValue fromStored(ValueType valueType, String raw) {
        return new SystemConfigValue(valueType, raw);
    }

    /**
     * Normalize a client-supplied JSON value into its canonical textual form
     * for {@code valueType}, validating the type in the process.
     */
    public static SystemConfigValue of(ValueType valueType, Object clientValue) {
        if (clientValue == null) {
            throw new InvalidValueException("value must not be null");
        }
        return switch (valueType) {
            case STRING -> {
                if (!(clientValue instanceof String s)) {
                    throw new InvalidValueException("value must be a string");
                }
                yield new SystemConfigValue(valueType, s);
            }
            case NUMBER -> {
                long parsed = parseLong(clientValue);
                yield new SystemConfigValue(valueType, Long.toString(parsed));
            }
            case BOOLEAN -> {
                boolean parsed = parseBoolean(clientValue);
                yield new SystemConfigValue(valueType, Boolean.toString(parsed));
            }
            case JSON -> new SystemConfigValue(valueType, toJsonString(clientValue));
        };
    }

    public ValueType valueType() {
        return valueType;
    }

    /** The canonical stored/cacheable textual form. */
    public String raw() {
        return raw;
    }

    public String asString() {
        return raw;
    }

    public long asLong() {
        return parseLong(raw);
    }

    public boolean asBoolean() {
        return parseBoolean(raw);
    }

    public <T> T asJson(Class<T> type) {
        try {
            return JSON.readValue(raw, type);
        } catch (Exception e) {
            throw new InvalidValueException("value is not valid JSON for " + type.getSimpleName());
        }
    }

    public JsonNode asJsonNode() {
        try {
            return JSON.readTree(raw);
        } catch (Exception e) {
            throw new InvalidValueException("value is not valid JSON");
        }
    }

    private static long parseLong(Object value) {
        if (value instanceof Number n) {
            if (n instanceof Double || n instanceof Float) {
                double d = n.doubleValue();
                if (d != Math.floor(d) || Double.isInfinite(d)) {
                    throw new InvalidValueException("value must be an integer number");
                }
                return (long) d;
            }
            return n.longValue();
        }
        if (value instanceof String s) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException e) {
                throw new InvalidValueException("value must be an integer number");
            }
        }
        throw new InvalidValueException("value must be an integer number");
    }

    private static boolean parseBoolean(Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof String s) {
            if ("true".equalsIgnoreCase(s.trim())) {
                return true;
            }
            if ("false".equalsIgnoreCase(s.trim())) {
                return false;
            }
        }
        throw new InvalidValueException("value must be a boolean");
    }

    private static String toJsonString(Object value) {
        try {
            if (value instanceof String s) {
                // Accept a raw JSON string payload; validate it parses.
                JSON.readTree(s);
                return s;
            }
            return JSON.writeValueAsString(value);
        } catch (Exception e) {
            throw new InvalidValueException("value must be valid JSON");
        }
    }
}
