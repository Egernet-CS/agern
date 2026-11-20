-- Releases: manual or automatic release, and the checks on the release pull request
-- (docs/ARCHITECTURE.md §6 "Releasing", "Release mode" and "Checks").
ALTER TABLE releases
    ADD COLUMN release_mode            TEXT NOT NULL DEFAULT 'manual',   -- manual | automatic
    ADD COLUMN released_automatically  BOOLEAN NOT NULL DEFAULT false,   -- merged by Agern for the owner (automatic mode)
    ADD COLUMN checks_state            TEXT NOT NULL DEFAULT 'unknown',  -- unknown | awaiting | none | pending | passed | failed
    ADD COLUMN checks_sha              TEXT,                             -- the pull request head commit they were read for
    ADD COLUMN checks_head_seen_at     TIMESTAMPTZ,                      -- when Agern first saw checks_sha as the head
    ADD COLUMN checks_detail           TEXT,                             -- e.g. "Failed: build, lint"
    ADD COLUMN checks_failure_sha      TEXT,                             -- head commit whose failed checks were commented on
    ADD COLUMN auto_merge_failed_sha   TEXT;                             -- head commit an automatic merge was refused for
ALTER TABLE releases ADD CONSTRAINT releases_mode_valid CHECK (release_mode IN ('manual', 'automatic'));
