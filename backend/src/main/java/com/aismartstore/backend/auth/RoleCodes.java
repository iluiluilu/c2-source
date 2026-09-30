package com.aismartstore.backend.auth;

import java.util.Locale;

/**
 * rbac-permission-editing.md Task 4: the one place that converts between a
 * {@code role} table code (upper-snake, e.g. {@code CAN_BO} - matches
 * {@link Role}) and a bare Keycloak realm role name (kebab-case, e.g.
 * {@code can-bo} - matches {@link com.aismartstore.backend.accounts.AssignableRoles}).
 * Both directions are a mechanical case/separator transform (no lookup
 * table needed) since the two naming schemes are 1:1 by construction.
 *
 * <p>Used by {@code com.aismartstore.backend.rbac.RbacService} (role code
 * from the DB -&gt; Keycloak role name, to call {@code
 * KeycloakAdminService.listRoleUsers}) and by {@code
 * com.aismartstore.backend.config.SecurityConfig#realmRoleAuthorities}
 * (Keycloak role name from the JWT -&gt; role code, to key the
 * per-role-permission cache) - kept in one place instead of duplicating the
 * transform at each call site.
 */
public final class RoleCodes {

    /** {@code CAN_BO} -&gt; {@code can-bo}. */
    public static String toKeycloakRoleName(String roleCode) {
        return roleCode.toLowerCase(Locale.ROOT).replace('_', '-');
    }

    /** {@code can-bo} -&gt; {@code CAN_BO}. */
    public static String toRoleCode(String keycloakRoleName) {
        return keycloakRoleName.toUpperCase(Locale.ROOT).replace('-', '_');
    }

    private RoleCodes() {
    }
}
