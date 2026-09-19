package io.commodity.stubs.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.commodity.contracts.events.Topics;
import io.commodity.contracts.events.ValuationCompleted;
import io.commodity.contracts.events.ValuationRequested;
import io.commodity.contracts.valuation.Lane;
import io.commodity.contracts.valuation.SubjectLevel;
import io.commodity.contracts.valuation.ValuationEngine;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.redpanda.RedpandaContainer;

/**
 * The engine adapter over a real broker: request in, reply out. The adapter's module depends on no other service, and the request carries all
 * its inputs, so this test needs nothing but a broker.
 */
@SpringBootTest(classes = StubEngineAdapterTest.App.class, properties = {
        "commodity.engine.enabled=true",
        "commodity.engine.delay-ms.interactive=50",
        "commodity.engine.failure-rate=1.0",       // every request is selected to fail...
        "commodity.engine.fail-attempts=1",        // ...on its first attempt only
        "spring.kafka.consumer.auto-offset-reset=earliest"})
@Testcontainers
class StubEngineAdapterTest {

    @SpringBootApplication(scanBasePackages = "io.commodity.stubs.engine")
    static class App {}

    @Container
    static RedpandaContainer redpanda = new RedpandaContainer("docker.redpanda.com/redpandadata/redpanda:v23.3.10");

    @DynamicPropertySource
    static void kafka(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", redpanda::getBootstrapServers);
    }

    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired ObjectMapper json;

    private ValuationRequested request(UUID id, int attempt) {
        return new ValuationRequested(id, "sha256:test", "1.1.3", SubjectLevel.ASSIGNMENT, LocalDate.of(2026, 9, 18), "RM_MODERN", ValuationEngine.MODERN,
                Lane.INTERACTIVE, attempt, UUID.randomUUID(), UUID.randomUUID(),
                new ValuationRequested.Inputs(new BigDecimal("250.0000"), List.of(), List.of()));
    }

    private List<ValuationCompleted> replies(UUID id, int expected) {
        List<ValuationCompleted> got = new CopyOnWriteArrayList<>();
        var props = Map.<String, Object>of(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, redpanda.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "reader-" + UUID.randomUUID(), ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class, ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        try (var consumer = new KafkaConsumer<String, String>(props)) {
            consumer.subscribe(List.of(Topics.VALUATION_REPLY));
            long deadline = System.currentTimeMillis() + 30_000;
            while (got.size() < expected && System.currentTimeMillis() < deadline) {
                consumer.poll(Duration.ofMillis(300)).forEach(r -> {
                    if (r.key().equals(id.toString())) {
                        try { got.add(json.readValue(r.value(), ValuationCompleted.class)); } catch (Exception e) { throw new IllegalStateException(e); }
                    }
                });
            }
        }
        return got;
    }

    // PROVES the adapter end to end: a complete request in, a correct and deterministic reply out, keyed by requestId. The first attempt
    // fails (knob) and the retry succeeds, and the successful value is exactly the documented formula.
    @Test
    void repliesWithAnErrorOnTheFirstAttemptAndTheDeterministicValueOnTheRetry() throws Exception {
        UUID id = UUID.randomUUID();
        kafka.send(Topics.VALUATION_REQUEST, id.toString(), json.writeValueAsString(request(id, 1))).get();
        kafka.send(Topics.VALUATION_REQUEST, id.toString(), json.writeValueAsString(request(id, 2))).get();

        var replies = replies(id, 2);
        await().atMost(Duration.ofSeconds(30)).until(() -> replies.size() >= 2);

        assertThat(replies).anySatisfy(r -> {
            assertThat(r.success()).isFalse();
            assertThat(r.error()).contains("attempt 1");
        });
        assertThat(replies).anySatisfy(r -> {
            assertThat(r.success()).isTrue();
            assertThat(new BigDecimal(r.result())).isEqualByComparingTo(StubEngine.value(new BigDecimal("250"), "1.1.3", new BigDecimal("100")));
            assertThat(r.engine()).isEqualTo(ValuationEngine.MODERN);
            assertThat(r.requestKey()).isEqualTo("sha256:test");
        });
    }
}
