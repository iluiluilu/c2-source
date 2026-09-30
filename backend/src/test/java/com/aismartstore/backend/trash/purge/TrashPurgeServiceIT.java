package com.aismartstore.backend.trash.purge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.aismartstore.backend.audit.AuditLog;
import com.aismartstore.backend.audit.AuditLogRepository;
import com.aismartstore.backend.digitization.DigitizationSource;
import com.aismartstore.backend.digitization.DigitizationSourceRepository;
import com.aismartstore.backend.digitization.SourceSeparationJobRepository;
import com.aismartstore.backend.documents.Document;
import com.aismartstore.backend.documents.DocumentRepository;
import com.aismartstore.backend.documents.DocumentStatus;
import com.aismartstore.backend.documents.PurgeState;
import com.aismartstore.backend.groups.DocumentGroupShareRepository;
import com.aismartstore.backend.notes.DocumentNote;
import com.aismartstore.backend.notes.DocumentNoteRepository;
import com.aismartstore.backend.outbox.OutboxEventRepository;
import com.aismartstore.backend.storage.ObjectStorageService;
import com.aismartstore.backend.tags.DocumentTag;
import com.aismartstore.backend.tags.DocumentTagRepository;
import com.aismartstore.backend.tags.Tag;
import com.aismartstore.backend.tags.TagRepository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * delete-restore-trash-02-xoa-cung-xung-dot.md §5: real Postgres coverage of
 * {@code TrashPurgeService} - the {@code claimPurgeBatch}/{@code
 * recoverStuckPurging} native queries, the bia-mộ write (§2.2, every column
 * asserted) and the sha256 dedup guard against the real DB (§7 rủi ro 3
 * explicitly requires covering both purge orders). {@code
 * ObjectStorageService} is mocked - SeaweedFS itself is out of scope here,
 * same as {@code TrashControllerIT}.
 */
@SpringBootTest
@Testcontainers
class TrashPurgeServiceIT {

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
    private TrashPurgeService trashPurgeService;

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private DocumentTagRepository documentTagRepository;

    @Autowired
    private TagRepository tagRepository;

    @Autowired
    private DocumentNoteRepository documentNoteRepository;

    @Autowired
    private DocumentGroupShareRepository documentGroupShareRepository;

    @Autowired
    private DigitizationSourceRepository digitizationSourceRepository;

    @Autowired
    private SourceSeparationJobRepository sourceSeparationJobRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void resetState() {
        documentTagRepository.deleteAll();
        tagRepository.deleteAll();
        documentNoteRepository.deleteAll();
        documentGroupShareRepository.deleteAll();
        digitizationSourceRepository.deleteAll();
        sourceSeparationJobRepository.deleteAll();
        documentRepository.deleteAll();
        outboxEventRepository.deleteAll();
    }

    private Document dueDocument(String sha256) {
        Document document = new Document();
        document.setOriginalName("f-" + UUID.randomUUID() + ".txt");
        document.setMimeType("text/plain");
        document.setSize(1024);
        document.setSha256(sha256);
        document.setStorageKey("documents/" + sha256);
        document.setRelativePath("");
        document.setUploaderSub("officer-a");
        document.setUploaderUsername("officer-a");
        document.setStatus(DocumentStatus.CLEAN);
        document.setCreatedAt(OffsetDateTime.now().minusDays(400));
        document.setDeletedAt(OffsetDateTime.now().minusDays(1));
        document.setDeletedBySub("officer-a");
        document.setDeletedByUsername("officer-a");
        document.setPurgeAfter(OffsetDateTime.now().minusMinutes(1)); // đã quá hạn
        document.setPurgeState(PurgeState.DELETED);
        return documentRepository.save(document);
    }

