package com.aismartstore.backend.systemconfig;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The MVP catalogue of configurable business parameters and their fallback
 * defaults, transcribed from the SA decision table (Quyết định 3 in
 * docs/01-sa/architecture/system-config.md). This registry - not a DB seed -
 * is the source of default values: a key with no {@code system_config} row
 * resolves to its default here (Story 1 AC-4, Story 6).
 *
 * <p>Design note (DEV, spec §2): the SA "storage_quota.default.R1/R2/R3"
 * single row is stored as three separate NUMBER keys
 * ({@code storage_quota.default.can_bo/lanh_dao/quan_tri}, in bytes) so each
 * reuses the same per-key validate/audit machinery. Together with the extra
 * {@code ingest.max_file_size_bytes} key (per-file cap, from data-ingestion.md
 * Quyết định 3), the effective catalogue is 13 keys.
 *
 * <p>{@code ai_queue.concurrency}/{@code ai_queue.batch_size} were left "chưa
 * chốt" by SA; the defaults below (2 / 8) are conservative starting points
 * that an admin can raise once real GPU capacity is known.
 */
public final class SystemConfigDefaults {

    // ---- keys (referenced by consumer modules; keep in sync with the table) ----
    public static final String RETENTION_TRASH_RETENTION_DAYS = "retention.trash_retention_days";
    public static final String STORAGE_QUOTA_DEFAULT_CAN_BO = "storage_quota.default.can_bo";
    public static final String STORAGE_QUOTA_DEFAULT_LANH_DAO = "storage_quota.default.lanh_dao";
    public static final String STORAGE_QUOTA_DEFAULT_QUAN_TRI = "storage_quota.default.quan_tri";
    public static final String INGEST_MAX_FILES_PER_BATCH = "ingest.max_files_per_batch";
    public static final String INGEST_MAX_BATCH_SIZE_BYTES = "ingest.max_batch_size_bytes";
    public static final String INGEST_MAX_FILE_SIZE_BYTES = "ingest.max_file_size_bytes";
    public static final String OCR_STT_AUTO_CONFIRM_HOURS = "ocr_stt.auto_confirm_hours";
    /**
     * digitization-pipeline-02-ocr-stt-processing.md Task 10. Stored as an
     * integer percent (0-100), same representation already used by the
     * warehouse thresholds below - {@code ValueType.NUMBER} only carries a
     * {@code long} (see {@link SystemConfigValue#asLong()}), so a fractional
     * ratio like {@code 0.60} cannot be stored directly; consumers divide by
     * 100 to get the 0-1 ratio the spec writes (e.g. 60 -> 0.60).
     */
    public static final String OCR_STT_MIN_CONFIDENCE_OCR_PERCENT = "ocr_stt.min_confidence_ocr_percent";
    public static final String OCR_STT_MIN_CONFIDENCE_STT_PERCENT = "ocr_stt.min_confidence_stt_percent";
    public static final String OCR_STT_POOR_RATIO_THRESHOLD_PERCENT = "ocr_stt.poor_ratio_threshold_percent";
    public static final String OCR_STT_NO_SPEECH_PROB_THRESHOLD_PERCENT = "ocr_stt.no_speech_prob_threshold_percent";
    public static final String OCR_STT_MAX_RETRY = "ocr_stt.max_retry";
    /** Worker-side only (Epic A3, out of this backend's scope) - kept here so the admin catalogue is complete. */
    public static final String OCR_STT_SEGMENT_MAX_SECONDS = "ocr_stt.segment_max_seconds";
    /** digitization-review-01-ocr.md §2 - kill switch cho {@code AutoConfirmScheduler} (Quyết định 1 SA). */
    public static final String OCR_STT_AUTO_CONFIRM_ENABLED = "ocr_stt.auto_confirm_enabled";
    /** digitization-review-01-ocr.md §2 - ngưỡng "quá hạn xử lý" cho {@code ReviewStaleAlertJob} (Quyết định 2 SA), tính bằng giờ kể từ {@code first_edit_at}. */
    public static final String OCR_STT_REVIEW_STALE_HOURS = "ocr_stt.review_stale_hours";
    /**
     * digitization-review-01-ocr.md §2 - "[ĐX]" của DEV trả lời câu hỏi mở BA
     * (ngưỡng "sắp hết hạn" cho {@code effectiveStatus=upcoming_deadline},
     * Story 1 AC-4) - cấu hình được thay vì hard-code UI.
     */
    public static final String OCR_STT_REVIEW_UPCOMING_DEADLINE_HOURS = "ocr_stt.review_upcoming_deadline_hours";
    public static final String WAREHOUSE_STORAGE_WARNING_THRESHOLD_PERCENT =
            "warehouse.storage_warning_threshold_percent";
    public static final String WAREHOUSE_STORAGE_BLOCK_THRESHOLD_PERCENT =
            "warehouse.storage_block_threshold_percent";
    public static final String AI_QUEUE_CONCURRENCY = "ai_queue.concurrency";
    public static final String AI_QUEUE_BATCH_SIZE = "ai_queue.batch_size";
    public static final String BACKUP_SCHEDULE_CRON = "backup.schedule_cron";
    /**
     * delete-restore-trash-02-xoa-cung-xung-dot.md §4.1 (Quyết định 3,
     * backup-restore.md): kill switch for {@code TrashPurgeJob}'s mandatory
     * "today has a verified backup" gate. Sensitive for the same reason as
     * {@link #RETENTION_TRASH_RETENTION_DAYS}: disabling the gate lets an
     * irreversible hard delete run without a verified backup to fall back
     * on.
     */
    public static final String BACKUP_PURGE_GATE_ENABLED = "backup.purge_gate_enabled";
    /** chunked-file-upload.md Quyết định 4 - whole-file/chunked threshold, in bytes. */
    public static final String UPLOAD_CHUNK_THRESHOLD_BYTES = "upload.chunk_threshold_bytes";
    /** chunked-file-upload.md Quyết định 4 - size of one chunk, in bytes. */
    public static final String UPLOAD_CHUNK_SIZE_BYTES = "upload.chunk_size_bytes";
    /**
     * classification-notes-01-tags.md §1/§4.2 (Task 5) - gate for creating a
     * {@code topic} tag on the fly at assignment time instead of rejecting
     * an unknown label with 400.
     */
    public static final String CLASSIFICATION_ALLOW_ADHOC_TOPIC_TAG = "classification.allow_adhoc_topic_tag";
    /**
     * classification-notes-01-tags.md §4.6 step 3 (Task 9b) - retention
     * window (days) for processed {@code outbox_event} rows.
     */
    public static final String CLASSIFICATION_OUTBOX_RETENTION_DAYS = "classification.outbox_retention_days";
    /**
     * search-fulltext-01-tim-kiem-co-ban.md §3.1/Task 8: default/max {@code
     * size} for {@code POST /search} (FR-22) result pages. The hard ceiling
     * of 50 (QĐ 6 SA) is a code constant (see {@code SearchRequestValidator}),
     * not itself configurable - only the default page size is.
     */
    public static final String SEARCH_MAX_RESULTS_PER_PAGE = "search.max_results_per_page";
    /** search-fulltext-01-tim-kiem-co-ban.md §3.1/§4/Task 8: OpenSearch query timeout before failing the request (AC-10). */
    public static final String SEARCH_QUERY_TIMEOUT_MS = "search.query_timeout_ms";
    /** search-semantic T3: number of k-NN / lexical candidates per branch (1..200). */
    public static final String SEMANTIC_TOP_K = "semantic.top_k";
    /**
     * search-semantic T3 (Q-9): cosine threshold in [0,1] below which a vector-only candidate is
     * never returned (AC-3). Stored as a decimal STRING ("0.50") because NUMBER values are integers.
     */
    public static final String SEMANTIC_MIN_SCORE = "semantic.min_score";

