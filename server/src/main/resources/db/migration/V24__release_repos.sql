-- Releases in workspace projects (docs/ARCHITECTURE.md §6 "Releases", "Several repositories"): one row per member
-- repository in which Agern created `release/<name>` for the release, with that repository's release pull request.
-- The project repository's pull request stays on `releases`.
CREATE TABLE release_repos (
  release_id uuid NOT NULL REFERENCES releases(id) ON DELETE CASCADE,
  repo text NOT NULL,
  directory text NOT NULL,
  submodule boolean NOT NULL DEFAULT false,
  base_branch text NOT NULL,
  branch_created boolean NOT NULL DEFAULT false,
  branch_deleted boolean NOT NULL DEFAULT false,
  pr_number integer,
  pr_url text,
  pr_body_sha text,
  merge_state text NOT NULL DEFAULT 'unknown',
  merge_method text,
  checks_state text NOT NULL DEFAULT 'unknown',
  checks_sha text,
  checks_detail text,
  checks_failure_sha text,
  checks_head_seen_at timestamptz,
  merged_at timestamptz,
  merge_sha text,
  created_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (release_id, repo)
);

-- The project repository's pull request was merged on GitHub while member pull requests remain.
ALTER TABLE releases ADD COLUMN pr_merged_at timestamptz;
-- The project repository's `release/<name>` was deleted (cancel or delete of a multi-repository release).
ALTER TABLE releases ADD COLUMN branch_deleted boolean NOT NULL DEFAULT false;
