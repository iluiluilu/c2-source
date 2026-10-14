package com.aismartstore.backend.reporting;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import com.aismartstore.backend.storage.ObjectStorageService;
import com.aismartstore.backend.testsupport.RolePermissionFixtures;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
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
 * Shared Testcontainers wiring + SQL fixtures for the export-reporting ITs (SPEC §5; pattern of
 * {@code BusinessDataScopeIT}). Fixtures are plain JDBC so tests control {@code created_at},
 * {@code status}, soft delete, tags, groups and shares exactly. Run with {@code mvn verify}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Testcontainers
public abstract class ReportITBase {

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
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", () -> "http://127.0.0.1:1/unused-jwks");
        // keep the background runner/cleanup out of the way unless a test drives them explicitly
        registry.add("app.reporting.runner-enabled", () -> "false");
    }

    @MockBean
    protected ObjectStorageService objectStorageService;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper objectMapper;

    protected UUID rootFolderId;

    @BeforeEach
    void resetReportingFixtures() {
        jdbc.execute(
                "TRUNCATE document, document_tag, document_group_share, tag, \"group\", group_member, "
                        + "digitization_source, digitization_run, document_digitization_status, warehouse_folder, "
                        + "report_export, report_saved_query CASCADE");
        rootFolderId = insertFolder("Root " + UUID.randomUUID(), null, new UUID[0]);
    }

    // ---- auth (authorities mirror the V12/V35/V36 seed, see RolePermissionFixtures) --------------------------------

    protected static JwtRequestPostProcessor asRole(String sub, String realmRole, String springRole) {
        return jwt().jwt(b -> b.subject(sub)
                        .claim("preferred_username", sub)
                        .claim("realm_access", Map.of("roles", List.of(realmRole))))
                .authorities(RolePermissionFixtures.authoritiesFor(springRole));
    }

    protected static JwtRequestPostProcessor canBo(String sub) {
        return asRole(sub, "can-bo", "CAN_BO");
    }

    protected static JwtRequestPostProcessor lanhDao(String sub) {
        return asRole(sub, "lanh-dao", "LANH_DAO");
    }

    protected static JwtRequestPostProcessor quanTri(String sub) {
        return asRole(sub, "quan-tri", "QUAN_TRI");
    }

    /** A CAN_BO token minus the given permissions (R3 revoked them through the RBAC screen). */
    protected static JwtRequestPostProcessor canBoWithout(String sub, String... revoked) {
        Set<String> drop = Set.of(revoked).stream().map(p -> "PERM_" + p).collect(java.util.stream.Collectors.toSet());
        return jwt().jwt(b -> b.subject(sub)
                        .claim("preferred_username", sub)
                        .claim("realm_access", Map.of("roles", List.of("can-bo"))))
                .authorities(RolePermissionFixtures.authoritiesFor("CAN_BO").stream()
                        .filter(a -> !drop.contains(a.getAuthority()))
                        .toList());
    }

    protected static String json(Object o) {
        try {
            return new ObjectMapper().findAndRegisterModules().writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    protected List<Map<String, Object>> auditRows(String action, String actorSub) {
        return jdbc.queryForList(
                "SELECT outcome, resource_id, metadata::text AS metadata, status_code FROM audit_log "
                        + "WHERE action = ? AND actor_sub = ? ORDER BY id",
                action, actorSub);
    }

    protected long auditCount(String action) {
        return jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = ?", Long.class, action);
    }

    protected UUID insertFolder(String name, UUID parentId, UUID[] pathIds) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO warehouse_folder (id, name, parent_id, path_ids, depth, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, now(), now())",
                ps -> {
                    ps.setObject(1, id);
                    ps.setString(2, name);
                    ps.setObject(3, parentId);
                    ps.setArray(4, ps.getConnection().createArrayOf("uuid", pathIds));
                    ps.setShort(5, (short) (pathIds.length + 1));
                });
        return id;
    }

    protected UUID insertDoc(String uploaderSub, String mime, long size, OffsetDateTime createdAt) {
        return insertDoc(uploaderSub, mime, size, createdAt, "clean", rootFolderId, false);
    }

    protected UUID insertDoc(
            String uploaderSub, String mime, long size, OffsetDateTime createdAt, String status, UUID folderId,
            boolean deleted) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO document (id, original_name, display_name, mime_type, size, sha256, storage_key, "
                        + "folder_id, uploader_sub, uploader_username, status, created_at, deleted_at, relative_path) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, '')",
                id, "f-" + id, "f-" + id, mime, size, "x".repeat(64), "k/" + id, folderId, uploaderSub, uploaderSub,
                status, Timestamp.from(createdAt.toInstant()), deleted ? Timestamp.from(createdAt.toInstant()) : null);
        return id;
    }

    protected UUID insertTag(String kind, String label, boolean active) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO tag (id, kind, label, created_by_sub, created_by_username, active) "
                        + "VALUES (?, ?, ?, 'seed', 'seed', ?)",
                id, kind, label, active);
        return id;
    }

    protected void tagDoc(UUID docId, UUID tagId, String kind) {
        jdbc.update(
                "INSERT INTO document_tag (document_id, tag_id, tag_kind, assigned_by_sub, assigned_by_username) "
                        + "VALUES (?, ?, ?, 'seed', 'seed')",
                docId, tagId, kind);
    }

    protected UUID insertGroup(String name, String ownerSub, String... memberSubs) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO \"group\" (id, name, owner_sub) VALUES (?, ?, ?)", id, name, ownerSub);
        jdbc.update(
                "INSERT INTO group_member (group_id, user_sub, role_in_group) VALUES (?, ?, 'owner')", id, ownerSub);
        for (String m : memberSubs) {
            jdbc.update(
                    "INSERT INTO group_member (group_id, user_sub, role_in_group) VALUES (?, ?, 'member')", id, m);
        }
        return id;
    }

    protected void shareDoc(UUID docId, UUID groupId, String sharedBy) {
        jdbc.update(
                "INSERT INTO document_group_share (document_id, group_id, shared_by_sub) VALUES (?, ?, ?)",
                docId, groupId, sharedBy);
    }

    protected UUID insertSource(UUID docId, String component, String mime, Integer durationMs) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO digitization_source (id, document_id, component_type, file_path, mime_type, duration_ms) "
                        + "VALUES (?, ?, ?, 'p', ?, ?)",
                id, docId, component, mime, durationMs);
        return id;
    }

    protected UUID insertRun(
            UUID docId, UUID sourceId, String task, String processing, String reviewState, String confirmSource,
            OffsetDateTime reviewDeadline, OffsetDateTime createdAt) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO digitization_run (id, document_id, source_id, task, processing_status, review_state, "
                        + "confirm_source, review_deadline_at, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, docId, sourceId, task, processing, reviewState, confirmSource,
                reviewDeadline == null ? null : Timestamp.from(reviewDeadline.toInstant()),
                Timestamp.from(createdAt.toInstant()));
        return id;
    }

    protected void setOverallStatus(UUID docId, String overall) {
        jdbc.update(
                "INSERT INTO document_digitization_status (document_id, overall_status) VALUES (?, ?) "
                        + "ON CONFLICT (document_id) DO UPDATE SET overall_status = EXCLUDED.overall_status",
                docId, overall);
    }
}
