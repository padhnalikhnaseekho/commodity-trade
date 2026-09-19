package io.commodity.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.commodity.blotter.service.BlotterProjector;
import io.commodity.gateway.service.ValuationService;
import io.commodity.logistics.service.AssignmentService;
import io.commodity.pricing.service.PricingService;
import io.commodity.trade.service.TradeService;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * PROVES the deployment claim: the same application runs as ONE process or as any subset of services, chosen only by the active profiles, and a
 * service that is not in the profile list loads nothing: no beans, no endpoints, no schema.
 *
 * <p>Each scenario starts a real Spring context on a fresh Postgres. Background workers and Kafka consumers are switched off, because this test is
 * about what is LOADED, not about message flow (the end-to-end test covers that).
 */
class ProfileIsolationTest {

    private static int freePort() throws IOException {
        try (var s = new ServerSocket(0)) { return s.getLocalPort(); }
    }

    /**
     * Starts the application exactly as an operator would: everything as COMMAND-LINE ARGUMENTS, which have the highest precedence. (Properties passed
     * through the builder are only defaults and lose to application.yml, and {@code .profiles()} only ADDS to the yml's default profile list, which is why
     * neither can be used to select a subset.)
     */
    private static ConfigurableApplicationContext start(PostgreSQLContainer<?> db, int port, String... profiles) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("spring.profiles.active", String.join(",", profiles));
        p.put("spring.datasource.url", db.getJdbcUrl());
        p.put("spring.datasource.username", db.getUsername());
        p.put("spring.datasource.password", db.getPassword());
        p.put("server.port", port);
        // every service is reached at this process's address (they talk over HTTP even inside one JVM)
        for (String svc : List.of("trade", "pricing", "gateway")) p.put("commodity." + svc + ".base-url", "http://localhost:" + port);
        p.put("commodity.pricing.consumer.enabled", false);
        p.put("commodity.gateway.workers.enabled", false);
        p.put("commodity.blotter.consumer.enabled", false);
        p.put("commodity.outbox.enabled", false);
        p.put("commodity.engine.enabled", false);
        p.put("spring.main.banner-mode", "off");
        p.put("logging.level.root", "WARN");
        return SpringApplication.run(CommodityApplication.class, p.entrySet().stream().map(e -> "--" + e.getKey() + "=" + e.getValue()).toArray(String[]::new));
    }

    private static List<String> schemas(ConfigurableApplicationContext ctx) {
        return ctx.getBean(JdbcTemplate.class).queryForList(
                "SELECT schema_name FROM information_schema.schemata WHERE schema_name IN ('trade', 'logistics', 'pricing', 'gateway', 'blotter')", String.class);
    }

    private static int status(int port, String method, String path, String body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).header("Content-Type", "application/json");
        var request = (body == null ? builder.method(method, HttpRequest.BodyPublishers.noBody()) : builder.method(method, HttpRequest.BodyPublishers.ofString(body))).build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    private static final String TRADE = """
            {"businessLine":"CONCENTRATES","deskId":"DESK-1","side":"PURCHASE","counterparty":"ACME Mining","commodity":"Copper concentrate",
             "totalQty":"1000","uom":"MT","delivery":{"periodicity":"MONTHLY","from":"2026-01-01","to":"2026-02-28"}}""";

    // PROVES the "one process" mode works end to end: all services in one context, no bean clashes, each migrating its own schema, and a call that crosses
    // service boundaries (logistics reads the quota from trade over real HTTP, to itself).
    @Test
    void everyProfileTogetherRunsAllServicesInOneJvm() throws Exception {
        try (var db = new PostgreSQLContainer<>("postgres:16").withStartupAttempts(5)) {
            db.start();
            int port = freePort();
            try (var ctx = start(db, port, "trade", "logistics", "pricing", "gateway", "blotter", "stubs")) {
                assertThat(ctx.getBeanNamesForType(TradeService.class)).isNotEmpty();
                assertThat(ctx.getBeanNamesForType(AssignmentService.class)).isNotEmpty();
                assertThat(ctx.getBeanNamesForType(PricingService.class)).isNotEmpty();
                assertThat(ctx.getBeanNamesForType(ValuationService.class)).isNotEmpty();
                assertThat(ctx.getBeanNamesForType(BlotterProjector.class)).isNotEmpty();
                assertThat(schemas(ctx)).containsExactlyInAnyOrder("trade", "logistics", "pricing", "gateway", "blotter");

                assertThat(status(port, "POST", "/api/trades", TRADE)).isEqualTo(201);
                // logistics needs the quota's quantity and desk: it asks trade over HTTP
                assertThat(status(port, "POST", "/api/quotas/1.1/assignments", "{\"qty\":\"100\",\"changeKind\":\"ALLOCATION\"}")).isEqualTo(201);
            }
        }
    }

    // PROVES a service that is not in the profile list is absent: only trade (and the stand-ins it needs) is loaded.
    @Test
    void onlyTradeLoadsNothingOfTheOtherServices() throws Exception {
        try (var db = new PostgreSQLContainer<>("postgres:16").withStartupAttempts(5)) {
            db.start();
            int port = freePort();
            try (var ctx = start(db, port, "trade", "stubs")) {
                assertThat(ctx.getBeanNamesForType(TradeService.class)).isNotEmpty();
                assertThat(ctx.getBeanNamesForType(AssignmentService.class)).isEmpty();
                assertThat(ctx.getBeanNamesForType(PricingService.class)).isEmpty();
                assertThat(ctx.getBeanNamesForType(ValuationService.class)).isEmpty();
                assertThat(schemas(ctx)).containsExactly("trade");                                   // only its own schema exists

                assertThat(status(port, "GET", "/api/trades/1", null)).isEqualTo(404);               // the trade endpoint exists (no such trade)
                assertThat(status(port, "POST", "/api/quotas/1.1/assignments", "{}")).isEqualTo(404); // logistics' endpoint does not exist here
                assertThat(status(port, "GET", "/api/valuations", null)).isEqualTo(404);
            }
        }
    }

    @Test
    void onlyPricingLoadsNothingOfTheOtherServices() throws Exception {
        try (var db = new PostgreSQLContainer<>("postgres:16").withStartupAttempts(5)) {
            db.start();
            int port = freePort();
            try (var ctx = start(db, port, "pricing", "stubs")) {
                assertThat(ctx.getBeanNamesForType(PricingService.class)).isNotEmpty();
                assertThat(ctx.getBeanNamesForType(TradeService.class)).isEmpty();
                assertThat(ctx.getBeanNamesForType(AssignmentService.class)).isEmpty();
                assertThat(ctx.getBeanNamesForType(ValuationService.class)).isEmpty();
                assertThat(schemas(ctx)).containsExactly("pricing");

                assertThat(status(port, "POST", "/api/trades", TRADE)).isEqualTo(404);               // trade's endpoint is not here
                assertThat(status(port, "GET", "/api/quotas/1.1/revisions", null)).isEqualTo(200);   // pricing's is (empty list)
            }
        }
    }

    // The gateway has no JPA entities at all: it must start on its own without the other services' entities or schemas.
    @Test
    void onlyTheGatewayStartsWithoutAnyEntities() throws Exception {
        try (var db = new PostgreSQLContainer<>("postgres:16").withStartupAttempts(5)) {
            db.start();
            int port = freePort();
            try (var ctx = start(db, port, "gateway", "stubs")) {
                assertThat(ctx.getBeanNamesForType(ValuationService.class)).isNotEmpty();
                assertThat(ctx.getBeanNamesForType(PricingService.class)).isEmpty();
                assertThat(schemas(ctx)).containsExactly("gateway");
                assertThat(status(port, "GET", "/api/valuations", null)).isEqualTo(200);
            }
        }
    }

    // The blotter is a service like the others: it starts alone, owns only its own schema, and serves its read API with nothing else loaded.
    @Test
    void onlyTheBlotterStartsAlone() throws Exception {
        try (var db = new PostgreSQLContainer<>("postgres:16").withStartupAttempts(5)) {
            db.start();
            int port = freePort();
            try (var ctx = start(db, port, "blotter", "stubs")) {
                assertThat(ctx.getBeanNamesForType(BlotterProjector.class)).isNotEmpty();
                assertThat(ctx.getBeanNamesForType(PricingService.class)).isEmpty();
                assertThat(ctx.getBeanNamesForType(TradeService.class)).isEmpty();
                assertThat(schemas(ctx)).containsExactly("blotter");
                assertThat(status(port, "GET", "/api/blotter", null)).isEqualTo(200);
                assertThat(status(port, "GET", "/api/trades/1", null)).isEqualTo(404);
            }
        }
    }
}
