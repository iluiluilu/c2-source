package com.aismartstore.backend.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.aismartstore.backend.audit.AuditRequestContext;
import com.aismartstore.backend.audit.AuditService;
import com.aismartstore.backend.auth.KeycloakUser;
import com.aismartstore.backend.common.ApiException;
import com.aismartstore.backend.config.EmbeddingProperties;
import com.aismartstore.backend.config.SemanticProperties;
import com.aismartstore.backend.groups.GroupMemberRepository;
import com.aismartstore.backend.search.dto.PositionInfo;
import com.aismartstore.backend.search.dto.SemanticResult;
import com.aismartstore.backend.search.dto.SemanticSearchRequest;
import com.aismartstore.backend.search.dto.SemanticSearchResponse;
import com.aismartstore.backend.search.query.LexicalHit;
import com.aismartstore.backend.systemconfig.SystemConfigDefaults;
import com.aismartstore.backend.systemconfig.SystemConfigService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;

/** SPEC-search-semantic T13/T14/T17 (query §5): orchestration, degradation, 403/429/503, audit. */
class SemanticSearchServiceTest {

    private static final String SUB = "user-a";
    private final UUID doc = UUID.randomUUID();

    private SearchServiceImpl searchService;
    private EmbeddingClient embeddingClient;
    private KnnSegmentSearcher knn;
    private SemanticResultHydrator hydrator;
    private GroupMemberRepository groupMemberRepository;
    private SystemConfigService config;
    private AuditService auditService;
    private SemanticQueryLimiter limiter;
    private SemanticSearchService service;

    @BeforeEach
    void setUp() throws Exception {
        searchService = mock(SearchServiceImpl.class);
        embeddingClient = mock(EmbeddingClient.class);
        knn = mock(KnnSegmentSearcher.class);
        hydrator = mock(SemanticResultHydrator.class);
        groupMemberRepository = mock(GroupMemberRepository.class);
        config = mock(SystemConfigService.class);
        auditService = mock(AuditService.class);
        AuditRequestContext auditRequestContext = mock(AuditRequestContext.class);
        limiter = new SemanticQueryLimiter(new SemanticProperties(true, 2, 500, 600000, 500, 1, 5, 3600000L));

        when(groupMemberRepository.findGroupIdsByUserSub(SUB)).thenReturn(List.of());
        when(config.getLong(SystemConfigDefaults.SEARCH_MAX_RESULTS_PER_PAGE)).thenReturn(20L);
        when(config.getLong(SystemConfigDefaults.SEARCH_QUERY_TIMEOUT_MS)).thenReturn(1500L);
        when(config.getLong(SystemConfigDefaults.SEMANTIC_TOP_K)).thenReturn(50L);
        when(config.getString(SystemConfigDefaults.SEMANTIC_MIN_SCORE)).thenReturn("0.50");
        when(embeddingClient.embedQuery(anyString())).thenReturn(new float[1024]);
        when(hydrator.hydrate(any(), any())).thenAnswer(inv -> {
            List<FusedHit> hits = inv.getArgument(0);
            return hits.stream().map(SemanticSearchServiceTest::result).toList();
        });

        EmbeddingProperties props = new EmbeddingProperties(
                "http://x", 32, 500, 30000, "m", "ver-1", 1024, 512, 64, true, "i");
        service = new SemanticSearchService(
                searchService, embeddingClient, knn, hydrator, limiter, groupMemberRepository, config, props,
                auditService, auditRequestContext);
    }

    @AfterEach
    void tearDown() {
        service.shutdown();
    }

    private static SemanticResult result(FusedHit hit) {
        return new SemanticResult(
                hit.segmentId(), hit.documentId().toString(), "Tai lieu", "Quyết định", "doan trich " + hit.segmentId(),
                "ocr", "auto", new PositionInfo("page", 1, 1, 2, null, null, null, null, null),
                hit.matchedLexical(), hit.cosine(), hit.rrf(), null, null);
    }

    private static KeycloakUser user() {
        return new KeycloakUser(SUB, "a", List.of("can-bo"));
    }

    private static SemanticSearchRequest request(String query, Integer page, Integer size) {
        return new SemanticSearchRequest(query, null, page, size);
    }

    private VectorHit vec(String id, double cosine) {
        return new VectorHit(id, doc, cosine);
    }

    private LexicalHit lex(String id) {
        return new LexicalHit(id, doc, 1.0);
    }

