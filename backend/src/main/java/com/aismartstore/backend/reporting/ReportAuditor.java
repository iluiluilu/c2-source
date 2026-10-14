package com.aismartstore.backend.reporting;

import com.aismartstore.backend.audit.AuditRequestContext;
import com.aismartstore.backend.audit.AuditService;
import com.aismartstore.backend.auth.KeycloakUser;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * SPEC T3 / AC-34/35: writes the {@code report.generate|export|download} audit rows. Metadata is ONLY
 * the allowlist (template code, format, normalized params = ids and dates, counts, durations,
 * export id) - never figures, names or free text; {@code AuditService} drops anything else.
 * {@code record} (not async) so the row lives and dies with the business transaction (AC-34).
 */
@Component
public class ReportAuditor {

    public static final String ACTION_GENERATE = "report.generate";
    public static final String ACTION_EXPORT = "report.export";
    public static final String ACTION_DOWNLOAD = "report.download";

    private final AuditService auditService;
    private final AuditRequestContext ctx;

    public ReportAuditor(AuditService auditService, AuditRequestContext ctx) {
        this.auditService = auditService;
        this.ctx = ctx;
    }

    public void generate(KeycloakUser user, String templateCode, ReportParams params, long rowCount, long durationMs) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("template_code", templateCode);
        m.put("params", params.toMap());
        m.put("row_count", rowCount);
        m.put("duration_ms", durationMs);
        auditService.record(request(user, ACTION_GENERATE, templateCode, "success", 200, m));
    }

    /**
     * AC-30/AC-35: a quota rejection (429) creates no job and writes no audit row. Without this, the generic
     * business-failure audit in {@code ApiExceptionHandler} would log a {@code report.request_rejected} entry whose
     * metadata carries the free-text error message, which is outside the module's metadata allowlist.
     */
    public void skipAuditForQuotaRejection() {
        auditService.markRecordedOnCurrentRequest();
    }

    /** Written in the same transaction as the job INSERT (review #2: first of the two {@code report.export} rows). */
    public void exportCreated(KeycloakUser user, ReportExport job) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("template_code", job.getTemplateCode());
        m.put("format", job.getFormat());
        m.put("params", job.getParamsJson());
        m.put("export_id", job.getId().toString());
        if (job.getRetryOf() != null) {
            m.put("retry_of", job.getRetryOf().toString());
        }
        auditService.record(request(user, ACTION_EXPORT, job.getId().toString(), "success", 202, m));
    }

    /** Second {@code report.export} row, written by the runner: {@code result} = SUCCESS|FAILED. */
    public void exportFinished(ReportExport job, boolean success, Integer rowCount, long durationMs) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("template_code", job.getTemplateCode());
        m.put("format", job.getFormat());
        m.put("params", job.getParamsJson());
        m.put("export_id", job.getId().toString());
        m.put("result", success ? "SUCCESS" : "FAILED");
        if (rowCount != null) {
            m.put("row_count", rowCount);
        }
        m.put("duration_ms", durationMs);
        auditService.record(new AuditService.AuditEntry(
                job.getRequestedByUsername(), job.getRequestedBySub(), ACTION_EXPORT, "report",
                job.getId().toString(), success ? "success" : "failure", null, null, null, null, m));
    }

    public void download(KeycloakUser user, ReportExport job) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("template_code", job.getTemplateCode());
        m.put("format", job.getFormat());
        m.put("export_id", job.getId().toString());
        auditService.record(request(user, ACTION_DOWNLOAD, job.getId().toString(), "success", 200, m));
    }

    private AuditService.AuditEntry request(
            KeycloakUser user, String action, String resourceId, String outcome, int status, Map<String, Object> m) {
        return new AuditService.AuditEntry(
                user.preferredUsername() != null ? user.preferredUsername() : user.sub(), user.sub(), action,
                "report", resourceId, outcome, ctx.method(), ctx.path(), status, ctx.ip(), m);
    }
}
