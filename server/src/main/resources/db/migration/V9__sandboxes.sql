-- Sandboxes (docs/ARCHITECTURE.md §5 "Sandboxes"): an admin approves a project's `.agern/sandbox.yml` by the SHA-256
-- of its exact content. One approval per repository; approving another version replaces it. A run uses the definition
-- only when the file on the base branch hashes to the approved value.
CREATE TABLE sandbox_approvals (
    repo        TEXT PRIMARY KEY,
    sha256      TEXT NOT NULL,
    content     TEXT NOT NULL,              -- the approved file, for display and diffs
    approved_by UUID NOT NULL REFERENCES users(id),
    approved_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- What the run decided about its sandbox when it started (RunSandbox in the API); NULL for runs from before sandboxes.
ALTER TABLE runs ADD COLUMN sandbox JSONB;
