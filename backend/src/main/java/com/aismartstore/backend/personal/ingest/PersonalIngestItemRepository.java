package com.aismartstore.backend.personal.ingest;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PersonalIngestItemRepository extends JpaRepository<PersonalIngestItem, UUID> {

    List<PersonalIngestItem> findByBatchIdOrderByOriginalNameAsc(UUID batchId);

    long countByBatchIdAndStatusNot(UUID batchId, String status);

    /**
     * personal-to-warehouse.md §4 step 4: drop items of batches that are no
     * longer OPEN (closed/expired/limit-exceeded) and last touched before the cutoff.
     */
    @Modifying
    @Query(
            value = "DELETE FROM personal_ingest_item i USING upload_batch b "
                    + "WHERE i.batch_id = b.id AND b.status <> 'OPEN' AND b.updated_at < :cutoff",
            nativeQuery = true)
    int deleteForFinishedBatchesBefore(@Param("cutoff") OffsetDateTime cutoff);

    /** Batches expired but never closed (OPEN with expires_at in the past) are also cleaned. */
    @Modifying
    @Query(
            value = "DELETE FROM personal_ingest_item i USING upload_batch b "
                    + "WHERE i.batch_id = b.id AND b.status = 'OPEN' AND b.expires_at < :cutoff",
            nativeQuery = true)
    int deleteForExpiredOpenBatchesBefore(@Param("cutoff") OffsetDateTime cutoff);
}
