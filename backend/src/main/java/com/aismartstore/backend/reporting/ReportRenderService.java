package com.aismartstore.backend.reporting;

import com.aismartstore.backend.reporting.dto.ReportResult;
import java.io.File;
import java.nio.file.Path;
import java.text.NumberFormat;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.sf.jasperreports.engine.JRDataSource;
import net.sf.jasperreports.engine.JRException;
import net.sf.jasperreports.engine.JasperFillManager;
import net.sf.jasperreports.engine.JasperPrint;
import net.sf.jasperreports.engine.JasperReport;
import net.sf.jasperreports.engine.data.JRBeanCollectionDataSource;
import net.sf.jasperreports.engine.export.ooxml.JRDocxExporter;
import net.sf.jasperreports.engine.util.JRLoader;
import net.sf.jasperreports.export.SimpleExporterInput;
import net.sf.jasperreports.export.SimpleOutputStreamExporterOutput;
import net.sf.jasperreports.export.SimpleXlsxReportConfiguration;
import net.sf.jasperreports.pdf.JRPdfExporter;
import net.sf.jasperreports.engine.export.ooxml.JRXlsxExporter;
import org.springframework.stereotype.Service;

/**
 * SPEC T11 / Part T §4 (AC-24..26, AC-36): renders a {@link ReportResult} to PDF / XLSX / DOCX with
 * JasperReports. Data reaches Jasper ONLY as {@link JRBeanCollectionDataSource}s built from the DTO
 * (the templates declare no query and take no user text into any expression); the compiled
 * {@code reports/*.jasper} are produced at build time. Fonts are the committed TTFs registered in
 * {@code fonts/fonts.xml} (embedded in the PDF), so output does not depend on the host.
 */
@Service
public class ReportRenderService {

    static {
        System.setProperty("java.awt.headless", "true");
    }

    public static final int MAX_TABLE_COLUMNS = 10;
    private static final String XLSX_EXCLUDE_ORIGIN = "net.sf.jasperreports.export.xlsx.exclude.origin.band.";
    private static final String XLSX_EXCLUDE_ORIGIN_KEEP_FIRST = "net.sf.jasperreports.export.xlsx.exclude.origin.keep.first.band.";
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");

    private final ReportTemplateRegistry registry;
    private final Map<String, JasperReport> compiled = new ConcurrentHashMap<>();

    public ReportRenderService(ReportTemplateRegistry registry) {
        this.registry = registry;
    }

    /** Bean for one body section of a master report (TEXT | TABLE | CHART_BAR | CHART_STACKED). */
    public static final class Section {
        private final String kind;
        private final String title;
        private final String text;
        private final JRDataSource ds;

        Section(String kind, String title, String text, JRDataSource ds) {
            this.kind = kind;
            this.title = title;
            this.text = text;
            this.ds = ds;
        }

        public String getKind() {
            return kind;
        }

        public String getTitle() {
            return title;
        }

        public String getText() {
            return text;
        }

        public JRDataSource getDs() {
            return ds;
        }
    }

    /**
     * One printed table row: up to {@link #MAX_TABLE_COLUMNS} cells; unused cells stay {@code null}. Each cell is EITHER a
     * pre-formatted string ({@code cN}) OR a raw number ({@code nN}, XLSX only) - table.jrxml prints whichever is set, so
     * spreadsheet cells keep a numeric type.
     */
    public static final class RenderRow {
        private final boolean header;
        private final String[] cells = new String[MAX_TABLE_COLUMNS];
        private final Number[] numbers = new Number[MAX_TABLE_COLUMNS];

        RenderRow(boolean header, List<String> values) {
            this.header = header;
            for (int i = 0; i < values.size() && i < MAX_TABLE_COLUMNS; i++) {
                cells[i] = values.get(i);
            }
        }

