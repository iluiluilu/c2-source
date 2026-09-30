package com.aismartstore.backend.digitization;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DigitizationSourceRepository extends JpaRepository<DigitizationSource, UUID> {

    /** delete-restore-trash-02-xoa-cung-xung-dot.md §4.2 bước 2 ({@code TrashPurgeService}): see {@code DocumentTagRepository#deleteByDocumentId}'s javadoc for why this is explicit. */
    @Modifying
    int deleteByDocumentId(UUID documentId);

    /**
     * digitization-pipeline-01-source-dispatch.md §4.5 step 1: rows whose
     * immediate publish attempt (§4.4) never flipped {@code dispatch_status}
     * to {@code published} - either it failed (connection issue) or the
     * backend died between the DB write and the publish call.
     */
    List<DigitizationSource> findByDispatchableTrueAndDispatchStatusAndUpdatedAtBefore(
            String dispatchStatus, OffsetDateTime updatedBefore, Pageable pageable);

    /**
     * digitization-pipeline-02-ocr-stt-processing.md §4.5 step 2 - "lần đầu
     * set": a no-op (0 rows) if {@code duration_ms} is already populated, so
     * a duplicate/late {@code q.result} for an already-completed {@code stt}
     * run can never overwrite it.
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE DigitizationSource s SET s.durationMs = :durationMs, s.updatedAt = :now "
            + "WHERE s.id = :sourceId AND s.durationMs IS NULL")
    int setDurationMsIfAbsent(
            @Param("sourceId") UUID sourceId, @Param("durationMs") Integer durationMs, @Param("now") OffsetDateTime now);
}
