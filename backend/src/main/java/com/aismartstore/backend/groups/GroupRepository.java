package com.aismartstore.backend.groups;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * group-management-sharing-01-group-lifecycle.md §2: every read here filters
 * out soft-deleted groups ({@code deleted_at IS NULL}) so callers never have
 * to remember to do it themselves - see {@link Group#isDeleted()}.
 */
public interface GroupRepository extends JpaRepository<Group, UUID> {

    Optional<Group> findByIdAndDeletedAtIsNull(UUID id);

    boolean existsByIdAndDeletedAtIsNull(UUID id);

    List<Group> findAllByIdInAndDeletedAtIsNull(Collection<UUID> ids);

    /** Story 6 AC-3: total active groups, for the system-wide 1.000 group cap. */
    long countByDeletedAtIsNull();
}
