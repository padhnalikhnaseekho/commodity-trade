-- Pricing schema: where structural sharing lives (P0.3).
-- Every table here is INSERT-ONLY. The trigger at the bottom makes that a database guarantee,
-- because one stray UPDATE on a revision table would silently break replay determinism.

-- Root revision: one row per pricing change on a quota.
CREATE TABLE pricing.quota_revision (
    id          BIGSERIAL PRIMARY KEY,
    pqr_id      UUID NOT NULL UNIQUE,
    quota_ref   TEXT NOT NULL,
    qagr_id     UUID NOT NULL,                              -- logistics revision it was built from (by id, no FK across services)
    previous_id UUID REFERENCES pricing.quota_revision(pqr_id),
    brd         DATE NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
-- Serves BRD as-of resolution: latest revision with brd <= :asOf, tie-broken by created_at.
CREATE INDEX quota_revision_quota_brd_idx ON pricing.quota_revision (quota_ref, brd, created_at DESC);

-- Immutable assignment revisions, shared across quota revisions.
CREATE TABLE pricing.assignment_revision (
    par_id         UUID PRIMARY KEY,
    assignment_ref TEXT NOT NULL,
    qty            NUMERIC(18,4) NOT NULL,
    content_hash   TEXT NOT NULL
);
-- Sharing lookup: "does a revision with this hash already exist for this assignment?"
CREATE INDEX assignment_revision_ref_hash_idx ON pricing.assignment_revision (assignment_ref, content_hash);

-- THE SHARING TABLE: which assignment revisions a quota revision points at.
-- An unchanged assignment appears in many quota revisions but is stored once (same idea as a Git tree).
CREATE TABLE pricing.quota_revision_member (
    pqr_id UUID NOT NULL REFERENCES pricing.quota_revision(pqr_id),
    par_id UUID NOT NULL REFERENCES pricing.assignment_revision(par_id),
    PRIMARY KEY (pqr_id, par_id)
);

CREATE TABLE pricing.parameter_revision (
    ppr_id       UUID PRIMARY KEY,
    par_id       UUID NOT NULL REFERENCES pricing.assignment_revision(par_id),
    element      TEXT NOT NULL,                             -- AG | AS | MOISTURE | PARTICLE_SIZE
    value        NUMERIC(18,6) NOT NULL,
    content_hash TEXT NOT NULL
);
-- Postgres does not index foreign keys automatically. Resolution joins from par_id, so index it.
CREATE INDEX parameter_revision_par_idx ON pricing.parameter_revision (par_id);

CREATE TABLE pricing.price_component (
    pc_id        UUID PRIMARY KEY,
    par_id       UUID NOT NULL REFERENCES pricing.assignment_revision(par_id),
    kind         TEXT NOT NULL,                             -- FIXED | AVERAGE | FORMULA
    qty          NUMERIC(18,4) NOT NULL CHECK (qty > 0),
    fixed_price  NUMERIC(18,6),
    index_name   TEXT,
    period_from  DATE,
    period_to    DATE,
    formula      TEXT,
    provisional  BOOLEAN NOT NULL DEFAULT false,
    content_hash TEXT NOT NULL
);
CREATE INDEX price_component_par_idx ON pricing.price_component (par_id);

-- Unpriced quantity is DERIVED (assignment qty - sum(component qty)); it has no column by design.

-- Immutability enforcement (project conventions rule 2). Belt and braces with Hibernate @Immutable:
-- the ORM protects against our own code, the trigger protects against everything else (psql, other tools).
-- TRADEOFF: future archival/purge must bypass this deliberately (e.g. drop whole partitions) - which is
-- the intent: deleting history should never be an accident.
CREATE FUNCTION pricing.reject_change() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION '% on pricing.% is not allowed: revision tables are insert-only', TG_OP, TG_TABLE_NAME;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER quota_revision_immutable BEFORE UPDATE OR DELETE ON pricing.quota_revision
    FOR EACH ROW EXECUTE FUNCTION pricing.reject_change();
CREATE TRIGGER assignment_revision_immutable BEFORE UPDATE OR DELETE ON pricing.assignment_revision
    FOR EACH ROW EXECUTE FUNCTION pricing.reject_change();
CREATE TRIGGER quota_revision_member_immutable BEFORE UPDATE OR DELETE ON pricing.quota_revision_member
    FOR EACH ROW EXECUTE FUNCTION pricing.reject_change();
CREATE TRIGGER parameter_revision_immutable BEFORE UPDATE OR DELETE ON pricing.parameter_revision
    FOR EACH ROW EXECUTE FUNCTION pricing.reject_change();
CREATE TRIGGER price_component_immutable BEFORE UPDATE OR DELETE ON pricing.price_component
    FOR EACH ROW EXECUTE FUNCTION pricing.reject_change();
