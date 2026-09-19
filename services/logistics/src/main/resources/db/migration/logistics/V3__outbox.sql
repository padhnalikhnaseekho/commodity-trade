-- Transactional outbox for this service. Events are inserted here in the SAME transaction as the business change,
-- then shipped to Kafka by the polling publisher (platform/outbox). Kept identical in shape to the platform test table.
CREATE TABLE logistics.outbox (
    id             BIGSERIAL PRIMARY KEY,
    event_id       UUID NOT NULL UNIQUE,               -- consumer-side dedup key (delivery is at-least-once)
    topic          TEXT NOT NULL,
    msg_key        TEXT NOT NULL,                      -- Kafka partition key (quotaRef / assignmentRef, never dealId)
    payload        TEXT NOT NULL,                      -- JSON body
    brd            DATE NOT NULL,                      -- envelope: business date the change belongs to
    source         TEXT NOT NULL,
    schema_version INT NOT NULL,
    traceparent    TEXT NOT NULL,
    occurred_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    sent_at        TIMESTAMPTZ                         -- NULL = not yet published
);
-- The publisher only ever asks for unsent rows in id order; a partial index keeps that cheap as history grows.
CREATE INDEX outbox_unsent_idx ON logistics.outbox (id) WHERE sent_at IS NULL;
