package com.aismartstore.backend.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.aismartstore.backend.search.dto.SearchFilters;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.opensearch.client.opensearch._types.query_dsl.BoolQuery;
import org.opensearch.client.opensearch._types.query_dsl.Query;
import org.opensearch.client.opensearch._types.query_dsl.RangeQuery;
import org.opensearch.client.opensearch._types.query_dsl.SimpleQueryStringQuery;
import org.opensearch.client.opensearch._types.query_dsl.TermQuery;
import org.opensearch.client.opensearch._types.query_dsl.TermsQuery;
import org.opensearch.client.opensearch.core.MsearchRequest;
import org.opensearch.client.opensearch.core.SearchRequest;

/**
 * search-fulltext-02-tim-kiem-nang-cao.md §5 - pure unit coverage of {@link
 * SearchQueryBuilder}'s query-DSL assembly (no OpenSearch/network involved):
 * AC-1-AC-6's filter clauses, AC-7-AC-11's shortened-syntax flags, and §4 step 2's
 * per-group {@code exclude} used by the AC-12 diagnostics.
 */
class SearchQueryBuilderTest {

    private static final ScopeContext CTX = new ScopeContext("user-1", List.of("group-a"));

    // ---- AC-7-AC-11: simple_query_string flags/fields, and AC-11's "no syntax error" guarantee ----

    @Test
    void usesARestrictedSimpleQueryStringWithTheDocumentedFieldsAndFlags() {
        SearchRequest request = SearchQueryBuilder.buildLexicalQuery("hop dong", null, CTX, List.of(), "relevance", 0, 20);

        SimpleQueryStringQuery sqs = mustClause(request).must().get(0).simpleQueryString();
        assertThat(sqs.query()).isEqualTo("hop dong");
        assertThat(sqs.fields()).containsExactlyInAnyOrder("text^3", "text.folded^0.5", "text.shingle^2");
        assertThat(sqs.flags().multiple()).isEqualTo("PHRASE|AND|OR|NOT|PRECEDENCE|WHITESPACE");
    }

    @Test
    void neverThrowsOnCharactersOutsideTheSupportedShorthandSyntax() {
        // AC-11: `*`, `~`, `field:value` - simple_query_string itself swallows these, no code here
        // needs to special-case them; this only proves the builder never rejects them either.
        assertThatCode(() -> SearchQueryBuilder.buildLexicalQuery(
                        "hop dong * ~fuzzy field:value", null, CTX, List.of(), "relevance", 0, 20))
                .doesNotThrowAnyException();
    }

    // ---- §4 step 1.3: empty query -> straight to `document`, no collapse, sort=uploaded_at desc ----

    @Test
    void queriesTheDocumentIndexDirectlyWithNoCollapseWhenQueryIsBlank() {
        SearchRequest request = SearchQueryBuilder.buildLexicalQuery("   ", null, CTX, List.of(), "relevance", 0, 20);

        assertThat(request.index()).containsExactly(SearchIndexNames.DOCUMENT_ALIAS);
        assertThat(request.collapse()).isNull();
        assertThat(request.query().bool().must()).isEmpty();
        assertThat(request.sort()).hasSize(1);
        assertThat(request.sort().get(0).field().field()).isEqualTo("uploaded_at");
    }

    @Test
    void queriesTheSegmentIndexWithCollapseWhenQueryIsPresent() {
        SearchRequest request = SearchQueryBuilder.buildLexicalQuery("hop dong", null, CTX, List.of(), "relevance", 0, 20);

        assertThat(request.index()).containsExactly(SearchIndexNames.SEGMENT_ALIAS);
        assertThat(request.collapse()).isNotNull();
        assertThat(request.collapse().field()).isEqualTo("document_id");
    }

    // ---- search-fulltext-03: §4 step 2 (AC-5) - inner_hits sort = score desc, page_no/time_start_ms asc ----

