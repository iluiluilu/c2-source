package com.aismartstore.backend.search.index;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.ErrorCause;
import org.opensearch.client.opensearch._types.Refresh;
import org.opensearch.client.opensearch.core.BulkRequest;
import org.opensearch.client.opensearch.core.BulkResponse;
import org.opensearch.client.opensearch.core.bulk.BulkResponseItem;
import org.opensearch.client.opensearch.core.bulk.OperationType;

/** Large documents are written in several bulk requests (OpenSearch 429 on one ~57 MB bulk, stg 2026-10-12). */
class SegmentVectorWriterTest {

    private final OpenSearchClient client = mock(OpenSearchClient.class);
    private final SegmentVectorWriter writer = new SegmentVectorWriter(client);

    @Test
    void splitsIntoChunksAndRefreshesOnlyWithTheLastOne() throws IOException {
        when(client.bulk(any(BulkRequest.class))).thenAnswer(inv -> ok(inv.getArgument(0)));

        SegmentVectorWriter.WriteResult result = writer.write(vectors(250), "m1");

        ArgumentCaptor<BulkRequest> captor = ArgumentCaptor.forClass(BulkRequest.class);
        verify(client, times(3)).bulk(captor.capture());
        List<BulkRequest> requests = captor.getAllValues();
        assertThat(requests).extracting(r -> r.operations().size()).containsExactly(100, 100, 50);
        assertThat(requests).extracting(BulkRequest::refresh)
                .containsExactly(Refresh.False, Refresh.False, Refresh.True);
        assertThat(requests.get(2).operations().get(49).update().id()).isEqualTo("s249");
        assertThat(result.updated()).isEqualTo(250);
        assertThat(result.ok()).isTrue();
    }

    @Test
    void aggregatesMissingAndFailedAcrossChunks() throws IOException {
        when(client.bulk(any(BulkRequest.class))).thenAnswer(inv -> {
            BulkRequest request = inv.getArgument(0);
            List<BulkResponseItem> items = new ArrayList<>();
            request.operations().forEach(op -> {
                String id = op.update().id();
                int status = id.equals("s5") ? 404 : id.equals("s150") ? 500 : 200;
                items.add(item(id, status));
            });
            return BulkResponse.of(b -> b.items(items).errors(true).took(1));
        });

        SegmentVectorWriter.WriteResult result = writer.write(vectors(160), "m1");

        assertThat(result.updated()).isEqualTo(158);
        assertThat(result.missing()).containsExactly("s5");
        assertThat(result.failed()).containsExactly("s150");
    }

    @Test
    void sendsAConditionalScriptWithTheHashAndCountsNoopAsSkipped() throws IOException {
        io.micrometer.core.instrument.simple.SimpleMeterRegistry registry =
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        SegmentVectorWriter metered = new SegmentVectorWriter(client, registry);
        when(client.bulk(any(BulkRequest.class))).thenAnswer(inv -> {
            BulkRequest request = inv.getArgument(0);
            List<BulkResponseItem> items = new ArrayList<>();
            request.operations().forEach(op -> {
                String id = op.update().id();
                items.add(BulkResponseItem.of(b -> b.operationType(OperationType.Update).index("segment-v2")
                        .id(id).status(200).result(id.equals("s1") ? "noop" : "updated")));
            });
            return BulkResponse.of(b -> b.items(items).errors(false).took(1));
        });

        SegmentVectorWriter.WriteResult result =
                metered.write(vectors(3), Map.of("s0", "h0", "s1", "h1", "s2", "h2"), "m1");

        ArgumentCaptor<BulkRequest> captor = ArgumentCaptor.forClass(BulkRequest.class);
        verify(client).bulk(captor.capture());
        var update = captor.getValue().operations().get(0).update();
        org.opensearch.client.json.jackson.JacksonJsonpMapper mapper = new org.opensearch.client.json.jackson.JacksonJsonpMapper();
        StringBuilder body = new StringBuilder();
        for (var it = update._serializables(); it.hasNext(); ) {
            Object part = it.next();
            if (part instanceof org.opensearch.client.json.JsonpSerializable serializable) {
                java.io.StringWriter out = new java.io.StringWriter();
                try (jakarta.json.stream.JsonGenerator generator = mapper.jsonProvider().createGenerator(out)) {
                    serializable.serialize(generator, mapper);
                }
                body.append(out);
            }
        }
        assertThat(body.toString()).contains("ctx.op = 'noop'").contains("embed_text_hash")
                .contains("\"vec\"").contains("\"model\"").contains("\"hash\":\"h0\"");
        assertThat(result.updated()).isEqualTo(2);
        assertThat(result.skipped()).isEqualTo(1);
        assertThat(result.ok()).isTrue();
        assertThat(registry.counter("semantic.vector.write.skipped").count()).isEqualTo(1.0);
    }

    @Test
    void emptyInputSendsNothing() throws IOException {
        assertThat(writer.write(Map.of(), "m1").updated()).isZero();
        verify(client, never()).bulk(any(BulkRequest.class));
    }

    private static Map<String, float[]> vectors(int n) {
        Map<String, float[]> map = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            map.put("s" + i, new float[] {1f});
        }
        return map;
    }

    private static BulkResponse ok(BulkRequest request) {
        List<BulkResponseItem> items = new ArrayList<>();
        request.operations().forEach(op -> items.add(item(op.update().id(), 200)));
        return BulkResponse.of(b -> b.items(items).errors(false).took(1));
    }

    private static BulkResponseItem item(String id, int status) {
        return BulkResponseItem.of(b -> {
            b.operationType(OperationType.Update).index("segment-v2").id(id).status(status);
            if (status != 200) {
                b.error(ErrorCause.of(e -> e.type("x").reason("boom")));
            }
            return b;
        });
    }
}
