package com.aismartstore.backend.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aismartstore.backend.config.EmbeddingProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * SPEC-search-semantic T5: contract with the embedding REST endpoint (a real in-process HTTP server
 * stands in for the endpoint - no WireMock dependency in this project).
 */
class EmbeddingClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int DIM = 1024;

    private HttpServer server;
    private final List<JsonNode> requests = new CopyOnWriteArrayList<>();
    private volatile Function<JsonNode, String> responder;
    private volatile int status = 200;
    private volatile long delayMs = 0;

    private StringRedisTemplate redis;
    private ValueOperations<String, String> ops;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/embed", exchange -> {
            JsonNode body = MAPPER.readTree(exchange.getRequestBody());
            requests.add(body);
            try {
                if (delayMs > 0) {
                    Thread.sleep(delayMs);
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            byte[] bytes = responder.apply(body).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        responder = body -> okResponse(body.get("inputs").size(), DIM);

        redis = mock(StringRedisTemplate.class);
        ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private static String okResponse(int count, int dim) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"dense\":[");
            for (int j = 0; j < dim; j++) {
                if (j > 0) {
                    sb.append(',');
                }
                sb.append(i + 1).append(".5");
            }
            sb.append("]}");
        }
        return sb.append(']').toString();
    }

    private EmbeddingClient client(boolean instruction, int batch, int timeoutMs) {
        EmbeddingProperties props = new EmbeddingProperties(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/embed", batch, timeoutMs, 5000, "Qwen/Qwen3-Embedding-0.6B",
                "ver-1", DIM, 512, 64, instruction, "Given a Vietnamese question, retrieve relevant passages");
        return new EmbeddingClient(props, redis, MAPPER);
    }

    @Test
    void embedPostsInputsInOrderWithDenseModeAndReturnsVectorsInOrder() {
        List<float[]> vectors = client(true, 32, 2000).embed(List.of("mot", "hai", "ba"));

        assertThat(requests).hasSize(1);
        assertThat(requests.get(0).get("mode").asText()).isEqualTo("dense");
        assertThat(requests.get(0).get("inputs")).extracting(JsonNode::asText).containsExactly("mot", "hai", "ba");
        assertThat(vectors).hasSize(3);
        assertThat(vectors.get(0)).hasSize(DIM);
        // constant-valued fake vectors are L2-normalised: every component = 1/sqrt(DIM)
        assertThat(vectors.get(0)[0]).isEqualTo((float) (1.0 / Math.sqrt(DIM)));
        assertThat(vectors.get(2)[0]).isEqualTo((float) (1.0 / Math.sqrt(DIM)));
    }

    @Test
    void vectorsAreL2NormalisedWhenNormDrifts() {
        float[] n = EmbeddingClient.normalizeL2(new float[] {3f, 4f});
        assertThat(n[0]).isEqualTo(0.6f);
        assertThat(n[1]).isEqualTo(0.8f);
    }

    @Test
    void alreadyUnitVectorIsReturnedUntouched() {
        float[] unit = {1f, 0f};
        assertThat(EmbeddingClient.normalizeL2(unit)).isSameAs(unit);
    }

    @Test
    void zeroNormVectorIsRejected() {
        assertThatThrownBy(() -> EmbeddingClient.normalizeL2(new float[] {0f, 0f}))
                .isInstanceOf(EmbeddingException.class);
    }

    @Test
    void queryVectorFromEndpointIsNormalised() {
        float[] q = client(true, 32, 2000).embedQuery("cau hoi");
        double sum = 0;
        for (float x : q) {
            sum += (double) x * x;
        }
        assertThat(Math.sqrt(sum)).isBetween(0.999, 1.001);
    }

    @Test
    void embedSplitsIntoBatchesAndPreservesOrder() {
        List<String> inputs = new ArrayList<>();
        for (int i = 0; i < 70; i++) {
            inputs.add("t" + i);
        }
        List<float[]> vectors = client(true, 32, 2000).embed(inputs);

        assertThat(requests).extracting(r -> r.get("inputs").size()).containsExactly(32, 32, 6);
        assertThat(vectors).hasSize(70);
    }

    @Test
    void documentTextIsNeverWrappedWithTheInstruction() {
        client(true, 32, 2000).embed(List.of("noi dung tai lieu"));
        assertThat(requests.get(0).get("inputs").get(0).asText()).isEqualTo("noi dung tai lieu");
    }

    @Test
    void aWrongNumberOfVectorsIsRejected() {
        responder = body -> okResponse(body.get("inputs").size() - 1, DIM);
        assertThatThrownBy(() -> client(true, 32, 2000).embed(List.of("a", "b")))
                .isInstanceOf(EmbeddingException.class)
                .hasMessageContaining("1 vectors for 2 inputs");
    }

    @Test
    void vectorsOf1023Or1025DimensionsAreRejected() {
        for (int dim : new int[] {1023, 1025}) {
            responder = body -> okResponse(body.get("inputs").size(), dim);
            assertThatThrownBy(() -> client(true, 32, 2000).embed(List.of("a")))
                    .as("dim %d", dim)
                    .isInstanceOf(EmbeddingException.class)
                    .hasMessageContaining("1024");
        }
    }

    @Test
    void malformedAndNonArrayBodiesAreRejected() {
        responder = body -> "not json";
        assertThatThrownBy(() -> client(true, 32, 2000).embed(List.of("a"))).isInstanceOf(EmbeddingException.class);
        responder = body -> "{\"dense\":[]}";
        assertThatThrownBy(() -> client(true, 32, 2000).embed(List.of("a"))).isInstanceOf(EmbeddingException.class);
    }

    @Test
    void http5xxIsAnEmbeddingException() {
        status = 503;
        responder = body -> "{}";
        assertThatThrownBy(() -> client(true, 32, 2000).embed(List.of("a")))
                .isInstanceOf(EmbeddingException.class)
                .hasMessageContaining("503");
    }

    @Test
    void aSlowEndpointTimesOut() {
        delayMs = 600;
        assertThatThrownBy(() -> client(true, 32, 100).embedQuery("cau hoi"))
                .isInstanceOf(EmbeddingException.class);
    }

    @Test
    void anUnconfiguredUrlFailsFast() {
        EmbeddingProperties props = new EmbeddingProperties("", 32, 500, 5000, "m", "v", DIM, 512, 64, true, "i");
        assertThatThrownBy(() -> new EmbeddingClient(props, redis, MAPPER).embed(List.of("a")))
                .isInstanceOf(EmbeddingException.class);
    }

    @Test
    void embedQueryWrapsTheQuestionWithTheInstructionWhenEnabled() {
        float[] v = client(true, 32, 2000).embedQuery("Hạn nộp báo cáo là khi nào?");

        assertThat(v).hasSize(DIM);
        assertThat(requests.get(0).get("inputs").get(0).asText())
                .isEqualTo("Instruct: Given a Vietnamese question, retrieve relevant passages\nQuery: Hạn nộp báo cáo là khi nào?");
    }

    @Test
    void embedQueryDoesNotWrapWhenDisabled() {
        client(false, 32, 2000).embedQuery("cau hoi");
        assertThat(requests.get(0).get("inputs").get(0).asText()).isEqualTo("cau hoi");
    }

    @Test
    void embedQueryCachesByHashAndModelVersionWithoutAnyUserComponent() {
        EmbeddingClient client = client(true, 32, 2000);
        client.embedQuery("cau hoi");

        org.mockito.ArgumentCaptor<String> key = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(ops).set(key.capture(), anyString(), eq(Duration.ofMinutes(10)));
        assertThat(key.getValue()).startsWith("semantic:qvec:").endsWith(":ver-1");
    }

    @Test
    void aCacheHitSkipsTheEndpoint() {
        StringBuilder cached = new StringBuilder();
        for (int i = 0; i < DIM; i++) {
            cached.append(i == 0 ? "" : ",").append("0.25");
        }
        when(ops.get(anyString())).thenReturn(cached.toString());

        float[] v = client(true, 32, 2000).embedQuery("cau hoi");

        assertThat(v[10]).isEqualTo(0.25f);
        assertThat(requests).isEmpty();
        verify(ops, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void redisFailuresAreJustCacheMisses() {
        when(ops.get(anyString())).thenThrow(new RuntimeException("redis down"));
        org.mockito.Mockito.doThrow(new RuntimeException("redis down")).when(ops).set(anyString(), anyString(), any(Duration.class));

        assertThat(client(true, 32, 2000).embedQuery("cau hoi")).hasSize(DIM);
    }

    @Test
    void aCorruptCacheEntryIsIgnored() {
        when(ops.get(anyString())).thenReturn("1,2,3");
        assertThat(client(true, 32, 2000).embedQuery("cau hoi")).hasSize(DIM);
        assertThat(requests).hasSize(1);
    }
}
