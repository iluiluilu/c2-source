package com.aismartstore.backend.displayname;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aismartstore.backend.common.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class DisplayNameValidatorTest {

    private static JsonNode vectors;

    @BeforeAll
    static void load() throws Exception {
        try (InputStream in = DisplayNameValidatorTest.class.getResourceAsStream("/display-name-vectors.json")) {
            vectors = new ObjectMapper().readTree(in);
        }
    }

    private static String expand(JsonNode n) {
        if (n.isTextual()) {
            return n.asText();
        }
        return n.get("rep").asText().repeat(n.get("n").asInt()) + n.get("tail").asText();
    }

    @Test
    void splitVectors() {
        for (JsonNode v : vectors.get("split")) {
            DisplayNameValidator.Split s = DisplayNameValidator.splitExtension(v.get("name").asText());
            assertThat(s.base()).as(v.toString()).isEqualTo(v.get("base").asText());
            assertThat(s.ext()).as(v.toString()).isEqualTo(v.get("ext").asText());
        }
    }

    @Test
    void suffixVectors() {
        for (JsonNode v : vectors.get("suffix")) {
            String out = DisplayNameValidator.withSuffix(
                    expand(v.get("original")), v.get("ts").asText(), v.get("rand").asText());
            assertThat(out).isEqualTo(expand(v.get("expected")));
            assertThat(DisplayNameValidator.cpLength(out)).isLessThanOrEqualTo(512);
        }
    }

    @Test
    void validateVectors() {
        for (JsonNode v : vectors.get("validate")) {
            String original = expand(v.get("original"));
            String display = expand(v.get("display"));
            String reason = DisplayNameValidator.checkReason(DisplayNameValidator.nfc(original), display);
            String expected = v.get("reason").isNull() ? null : v.get("reason").asText();
            assertThat(reason).as(v.toString()).isEqualTo(expected);
        }
    }

    @Test
    void generatedSuffixNameIsAlwaysValid() {
        String original = "Báo cáo tháng 9.docx";
        String generated = DisplayNameValidator.generateSuffixName(original);
        assertThat(DisplayNameValidator.checkReason(original, generated)).isNull();
        assertThat(generated).isNotEqualTo(DisplayNameValidator.generateSuffixName(original));
    }

    @Test
    void originalNameOver512CodePointsIsRejected() {
        String tooLong = "😀".repeat(513);
        assertThatThrownBy(() -> DisplayNameValidator.validate(tooLong, tooLong))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((java.util.Map<?, ?>) ((ApiException) e).getBody()).get("reason"))
                        .isEqualTo("ORIGINAL_NAME_TOO_LONG"));
        // 512 emoji = 1024 UTF-16 units but exactly 512 code points -> allowed.
        String ok = "😀".repeat(512);
        assertThat(DisplayNameValidator.validate(ok, ok)).isEqualTo(ok);
    }

    @Test
    void relativePathCapAndNfc() {
        assertThat(DisplayNameValidator.normalizeRelativePath("Báo/x")).isEqualTo("Báo/x");
        assertThat(DisplayNameValidator.normalizeRelativePath(null)).isEmpty();
        assertThatThrownBy(() -> DisplayNameValidator.normalizeRelativePath("a".repeat(1025)))
                .isInstanceOf(ApiException.class);
    }
}
