package com.aismartstore.backend.displayname;

import com.aismartstore.backend.common.ApiException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** SPEC BE-6 / AC-17 / AC-29: in-memory fixed 1-minute window per (sub, endpoint); not shared across instances. */
@Component
public class NameCheckRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(NameCheckRateLimiter.class);
    private static final long WINDOW_MS = 60_000L;

    private record Window(long startMs, int count) {
    }

    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    private final int limitPerMinute;

    public NameCheckRateLimiter(@Value("${app.name-check.rate-limit-per-minute:30}") int limitPerMinute) {
        this.limitPerMinute = limitPerMinute;
    }

    public void check(String sub, String endpoint) {
        check(sub, endpoint, System.currentTimeMillis());
    }

    void check(String sub, String endpoint, long nowMs) {
        Window w = windows.compute(sub + "|" + endpoint, (k, old) ->
                old == null || nowMs - old.startMs() >= WINDOW_MS
                        ? new Window(nowMs, 1)
                        : new Window(old.startMs(), old.count() + 1));
        if (w.count() > limitPerMinute) {
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
}
