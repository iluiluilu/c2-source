package com.aismartstore.backend.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** SPEC-search-semantic Q-9 / query §4.2: k-NN plugin score -> cosine, per space type. */
class KnnScoreTest {

    @Test
    void cosinesimilScoreIsOnePlusCosOverTwo() {
        // plugin: score = (1 + cos) / 2  =>  cos = 2*score - 1
        assertThat(KnnScore.toCosine(KnnScore.SPACE_COSINESIMIL, 0.85)).isCloseTo(0.70, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(KnnScore.toCosine(KnnScore.SPACE_COSINESIMIL, 1.0)).isEqualTo(1.0);
        assertThat(KnnScore.toCosine(KnnScore.SPACE_COSINESIMIL, 0.5)).isEqualTo(0.0);
    }

    @Test
    void innerProductScoreForPositiveIpIsOnePlusIp() {
        // spike T4 on OpenSearch 2.17.1: true cos 0.7901 came back as _score 1.7901
        assertThat(KnnScore.toCosine(KnnScore.SPACE_INNERPRODUCT, 1.7901418)).isCloseTo(0.7901418, org.assertj.core.data.Offset.offset(1e-6));
        assertThat(KnnScore.toCosine(KnnScore.SPACE_INNERPRODUCT, 2.0)).isEqualTo(1.0);
        assertThat(KnnScore.toCosine(KnnScore.SPACE_INNERPRODUCT, 1.0)).isEqualTo(0.0);
    }

    @Test
    void innerProductScoreForNegativeIpIsInverse() {
        // plugin: score = 1 / (1 - ip) for ip < 0  =>  ip = 1 - 1/score
        assertThat(KnnScore.toCosine(KnnScore.SPACE_INNERPRODUCT, 0.5)).isCloseTo(-1.0, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(KnnScore.toCosine(KnnScore.SPACE_INNERPRODUCT, 1.0 / 1.4)).isCloseTo(-0.4, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void segmentIndexSpaceTypeIsInnerProductAfterTheSpike() {
        assertThat(KnnScore.SEGMENT_SPACE_TYPE).isEqualTo(KnnScore.SPACE_INNERPRODUCT);
        assertThat(KnnScore.toCosine(1.5)).isEqualTo(0.5);
    }

    @Test
    void unknownSpaceTypeIsRejected() {
        assertThatThrownBy(() -> KnnScore.toCosine("l2", 0.3)).isInstanceOf(IllegalArgumentException.class);
    }
}
