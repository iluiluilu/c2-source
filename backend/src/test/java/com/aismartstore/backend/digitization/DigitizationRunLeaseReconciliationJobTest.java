package com.aismartstore.backend.digitization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aismartstore.backend.config.DigitizationProperties;
import com.aismartstore.backend.config.QueueProperties;
import com.aismartstore.backend.digitization.results.DigitizationRun;
import com.aismartstore.backend.digitization.results.DigitizationRunRepository;
import com.aismartstore.backend.documents.Document;
import com.aismartstore.backend.documents.DocumentRepository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

/**
 * digitization-pipeline-02-ocr-stt-processing.md §5, unit test bullet
 * "DigitizationRunLeaseReconciliationJobTest" (§4.4, Story 3 AC-4).
 */
class DigitizationRunLeaseReconciliationJobTest {

    private static final String OCR_QUEUE = "q.ocr";
    private static final String STT_QUEUE = "q.stt";
    private static final String VIDEO_QUEUE = "q.video";

    private DigitizationRunRepository digitizationRunRepository;
    private DigitizationSourceRepository digitizationSourceRepository;
    private DocumentRepository documentRepository;
    private RabbitTemplate rabbitTemplate;
    private DigitizationRunLeaseReconciliationJob job;

    @BeforeEach
    void setUp() {
        digitizationRunRepository = mock(DigitizationRunRepository.class);
        digitizationSourceRepository = mock(DigitizationSourceRepository.class);
        documentRepository = mock(DocumentRepository.class);
        rabbitTemplate = mock(RabbitTemplate.class);
        DigitizationProperties digitizationProperties = mock(DigitizationProperties.class);
        when(digitizationProperties.leaseCheckBatchSize()).thenReturn(100);

        job = new DigitizationRunLeaseReconciliationJob(
                digitizationRunRepository, digitizationSourceRepository, documentRepository, rabbitTemplate,
                new QueueProperties(OCR_QUEUE, STT_QUEUE, VIDEO_QUEUE), digitizationProperties);
    }

    @Test
    void expiredLease_isRequeuedAndRepublishedToTheCorrectQueue() {
        DigitizationRun run = new DigitizationRun();
        run.setId(UUID.randomUUID());
        run.setDocumentId(UUID.randomUUID());
        run.setSourceId(UUID.randomUUID());
        run.setTask(DigitizationRun.TASK_STT);
        run.setProcessingStatus(DigitizationRun.PROCESSING_RUNNING);
        run.setWorkerId("worker-1");
        run.setLeaseExpiresAt(OffsetDateTime.now().minusMinutes(5));
        run.setAttemptCount(0);
        when(digitizationRunRepository.findExpiredLeasesForUpdate(any(), anyInt())).thenReturn(List.of(run));

        DigitizationSource source = new DigitizationSource();
        source.setId(run.getSourceId());
        source.setDocumentId(run.getDocumentId());
        source.setFilePath("bucket/audio.wav");
        source.setMimeType("audio/wav");
        when(digitizationSourceRepository.findById(run.getSourceId())).thenReturn(Optional.of(source));

        Document document = new Document();
        document.setMimeType("video/mp4");
        try {
            var idField = Document.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(document, run.getDocumentId());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        when(documentRepository.findById(run.getDocumentId())).thenReturn(Optional.of(document));

        job.run();

        assertThat(run.getProcessingStatus()).isEqualTo(DigitizationRun.PROCESSING_QUEUED);
        assertThat(run.getWorkerId()).isNull();
        assertThat(run.getLeaseExpiresAt()).isNull();
        assertThat(run.getAttemptCount()).isZero(); // not counted as a failed attempt (§4.4)
        verify(digitizationRunRepository).save(run);

        ArgumentCaptor<Object> messageCaptor = ArgumentCaptor.forClass(Object.class);
        ArgumentCaptor<MessagePostProcessor> mppCaptor = ArgumentCaptor.forClass(MessagePostProcessor.class);
        verify(rabbitTemplate).convertAndSend(
                eq(RabbitConfig.EXCHANGE), eq(STT_QUEUE), messageCaptor.capture(), mppCaptor.capture());
        @SuppressWarnings("unchecked")
        Map<String, Object> message = (Map<String, Object>) messageCaptor.getValue();
        assertThat(message.get("runId")).isEqualTo(run.getId().toString());
        assertThat(message.get("fileId")).isEqualTo(run.getDocumentId().toString());
        assertThat(message.get("sourceId")).isEqualTo(run.getSourceId().toString());

        Message processed = mppCaptor.getValue().postProcessMessage(new Message(new byte[0]));
        assertThat(processed.getMessageProperties().getPriority()).isEqualTo(5); // no batchId -> interactive
    }

    /**
     * digitization-pipeline-03-video-queue.md §5, integration test bullet 5
     * (mục 4.4 bước 2, §7 rủi ro 2): task {@code video} shares the same
     * {@code lease_expires_at} reconciliation mechanism as every other task -
     * no task-specific branching in this job, so an expired video lease is
     * detected + requeued to {@code q.video} exactly like {@code stt}/{@code
     * ocr} above.
     */
    @Test
    void expiredVideoLease_isRequeuedToTheVideoQueue() {
        DigitizationRun run = new DigitizationRun();
        run.setId(UUID.randomUUID());
        run.setDocumentId(UUID.randomUUID());
        run.setSourceId(UUID.randomUUID());
        run.setTask(DigitizationRun.TASK_VIDEO);
        run.setProcessingStatus(DigitizationRun.PROCESSING_RUNNING);
        run.setWorkerId("video-worker-1");
        run.setLeaseExpiresAt(OffsetDateTime.now().minusMinutes(5));
        run.setAttemptCount(0);
        when(digitizationRunRepository.findExpiredLeasesForUpdate(any(), anyInt())).thenReturn(List.of(run));

        DigitizationSource source = new DigitizationSource();
        source.setId(run.getSourceId());
        source.setDocumentId(run.getDocumentId());
        source.setFilePath("bucket/video.mp4");
        source.setMimeType("video/mp4");
        when(digitizationSourceRepository.findById(run.getSourceId())).thenReturn(Optional.of(source));

        Document document = new Document();
        document.setMimeType("video/mp4");
        try {
            var idField = Document.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(document, run.getDocumentId());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        when(documentRepository.findById(run.getDocumentId())).thenReturn(Optional.of(document));

        job.run();

        assertThat(run.getProcessingStatus()).isEqualTo(DigitizationRun.PROCESSING_QUEUED);
        verify(rabbitTemplate).convertAndSend(
                eq(RabbitConfig.EXCHANGE), eq(VIDEO_QUEUE), any(Object.class), any(MessagePostProcessor.class));
    }

    @Test
    void noExpiredLeases_doesNothing() {
        when(digitizationRunRepository.findExpiredLeasesForUpdate(any(), anyInt())).thenReturn(List.of());

        job.run();

        verify(digitizationRunRepository, never()).save(any());
        verify(rabbitTemplate, never()).convertAndSend(any(String.class), any(String.class), any(), any(MessagePostProcessor.class));
    }
}
