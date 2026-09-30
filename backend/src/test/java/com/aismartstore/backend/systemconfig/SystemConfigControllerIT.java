package com.aismartstore.backend.systemconfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aismartstore.backend.audit.AuditLogRepository;
import com.aismartstore.backend.storage.ObjectStorageService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * End-to-end coverage of the system-config admin surface (spec §5,
 * "Integration test"): real Postgres (Flyway-migrated) + real Redis behind
 * MockMvc, so the DB-first/Redis-cache/audit write path and R3-only
 * authorization are exercised together, not just mocked at the service layer
 * (see {@link SystemConfigServiceTest} for the mocked unit-level coverage of
 * the same rules).
 *
 * <p>First Testcontainers-based test in this project - no existing IT pattern
 * to reuse (spec §5 called for checking first). {@link ObjectStorageService}
 * is mocked out purely to skip its {@code @PostConstruct} SeaweedFS bucket
 * bootstrap (unrelated to this feature, would otherwise retry for ~30s
 * against an object store that isn't part of this test's containers).
 *
 * <p>This test caught a real, previously-latent bug in the shared
 * {@code AuditService}: {@code AuditLog.metadata} is a JSON-mapped
 * {@code Map<String, Object>}, and Hibernate 6's post-insert dirty check for
 * that mapping snapshots it through a JSON round-trip - which turns a boxed
 * {@code Long} back into an {@code Integer}, so a caller-supplied
 * {@code Long} never {@code .equals()} its own snapshot and Hibernate flushes
 * a spurious identical {@code UPDATE} right after the insert, which the
 * append-only trigger on {@code audit_log} rejects. Fixed in
 * {@code AuditService.record()} by normalizing {@code metadata} through the
 * same JSON round-trip before it reaches Hibernate, so the "live" value
 * already matches its snapshot. See {@code docs/03-dev/specs/system-config.md}
 * §7 for the full repro trail.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Testcontainers
