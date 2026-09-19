package io.commodity.trade.service;

import io.commodity.contracts.lookup.BusinessDayClock;
import io.commodity.contracts.lookup.FunctionalLineDirectory;
import io.commodity.contracts.lookup.QuotaView;
import io.commodity.contracts.refs.QuotaRef;
import io.commodity.contracts.refs.TradeRef;
import io.commodity.platform.error.DomainException;
import io.commodity.platform.error.Parse;
import io.commodity.trade.api.CreateTradeRequest;
import io.commodity.trade.domain.*;
import io.commodity.trade.repository.QuotaRepository;
import io.commodity.trade.repository.TradeRepository;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Application service for the trade write path: creates a trade and derives its quotas in ONE transaction.
 *
 * <p>Why the transaction boundary lives here (service layer, not controller, not entity): trade and quotas are one
 * aggregate; either both exist or neither does. The rules themselves (split, period derivation) are in domain/ as plain
 * Java; this class only orchestrates: parse, plan, number, persist.
 *
 * <p>Flow: POST /api/trades -> here -> trade.trade + trade.quota rows. Logistics later creates assignments inside each
 * quota. The trade's created BRD comes from the desk's business date, not the wall clock.
 */
@Service
public class TradeService {

    private final TradeRepository trades;
    private final QuotaRepository quotas;
    private final BusinessDayClock clock;
    private final FunctionalLineDirectory lines;
    private final JdbcTemplate jdbc;

    public TradeService(TradeRepository trades, QuotaRepository quotas, BusinessDayClock clock, FunctionalLineDirectory lines, JdbcTemplate jdbc) {
        this.trades = trades;
        this.quotas = quotas;
        this.clock = clock;
        this.lines = lines;
        this.jdbc = jdbc;
    }

    /** Result of a create: the trade ref and its quota refs in sequence order. */
    public record Created(String tradeRef, List<String> quotaRefs) {}

    @Transactional
    public Created create(CreateTradeRequest req) {
        BusinessLine businessLine = Parse.enumValue(BusinessLine.class, req.businessLine(), "businessLine");
        Side side = Parse.enumValue(Side.class, req.side(), "side");
        BigDecimal total = parseQty(req.totalQty(), "totalQty");
        if (req.delivery() == null) throw new DomainException(400, "invalid-delivery-terms", "Delivery terms are required");
        Periodicity periodicity = Parse.enumValue(Periodicity.class, req.delivery().periodicity(), "delivery.periodicity");

        // An explicit routing override must be a real line of this business line: fail now, not at valuation time.
        if (req.functionalLine() != null && !lines.isValid(businessLine.name(), req.functionalLine())) {
            throw new DomainException(400, "invalid-functional-line", "Not a functional line of this business line")
                    .with("businessLine", businessLine.name()).with("functionalLine", req.functionalLine());
        }

        // Plan first: if the delivery terms are invalid nothing has been numbered or written.
        List<QuotaPlanner.CustomPeriod> custom = req.delivery().periods() == null ? null : req.delivery().periods().stream()
                .map(p -> new QuotaPlanner.CustomPeriod(p.from(), p.to(), parseQty(p.qty(), "periods.qty"))).toList();
        List<QuotaPlanner.PlannedQuota> planned =
                QuotaPlanner.plan(periodicity, req.delivery().from(), req.delivery().to(), total, custom);

        TradeRef ref = new TradeRef(jdbc.queryForObject("SELECT nextval('trade.trade_number_seq')", Long.class));
        Trade trade = trades.save(new Trade(ref.toString(), businessLine, req.deskId(), side, req.counterparty(),
                req.commodity(), total, req.uom(), clock.currentBrd(req.deskId()), req.functionalLine()));

        List<String> quotaRefs = planned.stream().map(q -> {
            QuotaRef qref = ref.quota(q.seq());
            quotas.save(new Quota(qref.toString(), trade.getId(), q.seq(), periodicity, q.from(), q.to(), q.qty()));
            return qref.toString();
        }).toList();
        return new Created(ref.toString(), quotaRefs);
    }

    @Transactional(readOnly = true)
    public Trade getTrade(String tradeRef) {
        return trades.findByTradeRef(tradeRef).orElseThrow(() -> notFound("trade", tradeRef));
    }

    @Transactional(readOnly = true)
    public List<Quota> getQuotas(String tradeRef) {
        Trade t = getTrade(tradeRef);
        return quotas.findByTradeIdOrderBySeq(t.getId());
    }

    /** The slice of a quota other services may see (see the QuotaDirectory port in :contracts). */
    @Transactional(readOnly = true)
    public QuotaView getQuotaView(String quotaRef) {
        QuotaRef parsed;
        try {
            parsed = QuotaRef.parse(quotaRef);
        } catch (IllegalArgumentException e) {
            throw new DomainException(400, "invalid-ref", "Not a quota ref").with("ref", quotaRef);
        }
        Trade t = getTrade(parsed.trade().toString());
        Quota q = quotas.findByTradeIdOrderBySeq(t.getId()).stream().filter(x -> x.getSeq() == parsed.seq()).findFirst()
                .orElseThrow(() -> notFound("quota", quotaRef));
        return new QuotaView(q.getQuotaRef(), t.getTradeRef(), t.getDeskId(), t.getBusinessLine().name(), q.getQty(),
                t.getCreatedBrd(), t.getFunctionalLine(), t.getCommodity());
    }

    /** Parses a quantity string and enforces the four-decimal scale rule instead of silently rounding. */
    static BigDecimal parseQty(String text, String field) {
        try {
            BigDecimal v = new BigDecimal(text);
            if (v.stripTrailingZeros().scale() > QuantitySplitter.QTY_SCALE) {
                throw new DomainException(400, "invalid-quantity", "Quantity has more than 4 decimal places")
                        .with("field", field).with("value", text);
            }
            return v.setScale(QuantitySplitter.QTY_SCALE);
        } catch (NumberFormatException | NullPointerException e) {
            throw new DomainException(400, "invalid-quantity", "Quantity is not a number").with("field", field).with("value", String.valueOf(text));
        }
    }

    private static DomainException notFound(String what, String ref) {
        return new DomainException(404, "not-found", "No such " + what).with("ref", ref);
    }
}
