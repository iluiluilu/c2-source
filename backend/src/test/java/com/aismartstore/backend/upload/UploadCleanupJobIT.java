package com.aismartstore.backend.upload;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.aismartstore.backend.digitization.DigitizationSourceRepository;
import com.aismartstore.backend.digitization.SourceSeparationJobRepository;
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
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
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
 * End-to-end coverage of chunked-file-upload-03-session-cleanup.md §5
 * ("Integration test") - same Testcontainers/MockMvc pattern as {@code
 * UploadSessionControllerIT}/{@code PersonalUploadSessionControllerIT}, plus
 * the real {@link UploadCleanupJob} bean invoked directly (never waiting on
 * the real {@code fixedDelay}, per §5 "Cấu hình bổ sung").
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Testcontainers
class UploadCleanupJobIT {

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

    @Autowired
    private UploadCleanupJob uploadCleanupJob;

    private static final long DEFAULT_MAX_CAPACITY_BYTES = 10_995_116_277_760L;
    private static final String OFFICER_SUB = "officer-1";
    private static final String OFFICER_USERNAME = "officer-1";

    private final List<Path> tempFilesToCleanUp = new ArrayList<>();

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
        digitizationSourceRepository.deleteAll();
        sourceSeparationJobRepository.deleteAll();
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

