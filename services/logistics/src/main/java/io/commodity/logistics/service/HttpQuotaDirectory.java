package io.commodity.logistics.service;

import io.commodity.contracts.lookup.QuotaDirectory;
import io.commodity.contracts.lookup.QuotaView;
import java.time.Duration;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * Reads quotas from the trade service over HTTP (GET /api/quotas/{ref}) and maps them to the contracts type.
 *
 * <p>Why HTTP even though everything shares a JVM in the demo: it keeps the boundary honest. Logistics knows nothing of
 * trade's classes or tables, so the two can be split into separate deployables without rework.
 *
 * <p>TARGET: a read-through cache with event invalidation from the trade topic, plus Resilience4j timeouts, retries and
 * a circuit breaker. The demo sets only connect/read timeouts, so a slow trade service cannot hang logistics forever.
 * Active only when {@code commodity.trade.base-url} is set.
 */
@Component
@ConditionalOnProperty("commodity.trade.base-url")
public class HttpQuotaDirectory implements QuotaDirectory {

    private final RestClient client;

    @Autowired
    public HttpQuotaDirectory(RestClient.Builder builder, @Value("${commodity.trade.base-url}") String baseUrl) {
        this(builder.baseUrl(baseUrl)
                .requestFactory(ClientHttpRequestFactories.get(ClientHttpRequestFactorySettings.DEFAULTS
                        .withConnectTimeout(Duration.ofSeconds(2)).withReadTimeout(Duration.ofSeconds(5))))
                .build());
    }

    /** For tests: takes an already-built client so a mock server's request factory is not overwritten. */
    HttpQuotaDirectory(RestClient client) {
        this.client = client;
    }

    @Override
    public Optional<QuotaView> find(String quotaRef) {
        try {
            return Optional.ofNullable(client.get().uri("/api/quotas/{ref}", quotaRef).retrieve().body(QuotaView.class));
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.NOT_FOUND) return Optional.empty();
            throw e;
        }
    }
}
