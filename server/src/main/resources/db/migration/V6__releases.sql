-- Releases: a named plan of issues for one project (docs/ARCHITECTURE.md §6 "Releases").
-- Step 1 only plans: status is always 'planning'; building and releasing come in later steps.
CREATE TABLE releases (
    id          UUID PRIMARY KEY,
    repo        TEXT NOT NULL,
    name        TEXT NOT NULL,
    description TEXT NOT NULL DEFAULT '',
    status      TEXT NOT NULL DEFAULT 'planning',
    created_by  UUID NOT NULL REFERENCES users(id),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX releases_repo_name ON releases(repo, lower(name));

CREATE TABLE release_issues (
    release_id   UUID NOT NULL REFERENCES releases(id) ON DELETE CASCADE,
    repo         TEXT NOT NULL,
    issue_number INT  NOT NULL,
    position     INT  NOT NULL,
    issue_title  TEXT NOT NULL,
    issue_kind   TEXT NOT NULL,
    added_by     UUID NOT NULL REFERENCES users(id),
    added_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (repo, issue_number)          -- an issue is in at most one release
);
CREATE INDEX release_issues_release ON release_issues(release_id, position);
