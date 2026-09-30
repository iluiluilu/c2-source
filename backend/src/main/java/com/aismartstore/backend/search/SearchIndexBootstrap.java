package com.aismartstore.backend.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch.generic.Bodies;
import org.opensearch.client.opensearch.generic.Requests;
import org.opensearch.client.opensearch.generic.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * search-fulltext-01-tim-kiem-co-ban.md Task 3: creates the {@code
 * segment-v1}/{@code document-v1} physical indices (mapping §2) and their
 * {@code segment}/{@code document} aliases on startup, if they do not already
 * exist. Idempotent (checked via {@code indices().exists}) so it is safe to
 * run on every boot in every environment - deliberately plain Java on the
 * main thread (not a Flyway migration - OpenSearch is not a relational
 * schema, and mapping changes here go through reindex + alias swap, not
 * {@code ALTER}), per Task 3's "quyết định cách bootstrap khi implement".
 *
 * <p>Best-effort: a failure here is logged loudly but does not stop the
 * application from starting (OpenSearch being down at boot must not prevent
 * every other route from working - the degraded-mode fallback in {@code
 * SearchService} already handles OpenSearch being unavailable at query
 * time).
 */
@Component
@Order(Integer.MAX_VALUE)
public class SearchIndexBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SearchIndexBootstrap.class);

    /**
     * The transport (OpenSearchClientConfig) deliberately sets no default
     * request timeout - per-request timeouts are the caller's job (see
     * SearchServiceImpl's executeWithTimeout). This bootstrap step is the
     * one caller that used to skip that contract: it blocked directly on
     * {@code client.indices()...}, which - when OpenSearch is unreachable -
     * does not fail fast (observed hanging indefinitely, not just for the
     * client5-level connect timeout). A blocking startup step must never
     * hang the whole application, so every call here now goes through a
     * bounded future, same pattern as SearchServiceImpl.
     */
    private static final long BOOTSTRAP_TIMEOUT_MS = 10_000;

    private final OpenSearchClient client;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    public SearchIndexBootstrap(OpenSearchClient client) {
        this.client = client;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            createIfAbsent(
                    SearchIndexNames.SEGMENT_PHYSICAL_INDEX,
                    SearchIndexNames.SEGMENT_ALIAS,
                    "search/segment-v1-index.json");
            createIfAbsent(
                    SearchIndexNames.DOCUMENT_PHYSICAL_INDEX,
                    SearchIndexNames.DOCUMENT_ALIAS,
                    "search/document-v1-index.json");
        } finally {
            // One-shot startup step - nothing else uses this executor afterwards.
            executor.shutdownNow();
        }
    }

    private void createIfAbsent(String physicalIndex, String alias, String mappingResourcePath) {
        try {
            boolean exists = withTimeout(() -> client.indices().exists(e -> e.index(physicalIndex)).value());
            if (exists) {
                log.info("OpenSearch index '{}' already exists, skipping bootstrap", physicalIndex);
                return;
            }

            String body = buildCreateIndexBody(mappingResourcePath, alias);
            withTimeout(() -> {
                try (Response response = client.generic()
                        .execute(Requests.create(
                                "PUT", "/" + physicalIndex, List.of(), Map.of(), Bodies.json(body)))) {
                    if (response.getStatus() >= 300) {
                        throw new IOException("PUT /" + physicalIndex + " failed: " + response.getStatus() + " "
                                + response.getBody()
                                        .map(org.opensearch.client.opensearch.generic.Body::bodyAsString)
                                        .orElse(""));
                    }
                    return null;
                }
            });
            log.info("Created OpenSearch index '{}' with alias '{}'", physicalIndex, alias);
        } catch (IOException | RuntimeException e) {
            log.error(
                    "Failed to bootstrap OpenSearch index '{}' (alias '{}') - the app will keep starting; "
                            + "POST /search will degrade until this is fixed",
                    physicalIndex,
                    alias,
                    e);
        }
    }

    /**
     * Runs one blocking OpenSearch call on a background thread and bounds
     * how long the ApplicationRunner (and therefore app startup) waits for
     * it. A timeout is reported the same way as any other failure here - the
     * calling createIfAbsent's catch block logs it and lets startup proceed.
     */
    private <T> T withTimeout(Callable<T> task) throws IOException {
        Future<T> future = executor.submit(task);
        try {
            return future.get(BOOTSTRAP_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new IOException("OpenSearch call did not complete within " + BOOTSTRAP_TIMEOUT_MS + "ms", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException io) {
                throw io;
            }
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new IOException(cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for OpenSearch", e);
        }
    }

    /**
     * The mapping resource files (search/segment-v1-index.json, .../
     * document-v1-index.json) hold one JSON document with top-level {@code
     * settings}/{@code mappings} keys, mirroring the tech spec's {@code PUT
     * /<index>} body verbatim - this reassembles them plus the alias into
     * that exact body, as a raw string.
     *
     * <p>Deliberately not the typed {@code CreateIndexRequest.Builder}
     * (which round-trips this same JSON through {@code IndexSettings
     * .Builder.withJson}/{@code TypeMapping.Builder.withJson} client-side):
     * {@code segment-v1-index.json}'s {@code vi_stop} filter uses {@code
     * stopwords_path} (a file, not an inline list) alone - valid, and
     * honored correctly by OpenSearch itself (verified directly against a
     * real cluster: the filter removes stopwords correctly) - but
     * opensearch-java 2.17.0's {@code StopTokenFilter} deserializer treats
     * {@code stopwords} as unconditionally required and throws {@code
     * MissingRequiredPropertyException} on this otherwise-valid mapping.
     * {@code client.generic()} sends the exact same JSON as a raw request,
     * skipping that client-side round-trip and its false-positive rejection
     * entirely - simpler than reshaping the mapping to appease it.
     */
    private String buildCreateIndexBody(String resourcePath, String alias) throws IOException {
        JsonNode root;
        try (InputStream in = new ClassPathResource(resourcePath).getInputStream()) {
            root = new ObjectMapper().readTree(in);
        }
        JsonNode settings = root.get("settings");
        JsonNode mappings = root.get("mappings");
        if (settings == null || mappings == null) {
            throw new IOException("Missing 'settings' or 'mappings' in " + resourcePath);
        }
        return "{\"settings\":" + settings + ",\"mappings\":" + mappings + ",\"aliases\":{\"" + alias + "\":{}}}";
    }
}
