package com.aismartstore.backend.accounts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aismartstore.backend.audit.AuditLogRepository;
import com.aismartstore.backend.storage.ObjectStorageService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * End-to-end coverage of account-lifecycle.md §5 ("Integration test"): real
 * Postgres (Flyway-migrated through V8) + real Redis behind MockMvc, so the
 * DB-is-source-of-truth / Redis-cache {@link AccountStatusFilter} path is
 * exercised together with the real Spring Security filter chain, not just
 * mocked at the unit level (see {@link AccountsServiceTest} and
 * {@link AccountStatusFilterTest} for that coverage).
 *
 * <p>{@link KeycloakAdminService} is mocked out (like {@link SystemConfigControllerIT}
 * mocks {@link ObjectStorageService}) rather than run against a real
 * Keycloak container - there is no Keycloak Testcontainers module in this
 * project yet and the Keycloak Admin API surface itself is already covered
 * by {@link KeycloakAdminServiceTest}. What this test class exists to prove
 * is the part that only a real Postgres + Redis + filter chain can show:
 * that a lock/disable takes effect on the very next request, even for an
 * access token that was already valid.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Testcontainers
class AccountsControllerIT {

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

    @MockBean
    private ObjectStorageService objectStorageService;

    @MockBean
    private KeycloakAdminService kc;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private StringRedisTemplate redisTemplate;

    private static final String ADMIN_SUB = "admin-actor";

    @BeforeEach
    void resetState() {
        accountRepository.deleteAll();
        try (var connection = redisTemplate.getConnectionFactory().getConnection()) {
            connection.serverCommands().flushAll();
        }
    }

    private static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                    .JwtRequestPostProcessor
            asUser(String sub, String realmRole, String springRole) {
        return jwt().jwt(builder -> builder.subject(sub)
                        .claim("preferred_username", sub)
                        .claim("realm_access", Map.of("roles", List.of(realmRole))))
                .authorities(com.aismartstore.backend.testsupport.RolePermissionFixtures.authoritiesFor(springRole));
    }

    private static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                    .JwtRequestPostProcessor
            admin() {
        return asUser(ADMIN_SUB, "quan-tri", "QUAN_TRI");
    }

    private Account seedAccount(String id, String status, String reason, long quotaBytes) {
        Account row = new Account();
        row.setKeycloakUserId(id);
        row.setStatus(status);
        row.setStatusReason(reason);
        row.setStorageQuotaBytes(quotaBytes);
        row.setCreatedAt(OffsetDateTime.now());
        row.setUpdatedAt(OffsetDateTime.now());
        return accountRepository.save(row);
    }

    private static Map<String, Object> kcUserRep(String id, boolean enabled) {
        return Map.of("id", id, "username", "user-" + id, "enabled", enabled);
    }

    // ---- Story 3 AC-3: lớp 2 filter enforcement ------------------------------

    @Test
    void lockingAnAccountBlocksItsNextRequestImmediately() throws Exception {
        String targetSub = UUID.randomUUID().toString();
        seedAccount(targetSub, "active", null, 1_000L);
        when(kc.getUser(targetSub)).thenReturn(kcUserRep(targetSub, true));

        // Before the lock: the filter lets the request through (validation
        // then fails on the empty body, proving it got past the filter).
        mockMvc.perform(put("/accounts/me/password")
                        .with(asUser(targetSub, "can-bo", "CAN_BO"))
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(patch("/accounts/" + targetSub + "/status")
                        .with(admin())
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("status", "locked", "reason", "nghỉ việc"))))
                .andExpect(status().isNoContent());

        assertThat(accountRepository.findById(targetSub).orElseThrow().getStatus()).isEqualTo("locked");

