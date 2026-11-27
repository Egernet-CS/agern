-- Base branch per project (docs/ARCHITECTURE.md §6 "Base branch"): the branch new single runs start from, open their
-- pull request against and merge into. Snapshotted into runs.base_branch when a run is created.
ALTER TABLE project_settings ADD COLUMN base_branch TEXT;  -- null: the repository's default branch
