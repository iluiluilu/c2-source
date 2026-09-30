package com.aismartstore.backend.digitization.results;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

/**
 * digitization-results-store.md §2.2 (V26) - detection thô của SED; mọi
 * detection được ghi (US5 AC-1), độc lập với việc có được thăng hạng thành
 * {@link ContentSegment} hay không ({@link #promotedSegmentId} NULL nếu dưới
 * ngưỡng cấu hình).
 */
@Entity
@Table(name = "sound_event")
public class SoundEvent {

    @Id
    @GeneratedValue
    @UuidGenerator
    private UUID id;

    @Column(name = "run_id", nullable = false)
    private UUID runId;

    @Column(name = "document_id", nullable = false)
    private UUID documentId;

    @Column(nullable = false, length = 100)
    private String label;

    @Column(name = "label_vi", length = 200)
    private String labelVi;

    @Column(nullable = false, precision = 4, scale = 3)
    private BigDecimal confidence;

    @Column(name = "time_start_ms", nullable = false)
    private int timeStartMs;

    @Column(name = "time_end_ms", nullable = false)
    private int timeEndMs;

    @Column(name = "promoted_segment_id")
    private UUID promotedSegmentId;

    @Column(nullable = false)
    private boolean verified;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getRunId() {
        return runId;
    }

    public void setRunId(UUID runId) {
        this.runId = runId;
    }

    public UUID getDocumentId() {
        return documentId;
    }

    public void setDocumentId(UUID documentId) {
        this.documentId = documentId;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public String getLabelVi() {
        return labelVi;
    }

    public void setLabelVi(String labelVi) {
        this.labelVi = labelVi;
    }

    public BigDecimal getConfidence() {
        return confidence;
    }

    public void setConfidence(BigDecimal confidence) {
        this.confidence = confidence;
    }

    public int getTimeStartMs() {
        return timeStartMs;
    }

    public void setTimeStartMs(int timeStartMs) {
        this.timeStartMs = timeStartMs;
    }

    public int getTimeEndMs() {
        return timeEndMs;
    }

    public void setTimeEndMs(int timeEndMs) {
        this.timeEndMs = timeEndMs;
    }

    public UUID getPromotedSegmentId() {
        return promotedSegmentId;
    }

    public void setPromotedSegmentId(UUID promotedSegmentId) {
        this.promotedSegmentId = promotedSegmentId;
    }

    public boolean isVerified() {
        return verified;
    }

    public void setVerified(boolean verified) {
        this.verified = verified;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
