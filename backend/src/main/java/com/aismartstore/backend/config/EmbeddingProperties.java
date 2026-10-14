package com.aismartstore.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * SPEC-search-semantic T1: binds {@code app.embedding.*} - the internal REST
 * endpoint that turns text into 1024-d dense vectors (Qwen3-Embedding-0.6B,
 * SA QĐ 6). {@code timeoutMs} is for the user's question (interactive path,
 * Q-7); {@code batchTimeoutMs} is for the background embed consumer.
 */
@ConfigurationProperties(prefix = "app.embedding")
public record EmbeddingProperties(
        String url,
        int batchSize,
        int timeoutMs,
        int batchTimeoutMs,
        String modelName,
        String modelVersion,
        int dimension,
        int chunkSizeTokens,
        int chunkOverlapTokens,
        boolean queryInstructionEnabled,
        String queryInstruction) {
}
