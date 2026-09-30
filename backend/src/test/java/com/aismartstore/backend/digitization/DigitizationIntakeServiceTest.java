package com.aismartstore.backend.digitization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aismartstore.backend.config.StorageProperties;
import com.aismartstore.backend.documents.Document;
import com.aismartstore.backend.documents.DocumentRepository;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * digitization-pipeline-01-source-dispatch.md §5, unit test bullet 4: the
 * §4.2/§4.3 mimeType routing, shared by {@link DigitizationDispatchListener}
 * and {@link DigitizationDispatchRetryJob}.
 *
 * <p>digitization-pipeline-04-video-no-separation.md §5: video/* now joins
 * the {@code original} branch - no more {@code SourceSeparationJobRepository}
 * dependency to verify/mock here.
 */
class DigitizationIntakeServiceTest {

    private DigitizationSourceRepository digitizationSourceRepository;
    private DocumentRepository documentRepository;
    private DigitizationDispatchService digitizationDispatchService;
    private DigitizationIntakeService service;

    private static final StorageProperties STORAGE =
            new StorageProperties(
                    "http://localhost:8333", "us-east-1", "key", "secret", "documents", "personal", "audit-archive");

    @BeforeEach
    void setUp() {
        digitizationSourceRepository = mock(DigitizationSourceRepository.class);
        documentRepository = mock(DocumentRepository.class);
        digitizationDispatchService = mock(DigitizationDispatchService.class);
        service = new DigitizationIntakeService(
                digitizationSourceRepository, documentRepository, STORAGE, digitizationDispatchService);
    }

    private static Document document(UUID id, String mimeType, String storageKey) {
        Document document = new Document();
        document.setMimeType(mimeType);
        document.setStorageKey(storageKey);
        document.setCreatedAt(OffsetDateTime.now());
        try {
            var idField = Document.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(document, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        return document;
    }

    @Test
    void videoMimeType_createsOriginalDigitizationSource_andPublishesOnce() {
        UUID documentId = UUID.randomUUID();
        when(documentRepository.findById(documentId))
                .thenReturn(Optional.of(document(documentId, "video/mp4", "documents/orig-key")));

        service.handleIngestedDocument(documentId, "video/mp4");

        ArgumentCaptor<DigitizationSource> captor = ArgumentCaptor.forClass(DigitizationSource.class);
        verify(digitizationSourceRepository).save(captor.capture());
        DigitizationSource saved = captor.getValue();
        assertThat(saved.getId()).isEqualTo(documentId);
        assertThat(saved.getDocumentId()).isEqualTo(documentId);
        assertThat(saved.getComponentType()).isEqualTo(DigitizationSource.COMPONENT_ORIGINAL);
        assertThat(saved.getMimeType()).isEqualTo("video/mp4");
        assertThat(saved.getFilePath()).isEqualTo("documents/documents/orig-key");
        assertThat(saved.isDispatchable()).isTrue();
        verify(digitizationDispatchService, times(1)).publish(saved);
    }

    @Test
    void audioMimeType_createsOriginalDigitizationSource_andPublishes() {
        UUID documentId = UUID.randomUUID();
        when(documentRepository.findById(documentId))
                .thenReturn(Optional.of(document(documentId, "audio/mpeg", "documents/orig-key")));

        service.handleIngestedDocument(documentId, "audio/mpeg");

        ArgumentCaptor<DigitizationSource> captor = ArgumentCaptor.forClass(DigitizationSource.class);
        verify(digitizationSourceRepository).save(captor.capture());
        DigitizationSource saved = captor.getValue();
        assertThat(saved.getId()).isEqualTo(documentId);
        assertThat(saved.getDocumentId()).isEqualTo(documentId);
        assertThat(saved.getComponentType()).isEqualTo(DigitizationSource.COMPONENT_ORIGINAL);
        assertThat(saved.getFilePath()).isEqualTo("documents/documents/orig-key");
        assertThat(saved.isDispatchable()).isTrue();
        verify(digitizationDispatchService).publish(saved);
    }

    @Test
    void imageMimeType_createsOriginalDigitizationSource() {
        UUID documentId = UUID.randomUUID();
        when(documentRepository.findById(documentId))
                .thenReturn(Optional.of(document(documentId, "image/png", "documents/orig-key")));

        service.handleIngestedDocument(documentId, "image/png");

        verify(digitizationSourceRepository).save(any());
        verify(digitizationDispatchService).publish(any());
    }

    @Test
    void pdfMimeType_createsOriginalDigitizationSource() {
        UUID documentId = UUID.randomUUID();
        when(documentRepository.findById(documentId))
                .thenReturn(Optional.of(document(documentId, "application/pdf", "documents/orig-key")));

        service.handleIngestedDocument(documentId, "application/pdf");

        verify(digitizationSourceRepository).save(any());
        verify(digitizationDispatchService).publish(any());
    }

    @Test
    void outOfScopeMimeTypes_createNothing() {
        UUID documentId = UUID.randomUUID();

        service.handleIngestedDocument(documentId, "application/msword");
        service.handleIngestedDocument(documentId, "text/plain");
        service.handleIngestedDocument(documentId, "text/csv");
        service.handleIngestedDocument(
                documentId, "application/vnd.openxmlformats-officedocument.wordprocessingml.document");

        verify(digitizationSourceRepository, never()).save(any());
        verify(digitizationDispatchService, never()).publish(any());
        verify(documentRepository, times(0)).findById(any());
    }

    @Test
    void duplicateDigitizationSource_isIdempotent_doesNotRepublish() {
        UUID documentId = UUID.randomUUID();
        when(documentRepository.findById(documentId))
                .thenReturn(Optional.of(document(documentId, "audio/mpeg", "documents/orig-key")));
        when(digitizationSourceRepository.save(any()))
                .thenThrow(new DataIntegrityViolationException("dup", new SQLException("dup", "23505")));

        service.handleIngestedDocument(documentId, "audio/mpeg");

        verify(digitizationDispatchService, never()).publish(any());
    }

    @Test
    void duplicateVideoDigitizationSource_isIdempotent_doesNotRepublish() {
        UUID documentId = UUID.randomUUID();
        when(documentRepository.findById(documentId))
                .thenReturn(Optional.of(document(documentId, "video/webm", "documents/orig-key")));
        when(digitizationSourceRepository.save(any()))
                .thenThrow(new DataIntegrityViolationException("dup", new SQLException("dup", "23505")));

        service.handleIngestedDocument(documentId, "video/webm");

        verify(digitizationDispatchService, never()).publish(any());
    }
}
