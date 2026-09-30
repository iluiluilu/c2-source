package com.aismartstore.backend.digitization.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aismartstore.backend.digitization.DigitizationSource;
import com.aismartstore.backend.digitization.DigitizationSourceRepository;
import com.aismartstore.backend.digitization.results.ContentSegment;
import com.aismartstore.backend.digitization.results.ContentSegmentRepository;
import com.aismartstore.backend.digitization.results.DigitizationRun;
import com.aismartstore.backend.digitization.results.DigitizationRunLifecycleService;
import com.aismartstore.backend.digitization.results.DigitizationRunRepository;
import com.aismartstore.backend.digitization.results.DocumentDigitizationStatusRepository;
import com.aismartstore.backend.digitization.results.NewSegment;
import com.aismartstore.backend.digitization.results.NewSoundEvent;
import com.aismartstore.backend.digitization.results.SegmentRevisionRepository;
import com.aismartstore.backend.digitization.results.SoundEventRepository;
import com.aismartstore.backend.documents.Document;
import com.aismartstore.backend.documents.DocumentRepository;
import com.aismartstore.backend.documents.DocumentStatus;
import com.aismartstore.backend.storage.ObjectStorageService;
import com.aismartstore.backend.testsupport.RolePermissionFixtures;
import com.aismartstore.backend.warehouse.WarehouseFolder;
import com.aismartstore.backend.warehouse.WarehouseFolderRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

