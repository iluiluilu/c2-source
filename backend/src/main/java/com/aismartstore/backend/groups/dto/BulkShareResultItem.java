package com.aismartstore.backend.groups.dto;

import java.util.UUID;

/**
 * One item of the {@code POST /documents/shares} response
 * (group-management-sharing-02-document-sharing.md §3.1). {@code status} is
 * {@code "shared"|"already_shared"|"rejected"}; {@code reason} is only set
 * for {@code "rejected"} ({@code "not_visible"|"group_limit_exceeded"}).
 */
public record BulkShareResultItem(UUID documentId, String status, String reason) {

    public static BulkShareResultItem shared(UUID documentId) {
        return new BulkShareResultItem(documentId, "shared", null);
    }

    public static BulkShareResultItem alreadyShared(UUID documentId) {
        return new BulkShareResultItem(documentId, "already_shared", null);
    }

    public static BulkShareResultItem rejected(UUID documentId, String reason) {
        return new BulkShareResultItem(documentId, "rejected", reason);
    }
}
