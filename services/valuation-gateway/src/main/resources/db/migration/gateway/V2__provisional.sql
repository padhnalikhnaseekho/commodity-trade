-- V2: remember whether a valuation was PROVISIONAL when it was requested.
-- Provisional = at least one contributing assignment was not yet approved as of the BRD. Approval must be complete before the desk closes, but
-- valuing an unapproved assignment is a normal use (a provisional number). Approval is deliberately NOT part of the request key: the valuation
-- maths are identical whether or not the assignment is approved, so approving later must return the SAME cached answer, now reported as final.
-- This column records the state AT REQUEST TIME (it is not updated when an assignment is approved later); a fresh submission reports the
-- CURRENT state, computed from pricing, which is why the API names the stored one "provisionalAtRequest".
ALTER TABLE gateway.valuation_request ADD COLUMN provisional BOOLEAN NOT NULL DEFAULT false;