/**
 * digitization-review-01-ocr.md §5 "Integration test" (Story 1-4, 8): {@code
 * ReviewController} end to end - real Postgres (Flyway through {@code V28}),
 * MockMvc, same pattern as {@code NotesControllerIT}.
 *
 * <p>NOTE: like every other Testcontainers-based test in this module, this
 * cannot currently be executed in this sandbox (V21's {@code setval} against
 * an empty {@code audit_log} - see that migration's comment / the task's
 * known-issue note). It is exercised by a normal CI/dev environment with a
 * populated or otherwise-compatible Postgres.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Testcontainers
class ReviewControllerIT {

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
    private DigitizationSourceRepository digitizationSourceRepository;

    @Autowired
    private DigitizationRunRepository digitizationRunRepository;

    @Autowired
    private ContentSegmentRepository contentSegmentRepository;

    @Autowired
    private SegmentRevisionRepository segmentRevisionRepository;

    @Autowired
    private SoundEventRepository soundEventRepository;

    @Autowired
    private DocumentDigitizationStatusRepository documentDigitizationStatusRepository;

    @Autowired
    private DigitizationRunLifecycleService lifecycleService;

    private UUID folderId;

    @BeforeEach
    void resetState() {
        segmentRevisionRepository.deleteAll();
        // digitization-review-04-sed-summary.md - sound_event.promoted_segment_id
        // FKs to content_segment (V26, not cascading) - must go first.
        soundEventRepository.deleteAll();
        contentSegmentRepository.deleteAll();
        documentDigitizationStatusRepository.deleteAll();
        digitizationRunRepository.deleteAll();
        digitizationSourceRepository.deleteAll();
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
    }

    private static JwtRequestPostProcessor asRole(String username, String realmRole, String springRole) {
        return jwt().jwt(builder -> builder.subject(username)
                        .claim("preferred_username", username)
                        .claim("realm_access", Map.of("roles", List.of(realmRole))))
                .authorities(RolePermissionFixtures.authoritiesFor(springRole));
    }

    /** R1 - CAN_BO: {@code digitization_review.manage} (V28). */
    private static JwtRequestPostProcessor officer(String username) {
        return asRole(username, "can-bo", "CAN_BO");
    }

    private static JwtRequestPostProcessor leader() {
        return asRole("leader-1", "lanh-dao", "LANH_DAO");
    }

    private Document saveDocument(String uploaderSub) {
        Document document = new Document();
        document.setOriginalName("scan-" + UUID.randomUUID() + ".png");
        document.setMimeType("image/png");
        document.setSize(10);
        document.setSha256((UUID.randomUUID().toString() + UUID.randomUUID()).replace("-", "").substring(0, 64));
        document.setStorageKey("documents/" + UUID.randomUUID());
        document.setRelativePath("");
        document.setFolderId(folderId);
        document.setUploaderSub(uploaderSub);
        document.setUploaderUsername(uploaderSub);
        document.setStatus(DocumentStatus.CLEAN);
        document.setCreatedAt(OffsetDateTime.now());
        return documentRepository.save(document);
    }

    private UUID saveSource(UUID documentId) {
        return saveSource(documentId, null);
    }

    /** digitization-review-02-stt.md §2/§4 bước 1: {@code duration_ms}, set when non-null (V27). */
    private UUID saveSource(UUID documentId, Integer durationMs) {
        DigitizationSource source = new DigitizationSource();
        source.setId(UUID.randomUUID());
        source.setDocumentId(documentId);
        source.setComponentType(DigitizationSource.COMPONENT_ORIGINAL);
        source.setFilePath("bucket/" + documentId);
        source.setMimeType("image/png");
        source.setDispatchable(true);
        source.setDispatchStatus(DigitizationSource.DISPATCH_PENDING);
        source.setDurationMs(durationMs);
        OffsetDateTime now = OffsetDateTime.now();
        source.setCreatedAt(now);
        source.setUpdatedAt(now);
        return digitizationSourceRepository.save(source).getId();
    }

    /** An OCR run, {@code succeeded}, {@code review_state=pending}, with one segment. */
    private DigitizationRun seedPendingOcrRunWithOneSegment(UUID documentId, UUID sourceId) {
        DigitizationRun run = lifecycleService.createRun(documentId, sourceId, DigitizationRun.TASK_OCR, null, null);
        lifecycleService.transitionProcessingStatus(run.getId(), DigitizationRun.PROCESSING_SUCCEEDED, null);
        lifecycleService.writeSegments(run.getId(), List.of(new NewSegment(
                0, ContentSegment.SOURCE_OCR, null, 1, null, 0, null, null, null, null, null, null,
                "original text", new BigDecimal("0.900"))));
        return digitizationRunRepository.findById(run.getId()).orElseThrow();
    }

    /**
     * digitization-review-02-stt.md §5 - an STT run, {@code succeeded},
     * {@code review_state=pending}, with {@code timeBounds.length} segments
     * ({@code [start, end]} pairs) laid out on a source whose
     * {@code duration_ms} is {@code durationMs} (nullable).
     */
    private DigitizationRun seedPendingSttRun(UUID documentId, Integer durationMs, int[][] timeBounds) {
        UUID sourceId = saveSource(documentId, durationMs);
        DigitizationRun run = lifecycleService.createRun(documentId, sourceId, DigitizationRun.TASK_STT, null, null);
        lifecycleService.transitionProcessingStatus(run.getId(), DigitizationRun.PROCESSING_SUCCEEDED, null);
        List<NewSegment> segments = new ArrayList<>();
        for (int i = 0; i < timeBounds.length; i++) {
            segments.add(new NewSegment(
                    i, ContentSegment.SOURCE_STT, null, null, null, null, null, null, timeBounds[i][0],
                    timeBounds[i][1], null, null, "segment " + i, new BigDecimal("0.900")));
        }
        lifecycleService.writeSegments(run.getId(), segments);
        return digitizationRunRepository.findById(run.getId()).orElseThrow();
    }

    /**
     * digitization-review-04-sed-summary.md §5/§6 - a SED run, {@code
     * succeeded}, {@code review_state=pending}, with one {@code sound_event}
     * detection per element of {@code confidences} (default
     * {@code sed-promotion-threshold=0.85}, application.yml) - only the ones
     * {@code >= 0.85} get promoted to a {@code content_segment}.
     */
    private DigitizationRun seedPendingSedRun(UUID documentId, UUID sourceId, String[] labels, double[] confidences) {
        DigitizationRun run = lifecycleService.createRun(documentId, sourceId, DigitizationRun.TASK_SED, null, null);
        lifecycleService.transitionProcessingStatus(run.getId(), DigitizationRun.PROCESSING_SUCCEEDED, null);
        List<NewSoundEvent> detections = new ArrayList<>();
        for (int i = 0; i < labels.length; i++) {
            detections.add(new NewSoundEvent(
                    labels[i], labels[i] + " (vi)", BigDecimal.valueOf(confidences[i]), i * 1000, i * 1000 + 1000));
        }
        lifecycleService.writeSoundEvents(run.getId(), detections);
        return digitizationRunRepository.findById(run.getId()).orElseThrow();
    }

    /**
     * digitization-review-04-sed-summary.md §4 bước 4 - a {@code
     * video_summary} run, {@code succeeded}, with exactly 1 segment
     * referencing {@code sourceSegmentIds}, {@code review_state=pending}.
     * {@code dependsOnRunId} is deliberately left {@code null} - it is out of
     * scope for this story (§ "Ngoài phạm vi": orchestration between runs)
     * and setting it would leave a self-referencing FK on {@code
     * digitization_run} that this test class' {@code deleteAll()}-based
     * {@code @BeforeEach} cleanup does not order for.
     */
    private DigitizationRun seedPendingVideoSummaryRun(UUID documentId, List<UUID> sourceSegmentIds) {
        UUID sourceId = saveSource(documentId);
        DigitizationRun run = lifecycleService.createRun(
                documentId, sourceId, DigitizationRun.TASK_VIDEO_SUMMARY, null, null);
        lifecycleService.transitionProcessingStatus(run.getId(), DigitizationRun.PROCESSING_SUCCEEDED, null);
        List<String> sourceIds = new ArrayList<>();
        for (UUID id : sourceSegmentIds) {
            sourceIds.add(id.toString());
        }
        lifecycleService.writeSegments(run.getId(), List.of(new NewSegment(
                0, ContentSegment.SOURCE_SUMMARY, null, null, null, null, null, null, null, null, null, sourceIds,
                "Tóm tắt nội dung video", null)));
        return digitizationRunRepository.findById(run.getId()).orElseThrow();
    }

    // ---- Story 1: hàng chờ scoped theo uploader --------------------------------

    @Test
    void queue_onlyListsRunsUploadedByTheCurrentUser() throws Exception {
        Document mine = saveDocument("officer-1");
        seedPendingOcrRunWithOneSegment(mine.getId(), saveSource(mine.getId()));
        Document someoneElses = saveDocument("officer-2");
        seedPendingOcrRunWithOneSegment(someoneElses.getId(), saveSource(someoneElses.getId()));

        mockMvc.perform(get("/digitization-review/queue").with(officer("officer-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].documentId").value(mine.getId().toString()));
    }

    // ---- digitization-review-06-queue-pending-status.md: processingStatus ------

    /**
     * §5 "Backend": {@code queue} luôn trả {@code processingStatus} nguyên
     * văn từ {@code digitization_run.processing_status}, cho mọi giá trị -
     * kể cả 2 giá trị "chưa xong" ({@code queued}/{@code running}) chưa từng
     * xuất hiện trong các helper seed khác của file này (chúng luôn transition
     * thẳng lên {@code succeeded}).
     */
    @Test
    void queue_exposesRawProcessingStatus_queued() throws Exception {
        Document document = saveDocument("officer-1");
        lifecycleService.createRun(document.getId(), saveSource(document.getId()), DigitizationRun.TASK_OCR, null, null);

        mockMvc.perform(get("/digitization-review/queue").with(officer("officer-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].processingStatus").value(DigitizationRun.PROCESSING_QUEUED));
    }

    @Test
    void queue_exposesRawProcessingStatus_running() throws Exception {
        Document document = saveDocument("officer-1");
        DigitizationRun run = lifecycleService.createRun(
                document.getId(), saveSource(document.getId()), DigitizationRun.TASK_OCR, null, null);
        lifecycleService.transitionProcessingStatus(run.getId(), DigitizationRun.PROCESSING_RUNNING, null);

        mockMvc.perform(get("/digitization-review/queue").with(officer("officer-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].processingStatus").value(DigitizationRun.PROCESSING_RUNNING));
    }

    @Test
    void queue_exposesRawProcessingStatus_succeeded() throws Exception {
        Document document = saveDocument("officer-1");
        seedPendingOcrRunWithOneSegment(document.getId(), saveSource(document.getId()));

        mockMvc.perform(get("/digitization-review/queue").with(officer("officer-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].processingStatus").value(DigitizationRun.PROCESSING_SUCCEEDED));
    }

    /**
     * Case biên §5: {@code failed_retryable}/{@code failed_permanent} trả về
     * đúng giá trị thô - không bị CASE nào ở SQL biến đổi (spec này chỉ
     * SELECT thẳng cột, không map).
     */
    @Test
    void queue_exposesRawProcessingStatus_failedRetryable() throws Exception {
        Document document = saveDocument("officer-1");
        DigitizationRun run = lifecycleService.createRun(
                document.getId(), saveSource(document.getId()), DigitizationRun.TASK_OCR, null, null);
        lifecycleService.transitionProcessingStatus(run.getId(), DigitizationRun.PROCESSING_FAILED_RETRYABLE, "transient error");

        mockMvc.perform(get("/digitization-review/queue").with(officer("officer-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].processingStatus").value(DigitizationRun.PROCESSING_FAILED_RETRYABLE));
    }

    @Test
    void queue_exposesRawProcessingStatus_failedPermanent() throws Exception {
        Document document = saveDocument("officer-1");
        DigitizationRun run = lifecycleService.createRun(
                document.getId(), saveSource(document.getId()), DigitizationRun.TASK_OCR, null, null);
        lifecycleService.transitionProcessingStatus(run.getId(), DigitizationRun.PROCESSING_FAILED_PERMANENT, "fatal error");

        mockMvc.perform(get("/digitization-review/queue").with(officer("officer-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].processingStatus").value(DigitizationRun.PROCESSING_FAILED_PERMANENT));
    }

    // ---- Story 2/3: chính chủ mới xem được -------------------------------------

    @Test
    void getRun_notTheUploader_returns403() throws Exception {
        Document document = saveDocument("officer-1");
        DigitizationRun run = seedPendingOcrRunWithOneSegment(document.getId(), saveSource(document.getId()));

        mockMvc.perform(get("/digitization-review/runs/" + run.getId()).with(officer("officer-2")))
                .andExpect(status().isForbidden());
    }

    @Test
    void getRun_theUploader_returns200AndSetsFirstViewedAt() throws Exception {
        Document document = saveDocument("officer-1");
        DigitizationRun run = seedPendingOcrRunWithOneSegment(document.getId(), saveSource(document.getId()));

        mockMvc.perform(get("/digitization-review/runs/" + run.getId()).with(officer("officer-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runId").value(run.getId().toString()))
                .andExpect(jsonPath("$.segments.content[0].currentText").value("original text"));

        assertThat(digitizationRunRepository.findById(run.getId()).orElseThrow().getFirstViewedAt()).isNotNull();
    }

    @Test
    void missingPermission_returns403() throws Exception {
        Document document = saveDocument("officer-1");
        DigitizationRun run = seedPendingOcrRunWithOneSegment(document.getId(), saveSource(document.getId()));

        mockMvc.perform(get("/digitization-review/runs/" + run.getId()).with(leader()))
                .andExpect(status().isForbidden());
    }

    // ---- Story 2 AC-3: optimistic lock -------------------------------------------

    @Test
    void patchSegment_staleExpectedRevisionNo_returns409WithLatestState() throws Exception {
        Document document = saveDocument("officer-1");
        DigitizationRun run = seedPendingOcrRunWithOneSegment(document.getId(), saveSource(document.getId()));
        ContentSegment segment = contentSegmentRepository.findByRunIdOrderBySegmentIndex(run.getId()).get(0);

        mockMvc.perform(patch("/digitization-review/segments/" + segment.getId())
                        .with(officer("officer-1"))
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("expectedRevisionNo", 99, "text", "x"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.latestRevisionNo").value(0))
                .andExpect(jsonPath("$.latestText").value("original text"));
    }

    @Test
    void patchSegment_concurrentRequestsWithTheSameExpectedRevisionNo_exactlyOneSucceeds() throws Exception {
        Document document = saveDocument("officer-1");
        DigitizationRun run = seedPendingOcrRunWithOneSegment(document.getId(), saveSource(document.getId()));
        ContentSegment segment = contentSegmentRepository.findByRunIdOrderBySegmentIndex(run.getId()).get(0);
        String body = objectMapper.writeValueAsString(Map.of("expectedRevisionNo", 0, "text", "concurrent edit"));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return mockMvc.perform(patch("/digitization-review/segments/" + segment.getId())
                                    .with(officer("officer-1"))
                                    .contentType("application/json")
                                    .content(body))
                            .andReturn().getResponse().getStatus();
                }));
            }
            ready.await(5, TimeUnit.SECONDS);
            go.countDown();

            AtomicInteger okCount = new AtomicInteger();
            AtomicInteger conflictCount = new AtomicInteger();
            for (Future<Integer> f : futures) {
                int status = f.get(10, TimeUnit.SECONDS);
                if (status == 200) {
                    okCount.incrementAndGet();
                } else if (status == 409) {
                    conflictCount.incrementAndGet();
                }
            }
            assertThat(okCount.get()).isEqualTo(1);
            assertThat(conflictCount.get()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    // ---- Story 2 AC-5: xoá/thêm đoạn ---------------------------------------------

    @Test
    void deleteSegment_marksIsDeletedAndBumpsRevision() throws Exception {
        Document document = saveDocument("officer-1");
        DigitizationRun run = seedPendingOcrRunWithOneSegment(document.getId(), saveSource(document.getId()));
        ContentSegment segment = contentSegmentRepository.findByRunIdOrderBySegmentIndex(run.getId()).get(0);

        mockMvc.perform(delete("/digitization-review/segments/" + segment.getId())
                        .with(officer("officer-1"))
                        .param("expectedRevisionNo", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isDeleted").value(true))
                .andExpect(jsonPath("$.currentRevisionNo").value(1));
    }

    @Test
    void addSegment_insertsAManualSegmentAndReturns201() throws Exception {
        Document document = saveDocument("officer-1");
        DigitizationRun run = seedPendingOcrRunWithOneSegment(document.getId(), saveSource(document.getId()));

        mockMvc.perform(post("/digitization-review/runs/" + run.getId() + "/segments")
                        .with(officer("officer-1"))
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("text", "manually typed line"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.origin").value("manual"))
                .andExpect(jsonPath("$.currentText").value("manually typed line"));

        assertThat(contentSegmentRepository.findByRunIdOrderBySegmentIndex(run.getId())).hasSize(2);
    }

    // ---- Story 4: xác nhận -------------------------------------------------------

    @Test
    void confirmRun_pendingRun_returns200Confirmed() throws Exception {
        Document document = saveDocument("officer-1");
        DigitizationRun run = seedPendingOcrRunWithOneSegment(document.getId(), saveSource(document.getId()));

        mockMvc.perform(post("/digitization-review/runs/" + run.getId() + "/confirm").with(officer("officer-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewState").value("confirmed"));
    }

    @Test
    void confirmRun_calledTwice_secondCallReturns409() throws Exception {
        Document document = saveDocument("officer-1");
        DigitizationRun run = seedPendingOcrRunWithOneSegment(document.getId(), saveSource(document.getId()));

        mockMvc.perform(post("/digitization-review/runs/" + run.getId() + "/confirm").with(officer("officer-1")))
                .andExpect(status().isOk());
        mockMvc.perform(post("/digitization-review/runs/" + run.getId() + "/confirm").with(officer("officer-1")))
                .andExpect(status().isConflict());
    }

    // ---- digitization-review-02-stt.md §5 (US3): task=stt time-bound edits ----

    @Test
    void patchSegment_validTimeBoundsOnSttRunWithKnownDuration_returns200AndPersistsCurrentTimeMs() throws Exception {
        Document document = saveDocument("officer-1");
        DigitizationRun run = seedPendingSttRun(document.getId(), 60000, new int[][] {{0, 1000}});
        ContentSegment segment = contentSegmentRepository.findByRunIdOrderBySegmentIndex(run.getId()).get(0);

        mockMvc.perform(patch("/digitization-review/segments/" + segment.getId())
                        .with(officer("officer-1"))
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(
                                Map.of("expectedRevisionNo", 0, "timeStartMs", 1200, "timeEndMs", 4500))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.timeStartMs").value(1200))
                .andExpect(jsonPath("$.timeEndMs").value(4500))
                .andExpect(jsonPath("$.currentRevisionNo").value(1));

        ContentSegment reloaded = contentSegmentRepository.findById(segment.getId()).orElseThrow();
        assertThat(reloaded.getCurrentTimeStartMs()).isEqualTo(1200);
        assertThat(reloaded.getCurrentTimeEndMs()).isEqualTo(4500);
    }

    @Test
    void patchSegment_timeEndMsBeyondSourceDuration_returns422AndLeavesCurrentTimeMsUnchanged() throws Exception {
        Document document = saveDocument("officer-1");
        DigitizationRun run = seedPendingSttRun(document.getId(), 60000, new int[][] {{0, 1000}});
        ContentSegment segment = contentSegmentRepository.findByRunIdOrderBySegmentIndex(run.getId()).get(0);

        mockMvc.perform(patch("/digitization-review/segments/" + segment.getId())
                        .with(officer("officer-1"))
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(
                                Map.of("expectedRevisionNo", 0, "timeStartMs", 1000, "timeEndMs", 70000))))
                .andExpect(status().isUnprocessableEntity());

        ContentSegment reloaded = contentSegmentRepository.findById(segment.getId()).orElseThrow();
        assertThat(reloaded.getCurrentTimeStartMs()).isEqualTo(0);
        assertThat(reloaded.getCurrentTimeEndMs()).isEqualTo(1000);
        assertThat(reloaded.getCurrentRevisionNo()).isZero();
    }

    @Test
    void getRun_twoAdjacentOverlappingSttSegments_bothFlagOverlapsAdjacentTrue() throws Exception {
        Document document = saveDocument("officer-1");
        DigitizationRun run = seedPendingSttRun(document.getId(), 60000, new int[][] {{0, 2000}, {1000, 3000}});

        mockMvc.perform(get("/digitization-review/runs/" + run.getId()).with(officer("officer-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.segments.content[0].overlapsAdjacent").value(true))
                .andExpect(jsonPath("$.segments.content[1].overlapsAdjacent").value(true));
    }

    @Test
    void patchSegment_timeFieldsOnOcrRun_returns400() throws Exception {
        Document document = saveDocument("officer-1");
        DigitizationRun run = seedPendingOcrRunWithOneSegment(document.getId(), saveSource(document.getId()));
        ContentSegment segment = contentSegmentRepository.findByRunIdOrderBySegmentIndex(run.getId()).get(0);

        mockMvc.perform(patch("/digitization-review/segments/" + segment.getId())
                        .with(officer("officer-1"))
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(
                                Map.of("expectedRevisionNo", 0, "timeStartMs", 0, "timeEndMs", 1000))))
                .andExpect(status().isBadRequest());
    }

    // ---- digitization-review-04-sed-summary.md §6 (Story 9/10) ----------------

    @Test
    void getRun_sedRunWithSomeDetectionsBelowThreshold_onlyPromotedSegmentsListedWithLabels() throws Exception {
        Document document = saveDocument("officer-1");
        DigitizationRun run = seedPendingSedRun(
                document.getId(), saveSource(document.getId()), new String[] {"Dog", "Cat", "Wind"},
                new double[] {0.900, 0.870, 0.300});

        mockMvc.perform(get("/digitization-review/runs/" + run.getId()).with(officer("officer-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.segments.totalElements").value(2))
                .andExpect(jsonPath("$.segments.content[0].soundEventLabel").value("Dog"))
                .andExpect(jsonPath("$.segments.content[1].soundEventLabel").value("Cat"));

        assertThat(contentSegmentRepository.findByRunIdOrderBySegmentIndex(run.getId())).hasSize(2);
    }

    @Test
    void patchSegment_concurrentRequestsOnSedSegment_exactlyOneSucceeds() throws Exception {
        Document document = saveDocument("officer-1");
        DigitizationRun run = seedPendingSedRun(
                document.getId(), saveSource(document.getId()), new String[] {"Dog"}, new double[] {0.900});
        ContentSegment segment = contentSegmentRepository.findByRunIdOrderBySegmentIndex(run.getId()).get(0);
        String body = objectMapper.writeValueAsString(Map.of("expectedRevisionNo", 0, "text", "Chó sủa"));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return mockMvc.perform(patch("/digitization-review/segments/" + segment.getId())
                                    .with(officer("officer-1"))
                                    .contentType("application/json")
                                    .content(body))
                            .andReturn().getResponse().getStatus();
                }));
            }
            ready.await(5, TimeUnit.SECONDS);
            go.countDown();

            AtomicInteger okCount = new AtomicInteger();
            AtomicInteger conflictCount = new AtomicInteger();
            for (Future<Integer> f : futures) {
                int status = f.get(10, TimeUnit.SECONDS);
                if (status == 200) {
                    okCount.incrementAndGet();
                } else if (status == 409) {
                    conflictCount.incrementAndGet();
                }
            }
            assertThat(okCount.get()).isEqualTo(1);
            assertThat(conflictCount.get()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void getRun_sedOrVideoSummaryRun_notTheUploader_returns403() throws Exception {
        Document document = saveDocument("officer-1");
        DigitizationRun sedRun = seedPendingSedRun(
                document.getId(), saveSource(document.getId()), new String[] {"Dog"}, new double[] {0.900});
        DigitizationRun summaryRun = seedPendingVideoSummaryRun(document.getId(), List.of());

        mockMvc.perform(get("/digitization-review/runs/" + sedRun.getId()).with(officer("officer-2")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/digitization-review/runs/" + summaryRun.getId()).with(officer("officer-2")))
                .andExpect(status().isForbidden());
    }

    @Test
    void getRun_videoSummaryConfirmedWithSupersededSourceRun_staleWarningTrueAndSourceRunStillReachable()
            throws Exception {
        Document document = saveDocument("officer-1");
        DigitizationRun sttRun = seedPendingSttRun(document.getId(), 60000, new int[][] {{0, 1000}, {1000, 2000}});
        List<ContentSegment> sttSegments = contentSegmentRepository.findByRunIdOrderBySegmentIndex(sttRun.getId());
        lifecycleService.confirmRun(sttRun.getId(), "officer-1");

        List<UUID> sourceSegmentIds = sttSegments.stream().map(ContentSegment::getId).toList();
        DigitizationRun summaryRun = seedPendingVideoSummaryRun(document.getId(), sourceSegmentIds);
        lifecycleService.confirmRun(summaryRun.getId(), "officer-1");

        // digitization-review-01-ocr.md Story 8: the STT source run is later
        // superseded by a re-run - simulated directly via transitionReviewState
        // (this test only cares about the resulting timestamp, not the full
        // re-run flow).
        lifecycleService.transitionReviewState(sttRun.getId(), DigitizationRun.REVIEW_SUPERSEDED, null, null);

        mockMvc.perform(get("/digitization-review/runs/" + summaryRun.getId()).with(officer("officer-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.staleWarning").value(true))
                .andExpect(jsonPath("$.segments.content[0].sourceReferences", org.hamcrest.Matchers.hasSize(2)))
                .andExpect(jsonPath("$.segments.content[0].sourceReferences[0].sourceRunReviewState")
                        .value("superseded"));

        mockMvc.perform(get("/digitization-review/runs/" + sttRun.getId()).with(officer("officer-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewState").value("superseded"));
    }

    @Test
    void postAndDeleteSegment_videoSummaryRun_return409AndSegmentCountUnchanged() throws Exception {
        Document document = saveDocument("officer-1");
        DigitizationRun sttRun = seedPendingSttRun(document.getId(), 60000, new int[][] {{0, 1000}});
        List<ContentSegment> sttSegments = contentSegmentRepository.findByRunIdOrderBySegmentIndex(sttRun.getId());
        DigitizationRun summaryRun = seedPendingVideoSummaryRun(
                document.getId(), List.of(sttSegments.get(0).getId()));
        ContentSegment summarySegment =
                contentSegmentRepository.findByRunIdOrderBySegmentIndex(summaryRun.getId()).get(0);

        mockMvc.perform(post("/digitization-review/runs/" + summaryRun.getId() + "/segments")
                        .with(officer("officer-1"))
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("text", "thêm tay"))))
                .andExpect(status().isConflict());

        mockMvc.perform(delete("/digitization-review/segments/" + summarySegment.getId())
                        .with(officer("officer-1"))
                        .param("expectedRevisionNo", "0"))
                .andExpect(status().isConflict());

        assertThat(contentSegmentRepository.findByRunIdOrderBySegmentIndex(summaryRun.getId())).hasSize(1);
    }

    // ---- digitization-review-05-frontend.md §5 (Task 9 integration bullet) ----

    private static ResponseInputStream<GetObjectResponse> fakeS3Stream(byte[] bytes) {
        return new ResponseInputStream<>(
                GetObjectResponse.builder().build(), AbortableInputStream.create(new ByteArrayInputStream(bytes)));
    }

    @Test
    void getPageImage_notTheUploader_returns403() throws Exception {
        Document document = saveDocument("officer-1");
        DigitizationRun run = seedPendingOcrRunWithOneSegment(document.getId(), saveSource(document.getId()));

        mockMvc.perform(get("/digitization-review/runs/" + run.getId() + "/pages/1/image")
                        .with(officer("officer-2")))
                .andExpect(status().isForbidden());
    }

    @Test
    void getPageImage_theUploaderWithImageSource_returns200WithRawBytes() throws Exception {
        Document document = saveDocument("officer-1");
        DigitizationRun run = seedPendingOcrRunWithOneSegment(document.getId(), saveSource(document.getId()));
        byte[] pngBytes = {1, 2, 3, 4};
        when(objectStorageService.getObject(document.getStorageKey())).thenReturn(fakeS3Stream(pngBytes));

        mockMvc.perform(get("/digitization-review/runs/" + run.getId() + "/pages/1/image")
                        .with(officer("officer-1")))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/png"))
                .andExpect(content().bytes(pngBytes));
    }

    @Test
    void getPageImage_theUploaderWithImageSourceRequestingPageTwo_returns404() throws Exception {
        Document document = saveDocument("officer-1");
        DigitizationRun run = seedPendingOcrRunWithOneSegment(document.getId(), saveSource(document.getId()));

        mockMvc.perform(get("/digitization-review/runs/" + run.getId() + "/pages/2/image")
                        .with(officer("officer-1")))
                .andExpect(status().isNotFound());
    }
}
