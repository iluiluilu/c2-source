package com.aismartstore.backend.reporting;

import com.aismartstore.backend.common.ServiceUnavailableException;
import com.aismartstore.backend.digitization.DigitizationSource;
import com.aismartstore.backend.digitization.results.DigitizationRun;
import com.aismartstore.backend.digitization.results.DocumentDigitizationStatus;
import com.aismartstore.backend.documents.Document;
import com.aismartstore.backend.documents.DocumentStatus;
import com.aismartstore.backend.groups.BusinessDataScope;
import com.aismartstore.backend.groups.DocumentGroupShare;
import com.aismartstore.backend.groups.GroupMemberRepository;
import com.aismartstore.backend.groups.GroupRepository;
import com.aismartstore.backend.reporting.dto.ReportResult;
import com.aismartstore.backend.systemconfig.SystemConfigDefaults;
import com.aismartstore.backend.systemconfig.SystemConfigService;
import com.aismartstore.backend.tags.DocumentTag;
import com.aismartstore.backend.tags.Tag;
import com.aismartstore.backend.tags.TagKinds;
import com.aismartstore.backend.tags.TagRepository;
import com.aismartstore.backend.warehouse.WarehouseFolder;
import com.aismartstore.backend.warehouse.WarehouseFolderRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.criteria.AbstractQuery;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SPEC-export-reporting §1/§4 (T5-T8): the ONE place that builds statistics queries (AC-3, AC-16).
 * Every query starts from {@code Root<Document>} (personal files live in another table, so they can
 * never leak in) and ANDs {@link BusinessDataScope#forDocuments} (unchanged - no rewritten scope, no
 * hand-written SQL), {@code status = 'clean'} (Q1) and the filters, which are EXISTS subqueries so a
 * document with many tags/groups is never multiplied. Runs in one read-only transaction with a
 * {@code SET LOCAL statement_timeout} (AC-20).
 */
@Service
public class ReportQueryService {

    private static final Logger log = LoggerFactory.getLogger(ReportQueryService.class);
    private static final DateTimeFormatter NOTICE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public static final String UNCLASSIFIED = "Chưa phân loại";
    public static final String NOTE_STATISTICS_SOURCE =
            "Số liệu lấy trực tiếp từ cơ sở dữ liệu chính thức (PostgreSQL), khác với tổng số gần đúng "
                    + "(total) trên màn tìm kiếm.";
    public static final String NOTE_REPO_DELETED =
            "Số liệu chỉ tính tài liệu đang còn trong kho lúc lập báo cáo; tài liệu đã xoá (kể cả đang trong "
                    + "thùng rác hoặc đã xoá vĩnh viễn) không được tính, tài liệu được phục hồi được tính lại "
                    + "như chưa từng xoá. Vì vậy số liệu của kỳ quá khứ có thể thay đổi giữa các lần lập.";
    public static final String NOTE_DIGITIZATION_SNAPSHOT = "Trạng thái số hoá (Bảng 4) là ảnh chụp tại thời điểm lập báo cáo.";
    public static final String NOTE_DIGITIZATION_UNIT =
            "Đơn vị đếm là lần chạy (run) số hoá, không phải tài liệu. Các cột đang xử lý, lỗi, chờ duyệt, "
                    + "quá hạn duyệt không loại trừ nhau (quá hạn duyệt là tập con của chờ duyệt); đã xác nhận "
                    + "và chờ duyệt loại trừ nhau.";
    public static final String NOTE_MULTI_TAG =
            "Một tài liệu có thể gắn nhiều nhãn nên tổng các dòng có thể lớn hơn tổng số tài liệu. Nhãn gắn "
                    + "trên tài liệu lấy theo trạng thái hiện tại.";
    public static final String WARNING_EMPTY = "Không có dữ liệu trong phạm vi và bộ lọc đã chọn.";

    private final EntityManager em;
    private final GroupMemberRepository groupMemberRepository;
    private final WarehouseFolderRepository warehouseFolderRepository;
    private final TagRepository tagRepository;
    private final GroupRepository groupRepository;
    private final SystemConfigService systemConfigService;

    public ReportQueryService(
            EntityManager em,
            GroupMemberRepository groupMemberRepository,
            WarehouseFolderRepository warehouseFolderRepository,
            TagRepository tagRepository,
            GroupRepository groupRepository,
            SystemConfigService systemConfigService) {
        this.em = em;
        this.groupMemberRepository = groupMemberRepository;
        this.warehouseFolderRepository = warehouseFolderRepository;
        this.tagRepository = tagRepository;
        this.groupRepository = groupRepository;
        this.systemConfigService = systemConfigService;
    }

    /** Query context shared by all builders: the scope for ONE user and the (already validated) params. */
    private record Ctx(
            ReportParams params, Specification<Document> scope, List<UUID> folderIds, OffsetDateTime t0,
            OffsetDateTime t1) {
    }

