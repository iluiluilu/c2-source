package com.aismartstore.backend.systemconfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aismartstore.backend.audit.AuditLogRepository;
import com.aismartstore.backend.audit.AuditRequestContext;
import com.aismartstore.backend.audit.AuditService;
import com.aismartstore.backend.auth.KeycloakUser;
import com.aismartstore.backend.common.ApiException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpStatus;

class SystemConfigServiceTest {

    private SystemConfigRepository repository;
    private AuditService auditService;
    private AuditLogRepository auditLogRepository;
    private StringRedisTemplate redis;
    private ValueOperations<String, String> valueOps;
    private SystemConfigService service;

    private final KeycloakUser admin =
            new KeycloakUser("sub-1", "admin", List.of("quan-tri"));

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        repository = mock(SystemConfigRepository.class);
        auditService = mock(AuditService.class);
        AuditRequestContext auditRequestContext = mock(AuditRequestContext.class);
        auditLogRepository = mock(AuditLogRepository.class);
        redis = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        service = new SystemConfigService(repository, auditService, auditRequestContext, auditLogRepository, redis);
    }

    // ---- Story 6: fallback ------------------------------------------------

    @Test
    void getFallsBackToDefaultWhenDbThrows() {
        when(valueOps.get(anyString())).thenReturn(null);
        when(repository.findById(anyString())).thenThrow(new RuntimeException("db down"));

        long value = service.getLong(SystemConfigDefaults.INGEST_MAX_FILES_PER_BATCH);

        assertThat(value).isEqualTo(500L); // static default, no exception
    }

    @Test
    void getFallsBackToDefaultWhenRowMissing() {
        when(valueOps.get(anyString())).thenReturn(null);
        when(repository.findById(anyString())).thenReturn(Optional.empty());

        assertThat(service.getLong(SystemConfigDefaults.OCR_STT_AUTO_CONFIRM_HOURS)).isEqualTo(24L);
    }

    @Test
    void getPrefersRedisOverDb() {
        when(valueOps.get("sysconfig:" + SystemConfigDefaults.INGEST_MAX_FILES_PER_BATCH)).thenReturn("777");

        assertThat(service.getLong(SystemConfigDefaults.INGEST_MAX_FILES_PER_BATCH)).isEqualTo(777L);
        verify(repository, never()).findById(anyString());
    }

    // ---- Story 2: non-sensitive update -----------------------------------

    @Test
    void updateNonSensitiveWritesDbCacheAndAudit() {
        when(repository.findById(SystemConfigDefaults.INGEST_MAX_FILES_PER_BATCH)).thenReturn(Optional.empty());
        when(repository.save(any(SystemConfig.class))).thenAnswer(inv -> inv.getArgument(0));

        SystemConfigService.UpdateResult result =
                service.update(SystemConfigDefaults.INGEST_MAX_FILES_PER_BATCH, 1000, admin);

        assertThat(result.value()).isEqualTo(1000L);
        verify(repository).save(any(SystemConfig.class));
        verify(valueOps).set(eq("sysconfig:" + SystemConfigDefaults.INGEST_MAX_FILES_PER_BATCH), eq("1000"), any());

        ArgumentCaptor<AuditService.AuditEntry> captor = ArgumentCaptor.forClass(AuditService.AuditEntry.class);
        verify(auditService).record(captor.capture());
        assertThat(captor.getValue().action()).isEqualTo(SystemConfigService.ACTION_UPDATE);
        assertThat(captor.getValue().resourceId()).isEqualTo(SystemConfigDefaults.INGEST_MAX_FILES_PER_BATCH);
        assertThat(captor.getValue().metadata()).containsEntry("newValue", 1000L);
    }

    @Test
    void updateInvalidTypeRejectedNoWrite() {
        assertThatThrownBy(() -> service.update(SystemConfigDefaults.INGEST_MAX_FILES_PER_BATCH, "abc", admin))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        verify(repository, never()).save(any());
        verify(auditService, never()).record(any());
    }

    @Test
    void updateConstraintViolationRejectedNoWrite() {
        // warning >= block (default block 95) -> CONSTRAINT_VIOLATION
        when(repository.findById(anyString())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.update(
                        SystemConfigDefaults.WAREHOUSE_STORAGE_WARNING_THRESHOLD_PERCENT, 99, admin))
                .isInstanceOf(ApiException.class);
        verify(repository, never()).save(any());
        verify(auditService, never()).record(any());
    }

    @Test
    void updateUnknownKeyIs404() {
        assertThatThrownBy(() -> service.update("no.such.key", 1, admin))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    // ---- Story 3: sensitive two-step -------------------------------------

    @Test
    void updateSensitiveReturns409AndDoesNotWrite() {
        assertThatThrownBy(() -> service.update(
                        SystemConfigDefaults.RETENTION_TRASH_RETENTION_DAYS, 30, admin))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus()).isEqualTo(HttpStatus.CONFLICT));

        verify(repository, never()).save(any());
        verify(auditService, never()).record(any());
        // token stored with the proposed value
        verify(valueOps).set(anyString(), eq("30"), any());
    }

    @Test
    void confirmWithValidTokenApplies() {
        String key = SystemConfigDefaults.RETENTION_TRASH_RETENTION_DAYS;
        when(valueOps.get(anyString())).thenReturn("30"); // pending value for token
        when(repository.findById(key)).thenReturn(Optional.empty());
        when(repository.save(any(SystemConfig.class))).thenAnswer(inv -> inv.getArgument(0));

        SystemConfigService.UpdateResult result = service.confirm(key, "tok-123", admin);

        assertThat(result.value()).isEqualTo(30L);
        verify(repository).save(any(SystemConfig.class));
        verify(auditService).record(any());
        verify(redis).delete(anyString()); // token consumed
    }

    @Test
    void updateSecondSensitiveKeyBackupCronReturns409AndDoesNotWrite() {
        // backup.schedule_cron is the SA's other sensitive key (Quyết định 3);
        // must behave exactly like retention.trash_retention_days.
        assertThatThrownBy(() -> service.update(
                        SystemConfigDefaults.BACKUP_SCHEDULE_CRON, "0 3 * * *", admin))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus()).isEqualTo(HttpStatus.CONFLICT));
        verify(repository, never()).save(any());
        verify(auditService, never()).record(any());
    }

    @Test
    void confirmWithMissingTokenIsGone() {
        when(valueOps.get(anyString())).thenReturn(null);
        assertThatThrownBy(() -> service.confirm(
                        SystemConfigDefaults.RETENTION_TRASH_RETENTION_DAYS, "expired", admin))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus()).isEqualTo(HttpStatus.GONE));
        verify(repository, never()).save(any());
    }

    // ---- Story 5: rollback -----------------------------------------------

    @Test
    void rollbackNonSensitiveUsesRollbackAction() {
        String key = SystemConfigDefaults.INGEST_MAX_FILES_PER_BATCH;
        when(repository.findById(key)).thenReturn(Optional.empty());
        when(repository.save(any(SystemConfig.class))).thenAnswer(inv -> inv.getArgument(0));

        service.rollback(key, 250, null, admin);

        ArgumentCaptor<AuditService.AuditEntry> captor = ArgumentCaptor.forClass(AuditService.AuditEntry.class);
        verify(auditService).record(captor.capture());
        assertThat(captor.getValue().action()).isEqualTo(SystemConfigService.ACTION_ROLLBACK);
    }

    @Test
    void rollbackSensitiveWithoutTokenReturns409() {
        assertThatThrownBy(() -> service.rollback(
                        SystemConfigDefaults.RETENTION_TRASH_RETENTION_DAYS, 200, null, admin))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus()).isEqualTo(HttpStatus.CONFLICT));
        verify(repository, never()).save(any());
    }

    // ---- Story 5 AC-1: history --------------------------------------------

    @Test
    void historyMapsMetadataNewestFirst() {
        var row = new com.aismartstore.backend.audit.AuditLog();
        row.setActor("admin");
        row.setCreatedAt(java.time.OffsetDateTime.now());
        row.setMetadata(java.util.Map.of("oldValue", 365, "newValue", 30));
        when(auditLogRepository.findByResourceTypeAndResourceIdOrderByIdDesc(
                        eq("system_config"), eq(SystemConfigDefaults.RETENTION_TRASH_RETENTION_DAYS)))
                .thenReturn(List.of(row));

        List<SystemConfigService.HistoryEntry> history =
                service.history(SystemConfigDefaults.RETENTION_TRASH_RETENTION_DAYS);

        assertThat(history).hasSize(1);
        assertThat(history.get(0).oldValue()).isEqualTo(365);
        assertThat(history.get(0).newValue()).isEqualTo(30);
    }
}
