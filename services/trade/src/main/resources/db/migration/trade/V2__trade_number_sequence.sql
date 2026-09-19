-- Source of the trade number in refs ("1", "2", ...). A sequence rather than max()+1: it is concurrency-safe
-- without locking. TRADEOFF: gaps appear when a transaction rolls back after taking a number; harmless for a ref.
CREATE SEQUENCE trade.trade_number_seq START 1;
