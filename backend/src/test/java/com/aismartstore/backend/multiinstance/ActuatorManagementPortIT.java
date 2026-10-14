package com.aismartstore.backend.multiinstance;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;

/** backend-multi-instance Task 16 (AC-24): health probes on the management port only; nothing else exposed. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"management.server.port=0", "app.scheduling.enabled=false"})
class ActuatorManagementPortIT extends MultiInstanceITBase {

    @LocalServerPort
    private int serverPort;

    @LocalManagementPort
    private int managementPort;

    private final HttpClient http = HttpClient.newHttpClient();

    private HttpResponse<String> get(int port, String path) throws Exception {
        return http.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void readinessAndLivenessAreUpOnTheManagementPort() throws Exception {
        assertThat(managementPort).isNotEqualTo(serverPort);
        HttpResponse<String> readiness = get(managementPort, "/actuator/health/readiness");
        assertThat(readiness.statusCode()).isEqualTo(200);
        assertThat(readiness.body()).contains("\"status\":\"UP\"");
        assertThat(get(managementPort, "/actuator/health/liveness").statusCode()).isEqualTo(200);
    }

    @Test
    void onlyTheHealthEndpointIsExposed() throws Exception {
        // Not exposed -> never 200 (anonymous gets 401 from the app's security chain, authenticated would get 404).
        assertThat(get(managementPort, "/actuator/env").statusCode()).isIn(401, 404);
        assertThat(get(managementPort, "/actuator/metrics").statusCode()).isIn(401, 404);
    }

    @Test
    void actuatorIsNotServedOnTheApplicationPort() throws Exception {
        int status = get(serverPort, "/actuator/health/readiness").statusCode();
        assertThat(status).isIn(401, 404); // never 200: unauthenticated -> 401, or not mapped -> 404
        assertThat(status).isNotEqualTo(200);
    }
}
