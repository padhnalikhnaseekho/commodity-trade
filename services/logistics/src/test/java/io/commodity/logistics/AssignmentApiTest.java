package io.commodity.logistics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import io.commodity.contracts.events.QagRevisionEvent;
import io.commodity.contracts.events.Topics;
import io.commodity.contracts.lookup.BusinessDayClock;
import io.commodity.contracts.lookup.QuotaDirectory;
import io.commodity.contracts.lookup.QuotaView;
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
 * The logistics write path through HTTP on a real Spring context and real Postgres. The trade service and Business Day
 * Control are behind ports, so the test supplies in-memory fakes (no other service module is involved).
 *
 * <p>Each test uses its own quota ref, so tests are independent of execution order.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class AssignmentApiTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    static final Map<String, QuotaView> QUOTAS = new ConcurrentHashMap<>();
    static final AtomicReference<LocalDate> BRD = new AtomicReference<>(LocalDate.of(2026, 9, 18));

    @TestConfiguration
    static class Ports {
        @Bean QuotaDirectory quotaDirectory() { return ref -> Optional.ofNullable(QUOTAS.get(ref)); }
        @Bean BusinessDayClock clock() { return desk -> BRD.get(); }
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;

    private static void quota(String ref, String qty) {
        QUOTAS.put(ref, new QuotaView(ref, ref.substring(0, ref.indexOf('.')), "DESK-1", "CONCENTRATES", new BigDecimal(qty)));
    }

    private String add(String quotaRef, String qty) throws Exception {
        String body = mvc.perform(post("/api/quotas/" + quotaRef + "/assignments").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"qty\":\"" + qty + "\",\"changeKind\":\"ALLOCATION\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.assignmentRef");
    }

    private List<QagRevisionEvent> revisionEvents(String quotaRef) throws Exception {
        List<QagRevisionEvent> out = new ArrayList<>();
        for (String payload : jdbc.queryForList("SELECT payload FROM logistics.outbox WHERE topic = ? AND msg_key = ? ORDER BY id",
                String.class, Topics.QAG_REVISION, quotaRef)) {
            out.add(json.readValue(payload, QagRevisionEvent.class));
        }
        return out;
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    // PROVES exit criterion: adding an assignment cuts a QAGR whose assignmentsAdded holds exactly that ref.
    @Test
    void addingAnAssignmentCutsARevisionWhoseDiffIsExactlyThatRef() throws Exception {
        quota("101.1", "1000");
        String ref = add("101.1", "100");

        assertThat(ref).isEqualTo("101.1.1");
        var events = revisionEvents("101.1");
        assertThat(events).hasSize(1);
        QagRevisionEvent e = events.get(0);
        assertThat(e.assignmentsAdded()).containsExactly("101.1.1");
        assertThat(e.assignmentsRemoved()).isEmpty();
        assertThat(e.assignmentsModified()).isEmpty();
        assertThat(e.previousQagrId()).isNull();
        assertThat(e.changeKinds()).extracting(Enum::name).containsExactly("ALLOCATION");
        assertThat(e.members()).extracting(QagRevisionEvent.Member::assignmentRef).containsExactly("101.1.1");
        assertThat(e.brd()).isEqualTo(LocalDate.of(2026, 9, 18)); // the desk's BRD, not wall clock
    }

    // PROVES exit criterion: modifying one assignment out of twenty lists exactly one modified ref.
    @Test
    void modifyingOneOfTwentyAssignmentsListsExactlyOneModifiedRef() throws Exception {
        quota("102.1", "100000");
        for (int i = 0; i < 20; i++) add("102.1", "100");
        assertThat(revisionEvents("102.1")).hasSize(20);

        mvc.perform(patch("/api/assignments/102.1.7").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"qty\":\"250\",\"changeKind\":\"ALLOCATION\"}"))
                .andExpect(status().isOk());

        var events = revisionEvents("102.1");
        assertThat(events).hasSize(21);
        QagRevisionEvent last = events.get(20);
        assertThat(last.assignmentsModified()).containsExactly("102.1.7");
        assertThat(last.assignmentsAdded()).isEmpty();
        assertThat(last.assignmentsRemoved()).isEmpty();
        assertThat(last.members()).hasSize(20); // full membership, so consumers can rebuild without reading back
        assertThat(last.previousQagrId()).isEqualTo(events.get(19).qagrId()); // chain
    }

    // PROVES exit criterion: an operational change (load, discharge) cuts NO revision.
    @Test
    void operationalChangeCutsNoRevisionButIsPublishedOnTheOperationalTopic() throws Exception {
        quota("103.1", "1000");
        add("103.1", "100");
        int revisionsBefore = count("SELECT count(*) FROM logistics.qag_revision WHERE quota_ref = '103.1'");
        int qagEventsBefore = revisionEvents("103.1").size();

        for (String kind : List.of("LOAD", "DISCHARGE", "BILL_OF_LADING", "INSURANCE_TRANSFER")) {
            mvc.perform(post("/api/assignments/103.1.1/events").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"changeKind\":\"" + kind + "\"}")).andExpect(status().isAccepted());
        }

        assertThat(count("SELECT count(*) FROM logistics.qag_revision WHERE quota_ref = '103.1'")).isEqualTo(revisionsBefore);
        assertThat(revisionEvents("103.1")).hasSize(qagEventsBefore);
        assertThat(count("SELECT count(*) FROM logistics.outbox WHERE topic = ? AND msg_key = '103.1.1'",
                Topics.SHIPPING_OPERATIONAL)).isEqualTo(4);
    }

    @Test
    void changeKindIsCheckedAgainstTheEndpoint() throws Exception {
        quota("104.1", "1000");
        add("104.1", "100");
        // an operational kind cannot cut a revision...
        mvc.perform(post("/api/quotas/104.1/assignments").contentType(MediaType.APPLICATION_JSON)
                .content("{\"qty\":\"10\",\"changeKind\":\"LOAD\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.type").value(endsWith("change-kind-not-material")));
        // ...and a material kind does not belong on the operational endpoint
        mvc.perform(post("/api/assignments/104.1.1/events").contentType(MediaType.APPLICATION_JSON)
                .content("{\"changeKind\":\"SPLIT\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.type").value(endsWith("change-kind-not-operational")));
        mvc.perform(post("/api/quotas/104.1/assignments").contentType(MediaType.APPLICATION_JSON)
                .content("{\"qty\":\"10\"}")).andExpect(status().isBadRequest());
        assertThat(count("SELECT count(*) FROM logistics.qag_revision WHERE quota_ref = '104.1'")).isEqualTo(1);
    }

    // PROVES: the quota cap is enforced with the numbers in the body, and a refused change leaves NOTHING behind
    // (no assignment, no revision, no event) because it all happens in one transaction.
    @Test
    void exceedingTheQuotaIs409WithNumbersAndLeavesNoTrace() throws Exception {
        quota("105.1", "1000");
        add("105.1", "750");
        int revisions = count("SELECT count(*) FROM logistics.qag_revision WHERE quota_ref = '105.1'");
        int outbox = count("SELECT count(*) FROM logistics.outbox WHERE msg_key = '105.1'");

        mvc.perform(post("/api/quotas/105.1/assignments").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"qty\":\"250.0001\",\"changeKind\":\"ALLOCATION\"}"))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://commodity.demo/errors/quota-quantity-exceeded"))
                .andExpect(jsonPath("$.quotaQty").value("1000"))
                .andExpect(jsonPath("$.existingAssignedQty").value("750.0000"))
                .andExpect(jsonPath("$.requestedQty").value("250.0001"));

        assertThat(count("SELECT count(*) FROM logistics.assignment WHERE quota_ref = '105.1'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM logistics.qag_revision WHERE quota_ref = '105.1'")).isEqualTo(revisions);
        assertThat(count("SELECT count(*) FROM logistics.outbox WHERE msg_key = '105.1'")).isEqualTo(outbox);
    }

    // PROVES the owner's rules: membership is ACTIVE only (leaving ACTIVE is "removed"), only ACTIVE can change,
    // and cancelled quantity frees room under the cap.
    @Test
    void cancellingRemovesFromMembershipAndFreesQuotaQuantity() throws Exception {
        quota("106.1", "1000");
        add("106.1", "600");
        add("106.1", "400");
        mvc.perform(post("/api/quotas/106.1/assignments").contentType(MediaType.APPLICATION_JSON)
                .content("{\"qty\":\"1\",\"changeKind\":\"ALLOCATION\"}")).andExpect(status().isConflict()); // full

        mvc.perform(patch("/api/assignments/106.1.1").contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"CANCELLED\",\"changeKind\":\"UNDO_SPLIT\"}")).andExpect(status().isOk());

        QagRevisionEvent last = revisionEvents("106.1").get(2);
        assertThat(last.assignmentsRemoved()).containsExactly("106.1.1");
        assertThat(last.members()).extracting(QagRevisionEvent.Member::assignmentRef).containsExactly("106.1.2");
        assertThat(add("106.1", "600")).isEqualTo("106.1.3"); // room freed; ref 106.1.1 is never reused

        mvc.perform(patch("/api/assignments/106.1.1").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"qty\":\"5\",\"changeKind\":\"ALLOCATION\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.type").value(endsWith("assignment-not-active")));
    }

    @Test
    void aPatchThatChangesNothingCutsNothing() throws Exception {
        quota("107.1", "1000");
        add("107.1", "100");
        String head = JsonPath.read(mvc.perform(patch("/api/assignments/107.1.1").contentType(MediaType.APPLICATION_JSON)
                .content("{\"qty\":\"100.0000\",\"changeKind\":\"ALLOCATION\"}")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), "$.qagrId");

        assertThat(head).isEqualTo(revisionEvents("107.1").get(0).qagrId().toString());
        assertThat(count("SELECT count(*) FROM logistics.qag_revision WHERE quota_ref = '107.1'")).isEqualTo(1);
    }

    // PROVES: reads resolve "latest revision as of a BRD" and never see a later BRD (monotonicity).
    @Test
    void readsResolveTheRevisionAsOfABrd() throws Exception {
        quota("108.1", "1000");
        LocalDate earlier = LocalDate.of(2026, 9, 10), later = LocalDate.of(2026, 9, 12);
        BRD.set(earlier);
        add("108.1", "100");
        BRD.set(later);
        add("108.1", "200");
        BRD.set(LocalDate.of(2026, 9, 18)); // restore for other tests

        mvc.perform(get("/api/quotas/108.1/qag").param("asOf", "2026-09-11"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.brd").value("2026-09-10"))
                .andExpect(jsonPath("$.members", hasSize(1)));
        mvc.perform(get("/api/quotas/108.1/qag").param("asOf", "2026-09-12"))
                .andExpect(jsonPath("$.brd").value("2026-09-12")).andExpect(jsonPath("$.members", hasSize(2)))
                .andExpect(jsonPath("$.previousQagrId").isNotEmpty());
        mvc.perform(get("/api/quotas/108.1/qag").param("asOf", "2026-09-01")).andExpect(status().isNotFound());
    }

    // PROVES the advisory lock: concurrent writers to ONE quota neither fork the revision chain nor reuse a ref.
    @Test
    void concurrentWritersToOneQuotaProduceALinearChainAndDistinctRefs() throws Exception {
        quota("109.1", "100000");
        int writers = 10;
        ExecutorService pool = Executors.newFixedThreadPool(writers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> results = new ArrayList<>();
        for (int i = 0; i < writers; i++) {
            results.add(pool.submit(() -> {
                start.await();
                return add("109.1", "10");
            }));
        }
        start.countDown();
        Set<String> refs = new TreeSet<>();
        for (Future<String> f : results) refs.add(f.get(30, TimeUnit.SECONDS));
        pool.shutdown();

        assertThat(refs).hasSize(writers); // no duplicate refs
        var events = revisionEvents("109.1");
        assertThat(events).hasSize(writers);
        // linear chain: exactly one root, and every revision is the predecessor of at most one other
        assertThat(events.stream().filter(e -> e.previousQagrId() == null)).hasSize(1);
        assertThat(events.stream().map(QagRevisionEvent::previousQagrId).filter(Objects::nonNull).distinct()).hasSize(writers - 1);
        // each revision adds exactly one ref: none lost to a race
        assertThat(events).allSatisfy(e -> assertThat(e.assignmentsAdded()).hasSize(1));
    }

    @Test
    void unknownQuotaAndAssignmentAre404() throws Exception {
        mvc.perform(post("/api/quotas/999.1/assignments").contentType(MediaType.APPLICATION_JSON)
                .content("{\"qty\":\"10\",\"changeKind\":\"ALLOCATION\"}")).andExpect(status().isNotFound());
        quota("110.1", "100");
        mvc.perform(patch("/api/assignments/110.1.9").contentType(MediaType.APPLICATION_JSON)
                .content("{\"qty\":\"10\",\"changeKind\":\"ALLOCATION\"}")).andExpect(status().isNotFound());
        mvc.perform(patch("/api/assignments/not-a-ref").contentType(MediaType.APPLICATION_JSON)
                .content("{\"qty\":\"10\",\"changeKind\":\"ALLOCATION\"}")).andExpect(status().isBadRequest());
    }
}
