package com.aismartstore.backend.search.index;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * SPEC-search-semantic T8 (indexing §4.3): builds the text that is embedded for a segment,
 * {@code tail(prev, overlap) + text(segment) + head(next, overlap)}. Pure, no I/O.
 *
 * <ul>
 *   <li>Neighbours are the adjacent segments of the SAME run (same document) and the same physical
 *       unit: OCR - same {@code page_no}; STT - adjacent in time (the run order). {@code
 *       title}/{@code note}/{@code summary} ({@code position_type=none}) never get neighbours.</li>
 *   <li>Neighbour text is trimmed first when the total would exceed {@code chunk-size-tokens}; if the
 *       segment alone exceeds it, the segment is embedded whole (no neighbours, no sub-points).</li>
 *   <li>Editing one segment re-embeds it and its two neighbours ({@link #build}'s expansion).</li>
 *   <li>Blank text is skipped (no call to the endpoint). {@code sed} and other non-embeddable
 *       sources are never produced.</li>
 *   <li>Document text is never wrapped with an instruction (only the question is).</li>
 * </ul>
 * Token counting is a conservative heuristic (no Qwen3 tokenizer on the JVM): 1.5 tokens per
 * whitespace-separated word, which over-counts for Vietnamese syllables; the 512 budget is far
 * below the model's 32k context (R9).
 */
public final class EmbedTextBuilder {

    /** The only {@code content_source} values that are embedded (AC-2.5: no {@code sed}). */
    public static final Set<String> EMBEDDABLE_SOURCES = Set.of("ocr", "stt", "note", "title", "summary");

    static final double TOKENS_PER_WORD = 1.5;

    private EmbedTextBuilder() {
    }

    /**
     * One segment of a run, in {@code segment_index} order.
     *
     * @param id            segment id as indexed ({@code content_segment} UUID or synthetic)
     * @param pageNo        for OCR neighbour grouping, else {@code null}
     * @param hasNeighbours {@code false} for title/note/summary
     */
    public record Seg(String id, String contentSource, Integer pageNo, String text, boolean hasNeighbours) {
    }

    /** One text to embed for the given segment id. */
    public record EmbedItem(String segmentId, String text, String hash) {
        /** backend-multi-instance Task 14: the hash is always derived from the exact text to embed. */
        public EmbedItem(String segmentId, String text) {
            this(segmentId, text, EmbedTextHash.of(text));
        }
    }

    public static boolean isEmbeddable(String contentSource) {
        return contentSource != null && EMBEDDABLE_SOURCES.contains(contentSource);
    }

    /**
     * @param run      all non-deleted segments of one run, ordered by {@code segment_index}
     * @param targets  ids whose vector must be (re)computed; their neighbours are re-embedded as well
     * @return items in run order, embeddable and non-blank only
     */
    public static List<EmbedItem> build(
            List<Seg> run, Set<String> targets, int chunkSizeTokens, int overlapTokens) {
        Set<Integer> wanted = new LinkedHashSet<>();
        for (int i = 0; i < run.size(); i++) {
            if (!targets.contains(run.get(i).id())) {
                continue;
            }
            wanted.add(i);
            if (run.get(i).hasNeighbours()) {
                if (i > 0 && sameUnit(run.get(i - 1), run.get(i))) {
                    wanted.add(i - 1);
                }
                if (i + 1 < run.size() && sameUnit(run.get(i), run.get(i + 1))) {
                    wanted.add(i + 1);
                }
            }
        }

        List<Integer> ordered = new ArrayList<>(wanted);
        java.util.Collections.sort(ordered);
        List<EmbedItem> items = new ArrayList<>();
        for (int i : ordered) {
            Seg seg = run.get(i);
            if (!isEmbeddable(seg.contentSource()) || isBlank(seg.text())) {
                continue;
            }
            String prev = seg.hasNeighbours() && i > 0 && sameUnit(run.get(i - 1), seg) ? neighbourText(run.get(i - 1)) : null;
            String next = seg.hasNeighbours() && i + 1 < run.size() && sameUnit(seg, run.get(i + 1))
                    ? neighbourText(run.get(i + 1))
                    : null;
            items.add(new EmbedItem(seg.id(), compose(prev, seg.text(), next, chunkSizeTokens, overlapTokens)));
        }
        return items;
    }

    static String compose(String prev, String own, String next, int chunkSizeTokens, int overlapTokens) {
        int ownTokens = estimateTokens(own);
        int budget = chunkSizeTokens - ownTokens;
        if (budget <= 0 || (prev == null && next == null)) {
            return own; // too long for neighbours: embed the whole segment content, never sub-point
        }
        int sides = (prev != null ? 1 : 0) + (next != null ? 1 : 0);
        int perSide = Math.min(overlapTokens, budget / sides);
        int words = (int) (perSide / TOKENS_PER_WORD);
        if (words <= 0) {
            return own;
        }
        StringBuilder sb = new StringBuilder();
        if (prev != null) {
            String tail = tailWords(prev, words);
            if (!tail.isEmpty()) {
                sb.append(tail).append(' ');
            }
        }
        sb.append(own);
        if (next != null) {
            String head = headWords(next, words);
            if (!head.isEmpty()) {
                sb.append(' ').append(head);
            }
        }
        return sb.toString();
    }

    static int estimateTokens(String text) {
        if (isBlank(text)) {
            return 0;
        }
        return (int) Math.ceil(text.trim().split("\\s+").length * TOKENS_PER_WORD);
    }

    static String tailWords(String text, int words) {
        String[] parts = text.trim().split("\\s+");
        int from = Math.max(0, parts.length - words);
        return String.join(" ", java.util.Arrays.copyOfRange(parts, from, parts.length));
    }

    static String headWords(String text, int words) {
        String[] parts = text.trim().split("\\s+");
        return String.join(" ", java.util.Arrays.copyOfRange(parts, 0, Math.min(parts.length, words)));
    }

    private static String neighbourText(Seg seg) {
        // a neighbour that is not itself embeddable (sed/video) must not leak into another segment's text
        return isEmbeddable(seg.contentSource()) && !isBlank(seg.text()) ? seg.text() : null;
    }

    private static boolean sameUnit(Seg a, Seg b) {
        if (!a.hasNeighbours() || !b.hasNeighbours()) {
            return false;
        }
        if ("ocr".equals(a.contentSource()) || "ocr".equals(b.contentSource())) {
            return a.pageNo() != null && a.pageNo().equals(b.pageNo());
        }
        return true; // stt: adjacent in time = adjacent in run order
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
