-- V4: resolve "the revision in force" by the INSERTION SEQUENCE (id), never by created_at.
--
-- WHY: created_at is wall-clock time. Wall clocks are not monotonic: NTP or the hypervisor can step them backwards, and separate application servers disagree by
-- design. A revision written AFTER another can therefore carry an EARLIER created_at, and a resolution that tie-breaks on created_at then returns the STALE
-- revision (observed for real in the end-to-end test: the API resolved to id 406 while the true head was id 413, which looked exactly like a lost update).
-- The sequence id is monotonic, and every writer of a quota holds that quota's advisory lock, so id order IS the logical order of the quota's revisions.
-- created_at stays as an audit column ("when did this happen"), but nothing may decide ordering with it.
DROP INDEX pricing.quota_revision_quota_brd_idx;
CREATE INDEX quota_revision_quota_brd_id_idx ON pricing.quota_revision (quota_ref, brd DESC, id DESC);

DROP INDEX pricing.assignment_approval_ref_brd_idx;
CREATE INDEX assignment_approval_ref_brd_id_idx ON pricing.assignment_approval (assignment_ref, brd DESC, id DESC);
