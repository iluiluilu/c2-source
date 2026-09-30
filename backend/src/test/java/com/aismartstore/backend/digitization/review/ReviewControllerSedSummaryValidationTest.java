package com.aismartstore.backend.digitization.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aismartstore.backend.auth.KeycloakUser;
import com.aismartstore.backend.common.ApiException;
import com.aismartstore.backend.digitization.DigitizationSourceRepository;
import com.aismartstore.backend.digitization.review.dto.AddSegmentRequest;
import com.aismartstore.backend.digitization.review.dto.PatchSegmentRequest;
import com.aismartstore.backend.digitization.results.ContentSegment;
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
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * digitization-review-04-sed-summary.md §3 (API contract table) + §6 (Unit
 * test) - the new tier-2 validates in {@code ReviewController} that only
 * apply to {@code task ∈ {sed, video_summary}}. Same pattern as {@link
 * ReviewControllerSttTimeValidationTest}: every collaborator except the one
 * behaviour under test is mocked out.
 */
class ReviewControllerSedSummaryValidationTest {

    private static final String UPLOADER_SUB = "officer-1";

    private ReviewQueueRepository reviewQueueRepository;
    private ReviewSegmentRepository reviewSegmentRepository;
    private DigitizationRunRepository digitizationRunRepository;
    private ContentSegmentRepository contentSegmentRepository;
    private DocumentRepository documentRepository;
    private DigitizationSourceRepository digitizationSourceRepository;
    private SoundEventRepository soundEventRepository;
    private DigitizationRunLifecycleService lifecycleService;
    private SystemConfigService systemConfigService;
    private ObjectStorageService objectStorageService;
    private ReviewController controller;

    private UUID runId;
    private UUID segmentId;
    private UUID documentId;

