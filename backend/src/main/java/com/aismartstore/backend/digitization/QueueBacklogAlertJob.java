package com.aismartstore.backend.digitization;

import com.aismartstore.backend.config.QueueProperties;
import com.aismartstore.backend.config.SemanticProperties;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * backend-multi-instance Task 17 (AC-26): the worker is a single point of consumption, so a stuck or dead worker
 * must be visible. Logs {@code ERROR queue backlog ...} when {@code q.embed}/{@code q.result} hold more than the
 * configured depth, or hold messages with zero consumers for more than N consecutive ticks. Log-only by design
 * (spec Q5): the key {@code queue backlog} is what log alerting greps for.
 */
@Component
public class QueueBacklogAlertJob {

    private static final Logger log = LoggerFactory.getLogger(QueueBacklogAlertJob.class);

    private final AmqpAdmin amqpAdmin;
    private final QueueProperties queueProperties;
    private final SemanticProperties semanticProperties;
    private final int depthThreshold;
    private final int noConsumerTicks;
    private final Map<String, Integer> noConsumerStreak = new HashMap<>();

    public QueueBacklogAlertJob(
            AmqpAdmin amqpAdmin,
            QueueProperties queueProperties,
            SemanticProperties semanticProperties,
            @Value("${app.queue-alert.depth-threshold:1000}") int depthThreshold,
            @Value("${app.queue-alert.no-consumer-ticks:3}") int noConsumerTicks) {
        this.amqpAdmin = amqpAdmin;
        this.queueProperties = queueProperties;
        this.semanticProperties = semanticProperties;
        this.depthThreshold = depthThreshold;
        this.noConsumerTicks = noConsumerTicks;
    }

    @Scheduled(fixedDelayString = "${app.queue-alert.interval-ms:60000}")
    @SchedulerLock(name = "queue-backlog-alert", lockAtMostFor = "PT5M", lockAtLeastFor = "PT55S")
    public void run() {
        if (semanticProperties.enabled()) {
            check(queueProperties.embedName());
        }
        check(RabbitConfig.RESULT_QUEUE);
    }

    /** Package-visible for tests. */
    void check(String queueName) {
        Properties properties;
        try {
            properties = amqpAdmin.getQueueProperties(queueName);
        } catch (RuntimeException e) {
            log.warn("queue backlog check: cannot read queue {}: {}", queueName, e.toString());
            return;
        }
        if (properties == null) {
            return; // not declared yet / broker unreachable this tick
        }
        int depth = asInt(properties.get(RabbitAdmin.QUEUE_MESSAGE_COUNT));
        int consumers = asInt(properties.get(RabbitAdmin.QUEUE_CONSUMER_COUNT));

        if (depth > depthThreshold) {
            log.error("queue backlog queue={} depth={} consumers={} threshold={}",
                    queueName, depth, consumers, depthThreshold);
        }
        if (depth > 0 && consumers == 0) {
            int streak = noConsumerStreak.merge(queueName, 1, Integer::sum);
            if (streak > noConsumerTicks) {
                log.error("queue backlog queue={} depth={} consumers=0 for {} consecutive checks - is the worker running?",
                        queueName, depth, streak);
            }
        } else {
            noConsumerStreak.remove(queueName);
        }
    }

    private static int asInt(Object value) {
        return value instanceof Number n ? n.intValue() : 0;
    }
}
