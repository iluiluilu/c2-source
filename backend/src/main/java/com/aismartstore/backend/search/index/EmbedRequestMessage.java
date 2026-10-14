package com.aismartstore.backend.search.index;

import java.util.List;
import java.util.UUID;

/**
 * SPEC-search-semantic §2.2: one {@code embed.requested} job on {@code q.embed}.
 *
 * @param segmentIds {@code content_segment} UUIDs plus the synthetic {@code <documentId>:title|note}
 *                   ids (hence strings); {@code null} = the whole document (restore).
 * @param reason     {@code confirmed|ingested|note|restored|reconcile}
 */
public record EmbedRequestMessage(
        UUID eventId, UUID documentId, List<String> segmentIds, String reason, long outboxVersion) {

    public static final String REASON_CONFIRMED = "confirmed";
    public static final String REASON_INGESTED = "ingested";
    public static final String REASON_NOTE = "note";
    public static final String REASON_RESTORED = "restored";
    public static final String REASON_RECONCILE = "reconcile";
}
