package com.aismartstore.backend.rbac;

import com.aismartstore.backend.accounts.KeycloakAdminService;
import com.aismartstore.backend.accounts.KeycloakUnavailableError;
import com.aismartstore.backend.audit.AuditRequestContext;
import com.aismartstore.backend.audit.AuditService;
import com.aismartstore.backend.auth.KeycloakUser;
import com.aismartstore.backend.auth.RoleCodes;
import com.aismartstore.backend.common.ApiException;
import com.aismartstore.backend.common.BadRequestException;
import com.aismartstore.backend.common.ServiceUnavailableException;
import com.github.benmanes.caffeine.cache.Cache;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * rbac-permission-editing.md §4: view/edit the role x permission matrix
 * (Story 1/2) plus the lockout guard (Story 3, Quyết định 3 SA). Every
 * mutation is audited (Story 2 AC-3) and evicts the per-role permission cache
 * that {@code SecurityConfig#realmRoleAuthorities} reads (Quyết định 2 SA,
 * §4.3) so enforcement catches up without waiting for the 60s TTL.
 */
@Service
public class RbacService {

    /** The 3 fixed SRS roles - no API here creates/renames a role (mục 3). */
    private static final Set<String> ROLE_CODES = Set.of("CAN_BO", "LANH_DAO", "QUAN_TRI");

    /** Quyết định 3 (SA): QUAN_TRI must always keep both, or nobody can administer the system back in. */
    private static final String PERM_ACCOUNT_MANAGE = "account.manage";

    private static final String PERM_SYSTEM_CONFIG_MANAGE = "system_config.manage";

    /** Same upper bound as {@code AccountsService.ADMIN_ROLE_LOOKUP_MAX} - well above any realistic headcount. */
    private static final int ROLE_LOOKUP_MAX = 1000;

    private final RoleRepository roleRepository;
    private final PermissionRepository permissionRepository;
    private final RolePermissionRepository rolePermissionRepository;
    private final KeycloakAdminService kc;
    private final AuditService auditService;
    private final AuditRequestContext auditRequestContext;
    private final Cache<String, Set<String>> rolePermissionCache;

    public RbacService(
            RoleRepository roleRepository,
            PermissionRepository permissionRepository,
            RolePermissionRepository rolePermissionRepository,
            KeycloakAdminService kc,
            AuditService auditService,
            AuditRequestContext auditRequestContext,
            Cache<String, Set<String>> rolePermissionCache) {
        this.roleRepository = roleRepository;
        this.permissionRepository = permissionRepository;
        this.rolePermissionRepository = rolePermissionRepository;
        this.kc = kc;
        this.auditService = auditService;
        this.auditRequestContext = auditRequestContext;
        this.rolePermissionCache = rolePermissionCache;
    }

    public record RoleView(String code, String displayName, List<String> permissionCodes) {
    }

    public record PermissionView(String code, String description) {
    }

    public record PreviewImpactResult(
            long affectedAccountCount, List<String> permissionsAdded, List<String> permissionsRemoved) {
    }

    public record UpdateResult(String roleCode, List<String> permissionCodes, long affectedAccountCount) {
    }

    /** Story 1: the full role x permission matrix. */
    public List<RoleView> listRoles() {
        Map<String, List<String>> permissionsByRole = new HashMap<>();
        for (RolePermission rp : rolePermissionRepository.findAll()) {
            permissionsByRole
                    .computeIfAbsent(rp.getRoleCode(), k -> new ArrayList<>())
                    .add(rp.getPermissionCode());
        }
        return roleRepository.findAllByOrderByCodeAsc().stream()
                .map(role -> new RoleView(
                        role.getCode(),
                        role.getDisplayName(),
                        permissionsByRole.getOrDefault(role.getCode(), List.of()).stream().sorted().toList()))
                .toList();
    }

    /** Story 1: the closed permission catalogue (not filtered by role). */
    public List<PermissionView> listPermissions() {
        return permissionRepository.findAllByOrderByCodeAsc().stream()
                .map(p -> new PermissionView(p.getCode(), p.getDescription()))
                .toList();
    }

    /** Story 2 AC-5: diff a desired permission set against the current one, before saving. */
    public PreviewImpactResult previewImpact(String roleCode, List<String> desiredPermissionCodes) {
        validateRoleCode(roleCode);
        Set<String> desired = validatePermissionCodes(desiredPermissionCodes);
        Set<String> current = currentPermissionCodes(roleCode);

        List<String> added = desired.stream().filter(p -> !current.contains(p)).sorted().toList();
        List<String> removed = current.stream().filter(p -> !desired.contains(p)).sorted().toList();

        return new PreviewImpactResult(countAccountsWithRole(roleCode), added, removed);
    }

