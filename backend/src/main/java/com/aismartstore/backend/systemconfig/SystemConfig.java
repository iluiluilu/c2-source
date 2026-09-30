package com.aismartstore.backend.systemconfig;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

/**
 * A single admin-tunable business parameter (FR-01). The value is stored as
 * TEXT and interpreted per {@link #valueType}; a row exists only for a key
 * that has been edited at least once - otherwise the static default in
 * {@link SystemConfigDefaults} applies (Story 1 AC-4, Story 6).
 *
 * <p>Postgres is the source of truth; Redis caches the value in front of this
 * table (Quyết định 4). Concurrency is last-write-wins (no optimistic lock)
 * which is sufficient for the MVP's small, rarely-edited parameter set.
 */
@Entity
@Table(name = "system_config")
public class SystemConfig {

    @Id
    @Column(length = 128)
    private String key;

    @Column(columnDefinition = "text", nullable = false)
    private String value;

    @Enumerated(EnumType.STRING)
    @Column(name = "value_type", length = 16, nullable = false)
    private ValueType valueType;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "updated_by", length = 255)
    private String updatedBy;

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }

    public ValueType getValueType() {
        return valueType;
    }

    public void setValueType(ValueType valueType) {
        this.valueType = valueType;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(OffsetDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public String getUpdatedBy() {
        return updatedBy;
    }

    public void setUpdatedBy(String updatedBy) {
        this.updatedBy = updatedBy;
    }
}
