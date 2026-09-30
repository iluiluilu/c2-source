package com.aismartstore.backend.digitization;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * digitization-pipeline-02-ocr-stt-processing.md §3.1 - the 6-field {@code
 * q.ocr}/{@code q.stt} job message shape, shared by every place that
 * publishes (or re-publishes) one: {@link DigitizationDispatchService} (first
 * dispatch), {@link
 * com.aismartstore.backend.digitization.results.DigitizationRunLifecycleServiceImpl}
 * (retry-with-backoff after a {@code failed_retryable} result), and the lease
 * reconciliation job (a worker died mid-job). Kept as one place so all three
 * agree on field names/types without copy-paste drift.
 */
public final class DigitizationJobMessages {

    private DigitizationJobMessages() {
    }

    public static Map<String, Object> build(
            UUID runId, UUID fileId, UUID sourceId, String filePath, String originalFileType, String processFileType) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("runId", runId.toString());
        message.put("fileId", fileId.toString());
        message.put("sourceId", sourceId.toString());
        message.put("filePath", filePath);
        message.put("originalFileType", originalFileType);
        message.put("processFileType", processFileType);
        return message;
    }
}
