package com.aismartstore.backend.reporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aismartstore.backend.auth.KeycloakUser;
import com.aismartstore.backend.systemconfig.SystemConfigDefaults;
import com.aismartstore.backend.systemconfig.SystemConfigService;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

/**
 * SPEC §5 ReportTimeoutIT (AC-20): a statistics query that cannot finish within {@code reporting.query_timeout_seconds}
 * ends with 503 + a "narrow the filters" hint instead of hanging. The slowness is produced deterministically by holding
 * an ACCESS EXCLUSIVE lock on {@code document} from another connection (statement_timeout also bounds lock waits).
 */
class ReportTimeoutIT extends ReportITBase {

    @Autowired
    private SystemConfigService systemConfigService;

    private Connection locker;

    @AfterEach
    void release() throws Exception {
        if (locker != null) {
            locker.rollback();
            locker.close();
        }
        systemConfigService.update(
                SystemConfigDefaults.REPORTING_QUERY_TIMEOUT_SECONDS, 15, new KeycloakUser("admin", "admin", List.of()));
    }

    @Test
    void queryOverTheTimeoutAnswers503WithAHintAndDoesNotHang() throws Exception {
        systemConfigService.update(
                SystemConfigDefaults.REPORTING_QUERY_TIMEOUT_SECONDS, 1, new KeycloakUser("admin", "admin", List.of()));
        locker = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        locker.setAutoCommit(false);
        try (Statement s = locker.createStatement()) {
            s.execute("LOCK TABLE document IN ACCESS EXCLUSIVE MODE");
        }
        String sub = "t-" + UUID.randomUUID();
        long started = System.currentTimeMillis();
        mockMvc.perform(post("/reports/REPO_SUMMARY/run")
                        .with(canBo(sub))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"from\":\"2026-03-01\",\"to\":\"2026-03-31\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("thu hẹp")));
        assertThat(System.currentTimeMillis() - started).isLessThan(10_000);
        // no report.generate audit for a run that produced nothing
        assertThat(auditRows("report.generate", sub)).isEmpty();
    }
}
