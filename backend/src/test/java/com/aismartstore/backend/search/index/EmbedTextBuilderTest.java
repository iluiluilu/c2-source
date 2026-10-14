package com.aismartstore.backend.search.index;

import static org.assertj.core.api.Assertions.assertThat;

import com.aismartstore.backend.search.index.EmbedTextBuilder.EmbedItem;
import com.aismartstore.backend.search.index.EmbedTextBuilder.Seg;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** SPEC-search-semantic T8 (indexing §4.3, §5): neighbour window, budget, exclusions. */
class EmbedTextBuilderTest {

    private static final int CHUNK = 512;
    private static final int OVERLAP = 64; // = 42 words at 1.5 tokens/word

    private static Seg ocr(String id, int page, String text) {
        return new Seg(id, "ocr", page, text, true);
    }

    private static String words(String prefix, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= n; i++) {
            sb.append(prefix).append(i).append(' ');
        }
        return sb.toString().trim();
    }

    private static List<EmbedItem> build(List<Seg> run, String... targets) {
        return EmbedTextBuilder.build(run, Set.of(targets), CHUNK, OVERLAP);
    }

    @Test
    void ocrSegmentsOnTheSamePageGetTheNeighbourWindow() {
        List<Seg> run = List.of(ocr("a", 1, "alpha beta"), ocr("b", 1, "gamma delta"), ocr("c", 1, "epsilon zeta"));

        List<EmbedItem> items = build(run, "b");

        // target b + its two neighbours are re-embedded (FR-18 edit => itself and the 2 adjacent ones)
        assertThat(items).extracting(EmbedItem::segmentId).containsExactly("a", "b", "c");
        assertThat(items.get(1).text()).isEqualTo("alpha beta gamma delta epsilon zeta");
        assertThat(items.get(0).text()).isEqualTo("alpha beta gamma delta"); // first: no prev
        assertThat(items.get(2).text()).isEqualTo("gamma delta epsilon zeta"); // last: no next
    }

    @Test
    void ocrNeighboursOnAnotherPageAreNotMixedIn() {
        List<Seg> run = List.of(ocr("a", 1, "alpha"), ocr("b", 2, "beta"), ocr("c", 3, "gamma"));

        List<EmbedItem> items = build(run, "b");

        assertThat(items).extracting(EmbedItem::segmentId).containsExactly("b");
        assertThat(items.get(0).text()).isEqualTo("beta");
    }

    @Test
    void sttSegmentsUseRunAdjacency() {
        List<Seg> run = List.of(
                new Seg("a", "stt", null, "mot hai", true),
                new Seg("b", "stt", null, "ba bon", true));
        assertThat(build(run, "b").get(1).text()).isEqualTo("mot hai ba bon");
    }

    @Test
    void titleNoteAndSummaryNeverGetNeighbours() {
        List<Seg> run = List.of(
                new Seg("t", "title", null, "Tieu de", false),
                new Seg("s", "summary", null, "Tom tat", false),
                new Seg("n", "note", null, "Ghi chu", false));

        List<EmbedItem> items = build(run, "s");

        assertThat(items).hasSize(1);
        assertThat(items.get(0)).isEqualTo(new EmbedItem("s", "Tom tat"));
    }

    @Test
    void neighbourWindowIsCappedAtTheOverlapSize() {
        List<Seg> run = List.of(ocr("a", 1, words("p", 100)), ocr("b", 1, "own text"), ocr("c", 1, words("n", 100)));

        String text = build(run, "b").stream().filter(i -> i.segmentId().equals("b")).findFirst().orElseThrow().text();

        // overlap 64 tokens -> 42 words each side: tail of prev (p59..p100), head of next (n1..n42)
        assertThat(text).startsWith("p59 ").contains(" p100 own text n1 ").endsWith(" n42");
        assertThat(text.split("\\s+")).hasSize(42 + 2 + 42);
    }

    @Test
    void neighbourTextIsTrimmedFirstWhenTheTotalWouldExceedTheBudget() {
        // own = 300 words = 450 tokens; budget 62 tokens => 31 each side => 20 words each side
        List<Seg> run = List.of(ocr("a", 1, words("p", 100)), ocr("b", 1, words("o", 300)), ocr("c", 1, words("n", 100)));

        String text = build(run, "b").stream().filter(i -> i.segmentId().equals("b")).findFirst().orElseThrow().text();

        assertThat(text.split("\\s+")).hasSize(20 + 300 + 20);
        assertThat(EmbedTextBuilder.estimateTokens(text)).isLessThanOrEqualTo(CHUNK);
    }

    @Test
    void aSegmentLongerThanTheBudgetIsEmbeddedWholeWithoutNeighboursOrSubPoints() {
        String longText = words("w", 600); // 900 tokens > 512
        List<Seg> run = List.of(ocr("a", 1, "prev words"), ocr("b", 1, longText), ocr("c", 1, "next words"));

        List<EmbedItem> items = build(run, "b");

        EmbedItem b = items.stream().filter(i -> i.segmentId().equals("b")).findFirst().orElseThrow();
        assertThat(b.text()).isEqualTo(longText); // one vector for the whole content
        assertThat(items.stream().filter(i -> i.segmentId().equals("b"))).hasSize(1);
    }

    @Test
    void sedAndOtherNonEmbeddableSourcesAreNeverProduced() {
        List<Seg> run = List.of(
                new Seg("a", "sed", null, "tieng vo tay", true),
                new Seg("b", "video", null, "khung hinh", true));
        assertThat(build(run, "a", "b")).isEmpty();
        assertThat(EmbedTextBuilder.isEmbeddable("sed")).isFalse();
        assertThat(EmbedTextBuilder.isEmbeddable("ocr")).isTrue();
    }

    @Test
    void blankTextIsSkippedAndDoesNotLeakIntoNeighbours() {
        List<Seg> run = List.of(ocr("a", 1, "   "), ocr("b", 1, "noi dung"), ocr("c", 1, ""));
        List<EmbedItem> items = build(run, "b");
        assertThat(items).extracting(EmbedItem::segmentId).containsExactly("b");
        assertThat(items.get(0).text()).isEqualTo("noi dung");
    }

    @Test
    void aSingleSegmentRunWorks() {
        assertThat(build(List.of(ocr("a", 1, "mot minh")), "a")).containsExactly(new EmbedItem("a", "mot minh"));
    }

    @Test
    void unknownTargetsProduceNothing() {
        assertThat(build(List.of(ocr("a", 1, "x")), "zzz")).isEmpty();
    }
}