    @AfterEach
    void cleanupTempFiles() throws IOException {
        for (Path p : tempFilesToCleanUp) {
            Files.deleteIfExists(p);
        }
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
        return asRole(OFFICER_SUB, "can-bo", "CAN_BO");
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

    private UUID openBatch(UUID folderId, int declaredFileCount, long declaredTotalBytes) throws Exception {
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
        return UUID.fromString((String) parsed.get("batchId"));
    }

    private UUID initSession(UUID batchId, String originalName, long declaredSizeBytes) throws Exception {
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
        return UUID.fromString((String) parsed.get("sessionId"));
    }

    /** Directly persists an {@code upload_session} row, bypassing the REST init flow, for fixtures the protocol itself can't produce (e.g. a COMPLETED row with a past expires_at). */
    private UploadSession saveSession(UUID batchId, String status, OffsetDateTime expiresAt, String tempFilePath) {
        UploadSession session = new UploadSession();
        session.setBatchId(batchId);
        session.setOriginalName("fixture.txt");
        session.setRelativePath("");
        session.setDeclaredSizeBytes(10);
        session.setReceivedBytes(UploadSessionStatus.OPEN.equals(status) ? 0 : 10);
        session.setChunkSizeBytes(8);
        session.setTempFilePath(tempFilePath);
        session.setStatus(status);
        session.setCreatedBySub(OFFICER_SUB);
        session.setCreatedByUsername(OFFICER_USERNAME);
        OffsetDateTime now = OffsetDateTime.now();
        session.setCreatedAt(now);
        session.setUpdatedAt(now);
        session.setExpiresAt(expiresAt);
        return uploadSessionRepository.save(session);
    }

    private Path newRealTempFile() throws IOException {
        Path path = Files.createTempFile("cleanup-it-", ".tmp");
        Files.writeString(path, "fixture content");
        tempFilesToCleanUp.add(path);
        return path;
    }

    private void expireNow(UUID sessionId) {
        UploadSession session = uploadSessionRepository.findById(sessionId).orElseThrow();
        session.setExpiresAt(OffsetDateTime.now().minusMinutes(1));
        uploadSessionRepository.save(session);
    }

    // ---- AC-1: an overdue OPEN session is flipped to EXPIRED and its temp file is removed ----

    @Test
    void anOverdueOpenSessionIsExpiredAndItsTempFileIsDeletedFromDisk() throws Exception {
        UUID folderId = createFolder("Root");
        UUID batchId = openBatch(folderId, 5, 1_000_000);
        UUID sessionId = initSession(batchId, "a.png", 18);

        UploadSession before = uploadSessionRepository.findById(sessionId).orElseThrow();
        Path tempFile = Path.of(before.getTempFilePath());
        assertThat(Files.exists(tempFile)).isTrue();

        expireNow(sessionId);

        uploadCleanupJob.run();

        UploadSession after = uploadSessionRepository.findById(sessionId).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(UploadSessionStatus.EXPIRED);
        assertThat(Files.exists(tempFile)).isFalse();
    }

    // ---- AC-2: a session renewed by a real chunk PUT is left alone ----------------

    @Test
    void aSessionRenewedByARealChunkPutIsNotTouchedByTheJob() throws Exception {
        UUID folderId = createFolder("Root");
        UUID batchId = openBatch(folderId, 5, 1_000_000);
        byte[] payload = pngPayload(18); // chunk size 8 -> first chunk is 8 bytes
        UUID sessionId = initSession(batchId, "a.png", payload.length);

        byte[] firstChunk = java.util.Arrays.copyOfRange(payload, 0, 8);
        mockMvc.perform(put("/upload-sessions/" + sessionId + "/chunks")
                        .param("offset", "0")
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .content(firstChunk)
                        .with(officer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.receivedBytes").value(8));

        uploadCleanupJob.run();

        UploadSession after = uploadSessionRepository.findById(sessionId).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(UploadSessionStatus.OPEN);
        assertThat(after.getReceivedBytes()).isEqualTo(8);
        assertThat(after.getExpiresAt()).isAfter(OffsetDateTime.now());
    }

    // ---- AC-3: COMPLETED/ABORTED sessions with a past expires_at are ignored ------

    @Test
    void completedAndAbortedSessionsWithAPastExpiresAtAreNeverTouched() throws Exception {
        UUID folderId = createFolder("Root");
        UUID batchId = openBatch(folderId, 5, 1_000_000);

        Path completedFile = newRealTempFile();
        Path abortedFile = newRealTempFile();
        UploadSession completed = saveSession(
                batchId, UploadSessionStatus.COMPLETED, OffsetDateTime.now().minusHours(1), completedFile.toString());
        UploadSession aborted = saveSession(
                batchId, UploadSessionStatus.ABORTED, OffsetDateTime.now().minusHours(1), abortedFile.toString());

        uploadCleanupJob.run();

        assertThat(uploadSessionRepository.findById(completed.getId()).orElseThrow().getStatus())
                .isEqualTo(UploadSessionStatus.COMPLETED);
        assertThat(uploadSessionRepository.findById(aborted.getId()).orElseThrow().getStatus())
                .isEqualTo(UploadSessionStatus.ABORTED);
        // Never scanned in the first place (status != OPEN) - the temp files
        // backing these two rows must be left alone.
        assertThat(Files.exists(completedFile)).isTrue();
        assertThat(Files.exists(abortedFile)).isTrue();
    }

    // ---- AC-4: continuation of AC-1 - chunk/complete on the now-EXPIRED session -----

    @Test
    void afterTheJobExpiresASessionBothChunkPutAndCompleteReturn409UploadSessionNotOpen() throws Exception {
        UUID folderId = createFolder("Root");
        UUID batchId = openBatch(folderId, 5, 1_000_000);
        UUID sessionId = initSession(batchId, "a.png", 18);
        expireNow(sessionId);

        uploadCleanupJob.run();
        assertThat(uploadSessionRepository.findById(sessionId).orElseThrow().getStatus())
                .isEqualTo(UploadSessionStatus.EXPIRED);

        mockMvc.perform(put("/upload-sessions/" + sessionId + "/chunks")
                        .param("offset", "0")
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .content(pngPayload(18))
                        .with(officer()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("UPLOAD_SESSION_NOT_OPEN"));

        mockMvc.perform(post("/upload-sessions/" + sessionId + "/complete").with(officer()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("UPLOAD_SESSION_NOT_OPEN"));
    }

    // ---- AC-6: exactly one INFO log line per run, with accurate counters ----------

    @Test
    void logsExactlyOneInfoLineWithAccurateScannedExpiredAndTempFileCounters() throws Exception {
        UUID folderId = createFolder("Root");
        UUID batchId = openBatch(folderId, 5, 1_000_000);

        // 1) a real temp file -> DELETED
        Path deletable = newRealTempFile();
        saveSession(batchId, UploadSessionStatus.OPEN, OffsetDateTime.now().minusMinutes(5), deletable.toString());

        // 2) a path that never existed -> ALREADY_MISSING
        Path missing = Path.of(System.getProperty("java.io.tmpdir"), "cleanup-it-missing-" + UUID.randomUUID());
        saveSession(batchId, UploadSessionStatus.OPEN, OffsetDateTime.now().minusMinutes(5), missing.toString());

        // 3) a non-empty directory in place of a file -> Files.deleteIfExists
        // throws DirectoryNotEmptyException -> DELETE_FAILED (a real, non-mocked
        // I/O failure, same technique as UploadSessionServiceTest).
        Path dir = Files.createTempDirectory("cleanup-it-dir-");
        Files.createFile(dir.resolve("child.tmp"));
        tempFilesToCleanUp.add(dir.resolve("child.tmp"));
        tempFilesToCleanUp.add(dir);
        saveSession(batchId, UploadSessionStatus.OPEN, OffsetDateTime.now().minusMinutes(5), dir.toString());

        var logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(UploadCleanupJob.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            uploadCleanupJob.run();
        } finally {
            logger.detachAppender(appender);
        }

        List<String> infoMessages = appender.list.stream()
                .filter(e -> e.getLevel() == ch.qos.logback.classic.Level.INFO)
                .map(ILoggingEvent::getFormattedMessage)
                .filter(m -> m.startsWith("upload_session cleanup:"))
                .toList();
        assertThat(infoMessages).hasSize(1);
        String message = infoMessages.get(0);
        assertThat(message).contains("scanned=3");
        assertThat(message).contains("expired=3");
        assertThat(message).contains("tempFilesDeleted=1");
        assertThat(message).contains("tempFilesAlreadyMissing=1");
        assertThat(message).contains("tempFileDeleteErrors=1");
    }

    // ---- Edge case: more eligible sessions than the job's per-run page size -------

    @Test
    void moreEligibleSessionsThanThePerRunPageSizeAreDrainedOverTwoConsecutiveRuns() throws Exception {
        UUID folderId = createFolder("Root");
        UUID batchId = openBatch(folderId, 5, 1_000_000);

        int total = 501; // one more than UploadCleanupJob's 500-row page size
        for (int i = 0; i < total; i++) {
            // Not real files on disk - deleteTempFile() will just report
            // ALREADY_MISSING for every one of them, which is fine: this case
            // is about the job not throwing and eventually draining every
            // eligible row across runs, not about disk I/O.
            saveSession(
                    batchId,
                    UploadSessionStatus.OPEN,
                    OffsetDateTime.now().minusMinutes(5),
                    "/tmp/cleanup-it-nonexistent-" + i);
        }

        List<UUID> stillOpenBefore = uploadSessionRepository
                .findByStatusAndExpiresAtBeforeOrderByExpiresAtAsc(
                        UploadSessionStatus.OPEN, OffsetDateTime.now(), org.springframework.data.domain.PageRequest.of(0, 1000))
                .stream()
                .map(UploadSession::getId)
                .toList();
        assertThat(stillOpenBefore).hasSize(total);

        uploadCleanupJob.run();
        long expiredAfterFirstRun = uploadSessionRepository.findAll().stream()
                .filter(s -> UploadSessionStatus.EXPIRED.equals(s.getStatus()))
                .count();
        assertThat(expiredAfterFirstRun).isEqualTo(500);

        uploadCleanupJob.run();
        long expiredAfterSecondRun = uploadSessionRepository.findAll().stream()
                .filter(s -> UploadSessionStatus.EXPIRED.equals(s.getStatus()))
                .count();
        assertThat(expiredAfterSecondRun).isEqualTo(total);
    }
}
