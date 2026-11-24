-- A warning the server records on a stage without failing it (docs/ARCHITECTURE.md §6 "Screenshots"): today only
-- "UI change reviewed without screenshots", set on the ux and ui-design stages of a round. Null: no warning.
ALTER TABLE stages ADD COLUMN warning TEXT;
