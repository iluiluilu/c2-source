package com.aismartstore.backend.digitization.results;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;

import com.aismartstore.backend.audit.AuditRequestContext;
import com.aismartstore.backend.audit.AuditService;
import com.aismartstore.backend.common.ConflictException;
import com.aismartstore.backend.config.DigitizationProperties;
import com.aismartstore.backend.config.QueueProperties;
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
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

/**
 * digitization-pipeline-02-ocr-stt-processing.md §5, unit test bullets 3-4
 * ({@code completeRunSuccess}/{@code completeRunFailure}).
 */
class DigitizationRunLifecycleServiceImplTest {

    private static final String OCR_QUEUE = "q.ocr";
    private static final String STT_QUEUE = "q.stt";

    private DigitizationRunRepository digitizationRunRepository;
    private ContentSegmentRepository contentSegmentRepository;
    private SegmentRevisionRepository segmentRevisionRepository;
    private DocumentDigitizationStatusRepository documentDigitizationStatusRepository;
    private OutboxEventRepository outboxEventRepository;
    private SystemConfigService systemConfigService;
    private DigitizationSourceRepository digitizationSourceRepository;
    private DocumentRepository documentRepository;
    private RabbitTemplate rabbitTemplate;
    private DigitizationRunLifecycleServiceImpl service;

    @BeforeEach
    void setUp() {
        digitizationRunRepository = mock(DigitizationRunRepository.class);
        contentSegmentRepository = mock(ContentSegmentRepository.class);
        segmentRevisionRepository = mock(SegmentRevisionRepository.class);
        documentDigitizationStatusRepository = mock(DocumentDigitizationStatusRepository.class);
        outboxEventRepository = mock(OutboxEventRepository.class);
        systemConfigService = mock(SystemConfigService.class);
        digitizationSourceRepository = mock(DigitizationSourceRepository.class);
        documentRepository = mock(DocumentRepository.class);
        rabbitTemplate = mock(RabbitTemplate.class);
        QueueProperties queueProperties = new QueueProperties(OCR_QUEUE, STT_QUEUE, "q.video");
        DigitizationProperties digitizationProperties = mock(DigitizationProperties.class);

        // Real registry defaults unless a test overrides a specific key below.
        when(systemConfigService.getLong(anyString())).thenAnswer(
                inv -> Long.parseLong(SystemConfigDefaults.get((String) inv.getArgument(0)).defaultValue()));

        when(digitizationRunRepository.save(any(DigitizationRun.class))).thenAnswer(inv -> inv.getArgument(0));
        when(contentSegmentRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));
        when(contentSegmentRepository.save(any(ContentSegment.class))).thenAnswer(inv -> inv.getArgument(0));
        when(segmentRevisionRepository.save(any(SegmentRevision.class))).thenAnswer(inv -> inv.getArgument(0));

