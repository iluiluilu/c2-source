package com.aismartstore.backend.rbac;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * The 3 fixed SRS roles (V12__init_groups.sql) - read-only from this module's
 * point of view (rbac-permission-editing.md §3: no API creates/renames a
 * role, only {@code role_permission} is editable).
 */
@Entity
@Table(name = "role")
public class RoleEntity {

    @Id
    @Column(length = 32)
    private String code;

    @Column(name = "display_name", nullable = false, length = 128)
    private String displayName;

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }
}
