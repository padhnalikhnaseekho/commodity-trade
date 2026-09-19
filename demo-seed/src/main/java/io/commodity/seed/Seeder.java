package io.commodity.seed;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Generates a realistic demo book by calling the application's public API, exactly as a user or a client system would.
 *
 * <p>The mix (agreed with the project owner): 50 trades across all four business lines and three desks. RM (copper cathode, nickel), concentrates (copper, zinc),
 * bulk (coal, iron ore) and energy (crude oil); purchases and sales; monthly, weekly and custom delivery; one to three assignments per quota; parameters
 * (silver, arsenic, moisture, particle size); partial fixations of all three price kinds, some provisional; a mix of approved and unapproved assignments; a
 * few RM trades pinned to the legacy engine by the explicit override; and valuations for a share of the book (RM at quota level, everything else at
 * assignment level), so the blotter shows provisional and final numbers.
 *
 * <p>Deterministic: the same seed produces the same book, so a demo is repeatable. It is NOT idempotent: running it twice creates two books.
 *
 * <p>Why it goes through the API and not the database: that is what proves the whole write path (trade, logistics, events, pricing, gateway, blotter) works
 * end to end, and the seeder itself depends on no service module.
 */
public final class Seeder {

    /** What the run created. */
    /** How many parameters and price components the seeder added to one assignment (used to verify the system holds exactly what was written). */
    public record Expected(int parameters, int components) {}

    public record Summary(int trades, int quotas, int assignments, int parameters, int components, int approvals, int valuationsRequested,
                          Map<String, Expected> perAssignment) {}

    private static final int TRADES = 50;
    private static final String[] COUNTERPARTIES = {"Northern Smelting", "Andes Mining", "Pacific Metals", "Gulf Energy", "Baltic Traders", "Sahara Resources", "Alpine Refining"};

    private final Http http;
    private final Random random;

    public Seeder(String baseUrl, long seed) {
        this.http = new Http(baseUrl);
        this.random = new Random(seed);
    }

    private record Line(String businessLine, String desk, List<String> commodities, String index) {}

    // 3 desks: metals (RM + concentrates), bulk, energy.
    private static final List<Line> LINES = List.of(
            new Line("RM", "DESK-METALS", List.of("Copper cathode", "Nickel"), "LME_COPPER"),
            new Line("CONCENTRATES", "DESK-METALS", List.of("Copper concentrate", "Zinc concentrate"), "LME_ZINC"),
            new Line("BULK", "DESK-BULK", List.of("Coal", "Iron ore"), "API2_COAL"),
            new Line("ENERGY", "DESK-ENERGY", List.of("Crude oil"), "BRENT"));

    private record Assignment(String ref, BigDecimal qty, String businessLine, String index) {}

    public Summary run() {
        int quotas = 0, assignmentsCount = 0, parameters = 0, components = 0, approvals = 0, valuations = 0;
        Map<String, int[]> perAssignment = new LinkedHashMap<>();
        List<Assignment> assignments = new ArrayList<>();
        Map<String, List<String>> quotaAssignments = new LinkedHashMap<>();   // quotaRef -> assignment refs
        Map<String, Line> quotaLine = new LinkedHashMap<>();

        // 1. trades, quotas and assignments
        int rmCount = 0;
        for (int i = 0; i < TRADES; i++) {
            Line line = LINES.get(i % LINES.size());
            boolean rm = line.businessLine().equals("RM");
            var body = new LinkedHashMap<String, Object>();
            body.put("businessLine", line.businessLine());
            body.put("deskId", line.desk());
            body.put("side", random.nextBoolean() ? "PURCHASE" : "SALE");
            body.put("counterparty", COUNTERPARTIES[random.nextInt(COUNTERPARTIES.length)]);
            body.put("commodity", line.commodities().get(random.nextInt(line.commodities().size())));
            BigDecimal total = BigDecimal.valueOf(1_000L * (3 + random.nextInt(58))).setScale(4);   // 3,000 .. 60,000 MT
            body.put("totalQty", total.toPlainString());
            body.put("uom", "MT");
            body.put("delivery", delivery(total, i));
            if (rm && (rmCount++ % 3 == 0)) body.put("functionalLine", "RM_LEGACY");   // every third RM trade is pinned to the legacy engine by the override

            JsonNode created = http.post("/api/trades", body);
            String tradeRef = created.get("tradeRef").asText();
            for (JsonNode q : http.get("/api/trades/" + tradeRef + "/quotas")) {
                String quotaRef = q.get("quotaRef").asText();
                BigDecimal quotaQty = new BigDecimal(q.get("qty").asText());
                quotas++;
                quotaLine.put(quotaRef, line);
                int n = 1 + random.nextInt(3);
                BigDecimal each = quotaQty.divide(BigDecimal.valueOf(n), 4, RoundingMode.DOWN);
                List<String> refs = new ArrayList<>();
                for (int a = 0; a < n; a++) {
                    JsonNode made = http.post("/api/quotas/" + quotaRef + "/assignments", Map.of("qty", each.toPlainString(), "changeKind", "ALLOCATION"));
                    String ref = made.get("assignmentRef").asText();
                    refs.add(ref);
                    assignments.add(new Assignment(ref, each, line.businessLine(), line.index()));
                    assignmentsCount++;
                }
                quotaAssignments.put(quotaRef, refs);
            }
        }

        // 2. pricing follows logistics asynchronously (events): wait until it has every assignment before pricing anything
        for (var e : quotaAssignments.entrySet()) awaitPricing(e.getKey(), e.getValue().size());

        // 3. parameters, price components, approvals
        for (Assignment a : assignments) {
            int[] counts = new int[2];
            perAssignment.put(a.ref(), counts);
            for (String[] p : parameterSet(a.businessLine())) {
                http.post("/api/assignments/" + a.ref() + "/parameters", Map.of("element", p[0], "value", p[1]));
                parameters++;
                counts[0]++;
            }
            if (random.nextInt(10) < 7) {   // ~70% have some fixation, never more than 80% of the quantity
                BigDecimal budget = a.qty().multiply(new BigDecimal("0.8"));
                int n = 1 + random.nextInt(2);
                for (int c = 0; c < n; c++) {
                    BigDecimal qty = budget.divide(BigDecimal.valueOf(n), 4, RoundingMode.DOWN);
                    http.post("/api/assignments/" + a.ref() + "/price-components", component(a, qty));
                    components++;
                    counts[1]++;
                }
            }
            if (random.nextInt(10) < 6) {   // ~60% approved
                http.post("/api/assignments/" + a.ref() + "/approval", Map.of("status", "APPROVED"));
                approvals++;
            }
        }

        // 4. valuations for about 40% of quotas: RM at quota level, everything else per assignment
        for (var e : quotaAssignments.entrySet()) {
            if (random.nextInt(10) >= 4) continue;
            Line line = quotaLine.get(e.getKey());
            String brd = http.get("/api/desks/" + line.desk() + "/brd").get("brd").asText();
            String lane = random.nextInt(4) == 0 ? "INTERACTIVE" : "BULK";
            if (line.businessLine().equals("RM")) {
                http.post("/api/valuations", Map.of("subjectRef", e.getKey(), "subjectLevel", "QUOTA", "brd", brd, "lane", lane));
                valuations++;
            } else {
                for (String ref : e.getValue()) {
                    http.post("/api/valuations", Map.of("subjectRef", ref, "subjectLevel", "ASSIGNMENT", "brd", brd, "lane", lane));
                    valuations++;
                }
            }
        }
        Map<String, Expected> expected = new LinkedHashMap<>();
        perAssignment.forEach((ref, c) -> expected.put(ref, new Expected(c[0], c[1])));
        return new Summary(TRADES, quotas, assignmentsCount, parameters, components, approvals, valuations, expected);
    }

