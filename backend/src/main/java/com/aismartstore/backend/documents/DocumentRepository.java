package com.aismartstore.backend.documents;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * {@code JpaSpecificationExecutor} + {@link DocumentRepositoryCustom}
 * (rbac-group-scope.md Task 8): every user-facing read goes through a {@code
 * Specification} that composes {@link com.aismartstore.backend.groups.BusinessDataScope}
 * with the {@code folderId}/{@code tagId} filters below, so scope can never
 * accidentally be omitted by a caller that only has a plain {@code
 * findAll()} available.
 */
public interface DocumentRepository
        extends JpaRepository<Document, UUID>, JpaSpecificationExecutor<Document>, DocumentRepositoryCustom {

    /**
     * personal-to-warehouse.md AC-21: ids of the caller's personal files that currently
     * have a live (not trashed) warehouse copy - a single query, no N+1.
     */
    @Query("SELECT DISTINCT d.sourcePersonalFileId FROM Document d "
            + "WHERE d.uploaderSub = :uploaderSub AND d.sourcePersonalFileId IS NOT NULL AND d.deletedAt IS NULL")
    List<UUID> findLiveSourcePersonalFileIdsByUploader(
            @org.springframework.data.repository.query.Param("uploaderSub") String uploaderSub);

    List<Document> findBySha256AndStatus(String sha256, String status);

    /**
     * SPEC-folder-upload-display-name: same expression/predicate as {@code UQ_document_display_name_alive}
     * (R14). {@code folderKey} = folder id or the all-zero sentinel uuid; {@code excludeId} skips the row itself.
     */
    @Query(value = "SELECT EXISTS(SELECT 1 FROM document d "
            + "WHERE COALESCE(d.folder_id, '00000000-0000-0000-0000-000000000000'::uuid) = CAST(:folderKey AS uuid) "
            + "AND md5(d.relative_path || '/' || lower(d.display_name)) = md5(:path || '/' || lower(:name)) "
            + "AND d.deleted_at IS NULL AND d.status <> 'rejected' AND d.id <> CAST(:excludeId AS uuid))",
            nativeQuery = true)
    boolean existsAliveDisplayName(
            @Param("folderKey") String folderKey,
            @Param("path") String path,
            @Param("name") String name,
            @Param("excludeId") String excludeId);

    /** Story 3 AC-1/AC-2: folder deletion is blocked only by ACTIVE (not soft-deleted) data. */
    boolean existsByFolderIdAndStatusAndDeletedAtIsNull(UUID folderId, String status);

    /**
     * Story 5 AC-1: total bytes used - deliberately NOT filtered by
     * deleted_at (trash still occupies real bytes on SeaweedFS until hard
     * deletion). Aggregate query, not a maintained counter - see spec §4.4
     * for the tradeoff rationale.
     */
    @Query("SELECT COALESCE(SUM(d.size), 0) FROM Document d WHERE d.status = :status")
    long sumSizeByStatus(@Param("status") String status);

    /**
     * digitization-pipeline-01-source-dispatch.md §4.5 step 2: reconciliation
     * query for "document committed but the in-JVM dispatch listener never
     * ran" (backend crash between the two, or - post decision-note in
     * {@code DigitizationDispatchListener} - any other reason the plain
     * {@code @EventListener} didn't fire/complete). {@code cutoff} keeps this
     * from racing a document whose listener simply hasn't run yet.
     *
     * <p>digitization-pipeline-04-video-no-separation.md §4.3/§0 point 2: no
     * longer excludes documents that already have a {@code
     * source_separation_job} row - video intake no longer creates that row
     * at all, so the {@code NOT EXISTS digitization_source} condition alone
     * is sufficient to prevent a duplicate dispatch (unique {@code id =
     * document_id}). This deliberately catches video documents left mid-way
     * through the old separation flow (any {@code source_separation_job}
     * status, including {@code failed}) and dispatches them fresh through
     * the new {@code q.video} path.
     */
    @Query(
            value = "SELECT d.* FROM document d "
                    + "WHERE d.status = 'clean' AND d.deleted_at IS NULL "
                    + "AND (d.mime_type LIKE 'video/%' OR d.mime_type LIKE 'audio/%' "
                    + "OR d.mime_type LIKE 'image/%' OR d.mime_type = 'application/pdf') "
                    + "AND NOT EXISTS (SELECT 1 FROM digitization_source ds WHERE ds.document_id = d.id) "
                    + "AND d.created_at < :cutoff "
                    + "ORDER BY d.created_at "
                    + "LIMIT :limit",
            nativeQuery = true)
    List<Document> findMissedForDigitization(@Param("cutoff") OffsetDateTime cutoff, @Param("limit") int limit);

    /**
     * delete-restore-trash-01-xoa-mem-thung-rac.md §4.3/§4.5: loads a trash
     * item by id, scoped to actually being in trash - used by {@code
     * TrashService#getOne}/{@code #restore} so a still-active document 404s
     * the same way a missing one does (never leaks "this id exists but
     * isn't trashed" through a different error).
     */
    Optional<Document> findByIdAndDeletedAtIsNotNull(UUID id);

    /**
     * Story 4 (spec §4.4): the trash-only counterpart of {@link
     * #sumSizeByStatus} - same table/status, restricted to soft-deleted rows
     * so {@code WarehouseCapacityService.Snapshot#trashBytes} can be shown
     * separately from {@code usedBytes} without changing that field's
     * existing (deliberately un-filtered) formula.
     */
    @Query("SELECT COALESCE(SUM(d.size), 0) FROM Document d WHERE d.status = :status AND d.deletedAt IS NOT NULL")
    long sumSizeByStatusAndDeletedAtIsNotNull(@Param("status") String status);

    /**
     * Story 3 AC-4 (spec §4.5): "mồ côi" - both the person who trashed it and
     * the original uploader are non-{@code active} accounts, so nobody left
     * can restore it (Quyết định 6 SA's restore-permission rule). Native SQL
     * for the 2 correlated {@code EXISTS} against {@code account} (no JPA
     * association between {@code Document} and {@code Account}). {@code
     * purge_state} (file 02/V30) does not exist yet at this point in the
     * rollout - every soft-deleted row here is implicitly "deleted" (no
     * {@code purging} sub-state exists until the hard-delete job from file
     * 02 ships), so that column is deliberately not referenced.
     */
    @Query(
            value = "SELECT count(*) AS cnt, min(d.purge_after) AS nearest FROM document d "
                    + "WHERE d.deleted_at IS NOT NULL "
                    + "AND EXISTS (SELECT 1 FROM account a WHERE a.keycloak_user_id = d.deleted_by_sub "
                    + "AND a.status != 'active') "
                    + "AND EXISTS (SELECT 1 FROM account a WHERE a.keycloak_user_id = d.uploader_sub "
                    + "AND a.status != 'active')",
            nativeQuery = true)
    List<Object[]> countUnrecoverableInTrash();

    /**
     * Story 3 (spec §3/§4.5, R3 stats): total item count + bytes per
     * hạn-xóa bucket, over every soft-deleted row. Fixed bucket boundaries
     * (DEV choice - BA's AC only gives "sắp hết hạn trong 30 ngày tới" as an
     * example, no exact table): {@code expired} (already past {@code
     * purge_after}), then 7/30/90 ngày, then {@code later}.
     */
    @Query(
            value = "SELECT bucket, count(*) AS cnt, coalesce(sum(size), 0) AS bytes FROM ("
                    + "  SELECT size, CASE "
                    + "    WHEN purge_after <= now() THEN 'expired' "
                    + "    WHEN purge_after <= now() + interval '7 days' THEN 'within_7_days' "
                    + "    WHEN purge_after <= now() + interval '30 days' THEN 'within_30_days' "
                    + "    WHEN purge_after <= now() + interval '90 days' THEN 'within_90_days' "
                    + "    ELSE 'later' END AS bucket "
                    + "  FROM document WHERE deleted_at IS NOT NULL"
                    + ") buckets GROUP BY bucket",
            nativeQuery = true)
    List<Object[]> countByExpiryBucket();

    /**
     * delete-restore-trash-02-xoa-cung-xung-dot.md §4.4 (warehouse-structure
     * folder-delete hook): every document currently filed under {@code
     * folderId} - any status, including soft-deleted/rejected/infected -
     * used to snapshot {@code deleted_folder_path_ids} right before the
     * folder row itself is deleted.
     */
    List<Document> findByFolderId(UUID folderId);

    /**
     * §4.2 bước 3 (khử trùng lặp trước khi xóa object SeaweedFS): true if
     * some OTHER, not-yet-purged document still shares this {@code sha256}
     * (data-ingestion.md's whole-file dedup key) - i.e. the object backing
     * {@code storageKey} is still in use and must NOT be deleted.
     */
    boolean existsBySha256AndIdNotAndStatusNot(String sha256, UUID id, String status);

    /**
     * §4.2 bước 1: atomically claims up to {@code limit} documents due for
     * hard delete by flipping them to {@code purging} in the same statement
     * as the {@code FOR UPDATE SKIP LOCKED} select - same idiom as {@code
     * SourceSeparationJobRepository#claimNextBatch} (now legacy/unused, see
     * digitization-pipeline-04-video-no-separation.md §1). ShedLock already
     * guarantees only one instance runs {@code TrashPurgeJob} at a time;
     * {@code SKIP LOCKED} is kept as a second line of defense (§4.2 note).
     */
    @Query(
            value = "UPDATE document SET purge_state = 'purging', purging_started_at = now() "
                    + "WHERE id IN ("
                    + "  SELECT id FROM document "
                    + "  WHERE deleted_at IS NOT NULL AND purge_after <= now() AND purge_state = 'deleted' "
                    + "  ORDER BY purge_after "
                    + "  LIMIT :limit "
                    + "  FOR UPDATE SKIP LOCKED"
                    + ") "
                    + "RETURNING id",
            nativeQuery = true)
    List<UUID> claimPurgeBatch(@Param("limit") int limit);

    /**
     * {@code PurgeStuckRecoveryJob} (§4.3): a document stuck in {@code
     * purging} for longer than the threshold (a document's own transaction
     * in {@code TrashPurgeService} crashed mid-purge) goes back to {@code
     * deleted} so the next {@code trash-purge} tick can claim and reprocess
     * it - idempotent, since every step 2-4 write is itself a no-op-safe
     * {@code DELETE ... WHERE}/{@code NOT EXISTS}/overwrite.
     */
    @Modifying
    @Query("update Document d set d.purgeState = 'deleted', d.purgingStartedAt = null "
            + "where d.purgeState = 'purging' and d.purgingStartedAt <= :cutoff")
    int recoverStuckPurging(@Param("cutoff") OffsetDateTime cutoff);
}
