-- Retrying a failed run: retry the failed step, continue from its commits, or start over
-- (docs/ARCHITECTURE.md §6 "Retrying a run").
ALTER TABLE runs
    ADD COLUMN continues_run_id UUID REFERENCES runs(id) ON DELETE SET NULL, -- the run this one continues from
    ADD COLUMN spec             TEXT,   -- the spec the builder got (written or reused), for a step retry or a continuation
    ADD COLUMN builder_summary  TEXT,   -- the builder's last final message, for a step retry (the reviewers get it)
    ADD COLUMN failure_kind     TEXT,   -- why a failed run stopped: time_limit | usage_limit | restart | api_unreachable |
                                        -- credentials | blocked | step | other (null: not failed, or before V16)
    ADD COLUMN failed_step      TEXT;   -- the mechanical step after the builder that failed: push | pull_request | merge
CREATE INDEX runs_continues ON runs(continues_run_id) WHERE continues_run_id IS NOT NULL;

-- A held issue retried with "continue" or "step" waits with its old run linked until the build reaches it.
ALTER TABLE release_issues ADD COLUMN retry_mode TEXT;
ALTER TABLE release_issues ADD CONSTRAINT release_issues_retry_mode_valid CHECK (retry_mode IS NULL OR retry_mode IN ('continue', 'step'));
