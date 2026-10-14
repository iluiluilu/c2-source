package com.aismartstore.backend.reporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Covers the synchronous run (AC-6..11, 18), parameter validation (AC-9), saved reports (AC-11) and the audit allowlist
 * (AC-34/35: ReportAuditMetadataIT) end to end over HTTP.
 */
class ReportApiIT extends ReportITBase {

    private static final String SAME_MESSAGE = ReportParamValidator.INVALID_FILTERS_MESSAGE;

    private final String me = "api-" + UUID.randomUUID();
    private final JwtRequestPostProcessor asMe = canBo(me);

    private ResultActions run(String template, Map<String, Object> params, JwtRequestPostProcessor who) throws Exception {
        return mockMvc.perform(post("/reports/{t}/run", template)
                .with(who)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(params)));
    }

    private static Map<String, Object> period(Object... extra) {
        Map<String, Object> m = new HashMap<>(Map.of("from", "2026-03-01", "to", "2026-03-31"));
        for (int i = 0; i < extra.length; i += 2) {
            m.put((String) extra[i], extra[i + 1]);
        }
        return m;
    }

    // ---- templates (AC-6) -------------------------------------------------------------------------

    @Test
    void catalogueHasExactlyTheThreeClosedTemplatesWithParams() throws Exception {
        mockMvc.perform(get("/reports/templates").with(asMe))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].code").value("REPO_SUMMARY"))
                .andExpect(jsonPath("$[1].code").value("DIGITIZATION_PROGRESS"))
                .andExpect(jsonPath("$[2].code").value("DOC_BY_CLASSIFICATION"))
                .andExpect(jsonPath("$[0].name").isNotEmpty())
                .andExpect(jsonPath("$[0].description").isNotEmpty())
                .andExpect(jsonPath("$[0].params.length()").value(7));
        // no endpoint to create/modify templates exists (AC-36)
        mockMvc.perform(post("/reports/templates").with(asMe).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().is4xxClientError());
        mockMvc.perform(delete("/reports/templates/REPO_SUMMARY").with(asMe)).andExpect(status().is4xxClientError());
    }

    // ---- run + validation (AC-9, AC-10, AC-18) -----------------------------------------------------

    @Test
    void runReturnsTheDtoWithScopeNoticeFiltersAndAuditsGenerate() throws Exception {
        insertDoc(me, "application/pdf", 10, OffsetDateTime.of(2026, 3, 5, 0, 0, 0, 0, ZoneOffset.UTC));
        UUID topic = insertTag("topic", "Tên nhãn bí mật", true);
        run("REPO_SUMMARY", period("topicTagIds", List.of(topic)), asMe)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.templateCode").value("REPO_SUMMARY"))
                .andExpect(jsonPath("$.title").isNotEmpty())
                .andExpect(jsonPath("$.period.from").value("2026-03-01"))
                .andExpect(jsonPath("$.filters['Chủ đề']").value("Tên nhãn bí mật"))
                .andExpect(jsonPath("$.scopeNotice").value(org.hamcrest.Matchers.startsWith("Số liệu trong phạm vi truy cập của " + me + " tại ")))
                .andExpect(jsonPath("$.generatedBy").value(me))
                .andExpect(jsonPath("$.tables.length()").value(4))
                .andExpect(jsonPath("$.charts.length()").value(1))
                .andExpect(jsonPath("$.notes[?(@ =~ /.*PostgreSQL.*/)]").isNotEmpty());

        List<Map<String, Object>> rows = auditRows("report.generate", me);
        assertThat(rows).hasSize(1);
        String metadata = rows.get(0).get("metadata").toString();
        // AC-35: only allowlisted keys, ids not names, no figures
        assertThat(metadata).contains("template_code").contains("row_count").contains("duration_ms").contains(topic.toString());
        assertThat(metadata).doesNotContain("Tên nhãn bí mật");
        JsonNode node = objectMapper.readTree(metadata);
        node.fieldNames().forEachRemaining(k ->
                assertThat(Set.of("template_code", "params", "row_count", "duration_ms")).contains(k));
    }

    @Test
    void emptyDataIsAnEmptyReportWithAWarningNotAnError() throws Exception {
        run("DOC_BY_CLASSIFICATION", period(), asMe)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.empty").value(true))
                .andExpect(jsonPath("$.warnings.length()").value(1));
    }

    @Test
    void invalidParametersAre400BeforeAnyQueryAndAuditNothingForGenerate() throws Exception {
        run("REPO_SUMMARY", Map.of("to", "2026-03-31"), asMe).andExpect(status().isBadRequest());
        run("REPO_SUMMARY", Map.of("from", "2026-03-01"), asMe).andExpect(status().isBadRequest());
        run("REPO_SUMMARY", Map.of("from", "2026-04-01", "to", "2026-03-01"), asMe).andExpect(status().isBadRequest());
        run("REPO_SUMMARY", period("granularity", "week"), asMe)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("day, month, quarter, year")));
        run("REPO_SUMMARY", Map.of("from", "not-a-date", "to", "2026-03-01"), asMe).andExpect(status().isBadRequest());
        // too many buckets: day granularity over ~3 years -> 400 with advice (Q5)
        run("REPO_SUMMARY", Map.of("from", "2023-01-01", "to", "2026-03-31", "granularity", "day"), asMe)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("tháng/quý/năm")));
        // unknown template -> 404
        run("NOPE", period(), asMe).andExpect(status().isNotFound());
        assertThat(auditRows("report.generate", me)).isEmpty();
    }

    @Test
    void nonexistentOutOfScopeAndWrongKindIdsAreIndistinguishable() throws Exception {
        UUID groupIAmNotIn = insertGroup("Nhóm khác", "someone-else");
        UUID docType = insertTag("doc_type", "Loại", true);
        UUID topic = insertTag("topic", "Chủ đề", true);
        List<Map<String, Object>> badParams = List.of(
                period("groupIds", List.of(UUID.randomUUID())), // does not exist
                period("groupIds", List.of(groupIAmNotIn)), // exists but I am not a member
                period("topicTagIds", List.of(UUID.randomUUID())),
                period("topicTagIds", List.of(docType)), // wrong kind
                period("docTypeTagIds", List.of(topic)), // wrong kind
                period("folderId", UUID.randomUUID()));
        String firstBody = null;
        for (Map<String, Object> p : badParams) {
            String body = run("REPO_SUMMARY", p, asMe)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value(SAME_MESSAGE))
                    .andReturn()
                    .getResponse()
                    .getContentAsString();
            JsonNode n = objectMapper.readTree(body);
            String normalized = n.get("statusCode") + "|" + n.get("message");
            if (firstBody == null) {
                firstBody = normalized;
            }
            assertThat(normalized).isEqualTo(firstBody);
        }
    }

    @Test
    void matrixIsPagedByRowButTotalsStayFull() throws Exception {
        OffsetDateTime t = OffsetDateTime.of(2026, 3, 5, 0, 0, 0, 0, ZoneOffset.UTC);
        for (int i = 0; i < 5; i++) {
            UUID topic = insertTag("topic", "Chủ đề " + i, true);
            UUID doc = insertDoc(me, "application/pdf", 1, t);
            tagDoc(doc, topic, "topic");
        }
        Map<String, Object> req = new HashMap<>(period("page", 1, "size", 2));
        run("DOC_BY_CLASSIFICATION", req, asMe)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tables[0].id").value("m1"))
                .andExpect(jsonPath("$.tables[0].paged").value(true))
                .andExpect(jsonPath("$.tables[0].rows.length()").value(2))
                .andExpect(jsonPath("$.tables[0].totalRows").value(6));
        run("DOC_BY_CLASSIFICATION", new HashMap<>(period("size", 0)), asMe).andExpect(status().isBadRequest());
        run("DOC_BY_CLASSIFICATION", new HashMap<>(period("page", -1)), asMe).andExpect(status().isBadRequest());
    }

    // ---- saved reports (AC-11) ----------------------------------------------------------------------

    private String save(String name, Map<String, Object> params, JwtRequestPostProcessor who, int expectedStatus) throws Exception {
        return mockMvc.perform(post("/reports/saved")
                        .with(who)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", name, "templateCode", "REPO_SUMMARY", "params", params))))
                .andExpect(status().is(expectedStatus))
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    @Test
    void savedReportsLifecycleIsPrivateAndValidated() throws Exception {
        String body = save("Báo cáo quý 1", period(), asMe, 201);
        UUID id = UUID.fromString(objectMapper.readTree(body).get("id").asText());

        // unique per person, case/space-insensitive; another person may reuse the name
        save("  BÁO CÁO QUÝ 1 ", period(), asMe, 400);
        save("Báo cáo quý 1", period(), canBo("other-" + UUID.randomUUID()), 201);
        // name 1..100 chars
        save("", period(), asMe, 400);
        save("x".repeat(101), period(), asMe, 400);
        save("x".repeat(100), period(), asMe, 201);
        // invalid params are rejected like a run
        save("bad", Map.of("from", "2026-05-01", "to", "2026-01-01"), asMe, 400);

        mockMvc.perform(get("/reports/saved").with(asMe))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));

        // reopen restores the parameters
        mockMvc.perform(get("/reports/saved/{id}", id).with(asMe))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.params.from").value("2026-03-01"))
                .andExpect(jsonPath("$.warnings.length()").value(0));

        // somebody else gets 404 (not 403) for get/rename/delete
        JwtRequestPostProcessor other = canBo("other2-" + UUID.randomUUID());
        mockMvc.perform(get("/reports/saved/{id}", id).with(other)).andExpect(status().isNotFound());
        mockMvc.perform(patch("/reports/saved/{id}", id).with(other).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"hack\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/reports/saved/{id}", id).with(other)).andExpect(status().isNotFound());

        // rename, and a rename onto an existing name is refused
        mockMvc.perform(patch("/reports/saved/{id}", id).with(asMe).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Mới\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Mới"));
        mockMvc.perform(patch("/reports/saved/{id}", id).with(asMe).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + "x".repeat(100) + "\"}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(delete("/reports/saved/{id}", id).with(asMe)).andExpect(status().isNoContent());
        mockMvc.perform(get("/reports/saved/{id}", id).with(asMe)).andExpect(status().isNotFound());
    }

    @Test
    void atMostFiftySavedReportsPerPerson() throws Exception {
        for (int i = 0; i < 50; i++) {
            save("r" + i, period(), asMe, 201);
        }
        save("one-too-many", period(), asMe, 400);
        save("fine-for-someone-else", period(), canBo("p-" + UUID.randomUUID()), 201);
    }

    @Test
    void reopeningDropsGoneOrOutOfScopeIdsWithOneGenericWarning() throws Exception {
        UUID topic = insertTag("topic", "Sẽ bị xoá", true);
        UUID folder = insertFolder("tmp-" + UUID.randomUUID().toString().substring(0, 8), null, new UUID[0]);
        UUID group = insertGroup("G", me);
        String body = save("Có lọc", period("topicTagIds", List.of(topic), "folderId", folder, "groupIds", List.of(group)), asMe, 201);
        UUID id = UUID.fromString(objectMapper.readTree(body).get("id").asText());

        // nothing changed -> no warning
        mockMvc.perform(get("/reports/saved/{id}", id).with(asMe))
                .andExpect(jsonPath("$.warnings.length()").value(0))
                .andExpect(jsonPath("$.params.topicTagIds.length()").value(1));

        // the tag is deleted, the folder is removed, and I leave the group
        jdbc.update("DELETE FROM tag WHERE id = ?", topic);
        jdbc.update("DELETE FROM warehouse_folder WHERE id = ?", folder);
        jdbc.update("DELETE FROM group_member WHERE group_id = ? AND user_sub = ?", group, me);

        mockMvc.perform(get("/reports/saved/{id}", id).with(asMe))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.warnings.length()").value(1))
                .andExpect(jsonPath("$.warnings[0]").value(SavedQueryService.WARNING_PARAMS_DROPPED))
                .andExpect(jsonPath("$.params.topicTagIds.length()").value(0))
                .andExpect(jsonPath("$.params.groupIds.length()").value(0))
                .andExpect(jsonPath("$.params.folderId").doesNotExist());
    }

    // ---- audit allowlist (AC-35) ---------------------------------------------------------------------

    @Test
    void auditMetadataOfEveryReportActionContainsOnlyAllowlistedKeys() throws Exception {
        Map<String, Set<String>> allowed = Map.of(
                "report.generate", Set.of("template_code", "params", "row_count", "duration_ms"),
                "report.export", Set.of("template_code", "format", "params", "row_count", "duration_ms", "export_id", "result", "retry_of"),
                "report.download", Set.of("template_code", "format", "export_id"),
                "report.access_denied", Set.of("permission", "reason", "export_id", "template_code"));
        // generate
        run("REPO_SUMMARY", period(), asMe).andExpect(status().isOk());
        // export (create) + denied
        mockMvc.perform(post("/reports/exports").with(asMe).contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("templateCode", "REPO_SUMMARY", "format", "PDF", "params", period()))))
                .andExpect(status().isAccepted());
        String denied = "deny-" + UUID.randomUUID();
        mockMvc.perform(get("/reports/templates").with(quanTri(denied))).andExpect(status().isForbidden());

        List<Map<String, Object>> all = new ArrayList<>();
        all.addAll(auditRows("report.generate", me));
        all.addAll(auditRows("report.export", me));
        all.addAll(auditRows("report.access_denied", denied));
        assertThat(all).hasSize(3);
        for (Map<String, Object> row : all) {
            JsonNode md = objectMapper.readTree(row.get("metadata").toString());
            String action = md.has("permission") ? "report.access_denied" : md.has("format") ? "report.export" : "report.generate";
            md.fieldNames().forEachRemaining(k -> assertThat(allowed.get(action)).as(action + "." + k).contains(k));
        }
    }
}
