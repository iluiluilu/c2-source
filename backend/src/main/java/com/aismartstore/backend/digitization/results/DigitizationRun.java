package com.aismartstore.backend.digitization.results;

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
 * digitization-results-store.md §2.1 (V25) - hợp nhất vòng đời hàng đợi/GPU
 * ({@code processing_status}) và vòng đời duyệt FR-18 ({@code review_state})
 * của một lần chạy AI cho một {@code (document_id, task)}. Chỉ 1 dòng "active"
 * (review_state ngoài {@code superseded}/{@code void}) mỗi cặp
 * {@code (document_id, task)} - enforce bởi {@code UQ_digitization_run_active}.
 */
@Entity
@Table(name = "digitization_run")
public class DigitizationRun {

    public static final String TASK_OCR = "ocr";
    public static final String TASK_STT = "stt";
    public static final String TASK_SED = "sed";
    public static final String TASK_VIDEO_SUMMARY = "video_summary";
    /**
     * digitization-pipeline-03-video-queue.md mục 0 điểm 1 (Quyết định 8):
     * phân tích hình ảnh video, không phụ thuộc STT - khác ngữ nghĩa với
     * {@link #TASK_VIDEO_SUMMARY} (tóm tắt nội dung đã phiên âm, trỏ
     * {@code dependsOnRunId} tới run {@code stt}).
     */
    public static final String TASK_VIDEO = "video";

    public static final String PROCESSING_QUEUED = "queued";
    public static final String PROCESSING_RUNNING = "running";
    public static final String PROCESSING_SUCCEEDED = "succeeded";
    public static final String PROCESSING_FAILED_RETRYABLE = "failed_retryable";
    public static final String PROCESSING_FAILED_PERMANENT = "failed_permanent";
    public static final String PROCESSING_CANCELLED = "cancelled";

    /**
     * digitization-results-store.md §7 điểm 2 (chốt 2026-09-07): giá trị an
     * toàn cấp cột, không phải giá trị thực tế cho bất kỳ task nào trong 4
     * task đang hỗ trợ - {@link DigitizationRunLifecycleService#createRun}
     * luôn set {@link #REVIEW_PENDING} tường minh.
     */
    public static final String REVIEW_NOT_APPLICABLE = "not_applicable";
    public static final String REVIEW_PENDING = "pending";
    public static final String REVIEW_IN_REVIEW = "in_review";
    public static final String REVIEW_CONFIRMED = "confirmed";
    public static final String REVIEW_SUPERSEDED = "superseded";
    public static final String REVIEW_VOID = "void";

    public static final String CONFIRM_AUTO = "auto";
    public static final String CONFIRM_MANUAL_UNCHANGED = "manual_unchanged";
    public static final String CONFIRM_MANUAL_EDITED = "manual_edited";

    public static final String INPUT_QUALITY_OK = "ok";
    public static final String INPUT_QUALITY_POOR_SCAN = "poor_scan";
    public static final String INPUT_QUALITY_LOW_AUDIO_SNR = "low_audio_snr";

    public static final String OUTPUT_QUALITY_OK = "ok";
    public static final String OUTPUT_QUALITY_POOR = "poor";

    @Id
    @GeneratedValue
    @UuidGenerator
    private UUID id;

    @Column(name = "document_id", nullable = false)
    private UUID documentId;

    @Column(name = "source_id", nullable = false)
    private UUID sourceId;

    @Column(nullable = false, length = 16)
    private String task;

    @Column(name = "batch_id")
    private UUID batchId;

