package com.aismartstore.backend.digitization.review;

import com.aismartstore.backend.auth.CurrentUser;
import com.aismartstore.backend.auth.KeycloakUser;
import com.aismartstore.backend.common.BadRequestException;
import com.aismartstore.backend.common.ConflictException;
import com.aismartstore.backend.common.ForbiddenException;
import com.aismartstore.backend.common.NotFoundException;
import com.aismartstore.backend.common.UnprocessableEntityException;
import com.aismartstore.backend.digitization.DigitizationSource;
import com.aismartstore.backend.digitization.DigitizationSourceRepository;
import com.aismartstore.backend.digitization.review.dto.AddSegmentRequest;
import com.aismartstore.backend.digitization.review.dto.PatchSegmentRequest;
import com.aismartstore.backend.digitization.results.ContentSegment;
import com.aismartstore.backend.digitization.results.ContentSegmentRepository;
import com.aismartstore.backend.digitization.results.DigitizationRun;
import com.aismartstore.backend.digitization.results.DigitizationRunLifecycleService;
import com.aismartstore.backend.digitization.results.DigitizationRunRepository;
import com.aismartstore.backend.digitization.results.SoundEvent;
import com.aismartstore.backend.digitization.results.SoundEventRepository;
import com.aismartstore.backend.documents.Document;
import com.aismartstore.backend.documents.DocumentRepository;
import com.aismartstore.backend.documents.PageImageRenderer;
import com.aismartstore.backend.groups.DocumentScopeGuard;
import com.aismartstore.backend.systemconfig.SystemConfigDefaults;
import com.aismartstore.backend.systemconfig.SystemConfigService;
import jakarta.validation.Valid;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * digitization-review-01-ocr.md §3/§4 (Story 1-4, 8) +
 * digitization-review-04-sed-summary.md §3/§4 (Story 9-10): the review queue
 * + one run's detail + edit/confirm actions for {@code task ∈ {ocr, stt, sed,
 * video_summary}}. Every endpoint requires {@code
 * PERM_digitization_review.manage} (V28) plus an
 * explicit "chính chủ" check in this controller (Quyết định 5 SA) - the
 * permission alone is only a necessary condition. Mặc định {@code CAN_BO} +
 * {@code LANH_DAO} (R2, cấp qua V35__r2_business_parity.sql); {@code
 * QUAN_TRI} (R3) chỉnh được qua màn RBAC.
 */
@RestController
@RequestMapping("/digitization-review")
public class ReviewController {

    private static final Logger log = LoggerFactory.getLogger(ReviewController.class);

    private static final int MAX_PAGE_SIZE = 100;
    private static final int DEFAULT_SEGMENT_PAGE_SIZE = 100;
    private static final Set<String> REVIEWABLE_TASKS = Set.of(
            DigitizationRun.TASK_OCR, DigitizationRun.TASK_STT, DigitizationRun.TASK_SED,
            DigitizationRun.TASK_VIDEO_SUMMARY, DigitizationRun.TASK_VIDEO);
    /** digitization-review-04-sed-summary.md §3 (API contract, POST/DELETE rows): no manual segment add/delete. */
    private static final Set<String> NO_MANUAL_SEGMENT_TASKS =
            Set.of(DigitizationRun.TASK_SED, DigitizationRun.TASK_VIDEO_SUMMARY);

    private final ReviewQueueRepository reviewQueueRepository;
    private final ReviewSegmentRepository reviewSegmentRepository;
    private final DigitizationRunRepository digitizationRunRepository;
    private final ContentSegmentRepository contentSegmentRepository;
    private final DocumentRepository documentRepository;
    private final DigitizationSourceRepository digitizationSourceRepository;
    private final SoundEventRepository soundEventRepository;
    private final DigitizationRunLifecycleService lifecycleService;
    private final SystemConfigService systemConfigService;
    private final PageImageRenderer pageImageRenderer;
    private final DocumentScopeGuard documentScopeGuard;

