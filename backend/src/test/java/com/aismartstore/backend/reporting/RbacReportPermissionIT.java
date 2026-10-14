package com.aismartstore.backend.reporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aismartstore.backend.accounts.KeycloakAdminService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;

/** SPEC §5 RbacReportPermissionIT (AC-5): {@code report.*} cannot be granted to QUAN_TRI (400, nothing changes); R1/R2 grants are editable. */
class RbacReportPermissionIT extends ReportITBase {

    @MockBean
    private KeycloakAdminService kc;

    @BeforeEach
    void stubKeycloak() {
        when(kc.listRoleUsers(any(), anyInt())).thenReturn(List.of());
    }

    private List<String> permissionsOf(String role) {
        return jdbc.queryForList("SELECT permission_code FROM role_permission WHERE role_code = ? ORDER BY 1", String.class, role);
    }

    private String body(List<String> codes) {
        return json(Map.of("permissionCodes", codes));
    }

    @Test
    void v36SeedsReportPermissionsForR1AndR2Only() {
        assertThat(permissionsOf("CAN_BO")).contains("report.read", "report.export");
        assertThat(permissionsOf("LANH_DAO")).contains("report.read", "report.export");
        assertThat(permissionsOf("QUAN_TRI")).doesNotContain("report.read", "report.export");
    }

    @Test
    void permissionCatalogueExposesTheAdminAssignableFlag() throws Exception {
        mockMvc.perform(get("/admin/rbac/permissions").with(quanTri("admin-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.code=='report.read')].adminAssignable").value(false))
                .andExpect(jsonPath("$[?(@.code=='report.export')].adminAssignable").value(false))
                .andExpect(jsonPath("$[?(@.code=='account.manage')].adminAssignable").value(true));
    }

    @Test
    void grantingReportPermissionsToAdminIsRejectedWith400AndChangesNothing() throws Exception {
        List<String> before = permissionsOf("QUAN_TRI");
        for (String code : List.of("report.read", "report.export")) {
            List<String> desired = new ArrayList<>(before);
            desired.add(code);
            mockMvc.perform(put("/admin/rbac/roles/QUAN_TRI/permissions")
                            .with(quanTri("admin-1"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(desired)))
                    .andExpect(status().isBadRequest());
            mockMvc.perform(get("/admin/rbac/roles/QUAN_TRI/preview-impact")
                            .param("permissionCodes", desired.toArray(String[]::new))
                            .with(quanTri("admin-1")))
                    .andExpect(status().isBadRequest());
        }
        assertThat(permissionsOf("QUAN_TRI")).isEqualTo(before);
    }

    @Test
    void r3CanRevokeAndRegrantReportPermissionsForR1() throws Exception {
        List<String> all = permissionsOf("CAN_BO");
        List<String> withoutExport = new ArrayList<>(all);
        withoutExport.remove("report.export");
        mockMvc.perform(put("/admin/rbac/roles/CAN_BO/permissions")
                        .with(quanTri("admin-1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(withoutExport)))
                .andExpect(status().isOk());
        assertThat(permissionsOf("CAN_BO")).contains("report.read").doesNotContain("report.export");
        mockMvc.perform(put("/admin/rbac/roles/CAN_BO/permissions")
                        .with(quanTri("admin-1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(all)))
                .andExpect(status().isOk());
        assertThat(permissionsOf("CAN_BO")).contains("report.export");
    }
}
