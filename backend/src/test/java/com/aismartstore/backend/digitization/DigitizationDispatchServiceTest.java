package com.aismartstore.backend.digitization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aismartstore.backend.config.QueueProperties;
import com.aismartstore.backend.digitization.results.DigitizationRun;
import com.aismartstore.backend.digitization.results.DigitizationRunLifecycleService;
import com.aismartstore.backend.digitization.results.DigitizationRunRepository;
import com.aismartstore.backend.documents.Document;
import com.aismartstore.backend.documents.DocumentRepository;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

/**
 * digitization-pipeline-01-source-dispatch.md §5, unit test bullet 1-2,
 * extended by digitization-pipeline-02-ocr-stt-processing.md §5 bullet 1
 * (mục 0 điểm 1: {@code runId} added to the message, run reused on re-publish).
 */
class DigitizationDispatchServiceTest {

    private static final String OCR_QUEUE = "q.ocr.test";
    private static final String STT_QUEUE = "q.stt.test";
    private static final String VIDEO_QUEUE = "q.video.test";

    private DigitizationSourceRepository digitizationSourceRepository;
    private DocumentRepository documentRepository;
    private RabbitTemplate rabbitTemplate;
    private DigitizationRunLifecycleService digitizationRunLifecycleService;
    private DigitizationRunRepository digitizationRunRepository;
    private DigitizationDispatchService service;

    @BeforeEach
    void setUp() {
        digitizationSourceRepository = mock(DigitizationSourceRepository.class);
        documentRepository = mock(DocumentRepository.class);
        rabbitTemplate = mock(RabbitTemplate.class);
        digitizationRunLifecycleService = mock(DigitizationRunLifecycleService.class);
        digitizationRunRepository = mock(DigitizationRunRepository.class);
        service = new DigitizationDispatchService(
                digitizationSourceRepository, documentRepository, rabbitTemplate,
                new QueueProperties(OCR_QUEUE, STT_QUEUE, VIDEO_QUEUE), digitizationRunLifecycleService,
                digitizationRunRepository);
    }

