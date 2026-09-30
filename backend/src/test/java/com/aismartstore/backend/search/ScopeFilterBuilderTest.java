package com.aismartstore.backend.search;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.opensearch.client.opensearch._types.query_dsl.BoolQuery;
import org.opensearch.client.opensearch._types.query_dsl.Query;
import org.opensearch.client.opensearch._types.query_dsl.TermQuery;
import org.opensearch.client.opensearch._types.query_dsl.TermsQuery;

/**
 * search-fulltext-01-tim-kiem-co-ban.md §5 - {@code ScopeFilterBuilder.build(ctx)}
 * sinh đúng {@code bool.should(terms(scope_group_ids, ctx.groupIds()),
 * term(owner_user_id, ctx.userSub()))} với {@code minimum_should_match=1};
 * {@code ctx.groupIds()} rỗng vẫn sinh đúng vế {@code owner_user_id}.
 */
class ScopeFilterBuilderTest {

    @Test
    void generatesOwnerAndGroupShouldClausesWithMinimumShouldMatchOne() {
        ScopeContext ctx = new ScopeContext("user-1", List.of("group-a", "group-b"));

        Query query = ScopeFilterBuilder.build(ctx);

        assertThat(query.isBool()).isTrue();
        BoolQuery bool = query.bool();
        assertThat(bool.minimumShouldMatch()).isEqualTo("1");
        assertThat(bool.should()).hasSize(2);

        TermQuery ownerClause = bool.should().stream()
                .filter(Query::isTerm)
                .map(Query::term)
                .findFirst()
                .orElseThrow();
        assertThat(ownerClause.field()).isEqualTo("owner_user_id");
        assertThat(ownerClause.value().stringValue()).isEqualTo("user-1");

        TermsQuery groupClause = bool.should().stream()
                .filter(Query::isTerms)
                .map(Query::terms)
                .findFirst()
                .orElseThrow();
        assertThat(groupClause.field()).isEqualTo("scope_group_ids");
        assertThat(groupClause.terms().value()).extracting(v -> v.stringValue())
                .containsExactlyInAnyOrder("group-a", "group-b");
    }

    @Test
    void stillGeneratesTheOwnerClauseWhenGroupIdsIsEmpty() {
        ScopeContext ctx = new ScopeContext("user-1", List.of());

        Query query = ScopeFilterBuilder.build(ctx);

        BoolQuery bool = query.bool();
        assertThat(bool.minimumShouldMatch()).isEqualTo("1");
        // No terms(scope_group_ids) clause at all - an empty `terms` array is not sent, but the
        // owner clause must still be present (never NPE, never silently drop every filter).
        assertThat(bool.should()).hasSize(1);
        assertThat(bool.should().get(0).isTerm()).isTrue();
        assertThat(bool.should().get(0).term().field()).isEqualTo("owner_user_id");
        assertThat(bool.should().get(0).term().value().stringValue()).isEqualTo("user-1");
    }
}
