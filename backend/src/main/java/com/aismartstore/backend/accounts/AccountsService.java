package com.aismartstore.backend.accounts;

import com.aismartstore.backend.accounts.dto.ChangeOwnPasswordDto;
import com.aismartstore.backend.accounts.dto.CreateAccountDto;
import com.aismartstore.backend.accounts.dto.ResetPasswordDto;
import com.aismartstore.backend.accounts.dto.SetAccountQuotaDto;
import com.aismartstore.backend.accounts.dto.SetAccountStatusDto;
import com.aismartstore.backend.accounts.dto.UpdateAccountDto;
import com.aismartstore.backend.audit.AuditRequestContext;
import com.aismartstore.backend.audit.AuditService;
import com.aismartstore.backend.auth.KeycloakUser;
import com.aismartstore.backend.common.ApiException;
import com.aismartstore.backend.common.BadRequestException;
import com.aismartstore.backend.common.ConflictException;
import com.aismartstore.backend.common.NotFoundException;
import com.aismartstore.backend.common.ServiceUnavailableException;
import com.aismartstore.backend.systemconfig.SystemConfigDefaults;
import com.aismartstore.backend.systemconfig.SystemConfigService;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Account management over the Keycloak Admin API (FR-04/05/06) plus the
 * local {@code account} table added by account-lifecycle.md for
 * status/quota (Keycloak has no concept of either). Every mutation lands in
 * the audit chain (FR-09) via AuditService, like any other feature module.
 * Java port of the NestJS backend's accounts/accounts.service.ts, extended
 * per docs/03-dev/specs/account-lifecycle.md.
 */
@Service
public class AccountsService {

    private static final Logger log = LoggerFactory.getLogger(AccountsService.class);

    /** Story 3/4 valid values for {@code account.status}. */
    private static final Set<String> VALID_STATUSES = Set.of("active", "locked", "disabled");

    /**
     * Upper bound for {@code kc.listRoleUsers("quan-tri", ...)} used by the
     * "last active admin" guard (mục 4.3) - must stay well above any
     * realistic R3 headcount, or the guard undercounts and blocks wrongly.
     */
    private static final int ADMIN_ROLE_LOOKUP_MAX = 1000;

    private final KeycloakAdminService kc;
    private final AuditService auditService;
    private final AuditRequestContext auditRequestContext;
    private final AccountRepository accountRepository;
    private final SystemConfigService systemConfigService;
    private final StringRedisTemplate redis;

    public AccountsService(
            KeycloakAdminService kc,
            AuditService auditService,
            AuditRequestContext auditRequestContext,
            AccountRepository accountRepository,
            SystemConfigService systemConfigService,
            StringRedisTemplate redis) {
        this.kc = kc;
        this.auditService = auditService;
        this.auditRequestContext = auditRequestContext;
        this.accountRepository = accountRepository;
        this.systemConfigService = systemConfigService;
        this.redis = redis;
    }

    public record AccountRow(
            String id, String username, String firstName, String lastName, String email, boolean enabled,
            List<String> roles, String status, String statusReason, long storageQuotaBytes) {
    }

    public record QuotaResult(long storageQuotaBytes) {
    }

    /** Maps the KeycloakAdminService error family onto this project's HTTP exceptions. */
    private static RuntimeException mapKcError(RuntimeException error) {
        if (error instanceof KcConflictError) {
            // duplicateEmailsAllowed=false, so both username and email collide as 409.
            return new ConflictException("Tên đăng nhập hoặc email đã tồn tại");
        }
        if (error instanceof KcBadRequestError) {
            // Typically the password policy text - surface it.
            return new BadRequestException(error.getMessage());
        }
        if (error instanceof KcNotFoundError) {
            return new NotFoundException("Không tìm thấy tài khoản");
        }
        if (error instanceof KeycloakUnavailableError) {
            return new ServiceUnavailableException("Không thể kết nối máy chủ định danh (Keycloak)");
        }
        return error;
    }

