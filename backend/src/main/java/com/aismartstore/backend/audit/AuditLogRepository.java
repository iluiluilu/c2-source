package com.aismartstore.backend.audit;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.stream.Stream;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long>, JpaSpecificationExecutor<AuditLog> {

    /**
     * Per-resource change history, most recent first. Backs the system-config
     * history/rollback screen (Story 5 AC-1); the (resource_type, resource_id,
     * id) index added in V7 keeps this cheap as audit_log grows.
     */
    List<AuditLog> findByResourceTypeAndResourceIdOrderByIdDesc(String resourceType, String resourceId);

    /** Kept for tests that assert on the raw audit trail (oldest first); production code uses {@link AuditService#search} / {@link #streamByIdRange} instead. */
    @Query("select a from AuditLog a order by a.id asc")
    List<AuditLog> findAllAscending();

    /** audit-logging.md §4.5: resolve the {@code from} boundary of a verify(from,to) call to an id. */
    @Query("select min(a.id) from AuditLog a where a.createdAt >= :from")
    Long findMinIdWithCreatedAtGreaterThanEqual(@Param("from") OffsetDateTime from);

    /** audit-logging.md §4.5: resolve the {@code to} boundary of a verify(from,to) call to an id. */
    @Query("select max(a.id) from AuditLog a where a.createdAt <= :to")
    Long findMaxIdWithCreatedAtLessThanEqual(@Param("to") OffsetDateTime to);

    /** Genesis row id, used by verifyChain when no checkpoint precedes the requested range. */
    @Query("select min(a.id) from AuditLog a")
    Long findMinId();

    /**
     * Streamed (not loaded into a List) walk of the chain in a bounded id
     * range, for {@link AuditService#verifyChain}. Caller must run inside a
     * transaction and close the stream (try-with-resources).
     */
    @QueryHints(@jakarta.persistence.QueryHint(
            name = org.hibernate.jpa.HibernateHints.HINT_FETCH_SIZE, value = "500"))
    @Query("select a from AuditLog a where a.id between :startId and :endId order by a.id asc")
    Stream<AuditLog> streamByIdRange(@Param("startId") long startId, @Param("endId") long endId);
}
