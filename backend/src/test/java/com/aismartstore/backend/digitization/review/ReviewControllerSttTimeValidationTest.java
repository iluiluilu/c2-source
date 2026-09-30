package com.aismartstore.backend.digitization.review;

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

/**
 * digitization-review-02-stt.md §5 "Unit test" (mở rộng phần STT của {@code
 * ReviewController.reviseSegment}, không có {@code ReviewService} riêng ở
 * file -01 nên các case này gọi thẳng {@code reviseSegment} với mọi phụ
 * thuộc khác mocked ra - chỉ {@link ReviewSegmentRepository} có hành vi thật
 * sự cần kiểm chứng cho từng case).
 */
class ReviewControllerSttTimeValidationTest {

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

    private DigitizationRun sttRun() {
        DigitizationRun run = new DigitizationRun();
        run.setId(runId);
        run.setDocumentId(documentId);
        run.setTask(DigitizationRun.TASK_STT);
        when(digitizationRunRepository.findById(runId)).thenReturn(Optional.of(run));
        return run;
    }

    private DigitizationRun ocrRun() {
        DigitizationRun run = new DigitizationRun();
        run.setId(runId);
        run.setDocumentId(documentId);
        run.setTask(DigitizationRun.TASK_OCR);
        when(digitizationRunRepository.findById(runId)).thenReturn(Optional.of(run));
        return run;
    }

    /** Stubs {@code contentSegmentRepository.findById(segmentId)} - the segment {@code requireSegment} loads. */
    private ContentSegment segment(Integer currentStart, Integer currentEnd) {
        ContentSegment segment = rawSegment(currentStart, currentEnd);
        when(contentSegmentRepository.findById(segmentId)).thenReturn(Optional.of(segment));
        return segment;
    }

    /** A standalone {@link ContentSegment}, e.g. for stubbing {@code lifecycleService.reviseSegment}'s return value. */
    private ContentSegment rawSegment(Integer currentStart, Integer currentEnd) {
        ContentSegment segment = new ContentSegment();
        segment.setId(segmentId);
        segment.setRunId(runId);
        segment.setCurrentTimeStartMs(currentStart);
        segment.setCurrentTimeEndMs(currentEnd);
        return segment;
    }

    private KeycloakUser user() {
        return new KeycloakUser(UPLOADER_SUB, UPLOADER_SUB, java.util.List.of());
    }

    private static int statusOf(Throwable t) {
        return ((ApiException) t).getStatus().value();
    }

    // ---- "timeStartMs/timeEndMs đều null" ----------------------------------

    @Test
    void bothTimeFieldsNull_doesNotQueryDurationAndCallsReviseSegmentWithTextOnly() {
        sttRun();
        segment(1000, 2000);
        when(lifecycleService.reviseSegment(eq(segmentId), eq(0), any(), any(), any(), any(), any()))
                .thenReturn(rawSegment(1000, 2000));

        controller.reviseSegment(user(), segmentId, new PatchSegmentRequest(0, "new text", null, null, null));

        verify(reviewSegmentRepository, never()).findSourceDurationMs(any());
        verify(lifecycleService).reviseSegment(segmentId, 0, "new text", null, null, UPLOADER_SUB, null);
    }

    // ---- "durationMs = null, gửi timeStartMs" ------------------------------

    @Test
    void durationMsNull_returns422AndNeverCallsReviseSegment() {
        sttRun();
        segment(1000, 2000);
        when(reviewSegmentRepository.findSourceDurationMs(segmentId)).thenReturn(null);

        assertThatThrownBy(() -> controller.reviseSegment(
                        user(), segmentId, new PatchSegmentRequest(0, null, null, 1000, null)))
                .isInstanceOf(ApiException.class)
                .matches(t -> statusOf(t) == HttpStatus.UNPROCESSABLE_ENTITY.value());

        verify(lifecycleService, never()).reviseSegment(any(), anyInt(), any(), any(), any(), any(), any());
    }

    // ---- "hợp lệ" -----------------------------------------------------------

