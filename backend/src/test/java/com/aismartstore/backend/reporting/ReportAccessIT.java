package com.aismartstore.backend.reporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;

/**
 * SPEC §5 ReportAccessIT (AC-1, AC-2, AC-5, review #4): R1/R2 allowed; R3/R4/token without {@code sub} -> 403 (+ {@code
 * report.access_denied} when the subject is known); no token -> 401; {@code report.export} revoked on its own leaves
 * {@code report.read} intact.
 */
class ReportAccessIT extends ReportITBase {

    private static final String RUN_BODY = "{\"from\":\"2026-03-01\",\"to\":\"2026-03-31\"}";

    private void assertAllEndpointsForbidden(JwtRequestPostProcessor who) throws Exception {
        UUID id = UUID.randomUUID();
        mockMvc.perform(get("/reports/templates").with(who)).andExpect(status().isForbidden());
        mockMvc.perform(post("/reports/REPO_SUMMARY/run").with(who).contentType(MediaType.APPLICATION_JSON).content(RUN_BODY))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/reports/saved").with(who)).andExpect(status().isForbidden());
        mockMvc.perform(post("/reports/saved").with(who).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/reports/saved/{id}", id).with(who)).andExpect(status().isForbidden());
        mockMvc.perform(patch("/reports/saved/{id}", id).with(who).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/reports/saved/{id}", id).with(who)).andExpect(status().isForbidden());
        mockMvc.perform(post("/reports/exports").with(who).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/reports/exports").with(who)).andExpect(status().isForbidden());
        mockMvc.perform(get("/reports/exports/{id}", id).with(who)).andExpect(status().isForbidden());
        mockMvc.perform(get("/reports/exports/{id}/download", id).with(who)).andExpect(status().isForbidden());
        mockMvc.perform(post("/reports/exports/{id}/retry", id).with(who)).andExpect(status().isForbidden());
    }

    @Test
    void r1AndR2CanOpenRunSaveExport() throws Exception {
        for (JwtRequestPostProcessor who : List.of(canBo("r1-" + UUID.randomUUID()), lanhDao("r2-" + UUID.randomUUID()))) {
            mockMvc.perform(get("/reports/templates").with(who)).andExpect(status().isOk());
            mockMvc.perform(post("/reports/REPO_SUMMARY/run").with(who).contentType(MediaType.APPLICATION_JSON).content(RUN_BODY))
                    .andExpect(status().isOk());
            mockMvc.perform(get("/reports/saved").with(who)).andExpect(status().isOk());
            mockMvc.perform(get("/reports/exports").with(who)).andExpect(status().isOk());
        }
    }

    @Test
    void r3AdminIsForbiddenEverywhereAndEveryDenialIsAudited() throws Exception {
        String sub = "r3-" + UUID.randomUUID();
        assertAllEndpointsForbidden(quanTri(sub));
        assertThat(auditRows("report.access_denied", sub)).hasSize(12);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM report_export", Long.class)).isZero();
    }

    @Test
    void r4UserWithoutAnyRoleIsForbiddenAndAudited() throws Exception {
        String sub = "r4-" + UUID.randomUUID();
        JwtRequestPostProcessor nobody = jwt().jwt(b -> b.subject(sub).claim("preferred_username", sub)).authorities();
        assertAllEndpointsForbidden(nobody);
        assertThat(auditRows("report.access_denied", sub)).hasSize(12);
    }

    @Test
    void tokenWithoutSubjectIsForbiddenAndCannotBeAttributed() throws Exception {
        long before = auditCount("report.access_denied");
        JwtRequestPostProcessor noSub = jwt().jwt(b -> b.claim("preferred_username", "ghost").claims(c -> c.remove("sub")))
                .authorities(com.aismartstore.backend.testsupport.RolePermissionFixtures.authoritiesFor("CAN_BO"));
        mockMvc.perform(get("/reports/templates").with(noSub)).andExpect(status().isForbidden());
        mockMvc.perform(post("/reports/REPO_SUMMARY/run").with(noSub).contentType(MediaType.APPLICATION_JSON).content(RUN_BODY))
                .andExpect(status().isForbidden());
        assertThat(auditCount("report.access_denied")).isEqualTo(before); // no subject -> nothing to attribute (review #4)
    }

    @Test
    void noTokenIsRejectedWith401BySecurityBeforeTheGuard() throws Exception {
        mockMvc.perform(get("/reports/templates")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/reports/exports").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void revokingOnlyReportExportKeepsReadAndBlocksExportEndpoints() throws Exception {
        String sub = "r1-" + UUID.randomUUID();
        JwtRequestPostProcessor noExport = canBoWithout(sub, "report.export");
        mockMvc.perform(get("/reports/templates").with(noExport)).andExpect(status().isOk());
        mockMvc.perform(post("/reports/REPO_SUMMARY/run").with(noExport).contentType(MediaType.APPLICATION_JSON).content(RUN_BODY))
                .andExpect(status().isOk());
        mockMvc.perform(get("/reports/exports").with(noExport)).andExpect(status().isForbidden());
        mockMvc.perform(post("/reports/exports").with(noExport).contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("templateCode", "REPO_SUMMARY", "format", "PDF"))))
                .andExpect(status().isForbidden());
        assertThat(auditRows("report.access_denied", sub)).hasSize(2);
    }

    @Test
    void revokingReportReadBlocksTemplatesAndRunButNotExportEndpoints() throws Exception {
        String sub = "r1-" + UUID.randomUUID();
        JwtRequestPostProcessor noRead = canBoWithout(sub, "report.read");
        mockMvc.perform(get("/reports/templates").with(noRead)).andExpect(status().isForbidden());
        mockMvc.perform(get("/reports/exports").with(noRead)).andExpect(status().isOk());
    }
}
