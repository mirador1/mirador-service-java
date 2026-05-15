package org.iris.messaging;

import io.micrometer.observation.ObservationRegistry;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaAdmin.NewTopics;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.serializer.JacksonJsonDeserializer;
import org.springframework.kafka.support.serializer.JacksonJsonSerializer;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link KafkaConfig} — pin the broker / serializer /
 * trusted-packages configuration that's critical for security and
 * data-loss correctness. We assemble the config bean with a fake
 * bootstrap-servers value via reflection (the @Value field) and inspect
 * the produced factories' configuration maps directly.
 *
 * <p>Pinned contracts:
 *   - kafkaTopics() builds three NewTopics with the supplied names
 *   - producer config: String key / Jackson JSON value + type-info headers
 *   - listener consumer config: AUTO_OFFSET_RESET=earliest (no event loss)
 *     + USE_TYPE_INFO_HEADERS=true + TRUSTED_PACKAGES=org.iris.messaging
 *   - kafkaListenerContainerFactory wires reply template + observation
 */
// eslint-disable-next-line max-lines-per-function (Java equivalent — none enforced here)
class KafkaConfigTest {

    private static final String BROKERS = "localhost:9092";

    private KafkaConfig newConfig() {
        KafkaConfig config = new KafkaConfig();
        ReflectionTestUtils.setField(config, "bootstrapServers", BROKERS);
        return config;
    }

    @Test
    void kafkaTopics_buildsThreeTopicsWithSuppliedNames() {
        // Pinned: the @Bean creates the three topics at startup so the
        // first producer.send() doesn't race against broker auto-create.
        // A regression that drops any of the three would surface as a
        // production "topic not found" 30 s into the first test run.
        NewTopics topics = newConfig().kafkaTopics(
                "customer.created",
                "customer.request",
                "customer.reply"
        );

        // getNewTopics() is package-private — reach via reflection.
        @SuppressWarnings("unchecked")
        var collection = (java.util.Collection<org.apache.kafka.clients.admin.NewTopic>)
                ReflectionTestUtils.invokeMethod(topics, "getNewTopics");
        assertThat(collection)
                .extracting(t -> t.name())
                .containsExactlyInAnyOrder("customer.created", "customer.request", "customer.reply");
    }

