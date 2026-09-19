package io.commodity.platform.lookup;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.commodity.contracts.valuation.Lane;
import io.commodity.contracts.valuation.SubjectLevel;
import io.commodity.contracts.valuation.ValuationSubmitter;
import io.commodity.platform.error.DomainException;
import java.time.LocalDate;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * Submits valuation requests to the gateway over HTTP (POST /api/valuations).
 *
 * <p>The gateway's answers map onto the port: 200 is a cache hit ({@code cached = true}), 202 an accepted or in-flight request. A 4xx refusal is
 * re-raised as a {@link DomainException} with the gateway's problem type and its diagnostic fields, so the caller's own API reports the SAME reason
 * (for example brd-closed with the dates) instead of a generic failure. Server errors and outages propagate unchanged.
 */
public class HttpValuationSubmitter implements ValuationSubmitter {

    private final RestClient client;
    private final ObjectMapper json = new ObjectMapper();

    public HttpValuationSubmitter(RestClient.Builder builder, String baseUrl) {
        this(HttpClients.create(builder, baseUrl));
    }

    HttpValuationSubmitter(RestClient client) {
        this.client = client;
    }

    @Override
    public Submission submit(String subjectRef, SubjectLevel level, LocalDate brd, Lane lane, boolean restatement, String source) {
        try {
            var response = client.post().uri("/api/valuations").contentType(MediaType.APPLICATION_JSON).header("X-Caller", source)
                    .body(Map.of("subjectRef", subjectRef, "subjectLevel", level.name(), "brd", brd.toString(), "lane", lane.name(), "restatement", restatement))
                    .retrieve().toEntity(JsonNode.class);
            JsonNode body = response.getBody();
            return new Submission(UUID.fromString(body.get("requestId").asText()), body.get("requestKey").asText(), body.get("status").asText(),
                    response.getStatusCode().value() == 200 && body.path("cached").asBoolean(false));
        } catch (HttpClientErrorException e) {
            throw refusal(e);
        }
    }

    private DomainException refusal(HttpClientErrorException e) {
        try {
            JsonNode problem = json.readTree(e.getResponseBodyAsString());
            String type = problem.path("type").asText("gateway-refused");
            DomainException out = new DomainException(e.getStatusCode().value(), type.substring(type.lastIndexOf('/') + 1), problem.path("title").asText("Valuation refused"));
            for (Iterator<Map.Entry<String, JsonNode>> it = problem.fields(); it.hasNext(); ) {
                var field = it.next();
                if (!Set.of("type", "title", "status", "detail", "instance").contains(field.getKey())) out.with(field.getKey(), field.getValue().asText());
            }
            return out;
        } catch (Exception parse) {
            return new DomainException(e.getStatusCode().value(), "gateway-refused", "Valuation refused by the gateway");
        }
    }

    private static final class Set {
        static java.util.Set<String> of(String... values) { return java.util.Set.of(values); }
    }
}
