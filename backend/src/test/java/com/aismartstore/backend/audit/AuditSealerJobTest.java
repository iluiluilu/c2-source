package com.aismartstore.backend.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/** Unit coverage of audit-logging.md §4.2/§5: the sealer never runs without the advisory lock, and is a no-op when there is nothing to seal. */
class AuditSealerJobTest {

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final AuditAdvisoryLock advisoryLock = mock(AuditAdvisoryLock.class);
    private final AuditSealerJob job = new AuditSealerJob(jdbcTemplate, advisoryLock);

    @Test
    @SuppressWarnings("unchecked")
    void skipsTheTickWhenTheAdvisoryLockIsNotAcquired() {
        when(advisoryLock.tryLockForTransaction(AuditAdvisoryLock.SEAL)).thenReturn(false);

        job.seal();

        verify(jdbcTemplate, never()).query(anyString(), any(RowMapper.class), any(Object[].class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void noOpWhenThereIsNothingUnsealed() {
        when(advisoryLock.tryLockForTransaction(AuditAdvisoryLock.SEAL)).thenReturn(true);
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(500))).thenReturn(List.of());

        job.seal();

        verify(jdbcTemplate, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void tryLockForTransactionUsesTheSealKey() {
        assertThat(AuditAdvisoryLock.SEAL).isNotEqualTo(AuditAdvisoryLock.CHECKPOINT);
        assertThat(AuditAdvisoryLock.SEAL).isNotEqualTo(AuditAdvisoryLock.PARTITION_MAINTENANCE);
        assertThat(AuditAdvisoryLock.SEAL).isNotEqualTo(AuditAdvisoryLock.RETENTION);
    }
}