    @Test
    void producerFactory_usesStringKeyAndJacksonJsonValue() {
        // Pinned: the project's serialization contract is "String keys
        // (typically the customer id), Jackson JSON values with
        // __TypeId__ header" so consumers can dispatch by type. Swapping
        // either serializer silently breaks every downstream consumer.
        ProducerFactory<String, Object> pf = newConfig().producerFactory();

        DefaultKafkaProducerFactory<?, ?> dpf = (DefaultKafkaProducerFactory<?, ?>) pf;
        var configs = dpf.getConfigurationProperties();

        assertThat(configs).containsEntry(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, BROKERS);
        assertThat(configs).containsEntry(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        assertThat(configs).containsEntry(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JacksonJsonSerializer.class);
        // Type headers — required so the consumer side knows what Java class to deserialize to.
        assertThat(configs).containsEntry(JacksonJsonSerializer.ADD_TYPE_INFO_HEADERS, true);
    }

    @Test
    void kafkaTemplate_hasObservationEnabledForTempoSpans() {
        // Pinned: spring.kafka.template.observation-enabled=true in
        // application.yml ONLY affects the auto-configured template. Our
        // manually declared bean MUST set observation programmatically —
        // otherwise outbound Kafka spans never appear in Tempo and the
        // service map shows the app as a black hole between HTTP and DB.
        ProducerFactory<String, Object> pf = newConfig().producerFactory();

        var template = newConfig().kafkaTemplate(pf, ObservationRegistry.create());

        // No public getter — verify via the private field set by setObservationEnabled().
        Boolean enabled = (Boolean) ReflectionTestUtils.getField(template, "observationEnabled");
        assertThat(enabled).isTrue();
    }

    @Test
    void listenerConsumerFactory_usesEarliestOffsetResetAndTrustedPackages() {
        // Pinned: AUTO_OFFSET_RESET=earliest is the data-loss prevention —
        // on first start (no committed offset) we replay all events from
        // the beginning. A switch to "latest" would silently drop every
        // event produced before the consumer first connects. The TRUSTED_PACKAGES
        // restriction is the security gate against deserialization gadget
        // attacks — Jackson would happily instantiate any class on the
        // classpath if we left it open.
        var factory = newConfig().kafkaListenerContainerFactory(
                newConfig().kafkaTemplate(newConfig().producerFactory(), ObservationRegistry.create()),
                ObservationRegistry.create()
        );

        DefaultKafkaConsumerFactory<?, ?> consumerFactory = (DefaultKafkaConsumerFactory<?, ?>) factory.getConsumerFactory();
        var configs = consumerFactory.getConfigurationProperties();

        assertThat(configs).containsEntry(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        assertThat(configs).containsEntry(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        assertThat(configs).containsEntry(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JacksonJsonDeserializer.class);
        assertThat(configs).containsEntry(JacksonJsonDeserializer.USE_TYPE_INFO_HEADERS, "true");
        // The CRITICAL security setting — restricts deserialization to our package only.
        assertThat(configs).containsEntry(JacksonJsonDeserializer.TRUSTED_PACKAGES, "org.iris.messaging");
    }

    @Test
    void kafkaListenerContainerFactory_hasObservationEnabledOnContainerProperties() {
        // Pinned: same rationale as kafkaTemplate observation — incoming
        // Kafka spans must be enabled programmatically on manually
        // declared factories. Without this, the consumer side of the
        // service map is missing.
        var factory = newConfig().kafkaListenerContainerFactory(
                newConfig().kafkaTemplate(newConfig().producerFactory(), ObservationRegistry.create()),
                ObservationRegistry.create()
        );

        assertThat(factory.getContainerProperties().isObservationEnabled()).isTrue();
    }

    // ─── peer.service tag conventions (the 4 anonymous inner classes) ────────

    @Test
    void kafkaTemplate_observationConventionEmitsPeerServiceKafka() {
        // Pinned : the anonymous KafkaTemplateObservationConvention added
        // in kafkaTemplate() must add "peer.service=kafka" so Tempo renders
        // Kafka as a named external node in the service map. A regression
        // here would silently break the service-map graph (the producer
        // span loses its peer hint and Tempo falls back to "unknown").
        var template = newConfig().kafkaTemplate(
                newConfig().producerFactory(), ObservationRegistry.create());
        var convention = (org.springframework.kafka.support.micrometer.KafkaTemplateObservation
                .DefaultKafkaTemplateObservationConvention) ReflectionTestUtils.getField(
                template, "observationConvention");
        assertThat(convention).isNotNull();

        // Build a minimal ProducerRecord context — only the key/value tags
        // we read in the assertions matter ; the convention is pure on the
        // rec's name + headers.
        var rec = new org.apache.kafka.clients.producer.ProducerRecord<>(
                "customer.created", "key-1", (Object) "payload");
        var ctx = new org.springframework.kafka.support.micrometer.KafkaRecordSenderContext(
                rec, "customer.created", () -> BROKERS);

        var keyValues = convention.getLowCardinalityKeyValues(ctx);
        assertThat(keyValues).extracting(io.micrometer.common.KeyValue::getKey)
                .contains("peer.service");
        assertThat(keyValues).extracting(io.micrometer.common.KeyValue::getValue)
                .contains("kafka");
    }

    @Test
    void kafkaListenerContainerFactory_observationConventionEmitsPeerServiceKafka() {
        var factory = newConfig().kafkaListenerContainerFactory(
                newConfig().kafkaTemplate(newConfig().producerFactory(), ObservationRegistry.create()),
                ObservationRegistry.create()
        );
        var convention = (org.springframework.kafka.support.micrometer.KafkaListenerObservation
                .DefaultKafkaListenerObservationConvention) factory.getContainerProperties()
                .getObservationConvention();
        assertThat(convention).isNotNull();

        var rec = new org.apache.kafka.clients.consumer.ConsumerRecord<>(
                "customer.created", 0, 0L, "k", (Object) "v");
        var ctx = new org.springframework.kafka.support.micrometer.KafkaRecordReceiverContext(
                rec, "consumer-id", () -> BROKERS);

        var keyValues = convention.getLowCardinalityKeyValues(ctx);
        assertThat(keyValues).extracting(io.micrometer.common.KeyValue::getKey)
                .contains("peer.service");
        assertThat(keyValues).extracting(io.micrometer.common.KeyValue::getValue)
                .contains("kafka");
    }

    @Test
    void replyingKafkaTemplate_observationConventionEmitsPeerServiceKafka() {
        // The 3rd anonymous KafkaTemplateObservationConvention — used by
        // the request-reply flow's ReplyingKafkaTemplate. Same peer.service
        // tag must apply so the request leg of the request-reply pair
        // shows up as a Kafka peer in Tempo.
        KafkaConfig config = newConfig();
        var container = config.replyListenerContainer("customer.reply", "reply-group");
        @SuppressWarnings({"unchecked", "rawtypes"})
        var template = config.replyingKafkaTemplate(
                (org.springframework.kafka.core.ProducerFactory) config.producerFactory(),
                container,
                ObservationRegistry.create());

        var convention = (org.springframework.kafka.support.micrometer.KafkaTemplateObservation
                .DefaultKafkaTemplateObservationConvention) ReflectionTestUtils.getField(
                template, "observationConvention");
        assertThat(convention).isNotNull();

        var rec = new org.apache.kafka.clients.producer.ProducerRecord<>(
                "customer.request", "key", (Object) "request-payload");
        var ctx = new org.springframework.kafka.support.micrometer.KafkaRecordSenderContext(
                rec, "customer.request", () -> BROKERS);

        var keyValues = convention.getLowCardinalityKeyValues(ctx);
        assertThat(keyValues).extracting(io.micrometer.common.KeyValue::getKey)
                .contains("peer.service");
        assertThat(keyValues).extracting(io.micrometer.common.KeyValue::getValue)
                .contains("kafka");
    }

    @Test
    void replyListenerContainer_observationConventionEmitsPeerServiceKafka() {
        // The 4th anonymous class — same pattern, but on the reply topic
        // ConcurrentMessageListenerContainer's container properties.
        var container = newConfig().replyListenerContainer("customer.reply", "reply-group");
        var convention = (org.springframework.kafka.support.micrometer.KafkaListenerObservation
                .DefaultKafkaListenerObservationConvention) container.getContainerProperties()
                .getObservationConvention();
        assertThat(convention).isNotNull();

        var rec = new org.apache.kafka.clients.consumer.ConsumerRecord<>(
                "customer.reply", 0, 0L, "k", (Object) "v");
        var ctx = new org.springframework.kafka.support.micrometer.KafkaRecordReceiverContext(
                rec, "reply-consumer", () -> BROKERS);

        var keyValues = convention.getLowCardinalityKeyValues(ctx);
        assertThat(keyValues).extracting(io.micrometer.common.KeyValue::getValue)
                .contains("kafka");
    }

    @Test
    void replyListenerContainer_usesLatestOffsetReset() {
        // Pinned: the reply topic uses AUTO_OFFSET_RESET=latest because
        // stale replies (from a previous request cycle) match no current
        // correlation ID — they'd be discarded by ReplyingKafkaTemplate
        // anyway. "latest" avoids wasting CPU on deserializing them.
        // Switching to "earliest" wouldn't break correctness but would
        // produce a 30s startup pause on a topic with backlog.
        KafkaConfig config = new KafkaConfig();
        ReflectionTestUtils.setField(config, "bootstrapServers", BROKERS);

        var container = config.replyListenerContainer("customer.reply", "reply-group");

        // The consumer factory is private to the container; we reach it
        // via reflection to verify the AUTO_OFFSET_RESET config. Less
        // brittle than skipping the assertion altogether — the contract
        // ("latest" on reply topic) is the actual point of the test.
        DefaultKafkaConsumerFactory<?, ?> consumerFactory =
                (DefaultKafkaConsumerFactory<?, ?>) ReflectionTestUtils.getField(container, "consumerFactory");
        var configs = consumerFactory.getConfigurationProperties();

        assertThat(configs).containsEntry(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
        assertThat(configs).containsEntry(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, BROKERS);
        assertThat(container.getContainerProperties().getGroupId()).isEqualTo("reply-group");
    }
}
