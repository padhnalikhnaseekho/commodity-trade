package io.commodity.trade.api;

import java.time.LocalDate;
import java.util.List;

/** Response shapes of the trade API. Quantities are plain strings at scale 4. */
public final class TradeResponses {
    private TradeResponses() {}

    public record Created(String tradeRef, List<String> quotas) {}

    public record TradeView(String tradeRef, String businessLine, String deskId, String side, String counterparty,
                            String commodity, String totalQty, String uom, LocalDate createdBrd) {}

    public record QuotaItem(String quotaRef, int seq, String periodicity, LocalDate from, LocalDate to, String qty) {}
}
