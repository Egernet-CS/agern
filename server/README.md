# Agern server

The backend of Agern — the software factory: Kotlin, Ktor 3 (Netty), PostgreSQL (Flyway, HikariCP,
plain JDBC). The API contract is [`docs/ARCHITECTURE.md`](../docs/ARCHITECTURE.md); configuration is
by environment variables (§3 there, annotated in [`.env.example`](../.env.example)).

## Build and test

Requires JDK 17. Nothing else: the Gradle wrapper downloads Gradle, and the tests use an embedded
PostgreSQL (zonky) plus fakes for GitHub, git and the Claude CLI.

```sh
./gradlew build                     # compile + all tests
./gradlew :server:test              # tests only
```

Release jar (one self-contained file, frontend embedded):

```sh
(cd web && npm ci && npm run build)                       # the Gradle build never runs npm itself
./gradlew :server:shadowJar -Pversion=1.2.3 -PwebDist=../web/dist
java -jar server/build/libs/agern.jar                     # reads FACTORY_* from the environment
```

`-PwebDist` is resolved relative to `server/` and must contain `index.html`; it is copied into the
jar under `web/`. Without it the jar has no frontend (set `FACTORY_WEB_DIR` instead, or use the API
only). `-Pversion` defaults to `0.0.0-dev` and is what `GET /api/health` reports.

## Production database (native PostgreSQL)

Agern runs directly on its host against a natively installed PostgreSQL 16 — no Docker:

```sh
sudo -u postgres createuser --pwprompt factory
sudo -u postgres createdb --owner=factory factory
# FACTORY_DB_URL=jdbc:postgresql://localhost:5432/factory (the default), FACTORY_DB_USER/PASSWORD
```

Flyway migrates the schema on every startup. Unfinished pipeline runs are marked `failed` when the
server restarts (v1); chat sessions become `idle` and resume on the next input.

## Development

```sh
docker compose -f docker-compose.dev.yml up -d            # throwaway PostgreSQL on localhost:54329

export FACTORY_DB_URL=jdbc:postgresql://localhost:54329/factory
export FACTORY_MASTER_KEY=$(openssl rand -base64 32)       # keep it stable or stored secrets become unreadable
export FACTORY_GITHUB_ORG=Egernet-CS
export FACTORY_AUTH=dev FACTORY_DEV_USERS=alice:alice-pw,bob:bob-pw FACTORY_ADMINS=alice
export FACTORY_ALLOWED_ORIGINS=http://localhost:5173        # Vite dev server
./gradlew :server:run
```

Logging in is two-step: `POST /api/auth/login` returns a `LoginStep`; the first login enrols TOTP
(`next: 'enroll'` with the secret and `otpauth://` URI), later logins ask for a code. For scripted
tests `FACTORY_2FA=off` is accepted when `dev` is the only provider.

### Working without GitHub and without spending tokens

* `FACTORY_DEV_FAKE_GITHUB=true` (requires `FACTORY_AUTH=dev`): an in-memory GitHub organization with
  the repos `demo-app` (visible to everyone) and `private-lab` (only visible to login `alice`) and a
  few issues. Register the GitHub token `fake-<login>` in Settings (e.g. `fake-alice`). Workspaces
  are local `git init` repositories and pushes are skipped; PRs, comments, labels and merges are
  simulated.
* `FACTORY_CLAUDE_BIN=$PWD/server/src/test/resources/fake-claude`: a Python 3 stand-in for the
  `claude` CLI that speaks the same stream-json protocol with canned behaviour:
  * chat: replies "You said: …" as streamed deltas; `slow` streams for ~10 s (try Interrupt),
    `tool` adds a Bash tool call, `mcp` calls the factory MCP endpoint and lists its tools, `install`
    files a software request through MCP (`factory_request_software`), `env` lists the environment
    variable names it received, `crash` exits with status 3, `long` answers with 80 lines; a message with
    attachments gets a report of what arrived (`IMAGE 1: image/png 32x16, … bytes` per decoded image block,
    `FILE ok <name> <n> bytes` per listed path found on disk, `FILE missing …` otherwise);
    `forward <runId> <role>: <message>` passes the message to that run's agent through the MCP tool
    `factory_send_to_agent` and reports the answer;
  * pipeline: the spec-writer prints a small spec, the builder commits to `AGERN_FAKE.md`,
    reviewers answer PASS and the approver APPROVE. Put `fake:changes` in the issue body for one
    round of CHANGES, `fake:always-changes` to exhaust the fix rounds (→ `needs_human`),
    `fake:escalate` for an approver ESCALATE, `fake:fail-<role>` to make a stage fail,
    `fake:hang-<role>` to make it hang (try the stage time limit or Cancel), `fake:slow` to make every
    stage take ~1.5 s so the diagram can be watched advancing, `fake:markdown` to have reviewers and
    the approver write their summaries and findings in Markdown, `fake:busy-<role>` (spec-writer, builder) to
    keep that agent working in short Bash steps until a message from the run owner contains `finish`
    (try the composer under its transcript on the run page). Like the real CLI, the spec-writer and
    builder pick up the owner's messages after their current tool call, or as a further turn when they
    were finishing.

    Put `fake:sandbox` in the issue body to make the builder call `factory_sandbox_info` and
    `factory_sandbox_exec` (`echo built in the sandbox && ls`) through MCP and report both answers.
