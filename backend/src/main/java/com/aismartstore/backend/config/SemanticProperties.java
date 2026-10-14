package com.aismartstore.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** SPEC-search-semantic T1/T9/T10: binds {@code app.semantic.*}. */
@ConfigurationProperties(prefix = "app.semantic")
public record SemanticProperties(
        boolean enabled,
        int queryConcurrency,
        int folderMoveThrottleRps,
        long reconciliationIntervalMs,
        int reconciliationBatchSize,
        int embedConsumerConcurrency,
        int embedMaxRetries,
        long reconciliationGraceMs) {
}
