package com.aismartstore.backend.upload;

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
 * One chunked-upload session (V16 {@code upload_session}), backing the
 * {@code init -> chunk -> status -> complete} protocol shared by the ingest
 * (chunked-file-upload-01-ingest-chunked-upload.md, this table's owning
 * spec) and personal (chunked-file-upload-02-personal-chunked-upload.md)
 * flows. Lives in a domain-neutral package (not {@code ingest}) on purpose -
 * see §2.2 of the ingest spec.
 */
@Entity
@Table(name = "upload_session")
public class UploadSession {

    @Id
    @GeneratedValue
    @UuidGenerator
    private UUID id;

    /**
     * FK to {@code upload_batch}. Deliberately a plain UUID column, not a
     * JPA relation: {@code complete()} always re-loads the batch through
     * {@code UploadBatchService.loadOpenBatch} (which itself lazily expires
     * it), so a mapped association here would just be an unused second path
     * to the same row.
     */
    @Column(name = "batch_id", nullable = false)
    private UUID batchId;

    @Column(name = "original_name", nullable = false, length = 1024)
    private String originalName;

    @Column(name = "display_name", length = 512)
    private String displayName;

    @Column(name = "relative_path", nullable = false, length = 1024)
    private String relativePath = "";

    @Column(name = "declared_size_bytes", nullable = false)
    private long declaredSizeBytes;

    @Column(name = "received_bytes", nullable = false)
    private long receivedBytes;

    /** Snapshot of {@code upload.chunk_size_bytes} at init time - see V16 migration comment. */
    @Column(name = "chunk_size_bytes", nullable = false)
    private long chunkSizeBytes;

    @Column(name = "declared_mime_type")
    private String declaredMimeType;

    @Column(length = 4000)
    private String note;

    /**
     * chunked-file-upload-02-personal-chunked-upload.md §2.1/§2.2 (V17):
     * destination folder for a {@code BatchKind.PERSONAL} session - always
     * {@code null} for a {@code WAREHOUSE} session.
     */
    @Column(name = "target_personal_folder_id")
    private UUID targetPersonalFolderId;

    @Column(name = "temp_file_path", nullable = false, length = 1024)
    private String tempFilePath;

    @Column(nullable = false, length = 16)
    private String status;

    /**
     * The response body {@code complete()} returned the first time it ran to
     * completion (success or a business rejection) - replayed verbatim on any
     * later {@code complete()} call against the same (by-then COMPLETED)
     * session, see §4.4.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "result_body", columnDefinition = "jsonb")
    private Map<String, Object> resultBody;

    @Column(name = "created_by_sub", nullable = false)
    private String createdBySub;

    @Column(name = "created_by_username", nullable = false)
    private String createdByUsername;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    public UUID getId() {
        return id;
    }

    public UUID getBatchId() {
        return batchId;
    }

    public void setBatchId(UUID batchId) {
        this.batchId = batchId;
    }

    public String getOriginalName() {
        return originalName;
    }

    public void setOriginalName(String originalName) {
        this.originalName = originalName;
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
        this.relativePath = relativePath;
    }

    public long getDeclaredSizeBytes() {
        return declaredSizeBytes;
    }

    public void setDeclaredSizeBytes(long declaredSizeBytes) {
        this.declaredSizeBytes = declaredSizeBytes;
    }

    public long getReceivedBytes() {
        return receivedBytes;
    }

    public void setReceivedBytes(long receivedBytes) {
        this.receivedBytes = receivedBytes;
    }

    public long getChunkSizeBytes() {
        return chunkSizeBytes;
    }

    public void setChunkSizeBytes(long chunkSizeBytes) {
        this.chunkSizeBytes = chunkSizeBytes;
    }

    public String getDeclaredMimeType() {
        return declaredMimeType;
    }

    public void setDeclaredMimeType(String declaredMimeType) {
        this.declaredMimeType = declaredMimeType;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }

    public UUID getTargetPersonalFolderId() {
        return targetPersonalFolderId;
    }

    public void setTargetPersonalFolderId(UUID targetPersonalFolderId) {
        this.targetPersonalFolderId = targetPersonalFolderId;
    }

    public String getTempFilePath() {
        return tempFilePath;
    }

    public void setTempFilePath(String tempFilePath) {
        this.tempFilePath = tempFilePath;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Map<String, Object> getResultBody() {
        return resultBody;
    }

    public void setResultBody(Map<String, Object> resultBody) {
        this.resultBody = resultBody;
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
