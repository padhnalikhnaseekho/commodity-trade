package io.commodity.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.commodity.contracts.valuation.Lane;
import io.commodity.contracts.valuation.SubjectLevel;
import io.commodity.gateway.domain.LaneLimiter;
import io.commodity.gateway.service.ValuationService;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.redpanda.RedpandaContainer;

/**
 * "Killing the gateway mid-flight and restarting completes the request from durable state."
 *
 * <p>Two whole Spring contexts, one after the other, over the same database and broker: the first is closed (killed) with a request in flight,
 * the second starts fresh and must finish the job. The engine is a separate consumer that outlives both. Nothing in the second gateway knows
 * about the first except what is in the database, which is exactly the claim: correlation through durable state, not through memory.
 */
@Testcontainers
class GatewayRestartTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16").withStartupAttempts(5);

    @Container
    static RedpandaContainer redpanda = new RedpandaContainer("docker.redpanda.com/redpandadata/redpanda:v23.3.10").withStartupAttempts(5);

    static FakeEngine engine;

    @BeforeAll
    static void startEngine() { engine = new FakeEngine(redpanda.getBootstrapServers()); }

    @AfterAll
    static void stopEngine() throws Exception { engine.close(); }

    private static ConfigurableApplicationContext startGateway(boolean workers) {
        return new SpringApplicationBuilder(GatewayTestApp.class).web(WebApplicationType.NONE).properties(Map.ofEntries(
                Map.entry("spring.datasource.url", postgres.getJdbcUrl()),
                Map.entry("spring.datasource.username", postgres.getUsername()),
                Map.entry("spring.datasource.password", postgres.getPassword()),
                Map.entry("spring.kafka.bootstrap-servers", redpanda.getBootstrapServers()),
                Map.entry("spring.kafka.consumer.auto-offset-reset", "earliest"),
                Map.entry("commodity.gateway.workers.enabled", String.valueOf(workers)),
                Map.entry("commodity.gateway.dispatch-interval-ms", "50"),
                Map.entry("commodity.gateway.watchdog-interval-ms", "60000"),   // out of the way: this test is about recovery, not timeouts
                Map.entry("commodity.outbox.poll-ms", "50"),
                Map.entry("spring.main.banner-mode", "off"),
                Map.entry("logging.level.root", "WARN"))).run();
    }

    private static UUID submit(ConfigurableApplicationContext ctx, String subjectRef) {
        var outcome = ctx.getBean(ValuationService.class).submit(new ValuationService.Submit(subjectRef, SubjectLevel.ASSIGNMENT,
                LocalDate.of(2026, 9, 18), Lane.INTERACTIVE, false, "restart-test"));
        return ((ValuationService.Outcome.Accepted) outcome).requestId();
    }

    private static String statusOf(ConfigurableApplicationContext ctx, UUID id) {
        return ctx.getBean(JdbcTemplate.class).queryForObject("SELECT status FROM gateway.valuation_request WHERE request_id = ?", String.class, id);
    }

    // PROVES acceptance: a request that is SENT (in flight at the engine) when the gateway dies is completed by a restarted gateway, from the
    // database alone, and the new instance's lane budget already knows that request is in flight.
    @Test
    void aRequestInFlightWhenTheGatewayDiesCompletesAfterARestart() {
        String ref = Fakes.assignment("801.1", 1, "12", true, null);
        engine.behavior = r -> r.subjectRef().equals(ref) ? FakeEngine.Action.hold() : FakeEngine.Action.reply(50);

        UUID id;
        try (var first = startGateway(true)) {
            id = submit(first, ref);
            await().atMost(Duration.ofSeconds(30)).until(() -> engine.received.stream().anyMatch(r -> r.requestId().equals(id))); // the engine has it
            assertThat(statusOf(first, id)).isEqualTo("SENT");
        } // the first gateway is closed here: killed mid-flight

        try (var second = startGateway(true)) {
            assertThat(statusOf(second, id)).isEqualTo("SENT");                                   // nothing was lost: it is in the database
            assertThat(second.getBean(LaneLimiter.class).inFlight(Lane.INTERACTIVE)).isEqualTo(1); // and the new instance's budget counts it

            engine.releaseHeld();                                                                  // the engine finally replies, to a gateway that never sent it
            await().atMost(Duration.ofSeconds(30)).until(() -> statusOf(second, id).equals("COMPLETED"));

            var result = second.getBean(JdbcTemplate.class).queryForObject("SELECT result->>'value' FROM gateway.valuation_request WHERE request_id = ?", String.class, id);
            assertThat(result).isEqualTo("1200.0000");
            await().atMost(Duration.ofSeconds(10)).until(() -> second.getBean(LaneLimiter.class).inFlight(Lane.INTERACTIVE) == 0); // the slot was released
        }
        engine.behavior = r -> FakeEngine.Action.reply(50);
    }

    // PROVES the other half: a request that was only ACCEPTED (PENDING, never sent) when the gateway died is picked up and sent by the restart.
    @Test
    void aRequestAcceptedButNotYetSentIsDispatchedAfterARestart() {
        String ref = Fakes.assignment("802.1", 1, "5", true, null);
        engine.behavior = r -> FakeEngine.Action.reply(50);

        UUID id;
        try (var first = startGateway(false)) {   // workers off: the request is accepted and stored, but nothing sends it
            id = submit(first, ref);
            assertThat(statusOf(first, id)).isEqualTo("PENDING");
        }
        try (var second = startGateway(true)) {
            await().atMost(Duration.ofSeconds(30)).until(() -> statusOf(second, id).equals("COMPLETED"));
        }
    }
}