        // Same subject, "still valid" token (MockMvc's jwt() never expires it)
        // -> immediately rejected by AccountStatusFilter, no wait for any TTL.
        mockMvc.perform(put("/accounts/me/password")
                        .with(asUser(targetSub, "can-bo", "CAN_BO"))
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("ACCOUNT_LOCKED"));
    }

    // ---- Story 3/4: status transitions --------------------------------------

    @Test
    void patchStatusWithoutReasonIsRejected() throws Exception {
        String targetSub = UUID.randomUUID().toString();
        seedAccount(targetSub, "active", null, 1_000L);

        mockMvc.perform(patch("/accounts/" + targetSub + "/status")
                        .with(admin())
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("status", "locked"))))
                .andExpect(status().isBadRequest());

        assertThat(accountRepository.findById(targetSub).orElseThrow().getStatus()).isEqualTo("active");
    }

    @Test
    void patchStatusToSameValueIsConflict() throws Exception {
        String targetSub = UUID.randomUUID().toString();
        seedAccount(targetSub, "locked", "đã khóa từ trước", 1_000L);

        mockMvc.perform(patch("/accounts/" + targetSub + "/status")
                        .with(admin())
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("status", "locked", "reason", "x"))))
                .andExpect(status().isConflict());
    }

    @Test
    void patchStatusUnlockSucceedsAndSyncsKeycloak() throws Exception {
        String targetSub = UUID.randomUUID().toString();
        seedAccount(targetSub, "locked", "tạm khóa", 1_000L);
        when(kc.getUser(targetSub)).thenReturn(kcUserRep(targetSub, false));

        mockMvc.perform(patch("/accounts/" + targetSub + "/status")
                        .with(admin())
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("status", "active", "reason", "hết thời hạn khóa"))))
                .andExpect(status().isNoContent());

        Account updated = accountRepository.findById(targetSub).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo("active");
        assertThat(updated.getStatusReason()).isNull();

        // AuditService.recordAsync (@EnableAsync) writes off the request
        // thread - poll briefly rather than assert immediately.
        List<com.aismartstore.backend.audit.AuditLog> rows = awaitAuditRows("account", targetSub, 1);
        assertThat(rows.get(0).getAction()).isEqualTo("account.unlock");
    }

    /** Polls up to ~2s for {@code @Async} audit writes to land (see AuditService.recordAsync). */
    private List<com.aismartstore.backend.audit.AuditLog> awaitAuditRows(
            String resourceType, String resourceId, int minCount) throws InterruptedException {
        for (int i = 0; i < 40; i++) {
            List<com.aismartstore.backend.audit.AuditLog> rows =
                    auditLogRepository.findByResourceTypeAndResourceIdOrderByIdDesc(resourceType, resourceId);
            if (rows.size() >= minCount) {
                return rows;
            }
            Thread.sleep(50);
        }
        return auditLogRepository.findByResourceTypeAndResourceIdOrderByIdDesc(resourceType, resourceId);
    }

    // ---- Quyết định 4: last active admin guard ------------------------------

    @Test
    void lockingTheLastActiveAdminIsBlocked() throws Exception {
        String soleAdminSub = UUID.randomUUID().toString();
        seedAccount(soleAdminSub, "active", null, 1_000L);
        when(kc.listRoleUsers(org.mockito.ArgumentMatchers.eq("quan-tri"), anyInt()))
                .thenReturn(List.of(Map.of("id", soleAdminSub)));

        mockMvc.perform(patch("/accounts/" + soleAdminSub + "/status")
                        .with(admin())
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("status", "locked", "reason", "x"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LAST_ADMIN"));

        assertThat(accountRepository.findById(soleAdminSub).orElseThrow().getStatus()).isEqualTo("active");
    }

    // ---- Story 5: quota ------------------------------------------------------

    @Test
    void patchQuotaRejectsNegativeAndAppliesValidValue() throws Exception {
        String targetSub = UUID.randomUUID().toString();
        seedAccount(targetSub, "active", null, 1_000L);

        mockMvc.perform(patch("/accounts/" + targetSub + "/quota")
                        .with(admin())
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("storageQuotaBytes", -1))))
                .andExpect(status().isBadRequest());

        mockMvc.perform(patch("/accounts/" + targetSub + "/quota")
                        .with(admin())
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("storageQuotaBytes", 12345))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.storageQuotaBytes").value(12345));

        assertThat(accountRepository.findById(targetSub).orElseThrow().getStorageQuotaBytes()).isEqualTo(12345L);
    }

    @Test
    void patchQuotaForbiddenForNonAdmin() throws Exception {
        String targetSub = UUID.randomUUID().toString();
        seedAccount(targetSub, "active", null, 1_000L);

        mockMvc.perform(patch("/accounts/" + targetSub + "/quota")
                        .with(asUser("officer", "can-bo", "CAN_BO"))
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("storageQuotaBytes", 12345))))
                .andExpect(status().isForbidden());
    }

    // ---- no hard-delete endpoint ----------------------------------------------

    @Test
    void noDeleteRouteExistsForAccounts() throws Exception {
        mockMvc.perform(delete("/accounts/" + UUID.randomUUID()).with(admin()))
                .andExpect(status().isMethodNotAllowed());
    }
}
