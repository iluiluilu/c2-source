package com.aismartstore.backend.digitization.results;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * digitization-results-store.md §3/§4: the only writer of {@code
 * document_digitization_status} is {@link DigitizationRunLifecycleServiceImpl}
 * (AC-4 User Story 4, "không luồng nào khác được ghi trực tiếp"). {@link
 * #upsertTaskStatus} is the sole write path - callers outside that service
 * must not use {@link #save} on this repository directly. {@link
 * #deleteByDocumentId} is the sole exception (hard delete on document purge,
 * bugs/bug-004-trash-purge-missing-child-table-cleanup.md), not a status
 * write.
 */
public interface DocumentDigitizationStatusRepository extends JpaRepository<DocumentDigitizationStatus, UUID> {

    /**
     * bugs/bug-004-trash-purge-missing-child-table-cleanup.md -
     * {@code TrashPurgeService#purgeOne} was missing this table entirely:
     * confirmed on stg as leftover drift ({@code
     * DocumentDigitizationStatusReconciliationJob} flagging every purged
     * document that had digitization history, forever, since nothing ever
     * cleaned this row). No FK dependents - safe at any point in the purge
     * sequence, no-op if the document never had one.
     */
    @Modifying
    void deleteByDocumentId(UUID documentId);

    /**
     * Lazily creates the row on the first call for a {@code documentId}
     * (§4 bước 1), otherwise updates only the column for {@code task} and
     * recomputes {@code overall_status} - all in one atomic statement (no
     * Java-side read-modify-write round trip, avoiding the lost-update window
     * a {@code findById} + {@code save} pattern would have). {@code
     * overall_status} priority, highest first: {@code failed} &gt; {@code
     * processing} &gt; {@code queued} &gt; {@code in_review} &gt; {@code
     * pending_review} &gt; {@code confirmed} &gt; {@code not_applicable}
     * (US4 AC-2 order, chốt 2026-09-07; {@code processing} over {@code queued}
     * within the "processing/queued" tier is a DEV refinement to pick one
     * concrete value when tasks disagree).
     *
     * <p>Uses {@code SELECT ... FOR UPDATE} on the existing row (when
     * present) to serialize concurrent updates to different task columns of
     * the same document. The very first insert for a brand new {@code
     * documentId} is not covered by that lock (nothing to lock yet); two
     * {@code createRun} calls for two different tasks of the same brand-new
     * document landing in the exact same instant could in theory lose one
     * task's status - accepted per digitization-results-store.md §7 ("rủi ro
     * kỹ thuật khác"), the periodic reconciliation job (Task 7) is the
     * documented backstop for this kind of drift.
     */
    @Modifying
    @Query(
            value =
                    "WITH existing AS ("
                            + "  SELECT * FROM document_digitization_status WHERE document_id = :documentId FOR UPDATE"
                            + "), new_values AS ("
                            + "  SELECT"
                            + "    CAST(:documentId AS uuid) AS document_id,"
                            + "    CASE WHEN :task = 'ocr' THEN :taskStatus "
                            + "         ELSE COALESCE((SELECT ocr_status FROM existing), 'not_applicable') END AS ocr_status,"
                            + "    CASE WHEN :task = 'stt' THEN :taskStatus "
                            + "         ELSE COALESCE((SELECT stt_status FROM existing), 'not_applicable') END AS stt_status,"
                            + "    CASE WHEN :task = 'sed' THEN :taskStatus "
                            + "         ELSE COALESCE((SELECT sed_status FROM existing), 'not_applicable') END AS sed_status,"
                            + "    CASE WHEN :task = 'video_summary' THEN :taskStatus "
                            + "         ELSE COALESCE((SELECT video_summary_status FROM existing), 'not_applicable') END"
                            + "         AS video_summary_status,"
                            + "    CASE WHEN :task = 'video' THEN :taskStatus "
                            + "         ELSE COALESCE((SELECT video_status FROM existing), 'not_applicable') END AS video_status"
                            + ") "
                            + "INSERT INTO document_digitization_status "
                            + "  (document_id, ocr_status, stt_status, sed_status, video_summary_status, video_status, overall_status, updated_at) "
                            + "SELECT document_id, ocr_status, stt_status, sed_status, video_summary_status, video_status, "
                            + "  CASE"
                            + "    WHEN 'failed' IN (ocr_status, stt_status, sed_status, video_summary_status, video_status) THEN 'failed'"
                            + "    WHEN 'processing' IN (ocr_status, stt_status, sed_status, video_summary_status, video_status) THEN 'processing'"
                            + "    WHEN 'queued' IN (ocr_status, stt_status, sed_status, video_summary_status, video_status) THEN 'queued'"
                            + "    WHEN 'in_review' IN (ocr_status, stt_status, sed_status, video_summary_status, video_status) THEN 'in_review'"
                            + "    WHEN 'pending_review' IN (ocr_status, stt_status, sed_status, video_summary_status, video_status) THEN 'pending_review'"
                            + "    WHEN 'confirmed' IN (ocr_status, stt_status, sed_status, video_summary_status, video_status) THEN 'confirmed'"
                            + "    ELSE 'not_applicable'"
                            + "  END, "
                            + "  now() "
                            + "FROM new_values "
                            + "ON CONFLICT (document_id) DO UPDATE SET "
                            + "  ocr_status = EXCLUDED.ocr_status,"
                            + "  stt_status = EXCLUDED.stt_status,"
                            + "  sed_status = EXCLUDED.sed_status,"
                            + "  video_summary_status = EXCLUDED.video_summary_status,"
                            + "  video_status = EXCLUDED.video_status,"
                            + "  overall_status = EXCLUDED.overall_status,"
                            + "  updated_at = EXCLUDED.updated_at",
            nativeQuery = true)
    void upsertTaskStatus(
            @Param("documentId") UUID documentId, @Param("task") String task, @Param("taskStatus") String taskStatus);
}
