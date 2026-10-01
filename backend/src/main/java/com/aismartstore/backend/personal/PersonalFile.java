package com.aismartstore.backend.personal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

/**
 * A stored personal file (FR-14). Every row is a clean, stored file -
 * rejected/infected uploads persist no row (personal files are not
 * evidence). Mirrors the NestJS backend's personal/personal-file.entity.ts.
 */
@Entity
@Table(name = "personal_file")
public class PersonalFile {

    @Id
    @GeneratedValue
    @UuidGenerator
    private UUID id;

    @Column(name = "owner_sub", nullable = false)
    private String ownerSub;

    @Column(name = "owner_username", nullable = false)
    private String ownerUsername;

    @Column(name = "folder_id")
    private UUID folderId;

    @Column(name = "original_name", nullable = false, length = 512)
    private String originalName;

    @Column(name = "display_name", nullable = false, length = 512)
    private String displayName;

    @Column(name = "relative_path", nullable = false, length = 1024)
    private String relativePath = "";

    @Column(name = "mime_type", nullable = false)
    private String mimeType;

    @Column(nullable = false)
    private long size;

    @Column(nullable = false, length = 64)
    private String sha256;

    @Column(name = "storage_key", nullable = false, length = 512)
    private String storageKey;

    /**
     * The batch that created this row (personal-storage-quota.md §2, V11) -
     * traceability + Story 3 AC-1 progress. Nullable: rows from before V11
     * (and, in principle, any future non-batch write path) have none.
     */
    @Column(name = "batch_id")
    private UUID batchId;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

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

    public UUID getFolderId() {
        return folderId;
    }

    public void setFolderId(UUID folderId) {
        this.folderId = folderId;
    }

    public String getOriginalName() {
        return originalName;
    }

    public void setOriginalName(String originalName) {
        this.originalName = originalName;
    }

    /**
     * Safety net: every production write path sets display_name explicitly (SPEC-folder-upload-display-name);
     * this only keeps a row without one (e.g. direct-repository fixtures) valid - display_name defaults to original_name.
     */
    @jakarta.persistence.PrePersist
    void defaultDisplayName() {
        if (displayName == null) {
            displayName = originalName;
        }
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getRelativePath() {
        return relativePath;
    }

    public void setRelativePath(String relativePath) {
        this.relativePath = relativePath == null ? "" : relativePath;
    }

    public String getMimeType() {
        return mimeType;
    }

    public void setMimeType(String mimeType) {
        this.mimeType = mimeType;
    }

    public long getSize() {
        return size;
    }

    public void setSize(long size) {
        this.size = size;
    }

    public String getSha256() {
        return sha256;
    }

    public void setSha256(String sha256) {
        this.sha256 = sha256;
    }

    @com.fasterxml.jackson.annotation.JsonIgnore
    public String getStorageKey() {
        return storageKey;
    }

    public void setStorageKey(String storageKey) {
        this.storageKey = storageKey;
    }

    public UUID getBatchId() {
        return batchId;
    }

    public void setBatchId(UUID batchId) {
        this.batchId = batchId;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