* `FACTORY_SANDBOX=fake` (only together with `FACTORY_DEV_FAKE_GITHUB`): sandboxes with an in-memory runtime
  that pretends to run commands (`echo …`, `ls` of the worktree, `seq N`, `sleep N`, `exit N`/`false`; anything
  else prints what would have run), so the sandbox UI works without Podman. The fake GitHub also serves files from
  `$FACTORY_DATA_DIR/fake-github/<repo>/<path>` on the repository's default branch, and runs read their sandbox
  definition from there (the local `git init` workspaces have none). For example:

  ```sh
  mkdir -p "$FACTORY_DATA_DIR/fake-github/demo-app/.agern"
  printf 'image: docker.io/library/eclipse-temurin:21-jdk\nsetup: echo installing\n' > "$FACTORY_DATA_DIR/fake-github/demo-app/.agern/sandbox.yml"
  ```

  then approve it as an admin on `/p/demo-app/sandbox` and start a run with `fake:sandbox` in the issue body.
  The end-to-end suite (`web/e2e/server.mjs`) runs with this mode.

With both, the whole product — chat, runs with live stages, needs-human approval — works locally for
UI development at zero cost.

### Trying sandboxes with a local Podman

Sandboxes (docs/ARCHITECTURE.md §5 "Sandboxes") need rootless Podman for the user running the server;
nothing else (no Docker). The tests do not: they use `FakeSandboxRuntime`. To try the real thing:

```sh
podman info --format '{{.Host.Security.Rootless}}'        # must print true (Linux: subuid/subgid ranges for your user)
# macOS: podman machine init && podman machine start      # the VM must see the data directory: keep
#        FACTORY_DATA_DIR under your home, which podman machine mounts by default
export FACTORY_SANDBOX=podman FACTORY_SANDBOX_MAX_CONCURRENT=1
./gradlew :server:run
```

Then, in a project the server can reach (a real GitHub repository; the fake GitHub's local `git init`
repositories have no definition file):

1. Commit `.agern/sandbox.yml` to the default branch, e.g. `image: docker.io/library/eclipse-temurin:21-jdk`.
2. As an admin, read `GET /api/projects/<repo>/sandbox` (state `pending`, with its `sha256`) and approve it with
   `POST /api/projects/<repo>/sandbox/approval` with `{"sha256":"…"}`, or on the project's sandbox page `/p/<repo>/sandbox`.
3. Start a run. The builder's transcript starts with "Sandbox: …"; its first `factory_sandbox_exec`
   creates `agern-run-<runId>` (watch with `podman ps --filter label=agern.run`), every command's full log
   is a `system` event in the transcript, and the containers and network disappear when the build/review
   loop ends (cache volumes `agern-cache-*` stay).

With the fake CLI, put `fake:sandbox` in the issue body to see the tool calls without spending tokens.
Leftovers of a crashed server are removed at the next start; by hand:
`podman rm -f $(podman ps -aq --filter label=agern.run)`.

## Layout

```
src/main/kotlin/dk/egernet/factory/
  Main.kt, App.kt, Config.kt, Models.kt   startup, wiring (Services), env config, JSON types
  FactoryService.kt                       operations shared by REST, MCP tools and /factory commands
  api/Api.kt                              HTTP routes, auth/CSRF/Origin guard, WebSockets, static frontend
  auth/                                   login providers (os = PAM, ldap, dev), TOTP second factor
  security/                               AES-GCM secret box, TOTP/Base32, recovery codes
  db/                                     JDBC helper and stores; migrations in resources/db/migration
  github/                                 GitHub interface, REST client, in-memory fake, visibility cache
  git/                                    server-side git (clone, worktree, push) with env-only auth
  agent/                                  AgentLauncher (local processes), AgentManager, event bus, prompts
  pipeline/Orchestrator.kt                the run state machine
  mcp/                                    MCP endpoint (JSON-RPC over HTTP) and per-process tokens
  sandbox/                                .agern/sandbox.yml parsing, SandboxRuntime (rootless Podman CLI), per-run SandboxManager
src/main/resources/prompts/               system prompts per role (chat, spec-writer, builder, reviewers, approver)
src/test/resources/fake-claude            the CLI stub described above
```

## Security notes

* Agent processes get an environment built from scratch (`PATH`, `HOME`, `LANG`, `TERM`, the
  owner's Claude credential, git auth via `GIT_CONFIG_*`, `GH_TOKEN`, git author). Nothing else
  from the server environment is inherited.
* Only the owner of a session or run can drive it (input, interrupt, approve, messages to a running
  spec-writer or builder); admins can stop and cancel. Everyone else who can read the repository on GitHub may observe. The MCP endpoint applies
  the same rules as the token's owner.
* `os` login on Linux uses PAM; the server's account must be allowed to check other users'
  passwords (e.g. membership in the `shadow` group for `pam_unix`). On macOS the `chkpasswd`
  service is used.
* Known limitation (v1): all agents run as the server's OS user with permissions bypassed — run
  Agern on a dedicated machine or VM.
* Sandboxes are not a security boundary yet: the agent runs on the host as the user that owns the
  rootless containers. They are never privileged, get no added capabilities and no credentials of the
  run's owner, and only the run's worktree is mounted (at `/workspace`).
