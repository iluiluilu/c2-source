package com.aismartstore.backend.reporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aismartstore.backend.groups.ScopeResolutionException;
import com.aismartstore.backend.reporting.dto.ReportResult;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * SPEC §5 ReportScopeIT (AC-3, AC-16, AC-37, risk R-2 gate): two users with different scopes get different - and each
 * correct - numbers on ALL THREE templates, nothing out of scope leaks into any figure, and a missing user context fails
 * closed.
 */
class ReportScopeIT extends ReportITBase {

    @Autowired
    private ReportQueryService queryService;

    private static OffsetDateTime utc(String iso) {
        return OffsetDateTime.parse(iso).withOffsetSameInstant(ZoneOffset.UTC);
    }

    private static ReportParams march() {
        return new ReportParams(
                        LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31), List.of(), List.of(), List.of(), null,
                        "month")
                .normalized(Granularity.MONTH);
    }

    private static long cell(ReportResult r, String tableId, String key, Object keyValue, String col) {
        return r.tables().stream().filter(t -> t.id().equals(tableId)).findFirst().orElseThrow().rows().stream()
                .filter(m -> keyValue.equals(m.get(key))).findFirst().orElseThrow().get(col) instanceof Number n
                ? n.longValue()
                : -1;
    }

    @Test
    void differentScopesGiveDifferentCorrectNumbersOnEveryTemplate() {
        String a = "scope-a-" + UUID.randomUUID();
        String b = "scope-b-" + UUID.randomUUID();
        String outsider = "scope-x-" + UUID.randomUUID();
        UUID group = insertGroup("Nhóm B", outsider, b); // b is a member; a is not

        UUID topic = insertTag("topic", "Chủ đề", true);
        UUID type = insertTag("doc_type", "Loại", true);
        OffsetDateTime t = utc("2026-03-10T00:00:00Z");

        // a: 3 docs (one soft-deleted -> 2 count). b: 1 own doc. outsider: 2 docs, one shared to b's group.
        UUID a1 = insertDoc(a, "application/pdf", 10, t);
        UUID a2 = insertDoc(a, "application/pdf", 10, t);
        insertDoc(a, "application/pdf", 10, t, "clean", rootFolderId, true);
        UUID b1 = insertDoc(b, "image/png", 5, t);
        UUID x1 = insertDoc(outsider, "application/pdf", 7, t);
        UUID x2 = insertDoc(outsider, "application/pdf", 7, t);
        shareDoc(x1, group, outsider);
        for (UUID d : List.of(a1, a2, b1, x1, x2)) {
            tagDoc(d, topic, "topic");
            tagDoc(d, type, "doc_type");
            UUID src = insertSource(d, "original", "application/pdf", null);
            insertRun(d, src, "ocr", "queued", "not_applicable", null, null, t);
            setOverallStatus(d, "queued");
        }

        // ---- REPO_SUMMARY ----
        ReportResult aSummary = queryService.run(a, a, ReportTemplateRegistry.REPO_SUMMARY, march());
        ReportResult bSummary = queryService.run(b, b, ReportTemplateRegistry.REPO_SUMMARY, march());
        assertThat(cell(aSummary, "b1", "metric", "Tồn kho cuối kỳ", "value")).isEqualTo(2);
        assertThat(cell(bSummary, "b1", "metric", "Tồn kho cuối kỳ", "value")).isEqualTo(2); // own + shared
        assertThat(cell(aSummary, "b4", "status", "Đang xử lý", "count")).isEqualTo(2);
        assertThat(cell(bSummary, "b4", "status", "Đang xử lý", "count")).isEqualTo(2);
        assertThat(cell(aSummary, "b2", "group", "Văn bản/PDF", "count")).isEqualTo(2);
        assertThat(cell(bSummary, "b2", "group", "Văn bản/PDF", "count")).isEqualTo(1); // only x1 (shared)
        assertThat(cell(bSummary, "b2", "group", "Ảnh", "count")).isEqualTo(1);
        assertThat(cell(aSummary, "b2", "group", "Ảnh", "count")).isZero();
        assertThat(cell(aSummary, "b2", "group", "Văn bản/PDF", "size")).isEqualTo(20);
        assertThat(cell(bSummary, "b2", "group", "Văn bản/PDF", "size")).isEqualTo(7);

        // ---- DIGITIZATION_PROGRESS ----
        ReportResult aDig = queryService.run(a, a, ReportTemplateRegistry.DIGITIZATION_PROGRESS, march());
        ReportResult bDig = queryService.run(b, b, ReportTemplateRegistry.DIGITIZATION_PROGRESS, march());
        assertThat(cell(aDig, "d1", "task", "OCR (nhận dạng chữ)", "total")).isEqualTo(2);
        assertThat(cell(bDig, "d1", "task", "OCR (nhận dạng chữ)", "total")).isEqualTo(2); // b1 + x1
        assertThat(cell(bDig, "d1", "task", "OCR (nhận dạng chữ)", "processing")).isEqualTo(2);

        // ---- DOC_BY_CLASSIFICATION ----
        ReportResult aCls = queryService.run(a, a, ReportTemplateRegistry.DOC_BY_CLASSIFICATION, march());
        ReportResult bCls = queryService.run(b, b, ReportTemplateRegistry.DOC_BY_CLASSIFICATION, march());
        assertThat(cell(aCls, "t1", "label", "Chủ đề", "count")).isEqualTo(2);
        assertThat(cell(bCls, "t1", "label", "Chủ đề", "count")).isEqualTo(2);
        assertThat(cell(aCls, "m1", "topic", "Chủ đề", "dt0")).isEqualTo(2);

        // a user with nothing in scope sees an all-zero (empty) report on every template, never somebody else's data
        String nobody = "scope-n-" + UUID.randomUUID();
        for (String code : List.of(
                ReportTemplateRegistry.REPO_SUMMARY, ReportTemplateRegistry.DIGITIZATION_PROGRESS,
                ReportTemplateRegistry.DOC_BY_CLASSIFICATION)) {
            assertThat(queryService.run(nobody, nobody, code, march()).empty()).as(code).isTrue();
        }
    }

    @Test
    void groupFilterNarrowsToDocumentsSharedToThatGroupWithinTheCallersScope() {
        String a = "scope-a-" + UUID.randomUUID();
        UUID g = insertGroup("G", a);
        OffsetDateTime t = utc("2026-03-10T00:00:00Z");
        UUID shared = insertDoc(a, "application/pdf", 1, t);
        insertDoc(a, "application/pdf", 1, t);
        shareDoc(shared, g, a);
        ReportParams p = new ReportParams(
                        LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31), List.of(g), List.of(), List.of(), null,
                        "month")
                .normalized(Granularity.MONTH);
        ReportResult r = queryService.run(a, a, ReportTemplateRegistry.REPO_SUMMARY, p);
        assertThat(cell(r, "b1", "metric", "Tồn kho cuối kỳ", "value")).isEqualTo(1);
    }

    @Test
    void missingUserContextFailsClosedOnEveryTemplate() {
        for (String code : List.of(
                ReportTemplateRegistry.REPO_SUMMARY, ReportTemplateRegistry.DIGITIZATION_PROGRESS,
                ReportTemplateRegistry.DOC_BY_CLASSIFICATION)) {
            assertThatThrownBy(() -> queryService.run(null, "x", code, march()))
                    .as(code)
                    .isInstanceOf(ScopeResolutionException.class);
            assertThatThrownBy(() -> queryService.run("  ", "x", code, march()))
                    .as(code)
                    .isInstanceOf(ScopeResolutionException.class);
        }
    }

    @Test
    void reportResultNeverContainsTheOtherUsersDocumentNamesOrIds() {
        String a = "scope-a-" + UUID.randomUUID();
        String x = "scope-x-" + UUID.randomUUID();
        OffsetDateTime t = utc("2026-03-10T00:00:00Z");
        UUID mine = insertDoc(a, "application/pdf", 1, t);
        UUID theirs = insertDoc(x, "application/pdf", 1, t);
        String json = json(queryService.run(a, a, ReportTemplateRegistry.REPO_SUMMARY, march()));
        assertThat(json).doesNotContain(theirs.toString()).doesNotContain("f-" + theirs).doesNotContain(x);
        assertThat(mine).isNotNull();
        assertThat(Map.of()).isEmpty();
    }
}
