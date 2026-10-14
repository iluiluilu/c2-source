package com.aismartstore.backend.reporting;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface ReportExportRepository extends JpaRepository<ReportExport, UUID> {

    Page<ReportExport> findByRequestedBySubOrderByCreatedAtDesc(String sub, Pageable pageable);

    @Query("SELECT count(e) FROM ReportExport e WHERE e.status IN ('PENDING','RUNNING') AND e.requestedBySub = :sub")
    long countActiveByUser(@Param("sub") String sub);

    @Query("SELECT count(e) FROM ReportExport e WHERE e.status IN ('PENDING','RUNNING')")
    long countActive();

    /**
     * SPEC §4 bước 4 - same idiom as {@code SourceSeparationJobRepository.claimNextBatch}: one
     * statement, {@code FOR UPDATE SKIP LOCKED}, flips PENDING (or RUNNING with an expired lease)
     * to RUNNING and bumps {@code attempt_count}. {@code RETURNING *} - callers get the post-update row.
     */
    @Query(
            value = "UPDATE report_export SET status = 'RUNNING', attempt_count = attempt_count + 1, "
                    + "started_at = now(), lease_expires_at = now() + make_interval(secs => :leaseSeconds) "
                    + "WHERE id IN ("
                    + "  SELECT id FROM report_export "
                    + "  WHERE status = 'PENDING' OR (status = 'RUNNING' AND lease_expires_at < now()) "
                    + "  ORDER BY created_at "
                    + "  LIMIT :limit "
                    + "  FOR UPDATE SKIP LOCKED"
                    + ") "
                    + "RETURNING *",
            nativeQuery = true)
    @Transactional
    List<ReportExport> claimNextBatch(@Param("leaseSeconds") int leaseSeconds, @Param("limit") int limit);

    /** Lease renewal before render (review #5); 0 rows = job no longer ours. */
    @Modifying
    @Transactional
    @Query(
            value = "UPDATE report_export SET lease_expires_at = now() + make_interval(secs => :leaseSeconds) "
                    + "WHERE id = :id AND status = 'RUNNING'",
            nativeQuery = true)
    int renewLease(@Param("id") UUID id, @Param("leaseSeconds") int leaseSeconds);

    @Modifying
    @Transactional
    @Query(
            value = "UPDATE report_export SET status = 'DONE', object_key = :key, byte_size = :bytes, "
                    + "row_count = :rows, finished_at = now(), error_message = NULL, lease_expires_at = NULL, "
                    + "expires_at = now() + make_interval(days => :ttlDays) "
                    + "WHERE id = :id AND status = 'RUNNING'",
            nativeQuery = true)
    int markDone(
            @Param("id") UUID id,
            @Param("key") String key,
            @Param("bytes") long bytes,
            @Param("rows") int rows,
            @Param("ttlDays") int ttlDays);

    @Modifying
    @Transactional
    @Query(
            value = "UPDATE report_export SET status = 'FAILED', error_message = :message, row_count = :rows, "
                    + "finished_at = now(), lease_expires_at = NULL "
                    + "WHERE id = :id AND status = 'RUNNING'",
            nativeQuery = true)
    int markFailed(@Param("id") UUID id, @Param("message") String message, @Param("rows") Integer rows);

    /** Jobs stuck in PENDING for long (WARN only) - ReviewStaleAlertJob pattern. */
    @Query("SELECT count(e) FROM ReportExport e WHERE e.status = 'PENDING' AND e.createdAt < :cutoff")
    long countStalePending(@Param("cutoff") OffsetDateTime cutoff);

    @Query(
            value = "SELECT * FROM report_export WHERE status = 'DONE' AND expires_at < now() "
                    + "ORDER BY expires_at LIMIT :limit FOR UPDATE SKIP LOCKED",
            nativeQuery = true)
    List<ReportExport> findExpiredDoneForUpdate(@Param("limit") int limit);
}
