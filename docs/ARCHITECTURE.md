# Agern — Architecture & API contract

Agern ("acorn" in Danish) is a software factory: a self-hosted web system where people put Claude agents to work building
software. A GitHub organization is the source of truth: **repositories are projects** and
**issues are tasks / features / bugs**. Each project has a CLI-like chat with a Claude agent, and
that agent (or a human) can start *pipeline runs* in which a team of agents takes an issue from
spec to merged pull request.

This document is the contract between the backend (`server/`), the frontend (`web/`) and the
artwork (`art/`). Change it first, then the code.

## 1. Repository layout

```
server/              Kotlin + Ktor backend (Gradle, JDK 17)
web/                 React + TypeScript + Vite frontend
art/                 Blender scripts and source renders
docs/                This document
docker-compose.dev.yml   PostgreSQL for local development and tests only
```

**Docker policy.** In production the factory runs directly on its host and uses a natively
installed PostgreSQL; nothing in the factory itself is containerised. Docker's role on the factory
host is as a tool *for the agents*: testing the software they build on different devices and
platforms. `docker-compose.dev.yml` exists only so a developer can get a throwaway database.

## 2. Stack

| Concern | Choice |
|---|---|
| Backend | Kotlin, Ktor 3 (Netty), kotlinx.serialization, coroutines |
| Database | PostgreSQL 16, Flyway migrations, HikariCP, plain JDBC (no ORM) |
| Frontend | React 18, TypeScript, Vite, React Router; no heavy UI kit |
| Agents | The `claude` CLI in headless mode (`-p`, stream-json in and out) |
| Realtime | WebSocket for output, REST for input |

All persistent state lives in PostgreSQL. The only things on disk are git workspaces, the
per-user Claude home directories and the agents' copies of chat attachments (a cache of the database,
§5), all under `FACTORY_DATA_DIR`.

## 3. Configuration (environment variables)

| Variable | Default | Meaning |
|---|---|---|
| `FACTORY_HOST` | `0.0.0.0` | Bind address. When it is one specific address other than `127.0.0.1` (e.g. a LAN address), the server also listens on `127.0.0.1:<port>`, because agents reach MCP over loopback (§9) |
| `FACTORY_PORT` | `8080` | HTTP port |
| `FACTORY_DB_URL` | `jdbc:postgresql://localhost:5432/factory` | JDBC URL (native PostgreSQL; development overrides it to port `54329`, see `docker-compose.dev.yml`) |
| `FACTORY_DB_USER` / `FACTORY_DB_PASSWORD` | `factory` / `factory` | DB credentials |
| `FACTORY_MASTER_KEY` | — (required) | Base64, 32 bytes. Encrypts user secrets (AES-256-GCM) |
| `FACTORY_GITHUB_ORG` | — (required) | The GitHub organization, e.g. `Egernet-CS` |
| `FACTORY_AUTH` | `os` | Comma list of login providers: `os`, `ldap`, `dev` |
| `FACTORY_2FA` | `required` | `required` or `off`; `off` is accepted only when `FACTORY_AUTH=dev` (sole provider), otherwise the server refuses to start |
| `FACTORY_PAM_SERVICE` | `chkpasswd` (macOS) / `login` (other) | PAM service used by the `os` provider |
| `FACTORY_ADMINS` | empty | Comma list of usernames that are admins |
| `FACTORY_LDAP_URL`, `FACTORY_LDAP_USER_DN_TEMPLATE`, `FACTORY_LDAP_BASE_DN`, `FACTORY_LDAP_USER_FILTER`, `FACTORY_LDAP_BIND_DN`, `FACTORY_LDAP_BIND_PASSWORD` | — | LDAP. Either a DN template (`uid={0},ou=people,dc=x`) or search-then-bind |
| `FACTORY_DEV_USERS` | — | `alice:pw,bob:pw`. Only read when `dev` is in `FACTORY_AUTH`. Never for production |
| `FACTORY_DATA_DIR` | `./data` | Workspaces and per-user homes |
| `FACTORY_WEB_DIR` | unset | Built frontend directory, served with SPA fallback. Unset: the frontend embedded in `agern.jar` (classpath `web/`) is served; with neither, non-API paths answer 404 |
| `FACTORY_CLAUDE_BIN` | `claude` | Path of the Claude CLI (tests point this at a stub) |
| `FACTORY_PUBLIC_URL` | `http://localhost:8080` | The URL browsers use. Cookie is `Secure` only when it is `https`; mutating requests and WebSocket upgrades with a different `Origin` are rejected (403). Agents reach MCP over loopback (`http://127.0.0.1:<port>/mcp`), not through this URL |
| `FACTORY_ALLOWED_ORIGINS` | empty | Extra allowed browser origins, comma list (e.g. `http://localhost:5173` for the Vite dev server) |
| `FACTORY_MAX_AGENTS_PER_USER` | `8` | Concurrent agent processes per user |
| `FACTORY_CHAT_IDLE_MINUTES` | `30` | Idle chat processes are stopped and resumed on next input |
| `FACTORY_STAGE_TIMEOUT_MINUTES` | `60` | Wall-clock limit for one pipeline stage agent (1–1440). On timeout the agent is stopped, the stage is `failed` and the run `failed` with an error that says so |
| `FACTORY_AGENT_PATH` | the server's `PATH` | `PATH` value handed to agent and git processes (the only value taken from the server environment) |
| `FACTORY_GITHUB_API_URL` | `https://api.github.com` | GitHub REST base URL (GitHub Enterprise: `https://host/api/v3`) |
| `FACTORY_ATTACHMENT_MAX_MB` | `20` | Largest single chat attachment (1–512) |
| `FACTORY_ATTACHMENT_MAX_MESSAGE_MB` | `50` | All attachments of one message together (1–512, at least the per-file limit) |
| `FACTORY_ATTACHMENT_MAX_COUNT` | `10` | Attachments per message (1–20) |
| `FACTORY_DEV_FAKE_GITHUB` | `false` | Development only, requires `FACTORY_AUTH=dev`: in-memory fake GitHub (org with `demo-app` and `private-lab`, tokens `fake-<login>` = classic with `repo`, `read:org`; `fake-<login>~<scope>,…` = only those scopes), local `git init` instead of clones, pushes skipped |

## 4. Identity and secrets

**Login.** Username + password checked by the configured providers in order.
- *Rate limit.* Failed passwords count per username (case-insensitive) and per client address
  within a 15-minute window. Five failures for a username, or twenty from one address, lock that
  key: login is then refused with 429 `too_many_attempts` (and `lockedUntil`) before any password is
  checked. A lock lasts 1 minute and doubles on each repeat, capped at 15 minutes, so an attacker can
  delay a user but never lock them out for good. Each failed attempt is answered after a short,
  growing delay. A correct password clears the username's counter (not the address's). Failures
  (`login_failed`) and locks (`login_locked`) are written to the audit table. The counters are kept
  in memory (a restart forgets them); behind a reverse proxy all clients share the proxy's address.
- `os`: a user account on the machine the factory runs on (PAM on Linux; on macOS PAM or
  `dscl . -authonly`). The password must never appear in a process argument list.
- `ldap`: bind against the directory.
- `dev`: static users from `FACTORY_DEV_USERS`, for local development and tests. The server logs a
  loud warning at startup when it is enabled.

**Two-factor authentication.** Mandatory for every user, from the first login. The second factor
is TOTP (RFC 6238, SHA-1, 6 digits, 30 s, ±1 step) from an authenticator app — it works over plain
HTTP on a LAN, which WebAuthn/passkeys do not (they can be added once Agern is served over HTTPS).
- After a correct password the session is only *pending*: it can call the two TOTP endpoints and
  nothing else (everything else, including WebSockets, answers 401).
- A user without a confirmed TOTP secret must enrol before getting in: the server generates a
  secret, the UI shows it as a QR code (rendered in the browser from the `otpauth://` URI) and as
  text, and the user confirms with a code. Ten single-use recovery codes are then shown once.
- The TOTP secret is encrypted like other secrets; recovery codes are stored hashed. A code cannot
  be reused within its time step. Five failed second-factor attempts lock the account's second
  factor for 15 minutes. Failures and resets are written to an audit table.
- An admin can reset a user's 2FA (the user enrols again at next login); admins cannot read secrets.
- `FACTORY_2FA` is `required` by default. `off` exists for automated tests and is accepted only
  when `dev` is the sole login provider; otherwise the server refuses to start.

Sessions are server-side rows in PostgreSQL referenced by an opaque random id in an `HttpOnly`,
`SameSite=Lax` cookie named `sf_session`. Every mutating request must carry the header
`X-Factory-Request: 1` (CSRF guard); the server rejects it otherwise with 403.

**Per-user credentials.** Each user registers their own:
- *Claude credential* — either a long-lived OAuth token from `claude setup-token`
  (`kind = oauth_token`, passed to agents as `CLAUDE_CODE_OAUTH_TOKEN`) or an Anthropic API key
  (`kind = api_key`, passed as `ANTHROPIC_API_KEY`).
- *GitHub token* — a personal access token. The server calls `GET /user` to learn the login.

Both are stored encrypted with AES-256-GCM under `FACTORY_MASTER_KEY` (random 12-byte nonce per
value, username as associated data). They are never returned by any API, never logged, and never
written to disk in plaintext.

**The ownership rule.** An agent process always runs with the credentials of the user who started
it — the *owner*. There is no API through which user A can cause a process to start with user B's
credential: only the owner may send input to, resume, or approve work in their sessions and runs
(admins may stop, never drive). Everyone else gets read-only observation.

**Visibility.** A user may see a project, its issues, runs, sessions and transcripts if and only if
their own GitHub token can read that repository (`GET /repos/{org}/{repo}` → 200; cached 60 s per
user+repo). All GitHub calls made for a user use that user's token, so GitHub itself enforces
access. A user without a GitHub token sees no projects.

## 5. Agent runtime

An agent is one `claude` child process. `AgentLauncher` is an interface so that the local process
launcher can later be replaced by a container- or `sudo -u`-based one.

Launch rules:
- The environment is built **from scratch** (never inherited): `PATH`, `HOME`, `LANG`, `TERM`,
  the owner's Claude credential variable, and git auth. `HOME` is
  `$FACTORY_DATA_DIR/homes/<username>` so Claude session files are per user.
- Git over HTTPS authenticates with the owner's GitHub token through environment-only config
  (`GIT_CONFIG_COUNT` / `GIT_CONFIG_KEY_n` / `GIT_CONFIG_VALUE_n` with an `http.extraheader`, or
  `GIT_ASKPASS`). The token is not written into `.git/config` or the remote URL. `GH_TOKEN` is set
  too so `gh` works.
