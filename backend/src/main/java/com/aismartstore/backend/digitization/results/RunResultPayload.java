package com.aismartstore.backend.digitization.results;

import java.util.List;
import java.util.Map;

/**
 * digitization-pipeline-02-ocr-stt-processing.md §3.3/§3.5 - the successful
 * {@code q.result} payload passed to {@link
 * DigitizationRunLifecycleService#completeRunSuccess}, already parsed off the
 * wire. Deliberately excludes {@code completedAt}/{@code startedAt}: §4.5
 * step 2 has the backend set {@code completed_at = now()} itself at
 * consumption time rather than trust the worker's clock/message latency.
 *
 * @param durationMs audio length in ms; null for {@code task=ocr}
 */
public record RunResultPayload(
        String modelName,
        String modelVersion,
        Integer durationMs,
        String inputQualityFlag,
        String rawOutputPath,
        Map<String, Object> metrics,
        List<RunResultSegment> segments) {
}
