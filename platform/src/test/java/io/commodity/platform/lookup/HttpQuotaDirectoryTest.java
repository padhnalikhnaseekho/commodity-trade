package io.commodity.platform.lookup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** The HTTP adapters behind the lookup ports, against mocked services (no network, no Spring context). */
class HttpQuotaDirectoryTest {

    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

    private HttpQuotaDirectory directory() {
        // The client is bound to the mock server above; the host is never contacted.
        return new HttpQuotaDirectory(builder.baseUrl("http://trade.invalid").build());
    }

    @Test
    void mapsTheTradeResponseToTheContractsType() {
        server.expect(requestTo("http://trade.invalid/api/quotas/1.2")).andRespond(withSuccess(
                "{\"quotaRef\":\"1.2\",\"tradeRef\":\"1\",\"deskId\":\"DESK-1\",\"businessLine\":\"RM\",\"qty\":\"333.3334\"}",
                MediaType.APPLICATION_JSON));

        var quota = directory().find("1.2").orElseThrow();

        assertThat(quota.deskId()).isEqualTo("DESK-1");
        assertThat(quota.qty()).isEqualByComparingTo("333.3334");
    }

    @Test
    void aMissingQuotaIsEmptyNotAnError() {
        server.expect(requestTo("http://trade.invalid/api/quotas/9.9")).andRespond(withStatus(HttpStatus.NOT_FOUND));
        assertThat(directory().find("9.9")).isEmpty();
    }

    // PROVES: a trade-service failure is NOT silently treated as "quota does not exist".
    @Test
    void aServerErrorPropagatesInsteadOfLookingLikeAMissingQuota() {
        server.expect(requestTo("http://trade.invalid/api/quotas/1.1")).andRespond(withServerError());
        assertThatThrownBy(() -> directory().find("1.1")).isInstanceOf(RestClientException.class);
    }

    // PROVES the fixation adapter: 404 means "pricing has not seen it yet" = no fixation; an outage is NOT "no fixation".
    @Test
    void fixationAdapterMapsAnswersAndFailsClosedOnOutage() {
        var fixation = new HttpFixationDirectory(builder.baseUrl("http://pricing.invalid").build());
        server.expect(requestTo("http://pricing.invalid/api/assignments/1.1.1/fixation")).andRespond(withSuccess(
                "{\"assignmentRef\":\"1.1.1\",\"fixed\":true}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://pricing.invalid/api/assignments/1.1.2/fixation")).andRespond(withStatus(HttpStatus.NOT_FOUND));
        server.expect(requestTo("http://pricing.invalid/api/assignments/1.1.3/fixation")).andRespond(withServerError());

        assertThat(fixation.hasFixation("1.1.1")).isTrue();
        assertThat(fixation.hasFixation("1.1.2")).isFalse();
        assertThatThrownBy(() -> fixation.hasFixation("1.1.3")).isInstanceOf(RestClientException.class);
    }

    // PROVES the inputs adapter maps the pricing answer, treats 404 as "nothing", and does not hide an outage.
    @Test
    void valuationInputsAdapterMapsAnswersAndDoesNotHideOutages() {
        var provider = new HttpValuationInputsProvider(builder.baseUrl("http://pricing.invalid").build());
        server.expect(requestTo("http://pricing.invalid/api/valuation-inputs?subjectRef=1.1.1&subjectLevel=ASSIGNMENT&asOf=2026-09-18")).andRespond(withSuccess(
                "{\"subjectRef\":\"1.1.1\",\"level\":\"ASSIGNMENT\",\"brd\":\"2026-09-18\",\"pqrId\":\"00000000-0000-0000-0000-000000000001\","
                        + "\"qagrId\":null,\"businessLine\":\"RM\",\"assignments\":[{\"assignmentRef\":\"1.1.1\",\"parId\":\"00000000-0000-0000-0000-000000000002\","
                        + "\"qty\":\"100.0000\",\"approved\":true,\"components\":[],\"parameters\":[]}]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://pricing.invalid/api/valuation-inputs?subjectRef=1.1.2&subjectLevel=ASSIGNMENT&asOf=2026-09-18")).andRespond(withStatus(HttpStatus.NOT_FOUND));
        server.expect(requestTo("http://pricing.invalid/api/valuation-inputs?subjectRef=1.1.3&subjectLevel=ASSIGNMENT&asOf=2026-09-18")).andRespond(withServerError());

        var got = provider.assemble("1.1.1", io.commodity.contracts.valuation.SubjectLevel.ASSIGNMENT, java.time.LocalDate.of(2026, 9, 18)).orElseThrow();
        assertThat(got.assignments().get(0).approved()).isTrue();
        assertThat(got.assignments().get(0).qty()).isEqualByComparingTo("100");
        assertThat(provider.assemble("1.1.2", io.commodity.contracts.valuation.SubjectLevel.ASSIGNMENT, java.time.LocalDate.of(2026, 9, 18))).isEmpty();
        assertThatThrownBy(() -> provider.assemble("1.1.3", io.commodity.contracts.valuation.SubjectLevel.ASSIGNMENT, java.time.LocalDate.of(2026, 9, 18)))
                .isInstanceOf(RestClientException.class);
    }

    // PROVES the submitter maps the gateway's answers: 200 is a cache hit, 202 is accepted, and a 409 refusal keeps the gateway's reason and numbers.
    @Test
    void valuationSubmitterMapsCacheHitsAcceptanceAndRefusals() {
        var submitter = new HttpValuationSubmitter(builder.baseUrl("http://gateway.invalid").build());
        String id = "00000000-0000-0000-0000-000000000009";
        server.expect(requestTo("http://gateway.invalid/api/valuations")).andRespond(withSuccess(
                "{\"requestId\":\"" + id + "\",\"requestKey\":\"sha256:a\",\"status\":\"COMPLETED\",\"result\":{\"value\":\"1\"},\"cached\":true}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://gateway.invalid/api/valuations")).andRespond(
                org.springframework.test.web.client.response.MockRestResponseCreators.withStatus(HttpStatus.ACCEPTED).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"requestId\":\"" + id + "\",\"requestKey\":\"sha256:b\",\"status\":\"PENDING\"}"));
        server.expect(requestTo("http://gateway.invalid/api/valuations")).andRespond(
                org.springframework.test.web.client.response.MockRestResponseCreators.withStatus(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"type\":\"https://commodity.demo/errors/brd-closed\",\"title\":\"closed\",\"status\":409,\"deskBrd\":\"2026-09-18\"}"));

        var d = java.time.LocalDate.of(2026, 9, 17);
        var lane = io.commodity.contracts.valuation.Lane.BULK;
        var level = io.commodity.contracts.valuation.SubjectLevel.ASSIGNMENT;
        assertThat(submitter.submit("1.1.1", level, d, lane, true, "t").cached()).isTrue();
        var accepted = submitter.submit("1.1.2", level, d, lane, true, "t");
        assertThat(accepted.cached()).isFalse();
        assertThat(accepted.status()).isEqualTo("PENDING");
        assertThatThrownBy(() -> submitter.submit("1.1.3", level, d, lane, false, "t")).isInstanceOfSatisfying(io.commodity.platform.error.DomainException.class, e -> {
            assertThat(e.status()).isEqualTo(409);
            assertThat(e.type()).isEqualTo("brd-closed");
            assertThat(e.details()).containsEntry("deskBrd", "2026-09-18");
        });
    }
}
