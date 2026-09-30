package com.aismartstore.backend.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.Map;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Append-only, hash-chained activity log (FR-09 / NFR-09). Mirrors the
 * NestJS backend's {@code AuditLog} entity (audit/audit-log.entity.ts).
 *
 * {@code hash = SHA256(stableJSON(semantic fields) || prevHash)}, so altering
 * any row - or deleting/reordering rows - breaks every later row's hash and
 * is detectable via {@link AuditService#verifyChain()}. A DB trigger (see the
 * V1__init_audit_log.sql migration) additionally rejects UPDATE/DELETE at
 * the row level.
 */
@Entity
@Table(name = "audit_log")
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    /** Username, or 'system' / 'anonymous'. */
    @Column(nullable = false, length = 255)
    private String actor;

    /** Keycloak subject id, when the actor is an authenticated user. */
    @Column(name = "actor_sub", length = 255)
    private String actorSub;

    /** Semantic action, e.g. 'http.POST', 'document.create'. */
    @Column(nullable = false, length = 128)
    private String action;

    @Column(name = "resource_type", length = 128)
    private String resourceType;

    @Column(name = "resource_id", length = 255)
    private String resourceId;

    @Column(nullable = false, length = 16)
    private String outcome;

    @Column(length = 8)
    private String method;

    @Column(columnDefinition = "text")
    private String path;

    @Column(name = "status_code")
    private Integer statusCode;

    @Column(length = 64)
    private String ip;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> metadata;

    /** Hash of the preceding row; null only for the genesis row. */
    @Column(name = "prev_hash", length = 64)
    private String prevHash;

    @Column(length = 64)
    private String hash;

    /** Set once, by the sealer job (or by migration V21 for pre-existing rows), when hash/prevHash are filled in. */
    @Column(name = "sealed_at")
    private OffsetDateTime sealedAt;

    public Long getId() {
        return id;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public String getActor() {
        return actor;
    }

    public void setActor(String actor) {
        this.actor = actor;
    }

    public String getActorSub() {
        return actorSub;
    }

    public void setActorSub(String actorSub) {
        this.actorSub = actorSub;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getResourceType() {
        return resourceType;
    }

    public void setResourceType(String resourceType) {
        this.resourceType = resourceType;
    }

    public String getResourceId() {
        return resourceId;
    }

    public void setResourceId(String resourceId) {
        this.resourceId = resourceId;
    }

    public String getOutcome() {
        return outcome;
    }

    public void setOutcome(String outcome) {
        this.outcome = outcome;
    }

    public String getMethod() {
        return method;
    }

    public void setMethod(String method) {
        this.method = method;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public Integer getStatusCode() {
        return statusCode;
    }

    public void setStatusCode(Integer statusCode) {
        this.statusCode = statusCode;
    }

    public String getIp() {
        return ip;
    }

    public void setIp(String ip) {
        this.ip = ip;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata;
    }

    public String getPrevHash() {
        return prevHash;
    }

    public void setPrevHash(String prevHash) {
        this.prevHash = prevHash;
    }

    public String getHash() {
        return hash;
    }

    public void setHash(String hash) {
        this.hash = hash;
    }

    public OffsetDateTime getSealedAt() {
        return sealedAt;
    }

    public void setSealedAt(OffsetDateTime sealedAt) {
        this.sealedAt = sealedAt;
    }
}
