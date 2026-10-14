package com.aismartstore.backend.search.index;

import com.aismartstore.backend.digitization.results.ContentSegmentRepository;
import com.aismartstore.backend.documents.Document;
import com.aismartstore.backend.documents.DocumentRepository;
import com.aismartstore.backend.documents.DocumentStatus;
import com.aismartstore.backend.groups.DocumentGroupShare;
import com.aismartstore.backend.groups.DocumentGroupShareRepository;
import com.aismartstore.backend.notes.DocumentNoteRepository;
import com.aismartstore.backend.search.MediaTypeResolver;
import com.aismartstore.backend.tags.DocumentTagRepository;
import com.aismartstore.backend.tags.Tag;
import com.aismartstore.backend.tags.TagKinds;
import com.aismartstore.backend.warehouse.WarehouseFolderRepository;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * search-fulltext-04-dong-bo-chi-muc.md §4.2 - re-reads the current-state
 * Postgres metadata that every {@code segment}/{@code document} write shares
 * (doc_type, topic_tags, folder_id/folder_path_ids, uploaded_at, media_type,
 * owner_user_id, scope_group_ids). Used by every indexer event handler so
 * none of them re-implements this join separately - "đọc lại hiện hành" is
 * always this exact query, never a partial/cached copy.
 *
 * <p>bugs/bug-002-search-index-version-conflict.md - also computes {@link
 * Fields#contentVersion()}, the OpenSearch external-version value every
 * indexer write now uses instead of {@code outbox_event.id} (see that file's
 * mục 5.2 for why {@code outbox_event.id} is unsafe: it reflects queue
 * processing order, not actual data freshness).
 */
@Component
public class DocumentIndexFieldsBuilder {

    private final DocumentRepository documentRepository;
    private final DocumentTagRepository documentTagRepository;
    private final DocumentGroupShareRepository documentGroupShareRepository;
    private final DocumentNoteRepository documentNoteRepository;
    private final ContentSegmentRepository contentSegmentRepository;
    private final WarehouseFolderRepository warehouseFolderRepository;

    public DocumentIndexFieldsBuilder(
            DocumentRepository documentRepository,
            DocumentTagRepository documentTagRepository,
            DocumentGroupShareRepository documentGroupShareRepository,
            DocumentNoteRepository documentNoteRepository,
            ContentSegmentRepository contentSegmentRepository,
            WarehouseFolderRepository warehouseFolderRepository) {
        this.documentRepository = documentRepository;
        this.documentTagRepository = documentTagRepository;
        this.documentGroupShareRepository = documentGroupShareRepository;
        this.documentNoteRepository = documentNoteRepository;
        this.contentSegmentRepository = contentSegmentRepository;
        this.warehouseFolderRepository = warehouseFolderRepository;
    }

    /**
     * @param documentId     mirrors {@code document_id}
     * @param documentName   {@code document.original_name} - {@code document}
     *                       index's {@code document_name} field only
     * @param displayName    {@code document.display_name} - stored only ({@code index:false}); search still
     *                       matches {@code document_name} = original_name
     * @param docType        the single {@code doc_type} tag label, or
     *                       {@code null} if not yet classified (US2 AC-5)
     * @param topicTags      every {@code topic} tag label currently assigned
     * @param folderId       {@code document.folder_id}, as a string, or
     *                       {@code null} if the document has no folder
     *                       (nullable by design, V9 migration - a deleted
     *                       folder detaches its documents rather than
     *                       cascading)
     * @param folderPathIds  ancestor folder ids (root first) plus {@code
     *                       folderId} itself - search-fulltext-01 §2.1;
     *                       empty when {@code folderId} is {@code null}
     * @param uploadedAt     {@code document.created_at} ("ngày nạp kho")
     * @param mediaType      {@link MediaTypeResolver#resolve}
     * @param ownerUserId    {@code document.uploader_sub}
     * @param scopeGroupIds  every group id the document is currently shared to
     * @param contentVersion bugs/bug-002-search-index-version-conflict.md
     *                       mục 5.2 - epoch-microseconds of the GREATEST of
     *                       every timestamp that can affect a field this
     *                       class reads: {@code document.created_at} (floor,
     *                       always present), {@code MAX(document_tag.assigned_at)},
     *                       {@code MAX(document_group_share.shared_at)},
     *                       {@code MAX(document_note.updated_at)}, {@code
     *                       MAX(content_segment.updated_at)}. Recomputed on
     *                       every {@link #build} call (never cached, never
     *                       taken from the triggering {@code outbox_event}),
     *                       so a retry of the same event after a partial
     *                       failure yields the same value (safe with {@code
     *                       ExternalGte}) and a genuinely newer write - from
     *                       any handler, processed in any order - always
     *                       carries a strictly greater value than a genuinely
     *                       older one. See the bug file for why {@code
     *                       outbox_event.id} could not provide that guarantee.
     */
    public record Fields(
            UUID documentId,
            String documentName,
            String docType,
            List<String> topicTags,
            String folderId,
            List<String> folderPathIds,
            OffsetDateTime uploadedAt,
            String mediaType,
            String ownerUserId,
            List<String> scopeGroupIds,
            long contentVersion,
            String displayName) {
    }

    /**
     * Empty if the document no longer exists, is soft-deleted, or is not {@code clean} (a rejected/infected FR-12
     * evidence row must never enter the index, even when a tag/note/share event fires for it - feedbacks S-01).
     */
    public Optional<Fields> build(UUID documentId) {
        Document document = documentRepository.findById(documentId).orElse(null);
        if (document == null || document.getDeletedAt() != null || !DocumentStatus.CLEAN.equals(document.getStatus())) {
            return Optional.empty();
        }

        List<Tag> tags = documentTagRepository.findTagsForDocument(documentId);
        String docType = tags.stream()
                .filter(t -> TagKinds.DOC_TYPE.equals(t.getKind()))
                .map(Tag::getLabel)
                .findFirst()
                .orElse(null);
        List<String> topicTags = tags.stream()
                .filter(t -> TagKinds.TOPIC.equals(t.getKind()))
                .map(Tag::getLabel)
                .toList();

        // Bug fix 2026-09-09 (checklist muc 8.16): document.folder_id is
        // nullable by design (V9 migration - FK is ON DELETE SET NULL when a
        // folder is deleted), but this used to call
        // warehouseFolderRepository.findById(null) unconditionally, which
        // throws InvalidDataAccessApiUsageException. Worse, that exception
        // propagates through findById()'s own @Transactional(readOnly=true)
        // advice while participating in SearchIndexOutboxPoller's outer
        // transaction, which marks that whole transaction rollback-only -
        // silently breaking the caller's own attempts/last_error bookkeeping
        // and leaving the outbox_event stuck retrying forever (see
        // SearchIndexOutboxPoller's own fix for the retry-tracking half of
        // this). A document with no folder simply has no folderPathIds.
        UUID folderId = document.getFolderId();
        List<String> folderPathIds = new ArrayList<>();
        if (folderId != null) {
            warehouseFolderRepository.findById(folderId).ifPresent(folder -> {
                for (UUID ancestorId : folder.getPathIds()) {
                    folderPathIds.add(ancestorId.toString());
                }
            });
            folderPathIds.add(folderId.toString());
        }

        List<String> scopeGroupIds = documentGroupShareRepository
                .findByDocumentIdOrderBySharedAtAsc(documentId)
                .stream()
                .map(DocumentGroupShare::getGroupId)
                .map(UUID::toString)
                .distinct()
                .toList();

        long contentVersion = computeContentVersion(documentId, document.getCreatedAt());

        return Optional.of(new Fields(
                documentId,
                document.getOriginalName(),
                docType,
                topicTags,
                folderId == null ? null : folderId.toString(),
                folderPathIds,
                document.getCreatedAt(),
                MediaTypeResolver.resolve(document.getMimeType()),
                document.getUploaderSub(),
                scopeGroupIds,
                contentVersion,
                document.getDisplayName()));
    }

    /**
     * bugs/bug-002-search-index-version-conflict.md mục 5.2 - GREATEST of
     * every timestamp source that can affect a field {@link #build} reads,
     * in epoch-microseconds. {@code document.createdAt} is the floor (always
     * present, so the result is never null even for a brand-new document
     * with no tag/share/note/segment yet).
     */
    private long computeContentVersion(UUID documentId, OffsetDateTime documentCreatedAt) {
        OffsetDateTime latest = documentCreatedAt;
        latest = laterOf(latest, documentTagRepository.findMaxAssignedAtByDocumentId(documentId).orElse(null));
        latest = laterOf(
                latest, documentGroupShareRepository.findMaxSharedAtByDocumentId(documentId).orElse(null));
        latest = laterOf(latest, documentNoteRepository.findMaxUpdatedAtByDocumentId(documentId).orElse(null));
        latest = laterOf(
                latest, contentSegmentRepository.findMaxUpdatedAtByDocumentId(documentId).orElse(null));
        return toEpochMicros(latest);
    }

    private static OffsetDateTime laterOf(OffsetDateTime a, OffsetDateTime b) {
        if (b == null) {
            return a;
        }
        return b.isAfter(a) ? b : a;
    }

    private static long toEpochMicros(OffsetDateTime value) {
        java.time.Instant instant = value.toInstant();
        return instant.getEpochSecond() * 1_000_000L + instant.getNano() / 1_000L;
    }
}
