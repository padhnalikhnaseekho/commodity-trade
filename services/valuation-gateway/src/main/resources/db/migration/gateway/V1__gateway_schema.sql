-- Valuation gateway: durable request state (the successor of a request/status database with a browser UI).
-- WHY durable: replies are correlated through THIS table, never an in-memory map, so any instance can handle any reply and a
-- restart or crash mid-flight loses nothing.
CREATE TABLE gateway.valuation_request (
    request_id      UUID PRIMARY KEY,
    request_key     TEXT NOT NULL,                       -- deterministic hash of the immutable inputs (idempotency key)
    subject_ref     TEXT NOT NULL,
    subject_level   TEXT NOT NULL,                       -- QUOTA | ASSIGNMENT
    brd             DATE NOT NULL,
    functional_line TEXT NOT NULL,
    engine          TEXT NOT NULL,                       -- LEGACY | MODERN (decided by the functional line)
    lane            TEXT NOT NULL,                       -- INTERACTIVE | INVOICE | BULK | CLOSE
    source          TEXT NOT NULL,                       -- calling service
    status          TEXT NOT NULL CHECK (status IN ('PENDING', 'SENT', 'COMPLETED', 'FAILED')),
    attempts        INT NOT NULL DEFAULT 0,              -- number of sends so far
    payload         JSONB NOT NULL,                      -- the complete, self-contained request (a retry needs nothing else)
    result          JSONB,
    error           TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    sent_at         TIMESTAMPTZ,
    completed_at    TIMESTAMPTZ,
    version         INT NOT NULL DEFAULT 0
);

-- The result cache: "is there already a COMPLETED answer for this key?". Partial, so it stays small as history grows.
-- TARGET: a distributed cache keyed by request key; here the index is sufficient and the interface is the same.
CREATE INDEX valuation_request_cache_idx ON gateway.valuation_request (request_key) WHERE status = 'COMPLETED';
-- In-flight duplicate detection: two identical requests submitted before the first completes collapse into one.
CREATE INDEX valuation_request_inflight_idx ON gateway.valuation_request (request_key) WHERE status IN ('PENDING', 'SENT');
-- The watchdog: SENT rows older than the lane timeout.
CREATE INDEX valuation_request_status_sent_idx ON gateway.valuation_request (status, sent_at);
-- The dispatcher: oldest PENDING per lane. Per-lane so a bulk backlog can never hide interactive work.
CREATE INDEX valuation_request_pending_idx ON gateway.valuation_request (lane, created_at) WHERE status = 'PENDING';
-- The request browser (GET /api/valuations?brd=&status=&lane=).
CREATE INDEX valuation_request_browse_idx ON gateway.valuation_request (brd, status, lane);

-- Transactional outbox (same shape as the other services).
CREATE TABLE gateway.outbox (
    id             BIGSERIAL PRIMARY KEY,
    event_id       UUID NOT NULL UNIQUE,
    topic          TEXT NOT NULL,
    msg_key        TEXT NOT NULL,
    payload        TEXT NOT NULL,
    brd            DATE NOT NULL,
    source         TEXT NOT NULL,
    schema_version INT NOT NULL,
    traceparent    TEXT NOT NULL,
    occurred_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    sent_at        TIMESTAMPTZ
);
CREATE INDEX outbox_unsent_idx ON gateway.outbox (id) WHERE sent_at IS NULL;
