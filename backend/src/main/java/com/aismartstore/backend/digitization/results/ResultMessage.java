package com.aismartstore.backend.digitization.results;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * digitization-pipeline-02-ocr-stt-processing.md §3.3 (succeeded) / §3.4
 * (failed) - {@code q.result} wire format. One shape covers both outcomes
 * (discriminated by {@code status}) since {@link DigitizationResultConsumer}
 * needs a single {@code @RabbitListener} parameter type; fields only
 * meaningful to one outcome are simply null/absent for the other (e.g. {@code
 * segments} for a failure, {@code errorCode} for a success).
 *
 * <p>{@code startedAt}/{@code completedAt} are plain {@code String} and
 * unused: {@code DigitizationRunLifecycleServiceImpl#completeRunSuccess} sets
 * {@code completed_at = now()} itself (§4.5 bước 2), it does not trust the
 * worker's clock.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ResultMessage(
        UUID runId,
        UUID documentId,
        UUID sourceId,
        String task,
        String status,
        String modelName,
        String modelVersion,
        String workerId,
        String startedAt,
        String completedAt,
        Integer durationMs,
        String inputQualityFlag,
        String rawOutputPath,
        Map<String, Object> metrics,
        List<ResultSegmentMessage> segments,
        String errorCode,
        String errorMessage) {
}
