package com.aismartstore.backend.digitization.results;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class DigitizationHeartbeatConsumerTest {

    @Test
    void heartbeat_forwardsRunIdAndWorkerIdToRepository() {
        DigitizationRunRepository repository = mock(DigitizationRunRepository.class);
        when(repository.heartbeat(any(), any(), any())).thenReturn(1);
        DigitizationHeartbeatConsumer consumer = new DigitizationHeartbeatConsumer(repository);
        UUID runId = UUID.randomUUID();

        consumer.onHeartbeat(new HeartbeatMessage(runId, "worker-1", "ocr", "2026-09-05T10:00:00Z"));

        verify(repository).heartbeat(eq(runId), eq("worker-1"), any());
    }

    @Test
    void heartbeatForTerminalRun_isSilentlyIgnored() {
        DigitizationRunRepository repository = mock(DigitizationRunRepository.class);
        when(repository.heartbeat(any(), any(), any())).thenReturn(0);
        DigitizationHeartbeatConsumer consumer = new DigitizationHeartbeatConsumer(repository);

        consumer.onHeartbeat(new HeartbeatMessage(UUID.randomUUID(), "worker-1", "ocr", "2026-09-05T10:00:00Z"));

        verify(repository).heartbeat(any(), any(), any());
    }
}
