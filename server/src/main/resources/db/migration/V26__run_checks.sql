-- Checks before a run is merged (docs/ARCHITECTURE.md §6 "Checks before a merge").
-- The checks on the pull request's head commit as last evaluated (RunChecksDto without the waiting times), with the
-- owner's override when they merged anyway.
ALTER TABLE runs ADD COLUMN checks jsonb;
-- The head commit Agern last saw for the run (pushed, or first evaluated) and when: the settle period counts from it.
ALTER TABLE runs ADD COLUMN checks_head_sha text;
ALTER TABLE runs ADD COLUMN checks_head_seen_at timestamptz;
-- While the run is `waiting_for_checks`: since when it waits and when it stops waiting (kept across a restart).
ALTER TABLE runs ADD COLUMN checks_waiting_since timestamptz;
ALTER TABLE runs ADD COLUMN checks_deadline timestamptz;
-- Who approved the merge that waits for checks: a username, or NULL for the approver agent.
ALTER TABLE runs ADD COLUMN merge_approved_by text;
