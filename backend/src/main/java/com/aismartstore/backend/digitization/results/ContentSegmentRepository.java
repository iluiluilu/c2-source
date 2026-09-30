package com.aismartstore.backend.digitization.results;

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

public interface ContentSegmentRepository extends JpaRepository<ContentSegment, UUID> {

    /** digitization-results-store.md §3: segments of one run, in original order. Backed by {@code IDX_content_segment_run}. */
    List<ContentSegment> findByRunIdOrderBySegmentIndex(UUID runId);

    /**
     * bugs/bug-004-trash-purge-missing-child-table-cleanup.md -
     * {@code TrashPurgeService#purgeOne} bước 2: phải xoá {@code
     * content_segment} trước {@code digitization_run} (FK {@code
     * content_segment_run_id_fkey}, {@code ON DELETE NO ACTION}) và trước
     * {@code segment_revision} của các segment này đã bị xoá riêng.
     */
    @Modifying
    int deleteByDocumentId(UUID documentId);

    /** digitization-review-01-ocr.md §3.2 ({@code RunDetail.segments}, `GET /runs/{runId}`) - same order, paginated. */
    Page<ContentSegment> findByRunIdOrderBySegmentIndex(UUID runId, Pageable pageable);

    /** digitization-results-store.md §3: e.g. all {@code sed}/{@code summary} segments of a document. */
    List<ContentSegment> findByDocumentIdAndContentSource(UUID documentId, String contentSource);

    /** digitization-review-01-ocr.md §3.4 ({@code confirmRun}, US4): count of segments touched by a human edit since AI wrote them. */
    long countByRunIdAndEditedTrue(UUID runId);

    /**
     * search-fulltext-04-dong-bo-chi-muc.md §4.3 - {@code
     * SearchIndexReconciliationJob}'s expected count for one run: only
     * non-soft-deleted segments are ever indexed (digitization-review-01-ocr.md
     * §2, V28).
     */
    long countByRunIdAndDeletedFalse(UUID runId);

    /**
     * bugs/bug-002-search-index-version-conflict.md muc 5.2 - 1 trong 5
     * nguon "do moi du lieu" dung de tinh {@code contentVersion} cho OpenSearch
     * external versioning (thay the {@code outbox_event.id} - xem
     * DocumentIndexFieldsBuilder#build). Bao gom ca segment da is_deleted vi
     * viec xoa mot doan cung la 1 thay doi noi dung can phan anh dung do moi.
     */
    @Query("select max(cs.updatedAt) from ContentSegment cs where cs.documentId = :documentId")
    Optional<OffsetDateTime> findMaxUpdatedAtByDocumentId(@Param("documentId") UUID documentId);
}
