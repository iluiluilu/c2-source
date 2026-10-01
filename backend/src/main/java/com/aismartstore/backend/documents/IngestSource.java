package com.aismartstore.backend.documents;

import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;

/**
 * personal-to-warehouse.md §1: input of {@link DocumentsService#ingestCore} -
 * decouples the ingest pipeline from {@code MultipartFile} so a stored
 * personal file can be streamed in server-side.
 *
 * @param expectedSha256       when non-null the streamed bytes must hash to this value (AC-13)
 * @param displayName          client-chosen display name (validated in ingestCore); null = missing -> 422
 * @param sourcePersonalFileId when non-null the document is marked as coming from personal storage
 */
public record IngestSource(
        String originalName,
        String mimeType,
        long size,
        StreamOpener open,
        String relativePath,
        String expectedSha256,
        UUID sourcePersonalFileId,
        String displayName) {

    /** Like {@code Supplier<InputStream>} but allowed to throw {@link IOException}. */
    @FunctionalInterface
    public interface StreamOpener {
        InputStream open() throws IOException;
    }
}
