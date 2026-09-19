-- V2: restrict assignment.status to the known lifecycle values.
-- WHY a new migration and not an edit of V1: applied migrations are immutable history (checksummed by
-- Flyway); a schema change is always a new versioned step, exactly as it would be on a live system.
-- Values are CHECK-constrained here (cheap, local, stable) and mirrored by the AssignmentStatus enum.
-- Approval is deliberately NOT here: it is owned by pricing (it gates valuation and P&L eligibility).
UPDATE logistics.assignment SET status = 'ACTIVE' WHERE status = 'OPEN';   -- V1 placeholder value

ALTER TABLE logistics.assignment
    ADD CONSTRAINT assignment_status_chk CHECK (status IN ('ACTIVE', 'SUPERSEDED', 'CANCELLED', 'DELETED'));
