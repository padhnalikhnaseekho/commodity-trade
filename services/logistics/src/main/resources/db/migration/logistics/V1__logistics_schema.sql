-- Logistics service schema (P0 scope: assignments and QAG revisions only).
-- No foreign key to trade.quota: quota_ref is a text reference across a service boundary.
-- WHY: each service owns its schema; a cross-schema FK would couple deployment and migration order.

CREATE TABLE logistics.assignment (
    id             BIGSERIAL PRIMARY KEY,
    assignment_ref TEXT NOT NULL UNIQUE,                   -- '1.1.1'
    quota_ref      TEXT NOT NULL,
    seq            INT NOT NULL,
    qty            NUMERIC(18,4) NOT NULL CHECK (qty > 0),
    status         TEXT NOT NULL,                          -- allowed values not yet specified: ask before P0.2
    UNIQUE (quota_ref, seq)
);

-- One row per material change to a quota's assignment graph (QAGR). Operational events
-- (load, discharge, ...) never create a row here: that is what keeps the revision count near 10 per trade.
CREATE TABLE logistics.qag_revision (
    id           BIGSERIAL PRIMARY KEY,
    qagr_id      UUID NOT NULL UNIQUE,
    quota_ref    TEXT NOT NULL,
    previous_id  UUID REFERENCES logistics.qag_revision(qagr_id),   -- the revision chain
    brd          DATE NOT NULL,
    change_kinds TEXT[] NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Serves "latest revision for a quota as of a BRD" (order by brd, created_at desc).
CREATE INDEX qag_revision_quota_brd_idx ON logistics.qag_revision (quota_ref, brd, created_at DESC);

-- Assignment refs as they stood at this revision, so a consumer can rebuild state without reading back.
CREATE TABLE logistics.qag_revision_member (
    qagr_id        UUID NOT NULL REFERENCES logistics.qag_revision(qagr_id),
    assignment_ref TEXT NOT NULL,
    qty            NUMERIC(18,4) NOT NULL,
    PRIMARY KEY (qagr_id, assignment_ref)
);
