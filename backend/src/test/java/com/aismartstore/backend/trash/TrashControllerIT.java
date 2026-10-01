package com.aismartstore.backend.trash;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aismartstore.backend.accounts.Account;
import com.aismartstore.backend.accounts.AccountRepository;
import com.aismartstore.backend.audit.AuditLog;
import com.aismartstore.backend.audit.AuditLogRepository;
import com.aismartstore.backend.digitization.DigitizationSourceRepository;
import com.aismartstore.backend.digitization.SourceSeparationJobRepository;
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
import com.aismartstore.backend.outbox.OutboxEventRepository;
import com.aismartstore.backend.storage.ObjectStorageService;
import com.aismartstore.backend.testsupport.RolePermissionFixtures;
import com.aismartstore.backend.warehouse.WarehouseFolder;
import com.aismartstore.backend.warehouse.WarehouseFolderRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
 * End-to-end coverage of delete-restore-trash-01-xoa-mem-thung-rac.md §5
 * (file 02's own restore-conflict/purge coverage lives in {@code
 * TrashRestoreConflictControllerIT}/{@code TrashPurgeServiceIT}): real
 * Postgres (Flyway through the latest migration) + MockMvc, same
 * Testcontainers pattern as {@code WarehouseControllerIT}/{@code
 * DocumentOwnershipControllerIT}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Testcontainers
class TrashControllerIT {

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
    private ObjectMapper objectMapper;

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private DigitizationSourceRepository digitizationSourceRepository;

    @Autowired
    private SourceSeparationJobRepository sourceSeparationJobRepository;

    @Autowired
    private WarehouseFolderRepository warehouseFolderRepository;

    @Autowired
    private GroupRepository groupRepository;

    @Autowired
    private GroupMemberRepository groupMemberRepository;

    @Autowired
    private DocumentGroupShareRepository documentGroupShareRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private AccountRepository accountRepository;

    private UUID folderId;

    @BeforeEach
    void resetState() {
        documentGroupShareRepository.deleteAll();
        groupMemberRepository.deleteAll();
        groupRepository.deleteAll();
        digitizationSourceRepository.deleteAll();
        sourceSeparationJobRepository.deleteAll();
        documentRepository.deleteAll();
        outboxEventRepository.deleteAll();
        accountRepository.deleteAll();

        List<WarehouseFolder> all = new ArrayList<>(warehouseFolderRepository.findAll());
        all.sort(Comparator.comparingInt(WarehouseFolder::getDepth).reversed());
        warehouseFolderRepository.deleteAll(all);

        WarehouseFolder folder = new WarehouseFolder();
        folder.setName("Root " + UUID.randomUUID());
        folder.setPathIds(new UUID[0]);
        folder.setDepth((short) 1);
        folder.setCreatedAt(OffsetDateTime.now());
        folder.setUpdatedAt(OffsetDateTime.now());
        folderId = warehouseFolderRepository.save(folder).getId();
    }

    private static JwtRequestPostProcessor asRole(String username, String realmRole, String springRole) {
        return jwt().jwt(builder -> builder.subject(username)
                        .claim("preferred_username", username)
                        .claim("realm_access", Map.of("roles", List.of(realmRole))))
                .authorities(RolePermissionFixtures.authoritiesFor(springRole));
    }

    private static JwtRequestPostProcessor officer(String username) {
        return asRole(username, "can-bo", "CAN_BO");
    }

    private static JwtRequestPostProcessor admin() {
        return asRole("admin-1", "quan-tri", "QUAN_TRI");
    }

    /** R2 - same {@code asRole(...)} pattern as {@code officer}/{@code admin} (e.g. {@code DocumentOwnershipControllerIT#leader}). */
    private static JwtRequestPostProcessor leader(String username) {
        return asRole(username, "lanh-dao", "LANH_DAO");
    }

    private Document saveDocument(String uploaderSub) {
        Document document = new Document();
        document.setOriginalName("f-" + UUID.randomUUID() + ".txt");
        document.setMimeType("text/plain");
        document.setSize(1024);
        document.setSha256("a".repeat(64));
        document.setStorageKey("documents/" + UUID.randomUUID());
        document.setRelativePath("");
        document.setFolderId(folderId);
        document.setUploaderSub(uploaderSub);
        document.setUploaderUsername(uploaderSub);
        document.setStatus(DocumentStatus.CLEAN);
        document.setCreatedAt(OffsetDateTime.now());
        return documentRepository.save(document);
    }

