package com.aismartstore.backend.documents;

/**
 * {@code document.purge_state} (V30, delete-restore-trash-02-xoa-cung-xung-
 * dot.md §2.1) - the hard-delete state machine (Quyết định 5 SA): {@code
 * ACTIVE -> DELETED -> PURGING -> PURGED}. {@code ACTIVE} is the default for
 * every document that has never been soft-deleted; {@code DELETED} is set by
 * {@code TrashService#trash} and unset back to {@code ACTIVE} by {@code
 * TrashService#restore}; {@code PURGING}/{@code PURGED} are owned by {@code
 * TrashPurgeService}.
 */
public final class PurgeState {
    public static final String ACTIVE = "active";
    public static final String DELETED = "deleted";
    public static final String PURGING = "purging";
    public static final String PURGED = "purged";

    private PurgeState() {
    }
}
