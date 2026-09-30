package com.aismartstore.backend.notes;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DocumentNoteRepository extends JpaRepository<DocumentNote, UUID> {

    List<DocumentNote> findByDocumentIdOrderByCreatedAtDesc(UUID documentId);

    Optional<DocumentNote> findByIdAndDocumentId(UUID id, UUID documentId);

    /** delete-restore-trash-02-xoa-cung-xung-dot.md §4.2 bước 2 ({@code TrashPurgeService}): see DocumentTagRepository#deleteByDocumentId's javadoc for why this is explicit. */
    @Modifying
    int deleteByDocumentId(UUID documentId);

    /**
     * bugs/bug-002-search-index-version-conflict.md muc 5.2 - 1 trong 5
     * nguon "do moi du lieu" dung de tinh {@code contentVersion} cho OpenSearch
     * external versioning (thay the {@code outbox_event.id} - xem
     * DocumentIndexFieldsBuilder#build).
     */
    @Query("select max(n.updatedAt) from DocumentNote n where n.documentId = :documentId")
    Optional<OffsetDateTime> findMaxUpdatedAtByDocumentId(@Param("documentId") UUID documentId);
}
