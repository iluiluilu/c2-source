package com.aismartstore.backend.testsupport;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/**
 * rbac-permission-editing.md Task 6/9: every {@code @PreAuthorize} switched
 * from {@code hasRole(...)}/{@code hasAnyRole(...)} to {@code
 * hasAuthority('PERM_<code>')}, resolved at request time from {@code
 * role_permission} (see {@code SecurityConfig#realmRoleAuthorities}) - not
 * from the bare {@code ROLE_<code>} authority anymore. MockMvc's {@code
 * jwt().authorities(...)} postprocessor bypasses that resolution entirely
 * (it sets the token's authorities directly, never calling the app's
 * converter), so every existing IT test that only granted {@code
 * ROLE_<code>} would now see 403 on every endpoint. This mirrors the V12
 * seed (the "zero R3 edits yet" baseline also asserted by {@code
 * RolePermissionSeedTest}), plus V22's {@code audit.read_scoped} addition for
 * {@code LANH_DAO} (audit-logging.md), so those IT tests keep exercising
 * "does this role reach this endpoint" without coupling each one to the live
 * DB/cache.
 */
public final class RolePermissionFixtures {

    public static final Set<String> CAN_BO = Set.of(
            "document.read", "document.upload", "note.write", "tag.manage", "folder.read",
            "account.self_service", "personal.manage", "group.manage", "document.share",
            // digitization-review-01-ocr.md V28 mục 2.
            "digitization_review.manage",
            // delete-restore-trash-01-xoa-mem-thung-rac.md V29.
            "trash.manage");

    public static final Set<String> LANH_DAO = Set.of(
            "document.read", "folder.read", "account.self_service", "personal.manage", "group.manage",
            "document.share", "audit.read_scoped");

    public static final Set<String> QUAN_TRI = Set.of(
            "folder.read", "folder.manage", "scan_allowlist.manage", "system_config.manage", "account.read",
            "account.manage", "account.self_service", "audit.read", "personal.manage",
            // digitization-review-01-ocr.md V28 mục 2.
            "document.transfer_ownership",
            // delete-restore-trash-01-xoa-mem-thung-rac.md V29, Story 3.
            "trash.stats.read");

    /** {@code ROLE_<springRole>} plus every {@code PERM_<code>} the V12 seed grants that role. */
    public static List<GrantedAuthority> authoritiesFor(String springRole) {
        Set<String> permissions = switch (springRole) {
            case "CAN_BO" -> CAN_BO;
            case "LANH_DAO" -> LANH_DAO;
            case "QUAN_TRI" -> QUAN_TRI;
            default -> Set.of();
        };
        List<GrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority("ROLE_" + springRole));
        for (String permission : permissions) {
            authorities.add(new SimpleGrantedAuthority("PERM_" + permission));
        }
        return authorities;
    }

    private RolePermissionFixtures() {
    }
}
