package com.aismartstore.backend.personal;

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

import com.aismartstore.backend.accounts.Account;
import com.aismartstore.backend.accounts.AccountRepository;
import com.aismartstore.backend.digitization.DigitizationSourceRepository;
import com.aismartstore.backend.digitization.SourceSeparationJobRepository;
import com.aismartstore.backend.documents.DocumentRepository;
import com.aismartstore.backend.ingest.UploadBatchRepository;
import com.aismartstore.backend.scanning.ClamdService;
import com.aismartstore.backend.storage.ObjectStorageService;
import com.aismartstore.backend.upload.UploadSessionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.MessageDigest;
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
 * End-to-end coverage of chunked-file-upload-02-personal-chunked-upload.md §5
 * ("Integration test") for the personal domain's chunked-upload endpoint
 * ({@code POST /personal/batches/{id}/upload-sessions}). Complements
 * {@code UploadSessionControllerIT} (ingest side, spec 01) - the shared
 * {@code chunks}/{@code status}/{@code complete} endpoints are exercised
 * there and not re-tested exhaustively here, except where personal-specific
 * behaviour (dispatch, quota, ownership) needs its own coverage. Real
 * Postgres (so {@code PersonalService}'s advisory-lock quota check runs for
 * real); {@link ClamdService}/{@link ObjectStorageService} mocked.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Testcontainers
class PersonalUploadSessionControllerIT {

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
    private PersonalFileRepository personalFileRepository;

    @Autowired
    private PersonalFolderRepository personalFolderRepository;

    @Autowired
    private UploadBatchRepository uploadBatchRepository;

    @Autowired
    private UploadSessionRepository uploadSessionRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private DocumentRepository documentRepository;

    // digitization-pipeline-01-source-dispatch.md V18: FK-dependent tables must
    // be cleared before documentRepository.deleteAll() (see PersonalControllerIT).
    @Autowired
    private DigitizationSourceRepository digitizationSourceRepository;

    @Autowired
    private SourceSeparationJobRepository sourceSeparationJobRepository;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @BeforeEach
    void resetState() throws Exception {
        personalFileRepository.deleteAll();
        uploadSessionRepository.deleteAll();
        uploadBatchRepository.deleteAll();
        personalFolderRepository.deleteAll();
        accountRepository.deleteAll();
        digitizationSourceRepository.deleteAll();
        sourceSeparationJobRepository.deleteAll();
        documentRepository.deleteAll();
        try (var connection = redisTemplate.getConnectionFactory().getConnection()) {
            connection.serverCommands().flushAll();
        }

        setConfig("ingest.max_batch_size_bytes", 5L * 1024 * 1024 * 1024);
        setConfig("ingest.max_file_size_bytes", 2L * 1024 * 1024 * 1024);
        setConfig("ingest.max_files_per_batch", 500);
        setConfig("upload.chunk_threshold_bytes", 20L * 1024 * 1024);
        setConfig("upload.chunk_size_bytes", 8);

        when(clamdService.ping()).thenReturn(true);
        when(clamdService.scanStream(any())).thenReturn(ClamdService.ScanVerdict.ofClean());
        doNothing().when(objectStorageService).putObject(anyString(), any(), anyString(), anyString());
    }

    private static JwtRequestPostProcessor asRole(String sub, String realmRole, String springRole) {
        return jwt().jwt(builder -> builder.subject(sub)
                        .claim("preferred_username", sub)
                        .claim("realm_access", Map.of("roles", List.of(realmRole))))
                .authorities(com.aismartstore.backend.testsupport.RolePermissionFixtures.authoritiesFor(springRole));
    }

    private static JwtRequestPostProcessor user(String sub) {
        return asRole(sub, "can-bo", "CAN_BO");
    }

    private static JwtRequestPostProcessor admin(String sub) {
        return asRole(sub, "quan-tri", "QUAN_TRI");
    }

    private Account seedAccount(String sub, long quotaBytes) {
        Account row = new Account();
        row.setKeycloakUserId(sub);
        row.setStatus("active");
        row.setStorageQuotaBytes(quotaBytes);
        row.setCreatedAt(OffsetDateTime.now());
        row.setUpdatedAt(OffsetDateTime.now());
        return accountRepository.save(row);
    }

