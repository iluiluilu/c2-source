package com.aismartstore.backend.personal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

/**
 * End-to-end coverage of personal-storage-quota.md §5 ("Integration test"):
 * real Postgres (Flyway-migrated through V11) behind MockMvc, so the
 * advisory-lock quota serialization and the owner+kind batch scoping are
 * exercised against a real DB, not mocked repositories (see
 * {@link PersonalServiceTest} for that unit coverage). {@link ClamdService}
 * and {@link ObjectStorageService} are mocked: neither a real clamd nor a
 * real SeaweedFS is part of this test's containers.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Testcontainers
class PersonalControllerIT {

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
    private AccountRepository accountRepository;

    @Autowired
    private DocumentRepository documentRepository;

    // digitization-pipeline-01-source-dispatch.md V18: digitization_source/
    // source_separation_job FK-reference document(id) with no cascade delete
    // (spec §2, copied as-is) - must be cleared before documentRepository.deleteAll()
    // now that DocumentsService.ingestBatchFile's listener can populate them.
    @Autowired
    private DigitizationSourceRepository digitizationSourceRepository;

    @Autowired
    private SourceSeparationJobRepository sourceSeparationJobRepository;

    @Autowired
    private StringRedisTemplate redisTemplate;

    private final ExecutorService concurrencyPool = Executors.newFixedThreadPool(4);

    @BeforeEach
    void resetState() {
        personalFileRepository.deleteAll();
        uploadBatchRepository.deleteAll();
        personalFolderRepository.deleteAll();
        accountRepository.deleteAll();
        digitizationSourceRepository.deleteAll();
        sourceSeparationJobRepository.deleteAll();
        documentRepository.deleteAll();
        try (var connection = redisTemplate.getConnectionFactory().getConnection()) {
            connection.serverCommands().flushAll();
        }

        when(clamdService.ping()).thenReturn(true);
        when(clamdService.scanStream(any())).thenReturn(ClamdService.ScanVerdict.ofClean());
        doNothing().when(objectStorageService).putObject(anyString(), any(), anyString(), anyString());
        doNothing().when(objectStorageService).deleteObject(anyString(), anyString());
        when(objectStorageService.getObject(anyString(), anyString())).thenAnswer(inv -> new ResponseInputStream<>(
                GetObjectResponse.builder().build(), new ByteArrayInputStream("stub-content".getBytes())));
    }

    @AfterEach
    void shutdownPool() {
        concurrencyPool.shutdown();
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

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(String json) throws Exception {
        return objectMapper.readValue(json, Map.class);
    }

    private String openBatch(JwtRequestPostProcessor jwt, Integer declaredFileCount, Long declaredTotalBytes)
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

    private ResultActions uploadFile(JwtRequestPostProcessor jwt, String batchId, String filename, byte[] content)
            throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", filename, "text/plain", content);
        return mockMvc.perform(multipart("/personal/batches/" + batchId + "/files").file(file)
                .param("displayName", filename).with(jwt));
    }

    private int uploadFileStatus(JwtRequestPostProcessor jwt, String batchId, String filename, byte[] content) {
        try {
            return uploadFile(jwt, batchId, filename, content).andReturn().getResponse().getStatus();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ---- SPEC-folder-upload-display-name (personal): name-check, unique, relativePath -----------------

    @Test
    void personalUploadKeepsRelativePathRejectsDuplicateNameAndNameCheckSeesIt() throws Exception {
        String sub = "owner-" + UUID.randomUUID();
        seedAccount(sub, 1_000_000);
        String batchId = openBatch(user(sub), 5, 100L);

        MockMultipartFile file = new MockMultipartFile("file", "hello.txt", "text/plain", "hi".getBytes());
        mockMvc.perform(multipart("/personal/batches/" + batchId + "/files").file(file)
                        .param("displayName", "hello.txt").param("relativePath", "Hồ sơ/2026").with(user(sub)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.file.relativePath").value("Hồ sơ/2026"))
                .andExpect(jsonPath("$.file.displayName").value("hello.txt"))
                .andExpect(jsonPath("$.file.storageKey").doesNotExist());

        // same (relativePath, lower(name)) -> 409, no second row
        MockMultipartFile again = new MockMultipartFile("file", "HELLO.txt", "text/plain", "hi".getBytes());
        mockMvc.perform(multipart("/personal/batches/" + batchId + "/files").file(again)
                        .param("displayName", "HELLO.txt").param("relativePath", "Hồ sơ/2026").with(user(sub)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DISPLAY_NAME_CONFLICT"));
        assertThat(personalFileRepository.findAll()).hasSize(1);

        // a different relative path is a different name
        mockMvc.perform(multipart("/personal/batches/" + batchId + "/files").file(again)
                        .param("displayName", "HELLO.txt").param("relativePath", "other").with(user(sub)))
                .andExpect(status().isCreated());

        String body = objectMapper.writeValueAsString(Map.of("files", List.of(
                Map.of("relativePath", "Hồ sơ/2026", "originalName", "Hello.TXT"),
                Map.of("relativePath", "", "originalName", "hello.txt"))));
        mockMvc.perform(post("/personal/name-check").contentType("application/json").content(body).with(user(sub)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].taken").value(true))
                .andExpect(jsonPath("$[1].taken").value(false));

        // the listing exposes displayName but never the storage key
        mockMvc.perform(get("/personal/files").with(user(sub)))
                .andExpect(jsonPath("$[0].storageKey").doesNotExist())
                .andExpect(jsonPath("$[0].displayName").exists());
    }

    @Test
    void personalNameCheckRejectsMoreThan1000FilesAndForeignFolders() throws Exception {
        String sub = "owner-" + UUID.randomUUID();
        seedAccount(sub, 1_000_000);
        List<Map<String, String>> files = new java.util.ArrayList<>();
        for (int i = 0; i < 1001; i++) {
            files.add(Map.of("relativePath", "", "originalName", "f" + i));
        }
        mockMvc.perform(post("/personal/name-check").contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("files", files))).with(user(sub)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/personal/name-check").contentType("application/json")
                        .content(objectMapper.writeValueAsString(
                                Map.of("folderId", UUID.randomUUID().toString(), "files", List.of())))
                        .with(user(sub)))
                .andExpect(status().isNotFound());
    }

    // ---- Story 2/3: happy path, batch -> file -> visible in files & quota ----

    @Test
    void ownerOpensBatchUploadsOneFileAndSeesItInFilesAndQuota() throws Exception {
        String sub = "owner-" + UUID.randomUUID();
        seedAccount(sub, 1_000_000);
        String batchId = openBatch(user(sub), 1, 100L);

        uploadFile(user(sub), batchId, "hello.txt", "hello world".getBytes())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("STORED"))
                .andExpect(jsonPath("$.file.originalName").value("hello.txt"));

        mockMvc.perform(get("/personal/files").with(user(sub)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].originalName").value("hello.txt"));

        mockMvc.perform(get("/personal/quota").with(user(sub)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.usedBytes").value(11))
                .andExpect(jsonPath("$.quotaBytes").value(1_000_000));
    }

    // ---- Story 1 AC-1: owner scoping - "existence must not leak", kể cả R3 ----

    @Test
    void anotherOwnersFolderBatchAndFileAllResolveTo404EvenForR3() throws Exception {
        String ownerA = "owner-a-" + UUID.randomUUID();
        String ownerB = "owner-b-" + UUID.randomUUID();
        seedAccount(ownerA, 1_000_000);
        seedAccount(ownerB, 1_000_000);

        String folderJson = mockMvc.perform(post("/personal/folders")
                        .with(user(ownerA))
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("name", "Ghi chú"))))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String folderId = (String) asMap(folderJson).get("id");

        String batchId = openBatch(user(ownerA), 1, 100L);
        String uploadJson = uploadFile(user(ownerA), batchId, "secret.txt", "top secret".getBytes())
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        @SuppressWarnings("unchecked")
        Map<String, Object> fileMap = (Map<String, Object>) asMap(uploadJson).get("file");
        String fileId = (String) fileMap.get("id");

        JwtRequestPostProcessor intruder = admin(ownerB); // R3, still owner-scoped like everyone else

        mockMvc.perform(patch("/personal/folders/" + folderId)
                        .with(intruder)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("name", "hacked"))))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/personal/batches/" + batchId).with(intruder)).andExpect(status().isNotFound());

        mockMvc.perform(get("/personal/files/" + fileId + "/content").with(intruder))
                .andExpect(status().isNotFound());
    }

    // ---- Story 3 AC-2/AC-4: quota exceeded mid-batch does not close the batch ----

    @Test
    void midBatchQuotaExceededRejectsOnlyTheOffendingFileKeepsPriorFilesAndLeavesBatchOpen() throws Exception {
        String sub = "owner-" + UUID.randomUUID();
        seedAccount(sub, 12L); // small quota: exactly 2x 5-byte files fit, a 3rd does not.
        // Declared estimate is deliberately low (server does not re-validate it
        // per file - the authoritative check is the per-file commit re-check).
        String batchId = openBatch(user(sub), 3, 1L);

        byte[] fiveBytes = "AAAAA".getBytes();

        String file1Json = uploadFile(user(sub), batchId, "1.txt", fiveBytes)
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        uploadFile(user(sub), batchId, "2.txt", fiveBytes).andExpect(status().isCreated());
        uploadFile(user(sub), batchId, "3.txt", fiveBytes)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("QUOTA_EXCEEDED"));

        assertThat(personalFileRepository.findByOwnerSubOrderByCreatedAtDesc(sub)).hasSize(2);

        mockMvc.perform(get("/personal/batches/" + batchId).with(user(sub)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"));

        @SuppressWarnings("unchecked")
        Map<String, Object> file1Map = (Map<String, Object>) asMap(file1Json).get("file");
        String file1Id = (String) file1Map.get("id");
        mockMvc.perform(get("/personal/files/" + file1Id + "/content").with(user(sub))).andExpect(status().isOk());
    }

    // ---- Story 3 AC-1: declared-total pre-check at open time ------------------

    @Test
    void openBatchWithDeclaredTotalAboveRemainingQuotaCreatesNoBatchRow() throws Exception {
        String sub = "owner-" + UUID.randomUUID();
        seedAccount(sub, 100L);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("declaredFileCount", 1);
        body.put("declaredTotalBytes", 101);
        mockMvc.perform(post("/personal/batches")
                        .with(user(sub))
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("QUOTA_EXCEEDED"));

        assertThat(uploadBatchRepository.findAll()).isEmpty();
    }

    // ---- Story 3 "Concurrency": 2 parallel uploads, exactly one wins --------

    @Test
    void concurrentUploadsExactlyOneSucceedsWhenCombinedSizeExceedsQuotaByOneByte() throws Exception {
        String sub = "owner-" + UUID.randomUUID();
        seedAccount(sub, 8L);
        String batchId = openBatch(user(sub), 2, 1L);

        byte[] fourBytes = "AAAA".getBytes(); // 4 bytes
        byte[] fiveBytes = "BBBBB".getBytes(); // 5 bytes -> combined 9 = quota(8) + 1

        CompletableFuture<Integer> f1 =
                CompletableFuture.supplyAsync(() -> uploadFileStatus(user(sub), batchId, "a.txt", fourBytes), concurrencyPool);
        CompletableFuture<Integer> f2 =
                CompletableFuture.supplyAsync(() -> uploadFileStatus(user(sub), batchId, "b.txt", fiveBytes), concurrencyPool);
        CompletableFuture.allOf(f1, f2).join();

        assertThat(List.of(f1.get(), f2.get())).containsExactlyInAnyOrder(201, 409);

        long totalStored = personalFileRepository.findByOwnerSubOrderByCreatedAtDesc(sub).stream()
                .mapToLong(PersonalFile::getSize)
                .sum();
        assertThat(totalStored).isLessThanOrEqualTo(8L);
    }

    // ---- Story 1 AC-4: personal files are invisible to the warehouse --------

    @Test
    void personalFilesNeverAppearInTheWarehouseDocumentsTable() throws Exception {
        String sub = "owner-" + UUID.randomUUID();
        seedAccount(sub, 1_000_000);
        String batchId = openBatch(user(sub), 1, 100L);

        uploadFile(user(sub), batchId, "private.txt", "not warehouse data".getBytes())
                .andExpect(status().isCreated());

        assertThat(documentRepository.findAll()).isEmpty();
    }

    // ---- FR-12: malware scan still runs for personal uploads ----------------

    @Test
    void infectedFileUploadIsRejectedWithNoPersonalFileRowCreated() throws Exception {
        String sub = "owner-" + UUID.randomUUID();
        seedAccount(sub, 1_000_000);
        String batchId = openBatch(user(sub), 1, 1000L);
        when(clamdService.scanStream(any())).thenReturn(ClamdService.ScanVerdict.infected("EICAR-TEST"));

        uploadFile(user(sub), batchId, "bad.txt", "hello".getBytes())
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("MALWARE_DETECTED"));

        assertThat(personalFileRepository.findAll()).isEmpty();
    }

    // ---- Story 4 AC-3: quota is a one-time snapshot, not tied live to role ---

    @Test
    void quotaStaysSnapshottedAcrossARoleChangeWithoutRecreatingTheAccountRow() throws Exception {
        String sub = "owner-" + UUID.randomUUID();
        seedAccount(sub, 12_345L);

        mockMvc.perform(get("/personal/quota").with(asRole(sub, "can-bo", "CAN_BO")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quotaBytes").value(12_345));

        // Same account row, but the caller now presents a different realm role -
        // the snapshotted quota must not silently recompute.
        mockMvc.perform(get("/personal/quota").with(asRole(sub, "quan-tri", "QUAN_TRI")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quotaBytes").value(12_345));

        assertThat(accountRepository.findById(sub).orElseThrow().getStorageQuotaBytes()).isEqualTo(12_345L);
    }
}
