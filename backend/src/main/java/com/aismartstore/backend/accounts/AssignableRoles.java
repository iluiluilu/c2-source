package com.aismartstore.backend.accounts;

import java.util.List;

/**
 * Security boundary: {@link AccountsService} may only grant/revoke these
 * three roles. Bare Keycloak role names (no {@code realm:} prefix). Mirrors
 * the NestJS backend's accounts/assignable-roles.constant.ts.
 */
public final class AssignableRoles {
    public static final List<String> ALL = List.of("can-bo", "lanh-dao", "quan-tri");

    private AssignableRoles() {
    }
}