        RenderRow(List<Object> values) {
            this.header = false;
            for (int i = 0; i < values.size() && i < MAX_TABLE_COLUMNS; i++) {
                if (values.get(i) instanceof Number n) {
                    numbers[i] = n;
                } else {
                    cells[i] = (String) values.get(i);
                }
            }
        }

        public Boolean getHeader() {
            return header;
        }

        public String getC0() {
            return cells[0];
        }

        public String getC1() {
            return cells[1];
        }

        public String getC2() {
            return cells[2];
        }

        public String getC3() {
            return cells[3];
        }

        public String getC4() {
            return cells[4];
        }

        public String getC5() {
            return cells[5];
        }

        public String getC6() {
            return cells[6];
        }

        public String getC7() {
            return cells[7];
        }

        public String getC8() {
            return cells[8];
        }

        public String getC9() {
            return cells[9];
        }

        public Number getN0() {
            return numbers[0];
        }

        public Number getN1() {
            return numbers[1];
        }

        public Number getN2() {
            return numbers[2];
        }

        public Number getN3() {
            return numbers[3];
        }

        public Number getN4() {
            return numbers[4];
        }

        public Number getN5() {
            return numbers[5];
        }

        public Number getN6() {
            return numbers[6];
        }

        public Number getN7() {
            return numbers[7];
        }

        public Number getN8() {
            return numbers[8];
        }

        public Number getN9() {
            return numbers[9];
        }
    }

    /** One bar of a chart sub-report. */
    public static final class ChartPoint {
        private final String series;
        private final String category;
        private final Number value;

        ChartPoint(String series, String category, Number value) {
            this.series = series;
            this.category = category;
            this.value = value;
        }

        public String getSeries() {
            return series;
        }

        public String getCategory() {
            return category;
        }

        public Number getValue() {
            return value;
        }
    }

    /** Renders {@code result} as {@code format} (PDF|XLSX|DOCX) into {@code out}. */
    public void render(ReportResult result, String format, Path out) {
        try {
            ReportTemplateRegistry.Template template = registry.find(result.templateCode())
                    .orElseThrow(() -> new IllegalArgumentException("Unknown template " + result.templateCode()));
            boolean xlsx = "XLSX".equals(format);
            JasperPrint print = JasperFillManager.fillReport(
                    load(template.jasperResource()), parameters(result), new JRBeanCollectionDataSource(sections(result, xlsx)));
            export(print, format, out.toFile());
        } catch (JRException e) {
            throw new IllegalStateException("Report render failed for " + result.templateCode(), e);
        }
    }

    private JasperReport load(String resource) {
        return compiled.computeIfAbsent(resource, r -> {
            try (java.io.InputStream in = ReportRenderService.class.getClassLoader().getResourceAsStream(r)) {
                if (in == null) {
                    throw new IllegalStateException("Compiled report not found on classpath: " + r);
                }
                return (JasperReport) JRLoader.loadObject(in);
            } catch (JRException | java.io.IOException e) {
                throw new IllegalStateException("Cannot load compiled report " + r, e);
            }
        });
    }

    private Map<String, Object> parameters(ReportResult r) {
        Map<String, Object> p = new java.util.HashMap<>();
        p.put("REPORT_CODE", r.templateCode());
        p.put("REPORT_NAME", r.title());
        p.put("HEADER_LINE", headerLine(r));
        p.put("FOOTER_LINE", footerLine(r));
        p.put("SUBREPORT_TABLE", load("reports/table.jasper"));
        p.put("SUBREPORT_CHART_BAR", load("reports/chart_bar.jasper"));
        p.put("SUBREPORT_CHART_STACKED", load("reports/chart_stacked.jasper"));
        return p;
    }

    private static String periodText(ReportResult r) {
        return "Kỳ báo cáo: " + r.period().from().format(DATE) + " - " + r.period().to().format(DATE);
    }

    private static String filtersText(ReportResult r) {
        if (r.filters().isEmpty()) {
            return "Bộ lọc: không có";
        }
        List<String> parts = new ArrayList<>();
        r.filters().forEach((k, v) -> parts.add(k + ": " + v));
        return "Bộ lọc: " + String.join("; ", parts);
    }

