package com.aismartstore.backend.documents;

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
 * Immutable warehouse document metadata row (FR-11/FR-12, NFR-09 - no
 * update/delete code path). Mirrors the NestJS backend's
 * documents/document.entity.ts.
 */
@Entity
@Table(name = "document")
public class Document {

    @Id
    @GeneratedValue
    @UuidGenerator
    private UUID id;

    @Column(name = "original_name", nullable = false, length = 512)
    private String originalName;

    @Column(name = "display_name", nullable = false, length = 512)
    private String displayName;

    @Column(name = "mime_type", nullable = false)
    private String mimeType;

    @Column(nullable = false)
    private long size;

    /**
     * Nullable (data-ingestion.md §2.3, Story 3 AC-2): a file rejected for
     * format ({@code status=rejected}) never gets hashed, so this is null for
     * those rows only - always present for {@code clean}/{@code infected}.
     */
    @Column(length = 64)
    private String sha256;

    @Column(name = "storage_key", length = 512)
    private String storageKey;

    /**
     * Path relative to {@link #folderId}, as declared by the uploader
     * (data-ingestion.md §2.3, Story 2 AC-1/Story 4 AC-2). Empty string for a
     * single-file upload with no subfolder.
     */
    @Column(name = "relative_path", nullable = false, length = 1024)
    private String relativePath = "";

    @Column(name = "folder_id", nullable = false)
    private UUID folderId;

    @Column(name = "uploader_sub", nullable = false)
    private String uploaderSub;

    @Column(name = "uploader_username", nullable = false)
    private String uploaderUsername;

