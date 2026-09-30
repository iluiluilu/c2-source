package com.aismartstore.backend.groups;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aismartstore.backend.accounts.KeycloakAdminService;
import com.aismartstore.backend.digitization.DigitizationSourceRepository;
import com.aismartstore.backend.digitization.SourceSeparationJobRepository;
import com.aismartstore.backend.digitization.results.DigitizationRunRepository;
import com.aismartstore.backend.digitization.results.DocumentDigitizationStatusRepository;
import com.aismartstore.backend.documents.Document;
import com.aismartstore.backend.documents.DocumentRepository;
import com.aismartstore.backend.documents.DocumentStatus;
import com.aismartstore.backend.storage.ObjectStorageService;
import com.aismartstore.backend.testsupport.RolePermissionFixtures;
import com.aismartstore.backend.warehouse.WarehouseFolder;
import com.aismartstore.backend.warehouse.WarehouseFolderRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
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
 * group-management-sharing-01-group-lifecycle.md §5 ("Integration test"):
 * end-to-end MockMvc coverage of Story 1-4 and the Story 4/Task 6 security
 * fix (a soft-deleted group must stop granting document visibility). Story 6
 * (limit checks) is covered at the unit level in {@link GroupsServiceTest} -
 * seeding 1.000/200 rows through a real Postgres here would make this class
 * slow without adding meaningfully more confidence over the unit tests, which
 * already exercise the exact boundary (cap - 1 / cap / cap + 1) via mocks.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Testcontainers
class GroupLifecycleIT {

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
    private KeycloakAdminService keycloakAdminService;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private DigitizationSourceRepository digitizationSourceRepository;

    // digitization-results-store.md V25: digitization_run FK-references
    // digitization_source(id) with no cascade delete - must be cleared before
    // digitizationSourceRepository.deleteAll() below or the FK constraint fails.
    @Autowired
    private DigitizationRunRepository digitizationRunRepository;

    // digitization-results-store.md V25/V26: document_digitization_status
    // FK-references document(id) with no cascade delete - must be cleared
    // before documentRepository.deleteAll() below or the FK constraint fails.
    @Autowired
    private DocumentDigitizationStatusRepository documentDigitizationStatusRepository;

    @Autowired
    private SourceSeparationJobRepository sourceSeparationJobRepository;

    @Autowired
    private GroupRepository groupRepository;

    @Autowired
    private GroupMemberRepository groupMemberRepository;

    @Autowired
    private DocumentGroupShareRepository documentGroupShareRepository;

    @Autowired
    private WarehouseFolderRepository warehouseFolderRepository;

    private UUID rootFolderId;

    @BeforeEach
    void resetState() {
        // GET /groups/{id}/members now joins Keycloak for username/displayName
        // (GroupsService#toMemberView) - stub a deterministic response keyed
        // by the sub used as the JWT subject in this test's fixtures, rather
        // than leaving it unmocked (which would hit a real Keycloak).
        lenient()
                .when(keycloakAdminService.getUser(anyString()))
                .thenAnswer(inv -> {
                    String sub = inv.getArgument(0);
                    return new LinkedHashMap<>(Map.of("username", sub, "firstName", "", "lastName", ""));
                });

        documentGroupShareRepository.deleteAll();
        groupMemberRepository.deleteAll();
        groupRepository.deleteAll();
        digitizationRunRepository.deleteAll();
        digitizationSourceRepository.deleteAll();
        sourceSeparationJobRepository.deleteAll();
        documentDigitizationStatusRepository.deleteAll();
        documentRepository.deleteAll();

        WarehouseFolder folder = new WarehouseFolder();
        folder.setName("Root " + UUID.randomUUID());
        folder.setPathIds(new UUID[0]);
        folder.setDepth((short) 1);
        folder.setCreatedAt(OffsetDateTime.now());
        folder.setUpdatedAt(OffsetDateTime.now());
        rootFolderId = warehouseFolderRepository.save(folder).getId();
    }

