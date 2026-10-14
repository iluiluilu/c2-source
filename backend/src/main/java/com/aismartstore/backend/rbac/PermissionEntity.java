package com.aismartstore.backend.rbac;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * The closed catalogue of 15 permission codes (V12__init_groups.sql) -
 * read-only from this module's point of view; {@code role_permission} is the
 * only editable mapping (Story 2 AC-1: a {@code permissionCodes} value outside
 * this table is rejected with 400).
 */
@Entity
@Table(name = "permission")
public class PermissionEntity {

    @Id
    @Column(length = 128)
    private String code;

    @Column(nullable = false, length = 255)
    private String description;

    /**
     * SPEC-export-reporting §2.1 (AC-5): {@code false} = permission that must never be granted
     * to QUAN_TRI (e.g. {@code report.*} - R3 cannot see business data). Enforced in
     * {@code RbacService}; mapped here because {@code ddl-auto: validate} needs the column.
     */
    @Column(name = "admin_assignable", nullable = false)
    private boolean adminAssignable = true;

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public boolean isAdminAssignable() {
        return adminAssignable;
    }

    public void setAdminAssignable(boolean adminAssignable) {
        this.adminAssignable = adminAssignable;
    }
}
