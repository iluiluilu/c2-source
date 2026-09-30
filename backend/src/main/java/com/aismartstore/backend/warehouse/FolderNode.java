package com.aismartstore.backend.warehouse;

import java.util.List;
import java.util.UUID;

/**
 * Nested folder-tree response shape, assembled in-memory by
 * {@link WarehouseService#getTree()} (and reused as-is by
 * {@code PersonalService.getTree}, which has no persisted depth column and
 * computes {@code depth} from recursion level instead). Additive field
 * (spec §3): existing consumers that only read {@code name}/{@code children}
 * are unaffected.
 */
public record FolderNode(UUID id, String name, int depth, List<FolderNode> children) {
}
