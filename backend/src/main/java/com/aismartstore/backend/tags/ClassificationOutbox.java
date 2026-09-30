package com.aismartstore.backend.tags;

import com.aismartstore.backend.outbox.OutboxEvent;
import com.aismartstore.backend.outbox.OutboxEventRepository;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * classification-notes-01-tags.md §4.6: shared helper for writing an
 * {@code outbox_event} row when a document's classification changes (used
 * by both {@link TagsService} - assign/unassign/ad-hoc topic - and {@link
 * DocTypeAssignmentService} - auto-assign at ingest/backfill). Deliberately
 * a plain static helper (not a {@code @Service}) so both callers can invoke
 * it from inside their own {@code @Transactional} method without an extra
 * Spring proxy hop.
 */
final class ClassificationOutbox {

    static final String EVENT_TYPE = "document.classification.changed";

    private static final Logger log = LoggerFactory.getLogger(ClassificationOutbox.class);

    private ClassificationOutbox() {
    }

    static void write(OutboxEventRepository outboxEventRepository, UUID documentId, String changedBySub) {
        OutboxEvent event = new OutboxEvent();
        event.setEventType(EVENT_TYPE);
        OffsetDateTime changedAt = OffsetDateTime.now();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("documentId", documentId.toString());
        payload.put("changedAt", changedAt.toString());
        payload.put("changedBySub", changedBySub);
        event.setPayload(payload);
        event.setCreatedAt(changedAt);
        try {
            outboxEventRepository.save(event);
        } catch (RuntimeException e) {
            log.error("Failed to write outbox_event for document {}", documentId, e);
            throw e;
        }
    }
}
