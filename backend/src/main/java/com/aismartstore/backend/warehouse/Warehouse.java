package com.aismartstore.backend.warehouse;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

/**
 * Singleton row (id always 1, DB CHECK-enforced) holding the warehouse's
 * configured maximum capacity (Story 5 AC-4, spec §2.4). Seeded once by
 * {@code V9__warehouse_structure_v2.sql}; the application only ever reads
 * and updates the existing row, never inserts.
 */
@Entity
@Table(name = "warehouse")
public class Warehouse {

    @Id
    private short id;

    @Column(name = "max_capacity_bytes", nullable = false)
    private long maxCapacityBytes;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "updated_by_sub")
    private String updatedBySub;

    @Column(name = "updated_by_username")
    private String updatedByUsername;

    public short getId() {
        return id;
    }

    public long getMaxCapacityBytes() {
        return maxCapacityBytes;
    }

    public void setMaxCapacityBytes(long maxCapacityBytes) {
        this.maxCapacityBytes = maxCapacityBytes;
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

    public String getUpdatedByUsername() {
        return updatedByUsername;
    }

    public void setUpdatedByUsername(String updatedByUsername) {
        this.updatedByUsername = updatedByUsername;
    }
}
