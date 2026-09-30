package com.aismartstore.backend.search;

import java.util.ArrayList;
import java.util.List;
import org.opensearch.client.opensearch._types.FieldValue;
import org.opensearch.client.opensearch._types.query_dsl.Query;

/**
 * search-fulltext-01-tim-kiem-co-ban.md §1/§4/Task 4 (ai-search-infra.md QĐ
 * 5, rbac-group-scope.md QĐ 3): the single place that turns a {@link
 * ScopeContext} into the OpenSearch {@code filter} clause every {@code
 * segment}/{@code document} query must carry. This is only the <b>first</b>
 * of two checks - {@code SearchResultHydrator} double-checks against
 * Postgres afterwards, since the index's ACL fields can drift (rủi ro Cao SA
 * "A không thấy tài liệu của B").
 */
public final class ScopeFilterBuilder {

    private ScopeFilterBuilder() {
    }

    /**
     * {@code owner_user_id = ctx.userSub() OR scope_group_ids ∩
     * ctx.groupIds() != ∅}, i.e. {@code bool.should(...)} with {@code
     * minimum_should_match = 1}. The {@code owner_user_id} clause is always
     * present, even when {@code ctx.groupIds()} is empty (a user with no
     * group memberships must still see their own documents).
     */
    public static Query build(ScopeContext ctx) {
        List<Query> should = new ArrayList<>();
        should.add(Query.of(q -> q.term(t -> t.field("owner_user_id").value(FieldValue.of(ctx.userSub())))));

        if (!ctx.groupIds().isEmpty()) {
            List<FieldValue> groupValues = ctx.groupIds().stream().map(FieldValue::of).toList();
            should.add(Query.of(q -> q.terms(t -> t.field("scope_group_ids").terms(tf -> tf.value(groupValues)))));
        }

        return Query.of(q -> q.bool(b -> b.should(should).minimumShouldMatch("1")));
    }
}
