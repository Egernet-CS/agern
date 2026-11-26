-- Merge policy per project (docs/ARCHITECTURE.md §6 "Merge policy"): set by an admin, snapshotted on every run.
CREATE TABLE project_settings (
    repo            TEXT PRIMARY KEY,
    merge_policy    TEXT NOT NULL DEFAULT 'automatic' CHECK (merge_policy IN ('automatic','owner_approves','github')),
    reviewers       TEXT NOT NULL DEFAULT '',   -- comma-separated GitHub logins
    team_reviewers  TEXT NOT NULL DEFAULT '',   -- comma-separated team slugs
    updated_by      UUID NOT NULL REFERENCES users(id),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE runs
    ADD COLUMN merge_policy     TEXT NOT NULL DEFAULT 'automatic',
    ADD COLUMN merged_on_github BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN merged_by        TEXT;
