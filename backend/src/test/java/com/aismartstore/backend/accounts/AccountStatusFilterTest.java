package com.aismartstore.backend.accounts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/** Unit coverage for account-lifecycle.md §5 ("AccountStatusFilterTest"). */
class AccountStatusFilterTest {

    private AccountRepository accountRepository;
    private StringRedisTemplate redis;
    private ValueOperations<String, String> valueOps;
    private AccountStatusFilter filter;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        accountRepository = mock(AccountRepository.class);
        redis = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        filter = new AccountStatusFilter(accountRepository, redis);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private static void authenticateAs(String sub) {
        Jwt jwt = Jwt.withTokenValue("test-token")
                .header("alg", "none")
                .subject(sub)
                .claim("sub", sub)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
    }

    private static MockHttpServletRequest request() {
        return new MockHttpServletRequest("GET", "/documents");
    }

    @Test
    void noAuthenticationSkipsFilter() throws Exception {
        MockHttpServletRequest req = request();
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(req, res, chain);

        assertThat(chain.getRequest()).isEqualTo(req);
        assertThat(res.getStatus()).isEqualTo(200); // MockHttpServletResponse default when untouched
    }

    @Test
    void activeStatusOnCacheMissQueriesDbAndPopulatesCache() throws Exception {
        authenticateAs("user-1");
        when(valueOps.get(anyString())).thenReturn(null);
        when(accountRepository.findStatusById("user-1")).thenReturn(Optional.of("active"));

        MockHttpServletRequest req = request();
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(req, res, chain);

        assertThat(chain.getRequest()).isEqualTo(req);
        verify(valueOps).set(eq(AccountStatusCacheKeys.keyFor("user-1")), eq("active"), any());
    }

    @Test
    void lockedStatusReturns401WithoutContinuingChain() throws Exception {
        authenticateAs("user-2");
        when(valueOps.get(anyString())).thenReturn(null);
        when(accountRepository.findStatusById("user-2")).thenReturn(Optional.of("locked"));

        MockHttpServletRequest req = request();
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(401);
        assertThat(res.getContentAsString()).contains("ACCOUNT_LOCKED");
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void disabledStatusReturns401WithDisabledCode() throws Exception {
        authenticateAs("user-3");
        when(valueOps.get(anyString())).thenReturn(null);
        when(accountRepository.findStatusById("user-3")).thenReturn(Optional.of("disabled"));

        MockHttpServletRequest req = request();
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilter(req, res, new MockFilterChain());

        assertThat(res.getStatus()).isEqualTo(401);
        assertThat(res.getContentAsString()).contains("ACCOUNT_DISABLED");
    }

    @Test
    void cacheHitSkipsSecondDbQueryAcrossTwoRequests() throws Exception {
        authenticateAs("user-4");
        when(valueOps.get(anyString())).thenReturn(null).thenReturn("active");
        when(accountRepository.findStatusById("user-4")).thenReturn(Optional.of("active"));

        filter.doFilter(request(), new MockHttpServletResponse(), new MockFilterChain());
        filter.doFilter(request(), new MockHttpServletResponse(), new MockFilterChain());

        verify(accountRepository, times(1)).findStatusById("user-4");
    }

    @Test
    void cacheExpiredQueriesDbAgainAndSeesUpdatedStatus() throws Exception {
        authenticateAs("user-5");
        // First call: cache miss, DB says active. Second call: cache "expired"
        // again (still a miss from Redis's point of view), DB now says locked.
        when(valueOps.get(anyString())).thenReturn(null);
        when(accountRepository.findStatusById("user-5")).thenReturn(Optional.of("active")).thenReturn(Optional.of("locked"));

        MockHttpServletResponse first = new MockHttpServletResponse();
        filter.doFilter(request(), first, new MockFilterChain());
        assertThat(first.getStatus()).isEqualTo(200);

        MockHttpServletResponse second = new MockHttpServletResponse();
        filter.doFilter(request(), second, new MockFilterChain());
        assertThat(second.getStatus()).isEqualTo(401);

        verify(accountRepository, times(2)).findStatusById("user-5");
    }

    @Test
    void redisFailureFallsBackToDbAndStillBlocksLockedAccount() throws Exception {
        authenticateAs("user-6");
        when(valueOps.get(anyString())).thenThrow(new RuntimeException("redis down"));
        when(accountRepository.findStatusById("user-6")).thenReturn(Optional.of("locked"));

        MockHttpServletRequest req = request();
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(401);
        assertThat(res.getContentAsString()).contains("ACCOUNT_LOCKED");
        assertThat(chain.getRequest()).isNull();
        verify(accountRepository).findStatusById("user-6");
    }

    @Test
    void missingAccountRowIsTreatedAsActive() throws Exception {
        authenticateAs("legacy-user");
        when(valueOps.get(anyString())).thenReturn(null);
        when(accountRepository.findStatusById("legacy-user")).thenReturn(Optional.empty());

        MockHttpServletRequest req = request();
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(req, res, chain);

        assertThat(chain.getRequest()).isEqualTo(req);
    }
}
