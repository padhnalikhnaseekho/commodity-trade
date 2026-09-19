package io.commodity.pricing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.commodity.contracts.events.ChangeKind;
import io.commodity.contracts.events.QagRevisionEvent;
import io.commodity.contracts.events.Topics;
import io.commodity.contracts.lookup.BusinessDayClock;
import io.commodity.contracts.lookup.QuotaDirectory;
import io.commodity.contracts.lookup.QuotaView;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.redpanda.RedpandaContainer;

/**
 * Pricing's consumer end to end: a real broker (Redpanda), a real database, and the real listener, error handler and handler.
 * Covers the delivery guarantees this design relies on: at-least-once with dedup, and poison messages that go to the DLQ
 * without stalling their partition.
 */
@SpringBootTest(properties = {
        "commodity.pricing.consumer.enabled=true",
        "commodity.pricing.consumer.retry-interval-ms=20",       // fast retries: the policy is still "3 retries then DLQ"
        "spring.kafka.consumer.auto-offset-reset=earliest"})
@Testcontainers
class QagRevisionConsumerTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16").withStartupAttempts(5);

    @Container
    static RedpandaContainer redpanda = new RedpandaContainer("docker.redpanda.com/redpandadata/redpanda:v23.3.10").withStartupAttempts(5);

    @DynamicPropertySource
    static void kafka(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", redpanda::getBootstrapServers);
    }

    static final Map<String, QuotaView> QUOTAS = new ConcurrentHashMap<>();

    @TestConfiguration
    static class Ports {
        @Bean QuotaDirectory quotaDirectory() { return ref -> Optional.ofNullable(QUOTAS.get(ref)); }
        @Bean BusinessDayClock clock() { return desk -> LocalDate.of(2026, 9, 18); }
    }

    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;

    private static final LocalDate BRD = LocalDate.of(2026, 9, 18);

    private QagRevisionEvent event(String quotaRef, int assignments, Map<Integer, String> qtyOverrides) {
        QUOTAS.put(quotaRef, new QuotaView(quotaRef, quotaRef.split("\\.")[0], "DESK-1", "CONCENTRATES", new BigDecimal("1000000")));
        List<QagRevisionEvent.Member> members = new ArrayList<>();
        for (int i = 1; i <= assignments; i++) members.add(new QagRevisionEvent.Member(quotaRef + "." + i, new BigDecimal(qtyOverrides.getOrDefault(i, "1000"))));
        return new QagRevisionEvent(UUID.randomUUID(), null, quotaRef, BRD, List.of(ChangeKind.ALLOCATION), List.of(), List.of(), List.of(), members);
    }

    private void send(String key, String payload, UUID eventId) throws Exception {
        var record = new ProducerRecord<>(Topics.QAG_REVISION, key, payload);
        record.headers().add("eventId", eventId.toString().getBytes());
        kafka.send(record).get();
    }

    private void send(QagRevisionEvent e, UUID eventId) throws Exception {
        send(e.quotaRef(), json.writeValueAsString(e), eventId);
    }

    private int count(String sql, Object... args) { return jdbc.queryForObject(sql, Integer.class, args); }

    private int revisions(String quotaRef) { return count("SELECT count(*) FROM pricing.quota_revision WHERE quota_ref = ?", quotaRef); }

    // PROVES the flow: a QAG revision event on Kafka becomes a pricing revision pinned to that QAG revision's id, and a second
    // event that changes ONE assignment shares the other 19 (structural sharing through the real consumer).
    @Test
    void aRevisionEventBecomesAPricingRevisionAndSharesUnchangedAssignments() throws Exception {
        var first = event("301.1", 20, Map.of());
        send(first, UUID.randomUUID());
        await().atMost(Duration.ofSeconds(30)).until(() -> revisions("301.1") == 1);
        assertThat(jdbc.queryForObject("SELECT qagr_id FROM pricing.quota_revision WHERE quota_ref = '301.1'", UUID.class)).isEqualTo(first.qagrId());
        assertThat(count("SELECT count(*) FROM pricing.assignment_revision WHERE assignment_ref LIKE '301.1.%'")).isEqualTo(20);

        send(event("301.1", 20, Map.of(7, "1500")), UUID.randomUUID()); // one assignment modified
        await().atMost(Duration.ofSeconds(30)).until(() -> revisions("301.1") == 2);

        assertThat(count("SELECT count(*) FROM pricing.assignment_revision WHERE assignment_ref LIKE '301.1.%'")).isEqualTo(21); // +1, 19 shared
    }

    // PROVES at-least-once safety end to end: the same event delivered twice creates ONE revision.
    @Test
    void aRedeliveredEventIsAppliedOnce() throws Exception {
        var ev = event("302.1", 3, Map.of());
        UUID eventId = UUID.randomUUID();
        send(ev, eventId);
        send(ev, eventId); // redelivery: same eventId
        send(event("302.1", 3, Map.of(1, "2000")), UUID.randomUUID()); // a later event on the same key, behind the duplicate

        await().atMost(Duration.ofSeconds(30)).until(() -> revisions("302.1") >= 2);
        assertThat(revisions("302.1")).isEqualTo(2); // not 3: the duplicate was skipped
        assertThat(count("SELECT count(*) FROM pricing.processed_event WHERE event_id = ?", eventId)).isEqualTo(1);
    }

    // PROVES the failure policy: a poison message is retried, lands on <topic>.dlq, and does NOT stall the partition: a valid
    // event on the same key behind it is still processed.
    @Test
    void aPoisonMessageGoesToTheDlqWithoutStallingThePartition() throws Exception {
        String poison = "{ this is not json";
        send("303.1", poison, UUID.randomUUID());
        send(event("303.1", 2, Map.of()), UUID.randomUUID()); // same key, so same partition, behind the poison

        await().atMost(Duration.ofSeconds(30)).until(() -> revisions("303.1") == 1);   // the valid event was processed
        assertThat(dlqValues()).contains(poison);                                    // and the poison is parked, not lost
    }

    private List<String> dlqValues() {
        var props = Map.<String, Object>of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, redpanda.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "dlq-reader-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        List<String> values = new ArrayList<>();
        try (var consumer = new KafkaConsumer<String, String>(props)) {
            consumer.subscribe(List.of(Topics.QAG_REVISION + ".dlq"));
            long deadline = System.currentTimeMillis() + 20_000;
            while (values.isEmpty() && System.currentTimeMillis() < deadline) {
                for (ConsumerRecord<String, String> r : consumer.poll(Duration.ofMillis(500))) values.add(r.value());
            }
        }
        return values;
    }
}
