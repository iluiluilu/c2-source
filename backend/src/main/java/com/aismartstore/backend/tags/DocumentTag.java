package com.aismartstore.backend.tags;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Document <-> tag assignment (many-to-many join row with extra columns).
 * Mirrors the NestJS backend's tags/document-tag.entity.ts.
 */
@Entity
@Table(name = "document_tag")
@IdClass(DocumentTagId.class)
public class DocumentTag {

    @Id
    @Column(name = "document_id")
    private UUID documentId;

    @Id
    @Column(name = "tag_id")
    private UUID tagId;

    /**
     * classification-notes-01-tags.md §2 (V20): denormalized copy of
     * {@code tag.kind} at assignment time - lets the partial unique index
     * {@code UQ_document_tag_one_doc_type} enforce "exactly one doc_type per
     * document" at the DB layer without a join back to {@code tag} (Quyết
     * định 3 SA - avoids a service-layer-only check racing a concurrent
     * insert).
     */
    @Column(name = "tag_kind", nullable = false, length = 16)
    private String tagKind;

    @Column(name = "assigned_by_sub", nullable = false)
    private String assignedBySub;

    @Column(name = "assigned_by_username", nullable = false)
    private String assignedByUsername;

    @Column(name = "assigned_at", nullable = false)
    private OffsetDateTime assignedAt;

    public UUID getDocumentId() {
        return documentId;
    }

    public void setDocumentId(UUID documentId) {
        this.documentId = documentId;
    }

    public UUID getTagId() {
        return tagId;
    }

    public void setTagId(UUID tagId) {
        this.tagId = tagId;
    }

    public String getTagKind() {
        return tagKind;
    }

    public void setTagKind(String tagKind) {
        this.tagKind = tagKind;
    }

    public String getAssignedBySub() {
        return assignedBySub;
    }

    public void setAssignedBySub(String assignedBySub) {
        this.assignedBySub = assignedBySub;
    }

    public String getAssignedByUsername() {
        return assignedByUsername;
    }

    public void setAssignedByUsername(String assignedByUsername) {
        this.assignedByUsername = assignedByUsername;
    }

    public OffsetDateTime getAssignedAt() {
        return assignedAt;
    }

    public void setAssignedAt(OffsetDateTime assignedAt) {
        this.assignedAt = assignedAt;
    }
}
