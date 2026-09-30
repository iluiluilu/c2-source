package com.aismartstore.backend.digitization.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aismartstore.backend.auth.KeycloakUser;
import com.aismartstore.backend.digitization.DigitizationSourceRepository;
import com.aismartstore.backend.digitization.results.ContentSegment;
import com.aismartstore.backend.digitization.results.ContentSegmentRepository;
import com.aismartstore.backend.digitization.results.DigitizationRun;
import com.aismartstore.backend.digitization.results.DigitizationRunLifecycleService;
import com.aismartstore.backend.digitization.results.DigitizationRunRepository;
import com.aismartstore.backend.digitization.results.SoundEvent;
import com.aismartstore.backend.digitization.results.SoundEventRepository;
import com.aismartstore.backend.documents.Document;
import com.aismartstore.backend.documents.DocumentRepository;
import com.aismartstore.backend.documents.PageImageRenderer;
import com.aismartstore.backend.groups.DocumentScopeGuard;
import com.aismartstore.backend.storage.ObjectStorageService;
import com.aismartstore.backend.systemconfig.SystemConfigService;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * digitization-review-04-sed-summary.md §2.1 (AC-1 Story 9), §2.3 (AC-5 Story
 * 10), §6 (Unit test) - {@code GET /runs/{id}}'s new enrichment
 * (soundEventLabel, sourceReferences, staleWarning), exercised through
 * {@code ReviewController.getRun} with every collaborator mocked.
 */
class ReviewControllerRunDetailSedSummaryTest {

    private static final String UPLOADER_SUB = "officer-1";

    private ContentSegmentRepository contentSegmentRepository;
    private DocumentRepository documentRepository;
    private DigitizationRunRepository digitizationRunRepository;
    private DigitizationSourceRepository digitizationSourceRepository;
    private SoundEventRepository soundEventRepository;
    private ReviewSegmentRepository reviewSegmentRepository;
    private DigitizationRunLifecycleService lifecycleService;
    private SystemConfigService systemConfigService;
    private ObjectStorageService objectStorageService;
    private ReviewController controller;

    private UUID runId;
    private UUID documentId;

