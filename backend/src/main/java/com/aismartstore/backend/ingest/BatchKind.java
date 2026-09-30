package com.aismartstore.backend.ingest;

/**
 * {@code upload_batch.kind} (personal-storage-quota.md §2): distinguishes a
 * warehouse ingest batch (data-ingestion.md, the original/default meaning of
 * this table) from a personal-storage batch (personal-storage-quota.md,
 * which reuses this same table instead of a separate one - see that spec's
 * §1 for the rationale).
 */
public enum BatchKind {
    WAREHOUSE,
    PERSONAL
}
