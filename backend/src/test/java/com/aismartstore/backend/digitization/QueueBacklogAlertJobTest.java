package com.aismartstore.backend.digitization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.aismartstore.backend.config.QueueProperties;
import com.aismartstore.backend.config.SemanticProperties;
import java.util.Properties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.rabbit.core.RabbitAdmin;

class QueueBacklogAlertJobTest {

    private final AmqpAdmin admin = mock(AmqpAdmin.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private final Logger logger = (Logger) LoggerFactory.getLogger(QueueBacklogAlertJob.class);
    private QueueBacklogAlertJob job;

    @BeforeEach
    void setUp() {
        appender.start();
        logger.addAppender(appender);
        job = new QueueBacklogAlertJob(
                admin,
                new QueueProperties("a", "b", "c", "q.embed"),
                new SemanticProperties(true, 8, 500, 600000, 100, 1, 5, 3600000L),
                100,
                2);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
    }

    private void queue(String name, int depth, int consumers) {
        Properties p = new Properties();
        p.put(RabbitAdmin.QUEUE_MESSAGE_COUNT, depth);
        p.put(RabbitAdmin.QUEUE_CONSUMER_COUNT, consumers);
        when(admin.getQueueProperties(name)).thenReturn(p);
    }

    private long errorsContaining(String text) {
        return appender.list.stream()
                .filter(e -> e.getLevel() == Level.ERROR && e.getFormattedMessage().contains(text))
                .count();
    }

    @Test
    void logsErrorWhenDepthExceedsTheThreshold() {
        queue("q.embed", 101, 1);
        job.check("q.embed");
        assertThat(errorsContaining("queue backlog queue=q.embed depth=101")).isEqualTo(1);
    }

    @Test
    void staysSilentBelowTheThreshold() {
        queue("q.embed", 100, 1);
        job.check("q.embed");
        assertThat(errorsContaining("queue backlog")).isZero();
    }

    @Test
    void logsAfterMoreThanNTicksWithMessagesAndNoConsumer() {
        queue("q.result", 5, 0);
        job.check("q.result");
        job.check("q.result");
        assertThat(errorsContaining("queue backlog")).isZero(); // streak 2, N = 2
        job.check("q.result");
        assertThat(errorsContaining("consumers=0")).isEqualTo(1);
    }

    @Test
    void anEmptyQueueWithoutConsumersIsNotAnAlert() {
        queue("q.result", 0, 0);
        for (int i = 0; i < 5; i++) {
            job.check("q.result");
        }
        assertThat(errorsContaining("queue backlog")).isZero();
    }
}
