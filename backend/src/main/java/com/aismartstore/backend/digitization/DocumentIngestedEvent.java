package com.aismartstore.backend.digitization;

import java.util.UUID;

/**
 * digitization-pipeline-01-source-dispatch.md §4.1 step 2: published by
 * {@code DocumentsService.ingestBatchFile} right before it returns, once
 * {@code document.status='clean'} has been persisted. {@code folderId} is
 * carried for parity with the spec's event shape even though the current
 * {@link DigitizationDispatchListener} only needs {@code documentId}/{@code
 * mimeType} - kept so a future consumer of this same event does not need a
 * signature change.
 */
public record DocumentIngestedEvent(UUID documentId, UUID folderId, String mimeType) {
}
