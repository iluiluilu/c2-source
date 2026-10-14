package com.aismartstore.backend.reporting;

import static org.assertj.core.api.Assertions.assertThat;

import com.aismartstore.backend.reporting.dto.ReportResult;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * SPEC §5 ReportRenderIT (AC-24, AC-25, AC-26): PDF/XLSX/DOCX re-open, Vietnamese text with diacritics survives,
 * PDF fonts are embedded, header/footer facts and the scope notice are present. No Spring/DB needed - the renderer only
 * consumes the DTO. Needs the build-time precompiled {@code reports/*.jasper} (phase process-classes).
 */
class ReportRenderIT {

    private static final String VN_SAMPLE = "Tổng hợp tình hình kho tài liệu — Ảnh, Âm thanh, Văn bản/PDF";

    private final ReportRenderService renderer = new ReportRenderService(new ReportTemplateRegistry());

    @TempDir
    Path tmp;

    private static Map<String, Object> row(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    static ReportResult sample(String code, String title) {
        List<ReportResult.Column> wideCols = new ArrayList<>();
        wideCols.add(new ReportResult.Column("topic", "Chủ đề \\ Loại tài liệu", "string"));
        for (int i = 0; i < 14; i++) {
            wideCols.add(new ReportResult.Column("dt" + i, "Loại " + i, "number"));
        }
        List<Map<String, Object>> wideRows = new ArrayList<>();
        for (int r = 0; r < 3; r++) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("topic", r == 2 ? "=HYPERLINK(\"http://evil\")" : "Chủ đề Ảnh " + r);
            for (int i = 0; i < 14; i++) {
                m.put("dt" + i, (long) (r * 100 + i));
            }
            wideRows.add(m);
        }
        List<ReportResult.Table> tables = List.of(
                new ReportResult.Table(
                        "b2", "Bảng 2. " + VN_SAMPLE,
                        List.of(
                                new ReportResult.Column("group", "Nhóm loại tệp", "string"),
                                new ReportResult.Column("count", "Số lượng", "number"),
                                new ReportResult.Column("size", "Dung lượng", "bytes"),
                                new ReportResult.Column("duration", "Tổng thời lượng", "duration")),
                        List.of(
                                row("group", "Văn bản/PDF", "count", 1234567L, "size", 5_368_709_120L, "duration", null),
                                row("group", "Âm thanh", "count", 12L, "size", 1024L, "duration", 3_725_000L)),
                        2, false),
                new ReportResult.Table("m1", "Ma trận", wideCols, wideRows, 3, true));
        List<ReportResult.Chart> charts = List.of(
                new ReportResult.Chart(
                        "c1", "Biểu đồ tài liệu theo tháng", "STACKED_BAR", List.of("2026-01", "2026-02"),
                        List.of(
                                new ReportResult.Series("Văn bản/PDF", List.of(3L, 5L)),
                                new ReportResult.Series("Ảnh", List.of(1L, 2L)))),
                new ReportResult.Chart(
                        "c2", "Theo chủ đề", "BAR", List.of("Chủ đề A", "Chưa phân loại"),
                        List.of(new ReportResult.Series("Số tài liệu", List.of(7L, 2L)))));
        return new ReportResult(
                code, title, new ReportResult.PeriodView(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 3, 31)),
                Map.of("Chủ đề", "Pháp chế, Tài chính"),
                "Số liệu trong phạm vi truy cập của nguyễn.văn.a tại 2026-04-01 09:00:00",
                OffsetDateTime.of(2026, 4, 1, 2, 0, 0, 0, ZoneOffset.UTC), "nguyễn.văn.a",
                List.of("Ghi chú: " + VN_SAMPLE), List.of(), false, tables, charts);
    }

    private File render(String format, String code) {
        File out = tmp.resolve(code + "." + format.toLowerCase()).toFile();
        renderer.render(sample(code, "Tổng hợp tình hình kho tài liệu theo kỳ"), format, out.toPath());
        assertThat(out).exists();
        assertThat(out.length()).isGreaterThan(1000);
        return out;
    }

    @Test
    void pdfIsReadableKeepsVietnameseDiacriticsEmbedsFontsAndCarriesHeaderFooter() throws IOException {
        for (String code : List.of(
                ReportTemplateRegistry.REPO_SUMMARY, ReportTemplateRegistry.DIGITIZATION_PROGRESS,
                ReportTemplateRegistry.DOC_BY_CLASSIFICATION)) {
            File pdf = render("PDF", code);
            try (PDDocument doc = Loader.loadPDF(pdf)) {
                assertThat(doc.getNumberOfPages()).isGreaterThan(0);
                String text = new PDFTextStripper().getText(doc);
                assertThat(text).contains(code);
                assertThat(text).contains("Tổng hợp tình hình kho tài liệu theo kỳ");
                assertThat(text).contains("Ảnh").contains("Âm thanh").contains("Văn bản/PDF");
                assertThat(text).contains("Số liệu trong phạm vi truy cập của");
                assertThat(text).contains("Người lập: nguyễn.văn.a");
                assertThat(text).contains("Kỳ báo cáo: 01/01/2026 - 31/03/2026");
                assertThat(text).contains("Chủ đề: Pháp chế, Tài chính");
                assertThat(text).contains("1.234.567").contains("5 GB").contains("1:02:05");
                assertThat(text).contains("Trang 1");
                // prepared-by/scope live in the page footer only, not repeated in the body (one per page)
                assertThat(countOf(text, "Người lập:")).isEqualTo(doc.getNumberOfPages());
                assertThat(countOf(text, "Kỳ báo cáo:")).isEqualTo(doc.getNumberOfPages());
                for (PDPage page : doc.getPages()) {
                    if (page.getResources() == null) {
                        continue;
                    }
                    for (org.apache.pdfbox.cos.COSName name : page.getResources().getFontNames()) {
                        PDFont font = page.getResources().getFont(name);
                        assertThat(font.isEmbedded()).as("font %s embedded", font.getName()).isTrue();
                    }
                }
            }
        }
    }

    @Test
    void xlsxOpensWithPoiHasTextAndEscapesFormulaLookingCells() throws Exception {
        File xlsx = render("XLSX", ReportTemplateRegistry.DOC_BY_CLASSIFICATION);
        StringBuilder all = new StringBuilder();
        try (XSSFWorkbook wb = new XSSFWorkbook(xlsx)) {
            assertThat(wb.getNumberOfSheets()).isGreaterThan(0);
            for (Sheet sheet : wb) {
                for (Row row : sheet) {
                    for (Cell cell : row) {
                        all.append(cell.toString()).append('\n');
                        // never a formula cell, whatever the user-entered label looked like
                        assertThat(cell.getCellType().name()).isNotEqualTo("FORMULA");
                    }
                }
            }
        }
        assertThat(all.toString()).contains("DOC_BY_CLASSIFICATION").contains("Chủ đề Ảnh 0").contains("Âm thanh");
        assertThat(all.toString()).contains("'=HYPERLINK");
    }

    @Test
    void xlsxKeepsCountsAsNumericCellsAndHasNoRepeatedPageBands() throws Exception {
        File xlsx = render("XLSX", ReportTemplateRegistry.REPO_SUMMARY);
        List<Double> numbers = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        try (XSSFWorkbook wb = new XSSFWorkbook(xlsx)) {
            for (Sheet sheet : wb) {
                for (Row row : sheet) {
                    for (Cell cell : row) {
                        if (cell.getCellType() == org.apache.poi.ss.usermodel.CellType.NUMERIC) {
                            numbers.add(cell.getNumericCellValue());
                        } else {
                            text.append(cell.toString()).append('\n');
                        }
                    }
                }
            }
        }
        // "number" columns are real numbers (SUM/sort work in Excel); bytes/duration keep their unit text
        assertThat(numbers).contains(1234567.0, 12.0, 113.0);
        assertThat(text.toString()).contains("5 GB").contains("1:02:05").doesNotContain("1.234.567");
        // one header (first page only), no page footers between rows; footer facts are printed once in the body
        assertThat(text.toString()).doesNotContain("Trang ");
        assertThat(countOf(text.toString(), "REPO_SUMMARY - ")).isEqualTo(1);
        assertThat(countOf(text.toString(), "Người lập: nguyễn.văn.a")).isEqualTo(1);
        assertThat(countOf(text.toString(), "Số liệu trong phạm vi truy cập của")).isEqualTo(1);
    }

    @Test
    void bodyInfoOnlyRepeatsFooterFactsForXlsx() {
        ReportResult r = sample(ReportTemplateRegistry.REPO_SUMMARY, "T");
        String pdfInfo = renderer.sections(r, false).get(0).getText();
        String xlsxInfo = renderer.sections(r, true).get(0).getText();
        assertThat(pdfInfo).doesNotContain("Người lập").doesNotContain("Kỳ báo cáo").startsWith("Ghi chú:");
        assertThat(xlsxInfo).contains("Người lập: nguyễn.văn.a").contains("Số liệu trong phạm vi truy cập của");
    }

    private static int countOf(String haystack, String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) {
            n++;
        }
        return n;
    }

    @Test
    void docxOpensWithPoiXwpf() throws IOException {
        File docx = render("DOCX", ReportTemplateRegistry.REPO_SUMMARY);
        try (XWPFDocument doc = new XWPFDocument(Files.newInputStream(docx.toPath()))) {
            StringBuilder text = new StringBuilder();
            doc.getParagraphs().forEach(p -> text.append(p.getText()).append('\n'));
            doc.getTables().forEach(t -> t.getRows().forEach(r -> r.getTableCells().forEach(c -> text.append(c.getText()).append('\n'))));
            doc.getHeaderList().forEach(h -> text.append(h.getText()).append('\n'));
            doc.getFooterList().forEach(h -> text.append(h.getText()).append('\n'));
            assertThat(text.toString()).contains("REPO_SUMMARY").contains("Âm thanh");
        }
    }

    @Test
    void wideTablesAreSplitIntoChunksOfAtMostTenColumns() {
        List<ReportResult.Column> cols = sample("X", "Y").tables().get(1).columns();
        List<List<ReportResult.Column>> chunks = ReportRenderService.chunkColumns(cols);
        assertThat(chunks).hasSize(2);
        assertThat(chunks).allSatisfy(c -> assertThat(c.size()).isLessThanOrEqualTo(ReportRenderService.MAX_TABLE_COLUMNS));
        assertThat(chunks).allSatisfy(c -> assertThat(c.get(0).key()).isEqualTo("topic"));
    }
}
