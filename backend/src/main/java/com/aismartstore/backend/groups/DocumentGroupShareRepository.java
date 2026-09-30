package com.aismartstore.backend.groups;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DocumentGroupShareRepository extends JpaRepository<DocumentGroupShare, DocumentGroupShareId> {

    List<DocumentGroupShare> findByDocumentIdOrderBySharedAtAsc(UUID documentId);

    boolean existsByDocumentIdAndGroupId(UUID documentId, UUID groupId);

    /**
     * bugs/bug-002-search-index-version-conflict.md muc 5.2 - 1 trong 5
     * nguon "do moi du lieu" dung de tinh {@code contentVersion} cho OpenSearch
     * external versioning (thay the {@code outbox_event.id} - xem
     * DocumentIndexFieldsBuilder#build).
     */
    @Query("select max(s.sharedAt) from DocumentGroupShare s where s.documentId = :documentId")
    Optional<OffsetDateTime> findMaxSharedAtByDocumentId(@Param("documentId") UUID documentId);

    /** group-management-sharing-02-document-sharing.md §4.3 (Task 6): who created a given share, for the unshare permission check. */
    Optional<DocumentGroupShare> findByDocumentIdAndGroupId(UUID documentId, UUID groupId);

    /** §4.1 step 2: how many groups a document is currently shared to, for the 50-groups/document cap (bulk share only). */
    long countByDocumentId(UUID documentId);

    @Modifying
    int deleteByDocumentIdAndGroupId(UUID documentId, UUID groupId);

    /** delete-restore-trash-02-xoa-cung-xung-dot.md §4.2 bước 2 ({@code TrashPurgeService}): see {@code DocumentTagRepository#deleteByDocumentId}'s javadoc for why this is explicit. */
    @Modifying
    int deleteByDocumentId(UUID documentId);

    /**
     * §4.2 step 1: the reverse of {@link #findByDocumentIdOrderBySharedAtAsc}
     * - every document shared to one group, joined against {@code document}
     * to pick up its metadata and to exclude soft-deleted documents. A
     * theta-join JPQL query (no JPA association between the two entities),
     * same idiom as {@link GroupMemberRepository#findGroupIdsByUserSub}.
     */
    @Query(
            value = "select s.documentId, d.originalName, d.uploaderSub, s.sharedBySub, s.sharedAt "
                    + "from DocumentGroupShare s, Document d "
                    + "where s.groupId = :groupId and s.documentId = d.id and d.deletedAt is null "
                    + "order by s.sharedAt desc",
            countQuery = "select count(s) from DocumentGroupShare s, Document d "
                    + "where s.groupId = :groupId and s.documentId = d.id and d.deletedAt is null")
    Page<Object[]> findGroupDocuments(@Param("groupId") UUID groupId, Pageable pageable);

    /**
     * delete-restore-trash-01-xoa-mem-thung-rac.md §3.2 step (c)/§4.1 step 3:
     * which of {@code documentIds} are shared to at least one of {@code
     * groupIds} - the "shared to a group I'm a member of" half of {@code
     * BusinessDataScope.forDocuments}'s predicate, re-derived here against a
     * whole batch at once (one query) instead of per-document.
     */
    @Query("select distinct s.documentId from DocumentGroupShare s "
            + "where s.documentId in :documentIds and s.groupId in :groupIds")
    List<UUID> findDocumentIdsSharedToAnyGroup(
            @Param("documentIds") List<UUID> documentIds, @Param("groupIds") List<UUID> groupIds);

    /**
     * §4.3 step 4: group ids a trashed document is (still) shared to that
     * are themselves soft-deleted - used only to build the {@code
     * groupsLostEffect} warning on restore, never to actually change
     * visibility (that already happens automatically via {@link
     * GroupMemberRepository#findGroupIdsByUserSub}'s own {@code g.deletedAt
     * is null} filter).
     */
    @Query("select s.groupId from DocumentGroupShare s, Group g "
            + "where s.documentId = :documentId and s.groupId = g.id and g.deletedAt is not null")
    List<UUID> findDeletedGroupIdsForDocument(@Param("documentId") UUID documentId);

    /**
     * §3.1/§3.2 ({@code activeGroupShareCount} on {@code GET /trash/{id}}
     * and {@code POST /documents/trash-preview}): how many *active* (not
     * soft-deleted) groups a document is currently shared to - deliberately
     * different from {@link #countByDocumentId}, which counts every share
     * row regardless of the group's own lifecycle state.
     */
    @Query("select count(s) from DocumentGroupShare s, Group g "
            + "where s.documentId = :documentId and s.groupId = g.id and g.deletedAt is null")
    long countActiveSharesForDocument(@Param("documentId") UUID documentId);

    /** Batch form of {@link #countActiveSharesForDocument}, for {@code POST /documents/trash-preview}. */
    @Query("select s.documentId, count(s) from DocumentGroupShare s, Group g "
            + "where s.documentId in :documentIds and s.groupId = g.id and g.deletedAt is null "
            + "group by s.documentId")
    List<Object[]> countActiveSharesForDocuments(@Param("documentIds") List<UUID> documentIds);
}
