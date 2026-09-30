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
 * digitization-review-01-ocr.md §5 (Story 6): {@code ReviewStaleAlertJob}
 * unit tests - {@code flagStaleInReviewBatch}'s own SQL selection/dedupe
 * behaviour (stale_alerted) is an IT concern; this only covers the job's own
 * looping + audit-writing logic.
 */
class ReviewStaleAlertJobTest {

    private DigitizationRunLifecycleService lifecycleService;
    private SystemConfigService systemConfigService;
    private AuditService auditService;
    private ReviewStaleAlertJob job;

    @BeforeEach
    void setUp() {
        lifecycleService = mock(DigitizationRunLifecycleService.class);
        systemConfigService = mock(SystemConfigService.class);
        auditService = mock(AuditService.class);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));

        job = new ReviewStaleAlertJob(lifecycleService, systemConfigService, auditService, transactionManager);

        when(systemConfigService.getLong(SystemConfigDefaults.OCR_STT_REVIEW_STALE_HOURS)).thenReturn(72L);
    }

    private static DigitizationRun staleRun() {
        DigitizationRun run = new DigitizationRun();
        run.setId(UUID.randomUUID());
        run.setDocumentId(UUID.randomUUID());
        run.setTask(DigitizationRun.TASK_STT);
        run.setReviewState(DigitizationRun.REVIEW_IN_REVIEW);
        return run;
    }

    @Test
    void oneStaleRun_writesOneAuditEntry() {
        DigitizationRun run = staleRun();
        when(lifecycleService.flagStaleInReviewBatch(72, ReviewStaleAlertJob.BATCH_SIZE))
                .thenReturn(List.of(run))
                .thenReturn(List.of());

        job.run();

        ArgumentCaptor<AuditService.AuditEntry> captor = ArgumentCaptor.forClass(AuditService.AuditEntry.class);
        verify(auditService).record(captor.capture());
        assertThat(captor.getValue().action()).isEqualTo("digitization.review_stale");
        assertThat(captor.getValue().resourceId()).isEqualTo(run.getId().toString());
    }

    @Test
    void noStaleRuns_writesNothing() {
        when(lifecycleService.flagStaleInReviewBatch(anyInt(), anyInt())).thenReturn(List.of());

        job.run();

        verify(auditService, never()).record(any());
    }

    @Test
    void fullBatch_keepsPollingUntilAShortBatch() {
        List<DigitizationRun> full = new ArrayList<>();
        for (int i = 0; i < ReviewStaleAlertJob.BATCH_SIZE; i++) {
            full.add(staleRun());
        }
        when(lifecycleService.flagStaleInReviewBatch(72, ReviewStaleAlertJob.BATCH_SIZE))
                .thenReturn(full)
                .thenReturn(List.of(staleRun()));

        job.run();

        verify(lifecycleService, times(2)).flagStaleInReviewBatch(72, ReviewStaleAlertJob.BATCH_SIZE);
    }
}
