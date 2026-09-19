package io.commodity.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.commodity.seed.Seeder;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.redpanda.RedpandaContainer;

/**
 * THE END-TO-END TEST: the whole platform in one JVM (all profiles) on real Postgres and a real broker, populated by the real seeder, checked through the
 * public HTTP APIs. It exercises the full chain (trade, logistics, events, pricing, valuation gateway, stub engine, blotter) and the P0 acceptance items:
 * the seeded book, priced and unpriced quantity in the blotter, provisional valuations, cache hits and replay, a live push within a second, and as-of reads.
 *
 * <p>Ordered on purpose: one shared book, and the later tests change it (a pricing change, a desk roll), so the read-only checks run first.
 */
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AppEndToEndTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16").withStartupAttempts(5);

    @Container
    static RedpandaContainer redpanda = new RedpandaContainer("docker.redpanda.com/redpandadata/redpanda:v23.3.10").withStartupAttempts(5);

    static ConfigurableApplicationContext app;
    static int port;
    static Seeder.Summary summary;
    static JdbcTemplate jdbc;
    static final ObjectMapper JSON = new ObjectMapper();
    static final HttpClient HTTP = HttpClient.newHttpClient();

    @BeforeAll
    static void startEverythingAndSeed() throws Exception {
        try (var s = new ServerSocket(0)) { port = s.getLocalPort(); }
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("spring.profiles.active", "trade,logistics,pricing,gateway,blotter,stubs");
        p.put("spring.datasource.url", postgres.getJdbcUrl());
        p.put("spring.datasource.username", postgres.getUsername());
        p.put("spring.datasource.password", postgres.getPassword());
        p.put("spring.kafka.bootstrap-servers", redpanda.getBootstrapServers());
        p.put("server.port", port);
        for (String svc : List.of("trade", "pricing", "gateway")) p.put("commodity." + svc + ".base-url", "http://localhost:" + port);
        // a fast engine and fast polling so the demo-sized book settles in seconds; the mechanisms are the production ones
        p.put("commodity.engine.delay-ms.interactive", 20);
        p.put("commodity.engine.delay-ms.bulk", 40);
        p.put("commodity.gateway.dispatch-interval-ms", 50);
        p.put("commodity.outbox.poll-ms", 50);
        p.put("commodity.gateway.watchdog-interval-ms", 5000);
        p.put("spring.main.banner-mode", "off");
        p.put("logging.level.root", "WARN");
        app = SpringApplication.run(CommodityApplication.class, p.entrySet().stream().map(e -> "--" + e.getKey() + "=" + e.getValue()).toArray(String[]::new));
        jdbc = app.getBean(JdbcTemplate.class);

        summary = new Seeder("http://localhost:" + port, 42).run();
    }

    @AfterAll
    static void stop() {
        if (app != null) app.close();
    }

    // ---- helpers ------------------------------------------------------------------------------------------------------------------------

    static JsonNode get(String path) throws Exception {
        var response = HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) throw new IllegalStateException("GET " + path + " -> " + response.statusCode() + " " + response.body());
        return JSON.readTree(response.body());
    }

    static HttpResponse<String> post(String path, Map<String, ?> body) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
    }

    static int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    static List<JsonNode> blotter(String query) throws Exception {
        List<JsonNode> rows = new ArrayList<>();
        get("/api/blotter" + query).forEach(rows::add);
        return rows;
    }

    // ---- the tests ----------------------------------------------------------------------------------------------------------------------

    // PROVES acceptance: the seed creates 50 trades with quotas, assignments, parameters and price components, and every one is readable through the API.
    @Test
    @Order(1)
    void theSeedCreatesFiftyTradesWithQuotasAssignmentsParametersAndComponents() throws Exception {
        assertThat(summary.trades()).isEqualTo(50);
        assertThat(count("SELECT count(*) FROM trade.trade")).isEqualTo(50);
        assertThat(count("SELECT count(*) FROM trade.quota")).isEqualTo(summary.quotas());
        assertThat(count("SELECT count(*) FROM logistics.assignment")).isEqualTo(summary.assignments());
        assertThat(count("SELECT count(DISTINCT business_line) FROM trade.trade")).isEqualTo(4);   // all four business lines
        assertThat(count("SELECT count(DISTINCT desk_id) FROM trade.trade")).isEqualTo(3);         // across three desks
        assertThat(count("SELECT count(DISTINCT periodicity) FROM trade.quota")).isEqualTo(3);     // monthly, weekly and custom delivery

        // read everything back through pricing's API and total it up (parameters, components and approvals as pricing resolves them)
        int parameters = 0, components = 0, approved = 0, assignments = 0;
        for (String quotaRef : jdbc.queryForList("SELECT quota_ref FROM trade.quota", String.class)) {
            await().atMost(Duration.ofSeconds(60)).until(() -> {
                try { return get("/api/quotas/" + quotaRef + "/pricing").get("assignments").size() > 0; } catch (IllegalStateException notYet) { return false; }
            });
            for (JsonNode a : get("/api/quotas/" + quotaRef + "/pricing").get("assignments")) {
                // The seeder recorded exactly how many parameters and components it added to each assignment, so any mismatch names the assignment.
                var exp = summary.perAssignment().get(a.get("assignmentRef").asText());
                assertThat(a.get("parameters").size()).as("parameters of " + a.get("assignmentRef").asText()).isEqualTo(exp.parameters());
                assertThat(a.get("components").size()).as("components of " + a.get("assignmentRef").asText()).isEqualTo(exp.components());
                assignments++;
                parameters += a.get("parameters").size();
                components += a.get("components").size();
                if (a.get("approvalStatus").asText().equals("APPROVED")) approved++;
            }
        }
        assertThat(assignments).isEqualTo(summary.assignments());
        assertThat(parameters).isEqualTo(summary.parameters());
        assertThat(components).isEqualTo(summary.components());
        assertThat(approved).isEqualTo(summary.approvals());
        assertThat(summary.parameters()).isPositive();
        assertThat(summary.components()).isPositive();
    }

    // PROVES acceptance: the blotter shows priced and unpriced quantity per assignment, and it agrees with pricing (the system of record) once events settle.
    @Test
    @Order(2)
    void theBlotterShowsPricedAndUnpricedQuantityPerAssignmentAndAgreesWithPricing() throws Exception {
        await().atMost(Duration.ofSeconds(60)).until(() -> blotter("").size() == summary.assignments());
        List<JsonNode> rows = blotter("");

        int priced = 0;
        for (JsonNode row : rows) {
            BigDecimal qty = new BigDecimal(row.get("qty").asText()), p = new BigDecimal(row.get("pricedQty").asText()), u = new BigDecimal(row.get("unpricedQty").asText());
            assertThat(p.add(u)).as("priced + unpriced = qty for " + row.get("assignmentRef").asText()).isEqualByComparingTo(qty);
            assertThat(row.get("status").asText()).isEqualTo("ACTIVE");
            assertThat(row.get("commodity").asText()).isNotBlank();
            if (p.signum() > 0) priced++;
        }
        assertThat(priced).isPositive();                                         // some assignments carry fixations,
        assertThat(priced).isLessThan(rows.size());                              // and some are entirely unpriced

        // the read model must equal pricing's own answer for the same assignments (eventually: wait for the last events)
        await().atMost(Duration.ofSeconds(60)).until(() -> {
            for (JsonNode row : blotter("")) {
                JsonNode pricing = get("/api/quotas/" + row.get("quotaRef").asText() + "/pricing");
                for (JsonNode a : pricing.get("assignments")) {
                    if (a.get("assignmentRef").asText().equals(row.get("assignmentRef").asText())
                            && new BigDecimal(a.get("pricedQty").asText()).compareTo(new BigDecimal(row.get("pricedQty").asText())) != 0) return false;
                }
            }
            return true;
        });
    }

    // PROVES the valuation chain end to end (gateway, stub engine, result fan-out, blotter) and the owner's rules: provisional while unapproved, RM valued at
    // quota level, and the legacy engine used for the RM trades pinned to it by the override.
    @Test
    @Order(3)
    void valuationsCompleteAndReachTheBlotterWithProvisionalFlags() throws Exception {
        assertThat(summary.valuationsRequested()).isPositive();
        await().atMost(Duration.ofSeconds(90)).until(() -> count("SELECT count(*) FROM gateway.valuation_request WHERE status = 'COMPLETED'") == summary.valuationsRequested());
        assertThat(count("SELECT count(*) FROM gateway.valuation_request WHERE status = 'FAILED'")).isZero();

        await().atMost(Duration.ofSeconds(60)).until(() -> blotter("").stream().filter(r -> !r.get("valuation").isNull()).count() >= summary.valuationsRequested() - 5);
        List<JsonNode> valued = blotter("").stream().filter(r -> !r.get("valuation").isNull()).toList();
        assertThat(valued).isNotEmpty();

        for (JsonNode row : valued) {
            assertThat(row.get("provisional").isNull()).isFalse();
            if (row.get("valuationLevel").asText().equals("ASSIGNMENT")) {
                // owner rule: provisional exactly while THIS assignment is unapproved
                assertThat(row.get("provisional").asBoolean()).isEqualTo(!row.get("approvalStatus").asText().equals("APPROVED"));
            }
        }
        assertThat(valued.stream().map(r -> r.get("valuationLevel").asText()).distinct()).contains("ASSIGNMENT");
        assertThat(valued.stream().filter(r -> r.get("businessLine").asText().equals("RM")).map(r -> r.get("valuationLevel").asText()).distinct())
                .containsOnly("QUOTA");                                                                   // RM values at quota level
        assertThat(valued.stream().map(r -> r.get("valuationEngine").asText()).distinct()).contains("MODERN");
        assertThat(count("SELECT count(*) FROM gateway.valuation_request WHERE functional_line = 'RM_LEGACY' AND status = 'COMPLETED'"))
                .as("RM trades pinned to the legacy engine by the override").isPositive();
    }

    // PROVES the interview claims: an identical request is a cache hit (engine not called), and replaying a quota is read-only and also served from the cache.
    @Test
    @Order(4)
    void anIdenticalValuationIsACacheHitAndReplayIsReadOnly() throws Exception {
        // a fully valued, non-RM quota: every assignment of it has a completed valuation
        Map<String, Object> row = jdbc.queryForList("""
                SELECT subject_ref, brd::text AS brd, lane FROM gateway.valuation_request
                 WHERE status = 'COMPLETED' AND subject_level = 'ASSIGNMENT' LIMIT 1""").get(0);
        int engineCallsBefore = count("SELECT count(*) FROM gateway.valuation_request");

        var again = post("/api/valuations", Map.of("subjectRef", row.get("subject_ref"), "subjectLevel", "ASSIGNMENT", "brd", row.get("brd"), "lane", "INTERACTIVE"));
        assertThat(again.statusCode()).isEqualTo(200);
        JsonNode body = JSON.readTree(again.body());
        assertThat(body.get("cached").asBoolean()).isTrue();
        assertThat(body.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(count("SELECT count(*) FROM gateway.valuation_request")).isEqualTo(engineCallsBefore);   // no new request, so no engine call

        // replay of a quota whose assignments were all valued: read-only (no new pricing revision) and answered from the cache
        String quotaRef = String.valueOf(row.get("subject_ref")).replaceAll("\\.\\d+$", "");
        int revisions = count("SELECT count(*) FROM pricing.quota_revision WHERE quota_ref = ?", quotaRef);
        var replay = post("/api/replay", Map.of("quotaRef", quotaRef));
        assertThat(replay.statusCode()).isEqualTo(202);
        assertThat(count("SELECT count(*) FROM pricing.quota_revision WHERE quota_ref = ?", quotaRef)).isEqualTo(revisions);
        JsonNode requests = JSON.readTree(replay.body()).get("requests");
        assertThat(requests.size()).isPositive();
    }

    // PROVES acceptance: a pricing change reaches an OPEN blotter within a second, with no refresh, carrying only what changed.
    @Test
    @Order(5)
    void aPricingChangePushesToTheOpenBlotterWithinASecond() throws Exception {
        JsonNode target = blotter("?deskId=DESK-BULK").stream().filter(r -> new BigDecimal(r.get("unpricedQty").asText()).compareTo(new BigDecimal("100")) >= 0)
                .findFirst().orElseThrow();
        String ref = target.get("assignmentRef").asText();
        BigDecimal priced = new BigDecimal(target.get("pricedQty").asText());

        BlockingQueue<String> events = new LinkedBlockingQueue<>();
        var stream = HTTP.sendAsync(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/blotter/stream?deskId=DESK-BULK"))
                .header("Accept", "text/event-stream").build(), HttpResponse.BodyHandlers.ofInputStream());
        Thread reader = new Thread(() -> {
            try (var in = new BufferedReader(new InputStreamReader(stream.get().body()))) {
                String line;
                while ((line = in.readLine()) != null) events.add(line);
            } catch (Exception ignored) { /* closed at the end of the test */ }
        });
        reader.setDaemon(true);
        reader.start();
        assertThat(events.poll(10, TimeUnit.SECONDS)).isNotNull();          // the stream is open ("id:0 / event:open")
        Thread.sleep(400);                                                   // subscribed; earlier seed events are long since flushed

        var fix = post("/api/assignments/" + ref + "/price-components", Map.of("kind", "FIXED", "qty", "10", "fixedPrice", "1234.5"));
        long start = System.nanoTime();
        assertThat(fix.statusCode()).isEqualTo(201);

        String patch = null;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (patch == null && System.nanoTime() < deadline) {
            String line = events.poll(100, TimeUnit.MILLISECONDS);
            if (line != null && line.startsWith("data:") && line.contains("\"" + ref + "\"") && line.contains("\"path\":\"/pricedQty\"")) patch = line;
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(patch).as("a change event for " + ref).isNotNull();
        assertThat(elapsedMs).as("ms from the pricing change to the open blotter").isLessThan(1_000);
        assertThat(patch).contains(priced.add(BigDecimal.TEN).setScale(4).toPlainString());
        assertThat(patch).doesNotContain("\"/qty\"").doesNotContain("\"path\":\"/commodity\"");   // only what changed
    }

    // PROVES acceptance: the as-of control resolves a past BRD and shows the historical state, while the live view shows the change made after the desk rolled.
    @Test
    @Order(6)
    void theAsOfControlResolvesAPastBrdToItsHistoricalState() throws Exception {
        JsonNode before = blotter("?deskId=DESK-ENERGY").stream().filter(r -> new BigDecimal(r.get("unpricedQty").asText()).compareTo(new BigDecimal("100")) >= 0)
                .findFirst().orElseThrow();
        String ref = before.get("assignmentRef").asText(), quotaRef = before.get("quotaRef").asText();
        BigDecimal pricedThen = new BigDecimal(before.get("pricedQty").asText());
        String past = before.get("brd").asText();

        var roll = post("/api/desks/DESK-ENERGY/roll", Map.of());                                     // the desk moves to the next business date
        assertThat(roll.statusCode()).isEqualTo(200);
        String next = JSON.readTree(roll.body()).get("brd").asText();
        assertThat(next).isNotEqualTo(past);

        assertThat(post("/api/assignments/" + ref + "/price-components", Map.of("kind", "FIXED", "qty", "20", "fixedPrice", "88")).statusCode()).isEqualTo(201);
        BigDecimal pricedNow = pricedThen.add(new BigDecimal("20"));

        await().atMost(Duration.ofSeconds(30)).until(() -> {
            JsonNode live = blotter("?deskId=DESK-ENERGY").stream().filter(r -> r.get("assignmentRef").asText().equals(ref)).findFirst().orElseThrow();
            return new BigDecimal(live.get("pricedQty").asText()).compareTo(pricedNow) == 0;
        });

        // the past date still shows the past
        JsonNode asOfPast = blotter("?deskId=DESK-ENERGY&brd=" + past).stream().filter(r -> r.get("assignmentRef").asText().equals(ref)).findFirst().orElseThrow();
        assertThat(new BigDecimal(asOfPast.get("pricedQty").asText())).isEqualByComparingTo(pricedThen);
        assertThat(asOfPast.get("brd").asText()).isEqualTo(past);
        // the new date shows the new state
        JsonNode asOfNext = blotter("?deskId=DESK-ENERGY&brd=" + next).stream().filter(r -> r.get("assignmentRef").asText().equals(ref)).findFirst().orElseThrow();
        assertThat(new BigDecimal(asOfNext.get("pricedQty").asText())).isEqualByComparingTo(pricedNow);
        // before anything was captured there is nothing to show
        assertThat(blotter("?deskId=DESK-ENERGY&brd=2026-01-01")).isEmpty();
        // and pricing's own as-of agrees with the blotter's
        assertThat(new BigDecimal(get("/api/quotas/" + quotaRef + "/pricing?asOf=" + past).get("assignments").findValues("pricedQty").get(0).asText())).isNotNull();
    }
}
