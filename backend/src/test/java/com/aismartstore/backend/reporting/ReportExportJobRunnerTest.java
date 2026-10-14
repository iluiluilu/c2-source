package com.aismartstore.backend.reporting;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.aismartstore.backend.config.StorageProperties;
import com.aismartstore.backend.storage.ObjectStorageService;
import com.aismartstore.backend.systemconfig.SystemConfigService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

/** AC-38: whatever goes wrong inside the reporting runner stays inside it - the shared scheduler thread never sees an exception. */
class ReportExportJobRunnerTest {

    private final ReportExportRepository repository = mock(ReportExportRepository.class);

    private ReportExportJobRunner runner(boolean enabled) {
        return new ReportExportJobRunner(
                repository, new ReportTemplateRegistry(), mock(ReportParamValidator.class), mock(ReportQueryService.class),
                mock(ReportRenderService.class), mock(ReportAuditor.class), mock(SystemConfigService.class),
                mock(ObjectStorageService.class),
                new StorageProperties("http://x", "r", "a", "s", "documents", "personal", "audit", "reports"),
                new ObjectMapper().findAndRegisterModules(), mock(PlatformTransactionManager.class), enabled);
    }

    @Test
    void aFailingClaimNeverEscapesThePollingMethod() {
        when(repository.claimNextBatch(anyInt(), anyInt())).thenThrow(new IllegalStateException("db down"));
        assertThatCode(() -> runner(true).poll()).doesNotThrowAnyException();
    }

    @Test
    void aFailingStuckCheckNeverEscapesEither() {
        when(repository.countStalePending(org.mockito.ArgumentMatchers.any())).thenThrow(new IllegalStateException("db down"));
        assertThatCode(() -> runner(true).poll()).doesNotThrowAnyException();
    }

    @Test
    void whenDisabledThePollTouchesNothing() {
        runner(false).poll();
        verifyNoInteractions(repository);
    }
}
