-- The blotter read model: a flat, denormalised, DISPOSABLE projection of what trade, pricing and the valuation gateway have published.
-- It is rebuilt from the event streams; nothing treats it as a source of truth. (The legacy blotter row was ~100 columns rebuilt by delete-and-reinsert on
-- every event; here it is an upsert, and history by BRD is kept so the as-of picker can show a past date.)

-- One row per (assignment, BRD): the assignment as pricing published it for that business date. The row in force as of a BRD is the latest with brd <= that date,
-- the same resolution rule as everywhere else in the platform.
CREATE TABLE blotter.blotter_row (
    assignment_ref  TEXT NOT NULL,
    brd             DATE NOT NULL,
    trade_ref       TEXT NOT NULL,
    quota_ref       TEXT NOT NULL,
    desk_id         TEXT NOT NULL,
    business_line   TEXT NOT NULL,
    commodity       TEXT,
    qty             NUMERIC(18,4) NOT NULL,
    priced_qty      NUMERIC(18,4) NOT NULL,
    unpriced_qty    NUMERIC(18,4) NOT NULL,
    over_fixed      BOOLEAN NOT NULL,
    approval_status TEXT NOT NULL,
    -- ACTIVE while pricing still lists the assignment in its quota; REMOVED once a later snapshot drops it (cancelled, superseded or deleted: the revision
    -- events carry membership only, so the reason is not known here).
    status          TEXT NOT NULL CHECK (status IN ('ACTIVE', 'REMOVED')),
    pqr_id          UUID NOT NULL,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (assignment_ref, brd)
);
CREATE INDEX blotter_row_desk_brd_idx ON blotter.blotter_row (desk_id, brd);
CREATE INDEX blotter_row_quota_brd_idx ON blotter.blotter_row (quota_ref, brd);

-- Valuation results by subject (an assignment, or a whole quota for RM) and BRD. Kept apart from the rows so a result that arrives before or after the
-- snapshot it belongs to is never lost; the API joins them.
CREATE TABLE blotter.valuation (
    subject_ref  TEXT NOT NULL,
    level        TEXT NOT NULL CHECK (level IN ('QUOTA', 'ASSIGNMENT')),
    brd          DATE NOT NULL,
    value        NUMERIC(24,4) NOT NULL,
    engine       TEXT NOT NULL,
    request_id   UUID NOT NULL,
    completed_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (subject_ref, level, brd)
);
