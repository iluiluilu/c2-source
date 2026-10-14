package com.aismartstore.backend.search;

import com.aismartstore.backend.digitization.results.ContentSegment;
import com.aismartstore.backend.search.dto.SearchFilters;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.opensearch.client.json.JsonData;
import org.opensearch.client.opensearch._types.FieldValue;
import org.opensearch.client.opensearch._types.SortOptions;
import org.opensearch.client.opensearch._types.SortOrder;
import org.opensearch.client.opensearch._types.query_dsl.Operator;
import org.opensearch.client.opensearch._types.query_dsl.Query;
import org.opensearch.client.opensearch.core.MsearchRequest;
import org.opensearch.client.opensearch.core.SearchRequest;
import org.opensearch.client.opensearch.core.msearch.MultisearchBody;
import org.opensearch.client.opensearch.core.msearch.RequestItem;
import org.opensearch.client.opensearch.core.search.BoundaryScanner;
import org.opensearch.client.opensearch.core.search.BuiltinHighlighterType;

/**
 * search-fulltext-01-tim-kiem-co-ban.md §4 step 1.3/Task 5 + search-fulltext-
 * 02-tim-kiem-nang-cao.md §4 step 1/2 - builds every OpenSearch request the
 * search feature issues: the main lexical/metadata-only query (§1 below), and
 * the up-to-5-query {@code _msearch} used for the AC-12 "0 kết quả" diagnostics
 * (§2). Bộ lọc nâng cao (đây) và tinh chỉnh vị trí/highlight (spec {@code
 * -03}) mở rộng request/pipeline này, không thay cấu trúc ở đây.
 */
public final class SearchQueryBuilder {

    /** search-fulltext.md Quyết định 4: tối đa 3 đoạn trích mỗi tài liệu ở danh sách kết quả. */
    public static final int INNER_HITS_SIZE = 3;

    public static final String INNER_HITS_NAME = "top_segments";

    /**
     * search-fulltext-03-dinh-vi-ket-qua.md §4 step 2 (AC-5): score first, then
     * {@code page_no}/{@code time_start_ms} ascending so multiple excerpts in
     * the same document read top-to-bottom/earliest-first when tied on score.
     * A segment missing both (a {@code note}/{@code title} match) has no
     * value for either field - OpenSearch's default {@code missing} handling
     * places it last regardless of sort order, so it always sorts after any
     * {@code ocr}/{@code stt}/{@code sed} match with the same score, never
     * assuming a page/time it doesn't have.
     */
    private static final List<SortOptions> INNER_HITS_SORT = List.of(
            SortOptions.of(s -> s.score(sc -> sc.order(SortOrder.Desc))),
            SortOptions.of(s -> s.field(f -> f.field("page_no").order(SortOrder.Asc))),
            SortOptions.of(s -> s.field(f -> f.field("time_start_ms").order(SortOrder.Asc))));

    /**
     * search-fulltext-01-tim-kiem-co-ban.md §3.2: {@code totalApprox} is an
     * estimate of matched <b>documents</b>, not matched segments - {@code
     * collapse}'s own {@code hits.total} counts segments (one per matching
     * segment before collapsing), so a separate {@code cardinality}
     * aggregation on {@code document_id} is required. Only present on
     * segment-index queries - a metadata-only query against {@code document}
     * counts documents directly via {@code hits.total} instead.
     */
    public static final String TOTAL_DOCUMENTS_AGGREGATION = "total_documents";

    /**
     * search-fulltext.md Quyết định 4: ranking cơ bản ({@code text^3},
     * {@code text.shingle^2}, {@code text.folded^0.5}) - boost theo nguồn
     * ({@code title^4}/{@code note^1.5}) và {@code match_phrase} bổ sung
     * thuộc phần tinh chỉnh vị trí/highlight, chốt ở spec {@code -03}.
     */
    private static final List<String> QUERY_FIELDS = List.of("text^3", "text.folded^0.5", "text.shingle^2");

    /**
     * search-fulltext.md Quyết định 3: bật {@code PHRASE|AND|OR|NOT|
     * PRECEDENCE|WHITESPACE}; {@code FUZZY}/{@code WILDCARD}/{@code PREFIX}/
     * {@code REGEX} bị tắt bằng cách không liệt kê (client chỉ hỗ trợ liệt
     * kê cờ bật, không có cờ "tắt" riêng). AC-11: ký tự ngoài cú pháp này
     * (`*`, `~`, `field:value`) không bị `simple_query_string` từ chối - nó
     * tự bỏ qua/coi như văn bản thường, không cần code thêm.
     */
    private static final String QUERY_FLAGS = "PHRASE|AND|OR|NOT|PRECEDENCE|WHITESPACE";

    /** End-of-day time suffix applied to {@code filters.uploadedTo()} so the whole day is inclusive (§4 step 1.2). */
    private static final String END_OF_DAY_SUFFIX = "T23:59:59.999Z";

    private SearchQueryBuilder() {
    }

