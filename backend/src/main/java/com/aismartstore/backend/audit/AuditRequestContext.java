package com.aismartstore.backend.audit;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * audit-logging.md §4.6 (Story 1 AC-9): lets a service that calls {@link
 * AuditService#record}/{@code recordAsync} manually fill in {@code
 * ip}/{@code method}/{@code path} without injecting {@link
 * HttpServletRequest} itself. A plain singleton (not a request-scoped bean)
 * that reads the current request off {@link RequestContextHolder} on every
 * call, so it also works - returning {@code null} from every accessor -
 * when called from a background job with no request in flight (the sealer,
 * checkpoint, partition and retention jobs never call this).
 */
@Component
public class AuditRequestContext {

    private HttpServletRequest currentRequest() {
        var attributes = RequestContextHolder.getRequestAttributes();
        if (!(attributes instanceof ServletRequestAttributes servletAttributes)) {
            return null;
        }
        return servletAttributes.getRequest();
    }

    public String ip() {
        HttpServletRequest request = currentRequest();
        return request != null ? request.getRemoteAddr() : null;
    }

    public String method() {
        HttpServletRequest request = currentRequest();
        return request != null ? request.getMethod() : null;
    }

    public String path() {
        HttpServletRequest request = currentRequest();
        return request != null ? request.getRequestURI() : null;
    }
}
