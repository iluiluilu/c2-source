package com.aismartstore.backend.scanning;

import com.aismartstore.backend.common.ApiException;
import com.aismartstore.backend.common.ConflictException;
import com.aismartstore.backend.common.NotFoundException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * data-ingestion.md §4.3: the {@code allowed_file_format} table (Task 1)
 * replaces {@link MimeAllowlist} as the source of truth for format checks,
 * cached in Redis (key {@code ingest:allowlist}) - {@link MimeAllowlist}
 * itself stays only as the seed data reference (V10) and its magic-byte
 * table is not otherwise used at runtime anymore.
 *
 * <p>Read order: Redis hit -&gt; used as-is. Redis miss -&gt; DB, then
 * best-effort re-populate Redis. Redis error -&gt; DB directly (still
 * correct, just slower). Redis AND DB both failing -&gt; fail-closed
 * ({@link ServiceUnavailableException}, code {@code ALLOWLIST_UNAVAILABLE}):
 * an unreadable allowlist must never be treated as "allow everything".
 */
@Service
public class AllowlistService {

    private static final Logger log = LoggerFactory.getLogger(AllowlistService.class);

    private static final String CACHE_KEY = "ingest:allowlist";
    private static final Duration CACHE_TTL = Duration.ofHours(6);

    private final AllowedFileFormatRepository repository;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public AllowlistService(
            AllowedFileFormatRepository repository, StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.repository = repository;
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    /** One cached allowlist row - deliberately not the JPA entity (keeps the Redis payload stable/small). */
    public record AllowlistEntry(
            String mimeType, List<String> extensions, String category, List<Map<String, Object>> magicBytes) {
    }

    public record FormatCheck(boolean ok, String reason) {
        public static FormatCheck ofOk() {
            return new FormatCheck(true, null);
        }

        public static FormatCheck reject(String reason) {
            return new FormatCheck(false, reason);
        }
    }

    // ---- runtime check (ingest pipeline, §4.2 step 5) ------------------

    /** Validates declared MIME type against the allowlist, extension, and magic bytes - mirrors MimeAllowlist.checkFormat. */
    public FormatCheck checkFormat(File file, String originalName, String mimeType) {
        AllowlistEntry entry =
                loadEntries().stream().filter(e -> e.mimeType().equals(mimeType)).findFirst().orElse(null);
        if (entry == null) {
            return FormatCheck.reject("Định dạng tệp không được phép nạp vào kho (" + mimeType + ")");
        }

        String extension = extractExtension(originalName);
        if (!entry.extensions().contains(extension)) {
            return FormatCheck.reject(
                    "Đuôi tệp '." + extension + "' không khớp với định dạng khai báo (" + mimeType + ")");
        }

        List<Map<String, Object>> signatures = entry.magicBytes();
        if (signatures != null && !signatures.isEmpty()) {
            byte[] header = readHeader(file, 16);
            boolean matched = signatures.stream().anyMatch(m -> matches(header, m));
            if (!matched) {
                return FormatCheck.reject("Nội dung tệp không khớp với định dạng khai báo (." + extension
                        + "). Tệp có thể đã bị đổi đuôi nên bị từ chối.");
            }
        }

        return FormatCheck.ofOk();
    }

    /**
     * personal-to-warehouse.md §4 step 1: cheap pre-filter by stored name/MIME
     * only (no content check) - the authoritative check stays {@link #checkFormat}.
     */
    public boolean isAllowedByNameAndMime(String originalName, String mimeType) {
        String extension = extractExtension(originalName);
        return loadEntries().stream()
                .anyMatch(e -> e.mimeType().equals(mimeType) && e.extensions().contains(extension));
    }

    private List<AllowlistEntry> loadEntries() {
        try {
            String cached = redis.opsForValue().get(CACHE_KEY);
            if (cached != null) {
                return parseJson(cached);
            }
        } catch (RuntimeException e) {
            log.warn("Redis read failed for allowlist, falling back to DB: {}", e.toString());
        }

        try {
            List<AllowlistEntry> entries = repository.findAll().stream().map(AllowlistService::toEntry).toList();
            trySetCache(entries);
            return entries;
        } catch (RuntimeException e) {
            log.error("DB read failed for allowlist (Redis unavailable/miss too) - fail-closed: {}", e.toString());
            throw allowlistUnavailable();
        }
    }

    // ---- admin catalogue / writes (Story 5, Task 7) ---------------------

    /** GET /admin/allowed-file-formats: DB directly, not the runtime cache (admin view of true stored state). */
    public List<AllowedFileFormat> listAll() {
        return repository.findAll();
    }

    public AllowedFileFormat create(
            String sub,
            String username,
            String mimeType,
            List<String> extensions,
            String category,
            List<Map<String, Object>> magicBytes) {
        if (repository.existsById(mimeType)) {
            throw new ConflictException("Định dạng '" + mimeType + "' đã tồn tại");
        }
        AllowedFileFormat row = new AllowedFileFormat();
        row.setMimeType(mimeType);
        row.setExtensions(extensions.toArray(new String[0]));
        row.setCategory(category);
        row.setMagicBytes(magicBytes);
        OffsetDateTime now = OffsetDateTime.now();
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        row.setUpdatedBySub(sub);
        row.setUpdatedByUsername(username);
        repository.save(row);
        refreshCache();
        return row;
    }

    public void delete(String mimeType) {
        if (!repository.existsById(mimeType)) {
            throw new NotFoundException("Định dạng '" + mimeType + "' không tồn tại");
        }
        repository.deleteById(mimeType);
        refreshCache();
    }

    /** §4.3: overwrite the WHOLE cached catalogue (not a per-entry patch) so a removed format truly disappears. */
    private void refreshCache() {
        List<AllowlistEntry> entries = repository.findAll().stream().map(AllowlistService::toEntry).toList();
        trySetCache(entries);
    }

    // ---- internals --------------------------------------------------------

    private static AllowlistEntry toEntry(AllowedFileFormat row) {
        return new AllowlistEntry(
                row.getMimeType(), Arrays.asList(row.getExtensions()), row.getCategory(), row.getMagicBytes());
    }

    private void trySetCache(List<AllowlistEntry> entries) {
        try {
            redis.opsForValue().set(CACHE_KEY, objectMapper.writeValueAsString(entries), CACHE_TTL);
        } catch (Exception e) {
            log.warn("Failed to cache allowlist in Redis: {}", e.toString());
        }
    }

    private List<AllowlistEntry> parseJson(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<List<AllowlistEntry>>() {});
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to parse cached allowlist", e);
        }
    }

    private static String extractExtension(String originalName) {
        String[] parts = originalName.split("\\.", -1);
        return parts[parts.length - 1].toLowerCase(Locale.ROOT);
    }

    private static byte[] readHeader(File file, int maxLen) {
        byte[] buffer = new byte[maxLen];
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            int read = raf.read(buffer, 0, maxLen);
            if (read <= 0) {
                return new byte[0];
            }
            return read == maxLen ? buffer : Arrays.copyOf(buffer, read);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static boolean matches(byte[] header, Map<String, Object> magic) {
        int offset = ((Number) magic.get("offset")).intValue();
        byte[] expected = hexToBytes((String) magic.get("hex"));
        if (header.length < offset + expected.length) {
            return false;
        }
        for (int i = 0; i < expected.length; i++) {
            if (header[offset + i] != expected[i]) {
                return false;
            }
        }
        return true;
    }

    private static byte[] hexToBytes(String hex) {
        int len = hex.length();
        byte[] out = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            out[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4) + Character.digit(hex.charAt(i + 1), 16));
        }
        return out;
    }

    private static ApiException allowlistUnavailable() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", "ALLOWLIST_UNAVAILABLE");
        body.put(
                "message",
                "Không thể tải danh mục định dạng cho phép (Redis và cơ sở dữ liệu đều không khả dụng)");
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, body, "Allowlist unavailable");
    }
}
