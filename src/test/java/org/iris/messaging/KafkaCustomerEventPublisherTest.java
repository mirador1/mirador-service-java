package org.iris.messaging;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the Kafka adapter of {@link org.iris.customer.port.CustomerEventPort}.
 *
 * <p>Tests the synchronous-send + timeout + exception-translation logic
 * that {@link KafkaCustomerEventPublisher} adds on top of the raw
 * {@link KafkaTemplate}. Does NOT test the Resilience4j {@code @Retry}
 * decorator — that annotation only activates through the Spring AOP
 * proxy, which would require a {@code @SpringBootTest} context. Integration
 * tests in {@code KafkaPatternITest} already cover the end-to-end retry
 * behaviour against a Testcontainers Kafka.
 */
@ExtendWith(MockitoExtension.class)
class KafkaCustomerEventPublisherTest {

    private static final String TOPIC = "customer.created";

    @Mock
    private KafkaTemplate<String, Object> kafkaTemplate;

    private KafkaCustomerEventPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new KafkaCustomerEventPublisher(kafkaTemplate, TOPIC);
    }

    @Test
    void publishCreated_sendsToConfiguredTopicWithIdAsKey() {
        SendResult<String, Object> sendResult = stubSendResult();
        when(kafkaTemplate.send(anyString(), anyString(), any()))
                .thenReturn(CompletableFuture.completedFuture(sendResult));

        publisher.publishCreated(42L, "Alice", "alice@example.com");

        ArgumentCaptor<String> topicCap = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> keyCap = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object> eventCap = ArgumentCaptor.forClass(Object.class);
        verify(kafkaTemplate).send(topicCap.capture(), keyCap.capture(), eventCap.capture());

        // Topic is injected from @Value — adapter owns the binding, domain doesn't.
        assertThat(topicCap.getValue()).isEqualTo(TOPIC);
        // Key is the customer id stringified — guarantees same-customer events
        // go to the same Kafka partition, preserving ordering per customer.
        assertThat(keyCap.getValue()).isEqualTo("42");
        // Event is a CustomerCreatedEvent built from the port primitives.
        assertThat(eventCap.getValue()).isInstanceOf(CustomerCreatedEvent.class);
        CustomerCreatedEvent event = (CustomerCreatedEvent) eventCap.getValue();
        assertThat(event.id()).isEqualTo(42L);
        assertThat(event.name()).isEqualTo("Alice");
        assertThat(event.email()).isEqualTo("alice@example.com");
    }

    @Test
    void publishCreated_timeoutUnwrapsAsIllegalStateException() {
        // Simulate a Kafka send that never ack's — the .get(timeout) throws
        // TimeoutException which the adapter must translate.
        CompletableFuture<SendResult<String, Object>> stuck = failedFutureFromGet(new TimeoutException("ack timeout"));
        when(kafkaTemplate.send(anyString(), anyString(), any())).thenReturn(stuck);

        assertThatThrownBy(() -> publisher.publishCreated(7L, "Bob", "bob@example.com"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Kafka publish failed")
                .hasMessageContaining("topic=" + TOPIC)
                .hasMessageContaining("key=7");
    }

    @Test
    void publishCreated_executionExceptionUnwrapsAsIllegalStateException() {
        CompletableFuture<SendResult<String, Object>> failed = failedFutureFromGet(new ExecutionException(new RuntimeException("broker down")));
        when(kafkaTemplate.send(anyString(), anyString(), any())).thenReturn(failed);

        assertThatThrownBy(() -> publisher.publishCreated(9L, "Carol", "carol@example.com"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Kafka publish failed")
                .hasMessageContaining("key=9");
    }

    @Test
    void publishCreatedFallback_logsSilentlyWithoutThrowing() {
        // Fallback is called reflectively by Resilience4j when retries are
        // exhausted. It must NEVER throw — the customer row is already
        // persisted, the event loss is accepted.
        Throwable rootCause = new RuntimeException("connection refused");

        // Method is package-private for exactly this testability reason.
        // Wrapped in assertThatNoException so Sonar S2699 sees an explicit
        // assertion. The contract is "swallow + log, never throw".
        assertThatNoException().isThrownBy(() ->
                publisher.publishCreatedFallback(100L, "Dave", "dave@example.com", rootCause));
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    /**
     * Creates a realistic SendResult for happy-path tests. The content of
     * the metadata doesn't matter to the adapter — it only awaits the
     * future; test asserts focus on the send() arguments.
     */
    private static SendResult<String, Object> stubSendResult() {
        ProducerRecord<String, Object> rec = new ProducerRecord<>(TOPIC, "key", new Object());
        RecordMetadata meta = new RecordMetadata(new TopicPartition(TOPIC, 0), 0, 0, 0, 0, 0);
        return new SendResult<>(rec, meta);
    }

    /**
     * Returns a CompletableFuture that throws {@code cause} from
     * {@code .get(timeout, unit)}. Wraps the cause so the future's
     * internal state matches what Kafka's real failed send would produce.
     */
    private static CompletableFuture<SendResult<String, Object>> failedFutureFromGet(Throwable cause) {
        CompletableFuture<SendResult<String, Object>> f = new CompletableFuture<>() {
            @Override
            public SendResult<String, Object> get(long timeout, TimeUnit unit) throws ExecutionException, TimeoutException {
                if (cause instanceof TimeoutException te) throw te;
                if (cause instanceof ExecutionException ee) throw ee;
                throw new ExecutionException(cause);
            }
        };
        // Avoid leaving the future in pending state when it's GC'd
        f.completeExceptionally(cause);
        return f;
    }
}
