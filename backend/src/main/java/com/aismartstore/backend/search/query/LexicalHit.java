package com.aismartstore.backend.search.query;

import java.util.UUID;

/**
 * search-fulltext-04-dong-bo-chi-muc.md §2/§3 - the internal cross-module
 * contract returned by {@link com.aismartstore.backend.search.SearchService
 * #lexicalSearch}, one raw (pre-ACL-double-check) OpenSearch segment hit for
 * {@code search-semantic}'s RRF fusion. Deliberately a different, much
 * flatter shape than {@code search}'s own internal {@code LexicalHit} (which
 * carries a document-collapsed group of inner hits plus highlight for the
 * {@code /search} HTTP response) - the two are unrelated types that happen to
 * share a name in different packages; this one is never used by the
 * {@code /search} read path.
 *
 * @param segmentId  the matching {@code segment} document's {@code segment_id} - a {@code content_segment}
 *                   UUID, or a synthetic {@code <documentId>:title}/{@code :note} id (SPEC-search-semantic),
 *                   hence a String
 * @param documentId the segment's {@code document_id}
 * @param bm25Score  the raw OpenSearch {@code _score} for this segment
 */
public record LexicalHit(String segmentId, UUID documentId, double bm25Score) {
}
