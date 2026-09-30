package com.aismartstore.backend.tags;

import com.aismartstore.backend.documents.Document;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Repository;

/**
 * classification-notes-01-tags.md §4.5 ({@code GET /tags/report}, US5): a
 * plain Criteria query joining {@code document_tag}/{@code tag}/{@code
 * document} (3 independent roots + explicit join predicates - {@code
 * Document} has no mapped relation to {@code DocumentTag}/{@code Tag}, so a
 * portable entity-graph join isn't available), same
 * EntityManager-based-custom-repository shape as {@link
 * com.aismartstore.backend.documents.DocumentRepositoryImpl}.
 *
 * <p>The caller-supplied {@code scope} ({@link
 * com.aismartstore.backend.groups.BusinessDataScope#forDocuments}) is applied
 * against the {@code document} root <em>before</em> the {@code GROUP BY}, so
 * counts always reflect only documents visible to the calling user (§4.5 step
 * 4: never cached across users).
 */
@Repository
public class TagReportRepository {

    /** One row of {@code GET /tags/report}'s response. */
    public record Row(UUID tagId, String kind, String label, long count) {
    }

    private final EntityManager entityManager;

    public TagReportRepository(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    public List<Row> report(
            Specification<Document> scope, String kind, OffsetDateTime from, OffsetDateTime toExclusive, UUID folderId) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Object[]> query = cb.createQuery(Object[].class);

        Root<Document> document = query.from(Document.class);
        Root<DocumentTag> documentTag = query.from(DocumentTag.class);
        Root<Tag> tag = query.from(Tag.class);

        List<Predicate> predicates = new ArrayList<>();
        predicates.add(cb.equal(document.get("id"), documentTag.get("documentId")));
        predicates.add(cb.equal(documentTag.get("tagId"), tag.get("id")));
        predicates.add(scope.toPredicate(document, query, cb));
        if (kind != null) {
            predicates.add(cb.equal(tag.get("kind"), kind));
        }
        if (from != null) {
            predicates.add(cb.greaterThanOrEqualTo(document.get("createdAt"), from));
        }
        if (toExclusive != null) {
            predicates.add(cb.lessThan(document.get("createdAt"), toExclusive));
        }
        if (folderId != null) {
            predicates.add(cb.equal(document.get("folderId"), folderId));
        }

        query.multiselect(tag.get("id"), tag.get("kind"), tag.get("label"), cb.count(document.get("id")))
                .where(predicates.toArray(new Predicate[0]))
                .groupBy(tag.get("id"), tag.get("kind"), tag.get("label"));

        List<Object[]> rows = entityManager.createQuery(query).getResultList();
        return rows.stream()
                .map(r -> new Row((UUID) r[0], (String) r[1], (String) r[2], (Long) r[3]))
                .toList();
    }
}
