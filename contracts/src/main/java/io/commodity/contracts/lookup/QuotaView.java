package io.commodity.contracts.lookup;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * What other services are allowed to know about a quota. The trade service owns quotas; logistics needs only the
 * quantity (for the cap) and the desk (to find the desk's business date). For valuation routing it also carries the trade's
 * created BRD and the optional explicit FunctionalLine override (null when the cutover rule applies); for display it carries the commodity. Anything else stays private to trade.
 */
public record QuotaView(
        String quotaRef,
        String tradeRef,
        String deskId,
        String businessLine,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal qty,
        LocalDate createdBrd,
        String functionalLine,
        String commodity) {

    /** Convenience for callers that do not care about routing data or the commodity (those fields are null). */
    public QuotaView(String quotaRef, String tradeRef, String deskId, String businessLine, BigDecimal qty) {
        this(quotaRef, tradeRef, deskId, businessLine, qty, null, null, null);
    }

    /** Convenience for callers that know the routing data but not the commodity. */
    public QuotaView(String quotaRef, String tradeRef, String deskId, String businessLine, BigDecimal qty, LocalDate createdBrd, String functionalLine) {
        this(quotaRef, tradeRef, deskId, businessLine, qty, createdBrd, functionalLine, null);
    }
}
