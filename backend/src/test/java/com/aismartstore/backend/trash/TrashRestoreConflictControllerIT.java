package com.aismartstore.backend.trash;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aismartstore.backend.auth.KeycloakUser;
import com.aismartstore.backend.documents.Document;
import com.aismartstore.backend.documents.DocumentRepository;
import com.aismartstore.backend.documents.DocumentStatus;
import com.aismartstore.backend.documents.PurgeState;
import com.aismartstore.backend.outbox.OutboxEventRepository;
import com.aismartstore.backend.storage.ObjectStorageService;
import com.aismartstore.backend.testsupport.RolePermissionFixtures;
import com.aismartstore.backend.warehouse.WarehouseFolder;
import com.aismartstore.backend.warehouse.WarehouseFolderRepository;
import com.aismartstore.backend.warehouse.WarehouseService;
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
 * delete-restore-trash-02-xoa-cung-xung-dot.md §5 ("Unit - 8 case xung đột"
 * is covered by mocks in {@code TrashServiceTest}; this class covers the
 * same 8-case table end-to-end against a real Postgres/{@code
 * WarehouseService}, plus §4.4's folder-delete snapshot hook) - same
 * Testcontainers pattern as {@code TrashControllerIT}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Testcontainers
class TrashRestoreConflictControllerIT {

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
    private WarehouseFolderRepository warehouseFolderRepository;

    @Autowired
    private WarehouseService warehouseService;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    private UUID rootFolderId;

    @BeforeEach
    void resetState() {
        documentRepository.deleteAll();

        List<WarehouseFolder> all = new ArrayList<>(warehouseFolderRepository.findAll());
        all.sort(Comparator.comparingInt(WarehouseFolder::getDepth).reversed());
        warehouseFolderRepository.deleteAll(all);

        WarehouseFolder root = new WarehouseFolder();
        root.setName("Root " + UUID.randomUUID());
        root.setPathIds(new UUID[0]);
        root.setDepth((short) 1);
        root.setCreatedAt(OffsetDateTime.now());
        root.setUpdatedAt(OffsetDateTime.now());
        rootFolderId = warehouseFolderRepository.save(root).getId();
    }

    private static JwtRequestPostProcessor officer(String username) {
        return jwt().jwt(builder -> builder.subject(username)
                        .claim("preferred_username", username)
                        .claim("realm_access", Map.of("roles", List.of("can-bo"))))
                .authorities(RolePermissionFixtures.authoritiesFor("CAN_BO"));
    }

    private Document saveDocument(String uploaderSub, UUID folderId, String name) {
        Document document = new Document();
        document.setOriginalName(name);
        document.setMimeType("text/plain");
        document.setSize(1024);
        document.setSha256(UUID.randomUUID().toString().replace("-", ""));
        document.setStorageKey("documents/" + UUID.randomUUID());
        document.setRelativePath("");
        document.setFolderId(folderId);
        document.setUploaderSub(uploaderSub);
        document.setUploaderUsername(uploaderSub);
        document.setStatus(DocumentStatus.CLEAN);
        document.setCreatedAt(OffsetDateTime.now());
        return documentRepository.save(document);
    }

    private void trashViaHttp(String actor, UUID documentId) throws Exception {
        mockMvc.perform(post("/documents/trash")
                        .with(officer(actor))
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("documentIds", List.of(documentId)))))
                .andExpect(status().isOk());
    }

    // ---- AC-1: thư mục cha đã xóa + gợi ý thư mục gần nhất còn tồn tại (§4.4) --

    @Test
    void folderMissingSuggestsNearestSurvivingAncestorAndTargetFolderIdResolvesIt() throws Exception {
        KeycloakUser admin = new KeycloakUser("admin-1", "admin-1", List.of());
        WarehouseFolder child = warehouseService.createFolder(admin, "child-" + UUID.randomUUID(), rootFolderId);
        WarehouseFolder grandchild =
                warehouseService.createFolder(admin, "grandchild-" + UUID.randomUUID(), child.getId());

        Document document = saveDocument("officer-a", grandchild.getId(), "f.txt");
        trashViaHttp("officer-a", document.getId());

        warehouseService.deleteFolder(grandchild.getId()); // §4.4 hook: snapshot before delete

        mockMvc.perform(get("/trash/" + document.getId()).with(officer("officer-a")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.folderMissing").value(true))
                .andExpect(jsonPath("$.suggestedFolderId").value(child.getId().toString()));

        mockMvc.perform(post("/trash/" + document.getId() + "/restore")
                        .with(officer("officer-a"))
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("FOLDER_MISSING"))
                .andExpect(jsonPath("$.suggestedFolderId").value(child.getId().toString()));

        mockMvc.perform(post("/trash/" + document.getId() + "/restore")
                        .with(officer("officer-a"))
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("targetFolderId", child.getId()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.restoredFolderId").value(child.getId().toString()));

        assertThat(documentRepository.findById(document.getId()).orElseThrow().getFolderId())
                .isEqualTo(child.getId());
    }

    // ---- AC-2: trùng tên tại đích ------------------------------------------

    @Test
    void nameConflictOnRestoreIsRejectedUnlessAutoRenameRequested() throws Exception {
        // SPEC-folder-upload-display-name BE-8: the conflict is judged on display_name (case-insensitive,
        // live documents only). A trashed document does not hold its name, so trash first, then rename it to
        // "dup.txt" (safe while soft-deleted) and only then create the live document that occupies the name.
        Document trashed = saveDocument("officer-a", rootFolderId, "dup-tmp.txt");
        trashViaHttp("officer-a", trashed.getId());
        trashed = documentRepository.findById(trashed.getId()).orElseThrow();
        trashed.setOriginalName("dup.txt");
        trashed.setDisplayName("dup.txt");
        documentRepository.save(trashed);

        saveDocument("officer-a", rootFolderId, "DUP.txt"); // live document holding the name (different case)

        mockMvc.perform(post("/trash/" + trashed.getId() + "/restore")
                        .with(officer("officer-a"))
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NAME_CONFLICT"))
                .andExpect(jsonPath("$.suggestedName").exists());
        // CANCEL is the same as no override: still a conflict, nothing changes
        mockMvc.perform(post("/trash/" + trashed.getId() + "/restore")
                        .with(officer("officer-a"))
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("onNameConflict", "CANCEL"))))
                .andExpect(status().isConflict());
        assertThat(documentRepository.findById(trashed.getId()).orElseThrow().getDeletedAt()).isNotNull();

        mockMvc.perform(post("/trash/" + trashed.getId() + "/restore")
                        .with(officer("officer-a"))
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("onNameConflict", "AUTO_RENAME"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.restoredName", org.hamcrest.Matchers.matchesPattern("dup_\\d{17}-[a-z0-9]{6}\\.txt")));

        Document restored = documentRepository.findById(trashed.getId()).orElseThrow();
        assertThat(restored.getDeletedAt()).isNull();
        // "Đổi tên" rewrites BOTH names with the suffix name
        assertThat(restored.getDisplayName()).matches("dup_\\d{17}-[a-z0-9]{6}\\.txt");
        assertThat(restored.getOriginalName()).isEqualTo(restored.getDisplayName());
        // reindex event so search sees the new name
        assertThat(outboxEventRepository.findAll())
                .anySatisfy(e -> assertThat(e.getEventType()).isEqualTo("document.restored"));
    }

    @Test
    void restoreWithAFreeNameKeepsBothNamesUnchanged() throws Exception {
        Document trashed = saveDocument("officer-a", rootFolderId, "free.txt");
        trashViaHttp("officer-a", trashed.getId());

        mockMvc.perform(post("/trash/" + trashed.getId() + "/restore")
                        .with(officer("officer-a"))
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.restoredName").value("free.txt"));
        Document restored = documentRepository.findById(trashed.getId()).orElseThrow();
        assertThat(restored.getOriginalName()).isEqualTo("free.txt");
        assertThat(restored.getDisplayName()).isEqualTo("free.txt");
    }

    @Test
    void restoreIsJudgedOnRelativePathToo() throws Exception {
        Document trashed = saveDocument("officer-a", rootFolderId, "same.txt");
        trashed.setRelativePath("dirA");
        documentRepository.save(trashed);
        trashViaHttp("officer-a", trashed.getId());
        Document other = saveDocument("officer-a", rootFolderId, "same.txt"); // same name, path "" -> no clash
        assertThat(other.getRelativePath()).isEmpty();

        mockMvc.perform(post("/trash/" + trashed.getId() + "/restore")
                        .with(officer("officer-a"))
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isOk());
    }

    // ---- AC-7/AC-8: đang purging / đã quá hạn (đã purge) ------------------------

    @Test
    void restoreRejectedWithPurgingThenGoneAsPurgeStateAdvances() throws Exception {
        Document trashed = saveDocument("officer-a", rootFolderId, "f.txt");
        trashViaHttp("officer-a", trashed.getId());

        Document purging = documentRepository.findById(trashed.getId()).orElseThrow();
        purging.setPurgeState(PurgeState.PURGING);
        documentRepository.save(purging);

        mockMvc.perform(post("/trash/" + trashed.getId() + "/restore")
                        .with(officer("officer-a"))
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PURGING"));

        Document purged = documentRepository.findById(trashed.getId()).orElseThrow();
        purged.setPurgeState(PurgeState.PURGED);
        documentRepository.save(purged);

        mockMvc.perform(post("/trash/" + trashed.getId() + "/restore")
                        .with(officer("officer-a"))
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("GONE"));
    }

    @Test
    void restoreRejectedWithGoneOncePurgeAfterHasElapsed() throws Exception {
        Document trashed = saveDocument("officer-a", rootFolderId, "f.txt");
        trashViaHttp("officer-a", trashed.getId());

        Document expired = documentRepository.findById(trashed.getId()).orElseThrow();
        expired.setPurgeAfter(OffsetDateTime.now().minusMinutes(1));
        documentRepository.save(expired);

        mockMvc.perform(post("/trash/" + trashed.getId() + "/restore")
                        .with(officer("officer-a"))
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("GONE"));
    }

    // ---- targetFolderId không tồn tại (400) ------------------------------

    @Test
    void restoreRejectsWithBadRequestWhenTargetFolderIdDoesNotExist() throws Exception {
        Document trashed = saveDocument("officer-a", rootFolderId, "f.txt");
        trashViaHttp("officer-a", trashed.getId());

        mockMvc.perform(post("/trash/" + trashed.getId() + "/restore")
                        .with(officer("officer-a"))
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("targetFolderId", UUID.randomUUID()))))
                .andExpect(status().isBadRequest());
    }
}
