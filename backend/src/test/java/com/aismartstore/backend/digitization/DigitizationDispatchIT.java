package com.aismartstore.backend.digitization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aismartstore.backend.config.QueueProperties;
import com.aismartstore.backend.digitization.results.DigitizationRun;
import com.aismartstore.backend.digitization.results.DigitizationRunRepository;
import com.aismartstore.backend.digitization.results.DocumentDigitizationStatusRepository;
import com.aismartstore.backend.documents.Document;
import com.aismartstore.backend.documents.DocumentRepository;
import com.aismartstore.backend.documents.DocumentStatus;
import com.aismartstore.backend.ingest.UploadBatchRepository;
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
import org.springframework.amqp.rabbit.core.RabbitTemplate;
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
 * digitization-pipeline-01-source-dispatch.md §5, "Integration test" -
 * Testcontainers Postgres + RabbitMQ, same MockMvc/JWT scaffolding as {@code
 * IngestControllerIT}. {@link ObjectStorageService} is mocked (no SeaweedFS
 * container in this project's Testcontainers convention - see {@code
 * SystemConfigControllerIT}'s Javadoc).
 *
 * <p>digitization-pipeline-04-video-no-separation.md §5: video/* no longer
 * goes through FFmpeg source separation at intake - {@code
 * videoIngest_dispatchesOriginalStraightToVideoQueue} below replaces the old
 * ffmpeg-fixture-driven split test (no more real {@code ffmpeg} subprocess
 * or {@code SourceSeparationJobRunner} in this suite).
 *
 * <p>Not covered here (documented gap, not silently skipped): the spec's
 * "RabbitMQ down when publishing, then recovers" scenario - it needs to
 * stop/start the broker container mid-test, which is materially heavier/
 * flakier than the rest of this suite; the same code path (best-effort
 * publish catches {@code AmqpException}, leaves {@code dispatch_status=
 * pending}) is exercised at the unit level in {@code
 * DigitizationDispatchServiceTest#publishExceptionIsSwallowedAndKeepsPending}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = "app.scheduling.enabled=false")
@AutoConfigureMockMvc
@Testcontainers
class DigitizationDispatchIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
            .withDatabaseName("aismartstore")
            .withUsername("aismartstore")
            .withPassword("aismartstore");

    @Container
    static GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @Container
    static GenericContainer<?> rabbit = new GenericContainer<>(DockerImageName.parse("rabbitmq:3.13-management-alpine"))
            .withExposedPorts(5672);

    @DynamicPropertySource
    static void containerProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.rabbitmq.host", rabbit::getHost);
        registry.add("spring.rabbitmq.port", () -> rabbit.getMappedPort(5672));
        registry.add("spring.rabbitmq.username", () -> "guest");
        registry.add("spring.rabbitmq.password", () -> "guest");
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
    private UploadBatchRepository uploadBatchRepository;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private SourceSeparationJobRepository sourceSeparationJobRepository;

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
    private DigitizationDispatchRetryJob digitizationDispatchRetryJob;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private QueueProperties queueProperties;

    private static final long DEFAULT_MAX_CAPACITY_BYTES = 10_995_116_277_760L;
    private static final byte[] PNG_BYTES = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
    private static final byte[] DOC_BYTES = {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, 0, 0, 0, 0};
    private static final byte[] MP4_BYTES = {0, 0, 0, 0x18, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm'};

    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.jdbc.core.JdbcTemplate shedlockJdbc;

    @BeforeEach
    void resetState() throws Exception {
        // backend-multi-instance: the context's own scheduler already ticked this job at startup and holds its
        // ShedLock row for lockAtLeastFor; expire it (UPDATE, not DELETE: ShedLock caches that the row exists) so the direct call below runs the job body.
        shedlockJdbc.update("UPDATE shedlock SET lock_until = timezone('utc', now()) - interval '1 minute'");
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

        redisTemplate.delete("ingest:allowlist");
        when(clamdService.scanStream(any())).thenReturn(ClamdService.ScanVerdict.ofClean());
        doNothing().when(objectStorageService).putObject(anyString(), any(), anyString());

        drainQueue(queueProperties.ocrName());
        drainQueue(queueProperties.sttName());
        drainQueue(queueProperties.videoName());
    }

    private void drainQueue(String queueName) {
        while (rabbitTemplate.receive(queueName, 50) != null) {
            // discard leftovers from a previous test.
        }
    }

    private static JwtRequestPostProcessor officer() {
        return jwt().jwt(builder -> builder.subject("officer-1")
                        .claim("preferred_username", "officer-1")
                        .claim("realm_access", Map.of("roles", List.of("can-bo"))))
                .authorities(com.aismartstore.backend.testsupport.RolePermissionFixtures.authoritiesFor("CAN_BO"));
    }

    private static JwtRequestPostProcessor admin() {
        return jwt().jwt(builder -> builder.subject("admin-1")
                        .claim("preferred_username", "admin-1")
                        .claim("realm_access", Map.of("roles", List.of("quan-tri"))))
                .authorities(com.aismartstore.backend.testsupport.RolePermissionFixtures.authoritiesFor("QUAN_TRI"));
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

    private String openBatch(UUID folderId) throws Exception {
        String json = mockMvc.perform(post("/ingest/batches")
                        .with(officer())
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "targetFolderId", folderId, "declaredFileCount", 5, "declaredTotalBytes", 50_000_000))))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        @SuppressWarnings("unchecked")
        Map<String, Object> parsed = objectMapper.readValue(json, Map.class);
        return (String) parsed.get("batchId");
    }

    private UUID uploadFile(String batchId, String filename, String contentType, byte[] bytes) throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", filename, contentType, bytes);
        String json = mockMvc.perform(
                        multipart("/ingest/batches/" + batchId + "/files").file(file)
                                .param("displayName", filename).with(officer()))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        @SuppressWarnings("unchecked")
        Map<String, Object> parsed = objectMapper.readValue(json, Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> document = (Map<String, Object>) parsed.get("document");
        return UUID.fromString((String) document.get("id"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> receive(String queueName) {
        Object payload = rabbitTemplate.receiveAndConvert(queueName, 5_000);
        assertThat(payload).as("message on queue " + queueName).isNotNull();
        return (Map<String, Object>) payload;
    }

    // ---- image/png: direct dispatch (Story 2) --------------------------------

    @Test
    void imageIngest_createsPublishedOriginalSource_andPublishesToOcrQueue() throws Exception {
        UUID folderId = createFolder("Root");
        String batchId = openBatch(folderId);

        UUID documentId = uploadFile(batchId, "photo.png", "image/png", PNG_BYTES);

        DigitizationSource source = digitizationSourceRepository.findById(documentId).orElseThrow();
        assertThat(source.getId()).isEqualTo(documentId);
        assertThat(source.getComponentType()).isEqualTo(DigitizationSource.COMPONENT_ORIGINAL);
        assertThat(source.getDispatchStatus()).isEqualTo(DigitizationSource.DISPATCH_PUBLISHED);

        Map<String, Object> message = receive(queueProperties.ocrName());
        assertThat(message.get("fileId")).isEqualTo(documentId.toString());
        assertThat(message.get("sourceId")).isEqualTo(documentId.toString());
        assertThat(message.get("originalFileType")).isEqualTo("image/png");
        assertThat(message.get("processFileType")).isEqualTo("image/png");
    }

    // ---- application/msword: out of digitization scope -----------------------

    @Test
    void docxIngest_createsNothing_publishesNothing() throws Exception {
        UUID folderId = createFolder("Root");
        String batchId = openBatch(folderId);

        UUID documentId = uploadFile(batchId, "report.doc", "application/msword", DOC_BYTES);

        assertThat(digitizationSourceRepository.findById(documentId)).isEmpty();
        assertThat(sourceSeparationJobRepository.existsByDocumentId(documentId)).isFalse();
        assertThat(rabbitTemplate.receive(queueProperties.ocrName(), 200)).isNull();
        assertThat(rabbitTemplate.receive(queueProperties.sttName(), 200)).isNull();
    }

    // ---- video/mp4: no more source separation ---------------------------------

    /**
     * digitization-pipeline-04-video-no-separation.md §5, integration test
     * bullet 3: video joins the {@code original} branch like audio/image/
     * PDF - 1 {@code digitization_source} row (id = documentId), exactly 1
     * message on {@code q.video}, 0 on {@code q.stt}, no {@code
     * source_separation_job} row, and the {@code task='video'}
     * {@code digitization_run} is {@code queued}.
     */
    @Test
    void videoIngest_dispatchesOriginalStraightToVideoQueue() throws Exception {
        UUID folderId = createFolder("Root");
        String batchId = openBatch(folderId);

        UUID documentId = uploadFile(batchId, "clip.mp4", "video/mp4", MP4_BYTES);

        DigitizationSource source = digitizationSourceRepository.findById(documentId).orElseThrow();
        assertThat(source.getComponentType()).isEqualTo(DigitizationSource.COMPONENT_ORIGINAL);
        assertThat(source.getMimeType()).isEqualTo("video/mp4");
        assertThat(source.isDispatchable()).isTrue();
        assertThat(source.getDispatchStatus()).isEqualTo(DigitizationSource.DISPATCH_PUBLISHED);

        assertThat(sourceSeparationJobRepository.existsByDocumentId(documentId)).isFalse();

        Map<String, Object> videoMessage = receive(queueProperties.videoName());
        assertThat(videoMessage.get("fileId")).isEqualTo(documentId.toString());
        assertThat(videoMessage.get("sourceId")).isEqualTo(documentId.toString());
        assertThat(videoMessage.get("originalFileType")).isEqualTo("video/mp4");
        assertThat(videoMessage.get("processFileType")).isEqualTo("video/mp4");

        DigitizationRun run = digitizationRunRepository
                .findActiveByDocumentAndTask(documentId, DigitizationRun.TASK_VIDEO)
                .orElseThrow();
        assertThat(run.getProcessingStatus()).isEqualTo(DigitizationRun.PROCESSING_QUEUED);

        assertThat(rabbitTemplate.receive(queueProperties.sttName(), 200)).isNull();
        assertThat(rabbitTemplate.receive(queueProperties.ocrName(), 200)).isNull();
    }

    // ---- §4.5 step 2: reconciliation for a document the listener never saw ---

    @Test
    void reconciliationJob_rescuesDocumentTheListenerNeverSaw() {
        UUID folderId = UUID.randomUUID(); // not FK-checked on document directly in this table.
        Document document = new Document();
        document.setOriginalName("missed.png");
        document.setMimeType("image/png");
        document.setSize(8);
        document.setSha256("a".repeat(64));
        document.setStorageKey("documents/missed-key");
        document.setRelativePath("");
        document.setFolderId(seedFolderId());
        document.setUploaderSub("officer-1");
        document.setUploaderUsername("officer-1");
        document.setStatus(DocumentStatus.CLEAN);
        // §4.5 step 2 requires created_at older than the 2-minute grace window.
        document.setCreatedAt(OffsetDateTime.now().minusMinutes(10));
        document = documentRepository.save(document);

        digitizationDispatchRetryJob.run();

        DigitizationSource source = digitizationSourceRepository.findById(document.getId()).orElseThrow();
        assertThat(source.getDispatchStatus()).isEqualTo(DigitizationSource.DISPATCH_PUBLISHED);
        Map<String, Object> message = receive(queueProperties.ocrName());
        assertThat(message.get("fileId")).isEqualTo(document.getId().toString());
    }

    /**
     * digitization-pipeline-04-video-no-separation.md §4.3/§5, "IT đối soát":
     * a video document stuck with a stale (pre-migration) {@code
     * source_separation_job('queued')} row and no {@code digitization_source}
     * - once {@code findMissedForDigitization} drops the {@code NOT EXISTS
     * source_separation_job} filter, the reconciliation job dispatches it
     * fresh through the new {@code q.video} path.
     */
    @Test
    void reconciliationJob_rescuesVideoDocumentWithStaleSeparationJob() {
        Document document = new Document();
        document.setOriginalName("stale-clip.mp4");
        document.setMimeType("video/mp4");
        document.setSize(8);
        document.setSha256("b".repeat(64));
        document.setStorageKey("documents/stale-clip-key");
        document.setRelativePath("");
        document.setFolderId(seedFolderId());
        document.setUploaderSub("officer-1");
        document.setUploaderUsername("officer-1");
        document.setStatus(DocumentStatus.CLEAN);
        // §4.5 step 2 requires created_at older than the 2-minute grace window.
        document.setCreatedAt(OffsetDateTime.now().minusMinutes(10));
        document = documentRepository.save(document);

        SourceSeparationJob staleJob = new SourceSeparationJob();
        staleJob.setDocumentId(document.getId());
        staleJob.setStatus(SourceSeparationJob.STATUS_QUEUED);
        OffsetDateTime now = OffsetDateTime.now();
        staleJob.setCreatedAt(now);
        staleJob.setUpdatedAt(now);
        sourceSeparationJobRepository.save(staleJob);

        digitizationDispatchRetryJob.run();

        DigitizationSource source = digitizationSourceRepository.findById(document.getId()).orElseThrow();
        assertThat(source.getComponentType()).isEqualTo(DigitizationSource.COMPONENT_ORIGINAL);
        assertThat(source.getDispatchStatus()).isEqualTo(DigitizationSource.DISPATCH_PUBLISHED);

        Map<String, Object> message = receive(queueProperties.videoName());
        assertThat(message.get("fileId")).isEqualTo(document.getId().toString());
        assertThat(message.get("sourceId")).isEqualTo(document.getId().toString());
    }

    private UUID seedFolderId() {
        try {
            return createFolder("ReconciliationRoot");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
