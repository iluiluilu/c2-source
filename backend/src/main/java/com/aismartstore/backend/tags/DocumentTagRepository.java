package com.aismartstore.backend.tags;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DocumentTagRepository extends JpaRepository<DocumentTag, DocumentTagId> {

    List<DocumentTag> findByDocumentIdOrderByAssignedAtAsc(UUID documentId);

    List<DocumentTag> findByDocumentIdIn(List<UUID> documentIds);

    /**
     * classification-notes-01-tags.md §4.3 step 4 (US2 AC-3): at most 1 row
     * thanks to {@code UQ_document_tag_one_doc_type} (V20).
     */
    Optional<DocumentTag> findByDocumentIdAndTagKind(UUID documentId, String tagKind);

    @Query("select t from DocumentTag dt join Tag t on t.id = dt.tagId "
            + "where dt.documentId = :documentId order by dt.assignedAt asc")
    List<Tag> findTagsForDocument(@Param("documentId") UUID documentId);

    /**
     * bugs/bug-002-search-index-version-conflict.md muc 5.2 - 1 trong 5
     * nguon "do moi du lieu" dung de tinh {@code contentVersion} cho OpenSearch
     * external versioning (thay the {@code outbox_event.id} - xem
     * DocumentIndexFieldsBuilder#build).
     */
    @Query("select max(dt.assignedAt) from DocumentTag dt where dt.documentId = :documentId")
    Optional<OffsetDateTime> findMaxAssignedAtByDocumentId(@Param("documentId") UUID documentId);

    /**
     * A real INSERT (not {@code save()}, which for an entity with a
     * manually-assigned composite key merges/upserts instead of failing on a
     * duplicate PK) - matches the NestJS backend's deliberate
     * {@code links.insert()} so a re-assignment trips a real unique
     * violation, not a silent upsert.
     *
     * <p>classification-notes-01-tags.md §2 (V20): now also carries the
     * denormalized {@code tag_kind} so the partial unique index
     * {@code UQ_document_tag_one_doc_type} can enforce "exactly one doc_type
     * per document" purely at the DB layer.
     */
    @Modifying
    @Query(
            value = "INSERT INTO document_tag "
                    + "(document_id, tag_id, tag_kind, assigned_by_sub, assigned_by_username, assigned_at) "
                    + "VALUES (:documentId, :tagId, :tagKind, :assignedBySub, :assignedByUsername, now())",
            nativeQuery = true)
    void insert(
            @Param("documentId") UUID documentId,
            @Param("tagId") UUID tagId,
            @Param("tagKind") String tagKind,
            @Param("assignedBySub") String assignedBySub,
            @Param("assignedByUsername") String assignedByUsername);

    @Modifying
    @Query("delete from DocumentTag dt where dt.documentId = :documentId and dt.tagId = :tagId")
    int deleteByDocumentIdAndTagId(@Param("documentId") UUID documentId, @Param("tagId") UUID tagId);

    /**
     * delete-restore-trash-02-xoa-cung-xung-dot.md §4.2 bước 2 ({@code
     * TrashPurgeService}): wipe every tag link of a document being hard-
     * deleted - the FK is {@code ON DELETE CASCADE} (V20), but the "bia mộ"
     * model (Quyết định 5 SA) never deletes the {@code document} row itself,
     * so that cascade never fires and this must be done explicitly.
     */
    @Modifying
    @Query("delete from DocumentTag dt where dt.documentId = :documentId")
    int deleteByDocumentId(@Param("documentId") UUID documentId);

    /**
     * classification-notes-01-tags.md §4.3 step 4 (US2 AC-3): used by the
     * assign "replace" flow to atomically drop the previous {@code doc_type}
     * link before inserting the new one, bypassing {@code TagsService
     * .unassign}'s public "doc_type is mandatory" guard (that guard only
     * applies to the standalone {@code DELETE} endpoint - see §3 API
     * contract, DELETE /documents/{id}/tags/{tagId} row).
     */
    @Modifying
    @Query("delete from DocumentTag dt where dt.documentId = :documentId and dt.tagKind = :tagKind")
    int deleteByDocumentIdAndTagKind(@Param("documentId") UUID documentId, @Param("tagKind") String tagKind);

    /**
     * classification-notes-01-tags.md §4.4 (Task 7 backfill) + §3
     * ({@code GET /documents/unclassified-doc-type}, US2 AC-5/AC-6): documents
     * with no {@code doc_type} link yet, oldest first.
     */
    @Query(
            value = "SELECT d.id FROM document d "
                    + "WHERE d.deleted_at IS NULL "
                    + "AND NOT EXISTS (SELECT 1 FROM document_tag dt "
                    + "WHERE dt.document_id = d.id AND dt.tag_kind = 'doc_type') "
                    + "ORDER BY d.created_at "
                    + "LIMIT :limit",
            nativeQuery = true)
    List<UUID> findDocumentIdsMissingDocType(@Param("limit") int limit);
}
