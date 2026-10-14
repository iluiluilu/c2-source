package com.aismartstore.backend.search;

import com.aismartstore.backend.common.TooManyRequestsException;
import com.aismartstore.backend.config.SemanticProperties;
import java.util.Map;
import java.util.concurrent.Semaphore;
import org.springframework.stereotype.Component;

/**
 * SPEC-search-semantic T14 (Q-5, AC-5.3): one system-wide (per backend instance) {@link Semaphore}
 * capping concurrent semantic queries at {@code app.semantic.query-concurrency} (8). Applies to
 * every mode, not just degraded ones. Never blocks: no free permit means an immediate 429.
 */
@Component
public class SemanticQueryLimiter {

    public static final String BUSY_MESSAGE = "Hệ thống tra cứu ngữ nghĩa đang bận, vui lòng thử lại sau ít giây.";

    private final Semaphore semaphore;

    public SemanticQueryLimiter(SemanticProperties properties) {
        this.semaphore = new Semaphore(Math.max(1, properties.queryConcurrency()));
    }

    /** @throws TooManyRequestsException (429, code {@code SEMANTIC_BUSY}) when all permits are taken */
    public void acquireOrThrow() {
        if (!semaphore.tryAcquire()) {
            throw new TooManyRequestsException(Map.of("code", "SEMANTIC_BUSY", "message", BUSY_MESSAGE), BUSY_MESSAGE);
        }
    }

    public void release() {
        semaphore.release();
    }

    public int availablePermits() {
        return semaphore.availablePermits();
    }
}
