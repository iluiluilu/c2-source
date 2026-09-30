package com.aismartstore.backend.audit;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.jpa.domain.Specification;

/** audit-logging.md §3: {@code GET /audit} query filters, combined with AND. Every filter is optional. */
public final class AuditLogSpecifications {

    private AuditLogSpecifications() {
    }

    public static Specification<AuditLog> withFilters(
            OffsetDateTime from,
            OffsetDateTime to,
            String actor,
            String action,
            String resourceType,
            String resourceId) {
        return (root, query, cb) -> {
            List<jakarta.persistence.criteria.Predicate> predicates = new ArrayList<>();
            if (from != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), from));
            }
            if (to != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("createdAt"), to));
            }
            if (actor != null && !actor.isBlank()) {
                predicates.add(cb.equal(root.get("actor"), actor));
            }
            if (action != null && !action.isBlank()) {
                predicates.add(cb.equal(root.get("action"), action));
            }
            if (resourceType != null && !resourceType.isBlank()) {
                predicates.add(cb.equal(root.get("resourceType"), resourceType));
            }
            if (resourceId != null && !resourceId.isBlank()) {
                predicates.add(cb.equal(root.get("resourceId"), resourceId));
            }
            return cb.and(predicates.toArray(new jakarta.persistence.criteria.Predicate[0]));
        };
    }
}
