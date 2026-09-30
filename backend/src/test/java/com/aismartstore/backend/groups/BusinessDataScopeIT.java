package com.aismartstore.backend.groups;

import static org.assertj.core.api.Assertions.assertThat;

import com.aismartstore.backend.digitization.DigitizationSourceRepository;
import com.aismartstore.backend.digitization.SourceSeparationJobRepository;
import com.aismartstore.backend.documents.Document;
import com.aismartstore.backend.documents.DocumentRepository;
import com.aismartstore.backend.documents.DocumentStatus;
import com.aismartstore.backend.storage.ObjectStorageService;
import com.aismartstore.backend.warehouse.WarehouseFolder;
import com.aismartstore.backend.warehouse.WarehouseFolderRepository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * rbac-group-scope.md §5 ("Unit test - BusinessDataScope: sinh đúng điều
 * kiện SQL cho 3 case"), Story 3 AC-1/AC-2: exercises the {@link
 * BusinessDataScope#forDocuments} {@code Specification} against a real
 * Postgres so the generated JPA Criteria subquery is verified end to end,
 * not just that Java code compiles against a mocked repository (see {@link
 * BusinessDataScopeTest} for the fail-closed unit coverage that doesn't need
 * a persistence context).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Testcontainers
class BusinessDataScopeIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
            .withDatabaseName("aismartstore")
            .withUsername("aismartstore")
            .withPassword("aismartstore");

    @Container
    static GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @org.springframework.test.context.DynamicPropertySource
    static void containerProps(org.springframework.test.context.DynamicPropertyRegistry registry) {
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
    private WarehouseFolderRepository warehouseFolderRepository;

    private final String owner = "owner-" + UUID.randomUUID();
    private final String groupMate = "group-mate-" + UUID.randomUUID();
    private final String stranger = "stranger-" + UUID.randomUUID();

    private UUID rootFolderId;
    private Document ownedByOwner;
    private Document sharedToGroupMatesGroup;
    private Document neitherOwnedNorShared;

    @BeforeEach
    void seed() {
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

        ownedByOwner = saveDocument(owner);
        sharedToGroupMatesGroup = saveDocument(stranger); // uploaded by a 3rd party, shared to groupMate's group
        neitherOwnedNorShared = saveDocument(stranger);

        Group group = new Group();
        group.setName("Nhóm test");
        group.setOwnerSub(owner);
        group.setCreatedAt(OffsetDateTime.now());
        group.setUpdatedAt(OffsetDateTime.now());
        groupRepository.save(group);

        GroupMember membership = new GroupMember();
        membership.setGroupId(group.getId());
        membership.setUserSub(groupMate);
        membership.setRoleInGroup(GroupRole.MEMBER);
        membership.setJoinedAt(OffsetDateTime.now());
        groupMemberRepository.save(membership);

        DocumentGroupShare share = new DocumentGroupShare();
        share.setDocumentId(sharedToGroupMatesGroup.getId());
        share.setGroupId(group.getId());
        share.setSharedBySub(stranger);
        share.setSharedAt(OffsetDateTime.now());
        documentGroupShareRepository.save(share);
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

    // ---- Story 3 AC-1: chính người tạo luôn thấy tài liệu của mình ----------

    @Test
    void ownerSeesTheirOwnDocument() {
        List<Document> visible =
                documentRepository.findAll(BusinessDataScope.forDocuments(owner, groupMemberRepository));

        assertThat(visible).extracting(Document::getId).contains(ownedByOwner.getId());
    }

    // ---- Story 3 AC-2: thành viên nhóm thấy tài liệu được chia sẻ tới nhóm --

    @Test
    void groupMemberSeesADocumentSharedToTheirGroup() {
        List<Document> visible =
                documentRepository.findAll(BusinessDataScope.forDocuments(groupMate, groupMemberRepository));

        assertThat(visible).extracting(Document::getId).containsExactly(sharedToGroupMatesGroup.getId());
    }

    // ---- Story 3 AC-2 (case biên): tài liệu chưa chia sẻ và không phải chủ sở hữu -> không thấy ----

    @Test
    void strangerDoesNotSeeDocumentsTheyNeitherOwnNorHaveGroupAccessTo() {
        List<Document> visible =
                documentRepository.findAll(BusinessDataScope.forDocuments(groupMate, groupMemberRepository));

        assertThat(visible).extracting(Document::getId).doesNotContain(neitherOwnedNorShared.getId());
    }

    // ---- delete-restore-trash-01-xoa-mem-thung-rac.md §2.2/§5 ----------------

    @Test
    void softDeletedDocumentIsInvisibleEvenToItsOwner() {
        ownedByOwner.setDeletedAt(OffsetDateTime.now());
        documentRepository.save(ownedByOwner);

        List<Document> visible =
                documentRepository.findAll(BusinessDataScope.forDocuments(owner, groupMemberRepository));

        assertThat(visible).extracting(Document::getId).doesNotContain(ownedByOwner.getId());
    }

    @Test
    void softDeletedDocumentIsInvisibleEvenToAGroupMemberItWasSharedTo() {
        sharedToGroupMatesGroup.setDeletedAt(OffsetDateTime.now());
        documentRepository.save(sharedToGroupMatesGroup);

        List<Document> visible =
                documentRepository.findAll(BusinessDataScope.forDocuments(groupMate, groupMemberRepository));

        assertThat(visible).extracting(Document::getId).doesNotContain(sharedToGroupMatesGroup.getId());
    }
}
