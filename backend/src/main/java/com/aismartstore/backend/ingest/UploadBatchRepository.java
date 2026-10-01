package com.aismartstore.backend.ingest;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UploadBatchRepository extends JpaRepository<UploadBatch, UUID> {

    /**
     * Owner-scoped lookup, additionally filtered by {@code kind}
     * (personal-storage-quota.md §4.1): a foreign id, or a batch of the
     * wrong kind (e.g. a warehouse batch id passed to a personal endpoint),
     * both resolve to "not found" - never leaks existence.
     */
    Optional<UploadBatch> findByIdAndOwnerSubAndKind(UUID id, String ownerSub, BatchKind kind);

    /**
     * personal-storage-quota.md §4.2 step 7: bump the batch's actual
     * counters for display/progress only (Story 3 AC-1/AC-3) - NOT the source
     * of truth for quota enforcement (that's the advisory-lock + re-check
     * SUM in PersonalService.commitFileInsert). Runs inside the same
     * already-locked transaction as the personal_file insert.
     */
    @Modifying
    @Query("UPDATE UploadBatch b SET b.actualFileCount = b.actualFileCount + 1, "
            + "b.actualTotalBytes = b.actualTotalBytes + :size, b.updatedAt = CURRENT_TIMESTAMP WHERE b.id = :id")
    int incrementActual(@Param("id") UUID id, @Param("size") long size);

    /**
     * data-ingestion.md §4.2 step 4: atomic "reserve quota" update, one round
     * trip, no application-level lock (Postgres row lock on the matched
     * {@code upload_batch} row does the serializing). Returns the number of
     * rows updated - 0 means the reservation was rejected (either the batch
     * moved out of OPEN concurrently, or the new counts would exceed the
     * current limit); 1 means it succeeded and the counters are already
     * updated.
     *
     * <p>Deliberately returns a row count rather than using the SQL
     * {@code RETURNING} clause the spec illustrates the query with: nothing
     * downstream needs the post-update counts back in the same round trip
     * (the caller already knows whether it succeeded), so the simpler,
     * unambiguous {@code @Modifying int} form is used instead.
     */
    @Modifying
    @Query(
            value = "UPDATE upload_batch SET actual_file_count = actual_file_count + 1, "
                    + "actual_total_bytes = actual_total_bytes + :fileSize, updated_at = now() "
                    + "WHERE id = :batchId AND status = 'OPEN' "
                    + "AND actual_file_count + 1 <= :maxFiles AND actual_total_bytes + :fileSize <= :maxBytes",
            nativeQuery = true)
    int reserve(
            @Param("batchId") UUID batchId,
            @Param("fileSize") long fileSize,
            @Param("maxFiles") long maxFiles,
            @Param("maxBytes") long maxBytes);

    /** Gives back one {@link #reserve} (DISPLAY_NAME_CONFLICT after reservation: the client retries the file). */
    @Modifying
    @Query("UPDATE UploadBatch b SET b.actualFileCount = b.actualFileCount - 1, "
            + "b.actualTotalBytes = b.actualTotalBytes - :size, b.updatedAt = CURRENT_TIMESTAMP "
            + "WHERE b.id = :id AND b.actualFileCount > 0")
    int release(@Param("id") UUID id, @Param("size") long size);

    @Modifying
    @Query("UPDATE UploadBatch b SET b.status = :status, b.updatedAt = CURRENT_TIMESTAMP WHERE b.id = :id")
    int updateStatus(@Param("id") UUID id, @Param("status") String status);

    /** DEV addition (see V10 migration comment) - backs the close() summary's filesAccepted. */
    @Modifying
    @Query("UPDATE UploadBatch b SET b.acceptedFileCount = b.acceptedFileCount + 1, b.updatedAt = CURRENT_TIMESTAMP "
            + "WHERE b.id = :id")
    int incrementAccepted(@Param("id") UUID id);

    /** DEV addition (see V10 migration comment) - backs the close() summary's filesSkipped. */
    @Modifying
    @Query("UPDATE UploadBatch b SET b.skippedFileCount = b.skippedFileCount + 1, b.updatedAt = CURRENT_TIMESTAMP "
            + "WHERE b.id = :id")
    int incrementSkipped(@Param("id") UUID id);

    /** DEV addition (see V10 migration comment) - backs the close() summary's filesRejected. */
    @Modifying
    @Query("UPDATE UploadBatch b SET b.rejectedFileCount = b.rejectedFileCount + 1, b.updatedAt = CURRENT_TIMESTAMP "
            + "WHERE b.id = :id")
    int incrementRejected(@Param("id") UUID id);
}