    @Test
    void innerHitsSortsByScoreThenPageNoThenTimeStartMsAscending() {
        SearchRequest request = SearchQueryBuilder.buildLexicalQuery("hop dong", null, CTX, List.of(), "relevance", 0, 20);

        org.opensearch.client.opensearch.core.search.InnerHits innerHits =
                request.collapse().innerHits().get(0);
        List<org.opensearch.client.opensearch._types.SortOptions> sort = innerHits.sort();

        assertThat(sort).hasSize(3);
        assertThat(sort.get(0).score().order()).isEqualTo(org.opensearch.client.opensearch._types.SortOrder.Desc);
        assertThat(sort.get(1).field().field()).isEqualTo("page_no");
        assertThat(sort.get(1).field().order()).isEqualTo(org.opensearch.client.opensearch._types.SortOrder.Asc);
        assertThat(sort.get(2).field().field()).isEqualTo("time_start_ms");
        assertThat(sort.get(2).field().order()).isEqualTo(org.opensearch.client.opensearch._types.SortOrder.Asc);
    }

    // ---- search-fulltext-03: §4 step 1.1 - unified highlighter, fragment_size=200, 1 fragment, sentence boundary ----

    @Test
    void innerHitsHighlightUsesUnifiedHighlighterWithSentenceBoundaryScanner() {
        SearchRequest request = SearchQueryBuilder.buildLexicalQuery("hop dong", null, CTX, List.of(), "relevance", 0, 20);

        org.opensearch.client.opensearch.core.search.InnerHits innerHits =
                request.collapse().innerHits().get(0);
        org.opensearch.client.opensearch.core.search.HighlightField field = innerHits.highlight().fields().get("text");

        assertThat(field.fragmentSize()).isEqualTo(200);
        assertThat(field.numberOfFragments()).isEqualTo(1);
        assertThat(field.boundaryScanner())
                .isEqualTo(org.opensearch.client.opensearch.core.search.BoundaryScanner.Sentence);
        assertThat(field.noMatchSize()).isZero();
    }

    // ---- AC-1: doc_type - single term ------------------------------------------------------------

    @Test
    void addsADocTypeTermClauseWhenPresent() {
        SearchFilters filters = filters(b -> b.docType = "văn bản");

        List<Query> clauses = SearchQueryBuilder.buildFilterClauses(filters, CTX, List.of(), null);

        TermQuery term = onlyClauseOfKind(clauses, Query::isTerm, Query::term, "doc_type");
        assertThat(term.value().stringValue()).isEqualTo("văn bản");
    }

    // ---- AC-2: topic_tags - AND = one term per tag, OR = a single terms clause -------------------

    @Test
    void topicTagsAndModeAddsOneTermClausePerTag() {
        SearchFilters filters = filters(b -> {
            b.topicTags = List.of("dat_dai", "dan_su");
            b.topicTagsMode = "AND";
        });

        List<Query> clauses = SearchQueryBuilder.buildFilterClauses(filters, CTX, List.of(), null);

        List<String> tagValues = clauses.stream()
                .filter(Query::isTerm)
                .map(Query::term)
                .filter(t -> "topic_tags".equals(t.field()))
                .map(t -> t.value().stringValue())
                .toList();
        assertThat(tagValues).containsExactlyInAnyOrder("dat_dai", "dan_su");
    }

    @Test
    void topicTagsOrModeAddsASingleTermsClause() {
        SearchFilters filters = filters(b -> {
            b.topicTags = List.of("dat_dai", "dan_su");
            b.topicTagsMode = "OR";
        });

        List<Query> clauses = SearchQueryBuilder.buildFilterClauses(filters, CTX, List.of(), null);

        TermsQuery terms = onlyClauseOfKind(clauses, Query::isTerms, Query::terms, "topic_tags");
        assertThat(terms.terms().value()).extracting(v -> v.stringValue()).containsExactlyInAnyOrder("dat_dai", "dan_su");
    }

    // ---- AC-3: uploaded_at range ------------------------------------------------------------------

    @Test
    void addsAnUploadedAtRangeClauseWithBothBounds() {
        SearchFilters filters = filters(b -> {
            b.uploadedFrom = LocalDate.of(2026, 1, 1);
            b.uploadedTo = LocalDate.of(2026, 3, 1);
        });

        List<Query> clauses = SearchQueryBuilder.buildFilterClauses(filters, CTX, List.of(), null);

        RangeQuery range = onlyClauseOfKind(clauses, Query::isRange, Query::range, "uploaded_at");
        assertThat(range.gte().to(String.class)).isEqualTo("2026-01-01");
        assertThat(range.lte().to(String.class)).isEqualTo("2026-03-01T23:59:59.999Z");
    }

