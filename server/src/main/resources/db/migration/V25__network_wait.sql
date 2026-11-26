-- Transient failures (docs/ARCHITECTURE.md §6 "Transient failures"): a run that lost the network keeps its status and
-- waits before its next attempt; a release that could not start its next issue pauses with reason 'network'.
ALTER TABLE runs
    ADD COLUMN wait_reason   TEXT,          -- 'network' while the run sleeps before its next attempt, else NULL
    ADD COLUMN wait_since    TIMESTAMPTZ,
    ADD COLUMN wait_next_at  TIMESTAMPTZ,
    ADD COLUMN wait_attempts INT NOT NULL DEFAULT 0,
    ADD COLUMN wait_detail   TEXT;
ALTER TABLE releases
    ADD COLUMN network_since    TIMESTAMPTZ,            -- first failed start of the current streak
    ADD COLUMN network_attempts INT NOT NULL DEFAULT 0;
