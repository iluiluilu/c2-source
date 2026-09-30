package com.aismartstore.backend.audit;

import com.aismartstore.backend.auth.CurrentUser;
import com.aismartstore.backend.auth.KeycloakUser;
import com.aismartstore.backend.groups.AuditScopeFilter;
import com.aismartstore.backend.groups.GroupMemberRepository;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * audit-logging.md §3 (Story 2/3): one endpoint shared by R2 and R3 - R3
 * ({@code PERM_audit.read}) sees everything, R2 ({@code
 * PERM_audit.read_scoped}) is transparently narrowed by {@link
 * AuditScopeFilter}. {@code /verify} stays R3-only (Story 4).
 */
@RestController
@RequestMapping("/audit")
public class AuditController {

    private static final int DEFAULT_PAGE_SIZE = 50;
    private static final int MAX_PAGE_SIZE = 200;
    private static final String UNRESTRICTED_AUTHORITY = "PERM_audit.read";

    /** {@code GET /audit} response shape (§3): a small explicit DTO instead of returning {@link Page} directly (Spring's default {@code PageImpl} JSON serialization is explicitly unstable/discouraged). */
    public record AuditPage(List<AuditLog> content, long totalElements, int page, int size, int totalPages) {
        static AuditPage from(Page<AuditLog> page) {
            return new AuditPage(
                    page.getContent(), page.getTotalElements(), page.getNumber(), page.getSize(),
                    page.getTotalPages());
        }
    }

    private final AuditService auditService;
    private final AuditRequestContext auditRequestContext;
    private final GroupMemberRepository groupMemberRepository;

    public AuditController(
            AuditService auditService,
            AuditRequestContext auditRequestContext,
            GroupMemberRepository groupMemberRepository) {
        this.auditService = auditService;
        this.auditRequestContext = auditRequestContext;
        this.groupMemberRepository = groupMemberRepository;
    }

    @GetMapping
    @PreAuthorize("hasAnyAuthority('PERM_audit.read', 'PERM_audit.read_scoped')")
    public AuditPage list(
            @CurrentUser KeycloakUser user,
            Authentication authentication,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @RequestParam(required = false) String actor,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String resourceType,
            @RequestParam(required = false) String resourceId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        int clampedSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        Pageable pageable = PageRequest.of(Math.max(page, 0), clampedSize, Sort.by(Sort.Direction.DESC, "id"));

        Specification<AuditLog> spec =
                AuditLogSpecifications.withFilters(from, to, actor, action, resourceType, resourceId);
        if (!hasAuthority(authentication, UNRESTRICTED_AUTHORITY)) {
            spec = spec.and(AuditScopeFilter.forCurrentUser(user.sub(), groupMemberRepository));
        }

        Page<AuditLog> result = auditService.search(spec, pageable);

        // audit-logging.md §3/Story 2 AC-5: viewing the log is itself audited - fire-and-forget,
        // never blocks or fails the response.
        auditService.recordAsync(new AuditService.AuditEntry(
                user.preferredUsername(), user.sub(), "audit.view", "audit_log", null, "success",
                auditRequestContext.method(), auditRequestContext.path(), 200, auditRequestContext.ip(), null));
        return AuditPage.from(result);
    }

    /** Tamper-evidence check over a bounded range - no longer verifies the whole table (avoids the past OOM). */
    @GetMapping("/verify")
    @PreAuthorize("hasAuthority('" + UNRESTRICTED_AUTHORITY + "')")
    public AuditService.ChainVerification verify(
            @CurrentUser KeycloakUser user,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to) {
        AuditService.ChainVerification result = auditService.verifyChain(from, to);

        auditService.recordAsync(new AuditService.AuditEntry(
                user.preferredUsername(), user.sub(), "audit.verify", "audit_log", null, "success",
                auditRequestContext.method(), auditRequestContext.path(), 200, auditRequestContext.ip(),
                java.util.Map.of("from", from.toString(), "to", to.toString())));
        return result;
    }

    private static boolean hasAuthority(Authentication authentication, String authority) {
        return authentication.getAuthorities().stream()
                .anyMatch(granted -> granted.getAuthority().equals(authority));
    }
}
