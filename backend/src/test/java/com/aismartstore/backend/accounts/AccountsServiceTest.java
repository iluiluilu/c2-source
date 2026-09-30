package com.aismartstore.backend.accounts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aismartstore.backend.accounts.dto.CreateAccountDto;
import com.aismartstore.backend.accounts.dto.SetAccountQuotaDto;
import com.aismartstore.backend.accounts.dto.SetAccountStatusDto;
import com.aismartstore.backend.audit.AuditRequestContext;
import com.aismartstore.backend.audit.AuditService;
import com.aismartstore.backend.auth.KeycloakUser;
import com.aismartstore.backend.common.ApiException;
import com.aismartstore.backend.common.BadRequestException;
import com.aismartstore.backend.common.ConflictException;
import com.aismartstore.backend.common.ServiceUnavailableException;
import com.aismartstore.backend.systemconfig.SystemConfigDefaults;
import com.aismartstore.backend.systemconfig.SystemConfigService;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;

/** Unit coverage for account-lifecycle.md §5 ("AccountsServiceTest"). */
class AccountsServiceTest {

    private static final long GB = 1024L * 1024L * 1024L;

    private KeycloakAdminService kc;
    private AuditService auditService;
    private AuditRequestContext auditRequestContext;
    private AccountRepository accountRepository;
    private SystemConfigService systemConfigService;
    private StringRedisTemplate redis;
    private AccountsService service;

    private final KeycloakUser admin = new KeycloakUser("admin-sub", "admin", List.of("quan-tri"));

