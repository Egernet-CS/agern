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
  The role prompt is passed with `--append-system-prompt`. Pipeline agents read their task prompt
  from stdin (text input, stdin closed afterwards); reviewers and the approver add `--json-schema`
  and `--disallowedTools Edit,Write,NotebookEdit` (the spec-writer is read-only too). The verdict is
  read from the `result` event's `structured_output`.
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
`factory/issue-<number>-<slug>`.

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
- Orchestration is a deterministic state machine in the server, not something an LLM decides. On
  server restart, unfinished runs are marked `failed` with a clear reason (v1).

Run `status`: `queued`, `spec`, `building`, `reviewing`, `approving`, `merged`, `needs_human`,
`failed`, `cancelled`.

Stage `role`: `spec-writer`, `builder`, `security`, `ux`, `ui-design`, `code`, `approver`.
Stage `status`: `pending`, `running`, `done`, `failed`, `skipped`.
Stage `verdict`: `PASS`, `CHANGES`, `APPROVE`, `ESCALATE` or null.

## 7. Chat agent ↔ factory (MCP)

The server exposes an MCP endpoint (`POST /mcp`, streamable HTTP, JSON responses). Every agent
process gets its own bearer token in its `--mcp-config`; the token maps to the session and thereby
to the owner and the repo, and dies with the process.

Tools: `factory_list_issues`, `factory_get_issue`, `factory_create_issue`, `factory_start_run`,
`factory_list_runs`, `factory_get_run`, `factory_approve_run`, `factory_cancel_run`,
`factory_request_software`. Pipeline-stage agents get only `factory_get_issue` and
`factory_request_software`.

Chat input starting with `/factory` is handled by the server and not sent to Claude (with attachments it
is rejected with 422 `attachments_not_allowed`; they stay unsent so the owner can send them in a normal message):
`/factory run <issue>`, `/factory status`, `/factory approve [runId]`, `/factory cancel <runId>`,
`/factory help`. The reply is appended to the transcript as a `system` event.

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
  createdAt: string; updatedAt: string; htmlUrl: string; body?: string; activeRunId: string | null };

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
  stages: Stage[] };

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
//  claude: one raw stream-json object from the CLI ({type:'system'|'assistant'|'user'|'result'|'stream_event', …})
//  system: { level: 'info' | 'warn' | 'error', text: string }
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
| `POST /api/sessions/{id}/input` | `{ text, attachmentIds? }` → 202 | owner only, chat only; accepted mid-turn too (delivered after the current turn); a `stopped` or process-less chat resumes. `text` may be empty when `attachmentIds` is not. The attachments are bound to this message when it is accepted, so a message queued mid-turn keeps its own |
| `POST /api/sessions/{id}/attachments?name=<file name>` | raw bytes → 201 `Attachment` | owner only, chat only; see "Attachments" below |
| `GET /api/sessions/{id}/attachments/{aid}?preview=1` | → bytes | anyone who may see the session once it is sent; before that only its uploader. `preview=1`: the downscaled copy if there is one |
| `DELETE /api/sessions/{id}/attachments/{aid}` | → 204 | owner only; unsent only (409 `attachment_sent`) |
| `POST /api/sessions/{id}/interrupt` | → 202 | owner only; stops the current turn (chat only; 409 for pipeline agents — cancel the run instead) |
| `POST /api/sessions/{id}/stop` | → `AgentSession` | owner or admin |
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
  `{ seq: -1, type: 'claude', payload: { type: 'stream_event', … } }`.
- `GET /api/ws/feed` — frames `{ type: 'session', session: AgentSession }` and
  `{ type: 'run', run: Run }` whenever one the user may see changes.

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
`previous.deb` (downgrade). Without `--force`, upgrade and rollback refuse while the server is
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
`/agents` (everything running), `/settings` (own credentials, and "Models & cost": the user's own model per
role and fix rounds, with presets — *Default* clears every choice, *Economy* picks Sonnet for chat and spec,
Haiku for the four reviewers and one fix round), `/admin` ("Factory settings": the default models and the
default and maximum fix rounds; installing software stays admin-only).

The chat renders the Claude stream the way the terminal does: prompt line, streamed assistant
text, tool calls as `⏺ Tool(args)` with collapsible results, thinking, cost/turn footer. Sessions
owned by someone else are shown read-only with an "observing <user>" banner.

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

Artwork is served from `web/public/art/` (see `art/README.md`).