    /**
     * §4 step 1: builds the single main request for this attempt - a lexical
     * {@code simple_query_string} search on {@code segment} when {@code
     * query} is non-blank (collapsed by {@code document_id}, with {@code
     * inner_hits}), or a plain metadata-only search directly on {@code
     * document} (no collapse needed - one row already is one document,
     * §4 step 1.3) when it is blank.
     *
     * @param from OpenSearch {@code from} for this specific attempt - the
     *             caller ({@code SearchService}) is responsible for the
     *             over-fetch math (§4 step 1.5, QĐ 5 SA) and any retry loop,
     *             this method only ever builds one request.
     * @param size OpenSearch {@code size} for this specific attempt (already
     *             over-fetched by the caller, not the page size the client asked for)
     */
    public static SearchRequest buildLexicalQuery(
            String query,
            SearchFilters filters,
            ScopeContext ctx,
            List<String> effectiveGroupIds,
            String sort,
            int from,
            int size) {
        List<Query> filterClauses = buildFilterClauses(filters, ctx, effectiveGroupIds, null);

        if (query == null || query.isBlank()) {
            // §4 step 1.3: no keyword at all - go straight to `document`, no collapse.
            Query scoped = Query.of(q -> q.bool(b -> b.filter(filterClauses)));
            return new SearchRequest.Builder()
                    .index(SearchIndexNames.DOCUMENT_ALIAS)
                    .query(scoped)
                    .from(from)
                    .size(size)
                    .sort(s -> s.field(f -> f.field("uploaded_at").order(SortOrder.Desc)))
                    .build();
        }

        Query scoped = Query.of(q -> q.bool(b -> b.must(simpleQueryString(query)).filter(filterClauses)));

        SearchRequest.Builder builder = new SearchRequest.Builder()
                .index(SearchIndexNames.SEGMENT_ALIAS)
                .query(scoped)
                .from(from)
                .size(size)
                .aggregations(TOTAL_DOCUMENTS_AGGREGATION, a -> a.cardinality(c -> c.field("document_id")))
                .collapse(c -> c.field("document_id")
                        .innerHits(ih -> ih.name(INNER_HITS_NAME)
                                .size(INNER_HITS_SIZE)
                                .sort(INNER_HITS_SORT)
                                .highlight(h -> h.type(t -> t.builtin(BuiltinHighlighterType.Unified))
                                        .fields("text", hf -> hf.fragmentSize(200)
                                                .numberOfFragments(1)
                                                .boundaryScanner(BoundaryScanner.Sentence)
                                                .noMatchSize(0)))));

        if ("recent".equals(sort)) {
            builder.sort(s -> s.field(f -> f.field("uploaded_at").order(SortOrder.Desc)));
        }

        return builder.build();
    }

    /**
     * search-fulltext-04-dong-bo-chi-muc.md §4.1 step 2 - the query behind
     * {@code SearchService.lexicalSearch()}: always the {@code segment} index
     * (a keyword match is mandatory - {@code search-semantic}'s caller never
     * invokes this with a blank query), <b>no</b> {@code collapse}/{@code
     * inner_hits}/highlight (RRF fuses by {@code segment_id} directly, one row
     * per matching segment) and a small {@code _source} filter instead of the
     * full segment body - this call never needs {@code text}/highlight, only
     * the two ids to build a {@link com.aismartstore.backend.search.query.LexicalHit}.
     * Default sort (relevance, {@code _score} desc) is exactly what {@code
     * topK} BM25 hits for RRF need - never {@code sort=recent}.
     */
    public static SearchRequest buildLexicalSearchOnlyQuery(
            String query, SearchFilters filters, ScopeContext ctx, List<String> effectiveGroupIds, int topK) {
        List<Query> filterClauses = buildFilterClauses(filters, ctx, effectiveGroupIds, null);
        Query scoped = Query.of(q -> q.bool(b -> b.must(simpleQueryString(query))
                .filter(filterClauses)
                // sed (sound-event) segments are never semantic passages: drop them before top-k, not only in the hydrator
                .mustNot(term("content_source", ContentSegment.SOURCE_SED))));

        return new SearchRequest.Builder()
                .index(SearchIndexNames.SEGMENT_ALIAS)
                .query(scoped)
                .from(0)
                .size(topK)
                .source(s -> s.filter(f -> f.includes("segment_id", "document_id")))
                .build();
    }

