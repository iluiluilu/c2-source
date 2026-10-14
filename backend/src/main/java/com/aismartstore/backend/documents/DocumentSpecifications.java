package com.aismartstore.backend.documents;

import com.aismartstore.backend.tags.DocumentTag;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.domain.Specification;

/** {@code folderId}/{@code tagId} filters for {@link DocumentsService#list}, as {@link Specification}s (rbac-group-scope.md §4.4). */
public final class DocumentSpecifications {

    private DocumentSpecifications() {
    }

    public static Specification<Document> hasFolderId(UUID folderId) {
        return (root, query, cb) -> cb.equal(root.get("folderId"), folderId);
    }

    /**
     * {@code status = 'clean'}: rejected/infected rows are FR-12 evidence only (no stored bytes) and must never
     * surface in search (feedbacks S-01) - {@link com.aismartstore.backend.groups.BusinessDataScope} filters
     * visibility, not status, because the warehouse list deliberately still shows those rows.
     */
    public static Specification<Document> isClean() {
        return (root, query, cb) -> cb.equal(root.get("status"), DocumentStatus.CLEAN);
    }

    /**
     * {@code GET /documents?q=} (group-detail "Thêm tài liệu" picker): case
     * and diacritics-sensitive {@code ILIKE '%q%'} on {@code originalName} -
     * same simple substring match as {@code UserDirectoryService}'s {@code
     * q}, not a fulltext search (that's {@code POST /search}).
     */
    public static Specification<Document> nameContains(String q) {
        return (root, query, cb) ->
                cb.like(cb.lower(root.get("originalName")), "%" + q.toLowerCase() + "%");
    }

    /** {@code EXISTS (SELECT 1 FROM document_tag WHERE document_id = d.id AND tag_id = :tagId)}, as a subquery. */
    public static Specification<Document> hasTagId(UUID tagId) {
        return (root, query, cb) -> {
            Subquery<UUID> taggedDocumentIds = query.subquery(UUID.class);
            Root<DocumentTag> tagRoot = taggedDocumentIds.from(DocumentTag.class);
            taggedDocumentIds.select(tagRoot.get("documentId")).where(cb.equal(tagRoot.get("tagId"), tagId));
            return root.get("id").in(taggedDocumentIds);
        };
    }

    /**
     * search-fulltext-02-tim-kiem-nang-cao.md §4 step 3 ({@code
     * MetadataFallbackSearchService}, AC-2 OR mode): {@code EXISTS (SELECT 1
     * FROM document_tag WHERE document_id = d.id AND tag_id IN (:tagIds))} -
     * the "OR between selected tags" counterpart of {@link #hasTagId} (AND is
     * simply {@link #hasTagId} called once per tag, combined with {@code
     * .and(...)}).
     */
    public static Specification<Document> hasAnyTagId(List<UUID> tagIds) {
        return (root, query, cb) -> {
            Subquery<UUID> taggedDocumentIds = query.subquery(UUID.class);
            Root<DocumentTag> tagRoot = taggedDocumentIds.from(DocumentTag.class);
            taggedDocumentIds.select(tagRoot.get("documentId")).where(tagRoot.get("tagId").in(tagIds));
            return root.get("id").in(taggedDocumentIds);
        };
    }

    /**
     * classification-notes-01-tags.md §3 ({@code GET
     * /documents/unclassified-doc-type}, US2 AC-5/AC-6): documents with no
     * {@code doc_type} link yet, scoped through {@link
     * com.aismartstore.backend.groups.BusinessDataScope} by the caller (this
     * predicate is combined with it, never used alone, so R1 only sees the
     * subset they're allowed to reclassify).
     */
    public static Specification<Document> missingDocType() {
        return (root, query, cb) -> {
            Subquery<UUID> docTypeDocumentIds = query.subquery(UUID.class);
            Root<DocumentTag> tagRoot = docTypeDocumentIds.from(DocumentTag.class);
            docTypeDocumentIds
                    .select(tagRoot.get("documentId"))
                    .where(cb.equal(tagRoot.get("tagKind"), "doc_type"));
            return cb.not(root.get("id").in(docTypeDocumentIds));
        };
    }

    /**
     * delete-restore-trash-01-xoa-mem-thung-rac.md §4.2, {@code GET
     * /trash?view=own}: items the current user personally moved to trash -
     * whether they uploaded them or not (Story 2 AC-1).
     */
    public static Specification<Document> deletedByUser(String sub) {
        return (root, query, cb) ->
                cb.and(cb.isNotNull(root.get("deletedAt")), cb.equal(root.get("deletedBySub"), sub));
    }

    /**
     * §4.2, {@code GET /trash?view=uploaded}: items the current user
     * uploaded but somebody else trashed (Story 2 AC-2 - "tài liệu tôi nạp bị
     * người khác xóa"). Deliberately excludes rows where {@code
     * deletedBySub == sub} - those already show up under {@code
     * deletedByUser} (view=own) and would otherwise be double-counted
     * across the two tabs.
     */
    public static Specification<Document> uploadedButDeletedByAnotherUser(String sub) {
        return (root, query, cb) -> cb.and(
                cb.isNotNull(root.get("deletedAt")),
                cb.equal(root.get("uploaderSub"), sub),
                cb.notEqual(root.get("deletedBySub"), sub));
    }
}
