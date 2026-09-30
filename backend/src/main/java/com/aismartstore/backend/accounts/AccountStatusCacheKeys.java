package com.aismartstore.backend.accounts;

/**
 * Shared Redis key convention for the cached account status (spec §4.3 step
 * 3 / §4.5): {@link AccountsService} evicts on a status change,
 * {@link AccountStatusFilter} reads/populates on every request - both must
 * agree on the exact key shape.
 */
public final class AccountStatusCacheKeys {

    private static final String PREFIX = "account:status:";

    private AccountStatusCacheKeys() {
    }

    public static String keyFor(String keycloakUserId) {
        return PREFIX + keycloakUserId;
    }
}
