package com.aismartstore.backend.search.index;

import com.aismartstore.backend.config.EmbeddingProperties;
import com.aismartstore.backend.digitization.results.ContentSegment;
import com.aismartstore.backend.digitization.results.ContentSegmentRepository;
import com.aismartstore.backend.digitization.results.DigitizationRun;
import com.aismartstore.backend.digitization.results.DigitizationRunRepository;
import com.aismartstore.backend.notes.DocumentNote;
import com.aismartstore.backend.notes.DocumentNoteRepository;
import com.aismartstore.backend.documents.Document;
import com.aismartstore.backend.documents.DocumentRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * SPEC-search-semantic T8: reads the current state from PostgreSQL and turns an {@link
 * EmbedRequestMessage} into the texts to embed (the message carries no ACL and no text - the
 * consumer never trusts it for anything but ids). A document that is gone / soft-deleted yields
 * nothing; only segments of a {@code confirmed} run count (same set as the lexical indexer).
 */
@Component
public class EmbedSegmentLoader {

    private final DocumentRepository documentRepository;
    private final ContentSegmentRepository contentSegmentRepository;
    private final DigitizationRunRepository digitizationRunRepository;
    private final DocumentNoteRepository documentNoteRepository;
    private final EmbeddingProperties embeddingProperties;

    public EmbedSegmentLoader(
            DocumentRepository documentRepository,
            ContentSegmentRepository contentSegmentRepository,
            DigitizationRunRepository digitizationRunRepository,
            DocumentNoteRepository documentNoteRepository,
            EmbeddingProperties embeddingProperties) {
        this.documentRepository = documentRepository;
        this.contentSegmentRepository = contentSegmentRepository;
        this.digitizationRunRepository = digitizationRunRepository;
        this.documentNoteRepository = documentNoteRepository;
        this.embeddingProperties = embeddingProperties;
    }

    public List<EmbedTextBuilder.EmbedItem> load(EmbedRequestMessage message) {
        Document document = documentRepository.findById(message.documentId()).orElse(null);
        if (document == null || document.getDeletedAt() != null) {
            return List.of();
        }
        UUID documentId = document.getId();
        boolean whole = message.segmentIds() == null;
        Set<String> targets = whole ? null : new LinkedHashSet<>(message.segmentIds());

        Map<String, EmbedTextBuilder.EmbedItem> items = new LinkedHashMap<>();

        if (whole || targets.contains(documentId + ":title")) {
            addIfPresent(items, documentId + ":title", document.getOriginalName());
        }
        if (whole || targets.contains(documentId + ":note")) {
            addIfPresent(items, documentId + ":note", mergedNotes(documentId));
        }

        // Content segments: group by run so neighbours are resolved within the run.
        Set<UUID> runIds = new LinkedHashSet<>();
        Set<String> contentTargets = new LinkedHashSet<>();
        if (whole) {
            for (DigitizationRun run : digitizationRunRepository.findByDocumentIdAndReviewState(
                    documentId, DigitizationRun.REVIEW_CONFIRMED)) {
                runIds.add(run.getId());
            }
        } else {
            List<UUID> segmentUuids = new ArrayList<>();
            for (String id : targets) {
                UUID parsed = tryParse(id);
                if (parsed != null) {
                    segmentUuids.add(parsed);
                    contentTargets.add(id);
                }
            }
            for (ContentSegment segment : contentSegmentRepository.findAllById(segmentUuids)) {
                if (documentId.equals(segment.getDocumentId())) {
                    runIds.add(segment.getRunId());
                }
            }
        }

        for (UUID runId : runIds) {
            DigitizationRun run = digitizationRunRepository.findById(runId).orElse(null);
            if (run == null || !DigitizationRun.REVIEW_CONFIRMED.equals(run.getReviewState())) {
                continue; // superseded / unconfirmed runs are not in the lexical index either
            }
            List<EmbedTextBuilder.Seg> segs = new ArrayList<>();
            Set<String> runTargets = new LinkedHashSet<>();
            for (ContentSegment segment : contentSegmentRepository.findByRunIdOrderBySegmentIndex(runId)) {
                if (segment.isDeleted()) {
                    continue;
                }
                String id = segment.getId().toString();
                boolean neighbours = ContentSegment.SOURCE_OCR.equals(segment.getContentSource())
                        || ContentSegment.SOURCE_STT.equals(segment.getContentSource());
                segs.add(new EmbedTextBuilder.Seg(
                        id, segment.getContentSource(), segment.getPageNo(), segment.getCurrentText(), neighbours));
                if (whole || contentTargets.contains(id)) {
                    runTargets.add(id);
                }
            }
            for (EmbedTextBuilder.EmbedItem item : EmbedTextBuilder.build(
                    segs, runTargets, embeddingProperties.chunkSizeTokens(), embeddingProperties.chunkOverlapTokens())) {
                items.putIfAbsent(item.segmentId(), item);
            }
        }
        return new ArrayList<>(items.values());
    }

    private static void addIfPresent(Map<String, EmbedTextBuilder.EmbedItem> items, String id, String text) {
        if (text != null && !text.isBlank()) {
            items.put(id, new EmbedTextBuilder.EmbedItem(id, text));
        }
    }

    private String mergedNotes(UUID documentId) {
        List<DocumentNote> notes = new ArrayList<>(documentNoteRepository.findByDocumentIdOrderByCreatedAtDesc(documentId));
        if (notes.isEmpty()) {
            return null;
        }
        java.util.Collections.reverse(notes);
        StringBuilder sb = new StringBuilder();
        for (DocumentNote note : notes) {
            if (sb.length() > 0) {
                sb.append("\n\n");
            }
            sb.append(note.getBody());
        }
        return sb.toString();
    }

    private static UUID tryParse(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