    private static AccountRow toRow(Map<String, Object> user, List<String> roles, Account account) {
        return new AccountRow(
                (String) user.get("id"),
                (String) user.get("username"),
                (String) user.get("firstName"),
                (String) user.get("lastName"),
                (String) user.get("email"),
                !Boolean.FALSE.equals(user.get("enabled")),
                roles,
                account.getStatus(),
                account.getStatusReason(),
                account.getStorageQuotaBytes());
    }

    /**
     * FR-04: list accounts with their app roles. One users query plus one
     * query per assignable role (constant fan-out, not per-user role lookups).
     * Extended (account-lifecycle.md §3) to join {@code status}/
     * {@code statusReason}/{@code storageQuotaBytes} from the local
     * {@code account} table, lazily backfilling a row for any account created
     * before that table existed (§2.2).
     */
    public List<AccountRow> list() {
        try {
            List<Map<String, Object>> users = kc.listUsers(200);
            Map<String, List<String>> rolesByUserId = new HashMap<>();
            for (String role : AssignableRoles.ALL) {
                for (Map<String, Object> member : kc.listRoleUsers(role, 200)) {
                    rolesByUserId
                            .computeIfAbsent((String) member.get("id"), k -> new ArrayList<>())
                            .add(role);
                }
            }
            return users.stream()
                    .filter(u -> !((String) u.get("username")).startsWith("service-account-"))
                    .map(u -> {
                        List<String> roles = rolesByUserId.getOrDefault((String) u.get("id"), List.of());
                        Account account = getOrCreateAccountRow((String) u.get("id"), roles);
                        return toRow(u, roles, account);
                    })
                    .sorted((a, b) -> a.username().compareToIgnoreCase(b.username()))
                    .toList();
        } catch (RuntimeException e) {
            throw mapKcError(e);
        }
    }

    /**
     * FR-04: create an account with initial password, role(s) and a
     * snapshotted personal storage quota (account-lifecycle.md §4.1).
     */
    public AccountRow create(KeycloakUser user, CreateAccountDto dto) {
        for (String role : dto.roles()) {
            if (!AssignableRoles.ALL.contains(role)) {
                throw new BadRequestException("Unknown role: " + role);
            }
        }

        long quotaBytes = maxDefaultQuota(dto.roles());

        boolean temporary = dto.temporary() == null || dto.temporary();
        boolean enabled = dto.enabled() == null || dto.enabled();
        String id;
        try {
            Map<String, Object> rep = new LinkedHashMap<>();
            rep.put("username", dto.username());
            rep.put("firstName", dto.firstName());
            rep.put("lastName", dto.lastName());
            if (dto.email() != null) {
                rep.put("email", dto.email());
            }
            rep.put("enabled", enabled);
            rep.put("emailVerified", true);
            id = kc.createUser(rep);
        } catch (RuntimeException e) {
            throw mapKcError(e);
        }

        Account savedRow;
        try {
            kc.resetPassword(id, dto.password(), temporary);
            List<KcRole> roleReps = new ArrayList<>();
            for (String role : dto.roles()) {
                roleReps.add(kc.getRealmRole(role));
            }
            kc.addRealmRoles(id, roleReps);

            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
            Account row = new Account();
            row.setKeycloakUserId(id);
            row.setStatus("active");
            row.setStatusReason(null);
            row.setStorageQuotaBytes(quotaBytes);
            row.setCreatedAt(now);
            row.setUpdatedAt(now);
            savedRow = accountRepository.save(row);
        } catch (RuntimeException e) {
            // Don't leave a half-created account (no password / no role / no
            // account row) behind - covers Keycloak errors and the DB insert.
            try {
                kc.deleteUser(id);
            } catch (RuntimeException cleanupError) {
                log.error(
                        "rollback delete of half-created account {} ({}) failed: {}",
                        dto.username(), id, cleanupError.getMessage());
            }
            throw mapKcError(e);
        }

        auditService.recordAsync(new AuditService.AuditEntry(
                user.preferredUsername(), user.sub(), "account.create", "account", id, "success",
                auditRequestContext.method(), auditRequestContext.path(), 200, auditRequestContext.ip(),
                Map.of(
                        "username", dto.username(),
                        "roles", dto.roles(),
                        "temporary", temporary,
                        "storageQuotaBytes", quotaBytes)));

        return new AccountRow(
                id, dto.username(), dto.firstName(), dto.lastName(), dto.email(), enabled, dto.roles(),
                savedRow.getStatus(), savedRow.getStatusReason(), savedRow.getStorageQuotaBytes());
    }

