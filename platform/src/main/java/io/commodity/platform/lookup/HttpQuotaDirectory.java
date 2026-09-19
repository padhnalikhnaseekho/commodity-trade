package io.commodity.platform.lookup;

import io.commodity.contracts.lookup.QuotaDirectory;
import io.commodity.contracts.lookup.QuotaView;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * Reads quotas from the trade service over HTTP (GET /api/quotas/{ref}) and maps them to the contracts type.
 *
 * <p>Why HTTP even though everything shares a JVM in the demo: it keeps the boundary honest. The caller knows nothing of
 * trade's classes or tables, so the services can be split into separate deployables without rework. Lives in platform
 * because logistics and pricing both need it and it is pure infrastructure.
 *
 * <p>A missing quota is {@code Optional.empty()}; any other failure propagates, so an outage is never mistaken for
 * "no such quota". TARGET: a read-through cache with event invalidation from the trade topic.
 */
public class HttpQuotaDirectory implements QuotaDirectory {

    private final RestClient client;

    public HttpQuotaDirectory(RestClient.Builder builder, String baseUrl) {
        this(HttpClients.create(builder, baseUrl));
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
