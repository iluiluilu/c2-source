package com.aismartstore.backend.rbac;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;

/**
 * rbac-permission-editing.md §2: role/permission are tiny reference tables
 * (3 x 15 rows), so a plain {@link JpaRepository} with a couple of derived
 * queries is used instead of hand-rolled JdbcTemplate SQL - consistent with
 * every other small mapping table in this codebase (e.g. {@code
 * DocumentGroupShareRepository}).
 */
public interface RolePermissionRepository extends JpaRepository<RolePermission, RolePermissionId> {

    List<RolePermission> findByRoleCode(String roleCode);

    @Modifying
    int deleteByRoleCodeAndPermissionCode(String roleCode, String permissionCode);
}
