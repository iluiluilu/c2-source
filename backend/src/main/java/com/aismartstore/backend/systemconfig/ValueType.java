package com.aismartstore.backend.systemconfig;

/**
 * Storage/runtime type of a {@link SystemConfig} value. The DB column stores
 * every value as TEXT; this discriminates how the service converts it back to
 * a Java type (see {@link SystemConfigValue}). Persisted as its name via
 * {@code @Enumerated(EnumType.STRING)} and mirrored by the CHECK constraint in
 * V7__init_system_config.sql.
 */
public enum ValueType {
    STRING,
    NUMBER,
    BOOLEAN,
    JSON
}
