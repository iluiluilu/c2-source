package com.aismartstore.backend.digitization.review;

import static org.assertj.core.api.Assertions.assertThat;

import com.aismartstore.backend.digitization.results.ContentSegment;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * digitization-review-02-stt.md §3.2/§5 (US3 AC-5) - {@link
 * SegmentView#listWithOverlap(List)}, the read-time {@code overlapsAdjacent}
 * computation. digitization-review-04-sed-summary.md §3.2/§6 (Story 9/10) -
 * {@link SegmentView#listWithOverlap(List, Map, Map)}'s per-segment
 * enrichment.
 */
class SegmentViewTest {

    private static ContentSegment seg(int index, Integer start, Integer end) {
        ContentSegment segment = new ContentSegment();
        segment.setSegmentIndex(index);
        segment.setCurrentTimeStartMs(start);
        segment.setCurrentTimeEndMs(end);
        return segment;
    }

    private static ContentSegment segWithId(UUID id, int index) {
        ContentSegment segment = seg(index, null, null);
        segment.setId(id);
        return segment;
    }

    @Test
    void noOverlap_allFalse() {
        List<SegmentView> views = SegmentView.listWithOverlap(
                List.of(seg(0, 0, 1000), seg(1, 1000, 2000), seg(2, 2000, 3000)));

        assertThat(views).extracting(SegmentView::overlapsAdjacent).containsExactly(false, false, false);
    }

    @Test
    void overlappingNeighbours_bothFlaggedTrue() {
        List<SegmentView> views = SegmentView.listWithOverlap(
                List.of(seg(0, 0, 1500), seg(1, 1000, 2000), seg(2, 2000, 3000)));

        assertThat(views).extracting(SegmentView::overlapsAdjacent).containsExactly(true, true, false);
    }

    @Test
    void onlyComparesImmediateNeighbours_notEveryPairInTheRun() {
        // segment 0 [0,2000] and segment 2 [500,900] overlap in absolute time,
        // but they are not adjacent by index (segment 1 sits between them and
        // overlaps neither) - §3.2 only compares 2 neighbours, so none of the
        // three is flagged.
        List<SegmentView> views = SegmentView.listWithOverlap(
                List.of(seg(0, 0, 2000), seg(1, 2000, 3000), seg(2, 500, 900)));

        assertThat(views).extracting(SegmentView::overlapsAdjacent).containsExactly(false, false, false);
    }

    @Test
    void nullTimeFields_neverOverlap() {
        // task=ocr segments never have time_*, must not NPE and must be false.
        List<SegmentView> views = SegmentView.listWithOverlap(List.of(seg(0, null, null), seg(1, null, null)));

        assertThat(views).extracting(SegmentView::overlapsAdjacent).containsExactly(false, false);
    }

    @Test
    void touchingBoundary_isNotOverlap() {
        // end of one segment == start of the next: adjacent, not overlapping.
        List<SegmentView> views = SegmentView.listWithOverlap(List.of(seg(0, 0, 1000), seg(1, 1000, 2000)));

        assertThat(views).extracting(SegmentView::overlapsAdjacent).containsExactly(false, false);
    }

    // ---- digitization-review-04-sed-summary.md §3.2 (AC-1 Story 9, AC-4 Story 10) ----

    @Test
    void soundEventLabelMap_appliedPerSegmentId_missingEntryIsNull() {
        UUID labeled = UUID.randomUUID();
        UUID unlabeled = UUID.randomUUID();
        List<SegmentView> views = SegmentView.listWithOverlap(
                List.of(segWithId(labeled, 0), segWithId(unlabeled, 1)),
                Map.of(labeled, "Dog bark"), Map.of());

        assertThat(views).extracting(SegmentView::soundEventLabel).containsExactly("Dog bark", null);
    }

    @Test
    void sourceReferencesMap_appliedPerSegmentId_missingEntryIsNull() {
        UUID summarySegmentId = UUID.randomUUID();
        UUID otherSegmentId = UUID.randomUUID();
        SourceReferenceView reference =
                new SourceReferenceView(UUID.randomUUID(), UUID.randomUUID(), 3, 12000, "confirmed");
        List<SegmentView> views = SegmentView.listWithOverlap(
                List.of(segWithId(summarySegmentId, 0), segWithId(otherSegmentId, 1)),
                Map.of(), Map.of(summarySegmentId, List.of(reference)));

        assertThat(views.get(0).sourceReferences()).containsExactly(reference);
        assertThat(views.get(1).sourceReferences()).isNull();
    }

    @Test
    void singleArgOverload_leavesNewFieldsNull() {
        List<SegmentView> views = SegmentView.listWithOverlap(List.of(seg(0, null, null)));

        assertThat(views.get(0).soundEventLabel()).isNull();
        assertThat(views.get(0).sourceReferences()).isNull();
    }
}