    private void vectorReturns(List<VectorHit> hits) throws Exception {
        when(knn.search(any(float[].class), anyInt(), any(), any(), anyString())).thenReturn(hits);
    }

    // ---- AC-1 / AC-3: hybrid, threshold ------------------------------------

    @Test
    void runsBothBranchesAndFusesThemWithDegradedModeNone() throws Exception {
        vectorReturns(List.of(vec("b", 0.9), vec("c", 0.3)));
        when(searchService.lexicalSearch(anyString(), any(), any(), anyInt())).thenReturn(List.of(lex("a"), lex("b")));

        SemanticSearchResponse response = service.search(request("hạn nộp báo cáo", null, null), user());

        assertThat(response.degradedMode()).isEqualTo("none");
        assertThat(response.results()).extracting(SemanticResult::segmentId).containsExactly("b", "a"); // c below 0.50, not lexical
        assertThat(response.insufficientEvidence()).isFalse();
        assertThat(response.maxPages()).isEqualTo(2);
        verify(knn).search(any(float[].class), eq(50), any(), any(), eq("ver-1"));
        verify(searchService).lexicalSearch(eq("hạn nộp báo cáo"), any(), any(), eq(50));
    }

    @Test
    void noEvidenceIsAnOrdinaryEmptyResultNotAnError() throws Exception {
        vectorReturns(List.of(vec("z", 0.49)));
        when(searchService.lexicalSearch(anyString(), any(), any(), anyInt())).thenReturn(List.of());

        SemanticSearchResponse response = service.search(request("câu hỏi lạ", null, null), user());

        assertThat(response.results()).isEmpty();
        assertThat(response.insufficientEvidence()).isTrue();
        assertThat(response.degradedMode()).isEqualTo("none");
    }

    @Test
    void theThresholdComesFromSystemConfigOnEveryRequest() throws Exception {
        when(config.getString(SystemConfigDefaults.SEMANTIC_MIN_SCORE)).thenReturn("0.95");
        vectorReturns(List.of(vec("a", 0.9)));
        when(searchService.lexicalSearch(anyString(), any(), any(), anyInt())).thenReturn(List.of());

        assertThat(service.search(request("q", null, null), user()).results()).isEmpty();
    }

    // ---- AC-5: degradation ---------------------------------------------------

    @Test
    void embeddingFailureDegradesToLexicalOnlyKeepingScopeAndOrder() throws Exception {
        when(embeddingClient.embedQuery(anyString())).thenThrow(new EmbeddingException("timeout"));
        when(searchService.lexicalSearch(anyString(), any(), any(), anyInt())).thenReturn(List.of(lex("a"), lex("b")));

        SemanticSearchResponse response = service.search(request("q", null, null), user());

        assertThat(response.degradedMode()).isEqualTo("lexical_only");
        assertThat(response.results()).extracting(SemanticResult::segmentId).containsExactly("a", "b");
        verify(knn, never()).search(any(float[].class), anyInt(), any(), any(), anyString());
        // the scope was still resolved and handed to the lexical branch
        ArgumentCaptor<ScopeContext> ctx = ArgumentCaptor.forClass(ScopeContext.class);
        verify(searchService).lexicalSearch(anyString(), any(), ctx.capture(), anyInt());
        assertThat(ctx.getValue().userSub()).isEqualTo(SUB);
    }

    @Test
    void knnFailureDegradesToLexicalOnly() throws Exception {
        when(knn.search(any(float[].class), anyInt(), any(), any(), anyString())).thenThrow(new java.io.IOException("os"));
        when(searchService.lexicalSearch(anyString(), any(), any(), anyInt())).thenReturn(List.of(lex("a")));

        assertThat(service.search(request("q", null, null), user()).degradedMode()).isEqualTo("lexical_only");
    }

    @Test
    void lexicalFailureDegradesToSemanticOnlyWithThreshold() throws Exception {
        vectorReturns(List.of(vec("a", 0.9), vec("b", 0.1)));
        when(searchService.lexicalSearch(anyString(), any(), any(), anyInt())).thenThrow(new OpenSearchUnavailableException(new java.io.IOException("x")));

        SemanticSearchResponse response = service.search(request("q", null, null), user());

        assertThat(response.degradedMode()).isEqualTo("semantic_only");
        assertThat(response.results()).extracting(SemanticResult::segmentId).containsExactly("a");
    }

