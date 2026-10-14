package com.aismartstore.backend.upload;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * backend-multi-instance Task 9: binds {@code app.upload.*}. {@code tempDir} blank = {@code java.io.tmpdir}
 * (previous behaviour); in a multi-instance deployment it points at the shared volume {@code upload-tmp}.
 */
@ConfigurationProperties(prefix = "app.upload")
public record UploadProperties(String tempDir) {

    /** Normalised absolute directory where chunk temp files live. */
    public Path resolvedTempDir() {
        String dir = (tempDir == null || tempDir.isBlank()) ? System.getProperty("java.io.tmpdir") : tempDir.trim();
        return Path.of(dir).toAbsolutePath().normalize();
    }
}