    @Test
    void validBounds_callsReviseSegmentWithExactArgs() {
        sttRun();
        segment(0, 60000);
        when(reviewSegmentRepository.findSourceDurationMs(segmentId)).thenReturn(60000);
        when(lifecycleService.reviseSegment(eq(segmentId), eq(0), any(), eq(1200), eq(4500), eq(UPLOADER_SUB), any()))
                .thenReturn(rawSegment(1200, 4500));

        controller.reviseSegment(user(), segmentId, new PatchSegmentRequest(0, null, "fix", 1200, 4500));

        verify(lifecycleService).reviseSegment(segmentId, 0, null, 1200, 4500, UPLOADER_SUB, "fix");
    }

    // ---- vi phạm mốc thời gian, mỗi case một thông báo khác nhau ------------

    @Test
    void startGreaterThanOrEqualEnd_returns422AndNeverCallsReviseSegment() {
        sttRun();
        segment(0, 60000);
        when(reviewSegmentRepository.findSourceDurationMs(segmentId)).thenReturn(60000);

        assertThatThrownBy(() -> controller.reviseSegment(
                        user(), segmentId, new PatchSegmentRequest(0, null, null, 5000, 5000)))
                .isInstanceOf(ApiException.class)
                .matches(t -> statusOf(t) == HttpStatus.UNPROCESSABLE_ENTITY.value())
                .hasMessageContaining("nhỏ hơn");

        verify(lifecycleService, never()).reviseSegment(any(), anyInt(), any(), any(), any(), any(), any());
    }

    @Test
    void endGreaterThanDuration_returns422AndNeverCallsReviseSegment() {
        sttRun();
        segment(0, 60000);
        when(reviewSegmentRepository.findSourceDurationMs(segmentId)).thenReturn(60000);

        assertThatThrownBy(() -> controller.reviseSegment(
                        user(), segmentId, new PatchSegmentRequest(0, null, null, 1000, 70000)))
                .isInstanceOf(ApiException.class)
                .matches(t -> statusOf(t) == HttpStatus.UNPROCESSABLE_ENTITY.value())
                .hasMessageContaining("vượt quá");

        verify(lifecycleService, never()).reviseSegment(any(), anyInt(), any(), any(), any(), any(), any());
    }

    @Test
    void startNegative_returns422AndNeverCallsReviseSegment() {
        sttRun();
        segment(0, 60000);
        when(reviewSegmentRepository.findSourceDurationMs(segmentId)).thenReturn(60000);

        assertThatThrownBy(() -> controller.reviseSegment(
                        user(), segmentId, new PatchSegmentRequest(0, null, null, -1, 5000)))
                .isInstanceOf(ApiException.class)
                .matches(t -> statusOf(t) == HttpStatus.UNPROCESSABLE_ENTITY.value())
                .hasMessageContaining(">= 0");

        verify(lifecycleService, never()).reviseSegment(any(), anyInt(), any(), any(), any(), any(), any());
    }

    // ---- "chỉ gửi timeEndMs" -------------------------------------------------

    @Test
    void onlyTimeEndMsSent_usesCurrentTimeStartMsAsEffectiveStart() {
        sttRun();
        segment(1000, 2000);
        when(reviewSegmentRepository.findSourceDurationMs(segmentId)).thenReturn(60000);
        when(lifecycleService.reviseSegment(eq(segmentId), eq(0), any(), eq(null), eq(3000), eq(UPLOADER_SUB), any()))
                .thenReturn(rawSegment(1000, 3000));

        controller.reviseSegment(user(), segmentId, new PatchSegmentRequest(0, null, null, null, 3000));

        verify(lifecycleService).reviseSegment(segmentId, 0, null, null, 3000, UPLOADER_SUB, null);
    }

    // ---- run task=ocr gửi kèm timeStartMs -----------------------------------

    @Test
    void ocrRunWithTimeFields_returns400AndNeverCallsReviseSegment() {
        ocrRun();
        segment(null, null);

        assertThatThrownBy(() -> controller.reviseSegment(
                        user(), segmentId, new PatchSegmentRequest(0, null, null, 1000, 2000)))
                .isInstanceOf(ApiException.class)
                .matches(t -> statusOf(t) == HttpStatus.BAD_REQUEST.value());

        verify(lifecycleService, never()).reviseSegment(any(), anyInt(), any(), any(), any(), any(), any());
        verify(reviewSegmentRepository, never()).findSourceDurationMs(any());
    }
}