    private void setConfig(String key, Object value) throws Exception {
        mockMvc.perform(patch("/admin/system-config/" + key)
                        .with(admin("config-admin"))
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("value", value))))
                .andExpect(status().isOk());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(String json) throws Exception {
        return objectMapper.readValue(json, Map.class);
    }

    private String openBatch(JwtRequestPostProcessor jwt, int declaredFileCount, long declaredTotalBytes)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("declaredFileCount", declaredFileCount);
        body.put("declaredTotalBytes", declaredTotalBytes);
        String json = mockMvc.perform(post("/personal/batches")
                        .with(jwt)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return (String) asMap(json).get("batchId");
    }

    private Map<String, Object> initSession(
            JwtRequestPostProcessor jwt, String batchId, String originalName, long declaredSizeBytes, UUID folderId)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("originalName", originalName);
        body.put("declaredSizeBytes", declaredSizeBytes);
        body.put("folderId", folderId);
        body.put("mimeType", "text/plain");
        String json = mockMvc.perform(post("/personal/batches/" + batchId + "/upload-sessions")
                        .with(jwt)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return asMap(json);
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
    void fullChunkedUploadFlowAssemblesTheFileByteForByteAndStoresExactlyOnePersonalFile() throws Exception {
        String sub = "owner-" + UUID.randomUUID();
        seedAccount(sub, 1_000_000);
        String batchId = openBatch(user(sub), 1, 1_000_000);

        String folderJson = mockMvc.perform(post("/personal/folders")
                        .with(user(sub))
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("name", "Docs"))))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        UUID folderId = UUID.fromString((String) asMap(folderJson).get("id"));

        byte[] payload = "AAAAAAAABBBBBBBBCC".getBytes(); // 18 bytes, chunk size 8 -> 8, 8, 2 (uneven last chunk)
        Map<String, Object> opened = initSession(user(sub), batchId, "a.txt", payload.length, folderId);
        String sessionId = (String) opened.get("sessionId");
        assertThat(((Number) opened.get("chunkSizeBytes")).longValue()).isEqualTo(8);
        assertThat(((Number) opened.get("receivedBytes")).longValue()).isZero();

        uploadAllChunks(sessionId, payload, 8, user(sub));

        mockMvc.perform(post("/upload-sessions/" + sessionId + "/complete").with(user(sub)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("STORED"))
                .andExpect(jsonPath("$.file.folderId").value(folderId.toString()));

        assertThat(personalFileRepository.findByOwnerSubOrderByCreatedAtDesc(sub)).hasSize(1);
        assertThat(personalFileRepository.findByOwnerSubOrderByCreatedAtDesc(sub).get(0).getSha256())
                .isEqualTo(sha256Hex(payload));
    }

    // ---- AC-5 (integration): a WAREHOUSE batch and a PERSONAL batch running concurrently never cross-write ----

    @Test
    void aWarehouseAndAPersonalChunkedUploadRunningTogetherEachLandInTheirOwnStoreOnly() throws Exception {
        String sub = "owner-" + UUID.randomUUID();
        seedAccount(sub, 1_000_000);

        UUID warehouseFolderId = createWarehouseFolder(sub);
        String warehouseBatchId = openIngestBatch(sub, warehouseFolderId);
        String personalBatchId = openBatch(user(sub), 1, 1_000_000);

        byte[] warehousePayload = pngPayload(18);
        Map<String, Object> warehouseOpened = mockMvc.perform(post(
                        "/ingest/batches/" + warehouseBatchId + "/upload-sessions")
                        .with(user(sub))
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "originalName", "w.png",
                                "relativePath", "",
                                "declaredSizeBytes", warehousePayload.length,
                                "mimeType", "image/png"))))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString()
                .transform(this::asMapUnchecked);
        String warehouseSessionId = (String) warehouseOpened.get("sessionId");
        uploadAllChunks(warehouseSessionId, warehousePayload, 8, user(sub));

        byte[] personalPayload = "personal-file-bytes".getBytes();
        Map<String, Object> personalOpened = initSession(user(sub), personalBatchId, "p.txt", personalPayload.length, null);
        String personalSessionId = (String) personalOpened.get("sessionId");
        uploadAllChunks(personalSessionId, personalPayload, 8, user(sub));

        mockMvc.perform(post("/upload-sessions/" + warehouseSessionId + "/complete").with(user(sub)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("INGESTED"));
        mockMvc.perform(post("/upload-sessions/" + personalSessionId + "/complete").with(user(sub)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("STORED"));

        assertThat(documentRepository.findAll()).hasSize(1);
        assertThat(personalFileRepository.findAll()).hasSize(1);
        assertThat(personalFileRepository.findAll().get(0).getOriginalName()).isEqualTo("p.txt");
    }

    private Map<String, Object> asMapUnchecked(String json) {
        try {
            return asMap(json);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private UUID createWarehouseFolder(String sub) throws Exception {
        String json = mockMvc.perform(post("/folders")
                        .with(admin(sub))
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("name", "Root-" + UUID.randomUUID()))))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return UUID.fromString((String) asMap(json).get("id"));
    }

    private String openIngestBatch(String sub, UUID folderId) throws Exception {
        String json = mockMvc.perform(post("/ingest/batches")
                        .with(user(sub))
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "targetFolderId", folderId,
                                "declaredFileCount", 1,
                                "declaredTotalBytes", 1_000_000))))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return (String) asMap(json).get("batchId");
    }

    /** PNG signature (4 bytes) + padding - same fixture shape as {@code UploadSessionControllerIT}. */
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

    // ---- GET /personal/quota exposes chunkThresholdBytes ------------------------

    @Test
    void personalQuotaExposesTheChunkThresholdDefault() throws Exception {
        String sub = "owner-" + UUID.randomUUID();
        seedAccount(sub, 1_000_000);
        setConfig("upload.chunk_threshold_bytes", 20L * 1024 * 1024);

        mockMvc.perform(get("/personal/quota").with(user(sub)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chunkThresholdBytes").value(20 * 1024 * 1024));
    }

    // ---- ownership (AC-2 story 1, re-applied to a session backed by a personal batch) ----

    @Test
    void aPersonalUploadSessionCreatedByOneUserReturns403ForChunkStatusAndCompleteFromAnotherUser() throws Exception {
        String sub = "owner-" + UUID.randomUUID();
        String otherSub = "other-" + UUID.randomUUID();
        seedAccount(sub, 1_000_000);
        String batchId = openBatch(user(sub), 1, 1_000_000);

        byte[] payload = "hello-world".getBytes();
        Map<String, Object> opened = initSession(user(sub), batchId, "a.txt", payload.length, null);
        String sessionId = (String) opened.get("sessionId");

        mockMvc.perform(put("/upload-sessions/" + sessionId + "/chunks")
                        .param("offset", "0")
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .content(payload)
                        .with(user(otherSub)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("UPLOAD_SESSION_FORBIDDEN"));

        mockMvc.perform(get("/upload-sessions/" + sessionId).with(user(otherSub)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("UPLOAD_SESSION_FORBIDDEN"));

        mockMvc.perform(post("/upload-sessions/" + sessionId + "/complete").with(user(otherSub)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("UPLOAD_SESSION_FORBIDDEN"));
    }

    // ---- AC-7: quota is only evaluated at complete() time, never at init/chunk ----

    @Test
    void quotaNearFullAllowsInitAndAllChunksButRejectsOnlyAtCompleteWithNoRowOrCounterChange() throws Exception {
        String sub = "owner-" + UUID.randomUUID();
        seedAccount(sub, 4L); // tiny quota - the 11-byte payload below can never fit.
        String batchId = openBatch(user(sub), 1, 1L); // declaredTotalBytes deliberately low - no pre-check at open time either.

        byte[] payload = "hello-world".getBytes(); // 11 bytes > quota(4)

        // init must NOT check quota (AC-7) - succeeds despite the payload being
        // certain to blow the quota at complete() time.
        Map<String, Object> opened = initSession(user(sub), batchId, "a.txt", payload.length, null);
        String sessionId = (String) opened.get("sessionId");

        // every chunk PUT must NOT check quota either.
        uploadAllChunks(sessionId, payload, 8, user(sub));

        var batchBefore = uploadBatchRepository.findById(UUID.fromString(batchId)).orElseThrow();
        int actualFileCountBefore = batchBefore.getActualFileCount();
        long actualTotalBytesBefore = batchBefore.getActualTotalBytes();

        mockMvc.perform(post("/upload-sessions/" + sessionId + "/complete").with(user(sub)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("QUOTA_EXCEEDED"));

        assertThat(personalFileRepository.findByOwnerSubOrderByCreatedAtDesc(sub)).isEmpty();
        var batchAfter = uploadBatchRepository.findById(UUID.fromString(batchId)).orElseThrow();
        assertThat(batchAfter.getActualFileCount()).isEqualTo(actualFileCountBefore);
        assertThat(batchAfter.getActualTotalBytes()).isEqualTo(actualTotalBytesBefore);
    }
}
