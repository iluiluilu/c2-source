package com.aismartstore.backend.reporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aismartstore.backend.auth.KeycloakUser;
import com.aismartstore.backend.systemconfig.SystemConfigDefaults;
import com.aismartstore.backend.systemconfig.SystemConfigService;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/**
 * SPEC §5 ReportExportFlowIT (AC-21..33, review #2, #5, Q6): the whole export life cycle over HTTP + the runner/cleanup
 * driven synchronously ({@code app.reporting.runner-enabled=false} keeps the scheduler away).
 */
class ReportExportFlowIT extends ReportITBase {

    private static final String BUCKET = "reports";

    @Autowired
    private ReportExportJobRunner runner;

    @Autowired
    private ReportExportCleanupJob cleanup;

    @Autowired
    private SystemConfigService systemConfigService;

    private final FakeStorage storage = new FakeStorage();

    /** audit_log is append-only (no truncate between tests), so every test gets its own subjects. */
    private final String alice = "alice-" + UUID.randomUUID();
    private final String bob = "bob-" + UUID.randomUUID();
    private final String carol = "carol-" + UUID.randomUUID();

    @BeforeEach
    void setUp() {
        storage.install(objectStorageService);
        KeycloakUser admin = new KeycloakUser("admin", "admin", List.of("quan-tri"));
        systemConfigService.update(SystemConfigDefaults.REPORTING_MAX_ROWS_PER_EXPORT, 100_000, admin);
        systemConfigService.update(SystemConfigDefaults.REPORTING_MAX_EXPORTS_PER_USER, 3, admin);
        systemConfigService.update(SystemConfigDefaults.REPORTING_MAX_CONCURRENT_EXPORTS, 10, admin);
        // a clean inventory so rows and numbers are predictable
        insertDoc(alice, "application/pdf", 100, OffsetDateTime.of(2026, 3, 5, 0, 0, 0, 0, ZoneOffset.UTC));
    }

    private static String body(String template, String format) {
        return json(Map.of(
                "templateCode", template, "format", format,
                "params", Map.of("from", "2026-03-01", "to", "2026-03-31", "granularity", "month")));
    }

