package com.aismartstore.backend.warehouse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aismartstore.backend.digitization.DigitizationSourceRepository;
import com.aismartstore.backend.digitization.SourceSeparationJobRepository;
import com.aismartstore.backend.documents.Document;
import com.aismartstore.backend.documents.DocumentRepository;
import com.aismartstore.backend.documents.DocumentStatus;
import com.aismartstore.backend.storage.ObjectStorageService;
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
import org.springframework.security.oauth2.jwt.Jwt;
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
 * End-to-end coverage of warehouse-structure.md §5 ("Integration test"): real
 * Postgres (Flyway-migrated through V9) + real Redis (system-config
 * thresholds cache) behind MockMvc - same Testcontainers pattern as
 * {@code SystemConfigControllerIT}/{@code AccountsControllerIT}.
 * {@link ObjectStorageService} is mocked purely to skip its
 * {@code @PostConstruct} SeaweedFS bootstrap; documents are inserted
 * directly via {@link DocumentRepository} (no need to exercise the
 * scan/upload pipeline for this feature).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Testcontainers
class WarehouseControllerIT {

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
    private WarehouseFolderRepository warehouseFolderRepository;

    @Autowired
    private WarehouseRepository warehouseRepository;

    @Autowired
    private DocumentRepository documentRepository;

    // digitization-pipeline-01-source-dispatch.md V18: FK-dependent tables must
    // be cleared before documentRepository.deleteAll() (see PersonalControllerIT).
    @Autowired
    private DigitizationSourceRepository digitizationSourceRepository;

    @Autowired
    private SourceSeparationJobRepository sourceSeparationJobRepository;

    private static final long DEFAULT_MAX_CAPACITY_BYTES = 10_995_116_277_760L;

    /**
     * warehouse_folder self-references itself (parent_id, ON DELETE
     * RESTRICT) so the whole table can't be wiped with one unordered bulk
     * delete; deepest-first ordering avoids ever deleting a row that's still
     * referenced by another row still pending deletion. document.folder_id
     * is ON DELETE SET NULL so document rows are simply cleared first.
     */
    @BeforeEach
    void resetState() {
        digitizationSourceRepository.deleteAll();
        sourceSeparationJobRepository.deleteAll();
        documentRepository.deleteAll();
        List<WarehouseFolder> all = new ArrayList<>(warehouseFolderRepository.findAll());
        all.sort(Comparator.comparingInt(WarehouseFolder::getDepth).reversed());
        warehouseFolderRepository.deleteAll(all);

        Warehouse warehouse = warehouseRepository.findById(WarehouseRepository.SINGLETON_ID).orElseThrow();
        warehouse.setMaxCapacityBytes(DEFAULT_MAX_CAPACITY_BYTES);
        warehouseRepository.save(warehouse);
    }

    private static JwtRequestPostProcessor asRole(String username, String realmRole, String springRole) {
        return jwt().jwt(builder -> builder.subject(username)
                        .claim("preferred_username", username)
                        .claim("realm_access", Map.of("roles", List.of(realmRole))))
                .authorities(com.aismartstore.backend.testsupport.RolePermissionFixtures.authoritiesFor(springRole));
    }

    private static JwtRequestPostProcessor admin() {
        return asRole("admin-1", "quan-tri", "QUAN_TRI");
    }

    private static JwtRequestPostProcessor officer() {
        return asRole("officer-1", "can-bo", "CAN_BO");
    }

    private static JwtRequestPostProcessor leader() {
        return asRole("leader-1", "lanh-dao", "LANH_DAO");
    }

