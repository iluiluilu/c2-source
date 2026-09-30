package com.aismartstore.backend.groups;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aismartstore.backend.audit.AuditLogRepository;
import com.aismartstore.backend.digitization.DigitizationSourceRepository;
import com.aismartstore.backend.digitization.SourceSeparationJobRepository;
import com.aismartstore.backend.documents.Document;
import com.aismartstore.backend.documents.DocumentRepository;
import com.aismartstore.backend.documents.DocumentStatus;
import com.aismartstore.backend.storage.ObjectStorageService;
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
 * rbac-group-scope.md §5 ("Integration test"), Story 1 AC-1-AC-3 and Story 3
 * AC-1-AC-4: end-to-end MockMvc coverage of {@link BusinessDataScope} applied
 * to the documents/notes read endpoints, group creation/sharing, and the
 * {@code access.denied} audit trail.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Testcontainers
class DocumentAccessScopeIT {

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

    // digitization-pipeline-01-source-dispatch.md V18: FK-dependent tables must
    // be cleared before documentRepository.deleteAll() (see PersonalControllerIT).
    @Autowired
    private DigitizationSourceRepository digitizationSourceRepository;

    @Autowired
    private SourceSeparationJobRepository sourceSeparationJobRepository;

    @Autowired
    private GroupRepository groupRepository;

    @Autowired
    private GroupMemberRepository groupMemberRepository;

    @Autowired
    private DocumentGroupShareRepository documentGroupShareRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private WarehouseFolderRepository warehouseFolderRepository;

    private UUID rootFolderId;

    /** {@code audit_log} is append-only (DB trigger rejects DELETE) - each test uses its own random subs instead. */
    @BeforeEach
    void resetState() {
        documentGroupShareRepository.deleteAll();
        groupMemberRepository.deleteAll();
        groupRepository.deleteAll();
        digitizationSourceRepository.deleteAll();
        sourceSeparationJobRepository.deleteAll();
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
                .authorities(com.aismartstore.backend.testsupport.RolePermissionFixtures.authoritiesFor(springRole));
    }

    private static JwtRequestPostProcessor canBo(String sub) {
        return asRole(sub, "can-bo", "CAN_BO");
    }

    private static JwtRequestPostProcessor lanhDao(String sub) {
        return asRole(sub, "lanh-dao", "LANH_DAO");
    }

    private static JwtRequestPostProcessor quanTri(String sub) {
        return asRole(sub, "quan-tri", "QUAN_TRI");
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

    // ---- Story 3 AC-1/AC-2/AC-3: R1 chỉ thấy tài liệu của mình + được chia sẻ ----

    @Test
    void canBoOnlySeesOwnDocumentsAndDocumentsSharedToTheirGroup() throws Exception {
        String me = "can-bo-" + UUID.randomUUID();
        String someoneElse = "other-" + UUID.randomUUID();

        Document mine = saveDocument(me);
        Document sharedToMe = saveDocument(someoneElse);
        Document notSharedToMe = saveDocument(someoneElse);

        Group group = new Group();
        group.setName("Nhóm chia sẻ");
        group.setOwnerSub(someoneElse);
        group.setCreatedAt(OffsetDateTime.now());
        group.setUpdatedAt(OffsetDateTime.now());
        groupRepository.save(group);

        GroupMember membership = new GroupMember();
        membership.setGroupId(group.getId());
        membership.setUserSub(me);
        membership.setRoleInGroup(GroupRole.MEMBER);
        membership.setJoinedAt(OffsetDateTime.now());
        groupMemberRepository.save(membership);

        DocumentGroupShare share = new DocumentGroupShare();
        share.setDocumentId(sharedToMe.getId());
        share.setGroupId(group.getId());
        share.setSharedBySub(someoneElse);
        share.setSharedAt(OffsetDateTime.now());
        documentGroupShareRepository.save(share);

        mockMvc.perform(get("/documents").with(canBo(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));

        mockMvc.perform(get("/documents/" + mine.getId()).with(canBo(me))).andExpect(status().isOk());
        mockMvc.perform(get("/documents/" + sharedToMe.getId()).with(canBo(me))).andExpect(status().isOk());

        // Story 3 AC-4: exists but out of scope -> 404 (not a 403 that would leak existence), + audit.
        mockMvc.perform(get("/documents/" + notSharedToMe.getId()).with(canBo(me)))
                .andExpect(status().isNotFound());

        List<com.aismartstore.backend.audit.AuditLog> deniedRows =
                awaitAuditLogs(row -> "access.denied".equals(row.getAction()) && me.equals(row.getActorSub()));
        assertThat(deniedRows).anySatisfy(
                row -> assertThat(row.getResourceId()).isEqualTo(notSharedToMe.getId().toString()));
    }

    // ---- Story 1 AC-1: R2 áp dụng đúng scope đọc tương tự R1 ----------------

    @Test
    void lanhDaoAppliesTheSameReadScopeAsCanBo() throws Exception {
        String me = "lanh-dao-" + UUID.randomUUID();
        Document mine = saveDocument(me);
        saveDocument("someone-else-" + UUID.randomUUID());

        mockMvc.perform(get("/documents").with(lanhDao(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(mine.getId().toString()));
    }

    // ---- Story 1 AC-2: R3 không có route nghiệp vụ nào cấp quyền ------------

    @Test
    void quanTriGetsForbiddenOnEveryDocumentReadRoute() throws Exception {
        Document doc = saveDocument("someone-" + UUID.randomUUID());
        String admin = "admin-" + UUID.randomUUID();

        mockMvc.perform(get("/documents").with(quanTri(admin))).andExpect(status().isForbidden());
        mockMvc.perform(get("/documents/" + doc.getId()).with(quanTri(admin))).andExpect(status().isForbidden());
        mockMvc.perform(get("/documents/" + doc.getId() + "/content").with(quanTri(admin)))
                .andExpect(status().isForbidden());
    }

    // ---- Story 1 AC-3: truy cập sai vai trò -> 403 + audit access.denied ----

    @Test
    void roleMismatchRecordsAnAccessDeniedAuditEntry() throws Exception {
        String admin = "admin-" + UUID.randomUUID();

        mockMvc.perform(get("/documents").with(quanTri(admin))).andExpect(status().isForbidden());

        List<com.aismartstore.backend.audit.AuditLog> deniedRows =
                awaitAuditLogs(row -> "access.denied".equals(row.getAction()) && admin.equals(row.getActorSub()));
        assertThat(deniedRows).anySatisfy(row -> assertThat(row.getPath()).isEqualTo("/documents"));
    }

    // ---- Story 3: chia sẻ tài liệu -> thành viên mới thấy ngay lập tức -----

    @Test
    void creatingAGroupAddingAMemberAndSharingADocumentMakesItImmediatelyVisible() throws Exception {
        String owner = "owner-" + UUID.randomUUID();
        String newMember = "new-member-" + UUID.randomUUID();
        Document doc = saveDocument(owner);

        String groupJson = mockMvc.perform(post("/groups")
                        .with(canBo(owner))
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("name", "Nhóm A"))))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String groupId = (String) asMap(groupJson).get("id");

        mockMvc.perform(post("/groups/" + groupId + "/members")
                        .with(canBo(owner))
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(
                                Map.of("userSub", newMember, "roleInGroup", "member"))))
                .andExpect(status().isNoContent());

        // Not visible before the share.
        mockMvc.perform(get("/documents/" + doc.getId()).with(canBo(newMember))).andExpect(status().isNotFound());

        mockMvc.perform(post("/documents/" + doc.getId() + "/shares")
                        .with(canBo(owner))
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("groupId", groupId))))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/documents/" + doc.getId()).with(canBo(newMember))).andExpect(status().isOk());

