package com.aismartstore.backend.trash.purge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aismartstore.backend.audit.AuditService;
import com.aismartstore.backend.digitization.DigitizationSourceRepository;
import com.aismartstore.backend.digitization.SourceSeparationJobRepository;
import com.aismartstore.backend.digitization.results.ContentSegmentRepository;
import com.aismartstore.backend.digitization.results.DigitizationRunRepository;
import com.aismartstore.backend.digitization.results.DocumentDigitizationStatusRepository;
import com.aismartstore.backend.digitization.results.SegmentRevisionRepository;
import com.aismartstore.backend.documents.Document;
import com.aismartstore.backend.documents.DocumentRepository;
import com.aismartstore.backend.documents.DocumentStatus;
import com.aismartstore.backend.documents.PurgeState;
import com.aismartstore.backend.groups.DocumentGroupShareRepository;
import com.aismartstore.backend.notes.DocumentNoteRepository;
import com.aismartstore.backend.outbox.OutboxEvent;
import com.aismartstore.backend.outbox.OutboxEventRepository;
import com.aismartstore.backend.storage.ObjectStorageService;
import com.aismartstore.backend.tags.DocumentTagRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * delete-restore-trash-02-xoa-cung-xung-dot.md §5 ("Unit - gợi ý thư mục" is
 * covered in {@code TrashServiceTest}; this class covers §4.2's bia-mộ
 * write, dedup-by-sha256, idempotency and the batch loop) - every
 * collaborator mocked, {@code batchPauseMs=0} so the loop tests run fast.
 * Real-Postgres coverage (claim query, {@code FOR UPDATE SKIP LOCKED},
 * ShedLock) lives in {@code TrashPurgeServiceIT}.
 */
class TrashPurgeServiceTest {

    private DocumentRepository documentRepository;
    private DocumentTagRepository documentTagRepository;
    private DocumentNoteRepository documentNoteRepository;
    private DocumentGroupShareRepository documentGroupShareRepository;
    private SegmentRevisionRepository segmentRevisionRepository;
    private ContentSegmentRepository contentSegmentRepository;
    private DigitizationRunRepository digitizationRunRepository;
    private DocumentDigitizationStatusRepository documentDigitizationStatusRepository;
    private DigitizationSourceRepository digitizationSourceRepository;
    private SourceSeparationJobRepository sourceSeparationJobRepository;
    private OutboxEventRepository outboxEventRepository;
    private ObjectStorageService objectStorageService;
    private AuditService auditService;
    private TrashPurgeService service;

    @BeforeEach
    void setUp() {
        documentRepository = mock(DocumentRepository.class);
        documentTagRepository = mock(DocumentTagRepository.class);
        documentNoteRepository = mock(DocumentNoteRepository.class);
        documentGroupShareRepository = mock(DocumentGroupShareRepository.class);
        segmentRevisionRepository = mock(SegmentRevisionRepository.class);
        contentSegmentRepository = mock(ContentSegmentRepository.class);
        digitizationRunRepository = mock(DigitizationRunRepository.class);
        documentDigitizationStatusRepository = mock(DocumentDigitizationStatusRepository.class);
        digitizationSourceRepository = mock(DigitizationSourceRepository.class);
        sourceSeparationJobRepository = mock(SourceSeparationJobRepository.class);
        outboxEventRepository = mock(OutboxEventRepository.class);
        objectStorageService = mock(ObjectStorageService.class);
        auditService = mock(AuditService.class);

        service = new TrashPurgeService(
                documentRepository, documentTagRepository, documentNoteRepository, documentGroupShareRepository,
                segmentRevisionRepository, contentSegmentRepository, digitizationRunRepository,
                documentDigitizationStatusRepository, digitizationSourceRepository, sourceSeparationJobRepository,
                outboxEventRepository, objectStorageService, auditService, 0L);
    }

    private static Document deletedDocument() {
        Document document = new Document();
        ReflectionTestUtils.setField(document, "id", UUID.randomUUID());
        document.setOriginalName("f.txt");
        document.setUploaderSub("uploader");
        document.setUploaderUsername("uploader");
        document.setSize(1024);
        document.setStatus(DocumentStatus.CLEAN);
        document.setSha256("a".repeat(64));
        document.setStorageKey("documents/" + document.getId());
        document.setPurgeState(PurgeState.PURGING);
        return document;
    }

    // ---- §4.2 bước 2-5: purgeOne ------------------------------------------

