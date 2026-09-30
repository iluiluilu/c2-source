package com.aismartstore.backend.tags;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aismartstore.backend.digitization.DigitizationSourceRepository;
import com.aismartstore.backend.digitization.SourceSeparationJobRepository;
import com.aismartstore.backend.digitization.results.DigitizationRunRepository;
import com.aismartstore.backend.digitization.results.DocumentDigitizationStatusRepository;
import com.aismartstore.backend.documents.Document;
import com.aismartstore.backend.documents.DocumentRepository;
import com.aismartstore.backend.documents.DocumentStatus;
import com.aismartstore.backend.ingest.UploadBatchRepository;
import com.aismartstore.backend.outbox.OutboxEventRepository;
import com.aismartstore.backend.scanning.AllowedFileFormatRepository;
import com.aismartstore.backend.scanning.ClamdService;
import com.aismartstore.backend.storage.ObjectStorageService;
import com.aismartstore.backend.warehouse.Warehouse;
import com.aismartstore.backend.warehouse.WarehouseFolder;
import com.aismartstore.backend.warehouse.WarehouseFolderRepository;
import com.aismartstore.backend.warehouse.WarehouseRepository;
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
 * End-to-end coverage of classification-notes-01-tags.md §4.3/§4.4/§5:
 * auto-assign at ingest (live path, through the real {@code
 * DocumentsService.ingestBatchFile}), the one-off backfill for
 * pre-existing documents ({@link DocTypeAssignmentService#backfillPage}),
 * and {@code GET /documents/unclassified-doc-type} (US2 AC-5/AC-6).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Testcontainers
class DocTypeAssignmentIT {

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
    private WarehouseRepository warehouseRepository;

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
    private UploadBatchRepository uploadBatchRepository;

    @Autowired
    private AllowedFileFormatRepository allowedFileFormatRepository;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private TagRepository tagRepository;

    @Autowired
    private DocumentTagRepository documentTagRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private DocTypeAssignmentService docTypeAssignmentService;

    private static final long DEFAULT_MAX_CAPACITY_BYTES = 10_995_116_277_760L;
    private static final byte[] PNG_BYTES = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};

    private UUID folderId;

    @BeforeEach
    void resetState() throws Exception {
        outboxEventRepository.deleteAll();
        documentTagRepository.deleteAll();
        digitizationRunRepository.deleteAll();
        digitizationSourceRepository.deleteAll();
        sourceSeparationJobRepository.deleteAll();
        documentDigitizationStatusRepository.deleteAll();
        documentRepository.deleteAll();
        uploadBatchRepository.deleteAll();
        List<WarehouseFolder> all = new ArrayList<>(warehouseFolderRepository.findAll());
        all.sort(Comparator.comparingInt(WarehouseFolder::getDepth).reversed());
        warehouseFolderRepository.deleteAll(all);

        Warehouse warehouse = warehouseRepository.findById(WarehouseRepository.SINGLETON_ID).orElseThrow();
        warehouse.setMaxCapacityBytes(DEFAULT_MAX_CAPACITY_BYTES);
        warehouseRepository.save(warehouse);

        setConfig("ingest.max_batch_size_bytes", 5L * 1024 * 1024 * 1024);
        setConfig("ingest.max_file_size_bytes", 2L * 1024 * 1024 * 1024);
        setConfig("ingest.max_files_per_batch", 500);

        if (allowedFileFormatRepository.findById("image/png").isEmpty()) {
            com.aismartstore.backend.scanning.AllowedFileFormat row =
                    new com.aismartstore.backend.scanning.AllowedFileFormat();
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
                .authorities(com.aismartstore.backend.testsupport.RolePermissionFixtures.authoritiesFor(springRole));
    }

    private static JwtRequestPostProcessor admin() {
        return asRole("admin-1", "quan-tri", "QUAN_TRI");
    }

    private static JwtRequestPostProcessor officer() {
        return asRole("officer-1", "can-bo", "CAN_BO");
    }

    private void setConfig(String key, Object value) throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .patch("/admin/system-config/" + key)
                        .with(admin())
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("value", value))))
                .andExpect(status().isOk());
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

    private void uploadPng(String batchId, String filename) throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", filename, "image/png", PNG_BYTES);
        mockMvc.perform(multipart("/ingest/batches/" + batchId + "/files").file(file).with(officer()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("INGESTED"));
    }

    private Document saveDocumentDirect(String mimeType) {
        Document document = new Document();
        document.setOriginalName("legacy-" + UUID.randomUUID());
        document.setMimeType(mimeType);
        document.setSize(10);
        document.setSha256("y".repeat(64));
        document.setStorageKey("documents/" + UUID.randomUUID());
        document.setRelativePath("");
        document.setFolderId(folderId);
        document.setUploaderSub("officer-1");
        document.setUploaderUsername("officer-1");
        document.setStatus(DocumentStatus.CLEAN);
        document.setCreatedAt(OffsetDateTime.now());
        return documentRepository.save(document);
    }

    // ---- §4.3: auto-assign at ingest (live path, mappable MIME) -------------

    @Test
    void ingestingAMappableFileAutoAssignsExactlyOneDocTypeTagAndWritesAnOutboxEvent() throws Exception {
        String batchId = openBatch(folderId, 5, 1_000_000);
        uploadPng(batchId, "photo.png");

        Document document = documentRepository.findAll().get(0);
        List<Tag> tags = documentTagRepository.findTagsForDocument(document.getId());
        assertThat(tags).extracting(Tag::getKind).containsExactly(TagKinds.DOC_TYPE);
        assertThat(tags).extracting(Tag::getLabel).containsExactly("hình ảnh");

        assertThat(outboxEventRepository.findAll())
                .anySatisfy(event -> assertThat(event.getEventType()).isEqualTo("document.classification.changed"));
    }

    // ---- §4.4: backfill for pre-existing documents ---------------------------

    @Test
    void backfillAssignsDocTypeForAMappableLegacyDocumentAndLeavesAnUnmappableOneUntouched() {
        Document mappable = saveDocumentDirect("image/png");
        Document unmappable = saveDocumentDirect("application/x-legacy-unknown");

        DocTypeAssignmentService.BackfillOutcome outcome = docTypeAssignmentService.backfillPage(500);

        assertThat(outcome.assigned()).isEqualTo(1);
        assertThat(outcome.stillUnmapped()).isEqualTo(1);
        assertThat(documentTagRepository.findTagsForDocument(mappable.getId()))
                .extracting(Tag::getLabel)
                .containsExactly("hình ảnh");
        assertThat(documentTagRepository.findTagsForDocument(unmappable.getId())).isEmpty();
    }

    // ---- US2 AC-5/AC-6: GET /documents/unclassified-doc-type -------------------

    @Test
    void unclassifiedDocTypeEndpointListsOnlyDocumentsWithNoDocTypeLinkForTheCallingUsersScope() throws Exception {
        Document unmappable = saveDocumentDirect("application/x-legacy-unknown");
        Document mappable = saveDocumentDirect("image/png");
        docTypeAssignmentService.assignOnIngest(mappable, "officer-1", "officer-1");

        mockMvc.perform(get("/documents/unclassified-doc-type").with(officer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(unmappable.getId().toString()))
                .andExpect(jsonPath("$.length()").value(1));
    }
}