    /** FR-04: edit profile fields and/or roles. Does not touch the {@code account} row (§4.2). */
    public AccountRow update(KeycloakUser user, String id, UpdateAccountDto dto) {
        try {
            Map<String, Object> rep = kc.getUser(id);

            List<String> changed = new ArrayList<>();
            if (dto.firstName() != null && !dto.firstName().equals(rep.get("firstName"))) {
                changed.add("firstName");
            }
            if (dto.lastName() != null && !dto.lastName().equals(rep.get("lastName"))) {
                changed.add("lastName");
            }
            if (dto.email() != null && !dto.email().equals(rep.get("email"))) {
                changed.add("email");
            }

            Map<String, Object> merged = new LinkedHashMap<>(rep);
            if (dto.firstName() != null) {
                merged.put("firstName", dto.firstName());
            }
            if (dto.lastName() != null) {
                merged.put("lastName", dto.lastName());
            }
            if (dto.email() != null) {
                merged.put("email", dto.email());
            }
            if (!changed.isEmpty()) {
                // PUT is a full replace: merge onto the fetched representation.
                kc.updateUser(id, merged);
            }

            List<KcRole> current = kc.listUserRealmRoles(id);
            List<KcRole> currentAssignable =
                    current.stream().filter(r -> AssignableRoles.ALL.contains(r.name())).toList();

            List<String> roles;
            List<String> rolesAdded = List.of();
            List<String> rolesRemoved = List.of();
            if (dto.roles() != null) {
                for (String role : dto.roles()) {
                    if (!AssignableRoles.ALL.contains(role)) {
                        throw new BadRequestException("Unknown role: " + role);
                    }
                }
                Set<String> desired = new HashSet<>(dto.roles());
                Set<String> held = currentAssignable.stream().map(KcRole::name).collect(java.util.stream.Collectors.toSet());
                rolesAdded = dto.roles().stream().filter(r -> !held.contains(r)).toList();
                List<KcRole> toRemove = currentAssignable.stream().filter(r -> !desired.contains(r.name())).toList();
                rolesRemoved = toRemove.stream().map(KcRole::name).toList();
                // Only ever remove whitelisted roles - unknown roles are untouched.
                if (!rolesAdded.isEmpty()) {
                    List<KcRole> reps = new ArrayList<>();
                    for (String role : rolesAdded) {
                        reps.add(kc.getRealmRole(role));
                    }
                    kc.addRealmRoles(id, reps);
                }
                if (!toRemove.isEmpty()) {
                    kc.removeRealmRoles(id, toRemove);
                }
                roles = dto.roles();
            } else {
                roles = currentAssignable.stream().map(KcRole::name).toList();
            }

            auditService.recordAsync(new AuditService.AuditEntry(
                    user.preferredUsername(), user.sub(), "account.update", "account", id, "success",
                    auditRequestContext.method(), auditRequestContext.path(), 200, auditRequestContext.ip(),
                    Map.of("targetUsername", rep.get("username"), "changed", changed, "rolesAdded", rolesAdded,
                            "rolesRemoved", rolesRemoved)));

            // role changes never re-snapshot quota (snapshot-once decision) - only
            // get-or-create so the response shape always includes status/quota.
            Account account = getOrCreateAccountRow(id, roles);
            return toRow(merged, roles, account);
        } catch (RuntimeException e) {
            throw mapKcError(e);
        }
    }

