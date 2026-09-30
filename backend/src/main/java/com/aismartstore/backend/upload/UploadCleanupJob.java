package com.aismartstore.backend.upload;

import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * chunked-file-upload-03-session-cleanup.md §0/§4.1: the first (and, as of
 * this story, only) background {@code @Scheduled} job in the backend. Scans
 * {@code upload_session} for {@code OPEN} rows whose {@code expires_at} is in
 * the past and flips them to {@code EXPIRED} via {@link
 * UploadSessionService#expireIfDue}, then deletes the backing temp file on
 * disk. Deliberately named/placed in a domain-neutral spot (not {@code
 * UploadSessionCleanupJob}) so a future story could add a second sweep step
 * for {@code UploadBatch} to this same class instead of introducing a second
 * scheduled job (see §0's "một job" constraint).
 */
@Component
public class UploadCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(UploadCleanupJob.class);
    private static final int BATCH_SIZE = 500;

    private final UploadSessionService uploadSessionService;

    public UploadCleanupJob(UploadSessionService uploadSessionService) {
        this.uploadSessionService = uploadSessionService;
    }

    @Scheduled(fixedDelayString = "${app.upload.session-cleanup-interval-ms:900000}")
    public void run() {
        long startedAt = System.currentTimeMillis();
        List<UUID> candidateIds = uploadSessionService.findExpiredSessionIds(BATCH_SIZE);

        int expired = 0;
        int filesDeleted = 0;
        int filesMissing = 0;
        int fileErrors = 0;

        for (UUID id : candidateIds) {
            UploadSessionService.ExpiryOutcome outcome = uploadSessionService.expireIfDue(id);
            if (outcome == null) {
                // Already handled by a concurrent chunk PUT/complete before this
                // job's transaction won the row lock (§4.2 race) - not an error.
                continue;
            }
            expired++;
            switch (outcome.deleteTempFile()) {
                case DELETED -> filesDeleted++;
                case ALREADY_MISSING -> filesMissing++;
                case DELETE_FAILED -> fileErrors++;
            }
        }

        // Always log exactly one INFO line per run, even when scanned == 0, so
        // operators can tell "job ran but found nothing to clean" apart from
        // "job did not run at all" (AC-6).
        log.info(
                "upload_session cleanup: scanned={} expired={} tempFilesDeleted={} tempFilesAlreadyMissing={} "
                        + "tempFileDeleteErrors={} durationMs={}",
                candidateIds.size(),
                expired,
                filesDeleted,
                filesMissing,
                fileErrors,
                System.currentTimeMillis() - startedAt);
    }
}
