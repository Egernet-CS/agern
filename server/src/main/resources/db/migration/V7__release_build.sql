-- Releases, step 2: starting a release and building it on `release/<name>` (docs/ARCHITECTURE.md §6 "Releases").
ALTER TABLE releases
    ADD COLUMN owner_id     UUID REFERENCES users(id),   -- who started it
    ADD COLUMN branch       TEXT,                        -- release/<name> once started
    ADD COLUMN started_at   TIMESTAMPTZ,
    ADD COLUMN ended_at     TIMESTAMPTZ,
    ADD COLUMN pause_reason TEXT,                        -- manual | usage_limit | credentials
    ADD COLUMN pause_note   TEXT,
    ADD COLUMN resume_at    TIMESTAMPTZ;                 -- automatic resume after a usage limit
-- At most one release per project is building or paused.
CREATE UNIQUE INDEX releases_one_building ON releases(repo) WHERE status IN ('building', 'paused');

ALTER TABLE runs
    ADD COLUMN release_id  UUID REFERENCES releases(id) ON DELETE SET NULL,
    ADD COLUMN base_branch TEXT;                         -- null: the repository's default branch
CREATE INDEX runs_release ON runs(release_id);

ALTER TABLE release_issues
    ADD COLUMN state  TEXT NOT NULL DEFAULT 'waiting',   -- waiting | running | merged | held | skipped
    ADD COLUMN run_id UUID REFERENCES runs(id) ON DELETE SET NULL,
    ADD COLUMN note   TEXT;
