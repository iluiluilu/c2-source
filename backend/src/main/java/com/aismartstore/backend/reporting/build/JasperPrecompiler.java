package com.aismartstore.backend.reporting.build;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import net.sf.jasperreports.engine.JasperCompileManager;

/**
 * Build-time only (never called at runtime): SPEC T11 / Part T §4 (review #3). Compiles every
 * {@code reports/*.jrxml} to {@code .jasper} under the build output, invoked by
 * {@code exec-maven-plugin} in phase {@code process-classes} because the old Jasper Maven plugins do
 * not support JasperReports 7. Needs {@code jasperreports-jdt} on the plugin classpath (declared as a
 * plugin dependency in pom.xml). Refuses a template containing {@code <queryString>} (AC-36: data only
 * comes from {@code JRBeanCollectionDataSource}) - the same rule {@code JrxmlGuardTest} enforces on the
 * sources.
 *
 * <p>Usage: {@code JasperPrecompiler <sourceDir> <targetDir>}.
 */
public final class JasperPrecompiler {

    private JasperPrecompiler() {
    }

    private static final Pattern COMMENT = Pattern.compile("<!--.*?-->", Pattern.DOTALL);
    private static final Pattern QUERY = Pattern.compile("<\\s*query(String)?[\\s>/]");

    /**
     * True when the template declares a data query: the old {@code <queryString>} or the JasperReports 7
     * {@code <query>} element. XML comments are ignored so documentation may mention the word.
     */
    public static boolean hasQuery(String jrxml) {
        return QUERY.matcher(COMMENT.matcher(jrxml).replaceAll("")).find();
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException("usage: JasperPrecompiler <sourceDir> <targetDir>");
        }
        Path source = Paths.get(args[0]);
        Path target = Paths.get(args[1]);
        if (!Files.isDirectory(source)) {
            System.out.println("JasperPrecompiler: no template directory " + source + ", nothing to do");
            return;
        }
        Files.createDirectories(target);
        List<Path> templates;
        try (Stream<Path> s = Files.list(source)) {
            templates = s.filter(p -> p.getFileName().toString().endsWith(".jrxml")).sorted().toList();
        }
        for (Path jrxml : templates) {
            if (hasQuery(Files.readString(jrxml))) {
                throw new IllegalStateException(
                        "Template " + jrxml.getFileName() + " must not declare a query (<queryString>/<query>, AC-36)");
            }
            String name = jrxml.getFileName().toString();
            Path out = target.resolve(name.substring(0, name.length() - ".jrxml".length()) + ".jasper");
            JasperCompileManager.compileReportToFile(jrxml.toString(), out.toString());
            System.out.println("JasperPrecompiler: " + jrxml.getFileName() + " -> " + out.getFileName());
        }
    }
}
