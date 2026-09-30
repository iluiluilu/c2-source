package com.aismartstore.backend.accounts;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

/**
 * Local snapshot of an account's lifecycle state and storage quota (FR-04
 * lock/disable, FR-14 quota), keyed by the Keycloak user id ({@code sub}).
 * Keycloak stays the source of truth for identity (username/email/roles);
 * this table only adds what Keycloak has no concept of. Shared with
 * personal-storage-quota.md, which reads/writes {@code storageQuotaBytes}
 * but does not own this table's schema
 * (docs/03-dev/specs/account-lifecycle.md §2.1).
 */
@Entity
@Table(name = "account")
public class Account {

    @Id
    @Column(name = "keycloak_user_id")
    private String keycloakUserId;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(name = "status_reason", length = 500)
    private String statusReason;

    @Column(name = "storage_quota_bytes", nullable = false)
    private long storageQuotaBytes;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    public String getKeycloakUserId() {
        return keycloakUserId;
    }

    public void setKeycloakUserId(String keycloakUserId) {
        this.keycloakUserId = keycloakUserId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getStatusReason() {
        return statusReason;
    }

    public void setStatusReason(String statusReason) {
        this.statusReason = statusReason;
    }

    public long getStorageQuotaBytes() {
        return storageQuotaBytes;
    }

    public void setStorageQuotaBytes(long storageQuotaBytes) {
        this.storageQuotaBytes = storageQuotaBytes;
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
}
