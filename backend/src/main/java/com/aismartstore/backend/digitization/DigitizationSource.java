package com.aismartstore.backend.digitization;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * digitization-pipeline-01-source-dispatch.md §2.2: one component ready for
 * the AI worker to consume. {@code id} is NOT database-generated (no
 * {@code DEFAULT} in V18) - callers must set it explicitly: {@code
 * documentId} itself for {@code component_type='original'} (Story 2 AC-2,
 * §4.3 step 1), a fresh random UUID for the two rows produced by video
 * separation (§4.2 step 5).
 */
@Entity
@Table(name = "digitization_source")
public class DigitizationSource {

    public static final String COMPONENT_VIDEO = "video";
    public static final String COMPONENT_AUDIO = "audio";
    public static final String COMPONENT_ORIGINAL = "original";

    public static final String DISPATCH_PENDING = "pending";
    public static final String DISPATCH_PUBLISHED = "published";
    public static final String DISPATCH_FAILED = "failed";

    @Id
    private UUID id;

    @Column(name = "document_id", nullable = false)
    private UUID documentId;

    @Column(name = "component_type", nullable = false, length = 16)
    private String componentType;

    /** {@code "<bucket>/<objectKey>"} - see §4.4 step 1. */
    @Column(name = "file_path", nullable = false, length = 1024)
    private String filePath;

    /** {@code processFileType} when dispatched (§4.4 step 3). */
    @Column(name = "mime_type", nullable = false)
    private String mimeType;

    @Column(nullable = false)
    private boolean dispatchable = true;

    @Column(name = "dispatch_status", nullable = false, length = 16)
    private String dispatchStatus = DISPATCH_PENDING;

    @Column(name = "dispatch_attempt_count", nullable = false)
    private int dispatchAttemptCount;

    @Column(name = "dispatched_at")
    private OffsetDateTime dispatchedAt;

    /**
     * digitization-pipeline-02-ocr-stt-processing.md §2 (V27) - audio length
     * in ms, only meaningful for {@code component_type IN ('audio',
     * 'original')} with an {@code audio/*} {@code mime_type}; NULL for
     * image/PDF. Set once by {@code DigitizationRunLifecycleServiceImpl
     * #completeRunSuccess} when a {@code stt} run finishes; the V27 trigger
     * uses it to reject {@code content_segment} rows past the source's end.
     */
    @Column(name = "duration_ms")
    private Integer durationMs;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getDocumentId() {
        return documentId;
    }

    public void setDocumentId(UUID documentId) {
        this.documentId = documentId;
    }

    public String getComponentType() {
        return componentType;
    }

    public void setComponentType(String componentType) {
        this.componentType = componentType;
    }

    public String getFilePath() {
        return filePath;
    }

    public void setFilePath(String filePath) {
        this.filePath = filePath;
    }

    public String getMimeType() {
        return mimeType;
    }

    public void setMimeType(String mimeType) {
        this.mimeType = mimeType;
    }

    public boolean isDispatchable() {
        return dispatchable;
    }

    public void setDispatchable(boolean dispatchable) {
        this.dispatchable = dispatchable;
    }

    public String getDispatchStatus() {
        return dispatchStatus;
    }

    public void setDispatchStatus(String dispatchStatus) {
        this.dispatchStatus = dispatchStatus;
    }

    public int getDispatchAttemptCount() {
        return dispatchAttemptCount;
    }

    public void setDispatchAttemptCount(int dispatchAttemptCount) {
        this.dispatchAttemptCount = dispatchAttemptCount;
    }

    public OffsetDateTime getDispatchedAt() {
        return dispatchedAt;
    }

    public void setDispatchedAt(OffsetDateTime dispatchedAt) {
        this.dispatchedAt = dispatchedAt;
    }

    public Integer getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(Integer durationMs) {
        this.durationMs = durationMs;
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
