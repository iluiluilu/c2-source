package com.aismartstore.backend.digitization.results;

import com.aismartstore.backend.config.DigitizationProperties;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * digitization-results-store.md §4 bước 8 (US4 AC-5) - "so khớp mỗi {@code
 * document_digitization_status} với MAX/tổng hợp thực tế từ {@code
 * digitization_run} active cùng {@code document_id}, log lệch (không tự sửa
 * âm thầm)". Modeled on {@link
 * com.aismartstore.backend.digitization.DigitizationDispatchRetryJob}'s
 * reconciliation role, but read-only: any mismatch found here means some
 * write path forgot to call {@code upsertTaskStatus} (bug), not something
 * this job should paper over.
 */
@Component
public class DocumentDigitizationStatusReconciliationJob {

    private static final Logger log = LoggerFactory.getLogger(DocumentDigitizationStatusReconciliationJob.class);

    private final DocumentDigitizationStatusRepository documentDigitizationStatusRepository;
    private final DigitizationRunRepository digitizationRunRepository;
    private final DigitizationProperties digitizationProperties;

    public DocumentDigitizationStatusReconciliationJob(
            DocumentDigitizationStatusRepository documentDigitizationStatusRepository,
            DigitizationRunRepository digitizationRunRepository,
            DigitizationProperties digitizationProperties) {
        this.documentDigitizationStatusRepository = documentDigitizationStatusRepository;
        this.digitizationRunRepository = digitizationRunRepository;
        this.digitizationProperties = digitizationProperties;
    }

    @Scheduled(fixedDelayString = "${app.digitization.status-reconciliation-interval-ms:300000}")
    public void run() {
        int mismatches = 0;
        int page = 0;
        Page<DocumentDigitizationStatus> batch;
        do {
            batch = documentDigitizationStatusRepository.findAll(
                    PageRequest.of(page, digitizationProperties.statusReconciliationBatchSize()));
            for (DocumentDigitizationStatus status : batch) {
                if (reconcileOne(status)) {
                    mismatches++;
                }
            }
            page++;
        } while (batch.hasNext());

        if (mismatches > 0) {
            log.warn("document_digitization_status reconciliation: {} document(s) out of sync", mismatches);
        }
    }

    /** @return true if a mismatch was found (and logged). */
    private boolean reconcileOne(DocumentDigitizationStatus status) {
        String expectedOcr = expectedTaskStatus(status.getDocumentId(), DigitizationRun.TASK_OCR);
        String expectedStt = expectedTaskStatus(status.getDocumentId(), DigitizationRun.TASK_STT);
        String expectedSed = expectedTaskStatus(status.getDocumentId(), DigitizationRun.TASK_SED);
        String expectedVideoSummary = expectedTaskStatus(status.getDocumentId(), DigitizationRun.TASK_VIDEO_SUMMARY);
        String expectedVideo = expectedTaskStatus(status.getDocumentId(), DigitizationRun.TASK_VIDEO);
        String expectedOverall = DigitizationStatusMapper.overallStatus(
                expectedOcr, expectedStt, expectedSed, expectedVideoSummary, expectedVideo);

        boolean mismatch = !expectedOcr.equals(status.getOcrStatus())
                || !expectedStt.equals(status.getSttStatus())
                || !expectedSed.equals(status.getSedStatus())
                || !expectedVideoSummary.equals(status.getVideoSummaryStatus())
                || !expectedVideo.equals(status.getVideoStatus())
                || !expectedOverall.equals(status.getOverallStatus());

        if (mismatch) {
            log.warn(
                    "document_digitization_status drift for document {}: stored=[ocr={}, stt={}, sed={}, "
                            + "video_summary={}, video={}, overall={}] expected=[ocr={}, stt={}, sed={}, video_summary={}, video={}, "
                            + "overall={}]",
                    status.getDocumentId(),
                    status.getOcrStatus(), status.getSttStatus(), status.getSedStatus(),
                    status.getVideoSummaryStatus(), status.getVideoStatus(), status.getOverallStatus(),
                    expectedOcr, expectedStt, expectedSed, expectedVideoSummary, expectedVideo, expectedOverall);
        }
        return mismatch;
    }

    private String expectedTaskStatus(UUID documentId, String task) {
        Optional<DigitizationRun> active = digitizationRunRepository.findActiveByDocumentAndTask(documentId, task);
        return active
                .map(run -> DigitizationStatusMapper.activeRunTaskStatus(run.getProcessingStatus(), run.getReviewState()))
                .orElse(DocumentDigitizationStatus.STATUS_NOT_APPLICABLE);
    }
}