- Command line: `claude -p --output-format stream-json --verbose --model <model>
  --permission-mode bypassPermissions --strict-mcp-config --mcp-config <file> …`. The MCP config
  (which contains the per-process bearer token) is written to a `0600` file under the owner's
  `HOME/.agern/` and deleted when the process exits, so the token is not in the argument list.
  The role prompt is passed with `--append-system-prompt`. Reviewers and the approver read their task
  prompt from stdin (text input, stdin closed afterwards) and add `--json-schema` and
  `--disallowedTools Edit,Write,NotebookEdit` (the spec-writer is read-only too); the verdict is read from
  the `result` event's `structured_output`. The spec-writer and the builder (fix rounds included) are
  *steerable*: they add `--input-format stream-json --replay-user-messages`, get their task prompt as the
  first stream-json user message and keep stdin open while they work, so their owner can send messages
  (§6 "Messages to running agents").
- Chat input is written to stdin as `{"type":"user","message":{"role":"user","content":<text>}}`.
  A message with attachments has an array as `content` instead: one `image` block
  (`{"type":"image","source":{"type":"base64","media_type":…,"data":…}}`) per image that goes inline,
  then the user's text (if any) as a `text` block, then a `text` block starting `[Attachments]` that lists
  every attachment with its name, type, size, pixel size and its `path:` on disk. Every attachment, images
  included, is written to `$FACTORY_DATA_DIR/attachments/<sessionId>/<attachmentId>-<safe-name>` — outside
  the git working tree, so it never lands in a commit unless the agent is asked to copy it in. These files
  are a cache: the bytes live in PostgreSQL and are written again when missing (on send, and for all of a
  chat's attachments when its process starts or resumes).
  *Inline images:* png/jpeg/gif/webp up to 2000 px on the longest edge and 3.75 MB (5 MB of base64, the
  strictest platform limit) go as they are. The API rejects images over 8000 px, and over 2000 px once a
  request holds more than 20 images — and the CLI resends every earlier image with each request — so larger
  ones are downscaled by the server to 2000 px (JPEG, or PNG when transparent) for the inline copy; the
  original stays available as the file. Images that cannot be resized safely (WebP, more than 50 megapixels,
  undecodable) are passed as files only. Either way the attachment's `note` says so and the transcript shows it.
  Verified against Claude Code 2.1.326: an `image` block in a stream-json user message is accepted and seen.
  Interrupt sends `{"type":"control_request","request_id":…,"request":{"subtype":"interrupt"}}`;
  the CLI ends the turn with a `result` of subtype `error_during_execution`. `total_cost_usd` in
  `result` is cumulative per process, `num_turns` is per turn.
  Chat sessions add `--input-format stream-json --include-partial-messages` and stay alive between
  turns. A later launch of the same conversation uses `--resume <claudeSessionId>`. If that launch
  dies before its `init` event (e.g. "No conversation found with session ID" after the CLI, the
  user's home or the CLI version changed), the stored id is cleared, a `system` warn event says the
  previous conversation could not be resumed, and the CLI is launched once more without `--resume`
  with the unanswered input — no retry loop. A rejected Claude credential ("Invalid API key", expired
  OAuth token, …) is reported as a `system` error telling the user to update it in Settings, for
  chats and pipeline stages alike. A process the server stops itself (stop, idle, shutdown) is
  reported as a neutral notice, never as an error exit code.
- Each stdout line is one JSON object. Complete messages are persisted as events; partial
  `stream_event` deltas are broadcast live but not persisted.

Workspaces: `$FACTORY_DATA_DIR/workspaces/<username>/<repo>/main` is the owner's clone used by
chat. A pipeline run works in a git worktree `…/<repo>/runs/<runId>` on branch
`factory/issue-<number>-<slug>`; a release run (§6 "Releases") on `factory/release-<name>/issue-<number>-<slug>`,
so it never reuses the open pull request of an earlier single run of the same issue.

**Known limitation (v1).** All agents run under the server's OS user with permissions bypassed.
The factory must run on a dedicated machine or VM. Per-user OS accounts or containers are the
planned hardening and are why `AgentLauncher` is an interface.

**Software installation.** Global setting `agentsMayInstallSoftware` (default `false`). When
false, agents are instructed not to install system-wide software and are given the MCP tool
`factory_request_software`, which files a request an admin handles manually. When true the
instruction is dropped.

## 6. The pipeline

```
issue → spec-writer (opus) → builder (opus) ─┬→ security  (opus)   ┐
                              ▲              ├→ ux        (sonnet) │ wait for all 4
                              │              ├→ ui-design (sonnet) │
                              │              └→ code      (sonnet) ┘
                              └── any CHANGES: resume builder (max 2 rounds)
                                             all PASS → approver (opus)
                                                          ├ APPROVE  → merge to main
                                                          └ ESCALATE → needs-human ← /factory approve
```

"main" is the repository's default branch for a single run, and `release/<name>` for a release run (§6
"Releases"): the run's worktree starts from that branch and its pull request merges into it.

- **spec-writer** reads the issue and the repo and writes a spec; it is posted as an issue comment.
- **builder** implements the spec on the run branch and commits. The orchestrator pushes the branch
  and opens a pull request after the first build.
- **reviewers** run in parallel, cannot edit files (`--disallowedTools Edit,Write,NotebookEdit`),
  and answer through `--json-schema` with
  `{ "verdict": "PASS" | "CHANGES", "summary": string, "findings": [{ "severity", "file", "detail" }] }`.
- If any reviewer says `CHANGES`, the **same** builder conversation is resumed with all findings:
  each fix round is a **new** builder `AgentSession` (own transcript, `parentSessionId` = the previous
  builder session) whose CLI is launched with `--resume <claudeSessionId of the previous builder>`.
  At most `maxFixRounds` (default 2) fix rounds; if reviewers still want changes after the last one,
  the approver is `skipped` and the run goes to `needs_human`.
- **Rounds** are numbered by fix rounds: `Run.round` and `Stage.round` are `0` for the initial
  build/review, `1..maxRounds` for fix rounds. The UI shows "build" for round 0 and "fix round N of
  max" after.
- **Time limit.** Every stage agent is stopped after `FACTORY_STAGE_TIMEOUT_MINUTES` (default 60) of
  wall-clock time (waiting for a free agent slot does not count). The stage becomes `failed` with
  summary "Timed out: …", and the run `failed` with error "<role> failed: Timed out: …".
- **approver** answers `{ "verdict": "APPROVE" | "ESCALATE", "summary": string }`. `APPROVE`
  squash-merges the PR and closes the issue. `ESCALATE` labels the issue `needs-human`, comments,
  and waits for the owner's `/factory approve`.
- **Whose models.** Every agent runs on its owner's Claude account, so model choices are personal. The
  global settings (`chatModel`, `pipelineModels`, `maxFixRounds`) are the admin's *defaults* — the models
  shown above unless an admin changes them. Each user may override any of them for themselves
  (`/api/me/preferences`); each value is optional. Effective value for an agent = its **owner's** preference
  if set, else the admin default. A run uses the preferences of the user who starts it: its `maxRounds` is
  resolved once when the run is created and never changes afterwards (also not when the preference is edited
  mid-run); each stage's `model` is resolved when the stage is created (round-0 stages and the approver when
  the run is created, fix-round stages when their round starts), and the stage's agent runs with exactly that
  model, so `Stage.model` is always what ran (or will run). A new chat uses its owner's `chatModel` unless
  the create call names a model. One user's preferences never affect another user's agents.
- **Fix rounds** per run: the admin `maxFixRounds` is the default **and** the upper bound; a user may choose
  `0..maxFixRounds` (422 `max_fix_rounds_out_of_range` otherwise). If an admin later lowers the bound below a
  stored preference, the effective value is clamped to it (and reported as `clamped`). `0` means no fix rounds:
  any `CHANGES` after the first review sends the run straight to `needs_human` (approver `skipped`).
- **Messages to running agents.** The run's owner — directly on the run page, or through their own chat
  agent (`factory_send_to_agent`, §7) — can send a text message to a *running* spec-writer or builder
  (fix rounds included). Nobody else can, admins included (they may stop, never drive). Reviewers and the
  approver never accept messages (409 `agent_does_not_accept_messages`): they answer in a fixed verdict
  format that an outside message must not bias. A message to an agent that is not running — not started
  yet, finished, failed or stopped — is rejected with 409 `agent_not_running` and a hint to start a new run;
  nothing is dropped silently. Messages are not posted on GitHub.
  - *Delivery.* The message is written to the agent's stdin at once as
    `{"type":"user","message":{"role":"user","content":"Message from the run owner (<username>) while you work — take it into account:\n\n<text>"}}`
    and the API answers `queued`. Verified against Claude Code 2.1.328 (`-p --input-format stream-json
    --output-format stream-json --verbose`, haiku, a task of three sequential `sleep 4` Bash calls): a message
    written while a tool call ran was picked up right after that tool result, inside the same turn — the
    model's next step already followed it, and the turn ended with one `result`. A message written while the
    model was writing its final answer (no further tool call) was not folded in: the turn finished, and the
    message then ran as a further turn with its own `result`. So a message is delivered at the agent's
    **next step**: after its current tool call, or as a further turn if it was finishing. With
    `--replay-user-messages` the CLI echoes each stdin message (`"isReplay": true`) at the moment it picks it
    up (also the task prompt, and with `--resume` only the new messages); the server uses the echo as the
    delivery receipt — it appends a `system` info event carrying the message's `messageId` — and does not
    persist the echo itself.
  - *End of the stage.* A `result` ends the stage only when no written message is still waiting for its echo:
    then the server closes stdin and the stage finishes as before (the agent exits by itself; it is killed
    30 s later if not). Otherwise the CLI runs a further turn for the waiting message(s), and the *last*
    `result` is the stage's result. Writes to stdin, the "waiting" bookkeeping and closing stdin happen under
    one lock per agent, so a message the API accepted was written before stdin closed and will be answered,
    or the caller got the rejection. If the agent stops before picking up an accepted message (crash, time
    limit, cancel), a `system` warn event with that `messageId` says it was not delivered. (A CLI that never
    echoes falls back to closing stdin at the first `result`.)
  - The stage time limit is unchanged and not extended by messages; cancel works as before.
- Orchestration is a deterministic state machine in the server, not something an LLM decides. On
  server restart, unfinished runs are marked `failed` with a clear reason (v1); a building release then builds
  the interrupted issue again (§6 "Releases").

Run `status`: `queued`, `spec`, `building`, `reviewing`, `approving`, `merged`, `needs_human`,
`failed`, `cancelled`.

Stage `role`: `spec-writer`, `builder`, `security`, `ux`, `ui-design`, `code`, `approver`.
Stage `status`: `pending`, `running`, `done`, `failed`, `skipped`.
Stage `verdict`: `PASS`, `CHANGES`, `APPROVE`, `ESCALATE` or null.