        // Story 5 AC-2: the share row carries no content/path copy.
        mockMvc.perform(get("/documents/" + doc.getId() + "/shares").with(canBo(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].groupId").value(groupId))
                .andExpect(jsonPath("$[0].groupName").value("Nhóm A"));
    }

    // ---- Case biên: mất quyền ngay sau khi bị xoá khỏi nhóm (không cache) --

    @Test
    void removingAMemberRevokesAccessOnTheVeryNextRead() throws Exception {
        String owner = "owner-" + UUID.randomUUID();
        String member = "member-" + UUID.randomUUID();
        Document doc = saveDocument(owner);

        Group group = new Group();
        group.setName("Nhóm B");
        group.setOwnerSub(owner);
        group.setCreatedAt(OffsetDateTime.now());
        group.setUpdatedAt(OffsetDateTime.now());
        groupRepository.save(group);

        GroupMember ownerMembership = new GroupMember();
        ownerMembership.setGroupId(group.getId());
        ownerMembership.setUserSub(owner);
        ownerMembership.setRoleInGroup(GroupRole.OWNER);
        ownerMembership.setJoinedAt(OffsetDateTime.now());
        groupMemberRepository.save(ownerMembership);

        GroupMember memberMembership = new GroupMember();
        memberMembership.setGroupId(group.getId());
        memberMembership.setUserSub(member);
        memberMembership.setRoleInGroup(GroupRole.MEMBER);
        memberMembership.setJoinedAt(OffsetDateTime.now());
        groupMemberRepository.save(memberMembership);

        DocumentGroupShare share = new DocumentGroupShare();
        share.setDocumentId(doc.getId());
        share.setGroupId(group.getId());
        share.setSharedBySub(owner);
        share.setSharedAt(OffsetDateTime.now());
        documentGroupShareRepository.save(share);

        mockMvc.perform(get("/documents/" + doc.getId()).with(canBo(member))).andExpect(status().isOk());

        mockMvc.perform(delete("/groups/" + group.getId() + "/members/" + member).with(canBo(owner)))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/documents/" + doc.getId()).with(canBo(member))).andExpect(status().isNotFound());
    }

    // ---- Notes are scoped through their parent document ---------------------

    @Test
    void notesListIsScopedThroughItsParentDocument() throws Exception {
        String owner = "owner-" + UUID.randomUUID();
        String stranger = "stranger-" + UUID.randomUUID();
        Document doc = saveDocument(owner);

        mockMvc.perform(get("/documents/" + doc.getId() + "/notes").with(canBo(owner)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/documents/" + doc.getId() + "/notes").with(canBo(stranger)))
                .andExpect(status().isNotFound());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(String json) throws Exception {
        return objectMapper.readValue(json, LinkedHashMap.class);
    }

    /** Polls up to ~2s for {@code @Async} audit writes to land (see AuditService.recordAsync). */
    private List<com.aismartstore.backend.audit.AuditLog> awaitAuditLogs(
            java.util.function.Predicate<com.aismartstore.backend.audit.AuditLog> matcher) throws InterruptedException {
        for (int i = 0; i < 40; i++) {
            List<com.aismartstore.backend.audit.AuditLog> matches =
                    auditLogRepository.findAll().stream().filter(matcher).toList();
            if (!matches.isEmpty()) {
                return matches;
            }
            Thread.sleep(50);
        }
        return List.of();
    }
}
