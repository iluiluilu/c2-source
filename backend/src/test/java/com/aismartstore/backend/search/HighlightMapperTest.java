package com.aismartstore.backend.search;

import static org.assertj.core.api.Assertions.assertThat;

import com.aismartstore.backend.digitization.results.ContentSegment;
import com.aismartstore.backend.digitization.review.SourceReferenceView;
import com.aismartstore.backend.search.dto.PositionInfo;
import com.aismartstore.backend.search.dto.SegmentHit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * search-fulltext-03-dinh-vi-ket-qua.md §5 - unit coverage of {@link
 * HighlightMapper}: one {@link PositionInfo} variant per {@code
 * content_source}, the {@code <em>} offset math (§4 step 1.2), and the edge
 * cases called out explicitly in the test plan (missing {@code bbox_polygon},
 * a null {@code current_time_*} falling back to {@code ai_time_*}).
 */
class HighlightMapperTest {

    private final HighlightMapper mapper = new HighlightMapper();

    // ---- content_source=ocr -> position.type=page, with bbox/line/offsets from Postgres ----

    @Test
    void ocrSegmentMapsToAPagePositionWithBboxAndHighlightOffsets() {
        ContentSegment segment = ocrSegment();
        segment.setCurrentText("Hop dong nay quy dinh viec thue nha giua hai ben.");
        LexicalHit.LexicalSegmentHit hit =
                hit(segment, "Hop dong nay quy dinh viec <em>thue nha</em> giua hai ben.");

        SegmentHit result = mapper.map(hit, segment);

        assertThat(result.position().type()).isEqualTo(PositionInfo.TYPE_PAGE);
        assertThat(result.position().pageNo()).isEqualTo(3);
        assertThat(result.position().lineFrom()).isEqualTo(10);
        assertThat(result.position().lineTo()).isEqualTo(12);
        assertThat(result.position().bboxPolygon()).isEqualTo(List.of(List.of(0.12, 0.30), List.of(0.62, 0.35)));
        // "Hop dong nay quy dinh viec " is 27 characters long, "thue nha" is 8.
        assertThat(result.position().highlightOffsetStart()).isEqualTo(27);
        assertThat(result.position().highlightOffsetEnd()).isEqualTo(35);
        assertThat(result.position().timeStartMs()).isNull();
        assertThat(result.position().timeEndMs()).isNull();
    }

    // ---- content_source=ocr, bbox_polygon missing (old/broken data) -> degrade, don't throw ----

    @Test
    void ocrSegmentWithoutBboxPolygonDegradesGracefullyInsteadOfThrowing() {
        ContentSegment segment = ocrSegment();
        segment.setBboxPolygon(null);
        segment.setCurrentText("Van ban khong co toa do bbox trong du lieu cu.");
        LexicalHit.LexicalSegmentHit hit = hit(segment, "Van ban khong co toa do <em>bbox</em> trong du lieu cu.");

        SegmentHit result = mapper.map(hit, segment);

        assertThat(result.position().type()).isEqualTo(PositionInfo.TYPE_PAGE);
        assertThat(result.position().bboxPolygon()).isNull();
        assertThat(result.position().pageNo()).isEqualTo(3);
        assertThat(result.position().lineFrom()).isEqualTo(10);
        assertThat(result.position().lineTo()).isEqualTo(12);
    }

    // ---- content_source=stt -> position.type=time, current_time_* preferred over ai_time_* ----

    @Test
    void sttSegmentMapsToATimePositionUsingCurrentTimeWhenPresent() {
        ContentSegment segment = new ContentSegment();
        segment.setContentSource(ContentSegment.SOURCE_STT);
        segment.setCurrentText("noi dung stt");
        segment.setAiTimeStartMs(10_000);
        segment.setAiTimeEndMs(15_000);
        segment.setCurrentTimeStartMs(12_000);
        segment.setCurrentTimeEndMs(18_500);
        LexicalHit.LexicalSegmentHit hit = hit(segment, "<em>noi dung</em> stt");

        SegmentHit result = mapper.map(hit, segment);

        assertThat(result.position().type()).isEqualTo(PositionInfo.TYPE_TIME);
        assertThat(result.position().timeStartMs()).isEqualTo(12_000);
        assertThat(result.position().timeEndMs()).isEqualTo(18_500);
        assertThat(result.position().pageNo()).isNull();
        assertThat(result.position().bboxPolygon()).isNull();
    }

    // ---- edge case §5: current_time_* null (never edited) -> falls back to ai_time_* ----

    @Test
    void sttSegmentFallsBackToAiTimeWhenCurrentTimeIsNull() {
        ContentSegment segment = new ContentSegment();
        segment.setContentSource(ContentSegment.SOURCE_STT);
        segment.setCurrentText("noi dung stt chua sua");
        segment.setAiTimeStartMs(10_000);
        segment.setAiTimeEndMs(15_000);
        segment.setCurrentTimeStartMs(null);
        segment.setCurrentTimeEndMs(null);
        LexicalHit.LexicalSegmentHit hit = hit(segment, "<em>noi dung</em> stt chua sua");

        SegmentHit result = mapper.map(hit, segment);

        assertThat(result.position().timeStartMs()).isEqualTo(10_000);
        assertThat(result.position().timeEndMs()).isEqualTo(15_000);
    }

