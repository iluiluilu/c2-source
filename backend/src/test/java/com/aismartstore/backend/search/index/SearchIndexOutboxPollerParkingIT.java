package com.aismartstore.backend.search.index;

import static org.assertj.core.api.Assertions.assertThat;

import com.aismartstore.backend.outbox.OutboxEvent;
import com.aismartstore.backend.outbox.OutboxEventRepository;
import com.aismartstore.backend.storage.ObjectStorageService;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
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

/**
 * search-fulltext-04-dong-bo-chi-muc.md §4.4.1/§5 - the half of the parking
 * contract that lives in SQL rather than in Java, so it needs a real
 * Postgres: {@code claimNextForIndexing}'s {@code attempts < :maxAttempts}
 * filter, and the head-of-line blocking it exists to prevent.
 *
 * <p>Before this filter, the claim query would hand back the same poisoned
 * row forever ({@code processed_at} is only ever set on success), so every
 * event queued behind it was never claimed again. Observed for real on stg
 * 2026-09-18: one {@code document.acl.changed} row retried 19,200 times
 * while 12 events behind it made zero progress for 17 minutes.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Testcontainers
class SearchIndexOutboxPollerParkingIT {

    private static final int MAX_ATTEMPTS = 20;

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
    private OutboxEventRepository outboxEventRepository;

    @MockBean
    private ObjectStorageService objectStorageService;

    @BeforeEach
    void resetState() {
        outboxEventRepository.deleteAll();
    }

    private OutboxEvent save(String eventType, int attempts) {
        OutboxEvent event = new OutboxEvent();
        event.setEventType(eventType);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("documentId", UUID.randomUUID().toString());
        event.setPayload(payload);
        event.setCreatedAt(OffsetDateTime.now());
        event.setAttempts(attempts);
        return outboxEventRepository.save(event);
    }

    private Optional<OutboxEvent> claim() {
        return outboxEventRepository.claimNextForIndexing(
                SearchIndexOutboxPoller.HANDLED_EVENT_TYPES, MAX_ATTEMPTS);
    }

    @Test
    void aRowAtTheAttemptsThresholdIsNoLongerClaimed() {
        OutboxEvent parked = save("document.ingested", MAX_ATTEMPTS);

        assertThat(claim()).isEmpty();
        // Parked, not deleted: the row and its evidence stay for operations.
        assertThat(outboxEventRepository.findById(parked.getId())).isPresent();
    }

    @Test
    void aRowOneAttemptBelowTheThresholdIsStillClaimed() {
        OutboxEvent almost = save("document.ingested", MAX_ATTEMPTS - 1);

        assertThat(claim()).isPresent().get().extracting(OutboxEvent::getId).isEqualTo(almost.getId());
    }

    /**
     * The regression that matters: a poisoned {@code document.acl.changed}
     * sorts ahead of everything else, so before §4.4.1 it starved the entire
     * queue. Now it steps aside and the healthy rows behind it get through.
     */
    @Test
    void aPoisonedHighPriorityRowNoLongerBlocksTheRowsBehindIt() {
        save("document.acl.changed", MAX_ATTEMPTS); // the stg incident, in miniature
        OutboxEvent behind1 = save("document.ingested", 0);
        OutboxEvent behind2 = save("document.classification.changed", 0);

        // Simulate the poller draining the queue: claim, mark done, claim again.
        OutboxEvent first = claim().orElseThrow();
        first.setProcessedAt(OffsetDateTime.now());
        outboxEventRepository.save(first);

        OutboxEvent second = claim().orElseThrow();
        second.setProcessedAt(OffsetDateTime.now());
        outboxEventRepository.save(second);

        assertThat(List.of(first.getId(), second.getId()))
                .containsExactlyInAnyOrder(behind1.getId(), behind2.getId());
        assertThat(claim()).isEmpty();
    }

    @Test
    void parkedRowsAreCountedAndListedForTheReconciliationAlarm() {
        OutboxEvent p1 = save("document.acl.changed", MAX_ATTEMPTS);
        OutboxEvent p2 = save("document.ingested", MAX_ATTEMPTS + 5);
        save("document.ingested", 0); // healthy, must not be counted

        assertThat(outboxEventRepository.countParkedForIndexing(
                        SearchIndexOutboxPoller.HANDLED_EVENT_TYPES, MAX_ATTEMPTS))
                .isEqualTo(2);
        assertThat(outboxEventRepository.findParkedIdsForIndexing(
                        SearchIndexOutboxPoller.HANDLED_EVENT_TYPES, MAX_ATTEMPTS, 20))
                .containsExactly(p1.getId(), p2.getId());
    }
}
