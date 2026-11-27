-- Runs keep their branch up to date with the base branch (docs/ARCHITECTURE.md §6 "Retrying a run", "Update and merge"):
-- Agern starts update rounds by itself when the target moved (trigger `base_moved`), records the pull requests that came
-- in with each round, and snapshots the other active runs of the project when a run starts.
ALTER TABLE run_updates DROP CONSTRAINT run_updates_trigger_check;
ALTER TABLE run_updates ADD CONSTRAINT run_updates_trigger_check CHECK (trigger IN ('owner', 'release', 'base_moved'));
ALTER TABLE run_updates ADD COLUMN pulls JSONB NOT NULL DEFAULT '[]';  -- [{number, title, url}] merged into the target meanwhile

ALTER TABLE runs ADD COLUMN other_runs JSONB NOT NULL DEFAULT '[]';  -- other active runs of the project when the run started