    @Test
    void runOnceTransitionsThroughPurgingToPurgedAndWritesTheFullBiaMo() {
        Document document = dueDocument("s" + UUID.randomUUID().toString().replace("-", "").substring(0, 20));

        Tag tagRow = new Tag();
        tagRow.setKind("doc_type");
        tagRow.setLabel("Công văn " + UUID.randomUUID());
        tagRow.setCreatedBySub("officer-a");
        tagRow.setCreatedByUsername("officer-a");
        tagRow.setCreatedAt(OffsetDateTime.now());
        tagRow = tagRepository.save(tagRow);

        DocumentTag tag = new DocumentTag();
        tag.setDocumentId(document.getId());
        tag.setTagId(tagRow.getId());
        tag.setTagKind("doc_type");
        tag.setAssignedBySub("officer-a");
        tag.setAssignedByUsername("officer-a");
        tag.setAssignedAt(OffsetDateTime.now());
        documentTagRepository.save(tag);

        DocumentNote note = new DocumentNote();
        note.setDocumentId(document.getId());
        note.setBody("ghi chú");
        note.setCreatedBySub("officer-a");
        note.setCreatedByUsername("officer-a");
        note.setCreatedAt(OffsetDateTime.now());
        note.setUpdatedAt(OffsetDateTime.now());
        documentNoteRepository.save(note);

        DigitizationSource source = new DigitizationSource();
        source.setId(document.getId());
        source.setDocumentId(document.getId());
        source.setComponentType(DigitizationSource.COMPONENT_ORIGINAL);
        source.setFilePath("documents/" + document.getId());
        source.setMimeType("text/plain");
        source.setDispatchable(false);
        source.setCreatedAt(OffsetDateTime.now());
        source.setUpdatedAt(OffsetDateTime.now());
        digitizationSourceRepository.save(source);

        long outboxBefore = outboxEventRepository.count();

        TrashPurgeService.PurgeRunSummary summary = trashPurgeService.runOnce();

        assertThat(summary.purged()).isEqualTo(1);
        assertThat(summary.errors()).isZero();

        Document purged = documentRepository.findById(document.getId()).orElseThrow();
        assertThat(purged.getPurgeState()).isEqualTo(PurgeState.PURGED);
        assertThat(purged.getStatus()).isEqualTo(DocumentStatus.PURGED);
        assertThat(purged.getPurgedAt()).isNotNull();
        assertThat(purged.getPurgingStartedAt()).isNull();
        assertThat(purged.getMimeType()).isEmpty();
        assertThat(purged.getUploaderSub()).isEmpty();
        assertThat(purged.getUploaderUsername()).isEmpty();
        assertThat(purged.getSize()).isZero();
        assertThat(purged.getStorageKey()).isNull();
        assertThat(purged.getRelativePath()).isEmpty();
        assertThat(purged.getFolderId()).isNull();
        assertThat(purged.getDeletedBySub()).isNull();
        assertThat(purged.getPurgeAfter()).isNull();
        // bia mộ tối thiểu (AC-3 Story 5).
        assertThat(purged.getId()).isEqualTo(document.getId());
        assertThat(purged.getSha256()).isEqualTo(document.getSha256());
        assertThat(purged.getOriginalName()).isEqualTo(document.getOriginalName());
        assertThat(purged.getDeletedAt()).isNotNull();

        assertThat(documentTagRepository.findByDocumentIdOrderByAssignedAtAsc(document.getId())).isEmpty();
        assertThat(documentNoteRepository.findByDocumentIdOrderByCreatedAtDesc(document.getId())).isEmpty();
        assertThat(digitizationSourceRepository.findById(document.getId())).isEmpty();

        assertThat(outboxEventRepository.count()).isEqualTo(outboxBefore + 1);

        List<AuditLog> auditRows = auditLogRepository.findByResourceTypeAndResourceIdOrderByIdDesc(
                "document", document.getId().toString());
        assertThat(auditRows).anySatisfy(row -> assertThat(row.getAction()).isEqualTo("document.purge"));
    }

