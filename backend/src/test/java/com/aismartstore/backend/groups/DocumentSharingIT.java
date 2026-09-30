package com.aismartstore.backend.groups;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.reset;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aismartstore.backend.accounts.KeycloakAdminService;
import com.aismartstore.backend.digitization.DigitizationSourceRepository;
import com.aismartstore.backend.digitization.SourceSeparationJobRepository;
import com.aismartstore.backend.documents.Document;
import com.aismartstore.backend.documents.DocumentRepository;
import com.aismartstore.backend.documents.DocumentStatus;
import com.aismartstore.backend.notifications.Notification;
import com.aismartstore.backend.notifications.NotificationRepository;
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
import java.util.function.Predicate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
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
 * group-management-sharing-02-document-sharing.md §5 ("Integration test"):
 * end-to-end MockMvc coverage of Story 1-4 (bulk share, group->documents,
 * the unshare permission fix, and notification fan-out). {@code
 * GroupLifecycleIT}/{@code DocumentAccessScopeIT} cover Story 1-4 of the
 * -01 spec and are left untouched.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Testcontainers
class DocumentSharingIT {

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

    /**
     * {@code @SpyBean} (not plain {@code @Autowired}) so {@link
     * #aNotificationRepositoryFailureNeverBreaksTheShareRequest} can stub a
     * failure on the real bean without a full mock (which would break every
     * other test's {@link #awaitNotifications} assertions against real rows).
     */
    @SpyBean
    private NotificationRepository notificationRepository;

    private UUID rootFolderId;

    @BeforeEach
    void resetState() {
        // GET /groups/{id}/documents now joins Keycloak for uploader/sharedBy
        // username+displayName (GroupsService#listGroupDocuments) - same
        // deterministic stub as GroupLifecycleIT's member list, keyed by the
        // sub used as the JWT subject in this test's fixtures.
        lenient()
                .when(keycloakAdminService.getUser(anyString()))
                .thenAnswer(inv -> {
                    String sub = inv.getArgument(0);
                    return new LinkedHashMap<>(Map.of("username", sub, "firstName", "", "lastName", ""));
                });

        // Undo any stubbing a previous test in this class left behind (spy is a shared, cached Spring bean).
        reset(notificationRepository);
        notificationRepository.deleteAll();
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
        return saveDocument(uploaderSub, "file-" + UUID.randomUUID() + ".txt");
    }

    private Document saveDocument(String uploaderSub, String originalName) {
        Document document = new Document();
        document.setOriginalName(originalName);
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

    /** Polls up to ~2s for {@code @Async} writes to land (same pattern as {@code DocumentAccessScopeIT#awaitAuditLogs}). */
    private List<Notification> awaitNotifications(Predicate<Notification> matcher) throws InterruptedException {
        for (int i = 0; i < 40; i++) {
            List<Notification> matches = notificationRepository.findAll().stream().filter(matcher).toList();
            if (!matches.isEmpty()) {
                return matches;
            }
            Thread.sleep(50);
        }
        return List.of();
    }

    // ---- Story 1: chia sẻ hàng loạt -----------------------------------------

    @Test
    void bulkShareHandlesAMixOfValidInvalidOutOfScopeAndAlreadySharedDocuments() throws Exception {
        String owner = "owner-" + UUID.randomUUID();
        String stranger = "stranger-" + UUID.randomUUID();
        String groupId = createGroup(owner, "Nhóm bulk " + UUID.randomUUID());

        Document valid = saveDocument(owner);
        Document outOfScope = saveDocument(stranger);
        Document alreadyShared = saveDocument(owner);
        mockMvc.perform(post("/documents/" + alreadyShared.getId() + "/shares")
                        .with(canBo(owner))
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("groupId", groupId))))
                .andExpect(status().isNoContent());

        String body = objectMapper.writeValueAsString(Map.of(
                "groupId", groupId,
                "documentIds", List.of(valid.getId(), outOfScope.getId(), alreadyShared.getId())));

        mockMvc.perform(post("/documents/shares").with(canBo(owner)).contentType("application/json").content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].documentId").value(valid.getId().toString()))
                .andExpect(jsonPath("$.results[0].status").value("shared"))
                .andExpect(jsonPath("$.results[1].documentId").value(outOfScope.getId().toString()))
                .andExpect(jsonPath("$.results[1].status").value("rejected"))
                .andExpect(jsonPath("$.results[1].reason").value("not_visible"))
                .andExpect(jsonPath("$.results[2].documentId").value(alreadyShared.getId().toString()))
                .andExpect(jsonPath("$.results[2].status").value("already_shared"));

