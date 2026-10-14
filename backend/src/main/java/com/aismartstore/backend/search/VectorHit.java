package com.aismartstore.backend.search;

import java.util.UUID;

/** SPEC-search-semantic §2: one raw (pre-ACL-double-check) k-NN hit; {@code cosine} already converted by {@link KnnScore}. */
public record VectorHit(String segmentId, UUID documentId, double cosine) {
}
