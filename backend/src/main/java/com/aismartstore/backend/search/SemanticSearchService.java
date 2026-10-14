package com.aismartstore.backend.search;

import com.aismartstore.backend.audit.AuditRequestContext;
import com.aismartstore.backend.audit.AuditService;
import com.aismartstore.backend.auth.KeycloakUser;
import com.aismartstore.backend.common.ApiException;
import com.aismartstore.backend.config.EmbeddingProperties;
import com.aismartstore.backend.groups.GroupMemberRepository;
import com.aismartstore.backend.groups.ScopeResolutionException;
import com.aismartstore.backend.search.dto.SearchFilters;
import com.aismartstore.backend.search.dto.SemanticResult;
import com.aismartstore.backend.search.dto.SemanticSearchRequest;
import com.aismartstore.backend.search.dto.SemanticSearchResponse;
import com.aismartstore.backend.search.query.LexicalHit;
import com.aismartstore.backend.systemconfig.SystemConfigDefaults;
import com.aismartstore.backend.systemconfig.SystemConfigService;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * SPEC-search-semantic T13/T17 (search-semantic-query.md §4.1): orchestrates {@code POST
 * /search/semantic} - always hybrid (lexical + k-NN, RRF, no rerank, no LLM).
 *
 * <p>Security order (R1): validate -> resolve scope (fail-closed 403, no engine/endpoint call) ->
 * concurrency permit (429) -> both branches with the very same scope filter -> RRF + evidence
 * threshold -> PostgreSQL double-check ({@link SemanticResultHydrator}). Degradation never drops the
 * scope filter. Search results are never cached.
 */
@Service
public class SemanticSearchService {

    private static final Logger log = LoggerFactory.getLogger(SemanticSearchService.class);

    static final int MAX_TOP_K = 200;
    static final int DEFAULT_TOP_K = 50;
    private static final double OVER_FETCH_FACTOR = 1.5;
    private static final int OVER_FETCH_PADDING = 10;
    private static final int MAX_HYDRATE_ROUNDS = 4;
    private static final int AUDIT_QUERY_MAX_LENGTH = 200;

    public static final String SCOPE_UNRESOLVED_MESSAGE =
            "Không xác định được phạm vi dữ liệu của bạn nên chưa thể tra cứu. Vui lòng thử lại sau hoặc liên hệ quản trị viên.";
    public static final String UNAVAILABLE_MESSAGE = "Dịch vụ tra cứu đang tạm thời không khả dụng, vui lòng thử lại sau.";

    private final SearchServiceImpl searchService;
    private final EmbeddingClient embeddingClient;
    private final KnnSegmentSearcher knnSearcher;
    private final SemanticResultHydrator hydrator;
    private final SemanticQueryLimiter limiter;
    private final GroupMemberRepository groupMemberRepository;
    private final SystemConfigService systemConfigService;
    private final EmbeddingProperties embeddingProperties;
    private final AuditService auditService;
    private final AuditRequestContext auditRequestContext;
    private final ExecutorService branchExecutor = Executors.newCachedThreadPool();

    public SemanticSearchService(
            SearchServiceImpl searchService,
            EmbeddingClient embeddingClient,
            KnnSegmentSearcher knnSearcher,
            SemanticResultHydrator hydrator,
            SemanticQueryLimiter limiter,
            GroupMemberRepository groupMemberRepository,
            SystemConfigService systemConfigService,
            EmbeddingProperties embeddingProperties,
            AuditService auditService,
            AuditRequestContext auditRequestContext) {
        this.searchService = searchService;
        this.embeddingClient = embeddingClient;
        this.knnSearcher = knnSearcher;
        this.hydrator = hydrator;
        this.limiter = limiter;
        this.groupMemberRepository = groupMemberRepository;
        this.systemConfigService = systemConfigService;
        this.embeddingProperties = embeddingProperties;
        this.auditService = auditService;
        this.auditRequestContext = auditRequestContext;
    }

    @PreDestroy
    void shutdown() {
        branchExecutor.shutdownNow();
    }

