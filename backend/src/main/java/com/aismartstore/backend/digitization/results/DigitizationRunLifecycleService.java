package com.aismartstore.backend.digitization.results;

import java.util.List;
import java.util.UUID;

/**
 * digitization-results-store.md §3 - "API nội bộ" duy nhất giữa {@code
 * digitization-pipeline} (worker ghi kết quả) và {@code digitization-review}
 * (UI duyệt), và điểm ghi duy nhất của {@code document_digitization_status}
 * (AC-4 User Story 4).
 */
public interface DigitizationRunLifecycleService {

    /**
     * Tạo run mới cho {@code (documentId, task)}. Ném {@link
     * IllegalStateException} nếu đã có run active cùng cặp {@code
     * (document_id, task)} - bọc quanh {@code UQ_digitization_run_active} để
     * trả lỗi nghiệp vụ rõ ràng thay vì lộ {@code
     * DataIntegrityViolationException} lên caller.
     */
    DigitizationRun createRun(UUID documentId, UUID sourceId, String task, UUID batchId, UUID dependsOnRunId);

    /**
     * Đổi {@code processing_status} ({@code queued -> running ->
     * succeeded/failed_retryable/failed_permanent/cancelled}). Cùng
     * transaction: upsert {@code document_digitization_status} tương ứng.
     */
    DigitizationRun transitionProcessingStatus(UUID runId, String newStatus, String lastError);

    /**
     * Đổi {@code review_state} ({@code pending -> in_review -> confirmed},
     * hoặc {@code -> superseded/void}). Cùng transaction: upsert {@code
     * document_digitization_status} tương ứng.
     */
    DigitizationRun transitionReviewState(UUID runId, String newReviewState, String confirmSource, String actorSub);

    /**
     * Ghi N {@code content_segment} cho 1 run (sau khi {@code
     * processing_status='succeeded'}), set {@code ai_text=current_text},
     * {@code ai_time_*=current_time_*} (US2 AC-1).
     */
    List<ContentSegment> writeSegments(UUID runId, List<NewSegment> segments);

    /**
     * digitization-review-01-ocr.md §3.4 (đổi chữ ký so với
     * digitization-results-store.md, chưa implement khi đó - an toàn để
     * sửa): ghi 1 {@code segment_revision} mới + cập nhật {@code current_*}/
     * {@code is_edited}/{@code current_revision_no} trên {@code
     * content_segment}, cùng 1 transaction (US2 AC-2/AC-3). Ném {@link
     * SegmentRevisionConflictException} (-> 409) nếu {@code
     * content_segment.current_revision_no != expectedRevisionNo} - optimistic
     * lock (US2 AC-3). Nếu {@code run.review_state='pending'} -> chuyển
     * {@code 'in_review'} + {@code first_edit_at=now()} (US2 AC-2). Nếu
     * {@code run.review_state='confirmed'} -> KHÔNG đổi {@code review_state},
     * ghi {@code outbox_event('review.reconfirmed')} thay vì không làm gì
     * (US4 AC-2).
     */
    ContentSegment reviseSegment(
            UUID segmentId, int expectedRevisionNo, String newText, Integer newTimeStartMs, Integer newTimeEndMs,
            String editedBy, String reason);

    /**
     * digitization-review-01-ocr.md §3.4 - MỚI: xoá mềm - {@code
     * segment_revision(deleted=true)} + {@code
     * content_segment.is_deleted=true}. Cùng optimistic lock + quy tắc
     * chuyển {@code review_state} như {@link #reviseSegment}.
     */
    ContentSegment deleteSegment(UUID segmentId, int expectedRevisionNo, String editedBy, String reason);

    /**
     * digitization-review-01-ocr.md §3.4 - MỚI: thêm đoạn thủ công (US2 AC-5)
     * - {@code origin='manual'}, {@code ai_text=""} (sentinel, cột NOT NULL
     * nhưng không có bản AI để đối chiếu), {@code segment_index} chèn sau
     * {@code afterSegmentIndex} (hoặc cuối run nếu null). Cùng quy tắc
     * chuyển {@code review_state} như {@link #reviseSegment}.
     */
    ContentSegment addManualSegment(UUID runId, Integer afterSegmentIndex, String text, String createdBySub);

    /**
     * digitization-review-01-ocr.md §3.4 - MỚI: chỉ ghi {@code
     * first_viewed_at}/{@code first_viewed_by} nếu đang NULL, KHÔNG đổi
     * {@code review_state} (US2 AC - mở xem không dừng đồng hồ, Quyết định 2
     * SA).
     */
    void markFirstViewed(UUID runId, String viewerSub);

