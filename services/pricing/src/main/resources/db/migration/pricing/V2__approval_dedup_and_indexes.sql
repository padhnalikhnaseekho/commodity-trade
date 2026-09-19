-- V2: approval as its own insert-only record, consumer-side dedup, and one more index.

-- Approval of an assignment (owner decision: approval is done in pricing and gates eligibility for valuation and P&L).
-- WHY a separate insert-only table and not a column on assignment_revision: revisions are immutable, so a field there
-- would mean every approval cuts a new pricing revision (and its rows). A separate record keeps approvals out of the
-- write-amplification path. The latest record as of a BRD wins; no record means UNAPPROVED.
CREATE TABLE pricing.assignment_approval (
    id             BIGSERIAL PRIMARY KEY,
    assignment_ref TEXT NOT NULL,
    status         TEXT NOT NULL CHECK (status IN ('APPROVED', 'UNAPPROVED')),
    brd            DATE NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX assignment_approval_ref_brd_idx ON pricing.assignment_approval (assignment_ref, brd, created_at DESC);
CREATE TRIGGER assignment_approval_immutable BEFORE UPDATE OR DELETE ON pricing.assignment_approval
    FOR EACH ROW EXECUTE FUNCTION pricing.reject_change();

-- Consumer-side dedup. Delivery is at-least-once, so the same event can arrive twice; the marker is written in the SAME
-- transaction as the revision, so "processed" and "applied" can never disagree. Old rows are swept by age (TTL).
CREATE TABLE pricing.processed_event (
    event_id     UUID PRIMARY KEY,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX processed_event_at_idx ON pricing.processed_event (processed_at);

-- Lets us ask "which pricing revisions were built from this QAG revision" without a scan.
CREATE INDEX quota_revision_qagr_idx ON pricing.quota_revision (qagr_id);
