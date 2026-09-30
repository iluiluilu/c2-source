package com.aismartstore.backend.digitization.results;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * digitization-pipeline-02-ocr-stt-processing.md §5, unit test bullet
 * "DigitizationResultConsumerTest".
 */
class DigitizationResultConsumerTest {

    private final DigitizationRunLifecycleService service = mock(DigitizationRunLifecycleService.class);
    private final DigitizationResultConsumer consumer = new DigitizationResultConsumer(service);

    @Test
    void succeededMessage_callsCompleteRunSuccessWithConvertedPayload() {
        UUID runId = UUID.randomUUID();
        ResultSegmentMessage segment = new ResultSegmentMessage(
                0, "hello", BigDecimal.valueOf(0.9), null, null, null, null, null, 0, 1000, null, null);
        ResultMessage message = new ResultMessage(
                runId, UUID.randomUUID(), UUID.randomUUID(), "stt", "succeeded",
                "phowhisper", "1.0", "worker-1", "2026-09-05T10:00:00Z", "2026-09-05T10:05:00Z",
                125_300, "ok", "derived/x.json", Map.of("avgConfidence", 0.9), List.of(segment), null, null);

        consumer.onResult(message);

        ArgumentCaptor<RunResultPayload> captor = ArgumentCaptor.forClass(RunResultPayload.class);
        verify(service).completeRunSuccess(eq(runId), captor.capture());
        RunResultPayload payload = captor.getValue();
        assertThat(payload.modelName()).isEqualTo("phowhisper");
        assertThat(payload.durationMs()).isEqualTo(125_300);
        assertThat(payload.segments()).hasSize(1);
        assertThat(payload.segments().get(0).text()).isEqualTo("hello");
        verify(service, never()).completeRunFailure(any(), any(), any(), any());
    }

    @Test
    void failedRetryableMessage_callsCompleteRunFailure() {
        UUID runId = UUID.randomUUID();
        ResultMessage message = new ResultMessage(
                runId, UUID.randomUUID(), UUID.randomUUID(), "ocr", "failed_retryable",
                null, null, "worker-1", null, null, null, "poor_scan", null, null, null, "TIMEOUT", "timed out");

        consumer.onResult(message);

        verify(service).completeRunFailure(runId, "failed_retryable", "TIMEOUT", "timed out");
        verify(service, never()).completeRunSuccess(any(), any());
    }

    @Test
    void failedPermanentMessage_callsCompleteRunFailure() {
        UUID runId = UUID.randomUUID();
        ResultMessage message = new ResultMessage(
                runId, UUID.randomUUID(), UUID.randomUUID(), "ocr", "failed_permanent",
                null, null, "worker-1", null, null, null, "ok", null, null, null, "CORRUPT_FILE", "bad header");

        consumer.onResult(message);

        verify(service).completeRunFailure(runId, "failed_permanent", "CORRUPT_FILE", "bad header");
    }

    @Test
    void unknownStatus_isLoggedAndDropped_doesNotCallService() {
        UUID runId = UUID.randomUUID();
        ResultMessage message = new ResultMessage(
                runId, UUID.randomUUID(), UUID.randomUUID(), "ocr", "bogus-status",
                null, null, "worker-1", null, null, null, "ok", null, null, null, null, null);

        consumer.onResult(message);

        verify(service, never()).completeRunSuccess(any(), any());
        verify(service, never()).completeRunFailure(any(), any(), any(), any());
    }
}
