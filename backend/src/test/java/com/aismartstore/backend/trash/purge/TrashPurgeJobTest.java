package com.aismartstore.backend.trash.purge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aismartstore.backend.audit.AuditService;
import com.aismartstore.backend.systemconfig.SystemConfigDefaults;
import com.aismartstore.backend.systemconfig.SystemConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * delete-restore-trash-02-xoa-cung-xung-dot.md §4.1/§7 rủi ro 1: {@code
 * TrashPurgeJob}'s backup gate - every branch fails closed except an
 * explicit, verified-and-outside-window backup for today. {@link
 * TrashPurgeService} is mocked, so these cover only the gate decision + the
 * {@code trash.purge.skipped} audit entry, not the purge logic itself (see
 * {@code TrashPurgeServiceTest}).
 */
class TrashPurgeJobTest {

    private TrashPurgeService trashPurgeService;
    private SystemConfigService systemConfigService;
    private AuditService auditService;
    private JdbcTemplate jdbcTemplate;
    private TrashPurgeJob job;

    @BeforeEach
    void setUp() {
        trashPurgeService = mock(TrashPurgeService.class);
        systemConfigService = mock(SystemConfigService.class);
        auditService = mock(AuditService.class);
        jdbcTemplate = mock(JdbcTemplate.class);

        job = new TrashPurgeJob(trashPurgeService, systemConfigService, auditService, jdbcTemplate);

        when(systemConfigService.getBoolean(SystemConfigDefaults.BACKUP_PURGE_GATE_ENABLED)).thenReturn(true);
    }

    @Test
    void skipsWhenBackupRunTableDoesNotExistYet() {
        when(jdbcTemplate.queryForObject(any(String.class), eq(Boolean.class))).thenReturn(false);

        job.run();

        verify(trashPurgeService, never()).runOnce();
        ArgumentCaptor<AuditService.AuditEntry> captor = ArgumentCaptor.forClass(AuditService.AuditEntry.class);
        verify(auditService).record(captor.capture());
        assertThat(captor.getValue().action()).isEqualTo("trash.purge.skipped");
    }

    @Test
    void skipsWhenGateCheckThrows() {
        when(jdbcTemplate.queryForObject(any(String.class), eq(Boolean.class)))
                .thenThrow(new DataAccessResourceFailureException("db down"));

        job.run();

        verify(trashPurgeService, never()).runOnce();
    }

    @Test
    void skipsWhenTableExistsButNoVerifiedBackupToday() {
        when(jdbcTemplate.queryForObject(any(String.class), eq(Boolean.class))).thenReturn(true);
        when(jdbcTemplate.queryForObject(any(String.class), eq(Long.class))).thenReturn(0L);

        job.run();

        verify(trashPurgeService, never()).runOnce();
    }

    @Test
    void runsWhenTableExistsAndVerifiedBackupExistsToday() {
        when(jdbcTemplate.queryForObject(any(String.class), eq(Boolean.class))).thenReturn(true);
        when(jdbcTemplate.queryForObject(any(String.class), eq(Long.class))).thenReturn(1L);

        job.run();

        verify(trashPurgeService, times(1)).runOnce();
        verify(auditService, never()).record(any());
    }

    @Test
    void bypassesGateEntirelyWhenKillSwitchDisabled() {
        when(systemConfigService.getBoolean(SystemConfigDefaults.BACKUP_PURGE_GATE_ENABLED)).thenReturn(false);

        job.run();

        verify(trashPurgeService, times(1)).runOnce();
        verify(jdbcTemplate, never()).queryForObject(any(String.class), eq(Boolean.class));
    }
}
