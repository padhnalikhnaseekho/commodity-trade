package io.commodity.platform.lookup;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Builds the RestClient used by the cross-service lookup adapters, with connect and read timeouts.
 *
 * <p>WHY timeouts are not optional: a lookup runs inside a caller's request. Without a bound, one slow downstream service
 * holds the caller's threads until it collapses too. TARGET: Resilience4j retries with jitter and a circuit breaker on top.
 */
final class HttpClients {
    private HttpClients() {}

    static RestClient create(RestClient.Builder builder, String baseUrl) {
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
        factory.setReadTimeout(Duration.ofSeconds(5));
        return builder.baseUrl(baseUrl).requestFactory(factory).build();
    }
}
