package com.aismartstore.backend.search.index;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aismartstore.backend.search.SearchIndexNames;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.ErrorResponse;
import org.opensearch.client.opensearch._types.FieldValue;
import org.opensearch.client.opensearch._types.OpenSearchException;
import org.opensearch.client.opensearch.core.DeleteByQueryRequest;
import org.opensearch.client.opensearch.core.DeleteByQueryResponse;
import org.opensearch.client.opensearch.core.IndexResponse;
import org.opensearch.client.util.ObjectBuilder;

/** search-fulltext-04-dong-bo-chi-muc.md Task 2/8 - same external-versioning contract as {@link SegmentIndexerTest}, single-document shape. */
class DocumentIndexerTest {

    private final OpenSearchClient client = mock(OpenSearchClient.class);
    private final DocumentIndexer indexer = new DocumentIndexer(client);

    @Test
    void upsertDocumentSucceedsWhenTheClientAccepts() throws Exception {
        UUID documentId = UUID.randomUUID();
        when(client.index(any(java.util.function.Function.class)))
                .thenReturn(IndexResponse.of(b -> b
                        .index(SearchIndexNames.DOCUMENT_ALIAS)
                        .id(documentId.toString())
                        .result(org.opensearch.client.opensearch._types.Result.Created)
                        .shards(s -> s.total(1).successful(1).failed(0))
                        .seqNo(1)
                        .primaryTerm(1)
                        .version(1)));

        boolean ok = indexer.upsertDocument(Map.of("document_id", documentId.toString()), 5L);

        assertThat(ok).isTrue();
    }

    @Test
    void upsertDocumentReturnsFalseOnABenignVersionConflict() throws Exception {
        UUID documentId = UUID.randomUUID();
        when(client.index(any(java.util.function.Function.class)))
                .thenThrow(versionConflict());

        boolean ok = indexer.upsertDocument(Map.of("document_id", documentId.toString()), 5L);

        assertThat(ok).isFalse();
    }

    @Test
    void upsertDocumentRethrowsAnyOtherOpenSearchException() throws Exception {
        UUID documentId = UUID.randomUUID();
        when(client.index(any(java.util.function.Function.class)))
                .thenThrow(new OpenSearchException(ErrorResponse.of(
                        b -> b.status(500).error(e -> e.type("some_other_error").reason("boom")))));

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> indexer.upsertDocument(Map.of("document_id", documentId.toString()), 5L))
                .isInstanceOf(OpenSearchException.class);
    }

    /**
     * bugs/bug-002-search-index-version-conflict.md mục 5.1b - the actual
     * root cause of BUG-002: a 409 version_conflict_engine_exception from
     * this single-document {@code index()} call can surface as {@link
     * org.opensearch.client.transport.httpclient5.ResponseException} instead
     * of {@link OpenSearchException} (confirmed on stg, opensearch-java
     * 2.17.0 - the two classes share no inheritance). Before the fix, this
     * exception propagated uncaught, causing 20 useless retries then a
     * permanent PARK. {@code ResponseException} is {@code final} with a
     * package-private constructor (can't be built directly from this test's
     * package), so it is mocked instead - Mockito's inline mock maker (the
     * default since Mockito 5) can mock final classes without a real
     * constructor call.
     */
    @Test
    void upsertDocumentReturnsFalseOnABenignVersionConflictSurfacedAsResponseException() throws Exception {
        UUID documentId = UUID.randomUUID();
        org.opensearch.client.transport.httpclient5.ResponseException exception =
                mock(org.opensearch.client.transport.httpclient5.ResponseException.class);
        when(exception.status()).thenReturn(409);
        when(exception.getMessage()).thenReturn(
                "method [PUT] ... version_conflict_engine_exception ... current version is higher or equal");
        when(client.index(any(java.util.function.Function.class))).thenThrow(exception);

        boolean ok = indexer.upsertDocument(Map.of("document_id", documentId.toString()), 5L);

        assertThat(ok).isFalse();
    }

    /** Only a version-conflict ResponseException is swallowed - any other one (e.g. a real outage) still propagates. */
    @Test
    void upsertDocumentRethrowsAResponseExceptionThatIsNotAVersionConflict() throws Exception {
        UUID documentId = UUID.randomUUID();
        org.opensearch.client.transport.httpclient5.ResponseException exception =
                mock(org.opensearch.client.transport.httpclient5.ResponseException.class);
        when(exception.status()).thenReturn(503);
        when(exception.getMessage()).thenReturn("service unavailable");
        when(client.index(any(java.util.function.Function.class))).thenThrow(exception);

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> indexer.upsertDocument(Map.of("document_id", documentId.toString()), 5L))
                .isInstanceOf(org.opensearch.client.transport.httpclient5.ResponseException.class);
    }

    // ---- trash-search-index-cleanup.md §4.5/Task 2: deleteByIds ------------------------------

    /**
     * trash-search-index-cleanup.md §4.2/§4.5 - one {@code deleteByQuery}
     * call with a {@code terms} filter for the whole batch (never the
     * {@code _id}-keyed delete API in a loop).
     */
    @Test
    void deleteByIdsSendsOneTermsQueryDeleteByQueryForTheWholeBatch() throws Exception {
        when(client.deleteByQuery(any(Function.class))).thenReturn(fakeDeleteByQueryResponse());
        List<UUID> documentIds = List.of(UUID.randomUUID(), UUID.randomUUID());

        indexer.deleteByIds(documentIds);

        ArgumentCaptor<Function<DeleteByQueryRequest.Builder, ObjectBuilder<DeleteByQueryRequest>>> captor =
                ArgumentCaptor.forClass(Function.class);
        verify(client, org.mockito.Mockito.times(1)).deleteByQuery(captor.capture());
        DeleteByQueryRequest request = captor.getValue().apply(new DeleteByQueryRequest.Builder()).build();
        assertThat(request.index()).containsExactly(SearchIndexNames.DOCUMENT_ALIAS);
        assertThat(request.refresh()).isTrue();
        assertThat(request.query().isTerms()).isTrue();
        assertThat(request.query().terms().field()).isEqualTo("document_id");
        assertThat(request.query().terms().terms().value())
                .extracting(FieldValue::stringValue)
                .containsExactlyInAnyOrderElementsOf(documentIds.stream().map(UUID::toString).toList());
    }

    @Test
    void deleteByIdsIsANoOpForAnEmptyList() throws Exception {
        indexer.deleteByIds(List.of());

        verify(client, never()).deleteByQuery(any(Function.class));
    }

    private static DeleteByQueryResponse fakeDeleteByQueryResponse() {
        return DeleteByQueryResponse.of(b -> b.deleted(0L).total(0L).batches(1L).versionConflicts(0L).noops(0L)
                .retries(rb -> rb.bulk(0L).search(0L)).throttledMillis(0L).throttledUntilMillis(0L)
                .requestsPerSecond(-1.0f).timedOut(false));
    }

    private static OpenSearchException versionConflict() {
        return new OpenSearchException(ErrorResponse.of(
                b -> b.status(409).error(e -> e.type("version_conflict_engine_exception").reason("conflict"))));
    }
}
