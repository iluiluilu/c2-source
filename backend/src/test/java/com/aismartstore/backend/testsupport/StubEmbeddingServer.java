package com.aismartstore.backend.testsupport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test double for the internal embedding REST endpoint (search-semantic): deterministic unit vectors, so
 * k-NN geometry is fully controlled by the test. A text containing one of the {@link #AXES} keywords maps to
 * that basis vector {@code e_i}; anything else maps to {@code e_1000}. {@code Mode.FAIL} answers HTTP 500,
 * {@code Mode.WRONG_DIM} answers 1023-dimensional vectors.
 */
public final class StubEmbeddingServer implements AutoCloseable {

    public static final int DIM = 1024;
    public static final List<String> AXES = List.of("alpha", "beta", "gamma", "delta");

    public enum Mode { OK, FAIL, WRONG_DIM }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpServer server;
    private final AtomicInteger requestCount = new AtomicInteger();
    private final AtomicInteger inputCount = new AtomicInteger();
    private final List<String> lastInputs = new CopyOnWriteArrayList<>();
    private volatile Mode mode = Mode.OK;

    public StubEmbeddingServer() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.createContext("/embed", exchange -> {
            JsonNode body = MAPPER.readTree(exchange.getRequestBody());
            requestCount.incrementAndGet();
            StringBuilder out = new StringBuilder("[");
            int n = 0;
            for (JsonNode input : body.get("inputs")) {
                inputCount.incrementAndGet();
                lastInputs.add(input.asText());
                if (n++ > 0) {
                    out.append(',');
                }
                out.append("{\"dense\":").append(denseJson(vectorFor(input.asText(), mode == Mode.WRONG_DIM ? DIM - 1 : DIM))).append('}');
            }
            out.append(']');
            byte[] bytes = out.toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(mode == Mode.FAIL ? 500 : 200, mode == Mode.FAIL ? -1 : bytes.length);
            if (mode != Mode.FAIL) {
                exchange.getResponseBody().write(bytes);
            }
            exchange.close();
        });
        server.start();
    }

    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/embed";
    }

    public void setMode(Mode mode) {
        this.mode = mode;
    }

    public int requestCount() {
        return requestCount.get();
    }

    public int inputCount() {
        return inputCount.get();
    }

    public List<String> lastInputs() {
        return lastInputs;
    }

    public void reset() {
        mode = Mode.OK;
        requestCount.set(0);
        inputCount.set(0);
        lastInputs.clear();
    }

    /** The basis vector this server returns for {@code text} (also used by tests to seed matching index vectors). */
    public static float[] vectorFor(String text, int dim) {
        float[] v = new float[dim];
        String lower = text.toLowerCase();
        for (int i = 0; i < AXES.size(); i++) {
            if (lower.contains(AXES.get(i))) {
                v[i] = 1f;
                return v;
            }
        }
        v[1000 % dim] = 1f;
        return v;
    }

    /** {@code a*e_i + b*e_j} normalised - a vector at a known cosine from {@code e_i}. */
    public static float[] mix(int i, double a, int j, double b) {
        float[] v = new float[DIM];
        double norm = Math.sqrt(a * a + b * b);
        v[i] = (float) (a / norm);
        v[j] = (float) (b / norm);
        return v;
    }

    private static String denseJson(float[] v) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < v.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(v[i]);
        }
        return sb.append(']').toString();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
