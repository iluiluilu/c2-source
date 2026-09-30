package com.aismartstore.backend.digitization.results;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aismartstore.backend.digitization.DigitizationSource;
import com.aismartstore.backend.digitization.DigitizationSourceRepository;
import com.aismartstore.backend.documents.Document;
import com.aismartstore.backend.documents.DocumentRepository;
import com.aismartstore.backend.documents.DocumentStatus;
import com.aismartstore.backend.storage.ObjectStorageService;
import com.aismartstore.backend.warehouse.WarehouseFolder;
import com.aismartstore.backend.warehouse.WarehouseFolderRepository;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.dao.DataIntegrityViolationException;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * digitization-results-store.md §5 "Migration / constraint" - V25/V26 run
 * cleanly on top of V1..V24 (proven simply by this class's Spring context
 * starting up, Flyway included), and the CHECK/UNIQUE constraints they add
 * behave as designed. Real Postgres via Testcontainers - CHECK constraints
 * and partial unique indexes are DB-enforced, not something an in-memory/H2
 * substitute would exercise faithfully.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Testcontainers
class DigitizationSchemaConstraintsIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
            .withDatabaseName("aismartstore")
            .withUsername("aismartstore")
            .withPassword("aismartstore");

    @Container
    static GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @org.springframework.test.context.DynamicPropertySource
    static void containerProps(org.springframework.test.context.DynamicPropertyRegistry registry) {
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
    private DocumentRepository documentRepository;

    @Autowired
    private DigitizationSourceRepository digitizationSourceRepository;

    @Autowired
    private DigitizationRunRepository digitizationRunRepository;

    @Autowired
    private ContentSegmentRepository contentSegmentRepository;

    @Autowired
    private SoundEventRepository soundEventRepository;

    @Autowired
    private WarehouseFolderRepository warehouseFolderRepository;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    private UUID documentId;
    private UUID sourceId;

    @BeforeEach
    void seedDocumentAndSource() {
        soundEventRepository.deleteAll();
        contentSegmentRepository.deleteAll();
        digitizationRunRepository.deleteAll();
        digitizationSourceRepository.deleteAll();
        documentRepository.deleteAll();
        List<WarehouseFolder> allFolders = new ArrayList<>(warehouseFolderRepository.findAll());
        allFolders.sort(Comparator.comparingInt(WarehouseFolder::getDepth).reversed());
        warehouseFolderRepository.deleteAll(allFolders);

        WarehouseFolder folder = new WarehouseFolder();
        folder.setName("Root " + UUID.randomUUID());
        folder.setPathIds(new UUID[0]);
        folder.setDepth((short) 1);
        folder.setCreatedAt(OffsetDateTime.now());
        folder.setUpdatedAt(OffsetDateTime.now());
        UUID folderId = warehouseFolderRepository.save(folder).getId();

        Document document = new Document();
        document.setOriginalName("scan.png");
        document.setMimeType("image/png");
        document.setSize(10);
        document.setSha256("a".repeat(64));
        document.setStorageKey("documents/" + UUID.randomUUID());
        document.setRelativePath("");
        document.setFolderId(folderId);
        document.setUploaderSub("officer-1");
        document.setUploaderUsername("officer-1");
        document.setStatus(DocumentStatus.CLEAN);
        document.setCreatedAt(OffsetDateTime.now());
        documentId = documentRepository.save(document).getId();

        DigitizationSource source = new DigitizationSource();
        source.setId(UUID.randomUUID());
        source.setDocumentId(documentId);
        source.setComponentType(DigitizationSource.COMPONENT_ORIGINAL);
        source.setFilePath("bucket/" + documentId);
        source.setMimeType("image/png");
        source.setDispatchable(true);
        source.setDispatchStatus(DigitizationSource.DISPATCH_PENDING);
        OffsetDateTime now = OffsetDateTime.now();
        source.setCreatedAt(now);
        source.setUpdatedAt(now);
        sourceId = digitizationSourceRepository.save(source).getId();
    }

    private DigitizationRun newRun(String task, String reviewState) {
        DigitizationRun run = new DigitizationRun();
        run.setDocumentId(documentId);
        run.setSourceId(sourceId);
        run.setTask(task);
        run.setProcessingStatus(DigitizationRun.PROCESSING_QUEUED);
        run.setReviewState(reviewState);
        OffsetDateTime now = OffsetDateTime.now();
        run.setCreatedAt(now);
        run.setUpdatedAt(now);
        return run;
    }

    private ContentSegment newOcrSegment(UUID runId, Integer aiStart, Integer aiEnd, Integer curStart, Integer curEnd) {
        ContentSegment segment = new ContentSegment();
        segment.setRunId(runId);
        segment.setDocumentId(documentId);
        segment.setSegmentIndex(0);
        segment.setContentSource(ContentSegment.SOURCE_OCR);
        segment.setAiText("ai text");
        segment.setAiTimeStartMs(aiStart);
        segment.setAiTimeEndMs(aiEnd);
        segment.setCurrentText("current text");
        segment.setCurrentTimeStartMs(curStart);
        segment.setCurrentTimeEndMs(curEnd);
        OffsetDateTime now = OffsetDateTime.now();
        segment.setCreatedAt(now);
        segment.setUpdatedAt(now);
        return segment;
    }

    // ---- migrations run cleanly (context startup) -----------------------

    @Test
    void contextLoadsWithV25AndV26AppliedOnTopOfV1ThroughV24() {
        assertThat(digitizationRunRepository.count()).isZero();
    }

    // ---- content_segment CHECK constraints (US3 AC-1/AC-2/AC-4) ------------

    @Test
    void aiTimeStartNotBeforeAiTimeEnd_isRejected() {
        DigitizationRun run = digitizationRunRepository.save(newRun(DigitizationRun.TASK_OCR, DigitizationRun.REVIEW_PENDING));
        ContentSegment segment = newOcrSegment(run.getId(), 1000, 500, null, null);

        assertThatThrownBy(() -> contentSegmentRepository.saveAndFlush(segment))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void currentTimeStartNotBeforeCurrentTimeEnd_isRejected() {
        DigitizationRun run = digitizationRunRepository.save(newRun(DigitizationRun.TASK_STT, DigitizationRun.REVIEW_PENDING));
        ContentSegment segment = newOcrSegment(run.getId(), null, null, 1000, 500);

        assertThatThrownBy(() -> contentSegmentRepository.saveAndFlush(segment))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void ocrSegmentWithNullTimestamps_isAccepted() {
        DigitizationRun run = digitizationRunRepository.save(newRun(DigitizationRun.TASK_OCR, DigitizationRun.REVIEW_PENDING));
        ContentSegment segment = newOcrSegment(run.getId(), null, null, null, null);

        ContentSegment saved = contentSegmentRepository.saveAndFlush(segment);

        assertThat(saved.getId()).isNotNull();
    }

    // ---- sound_event CHECK constraint (US3 AC-3) --------------------------

    @Test
    void soundEventTimeStartNotBeforeTimeEnd_isRejected() {
        DigitizationRun run = digitizationRunRepository.save(newRun(DigitizationRun.TASK_SED, DigitizationRun.REVIEW_PENDING));
        SoundEvent event = new SoundEvent();
        event.setRunId(run.getId());
        event.setDocumentId(documentId);
        event.setLabel("Dog barking");
        event.setConfidence(new BigDecimal("0.900"));
        event.setTimeStartMs(1000);
        event.setTimeEndMs(500);
        event.setCreatedAt(OffsetDateTime.now());

        assertThatThrownBy(() -> soundEventRepository.saveAndFlush(event))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ---- UQ_digitization_run_active (US1 AC-2/AC-3) ------------------------

    @Test
    void onlyOneActiveRunPerDocumentAndTask_supersedingTheOldOneUnblocksANewOne() {
        DigitizationRun run1 = digitizationRunRepository.saveAndFlush(
                newRun(DigitizationRun.TASK_OCR, DigitizationRun.REVIEW_PENDING));

        DigitizationRun run2Attempt = newRun(DigitizationRun.TASK_OCR, DigitizationRun.REVIEW_PENDING);
        assertThatThrownBy(() -> digitizationRunRepository.saveAndFlush(run2Attempt))
                .isInstanceOf(DataIntegrityViolationException.class);

        run1.setReviewState(DigitizationRun.REVIEW_SUPERSEDED);
        digitizationRunRepository.saveAndFlush(run1);

        DigitizationRun run2 = digitizationRunRepository.saveAndFlush(
                newRun(DigitizationRun.TASK_OCR, DigitizationRun.REVIEW_PENDING));

        assertThat(digitizationRunRepository.findById(run1.getId())).isPresent();
        assertThat(digitizationRunRepository.findById(run2.getId())).isPresent();
        assertThat(digitizationRunRepository.findById(run1.getId()).orElseThrow().getReviewState())
                .isEqualTo(DigitizationRun.REVIEW_SUPERSEDED);
    }

    // ---- UQ_sound_event_promoted_segment ---------------------------------

    @Test
    void twoSoundEventsCannotPromoteTheSameSegment() {
        DigitizationRun run = digitizationRunRepository.save(newRun(DigitizationRun.TASK_SED, DigitizationRun.REVIEW_PENDING));
        ContentSegment segment = contentSegmentRepository.saveAndFlush(newOcrSegment(run.getId(), null, null, null, null));
        segment.setContentSource(ContentSegment.SOURCE_SED);
        contentSegmentRepository.saveAndFlush(segment);

        SoundEvent event1 = new SoundEvent();
        event1.setRunId(run.getId());
        event1.setDocumentId(documentId);
        event1.setLabel("Dog barking");
        event1.setConfidence(new BigDecimal("0.900"));
        event1.setTimeStartMs(0);
        event1.setTimeEndMs(1000);
        event1.setPromotedSegmentId(segment.getId());
        event1.setCreatedAt(OffsetDateTime.now());
        soundEventRepository.saveAndFlush(event1);

        SoundEvent event2 = new SoundEvent();
        event2.setRunId(run.getId());
        event2.setDocumentId(documentId);
        event2.setLabel("Dog barking");
        event2.setConfidence(new BigDecimal("0.910"));
        event2.setTimeStartMs(1000);
        event2.setTimeEndMs(2000);
        event2.setPromotedSegmentId(segment.getId());
        event2.setCreatedAt(OffsetDateTime.now());

        assertThatThrownBy(() -> soundEventRepository.saveAndFlush(event2))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ---- V28 (digitization-review-01-ocr.md §2) --------------------------

    @Test
    void v28AddsStaleAlertedAndIsDeletedColumnsPlusThePollIndex() {
        List<String> digitizationRunColumns = jdbcTemplate.queryForList(
                "SELECT column_name FROM information_schema.columns "
                        + "WHERE table_schema = 'public' AND table_name = 'digitization_run' AND column_name = 'stale_alerted'",
                String.class);
        assertThat(digitizationRunColumns).containsExactly("stale_alerted");

        List<String> contentSegmentColumns = jdbcTemplate.queryForList(
                "SELECT column_name FROM information_schema.columns "
                        + "WHERE table_schema = 'public' AND table_name = 'content_segment' AND column_name = 'is_deleted'",
                String.class);
        assertThat(contentSegmentColumns).containsExactly("is_deleted");

        Integer indexCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM pg_indexes WHERE indexname = 'idx_digitization_run_stale_unalerted'",
                Integer.class);
        assertThat(indexCount).isEqualTo(1);
    }
}