    /**
     * digitization-review-01-ocr.md §3.4 - MỚI: xác nhận thủ công (US4).
     * Validate {@code review_state IN ('pending','in_review')} (ném {@link
     * com.aismartstore.backend.common.ConflictException} -> 409 nếu không);
     * tính {@code edited_segment_count = COUNT(*) content_segment WHERE
     * run_id=:runId AND is_edited=true}; {@code confirm_source =
     * edited_segment_count>0 ? 'manual_edited' : 'manual_unchanged'}; set
     * {@code confirmed_by}/{@code confirmed_at}; ghi {@code
     * outbox_event('review.confirmed')}; nếu có run khác cùng {@code
     * (document_id, task)} đang {@code 'confirmed'} -> chuyển run đó
     * {@code 'superseded'} cùng transaction (US8 AC-2).
     */
    DigitizationRun confirmRun(UUID runId, String confirmedBySub);

    /**
     * digitization-review-01-ocr.md §3.4 - MỚI (dùng bởi {@code
     * AutoConfirmScheduler}) - 1 lệnh UPDATE...FROM subquery {@code FOR
     * UPDATE SKIP LOCKED LIMIT :batchSize}, {@code review_state='pending'} ->
     * {@code 'confirmed'}, {@code confirm_source='auto'}. Trả về danh sách
     * run đã xác nhận để caller ghi audit/outbox trong CÙNG transaction.
     */
    List<DigitizationRun> autoConfirmDueBatch(int batchSize);

    /**
     * digitization-review-01-ocr.md §3.4 - MỚI (dùng bởi {@code
     * ReviewStaleAlertJob}) - SELECT ... WHERE {@code
     * review_state='in_review' AND first_edit_at <= now() - stale_hours AND
     * stale_alerted=false LIMIT :batchSize}, rồi set {@code
     * stale_alerted=true}.
     */
    List<DigitizationRun> flagStaleInReviewBatch(int staleHours, int batchSize);

    /**
     * Ghi N {@code sound_event} (mọi detection, US5 AC-1) + thăng hạng
     * detection vượt ngưỡng thành {@code content_segment} (US5 AC-2/AC-3).
     * Ngưỡng đọc từ {@code DigitizationProperties}, không hardcode.
     */
    List<SoundEvent> writeSoundEvents(UUID runId, List<NewSoundEvent> detections);

    /**
     * digitization-pipeline-02-ocr-stt-processing.md §3.5/§4.5 bước 2 - MỚI:
     * 1 transaction - {@code transitionProcessingStatus(succeeded)} (qua
     * conditional UPDATE, không phải read-then-write - mục 0 điểm 5) + set
     * {@code completed_at}/{@code model_name}/{@code model_version}/{@code
     * raw_output_path}/{@code metrics}/{@code input_quality_flag} + set
     * {@code digitization_source.duration_ms} (nếu {@code task=stt}, lần đầu
     * set) + {@link #writeSegments} + tính {@code output_quality_flag}/{@code
     * auto_confirm_blocked} (đọc ngưỡng từ {@code system_config}, theo
     * task) + {@code review_deadline_at = completed_at +
     * ocr_stt.auto_confirm_hours} + upsert {@code
     * document_digitization_status} + ghi {@code
     * outbox_event('digitization.completed')}.
     *
     * <p>Bỏ qua (no-op, trả về run hiện tại) nếu {@code processing_status}
     * không còn {@code queued}/{@code running} (dedupe - Story 6 AC-1/AC-2).
     */
    DigitizationRun completeRunSuccess(UUID runId, RunResultPayload payload);

    /**
     * digitization-pipeline-02-ocr-stt-processing.md §3.5/§4.5 bước 3 - MỚI:
     * 1 transaction - tăng {@code attempt_count}, quyết định requeue
     * (retryable, còn lượt - republish vào {@code <queue>.retry} với TTL
     * backoff theo lần thử) / {@code failed_permanent} (hết lượt hoặc lỗi
     * vĩnh viễn - republish vào {@code <queue>.dlq}) + set {@code
     * last_error}.
     *
     * <p>Bỏ qua (no-op, trả về run hiện tại) nếu đã ở trạng thái cuối
     * (dedupe).
     */
    DigitizationRun completeRunFailure(UUID runId, String status, String errorCode, String errorMessage);
}
