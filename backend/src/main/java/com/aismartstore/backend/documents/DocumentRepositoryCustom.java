package com.aismartstore.backend.documents;

import java.util.List;
import org.springframework.data.jpa.domain.Specification;

/**
 * Arbitrary limit/offset (not page-aligned), matching the NestJS backend's
 * list(options) API - Spring Data's Pageable/Page abstraction forces
 * offset = page * size, which doesn't fit that shape (see the previous
 * native-SQL {@code search}, replaced by this in rbac-group-scope.md Task 8
 * so the query can also carry a {@code Specification} - i.e. {@link
 * com.aismartstore.backend.groups.BusinessDataScope} - instead of nothing
 * enforcing scope on the list endpoint).
 */
public interface DocumentRepositoryCustom {
    List<Document> search(Specification<Document> spec, int limit, int offset);

    /**
     * delete-restore-trash-01-xoa-mem-thung-rac.md §4.2: same shape as
     * {@link #search}, but ordered by {@code deletedAt DESC} (most recently
     * trashed first) instead of {@code createdAt DESC} - {@code GET /trash}
     * is the only caller.
     */
    List<Document> searchTrash(Specification<Document> spec, int limit, int offset);
}