    /**
     * Story 3/4: lock/unlock/disable an account. DB (§4.3) is the source of
     * truth consumed by {@link AccountStatusFilter}, so it is written first;
     * the Keycloak {@code enabled} flag and the best-effort logout follow.
     * Never your own account, and never the last {@code active} R3 (§0
     * Quyết định 4).
     */
    public void setStatus(KeycloakUser user, String id, SetAccountStatusDto dto) {
        if (id.equals(user.sub())) {
            throw new BadRequestException("Không thể " + selfStatusActionLabel(dto.status()) + " tài khoản của chính mình");
        }
        String status = dto.status();
        if (!VALID_STATUSES.contains(status)) {
            throw new BadRequestException("Trạng thái không hợp lệ: " + status);
        }
        boolean active = "active".equals(status);
        if (dto.reason() == null || dto.reason().isBlank()) {
            throw new BadRequestException("Cần nêu lý do khi thay đổi trạng thái tài khoản");
        }

        Account account = getOrCreateAccountRowLazy(id);
        if (status.equals(account.getStatus())) {
            throw new ConflictException("Tài khoản đã ở trạng thái '" + status + "'");
        }

        guardLastActiveAdmin(id, status);

        String oldStatus = account.getStatus();
        String oldReason = account.getStatusReason();

        // Step 2: DB write - the source of truth AccountStatusFilter reads.
        // A DB failure here aborts before Keycloak is ever touched (§4.3 step 2).
        account.setStatus(status);
        account.setStatusReason(active ? null : dto.reason());
        account.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        try {
            accountRepository.save(account);
        } catch (RuntimeException e) {
            throw new ServiceUnavailableException("Không thể ghi trạng thái tài khoản");
        }

        // Step 3: evict the shared Redis cache so every backend instance
        // stops trusting the old status immediately (§4.3 step 3).
        evictStatusCache(id);

        // Step 4: sync Keycloak's enabled flag; any failure here compensates
        // the DB write back and reports 503, per §4.3 step 4.
        String username;
        try {
            Map<String, Object> rep = kc.getUser(id);
            username = (String) rep.get("username");
            Map<String, Object> merged = new LinkedHashMap<>(rep);
            merged.put("enabled", active);
            kc.updateUser(id, merged);
        } catch (RuntimeException e) {
            account.setStatus(oldStatus);
            account.setStatusReason(oldReason);
            account.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
            accountRepository.save(account);
            evictStatusCache(id);
            throw new ServiceUnavailableException("Không thể đồng bộ trạng thái với Keycloak");
        }

        // Step 5: best-effort session termination (lớp 1) - never rolled back.
        boolean logoutCallFailed = false;
        if (!active) {
            try {
                kc.logoutUser(id);
            } catch (RuntimeException e) {
                log.error("logout call failed for account {} after status change to {}: {}", id, status, e.getMessage());
                logoutCallFailed = true;
            }
        }

        String action = active ? "account.unlock" : ("locked".equals(status) ? "account.lock" : "account.disable");
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("targetUsername", username);
        metadata.put("reason", dto.reason());
        if (logoutCallFailed) {
            metadata.put("logoutCallFailed", true);
        }
        auditService.recordAsync(new AuditService.AuditEntry(
                user.preferredUsername(), user.sub(), action, "account", id, "success",
                auditRequestContext.method(), auditRequestContext.path(), 200, auditRequestContext.ip(),
                metadata));
    }

    /**
     * Story 5: set an account's personal storage quota directly - purely
     * internal, no Keycloak call, no retroactive enforcement (§4.4).
     */
    public QuotaResult setQuota(KeycloakUser user, String id, SetAccountQuotaDto dto) {
        Account account = getOrCreateAccountRowLazy(id);
        long oldValue = account.getStorageQuotaBytes();
        long newValue = dto.storageQuotaBytes();

        account.setStorageQuotaBytes(newValue);
        account.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        accountRepository.save(account);

        auditService.recordAsync(new AuditService.AuditEntry(
                user.preferredUsername(), user.sub(), "account.quota_update", "account", id, "success",
                auditRequestContext.method(), auditRequestContext.path(), 200, auditRequestContext.ip(),
                Map.of("oldValue", oldValue, "newValue", newValue)));

        return new QuotaResult(newValue);
    }

