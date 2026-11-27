# Sandboxes: a guide for project authors

A *sandbox* is a container in which Agern's builder agent builds and tests your project during a
pipeline run. You describe it in your repository, in **`.agern/sandbox.yml`** on the default branch;
an admin approves that exact file; from then on every run of the project gets its own sandbox.

This guide is for the people who write that file. How an admin switches sandboxes on is in
[INSTALL-LINUX.md §13](INSTALL-LINUX.md#13-sandboxes); the server's exact behaviour and the Podman
command lines are in [ARCHITECTURE.md §5](ARCHITECTURE.md#sandboxes).

## What a sandbox is — and is not, in this release

**It is** the project's toolchain, sidecar services (e.g. a PostgreSQL per run), resource limits and a
clean environment, without anyone installing that toolchain on the factory host:

- one sandbox per run, created on the builder's first sandbox command and removed when the run's
  build/review part is over (also after a cancel, a time-out or a server restart);
- the setup script runs once per project, image and approved definition: the container it prepared is
  stored as a *prepared image*, and later sandboxes of the project (other runs, the next issue of a
  release, the review sandbox) start from it in seconds, each still in its own fresh container (see
  "Prepared images");
- the run's worktree mounted read-write at `/workspace` (the builder edits on the host and builds in
  the container: the same files); nothing else from the host is mounted;
- rootless Podman: the containers belong to the factory's unprivileged service user, never to root, are
  never privileged, and Agern — not your file — decides mounts, network, capabilities and limits;
- sidecars on a private network of the run, reachable by service name from that run only;
- per-project cache volumes (Gradle, npm, pip …) that survive between runs.

**It is not:**

- **a security boundary against the agent.** The agent itself still runs on the host as the service
  user, with its permissions bypassed; it can reach what that user can. Containing the agent is a later
  release.
- **a place for credentials.** Nothing of the run owner's credentials is passed in: no GitHub token, no
  Claude credential, no git configuration. git does not work inside the sandbox (the worktree's `.git`
  points outside the mount); the agent commits on the host. Builds that need a private package registry
  are not supported yet. Never put secrets in the file: it is in your repository and shown to everyone
  who can see the project.
- **a network boundary.** Outbound network is Podman's default, so package registries work — and so
  does the host's LAN. Nothing is restricted in this release.

For an app that only runs meaningfully on a whole machine (systemd, nginx, a native database), a run
can also deploy to a **sandbox VM** after building and testing here: see [SANDBOX-VMS.md](SANDBOX-VMS.md).
Both can be used in the same run.

For the whole trust model (who can do what, what agents can do with the owner's GitHub token, what is
merged without a human), see [README.md, "Security: read this first"](../README.md#security-read-this-first).

Three stages use the sandbox. The spec writer reads its description (`factory_sandbox_info`) and plans
its use in the spec's **Verification** section; it cannot run anything. The builder runs commands in it
with the worktree read-write. The `ux` and `ui-design` reviewers get a separate, read-only **review
sandbox** in which they start the app and take screenshots (below). The `security` and `code` reviewers
and the approver read the code on the host as before. Projects without an (approved, valid) file keep
working exactly as before, on the host. No agent can enable, change or approve a sandbox: that stays an
admin's decision (next section).

## Approval

The server reads `.agern/sandbox.yml` from the commit a run starts from (the default branch) and uses
it only if an admin approved **exactly that content** (its SHA-256). Every change to the file needs a
new approval; until then runs proceed without a sandbox and the run page says why. A run that edits the
file still uses the approved version it started from, so a change can never approve itself. Runs of a
release read the file from the default branch too, not from the release branch they build on, so changes merged into
the release cannot change its sandbox.

The project's **Sandbox** page (from the project's activity panel) shows the file, its validation
errors, a diff against the approved version and, for admins, *Approve* and *Revoke*. Approve only what
you would run on the factory host yourself: the images, the setup scripts and the services all run there.

## The file

Every key, with its default. Only `image` is required.

```yaml
# The image commands run in. Fully qualified (registry/name:tag or @sha256:…) and from a registry the
# server allows (FACTORY_SANDBOX_REGISTRIES, default docker.io, ghcr.io, quay.io, mcr.microsoft.com).
# It needs a POSIX sh.
image: docker.io/library/eclipse-temurin:21-jdk

# Shell script, run as root in a fresh container (sh -lc, in /workspace, time limit as for commands).
# Install system packages here. The result is stored as a prepared image that later sandboxes start
# from, so it runs once per project, image and definition, not per run (see "Prepared images").
# Optional; at most 32 KiB.
setup: |
  apt-get update && apt-get install -y --no-install-recommends git

# Environment of every command (names: letters, digits, _; at most 100). Not for secrets.
env:
  CI: "true"

# Run commands as this user instead of root: a name (uid 1000) or a uid. Optional. See "A non-root user".
user: ubuntu

# Sidecars, at most 5, reachable by their name (here: host "postgres").
services:
  postgres:
    image: docker.io/library/postgres:17          # same rules as `image`
    env: { POSTGRES_PASSWORD: dev }               # optional
    ready:                                        # optional readiness check, see "Services"
      command: pg_isready -h 127.0.0.1 -U postgres
      timeoutSeconds: 60                          # 1–600, default 60

# Directories kept between runs of this project (at most 10): absolute, not /workspace, not system
# directories, not overlapping. One volume per path (per platform).
caches: [ /home/ubuntu/.gradle ]

# Per container; capped by the server (FACTORY_SANDBOX_MAX_CPUS, _MAX_MEMORY: default 2 and 4g).
resources: { cpus: 2, memory: 4g }

# Other images to test on (at most FACTORY_SANDBOX_MAX_PLATFORMS, default 4). See "Platforms".
platforms:
  jdk17:
    image: docker.io/library/eclipse-temurin:17-jdk
    # no `setup:` key: the top-level setup runs; `setup:` with an empty value: none
```

Validation is strict: unknown keys (at any level), unqualified images, registries that are not allowed,
service and platform names that are not lower-case DNS labels (and the reserved ones), and malformed
values are all errors, listed with their place in the file. The file may be at most 64 KiB; YAML anchors
and duplicate keys are refused. An invalid file means no sandbox (the run proceeds on the host).

## How the builder uses it

The builder gets three tools. `factory_sandbox_info` describes the sandbox: image, platforms, the user,
services (host name, ports, readiness), caches, limits, and the command running now. `factory_sandbox_exec
{ command, timeoutSeconds?, platform?, background? }` runs `sh -lc <command>` in `/workspace` and returns
a handle, the exit code and the last 20,000 characters of the output; the full output (up to 1,000,000
characters) of every command and setup script appears on the run page. One command runs at a time per
run; a command that runs out of time (server default 30 minutes) is killed together with everything it
started, files are kept.

A call takes as long as its command, also many minutes: Agern raises the agent's MCP timeouts to the
stage and command limits and streams progress while the call runs, so a full build with tests comes back
in one call. With `background: true` the command starts and the call returns its handle at once, so the
builder can keep working; `factory_sandbox_wait { handle?, waitSeconds? }` then waits for the command
(default: the most recently started one) and returns the same result, or `running: true` when
`waitSeconds` ran out first. While a background command runs, any other `factory_sandbox_exec` of the run
is refused with its handle. Commands run on the server, independent of the tool call that started them:
when a call is cut off on the agent's side, or the builder's stage ends without waiting, the command runs
on until it ends, reaches its time limit or the sandbox is removed, and a later wait (also in a fix round
of the same run) gets its result. When the run's sandbox is removed (the run ended, or an admin stopped
it), a running command dies with it and its waits end with an error saying so.

