package io.commodity.trade;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import io.commodity.contracts.lookup.BusinessDayClock;
import io.commodity.contracts.lookup.FunctionalLine;
import io.commodity.contracts.lookup.FunctionalLineDirectory;
import io.commodity.contracts.valuation.ValuationEngine;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** The trade write path through HTTP, real Spring context, real Postgres. */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class TradeApiTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    /** Business Day Control belongs to another service; the test supplies a fixed desk date behind the port. */
    @TestConfiguration
    static class Ports {
        @Bean BusinessDayClock clock() { return desk -> LocalDate.of(2026, 9, 18); }

        /** Reference data lives in another service; the test supplies a tiny fake with one valid override. */
        @Bean FunctionalLineDirectory lines() {
            return new FunctionalLineDirectory() {
                public FunctionalLine resolve(String bl, LocalDate created, String override) { return new FunctionalLine(bl + "_MODERN", ValuationEngine.MODERN); }
                public boolean isValid(String bl, String name) { return name.equals("RM_LEGACY") && bl.equals("RM"); }
            };
        }
    }

    @Autowired MockMvc mvc;

    private static String body(String periodicity, String from, String to, String qty, String periods) {
        return """
                {"businessLine":"CONCENTRATES","deskId":"DESK-1","side":"PURCHASE","counterparty":"ACME Mining",
                 "commodity":"Copper concentrate","totalQty":"%s","uom":"MT",
                 "delivery":{"periodicity":"%s","from":"%s","to":"%s"%s}}""".formatted(qty, periodicity, from, to, periods);
    }

    // PROVES exit criterion end to end: POST a monthly schedule over three months -> 201 with three quota refs.
    @Test
    void createsTradeWithThreeMonthlyQuotas() throws Exception {
        String response = mvc.perform(post("/api/trades").contentType(MediaType.APPLICATION_JSON)
                        .content(body("MONTHLY", "2026-01-01", "2026-03-31", "1000", "")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.quotas", hasSize(3)))
                .andReturn().getResponse().getContentAsString();
        String tradeRef = com.jayway.jsonpath.JsonPath.read(response, "$.tradeRef");

        mvc.perform(get("/api/trades/" + tradeRef + "/quotas"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].quotaRef", contains(tradeRef + ".1", tradeRef + ".2", tradeRef + ".3")))
                .andExpect(jsonPath("$[*].qty", contains("333.3333", "333.3333", "333.3334")));
        mvc.perform(get("/api/trades/" + tradeRef))
                .andExpect(jsonPath("$.createdBrd").value("2026-09-18")); // desk BRD, not wall-clock
        mvc.perform(get("/api/quotas/" + tradeRef + ".3"))
                .andExpect(jsonPath("$.deskId").value("DESK-1")).andExpect(jsonPath("$.qty").value("333.3334"));
    }

    // PROVES: domain errors come back as problem+json carrying the numbers that caused them.
    @Test
    void customQuantityMismatchIsAProblemJsonWithTheNumbers() throws Exception {
        String periods = ",\"periods\":[{\"from\":\"2026-01-01\",\"to\":\"2026-01-31\",\"qty\":\"30\"}]";
        mvc.perform(post("/api/trades").contentType(MediaType.APPLICATION_JSON)
                        .content(body("CUSTOM", "2026-01-01", "2026-06-30", "100", periods)))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://commodity.demo/errors/quota-quantity-mismatch"))
                .andExpect(jsonPath("$.totalQty").value("100.0000"))
                .andExpect(jsonPath("$.periodsQty").value("30.0000"));
    }

    // PROVES the owner decision: a trade may carry an explicit FunctionalLine override, validated at creation and visible to
    // other services through the quota lookup (which is how the gateway routes).
    @Test
    void anExplicitFunctionalLineIsValidatedStoredAndExposedToOtherServices() throws Exception {
        String rm = body("MONTHLY", "2026-01-01", "2026-01-31", "100", "").replace("CONCENTRATES", "RM").replace("\"delivery\"", "\"functionalLine\":\"RM_LEGACY\",\"delivery\"");
        String response = mvc.perform(post("/api/trades").contentType(MediaType.APPLICATION_JSON).content(rm))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String tradeRef = com.jayway.jsonpath.JsonPath.read(response, "$.tradeRef");

        mvc.perform(get("/api/trades/" + tradeRef)).andExpect(jsonPath("$.functionalLine").value("RM_LEGACY"));
        mvc.perform(get("/api/quotas/" + tradeRef + ".1"))
                .andExpect(jsonPath("$.functionalLine").value("RM_LEGACY")).andExpect(jsonPath("$.createdBrd").value("2026-09-18"));

        String wrong = body("MONTHLY", "2026-01-01", "2026-01-31", "100", "").replace("\"delivery\"", "\"functionalLine\":\"RM_LEGACY\",\"delivery\"");
        mvc.perform(post("/api/trades").contentType(MediaType.APPLICATION_JSON).content(wrong)) // CONCENTRATES has no RM_LEGACY line
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.type").value(endsWith("invalid-functional-line")));
    }

    @Test
    void invalidInputIsA400NotA500() throws Exception {
        mvc.perform(post("/api/trades").contentType(MediaType.APPLICATION_JSON)
                        .content(body("FORTNIGHTLY", "2026-01-01", "2026-03-31", "100", "")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("delivery.periodicity"))
                .andExpect(jsonPath("$.allowed").value(containsString("WEEKLY")));
        mvc.perform(post("/api/trades").contentType(MediaType.APPLICATION_JSON)
                        .content(body("MONTHLY", "2026-01-01", "2026-03-31", "1.23456", "")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("totalQty"));
        mvc.perform(get("/api/trades/9999")).andExpect(status().isNotFound());
    }
}
