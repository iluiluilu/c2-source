package com.aismartstore.backend.accounts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aismartstore.backend.config.KeycloakProperties;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit coverage for account-lifecycle.md §5 ("KeycloakAdminServiceTest").
 *
 * <p>{@link KeycloakAdminService} hand-rolls its HTTP calls on
 * {@link java.net.http.HttpClient} (no mocking-friendly seam, deliberately -
 * see its class javadoc), so this stubs the two endpoints it needs
 * ({@code /token} and the admin API) with the JDK's built-in
 * {@link HttpServer} rather than pulling in a new test dependency - same
 * "avoid extra deps" reasoning as the class under test (NFR-11).
 */
class KeycloakAdminServiceTest {

    private HttpServer server;
    private KeycloakAdminService service;

    /** Last path+method the fake admin API saw, for assertions. */
    private final AtomicReference<String> lastAdminRequest = new AtomicReference<>();
    private volatile int adminResponseStatus = 204;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/realms/aismartstore/protocol/openid-connect/token", exchange -> {
            respondJson(
                    exchange, 200, "{\"access_token\":\"test-token\",\"expires_in\":300}");
        });
        server.createContext("/admin/realms/aismartstore/users/", exchange -> {
            lastAdminRequest.set(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
            if (adminResponseStatus == 204) {
                exchange.sendResponseHeaders(204, -1);
                exchange.close();
            } else {
                respondJson(exchange, adminResponseStatus, "{\"error\":\"stubbed failure\"}");
            }
        });
        server.start();

        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        KeycloakProperties properties = new KeycloakProperties(baseUrl, "aismartstore", "backend", "secret");
        service = new KeycloakAdminService(properties);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private static void respondJson(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @Test
    void logoutUserCallsExpectedEndpoint() {
        service.logoutUser("user-123");

        assertThat(lastAdminRequest.get()).isEqualTo("POST /admin/realms/aismartstore/users/user-123/logout");
    }

    @Test
    void logoutUserMapsNotFoundLikeOtherMethods() {
        adminResponseStatus = 404;

        assertThatThrownBy(() -> service.logoutUser("missing-user")).isInstanceOf(KcNotFoundError.class);
    }

    @Test
    void logoutUserMapsServerErrorLikeOtherMethods() {
        adminResponseStatus = 500;

        assertThatThrownBy(() -> service.logoutUser("user-123")).isInstanceOf(KeycloakUnavailableError.class);
    }
}