    private UUID createFolder(String name, UUID parentId) throws Exception {
        Map<String, Object> body = parentId == null ? Map.of("name", name) : Map.of("name", name, "parentId", parentId);
        String json = mockMvc.perform(post("/folders")
                        .with(admin())
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        @SuppressWarnings("unchecked")
        Map<String, Object> parsed = objectMapper.readValue(json, Map.class);
        return UUID.fromString((String) parsed.get("id"));
    }

    private Document saveDocument(UUID folderId, String status, long size, OffsetDateTime deletedAt) {
        Document d = new Document();
        d.setOriginalName("f.txt");
        d.setMimeType("text/plain");
        d.setSize(size);
        d.setSha256("a".repeat(64));
        d.setFolderId(folderId);
        d.setUploaderSub("uploader-sub");
        d.setUploaderUsername("uploader");
        d.setStatus(status);
        d.setCreatedAt(OffsetDateTime.now());
        d.setDeletedAt(deletedAt);
        return documentRepository.save(d);
    }

    // ---- Story 1 AC-1: R3-only mutation ---------------------------------------

    @Test
    void nonAdminIsForbiddenOnEveryMutatingEndpoint() throws Exception {
        UUID folderId = createFolder("Root", null);

        mockMvc.perform(post("/folders")
                        .with(officer())
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("name", "X"))))
                .andExpect(status().isForbidden());
        mockMvc.perform(patch("/folders/" + folderId)
                        .with(officer())
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("name", "Y"))))
                .andExpect(status().isForbidden());
        mockMvc.perform(patch("/folders/" + folderId + "/move")
                        .with(officer())
                        .contentType("application/json")
                        .content("{\"newParentId\":null}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/folders/" + folderId).with(officer())).andExpect(status().isForbidden());
        mockMvc.perform(patch("/warehouse/capacity")
                        .with(officer())
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("maxCapacityBytes", 1000))))
                .andExpect(status().isForbidden());
    }

    // ---- Story 4 AC-1: every logged-in role can read the tree / capacity -----

    @Test
    void everyLoggedInRoleCanReadTreeAndCapacity() throws Exception {
        createFolder("Root", null);

        mockMvc.perform(get("/folders/tree").with(officer())).andExpect(status().isOk());
        mockMvc.perform(get("/folders/tree").with(leader())).andExpect(status().isOk());
        mockMvc.perform(get("/warehouse/capacity").with(officer())).andExpect(status().isOk());
    }

    // ---- Story 1: create/rename + depth/name rules ---------------------------

    @Test
    void createDuplicateNameSameParentIsConflict() throws Exception {
        createFolder("Ho so vu an", null);

        mockMvc.perform(post("/folders")
                        .with(admin())
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("name", "Ho so vu an"))))
                .andExpect(status().isConflict());
    }

    @Test
    void createRejectsAccentedNameWith400() throws Exception {
        mockMvc.perform(post("/folders")
                        .with(admin())
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("name", "Hồ sơ"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void creatingSixthLevelIsRejectedWithMaxDepthExceeded() throws Exception {
        UUID parentId = null;
        for (int level = 1; level <= 5; level++) {
            parentId = createFolder("Level " + level, parentId);
        }

        mockMvc.perform(post("/folders")
                        .with(admin())
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("name", "Level 6", "parentId", parentId))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MAX_DEPTH_EXCEEDED"));
    }

    @Test
    void renameDoesNotBreakChildAccessOrPath() throws Exception {
        UUID parentId = createFolder("Parent", null);
        UUID childId = createFolder("Child", parentId);

        mockMvc.perform(patch("/folders/" + parentId)
                        .with(admin())
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("name", "Parent Renamed"))))
                .andExpect(status().isOk());

        mockMvc.perform(get("/folders/tree").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Parent Renamed"))
                .andExpect(jsonPath("$[0].children[0].id").value(childId.toString()))
                .andExpect(jsonPath("$[0].children[0].depth").value(2));
    }

    // ---- Story 2: move ---------------------------------------------------------

    @Test
    void moveIntoOwnDescendantIsCycleDetected() throws Exception {
        UUID aId = createFolder("A", null);
        UUID bId = createFolder("B", aId);

        mockMvc.perform(patch("/folders/" + aId + "/move")
                        .with(admin())
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("newParentId", bId))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CYCLE_DETECTED"));
    }

    @Test
    void movingBranchUpdatesDepthOfWholeSubtreeAndKeepsItReachable() throws Exception {
        UUID rootA = createFolder("A", null);
        UUID branchB = createFolder("B", rootA); // depth 2
        UUID leafC = createFolder("C", branchB); // depth 3
        UUID rootD = createFolder("D", null); // depth 1, move target

        mockMvc.perform(patch("/folders/" + branchB + "/move")
                        .with(admin())
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("newParentId", rootD))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.parentId").value(rootD.toString()))
                .andExpect(jsonPath("$.depth").value(2));

        mockMvc.perform(get("/folders/tree").with(admin()))
                .andExpect(status().isOk())
                // A no longer has B as a child.
                .andExpect(jsonPath("$[?(@.id=='" + rootA + "')].children.length()").value(0))
                // D now has B (depth 2) with C (depth 3) still nested under it.
                .andExpect(jsonPath("$[?(@.id=='" + rootD + "')].children[0].id").value(branchB.toString()))
                .andExpect(jsonPath("$[?(@.id=='" + rootD + "')].children[0].depth").value(2))
                .andExpect(jsonPath("$[?(@.id=='" + rootD + "')].children[0].children[0].id").value(leafC.toString()))
                .andExpect(jsonPath("$[?(@.id=='" + rootD + "')].children[0].children[0].depth").value(3));
    }

    // ---- Story 3: delete only blocked by child folders / active documents ----

    @Test
    void deleteBlockedByChildFolder() throws Exception {
        UUID parentId = createFolder("Parent", null);
        createFolder("Child", parentId);

        mockMvc.perform(delete("/folders/" + parentId).with(admin()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("FOLDER_NOT_EMPTY"))
                .andExpect(jsonPath("$.reason").value("hasChildFolders"));
    }

    @Test
    void deleteBlockedByActiveCleanDocument() throws Exception {
        UUID folderId = createFolder("HasDocs", null);
        saveDocument(folderId, DocumentStatus.CLEAN, 1024, null);

        mockMvc.perform(delete("/folders/" + folderId).with(admin()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("FOLDER_NOT_EMPTY"))
                .andExpect(jsonPath("$.reason").value("hasActiveDocuments"));
    }

    @Test
    void deleteSucceedsWhenOnlyRejectedOrInfectedDocumentsRemainAndDetachesThem() throws Exception {
        UUID folderId = createFolder("OnlyRejected", null);
        Document rejected = saveDocument(folderId, DocumentStatus.REJECTED, 10, null);
        Document infected = saveDocument(folderId, DocumentStatus.INFECTED, 10, null);

        mockMvc.perform(delete("/folders/" + folderId).with(admin())).andExpect(status().isNoContent());

        assertThat(documentRepository.findById(rejected.getId())).get().satisfies(d ->
                assertThat(d.getFolderId()).isNull());
        assertThat(documentRepository.findById(infected.getId())).get().satisfies(d ->
                assertThat(d.getFolderId()).isNull());
    }

    @Test
    void deleteSucceedsWhenOnlyTrashedCleanDocumentsRemain() throws Exception {
        // Story 3 AC-2: a folder whose only documents are 'clean' but soft-deleted
        // (deleted_at set) is treated as empty and deletable - Task 0/Quyet dinh 1
        // muc 0's minimal deleted_at column, exercised via a direct fixture write
        // since no trash UI/API exists yet.
        UUID folderId = createFolder("OnlyTrashed", null);
        saveDocument(folderId, DocumentStatus.CLEAN, 10, OffsetDateTime.now());

        mockMvc.perform(delete("/folders/" + folderId).with(admin())).andExpect(status().isNoContent());
    }

    // ---- Story 5: warehouse capacity -------------------------------------------

    @Test
    void usedBytesCountsTrashedCleanDocumentsToo() throws Exception {
        UUID folderId = createFolder("CapacityFolder", null);
        saveDocument(folderId, DocumentStatus.CLEAN, 100 * 1024L * 1024L, null); // active, 100MB
        saveDocument(folderId, DocumentStatus.CLEAN, 50 * 1024L * 1024L, OffsetDateTime.now()); // trashed, 50MB
        saveDocument(folderId, DocumentStatus.REJECTED, 999 * 1024L * 1024L, null); // never counted

        mockMvc.perform(get("/warehouse/capacity").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.usedBytes").value(150 * 1024 * 1024));
    }

    // ---- delete-restore-trash-01-xoa-mem-thung-rac.md §4.4 (Story 4) ---------

    @Test
    void trashBytesReflectsOnlySoftDeletedCleanDocumentsAndUsedBytesStaysUnchanged() throws Exception {
        UUID folderId = createFolder("TrashBadgeFolder", null);
        saveDocument(folderId, DocumentStatus.CLEAN, 100 * 1024L * 1024L, null); // active, 100MB
        saveDocument(folderId, DocumentStatus.CLEAN, 50 * 1024L * 1024L, OffsetDateTime.now()); // trashed, 50MB

        mockMvc.perform(get("/warehouse/capacity").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.usedBytes").value(150 * 1024 * 1024))
                .andExpect(jsonPath("$.trashBytes").value(50 * 1024 * 1024));
    }

    @Test
    void patchCapacityBelowUsedBytesStillSucceedsAndReflectsBlockedImmediately() throws Exception {
        UUID folderId = createFolder("CapacityFolder", null);
        saveDocument(folderId, DocumentStatus.CLEAN, 900_000, null);

        // 900_000 / 920_000 =~ 97.8% >= the 95% block threshold.
        mockMvc.perform(patch("/warehouse/capacity")
                        .with(admin())
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("maxCapacityBytes", 920_000))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("BLOCKED"));

        mockMvc.perform(get("/warehouse/capacity").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.maxCapacityBytes").value(920_000))
                .andExpect(jsonPath("$.status").value("BLOCKED"));
    }

    @Test
    void patchCapacityRejectsNonPositiveValue() throws Exception {
        mockMvc.perform(patch("/warehouse/capacity")
                        .with(admin())
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("maxCapacityBytes", 0))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_VALUE"));
    }
}
