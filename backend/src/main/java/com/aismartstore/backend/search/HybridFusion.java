package com.aismartstore.backend.search;

import com.aismartstore.backend.search.query.LexicalHit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SPEC-search-semantic T12 (search-semantic-query.md §4.1 step 5): Reciprocal Rank Fusion (k = 60,
 * no rerank) of the lexical and vector branches, keyed by {@code segment_id}, plus the evidence
 * threshold.
 *
 * <ul>
 *   <li>both branches alive: keep a candidate iff {@code cosine >= minScore} <b>or</b> it matched
 *       lexically; never keep a vector-only candidate below the threshold (AC-3);</li>
 *   <li>lexical only ({@code lexical_only}): every lexical hit in BM25 order;</li>
 *   <li>vector only ({@code semantic_only}): {@code cosine >= minScore}, in cosine order.</li>
 * </ul>
 * A {@code null} list means "that branch failed". The threshold is never lowered.
 */
public final class HybridFusion {

    public static final int RRF_K = 60;

    private HybridFusion() {
    }

    public static List<FusedHit> fuse(List<LexicalHit> lexical, List<VectorHit> vector, double minScore) {
        Map<String, Acc> byId = new LinkedHashMap<>();

        if (lexical != null) {
            int rank = 1;
            for (LexicalHit hit : lexical) {
                Acc acc = byId.get(hit.segmentId());
                if (acc != null && acc.matchedLexical) {
                    continue; // duplicate id within the branch - keep the best (first) rank only
                }
                if (acc == null) {
                    acc = new Acc(hit.segmentId(), hit.documentId());
                    byId.put(hit.segmentId(), acc);
                }
                acc.matchedLexical = true;
                acc.rrf += 1.0 / (RRF_K + rank);
                acc.lexicalRank = rank;
                rank++;
            }
        }
        if (vector != null) {
            int rank = 1;
            for (VectorHit hit : vector) {
                Acc acc = byId.get(hit.segmentId());
                if (acc != null && acc.cosine != null) {
                    continue;
                }
                if (acc == null) {
                    acc = new Acc(hit.segmentId(), hit.documentId());
                    byId.put(hit.segmentId(), acc);
                }
                acc.cosine = hit.cosine();
                acc.rrf += 1.0 / (RRF_K + rank);
                acc.vectorRank = rank;
                rank++;
            }
        }

        List<Acc> kept = new ArrayList<>();
        for (Acc acc : byId.values()) {
            boolean keep;
            if (lexical == null) {
                keep = acc.cosine != null && acc.cosine >= minScore;
            } else if (vector == null) {
                keep = acc.matchedLexical;
            } else {
                keep = acc.matchedLexical || (acc.cosine != null && acc.cosine >= minScore);
            }
            if (keep) {
                kept.add(acc);
            }
        }

        // Highest fused rank first; deterministic tie-breaks (better lexical rank, then better vector rank).
        kept.sort(Comparator.comparingDouble((Acc a) -> a.rrf).reversed()
                .thenComparingInt(a -> a.lexicalRank)
                .thenComparingInt(a -> a.vectorRank));
        return kept.stream()
                .map(a -> new FusedHit(a.segmentId, a.documentId, a.cosine, a.matchedLexical, a.rrf))
                .toList();
    }

    private static final class Acc {
        final String segmentId;
        final java.util.UUID documentId;
        Double cosine;
        boolean matchedLexical;
        double rrf;
        int lexicalRank = Integer.MAX_VALUE;
        int vectorRank = Integer.MAX_VALUE;

        Acc(String segmentId, java.util.UUID documentId) {
            this.segmentId = segmentId;
            this.documentId = documentId;
        }
    }
}