    private void seedAccount(String sub, String status) {
        Account account = new Account();
        account.setKeycloakUserId(sub);
        account.setStatus(status);
        if (!"active".equals(status)) {
            account.setStatusReason("test fixture");
        }
        account.setStorageQuotaBytes(0);
        account.setCreatedAt(OffsetDateTime.now());
        account.setUpdatedAt(OffsetDateTime.now());
        accountRepository.save(account);
    }

    // ---- Story 1: xóa mềm + biến mất khỏi tra cứu ---------------------------

    @Test
    void trashRemovesDocumentFromListImmediatelyAndAppearsInOwnTrash() throws Exception {
        Document document = saveDocument("officer-a");

        mockMvc.perform(post("/documents/trash")
                        .with(officer("officer-a"))
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("documentIds", List.of(document.getId())))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.itemCount").value(1))
                .andExpect(jsonPath("$.batchId").exists())
                .andExpect(jsonPath("$.purgeAfter").exists());

        mockMvc.perform(get("/documents").with(officer("officer-a")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(0));

        mockMvc.perform(get("/trash").with(officer("officer-a")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].documentId").value(document.getId().toString()));

        assertThat(documentRepository.findById(document.getId()).orElseThrow().getDeletedAt()).isNotNull();
    }

    @Test
    void trashRejectsWholeBatchWhenOneDocumentIsOutOfScope() throws Exception {
        Document owned = saveDocument("officer-a");
        Document strangers = saveDocument("officer-b");

        mockMvc.perform(post("/documents/trash")
                        .with(officer("officer-a"))
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(
                                Map.of("documentIds", List.of(owned.getId(), strangers.getId())))))
                .andExpect(status().isForbidden());

        assertThat(documentRepository.findById(owned.getId()).orElseThrow().getDeletedAt()).isNull();
        assertThat(documentRepository.findById(strangers.getId()).orElseThrow().getDeletedAt()).isNull();

        // Gap 2: the out-of-scope document must have a real "document.trash.denied" audit_log row.
        List<AuditLog> deniedRows = auditLogRepository.findByResourceTypeAndResourceIdOrderByIdDesc(
                "document", strangers.getId().toString());
        assertThat(deniedRows).anySatisfy(row -> {
            assertThat(row.getAction()).isEqualTo("document.trash.denied");
            assertThat(row.getResourceId()).isEqualTo(strangers.getId().toString());
        });
    }

    @Test
    void trashingAnAlreadyTrashedDocumentIsConflict() throws Exception {
        Document document = saveDocument("officer-a");
        document.setDeletedAt(OffsetDateTime.now());
        documentRepository.save(document);

        mockMvc.perform(post("/documents/trash")
                        .with(officer("officer-a"))
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("documentIds", List.of(document.getId())))))
                .andExpect(status().isConflict());
    }

    @Test
    void trashRejectsEmptyOrOversizedDocumentIdsWith400() throws Exception {
        mockMvc.perform(post("/documents/trash")
                        .with(officer("officer-a"))
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("documentIds", List.of()))))
                .andExpect(status().isBadRequest());

        List<UUID> tooMany = java.util.stream.Stream.generate(UUID::randomUUID).limit(501).toList();
        mockMvc.perform(post("/documents/trash")
                        .with(officer("officer-a"))
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("documentIds", tooMany))))
                .andExpect(status().isBadRequest());
    }

    // ---- POST /documents/trash-preview: activeGroupShareCount ---------------

    @Test
    void trashPreviewReturnsActiveGroupShareCountForEachDocument() throws Exception {
        Document shared = saveDocument("officer-a");
        Document unshared = saveDocument("officer-a");
        Group group = new Group();
        group.setName("Nhom preview " + UUID.randomUUID());
        group.setOwnerSub("officer-a");
        group.setCreatedAt(OffsetDateTime.now());
        group.setUpdatedAt(OffsetDateTime.now());
        groupRepository.save(group);
        DocumentGroupShare share = new DocumentGroupShare();
        share.setDocumentId(shared.getId());
        share.setGroupId(group.getId());
        share.setSharedBySub("officer-a");
        share.setSharedAt(OffsetDateTime.now());
        documentGroupShareRepository.save(share);

        mockMvc.perform(post("/documents/trash-preview")
                        .with(officer("officer-a"))
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(
                                Map.of("documentIds", List.of(shared.getId(), unshared.getId())))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].documentId").value(shared.getId().toString()))
                .andExpect(jsonPath("$[0].activeGroupShareCount").value(1))
                .andExpect(jsonPath("$[1].documentId").value(unshared.getId().toString()))
                .andExpect(jsonPath("$[1].activeGroupShareCount").value(0));
    }

    // ---- Story 1 AC-4/Quyết định 3 SA: chia sẻ nhóm không bị xóa --------------

    @Test
    void trashDoesNotRemoveGroupSharesAndRestoreKeepsThemIntact() throws Exception {
        Document document = saveDocument("officer-a");
        Group group = new Group();
        group.setName("Nhom test " + UUID.randomUUID());
        group.setOwnerSub("officer-a");
        group.setCreatedAt(OffsetDateTime.now());
        group.setUpdatedAt(OffsetDateTime.now());
        groupRepository.save(group);

        DocumentGroupShare share = new DocumentGroupShare();
        share.setDocumentId(document.getId());
        share.setGroupId(group.getId());
        share.setSharedBySub("officer-a");
        share.setSharedAt(OffsetDateTime.now());
        documentGroupShareRepository.save(share);

        long sharesBefore = documentGroupShareRepository.countByDocumentId(document.getId());

        mockMvc.perform(post("/documents/trash")
                        .with(officer("officer-a"))
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("documentIds", List.of(document.getId())))))
                .andExpect(status().isOk());

        assertThat(documentGroupShareRepository.countByDocumentId(document.getId())).isEqualTo(sharesBefore);

        mockMvc.perform(post("/trash/" + document.getId() + "/restore")
                        .with(officer("officer-a"))
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isOk());

        assertThat(documentGroupShareRepository.countByDocumentId(document.getId())).isEqualTo(sharesBefore);
    }

    // ---- Story 2: quyền phục hồi (Quyết định 6 SA) ---------------------------

    @Test
    void restoreForbiddenForSomeoneWhoNeitherUploadedNorDeletedIt() throws Exception {
        Document document = saveDocument("officer-a");
        mockMvc.perform(post("/documents/trash")
                        .with(officer("officer-a"))
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("documentIds", List.of(document.getId())))))
                .andExpect(status().isOk());

        mockMvc.perform(post("/trash/" + document.getId() + "/restore")
                        .with(officer("officer-c"))
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void restoreAllowedForUploaderEvenWhenSomeoneElseDeletedIt() throws Exception {
        Document document = saveDocument("officer-a");
        Group group = new Group();
        group.setName("Nhom " + UUID.randomUUID());
        group.setOwnerSub("officer-a");
        group.setCreatedAt(OffsetDateTime.now());
        group.setUpdatedAt(OffsetDateTime.now());
        groupRepository.save(group);
        GroupMember membership = new GroupMember();
        membership.setGroupId(group.getId());
        membership.setUserSub("officer-b");
        membership.setRoleInGroup(GroupRole.MEMBER);
        membership.setJoinedAt(OffsetDateTime.now());
        groupMemberRepository.save(membership);
        DocumentGroupShare share = new DocumentGroupShare();
        share.setDocumentId(document.getId());
        share.setGroupId(group.getId());
        share.setSharedBySub("officer-a");
        share.setSharedAt(OffsetDateTime.now());
        documentGroupShareRepository.save(share);

        // officer-b (group member, shared access) trashes officer-a's document.
        mockMvc.perform(post("/documents/trash")
                        .with(officer("officer-b"))
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("documentIds", List.of(document.getId())))))
                .andExpect(status().isOk());

        // officer-a (uploader, not the deleter) can still restore it (Story 2 AC-2).
        mockMvc.perform(post("/trash/" + document.getId() + "/restore")
                        .with(officer("officer-a"))
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documentId").value(document.getId().toString()));

        assertThat(documentRepository.findById(document.getId()).orElseThrow().getDeletedAt()).isNull();
    }

    @Test
    void restoreWithTargetFolderIdMovesTheDocument() throws Exception {
        Document document = saveDocument("officer-a");
        mockMvc.perform(post("/documents/trash")
                        .with(officer("officer-a"))
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("documentIds", List.of(document.getId())))))
                .andExpect(status().isOk());

        WarehouseFolder otherFolder = new WarehouseFolder();
        otherFolder.setName("Other " + UUID.randomUUID());
        otherFolder.setPathIds(new UUID[0]);
        otherFolder.setDepth((short) 1);
        otherFolder.setCreatedAt(OffsetDateTime.now());
        otherFolder.setUpdatedAt(OffsetDateTime.now());
        UUID otherFolderId = warehouseFolderRepository.save(otherFolder).getId();

        mockMvc.perform(post("/trash/" + document.getId() + "/restore")
                        .with(officer("officer-a"))
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("targetFolderId", otherFolderId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.restoredFolderId").value(otherFolderId.toString()));

        assertThat(documentRepository.findById(document.getId()).orElseThrow().getFolderId()).isEqualTo(otherFolderId);

        // Gap 2: restore must leave behind a real "document.restore" audit_log row.
        List<AuditLog> restoreRows =
                auditLogRepository.findByResourceTypeAndResourceIdOrderByIdDesc("document", document.getId().toString());
        assertThat(restoreRows).anySatisfy(row -> {
            assertThat(row.getAction()).isEqualTo("document.restore");
            assertThat(row.getResourceId()).isEqualTo(document.getId().toString());
        });
    }

    // ---- delete-restore-trash-02 §3 AC-6: groupsLostEffect (nhóm chia sẻ đã bị xóa) ----

    @Test
    void restoreReturnsGroupsLostEffectWhenSharedGroupWasSoftDeleted() throws Exception {
        Document document = saveDocument("officer-a");
        Group group = new Group();
        group.setName("Nhom lost " + UUID.randomUUID());
        group.setOwnerSub("officer-a");
        group.setCreatedAt(OffsetDateTime.now());
        group.setUpdatedAt(OffsetDateTime.now());
        groupRepository.save(group);
        DocumentGroupShare share = new DocumentGroupShare();
        share.setDocumentId(document.getId());
        share.setGroupId(group.getId());
        share.setSharedBySub("officer-a");
        share.setSharedAt(OffsetDateTime.now());
        documentGroupShareRepository.save(share);

        mockMvc.perform(post("/documents/trash")
                        .with(officer("officer-a"))
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("documentIds", List.of(document.getId())))))
                .andExpect(status().isOk());

        // Soft-delete the group itself (not the share) while the document sits in the trash.
        group.setDeletedAt(OffsetDateTime.now());
        groupRepository.save(group);

        mockMvc.perform(post("/trash/" + document.getId() + "/restore")
                        .with(officer("officer-a"))
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groupsLostEffect", org.hamcrest.Matchers.contains(group.getId().toString())));
    }

    // ---- Story 3: R3 stats, không rò rỉ nội dung ------------------------------

    @Test
    void statsForbiddenForR1AndDoesNotLeakBusinessFields() throws Exception {
        Document document = saveDocument("officer-a");
        document.setDeletedAt(OffsetDateTime.now());
        document.setDeletedBySub("officer-a");
        document.setPurgeAfter(OffsetDateTime.now().plusDays(365));
        documentRepository.save(document);

        mockMvc.perform(get("/trash/stats").with(officer("officer-a"))).andExpect(status().isForbidden());

        mockMvc.perform(get("/trash/stats").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.originalName").doesNotExist())
                .andExpect(jsonPath("$.deletedBySub").doesNotExist())
                .andExpect(jsonPath("$.byExpiryBucket").isArray());
    }

    @Test
    void detailForbiddenForR3() throws Exception {
        Document document = saveDocument("officer-a");
        mockMvc.perform(post("/documents/trash")
                        .with(officer("officer-a"))
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("documentIds", List.of(document.getId())))))
                .andExpect(status().isOk());

        mockMvc.perform(get("/trash/" + document.getId()).with(admin())).andExpect(status().isForbidden());
    }

    // ---- Story 3 AC-4: unrecoverableAccounts -----------------------------------

    @Test
    void unrecoverableAccountsCountsOnlyWhenBothDeleterAndUploaderAreDisabled() throws Exception {
        seedAccount("uploader-active", "active");
        seedAccount("deleter-locked", "locked");
        seedAccount("uploader-locked", "locked");

        // Case 1: deleter disabled, uploader still active -> NOT counted.
        Document stillRecoverable = saveDocument("uploader-active");
        stillRecoverable.setDeletedAt(OffsetDateTime.now());
        stillRecoverable.setDeletedBySub("deleter-locked");
        stillRecoverable.setPurgeAfter(OffsetDateTime.now().plusDays(300));
        documentRepository.save(stillRecoverable);

        // Case 2: both disabled -> counted.
        Document orphaned = saveDocument("uploader-locked");
        orphaned.setDeletedAt(OffsetDateTime.now());
        orphaned.setDeletedBySub("deleter-locked");
        orphaned.setPurgeAfter(OffsetDateTime.now().plusDays(10));
        documentRepository.save(orphaned);

        mockMvc.perform(get("/trash/stats").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unrecoverableAccounts.count").value(1));
    }

    // ---- audit + outbox side effects ------------------------------------------

    @Test
    void trashWritesOneOutboxEventAndOneAuditEntryPerDocument() throws Exception {
        Document d1 = saveDocument("officer-a");
        Document d2 = saveDocument("officer-a");
        long outboxBefore = outboxEventRepository.count();

        mockMvc.perform(post("/documents/trash")
                        .with(officer("officer-a"))
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("documentIds", List.of(d1.getId(), d2.getId())))))
                .andExpect(status().isOk());

        assertThat(outboxEventRepository.count()).isEqualTo(outboxBefore + 1);

        List<AuditLog> d1Rows =
                auditLogRepository.findByResourceTypeAndResourceIdOrderByIdDesc("document", d1.getId().toString());
        assertThat(d1Rows).anySatisfy(row -> assertThat(row.getAction()).isEqualTo("document.trash"));
    }

    // ---- r2-business-parity.md ADR-1 / V35: LANH_DAO (R2) now has
    // trash.manage too (SPEC-r2-business-parity-01 AC-1, R-7) - "chính chủ"
    // (xoá/khôi phục tài liệu của chính mình) still applies, same as R1;
    // R3 (QUAN_TRI) still lacks PERM_trash.manage. ----------------------------

    @Test
    void trashSucceedsForR2LeaderOwner() throws Exception {
        Document document = saveDocument("leader-1");

        mockMvc.perform(post("/documents/trash")
                        .with(leader("leader-1"))
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("documentIds", List.of(document.getId())))))
                .andExpect(status().isOk());
    }

    @Test
    void trashForbiddenForR3Admin() throws Exception {
        Document document = saveDocument("officer-a");

        mockMvc.perform(post("/documents/trash")
                        .with(admin())
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("documentIds", List.of(document.getId())))))
                .andExpect(status().isForbidden());
    }

    @Test
    void restoreSucceedsForR2LeaderOwner() throws Exception {
        Document document = saveDocument("leader-1");
        mockMvc.perform(post("/documents/trash")
                        .with(leader("leader-1"))
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("documentIds", List.of(document.getId())))))
                .andExpect(status().isOk());

        mockMvc.perform(post("/trash/" + document.getId() + "/restore")
                        .with(leader("leader-1"))
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isOk());
    }

    @Test
    void restoreForbiddenForR3Admin() throws Exception {
        Document document = saveDocument("officer-a");
        mockMvc.perform(post("/documents/trash")
                        .with(officer("officer-a"))
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("documentIds", List.of(document.getId())))))
                .andExpect(status().isOk());

        mockMvc.perform(post("/trash/" + document.getId() + "/restore")
                        .with(admin())
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isForbidden());
    }

    // SPEC-r2-business-parity-01 AC-9 (R-6): leader sees their own trashed
    // document in /trash and can restore it; another officer does not see it.
    @Test
    void leaderSeesOwnTrashedDocumentAndAnotherOfficerDoesNot() throws Exception {
        Document document = saveDocument("leader-1");

        mockMvc.perform(post("/documents/trash")
                        .with(leader("leader-1"))
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("documentIds", List.of(document.getId())))))
                .andExpect(status().isOk());

        mockMvc.perform(get("/trash").with(leader("leader-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].documentId").value(document.getId().toString()));

        mockMvc.perform(get("/trash").with(officer("officer-a")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));

        mockMvc.perform(post("/trash/" + document.getId() + "/restore")
                        .with(leader("leader-1"))
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isOk());

        assertThat(documentRepository.findById(document.getId()).orElseThrow().getDeletedAt()).isNull();
    }
}
