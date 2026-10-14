package com.aismartstore.backend.search;

/**
 * SPEC-search-semantic T11 / Q-9: converts the {@code _score} the OpenSearch k-NN plugin returns
 * back to a cosine similarity, so {@code semantic.min_score} is compared against a real cosine.
 *
 * <p>Spike T4 (OpenSearch 2.17.1, faiss HNSW {@code on_disk} 32x): {@code cosinesimil} is rejected
 * for faiss HNSW ("does not support space type"), so the index uses {@code innerproduct}. The
 * embedding endpoint returns L2-normalised vectors (norm = 1.0, measured 2026-10-10), hence inner
 * product == cosine. The plugin's innerproduct score is {@code 1 + ip} when {@code ip >= 0} and
 * {@code 1 / (1 - ip)} otherwise.
 */
public final class KnnScore {

    public static final String SPACE_COSINESIMIL = "cosinesimil";
    public static final String SPACE_INNERPRODUCT = "innerproduct";
    /** The space type of {@code segment-v2-index.json} - keep in sync. */
    public static final String SEGMENT_SPACE_TYPE = SPACE_INNERPRODUCT;

    private KnnScore() {
    }

    public static double toCosine(String spaceType, double score) {
        return switch (spaceType) {
            // plugin: score = (1 + cos) / 2
            case SPACE_COSINESIMIL -> 2.0 * score - 1.0;
            case SPACE_INNERPRODUCT -> score >= 1.0 ? score - 1.0 : (score <= 0.0 ? -1.0 : 1.0 - 1.0 / score);
            default -> throw new IllegalArgumentException("Unsupported k-NN space type: " + spaceType);
        };
    }

    public static double toCosine(double score) {
        return toCosine(SEGMENT_SPACE_TYPE, score);
    }
}