    @Column(nullable = false, length = 16)
    private String status;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "scan_result", columnDefinition = "jsonb")
    private Map<String, Object> scanResult;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    /**
     * NULL = active; non-null = soft-deleted (trash). Written by {@link
     * com.aismartstore.backend.trash.TrashService#trash} (delete-restore-
     * trash-01-xoa-mem-thung-rac.md §4.1) and cleared back to NULL by {@code
     * TrashService#restore} (§4.3).
     */
    @Column(name = "deleted_at")
    private OffsetDateTime deletedAt;

    /** Who moved this document to trash - NULL when active (delete-restore-trash-01 §2.3). */
    @Column(name = "deleted_by_sub")
    private String deletedBySub;

    @Column(name = "deleted_by_username")
    private String deletedByUsername;

    /** Optional free-text reason supplied at delete time. */
    @Column(name = "deleted_reason", length = 500)
    private String deletedReason;

    /**
     * Chốt cứng tại thời điểm xóa = {@code deletedAt + retention.trash_retention_days}
     * (delete-restore-trash-01 §2.1) - never hồi tố if the retention config
     * changes later.
     */
    @Column(name = "purge_after")
    private OffsetDateTime purgeAfter;

    /**
     * Which batch this document was trashed as part of (history only - not
     * cleared on restore). No JPA relation to {@code TrashBatch} on purpose
     * (delete-restore-trash-01 §2.3) - only the id is needed here, avoiding
     * N+1 loads when listing trash.
     */
    @Column(name = "trash_batch_id")
    private UUID trashBatchId;

    /**
     * Hard-delete state machine (delete-restore-trash-02-xoa-cung-xung-
     * dot.md §2.1, V30): {@code active -> deleted -> purging -> purged}. Set
     * to {@link PurgeState#DELETED} by {@code TrashService#trash} and back
     * to {@link PurgeState#ACTIVE} by {@code TrashService#restore}; {@code
     * purging}/{@code purged} are owned exclusively by {@code
     * TrashPurgeService}.
     */
    @Column(name = "purge_state", nullable = false, length = 16)
    private String purgeState = PurgeState.ACTIVE;

    /** Set once, when {@code TrashPurgeService} finishes purging this row (§2.2 bia mộ). */
    @Column(name = "purged_at")
    private OffsetDateTime purgedAt;

    /**
     * When this row last flipped to {@link PurgeState#PURGING} - used by
     * {@code PurgeStuckRecoveryJob} (§4.3) to detect a batch that crashed
     * mid-purge and never reached {@code purged}. Cleared back to {@code
     * null} whenever the row leaves {@code purging} (either forward to
     * {@code purged}, or back to {@code deleted} via the recovery job).
     */
    @Column(name = "purging_started_at")
    private OffsetDateTime purgingStartedAt;

    /** backend-multi-instance Task 8: last embed.requested publish (semantic reconciliation grace window). */
    @Column(name = "embed_requested_at")
    private OffsetDateTime embedRequestedAt;

    /**
     * Snapshot of the deleted ancestor folder's {@code path_ids ++ [id]}
     * (root -> leaf), taken by {@code WarehouseService#deleteFolder} at the
     * moment a folder this document was filed under gets deleted (§4.4) -
     * {@code null} for the common case (folder never deleted, or deleted
     * before this column existed). Restore (§4.4) walks this array
     * leaf-to-root to suggest the nearest still-existing ancestor folder.
     * Cleared (along with the rest of the trash bookkeeping columns) once
     * {@code TrashPurgeService} purges this row (§2.2).
     */
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "deleted_folder_path_ids")
    private UUID[] deletedFolderPathIds;

    /**
     * personal-to-warehouse.md §2: provenance - {@code 'personal'} when this
     * document was supplemented from the uploader's personal storage, else null.
     */
    @Column(name = "source_kind", length = 16)
    private String sourceKind;

    /** Source personal_file id (no FK on purpose - personal files are hard-deleted, AC-22). */
    @Column(name = "source_personal_file_id")
    private UUID sourcePersonalFileId;

    public String getSourceKind() {
        return sourceKind;
    }

    public void setSourceKind(String sourceKind) {
        this.sourceKind = sourceKind;
    }

    public UUID getSourcePersonalFileId() {
        return sourcePersonalFileId;
    }

    public void setSourcePersonalFileId(UUID sourcePersonalFileId) {
        this.sourcePersonalFileId = sourcePersonalFileId;
    }

    public UUID getId() {
        return id;
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

    public String getRelativePath() {
        return relativePath;
    }

    public void setRelativePath(String relativePath) {
        this.relativePath = relativePath;
    }

    public UUID getFolderId() {
        return folderId;
    }

    public void setFolderId(UUID folderId) {
        this.folderId = folderId;
    }

    public String getUploaderSub() {
        return uploaderSub;
    }

    public void setUploaderSub(String uploaderSub) {
        this.uploaderSub = uploaderSub;
    }

    public String getUploaderUsername() {
        return uploaderUsername;
    }

    public void setUploaderUsername(String uploaderUsername) {
        this.uploaderUsername = uploaderUsername;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Map<String, Object> getScanResult() {
        return scanResult;
    }

    public void setScanResult(Map<String, Object> scanResult) {
        this.scanResult = scanResult;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public OffsetDateTime getEmbedRequestedAt() {
        return embedRequestedAt;
    }

    public void setEmbedRequestedAt(OffsetDateTime embedRequestedAt) {
        this.embedRequestedAt = embedRequestedAt;
    }

    public OffsetDateTime getDeletedAt() {
        return deletedAt;
    }

    public void setDeletedAt(OffsetDateTime deletedAt) {
        this.deletedAt = deletedAt;
    }

    public String getDeletedBySub() {
        return deletedBySub;
    }

    public void setDeletedBySub(String deletedBySub) {
        this.deletedBySub = deletedBySub;
    }

    public String getDeletedByUsername() {
        return deletedByUsername;
    }

    public void setDeletedByUsername(String deletedByUsername) {
        this.deletedByUsername = deletedByUsername;
    }

    public String getDeletedReason() {
        return deletedReason;
    }

    public void setDeletedReason(String deletedReason) {
        this.deletedReason = deletedReason;
    }

    public OffsetDateTime getPurgeAfter() {
        return purgeAfter;
    }

    public void setPurgeAfter(OffsetDateTime purgeAfter) {
        this.purgeAfter = purgeAfter;
    }

    public UUID getTrashBatchId() {
        return trashBatchId;
    }

    public void setTrashBatchId(UUID trashBatchId) {
        this.trashBatchId = trashBatchId;
    }

    public String getPurgeState() {
        return purgeState;
    }

    public void setPurgeState(String purgeState) {
        this.purgeState = purgeState;
    }

    public OffsetDateTime getPurgedAt() {
        return purgedAt;
    }

    public void setPurgedAt(OffsetDateTime purgedAt) {
        this.purgedAt = purgedAt;
    }

    public OffsetDateTime getPurgingStartedAt() {
        return purgingStartedAt;
    }

    public void setPurgingStartedAt(OffsetDateTime purgingStartedAt) {
        this.purgingStartedAt = purgingStartedAt;
    }

    public UUID[] getDeletedFolderPathIds() {
        return deletedFolderPathIds;
    }

    public void setDeletedFolderPathIds(UUID[] deletedFolderPathIds) {
        this.deletedFolderPathIds = deletedFolderPathIds;
    }
}
