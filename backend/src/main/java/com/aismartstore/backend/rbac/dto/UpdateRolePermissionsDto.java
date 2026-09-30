package com.aismartstore.backend.rbac.dto;

import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * Full-replace, same convention as {@code UpdateAccountDto.roles()} - there
 * is no "add 1 permission"/"remove 1 permission" endpoint, to avoid two APIs
 * doing the same thing (rbac-permission-editing.md §2). An empty list is
 * valid (revokes every permission from the role, subject to the lockout
 * guard for QUAN_TRI); {@code null} is rejected by validation.
 */
public record UpdateRolePermissionsDto(@NotNull List<String> permissionCodes) {
}