        assertThat(documentGroupShareRepository.existsByDocumentIdAndGroupId(valid.getId(), UUID.fromString(groupId)))
                .isTrue();
    }

    @Test
    void bulkShareRejectsAnEmptyDocumentList() throws Exception {
        String owner = "owner-" + UUID.randomUUID();
        String groupId = createGroup(owner, "Nhóm rỗng " + UUID.randomUUID());

        mockMvc.perform(post("/documents/shares")
                        .with(canBo(owner))
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("groupId", groupId, "documentIds", List.of()))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void bulkShareRejects101Documents() throws Exception {
        String owner = "owner-" + UUID.randomUUID();
        String groupId = createGroup(owner, "Nhóm 101 " + UUID.randomUUID());
        List<UUID> tooMany = java.util.stream.Stream.generate(UUID::randomUUID).limit(101).toList();

        mockMvc.perform(post("/documents/shares")
                        .with(canBo(owner))
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("groupId", groupId, "documentIds", tooMany))))
                .andExpect(status().isBadRequest());
    }

    // ---- Story 2: tài liệu của một nhóm --------------------------------------

    @Test
    void memberSeesGroupDocumentsButOutsidersGet403() throws Exception {
        String owner = "owner-" + UUID.randomUUID();
        String member = "member-" + UUID.randomUUID();
        String outsider = "outsider-" + UUID.randomUUID();
        String groupId = createGroup(owner, "Nhóm docs " + UUID.randomUUID());
        addMember(owner, groupId, member, GroupRole.MEMBER);

        Document doc = saveDocument(owner);
        mockMvc.perform(post("/documents/" + doc.getId() + "/shares")
                        .with(canBo(owner))
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("groupId", groupId))))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/groups/" + groupId + "/documents").with(canBo(member)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].documentId").value(doc.getId().toString()))
                .andExpect(jsonPath("$.content[0].title").value(doc.getOriginalName()))
                .andExpect(jsonPath("$.content[0].uploaderSub").value(owner))
                // group-management-sharing-02-document-sharing.md - display bug fix
                // (GroupsService#listGroupDocuments now joins Keycloak): the frontend
                // showed the raw sub before, not a name.
                .andExpect(jsonPath("$.content[0].uploaderUsername").value(owner))
                .andExpect(jsonPath("$.content[0].uploaderDisplayName").value(owner))
                .andExpect(jsonPath("$.content[0].sharedBySub").value(owner))
                .andExpect(jsonPath("$.content[0].sharedByUsername").value(owner))
                .andExpect(jsonPath("$.content[0].sharedByDisplayName").value(owner));

        mockMvc.perform(get("/groups/" + groupId + "/documents").with(canBo(outsider)))
                .andExpect(status().isForbidden());
    }

    // ---- group detail "Thêm tài liệu" picker: GET /documents?q= ------------

    @Test
    void documentsListFiltersByNameCaseInsensitively() throws Exception {
        String owner = "owner-" + UUID.randomUUID();
        Document target = saveDocument(owner, "Ke hoach quy 2.pdf");
        saveDocument(owner, "Bao cao thang 1.docx");

        mockMvc.perform(get("/documents").param("q", "KE HOACH").with(canBo(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(target.getId().toString()));
    }

    @Test
    void documentsListWithNoNameMatchReturnsEmpty() throws Exception {
        String owner = "owner-" + UUID.randomUUID();
        saveDocument(owner, "Ke hoach quy 2.pdf");

        mockMvc.perform(get("/documents").param("q", "khong-ton-tai").with(canBo(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void softDeletedDocumentsDisappearFromGroupDocumentsButTheShareRowStays() throws Exception {
        String owner = "owner-" + UUID.randomUUID();
        String groupId = createGroup(owner, "Nhóm trash " + UUID.randomUUID());

        Document doc = saveDocument(owner);
        mockMvc.perform(post("/documents/" + doc.getId() + "/shares")
                        .with(canBo(owner))
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("groupId", groupId))))
                .andExpect(status().isNoContent());

        doc.setDeletedAt(OffsetDateTime.now());
        documentRepository.save(doc);

        mockMvc.perform(get("/groups/" + groupId + "/documents").with(canBo(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0));

        assertThat(documentGroupShareRepository.existsByDocumentIdAndGroupId(doc.getId(), UUID.fromString(groupId)))
                .isTrue();
    }

    // ---- Story 3: sửa quyền gỡ chia sẻ ---------------------------------------

    @Test
    void groupOwnerSharerAndUploaderCanUnshareButAPlainMemberCannot() throws Exception {
        String owner = "owner-" + UUID.randomUUID();
        String sharer = "sharer-" + UUID.randomUUID();
        String uploader = "uploader-" + UUID.randomUUID();
        String plainMember = "member-" + UUID.randomUUID();
        String groupId = createGroup(owner, "Nhóm unshare " + UUID.randomUUID());
        addMember(owner, groupId, sharer, GroupRole.MEMBER);
        addMember(owner, groupId, uploader, GroupRole.MEMBER);
        addMember(owner, groupId, plainMember, GroupRole.MEMBER);

        // Case 1: group owner unshares (created by someone else). sharer must
        // be able to see doc1 to share it in the first place (shareDocument
        // requires documentScopeGuard visibility) - simplest legitimate way
        // is for sharer to be doc1's uploader.
        Document doc1 = saveDocument(sharer);
        mockMvc.perform(post("/documents/" + doc1.getId() + "/shares")
                        .with(canBo(sharer))
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("groupId", groupId))))
                .andExpect(status().isNoContent());
        mockMvc.perform(delete("/documents/" + doc1.getId() + "/shares/" + groupId).with(canBo(owner)))
                .andExpect(status().isNoContent());

        // Case 2: the sharer unshares their own share (not group owner, not
        // uploader). Same visibility chicken-and-egg as Case 3 - sharer is
        // deliberately kept distinct from doc2's uploader here, so the share
        // row is inserted directly rather than via POST /shares.
        Document doc2 = saveDocument("uploader-of-doc2-" + UUID.randomUUID());
        DocumentGroupShare shareBySharer = new DocumentGroupShare();
        shareBySharer.setDocumentId(doc2.getId());
        shareBySharer.setGroupId(UUID.fromString(groupId));
        shareBySharer.setSharedBySub(sharer);
        shareBySharer.setSharedAt(OffsetDateTime.now());
        documentGroupShareRepository.save(shareBySharer);
        mockMvc.perform(delete("/documents/" + doc2.getId() + "/shares/" + groupId).with(canBo(sharer)))
                .andExpect(status().isNoContent());

        // Case 3: the uploader unshares a share created by someone else. Not
        // going through POST /shares to set this up: shareDocument requires
        // the caller to already see the document (documentScopeGuard), and
        // nobody but doc3's own uploader can see an unshared document -
        // inserting the share row directly sidesteps that chicken-and-egg
        // and still puts unshareDocument's isUploader branch under real test.
        Document doc3 = saveDocument(uploader);
        DocumentGroupShare shareByOwner = new DocumentGroupShare();
        shareByOwner.setDocumentId(doc3.getId());
        shareByOwner.setGroupId(UUID.fromString(groupId));
        shareByOwner.setSharedBySub(owner);
        shareByOwner.setSharedAt(OffsetDateTime.now());
        documentGroupShareRepository.save(shareByOwner);
        mockMvc.perform(delete("/documents/" + doc3.getId() + "/shares/" + groupId).with(canBo(uploader)))
                .andExpect(status().isNoContent());

        // Case 4: a plain member who is neither owner, sharer, nor uploader -> 403.
        // Same chicken-and-egg as Cases 2/3 - insert the share directly.
        Document doc4 = saveDocument("uploader-of-doc4-" + UUID.randomUUID());
        DocumentGroupShare shareForDoc4 = new DocumentGroupShare();
        shareForDoc4.setDocumentId(doc4.getId());
        shareForDoc4.setGroupId(UUID.fromString(groupId));
        shareForDoc4.setSharedBySub(owner);
        shareForDoc4.setSharedAt(OffsetDateTime.now());
        documentGroupShareRepository.save(shareForDoc4);
        mockMvc.perform(delete("/documents/" + doc4.getId() + "/shares/" + groupId).with(canBo(plainMember)))
                .andExpect(status().isForbidden());
        assertThat(documentGroupShareRepository.existsByDocumentIdAndGroupId(doc4.getId(), UUID.fromString(groupId)))
                .isTrue();
    }

    // ---- Story 4: thông báo ---------------------------------------------------

    @Test
    void bulkSharingToAFiveMemberGroupNotifiesEveryoneExceptTheSharerExactlyOnce() throws Exception {
        String owner = "owner-" + UUID.randomUUID();
        List<String> members = java.util.stream.Stream.generate(() -> "member-" + UUID.randomUUID())
                .limit(4)
                .toList();
        String groupId = createGroup(owner, "Nhóm 5 thành viên " + UUID.randomUUID());
        for (String member : members) {
            addMember(owner, groupId, member, GroupRole.MEMBER);
        }

        Document doc1 = saveDocument(owner);
        Document doc2 = saveDocument(owner);
        String body = objectMapper.writeValueAsString(
                Map.of("groupId", groupId, "documentIds", List.of(doc1.getId(), doc2.getId())));

        mockMvc.perform(post("/documents/shares").with(canBo(owner)).contentType("application/json").content(body))
                .andExpect(status().isOk());

        List<Notification> notifications = awaitNotifications(
                n -> "document.shared_to_group".equals(n.getType()) && members.contains(n.getRecipientSub()));
        // One notification per recipient (4), never one per document (would be 8), never one for the sharer.
        assertThat(notifications).hasSize(4);
        assertThat(notifications).noneMatch(n -> owner.equals(n.getRecipientSub()));
    }

    @Test
    void addingAMemberNotifiesThatMemberAndRemovingNotifiesThemToo() throws Exception {
        String owner = "owner-" + UUID.randomUUID();
        String member = "member-" + UUID.randomUUID();
        String groupId = createGroup(owner, "Nhóm notif " + UUID.randomUUID());

        addMember(owner, groupId, member, GroupRole.MEMBER);
        List<Notification> added = awaitNotifications(
                n -> "group.member_added".equals(n.getType()) && member.equals(n.getRecipientSub()));
        assertThat(added).hasSize(1);

        mockMvc.perform(delete("/groups/" + groupId + "/members/" + member).with(canBo(owner)))
                .andExpect(status().isNoContent());
        List<Notification> removed = awaitNotifications(
                n -> "group.member_removed".equals(n.getType()) && member.equals(n.getRecipientSub()));
        assertThat(removed).hasSize(1);
    }

    @Test
    void aNotificationRepositoryFailureNeverBreaksTheShareRequest() throws Exception {
        String owner = "owner-" + UUID.randomUUID();
        String member = "member-" + UUID.randomUUID();
        String groupId = createGroup(owner, "Nhóm lỗi thông báo " + UUID.randomUUID());
        // A member (besides the sharer) so notifyDocumentsSharedToGroup actually has a recipient
        // and calls saveAll - added before the stub below so this addMember's own "member added"
        // notification still succeeds normally.
        addMember(owner, groupId, member, GroupRole.MEMBER);

        doThrow(new RuntimeException("boom")).when(notificationRepository).saveAll(anyList());

        Document doc = saveDocument(owner);
        mockMvc.perform(post("/documents/" + doc.getId() + "/shares")
                        .with(canBo(owner))
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("groupId", groupId))))
                .andExpect(status().isNoContent());

        // The share itself must have gone through despite the notification failure.
        assertThat(documentGroupShareRepository.existsByDocumentIdAndGroupId(doc.getId(), UUID.fromString(groupId)))
                .isTrue();
    }

    // ---- GET/POST /notifications ----------------------------------------------

    @Test
    void markingSomeoneElsesNotificationAsReadIs404() throws Exception {
        String owner = "owner-" + UUID.randomUUID();
        String member = "member-" + UUID.randomUUID();
        String groupId = createGroup(owner, "Nhóm read " + UUID.randomUUID());

        addMember(owner, groupId, member, GroupRole.MEMBER);
        List<Notification> notifications = awaitNotifications(
                n -> "group.member_added".equals(n.getType()) && member.equals(n.getRecipientSub()));
        assertThat(notifications).hasSize(1);
        UUID notificationId = notifications.get(0).getId();

        // owner is not the recipient of the "member added" notification.
        mockMvc.perform(post("/notifications/" + notificationId + "/read").with(canBo(owner)))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/notifications/" + notificationId + "/read").with(canBo(member)))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/notifications?unreadOnly=true").with(canBo(member)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }
}
