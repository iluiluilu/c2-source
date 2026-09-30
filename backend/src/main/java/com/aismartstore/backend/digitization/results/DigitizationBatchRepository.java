package com.aismartstore.backend.digitization.results;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DigitizationBatchRepository extends JpaRepository<DigitizationBatch, UUID> {

    /**
     * digitization-results-store.md §3: atomic counter bump (same idiom as
     * {@code UploadBatchRepository.incrementActual}) - avoids lost updates
     * when multiple workers finish jobs from the same batch concurrently.
     */
    @Modifying
    @Query("UPDATE DigitizationBatch b SET b.succeededCount = b.succeededCount + 1, "
            + "b.updatedAt = CURRENT_TIMESTAMP WHERE b.id = :id")
    int incrementSucceeded(@Param("id") UUID id);

    @Modifying
    @Query("UPDATE DigitizationBatch b SET b.failedCount = b.failedCount + 1, "
            + "b.updatedAt = CURRENT_TIMESTAMP WHERE b.id = :id")
    int incrementFailed(@Param("id") UUID id);
}
