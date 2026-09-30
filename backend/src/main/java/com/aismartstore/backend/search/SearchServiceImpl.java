package com.aismartstore.backend.search;

import com.aismartstore.backend.audit.AuditRequestContext;
import com.aismartstore.backend.audit.AuditService;
import com.aismartstore.backend.auth.KeycloakUser;
import com.aismartstore.backend.common.ApiException;
import com.aismartstore.backend.groups.GroupMemberRepository;
import com.aismartstore.backend.search.dto.RefineSuggestion;
import com.aismartstore.backend.search.dto.SearchFilters;
import com.aismartstore.backend.search.dto.SearchHit;
import com.aismartstore.backend.search.dto.SearchRequest;
import com.aismartstore.backend.search.dto.SearchResponse;
import com.aismartstore.backend.systemconfig.SystemConfigDefaults;
import com.aismartstore.backend.systemconfig.SystemConfigService;
import com.aismartstore.backend.tags.TagKinds;
import com.aismartstore.backend.tags.TagRepository;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import jakarta.annotation.PreDestroy;
import org.opensearch.client.json.JsonData;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.aggregations.Aggregate;
import org.opensearch.client.opensearch.core.MsearchRequest;
import org.opensearch.client.opensearch.core.MsearchResponse;
import org.opensearch.client.opensearch.core.search.Hit;
import org.opensearch.client.opensearch.core.search.InnerHitsResult;
import org.opensearch.client.opensearch.core.msearch.MultiSearchResponseItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * search-fulltext-01-tim-kiem-co-ban.md §4/Task 5-8 + search-fulltext-02-
 * tim-kiem-nang-cao.md §4/Task 1-6 + search-fulltext-04-dong-bo-chi-muc.md
 * §3/§4.1: orchestrates {@code POST /search} (FR-22) - scope resolution,
 * request/filter validation, the OpenSearch call (with a hard timeout and a
 * degraded-mode/SQL-fallback path), over-fetch + ACL double-check, the AC-12
 * "0 kết quả" diagnostics, and the audit trail - plus {@link #lexicalSearch},
 * the {@link SearchService} contract {@code search-semantic} calls into.
 * {@code SearchController} is a thin HTTP adapter over the former.
 */
@Service
public class SearchServiceImpl implements SearchService {

    private static final Logger log = LoggerFactory.getLogger(SearchServiceImpl.class);

    /** search-fulltext-01-tim-kiem-co-ban.md §3.1 Quyết định 6 SA - hard ceiling, not itself configurable. */
    static final int HARD_MAX_SIZE = 50;
    /** search-fulltext-01-tim-kiem-co-ban.md §3.1 Quyết định 6 SA. */
    static final int MAX_FROM_PLUS_SIZE = 10_000;
    /** search-fulltext-01-tim-kiem-co-ban.md §4 step 1.5 (QĐ 5 SA). */
    private static final double OVER_FETCH_FACTOR = 1.5;
    private static final int OVER_FETCH_PADDING = 10;
    /** search-fulltext-01-tim-kiem-co-ban.md §4 step 1.6 (QĐ 5 SA): backfill loop for docs dropped by the ACL double-check. */
    private static final int MAX_HYDRATE_ROUNDS = 2;

    /** search-fulltext-02-tim-kiem-nang-cao.md §3.3 AC-13. */
    static final int MAX_QUERY_LENGTH = 256;

    static final int MAX_QUERY_TOKENS = 32;
    static final int MAX_FILTER_VALUES = 20;
    private static final Set<String> VALID_CONFIRM_SOURCES = Set.of("manual", "auto");

    /** search-fulltext-04-dong-bo-chi-muc.md Task 7: query truncated to this length before it ever reaches audit metadata. */
    private static final int AUDIT_QUERY_MAX_LENGTH = 200;

    private final OpenSearchClient openSearchClient;
    private final GroupMemberRepository groupMemberRepository;
    private final SearchResultHydrator hydrator;
    private final SystemConfigService systemConfigService;
    private final AuditService auditService;
    private final AuditRequestContext auditRequestContext;
    private final TagRepository tagRepository;
    private final MetadataFallbackSearchService metadataFallbackSearchService;
    private final ExecutorService searchExecutor = Executors.newCachedThreadPool();

    public SearchServiceImpl(
            OpenSearchClient openSearchClient,
            GroupMemberRepository groupMemberRepository,
            SearchResultHydrator hydrator,
            SystemConfigService systemConfigService,
            AuditService auditService,
            AuditRequestContext auditRequestContext,
            TagRepository tagRepository,
            MetadataFallbackSearchService metadataFallbackSearchService) {
        this.openSearchClient = openSearchClient;
        this.groupMemberRepository = groupMemberRepository;
        this.hydrator = hydrator;
        this.systemConfigService = systemConfigService;
        this.auditService = auditService;
        this.auditRequestContext = auditRequestContext;
        this.tagRepository = tagRepository;
        this.metadataFallbackSearchService = metadataFallbackSearchService;
    }

    @PreDestroy
    void shutdown() {
        searchExecutor.shutdownNow();
    }

    public SearchResponse search(SearchRequest request, KeycloakUser user) {
        // §4 step 1.1 (AC-5): resolve scope BEFORE touching OpenSearch at all - a
        // ScopeResolutionException here propagates straight to ApiExceptionHandler (403).
        ScopeContext ctx = ScopeContext.resolve(user.sub(), groupMemberRepository);

        String query = request.query() == null ? "" : request.query().trim();
        SearchFilters filters = request.filters();
        boolean hasQuery = !query.isEmpty();
        boolean hasFilters = filters != null && !filters.isEmpty();

        // AC-7 story -01, relaxed by -02: at least one of query/filters must be present.
        if (!hasQuery && !hasFilters) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Vui lòng nhập từ khóa hoặc chọn ít nhất một bộ lọc");
        }
        validateQuery(query);
        validateFilters(filters);

        GroupIdIntersection groupIds =
                GroupIdIntersection.resolve(filters == null ? null : filters.groupIds(), ctx);

        String sort = normalizeSort(request.sort());
        int page = request.page() == null ? 0 : request.page();
        if (page < 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "page phải >= 0");
        }
        int size = resolveSize(request.size());
        int from = page * size;
        if (from + size > MAX_FROM_PLUS_SIZE) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "Trang kết quả yêu cầu vượt quá giới hạn (" + MAX_FROM_PLUS_SIZE
                            + "), vui lòng thu hẹp bộ lọc hoặc tìm kiếm cụ thể hơn");
        }

        long timeoutMs = systemConfigService.getLong(SystemConfigDefaults.SEARCH_QUERY_TIMEOUT_MS);

        SearchResponse response;
        try {
            response = runSearch(query, filters, groupIds.effective(), ctx, sort, from, size, timeoutMs, hasQuery);
        } catch (OpenSearchUnavailableException e) {
            if (!hasQuery) {
                // §4 step 3 (AC-14 story -02, AC-7 story -04): filters-only search survives via Postgres.
                log.warn("OpenSearch unavailable for a filters-only POST /search call - falling back to Postgres", e);
                response = metadataFallbackSearchService.search(filters, ctx, groupIds.effective(), page, size);
            } else {
                // -01 §4 step 2: a keyword search has no SQL fallback - degrade instead.
                log.warn("OpenSearch unavailable for a POST /search call - degrading (results=[])", e);
                response =
                        new SearchResponse(List.of(), 0, page, size, SearchResponse.DEGRADED_OPENSEARCH_UNAVAILABLE, List.of());
            }
        }

        recordAudit(user, query, filters, sort, response, groupIds.dropped());
        return response;
    }

    /**
     * search-fulltext-04-dong-bo-chi-muc.md §3/§4.1: the {@link SearchService}
     * contract for {@code search-semantic}. See the interface Javadoc for the
     * full contract - this method never resolves scope itself, never runs the
     * ACL double-check, and never falls back to SQL.
     */
    @Override
    public List<com.aismartstore.backend.search.query.LexicalHit> lexicalSearch(
            String query, SearchFilters filters, ScopeContext scopeCtx, int topK) {
        if (query == null || query.isBlank()) {
            return List.of();
        }

        GroupIdIntersection groupIds = GroupIdIntersection.resolve(filters == null ? null : filters.groupIds(), scopeCtx);
        org.opensearch.client.opensearch.core.SearchRequest osRequest = SearchQueryBuilder.buildLexicalSearchOnlyQuery(
                query, filters, scopeCtx, groupIds.effective(), topK);

        org.opensearch.client.opensearch.core.SearchResponse<SegmentDocument> osResponse;
        try {
            osResponse = openSearchClient.search(osRequest, SegmentDocument.class);
        } catch (IOException e) {
            // Same rationale as the /search path (§4 step 2, isConnectivityFailure): a
            // transport-level IOException means OpenSearch itself is unreachable.
            throw new OpenSearchUnavailableException(e);
        }

        List<com.aismartstore.backend.search.query.LexicalHit> hits = new ArrayList<>();
        for (Hit<SegmentDocument> hit : osResponse.hits().hits()) {
            SegmentDocument source = hit.source();
            if (source == null || source.getSegmentId() == null || source.getDocumentId() == null) {
                continue;
            }
            double score = hit.score() != null ? hit.score() : 0.0;
            hits.add(new com.aismartstore.backend.search.query.LexicalHit(
                    UUID.fromString(source.getSegmentId()), UUID.fromString(source.getDocumentId()), score));
        }
        return hits;
    }

    private SearchResponse runSearch(
            String query,
            SearchFilters filters,
            List<String> effectiveGroupIds,
            ScopeContext ctx,
            String sort,
            int from,
            int size,
            long timeoutMs,
            boolean hasQuery) {
        int fetchSize = (int) Math.ceil(size * OVER_FETCH_FACTOR) + OVER_FETCH_PADDING;
        int attemptFrom = from;
        long totalApprox = 0;
        Set<UUID> seenDocumentIds = new LinkedHashSet<>();
        List<SearchHit> collected = new ArrayList<>();

        for (int round = 0; round < MAX_HYDRATE_ROUNDS && collected.size() < size; round++) {
            org.opensearch.client.opensearch.core.SearchRequest osRequest = SearchQueryBuilder.buildLexicalQuery(
                    query, filters, ctx, effectiveGroupIds, sort, attemptFrom, fetchSize);
            org.opensearch.client.opensearch.core.SearchResponse<SegmentDocument> osResponse =
                    executeWithTimeout(osRequest, timeoutMs);

            totalApprox = Math.max(totalApprox, extractTotalApprox(osResponse));
            List<LexicalHit> lexicalHits = toLexicalHits(osResponse, hasQuery);
            if (lexicalHits.isEmpty()) {
                break;
            }

            for (SearchHit hit : hydrator.hydrate(lexicalHits, ctx)) {
                if (seenDocumentIds.add(hit.documentId())) {
                    collected.add(hit);
                }
            }

            if (lexicalHits.size() < fetchSize) {
                // OpenSearch itself ran out of matches - no point asking for a further window.
                break;
            }
            attemptFrom += fetchSize;
        }

        List<SearchHit> pageResults = collected.size() > size ? collected.subList(0, size) : collected;

        List<RefineSuggestion> refineSuggestions = List.of();
        if (totalApprox == 0) {
            // §4 step 2 (AC-12): only meaningful if there is at least one active filter group to drop.
            refineSuggestions = diagnoseZeroResults(query, filters, effectiveGroupIds, ctx, hasQuery, timeoutMs);
        }

        return new SearchResponse(pageResults, totalApprox, from / size, size, SearchResponse.DEGRADED_NONE, refineSuggestions);
    }

    /**
     * §4 step 2 (AC-12): best-effort - any failure here (including OpenSearch
     * being unavailable, already handled by the caller for the main query)
     * simply yields no suggestions, it never fails the whole search.
     */
    private List<RefineSuggestion> diagnoseZeroResults(
            String query, SearchFilters filters, List<String> effectiveGroupIds, ScopeContext ctx, boolean hasQuery, long timeoutMs) {
        List<FilterGroup> activeGroups = FilterGroup.activeGroupsOf(filters, effectiveGroupIds);
        if (activeGroups.isEmpty()) {
            return List.of();
        }

        String indexAlias = hasQuery ? SearchIndexNames.SEGMENT_ALIAS : SearchIndexNames.DOCUMENT_ALIAS;
        MsearchRequest msearchRequest = SearchQueryBuilder.buildRefineDiagnosticsRequest(
                indexAlias, hasQuery ? query : null, filters, ctx, effectiveGroupIds, activeGroups);

        MsearchResponse<SegmentDocument> msearchResponse;
        try {
            msearchResponse = executeMsearchWithTimeout(msearchRequest, timeoutMs);
        } catch (RuntimeException e) {
            log.warn("0-result refine-suggestion diagnostics failed - returning no suggestions", e);
            return List.of();
        }

        List<MultiSearchResponseItem<SegmentDocument>> responses = msearchResponse.responses();
        List<RefineSuggestion> suggestions = new ArrayList<>();
        for (int i = 0; i < activeGroups.size() && i < responses.size(); i++) {
            MultiSearchResponseItem<SegmentDocument> item = responses.get(i);
            if (item.isFailure()) {
                continue;
            }
            long estimatedCount = extractTotalApprox(item.result());
            if (estimatedCount > 0) {
                suggestions.add(new RefineSuggestion(activeGroups.get(i).label(), estimatedCount));
            }
        }
        suggestions.sort(Comparator.comparingLong(RefineSuggestion::estimatedCount).reversed());
        return suggestions;
    }

    private MsearchResponse<SegmentDocument> executeMsearchWithTimeout(MsearchRequest request, long timeoutMs) {
        Callable<MsearchResponse<SegmentDocument>> task = () -> openSearchClient.msearch(request, SegmentDocument.class);
        Future<MsearchResponse<SegmentDocument>> future = searchExecutor.submit(task);
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new IllegalStateException("Refine-suggestion diagnostics timed out", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Refine-suggestion diagnostics failed", e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Refine-suggestion diagnostics interrupted", e);
        }
    }

    private org.opensearch.client.opensearch.core.SearchResponse<SegmentDocument> executeWithTimeout(
            org.opensearch.client.opensearch.core.SearchRequest request, long timeoutMs) {
        Callable<org.opensearch.client.opensearch.core.SearchResponse<SegmentDocument>> task =
                () -> openSearchClient.search(request, SegmentDocument.class);
        Future<org.opensearch.client.opensearch.core.SearchResponse<SegmentDocument>> future = searchExecutor.submit(task);
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            // §4 step 1.4 (AC-10): a slow query is a clear error, never a partial result.
            throw new ApiException(
                    HttpStatus.GATEWAY_TIMEOUT,
                    "Tìm kiếm quá thời gian cho phép, vui lòng thu hẹp bộ lọc hoặc thử lại sau");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof SocketTimeoutException) {
                // search-fulltext-02-tim-kiem-nang-cao.md Task 6 (rủi ro mục 7): a single
                // request's own socket read timing out is NOT "OpenSearch is down" - it must
                // never silently trigger the SQL fallback, only ever a clear per-request error.
                throw new ApiException(
                        HttpStatus.GATEWAY_TIMEOUT,
                        "Tìm kiếm quá thời gian cho phép, vui lòng thu hẹp bộ lọc hoặc thử lại sau");
            }
            if (isConnectivityFailure(cause)) {
                throw new OpenSearchUnavailableException(cause);
            }
            log.error("POST /search: OpenSearch call failed", cause);
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Không thể thực hiện tìm kiếm lúc này");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Tìm kiếm bị gián đoạn");
        }
    }

    /**
     * §4 step 2: connection-level failures (OpenSearch down/unreachable) degrade; a single
     * request's own {@link SocketTimeoutException} is handled by the caller before this is
     * ever consulted (Task 6) - anything else real (bad request, cluster error) is a real error.
     */
    private static boolean isConnectivityFailure(Throwable cause) {
        return cause instanceof IOException;
    }

    private long extractTotalApprox(org.opensearch.client.opensearch.core.SearchResponse<SegmentDocument> response) {
        if (response.aggregations() != null) {
            Aggregate aggregate = response.aggregations().get(SearchQueryBuilder.TOTAL_DOCUMENTS_AGGREGATION);
            if (aggregate != null && aggregate.isCardinality()) {
                return aggregate.cardinality().value();
            }
        }
        // §4 step 1.3: the metadata-only query against `document` has no cardinality aggregation
        // at all - one hit already is one document, so `hits.total` is the exact count.
        return response.hits().total() != null ? response.hits().total().value() : 0;
    }

    private List<LexicalHit> toLexicalHits(
            org.opensearch.client.opensearch.core.SearchResponse<SegmentDocument> response, boolean hasQuery) {
        List<LexicalHit> hits = new ArrayList<>();
        for (Hit<SegmentDocument> hit : response.hits().hits()) {
            SegmentDocument top = hit.source();
            if (top == null || top.getDocumentId() == null) {
                continue;
            }

            List<LexicalHit.LexicalSegmentHit> segments = new ArrayList<>();
            if (hasQuery) {
                InnerHitsResult innerHits =
                        hit.innerHits() != null ? hit.innerHits().get(SearchQueryBuilder.INNER_HITS_NAME) : null;
                if (innerHits != null) {
                    for (Hit<JsonData> innerHit : innerHits.hits().hits()) {
                        if (innerHit.source() == null) {
                            continue;
                        }
                        SegmentDocument segment = innerHit.source().to(SegmentDocument.class);
                        segments.add(new LexicalHit.LexicalSegmentHit(segment, firstHighlightFragment(innerHit)));
                    }
                }
                if (segments.isEmpty()) {
                    // Defensive - collapse/inner_hits should always include at least the top match itself.
                    segments.add(new LexicalHit.LexicalSegmentHit(top, firstHighlightFragment(hit)));
                }
            }
            // §4 step 1.3: a metadata-only hit against `document` has no segment/highlight at all
            // (no `simple_query_string`, no collapse) - `segments` stays empty, matching the SQL
            // fallback's own `results[].segments = []` contract.

            double score = hit.score() != null ? hit.score() : 0.0;
            hits.add(new LexicalHit(top.getDocumentId(), score, top.getDocType(), segments));
        }
        return hits;
    }

    private static String firstHighlightFragment(Hit<?> hit) {
        if (hit.highlight() == null) {
            return null;
        }
        List<String> fragments = hit.highlight().get("text");
        return fragments == null || fragments.isEmpty() ? null : fragments.get(0);
    }

    private static String normalizeSort(String sort) {
        return "recent".equals(sort) ? "recent" : "relevance";
    }

    private int resolveSize(Integer requested) {
        if (requested == null) {
            long configured = systemConfigService.getLong(SystemConfigDefaults.SEARCH_MAX_RESULTS_PER_PAGE);
            return (int) Math.min(configured, HARD_MAX_SIZE);
        }
        if (requested <= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "size phải là số nguyên dương");
        }
        if (requested > HARD_MAX_SIZE) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST, "size vượt quá giới hạn tối đa (" + HARD_MAX_SIZE + ")");
        }
        return requested;
    }

    /** §3.3 AC-13: query length/token-count limits, checked before any OpenSearch call. */
    private void validateQuery(String query) {
        if (query.length() > MAX_QUERY_LENGTH) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "query: tối đa " + MAX_QUERY_LENGTH + " ký tự");
        }
        if (!query.isEmpty() && query.split("\\s+").length > MAX_QUERY_TOKENS) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "query: tối đa " + MAX_QUERY_TOKENS + " từ");
        }
    }

    /** §3.3 AC-13/AC-1/AC-6: per-filter max size + the 2 closed lists (docType/confirmSource). */
    private void validateFilters(SearchFilters filters) {
        if (filters == null) {
            return;
        }
        validateMaxSize("topicTags", filters.topicTags());
        validateMaxSize("groupIds", filters.groupIds());
        validateMaxSize("mediaType", filters.mediaType());

        if (filters.docType() != null
                && !filters.docType().isBlank()
                && tagRepository.findByKindAndLabelIgnoreCase(TagKinds.DOC_TYPE, filters.docType()).isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "docType không thuộc danh mục đóng");
        }
        if (filters.confirmSource() != null
                && !filters.confirmSource().isBlank()
                && !VALID_CONFIRM_SOURCES.contains(filters.confirmSource())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "confirmSource không thuộc danh mục đóng");
        }
    }

    private static void validateMaxSize(String filterName, List<?> values) {
        if (values != null && values.size() > MAX_FILTER_VALUES) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST, filterName + ": tối đa " + MAX_FILTER_VALUES + " giá trị");
        }
    }

    /**
     * search-fulltext-04-dong-bo-chi-muc.md Task 7: {@code search.executed}
     * audit entry, restricted to the {@code AuditService.ALLOWED_METADATA_KEYS}
     * allowlist ({@code query, filters, resultCount, sort, droppedGroupIds}).
     * {@code query} is truncated to {@link #AUDIT_QUERY_MAX_LENGTH} chars
     * <b>here</b>, at metadata-construction time - never relying on the
     * allowlist mechanism itself (which only drops disallowed keys, it never
     * truncates a value) to bound its size.
     */
    private void recordAudit(
            KeycloakUser user,
            String query,
            SearchFilters filters,
            String sort,
            SearchResponse response,
            List<String> droppedGroupIds) {
        try {
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("query", truncateForAudit(query));
            if (filters != null) {
                metadata.put("filters", filters);
            }
            metadata.put("resultCount", response.results().size());
            metadata.put("sort", sort);
            if (droppedGroupIds != null && !droppedGroupIds.isEmpty()) {
                // search-fulltext-02-tim-kiem-nang-cao.md Task 3/AC-4: audited, never surfaced as an
                // error to the caller.
                metadata.put("droppedGroupIds", droppedGroupIds);
            }

            auditService.recordAsync(new AuditService.AuditEntry(
                    user.preferredUsername(),
                    user.sub(),
                    "search.executed",
                    "search",
                    null,
                    SearchResponse.DEGRADED_NONE.equals(response.degradedMode()) ? "success" : "failure",
                    auditRequestContext.method(),
                    auditRequestContext.path(),
                    200,
                    auditRequestContext.ip(),
                    metadata));
        } catch (RuntimeException e) {
            log.warn("Failed to record a search.executed audit entry", e);
        }
    }

    private static String truncateForAudit(String query) {
        if (query == null) {
            return null;
        }
        return query.length() > AUDIT_QUERY_MAX_LENGTH ? query.substring(0, AUDIT_QUERY_MAX_LENGTH) : query;
    }
}