    @BeforeEach
    void setUp() {
        kc = mock(KeycloakAdminService.class);
        auditService = mock(AuditService.class);
        auditRequestContext = mock(AuditRequestContext.class);
        accountRepository = mock(AccountRepository.class);
        systemConfigService = mock(SystemConfigService.class);
        redis = mock(StringRedisTemplate.class);
        service = new AccountsService(
                kc, auditService, auditRequestContext, accountRepository, systemConfigService, redis);

        when(accountRepository.save(any(Account.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private static Account accountRow(String id, String status, String reason, long quotaBytes) {
        Account row = new Account();
        row.setKeycloakUserId(id);
        row.setStatus(status);
        row.setStatusReason(reason);
        row.setStorageQuotaBytes(quotaBytes);
        row.setCreatedAt(OffsetDateTime.now());
        row.setUpdatedAt(OffsetDateTime.now());
        return row;
    }

    private static Map<String, Object> kcUserRep(String id, String username, boolean enabled) {
        return Map.of("id", id, "username", username, "enabled", enabled, "firstName", "F", "lastName", "L");
    }

    // ---- create(): quota snapshot -----------------------------------------

    @Test
    void createSnapshotsMaxQuotaAcrossMultipleRoles() {
        CreateAccountDto dto = new CreateAccountDto(
                "canbo1", "Nguyen", "Van A", null, List.of("can-bo", "lanh-dao"), "Password123", true, true);
        when(systemConfigService.getLong(SystemConfigDefaults.STORAGE_QUOTA_DEFAULT_CAN_BO)).thenReturn(20 * GB);
        when(systemConfigService.getLong(SystemConfigDefaults.STORAGE_QUOTA_DEFAULT_LANH_DAO)).thenReturn(50 * GB);
        when(kc.createUser(any())).thenReturn("user-1");
        when(kc.getRealmRole("can-bo")).thenReturn(new KcRole("r1", "can-bo"));
        when(kc.getRealmRole("lanh-dao")).thenReturn(new KcRole("r2", "lanh-dao"));

        AccountsService.AccountRow row = service.create(admin, dto);

        assertThat(row.storageQuotaBytes()).isEqualTo(50 * GB);
        ArgumentCaptor<Account> captor = ArgumentCaptor.forClass(Account.class);
        verify(accountRepository).save(captor.capture());
        assertThat(captor.getValue().getStorageQuotaBytes()).isEqualTo(50 * GB);
        assertThat(captor.getValue().getStatus()).isEqualTo("active");
        verify(kc, never()).deleteUser(any());
    }

    @Test
    void createSnapshotsQuotaForSingleRole() {
        CreateAccountDto dto =
                new CreateAccountDto("canbo2", "Tran", "Thi B", null, List.of("quan-tri"), "Password123", true, true);
        when(systemConfigService.getLong(SystemConfigDefaults.STORAGE_QUOTA_DEFAULT_QUAN_TRI)).thenReturn(20 * GB);
        when(kc.createUser(any())).thenReturn("user-2");
        when(kc.getRealmRole("quan-tri")).thenReturn(new KcRole("r3", "quan-tri"));

        AccountsService.AccountRow row = service.create(admin, dto);

        assertThat(row.storageQuotaBytes()).isEqualTo(20 * GB);
    }

    @Test
    void createRollsBackKeycloakUserWhenDbInsertFails() {
        CreateAccountDto dto =
                new CreateAccountDto("canbo3", "Le", "Van C", null, List.of("can-bo"), "Password123", true, true);
        when(systemConfigService.getLong(anyString())).thenReturn(20 * GB);
        when(kc.createUser(any())).thenReturn("user-3");
        when(kc.getRealmRole("can-bo")).thenReturn(new KcRole("r1", "can-bo"));
        when(accountRepository.save(any(Account.class))).thenThrow(new DataIntegrityViolationException("boom"));

        assertThatThrownBy(() -> service.create(admin, dto)).isInstanceOf(DataIntegrityViolationException.class);

        verify(kc).deleteUser("user-3");
    }

    @Test
    void createDuplicateUsernameIsConflictAndSkipsAccountRow() {
        CreateAccountDto dto =
                new CreateAccountDto("dup", "A", "B", null, List.of("can-bo"), "Password123", true, true);
        when(systemConfigService.getLong(anyString())).thenReturn(20 * GB);
        when(kc.createUser(any())).thenThrow(new KcConflictError("duplicate"));

        assertThatThrownBy(() -> service.create(admin, dto))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus()).isEqualTo(HttpStatus.CONFLICT));

        verify(accountRepository, never()).save(any());
    }

    // ---- setStatus(): validation --------------------------------------------

    @Test
    void setStatusRequiresReasonWhenLockingOrDisabling() {
        SetAccountStatusDto dto = new SetAccountStatusDto("locked", "  ");
        assertThatThrownBy(() -> service.setStatus(admin, "target", dto)).isInstanceOf(BadRequestException.class);
        verify(accountRepository, never()).save(any());
    }

    @Test
    void setStatusRejectsSelfAction() {
        SetAccountStatusDto dto = new SetAccountStatusDto("locked", "vắng mặt dài hạn");
        assertThatThrownBy(() -> service.setStatus(admin, admin.sub(), dto))
                .isInstanceOf(BadRequestException.class);
        verify(accountRepository, never()).save(any());
    }

    @Test
    void setStatusNoopWhenAlreadyAtTargetStatusIsConflict() {
        when(accountRepository.findById("target")).thenReturn(Optional.of(accountRow("target", "locked", "x", 1)));
        SetAccountStatusDto dto = new SetAccountStatusDto("locked", "same reason");

        assertThatThrownBy(() -> service.setStatus(admin, "target", dto)).isInstanceOf(ConflictException.class);

        verify(accountRepository, never()).save(any());
        verify(kc, never()).getUser(any());
    }

    // ---- setStatus(): last-active-admin guard (Quyết định 4) ----------------

    @Test
    void setStatusAllowsLockingAdminWhenAnotherActiveAdminRemains() {
        when(accountRepository.findById("admin-2")).thenReturn(Optional.of(accountRow("admin-2", "active", null, 1)));
        when(kc.listRoleUsers(eq("quan-tri"), anyInt()))
                .thenReturn(List.of(Map.of("id", "admin-2"), Map.of("id", "admin-3")));
        when(accountRepository.findStatusById("admin-2")).thenReturn(Optional.of("active"));
        when(accountRepository.findStatusById("admin-3")).thenReturn(Optional.of("active"));
        when(kc.getUser("admin-2")).thenReturn(kcUserRep("admin-2", "admin2", true));

        SetAccountStatusDto dto = new SetAccountStatusDto("locked", "nghỉ việc");
        service.setStatus(admin, "admin-2", dto);

        verify(accountRepository).save(any(Account.class));
        verify(kc).updateUser(eq("admin-2"), any());
    }

    @Test
    void setStatusBlocksLockingTheLastActiveAdmin() {
        when(accountRepository.findById("admin-2")).thenReturn(Optional.of(accountRow("admin-2", "active", null, 1)));
        when(kc.listRoleUsers(eq("quan-tri"), anyInt()))
                .thenReturn(List.of(Map.of("id", "admin-2"), Map.of("id", "admin-3")));
        when(accountRepository.findStatusById("admin-2")).thenReturn(Optional.of("active"));
        when(accountRepository.findStatusById("admin-3")).thenReturn(Optional.of("locked"));

        SetAccountStatusDto dto = new SetAccountStatusDto("locked", "nghỉ việc");

        assertThatThrownBy(() -> service.setStatus(admin, "admin-2", dto))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus()).isEqualTo(HttpStatus.CONFLICT))
                .satisfies(e -> assertThat(((ApiException) e).getBody()).isInstanceOf(Map.class))
                .satisfies(e -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> body = (Map<String, Object>) ((ApiException) e).getBody();
                    assertThat(body).containsEntry("code", "LAST_ADMIN");
                });

