package com.aismartstore.backend.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
import com.aismartstore.backend.scanning.AllowedFileFormat;
import com.aismartstore.backend.scanning.AllowedFileFormatRepository;
import com.aismartstore.backend.scanning.ClamdService;
import com.aismartstore.backend.scanning.ScannerUnavailableException;
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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mock.web.MockMultipartFile;
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
 * End-to-end coverage of data-ingestion.md §5 ("Integration test"): real
 * Postgres (Flyway-migrated through V10) + real Redis (allowlist cache)
 * behind MockMvc - same Testcontainers pattern as
 * {@code WarehouseControllerIT}/{@code SystemConfigControllerIT}.
 * {@link ObjectStorageService} and {@link ClamdService} are mocked: neither
 * a real SeaweedFS nor a real clamd is part of this test's containers, and
 * the pipeline steps around them (format/hash/batch counters/capacity) are
 * exactly what this test exercises.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Testcontainers
class IngestControllerIT {

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

    // digitization-pipeline-01-source-dispatch.md V18: digitization_source/
    // source_separation_job FK-reference document(id) with no cascade delete
    // (spec §2, copied as-is) - DocumentsService.ingestBatchFile's listener now
    // populates them for image/audio/video/pdf uploads, so they must be cleared
    // before documentRepository.deleteAll() below or the FK constraint fails.
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

