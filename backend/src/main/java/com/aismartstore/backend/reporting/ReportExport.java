package com.aismartstore.backend.reporting;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

/** One export job (AC-21..33). Lifecycle: PENDING -> RUNNING -> DONE|FAILED; DONE -> EXPIRED by cleanup. */
@Entity
@Table(name = "report_export")
public class ReportExport {

    public static final String PENDING = "PENDING";
    public static final String RUNNING = "RUNNING";
    public static final String DONE = "DONE";
    public static final String FAILED = "FAILED";
    public static final String EXPIRED = "EXPIRED";

    @Id
    @GeneratedValue
    @UuidGenerator
    private UUID id;

    @Column(name = "template_code", nullable = false, length = 48)
    private String templateCode;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "params_json", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> paramsJson;

    @Column(nullable = false, length = 8)
    private String format;

    @Column(nullable = false, length = 10)
    private String status = PENDING;

    @Column(name = "requested_by_sub", nullable = false)
    private String requestedBySub;

    @Column(name = "requested_by_username", nullable = false)
    private String requestedByUsername;

    @Column(name = "retry_of")
    private UUID retryOf;

    @Column(name = "object_key", length = 512)
    private String objectKey;

    @Column(name = "byte_size")
    private Long byteSize;

    @Column(name = "row_count")
    private Integer rowCount;

    @Column(name = "error_message", length = 500)
    private String errorMessage;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "lease_expires_at")
    private OffsetDateTime leaseExpiresAt;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "started_at")
    private OffsetDateTime startedAt;

    @Column(name = "finished_at")
    private OffsetDateTime finishedAt;

    @Column(name = "expires_at")
    private OffsetDateTime expiresAt;

    public UUID getId() {
        return id;
    }

    public String getTemplateCode() {
        return templateCode;
    }

    public void setTemplateCode(String templateCode) {
        this.templateCode = templateCode;
    }

    public Map<String, Object> getParamsJson() {
        return paramsJson;
    }

    public void setParamsJson(Map<String, Object> paramsJson) {
        this.paramsJson = paramsJson;
    }

    public String getFormat() {
        return format;
    }

    public void setFormat(String format) {
        this.format = format;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getRequestedBySub() {
        return requestedBySub;
    }

    public void setRequestedBySub(String requestedBySub) {
        this.requestedBySub = requestedBySub;
    }

    public String getRequestedByUsername() {
        return requestedByUsername;
    }

    public void setRequestedByUsername(String requestedByUsername) {
        this.requestedByUsername = requestedByUsername;
    }

    public UUID getRetryOf() {
        return retryOf;
    }

    public void setRetryOf(UUID retryOf) {
        this.retryOf = retryOf;
    }

    public String getObjectKey() {
        return objectKey;
    }

    public void setObjectKey(String objectKey) {
        this.objectKey = objectKey;
    }

    public Long getByteSize() {
        return byteSize;
    }

    public Integer getRowCount() {
        return rowCount;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public OffsetDateTime getLeaseExpiresAt() {
        return leaseExpiresAt;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public OffsetDateTime getStartedAt() {
        return startedAt;
    }

    public OffsetDateTime getFinishedAt() {
        return finishedAt;
    }

    public OffsetDateTime getExpiresAt() {
        return expiresAt;
    }
}
