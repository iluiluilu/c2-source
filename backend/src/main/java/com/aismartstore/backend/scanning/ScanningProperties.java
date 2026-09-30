package com.aismartstore.backend.scanning;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Binds the {@code app.scanning.*} keys in application.yml.
 *
 * <p>{@code socketTimeoutMs} (data-ingestion.md §4.2 step 7, Task 5b) used to
 * be hard-coded (120s) in {@link ClamdService}; it is now a parameter so it
 * can be tuned against {@code ingest.max_file_size_bytes} without a code
 * change. Default kept at the old hard-coded value - raising it further
 * requires measuring real scan time for a file at the size cap on the actual
 * deployment hardware (see the spec's Task 5b / §7 risk note; this default is
 * not that measurement).
 */
@ConfigurationProperties(prefix = "app.scanning")
public record ScanningProperties(String clamdHost, int clamdPort, @DefaultValue("120000") int socketTimeoutMs) {
}
