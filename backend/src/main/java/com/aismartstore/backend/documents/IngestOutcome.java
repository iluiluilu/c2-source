package com.aismartstore.backend.documents;

import java.util.List;
import java.util.UUID;

/**
 * Result of {@link DocumentsService#ingestBatchFile} (data-ingestion.md §3):
 * either the file made it into the warehouse ({@link Ingested}, HTTP 201) or
 * it was recognized as already present and skipped without touching the
 * pipeline ({@link Skipped}, HTTP 200) - see §4.2 step 3.
 */
public sealed interface IngestOutcome {

    record Ingested(Document document, List<Document> duplicateOf) implements IngestOutcome {
    }

    record Skipped(String reason, UUID documentId) implements IngestOutcome {
    }
}
