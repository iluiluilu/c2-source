package com.aismartstore.backend.groups;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A document shared to a group (rbac-group-scope.md §2, Story 5 AC-2): grants
 * every {@link GroupMember} of {@code groupId} read access to {@code
 * documentId} via {@link BusinessDataScope}. Deliberately holds no copy of
 * the document's content/path - only the grant itself.
 */
@Entity
@Table(name = "document_group_share")
@IdClass(DocumentGroupShareId.class)
public class DocumentGroupShare {

    @Id
    @Column(name = "document_id")
    private UUID documentId;

    @Id
    @Column(name = "group_id")
    private UUID groupId;

    @Column(name = "shared_by_sub", nullable = false)
    private String sharedBySub;

    @Column(name = "shared_at", nullable = false)
    private OffsetDateTime sharedAt;

    public UUID getDocumentId() {
        return documentId;
    }

    public void setDocumentId(UUID documentId) {
        this.documentId = documentId;
    }

    public UUID getGroupId() {
        return groupId;
    }

    public void setGroupId(UUID groupId) {
        this.groupId = groupId;
    }

    public String getSharedBySub() {
        return sharedBySub;
    }

    public void setSharedBySub(String sharedBySub) {
        this.sharedBySub = sharedBySub;
    }

    public OffsetDateTime getSharedAt() {
        return sharedAt;
    }

    public void setSharedAt(OffsetDateTime sharedAt) {
        this.sharedAt = sharedAt;
    }
}