    /**
     * Story 2/3: full-replace {@code role_permission} for {@code roleCode}.
     * mục 4.2: validate -&gt; diff -&gt; guard lockout -&gt; write (one
     * transaction) -&gt; evict cache -&gt; audit -&gt; recount. A no-op save
     * (identical set) still returns 200 but skips both the audit write and
     * the cache eviction (mục 5, "case biên").
     *
     * <p>Concurrency: last-write-wins on the full {@code permissionCodes}
     * set sent by the caller - two R3 editing the same role at once is not
     * optimistically locked, by design (mục 5), given this system's scale.
     */
    @Transactional
    public UpdateResult updateRolePermissions(KeycloakUser user, String roleCode, List<String> desiredPermissionCodes) {
        validateRoleCode(roleCode);
        Set<String> desired = validatePermissionCodes(desiredPermissionCodes);
        Set<String> current = currentPermissionCodes(roleCode);

        guardLockout(roleCode, desired);

        List<String> added = desired.stream().filter(p -> !current.contains(p)).sorted().toList();
        List<String> removed = current.stream().filter(p -> !desired.contains(p)).sorted().toList();

        if (!added.isEmpty() || !removed.isEmpty()) {
            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
            for (String permissionCode : removed) {
                rolePermissionRepository.deleteByRoleCodeAndPermissionCode(roleCode, permissionCode);
            }
            for (String permissionCode : added) {
                rolePermissionRepository.save(new RolePermission(roleCode, permissionCode, now, user.sub()));
            }

            // Quyết định 2 (SA), mục 4.3 step 4: evict now, don't wait for the 60s TTL.
            rolePermissionCache.invalidate(roleCode);

            auditService.recordAsync(new AuditService.AuditEntry(
                    user.preferredUsername(), user.sub(), "rbac.role_permission_update", "role", roleCode, "success",
                    auditRequestContext.method(), auditRequestContext.path(), 200, auditRequestContext.ip(),
                    Map.of("permissionsAdded", added, "permissionsRemoved", removed)));
        }

        return new UpdateResult(roleCode, desired.stream().sorted().toList(), countAccountsWithRole(roleCode));
    }

    /** mục 4.4: hard-block turning QUAN_TRI into a role that can no longer administer the system. */
    private void guardLockout(String roleCode, Set<String> newPermissionCodes) {
        if (!"QUAN_TRI".equals(roleCode)) {
            return;
        }
        if (newPermissionCodes.contains(PERM_ACCOUNT_MANAGE) && newPermissionCodes.contains(PERM_SYSTEM_CONFIG_MANAGE)) {
            return;
        }
        throw lockoutConflict();
    }

    private static ApiException lockoutConflict() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", "RBAC_LOCKOUT");
        body.put(
                "message",
                "Không thể gỡ đồng thời quyền 'account.manage' và 'system_config.manage' khỏi QUAN_TRI"
                        + " - sẽ không còn ai quản trị được hệ thống");
        return new ApiException(HttpStatus.CONFLICT, body, "RBAC lockout guard");
    }

    private void validateRoleCode(String roleCode) {
        if (!ROLE_CODES.contains(roleCode)) {
            throw new BadRequestException("Unknown role: " + roleCode);
        }
    }

    /** Story 2 AC-1: every requested code must exist in the closed {@code permission} catalogue. */
    private Set<String> validatePermissionCodes(List<String> permissionCodes) {
        if (permissionCodes == null) {
            return Set.of();
        }
        Set<String> known =
                permissionRepository.findAll().stream().map(PermissionEntity::getCode).collect(Collectors.toSet());
        Set<String> requested = new LinkedHashSet<>(permissionCodes);
        for (String code : requested) {
            if (!known.contains(code)) {
                throw new BadRequestException("Unknown permission: " + code);
            }
        }
        return requested;
    }

    private Set<String> currentPermissionCodes(String roleCode) {
        return rolePermissionRepository.findByRoleCode(roleCode).stream()
                .map(RolePermission::getPermissionCode)
                .collect(Collectors.toSet());
    }

    /** mục 4.2 step 1/g: same counting approach as {@code AccountsService}'s admin-headcount guard. */
    private long countAccountsWithRole(String roleCode) {
        try {
            return kc.listRoleUsers(RoleCodes.toKeycloakRoleName(roleCode), ROLE_LOOKUP_MAX).size();
        } catch (KeycloakUnavailableError e) {
            throw new ServiceUnavailableException("Không thể kết nối máy chủ định danh (Keycloak)");
        }
    }
}
