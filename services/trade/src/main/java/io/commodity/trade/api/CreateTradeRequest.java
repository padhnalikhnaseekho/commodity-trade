package io.commodity.trade.api;

import java.time.LocalDate;
import java.util.List;

/**
 * Body of POST /api/trades. Quantities are strings (API rule: no float loss); enums arrive as their names.
 * {@code delivery.periods} is only used for CUSTOM delivery.
 */
public record CreateTradeRequest(String businessLine, String deskId, String side, String counterparty, String commodity,
                                 String totalQty, String uom, Delivery delivery) {

    public record Delivery(String periodicity, LocalDate from, LocalDate to, List<Period> periods) {}

    public record Period(LocalDate from, LocalDate to, String qty) {}
}
