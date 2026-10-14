package com.aismartstore.backend.displayname;

import com.aismartstore.backend.common.ApiException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * SPEC BE-6 / AC-17 / AC-29 + backend-multi-instance Task 15 (AC-18..20): fixed 1-minute window per (sub, endpoint),
 * shared across instances through Redis ({@code INCR} + {@code EXPIRE}). Redis errors/timeouts fail OPEN to the
 * in-memory per-instance window below (limit then applies per instance, a deliberate degradation).
 */
@Component
public class NameCheckRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(NameCheckRateLimiter.class);
    private static final long WINDOW_MS = 60_000L;

    private record Window(long startMs, int count) {
    }

    /** Own, short budget for the Redis call: the shared 2s Redis timeout stays untouched for every other user. */
    static final long REDIS_TIMEOUT_MS = 300L;
    private static final long WARN_INTERVAL_MS = 30_000L;

    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    private final int limitPerMinute;
    private final StringRedisTemplate redis;
    private final ExecutorService redisExecutor;
    private volatile long lastWarnMs;

    @Autowired
    public NameCheckRateLimiter(
            StringRedisTemplate redis, @Value("${app.name-check.rate-limit-per-minute:30}") int limitPerMinute) {
        this.redis = redis;
        this.limitPerMinute = limitPerMinute;
        // Bounded, no queue: if Redis hangs and the pool saturates, callers fall back locally straight away.
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                2, 32, 30, TimeUnit.SECONDS, new SynchronousQueue<>(), r -> {
                    Thread t = new Thread(r, "name-check-redis");
                    t.setDaemon(true);
                    return t;
                });
        this.redisExecutor = executor;
    }

    /** Local-only limiter (unit tests, no Redis). */
    public NameCheckRateLimiter(int limitPerMinute) {
        this.redis = null;
        this.limitPerMinute = limitPerMinute;
        this.redisExecutor = null;
    }

    public void check(String sub, String endpoint) {
        check(sub, endpoint, System.currentTimeMillis());
    }

    void check(String sub, String endpoint, long nowMs) {
        Long redisCount = incrementInRedis(sub, endpoint, nowMs);
        long count;
        if (redisCount != null) {
            count = redisCount;
        } else {
            count = windows.compute(sub + "|" + endpoint, (k, old) ->
                    old == null || nowMs - old.startMs() >= WINDOW_MS
                            ? new Window(nowMs, 1)
                            : new Window(old.startMs(), old.count() + 1)).count();
        }
        if (count > limitPerMinute) {
            log.warn("name-check rate limit exceeded endpoint={} limit={}/min", endpoint, limitPerMinute);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("code", "NAME_CHECK_RATE_LIMITED");
            body.put("message", "Quá nhiều yêu cầu kiểm tra tên, vui lòng thử lại sau");
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, body, "Name check rate limited");
        }
        if (windows.size() > 10_000) {
            windows.entrySet().removeIf(e -> nowMs - e.getValue().startMs() >= WINDOW_MS);
        }
    }

    /** @return the shared count for this window, or {@code null} when Redis is unavailable (caller falls back). */
    private Long incrementInRedis(String sub, String endpoint, long nowMs) {
        if (redis == null) {
            return null;
        }
        String key = "namecheck:" + sub + ":" + endpoint + ":" + (nowMs / WINDOW_MS);
        try {
            return CompletableFuture.supplyAsync(() -> {
                        Long count = redis.opsForValue().increment(key);
                        if (count != null && count == 1L) {
                            redis.expire(key, Duration.ofSeconds(120));
                        }
                        return count;
                    }, redisExecutor)
                    .orTimeout(REDIS_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .join();
        } catch (RuntimeException e) {
            // CompletionException (timeout, DataAccessException) or RejectedExecutionException: fail open.
            warnThrottled(e);
            return null;
        }
    }

    private void warnThrottled(RuntimeException e) {
        long now = System.currentTimeMillis();
        if (now - lastWarnMs >= WARN_INTERVAL_MS) {
            lastWarnMs = now;
            // Never log the sub (key content); only the failure class.
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            log.warn("name-check rate limit: Redis unavailable ({}), falling back to the per-instance window",
                    cause.getClass().getSimpleName());
        }
    }
}
