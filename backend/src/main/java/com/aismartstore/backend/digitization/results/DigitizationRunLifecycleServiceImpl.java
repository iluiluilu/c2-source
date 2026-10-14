package com.aismartstore.backend.digitization.results;

import com.aismartstore.backend.audit.AuditRequestContext;
import com.aismartstore.backend.audit.AuditService;
import com.aismartstore.backend.common.ConflictException;
import com.aismartstore.backend.common.SqlErrors;
import com.aismartstore.backend.config.DigitizationProperties;
import com.aismartstore.backend.config.QueueProperties;
import com.aismartstore.backend.digitization.DigitizationJobMessages;
import com.aismartstore.backend.digitization.DigitizationSource;
import com.aismartstore.backend.digitization.DigitizationSourceRepository;
import com.aismartstore.backend.digitization.RabbitConfig;
import com.aismartstore.backend.documents.Document;
import com.aismartstore.backend.documents.DocumentRepository;
import com.aismartstore.backend.outbox.OutboxEvent;
import com.aismartstore.backend.outbox.OutboxEventRepository;
import com.aismartstore.backend.systemconfig.SystemConfigDefaults;
import com.aismartstore.backend.systemconfig.SystemConfigService;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * digitization-results-store.md §3/§4 - implementation of the sole read/write
 * API between {@code digitization-pipeline} and {@code digitization-review}.
 * Every public method is one transaction (§4: "cùng transaction" appears
 * throughout) - default {@code @Transactional} propagation means a caller
 * that itself runs inside a transaction (e.g. wrapping {@link
 * #transitionProcessingStatus} + {@link #writeSegments} per §4 bước 3, "một
 * lệnh gọi service duy nhất bọc cả 2 thao tác") joins that same transaction
 * instead of opening a nested one.
 */
@Service
@Transactional
public class DigitizationRunLifecycleServiceImpl implements DigitizationRunLifecycleService {

    private static final Logger log = LoggerFactory.getLogger(DigitizationRunLifecycleServiceImpl.class);

    private static final double LOW_CONFIDENCE_THRESHOLD = 0.60; // matches IDX_content_segment_low_confidence

    private static final Set<String> VALID_TASKS = Set.of(
            DigitizationRun.TASK_OCR, DigitizationRun.TASK_STT, DigitizationRun.TASK_SED,
            DigitizationRun.TASK_VIDEO_SUMMARY, DigitizationRun.TASK_VIDEO);

    /**
     * digitization-pipeline-02-ocr-stt-processing.md mục 0 điểm 7 / §4.5 bước
     * 3: "backoff 1m/5m/30m" theo lần thử (index 0 = attempt 1).
     */
    private static final long[] RETRY_BACKOFF_MS = {60_000L, 300_000L, 1_800_000L};

    static final String OUTBOX_EVENT_DIGITIZATION_COMPLETED = "digitization.completed";
    /** digitization-review-01-ocr.md §3.4/§4 bước 4 (US4 AC-2) - R1 sửa tiếp sau khi run đã {@code confirmed}. */
    static final String OUTBOX_EVENT_REVIEW_RECONFIRMED = "review.reconfirmed";
    /** digitization-review-01-ocr.md §3.4 ({@code confirmRun}, US4). */
    static final String OUTBOX_EVENT_REVIEW_CONFIRMED = "review.confirmed";

    private final DigitizationRunRepository digitizationRunRepository;
    private final ContentSegmentRepository contentSegmentRepository;
    private final SegmentRevisionRepository segmentRevisionRepository;
    private final SoundEventRepository soundEventRepository;
    private final DocumentDigitizationStatusRepository documentDigitizationStatusRepository;
    private final DigitizationProperties digitizationProperties;
    private final OutboxEventRepository outboxEventRepository;
    private final SystemConfigService systemConfigService;
    private final DigitizationSourceRepository digitizationSourceRepository;
    private final DocumentRepository documentRepository;
    private final RabbitTemplate rabbitTemplate;
    private final QueueProperties queueProperties;
    private final AuditService auditService;
    private final AuditRequestContext auditRequestContext;

    public DigitizationRunLifecycleServiceImpl(
            DigitizationRunRepository digitizationRunRepository,
            ContentSegmentRepository contentSegmentRepository,
            SegmentRevisionRepository segmentRevisionRepository,
            SoundEventRepository soundEventRepository,
            DocumentDigitizationStatusRepository documentDigitizationStatusRepository,
            DigitizationProperties digitizationProperties,
            OutboxEventRepository outboxEventRepository,
            SystemConfigService systemConfigService,
            DigitizationSourceRepository digitizationSourceRepository,
            DocumentRepository documentRepository,
            RabbitTemplate rabbitTemplate,
            QueueProperties queueProperties,
            AuditService auditService,
            AuditRequestContext auditRequestContext) {
        this.digitizationRunRepository = digitizationRunRepository;
        this.contentSegmentRepository = contentSegmentRepository;
        this.segmentRevisionRepository = segmentRevisionRepository;
        this.soundEventRepository = soundEventRepository;
        this.documentDigitizationStatusRepository = documentDigitizationStatusRepository;
        this.digitizationProperties = digitizationProperties;
        this.outboxEventRepository = outboxEventRepository;
        this.systemConfigService = systemConfigService;
        this.digitizationSourceRepository = digitizationSourceRepository;
        this.documentRepository = documentRepository;
        this.rabbitTemplate = rabbitTemplate;
        this.queueProperties = queueProperties;
        this.auditService = auditService;
        this.auditRequestContext = auditRequestContext;
    }

    /**
     * digitization-review-01-ocr.md §4 bước 3 / BA AC-6 US2: audit metadata for a segment edit must
     * never carry the edited text itself, only a magnitude signal. Not true edit distance (no need
     * for one here - segment_revision already holds the full before/after text for anyone who needs
     * the exact diff) - counts differing character positions up to the shorter string's length, plus
     * the length delta, so an in-place edit ("segment X" -> "segment Y") counts as 1, not 0.
     */
    private static int charsChanged(String oldText, String newText) {
        String a = oldText == null ? "" : oldText;
        String b = newText == null ? "" : newText;
        int changed = Math.abs(a.length() - b.length());
        int commonLength = Math.min(a.length(), b.length());
        for (int i = 0; i < commonLength; i++) {
            if (a.charAt(i) != b.charAt(i)) {
                changed++;
            }
        }
        return changed;
    }

    @Override
    public DigitizationRun createRun(UUID documentId, UUID sourceId, String task, UUID batchId, UUID dependsOnRunId) {
        if (!VALID_TASKS.contains(task)) {
            throw new IllegalArgumentException("Unknown digitization task: " + task);
        }

        OffsetDateTime now = OffsetDateTime.now();
        DigitizationRun run = new DigitizationRun();
        run.setDocumentId(documentId);
        run.setSourceId(sourceId);
        run.setTask(task);
        run.setBatchId(batchId);
        run.setDependsOnRunId(dependsOnRunId);
        run.setProcessingStatus(DigitizationRun.PROCESSING_QUEUED);
        // §7 điểm 2 (chốt 2026-09-07): cả 4 task đều qua FR-18, set tường
        // minh 'pending' - không dựa vào DEFAULT 'not_applicable' của cột.
        run.setReviewState(DigitizationRun.REVIEW_PENDING);
        run.setCreatedAt(now);
        run.setUpdatedAt(now);

        try {
            // saveAndFlush (not save): the id is a client-assigned UUID, so
            // Hibernate has no reason to flush the insert immediately and
            // would otherwise defer it past this try/catch (surfacing
            // uq_digitization_run_active violations uncaught, later, at
            // whatever point the persistence context happens to flush).
            run = digitizationRunRepository.saveAndFlush(run);
        } catch (DataIntegrityViolationException e) {
            if (SqlErrors.isUniqueViolation(e)) {
                throw new IllegalStateException(
                        "A run is already active for document " + documentId + " task " + task, e);
            }
            throw e;
        }

        documentDigitizationStatusRepository.upsertTaskStatus(documentId, task, DocumentDigitizationStatus.STATUS_QUEUED);
        return run;
    }

    @Override
    public DigitizationRun transitionProcessingStatus(UUID runId, String newStatus, String lastError) {
        DigitizationRun run = getRunOrThrow(runId);
        OffsetDateTime now = OffsetDateTime.now();

        if (DigitizationRun.PROCESSING_RUNNING.equals(newStatus) && run.getStartedAt() == null) {
            run.setStartedAt(now);
            run.setAttemptCount(run.getAttemptCount() + 1);
        }
        if (isTerminalProcessingStatus(newStatus)) {
            run.setCompletedAt(now);
        }
        run.setProcessingStatus(newStatus);
        run.setLastError(lastError);
        run.setUpdatedAt(now);
        run = digitizationRunRepository.save(run);

        // §4 bước 2: 'succeeded' giữ nguyên cột task, chờ review_state (bước
        // 3/4/5) quyết định pending_review/in_review/confirmed.
        if (!DigitizationRun.PROCESSING_SUCCEEDED.equals(newStatus)) {
            String taskStatus = DigitizationStatusMapper.activeRunTaskStatus(newStatus, run.getReviewState());
            documentDigitizationStatusRepository.upsertTaskStatus(run.getDocumentId(), run.getTask(), taskStatus);
        }
        return run;
    }

    @Override
    public DigitizationRun transitionReviewState(UUID runId, String newReviewState, String confirmSource, String actorSub) {
        DigitizationRun run = getRunOrThrow(runId);
        OffsetDateTime now = OffsetDateTime.now();

        run.setReviewState(newReviewState);
        if (DigitizationRun.REVIEW_CONFIRMED.equals(newReviewState)) {
            run.setConfirmSource(confirmSource);
            run.setConfirmedBy(actorSub);
            run.setConfirmedAt(now);
        }
        run.setUpdatedAt(now);
        run = digitizationRunRepository.save(run);

        // §4 bước 5: superseded/void không đổi cột task - run active mới (nếu
        // có) tự cập nhật qua createRun/transition của chính nó.
        if (!DigitizationRun.REVIEW_SUPERSEDED.equals(newReviewState) && !DigitizationRun.REVIEW_VOID.equals(newReviewState)) {
            String taskStatus = DigitizationStatusMapper.activeRunTaskStatus(run.getProcessingStatus(), newReviewState);
            documentDigitizationStatusRepository.upsertTaskStatus(run.getDocumentId(), run.getTask(), taskStatus);
        }
        return run;
    }

    @Override
    public List<ContentSegment> writeSegments(UUID runId, List<NewSegment> segments) {
        DigitizationRun run = getRunOrThrow(runId);
        OffsetDateTime now = OffsetDateTime.now();

        List<ContentSegment> toSave = new ArrayList<>(segments.size());
        int lowConfidenceCount = 0;
        for (NewSegment newSegment : segments) {
            ContentSegment segment = new ContentSegment();
            segment.setRunId(runId);
            segment.setDocumentId(run.getDocumentId());
            segment.setSegmentIndex(newSegment.segmentIndex());
            segment.setContentSource(newSegment.contentSource());
            segment.setOrigin(newSegment.origin() != null ? newSegment.origin() : ContentSegment.ORIGIN_AI);
            segment.setPageNo(newSegment.pageNo());
            segment.setBboxPolygon(newSegment.bboxPolygon());
            segment.setReadingOrder(newSegment.readingOrder());
            segment.setLineFrom(newSegment.lineFrom());
            segment.setLineTo(newSegment.lineTo());
            segment.setTimeStartMs(newSegment.timeStartMs());
            segment.setTimeEndMs(newSegment.timeEndMs());
            segment.setSpeakerLabel(newSegment.speakerLabel());
            segment.setSourceSegmentIds(newSegment.sourceSegmentIds());

            // US2 AC-1: ai_* seeded from the same value as current_* at write time.
            segment.setAiText(newSegment.text());
            segment.setAiTimeStartMs(newSegment.timeStartMs());
            segment.setAiTimeEndMs(newSegment.timeEndMs());
            segment.setCurrentText(newSegment.text());
            segment.setCurrentTimeStartMs(newSegment.timeStartMs());
            segment.setCurrentTimeEndMs(newSegment.timeEndMs());
            segment.setCurrentRevisionNo(0);
            segment.setEdited(false);

            segment.setConfidence(newSegment.confidence());
            segment.setCreatedAt(now);
            segment.setUpdatedAt(now);
            toSave.add(segment);

            if (newSegment.confidence() != null
                    && newSegment.confidence().compareTo(BigDecimal.valueOf(LOW_CONFIDENCE_THRESHOLD)) < 0) {
                lowConfidenceCount++;
            }
        }

        List<ContentSegment> saved = contentSegmentRepository.saveAll(toSave);

        // §2.1 "Cờ chất lượng": segment_count/low_confidence_count are only
        // populated here - no other write path in this service touches them.
        run.setSegmentCount(run.getSegmentCount() + saved.size());
        run.setLowConfidenceCount(run.getLowConfidenceCount() + lowConfidenceCount);
        run.setUpdatedAt(now);
        digitizationRunRepository.save(run);

        return saved;
    }

    @Override
    public ContentSegment reviseSegment(
            UUID segmentId, int expectedRevisionNo, String newText, Integer newTimeStartMs, Integer newTimeEndMs,
            String editedBy, String reason) {
        ContentSegment segment = contentSegmentRepository.findById(segmentId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy đoạn nội dung: " + segmentId));
        requireExpectedRevision(segment, expectedRevisionNo);
        String oldText = segment.getCurrentText();
        OffsetDateTime now = OffsetDateTime.now();
        int newRevisionNo = segment.getCurrentRevisionNo() + 1;

        // PATCH partial-update semantics: a field omitted from the request
        // (null) means "leave unchanged", not "clear it" - e.g. an STT
        // time-only edit (digitization-review-02-stt.md) must not null out
        // current_text (NOT NULL), and a text-only edit must not clobber an
        // already-set current_time_*.
        if (newText == null) {
            newText = segment.getCurrentText();
        }
        if (newTimeStartMs == null) {
            newTimeStartMs = segment.getCurrentTimeStartMs();
        }
        if (newTimeEndMs == null) {
            newTimeEndMs = segment.getCurrentTimeEndMs();
        }

        SegmentRevision revision = new SegmentRevision();
        revision.setSegmentId(segmentId);
        revision.setRevisionNo(newRevisionNo);
        revision.setText(newText);
        revision.setTimeStartMs(newTimeStartMs);
        revision.setTimeEndMs(newTimeEndMs);
        revision.setDeleted(false);
        revision.setEditedBy(editedBy);
        revision.setEditedAt(now);
        revision.setReason(reason);
        insertRevisionOrThrowConflict(revision, segmentId, expectedRevisionNo);

        // US2 AC-2/AC-3: current_* updated, ai_*/ai_time_* untouched.
        segment.setCurrentText(newText);
        segment.setCurrentTimeStartMs(newTimeStartMs);
        segment.setCurrentTimeEndMs(newTimeEndMs);
        segment.setCurrentRevisionNo(newRevisionNo);
        segment.setEdited(true);
        segment.setUpdatedAt(now);
        segment = contentSegmentRepository.save(segment);

        applyReviewStateEditSideEffect(segment.getRunId(), now);

        // digitization-review-01-ocr.md §4 bước 3 / BA AC-6 US2: never the edited text itself.
        auditService.recordAsync(new AuditService.AuditEntry(
                editedBy, editedBy, "digitization_review.segment_revised", "content_segment",
                segment.getId().toString(), "success", auditRequestContext.method(), auditRequestContext.path(), 200,
                auditRequestContext.ip(),
                Map.of(
                        "segmentId", segment.getId().toString(),
                        "revisionNo", newRevisionNo,
                        "charsChanged", charsChanged(oldText, segment.getCurrentText()),
                        "editedBy", editedBy,
                        "editedAt", now.toString())));
        return segment;
    }

    @Override
    public ContentSegment deleteSegment(UUID segmentId, int expectedRevisionNo, String editedBy, String reason) {
        ContentSegment segment = contentSegmentRepository.findById(segmentId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy đoạn nội dung: " + segmentId));
        requireExpectedRevision(segment, expectedRevisionNo);
        OffsetDateTime now = OffsetDateTime.now();
        int newRevisionNo = segment.getCurrentRevisionNo() + 1;

        // digitization-review-01-ocr.md §3.4 - xoá mềm: segment_revision(deleted=true)
        // (text/time_* null - is_deleted is the source of truth, not a text diff)
        // + content_segment.is_deleted=true. current_text/current_time_* are left
        // untouched (spec does not ask for them to be cleared).
        SegmentRevision revision = new SegmentRevision();
        revision.setSegmentId(segmentId);
        revision.setRevisionNo(newRevisionNo);
        revision.setText(null);
        revision.setTimeStartMs(null);
        revision.setTimeEndMs(null);
        revision.setDeleted(true);
        revision.setEditedBy(editedBy);
        revision.setEditedAt(now);
        revision.setReason(reason);
        insertRevisionOrThrowConflict(revision, segmentId, expectedRevisionNo);

        segment.setDeleted(true);
        segment.setCurrentRevisionNo(newRevisionNo);
        segment.setEdited(true);
        segment.setUpdatedAt(now);
        segment = contentSegmentRepository.save(segment);

        applyReviewStateEditSideEffect(segment.getRunId(), now);

        // digitization-review-01-ocr.md §4 bước 3 / BA AC-6 US2 (delete follows the same
        // no-content rule as revise, per AC-5 "cũng tạo revision tương ứng").
        auditService.recordAsync(new AuditService.AuditEntry(
                editedBy, editedBy, "digitization_review.segment_deleted", "content_segment",
                segment.getId().toString(), "success", auditRequestContext.method(), auditRequestContext.path(), 200,
                auditRequestContext.ip(),
                Map.of(
                        "segmentId", segment.getId().toString(),
                        "revisionNo", newRevisionNo,
                        "charsChanged", charsChanged(segment.getCurrentText(), ""),
                        "editedBy", editedBy,
                        "editedAt", now.toString())));
        return segment;
    }

    @Override
    public ContentSegment addManualSegment(UUID runId, Integer afterSegmentIndex, String text, String createdBySub) {
        DigitizationRun run = getRunOrThrow(runId);
        OffsetDateTime now = OffsetDateTime.now();

        List<ContentSegment> existing = contentSegmentRepository.findByRunIdOrderBySegmentIndex(runId);
        int insertIndex;
        if (afterSegmentIndex == null) {
            insertIndex = existing.isEmpty()
                    ? 0
                    : existing.get(existing.size() - 1).getSegmentIndex() + 1;
        } else {
            insertIndex = afterSegmentIndex + 1;
            // Shift every following segment by 1 so segment_index stays a
            // gapless, collision-free ordering key (spec §3.4 only says
            // "chèn sau afterSegmentIndex", the shift is DEV gap-fill).
            List<ContentSegment> toShift = new ArrayList<>();
            for (ContentSegment s : existing) {
                if (s.getSegmentIndex() >= insertIndex) {
                    s.setSegmentIndex(s.getSegmentIndex() + 1);
                    s.setUpdatedAt(now);
                    toShift.add(s);
                }
            }
            if (!toShift.isEmpty()) {
                contentSegmentRepository.saveAll(toShift);
            }
        }

        String contentSource = contentSourceFor(run.getTask());

        ContentSegment segment = new ContentSegment();
        segment.setRunId(runId);
        segment.setDocumentId(run.getDocumentId());
        segment.setSegmentIndex(insertIndex);
        segment.setContentSource(contentSource);
        segment.setOrigin(ContentSegment.ORIGIN_MANUAL);
        // "" sentinel (§3.4): ai_text is NOT NULL but a manual segment has no
        // AI output to mirror.
        segment.setAiText("");
        segment.setCurrentText(text);
        segment.setCurrentRevisionNo(0);
        // Counts as a human edit for confirmRun's edited_segment_count -
        // it is, by construction, a deviation from the AI's original output.
        segment.setEdited(true);
        segment.setCreatedAt(now);
        segment.setUpdatedAt(now);
        segment = contentSegmentRepository.save(segment);

        run.setSegmentCount(run.getSegmentCount() + 1);
        run.setUpdatedAt(now);
        digitizationRunRepository.save(run);

        applyReviewStateEditSideEffect(runId, now);

        // digitization-review-01-ocr.md §4 bước 3 / BA AC-6 US2 (add follows the same
        // no-content rule as revise, per AC-5 "cũng tạo revision tương ứng").
        auditService.recordAsync(new AuditService.AuditEntry(
                createdBySub, createdBySub, "digitization_review.segment_added", "content_segment",
                segment.getId().toString(), "success", auditRequestContext.method(), auditRequestContext.path(), 201,
                auditRequestContext.ip(),
                Map.of(
                        "segmentId", segment.getId().toString(),
                        "revisionNo", segment.getCurrentRevisionNo(),
                        "charsChanged", charsChanged("", segment.getCurrentText()),
                        "editedBy", createdBySub,
                        "editedAt", now.toString())));
        return segment;
    }

    @Override
    public void markFirstViewed(UUID runId, String viewerSub) {
        DigitizationRun run = getRunOrThrow(runId);
        if (run.getFirstViewedAt() != null) {
            return;
        }
        OffsetDateTime now = OffsetDateTime.now();
        run.setFirstViewedAt(now);
        run.setFirstViewedBy(viewerSub);
        run.setUpdatedAt(now);
        digitizationRunRepository.save(run);
    }

    @Override
    public DigitizationRun confirmRun(UUID runId, String confirmedBySub) {
        DigitizationRun run = getRunOrThrow(runId);
        if (!DigitizationRun.REVIEW_PENDING.equals(run.getReviewState())
                && !DigitizationRun.REVIEW_IN_REVIEW.equals(run.getReviewState())) {
            throw new ConflictException(
                    "Lượt số hoá " + runId + " không thể xác nhận ở trạng thái kiểm duyệt hiện tại ("
                            + run.getReviewState() + ")");
        }

        OffsetDateTime now = OffsetDateTime.now();
        ConfirmSourceComputation computation = computeConfirmSource(runId);
        String confirmSource = computation.confirmSource();

        run.setReviewState(DigitizationRun.REVIEW_CONFIRMED);
        run.setConfirmSource(confirmSource);
        run.setConfirmedBy(confirmedBySub);
        run.setConfirmedAt(now);
        run.setEditedSegmentCount((int) computation.editedSegmentCount());
        run.setUpdatedAt(now);
        run = digitizationRunRepository.save(run);
        DigitizationRun confirmed = run;

        // Bug fix 2026-09-09 (checklist muc 8.16): confirmRun() never used to
        // update the document_digitization_status read-model, so a document
        // stayed "pending_review"/"in_review" there forever after being
        // confirmed - visible on /search (documentDigitizationStatus) and
        // /admin/document-ownership. DocumentDigitizationStatusReconciliationJob
        // only detects/logs this drift, it never self-heals it. Confirmed
        // directly (not via DigitizationStatusMapper.activeRunTaskStatus) -
        // reaching this line already means the task is confirmed, regardless
        // of processing_status (only ever reached from succeeded runs in
        // practice; deriving it from processing_status would be a fragile
        // proxy for something this method already knows for a fact).
        documentDigitizationStatusRepository.upsertTaskStatus(
                confirmed.getDocumentId(), confirmed.getTask(), DocumentDigitizationStatus.STATUS_CONFIRMED);

        // US8 AC-2: superseding any other run already 'confirmed' for the same
        // (document_id, task) - at most one can exist (UQ_digitization_run_active).
        digitizationRunRepository
                .findByDocumentIdAndTaskAndReviewState(confirmed.getDocumentId(), confirmed.getTask(), DigitizationRun.REVIEW_CONFIRMED)
                .filter(other -> !other.getId().equals(confirmed.getId()))
                .ifPresent(other -> {
                    other.setReviewState(DigitizationRun.REVIEW_SUPERSEDED);
                    other.setUpdatedAt(now);
                    digitizationRunRepository.save(other);
                });

        writeReviewOutboxEvent(OUTBOX_EVENT_REVIEW_CONFIRMED, confirmed, confirmSource, now);

        // digitization-review-01-ocr.md §4 bước 4: ghi audit_log(digitization_review.confirmed)
        // (actor, documentId, runId, confirmSource).
        auditService.recordAsync(new AuditService.AuditEntry(
                confirmedBySub, confirmedBySub, "digitization_review.confirmed", "digitization_run",
                confirmed.getId().toString(), "success", auditRequestContext.method(), auditRequestContext.path(),
                200, auditRequestContext.ip(),
                Map.of(
                        "documentId", confirmed.getDocumentId().toString(),
                        "runId", confirmed.getId().toString(),
                        "confirmSource", confirmSource)));
        return confirmed;
    }

    @Override
    public List<DigitizationRun> autoConfirmDueBatch(int batchSize) {
        return digitizationRunRepository.autoConfirmDueBatch(batchSize);
    }

    @Override
    public List<DigitizationRun> flagStaleInReviewBatch(int staleHours, int batchSize) {
        OffsetDateTime cutoff = OffsetDateTime.now().minusHours(staleHours);
        return digitizationRunRepository.flagStaleInReviewBatch(cutoff, batchSize);
    }

    private void requireExpectedRevision(ContentSegment segment, int expectedRevisionNo) {
        if (segment.getCurrentRevisionNo() != expectedRevisionNo) {
            throw new SegmentRevisionConflictException(segment, expectedRevisionNo);
        }
    }

    /**
     * requireExpectedRevision above only catches a stale expectedRevisionNo
     * read before this request started; it cannot see a second request that
     * reads the same currentRevisionNo and races this one to insert the same
     * (segment_id, revision_no) row. saveAndFlush (not save) surfaces that
     * uq_segment_revision violation here, synchronously, instead of letting
     * it escape uncaught later at some unrelated flush point - translated to
     * the same 409 conflict payload as the up-front check, re-reading
     * whichever request actually won the race.
     */
    private void insertRevisionOrThrowConflict(SegmentRevision revision, UUID segmentId, int expectedRevisionNo) {
        try {
            segmentRevisionRepository.saveAndFlush(revision);
        } catch (DataIntegrityViolationException e) {
            if (SqlErrors.isUniqueViolation(e)) {
                ContentSegment latest = contentSegmentRepository.findById(segmentId)
                        .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy đoạn nội dung: " + segmentId));
                throw new SegmentRevisionConflictException(latest, expectedRevisionNo);
            }
            throw e;
        }
    }

    /**
     * digitization-review-01-ocr.md §3.4/§4 bước 3 - shared side effect of
     * {@link #reviseSegment}/{@link #deleteSegment}/{@link
     * #addManualSegment}: {@code review_state='pending'} -> {@code
     * 'in_review'} + {@code first_edit_at=now()} (only the first edit sets
     * it - a run already {@code in_review} does not match this branch, so a
     * second edit leaves {@code first_edit_at} untouched); {@code
     * review_state='confirmed'} -> unchanged, but emits {@code
     * review.reconfirmed} instead (US4 AC-2).
     */
    private void applyReviewStateEditSideEffect(UUID runId, OffsetDateTime now) {
        DigitizationRun run = getRunOrThrow(runId);
        if (DigitizationRun.REVIEW_PENDING.equals(run.getReviewState())) {
            run.setReviewState(DigitizationRun.REVIEW_IN_REVIEW);
            run.setFirstEditAt(now);
            run.setUpdatedAt(now);
            run = digitizationRunRepository.save(run);
            // Same read-model gap as confirmRun() (checklist muc 8.16) - keep
            // document_digitization_status in sync with review_state here too,
            // direct constant for the same reason confirmRun() uses one.
            documentDigitizationStatusRepository.upsertTaskStatus(
                    run.getDocumentId(), run.getTask(), DocumentDigitizationStatus.STATUS_IN_REVIEW);
        } else if (DigitizationRun.REVIEW_CONFIRMED.equals(run.getReviewState())) {
            // Bug fix 2026-09-20: confirm_source/edited_segment_count used to stay frozen at
            // whatever confirmRun() last computed - a run confirmed clean (manual_unchanged, 0)
            // then edited here kept reporting manual_unchanged/0 in Postgres (read by
            // MetadataFilterSpecifications.hasConfirmSource(), the confirm_source search filter)
            // even though the edit that just happened makes it manual_edited by the exact same
            // rule confirmRun() itself uses - and even though OpenSearch's per-segment
            // normalizeConfirmSource() already, correctly, derives 'manual' for this segment from
            // the same isEdited flag. Recomputed with the same formula so both writers of this
            // pair of columns can never drift apart from each other again.
            ConfirmSourceComputation computation = computeConfirmSource(runId);
            run.setConfirmSource(computation.confirmSource());
            run.setEditedSegmentCount((int) computation.editedSegmentCount());
            run.setUpdatedAt(now);
            run = digitizationRunRepository.save(run);
            writeReviewOutboxEvent(OUTBOX_EVENT_REVIEW_RECONFIRMED, run, computation.confirmSource(), now);
        }
    }

    /** Shared by {@link #confirmRun} and {@link #applyReviewStateEditSideEffect}'s reconfirm branch - see that branch's comment. */
    private record ConfirmSourceComputation(String confirmSource, long editedSegmentCount) {
    }

    private ConfirmSourceComputation computeConfirmSource(UUID runId) {
        long editedSegmentCount = contentSegmentRepository.countByRunIdAndEditedTrue(runId);
        String confirmSource = editedSegmentCount > 0
                ? DigitizationRun.CONFIRM_MANUAL_EDITED : DigitizationRun.CONFIRM_MANUAL_UNCHANGED;
        return new ConfirmSourceComputation(confirmSource, editedSegmentCount);
    }

    private void writeReviewOutboxEvent(String eventType, DigitizationRun run, String confirmSource, OffsetDateTime now) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("documentId", run.getDocumentId().toString());
        payload.put("runId", run.getId().toString());
        payload.put("task", run.getTask());
        payload.put("confirmSource", confirmSource);
        payload.put("confirmedAt", now.toString());
        payload.put("segmentCount", run.getSegmentCount());

        OutboxEvent event = new OutboxEvent();
        event.setEventType(eventType);
        event.setPayload(payload);
        event.setCreatedAt(now);
        outboxEventRepository.save(event);
    }

    @Override
    public List<SoundEvent> writeSoundEvents(UUID runId, List<NewSoundEvent> detections) {
        DigitizationRun run = getRunOrThrow(runId);
        OffsetDateTime now = OffsetDateTime.now();
        double threshold = digitizationProperties.sedPromotionThreshold();

        List<SoundEvent> saved = new ArrayList<>(detections.size());
        int nextSegmentIndex = 0;
        for (NewSoundEvent detection : detections) {
            SoundEvent soundEvent = new SoundEvent();
            soundEvent.setRunId(runId);
            soundEvent.setDocumentId(run.getDocumentId());
            soundEvent.setLabel(detection.label());
            soundEvent.setLabelVi(detection.labelVi());
            soundEvent.setConfidence(detection.confidence());
            soundEvent.setTimeStartMs(detection.timeStartMs());
            soundEvent.setTimeEndMs(detection.timeEndMs());
            soundEvent.setVerified(false);
            soundEvent.setCreatedAt(now);
            soundEvent = soundEventRepository.save(soundEvent);

            // §4 bước 6 / §7 điểm 2: thăng hạng vượt ngưỡng thành
            // content_segment ở trạng thái chờ duyệt bình thường - KHÔNG tự
            // vào chỉ mục ngay khi thăng hạng (chờ transitionReviewState của
            // run 'sed' như OCR/STT).
            if (detection.confidence() != null && detection.confidence().doubleValue() >= threshold) {
                ContentSegment segment = new ContentSegment();
                segment.setRunId(runId);
                segment.setDocumentId(run.getDocumentId());
                segment.setSegmentIndex(nextSegmentIndex++);
                segment.setContentSource(ContentSegment.SOURCE_SED);
                segment.setOrigin(ContentSegment.ORIGIN_AI);
                segment.setTimeStartMs(detection.timeStartMs());
                segment.setTimeEndMs(detection.timeEndMs());
                // Không có cột text riêng ở sound_event - dùng label tiếng
                // Việt (ưu tiên) hoặc nhãn gốc làm nội dung đoạn (DEV gap-fill,
                // spec không mô tả rõ nội dung ai_text cho segment thăng hạng
                // từ SED).
                String text = detection.labelVi() != null ? detection.labelVi() : detection.label();
                segment.setAiText(text);
                segment.setAiTimeStartMs(detection.timeStartMs());
                segment.setAiTimeEndMs(detection.timeEndMs());
                segment.setCurrentText(text);
                segment.setCurrentTimeStartMs(detection.timeStartMs());
                segment.setCurrentTimeEndMs(detection.timeEndMs());
                segment.setCurrentRevisionNo(0);
                segment.setEdited(false);
                segment.setConfidence(detection.confidence());
                segment.setCreatedAt(now);
                segment.setUpdatedAt(now);
                segment = contentSegmentRepository.save(segment);

                soundEvent.setPromotedSegmentId(segment.getId());
                soundEvent = soundEventRepository.save(soundEvent);
            }

            saved.add(soundEvent);
        }
        return saved;
    }

    // ---- completeRunSuccess / completeRunFailure (Pha 2) --------------------

    @Override
    public DigitizationRun completeRunSuccess(UUID runId, RunResultPayload payload) {
        OffsetDateTime now = OffsetDateTime.now();

        int updated = digitizationRunRepository.conditionallyTransitionProcessingStatus(
                runId, DigitizationRun.PROCESSING_SUCCEEDED, now);
        if (updated == 0) {
            log.warn("Duplicate/late succeeded result for run {}, ignoring (not queued/running)", runId);
            return getRunOrThrow(runId);
        }

        DigitizationRun run = getRunOrThrow(runId);
        run.setCompletedAt(now);
        run.setModelName(payload.modelName());
        run.setModelVersion(payload.modelVersion());
        run.setRawOutputPath(payload.rawOutputPath());
        run.setMetrics(payload.metrics());
        run.setInputQualityFlag(payload.inputQualityFlag());

        if (isTimeBasedTask(run.getTask()) && payload.durationMs() != null) {
            digitizationSourceRepository.setDurationMsIfAbsent(run.getSourceId(), payload.durationMs(), now);
        }

        String contentSource = contentSourceFor(run.getTask());
        List<RunResultSegment> resultSegments = payload.segments() == null ? List.of() : payload.segments();
        List<NewSegment> newSegments = new ArrayList<>(resultSegments.size());
        for (RunResultSegment s : resultSegments) {
            newSegments.add(new NewSegment(
                    s.segmentIndex(), contentSource, ContentSegment.ORIGIN_AI,
                    s.pageNo(), s.bboxPolygon(), s.readingOrder(), s.lineFrom(), s.lineTo(),
                    s.timeStartMs(), s.timeEndMs(), s.speakerLabel(), null,
                    s.text(), s.confidence()));
        }
        // Self-invocation, safe here: we are already inside completeRunSuccess's
        // own transaction (see class Javadoc), and writeSegments re-fetches the
        // same managed DigitizationRun instance (JPA identity map) - its
        // segmentCount/lowConfidenceCount mutations are visible on `run` below.
        writeSegments(runId, newSegments);

        // §4.5 bước 2 (Story 4 AC-2/AC-4/AC-5): a *task-specific* confidence gate
        // for the auto-confirm-blocking decision, independent of
        // writeSegments' own LOW_CONFIDENCE_THRESHOLD (that one is fixed at
        // 0.60 for every task to match IDX_content_segment_low_confidence, a
        // general review-UI highlight heuristic - not this quality gate).
        // digitization-pipeline-05-video-review.md: task=video dùng ngưỡng STT
        // (cùng loại kết quả theo mốc thời gian).
        double confidenceThreshold = isTimeBasedTask(run.getTask())
                ? percentRatio(SystemConfigDefaults.OCR_STT_MIN_CONFIDENCE_STT_PERCENT)
                : percentRatio(SystemConfigDefaults.OCR_STT_MIN_CONFIDENCE_OCR_PERCENT);
        double noSpeechProbThreshold = percentRatio(SystemConfigDefaults.OCR_STT_NO_SPEECH_PROB_THRESHOLD_PERCENT);
        double poorRatioThreshold = percentRatio(SystemConfigDefaults.OCR_STT_POOR_RATIO_THRESHOLD_PERCENT);

        int poorCount = 0;
        for (RunResultSegment s : resultSegments) {
            boolean lowConfidence = s.confidence() != null && s.confidence().doubleValue() < confidenceThreshold;
            // Story 4 AC-3: a suspected STT "hallucination" is kept (already
            // written above via writeSegments), just folded into the same
            // poor-quality count as an ordinary low-confidence segment.
            boolean suspectedHallucination = DigitizationRun.TASK_STT.equals(run.getTask())
                    && s.noSpeechProb() != null && s.noSpeechProb().doubleValue() > noSpeechProbThreshold;
            if (lowConfidence || suspectedHallucination) {
                poorCount++;
            }
        }
        int segmentCount = resultSegments.size();
        double poorRatio = segmentCount == 0 ? 0.0 : (double) poorCount / segmentCount;
        boolean poor = poorRatio > poorRatioThreshold;
        run.setOutputQualityFlag(poor ? DigitizationRun.OUTPUT_QUALITY_POOR : DigitizationRun.OUTPUT_QUALITY_OK);
        run.setAutoConfirmBlocked(poor);

        long autoConfirmHours = systemConfigService.getLong(SystemConfigDefaults.OCR_STT_AUTO_CONFIRM_HOURS);
        run.setReviewDeadlineAt(now.plusHours(autoConfirmHours));
        run.setUpdatedAt(now);
        run = digitizationRunRepository.save(run);

        // §4 bước 2 (digitization-results-store.md): review_state is still
        // 'pending' (createRun's initial value, untouched here) -> maps to
        // pending_review, exactly as this spec's §4.5 bước 2 asks for.
        documentDigitizationStatusRepository.upsertTaskStatus(
                run.getDocumentId(), run.getTask(),
                DigitizationStatusMapper.activeRunTaskStatus(run.getProcessingStatus(), run.getReviewState()));

        writeCompletedOutboxEvent(run, segmentCount, poorCount);

        return run;
    }

    @Override
    public DigitizationRun completeRunFailure(UUID runId, String status, String errorCode, String errorMessage) {
        OffsetDateTime now = OffsetDateTime.now();

        // Guard-only claim (mục 0 điểm 5): the *final* processing_status here
        // depends on attempt_count, only knowable once this claim holds the
        // row lock - see DigitizationRunRepository#claimActiveForCompletion.
        int claimed = digitizationRunRepository.claimActiveForCompletion(runId, now);
        if (claimed == 0) {
            log.warn("Duplicate/late {} result for run {}, ignoring (not queued/running)", status, runId);
            return getRunOrThrow(runId);
        }

        DigitizationRun run = getRunOrThrow(runId);
        String finalStatus;
        String finalLastError;

        if (DigitizationRun.PROCESSING_FAILED_PERMANENT.equals(status)) {
            finalStatus = DigitizationRun.PROCESSING_FAILED_PERMANENT;
            finalLastError = errorMessage;
            run.setCompletedAt(now);
        } else {
            int maxRetry = (int) systemConfigService.getLong(SystemConfigDefaults.OCR_STT_MAX_RETRY);
            int attemptCount = run.getAttemptCount() + 1;
            run.setAttemptCount(attemptCount);
            if (attemptCount <= maxRetry) {
                finalStatus = DigitizationRun.PROCESSING_QUEUED;
                finalLastError = errorMessage;
                run.setWorkerId(null);
                run.setLeaseExpiresAt(null);
                publishRetry(run, attemptCount);
            } else {
                finalStatus = DigitizationRun.PROCESSING_FAILED_PERMANENT;
                finalLastError = "exhausted " + maxRetry + " retries, last error: " + errorMessage;
                run.setCompletedAt(now);
            }
        }

        if (DigitizationRun.PROCESSING_FAILED_PERMANENT.equals(finalStatus)) {
            publishDlq(run);
        }

        run.setProcessingStatus(finalStatus);
        run.setLastError(finalLastError);
        run.setUpdatedAt(now);
        run = digitizationRunRepository.save(run);

        documentDigitizationStatusRepository.upsertTaskStatus(
                run.getDocumentId(), run.getTask(),
                DigitizationStatusMapper.activeRunTaskStatus(run.getProcessingStatus(), run.getReviewState()));

        return run;
    }

    private void writeCompletedOutboxEvent(DigitizationRun run, int segmentCount, int lowConfidenceCount) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("documentId", run.getDocumentId().toString());
        payload.put("runId", run.getId().toString());
        payload.put("task", run.getTask());
        payload.put("segmentCount", segmentCount);
        payload.put("lowConfidenceCount", lowConfidenceCount);
        payload.put("qualityFlag", run.getOutputQualityFlag());
        payload.put("autoConfirmBlocked", run.isAutoConfirmBlocked());
        payload.put("reviewDeadlineAt", run.getReviewDeadlineAt() != null ? run.getReviewDeadlineAt().toString() : null);

        OutboxEvent event = new OutboxEvent();
        event.setEventType(OUTBOX_EVENT_DIGITIZATION_COMPLETED);
        event.setPayload(payload);
        event.setCreatedAt(OffsetDateTime.now());
        outboxEventRepository.save(event);
    }

    /** digitization-pipeline-02-ocr-stt-processing.md §4.5 bước 3: re-publish into {@code <queue>.retry} with per-attempt TTL. */
    private void publishRetry(DigitizationRun run, int attemptCount) {
        Map<String, Object> message = buildJobMessage(run);
        if (message == null) {
            return;
        }
        long ttlMs = RETRY_BACKOFF_MS[Math.min(attemptCount, RETRY_BACKOFF_MS.length) - 1];
        String queueName = queueProperties.forTask(run.getTask()) + ".retry";
        rabbitTemplate.convertAndSend(RabbitConfig.EXCHANGE, queueName, message, m -> {
            m.getMessageProperties().setExpiration(Long.toString(ttlMs));
            return m;
        });
    }

    /** digitization-pipeline-02-ocr-stt-processing.md §4.5 bước 3 / §4.6: re-publish into {@code <queue>.dlq} for observability. */
    private void publishDlq(DigitizationRun run) {
        Map<String, Object> message = buildJobMessage(run);
        if (message == null) {
            return;
        }
        String queueName = queueProperties.forTask(run.getTask()) + ".dlq";
        rabbitTemplate.convertAndSend(RabbitConfig.DEAD_LETTER_EXCHANGE, queueName, message);
    }

    private Map<String, Object> buildJobMessage(DigitizationRun run) {
        DigitizationSource source = digitizationSourceRepository.findById(run.getSourceId()).orElse(null);
        Document document = documentRepository.findById(run.getDocumentId()).orElse(null);
        if (source == null || document == null) {
            log.error(
                    "Cannot rebuild job message for run {} - source {} or document {} missing",
                    run.getId(), run.getSourceId(), run.getDocumentId());
            return null;
        }
        return DigitizationJobMessages.build(
                run.getId(), document.getId(), source.getId(), source.getFilePath(),
                document.getMimeType(), source.getMimeType());
    }

    /** {@code system_config} NUMBER value stored as an integer percent (0-100), returned as a 0-1 ratio. */
    private double percentRatio(String key) {
        return systemConfigService.getLong(key) / 100.0;
    }

    private DigitizationRun getRunOrThrow(UUID runId) {
        return digitizationRunRepository.findById(runId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy lượt số hoá: " + runId));
    }

    private static boolean isTerminalProcessingStatus(String status) {
        return DigitizationRun.PROCESSING_SUCCEEDED.equals(status)
                || DigitizationRun.PROCESSING_FAILED_RETRYABLE.equals(status)
                || DigitizationRun.PROCESSING_FAILED_PERMANENT.equals(status)
                || DigitizationRun.PROCESSING_CANCELLED.equals(status);
    }

    /** digitization-pipeline-05-video-review.md: stt/video = kết quả theo mốc thời gian trên tệp gốc. */
    private static boolean isTimeBasedTask(String task) {
        return DigitizationRun.TASK_STT.equals(task) || DigitizationRun.TASK_VIDEO.equals(task);
    }

    private static String contentSourceFor(String task) {
        if (DigitizationRun.TASK_STT.equals(task)) {
            return ContentSegment.SOURCE_STT;
        }
        if (DigitizationRun.TASK_VIDEO.equals(task)) {
            return ContentSegment.SOURCE_VIDEO;
        }
        return ContentSegment.SOURCE_OCR;
    }
}
