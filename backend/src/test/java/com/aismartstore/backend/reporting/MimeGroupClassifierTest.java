package com.aismartstore.backend.reporting;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** AC-12: the 5 file-type groups by MIME prefix (constants in code). */
class MimeGroupClassifierTest {

    @ParameterizedTest
    @CsvSource({
        "application/pdf,DOCUMENT",
        "text/plain,DOCUMENT",
        "text/csv,DOCUMENT",
        "application/msword,DOCUMENT",
        "application/vnd.ms-excel,DOCUMENT",
        "application/vnd.ms-powerpoint,DOCUMENT",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document,DOCUMENT",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet,DOCUMENT",
        "application/vnd.oasis.opendocument.text,DOCUMENT",
        "application/rtf,DOCUMENT",
        "image/png,IMAGE",
        "image/jpeg,IMAGE",
        "audio/mpeg,AUDIO",
        "audio/wav,AUDIO",
        "video/mp4,VIDEO",
        "video/x-matroska,VIDEO",
        "application/zip,OTHER",
        "application/octet-stream,OTHER",
        "application/vnd.android.package-archive,OTHER",
        "application/vnd.msword-fake,OTHER",
        "model/gltf+json,OTHER"
    })
    void classifiesByMimePrefix(String mime, MimeGroup expected) {
        assertThat(MimeGroup.of(mime)).isEqualTo(expected);
    }

    @Test
    void isCaseInsensitiveIgnoresParametersAndTreatsMissingAsOther() {
        assertThat(MimeGroup.of("IMAGE/PNG")).isEqualTo(MimeGroup.IMAGE);
        assertThat(MimeGroup.of("text/plain; charset=utf-8")).isEqualTo(MimeGroup.DOCUMENT);
        assertThat(MimeGroup.of(null)).isEqualTo(MimeGroup.OTHER);
        assertThat(MimeGroup.of("")).isEqualTo(MimeGroup.OTHER);
        assertThat(MimeGroup.of("weird")).isEqualTo(MimeGroup.OTHER);
    }

    @Test
    void thereAreExactlyFiveGroups() {
        assertThat(MimeGroup.values()).hasSize(5);
    }
}
