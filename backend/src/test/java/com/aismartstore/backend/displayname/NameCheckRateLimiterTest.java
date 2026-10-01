package com.aismartstore.backend.displayname;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aismartstore.backend.common.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class NameCheckRateLimiterTest {

    @Test
    void allowsTheLimitThenAnswers429WithinTheWindow() {
        NameCheckRateLimiter limiter = new NameCheckRateLimiter(3);
        for (int i = 0; i < 3; i++) {
            limiter.check("u1", "ingest", 1_000L + i);
        }
        assertThatThrownBy(() -> limiter.check("u1", "ingest", 1_010L))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> org.assertj.core.api.Assertions.assertThat(e.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
    }

    @Test
    void countsPerUserAndPerEndpointSeparately() {
        NameCheckRateLimiter limiter = new NameCheckRateLimiter(1);
        limiter.check("u1", "ingest", 0);
        assertThatCode(() -> limiter.check("u2", "ingest", 1)).doesNotThrowAnyException();
        assertThatCode(() -> limiter.check("u1", "personal", 1)).doesNotThrowAnyException();
        assertThatThrownBy(() -> limiter.check("u1", "ingest", 2)).isInstanceOf(ApiException.class);
    }

    @Test
    void theWindowResetsAfterOneMinute() {
        NameCheckRateLimiter limiter = new NameCheckRateLimiter(1);
        limiter.check("u1", "ingest", 0);
        assertThatThrownBy(() -> limiter.check("u1", "ingest", 59_999)).isInstanceOf(ApiException.class);
        assertThatCode(() -> limiter.check("u1", "ingest", 60_000)).doesNotThrowAnyException();
    }
}
