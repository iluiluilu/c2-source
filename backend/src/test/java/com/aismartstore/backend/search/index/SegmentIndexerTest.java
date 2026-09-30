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
import org.opensearch.client.opensearch._types.FieldValue;
import org.opensearch.client.opensearch._types.VersionType;
import org.opensearch.client.opensearch.core.BulkRequest;
import org.opensearch.client.opensearch.core.BulkResponse;
import org.opensearch.client.opensearch.core.DeleteByQueryRequest;
import org.opensearch.client.opensearch.core.DeleteByQueryResponse;
import org.opensearch.client.opensearch.core.bulk.BulkOperation;
import org.opensearch.client.opensearch.core.bulk.BulkResponseItem;
import org.opensearch.client.opensearch.core.bulk.IndexOperation;
import org.opensearch.client.util.ObjectBuilder;

/**
 * search-fulltext-04-dong-bo-chi-muc.md Task 2/8 - {@link SegmentIndexer}'s
 * external-versioning contract (§4.2 intro): a version-conflict bulk item is
 * benign (a later event already won), any other error is a real failure the
 * poller must retry.
 */
class SegmentIndexerTest {

    private final OpenSearchClient client = mock(OpenSearchClient.class);
    private static final int CHUNK_SIZE = 500;

    private final SegmentIndexer indexer = new SegmentIndexer(client, CHUNK_SIZE);

    @Test
    void upsertSegmentsIsANoOpForAnEmptyList() throws Exception {
        boolean ok = indexer.upsertSegments(List.of(), 42L);

        assertThat(ok).isTrue();
        verify(client, org.mockito.Mockito.never()).bulk(any(BulkRequest.class));
    }

    @Test
    void upsertSegmentsSendsOneIndexOperationPerSourceWithTheExternalVersion() throws Exception {
        when(client.bulk(any(BulkRequest.class))).thenReturn(fakeBulkResponse(false, null));

        String segmentId = UUID.randomUUID().toString();
        Map<String, Object> source = Map.of("segment_id", segmentId, "document_id", UUID.randomUUID().toString());

        boolean ok = indexer.upsertSegments(List.of(source), 7L);

        assertThat(ok).isTrue();
        ArgumentCaptor<BulkRequest> captor = ArgumentCaptor.forClass(BulkRequest.class);
        verify(client).bulk(captor.capture());
        List<BulkOperation> operations = captor.getValue().operations();
        assertThat(operations).hasSize(1);
        IndexOperation<?> indexOp = operations.get(0).index();
        assertThat(indexOp.index()).isEqualTo(SearchIndexNames.SEGMENT_ALIAS);
        assertThat(indexOp.id()).isEqualTo(segmentId);
        assertThat(indexOp.version()).isEqualTo(7L);
        // bugs/bug-002-search-index-version-conflict.md muc 5.2 - ExternalGte (not External):
        // accepts a retry that resends the exact same version (Postgres unchanged between attempts).
        assertThat(indexOp.versionType()).isEqualTo(VersionType.ExternalGte);
    }

    @Test
    void aVersionConflictBulkErrorIsNotTreatedAsAFailure() throws Exception {
        when(client.bulk(any(BulkRequest.class))).thenReturn(fakeBulkResponse(true, "version_conflict_engine_exception"));

        boolean ok = indexer.upsertSegments(List.of(Map.of("segment_id", "s1")), 1L);

        assertThat(ok).isTrue();
    }

    @Test
    void aRealBulkErrorIsTreatedAsAFailure() throws Exception {
        when(client.bulk(any(BulkRequest.class))).thenReturn(fakeBulkResponse(true, "mapper_parsing_exception"));

        boolean ok = indexer.upsertSegments(List.of(Map.of("segment_id", "s1")), 1L);

        assertThat(ok).isFalse();
    }

    private static BulkResponse fakeBulkResponse(boolean hasErrors, String errorType) {
        BulkResponseItem.Builder itemBuilder = new BulkResponseItem.Builder()
                .index(SearchIndexNames.SEGMENT_ALIAS)
                .id("s1")
                .status(hasErrors ? 409 : 200)
                .operationType(org.opensearch.client.opensearch.core.bulk.OperationType.Index);
        if (hasErrors) {
            itemBuilder.error(e -> e.type(errorType).reason("boom"));
        }
        return BulkResponse.of(b -> b.errors(hasErrors).took(1).items(List.of(itemBuilder.build())));
    }

    // ---- §4.4.2: bulk chunking ---------------------------------------------

    /**
     * §4.4.2 - the whole point of the change: a run's segments must never go
     * out as one unbounded BulkRequest. Uses a small indexer so the test does
     * not have to build thousands of sources to cross the boundary.
     */
    @Test
    void upsertSegmentsSplitsIntoChunksAndSendsEverySegmentExactlyOnce() throws Exception {
        when(client.bulk(any(BulkRequest.class))).thenReturn(fakeBulkResponse(false, null));
        SegmentIndexer smallChunks = new SegmentIndexer(client, 10);
        List<Map<String, Object>> sources = sources(21); // 2 full chunks + a remainder of 1

        boolean ok = smallChunks.upsertSegments(sources, 5L);

        assertThat(ok).isTrue();
        ArgumentCaptor<BulkRequest> captor = ArgumentCaptor.forClass(BulkRequest.class);
        verify(client, org.mockito.Mockito.times(3)).bulk(captor.capture());
        assertThat(captor.getAllValues()).extracting(r -> r.operations().size()).containsExactly(10, 10, 1);

        List<String> sentIds = captor.getAllValues().stream()
                .flatMap(r -> r.operations().stream())
                .map(op -> op.index().id())
                .toList();
        assertThat(sentIds)
                .hasSize(21)
                .doesNotHaveDuplicates()
                .containsExactlyElementsOf(sources.stream().map(m -> (String) m.get("segment_id")).toList());
    }

