package com.aismartstore.backend.systemconfig;

/**
 * Static definition of one configurable parameter: its key, storage type,
 * fallback default, sensitivity (two-step confirm), a human description and an
 * optional unit. Populated from the SA catalogue (Quyết định 3 in
 * docs/01-sa/architecture/system-config.md); the defaults here - not any DB
 * row - are the source of default values (Story 1 AC-4, Story 6).
 *
 * @param key          config key (matches the {@code system_config.key} column)
 * @param valueType    how {@code defaultValue} and stored values are interpreted
 * @param defaultValue textual default, always parseable per {@code valueType}
 * @param sensitive    true = requires two-step confirmation before applying
 * @param description  Vietnamese label for the admin UI
 * @param unit         display unit (e.g. "ngày", "byte", "%"), or null
 */
public record SystemConfigDef(
        String key,
        ValueType valueType,
        String defaultValue,
        boolean sensitive,
        String description,
        String unit) {
}
