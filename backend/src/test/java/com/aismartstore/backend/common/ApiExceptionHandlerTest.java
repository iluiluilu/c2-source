package com.aismartstore.backend.common;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aismartstore.backend.audit.AuditRequestContext;
import com.aismartstore.backend.audit.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;

/** Unit coverage of audit-logging.md §4.7 (Story 1 AC-8): business 4xx on an uninstrumented route gets exactly one audit entry. */
class ApiExceptionHandlerTest {

    private final AuditService auditService = mock(AuditService.class);
    private final AuditRequestContext auditRequestContext = mock(AuditRequestContext.class);
    private final ApiExceptionHandler handler = new ApiExceptionHandler(auditService, auditRequestContext);

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private static HttpServletRequest requestFor(String path, boolean alreadyRecorded) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn(path);
        when(request.getAttribute(AuditService.AUDIT_RECORDED_ATTRIBUTE)).thenReturn(alreadyRecorded);
        return request;
    }

    @Test
    void businessFourXxOnUninstrumentedRoute_recordsExactlyOneFailureEntry() {
        HttpServletRequest request = requestFor("/allowed-file-formats/abc", false);

        handler.handleApiException(new ConflictException("already exists"), request);

        verify(auditService).recordAsync(argThat(entry ->
                entry.action().equals("allowed_file_format.request_rejected")
                        && entry.resourceType().equals("allowed_file_format")
                        && "failure".equals(entry.outcome())));
    }

    @Test
    void alreadyRecordedRequest_isNeverDoubleAudited() {
        HttpServletRequest request = requestFor("/documents", true);

        handler.handleApiException(new ConflictException("already exists"), request);

        verify(auditService, never()).recordAsync(any());
    }

    @Test
    void unauthorizedAndForbiddenApiExceptionsAreNotAuditedByThisHandler() {
        HttpServletRequest request = requestFor("/accounts", false);

        handler.handleApiException(new ForbiddenException("nope"), request);

        verify(auditService, never()).recordAsync(any());
    }

    @Test
    void validationFailure_recordsABusinessFailureEntry() {
        HttpServletRequest request = requestFor("/groups", false);
        org.springframework.validation.BindingResult bindingResult =
                mock(org.springframework.validation.BindingResult.class);
        when(bindingResult.getFieldErrors()).thenReturn(java.util.List.of());
        org.springframework.web.bind.MethodArgumentNotValidException ex =
                mock(org.springframework.web.bind.MethodArgumentNotValidException.class);
        when(ex.getBindingResult()).thenReturn(bindingResult);

        handler.handleValidation(ex, request);

        verify(auditService).recordAsync(argThat(entry -> entry.action().equals("group.request_rejected")));
    }
}
