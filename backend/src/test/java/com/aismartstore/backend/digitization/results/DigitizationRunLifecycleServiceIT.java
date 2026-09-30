package com.aismartstore.backend.digitization.results;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aismartstore.backend.config.DigitizationProperties;
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
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * digitization-results-store.md §5 "Integration test (service, Spring Boot
 * Test + Testcontainers)".
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Testcontainers
class DigitizationRunLifecycleServiceIT {

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
    private DigitizationRunLifecycleService service;

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
    private WarehouseFolderRepository warehouseFolderRepository;

    @Autowired
    private DigitizationProperties digitizationProperties;

    private UUID documentId;
    private UUID sourceId;

    @BeforeEach
    void seedDocumentAndSource() {
        soundEventRepository.deleteAll();
        segmentRevisionRepository.deleteAll();
        contentSegmentRepository.deleteAll();
        documentDigitizationStatusRepository.deleteAll();
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

    // ---- createRun ---------------------------------------------------------

    @Test
    void createRun_forAllFourTasks_startsAtReviewStatePending() {
        for (String task : List.of(
                DigitizationRun.TASK_OCR, DigitizationRun.TASK_STT, DigitizationRun.TASK_SED,
                DigitizationRun.TASK_VIDEO_SUMMARY)) {
            DigitizationRun run = service.createRun(documentId, sourceId, task, null, null);
            assertThat(run.getReviewState()).isEqualTo(DigitizationRun.REVIEW_PENDING);
            assertThat(run.getProcessingStatus()).isEqualTo(DigitizationRun.PROCESSING_QUEUED);
        }

        DocumentDigitizationStatus status = documentDigitizationStatusRepository.findById(documentId).orElseThrow();
        assertThat(status.getOcrStatus()).isEqualTo(DocumentDigitizationStatus.STATUS_QUEUED);
        assertThat(status.getSttStatus()).isEqualTo(DocumentDigitizationStatus.STATUS_QUEUED);
        assertThat(status.getSedStatus()).isEqualTo(DocumentDigitizationStatus.STATUS_QUEUED);
        assertThat(status.getVideoSummaryStatus()).isEqualTo(DocumentDigitizationStatus.STATUS_QUEUED);
        assertThat(status.getOverallStatus()).isEqualTo(DocumentDigitizationStatus.STATUS_QUEUED);
    }

    @Test
    void createRun_whenAnActiveRunAlreadyExistsForTheSameDocumentAndTask_throwsIllegalState() {
        service.createRun(documentId, sourceId, DigitizationRun.TASK_OCR, null, null);

        assertThatThrownBy(() -> service.createRun(documentId, sourceId, DigitizationRun.TASK_OCR, null, null))
                .isInstanceOf(IllegalStateException.class);
    }

    // ---- writeSegments (US2 AC-1) --------------------------------------------

    @Test
    void writeSegments_afterProcessingSucceeded_aiFieldsMirrorCurrentFields() {
        DigitizationRun run = service.createRun(documentId, sourceId, DigitizationRun.TASK_STT, null, null);
        service.transitionProcessingStatus(run.getId(), DigitizationRun.PROCESSING_RUNNING, null);
        service.transitionProcessingStatus(run.getId(), DigitizationRun.PROCESSING_SUCCEEDED, null);

        NewSegment segment1 = new NewSegment(
                0, ContentSegment.SOURCE_STT, null, null, null, null, null, null,
                0, 1000, null, null, "hello", new BigDecimal("0.950"));
        NewSegment segment2 = new NewSegment(
                1, ContentSegment.SOURCE_STT, null, null, null, null, null, null,
                1000, 2000, null, null, "world", new BigDecimal("0.500"));

        List<ContentSegment> saved = service.writeSegments(run.getId(), List.of(segment1, segment2));

        assertThat(saved).hasSize(2);
        for (ContentSegment segment : saved) {
            assertThat(segment.getAiText()).isEqualTo(segment.getCurrentText());
            assertThat(segment.getAiTimeStartMs()).isEqualTo(segment.getCurrentTimeStartMs());
            assertThat(segment.getAiTimeEndMs()).isEqualTo(segment.getCurrentTimeEndMs());
            assertThat(segment.getCurrentRevisionNo()).isZero();
            assertThat(segment.isEdited()).isFalse();
        }
    }

    // ---- reviseSegment (US2 AC-2/AC-3/AC-4) -------------------------------------

    @Test
    void reviseSegment_twice_keepsAiTextUnchangedAndAppendsTwoRevisions() {
        DigitizationRun run = service.createRun(documentId, sourceId, DigitizationRun.TASK_STT, null, null);
        service.transitionProcessingStatus(run.getId(), DigitizationRun.PROCESSING_SUCCEEDED, null);
        NewSegment newSegment = new NewSegment(
                0, ContentSegment.SOURCE_STT, null, null, null, null, null, null,
                0, 1000, null, null, "original text", new BigDecimal("0.900"));
        ContentSegment segment = service.writeSegments(run.getId(), List.of(newSegment)).get(0);
        String originalAiText = segment.getAiText();

        service.reviseSegment(segment.getId(), 0, "first edit", 100, 900, "officer-1", "typo");
        ContentSegment afterFirstEdit = service.reviseSegment(segment.getId(), 1, "second edit", 200, 800, "officer-1", "typo 2");

        assertThat(afterFirstEdit.getCurrentText()).isEqualTo("second edit");
        assertThat(afterFirstEdit.getAiText()).isEqualTo(originalAiText);
        assertThat(afterFirstEdit.getCurrentRevisionNo()).isEqualTo(2);
        assertThat(afterFirstEdit.isEdited()).isTrue();

        List<SegmentRevision> revisions = segmentRevisionRepository.findAll().stream()
                .filter(r -> r.getSegmentId().equals(segment.getId()))
                .toList();
        assertThat(revisions).hasSize(2);
        assertThat(revisions.stream().map(SegmentRevision::getRevisionNo).sorted().toList()).containsExactly(1, 2);
    }

    // ---- transitionProcessingStatus / transitionReviewState (US4 AC-1) -------

    @Test
    void transitionProcessingStatus_updatesDocumentDigitizationStatusEachTime() {
        DigitizationRun run = service.createRun(documentId, sourceId, DigitizationRun.TASK_OCR, null, null);
        assertThat(documentDigitizationStatusRepository.findById(documentId).orElseThrow().getOcrStatus())
                .isEqualTo(DocumentDigitizationStatus.STATUS_QUEUED);

        service.transitionProcessingStatus(run.getId(), DigitizationRun.PROCESSING_RUNNING, null);
        assertThat(documentDigitizationStatusRepository.findById(documentId).orElseThrow().getOcrStatus())
                .isEqualTo(DocumentDigitizationStatus.STATUS_PROCESSING);

        service.transitionProcessingStatus(run.getId(), DigitizationRun.PROCESSING_FAILED_PERMANENT, "boom");
        assertThat(documentDigitizationStatusRepository.findById(documentId).orElseThrow().getOcrStatus())
                .isEqualTo(DocumentDigitizationStatus.STATUS_FAILED);
        assertThat(documentDigitizationStatusRepository.findById(documentId).orElseThrow().getOverallStatus())
                .isEqualTo(DocumentDigitizationStatus.STATUS_FAILED);
    }

    @Test
    void transitionReviewState_updatesDocumentDigitizationStatusAndConfirmMetadata() {
        DigitizationRun run = service.createRun(documentId, sourceId, DigitizationRun.TASK_OCR, null, null);
        service.transitionProcessingStatus(run.getId(), DigitizationRun.PROCESSING_SUCCEEDED, null);

        service.transitionReviewState(run.getId(), DigitizationRun.REVIEW_IN_REVIEW, null, "officer-1");
        assertThat(documentDigitizationStatusRepository.findById(documentId).orElseThrow().getOcrStatus())
                .isEqualTo(DocumentDigitizationStatus.STATUS_IN_REVIEW);

        DigitizationRun confirmed = service.transitionReviewState(
                run.getId(), DigitizationRun.REVIEW_CONFIRMED, DigitizationRun.CONFIRM_MANUAL_UNCHANGED, "officer-1");

        assertThat(confirmed.getConfirmedBy()).isEqualTo("officer-1");
        assertThat(confirmed.getConfirmedAt()).isNotNull();
        assertThat(confirmed.getConfirmSource()).isEqualTo(DigitizationRun.CONFIRM_MANUAL_UNCHANGED);
        assertThat(documentDigitizationStatusRepository.findById(documentId).orElseThrow().getOcrStatus())
                .isEqualTo(DocumentDigitizationStatus.STATUS_CONFIRMED);
        assertThat(documentDigitizationStatusRepository.findById(documentId).orElseThrow().getOverallStatus())
                .isEqualTo(DocumentDigitizationStatus.STATUS_CONFIRMED);
    }

    // ---- overall_status priority end to end (US4 AC-2) ------------------------

    @Test
    void overallStatus_oneFailedAmongThreeConfirmed_isFailed() {
        DigitizationRun ocr = service.createRun(documentId, sourceId, DigitizationRun.TASK_OCR, null, null);
        DigitizationRun stt = service.createRun(documentId, sourceId, DigitizationRun.TASK_STT, null, null);
        DigitizationRun sed = service.createRun(documentId, sourceId, DigitizationRun.TASK_SED, null, null);
        DigitizationRun videoSummary = service.createRun(documentId, sourceId, DigitizationRun.TASK_VIDEO_SUMMARY, null, null);

        for (DigitizationRun run : List.of(ocr, stt, sed)) {
            service.transitionProcessingStatus(run.getId(), DigitizationRun.PROCESSING_SUCCEEDED, null);
            service.transitionReviewState(run.getId(), DigitizationRun.REVIEW_CONFIRMED, DigitizationRun.CONFIRM_AUTO, null);
        }
        service.transitionProcessingStatus(videoSummary.getId(), DigitizationRun.PROCESSING_FAILED_PERMANENT, "gpu oom");

        assertThat(documentDigitizationStatusRepository.findById(documentId).orElseThrow().getOverallStatus())
                .isEqualTo(DocumentDigitizationStatus.STATUS_FAILED);
    }

    // ---- writeSoundEvents (US5 AC-1/AC-2/AC-3/AC-5) ------------------------------

    @Test
    void writeSoundEvents_recordsEveryDetectionAndPromotesOnlyThoseAboveThreshold() {
        DigitizationRun run = service.createRun(documentId, sourceId, DigitizationRun.TASK_SED, null, null);
        double threshold = digitizationProperties.sedPromotionThreshold();
        BigDecimal above = BigDecimal.valueOf(threshold + 0.05);
        BigDecimal below = BigDecimal.valueOf(Math.max(0.0, threshold - 0.30));

        List<NewSoundEvent> detections = List.of(
                new NewSoundEvent("Dog barking", "Chó sủa", above, 0, 1000),
                new NewSoundEvent("Glass breaking", "Vỡ kính", below, 1000, 2000),
                new NewSoundEvent("Siren", "Còi báo động", above, 2000, 3000));

        List<SoundEvent> saved = service.writeSoundEvents(run.getId(), detections);

        assertThat(saved).hasSize(3);
        long promotedCount = saved.stream().filter(e -> e.getPromotedSegmentId() != null).count();
        assertThat(promotedCount).isEqualTo(2);
        assertThat(saved).allMatch(e -> !e.isVerified());

        for (SoundEvent event : saved) {
            if (event.getPromotedSegmentId() != null) {
                ContentSegment promoted = contentSegmentRepository.findById(event.getPromotedSegmentId()).orElseThrow();
                assertThat(promoted.getContentSource()).isEqualTo(ContentSegment.SOURCE_SED);
            }
        }
    }

    // ---- autoConfirmDueBatch (digitization-review-01-ocr.md §3.4/§5, US5) ---

    private DigitizationRun pendingRunDueForAutoConfirm(String task, OffsetDateTime deadline, boolean blocked) {
        DigitizationRun run = new DigitizationRun();
        run.setDocumentId(documentId);
        run.setSourceId(sourceId);
        run.setTask(task);
        run.setProcessingStatus(DigitizationRun.PROCESSING_SUCCEEDED);
        run.setReviewState(DigitizationRun.REVIEW_PENDING);
        run.setReviewDeadlineAt(deadline);
        run.setAutoConfirmBlocked(blocked);
        OffsetDateTime now = OffsetDateTime.now();
        run.setCreatedAt(now);
        run.setUpdatedAt(now);
        return digitizationRunRepository.save(run);
    }

    @Test
    void autoConfirmDueBatch_skipsBlockedAndNotYetDueRuns_onlySelectsThePastDeadlineUnblockedOne() {
        DigitizationRun due = pendingRunDueForAutoConfirm(
                DigitizationRun.TASK_OCR, OffsetDateTime.now().minusHours(1), false);
        pendingRunDueForAutoConfirm(DigitizationRun.TASK_STT, OffsetDateTime.now().minusHours(1), true); // blocked
        pendingRunDueForAutoConfirm(DigitizationRun.TASK_SED, OffsetDateTime.now().plusHours(1), false); // not yet due

        List<DigitizationRun> confirmed = service.autoConfirmDueBatch(10);

        assertThat(confirmed).extracting(DigitizationRun::getId).containsExactly(due.getId());
        assertThat(confirmed.get(0).getReviewState()).isEqualTo(DigitizationRun.REVIEW_CONFIRMED);
        assertThat(confirmed.get(0).getConfirmSource()).isEqualTo(DigitizationRun.CONFIRM_AUTO);
    }

    // ---- flagStaleInReviewBatch (digitization-review-01-ocr.md §3.4/§5, US6) -

    @Test
    void flagStaleInReviewBatch_selectsAnInReviewRunPastTheCutoffOnceOnly() {
        DigitizationRun run = new DigitizationRun();
        run.setDocumentId(documentId);
        run.setSourceId(sourceId);
        run.setTask(DigitizationRun.TASK_VIDEO_SUMMARY);
        run.setProcessingStatus(DigitizationRun.PROCESSING_SUCCEEDED);
        run.setReviewState(DigitizationRun.REVIEW_IN_REVIEW);
        run.setFirstEditAt(OffsetDateTime.now().minusHours(100));
        OffsetDateTime now = OffsetDateTime.now();
        run.setCreatedAt(now);
        run.setUpdatedAt(now);
        UUID runId = digitizationRunRepository.save(run).getId();

        List<DigitizationRun> firstPass = service.flagStaleInReviewBatch(72, 10);
        assertThat(firstPass).extracting(DigitizationRun::getId).containsExactly(runId);
        assertThat(digitizationRunRepository.findById(runId).orElseThrow().isStaleAlerted()).isTrue();

        List<DigitizationRun> secondPass = service.flagStaleInReviewBatch(72, 10);
        assertThat(secondPass).isEmpty();
    }
}
