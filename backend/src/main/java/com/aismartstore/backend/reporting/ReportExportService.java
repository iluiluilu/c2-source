package com.aismartstore.backend.reporting;

import com.aismartstore.backend.auth.KeycloakUser;
import com.aismartstore.backend.common.BadRequestException;
import com.aismartstore.backend.common.ConflictException;
import com.aismartstore.backend.common.GoneException;
import com.aismartstore.backend.common.NotFoundException;
import com.aismartstore.backend.common.TooManyRequestsException;
import com.aismartstore.backend.config.StorageProperties;
import com.aismartstore.backend.reporting.dto.ReportDtos;
import com.aismartstore.backend.storage.ObjectStorageService;
import com.aismartstore.backend.systemconfig.SystemConfigDefaults;
import com.aismartstore.backend.systemconfig.SystemConfigService;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.core.io.InputStreamResource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

/** T12 (AC-21, 29-32): create/list/status/download/retry of export jobs. Ownership is enforced here (AC-31). */
@Service
public class ReportExportService {

    public static final Set<String> FORMATS = Set.of("PDF", "XLSX", "DOCX");
    public static final String MSG_EXPIRED = "Tệp báo cáo đã hết hạn, hãy tạo lại báo cáo";
    private static final DateTimeFormatter FILE_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final ReportExportRepository repository;
    private final ReportTemplateRegistry registry;
    private final ReportParamValidator validator;
    private final ReportAuditor auditor;
    private final ReportAccessGuard guard;
    private final SystemConfigService systemConfigService;
    private final JdbcTemplate jdbc;
    private final ObjectStorageService storage;
    private final StorageProperties storageProperties;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    public ReportExportService(
            ReportExportRepository repository,
            ReportTemplateRegistry registry,
            ReportParamValidator validator,
            ReportAuditor auditor,
            ReportAccessGuard guard,
            SystemConfigService systemConfigService,
            JdbcTemplate jdbc,
            ObjectStorageService storage,
            StorageProperties storageProperties,
            com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
        this.repository = repository;
        this.registry = registry;
        this.validator = validator;
        this.auditor = auditor;
        this.guard = guard;
        this.systemConfigService = systemConfigService;
        this.jdbc = jdbc;
        this.storage = storage;
        this.storageProperties = storageProperties;
        this.objectMapper = objectMapper;
    }

    /** AC-21: 202 + PENDING. Validation (400) happens BEFORE the quota transaction; 429 inserts and audits nothing (AC-30). */
    @Transactional
    public ReportDtos.ExportCreated create(KeycloakUser user, ReportDtos.CreateExportRequest request) {
        if (request == null || request.templateCode() == null) {
            throw new BadRequestException("Thiếu mẫu báo cáo");
        }
        ReportTemplateRegistry.Template template = registry.find(request.templateCode())
                .orElseThrow(() -> new BadRequestException("Mẫu báo cáo không hợp lệ"));
        String format = request.format() == null ? "" : request.format().trim().toUpperCase(Locale.ROOT);
        if (!FORMATS.contains(format)) {
            throw new BadRequestException("Định dạng không hợp lệ, hãy chọn PDF, XLSX hoặc DOCX");
        }
        ReportParams params = validator.validate(request.params(), template, user.sub());
        return insertJob(user, template.code(), params, format, null);
    }

    /** AC-29: a NEW job (new id, same template/params/format), through the same quota path with its own audit row. */
    @Transactional
    public ReportDtos.ExportCreated retry(KeycloakUser user, UUID id) {
        ReportExport old = requireOwned(user, id);
        if (!ReportExport.FAILED.equals(old.getStatus())) {
            throw new ConflictException("Chỉ có thể chạy lại tệp xuất đã thất bại");
        }
        ReportParams params = objectMapper.convertValue(old.getParamsJson(), ReportParams.class);
        return insertJob(user, old.getTemplateCode(), params, old.getFormat(), old.getId());
    }