    private static String generatedText(ReportResult r) {
        return "Người lập: " + r.generatedBy() + " - Thời điểm lập: "
                + r.generatedAt().atZoneSameInstant(Period.ZONE).format(DATE_TIME);
    }

    static String headerLine(ReportResult r) {
        return periodText(r) + "\n" + filtersText(r);
    }

    static String footerLine(ReportResult r) {
        return generatedText(r) + "\n" + r.scopeNotice();
    }

    /**
     * {@code xlsx} switches the spreadsheet-only behaviour: formula escaping, numeric cells, and the prepared-by/scope lines
     * in the body. Those two lines live in the page footer, which the XLSX export drops (see {@link #export}) so that
     * "Trang n" rows do not land between table rows; PDF/DOCX already print the period/filters in the page header and the
     * prepared-by/scope lines in the page footer of every page, so repeating them in the body is noise.
     */
    List<Section> sections(ReportResult r, boolean xlsx) {
        List<Section> out = new ArrayList<>();
        List<String> info = new ArrayList<>();
        if (xlsx) {
            info.add(generatedText(r));
            info.add(r.scopeNotice());
        }
        for (String w : r.warnings()) {
            info.add("Cảnh báo: " + w);
        }
        for (String n : r.notes()) {
            info.add("Ghi chú: " + n);
        }
        if (!info.isEmpty()) {
            out.add(new Section("TEXT", null, escape(String.join("\n", info), xlsx), null));
        }
        for (ReportResult.Table t : r.tables()) {
            int chunk = 0;
            for (List<ReportResult.Column> cols : chunkColumns(t.columns())) {
                String title = t.title() + (chunk++ > 0 ? " (tiếp)" : "");
                out.add(new Section("TABLE", escape(title, xlsx), null, tableData(t, cols, xlsx)));
            }
        }
        for (ReportResult.Chart c : r.charts()) {
            List<ChartPoint> points = new ArrayList<>();
            for (ReportResult.Series s : c.series()) {
                for (int i = 0; i < c.categories().size(); i++) {
                    Number v = i < s.values().size() ? s.values().get(i) : 0;
                    points.add(new ChartPoint(escape(s.name(), xlsx), escape(c.categories().get(i), xlsx), v));
                }
            }
            out.add(new Section(
                    "STACKED_BAR".equals(c.type()) ? "CHART_STACKED" : "CHART_BAR", escape(c.title(), xlsx),
                    null, new JRBeanCollectionDataSource(points)));
        }
        return out;
    }

    /** A table wider than {@link #MAX_TABLE_COLUMNS} is printed as several tables, each repeating the first (label) column. */
    static List<List<ReportResult.Column>> chunkColumns(List<ReportResult.Column> columns) {
        if (columns.size() <= MAX_TABLE_COLUMNS) {
            return List.of(columns);
        }
        List<List<ReportResult.Column>> chunks = new ArrayList<>();
        ReportResult.Column label = columns.get(0);
        int step = MAX_TABLE_COLUMNS - 1;
        for (int i = 1; i < columns.size(); i += step) {
            List<ReportResult.Column> c = new ArrayList<>();
            c.add(label);
            c.addAll(columns.subList(i, Math.min(i + step, columns.size())));
            chunks.add(c);
        }
        return chunks;
    }

    static List<RenderRow> tableRows(ReportResult.Table t, List<ReportResult.Column> cols, boolean xlsx) {
        List<RenderRow> rows = new ArrayList<>();
        rows.add(new RenderRow(true, cols.stream().map(c -> escape(c.label(), xlsx)).toList()));
        for (Map<String, Object> row : t.rows()) {
            List<Object> cells = new ArrayList<>();
            for (ReportResult.Column c : cols) {
                Object value = row.get(c.key());
                // Plain counts stay numbers in XLSX; bytes/duration/percent keep their unit text ("19,9 MB", "0:00:08").
                cells.add(xlsx && "number".equals(c.type()) && value instanceof Number n
                        ? n
                        : escape(format(value, c.type()), xlsx));
            }
            rows.add(new RenderRow(cells));
        }
        return rows;
    }

