package com.aismartstore.backend.trash.purge;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aismartstore.backend.documents.DocumentRepository;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** delete-restore-trash-02-xoa-cung-xung-dot.md §4.3: {@code PurgeStuckRecoveryJob} calls the repository's threshold correctly. */
class PurgeStuckRecoveryJobTest {

    @Test
    void recoversUsingA2HourThreshold() {
        DocumentRepository documentRepository = mock(DocumentRepository.class);
        when(documentRepository.recoverStuckPurging(any())).thenReturn(3);
        PurgeStuckRecoveryJob job = new PurgeStuckRecoveryJob(documentRepository);

        OffsetDateTime before = OffsetDateTime.now().minus(PurgeStuckRecoveryJob.STUCK_THRESHOLD);
        job.run();
        OffsetDateTime after = OffsetDateTime.now().minus(PurgeStuckRecoveryJob.STUCK_THRESHOLD);

        ArgumentCaptor<OffsetDateTime> cutoffCaptor = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(documentRepository).recoverStuckPurging(cutoffCaptor.capture());
        OffsetDateTime cutoff = cutoffCaptor.getValue();
        org.assertj.core.api.Assertions.assertThat(cutoff).isBetween(before, after);
    }
}
