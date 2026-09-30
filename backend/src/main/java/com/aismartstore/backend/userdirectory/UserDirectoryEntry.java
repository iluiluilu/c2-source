package com.aismartstore.backend.userdirectory;

/**
 * {@code GET /users/directory} response row (group-management-sharing-01-
 * group-lifecycle.md §3.2, Story 5) - deliberately minimal, no email/status/
 * quota, to avoid leaking administrative account data through a route R1/R2
 * can call (§4.7 step 3).
 */
public record UserDirectoryEntry(String sub, String username, String displayName, String role) {
}
