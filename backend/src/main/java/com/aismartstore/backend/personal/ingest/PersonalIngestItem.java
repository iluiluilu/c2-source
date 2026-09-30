package com.aismartstore.backend.personal.ingest;

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
 * personal-to-warehouse.md §2: per-file result of one personal-to-warehouse
 * run. Never stores personal folder names/paths - only the file name.
 */
@Entity
@Table(name = "personal_ingest_item")
public class PersonalIngestItem {

    public static final String PENDING = "PENDING";
    public static final String INGESTED = "INGESTED";
    public static final String SKIPPED = "SKIPPED";
    public static final String FAILED = "FAILED";

    @Id
    @GeneratedValue
    @UuidGenerator
    private UUID id;

    @Column(name = "batch_id", nullable = false)
    private UUID batchId;

    @Column(name = "personal_file_id", nullable = false)
    private UUID personalFileId;

    @Column(name = "original_name", nullable = false, length = 512)
    private String originalName;

    @Column(nullable = false)
    private long size;

    @Column(name = "relative_path", nullable = false, length = 1024)
    private String relativePath = "";

    @Column(nullable = false, length = 32)
    private String status = PENDING;

    @Column(length = 48)
    private String code;

    @Column(name = "document_id")
    private UUID documentId;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "duplicate_of")
    private UUID[] duplicateOf;

    @Column(name = "processed_at")
    private OffsetDateTime processedAt;

    public UUID getId() {
        return id;
    }

    public UUID getBatchId() {
        return batchId;
    }

    public void setBatchId(UUID batchId) {
        this.batchId = batchId;
    }

    public UUID getPersonalFileId() {
        return personalFileId;
    }

    public void setPersonalFileId(UUID personalFileId) {
        this.personalFileId = personalFileId;
    }

    public String getOriginalName() {
        return originalName;
    }

    public void setOriginalName(String originalName) {
        this.originalName = originalName;
    }

    public long getSize() {
        return size;
    }

    public void setSize(long size) {
        this.size = size;
    }

    public String getRelativePath() {
        return relativePath;
    }

    public void setRelativePath(String relativePath) {
        this.relativePath = relativePath;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public UUID getDocumentId() {
        return documentId;
    }

    public void setDocumentId(UUID documentId) {
        this.documentId = documentId;
    }

    public UUID[] getDuplicateOf() {
        return duplicateOf;
    }

    public void setDuplicateOf(UUID[] duplicateOf) {
        this.duplicateOf = duplicateOf;
    }

    public OffsetDateTime getProcessedAt() {
        return processedAt;
    }

    public void setProcessedAt(OffsetDateTime processedAt) {
        this.processedAt = processedAt;
    }
}
