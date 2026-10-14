package com.aismartstore.backend.search.index;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aismartstore.backend.config.EmbeddingProperties;
import com.aismartstore.backend.config.QueueProperties;
import com.aismartstore.backend.config.SemanticProperties;
import com.aismartstore.backend.search.EmbeddingClient;
import com.aismartstore.backend.search.EmbeddingException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

/** SPEC-search-semantic T8 (indexing §5): consumer success path, validation, retry/DLQ. */
class EmbedRequestConsumerTest {

    private final EmbedSegmentLoader loader = mock(EmbedSegmentLoader.class);
    private final EmbeddingClient client = mock(EmbeddingClient.class);
    private final SegmentVectorWriter writer = mock(SegmentVectorWriter.class);
    private final RabbitTemplate rabbit = mock(RabbitTemplate.class);
    private EmbedRequestConsumer consumer;

    private final UUID documentId = UUID.randomUUID();
    private final EmbedRequestMessage message =
            new EmbedRequestMessage(UUID.randomUUID(), documentId, List.of("s1", "s2"), "confirmed", 5L);

    @BeforeEach
    void setUp() {
        EmbeddingProperties props = new EmbeddingProperties("http://x", 32, 500, 30000, "m", "ver-9", 1024, 512, 64, true, "i");
        consumer = new EmbedRequestConsumer(
                loader, client, writer, props, new SemanticProperties(true, 8, 500, 600000, 500, 1, 3, 3600000L),
                new QueueProperties("q.ocr", "q.stt", "q.video", "q.embed"), rabbit);
    }

    private List<EmbedTextBuilder.EmbedItem> items() {
        return List.of(new EmbedTextBuilder.EmbedItem("s1", "mot"), new EmbedTextBuilder.EmbedItem("s2", "hai"));
    }

    @Test
    void writesTheValidatedVectorsWithTheCurrentModelVersionInOrder() throws Exception {
        float[] v1 = {1f};
        float[] v2 = {2f};
        when(loader.load(message)).thenReturn(items());
        when(client.embed(List.of("mot", "hai"))).thenReturn(List.of(v1, v2));
        when(writer.write(any(), any(), eq("ver-9"))).thenReturn(new SegmentVectorWriter.WriteResult(2, List.of(), List.of()));

        consumer.onMessage(message, null);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, float[]>> captor = ArgumentCaptor.forClass(Map.class);
        verify(writer).write(captor.capture(), any(), eq("ver-9"));
        assertThat(captor.getValue()).containsOnlyKeys("s1", "s2");
        assertThat(captor.getValue().get("s2")).isSameAs(v2);
        verify(rabbit, never()).convertAndSend(anyString(), anyString(), any(Object.class));
    }

    @Test
    void nothingEligibleMeansNoEndpointCallAndNoWrite() throws Exception {
        when(loader.load(message)).thenReturn(List.of());

        consumer.onMessage(message, null);

        verify(client, never()).embed(any());
        verify(writer, never()).write(any(), any(), anyString());
    }

    @Test
    void anEndpointFailureNeverWritesAndGoesToTheRetryQueueWithBackoff() throws Exception {
        when(loader.load(message)).thenReturn(items());
        when(client.embed(any())).thenThrow(new EmbeddingException("1023 dims"));

        consumer.onMessage(message, 1);

        verify(writer, never()).write(any(), any(), anyString());
        ArgumentCaptor<MessagePostProcessor> post = ArgumentCaptor.forClass(MessagePostProcessor.class);
        verify(rabbit).convertAndSend(eq("ai.jobs"), eq("q.embed.retry"), eq(message), post.capture());
        Message sent = post.getValue().postProcessMessage(new Message(new byte[0], new MessageProperties()));
        assertThat(sent.getMessageProperties().getExpiration()).isEqualTo("30000"); // 2nd backoff step
        assertThat((Integer) sent.getMessageProperties().getHeader("x-retry-count")).isEqualTo(2);
    }

    @Test
    void afterTheMaxRetriesAFailureGoesToTheDlq() throws Exception {
        when(loader.load(message)).thenReturn(items());
        when(client.embed(any())).thenThrow(new EmbeddingException("down"));

        consumer.onMessage(message, 3);

        verify(rabbit).convertAndSend("ai.jobs.dlx", "q.embed.dlq", message);
        verify(rabbit, never()).convertAndSend(eq("ai.jobs"), eq("q.embed.retry"), any(Object.class), any(MessagePostProcessor.class));
    }

    @Test
    void aFailedBulkUpdateIsRetried() throws Exception {
        when(loader.load(message)).thenReturn(items());
        when(client.embed(any())).thenReturn(List.of(new float[] {1f}, new float[] {2f}));
        when(writer.write(any(), any(), anyString())).thenReturn(new SegmentVectorWriter.WriteResult(1, List.of(), List.of("s2")));

        consumer.onMessage(message, null);

        verify(rabbit).convertAndSend(eq("ai.jobs"), eq("q.embed.retry"), eq(message), any(MessagePostProcessor.class));
    }

    @Test
    void documentsNotIndexedYetAreRetriedThenDroppedWithoutPoisoningTheDlq() throws Exception {
        when(loader.load(message)).thenReturn(items());
        when(client.embed(any())).thenReturn(List.of(new float[] {1f}, new float[] {2f}));
        when(writer.write(any(), any(), anyString())).thenReturn(new SegmentVectorWriter.WriteResult(0, List.of("s1", "s2"), List.of()));

        consumer.onMessage(message, 0);
        verify(rabbit).convertAndSend(eq("ai.jobs"), eq("q.embed.retry"), eq(message), any(MessagePostProcessor.class));

        org.mockito.Mockito.reset(rabbit);
        consumer.onMessage(message, 3); // retries exhausted, still 404 => the document was purged: drop
        verify(rabbit, never()).convertAndSend(anyString(), anyString(), any(Object.class));
    }

    @Test
    void anUnexpectedRuntimeErrorNeverEscapesTheListener() throws Exception {
        when(loader.load(message)).thenThrow(new IllegalStateException("db"));

        consumer.onMessage(message, null);

        verify(rabbit).convertAndSend(eq("ai.jobs"), eq("q.embed.retry"), eq(message), any(MessagePostProcessor.class));
    }

    @Test
    void processingTheSameMessageTwiceIsHarmless() throws Exception {
        when(loader.load(message)).thenReturn(items());
        when(client.embed(any())).thenReturn(List.of(new float[] {1f}, new float[] {2f}));
        when(writer.write(any(), any(), anyString())).thenReturn(new SegmentVectorWriter.WriteResult(2, List.of(), List.of()));

        consumer.onMessage(message, null);
        consumer.onMessage(message, null);

        verify(writer, org.mockito.Mockito.times(2)).write(any(), any(), eq("ver-9"));
        verify(rabbit, never()).convertAndSend(anyString(), anyString(), any(Object.class));
    }
}
