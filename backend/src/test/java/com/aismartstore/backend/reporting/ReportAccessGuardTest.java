package com.aismartstore.backend.reporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.aismartstore.backend.audit.AuditRequestContext;
import com.aismartstore.backend.audit.AuditService;
import com.aismartstore.backend.auth.KeycloakUser;
import com.aismartstore.backend.common.ForbiddenException;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/** AC-1/AC-2: the guard allows holders of the permission, denies everyone else with 403 + a report.access_denied audit row. */
class ReportAccessGuardTest {

    private AuditService auditService;
    private ReportAccessGuard guard;

    @BeforeEach
    void setUp() {
        auditService = mock(AuditService.class);
        guard = new ReportAccessGuard(auditService, new AuditRequestContext());
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static void authenticateWith(String... authorities) {
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("u", "n", authorities));
    }

    private static KeycloakUser user(String sub) {
        return new KeycloakUser(sub, "name-" + sub, List.of());
    }

    @Test
    void holderOfThePermissionPassesWithoutAnAuditRow() {
        authenticateWith("ROLE_CAN_BO", "PERM_report.read");
        guard.require(user("u1"), ReportAccessGuard.PERM_READ);
        verifyNoInteractions(auditService);
    }

    @Test
    void missingPermissionIs403AndAuditedIndependently() {
        authenticateWith("ROLE_QUAN_TRI", "PERM_account.manage");
        assertThatThrownBy(() -> guard.require(user("u2"), ReportAccessGuard.PERM_READ)).isInstanceOf(ForbiddenException.class);
        ArgumentCaptor<AuditService.AuditEntry> captor = ArgumentCaptor.forClass(AuditService.AuditEntry.class);
        verify(auditService).recordIndependently(captor.capture());
        assertThat(captor.getValue().action()).isEqualTo("report.access_denied");
        assertThat(captor.getValue().actorSub()).isEqualTo("u2");
        assertThat(captor.getValue().outcome()).isEqualTo("failure");
        assertThat(captor.getValue().metadata()).containsEntry("permission", "report.read");
        verify(auditService, never()).record(any());
    }

    @Test
    void readPermissionDoesNotImplyExport() {
        authenticateWith("PERM_report.read");
        assertThatThrownBy(() -> guard.require(user("u3"), ReportAccessGuard.PERM_EXPORT)).isInstanceOf(ForbiddenException.class);
    }

    @Test
    void noSubjectIsForbiddenEvenWithTheAuthorityAndCannotBeAudited() {
        authenticateWith("PERM_report.read");
        assertThatThrownBy(() -> guard.require(user(null), ReportAccessGuard.PERM_READ)).isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> guard.require(user("  "), ReportAccessGuard.PERM_READ)).isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> guard.require(null, ReportAccessGuard.PERM_READ)).isInstanceOf(ForbiddenException.class);
        verifyNoInteractions(auditService);
    }

    @Test
    void noAuthenticationIsForbidden() {
        assertThatThrownBy(() -> guard.require(user("u4"), ReportAccessGuard.PERM_READ)).isInstanceOf(ForbiddenException.class);
    }

    @Test
    void aFailingAuditWriteNeverTurnsTheDenialIntoAnotherError() {
        authenticateWith("ROLE_X");
        doThrow(new IllegalStateException("db down")).when(auditService).recordIndependently(any());
        assertThatThrownBy(() -> guard.require(user("u5"), ReportAccessGuard.PERM_READ)).isInstanceOf(ForbiddenException.class);
    }

    @Test
    void notOwnerDenialIsAuditedWithTheExportId() {
        ForbiddenException e = guard.denyNotOwner(user("u6"), "exp-1");
        assertThat(e).isInstanceOf(ForbiddenException.class);
        ArgumentCaptor<AuditService.AuditEntry> captor = ArgumentCaptor.forClass(AuditService.AuditEntry.class);
        verify(auditService).recordIndependently(captor.capture());
        assertThat(captor.getValue().resourceId()).isEqualTo("exp-1");
        assertThat(captor.getValue().metadata()).containsEntry("reason", "not_owner").containsEntry("export_id", "exp-1");
    }
}
