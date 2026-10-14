package com.aismartstore.backend.reporting;

import java.util.Locale;

/**
 * AC-12: the 5 file-type groups of Bảng 2/3, derived from {@code document.mime_type} by MIME
 * prefix (constants in code, covered by {@code MimeGroupClassifierTest}).
 */
public enum MimeGroup {
    DOCUMENT("Văn bản/PDF"),
    IMAGE("Ảnh"),
    AUDIO("Âm thanh"),
    VIDEO("Video"),
    OTHER("Khác");

    private final String label;

    MimeGroup(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public static MimeGroup of(String mimeType) {
        if (mimeType == null) {
            return OTHER;
        }
        String m = mimeType.trim().toLowerCase(Locale.ROOT);
        int semicolon = m.indexOf(';');
        if (semicolon >= 0) {
            m = m.substring(0, semicolon).trim();
        }
        if (m.startsWith("image/")) {
            return IMAGE;
        }
        if (m.startsWith("audio/")) {
            return AUDIO;
        }
        if (m.startsWith("video/")) {
            return VIDEO;
        }
        if (m.equals("application/pdf")
                || m.startsWith("text/")
                || m.equals("application/msword")
                || m.startsWith("application/vnd.ms-")
                || m.startsWith("application/vnd.openxmlformats-")
                || m.startsWith("application/vnd.oasis.opendocument.")
                || m.equals("application/rtf")) {
            return DOCUMENT;
        }
        return OTHER;
    }
}
