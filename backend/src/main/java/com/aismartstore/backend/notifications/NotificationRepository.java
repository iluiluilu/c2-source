package com.aismartstore.backend.notifications;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    /** GET /notifications: most recent first, capped at 100 (§3.4). */
    List<Notification> findTop100ByRecipientSubOrderByCreatedAtDesc(String recipientSub);

    /** GET /notifications?unreadOnly=true. */
    List<Notification> findTop100ByRecipientSubAndReadAtIsNullOrderByCreatedAtDesc(String recipientSub);

    /** POST /notifications/{id}/read: existence check scoped to the caller, for the 404-vs-not-mine decision. */
    boolean existsByIdAndRecipientSub(UUID id, String recipientSub);

    /**
     * Idempotent: a second read of an already-read notification still
     * matches zero rows to update but the endpoint itself does not treat
     * that as an error (existence is checked separately, see {@link
     * #existsByIdAndRecipientSub}) - calling this twice is not an error.
     */
    @Modifying
    @Query("update Notification n set n.readAt = :now where n.id = :id and n.recipientSub = :recipientSub "
            + "and n.readAt is null")
    int markRead(@Param("id") UUID id, @Param("recipientSub") String recipientSub, @Param("now") OffsetDateTime now);

    /** §4.5 step 3: TTL cleanup job. */
    @Modifying
    int deleteByCreatedAtBefore(OffsetDateTime cutoff);
}
