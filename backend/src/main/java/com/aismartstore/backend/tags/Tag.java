package com.aismartstore.backend.tags;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

/** Mirrors the NestJS backend's tags/tag.entity.ts. */
@Entity
@Table(name = "tag")
public class Tag {

    @Id
    @GeneratedValue
    @UuidGenerator
    private UUID id;

    @Column(nullable = false, length = 16)
    private String kind;

    @Column(nullable = false, length = 128)
    private String label;

    @Column(name = "created_by_sub", nullable = false)
    private String createdBySub;

    @Column(name = "created_by_username", nullable = false)
    private String createdByUsername;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    /**
     * classification-notes-01-tags.md §2 (V20): vòng đời "ngừng dùng" cho
     * {@code topic} (Quyết định 2 SA) - nhãn đã ngừng dùng không thể gán mới
     * nhưng {@code document_tag} hiện có không bị xoá (không hard-delete).
     */
    @Column(nullable = false)
    private boolean active = true;

    public UUID getId() {
        return id;
    }

    public String getKind() {
        return kind;
    }

    public void setKind(String kind) {
        this.kind = kind;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
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

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }
}
