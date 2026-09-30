package com.aismartstore.backend.groups;

import com.aismartstore.backend.audit.AuditLog;
import com.aismartstore.backend.documents.Document;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.domain.Specification;

/**
 * audit-logging.md §4.4 (Story 3): R2's {@code GET /audit} scope. Reuses the
 * exact visibility rule {@link BusinessDataScope#forDocuments} already
 * applies to business data, rather than defining a second notion of "scope"
 * for the audit module (Story 3 AC-2).
 *
 * <p><b>v1 limitation, confirmed with the user 2026-09-01 (see
 * docs/03-dev/specs/audit-logging.md §4.4/§7):</b> only {@code resource_type
 * = 'document'} is covered - there is no {@code Specification} equivalent to
 * {@code BusinessDataScope.forDocuments} yet for {@code folder}/{@code
 * group}, so R2 sees no other {@code resource_type} at all in this version
 * (fail-closed per Story 3 AC-3, not an oversight).
 *
 * <p>{@code audit_log.resource_id} is a plain {@code VARCHAR} (not a real FK
 * to {@code document.id}), so the "which documents is this user allowed to
 * see" check is expressed as a real correlated subquery over {@link
 * Document} - never a Java-side list of ids - with the id cast to text for
 * the comparison against {@code resource_id}.
 */
public final class AuditScopeFilter {

    private AuditScopeFilter() {
    }

    /** Throws {@link ScopeResolutionException} (fail-closed, Story 3 AC-4) if group membership cannot be resolved. */
    public static Specification<AuditLog> forCurrentUser(
            String currentUserSub, GroupMemberRepository groupMemberRepository) {
        if (currentUserSub == null || currentUserSub.isBlank()) {
            throw new ScopeResolutionException("Current user token is missing a subject claim");
        }

        List<UUID> memberGroupIds;
        try {
            memberGroupIds = groupMemberRepository.findGroupIdsByUserSub(currentUserSub);
        } catch (RuntimeException e) {
            throw new ScopeResolutionException("Failed to resolve the current user's group membership", e);
        }

        return (root, query, cb) -> {
            Subquery<String> visibleDocumentIds = query.subquery(String.class);
            Root<Document> documentRoot = visibleDocumentIds.from(Document.class);

            Predicate ownedByCurrentUser = cb.equal(documentRoot.get("uploaderSub"), currentUserSub);
            Predicate visible = ownedByCurrentUser;
            if (!memberGroupIds.isEmpty()) {
                Subquery<UUID> sharedDocumentIds = visibleDocumentIds.subquery(UUID.class);
                Root<DocumentGroupShare> shareRoot = sharedDocumentIds.from(DocumentGroupShare.class);
                sharedDocumentIds
                        .select(shareRoot.get("documentId"))
                        .where(shareRoot.get("groupId").in(memberGroupIds));
                visible = cb.or(ownedByCurrentUser, documentRoot.get("id").in(sharedDocumentIds));
            }

            visibleDocumentIds.select(documentRoot.get("id").as(String.class)).where(visible);

            Predicate isDocumentEvent = cb.equal(root.get("resourceType"), "document");
            Predicate resourceIdIsVisible = root.get("resourceId").in(visibleDocumentIds);
            return cb.and(isDocumentEvent, resourceIdIsVisible);
        };
    }
}
