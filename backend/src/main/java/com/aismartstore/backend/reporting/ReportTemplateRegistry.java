package com.aismartstore.backend.reporting;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * AC-6/AC-36: the CLOSED catalogue of 3 report templates. Source of truth for validating
 * {@code templateCode} (the {@code report_template} table only backs FKs and display); there is no
 * API to add/edit templates.
 */
@Component
public class ReportTemplateRegistry {

    public static final String REPO_SUMMARY = "REPO_SUMMARY";
    public static final String DIGITIZATION_PROGRESS = "DIGITIZATION_PROGRESS";
    public static final String DOC_BY_CLASSIFICATION = "DOC_BY_CLASSIFICATION";

    public record ParamDef(String name, String type, boolean required) {
    }

    public record Template(String code, String title, String jasperResource, boolean usesGranularity) {
    }

    private static final List<ParamDef> PARAMS = List.of(
            new ParamDef("from", "date", true),
            new ParamDef("to", "date", true),
            new ParamDef("groupIds", "uuid[]", false),
            new ParamDef("topicTagIds", "uuid[]", false),
            new ParamDef("docTypeTagIds", "uuid[]", false),
            new ParamDef("folderId", "uuid", false),
            new ParamDef("granularity", "day|month|quarter|year", false));

    private final Map<String, Template> templates = new LinkedHashMap<>();

    public ReportTemplateRegistry() {
        add(new Template(REPO_SUMMARY, "Tổng hợp tình hình kho tài liệu theo kỳ", "reports/repo_summary.jasper", true));
        add(new Template(
                DIGITIZATION_PROGRESS, "Tiến độ và chất lượng số hoá", "reports/digitization_progress.jasper", false));
        add(new Template(
                DOC_BY_CLASSIFICATION, "Thống kê theo chủ đề và loại tài liệu",
                "reports/doc_by_classification.jasper", false));
    }

    private void add(Template t) {
        templates.put(t.code(), t);
    }

    public List<Template> all() {
        return List.copyOf(templates.values());
    }

    public Optional<Template> find(String code) {
        return Optional.ofNullable(code == null ? null : templates.get(code));
    }

    public List<ParamDef> paramDefs() {
        return PARAMS;
    }
}