    private JRDataSource tableData(ReportResult.Table t, List<ReportResult.Column> cols, boolean xlsx) {
        return new JRBeanCollectionDataSource(tableRows(t, cols, xlsx));
    }

    /** Vietnamese number formatting (1.234.567), sizes in KB/MB/GB, durations as h:mm:ss. {@code null} -> empty string. */
    static String format(Object value, String type) {
        if (value == null) {
            return "";
        }
        if (value instanceof Number n) {
            return switch (type) {
                case "bytes" -> humanBytes(n.longValue());
                case "duration" -> duration(n.longValue());
                case "percent" -> NumberFormat.getNumberInstance(new Locale("vi", "VN")).format(n) + "%";
                default -> NumberFormat.getIntegerInstance(new Locale("vi", "VN")).format(n);
            };
        }
        return String.valueOf(value);
    }

    static String humanBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        String[] units = {"KB", "MB", "GB", "TB"};
        double v = bytes;
        int i = -1;
        while (v >= 1024 && i < units.length - 1) {
            v /= 1024;
            i++;
        }
        return NumberFormat.getNumberInstance(new Locale("vi", "VN")).format(Math.round(v * 10) / 10.0) + " " + units[i];
    }

    static String duration(long ms) {
        long s = ms / 1000;
        return String.format("%d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60);
    }

    /**
     * CSV/formula injection guard (security checklist): a cell whose text starts with {@code = + - @} (tag, folder
     * or group names are user-entered) is prefixed with an apostrophe so spreadsheets treat it as text.
     */
    static String escape(String text, boolean spreadsheet) {
        if (!spreadsheet || text == null || text.isEmpty()) {
            return text;
        }
        char first = text.charAt(0);
        return (first == '=' || first == '+' || first == '-' || first == '@') ? "'" + text : text;
    }

    private void export(JasperPrint print, String format, File out) throws JRException {
        SimpleExporterInput input = new SimpleExporterInput(print);
        switch (format) {
            case "PDF" -> {
                JRPdfExporter exporter = new JRPdfExporter();
                exporter.setExporterInput(input);
                exporter.setExporterOutput(new SimpleOutputStreamExporterOutput(out));
                exporter.exportReport();
            }
            case "XLSX" -> {
                // One continuous sheet: keep only the first page header (code, name, period, filters) and drop every
                // page footer, otherwise each page break leaves header/"Trang n" rows between table rows. The footer
                // facts (prepared-by, scope notice) are printed in the body instead - see sections(r, true).
                print.setProperty(XLSX_EXCLUDE_ORIGIN_KEEP_FIRST + "pageHeader", "pageHeader");
                print.setProperty(XLSX_EXCLUDE_ORIGIN + "pageFooter", "pageFooter");
                JRXlsxExporter exporter = new JRXlsxExporter();
                exporter.setExporterInput(input);
                exporter.setExporterOutput(new SimpleOutputStreamExporterOutput(out));
                SimpleXlsxReportConfiguration cfg = new SimpleXlsxReportConfiguration();
                cfg.setOnePagePerSheet(false);
                cfg.setDetectCellType(true);
                cfg.setRemoveEmptySpaceBetweenRows(true);
                cfg.setWhitePageBackground(false);
                exporter.setConfiguration(cfg);
                exporter.exportReport();
            }
            case "DOCX" -> {
                JRDocxExporter exporter = new JRDocxExporter();
                exporter.setExporterInput(input);
                exporter.setExporterOutput(new SimpleOutputStreamExporterOutput(out));
                exporter.exportReport();
            }
            default -> throw new IllegalArgumentException("Unsupported format " + format);
        }
    }
}
