package com.aismartstore.backend.tags;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/** Composite key for {@link DocumentTag} (document_id, tag_id). */
public class DocumentTagId implements Serializable {

    private UUID documentId;
    private UUID tagId;

    public DocumentTagId() {
    }

    public DocumentTagId(UUID documentId, UUID tagId) {
        this.documentId = documentId;
        this.tagId = tagId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof DocumentTagId that)) {
            return false;
        }
        return Objects.equals(documentId, that.documentId) && Objects.equals(tagId, that.tagId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(documentId, tagId);
    }
}
