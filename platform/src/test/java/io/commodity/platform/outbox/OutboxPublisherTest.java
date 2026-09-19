package io.commodity.platform.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import io.commodity.platform.eventing.KafkaMessageSink;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.redpanda.RedpandaContainer;

/** Real Postgres and a real Kafka-compatible broker (Redpanda): the outbox contract end to end. */
@Testcontainers
class OutboxPublisherTest {

    @Container static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");
    @Container static RedpandaContainer redpanda = new RedpandaContainer("docker.redpanda.com/redpandadata/redpanda:v23.3.10");

    JdbcTemplate jdbc;
    TransactionTemplate tx;
    OutboxWriter writer;

    @BeforeEach
    void setUp() {
        var ds = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        jdbc = new JdbcTemplate(ds);
        tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        jdbc.execute("CREATE SCHEMA IF NOT EXISTS demo");
        jdbc.execute("DROP TABLE IF EXISTS demo.outbox");
        // Keep in sync with the outbox table in each service's migration; the service integration tests
        // run the real migration against OutboxWriter, so drift is caught there.
        jdbc.execute("""
                CREATE TABLE demo.outbox (
                  id BIGSERIAL PRIMARY KEY, event_id UUID NOT NULL UNIQUE, topic TEXT NOT NULL, msg_key TEXT NOT NULL,
                  payload TEXT NOT NULL, brd DATE NOT NULL, source TEXT NOT NULL, schema_version INT NOT NULL,
                  traceparent TEXT NOT NULL, occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(), sent_at TIMESTAMPTZ)""");
        writer = new OutboxWriter(jdbc, "demo");
    }

    private KafkaMessageSink kafkaSink() {
        var pf = new DefaultKafkaProducerFactory<String, String>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, redpanda.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class));
        return new KafkaMessageSink(new KafkaTemplate<>(pf));
    }

    private List<ConsumerRecord<String, String>> consume(String topic, int expected) {
        var props = Map.<String, Object>of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, redpanda.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "test-" + topic,
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        List<ConsumerRecord<String, String>> got = new ArrayList<>();
        try (var consumer = new KafkaConsumer<String, String>(props)) {
            consumer.subscribe(List.of(topic));
            long deadline = System.currentTimeMillis() + 20_000;
            while (got.size() < expected && System.currentTimeMillis() < deadline) {
                consumer.poll(Duration.ofMillis(500)).forEach(got::add);
            }
        }
        return got;
    }

    // PROVES: an event written in a transaction reaches Kafka with its key, body and full envelope headers,
    // and the row is marked sent so it is not shipped twice.
    @Test
    void publishesRowsToKafkaWithEnvelopeHeadersAndMarksThemSent() {
        tx.executeWithoutResult(s -> writer.append(
                OutboxMessage.of("demo.topic.v1", "1.1", "{\"hello\":\"world\"}", LocalDate.of(2026, 9, 18), "logistics")));

        var publisher = new OutboxPublisher(jdbc, tx, "demo", kafkaSink(), 100);
        assertThat(publisher.publishBatch()).isEqualTo(1);
        assertThat(publisher.publishBatch()).isZero(); // already sent

        var records = consume("demo.topic.v1", 1);
        assertThat(records).hasSize(1);
        var r = records.get(0);
        assertThat(r.key()).isEqualTo("1.1");
        assertThat(r.value()).isEqualTo("{\"hello\":\"world\"}");
        for (String h : List.of("eventId", "occurredAt", "brd", "source", "schemaVersion", "traceparent")) {
            assertThat(r.headers().lastHeader(h)).as("header " + h).isNotNull();
        }
        assertThat(new String(r.headers().lastHeader("brd").value())).isEqualTo("2026-09-18");
        assertThat(new String(r.headers().lastHeader("traceparent").value())).matches("00-[0-9a-f]{32}-[0-9a-f]{16}-01");
    }

    // PROVES: a rolled-back business transaction leaves NO event behind (the reason the outbox exists).
    @Test
    void rolledBackTransactionPublishesNothing() {
        try {
            tx.executeWithoutResult(s -> {
                writer.append(OutboxMessage.of("demo.topic.v1", "9.9", "{}", LocalDate.of(2026, 9, 18), "logistics"));
                throw new IllegalStateException("business rule failed after the event was recorded");
            });
        } catch (IllegalStateException expected) {
            // the whole transaction, event row included, is rolled back
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM demo.outbox", Integer.class)).isZero();
    }

    // PROVES: delivery failure keeps the row unsent and preserves order; it is retried, not lost.
    @Test
    void failedDeliveryStaysUnsentAndIsRetriedInOrder() {
        for (String key : List.of("a", "b", "c")) {
            tx.executeWithoutResult(s -> writer.append(
                    OutboxMessage.of("demo.topic.v1", key, "{}", LocalDate.of(2026, 9, 18), "logistics")));
        }
        AtomicInteger calls = new AtomicInteger();
        List<String> delivered = new ArrayList<>();
        MessageSink flaky = row -> {
            if (calls.incrementAndGet() == 2) throw new RuntimeException("broker unavailable");
            delivered.add(row.key());
        };
        var publisher = new OutboxPublisher(jdbc, tx, "demo", flaky, 100);

        assertThat(publisher.publishBatch()).isEqualTo(1);      // "a" ok, "b" fails, batch stops before "c"
        assertThat(delivered).containsExactly("a");
        assertThat(publisher.publishBatch()).isEqualTo(2);      // "b" and "c" on the next poll, in order
        assertThat(delivered).containsExactly("a", "b", "c");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM demo.outbox WHERE sent_at IS NULL", Integer.class)).isZero();
    }
}
