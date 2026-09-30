package com.aismartstore.backend.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.aismartstore.backend.audit.AuditRequestContext;
import com.aismartstore.backend.audit.AuditService;
import com.aismartstore.backend.auth.KeycloakUser;
import com.aismartstore.backend.common.ApiException;
import com.aismartstore.backend.groups.GroupMemberRepository;
import com.aismartstore.backend.groups.ScopeResolutionException;
import com.aismartstore.backend.search.dto.SearchFilters;
import com.aismartstore.backend.search.dto.SearchHit;
import com.aismartstore.backend.search.dto.SearchRequest;
import com.aismartstore.backend.search.dto.SearchResponse;
import com.aismartstore.backend.systemconfig.SystemConfigDefaults;
import com.aismartstore.backend.systemconfig.SystemConfigService;
import com.aismartstore.backend.tags.Tag;
import com.aismartstore.backend.tags.TagKinds;
import com.aismartstore.backend.tags.TagRepository;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.aggregations.Aggregate;
import org.opensearch.client.opensearch.core.MsearchResponse;
import org.opensearch.client.opensearch.core.SearchResponse.Builder;
import org.opensearch.client.opensearch.core.search.Hit;
import org.opensearch.client.opensearch.core.search.TotalHitsRelation;

/**
 * search-fulltext-01-tim-kiem-co-ban.md §4/§5/Task 5-8 - pure Mockito
 * coverage of {@link SearchServiceImpl}'s business rules (validation, scope
 * check ordering, timeout, degraded mode). Unlike {@code SearchScopeIT}, this
 * needs no real OpenSearch/Postgres, so it actually runs in every
 * environment (including this sandbox, unlike the Testcontainers-based
 * suite - see that class's note on the V21 migration issue).
 */
class SearchServiceTest {

    private static final String USER_SUB = "user-1";

    private OpenSearchClient openSearchClient;
    private GroupMemberRepository groupMemberRepository;
    private SearchResultHydrator hydrator;
    private SystemConfigService systemConfigService;
    private AuditService auditService;
    private AuditRequestContext auditRequestContext;
    private TagRepository tagRepository;
    private MetadataFallbackSearchService metadataFallbackSearchService;
    private SearchServiceImpl service;

    @BeforeEach
    void setUp() {
        openSearchClient = mock(OpenSearchClient.class);
        groupMemberRepository = mock(GroupMemberRepository.class);
        hydrator = mock(SearchResultHydrator.class);
        systemConfigService = mock(SystemConfigService.class);
        auditService = mock(AuditService.class);
        auditRequestContext = mock(AuditRequestContext.class);
        tagRepository = mock(TagRepository.class);
        metadataFallbackSearchService = mock(MetadataFallbackSearchService.class);

        when(groupMemberRepository.findGroupIdsByUserSub(USER_SUB)).thenReturn(List.of());
        when(systemConfigService.getLong(SystemConfigDefaults.SEARCH_MAX_RESULTS_PER_PAGE)).thenReturn(20L);
        when(systemConfigService.getLong(SystemConfigDefaults.SEARCH_QUERY_TIMEOUT_MS)).thenReturn(1500L);
        when(tagRepository.findByKindAndLabelIgnoreCase(eq(TagKinds.DOC_TYPE), any()))
                .thenAnswer(invocation -> Optional.of(docTypeTag(invocation.getArgument(1))));

        service = new SearchServiceImpl(
                openSearchClient,
                groupMemberRepository,
                hydrator,
                systemConfigService,
                auditService,
                auditRequestContext,
                tagRepository,
                metadataFallbackSearchService);
    }

    private static Tag docTypeTag(String label) {
        Tag tag = new Tag();
        tag.setKind(TagKinds.DOC_TYPE);
        tag.setLabel(label);
        return tag;
    }

    private static KeycloakUser user(String sub) {
        return new KeycloakUser(sub, sub, List.of("can-bo"));
    }

    // ---- AC-5: scope resolved BEFORE OpenSearch is ever touched --------------

