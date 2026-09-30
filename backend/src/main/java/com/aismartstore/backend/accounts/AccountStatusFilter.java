package com.aismartstore.backend.accounts;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Lớp 2 of account-lifecycle.md's session-termination design (§4.5): after
 * the JWT has been verified offline (signature + expiry, by
 * {@code BearerTokenAuthenticationFilter}), re-check the account's
 * {@code status} on every authenticated request so a still-valid access
 * token stops working the moment an admin locks/disables the account -
 * without waiting for the token to expire.
 *
 * <p>Registered in {@link com.aismartstore.backend.config.SecurityConfig}
 * via {@code addFilterAfter(..., BearerTokenAuthenticationFilter.class)}.
 *
 * <p>Cache: shared Redis (TTL 45s as an upper bound only; the real
 * invalidation is the active {@code DEL} in
 * {@code AccountsService.setStatus()}). A Redis outage is treated exactly
 * like a cache miss - never fail-open - so this always queries
 * {@link AccountRepository} directly instead (§4.5 step 4b); the only cost
 * is extra DB load, never a security gap.
 */
@Component
public class AccountStatusFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AccountStatusFilter.class);
    private static final Duration CACHE_TTL = Duration.ofSeconds(45);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final AccountRepository accountRepository;
    private final StringRedisTemplate redis;

    public AccountStatusFilter(AccountRepository accountRepository, StringRedisTemplate redis) {
        this.accountRepository = accountRepository;
        this.redis = redis;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken jwtAuth)) {
            // Public route, or no bearer token presented at all - nothing to check.
            chain.doFilter(request, response);
            return;
        }

        String sub = jwtAuth.getToken().getSubject();
        String status = resolveStatus(sub);
        if (!"active".equals(status)) {
            writeLockedResponse(response, status);
            return;
        }
        chain.doFilter(request, response);
    }

    private String resolveStatus(String sub) {
        String cacheKey = AccountStatusCacheKeys.keyFor(sub);
        String cached = tryGetCache(cacheKey);
        if (cached != null) {
            return cached;
        }
        // Cache miss/expired, or Redis unavailable (§4.5 step 4b) - both take
        // the same direct-DB path, so a dead Redis degrades throughput, not
        // correctness. No row -> never locked through this feature -> active.
        String status = accountRepository.findStatusById(sub).orElse("active");
        trySetCache(cacheKey, status);
        return status;
    }

    private String tryGetCache(String key) {
        try {
            return redis.opsForValue().get(key);
        } catch (RuntimeException e) {
            log.warn("Redis read failed for account status cache '{}', querying DB directly: {}", key, e.toString());
            return null;
        }
    }

    private void trySetCache(String key, String status) {
        try {
            redis.opsForValue().set(key, status, CACHE_TTL);
        } catch (RuntimeException e) {
            log.warn("Failed to cache account status for '{}': {}", key, e.toString());
        }
    }

    private void writeLockedResponse(HttpServletResponse response, String status) throws IOException {
        String code = "locked".equals(status) ? "ACCOUNT_LOCKED" : "ACCOUNT_DISABLED";
        String message = "locked".equals(status)
                ? "Tài khoản đã bị khóa"
                : "Tài khoản đã bị vô hiệu hóa";
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("message", message);
        response.getWriter().write(JSON.writeValueAsString(body));
    }
}
