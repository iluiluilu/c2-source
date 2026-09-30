package com.aismartstore.backend.documents;

import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.util.List;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Repository;

/**
 * {@link DocumentRepositoryCustom} - a plain JPA Criteria query so {@code
 * setFirstResult}/{@code setMaxResults} (arbitrary limit/offset) can be
 * combined with a {@link Specification}, which {@code
 * JpaSpecificationExecutor.findAll(Specification, Pageable)} does not allow
 * (Pageable forces offset = page * size).
 */
@Repository
public class DocumentRepositoryImpl implements DocumentRepositoryCustom {

    private final EntityManager entityManager;

    public DocumentRepositoryImpl(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    public List<Document> search(Specification<Document> spec, int limit, int offset) {
        return search(spec, limit, offset, "createdAt");
    }

    @Override
    public List<Document> searchTrash(Specification<Document> spec, int limit, int offset) {
        return search(spec, limit, offset, "deletedAt");
    }

    private List<Document> search(Specification<Document> spec, int limit, int offset, String orderByProperty) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Document> query = cb.createQuery(Document.class);
        Root<Document> root = query.from(Document.class);

        Predicate predicate = spec == null ? null : spec.toPredicate(root, query, cb);
        if (predicate != null) {
            query.where(predicate);
        }
        query.orderBy(cb.desc(root.get(orderByProperty)));

        return entityManager
                .createQuery(query)
                .setFirstResult(offset)
                .setMaxResults(limit)
                .getResultList();
    }
}