    @Test
    void purgeOneDeletesDerivativesPurgesObjectAndWritesBiaMo() {
        Document document = deletedDocument();
        String originalStorageKey = document.getStorageKey();
        String originalSha256 = document.getSha256();
        when(documentRepository.findById(document.getId())).thenReturn(Optional.of(document));
        when(documentRepository.existsBySha256AndIdNotAndStatusNot(
                        originalSha256, document.getId(), DocumentStatus.PURGED))
                .thenReturn(false); // no other document shares this sha256

        service.purgeOne(document.getId());

        verify(documentTagRepository).deleteByDocumentId(document.getId());
        verify(documentNoteRepository).deleteByDocumentId(document.getId());
        verify(documentGroupShareRepository).deleteByDocumentId(document.getId());
        verify(digitizationRunRepository).clearDependsOnRunIdByDocumentId(document.getId());
        verify(segmentRevisionRepository).deleteByContentSegmentDocumentId(document.getId());
        verify(contentSegmentRepository).deleteByDocumentId(document.getId());
        verify(digitizationRunRepository).deleteByDocumentId(document.getId());
        verify(digitizationSourceRepository).deleteByDocumentId(document.getId());
        verify(sourceSeparationJobRepository).deleteByDocumentId(document.getId());
        verify(documentDigitizationStatusRepository).deleteByDocumentId(document.getId());

        verify(objectStorageService).purgeWarehouseObject(originalStorageKey);

        assertThat(document.getPurgeState()).isEqualTo(PurgeState.PURGED);
        assertThat(document.getPurgedAt()).isNotNull();
        assertThat(document.getPurgingStartedAt()).isNull();
        assertThat(document.getStatus()).isEqualTo(DocumentStatus.PURGED);
        assertThat(document.getMimeType()).isEmpty();
        assertThat(document.getUploaderSub()).isEmpty();
        assertThat(document.getUploaderUsername()).isEmpty();
        assertThat(document.getSize()).isZero();
        assertThat(document.getStorageKey()).isNull();
        assertThat(document.getScanResult()).isNull();
        assertThat(document.getRelativePath()).isEmpty();
        assertThat(document.getFolderId()).isNull();
        assertThat(document.getDeletedBySub()).isNull();
        assertThat(document.getDeletedByUsername()).isNull();
        assertThat(document.getDeletedReason()).isNull();
        assertThat(document.getPurgeAfter()).isNull();
        assertThat(document.getTrashBatchId()).isNull();
        assertThat(document.getDeletedFolderPathIds()).isNull();
        // bia mộ tối thiểu (AC-3 Story 5): id/sha256/originalName/deletedAt giữ nguyên.
        assertThat(document.getOriginalName()).isEqualTo("f.txt");
        assertThat(document.getSha256()).isEqualTo("a".repeat(64));

        verify(documentRepository).save(document);

        ArgumentCaptor<OutboxEvent> outboxCaptor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository).save(outboxCaptor.capture());
        assertThat(outboxCaptor.getValue().getEventType()).isEqualTo("document.purged");

