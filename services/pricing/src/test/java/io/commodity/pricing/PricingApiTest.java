package io.commodity.pricing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.jayway.jsonpath.JsonPath;
import io.commodity.contracts.events.ChangeKind;
import io.commodity.contracts.events.QagRevisionEvent;
import io.commodity.contracts.lookup.BusinessDayClock;
import io.commodity.contracts.lookup.QuotaDirectory;
import io.commodity.contracts.valuation.Lane;
import io.commodity.contracts.valuation.SubjectLevel;
import io.commodity.contracts.valuation.ValuationSubmitter;
import io.commodity.contracts.lookup.QuotaView;
import io.commodity.platform.error.DomainException;
import io.commodity.pricing.service.QagRevisionHandler;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The pricing service through HTTP on a real Spring context and real Postgres. Trade and Business Day Control sit behind ports and
 * are faked; revisions are seeded by handing QAG revision events to the real handler, exactly as the Kafka listener would.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class PricingApiTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16").withStartupAttempts(5);

    static final Map<String, QuotaView> QUOTAS = new ConcurrentHashMap<>();
    static final AtomicReference<LocalDate> BRD = new AtomicReference<>(LocalDate.of(2026, 9, 18));

    @TestConfiguration
    static class Ports {
        @Bean QuotaDirectory quotaDirectory() { return ref -> Optional.ofNullable(QUOTAS.get(ref)); }
        @Bean BusinessDayClock clock() { return desk -> BRD.get(); }

        /** The gateway is another service: replay talks to it through a port, and the test records what it was asked. */
        @Bean ValuationSubmitter gateway() {
            return (ref, level, brd, lane, restatement, source) -> {
                SUBMISSIONS.add(new Object[] {ref, level, brd, lane, restatement, source});
                return new ValuationSubmitter.Submission(UUID.randomUUID(), "sha256:" + ref, "COMPLETED", true);
            };
        }
    }

    static final List<Object[]> SUBMISSIONS = new java.util.concurrent.CopyOnWriteArrayList<>();

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired QagRevisionHandler handler;

    /** Registers a quota and seeds pricing with a first QAG revision holding the given assignments (seq -> qty). */
    private void seed(String quotaRef, String... seqAndQty) {
        QUOTAS.put(quotaRef, new QuotaView(quotaRef, quotaRef.split("\\.")[0], "DESK-1", "CONCENTRATES", new BigDecimal("100000")));
        handler.handle(UUID.randomUUID(), event(quotaRef, BRD.get(), seqAndQty));
    }

    private static QagRevisionEvent event(String quotaRef, LocalDate brd, String... seqAndQty) {
        List<QagRevisionEvent.Member> members = new ArrayList<>();
        for (int i = 0; i < seqAndQty.length; i += 2) members.add(new QagRevisionEvent.Member(quotaRef + "." + seqAndQty[i], new BigDecimal(seqAndQty[i + 1])));
        return new QagRevisionEvent(UUID.randomUUID(), null, quotaRef, brd, List.of(ChangeKind.ALLOCATION), List.of(), List.of(), List.of(), members);
    }

    private static String fixed(String qty, String price) {
        return "{\"kind\":\"FIXED\",\"qty\":\"" + qty + "\",\"fixedPrice\":\"" + price + "\"}";
    }

    private void fix(String assignmentRef, String qty) throws Exception {
        mvc.perform(post("/api/assignments/" + assignmentRef + "/price-components").contentType(MediaType.APPLICATION_JSON)
                .content(fixed(qty, "8500"))).andExpect(status().isCreated());
    }

    private int count(String sql, Object... args) { return jdbc.queryForObject(sql, Integer.class, args); }

    // PROVES priced/unpriced are DERIVED on read, and a component is created with the ids the spec names.
    @Test
    void fixingPartOfAnAssignmentShowsPricedAndUnpricedQuantity() throws Exception {
        seed("201.1", "1", "100");
        String body = mvc.perform(post("/api/assignments/201.1.1/price-components").contentType(MediaType.APPLICATION_JSON).content(fixed("10", "8500")))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.pcId").isNotEmpty()).andExpect(jsonPath("$.pqrId").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        String pqrId = JsonPath.read(body, "$.pqrId");

        mvc.perform(get("/api/quotas/201.1/pricing"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.pqrId").value(pqrId))
                .andExpect(jsonPath("$.assignments[0].qty").value("100.0000"))
                .andExpect(jsonPath("$.assignments[0].pricedQty").value("10.0000"))
                .andExpect(jsonPath("$.assignments[0].unpricedQty").value("90.0000"))
                .andExpect(jsonPath("$.assignments[0].overFixed").value(false))
                .andExpect(jsonPath("$.assignments[0].components[0].fixedPrice").value("8500.000000"));
    }

    // PROVES acceptance item: over-fixation returns 409 with the offending quantities in the body (spec error shape).
    @Test
    void overFixationIs409WithTheOffendingQuantities() throws Exception {
        seed("202.1", "3", "250");
        fix("202.1.3", "200");
        int revisions = count("SELECT count(*) FROM pricing.quota_revision WHERE quota_ref = '202.1'");

        mvc.perform(post("/api/assignments/202.1.3/price-components").contentType(MediaType.APPLICATION_JSON).content(fixed("75", "8500")))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://commodity.demo/errors/over-fixation"))
                .andExpect(jsonPath("$.title").value("Price component quantity exceeds assignment quantity"))
                .andExpect(jsonPath("$.assignmentRef").value("202.1.3"))
                .andExpect(jsonPath("$.assignmentQty").value("250.0000"))
                .andExpect(jsonPath("$.existingComponentQty").value("200.0000"))
                .andExpect(jsonPath("$.requestedQty").value("75.0000"));
        assertThat(count("SELECT count(*) FROM pricing.quota_revision WHERE quota_ref = '202.1'")).isEqualTo(revisions); // nothing written
    }

    // PROVES the invariant under concurrency: two simultaneous fixations that together would over-fix -> exactly one succeeds.
    @Test
    void concurrentFixationsCannotTogetherOverFixTheAssignment() throws Exception {
        seed("203.1", "1", "100");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            results.add(pool.submit(() -> {
                start.await();
                return mvc.perform(post("/api/assignments/203.1.1/price-components").contentType(MediaType.APPLICATION_JSON)
                        .content(fixed("60", "8500"))).andReturn().getResponse().getStatus();
            }));
        }
        start.countDown();
        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> f : results) statuses.add(f.get(30, TimeUnit.SECONDS));
        pool.shutdown();

        assertThat(statuses).containsExactlyInAnyOrder(201, 409);
        mvc.perform(get("/api/quotas/203.1/pricing")).andExpect(jsonPath("$.assignments[0].pricedQty").value("60.0000"));
    }

    @Test
    void parametersAreRecordedAndInvalidInputIs400() throws Exception {
        seed("204.1", "1", "100");
        mvc.perform(post("/api/assignments/204.1.1/parameters").contentType(MediaType.APPLICATION_JSON).content("{\"element\":\"AG\",\"value\":\"12.5\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.pprId").isNotEmpty());
        mvc.perform(get("/api/quotas/204.1/pricing")).andExpect(jsonPath("$.assignments[0].parameters[0].element").value("AG"))
                .andExpect(jsonPath("$.assignments[0].parameters[0].value").value("12.500000"));

        mvc.perform(post("/api/assignments/204.1.1/price-components").contentType(MediaType.APPLICATION_JSON)
                .content("{\"kind\":\"FIXED\",\"qty\":\"5\"}")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value(endsWith("invalid-price-component")));
        mvc.perform(post("/api/assignments/204.1.1/price-components").contentType(MediaType.APPLICATION_JSON)
                .content("{\"kind\":\"WEIRD\",\"qty\":\"5\"}")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("kind"));
        mvc.perform(post("/api/assignments/204.1.9/parameters").contentType(MediaType.APPLICATION_JSON).content("{\"element\":\"AG\",\"value\":\"1\"}"))
                .andExpect(status().isNotFound());
    }

    // PROVES reproducibility over HTTP: the same quota resolves differently at different BRDs, and never sees a later change.
    @Test
    void pricingCanBeResolvedAsOfAPastBrd() throws Exception {
        LocalDate d1 = LocalDate.of(2026, 9, 10), d2 = LocalDate.of(2026, 9, 12);
        BRD.set(d1);
        seed("205.1", "1", "100");
        BRD.set(d2);
        fix("205.1.1", "40");
        BRD.set(LocalDate.of(2026, 9, 18));

        mvc.perform(get("/api/quotas/205.1/pricing").param("asOf", "2026-09-11"))
                .andExpect(jsonPath("$.brd").value("2026-09-10")).andExpect(jsonPath("$.assignments[0].pricedQty").value("0.0000"));
        mvc.perform(get("/api/quotas/205.1/pricing").param("asOf", "2026-09-12"))
                .andExpect(jsonPath("$.brd").value("2026-09-12")).andExpect(jsonPath("$.assignments[0].pricedQty").value("40.0000"));
        mvc.perform(get("/api/quotas/205.1/pricing").param("asOf", "2026-09-01")).andExpect(status().isNotFound());
        mvc.perform(get("/api/quotas/205.1/revisions")).andExpect(jsonPath("$", hasSize(2)));
    }

    // PROVES the owner decision: approval is its own insert-only record. Default UNAPPROVED, latest wins, history kept,
    // and approving never cuts a pricing revision.
    @Test
    void approvalIsAnInsertOnlyRecordAndDoesNotCutARevision() throws Exception {
        seed("206.1", "1", "100");
        int revisions = count("SELECT count(*) FROM pricing.quota_revision WHERE quota_ref = '206.1'");
        mvc.perform(get("/api/quotas/206.1/pricing")).andExpect(jsonPath("$.assignments[0].approvalStatus").value("UNAPPROVED"));

        mvc.perform(post("/api/assignments/206.1.1/approval").contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"APPROVED\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("APPROVED"));
        mvc.perform(get("/api/quotas/206.1/pricing")).andExpect(jsonPath("$.assignments[0].approvalStatus").value("APPROVED"));
        mvc.perform(post("/api/assignments/206.1.1/approval").contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"UNAPPROVED\"}"))
                .andExpect(status().isCreated());
        mvc.perform(get("/api/quotas/206.1/pricing")).andExpect(jsonPath("$.assignments[0].approvalStatus").value("UNAPPROVED"));

        assertThat(count("SELECT count(*) FROM pricing.quota_revision WHERE quota_ref = '206.1'")).isEqualTo(revisions);
        assertThat(count("SELECT count(*) FROM pricing.assignment_approval WHERE assignment_ref = '206.1.1'")).isEqualTo(2); // history kept
        mvc.perform(post("/api/assignments/206.1.7/approval").contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"APPROVED\"}"))
                .andExpect(status().isNotFound());
    }

    // PROVES the endpoint behind the FixationDirectory port that logistics uses to protect quantity.
    @Test
    void fixationEndpointReportsWhetherAnyComponentExists() throws Exception {
        seed("207.1", "1", "100");
        mvc.perform(get("/api/assignments/207.1.1/fixation")).andExpect(jsonPath("$.fixed").value(false));
        fix("207.1.1", "10");
        mvc.perform(get("/api/assignments/207.1.1/fixation")).andExpect(jsonPath("$.fixed").value(true));
        mvc.perform(get("/api/assignments/207.1.5/fixation")).andExpect(status().isNotFound());
    }

    // PROVES the closed-date rule: a revision for a BRD earlier than the desk's current BRD is refused with 409.
    @Test
    void aRevisionForAClosedBrdIsRefused() {
        seed("208.1", "1", "100");
        var stale = event("208.1", BRD.get().minusDays(1), "1", "100");

        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> handler.handle(UUID.randomUUID(), stale)))
                .isInstanceOfSatisfying(DomainException.class, e -> {
                    assertThat(e.status()).isEqualTo(409);
                    assertThat(e.type()).isEqualTo("brd-closed");
                    assertThat(e.details()).containsKeys("revisionBrd", "deskBrd");
                });
        assertThat(count("SELECT count(*) FROM pricing.quota_revision WHERE quota_ref = '208.1'")).isEqualTo(1);
    }

    // PROVES at-least-once safety: the same event delivered twice applies once, and a FAILED attempt leaves no dedup marker
    // behind (so the retry is processed for real).
    @Test
    void aDuplicateEventIsAppliedOnceAndAFailedAttemptCanBeRetried() {
        QUOTAS.put("209.1", new QuotaView("209.1", "209", "DESK-1", "RM", new BigDecimal("100")));
        UUID eventId = UUID.randomUUID();
        var ev = event("209.1", BRD.get(), "1", "100");

        assertThat(handler.handle(eventId, ev)).isEqualTo(QagRevisionHandler.Result.APPLIED);
        assertThat(handler.handle(eventId, ev)).isEqualTo(QagRevisionHandler.Result.DUPLICATE);
        assertThat(count("SELECT count(*) FROM pricing.quota_revision WHERE quota_ref = '209.1'")).isEqualTo(1);

        UUID failing = UUID.randomUUID();
        var closed = event("209.1", BRD.get().minusDays(3), "1", "100");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> handler.handle(failing, closed)).isInstanceOf(DomainException.class);
        assertThat(count("SELECT count(*) FROM pricing.processed_event WHERE event_id = ?", failing)).isZero(); // rolled back with the failure
    }

    // PROVES the race fallback owned in the addendum: a quantity below the fixed quantity is applied and reported over-fixed.
    @Test
    void aQuantityReducedBelowTheFixedQuantityIsAppliedAndFlagged() throws Exception {
        seed("210.1", "1", "100");
        fix("210.1.1", "80");
        handler.handle(UUID.randomUUID(), event("210.1", BRD.get(), "1", "60"));

        mvc.perform(get("/api/quotas/210.1/pricing")).andExpect(jsonPath("$.assignments[0].qty").value("60.0000"))
                .andExpect(jsonPath("$.assignments[0].unpricedQty").value("-20.0000")).andExpect(jsonPath("$.assignments[0].overFixed").value(true));
    }

    // PROVES the write-amplification claim through the real service: a price change on 1 of 20 assignments writes a revision
    // whose new rows are only that assignment's subtree (the other 19 are shared).
    @Test
    void aPriceChangeThroughTheServiceSharesTheUntouchedAssignments() throws Exception {
        String[] members = new String[40];
        for (int i = 0; i < 20; i++) { members[2 * i] = String.valueOf(i + 1); members[2 * i + 1] = "1000"; }
        seed("211.1", members);
        int assignmentRevisionsBefore = count("SELECT count(*) FROM pricing.assignment_revision WHERE assignment_ref LIKE '211.1.%'");
        assertThat(assignmentRevisionsBefore).isEqualTo(20);

        fix("211.1.7", "10");

        assertThat(count("SELECT count(*) FROM pricing.assignment_revision WHERE assignment_ref LIKE '211.1.%'")).isEqualTo(21); // 1 new, 19 shared
        assertThat(count("SELECT count(*) FROM pricing.quota_revision_member m JOIN pricing.quota_revision q ON q.pqr_id = m.pqr_id"
                + " WHERE q.quota_ref = '211.1'")).isEqualTo(40); // both revisions link all 20
    }

    // PROVES the bridge from structural sharing to idempotent valuation: an assignment that did NOT change keeps the same row ids
    // when a DIFFERENT assignment in its quota is repriced. Valuation keys are built from these ids, so they identify content.
    @Test
    void unchangedAssignmentsKeepTheirIdsWhenAnotherAssignmentChanges() throws Exception {
        seed("212.1", "1", "100", "2", "100");
        fix("212.1.1", "10");
        var before = mvc.perform(get("/api/valuation-inputs").param("subjectRef", "212.1.1").param("subjectLevel", "ASSIGNMENT").param("asOf", "2026-09-18"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        fix("212.1.2", "20"); // reprice the OTHER assignment: a new quota revision, but 212.1.1 is shared
        var after = mvc.perform(get("/api/valuation-inputs").param("subjectRef", "212.1.1").param("subjectLevel", "ASSIGNMENT").param("asOf", "2026-09-18"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(JsonPath.<String>read(after, "$.assignments[0].parId")).isEqualTo(JsonPath.read(before, "$.assignments[0].parId"));
        assertThat(JsonPath.<String>read(after, "$.assignments[0].components[0].pcId")).isEqualTo(JsonPath.read(before, "$.assignments[0].components[0].pcId"));
        assertThat(JsonPath.<String>read(after, "$.pqrId")).isNotEqualTo(JsonPath.read(before, "$.pqrId")); // the quota revision moved on
    }

    @Test
    void valuationInputsCarryApprovalAndSupportBothLevels() throws Exception {
        seed("213.1", "1", "100", "2", "50");
        mvc.perform(post("/api/assignments/213.1.1/approval").contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"APPROVED\"}")).andExpect(status().isCreated());

        mvc.perform(get("/api/valuation-inputs").param("subjectRef", "213.1.1").param("subjectLevel", "ASSIGNMENT").param("asOf", "2026-09-18"))
                .andExpect(jsonPath("$.assignments", hasSize(1))).andExpect(jsonPath("$.assignments[0].approved").value(true))
                .andExpect(jsonPath("$.businessLine").value("CONCENTRATES"));
        mvc.perform(get("/api/valuation-inputs").param("subjectRef", "213.1").param("subjectLevel", "QUOTA").param("asOf", "2026-09-18"))
                .andExpect(jsonPath("$.assignments", hasSize(2))).andExpect(jsonPath("$.assignments[1].approved").value(false));
        mvc.perform(get("/api/valuation-inputs").param("subjectRef", "213.1.9").param("subjectLevel", "ASSIGNMENT").param("asOf", "2026-09-18"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/valuation-inputs").param("subjectRef", "213.1.1").param("subjectLevel", "ASSIGNMENT").param("asOf", "2026-01-01"))
                .andExpect(status().isNotFound()); // nothing existed on that date
    }

    // PROVES replay: it re-requests valuation for every assignment of the stored revision (assignment level outside RM), asks with the
    // restatement flag at the revision's own BRD, and is READ-ONLY: no pricing revision is written.
    @Test
    void replayReRequestsValuationFromTheStoredRevisionWithoutWritingAnything() throws Exception {
        seed("214.1", "1", "100", "2", "50");
        fix("214.1.1", "10");
        int revisions = count("SELECT count(*) FROM pricing.quota_revision WHERE quota_ref = '214.1'");
        SUBMISSIONS.clear();

        mvc.perform(post("/api/replay").contentType(MediaType.APPLICATION_JSON).content("{\"quotaRef\":\"214.1\"}"))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.requests", hasSize(2)))
                .andExpect(jsonPath("$.requests[0].cached").value(true)).andExpect(jsonPath("$.brd").value("2026-09-18"));

        assertThat(SUBMISSIONS).hasSize(2).allSatisfy(c -> {
            assertThat(c[1]).isEqualTo(SubjectLevel.ASSIGNMENT);
            assertThat(c[2]).isEqualTo(LocalDate.of(2026, 9, 18));
            assertThat(c[3]).isEqualTo(Lane.BULK);
            assertThat(c[4]).isEqualTo(true);      // restatement: reproducing a past date is a restatement of history
            assertThat(c[5]).isEqualTo("pricing-replay");
        });
        assertThat(count("SELECT count(*) FROM pricing.quota_revision WHERE quota_ref = '214.1'")).isEqualTo(revisions); // read-only
    }

    @Test
    void replayForRmValuesAtQuotaLevelAndByRevisionIdAndRefusesASupersededRevision() throws Exception {
        QUOTAS.put("215.1", new QuotaView("215.1", "215", "DESK-1", "RM", new BigDecimal("100000")));
        handler.handle(UUID.randomUUID(), event("215.1", BRD.get(), "1", "100", "2", "50"));
        String first = JsonPath.read(mvc.perform(get("/api/quotas/215.1/revisions")).andReturn().getResponse().getContentAsString(), "$[0].pqrId");
        SUBMISSIONS.clear();

        mvc.perform(post("/api/replay").contentType(MediaType.APPLICATION_JSON).content("{\"pqrId\":\"" + first + "\"}"))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.requests", hasSize(1)));   // one quota-level request, not one per assignment
        assertThat(SUBMISSIONS.get(0)[0]).isEqualTo("215.1");
        assertThat(SUBMISSIONS.get(0)[1]).isEqualTo(SubjectLevel.QUOTA);

        fix("215.1.1", "10"); // a newer revision on the SAME business date supersedes the first for resolution by date
        mvc.perform(post("/api/replay").contentType(MediaType.APPLICATION_JSON).content("{\"pqrId\":\"" + first + "\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.type").value(endsWith("revision-superseded-on-brd")));
        mvc.perform(post("/api/replay").contentType(MediaType.APPLICATION_JSON).content("{\"quotaRef\":\"215.1\"}")).andExpect(status().isAccepted());

        mvc.perform(post("/api/replay").contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/replay").contentType(MediaType.APPLICATION_JSON).content("{\"quotaRef\":\"999.1\"}")).andExpect(status().isNotFound());
    }

    // PROVES the read-model feed: every pricing change and every approval leaves a FULL quota snapshot in the outbox (same transaction), carrying the derived
    // priced/unpriced quantities and the approval state, keyed by quota so a quota's snapshots stay in order.
    @Test
    void everyPricingChangeAndApprovalPublishesAQuotaSnapshot() throws Exception {
        seed("216.1", "1", "100", "2", "40");
        fix("216.1.1", "30");
        mvc.perform(post("/api/assignments/216.1.2/approval").contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"APPROVED\"}")).andExpect(status().isCreated());

        List<String> payloads = jdbc.queryForList("SELECT payload FROM pricing.outbox WHERE topic = 'pricing.quota.published.v1' AND msg_key = '216.1' ORDER BY id", String.class);
        assertThat(payloads).hasSize(3); // the seeding revision, the fixation, the approval

        var mapper = new com.fasterxml.jackson.databind.ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
        var fixation = mapper.readValue(payloads.get(1), io.commodity.contracts.events.PricingQuotaPublished.class);
        assertThat(fixation.cause()).isEqualTo("REVISION");
        assertThat(fixation.assignments()).hasSize(2);
        var first = fixation.assignments().get(0);
        assertThat(first.assignmentRef()).isEqualTo("216.1.1");
        assertThat(first.pricedQty()).isEqualByComparingTo("30");
        assertThat(first.unpricedQty()).isEqualByComparingTo("70");
        assertThat(first.approvalStatus()).isEqualTo("UNAPPROVED");

        var approval = mapper.readValue(payloads.get(2), io.commodity.contracts.events.PricingQuotaPublished.class);
        assertThat(approval.cause()).isEqualTo("APPROVAL");
        assertThat(approval.pqrId()).isEqualTo(fixation.pqrId());                               // approval is not a revision: same pqrId
        assertThat(approval.assignments().get(1).approvalStatus()).isEqualTo("APPROVED");
    }
}
