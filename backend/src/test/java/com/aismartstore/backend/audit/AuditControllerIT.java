package com.aismartstore.backend.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aismartstore.backend.documents.Document;
import com.aismartstore.backend.documents.DocumentRepository;
import com.aismartstore.backend.documents.DocumentStatus;
import com.aismartstore.backend.groups.DocumentGroupShare;
import com.aismartstore.backend.groups.DocumentGroupShareRepository;
import com.aismartstore.backend.groups.Group;
import com.aismartstore.backend.groups.GroupMember;
import com.aismartstore.backend.groups.GroupMemberRepository;
import com.aismartstore.backend.groups.GroupRepository;
import com.aismartstore.backend.groups.GroupRole;
import com.aismartstore.backend.storage.ObjectStorageService;
import com.aismartstore.backend.warehouse.WarehouseFolder;
import com.aismartstore.backend.warehouse.WarehouseFolderRepository;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.UncategorizedSQLException;
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
 * End-to-end coverage of docs/03-dev/specs/audit-logging.md against a real,
 * Flyway-migrated Postgres (the seal-aware trigger, the sealer job's native
 * SQL, and R2/R3 authorization all depend on real DB behaviour that a mocked
 * repository can't exercise). Follows the {@code SystemConfigControllerIT} /
 * {@code BusinessDataScopeIT} pattern.
 *
 * <p><b>Not yet run in this environment</b> - written and reviewed, but this
 * sandbox has no Docker daemon, so Testcontainers cannot start Postgres/Redis
 * here. Run with Docker available before relying on it.
 *
 * <p>Scheduled jobs ({@link AuditSealerJob}, {@link AuditCheckpointJob}) are
 * invoked directly rather than waited on via their real {@code @Scheduled}
 * cadence, both for determinism and because {@code @EnableScheduling} is
 * active for the whole Spring context in every {@code @SpringBootTest} here -
 * the sealer's real 5s timer could otherwise race an assertion.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Testcontainers
class AuditControllerIT {

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

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AuditService auditService;

    @Autowired
    private AuditSealerJob sealerJob;

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private GroupRepository groupRepository;

    @Autowired
    private GroupMemberRepository groupMemberRepository;

    @Autowired
    private DocumentGroupShareRepository documentGroupShareRepository;

    @Autowired
    private WarehouseFolderRepository warehouseFolderRepository;

    private UUID folderId;

    @BeforeEach
    void resetState() {
        jdbcTemplate.update("DELETE FROM audit_checkpoint");
        // audit_log is append-only (V21 audit_log_seal_aware_immutable trigger rejects any
        // DELETE). TRUNCATE doesn't fire FOR EACH ROW triggers, so it clears the table between
        // tests without tripping the trigger; the chain state the sealer job cares about (each
        // row's own hash/prev_hash) lives entirely in the rows themselves, so this is equivalent
        // to the DELETE this replaced.
        jdbcTemplate.update("TRUNCATE TABLE audit_log");
        documentGroupShareRepository.deleteAll();
        groupMemberRepository.deleteAll();
        groupRepository.deleteAll();
        documentRepository.deleteAll();

        WarehouseFolder folder = new WarehouseFolder();
        folder.setName("Root " + UUID.randomUUID());
        folder.setPathIds(new UUID[0]);
        folder.setDepth((short) 1);
        folder.setCreatedAt(OffsetDateTime.now());
        folder.setUpdatedAt(OffsetDateTime.now());
        folderId = warehouseFolderRepository.save(folder).getId();
    }

    private Document saveDocument(String uploaderSub) {
        Document document = new Document();
        document.setOriginalName("file-" + UUID.randomUUID() + ".txt");
        document.setMimeType("text/plain");
        document.setSize(10);
        document.setSha256("x".repeat(64));
        document.setStorageKey("documents/" + UUID.randomUUID());
        document.setRelativePath("");
        document.setFolderId(folderId);
        document.setUploaderSub(uploaderSub);
        document.setUploaderUsername(uploaderSub);
        document.setStatus(DocumentStatus.CLEAN);
        document.setCreatedAt(OffsetDateTime.now());
        return documentRepository.save(document);
    }

    private static JwtRequestPostProcessor asRole(String username, String realmRole, String springRole) {
        return jwt().jwt(builder -> builder.subject(username)
                        .claim("preferred_username", username)
                        .claim("realm_access", Map.of("roles", List.of(realmRole))))
                .authorities(com.aismartstore.backend.testsupport.RolePermissionFixtures.authoritiesFor(springRole));
    }

    private static JwtRequestPostProcessor officer() {
        return asRole("officer-1", "can-bo", "CAN_BO");
    }

    private static JwtRequestPostProcessor leader(String sub) {
        return asRole(sub, "lanh-dao", "LANH_DAO");
    }

    private static JwtRequestPostProcessor admin() {
        return asRole("admin-1", "quan-tri", "QUAN_TRI");
    }

    private long insertUnsealedRow(String action, String resourceType, String resourceId) {
        jdbcTemplate.update(
                "INSERT INTO audit_log (created_at, actor, action, resource_type, resource_id, outcome) "
                        + "VALUES (now(), 'tester', ?, ?, ?, 'success')",
                action, resourceType, resourceId);
        return jdbcTemplate.queryForObject("SELECT max(id) FROM audit_log", Long.class);
    }

    // ---- §2.3 trigger: exactly one valid seal, everything else rejected -----

    @Test
    void triggerAllowsExactlyOneValidSealThenRejectsFurtherUpdates() {
        long id = insertUnsealedRow("tag.create", "tag", "tag-1");

        jdbcTemplate.update(
                "UPDATE audit_log SET prev_hash = NULL, hash = 'deadbeef', sealed_at = now() WHERE id = ?", id);

        assertThat(jdbcTemplate.queryForObject("SELECT hash FROM audit_log WHERE id = ?", String.class, id))
                .isEqualTo("deadbeef");

        // Re-seal (already sealed) must be rejected. The trigger raises a plain PL/pgSQL
        // RAISE EXCEPTION (SQLSTATE P0001, not one of the standard constraint-violation codes), so
        // Spring's SQLErrorCodeSQLExceptionTranslator can't map it to DataIntegrityViolationException
        // and falls back to UncategorizedSQLException - that fallback is the actual, correct
        // behaviour here, not a bug to work around.
        org.junit.jupiter.api.Assertions.assertThrows(UncategorizedSQLException.class, () ->
                jdbcTemplate.update(
                        "UPDATE audit_log SET hash = 'other', sealed_at = now() WHERE id = ?", id));

        // Changing an unrelated field on an unsealed row must also be rejected (not just re-seals).
        long otherId = insertUnsealedRow("tag.create", "tag", "tag-2");
        org.junit.jupiter.api.Assertions.assertThrows(UncategorizedSQLException.class, () ->
                jdbcTemplate.update("UPDATE audit_log SET actor = 'someone-else' WHERE id = ?", otherId));

        org.junit.jupiter.api.Assertions.assertThrows(UncategorizedSQLException.class, () ->
                jdbcTemplate.update("DELETE FROM audit_log WHERE id = ?", otherId));
    }

    // ---- §4.2 sealer job: chains a batch of unsealed rows correctly ----------

    @Test
    void sealerJobChainsUnsealedRowsAndTheResultVerifies() {
        auditService.record(new AuditService.AuditEntry(
                "user-1", "sub-1", "tag.create", "tag", "tag-1", "success", null, null, null, null, null));
        auditService.record(new AuditService.AuditEntry(
                "user-1", "sub-1", "tag.rename", "tag", "tag-1", "success", null, null, null, null, null));
        auditService.record(new AuditService.AuditEntry(
                "user-1", "sub-1", "tag.deactivate", "tag", "tag-1", "success", null, null, null, null, null));

        long unsealedBefore = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM audit_log WHERE hash IS NULL", Long.class);
        assertThat(unsealedBefore).isEqualTo(3);

        sealerJob.seal();

        long unsealedAfter = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM audit_log WHERE hash IS NULL", Long.class);
        assertThat(unsealedAfter).isZero();

        OffsetDateTime from = OffsetDateTime.now(ZoneOffset.UTC).minusHours(1);
        OffsetDateTime to = OffsetDateTime.now(ZoneOffset.UTC).plusHours(1);
        AuditService.ChainVerification result = auditService.verifyChain(from, to);
        assertThat(result.ok()).isTrue();
        assertThat(result.count()).isEqualTo(3);
    }

    @Test
    void sealerJobIsANoOpWhenNothingIsUnsealed() {
        sealerJob.seal(); // must not throw with an empty table
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM audit_log", Long.class)).isZero();
    }

    // ---- §3 GET /audit/verify: from/to are mandatory -------------------------

    @Test
    void verifyWithoutFromOrToReturns400() throws Exception {
        mockMvc.perform(get("/audit/verify").with(admin())).andExpect(status().isBadRequest());
    }

    // ---- §3/§4.4 GET /audit: R1 forbidden, R3 unrestricted, R2 scoped to their own/shared documents ----

    @Test
    void nonAuditRoleIsForbidden() throws Exception {
        mockMvc.perform(get("/audit").with(officer())).andExpect(status().isForbidden());
    }

    @Test
    void adminSeesEveryResourceType() throws Exception {
        insertUnsealedRow("account.create", "account", "acct-1");
        insertUnsealedRow("tag.create", "tag", "tag-1");

        mockMvc.perform(get("/audit").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(org.hamcrest.Matchers.greaterThanOrEqualTo(2)));
    }

    @Test
    void leaderOnlySeesDocumentEventsWithinTheirGroupScope_notAccountEvents() throws Exception {
        String leaderSub = "leader-" + UUID.randomUUID();
        Document ownDocument = saveDocument(leaderSub);
        Document strangersDocument = saveDocument("stranger-" + UUID.randomUUID());

        insertUnsealedRow("document.download", "document", ownDocument.getId().toString());
        insertUnsealedRow("document.download", "document", strangersDocument.getId().toString());
        insertUnsealedRow("account.create", "account", "acct-1");

        String response = mockMvc.perform(get("/audit").with(leader(leaderSub)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(response).contains(ownDocument.getId().toString());
        assertThat(response).doesNotContain(strangersDocument.getId().toString());
        assertThat(response).doesNotContain("acct-1");
    }

    @Test
    void leaderSeesDocumentsSharedToTheirGroupToo() throws Exception {
        String leaderSub = "leader-" + UUID.randomUUID();
        String uploaderSub = "uploader-" + UUID.randomUUID();
        Document sharedDocument = saveDocument(uploaderSub);

        Group group = new Group();
        group.setName("Nhom test");
        group.setOwnerSub(uploaderSub);
        group.setCreatedAt(OffsetDateTime.now());
        group.setUpdatedAt(OffsetDateTime.now());
        groupRepository.save(group);

        GroupMember membership = new GroupMember();
        membership.setGroupId(group.getId());
        membership.setUserSub(leaderSub);
        membership.setRoleInGroup(GroupRole.MEMBER);
        membership.setJoinedAt(OffsetDateTime.now());
        groupMemberRepository.save(membership);

        DocumentGroupShare share = new DocumentGroupShare();
        share.setDocumentId(sharedDocument.getId());
        share.setGroupId(group.getId());
        share.setSharedBySub(uploaderSub);
        share.setSharedAt(OffsetDateTime.now());
        documentGroupShareRepository.save(share);

        insertUnsealedRow("document.download", "document", sharedDocument.getId().toString());

        String response = mockMvc.perform(get("/audit").with(leader(leaderSub)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(response).contains(sharedDocument.getId().toString());
    }

    // SPEC-r2-business-parity-01 AC-9 (R-6): tag/note events on a document the
    // leader uploaded show up in the leader's own scoped /audit read - same
    // `audit.read_scoped` behaviour as for R1's events (V22, unchanged by this
    // story). NB: `ingest.batch.*` events use resource_type='upload_batch',
    // which AuditScopeFilter's documented v1 limitation never scopes to R2
    // regardless of uploader (see its class Javadoc) - that is a pre-existing,
    // out-of-scope limitation, not something this story changes or re-tests.
    @Test
    void leaderSeesTagAndNoteEventsOnTheirOwnDocument() throws Exception {
        String leaderSub = "leader-" + UUID.randomUUID();
        Document ownDocument = saveDocument(leaderSub);

        insertUnsealedRow("tag.assign", "document", ownDocument.getId().toString());
        insertUnsealedRow("note.create", "document", ownDocument.getId().toString());

        String response = mockMvc.perform(get("/audit").with(leader(leaderSub)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(response).contains(ownDocument.getId().toString());
        assertThat(response).contains("tag.assign");
        assertThat(response).contains("note.create");
    }
}
