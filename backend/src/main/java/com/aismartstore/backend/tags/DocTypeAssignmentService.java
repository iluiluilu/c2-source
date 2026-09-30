package com.aismartstore.backend.tags;

import com.aismartstore.backend.documents.Document;
import com.aismartstore.backend.documents.DocumentRepository;
import com.aismartstore.backend.outbox.OutboxEventRepository;
import com.aismartstore.backend.scanning.AllowedFileFormat;
import com.aismartstore.backend.scanning.AllowedFileFormatRepository;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * classification-notes-01-tags.md §4.3/§4.4 (Task 6/7): auto-assigns
 * exactly one {@code doc_type} tag per document from its MIME type, reusing
 * {@link AllowedFileFormat#getCategory()} (already populated for every MIME
 * in the ingest allowlist, FR-12) instead of a separate mapping table
 * (Câu hỏi mở #2, mục 8 - đã CHỐT).
 *
 * <p>The 4 possible {@code doc_type} labels are seeded once by
 * {@code V20__classification_tags.sql} - this service only looks them up
 * ({@link TagRepository#findByKindAndLabelIgnoreCase}), it never creates a
 * new one, so an unmapped MIME (not in the allowlist, or an old/unknown
 * value from before the allowlist existed) is left with no {@code doc_type}
 * link rather than guessing (US2 AC-5) - such documents surface via {@link
 * com.aismartstore.backend.documents.DocumentSpecifications#missingDocType()}
 * / {@link DocumentTagRepository#findDocumentIdsMissingDocType}, one shared
 * query for both the live ingest path and the one-off backfill (§4.4 step 2:
 * "cùng cơ chế hiển thị... không tạo 2 luồng riêng").
 */
@Service
public class DocTypeAssignmentService {

    /** classification-notes-01-tags.md §8 Câu hỏi mở #2: 4 nhãn cố định, seed ở V20. */
    private static final Map<String, String> CATEGORY_TO_LABEL = Map.of(
            "TEXT_DOC", "văn bản",
            "IMAGE", "hình ảnh",
            "AUDIO", "âm thanh",
            "VIDEO", "video");

    /** Backfill/self-assign actor recorded when there is no end-user request context (Task 7 runner). */
    private static final String SYSTEM_SUB = "system";
    private static final String SYSTEM_USERNAME = "system";

    private final AllowedFileFormatRepository allowedFileFormatRepository;
    private final TagRepository tagRepository;
    private final DocumentTagRepository documentTagRepository;
    private final DocumentRepository documentRepository;
    private final OutboxEventRepository outboxEventRepository;

    public DocTypeAssignmentService(
            AllowedFileFormatRepository allowedFileFormatRepository,
            TagRepository tagRepository,
            DocumentTagRepository documentTagRepository,
            DocumentRepository documentRepository,
            OutboxEventRepository outboxEventRepository) {
        this.allowedFileFormatRepository = allowedFileFormatRepository;
        this.tagRepository = tagRepository;
        this.documentTagRepository = documentTagRepository;
        this.documentRepository = documentRepository;
        this.outboxEventRepository = outboxEventRepository;
    }

    /**
     * §4.3 steps 1-3: called right after a document is persisted during
     * ingest ({@code DocumentsService.ingestBatchFile}). Deliberately does
     * NOT go through {@link com.aismartstore.backend.groups.DocumentScopeGuard}
     * - this is internal system context triggered by the upload itself, not
     * an end-user request against an arbitrary document id (§4.3 step 2).
     *
     * @return true if a doc_type tag was assigned, false if the MIME type
     *     could not be mapped (document is left for manual classification).
     */
    @Transactional
    public boolean assignOnIngest(Document document, String actorSub, String actorUsername) {
        Optional<Tag> tag = resolveDocTypeTag(document.getMimeType());
        if (tag.isEmpty()) {
            return false;
        }
        documentTagRepository.insert(document.getId(), tag.get().getId(), TagKinds.DOC_TYPE, actorSub, actorUsername);
        ClassificationOutbox.write(outboxEventRepository, document.getId(), actorSub);
        return true;
    }

    /** §4.3 step 2: MIME -> doc_type tag, or empty if unmapped (US2 AC-5). */
    public Optional<Tag> resolveDocTypeTag(String mimeType) {
        return allowedFileFormatRepository
                .findById(mimeType)
                .map(AllowedFileFormat::getCategory)
                .map(CATEGORY_TO_LABEL::get)
                .flatMap(label -> tagRepository.findByKindAndLabelIgnoreCase(TagKinds.DOC_TYPE, label));
    }

    /** One page of the §4.4 backfill (Task 7). Idempotent - safe to call repeatedly until it reports 0 scanned. */
    public record BackfillOutcome(int scanned, int assigned, int stillUnmapped) {
    }

    @Transactional
    public BackfillOutcome backfillPage(int batchSize) {
        var documentIds = documentTagRepository.findDocumentIdsMissingDocType(batchSize);
        int assigned = 0;
        int unmapped = 0;
        for (UUID documentId : documentIds) {
            Optional<Document> document = documentRepository.findById(documentId);
            if (document.isEmpty()) {
                continue;
            }
            boolean ok = assignOnIngest(document.get(), SYSTEM_SUB, SYSTEM_USERNAME);
            if (ok) {
                assigned++;
            } else {
                unmapped++;
            }
        }
        return new BackfillOutcome(documentIds.size(), assigned, unmapped);
    }
}
