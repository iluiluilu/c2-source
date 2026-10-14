package com.aismartstore.backend.reporting;

import static org.assertj.core.api.Assertions.assertThat;

import com.aismartstore.backend.reporting.build.JasperPrecompiler;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * AC-36 (runs in {@code mvn test}, so it fails the build): no report template may declare a data query - data reaches Jasper
 * only through JRBeanCollectionDataSource built from the aggregated DTO. Covers both the legacy {@code <queryString>} and the
 * JasperReports 7 {@code <query>} element; comments are ignored.
 */
class JrxmlGuardTest {

    private static final Path SOURCES = Path.of("src/main/resources/reports");

    @Test
    void noTemplateInTheRepositoryDeclaresAQuery() throws IOException {
        List<Path> templates;
        try (Stream<Path> s = Files.list(SOURCES)) {
            templates = s.filter(p -> p.getFileName().toString().endsWith(".jrxml")).toList();
        }
        assertThat(templates).extracting(p -> p.getFileName().toString())
                .contains("repo_summary.jrxml", "digitization_progress.jrxml", "doc_by_classification.jrxml", "table.jrxml",
                        "chart_bar.jrxml", "chart_stacked.jrxml");
        for (Path t : templates) {
            assertThat(JasperPrecompiler.hasQuery(Files.readString(t))).as(t.getFileName() + " must not declare a query").isFalse();
        }
    }

    @Test
    void detectorFlagsBothQueryFormsButNotComments() {
        assertThat(JasperPrecompiler.hasQuery("<jasperReport><queryString><![CDATA[select 1]]></queryString></jasperReport>")).isTrue();
        assertThat(JasperPrecompiler.hasQuery("<jasperReport><query language=\"sql\"><![CDATA[select 1]]></query></jasperReport>")).isTrue();
        assertThat(JasperPrecompiler.hasQuery("<jasperReport>< queryString >x</queryString></jasperReport>")).isTrue();
        assertThat(JasperPrecompiler.hasQuery("<jasperReport><!-- no <queryString> here --><field name=\"query\"/></jasperReport>")).isFalse();
    }

    @Test
    void thereIsNoEndpointOrCodePathLoadingTemplatesFromUserInput() throws IOException {
        // the renderer only loads fixed classpath resources taken from the closed registry
        String src = Files.readString(Path.of("src/main/java/com/aismartstore/backend/reporting/ReportRenderService.java"));
        assertThat(src).doesNotContain("JasperCompileManager").doesNotContain("JRXmlLoader").doesNotContain("MultipartFile");
    }
}
