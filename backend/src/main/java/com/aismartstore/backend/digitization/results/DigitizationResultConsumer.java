package com.aismartstore.backend.digitization.results;

import com.aismartstore.backend.digitization.RabbitConfig;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * digitization-pipeline-02-ocr-stt-processing.md §4.5 (Story 5, Story 6) -
 * {@code q.result} consumer. All the actual state-transition/idempotency/
 * retry-publish logic lives in {@link DigitizationRunLifecycleService}
 * (§3.5) - this class only translates the wire message into that service's
 * contract, keeping the messaging entrypoint thin and testable in isolation
 * from RabbitMQ.
 */
@Component
public class DigitizationResultConsumer {

    private static final Logger log = LoggerFactory.getLogger(DigitizationResultConsumer.class);

    private final DigitizationRunLifecycleService digitizationRunLifecycleService;

    public DigitizationResultConsumer(DigitizationRunLifecycleService digitizationRunLifecycleService) {
        this.digitizationRunLifecycleService = digitizationRunLifecycleService;
    }

    @RabbitListener(queues = RabbitConfig.RESULT_QUEUE, autoStartup = "${app.messaging.consumers-enabled:true}")
    public void onResult(ResultMessage message) {
        if (DigitizationRun.PROCESSING_SUCCEEDED.equals(message.status())) {
            digitizationRunLifecycleService.completeRunSuccess(message.runId(), toPayload(message));
        } else if (DigitizationRun.PROCESSING_FAILED_RETRYABLE.equals(message.status())
                || DigitizationRun.PROCESSING_FAILED_PERMANENT.equals(message.status())) {
            digitizationRunLifecycleService.completeRunFailure(
                    message.runId(), message.status(), message.errorCode(), message.errorMessage());
        } else {
            // §3.4: worker only ever sends one of the 3 status values above -
            // an unknown one is a worker/contract bug, log and drop rather
            // than crash the listener container.
            log.error("q.result for run {} has unknown status '{}', ignoring", message.runId(), message.status());
        }
    }

    private static RunResultPayload toPayload(ResultMessage message) {
        List<ResultSegmentMessage> segments = message.segments() == null ? List.of() : message.segments();
        List<RunResultSegment> converted = segments.stream()
                .map(s -> new RunResultSegment(
                        s.segmentIndex(), s.text(), s.confidence(), s.pageNo(), s.bboxPolygon(),
                        s.readingOrder(), s.lineFrom(), s.lineTo(), s.timeStartMs(), s.timeEndMs(),
                        s.speakerLabel(), s.noSpeechProb()))
                .toList();
        return new RunResultPayload(
                message.modelName(), message.modelVersion(), message.durationMs(),
                message.inputQualityFlag(), message.rawOutputPath(), message.metrics(), converted);
    }
}
