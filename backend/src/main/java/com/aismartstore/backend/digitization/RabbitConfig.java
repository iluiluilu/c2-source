package com.aismartstore.backend.digitization;

import com.aismartstore.backend.config.QueueProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * digitization-pipeline-01-source-dispatch.md §4.6 + digitization-pipeline-02
 * -ocr-stt-processing.md §3/mục 0 điểm 6-7: declares the exchange + queue
 * topology chosen at digitization-pipeline.md Quyet dinh 2. Pha 1 only ever
 * publishes into {@code q.ocr}/{@code q.stt} (see {@link
 * DigitizationDispatchService}); Pha 2 (this spec) adds the two worker->
 * backend queues ({@code q.heartbeat}/{@code q.result}) and the consumer-side
 * logic that reads {@code .retry}/{@code .dlq} (see {@code
 * com.aismartstore.backend.digitization.results.DigitizationRunLifecycleServiceImpl
 * #completeRunFailure}).
 */
@Configuration
public class RabbitConfig {

    public static final String EXCHANGE = "ai.jobs";
    public static final String DEAD_LETTER_EXCHANGE = "ai.jobs.dlx";

    /**
     * digitization-pipeline-02-ocr-stt-processing.md mục 0 điểm 7 / §4.5 bước
     * 3: worker->backend, fire-and-forget lease keepalive - no retry/DLQ
     * needed, a missed heartbeat is covered by {@code
     * DigitizationRunLeaseReconciliationJob}, not by redelivery.
     */
    public static final String HEARTBEAT_QUEUE = "q.heartbeat";

    /**
     * digitization-pipeline-02-ocr-stt-processing.md §3.3/3.4: worker->backend
     * final outcome. §3 Task 3: deliberately no {@code .retry}/{@code .dlq} -
     * a failure to process this message is a backend programming bug, not a
     * job-level failure the {@code ocr_stt.max_retry} backoff should own.
     */
    public static final String RESULT_QUEUE = "q.result";

    private final QueueProperties queueProperties;

    public RabbitConfig(QueueProperties queueProperties) {
        this.queueProperties = queueProperties;
    }

    @Bean
    public TopicExchange aiJobsExchange() {
        return new TopicExchange(EXCHANGE, true, false);
    }

    @Bean
    public TopicExchange aiJobsDeadLetterExchange() {
        return new TopicExchange(DEAD_LETTER_EXCHANGE, true, false);
    }

    // ---- q.ocr ----------------------------------------------------------

    @Bean
    public Queue ocrQueue() {
        return mainQueue(queueProperties.ocrName());
    }

    @Bean
    public Binding ocrBinding() {
        return BindingBuilder.bind(ocrQueue()).to(aiJobsExchange()).with(queueProperties.ocrName());
    }

    @Bean
    public Queue ocrRetryQueue() {
        return retryQueue(queueProperties.ocrName());
    }

    @Bean
    public Binding ocrRetryBinding() {
        return BindingBuilder.bind(ocrRetryQueue()).to(aiJobsExchange()).with(queueProperties.ocrName() + ".retry");
    }

    @Bean
    public Queue ocrDlq() {
        return dlq(queueProperties.ocrName());
    }

    @Bean
    public Binding ocrDlqBinding() {
        return BindingBuilder.bind(ocrDlq())
                .to(aiJobsDeadLetterExchange())
                .with(queueProperties.ocrName() + ".dlq");
    }

    // ---- q.stt ------------------------------------------------------------

    @Bean
    public Queue sttQueue() {
        return mainQueue(queueProperties.sttName());
    }

    @Bean
    public Binding sttBinding() {
        return BindingBuilder.bind(sttQueue()).to(aiJobsExchange()).with(queueProperties.sttName());
    }

    @Bean
    public Queue sttRetryQueue() {
        return retryQueue(queueProperties.sttName());
    }

    @Bean
    public Binding sttRetryBinding() {
        return BindingBuilder.bind(sttRetryQueue()).to(aiJobsExchange()).with(queueProperties.sttName() + ".retry");
    }

    @Bean
    public Queue sttDlq() {
        return dlq(queueProperties.sttName());
    }

    @Bean
    public Binding sttDlqBinding() {
        return BindingBuilder.bind(sttDlq())
                .to(aiJobsDeadLetterExchange())
                .with(queueProperties.sttName() + ".dlq");
    }

    // ---- q.video (digitization-pipeline-03-video-queue.md §1/§4.1) -------

    @Bean
    public Queue videoQueue() {
        return mainQueue(queueProperties.videoName());
    }

    @Bean
    public Binding videoBinding() {
        return BindingBuilder.bind(videoQueue()).to(aiJobsExchange()).with(queueProperties.videoName());
    }

    @Bean
    public Queue videoRetryQueue() {
        return retryQueue(queueProperties.videoName());
    }

    @Bean
    public Binding videoRetryBinding() {
        return BindingBuilder.bind(videoRetryQueue())
                .to(aiJobsExchange())
                .with(queueProperties.videoName() + ".retry");
    }

    @Bean
    public Queue videoDlq() {
        return dlq(queueProperties.videoName());
    }

    @Bean
    public Binding videoDlqBinding() {
        return BindingBuilder.bind(videoDlq())
                .to(aiJobsDeadLetterExchange())
                .with(queueProperties.videoName() + ".dlq");
    }

    // ---- q.embed (search-semantic T6, SPEC-search-semantic indexing §9) ------
    // Parameters MUST match the queues created by hand on stg (2026-09-15): main = durable +
    // x-max-priority=9 + DLX ai.jobs.dlx / key q.embed.dlq; retry = durable + DLX ai.jobs / key q.embed
    // (no TTL); dlq = durable, no arguments. A mismatch means 406 PRECONDITION_FAILED at startup.

    @Bean
    public Queue embedQueue() {
        return mainQueue(queueProperties.embedName());
    }

    @Bean
    public Binding embedBinding() {
        return BindingBuilder.bind(embedQueue()).to(aiJobsExchange()).with(queueProperties.embedName());
    }

    @Bean
    public Queue embedRetryQueue() {
        return retryQueue(queueProperties.embedName());
    }

    @Bean
    public Binding embedRetryBinding() {
        return BindingBuilder.bind(embedRetryQueue())
                .to(aiJobsExchange())
                .with(queueProperties.embedName() + ".retry");
    }

    @Bean
    public Queue embedDlq() {
        return dlq(queueProperties.embedName());
    }

    @Bean
    public Binding embedDlqBinding() {
        return BindingBuilder.bind(embedDlq())
                .to(aiJobsDeadLetterExchange())
                .with(queueProperties.embedName() + ".dlq");
    }

    // ---- q.heartbeat / q.result (Pha 2) --------------------------------

    @Bean
    public Queue heartbeatQueue() {
        return QueueBuilder.durable(HEARTBEAT_QUEUE).build();
    }

    @Bean
    public Binding heartbeatBinding() {
        return BindingBuilder.bind(heartbeatQueue()).to(aiJobsExchange()).with(HEARTBEAT_QUEUE);
    }

    @Bean
    public Queue resultQueue() {
        return QueueBuilder.durable(RESULT_QUEUE).build();
    }

    @Bean
    public Binding resultBinding() {
        return BindingBuilder.bind(resultQueue()).to(aiJobsExchange()).with(RESULT_QUEUE);
    }

    // ---- shared queue shapes ------------------------------------------

    /**
     * digitization-pipeline-02-ocr-stt-processing.md mục 0 điểm 6 (Story 3
     * AC-6/AC-7): {@code x-max-priority=9} so {@link DigitizationDispatchService}
     * and the lease reconciliation job can set an AMQP message {@code
     * priority} (5 interactive / 1 bulk) that RabbitMQ actually honours.
     */
    private static Queue mainQueue(String name) {
        return QueueBuilder.durable(name)
                .withArgument("x-dead-letter-exchange", DEAD_LETTER_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", name + ".dlq")
                .withArgument("x-max-priority", 9)
                .build();
    }

    /**
     * TTL backoff queue: dead-letters back into the main exchange/queue once
     * the TTL expires. digitization-pipeline-02-ocr-stt-processing.md §4.5
     * bước 3 needs a different TTL per retry attempt (1m/5m/30m) - unlike Pha
     * 1's placeholder, this queue intentionally has NO queue-level {@code
     * x-message-ttl} argument: {@code
     * DigitizationRunLifecycleServiceImpl#completeRunFailure} sets a
     * per-message {@code expiration} property instead (RabbitMQ applies
     * whichever of a queue-level TTL and a per-message TTL is lower, so a
     * fixed queue-level TTL would otherwise cap every retry at that value).
     */
    private static Queue retryQueue(String name) {
        return QueueBuilder.durable(name + ".retry")
                .withArgument("x-dead-letter-exchange", EXCHANGE)
                .withArgument("x-dead-letter-routing-key", name)
                .build();
    }

    private static Queue dlq(String name) {
        return QueueBuilder.durable(name + ".dlq").build();
    }

    /**
     * Without this, Spring AMQP's default {@code SimpleMessageConverter}
     * would serialize the {@code Map}/record messages this module builds as a
     * Java-serialized object, not JSON (§4.4 step 3 requires JSON on the wire
     * for the AI worker). {@code JavaTimeModule} is registered explicitly -
     * this converter's {@link ObjectMapper} is private to this bean (not the
     * Spring-managed one Boot auto-configures with JSR-310 support), and Pha 2
     * message records ({@code HeartbeatMessage}/{@code ResultMessage}) carry
     * plain {@code String} timestamps precisely to avoid depending on that;
     * registered anyway as defensive, low-cost future-proofing.
     */
    @Bean
    public MessageConverter jsonMessageConverter() {
        ObjectMapper mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return new Jackson2JsonMessageConverter(mapper);
    }
}