### Releases

A **release** is a named plan of issues for one project, in the order they will be built. Once **started**, Agern
builds it unattended on the branch `release/<name>`: one issue at a time, each run branching from and squash-merging
into the release branch, without closing its issue. Delivered in steps (issue #13): planning (#17), starting and
building (#19), scheduled starts (#21, "Scheduled start" below). The release pull request, approval and merge
into the default branch (#20) come later. Single runs are unaffected and still merge into the default branch.

- **Model.** Tables `releases` (`id`, `repo`, `name`, `description`, `status`, `created_by`, timestamps; once
  started also `owner_id`, `branch`, `started_at`, `ended_at`, and while paused `pause_reason`, `pause_note`,
  `resume_at`; for a scheduled start `owner_id` (set when scheduled), `scheduled_at`, `waiting_for` → `releases`
  (`ON DELETE SET NULL`), `start_failed_at`, `start_failed_owner`, `start_failed_reason`, with the partial index
  `releases_due` on `scheduled_at` for `status = 'scheduled'`; migrations V7 and V8) and `release_issues` (`release_id`, `repo`, `issue_number`, `position`, `issue_title`, `issue_kind`,
  `added_by`, `added_at`, `state`, `run_id` = the issue's latest release run, `note`). `runs` carry `release_id`
  (`ON DELETE SET NULL`) and `base_branch` (null = the default branch). Several releases may exist per project.
  Creating one creates no branch, no run and makes no GitHub write.
- **States.** Release `status`: `planning` (shown as "in preparation"), `scheduled`, `building`, `paused`,
  `cancelled`; later steps add `ready` and `released`. *Planned* means `planning` or `scheduled` (a scheduled release
  is a plan with a start time: the planning rules apply). *Started* means `building`, `paused` or `cancelled`. Issue
  `state` within a release: `waiting`, `running`, `merged`, `held`, `skipped`; all issues of a planned release are
  `waiting`. A `building` release with no `waiting` or `running` issue is *idle*: it stays `building` (and keeps the
  project's slot until it is cancelled) and continues by itself when an issue is added or retried. **At most one
  release per project is `building` or `paused`**, enforced by the partial unique index `releases_one_building`.
- **Names** are trimmed, match `^[A-Za-z0-9][A-Za-z0-9._-]{0,49}$`, contain no `..` and do not end with `.` or
  `.lock`, so they are safe as the git branch `release/<name>` and in URLs (400 otherwise). They are unique per
  project, case-insensitively, across all releases including future history (409 `release_name_taken`); renaming
  to another casing of the release's own name is allowed. A started release cannot be renamed (409
  `release_name_locked`). Descriptions are optional, at most 2000 characters. The branch name is only ever built
  from the validated name.
- **Issues.** Only open issues can be assigned (checked with the caller's GitHub token; pull requests are not
  issues). An issue is in **at most one** release per project, enforced by the primary key `(repo, issue_number)`;
  assigning an issue that is in another release moves it. New and moved issues go last, in the order given, as
  `waiting`; assigning an issue already in the target release changes nothing. The title and kind are stored at
  assignment time (like `runs.issue_title`) and not refreshed. An issue closed on GitHub afterwards stays until
  removed. Assignments and reorders run in one transaction under a per-repo advisory lock (`releases:<repo>`),
  where the rules of started releases below are checked for the target and every source; an assignment is
  all-or-nothing. When an issue leaves a release while its linked run is still active (`needs_human`), that run is
  cancelled.
- **Order.** Positions are explicit; a reorder must name exactly the release's current issues (else 409
  `release_issues_changed`) and rewrites them densely. In a `building` or `paused` release every issue that is not
  `waiting` must keep its index (409 `release_issue_started`); a cancelled release cannot be reordered (409
  `release_closed`).
- **Access.** Everyone who can see the project sees its releases; a release of a repository the caller cannot see
  answers 404, never 403, checked before any other rule. Who may change a release:

  | Release status | Action | Allowed for |
  |---|---|---|
  | `planning` | everything, including start and scheduling a start | anyone who can see the project |
  | `scheduled` | edit, assign, reorder (as `planning`) | anyone who can see the project |
  | `scheduled` | change or cancel the schedule | owner (who scheduled it) or admin |
  | started | pause, cancel, take an issue out, edit the description | owner or admin |
  | started | resume, add issues, reorder, retry an issue | owner only |
  | `cancelled` | delete | owner or admin |

  The owner is the user who started the release. Admins are refused the owner-only actions because those make
  agents run on the owner's credentials (§4: admins may stop, never drive). Anyone else gets 403 `forbidden` "Only
  <owner>, who started this release, can do this" ("…, or an admin can do this" where admins are allowed).
- **Starting** (`POST /api/releases/{id}/start`, "start now"). Checked in this order: visible (404); `planning` (409
  `release_not_planning`; a `scheduled` release: "cancel the scheduled start to start it now"); the caller has a Claude credential (422 `claude_credential_required`) and a GitHub token
  (403, as for every GitHub action); at least one issue (409 `release_empty`); no other release of the project is
  `building` or `paused` (409 `release_already_building`, naming it). Then the release is claimed with an update
  guarded by `status = 'planning'` (`building`, owner, `branch = release/<name>`, `started_at`; a violation of the
  one-building index is 409 `release_already_building`), and the branch is created on GitHub from the current tip of
  the default branch with the caller's token. An existing branch is 409 `release_branch_exists` ("Branch
  release/<name> already exists on GitHub; delete it or rename the release."); GitHub refusing (401/403/404) is 422
  `github_push_denied`. On any failure the claim is rolled back to `planning` with no owner, branch or start time,
  and nothing else changes. Then every issue is skip-checked and the first run starts. All later GitHub calls use
  the owner's token.
- **Scheduled start.**
  - *Owner.* The user who schedules a release becomes its `owner`; the start and the whole build use the owner's
    Claude credential and GitHub token (the owner of the started release is that user). An admin who changes the time does not become the owner. Cancelling clears the owner: the
    release is a shared plan again.
  - *Set* (`PUT /api/releases/{id}/schedule`, `planning` → `scheduled`): the time must be in the future and at
    most 366 days ahead (stored at millisecond precision); the caller needs a Claude credential (422
    `claude_credential_required`) and a GitHub token (403). Clears an earlier start failure. Creates no branch,
    no run and makes no GitHub write; a release without issues may be scheduled. *Change* (same route while
    `scheduled`): owner or admin. *Cancel* (`DELETE …/schedule`): owner or admin, back to `planning`; the start
    failure, if any, is kept. Any other state: 409 (`release_not_schedulable` / `release_not_scheduled`).
  - *Due* means `scheduled` and `scheduled_at <= now`. The scheduler (`ReleaseScheduler`) ticks at server start
    and then every 15 seconds; each tick handles the due releases oldest first (`scheduled_at`, `created_at`,
    `id`), re-reading each. One failing release does not stop the others. Set, change, cancel and the handling
    of each due release are serialised by one mutex: a cancel that arrives during a start waits, then answers 409
    `release_not_scheduled`. All time comparisons use an injectable `java.time.Clock` (`Services.clock`, the
    system UTC clock by default); start precision is about 15 seconds.
  - *Waiting.* While another release of the same project is `building` or `paused`, a due release stays
    `scheduled` with `waiting_for` naming that release (a feed frame only when the value changes). The first
    tick after the project is free continues; of two due releases in one project the earlier starts and the
    later waits behind it. Releases of other projects are not held back.
  - *Credential check*, as the owner, afresh at due time: the owner's account, a Claude credential, a GitHub token
    and a fresh `getRepo` of the project with that token (not the 60-second visibility cache). A missing
    credential or a token GitHub rejects (401/403/404, or the repository not readable) is a **start failure**
    with a fixed reason naming the user and what to do. Any other GitHub error (5xx, I/O) is not: the release
    stays scheduled and due, a warning is logged, and the next tick retries.
  - *Start.* The scheduler calls `ReleaseStarter`, bound to `ReleaseService.startScheduled`: **the same start as
    "start now"** (above), as the owner, except that the release must be `scheduled` and the claim is guarded by
    `status = 'scheduled'` (it clears `scheduled_at` and `waiting_for`; the owner stays). Every refusal of that start
    (`release_empty`, `release_branch_exists`, `github_push_denied`, …) is a start failure with its message; if the
    branch cannot be created the claim rolls back to `scheduled` first, so the failure is recorded. 409
    `release_already_building` (another release took the slot by "start now" in the meantime) is not a failure:
    the release stays `scheduled` and waits behind it. Still `scheduled` after the call is a start failure ("The
    release did not start."); any other exception is logged and one with "Internal error while starting the
    release; see the server log." Tests may substitute the starter.
  - *Start failure*: back to `planning` (owner, time and waiting cleared) with `start_failed_at/_owner/_reason`
    set and a feed frame; the owner sees it on the release page, the project page and in `/factory releases`.
    There is no automatic retry; the failure stays until the release is scheduled again. Reasons are fixed
    texts or `ApiException` messages, never tokens or raw exception text.
  - *Restart.* All state is in the database. A time that passed while the server was down is handled at the
    first tick, not skipped; `waiting_for` is recomputed by ticks.
- **Building.** Issues are built one at a time, lowest position first among `waiting` issues; no second run of a
  release starts while one of its issues is `running`. The builder (`ReleaseBuilder`) is event-driven — start, a
  run settling, resume, retry, adding an issue, restart and the resume ticker each call `advance`, which runs under
  a per-release mutex, re-reads the database and starts the next run only when the release is `building` and
  nothing runs. *Skip check*, at start and again at each issue's turn: an issue closed or missing on GitHub becomes
  `skipped` ("Closed on GitHub"); one with an active run outside the release becomes `skipped` ("Has an active run
  outside the release"). A skipped issue never blocks the build and can be retried. If a run cannot be started for
  another reason, the issue is `held` with the message and the build goes on.
- **Release runs** are created as the release owner, with the owner's preferences as for single runs, with
  `releaseId` and `baseBranch = release/<name>`, and go through the unchanged pipeline except: the branch is
  `factory/release-<name>/issue-<n>-<slug>`; the worktree is created from the current tip of the release branch
  (fetched first), so it builds on the issues merged before it; the pull request targets the release branch and its
  body starts with "Issue #n · release `<name>`" with no closing keyword; build, review and approver prompts name
  the release branch as the merge target; on merge the issue is **not** closed and the comment reads "**Agern:
  merged** pull request #p into `release/<name>` (approved by …). The issue stays open until the release is merged
  into `<default branch>`." Release runs use the same per-user agent slots as all pipeline agents.
- **Outcomes.** A run that ends `merged` makes its issue `merged`; `needs_human`, `failed` or `cancelled` (also
  through the run's own cancel) makes it `held` with the run's error as note (or "Run failed"). Then the next issue
  starts. The orchestrator reports every settled release run (end of its pipeline, approve, cancel — not a
  cancelled coroutine) through a callback that the builder dispatches asynchronously; settling is idempotent and
  ignores runs no longer linked to an issue.
- **Held issues.** The owner can approve the held run (`POST /api/runs/{id}/approve`, `/factory approve`): when it
  merges the issue becomes `merged`, if the merge fails it stays `held` with the new reason (a branch that is behind
  is merged by GitHub's squash merge; a conflict fails). The owner can retry it, or anyone allowed can take it out.
- **Retry** (`POST /api/releases/{id}/issues/{number}/retry`, owner only): the release must be `building` or
  `paused` (409 `release_not_building`), the issue `held` or `skipped` (409 `release_issue_not_held`). It becomes
  `waiting` at its position with note and run link cleared; a linked run that is still active (`needs_human`) is
  cancelled; the build continues.
- **Pause** (owner or admin): `building` → `paused`, reason `manual`, note "Paused by <username>" (else 409
  `release_not_building`). The running run is not stopped: it finishes, its outcome is recorded, and no further
  issue starts. **Resume** (owner only, needs a Claude credential): `paused` → `building` (else 409
  `release_not_paused`), clearing the pause, and continues.
- **Cancel** (owner or admin): `building` or `paused` → `cancelled` with `ended_at` (else 409
  `release_not_building`). The `running` issue becomes `held` ("Cancelled with the release") before every active run
  of the release (running or `needs_human`) is cancelled through the orchestrator; `waiting` issues stay `waiting`.
  The release branch and what was merged into it are left alone; nothing starts afterwards. A cancelled release is
  final: it cannot be restarted and its name stays taken until it is deleted.
- **Usage limit.** The stage agent recognises the owner's Claude usage limit in the CLI stream: a
  `rate_limit_event` whose `rate_limit_info.status` is `rejected`, an `assistant` line with `error: "rate_limit"`,
  or a failing `result` whose text matches `usage limit|session limit|weekly limit|hit your limit`; the reset time
  comes from any `rate_limit_info.resetsAt` (epoch seconds, epoch milliseconds or ISO-8601; anything else is
  unknown). These names were read from the Claude Code 2.1.328 binary, not observed in a live limit, so parsing is
  tolerant. When a release run fails that way, the run is `failed` as usual but its issue goes back to `waiting`
  ("Interrupted by the Claude usage limit; it is built again"), and the release becomes `paused` with reason
  `usage_limit`, a note naming the limit and the reset time when known, and `resumeAt` = reset time + 1 minute (now
  + 30 minutes when unknown or past). A ticker (every 30 s) resumes releases whose `resumeAt` has passed; if the
  limit is still reached it pauses again the same way. The owner may resume by hand.
- **Rejected or missing Claude credential.** When the owner's credential is rejected (the existing detection) or
  missing when a run should start, the issue goes back to `waiting` and the release is `paused` with reason
  `credentials`, note "Claude rejected the owner's credential; update it in Settings and resume." and no
  `resumeAt`. (An owner without a GitHub token pauses it the same way, with a note saying so.) If the release is
  already `paused` or `cancelled` when such a failure arrives, only the issue changes.
- **Restart.** Interrupted runs are failed as before (§6). Then each `running` release issue is settled from its
  run: `merged` → `merged`, `needs_human` → `held`, anything else → `waiting` ("Interrupted by a server restart; it is
  built again"); every `building` release continues, paused releases stay paused, and a due `resumeAt` is honoured.
  A run that failed in the instant before a restart, before its outcome was recorded, is rebuilt rather than held.
- **Deleting** a release in preparation removes the plan and its assignments only; the issues stay open on GitHub.
  A `scheduled` release cannot be deleted (409 `release_scheduled`, "Cancel the scheduled start before deleting
  this release"). A `building` or `paused` release cannot be deleted (409 `release_active`, "Cancel the release first"); a cancelled
  one can (owner or admin): the plan and its assignments go, the branch stays on GitHub and its runs keep existing
  with `releaseId` null.
- **Live.** Every change, including every state change of a release or of one of its issues, emits a `release`
  feed frame per affected release (a move: source and target; also set, change and cancel of a schedule, waiting
  set or cleared, and a start failure), deletion `release_deleted` (§8). `updatedAt`
  strictly increases on every change at millisecond resolution.
- **Fake-GitHub mode.** The local clone has no remote: the release branch is created locally from the clone's
  `HEAD` when first needed, and merges are not applied to it, so each run starts from the same local release
  branch tip. Real mode fetches the merged tip from GitHub.

## 7. Chat agent ↔ factory (MCP)

The server exposes an MCP endpoint (`POST /mcp`, streamable HTTP, JSON responses). Every agent
process gets its own bearer token in its `--mcp-config`; the token maps to the session and thereby
to the owner and the repo, and dies with the process.

Tools: `factory_list_issues`, `factory_get_issue`, `factory_create_issue`, `factory_start_run`,
`factory_list_runs`, `factory_get_run`, `factory_approve_run`, `factory_cancel_run`,
`factory_send_to_agent`, `factory_request_software`, `factory_list_releases`, `factory_create_release`,
`factory_assign_issues`, `factory_unassign_issues`. Pipeline-stage agents get only `factory_get_issue`
and `factory_request_software`.

Release tools (§6 "Releases"), chat agents only; the three mutating ones also refuse run-bound tokens:
`factory_list_releases {}` (the `/factory releases` text, with release ids), `factory_create_release
{ name, description? }`, `factory_assign_issues { release, issueNumbers }` (`release`: a name — case-insensitive —
or id of a release of this project; `issueNumbers`: an array of integers, `"#5"` accepted; assigns or moves) and
`factory_unassign_issues { issueNumbers }`. `factory_list_issues` appends ` [release <name>]` to assigned issues.

`factory_send_to_agent` `{ runId?, role?, sessionId?, message }` passes the person's message to a running
spec-writer or builder of a run in this project (§6 "Messages to running agents"): by `runId` + `role`
(`spec-writer` | `builder`; without `role`, the run's running one of the two) or by the agent's `sessionId`.
The chat's owner must own the run; the same checks and refusals as the HTTP endpoint apply and come back as
tool errors. The transcript entry records `origin: 'chat'` and the chat's session id. The answer says the
message is queued for the agent's next step, never that it was already applied.

Chat input starting with `/factory` is handled by the server and not sent to Claude (with attachments it
is rejected with 422 `attachments_not_allowed`; they stay unsent so the owner can send them in a normal message):
`/factory run <issue>`, `/factory status`, `/factory approve [runId]`, `/factory cancel <runId>`,
`/factory releases`, `/factory release create <name> [description…]`, `/factory release add <name> <issue>…`,
`/factory release remove <issue>…`, `/factory help`. The reply is appended to the transcript as a `system` event.
Release commands act in the chat's project as its owner: `releases` lists each release with its status ("in
preparation" with the issue count, plus " (scheduled start failed: <reason>)" after a failed scheduled start;
"scheduled for <ISO time, UTC> by <owner>" plus ", waiting for release "<name>"" when due and waiting; for started ones "building", "paused" or "cancelled", "k of n merged", the owner
and the branch, and a paused release's note) and the issues in order (`#n title`, with ` — <state>` when not
`waiting`, plus the note for held and skipped issues); starting, scheduling, pausing, resuming, cancelling and retrying are
web-only in this step; `add` assigns or moves and says what happened (e.g. `Release "1.4": added
#5, #6 (now 4 issues).`, plus issues moved from another release); `remove` takes issues out of whatever release
they are in. The state is `in preparation` (plus ` (scheduled start failed: <reason>)` after a failed scheduled
start), `scheduled for <ISO time, UTC> by <username>` (plus `, waiting for release "<name>"`), or the raw status
(`building`, `paused`). Scheduling has no command or tool. Issue tokens accept `5`, `#5` and `#5,`; an unknown release name gets a reply naming the existing
ones; a missing or invalid argument gets a one-line usage reply. Renaming, deleting and reordering are web-only.

## 8. HTTP API

JSON, camelCase, ISO-8601 UTC timestamps, ids are UUID strings unless noted. Errors:
`{ "error": { "code": string, "message": string } }` with 400/401/403/404/409/422/500.
`{repo}` is the bare repository name inside the configured organization.

### Types

```ts
type User = { id: string; username: string; displayName: string; isAdmin: boolean;
  githubLogin: string | null; hasGithubToken: boolean;
  hasClaudeCredential: boolean; claudeCredentialKind: 'oauth_token' | 'api_key' | null;
  twoFactorEnabled: boolean;
  twoFactorLockedUntil: string | null;    // set while the second factor is locked (too many wrong codes)
  githubTokenKind: 'classic' | 'fine_grained' | null;  // from the prefix: ghp_ → classic, github_pat_ → fine_grained
  githubTokenScopes: string[] | null };   // classic tokens: GitHub's X-OAuth-Scopes at registration; null otherwise

type LoginStep =
  | { next: 'totp' }                                             // enter a code
  | { next: 'enroll'; secret: string; otpauthUri: string }       // first login: set up the authenticator
  | { next: 'done'; user: User; recoveryCodes?: string[] };      // recoveryCodes only right after enrolment

type Project = { name: string; fullName: string; description: string | null; private: boolean;
  defaultBranch: string; language: string | null; openIssues: number; pushedAt: string | null;
  htmlUrl: string; activeAgents: number; activeRuns: number };

type IssueKind = 'feature' | 'bug' | 'task';      // from labels: bug → bug; enhancement/feature → feature; else task
type Issue = { number: number; title: string; state: 'open' | 'closed'; kind: IssueKind;
  labels: { name: string; color: string }[]; author: string; assignees: string[]; comments: number;
  createdAt: string; updatedAt: string; htmlUrl: string; body?: string; activeRunId: string | null;
  release: { id: string; name: string } | null };  // the release it is planned in (§6 "Releases"); always present

type ReleaseIssue = { number: number; title: string; kind: IssueKind;   // title/kind as when it was assigned
  state: 'waiting' | 'running' | 'merged' | 'held' | 'skipped';
  runId: string | null;                     // the issue's latest release run
  note: string | null };                    // why it is held or skipped, or that it will be built again
type Release = { id: string; repo: string; name: string; description: string;
  status: 'planning' | 'scheduled' | 'building' | 'paused' | 'cancelled';   // planning is shown as "in preparation"
  createdBy: { username: string; displayName: string };
  createdAt: string; updatedAt: string;     // updatedAt strictly increases on every change
  issues: ReleaseIssue[];                   // in build order
  owner: { username: string; displayName: string } | null;   // who scheduled or started it
  branch: string | null;                    // 'release/<name>' once started
  startedAt: string | null; endedAt: string | null;
  pause: { reason: 'manual' | 'usage_limit' | 'credentials'; note: string | null; resumeAt: string | null } | null;
  scheduledAt: string | null;               // ISO instant; set exactly while scheduled
  waitingFor: { id: string; name: string } | null;          // only while scheduled and due
  startFailure: { at: string; owner: { username: string; displayName: string }; reason: string } | null };
                                            // the last scheduled start that did not happen

type SessionKind = 'chat' | 'spec-writer' | 'builder' | 'security' | 'ux' | 'ui-design' | 'code' | 'approver';
type SessionStatus = 'starting' | 'running' | 'idle' | 'done' | 'failed' | 'stopped';
type AgentSession = { id: string; repo: string; kind: SessionKind; model: string; status: SessionStatus;
  owner: { username: string; displayName: string }; title: string | null;
  runId: string | null; parentSessionId: string | null; issueNumber: number | null;
  createdAt: string; updatedAt: string; endedAt: string | null;
  costUsd: number | null; numTurns: number | null; lastActivity: string | null;  // lastActivity: short text, e.g. "Bash: ./gradlew test"
  live: boolean };  // an agent process exists right now
// status: `starting` = process launching; `running` = mid-turn (busy); `idle` = waiting for input
// (a chat between turns, with a process if `live`, else resumed on the next input); `stopped` = stopped by
// a person (a chat resumes on its owner's next input); `done` / `failed` = a pipeline agent finished.

type Stage = { id: string; role: Exclude<SessionKind, 'chat'>;
  round: number;             // 0 = initial build/review, 1..maxRounds = fix rounds
  model: string;             // the model the stage runs (or ran) with; set when the stage is created
  status: 'pending' | 'running' | 'done' | 'failed' | 'skipped';
  verdict: 'PASS' | 'CHANGES' | 'APPROVE' | 'ESCALATE' | null; summary: string | null;
  findings: { severity: string; file: string | null; detail: string }[];
  sessionId: string | null; startedAt: string | null; endedAt: string | null };

type Run = { id: string; repo: string; issueNumber: number; issueTitle: string; issueKind: IssueKind;
  status: 'queued' | 'spec' | 'building' | 'reviewing' | 'approving' | 'merged' | 'needs_human' | 'failed' | 'cancelled';
  round: number;             // fix rounds so far (0 = initial build)
  maxRounds: number;         // the starting user's effective maxFixRounds, fixed when the run is created
  branch: string | null; prNumber: number | null; prUrl: string | null;
  owner: { username: string; displayName: string }; startedBySessionId: string | null;
  error: string | null; createdAt: string; updatedAt: string; endedAt: string | null;
  stages: Stage[];
  releaseId: string | null;  // the release this run builds an issue for (null: a single run, or the release was deleted)
  baseBranch: string | null }; // the branch it starts from and merges into; null = the default branch

type Attachment = { id: string; name: string;   // name: sanitised (no path, control/bidi characters or leading dots; ≤ 120 chars)
  mediaType: string;                 // decided by sniffing the bytes: image/png|jpeg|gif|webp, application/pdf, application/zip,
                                     // text/plain (UTF-8 without NUL) or application/octet-stream — never the client's Content-Type
  size: number; kind: 'image' | 'file';           // image = one of the four raster types
  width: number | null; height: number | null;
  inline: 'original' | 'downscaled' | 'none';     // how the model sees an image (§5); always 'none' for files
  note: string | null };                          // why an image was downscaled / is file only

type ClientConfig = { attachments: { maxFileBytes: number; maxMessageBytes: number; maxCount: number; imageTypes: string[] } };

type Event = { seq: number; sessionId: string; ts: string;
  type: 'user' | 'claude' | 'system' | 'status'; payload: unknown };
//  user:   { text: string, username: string, attachments?: Attachment[] }   // attachments only when there are some
//          A message to a running pipeline agent (§6) adds: messageId: string, origin: 'direct' | 'chat',
//          viaSessionId?: string   // the forwarding chat session when origin is 'chat'
//  claude: one raw stream-json object from the CLI ({type:'system'|'assistant'|'user'|'result'|'stream_event', …})
//  system: { level: 'info' | 'warn' | 'error', text: string, messageId?: string }
//          messageId: the agent picked that message up (info) or stopped before it did (warn)

type AgentMessage = { status: 'queued';          // always: picked up at the agent's next step (§6)
  messageId: string; sessionId: string; runId: string | null;
  role: 'spec-writer' | 'builder'; note: string }; // note: human-readable, e.g. "Queued — delivered at the agent's next step …"
//  status: { status: SessionStatus }

type Settings = { agentsMayInstallSoftware: boolean;
  chatModel: string; pipelineModels: Record<Exclude<SessionKind, 'chat'>, string>;   // defaults for users without their own choice
  maxFixRounds: number };                                                             // default and maximum (0–10)

// Model values: an alias or a full model id, /^[A-Za-z0-9._\[\]-]{1,100}$/ (same rule for settings and preferences).
type PreferenceOverrides = { chatModel: string | null;            // null / missing = admin default
  pipelineModels: Partial<Record<Exclude<SessionKind, 'chat'>, string>>; maxFixRounds: number | null };
type Preferences = { overrides: PreferenceOverrides;
  effective: { chatModel: { value: string; source: 'user' | 'default' };
    pipelineModels: Record<Exclude<SessionKind, 'chat'>, { value: string; source: 'user' | 'default' }>;
    maxFixRounds: { value: number; source: 'user' | 'default'; max: number; clamped: boolean } };
  suggestions: string[] };                                       // 'opus', 'sonnet', 'haiku', 'fable'; free text is fine

type SoftwareRequest = { id: string; name: string; reason: string; repo: string;
  requestedBy: string; sessionId: string | null;
  status: 'pending' | 'installed' | 'denied'; note: string | null; createdAt: string; resolvedAt: string | null };
```

### Endpoints

| Method & path | Body → Response | Notes |
|---|---|---|
| `GET /api/health` | → `{ status: 'ok', version, name: 'agern' }` | no auth |
| `GET /api/internal/activity` | → `{ idle, activeRuns, busyAgents, version }` | no auth, **host-local only**: answered only when the TCP peer is a loopback address and no proxy header (`Forwarded`, `X-Forwarded-For`, `X-Forwarded-Host`, `X-Real-IP`) is present; anyone else gets the generic 404. `activeRuns`: runs in `queued`/`spec`/`building`/`reviewing`/`approving` (a restart fails them); `busyAgents`: sessions `starting`/`running` (pipeline agents, chats mid-turn). `needs_human` runs and chats between turns survive a restart and do not count. Used by the upgrade timer (§9) |
| `GET /api/auth/providers` | → `{ providers: string[], org: string, twoFactor: boolean }` | no auth |
| `POST /api/auth/login` | `{ username, password }` → `LoginStep` | sets cookie (pending until 2FA passes) |
| `POST /api/auth/totp` | `{ code }` → `LoginStep` | pending session; `code` is a TOTP code or a recovery code; 423 when locked |
| `POST /api/auth/totp/enroll` | `{ code }` → `LoginStep` | pending session in `enroll`; `done` step carries `recoveryCodes` |
| `POST /api/me/recovery-codes` | `{ code }` → `{ recoveryCodes: string[] }` | regenerate; needs a current TOTP code |
| `POST /api/admin/users/{id}/reset-2fa` | → `User` | admin |
| `POST /api/auth/logout` | → 204 | |
| `GET /api/me` | → `User` | 401 when not logged in |
| `PUT /api/me/claude-credential` | `{ kind, value }` → `User` | |
| `DELETE /api/me/claude-credential` | → `User` | |
| `PUT /api/me/github-token` | `{ token }` → `User` | 422 if GitHub rejects it. `GET /user` succeeds with any scope, so the token's kind and (classic) scopes are recorded and shown; Settings warns when a classic token lacks `repo` (public repositories only, no push) and explains the grants a fine-grained token needs |
| `DELETE /api/me/github-token` | → `User` | |
| `GET /api/me/preferences` | → `Preferences` | the caller's own model / fix-round choices and the effective values |
| `PUT /api/me/preferences` | `PreferenceOverrides` → `Preferences` | replaces the caller's choices; 400 for an invalid model or unknown role, 422 `max_fix_rounds_out_of_range` |
| `GET /api/projects` | → `Project[]` | repos of the org visible to the user |
| `GET /api/projects/{repo}` | → `Project` | |
| `GET /api/projects/{repo}/issues?state=open\|closed\|all` | → `Issue[]` | pull requests excluded |
| `GET /api/projects/{repo}/issues/{number}` | → `Issue` (with `body`) | |
| `POST /api/projects/{repo}/issues` | `{ title, body, kind }` → `Issue` | |
| `GET /api/projects/{repo}/releases` | → `Release[]` | newest first (`createdAt` desc), each with its issues in order |
| `POST /api/projects/{repo}/releases` | `{ name, description? }` → 201 `Release` | 400 invalid name or description; 409 `release_name_taken`. Starts nothing |
| `GET /api/releases/{id}` | → `Release` | 404 unknown or invisible |
| `PUT /api/releases/{id}` | `{ name, description }` → `Release` | rename or edit; same validation as create. Started: owner or admin, and a different name → 409 `release_name_locked` |
| `DELETE /api/releases/{id}` | → 204 | removes the plan and its assignments; the issues are untouched on GitHub. `scheduled` → 409 `release_scheduled`; `building`/`paused` → 409 `release_active`; `cancelled`: owner or admin, the branch stays and its runs keep existing with `releaseId` null |
| `POST /api/releases/{id}/start` | → `Release` | the caller becomes the owner; creates `release/<name>` and builds (§6 "Releases"). 409 `release_not_planning` (also while scheduled), 422 `claude_credential_required`, 409 `release_empty`, 409 `release_already_building`, 409 `release_branch_exists`, 422 `github_push_denied` |
| `PUT /api/releases/{id}/schedule` | `{ startAt: string }` → `Release` | set (`planning`, caller becomes owner) or change (`scheduled`, owner or admin, owner unchanged) the scheduled start. In order: 404 invisible; 400 "startAt must be an ISO-8601 time" (an instant with `Z` or offset), "The start time must be in the future", "The start time is too far ahead (at most a year)" (> 366 days); set: 422 `claude_credential_required`, 403 without a GitHub token; change: 403 "Only <owner> or an admin can change this schedule"; other states: 409 `release_not_schedulable`. Stored at millisecond precision |
| `DELETE /api/releases/{id}/schedule` | → `Release` | cancel: back to `planning`, `owner`/`scheduledAt`/`waitingFor` null, `startFailure` kept. 409 `release_not_scheduled` when not scheduled (also once the start has begun), else 403 unless owner or admin |
| `POST /api/releases/{id}/pause` | → `Release` | owner or admin; `building` → `paused` (else 409 `release_not_building`); the running issue finishes |
| `POST /api/releases/{id}/resume` | → `Release` | owner only; `paused` → `building` (else 409 `release_not_paused`); 422 `claude_credential_required` |
| `POST /api/releases/{id}/cancel` | → `Release` | owner or admin; `building`/`paused` → `cancelled` (else 409 `release_not_building`); cancels the release's active runs |
| `POST /api/releases/{id}/issues/{number}/retry` | → `Release` | owner only; release `building`/`paused` (else 409 `release_not_building`), issue `held`/`skipped` (else 409 `release_issue_not_held`); it is built again |
| `POST /api/projects/{repo}/releases/assign` | `{ issueNumbers: number[], releaseId: string \| null }` → `Release[]` | assigns or moves the issues to that release of `{repo}` (404 otherwise), or takes them out with `releaseId: null` (numbers in no release are ignored, no GitHub calls). 1–100 positive numbers, duplicates collapsed (400 otherwise; an invalid `releaseId` is 400). Every issue is fetched with the caller's token: unknown → 404 "Issue #n not found", closed → 409 `issue_closed`; all-or-nothing. Target `building`/`paused`: owner only, appended as `waiting`; target `cancelled`: 409 `release_closed`. An issue leaving a `building`/`paused` release: owner or admin, and only `waiting`, `held` or `skipped` (else 409 `release_issue_started`); leaving a `cancelled` one: owner or admin. A still active run of an issue that leaves is cancelled. Answers all releases of the project after the change |
| `PUT /api/releases/{id}/issues/order` | `{ issueNumbers: number[] }` → `Release` | the new complete order; anything but exactly the current issues → 409 `release_issues_changed`, nothing changes. `building`/`paused`: owner only, and every issue that is not `waiting` keeps its index (409 `release_issue_started`); `cancelled`: 409 `release_closed` |
| `GET /api/projects/{repo}/runs` | → `Run[]` | newest first |
| `POST /api/projects/{repo}/runs` | `{ issueNumber }` → `Run` | needs Claude credential; 409 if the issue has an active run |
| `GET /api/runs?active=true&status=<s>` | → `Run[]` | across visible repos, newest first (max 200). `active=true`: not finished, i.e. `queued`, `spec`, `building`, `reviewing`, `approving` or `needs_human`. `status`: only these statuses, repeatable and/or comma list (`?status=needs_human`); unknown status → 400. Both given: both apply |
| `GET /api/runs/{id}` | → `Run` | |
| `POST /api/runs/{id}/approve` | → `Run` | owner only, only in `needs_human` |
| `POST /api/runs/{id}/cancel` | → `Run` | owner or admin |
| `GET /api/projects/{repo}/sessions?kind=chat` | → `AgentSession[]` | |
| `POST /api/projects/{repo}/sessions` | `{ model? }` → `AgentSession` | new chat; needs Claude credential; without `model` the owner's effective `chatModel` |
| `GET /api/agents?active=true` | → `AgentSession[]` | all kinds, across visible repos. `active=true`: `live` (a process exists, e.g. a chat idling between turns) or `starting`/`running` |
| `GET /api/sessions/{id}` | → `AgentSession` | |
| `GET /api/sessions/{id}/events?after=<seq>&limit=<n>` | → `Event[]` | ascending |
| `GET /api/config` | → `ClientConfig` | limits the frontend checks before uploading |
| `POST /api/sessions/{id}/input` | `{ text, attachmentIds? }` → 202 | owner only. **Chat:** accepted mid-turn too (delivered after the current turn); a `stopped` or process-less chat resumes. `text` may be empty when `attachmentIds` is not. The attachments are bound to this message when it is accepted, so a message queued mid-turn keeps its own. Answer `{ status: 'accepted' }`. **Pipeline agent:** a text message for a running spec-writer or builder (§6 "Messages to running agents") → 202 `AgentMessage`; 403 for everyone but the run's owner (admins too), 409 `agent_does_not_accept_messages` (reviewers, approver), 409 `agent_not_running`, 422 `attachments_not_allowed` |
| `POST /api/sessions/{id}/attachments?name=<file name>` | raw bytes → 201 `Attachment` | owner only, chat only; see "Attachments" below |
| `GET /api/sessions/{id}/attachments/{aid}?preview=1` | → bytes | anyone who may see the session once it is sent; before that only its uploader. `preview=1`: the downscaled copy if there is one |
| `DELETE /api/sessions/{id}/attachments/{aid}` | → 204 | owner only; unsent only (409 `attachment_sent`) |
| `POST /api/sessions/{id}/interrupt` | → 202 | owner only; stops the current turn (chat only; 409 for pipeline agents — cancel the run instead) |
| `POST /api/sessions/{id}/stop` | → `AgentSession` | owner or admin |
| `DELETE /api/sessions/{id}` | → 204 | **Close a chat**: owner or admin (403 otherwise, 404 if the repo is not visible); chat only (409 `not_a_chat` for pipeline sessions — cancel the run instead). A live process is stopped first (as `stop`, up to 10 s; if it has not exited, 409 `agent_not_stopped` and nothing is deleted). Then the session is deleted permanently with its events, attachment rows and the files under `<dataDir>/attachments/<id>/`; its session sockets are closed with `4404` and the feed announces `session_closed`. Runs started from it keep running (their `startedBySessionId` becomes `null`). Afterwards every route for the id answers 404 |
| `GET /api/admin/settings` · `PUT /api/admin/settings` | `Settings` | admin |
| `GET /api/admin/users` | → `User[]` | admin |
| `GET /api/admin/software-requests` | → `SoftwareRequest[]` | admin |
| `POST /api/admin/software-requests/{id}` | `{ status, note? }` → `SoftwareRequest` | admin |

**Details pinned down by the backend implementation:**
- `POST` endpoints that create something (`…/issues`, `…/runs`, `…/sessions`) answer `201`.
- Error bodies may carry extra fields: `{ "error": { "code", "message", "lockedUntil"? } }`.
- Login errors (the frontend relies on these exact codes):

  | Step | Status | `code` | Meaning |
  |---|---|---|---|
  | password | 401 | `invalid_credentials` | wrong username or password |
  | password | 429 | `too_many_attempts` | rate limited (§4); `lockedUntil` says until when |
  | TOTP / enrol / recovery codes | 422 | `invalid_code` | wrong, malformed or replayed code (counts towards the lock) |
  | TOTP / enrol / recovery codes | 423 | `locked` | five wrong codes: second factor locked for 15 minutes; `lockedUntil` (ISO) |
  | TOTP / enrol | 409 | `wrong_login_step` | the pending session is in the other step (`totp` vs `enroll`): start again |
  | TOTP / enrol | 401 | `unauthorized` | no pending session (never logged in, or it expired after 10 minutes): start again |
  | TOTP | 422 | `not_enrolled` | the account has no confirmed TOTP secret |
  | enrol | 422 | `no_enrolment` | no enrolment in progress: start again |
- Messages to pipeline agents: reviewer/approver → 409 `agent_does_not_accept_messages`; agent not running
  (pending, finished, failed, stopped) → 409 `agent_not_running`; with attachments → 422
  `attachments_not_allowed`; not the run's owner → 403 `forbidden`.
- Releases: duplicate name → 409 `release_name_taken`; assigning a closed issue → 409 `issue_closed`; a stale
  order → 409 `release_issues_changed`. Every release route answers 404 when the caller cannot see the project,
  also when addressed by release id; then 403 `forbidden` for the owner/admin rules of started releases (§6
  "Releases"). Building: `release_not_planning`, `release_empty`, `release_already_building`,
  `release_branch_exists`, `release_not_building`, `release_not_paused`, `release_issue_not_held`,
  `release_issue_started`, `release_closed`, `release_active`, `release_name_locked` (409);
  `github_push_denied`, `claude_credential_required` (422);
  scheduling: `release_not_schedulable`, `release_not_scheduled`, `release_scheduled` (409).
- "Needs Claude credential" → 422 `claude_credential_required`; too many agents → 409
  `too_many_agents`; issue already has an active run → 409 `active_run_exists`; missing CSRF header
  → 403 `csrf`; foreign `Origin` → 403 `bad_origin`.
- A new chat session starts `idle` without a process; the first input launches the CLI
  (`starting` → `running` → `idle` after each turn). A `stopped` chat resumes (`--resume`) on the
  next input from its owner.
- When a run is created, its stages for round 0 are created as `pending` (spec-writer, builder,
  four reviewers, approver); fix rounds add a builder and four reviewer stages with `round = n` when
  round n starts; the approver's `round` becomes the round it runs in. Stages are returned ordered by
  round, then role. On failure or cancel, `pending` stages become `skipped` and `running` ones `failed`.
- For `needs_human` runs, `error` carries the human-readable reason (escalation summary, reviewers
  still requesting changes, or a failed merge). Approval clears it.
- MCP tools always act on the repository of the agent's session, as its owner.

**Attachments.** Uploads are untrusted:
- Same central checks as everything else: `requireUser`, session visibility (404 otherwise), owner for
  upload/delete/send (403), the CSRF header and Origin guard (it is a mutating request).
- Limits (§3): a declared `Content-Length` over the per-file limit is refused before reading; the body is
  read with a cap and abandoned as soon as it exceeds it (413 `attachment_too_large`). The uploader's unsent
  attachments in a session count as the next message: more than `maxCount` → 409 `too_many_attachments`,
  more than the per-message total → 413 `attachments_too_large` (checked under a per-session lock). Empty
  file → 400 `empty_attachment`.
- Sending: `attachmentIds` must all be unsent uploads of this session by the sender, without duplicates,
  else 409 `attachment_unavailable` (all or nothing). The user event carries them as `attachments`.
- Serving: only the four raster image types are served as what they are, `Content-Disposition: inline`;
  everything else (SVG, HTML, PDF, …) is `application/octet-stream` with `Content-Disposition: attachment`.
  Always `X-Content-Type-Options: nosniff`, `Content-Security-Policy: default-src 'none'; img-src 'self';
  style-src 'unsafe-inline'; sandbox`, `Cross-Origin-Resource-Policy: same-origin`, private immutable caching.
- Uploads never sent are deleted after 24 hours (and right away when the composer removes them or the page
  is left).

### WebSockets (cookie-authenticated)

- `GET /api/ws/sessions/{id}?after=<seq>` — replays persisted events with `seq > after`, then
  streams live. Frames are `Event` JSON. Live-only partial deltas are sent as
  `{ seq: -1, type: 'claude', payload: { type: 'stream_event', … } }`. An unknown or invisible session
  is refused with close code `1008 "not found"`. When the chat is closed (`DELETE /api/sessions/{id}`),
  the server closes every open socket of it with code `4404 "session closed"` (no frame is sent first);
  clients must not reconnect after `4404` or `1008`.
- `GET /api/ws/feed` — frames `{ type: 'session', session: AgentSession }` and
  `{ type: 'run', run: Run }` whenever one the user may see changes, and
  `{ type: 'issue', repo: string, issue: Issue }` when an issue is created through Agern (the REST
  route `POST /api/projects/{repo}/issues` or the MCP tool `factory_create_issue`). The issue is in
  list shape (no `body`), and the frame passes the same per-user visibility filter as the others. The
  feed is live only (no replay), so clients reload their issue lists and the project's open-issue count
  after a reconnect. Issues created, edited or closed directly on GitHub are not announced.
  `{ type: 'session_closed', repo: string, sessionId: string }` announces a closed (deleted) chat, with
  the same visibility filter; clients drop it from their lists and never show it again.
  `{ type: 'release', repo: string, release: Release }` is sent after every change to a release (created, edited,
  issues added, removed, moved in or out, reordered, started, paused, resumed, cancelled, and every state change of
  one of its issues), once per affected release — a move sends one for the source
  and one for the target. `{ type: 'release_deleted', repo: string, releaseId: string }` announces a deleted
  release. Both pass the same visibility filter; clients order versions by `updatedAt` and reload their release
  lists after a reconnect.

## 9. Deployment and release

**Where it runs.** To begin with Agern runs on one machine on a local network, reached by browser
at `FACTORY_PUBLIC_URL` (plain HTTP on the LAN is supported: the session cookie is marked `Secure`
only when that URL is `https`). The server binds `FACTORY_HOST` (default `0.0.0.0`). Agents reach
the MCP endpoint over loopback (`127.0.0.1:<port>`), never through the public URL; when `FACTORY_HOST`
is one specific non-loopback address the server additionally listens on `127.0.0.1:<port>` (logged
at startup) rather than refusing to start. WebSocket and mutating requests are
rejected when their `Origin` does not match `FACTORY_PUBLIC_URL`.

**Sandboxes (later).** The network has sandbox machines where agents will be able to try things
out. Not part of v1; they will plug in behind `AgentLauncher` / as agent tools.

**Release artifact.** One self-contained `agern.jar` (server with the built frontend embedded on
the classpath) — the host needs only Java 17 and PostgreSQL. `FACTORY_WEB_DIR` overrides the
embedded frontend when set. It ships in two packagings: the tarball for `install.sh` (Linux and
macOS) and a Debian package for Debian 12/13 and Ubuntu 24.04 (below).

**Cutting a release.** `./scripts/release.sh <version>` checks that the version is semantic, the
tree is clean, `main` is level with `origin/main` and the tag is new, then creates and pushes the
annotated tag `v<version>` (`--dry-run` only checks). GitHub Actions (`.github/workflows/release.yml`)
then checks and tests frontend and server, builds with `scripts/package.sh --deb <version>` (the
same script works locally), smoke-tests the package and publishes a GitHub Release with
`agern-<version>.tar.gz` (`agern.jar`, the `agern` CLI, `install.sh`, the systemd unit and launchd
plist templates, `.env.example`, `VERSION`), `install.sh` on its own (for bootstrapping),
`agern_<version>_all.deb` and `SHA256SUMS` covering all three. Versions with a suffix (`-rc.1`)
are published as pre-releases.

**Installing and upgrading a host** (`packaging/`). `install.sh` either runs from an unpacked
release or, downloaded alone, fetches the latest (or `--version`) release, verifies it against
`SHA256SUMS` and runs the installer inside it. As root it creates the unprivileged service user
`agern` (the service never runs as root), unpacks to `/opt/agern/versions/<version>` with symlinks
`current` and `previous`, creates `/etc/agern/agern.env` on first install (generated
`FACTORY_MASTER_KEY`, mode `0600`, never overwritten or printed), creates the data directory
(`/var/lib/agern`, macOS `/usr/local/var/agern`) and registers a service: systemd on Linux (the
env file is root-owned and read via `EnvironmentFile`), launchd on macOS (`dk.egernet.agern`; the
env file is owned by `agern` and read by `agern run`). `agern upgrade [version]` downloads a
release, verifies its checksum, unpacks it, switches `current` (the old one becomes `previous`),
restarts and waits for `/api/health` to report the new version — switching back automatically if
it does not; `agern rollback` swaps `current` and `previous`. `AGERN_PREFIX` relocates everything
for an unprivileged, service-manager-less install (pid file), and `AGERN_RELEASE_BASE_URL`
replaces `https://github.com/Egernet-CS/agern/releases` (any URL with the same `latest/download/…`
and `download/v<version>/…` layout, `file://` included). Database migrations run on startup and
are not undone by a rollback. The reported version comes from the build.

**Debian package** (`packaging/deb/`, built by `scripts/package.sh` with plain
`dpkg-deb --build --root-owner-group`; guide: [INSTALL-LINUX.md](INSTALL-LINUX.md)).
`agern_<semver>_all.deb`, `Architecture: all`. The file name carries the semantic version; the
package version replaces the `-` that starts a pre-release with `~` so dpkg sorts pre-releases
first (`1.3.0-rc.1` → `1.3.0~rc.1` < `1.3.0`). Depends: `default-jre-headless (>= 2:1.17) |
java17-runtime-headless` (checked with `apt-cache` on all three: Debian 12's default is OpenJDK 17,
Debian 13's and Ubuntu 24.04's 21; `openjdk-17/21-jre-headless` provide `java17-runtime-headless`),
`git`, `ca-certificates`, `curl`, `passwd`; Recommends `postgresql`; Suggests `gh`. The `claude`
CLI is not a dependency (`agern doctor` reports it and how to install it). Layout: `/usr/bin/agern`,
`/usr/share/agern/{agern.jar,VERSION,env.example}`, `/usr/lib/systemd/system/{agern.service,
agern-upgrade.service,agern-upgrade.timer}` (`/usr/lib`, not `/lib`: all targets are merged-/usr),
`/etc/agern/agern.env`, `/var/lib/agern`, `/var/cache/agern`, logs in journald. The `agern`
command detects the layout from where it lives (`<dir>/../share/agern/agern.jar` → deb, otherwise
the tarball layout), so one script serves both. Maintainer scripts:

- `postinst configure`: system user and group `agern` (home `/var/lib/agern`, `nologin`, never in
  `shadow` — `os` login on Linux needs that, which lets every agent read the password hashes, so
  it is the administrator's decision); `/etc/agern` `root 0755`, `/var/lib/agern` `agern 0750`
  (created once, never chowned recursively), `/var/cache/agern` `root 0755`;
  `/etc/agern/agern.env` created from `env.example` on first install only (generated
  `FACTORY_MASTER_KEY`, empty `FACTORY_GITHUB_ORG` and `FACTORY_DB_*`), `root:agern 0640`, not a
  dpkg conffile, never overwritten. `daemon-reload`; enables `agern.service` on first install;
  starts it (first install) or restarts it (upgrade, when enabled or running) only when
  `FACTORY_DB_URL` and `FACTORY_GITHUB_ORG` are set, through `deb-systemd-invoke` (honours
  `policy-rc.d`). The unit has `RestartPreventExitStatus=2` (the server's configuration-error exit),
  so a misconfigured start fails once instead of looping.
- `prerm remove`: stop and disable the service and timer. `prerm upgrade`: nothing — the old
  server keeps running until `postinst` restarts it.
- `postrm purge`: saves a root-only copy of `agern.env` to `/var/backups/agern.env.purged-<time>`
  (the master key is needed to use the database), removes `agern.env`, `/var/cache/agern`, the
  user and group, and prints what is left: `/var/lib/agern` and the database are never deleted.

`agern setup-db` (root) creates the role and database on the local PostgreSQL as `postgres`
(`runuser`), with a generated 32-character password sent over stdin (never in an argument list),
writes `FACTORY_DB_URL/USER/PASSWORD` into `agern.env` and checks a TCP login; idempotent (an
existing password for the same role is kept). The unit hardening keeps agents able to build and
test (network, writable `/var/lib/agern`, private `/tmp`, devices) but closes the ways to root:
`NoNewPrivileges`, empty `CapabilityBoundingSet`, `RestrictSUIDSGID`, `ProtectSystem=full`,
`ProtectHome=true`, `ProtectProc=invisible`, kernel/cgroup/clock protection.

**Upgrades of a deb install.** `agern upgrade [--channel stable|main] [--version X] [--file F]
[--force]` (root): resolves `SHA256SUMS` of the release (`latest/download/`, `download/main-latest/`
or `download/v<X>/`), downloads `agern_<semver>_all.deb` into root-only `/var/cache/agern/partial`,
verifies the SHA-256 and the package's name, architecture and version, then installs with
`apt-get install --allow-downgrades` (dependencies resolve) and waits for `/api/health` to report
the new version — reinstalling the previous package if it does not. `--file` installs a package
the admin names (copied root-only first; no checksum). The installed package and the one before
it are kept as `/var/cache/agern/{current,previous}.deb`; `agern rollback` reinstalls
`previous.deb` (downgrade). Without a cached copy of the version being replaced, `agern upgrade`
fetches it from its release (`download/v<semver>/`, or `main-latest` while that still holds it;
checksum- and version-verified), else rebuilds it with `dpkg-deb` from dpkg's own records
(`/var/lib/dpkg/info/agern.{list,postinst,prerm,postrm,md5sums}`, `dpkg-query -s` for the control
file) and the listed files under `/usr` — only when `dpkg --verify agern` is clean and every file is
root-owned and not group/world-writable, so nothing the service user can write ends up in a package
root installs. Failing both, it warns that no rollback copy was kept and `agern rollback` refuses
with a pointer to `agern upgrade --version X.Y.Z --force`. Packages carry `DEBIAN/md5sums`. Without `--force`, upgrade and rollback refuse while the server is
busy (below), refuse downgrades and do nothing when the version is installed. Downloads are
anonymous (the repository is public); no token is ever used.

**Channels.** `stable` is the latest GitHub release (`releases/latest` skips pre-releases). `main`
is the rolling pre-release `main-latest`, which CI (`ci.yml`, job `publish-main`) replaces on every
push to `main` after the tests and the package smoke tests on Debian 12, Debian 13 and Ubuntu 24.04
passed: it moves the tag `main-latest` to the commit and uploads the package and its `SHA256SUMS`
(only when the commit is still the head of `main`). Main builds are versioned by
`scripts/main-version.sh`: `X.Y.(Z+1)-main.<commit time UTC YYYYMMDDHHMMSS>.g<sha>` after release
`X.Y.Z` (after a pre-release `X.Y.Z-pre`: `X.Y.Z-pre.main.<time>.g<sha>`), package version with
`~`, so dpkg orders them after the last release, before the next and by commit time (tested with
`dpkg --compare-versions` in the smoke test).

**Automatic upgrades.** `agern-upgrade.timer` (disabled by default; `agern auto-upgrade on|off|status`)
runs `agern-upgrade.service` (root, `Type=oneshot`) every 15 minutes: `agern auto-upgrade run`
reads `AGERN_UPGRADE_CHANNEL` (`stable`/`main`) and `AGERN_UPGRADE_MAX_WAIT_HOURS` (default 24)
from `agern.env`, and when the channel has a newer version asks `GET /api/internal/activity` on
`127.0.0.1` whether a restart is safe. *Idle* means no run in progress and no agent starting or
mid-turn — the same states a restart would fail; a stopped server counts as idle, a running
server that does not answer as busy. Busy: postpone to the next tick (state in
`/var/cache/agern/auto-upgrade.state`). Waiting longer than the maximum for the same version:
give up on that version with a warning-priority journal message; a newer version starts a new
wait. Idle: download and verify, look once more, install as above. The endpoint was chosen over
reading the database from the script because it needs no database credentials or `psql` in the
root path and reflects exactly the states the server itself treats as unfinished.

**Security boundary.** Agents run as `agern` with permissions bypassed, so the `agern` user is
treated as hostile towards root: it has no sudo rules (the package adds none; `agern doctor`
fails on `sudo`/`wheel`/`admin` membership and warns about root-equivalent groups such as
`docker`); the service cannot gain privileges (above); only root installs packages, and only a
release package that matches its `SHA256SUMS` or a file the admin passes — an agent can build a
`.deb` but cannot have it installed. Nothing the `agern` user can write is executed or sourced by
root on the upgrade path: units and `/usr/bin/agern` are root-owned package files, the timer runs
with systemd's default `PATH`, `agern.env` is parsed as data (only known keys, set without
`export`, and only when it is root-owned and not group/world-writable), downloads and state live
in root-only `/var/cache/agern`, and root never runs a program found via the agents' `PATH`
(`agern doctor` only looks those up). The checksum guards against corrupt downloads, not against
a compromised GitHub release; the activity answer is advisory (it decides when, never what).

## 10. Frontend

Routes: `/login`, `/` (projects), `/p/:repo` (issues · CLI chat · agents), `/p/:repo/runs/:runId`
(pipeline diagram with live stages; selecting a stage shows that agent's transcript),
`/p/:repo/releases/:releaseId` (a release's plan and build, below),
`/agents` (everything running), `/settings` (own credentials, and "Models & cost": the user's own model per
role and fix rounds, with presets — *Default* clears every choice, *Economy* picks Sonnet for chat and spec,
Haiku for the four reviewers and one fix round), `/admin` ("Factory settings": the default models and the
default and maximum fix rounds; installing software stays admin-only).

The chat renders the Claude stream the way the terminal does: prompt line, streamed assistant
text, tool calls as `⏺ Tool(args)` with collapsible results, thinking, cost/turn footer. Sessions
owned by someone else are shown read-only with an "observing <user>" banner.

On the run page the owner of a run gets a text-only composer (the chat prompt without `/factory` commands,
attachments or interrupt; it can send while the agent works) under the transcript of a selected spec-writer or
builder while that agent is `running`, with a note that messages are queued for the agent's next step and are
not posted on GitHub. Everyone else, and every other stage, stays read-only. In the transcript a forwarded
message shows its sender and origin ("alice · direct" or "alice · via chat <id>") and its state: "queued —
delivered at the agent's next step", then "delivered" (or "not delivered" if the agent stopped first).

The owner attaches files by pasting (a pasted screenshot is named `screenshot-<date>-<time>.png`),
dropping them anywhere on the chat pane (which shows a drop target) or with the `+` button. Each becomes a
removable chip above the input that uploads at once with progress; per-file errors (too large, too many) show
on the chip and block sending until removed; sending waits for uploads. In the transcript images show as
thumbnails that open larger, other files as chips with name and size that download. Observers see them too
but have no attach affordances.

**Layout.** The project page is an app shell exactly the viewport tall (`100dvh`, or the visual viewport's
height while an on-screen keyboard is open): the top bar, the project header and (on narrow screens) the tabs
stay at the top, the chat prompt and its footer line stay at the bottom of the chat pane, and only the
transcript scrolls — the issues and agents/runs panes each scroll on their own; the document never scrolls.
The transcript sticks to the bottom while the agent streams unless the reader has scrolled up, then offers
"jump to latest". The input grows with its text and attachment chips up to a cap and then scrolls inside,
so the transcript shrinks rather than the prompt being pushed off screen. Other pages (projects, run,
agents, settings, admin) scroll normally under a sticky top bar; the run page's transcript is its own
scrolling pane with the same stick-to-bottom behaviour.

**Closing chats.** The activity pane lists every chat of the project under "Chats" (newest first). The owner
of a chat, and admins, get a "Close" button on its row and a "Close chat" button in the open chat's header;
both ask for confirmation (the chat, its transcript and attachments are deleted permanently; runs started from
it keep running). The closed chat disappears from the picker and all lists at once; if it was open, `?session=`
is cleared and the page falls back to another chat or the empty state. Others watching it via `?session=` see
"This chat was closed" with a Dismiss button instead of an error.

**Releases.** The activity pane has a "Releases" section above "Runs": one row per release (name, the status pill
— "in preparation", "scheduled", "building", "paused" or "cancelled" — and "N issues", or for started releases
"k of n merged" plus "· h held" when h > 0; for a scheduled release "· starts <local date and time>" or "· waiting
for <name>", for one in preparation whose scheduled start failed "· start failed") linking to its page, and "+ New" (accessible name "New release") opening the dialog
"New release in <repo>" (Name with the naming rule, optional Description). Once the project has at least one
release, the issues panel adds: a "Release" filter (All issues · No release · each release) combined with the kind
and state filters; the release name as a tag on assigned issues, linking to the release page; on open issues a
compact "Release for #n" select (assigns, moves or removes at once; disabled while the request runs; errors show
in the panel's alert line) and a "Select #n" checkbox — with a selection, a bar shows "N selected", "Assign
selected to", "Assign" and "Clear" (changing the state filter clears the selection). Badges and filter come from
the live release list (fetched once per project page and overlaid with feed frames and the client's own answers),
so assignments made by others, `/factory` or the chat agent appear without a reload; a deleted release drops out
of the filter. Cancelled releases are not offered as targets in the per-issue and bulk selects (an issue in one still
shows its tag); server refusals (403/409) show in the alert line and the control returns to the server's value.
The release page (a normal scrolling page) shows the name, pill, description (plain text, line
breaks kept), creator, the note that nothing is built until the release is started, "Edit" (name, description)
and "Delete" (confirmation; the issues stay open and unassigned; then back to the project). Its issues are an
ordered list with "Move #n up", "Move #n down" and "Remove #n from release" buttons (no drag and drop), and an
"Add an open issue" select listing open issues not in this release (those in another release are marked
"(in <name>)" and moved). It updates live; if someone else deletes the release, the page says so.

Scheduled start on the release page: in preparation, "Schedule start…" opens the dialog "Schedule release <name>"
with "Start at" (`datetime-local`, required, `min` now, default today 22:00 local time if still ahead, otherwise
tomorrow 22:00) and the hint "Your local time (<IANA zone>). The release is built with your Claude and GitHub
credentials. If another release of this project is still building at that time, this one waits for it."; it sends
`new Date(value).toISOString()`, keeps server errors in the dialog (`role="alert"`) and disables its buttons while
the request runs. Scheduled, the pill reads "scheduled" and the plan note becomes "Scheduled to start <local date
and time> by <owner>."; when due and waiting, a `role="status"` line "Its time has come. Waiting for release <name,
linked> to finish building." The owner and admins get "Change time" (the same dialog titled "Change scheduled
start", prefilled, "Save") and "Cancel schedule" (at once, no confirmation); others read "Only <owner> or an admin
can change the schedule.". "Delete" is disabled with the hint "Cancel the scheduled start to delete this
release."; editing and the issue list stay enabled. After a failed scheduled start an error notice "The scheduled
start did not happen" shows the reason and "Scheduled by <user> · tried <local date and time>". A 409
`release_not_scheduled`/`release_not_schedulable` reloads the release and shows the server's message. Times are
shown in the browser's time zone in `<time dateTime>`. The status pill shows unknown states as their raw text.

For building, the release page also shows (owner = `me.username === release.owner.username`,
admin = `me.isAdmin`): for started releases "started by <owner> · <time ago>", the branch in monospace linking to
`<project.htmlUrl>/tree/release/<name>`, and the progress "k of n merged" (plus "· h held", "· s skipped"); the
note "This release is a plan…" only while planning. A plain-text status line (`role="status"`): "Building #n
<title>." · "Nothing left to build." (plus " h held issue(s) need attention.") · the pause note (plus " #n is
finishing; no further issue starts." while one runs; for a usage limit "Resumes automatically <time>.") ·
"Cancelled <time ago>. What was merged stays on release/<name>.". Only the buttons the user may use are rendered:
planning "▶ Start release" (disabled with the hint "Add an issue first" when empty), "Schedule start…", Edit,
Delete; scheduled "Change time" and "Cancel schedule" (owner or admin), Edit, Delete (disabled); building
"Pause", "Cancel release", Edit; paused "Resume" (owner), "Cancel release", Edit; cancelled Delete (owner or
admin). In a started release Edit shows the name disabled ("The name is fixed once the release is started"). Users
who may change nothing see "Only <owner>, who started this release, and admins can change it." The dialog "Start
release?" explains the branch, the credentials used and that merged issues stay open (Cancel has focus; errors in
`role="alert"`; "Starting…" while the request runs); "Cancel release?" offers "Keep building" (focused) and
"Cancel release". Each issue shows a state badge with its text, a "run" link to its latest release run, and the
note for held and skipped issues; "Retry #n" (owner, held or skipped); in a started release moving is owner-only
among `waiting` issues and removing is for the owner or admins on `waiting`, `held` and `skipped` issues (any issue
when cancelled); "Add an open issue" shows while planning and, for the owner, while building or paused.

On the run page of a release run the merge node is titled "merge to release" and the header shows the base branch
linking to the release page (plain text when the release was deleted); single runs still say "merge to main".

Artwork is served from `web/public/art/` (see `art/README.md`).
