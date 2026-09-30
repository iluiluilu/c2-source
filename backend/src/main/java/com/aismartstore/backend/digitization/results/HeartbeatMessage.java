package com.aismartstore.backend.digitization.results;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.UUID;

/**
 * digitization-pipeline-02-ocr-stt-processing.md §3.2 - {@code q.heartbeat}
 * wire format, published by the (Python) OCR/STT worker. {@code timestamp} is
 * intentionally typed as a plain {@code String} and otherwise unused: the
 * backend computes {@code lease_expires_at} from its own clock (§4.3), it
 * never trusts/parses the worker's.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record HeartbeatMessage(UUID runId, String workerId, String task, String timestamp) {
}
