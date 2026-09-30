package com.aismartstore.backend.notifications;

import com.aismartstore.backend.auth.KeycloakUser;
import com.aismartstore.backend.common.NotFoundException;
import com.aismartstore.backend.groups.GroupMember;
import com.aismartstore.backend.groups.GroupMemberRepository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * group-management-sharing-02-document-sharing.md §4.4/§4.5 (Story 4): a
 * plain in-app notification inbox. Every write entrypoint here is {@code
 * @Async} and best-effort (§4.4 point 2/§7 rủi ro 3) - a failure here must
 * never affect the business operation that triggered it, so every public
 * write method catches {@code RuntimeException} itself instead of letting it
 * propagate, and is called directly (not wrapped in the caller's own
 * transaction) the same way {@code AuditService#recordAsync} already is.
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private static final int LIST_LIMIT = 100;
    private static final long RETENTION_DAYS = 90;

    private final NotificationRepository notificationRepository;
    private final GroupMemberRepository groupMemberRepository;

    public NotificationService(NotificationRepository notificationRepository, GroupMemberRepository groupMemberRepository) {
        this.notificationRepository = notificationRepository;
        this.groupMemberRepository = groupMemberRepository;
    }

    /** GET /notifications response row (§3.4). */
    public record NotificationView(
            UUID id, String type, Map<String, Object> payload, OffsetDateTime createdAt, OffsetDateTime readAt) {
    }

    /** §4.5 step 1: newest first, capped at {@value #LIST_LIMIT} (§3.4). */
    public List<NotificationView> list(KeycloakUser user, boolean unreadOnly) {
        List<Notification> rows = unreadOnly
                ? notificationRepository.findTop100ByRecipientSubAndReadAtIsNullOrderByCreatedAtDesc(user.sub())
                : notificationRepository.findTop100ByRecipientSubOrderByCreatedAtDesc(user.sub());
        return rows.stream()
                .map(n -> new NotificationView(n.getId(), n.getType(), n.getPayload(), n.getCreatedAt(), n.getReadAt()))
                .toList();
    }

    /**
     * §4.5 step 2: {@code 404} if {@code id} does not exist or does not
     * belong to the caller (never distinguished - same rationale as {@code
     * DocumentScopeGuard#requireVisible}: do not leak whether some other
     * user's notification exists). Idempotent: reading an already-read
     * notification again still returns {@code 204}.
     */
    @Transactional
    public void markRead(KeycloakUser user, UUID id) {
        if (!notificationRepository.existsByIdAndRecipientSub(id, user.sub())) {
            throw new NotFoundException("Notification not found");
        }
        notificationRepository.markRead(id, user.sub(), OffsetDateTime.now());
    }

    /**
     * §4.4 point 1/4: generic single-payload fan-out, used directly by
     * {@code GroupsService.addMember}/{@code removeMember}/{@code
     * leaveGroup} for {@code group.member_added}/{@code group.member_removed}
     * (recipient = the member added/removed).
     */
    @Async
    public void notifyAsync(List<String> recipientSubs, String type, Map<String, Object> payload) {
        try {
            insertBatch(recipientSubs, type, payload);
        } catch (RuntimeException e) {
            log.error("Failed to write notification(s) type={} recipients={}", type, recipientSubs, e);
        }
    }

    /**
     * §4.1 step 3/§4.4 point 3 (Story 1 AC-Story 4 AC-3): exactly one {@code
     * document.shared_to_group} notification per recipient per bulk-share
     * request, regardless of how many documents were shared in it.
     * Recipients are every current member of {@code groupId} except {@code
     * actorSub}, resolved here (not by the caller) so {@code GroupsService}
     * never has to know how notification fan-out works.
     */
    @Async
    public void notifyDocumentsSharedToGroup(UUID groupId, List<UUID> documentIds, String actorSub) {
        try {
            if (documentIds.isEmpty()) {
                return;
            }
            List<String> recipients = groupMemberRepository.findByGroupIdOrderByJoinedAtAsc(groupId).stream()
                    .map(GroupMember::getUserSub)
                    .filter(sub -> !sub.equals(actorSub))
                    .distinct()
                    .toList();
            if (recipients.isEmpty()) {
                return;
            }
            Map<String, Object> payload = Map.of(
                    "groupId", groupId.toString(),
                    "documentIds", documentIds.stream().map(UUID::toString).toList(),
                    "sharedBySub", actorSub);
            insertBatch(recipients, "document.shared_to_group", payload);
        } catch (RuntimeException e) {
            log.error("Failed to write document.shared_to_group notifications for group={}", groupId, e);
        }
    }

    private void insertBatch(List<String> recipientSubs, String type, Map<String, Object> payload) {
        OffsetDateTime now = OffsetDateTime.now();
        List<Notification> rows = recipientSubs.stream()
                .map(sub -> {
                    Notification n = new Notification();
                    n.setRecipientSub(sub);
                    n.setType(type);
                    n.setPayload(payload);
                    n.setCreatedAt(now);
                    return n;
                })
                .toList();
        notificationRepository.saveAll(rows);
    }

    /** §4.5 step 3: daily TTL cleanup - rows older than {@value #RETENTION_DAYS} days. */
    @Scheduled(fixedDelayString = "${app.notifications.retention-interval-ms:86400000}")
    public void cleanupExpired() {
        OffsetDateTime cutoff = OffsetDateTime.now().minusDays(RETENTION_DAYS);
        int deleted = notificationRepository.deleteByCreatedAtBefore(cutoff);
        log.info("notification retention: deleted={} cutoff={}", deleted, cutoff);
    }
}
