package com.aismartstore.backend.displayname;

import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;

/** Body of {@code POST /ingest/name-check} and {@code POST /personal/name-check} (max 1000 files, checked in the controller). */
public record NameCheckRequest(UUID folderId, @NotNull List<File> files) {

    public static final int MAX_FILES = 1000;

    public record File(String relativePath, String originalName) {
    }

    /** {@code [{taken}]} element, same order as the request. */
    public record Result(boolean taken) {
    }
}