    // ---- content_source=sed -> also time, same as stt (spec §2: "hợp lý là Time, giống stt") ----

    @Test
    void sedSegmentAlsoMapsToATimePosition() {
        ContentSegment segment = new ContentSegment();
        segment.setContentSource(ContentSegment.SOURCE_SED);
        segment.setCurrentText("tieng cho sua");
        segment.setCurrentTimeStartMs(1_000);
        segment.setCurrentTimeEndMs(2_000);
        LexicalHit.LexicalSegmentHit hit = hit(segment, "<em>tieng cho</em> sua");

        SegmentHit result = mapper.map(hit, segment);

        assertThat(result.position().type()).isEqualTo(PositionInfo.TYPE_TIME);
        assertThat(result.position().timeStartMs()).isEqualTo(1_000);
        assertThat(result.position().timeEndMs()).isEqualTo(2_000);
    }

    // ---- search-fulltext-05-frontend.md §2/§5: content_source=summary -> sourceReferences ----

    @Test
    void summarySegmentWithResolvableSourceSegmentIdsGetsSourceReferences() {
        UUID sourceSegmentId1 = UUID.randomUUID();
        UUID sourceSegmentId2 = UUID.randomUUID();
        UUID sourceRunId = UUID.randomUUID();
        ContentSegment segment = new ContentSegment();
        segment.setContentSource(ContentSegment.SOURCE_SUMMARY);
        segment.setCurrentText("Chu de chinh la du an so hoa");
        segment.setSourceSegmentIds(List.of(sourceSegmentId1.toString(), sourceSegmentId2.toString()));
        LexicalHit.LexicalSegmentHit hit = hit(segment, "Chu de chinh la du an <em>so hoa</em>");

        SourceReferenceView view1 = new SourceReferenceView(sourceSegmentId1, sourceRunId, 1, 2000, "confirmed");
        Map<UUID, SourceReferenceView> resolved = Map.of(sourceSegmentId1, view1);
        // sourceSegmentId2 deliberately absent - resolves to nothing (e.g. deleted since), skipped silently.

        SegmentHit result = mapper.map(hit, segment, resolved);

        assertThat(result.sourceReferences()).containsExactly(view1);
        assertThat(result.position().type()).isEqualTo(PositionInfo.TYPE_NONE);
    }

    @Test
    void summarySegmentWithNoResolvableSourceSegmentIdsGetsNullSourceReferences() {
        UUID sourceSegmentId = UUID.randomUUID();
        ContentSegment segment = new ContentSegment();
        segment.setContentSource(ContentSegment.SOURCE_SUMMARY);
        segment.setCurrentText("Chu de chinh la du an so hoa");
        segment.setSourceSegmentIds(List.of(sourceSegmentId.toString()));
        LexicalHit.LexicalSegmentHit hit = hit(segment, "Chu de chinh la du an <em>so hoa</em>");

        SegmentHit result = mapper.map(hit, segment, Map.of());

        assertThat(result.sourceReferences()).isNull();
    }

    @Test
    void summarySegmentWithNoSourceSegmentIdsAtAllGetsNullSourceReferences() {
        ContentSegment segment = new ContentSegment();
        segment.setContentSource(ContentSegment.SOURCE_SUMMARY);
        segment.setCurrentText("Chu de chinh la du an so hoa");
        segment.setSourceSegmentIds(null);
        LexicalHit.LexicalSegmentHit hit = hit(segment, "Chu de chinh la du an <em>so hoa</em>");

        SegmentHit result = mapper.map(hit, segment, Map.of());

        assertThat(result.sourceReferences()).isNull();
    }

    @Test
    void nonSummarySegmentsAlwaysGetNullSourceReferencesEvenIfTheLookupMapIsNonEmpty() {
        ContentSegment segment = ocrSegment();
        segment.setCurrentText("Hop dong nay quy dinh viec thue nha giua hai ben.");
        LexicalHit.LexicalSegmentHit hit = hit(segment, "Hop dong nay quy dinh viec <em>thue nha</em> giua hai ben.");
        UUID someId = UUID.randomUUID();
        Map<UUID, SourceReferenceView> resolved =
                Map.of(someId, new SourceReferenceView(someId, UUID.randomUUID(), 0, 0, "confirmed"));

        SegmentHit result = mapper.map(hit, segment, resolved);

        assertThat(result.sourceReferences()).isNull();
    }

