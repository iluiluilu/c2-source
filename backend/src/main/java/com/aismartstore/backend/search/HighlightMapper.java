package com.aismartstore.backend.search;

import com.aismartstore.backend.digitization.results.ContentSegment;
import com.aismartstore.backend.digitization.review.SourceReferenceView;
import com.aismartstore.backend.search.dto.PositionInfo;
import com.aismartstore.backend.search.dto.SegmentHit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * search-fulltext-03-dinh-vi-ket-qua.md §4 step 1 - turns one OpenSearch
 * inner hit (the raw {@code segment} source plus its possibly-absent
 * highlight fragment) and its hydrated Postgres {@code content_segment} row
 * into the API's {@link SegmentHit}: the highlight fallback, the {@code
 * content_source} -> {@link PositionInfo} branch (§2/§4 step 1.3), and the
 * {@code <em>} offset math for OCR segments (§4 step 1.2). Called from {@link
 * SearchResultHydrator#hydrate} once a document has already passed the ACL
 * double-check.
 *
 * <p>search-fulltext-05-frontend.md §2/§4 bước 6.2 (Task 3b): also resolves
 * {@link SegmentHit#sourceReferences()} for {@code content_source="summary"}
 * segments - the actual Postgres lookup ({@code
 * ReviewSegmentRepository.resolveSourceReferences}) is batched once for the
 * whole page of results by {@link SearchResultHydrator}, never once per
 * segment here; this class only looks up the already-resolved map.
 */
@Component
public class HighlightMapper {

    private static final Logger log = LoggerFactory.getLogger(HighlightMapper.class);
    private static final int HIGHLIGHT_FALLBACK_LENGTH = 240;
    private static final String OPEN_TAG = "<em>";
    private static final String CLOSE_TAG = "</em>";

    /**
     * @param hit     the OpenSearch inner hit (source + highlight fragment, if any)
     * @param segment the matching Postgres {@code content_segment} row, or
     *                {@code null} if none exists (e.g. a {@code note}/{@code
     *                title} pseudo-segment not backed by that table - degrades
     *                to {@code position.type = "none"}, never a lookup failure)
     */
    public SegmentHit map(LexicalHit.LexicalSegmentHit hit, ContentSegment segment) {
        return map(hit, segment, Map.of());
    }

    /**
     * @param sourceReferencesBySourceSegmentId search-fulltext-05-frontend.md
     *     §4 bước 6.2 - every source segment id resolved for the current page
     *     of results (across every {@code summary} segment in it), keyed by
     *     the source segment's own id. Built once per {@code hydrate()} call
     *     by {@link SearchResultHydrator}, not by this method.
     */
    public SegmentHit map(
            LexicalHit.LexicalSegmentHit hit,
            ContentSegment segment,
            Map<UUID, SourceReferenceView> sourceReferencesBySourceSegmentId) {
        SegmentDocument source = hit.source();
        String highlight = hit.highlight();
        if ((highlight == null || highlight.isBlank()) && segment != null) {
            highlight = fallbackHighlight(segment.getCurrentText());
        }

        return new SegmentHit(
                source.getSegmentId(),
                source.getContentSource(),
                source.getConfirmSource(),
                highlight,
                toPosition(segment, hit.highlight()),
                resolveSourceReferences(segment, sourceReferencesBySourceSegmentId));
    }

    /**
     * §2/§4 bước 6.2: only {@code content_source="summary"} ever carries
     * {@code sourceReferences} - every other source, and a summary segment
     * whose ids don't resolve to anything, gets {@code null} (never an empty
     * list vs. {@code null} distinction the frontend has to care about).
     */
    private List<SourceReferenceView> resolveSourceReferences(
            ContentSegment segment, Map<UUID, SourceReferenceView> sourceReferencesBySourceSegmentId) {
        if (segment == null || !ContentSegment.SOURCE_SUMMARY.equals(segment.getContentSource())) {
            return null;
        }
        List<UUID> ids = parseSourceSegmentIds(segment.getSourceSegmentIds());
        if (ids.isEmpty()) {
            return null;
        }
        List<SourceReferenceView> resolved = new ArrayList<>();
        for (UUID id : ids) {
            SourceReferenceView view = sourceReferencesBySourceSegmentId.get(id);
            if (view != null) {
                resolved.add(view);
            }
        }
        return resolved.isEmpty() ? null : resolved;
    }

    /**
     * {@code content_segment.source_segment_ids} (JSONB array of UUID
     * strings) -> parsed {@link UUID}s, skipping malformed entries (mirrors
     * {@code ReviewController.parseSourceSegmentIds}).
     */
    static List<UUID> parseSourceSegmentIds(List<String> raw) {
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

    /**
     * §2/§4 step 1.3: {@code bbox_polygon} and the rest of a page/time
     * position never come from the OpenSearch hit source - only Postgres
     * {@code content_segment} is the field of record (§2.1 of {@code -01}).
     */
    private PositionInfo toPosition(ContentSegment segment, String rawHighlightFragment) {
        if (segment == null) {
            return none();
        }
        return switch (segment.getContentSource()) {
            case ContentSegment.SOURCE_OCR -> {
                int[] offsets = computeHighlightOffsets(rawHighlightFragment, segment.getCurrentText());
                yield new PositionInfo(
                        PositionInfo.TYPE_PAGE,
                        segment.getPageNo(),
                        segment.getLineFrom(),
                        segment.getLineTo(),
                        segment.getBboxPolygon(),
                        offsets == null ? null : offsets[0],
                        offsets == null ? null : offsets[1],
                        null,
                        null);
            }
            case ContentSegment.SOURCE_STT, ContentSegment.SOURCE_SED, ContentSegment.SOURCE_VIDEO -> new PositionInfo(
                    PositionInfo.TYPE_TIME,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    resolveTimeStart(segment),
                    resolveTimeEnd(segment));
            default -> none();
        };
    }

    private static PositionInfo none() {
        return new PositionInfo(PositionInfo.TYPE_NONE, null, null, null, null, null, null, null, null);
    }

    /**
     * §4 step 1.3 note (test plan §5 edge case): "hiện hành" always wins over
     * the AI-generated value. {@code current_time_*} is set equal to {@code
     * ai_time_*} at write time (digitization-results-store.md §4 step 3), so
     * it should never actually be null in practice - this falls back
     * defensively rather than surfacing a broken position if that invariant
     * is ever violated.
     */
    private static Integer resolveTimeStart(ContentSegment segment) {
        return segment.getCurrentTimeStartMs() != null ? segment.getCurrentTimeStartMs() : segment.getAiTimeStartMs();
    }

    private static Integer resolveTimeEnd(ContentSegment segment) {
        return segment.getCurrentTimeEndMs() != null ? segment.getCurrentTimeEndMs() : segment.getAiTimeEndMs();
    }

    /**
     * §4 step 1.2: OpenSearch's {@code unified} highlighter returns a
     * fragment with the match wrapped in {@code <em>...</em>} but no absolute
     * offset into the field - locate the fragment inside {@code current_text}
     * (stripping the tags first, since the fragment text is otherwise
     * verbatim) and add the {@code <em>} position within it. Only the first
     * {@code <em>...</em>} pair is used ({@code number_of_fragments=1} already
     * limits the fragment to a single match).
     *
     * @return {@code {offsetStart, offsetEnd}}, or {@code null} if there is
     *         no highlight or the fragment cannot be located verbatim in
     *         {@code current_text} - defensive, never blocks the rest of the
     *         segment from being returned (§5 test plan).
     */
    static int[] computeHighlightOffsets(String fragment, String currentText) {
        if (fragment == null || currentText == null) {
            return null;
        }
        int emStart = fragment.indexOf(OPEN_TAG);
        int emEnd = fragment.indexOf(CLOSE_TAG);
        if (emStart < 0 || emEnd < 0 || emEnd < emStart) {
            return null;
        }
        String plainFragment = fragment.replace(OPEN_TAG, "").replace(CLOSE_TAG, "");
        int fragmentOffsetInText = currentText.indexOf(plainFragment);
        if (fragmentOffsetInText < 0) {
            return null;
        }
        int highlightStartInFragment = emStart;
        int highlightEndInFragment = emEnd - OPEN_TAG.length();
        return new int[] {
            fragmentOffsetInText + highlightStartInFragment, fragmentOffsetInText + highlightEndInFragment
        };
    }

    private static String fallbackHighlight(String currentText) {
        if (currentText == null) {
            return null;
        }
        return currentText.length() > HIGHLIGHT_FALLBACK_LENGTH
                ? currentText.substring(0, HIGHLIGHT_FALLBACK_LENGTH) + "…"
                : currentText;
    }
}
