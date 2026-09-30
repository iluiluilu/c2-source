package com.aismartstore.backend.search;

/**
 * search-fulltext-01-tim-kiem-co-ban.md §2.1 - the {@code media_type}
 * OpenSearch facet is derived from {@code document.mime_type} at index time
 * (there is no {@code media_type} column on {@link
 * com.aismartstore.backend.documents.Document}). Mirrors the mime-type
 * routing {@code DigitizationIntakeService}/{@code DigitizationDispatchService}
 * already use (audio/image+pdf/video), so the same document is always
 * classified the same way across both features.
 */
public final class MediaTypeResolver {

    public static final String DOCUMENT = "document";
    public static final String AUDIO = "audio";
    public static final String VIDEO = "video";

    private static final String PDF_MIME_TYPE = "application/pdf";

    private MediaTypeResolver() {
    }

    /** Never null - an unrecognized mime type falls back to {@link #DOCUMENT} (conservative default). */
    public static String resolve(String mimeType) {
        if (mimeType == null) {
            return DOCUMENT;
        }
        if (mimeType.startsWith("audio/")) {
            return AUDIO;
        }
        if (mimeType.startsWith("video/")) {
            return VIDEO;
        }
        return DOCUMENT;
    }

    /** Exposed for callers that already special-cased PDF (kept explicit for readability at call sites). */
    public static boolean isPdf(String mimeType) {
        return PDF_MIME_TYPE.equals(mimeType);
    }
}