    public ReviewController(
            ReviewQueueRepository reviewQueueRepository,
            ReviewSegmentRepository reviewSegmentRepository,
            DigitizationRunRepository digitizationRunRepository,
            ContentSegmentRepository contentSegmentRepository,
            DocumentRepository documentRepository,
            DigitizationSourceRepository digitizationSourceRepository,
            SoundEventRepository soundEventRepository,
            DigitizationRunLifecycleService lifecycleService,
            SystemConfigService systemConfigService,
            PageImageRenderer pageImageRenderer,
            DocumentScopeGuard documentScopeGuard) {
        this.reviewQueueRepository = reviewQueueRepository;
        this.reviewSegmentRepository = reviewSegmentRepository;
        this.digitizationRunRepository = digitizationRunRepository;
        this.contentSegmentRepository = contentSegmentRepository;
        this.documentRepository = documentRepository;
        this.digitizationSourceRepository = digitizationSourceRepository;
        this.soundEventRepository = soundEventRepository;
        this.lifecycleService = lifecycleService;
        this.systemConfigService = systemConfigService;
        this.pageImageRenderer = pageImageRenderer;
        this.documentScopeGuard = documentScopeGuard;
    }

    @GetMapping("/queue")
    @PreAuthorize("hasAuthority('PERM_digitization_review.manage')")
    public RunSummaryPageView queue(
            @CurrentUser KeycloakUser user,
            @RequestParam(required = false) String task,
            @RequestParam(required = false) List<String> status,
            @RequestParam(required = false) String qualityFlag,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        if (task != null && !REVIEWABLE_TASKS.contains(task)) {
            throw new IllegalArgumentException("task must be one of " + REVIEWABLE_TASKS);
        }
        int clampedSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        return reviewQueueRepository.search(user.sub(), task, status, qualityFlag, Math.max(page, 0), clampedSize);
    }

    @GetMapping("/runs/{runId}")
    @PreAuthorize("hasAuthority('PERM_digitization_review.manage')")
    public RunDetailView getRun(
            @CurrentUser KeycloakUser user,
            @PathVariable UUID runId,
            @RequestParam(defaultValue = "0") int segmentPage,
            @RequestParam(defaultValue = "100") int segmentSize) {
        DigitizationRun run = requireOwnedReviewableRun(runId, user);
        Document document = requireDocument(run.getDocumentId());

        // §4 bước 2 (US2/US3): best-effort - a failure here must not fail the
        // read the reviewer is currently trying to perform.
        try {
            lifecycleService.markFirstViewed(runId, user.sub());
        } catch (RuntimeException e) {
            log.warn("markFirstViewed failed for run {}", runId, e);
        }

        return buildRunDetail(run, document, segmentPage, segmentSize);
    }

    /**
     * digitization-review-07-confirmed-content-view.md §4: read-only
     * counterpart of {@link #getRun} for a run already {@code confirmed} -
     * gated by document-view scope instead of {@code
     * digitization_review.manage}/"chính chủ", and deliberately never calls
     * {@link DigitizationRunLifecycleService#markFirstViewed} (that side
     * effect belongs only to the reviewer's own first open, §4 bước 2 above -
     * a broader audience just viewing already-confirmed content must not
     * affect review SLA bookkeeping).
     */
    @GetMapping("/runs/{runId}/view")
    @PreAuthorize("hasAuthority('PERM_document.read')")
    public RunDetailView getConfirmedRunView(
            @CurrentUser KeycloakUser user,
            @PathVariable UUID runId,
            @RequestParam(defaultValue = "0") int segmentPage,
            @RequestParam(defaultValue = "100") int segmentSize) {
        DigitizationRun run = requireVisibleConfirmedRun(runId, user);
        Document document = requireDocument(run.getDocumentId());
        return buildRunDetail(run, document, segmentPage, segmentSize);
    }

