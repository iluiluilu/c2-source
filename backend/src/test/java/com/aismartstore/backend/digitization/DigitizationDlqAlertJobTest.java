package com.aismartstore.backend.digitization;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aismartstore.backend.config.QueueProperties;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * digitization-pipeline-02-ocr-stt-processing.md §5, unit test bullet
 * "DigitizationDlqAlertJob" (§4.6, Story 3 AC-5).
 */
@ExtendWith(OutputCaptureExtension.class)
class DigitizationDlqAlertJobTest {

    @Test
    void nonEmptyDlq_logsStructuredError(CapturedOutput output) {
        AmqpAdmin amqpAdmin = mock(AmqpAdmin.class);
        Properties props = new Properties();
        props.put(RabbitAdmin.QUEUE_MESSAGE_COUNT, 3);
        when(amqpAdmin.getQueueProperties(eq("q.ocr.dlq"))).thenReturn(props);
        when(amqpAdmin.getQueueProperties(eq("q.stt.dlq"))).thenReturn(null);

        DigitizationDlqAlertJob job = new DigitizationDlqAlertJob(amqpAdmin, new QueueProperties("q.ocr", "q.stt", "q.video"));
        job.run();

        assertThat(output.getOut() + output.getErr()).contains("q.ocr.dlq").contains("3");
    }

    @Test
    void emptyOrMissingDlq_doesNotLogError(CapturedOutput output) {
        AmqpAdmin amqpAdmin = mock(AmqpAdmin.class);
        Properties props = new Properties();
        props.put(RabbitAdmin.QUEUE_MESSAGE_COUNT, 0);
        when(amqpAdmin.getQueueProperties(eq("q.ocr.dlq"))).thenReturn(props);
        when(amqpAdmin.getQueueProperties(eq("q.stt.dlq"))).thenReturn(null);

        DigitizationDlqAlertJob job = new DigitizationDlqAlertJob(amqpAdmin, new QueueProperties("q.ocr", "q.stt", "q.video"));
        job.run();

        assertThat(output.getOut() + output.getErr()).doesNotContain("digitization DLQ alert");
    }
}
