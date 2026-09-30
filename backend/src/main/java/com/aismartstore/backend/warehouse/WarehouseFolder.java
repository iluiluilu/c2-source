package com.aismartstore.backend.warehouse;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

/**
 * Admin-managed folder tree for warehouse (case-managed) documents. Mirrors
 * the NestJS backend's warehouse/warehouse-folder.entity.ts, extended per
 * docs/03-dev/specs/warehouse-structure.md §2 with a materialized path
 * ({@link #pathIds}, by ANCESTOR ID - not name, so a rename never touches
 * it), {@link #depth} (max 5, enforced by a DB CHECK) and audit columns.
 */
@Entity
@Table(name = "warehouse_folder")
public class WarehouseFolder {

    @Id
    @GeneratedValue
    @UuidGenerator
    private UUID id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "parent_id")
    private UUID parentId;

    /** Ancestor ids, root first, NOT including this folder's own id. Empty for a root folder. */
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "path_ids", nullable = false)
    private UUID[] pathIds = new UUID[0];

    /** 1 for a root folder, up to 5 (DB CHECK CK_warehouse_folder_depth). */
    @Column(nullable = false)
    private short depth = 1;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "created_by_sub")
    private String createdBySub;

    @Column(name = "created_by_username")
    private String createdByUsername;

    @Column(name = "updated_by_sub")
    private String updatedBySub;

    @Column(name = "updated_by_username")
    private String updatedByUsername;

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public UUID getParentId() {
        return parentId;
    }

    public void setParentId(UUID parentId) {
        this.parentId = parentId;
    }

    public UUID[] getPathIds() {
        return pathIds;
    }

    public void setPathIds(UUID[] pathIds) {
        this.pathIds = pathIds;
    }

    public short getDepth() {
        return depth;
    }

    public void setDepth(short depth) {
        this.depth = depth;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(OffsetDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public String getCreatedBySub() {
        return createdBySub;
    }

    public void setCreatedBySub(String createdBySub) {
        this.createdBySub = createdBySub;
    }

    public String getCreatedByUsername() {
        return createdByUsername;
    }

    public void setCreatedByUsername(String createdByUsername) {
        this.createdByUsername = createdByUsername;
    }

    public String getUpdatedBySub() {
        return updatedBySub;
    }

    public void setUpdatedBySub(String updatedBySub) {
        this.updatedBySub = updatedBySub;
    }

    public String getUpdatedByUsername() {
        return updatedByUsername;
    }

    public void setUpdatedByUsername(String updatedByUsername) {
        this.updatedByUsername = updatedByUsername;
    }
}