    private ReportDtos.ExportCreated insertJob(
            KeycloakUser user, String templateCode, ReportParams params, String format, UUID retryOf) {
        // Serialize the check+insert across instances (R-6): one transaction-scoped advisory lock.
        jdbc.query("SELECT pg_advisory_xact_lock(hashtext('report_export_quota'))", rs -> {
            return null;
        });
        long perUser = systemConfigService.getLong(SystemConfigDefaults.REPORTING_MAX_EXPORTS_PER_USER);
        if (repository.countActiveByUser(user.sub()) >= perUser) {
            auditor.skipAuditForQuotaRejection();
            throw new TooManyRequestsException(
                    "Bạn đang có " + perUser + " tệp đang tạo, hãy chờ xong rồi thử lại");
        }
        long system = systemConfigService.getLong(SystemConfigDefaults.REPORTING_MAX_CONCURRENT_EXPORTS);
        if (repository.countActive() >= system) {
            auditor.skipAuditForQuotaRejection();
            throw new TooManyRequestsException("Hệ thống đang bận, hãy thử lại sau");
        }
        ReportExport job = new ReportExport();
        job.setTemplateCode(templateCode);
        job.setParamsJson(params.toMap());
        job.setFormat(format);
        job.setStatus(ReportExport.PENDING);
        job.setRequestedBySub(user.sub());
        job.setRequestedByUsername(user.preferredUsername() != null ? user.preferredUsername() : user.sub());
        job.setRetryOf(retryOf);
        job.setCreatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        ReportExport saved = repository.saveAndFlush(job);
        auditor.exportCreated(user, saved);
        return new ReportDtos.ExportCreated(saved.getId(), saved.getStatus());
    }

    @Transactional(readOnly = true)
    public ReportDtos.ExportPage list(KeycloakUser user, int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new BadRequestException("page phải >= 0 và size trong khoảng 1..100");
        }
        Page<ReportExport> p = repository.findByRequestedBySubOrderByCreatedAtDesc(user.sub(), PageRequest.of(page, size));
        return new ReportDtos.ExportPage(
                p.getContent().stream().map(ReportExportService::view).toList(), p.getNumber(), p.getSize(),
                p.getTotalElements(), p.getTotalPages());
    }

    @Transactional(readOnly = true)
    public ReportDtos.ExportView get(KeycloakUser user, UUID id) {
        return view(requireOwned(user, id));
    }

    /** AC-24/31/33: owner only; EXPIRED -> 410; not DONE -> 409; stream from the {@code reports} bucket. */
    public ResponseEntity<InputStreamResource> download(KeycloakUser user, UUID id) {
        ReportExport job = requireOwned(user, id);
        if (ReportExport.EXPIRED.equals(job.getStatus())) {
            throw new GoneException(MSG_EXPIRED);
        }
        if (!ReportExport.DONE.equals(job.getStatus())) {
            throw new ConflictException("Tệp báo cáo chưa sẵn sàng để tải");
        }
        InputStreamResource body;
        try {
            body = new InputStreamResource(storage.getObject(job.getObjectKey(), storageProperties.reportsBucket()));
        } catch (NoSuchKeyException e) {
            throw new GoneException(MSG_EXPIRED);
        }
        auditor.download(user, job);
        String fileName = fileName(job);
        return ResponseEntity.ok()
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(fileName).build().toString())
                .contentType(mediaType(job.getFormat()))
                .body(body);
    }

    /** Fixed safe name {@code <template>_<yyyyMMdd>.<ext>} - never user text (header injection). */
    static String fileName(ReportExport job) {
        String date = (job.getFinishedAt() != null ? job.getFinishedAt() : job.getCreatedAt())
                .atZoneSameInstant(Period.ZONE)
                .format(FILE_DATE);
        return job.getTemplateCode().toLowerCase(Locale.ROOT) + "_" + date + "." + extension(job.getFormat());
    }

    static String extension(String format) {
        return format.toLowerCase(Locale.ROOT);
    }

    static MediaType mediaType(String format) {
        return switch (format) {
            case "PDF" -> MediaType.APPLICATION_PDF;
            case "XLSX" -> MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            case "DOCX" -> MediaType.parseMediaType(
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
            default -> MediaType.APPLICATION_OCTET_STREAM;
        };
    }

    /** 404 when absent; 403 + {@code report.access_denied} when it belongs to someone else (AC-31). */
    private ReportExport requireOwned(KeycloakUser user, UUID id) {
        ReportExport job = repository.findById(id).orElseThrow(() -> new NotFoundException("Không tìm thấy tệp xuất"));
        if (!job.getRequestedBySub().equals(user.sub())) {
            throw guard.denyNotOwner(user, id.toString());
        }
        return job;
    }

    static ReportDtos.ExportView view(ReportExport e) {
        return new ReportDtos.ExportView(
                e.getId(), e.getTemplateCode(), e.getFormat(), e.getStatus(), e.getCreatedAt(), e.getFinishedAt(),
                e.getExpiresAt(), e.getErrorMessage());
    }
}
