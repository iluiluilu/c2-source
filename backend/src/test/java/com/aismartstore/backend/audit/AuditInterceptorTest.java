package com.aismartstore.backend.audit;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;

/** Unit coverage of audit-logging.md §4.3/§5 (Story 1 AC-1/AC-2): the interceptor is a fallback only. */
class AuditInterceptorTest {

    private final AuditService auditService = mock(AuditService.class);
    private final AuditInterceptor interceptor = new AuditInterceptor(auditService);

    private static HttpServletRequest mutatingRequest(String method) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getMethod()).thenReturn(method);
        when(request.getRequestURI()).thenReturn("/documents");
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        return request;
    }

    private static HttpServletResponse responseWithStatus(int status) {
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(response.getStatus()).thenReturn(status);
        return response;
    }

    @Test
    void auditRecordedAttributeSet_neverWritesEvenOnFailureStatus() {
        HttpServletRequest request = mutatingRequest("POST");
        when(request.getAttribute(AuditService.AUDIT_RECORDED_ATTRIBUTE)).thenReturn(Boolean.TRUE);

        interceptor.afterCompletion(request, responseWithStatus(500), new Object(), null);

        verify(auditService, never()).recordAsync(any());
    }

    @Test
    void notRecorded_successStatus_doesNotWrite() {
        HttpServletRequest request = mutatingRequest("POST");

        interceptor.afterCompletion(request, responseWithStatus(200), new Object(), null);

        verify(auditService, never()).recordAsync(any());
    }

    @Test
    void notRecorded_forbiddenStatus_writesFallbackEntry() {
        HttpServletRequest request = mutatingRequest("POST");

        interceptor.afterCompletion(request, responseWithStatus(403), new Object(), null);

        verify(auditService).recordAsync(any());
    }

    @Test
    void notRecorded_serverErrorStatus_writesFallbackEntry() {
        HttpServletRequest request = mutatingRequest("DELETE");

        interceptor.afterCompletion(request, responseWithStatus(500), new Object(), null);

        verify(auditService).recordAsync(any());
    }

    @Test
    void nonMutatingMethod_neverWrites() {
        HttpServletRequest request = mutatingRequest("GET");

        interceptor.afterCompletion(request, responseWithStatus(500), new Object(), null);

        verify(auditService, never()).recordAsync(any());
    }
}
