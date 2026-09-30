package com.aismartstore.backend.groups;

import com.aismartstore.backend.documents.Document;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.domain.Specification;

/**
 * Row-level scope filter shared by every entrypoint that reads business data
 * (rbac-group-scope.md §2, Quyết định 3 SA). A document is visible to
 * {@code currentUserSub} iff they uploaded it, or it was shared to a group
 * they are a member of:
 *
 * <pre>
 * uploader_sub = :sub
 *   OR id IN (SELECT document_id FROM document_group_share
 *             WHERE group_id IN (:groupIdsOfCurrentUser))
 * </pre>
 *
 * <p>The user's own group ids are resolved with a single cheap, bounded
 * query (a user belongs to at most a few dozen/hundred groups per NFR-03) -
 * this is the "resolve group membership" step the fail-closed contract talks
 * about, and is deliberately eager (not deferred into the specification's
 * lazy {@code toPredicate}) so a DB failure surfaces synchronously as a
 * {@link ScopeResolutionException} right when the caller asks for the scope,
 * rather than a raw exception erupting later out of Hibernate mid-query.
 * Which <em>documents</em> those groups can see stays a real correlated SQL
 * subquery (never loaded into Java) - see the {@code IN (subquery)} below.
 *
 * <p>delete-restore-trash-01-xoa-mem-thung-rac.md §2.2: also excludes
 * soft-deleted documents ({@code deletedAt IS NOT NULL}) - a single change
 * here, applied to every read entrypoint that composes this scope, instead
 * of touching each of them individually. The trash module itself never uses
 * this scope (it needs the opposite - only soft-deleted rows) - see {@code
 * com.aismartstore.backend.trash.TrashService}.
 */
public final class BusinessDataScope {

    private BusinessDataScope() {
    }

    /**
     * Builds the {@link Specification} for {@link Document}. Throws {@link
     * ScopeResolutionException} (fail-closed, Story 4 AC-1) if {@code
     * currentUserSub} is missing or the group membership lookup fails - the
     * caller must not fall back to an unfiltered/empty specification.
     */
    public static Specification<Document> forDocuments(
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
            Predicate notDeleted = cb.isNull(root.get("deletedAt"));
            Predicate ownedByCurrentUser = cb.equal(root.get("uploaderSub"), currentUserSub);
            if (memberGroupIds.isEmpty()) {
                return cb.and(notDeleted, ownedByCurrentUser);
            }

            Subquery<UUID> sharedDocumentIds = query.subquery(UUID.class);
            Root<DocumentGroupShare> shareRoot = sharedDocumentIds.from(DocumentGroupShare.class);
            sharedDocumentIds
                    .select(shareRoot.get("documentId"))
                    .where(shareRoot.get("groupId").in(memberGroupIds));

            Predicate sharedToCurrentUsersGroup = root.get("id").in(sharedDocumentIds);
            return cb.and(notDeleted, cb.or(ownedByCurrentUser, sharedToCurrentUsersGroup));
        };
    }
}
