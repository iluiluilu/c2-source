package com.aismartstore.backend.search.index;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EmbedTextHashTest {

    private static Map<String, Object> source(String id, String contentSource, Integer page, String text, String runId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("segment_id", id);
        m.put("content_source", contentSource);
        m.put("page_no", page);
        m.put("text", text);
        m.put("run_id", runId);
        return m;
    }

    @Test
    void sha256IsStableAndDependsOnTheExactText() {
        assertThat(EmbedTextHash.of("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        assertThat(EmbedTextHash.of("abc ")).isNotEqualTo(EmbedTextHash.of("abc"));
    }

    @Test
    void annotateMatchesWhatTheConsumerHashesForTheSameSegments() {
        List<Map<String, Object>> sources = new ArrayList<>();
        sources.add(source("s1", "ocr", 1, "mot hai ba", "r1"));
        sources.add(source("s2", "ocr", 1, "bon nam sau", "r1"));
        sources.add(source("s3", "sed", null, "vo tay", "r1"));

        EmbedTextHash.annotate(sources, 512, 64);

        List<EmbedTextBuilder.Seg> segs = List.of(
                new EmbedTextBuilder.Seg("s1", "ocr", 1, "mot hai ba", true),
                new EmbedTextBuilder.Seg("s2", "ocr", 1, "bon nam sau", true),
                new EmbedTextBuilder.Seg("s3", "sed", null, "vo tay", false));
        Map<String, String> consumerSide = new LinkedHashMap<>();
        for (EmbedTextBuilder.EmbedItem item : EmbedTextBuilder.build(segs, java.util.Set.of("s1", "s2"), 512, 64)) {
            consumerSide.put(item.segmentId(), item.hash());
        }
        assertThat(sources.get(0)).containsEntry("embed_text_hash", consumerSide.get("s1"));
        assertThat(sources.get(1)).containsEntry("embed_text_hash", consumerSide.get("s2"));
        assertThat(sources.get(2)).doesNotContainKey("embed_text_hash"); // sed is never embedded
    }

    @Test
    void changingTheChunkConfigurationChangesTheHash() {
        List<Map<String, Object>> a = new ArrayList<>(List.of(
                source("s1", "ocr", 1, "mot hai ba bon nam sau bay tam chin muoi", "r1"),
                source("s2", "ocr", 1, "mot hai ba bon nam sau bay tam chin muoi", "r1")));
        List<Map<String, Object>> b = new ArrayList<>(List.of(
                source("s1", "ocr", 1, "mot hai ba bon nam sau bay tam chin muoi", "r1"),
                source("s2", "ocr", 1, "mot hai ba bon nam sau bay tam chin muoi", "r1")));
        EmbedTextHash.annotate(a, 512, 64);
        EmbedTextHash.annotate(b, 512, 0); // no overlap -> no neighbour context

        assertThat(a.get(0).get("embed_text_hash")).isNotEqualTo(b.get(0).get("embed_text_hash"));
    }
}