    @BeforeEach
    void setUp() {
        contentSegmentRepository = mock(ContentSegmentRepository.class);
        documentRepository = mock(DocumentRepository.class);
        digitizationRunRepository = mock(DigitizationRunRepository.class);
        digitizationSourceRepository = mock(DigitizationSourceRepository.class);
        soundEventRepository = mock(SoundEventRepository.class);
        reviewSegmentRepository = mock(ReviewSegmentRepository.class);
        lifecycleService = mock(DigitizationRunLifecycleService.class);
        systemConfigService = mock(SystemConfigService.class);
        objectStorageService = mock(ObjectStorageService.class);

        controller = new ReviewController(
                mock(ReviewQueueRepository.class), reviewSegmentRepository, digitizationRunRepository,
                contentSegmentRepository, documentRepository, digitizationSourceRepository, soundEventRepository,
                lifecycleService, systemConfigService, new PageImageRenderer(objectStorageService),
                mock(DocumentScopeGuard.class));

        runId = UUID.randomUUID();
        documentId = UUID.randomUUID();

        Document document = new Document();
        document.setUploaderSub(UPLOADER_SUB);
        document.setOriginalName("audio.mp3");
        when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));
    }

    private DigitizationRun run(String task, String reviewState, OffsetDateTime confirmedAt) {
        DigitizationRun run = new DigitizationRun();
        run.setId(runId);
        run.setDocumentId(documentId);
        run.setTask(task);
        run.setReviewState(reviewState);
        run.setConfirmedAt(confirmedAt);
        when(digitizationRunRepository.findById(runId)).thenReturn(Optional.of(run));
        return run;
    }

    private KeycloakUser user() {
        return new KeycloakUser(UPLOADER_SUB, UPLOADER_SUB, List.of());
    }

    // ---- AC-1 Story 9: soundEventLabel joined from sound_event ---------------

    @Test
    void sedRun_segmentsCarrySoundEventLabelFromPromotedSegmentId() {
        run(DigitizationRun.TASK_SED, DigitizationRun.REVIEW_PENDING, null);

        UUID seg1 = UUID.randomUUID();
        UUID seg2 = UUID.randomUUID();
        ContentSegment s1 = sedSegment(seg1, 0);
        ContentSegment s2 = sedSegment(seg2, 1);
        when(contentSegmentRepository.findByRunIdOrderBySegmentIndex(runId)).thenReturn(List.of(s1, s2));

        SoundEvent e1 = soundEvent(seg1, "Dog bark");
        SoundEvent e2 = soundEvent(seg2, "Music");
        when(soundEventRepository.findByPromotedSegmentIdIn(any())).thenReturn(List.of(e1, e2));

        RunDetailView view = controller.getRun(user(), runId, 0, 100);

        assertThat(view.segments().content()).extracting(SegmentView::soundEventLabel)
                .containsExactlyInAnyOrder("Dog bark", "Music");
        assertThat(view.staleWarning()).isFalse();
    }

    // ---- AC-5 Story 10: staleWarning formula -----------------------------------

    @Test
    void videoSummaryRun_notConfirmed_staleWarningAlwaysFalse() {
        run(DigitizationRun.TASK_VIDEO_SUMMARY, DigitizationRun.REVIEW_IN_REVIEW, null);
        UUID summarySegmentId = UUID.randomUUID();
        UUID sourceSegmentId = UUID.randomUUID();
        ContentSegment summarySegment = summarySegment(summarySegmentId, sourceSegmentId);
        when(contentSegmentRepository.findByRunIdOrderBySegmentIndex(runId)).thenReturn(List.of(summarySegment));
        when(reviewSegmentRepository.resolveSourceReferences(any())).thenReturn(List.of(
                sourceReferenceRow(sourceSegmentId, "confirmed", OffsetDateTime.now(), OffsetDateTime.now())));

        RunDetailView view = controller.getRun(user(), runId, 0, 100);

        assertThat(view.staleWarning()).isFalse();
    }

    @Test
    void videoSummaryRun_staleTimestampEqualsConfirmedAt_staleWarningFalse() {
        OffsetDateTime confirmedAt = OffsetDateTime.now();
        run(DigitizationRun.TASK_VIDEO_SUMMARY, DigitizationRun.REVIEW_CONFIRMED, confirmedAt);
        UUID summarySegmentId = UUID.randomUUID();
        UUID sourceSegmentId = UUID.randomUUID();
        when(contentSegmentRepository.findByRunIdOrderBySegmentIndex(runId))
                .thenReturn(List.of(summarySegment(summarySegmentId, sourceSegmentId)));
        when(reviewSegmentRepository.resolveSourceReferences(any())).thenReturn(List.of(
                sourceReferenceRow(sourceSegmentId, "confirmed", confirmedAt, confirmedAt)));

        RunDetailView view = controller.getRun(user(), runId, 0, 100);

        assertThat(view.staleWarning()).isFalse();
    }

    @Test
    void videoSummaryRun_sourceSegmentUpdatedAfterConfirmedAt_staleWarningTrue() {
        OffsetDateTime confirmedAt = OffsetDateTime.now();
        run(DigitizationRun.TASK_VIDEO_SUMMARY, DigitizationRun.REVIEW_CONFIRMED, confirmedAt);
        UUID summarySegmentId = UUID.randomUUID();
        UUID sourceSegmentId = UUID.randomUUID();
        when(contentSegmentRepository.findByRunIdOrderBySegmentIndex(runId))
                .thenReturn(List.of(summarySegment(summarySegmentId, sourceSegmentId)));
        when(reviewSegmentRepository.resolveSourceReferences(any())).thenReturn(List.of(
                sourceReferenceRow(sourceSegmentId, "confirmed", confirmedAt.plusNanos(1_000_000), confirmedAt)));

        RunDetailView view = controller.getRun(user(), runId, 0, 100);

        assertThat(view.staleWarning()).isTrue();
    }

    @Test
    void videoSummaryRun_sourceRunSupersededAfterConfirmedAt_staleWarningTrueEvenIfSegmentUntouched() {
        OffsetDateTime confirmedAt = OffsetDateTime.now();
        run(DigitizationRun.TASK_VIDEO_SUMMARY, DigitizationRun.REVIEW_CONFIRMED, confirmedAt);
        UUID summarySegmentId = UUID.randomUUID();
        UUID sourceSegmentId = UUID.randomUUID();
        when(contentSegmentRepository.findByRunIdOrderBySegmentIndex(runId))
                .thenReturn(List.of(summarySegment(summarySegmentId, sourceSegmentId)));
        // segment itself not touched (older than confirmedAt), but the source
        // run was superseded afterwards - §2.3's second GREATEST branch.
        when(reviewSegmentRepository.resolveSourceReferences(any())).thenReturn(List.of(
                sourceReferenceRow(
                        sourceSegmentId, "superseded", confirmedAt.minusSeconds(60), confirmedAt.plusSeconds(60))));

        RunDetailView view = controller.getRun(user(), runId, 0, 100);

        assertThat(view.staleWarning()).isTrue();
    }

    @Test
    void videoSummaryRun_bothOlderThanConfirmedAt_staleWarningFalse() {
        OffsetDateTime confirmedAt = OffsetDateTime.now();
        run(DigitizationRun.TASK_VIDEO_SUMMARY, DigitizationRun.REVIEW_CONFIRMED, confirmedAt);
        UUID summarySegmentId = UUID.randomUUID();
        UUID sourceSegmentId = UUID.randomUUID();
        when(contentSegmentRepository.findByRunIdOrderBySegmentIndex(runId))
                .thenReturn(List.of(summarySegment(summarySegmentId, sourceSegmentId)));
        when(reviewSegmentRepository.resolveSourceReferences(any())).thenReturn(List.of(
                sourceReferenceRow(
                        sourceSegmentId, "superseded", confirmedAt.minusSeconds(60), confirmedAt.minusSeconds(30))));

        RunDetailView view = controller.getRun(user(), runId, 0, 100);

        assertThat(view.staleWarning()).isFalse();
    }

    // ---- helpers --------------------------------------------------------------

    private ContentSegment sedSegment(UUID id, int index) {
        ContentSegment segment = new ContentSegment();
        segment.setId(id);
        segment.setRunId(runId);
        segment.setSegmentIndex(index);
        segment.setContentSource(ContentSegment.SOURCE_SED);
        segment.setAiText("label");
        segment.setCurrentText("label");
        return segment;
    }

    private ContentSegment summarySegment(UUID id, UUID sourceSegmentId) {
        ContentSegment segment = new ContentSegment();
        segment.setId(id);
        segment.setRunId(runId);
        segment.setSegmentIndex(0);
        segment.setContentSource(ContentSegment.SOURCE_SUMMARY);
        segment.setAiText("summary");
        segment.setCurrentText("summary");
        segment.setSourceSegmentIds(List.of(sourceSegmentId.toString()));
        return segment;
    }

    private SoundEvent soundEvent(UUID promotedSegmentId, String label) {
        SoundEvent event = new SoundEvent();
        event.setPromotedSegmentId(promotedSegmentId);
        event.setLabel(label);
        return event;
    }

    private ReviewSegmentRepository.SourceReferenceRow sourceReferenceRow(
            UUID sourceSegmentId, String sourceRunReviewState, OffsetDateTime sourceSegmentUpdatedAt,
            OffsetDateTime sourceRunUpdatedAt) {
        return new ReviewSegmentRepository.SourceReferenceRow(
                sourceSegmentId, UUID.randomUUID(), 3, 12000, sourceRunReviewState, sourceSegmentUpdatedAt,
                sourceRunUpdatedAt);
    }
}