        verify(accountRepository, never()).save(any());
        verify(kc, never()).updateUser(any(), any());
    }

    @Test
    void setStatusGuardDoesNotActivateWhenUnlockingAnAdmin() {
        when(accountRepository.findById("admin-2")).thenReturn(Optional.of(accountRow("admin-2", "locked", "x", 1)));
        when(kc.getUser("admin-2")).thenReturn(kcUserRep("admin-2", "admin2", false));

        SetAccountStatusDto dto = new SetAccountStatusDto("active", null);
        service.setStatus(admin, "admin-2", dto);

        verify(kc, never()).listRoleUsers(eq("quan-tri"), anyInt());
        verify(accountRepository).save(any(Account.class));
    }

    // ---- setStatus(): Keycloak sync compensation -----------------------------

    @Test
    void setStatusCompensatesDbWhenKeycloakUpdateFails() {
        when(accountRepository.findById("target")).thenReturn(Optional.of(accountRow("target", "active", null, 1)));
        when(kc.getUser("target")).thenThrow(new KeycloakUnavailableError("kc down"));

        SetAccountStatusDto dto = new SetAccountStatusDto("locked", "sự cố");

        assertThatThrownBy(() -> service.setStatus(admin, "target", dto))
                .isInstanceOf(ServiceUnavailableException.class);

        ArgumentCaptor<Account> captor = ArgumentCaptor.forClass(Account.class);
        verify(accountRepository, org.mockito.Mockito.times(2)).save(captor.capture());
        // last save is the compensating write, back to the original status
        assertThat(captor.getValue().getStatus()).isEqualTo("active");
        assertThat(captor.getValue().getStatusReason()).isNull();
    }

    // ---- setStatus(): best-effort logout --------------------------------------

    @Test
    void setStatusSucceedsAndFlagsAuditWhenLogoutCallFails() {
        when(accountRepository.findById("target")).thenReturn(Optional.of(accountRow("target", "active", null, 1)));
        when(kc.getUser("target")).thenReturn(kcUserRep("target", "someuser", true));
        org.mockito.Mockito.doThrow(new KeycloakUnavailableError("logout failed")).when(kc).logoutUser("target");

        SetAccountStatusDto dto = new SetAccountStatusDto("disabled", "nghỉ việc");
        service.setStatus(admin, "target", dto);

        verify(accountRepository).save(any(Account.class));
        ArgumentCaptor<AuditService.AuditEntry> captor = ArgumentCaptor.forClass(AuditService.AuditEntry.class);
        verify(auditService).recordAsync(captor.capture());
        assertThat(captor.getValue().action()).isEqualTo("account.disable");
        assertThat(captor.getValue().metadata()).containsEntry("logoutCallFailed", true);
    }

    // ---- setQuota() ------------------------------------------------------

    @Test
    void setQuotaGetsOrCreatesRowWhenMissing() {
        when(accountRepository.findById("legacy-user")).thenReturn(Optional.empty());
        when(kc.listUserRealmRoles("legacy-user")).thenReturn(List.of(new KcRole("r1", "can-bo")));
        when(systemConfigService.getLong(SystemConfigDefaults.STORAGE_QUOTA_DEFAULT_CAN_BO)).thenReturn(20 * GB);

        AccountsService.QuotaResult result =
                service.setQuota(admin, "legacy-user", new SetAccountQuotaDto(5 * GB));

        assertThat(result.storageQuotaBytes()).isEqualTo(5 * GB);
        ArgumentCaptor<Account> captor = ArgumentCaptor.forClass(Account.class);
        verify(accountRepository, org.mockito.Mockito.times(2)).save(captor.capture());
        assertThat(captor.getValue().getStorageQuotaBytes()).isEqualTo(5 * GB);
    }

    @Test
    void setQuotaNegativeValueFailsBeanValidation() {
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        Set<ConstraintViolation<SetAccountQuotaDto>> violations = validator.validate(new SetAccountQuotaDto(-1L));
        assertThat(violations).isNotEmpty();
    }
}
