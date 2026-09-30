package com.aismartstore.backend.ingest;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

/**
 * One "open upload" round (data-ingestion.md §2.2, Story 2 AC-3) - a single
 * file or a whole folder, both go through this same batch API. Reused
 * as-is (not a separate table) for personal-storage batches
 * ({@code kind = PERSONAL}) per personal-storage-quota.md §1/§2: exactly one
 * of {@code targetFolderId} (WAREHOUSE) or {@code targetPersonalFolderId}
 * (PERSONAL, nullable = personal root) is set, enforced by
 * {@code CK_upload_batch_kind_target} (V11).
 */
@Entity
@Table(name = "upload_batch")
public class UploadBatch {

    @Id
    @GeneratedValue
    @UuidGenerator
    private UUID id;

    /** WAREHOUSE only (V11 CHECK); null for a PERSONAL batch. */
    @Column(name = "target_folder_id")
    private UUID targetFolderId;

    /**
     * PERSONAL only; null = personal storage root. Added by
     * personal-storage-quota.md §2 (V11).
     */
    @Column(name = "target_personal_folder_id")
    private UUID targetPersonalFolderId;

    /**
     * Distinguishes a warehouse batch from a personal-storage batch
     * (personal-storage-quota.md §2, V11). Defaults to WAREHOUSE so existing
     * data-ingestion.md code paths that never set this field keep inserting
     * the same value the DB column default already provided pre-V11.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 16)
    private BatchKind kind = BatchKind.WAREHOUSE;

    @Column(name = "owner_sub", nullable = false)
    private String ownerSub;

    @Column(name = "owner_username", nullable = false)
    private String ownerUsername;

    @Column(name = "declared_file_count", nullable = false)
    private int declaredFileCount;

    @Column(name = "declared_total_bytes", nullable = false)
    private long declaredTotalBytes;

    @Column(name = "actual_file_count", nullable = false)
    private int actualFileCount;

    @Column(name = "actual_total_bytes", nullable = false)
    private long actualTotalBytes;

    /** DEV addition beyond spec §2.2 - backs the close() summary, see V10 migration comment. */
    @Column(name = "accepted_file_count", nullable = false)
    private int acceptedFileCount;

    /** DEV addition beyond spec §2.2 - backs the close() summary, see V10 migration comment. */
    @Column(name = "skipped_file_count", nullable = false)
    private int skippedFileCount;

    /** DEV addition beyond spec §2.2 - backs the close() summary, see V10 migration comment. */
    @Column(name = "rejected_file_count", nullable = false)
    private int rejectedFileCount;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    public UUID getId() {
        return id;
    }

    public UUID getTargetFolderId() {
        return targetFolderId;
    }

    public void setTargetFolderId(UUID targetFolderId) {
        this.targetFolderId = targetFolderId;
    }

    public UUID getTargetPersonalFolderId() {
        return targetPersonalFolderId;
    }

    public void setTargetPersonalFolderId(UUID targetPersonalFolderId) {
        this.targetPersonalFolderId = targetPersonalFolderId;
    }

    public BatchKind getKind() {
        return kind;
    }

    public void setKind(BatchKind kind) {
        this.kind = kind;
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

    public int getDeclaredFileCount() {
        return declaredFileCount;
    }

    public void setDeclaredFileCount(int declaredFileCount) {
        this.declaredFileCount = declaredFileCount;
    }

    public long getDeclaredTotalBytes() {
        return declaredTotalBytes;
    }

    public void setDeclaredTotalBytes(long declaredTotalBytes) {
        this.declaredTotalBytes = declaredTotalBytes;
    }

    public int getActualFileCount() {
        return actualFileCount;
    }

    public void setActualFileCount(int actualFileCount) {
        this.actualFileCount = actualFileCount;
    }

    public long getActualTotalBytes() {
        return actualTotalBytes;
    }

    public void setActualTotalBytes(long actualTotalBytes) {
        this.actualTotalBytes = actualTotalBytes;
    }

    public int getAcceptedFileCount() {
        return acceptedFileCount;
    }

    public void setAcceptedFileCount(int acceptedFileCount) {
        this.acceptedFileCount = acceptedFileCount;
    }

    public int getSkippedFileCount() {
        return skippedFileCount;
    }

    public void setSkippedFileCount(int skippedFileCount) {
        this.skippedFileCount = skippedFileCount;
    }

    public int getRejectedFileCount() {
        return rejectedFileCount;
    }

    public void setRejectedFileCount(int rejectedFileCount) {
        this.rejectedFileCount = rejectedFileCount;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
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

    public OffsetDateTime getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(OffsetDateTime expiresAt) {
        this.expiresAt = expiresAt;
    }
}
