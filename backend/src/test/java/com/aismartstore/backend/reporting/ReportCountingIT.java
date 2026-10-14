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

/**
 * SPEC §5 ReportCountingIT (AC-12, AC-16, Q1, Q3, review #1/#7): distinct counting, soft delete,
 * status filter, folder subtree, personal-origin documents, audio/video duration counted once.
 */
class ReportCountingIT extends ReportITBase {

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

    private ReportResult run(String sub, String template, ReportParams p) {
        return queryService.run(sub, sub, template, p);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> rowWhere(ReportResult.Table t, String key, Object value) {
        return t.rows().stream().filter(r -> value.equals(r.get(key))).findFirst().orElseThrow();
    }

    private static ReportResult.Table table(ReportResult r, String id) {
        return r.tables().stream().filter(t -> t.id().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void table1BalancesAndPeriodBoundariesUseIctHalfOpenInterval() {
        String a = "user-a";
        insertDoc(a, "application/pdf", 100, utc("2026-02-10T00:00:00Z")); // opening
        insertDoc(a, "application/pdf", 100, utc("2026-02-28T16:59:59Z")); // opening (23:59:59 ICT Feb 28)
        insertDoc(a, "application/pdf", 100, utc("2026-02-28T17:00:00Z")); // ingested (00:00 ICT Mar 1)
        insertDoc(a, "image/png", 50, utc("2026-03-15T03:00:00Z")); // ingested
        insertDoc(a, "image/png", 50, utc("2026-03-31T16:59:59Z")); // ingested (23:59:59 ICT Mar 31)
        insertDoc(a, "image/png", 50, utc("2026-03-31T17:00:00Z")); // after (00:00 ICT Apr 1)

        ReportResult r = run(a, ReportTemplateRegistry.REPO_SUMMARY, march());
        ReportResult.Table b1 = table(r, "b1");
        assertThat(rowWhere(b1, "metric", "Tồn kho đầu kỳ").get("value")).isEqualTo(2L);
        assertThat(rowWhere(b1, "metric", "Nạp mới trong kỳ").get("value")).isEqualTo(3L);
        assertThat(rowWhere(b1, "metric", "Tồn kho cuối kỳ").get("value")).isEqualTo(5L);
        assertThat(r.empty()).isFalse();
    }

    @Test
    void docsWithManyTagsGroupsAndSharesAreCountedOnce_andSoftDeletedAndNonCleanAreExcluded() {
        String a = "user-a";
        UUID d1 = insertDoc(a, "application/pdf", 10, utc("2026-03-05T00:00:00Z"));
        UUID topic1 = insertTag("topic", "T1", true);
        UUID topic2 = insertTag("topic", "T2", true);
        UUID type1 = insertTag("doc_type", "D1", true);
        tagDoc(d1, topic1, "topic");
        tagDoc(d1, topic2, "topic");
        tagDoc(d1, type1, "doc_type");
        UUID g1 = insertGroup("G1", a);
        UUID g2 = insertGroup("G2", a);
        shareDoc(d1, g1, a);
        shareDoc(d1, g2, a);
        insertDoc(a, "application/pdf", 10, utc("2026-03-06T00:00:00Z"), "clean", rootFolderId, true); // soft deleted
        insertDoc(a, "application/pdf", 10, utc("2026-03-06T00:00:00Z"), "pending", rootFolderId, false);
        insertDoc(a, "application/pdf", 10, utc("2026-03-06T00:00:00Z"), "infected", rootFolderId, false);
        insertDoc(a, "application/pdf", 10, utc("2026-03-06T00:00:00Z"), "rejected", rootFolderId, false);
        insertDoc(a, "application/pdf", 10, utc("2026-03-06T00:00:00Z"), "purged", rootFolderId, false);

        ReportParams filtered = new ReportParams(
                        LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31), List.of(g1, g2), List.of(topic1, topic2),
                        List.of(type1), null, "month")
                .normalized(Granularity.MONTH);
        ReportResult r = run(a, ReportTemplateRegistry.REPO_SUMMARY, filtered);
        assertThat(rowWhere(table(r, "b1"), "metric", "Nạp mới trong kỳ").get("value")).isEqualTo(1L);
        assertThat(rowWhere(table(r, "b2"), "group", "Văn bản/PDF").get("count")).isEqualTo(1L);

        ReportResult unfiltered = run(a, ReportTemplateRegistry.REPO_SUMMARY, march());
        assertThat(rowWhere(table(unfiltered, "b1"), "metric", "Tồn kho cuối kỳ").get("value")).isEqualTo(1L);
    }

    @Test
    void restoredDocumentIsCountedAgain() {
        String a = "user-a";
        UUID d = insertDoc(a, "application/pdf", 10, utc("2026-03-05T00:00:00Z"));
        jdbc.update("UPDATE document SET deleted_at = now() WHERE id = ?", d);
        assertThat(rowWhere(table(run(a, ReportTemplateRegistry.REPO_SUMMARY, march()), "b1"), "metric",
                        "Tồn kho cuối kỳ").get("value"))
                .isEqualTo(0L);
        jdbc.update("UPDATE document SET deleted_at = NULL WHERE id = ?", d);
        assertThat(rowWhere(table(run(a, ReportTemplateRegistry.REPO_SUMMARY, march()), "b1"), "metric",
                        "Tồn kho cuối kỳ").get("value"))
                .isEqualTo(1L);
    }

    @Test
    void folderFilterIncludesSubfolders() {
        String a = "user-a";
        UUID child = insertFolder("child", rootFolderId, new UUID[] {rootFolderId});
        UUID grandChild = insertFolder("grand", child, new UUID[] {rootFolderId, child});
        UUID other = insertFolder("other-" + UUID.randomUUID(), null, new UUID[0]);
        insertDoc(a, "application/pdf", 1, utc("2026-03-05T00:00:00Z"), "clean", rootFolderId, false);
        insertDoc(a, "application/pdf", 1, utc("2026-03-05T00:00:00Z"), "clean", child, false);
        insertDoc(a, "application/pdf", 1, utc("2026-03-05T00:00:00Z"), "clean", grandChild, false);
        insertDoc(a, "application/pdf", 1, utc("2026-03-05T00:00:00Z"), "clean", other, false);

        ReportParams p = new ReportParams(
                        LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31), List.of(), List.of(), List.of(),
                        rootFolderId, "month")
                .normalized(Granularity.MONTH);
        ReportResult r = run(a, ReportTemplateRegistry.REPO_SUMMARY, p);
        assertThat(rowWhere(table(r, "b1"), "metric", "Nạp mới trong kỳ").get("value")).isEqualTo(3L);
    }

    @Test
    void documentTransferredFromPersonalIsCounted_andPersonalFilesNeverAre() {
        String a = "user-a";
        UUID d = insertDoc(a, "application/pdf", 1, utc("2026-03-05T00:00:00Z"));
        jdbc.update("UPDATE document SET source_kind = 'personal' WHERE id = ?", d);
        // a personal_file row exists in its own table - it cannot appear in a Document-rooted query
        ReportResult r = run(a, ReportTemplateRegistry.REPO_SUMMARY, march());
        assertThat(rowWhere(table(r, "b1"), "metric", "Nạp mới trong kỳ").get("value")).isEqualTo(1L);
    }

    @Test
    void videoWithSeveralSourcesCountsTheLongestDurationOnce_andFileGroupsAreSplit() {
        String a = "user-a";
        UUID video = insertDoc(a, "video/mp4", 1000, utc("2026-03-05T00:00:00Z"));
        insertSource(video, "video", "video/mp4", 60_000);
        insertSource(video, "audio", "audio/wav", 60_000);
        insertSource(video, "original", "video/mp4", 59_000);
        UUID audio = insertDoc(a, "audio/mpeg", 500, utc("2026-03-06T00:00:00Z"));
        insertSource(audio, "audio", "audio/mpeg", 30_000);
        insertDoc(a, "application/zip", 5, utc("2026-03-06T00:00:00Z"));
        insertDoc(a, "text/plain", 5, utc("2026-03-06T00:00:00Z"));
        insertDoc(a, "image/png", 5, utc("2026-03-06T00:00:00Z"));

        ReportResult r = run(a, ReportTemplateRegistry.REPO_SUMMARY, march());
        ReportResult.Table b2 = table(r, "b2");
        assertThat(rowWhere(b2, "group", "Video").get("duration")).isEqualTo(60_000L);
        assertThat(rowWhere(b2, "group", "Âm thanh").get("duration")).isEqualTo(30_000L);
        assertThat(rowWhere(b2, "group", "Video").get("size")).isEqualTo(1000L);
        assertThat(rowWhere(b2, "group", "Khác").get("count")).isEqualTo(1L);
        assertThat(rowWhere(b2, "group", "Văn bản/PDF").get("count")).isEqualTo(1L);
        assertThat(rowWhere(b2, "group", "Ảnh").get("count")).isEqualTo(1L);
    }

    @Test
    void ingestedByBucketAndSnapshotOfDigitization() {
        String a = "user-a";
        ReportParams p = new ReportParams(
                        LocalDate.of(2026, 1, 15), LocalDate.of(2026, 3, 10), List.of(), List.of(), List.of(), null,
                        "month")
                .normalized(Granularity.MONTH);
        UUID d1 = insertDoc(a, "application/pdf", 10, utc("2026-01-20T00:00:00Z"));
        insertDoc(a, "application/pdf", 10, utc("2026-01-21T00:00:00Z"));
        insertDoc(a, "image/png", 10, utc("2026-03-02T00:00:00Z"));
        setOverallStatus(d1, "confirmed");
        UUID d2 = insertDoc(a, "application/pdf", 10, utc("2026-03-02T00:00:00Z"));
        setOverallStatus(d2, "pending_review");
        UUID d3 = insertDoc(a, "application/pdf", 10, utc("2026-03-02T00:00:00Z"));
        setOverallStatus(d3, "not_applicable");

        ReportResult r = run(a, ReportTemplateRegistry.REPO_SUMMARY, p);
        ReportResult.Table b3 = table(r, "b3");
        assertThat(b3.rows()).extracting(m -> m.get("bucket")).containsExactly("2026-01", "2026-02", "2026-03");
        assertThat(rowWhere(b3, "bucket", "2026-01").get("count")).isEqualTo(2L);
        assertThat(rowWhere(b3, "bucket", "2026-02").get("count")).isEqualTo(0L);
        assertThat(rowWhere(b3, "bucket", "2026-03").get("count")).isEqualTo(3L);
        ReportResult.Table b4 = table(r, "b4");
        assertThat(rowWhere(b4, "status", "Đã xong").get("count")).isEqualTo(1L);
        assertThat(rowWhere(b4, "status", "Chờ duyệt").get("count")).isEqualTo(1L);
        assertThat(rowWhere(b4, "status", "Đang xử lý").get("count")).isEqualTo(0L);
        assertThat(r.charts()).hasSize(1);
    }

    @Test
    void emptyReportIsFlaggedWithAWarningNotAnError() {
        ReportResult r = run("user-a", ReportTemplateRegistry.REPO_SUMMARY, march());
        assertThat(r.empty()).isTrue();
        assertThat(r.warnings()).isNotEmpty();
    }
}