class SystemConfigControllerIT {

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
        // Force the lazy jwk-set-uri decoder instead of eager issuer-uri OIDC
        // discovery, which would otherwise hit a real Keycloak at context
        // startup; SecurityMockMvcRequestPostProcessors.jwt() below never
        // triggers an actual decode, so this URI is never dereferenced.
        registry.add(
                "spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
                () -> "http://127.0.0.1:1/unused-jwks");
    }

    @MockBean
    private ObjectStorageService objectStorageService;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private SystemConfigRepository systemConfigRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    private static final String BASE = "/admin/system-config";
    private static final String NON_SENSITIVE_KEY = SystemConfigDefaults.INGEST_MAX_FILES_PER_BATCH;
    private static final String SENSITIVE_KEY = SystemConfigDefaults.RETENTION_TRASH_RETENTION_DAYS;

    /**
     * {@code system_config} rows are reset before every test (each test then
     * sees every key at its static default). {@code audit_log} is
     * intentionally append-only (NFR-09 hash chain, DB trigger rejects
     * DELETE/UPDATE) and is therefore never wiped; tests that assert on audit
     * history compare against a baseline count captured at the start of the
     * test, or only inspect the newest entries (index 0..N-1), which are
     * always this test's own writes since JUnit runs test methods
     * sequentially against one shared container/context.
     */
    @BeforeEach
    void resetState() {
        systemConfigRepository.deleteAll();
    }

    private long historyCount(String key) {
        return auditLogRepository
                .findByResourceTypeAndResourceIdOrderByIdDesc(SystemConfigService.RESOURCE_TYPE, key)
                .size();
    }

    private static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                    .JwtRequestPostProcessor
            asRole(String username, String realmRole, String springRole) {
        return jwt().jwt(builder -> builder.subject(username)
                        .claim("preferred_username", username)
                        .claim("realm_access", Map.of("roles", List.of(realmRole))))
                .authorities(com.aismartstore.backend.testsupport.RolePermissionFixtures.authoritiesFor(springRole));
    }

    private static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                    .JwtRequestPostProcessor
            admin() {
        return asRole("admin-1", "quan-tri", "QUAN_TRI");
    }

    private static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                    .JwtRequestPostProcessor
            officer() {
        return asRole("officer-1", "can-bo", "CAN_BO");
    }

    // ---- Story 1 AC-3 / security ------------------------------------------

    @Test
    void nonAdminIsForbiddenOnEveryEndpoint() throws Exception {
        mockMvc.perform(get(BASE).with(officer())).andExpect(status().isForbidden());
        mockMvc.perform(patch(BASE + "/" + NON_SENSITIVE_KEY)
                        .with(officer())
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("value", 100))))
                .andExpect(status().isForbidden());
        mockMvc.perform(get(BASE + "/" + NON_SENSITIVE_KEY + "/history").with(officer()))
                .andExpect(status().isForbidden());
    }

    // ---- Story 1 AC-4: default fallback when no row exists -----------------

    @Test
    void catalogueFallsBackToDefaultWhenNoRowExists() throws Exception {
        mockMvc.perform(get(BASE).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.key == '" + NON_SENSITIVE_KEY + "')].usingDefault").value(true))
                .andExpect(jsonPath("$[?(@.key == '" + NON_SENSITIVE_KEY + "')].value").value(500));
    }

    // ---- Story 2: non-sensitive update -------------------------------------

    @Test
    void patchNonSensitiveAppliesImmediatelyAndIsVisibleOnGetAndHistory() throws Exception {
        long baseline = historyCount(NON_SENSITIVE_KEY);

        mockMvc.perform(patch(BASE + "/" + NON_SENSITIVE_KEY)
                        .with(admin())
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("value", 750))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.value").value(750));

        mockMvc.perform(get(BASE).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.key == '" + NON_SENSITIVE_KEY + "')].usingDefault").value(false))
                .andExpect(jsonPath("$[?(@.key == '" + NON_SENSITIVE_KEY + "')].value").value(750));

        mockMvc.perform(get(BASE + "/" + NON_SENSITIVE_KEY + "/history").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].newValue").value(750))
                .andExpect(jsonPath("$[0].oldValue").value(500));

        List<com.aismartstore.backend.audit.AuditLog> rows = auditLogRepository
                .findByResourceTypeAndResourceIdOrderByIdDesc(SystemConfigService.RESOURCE_TYPE, NON_SENSITIVE_KEY);
        assertThat(rows).hasSize((int) baseline + 1);
        assertThat(rows.get(0).getAction()).isEqualTo(SystemConfigService.ACTION_UPDATE);
    }

    // ---- Story 2 AC-2/AC-3: validation ---------------------------------------

    @Test
    void patchInvalidTypeIsRejectedAndDoesNotWriteOrAudit() throws Exception {
        long baseline = historyCount(NON_SENSITIVE_KEY);

        mockMvc.perform(patch(BASE + "/" + NON_SENSITIVE_KEY)
                        .with(admin())
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("value", "not-a-number"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_VALUE"));

        assertThat(systemConfigRepository.findById(NON_SENSITIVE_KEY)).isEmpty();
        assertThat(historyCount(NON_SENSITIVE_KEY)).isEqualTo(baseline);
    }

    @Test
    void patchConstraintViolationIsRejected() throws Exception {
        String key = SystemConfigDefaults.WAREHOUSE_STORAGE_WARNING_THRESHOLD_PERCENT;
        mockMvc.perform(patch(BASE + "/" + key)
                        .with(admin())
                        .contentType("application/json")
                        // default block threshold is 95; warning >= block is invalid
                        .content(objectMapper.writeValueAsString(Map.of("value", 99))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CONSTRAINT_VIOLATION"));

        assertThat(systemConfigRepository.findById(key)).isEmpty();
    }

    // ---- Story 3: sensitive two-step confirm -------------------------------

    @Test
    void patchSensitiveRequiresConfirmationThenConfirmApplies() throws Exception {
        long baseline = historyCount(SENSITIVE_KEY);
        String body = objectMapper.writeValueAsString(Map.of("value", 90));

        String responseJson = mockMvc.perform(patch(BASE + "/" + SENSITIVE_KEY)
                        .with(admin())
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFIRMATION_REQUIRED"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(systemConfigRepository.findById(SENSITIVE_KEY)).isEmpty();
        assertThat(historyCount(SENSITIVE_KEY)).isEqualTo(baseline);

        @SuppressWarnings("unchecked")
        Map<String, Object> parsed = objectMapper.readValue(responseJson, Map.class);
        String token = (String) parsed.get("confirmationToken");
        assertThat(token).isNotBlank();

        mockMvc.perform(post(BASE + "/" + SENSITIVE_KEY + "/confirm")
                        .with(admin())
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(
                                Map.of("confirmationToken", token, "value", 90))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.value").value(90));

        assertThat(historyCount(SENSITIVE_KEY)).isEqualTo(baseline + 1);

        // token already consumed -> reuse is rejected
        mockMvc.perform(post(BASE + "/" + SENSITIVE_KEY + "/confirm")
                        .with(admin())
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(
                                Map.of("confirmationToken", token, "value", 90))))
                .andExpect(status().isGone());

        assertThat(historyCount(SENSITIVE_KEY)).isEqualTo(baseline + 1);
    }

    @Test
    void nonSensitiveKeyNeverReturnsConfirmationRequired() throws Exception {
        mockMvc.perform(patch(BASE + "/" + NON_SENSITIVE_KEY)
                        .with(admin())
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("value", 600))))
                .andExpect(status().isOk());
    }

    // ---- Story 5: history + rollback ---------------------------------------

    @Test
    void historyOrdersNewestFirstAcrossMultipleEdits() throws Exception {
        for (int value : List.of(600, 700, 800)) {
            mockMvc.perform(patch(BASE + "/" + NON_SENSITIVE_KEY)
                            .with(admin())
                            .contentType("application/json")
                            .content(objectMapper.writeValueAsString(Map.of("value", value))))
                    .andExpect(status().isOk());
        }

        mockMvc.perform(get(BASE + "/" + NON_SENSITIVE_KEY + "/history").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(greaterThanOrEqualTo(3))))
                .andExpect(jsonPath("$[0].newValue").value(800))
                .andExpect(jsonPath("$[1].newValue").value(700))
                .andExpect(jsonPath("$[2].newValue").value(600));
    }

    @Test
    void rollbackNonSensitiveAppendsRollbackAuditEntryWithoutErasingHistory() throws Exception {
        long baseline = historyCount(NON_SENSITIVE_KEY);

        mockMvc.perform(patch(BASE + "/" + NON_SENSITIVE_KEY)
                        .with(admin())
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("value", 900))))
                .andExpect(status().isOk());

        mockMvc.perform(post(BASE + "/" + NON_SENSITIVE_KEY + "/rollback")
                        .with(admin())
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("toValue", 500))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.value").value(500));

        List<com.aismartstore.backend.audit.AuditLog> rows = auditLogRepository
                .findByResourceTypeAndResourceIdOrderByIdDesc(SystemConfigService.RESOURCE_TYPE, NON_SENSITIVE_KEY);
        assertThat(rows).hasSize((int) baseline + 2);
        assertThat(rows.get(0).getAction()).isEqualTo(SystemConfigService.ACTION_ROLLBACK);
        assertThat(rows.get(1).getAction()).isEqualTo(SystemConfigService.ACTION_UPDATE);
    }

    @Test
    void rollbackSensitiveWithoutTokenRequiresConfirmationLikePatch() throws Exception {
        mockMvc.perform(post(BASE + "/" + SENSITIVE_KEY + "/rollback")
                        .with(admin())
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("toValue", 200))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFIRMATION_REQUIRED"));

        assertThat(systemConfigRepository.findById(SENSITIVE_KEY)).isEmpty();
    }
}
