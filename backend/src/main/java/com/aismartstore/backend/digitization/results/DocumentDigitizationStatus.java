package com.aismartstore.backend.digitization.results;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * digitization-results-store.md §2.2 (V26) - read-model trạng thái tổng hợp
 * cấp tài liệu (US4). {@code document_id} là PK, không tự sinh
 * ({@code gen_random_uuid()}) - 1-1 với {@code document}, tạo lazy ở lần
 * {@link DigitizationRunLifecycleService#createRun} đầu tiên của tài liệu
 * (mục 4 "Luồng xử lý chính" bước 1).
 *
 * <p>Điểm ghi duy nhất là {@link DigitizationRunLifecycleServiceImpl} (AC-4
 * User Story 4) - {@link DocumentDigitizationStatusRepository} không expose
 * {@code save}/upsert trực tiếp cho luồng khác dùng tuỳ tiện, xem Javadoc
 * repository đó.
 */
@Entity
@Table(name = "document_digitization_status")
public class DocumentDigitizationStatus {

    public static final String STATUS_NOT_APPLICABLE = "not_applicable";
    public static final String STATUS_QUEUED = "queued";
    public static final String STATUS_PROCESSING = "processing";
    public static final String STATUS_FAILED = "failed";
    public static final String STATUS_PENDING_REVIEW = "pending_review";
    public static final String STATUS_IN_REVIEW = "in_review";
    public static final String STATUS_CONFIRMED = "confirmed";

    @Id
    @Column(name = "document_id")
    private UUID documentId;

    @Column(name = "ocr_status", nullable = false, length = 20)
    private String ocrStatus = STATUS_NOT_APPLICABLE;

    @Column(name = "stt_status", nullable = false, length = 20)
    private String sttStatus = STATUS_NOT_APPLICABLE;

    @Column(name = "sed_status", nullable = false, length = 20)
    private String sedStatus = STATUS_NOT_APPLICABLE;

    @Column(name = "video_summary_status", nullable = false, length = 20)
    private String videoSummaryStatus = STATUS_NOT_APPLICABLE;

    /**
     * digitization-pipeline-03-video-queue.md §2.2 (V31). Since
     * digitization-pipeline-05-video-review.md (V32, trigger
     * {@code trg_sync_video_status} dropped) written like every other column
     * here - only via {@link
     * DocumentDigitizationStatusRepository#upsertTaskStatus} from {@link
     * DigitizationRunLifecycleServiceImpl}.
     */
    @Column(name = "video_status", nullable = false, length = 20)
    private String videoStatus = STATUS_NOT_APPLICABLE;

    @Column(name = "overall_status", nullable = false, length = 20)
    private String overallStatus = STATUS_NOT_APPLICABLE;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    public UUID getDocumentId() {
        return documentId;
    }

    public void setDocumentId(UUID documentId) {
        this.documentId = documentId;
    }

    public String getOcrStatus() {
        return ocrStatus;
    }

    public void setOcrStatus(String ocrStatus) {
        this.ocrStatus = ocrStatus;
    }

    public String getSttStatus() {
        return sttStatus;
    }

    public void setSttStatus(String sttStatus) {
        this.sttStatus = sttStatus;
    }

    public String getSedStatus() {
        return sedStatus;
    }

    public void setSedStatus(String sedStatus) {
        this.sedStatus = sedStatus;
    }

    public String getVideoSummaryStatus() {
        return videoSummaryStatus;
    }

    public void setVideoSummaryStatus(String videoSummaryStatus) {
        this.videoSummaryStatus = videoSummaryStatus;
    }

    public String getVideoStatus() {
        return videoStatus;
    }

    public void setVideoStatus(String videoStatus) {
        this.videoStatus = videoStatus;
    }

    public String getOverallStatus() {
        return overallStatus;
    }

    public void setOverallStatus(String overallStatus) {
        this.overallStatus = overallStatus;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(OffsetDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
