package com.aismartstore.backend.displayname;

import com.aismartstore.backend.common.ApiException;
import java.security.SecureRandom;
import java.text.Normalizer;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;

/**
 * SPEC-folder-upload-display-name BE-2 (QD4): validates a client-supplied {@code display_name}
 * and holds the rules shared (via {@code display-name-vectors.json}) with the frontend module
 * {@code web/src/lib/displayName.ts}: NFC, extension split, 512 code point cut, suffix format.
 * Lengths are counted in Unicode code points, never UTF-16 units.
 */
public final class DisplayNameValidator {

    public static final int MAX_NAME_CODE_POINTS = 512;
    public static final int MAX_PATH_CODE_POINTS = 1024;
    public static final int MAX_EXTENSION_CODE_POINTS = 16;
    public static final String CODE = "INVALID_DISPLAY_NAME";

    private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final DateTimeFormatter TS_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");
    private static final Pattern SUFFIX = Pattern.compile("_(\\d{17})-([a-z0-9]{6})$");
    private static final String ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private DisplayNameValidator() {
    }

    /** Result of {@link #splitExtension}: {@code base + ext == name}; {@code ext} includes the dot, or is "". */
    public record Split(String base, String ext) {
    }

    public static String nfc(String s) {
        return s == null ? null : Normalizer.normalize(s, Normalizer.Form.NFC);
    }

    public static int cpLength(String s) {
        return s.codePointCount(0, s.length());
    }

    /**
     * Extension = from the LAST dot, when that dot is not the first/last char and the part after it
     * is at most 16 code points (so ".env" and "a." have no extension, "a.tar.gz" -> ".gz").
     */
    public static Split splitExtension(String name) {
        int dot = name.lastIndexOf('.');
        if (dot <= 0 || dot == name.length() - 1) {
            return new Split(name, "");
        }
        String ext = name.substring(dot);
        if (cpLength(ext) - 1 > MAX_EXTENSION_CODE_POINTS) {
            return new Split(name, "");
        }
        return new Split(name.substring(0, dot), ext);
    }

    /** Cuts {@code s} to at most {@code maxCodePoints} code points, never inside a surrogate pair. */
    public static String cutCodePoints(String s, int maxCodePoints) {
        if (cpLength(s) <= maxCodePoints) {
            return s;
        }
        return s.substring(0, s.offsetByCodePoints(0, maxCodePoints));
    }

    /** {@code <base>_<timestamp17>-<rand6><ext>}, base cut so the whole name is at most 512 code points. */
    public static String withSuffix(String originalName, String timestamp17, String rand6) {
        Split split = splitExtension(nfc(originalName));
        String suffix = "_" + timestamp17 + "-" + rand6;
        int room = MAX_NAME_CODE_POINTS - cpLength(suffix) - cpLength(split.ext());
        return cutCodePoints(split.base(), Math.max(room, 0)) + suffix + split.ext();
    }

    /** Suffix name with the current time (Asia/Ho_Chi_Minh, ms) and a random 6-char tail - used on restore. */
    public static String generateSuffixName(String originalName) {
        StringBuilder rand = new StringBuilder(6);
        for (int i = 0; i < 6; i++) {
            rand.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return withSuffix(originalName, LocalDateTime.now(ZONE).format(TS_FORMAT), rand.toString());
    }

    /** AC-28: {@code original_name} must fit in 512 code points; checked before any chunk/file is accepted. */
    public static void validateOriginalName(String originalName) {
        if (originalName == null || originalName.isEmpty()) {
            throw invalid("EMPTY");
        }
        if (cpLength(nfc(originalName)) > MAX_NAME_CODE_POINTS) {
            throw invalid("ORIGINAL_NAME_TOO_LONG");
        }
    }

    /** Returns the NFC-normalized relative path (and enforces the 1024 code point cap). */
    public static String normalizeRelativePath(String relativePath) {
        String p = relativePath == null ? "" : nfc(relativePath);
        if (cpLength(p) > MAX_PATH_CODE_POINTS) {
            throw invalid("TOO_LONG");
        }
        return p;
    }

    /**
     * Full check of a {@code displayName} against its {@code originalName}; throws 422
     * {@code INVALID_DISPLAY_NAME{reason}}. Returns the value (already NFC by contract).
     */
    public static String validate(String originalName, String displayName) {
        validateOriginalName(originalName);
        String reason = checkReason(nfc(originalName), displayName);
        if (reason != null) {
            throw invalid(reason);
        }
        return displayName;
    }

    /** null when valid, otherwise the 422 {@code reason}. {@code nfcOriginal} must already be NFC. */
    public static String checkReason(String nfcOriginal, String displayName) {
        if (displayName == null || displayName.isEmpty()) {
            return "EMPTY";
        }
        if (!Normalizer.isNormalized(displayName, Normalizer.Form.NFC)) {
            return "NOT_NFC";
        }
        if (cpLength(displayName) > MAX_NAME_CODE_POINTS) {
            return "TOO_LONG";
        }
        if (hasForbiddenChar(displayName)) {
            return "FORBIDDEN_CHAR";
        }
        String trimmed = displayName.strip();
        if (trimmed.isEmpty() || ".".equals(displayName) || "..".equals(displayName)) {
            return "BLANK_OR_DOT";
        }
        if (displayName.equals(nfcOriginal)) {
            return null;
        }
        return suffixMatches(nfcOriginal, displayName) ? null : "SUFFIX_MISMATCH";
    }

    private static boolean suffixMatches(String nfcOriginal, String displayName) {
        Split split = splitExtension(nfcOriginal);
        String rest = displayName;
        if (!split.ext().isEmpty()) {
            if (!rest.endsWith(split.ext())) {
                return false;
            }
            rest = rest.substring(0, rest.length() - split.ext().length());
        }
        Matcher m = SUFFIX.matcher(rest);
        if (!m.find()) {
            return false;
        }
        try {
            LocalDateTime.parse(m.group(1), TS_FORMAT);
        } catch (DateTimeParseException e) {
            return false;
        }
        String prefix = rest.substring(0, m.start());
        if (prefix.equals(split.base())) {
            return true;
        }
        // truncated base: only allowed when the whole name is exactly 512 code points.
        return split.base().startsWith(prefix) && cpLength(displayName) == MAX_NAME_CODE_POINTS;
    }

    static boolean hasForbiddenChar(String s) {
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            if (cp == '/' || cp == '\\'
                    || Character.getType(cp) == Character.CONTROL
                    || cp == 0x2028 || cp == 0x2029
                    || (cp >= 0x202A && cp <= 0x202E)
                    || (cp >= 0x2066 && cp <= 0x2069)) {
                return true;
            }
        }
        return false;
    }

    public static ApiException invalid(String reason) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", CODE);
        body.put("reason", reason);
        body.put("message", "Tên hiển thị không hợp lệ (" + reason + ")");
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, body, "Invalid display name: " + reason);
    }

    public static ApiException conflict() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", "DISPLAY_NAME_CONFLICT");
        body.put("message", "Tên hiển thị đã tồn tại trong thư mục này");
        return new ApiException(HttpStatus.CONFLICT, body, "Display name conflict");
    }
}