    /** digitization-review-07-confirmed-content-view.md §4: shared body of {@link #getRun}/{@link #getConfirmedRunView}. */
    private RunDetailView buildRunDetail(DigitizationRun run, Document document, int segmentPage, int segmentSize) {
        UUID runId = run.getId();
        int clampedSize = Math.min(Math.max(segmentSize, 1), MAX_PAGE_SIZE);
        Pageable pageable = PageRequest.of(Math.max(segmentPage, 0), clampedSize);
        // digitization-review-02-stt.md §3.2 (Task 3): overlapsAdjacent only ever
        // compares a segment to its immediate neighbours by segment_index, but
        // those neighbours can straddle a page boundary - load the run's full,
        // already-ordered segment list once (bounded by one run's segment count,
        // unlike the queue's up-to-100.000-row scale) and paginate in memory.
        List<ContentSegment> allSegments = contentSegmentRepository.findByRunIdOrderBySegmentIndex(runId);
        // digitization-review-04-sed-summary.md §2.1/§2.2 (Task 2/3): both
        // empty maps (and thus a no-op) for task ∈ {ocr, stt}.
        Map<UUID, String> soundEventLabels = loadSoundEventLabels(run, allSegments);
        Map<UUID, List<ReviewSegmentRepository.SourceReferenceRow>> sourceReferenceRows =
                loadSourceReferenceRows(run, allSegments);
        List<SegmentView> allViews = SegmentView.listWithOverlap(
                allSegments, soundEventLabels, toSourceReferenceViewMap(sourceReferenceRows));
        int total = allViews.size();
        int fromIndex = Math.min(pageable.getPageNumber() * pageable.getPageSize(), total);
        int toIndex = Math.min(fromIndex + pageable.getPageSize(), total);
        Page<SegmentView> segments = new PageImpl<>(allViews.subList(fromIndex, toIndex), pageable, total);

        RunSummaryView summary = toSummary(run, document);
        Integer sourceDurationMs = digitizationSourceRepository.findById(run.getSourceId())
                .map(DigitizationSource::getDurationMs)
                .orElse(null);
        UUID previousConfirmedRunId = findOtherConfirmedRun(run).map(DigitizationRun::getId).orElse(null);
        boolean staleWarning = computeStaleWarning(run, sourceReferenceRows);

        return RunDetailView.of(
                summary, sourceDurationMs, previousConfirmedRunId, SegmentPageView.from(segments), staleWarning);
    }

    /**
     * digitization-review-07-confirmed-content-view.md §3: confirmed runs of
     * one document, for the "Nội dung số hoá" section on the document detail
     * page - same document-view scope as {@link #getConfirmedRunView}.
     */
    @GetMapping("/documents/{documentId}/confirmed-runs")
    @PreAuthorize("hasAuthority('PERM_document.read')")
    public List<RunSummaryView> confirmedRuns(@CurrentUser KeycloakUser user, @PathVariable UUID documentId) {
        Document document = documentScopeGuard.requireVisible(user, documentId);
        return digitizationRunRepository.findByDocumentIdAndReviewState(documentId, DigitizationRun.REVIEW_CONFIRMED)
                .stream()
                .map(run -> toSummary(run, document))
                .toList();
    }

    @PatchMapping("/segments/{segmentId}")
    @PreAuthorize("hasAuthority('PERM_digitization_review.manage')")
    public SegmentView reviseSegment(
            @CurrentUser KeycloakUser user, @PathVariable UUID segmentId, @Valid @RequestBody PatchSegmentRequest body) {
        ContentSegment segment = requireSegment(segmentId);
        DigitizationRun run = requireOwnedReviewableRun(segment.getRunId(), user);

        if (body.timeStartMs() != null || body.timeEndMs() != null) {
            // digitization-review-04-sed-summary.md §3 (AC-5 Story 9): a
            // dedicated 422 for task=sed, distinct from validateSttTimeBounds'
            // generic "only task=stt" 400 (kept unchanged for ocr/video_summary
            // below - neither has a spec'd status code of its own).
            if (DigitizationRun.TASK_SED.equals(run.getTask())) {
                throw new UnprocessableEntityException("SED không hỗ trợ sửa mốc thời gian sự kiện");
            }
            validateSttTimeBounds(run, segment, body.timeStartMs(), body.timeEndMs());
        }

        ContentSegment revised = lifecycleService.reviseSegment(
                segmentId, body.expectedRevisionNo(), body.text(), body.timeStartMs(), body.timeEndMs(), user.sub(),
                body.reason());
        return SegmentView.of(revised);
    }