    private UUID createExport(String sub, String template, String format) throws Exception {
        MvcResult r = mockMvc.perform(post("/reports/exports")
                        .with(canBo(sub))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(template, format)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn();
        JsonNode n = objectMapper.readTree(r.getResponse().getContentAsString());
        return UUID.fromString(n.get("exportId").asText());
    }

    private String statusOf(UUID id) {
        return jdbc.queryForObject("SELECT status FROM report_export WHERE id = ?", String.class, id);
    }

    @Test
    void createRunDownloadHappyPath_withTwoExportAuditRowsAndOwnerOnlyAccess() throws Exception {
        UUID id = createExport(alice, ReportTemplateRegistry.REPO_SUMMARY, "PDF");
        assertThat(statusOf(id)).isEqualTo("PENDING");
        // creation audit row (review #2): same export_id, no result yet
        assertThat(auditRows("report.export", alice)).hasSize(1);

        // download while PENDING -> 409
        mockMvc.perform(get("/reports/exports/{id}/download", id).with(canBo(alice)))
                .andExpect(status().isConflict());

        assertThat(runner.runOnce()).isEqualTo(1);
        assertThat(statusOf(id)).isEqualTo("DONE");
        assertThat(storage.has(BUCKET, id + ".pdf")).isTrue(); // key without a repeated bucket prefix

        mockMvc.perform(get("/reports/exports/{id}", id).with(canBo(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DONE"))
                .andExpect(jsonPath("$.expiresAt").isNotEmpty());

        MvcResult dl = mockMvc.perform(get("/reports/exports/{id}/download", id).with(canBo(alice)))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(dl.getResponse().getHeader("Content-Disposition"))
                .startsWith("attachment")
                .containsPattern("repo_summary_\\d{8}\\.pdf");
        assertThat(new String(dl.getResponse().getContentAsByteArray(), 0, 4)).isEqualTo("%PDF");

        // another user (same role) cannot read status or download, and it is audited (AC-31)
        mockMvc.perform(get("/reports/exports/{id}", id).with(canBo(bob))).andExpect(status().isForbidden());
        mockMvc.perform(get("/reports/exports/{id}/download", id).with(canBo(bob))).andExpect(status().isForbidden());
        assertThat(auditRows("report.access_denied", bob)).hasSize(2);

        // two report.export rows share the export_id; the second carries the result (review #2)
        List<Map<String, Object>> exportRows = auditRows("report.export", alice);
        assertThat(exportRows).hasSize(2);
        assertThat(exportRows).allSatisfy(r -> assertThat(r.get("resource_id")).isEqualTo(id.toString()));
        assertThat(exportRows.get(0).get("metadata").toString()).contains(id.toString()).doesNotContain("\"result\"");
        assertThat(exportRows.get(1).get("metadata").toString()).contains("\"result\": \"SUCCESS\"");
        assertThat(auditRows("report.download", alice)).hasSize(1);

        // history lists only my jobs
        mockMvc.perform(get("/reports/exports").with(canBo(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1));
        mockMvc.perform(get("/reports/exports").with(canBo(bob)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0));
    }

    @Test
    void xlsxAndDocxJobsComplete() throws Exception {
        UUID x = createExport(alice, ReportTemplateRegistry.DOC_BY_CLASSIFICATION, "XLSX");
        UUID d = createExport(alice, ReportTemplateRegistry.DIGITIZATION_PROGRESS, "DOCX");
        runner.runOnce();
        assertThat(statusOf(x)).isEqualTo("DONE");
        assertThat(statusOf(d)).isEqualTo("DONE");
        assertThat(storage.has(BUCKET, x + ".xlsx")).isTrue();
        assertThat(storage.has(BUCKET, d + ".docx")).isTrue();
    }

    @Test
    void jobLeftRunningWithAnExpiredLeaseIsReclaimedAfterARestart() throws Exception {
        UUID id = createExport(alice, ReportTemplateRegistry.REPO_SUMMARY, "PDF");
        jdbc.update(
                "UPDATE report_export SET status='RUNNING', attempt_count=1, lease_expires_at = now() - interval '1 minute' WHERE id=?",
                id);
        assertThat(runner.runOnce()).isEqualTo(1);
        assertThat(statusOf(id)).isEqualTo("DONE");
        assertThat(jdbc.queryForObject("SELECT attempt_count FROM report_export WHERE id=?", Integer.class, id))
                .isEqualTo(2);
    }

    @Test
    void jobWithAValidLeaseIsNotStolen() throws Exception {
        UUID id = createExport(alice, ReportTemplateRegistry.REPO_SUMMARY, "PDF");
        jdbc.update("UPDATE report_export SET status='RUNNING', attempt_count=1, lease_expires_at = now() + interval '4 minute' WHERE id=?", id);
        assertThat(runner.runOnce()).isZero();
        assertThat(statusOf(id)).isEqualTo("RUNNING");
    }

    @Test
    void tooManyAttemptsFailsTheJob() throws Exception {
        UUID id = createExport(alice, ReportTemplateRegistry.REPO_SUMMARY, "PDF");
        jdbc.update("UPDATE report_export SET status='RUNNING', attempt_count=3, lease_expires_at = now() - interval '1 minute' WHERE id=?", id);
        runner.runOnce();
        assertThat(statusOf(id)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT error_message FROM report_export WHERE id=?", String.class, id))
                .contains("Quá số lần thử");
    }

    @Test
    void reportWithMoreRowsThanTheLimitFailsWithoutProducingAFile() throws Exception {
        KeycloakUser admin = new KeycloakUser("admin", "admin", List.of("quan-tri"));
        systemConfigService.update(SystemConfigDefaults.REPORTING_MAX_ROWS_PER_EXPORT, 3, admin);
        UUID id = createExport(alice, ReportTemplateRegistry.REPO_SUMMARY, "PDF"); // REPO_SUMMARY has > 3 rows
        runner.runOnce();
        assertThat(statusOf(id)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT error_message FROM report_export WHERE id=?", String.class, id))
                .contains("quá lớn");
        assertThat(storage.objects).isEmpty();
        List<Map<String, Object>> rows = auditRows("report.export", alice);
        assertThat(rows.get(rows.size() - 1).get("metadata").toString()).contains("\"result\": \"FAILED\"");
    }

    @Test
    void renderFailureGivesAGenericMessageAndCanBeRetriedAsANewJob() throws Exception {
        UUID id = createExport(alice, ReportTemplateRegistry.REPO_SUMMARY, "PDF");
        org.mockito.Mockito.doThrow(new RuntimeException("S3 secret detail xyz"))
                .when(objectStorageService)
                .putObject(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(java.io.File.class),
                        org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
        runner.runOnce();
        assertThat(statusOf(id)).isEqualTo("FAILED");
        String message = jdbc.queryForObject("SELECT error_message FROM report_export WHERE id=?", String.class, id);
        assertThat(message).doesNotContain("secret").doesNotContain("xyz");

        // retry only for FAILED jobs, creating a NEW job that links back, with its own audit row
        storage.install(objectStorageService);
        MvcResult r = mockMvc.perform(post("/reports/exports/{id}/retry", id).with(canBo(alice)))
                .andExpect(status().isAccepted())
                .andReturn();
        UUID newId = UUID.fromString(objectMapper.readTree(r.getResponse().getContentAsString()).get("exportId").asText());
        assertThat(newId).isNotEqualTo(id);
        assertThat(jdbc.queryForObject("SELECT retry_of FROM report_export WHERE id=?", UUID.class, newId)).isEqualTo(id);
        assertThat(statusOf(id)).isEqualTo("FAILED");
        runner.runOnce();
        assertThat(statusOf(newId)).isEqualTo("DONE");
        // retry on a non-FAILED job -> 409; on somebody else's job -> 403
        mockMvc.perform(post("/reports/exports/{id}/retry", newId).with(canBo(alice))).andExpect(status().isConflict());
        mockMvc.perform(post("/reports/exports/{id}/retry", id).with(canBo(bob))).andExpect(status().isForbidden());
    }

    @Test
    void perUserLimitRejectsWith429_andCreatesNoRecordOrExportAudit() throws Exception {
        for (int i = 0; i < 3; i++) {
            createExport(alice, ReportTemplateRegistry.REPO_SUMMARY, "PDF");
        }
        long exportAudits = auditCount("report.export");
        mockMvc.perform(post("/reports/exports")
                        .with(canBo(alice))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(ReportTemplateRegistry.REPO_SUMMARY, "PDF")))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("3 tệp")));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM report_export", Long.class)).isEqualTo(3L);
        assertThat(auditCount("report.export")).isEqualTo(exportAudits);
        // another user is not blocked by alice's quota
        createExport(bob, ReportTemplateRegistry.REPO_SUMMARY, "PDF");
    }

    @Test
    void systemWideLimitRejectsWith429AndADifferentMessage() throws Exception {
        KeycloakUser admin = new KeycloakUser("admin", "admin", List.of("quan-tri"));
        systemConfigService.update(SystemConfigDefaults.REPORTING_MAX_CONCURRENT_EXPORTS, 2, admin);
        createExport(alice, ReportTemplateRegistry.REPO_SUMMARY, "PDF");
        createExport(bob, ReportTemplateRegistry.REPO_SUMMARY, "PDF");
        long exportAudits = auditCount("report.export");
        mockMvc.perform(post("/reports/exports")
                        .with(canBo(carol))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(ReportTemplateRegistry.REPO_SUMMARY, "PDF")))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Hệ thống đang bận")));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM report_export", Long.class)).isEqualTo(2L);
        assertThat(auditCount("report.export")).isEqualTo(exportAudits);
    }

    @Test
    void expiredFilesAreCleanedUpAndDownloadAnswers410() throws Exception {
        UUID id = createExport(alice, ReportTemplateRegistry.REPO_SUMMARY, "PDF");
        runner.runOnce();
        assertThat(storage.has(BUCKET, id + ".pdf")).isTrue();
        // not yet expired -> sweep leaves it
        assertThat(cleanup.sweep()).isZero();
        jdbc.update("UPDATE report_export SET expires_at = now() - interval '1 hour' WHERE id = ?", id);
        assertThat(cleanup.sweep()).isEqualTo(1);
        assertThat(statusOf(id)).isEqualTo("EXPIRED");
        assertThat(storage.has(BUCKET, id + ".pdf")).isFalse();
        assertThat(cleanup.sweep()).isZero(); // idempotent
        mockMvc.perform(get("/reports/exports/{id}/download", id).with(canBo(alice))).andExpect(status().isGone());
        mockMvc.perform(get("/reports/exports/{id}", id).with(canBo(alice)))
                .andExpect(jsonPath("$.status").value("EXPIRED"));
    }

    @Test
    void revokingReportExportWhileTheJobIsPendingStillFinishesButDownloadIsForbidden() throws Exception {
        UUID id = createExport(alice, ReportTemplateRegistry.REPO_SUMMARY, "PDF");
        // R3 revokes report.export now; the runner does not re-check (Q6)
        runner.runOnce();
        assertThat(statusOf(id)).isEqualTo("DONE");
        mockMvc.perform(get("/reports/exports/{id}/download", id).with(canBoWithout(alice, "report.export")))
                .andExpect(status().isForbidden());
        // report.read is untouched: viewing reports still works (AC-5)
        mockMvc.perform(get("/reports/templates").with(canBoWithout(alice, "report.export")))
                .andExpect(status().isOk());
    }

    @Test
    void aReclaimedDuplicateRunNeverOverwritesOrDeletesTheWinnersResult() throws Exception {
        UUID id = createExport(alice, ReportTemplateRegistry.REPO_SUMMARY, "PDF");
        ReportExport stale = claimWithoutProcessing(id);
        // run A (the reclaimer) wins and records DONE
        runner.process(stale);
        assertThat(statusOf(id)).isEqualTo("DONE");
        byte[] winnerBytes = storage.objects.get(BUCKET + "/" + id + ".pdf");
        // run B (the original, stale holder of the lease) finishes late: its conditional update touches 0 rows
        runner.process(stale);
        assertThat(statusOf(id)).isEqualTo("DONE");
        assertThat(storage.has(BUCKET, id + ".pdf")).as("winner's file must survive").isTrue();
        assertThat(winnerBytes).isNotNull();
        // exactly one SUCCESS result row, not two
        long success = auditRows("report.export", alice).stream()
                .filter(r -> r.get("metadata").toString().contains("SUCCESS"))
                .count();
        assertThat(success).isEqualTo(1);
    }

    private ReportExport claimWithoutProcessing(UUID id) {
        List<ReportExport> claimed = jdbcClaim();
        return claimed.stream().filter(j -> j.getId().equals(id)).findFirst().orElseThrow();
    }

    @Autowired
    private ReportExportRepository exportRepository;

    private List<ReportExport> jdbcClaim() {
        return exportRepository.claimNextBatch(ReportExportJobRunner.LEASE_SECONDS, 5);
    }

    @Test
    void invalidRequestsAre400AndNeverCreateAJob() throws Exception {
        mockMvc.perform(post("/reports/exports")
                        .with(canBo(alice))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(ReportTemplateRegistry.REPO_SUMMARY, "CSV")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/reports/exports")
                        .with(canBo(alice))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("NOPE", "PDF")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/reports/exports")
                        .with(canBo(alice))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("templateCode", "REPO_SUMMARY", "format", "PDF",
                                "params", Map.of("from", "2026-05-01", "to", "2026-03-01")))))
                .andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM report_export", Long.class)).isZero();
    }
}
