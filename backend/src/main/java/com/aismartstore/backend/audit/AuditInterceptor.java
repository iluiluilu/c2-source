package com.aismartstore.backend.audit;

import com.aismartstore.backend.auth.CurrentUserArgumentResolver;
import com.aismartstore.backend.auth.KeycloakUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Set;
import org.springframework.lang.NonNull;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * audit-logging.md §4.3 (Story 1 AC-1/AC-2): narrowed to a fallback. Most
 * mutating routes now call {@link AuditService} themselves (§4.1), which
 * marks the request via {@link AuditService#markRecordedOnCurrentRequest()}
 * - when that marker is present this interceptor does nothing at all. When
 * it is absent (a route with no manual audit call yet, or one that was
 * rejected before reaching any service code), it still records - but only
 * a denial (401/403) or a server error (5xx), no longer every successful
 * request. Java port of the NestJS backend's audit/audit.interceptor.ts,
 * reworked per the spec above.
 */
@Component
public class AuditInterceptor implements HandlerInterceptor {

    private static final Set<String> MUTATING_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");

    private final AuditService auditService;

    public AuditInterceptor(AuditService auditService) {
        this.auditService = auditService;
    }

    @Override
    public void afterCompletion(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull Object handler,
            Exception ex) {
        if (!MUTATING_METHODS.contains(request.getMethod())) {
            return;
        }
        if (Boolean.TRUE.equals(request.getAttribute(AuditService.AUDIT_RECORDED_ATTRIBUTE))) {
            return; // a manual audit call already covered this request (§4.3 step 2)
        }

        int status = response.getStatus();
        boolean isDenialOrServerError = status == 401 || status == 403 || status >= 500;
        if (!isDenialOrServerError) {
            return;
        }

        String actor = "anonymous";
        String actorSub = null;
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof Jwt jwt) {
            KeycloakUser user = CurrentUserArgumentResolver.fromJwt(jwt);
            actor = user.preferredUsername() != null ? user.preferredUsername() : "anonymous";
            actorSub = user.sub();
        }

        auditService.recordAsync(new AuditService.AuditEntry(
                actor,
                actorSub,
                "http." + request.getMethod(),
                null,
                null,
                "failure",
                request.getMethod(),
                request.getRequestURI(),
                status,
                request.getRemoteAddr(),
                null));
    }
}
