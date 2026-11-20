-- Releases, step 3: the release pull request, owner approval and the merge into the default branch
-- (docs/ARCHITECTURE.md §6 "Releases", "Releasing"). New statuses: 'ready' and 'released'.
ALTER TABLE releases
    ADD COLUMN pr_number       INT,                            -- the release pull request release/<name> -> default branch
    ADD COLUMN pr_url          TEXT,
    ADD COLUMN pr_body_sha     TEXT,                           -- SHA-256 of the title and body last written to it
    ADD COLUMN target_branch   TEXT,                           -- the default branch the pull request merges into
    ADD COLUMN merge_state     TEXT NOT NULL DEFAULT 'unknown', -- unknown | clean | conflict
    ADD COLUMN merge_method    TEXT,                           -- rebase | merge | unavailable; once released: the one used
    ADD COLUMN notice          TEXT,                           -- the last problem, shown on the release page
    ADD COLUMN ready_at        TIMESTAMPTZ,
    ADD COLUMN ready_announced BOOLEAN NOT NULL DEFAULT false,  -- the "ready to release" comment is posted
    ADD COLUMN released_at     TIMESTAMPTZ,
    ADD COLUMN released_by     UUID REFERENCES users(id),       -- null: merged on GitHub outside Agern
    ADD COLUMN merge_sha       TEXT,                           -- the default branch's commit after the merge
    ADD COLUMN cleanup_pending BOOLEAN NOT NULL DEFAULT false;  -- released; closing issues / deleting the branch not done
CREATE INDEX releases_open_work ON releases(status) WHERE status = 'ready' OR cleanup_pending;