    // ---- AC-4: group_ids - the caller passes in the already-intersected ids ----------------------

    @Test
    void addsAScopeGroupIdsTermsClauseFromTheEffectiveGroupIdsOnly() {
        SearchFilters filters = filters(b -> b.groupIds = List.of(UUID.randomUUID(), UUID.randomUUID()));
        List<String> effectiveGroupIds = List.of("only-this-one");

        List<Query> clauses = SearchQueryBuilder.buildFilterClauses(filters, CTX, effectiveGroupIds, null);

        long scopeGroupIdsClauses = clauses.stream()
                .filter(Query::isTerms)
                .map(Query::terms)
                .filter(t -> "scope_group_ids".equals(t.field()))
                .count();
        // One from ScopeFilterBuilder's own `should` (nested inside the bool clause, not top-level
        // `filter[]`) - the top-level one here is the AC-4 narrowing clause, exactly one.
        assertThat(scopeGroupIdsClauses).isEqualTo(1);
        TermsQuery terms = clauses.stream()
                .filter(Query::isTerms)
                .map(Query::terms)
                .filter(t -> "scope_group_ids".equals(t.field()))
                .findFirst()
                .orElseThrow();
        assertThat(terms.terms().value()).extracting(v -> v.stringValue()).containsExactly("only-this-one");
    }

    @Test
    void addsNoGroupIdsClauseWhenTheIntersectionIsEmpty() {
        SearchFilters filters = filters(b -> b.groupIds = List.of(UUID.randomUUID()));

        List<Query> clauses = SearchQueryBuilder.buildFilterClauses(filters, CTX, List.of(), null);

        boolean anyScopeGroupIdsTermsClause =
                clauses.stream().filter(Query::isTerms).map(Query::terms).anyMatch(t -> "scope_group_ids".equals(t.field()));
        assertThat(anyScopeGroupIdsTermsClause).isFalse();
    }

    // ---- AC-5: folder_id -> folder_path_ids ---------------------------------------------------------

    @Test
    void addsAFolderPathIdsTermsClauseNeverAPrefixQuery() {
        UUID folderId = UUID.randomUUID();
        SearchFilters filters = filters(b -> b.folderId = folderId);

        List<Query> clauses = SearchQueryBuilder.buildFilterClauses(filters, CTX, List.of(), null);

        TermsQuery terms = onlyClauseOfKind(clauses, Query::isTerms, Query::terms, "folder_path_ids");
        assertThat(terms.terms().value()).extracting(v -> v.stringValue()).containsExactly(folderId.toString());
    }

    // ---- AC-6: media_type/confirm_source - independent, both AND'd in via bool.filter -------------

    @Test
    void addsMediaTypeAndConfirmSourceClausesIndependently() {
        SearchFilters filters = filters(b -> {
            b.mediaType = List.of("audio", "video");
            b.confirmSource = "manual";
        });

        List<Query> clauses = SearchQueryBuilder.buildFilterClauses(filters, CTX, List.of(), null);

        TermsQuery mediaType = onlyClauseOfKind(clauses, Query::isTerms, Query::terms, "media_type");
        assertThat(mediaType.terms().value()).extracting(v -> v.stringValue()).containsExactlyInAnyOrder("audio", "video");
        TermQuery confirmSource = onlyClauseOfKind(clauses, Query::isTerm, Query::term, "confirm_source");
        assertThat(confirmSource.value().stringValue()).isEqualTo("manual");
    }

    // ---- §4 step 2: `exclude` drops exactly one group's clause(s), nothing else -------------------

