package com.aismartstore.backend.rbac;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aismartstore.backend.accounts.KeycloakAdminService;
import com.aismartstore.backend.audit.AuditLog;
import com.aismartstore.backend.audit.AuditLogRepository;
import com.aismartstore.backend.storage.ObjectStorageService;
import com.aismartstore.backend.testsupport.RolePermissionFixtures;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
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
 * rbac-permission-editing.md §5 ("Integration test"): the RBAC admin API end
 * to end over a real (Flyway-migrated) Postgres, mirroring {@code
 * AccountsControllerIT}'s Testcontainers setup. Like every other
 * {@code *ControllerIT} in this codebase, roles/permissions on the request's
 * JWT are set directly via {@code jwt().authorities(...)} (see {@link
 * RolePermissionFixtures}) rather than exercised through the real
 * {@code SecurityConfig#realmRoleAuthorities} JWT converter - that mapping is
 * covered directly by {@code SecurityConfigTest}, and cache eviction on
 * write is covered by {@code RbacServiceTest}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Testcontainers
class RbacControllerIT {

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
    private AuditLogRepository auditLogRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void resetRolePermissionToSeed() {
        // V12 seed, so every test starts from the same known-good matrix
        // regardless of what an earlier test in this class wrote.
        jdbcTemplate.update("DELETE FROM role_permission");
        jdbcTemplate.update(
                "INSERT INTO role_permission (role_code, permission_code, updated_at, updated_by_sub) "
                        + "SELECT role_code, permission_code, now(), NULL FROM (VALUES "
                        + seedValues()
                        + ") AS v(role_code, permission_code)");
        when(kc.listRoleUsers(any(), anyInt())).thenReturn(List.of());
    }

    private static String seedValues() {
        return "('CAN_BO','document.read'),('CAN_BO','document.upload'),('CAN_BO','note.write'),"
                + "('CAN_BO','tag.manage'),('CAN_BO','folder.read'),('CAN_BO','account.self_service'),"
                + "('CAN_BO','personal.manage'),('CAN_BO','group.manage'),('CAN_BO','document.share'),"
                + "('LANH_DAO','document.read'),('LANH_DAO','folder.read'),('LANH_DAO','account.self_service'),"
                + "('LANH_DAO','personal.manage'),('LANH_DAO','group.manage'),('LANH_DAO','document.share'),"
                + "('QUAN_TRI','folder.read'),('QUAN_TRI','folder.manage'),('QUAN_TRI','scan_allowlist.manage'),"
                + "('QUAN_TRI','system_config.manage'),('QUAN_TRI','account.read'),('QUAN_TRI','account.manage'),"
                + "('QUAN_TRI','account.self_service'),('QUAN_TRI','audit.read'),('QUAN_TRI','personal.manage')";
    }

    private static JwtRequestPostProcessor asRole(String sub, String realmRole, String springRole) {
        return jwt().jwt(builder -> builder.subject(sub)
                        .claim("preferred_username", sub)
                        .claim("realm_access", Map.of("roles", List.of(realmRole))))
                .authorities(RolePermissionFixtures.authoritiesFor(springRole));
    }

    private static JwtRequestPostProcessor admin() {
        return asRole("admin-1", "quan-tri", "QUAN_TRI");
    }

    private static JwtRequestPostProcessor officer() {
        return asRole("officer-1", "can-bo", "CAN_BO");
    }

    // ---- Story 1 --------------------------------------------------------

    @Test
    void nonAdminIsForbidden() throws Exception {
        mockMvc.perform(get("/admin/rbac/roles").with(officer())).andExpect(status().isForbidden());
    }

    @Test
    void adminSeesTheFullMatrixMatchingSeed() throws Exception {
        // /admin/rbac/roles reads role_permission, which resetRolePermissionToSeed
        // above deliberately pins to the fixed 9-permission "V12 seed" fixture
        // (seedValues()) regardless of what later migrations grant - so 9 here
        // tracks that fixture, not the live migration state.
        //
        // /admin/rbac/permissions reads the permission catalog table itself,
        // which resetRolePermissionToSeed does NOT touch - so 21 is a real
        // snapshot of the migrations applied as of V29 (V12's original 15,
        // +1 each from V22/V23, +2 from V28, +2 from V29 - delete-restore-
        // trash-01-xoa-mem-thung-rac.md: trash.manage/trash.stats.read).
        // Update this number, on purpose, the next time a migration adds a
        // permission.
        mockMvc.perform(get("/admin/rbac/roles").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].code").value("CAN_BO"))
                .andExpect(jsonPath("$[0].permissionCodes.length()").value(9))
                .andExpect(jsonPath("$[2].code").value("QUAN_TRI"))
                .andExpect(jsonPath("$[2].permissionCodes", org.hamcrest.Matchers.hasItem("account.manage")));

        mockMvc.perform(get("/admin/rbac/permissions").with(admin()))
                .andExpect(status().isOk())
                // 21 + report.read/report.export (export-reporting V36)
                .andExpect(jsonPath("$.length()").value(23));
    }

    // ---- Story 2 ----------------------------------------------------------

    @Test
    void updateRejectsUnknownPermissionCode() throws Exception {
        mockMvc.perform(put("/admin/rbac/roles/CAN_BO/permissions")
                        .with(admin())
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(
                                Map.of("permissionCodes", List.of("document.delete_all")))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void updateRejectsUnknownRoleCode() throws Exception {
        mockMvc.perform(put("/admin/rbac/roles/NOT_A_ROLE/permissions")
                        .with(admin())
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("permissionCodes", List.of("document.read")))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void updateRemovesAPermissionAndRecordsAudit() throws Exception {
        mockMvc.perform(put("/admin/rbac/roles/CAN_BO/permissions")
                        .with(admin())
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "permissionCodes",
                                List.of(
                                        "document.read", "document.upload", "note.write", "folder.read",
                                        "account.self_service", "personal.manage", "group.manage",
                                        "document.share")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.permissionCodes", org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.hasItem("tag.manage"))));

        List<String> remaining = jdbcTemplate.queryForList(
                "SELECT permission_code FROM role_permission WHERE role_code = 'CAN_BO'", String.class);
        assertThat(remaining).doesNotContain("tag.manage");

        List<AuditLog> rows = awaitAuditRows("role", "CAN_BO", 1);
        assertThat(rows.get(0).getAction()).isEqualTo("rbac.role_permission_update");
    }

    // ---- Story 3: lockout guard ---------------------------------------------

    @Test
    void updateBlocksRemovingAccountManageFromQuanTri() throws Exception {
        mockMvc.perform(put("/admin/rbac/roles/QUAN_TRI/permissions")
                        .with(admin())
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "permissionCodes",
                                List.of("folder.read", "folder.manage", "system_config.manage")))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RBAC_LOCKOUT"));

        List<String> stillHeld = jdbcTemplate.queryForList(
                "SELECT permission_code FROM role_permission WHERE role_code = 'QUAN_TRI'", String.class);
        assertThat(stillHeld).contains("account.manage", "system_config.manage");
    }

    // ---- Story 2 AC-5: preview-impact -----------------------------------------

    @Test
    void previewImpactReturnsDiffAndAffectedCount() throws Exception {
        when(kc.listRoleUsers(eq("can-bo"), anyInt())).thenReturn(List.of(Map.of("id", "u1"), Map.of("id", "u2")));

        mockMvc.perform(get("/admin/rbac/roles/CAN_BO/preview-impact")
                        .with(admin())
                        .param("permissionCodes", "document.read,note.write"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.affectedAccountCount").value(2))
                .andExpect(jsonPath("$.permissionsRemoved", org.hamcrest.Matchers.hasItem("document.upload")));
    }

    /** Polls up to ~2s for {@code @Async} audit writes to land (see AuditService.recordAsync). */
    private List<AuditLog> awaitAuditRows(String resourceType, String resourceId, int minCount)
            throws InterruptedException {
        for (int i = 0; i < 40; i++) {
            List<AuditLog> rows = auditLogRepository.findByResourceTypeAndResourceIdOrderByIdDesc(
                    resourceType, resourceId);
            if (rows.size() >= minCount) {
                return rows;
            }
            Thread.sleep(50);
        }
        return auditLogRepository.findByResourceTypeAndResourceIdOrderByIdDesc(resourceType, resourceId);
    }
}
