package com.aismartstore.backend.notifications;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

/**
 * group-management-sharing-02-document-sharing.md §2/Task 2 (Story 4): one
 * in-app notification for one recipient. Deliberately no relation to {@code
 * Group}/{@code Document} - {@code payload} carries whatever ids the UI
 * needs, so this table never has to change shape when a new notification
 * {@code type} is added.
 *
 * <p>{@code payload} is {@code Map<String, Object>} + {@link JdbcTypeCode}
 * ({@code SqlTypes.JSON}), not a hand-serialized {@code String} - the spec's
 * example entity used a plain {@code String} field for the {@code jsonb}
 * column, but every other {@code jsonb} column in this codebase ({@link
 * com.aismartstore.backend.audit.AuditLog#getMetadata()}, {@link
 * com.aismartstore.backend.documents.Document#getScanResult()}) uses this
 * idiom, and a bare {@code String} does not bind correctly against a {@code
 * jsonb} column under this project's Hibernate version without it.
 */
@Entity
@Table(name = "notification")
public class Notification {

    @Id
    @GeneratedValue
    @UuidGenerator
    private UUID id;

    @Column(name = "recipient_sub", nullable = false)
    private String recipientSub;

    @Column(nullable = false, length = 64)
    private String type;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload_json", columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> payload;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "read_at")
    private OffsetDateTime readAt;

    public UUID getId() {
        return id;
    }

    public String getRecipientSub() {
        return recipientSub;
    }

    public void setRecipientSub(String recipientSub) {
        this.recipientSub = recipientSub;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public Map<String, Object> getPayload() {
        return payload;
    }

    public void setPayload(Map<String, Object> payload) {
        this.payload = payload;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public OffsetDateTime getReadAt() {
        return readAt;
    }

    public void setReadAt(OffsetDateTime readAt) {
        this.readAt = readAt;
    }
}