    @Test
    void excludingAGroupDropsOnlyThatGroupsClauses() {
        SearchFilters filters = filters(b -> {
            b.docType = "văn bản";
            b.mediaType = List.of("audio");
        });

        List<Query> withDocType = SearchQueryBuilder.buildFilterClauses(filters, CTX, List.of(), null);
        List<Query> withoutDocType = SearchQueryBuilder.buildFilterClauses(filters, CTX, List.of(), FilterGroup.DOC_TYPE);

        assertThat(withDocType.stream().filter(Query::isTerm).map(Query::term).anyMatch(t -> "doc_type".equals(t.field())))
                .isTrue();
        assertThat(withoutDocType.stream().filter(Query::isTerm).map(Query::term).anyMatch(t -> "doc_type".equals(t.field())))
                .isFalse();
        // media_type (a different group) survives untouched.
        assertThat(withoutDocType.stream().filter(Query::isTerms).map(Query::terms).anyMatch(t -> "media_type".equals(t.field())))
                .isTrue();
    }

    @Test
    void excludingTheFolderMediaConfirmGroupDropsAllThreeAtOnce() {
        SearchFilters filters = filters(b -> {
            b.folderId = UUID.randomUUID();
            b.mediaType = List.of("audio");
            b.confirmSource = "auto";
        });

        List<Query> clauses =
                SearchQueryBuilder.buildFilterClauses(filters, CTX, List.of(), FilterGroup.FOLDER_MEDIA_CONFIRM);

        assertThat(clauses.stream().filter(Query::isTerms).map(Query::terms).anyMatch(t -> "folder_path_ids".equals(t.field())))
                .isFalse();
        assertThat(clauses.stream().filter(Query::isTerms).map(Query::terms).anyMatch(t -> "media_type".equals(t.field())))
                .isFalse();
        assertThat(clauses.stream().filter(Query::isTerm).map(Query::term).anyMatch(t -> "confirm_source".equals(t.field())))
                .isFalse();
    }

    // ---- §4 step 2: the diagnostics _msearch request has one item per active group ---------------

    @Test
    void buildsOneMsearchItemPerActiveGroup() {
        SearchFilters filters = filters(b -> {
            b.docType = "văn bản";
            b.mediaType = List.of("audio");
        });
        List<FilterGroup> activeGroups = List.of(FilterGroup.DOC_TYPE, FilterGroup.FOLDER_MEDIA_CONFIRM);

        MsearchRequest request = SearchQueryBuilder.buildRefineDiagnosticsRequest(
                SearchIndexNames.SEGMENT_ALIAS, "hop dong", filters, CTX, List.of(), activeGroups);

        assertThat(request.searches()).hasSize(2);
        request.searches().forEach(item -> {
            assertThat(item.header().index()).containsExactly(SearchIndexNames.SEGMENT_ALIAS);
            assertThat(item.body().size()).isZero();
        });
    }

    private static BoolQuery mustClause(SearchRequest request) {
        return request.query().bool();
    }

    private static <T> T onlyClauseOfKind(
            List<Query> clauses, java.util.function.Predicate<Query> kindCheck, java.util.function.Function<Query, T> extract, String field) {
        return clauses.stream()
                .filter(kindCheck)
                .map(extract)
                .filter(clause -> fieldOf(clause).equals(field))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No clause found for field " + field));
    }

    private static String fieldOf(Object clause) {
        if (clause instanceof TermQuery term) {
            return term.field();
        }
        if (clause instanceof TermsQuery terms) {
            return terms.field();
        }
        if (clause instanceof RangeQuery range) {
            return range.field();
        }
        throw new IllegalArgumentException("Unsupported clause type: " + clause);
    }

    private static SearchFilters filters(java.util.function.Consumer<FilterBuilder> customize) {
        FilterBuilder builder = new FilterBuilder();
        customize.accept(builder);
        return new SearchFilters(
                builder.docType,
                builder.topicTags,
                builder.topicTagsMode,
                builder.uploadedFrom,
                builder.uploadedTo,
                builder.groupIds,
                builder.folderId,
                builder.mediaType,
                builder.confirmSource);
    }

    /** Plain mutable holder so each test only sets the 1-2 fields it actually cares about. */
    private static final class FilterBuilder {
        String docType;
        List<String> topicTags;
        String topicTagsMode;
        LocalDate uploadedFrom;
        LocalDate uploadedTo;
        List<UUID> groupIds;
        UUID folderId;
        List<String> mediaType;
        String confirmSource;
    }
}