## The review sandbox

The `ux` and `ui-design` reviewers of a run with an approved file look at the running app in a review
sandbox: a container of its own, built from the same approved file, so an app that needs the project's
toolchain or its database can be started for review. What to know when you write the file:

- **Read-only worktree.** `/workspace` is the run's worktree, mounted read-only: every write below it
  fails. An app that writes into its own tree at start (build output, `node_modules`, caches next to the
  code) is copied to `/tmp` inside the container first; the reviewers know this. Point caches and
  temporary files at a `caches:` path or `/tmp` where you can.
- **Default image only, own services.** It uses the top-level `image`, `setup`, `env`, `user`,
  `resources` and the default `caches` (shared with the builder's container, read-write) — no platforms
  — and starts its own copy of every service, with your readiness checks. It does not share data with the
  builder's services.
- **Setup.** The review sandbox starts from the project's prepared image (the same one the builder's
  sandbox uses, see "Prepared images"), so setup does not run again. Only when there is none yet does
  setup run here, as root, with `/workspace` read-only, and its result is stored for later sandboxes: it
  must not write into `/workspace` (install into the image's system directories, the user's home or a
  cache instead). Afterwards only the user's home is handed over to `user:`.
- **A browser.** To take screenshots inside the container the reviewers need a headless browser there:
  install one in `setup` (e.g. Chromium, or Playwright's browser into a `caches:` path such as
  `/home/<user>/.cache/ms-playwright`, so it is downloaded once per project).
