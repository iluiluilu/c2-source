package com.aismartstore.backend.digitization;

import com.aismartstore.backend.config.QueueProperties;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * digitization-pipeline-02-ocr-stt-processing.md §4.6 (Story 3 AC-5) - passive
 * checks {@code q.ocr.dlq}/{@code q.stt.dlq} message counts and logs a
 * structured {@code ERROR} when non-zero, so an existing log-based alerting
 * stack (NFR-13) can pick it up. Deliberately does not replay DLQ messages -
 * that is a manual operational action, out of scope here (§4.6).
 */
@Component
public class DigitizationDlqAlertJob {

    private static final Logger log = LoggerFactory.getLogger(DigitizationDlqAlertJob.class);

    private final AmqpAdmin amqpAdmin;
    private final QueueProperties queueProperties;

    public DigitizationDlqAlertJob(AmqpAdmin amqpAdmin, QueueProperties queueProperties) {
        this.amqpAdmin = amqpAdmin;
        this.queueProperties = queueProperties;
    }

    @Scheduled(fixedDelayString = "${app.digitization.dlq-alert-interval-ms:300000}")
    public void run() {
        checkDlq(queueProperties.ocrName() + ".dlq");
        checkDlq(queueProperties.sttName() + ".dlq");
        checkDlq(queueProperties.videoName() + ".dlq");
    }

    private void checkDlq(String queueName) {
        Properties properties = amqpAdmin.getQueueProperties(queueName);
        if (properties == null) {
            // Queue not declared yet (e.g. broker restarted and this admin
            // hasn't reconnected) - nothing to alert on this cycle.
            return;
        }
        Object rawCount = properties.get(RabbitAdmin.QUEUE_MESSAGE_COUNT);
        if (rawCount instanceof Integer count && count > 0) {
            log.error("digitization DLQ alert: queue={} pendingMessages={}", queueName, count);
        }
    }
}
