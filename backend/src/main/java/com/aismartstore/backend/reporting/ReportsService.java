package com.aismartstore.backend.reporting;

import com.aismartstore.backend.auth.KeycloakUser;
import com.aismartstore.backend.common.BadRequestException;
import com.aismartstore.backend.common.NotFoundException;
import com.aismartstore.backend.reporting.dto.ReportDtos;
import com.aismartstore.backend.reporting.dto.ReportResult;
import com.aismartstore.backend.reporting.dto.RunReportRequest;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/** T4/T9: template catalogue + synchronous run ({@code POST /reports/{code}/run}, AC-18). */
@Service
public class ReportsService {

    static final int DEFAULT_PAGE_SIZE = 50;
    static final int MAX_PAGE_SIZE = 500;

    private final ReportTemplateRegistry registry;
    private final ReportTemplateRepository templateRepository;
    private final ReportParamValidator validator;
    private final ReportQueryService queryService;
    private final ReportAuditor auditor;

    public ReportsService(
            ReportTemplateRegistry registry,
            ReportTemplateRepository templateRepository,
            ReportParamValidator validator,
            ReportQueryService queryService,
            ReportAuditor auditor) {
        this.registry = registry;
        this.templateRepository = templateRepository;
        this.validator = validator;
        this.queryService = queryService;
        this.auditor = auditor;
    }

    /** AC-6: exactly the 3 closed templates, with name, description and parameters. */
    public List<ReportDtos.TemplateView> templates() {
        Map<String, ReportTemplate> rows = new java.util.HashMap<>();
        templateRepository.findAll().forEach(t -> rows.put(t.getCode(), t));
        List<ReportDtos.ParamView> params = registry.paramDefs().stream()
                .map(p -> new ReportDtos.ParamView(p.name(), p.type(), p.required()))
                .toList();
        return registry.all().stream()
                .map(t -> {
                    ReportTemplate row = rows.get(t.code());
                    return new ReportDtos.TemplateView(
                            t.code(),
                            row != null ? row.getName() : t.title(),
                            row != null ? row.getDescription() : "",
                            params);
                })
                .toList();
    }

    public ReportTemplateRegistry.Template requireTemplate(String code) {
        return registry.find(code).orElseThrow(() -> new NotFoundException("Không tìm thấy mẫu báo cáo"));
    }

    public ReportResult run(KeycloakUser user, String templateCode, RunReportRequest request) {
        ReportTemplateRegistry.Template template = requireTemplate(templateCode);
        ReportParams params = validator.validate(request.toParams(), template, user.sub());
        long started = System.nanoTime();
        ReportResult full = queryService.run(user.sub(), user.preferredUsername(), templateCode, params);
        long durationMs = (System.nanoTime() - started) / 1_000_000;
        auditor.generate(user, templateCode, params, full.rowCount(), durationMs);
        return paginate(full, request.page(), request.size());
    }

    /** AC-18: paged tables (the matrix) are cut by row; {@code totalRows} always carries the full count. */
    static ReportResult paginate(ReportResult full, Integer pageParam, Integer sizeParam) {
        int page = pageParam == null ? 0 : pageParam;
        int size = sizeParam == null ? DEFAULT_PAGE_SIZE : sizeParam;
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new BadRequestException("page phải >= 0 và size phải trong khoảng 1.." + MAX_PAGE_SIZE);
        }
        List<ReportResult.Table> tables = full.tables().stream()
                .map(t -> {
                    if (!t.paged()) {
                        return t;
                    }
                    int from = (int) Math.min((long) page * size, t.rows().size());
                    int to = Math.min(from + size, t.rows().size());
                    return new ReportResult.Table(
                            t.id(), t.title(), t.columns(), List.copyOf(t.rows().subList(from, to)), t.totalRows(),
                            true);
                })
                .toList();
        return new ReportResult(
                full.templateCode(), full.title(), full.period(), full.filters(), full.scopeNotice(),
                full.generatedAt(), full.generatedBy(), full.notes(), full.warnings(), full.empty(), tables,
                full.charts());
    }
}