    @DeleteMapping("/segments/{segmentId}")
    @PreAuthorize("hasAuthority('PERM_digitization_review.manage')")
    public SegmentView deleteSegment(
            @CurrentUser KeycloakUser user, @PathVariable UUID segmentId, @RequestParam int expectedRevisionNo) {
        ContentSegment segment = requireSegment(segmentId);
        DigitizationRun run = requireOwnedReviewableRun(segment.getRunId(), user);
        // digitization-review-04-sed-summary.md §3 (AC-2 Story 10): a
        // video_summary run always has exactly 1 segment - deleting it would
        // leave the run without any content, so this op is disallowed
        // entirely (unlike sed, which reuses "xoá đoạn" as-is for AC-4 Story 9).
        if (DigitizationRun.TASK_VIDEO_SUMMARY.equals(run.getTask())) {
            throw new ConflictException("Tóm tắt video không hỗ trợ xoá đoạn");
        }

        ContentSegment deleted = lifecycleService.deleteSegment(segmentId, expectedRevisionNo, user.sub(), null);
        return SegmentView.of(deleted);
    }

    @PostMapping("/runs/{runId}/segments")
    @PreAuthorize("hasAuthority('PERM_digitization_review.manage')")
    public ResponseEntity<SegmentView> addSegment(
            @CurrentUser KeycloakUser user, @PathVariable UUID runId, @Valid @RequestBody AddSegmentRequest body) {
        DigitizationRun run = requireOwnedReviewableRun(runId, user);
        // digitization-review-04-sed-summary.md §3 (Story 9 has no manual
        // "add sound event" AC; AC-2 Story 10 disallows it explicitly for
        // video_summary's single-segment-per-run invariant).
        if (NO_MANUAL_SEGMENT_TASKS.contains(run.getTask())) {
            throw new ConflictException("Không hỗ trợ thêm đoạn thủ công cho tác vụ này");
        }

        ContentSegment created = lifecycleService.addManualSegment(runId, body.afterSegmentIndex(), body.text(), user.sub());
        return ResponseEntity.status(HttpStatus.CREATED).body(SegmentView.of(created));
    }

    @PostMapping("/runs/{runId}/confirm")
    @PreAuthorize("hasAuthority('PERM_digitization_review.manage')")
    public RunSummaryView confirmRun(@CurrentUser KeycloakUser user, @PathVariable UUID runId) {
        requireOwnedReviewableRun(runId, user);

        DigitizationRun confirmed = lifecycleService.confirmRun(runId, user.sub());
        Document document = requireDocument(confirmed.getDocumentId());
        return toSummary(confirmed, document);
    }

    /**
     * digitization-review-05-frontend.md §3.1 (Task 1, OcrPageViewer's
     * image {@code <img>} - Story 2): renders one page of the document's
     * original file, not a stored/cached artifact - {@code
     * document.storage_key}/{@code mime_type} via {@link
     * ObjectStorageService} are the only inputs, same "chính chủ" gate
     * ({@link #requireOwnedReviewableRun}) as every other endpoint here.
     * {@code pageNo} is 1-indexed on the wire (matches {@code
     * content_segment.page_no}, §3.1); PDFBox's own paging is 0-indexed
     * internally.
     */
    @GetMapping("/runs/{runId}/pages/{pageNo}/image")
    @PreAuthorize("hasAuthority('PERM_digitization_review.manage')")
    public ResponseEntity<byte[]> getPageImage(
            @CurrentUser KeycloakUser user, @PathVariable UUID runId, @PathVariable int pageNo) {
        DigitizationRun run = requireOwnedReviewableRun(runId, user);
        Document document = requireDocument(run.getDocumentId());

        // search-fulltext-05-frontend.md §1/§3.1 (Task 1): rendering itself now
        // lives in the shared PageImageRenderer bean - only the "chính chủ" gate
        // above is specific to this review-only endpoint.
        PageImageRenderer.PageImage image = pageImageRenderer.render(document, pageNo);

        return ResponseEntity.ok()
                .contentType(image.contentType())
                // §3.1: the page image itself never changes over time (only
                // text/bbox can) - safe for the browser to cache across page
                // back-and-forth or a re-opened run.
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=3600")
                .body(image.bytes());
    }