        service = new DigitizationRunLifecycleServiceImpl(
                digitizationRunRepository, contentSegmentRepository, segmentRevisionRepository,
                mock(SoundEventRepository.class), documentDigitizationStatusRepository, digitizationProperties,
                outboxEventRepository, systemConfigService, digitizationSourceRepository, documentRepository,
                rabbitTemplate, queueProperties, mock(AuditService.class), mock(AuditRequestContext.class));
    }

    private static DigitizationRun activeRun(UUID id, String task) {
        DigitizationRun run = new DigitizationRun();
        run.setId(id);
        run.setDocumentId(UUID.randomUUID());
        run.setSourceId(UUID.randomUUID());
        run.setTask(task);
        run.setProcessingStatus(DigitizationRun.PROCESSING_RUNNING);
        run.setReviewState(DigitizationRun.REVIEW_PENDING);
        run.setAttemptCount(0);
        return run;
    }

    private static ContentSegment segment(UUID id, UUID runId, int currentRevisionNo) {
        ContentSegment segment = new ContentSegment();
        segment.setId(id);
        segment.setRunId(runId);
        segment.setDocumentId(UUID.randomUUID());
        segment.setSegmentIndex(0);
        segment.setContentSource(ContentSegment.SOURCE_OCR);
        segment.setOrigin(ContentSegment.ORIGIN_AI);
        segment.setAiText("original");
        segment.setCurrentText("original");
        segment.setCurrentRevisionNo(currentRevisionNo);
        segment.setEdited(false);
        segment.setCreatedAt(OffsetDateTime.now());
        segment.setUpdatedAt(OffsetDateTime.now());
        return segment;
    }

    private static RunResultSegment ocrSegment(int index, double confidence) {
        return new RunResultSegment(index, "text " + index, BigDecimal.valueOf(confidence), 1, null, index, null, null,
                null, null, null, null);
    }

    private void stubSourceAndDocumentFor(DigitizationRun run) {
        DigitizationSource source = new DigitizationSource();
        source.setId(run.getSourceId());
        source.setDocumentId(run.getDocumentId());
        source.setFilePath("bucket/" + run.getSourceId());
        source.setMimeType("image/png");
        when(digitizationSourceRepository.findById(run.getSourceId())).thenReturn(Optional.of(source));

        Document document = new Document();
        document.setMimeType("image/png");
        try {
            var idField = Document.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(document, run.getDocumentId());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        when(documentRepository.findById(run.getDocumentId())).thenReturn(Optional.of(document));
    }

    // ---- completeRunSuccess --------------------------------------------

    @Test
    void completeRunSuccess_belowPoorRatioThreshold_flagsOk() {
        UUID runId = UUID.randomUUID();
        DigitizationRun run = activeRun(runId, DigitizationRun.TASK_OCR);
        when(digitizationRunRepository.conditionallyTransitionProcessingStatus(
                eq(runId), eq(DigitizationRun.PROCESSING_SUCCEEDED), any())).thenReturn(1);
        when(digitizationRunRepository.findById(runId)).thenReturn(Optional.of(run));

        // min_confidence_ocr=60% -> 1 of 4 segments (25%) below threshold; poor_ratio_threshold=30%.
        RunResultPayload payload = new RunResultPayload(
                "paddleocr", "1.0", null, DigitizationRun.INPUT_QUALITY_OK, "derived/x.json", null,
                List.of(ocrSegment(0, 0.90), ocrSegment(1, 0.90), ocrSegment(2, 0.90), ocrSegment(3, 0.40)));

        DigitizationRun result = service.completeRunSuccess(runId, payload);

        // processing_status itself is flipped by the mocked conditional UPDATE
        // (conditionallyTransitionProcessingStatus), not by this in-memory
        // entity - verified separately, at the SQL level, by
        // DigitizationSchemaConstraintsIT/migration checks; this test only
        // covers the quality-gate computation that runs after that update.
        assertThat(result.getOutputQualityFlag()).isEqualTo(DigitizationRun.OUTPUT_QUALITY_OK);
        assertThat(result.isAutoConfirmBlocked()).isFalse();
    }

    @Test
    void completeRunSuccess_abovePoorRatioThreshold_flagsPoorAndBlocksAutoConfirm() {
        UUID runId = UUID.randomUUID();
        DigitizationRun run = activeRun(runId, DigitizationRun.TASK_OCR);
        when(digitizationRunRepository.conditionallyTransitionProcessingStatus(
                eq(runId), eq(DigitizationRun.PROCESSING_SUCCEEDED), any())).thenReturn(1);
        when(digitizationRunRepository.findById(runId)).thenReturn(Optional.of(run));

        // 2 of 4 (50%) below the 60% ocr threshold; poor_ratio_threshold=30% -> poor.
        RunResultPayload payload = new RunResultPayload(
                "paddleocr", "1.0", null, DigitizationRun.INPUT_QUALITY_OK, "derived/x.json", null,
                List.of(ocrSegment(0, 0.90), ocrSegment(1, 0.90), ocrSegment(2, 0.50), ocrSegment(3, 0.40)));

        DigitizationRun result = service.completeRunSuccess(runId, payload);

        assertThat(result.getOutputQualityFlag()).isEqualTo(DigitizationRun.OUTPUT_QUALITY_POOR);
        assertThat(result.isAutoConfirmBlocked()).isTrue();
    }

    @Test
    void completeRunSuccess_exactlyAtPoorRatioThreshold_isNotPoor() {
        UUID runId = UUID.randomUUID();
        DigitizationRun run = activeRun(runId, DigitizationRun.TASK_OCR);
        when(digitizationRunRepository.conditionallyTransitionProcessingStatus(
                eq(runId), eq(DigitizationRun.PROCESSING_SUCCEEDED), any())).thenReturn(1);
        when(digitizationRunRepository.findById(runId)).thenReturn(Optional.of(run));

        // Exactly 30% (3 of 10) below threshold; spec's condition is "> poorRatioThreshold" (strict), so this stays OK.
        List<RunResultSegment> segments = new java.util.ArrayList<>();
        for (int i = 0; i < 7; i++) {
            segments.add(ocrSegment(i, 0.90));
        }
        for (int i = 7; i < 10; i++) {
            segments.add(ocrSegment(i, 0.10));
        }
        RunResultPayload payload = new RunResultPayload(
                "paddleocr", "1.0", null, DigitizationRun.INPUT_QUALITY_OK, "derived/x.json", null, segments);

        DigitizationRun result = service.completeRunSuccess(runId, payload);

        assertThat(result.getOutputQualityFlag()).isEqualTo(DigitizationRun.OUTPUT_QUALITY_OK);
        assertThat(result.isAutoConfirmBlocked()).isFalse();
    }

    @Test
    void completeRunSuccess_reviewDeadline_isCompletedAtPlusAutoConfirmHours() {
        UUID runId = UUID.randomUUID();
        DigitizationRun run = activeRun(runId, DigitizationRun.TASK_OCR);
        when(digitizationRunRepository.conditionallyTransitionProcessingStatus(
                eq(runId), eq(DigitizationRun.PROCESSING_SUCCEEDED), any())).thenReturn(1);
        when(digitizationRunRepository.findById(runId)).thenReturn(Optional.of(run));
        RunResultPayload payload = new RunResultPayload(
                "paddleocr", "1.0", null, DigitizationRun.INPUT_QUALITY_OK, "derived/x.json", null,
                List.of(ocrSegment(0, 0.95)));

        DigitizationRun result = service.completeRunSuccess(runId, payload);

        long autoConfirmHours = Long.parseLong(SystemConfigDefaults.get(SystemConfigDefaults.OCR_STT_AUTO_CONFIRM_HOURS).defaultValue());
        assertThat(result.getReviewDeadlineAt()).isEqualTo(result.getCompletedAt().plusHours(autoConfirmHours));
    }

    @Test
    void completeRunSuccess_stt_setsSourceDurationMsOnFirstWrite() {
        UUID runId = UUID.randomUUID();
        DigitizationRun run = activeRun(runId, DigitizationRun.TASK_STT);
        when(digitizationRunRepository.conditionallyTransitionProcessingStatus(
                eq(runId), eq(DigitizationRun.PROCESSING_SUCCEEDED), any())).thenReturn(1);
        when(digitizationRunRepository.findById(runId)).thenReturn(Optional.of(run));
        RunResultPayload payload = new RunResultPayload(
                "phowhisper", "1.0", 125_300, DigitizationRun.INPUT_QUALITY_OK, "derived/x.json", null,
                List.of(new RunResultSegment(0, "hi", BigDecimal.valueOf(0.9), null, null, null, null, null, 0, 1000, null, null)));

        service.completeRunSuccess(runId, payload);

        verify(digitizationSourceRepository).setDurationMsIfAbsent(eq(run.getSourceId()), eq(125_300), any());
    }

    /** digitization-pipeline-05-video-review.md: task=video đi cùng luồng q.result như stt. */
    @Test
    @SuppressWarnings("unchecked")
    void completeRunSuccess_video_writesVideoSegmentsAndSourceDuration() {
        UUID runId = UUID.randomUUID();
        DigitizationRun run = activeRun(runId, DigitizationRun.TASK_VIDEO);
        when(digitizationRunRepository.conditionallyTransitionProcessingStatus(
                eq(runId), eq(DigitizationRun.PROCESSING_SUCCEEDED), any())).thenReturn(1);
        when(digitizationRunRepository.findById(runId)).thenReturn(Optional.of(run));
        when(contentSegmentRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));
        RunResultPayload payload = new RunResultPayload(
                "video-vlm", "1.0", 60_000, DigitizationRun.INPUT_QUALITY_OK, "derived/x.json", null,
                List.of(new RunResultSegment(0, "cảnh 1", BigDecimal.valueOf(0.9), null, null, null, null, null, 0, 5000, null, null)));

        DigitizationRun result = service.completeRunSuccess(runId, payload);

        ArgumentCaptor<List<ContentSegment>> captor = ArgumentCaptor.forClass(List.class);
        verify(contentSegmentRepository).saveAll(captor.capture());
        assertThat(captor.getValue()).extracting(ContentSegment::getContentSource)
                .containsOnly(ContentSegment.SOURCE_VIDEO);
        verify(digitizationSourceRepository).setDurationMsIfAbsent(eq(run.getSourceId()), eq(60_000), any());
        // processing_status transition itself is mocked above - only the task column routing matters here.
        verify(documentDigitizationStatusRepository).upsertTaskStatus(
                eq(run.getDocumentId()), eq(DigitizationRun.TASK_VIDEO), any());
        assertThat(result.getReviewState()).isEqualTo(DigitizationRun.REVIEW_PENDING);
    }

    @Test
    void completeRunSuccess_writesOutboxEventWithExpectedFields() {
        UUID runId = UUID.randomUUID();
        DigitizationRun run = activeRun(runId, DigitizationRun.TASK_OCR);
        when(digitizationRunRepository.conditionallyTransitionProcessingStatus(
                eq(runId), eq(DigitizationRun.PROCESSING_SUCCEEDED), any())).thenReturn(1);
        when(digitizationRunRepository.findById(runId)).thenReturn(Optional.of(run));
        RunResultPayload payload = new RunResultPayload(
                "paddleocr", "1.0", null, DigitizationRun.INPUT_QUALITY_OK, "derived/x.json", null,
                List.of(ocrSegment(0, 0.95)));

        service.completeRunSuccess(runId, payload);

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository).save(captor.capture());
        OutboxEvent event = captor.getValue();
        assertThat(event.getEventType()).isEqualTo("digitization.completed");
        assertThat(event.getPayload()).containsOnlyKeys(
                "documentId", "runId", "task", "segmentCount", "lowConfidenceCount",
                "qualityFlag", "autoConfirmBlocked", "reviewDeadlineAt");
        assertThat(event.getPayload().get("runId")).isEqualTo(runId.toString());
        assertThat(event.getPayload().get("segmentCount")).isEqualTo(1);
    }

    @Test
    void completeRunSuccess_duplicateOrLateResult_isNoOpAndDoesNotWriteSegments() {
        UUID runId = UUID.randomUUID();
        DigitizationRun alreadyTerminal = activeRun(runId, DigitizationRun.TASK_OCR);
        alreadyTerminal.setProcessingStatus(DigitizationRun.PROCESSING_SUCCEEDED);
        when(digitizationRunRepository.conditionallyTransitionProcessingStatus(
                eq(runId), eq(DigitizationRun.PROCESSING_SUCCEEDED), any())).thenReturn(0);
        when(digitizationRunRepository.findById(runId)).thenReturn(Optional.of(alreadyTerminal));
        RunResultPayload payload = new RunResultPayload(
                "paddleocr", "1.0", null, DigitizationRun.INPUT_QUALITY_OK, "derived/x.json", null,
                List.of(ocrSegment(0, 0.95)));

        DigitizationRun result = service.completeRunSuccess(runId, payload);

        assertThat(result).isSameAs(alreadyTerminal);
        verify(contentSegmentRepository, never()).saveAll(anyList());
        verify(outboxEventRepository, never()).save(any());
    }

    // ---- completeRunFailure ---------------------------------------------

    @Test
    void completeRunFailure_retryable_incrementsAttemptCountAndRepublishesWithFirstBackoff() {
        UUID runId = UUID.randomUUID();
        DigitizationRun run = activeRun(runId, DigitizationRun.TASK_OCR);
        stubSourceAndDocumentFor(run);
        when(digitizationRunRepository.claimActiveForCompletion(eq(runId), any())).thenReturn(1);
        when(digitizationRunRepository.findById(runId)).thenReturn(Optional.of(run));

        DigitizationRun result = service.completeRunFailure(
                runId, DigitizationRun.PROCESSING_FAILED_RETRYABLE, "TIMEOUT", "worker timed out");

        assertThat(result.getAttemptCount()).isEqualTo(1);
        assertThat(result.getProcessingStatus()).isEqualTo(DigitizationRun.PROCESSING_QUEUED);
        assertThat(result.getLastError()).isEqualTo("worker timed out");

        ArgumentCaptor<MessagePostProcessor> mppCaptor = ArgumentCaptor.forClass(MessagePostProcessor.class);
        verify(rabbitTemplate).convertAndSend(eq(RabbitConfig.EXCHANGE), eq(OCR_QUEUE + ".retry"), any(), mppCaptor.capture());
        Message processed = mppCaptor.getValue().postProcessMessage(new Message(new byte[0]));
        assertThat(processed.getMessageProperties().getExpiration()).isEqualTo("60000");
    }

    @Test
    void completeRunFailure_exhaustedRetries_becomesFailedPermanentAndPublishesToDlq() {
        UUID runId = UUID.randomUUID();
        DigitizationRun run = activeRun(runId, DigitizationRun.TASK_STT);
        run.setAttemptCount(3); // already used all 3 retries (default ocr_stt.max_retry)
        stubSourceAndDocumentFor(run);
        when(digitizationRunRepository.claimActiveForCompletion(eq(runId), any())).thenReturn(1);
        when(digitizationRunRepository.findById(runId)).thenReturn(Optional.of(run));

        DigitizationRun result = service.completeRunFailure(
                runId, DigitizationRun.PROCESSING_FAILED_RETRYABLE, "IO_ERROR", "disk full");

        assertThat(result.getAttemptCount()).isEqualTo(4);
        assertThat(result.getProcessingStatus()).isEqualTo(DigitizationRun.PROCESSING_FAILED_PERMANENT);
        assertThat(result.getLastError()).contains("exhausted 3 retries").contains("disk full");

        verify(rabbitTemplate).convertAndSend(eq(RabbitConfig.DEAD_LETTER_EXCHANGE), eq(STT_QUEUE + ".dlq"), any(Object.class));
        verify(rabbitTemplate, never()).convertAndSend(eq(RabbitConfig.EXCHANGE), eq(STT_QUEUE + ".retry"), any(), any(MessagePostProcessor.class));
    }

    @Test
    void completeRunFailure_permanentFromTheStart_doesNotSpendARetryAttempt() {
        UUID runId = UUID.randomUUID();
        DigitizationRun run = activeRun(runId, DigitizationRun.TASK_OCR);
        stubSourceAndDocumentFor(run);
        when(digitizationRunRepository.claimActiveForCompletion(eq(runId), any())).thenReturn(1);
        when(digitizationRunRepository.findById(runId)).thenReturn(Optional.of(run));

        DigitizationRun result = service.completeRunFailure(
                runId, DigitizationRun.PROCESSING_FAILED_PERMANENT, "CORRUPT_FILE", "unreadable header");

        assertThat(result.getAttemptCount()).isZero();
        assertThat(result.getProcessingStatus()).isEqualTo(DigitizationRun.PROCESSING_FAILED_PERMANENT);
        assertThat(result.getLastError()).isEqualTo("unreadable header");
        verify(rabbitTemplate).convertAndSend(eq(RabbitConfig.DEAD_LETTER_EXCHANGE), eq(OCR_QUEUE + ".dlq"), any(Object.class));
    }

    @Test
    void completeRunFailure_duplicateOrLateResult_isNoOpAndDoesNotPublish() {
        UUID runId = UUID.randomUUID();
        DigitizationRun alreadyTerminal = activeRun(runId, DigitizationRun.TASK_OCR);
        alreadyTerminal.setProcessingStatus(DigitizationRun.PROCESSING_SUCCEEDED);
        when(digitizationRunRepository.claimActiveForCompletion(eq(runId), any())).thenReturn(0);
        when(digitizationRunRepository.findById(runId)).thenReturn(Optional.of(alreadyTerminal));

        DigitizationRun result = service.completeRunFailure(
                runId, DigitizationRun.PROCESSING_FAILED_RETRYABLE, "TIMEOUT", "late message");

        assertThat(result).isSameAs(alreadyTerminal);
        verify(rabbitTemplate, never()).convertAndSend(anyString(), anyString(), any(), any(MessagePostProcessor.class));
        verify(rabbitTemplate, never()).convertAndSend(anyString(), anyString(), any(Object.class));
    }

    @ParameterizedTest
    @CsvSource({"1,60000", "2,300000", "3,1800000"})
    void completeRunFailure_retryBackoffTtl_matchesAttemptNumber(int startingAttemptCount, long expectedTtlMs) {
        UUID runId = UUID.randomUUID();
        DigitizationRun run = activeRun(runId, DigitizationRun.TASK_OCR);
        run.setAttemptCount(startingAttemptCount - 1);
        stubSourceAndDocumentFor(run);
        when(digitizationRunRepository.claimActiveForCompletion(eq(runId), any())).thenReturn(1);
        when(digitizationRunRepository.findById(runId)).thenReturn(Optional.of(run));

        service.completeRunFailure(runId, DigitizationRun.PROCESSING_FAILED_RETRYABLE, "TIMEOUT", "boom");

        ArgumentCaptor<MessagePostProcessor> mppCaptor = ArgumentCaptor.forClass(MessagePostProcessor.class);
        verify(rabbitTemplate, times(1))
                .convertAndSend(eq(RabbitConfig.EXCHANGE), eq(OCR_QUEUE + ".retry"), any(), mppCaptor.capture());
        Message processed = mppCaptor.getValue().postProcessMessage(new Message(new byte[0]));
        assertThat(processed.getMessageProperties().getExpiration()).isEqualTo(Long.toString(expectedTtlMs));
    }

    // ---- reviseSegment (digitization-review-01-ocr.md §3.4/§5, US2 AC-2/AC-3) ----

    @Test
    void reviseSegment_wrongExpectedRevisionNo_throwsConflictAndTouchesNothingElse() {
        UUID runId = UUID.randomUUID();
        UUID segmentId = UUID.randomUUID();
        ContentSegment segment = segment(segmentId, runId, 2);
        when(contentSegmentRepository.findById(segmentId)).thenReturn(Optional.of(segment));

        assertThatThrownBy(() -> service.reviseSegment(segmentId, 1, "new text", null, null, "officer-1", "typo"))
                .isInstanceOf(SegmentRevisionConflictException.class)
                .satisfies(e -> {
                    SegmentRevisionConflictException conflict = (SegmentRevisionConflictException) e;
                    assertThat(conflict.getLatestRevisionNo()).isEqualTo(2);
                    assertThat(conflict.getLatestText()).isEqualTo("original");
                });

        verify(segmentRevisionRepository, never()).save(any());
        verify(contentSegmentRepository, never()).save(any());
        // requireExpectedRevision throws before reviseSegment ever needs the owning run.
        verifyNoInteractions(digitizationRunRepository);
    }

    @Test
    void reviseSegment_firstEditOfAPendingRun_movesToInReviewAndSetsFirstEditAtOnce() {
        UUID runId = UUID.randomUUID();
        UUID segmentId = UUID.randomUUID();
        DigitizationRun run = activeRun(runId, DigitizationRun.TASK_OCR);
        run.setReviewState(DigitizationRun.REVIEW_PENDING);
        ContentSegment segment = segment(segmentId, runId, 0);
        when(contentSegmentRepository.findById(segmentId)).thenReturn(Optional.of(segment));
        when(digitizationRunRepository.findById(runId)).thenReturn(Optional.of(run));

        service.reviseSegment(segmentId, 0, "first edit", null, null, "officer-1", "typo");

        assertThat(run.getReviewState()).isEqualTo(DigitizationRun.REVIEW_IN_REVIEW);
        OffsetDateTime firstEditAt = run.getFirstEditAt();
        assertThat(firstEditAt).isNotNull();
        // Bug fix 2026-09-09 (checklist muc 8.16): pending -> in_review must
        // also keep document_digitization_status in sync, exactly once for
        // the first edit (the 2nd edit below stays in_review, no new call).
        verify(documentDigitizationStatusRepository)
                .upsertTaskStatus(run.getDocumentId(), DigitizationRun.TASK_OCR, DocumentDigitizationStatus.STATUS_IN_REVIEW);

        service.reviseSegment(segmentId, 1, "second edit", null, null, "officer-1", "typo 2");

        assertThat(run.getReviewState()).isEqualTo(DigitizationRun.REVIEW_IN_REVIEW);
        assertThat(run.getFirstEditAt()).isEqualTo(firstEditAt); // unchanged on the 2nd edit
        verify(documentDigitizationStatusRepository, times(1)) // still just the one call from the first edit
                .upsertTaskStatus(any(), any(), any());
    }

    @Test
    void reviseSegment_onAConfirmedRun_leavesReviewStateAloneAndEmitsReconfirmed() {
        UUID runId = UUID.randomUUID();
        UUID segmentId = UUID.randomUUID();
        DigitizationRun run = activeRun(runId, DigitizationRun.TASK_OCR);
        run.setReviewState(DigitizationRun.REVIEW_CONFIRMED);
        run.setConfirmSource(DigitizationRun.CONFIRM_MANUAL_UNCHANGED);
        ContentSegment segment = segment(segmentId, runId, 0);
        when(contentSegmentRepository.findById(segmentId)).thenReturn(Optional.of(segment));
        when(digitizationRunRepository.findById(runId)).thenReturn(Optional.of(run));
        // The edit this test makes is the one that makes the count non-zero.
        when(contentSegmentRepository.countByRunIdAndEditedTrue(runId)).thenReturn(1L);

        service.reviseSegment(segmentId, 0, "post-confirm fix", null, null, "officer-1", "typo");

        assertThat(run.getReviewState()).isEqualTo(DigitizationRun.REVIEW_CONFIRMED); // US4 AC-2: unchanged

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository).save(captor.capture());
        assertThat(captor.getValue().getEventType()).isEqualTo("review.reconfirmed");

        // Regression 2026-09-20: confirm_source/edited_segment_count used to stay frozen at
        // whatever confirmRun() computed at the original confirm - a run confirmed clean
        // (manual_unchanged, 0 - as stubbed above) then edited must recompute to manual_edited/1
        // with the exact same formula confirmRun() uses, and the reconfirmed event must carry the
        // fresh value, not the stale one from before this edit.
        assertThat(run.getConfirmSource()).isEqualTo(DigitizationRun.CONFIRM_MANUAL_EDITED);
        assertThat(run.getEditedSegmentCount()).isEqualTo(1);
        assertThat(captor.getValue().getPayload().get("confirmSource")).isEqualTo(DigitizationRun.CONFIRM_MANUAL_EDITED);
    }

    @Test
    void reviseSegment_onAConfirmedRunEditedRunStaysManualEditedEvenIfOriginallyConfirmedByAutoConfirm() {
        UUID runId = UUID.randomUUID();
        UUID segmentId = UUID.randomUUID();
        DigitizationRun run = activeRun(runId, DigitizationRun.TASK_OCR);
        run.setReviewState(DigitizationRun.REVIEW_CONFIRMED);
        run.setConfirmSource(DigitizationRun.CONFIRM_AUTO); // auto-confirmed, never touched by a human until now
        ContentSegment segment = segment(segmentId, runId, 0);
        when(contentSegmentRepository.findById(segmentId)).thenReturn(Optional.of(segment));
        when(digitizationRunRepository.findById(runId)).thenReturn(Optional.of(run));
        when(contentSegmentRepository.countByRunIdAndEditedTrue(runId)).thenReturn(1L);

        service.reviseSegment(segmentId, 0, "human correction after auto-confirm", null, null, "officer-1", "typo");

        // A human has now demonstrably touched this run's content - confirm_source (and the
        // MetadataFilterSpecifications.hasConfirmSource() search filter reading it) must reflect
        // that, not keep reporting 'auto'.
        assertThat(run.getConfirmSource()).isEqualTo(DigitizationRun.CONFIRM_MANUAL_EDITED);
    }

    // ---- confirmRun (digitization-review-01-ocr.md §3.4/§5, US4) ---------------

    @Test
    void confirmRun_zeroEditedSegments_isManualUnchanged() {
        UUID runId = UUID.randomUUID();
        DigitizationRun run = activeRun(runId, DigitizationRun.TASK_OCR);
        run.setReviewState(DigitizationRun.REVIEW_PENDING);
        when(digitizationRunRepository.findById(runId)).thenReturn(Optional.of(run));
        when(contentSegmentRepository.countByRunIdAndEditedTrue(runId)).thenReturn(0L);
        when(digitizationRunRepository.findByDocumentIdAndTaskAndReviewState(any(), anyString(), anyString()))
                .thenReturn(Optional.empty());

        DigitizationRun confirmed = service.confirmRun(runId, "officer-1");

        assertThat(confirmed.getReviewState()).isEqualTo(DigitizationRun.REVIEW_CONFIRMED);
        assertThat(confirmed.getConfirmSource()).isEqualTo(DigitizationRun.CONFIRM_MANUAL_UNCHANGED);
        assertThat(confirmed.getConfirmedBy()).isEqualTo("officer-1");

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository).save(captor.capture());
        assertThat(captor.getValue().getEventType()).isEqualTo("review.confirmed");

        // Bug fix 2026-09-09 (checklist muc 8.16): confirmRun() used to never
        // update document_digitization_status, leaving it stuck at
        // pending_review/in_review forever after a real confirm - wrong on
        // /search (documentDigitizationStatus) and /admin/document-ownership.
        verify(documentDigitizationStatusRepository).upsertTaskStatus(
                confirmed.getDocumentId(), DigitizationRun.TASK_OCR, DocumentDigitizationStatus.STATUS_CONFIRMED);
    }

    @Test
    void confirmRun_withEditedSegments_isManualEdited() {
        UUID runId = UUID.randomUUID();
        DigitizationRun run = activeRun(runId, DigitizationRun.TASK_STT);
        run.setReviewState(DigitizationRun.REVIEW_IN_REVIEW);
        when(digitizationRunRepository.findById(runId)).thenReturn(Optional.of(run));
        when(contentSegmentRepository.countByRunIdAndEditedTrue(runId)).thenReturn(3L);
        when(digitizationRunRepository.findByDocumentIdAndTaskAndReviewState(any(), anyString(), anyString()))
                .thenReturn(Optional.empty());

        DigitizationRun confirmed = service.confirmRun(runId, "officer-1");

        assertThat(confirmed.getConfirmSource()).isEqualTo(DigitizationRun.CONFIRM_MANUAL_EDITED);
        assertThat(confirmed.getEditedSegmentCount()).isEqualTo(3);
    }

    @Test
    void confirmRun_withAnOtherConfirmedRunForSameDocumentAndTask_supersedesIt() {
        UUID documentId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        DigitizationRun run = activeRun(runId, DigitizationRun.TASK_OCR);
        run.setDocumentId(documentId);
        run.setReviewState(DigitizationRun.REVIEW_PENDING);

        UUID otherRunId = UUID.randomUUID();
        DigitizationRun other = activeRun(otherRunId, DigitizationRun.TASK_OCR);
        other.setDocumentId(documentId);
        other.setReviewState(DigitizationRun.REVIEW_CONFIRMED);

        when(digitizationRunRepository.findById(runId)).thenReturn(Optional.of(run));
        when(contentSegmentRepository.countByRunIdAndEditedTrue(runId)).thenReturn(0L);
        when(digitizationRunRepository.findByDocumentIdAndTaskAndReviewState(
                documentId, DigitizationRun.TASK_OCR, DigitizationRun.REVIEW_CONFIRMED))
                .thenReturn(Optional.of(other));

        service.confirmRun(runId, "officer-1");

        assertThat(other.getReviewState()).isEqualTo(DigitizationRun.REVIEW_SUPERSEDED);
    }

    @Test
    void confirmRun_onAnAlreadyConfirmedRun_throwsConflict() {
        UUID runId = UUID.randomUUID();
        DigitizationRun run = activeRun(runId, DigitizationRun.TASK_OCR);
        run.setReviewState(DigitizationRun.REVIEW_CONFIRMED);
        when(digitizationRunRepository.findById(runId)).thenReturn(Optional.of(run));

        assertThatThrownBy(() -> service.confirmRun(runId, "officer-1")).isInstanceOf(ConflictException.class);

        verify(outboxEventRepository, never()).save(any());
    }

    // ---- autoConfirmDueBatch / flagStaleInReviewBatch: thin delegation to ------
    // ---- DigitizationRunRepository (SQL-level behaviour verified by the -------
    // ---- Testcontainers IT, per digitization-review-01-ocr.md §5) -------------

    @Test
    void autoConfirmDueBatch_delegatesToRepositoryWithTheRequestedBatchSize() {
        List<DigitizationRun> expected = List.of(activeRun(UUID.randomUUID(), DigitizationRun.TASK_OCR));
        when(digitizationRunRepository.autoConfirmDueBatch(500)).thenReturn(expected);

        assertThat(service.autoConfirmDueBatch(500)).isSameAs(expected);
    }

    @Test
    void flagStaleInReviewBatch_delegatesToRepositoryWithATimeCutoff() {
        List<DigitizationRun> expected = List.of(activeRun(UUID.randomUUID(), DigitizationRun.TASK_STT));
        ArgumentCaptor<java.time.OffsetDateTime> cutoffCaptor = ArgumentCaptor.forClass(java.time.OffsetDateTime.class);
        when(digitizationRunRepository.flagStaleInReviewBatch(cutoffCaptor.capture(), eq(500))).thenReturn(expected);

        assertThat(service.flagStaleInReviewBatch(72, 500)).isSameAs(expected);
        assertThat(cutoffCaptor.getValue()).isBefore(OffsetDateTime.now().minusHours(71));
    }
}
