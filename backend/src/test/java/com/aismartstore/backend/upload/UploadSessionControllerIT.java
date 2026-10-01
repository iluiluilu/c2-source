package com.aismartstore.backend.upload;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aismartstore.backend.digitization.DigitizationSourceRepository;
import com.aismartstore.backend.digitization.SourceSeparationJobRepository;
import com.aismartstore.backend.digitization.results.DigitizationRunRepository;
import com.aismartstore.backend.digitization.results.DocumentDigitizationStatusRepository;
import com.aismartstore.backend.documents.DocumentRepository;
import com.aismartstore.backend.ingest.UploadBatchRepository;
import com.aismartstore.backend.scanning.AllowedFileFormat;
import com.aismartstore.backend.scanning.AllowedFileFormatRepository;
import com.aismartstore.backend.scanning.ClamdService;
import com.aismartstore.backend.storage.ObjectStorageService;
import com.aismartstore.backend.warehouse.Warehouse;
import com.aismartstore.backend.warehouse.WarehouseFolder;
import com.aismartstore.backend.warehouse.WarehouseFolderRepository;
import com.aismartstore.backend.warehouse.WarehouseRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.MessageDigest;
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
import org.springframework.http.MediaType;
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
 * End-to-end coverage of chunked-file-upload-01-ingest-chunked-upload.md §5
 * ("Integration test") - same Testcontainers/MockMvc pattern as
 * {@code IngestControllerIT}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Testcontainers
class UploadSessionControllerIT {

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

    // digitization-pipeline-01-source-dispatch.md V18: FK-dependent tables must
    // be cleared before documentRepository.deleteAll() (see PersonalControllerIT).
    @Autowired
    private DigitizationSourceRepository digitizationSourceRepository;

    // digitization-results-store.md V25/V26: digitization_run/
    // document_digitization_status FK-reference digitization_source(id)/
    // document(id) with no cascade delete - must be cleared before those
    // repositories' deleteAll() below or the FK constraints fail.
    @Autowired
    private DigitizationRunRepository digitizationRunRepository;

    @Autowired
    private DocumentDigitizationStatusRepository documentDigitizationStatusRepository;

    @Autowired
    private SourceSeparationJobRepository sourceSeparationJobRepository;

    @Autowired
    private UploadBatchRepository uploadBatchRepository;

    @Autowired
    private UploadSessionRepository uploadSessionRepository;

    @Autowired
    private AllowedFileFormatRepository allowedFileFormatRepository;

    @Autowired
    private StringRedisTemplate redisTemplate;

    private static final long DEFAULT_MAX_CAPACITY_BYTES = 10_995_116_277_760L;

    /** PNG signature (4 bytes, matches the V10 allowlist seed) + padding, 18 bytes total - deliberately not a multiple of an 8-byte chunk size (last chunk = 2 bytes). */
    private static byte[] pngPayload(int totalLength) {
        byte[] bytes = new byte[totalLength];
        bytes[0] = (byte) 0x89;
        bytes[1] = 0x50;
        bytes[2] = 0x4E;
        bytes[3] = 0x47;
        for (int i = 4; i < totalLength; i++) {
            bytes[i] = (byte) (i % 251);
        }
        return bytes;
    }

