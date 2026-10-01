package com.aismartstore.backend.notes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aismartstore.backend.audit.AuditLog;
import com.aismartstore.backend.audit.AuditLogRepository;
import com.aismartstore.backend.digitization.DigitizationSourceRepository;
import com.aismartstore.backend.digitization.SourceSeparationJobRepository;
import com.aismartstore.backend.documents.Document;
import com.aismartstore.backend.documents.DocumentRepository;
import com.aismartstore.backend.documents.DocumentStatus;
import com.aismartstore.backend.ingest.UploadBatchRepository;
import com.aismartstore.backend.outbox.OutboxEvent;
import com.aismartstore.backend.outbox.OutboxEventRepository;
import com.aismartstore.backend.scanning.AllowedFileFormat;
import com.aismartstore.backend.scanning.AllowedFileFormatRepository;
import com.aismartstore.backend.scanning.ClamdService;
import com.aismartstore.backend.storage.ObjectStorageService;
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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockMultipartFile;
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
 * End-to-end coverage of classification-notes-02-notes.md §5 ("Integration
 * test"): real Postgres (Flyway-migrated through V20, {@code outbox_event}
 * included since V19) behind MockMvc, same Testcontainers pattern as {@code
 * TagsControllerIT}/{@code SystemConfigControllerIT}. {@link
 * ObjectStorageService}/{@link ClamdService} are mocked for the one ingest
 * scenario below (Story 1, §4 step 1) - everything else here is note
 * CRUD/scope/audit, which never touches either.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Testcontainers
class NotesControllerIT {

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
    private ClamdService clamdService;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private WarehouseFolderRepository warehouseFolderRepository;

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private DocumentNoteRepository documentNoteRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private UploadBatchRepository uploadBatchRepository;

    @Autowired
    private AllowedFileFormatRepository allowedFileFormatRepository;

    @Autowired
    private DigitizationSourceRepository digitizationSourceRepository;

    @Autowired
    private SourceSeparationJobRepository sourceSeparationJobRepository;

    @Autowired
    private StringRedisTemplate redisTemplate;

