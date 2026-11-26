# Sandbox VMs: a guide for admins and project authors

A *sandbox VM* is one machine outside Agern — a virtual machine with systemd, nginx, a native
PostgreSQL — on which a run's builder can install what it just built and check that it works. You
describe the VM in your repository, in **`.agern/sandbox-vm.yml`** on the default branch; an admin
approves that exact file and puts the project's public key on the VM. From then on the builder of every
run of the project can deploy its committed `HEAD` to the VM and run commands there, and the owner of a
run that waits for a human can click *Try it on the sandbox VM* on the run page.

The agent never gets the key. Agern holds it — the same key as the project's
[deploy targets](DEPLOY.md) —, opens the SSH connection itself and records every step, its exit code
and its output in the builder's transcript. Only one run uses the VM at a time. The server's exact
behaviour is in [ARCHITECTURE.md §5 "Sandbox VMs"](ARCHITECTURE.md#sandbox-vms).

## Sandbox VM or container sandbox?

The [container sandbox](SANDBOXES.md) (`.agern/sandbox.yml`) is a fresh rootless Podman container per
run, on the Agern host, with the worktree mounted. It is the place for builds and unit tests: cheap,
parallel, thrown away after the run.

A sandbox VM is for what a container cannot show: an application installed the way it runs in
production, as systemd units behind nginx, against a native database, after a real restart. It is one
long-lived machine shared by all runs (of all projects that name it), so runs take turns and the VM
keeps state between them.

Both can be used in the same run, and usually are: the builder builds and runs the tests in the
container sandbox (or on the host), commits, then deploys the commit to the VM and checks it there. The
two files are approved independently of each other and of `.agern/deploy.yml`.

## How it works

1. **The project's key.** The VM authenticates the project's deploy key, the one Agern generates for
   deploy targets. An admin generates it once, on the Deploy page or on the Sandbox VM page, and puts the
   public key line in `~/.ssh/authorized_keys` of the VM user.
2. **A file in the repository, approved by an admin.** `.agern/sandbox-vm.yml` names the VM (host,
   port, user, *pinned host key*) and the commands that install a commit on it. An admin approves the
   exact content (by its SHA-256). Any change needs a new approval.
3. **A decision per run.** When an issue run starts, Agern reads the file from the commit the run starts
   from (the same commit and repository as the container sandbox's file) and records on the run whether
   it has a VM: switched off, no file, invalid, not approved, or approved. Runs that bring a release up
   to date get no VM.
4. **Tools for the builder, a button for the owner.** Only the builder of a run with an approved file
   gets the four `factory_sandbox_vm_*` tools. The spec-writer is told that the VM exists so that it can
   plan its use; no other agent — chats, the advisor, reviewers, the approver, personal MCP tokens — can
   use the VM, and the server refuses the tools for them even if asked.
5. **A lease.** Deploying takes the VM for the run. While a run holds it, every other run is turned
   away at once. When the lease ends, the file's `reset` command runs.

## Who can do what

- **Everyone who can see the project** sees the Sandbox VM page (`/p/<repo>/sandbox-vm`): the file, its
  state, the commands, the public key with its fingerprint and who holds the VM now; and on the run page
  the run's VM line and its preview.
- **The builder of any run of the project** can run *any* command on the approved VM as the listed
  user, until the file changes or the approval is revoked. The admin's approval is the gate.
- **The owner of a run, and admins,** can start and release a human preview of that run.
- **Admins** generate the key, approve and revoke the file, check the VM and force-release its lease.
  Rotating the key is done on the Deploy page.
- **Command output is visible to everyone who can see the project.** It is stored in the run's
  transcript. Do not run commands that print secrets, and keep secrets off the VM (below).

## The file

```yaml
# .agern/sandbox-vm.yml, on the default branch
name: sandbox                    # optional label: a-z, 0-9 and '-', starts with a letter, ≤ 31 characters; default "sandbox-vm"
host: sandbox.example.net        # DNS name or IPv4/IPv6 address (no brackets)
port: 22                         # optional, 1–65535, default 22
user: agern                      # the account Agern logs in as
hostKey: ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAI…   # the VM's host key: "<type> <base64>"
description: Staging VM          # optional, one line, at most 200 characters
url: https://sandbox.example.net/   # optional: where people open the deployed app
checkout: |                      # installs the uploaded commit; required
  rm -rf /srv/app/src && mkdir -p /srv/app/src
  tar xzf "$AGERN_ARCHIVE" -C /srv/app/src
setup: sudo systemctl restart app   # optional: runs after checkout
ready:                           # optional: polled until the app answers
  command: curl -fsS http://127.0.0.1:8080/health
  timeoutSeconds: 60             # 1–600, default 60
reset: /srv/app/bin/restore-test-db   # optional: runs when a run's lease ends
```

Validation is strict, as for `.agern/deploy.yml`, and every problem is listed with its path in the file
(`ready.timeoutSeconds: …`):

- at most 64 KiB, valid YAML; anchors, aliases and duplicate keys are refused;
- unknown keys at any level are errors;
- `host`, `user`, `hostKey` and `checkout` are required;
- `host`, `user` and `hostKey` follow the rules of `.agern/deploy.yml` ([DEPLOY.md, "The
  file"](DEPLOY.md#the-file)): `user` matches `[A-Za-z_][A-Za-z0-9_.-]{0,31}`; `hostKey` is exactly the
  type and the base64 key, of type `ssh-ed25519`, `ecdsa-sha2-nistp256`, `ecdsa-sha2-nistp384`,
  `ecdsa-sha2-nistp521` or `ssh-rsa` (at least 2048 bits), without a host or comment field. Get it with
  `ssh-keyscan -t ed25519 <host>` on a path you trust and drop the first field;
- `url` is an absolute `http://` or `https://` URL, at most 2000 characters, with no whitespace or
  control characters;
- `checkout` is not blank; `checkout`, `setup`, `ready.command` and `reset` are each at most 32 KiB and
  contain no NUL character;
- `ready.timeoutSeconds` is a whole number from 1 to 600.

The file describes one VM. Agern compares the host key the VM presents with `hostKey` byte for byte:
no "trust on first use", no `known_hosts`, no fallback.

## Approval

The Sandbox VM page shows the state of the file on the default branch:

- **No sandbox VM** — there is no `.agern/sandbox-vm.yml`. The Project page then shows no Sandbox VM row.
- **Invalid** — the errors, each with its path.
- **Waiting for approval** — valid, never approved.
- **Changed since approval** — valid, but not the approved version: the page shows a diff against the
  approved content. No run can use the VM until this version is approved.
- **Approved** — by whom and when.

*Approve…* lists `user@host:port`, the host key's fingerprint and every command, and says what the
approval allows: the builder of every run of the project may then run any command on the VM as that
user. An admin approves what they saw; if the file changed meanwhile, the approval is refused and the
page shows the new version. Any change to the file — even whitespace — needs a new approval.

The approval is independent of `.agern/deploy.yml` and `.agern/sandbox.yml`: approving or revoking one
does not affect the others.

**The approval is checked again on every operation.** A run records the file's hash when it starts.
Each tool call, each preview and each `reset` is allowed only when the project's current approval has
that same hash and the project has a key; the commands that run are taken from the approved content,
never from the run's worktree. A run that changes the file therefore still uses the approved version,
and a file approved or revoked while a run is going takes effect at the run's next operation: after a
revocation or a newer approval the run's operations are refused ("approval revoked or changed since the
run started", with a link to the Sandbox VM page). *Revoke…* also deletes the VM's leases of this project
at once, without running `reset`.

In a workspace run the member repository the run changes counts: its file, its approval and its key.

## The key

The sandbox VM uses the project's **deploy key**, the same Ed25519 pair and the same public key line
(`ssh-ed25519 AAAA… agern-deploy:<org>/<repo>`) as the project's deploy targets. There is no second key.

- **Generate key** (admin) is on the Deploy page and, as a shortcut, on the Sandbox VM page; both create
  the same pair. The Sandbox VM page shows the public key with a *Copy* button and its fingerprint, or
  "No key yet".
- **Rotate key…** is only on the Deploy page. It rotates the key for the deploy targets *and* the
  sandbox VM: put the new public key line in the VM user's `authorized_keys` too, then remove the old
  one. Until then deploys to the VM fail at authentication.
- A project without a key cannot use its VM: the run's operations and *Check* say that an admin must
  generate it.

## The lease

A VM is identified across the whole server by its host (lower-cased) and port. At most one lease exists
per VM — also when two projects name the same VM.

- **Build lease.** The builder's first `factory_sandbox_vm_deploy` takes it. The run keeps it through
  its fix rounds and reviews, and it ends when the run's build/review part ends — finished, failed or
  cancelled — and in the worktree cleanup.
- **Preview lease.** A human preview (below) takes it. It ends when the owner or an admin releases it,
  when it expires (`FACTORY_SANDBOX_VM_PREVIEW_MINUTES`, default 120), when the run ends or leaves
  *needs human*, when the preview's deploy fails, when an admin force-releases it on the Sandbox VM page,
  or when the approval is revoked.
- **Nothing waits.** If another run holds the VM, or it is being reset, a deploy is refused at once. The
  builder is told who holds it and since when, and to continue without it: verify in the container
  sandbox or on the host and say in its summary that the VM check was skipped. A run that already holds
  the lease re-uses it.

**When a lease ends** — never blocking the run, never failing it:

1. the lease is marked as resetting (other runs are still turned away);
2. a command still running for the lease is cancelled ("The sandbox VM lease ended while the command
   ran.");
3. if the file has `reset`, the approval still matches and the key exists, `reset` runs once, with the
   command time limit;
4. the lease is deleted — whatever `reset` did. A failed, timed-out or skipped `reset` still frees the
   VM; it is logged as a warning and, for a build lease, recorded in the transcript of the builder that
   used the VM last.

Revoking the approval deletes the project's leases without running `reset`.

**At a server restart** a preview lease of a run that still waits in *needs human* and has not expired
is kept, with its expiry timer re-armed. Every other lease is ended in the background as above, `reset`
included.

## The deploy procedure

`factory_sandbox_vm_deploy` and a human preview deploy the same way, within one overall time limit (the
command time limit, at most the stage time limit):

1. **Archive.** Agern resolves the worktree's `HEAD` once and packs it with
   `git archive --format=tar.gz HEAD` into a temporary file outside the worktree, which is always
   deleted afterwards. **Only the committed `HEAD` is deployed:** uncommitted changes are not included
   (the answer says `dirty: true` when there were any), and neither is the content of submodules. An
   archive larger than
   `FACTORY_DEPLOY_MAX_COPY_MB` fails the upload step.
2. **Upload** with SCP to `~/agern-sandbox-vm.tar.gz` in the VM user's home directory, mode `0600`.
   Each deploy overwrites it.
3. **`checkout`**, then **`setup`** if defined: one SSH command each. A non-zero exit or a timeout stops
   the deploy.
4. **`ready`** if defined: runs again and again, with a 1 s pause, until it exits 0 or `timeoutSeconds`
   has passed. A failed connection while polling counts as a failed attempt.

Every command from the file (`checkout`, `setup`, `ready.command`, `reset`, also the `ready` of *Check*)
is sent with one line in front of it:

```sh
export AGERN_SHA='<commit>' AGERN_RUN_ID='<run id>' AGERN_ARCHIVE="$HOME/agern-sandbox-vm.tar.gz"
<your command>
```

`AGERN_SHA` and `AGERN_RUN_ID` are empty where there is no commit or run (e.g. *Check*). Commands run
as the VM user, in its home directory, through its login shell — which must therefore be
POSIX-compatible (`sh`, `bash` or `dash`; not `fish`, `csh` or `tcsh`). There is no
PTY and no stdin.

## Server settings

| Setting | Default | Meaning |
|---|---|---|
| `FACTORY_SANDBOX_VM` | `ssh` | `off` removes the tools from builders; a preview and *Check* answer 409 `sandbox_vm_disabled`. Files can still be approved. `fake` is for development only (requires `FACTORY_DEV_FAKE_GITHUB`). |
| `FACTORY_SANDBOX_VM_EXEC_TIMEOUT_MINUTES` | `30` | Default and maximum time of one command, and of one whole deploy (1–1440). Also capped by `FACTORY_STAGE_TIMEOUT_MINUTES`. |
| `FACTORY_SANDBOX_VM_PREVIEW_MINUTES` | `120` | How long a human preview holds the VM (5–1440). |
| `FACTORY_DEPLOY_MAX_COPY_MB` | `512` | Largest archive a deploy uploads (shared with deploy targets; 1–10240). |

The feature is on by default because nothing happens until an admin generates a key and approves a
file. Invalid values stop the server at start.

## The tools

What the builder sees (only the builder of an issue run whose file is approved, and only when
`FACTORY_SANDBOX_VM` is not `off`; for every other agent the server answers "Sandbox VM tools are only
available to a run's builder"):

- `factory_sandbox_vm_info {}` — whether the VM is available and why not; its name, host, port, user,
  description and URL; which commands the file defines; the lease (held by this run, held by another run
  with its id, project and start time, or being reset); the commit deployed last; the command running
  now; the limits; and a note that the VM is shared, that commands run as the user in its home directory
  through the login shell, and that a timed-out command may keep running on the VM.
- `factory_sandbox_vm_deploy { background? }` — takes the lease if needed and runs the deploy procedure.
  Answers `{ handle, ok, sha, dirty, url, steps: [{ step, ok, exitCode, timedOut, durationMs }],
  failedStep, output, truncated, durationMs }`. A failing step is a normal answer with `ok: false`; the
  lease stays. A refusal (busy, not approved, no key) or a connection, host-key or authentication failure
  is a tool error.
- `factory_sandbox_vm_exec { command, timeoutSeconds?, background? }` — one SSH command on the VM,
  through the user's login shell, no PTY, no stdin. Requires the lease ("Call factory_sandbox_vm_deploy
  first."). `command` is not blank, at most 32,768 characters, without NUL. Answers `{ handle, exitCode,
  output, truncated, durationMs, timedOut, note? }`. A non-zero exit code is a normal answer. On a timeout
  Agern closes the connection: `exitCode: -1`, `timedOut: true`, and a note that the remote process may
  still be running.
- `factory_sandbox_vm_wait { handle?, waitSeconds? }` — like `factory_sandbox_wait`: waits for a
  background deploy or command (default: the latest) and returns its answer, or `running: true` when
  `waitSeconds` ran out first. The last 20 results are kept while the lease lasts.

One operation runs at a time per lease. Operations run on the server, independent of the tool call: a
call cut off on the agent's side does not stop the command, and a later wait gets its result. The agent
gets the last 20,000 characters of the output.

The builder's prompt tells it to deploy after committing, to verify with `factory_sandbox_vm_exec`, to
keep builds and unit tests in the container sandbox or on the host, to continue without the VM when it
is in use and say so in its summary, and never to handle SSH keys.

**Records.** Every deploy step, command and `reset` appears in the builder's transcript as one block with
the VM, the step or command, the commit, the exit code or error, the duration and the output; failures
are marked. After the builder's first successful lease the run page's VM line shows `used ✓`. The server
log gets one line per operation (run, project, VM, kind, outcome, duration), never the command or its
output.

## The human preview

When a run waits in *needs human* and its file is approved, the run page shows a **sandbox VM** panel.
The run's owner and admins see the button **Try it on the sandbox VM**: it takes a preview lease,
deploys the run's latest commit in the background and shows "Deploying <commit> to <name>…". Then:

- **Ready** — "Deployed <commit> to <name>.", a link **Open <url> ↗** when the file has a `url`, "Held
  until <time>", and the buttons **Deploy again** (deploys the current `HEAD` again and renews the time)
  and **Release the VM**.
- **Failed** — "Deploying to <name> failed at <step>: <error>", the output (its last 20,000 characters)
  in a collapsed block, and **Try again**. The lease has already ended and `reset` ran.
- **Released** — the button again, with "Released <time ago>" or "The preview ended after <N> minutes."

The preview holds the VM for `FACTORY_SANDBOX_VM_PREVIEW_MINUTES` (default 120) at most; other runs
cannot use it meanwhile. If the VM is busy, the panel says who holds it. Everyone else who can see the
run sees the state and the link but no buttons. The preview also ends when the run is approved,
cancelled or otherwise leaves *needs human*.

## Check

*Check* on the Sandbox VM page (admins, once the file is approved and the key exists) connects, verifies
the host key and authenticates. If that works and the file has `ready`, it runs `ready.command` once with
a 60 s limit. The result names the stage reached:

- **`connect`** — the host does not resolve, the port is closed or filtered, or the VM is off. Try
  `nc -vz <host> <port>` from the Agern host.
- **`host_key`** — the VM presented another host key than the pinned one (the message shows its
  fingerprint). If the VM was reinstalled, update `hostKey` and approve the file again; if nobody changed
  it, treat it as an attack.
- **`auth`** — the key was refused: the public key is not (or, after a rotation, no longer) in
  `~<user>/.ssh/authorized_keys`, a `from="…"` does not match Agern's address, or the permissions of
  `~/.ssh` are too open for sshd.
- **`ready`** — connected, but `ready.command` failed; the exit code and output are shown.
- **`ok`** — everything worked.

## Limits

- One VM per project, one lease per VM (server-wide, by host and port), one operation per lease.
- Commands and whole deploys: `FACTORY_SANDBOX_VM_EXEC_TIMEOUT_MINUTES` (default 30), also capped by the
  stage time limit; `ready` polling: `timeoutSeconds` (at most 600); *Check*'s `ready`: 60 s. Connecting
  and authenticating time out after 15 s each, as for deploy targets.
- Archive size: `FACTORY_DEPLOY_MAX_COPY_MB` (default 512).
- A command runs in the foreground of its SSH session: start services through the VM's service manager
  (`sudo systemctl restart app`) in `setup`, not as a background process in a command.
- The VM must be reachable from the Agern host; jump hosts and VPNs are not supported.

## Recommended VM setup

The VM runs whatever the builder of any run of the project asks for — and the builder's input includes
issue text, comments and repository content written by others. Set the VM up so that this does no harm.

- **A dedicated, unprivileged user** for Agern, not `root` and not your own account:

  ```sh
  sudo adduser --disabled-password --gecos "Agern sandbox VM" agern
  sudo install -d -m 700 -o agern -g agern ~agern/.ssh
  echo 'restrict,from="192.168.1.10" ssh-ed25519 AAAA… agern-deploy:Egernet-CS/shop' | sudo tee -a ~agern/.ssh/authorized_keys
  sudo chown agern:agern ~agern/.ssh/authorized_keys && sudo chmod 600 ~agern/.ssh/authorized_keys
  ```

  Its login shell must be POSIX-compatible (the default `/bin/bash` or `/bin/sh` is).
- **`restrict` and `from="<agern host>"`** in front of the key in `authorized_keys`, as above: no
  forwarding, no PTY, and the key works only from Agern's address. See [DEPLOY.md, "Locking the key
  down"](DEPLOY.md#locking-the-key-down-authorized_keys).
- **No forced `command="…"`.** Unlike deploy targets, a sandbox VM does **not** support a forced command
  on the key line: it replaces the SCP upload of the archive and every command the builder runs, so
  deploying and `factory_sandbox_vm_exec` both break. If you need that kind of lock, use a deploy target
  instead.
- **Narrow `sudoers` rules** for what `setup` and `reset` need, e.g.
  `agern ALL=(root) NOPASSWD: /usr/bin/systemctl restart app`, not general `sudo`.
- **Mocks or scrubbed data, no production credentials.** The VM is a test machine: test or anonymised
  data, sandbox accounts for payment and mail providers, no keys or passwords that work in production.
  Everything a command prints is visible to everyone who can see the project.
- **A firewall so the VM cannot reach production** — not the production database, not internal APIs —
  and ideally nothing else on your network that it does not need.
- **Idempotent `checkout` and `setup` that do not depend on `reset`.** A `reset` can fail or be skipped
  (a revoked approval, a server stop), and a run may deploy several times. `checkout` should replace the
  application's files completely (`rm -rf` the directory, then unpack `"$AGERN_ARCHIVE"`), and `setup`
  should bring the VM into a known state on its own (run migrations, restart services), whatever ran
  before.

## Troubleshooting

- **The run page says `none — …`.** `invalid`: fix the errors listed on the Sandbox VM page. `unapproved`:
  an admin must approve the file; runs started before the approval keep their decision, so start a new
  run.
- **"The sandbox VM <name> is in use by run …" / "is being reset."** Another run (possibly of another
  project naming the same host and port) or a preview holds the VM. The builder continues without it.
  An admin can force-release the lease on the Sandbox VM page; a preview ends by itself after
  `FACTORY_SANDBOX_VM_PREVIEW_MINUTES`.
- **"approval revoked or changed since the run started".** The file was changed and approved again, or
  revoked, while the run was going. Start a new run.
- **No key.** An admin generates it on the Deploy page or the Sandbox VM page.
- **Deploy fails at `upload`.** The archive is larger than `FACTORY_DEPLOY_MAX_COPY_MB`, the user's home
  directory is not writable or full, or the key line has a forced `command="…"`.
- **Deploy fails at `checkout` or `setup`.** The command's own problem; its output is in the transcript
  (or the preview's details). Run the command by hand as the VM user with `AGERN_ARCHIVE` set to try it.
- **Deploy fails at `ready`.** The app did not answer within `timeoutSeconds`: raise it, or check the
  service's log (`journalctl -u app`).
- **The deployed code lacks recent changes.** Only committed `HEAD` is deployed: commit first. Submodule
  content is never included.
- **Strange errors from every command** (`export: command not found`, syntax errors). The VM user's
  login shell is not POSIX-compatible.
- **Connection problems** — use *Check* (above).
- **The tools are missing.** The server has `FACTORY_SANDBOX_VM=off`, the run's file is not approved, the
  agent is not the builder, or the run brings a release up to date.

## Security notes

- **The key never reaches the agent.** It exists only encrypted in the database and, during one
  connection, in the server's memory; Agern connects in process, never through the `ssh` or `scp`
  binaries (see [DEPLOY.md, "Security notes"](DEPLOY.md#security-notes)).
- **The host key is pinned.** A VM that presents another host key is refused before authentication.
- **The approval is checked again on every operation**, and the commands come from the approved content,
  so neither a run's own changes nor a later edit to the file can change what runs on the VM without a
  new approval.
- **Approval is the gate.** Every builder of the project can run any command on the VM as its user, and
  prompt injection through issues or repository content is a real risk. Keep the user unprivileged, the
  key restricted, the data fake and the VM fenced off from production.
- **Output is shared.** Everything the VM prints is stored in the run's transcript and visible to everyone
  who can see the project.
