package com.aismartstore.backend.digitization.results;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * digitization-results-store.md §3: {@code segment_revision} is append-only
 * (US2 AC-4) during a document's normal lifecycle - deliberately no
 * update/delete method for that path beyond the standard {@link
 * JpaRepository#save} used for inserting a brand new revision row (never to
 * modify an existing one, see {@link
 * DigitizationRunLifecycleServiceImpl#reviseSegment}). {@link
 * #deleteByContentSegmentDocumentId} is the sole exception, only for hard
 * delete (bugs/bug-004-trash-purge-missing-child-table-cleanup.md).
 */
public interface SegmentRevisionRepository extends JpaRepository<SegmentRevision, UUID> {

    @Query("SELECT sr FROM SegmentRevision sr WHERE sr.segmentId = :segmentId AND sr.revisionNo = "
            + "(SELECT MAX(sr2.revisionNo) FROM SegmentRevision sr2 WHERE sr2.segmentId = :segmentId)")
    Optional<SegmentRevision> findLatestBySegmentId(@Param("segmentId") UUID segmentId);

    /**
     * bugs/bug-004-trash-purge-missing-child-table-cleanup.md -
     * {@code TrashPurgeService#purgeOne} bước 2: phải xoá {@code
     * segment_revision} trước {@code content_segment} (FK {@code
     * segment_revision_segment_id_fkey}, {@code ON DELETE NO ACTION}).
     * {@code segment_id} has no {@code document_id} column of its own, hence
     * the subquery through {@link ContentSegment}.
     */
    @Modifying
    @Query("DELETE FROM SegmentRevision sr WHERE sr.segmentId IN "
            + "(SELECT cs.id FROM ContentSegment cs WHERE cs.documentId = :documentId)")
    int deleteByContentSegmentDocumentId(@Param("documentId") UUID documentId);
}