    @BeforeEach
    void setUp() {
        reviewQueueRepository = mock(ReviewQueueRepository.class);
        reviewSegmentRepository = mock(ReviewSegmentRepository.class);
        digitizationRunRepository = mock(DigitizationRunRepository.class);
        contentSegmentRepository = mock(ContentSegmentRepository.class);
        documentRepository = mock(DocumentRepository.class);
        digitizationSourceRepository = mock(DigitizationSourceRepository.class);
        soundEventRepository = mock(SoundEventRepository.class);
        lifecycleService = mock(DigitizationRunLifecycleService.class);
        systemConfigService = mock(SystemConfigService.class);
        objectStorageService = mock(ObjectStorageService.class);

        controller = new ReviewController(
                reviewQueueRepository, reviewSegmentRepository, digitizationRunRepository, contentSegmentRepository,
                documentRepository, digitizationSourceRepository, soundEventRepository, lifecycleService,
                systemConfigService, new PageImageRenderer(objectStorageService), mock(DocumentScopeGuard.class));

        runId = UUID.randomUUID();
        segmentId = UUID.randomUUID();
        documentId = UUID.randomUUID();

        Document document = new Document();
        document.setUploaderSub(UPLOADER_SUB);
        when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));
    }

    private DigitizationRun run(String task) {
        DigitizationRun run = new DigitizationRun();
        run.setId(runId);
        run.setDocumentId(documentId);
        run.setTask(task);
        when(digitizationRunRepository.findById(runId)).thenReturn(Optional.of(run));
        return run;
    }

    /** Stubs {@code contentSegmentRepository.findById(segmentId)} - the segment {@code requireSegment} loads. */
    private ContentSegment segment() {
        ContentSegment segment = rawSegment();
        when(contentSegmentRepository.findById(segmentId)).thenReturn(Optional.of(segment));
        return segment;
    }

    private ContentSegment rawSegment() {
        ContentSegment segment = new ContentSegment();
        segment.setId(segmentId);
        segment.setRunId(runId);
        return segment;
    }

    private KeycloakUser user() {
        return new KeycloakUser(UPLOADER_SUB, UPLOADER_SUB, java.util.List.of());
    }

    private static int statusOf(Throwable t) {
        return ((ApiException) t).getStatus().value();
    }

    // ---- AC-5 Story 9: PATCH task=sed + timeStartMs/timeEndMs -> 422 --------

    @Test
    void patchSegment_sedRunWithTimeFields_returns422AndNeverCallsReviseSegment() {
        run(DigitizationRun.TASK_SED);
        segment();

        assertThatThrownBy(() -> controller.reviseSegment(
                        user(), segmentId, new PatchSegmentRequest(0, "nhãn mới", null, 1000, 2000)))
                .isInstanceOf(ApiException.class)
                .matches(t -> statusOf(t) == HttpStatus.UNPROCESSABLE_ENTITY.value());

        verify(lifecycleService, never()).reviseSegment(any(), anyInt(), any(), any(), any(), any(), any());
    }

    @Test
    void patchSegment_sedRunTextOnly_callsReviseSegmentNormally() {
        run(DigitizationRun.TASK_SED);
        segment();
        when(lifecycleService.reviseSegment(eq(segmentId), eq(0), any(), eq(null), eq(null), eq(UPLOADER_SUB), any()))
                .thenReturn(rawSegment());

        controller.reviseSegment(user(), segmentId, new PatchSegmentRequest(0, "nhãn mới", null, null, null));

        verify(lifecycleService).reviseSegment(segmentId, 0, "nhãn mới", null, null, UPLOADER_SUB, null);
    }

    // ---- Story 9 (không có AC thêm sự kiện thủ công) / AC-2 Story 10: POST --

    @Test
    void addSegment_sedRun_returns409AndNeverCallsAddManualSegment() {
        run(DigitizationRun.TASK_SED);

        assertThatThrownBy(() -> controller.addSegment(user(), runId, new AddSegmentRequest(null, "text")))
                .isInstanceOf(ApiException.class)
                .matches(t -> statusOf(t) == HttpStatus.CONFLICT.value());

        verify(lifecycleService, never()).addManualSegment(any(), any(), any(), any());
    }

    @Test
    void addSegment_videoSummaryRun_returns409AndNeverCallsAddManualSegment() {
        run(DigitizationRun.TASK_VIDEO_SUMMARY);

        assertThatThrownBy(() -> controller.addSegment(user(), runId, new AddSegmentRequest(null, "text")))
                .isInstanceOf(ApiException.class)
                .matches(t -> statusOf(t) == HttpStatus.CONFLICT.value());

        verify(lifecycleService, never()).addManualSegment(any(), any(), any(), any());
    }

    @Test
    void addSegment_ocrRun_stillCallsAddManualSegment() {
        run(DigitizationRun.TASK_OCR);
        when(lifecycleService.addManualSegment(eq(runId), any(), eq("text"), eq(UPLOADER_SUB)))
                .thenReturn(rawSegment());

        ResponseEntity<SegmentView> response =
                controller.addSegment(user(), runId, new AddSegmentRequest(null, "text"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        verify(lifecycleService).addManualSegment(runId, null, "text", UPLOADER_SUB);
    }

    // ---- AC-2 Story 10: DELETE task=video_summary -> 409; AC-4 Story 9: DELETE task=sed -> ok ----

    @Test
    void deleteSegment_videoSummaryRun_returns409AndNeverCallsDeleteSegment() {
        run(DigitizationRun.TASK_VIDEO_SUMMARY);
        segment();

        assertThatThrownBy(() -> controller.deleteSegment(user(), segmentId, 0))
                .isInstanceOf(ApiException.class)
                .matches(t -> statusOf(t) == HttpStatus.CONFLICT.value());

        verify(lifecycleService, never()).deleteSegment(any(), anyInt(), any(), any());
    }

    @Test
    void deleteSegment_sedRun_stillCallsDeleteSegment() {
        run(DigitizationRun.TASK_SED);
        segment();
        when(lifecycleService.deleteSegment(segmentId, 0, UPLOADER_SUB, null)).thenReturn(rawSegment());

        controller.deleteSegment(user(), segmentId, 0);

        verify(lifecycleService).deleteSegment(segmentId, 0, UPLOADER_SUB, null);
    }
}