    private static JwtRequestPostProcessor asRole(String sub, String realmRole, String springRole) {
        return jwt().jwt(builder -> builder.subject(sub)
                        .claim("preferred_username", sub)
                        .claim("realm_access", Map.of("roles", List.of(realmRole))))
                .authorities(RolePermissionFixtures.authoritiesFor(springRole));
    }

    private static JwtRequestPostProcessor canBo(String sub) {
        return asRole(sub, "can-bo", "CAN_BO");
    }

    private String createGroup(String ownerSub, String name) throws Exception {
        String json = mockMvc.perform(post("/groups")
                        .with(canBo(ownerSub))
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("name", name))))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return (String) asMap(json).get("id");
    }

    private void addMember(String ownerSub, String groupId, String memberSub, String role) throws Exception {
        mockMvc.perform(post("/groups/" + groupId + "/members")
                        .with(canBo(ownerSub))
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("userSub", memberSub, "roleInGroup", role))))
                .andExpect(status().isNoContent());
    }

    private Document saveDocument(String uploaderSub) {
        Document document = new Document();
        document.setOriginalName("file-" + UUID.randomUUID() + ".txt");
        document.setMimeType("text/plain");
        document.setSize(10);
        document.setSha256("x".repeat(64));
        document.setStorageKey("documents/" + UUID.randomUUID());
        document.setRelativePath("");
        document.setFolderId(rootFolderId);
        document.setUploaderSub(uploaderSub);
        document.setUploaderUsername(uploaderSub);
        document.setStatus(DocumentStatus.CLEAN);
        document.setCreatedAt(OffsetDateTime.now());
        return documentRepository.save(document);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(String json) throws Exception {
        return objectMapper.readValue(json, LinkedHashMap.class);
    }

    // ---- Story 1: cập nhật nhóm --------------------------------------------

    @Test
    void ownerCanRenameAGroup() throws Exception {
        String owner = "owner-" + UUID.randomUUID();
        String groupId = createGroup(owner, "Nhóm A " + UUID.randomUUID());

        mockMvc.perform(patch("/groups/" + groupId)
                        .with(canBo(owner))
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("name", "Nhóm A đổi tên"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Nhóm A đổi tên"));
    }

    @Test
    void aPlainMemberCannotRenameAGroup() throws Exception {
        String owner = "owner-" + UUID.randomUUID();
        String member = "member-" + UUID.randomUUID();
        String groupId = createGroup(owner, "Nhóm B " + UUID.randomUUID());
        addMember(owner, groupId, member, GroupRole.MEMBER);

        mockMvc.perform(patch("/groups/" + groupId)
                        .with(canBo(member))
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("name", "Hijack"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void creatingAGroupWithAnExistingActiveNameConflicts() throws Exception {
        String owner = "owner-" + UUID.randomUUID();
        String suffix = UUID.randomUUID().toString();
        String name = "Nhóm E " + suffix;
        createGroup(owner, name);

        // Case-insensitive collision, same as the rename case below - must be a clean 409 the
        // caller can act on (pick another name), not an opaque 500 from an uncaught
        // DataIntegrityViolationException surfacing after this try/catch has already returned
        // (id is a Hibernate-in-memory-generated UUID, so save() alone does not force the flush).
        mockMvc.perform(post("/groups")
                        .with(canBo(owner))
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("name", name.toUpperCase()))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString(name.toUpperCase())));
    }

    @Test
    void renamingToAnExistingActiveGroupNameConflicts() throws Exception {
        String owner = "owner-" + UUID.randomUUID();
        String suffix = UUID.randomUUID().toString();
        createGroup(owner, "Nhóm C " + suffix);
        String otherGroupId = createGroup(owner, "Nhóm D " + suffix);

        // Case-insensitive collision.
        mockMvc.perform(patch("/groups/" + otherGroupId)
                        .with(canBo(owner))
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("name", "NHÓM C " + suffix))))
                .andExpect(status().isConflict());
    }

    @Test
    void renamingToTheNameOfADeletedGroupIsAllowed() throws Exception {
        String owner = "owner-" + UUID.randomUUID();
        String suffix = UUID.randomUUID().toString();
        String deletedGroupId = createGroup(owner, "Nhóm E " + suffix);
        String otherGroupId = createGroup(owner, "Nhóm F " + suffix);

        mockMvc.perform(delete("/groups/" + deletedGroupId).with(canBo(owner))).andExpect(status().isNoContent());

        mockMvc.perform(patch("/groups/" + otherGroupId)
                        .with(canBo(owner))
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("name", "Nhóm E " + suffix))))
                .andExpect(status().isOk());
    }

    // ---- Story 2: đổi vai trò thành viên ------------------------------------

    @Test
    void demotingTheOnlyOwnerIsRejected() throws Exception {
        String owner = "owner-" + UUID.randomUUID();
        String groupId = createGroup(owner, "Nhóm G " + UUID.randomUUID());

        mockMvc.perform(patch("/groups/" + groupId + "/members/" + owner)
                        .with(canBo(owner))
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("roleInGroup", "member"))))
                .andExpect(status().isConflict());
    }

    @Test
    void demotingOneOfTwoOwnersSucceedsAndTheGroupKeepsAnOwner() throws Exception {
        String owner = "owner-" + UUID.randomUUID();
        String secondOwner = "owner2-" + UUID.randomUUID();
        String groupId = createGroup(owner, "Nhóm H " + UUID.randomUUID());
        addMember(owner, groupId, secondOwner, GroupRole.OWNER);

        mockMvc.perform(patch("/groups/" + groupId + "/members/" + secondOwner)
                        .with(canBo(owner))
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("roleInGroup", "member"))))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/groups/" + groupId + "/members").with(canBo(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.userSub == '" + owner + "')].roleInGroup").value("owner"));
    }

    // ---- Story 3: rời nhóm --------------------------------------------------

    @Test
    void aPlainMemberCanLeaveAtAnyTime() throws Exception {
        String owner = "owner-" + UUID.randomUUID();
        String member = "member-" + UUID.randomUUID();
        String groupId = createGroup(owner, "Nhóm I " + UUID.randomUUID());
        addMember(owner, groupId, member, GroupRole.MEMBER);

        mockMvc.perform(post("/groups/" + groupId + "/leave").with(canBo(member))).andExpect(status().isNoContent());
    }

    @Test
    void theOnlyOwnerCannotLeave() throws Exception {
        String owner = "owner-" + UUID.randomUUID();
        String groupId = createGroup(owner, "Nhóm J " + UUID.randomUUID());

        mockMvc.perform(post("/groups/" + groupId + "/leave").with(canBo(owner))).andExpect(status().isConflict());
    }

    @Test
    void anOwnerCanLeaveWhileAnotherOwnerRemainsAndTheirPastSharesStayVisible() throws Exception {
        String owner = "owner-" + UUID.randomUUID();
        String secondOwner = "owner2-" + UUID.randomUUID();
        String remainingMember = "member-" + UUID.randomUUID();
        String groupId = createGroup(owner, "Nhóm K " + UUID.randomUUID());
        addMember(owner, groupId, secondOwner, GroupRole.OWNER);
        addMember(owner, groupId, remainingMember, GroupRole.MEMBER);

        Document doc = saveDocument(owner);
        mockMvc.perform(post("/documents/" + doc.getId() + "/shares")
                        .with(canBo(owner))
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("groupId", groupId))))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/groups/" + groupId + "/leave").with(canBo(owner))).andExpect(status().isNoContent());

        // AC-3: the share created by the now-departed owner is untouched.
        mockMvc.perform(get("/documents/" + doc.getId()).with(canBo(remainingMember))).andExpect(status().isOk());
    }

    // ---- Story 4: xoá nhóm (soft delete) + Task 6 security fix -------------

    @Test
    void ownerCanDeleteAGroupAndItDisappearsFromEveryMembersList() throws Exception {
        String owner = "owner-" + UUID.randomUUID();
        String otherMember = "member-" + UUID.randomUUID();
        String groupId = createGroup(owner, "Nhóm L " + UUID.randomUUID());
        addMember(owner, groupId, otherMember, GroupRole.MEMBER);

        mockMvc.perform(delete("/groups/" + groupId).with(canBo(owner))).andExpect(status().isNoContent());

        mockMvc.perform(get("/groups").with(canBo(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '" + groupId + "')]").isEmpty());
        mockMvc.perform(get("/groups").with(canBo(otherMember)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '" + groupId + "')]").isEmpty());
    }

    /**
     * Task 6 ("rủi ro bảo mật Cao nhất"): before the fix, {@link
     * BusinessDataScope}/{@link GroupMemberRepository#findGroupIdsByUserSub}
     * still counted a soft-deleted group's membership, so a document shared
     * only through that group kept leaking to former group members after
     * deletion. This is the regression test for that exact scenario.
     */
    @Test
    void deletingAGroupImmediatelyRevokesAccessToDocumentsOnlySharedThroughIt() throws Exception {
        String owner = "owner-" + UUID.randomUUID();
        String otherMember = "member-" + UUID.randomUUID();
        String groupId = createGroup(owner, "Nhóm M " + UUID.randomUUID());
        addMember(owner, groupId, otherMember, GroupRole.MEMBER);

        Document doc = saveDocument(owner);
        mockMvc.perform(post("/documents/" + doc.getId() + "/shares")
                        .with(canBo(owner))
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("groupId", groupId))))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/documents/" + doc.getId()).with(canBo(otherMember))).andExpect(status().isOk());

        mockMvc.perform(delete("/groups/" + groupId).with(canBo(owner))).andExpect(status().isNoContent());

        mockMvc.perform(get("/documents/" + doc.getId()).with(canBo(otherMember))).andExpect(status().isNotFound());
        mockMvc.perform(get("/documents").with(canBo(otherMember)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '" + doc.getId() + "')]").isEmpty());
    }

    // ---- Case biên: thao tác trên nhóm đã xoá -> 404 ------------------------

    @Test
    void patchDeleteAndLeaveOnADeletedGroupAllReturn404() throws Exception {
        String owner = "owner-" + UUID.randomUUID();
        String groupId = createGroup(owner, "Nhóm N " + UUID.randomUUID());
        mockMvc.perform(delete("/groups/" + groupId).with(canBo(owner))).andExpect(status().isNoContent());

        mockMvc.perform(patch("/groups/" + groupId)
                        .with(canBo(owner))
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("name", "x"))))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/groups/" + groupId).with(canBo(owner))).andExpect(status().isNotFound());
        mockMvc.perform(post("/groups/" + groupId + "/leave").with(canBo(owner))).andExpect(status().isNotFound());
    }

    // ---- Hồi quy: removeMember trên thành viên thường vẫn hoạt động --------

    @Test
    void removingAnOrdinaryMemberStillWorksAfterTheLastOwnerGuardWasAdded() throws Exception {
        String owner = "owner-" + UUID.randomUUID();
        String member = "member-" + UUID.randomUUID();
        String groupId = createGroup(owner, "Nhóm O " + UUID.randomUUID());
        addMember(owner, groupId, member, GroupRole.MEMBER);

        mockMvc.perform(delete("/groups/" + groupId + "/members/" + member).with(canBo(owner)))
                .andExpect(status().isNoContent());
    }

    @Test
    void removingTheLastOwnerThroughRemoveMemberIsRejected() throws Exception {
        String owner = "owner-" + UUID.randomUUID();
        String groupId = createGroup(owner, "Nhóm P " + UUID.randomUUID());

        mockMvc.perform(delete("/groups/" + groupId + "/members/" + owner).with(canBo(owner)))
                .andExpect(status().isConflict());
    }
}
