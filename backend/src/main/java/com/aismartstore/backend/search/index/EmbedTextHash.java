package com.aismartstore.backend.search.index;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * backend-multi-instance Task 14 (AC-17): the single hash function shared by the lexical indexer (writes
 * {@code embed_text_hash} on the segment) and the embed consumer (conditional vector write). Both sides MUST use
 * the same {@code chunkSizeTokens}/{@code overlapTokens}/{@code EMBEDDABLE_SOURCES}, otherwise every vector write
 * is skipped until a lexical reindex (see spec R5b).
 */
public final class EmbedTextHash {

    private EmbedTextHash() {
    }

    /** SHA-256 hex of the exact {@code text_embed} sent to the embedding endpoint. */
    public static String of(String textEmbed) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(textEmbed.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Adds {@code embed_text_hash} to every embeddable, non-blank source of {@code sources} (lexical segment maps,
     * in {@code segment_index} order per run). Neighbour context is resolved inside one run ({@code run_id}), or
     * within the whole list for run-less sources (title/note: they have no neighbours anyway).
     */
    public static void annotate(List<Map<String, Object>> sources, int chunkSizeTokens, int overlapTokens) {
        Map<String, List<Map<String, Object>>> byRun = new LinkedHashMap<>();
        for (Map<String, Object> source : sources) {
            Object runId = source.get("run_id");
            byRun.computeIfAbsent(runId == null ? "" : String.valueOf(runId), k -> new ArrayList<>()).add(source);
        }
        for (List<Map<String, Object>> group : byRun.values()) {
            List<EmbedTextBuilder.Seg> segs = new ArrayList<>();
            Set<String> ids = new HashSet<>();
            Map<String, Map<String, Object>> byId = new LinkedHashMap<>();
            for (Map<String, Object> source : group) {
                String id = String.valueOf(source.get("segment_id"));
                String contentSource = (String) source.get("content_source");
                Object text = source.get("text");
                Object pageNo = source.get("page_no");
                boolean neighbours = "ocr".equals(contentSource) || "stt".equals(contentSource);
                segs.add(new EmbedTextBuilder.Seg(
                        id,
                        contentSource,
                        pageNo instanceof Number n ? n.intValue() : null,
                        text == null ? null : String.valueOf(text),
                        neighbours));
                ids.add(id);
                byId.put(id, source);
            }
            for (EmbedTextBuilder.EmbedItem item : EmbedTextBuilder.build(segs, ids, chunkSizeTokens, overlapTokens)) {
                byId.get(item.segmentId()).put("embed_text_hash", item.hash());
            }
        }
    }
}
