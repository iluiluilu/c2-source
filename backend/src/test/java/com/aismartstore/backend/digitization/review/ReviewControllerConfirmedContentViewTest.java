package com.aismartstore.backend.digitization.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aismartstore.backend.auth.KeycloakUser;
import com.aismartstore.backend.common.NotFoundException;
import com.aismartstore.backend.digitization.DigitizationSourceRepository;
import com.aismartstore.backend.digitization.results.ContentSegmentRepository;
import com.aismartstore.backend.digitization.results.DigitizationRun;
import com.aismartstore.backend.digitization.results.DigitizationRunLifecycleService;
import com.aismartstore.backend.digitization.results.DigitizationRunRepository;
import com.aismartstore.backend.digitization.results.SoundEventRepository;
import com.aismartstore.backend.documents.Document;
import com.aismartstore.backend.documents.DocumentRepository;
import com.aismartstore.backend.documents.PageImageRenderer;
import com.aismartstore.backend.groups.DocumentScopeGuard;
import com.aismartstore.backend.storage.ObjectStorageService;
import com.aismartstore.backend.systemconfig.SystemConfigService;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * digitization-review-07-confirmed-content-view.md §5: {@code GET
 * /runs/{id}/view} and {@code GET /documents/{id}/confirmed-runs} - gated by
 * {@link DocumentScopeGuard} instead of "chính chủ" + {@code
 * digitization_review.manage}, and must never trigger {@code
 * markFirstViewed} (that side effect belongs to the reviewer's own
 * {@link ReviewController#getRun}).
 */
class ReviewControllerConfirmedContentViewTest {

    private static final String UPLOADER_SUB = "officer-1";
    private static final String OTHER_VIEWER_SUB = "officer-2-group-member";

    private DigitizationRunRepository digitizationRunRepository;
    private DocumentRepository documentRepository;
    private ContentSegmentRepository contentSegmentRepository;
    private DigitizationRunLifecycleService lifecycleService;
    private DocumentScopeGuard documentScopeGuard;
    private ReviewController controller;

    private UUID runId;
    private UUID documentId;
    private Document document;

    @BeforeEach
    void setUp() {
        digitizationRunRepository = mock(DigitizationRunRepository.class);
        documentRepository = mock(DocumentRepository.class);
        contentSegmentRepository = mock(ContentSegmentRepository.class);
        lifecycleService = mock(DigitizationRunLifecycleService.class);
        documentScopeGuard = mock(DocumentScopeGuard.class);
        ObjectStorageService objectStorageService = mock(ObjectStorageService.class);
        SystemConfigService systemConfigService = mock(SystemConfigService.class);

        controller = new ReviewController(
                mock(ReviewQueueRepository.class), mock(ReviewSegmentRepository.class), digitizationRunRepository,
                contentSegmentRepository, documentRepository, mock(DigitizationSourceRepository.class),
                mock(SoundEventRepository.class), lifecycleService, systemConfigService,
                new PageImageRenderer(objectStorageService), documentScopeGuard);

        runId = UUID.randomUUID();
        documentId = UUID.randomUUID();
        document = new Document();
        document.setUploaderSub(UPLOADER_SUB);
        document.setOriginalName("bien-ban.pdf");
        when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));
        when(contentSegmentRepository.findByRunIdOrderBySegmentIndex(runId)).thenReturn(List.of());
    }

    private DigitizationRun run(String task, String reviewState) {
        DigitizationRun run = new DigitizationRun();
        run.setId(runId);
        run.setDocumentId(documentId);
        run.setTask(task);
        run.setReviewState(reviewState);
        when(digitizationRunRepository.findById(runId)).thenReturn(Optional.of(run));
        return run;
    }

    private KeycloakUser user(String sub) {
        return new KeycloakUser(sub, sub, List.of());
    }

    @Test
    void getConfirmedRunView_runNotConfirmed_throws404() {
        run(DigitizationRun.TASK_OCR, DigitizationRun.REVIEW_IN_REVIEW);

        assertThatThrownBy(() -> controller.getConfirmedRunView(user(OTHER_VIEWER_SUB), runId, 0, 100))
                .isInstanceOf(NotFoundException.class);
        verify(documentScopeGuard, never()).requireVisible(any(), any());
    }

    @Test
    void getConfirmedRunView_runNotFound_throws404() {
        when(digitizationRunRepository.findById(runId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.getConfirmedRunView(user(OTHER_VIEWER_SUB), runId, 0, 100))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void getConfirmedRunView_outsideDocumentScope_propagates404FromScopeGuard() {
        run(DigitizationRun.TASK_OCR, DigitizationRun.REVIEW_CONFIRMED);
        when(documentScopeGuard.requireVisible(any(), eq(documentId)))
                .thenThrow(new NotFoundException("Document not found"));

        assertThatThrownBy(() -> controller.getConfirmedRunView(user(OTHER_VIEWER_SUB), runId, 0, 100))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void getConfirmedRunView_confirmedAndVisible_returnsDetailWithoutMarkingFirstViewed() {
        run(DigitizationRun.TASK_OCR, DigitizationRun.REVIEW_CONFIRMED);
        when(documentScopeGuard.requireVisible(any(), eq(documentId))).thenReturn(document);

        // Not the uploader - a group member the document was shared to (the
        // whole point of this endpoint vs. getRun's "chính chủ" gate).
        RunDetailView view = controller.getConfirmedRunView(user(OTHER_VIEWER_SUB), runId, 0, 100);

        assertThat(view.runId()).isEqualTo(runId);
        assertThat(view.reviewState()).isEqualTo(DigitizationRun.REVIEW_CONFIRMED);
        verify(lifecycleService, never()).markFirstViewed(any(), any());
    }

    @Test
    void confirmedRuns_listsOnlyConfirmedRunsOfDocument() {
        when(documentScopeGuard.requireVisible(any(), eq(documentId))).thenReturn(document);
        DigitizationRun confirmedOcr = new DigitizationRun();
        confirmedOcr.setId(UUID.randomUUID());
        confirmedOcr.setDocumentId(documentId);
        confirmedOcr.setTask(DigitizationRun.TASK_OCR);
        confirmedOcr.setReviewState(DigitizationRun.REVIEW_CONFIRMED);
        when(digitizationRunRepository.findByDocumentIdAndReviewState(documentId, DigitizationRun.REVIEW_CONFIRMED))
                .thenReturn(List.of(confirmedOcr));

        List<RunSummaryView> result = controller.confirmedRuns(user(OTHER_VIEWER_SUB), documentId);

        assertThat(result).extracting(RunSummaryView::runId).containsExactly(confirmedOcr.getId());
        assertThat(result).extracting(RunSummaryView::reviewState).containsExactly(DigitizationRun.REVIEW_CONFIRMED);
    }

    @Test
    void confirmedRuns_outsideDocumentScope_propagates404() {
        when(documentScopeGuard.requireVisible(any(), eq(documentId)))
                .thenThrow(new NotFoundException("Document not found"));

        assertThatThrownBy(() -> controller.confirmedRuns(user(OTHER_VIEWER_SUB), documentId))
                .isInstanceOf(NotFoundException.class);
        verify(digitizationRunRepository, never())
                .findByDocumentIdAndReviewState(any(), any());
    }

    @Test
    void confirmedRuns_noConfirmedRuns_returnsEmptyList() {
        when(documentScopeGuard.requireVisible(any(), eq(documentId))).thenReturn(document);
        when(digitizationRunRepository.findByDocumentIdAndReviewState(documentId, DigitizationRun.REVIEW_CONFIRMED))
                .thenReturn(Collections.emptyList());

        assertThat(controller.confirmedRuns(user(OTHER_VIEWER_SUB), documentId)).isEmpty();
    }

    @Test
    void getRun_stillMarksFirstViewed_unaffectedByReadOnlyEndpoint() {
        DigitizationRun pendingRun = run(DigitizationRun.TASK_OCR, DigitizationRun.REVIEW_PENDING);
        pendingRun.setDocumentId(documentId);
        document.setUploaderSub(UPLOADER_SUB);

        controller.getRun(user(UPLOADER_SUB), runId, 0, 100);

        verify(lifecycleService).markFirstViewed(runId, UPLOADER_SUB);
    }
}
