package com.aismartstore.backend.search;

import java.util.List;

/**
 * One collapsed OpenSearch hit (a {@code document_id} group) plus its inner
 * hits, before ACL double-check/hydration. Internal pipeline shape between
 * {@code SearchService} and {@link SearchResultHydrator} - not an API DTO.
 */
public record LexicalHit(String documentId, double score, String docType, List<LexicalSegmentHit> segments) {

    /** One inner-hit segment: the raw OpenSearch source plus its (possibly absent) highlight fragment. */
    public record LexicalSegmentHit(SegmentDocument source, String highlight) {
    }
}
