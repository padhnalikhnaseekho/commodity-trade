package io.commodity.contracts.lookup;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * What other services are allowed to know about a quota. The trade service owns quotas; logistics needs only the
 * quantity (for the cap) and the desk (to find the desk's business date). For valuation routing it also carries the trade's
 * created BRD and the optional explicit FunctionalLine override (null when the cutover rule applies). Anything else stays private to trade.
 */
public record QuotaView(
        String quotaRef,
        String tradeRef,
        String deskId,
        String businessLine,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal qty,
        LocalDate createdBrd,
        String functionalLine) {

    /** Convenience for callers that do not care about routing data ({@code createdBrd} and the override are null). */
    public QuotaView(String quotaRef, String tradeRef, String deskId, String businessLine, BigDecimal qty) {
        this(quotaRef, tradeRef, deskId, businessLine, qty, null, null);
    }
}
