package com.aismartstore.backend.tags;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aismartstore.backend.audit.AuditLog;
import com.aismartstore.backend.audit.AuditLogRepository;
import com.aismartstore.backend.documents.Document;
import com.aismartstore.backend.documents.DocumentRepository;
import com.aismartstore.backend.documents.DocumentStatus;
import com.aismartstore.backend.groups.DocumentGroupShareRepository;
import com.aismartstore.backend.groups.GroupMemberRepository;
import com.aismartstore.backend.groups.GroupRepository;
import com.aismartstore.backend.outbox.OutboxEvent;
import com.aismartstore.backend.outbox.OutboxEventRepository;
import com.aismartstore.backend.storage.ObjectStorageService;
import com.aismartstore.backend.warehouse.WarehouseFolder;
import com.aismartstore.backend.warehouse.WarehouseFolderRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
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
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * End-to-end coverage of classification-notes-01-tags.md §5 ("Integration
 * test"): real Postgres (Flyway-migrated through V20) behind MockMvc, same
 * Testcontainers pattern as {@code IngestControllerIT}/{@code
 * BusinessDataScopeIT}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Testcontainers
class TagsControllerIT {

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
    private DocumentRepository documentRepository;

    @Autowired
    private TagRepository tagRepository;

    @Autowired
    private DocumentTagRepository documentTagRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private GroupRepository groupRepository;

    @Autowired
    private GroupMemberRepository groupMemberRepository;

    @Autowired
    private DocumentGroupShareRepository documentGroupShareRepository;

    @PersistenceContext
    private EntityManager entityManager;

    private UUID folderId;

    @BeforeEach
    void resetState() throws Exception {
        outboxEventRepository.deleteAll();
        documentGroupShareRepository.deleteAll();
        groupMemberRepository.deleteAll();
        groupRepository.deleteAll();
        documentTagRepository.deleteAll();
        // Only clear topic tags between tests - the 4 doc_type seeds (V20) stay.
        tagRepository.findAll().stream()
                .filter(t -> TagKinds.TOPIC.equals(t.getKind()))
                .forEach(t -> tagRepository.deleteById(t.getId()));
        documentRepository.deleteAll();

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

        setConfig("classification.allow_adhoc_topic_tag", false);
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

    private static JwtRequestPostProcessor officer2() {
        return asRole("officer-2", "can-bo", "CAN_BO");
    }

    private static JwtRequestPostProcessor leader() {
        return asRole("leader-1", "lanh-dao", "LANH_DAO");
    }

    private void setConfig(String key, Object value) throws Exception {
        mockMvc.perform(patch("/admin/system-config/" + key)
                        .with(admin())
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("value", value))))
                .andExpect(status().isOk());
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

    private UUID createTopic(String label) throws Exception {
        String json = mockMvc.perform(post("/tags")
                        .with(officer())
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("kind", "topic", "label", label))))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        @SuppressWarnings("unchecked")
        Map<String, Object> parsed = objectMapper.readValue(json, Map.class);
        return UUID.fromString((String) parsed.get("id"));
    }

    // ---- catalogue: POST /tags chỉ tạo topic, doc_type bị từ chối 400 -------

    @Test
    void creatingADocTypeTagThroughPostTagsIsRejectedWith400() throws Exception {
        mockMvc.perform(post("/tags")
                        .with(officer())
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("kind", "doc_type", "label", "khác"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void creatingATopicThenRenamingAndDeactivatingItWorks() throws Exception {
        UUID tagId = createTopic("Hợp đồng");

        mockMvc.perform(patch("/tags/" + tagId)
                        .with(officer())
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("label", "Hợp đồng thuê"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.label").value("Hợp đồng thuê"));

        mockMvc.perform(post("/tags/" + tagId + "/deactivate").with(officer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));

        assertThat(tagRepository.findById(tagId).orElseThrow().isActive()).isFalse();
    }

    @Test
    void creatingATopicWithADuplicateNormalizedNameIsRejectedWith409() throws Exception {
        createTopic("Hợp đồng");

        mockMvc.perform(post("/tags")
                        .with(officer())
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("kind", "topic", "label", "  hợp   ĐỒNG  "))))
                .andExpect(status().isConflict());
    }

    @Test
    void oldDeleteTagsEndpointNoLongerExists() throws Exception {
        UUID tagId = createTopic("Sẽ xoá");
        mockMvc.perform(delete("/tags/" + tagId).with(officer())).andExpect(status().isMethodNotAllowed());
    }

    // ---- gán/gỡ topic hợp lệ + outbox ----------------------------------------

    @Test
    void assigningAndUnassigningATopicWritesExactlyOneOutboxEventEach() throws Exception {
        Document document = saveDocument("officer-1");
        UUID tagId = createTopic("Hợp đồng");

        mockMvc.perform(post("/documents/" + document.getId() + "/tags")
                        .with(officer())
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("tagId", tagId))))
                .andExpect(status().isCreated());

        List<OutboxEvent> afterAssign = outboxEventRepository.findAll();
        assertThat(afterAssign).hasSize(1);
        assertThat(afterAssign.get(0).getEventType()).isEqualTo("document.classification.changed");
        assertThat(afterAssign.get(0).getProcessedAt()).isNull();
        assertThat(afterAssign.get(0).getPayload()).containsEntry("documentId", document.getId().toString());

        mockMvc.perform(delete("/documents/" + document.getId() + "/tags/" + tagId).with(officer()))
                .andExpect(status().isNoContent());

        assertThat(outboxEventRepository.findAll()).hasSize(2);
    }

    @Test
    void assigningADeactivatedTopicIsRejectedWith400() throws Exception {
        Document document = saveDocument("officer-1");
        UUID tagId = createTopic("Ngừng dùng");
        mockMvc.perform(post("/tags/" + tagId + "/deactivate").with(officer())).andExpect(status().isOk());

        mockMvc.perform(post("/documents/" + document.getId() + "/tags")
                        .with(officer())
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("tagId", tagId))))
                .andExpect(status().isBadRequest());
    }

    // ---- ad-hoc topic creation gated by classification.allow_adhoc_topic_tag ----

    @Test
    void assigningByLabelWithAdhocDisabledIsRejectedWith400() throws Exception {
        setConfig("classification.allow_adhoc_topic_tag", false);
        Document document = saveDocument("officer-1");

        mockMvc.perform(post("/documents/" + document.getId() + "/tags")
                        .with(officer())
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("label", "Chủ đề mới"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void assigningByLabelWithAdhocEnabledCreatesAndAssignsANewTopic() throws Exception {
        setConfig("classification.allow_adhoc_topic_tag", true);
        Document document = saveDocument("officer-1");

        mockMvc.perform(post("/documents/" + document.getId() + "/tags")
                        .with(officer())
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("label", "Chủ đề mới"))))
                .andExpect(status().isCreated());

        List<Tag> topics = tagRepository.findByKindOrderByLabelAsc(TagKinds.TOPIC);
        assertThat(topics).extracting(Tag::getLabel).containsExactly("Chủ đề mới");
    }

    // ---- doc_type: gán thay thế thay vì 409; gỡ đơn lẻ bị chặn ---------------

    @Test
    void assigningADocTypeReplacesThePreviousOneInsteadOfConflicting() throws Exception {
        Document document = saveDocument("officer-1");
        UUID vanBan = tagRepository.findByKindAndLabelIgnoreCase(TagKinds.DOC_TYPE, "văn bản").orElseThrow().getId();
        UUID hinhAnh = tagRepository.findByKindAndLabelIgnoreCase(TagKinds.DOC_TYPE, "hình ảnh").orElseThrow().getId();

        mockMvc.perform(post("/documents/" + document.getId() + "/tags")
                        .with(officer())
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("tagId", vanBan))))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/documents/" + document.getId() + "/tags")
                        .with(officer())
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("tagId", hinhAnh))))
                .andExpect(status().isCreated());

        List<DocumentTag> links = documentTagRepository.findByDocumentIdOrderByAssignedAtAsc(document.getId());
        assertThat(links).hasSize(1);
        assertThat(links.get(0).getTagId()).isEqualTo(hinhAnh);
    }

    @Test
    void unassigningADocTypeStandaloneIsRejectedWith400() throws Exception {
        Document document = saveDocument("officer-1");
        UUID vanBan = tagRepository.findByKindAndLabelIgnoreCase(TagKinds.DOC_TYPE, "văn bản").orElseThrow().getId();
        mockMvc.perform(post("/documents/" + document.getId() + "/tags")
                        .with(officer())
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("tagId", vanBan))))
                .andExpect(status().isCreated());

        mockMvc.perform(delete("/documents/" + document.getId() + "/tags/" + vanBan).with(officer()))
                .andExpect(status().isBadRequest());
    }

    // ---- partial unique index UQ_document_tag_one_doc_type (DB-level) -------

    @Test
    @Transactional
    void databaseLevelPartialUniqueIndexRejectsTwoDocTypesForTheSameDocumentInsertedDirectly() {
        Document document = saveDocument("officer-1");
        UUID vanBan = tagRepository.findByKindAndLabelIgnoreCase(TagKinds.DOC_TYPE, "văn bản").orElseThrow().getId();
        UUID hinhAnh = tagRepository.findByKindAndLabelIgnoreCase(TagKinds.DOC_TYPE, "hình ảnh").orElseThrow().getId();

        insertDocumentTagNative(document.getId(), vanBan, "doc_type");

        // Spring only translates DataIntegrityViolationException for calls that cross a
        // @Repository-proxied bean boundary (PersistenceExceptionTranslationInterceptor).
        // entityManager here is called directly, so the exception Hibernate actually
        // throws - org.hibernate.exception.ConstraintViolationException - reaches this
        // assertion untranslated; that's fine, this test only cares that the DB-level
        // constraint itself rejects the row.
        org.junit.jupiter.api.Assertions.assertThrows(
                org.hibernate.exception.ConstraintViolationException.class,
                () -> {
                    insertDocumentTagNative(document.getId(), hinhAnh, "doc_type");
                    entityManager.flush();
                });
    }

    private void insertDocumentTagNative(UUID documentId, UUID tagId, String tagKind) {
        Query query = entityManager.createNativeQuery(
                "INSERT INTO document_tag (document_id, tag_id, tag_kind, assigned_by_sub, "
                        + "assigned_by_username, assigned_at) VALUES (?1, ?2, ?3, 'system', 'system', now())");
        query.setParameter(1, documentId);
        query.setParameter(2, tagId);
        query.setParameter(3, tagKind);
        query.executeUpdate();
    }

    // ---- Migration V20: kind CHECK constraint rejects 'unit'/'secrecy' ------

    @Test
    @Transactional
    void checkConstraintRejectsTheRemovedUnitAndSecrecyKinds() {
        Query query = entityManager.createNativeQuery(
                "INSERT INTO tag (kind, label, created_by_sub, created_by_username, created_at) "
                        + "VALUES ('unit', 'x', 'system', 'system', now())");
        // See the comment on databaseLevelPartialUniqueIndexRejects... above: direct
        // EntityManager use bypasses Spring's exception translation.
        org.junit.jupiter.api.Assertions.assertThrows(
                org.hibernate.exception.ConstraintViolationException.class,
                () -> {
                    query.executeUpdate();
                    entityManager.flush();
                });
    }

    // ---- DocumentScopeGuard: 404 đồng nhất + audit access.denied ------------

    @Test
    void assigningToADocumentOutOfScopeReturns404AndRecordsAnAccessDeniedAuditEntry() throws Exception {
        Document strangersDocument = saveDocument("someone-else");
        UUID tagId = createTopic("Chủ đề");

        mockMvc.perform(post("/documents/" + strangersDocument.getId() + "/tags")
                        .with(officer())
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("tagId", tagId))))
                .andExpect(status().isNotFound());

        List<AuditLog> auditLogs = auditLogRepository.findAllAscending();
        assertThat(auditLogs)
                .anySatisfy(entry -> {
                    assertThat(entry.getAction()).isEqualTo("access.denied");
                    assertThat(entry.getActorSub()).isEqualTo("officer-1");
                });
    }

    @Test
    void listingTagsForADocumentOutOfScopeReturns404() throws Exception {
        Document strangersDocument = saveDocument("someone-else");
        mockMvc.perform(get("/documents/" + strangersDocument.getId() + "/tags").with(officer()))
                .andExpect(status().isNotFound());
    }

    // ---- GET /tags: không lọc phạm vi ----------------------------------------

    @Test
    void getTagsCatalogueIsNotScopedByCurrentUser() throws Exception {
        createTopic("Ai cũng thấy");

        String officerJson = mockMvc.perform(get("/tags").with(officer()))
                .andReturn().getResponse().getContentAsString();
        String leaderJson = mockMvc.perform(get("/tags").with(leader()))
                .andReturn().getResponse().getContentAsString();
        assertThat(officerJson).isEqualTo(leaderJson);
    }

    // ---- GET /tags/report: scope theo user, R3 -> 403 ------------------------

    @Test
    void tagReportCountsDifferPerUserScopeAndBlocksR3() throws Exception {
        UUID topicId = createTopic("Chủ đề dùng chung");

        Document ownedByOfficer1 = saveDocument("officer-1");
        Document ownedByOfficer2 = saveDocument("officer-2");

        mockMvc.perform(post("/documents/" + ownedByOfficer1.getId() + "/tags")
                        .with(officer())
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("tagId", topicId))))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/documents/" + ownedByOfficer2.getId() + "/tags")
                        .with(officer2())
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("tagId", topicId))))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/tags/report").param("kind", "topic").with(officer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].count").value(1));

        mockMvc.perform(get("/tags/report").param("kind", "topic").with(admin()))
                .andExpect(status().isForbidden());
    }

    // ---- r2-business-parity.md ADR-1 / V35: LANH_DAO (R2) now has
    // tag.manage too (SPEC-r2-business-parity-01 AC-1, R-7). ------------------

    @Test
    void leaderCanCreateAndAttachTag() throws Exception {
        Document document = saveDocument("leader-1");

        String json = mockMvc.perform(post("/tags")
                        .with(leader())
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("kind", "topic", "label", "Nhãn R2"))))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        @SuppressWarnings("unchecked")
        Map<String, Object> parsed = objectMapper.readValue(json, Map.class);
        UUID tagId = UUID.fromString((String) parsed.get("id"));

        mockMvc.perform(post("/documents/" + document.getId() + "/tags")
                        .with(leader())
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("tagId", tagId))))
                .andExpect(status().isCreated());
    }
}
