-- Sandbox VMs (docs/ARCHITECTURE.md §5 "Sandbox VMs", docs/SANDBOX-VMS.md).
--
-- An admin approves a project's `.agern/sandbox-vm.yml` by the SHA-256 of its exact content, like deploy_approvals. The
-- VM authenticates the project's deploy key (deploy_keys); there is no key of its own.
CREATE TABLE sandbox_vm_approvals (
    repo        TEXT PRIMARY KEY,
    sha256      TEXT NOT NULL,
    content     TEXT NOT NULL,              -- the approved file: operations parse the definition from here
    approved_by UUID NOT NULL REFERENCES users(id),
    approved_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Who holds a VM now: at most one lease per VM (lower-case host and port), also across projects.
CREATE TABLE sandbox_vm_leases (
    vm_key       TEXT PRIMARY KEY,           -- lowercase(host):port
    repo         TEXT NOT NULL,              -- the repository whose approval the lease uses
    run_id       UUID NOT NULL REFERENCES runs(id) ON DELETE CASCADE,
    kind         TEXT NOT NULL,              -- build | preview
    state        TEXT NOT NULL,              -- held | resetting
    sha256       TEXT NOT NULL,              -- the approved definition the lease was taken with
    deployed_sha TEXT,                       -- the commit last checked out on the VM
    started_by   UUID REFERENCES users(id),  -- preview: who started it
    acquired_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at   TIMESTAMPTZ                 -- preview: when it ends by itself
);

CREATE INDEX sandbox_vm_leases_run ON sandbox_vm_leases(run_id);

-- The run's sandbox VM decision and preview (RunSandboxVmDto).
ALTER TABLE runs ADD COLUMN sandbox_vm JSONB;
