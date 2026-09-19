package io.commodity.blotter;

import static org.assertj.core.api.Assertions.assertThat;

import io.commodity.blotter.service.BlotterProjector;
import io.commodity.contracts.events.PricingQuotaPublished;
import io.commodity.contracts.lookup.QuotaDirectory;
import io.commodity.contracts.lookup.QuotaView;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The live stream over real HTTP: an open blotter receives a change WITHOUT refreshing, within a second, and a reconnecting client resumes from
 * Last-Event-ID. Real server, real Postgres, the real projector, broadcaster and flush timer.
 */
@SpringBootTest(classes = {BlotterStreamTest.App.class, BlotterStreamTest.Ports.class}, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class BlotterStreamTest {

    @org.springframework.boot.autoconfigure.SpringBootApplication(scanBasePackages = "io.commodity.blotter")
    static class App {}

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16").withStartupAttempts(5);

    @TestConfiguration
    static class Ports {
        @Bean QuotaDirectory quotas() {
            return ref -> Optional.of(new QuotaView(ref, ref.split("\\.")[0], "DESK-S", "BULK", new BigDecimal("1000"), LocalDate.of(2026, 9, 1), null, "Coal"));
        }
    }

    @LocalServerPort int port;
    @Autowired BlotterProjector projector;

    private static PricingQuotaPublished snapshot(String quota, String priced) {
        var q = new BigDecimal("100");
        var p = new BigDecimal(priced);
        return new PricingQuotaPublished(quota, UUID.randomUUID(), LocalDate.of(2026, 9, 18), "REVISION",
                List.of(new PricingQuotaPublished.Assignment(quota + ".1", q, p, q.subtract(p), false, "UNAPPROVED")));
    }

    /** Opens the SSE stream and completes with the first line that satisfies the predicate (or fails on timeout). */
    private CompletableFuture<String> firstLineMatching(String path, String lastEventId, java.util.function.Predicate<String> wanted) {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).header("Accept", "text/event-stream");
        if (lastEventId != null) request.header("Last-Event-ID", lastEventId);
        return CompletableFuture.supplyAsync(() -> {
            try {
                var response = HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
                try (var reader = new BufferedReader(new InputStreamReader(response.body()))) {
                    String line;
                    while ((line = reader.readLine()) != null) if (wanted.test(line)) return line;
                }
                throw new IllegalStateException("stream ended");
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
    }

    // PROVES acceptance: a pricing change reaches an OPEN blotter within a second, with no refresh, carrying only what changed.
    @Test
    void aPricingChangeReachesAnOpenBlotterWithinASecond() throws Exception {
        projector.onQuotaPublished(snapshot("501.1", "0"));

        var opened = firstLineMatching("/api/blotter/stream?deskId=DESK-S", null, l -> l.startsWith("event:open"));
        opened.get(10, TimeUnit.SECONDS);                                   // the stream is open and subscribed
        var change = firstLineMatching("/api/blotter/stream?deskId=DESK-S", null, l -> l.startsWith("data:") && l.contains("501.1.1") && l.contains("\"path\":\"/pricedQty\""));
        Thread.sleep(300);                                                   // let that second connection subscribe

        long start = System.nanoTime();
        projector.onQuotaPublished(snapshot("501.1", "40"));                 // the pricing change
        String data = change.get(3, TimeUnit.SECONDS);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(elapsedMs).as("time from the change to the open blotter").isLessThan(1_000);
        assertThat(data).contains("/pricedQty").contains("40.0000").contains("/unpricedQty").contains("60.0000");
    }

    // PROVES resume: a client that reconnects with Last-Event-ID receives the change it missed while disconnected.
    @Test
    void aReconnectingClientGetsTheChangeItMissed() throws Exception {
        projector.onQuotaPublished(snapshot("502.1", "0"));
        var first = firstLineMatching("/api/blotter/stream?deskId=DESK-S", null, l -> l.startsWith("event:open"));
        first.get(10, TimeUnit.SECONDS);

        projector.onQuotaPublished(snapshot("502.1", "10"));   // happens while nobody is subscribed to this quota's changes
        Thread.sleep(400);                                     // flushed into the replay buffer

        // Last-Event-ID 0 replays everything still buffered, in order: the initial row first, then the change it missed. Wait for the change itself.
        String replayed = firstLineMatching("/api/blotter/stream?deskId=DESK-S", "0", l -> l.startsWith("data:") && l.contains("502.1.1") && l.contains("/pricedQty"))
                .get(5, TimeUnit.SECONDS);
        assertThat(replayed).contains("10.0000").contains("/unpricedQty").contains("90.0000");
    }
}
