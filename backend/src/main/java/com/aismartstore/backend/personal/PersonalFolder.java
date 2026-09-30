package com.aismartstore.backend.personal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

/** Per-user folder tree (FR-14). Mirrors the NestJS backend's personal/personal-folder.entity.ts. */
@Entity
@Table(name = "personal_folder")
public class PersonalFolder {

    @Id
    @GeneratedValue
    @UuidGenerator
    private UUID id;

    @Column(name = "owner_sub", nullable = false)
    private String ownerSub;

    @Column(name = "owner_username", nullable = false)
    private String ownerUsername;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(name = "parent_id")
    private UUID parentId;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    public UUID getId() {
        return id;
    }

    public String getOwnerSub() {
        return ownerSub;
    }

    public void setOwnerSub(String ownerSub) {
        this.ownerSub = ownerSub;
    }

    public String getOwnerUsername() {
        return ownerUsername;
    }

    public void setOwnerUsername(String ownerUsername) {
        this.ownerUsername = ownerUsername;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public UUID getParentId() {
        return parentId;
    }

    public void setParentId(UUID parentId) {
        this.parentId = parentId;
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
