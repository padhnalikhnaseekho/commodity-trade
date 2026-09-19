-- V4: resolve "the QAG revision in force" by the INSERTION SEQUENCE (id), not by created_at. Wall-clock time is not monotonic (clock steps, skew between
-- servers), so it must never decide the order of revisions; the id sequence is, and all writers of a quota hold its advisory lock. created_at stays for audit.
DROP INDEX logistics.qag_revision_quota_brd_idx;
CREATE INDEX qag_revision_quota_brd_id_idx ON logistics.qag_revision (quota_ref, brd DESC, id DESC);
