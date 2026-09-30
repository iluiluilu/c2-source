package com.aismartstore.backend.groups;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/** Composite key for {@link DocumentGroupShare} (document_id, group_id). */
public class DocumentGroupShareId implements Serializable {

    private UUID documentId;
    private UUID groupId;

    public DocumentGroupShareId() {
    }

    public DocumentGroupShareId(UUID documentId, UUID groupId) {
        this.documentId = documentId;
        this.groupId = groupId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof DocumentGroupShareId that)) {
            return false;
        }
        return Objects.equals(documentId, that.documentId) && Objects.equals(groupId, that.groupId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(documentId, groupId);
    }
}
