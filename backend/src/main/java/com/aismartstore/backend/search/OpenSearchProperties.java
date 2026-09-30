package com.aismartstore.backend.search;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds {@code app.opensearch.*} (search-fulltext-01-tim-kiem-co-ban.md Task
 * 1) - host/port of the OpenSearch cluster already running in
 * {@code docker-compose.yml} (service {@code opensearch}). {@code username}/
 * {@code password} are blank by default (security plugin disabled, e.g.
 * local dev) - {@link OpenSearchClientConfig} only attaches Basic Auth when
 * both are non-blank.
 */
@ConfigurationProperties(prefix = "app.opensearch")
public record OpenSearchProperties(String host, int port, String scheme, String username, String password) {
}