    @Test
    void twoArgOverloadStillWorksAndAlwaysReturnsNullSourceReferences() {
        ContentSegment segment = new ContentSegment();
        segment.setContentSource(ContentSegment.SOURCE_SUMMARY);
        segment.setCurrentText("Chu de chinh");
        segment.setSourceSegmentIds(List.of(UUID.randomUUID().toString()));
        LexicalHit.LexicalSegmentHit hit = hit(segment, "Chu de <em>chinh</em>");

        SegmentHit result = mapper.map(hit, segment);

        assertThat(result.sourceReferences()).isNull();
    }

    // ---- content_source=note/title -> position.type=none, no page/time assumed ----

    @Test
    void noteSegmentMapsToNonePosition() {
        ContentSegment segment = new ContentSegment();
        segment.setContentSource(ContentSegment.SOURCE_SUMMARY); // any non-ocr/stt/sed source degrades to none
        segment.setCurrentText("ghi chu ve tai lieu nay");
        LexicalHit.LexicalSegmentHit hit = hit(segment, "ghi chu ve <em>tai lieu</em> nay");

        SegmentHit result = mapper.map(hit, segment);

        assertThat(result.position().type()).isEqualTo(PositionInfo.TYPE_NONE);
        assertThat(result.position().pageNo()).isNull();
        assertThat(result.position().timeStartMs()).isNull();
        assertThat(result.position().bboxPolygon()).isNull();
    }

    // ---- no matching content_segment row at all (e.g. a note/title pseudo-segment) -> degrade to none ----

    @Test
    void missingContentSegmentDegradesToNonePositionInsteadOfFailing() {
        SegmentDocument source = segmentDocument("title");
        LexicalHit.LexicalSegmentHit hit = new LexicalHit.LexicalSegmentHit(source, "Hop_dong_<em>thue</em>_nha_2024.pdf");

        SegmentHit result = mapper.map(hit, null);

        assertThat(result.position().type()).isEqualTo(PositionInfo.TYPE_NONE);
        assertThat(result.highlight()).isEqualTo("Hop_dong_<em>thue</em>_nha_2024.pdf");
    }

    // ---- highlight fallback: no highlight fragment at all -> truncated current_text, no offsets computed ----

    @Test
    void fallsBackToATruncatedCurrentTextWhenThereIsNoHighlightFragment() {
        ContentSegment segment = ocrSegment();
        segment.setCurrentText("a".repeat(300));
        LexicalHit.LexicalSegmentHit hit = new LexicalHit.LexicalSegmentHit(segmentDocument("ocr"), null);

        SegmentHit result = mapper.map(hit, segment);

        assertThat(result.highlight()).hasSize(241); // 240 chars + the ellipsis marker
        assertThat(result.position().highlightOffsetStart()).isNull();
        assertThat(result.position().highlightOffsetEnd()).isNull();
    }

    // ---- §4 step 1.2 offset math, tested directly: first <em>...</em> only, mid-text fragment ----

    @Test
    void computesOffsetsForTheFirstHighlightOnlyWhenTheFragmentIsInTheMiddleOfALongerText() {
        String currentText = "mo dau khong lien quan. " + "day la doan can tim ".repeat(3)
                + "va con nhieu doan can tim khac nua o day.";
        String fragment = "day la doan can <em>tim</em> day la doan can <em>tim</em> ";

        int[] offsets = HighlightMapper.computeHighlightOffsets(fragment, currentText);

        assertThat(offsets).isNotNull();
        int expectedFragmentStart = currentText.indexOf("day la doan can tim");
        assertThat(offsets[0]).isEqualTo(expectedFragmentStart + "day la doan can ".length());
        assertThat(offsets[1]).isEqualTo(offsets[0] + "tim".length());
    }

    @Test
    void computeOffsetsReturnsNullWhenTheFragmentCannotBeLocatedInTheCurrentText() {
        // Defensive - never blocks the rest of the segment (§5 test plan).
        assertThat(HighlightMapper.computeHighlightOffsets("<em>khong khop</em>", "van ban hoan toan khac")).isNull();
        assertThat(HighlightMapper.computeHighlightOffsets(null, "text")).isNull();
        assertThat(HighlightMapper.computeHighlightOffsets("no em tag here", "no em tag here")).isNull();
    }

    private static ContentSegment ocrSegment() {
        ContentSegment segment = new ContentSegment();
        segment.setContentSource(ContentSegment.SOURCE_OCR);
        segment.setPageNo(3);
        segment.setLineFrom(10);
        segment.setLineTo(12);
        segment.setBboxPolygon(List.of(List.of(0.12, 0.30), List.of(0.62, 0.35)));
        return segment;
    }

    private static LexicalHit.LexicalSegmentHit hit(ContentSegment segment, String highlight) {
        return new LexicalHit.LexicalSegmentHit(segmentDocument(segment.getContentSource()), highlight);
    }

    private static SegmentDocument segmentDocument(String contentSource) {
        SegmentDocument source = new SegmentDocument();
        source.setSegmentId(java.util.UUID.randomUUID().toString());
        source.setDocumentId(java.util.UUID.randomUUID().toString());
        source.setContentSource(contentSource);
        source.setConfirmSource("manual");
        return source;
    }
}
