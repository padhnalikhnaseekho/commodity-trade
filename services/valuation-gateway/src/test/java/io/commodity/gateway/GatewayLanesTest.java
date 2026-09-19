package io.commodity.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.commodity.contracts.valuation.Lane;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
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
 * Lane isolation, the reason lanes exist: a bulk sweep must never make a trader's what-if wait. The BULK lane is given a tiny budget (2 in flight)
 * and a slow engine, then saturated; an INTERACTIVE request submitted behind the backlog must still complete well inside its timeout.
 */
@SpringBootTest(classes = GatewayTestApp.class, properties = {
        "commodity.gateway.workers.enabled=true",
        "commodity.gateway.dispatch-interval-ms=50",
        "commodity.gateway.watchdog-interval-ms=5000",
        "commodity.gateway.lanes.bulk.max-in-flight=2",
        "commodity.gateway.lanes.interactive.max-in-flight=2",
        "commodity.gateway.lanes.interactive.timeout-ms=5000",
        "commodity.outbox.poll-ms=50",
        "spring.kafka.consumer.auto-offset-reset=earliest"})
@AutoConfigureMockMvc
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class GatewayLanesTest {

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
    static final AtomicInteger NEXT = new AtomicInteger(700);

    @BeforeAll
    static void startEngine() {
        engine = new FakeEngine(redpanda.getBootstrapServers());
        engine.behavior = r -> r.lane() == Lane.BULK ? FakeEngine.Action.reply(1500) : FakeEngine.Action.reply(50); // slow bulk, fast interactive
    }

    @AfterAll
    static void stopEngine() throws Exception { engine.close(); }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    private String submit(String lane) throws Exception {
        String ref = Fakes.assignment(NEXT.incrementAndGet() + ".1", 1, "10", true, null);
        return JsonPath.read(mvc.perform(post("/api/valuations").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subjectRef\":\"" + ref + "\",\"subjectLevel\":\"ASSIGNMENT\",\"brd\":\"2026-09-18\",\"lane\":\"" + lane + "\"}"))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString(), "$.requestId");
    }

    private String statusOf(String id) throws Exception {
        return JsonPath.read(mvc.perform(get("/api/valuations/" + id)).andReturn().getResponse().getContentAsString(), "$.status");
    }

    private int sentInLane(String lane) {
        return jdbc.queryForObject("SELECT count(*) FROM gateway.valuation_request WHERE status = 'SENT' AND lane = ?", Integer.class, lane);
    }

    // PROVES acceptance: saturating the BULK lane leaves an INTERACTIVE request completing inside its timeout, and no lane ever exceeds its budget.
    @Test
    void saturatingBulkLeavesInteractiveInsideItsTimeout() throws Exception {
        List<String> bulk = new ArrayList<>();
        for (int i = 0; i < 8; i++) bulk.add(submit("BULK"));      // 8 slow bulk requests against a budget of 2
        await().atMost(Duration.ofSeconds(10)).until(() -> sentInLane("BULK") == 2); // the lane is full; the rest wait in the database

        long start = System.currentTimeMillis();
        String interactive = submit("INTERACTIVE");
        int maxBulkSent = 0;
        while (!statusOf(interactive).equals("COMPLETED")) {
            maxBulkSent = Math.max(maxBulkSent, sentInLane("BULK"));
            assertThat(System.currentTimeMillis() - start).as("interactive request must finish inside its 5s timeout").isLessThan(5_000);
            Thread.sleep(50);
        }
        long elapsed = System.currentTimeMillis() - start;

        long bulkDone = bulk.stream().filter(id -> { try { return statusOf(id).equals("COMPLETED"); } catch (Exception e) { throw new IllegalStateException(e); } }).count();
        // The claim is "inside its timeout" (asserted in the loop above), NOT a tuned latency figure: a tighter bound would only measure
        // infrastructure jitter such as consumer-group start-up. Isolation is shown by what happened to the bulk backlog instead:
        assertThat(elapsed).isLessThan(5_000);
        assertThat(bulkDone).isLessThan(8);                    // the bulk backlog (8 x 1.5s at 2 at a time) was still working when interactive finished
        assertThat(maxBulkSent).isLessThanOrEqualTo(2);        // the bulk budget was never exceeded

        for (String id : bulk) await().atMost(Duration.ofSeconds(40)).until(() -> statusOf(id).equals("COMPLETED")); // the backlog drains, nothing is lost
        assertThat(engine.maxInFlight(Lane.BULK)).isLessThanOrEqualTo(2); // the ENGINE never saw more than the lane's budget at once
    }
}
