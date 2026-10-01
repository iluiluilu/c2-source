package com.aismartstore.backend.documents;

import java.util.List;

/**
 * Result of {@link DocumentsService#ingestBatchFile} (data-ingestion.md §3):
 * the file made it into the warehouse ({@link Ingested}, HTTP 201). The old
 * {@code Skipped ALREADY_EXISTS} outcome was removed by
 * SPEC-folder-upload-display-name (re-uploading a name creates a new copy).
 */
public sealed interface IngestOutcome {

    record Ingested(Document document, List<Document> duplicateOf) implements IngestOutcome {
    }

}
