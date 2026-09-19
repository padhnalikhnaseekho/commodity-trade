package io.commodity.platform.lookup;

import io.commodity.contracts.valuation.SubjectLevel;
import io.commodity.contracts.valuation.ValuationInputs;
import io.commodity.contracts.valuation.ValuationInputsProvider;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * Assembles valuation inputs by asking pricing (GET /api/valuation-inputs). 404 means pricing has nothing for the subject at that
 * date (empty); every other failure propagates so an outage is never mistaken for "no inputs".
 */
public class HttpValuationInputsProvider implements ValuationInputsProvider {

    private final RestClient client;

    public HttpValuationInputsProvider(RestClient.Builder builder, String baseUrl) {
        this(HttpClients.create(builder, baseUrl));
    }

    HttpValuationInputsProvider(RestClient client) {
        this.client = client;
    }

    @Override
    public Optional<ValuationInputs> assemble(String subjectRef, SubjectLevel level, LocalDate brd) {
        try {
            return Optional.ofNullable(client.get().uri("/api/valuation-inputs?subjectRef={r}&subjectLevel={l}&asOf={d}", subjectRef, level, brd)
                    .retrieve().body(ValuationInputs.class));
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.NOT_FOUND) return Optional.empty();
            throw e;
        }
    }
}
