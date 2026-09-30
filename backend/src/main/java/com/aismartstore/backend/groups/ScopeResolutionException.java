package com.aismartstore.backend.groups;

/**
 * Thrown by {@link BusinessDataScope} when the current user's group
 * membership cannot be determined (DB failure, missing token subject, ...).
 * Fail-closed (rbac-group-scope.md Story 4 AC-1/AC-2): {@code
 * com.aismartstore.backend.common.ApiExceptionHandler} maps this to {@code
 * 403}, never a bare {@code 500} and never an "empty specification" that
 * some ORMs would otherwise interpret as "match everything".
 */
public class ScopeResolutionException extends RuntimeException {

    public ScopeResolutionException(String message) {
        super(message);
    }

    public ScopeResolutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
