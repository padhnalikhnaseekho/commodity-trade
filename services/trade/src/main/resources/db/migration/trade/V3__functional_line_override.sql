-- Optional explicit FunctionalLine chosen at trade creation. NULL means "follow the cutover rule in reference data".
-- Stored as the line's name only: the meaning of a line (which engine) belongs to reference data, not to the trade.
ALTER TABLE trade.trade ADD COLUMN functional_line TEXT;
