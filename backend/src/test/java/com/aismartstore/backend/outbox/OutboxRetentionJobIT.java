package com.aismartstore.backend.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import com.aismartstore.backend.storage.ObjectStorageService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * End-to-end coverage of classification-notes-01-tags.md §5 ("Integration
 * test - Outbox: ... retention job xoá đúng row processed_at quá hạn, không
 * xoá row chưa xử lý, log cảnh báo backlog"), Task 9b.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = "app.scheduling.enabled=false")
@AutoConfigureMockMvc
@Testcontainers
class OutboxRetentionJobIT {

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
                "spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
                () -> "http://127.0.0.1:1/unused-jwks");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private OutboxRetentionJob outboxRetentionJob;

    // Not under test here (this class is about outbox retention) - real S3
    // credentials aren't configured in this environment and AuditRetentionJob
    // (an unrelated scheduled bean in the same context) needs an
    // ObjectStorageService bean to construct, same as IngestControllerIT.
    @MockBean
    private ObjectStorageService objectStorageService;

    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.jdbc.core.JdbcTemplate shedlockJdbc;

    @BeforeEach
    void resetState() throws Exception {
        // backend-multi-instance: the context's own scheduler already ticked this job at startup and holds its
        // ShedLock row for lockAtLeastFor; expire it (UPDATE, not DELETE: ShedLock caches that the row exists) so the direct call below runs the job body.
        shedlockJdbc.update("UPDATE shedlock SET lock_until = timezone('utc', now()) - interval '1 minute'");
        outboxEventRepository.deleteAll();
        setConfig("classification.outbox_retention_days", 30);
    }

    private static JwtRequestPostProcessor admin() {
        return jwt().jwt(builder -> builder.subject("admin-1")
                        .claim("preferred_username", "admin-1")
                        .claim("realm_access", Map.of("roles", List.of("quan-tri"))))
                .authorities(com.aismartstore.backend.testsupport.RolePermissionFixtures.authoritiesFor("QUAN_TRI"));
    }

    private void setConfig(String key, Object value) throws Exception {
        mockMvc.perform(patch("/admin/system-config/" + key)
                        .with(admin())
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("value", value))))
                .andExpect(status().isOk());
    }

    private OutboxEvent saveEvent(OffsetDateTime createdAt, OffsetDateTime processedAt) {
        OutboxEvent event = new OutboxEvent();
        event.setEventType("document.classification.changed");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("documentId", java.util.UUID.randomUUID().toString());
        event.setPayload(payload);
        event.setCreatedAt(createdAt);
        event.setProcessedAt(processedAt);
        return outboxEventRepository.save(event);
    }

    @Test
    void deletesOnlyProcessedRowsPastTheRetentionWindow() throws Exception {
        OffsetDateTime now = OffsetDateTime.now();
        OutboxEvent processedOld = saveEvent(now.minusDays(40), now.minusDays(35));
        OutboxEvent processedRecent = saveEvent(now.minusDays(5), now.minusDays(2));
        OutboxEvent unprocessedOld = saveEvent(now.minusDays(40), null);

        outboxRetentionJob.run();

        assertThat(outboxEventRepository.findById(processedOld.getId())).isEmpty();
        assertThat(outboxEventRepository.findById(processedRecent.getId())).isPresent();
        assertThat(outboxEventRepository.findById(unprocessedOld.getId())).isPresent();
    }

    @Test
    void logsABacklogWarningWhenTheOldestUnprocessedRowIsOlderThan24Hours() {
        OffsetDateTime now = OffsetDateTime.now();
        saveEvent(now.minusHours(30), null);

        var logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(OutboxRetentionJob.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            outboxRetentionJob.run();
        } finally {
            logger.detachAppender(appender);
        }

        boolean warned = appender.list.stream()
                .anyMatch(e -> e.getLevel() == ch.qos.logback.classic.Level.WARN
                        && e.getFormattedMessage().contains("outbox_event backlog"));
        assertThat(warned).isTrue();
    }

    @Test
    void doesNotWarnWhenTheOldestUnprocessedRowIsWithinTheBacklogThreshold() {
        OffsetDateTime now = OffsetDateTime.now();
        saveEvent(now.minusHours(1), null);

        var logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(OutboxRetentionJob.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            outboxRetentionJob.run();
        } finally {
            logger.detachAppender(appender);
        }

        boolean warned = appender.list.stream()
                .anyMatch(e -> e.getLevel() == ch.qos.logback.classic.Level.WARN
                        && e.getFormattedMessage().contains("outbox_event backlog"));
        assertThat(warned).isFalse();
    }
}
