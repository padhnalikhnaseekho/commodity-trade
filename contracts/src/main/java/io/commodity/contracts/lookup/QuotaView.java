package io.commodity.contracts.lookup;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.math.BigDecimal;

/**
 * What other services are allowed to know about a quota. The trade service owns quotas; logistics needs only the
 * quantity (for the cap) and the desk (to find the desk's business date). Anything else stays private to trade.
 */
public record QuotaView(
        String quotaRef,
        String tradeRef,
        String deskId,
        String businessLine,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal qty) {}
