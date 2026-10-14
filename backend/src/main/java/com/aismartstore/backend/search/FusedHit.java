package com.aismartstore.backend.search;

import java.util.UUID;

/** SPEC-search-semantic §2: one candidate after RRF fusion; {@code cosine} is {@code null} when only the lexical branch found it. */
public record FusedHit(String segmentId, UUID documentId, Double cosine, boolean matchedLexical, double rrf) {
}
