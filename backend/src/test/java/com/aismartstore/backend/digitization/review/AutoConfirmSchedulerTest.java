package com.aismartstore.backend.digitization.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aismartstore.backend.audit.AuditService;
import com.aismartstore.backend.digitization.results.DigitizationRun;
import com.aismartstore.backend.digitization.results.DigitizationRunLifecycleService;
import com.aismartstore.backend.outbox.OutboxEvent;
import com.aismartstore.backend.outbox.OutboxEventRepository;
import com.aismartstore.backend.systemconfig.SystemConfigDefaults;
import com.aismartstore.backend.systemconfig.SystemConfigService;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

/**
 * digitization-review-01-ocr.md §5 (Story 5): {@code AutoConfirmScheduler}
 * unit tests - {@link DigitizationRunLifecycleService} is mocked, so these
 * cover the scheduler's own logic (kill switch, multi-batch loop, audit/
 * outbox writes) - not the {@code autoConfirmDueBatch} SQL itself (that is
 * an IT concern, see the class's Javadoc).
 */
class AutoConfirmSchedulerTest {

    private DigitizationRunLifecycleService lifecycleService;
    private SystemConfigService systemConfigService;
    private AuditService auditService;
    private OutboxEventRepository outboxEventRepository;
    private AutoConfirmScheduler scheduler;

    @BeforeEach
    void setUp() {
        lifecycleService = mock(DigitizationRunLifecycleService.class);
        systemConfigService = mock(SystemConfigService.class);
        auditService = mock(AuditService.class);
        outboxEventRepository = mock(OutboxEventRepository.class);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));

        scheduler = new AutoConfirmScheduler(
                lifecycleService, systemConfigService, auditService, outboxEventRepository, transactionManager);

        when(systemConfigService.getBoolean(SystemConfigDefaults.OCR_STT_AUTO_CONFIRM_ENABLED)).thenReturn(true);
    }

    private static DigitizationRun confirmedRun() {
        DigitizationRun run = new DigitizationRun();
        run.setId(UUID.randomUUID());
        run.setDocumentId(UUID.randomUUID());
        run.setTask(DigitizationRun.TASK_OCR);
        run.setReviewState(DigitizationRun.REVIEW_CONFIRMED);
        run.setConfirmSource(DigitizationRun.CONFIRM_AUTO);
        return run;
    }

    @Test
    void killSwitchDisabled_neverCallsAutoConfirmDueBatch() {
        when(systemConfigService.getBoolean(SystemConfigDefaults.OCR_STT_AUTO_CONFIRM_ENABLED)).thenReturn(false);

        scheduler.run();

        verify(lifecycleService, never()).autoConfirmDueBatch(anyInt());
    }

    @Test
    void oneRunConfirmed_writesOneAuditEntryAndOneOutboxEvent() {
        DigitizationRun run = confirmedRun();
        when(lifecycleService.autoConfirmDueBatch(AutoConfirmScheduler.BATCH_SIZE))
                .thenReturn(List.of(run))
                .thenReturn(List.of());

        scheduler.run();

        ArgumentCaptor<AuditService.AuditEntry> auditCaptor = ArgumentCaptor.forClass(AuditService.AuditEntry.class);
        verify(auditService).record(auditCaptor.capture());
        assertThat(auditCaptor.getValue().action()).isEqualTo("digitization.auto_confirmed");
        assertThat(auditCaptor.getValue().resourceId()).isEqualTo(run.getId().toString());

        ArgumentCaptor<OutboxEvent> outboxCaptor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository).save(outboxCaptor.capture());
        assertThat(outboxCaptor.getValue().getEventType()).isEqualTo("review.confirmed");
        assertThat(outboxCaptor.getValue().getPayload().get("confirmSource")).isEqualTo("auto");
    }

    @Test
    void fullBatches_keepPollingUpToTheTickCeiling() {
        // Every batch call returns a full 500-row batch - the loop must stop
        // at MAX_BATCHES_PER_TICK (Story 5 AC-4), not spin forever.
        List<DigitizationRun> fullBatch = new ArrayList<>();
        for (int i = 0; i < AutoConfirmScheduler.BATCH_SIZE; i++) {
            fullBatch.add(confirmedRun());
        }
        when(lifecycleService.autoConfirmDueBatch(AutoConfirmScheduler.BATCH_SIZE)).thenReturn(fullBatch);

        scheduler.run();

        verify(lifecycleService, times(AutoConfirmScheduler.MAX_BATCHES_PER_TICK))
                .autoConfirmDueBatch(AutoConfirmScheduler.BATCH_SIZE);
    }

    @Test
    void partialBatch_stopsPollingAfterTheFirstShortBatch() {
        List<DigitizationRun> partial = List.of(confirmedRun(), confirmedRun());
        when(lifecycleService.autoConfirmDueBatch(AutoConfirmScheduler.BATCH_SIZE)).thenReturn(partial);

        scheduler.run();

        verify(lifecycleService, times(1)).autoConfirmDueBatch(AutoConfirmScheduler.BATCH_SIZE);
        verify(auditService, times(2)).record(any());
    }

    @Test
    void noRunsDue_writesNothing() {
        when(lifecycleService.autoConfirmDueBatch(AutoConfirmScheduler.BATCH_SIZE)).thenReturn(List.of());

        scheduler.run();

        verify(auditService, never()).record(any());
        verify(outboxEventRepository, never()).save(any());
    }
}
