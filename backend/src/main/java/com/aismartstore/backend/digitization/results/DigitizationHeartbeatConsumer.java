package com.aismartstore.backend.digitization.results;

import com.aismartstore.backend.digitization.RabbitConfig;
import java.time.OffsetDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * digitization-pipeline-02-ocr-stt-processing.md §4.3 - {@code q.heartbeat}
 * consumer: {@code queued -> running} on the first heartbeat, lease refresh
 * on every subsequent one (90s = 3x the worker's 30s heartbeat period).
 * Fire-and-forget by design (mục 0 điểm 2/§3.2) - no ack/retry/DLQ semantics
 * to worry about beyond Spring AMQP's default auto-ack.
 */
@Component
public class DigitizationHeartbeatConsumer {

    private static final Logger log = LoggerFactory.getLogger(DigitizationHeartbeatConsumer.class);

    /** 3x the worker's 30s heartbeat period (§4.3) - tolerates 1-2 missed heartbeats before a run is deemed stuck. */
    private static final long LEASE_SECONDS = 90;

    private final DigitizationRunRepository digitizationRunRepository;

    public DigitizationHeartbeatConsumer(DigitizationRunRepository digitizationRunRepository) {
        this.digitizationRunRepository = digitizationRunRepository;
    }

    @RabbitListener(queues = RabbitConfig.HEARTBEAT_QUEUE)
    public void onHeartbeat(HeartbeatMessage message) {
        OffsetDateTime leaseExpiresAt = OffsetDateTime.now().plusSeconds(LEASE_SECONDS);
        int updated = digitizationRunRepository.heartbeat(message.runId(), message.workerId(), leaseExpiresAt);
        if (updated == 0) {
            log.debug("Heartbeat for run {} ignored (not queued/running)", message.runId());
        }
    }
}