    @Test
    void neverCallsOpenSearchWhenTheCurrentUserHasNoSubjectClaim() {
        SearchRequest request = new SearchRequest("hop dong", null, null, null, null);

        assertThatThrownBy(() -> service.search(request, user(null))).isInstanceOf(ScopeResolutionException.class);

        verifyNoInteractions(openSearchClient);
    }

    @Test
    void neverCallsOpenSearchWhenGroupMembershipLookupFails() {
        when(groupMemberRepository.findGroupIdsByUserSub(USER_SUB)).thenThrow(new RuntimeException("db down"));
        SearchRequest request = new SearchRequest("hop dong", null, null, null, null);

        assertThatThrownBy(() -> service.search(request, user(USER_SUB))).isInstanceOf(ScopeResolutionException.class);

        verifyNoInteractions(openSearchClient);
    }

    // ---- AC-7: empty query -> 400, no OpenSearch call -------------------------

    @Test
    void rejectsABlankQueryWithoutCallingOpenSearch() {
        SearchRequest request = new SearchRequest("   ", null, null, null, null);

        assertThatThrownBy(() -> service.search(request, user(USER_SUB)))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getStatus())
                .isEqualTo(org.springframework.http.HttpStatus.BAD_REQUEST);

        verifyNoInteractions(openSearchClient);
    }

    // ---- AC-9: size validation -------------------------------------------------

    @Test
    void rejectsASizeAboveTheHardCeiling() {
        SearchRequest request = new SearchRequest("hop dong", null, null, 0, SearchServiceImpl.HARD_MAX_SIZE + 1);

        assertThatThrownBy(() -> service.search(request, user(USER_SUB)))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getStatus())
                .isEqualTo(org.springframework.http.HttpStatus.BAD_REQUEST);
    }

    @Test
    void rejectsANonPositiveSize() {
        SearchRequest request = new SearchRequest("hop dong", null, null, 0, 0);

        assertThatThrownBy(() -> service.search(request, user(USER_SUB))).isInstanceOf(ApiException.class);
    }

    @Test
    void rejectsFromPlusSizeAboveTenThousand() {
        // page * size + size > 10_000, size at the hard ceiling.
        SearchRequest request = new SearchRequest("hop dong", null, null, 201, SearchServiceImpl.HARD_MAX_SIZE);

        assertThatThrownBy(() -> service.search(request, user(USER_SUB)))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getStatus())
                .isEqualTo(org.springframework.http.HttpStatus.BAD_REQUEST);
    }

    // ---- AC-10: a slow OpenSearch call is a clear error, never a partial result ----

    @Test
    void failsWithGatewayTimeoutWhenOpenSearchTakesLongerThanTheConfiguredTimeout() throws Exception {
        when(systemConfigService.getLong(SystemConfigDefaults.SEARCH_QUERY_TIMEOUT_MS)).thenReturn(100L);
        when(openSearchClient.search(any(org.opensearch.client.opensearch.core.SearchRequest.class), eq(SegmentDocument.class)))
                .thenAnswer(invocation -> {
                    Thread.sleep(1000);
                    return fakeResponse(List.of());
                });

        SearchRequest request = new SearchRequest("hop dong", null, null, 0, 20);

        assertThatThrownBy(() -> service.search(request, user(USER_SUB)))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getStatus())
                .isEqualTo(org.springframework.http.HttpStatus.GATEWAY_TIMEOUT);
    }

    // ---- AC-11: OpenSearch unreachable -> degraded mode, HTTP 200, no SQL fallback ----

    @Test
    void degradesInsteadOfFailingWhenOpenSearchIsUnreachable() throws Exception {
        when(openSearchClient.search(any(org.opensearch.client.opensearch.core.SearchRequest.class), eq(SegmentDocument.class)))
                .thenThrow(new IOException("connection refused", new ConnectException("Connection refused")));

        SearchRequest request = new SearchRequest("hop dong", null, null, 0, 20);

        SearchResponse response = service.search(request, user(USER_SUB));

        assertThat(response.degradedMode()).isEqualTo(SearchResponse.DEGRADED_OPENSEARCH_UNAVAILABLE);
        assertThat(response.results()).isEmpty();
        verifyNoInteractions(hydrator);
    }

    // ---- Happy path: assembles the response from the (mocked) hydrator's output ----

    @Test
    void assemblesTheResponseFromOpenSearchAndTheHydrator() throws Exception {
        UUID documentId = UUID.randomUUID();
        SegmentDocument source = new SegmentDocument();
        source.setSegmentId(UUID.randomUUID().toString());
        source.setDocumentId(documentId.toString());
        source.setDocType("hop_dong");
        source.setContentSource("ocr");
        source.setConfirmSource("manual");

        Hit<SegmentDocument> hit = new Hit.Builder<SegmentDocument>()
                .index(SearchIndexNames.SEGMENT_PHYSICAL_INDEX)
                .id(source.getSegmentId())
                .score(12.34)
                .source(source)
                .build();

        when(openSearchClient.search(any(org.opensearch.client.opensearch.core.SearchRequest.class), eq(SegmentDocument.class)))
                .thenReturn(fakeResponse(List.of(hit), 1));

        SearchHit hydratedHit = new SearchHit(documentId, "Hop_dong.pdf", "hop_dong", 12.34, List.of(), "confirmed");
        when(hydrator.hydrate(any(), any())).thenReturn(List.of(hydratedHit));

        SearchRequest request = new SearchRequest("hop dong", null, null, 0, 20);
        SearchResponse response = service.search(request, user(USER_SUB));

        assertThat(response.degradedMode()).isEqualTo(SearchResponse.DEGRADED_NONE);
        assertThat(response.results()).containsExactly(hydratedHit);
        assertThat(response.totalApprox()).isEqualTo(1);
        assertThat(response.page()).isEqualTo(0);
        assertThat(response.size()).isEqualTo(20);
        verify(auditService, times(1)).recordAsync(any());
    }

    // ---- search-fulltext-04: Task 7 - search.executed audit metadata shape ----

    @Test
    void auditsWithTheAllowlistedMetadataKeysOnlyAndATruncatedQuery() throws Exception {
        when(openSearchClient.search(any(org.opensearch.client.opensearch.core.SearchRequest.class), eq(SegmentDocument.class)))
                .thenReturn(fakeResponse(List.of(), 0));

        String longQuery = "a".repeat(256);
        SearchRequest request = new SearchRequest(longQuery, null, "recent", 0, 20);
        service.search(request, user(USER_SUB));

        org.mockito.ArgumentCaptor<AuditService.AuditEntry> captor =
                org.mockito.ArgumentCaptor.forClass(AuditService.AuditEntry.class);
        verify(auditService).recordAsync(captor.capture());
        Map<String, Object> metadata = captor.getValue().metadata();

        assertThat(metadata.get("query")).isEqualTo("a".repeat(200));
        assertThat(metadata.get("resultCount")).isEqualTo(0);
        assertThat(metadata.get("sort")).isEqualTo("recent");
        assertThat(metadata).doesNotContainKey("degradedMode");
        assertThat(metadata).doesNotContainKey("queryLength");
    }

    // ---- search-fulltext-02: §3.3 AC-13 - filter/query size limits, validated before any OpenSearch call ----

    @Test
    void rejectsMoreThanTwentyTopicTags() {
        SearchFilters filters = filtersWithTopicTags(21);
        SearchRequest request = new SearchRequest(null, filters, null, null, null);

        assertThatThrownBy(() -> service.search(request, user(USER_SUB)))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getStatus())
                .isEqualTo(org.springframework.http.HttpStatus.BAD_REQUEST);
        verifyNoInteractions(openSearchClient);
    }

    @Test
    void rejectsAQueryLongerThan256Characters() {
        SearchRequest request = new SearchRequest("a".repeat(257), null, null, null, null);

        assertThatThrownBy(() -> service.search(request, user(USER_SUB)))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getStatus())
                .isEqualTo(org.springframework.http.HttpStatus.BAD_REQUEST);
        verifyNoInteractions(openSearchClient);
    }

    @Test
    void rejectsAQueryWithMoreThan32Tokens() {
        String query = String.join(" ", java.util.Collections.nCopies(33, "tu"));
        SearchRequest request = new SearchRequest(query, null, null, null, null);

        assertThatThrownBy(() -> service.search(request, user(USER_SUB)))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getStatus())
                .isEqualTo(org.springframework.http.HttpStatus.BAD_REQUEST);
        verifyNoInteractions(openSearchClient);
    }

    // ---- search-fulltext-02: AC-1/AC-6 - docType/confirmSource are closed lists ----

    @Test
    void rejectsADocTypeOutsideTheClosedList() {
        when(tagRepository.findByKindAndLabelIgnoreCase(eq(TagKinds.DOC_TYPE), any())).thenReturn(Optional.empty());
        SearchFilters filters = new SearchFilters("khong-ton-tai", null, null, null, null, null, null, null, null);
        SearchRequest request = new SearchRequest(null, filters, null, null, null);

        assertThatThrownBy(() -> service.search(request, user(USER_SUB)))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getStatus())
                .isEqualTo(org.springframework.http.HttpStatus.BAD_REQUEST);
        verifyNoInteractions(openSearchClient);
    }

    @Test
    void rejectsAConfirmSourceOutsideTheClosedList() {
        SearchFilters filters = new SearchFilters(null, null, null, null, null, null, null, null, "khong-hop-le");
        SearchRequest request = new SearchRequest(null, filters, null, null, null);

        assertThatThrownBy(() -> service.search(request, user(USER_SUB)))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getStatus())
                .isEqualTo(org.springframework.http.HttpStatus.BAD_REQUEST);
        verifyNoInteractions(openSearchClient);
    }

    // ---- search-fulltext-02: AC-4 - groupIds outside the caller's own scope are dropped silently, audited ----

    @Test
    void dropsAGroupIdOutsideTheCallersScopeAndAuditsItInsteadOfFailing() throws Exception {
        UUID memberGroupId = UUID.randomUUID();
        UUID foreignGroupId = UUID.randomUUID();
        when(groupMemberRepository.findGroupIdsByUserSub(USER_SUB)).thenReturn(List.of(memberGroupId));

        when(openSearchClient.search(any(org.opensearch.client.opensearch.core.SearchRequest.class), eq(SegmentDocument.class)))
                .thenReturn(fakeResponse(List.of(), 1));

        SearchFilters filters =
                new SearchFilters(null, null, null, null, null, List.of(memberGroupId, foreignGroupId), null, null, null);
        SearchRequest request = new SearchRequest("hop dong", filters, null, 0, 20);

        SearchResponse response = service.search(request, user(USER_SUB));

        assertThat(response.degradedMode()).isEqualTo(SearchResponse.DEGRADED_NONE);

        org.mockito.ArgumentCaptor<AuditService.AuditEntry> captor =
                org.mockito.ArgumentCaptor.forClass(AuditService.AuditEntry.class);
        verify(auditService).recordAsync(captor.capture());
        @SuppressWarnings("unchecked")
        List<String> droppedGroupIds = (List<String>) captor.getValue().metadata().get("droppedGroupIds");
        assertThat(droppedGroupIds).containsExactly(foreignGroupId.toString());
    }

    // ---- search-fulltext-02: AC-14/AC-7(-04) - filters-only + OpenSearch down -> SQL fallback, never degraded ----

    @Test
    void fallsBackToPostgresWhenOpenSearchIsUnavailableAndOnlyFiltersWereSupplied() throws Exception {
        when(openSearchClient.search(any(org.opensearch.client.opensearch.core.SearchRequest.class), eq(SegmentDocument.class)))
                .thenThrow(new IOException("connection refused", new ConnectException("Connection refused")));

        SearchHit fallbackHit = new SearchHit(UUID.randomUUID(), "tai-lieu.pdf", "văn bản", 0.0, List.of(), null);
        SearchResponse fallbackResponse =
                new SearchResponse(List.of(fallbackHit), 1, 0, 20, SearchResponse.DEGRADED_OPENSEARCH_UNAVAILABLE, List.of());
        when(metadataFallbackSearchService.search(any(), any(), any(), eq(0), eq(20))).thenReturn(fallbackResponse);

        SearchFilters filters = new SearchFilters("văn bản", null, null, null, null, null, null, null, null);
        SearchRequest request = new SearchRequest(null, filters, null, 0, 20);

        SearchResponse response = service.search(request, user(USER_SUB));

        assertThat(response).isEqualTo(fallbackResponse);
        verifyNoInteractions(hydrator);
    }

    @Test
    void neverFallsBackToPostgresWhenAKeywordWasSupplied() throws Exception {
        when(openSearchClient.search(any(org.opensearch.client.opensearch.core.SearchRequest.class), eq(SegmentDocument.class)))
                .thenThrow(new IOException("connection refused", new ConnectException("Connection refused")));

        SearchFilters filters = new SearchFilters("văn bản", null, null, null, null, null, null, null, null);
        SearchRequest request = new SearchRequest("hop dong", filters, null, 0, 20);

        SearchResponse response = service.search(request, user(USER_SUB));

        assertThat(response.degradedMode()).isEqualTo(SearchResponse.DEGRADED_OPENSEARCH_UNAVAILABLE);
        assertThat(response.results()).isEmpty();
        verifyNoInteractions(metadataFallbackSearchService);
    }

    // ---- search-fulltext-02 Task 6: a single request's own socket timeout is NOT "OpenSearch is down" ----

    @Test
    void aSingleRequestsSocketTimeoutNeverTriggersTheSqlFallbackOrDegradedMode() throws Exception {
        when(openSearchClient.search(any(org.opensearch.client.opensearch.core.SearchRequest.class), eq(SegmentDocument.class)))
                .thenThrow(new SocketTimeoutException("Read timed out"));

        SearchRequest request = new SearchRequest("hop dong", null, null, 0, 20);

        assertThatThrownBy(() -> service.search(request, user(USER_SUB)))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getStatus())
                .isEqualTo(org.springframework.http.HttpStatus.GATEWAY_TIMEOUT);
        verifyNoInteractions(metadataFallbackSearchService);
    }

    // ---- search-fulltext-02: AC-12 - 0 kết quả + active filter -> up to 5 refine suggestions ----

    @Test
    void suggestsRefinementsWhenTheCombinedQueryAndFiltersReturnZeroResults() throws Exception {
        when(openSearchClient.search(any(org.opensearch.client.opensearch.core.SearchRequest.class), eq(SegmentDocument.class)))
                .thenReturn(fakeResponse(List.of(), 0));
        when(openSearchClient.msearch(any(org.opensearch.client.opensearch.core.MsearchRequest.class), eq(SegmentDocument.class)))
                .thenReturn(fakeMsearchResponse(42));

        SearchFilters filters = new SearchFilters("văn bản", null, null, null, null, null, null, null, null);
        SearchRequest request = new SearchRequest("tu khoa khong ra ket qua", filters, null, 0, 20);

        SearchResponse response = service.search(request, user(USER_SUB));

        assertThat(response.totalApprox()).isZero();
        assertThat(response.refineSuggestions()).hasSize(1);
        assertThat(response.refineSuggestions().get(0).removedFilter()).isEqualTo("docType");
        assertThat(response.refineSuggestions().get(0).estimatedCount()).isEqualTo(42);
    }

    /**
     * Real (not mocked) {@code _msearch} response, one item per {@code cardinalities} entry -
     * {@code MultiSearchResponseItem}/{@code MultiSearchItem} carry final methods the sandbox's
     * Mockito mock maker cannot stub (self-attach for the inline mock maker is unavailable here),
     * so this builds the actual response objects the way {@code fakeResponse} already does below.
     */
    private static MsearchResponse<SegmentDocument> fakeMsearchResponse(long... cardinalities) {
        List<org.opensearch.client.opensearch.core.msearch.MultiSearchResponseItem<SegmentDocument>> items =
                new ArrayList<>();
        for (long cardinality : cardinalities) {
            org.opensearch.client.opensearch.core.msearch.MultiSearchItem<SegmentDocument> result =
                    new org.opensearch.client.opensearch.core.msearch.MultiSearchItem.Builder<SegmentDocument>()
                            .status(200)
                            .took(1)
                            .timedOut(false)
                            .shards(s -> s.total(1).successful(1).failed(0))
                            .hits(h -> h.total(t -> t.value(0).relation(TotalHitsRelation.Eq)).hits(List.of()))
                            .aggregations(
                                    SearchQueryBuilder.TOTAL_DOCUMENTS_AGGREGATION,
                                    Aggregate.of(a -> a.cardinality(c -> c.value(cardinality))))
                            .build();
            items.add(org.opensearch.client.opensearch.core.msearch.MultiSearchResponseItem
                    .<SegmentDocument>of(b -> b.result(result)));
        }
        return MsearchResponse.<SegmentDocument>of(b -> b.took(1).responses(items));
    }

    private static SearchFilters filtersWithTopicTags(int count) {
        List<String> tags = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            tags.add("tag-" + i);
        }
        return new SearchFilters(null, tags, null, null, null, null, null, null, null);
    }

    private static org.opensearch.client.opensearch.core.SearchResponse<SegmentDocument> fakeResponse(
            List<Hit<SegmentDocument>> hits) {
        return fakeResponse(hits, hits.size());
    }

    private static org.opensearch.client.opensearch.core.SearchResponse<SegmentDocument> fakeResponse(
            List<Hit<SegmentDocument>> hits, long cardinality) {
        Builder<SegmentDocument> builder = new Builder<SegmentDocument>()
                .took(1)
                .timedOut(false)
                .shards(s -> s.total(1).successful(1).failed(0))
                .hits(h -> h.total(t -> t.value(hits.size()).relation(TotalHitsRelation.Eq)).hits(hits))
                .aggregations(
                        SearchQueryBuilder.TOTAL_DOCUMENTS_AGGREGATION,
                        Aggregate.of(a -> a.cardinality(c -> c.value(cardinality))));
        return builder.build();
    }

    // ==== search-fulltext-04-dong-bo-chi-muc.md §5 - SearchService.lexicalSearch() =========

    private static ScopeContext scopeCtx(String sub) {
        return new ScopeContext(sub, List.of());
    }

    // ---- never calls SearchResultHydrator (double-check ACL is the caller's job) ----

    @Test
    void lexicalSearchNeverCallsTheHydrator() throws Exception {
        SegmentDocument source = new SegmentDocument();
        source.setSegmentId(UUID.randomUUID().toString());
        source.setDocumentId(UUID.randomUUID().toString());
        Hit<SegmentDocument> hit = new Hit.Builder<SegmentDocument>()
                .index(SearchIndexNames.SEGMENT_PHYSICAL_INDEX)
                .id(source.getSegmentId())
                .score(1.0)
                .source(source)
                .build();
        when(openSearchClient.search(any(org.opensearch.client.opensearch.core.SearchRequest.class), eq(SegmentDocument.class)))
                .thenReturn(fakeResponse(List.of(hit)));

        service.lexicalSearch("hop dong", null, scopeCtx(USER_SUB), 50);

        verifyNoInteractions(hydrator);
    }

    // ---- returns up to topK hits, ordered by bm25Score descending (OpenSearch's own _score order) ----

    @Test
    void lexicalSearchReturnsHitsOrderedByScoreDescending() throws Exception {
        UUID documentId1 = UUID.randomUUID();
        UUID documentId2 = UUID.randomUUID();
        SegmentDocument higher = new SegmentDocument();
        higher.setSegmentId(UUID.randomUUID().toString());
        higher.setDocumentId(documentId1.toString());
        SegmentDocument lower = new SegmentDocument();
        lower.setSegmentId(UUID.randomUUID().toString());
        lower.setDocumentId(documentId2.toString());

        Hit<SegmentDocument> hitHigher = new Hit.Builder<SegmentDocument>()
                .index(SearchIndexNames.SEGMENT_PHYSICAL_INDEX)
                .id(higher.getSegmentId())
                .score(9.9)
                .source(higher)
                .build();
        Hit<SegmentDocument> hitLower = new Hit.Builder<SegmentDocument>()
                .index(SearchIndexNames.SEGMENT_PHYSICAL_INDEX)
                .id(lower.getSegmentId())
                .score(1.1)
                .source(lower)
                .build();
        when(openSearchClient.search(any(org.opensearch.client.opensearch.core.SearchRequest.class), eq(SegmentDocument.class)))
                .thenReturn(fakeResponse(List.of(hitHigher, hitLower)));

        List<com.aismartstore.backend.search.query.LexicalHit> hits =
                service.lexicalSearch("hop dong", null, scopeCtx(USER_SUB), 50);

        assertThat(hits).hasSize(2);
        assertThat(hits.get(0).documentId()).isEqualTo(documentId1);
        assertThat(hits.get(0).bm25Score()).isEqualTo(9.9);
        assertThat(hits.get(1).documentId()).isEqualTo(documentId2);
        assertThat(hits.get(1).bm25Score()).isEqualTo(1.1);
    }

    // ---- OpenSearch unreachable -> OpenSearchUnavailableException (no SQL fallback) ----

    @Test
    void lexicalSearchThrowsWhenOpenSearchIsUnreachable() throws Exception {
        when(openSearchClient.search(any(org.opensearch.client.opensearch.core.SearchRequest.class), eq(SegmentDocument.class)))
                .thenThrow(new IOException("connection refused", new ConnectException("Connection refused")));

        assertThatThrownBy(() -> service.lexicalSearch("hop dong", null, scopeCtx(USER_SUB), 50))
                .isInstanceOf(OpenSearchUnavailableException.class);
        verifyNoInteractions(metadataFallbackSearchService);
    }

    // ---- a blank query never reaches OpenSearch at all - no BM25 score to fuse ----

    @Test
    void lexicalSearchReturnsEmptyForABlankQueryWithoutCallingOpenSearch() {
        List<com.aismartstore.backend.search.query.LexicalHit> hits =
                service.lexicalSearch("   ", null, scopeCtx(USER_SUB), 50);

        assertThat(hits).isEmpty();
        verifyNoInteractions(openSearchClient);
    }

    /**
     * Rủi ro Cao (nhắc lại theo yêu cầu chéo 2 slug, spec -04 §5): {@code
     * lexicalSearch()} deliberately returns a hit whose {@code scopeCtx}
     * would NOT actually grant access in Postgres (no hydrator call here at
     * all - see {@link #lexicalSearchNeverCallsTheHydrator}). This is
     * correct <b>only</b> because the caller ({@code SemanticSearchService},
     * spec {@code search-semantic.md}) is contractually required to run
     * every hit through {@code SearchResultHydrator} (or an equivalent
     * Postgres check) before ever handing a result to an end user - that
     * integration test lives in {@code search-semantic}'s own test suite,
     * not here (this module has no visibility into that caller).
     */
    @Test
    void lexicalSearchDoesNotItselfDoubleCheckAclLeavingItEntirelyToTheCaller() throws Exception {
        SegmentDocument otherUsersSegment = new SegmentDocument();
        otherUsersSegment.setSegmentId(UUID.randomUUID().toString());
        otherUsersSegment.setDocumentId(UUID.randomUUID().toString());
        Hit<SegmentDocument> hit = new Hit.Builder<SegmentDocument>()
                .index(SearchIndexNames.SEGMENT_PHYSICAL_INDEX)
                .id(otherUsersSegment.getSegmentId())
                .score(5.0)
                .source(otherUsersSegment)
                .build();
        when(openSearchClient.search(any(org.opensearch.client.opensearch.core.SearchRequest.class), eq(SegmentDocument.class)))
                .thenReturn(fakeResponse(List.of(hit)));

        // scopeCtx here has no relation to otherUsersSegment's real owner/ACL in Postgres -
        // lexicalSearch() has no way to know that, and must not pretend to.
        List<com.aismartstore.backend.search.query.LexicalHit> hits =
                service.lexicalSearch("hop dong", null, scopeCtx("someone-else"), 50);

        assertThat(hits).extracting(h -> h.documentId().toString())
                .containsExactly(otherUsersSegment.getDocumentId());
    }
}
