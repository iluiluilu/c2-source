package com.aismartstore.backend.reporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.aismartstore.backend.auth.KeycloakUser;
import com.aismartstore.backend.common.TooManyRequestsException;
import com.aismartstore.backend.config.StorageProperties;
import com.aismartstore.backend.reporting.dto.ReportDtos;
import com.aismartstore.backend.storage.ObjectStorageService;
import com.aismartstore.backend.systemconfig.SystemConfigDefaults;
import com.aismartstore.backend.systemconfig.SystemConfigService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/** AC-30: per-user vs system-wide limit, distinct messages, N taken from configuration, nothing inserted or audited on 429. */
class ExportQuotaTest {

    private ReportExportRepository repository;
    private ReportAuditor auditor;
    private SystemConfigService config;
    private ReportParamValidator validator;
    private ReportExportService service;
    private final KeycloakUser user = new KeycloakUser("u1", "user1", List.of());

    @BeforeEach
    void setUp() {
        repository = mock(ReportExportRepository.class);
        auditor = mock(ReportAuditor.class);
        config = mock(SystemConfigService.class);
        validator = mock(ReportParamValidator.class);
        when(validator.validate(any(), any(), anyString()))
                .thenAnswer(inv -> ((ReportParams) inv.getArgument(0)).normalized(Granularity.MONTH));
        when(config.getLong(SystemConfigDefaults.REPORTING_MAX_EXPORTS_PER_USER)).thenReturn(3L);
        when(config.getLong(SystemConfigDefaults.REPORTING_MAX_CONCURRENT_EXPORTS)).thenReturn(10L);
        service = new ReportExportService(
                repository, new ReportTemplateRegistry(), validator, auditor, mock(ReportAccessGuard.class), config,
                mock(JdbcTemplate.class), mock(ObjectStorageService.class),
                new StorageProperties("http://x", "r", "a", "s", "documents", "personal", "audit", "reports"),
                new ObjectMapper().findAndRegisterModules());
    }

    private ReportDtos.CreateExportRequest request() {
        return new ReportDtos.CreateExportRequest(
                "REPO_SUMMARY", new ReportParams(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31), null, null, null, null, null), "PDF");
    }

    @Test
    void userAtTheLimitGetsThePerUserMessageWithNFromConfig() {
        when(repository.countActiveByUser("u1")).thenReturn(3L);
        assertThatThrownBy(() -> service.create(user, request()))
                .isInstanceOf(TooManyRequestsException.class)
                .hasMessageContaining("3 tệp đang tạo");
        when(config.getLong(SystemConfigDefaults.REPORTING_MAX_EXPORTS_PER_USER)).thenReturn(5L);
        when(repository.countActiveByUser("u1")).thenReturn(5L);
        assertThatThrownBy(() -> service.create(user, request())).hasMessageContaining("5 tệp đang tạo");
        verify(repository, never()).saveAndFlush(any());
        // no report.* row, and the generic request_rejected audit (free-text reason, AC-35) is suppressed too
        verify(auditor, times(2)).skipAuditForQuotaRejection();
        verifyNoMoreInteractions(auditor);
    }

    @Test
    void systemAtTheLimitGetsTheBusyMessage() {
        when(repository.countActiveByUser("u1")).thenReturn(0L);
        when(repository.countActive()).thenReturn(10L);
        assertThatThrownBy(() -> service.create(user, request()))
                .isInstanceOf(TooManyRequestsException.class)
                .hasMessageContaining("Hệ thống đang bận")
                .hasMessageNotContaining("tệp đang tạo");
        verify(repository, never()).saveAndFlush(any());
        verify(auditor).skipAuditForQuotaRejection();
        verifyNoMoreInteractions(auditor);
    }

    @Test
    void belowBothLimitsTheJobIsInsertedAndAudited() {
        when(repository.countActiveByUser("u1")).thenReturn(2L);
        when(repository.countActive()).thenReturn(9L);
        when(repository.saveAndFlush(any())).thenAnswer(inv -> {
            ReportExport e = inv.getArgument(0);
            java.lang.reflect.Field f = ReportExport.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(e, java.util.UUID.randomUUID());
            return e;
        });
        ReportDtos.ExportCreated created = service.create(user, request());
        assertThat(created.status()).isEqualTo("PENDING");
        verify(repository).saveAndFlush(any());
        verify(auditor).exportCreated(any(), any());
    }

    @Test
    void invalidFormatIsRejectedBeforeAnyQuotaWork() {
        ReportDtos.CreateExportRequest bad = new ReportDtos.CreateExportRequest("REPO_SUMMARY", request().params(), "CSV");
        assertThatThrownBy(() -> service.create(user, bad)).isInstanceOf(com.aismartstore.backend.common.BadRequestException.class);
        verifyNoInteractions(repository, auditor);
    }
}