    /**
     * §4 step 2 (AC-12): one {@code _msearch} request carrying one {@code
     * size=0} count query per entry of {@code activeGroups}, each with that
     * one group's filter clauses removed (everything else - keyword, scope,
     * remaining groups - unchanged). Caller maps {@code responses().get(i)}
     * back to {@code activeGroups.get(i)} by position.
     */
    public static MsearchRequest buildRefineDiagnosticsRequest(
            String indexAlias,
            String query,
            SearchFilters filters,
            ScopeContext ctx,
            List<String> effectiveGroupIds,
            List<FilterGroup> activeGroups) {
        boolean segmentIndex = SearchIndexNames.SEGMENT_ALIAS.equals(indexAlias);
        List<RequestItem> items = new ArrayList<>();

        for (FilterGroup exclude : activeGroups) {
            List<Query> filterClauses = buildFilterClauses(filters, ctx, effectiveGroupIds, exclude);
            Query boolQuery = (query == null || query.isBlank())
                    ? Query.of(q -> q.bool(b -> b.filter(filterClauses)))
                    : Query.of(q -> q.bool(b -> b.must(simpleQueryString(query)).filter(filterClauses)));

            MultisearchBody.Builder bodyBuilder = new MultisearchBody.Builder().query(boolQuery).size(0);
            if (segmentIndex) {
                bodyBuilder.aggregations(TOTAL_DOCUMENTS_AGGREGATION, a -> a.cardinality(c -> c.field("document_id")));
            } else {
                bodyBuilder.trackTotalHits(t -> t.enabled(true));
            }
            MultisearchBody body = bodyBuilder.build();

            items.add(RequestItem.of(r -> r.header(h -> h.index(indexAlias)).body(body)));
        }

        return new MsearchRequest.Builder().searches(items).build();
    }

    private static Query simpleQueryString(String query) {
        return Query.of(q -> q.simpleQueryString(sq -> sq
                .query(query)
                .fields(QUERY_FIELDS)
                .flags(f -> f.multiple(QUERY_FLAGS))
                .defaultOperator(Operator.And)));
    }

    /**
     * §4 step 1.2: {@code ScopeFilterBuilder.build(ctx)} plus, for every
     * filter group that has a value, its own clause - except {@code exclude}
     * (§4 step 2's diagnostics), which is skipped entirely as if the caller
     * never supplied it.
     *
     * @param effectiveGroupIds already {@code filters.groupIds() ∩
     *                          ctx.groupIds()} (AC-4) - the caller ({@code
     *                          SearchService}) computes this once per request,
     *                          not here, since it is also needed for the
     *                          audit entry.
     */
    public static List<Query> buildFilterClauses(
            SearchFilters filters, ScopeContext ctx, List<String> effectiveGroupIds, FilterGroup exclude) {
        List<Query> clauses = new ArrayList<>();
        clauses.add(ScopeFilterBuilder.build(ctx));

        if (filters == null) {
            return clauses;
        }

        if (exclude != FilterGroup.DOC_TYPE && filters.docType() != null && !filters.docType().isBlank()) {
            clauses.add(term("doc_type", filters.docType()));
        }

        if (exclude != FilterGroup.TOPIC_TAGS && filters.topicTags() != null && !filters.topicTags().isEmpty()) {
            if (SearchFilters.TOPIC_TAGS_MODE_OR.equals(filters.resolvedTopicTagsMode())) {
                clauses.add(terms("topic_tags", filters.topicTags()));
            } else {
                // AND: one `term` per tag, all inside the same `bool.filter[]` - AND is
                // automatic there, no need for a nested bool.
                for (String tag : filters.topicTags()) {
                    clauses.add(term("topic_tags", tag));
                }
            }
        }

        if (exclude != FilterGroup.UPLOADED_AT && (filters.uploadedFrom() != null || filters.uploadedTo() != null)) {
            clauses.add(uploadedAtRange(filters.uploadedFrom(), filters.uploadedTo()));
        }

        if (exclude != FilterGroup.GROUP_IDS && effectiveGroupIds != null && !effectiveGroupIds.isEmpty()) {
            clauses.add(terms("scope_group_ids", effectiveGroupIds));
        }

        if (exclude != FilterGroup.FOLDER_MEDIA_CONFIRM) {
            if (filters.folderId() != null) {
                clauses.add(terms("folder_path_ids", List.of(filters.folderId().toString())));
            }
            if (filters.mediaType() != null && !filters.mediaType().isEmpty()) {
                clauses.add(terms("media_type", filters.mediaType()));
            }
            if (filters.confirmSource() != null && !filters.confirmSource().isBlank()) {
                clauses.add(term("confirm_source", filters.confirmSource()));
            }
        }

        return clauses;
    }

    private static Query uploadedAtRange(LocalDate from, LocalDate to) {
        return Query.of(q -> q.range(r -> {
            r.field("uploaded_at");
            if (from != null) {
                r.gte(JsonData.of(from.toString()));
            }
            if (to != null) {
                r.lte(JsonData.of(toEndOfDayInstant(to)));
            }
            return r;
        }));
    }

    private static String toEndOfDayInstant(LocalDate date) {
        return date + END_OF_DAY_SUFFIX;
    }

    private static Query term(String field, String value) {
        return Query.of(q -> q.term(t -> t.field(field).value(FieldValue.of(value))));
    }

    private static Query terms(String field, List<String> values) {
        List<FieldValue> fieldValues = values.stream().map(FieldValue::of).toList();
        return Query.of(q -> q.terms(t -> t.field(field).terms(tf -> tf.value(fieldValues))));
    }
}
