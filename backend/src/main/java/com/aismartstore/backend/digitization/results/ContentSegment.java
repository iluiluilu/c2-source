package com.aismartstore.backend.digitization.results;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

/**
 * digitization-results-store.md §2.2 (V26) - đơn vị nội dung thống nhất
 * OCR/STT/SED/tóm tắt. {@code ai_*} là bản AI sinh, bất biến sau khi run kết
 * thúc; {@code current_*} là bản hiện hành, cập nhật cùng transaction với
 * {@link SegmentRevision} (US2 AC-1-AC-3).
 */
@Entity
@Table(name = "content_segment")
public class ContentSegment {

    public static final String SOURCE_OCR = "ocr";
    public static final String SOURCE_STT = "stt";
    public static final String SOURCE_SED = "sed";
    public static final String SOURCE_SUMMARY = "summary";
    /** digitization-pipeline-05-video-review.md: segment of a {@code task=video} run (V32). */
    public static final String SOURCE_VIDEO = "video";

    public static final String ORIGIN_AI = "ai";
    public static final String ORIGIN_MANUAL = "manual";

    @Id
    @GeneratedValue
    @UuidGenerator
    private UUID id;

    @Column(name = "run_id", nullable = false)
    private UUID runId;

    @Column(name = "document_id", nullable = false)
    private UUID documentId;

    @Column(name = "segment_index", nullable = false)
    private int segmentIndex;

    @Column(name = "content_source", nullable = false, length = 16)
    private String contentSource;

    @Column(nullable = false, length = 8)
    private String origin = ORIGIN_AI;

    @Column(name = "page_no")
    private Integer pageNo;

    /** {@code [[x,y],...]} tỉ lệ 0-1 trên trang, không phải pixel (chốt 2026-09-07). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "bbox_polygon", columnDefinition = "jsonb")
    private List<List<Double>> bboxPolygon;

    @Column(name = "reading_order")
    private Integer readingOrder;

    @Column(name = "line_from")
    private Integer lineFrom;

    @Column(name = "line_to")
    private Integer lineTo;

    @Column(name = "time_start_ms")
    private Integer timeStartMs;

    @Column(name = "time_end_ms")
    private Integer timeEndMs;

    @Column(name = "speaker_label", length = 64)
    private String speakerLabel;

    /**
     * digitization-results-store.md §1: mảng chuỗi UUID dưới dạng
     * {@code JSONB} thay vì {@code UUID[]} (không có tiền lệ mapping mảng
     * Postgres trong dự án) - hành vi không đổi so với thiết kế SA.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "source_segment_ids", columnDefinition = "jsonb")
    private List<String> sourceSegmentIds;

    @Column(name = "ai_text", nullable = false)
    private String aiText;

    @Column(name = "ai_time_start_ms")
    private Integer aiTimeStartMs;

    @Column(name = "ai_time_end_ms")
    private Integer aiTimeEndMs;

    @Column(name = "current_text", nullable = false)
    private String currentText;

    @Column(name = "current_time_start_ms")
    private Integer currentTimeStartMs;

    @Column(name = "current_time_end_ms")
    private Integer currentTimeEndMs;

    @Column(name = "current_revision_no", nullable = false)
    private int currentRevisionNo;

    @Column(name = "is_edited", nullable = false)
    private boolean edited;

    /** digitization-review-01-ocr.md §2 (V28) - Story 2 AC-5, "xoá đoạn" mềm. */
    @Column(name = "is_deleted", nullable = false)
    private boolean deleted;

    @Column(precision = 4, scale = 3)
    private BigDecimal confidence;

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

    public int getSegmentIndex() {
        return segmentIndex;
    }

    public void setSegmentIndex(int segmentIndex) {
        this.segmentIndex = segmentIndex;
    }

    public String getContentSource() {
        return contentSource;
    }

    public void setContentSource(String contentSource) {
        this.contentSource = contentSource;
    }

    public String getOrigin() {
        return origin;
    }

    public void setOrigin(String origin) {
        this.origin = origin;
    }

    public Integer getPageNo() {
        return pageNo;
    }

    public void setPageNo(Integer pageNo) {
        this.pageNo = pageNo;
    }

    public List<List<Double>> getBboxPolygon() {
        return bboxPolygon;
    }

    public void setBboxPolygon(List<List<Double>> bboxPolygon) {
        this.bboxPolygon = bboxPolygon;
    }

    public Integer getReadingOrder() {
        return readingOrder;
    }

    public void setReadingOrder(Integer readingOrder) {
        this.readingOrder = readingOrder;
    }

    public Integer getLineFrom() {
        return lineFrom;
    }

    public void setLineFrom(Integer lineFrom) {
        this.lineFrom = lineFrom;
    }

    public Integer getLineTo() {
        return lineTo;
    }

    public void setLineTo(Integer lineTo) {
        this.lineTo = lineTo;
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

    public String getSpeakerLabel() {
        return speakerLabel;
    }

    public void setSpeakerLabel(String speakerLabel) {
        this.speakerLabel = speakerLabel;
    }

    public List<String> getSourceSegmentIds() {
        return sourceSegmentIds;
    }

    public void setSourceSegmentIds(List<String> sourceSegmentIds) {
        this.sourceSegmentIds = sourceSegmentIds;
    }

    public String getAiText() {
        return aiText;
    }

    public void setAiText(String aiText) {
        this.aiText = aiText;
    }

    public Integer getAiTimeStartMs() {
        return aiTimeStartMs;
    }

    public void setAiTimeStartMs(Integer aiTimeStartMs) {
        this.aiTimeStartMs = aiTimeStartMs;
    }

    public Integer getAiTimeEndMs() {
        return aiTimeEndMs;
    }

    public void setAiTimeEndMs(Integer aiTimeEndMs) {
        this.aiTimeEndMs = aiTimeEndMs;
    }

    public String getCurrentText() {
        return currentText;
    }

    public void setCurrentText(String currentText) {
        this.currentText = currentText;
    }

    public Integer getCurrentTimeStartMs() {
        return currentTimeStartMs;
    }

    public void setCurrentTimeStartMs(Integer currentTimeStartMs) {
        this.currentTimeStartMs = currentTimeStartMs;
    }

    public Integer getCurrentTimeEndMs() {
        return currentTimeEndMs;
    }

    public void setCurrentTimeEndMs(Integer currentTimeEndMs) {
        this.currentTimeEndMs = currentTimeEndMs;
    }

    public int getCurrentRevisionNo() {
        return currentRevisionNo;
    }

    public void setCurrentRevisionNo(int currentRevisionNo) {
        this.currentRevisionNo = currentRevisionNo;
    }

    public boolean isEdited() {
        return edited;
    }

    public void setEdited(boolean edited) {
        this.edited = edited;
    }

    public boolean isDeleted() {
        return deleted;
    }

    public void setDeleted(boolean deleted) {
        this.deleted = deleted;
    }

    public BigDecimal getConfidence() {
        return confidence;
    }

    public void setConfidence(BigDecimal confidence) {
        this.confidence = confidence;
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
