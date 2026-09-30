package com.aismartstore.backend.digitization.results;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

/**
 * digitization-results-store.md §2.2 (V26) - lịch sử sửa của một
 * {@link ContentSegment}, append-only (US2 AC-2/AC-4): {@link
 * DigitizationRunLifecycleService#reviseSegment} chỉ INSERT, không có method
 * update/delete expose ra ngoài ở {@link SegmentRevisionRepository}.
 */
@Entity
@Table(name = "segment_revision")
public class SegmentRevision {

    @Id
    @GeneratedValue
    @UuidGenerator
    private UUID id;

    @Column(name = "segment_id", nullable = false)
    private UUID segmentId;

    @Column(name = "revision_no", nullable = false)
    private int revisionNo;

    @Column
    private String text;

    @Column(name = "time_start_ms")
    private Integer timeStartMs;

    @Column(name = "time_end_ms")
    private Integer timeEndMs;

    @Column(nullable = false)
    private boolean deleted;

    @Column(name = "edited_by", nullable = false)
    private String editedBy;

    @Column(name = "edited_at", nullable = false)
    private OffsetDateTime editedAt;

    @Column
    private String reason;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getSegmentId() {
        return segmentId;
    }

    public void setSegmentId(UUID segmentId) {
        this.segmentId = segmentId;
    }

    public int getRevisionNo() {
        return revisionNo;
    }

    public void setRevisionNo(int revisionNo) {
        this.revisionNo = revisionNo;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public Integer getTimeStartMs() {
        return timeStartMs;
    }

    public void setTimeStartMs(Integer timeStartMs) {
        this.timeStartMs = timeStartMs;
    }

    public Integer getTimeEndMs() {
        return timeEndMs;
    }

    public void setTimeEndMs(Integer timeEndMs) {
        this.timeEndMs = timeEndMs;
    }

    public boolean isDeleted() {
        return deleted;
    }

    public void setDeleted(boolean deleted) {
        this.deleted = deleted;
    }

    public String getEditedBy() {
        return editedBy;
    }

    public void setEditedBy(String editedBy) {
        this.editedBy = editedBy;
    }

    public OffsetDateTime getEditedAt() {
        return editedAt;
    }

    public void setEditedAt(OffsetDateTime editedAt) {
        this.editedAt = editedAt;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }
}