    private static final byte[] PNG_BYTES = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};

    private UUID folderId;

    @BeforeEach
    void resetState() {
        outboxEventRepository.deleteAll();
        documentNoteRepository.deleteAll();
        digitizationSourceRepository.deleteAll();
        sourceSeparationJobRepository.deleteAll();
        documentRepository.deleteAll();
        uploadBatchRepository.deleteAll();

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

        if (allowedFileFormatRepository.findById("image/png").isEmpty()) {
            AllowedFileFormat row = new AllowedFileFormat();
            row.setMimeType("image/png");
            row.setExtensions(new String[] {"png"});
            row.setCategory("IMAGE");
            row.setMagicBytes(List.of(Map.of("offset", 0, "hex", "89504E47")));
            row.setCreatedAt(OffsetDateTime.now());
            row.setUpdatedAt(OffsetDateTime.now());
            allowedFileFormatRepository.save(row);
        }
        redisTemplate.delete("ingest:allowlist");

        when(clamdService.scanStream(any())).thenReturn(ClamdService.ScanVerdict.ofClean());
        doNothing().when(objectStorageService).putObject(anyString(), any(), anyString());
    }

    private static JwtRequestPostProcessor asRole(String username, String realmRole, String springRole) {
        return jwt().jwt(builder -> builder.subject(username)
                        .claim("preferred_username", username)
                        .claim("realm_access", Map.of("roles", List.of(realmRole))))
                .authorities(com.aismartstore.backend.testsupport.RolePermissionFixtures.authoritiesFor(springRole));
    }

    /** R1 - CAN_BO: {@code note.write} + {@code document.read}. */
    private static JwtRequestPostProcessor officer() {
        return asRole("officer-1", "can-bo", "CAN_BO");
    }

    /** R2 - LANH_DAO: {@code document.read} only, no {@code note.write}. */
    private static JwtRequestPostProcessor leader() {
        return asRole("leader-1", "lanh-dao", "LANH_DAO");
    }

    /** R3 - QUAN_TRI: neither {@code document.read} nor {@code note.write}. */
    private static JwtRequestPostProcessor admin() {
        return asRole("admin-1", "quan-tri", "QUAN_TRI");
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

    // ---- Story 2/3 AC-2: ngoài phạm vi -> 404 + access.denied -------------------

    @Test
    void creatingANoteForADocumentOutOfScopeReturns404AndRecordsAnAccessDeniedAuditEntry() throws Exception {
        Document strangersDocument = saveDocument("someone-else");

        mockMvc.perform(post("/documents/" + strangersDocument.getId() + "/notes")
                        .with(officer())
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("body", "ghi chú"))))
                .andExpect(status().isNotFound());

        assertThat(documentNoteRepository.findAll()).isEmpty();
        List<AuditLog> auditLogs = auditLogRepository.findAllAscending();
        assertThat(auditLogs).anySatisfy(entry -> {
            assertThat(entry.getAction()).isEqualTo("access.denied");
            assertThat(entry.getActorSub()).isEqualTo("officer-1");
        });
    }

    @Test
    void updatingANoteOfADocumentOutOfScopeReturns404() throws Exception {
        Document ownedByOfficer1 = saveDocument("officer-1");
        String noteId = createNote(ownedByOfficer1.getId(), "ghi chú gốc");

        Document strangersDocument = saveDocument("someone-else");
        mockMvc.perform(patch("/documents/" + strangersDocument.getId() + "/notes/" + noteId)
                        .with(officer())
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("body", "sửa"))))
                .andExpect(status().isNotFound());
    }

    private String createNote(UUID documentId, String body) throws Exception {
        String json = mockMvc.perform(post("/documents/" + documentId + "/notes")
                        .with(officer())
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("body", body))))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        @SuppressWarnings("unchecked")
        Map<String, Object> parsed = objectMapper.readValue(json, Map.class);
        return (String) parsed.get("id");
    }

    // ---- r2-business-parity.md ADR-1 / V35: LANH_DAO (R2) now has note.write
    // too (SPEC-r2-business-parity-01 AC-1, R-7) - admin (R3) still 403. ------

    @Test
    void postAndPatchByTheLeaderOwnerSucceed() throws Exception {
        Document document = saveDocument("leader-1");

        String json = mockMvc.perform(post("/documents/" + document.getId() + "/notes")
                        .with(leader())
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("body", "ghi chú"))))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        @SuppressWarnings("unchecked")
        Map<String, Object> parsed = objectMapper.readValue(json, Map.class);
        String noteId = (String) parsed.get("id");

        mockMvc.perform(patch("/documents/" + document.getId() + "/notes/" + noteId)
                        .with(leader())
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("body", "sửa"))))
                .andExpect(status().isOk());
    }

    @Test
    void postAndPatchByAdminAreForbidden() throws Exception {
        Document document = saveDocument("officer-1");

        mockMvc.perform(post("/documents/" + document.getId() + "/notes")
                        .with(admin())
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("body", "ghi chú"))))
                .andExpect(status().isForbidden());

        String noteId = createNote(document.getId(), "ghi chú gốc");
        mockMvc.perform(patch("/documents/" + document.getId() + "/notes/" + noteId)
                        .with(admin())
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("body", "sửa"))))
                .andExpect(status().isForbidden());
    }

    // ---- Story 4 AC-3: R3 (QUAN_TRI) không có document.read -> 403 --------------

    @Test
    void listingNotesByR3IsForbidden() throws Exception {
        Document document = saveDocument("officer-1");
        mockMvc.perform(get("/documents/" + document.getId() + "/notes").with(admin()))
                .andExpect(status().isForbidden());
    }

    // ---- 409 khi expectedUpdatedAt lệch ------------------------------------------

    @Test
    void updatingWithAStaleExpectedUpdatedAtReturns409AndLeavesTheBodyUnchanged() throws Exception {
        Document document = saveDocument("officer-1");
        String noteId = createNote(document.getId(), "ghi chú gốc");

        mockMvc.perform(patch("/documents/" + document.getId() + "/notes/" + noteId)
                        .with(officer())
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "body", "sửa lần 1",
                                "expectedUpdatedAt", OffsetDateTime.now().minusDays(1).toString()))))
                .andExpect(status().isConflict());

        DocumentNote unchanged =
                documentNoteRepository.findById(UUID.fromString(noteId)).orElseThrow();
        assertThat(unchanged.getBody()).isEqualTo("ghi chú gốc");
    }

    // ---- PATCH thành công -> trả về updatedBySub/updatedByUsername/updatedAt ---

    @Test
    void updatingSuccessfullyReturnsUpdatedByFields() throws Exception {
        Document document = saveDocument("officer-1");
        String noteId = createNote(document.getId(), "ghi chú gốc");

        mockMvc.perform(patch("/documents/" + document.getId() + "/notes/" + noteId)
                        .with(officer())
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("body", "sửa lần cuối"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body").value("sửa lần cuối"))
                .andExpect(jsonPath("$.updatedBySub").value("officer-1"))
                .andExpect(jsonPath("$.updatedByUsername").value("officer-1"))
                .andExpect(jsonPath("$.updatedAt").isNotEmpty());
    }

    // ---- Story 3 AC-5: không có route DELETE --------------------------------------

    @Test
    void deleteRouteDoesNotExist() throws Exception {
        Document document = saveDocument("officer-1");
        String noteId = createNote(document.getId(), "ghi chú gốc");

        mockMvc.perform(delete("/documents/" + document.getId() + "/notes/" + noteId).with(officer()))
                .andExpect(status().isMethodNotAllowed());
    }

    // ---- 0 ghi chú -> mảng rỗng ---------------------------------------------------

    @Test
    void listingNotesForADocumentWithNoneReturnsAnEmptyArray() throws Exception {
        Document document = saveDocument("officer-1");

        mockMvc.perform(get("/documents/" + document.getId() + "/notes").with(officer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(0));
    }

    // ---- UUID hợp lệ nhưng không tồn tại -> 404 giống hệt "ngoài phạm vi" -------

    @Test
    void creatingANoteForANonExistentDocumentIdReturns404() throws Exception {
        mockMvc.perform(post("/documents/" + UUID.randomUUID() + "/notes")
                        .with(officer())
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("body", "ghi chú"))))
                .andExpect(status().isNotFound());
    }

    // ---- Task 3: outbox_event ghi đúng 1 dòng/lần create-update, không có body --

    @Test
    void creatingThenUpdatingANoteWritesExactlyOneOutboxEventEachWithoutTheBody() throws Exception {
        Document document = saveDocument("officer-1");

        String noteId = createNote(document.getId(), "nội dung bí mật lúc tạo");

        List<OutboxEvent> afterCreate = outboxEventRepository.findAll();
        assertThat(afterCreate).hasSize(1);
        assertThat(afterCreate.get(0).getEventType()).isEqualTo("document.note.changed");
        assertThat(afterCreate.get(0).getPayload()).containsOnlyKeys("documentId", "changedAt", "changedBySub");
        assertThat(afterCreate.get(0).getPayload()).containsEntry("documentId", document.getId().toString());
        assertThat(afterCreate.get(0).getPayload().values()).doesNotContain("nội dung bí mật lúc tạo");

        mockMvc.perform(patch("/documents/" + document.getId() + "/notes/" + noteId)
                        .with(officer())
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("body", "nội dung bí mật lúc sửa"))))
                .andExpect(status().isOk());

        List<OutboxEvent> afterUpdate = outboxEventRepository.findAll();
        assertThat(afterUpdate).hasSize(2);
        assertThat(afterUpdate)
                .noneMatch(e -> e.getPayload().values().stream()
                        .anyMatch(v -> v != null && v.toString().contains("nội dung bí mật")));
    }

    // ---- Task 5: audit_log.metadata chỉ có documentId/noteId, không có body -----

    @Test
    void auditLogEntriesForNoteCreateAndUpdateNeverContainTheBody() throws Exception {
        Document document = saveDocument("officer-1");
        String secretBody = "nội dung riêng tư không được vào audit_log";

        String noteId = createNote(document.getId(), secretBody);
        mockMvc.perform(patch("/documents/" + document.getId() + "/notes/" + noteId)
                        .with(officer())
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("body", secretBody + " sửa"))))
                .andExpect(status().isOk());

        List<AuditLog> noteEntries = auditLogRepository.findAllAscending().stream()
                .filter(e -> "note.create".equals(e.getAction()) || "note.update".equals(e.getAction()))
                .toList();
        assertThat(noteEntries).hasSizeGreaterThanOrEqualTo(2);
        for (AuditLog entry : noteEntries) {
            assertThat(entry.getMetadata()).containsOnlyKeys("documentId", "noteId");
            assertThat(entry.getMetadata().values().stream().map(String::valueOf))
                    .noneMatch(v -> v.contains(secretBody));
        }
    }

    // ---- Story 1: ingest kèm note > 4000 ký tự -> 400, không tạo document_note --

    @Test
    void ingestWithANoteLongerThan4000CharactersIsRejectedWith400AndCreatesNoNote() throws Exception {
        String batchId = openBatch(folderId, 1, 1_000_000);
        String tooLongNote = "a".repeat(NoteConstraints.NOTE_MAX_LENGTH + 1);

        MockMultipartFile file = new MockMultipartFile("file", "a.png", "image/png", PNG_BYTES);
        mockMvc.perform(multipart("/ingest/batches/" + batchId + "/files")
                        .file(file)
                        .param("displayName", "a.png")
                        .param("note", tooLongNote)
                        .with(officer()))
                .andExpect(status().isBadRequest());

        assertThat(documentNoteRepository.findAll()).isEmpty();
    }

    @Test
    void ingestWithAValidNoteCreatesItAfterTheDocumentIsPersisted() throws Exception {
        String batchId = openBatch(folderId, 1, 1_000_000);

        MockMultipartFile file = new MockMultipartFile("file", "a.png", "image/png", PNG_BYTES);
        mockMvc.perform(multipart("/ingest/batches/" + batchId + "/files")
                        .file(file)
                        .param("displayName", "a.png")
                        .param("note", "ghi chú lúc nạp")
                        .with(officer()))
                .andExpect(status().isCreated());

        List<Document> documents = documentRepository.findAll();
        assertThat(documents).hasSize(1);
        List<DocumentNote> notes = documentNoteRepository.findByDocumentIdOrderByCreatedAtDesc(documents.get(0).getId());
        assertThat(notes).hasSize(1);
        assertThat(notes.get(0).getBody()).isEqualTo("ghi chú lúc nạp");
    }

    private String openBatch(UUID folderId, int declaredFileCount, long declaredTotalBytes) throws Exception {
        String json = mockMvc.perform(post("/ingest/batches")
                        .with(officer())
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "targetFolderId", folderId,
                                "declaredFileCount", declaredFileCount,
                                "declaredTotalBytes", declaredTotalBytes))))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        @SuppressWarnings("unchecked")
        Map<String, Object> parsed = objectMapper.readValue(json, Map.class);
        return (String) parsed.get("batchId");
    }
}
