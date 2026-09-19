package io.commodity.platform.lookup;

import io.commodity.contracts.lookup.FixationDirectory;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * Asks the pricing service (GET /api/assignments/{ref}/fixation) whether an assignment has any price fixation.
 *
 * <p>404 means pricing has not seen the assignment yet (its revision event is still in flight), which means it cannot
 * have a fixation: answered as {@code false}. Every other failure propagates, so a pricing outage blocks quantity edits
 * (fails closed) instead of silently allowing an edit that might break the fixation rule.
 */
public class HttpFixationDirectory implements FixationDirectory {

    /** Shape of pricing's answer. */
    public record Fixation(String assignmentRef, boolean fixed) {}

    private final RestClient client;

    public HttpFixationDirectory(RestClient.Builder builder, String baseUrl) {
        this(HttpClients.create(builder, baseUrl));
    }

    HttpFixationDirectory(RestClient client) {
        this.client = client;
    }

    @Override
    public boolean hasFixation(String assignmentRef) {
        try {
            Fixation f = client.get().uri("/api/assignments/{ref}/fixation", assignmentRef).retrieve().body(Fixation.class);
            return f != null && f.fixed();
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.NOT_FOUND) return false;
            throw e;
        }
    }
}