    /** A list that fits in one chunk must still go out as exactly one request (no behaviour change). */
    @Test
    void upsertSegmentsSendsASingleRequestWhenEverythingFitsInOneChunk() throws Exception {
        when(client.bulk(any(BulkRequest.class))).thenReturn(fakeBulkResponse(false, null));
        SegmentIndexer smallChunks = new SegmentIndexer(client, 10);

        assertThat(smallChunks.upsertSegments(sources(10), 5L)).isTrue();

        verify(client, org.mockito.Mockito.times(1)).bulk(any(BulkRequest.class));
    }

    /**
     * §4.4.2 - one bad chunk must fail the whole call (so the poller retries
     * the event) without stopping the remaining chunks: leaving more of the
     * run un-indexed would buy nothing, since the retry re-sends all of it.
     */
    @Test
    void upsertSegmentsReportsFailureButStillSendsTheRemainingChunks() throws Exception {
        when(client.bulk(any(BulkRequest.class)))
                .thenReturn(fakeBulkResponse(false, null))
                .thenReturn(fakeBulkResponse(true, "mapper_parsing_exception"))
                .thenReturn(fakeBulkResponse(false, null));
        SegmentIndexer smallChunks = new SegmentIndexer(client, 10);

        assertThat(smallChunks.upsertSegments(sources(21), 5L)).isFalse();

        verify(client, org.mockito.Mockito.times(3)).bulk(any(BulkRequest.class));
    }

    /**
     * §4.4.2 - why retrying a partially-applied set needs no extra bookkeeping:
     * chunks that already landed come back as version conflicts, which are
     * benign, so the retry still reports success.
     */
    @Test
    void retryingAPartiallyAppliedSetSucceedsBecauseLandedChunksOnlyConflict() throws Exception {
        when(client.bulk(any(BulkRequest.class)))
                .thenReturn(fakeBulkResponse(true, "version_conflict_engine_exception"))
                .thenReturn(fakeBulkResponse(false, null))
                .thenReturn(fakeBulkResponse(false, null));
        SegmentIndexer smallChunks = new SegmentIndexer(client, 10);

        assertThat(smallChunks.upsertSegments(sources(21), 5L)).isTrue();
    }

    // ---- trash-search-index-cleanup.md §4.5/Task 2: deleteByDocumentIds ---------------------

    /**
     * trash-search-index-cleanup.md §4.2/§4.5 - one {@code deleteByQuery}
     * call with a {@code terms} filter for the whole batch, never a loop
     * calling the API once per id.
     */
    @Test
    void deleteByDocumentIdsSendsOneTermsQueryDeleteByQueryForTheWholeBatch() throws Exception {
        when(client.deleteByQuery(any(Function.class))).thenReturn(fakeDeleteByQueryResponse());
        List<UUID> documentIds = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

        indexer.deleteByDocumentIds(documentIds);

        ArgumentCaptor<Function<DeleteByQueryRequest.Builder, ObjectBuilder<DeleteByQueryRequest>>> captor =
                ArgumentCaptor.forClass(Function.class);
        verify(client, org.mockito.Mockito.times(1)).deleteByQuery(captor.capture());
        DeleteByQueryRequest request = captor.getValue().apply(new DeleteByQueryRequest.Builder()).build();
        assertThat(request.index()).containsExactly(SearchIndexNames.SEGMENT_ALIAS);
        assertThat(request.refresh()).isTrue();
        assertThat(request.query().isTerms()).isTrue();
        assertThat(request.query().terms().field()).isEqualTo("document_id");
        assertThat(request.query().terms().terms().value())
                .extracting(FieldValue::stringValue)
                .containsExactlyInAnyOrderElementsOf(documentIds.stream().map(UUID::toString).toList());
    }

    /** An empty batch must not call the client at all - nothing to delete, and an empty `terms` filter is invalid anyway. */
    @Test
    void deleteByDocumentIdsIsANoOpForAnEmptyList() throws Exception {
        indexer.deleteByDocumentIds(List.of());

        verify(client, never()).deleteByQuery(any(Function.class));
    }

    private static DeleteByQueryResponse fakeDeleteByQueryResponse() {
        return DeleteByQueryResponse.of(b -> b.deleted(0L).total(0L).batches(1L).versionConflicts(0L).noops(0L)
                .retries(rb -> rb.bulk(0L).search(0L)).throttledMillis(0L).throttledUntilMillis(0L)
                .requestsPerSecond(-1.0f).timedOut(false));
    }

    private static List<Map<String, Object>> sources(int count) {
        return java.util.stream.IntStream.range(0, count)
                .mapToObj(i -> Map.<String, Object>of("segment_id", "seg-" + i, "document_id", "doc"))
                .toList();
    }
}
