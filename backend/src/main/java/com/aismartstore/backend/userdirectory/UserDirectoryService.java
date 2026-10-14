package com.aismartstore.backend.userdirectory;

import com.aismartstore.backend.accounts.Account;
import com.aismartstore.backend.accounts.AccountRepository;
import com.aismartstore.backend.accounts.AssignableRoles;
import com.aismartstore.backend.accounts.KcRole;
import com.aismartstore.backend.accounts.KeycloakAdminService;
import com.aismartstore.backend.accounts.KeycloakUnavailableError;
import com.aismartstore.backend.audit.AuditRequestContext;
import com.aismartstore.backend.audit.AuditService;
import com.aismartstore.backend.auth.KeycloakUser;
import com.aismartstore.backend.common.BadRequestException;
import com.aismartstore.backend.common.ServiceUnavailableException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * group-management-sharing-01-group-lifecycle.md §4.7 (Story 5): a minimal
 * user search so a group owner can find a {@code sub} to add as a member,
 * without exposing the full {@code account.read} (R3-only) surface to R1/R2
 * (NFR-04).
 *
 * <p><b>Deviation from the spec's §2/§4.7 sketch, flagged for review:</b> the
 * spec describes this as an {@code AccountRepository} LIKE query over
 * {@code username}/{@code display_name}/{@code active} columns. Those columns
 * do not exist - the local {@code account} table (account-lifecycle.md §2.1)
 * only stores {@code status}/{@code status_reason}/{@code
 * storage_quota_bytes}; username/name/enabled live in Keycloak only and are
 * fetched through {@link KeycloakAdminService} (see how {@code
 * AccountsService#list()} already does exactly this join). This class follows
 * that same, already-established pattern instead: search Keycloak, then
 * filter/annotate with the local {@code account.status}.
 */
@Service
public class UserDirectoryService {

    private static final int MAX_RESULTS = 20;

    /** Keycloak-side fetch size before local filtering - generous enough that a handful of locked/disabled hits don't starve the 20-result page. */
    private static final int SEARCH_FETCH_SIZE = 100;

    private final KeycloakAdminService kc;
    private final AccountRepository accountRepository;
    private final AuditService auditService;
    private final AuditRequestContext auditRequestContext;

    public UserDirectoryService(
            KeycloakAdminService kc,
            AccountRepository accountRepository,
            AuditService auditService,
            AuditRequestContext auditRequestContext) {
        this.kc = kc;
        this.accountRepository = accountRepository;
        this.auditService = auditService;
        this.auditRequestContext = auditRequestContext;
    }

    public List<UserDirectoryEntry> search(KeycloakUser user, String q) {
        String keyword = q == null ? "" : q.trim();
        if (keyword.length() < 2) {
            throw new BadRequestException("Từ khoá tìm kiếm phải có ít nhất 2 ký tự");
        }

        List<Map<String, Object>> candidates;
        try {
            candidates = kc.searchUsers(keyword, SEARCH_FETCH_SIZE);
        } catch (KeycloakUnavailableError e) {
            throw new ServiceUnavailableException("Không thể kết nối máy chủ định danh (Keycloak)");
        }

        List<UserDirectoryEntry> results = new ArrayList<>();
        for (Map<String, Object> candidate : candidates) {
            if (results.size() >= MAX_RESULTS) {
                break;
            }
            String sub = (String) candidate.get("id");
            String username = (String) candidate.get("username");
            if (sub == null || username == null || username.startsWith("service-account-")) {
                continue;
            }
            if (!isActive(sub)) {
                continue;
            }
            results.add(new UserDirectoryEntry(sub, username, displayName(candidate, username), roleOf(sub)));
        }

        auditService.recordAsync(new AuditService.AuditEntry(
                user.preferredUsername(), user.sub(), "user.directory_search", "user", null, "success",
                auditRequestContext.method(), auditRequestContext.path(), 200, auditRequestContext.ip(),
                Map.of("q", keyword)));
        return results;
    }

    /** No local row yet means "never touched by account-lifecycle's lazy backfill" - treated as active, same default {@link com.aismartstore.backend.accounts.AccountsService} uses. */
    private boolean isActive(String sub) {
        return accountRepository.findById(sub).map(Account::getStatus).orElse("active").equals("active");
    }

    private static String displayName(Map<String, Object> user, String fallback) {
        String firstName = (String) user.get("firstName");
        String lastName = (String) user.get("lastName");
        String combined = ((firstName != null ? firstName : "") + " " + (lastName != null ? lastName : "")).trim();
        return combined.isEmpty() ? fallback : combined;
    }

    /** First assignable role held, if any - a directory entry with none (e.g. a stray Keycloak account) still returns, just with {@code role = null}. */
    private String roleOf(String sub) {
        try {
            return kc.listUserRealmRoles(sub).stream()
                    .map(KcRole::name)
                    .filter(AssignableRoles.ALL::contains)
                    .findFirst()
                    .orElse(null);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
