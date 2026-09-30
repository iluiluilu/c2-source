package com.aismartstore.backend.common;

import com.aismartstore.backend.audit.AuditRequestContext;
import com.aismartstore.backend.audit.AuditService;
import com.aismartstore.backend.auth.CurrentUserArgumentResolver;
import com.aismartstore.backend.auth.KeycloakUser;
import com.aismartstore.backend.digitization.results.SegmentRevisionConflictException;
import com.aismartstore.backend.groups.ScopeResolutionException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Maps this project's {@link ApiException} family (and a few framework
 * exceptions) onto the same status-code shape the NestJS backend produced,
 * so the frontend's existing error handling keeps working unchanged.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    private final AuditService auditService;
    private final AuditRequestContext auditRequestContext;

    public ApiExceptionHandler(AuditService auditService, AuditRequestContext auditRequestContext) {
        this.auditService = auditService;
        this.auditRequestContext = auditRequestContext;
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Object> handleApiException(ApiException ex, HttpServletRequest request) {
        HttpStatus status = ex.getStatus();
        // audit-logging.md §4.7 (Story 1 AC-8): 401/403 stay out of this - AccessDeniedException below
        // already covers @PreAuthorize denials, and AuditInterceptor's fallback (§4.3 AC-2) covers any
        // other 401/403 on an uninstrumented route.
        if (status.is4xxClientError() && status != HttpStatus.UNAUTHORIZED && status != HttpStatus.FORBIDDEN) {
            recordBusinessFailureAudit(request, status.value(), "business_error: " + ex.getMessage());
        }
        if (ex.getBody() != null) {
            return ResponseEntity.status(status).body(ex.getBody());
        }
        return ResponseEntity.status(status).body(errorBody(status, ex.getMessage()));
    }

    /**
     * digitization-review-01-ocr.md §3/§3.4: optimistic-lock conflict on a
     * {@code PATCH}/{@code DELETE} segment edit - the caller's {@code
     * expectedRevisionNo} is stale. Unlike every other {@code ApiException}
     * here, the 409 body carries the segment's up-to-date state (spec's
     * {@code { latestRevisionNo, latestText, latestTimeStartMs,
     * latestTimeEndMs }}) so the UI can offer "reload and re-apply" without a
     * second round-trip.
     */
    @ExceptionHandler(SegmentRevisionConflictException.class)
    public ResponseEntity<Object> handleSegmentRevisionConflict(
            SegmentRevisionConflictException ex, HttpServletRequest request) {
        recordBusinessFailureAudit(request, HttpStatus.CONFLICT.value(), "segment_revision_conflict: " + ex.getMessage());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("latestRevisionNo", ex.getLatestRevisionNo());
        body.put("latestText", ex.getLatestText());
        body.put("latestTimeStartMs", ex.getLatestTimeStartMs());
        body.put("latestTimeEndMs", ex.getLatestTimeEndMs());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Object> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .orElse("Validation failed");
        recordBusinessFailureAudit(request, HttpStatus.BAD_REQUEST.value(), "validation_failed: " + message);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorBody(HttpStatus.BAD_REQUEST, message));
    }

    /**
     * The {@code @Validated} counterpart of {@link
     * #handleValidation(MethodArgumentNotValidException, HttpServletRequest)}.
     * Bean Validation raises two different exceptions depending on where the
     * constraint sits: {@code MethodArgumentNotValidException} for a {@code
     * @Valid @RequestBody}, and {@code ConstraintViolationException} for a
     * constraint on a method parameter of a {@code @Validated} controller
     * (e.g. {@code @Size @RequestParam String note} on {@code
     * IngestController}/{@code DocumentsController}). Only the first had a
     * handler, so request-body validation answered 400 while an equally
     * invalid request parameter escaped as a ServletException and answered
     * **500** - caught by {@code
     * NotesControllerIT.ingestWithANoteLongerThan4000CharactersIsRejectedWith400AndCreatesNoNote},
     * which had never run until 2026-09-18.
     *
     * <p>Strictly a repair of that inconsistency: it can only turn responses
     * that are currently unhandled 500s into the 400 they should always have
     * been, and cannot change any request that works today.
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Object> handleConstraintViolation(
            ConstraintViolationException ex, HttpServletRequest request) {
        String message = ex.getConstraintViolations().stream()
                .findFirst()
                .map(v -> lastPathNode(v) + ": " + v.getMessage())
                .orElse("Validation failed");
        recordBusinessFailureAudit(request, HttpStatus.BAD_REQUEST.value(), "validation_failed: " + message);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorBody(HttpStatus.BAD_REQUEST, message));
    }

    /**
     * A violation's propertyPath here is the whole method path (e.g. {@code
     * uploadFile.note}); only the trailing node names the offending
     * parameter, which is what {@link
     * #handleValidation(MethodArgumentNotValidException, HttpServletRequest)}
     * reports for the request-body case, so both messages read alike.
     */
    private static String lastPathNode(ConstraintViolation<?> violation) {
        String path = violation.getPropertyPath().toString();
        int lastDot = path.lastIndexOf('.');
        return lastDot >= 0 && lastDot < path.length() - 1 ? path.substring(lastDot + 1) : path;
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Object> handleUnreadable(HttpMessageNotReadableException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(errorBody(HttpStatus.BAD_REQUEST, "Malformed request body"));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Object> handleMaxUpload(MaxUploadSizeExceededException ex) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(errorBody(HttpStatus.PAYLOAD_TOO_LARGE, "File exceeds the maximum upload size"));
    }

    /**
     * rbac-group-scope.md Task 9/Story 1 AC-3: a {@code @PreAuthorize} role
     * mismatch is a security-relevant denial, so - unlike every other 4xx in
     * this handler - it is also recorded to the audit log (best-effort;
     * {@link #recordAccessDeniedAudit} never lets a logging failure affect
     * the response).
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Object> handleAccessDenied(AccessDeniedException ex) {
        recordAccessDeniedAudit("role_mismatch");
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(errorBody(HttpStatus.FORBIDDEN, "Forbidden resource"));
    }

    /**
     * rbac-group-scope.md Task 7/Story 4 AC-2: fail-closed - never a bare 500,
     * always logged loudly (with stack trace) for technical triage, and
     * still recorded as an {@code access.denied} audit entry like any other
     * denial.
     */
    @ExceptionHandler(ScopeResolutionException.class)
    public ResponseEntity<Object> handleScopeResolution(ScopeResolutionException ex, HttpServletRequest request) {
        log.error(
                "Business data scope resolution failed for {} {}",
                request.getMethod(), request.getRequestURI(), ex);
        recordAccessDeniedAudit("scope_resolution_failed");
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(errorBody(HttpStatus.FORBIDDEN, "Forbidden resource"));
    }

    /**
     * audit-logging.md §4.6 (Story 1 AC-9): sources ip/method/path from
     * {@link AuditRequestContext} instead of leaving {@code ip} null as this
     * used to - the one gap found reviewing this method for that spec.
     */
    private void recordAccessDeniedAudit(String reason) {
        try {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication == null || !(authentication.getPrincipal() instanceof Jwt jwt)) {
                return;
            }
            KeycloakUser user = CurrentUserArgumentResolver.fromJwt(jwt);
            auditService.recordAsync(new AuditService.AuditEntry(
                    user.preferredUsername(),
                    user.sub(),
                    "access.denied",
                    null,
                    null,
                    "failure",
                    auditRequestContext.method(),
                    auditRequestContext.path(),
                    HttpStatus.FORBIDDEN.value(),
                    auditRequestContext.ip(),
                    Map.of("reason", reason)));
        } catch (RuntimeException e) {
            log.warn("Failed to record an access.denied audit entry", e);
        }
    }

    /**
     * audit-logging.md §4.7 (Story 1 AC-8): a business 4xx on a route that
     * was never instrumented with its own audit call still gets exactly one
     * {@code outcome=failure} entry. Guarded by {@link
     * AuditService#AUDIT_RECORDED_ATTRIBUTE} so a service that already
     * called {@code record(...)} with {@code outcome=failure} before
     * throwing is never double-logged. Best-effort: {@code action}/{@code
     * resourceType} are inferred from the request path when they cannot be
     * known precisely here (see this method's spec section for why that is
     * an accepted limitation for v1).
     */
    private void recordBusinessFailureAudit(HttpServletRequest request, int status, String reason) {
        if (Boolean.TRUE.equals(request.getAttribute(AuditService.AUDIT_RECORDED_ATTRIBUTE))) {
            return;
        }
        try {
            String actorName = "anonymous";
            String actorSub = null;
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication != null && authentication.getPrincipal() instanceof Jwt jwt) {
                KeycloakUser user = CurrentUserArgumentResolver.fromJwt(jwt);
                actorName = user.preferredUsername() != null ? user.preferredUsername() : "anonymous";
                actorSub = user.sub();
            }

            String domain = domainFromPath(request.getRequestURI());
            auditService.recordAsync(new AuditService.AuditEntry(
                    actorName,
                    actorSub,
                    domain + ".request_rejected",
                    domain,
                    firstPathVariable(request),
                    "failure",
                    auditRequestContext.method(),
                    auditRequestContext.path(),
                    status,
                    auditRequestContext.ip(),
                    Map.of("reason", reason)));
        } catch (RuntimeException e) {
            log.warn("Failed to record a business-failure audit entry", e);
        }
    }

    /** First non-empty path segment, singularized/underscored to loosely match this codebase's `<domain>.<verb>` action taxonomy (e.g. "/allowed-file-formats/{key}" -> "allowed_file_format"). Best-effort only - see {@link #recordBusinessFailureAudit}. */
    private static String domainFromPath(String path) {
        if (path == null) {
            return "unknown";
        }
        String[] segments = path.split("/");
        for (String segment : segments) {
            if (!segment.isBlank()) {
                String normalized = segment.replace('-', '_');
                return normalized.endsWith("s") && normalized.length() > 1
                        ? normalized.substring(0, normalized.length() - 1)
                        : normalized;
            }
        }
        return "unknown";
    }

    @SuppressWarnings("unchecked")
    private static String firstPathVariable(HttpServletRequest request) {
        Object attribute = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        if (!(attribute instanceof Map<?, ?> variables) || variables.isEmpty()) {
            return null;
        }
        return String.valueOf(variables.values().iterator().next());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Object> handleTypeMismatch(MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
        String message = "Invalid value for '" + ex.getName() + "'";
        recordBusinessFailureAudit(request, HttpStatus.BAD_REQUEST.value(), "type_mismatch: " + message);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorBody(HttpStatus.BAD_REQUEST, message));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Object> handleIllegalArgument(IllegalArgumentException ex, HttpServletRequest request) {
        recordBusinessFailureAudit(request, HttpStatus.BAD_REQUEST.value(), "illegal_argument: " + ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorBody(HttpStatus.BAD_REQUEST, ex.getMessage()));
    }

    private static Map<String, Object> errorBody(HttpStatus status, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("statusCode", status.value());
        body.put("message", message);
        body.put("timestamp", Instant.now().toString());
        return body;
    }

    /*
     * No @ExceptionHandler(Exception.class) here - considered and declined
     * 2026-09-20 (audit-logging.md §4.7). Before adding one, re-read that
     * section in full; short version: the gap it would close is already
     * closed (AuditInterceptor.afterCompletion, §4.3 rule 3, fallback-audits
     * any response >= 500 regardless of whether an @ExceptionHandler here
     * ever ran; server.error.include-message defaults to 'never' so an
     * unhandled exception's message is already suppressed today), while the
     * risk it would add is real and easy to trigger by accident: every
     * handler above passes ex.getMessage() straight into the body - a
     * catch-all copied from that same pattern leaks internal exception text
     * (web/src/lib/api.ts renders body.message directly to the user) the
     * first time something actually crashes, not at review time. It would
     * also need NotesOutboxAtomicityIT changed back to asserting
     * status().isInternalServerError() instead of the thrown exception (see
     * that test's own comment) and classification-notes-02-notes.md's leak
     * review re-verified.
     */
}
