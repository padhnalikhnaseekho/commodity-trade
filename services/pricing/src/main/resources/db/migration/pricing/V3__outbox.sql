-- Transactional outbox for pricing (same shape as the other services): the snapshot events are inserted here in the SAME transaction as the
-- pricing change and shipped to Kafka afterwards, so an event exists if and only if the change committed.
CREATE TABLE pricing.outbox (
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
CREATE INDEX outbox_unsent_idx ON pricing.outbox (id) WHERE sent_at IS NULL;
