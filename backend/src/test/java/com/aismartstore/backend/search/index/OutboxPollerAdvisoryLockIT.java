package com.aismartstore.backend.search.index;

import static org.assertj.core.api.Assertions.assertThat;

import com.aismartstore.backend.scanning.ClamdService;
import com.aismartstore.backend.storage.ObjectStorageService;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Optional;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/** backend-multi-instance Task 12 (AC-16): only one holder of the poller advisory lock; lock dies with its connection. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {"app.scheduling.enabled=false"})
@Testcontainers
class OutboxPollerAdvisoryLockIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
            .withDatabaseName("aismartstore")
            .withUsername("aismartstore")
            .withPassword("aismartstore");

    @Container
    static GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @DynamicPropertySource
    static void containerProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add(
                "spring.security.oauth2.resourceserver.jwt.jwk-set-uri", () -> "http://127.0.0.1:1/unused-jwks");
    }

    @MockBean
    private ObjectStorageService objectStorageService;

    @MockBean
    private ClamdService clamdService;

    @Autowired
    private SearchIndexPollerLock lock;

    @Autowired
    private DataSource dataSource;

    @Test
    void secondInstanceCannotEnterWhileTheFirstHoldsTheLockAndGetsItAfterRelease() {
        Optional<SearchIndexPollerLock.Handle> first = lock.tryAcquire();
        assertThat(first).isPresent();

        assertThat(lock.tryAcquire()).isEmpty();

        first.get().close();
        Optional<SearchIndexPollerLock.Handle> again = lock.tryAcquire();
        assertThat(again).isPresent();
        again.get().close();
    }

    @Test
    void killingTheHoldersConnectionReleasesTheLock() throws Exception {
        Optional<SearchIndexPollerLock.Handle> holder = lock.tryAcquire();
        assertThat(holder).isPresent();

        try (Connection admin = dataSource.getConnection(); Statement st = admin.createStatement()) {
            ResultSet pid = st.executeQuery(
                    "SELECT pid FROM pg_locks WHERE locktype = 'advisory' AND objid = "
                            + SearchIndexPollerLock.KEY + " AND granted");
            assertThat(pid.next()).isTrue();
            int holderPid = pid.getInt(1);
            st.execute("SELECT pg_terminate_backend(" + holderPid + ")");
        }

        // Postgres frees the session lock when the backend dies; give it a moment to tear down.
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(10)).untilAsserted(() -> {
            Optional<SearchIndexPollerLock.Handle> next = lock.tryAcquire();
            assertThat(next).isPresent();
            next.get().close();
        });
        holder.get().close(); // unlock fails on the dead connection; must not throw
    }
}