- **Output directory.** The only directory the container can write on the host is `/run/agern-review`
  (`.agern-run/review` in the agents' working directory, git-ignored); screenshots go there and are
  attached from the host. It is deleted with the sandbox. Do not use `/run/agern-review` in `caches:`.
- **Lifetime.** One per review round: created by the first reviewer command, shared by both UI
  reviewers (the app is started once), removed when the round ends. A fix round gets a fresh one.
- **Memory and slots.** Its container takes one of the server's `FACTORY_SANDBOX_MAX_CONCURRENT` slots
  and, with its services, the per-container memory each. When no slot is free the server first removes
  the run's own idle build containers; otherwise the reviewers review on the host as before, nothing waits.
- **Verification.** The spec can say how the reviewers start the app there (start command, readiness
  probe, URL, login or seed data) and which screens to look at. A `.agern/preview.md` recipe works too.

## Platforms

`platforms:` names further images — another runtime version, another distribution — on which the
builder runs the test suite too. With platforms, the builder is asked to run the tests in the default
image and on every platform before it finishes, and to report the result per platform; the run page
lists the platforms used and labels every command with the platform it ran on.

- A platform's container is created on its **first** command, with the same worktree, environment,
  user, limits and network (so the same services under the same names) as the default one. Its setup is
  its own `setup:`, or the top-level one when the platform has no `setup` key.
- **Caches are per platform**: the same paths, in separate volumes, because another JDK, libc or Node
  version cannot be assumed to share a cache safely.
- Each container that runs commands — the default one and each platform's — takes one of the server's
  `FACTORY_SANDBOX_MAX_CONCURRENT` slots and has the full memory limit, so a run that tests on three
  images uses up to three times the memory (plus its services). When the server has no free slot, a run
  first gives up its own idle containers (the least recently used; it is created again from its prepared
  image when used next), so testing on several platforms also works on a small server, one container at
  a time.
- Each platform has its own prepared image, made by its own setup the first time it is used.
- Platform names: lower-case letters, digits and `-`, starting with a letter, at most 31 characters;
  `default` means the top-level image.

## Prepared images

Setting up a container (pulling the image, `apt-get update`, installing packages, downloading a
runtime) takes minutes and gives the same result every time, as long as the image and the file do not
change. So Agern does it once:

- The **first** sandbox of a project with a `setup` script runs it as described above. When setup has
  succeeded, Agern stores that container's file system as a local image, a *prepared image*
  (`localhost/agern-prepared/<project>:<key>`). The run page says `Preparing sandbox image (setup) …`
  and then `Prepared image <key> stored in … s`.
- **Later** sandboxes with the same key start from that image and skip setup: other runs, the next
  issue of a release, the review sandbox, a container recreated after an admin's stop or after a run
  made room for a platform. The run page says `Sandbox from prepared image <key> (prepared <date>)`.
  Each sandbox is still its own fresh container with its own worktree mount, services and network:
  runs share the prepared starting point, never each other's files, processes or databases.
- The **key** is made of the project, the platform, the approved version of this file, the local id
  of the base image and Agern's own preparation scripts. A newly approved file, a base image that was
  pulled again (a moved tag), another platform or a new Agern version that prepares the user
  differently therefore prepares a new image; old ones are never reused.
- A prepared image is used for at most `FACTORY_SANDBOX_PREPARED_MAX_AGE_DAYS` (default 7) days.
  After that setup runs again, so `apt-get update` and similar steps pick up security updates. Unused
  expired images are removed by the daily prune. An admin can remove all unused prepared images at any
  time (admin page, Sandboxes, or `agern sandbox prune --prepared`); `0` switches prepared images off
  and runs setup in every new container, as before.
- A failed setup never stores anything. When two runs need the same missing image at the same time,
  the second waits for the first (at most the command limit) and starts from its image.

What this means for your `setup` script — **setup must only install into the image**:

- A prepared image keeps the container's own file system after setup: system directories, `/opt`, the
  user's home.
- It does **not** keep what setup wrote into `/workspace` (the worktree is mounted, not copied), into a
  `caches:` path (a volume) or into `/run/agern-review`, nor anything it did to a service (a database
  it created or migrated), nor processes it started. Such effects happen only in the sandbox that
  prepares the image; later sandboxes do not see them. Do these things in the build (or in the
  Verification commands) instead.
- What setup *reads* from `/workspace` (for example a version number in a lock file) is frozen in the
  image until it is prepared again: after a change that matters, ask an admin to remove the prepared
  images.

**Caches versus prepared images.** Both survive between runs. `caches:` volumes hold downloads that the
build itself fills and updates (Gradle, npm, pip), are shared by all runs of the project and are mounted
into every sandbox. A prepared image holds what setup installed into the system; it is read-only for the
runs (each sandbox writes into its own container layer) and replaced, not updated.

## Services and readiness

A service is started from its image with its own default command and the environment you give it.
Without a readiness check the first command may run before the service accepts connections (a database
initialising its data directory takes seconds), and the builder is told so. With

```yaml
    ready:
      command: pg_isready -h 127.0.0.1 -U postgres
      timeoutSeconds: 60
```

the server runs `command` with `sh -c` **inside the service's container** once a second until it
exits 0, and only then runs anything in the sandbox. If it does not pass within `timeoutSeconds`, the
sandbox is removed and the builder and the run page get the service's name, the check's last result and
the service's last 30 log lines. Good checks:

| Service | `ready.command` |
|---|---|
| PostgreSQL | `pg_isready -h 127.0.0.1 -U <user>` (TCP: the official image's init phase listens only on a socket) |
| MySQL / MariaDB | `mysqladmin ping -h 127.0.0.1 --silent` / `healthcheck.sh --connect` |
| Redis | `redis-cli ping` |
| Anything with HTTP | `wget -q -O /dev/null http://127.0.0.1:8080/health` (if the image has wget) |

The command runs in the *service's* image, so it can only use what that image contains.

## A non-root user

Commands run as root inside the container by default (which rootless Podman maps to the service user on
the host). Some tools refuse to run as root — PostgreSQL's `initdb`, used by embedded-PostgreSQL test
libraries, is the common one. Then set `user`:

- `user: <name>` runs commands as that name with uid 1000. If the image already has the name with uid
  1000 (e.g. `node` in the Node images, `ubuntu` in Ubuntu-based images such as `eclipse-temurin`), that
  account is used; if it does not exist it is created with home `/home/<name>`. If the image has the name
  with another uid, or uid 1000 belongs to another name, creating the sandbox fails with a message telling
  you what to write instead (e.g. `user: ubuntu`).
- `user: <uid>` (1–60000) runs as that uid: under the image's name for it, or as a new account `sandbox`.

The setup script still runs as **root**, before the commands. The user owns its home directory and the
cache directories; whatever setup leaves in the home directory or in `/workspace` is handed to the user
afterwards. Inside the container the user is uid *n*, and on the host it is the factory's service user,
so everything the build writes in `/workspace` stays editable by the agent. (Technically: `--userns
keep-id:uid=<n>,gid=<n>`; container root is then an unprivileged subordinate id.) The first sandbox of
an image with a `user` can take longer to start once, while Podman prepares the image for that mapping.

Point cache paths at the user's home, e.g. `/home/ubuntu/.gradle`, not `/root/.gradle`.

## Caches

Each path in `caches` is a named volume of the project (one per platform), kept between runs and
removed only by an admin (`agern sandbox prune --caches`, which also removes the unused prepared
images, since a setup script may have filled a cache that is then gone). Use them for downloads that are
safe to reuse: `~/.gradle`, `~/.m2`, `~/.npm`, `~/.cache/pip`, `~/.cache/ms-playwright`. What setup
writes into a cache path is not part of the prepared image (see "Prepared images"): a browser that setup
downloads into a cache is there as long as the volume is. Do not use them for
build output that belongs to a run (that stays in `/workspace`) or for anything secret. Several runs of a
project can use a cache at the same time; tools whose caches are not safe for concurrent use (few are)
need their own locking.

## Limits

| | |
|---|---|
| CPUs, memory | `resources`, capped by the server (default 2 CPUs, 4 GiB per container; no swap) |
| Processes | 4096 per container |
| One command | the server's limit (default 30 minutes) and the stage limit; `timeoutSeconds` can only lower it |
| One tool call | as long as the command: Agern raises the agent's MCP timeouts to the stage/command limit and streams progress while the call runs; `background: true` + `factory_sandbox_wait` returns at once instead |
| Handles | the running command and the results of the last 20 finished commands per run, until the sandbox is removed |
| Containers at once | `FACTORY_SANDBOX_MAX_CONCURRENT` over the whole server (default 2), counting the default and each platform container and the review sandbox's; a builder over it is told to try again later |
| Review sandbox | one per run, for one review round, default image only; one slot (after removing the run's idle build containers); without a free slot the UI reviewers review on the host |
| Prepared images | reused for `FACTORY_SANDBOX_PREPARED_MAX_AGE_DAYS` (default 7; `0`: off), one per project, platform, definition and base image |
| Platforms | `FACTORY_SANDBOX_MAX_PLATFORMS` (default 4) |
| Services | 5, each with the same CPU and memory limits as the sandbox |

A command that uses more memory than the limit is killed by the kernel; the sandbox itself survives.

## Troubleshooting

- **The run says "no sandbox":** the file is missing on the default branch, invalid (the reason is on
  the run page and the project's Sandbox page), or not approved in this version.
- **"Could not create the sandbox: … pull …":** the image name is wrong, the registry is not allowed, or
  Docker Hub's anonymous pull limit was hit; try again later or use another registry.
- **A tool that setup installs is missing or outdated:** the sandbox started from a prepared image
  made before. A new approval of the file prepares a new one by itself; otherwise ask an admin to remove
  the prepared images (admin page, Sandboxes, "Remove prepared images"), and the next sandbox runs setup
  again. Also check that setup did not put the tool into `/workspace` or a cache path, which are not
  part of the prepared image (see "Prepared images").
- **"Another run is still preparing this project's sandbox image":** a first sandbox is running setup
  and took longer than the command limit; try again a few minutes later.
- **"The sandbox's setup script failed":** its output is on the run page. Setup runs non-interactively
  as root: use `DEBIAN_FRONTEND=noninteractive`, `apt-get install -y`, and remember that `apt-get update`
  is needed in fresh images.
- **A tool is not found although the image has it:** commands run in a *login* shell (`sh -lc`), and
  Debian's `/etc/profile` resets `PATH`. Ubuntu, Alpine and the official language images that put their
  tools in `/usr/local/bin` are not affected; otherwise call the tool by its full path or extend `PATH`
  in a file under `/etc/profile.d/` from the setup script.
- **"… refuses to run as root" / `initdb: cannot be run as root`:** add `user:` (above).
- **"uid 1000 already belongs to the image's user '…'":** write `user: <that name>`.
- **"Service '…' did not become ready":** read the log lines in the message; raise `timeoutSeconds`
  for slow services or fix the check (it runs inside the service's image).
- **Connection refused from a service without `ready`:** add a readiness check, or retry in the test.
- **`The operation timed out` on a sandbox call:** the agent's MCP client gave up on the call (an older
  Agern, or a client that ignores the timeout settings), but the command still ran. Call
  `factory_sandbox_wait` without a handle to get its result.
- **"The server's sandboxes are all in use":** other runs hold every slot; the builder retries later, a
  UI reviewer reviews on the host without the review sandbox.
- **"Read-only file system" in the review sandbox:** the app (or your setup script) writes into
  `/workspace`. Make it write to `/tmp` or a cache path, or let the reviewers copy the tree to `/tmp`
  first (see "The review sandbox").
- **Screenshots from the review sandbox do not show up:** they must be written to `/run/agern-review`
  inside the container; `factory_sandbox_info` says when the output directory could not be created.
- **The app stops between reviewer commands:** start it detached with every stream redirected
  (`nohup <start> > /tmp/app.log 2>&1 < /dev/null &`); a command that hits its time limit restarts the
  container and kills the app.
- **Permission denied in a cache:** the cache was filled by an earlier version of the file that ran as
  root; ask an admin to remove the project's cache volumes (`agern sandbox prune --caches` removes the
  unused ones).

## Examples

### JVM and PostgreSQL (Gradle)

```yaml
image: docker.io/library/eclipse-temurin:21-jdk
user: ubuntu                       # Ubuntu-based image: uid 1000 is 'ubuntu'; embedded PostgreSQL needs non-root
env:
  SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/app
  SPRING_DATASOURCE_USERNAME: app
  SPRING_DATASOURCE_PASSWORD: app
services:
  postgres:
    image: docker.io/library/postgres:17
    env: { POSTGRES_DB: app, POSTGRES_USER: app, POSTGRES_PASSWORD: app }
    ready: { command: "pg_isready -h 127.0.0.1 -U app -d app" }
caches: [ /home/ubuntu/.gradle ]
platforms:
  jdk17:
    image: docker.io/library/eclipse-temurin:17-jdk
```

The builder runs `./gradlew test` and `factory_sandbox_exec { command: "./gradlew test", platform:
"jdk17" }`. (A Gradle *toolchain* pins the JDK the tests use, whatever the image's; a JDK platform is
only meaningful when the build follows the running JDK.)

### Python and PostgreSQL

```yaml
image: docker.io/library/python:3.12-slim
user: app                          # created with uid 1000
env:
  DATABASE_URL: postgresql://app:app@postgres:5432/app
  PIP_DISABLE_PIP_VERSION_CHECK: "1"
services:
  postgres:
    image: docker.io/library/postgres:17
    env: { POSTGRES_DB: app, POSTGRES_USER: app, POSTGRES_PASSWORD: app }
    ready: { command: "pg_isready -h 127.0.0.1 -U app -d app", timeoutSeconds: 90 }
caches: [ /home/app/.cache/pip ]
platforms:
  py311:
    image: docker.io/library/python:3.11-slim
```

Commands: `python -m venv .venv && .venv/bin/pip install -r requirements.txt && .venv/bin/pytest`
(the virtual environment lives in the worktree; on a platform, recreate it: `rm -rf .venv` first, or use
`.venv-py311`).

### Node

```yaml
image: docker.io/library/node:22
user: node                         # the image's own account, uid 1000
caches: [ /home/node/.npm ]
platforms:
  node20:
    image: docker.io/library/node:20
```

Commands: `npm ci && npm test`. For browser tests with Playwright, install the browsers' system
libraries in `setup` (`npx --yes playwright@<version> install-deps chromium`, as root) and the browser
itself in a command (`npx playwright install --only-shell chromium`), with
`/home/node/.cache/ms-playwright` as a cache.

### Agern itself

This repository's own [`.agern/sandbox.yml`](../.agern/sandbox.yml) builds and tests Agern: JDK 17 for
the server (its Gradle toolchain), Node.js for the web app, the system libraries of Playwright's Chromium,
a PostgreSQL service with a readiness check for the end-to-end suite, `user: ubuntu` for the server
tests' embedded PostgreSQL, and caches for Gradle, npm and the Playwright browser.
