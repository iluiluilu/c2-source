package com.aismartstore.backend.digitization;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

/**
 * digitization-pipeline-01-source-dispatch.md §2.1: FFmpeg source-separation
 * job, one row per video {@code document} (unique on {@code documentId}).
 *
 * <p>Legacy as of digitization-pipeline-04-video-no-separation.md §1/§0
 * point 1: no longer written (video intake goes straight to {@code
 * digitization_source} instead, see {@code DigitizationIntakeService}) and
 * no longer polled (its runner was removed). Kept read/delete-only for
 * historical rows and {@code TrashPurgeService}.
 */
@Entity
@Table(name = "source_separation_job")
public class SourceSeparationJob {

    public static final String STATUS_QUEUED = "queued";
    public static final String STATUS_RUNNING = "running";
    public static final String STATUS_SUCCEEDED = "succeeded";
    public static final String STATUS_FAILED = "failed";

    @Id
    @GeneratedValue
    @UuidGenerator
    private UUID id;

    @Column(name = "document_id", nullable = false)
    private UUID documentId;

    @Column(nullable = false, length = 16)
    private String status = STATUS_QUEUED;

    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

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

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public void setAttemptCount(int attemptCount) {
        this.attemptCount = attemptCount;
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
