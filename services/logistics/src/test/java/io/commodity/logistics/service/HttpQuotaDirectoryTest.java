package io.commodity.logistics.service;

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

/** The HTTP adapter behind the QuotaDirectory port, against a mocked trade service (no network, no Spring context). */
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
}
