package com.aismartstore.backend.displayname;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aismartstore.backend.common.ApiException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
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

    @Test
    void usesTheSharedRedisCounterWhenAvailable() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.increment("namecheck:u1:ingest:0")).thenReturn(1L, 2L, 3L);
        NameCheckRateLimiter limiter = new NameCheckRateLimiter(redis, 2);

        limiter.check("u1", "ingest", 10);
        limiter.check("u1", "ingest", 20);
        assertThatThrownBy(() -> limiter.check("u1", "ingest", 30)).isInstanceOf(ApiException.class);
        verify(redis, times(1)).expire(eq("namecheck:u1:ingest:0"), any(Duration.class)); // only on count == 1
    }

    @Test
    void redisFailureFailsOpenToTheLocalWindow() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.increment(anyString())).thenThrow(new RedisConnectionFailureException("down"));
        NameCheckRateLimiter limiter = new NameCheckRateLimiter(redis, 1);

        assertThatCode(() -> limiter.check("u1", "ingest", 0)).doesNotThrowAnyException();
        assertThatThrownBy(() -> limiter.check("u1", "ingest", 1)).isInstanceOf(ApiException.class);
    }

    @Test
    void aHangingRedisFallsBackAfterTheOwnTimeout() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.increment(anyString())).thenAnswer(inv -> {
            Thread.sleep(3_000);
            return 1L;
        });
        NameCheckRateLimiter limiter = new NameCheckRateLimiter(redis, 5);

        long started = System.nanoTime();
        assertThatCode(() -> limiter.check("u1", "ingest", 0)).doesNotThrowAnyException();
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;
        org.assertj.core.api.Assertions.assertThat(elapsedMs).isLessThan(2_000);
    }
}