    private static Document document(UUID id, String mimeType, String storageKey) {
        Document document = new Document();
        document.setStorageKey(storageKey);
        document.setMimeType(mimeType);
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

    private static DigitizationSource source(UUID id, UUID documentId, String mimeType, String filePath) {
        DigitizationSource source = new DigitizationSource();
        source.setId(id);
        source.setDocumentId(documentId);
        source.setComponentType(id.equals(documentId) ? DigitizationSource.COMPONENT_ORIGINAL : DigitizationSource.COMPONENT_AUDIO);
        source.setFilePath(filePath);
        source.setMimeType(mimeType);
        source.setDispatchable(true);
        source.setDispatchStatus(DigitizationSource.DISPATCH_PENDING);
        source.setCreatedAt(OffsetDateTime.now());
        source.setUpdatedAt(OffsetDateTime.now());
        return source;
    }

    private static DigitizationRun run(UUID id, UUID batchId) {
        DigitizationRun run = new DigitizationRun();
        run.setId(id);
        run.setBatchId(batchId);
        return run;
    }

    /** Mirrors {@code RabbitTemplate#convertAndSend(String, String, Object, MessagePostProcessor)}'s effect for assertions. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> capturedMessage(ArgumentCaptor<Object> captor) {
        return (Map<String, Object>) captor.getValue();
    }

    @Test
    void audioWavRoutesToSttQueue_andMessageCarriesTheCreatedRunId() {
        UUID documentId = UUID.randomUUID();
        Document document = document(documentId, "video/mp4", "documents/orig");
        when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));
        DigitizationSource source = source(UUID.randomUUID(), documentId, "audio/wav", "documents/derived/audio.wav");
        UUID runId = UUID.randomUUID();
        when(digitizationRunLifecycleService.createRun(documentId, source.getId(), DigitizationRun.TASK_STT, null, null))
                .thenReturn(run(runId, null));

        service.publish(source);

        ArgumentCaptor<Object> messageCaptor = ArgumentCaptor.forClass(Object.class);
        verify(rabbitTemplate).convertAndSend(
                eq(RabbitConfig.EXCHANGE), eq(STT_QUEUE), messageCaptor.capture(), any(MessagePostProcessor.class));
        Map<String, Object> message = capturedMessage(messageCaptor);
        assertThat(message).containsOnlyKeys(
                "runId", "fileId", "sourceId", "filePath", "originalFileType", "processFileType");
        assertThat(message.get("runId")).isEqualTo(runId.toString());
        assertThat(message.get("fileId")).isEqualTo(documentId.toString());
        assertThat(message.get("sourceId")).isEqualTo(source.getId().toString());
        assertThat(message.get("filePath")).isEqualTo(source.getFilePath());
        assertThat(message.get("originalFileType")).isEqualTo("video/mp4");
        assertThat(message.get("processFileType")).isEqualTo("audio/wav");
        // video-derived audio source: sourceId != fileId.
        assertThat(message.get("sourceId")).isNotEqualTo(message.get("fileId"));
        assertThat(source.getDispatchStatus()).isEqualTo(DigitizationSource.DISPATCH_PUBLISHED);
        assertThat(source.getDispatchedAt()).isNotNull();
    }

    @Test
    void imagePngRoutesToOcrQueue() {
        UUID documentId = UUID.randomUUID();
        when(documentRepository.findById(documentId))
                .thenReturn(Optional.of(document(documentId, "image/png", "documents/orig")));
        // original component: sourceId == fileId (Story 2 AC-2).
        DigitizationSource source = source(documentId, documentId, "image/png", "documents/orig");
        when(digitizationRunLifecycleService.createRun(documentId, documentId, DigitizationRun.TASK_OCR, null, null))
                .thenReturn(run(UUID.randomUUID(), null));

        service.publish(source);

        ArgumentCaptor<Object> messageCaptor = ArgumentCaptor.forClass(Object.class);
        verify(rabbitTemplate).convertAndSend(
                eq(RabbitConfig.EXCHANGE), eq(OCR_QUEUE), messageCaptor.capture(), any(MessagePostProcessor.class));
        Map<String, Object> message = capturedMessage(messageCaptor);
        assertThat(message.get("fileId")).isEqualTo(message.get("sourceId"));
    }

    /**
     * digitization-pipeline-03-video-queue.md §5, unit test bullet 1: {@code
     * video/*} routes to {@code resolveTask() == "video"}, publish targets
     * {@code QUEUE_VIDEO_NAME}.
     */
    @Test
    void videoMp4RoutesToVideoQueue() {
        UUID documentId = UUID.randomUUID();
        when(documentRepository.findById(documentId))
                .thenReturn(Optional.of(document(documentId, "video/mp4", "documents/orig")));
        DigitizationSource source = source(documentId, documentId, "video/mp4", "documents/orig");
        when(digitizationRunLifecycleService.createRun(documentId, documentId, DigitizationRun.TASK_VIDEO, null, null))
                .thenReturn(run(UUID.randomUUID(), null));

        service.publish(source);

        verify(rabbitTemplate).convertAndSend(
                eq(RabbitConfig.EXCHANGE), eq(VIDEO_QUEUE), any(Object.class), any(MessagePostProcessor.class));
    }

    @Test
    void applicationPdfRoutesToOcrQueue() {
        UUID documentId = UUID.randomUUID();
        when(documentRepository.findById(documentId))
                .thenReturn(Optional.of(document(documentId, "application/pdf", "documents/orig")));
        DigitizationSource source = source(documentId, documentId, "application/pdf", "documents/orig");
        when(digitizationRunLifecycleService.createRun(documentId, documentId, DigitizationRun.TASK_OCR, null, null))
                .thenReturn(run(UUID.randomUUID(), null));

        service.publish(source);

        verify(rabbitTemplate).convertAndSend(
                eq(RabbitConfig.EXCHANGE), eq(OCR_QUEUE), any(Object.class), any(MessagePostProcessor.class));
    }

    @Test
    void bulkDispatch_batchIdOnRun_setsLowerAmqpPriority() {
        UUID documentId = UUID.randomUUID();
        when(documentRepository.findById(documentId))
                .thenReturn(Optional.of(document(documentId, "application/pdf", "documents/orig")));
        DigitizationSource source = source(documentId, documentId, "application/pdf", "documents/orig");
        when(digitizationRunLifecycleService.createRun(documentId, documentId, DigitizationRun.TASK_OCR, null, null))
                .thenReturn(run(UUID.randomUUID(), UUID.randomUUID()));

        service.publish(source);

        ArgumentCaptor<MessagePostProcessor> mppCaptor = ArgumentCaptor.forClass(MessagePostProcessor.class);
        verify(rabbitTemplate).convertAndSend(
                eq(RabbitConfig.EXCHANGE), eq(OCR_QUEUE), any(Object.class), mppCaptor.capture());
        Message probe = new Message(new byte[0]);
        Message processed = mppCaptor.getValue().postProcessMessage(probe);
        assertThat(processed.getMessageProperties().getPriority()).isEqualTo(1);
    }

    @Test
    void republishForAlreadyActiveRun_reusesExistingRunId_doesNotCreateANewOne() {
        UUID documentId = UUID.randomUUID();
        when(documentRepository.findById(documentId))
                .thenReturn(Optional.of(document(documentId, "application/pdf", "documents/orig")));
        DigitizationSource source = source(documentId, documentId, "application/pdf", "documents/orig");
        UUID existingRunId = UUID.randomUUID();
        when(digitizationRunLifecycleService.createRun(documentId, documentId, DigitizationRun.TASK_OCR, null, null))
                .thenThrow(new IllegalStateException("already active"));
        when(digitizationRunRepository.findActiveByDocumentAndTask(documentId, DigitizationRun.TASK_OCR))
                .thenReturn(Optional.of(run(existingRunId, null)));

        service.publish(source);

        ArgumentCaptor<Object> messageCaptor = ArgumentCaptor.forClass(Object.class);
        verify(rabbitTemplate).convertAndSend(
                eq(RabbitConfig.EXCHANGE), eq(OCR_QUEUE), messageCaptor.capture(), any(MessagePostProcessor.class));
        assertThat(capturedMessage(messageCaptor).get("runId")).isEqualTo(existingRunId.toString());
    }

    @Test
    void publishExceptionIsSwallowedAndKeepsPending() {
        UUID documentId = UUID.randomUUID();
        when(documentRepository.findById(documentId))
                .thenReturn(Optional.of(document(documentId, "application/pdf", "documents/orig")));
        DigitizationSource source = source(documentId, documentId, "application/pdf", "documents/orig");
        when(digitizationRunLifecycleService.createRun(documentId, documentId, DigitizationRun.TASK_OCR, null, null))
                .thenReturn(run(UUID.randomUUID(), null));
        doThrow(new AmqpException("broker unreachable"))
                .when(rabbitTemplate)
                .convertAndSend(anyString(), anyString(), any(Object.class), any(MessagePostProcessor.class));

        assertThatCode(() -> service.publish(source)).doesNotThrowAnyException();

        assertThat(source.getDispatchStatus()).isEqualTo(DigitizationSource.DISPATCH_PENDING);
        assertThat(source.getDispatchAttemptCount()).isEqualTo(1);
        verify(digitizationSourceRepository).save(source);
    }

    @Test
    void unroutableMimeTypeLeavesSourcePendingWithoutPublishing() {
        UUID documentId = UUID.randomUUID();
        when(documentRepository.findById(documentId))
                .thenReturn(Optional.of(document(documentId, "application/msword", "documents/orig")));
        DigitizationSource source = source(documentId, documentId, "application/msword", "documents/orig");

        service.publish(source);

        verify(rabbitTemplate, never()).convertAndSend(anyString(), anyString(), any(Object.class), any(MessagePostProcessor.class));
        assertThat(source.getDispatchStatus()).isEqualTo(DigitizationSource.DISPATCH_PENDING);
    }
}
