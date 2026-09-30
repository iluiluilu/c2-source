package com.aismartstore.backend.scanning;

import com.aismartstore.backend.audit.AuditRequestContext;
import com.aismartstore.backend.audit.AuditService;
import com.aismartstore.backend.auth.CurrentUser;
import com.aismartstore.backend.auth.KeycloakUser;
import com.aismartstore.backend.scanning.dto.CreateAllowedFileFormatDto;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * R3-only management of the format allowlist (data-ingestion.md §3, Task 7,
 * Story 5). Every write refreshes the Redis cache synchronously (same
 * request, see {@link AllowlistService#create}/{@code delete}) so the very
 * next upload sees it - no restart required (Story 5 AC-3).
 */
@RestController
@RequestMapping("/admin/allowed-file-formats")
public class AllowedFileFormatController {

    private final AllowlistService allowlistService;
    private final AuditService auditService;
    private final AuditRequestContext auditRequestContext;

    public AllowedFileFormatController(
            AllowlistService allowlistService, AuditService auditService, AuditRequestContext auditRequestContext) {
        this.allowlistService = allowlistService;
        this.auditService = auditService;
        this.auditRequestContext = auditRequestContext;
    }

    /** {@code mimeType}/{@code extensions}/{@code category}/{@code updatedAt} - the API contract's response shape. */
    public record View(String mimeType, List<String> extensions, String category, OffsetDateTime updatedAt) {
        static View of(AllowedFileFormat row) {
            return new View(row.getMimeType(), Arrays.asList(row.getExtensions()), row.getCategory(), row.getUpdatedAt());
        }
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PERM_scan_allowlist.manage')")
    public List<View> list() {
        return allowlistService.listAll().stream().map(View::of).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PERM_scan_allowlist.manage')")
    public View create(@CurrentUser KeycloakUser user, @Valid @RequestBody CreateAllowedFileFormatDto dto) {
        AllowedFileFormat row = allowlistService.create(
                user.sub(), user.preferredUsername(), dto.mimeType(), dto.extensions(), dto.category(), dto.magicBytes());
        auditService.recordAsync(new AuditService.AuditEntry(
                user.preferredUsername(), user.sub(), "allowed_file_format.create", "allowed_file_format",
                row.getMimeType(), "success", auditRequestContext.method(), auditRequestContext.path(), 200,
                auditRequestContext.ip(),
                Map.of("extensions", dto.extensions(), "category", dto.category())));
        return View.of(row);
    }

    /**
     * {@code mimeType} contains a slash (e.g. {@code application/pdf}), so it
     * cannot be a plain {@code {mimeType}} path segment - {@code {*mimeType}}
     * captures the whole remaining path (including the leading slash, which
     * is stripped below). A literal slash in a URL path is fine; it is only
     * an *encoded* {@code %2F} that Spring Security's StrictHttpFirewall
     * rejects by default, and clients calling this endpoint send a real
     * slash, not an escaped one.
     */
    @DeleteMapping("/{*mimeType}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('PERM_scan_allowlist.manage')")
    public void delete(@CurrentUser KeycloakUser user, @PathVariable String mimeType) {
        String key = mimeType.startsWith("/") ? mimeType.substring(1) : mimeType;
        allowlistService.delete(key);
        auditService.recordAsync(new AuditService.AuditEntry(
                user.preferredUsername(), user.sub(), "allowed_file_format.delete", "allowed_file_format",
                key, "success", auditRequestContext.method(), auditRequestContext.path(), 200,
                auditRequestContext.ip(), null));
    }
}
