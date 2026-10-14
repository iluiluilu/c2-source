package com.aismartstore.backend.reporting;

import com.aismartstore.backend.audit.AuditRequestContext;
import com.aismartstore.backend.audit.AuditService;
import com.aismartstore.backend.auth.KeycloakUser;
import com.aismartstore.backend.common.ForbiddenException;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * SPEC-export-reporting §1/§4 bước 1 (AC-1, AC-2, AC-34): the one authorization gate for every
 * {@code /reports} endpoint. Not a plain {@code @PreAuthorize}: {@code
 * ApiExceptionHandler#handleAccessDenied} audits {@code access.denied}, but this module must audit
 * {@code report.access_denied}. No query runs before the guard (AC-2).
 *
 * <p>The denial is written with {@code recordIndependently} because the very next statement throws
 * (otherwise a surrounding transaction would roll the audit row back).
 */
@Component
public class ReportAccessGuard {

    public static final String PERM_READ = "report.read";
    public static final String PERM_EXPORT = "report.export";
    public static final String ACTION_ACCESS_DENIED = "report.access_denied";

    private static final Logger log = LoggerFactory.getLogger(ReportAccessGuard.class);
    private static final String DENIED_MESSAGE = "Bạn không có quyền truy cập chức năng báo cáo";

    private final AuditService auditService;
    private final AuditRequestContext auditRequestContext;

    public ReportAccessGuard(AuditService auditService, AuditRequestContext auditRequestContext) {
        this.auditService = auditService;
        this.auditRequestContext = auditRequestContext;
    }

    /** Throws 403 (+ {@code report.access_denied} audit when the subject is known) unless the caller holds {@code permission}. */
    public void require(KeycloakUser user, String permission) {
        if (user != null && user.sub() != null && !user.sub().isBlank() && hasAuthority("PERM_" + permission)) {
            return;
        }
        auditDenied(user, permission, "missing_permission", null);
        throw new ForbiddenException(DENIED_MESSAGE);
    }

    /** Ownership denial (download/status of somebody else's export): 403 + audit, no leak of existence beyond what the spec fixes (403). */
    public ForbiddenException denyNotOwner(KeycloakUser user, String exportId) {
        auditDenied(user, PERM_EXPORT, "not_owner", exportId);
        return new ForbiddenException(DENIED_MESSAGE);
    }

    private void auditDenied(KeycloakUser user, String permission, String reason, String exportId) {
        if (user == null || user.sub() == null || user.sub().isBlank()) {
            return; // no subject to attribute the denial to
        }
        try {
            Map<String, Object> metadata = new java.util.LinkedHashMap<>();
            metadata.put("permission", permission);
            metadata.put("reason", reason);
            if (exportId != null) {
                metadata.put("export_id", exportId);
            }
            auditService.recordIndependently(new AuditService.AuditEntry(
                    user.preferredUsername() != null ? user.preferredUsername() : user.sub(),
                    user.sub(),
                    ACTION_ACCESS_DENIED,
                    "report",
                    exportId,
                    "failure",
                    auditRequestContext.method(),
                    auditRequestContext.path(),
                    403,
                    auditRequestContext.ip(),
                    metadata));
        } catch (RuntimeException e) {
            log.warn("Failed to record a report.access_denied audit entry", e);
        }
    }

    private static boolean hasAuthority(String authority) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return false;
        }
        return authentication.getAuthorities().stream().anyMatch(a -> authority.equals(a.getAuthority()));
    }
}