    /**
     * Runs {@code templateCode} for {@code userSub}'s scope. {@code params} MUST already be validated
     * ({@link ReportParamValidator}). Throws {@code ScopeResolutionException} (fail-closed) when the
     * subject is missing and {@code ServiceUnavailableException} (503) on statement timeout.
     */
    @Transactional(readOnly = true)
    public ReportResult run(String userSub, String username, String templateCode, ReportParams params) {
        long started = System.nanoTime();
        try {
            applyStatementTimeout();
            Specification<Document> scope = BusinessDataScope.forDocuments(userSub, groupMemberRepository);
            List<UUID> folderIds = resolveFolderIds(params.folderId());
            Period period = params.period();
            Ctx ctx = new Ctx(params, scope, folderIds, period.start(), period.endExclusive());
            OffsetDateTime generatedAt = OffsetDateTime.now(ZoneOffset.UTC);

            Built built = switch (templateCode) {
                case ReportTemplateRegistry.REPO_SUMMARY -> repoSummary(ctx);
                case ReportTemplateRegistry.DIGITIZATION_PROGRESS -> digitizationProgress(ctx, generatedAt);
                case ReportTemplateRegistry.DOC_BY_CLASSIFICATION -> docByClassification(ctx);
                default -> throw new IllegalArgumentException("Unknown template " + templateCode);
            };

            List<String> notes = new ArrayList<>(built.notes());
            notes.add(NOTE_STATISTICS_SOURCE);
            List<String> warnings = new ArrayList<>();
            if (built.empty()) {
                warnings.add(WARNING_EMPTY);
            }
            String scopeNotice = "Số liệu trong phạm vi truy cập của " + username + " tại "
                    + generatedAt.atZoneSameInstant(Period.ZONE).format(NOTICE_TIME);
            ReportResult result = new ReportResult(
                    templateCode,
                    titleOf(templateCode),
                    new ReportResult.PeriodView(params.from(), params.to()),
                    filterLabels(params, templateCode),
                    scopeNotice,
                    generatedAt,
                    username,
                    List.copyOf(notes),
                    List.copyOf(warnings),
                    built.empty(),
                    built.tables(),
                    built.charts());
            log.info(
                    "report.run template={} user={} durationMs={} rows={}",
                    templateCode, userSub, (System.nanoTime() - started) / 1_000_000, result.rowCount());
            return result;
        } catch (RuntimeException e) {
            if (isStatementTimeout(e)) {
                log.warn("report.run timeout template={} user={}", templateCode, userSub);
                throw new ServiceUnavailableException(
                        "Báo cáo quá lớn nên chưa hoàn tất kịp thời gian, hãy thu hẹp khoảng thời gian hoặc bộ lọc rồi thử lại");
            }
            throw e;
        }
    }

    private static String titleOf(String templateCode) {
        return new ReportTemplateRegistry().find(templateCode).map(ReportTemplateRegistry.Template::title).orElse(templateCode);
    }

    private record Built(
            List<ReportResult.Table> tables, List<ReportResult.Chart> charts, List<String> notes, boolean empty) {
    }

    // --------------------------------------------------------------------------------------------
    // infrastructure
    // --------------------------------------------------------------------------------------------

    private void applyStatementTimeout() {
        long seconds = systemConfigService.getLong(SystemConfigDefaults.REPORTING_QUERY_TIMEOUT_SECONDS);
        em.createNativeQuery("select set_config('statement_timeout', :v, true)")
                .setParameter("v", Long.toString(seconds * 1000))
                .getSingleResult();
    }

