package com.aismartstore.backend.groups;

import com.aismartstore.backend.outbox.OutboxEvent;
import com.aismartstore.backend.outbox.OutboxEventRepository;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * search-fulltext-04-dong-bo-chi-muc.md Task 4 - shared helper for writing an
 * {@code outbox_event} row whenever a document is shared/unshared to a group,
 * so {@code search-service}'s indexer can keep {@code scope_group_ids} in
 * sync. Same shape/rationale as {@code
 * com.aismartstore.backend.tags.ClassificationOutbox} (plain static helper,
 * not a {@code @Service}, so {@link GroupsService}'s own {@code
 * @Transactional} methods can call it inline without an extra Spring proxy
 * hop) - copied deliberately rather than reused across packages/module
 * boundaries.
 */
final class AclChangeOutbox {

    static final String EVENT_TYPE = "document.acl.changed";

    static final String ACTION_SHARED = "shared";
    static final String ACTION_UNSHARED = "unshared";

    private static final Logger log = LoggerFactory.getLogger(AclChangeOutbox.class);

    private AclChangeOutbox() {
    }

    static void write(
            OutboxEventRepository outboxEventRepository, UUID documentId, UUID groupId, String action, String changedBySub) {
        OutboxEvent event = new OutboxEvent();
        event.setEventType(EVENT_TYPE);
        OffsetDateTime changedAt = OffsetDateTime.now();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("documentId", documentId.toString());
        payload.put("groupId", groupId.toString());
        payload.put("action", action);
        payload.put("changedAt", changedAt.toString());
        payload.put("changedBySub", changedBySub);
        event.setPayload(payload);
        event.setCreatedAt(changedAt);
        try {
            outboxEventRepository.save(event);
        } catch (RuntimeException e) {
            log.error("Failed to write document.acl.changed outbox_event for document {} / group {}", documentId, groupId, e);
            throw e;
        }
    }
}