    @Test
    void bothBranchesFailingIs503NotAnEmptyList() throws Exception {
        when(embeddingClient.embedQuery(anyString())).thenThrow(new EmbeddingException("down"));
        when(searchService.lexicalSearch(anyString(), any(), any(), anyInt())).thenThrow(new RuntimeException("down"));

        assertThatThrownBy(() -> service.search(request("q", null, null), user()))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    ApiException api = (ApiException) e;
                    assertThat(api.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(((Map<?, ?>) api.getBody()).get("code")).isEqualTo("SEARCH_UNAVAILABLE");
                    assertThat(api.getMessage()).isEqualTo("Dịch vụ tra cứu đang tạm thời không khả dụng, vui lòng thử lại sau.");
                });
        verify(hydrator, never()).hydrate(any(), any());
        assertThat(limiter.availablePermits()).isEqualTo(2); // permit released even on failure
    }

    // ---- AC-4.2: fail-closed ---------------------------------------------------

    @Test
    void scopeResolutionFailureIs403WithoutTouchingAnyEngineOrEndpointAndIsAudited() {
        when(groupMemberRepository.findGroupIdsByUserSub(SUB)).thenThrow(new RuntimeException("db down"));

        assertThatThrownBy(() -> service.search(request("q", null, null), user()))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    ApiException api = (ApiException) e;
                    assertThat(api.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(((Map<?, ?>) api.getBody()).get("code")).isEqualTo("SCOPE_UNRESOLVED");
                    assertThat(api.getMessage()).startsWith("Không xác định được phạm vi dữ liệu của bạn");
                });

        verifyNoInteractions(embeddingClient, knn, hydrator);
        verify(searchService, never()).lexicalSearch(anyString(), any(), any(), anyInt());
        AuditService.AuditEntry entry = capturedAudit();
        assertThat(entry.outcome()).isEqualTo("failure");
        assertThat(entry.statusCode()).isEqualTo(403);
    }

    @Test
    void aMissingSubjectIsAlso403() {
        assertThatThrownBy(() -> service.search(request("q", null, null), new KeycloakUser(null, "x", List.of())))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
        verifyNoInteractions(embeddingClient, knn);
    }

    // ---- AC-5.3: concurrency -----------------------------------------------------

    @Test
    void whenAllPermitsAreTakenTheRequestIs429AndAudited() {
        limiter.acquireOrThrow();
        limiter.acquireOrThrow();

        assertThatThrownBy(() -> service.search(request("q", null, null), user()))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

        verifyNoInteractions(embeddingClient, knn, hydrator);
        assertThat(capturedAudit().statusCode()).isEqualTo(429);
    }

    @Test
    void concurrentQueriesBeyondTheLimitGetA429WhileTheRestComplete() throws Exception {
        CountDownLatch inFlight = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        when(embeddingClient.embedQuery(anyString())).thenAnswer(inv -> {
            inFlight.countDown();
            release.await(5, TimeUnit.SECONDS);
            return new float[1024];
        });
        vectorReturns(List.of(vec("a", 0.9)));
        when(searchService.lexicalSearch(anyString(), any(), any(), anyInt())).thenReturn(List.of());

        ExecutorService callers = Executors.newFixedThreadPool(3);
        try {
            Future<SemanticSearchResponse> first = callers.submit(() -> service.search(request("q1", null, null), user()));
            Future<SemanticSearchResponse> second = callers.submit(() -> service.search(request("q2", null, null), user()));
            assertThat(inFlight.await(5, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> service.search(request("q3", null, null), user()))
                    .isInstanceOf(ApiException.class)
                    .extracting(e -> ((ApiException) e).getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

            release.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS).results()).hasSize(1);
            assertThat(second.get(5, TimeUnit.SECONDS).results()).hasSize(1);
        } finally {
            callers.shutdownNow();
        }
        assertThat(limiter.availablePermits()).isEqualTo(2);
    }

    @Test
    void thePermitIsReleasedWhenTheSearchThrows() throws Exception {
        org.mockito.Mockito.doThrow(new IllegalStateException("db")).when(hydrator).hydrate(any(), any());
        vectorReturns(List.of(vec("a", 0.9)));
        when(searchService.lexicalSearch(anyString(), any(), any(), anyInt())).thenReturn(List.of());

        assertThatThrownBy(() -> service.search(request("q", null, null), user())).isInstanceOf(IllegalStateException.class);

        assertThat(limiter.availablePermits()).isEqualTo(2);
    }

    // ---- AC-6: paging ----------------------------------------------------------------

    @Test
    void pagesHaveTheRequestedSizeAndOnlyTwoPagesAreViewable() throws Exception {
        List<VectorHit> many = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            many.add(vec("s" + i, 0.9 - i * 0.001));
        }
        vectorReturns(many);
        when(searchService.lexicalSearch(anyString(), any(), any(), anyInt())).thenReturn(List.of());

        SemanticSearchResponse page0 = service.search(request("q", 0, 10), user());
        SemanticSearchResponse page1 = service.search(request("q", 1, 10), user());

        assertThat(page0.results()).extracting(SemanticResult::segmentId).containsExactlyElementsOf(ids(0, 10));
        assertThat(page1.results()).extracting(SemanticResult::segmentId).containsExactlyElementsOf(ids(10, 20));
        assertThat(page0.hasMore()).isTrue(); // 30 results > 2 viewable pages of 10
        assertThat(page0.totalApprox()).isEqualTo(30);
    }

    private static List<String> ids(int from, int to) {
        List<String> ids = new ArrayList<>();
        for (int i = from; i < to; i++) {
            ids.add("s" + i);
        }
        return ids;
    }

    @Test
    void hasMoreIsFalseWhenEverythingFitsInTwoPages() throws Exception {
        vectorReturns(List.of(vec("a", 0.9), vec("b", 0.8)));
        when(searchService.lexicalSearch(anyString(), any(), any(), anyInt())).thenReturn(List.of());

        SemanticSearchResponse response = service.search(request("q", 0, 10), user());

        assertThat(response.hasMore()).isFalse();
        assertThat(response.totalApprox()).isEqualTo(2);
    }

    @Test
    void page2OrNegativeAndBlankQueriesAreRejectedWith400BeforeAnyWork() {
        for (SemanticSearchRequest bad : List.of(request("q", 2, null), request("q", -1, null), request("   ", 0, null))) {
            assertThatThrownBy(() -> service.search(bad, user()))
                    .isInstanceOf(ApiException.class)
                    .extracting(e -> ((ApiException) e).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
        verifyNoInteractions(embeddingClient, knn, hydrator);
        verify(groupMemberRepository, never()).findGroupIdsByUserSub(anyString());
    }

    // ---- AC-7: audit --------------------------------------------------------------------

    private AuditService.AuditEntry capturedAudit() {
        ArgumentCaptor<AuditService.AuditEntry> captor = ArgumentCaptor.forClass(AuditService.AuditEntry.class);
        verify(auditService).recordAsync(captor.capture());
        return captor.getValue();
    }

    @Test
    void successfulSearchAuditsQueryTruncatedTo200ModeDegradedModeMinScoreAndCountButNoExcerpt() throws Exception {
        String longQuery = "x".repeat(256);
        vectorReturns(List.of(vec("a", 0.9)));
        when(searchService.lexicalSearch(anyString(), any(), any(), anyInt())).thenReturn(List.of());

        service.search(request(longQuery, null, null), user());

        AuditService.AuditEntry entry = capturedAudit();
        assertThat(entry.action()).isEqualTo("search.executed");
        assertThat(entry.outcome()).isEqualTo("success");
        assertThat(entry.metadata()).containsEntry("mode", "hybrid").containsEntry("degradedMode", "none")
                .containsEntry("minScore", 0.5).containsEntry("resultCount", 1);
        assertThat((String) entry.metadata().get("query")).hasSize(200);
        assertThat(entry.metadata().toString()).doesNotContain("doan trich");
    }

    @Test
    void a503IsAudited() throws Exception {
        when(embeddingClient.embedQuery(anyString())).thenThrow(new EmbeddingException("down"));
        when(searchService.lexicalSearch(anyString(), any(), any(), anyInt())).thenThrow(new RuntimeException("down"));

        assertThatThrownBy(() -> service.search(request("q", null, null), user())).isInstanceOf(ApiException.class);

        assertThat(capturedAudit().statusCode()).isEqualTo(503);
    }

    // ---- misc -----------------------------------------------------------------------------

    @Test
    void anUnreadableMinScoreFallsBackToTheDefault() {
        when(config.getString(SystemConfigDefaults.SEMANTIC_MIN_SCORE)).thenReturn("abc");
        assertThat(service.resolveMinScore()).isEqualTo(0.50);
        when(config.getString(SystemConfigDefaults.SEMANTIC_MIN_SCORE)).thenReturn("1.5");
        assertThat(service.resolveMinScore()).isEqualTo(0.50);
    }
}