    @Test
    void dedupBySha256_objectSurvivesUntilTheLastReferencingDocumentIsPurged() {
        String sharedSha256 = "dedup" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        Document a = dueDocument(sharedSha256);
        Document b = dueDocument(sharedSha256);
        // Same storageKey (dedup, data-ingestion.md) for both.
        a.setStorageKey("documents/shared-object");
        b.setStorageKey("documents/shared-object");
        documentRepository.save(a);
        documentRepository.save(b);

        trashPurgeService.runOnce(); // both due -> claimed and processed in the same lô

        verify(objectStorageService).purgeWarehouseObject("documents/shared-object");
        assertThat(documentRepository.findById(a.getId()).orElseThrow().getPurgeState()).isEqualTo(PurgeState.PURGED);
        assertThat(documentRepository.findById(b.getId()).orElseThrow().getPurgeState()).isEqualTo(PurgeState.PURGED);
    }

    @Test
    void purgeOneNeverPurgesObjectWhileAnotherDocumentStillReferencesIt() {
        String sharedSha256 = "live" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        Document deleted = dueDocument(sharedSha256);
        deleted.setStorageKey("documents/still-in-use");
        documentRepository.save(deleted);

        Document stillActive = new Document();
        stillActive.setOriginalName("active.txt");
        stillActive.setMimeType("text/plain");
        stillActive.setSize(10);
        stillActive.setSha256(sharedSha256);
        stillActive.setStorageKey("documents/still-in-use");
        stillActive.setRelativePath("");
        stillActive.setUploaderSub("officer-a");
        stillActive.setUploaderUsername("officer-a");
        stillActive.setStatus(DocumentStatus.CLEAN);
        stillActive.setCreatedAt(OffsetDateTime.now());
        documentRepository.save(stillActive);

        trashPurgeService.runOnce();

        verify(objectStorageService, never()).purgeWarehouseObject("documents/still-in-use");
        assertThat(documentRepository.findById(deleted.getId()).orElseThrow().getPurgeState())
                .isEqualTo(PurgeState.PURGED);
    }

    /**
     * Calls {@link DocumentRepository#recoverStuckPurging} directly (not
     * through the {@code PurgeStuckRecoveryJob} bean) - that bean is
     * {@code @Scheduled}(fixedDelay), so it also fires once automatically
     * right after this shared {@code @SpringBootTest} context starts up;
     * calling it again here would very likely be skipped by its own {@code
     * @SchedulerLock}'s {@code lockAtLeastFor}. The call itself needs an
     * explicit, committed {@link TransactionTemplate} transaction (a
     * {@code @Modifying} query has no ambient transaction otherwise) that is
     * deliberately its OWN transaction, separate from the test method itself
     * - wrapping the whole test in {@code @Transactional} instead would make
     * the {@code findById} reads below return Hibernate's stale, already-
     * cached entities rather than what the bulk {@code UPDATE} actually
     * wrote (classic JPQL-bulk-update-vs-session-cache pitfall).
     */
    @Test
    void recoverStuckPurgingResetsOnlyThoseOlderThanTheThreshold() {
        Document stuckOld = dueDocument("stuck-old-" + UUID.randomUUID());
        stuckOld.setPurgeState(PurgeState.PURGING);
        stuckOld.setPurgingStartedAt(OffsetDateTime.now().minus(PurgeStuckRecoveryJob.STUCK_THRESHOLD).minusMinutes(1));
        documentRepository.save(stuckOld);

        Document stuckRecent = dueDocument("stuck-recent-" + UUID.randomUUID());
        stuckRecent.setPurgeState(PurgeState.PURGING);
        stuckRecent.setPurgingStartedAt(OffsetDateTime.now().minusMinutes(5));
        documentRepository.save(stuckRecent);

        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        int recovered = transactionTemplate.execute(status -> documentRepository.recoverStuckPurging(
                OffsetDateTime.now().minus(PurgeStuckRecoveryJob.STUCK_THRESHOLD)));

        assertThat(recovered).isEqualTo(1);
        assertThat(documentRepository.findById(stuckOld.getId()).orElseThrow().getPurgeState())
                .isEqualTo(PurgeState.DELETED);
        assertThat(documentRepository.findById(stuckOld.getId()).orElseThrow().getPurgingStartedAt()).isNull();
        assertThat(documentRepository.findById(stuckRecent.getId()).orElseThrow().getPurgeState())
                .isEqualTo(PurgeState.PURGING);
    }
}
