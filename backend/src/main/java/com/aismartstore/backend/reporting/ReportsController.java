package com.aismartstore.backend.reporting;

import com.aismartstore.backend.auth.CurrentUser;
import com.aismartstore.backend.auth.KeycloakUser;
import com.aismartstore.backend.reporting.dto.ReportDtos;
import com.aismartstore.backend.reporting.dto.ReportResult;
import com.aismartstore.backend.reporting.dto.RunReportRequest;
import java.util.List;
import java.util.UUID;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * SPEC §3: the {@code /reports} API. Every handler calls {@link ReportAccessGuard#require} FIRST - before
 * any parameter validation or query (AC-2). Deliberately no {@code @PreAuthorize}: the guard audits
 * {@code report.access_denied}, which the generic access-denied handler would not.
 */
@RestController
@RequestMapping("/reports")
public class ReportsController {

    private final ReportAccessGuard guard;
    private final ReportsService reportsService;
    private final SavedQueryService savedQueryService;
    private final ReportExportService exportService;

    public ReportsController(
            ReportAccessGuard guard,
            ReportsService reportsService,
            SavedQueryService savedQueryService,
            ReportExportService exportService) {
        this.guard = guard;
        this.reportsService = reportsService;
        this.savedQueryService = savedQueryService;
        this.exportService = exportService;
    }

    // ---- report.read -------------------------------------------------------------------------

    @GetMapping("/templates")
    public List<ReportDtos.TemplateView> templates(@CurrentUser KeycloakUser user) {
        guard.require(user, ReportAccessGuard.PERM_READ);
        return reportsService.templates();
    }

    @PostMapping("/{templateCode}/run")
    public ReportResult run(
            @CurrentUser KeycloakUser user, @PathVariable String templateCode, @RequestBody RunReportRequest request) {
        guard.require(user, ReportAccessGuard.PERM_READ);
        return reportsService.run(user, templateCode, request);
    }

    @GetMapping("/saved")
    public List<ReportDtos.SavedQuerySummary> listSaved(@CurrentUser KeycloakUser user) {
        guard.require(user, ReportAccessGuard.PERM_READ);
        return savedQueryService.list(user);
    }

    @PostMapping("/saved")
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.CREATED)
    public ReportDtos.SavedQuerySummary createSaved(
            @CurrentUser KeycloakUser user, @RequestBody ReportDtos.SaveQueryRequest request) {
        guard.require(user, ReportAccessGuard.PERM_READ);
        return savedQueryService.create(user, request);
    }

    @GetMapping("/saved/{id}")
    public ReportDtos.SavedQueryDetail getSaved(@CurrentUser KeycloakUser user, @PathVariable UUID id) {
        guard.require(user, ReportAccessGuard.PERM_READ);
        return savedQueryService.get(user, id);
    }

    @PatchMapping("/saved/{id}")
    public ReportDtos.SavedQuerySummary renameSaved(
            @CurrentUser KeycloakUser user, @PathVariable UUID id, @RequestBody ReportDtos.RenameQueryRequest request) {
        guard.require(user, ReportAccessGuard.PERM_READ);
        return savedQueryService.rename(user, id, request);
    }

    @DeleteMapping("/saved/{id}")
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteSaved(@CurrentUser KeycloakUser user, @PathVariable UUID id) {
        guard.require(user, ReportAccessGuard.PERM_READ);
        savedQueryService.delete(user, id);
    }

    // ---- report.export -----------------------------------------------------------------------

    @PostMapping("/exports")
    public ResponseEntity<ReportDtos.ExportCreated> createExport(
            @CurrentUser KeycloakUser user, @RequestBody ReportDtos.CreateExportRequest request) {
        guard.require(user, ReportAccessGuard.PERM_EXPORT);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(exportService.create(user, request));
    }

    @GetMapping("/exports")
    public ReportDtos.ExportPage listExports(
            @CurrentUser KeycloakUser user,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        guard.require(user, ReportAccessGuard.PERM_EXPORT);
        return exportService.list(user, page, size);
    }

    @GetMapping("/exports/{id}")
    public ReportDtos.ExportView getExport(@CurrentUser KeycloakUser user, @PathVariable UUID id) {
        guard.require(user, ReportAccessGuard.PERM_EXPORT);
        return exportService.get(user, id);
    }

    @GetMapping("/exports/{id}/download")
    public ResponseEntity<InputStreamResource> download(@CurrentUser KeycloakUser user, @PathVariable UUID id) {
        guard.require(user, ReportAccessGuard.PERM_EXPORT);
        return exportService.download(user, id);
    }

    @PostMapping("/exports/{id}/retry")
    public ResponseEntity<ReportDtos.ExportCreated> retry(@CurrentUser KeycloakUser user, @PathVariable UUID id) {
        guard.require(user, ReportAccessGuard.PERM_EXPORT);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(exportService.retry(user, id));
    }
}
