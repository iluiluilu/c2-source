package com.aismartstore.backend.reporting;

import static org.assertj.core.api.Assertions.assertThat;

import com.aismartstore.backend.reporting.dto.ReportResult;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** SPEC §5 ReportTemplatesIT: content of DIGITIZATION_PROGRESS (AC-14) and DOC_BY_CLASSIFICATION (AC-15), empty report (AC-10). */
class ReportTemplatesIT extends ReportITBase {

    @Autowired
    private ReportQueryService queryService;

    private static OffsetDateTime utc(String iso) {
        return OffsetDateTime.parse(iso).withOffsetSameInstant(ZoneOffset.UTC);
    }

    private static ReportParams march(List<UUID> topics, List<UUID> types) {
        return new ReportParams(
                        LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31), List.of(), topics, types, null, "month")
                .normalized(Granularity.MONTH);
    }

    private static Map<String, Object> rowWhere(ReportResult.Table t, String key, Object value) {
        return t.rows().stream().filter(r -> value.equals(r.get(key))).findFirst().orElseThrow();
    }

    private static ReportResult.Table table(ReportResult r, String id) {
        return r.tables().stream().filter(t -> t.id().equals(id)).findFirst().orElseThrow();
    }

    // ---- DIGITIZATION_PROGRESS --------------------------------------------------------------

    @Test
    void digitizationProgressCountsRunsByTaskAndExcludesSupersededVoidSedAndOutOfPeriod() {
        String a = "user-a";
        UUID doc = insertDoc(a, "application/pdf", 1, utc("2026-03-01T00:00:00Z"));
        UUID src = insertSource(doc, "original", "application/pdf", null);
        OffsetDateTime inPeriod = utc("2026-03-10T00:00:00Z");
        OffsetDateTime past = OffsetDateTime.now().minusDays(2);
        OffsetDateTime future = OffsetDateTime.now().plusDays(2);

        // ocr: 1 queued, 1 failed_permanent (error), 1 pending review overdue, 1 confirmed edited
        UUID d1 = insertDoc(a, "application/pdf", 1, utc("2026-03-01T00:00:00Z"));
        UUID s1 = insertSource(d1, "original", "application/pdf", null);
        insertRun(d1, s1, "ocr", "queued", "not_applicable", null, null, inPeriod);
        UUID d2 = insertDoc(a, "application/pdf", 1, utc("2026-03-01T00:00:00Z"));
        UUID s2 = insertSource(d2, "original", "application/pdf", null);
        insertRun(d2, s2, "ocr", "failed_permanent", "not_applicable", null, null, inPeriod);
        UUID d3 = insertDoc(a, "application/pdf", 1, utc("2026-03-01T00:00:00Z"));
        UUID s3 = insertSource(d3, "original", "application/pdf", null);
        insertRun(d3, s3, "ocr", "succeeded", "pending", null, past, inPeriod);
        UUID d4 = insertDoc(a, "application/pdf", 1, utc("2026-03-01T00:00:00Z"));
        UUID s4 = insertSource(d4, "original", "application/pdf", null);
        insertRun(d4, s4, "ocr", "succeeded", "confirmed", "manual_edited", null, inPeriod);
        // stt: pending review not overdue + auto confirmed
        UUID d5 = insertDoc(a, "audio/mpeg", 1, utc("2026-03-01T00:00:00Z"));
        UUID s5 = insertSource(d5, "audio", "audio/mpeg", 1000);
        insertRun(d5, s5, "stt", "succeeded", "in_review", null, future, inPeriod);
        UUID d6 = insertDoc(a, "audio/mpeg", 1, utc("2026-03-01T00:00:00Z"));
        UUID s6 = insertSource(d6, "audio", "audio/mpeg", 1000);
        insertRun(d6, s6, "stt", "succeeded", "confirmed", "auto", null, inPeriod);
        // excluded: superseded, void, sed, out of period, other user's document
        UUID d7 = insertDoc(a, "audio/mpeg", 1, utc("2026-03-01T00:00:00Z"));
        UUID s7 = insertSource(d7, "audio", "audio/mpeg", 1000);
        insertRun(d7, s7, "stt", "succeeded", "superseded", null, null, inPeriod);
        UUID d8 = insertDoc(a, "audio/mpeg", 1, utc("2026-03-01T00:00:00Z"));
        UUID s8 = insertSource(d8, "audio", "audio/mpeg", 1000);
        insertRun(d8, s8, "sed", "succeeded", "confirmed", "auto", null, inPeriod);
        UUID d9 = insertDoc(a, "application/pdf", 1, utc("2026-03-01T00:00:00Z"));
        UUID s9 = insertSource(d9, "original", "application/pdf", null);
        insertRun(d9, s9, "ocr", "queued", "not_applicable", null, null, utc("2026-04-05T00:00:00Z"));
        UUID dOther = insertDoc("someone-else", "application/pdf", 1, utc("2026-03-01T00:00:00Z"));
        UUID sOther = insertSource(dOther, "original", "application/pdf", null);
        insertRun(dOther, sOther, "ocr", "queued", "not_applicable", null, null, inPeriod);
        // a video_summary run
        UUID d10 = insertDoc(a, "video/mp4", 1, utc("2026-03-01T00:00:00Z"));
        UUID s10 = insertSource(d10, "video", "video/mp4", 1000);
        insertRun(d10, s10, "video_summary", "running", "not_applicable", null, null, inPeriod);

        ReportResult r = queryService.run(a, a, ReportTemplateRegistry.DIGITIZATION_PROGRESS, march(List.of(), List.of()));
        ReportResult.Table t = table(r, "d1");
        assertThat(t.rows()).hasSize(3);
        Map<String, Object> ocr = rowWhere(t, "task", "OCR (nhận dạng chữ)");
        assertThat(ocr.get("total")).isEqualTo(4L);
        assertThat(ocr.get("processing")).isEqualTo(1L);
        assertThat(ocr.get("error")).isEqualTo(1L);
        assertThat(ocr.get("pending")).isEqualTo(1L);
        assertThat(ocr.get("overdue")).isEqualTo(1L);
        assertThat(ocr.get("c_edited")).isEqualTo(1L);
        Map<String, Object> stt = rowWhere(t, "task", "STT (nhận dạng giọng nói)");
        assertThat(stt.get("total")).isEqualTo(2L);
        assertThat(stt.get("pending")).isEqualTo(1L);
        assertThat(stt.get("overdue")).isEqualTo(0L);
        assertThat(stt.get("c_auto")).isEqualTo(1L);
        assertThat(rowWhere(t, "task", "Tóm tắt video").get("processing")).isEqualTo(1L);
        assertThat(r.charts()).hasSize(1);
        assertThat(r.charts().get(0).type()).isEqualTo("STACKED_BAR");
        assertThat(r.notes()).anyMatch(n -> n.contains("lần chạy"));
        assertThat(src).isNotNull();
    }

    // ---- DOC_BY_CLASSIFICATION --------------------------------------------------------------

    @Test
    void classificationMatrixHasUnclassifiedRowAndColumnAndInactiveTags() {
        String a = "user-a";
        UUID tpA = insertTag("topic", "Chủ đề A", true);
        UUID tpOld = insertTag("topic", "Chủ đề cũ", false);
        UUID dtX = insertTag("doc_type", "Công văn", true);
        UUID dtY = insertTag("doc_type", "Báo cáo", true);

        UUID both = insertDoc(a, "application/pdf", 1, utc("2026-03-01T00:00:00Z"));
        tagDoc(both, tpA, "topic");
        tagDoc(both, dtX, "doc_type");
        UUID multi = insertDoc(a, "application/pdf", 1, utc("2026-03-01T00:00:00Z"));
        tagDoc(multi, tpA, "topic");
        tagDoc(multi, tpOld, "topic");
        tagDoc(multi, dtY, "doc_type");
        UUID topicOnly = insertDoc(a, "application/pdf", 1, utc("2026-03-02T00:00:00Z"));
        tagDoc(topicOnly, tpA, "topic");
        UUID typeOnly = insertDoc(a, "application/pdf", 1, utc("2026-03-02T00:00:00Z"));
        tagDoc(typeOnly, dtX, "doc_type");
        insertDoc(a, "application/pdf", 1, utc("2026-03-02T00:00:00Z")); // neither
        insertDoc(a, "application/pdf", 1, utc("2026-04-15T00:00:00Z")); // after the period: not in inventory
        insertDoc("someone-else", "application/pdf", 1, utc("2026-03-02T00:00:00Z")); // out of scope

        ReportResult r = queryService.run(
                a, a, ReportTemplateRegistry.DOC_BY_CLASSIFICATION, march(List.of(), List.of()));
        ReportResult.Table m = table(r, "m1");
        assertThat(m.paged()).isTrue();
        assertThat(m.columns()).extracting(ReportResult.Column::label)
                .containsExactly("Chủ đề \\ Loại tài liệu", "Báo cáo", "Công văn", ReportQueryService.UNCLASSIFIED);
        Map<String, Object> rowA = rowWhere(m, "topic", "Chủ đề A");
        assertThat(rowA.get("dt0")).isEqualTo(1L); // Báo cáo (multi)
        assertThat(rowA.get("dt1")).isEqualTo(1L); // Công văn (both)
        assertThat(rowA.get("dt_none")).isEqualTo(1L); // topicOnly
        Map<String, Object> rowOld = rowWhere(m, "topic", "Chủ đề cũ"); // active=false still shown
        assertThat(rowOld.get("dt0")).isEqualTo(1L);
        Map<String, Object> rowNone = rowWhere(m, "topic", ReportQueryService.UNCLASSIFIED);
        assertThat(rowNone.get("dt1")).isEqualTo(1L); // typeOnly
        assertThat(rowNone.get("dt_none")).isEqualTo(1L); // neither

        // by-topic marginals are distinct documents, not sums of cells
        ReportResult.Table byTopic = table(r, "t1");
        assertThat(rowWhere(byTopic, "label", "Chủ đề A").get("count")).isEqualTo(3L);
        assertThat(rowWhere(byTopic, "label", ReportQueryService.UNCLASSIFIED).get("count")).isEqualTo(2L);
        ReportResult.Table byType = table(r, "t2");
        assertThat(rowWhere(byType, "label", "Công văn").get("count")).isEqualTo(2L);
        assertThat(rowWhere(byType, "label", ReportQueryService.UNCLASSIFIED).get("count")).isEqualTo(2L);
        assertThat(r.charts()).hasSize(2);
        assertThat(r.notes()).anyMatch(n -> n.contains("nhiều nhãn"));
    }

    @Test
    void classificationFilteredByTopicShowsOnlySelectedTopicsAndNoUnclassifiedRow() {
        String a = "user-a";
        UUID tpA = insertTag("topic", "A", true);
        UUID tpB = insertTag("topic", "B", true);
        UUID dtX = insertTag("doc_type", "X", true);
        UUID d1 = insertDoc(a, "application/pdf", 1, utc("2026-03-01T00:00:00Z"));
        tagDoc(d1, tpA, "topic");
        tagDoc(d1, dtX, "doc_type");
        UUID d2 = insertDoc(a, "application/pdf", 1, utc("2026-03-01T00:00:00Z"));
        tagDoc(d2, tpB, "topic");

        ReportResult r = queryService.run(
                a, a, ReportTemplateRegistry.DOC_BY_CLASSIFICATION, march(List.of(tpA), List.of()));
        ReportResult.Table m = table(r, "m1");
        assertThat(m.rows()).extracting(row -> row.get("topic")).containsExactly("A");
        assertThat(rowWhere(m, "topic", "A").get("dt0")).isEqualTo(1L);
    }

    @Test
    void emptyDataGivesAnEmptyReportWithWarning() {
        for (String code : List.of(
                ReportTemplateRegistry.DIGITIZATION_PROGRESS, ReportTemplateRegistry.DOC_BY_CLASSIFICATION)) {
            ReportResult r = queryService.run("nobody", "nobody", code, march(List.of(), List.of()));
            assertThat(r.empty()).as(code).isTrue();
            assertThat(r.warnings()).as(code).isNotEmpty();
        }
    }
}
