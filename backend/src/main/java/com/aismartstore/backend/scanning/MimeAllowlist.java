package com.aismartstore.backend.scanning;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * FR-11/FR-12 format validation: an allowlist of the SRS's accepted
 * categories (text/PDF/Office, image, audio, video) with two consistency
 * checks - extension vs declared MIME type, and leading magic bytes. Java
 * port of the NestJS backend's scanning/mime-allowlist.ts.
 *
 * Hand-rolled magic-byte table rather than a library: only covers container
 * signatures, deep structural validation (e.g. parsing the PDF xref) is a
 * documented follow-up, as is content sniffing for plain text.
 */
public final class MimeAllowlist {

    private MimeAllowlist() {
    }

    public record FormatCheck(boolean ok, String reason) {
        public static FormatCheck ofOk() {
            return new FormatCheck(true, null);
        }

        public static FormatCheck reject(String reason) {
            return new FormatCheck(false, reason);
        }
    }

    private record Magic(int offset, byte[] bytes) {
        static Magic ascii(String text) {
            return new Magic(0, text.getBytes(StandardCharsets.US_ASCII));
        }

        static Magic ascii(int offset, String text) {
            return new Magic(offset, text.getBytes(StandardCharsets.US_ASCII));
        }

        static Magic bytes(int... unsignedBytes) {
            byte[] b = new byte[unsignedBytes.length];
            for (int i = 0; i < unsignedBytes.length; i++) {
                b[i] = (byte) unsignedBytes[i];
            }
            return new Magic(0, b);
        }
    }

    /** MIME type -> accepted file extensions (lowercase, no dot). */
    private static final Map<String, List<String>> ALLOWED = Map.ofEntries(
            // Text & documents
            Map.entry("application/pdf", List.of("pdf")),
            Map.entry("text/plain", List.of("txt", "text", "log", "md")),
            Map.entry("text/csv", List.of("csv")),
            Map.entry("application/msword", List.of("doc")),
            Map.entry(
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    List.of("docx")),
            Map.entry("application/vnd.ms-excel", List.of("xls")),
            Map.entry(
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", List.of("xlsx")),
            Map.entry("application/vnd.ms-powerpoint", List.of("ppt")),
            Map.entry(
                    "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                    List.of("pptx")),
            // Images
            Map.entry("image/png", List.of("png")),
            Map.entry("image/jpeg", List.of("jpg", "jpeg")),
            Map.entry("image/gif", List.of("gif")),
            Map.entry("image/webp", List.of("webp")),
            Map.entry("image/tiff", List.of("tif", "tiff")),
            // Audio
            Map.entry("audio/mpeg", List.of("mp3")),
            Map.entry("audio/mp4", List.of("m4a")),
            Map.entry("audio/wav", List.of("wav")),
            Map.entry("audio/x-wav", List.of("wav")),
            Map.entry("audio/ogg", List.of("ogg", "oga")),
            Map.entry("audio/aac", List.of("aac")),
            // Video
            Map.entry("video/mp4", List.of("mp4")),
            Map.entry("video/mpeg", List.of("mpeg", "mpg")),
            Map.entry("video/quicktime", List.of("mov")),
            Map.entry("video/x-matroska", List.of("mkv")),
            Map.entry("video/webm", List.of("webm")));

    /** Leading-byte signatures per MIME type; any match accepts the file. */
    private static final Map<String, List<Magic>> MAGIC = Map.ofEntries(
            Map.entry("application/pdf", List.of(Magic.ascii("%PDF"))),
            // OOXML (docx/xlsx/pptx) are ZIP containers; legacy Office is OLE2.
            Map.entry(
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    List.of(Magic.ascii("PK"))),
            Map.entry(
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    List.of(Magic.ascii("PK"))),
            Map.entry(
                    "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                    List.of(Magic.ascii("PK"))),
            Map.entry("application/msword", List.of(Magic.bytes(0xD0, 0xCF, 0x11, 0xE0))),
            Map.entry("application/vnd.ms-excel", List.of(Magic.bytes(0xD0, 0xCF, 0x11, 0xE0))),
            Map.entry("application/vnd.ms-powerpoint", List.of(Magic.bytes(0xD0, 0xCF, 0x11, 0xE0))),
            Map.entry("image/png", List.of(Magic.bytes(0x89, 0x50, 0x4E, 0x47))),
            Map.entry("image/jpeg", List.of(Magic.bytes(0xFF, 0xD8, 0xFF))),
            Map.entry("image/gif", List.of(Magic.ascii("GIF8"))),
            Map.entry("image/webp", List.of(Magic.ascii("RIFF"))),
            Map.entry(
                    "image/tiff",
                    List.of(Magic.bytes(0x49, 0x49, 0x2A, 0x00), Magic.bytes(0x4D, 0x4D, 0x00, 0x2A))),
            Map.entry(
                    "audio/mpeg",
                    List.of(
                            Magic.ascii("ID3"),
                            Magic.bytes(0xFF, 0xFB),
                            Magic.bytes(0xFF, 0xF3),
                            Magic.bytes(0xFF, 0xF2))),
            Map.entry("audio/mp4", List.of(Magic.ascii(4, "ftyp"))),
            Map.entry("audio/wav", List.of(Magic.ascii("RIFF"))),
            Map.entry("audio/x-wav", List.of(Magic.ascii("RIFF"))),
            Map.entry("audio/ogg", List.of(Magic.ascii("OggS"))),
            Map.entry("audio/aac", List.of(Magic.bytes(0xFF, 0xF1), Magic.bytes(0xFF, 0xF9))),
            Map.entry("video/mp4", List.of(Magic.ascii(4, "ftyp"))),
            Map.entry(
                    "video/quicktime", List.of(Magic.ascii(4, "ftyp"), Magic.ascii(4, "moov"))),
            Map.entry("video/x-matroska", List.of(Magic.bytes(0x1A, 0x45, 0xDF, 0xA3))),
            Map.entry("video/webm", List.of(Magic.bytes(0x1A, 0x45, 0xDF, 0xA3)))
            // text/plain, text/csv, video/mpeg: no reliable signature - extension +
            // MIME agreement only.
            );

    /** For the web upload form's `accept` attribute and docs. */
    public static final Set<String> ALLOWED_MIME_TYPES = ALLOWED.keySet();

    /** Validates declared MIME type against the allowlist, extension, and magic bytes. */
    public static FormatCheck checkFormat(File file, String originalName, String mimeType) {
        List<String> extensions = ALLOWED.get(mimeType);
        if (extensions == null) {
            return FormatCheck.reject("File type '" + mimeType + "' is not accepted");
        }

        String extension = extractExtension(originalName);
        if (!extensions.contains(extension)) {
            return FormatCheck.reject(
                    "Extension '." + extension + "' does not match declared type '" + mimeType + "'");
        }

        List<Magic> signatures = MAGIC.get(mimeType);
        if (signatures != null) {
            byte[] header = readHeader(file, 16);
            boolean matched = signatures.stream().anyMatch(m -> matches(header, m));
            if (!matched) {
                return FormatCheck.reject(
                        "File content does not match declared type '" + mimeType + "'");
            }
        }

        return FormatCheck.ofOk();
    }

    /** Mirrors the TS `originalName.split('.').pop()` semantics exactly. */
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

    private static boolean matches(byte[] header, Magic magic) {
        byte[] expected = magic.bytes();
        if (header.length < magic.offset() + expected.length) {
            return false;
        }
        for (int i = 0; i < expected.length; i++) {
            if (header[magic.offset() + i] != expected[i]) {
                return false;
            }
        }
        return true;
    }
}
