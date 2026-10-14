package com.aismartstore.backend.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aismartstore.backend.common.ApiException;
import com.aismartstore.backend.common.TooManyRequestsException;
import com.aismartstore.backend.config.SemanticProperties;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class SemanticQueryLimiterTest {

    private static SemanticQueryLimiter limiter(int permits) {
        return new SemanticQueryLimiter(new SemanticProperties(true, permits, 500, 600000, 500, 1, 5, 3600000L));
    }

    @Test
    void ninthConcurrentQueryGets429WithTheVerbatimMessage() {
        SemanticQueryLimiter limiter = limiter(8);
        for (int i = 0; i < 8; i++) {
            limiter.acquireOrThrow();
        }

        assertThatThrownBy(limiter::acquireOrThrow)
                .isInstanceOf(TooManyRequestsException.class)
                .satisfies(e -> {
                    ApiException api = (ApiException) e;
                    assertThat(api.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                    assertThat(api.getMessage()).isEqualTo("Hệ thống tra cứu ngữ nghĩa đang bận, vui lòng thử lại sau ít giây.");
                    assertThat(((Map<?, ?>) api.getBody()).get("code")).isEqualTo("SEMANTIC_BUSY");
                });
    }

    @Test
    void releaseFreesAPermit() {
        SemanticQueryLimiter limiter = limiter(1);
        limiter.acquireOrThrow();
        limiter.release();
        limiter.acquireOrThrow();
        assertThat(limiter.availablePermits()).isZero();
    }
}
