package com.aismartstore.backend.rbac;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

/**
 * One (role, permission) grant. Editable from this spec onward (V15
 * __role_permission_audit_columns.sql lifts the "seed-only" constraint from
 * V12) - {@link #updatedAt}/{@link #updatedBySub} record the last R3 edit at
 * row granularity, separate from the FR-09 audit_log chain (mục 4.2.f writes
 * there too, for the human-readable history).
 */
@Entity
@Table(name = "role_permission")
@IdClass(RolePermissionId.class)
public class RolePermission {

    @Id
    @Column(name = "role_code")
    private String roleCode;

    @Id
    @Column(name = "permission_code")
    private String permissionCode;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "updated_by_sub")
    private String updatedBySub;

    public RolePermission() {
    }

    public RolePermission(String roleCode, String permissionCode, OffsetDateTime updatedAt, String updatedBySub) {
        this.roleCode = roleCode;
        this.permissionCode = permissionCode;
        this.updatedAt = updatedAt;
        this.updatedBySub = updatedBySub;
    }

    public String getRoleCode() {
        return roleCode;
    }

    public void setRoleCode(String roleCode) {
        this.roleCode = roleCode;
    }

    public String getPermissionCode() {
        return permissionCode;
    }

    public void setPermissionCode(String permissionCode) {
        this.permissionCode = permissionCode;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(OffsetDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public String getUpdatedBySub() {
        return updatedBySub;
    }

    public void setUpdatedBySub(String updatedBySub) {
        this.updatedBySub = updatedBySub;
    }
}