    /** digitization-review-07-confirmed-content-view.md §3: read-only counterpart of {@link #getPageImage}. */
    @GetMapping("/runs/{runId}/view/pages/{pageNo}/image")
    @PreAuthorize("hasAuthority('PERM_document.read')")
    public ResponseEntity<byte[]> getConfirmedRunPageImage(
            @CurrentUser KeycloakUser user, @PathVariable UUID runId, @PathVariable int pageNo) {
        DigitizationRun run = requireVisibleConfirmedRun(runId, user);
        Document document = requireDocument(run.getDocumentId());

        PageImageRenderer.PageImage image = pageImageRenderer.render(document, pageNo);

        return ResponseEntity.ok()
                .contentType(image.contentType())
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=3600")
                .body(image.bytes());
    }

    // ---- shared helpers -----------------------------------------------------

    /** §3 (chung cho mọi endpoint): run must exist, be a reviewable run (ocr/stt/sed/video_summary), and belong to the caller. */
    private DigitizationRun requireOwnedReviewableRun(UUID runId, KeycloakUser user) {
        DigitizationRun run = digitizationRunRepository.findById(runId)
                .filter(r -> REVIEWABLE_TASKS.contains(r.getTask()))
                .orElseThrow(() -> new NotFoundException("digitization_run not found: " + runId));
        Document document = requireDocument(run.getDocumentId());
        if (!document.getUploaderSub().equals(user.sub())) {
            throw new ForbiddenException("Not the uploader of document " + document.getId());
        }
        return run;
    }

    /**
     * digitization-review-07-confirmed-content-view.md §4: run must exist, be
     * a reviewable run, already {@code confirmed}, and belong to a document
     * within the caller's {@link DocumentScopeGuard} scope - the broadened
     * read-only gate used by {@code .../view}, {@code .../view/pages/.../image}
     * and {@code /documents/{id}/confirmed-runs}. Deliberately does not check
     * "chính chủ" ({@link #requireOwnedReviewableRun} still does, for the
     * edit/confirm endpoints).
     */
    private DigitizationRun requireVisibleConfirmedRun(UUID runId, KeycloakUser user) {
        DigitizationRun run = digitizationRunRepository.findById(runId)
                .filter(r -> REVIEWABLE_TASKS.contains(r.getTask()))
                .filter(r -> DigitizationRun.REVIEW_CONFIRMED.equals(r.getReviewState()))
                .orElseThrow(() -> new NotFoundException("digitization_run not found: " + runId));
        documentScopeGuard.requireVisible(user, run.getDocumentId());
        return run;
    }

    /**
     * digitization-review-02-stt.md §4 bước 1 (US3 AC-2/AC-3/AC-4) - tier 2
     * validate, run BEFORE {@link DigitizationRunLifecycleService#reviseSegment}
     * (i.e. before any write) so a 422 leaves {@code current_time_*}
     * untouched (US3 AC-2, "rollback toàn bộ thao tác sửa của request"). The
     * DB trigger from {@code V27} ({@code
     * fn_check_segment_within_source_duration}) is the last-resort net for
     * other write paths (manual DB fixups) - a normal API call never
     * reaches it because this check runs first.
     */
    private void validateSttTimeBounds(
            DigitizationRun run, ContentSegment segment, Integer timeStartMs, Integer timeEndMs) {
        // digitization-pipeline-05-video-review.md: task=video sửa mốc thời gian như stt.
        if (!DigitizationRun.TASK_STT.equals(run.getTask()) && !DigitizationRun.TASK_VIDEO.equals(run.getTask())) {
            throw new BadRequestException("timeStartMs/timeEndMs chỉ áp dụng cho run task=stt/video");
        }
        Integer durationMs = reviewSegmentRepository.findSourceDurationMs(segment.getId());
        if (durationMs == null) {
            throw new UnprocessableEntityException(
                    "Chưa xác định được độ dài tệp gốc, không thể sửa mốc thời gian");
        }
        Integer effectiveStart = timeStartMs != null ? timeStartMs : segment.getCurrentTimeStartMs();
        Integer effectiveEnd = timeEndMs != null ? timeEndMs : segment.getCurrentTimeEndMs();
        if (effectiveStart == null || effectiveEnd == null) {
            throw new UnprocessableEntityException(
                    "Không xác định được mốc thời gian hiện tại của đoạn để đối chiếu");
        }
        if (effectiveStart < 0) {
            throw new UnprocessableEntityException("timeStartMs phải >= 0");
        }
        if (effectiveStart >= effectiveEnd) {
            throw new UnprocessableEntityException("timeStartMs phải nhỏ hơn timeEndMs");
        }
        if (effectiveEnd > durationMs) {
            throw new UnprocessableEntityException("timeEndMs vượt quá độ dài tệp gốc (durationMs)");
        }
    }

