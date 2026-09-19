package io.commodity.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.jayway.jsonpath.JsonPath;
import io.commodity.contracts.events.Topics;
import io.commodity.contracts.valuation.ValuationEngine;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.redpanda.RedpandaContainer;

/**
 * The valuation gateway end to end: real Postgres, real broker, the real dispatcher, outbox, reply listener and watchdog, with a controllable
 * fake engine on the other side. Each test uses its own subjects, so tests are independent of order.
 */
@SpringBootTest(classes = GatewayTestApp.class, properties = {
        "commodity.gateway.workers.enabled=true",
        "commodity.gateway.dispatch-interval-ms=50",
        "commodity.gateway.watchdog-interval-ms=200",
        "commodity.gateway.lanes.interactive.timeout-ms=800",     // short, so watchdog behaviour is quick to observe
        "commodity.gateway.consumer.retry-interval-ms=20",
        "commodity.outbox.poll-ms=50",
        "spring.kafka.consumer.auto-offset-reset=earliest"})
@AutoConfigureMockMvc
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class GatewayFlowTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Container
    static RedpandaContainer redpanda = new RedpandaContainer("docker.redpanda.com/redpandadata/redpanda:v23.3.10");

    @DynamicPropertySource
    static void kafka(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", redpanda::getBootstrapServers);
    }

    static FakeEngine engine;
    static final AtomicInteger NEXT = new AtomicInteger(400);

    @BeforeAll
    static void startEngine() { engine = new FakeEngine(redpanda.getBootstrapServers()); }

    @AfterAll
    static void stopEngine() throws Exception { engine.close(); }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    /** A fresh approved assignment on its own quota; returns its ref. */
    private String subject(String qty, boolean approved, String override) {
        return Fakes.assignment(NEXT.incrementAndGet() + ".1", 1, qty, approved, override);
    }

    private static String body(String ref, String brd, String lane, boolean restatement) {
        return "{\"subjectRef\":\"" + ref + "\",\"subjectLevel\":\"ASSIGNMENT\",\"brd\":\"" + brd + "\",\"lane\":\"" + lane + "\",\"restatement\":" + restatement + "}";
    }

    private String submit(String ref, String brd, String lane) throws Exception {
        return mvc.perform(post("/api/valuations").contentType(MediaType.APPLICATION_JSON).content(body(ref, brd, lane, false)))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
    }

    private String statusOf(String requestId) throws Exception {
        return JsonPath.read(mvc.perform(get("/api/valuations/" + requestId)).andReturn().getResponse().getContentAsString(), "$.status");
    }

    private void awaitStatus(String requestId, String expected) {
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(100)).until(() -> statusOf(requestId).equals(expected));
    }

    // PROVES acceptance: submitting the same request twice calls the engine ONCE; the second returns from the cache with cached:true.
    @Test
    void submittingTheSameRequestTwiceCallsTheEngineOnce() throws Exception {
        String ref = subject("250", true, null);
        String first = submit(ref, "2026-09-18", "INTERACTIVE");
        String id = JsonPath.read(first, "$.requestId"), key = JsonPath.read(first, "$.requestKey");
        awaitStatus(id, "COMPLETED");

        mvc.perform(get("/api/valuations/" + id)).andExpect(jsonPath("$.result.value").value("25000.0000")).andExpect(jsonPath("$.attempts").value(1));
        mvc.perform(post("/api/valuations").contentType(MediaType.APPLICATION_JSON).content(body(ref, "2026-09-18", "INTERACTIVE", false)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cached").value(true)).andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.result.value").value("25000.0000")).andExpect(jsonPath("$.requestKey").value(key));

        assertThat(engine.callsFor(key)).isEqualTo(1);
    }

    // PROVES the engine receives a COMPLETE request: quantity, components and parameters are inside it (no lookups needed), and the
    // request carries the ids the key was built from.
    @Test
    void theEngineRequestIsSelfContained() throws Exception {
        String ref = subject("80", true, null);
        String id = JsonPath.read(submit(ref, "2026-09-18", "INTERACTIVE"), "$.requestId");
        awaitStatus(id, "COMPLETED");

        var sent = engine.received.stream().filter(r -> r.requestId().toString().equals(id)).findFirst().orElseThrow();
        assertThat(sent.inputs().qty()).isEqualByComparingTo("80");
        assertThat(sent.inputs().components()).hasSize(2).allSatisfy(c -> assertThat(c.pcId()).isNotNull());
        assertThat(sent.inputs().parameters()).hasSize(1);
        assertThat(sent.pqrId()).isNotNull();
        assertThat(sent.parId()).isNotNull();
        assertThat(sent.attempt()).isEqualTo(1);
        assertThat(sent.functionalLine()).isEqualTo("RM_MODERN");
    }

    // PROVES in-flight idempotency: an identical request submitted while the first is still running attaches to it instead of duplicating work.
    @Test
    void anIdenticalRequestWhileOneIsInFlightCollapsesIntoIt() throws Exception {
        String ref = subject("10", true, null);
        var holding = new java.util.concurrent.atomic.AtomicBoolean(true);
        engine.behavior = r -> r.subjectRef().equals(ref) && holding.get() ? FakeEngine.Action.hold() : FakeEngine.Action.reply(50);

        String first = submit(ref, "2026-09-18", "INTERACTIVE");
        String second = submit(ref, "2026-09-18", "INTERACTIVE");
        String id = JsonPath.read(first, "$.requestId"), key = JsonPath.read(first, "$.requestKey");
        assertThat(JsonPath.<String>read(second, "$.requestId")).isEqualTo(id); // the same request, not a second one

        await().atMost(Duration.ofSeconds(30)).until(() -> engine.callsFor(key) == 1);
        holding.set(false);
        engine.releaseHeld();
        awaitStatus(id, "COMPLETED");
        engine.behavior = r -> FakeEngine.Action.reply(50);

        assertThat(engine.callsFor(key)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM gateway.valuation_request WHERE request_key = ?", Integer.class, key)).isEqualTo(1);
    }

    // PROVES acceptance: a request whose BRD is closed for the desk is rejected (409), unless flagged as a restatement, and a request
    // that is already in the cache is served even for a closed BRD (that is what makes replaying a past date a cache hit).
    @Test
    void aClosedBrdIsRejectedUnlessRestatedAndACachedAnswerIsStillServed() throws Exception {
        String ref = subject("20", true, null);
        String closed = "2026-09-17"; // the desk is on 2026-09-18

        mvc.perform(post("/api/valuations").contentType(MediaType.APPLICATION_JSON).content(body(ref, closed, "INTERACTIVE", false)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://commodity.demo/errors/brd-closed"))
                .andExpect(jsonPath("$.brd").value(closed)).andExpect(jsonPath("$.deskBrd").value("2026-09-18"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM gateway.valuation_request WHERE subject_ref = ?", Integer.class, ref)).isZero(); // nothing was stored

        String restated = mvc.perform(post("/api/valuations").contentType(MediaType.APPLICATION_JSON).content(body(ref, closed, "INTERACTIVE", true)))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        awaitStatus(JsonPath.read(restated, "$.requestId"), "COMPLETED");

        mvc.perform(post("/api/valuations").contentType(MediaType.APPLICATION_JSON).content(body(ref, closed, "INTERACTIVE", false)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.cached").value(true)); // replay of a past date: a cache hit
    }

    // PROVES the owner rule: only approved assignments are eligible for valuation.
    @Test
    void unapprovedAssignmentsAreNotEligibleForValuation() throws Exception {
        String ref = subject("20", false, null);
        mvc.perform(post("/api/valuations").contentType(MediaType.APPLICATION_JSON).content(body(ref, "2026-09-18", "INTERACTIVE", false)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.type").value(endsWith("assignment-not-approved")))
                .andExpect(jsonPath("$.unapproved").value(ref));
    }

    // PROVES routing is reference data: the trade's functional line decides the engine, carried on the request and visible in the browser.
    @Test
    void theFunctionalLineDecidesTheEngine() throws Exception {
        String legacy = subject("30", true, "RM_LEGACY");
        String modern = subject("30", true, null);
        String legacyId = JsonPath.read(submit(legacy, "2026-09-18", "INTERACTIVE"), "$.requestId");
        String modernId = JsonPath.read(submit(modern, "2026-09-18", "INTERACTIVE"), "$.requestId");
        awaitStatus(legacyId, "COMPLETED");
        awaitStatus(modernId, "COMPLETED");

        mvc.perform(get("/api/valuations/" + legacyId)).andExpect(jsonPath("$.engine").value("LEGACY")).andExpect(jsonPath("$.functionalLine").value("RM_LEGACY"));
        mvc.perform(get("/api/valuations/" + modernId)).andExpect(jsonPath("$.engine").value("MODERN"));
        assertThat(engine.received.stream().filter(r -> r.requestId().toString().equals(legacyId)).findFirst().orElseThrow().engine()).isEqualTo(ValuationEngine.LEGACY);
    }

    // PROVES the watchdog: a lost reply is retried, and the request completes on the second attempt.
    @Test
    void aLostReplyIsRetriedByTheWatchdogAndThenCompletes() throws Exception {
        String ref = subject("40", true, null);
        engine.behavior = r -> r.subjectRef().equals(ref) && r.attempt() == 1 ? FakeEngine.Action.drop() : FakeEngine.Action.reply(50);

        String id = JsonPath.read(submit(ref, "2026-09-18", "INTERACTIVE"), "$.requestId");
        awaitStatus(id, "COMPLETED");
        engine.behavior = r -> FakeEngine.Action.reply(50);

        mvc.perform(get("/api/valuations/" + id)).andExpect(jsonPath("$.attempts").value(2)).andExpect(jsonPath("$.result.value").value("4000.0000"));
    }

    // PROVES the attempt budget: a request that never gets a reply ends FAILED after three attempts, with the reason recorded.
    @Test
    void aRequestThatNeverGetsAReplyEndsFailedAfterThreeAttempts() throws Exception {
        String ref = subject("50", true, null);
        engine.behavior = r -> r.subjectRef().equals(ref) ? FakeEngine.Action.drop() : FakeEngine.Action.reply(50);

        String id = JsonPath.read(submit(ref, "2026-09-18", "INTERACTIVE"), "$.requestId");
        awaitStatus(id, "FAILED");
        engine.behavior = r -> FakeEngine.Action.reply(50);

        mvc.perform(get("/api/valuations/" + id)).andExpect(jsonPath("$.attempts").value(3)).andExpect(jsonPath("$.error").value(containsString("no reply within")));
    }

    @Test
    void anErrorReplyIsRetriedAndThenFailsWithTheEnginesMessage() throws Exception {
        String ref = subject("60", true, null);
        engine.behavior = r -> r.subjectRef().equals(ref) ? FakeEngine.Action.error(20) : FakeEngine.Action.reply(50);

        String id = JsonPath.read(submit(ref, "2026-09-18", "INTERACTIVE"), "$.requestId");
        awaitStatus(id, "FAILED");
        engine.behavior = r -> FakeEngine.Action.reply(50);

        mvc.perform(get("/api/valuations/" + id)).andExpect(jsonPath("$.attempts").value(3)).andExpect(jsonPath("$.error").value("fake engine error"));
    }

    // PROVES idempotent reply handling: a duplicate or late reply for an already COMPLETED request changes nothing.
    @Test
    void aDuplicateReplyChangesNothing() throws Exception {
        String ref = subject("70", true, null);
        String id = JsonPath.read(submit(ref, "2026-09-18", "INTERACTIVE"), "$.requestId");
        awaitStatus(id, "COMPLETED");

        engine.publishReply(id, "{\"requestId\":\"" + id + "\",\"requestKey\":\"x\",\"engine\":\"MODERN\",\"success\":true,\"result\":\"1.0000\",\"error\":null}");
        // and a legitimate later request proves the reply consumer is still alive and processed the duplicate without incident
        String other = JsonPath.read(submit(subject("71", true, null), "2026-09-18", "INTERACTIVE"), "$.requestId");
        awaitStatus(other, "COMPLETED");

        mvc.perform(get("/api/valuations/" + id)).andExpect(jsonPath("$.result.value").value("7000.0000")).andExpect(jsonPath("$.attempts").value(1));
    }

    // PROVES the failure policy on the reply side: a malformed reply is parked on the DLQ and does not stall the reply consumer.
    @Test
    void aMalformedReplyGoesToTheDlqAndTheGatewayKeepsWorking() throws Exception {
        String garbage = "{ not json " + UUID.randomUUID();
        engine.publishReply("poison", garbage);

        String id = JsonPath.read(submit(subject("90", true, null), "2026-09-18", "INTERACTIVE"), "$.requestId");
        awaitStatus(id, "COMPLETED"); // processing continued past the poison message
        assertThat(dlq()).contains(garbage);
    }

    // PROVES the request browser filters by status, lane and BRD.
    @Test
    void theRequestBrowserFiltersByStatusLaneAndBrd() throws Exception {
        String ref = subject("15", true, null);
        String id = JsonPath.read(submit(ref, "2026-09-18", "INVOICE"), "$.requestId");
        awaitStatus(id, "COMPLETED");

        mvc.perform(get("/api/valuations").param("lane", "INVOICE").param("status", "COMPLETED").param("brd", "2026-09-18"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[?(@.requestId == '" + id + "')]", hasSize(1)));
        mvc.perform(get("/api/valuations").param("lane", "CLOSE").param("brd", "2026-09-18"))
                .andExpect(jsonPath("$[?(@.requestId == '" + id + "')]", hasSize(0)));
        mvc.perform(get("/api/valuations").param("status", "NONSENSE")).andExpect(status().isBadRequest());
    }

    private List<String> dlq() {
        var props = Map.<String, Object>of(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, redpanda.getBootstrapServers(), ConsumerConfig.GROUP_ID_CONFIG, "dlq-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest", ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        List<String> values = new ArrayList<>();
        try (var consumer = new KafkaConsumer<String, String>(props)) {
            consumer.subscribe(List.of(Topics.VALUATION_REPLY + ".dlq"));
            long deadline = System.currentTimeMillis() + 20_000;
            while (values.isEmpty() && System.currentTimeMillis() < deadline) consumer.poll(Duration.ofMillis(500)).forEach(r -> values.add(r.value()));
        }
        return values;
    }
}
