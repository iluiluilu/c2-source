package com.aismartstore.backend.digitization;

import com.aismartstore.backend.common.SqlErrors;
import com.aismartstore.backend.config.StorageProperties;
import com.aismartstore.backend.documents.Document;
import com.aismartstore.backend.documents.DocumentRepository;
import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * digitization-pipeline-01-source-dispatch.md §4.2 step 1 / §4.3 step 1:
 * classifies a just-ingested (or reconciliation-found, §4.5 step 2) document
 * by {@code mimeType} and creates the corresponding row(s). Shared by {@link
 * DigitizationDispatchListener} (hot path) and {@link
 * DigitizationDispatchRetryJob} (reconciliation) so the two never drift.
 *
 * <p>digitization-pipeline-04-video-no-separation.md §4.1: video/* no longer
 * goes through {@code source_separation_job}/FFmpeg - it joins the
 * {@code original} branch like audio/image/PDF, dispatched straight to
 * {@code q.video} against the original file.
 */
@Service
public class DigitizationIntakeService {

    private static final Logger log = LoggerFactory.getLogger(DigitizationIntakeService.class);

    /** §4.2 step 1 - exactly MimeAllowlist's video/* entries. */
    private static final Set<String> VIDEO_MIME_TYPES = Set.of(
            "video/mp4", "video/mpeg", "video/quicktime", "video/x-matroska", "video/webm");

    private static final String PDF_MIME_TYPE = "application/pdf";

    private final DigitizationSourceRepository digitizationSourceRepository;
    private final DocumentRepository documentRepository;
    private final StorageProperties storageProperties;
    private final DigitizationDispatchService digitizationDispatchService;

    public DigitizationIntakeService(
            DigitizationSourceRepository digitizationSourceRepository,
            DocumentRepository documentRepository,
            StorageProperties storageProperties,
            DigitizationDispatchService digitizationDispatchService) {
        this.digitizationSourceRepository = digitizationSourceRepository;
        this.documentRepository = documentRepository;
        this.storageProperties = storageProperties;
        this.digitizationDispatchService = digitizationDispatchService;
    }

    /**
     * §4.1 step 4 / §4.2-4.3: routes by {@code mimeType}. Office docs/text/
     * csv/etc fall through as a no-op - out of scope (FR-17/FR-19 only cover
     * image/scan/PDF/audio/video).
     */
    public void handleIngestedDocument(UUID documentId, String mimeType) {
        if (mimeType == null) {
            return;
        }
        if (VIDEO_MIME_TYPES.contains(mimeType) || isDirectlyDigitizable(mimeType)) {
            createOriginalSourceIfAbsent(documentId, mimeType);
        }
    }

    private boolean isDirectlyDigitizable(String mimeType) {
        return mimeType.startsWith("audio/") || mimeType.startsWith("image/") || mimeType.equals(PDF_MIME_TYPE);
    }

    /** §4.3 step 1: {@code id = documentId}, {@code ON CONFLICT (id) DO NOTHING} equivalent. */
    private void createOriginalSourceIfAbsent(UUID documentId, String mimeType) {
        Document document = documentRepository.findById(documentId).orElse(null);
        if (document == null || document.getStorageKey() == null) {
            log.error("Cannot create digitization_source for document {} - document/storageKey missing", documentId);
            return;
        }

        DigitizationSource source = new DigitizationSource();
        source.setId(documentId);
        source.setDocumentId(documentId);
        source.setComponentType(DigitizationSource.COMPONENT_ORIGINAL);
        source.setFilePath(storageProperties.bucket() + "/" + document.getStorageKey());
        source.setMimeType(mimeType);
        source.setDispatchable(true);
        source.setDispatchStatus(DigitizationSource.DISPATCH_PENDING);
        OffsetDateTime now = OffsetDateTime.now();
        source.setCreatedAt(now);
        source.setUpdatedAt(now);

        try {
            digitizationSourceRepository.save(source);
        } catch (DataIntegrityViolationException e) {
            if (!SqlErrors.isUniqueViolation(e)) {
                throw e;
            }
            log.debug("digitization_source {} already exists - skipping (idempotent)", documentId);
            return;
        }

        // §4.3 step 2: publish immediately, same pass - no separate job needed.
        digitizationDispatchService.publish(source);
    }
}
