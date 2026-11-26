-- Bringing a release up to date (docs/ARCHITECTURE.md §6 "Bringing a release up to date"): a run of kind 'sync' merges
-- the default branch into `release/<name>`; every other run is of kind 'issue'. A release remembers how far its branch
-- is behind the default branch and whether its migrations collide with the default branch's, as last compared.
ALTER TABLE runs
    ADD COLUMN kind            TEXT NOT NULL DEFAULT 'issue' CHECK (kind IN ('issue', 'sync')),
    ADD COLUMN sync_pushed_sha TEXT;          -- the commit a sync run last pushed to `release/<name>`
CREATE INDEX runs_release_sync_idx ON runs (release_id, created_at DESC) WHERE kind = 'sync';
ALTER TABLE releases
    ADD COLUMN behind_by          INT,                            -- commits on the target not on `release/<name>`; NULL: not known
    ADD COLUMN migration_conflict BOOLEAN NOT NULL DEFAULT false;
