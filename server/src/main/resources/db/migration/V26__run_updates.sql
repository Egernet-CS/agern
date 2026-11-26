-- "Update and merge" (docs/ARCHITECTURE.md §6 "Retrying a run"): a finished run whose pull request could not be merged
-- because its target moved gets update rounds. Agern merges the target into the run's branch, the builder resolves
-- the conflict, the reviewers and the approver run again and the pull request is merged.
ALTER TABLE runs
    ADD COLUMN update_rounds     INT NOT NULL DEFAULT 0,  -- update rounds started on the run
    ADD COLUMN max_update_rounds INT NOT NULL DEFAULT 2;  -- FACTORY_MAX_UPDATE_ROUNDS when the run was created

CREATE TABLE run_updates (
    id             UUID PRIMARY KEY,
    run_id         UUID NOT NULL REFERENCES runs(id) ON DELETE CASCADE,
    number         INT NOT NULL,                      -- 1, 2, … per run
    round          INT NOT NULL,                      -- the stage round of the update
    max_rounds     INT NOT NULL,                      -- the run's max_update_rounds
    trigger        TEXT NOT NULL CHECK (trigger IN ('owner', 'release')),
    target         TEXT NOT NULL,                     -- the branch merged into the run's branch
    reason         TEXT,                              -- the merge refusal that caused the update
    status         TEXT NOT NULL DEFAULT 'running' CHECK (status IN ('running', 'resolved', 'failed', 'cancelled')),
    merge_status   TEXT,                              -- up_to_date | merged | conflict
    target_sha     TEXT,
    head_before    TEXT,
    head_after     TEXT,
    incoming_count INT NOT NULL DEFAULT 0,
    incoming       JSONB NOT NULL DEFAULT '[]',       -- subjects of the first-parent commits merged in (at most 50)
    conflicts      JSONB NOT NULL DEFAULT '[]',       -- conflicted paths
    migrations     JSONB NOT NULL DEFAULT '[]',       -- migration problems
    commits        JSONB NOT NULL DEFAULT '[]',       -- the builder's commits, "<short sha> <subject>"
    error          TEXT,
    started_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    ended_at       TIMESTAMPTZ,
    UNIQUE (run_id, number)
);

ALTER TABLE release_issues DROP CONSTRAINT release_issues_retry_mode_valid;
ALTER TABLE release_issues ADD CONSTRAINT release_issues_retry_mode_valid
    CHECK (retry_mode IS NULL OR retry_mode IN ('continue', 'step', 'update'));

-- Runs held for a migration conflict before this change get the offer too.
UPDATE runs SET failure_kind = 'migration_conflict'
WHERE status = 'needs_human' AND failure_kind IS NULL AND error LIKE 'Database migration conflict with %';