    public SemanticSearchResponse search(SemanticSearchRequest request, KeycloakUser user) {
        String query = request.query() == null ? "" : request.query().trim();
        if (query.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "query: không được để trống");
        }
        // Unlike /search there is deliberately NO 32-token cap: a natural-language question is long
        // (SPEC-search-semantic §3 - only "rỗng / > 256 ký tự" is a 400).
        if (query.length() > SearchServiceImpl.MAX_QUERY_LENGTH) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST, "query: tối đa " + SearchServiceImpl.MAX_QUERY_LENGTH + " ký tự");
        }
        SearchFilters filters = request.filters();
        searchService.validateFilters(filters);
        int page = request.page() == null ? 0 : request.page();
        if (page < 0 || page >= SemanticSearchResponse.MAX_PAGES) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "page chỉ được là 0 hoặc 1 (tối đa 2 trang kết quả)");
        }
        int size = resolveSize(request.size());
        double minScore = resolveMinScore();

        // AC-4.2: fail-closed - no engine, no embedding endpoint is touched when scope is unknown.
        ScopeContext ctx;
        try {
            ctx = ScopeContext.resolve(user.sub(), groupMemberRepository);
        } catch (ScopeResolutionException e) {
            log.error("Semantic search: scope resolution failed", e);
            recordAudit(user, query, filters, null, "failure", 403, minScore, 0);
            throw new ApiException(
                    HttpStatus.FORBIDDEN,
                    Map.of("code", "SCOPE_UNRESOLVED", "message", SCOPE_UNRESOLVED_MESSAGE),
                    SCOPE_UNRESOLVED_MESSAGE);
        }

        try {
            limiter.acquireOrThrow();
        } catch (ApiException e) {
            recordAudit(user, query, filters, null, "failure", 429, minScore, 0);
            throw e;
        }

        try {
            return runSearch(query, filters, ctx, page, size, minScore, user);
        } finally {
            limiter.release();
        }
    }

    private SemanticSearchResponse runSearch(
            String query, SearchFilters filters, ScopeContext ctx, int page, int size, double minScore, KeycloakUser user) {
        int topK = resolveTopK();
        long searchTimeoutMs = systemConfigService.getLong(SystemConfigDefaults.SEARCH_QUERY_TIMEOUT_MS);

        long started = System.nanoTime();
        CompletableFuture<List<VectorHit>> vectorFuture = CompletableFuture.supplyAsync(() -> {
            try {
                float[] vector = embeddingClient.embedQuery(query);
                return knnSearcher.search(vector, topK, ctx, filters, embeddingProperties.modelVersion());
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }, branchExecutor);
        CompletableFuture<List<LexicalHit>> lexicalFuture =
                CompletableFuture.supplyAsync(() -> searchService.lexicalSearch(query, filters, ctx, topK), branchExecutor);

        List<VectorHit> vectorHits = await(vectorFuture, embeddingProperties.timeoutMs() + searchTimeoutMs, "vector");
        List<LexicalHit> lexicalHits = await(lexicalFuture, searchTimeoutMs, "lexical");

        if (vectorHits == null && lexicalHits == null) {
            recordAudit(user, query, filters, null, "failure", 503, minScore, 0);
            throw new ApiException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    Map.of("code", "SEARCH_UNAVAILABLE", "message", UNAVAILABLE_MESSAGE),
                    UNAVAILABLE_MESSAGE);
        }
        String degradedMode = vectorHits == null
                ? SemanticSearchResponse.DEGRADED_LEXICAL_ONLY
                : lexicalHits == null ? SemanticSearchResponse.DEGRADED_SEMANTIC_ONLY : SemanticSearchResponse.DEGRADED_NONE;

        List<FusedHit> fused = HybridFusion.fuse(lexicalHits, vectorHits, minScore);

        // Hydrate in over-fetched rounds so ACL-dropped / vanished segments do not leave short pages.
        int viewable = SemanticSearchResponse.MAX_PAGES * size;
        int fetch = (int) Math.ceil(size * OVER_FETCH_FACTOR) + OVER_FETCH_PADDING;
        List<SemanticResult> collected = new ArrayList<>();
        int consumed = 0;
        for (int round = 0; round < MAX_HYDRATE_ROUNDS && consumed < fused.size() && collected.size() <= viewable; round++) {
            int to = Math.min(fused.size(), consumed + fetch);
            collected.addAll(hydrator.hydrate(fused.subList(consumed, to), ctx));
            consumed = to;
        }

        long totalApprox = collected.size() + (fused.size() - consumed);
        boolean hasMore = collected.size() > viewable || (consumed < fused.size() && collected.size() >= viewable);
        int from = Math.min(page * size, collected.size());
        int to = Math.min(from + size, collected.size());
        List<SemanticResult> pageResults = List.copyOf(collected.subList(from, to));
        boolean insufficient = collected.isEmpty();

        log.info("semantic search: userSub={} queryLen={} degradedMode={} resultCount={} insufficientEvidence={} totalMs={}",
                user.sub(), query.length(), degradedMode, pageResults.size(), insufficient,
                (System.nanoTime() - started) / 1_000_000);

        recordAudit(user, query, filters, degradedMode, "success", 200, minScore, pageResults.size());
        return new SemanticSearchResponse(
                pageResults, degradedMode, insufficient, totalApprox, hasMore, SemanticSearchResponse.MAX_PAGES);
    }

    /** @return the branch result, or {@code null} when the branch failed or timed out */
    private <T> T await(CompletableFuture<T> future, long timeoutMs, String branch) {
        try {
            return future.get(Math.max(1, timeoutMs), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            log.warn("semantic search: {} branch timed out after {}ms", branch, timeoutMs);
            return null;
        } catch (ExecutionException e) {
            log.warn("semantic search: {} branch failed: {}", branch, e.getCause() == null ? e : e.getCause().toString());
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            return null;
        }
    }

    private int resolveSize(Integer requested) {
        if (requested != null) {
            if (requested <= 0 || requested > SearchServiceImpl.HARD_MAX_SIZE) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "size phải nằm trong khoảng 1–" + SearchServiceImpl.HARD_MAX_SIZE);
            }
            return requested;
        }
        long configured = systemConfigService.getLong(SystemConfigDefaults.SEARCH_MAX_RESULTS_PER_PAGE);
        return (int) Math.max(1, Math.min(configured, SearchServiceImpl.HARD_MAX_SIZE));
    }

    private int resolveTopK() {
        try {
            long configured = systemConfigService.getLong(SystemConfigDefaults.SEMANTIC_TOP_K);
            return (int) Math.max(1, Math.min(configured, MAX_TOP_K));
        } catch (RuntimeException e) {
            return DEFAULT_TOP_K;
        }
    }

    double resolveMinScore() {
        try {
            double value = Double.parseDouble(systemConfigService.getString(SystemConfigDefaults.SEMANTIC_MIN_SCORE).trim());
            if (Double.isNaN(value) || value < 0.0 || value > 1.0) {
                throw new NumberFormatException("out of range");
            }
            return value;
        } catch (RuntimeException e) {
            log.warn("semantic.min_score is unreadable/out of range, using the default 0.50: {}", e.toString());
            return 0.50;
        }
    }

    /** AC-7: {@code search.executed}, async, never carries an excerpt; also written for 403/429/503. */
    private void recordAudit(
            KeycloakUser user,
            String query,
            SearchFilters filters,
            String degradedMode,
            String outcome,
            int status,
            double minScore,
            int resultCount) {
        try {
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("query", query.length() > AUDIT_QUERY_MAX_LENGTH ? query.substring(0, AUDIT_QUERY_MAX_LENGTH) : query);
            if (filters != null) {
                metadata.put("filters", filters);
            }
            metadata.put("mode", "hybrid");
            if (degradedMode != null) {
                metadata.put("degradedMode", degradedMode);
            }
            metadata.put("minScore", minScore);
            metadata.put("resultCount", resultCount);

            auditService.recordAsync(new AuditService.AuditEntry(
                    user.preferredUsername(),
                    user.sub(),
                    "search.executed",
                    "search",
                    null,
                    outcome,
                    auditRequestContext.method(),
                    auditRequestContext.path(),
                    status,
                    auditRequestContext.ip(),
                    metadata));
        } catch (RuntimeException e) {
            log.warn("Failed to record a search.executed audit entry (semantic)", e);
        }
    }
}
