package com.aismartstore.backend.notes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

/**
 * Free-text annotation on a document (FR-16). Add/edit only - no delete
 * path, so the annotation trail stays intact. Mirrors the NestJS backend's
 * notes/document-note.entity.ts.
 */
@Entity
@Table(name = "document_note")
public class DocumentNote {

    @Id
    @GeneratedValue
    @UuidGenerator
    private UUID id;

    @Column(name = "document_id", nullable = false)
    private UUID documentId;

    @Column(nullable = false, columnDefinition = "text")
    private String body;

    @Column(name = "created_by_sub", nullable = false)
    private String createdBySub;

    @Column(name = "created_by_username", nullable = false)
    private String createdByUsername;

    @Column(name = "updated_by_sub")
    private String updatedBySub;

    @Column(name = "updated_by_username")
    private String updatedByUsername;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    public UUID getId() {
        return id;
    }

    public UUID getDocumentId() {
        return documentId;
    }

    public void setDocumentId(UUID documentId) {
        this.documentId = documentId;
    }

    public String getBody() {
        return body;
    }

    public void setBody(String body) {
        this.body = body;
    }

    public String getCreatedBySub() {
        return createdBySub;
    }

    public void setCreatedBySub(String createdBySub) {
        this.createdBySub = createdBySub;
    }

    public String getCreatedByUsername() {
        return createdByUsername;
    }

    public void setCreatedByUsername(String createdByUsername) {
        this.createdByUsername = createdByUsername;
    }

    public String getUpdatedBySub() {
        return updatedBySub;
    }

    public void setUpdatedBySub(String updatedBySub) {
        this.updatedBySub = updatedBySub;
    }

    public String getUpdatedByUsername() {
        return updatedByUsername;
    }

    public void setUpdatedByUsername(String updatedByUsername) {
        this.updatedByUsername = updatedByUsername;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(OffsetDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