    private static final long DEFAULT_MAX_CAPACITY_BYTES = 10_995_116_277_760L;
    private static final byte[] PNG_BYTES = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};

    @BeforeEach
    void resetState() throws Exception {
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

        // Restore the ingest limits to their (generous) defaults before each test -
        // individual tests lower them via PATCH /admin/system-config as needed. Order
        // matters: max_file_size_bytes must never exceed max_batch_size_bytes
        // (SystemConfigValidator cross-parameter rule), so widen the batch cap first.
        setConfig("ingest.max_batch_size_bytes", 5L * 1024 * 1024 * 1024);
        setConfig("ingest.max_file_size_bytes", 2L * 1024 * 1024 * 1024);
        setConfig("ingest.max_files_per_batch", 500);

        // One test (Story 5 AC-3) deletes 'image/png' from the allowlist to prove the
        // change is live immediately - restore it (+ drop the stale Redis cache) so
        // every other test can still upload a PNG regardless of execution order.
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

    private static JwtRequestPostProcessor admin() {
        return asRole("admin-1", "quan-tri", "QUAN_TRI");
    }

    private static JwtRequestPostProcessor officer() {
        return asRole("officer-1", "can-bo", "CAN_BO");
    }

    private static JwtRequestPostProcessor leader() {
        return asRole("leader-1", "lanh-dao", "LANH_DAO");
    }

    private UUID createFolder(String name) throws Exception {
        String json = mockMvc.perform(post("/folders")
                        .with(admin())
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("name", name))))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        @SuppressWarnings("unchecked")
        Map<String, Object> parsed = objectMapper.readValue(json, Map.class);
        return UUID.fromString((String) parsed.get("id"));
    }

    private void setConfig(String key, Object value) throws Exception {
        mockMvc.perform(patch("/admin/system-config/" + key)
                        .with(admin())
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("value", value))))
                .andExpect(status().isOk());
    }

    private String openBatch(UUID folderId, int declaredFileCount, long declaredTotalBytes) throws Exception {
        return openBatchAs(officer(), folderId, declaredFileCount, declaredTotalBytes);
    }

    /** Same as {@link #openBatch}, parameterized by role (SPEC-r2-business-parity-01 AC-8: R2 same limits as R1). */
    private String openBatchAs(JwtRequestPostProcessor role, UUID folderId, int declaredFileCount, long declaredTotalBytes)
            throws Exception {
        String json = mockMvc.perform(post("/ingest/batches")
                        .with(role)
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

    private org.springframework.test.web.servlet.ResultActions uploadPng(
            String batchId, String filename, String relativePath) throws Exception {
        return uploadPng(batchId, filename, relativePath, filename);
    }

    private org.springframework.test.web.servlet.ResultActions uploadPng(
            String batchId, String filename, String relativePath, String displayName) throws Exception {
        return uploadPngAs(officer(), batchId, filename, relativePath, displayName);
    }

    /** Same as {@link #uploadPng}, parameterized by role (SPEC-r2-business-parity-01 AC-8: R2 same limits as R1). */
    private org.springframework.test.web.servlet.ResultActions uploadPngAs(
            JwtRequestPostProcessor role, String batchId, String filename, String relativePath, String displayName)
            throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", filename, "image/png", PNG_BYTES);
        var request = multipart("/ingest/batches/" + batchId + "/files").file(file).param("displayName", displayName);
        if (relativePath != null) {
            request.param("relativePath", relativePath);
        }
        return mockMvc.perform(request.with(role));
    }

    // ---- Story 2 AC-3: declared-limit pre-check at open -----------------------

    @Test
    void openBatchAboveDeclaredFileCountLimitIsRejectedAndNothingIsCreated() throws Exception {
        UUID folderId = createFolder("Root");
        setConfig("ingest.max_files_per_batch", 1);

        mockMvc.perform(post("/ingest/batches")
                        .with(officer())
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(
                                Map.of("targetFolderId", folderId, "declaredFileCount", 2, "declaredTotalBytes", 100))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BATCH_LIMIT_EXCEEDED"));

        assertThat(uploadBatchRepository.findAll()).isEmpty();
    }

    // SPEC-r2-business-parity-01 AC-8 (R-6): R2 (LANH_DAO) hits the exact same
    // batch-limit error as R1 - the limit is per-batch, not per-role.
    @Test
    void leaderOpenBatchAboveDeclaredFileCountLimitGetsTheSameErrorAsOfficer() throws Exception {
        UUID folderId = createFolder("Root");
        setConfig("ingest.max_files_per_batch", 1);

        mockMvc.perform(post("/ingest/batches")
                        .with(leader())
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(
                                Map.of("targetFolderId", folderId, "declaredFileCount", 2, "declaredTotalBytes", 100))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BATCH_LIMIT_EXCEEDED"));

        assertThat(uploadBatchRepository.findAll()).isEmpty();
    }

    // ---- Story 1/2 AC-2: one bad file does not block the rest -----------------

    @Test
    void oneInvalidFormatDoesNotBlockOtherFilesInTheSameBatch() throws Exception {
        UUID folderId = createFolder("Root");
        String batchId = openBatch(folderId, 2, 1_000_000);

        MockMultipartFile badFile = new MockMultipartFile("file", "virus.exe", "application/x-msdownload",
                "not a real exe".getBytes());
        mockMvc.perform(multipart("/ingest/batches/" + batchId + "/files").file(badFile)
                        .param("displayName", "virus.exe").with(officer()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_FORMAT"));

        uploadPng(batchId, "photo.png", null).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("INGESTED"));
    }

    // ---- Story 2 AC-3: actual usage can still exceed a limit lowered mid-batch --

    @Test
    void reserveFailsWhenLimitIsLoweredAfterOpenAndPriorFilesAreUnaffected() throws Exception {
        UUID folderId = createFolder("Root");
        String batchId = openBatch(folderId, 5, 1_000_000);

        uploadPng(batchId, "a.png", null).andExpect(status().isCreated());

        // PNG_BYTES is 8 bytes; after 1 file actual_total_bytes=8, so a cap of 10
        // admits the 8 already used but rejects 8 more. max_file_size_bytes must be
        // lowered first (cross-parameter rule: it can never exceed the batch cap).
        setConfig("ingest.max_file_size_bytes", 10);
        setConfig("ingest.max_batch_size_bytes", 10);

        uploadPng(batchId, "b.png", null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BATCH_LIMIT_EXCEEDED"));

        assertThat(documentRepository.findAll()).hasSize(1);
    }

    // ---- Story 5 AC-5: warehouse capacity pre-check mid-batch ------------------

    @Test
    void warehouseCapacityBlockedMidBatchRejectsTheNextFileOnly() throws Exception {
        UUID folderId = createFolder("Root");
        String batchId = openBatch(folderId, 5, 1_000_000);

        uploadPng(batchId, "a.png", null).andExpect(status().isCreated());

        Warehouse warehouse = warehouseRepository.findById(WarehouseRepository.SINGLETON_ID).orElseThrow();
        warehouse.setMaxCapacityBytes(1); // now far over the 95% block threshold
        warehouseRepository.save(warehouse);

        uploadPng(batchId, "b.png", null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WAREHOUSE_CAPACITY_EXCEEDED"));
    }

    // SPEC-r2-business-parity-01 AC-8 (R-6): same warehouse-capacity check for
    // a batch opened by R2 (LANH_DAO) - capacity is global, not per-role.
    @Test
    void leaderWarehouseCapacityBlockedGetsTheSameErrorAsOfficer() throws Exception {
        UUID folderId = createFolder("Root");
        String batchId = openBatchAs(leader(), folderId, 5, 1_000_000);

        uploadPngAs(leader(), batchId, "a.png", null, "a.png").andExpect(status().isCreated());

        Warehouse warehouse = warehouseRepository.findById(WarehouseRepository.SINGLETON_ID).orElseThrow();
        warehouse.setMaxCapacityBytes(1); // now far over the 95% block threshold
        warehouseRepository.save(warehouse);

        uploadPngAs(leader(), batchId, "b.png", null, "b.png")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WAREHOUSE_CAPACITY_EXCEEDED"));

        assertThat(documentRepository.findAll()).hasSize(1);
    }

    // ---- SPEC-folder-upload-display-name: no dedup by name, unique display_name ----------

    @Test
    void reuploadWithTheSameDisplayNameIsAConflictNotASkip() throws Exception {
        UUID folderId = createFolder("Root");
        String batchId = openBatch(folderId, 5, 1_000_000);

        uploadPng(batchId, "a.png", null).andExpect(status().isCreated());

        uploadPng(batchId, "A.PNG", null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DISPLAY_NAME_CONFLICT"));

        assertThat(documentRepository.findAll()).hasSize(1);
        // the reservation of the failed attempt was given back
        mockMvc.perform(get("/ingest/batches/" + batchId).with(officer()))
                .andExpect(jsonPath("$.actualFileCount").value(1));
    }

    @Test
    void reuploadWithASuffixedDisplayNameCreatesANewCopy() throws Exception {
        UUID folderId = createFolder("Root");
        String batchId = openBatch(folderId, 5, 1_000_000);

        uploadPng(batchId, "a.png", null).andExpect(status().isCreated());
        uploadPng(batchId, "a.png", null, "a_20260930101530123-ab12cd.png")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("INGESTED"))
                .andExpect(jsonPath("$.document.displayName").value("a_20260930101530123-ab12cd.png"))
                .andExpect(jsonPath("$.document.originalName").value("a.png"))
                .andExpect(jsonPath("$.document.storageKey").doesNotExist());

        assertThat(documentRepository.findAll()).hasSize(2);
    }

    @Test
    void sameNameInADifferentRelativePathIsNotAConflict() throws Exception {
        UUID folderId = createFolder("Root");
        String batchId = openBatch(folderId, 5, 1_000_000);

        uploadPng(batchId, "a.png", "dirA").andExpect(status().isCreated());
        uploadPng(batchId, "a.png", "dirB").andExpect(status().isCreated());
        uploadPng(batchId, "a.png", "dirA").andExpect(status().isConflict());
    }

    @Test
    void invalidDisplayNameIsRejectedWith422AndReason() throws Exception {
        UUID folderId = createFolder("Root");
        String batchId = openBatch(folderId, 5, 1_000_000);

        uploadPng(batchId, "a.png", null, "other.png")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_DISPLAY_NAME"))
                .andExpect(jsonPath("$.reason").value("SUFFIX_MISMATCH"));
        // missing displayName (old client) -> 422 EMPTY
        mockMvc.perform(multipart("/ingest/batches/" + batchId + "/files")
                        .file(new MockMultipartFile("file", "a.png", "image/png", PNG_BYTES))
                        .with(officer()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.reason").value("EMPTY"));
        assertThat(documentRepository.findAll()).isEmpty();
    }

    @Test
    void nameCheckReportsTakenNamesInOrderIgnoringCaseAndTrash() throws Exception {
        UUID folderId = createFolder("Root");
        String batchId = openBatch(folderId, 5, 1_000_000);
        uploadPng(batchId, "a.png", "dir").andExpect(status().isCreated());
        uploadPng(batchId, "gone.png", "dir").andExpect(status().isCreated());
        Document gone = documentRepository.findAll().stream()
                .filter(d -> "gone.png".equals(d.getOriginalName())).findFirst().orElseThrow();
        gone.setDeletedAt(java.time.OffsetDateTime.now());
        documentRepository.save(gone);

        String body = objectMapper.writeValueAsString(Map.of(
                "folderId", folderId.toString(),
                "files", java.util.List.of(
                        Map.of("relativePath", "dir", "originalName", "A.PNG"),
                        Map.of("relativePath", "dir", "originalName", "new.png"),
                        Map.of("relativePath", "other", "originalName", "a.png"),
                        Map.of("relativePath", "dir", "originalName", "gone.png"))));
        mockMvc.perform(post("/ingest/name-check").contentType("application/json").content(body).with(officer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].taken").value(true))
                .andExpect(jsonPath("$[1].taken").value(false))
                .andExpect(jsonPath("$[2].taken").value(false))
                .andExpect(jsonPath("$[3].taken").value(false));
    }

    @Test
    void nameCheckRejectsMoreThan1000FilesAndUnknownFolder() throws Exception {
        UUID folderId = createFolder("Root");
        java.util.List<Map<String, String>> files = new java.util.ArrayList<>();
        for (int i = 0; i < 1001; i++) {
            files.add(Map.of("relativePath", "", "originalName", "f" + i));
        }
        mockMvc.perform(post("/ingest/name-check").contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("folderId", folderId.toString(), "files", files)))
                        .with(officer()))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/ingest/name-check").contentType("application/json")
                        .content(objectMapper.writeValueAsString(
                                Map.of("folderId", UUID.randomUUID().toString(), "files", java.util.List.of())))
                        .with(officer()))
                .andExpect(status().isNotFound());
    }

    @Test
    void nameCheckIsRateLimitedPerUserAndEndpointWith429() throws Exception {
        UUID folderId = createFolder("Root");
        String body = objectMapper.writeValueAsString(Map.of("folderId", folderId.toString(), "files", java.util.List.of()));
        var otherOfficer = asRole("rate-limited-officer", "can-bo", "CAN_BO");
        for (int i = 0; i < 30; i++) {
            mockMvc.perform(post("/ingest/name-check").contentType("application/json").content(body).with(otherOfficer))
                    .andExpect(status().isOk());
        }
        mockMvc.perform(post("/ingest/name-check").contentType("application/json").content(body).with(otherOfficer))
                .andExpect(status().isTooManyRequests());
        // another user is not affected
        mockMvc.perform(post("/ingest/name-check").contentType("application/json").content(body).with(officer()))
                .andExpect(status().isOk());
    }

    // r2-business-parity.md ADR-1 / V35: LANH_DAO (R2) now has document.upload
    // too (SPEC-r2-business-parity-01 AC-1, R-7) - only QUAN_TRI (R3) still
    // lacks it, so that is the role that proves the permission gate still works.
    @Test
    void nameCheckRequiresTheUploadPermission() throws Exception {
        UUID folderId = createFolder("Root");
        String body = objectMapper.writeValueAsString(Map.of("folderId", folderId.toString(), "files", java.util.List.of()));
        mockMvc.perform(post("/ingest/name-check").contentType("application/json").content(body).with(admin()))
                .andExpect(status().isForbidden());
    }

    @Test
    void leaderHasTheUploadPermissionOnNameCheck() throws Exception {
        UUID folderId = createFolder("Root");
        String body = objectMapper.writeValueAsString(Map.of("folderId", folderId.toString(), "files", java.util.List.of()));
        mockMvc.perform(post("/ingest/name-check").contentType("application/json").content(body).with(leader()))
                .andExpect(status().isOk());
    }

    @Test
    void aFileFlaggedInfectedStillHoldsItsNameButARejectedFormatDoesNot() throws Exception {
        UUID folderId = createFolder("Root");
        String batchId = openBatch(folderId, 5, 1_000_000);

        when(clamdService.scanStream(any())).thenReturn(ClamdService.ScanVerdict.infected("Eicar"));
        uploadPng(batchId, "virus.png", null).andExpect(status().isUnprocessableEntity());
        when(clamdService.scanStream(any())).thenReturn(ClamdService.ScanVerdict.ofClean());

        MockMultipartFile bad = new MockMultipartFile("file", "bad.exe", "application/x-msdownload", "x".getBytes());
        mockMvc.perform(multipart("/ingest/batches/" + batchId + "/files").file(bad)
                        .param("displayName", "bad.exe").with(officer()))
                .andExpect(status().isUnprocessableEntity());

        String body = objectMapper.writeValueAsString(Map.of(
                "folderId", folderId.toString(),
                "files", java.util.List.of(
                        Map.of("relativePath", "", "originalName", "virus.png"),
                        Map.of("relativePath", "", "originalName", "bad.exe"))));
        mockMvc.perform(post("/ingest/name-check").contentType("application/json").content(body).with(officer()))
                .andExpect(jsonPath("$[0].taken").value(true))   // infected row keeps the name (Q3)
                .andExpect(jsonPath("$[1].taken").value(false)); // rejected evidence row does not
        uploadPng(batchId, "virus.png", null).andExpect(status().isConflict());
    }

    @Test
    void nameOf512CodePointsInAPathOf1024CodePointsIsAcceptedAndStillUnique() throws Exception {
        UUID folderId = createFolder("Root");
        String batchId = openBatch(folderId, 5, 1_000_000);
        String name = "Ệ".repeat(508) + ".png"; // 512 code points, Vietnamese (multi-byte)
        String path = ("d".repeat(31) + "/").repeat(32).substring(0, 1024); // 1024 chars

        uploadPng(batchId, name, path).andExpect(status().isCreated());
        uploadPng(batchId, name, path).andExpect(status().isConflict());
        uploadPng(batchId, "x" + name.substring(1), path).andExpect(status().isCreated());

        String tooLongPath = path + "e";
        uploadPng(batchId, "p.png", tooLongPath).andExpect(status().is4xxClientError());
    }

    @Test
    void contentDispositionUsesOriginalNameAndNotDisplayName() throws Exception {
        UUID folderId = createFolder("Root");
        String batchId = openBatch(folderId, 5, 1_000_000);
        uploadPng(batchId, "a.png", null).andExpect(status().isCreated());
        uploadPng(batchId, "a.png", null, "a_20260930101530123-ab12cd.png").andExpect(status().isCreated());
        Document copy = documentRepository.findAll().stream()
                .filter(d -> d.getDisplayName().contains("_2026")).findFirst().orElseThrow();
        when(objectStorageService.getObject(anyString())).thenReturn(new software.amazon.awssdk.core.ResponseInputStream<>(
                software.amazon.awssdk.services.s3.model.GetObjectResponse.builder().build(),
                software.amazon.awssdk.http.AbortableInputStream.create(new java.io.ByteArrayInputStream(PNG_BYTES))));
        mockMvc.perform(get("/documents/" + copy.getId() + "/content").with(officer()))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", "attachment; filename*=UTF-8''a.png"));
    }

    @Test
    void reuploadAfterAPriorRejectionIsProcessedFromScratch() throws Exception {
        UUID folderId = createFolder("Root");
        String batchId = openBatch(folderId, 5, 1_000_000);

        MockMultipartFile badFile =
                new MockMultipartFile("file", "a.png", "application/x-msdownload", "bad".getBytes());
        mockMvc.perform(multipart("/ingest/batches/" + batchId + "/files").file(badFile)
                        .param("displayName", "a.png").with(officer()))
                .andExpect(status().isUnprocessableEntity());

        uploadPng(batchId, "a.png", null)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("INGESTED"));
    }

    @Test
    void reuploadOverATrashedCleanDocumentIsNotADuplicate() throws Exception {
        UUID folderId = createFolder("Root");
        Document trashed = new Document();
        trashed.setOriginalName("a.png");
        trashed.setMimeType("image/png");
        trashed.setSize(8);
        trashed.setSha256("f".repeat(64));
        trashed.setRelativePath("");
        trashed.setFolderId(folderId);
        trashed.setUploaderSub("someone");
        trashed.setUploaderUsername("someone");
        trashed.setStatus(DocumentStatus.CLEAN);
        trashed.setCreatedAt(OffsetDateTime.now());
        trashed.setDeletedAt(OffsetDateTime.now());
        documentRepository.save(trashed);

        String batchId = openBatch(folderId, 5, 1_000_000);
        uploadPng(batchId, "a.png", null)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("INGESTED"));

        assertThat(documentRepository.findAll()).hasSize(2);
    }

    // ---- Quyet dinh 3 muc 0: per-file size cap ---------------------------------

    @Test
    void fileAboveMaxFileSizeIsRejectedWithoutTouchingBatchCounters() throws Exception {
        UUID folderId = createFolder("Root");
        setConfig("ingest.max_file_size_bytes", 4);
        String batchId = openBatch(folderId, 5, 1_000_000);

        uploadPng(batchId, "a.png", null)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("FILE_TOO_LARGE"));

        UploadBatch batch = uploadBatchRepository.findById(UUID.fromString(batchId)).orElseThrow();
        assertThat(batch.getActualFileCount()).isZero();
        assertThat(batch.getActualTotalBytes()).isZero();
    }

    // ---- Quyet dinh 2 muc 0: crash between pending row and putObject ----------

    @Test
    void putObjectFailureLeavesNoUsableDuplicateAndRetrySucceeds() throws Exception {
        UUID folderId = createFolder("Root");
        String batchId = openBatch(folderId, 5, 1_000_000);

        doThrow(new RuntimeException("simulated SeaweedFS outage"))
                .doNothing()
                .when(objectStorageService)
                .putObject(anyString(), any(), anyString());

        uploadPng(batchId, "a.png", null)
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("STORAGE_WRITE_FAILED"));

        // the pending row must not block a retry as a "duplicate", and must not be 'clean'.
        assertThat(documentRepository.findAll()).noneMatch(d -> DocumentStatus.CLEAN.equals(d.getStatus()));

        uploadPng(batchId, "a.png", null)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("INGESTED"));
    }

    // ---- ClamAV unavailable: fail-closed, no document row at all --------------

    @Test
    void scannerUnavailableRejectsWithoutCreatingAnyDocumentRow() throws Exception {
        UUID folderId = createFolder("Root");
        String batchId = openBatch(folderId, 5, 1_000_000);
        when(clamdService.scanStream(any())).thenThrow(new ScannerUnavailableException("down"));

        uploadPng(batchId, "a.png", null)
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("SCANNER_UNAVAILABLE"));

        assertThat(documentRepository.findAll()).isEmpty();
    }

    // ---- Story 5 AC-3: admin allowlist change takes effect immediately ---------

    @Test
    void removingAFormatFromTheAllowlistTakesEffectOnTheVeryNextUpload() throws Exception {
        UUID folderId = createFolder("Root");
        String batchId = openBatch(folderId, 5, 1_000_000);

        mockMvc.perform(delete("/admin/allowed-file-formats/image/png").with(admin()))
                .andExpect(status().isNoContent());

        uploadPng(batchId, "a.png", null)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_FORMAT"));
    }

    // ---- RBAC ------------------------------------------------------------------

    // r2-business-parity.md ADR-1 / V35: only QUAN_TRI (R3) lacks
    // document.upload now - LANH_DAO (R2) is covered by
    // leaderCanOpenBatchAndReadLimits below (SPEC-r2-business-parity-01 AC-1, R-7).
    @Test
    void nonOfficerIsForbiddenOnIngestEndpoints() throws Exception {
        UUID folderId = createFolder("Root");

        mockMvc.perform(post("/ingest/batches")
                        .with(admin())
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(
                                Map.of("targetFolderId", folderId, "declaredFileCount", 1, "declaredTotalBytes", 10))))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/ingest/limits").with(admin())).andExpect(status().isForbidden());
    }

    @Test
    void leaderCanOpenBatchAndReadLimits() throws Exception {
        UUID folderId = createFolder("Root");

        mockMvc.perform(post("/ingest/batches")
                        .with(leader())
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(
                                Map.of("targetFolderId", folderId, "declaredFileCount", 1, "declaredTotalBytes", 10))))
                .andExpect(status().isCreated());
        mockMvc.perform(get("/ingest/limits").with(leader())).andExpect(status().isOk());
    }

    @Test
    void nonAdminIsForbiddenOnAllowedFormatsAdminEndpoints() throws Exception {
        mockMvc.perform(get("/admin/allowed-file-formats").with(officer())).andExpect(status().isForbidden());
        mockMvc.perform(delete("/admin/allowed-file-formats/image/png").with(officer()))
                .andExpect(status().isForbidden());
    }
}
