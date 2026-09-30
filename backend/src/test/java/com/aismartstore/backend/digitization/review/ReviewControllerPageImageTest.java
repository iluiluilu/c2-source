package com.aismartstore.backend.digitization.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aismartstore.backend.auth.KeycloakUser;
import com.aismartstore.backend.common.ApiException;
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
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Optional;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

/**
 * digitization-review-05-frontend.md §3.1/§5 (Task 1, Task 9 "Unit" bullet) -
 * {@code GET /digitization-review/runs/{runId}/pages/{pageNo}/image}, same
 * mock-every-collaborator pattern as {@link ReviewControllerSttTimeValidationTest}.
 */
class ReviewControllerPageImageTest {

    private static final String UPLOADER_SUB = "officer-1";
    private static final String STORAGE_KEY = "documents/orig-key";

    private DigitizationRunRepository digitizationRunRepository;
    private DocumentRepository documentRepository;
    private ObjectStorageService objectStorageService;
    private ReviewController controller;

    private UUID runId;
    private UUID documentId;

    @BeforeEach
    void setUp() {
        ReviewQueueRepository reviewQueueRepository = mock(ReviewQueueRepository.class);
        ReviewSegmentRepository reviewSegmentRepository = mock(ReviewSegmentRepository.class);
        digitizationRunRepository = mock(DigitizationRunRepository.class);
        ContentSegmentRepository contentSegmentRepository = mock(ContentSegmentRepository.class);
        documentRepository = mock(DocumentRepository.class);
        DigitizationSourceRepository digitizationSourceRepository = mock(DigitizationSourceRepository.class);
        SoundEventRepository soundEventRepository = mock(SoundEventRepository.class);
        DigitizationRunLifecycleService lifecycleService = mock(DigitizationRunLifecycleService.class);
        SystemConfigService systemConfigService = mock(SystemConfigService.class);
        objectStorageService = mock(ObjectStorageService.class);
        PageImageRenderer pageImageRenderer = new PageImageRenderer(objectStorageService);
        DocumentScopeGuard documentScopeGuard = mock(DocumentScopeGuard.class);

        controller = new ReviewController(
                reviewQueueRepository, reviewSegmentRepository, digitizationRunRepository, contentSegmentRepository,
                documentRepository, digitizationSourceRepository, soundEventRepository, lifecycleService,
                systemConfigService, pageImageRenderer, documentScopeGuard);

        runId = UUID.randomUUID();
        documentId = UUID.randomUUID();
    }

    private void seedRun(String mimeType) {
        DigitizationRun run = new DigitizationRun();
        run.setId(runId);
        run.setDocumentId(documentId);
        run.setTask(DigitizationRun.TASK_OCR);
        when(digitizationRunRepository.findById(runId)).thenReturn(Optional.of(run));

        Document document = new Document();
        document.setUploaderSub(UPLOADER_SUB);
        document.setMimeType(mimeType);
        document.setStorageKey(STORAGE_KEY);
        when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));
    }

    private KeycloakUser user() {
        return new KeycloakUser(UPLOADER_SUB, UPLOADER_SUB, java.util.List.of());
    }

    private static int statusOf(Throwable t) {
        return ((ApiException) t).getStatus().value();
    }

    private static byte[] buildPdf(int pageCount) throws Exception {
        try (PDDocument pdf = new PDDocument()) {
            for (int i = 0; i < pageCount; i++) {
                pdf.addPage(new PDPage(PDRectangle.A4));
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            pdf.save(out);
            return out.toByteArray();
        }
    }

    private static ResponseInputStream<GetObjectResponse> fakeS3Stream(byte[] bytes) {
        return new ResponseInputStream<>(
                GetObjectResponse.builder().build(), AbortableInputStream.create(new ByteArrayInputStream(bytes)));
    }

    // ---- PDF: renders the requested page at 150 DPI ---------------------------

    @Test
    void pdfSource_validPageNo_returns200PngSizedFor150Dpi() throws Exception {
        seedRun(MediaType.APPLICATION_PDF_VALUE);
        byte[] pdfBytes = buildPdf(2);
        when(objectStorageService.getObject(STORAGE_KEY)).thenReturn(fakeS3Stream(pdfBytes));

        ResponseEntity<byte[]> response = controller.getPageImage(user(), runId, 1);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.IMAGE_PNG);
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("private, max-age=3600");
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(response.getBody()));
        // A4 (595x842pt) at 150 DPI ~ round(595/72*150) x round(842/72*150) -
        // within 1px of PDFBox's own rounding (its rasterizer does not always
        // match a naive Math.round of the point->pixel conversion).
        assertThat(decoded.getWidth()).isCloseTo(Math.round(595f / 72f * 150f), org.assertj.core.data.Offset.offset(1));
        assertThat(decoded.getHeight()).isCloseTo(Math.round(842f / 72f * 150f), org.assertj.core.data.Offset.offset(1));
    }

    @Test
    void pdfSource_pageNoBeyondPageCount_returns404() throws Exception {
        seedRun(MediaType.APPLICATION_PDF_VALUE);
        byte[] pdfBytes = buildPdf(2);
        when(objectStorageService.getObject(STORAGE_KEY)).thenReturn(fakeS3Stream(pdfBytes));

        assertThatThrownBy(() -> controller.getPageImage(user(), runId, 3))
                .isInstanceOf(ApiException.class)
                .matches(t -> statusOf(t) == HttpStatus.NOT_FOUND.value());
    }

    // ---- image passthrough: no PDFBox involved ---------------------------------

    @Test
    void imageSource_pageOne_returnsRawBytesWithOriginalMimeType() {
        seedRun("image/png");
        byte[] pngBytes = {1, 2, 3, 4};
        when(objectStorageService.getObject(STORAGE_KEY)).thenReturn(fakeS3Stream(pngBytes));

        ResponseEntity<byte[]> response = controller.getPageImage(user(), runId, 1);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.IMAGE_PNG);
        assertThat(response.getBody()).isEqualTo(pngBytes);
    }

    @Test
    void imageSource_pageTwo_returns404() {
        seedRun("image/png");

        assertThatThrownBy(() -> controller.getPageImage(user(), runId, 2))
                .isInstanceOf(ApiException.class)
                .matches(t -> statusOf(t) == HttpStatus.NOT_FOUND.value());
    }

    @Test
    void unsupportedMimeType_returns404() {
        seedRun("video/mp4");

        assertThatThrownBy(() -> controller.getPageImage(user(), runId, 1))
                .isInstanceOf(ApiException.class)
                .matches(t -> statusOf(t) == HttpStatus.NOT_FOUND.value());
    }
}