    static boolean isStatementTimeout(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof jakarta.persistence.QueryTimeoutException) {
                return true;
            }
            if (t instanceof SQLException sql && "57014".equals(sql.getSQLState())) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }

    /** {@code folderId} plus all its descendants (Q3: same semantics as search's {@code folder_path_ids}); null = no folder filter. */
    private List<UUID> resolveFolderIds(UUID folderId) {
        if (folderId == null) {
            return null;
        }
        List<UUID> ids = new ArrayList<>();
        ids.add(folderId);
        for (WarehouseFolder f : warehouseFolderRepository.findDescendants(folderId)) {
            ids.add(f.getId());
        }
        return ids;
    }

    /** Predicates every statistic shares: scope, {@code status='clean'}, EXISTS filters. */
    private List<Predicate> documentPredicates(
            Root<Document> d, CriteriaQuery<?> outer, AbstractQuery<?> owner, CriteriaBuilder cb, Ctx ctx) {
        List<Predicate> ps = new ArrayList<>();
        ps.add(ctx.scope().toPredicate(d, outer, cb));
        ps.add(cb.equal(d.get("status"), DocumentStatus.CLEAN));
        ReportParams p = ctx.params();
        if (!p.groupIdsOrEmpty().isEmpty()) {
            Subquery<Integer> sq = owner.subquery(Integer.class);
            Root<DocumentGroupShare> s = sq.from(DocumentGroupShare.class);
            sq.select(cb.literal(1))
                    .where(cb.equal(s.get("documentId"), d.get("id")), s.get("groupId").in(p.groupIdsOrEmpty()));
            ps.add(cb.exists(sq));
        }
        if (!p.topicTagIdsOrEmpty().isEmpty()) {
            ps.add(cb.exists(tagExists(owner, cb, d, p.topicTagIdsOrEmpty())));
        }
        if (!p.docTypeTagIdsOrEmpty().isEmpty()) {
            ps.add(cb.exists(tagExists(owner, cb, d, p.docTypeTagIdsOrEmpty())));
        }
        if (ctx.folderIds() != null) {
            ps.add(d.get("folderId").in(ctx.folderIds()));
        }
        return ps;
    }

    private static Subquery<Integer> tagExists(
            AbstractQuery<?> owner, CriteriaBuilder cb, Root<Document> d, List<UUID> tagIds) {
        Subquery<Integer> sq = owner.subquery(Integer.class);
        Root<DocumentTag> dt = sq.from(DocumentTag.class);
        sq.select(cb.literal(1)).where(cb.equal(dt.get("documentId"), d.get("id")), dt.get("tagId").in(tagIds));
        return sq;
    }

    private static Predicate[] arr(List<Predicate> ps) {
        return ps.toArray(new Predicate[0]);
    }

    private static Expression<UUID> idIf(CriteriaBuilder cb, Predicate cond, Expression<UUID> id) {
        return cb.<UUID>selectCase().when(cond, id).otherwise(cb.nullLiteral(UUID.class));
    }

    private static long asLong(Object o) {
        return o == null ? 0L : ((Number) o).longValue();
    }

    private static Map<String, Object> row(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private static ReportResult.Column col(String key, String label, String type) {
        return new ReportResult.Column(key, label, type);
    }

    private static ReportResult.Table table(
            String id, String title, List<ReportResult.Column> cols, List<Map<String, Object>> rows, boolean paged) {
        return new ReportResult.Table(id, title, cols, rows, rows.size(), paged);
    }

    // --------------------------------------------------------------------------------------------
    // REPO_SUMMARY (Part T 2.1)
    // --------------------------------------------------------------------------------------------

    private Built repoSummary(Ctx ctx) {
        CriteriaBuilder cb = em.getCriteriaBuilder();

        // ---- B1: opening / ingested / closing (one pass over the closing inventory) ----
        long opening;
        long ingested;
        long closing;
        {
            CriteriaQuery<Object[]> q = cb.createQuery(Object[].class);
            Root<Document> d = q.from(Document.class);
            List<Predicate> ps = documentPredicates(d, q, q, cb, ctx);
            ps.add(cb.lessThan(d.<OffsetDateTime>get("createdAt"), ctx.t1()));
            Expression<UUID> id = d.get("id");
            q.multiselect(
                    cb.countDistinct(idIf(cb, cb.lessThan(d.<OffsetDateTime>get("createdAt"), ctx.t0()), id)),
                    cb.countDistinct(idIf(cb, cb.greaterThanOrEqualTo(d.<OffsetDateTime>get("createdAt"), ctx.t0()), id)),
                    cb.countDistinct(id));
            q.where(arr(ps));
            Object[] r = em.createQuery(q).getSingleResult();
            opening = asLong(r[0]);
            ingested = asLong(r[1]);
            closing = asLong(r[2]);
        }
        if (opening + ingested != closing) {
            log.error("report REPO_SUMMARY invariant violated: opening={} ingested={} closing={}", opening, ingested, closing);
        }
        ReportResult.Table b1 = table(
                "b1", "Bảng 1. Tình hình kho tài liệu trong kỳ",
                List.of(col("metric", "Chỉ tiêu", "string"), col("value", "Số tài liệu", "number")),
                List.of(
                        row("metric", "Tồn kho đầu kỳ", "value", opening),
                        row("metric", "Nạp mới trong kỳ", "value", ingested),
                        row("metric", "Tồn kho cuối kỳ", "value", closing)),
                false);

        // ---- B2: by file-type group over the closing inventory ----
        EnumMap<MimeGroup, long[]> byGroup = new EnumMap<>(MimeGroup.class); // [count, size, durationMs]
        for (MimeGroup g : MimeGroup.values()) {
            byGroup.put(g, new long[3]);
        }
        {
            CriteriaQuery<Object[]> q = cb.createQuery(Object[].class);
            Root<Document> d = q.from(Document.class);
            List<Predicate> ps = documentPredicates(d, q, q, cb, ctx);
            ps.add(cb.lessThan(d.<OffsetDateTime>get("createdAt"), ctx.t1()));
            q.multiselect(d.get("mimeType"), cb.countDistinct(d.get("id")), cb.sum(d.<Long>get("size")));
            q.where(arr(ps)).groupBy(d.get("mimeType"));
            for (Object[] r : em.createQuery(q).getResultList()) {
                long[] acc = byGroup.get(MimeGroup.of((String) r[0]));
                acc[0] += asLong(r[1]);
                acc[1] += asLong(r[2]);
            }
        }
        // Duration: per document MAX(duration_ms) over its digitization_source rows (review #1), then summed.
        for (Map.Entry<MimeGroup, String> e : Map.of(MimeGroup.AUDIO, "audio/", MimeGroup.VIDEO, "video/").entrySet()) {
            CriteriaQuery<Long> q = cb.createQuery(Long.class);
            Root<Document> d = q.from(Document.class);
            List<Predicate> ps = documentPredicates(d, q, q, cb, ctx);
            ps.add(cb.lessThan(d.<OffsetDateTime>get("createdAt"), ctx.t1()));
            ps.add(cb.like(d.<String>get("mimeType"), e.getValue() + "%"));
            Subquery<Integer> maxDuration = q.subquery(Integer.class);
            Root<DigitizationSource> ds = maxDuration.from(DigitizationSource.class);
            maxDuration.select(cb.max(ds.<Integer>get("durationMs"))).where(cb.equal(ds.get("documentId"), d.get("id")));
            q.select(cb.sumAsLong(cb.coalesce(maxDuration, 0)));
            q.where(arr(ps));
            Long total = em.createQuery(q).getSingleResult();
            byGroup.get(e.getKey())[2] = total == null ? 0 : total;
        }
        List<Map<String, Object>> b2Rows = new ArrayList<>();
        for (MimeGroup g : MimeGroup.values()) {
            long[] acc = byGroup.get(g);
            boolean hasDuration = g == MimeGroup.AUDIO || g == MimeGroup.VIDEO;
            b2Rows.add(row("group", g.label(), "count", acc[0], "size", acc[1], "duration", hasDuration ? acc[2] : null));
        }
        ReportResult.Table b2 = table(
                "b2", "Bảng 2. Tồn kho cuối kỳ theo nhóm loại tệp",
                List.of(
                        col("group", "Nhóm loại tệp", "string"), col("count", "Số lượng", "number"),
                        col("size", "Dung lượng", "bytes"), col("duration", "Tổng thời lượng", "duration")),
                b2Rows, false);

        // ---- B3: ingested in the period, by bucket x file-type group ----
        Granularity gran = ctx.params().granularityEnum();
        List<LocalDateTime> bucketStarts = bucketStarts(ctx.params().period(), gran);
        Map<LocalDateTime, EnumMap<MimeGroup, long[]>> bucketAcc = new LinkedHashMap<>();
        for (LocalDateTime b : bucketStarts) {
            EnumMap<MimeGroup, long[]> m = new EnumMap<>(MimeGroup.class);
            for (MimeGroup g : MimeGroup.values()) {
                m.put(g, new long[2]);
            }
            bucketAcc.put(b, m);
        }
        {
            CriteriaQuery<Object[]> q = cb.createQuery(Object[].class);
            Root<Document> d = q.from(Document.class);
            List<Predicate> ps = documentPredicates(d, q, q, cb, ctx);
            ps.add(cb.greaterThanOrEqualTo(d.<OffsetDateTime>get("createdAt"), ctx.t0()));
            ps.add(cb.lessThan(d.<OffsetDateTime>get("createdAt"), ctx.t1()));
            Expression<String> bucket = bucketExpr(cb, d, gran);
            q.multiselect(bucket, d.get("mimeType"), cb.countDistinct(d.get("id")), cb.sum(d.<Long>get("size")));
            q.where(arr(ps)).groupBy(bucket, d.get("mimeType"));
            for (Object[] r : em.createQuery(q).getResultList()) {
                EnumMap<MimeGroup, long[]> m = bucketAcc.get(LocalDate.parse((String) r[0]).atStartOfDay());
                if (m == null) {
                    continue;
                }
                long[] acc = m.get(MimeGroup.of((String) r[1]));
                acc[0] += asLong(r[2]);
                acc[1] += asLong(r[3]);
            }
        }
        List<Map<String, Object>> b3Rows = new ArrayList<>();
        List<String> categories = new ArrayList<>();
        EnumMap<MimeGroup, List<Number>> seriesValues = new EnumMap<>(MimeGroup.class);
        for (MimeGroup g : MimeGroup.values()) {
            seriesValues.put(g, new ArrayList<>());
        }
        for (Map.Entry<LocalDateTime, EnumMap<MimeGroup, long[]>> e : bucketAcc.entrySet()) {
            String label = bucketLabel(e.getKey(), gran);
            categories.add(label);
            long count = 0;
            long size = 0;
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("bucket", label);
            for (MimeGroup g : MimeGroup.values()) {
                long[] acc = e.getValue().get(g);
                count += acc[0];
                size += acc[1];
                seriesValues.get(g).add(acc[0]);
            }
            r.put("count", count);
            r.put("size", size);
            for (MimeGroup g : MimeGroup.values()) {
                r.put("g_" + g.name().toLowerCase(), e.getValue().get(g)[0]);
            }
            b3Rows.add(r);
        }
        List<ReportResult.Column> b3Cols = new ArrayList<>(List.of(
                col("bucket", "Kỳ", "string"), col("count", "Số lượng", "number"), col("size", "Dung lượng", "bytes")));
        for (MimeGroup g : MimeGroup.values()) {
            b3Cols.add(col("g_" + g.name().toLowerCase(), g.label(), "number"));
        }
        ReportResult.Table b3 = table("b3", "Bảng 3. Tài liệu nạp mới theo thời gian", b3Cols, b3Rows, false);
        List<ReportResult.Series> series = new ArrayList<>();
        for (MimeGroup g : MimeGroup.values()) {
            series.add(new ReportResult.Series(g.label(), seriesValues.get(g)));
        }
        ReportResult.Chart chart = new ReportResult.Chart(
                "c3", "Tài liệu nạp mới theo thời gian và nhóm loại tệp", "STACKED_BAR", categories, series);

        // ---- B4: digitization snapshot (not period-bound) ----
        long done = 0;
        long processing = 0;
        long pendingReview = 0;
        long failed = 0;
        {
            CriteriaQuery<Object[]> q = cb.createQuery(Object[].class);
            Root<Document> d = q.from(Document.class);
            Root<DocumentDigitizationStatus> s = q.from(DocumentDigitizationStatus.class);
            List<Predicate> ps = documentPredicates(d, q, q, cb, ctx);
            ps.add(cb.equal(s.get("documentId"), d.get("id")));
            q.multiselect(s.get("overallStatus"), cb.countDistinct(d.get("id")));
            q.where(arr(ps)).groupBy(s.get("overallStatus"));
            for (Object[] r : em.createQuery(q).getResultList()) {
                long n = asLong(r[1]);
                switch ((String) r[0]) {
                    case DocumentDigitizationStatus.STATUS_CONFIRMED -> done += n;
                    case DocumentDigitizationStatus.STATUS_QUEUED, DocumentDigitizationStatus.STATUS_PROCESSING ->
                            processing += n;
                    case DocumentDigitizationStatus.STATUS_PENDING_REVIEW, DocumentDigitizationStatus.STATUS_IN_REVIEW ->
                            pendingReview += n;
                    case DocumentDigitizationStatus.STATUS_FAILED -> failed += n;
                    default -> { /* not_applicable: not counted */ }
                }
            }
        }
        ReportResult.Table b4 = table(
                "b4", "Bảng 4. Tình trạng số hoá (tại thời điểm lập báo cáo)",
                List.of(col("status", "Trạng thái", "string"), col("count", "Số tài liệu", "number")),
                List.of(
                        row("status", "Đã xong", "count", done),
                        row("status", "Đang xử lý", "count", processing),
                        row("status", "Chờ duyệt", "count", pendingReview),
                        row("status", "Lỗi", "count", failed)),
                false);

        boolean empty = closing == 0 && done + processing + pendingReview + failed == 0;
        return new Built(
                List.of(b1, b2, b3, b4), List.of(chart),
                List.of(NOTE_REPO_DELETED, NOTE_DIGITIZATION_SNAPSHOT), empty);
    }

    /**
     * Bucket start as {@code YYYY-MM-DD} text (ICT local date). Text, not a temporal type: avoids any
     * JVM-time-zone shift when Hibernate materialises a {@code timestamp without time zone}.
     */
    private static Expression<String> bucketExpr(CriteriaBuilder cb, Root<Document> d, Granularity g) {
        Expression<LocalDateTime> local = cb.function(
                "timezone", LocalDateTime.class, cb.literal(Period.ZONE.getId()), d.get("createdAt"));
        Expression<LocalDateTime> truncated = cb.function("date_trunc", LocalDateTime.class, cb.literal(g.pgUnit()), local);
        return cb.function("to_char", String.class, truncated, cb.literal("YYYY-MM-DD"));
    }

    /** Start of every bucket of the period, in ICT local time (matches {@code date_trunc(.., timezone('Asia/Ho_Chi_Minh', ts))}). */
    static List<LocalDateTime> bucketStarts(Period period, Granularity g) {
        List<LocalDateTime> out = new ArrayList<>();
        LocalDate cursor = switch (g) {
            case DAY -> period.from();
            case MONTH -> period.from().withDayOfMonth(1);
            case QUARTER -> period.from().withDayOfMonth(1).withMonth(((period.from().getMonthValue() - 1) / 3) * 3 + 1);
            case YEAR -> period.from().withDayOfYear(1);
        };
        while (!cursor.isAfter(period.to())) {
            out.add(cursor.atStartOfDay());
            cursor = switch (g) {
                case DAY -> cursor.plusDays(1);
                case MONTH -> cursor.plusMonths(1);
                case QUARTER -> cursor.plusMonths(3);
                case YEAR -> cursor.plusYears(1);
            };
        }
        return out;
    }

    static String bucketLabel(LocalDateTime start, Granularity g) {
        return switch (g) {
            case DAY -> start.toLocalDate().toString();
            case MONTH -> String.format("%04d-%02d", start.getYear(), start.getMonthValue());
            case QUARTER -> String.format("%04d-Q%d", start.getYear(), (start.getMonthValue() - 1) / 3 + 1);
            case YEAR -> String.format("%04d", start.getYear());
        };
    }

    // --------------------------------------------------------------------------------------------
    // DIGITIZATION_PROGRESS (Part T 2.2)
    // --------------------------------------------------------------------------------------------

    private static final List<String> DIGITIZATION_TASKS =
            List.of(DigitizationRun.TASK_OCR, DigitizationRun.TASK_STT, DigitizationRun.TASK_VIDEO_SUMMARY);

    private static String taskLabel(String task) {
        return switch (task) {
            case DigitizationRun.TASK_OCR -> "OCR (nhận dạng chữ)";
            case DigitizationRun.TASK_STT -> "STT (nhận dạng giọng nói)";
            case DigitizationRun.TASK_VIDEO_SUMMARY -> "Tóm tắt video";
            default -> task;
        };
    }

    private Built digitizationProgress(Ctx ctx, OffsetDateTime now) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Object[]> q = cb.createQuery(Object[].class);
        Root<DigitizationRun> r = q.from(DigitizationRun.class);

        // r.document ∈ scoped/filtered documents (EXISTS on Root<Document> + BusinessDataScope)
        Subquery<UUID> docs = q.subquery(UUID.class);
        Root<Document> d = docs.from(Document.class);
        List<Predicate> docPs = documentPredicates(d, q, docs, cb, ctx);
        docs.select(d.<UUID>get("id")).where(arr(docPs));

        Expression<UUID> id = r.get("id");
        Predicate processing = r.<String>get("processingStatus").in(
                DigitizationRun.PROCESSING_QUEUED, DigitizationRun.PROCESSING_RUNNING,
                DigitizationRun.PROCESSING_FAILED_RETRYABLE);
        Predicate error = cb.equal(r.get("processingStatus"), DigitizationRun.PROCESSING_FAILED_PERMANENT);
        Predicate pendingReview = r.<String>get("reviewState").in(
                DigitizationRun.REVIEW_PENDING, DigitizationRun.REVIEW_IN_REVIEW);
        Predicate overdue = cb.and(pendingReview, cb.lessThan(r.<OffsetDateTime>get("reviewDeadlineAt"), now));
        Predicate confirmed = cb.equal(r.get("reviewState"), DigitizationRun.REVIEW_CONFIRMED);
        Predicate cManual = cb.and(confirmed, cb.equal(r.get("confirmSource"), DigitizationRun.CONFIRM_MANUAL_UNCHANGED));
        Predicate cEdited = cb.and(confirmed, cb.equal(r.get("confirmSource"), DigitizationRun.CONFIRM_MANUAL_EDITED));
        Predicate cAuto = cb.and(confirmed, cb.equal(r.get("confirmSource"), DigitizationRun.CONFIRM_AUTO));

        q.multiselect(
                r.get("task"),
                cb.countDistinct(id),
                cb.countDistinct(idIf(cb, processing, id)),
                cb.countDistinct(idIf(cb, error, id)),
                cb.countDistinct(idIf(cb, pendingReview, id)),
                cb.countDistinct(idIf(cb, overdue, id)),
                cb.countDistinct(idIf(cb, cManual, id)),
                cb.countDistinct(idIf(cb, cEdited, id)),
                cb.countDistinct(idIf(cb, cAuto, id)));
        q.where(
                r.get("documentId").in(docs),
                cb.not(r.<String>get("reviewState").in(DigitizationRun.REVIEW_SUPERSEDED, DigitizationRun.REVIEW_VOID)),
                cb.greaterThanOrEqualTo(r.<OffsetDateTime>get("createdAt"), ctx.t0()),
                cb.lessThan(r.<OffsetDateTime>get("createdAt"), ctx.t1()),
                r.<String>get("task").in(DIGITIZATION_TASKS));
        q.groupBy(r.get("task"));

        Map<String, long[]> byTask = new LinkedHashMap<>();
        for (String t : DIGITIZATION_TASKS) {
            byTask.put(t, new long[8]);
        }
        for (Object[] row : em.createQuery(q).getResultList()) {
            long[] acc = byTask.get((String) row[0]);
            for (int i = 0; i < 8; i++) {
                acc[i] = asLong(row[i + 1]);
            }
        }
        String[] keys = {"total", "processing", "error", "pending", "overdue", "c_unchanged", "c_edited", "c_auto"};
        String[] labels = {
            "Tổng số lần chạy", "Đang xử lý", "Lỗi", "Chờ duyệt", "Quá hạn duyệt", "Đã xác nhận (giữ nguyên)",
            "Đã xác nhận (có sửa)", "Tự xác nhận"
        };
        List<ReportResult.Column> cols = new ArrayList<>();
        cols.add(col("task", "Tác vụ", "string"));
        for (int i = 0; i < keys.length; i++) {
            cols.add(col(keys[i], labels[i], "number"));
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        long total = 0;
        for (Map.Entry<String, long[]> e : byTask.entrySet()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("task", taskLabel(e.getKey()));
            for (int i = 0; i < keys.length; i++) {
                m.put(keys[i], e.getValue()[i]);
            }
            total += e.getValue()[0];
            rows.add(m);
        }
        List<String> categories = DIGITIZATION_TASKS.stream().map(ReportQueryService::taskLabel).toList();
        List<ReportResult.Series> series = new ArrayList<>();
        for (int i = 1; i < keys.length; i++) {
            if (i == 4) {
                continue; // overdue is a subset of pending: not stacked
            }
            List<Number> values = new ArrayList<>();
            for (String t : DIGITIZATION_TASKS) {
                values.add(byTask.get(t)[i]);
            }
            series.add(new ReportResult.Series(labels[i], values));
        }
        ReportResult.Chart chart = new ReportResult.Chart(
                "c1", "Tiến độ số hoá theo tác vụ", "STACKED_BAR", categories, series);
        return new Built(
                List.of(table("d1", "Bảng 1. Số hoá theo tác vụ", cols, rows, false)), List.of(chart),
                List.of(NOTE_DIGITIZATION_UNIT), total == 0);
    }

    // --------------------------------------------------------------------------------------------
    // DOC_BY_CLASSIFICATION (Part T 2.3)
    // --------------------------------------------------------------------------------------------

    private Built docByClassification(Ctx ctx) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        ReportParams p = ctx.params();
        boolean topicFiltered = !p.topicTagIdsOrEmpty().isEmpty();
        boolean docTypeFiltered = !p.docTypeTagIdsOrEmpty().isEmpty();

        // 1) both a topic and a doc_type
        Map<UUID, Map<UUID, Long>> matrix = new LinkedHashMap<>();
        Map<UUID, Long> topicTotals = new LinkedHashMap<>();
        Map<UUID, Long> docTypeTotals = new LinkedHashMap<>();
        Map<UUID, Long> topicWithoutDocType = new LinkedHashMap<>();
        Map<UUID, Long> docTypeWithoutTopic = new LinkedHashMap<>();
        long neither;
        long totalDocs;
        {
            CriteriaQuery<Object[]> q = cb.createQuery(Object[].class);
            Root<Document> d = q.from(Document.class);
            Root<DocumentTag> dt1 = q.from(DocumentTag.class);
            Root<Tag> t1 = q.from(Tag.class);
            Root<DocumentTag> dt2 = q.from(DocumentTag.class);
            Root<Tag> t2 = q.from(Tag.class);
            List<Predicate> ps = classificationBase(d, q, cb, ctx);
            ps.add(cb.equal(dt1.get("documentId"), d.get("id")));
            ps.add(cb.equal(dt1.get("tagId"), t1.get("id")));
            ps.add(cb.equal(t1.get("kind"), TagKinds.TOPIC));
            ps.add(cb.equal(dt2.get("documentId"), d.get("id")));
            ps.add(cb.equal(dt2.get("tagId"), t2.get("id")));
            ps.add(cb.equal(t2.get("kind"), TagKinds.DOC_TYPE));
            if (topicFiltered) {
                ps.add(t1.get("id").in(p.topicTagIdsOrEmpty()));
            }
            if (docTypeFiltered) {
                ps.add(t2.get("id").in(p.docTypeTagIdsOrEmpty()));
            }
            q.multiselect(t1.get("id"), t2.get("id"), cb.countDistinct(d.get("id")));
            q.where(arr(ps)).groupBy(t1.get("id"), t2.get("id"));
            for (Object[] r : em.createQuery(q).getResultList()) {
                matrix.computeIfAbsent((UUID) r[0], k -> new LinkedHashMap<>()).put((UUID) r[1], asLong(r[2]));
            }
        }
        // 2) topic only (no doc_type)
        if (!docTypeFiltered) {
            CriteriaQuery<Object[]> q = cb.createQuery(Object[].class);
            Root<Document> d = q.from(Document.class);
            Root<DocumentTag> dt1 = q.from(DocumentTag.class);
            Root<Tag> t1 = q.from(Tag.class);
            List<Predicate> ps = classificationBase(d, q, cb, ctx);
            ps.add(cb.equal(dt1.get("documentId"), d.get("id")));
            ps.add(cb.equal(dt1.get("tagId"), t1.get("id")));
            ps.add(cb.equal(t1.get("kind"), TagKinds.TOPIC));
            if (topicFiltered) {
                ps.add(t1.get("id").in(p.topicTagIdsOrEmpty()));
            }
            ps.add(cb.not(cb.exists(kindExists(q, cb, d, TagKinds.DOC_TYPE))));
            q.multiselect(t1.get("id"), cb.countDistinct(d.get("id")));
            q.where(arr(ps)).groupBy(t1.get("id"));
            for (Object[] r : em.createQuery(q).getResultList()) {
                topicWithoutDocType.put((UUID) r[0], asLong(r[1]));
            }
        }
        // 3) doc_type only (no topic)
        if (!topicFiltered) {
            CriteriaQuery<Object[]> q = cb.createQuery(Object[].class);
            Root<Document> d = q.from(Document.class);
            Root<DocumentTag> dt2 = q.from(DocumentTag.class);
            Root<Tag> t2 = q.from(Tag.class);
            List<Predicate> ps = classificationBase(d, q, cb, ctx);
            ps.add(cb.equal(dt2.get("documentId"), d.get("id")));
            ps.add(cb.equal(dt2.get("tagId"), t2.get("id")));
            ps.add(cb.equal(t2.get("kind"), TagKinds.DOC_TYPE));
            if (docTypeFiltered) {
                ps.add(t2.get("id").in(p.docTypeTagIdsOrEmpty()));
            }
            ps.add(cb.not(cb.exists(kindExists(q, cb, d, TagKinds.TOPIC))));
            q.multiselect(t2.get("id"), cb.countDistinct(d.get("id")));
            q.where(arr(ps)).groupBy(t2.get("id"));
            for (Object[] r : em.createQuery(q).getResultList()) {
                docTypeWithoutTopic.put((UUID) r[0], asLong(r[1]));
            }
        }
        // 4) neither
        if (!topicFiltered && !docTypeFiltered) {
            CriteriaQuery<Long> q = cb.createQuery(Long.class);
            Root<Document> d = q.from(Document.class);
            List<Predicate> ps = classificationBase(d, q, cb, ctx);
            ps.add(cb.not(cb.exists(kindExists(q, cb, d, TagKinds.TOPIC))));
            ps.add(cb.not(cb.exists(kindExists(q, cb, d, TagKinds.DOC_TYPE))));
            q.select(cb.countDistinct(d.get("id"))).where(arr(ps));
            neither = asLong(em.createQuery(q).getSingleResult());
        } else {
            neither = 0;
        }
        // total distinct documents in the inventory (for the empty flag / reference)
        {
            CriteriaQuery<Long> q = cb.createQuery(Long.class);
            Root<Document> d = q.from(Document.class);
            q.select(cb.countDistinct(d.get("id"))).where(arr(classificationBase(d, q, cb, ctx)));
            totalDocs = asLong(em.createQuery(q).getSingleResult());
        }

        // per-dimension totals = row/column marginals computed as distinct counts, NOT sums of the matrix
        // (a document with several tags would otherwise be double counted inside a single dimension)
        collectMarginals(ctx, TagKinds.TOPIC, topicFiltered ? p.topicTagIdsOrEmpty() : null, topicTotals);
        collectMarginals(ctx, TagKinds.DOC_TYPE, docTypeFiltered ? p.docTypeTagIdsOrEmpty() : null, docTypeTotals);

        Map<UUID, String> labels = new LinkedHashMap<>();
        for (Tag t : tagRepository.findAllById(union(matrix.keySet(), topicWithoutDocType.keySet(),
                docTypeWithoutTopic.keySet(), topicTotals.keySet(), docTypeTotals.keySet(), nestedKeys(matrix)))) {
            labels.put(t.getId(), t.getLabel());
        }
        List<UUID> topicIds = sortByLabel(union(matrix.keySet(), topicWithoutDocType.keySet(), topicTotals.keySet()), labels);
        List<UUID> docTypeIds =
                sortByLabel(union(nestedKeys(matrix), docTypeWithoutTopic.keySet(), docTypeTotals.keySet()), labels);

        // ---- matrix table: rows = topic, columns = doc_type (+ Chưa phân loại) ----
        List<ReportResult.Column> mCols = new ArrayList<>();
        mCols.add(col("topic", "Chủ đề \\ Loại tài liệu", "string"));
        for (int i = 0; i < docTypeIds.size(); i++) {
            mCols.add(col("dt" + i, labels.get(docTypeIds.get(i)), "number"));
        }
        boolean showUnclassifiedCol = !docTypeFiltered;
        if (showUnclassifiedCol) {
            mCols.add(col("dt_none", UNCLASSIFIED, "number"));
        }
        List<Map<String, Object>> mRows = new ArrayList<>();
        for (UUID topicId : topicIds) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("topic", labels.get(topicId));
            Map<UUID, Long> rowCounts = matrix.getOrDefault(topicId, Map.of());
            for (int i = 0; i < docTypeIds.size(); i++) {
                r.put("dt" + i, rowCounts.getOrDefault(docTypeIds.get(i), 0L));
            }
            if (showUnclassifiedCol) {
                r.put("dt_none", topicWithoutDocType.getOrDefault(topicId, 0L));
            }
            mRows.add(r);
        }
        if (!topicFiltered) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("topic", UNCLASSIFIED);
            for (int i = 0; i < docTypeIds.size(); i++) {
                r.put("dt" + i, docTypeWithoutTopic.getOrDefault(docTypeIds.get(i), 0L));
            }
            if (showUnclassifiedCol) {
                r.put("dt_none", neither);
            }
            mRows.add(r);
        }
        ReportResult.Table matrixTable = table(
                "m1", "Bảng 1. Ma trận chủ đề x loại tài liệu", mCols, mRows, true);

        // ---- by-topic and by-doc-type tables ----
        List<Map<String, Object>> byTopicRows = new ArrayList<>();
        List<String> topicCats = new ArrayList<>();
        List<Number> topicVals = new ArrayList<>();
        for (UUID id : topicIds) {
            long n = topicTotals.getOrDefault(id, 0L);
            byTopicRows.add(row("label", labels.get(id), "count", n));
            topicCats.add(labels.get(id));
            topicVals.add(n);
        }
        if (!topicFiltered) {
            long n = unclassifiedTotal(ctx, TagKinds.TOPIC);
            byTopicRows.add(row("label", UNCLASSIFIED, "count", n));
            topicCats.add(UNCLASSIFIED);
            topicVals.add(n);
        }
        List<Map<String, Object>> byDocTypeRows = new ArrayList<>();
        List<String> dtCats = new ArrayList<>();
        List<Number> dtVals = new ArrayList<>();
        for (UUID id : docTypeIds) {
            long n = docTypeTotals.getOrDefault(id, 0L);
            byDocTypeRows.add(row("label", labels.get(id), "count", n));
            dtCats.add(labels.get(id));
            dtVals.add(n);
        }
        if (!docTypeFiltered) {
            long n = unclassifiedTotal(ctx, TagKinds.DOC_TYPE);
            byDocTypeRows.add(row("label", UNCLASSIFIED, "count", n));
            dtCats.add(UNCLASSIFIED);
            dtVals.add(n);
        }
        ReportResult.Table byTopic = table(
                "t1", "Bảng 2. Theo chủ đề",
                List.of(col("label", "Chủ đề", "string"), col("count", "Số tài liệu", "number")), byTopicRows, false);
        ReportResult.Table byDocType = table(
                "t2", "Bảng 3. Theo loại tài liệu",
                List.of(col("label", "Loại tài liệu", "string"), col("count", "Số tài liệu", "number")), byDocTypeRows,
                false);
        List<ReportResult.Chart> charts = List.of(
                new ReportResult.Chart("c1", "Số tài liệu theo chủ đề", "BAR", topicCats,
                        List.of(new ReportResult.Series("Số tài liệu", topicVals))),
                new ReportResult.Chart("c2", "Số tài liệu theo loại tài liệu", "BAR", dtCats,
                        List.of(new ReportResult.Series("Số tài liệu", dtVals))));
        return new Built(List.of(matrixTable, byTopic, byDocType), charts, List.of(NOTE_MULTI_TAG), totalDocs == 0);
    }

    /** Inventory at the end of the period (tồn kho cuối kỳ): scope + status + filters + created_at < to+1. */
    private List<Predicate> classificationBase(Root<Document> d, CriteriaQuery<?> q, CriteriaBuilder cb, Ctx ctx) {
        List<Predicate> ps = documentPredicates(d, q, q, cb, ctx);
        ps.add(cb.lessThan(d.<OffsetDateTime>get("createdAt"), ctx.t1()));
        return ps;
    }

    private static Subquery<Integer> kindExists(CriteriaQuery<?> q, CriteriaBuilder cb, Root<Document> d, String kind) {
        Subquery<Integer> sq = q.subquery(Integer.class);
        Root<DocumentTag> dt = sq.from(DocumentTag.class);
        Root<Tag> t = sq.from(Tag.class);
        sq.select(cb.literal(1))
                .where(
                        cb.equal(dt.get("documentId"), d.get("id")),
                        cb.equal(dt.get("tagId"), t.get("id")),
                        cb.equal(t.get("kind"), kind));
        return sq;
    }

    /** Distinct documents per tag of one kind (marginal of the matrix, no multiplication across the other dimension). */
    private void collectMarginals(Ctx ctx, String kind, List<UUID> onlyTagIds, Map<UUID, Long> out) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Object[]> q = cb.createQuery(Object[].class);
        Root<Document> d = q.from(Document.class);
        Root<DocumentTag> dt = q.from(DocumentTag.class);
        Root<Tag> t = q.from(Tag.class);
        List<Predicate> ps = classificationBase(d, q, cb, ctx);
        ps.add(cb.equal(dt.get("documentId"), d.get("id")));
        ps.add(cb.equal(dt.get("tagId"), t.get("id")));
        ps.add(cb.equal(t.get("kind"), kind));
        if (onlyTagIds != null) {
            ps.add(t.get("id").in(onlyTagIds));
        }
        q.multiselect(t.get("id"), cb.countDistinct(d.get("id")));
        q.where(arr(ps)).groupBy(t.get("id"));
        for (Object[] r : em.createQuery(q).getResultList()) {
            out.put((UUID) r[0], asLong(r[1]));
        }
    }

    private long unclassifiedTotal(Ctx ctx, String kind) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Long> q = cb.createQuery(Long.class);
        Root<Document> d = q.from(Document.class);
        List<Predicate> ps = classificationBase(d, q, cb, ctx);
        ps.add(cb.not(cb.exists(kindExists(q, cb, d, kind))));
        q.select(cb.countDistinct(d.get("id"))).where(arr(ps));
        return asLong(em.createQuery(q).getSingleResult());
    }

    @SafeVarargs
    private static List<UUID> union(java.util.Collection<UUID>... parts) {
        java.util.LinkedHashSet<UUID> set = new java.util.LinkedHashSet<>();
        for (java.util.Collection<UUID> p : parts) {
            set.addAll(p);
        }
        return new ArrayList<>(set);
    }

    private static java.util.Collection<UUID> nestedKeys(Map<UUID, Map<UUID, Long>> matrix) {
        java.util.LinkedHashSet<UUID> keys = new java.util.LinkedHashSet<>();
        matrix.values().forEach(m -> keys.addAll(m.keySet()));
        return keys;
    }

    private static List<UUID> sortByLabel(List<UUID> ids, Map<UUID, String> labels) {
        List<UUID> sorted = new ArrayList<>(ids);
        sorted.sort(java.util.Comparator.comparing((UUID id) -> labels.getOrDefault(id, id.toString()).toLowerCase())
                .thenComparing(UUID::toString));
        return sorted;
    }

    // --------------------------------------------------------------------------------------------
    // display filters (names only appear here and in the rendered file - never in audit)
    // --------------------------------------------------------------------------------------------

    private Map<String, String> filterLabels(ReportParams p, String templateCode) {
        Map<String, String> f = new LinkedHashMap<>();
        if (!p.groupIdsOrEmpty().isEmpty()) {
            f.put("Nhóm", String.join(", ", groupRepository.findAllById(p.groupIdsOrEmpty()).stream()
                    .map(g -> g.getName()).sorted().toList()));
        }
        if (!p.topicTagIdsOrEmpty().isEmpty()) {
            f.put("Chủ đề", labelsOf(p.topicTagIdsOrEmpty()));
        }
        if (!p.docTypeTagIdsOrEmpty().isEmpty()) {
            f.put("Loại tài liệu", labelsOf(p.docTypeTagIdsOrEmpty()));
        }
        if (p.folderId() != null) {
            f.put("Thư mục", warehouseFolderRepository.findById(p.folderId()).map(WarehouseFolder::getName)
                    .orElse(""));
        }
        if (ReportTemplateRegistry.REPO_SUMMARY.equals(templateCode)) {
            f.put("Độ chi tiết", p.granularityEnum().label());
        }
        return f;
    }

    private String labelsOf(List<UUID> tagIds) {
        return String.join(", ", tagRepository.findAllById(tagIds).stream().map(Tag::getLabel).sorted().toList());
    }
}
