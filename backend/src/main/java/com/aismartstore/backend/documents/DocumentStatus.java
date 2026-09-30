package com.aismartstore.backend.documents;

/** Mirrors the NestJS backend's Document status union ('pending'|'clean'|'infected'|'rejected'). */
public final class DocumentStatus {
    public static final String PENDING = "pending";
    public static final String CLEAN = "clean";
    public static final String INFECTED = "infected";
    public static final String REJECTED = "rejected";
    /**
     * delete-restore-trash-02-xoa-cung-xung-dot.md §2.2 (Quyết định 5 SA):
     * hard-deleted "bia mộ" row - deliberately never {@code 'clean'} so every
     * pre-existing {@code status = 'clean'} query (capacity, folder-empty
     * check, the ingest dedup lookup) excludes it without needing to change.
     */
    public static final String PURGED = "purged";

    private DocumentStatus() {
    }
}
