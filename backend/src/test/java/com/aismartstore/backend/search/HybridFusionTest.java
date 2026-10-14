package com.aismartstore.backend.search;

import static org.assertj.core.api.Assertions.assertThat;

import com.aismartstore.backend.search.query.LexicalHit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** SPEC-search-semantic T12: RRF (k=60) + evidence threshold (AC-3). */
class HybridFusionTest {

    private static final double MIN = 0.50;
    private final UUID doc = UUID.randomUUID();

    private LexicalHit lex(String id) {
        return new LexicalHit(id, doc, 1.0);
    }

    private VectorHit vec(String id, double cosine) {
        return new VectorHit(id, doc, cosine);
    }

    @Test
    void segmentInBothBranchesOutranksSegmentInOne() {
        List<FusedHit> fused = HybridFusion.fuse(List.of(lex("a"), lex("b")), List.of(vec("b", 0.9), vec("c", 0.8)), MIN);

        assertThat(fused).extracting(FusedHit::segmentId).containsExactly("b", "a", "c");
        FusedHit b = fused.get(0);
        assertThat(b.rrf()).isCloseTo(1.0 / 62 + 1.0 / 61, org.assertj.core.data.Offset.offset(1e-12));
        assertThat(b.matchedLexical()).isTrue();
        assertThat(b.cosine()).isEqualTo(0.9);
    }

    @Test
    void vectorOnlyCandidateBelowThresholdIsDroppedButLexicalMatchIsKept() {
        List<FusedHit> fused = HybridFusion.fuse(List.of(lex("a")), List.of(vec("a", 0.20), vec("z", 0.49)), MIN);

        assertThat(fused).extracting(FusedHit::segmentId).containsExactly("a");
        assertThat(fused.get(0).cosine()).isEqualTo(0.20); // kept via lexical, cosine still reported
    }

    @Test
    void vectorOnlyCandidateAtThresholdIsKept() {
        List<FusedHit> fused = HybridFusion.fuse(List.of(), List.of(vec("a", 0.50)), MIN);
        assertThat(fused).extracting(FusedHit::segmentId).containsExactly("a");
        assertThat(fused.get(0).matchedLexical()).isFalse();
    }

    @Test
    void noEvidenceYieldsAnEmptyListAndNeverLowersTheThreshold() {
        assertThat(HybridFusion.fuse(List.of(), List.of(vec("a", 0.49), vec("b", 0.10)), MIN)).isEmpty();
        assertThat(HybridFusion.fuse(List.of(), List.of(), MIN)).isEmpty();
    }

    @Test
    void lexicalOnlyWhenTheVectorBranchFailedKeepsBm25Order() {
        List<FusedHit> fused = HybridFusion.fuse(List.of(lex("a"), lex("b"), lex("c")), null, MIN);
        assertThat(fused).extracting(FusedHit::segmentId).containsExactly("a", "b", "c");
        assertThat(fused).allMatch(FusedHit::matchedLexical).allMatch(h -> h.cosine() == null);
    }

    @Test
    void semanticOnlyWhenTheLexicalBranchFailedAppliesTheThresholdInCosineOrder() {
        List<FusedHit> fused = HybridFusion.fuse(null, List.of(vec("a", 0.9), vec("b", 0.6), vec("c", 0.3)), MIN);
        assertThat(fused).extracting(FusedHit::segmentId).containsExactly("a", "b");
    }

    @Test
    void duplicateIdsWithinABranchCountOnce() {
        List<FusedHit> fused = HybridFusion.fuse(List.of(lex("a"), lex("a")), List.of(vec("a", 0.9), vec("a", 0.8)), MIN);
        assertThat(fused).hasSize(1);
        assertThat(fused.get(0).rrf()).isCloseTo(2.0 / 61, org.assertj.core.data.Offset.offset(1e-12));
        assertThat(fused.get(0).cosine()).isEqualTo(0.9);
    }
}
