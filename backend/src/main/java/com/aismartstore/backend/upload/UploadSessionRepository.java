package com.aismartstore.backend.upload;

import jakarta.persistence.LockModeType;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UploadSessionRepository extends JpaRepository<UploadSession, UUID> {

    /**
     * Ownership-scoped lookup used by every endpoint after {@code init}
     * (chunk/status/complete, AC-7): a foreign id and a session that belongs
     * to someone else both resolve the same way (empty) - callers turn that
     * into a single 403, never leaking whether the id exists at all.
     */
    Optional<UploadSession> findByIdAndCreatedBySub(UUID id, String createdBySub);

    /**
     * Row-level lock for the read-modify-write around {@code received_bytes}
     * (§4.2 step 2): held for the duration of the enclosing
     * {@code @Transactional} method, so two chunk requests racing on the same
     * session serialize instead of both reading the same starting offset.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from UploadSession s where s.id = :id")
    Optional<UploadSession> findByIdForUpdate(@Param("id") UUID id);

    /**
     * chunked-file-upload-03-session-cleanup.md §2: candidate ids for
     * {@code UploadCleanupJob} - pushes the {@code expires_at < cutoff}
     * condition down to SQL so the query uses the composite
     * {@code IDX_upload_session_status_expires_at} index (V16) instead of
     * filtering in application code.
     */
    List<UploadSession> findByStatusAndExpiresAtBeforeOrderByExpiresAtAsc(
            String status, OffsetDateTime cutoff, Pageable pageable);
}
