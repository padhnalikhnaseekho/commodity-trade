-- Trade service schema. One Postgres schema per service, no cross-schema foreign keys:
-- other services refer to trades/quotas by text ref only (service boundary rule).
-- TARGET: Oracle Exadata. Same SQL shape; HCC/Smart Scan are not exercised by the demo.

CREATE TABLE trade.trade (
    id            BIGSERIAL PRIMARY KEY,
    trade_ref     TEXT NOT NULL UNIQUE,                    -- '1'
    business_line TEXT NOT NULL,                           -- RM | CONCENTRATES | BULK | ENERGY
    desk_id       TEXT NOT NULL,
    side          TEXT NOT NULL,                           -- PURCHASE | SALE
    counterparty  TEXT NOT NULL,
    commodity     TEXT NOT NULL,
    total_qty     NUMERIC(18,4) NOT NULL CHECK (total_qty > 0),
    uom           TEXT NOT NULL,                           -- MT
    created_brd   DATE NOT NULL
);

CREATE TABLE trade.quota (
    id          BIGSERIAL PRIMARY KEY,
    quota_ref   TEXT NOT NULL UNIQUE,                      -- '1.1'
    trade_id    BIGINT NOT NULL REFERENCES trade.trade(id),
    seq         INT NOT NULL,
    periodicity TEXT NOT NULL,                             -- MONTHLY | WEEKLY | DAILY | CUSTOM
    period_from DATE NOT NULL,
    period_to   DATE NOT NULL,
    qty         NUMERIC(18,4) NOT NULL CHECK (qty > 0),
    -- UNIQUE also gives us the (trade_id, seq) index used to list a trade's quotas in order.
    UNIQUE (trade_id, seq)
);

-- INVARIANT sum(quota.qty) = trade.total_qty is deliberately NOT a trigger: it is enforced in
-- domain code and asserted by tests (spec). A trigger would hide a business rule in the database.