    private ContentSegment requireSegment(UUID segmentId) {
        return contentSegmentRepository.findById(segmentId)
                .orElseThrow(() -> new NotFoundException("content_segment not found: " + segmentId));
    }

    private Document requireDocument(UUID documentId) {
        return documentRepository.findById(documentId)
                .orElseThrow(() -> new NotFoundException("document not found: " + documentId));
    }

    /**
     * digitization-review-01-ocr.md §3.4/§4 bước 7 (Story 8 AC-2): the other
     * "active" run for {@code (document_id, task)} already {@code confirmed}
     * - excludes {@code run} itself (a run being re-opened for edits after
     * its own confirmation, US4 AC-2, must not be reported as "replacing
     * itself").
     */
    private java.util.Optional<DigitizationRun> findOtherConfirmedRun(DigitizationRun run) {
        return digitizationRunRepository
                .findByDocumentIdAndTaskAndReviewState(run.getDocumentId(), run.getTask(), DigitizationRun.REVIEW_CONFIRMED)
                .filter(other -> !other.getId().equals(run.getId()));
    }

    /** digitization-review-01-ocr.md §3.1 - one {@link DigitizationRun}, projected for the review UI. */
    private RunSummaryView toSummary(DigitizationRun run, Document document) {
        OffsetDateTime now = OffsetDateTime.now();
        long staleHours = systemConfigService.getLong(SystemConfigDefaults.OCR_STT_REVIEW_STALE_HOURS);
        long upcomingDeadlineHours =
                systemConfigService.getLong(SystemConfigDefaults.OCR_STT_REVIEW_UPCOMING_DEADLINE_HOURS);
        String effectiveStatus = ReviewEffectiveStatus.compute(
                run.getReviewState(), run.getFirstEditAt(), run.getReviewDeadlineAt(), now, staleHours,
                upcomingDeadlineHours);
        boolean isReplacingConfirmedRun = findOtherConfirmedRun(run).isPresent();

        return new RunSummaryView(
                run.getId(), run.getDocumentId(), document.getOriginalName(), run.getTask(), run.getReviewState(),
                effectiveStatus, run.getOutputQualityFlag(), run.getLowConfidenceCount(), run.getSegmentCount(),
                run.getReviewDeadlineAt(), run.getFirstEditAt(), isReplacingConfirmedRun,
                run.getProcessingStatus());
    }

    // ---- digitization-review-04-sed-summary.md §2.1/§2.2/§2.3 (SED/summary) ---

    /**
     * §2.1/§4 bước 1 (AC-1/AC-3 Story 9): {@code soundEventLabel} per segment,
     * only for {@code task=sed} - one bulk query for the whole run instead of
     * one per segment.
     */
    private Map<UUID, String> loadSoundEventLabels(DigitizationRun run, List<ContentSegment> segments) {
        if (!DigitizationRun.TASK_SED.equals(run.getTask()) || segments.isEmpty()) {
            return Map.of();
        }
        List<UUID> segmentIds = new ArrayList<>(segments.size());
        for (ContentSegment segment : segments) {
            segmentIds.add(segment.getId());
        }
        Map<UUID, String> result = new HashMap<>();
        for (SoundEvent soundEvent : soundEventRepository.findByPromotedSegmentIdIn(segmentIds)) {
            result.put(soundEvent.getPromotedSegmentId(), soundEvent.getLabel());
        }
        return result;
    }

