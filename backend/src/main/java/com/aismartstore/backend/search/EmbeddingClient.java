package com.aismartstore.backend.search;

import com.aismartstore.backend.config.EmbeddingProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * SPEC-search-semantic T5 (search-semantic-indexing.md §4.3, search-semantic-query.md §4.1): client of
 * the internal embedding REST endpoint ({@code POST {"inputs":[...],"mode":"dense"}} ->
 * {@code [{"dense":[1024 floats]}, ...]}, no authentication). Hand-rolled on {@link HttpClient}
 * like {@code KeycloakAdminService} (no new dependency).
 *
 * <p>Every response is validated before being handed back: element count must equal the input
 * count and every vector must have exactly {@code dimension} components - otherwise {@link
 * EmbeddingException}. Callers must never write a vector that did not pass this.
 *
 * <p>Text is never logged. Only the user's question (never document text) is wrapped with the
 * Qwen3 instruction (Q-8) and cached in Redis (key = hash(instruction+query)+model_version, no user
 * component, TTL 10 minutes; any Redis failure is just a cache miss).
 */
@Component
public class EmbeddingClient {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingClient.class);

    static final String QUERY_CACHE_PREFIX = "semantic:qvec:";
    static final Duration QUERY_CACHE_TTL = Duration.ofMinutes(10);

    private final EmbeddingProperties properties;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    public EmbeddingClient(EmbeddingProperties properties, StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.properties = properties;
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    /** Document-side embedding (background consumer): no instruction, batched, {@code batch-timeout-ms}. */
    public List<float[]> embed(List<String> inputs) {
        if (inputs.isEmpty()) {
            return List.of();
        }
        List<float[]> all = new ArrayList<>(inputs.size());
        int batch = Math.max(1, properties.batchSize());
        for (int from = 0; from < inputs.size(); from += batch) {
            List<String> chunk = inputs.subList(from, Math.min(from + batch, inputs.size()));
            all.addAll(post(chunk, properties.batchTimeoutMs()));
        }
        return all;
    }

    /** The exact string embedded for a question (instruction wrapper when enabled, Q-8). */
    String queryInput(String query) {
        if (properties.queryInstructionEnabled()) {
            return "Instruct: " + properties.queryInstruction() + "\nQuery: " + query;
        }
        return query;
    }

    /** Question-side embedding (interactive path): instruction wrapper, Redis cache, {@code timeout-ms}. */
    public float[] embedQuery(String query) {
        String input = queryInput(query);
        String key = QUERY_CACHE_PREFIX + sha256(input) + ":" + properties.modelVersion();

        float[] cached = readCache(key);
        if (cached != null) {
            return cached;
        }
        List<float[]> result = post(List.of(input), properties.timeoutMs());
        float[] vector = result.get(0);
        writeCache(key, vector);
        return vector;
    }

    private List<float[]> post(List<String> inputs, int timeoutMs) {
        if (properties.url() == null || properties.url().isBlank()) {
            throw new EmbeddingException("app.embedding.url (EMBEDDING_API_URL) is not configured");
        }
        ObjectNode body = objectMapper.createObjectNode();
        ArrayNode array = body.putArray("inputs");
        inputs.forEach(array::add);
        body.put("mode", "dense");

        HttpResponse<String> response;
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(properties.url()))
                    .timeout(Duration.ofMillis(timeoutMs))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new EmbeddingException("Embedding endpoint call failed: " + e.getClass().getSimpleName(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EmbeddingException("Embedding endpoint call interrupted", e);
        } catch (IllegalArgumentException e) {
            throw new EmbeddingException("Embedding endpoint URL is invalid", e);
        }
        if (response.statusCode() / 100 != 2) {
            throw new EmbeddingException("Embedding endpoint answered HTTP " + response.statusCode());
        }
        return parseAndValidate(response.body(), inputs.size());
    }

    List<float[]> parseAndValidate(String json, int expectedCount) {
        JsonNode root;
        try {
            root = objectMapper.readTree(json);
        } catch (IOException e) {
            throw new EmbeddingException("Embedding endpoint returned malformed JSON", e);
        }
        if (root == null || !root.isArray()) {
            throw new EmbeddingException("Embedding endpoint response is not an array");
        }
        if (root.size() != expectedCount) {
            throw new EmbeddingException(
                    "Embedding endpoint returned " + root.size() + " vectors for " + expectedCount + " inputs");
        }
        List<float[]> vectors = new ArrayList<>(expectedCount);
        for (JsonNode item : root) {
            JsonNode dense = item.get("dense");
            if (dense == null || !dense.isArray() || dense.size() != properties.dimension()) {
                throw new EmbeddingException("Embedding vector must have exactly " + properties.dimension()
                        + " dimensions, got " + (dense == null || !dense.isArray() ? "none" : dense.size()));
            }
            float[] vector = new float[dense.size()];
            for (int i = 0; i < vector.length; i++) {
                JsonNode component = dense.get(i);
                if (!component.isNumber()) {
                    throw new EmbeddingException("Embedding vector contains a non-numeric component");
                }
                vector[i] = (float) component.asDouble();
            }
            vectors.add(normalizeL2(vector));
        }
        return vectors;
    }

    /** Index uses innerproduct, so ip == cosine only for unit vectors: normalise when the norm drifts from 1. */
    static float[] normalizeL2(float[] v) {
        double sum = 0;
        for (float x : v) {
            sum += (double) x * x;
        }
        double norm = Math.sqrt(sum);
        if (!(norm > 1e-12) || Double.isInfinite(norm)) {
            throw new EmbeddingException("Embedding vector has zero or invalid norm");
        }
        if (Math.abs(norm - 1.0) <= 1e-3) {
            return v;
        }
        float[] out = new float[v.length];
        for (int i = 0; i < v.length; i++) {
            out[i] = (float) (v[i] / norm);
        }
        return out;
    }

    private float[] readCache(String key) {
        try {
            String raw = redis.opsForValue().get(key);
            if (raw == null) {
                return null;
            }
            String[] parts = raw.split(",");
            if (parts.length != properties.dimension()) {
                return null;
            }
            float[] vector = new float[parts.length];
            for (int i = 0; i < parts.length; i++) {
                vector[i] = Float.parseFloat(parts[i]);
            }
            return vector;
        } catch (RuntimeException e) {
            log.warn("Redis read failed for the semantic query-vector cache, treating as a miss: {}", e.toString());
            return null;
        }
    }

    private void writeCache(String key, float[] vector) {
        try {
            StringBuilder sb = new StringBuilder(vector.length * 10);
            for (int i = 0; i < vector.length; i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(vector[i]);
            }
            redis.opsForValue().set(key, sb.toString(), QUERY_CACHE_TTL);
        } catch (RuntimeException e) {
            log.warn("Redis write failed for the semantic query-vector cache, ignoring: {}", e.toString());
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
