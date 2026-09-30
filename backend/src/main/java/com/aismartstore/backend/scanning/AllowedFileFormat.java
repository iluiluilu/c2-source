package com.aismartstore.backend.scanning;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One row of the format allowlist (data-ingestion.md §2.1, Story 5), seeded
 * from {@link MimeAllowlist}'s ALLOWED/MAGIC maps by V10. {@code mimeType} is
 * the primary key - one row per accepted MIME type.
 */
@Entity
@Table(name = "allowed_file_format")
public class AllowedFileFormat {

    @Id
    @Column(name = "mime_type")
    private String mimeType;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(nullable = false)
    private String[] extensions;

    @Column(nullable = false, length = 32)
    private String category;

    /** {@code [{"offset":0,"hex":"89504E47"}, ...]}; null = no signature check for this type. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "magic_bytes", columnDefinition = "jsonb")
    private List<Map<String, Object>> magicBytes;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "updated_by_sub")
    private String updatedBySub;

    @Column(name = "updated_by_username")
    private String updatedByUsername;

    public String getMimeType() {
        return mimeType;
    }

    public void setMimeType(String mimeType) {
        this.mimeType = mimeType;
    }

    public String[] getExtensions() {
        return extensions;
    }

    public void setExtensions(String[] extensions) {
        this.extensions = extensions;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public List<Map<String, Object>> getMagicBytes() {
        return magicBytes;
    }

    public void setMagicBytes(List<Map<String, Object>> magicBytes) {
        this.magicBytes = magicBytes;
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