    @Column(name = "processing_status", nullable = false, length = 20)
    private String processingStatus = PROCESSING_QUEUED;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "lease_expires_at")
    private OffsetDateTime leaseExpiresAt;

    @Column(name = "worker_id")
    private String workerId;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "model_name")
    private String modelName;

    @Column(name = "model_version", length = 64)
    private String modelVersion;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> metrics;

    @Column(name = "started_at")
    private OffsetDateTime startedAt;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    /** {@code derived/<document_id>/<run_id>.json} */
    @Column(name = "raw_output_path", length = 1024)
    private String rawOutputPath;

    @Column(name = "input_quality_flag", length = 16)
    private String inputQualityFlag;

    @Column(name = "output_quality_flag", length = 16)
    private String outputQualityFlag;

    @Column(name = "low_confidence_count", nullable = false)
    private int lowConfidenceCount;

    @Column(name = "segment_count", nullable = false)
    private int segmentCount;

    @Column(name = "auto_confirm_blocked", nullable = false)
    private boolean autoConfirmBlocked;

    @Column(name = "review_state", nullable = false, length = 16)
    private String reviewState = REVIEW_NOT_APPLICABLE;

    @Column(name = "review_deadline_at")
    private OffsetDateTime reviewDeadlineAt;

    @Column(name = "confirm_source", length = 20)
    private String confirmSource;

    @Column(name = "confirmed_by")
    private String confirmedBy;

    @Column(name = "confirmed_at")
    private OffsetDateTime confirmedAt;

    @Column(name = "first_viewed_at")
    private OffsetDateTime firstViewedAt;

    @Column(name = "first_viewed_by")
    private String firstViewedBy;

    @Column(name = "edited_segment_count", nullable = false)
    private int editedSegmentCount;

    @Column(name = "first_edit_at")
    private OffsetDateTime firstEditAt;

    /**
     * digitization-review-01-ocr.md §2 (V28, Story 6) - đã ghi
     * {@code audit_log(digitization.review_stale)} cho lần {@code in_review}
     * hiện tại, chống {@link
     * DigitizationRunLifecycleService#flagStaleInReviewBatch} chọn lại/ghi
     * audit trùng mỗi chu kỳ quét.
     */
    @Column(name = "stale_alerted", nullable = false)
    private boolean staleAlerted;

    /**
     * digitization-results-store.md §8 rủi ro 3: chỉ set cho
     * {@code task='video_summary'} - trỏ tới run {@code stt} đã confirmed mà
     * bản tóm tắt này phụ thuộc. Chỉ dùng để truy vết/hiển thị (mục 4 bước 7),
     * không có trigger DB chặn tạo run trái phép.
     */
    @Column(name = "depends_on_run_id")
    private UUID dependsOnRunId;

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

    public UUID getSourceId() {
        return sourceId;
    }

    public void setSourceId(UUID sourceId) {
        this.sourceId = sourceId;
    }

    public String getTask() {
        return task;
    }

    public void setTask(String task) {
        this.task = task;
    }

    public UUID getBatchId() {
        return batchId;
    }

    public void setBatchId(UUID batchId) {
        this.batchId = batchId;
    }

    public String getProcessingStatus() {
        return processingStatus;
    }

    public void setProcessingStatus(String processingStatus) {
        this.processingStatus = processingStatus;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public void setAttemptCount(int attemptCount) {
        this.attemptCount = attemptCount;
    }

    public OffsetDateTime getLeaseExpiresAt() {
        return leaseExpiresAt;
    }

    public void setLeaseExpiresAt(OffsetDateTime leaseExpiresAt) {
        this.leaseExpiresAt = leaseExpiresAt;
    }

    public String getWorkerId() {
        return workerId;
    }

    public void setWorkerId(String workerId) {
        this.workerId = workerId;
    }

    public String getLastError() {
        return lastError;
    }

    public void setLastError(String lastError) {
        this.lastError = lastError;
    }

    public String getModelName() {
        return modelName;
    }

    public void setModelName(String modelName) {
        this.modelName = modelName;
    }

    public String getModelVersion() {
        return modelVersion;
    }

    public void setModelVersion(String modelVersion) {
        this.modelVersion = modelVersion;
    }

    public Map<String, Object> getMetrics() {
        return metrics;
    }

    public void setMetrics(Map<String, Object> metrics) {
        this.metrics = metrics;
    }

    public OffsetDateTime getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(OffsetDateTime startedAt) {
        this.startedAt = startedAt;
    }

    public OffsetDateTime getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(OffsetDateTime completedAt) {
        this.completedAt = completedAt;
    }

    public String getRawOutputPath() {
        return rawOutputPath;
    }

    public void setRawOutputPath(String rawOutputPath) {
        this.rawOutputPath = rawOutputPath;
    }

    public String getInputQualityFlag() {
        return inputQualityFlag;
    }

    public void setInputQualityFlag(String inputQualityFlag) {
        this.inputQualityFlag = inputQualityFlag;
    }

    public String getOutputQualityFlag() {
        return outputQualityFlag;
    }

    public void setOutputQualityFlag(String outputQualityFlag) {
        this.outputQualityFlag = outputQualityFlag;
    }

    public int getLowConfidenceCount() {
        return lowConfidenceCount;
    }

    public void setLowConfidenceCount(int lowConfidenceCount) {
        this.lowConfidenceCount = lowConfidenceCount;
    }

    public int getSegmentCount() {
        return segmentCount;
    }

    public void setSegmentCount(int segmentCount) {
        this.segmentCount = segmentCount;
    }

    public boolean isAutoConfirmBlocked() {
        return autoConfirmBlocked;
    }

    public void setAutoConfirmBlocked(boolean autoConfirmBlocked) {
        this.autoConfirmBlocked = autoConfirmBlocked;
    }

    public String getReviewState() {
        return reviewState;
    }

    public void setReviewState(String reviewState) {
        this.reviewState = reviewState;
    }

    public OffsetDateTime getReviewDeadlineAt() {
        return reviewDeadlineAt;
    }

    public void setReviewDeadlineAt(OffsetDateTime reviewDeadlineAt) {
        this.reviewDeadlineAt = reviewDeadlineAt;
    }

    public String getConfirmSource() {
        return confirmSource;
    }

    /**
     * search-fulltext-01-tim-kiem-co-ban.md §2.1: the 3 DB values normalize to the search contract's
     * 2 values - {@code manual_unchanged}/{@code manual_edited} -> {@code manual}, anything else
     * (incl. a missing value) -> {@code auto}, so the "tự động xác nhận" badge errs on the cautious side.
     */
    public static String searchConfirmSource(String dbConfirmSource) {
        return CONFIRM_MANUAL_UNCHANGED.equals(dbConfirmSource) || CONFIRM_MANUAL_EDITED.equals(dbConfirmSource)
                ? "manual"
                : "auto";
    }

    public void setConfirmSource(String confirmSource) {
        this.confirmSource = confirmSource;
    }

    public String getConfirmedBy() {
        return confirmedBy;
    }

    public void setConfirmedBy(String confirmedBy) {
        this.confirmedBy = confirmedBy;
    }

    public OffsetDateTime getConfirmedAt() {
        return confirmedAt;
    }

    public void setConfirmedAt(OffsetDateTime confirmedAt) {
        this.confirmedAt = confirmedAt;
    }

    public OffsetDateTime getFirstViewedAt() {
        return firstViewedAt;
    }

    public void setFirstViewedAt(OffsetDateTime firstViewedAt) {
        this.firstViewedAt = firstViewedAt;
    }

    public String getFirstViewedBy() {
        return firstViewedBy;
    }

    public void setFirstViewedBy(String firstViewedBy) {
        this.firstViewedBy = firstViewedBy;
    }

    public int getEditedSegmentCount() {
        return editedSegmentCount;
    }

    public void setEditedSegmentCount(int editedSegmentCount) {
        this.editedSegmentCount = editedSegmentCount;
    }

    public OffsetDateTime getFirstEditAt() {
        return firstEditAt;
    }

    public void setFirstEditAt(OffsetDateTime firstEditAt) {
        this.firstEditAt = firstEditAt;
    }

    public boolean isStaleAlerted() {
        return staleAlerted;
    }

    public void setStaleAlerted(boolean staleAlerted) {
        this.staleAlerted = staleAlerted;
    }

    public UUID getDependsOnRunId() {
        return dependsOnRunId;
    }

    public void setDependsOnRunId(UUID dependsOnRunId) {
        this.dependsOnRunId = dependsOnRunId;
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
