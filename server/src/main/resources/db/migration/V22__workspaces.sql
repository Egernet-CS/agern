-- Workspace projects (docs/ARCHITECTURE.md §5 "Workspace projects", §6 "Workspace runs").
-- A workspace run changes one member repository: its name and directory, fixed when the run is created (null for a
-- run of a single-repository project). context_repos: the member repositories checked out for a run or an agent
-- session, comma-separated; whoever cannot read one of them does not see the run or session.
ALTER TABLE runs ADD COLUMN target_repo text;
ALTER TABLE runs ADD COLUMN target_dir text;
ALTER TABLE runs ADD COLUMN context_repos text NOT NULL DEFAULT '';
ALTER TABLE agent_sessions ADD COLUMN context_repos text NOT NULL DEFAULT '';