        ArgumentCaptor<AuditService.AuditEntry> auditCaptor = ArgumentCaptor.forClass(AuditService.AuditEntry.class);
        verify(auditService).record(auditCaptor.capture());
        assertThat(auditCaptor.getValue().action()).isEqualTo("document.purge");
        assertThat(auditCaptor.getValue().resourceId()).isEqualTo(document.getId().toString());
    }

    /**
     * bugs/bug-004-trash-purge-missing-child-table-cleanup.md - the real
     * defect: on a real Postgres, deleting {@code digitization_source}
     * before {@code digitization_run} (or {@code digitization_run} before
     * {@code content_segment}/{@code segment_revision}) violates an {@code
     * ON DELETE NO ACTION} foreign key. Mocks can't catch a DB constraint,
     * so this test is the only thing pinning down the required call order.
     */
    @Test
    void purgeOneDeletesDigitizationChildTablesInDependencyOrder() {
        Document document = deletedDocument();
        when(documentRepository.findById(document.getId())).thenReturn(Optional.of(document));
        when(documentRepository.existsBySha256AndIdNotAndStatusNot(any(), any(), any())).thenReturn(false);

        service.purgeOne(document.getId());

        var order = inOrder(digitizationRunRepository, segmentRevisionRepository, contentSegmentRepository,
                digitizationSourceRepository);
        order.verify(digitizationRunRepository).clearDependsOnRunIdByDocumentId(document.getId());
        order.verify(segmentRevisionRepository).deleteByContentSegmentDocumentId(document.getId());
        order.verify(contentSegmentRepository).deleteByDocumentId(document.getId());
        order.verify(digitizationRunRepository).deleteByDocumentId(document.getId());
        order.verify(digitizationSourceRepository).deleteByDocumentId(document.getId());
    }

    @Test
    void purgeOneNeverPurgesObjectWhenAnotherNonPurgedDocumentSharesSha256() {
        Document document = deletedDocument();
        when(documentRepository.findById(document.getId())).thenReturn(Optional.of(document));
        when(documentRepository.existsBySha256AndIdNotAndStatusNot(
                        document.getSha256(), document.getId(), DocumentStatus.PURGED))
                .thenReturn(true); // another, still-live document shares this object

        service.purgeOne(document.getId());

        verify(objectStorageService, never()).purgeWarehouseObject(any());
        assertThat(document.getPurgeState()).isEqualTo(PurgeState.PURGED); // bia mộ still written
    }

    @Test
    void purgeOneIsANoOpWhenAlreadyPurgedByAResumedAttempt() {
        Document document = deletedDocument();
        document.setPurgeState(PurgeState.PURGED);
        when(documentRepository.findById(document.getId())).thenReturn(Optional.of(document));

        service.purgeOne(document.getId());

        verify(documentTagRepository, never()).deleteByDocumentId(any());
        verify(documentRepository, never()).save(any());
        verify(auditService, never()).record(any());
    }

    @Test
    void purgeOneIsANoOpWhenDocumentVanished() {
        UUID id = UUID.randomUUID();
        when(documentRepository.findById(id)).thenReturn(Optional.empty());

        service.purgeOne(id); // must not throw

        verify(documentTagRepository, never()).deleteByDocumentId(any());
    }

    @Test
    void purgeOneSkipsObjectDeletionWhenStorageKeyOrSha256IsMissing() {
        Document document = deletedDocument();
        document.setSha256(null);
        document.setStorageKey(null);
        when(documentRepository.findById(document.getId())).thenReturn(Optional.of(document));

        service.purgeOne(document.getId());

        verify(objectStorageService, never()).purgeWarehouseObject(any());
        verify(documentRepository, never())
                .existsBySha256AndIdNotAndStatusNot(any(), any(), any());
    }

    // ---- runOnce: batch loop -----------------------------------------------

    @Test
    void runOnceStopsAfterAPartialBatch() {
        Document d1 = deletedDocument();
        when(documentRepository.claimPurgeBatch(anyInt())).thenReturn(List.of(d1.getId()));
        when(documentRepository.findById(d1.getId())).thenReturn(Optional.of(d1));
        when(documentRepository.existsBySha256AndIdNotAndStatusNot(any(), any(), any())).thenReturn(false);

        TrashPurgeService.PurgeRunSummary summary = service.runOnce();

        assertThat(summary.purged()).isEqualTo(1);
        assertThat(summary.errors()).isZero();
        assertThat(summary.batches()).isEqualTo(1);
        verify(documentRepository, times(1)).claimPurgeBatch(eq(TrashPurgeService.BATCH_SIZE));
    }

    @Test
    void runOnceKeepsPollingWhileBatchesAreFull() {
        List<UUID> fullBatch1 = newFullBatch();
        List<UUID> fullBatch2 = newFullBatch();
        when(documentRepository.existsBySha256AndIdNotAndStatusNot(any(), any(), any())).thenReturn(false);
        when(documentRepository.claimPurgeBatch(anyInt()))
                .thenReturn(fullBatch1)
                .thenReturn(fullBatch2)
                .thenReturn(List.of());

        TrashPurgeService.PurgeRunSummary summary = service.runOnce();

        assertThat(summary.batches()).isEqualTo(2);
        assertThat(summary.purged()).isEqualTo(2 * TrashPurgeService.BATCH_SIZE);
        verify(documentRepository, times(3)).claimPurgeBatch(anyInt());
    }

    /** Builds a fresh, fully-stubbed batch of {@code BATCH_SIZE} due documents. */
    private List<UUID> newFullBatch() {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < TrashPurgeService.BATCH_SIZE; i++) {
            Document d = deletedDocument();
            ids.add(d.getId());
            when(documentRepository.findById(d.getId())).thenReturn(Optional.of(d));
        }
        return ids;
    }

    @Test
    void runOnceCountsOneFailureButKeepsProcessingTheRestOfTheBatch() {
        Document ok = deletedDocument();
        Document broken = deletedDocument();
        when(documentRepository.claimPurgeBatch(anyInt())).thenReturn(List.of(broken.getId(), ok.getId()));
        when(documentRepository.findById(broken.getId())).thenReturn(Optional.of(broken));
        when(documentRepository.findById(ok.getId())).thenReturn(Optional.of(ok));
        when(documentRepository.existsBySha256AndIdNotAndStatusNot(any(), any(), any())).thenReturn(false);
        doThrow(new RuntimeException("SeaweedFS timeout"))
                .when(documentTagRepository)
                .deleteByDocumentId(broken.getId());

        TrashPurgeService.PurgeRunSummary summary = service.runOnce();

        assertThat(summary.errors()).isEqualTo(1);
        assertThat(summary.purged()).isEqualTo(1);
        assertThat(ok.getPurgeState()).isEqualTo(PurgeState.PURGED);
        // broken's own transaction rolled back before flipping purgeState -
        // in a real DB it stays 'purging' (already committed by the claim
        // step) for PurgeStuckRecoveryJob; here (no real transaction) it
        // simply never reaches the bia-mộ write.
        assertThat(broken.getPurgeState()).isEqualTo(PurgeState.PURGING);
    }

    @Test
    void runOnceDoesNothingWhenNoDocumentsAreDue() {
        when(documentRepository.claimPurgeBatch(anyInt())).thenReturn(List.of());

        TrashPurgeService.PurgeRunSummary summary = service.runOnce();

        assertThat(summary.batches()).isZero();
        assertThat(summary.purged()).isZero();
        verify(auditService, never()).record(any());
    }
}
