package com.aismartstore.backend.search;

import com.aismartstore.backend.digitization.results.DigitizationRun;
import com.aismartstore.backend.documents.Document;
import com.aismartstore.backend.groups.DocumentGroupShare;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.domain.Specification;

/**
 * search-fulltext-02-tim-kiem-nang-cao.md §4 step 3 - {@link
 * MetadataFallbackSearchService}'s {@code Specification<Document>} builders
 * for the filter groups that have no Postgres-native equivalent yet
 * ({@code doc_type}/{@code topic_tags} reuse {@code
 * com.aismartstore.backend.documents.DocumentSpecifications} instead, since
 * those are plain {@code document_tag} lookups already implemented there).
 */
final class MetadataFilterSpecifications {

    private MetadataFilterSpecifications() {
    }

    /** Always false - used when a requested value can never match anything (e.g. an unknown topic tag label, AND mode). */
    static Specification<Document> none() {
        return (root, query, cb) -> cb.disjunction();
    }

    /**
     * §4 step 1.2 "uploaded_at": on {@link Document#getCreatedAt()} - the
     * only ingestion timestamp {@code Document} has (AC-3's "Ngày nạp kho").
     * {@code to} is inclusive of the whole day.
     */
    static Specification<Document> uploadedBetween(LocalDate from, LocalDate to) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (from != null) {
                predicates.add(cb.greaterThanOrEqualTo(
                        root.get("createdAt"), from.atStartOfDay(ZoneOffset.UTC).toOffsetDateTime()));
            }
            if (to != null) {
                predicates.add(cb.lessThanOrEqualTo(
                        root.get("createdAt"),
                        to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toOffsetDateTime().minusNanos(1)));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    /**
     * §4 step 1.2 "group_ids": narrows further within {@code
     * BusinessDataScope} - only documents shared to one of {@code groupIds}
     * (the {@code effectiveGroupIds}, already {@code ∩ ctx.groupIds()}), same
     * semantics as the OpenSearch-side {@code scope_group_ids} clause (AC-4).
     * Deliberately does NOT also match "owned by the current user" - the
     * OpenSearch equivalent doesn't either (it filters {@code
     * scope_group_ids}, not {@code owner_user_id}).
     */
    static Specification<Document> sharedToAnyGroup(List<UUID> groupIds) {
        return (root, query, cb) -> {
            Subquery<UUID> sharedDocumentIds = query.subquery(UUID.class);
            Root<DocumentGroupShare> shareRoot = sharedDocumentIds.from(DocumentGroupShare.class);
            sharedDocumentIds.select(shareRoot.get("documentId")).where(shareRoot.get("groupId").in(groupIds));
            return root.get("id").in(sharedDocumentIds);
        };
    }

    /** §4 step 1.2 "folder_id": {@code folderId} plus every id in its subtree (already resolved by the caller). */
    static Specification<Document> inFolderIds(List<UUID> folderIds) {
        return (root, query, cb) -> root.get("folderId").in(folderIds);
    }

    /**
     * §4 step 1.2 "media_type": {@link Document} has no {@code media_type}
     * column (same as the OpenSearch mapping note, search-fulltext-01 §2.1) -
     * re-derives it from {@code mime_type} via the same 3 categories {@link
     * MediaTypeResolver} uses at index time, so both search paths agree.
     */
    static Specification<Document> hasMediaType(List<String> mediaTypes) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            for (String mediaType : mediaTypes) {
                if (MediaTypeResolver.AUDIO.equals(mediaType)) {
                    predicates.add(cb.like(root.get("mimeType"), "audio/%"));
                } else if (MediaTypeResolver.VIDEO.equals(mediaType)) {
                    predicates.add(cb.like(root.get("mimeType"), "video/%"));
                } else if (MediaTypeResolver.DOCUMENT.equals(mediaType)) {
                    predicates.add(cb.and(
                            cb.notLike(root.get("mimeType"), "audio/%"), cb.notLike(root.get("mimeType"), "video/%")));
                }
                // An unrecognized value simply never matches anything - no error (§3.3 doesn't
                // close-list mediaType, only doc_type/confirmSource do).
            }
            return predicates.isEmpty() ? cb.disjunction() : cb.or(predicates.toArray(new Predicate[0]));
        };
    }

    /**
     * §4 step 1.2 "confirm_source": {@code confirm_source} lives on {@link
     * DigitizationRun}, not {@code Document} - {@code manual} matches both DB
     * values {@code manual_unchanged}/{@code manual_edited} (search-fulltext-
     * 01-tim-kiem-co-ban.md §2.1's normalization note), {@code auto} matches
     * {@code auto} only. A document with no digitization run at all never
     * matches either value (never "false positive" for an un-digitized file).
     */
    static Specification<Document> hasConfirmSource(String confirmSource) {
        List<String> dbValues = "manual".equals(confirmSource)
                ? List.of(DigitizationRun.CONFIRM_MANUAL_UNCHANGED, DigitizationRun.CONFIRM_MANUAL_EDITED)
                : List.of(DigitizationRun.CONFIRM_AUTO);

        return (root, query, cb) -> {
            Subquery<UUID> matchingDocumentIds = query.subquery(UUID.class);
            Root<DigitizationRun> runRoot = matchingDocumentIds.from(DigitizationRun.class);
            matchingDocumentIds
                    .select(runRoot.get("documentId"))
                    .where(runRoot.get("confirmSource").in(dbValues));
            return root.get("id").in(matchingDocumentIds);
        };
    }
}
