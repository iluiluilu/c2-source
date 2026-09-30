package com.aismartstore.backend.documents;

import com.aismartstore.backend.auth.CurrentUser;
import com.aismartstore.backend.auth.KeycloakUser;
import com.aismartstore.backend.digitization.results.DocumentDigitizationStatus;
import com.aismartstore.backend.digitization.results.DocumentDigitizationStatusRepository;
import com.aismartstore.backend.documents.dto.TrashDto;
import com.aismartstore.backend.documents.dto.TrashPreviewDto;
import com.aismartstore.backend.groups.DocumentScopeGuard;
import com.aismartstore.backend.trash.TrashService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Java port of the NestJS backend's documents/documents.controller.ts.
 * {@code QUAN_TRI} is deliberately never granted here (NFR-07 - admins never
 * read business content).
 *
 * <p>data-ingestion.md §3 (breaking change): the old {@code POST /documents}
 * (multipart) endpoint is removed - uploads now go through the 2-step batch
 * API in {@code com.aismartstore.backend.ingest.IngestController}
 * ({@code POST /ingest/batches} then {@code POST
 * /ingest/batches/{id}/files}). The read endpoints below are unchanged.
 */
@RestController
@RequestMapping("/documents")
@Validated
public class DocumentsController {

    private final DocumentsService documentsService;
    private final DocumentScopeGuard documentScopeGuard;
    private final PageImageRenderer pageImageRenderer;
    private final TrashService trashService;
    private final DocumentDigitizationStatusRepository documentDigitizationStatusRepository;

    public DocumentsController(
            DocumentsService documentsService,
            DocumentScopeGuard documentScopeGuard,
            PageImageRenderer pageImageRenderer,
            TrashService trashService,
            DocumentDigitizationStatusRepository documentDigitizationStatusRepository) {
        this.documentsService = documentsService;
        this.documentScopeGuard = documentScopeGuard;
        this.pageImageRenderer = pageImageRenderer;
        this.trashService = trashService;
        this.documentDigitizationStatusRepository = documentDigitizationStatusRepository;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PERM_document.read')")
    public List<DocumentsService.DocumentListItem> list(
            @CurrentUser KeycloakUser user,
            @RequestParam(required = false) UUID folderId,
            @RequestParam(required = false) UUID tagId,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) @Min(1) @Max(200) Integer limit,
            @RequestParam(required = false) @Min(0) Integer offset) {
        return documentsService.list(user, folderId, tagId, q, limit, offset);
    }

    /**
     * classification-notes-01-tags.md §3 (US2 AC-5/AC-6) - static literal
     * segment, matched ahead of {@code /{id}} by Spring's PathPattern
     * specificity ordering regardless of declaration order.
     */
    @GetMapping("/unclassified-doc-type")
    @PreAuthorize("hasAuthority('PERM_document.read')")
    public List<DocumentsService.DocumentListItem> unclassifiedDocType(
            @CurrentUser KeycloakUser user,
            @RequestParam(required = false) @Min(1) @Max(200) Integer limit,
            @RequestParam(required = false) @Min(0) Integer offset) {
        return documentsService.listUnclassifiedDocType(user, limit, offset);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('PERM_document.read')")
    public Document findOne(@CurrentUser KeycloakUser user, @PathVariable UUID id) {
        return documentsService.findOne(user, id);
    }

    @GetMapping("/{id}/content")
    @PreAuthorize("hasAuthority('PERM_document.read')")
    public ResponseEntity<InputStreamResource> content(@CurrentUser KeycloakUser user, @PathVariable UUID id) {
        DocumentsService.DocumentContent content = documentsService.getContent(user, id);
        Document document = content.document();

        String encodedName =
                URLEncoder.encode(document.getOriginalName(), StandardCharsets.UTF_8).replace("+", "%20");

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(document.getMimeType()))
                .contentLength(document.getSize())
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encodedName)
                .body(new InputStreamResource(content.stream()));
    }

    /**
     * search-fulltext-05-frontend.md §1/§3.1 (Task 2): general-scope
     * counterpart to {@code GET
     * /digitization-review/runs/{runId}/pages/{pageNo}/image} - that endpoint
     * only allows the document's own uploader in ("chính chủ"), which 403s
     * for a document shared to a group a search result's viewer belongs to
     * but did not upload themselves. This one is scoped via {@link
     * DocumentScopeGuard#requireVisible} (same {@link
     * com.aismartstore.backend.groups.BusinessDataScope} as every other
     * {@code DocumentsController} read route) instead - 404 (not 403) when
     * out of scope, consistent with every other endpoint here.
     */
    /**
     * delete-restore-trash-01-xoa-mem-thung-rac.md §3/§4.1: xóa mềm cả lô.
     * Kept under {@code /documents} for route cohesion with the rest of
     * {@code Document}'s mutations (§1) - the actual logic is {@code
     * TrashService#trash}.
     */
    @PostMapping("/trash")
    @PreAuthorize("hasAuthority('PERM_trash.manage')")
    public TrashService.TrashResult trash(@CurrentUser KeycloakUser user, @Valid @RequestBody TrashDto dto) {
        return trashService.trash(user, dto.documentIds(), dto.reason());
    }

    @PostMapping("/trash-preview")
    @PreAuthorize("hasAuthority('PERM_trash.manage')")
    public List<TrashService.PreviewItem> trashPreview(@Valid @RequestBody TrashPreviewDto dto) {
        return trashService.preview(dto.documentIds());
    }

    @GetMapping("/{id}/pages/{pageNo}/image")
    @PreAuthorize("hasAuthority('PERM_document.read')")
    public ResponseEntity<byte[]> getPageImage(
            @CurrentUser KeycloakUser user, @PathVariable UUID id, @PathVariable int pageNo) {
        Document document = documentScopeGuard.requireVisible(user, id);
        PageImageRenderer.PageImage image = pageImageRenderer.render(document, pageNo);

        return ResponseEntity.ok()
                .contentType(image.contentType())
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=3600")
                .body(image.bytes());
    }

    /**
     * digitization-pipeline-03-video-queue.md §3 (mục 0 điểm 2) - client poll
     * endpoint for task {@code video}'s status of one document. Reads {@code
     * document_digitization_status.video_status} (written by
     * DigitizationRunLifecycleServiceImpl since
     * digitization-pipeline-05-video-review.md) - deliberately not
     * {@code digitization_run} directly, so {@code worker_id}/{@code
     * last_error} never leak to a general document-read caller. Same
     * {@code PERM_document.read} + {@link DocumentScopeGuard} 404-on-out-of-
     * scope convention as every other read route here (mục 3, xác nhận
     * 2026-09-14) - not {@code PERM_digitization_review.manage} (that is
     * FR-18 reviewer tooling, which task {@code video} now also goes through,
     * but this route stays a general status read).
     */
    @GetMapping("/{id}/video-status")
    @PreAuthorize("hasAuthority('PERM_document.read')")
    public VideoStatusView videoStatus(@CurrentUser KeycloakUser user, @PathVariable UUID id) {
        Document document = documentScopeGuard.requireVisible(user, id);
        return documentDigitizationStatusRepository
                .findById(document.getId())
                .map(status -> new VideoStatusView(document.getId(), status.getVideoStatus(), status.getUpdatedAt()))
                .orElseGet(() -> new VideoStatusView(
                        document.getId(), DocumentDigitizationStatus.STATUS_NOT_APPLICABLE, null));
    }

    public record VideoStatusView(UUID documentId, String status, OffsetDateTime updatedAt) {}
}