    /** SPEC-export-reporting §2: reporting.* (6 keys, registry in code - no DB seed). */
    public static final String REPORTING_EXPORT_TTL_DAYS = "reporting.export_ttl_days";
    public static final String REPORTING_MAX_ROWS_PER_EXPORT = "reporting.max_rows_per_export";
    public static final String REPORTING_MAX_CONCURRENT_EXPORTS = "reporting.max_concurrent_exports";
    public static final String REPORTING_MAX_EXPORTS_PER_USER = "reporting.max_exports_per_user";
    public static final String REPORTING_QUERY_TIMEOUT_SECONDS = "reporting.query_timeout_seconds";
    public static final String REPORTING_MAX_BUCKETS = "reporting.max_buckets";

    private static final long GB = 1024L * 1024L * 1024L;
    private static final long MB = 1024L * 1024L;

    /** Insertion-ordered so the API returns parameters grouped by business area. */
    private static final Map<String, SystemConfigDef> DEFS = new LinkedHashMap<>();

    private static void add(SystemConfigDef def) {
        DEFS.put(def.key(), def);
    }

    static {
        // Retention (FR-26/27) - sensitive: wrong value = irreversible hard delete.
        add(new SystemConfigDef(RETENTION_TRASH_RETENTION_DAYS, ValueType.NUMBER, "365", true,
                "Số ngày giữ dữ liệu trong thùng rác trước khi xoá cứng", "ngày"));

        // Personal storage default quota per role (FR-14), stored in bytes.
        add(new SystemConfigDef(STORAGE_QUOTA_DEFAULT_CAN_BO, ValueType.NUMBER, Long.toString(20 * GB), false,
                "Hạn mức lưu trữ cá nhân mặc định - Cán bộ (R1)", "byte"));
        add(new SystemConfigDef(STORAGE_QUOTA_DEFAULT_LANH_DAO, ValueType.NUMBER, Long.toString(50 * GB), false,
                "Hạn mức lưu trữ cá nhân mặc định - Lãnh đạo (R2)", "byte"));
        add(new SystemConfigDef(STORAGE_QUOTA_DEFAULT_QUAN_TRI, ValueType.NUMBER, Long.toString(20 * GB), false,
                "Hạn mức lưu trữ cá nhân mặc định - Quản trị (R3)", "byte"));

        // Ingest limits (FR-11).
        add(new SystemConfigDef(INGEST_MAX_FILES_PER_BATCH, ValueType.NUMBER, "500", false,
                "Số tệp tối đa mỗi lượt tải theo thư mục", "tệp"));
        add(new SystemConfigDef(INGEST_MAX_BATCH_SIZE_BYTES, ValueType.NUMBER, Long.toString(5 * GB), false,
                "Tổng dung lượng tối đa mỗi lượt tải", "byte"));
        add(new SystemConfigDef(INGEST_MAX_FILE_SIZE_BYTES, ValueType.NUMBER, Long.toString(2 * GB), false,
                "Dung lượng tối đa của từng tệp", "byte"));

        // OCR/STT auto-confirm window (FR-18).
        add(new SystemConfigDef(OCR_STT_AUTO_CONFIRM_HOURS, ValueType.NUMBER, "24", false,
                "Thời hạn tự động xác nhận kết quả OCR/STT", "giờ"));

        // OCR/STT quality gate + retry (digitization-pipeline-02-ocr-stt-processing.md
        // Task 10) - "[ĐX]" giá trị khởi điểm của SA, có thể chỉnh sau benchmark
        // Epic A6 mà không cần sửa/deploy lại code.
        add(new SystemConfigDef(OCR_STT_MIN_CONFIDENCE_OCR_PERCENT, ValueType.NUMBER, "60", false,
                "Ngưỡng tin cậy tối thiểu cho từng đoạn OCR (dưới ngưỡng tính là confidence thấp)", "%"));
        add(new SystemConfigDef(OCR_STT_MIN_CONFIDENCE_STT_PERCENT, ValueType.NUMBER, "55", false,
                "Ngưỡng tin cậy tối thiểu cho từng đoạn STT (dưới ngưỡng tính là confidence thấp)", "%"));
        add(new SystemConfigDef(OCR_STT_POOR_RATIO_THRESHOLD_PERCENT, ValueType.NUMBER, "30", false,
                "Tỷ lệ đoạn confidence thấp tối đa trước khi đánh dấu cả lần chạy là 'poor'", "%"));
        add(new SystemConfigDef(OCR_STT_NO_SPEECH_PROB_THRESHOLD_PERCENT, ValueType.NUMBER, "80", false,
                "Ngưỡng no_speech_prob để nghi ngờ STT 'ảo giác' (tính vào confidence thấp)", "%"));
        add(new SystemConfigDef(OCR_STT_MAX_RETRY, ValueType.NUMBER, "3", false,
                "Số lần thử lại tối đa cho một job OCR/STT thất bại tạm thời", "lần"));
        add(new SystemConfigDef(OCR_STT_SEGMENT_MAX_SECONDS, ValueType.NUMBER, "30", false,
                "Độ dài tối đa (giây) của một đoạn STT trước khi worker cắt cưỡng bức", "giây"));

        // OCR/STT review (digitization-review-01-ocr.md §2, FR-18) - not
        // sensitive: wrong values only affect review UX/throughput, never
        // data safety (the underlying auto-confirm window itself,
        // ocr_stt.auto_confirm_hours above, already governs the deadline).
        add(new SystemConfigDef(OCR_STT_AUTO_CONFIRM_ENABLED, ValueType.BOOLEAN, "true", false,
                "Bật/tắt tự động xác nhận kết quả OCR/STT quá hạn duyệt (Quyết định 1 SA)", null));
        add(new SystemConfigDef(OCR_STT_REVIEW_STALE_HOURS, ValueType.NUMBER, "72", false,
                "Số giờ đang duyệt dở (in_review) trước khi cảnh báo 'quá hạn xử lý' (Quyết định 2 SA)", "giờ"));
        add(new SystemConfigDef(OCR_STT_REVIEW_UPCOMING_DEADLINE_HOURS, ValueType.NUMBER, "4", false,
                "Số giờ trước review_deadline_at để đánh dấu 'sắp hết hạn' trên hàng chờ duyệt", "giờ"));

        // Warehouse capacity thresholds (FR-13), percent.
        add(new SystemConfigDef(WAREHOUSE_STORAGE_WARNING_THRESHOLD_PERCENT, ValueType.NUMBER, "85", false,
                "Ngưỡng cảnh báo dung lượng kho", "%"));
        add(new SystemConfigDef(WAREHOUSE_STORAGE_BLOCK_THRESHOLD_PERCENT, ValueType.NUMBER, "95", false,
                "Ngưỡng chặn nạp mới theo dung lượng kho", "%"));

        // AI queue (FR-17/19) - DEV-proposed defaults, tunable per GPU capacity.
        add(new SystemConfigDef(AI_QUEUE_CONCURRENCY, ValueType.NUMBER, "2", false,
                "Số tác vụ AI (OCR/STT) xử lý đồng thời", "tác vụ"));
        add(new SystemConfigDef(AI_QUEUE_BATCH_SIZE, ValueType.NUMBER, "8", false,
                "Kích thước lô xử lý hàng đợi AI", "tác vụ"));

        // Backup schedule (FR-34) - sensitive: a bad cron expression can silently
        // stop backups, so a wrong value here has the same "unrecoverable data
        // loss" risk profile as retention and requires the two-step confirm.
        add(new SystemConfigDef(BACKUP_SCHEDULE_CRON, ValueType.STRING, "0 2 * * *", true,
                "Lịch sao lưu định kỳ (biểu thức cron)", null));
        add(new SystemConfigDef(BACKUP_PURGE_GATE_ENABLED, ValueType.BOOLEAN, "true", true,
                "Bắt buộc có bản sao lưu đã xác minh trong ngày trước khi cho phép job xóa cứng thùng rác chạy",
                null));

        // Chunked upload (chunked-file-upload.md Quyết định 4) - not sensitive: a
        // wrong value only affects performance/UX, never data safety.
        add(new SystemConfigDef(UPLOAD_CHUNK_THRESHOLD_BYTES, ValueType.NUMBER, Long.toString(20 * MB), false,
                "Ngưỡng dung lượng tệp để chuyển sang tải theo từng phần (chunk)", "byte"));
        add(new SystemConfigDef(UPLOAD_CHUNK_SIZE_BYTES, ValueType.NUMBER, Long.toString(8 * MB), false,
                "Kích thước mỗi phần (chunk) khi tải tệp lớn", "byte"));

        // Classification/tags (classification-notes-01-tags.md) - not sensitive:
        // wrong values only affect UX (extra ad-hoc topics) or a slower cleanup
        // sweep, never data safety.
        add(new SystemConfigDef(CLASSIFICATION_ALLOW_ADHOC_TOPIC_TAG, ValueType.BOOLEAN, "false", false,
                "Cho phép tạo nhãn chủ đề (topic) mới ngay khi gán vào tài liệu", null));
        add(new SystemConfigDef(CLASSIFICATION_OUTBOX_RETENTION_DAYS, ValueType.NUMBER, "30", false,
                "Số ngày giữ lại các sự kiện outbox đã xử lý trước khi dọn", "ngày"));

        // Full-text search (search-fulltext-01-tim-kiem-co-ban.md) - not sensitive:
        // wrong values only affect result-page UX/OpenSearch load, never data safety.
        add(new SystemConfigDef(SEARCH_MAX_RESULTS_PER_PAGE, ValueType.NUMBER, "20", false,
                "Số kết quả mặc định mỗi trang tìm kiếm toàn văn (tối đa cứng 50)", "kết quả"));
        add(new SystemConfigDef(SEARCH_QUERY_TIMEOUT_MS, ValueType.NUMBER, "1500", false,
                "Thời gian chờ tối đa cho một truy vấn OpenSearch trước khi báo lỗi", "ms"));

        // Semantic search (SPEC-search-semantic T3) - not sensitive: wrong values only change recall/precision.
        add(new SystemConfigDef(SEMANTIC_TOP_K, ValueType.NUMBER, "50", false,
                "Số ứng viên tối đa mỗi nhánh (từ khoá / ngữ nghĩa) trong tra cứu ngữ nghĩa (tối đa 200)", "kết quả"));
        add(new SystemConfigDef(SEMANTIC_MIN_SCORE, ValueType.STRING, "0.50", false,
                "Ngưỡng tương đồng cosine tối thiểu (0–1) để một đoạn chỉ khớp ngữ nghĩa được coi là đủ căn cứ", null));

        // Reporting/export (SPEC-export-reporting §2) - not sensitive: wrong values only affect
        // report availability/load, never data safety.
        add(new SystemConfigDef(REPORTING_EXPORT_TTL_DAYS, ValueType.NUMBER, "7", false,
                "Số ngày giữ tệp báo cáo đã xuất trước khi hết hạn", "ngày"));
        add(new SystemConfigDef(REPORTING_MAX_ROWS_PER_EXPORT, ValueType.NUMBER, "100000", false,
                "Số dòng tối đa của một báo cáo xuất tệp", "dòng"));
        add(new SystemConfigDef(REPORTING_MAX_CONCURRENT_EXPORTS, ValueType.NUMBER, "10", false,
                "Số job xuất báo cáo đang tạo đồng thời tối đa toàn hệ thống", "job"));
        add(new SystemConfigDef(REPORTING_MAX_EXPORTS_PER_USER, ValueType.NUMBER, "3", false,
                "Số job xuất báo cáo đang tạo đồng thời tối đa mỗi người", "job"));
        add(new SystemConfigDef(REPORTING_QUERY_TIMEOUT_SECONDS, ValueType.NUMBER, "15", false,
                "Thời gian chờ tối đa cho một truy vấn thống kê", "giây"));
        add(new SystemConfigDef(REPORTING_MAX_BUCKETS, ValueType.NUMBER, "400", false,
                "Số mốc thời gian tối đa của một báo cáo theo độ chi tiết đã chọn", "mốc"));
    }

    private SystemConfigDefaults() {
    }

    /** All definitions, in business-grouped insertion order. */
    public static Map<String, SystemConfigDef> all() {
        return DEFS;
    }

    /** The definition for {@code key}, or null if it is not a known parameter. */
    public static SystemConfigDef get(String key) {
        return DEFS.get(key);
    }

    public static boolean contains(String key) {
        return DEFS.containsKey(key);
    }
}
