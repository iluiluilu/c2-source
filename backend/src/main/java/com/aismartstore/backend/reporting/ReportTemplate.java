package com.aismartstore.backend.reporting;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Read-only seeded template catalogue (V36); FK target + display. Validation source of truth is {@link ReportTemplateRegistry}. */
@Entity
@Table(name = "report_template")
public class ReportTemplate {

    @Id
    @Column(length = 48)
    private String code;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(nullable = false, columnDefinition = "text")
    private String description;

    @Column(name = "jasper_path", nullable = false)
    private String jasperPath;

    @Column(nullable = false, length = 64)
    private String formats;

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public String getJasperPath() {
        return jasperPath;
    }

    public String getFormats() {
        return formats;
    }
}