    @BeforeEach
    void resetState() throws Exception {
        uploadSessionRepository.deleteAll();
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
        setConfig("upload.chunk_threshold_bytes", 20L * 1024 * 1024);
        setConfig("upload.chunk_size_bytes", 8);

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

    private static JwtRequestPostProcessor otherOfficer() {
        return asRole("officer-2", "can-bo", "CAN_BO");
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

    private Map<String, Object> initSession(String batchId, String originalName, long declaredSizeBytes)
            throws Exception {
        String json = mockMvc.perform(post("/ingest/batches/" + batchId + "/upload-sessions")
                        .with(officer())
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "originalName", originalName,
                                "displayName", originalName,
                                "relativePath", "",
                                "declaredSizeBytes", declaredSizeBytes,
                                "mimeType", "image/png"))))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        @SuppressWarnings("unchecked")
        Map<String, Object> parsed = objectMapper.readValue(json, Map.class);
        return parsed;
    }

    /** Uploads {@code payload} in chunks of {@code chunkSize}, sequentially, as the frontend would. */
    private void uploadAllChunks(String sessionId, byte[] payload, int chunkSize, JwtRequestPostProcessor as)
            throws Exception {
        int offset = 0;
        while (offset < payload.length) {
            int len = Math.min(chunkSize, payload.length - offset);
            byte[] chunk = java.util.Arrays.copyOfRange(payload, offset, offset + len);
            mockMvc.perform(put("/upload-sessions/" + sessionId + "/chunks")
                            .param("offset", String.valueOf(offset))
                            .contentType(MediaType.APPLICATION_OCTET_STREAM)
                            .content(chunk)
                            .with(as))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.receivedBytes").value(offset + len));
            offset += len;
        }
    }

    private static String sha256Hex(byte[] bytes) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(bytes);
        StringBuilder sb = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    // ---- full flow: init -> 3 uneven chunks -> complete ------------------------

    @Test
    void fullChunkedUploadFlowAssemblesTheFileByteForByteAndIngestsExactlyOneDocument() throws Exception {
        UUID folderId = createFolder("Root");
        String batchId = openBatch(folderId, 5, 1_000_000);

        byte[] payload = pngPayload(18); // chunk size 8 -> chunks of 8, 8, 2 (uneven last chunk)
        Map<String, Object> opened = initSession(batchId, "a.png", payload.length);
        String sessionId = (String) opened.get("sessionId");
        assertThat(((Number) opened.get("chunkSizeBytes")).longValue()).isEqualTo(8);
        assertThat(((Number) opened.get("receivedBytes")).longValue()).isZero();

        uploadAllChunks(sessionId, payload, 8, officer());

        mockMvc.perform(post("/upload-sessions/" + sessionId + "/complete").with(officer()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("INGESTED"));

        assertThat(documentRepository.findAll()).hasSize(1);
        assertThat(documentRepository.findAll().get(0).getSha256()).isEqualTo(sha256Hex(payload));
    }

    @Test
    void completeCalledTwiceReplaysTheSameResultAndDoesNotCreateASecondDocument() throws Exception {
        UUID folderId = createFolder("Root");
        String batchId = openBatch(folderId, 5, 1_000_000);

        byte[] payload = pngPayload(18);
        Map<String, Object> opened = initSession(batchId, "a.png", payload.length);
        String sessionId = (String) opened.get("sessionId");
        uploadAllChunks(sessionId, payload, 8, officer());

        mockMvc.perform(post("/upload-sessions/" + sessionId + "/complete").with(officer()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("INGESTED"));

        // Retried complete() (e.g. client lost the first response) - must not ingest twice.
        mockMvc.perform(post("/upload-sessions/" + sessionId + "/complete").with(officer()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("INGESTED"));

        assertThat(documentRepository.findAll()).hasSize(1);
    }

    // ---- SPEC-folder-upload-display-name: 409 at complete keeps the session (AC-14/AC-15) ------

    @Test
    void completeWithATakenDisplayNameIs409KeepsTheSessionAndACommitAgainNeedsNoNewChunks() throws Exception {
        UUID folderId = createFolder("Root");
        String batchId = openBatch(folderId, 5, 1_000_000);
        byte[] payload = pngPayload(18);

        // first file takes the name
        String firstSession = (String) initSession(batchId, "a.png", payload.length).get("sessionId");
        uploadAllChunks(firstSession, payload, 8, officer());
        mockMvc.perform(post("/upload-sessions/" + firstSession + "/complete").with(officer()))
                .andExpect(status().isCreated());

        // second one wants the same display name -> 409 at complete
        String sessionId = (String) initSession(batchId, "a.png", payload.length).get("sessionId");
        uploadAllChunks(sessionId, payload, 8, officer());
        mockMvc.perform(post("/upload-sessions/" + sessionId + "/complete").with(officer()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DISPLAY_NAME_CONFLICT"));
        assertThat(documentRepository.findAll()).hasSize(1);
        // the session is still OPEN with all bytes received
        mockMvc.perform(get("/upload-sessions/" + sessionId).with(officer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.receivedBytes").value(payload.length));

        // an invalid regenerated name is a 422 and still keeps the session
        mockMvc.perform(post("/upload-sessions/" + sessionId + "/complete").with(officer())
                        .contentType("application/json").content("{\"displayName\":\"other.png\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_DISPLAY_NAME"));

        // commit again with a fresh suffix, no chunk re-sent
        String suffixed = "a_20260930101530123-ab12cd.png";
        mockMvc.perform(post("/upload-sessions/" + sessionId + "/complete").with(officer())
                        .contentType("application/json").content("{\"displayName\":\"" + suffixed + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.document.displayName").value(suffixed));
        assertThat(documentRepository.findAll()).hasSize(2);
    }

    @Test
    void initRejectsAnOverlongOriginalNameBeforeAnyChunkAndABadDisplayName() throws Exception {
        UUID folderId = createFolder("Root");
        String batchId = openBatch(folderId, 5, 1_000_000);

        String tooLong = "a".repeat(509) + ".png"; // 513 code points
        mockMvc.perform(post("/ingest/batches/" + batchId + "/upload-sessions")
                        .with(officer()).contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "originalName", tooLong, "displayName", tooLong, "declaredSizeBytes", 10))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.reason").value("ORIGINAL_NAME_TOO_LONG"));

        mockMvc.perform(post("/ingest/batches/" + batchId + "/upload-sessions")
                        .with(officer()).contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "originalName", "a.png", "displayName", "b/c.png", "declaredSizeBytes", 10))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.reason").value("FORBIDDEN_CHAR"));
    }

    // ---- AC-5/AC-6: malware detected on the assembled file -----------------------

    @Test
    void malwareDetectedOnTheAssembledFileMatchesTheWholeFileErrorShapeAndCreatesNoCleanDocument() throws Exception {
        UUID folderId = createFolder("Root");
        String batchId = openBatch(folderId, 5, 1_000_000);
        when(clamdService.scanStream(any())).thenReturn(ClamdService.ScanVerdict.infected("EICAR-TEST"));

        byte[] payload = pngPayload(18);
        Map<String, Object> opened = initSession(batchId, "a.png", payload.length);
        String sessionId = (String) opened.get("sessionId");
        uploadAllChunks(sessionId, payload, 8, officer());

        mockMvc.perform(post("/upload-sessions/" + sessionId + "/complete").with(officer()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("MALWARE_DETECTED"));

        assertThat(documentRepository.findAll()).noneMatch(d -> "clean".equals(d.getStatus()));
        var batch = uploadBatchRepository.findById(UUID.fromString(batchId)).orElseThrow();
        assertThat(batch.getRejectedFileCount()).isEqualTo(1);

        // Retried complete() replays the same rejection, does not re-run the scanner.
        mockMvc.perform(post("/upload-sessions/" + sessionId + "/complete").with(officer()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("MALWARE_DETECTED"));
        assertThat(batch.getRejectedFileCount())
                .isEqualTo(uploadBatchRepository.findById(UUID.fromString(batchId)).orElseThrow().getRejectedFileCount());
    }

    // ---- AC-7: ownership -----------------------------------------------------------

    @Test
    void aSessionCreatedByOneUserReturns403ForChunkStatusAndCompleteFromAnotherUser() throws Exception {
        UUID folderId = createFolder("Root");
        String batchId = openBatch(folderId, 5, 1_000_000);

        byte[] payload = pngPayload(18);
        Map<String, Object> opened = initSession(batchId, "a.png", payload.length);
        String sessionId = (String) opened.get("sessionId");

        mockMvc.perform(put("/upload-sessions/" + sessionId + "/chunks")
                        .param("offset", "0")
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .content(payload)
                        .with(otherOfficer()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("UPLOAD_SESSION_FORBIDDEN"));

        mockMvc.perform(get("/upload-sessions/" + sessionId).with(otherOfficer()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("UPLOAD_SESSION_FORBIDDEN"));

        mockMvc.perform(post("/upload-sessions/" + sessionId + "/complete").with(otherOfficer()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("UPLOAD_SESSION_FORBIDDEN"));
    }

    // ---- limits --------------------------------------------------------------------

    @Test
    void ingestLimitsExposesTheChunkThresholdDefault() throws Exception {
        setConfig("upload.chunk_threshold_bytes", 20L * 1024 * 1024);

        mockMvc.perform(get("/ingest/limits").with(officer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chunkThresholdBytes").value(20 * 1024 * 1024));
    }
}