    /** Monthly over three months, weekly over 2 to 4 weeks, or two explicit custom periods (quantities that add up to the total). */
    private Map<String, Object> delivery(BigDecimal total, int i) {
        LocalDate start = LocalDate.of(2026, 10, 1);
        Map<String, Object> d = new LinkedHashMap<>();
        switch (i % 3) {
            case 0 -> { d.put("periodicity", "MONTHLY"); d.put("from", start.toString()); d.put("to", start.plusMonths(3).minusDays(1).toString()); }
            case 1 -> { d.put("periodicity", "WEEKLY"); d.put("from", start.toString()); d.put("to", start.plusDays(7L * (2 + random.nextInt(3)) - 1).toString()); }
            default -> {
                BigDecimal first = total.multiply(new BigDecimal("0.4")).setScale(4, RoundingMode.DOWN);
                d.put("periodicity", "CUSTOM");
                d.put("from", start.toString());
                d.put("to", start.plusMonths(2).minusDays(1).toString());
                d.put("periods", List.of(
                        Map.of("from", start.toString(), "to", start.plusMonths(1).minusDays(1).toString(), "qty", first.toPlainString()),
                        Map.of("from", start.plusMonths(1).toString(), "to", start.plusMonths(2).minusDays(1).toString(), "qty", total.subtract(first).toPlainString())));
            }
        }
        return d;
    }

    private List<String[]> parameterSet(String businessLine) {
        return switch (businessLine) {
            case "CONCENTRATES" -> List.of(new String[] {"AG", price(5, 60)}, new String[] {"AS", price(1, 8)});          // silver earns a premium, arsenic a penalty
            case "BULK" -> List.of(new String[] {"MOISTURE", price(5, 15)}, new String[] {"PARTICLE_SIZE", price(2, 40)}); // coal moisture and particle size
            default -> List.of();
        };
    }

    private String price(int min, int max) {
        return BigDecimal.valueOf(min + random.nextInt(max - min + 1)).setScale(6).toPlainString();
    }

    /** A fixed, average or formula price component of the given quantity (some provisional). */
    private Map<String, Object> component(Assignment a, BigDecimal qty) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("qty", qty.toPlainString());
        c.put("provisional", random.nextInt(4) == 0);
        switch (random.nextInt(3)) {
            case 0 -> { c.put("kind", "FIXED"); c.put("fixedPrice", BigDecimal.valueOf(1000 + random.nextInt(8000)).setScale(6).toPlainString()); }
            case 1 -> { c.put("kind", "AVERAGE"); c.put("indexName", a.index()); c.put("periodFrom", "2026-10-01"); c.put("periodTo", "2026-10-31"); }
            default -> { c.put("kind", "FORMULA"); c.put("formula", a.index() + " - " + (10 + random.nextInt(40)) + " * 0.9"); }
        }
        return c;
    }

    /** Polls until pricing has written a revision for the quota holding the expected number of assignments (it consumes logistics events asynchronously). */
    private void awaitPricing(String quotaRef, int expected) {
        long deadline = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < deadline) {
            try {
                if (http.get("/api/quotas/" + quotaRef + "/pricing").get("assignments").size() >= expected) return;
            } catch (Http.ApiException notYet) {
                if (notYet.status != 404) throw notYet;   // 404 = pricing has not consumed the event yet
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        throw new IllegalStateException("pricing did not catch up for quota " + quotaRef + " within 60s (is the pricing consumer running?)");
    }
}