    /**
     * FR-06: admin sets a new password for any account - no old password
     * needed, never sees the old one, operation is audit-logged.
     */
    public void resetPassword(KeycloakUser user, String id, ResetPasswordDto dto) {
        boolean temporary = dto.temporary() == null || dto.temporary();
        String username;
        try {
            Map<String, Object> rep = kc.getUser(id);
            username = (String) rep.get("username");
            kc.resetPassword(id, dto.password(), temporary);
        } catch (RuntimeException e) {
            throw mapKcError(e);
        }
        auditService.recordAsync(new AuditService.AuditEntry(
                user.preferredUsername(), user.sub(), "account.password_reset", "account", id, "success",
                auditRequestContext.method(), auditRequestContext.path(), 200, auditRequestContext.ip(),
                Map.of("targetUsername", username, "temporary", temporary)));
    }

    /**
     * FR-05: change own password. The current password is verified against
     * Keycloak (password grant) before the reset; a wrong current password is
     * itself an audited failure.
     */
    public void changeOwnPassword(KeycloakUser user, ChangeOwnPasswordDto dto) {
        if (dto.newPassword().equals(dto.currentPassword())) {
            throw new BadRequestException("Mật khẩu mới phải khác mật khẩu hiện tại");
        }

        KeycloakAdminService.PasswordVerification verdict;
        try {
            verdict = kc.verifyPassword(user.preferredUsername(), dto.currentPassword());
        } catch (RuntimeException e) {
            throw mapKcError(e);
        }

        if (verdict == KeycloakAdminService.PasswordVerification.INVALID) {
            auditService.recordAsync(new AuditService.AuditEntry(
                    user.preferredUsername(), user.sub(), "account.password_change", "account", user.sub(),
                    "failure", auditRequestContext.method(), auditRequestContext.path(), null,
                    auditRequestContext.ip(), Map.of("reason", "wrong current password")));
            throw new BadRequestException("Mật khẩu hiện tại không đúng");
        }
        if (verdict == KeycloakAdminService.PasswordVerification.NOT_FULLY_SET_UP) {
            throw new ConflictException(
                    "Tài khoản đang chờ đổi mật khẩu bắt buộc — hãy đăng xuất và đăng nhập lại để hoàn tất");
        }
        if (verdict == KeycloakAdminService.PasswordVerification.DISABLED) {
            throw new ConflictException("Tài khoản đã bị khóa");
        }

        try {
            kc.resetPassword(user.sub(), dto.newPassword(), false);
        } catch (RuntimeException e) {
            throw mapKcError(e);
        }

        auditService.recordAsync(new AuditService.AuditEntry(
                user.preferredUsername(), user.sub(), "account.password_change", "account", user.sub(), "success",
                auditRequestContext.method(), auditRequestContext.path(), 200, auditRequestContext.ip(), null));
    }

    // ---- account table get-or-create (§2.2) ------------------------------

    /**
     * Get-or-create the {@code account} row for {@code keycloakUserId}
     * (mục 2.2): accounts created before {@code V8} - or simply never
     * touched by this feature yet - get a lazily-inserted row with
     * {@code status='active'} and the quota snapshotted the same way as
     * {@link #create}, MAX over {@code currentRoles}.
     */
    public Account getOrCreateAccountRow(String keycloakUserId, List<String> currentRoles) {
        return accountRepository.findById(keycloakUserId)
                .orElseGet(() -> insertAccountRow(keycloakUserId, currentRoles));
    }

    /**
     * Same get-or-create as {@link #getOrCreateAccountRow}, for the
     * {@code setStatus}/{@code setQuota} call sites that don't already have
     * the caller's role list in hand: {@code currentRoles} is only resolved
     * from Keycloak ({@link #assignableRolesOf}) on the (rare, post-rollout)
     * path where a row still needs to be created, not on every call.
     */
    private Account getOrCreateAccountRowLazy(String keycloakUserId) {
        return accountRepository.findById(keycloakUserId)
                .orElseGet(() -> insertAccountRow(keycloakUserId, assignableRolesOf(keycloakUserId)));
    }