    /**
     * §2.2/§4 bước 4 (AC-4 Story 10): {@code source_segment_ids} resolved to
     * their current row, keyed by the {@code content_source='summary'}
     * segment that references them. Only non-empty for {@code
     * task=video_summary} - a run normally has exactly 1 such segment (§4
     * bước 4), but this loops over every segment in the run defensively
     * (rủi ro mục 8: no DB constraint enforces that invariant).
     */
    private Map<UUID, List<ReviewSegmentRepository.SourceReferenceRow>> loadSourceReferenceRows(
            DigitizationRun run, List<ContentSegment> segments) {
        if (!DigitizationRun.TASK_VIDEO_SUMMARY.equals(run.getTask())) {
            return Map.of();
        }
        Map<UUID, List<ReviewSegmentRepository.SourceReferenceRow>> result = new LinkedHashMap<>();
        for (ContentSegment segment : segments) {
            List<UUID> sourceSegmentIds = parseSourceSegmentIds(segment.getSourceSegmentIds());
            result.put(segment.getId(), reviewSegmentRepository.resolveSourceReferences(sourceSegmentIds));
        }
        return result;
    }

    /** {@code content_segment.source_segment_ids} (JSONB array of UUID strings) -> parsed {@link UUID}s, skipping malformed entries. */
    private List<UUID> parseSourceSegmentIds(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<UUID> ids = new ArrayList<>(raw.size());
        for (String value : raw) {
            try {
                ids.add(UUID.fromString(value));
            } catch (IllegalArgumentException e) {
                log.warn("Invalid source_segment_ids entry '{}', skipping", value);
            }
        }
        return ids;
    }

    /** Internal {@link ReviewSegmentRepository.SourceReferenceRow} -> public {@link SourceReferenceView}, dropping the internal timestamp fields. */
    private static Map<UUID, List<SourceReferenceView>> toSourceReferenceViewMap(
            Map<UUID, List<ReviewSegmentRepository.SourceReferenceRow>> rowsBySegment) {
        Map<UUID, List<SourceReferenceView>> result = new HashMap<>();
        for (Map.Entry<UUID, List<ReviewSegmentRepository.SourceReferenceRow>> entry : rowsBySegment.entrySet()) {
            List<SourceReferenceView> views = new ArrayList<>(entry.getValue().size());
            for (ReviewSegmentRepository.SourceReferenceRow row : entry.getValue()) {
                views.add(new SourceReferenceView(
                        row.sourceSegmentId(), row.sourceRunId(), row.segmentIndex(), row.timeStartMs(),
                        row.sourceRunReviewState()));
            }
            result.put(entry.getKey(), views);
        }
        return result;
    }

    /**
     * §2.3 (AC-5 Story 10): {@code true} only for a {@code confirmed}
     * {@code task=video_summary} run whose referenced source segments (or the
     * STT run superseding one of them) were touched strictly after {@code
     * confirmed_at}. Not yet {@code confirmed} -> always {@code false}
     * (§3.1: "chưa confirmed thì chưa có mốc so sánh").
     */
    private boolean computeStaleWarning(
            DigitizationRun run, Map<UUID, List<ReviewSegmentRepository.SourceReferenceRow>> rowsBySegment) {
        if (!DigitizationRun.TASK_VIDEO_SUMMARY.equals(run.getTask())
                || !DigitizationRun.REVIEW_CONFIRMED.equals(run.getReviewState())
                || run.getConfirmedAt() == null) {
            return false;
        }
        OffsetDateTime staleTimestamp = null;
        for (List<ReviewSegmentRepository.SourceReferenceRow> rows : rowsBySegment.values()) {
            for (ReviewSegmentRepository.SourceReferenceRow row : rows) {
                staleTimestamp = laterOf(staleTimestamp, row.sourceSegmentUpdatedAt());
                if (DigitizationRun.REVIEW_SUPERSEDED.equals(row.sourceRunReviewState())) {
                    staleTimestamp = laterOf(staleTimestamp, row.sourceRunUpdatedAt());
                }
            }
        }
        return staleTimestamp != null && staleTimestamp.isAfter(run.getConfirmedAt());
    }

    private static OffsetDateTime laterOf(OffsetDateTime a, OffsetDateTime b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.isAfter(b) ? a : b;
    }
}
