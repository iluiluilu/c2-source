package com.aismartstore.backend.documents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aismartstore.backend.auth.KeycloakUser;
import com.aismartstore.backend.common.NotFoundException;
import com.aismartstore.backend.groups.DocumentScopeGuard;
import com.aismartstore.backend.storage.ObjectStorageService;
import com.aismartstore.backend.trash.TrashService;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

/**
 * search-fulltext-05-frontend.md §3.1/§5 (Task 2, Task 3) - {@code GET
 * /documents/{id}/pages/{pageNo}/image}, mirroring {@code
 * ReviewControllerPageImageTest}'s mock-every-collaborator pattern, plus the
 * one case that endpoint cannot cover: a document shared to a group (not
 * uploaded by the viewer) - {@link DocumentScopeGuard} is mocked directly
 * here rather than re-exercising {@code BusinessDataScope}'s own group-
 * sharing SQL (already covered by {@code DocumentAccessScopeIT}); the point
 * of this suite is that the controller itself never re-adds a "chính chủ"
 * check on top of {@link DocumentScopeGuard#requireVisible}.
 */
class DocumentsControllerPageImageTest {

    private static final String STORAGE_KEY = "documents/orig-key";

    private DocumentScopeGuard documentScopeGuard;
    private ObjectStorageService objectStorageService;
    private DocumentsController controller;

    private UUID documentId;

    @BeforeEach
    void setUp() {
        DocumentsService documentsService = mock(DocumentsService.class);
        documentScopeGuard = mock(DocumentScopeGuard.class);
        objectStorageService = mock(ObjectStorageService.class);
        PageImageRenderer pageImageRenderer = new PageImageRenderer(objectStorageService);

        controller = new DocumentsController(
                documentsService, documentScopeGuard, pageImageRenderer, mock(TrashService.class),
                mock(com.aismartstore.backend.digitization.results.DocumentDigitizationStatusRepository.class));

        documentId = UUID.randomUUID();
    }

    private static KeycloakUser user(String sub) {
        return new KeycloakUser(sub, sub, List.of());
    }

    private Document document(String uploaderSub, String mimeType) {
        Document document = new Document();
        document.setUploaderSub(uploaderSub);
        document.setMimeType(mimeType);
        document.setStorageKey(STORAGE_KEY);
        return document;
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

    // ---- owner: sees their own document's page image -------------------------

    @Test
    void ownerCanViewTheirOwnDocumentsPageImage() throws Exception {
        String owner = "owner-1";
        Document document = document(owner, MediaType.APPLICATION_PDF_VALUE);
        when(documentScopeGuard.requireVisible(user(owner), documentId)).thenReturn(document);
        byte[] pdfBytes = buildPdf(2);
        when(objectStorageService.getObject(STORAGE_KEY)).thenReturn(fakeS3Stream(pdfBytes));

        ResponseEntity<byte[]> response = controller.getPageImage(user(owner), documentId, 1);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.IMAGE_PNG);
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("private, max-age=3600");
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(response.getBody()));
        assertThat(decoded).isNotNull();
    }

    // ---- case mới (không có ở ReviewControllerPageImageTest): tài liệu người khác
    // nạp nhưng đã chia sẻ nhóm - DocumentScopeGuard cho qua, controller không tự
    // thêm bất kỳ kiểm tra "chính chủ" nào chồng lên nó -------------------------

    @Test
    void memberOfADocumentsSharedGroupCanViewItsPageImageEvenThoughSomeoneElseUploadedIt() {
        String uploader = "uploader-1";
        String groupMember = "group-member-1";
        // DocumentScopeGuard.requireVisible already encapsulates the
        // BusinessDataScope check (own OR shared-to-a-group-I'm-in) - here it
        // is mocked to represent exactly that "shared to my group" outcome,
        // for a document *not* uploaded by groupMember.
        Document document = document(uploader, "image/png");
        when(documentScopeGuard.requireVisible(user(groupMember), documentId)).thenReturn(document);
        byte[] pngBytes = {1, 2, 3, 4};
        when(objectStorageService.getObject(STORAGE_KEY)).thenReturn(fakeS3Stream(pngBytes));

        ResponseEntity<byte[]> response = controller.getPageImage(user(groupMember), documentId, 1);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo(pngBytes);
    }

    // ---- out of scope (không sở hữu, không chia sẻ) -> 404, không phải 403 ----

    @Test
    void outOfScopeReturns404NotFound() {
        String stranger = "stranger-1";
        when(documentScopeGuard.requireVisible(user(stranger), documentId))
                .thenThrow(new NotFoundException("not visible"));

        assertThatThrownBy(() -> controller.getPageImage(user(stranger), documentId, 1))
                .isInstanceOf(NotFoundException.class);
    }

    // ---- pageNo vượt số trang PDF thật -> 404 ----------------------------------

    @Test
    void pageNoBeyondPdfPageCountReturns404() throws Exception {
        String owner = "owner-1";
        Document document = document(owner, MediaType.APPLICATION_PDF_VALUE);
        when(documentScopeGuard.requireVisible(user(owner), documentId)).thenReturn(document);
        byte[] pdfBytes = buildPdf(2);
        when(objectStorageService.getObject(STORAGE_KEY)).thenReturn(fakeS3Stream(pdfBytes));

        assertThatThrownBy(() -> controller.getPageImage(user(owner), documentId, 3))
                .isInstanceOf(NotFoundException.class);
    }

    // ---- mime không phải PDF/image/* -> 404 ------------------------------------

    @Test
    void unsupportedMimeTypeReturns404() {
        String owner = "owner-1";
        Document document = document(owner, "video/mp4");
        when(documentScopeGuard.requireVisible(user(owner), documentId)).thenReturn(document);

        assertThatThrownBy(() -> controller.getPageImage(user(owner), documentId, 1))
                .isInstanceOf(NotFoundException.class);
    }
}
