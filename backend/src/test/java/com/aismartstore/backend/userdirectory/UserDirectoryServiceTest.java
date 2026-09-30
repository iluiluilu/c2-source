package com.aismartstore.backend.userdirectory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aismartstore.backend.accounts.Account;
import com.aismartstore.backend.accounts.AccountRepository;
import com.aismartstore.backend.accounts.KcRole;
import com.aismartstore.backend.accounts.KeycloakAdminService;
import com.aismartstore.backend.audit.AuditRequestContext;
import com.aismartstore.backend.audit.AuditService;
import com.aismartstore.backend.auth.KeycloakUser;
import com.aismartstore.backend.common.BadRequestException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit coverage for group-management-sharing-01-group-lifecycle.md §5, Story 5. */
class UserDirectoryServiceTest {

    private KeycloakAdminService kc;
    private AccountRepository accountRepository;
    private UserDirectoryService service;

    private final KeycloakUser caller = new KeycloakUser("caller-sub", "caller", List.of("can-bo"));

    @BeforeEach
    void setUp() {
        kc = mock(KeycloakAdminService.class);
        accountRepository = mock(AccountRepository.class);
        AuditService auditService = mock(AuditService.class);
        AuditRequestContext auditRequestContext = mock(AuditRequestContext.class);
        service = new UserDirectoryService(kc, accountRepository, auditService, auditRequestContext);
    }

    private static Map<String, Object> kcUser(String id, String username, String first, String last) {
        Map<String, Object> user = new java.util.HashMap<>();
        user.put("id", id);
        user.put("username", username);
        user.put("firstName", first);
        user.put("lastName", last);
        return user;
    }

    private static Account accountRow(String id, String status) {
        Account row = new Account();
        row.setKeycloakUserId(id);
        row.setStatus(status);
        row.setStorageQuotaBytes(0);
        row.setCreatedAt(OffsetDateTime.now());
        row.setUpdatedAt(OffsetDateTime.now());
        return row;
    }

    @Test
    void queryShorterThanTwoCharactersIsRejected() {
        assertThatThrownBy(() -> service.search(caller, "a")).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.search(caller, " a ")).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.search(caller, null)).isInstanceOf(BadRequestException.class);
    }

    @Test
    void lockedAccountsAreExcludedFromResults() {
        when(kc.searchUsers(anyString(), anyInt())).thenReturn(List.of(
                kcUser("active-user", "active1", "A", "One"), kcUser("locked-user", "locked1", "L", "One")));
        when(accountRepository.findById("active-user")).thenReturn(Optional.of(accountRow("active-user", "active")));
        when(accountRepository.findById("locked-user")).thenReturn(Optional.of(accountRow("locked-user", "locked")));
        when(kc.listUserRealmRoles(anyString())).thenReturn(List.of(new KcRole("r1", "can-bo")));

        List<UserDirectoryEntry> results = service.search(caller, "user");

        assertThat(results).extracting(UserDirectoryEntry::sub).containsExactly("active-user");
    }

    @Test
    void accountsWithNoLocalRowYetDefaultToActive() {
        when(kc.searchUsers(anyString(), anyInt())).thenReturn(List.of(kcUser("brand-new", "newbie", null, null)));
        when(accountRepository.findById("brand-new")).thenReturn(Optional.empty());
        when(kc.listUserRealmRoles("brand-new")).thenReturn(List.of(new KcRole("r1", "lanh-dao")));

        List<UserDirectoryEntry> results = service.search(caller, "new");

        assertThat(results).hasSize(1);
        assertThat(results.get(0).displayName()).isEqualTo("newbie"); // no first/last name -> falls back to username
        assertThat(results.get(0).role()).isEqualTo("lanh-dao");
    }

    @Test
    void serviceAccountsAreNeverReturned() {
        when(kc.searchUsers(anyString(), anyInt()))
                .thenReturn(List.of(kcUser("svc-1", "service-account-backend", "S", "A")));

        List<UserDirectoryEntry> results = service.search(caller, "backend");

        assertThat(results).isEmpty();
    }

    @Test
    void resultsAreCappedAtTwenty() {
        List<Map<String, Object>> many = new java.util.ArrayList<>();
        for (int i = 0; i < 30; i++) {
            String id = "user-" + i;
            many.add(kcUser(id, "user" + i, "F", "L"));
            when(accountRepository.findById(id)).thenReturn(Optional.of(accountRow(id, "active")));
        }
        when(kc.searchUsers(anyString(), anyInt())).thenReturn(many);
        when(kc.listUserRealmRoles(anyString())).thenReturn(List.of());

        List<UserDirectoryEntry> results = service.search(caller, "user");

        assertThat(results).hasSize(20);
    }
}