    private Account insertAccountRow(String keycloakUserId, List<String> currentRoles) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        Account row = new Account();
        row.setKeycloakUserId(keycloakUserId);
        row.setStatus("active");
        row.setStatusReason(null);
        row.setStorageQuotaBytes(maxDefaultQuota(currentRoles));
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        try {
            return accountRepository.save(row);
        } catch (DataIntegrityViolationException e) {
            // Lost a get-or-create race to a concurrent caller for the same
            // id; the row exists now, so just re-read it.
            return accountRepository.findById(keycloakUserId).orElseThrow(() -> e);
        }
    }

    /** MAX of the default quota (system-config.md) across {@code roles} - Quyết định 1 mục 0. */
    private long maxDefaultQuota(List<String> roles) {
        long max = 0;
        for (String role : roles) {
            max = Math.max(max, systemConfigService.getLong(defaultQuotaKey(role)));
        }
        return max;
    }

    private static String defaultQuotaKey(String role) {
        return switch (role) {
            case "can-bo" -> SystemConfigDefaults.STORAGE_QUOTA_DEFAULT_CAN_BO;
            case "lanh-dao" -> SystemConfigDefaults.STORAGE_QUOTA_DEFAULT_LANH_DAO;
            case "quan-tri" -> SystemConfigDefaults.STORAGE_QUOTA_DEFAULT_QUAN_TRI;
            default -> throw new IllegalStateException("Unknown role for quota lookup: " + role);
        };
    }

    /**
     * Assignable roles currently held by {@code id} in Keycloak - only
     * needed by {@link #getOrCreateAccountRowLazy} when the row does not
     * exist yet (backfill quota calc, §2.2); a nonexistent Keycloak user
     * surfaces as the usual 404 here.
     */
    private List<String> assignableRolesOf(String id) {
        try {
            return kc.listUserRealmRoles(id).stream()
                    .map(KcRole::name)
                    .filter(AssignableRoles.ALL::contains)
                    .toList();
        } catch (RuntimeException e) {
            throw mapKcError(e);
        }
    }

    // ---- last-active-admin guard (Quyết định 4 mục 0) ---------------------

    /**
     * Blocks locking/disabling the last {@code active} R3, per Quyết định 4:
     * only relevant when the target is being taken out of {@code active} and
     * currently holds {@code quan-tri}. The role list must come from
     * Keycloak - the {@code account} table does not store roles.
     */
    private void guardLastActiveAdmin(String targetId, String newStatus) {
        if ("active".equals(newStatus)) {
            return;
        }
        List<Map<String, Object>> admins;
        try {
            admins = kc.listRoleUsers("quan-tri", ADMIN_ROLE_LOOKUP_MAX);
        } catch (RuntimeException e) {
            throw mapKcError(e);
        }
        boolean targetIsAdmin = admins.stream().anyMatch(m -> targetId.equals(m.get("id")));
        if (!targetIsAdmin) {
            return;
        }
        long activeAdminCount = admins.stream()
                .map(m -> (String) m.get("id"))
                .filter(adminId -> accountRepository.findStatusById(adminId).orElse("active").equals("active"))
                .count();
        if (activeAdminCount <= 1) {
            throw lastAdminConflict(newStatus);
        }
    }

    private static String selfStatusActionLabel(String status) {
        return switch (status == null ? "" : status) {
            case "locked" -> "khóa";
            case "disabled" -> "vô hiệu hóa";
            case "active" -> "mở khóa";
            default -> "khóa/mở khóa/vô hiệu hóa";
        };
    }

    private static ApiException lastAdminConflict(String newStatus) {
        String action = "locked".equals(newStatus) ? "khóa" : "vô hiệu hóa";
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", "LAST_ADMIN");
        body.put("message", "Không thể " + action + " quản trị viên (R3) đang hoạt động cuối cùng");
        return new ApiException(HttpStatus.CONFLICT, body, "Last active admin");
    }

    // ---- Redis cache eviction (§4.3 step 3) -------------------------------

    private void evictStatusCache(String id) {
        try {
            redis.delete(AccountStatusCacheKeys.keyFor(id));
        } catch (RuntimeException e) {
            // DB already committed and is the source of truth (§4.3 step 3);
            // the key self-expires within the TTL upper bound regardless.
            log.warn("Failed to evict Redis account status cache for '{}': {}", id, e.toString());
        }
    }
}
