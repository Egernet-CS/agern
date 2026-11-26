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

**Container policy.** In production the factory runs directly on its host and uses a natively
installed PostgreSQL; nothing in the factory itself is containerised, and nothing depends on Docker.
Containers exist only to build and test the software the agents work on: per-run *sandboxes* in
rootless Podman (§5 "Sandboxes"). `docker-compose.dev.yml` exists only so a developer can get a
throwaway database.

## 2. Stack

| Concern | Choice |
|---|---|
| Backend | Kotlin, Ktor 3 (Netty), kotlinx.serialization, coroutines |
| Database | PostgreSQL 16, Flyway migrations, HikariCP, plain JDBC (no ORM) |
| Frontend | React 18, TypeScript, Vite, React Router; no heavy UI kit |
| Agents | The `claude` CLI in headless mode (`-p`, stream-json in and out) |
| Realtime | WebSocket for output, REST for input |

All persistent state lives in PostgreSQL, including the bytes of chat attachments, of the screenshots
reviewers attach to their stage (§6 "Screenshots") and of project icons (§8 "Project icons"). The only things on disk are git workspaces, the
per-user Claude home directories and the agents' copies of chat attachments (a cache of the database,
§5), all under `FACTORY_DATA_DIR` — plus, with sandboxes on, the service user's Podman storage (images,
per-run containers, per-project cache volumes; §5 "Sandboxes").

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
| `FACTORY_LDAP_REQUIRED_GROUP`, `FACTORY_LDAP_GROUP_MEMBER_ATTRIBUTE`, `FACTORY_LDAP_GROUP_MEMBER_VALUE` | —, `member`, `dn` | LDAP group check (§4): one group DN or several separated by `;` (any one admits); the group attribute that lists members (`uniqueMember`, `memberUid`, …); whether it holds the user's DN (`dn`) or login name (`uid`). Unset: no check. Recommended whenever the directory is wider than the team |
| `FACTORY_LDAP_URL`, `FACTORY_LDAP_USER_DN_TEMPLATE`, `FACTORY_LDAP_BASE_DN`, `FACTORY_LDAP_USER_FILTER`, `FACTORY_LDAP_BIND_DN`, `FACTORY_LDAP_BIND_PASSWORD` | — | LDAP. Either a DN template (`uid={0},ou=people,dc=x`) or search-then-bind |
| `FACTORY_DEV_USERS` | — | `alice:pw,bob:pw`. Only read when `dev` is in `FACTORY_AUTH`. Never for production |
| `FACTORY_DATA_DIR` | `./data` | Workspaces and per-user homes |
| `FACTORY_WEB_DIR` | unset | Built frontend directory, served with SPA fallback. Unset: the frontend embedded in `agern.jar` (classpath `web/`) is served; with neither, non-API paths answer 404 |
| `FACTORY_CLAUDE_BIN` | `claude` | Path of the Claude CLI (tests point this at a stub) |
| `FACTORY_CODEX_BIN` | `codex` | The OpenAI Codex CLI for the image provider `codex` (§4 "Codex login"); a bare name is resolved on `FACTORY_AGENT_PATH`. Optional: without it Codex is listed as unavailable. Tests and the e2e suite point it at `server/src/test/resources/fake-codex` |
| `FACTORY_PUBLIC_URL` | `http://localhost:8080` | The URL browsers use. Cookie is `Secure` only when it is `https`; mutating requests and WebSocket upgrades with a different `Origin` are rejected (403). Agents reach MCP over loopback (`http://127.0.0.1:<port>/mcp`), not through this URL; personal MCP tokens (§7 "Personal tokens") reach `POST /mcp` through it (`<FACTORY_PUBLIC_URL>/mcp`), and its host (plus a non-default port) names the instance in their server name, `initialize` answer and tool descriptions. Text Agern writes on GitHub (comments, pull request bodies) links to its pages only when this is a public address; with an internal one (loopback, a private network, a name without a public domain) it names the page instead, so no internal address is published. |
| `FACTORY_ALLOWED_ORIGINS` | empty | Extra allowed browser origins, comma list (e.g. `http://localhost:5173` for the Vite dev server) |
| `FACTORY_MAX_AGENTS_PER_USER` | `8` | Concurrent agent processes per user |
| `FACTORY_CHAT_IDLE_MINUTES` | `30` | Idle chat processes are stopped and resumed on next input |
| `FACTORY_STAGE_TIMEOUT_MINUTES` | `60` | Wall-clock limit for one pipeline stage agent (1–1440). A spec-writer or builder is warned at 80 % of it; on timeout the agent is stopped, the stage is `failed` and the run `failed` with an error that says so (§6 "Time limit") |
| `FACTORY_NETWORK_RETRY_HOURS` | `24` | How long a run or a release start waits for the network (GitHub, the Claude API) before it fails or holds the issue (0–168, whole hours; `0` switches waiting off, §6 "Transient failures"). Anything else is a startup error |
| `FACTORY_RELEASE_CHECKS_SETTLE_SECONDS` | `120` | How long the head commit of a pull request must have had no checks at all before "no checks" counts as green, for an automatic release (§6 "Checks") and before a run is merged (§6 "Checks before a merge") (0–3600). Measured from when Agern first saw that head commit (for a run: when Agern pushed it, else its first evaluation), persisted across restarts |
| `FACTORY_RUN_CHECKS_WAIT_MINUTES` | `60` | How long an approved run waits in `waiting_for_checks` for the checks on its pull request's head commit before it goes to `needs_human` (1–1440; §6 "Checks before a merge") |
| `FACTORY_AGENT_PATH` | the server's `PATH` | `PATH` value handed to agent and git processes (the only value taken from the server environment) |
| `FACTORY_GITHUB_API_URL` | `https://api.github.com` | GitHub REST base URL (GitHub Enterprise: `https://host/api/v3`) |
| `FACTORY_GITHUB_STATUS_URL` | githubstatus.com, or off | GitHub's official status summary shown on the GitHub status page (§6 "GitHub status"). Unset: `https://www.githubstatus.com/api/v2/summary.json` when `FACTORY_GITHUB_API_URL` is `https://api.github.com` and the fake GitHub is off, otherwise off (GitHub Enterprise, development). `off` or empty switches it off; any other value must be an `http://` or `https://` URL, else a startup error |
| `FACTORY_ATTACHMENT_MAX_MB` | `20` | Largest single chat attachment (1–512) |
| `FACTORY_ATTACHMENT_MAX_MESSAGE_MB` | `50` | All attachments of one message together (1–512, at least the per-file limit) |
| `FACTORY_ATTACHMENT_MAX_COUNT` | `10` | Attachments per message (1–20) |
| `FACTORY_SCREENSHOT_MAX_MB` | `5` | Largest single screenshot a UX or UI design reviewer attaches (1–512; §6 "Screenshots") |
| `FACTORY_SCREENSHOT_MAX_COUNT` | `12` | Screenshots per stage, i.e. per reviewer per round (1–50) |
| `FACTORY_IMAGE_MAX_PER_REQUEST` | `4` | Images per `factory_generate_image` call (1–10; §6 "Generated images") |
| `FACTORY_IMAGE_MAX_PER_TURN` | `8` | Images per chat message of the owner (1–100) |
| `FACTORY_IMAGE_MAX_PER_STAGE` | `12` | Images per pipeline stage agent (1–200; each fix round is a new stage) |
| `FACTORY_IMAGE_MAX_EDGE` | `2048` | Longest edge an agent may ask for, in pixels (256–4096) |
| `FACTORY_SANDBOX` | `off` | `off` or `podman`: per-run build/test sandboxes in rootless Podman (§5 "Sandboxes"). `off`: runs behave as before and the builder gets no sandbox tools; definitions can still be approved. `fake` (development and end-to-end tests only, accepted only with `FACTORY_DEV_FAKE_GITHUB`): an in-memory runtime that only pretends to run commands |
| `FACTORY_SANDBOX_PODMAN` | `podman` | The podman binary |
| `FACTORY_SANDBOX_PODMAN_URL` | unset | When set, every call is `podman --url <url> …` (podman-remote against a `podman system service`, e.g. `unix:///run/…/podman.sock`) |
| `FACTORY_SANDBOX_CGROUP_PARENT` | unset | When set, every container gets `--cgroup-parent <path>` (an absolute cgroup path, not a `.slice`). Needed by rootless Podman with the `cgroupfs` manager — the packaged `agern-sandbox.service` (§9) — which creates no cgroup, and so applies no limits, for a container without one; `agern sandbox enable` sets it to `<unit cgroup>/containers` |
| `FACTORY_SANDBOX_REGISTRIES` | `docker.io,ghcr.io,quay.io,mcr.microsoft.com` | Registries sandbox and service images may come from (comma list of hosts, `host:port` allowed); images must name one |
| `FACTORY_SANDBOX_MAX_CPUS` | `2` | Upper limit, and default, of CPUs per container |
| `FACTORY_SANDBOX_MAX_MEMORY` | `4g` | Upper limit, and default, of memory per container (`k`/`m`/`g`, binary; at least `64m`); swap is not added on top |
| `FACTORY_SANDBOX_MAX_CONCURRENT` | `2` | Containers that run commands that may exist at the same time, over all runs (1–100): a run's sandbox takes one slot and each platform container it creates one more (sidecars take none). A builder that would exceed it first gives up its run's own idle containers, else gets a "busy" answer |
| `FACTORY_SANDBOX_MAX_PLATFORMS` | `4` | Platforms a definition may name besides its default image (0–20; `0` forbids them) |
| `FACTORY_SANDBOX_EXEC_TIMEOUT_MINUTES` | `30` | Default and maximum time for one sandbox command (1–1440), also capped by `FACTORY_STAGE_TIMEOUT_MINUTES` |
| `FACTORY_DEV_FAKE_GITHUB` | `false` | Development only, requires `FACTORY_AUTH=dev`: in-memory fake GitHub (org with `demo-app`, `private-lab` and the workspace `demo-workspace` with its members `demo-ws-app` (base branch `dev`), `demo-ws-tools` and `demo-ws-lab` (only alice can read it); issues #1–#5 of `demo-workspace` carry one, no, several and an inaccessible member's label; the submodule workspace `demo-shop` whose `.gitmodules` declares `services/api` (`demo-shop-api`, SSH URL), `web` (`demo-shop-web`, relative URL, `branch = main`), `tools/cli` (`demo-ws-tools`, whose label no issue of `demo-shop` carries, so it is missing until Agern creates it) and `vendor/icons` (another organization, context only), with issues #1–#2 labelled `demo-shop-api` and `demo-shop-web` and #3 without a member label (a run of `demo-shop` itself); tokens `fake-<login>` = classic with `repo`, `read:org`; `fake-<login>~<scope>,…` = only those scopes), local `git init` instead of clones, pushes skipped (a sync run's fast-forward push moves the local release branch instead); `<dataDir>/fake-github-checks/<repo>/<branch>.json` sets the checks of a branch's head and, with `"conflict": true`, makes its pull requests conflict (§6 "Releasing", "Fake-GitHub mode") |

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
  The MCP endpoint has its own throttle for guessed bearer tokens (§7 "Personal tokens"): 20 unknown
  bearers from one client address within 15 minutes block personal tokens from that address (429).
- `os`: a user account on the machine the factory runs on (PAM on Linux; on macOS PAM or
  `dscl . -authonly`). The password must never appear in a process argument list.
- `ldap`: bind against the directory. Without `FACTORY_LDAP_REQUIRED_GROUP` any successful bind is
  a login, so everyone in the directory can log in; set the group check whenever the directory is
  wider than the team.
  - The group check runs after the user's own bind, on the same connection and as the user, in both
    the DN-template and the search-then-bind mode: an LDAP compare of each configured group's member
    attribute against the user's DN (`dn`) or login name as typed (`uid`). The first group that
    answers true admits the user ("any of"). No search and no service account are involved, so no
    extra secret lies on the host; this is why it is preferred over a filter with a service account.
  - It fails closed: compare false, a missing group, insufficient access or a connection error all
    refuse the login, with exactly the answer of a wrong password (401 `invalid_credentials`,
    `login_failed` audit row, throttle). Only the server log says why
    (`ldap: <user> is not in <group>`, `ldap: cannot check <group> for <user>: <result code>`).
  - It is evaluated at every password login, without caching. Removing someone from the group shuts
    them out at their next login; sessions that already exist last up to 14 days.
  - Prerequisite: the directory's ACLs must let authenticated users compare (or read) the member
    attribute of the configured group entries.
  - `FACTORY_ADMINS` is unchanged; an admin must pass the group check like anyone else.
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
- *Image provider* (optional) — one image-generation provider (OpenAI or Google Gemini) with the user's own API
  key and a model (§6 "Generated images"), or OpenAI Codex with the user's own ChatGPT login (below). An API key is
  checked with the provider before it is stored.

All three are stored encrypted with AES-256-GCM under `FACTORY_MASTER_KEY` (random 12-byte nonce per
value, username as associated data). They are never returned by any API, never logged, and never
written to disk in plaintext.

**The ownership rule.** An agent process always runs with the credentials of the user who started
it — the *owner*. There is no API through which user A can cause a process to start with user B's
credential: only the owner may send input to, resume, or approve work in their sessions and runs
(admins may stop, never drive). Everyone else gets read-only observation. The same holds for images: the server
generates them only with the image key of the owner of the chat or run whose agent asks, and the key never reaches
an agent process.

**Codex login.** The image provider `codex` (`CodexImageProvider`) runs the OpenAI Codex CLI on the server with the
user's own ChatGPT login instead of an API key.

- *Connecting:* `POST /api/me/image-provider/codex/login` runs `codex login --device-auth -c
  cli_auth_credentials_store="file"` in a fresh directory and answers the verification URL (only
  `https://auth.openai.com/…` is accepted) and the one-time code once the CLI printed them (ANSI codes stripped; 502
  `codex_login_failed` if none within 20 s). The web polls `GET …/codex/login` (`pending`, `connected`, `failed`,
  `expired` after 15 minutes, `none`). When the CLI exits 0 and `$CODEX_HOME/auth.json` is a regular file of 1 byte to
  64 KiB holding a JSON object, its text is stored like an API key (`users.image_key_enc`, AES-GCM, username as
  associated data) with `image_provider = 'codex'`, `image_model = 'default'`. Pending logins live in memory
  (`CodexLogins`), one per user; a new start, `DELETE …/codex/login`, saving or removing the provider setting, expiry
  and server shutdown kill the process. `PUT /api/me/image-provider` refuses `codex` (409 `codex_login_required`).
  Starts are limited to 5 per user per 15 minutes (429). The URL, the code and `auth.json` are never logged; only
  the two login responses to their owner contain the code.
- *Generating:* each call gets `$FACTORY_DATA_DIR/codex/<uuid>/` (`0700`) with `home/` (`HOME` and `CODEX_HOME`, the
  login written as `auth.json`, `0600`) and `work/`, and runs `codex exec --skip-git-repo-check --ignore-user-config
  --json --color never -s read-only -C <work> -c cli_auth_credentials_store="file" -` with an environment built from
  scratch (`PATH`, `HOME`, `CODEX_HOME`, `LANG`, `TERM`, `NO_COLOR` only — no Claude, GitHub or factory variable). The
  instruction, a fixed template ending in the agent's prompt, goes to stdin only. Size and format are wishes (the
  tool's `notes` report what came back). Images are collected from regular files (no symlinks) under
  `home/generated_images/` and `work/`, oldest first. The directory is deleted afterwards in every case; leftovers are
  deleted at server start. 600 s timeout.
- *Plaintext on disk:* the login is the one secret that exists in plaintext on disk, only while its `codex` process
  runs, in that `0700` directory. Codex runs as the server's OS user, like every agent (the v1 limitation of §5).
- *Refreshed tokens:* Codex may refresh and rewrite `auth.json`. One login must not be used by two processes, so one
  user's Codex generations run one at a time (a per-user lock; the stored login is read after taking it). If the file
  changed — on success or failure — the new text replaces the stored login by compare-and-set
  (`UPDATE … WHERE image_provider = 'codex' AND image_key_enc = <what was read>`), so a re-login or removal made
  during the call wins.
- *Errors:* CLI output never leaves the provider. Without an image, the output is matched loosely: `401`/unauthorized/
  not logged in/refresh token/expired token → "Your sign-in to OpenAI Codex is no longer valid. Connect it again under
  Settings → Image generation."; usage limit/rate limit/quota → quota; content policy/safety/moderation → refused;
  otherwise "answered without a usable image".
- *Verification (factory host, 2026-11-22):* `codex` 0.170.0 is installed; `codex features list` reports
  `image_generation stable true`; the bundled `imagegen` skill says the built-in `image_gen` tool needs no
  `OPENAI_API_KEY` and saves under `$CODEX_HOME/generated_images/…`; `codex exec` is non-interactive and reads the
  prompt from stdin; `codex login --device-auth` runs headless and prints the URL and code shown above. **Not
  verified**, because no ChatGPT login was available to the pipeline: a real generation on a given ChatGPT plan, that
  the file appears under `generated_images` with `-s read-only`, and the CLI's exact error texts. The owner's first
  real use is the acceptance test for these; if it fails, removing `CodexImageProvider` from the list in `App.kt`
  removes the provider without touching the rest. The user must enable device-code login in their ChatGPT security
  settings.

**Visibility.** A user may see a project, its issues, runs, sessions and transcripts if and only if
their own GitHub token can read that repository (`GET /repos/{org}/{repo}` → 200; cached 60 s per
user+repo). All GitHub calls made for a user use that user's token, so GitHub itself enforces
access. A user without a GitHub token sees no projects.

A workspace project (§5 "Workspace projects") adds one rule: every run and agent session records the member
repositories that were checked out for it (`context_repos`: at a run's creation the target and every member the owner
can read, narrowed at its set-up to what was checked out, and copied to its stage and advisor sessions; for a chat the
union of every member clone in its working directory — every repository in `members.json` whose clone exists, recorded
when the chat is created, before its process starts and at every sync, whatever the current definition says or whether
it can be read at all). Such a run or session — and everything recorded with it: transcripts, event streams,
attachments, screenshots, images — is visible only to a user who can read the workspace repository **and** every
recorded repository (`Access.canSee(user, repo, alsoRepos)`), at every enforcement point: `FactoryService.visibleRun`,
`listRuns` and `sendToAgent`, `visibleSession`, `GET /api/projects/{repo}/sessions`, `GET /api/agents`, the session
WebSocket, the feed's `session` and `run` frames, `ImageService.visible` (through the image's session) and the admin
sandbox overview's `visible`, and the list of an issue's selected images (`GET
/api/projects/{repo}/issues/{number}/images` leaves out images from sessions the caller may not see). Invisible ones answer exactly like unknown ones (404, left out of lists, close code
1008). Ownership is unchanged: every clone, fetch, push, pull request and merge of a run uses the run owner's token.

## 5. Agent runtime

An agent is one `claude` child process. `AgentLauncher` is an interface so that the local process
launcher can later be replaced by a container- or `sudo -u`-based one.

Launch rules:
- The environment is built **from scratch** (never inherited): `PATH`, `HOME`, `LANG`, `TERM`,
  the owner's Claude credential variable, the MCP timeouts, and git auth. `HOME` is
  `$FACTORY_DATA_DIR/homes/<username>` so Claude session files are per user.
- MCP timeouts: `MCP_TOOL_TIMEOUT` (the CLI's wall-clock limit per tool call) and
  `CLAUDE_CODE_MCP_TOOL_IDLE_TIMEOUT` (its limit for a call that sends no response or progress; default
  300,000 for HTTP servers) are both set to `max(stage limit, sandbox command limit) + 60 s`, in
  milliseconds as decimal strings (default 3,660,000), for chats and every stage, so a long sandbox command
  is never abandoned by the client while the server still runs it. Names and units were read from the
  bundled code of Claude Code 2.1.328 (the CLI may clamp the value to its own maximum). The CLI also has a
  per-request budget until an HTTP response starts (~60 s), which no variable raises: `POST /mcp` therefore
  starts a slow answer early as an event stream (§7).
- Git over HTTPS authenticates with the owner's GitHub token through environment-only config
  (`GIT_CONFIG_COUNT` / `GIT_CONFIG_KEY_n` / `GIT_CONFIG_VALUE_n` with an `http.extraheader`, or
  `GIT_ASKPASS`). The token is not written into `.git/config` or the remote URL. `GH_TOKEN` is set
  too so `gh` works. The same config rewrites the host's SSH URLs to HTTPS (`url.<base>.insteadOf`, §5 "Submodules").
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
- The **advisor** (§6 "Conversation at needs human") is launched exactly like a chat — the same flags, kept alive
  between turns, stopped by the idle reaper, resumed with `--resume` and the same failed-resume fallback — except:
  its working directory is the run's worktree, its role prompt is `advisor`, its MCP grant is the stage tools
  (bound to the run), and its first stream-json message of every launch starts with a `<run_context>` block rebuilt
  from current data (over stdin, never in argv). It has no `/factory` commands, no attachments and no stage time
  limit.
- Each stdout line is one JSON object. Complete messages are persisted as events; partial
  `stream_event` deltas are broadcast live but not persisted.

Workspaces: `$FACTORY_DATA_DIR/workspaces/<username>/<repo>/main` is the owner's clone used by
chat. A pipeline run works in a git worktree `…/<repo>/runs/<runId>` on its own branch
`factory/issue-<number>-<slug>-<run>`; a release run (§6 "Releases") on
`factory/release-<name>/issue-<number>-<slug>-<run>`, where `<run>` is the first 8 hex digits of the run id. How
worktrees and branches are cleaned up is in §6 "Worktrees and branches".

The main clone is kept in step with `origin/<default branch>` (GitHub's current default branch, cached 60 s) so a chat
agent does not read and report old code (`Workspaces.syncMain`, `CliGitOps.syncCheckout`). A sync runs in the
background when a chat is created (after `ensureMain`) and before every non-command chat message (inside the chat
lock, after the process is obtained); `/factory` commands, advisors and pipeline stages never sync, and run worktrees
are unaffected. Under the clone's lock it fetches only `+refs/heads/<b>:refs/remotes/origin/<b>` (`--no-tags`, 30 s
timeout; skipped when the clone was fetched by `ensureMain` or a sync less than 30 s ago; a failed fetch is logged and
the last fetched ref is used), then reads the branch, `HEAD`, ahead/behind relative to `origin/<b>` and
`git status --porcelain` (untracked, non-ignored files count as local changes). Not behind → `current`, nothing is
touched whatever the branch or local state. Behind → the checkout is left exactly as it is when another branch or a
detached HEAD is checked out (`other_branch`), it has local changes (`local_changes`) or local commits
(`local_commits`); otherwise `git -c core.hooksPath=/dev/null merge --ff-only --quiet origin/<b>` (`updated`; a failure
is `behind` / `update_failed`). That merge is the only state-changing command; reset, checkout, stash, clean, pull and
rebase are never run. In fake-GitHub mode nothing is fetched or merged and the state is `current`. A sync never fails a
chat: errors are logged and the message goes out unchanged. After a sync with a result the server publishes a
`checkout` event (§8) unless the payload equals the last one it published for that chat, and prepends a
`<checkout_status>` block to the content delivered to the agent (never to the `user` event): always after a
fast-forward (old and new short sha, number of new commits, "read files again"), and when behind once per process
launch and again whenever reason, `HEAD` or the count changes (how far behind, why, how to read `origin/<b>`, and not
to update the checkout without asking). `prompts/chat.md` explains the block to the agent.

Submodules: whenever a checkout has a `.gitmodules` file at its root, `git submodule update --init --recursive` runs
with the owner's git authentication — after a fresh clone (`ensureClone`), after a successful fast-forward in
`syncCheckout`, and in every worktree a run creates (the run's worktree; for a workspace run its root, target and
context members). A failure never fails the operation: clones and syncs log it, and for a run the spec-writer's and
builder's transcripts start with a `warn` event ``Submodules of `<directory or repo>` could not be fetched: <last 300
characters of git's output>``. No protocol restriction is relaxed (never `protocol.file.allow=always`); in fake-GitHub
mode submodules are skipped. A workspace run's submodule target is left out of the root's update
(`-c submodule.<name>.update=none`, §5 "Workspace projects"). Agern has no SSH key, so wherever it sets up git (agents and server-side git, with or
without a token) SSH URLs of the git host (`git@github.com:org/repo.git`, `ssh://git@github.com/org/repo.git`) are
rewritten to its HTTPS base through two `url.<base>.insteadOf` entries in the `GIT_CONFIG_*` environment. Agern pushes
only a run's own branch, never a submodule: how it keeps a run from merging a pointer to a submodule commit that was
never pushed, and from losing such commits, is §6 "Submodule pointers".

### Workspace projects

A project is a **workspace** when its repository has `.agern/workspace.yml` at the root of its default branch, or —
without that file — a `.gitmodules` there with at least one submodule of the organization: a set of member
repositories of the same organization that belong together (an application plus agents, plugins, tooling), checked
out side by side; issues live in the workspace repository. Without either nothing changes.

```yaml
members:
  - directory: app            # required; where the member is checked out, relative to the workspace root
    repository: shop-app      # required; a repository of the configured organization (`<name>` or `<org>/<name>`)
    branch: dev               # optional base branch; default: the member repository's default branch
    label: app                # optional issue label; default: the repository name
```

`WorkspaceDefinitions.parse` validates strictly and lists every problem with its path (e.g. `members[1].branch: …`):
at most 64 KiB, no anchors/aliases or duplicate keys, no unknown keys, 1–50 members; `directory` 1–4 `/`-separated
segments of `[A-Za-z0-9._-]{1,100}`, none `.`, `..`, `.git`, `.agern`, `.agern-run` or `.claude` (case-insensitive),
none equal to or a prefix of another member's; `repository` a bare name or `<org>/<name>` of the configured
organization, not the workspace repository, unique; `branch` `[A-Za-z0-9._/-]{1,200}`, not starting with `-`, `/` or
`.`, not ending with `/`, `.` or `.lock`, without `..` or `//`; `label` trimmed, 1–50 characters, no control
characters, unique ignoring case (defaults included), not `needs-human`, `bug`, `enhancement` or `feature`. The file
is read through the contents API with the acting user's own token, after the visibility check, and cached 60 s per
user and repository (`WorkspaceService`). A member's effective base branch is `branch`, or its default branch as
that user's token sees it. An invalid file leaves the project usable for chats as a single repository, but no run can
start (409 `workspace_invalid`). Agern never runs a setup script from the repository; it clones the members itself.

**Workspaces from git submodules.** `.gitmodules` is read like the file (contents API, the user's own token, at most
64 KiB, cached with it) and parsed as git config: `[submodule "<name>"]` sections with `path`, `url` and `branch` (keys
case-insensitive, quoted values, escapes, `#`/`;` comments, continuation lines; the last value wins; sections without
`path` or `url` are ignored). A URL names a repository of the organization when it is
`https://<host>/<org>/<repo>[.git]` (also `http`/`git`, with a user or port), `git@<host>:<org>/<repo>[.git]`,
`ssh://git@<host>[:port]/<org>/<repo>[.git]`, or relative to the workspace repository (`../<repo>.git`,
`../../<org>/<repo>.git`); `<host>` is the configured git host, `<org>` matched ignoring case. Without
`.agern/workspace.yml` every such submodule is a **member**: directory = its path, repository = the one its URL names,
base branch = its `branch` (`.` = the workspace's default branch; an invalid name is ignored), else the member's
default branch, label = the repository name. A submodule elsewhere (another host or organization, an unresolvable URL)
or one that cannot be a member (the workspace repository itself, an invalid directory, a reserved or too long label, a
repository listed twice, a path inside another member's) is **context only**: fetched with the workspace like any
submodule, never a run's target. `GET /api/projects/{repo}` reports `path: ".gitmodules"` and the context-only paths.
When `.agern/workspace.yml` exists it wins; a member whose `directory` is a submodule's path is that submodule (its
`repository` must be the one the URL names, else ``members[i].directory: '<d>' is the submodule <url> in .gitmodules,
not the repository <org>/<repo>``; without a `branch` it takes the submodule's), the other submodules are context only.

For a **submodule member** the submodule checkout is the member's clone: `main/<directory>` is not cloned by Agern
but initialised with ``git submodule update --init -- <directory>`` when it is not checked out yet, recorded in
`members.json` (with `"submodule": true`) and never written to `info/exclude` (it is tracked by the workspace
repository). It follows the commit the workspace pins (chat syncs fast-forward the workspace repository, whose
submodule update moves it); it is not synced against its base branch and has no `<checkout_status>` member line. In
fake-GitHub mode, where the workspace clone is a local repository without submodules, a local repository stands in
for it, as for a cloned member.

**Member labels.** Issues are routed by label, so Agern makes sure every member's label exists in the workspace
repository (both kinds of workspace), with the acting user's own token (`MemberLabels`). When `GET
/api/projects/{repo}` builds the workspace, the repository's labels are listed (`GET /repos/{org}/{repo}/labels`,
paged) and each member label missing there (compared ignoring case) is created: `POST /repos/{org}/{repo}/labels`
with name = the label, a stable colour (the first three bytes of the SHA-256 of the lower-case name, each ×¾ so white
text stays readable) and the description "Runs change the member <directory> (<repository>)" (at most 100
characters). 422 `already_exists` counts as there; 403/404 (the token may not create labels) and other errors are
logged and the label stays missing. Existing labels are never renamed or recoloured. This happens at most once per
user, repository and definition (member labels, directories and repositories) within 5 minutes; the result is
cached with it, and a failure (even to list the labels) never fails the page (no warning then). The labels still
missing are `Workspace.missingLabels`; the project page shows them with a "Create missing labels" button, which calls
`POST /api/projects/{repo}/workspace/labels` (any user who can see the project, as for creating an issue; GitHub
decides with the caller's token): the same listing and creation, now regardless of the cache, answering
`{ created, missing }`; 403 `labels_forbidden` "Your GitHub token cannot create labels in <org>/<repo>: <labels> are
still missing." when GitHub refused and nothing was created; 409 `not_a_workspace` or `workspace_invalid`. The fake
GitHub lists the labels its issues carry plus those created, and refuses to create labels in a repository while
`<dataDir>/fake-github/<repo>/.agern-fake/labels-readonly` exists (for the end-to-end test).

Layout under `workspaces/<user>/<workspace repo>/`:

| Path | Content |
|---|---|
| `main/` | clone of the workspace repository (as for every project) |
| `main/<directory>/` | a full clone of each member the user's token can read, on local branch `<base>` when cloned |
| `main/<submodule directory>/` | a submodule member: the workspace clone's own submodule checkout, at the pinned commit |
| `members.json` | the member directories Agern has cloned or uses as submodule clones (directory, repository, submodule), for clean-up |
| `runs/<runId>/` | detached worktree of the workspace clone at `origin/<default branch>`: the agents' working directory |
| `runs/<runId>/<target directory>/` | worktree of the target member's clone on the run branch, from `origin/<base>` (also for a submodule target: never the pinned commit) |
| `runs/<runId>/<other directory>/` | detached worktree of each other readable member's clone at `origin/<base>`; a submodule member is the root's own submodule checkout at the pinned commit |

The root's submodules are fetched as everywhere, except a submodule target (left out with
`-c submodule.<name>.update=none`, so its directory stays the empty gitlink directory git creates); the target's
worktree is then added there from `main/<directory>` (an empty directory is not "occupied"). A context submodule
member whose fetch failed falls back to a detached worktree of its clone at `origin/<base>`.

- Members the user cannot read are left out of their checkouts. A member whose directory is occupied (it exists and is
  not Agern's clone of that repository, or a symbolic link of the workspace repository is in the way), whose clone
  fails or whose base branch does not exist is left out with a warning (a failed clone's directory and `members.json`
  entry are removed again); if it is a run's target, the run fails at
  set-up with that reason.
- Member directories are written once each as `/<directory>/` to `main/.git/info/exclude`, so the workspace clone
  (and its worktrees) never report them as changes. Every clone, sync, worktree and clean-up operation of a workspace
  runs under the workspace clone's lock. No shallow or partial clones: a member is cloned once per user and
  workspace, runs add worktrees.
- **Chats:** creating a chat and starting its process clone the readable members (existing clones are left alone).
  Before every non-command message each member clone is synced against `origin/<base>` with the rules above
  (fast-forward only on `<base>`, clean, without local commits). The `checkout` event and the header badge describe
  the workspace repository only; when members were fast-forwarded or are behind, the `<checkout_status>` block (added
  to the workspace's own when both apply) carries one line per such member, ``Member `<directory>` (<repository>): ``
  followed by the usual sentence, delivered only when the member lines differ from what the process was last told.
  The chat's system prompt lists the members (directory, repository, base branch, label) and those left out, with why.
- **Runs:** every stage and the advisor work in `runs/<runId>/`; the other members and the workspace root are
  read-only by instruction (detached, never pushed, discarded with the run). Runs read other members at their base
  branches only, never unmerged work.
- **Clean-up:** when a run ends, and in both reapers, every member worktree of the run (found through `members.json`,
  whose entries are re-validated) is removed from its member clone (the usual `keepUnpushed` rule for the local
  branch) before the root worktree and the directory. For a submodule target that clone is `main/<directory>`, so an
  unpushed run branch stays there (in the workspace clone's `.git/modules/…`) when the run worktree goes. A context
  submodule checkout of the root is not a worktree of the member's clone: it is left for the root's removal (and its unpushed commits for the root's bundling, §6 "Submodule pointers"). A member removed from the definition keeps its clone on disk;
  it is no longer synced, listed or checked out for new runs.
- **What agents are told:** `Prompts.system` replaces "Your working directory is a git checkout of it." with "This
  project is a workspace: your working directory is a checkout of the workspace repository `<org>/<repo>`, and its
  member repositories are separate git repositories in sub-directories. Issues live in the workspace repository." The
  task prompts of a workspace run carry the workspace block (§6 "Workspace runs").

**Known limitation (v1).** All agents run under the server's OS user with permissions bypassed.
The factory must run on a dedicated machine or VM. Per-user OS accounts or containers are the
planned hardening and are why `AgentLauncher` is an interface. Sandboxes (below) do not change this:
the agent process still runs on the host.

### Claude usage

Every agent runs on its owner's Claude credential, so each user can see their own usage (`GET /api/me/usage`, §8;
the top-bar indicator and Settings → "Models & cost", §10). Two things are recorded per user, private to that user:

- **Limit windows** (`claude_usage_windows`): the CLI emits a `rate_limit_event` whenever a subscription limit
  changes, `{ type: 'rate_limit_event', rate_limit_info: { status, rateLimitType?, utilization?, resetsAt?,
  unifiedWindows?: { <type>: { utilization, resetsAt, status? } }, … } }` (shape from the Claude Code 2.1.328
  schema, not from a captured event; `utilization` is a fraction that may exceed 1, `resetsAt` epoch seconds).
  Every such event of a chat or stage is parsed tolerantly (`RateLimitWindows`): the top-level fields describe the
  currently limiting window, each `unifiedWindows` object is one window, the per-window values win and `rejected`
  from either side wins; window types must match `^[A-Za-z0-9_.-]{1,64}$` (unknown types are kept), statuses are
  strings of at most 40 characters, anything else is dropped, at most 20 windows per event and per user. Only the
  latest value per `(user, window type)` is kept: status and observation time are replaced, a reset time only by a
  known one, and a missing utilization keeps the stored one only while the reset time is unchanged. Windows are
  recorded only while the owner's current credential is the OAuth token the process was launched with (checked in
  the SQL upsert), so API-key sessions and agents still running on a replaced or removed credential record nothing.
  Replacing or removing the credential clears the windows (in the same transaction). The values are only refreshed
  while one of the user's agents runs: Agern never polls Anthropic.
- **Spend** (`claude_spend_daily`): on every `result`, the increase of the process's cumulative `total_cost_usd`
  since its previous `result` is added to the owner's total of the current UTC day — for both credential kinds, chats
  and stages. It is kept when a chat is closed or the credential changes. Sessions from before this ledger are
  counted on the UTC day they were created.

A malformed event or a failed write is logged and ignored; it never fails the session, and the event is still part
of the transcript. After a recorded change the owner's feed connections get a data-less `usage` frame (§8).

**Software installation.** Global setting `agentsMayInstallSoftware` (default `false`). When
false, agents are instructed not to install system-wide software and are given the MCP tool
`factory_request_software`, which files a request an admin handles manually. When true the
instruction is dropped.

### Sandboxes

A project can describe a container in which its builds and tests run: its own toolchain, sidecar
services such as a database, resource limits and a clean environment, without an admin installing
anything on the host, and test on more than one image (*platforms*). This is issue #25's "model (a)":
**the agent process keeps running on the host as before** and gets MCP tools that run commands inside
its run's sandbox. The spec-writer plans the sandbox's use (a *Verification* section in the spec), the
builder runs commands in it, and the `ux` and `ui-design` reviewers get a separate, read-only *review
sandbox* in which they start the app (below). Project authors: [SANDBOXES.md](SANDBOXES.md).

**What a sandbox is not (yet).** It is *not* a security boundary against the agent. The agent runs
with permissions bypassed as the same OS user that owns the rootless containers, so it can reach
everything that user can (including `podman` itself, the other runs' worktrees and the data
directory). Containing the agent itself — running `claude` inside the sandbox — is a later release.
What the server does guarantee is that the *definition* cannot widen the container's boundary: the
server alone chooses mounts, user mapping, capabilities and network (below), and a definition is
only used after an admin approved its exact content.

**Runtime.** Rootless Podman driven through its CLI (`FACTORY_SANDBOX=podman`), never rootful,
never Docker. With `FACTORY_SANDBOX_PODMAN_URL` every call is `podman --url <url> …` so a packaged
setup points the server at the `agern-sandbox` unit's `podman system service` socket (§9 "Sandboxes"). Every
sub-command and option below exists in Podman 4.3 (Debian 12), the oldest supported version. The runtime is an interface
(`SandboxRuntime`); tests use an in-memory fake.

**Definition: `.agern/sandbox.yml`** at the repository root, read from the base branch:

```yaml
image: docker.io/library/eclipse-temurin:21-jdk     # required; fully qualified, from an allowed registry
setup: |                                             # optional shell script, run once when the sandbox is created
  apt-get update && apt-get install -y nodejs npm
env: { CI: "true" }                                  # optional environment of the sandbox
services:                                            # optional sidecars (at most 5), reachable by name
  postgres:
    image: docker.io/library/postgres:17
    env: { POSTGRES_PASSWORD: dev }
    ready:                                           # optional readiness check, run inside the service
      command: pg_isready -h 127.0.0.1 -U postgres
      timeoutSeconds: 60                             # 1–600, default 60
caches: [ /root/.gradle, /root/.npm ]                # optional (at most 10): per-project named volumes
resources: { cpus: 2, memory: 4g }                   # optional, capped by FACTORY_SANDBOX_MAX_CPUS / _MAX_MEMORY
user: builder                                        # optional: commands run as this user (name or uid), not root
platforms:                                           # optional (at most FACTORY_SANDBOX_MAX_PLATFORMS): other images
  jdk17:
    image: docker.io/library/eclipse-temurin:17-jdk  # validated like `image`
    setup: |                                         # optional; without the key the top-level setup runs,
      apt-get update                                 # an empty `setup:` runs none
```

Validation is strict and lists every problem with its path (`services.db.ports: unknown key …`):
unknown keys at any level, an image without a registry (`debian:12`) or from a registry not in
`FACTORY_SANDBOX_REGISTRIES`, malformed image references, service names that are not DNS labels
(lower-case, start with a letter, ≤ 31 characters; not `localhost`, `sandbox`, `workspace`, `host`,
`gateway`), invalid environment names, cache paths that are not absolute, contain `..`, overlap, or lie
in `/workspace` or a system directory, non-positive CPUs, memory below `64m`, platform names that are not
DNS labels or are `default` (the top-level image's name), more platforms than allowed, a readiness check
without a command or with a timeout outside 1–600 s, and a `user` that is root (`0`, `root`), a uid
outside 1–60000 or not a POSIX-style name (`[a-z_][a-z0-9_-]{0,31}`). Definitions written before these
keys existed are unchanged: without `platforms`, `user` and `ready` everything behaves as before. YAML anchors/aliases and
duplicate keys are rejected; the file may be at most 64 KiB and `setup` 32 KiB. No Containerfile
builds in this step (an image plus a setup script only). The image needs a POSIX `sh`.

**Approval by content hash.** A definition is used only if an admin approved exactly that content:
the SHA-256 of the file's bytes, stored per repository with who approved it, when, and the approved
content (table `sandbox_approvals`, one row per repository; approving another version replaces it).
Admins approve what they saw (the request carries the hash; a file that changed meanwhile is refused)
and can revoke. Approvals can be given while `FACTORY_SANDBOX=off`.

**The run's decision.** When a run has created its worktree, the server reads `.agern/sandbox.yml` with
git from the run's base commit (`origin/<default branch>` as fetched), validates it and compares its hash
with the approval. The decision is stored on the run (`Run.sandbox`, below) and never changes during the
run. Because the file comes from the base commit, a run cannot approve its own change to it: a run that
edits `.agern/sandbox.yml` still uses the approved base version, and the changed file needs a new
approval after it is merged. A **release run** (§6 "Releases"; whether the release was started by hand or by the
scheduler makes no difference) starts from its release branch but reads the
definition from the default branch (`origin/<default branch>`, fetched with the owner's clone just before), not from the
release branch: earlier runs of the release squash-merge into that branch without a human, and approvals are always of
the default branch's file (the sandbox page and the approval endpoint read it there), so a definition changed inside
the release could never be approved and would silently cost the rest of the release its sandbox. Nothing merged into a
release branch can therefore change the sandbox of the release's later runs; a release that needs another toolchain gets
it through a change to the default branch and a new approval. With no file, an invalid one or an unapproved one the run proceeds
**without** a sandbox, exactly as before; it does not fail. The spec-writer's and the builder's transcripts start with a
`system` event saying why (`warn`), or which image it has (`info`), and their task prompts say the same in
one paragraph (the spec-writer's: plan the sandbox's use in the spec's Verification section, or — without one — do not
ask for a sandbox and write no Verification section that needs one). Whether a project has a sandbox stays an admin's
decision: no tool of any agent can create, change, approve or revoke a definition or an approval.

**Who gets the tools.** Only when `FACTORY_SANDBOX` is not `off`; with it off no stage gets a sandbox tool,
note or prompt paragraph.

| Stage | Tools (§7) | When |
|---|---|---|
| spec-writer | `factory_sandbox_info` only (`access: "plan"`) | always (also for a run without an approved definition: it then says why there is none) |
| builder (build and fix rounds) | info, exec, wait on the **build sandbox** | always; without an approved definition they answer that there is none |
| `ux`, `ui-design` in pipeline review rounds | info, exec, wait on the **review sandbox** only | the run's decision is `approved` |
| `security`, `code`, approver, advisor, chats, follow-up reviewers at `needs_human`, personal tokens | none | — |

The server fixes a token's *sandbox scope* (`plan`, `build`, `review`; `McpGrant.sandboxScope`) when it issues the
grant; tool arguments never choose it. A build-scoped call can only address the build sandbox of the token's run, a
review-scoped call only its review sandbox, and a plan-scoped `exec` or `wait` is refused. Command handles are looked
up only in the sandbox of the token's scope and run, so a reviewer can never wait for (or reach) a builder's command,
nor an agent another run's. This is what the tools guarantee: no reviewer *tool call* reaches the builder's
read-write container. It is not a boundary against an agent process on the host (see above). A sandbox for the `code`
reviewer, the approver or follow-up reviews is possible later.

**Lifecycle.** One sandbox per run, created lazily by the builder's first `factory_sandbox_exec` and
shared by all builder rounds of the run. Creation, in order: a private network `agern-run-<runId>`; one
container per service, `agern-run-<runId>-<service>`, with the service name as its network alias; one
named volume per cache, `agern-cache-<repo>-<first 12 hex of SHA-256(path)>` (kept across runs, per
project); the sandbox container `agern-run-<runId>`; the services' readiness checks (below); with `user`,
the account is prepared (below); then the `setup` script (`sh -lc`, as root, time limit as for commands).
A failed creation, readiness check or setup removes what was created and frees the slot; the tool answers
with the error (and the setup output's tail), and a later call tries again. Everything carries the labels
`agern.run=<runId>`, `agern.repo=<repo>` and `agern.role=sandbox|platform|service|network` (platform
containers also `agern.platform=<name>`). The sandbox is
removed (all containers and network, never the cache volumes) as soon as no builder round can follow: before
the approver starts, before the run goes to `needs_human`, and before it is marked `failed` (stage error,
stage time limit) or `cancelled`. A `needs_human` run keeps its worktree (§6 "Worktrees and branches") but has no
sandbox, and nothing creates one again: only a builder or a pipeline review round can, and none follows. Names and
labels use the run id, never the run's branch name.

*Sandbox before worktree.* The sandbox bind-mounts the run's worktree, so its containers are always removed
before the worktree is deleted: the worktree clean-up of every ended run (merged, failed, time limit, cancelled,
also from `needs_human` or through a release) first removes the run's sandbox if one is left, and so does each
worktree reaper for every run it reaps. At server start the order is: unfinished runs are marked `failed`; then
the sandbox reaper removes containers and networks labelled `agern.run` whose run is not active (platform
containers and services included, since they carry the same label; synchronously, at most 2 minutes, so a hanging
Podman cannot keep the server from starting); then the worktree reaper removes the worktrees of runs that are not
live; only then do releases resume and the scheduler start, which may start new runs. A best-effort removal of
every sandbox also runs at shutdown.

**Platforms.** `platforms:` names other images the builder can run commands in, e.g. another runtime
version or distribution (issue #25's "test on different platforms"). `factory_sandbox_exec` with
`platform: <name>` runs in that platform's container, `agern-run-<runId>--<name>` (the `--` cannot clash
with a service's container, whose name starts with a letter), created on its first command exactly like
the sandbox container: the same worktree mount at `/workspace`, environment, `user`, limits and network
(so the same services, reached by the same names; a platform container is not given a network alias).
Its setup is the platform's own `setup`, or the top-level one when the platform has no `setup` key.
Caches are **per platform**: the same paths, in volumes `agern-cache-<repo>-<first 12 hex of
SHA-256(<platform> NUL <path>)>`, because another image's tools (another JDK, another libc, other native
npm modules) cannot be assumed to share a cache safely. `platform` omitted or `default` is the top-level
image. Commands stay one at a time per run, whichever container they run in.

**Review sandbox.** The `ux` and `ui-design` reviewers of an approved run start the app in a container of their own,
so an app that needs the project's toolchain or its database can be looked at and screenshotted (§6 "Screenshots"):

- *Shape.* One per run at a time, built from the run's approved definition: the default image only (no platforms), its
  own network `agern-review-<runId>`, its own service containers `agern-review-<runId>-<service>` (service name as
  network alias, readiness checks as above) and the command container `agern-review-<runId>`, with the same
  environment, `user`, limits, security options and default cache volumes as the build sandbox's default container.
  Labels: `agern.run`, `agern.repo` and `agern.role=review|review-service|review-network`.
- *Mounts.* The run's worktree is bind-mounted **read-only** at `/workspace` (still the working directory): every write
  below it fails. The only writable host directory is `<run directory>/.agern-run/review/` (the agents' working
  directory, i.e. the workspace root of a workspace run), mounted at `/run/agern-review` — `/run` cannot hold a cache
  path, so it never collides with one. The server creates it git-ignored and never through a symlink
  (`ImageService.ensureRunDir`, `SafeFiles`); when it cannot, the sandbox is made without it and
  `factory_sandbox_info` says so.
- *Creation and sharing.* Lazily, by the first review-scoped `factory_sandbox_exec` of a round, in the same order as
  the build sandbox (network, services, cache volumes, container, readiness checks, `prepare-user.sh`, setup as root).
  After setup only the user's home is handed over (`/workspace` is read-only). Both UI reviewers of the round use the
  same container — two first calls create it once — and commands stay one at a time: foreground calls queue, a
  background command refuses the others. Reviewers start a long-running app detached inside a foreground command with
  all streams redirected (`nohup … > /tmp/app.log 2>&1 < /dev/null &`), which `podman exec` returns from. A failed
  creation, readiness check or setup removes what was created and frees the slot; a later call tries again.
- *Logs.* Lifecycle messages and every command's full log go to the transcript of the reviewer that made the call
  (`payload.sandbox.review: true`, text starting `Review sandbox`); the setup log to the one that triggered creation.
  The first successful creation sets `Run.sandbox.reviewUsed`.
- *Removal.* When every reviewer of the round has ended — also when one failed, timed out or the run was cancelled —
  and so before the next builder round, the approver or `needs_human`; and by every path that removes the build
  sandbox (`release`, worktree clean-up, reapers, shutdown, an admin's stop). Containers and network go, never cache
  volumes, and `.agern-run/review/` is deleted. A running command dies with it; its waits end with an error. Each
  round creates a fresh one, so a fix round never looks at a stale app. Once the run's sandbox was released, no review
  sandbox can be created for it (a creation racing the release ends with everything removed and its slot freed). At
  server start the reaper removes every `agern-review-*` container and network it finds: a review round never
  survives a restart.

**Readiness of services.** A service may name a `ready.command`; after starting the services the server
runs it with `sh -c` inside the service's container (`exec --workdir /`, each attempt at most 10 s) once
a second until it exits 0, for at most `ready.timeoutSeconds` (default 60). Commands run only after every
check passed. When one does not pass in time, the sandbox is removed and the tool answers — and the
transcript says — which service, the check's last exit code and output, and the service's last 30 log
lines (`podman logs --tail 30`). Checks run when the services are created, not again for a platform
container that joins them. A service without a check behaves as before, and `factory_sandbox_info`
tells the agent that it may still be starting.

**A non-root user.** By default commands run as root inside the container, which rootless Podman maps to
the service user on the host. Some tools refuse to run as root (PostgreSQL's `initdb`, used by embedded
PostgreSQL test libraries). With `user: <name>` (uid 1000) or `user: <uid>` the command containers start
with `--userns keep-id:uid=<n>,gid=<n> --user 0:0`: inside the container's own user namespace the service
user is uid/gid *n* (so the worktree and what commands write there stay the service user's on the host),
container root is the first subordinate id, and the keep-alive process and setup run as that root. Before
setup the server runs `resources/sandbox/prepare-user.sh` as root (POSIX `sh`, busybox is enough): a name
the image has with uid *n* is used; a missing one is added to `/etc/passwd` and `/etc/group` with home
`/home/<name>`; a uid the image has is used under the image's name, a free one becomes `sandbox`; a name
with another uid, or uid *n* taken by another name, fails with a message saying what to write instead.
It creates the home directory and gives the user the home, every cache directory and the cache paths'
parents inside the home. After setup, files setup left in the home or `/workspace` that do not belong to
the user are handed to it (`find <home> /workspace -xdev ! -user <n> -exec chown -h`; `-xdev` keeps it out
of the cache volumes). Commands then run with `exec --user <n>:<n>` and `HOME`, `USER`, `LOGNAME` set.
`keep-id` with `uid`/`gid` exists since Podman 4.3; with explicit `uid`/`gid` Podman adds no
`/etc/passwd` entry of its own. Never `--userns=host`. The first container of an image with a given
mapping can take longer to start: rootless storage may copy the image's layers once for that mapping.

**Containers.** The server decides every option; the definition only supplies images, environment,
services and their readiness commands, cache paths, setup, the user and limits within the caps. Never
`--privileged`, never host network/pid/ipc/userns, no added capabilities (Podman's default rootless set), no devices,
`no-new-privileges`, a PID limit of 4096, `--memory-swap` equal to `--memory`. The run's worktree is
bind-mounted read-write at `/workspace` (the working directory); nothing else from the host is mounted (the review
sandbox: the worktree read-only, plus its output directory, above).
Inside the rootless user namespace the container's root is the service user — with `user`, the user's uid
is — so files the build writes in `/workspace` belong to that user on the host. Limits apply per container
(sidecars and platform containers get the same limits as the sandbox). The exact command lines (`podman` or
`podman --url <url>` first):

```
network create --label agern.run=<id> --label agern.repo=<repo> --label agern.role=network agern-run-<id>
volume exists <volume>  ;  volume create --label agern.repo=<repo> --label agern.cache=<path> <volume>
run --detach --name agern-run-<id>-<svc> --label agern.run=<id> --label agern.repo=<repo> --label agern.role=service
    --label agern.service=<svc> --network agern-run-<id> --network-alias <svc> --security-opt no-new-privileges
    --pids-limit 4096 --cpus <cpus> --memory <mem> --memory-swap <mem> --stop-timeout 5 --pull=missing
    [--cgroup-parent <FACTORY_SANDBOX_CGROUP_PARENT>] [--env K=V …] <service image>
run --detach --name agern-run-<id> --label agern.run=<id> --label agern.repo=<repo> --label agern.role=sandbox
    --network agern-run-<id> --security-opt no-new-privileges --pids-limit 4096 --cpus <cpus> --memory <mem>
    --memory-swap <mem> --stop-timeout 5 --pull=missing [--cgroup-parent <FACTORY_SANDBOX_CGROUP_PARENT>]
    --workdir /workspace --entrypoint sh [--env K=V …]
    --mount type=bind,source=<worktree>,target=/workspace [--mount type=volume,source=<volume>,target=<path> …]
    <image> -c "trap 'exit 0' TERM INT; while :; do sleep 3600 & wait $!; done"
run … --name agern-run-<id>--<platform> … --label agern.role=platform --label agern.platform=<platform>
    …(as the sandbox container, with the platform's cache volumes)… <platform image> -c "<keep-alive>"   # platform, on first use
    # with `user: <n>`, both kinds also get, after --network:  --userns keep-id:uid=<n>,gid=<n> --user 0:0
network create --label agern.run=<id> --label agern.repo=<repo> --label agern.role=review-network agern-review-<id>
run … --name agern-review-<id>-<svc> … --label agern.role=review-service --label agern.service=<svc>
    --network agern-review-<id> --network-alias <svc> …(as a service)… <service image>        # review sandbox services
run --detach --name agern-review-<id> --label agern.run=<id> --label agern.repo=<repo> --label agern.role=review
    --network agern-review-<id> …(limits, user mapping as the sandbox container)… --workdir /workspace --entrypoint sh [--env K=V …]
    --mount type=bind,source=<worktree>,target=/workspace,readonly=true
    [--mount type=bind,source=<run dir>/.agern-run/review,target=/run/agern-review]
    [--mount type=volume,source=<volume>,target=<path> …] <image> -c "<keep-alive>"      # review sandbox
exec --workdir / agern-run-<id>-<svc> sh -c <ready.command>                                      # readiness check, until exit 0
logs --tail 30 agern-run-<id>-<svc>                                                              # when it never passes
exec --workdir /workspace <container> sh -c <prepare-user.sh> agern-user <n> <name or ''> [<cache path> …]   # with `user`, as root
exec --workdir /workspace <container> sh -c '{ echo 1000 > /proc/self/oom_score_adj; } 2>/dev/null; exec sh -lc "$1"' sh <setup>   # as root
exec --workdir /workspace <container> sh -c 'uid=$1; shift; find "$@" -xdev ! -user "$uid" -exec chown -h "$uid:$uid" {} + 2>/dev/null || true' agern-hand-over <n> <home> /workspace   # with `user`, after setup
exec [--user <n>:<n> --env HOME=<home> --env USER=<name> --env LOGNAME=<name>] --workdir /workspace <container>
    sh -c '{ echo 1000 > /proc/self/oom_score_adj; } 2>/dev/null; exec sh -lc "$1"' sh <command>   # every command
restart --time 0 <container>                                       # after a command timed out
rm --force --ignore --volumes agern-run-<id> [agern-run-<id>--<platform> …] [agern-run-<id>-<svc> …]   # anonymous volumes (an image's VOLUME) too
rm --force --ignore --volumes agern-run-<id>--<platform>           # a run's idle container, to free its slot (below)
network rm --force agern-run-<id>
rm --force --ignore --volumes agern-review-<id> [agern-review-<id>-<svc> …]  ;  network rm --force agern-review-<id>   # end of a review round
ps --all --filter label=agern.run --format '{{.Names}}	{{index .Labels "agern.run"}}'          # reaper
network ls --filter label=agern.run --format json                                               # reaper (name, labels)
image inspect --format '{{json .Config.ExposedPorts}}' <service image>                          # ports for factory_sandbox_info
```

The podman process gets an environment built from an allow-list (`PATH`, `HOME`, `USER`, `LOGNAME`,
`XDG_RUNTIME_DIR`, `XDG_CONFIG_HOME`, `XDG_DATA_HOME`, `DBUS_SESSION_BUS_ADDRESS`, `TMPDIR`), never the
server's secrets.

**Commands.** `factory_sandbox_exec` runs `sh -lc <command>` in `/workspace` of the default or the named
platform's container, as root or the `user` (first raising its own
`oom_score_adj` to 1000, so that a command over the memory limit is what the kernel kills — never the
sandbox's keep-alive process, which real Podman showed can otherwise happen when the command's memory is
not freed before a second OOM, ending the whole sandbox), one command at a time per run
(concurrent calls wait). Its time limit is `timeoutSeconds` if given, never more than
`FACTORY_SANDBOX_EXEC_TIMEOUT_MINUTES` and the stage time limit. A command that runs out of time is
stopped and its container restarted (`restart --time 0`: every process in it is killed, files are
kept), and the answer says `timedOut`. The agent gets the exit code, the last 20,000 characters of the
interleaved stdout/stderr, whether that is truncated, and the duration. The full output (its last
1,000,000 characters) of every command and of the setup script is kept as a `system` event in the
builder's transcript (`payload.sandbox`, §8, with the platform), so the run page can show it; there is no
separate log store. When the definition has platforms, the run records the ones commands ran in
(`Run.sandbox.platformsUsed`) and the builder's task prompt asks it to run the test suite on every platform
and report the result per platform.

**Background commands and handles.** Validation and creation happen in the tool call; the command itself runs in a
job on a coroutine scope owned by `SandboxManager` (the application's), which completes the command's result when the
runtime's `exec` returns. A dropped, cancelled or timed-out HTTP request therefore never kills or loses a command.
Every command, foreground or background, gets a handle (`cmd-` + 8 random lower-case hex characters, unique in the run).
In the foreground the call waits for the result (concurrent foreground calls queue on the run's lock); with
`background: true` it returns the handle at once and the job releases the run's lock when the command ends. While a
background command runs, every other `exec` of the run is refused at once with its handle, and a background `exec`
is refused while any command runs. `factory_sandbox_wait` waits for a handle's result (default: the most recently
started command) without taking the lock, so any number of waits can run. A run keeps its running command and the
results (the 20,000-character tail; the full log is in the transcript) of its last 20 finished commands until its
sandbox is released; an admin stop keeps them. A background command's `system` log event goes to the session that
started it, when it ends; when the builder's stage ends without waiting, the command runs on until it ends, reaches
its limit or the sandbox is released, and a fix round of the same run can still wait for it. A release or an admin
stop ends a running command's waits at once with the same errors a foreground call gets.

**Stopped by an admin.** An admin can remove a run's sandboxes while the run is active (`DELETE
/api/admin/sandboxes/{runId}`, the admin page's "Stop"): the build sandbox's containers — platform containers and
services included — and network, and the review sandbox if it exists, are removed at once (cache volumes are kept)
and their slots are freed; 404 only when the run has neither. The run itself is not touched: a command running at that moment ends with
a tool error saying the sandbox was stopped, a `system` warn event in the transcript of each sandbox's last user (the
builder, a reviewer) says who stopped it, and the next `factory_sandbox_exec` creates a fresh sandbox (setup runs
again) like the first one did.

**Concurrency and memory.** `FACTORY_SANDBOX_MAX_CONCURRENT` counts *containers that run commands*, over all
runs: a run's sandbox container takes a slot when created and each platform container one more; sidecars
take none (they belong to the run's network, whatever container uses them). A slot is freed when its
container is removed. A review sandbox's container takes one slot too (its services none). This keeps the cap truthful about load — a run that tests on three images really
runs three containers, each with the full per-container memory limit — without starving single-image runs.
When a run needs a slot and none is free, it first removes its *own* other command containers, least
recently used first (they are idle: one command at a time per run; files in `/workspace` stay, the setup
runs again when that container is used next); a run that holds none is not queued: the tool answers at once
that the sandboxes are busy and nothing ran; the agent may try again later (its prompt tells it not to fall
back to installing a toolchain on the host). So even with a cap of 1 a run can test on every platform, one
container at a time. A review sandbox that finds no free slot first removes the same run's idle build command
containers, least recently used first — only while no build command runs or is being started (the build sandbox's
lock is free) — with the same info event; if there is still none, the reviewer is told at once to review on the host
without a sandbox (`The server's sandboxes are all in use (<n> of <n> containers). No review sandbox was created …`),
and the review stage goes on: nothing queues or blocks. The memory one run may use is the per-container limit × (its
command containers + its services, of both sandboxes); the admin overview shows that sum per run (`memoryTotal`) and
`inUse` counts slots.

**Network and credentials.** Each run's containers share a private network; sidecars are reachable by
service name only from that run's containers (and not published on the host). Outbound network access is
Podman's default (needed for package registries); access to the host's LAN is not restricted in this
step. Nothing of the owner's credentials is passed into a container — no GitHub token, no Claude
credential, no git configuration — and git does not work inside the sandbox (the worktree's `.git` points
outside the mount): agents commit on the host as before. Builds that need private package registries are
out of scope for this step.

## 6. The pipeline

```
issue → spec-writer (opus) → builder (opus) ─┬→ security  (opus)   ┐
                              ▲              ├→ ux        (sonnet) │ wait for all 4
                              │              ├→ ui-design (sonnet) │
                              │              └→ code      (sonnet) ┘
                              └── any CHANGES: resume builder (max 2 rounds)
                                             all PASS → approver (opus)
                                                          ├ APPROVE  → checks → merge to main
                                                          └ ESCALATE → needs-human ← /factory approve
   merge policy owner_approves: APPROVE and ESCALATE → needs-human ← /factory approve
   merge policy github:         APPROVE and ESCALATE → ready (pull request left open; merged or closed on GitHub)
```

"main" is the repository's default branch for a single run, and `release/<name>` for a release run (§6
"Releases"): the run's worktree starts from that branch and its pull request merges into it. A release branch
reaches the default branch only through the owner's approval of the release (§6 "Releasing").

- **spec-writer** reads the issue and the repo and writes a spec; it is posted as an issue comment. When the run
  has an approved sandbox it may plan its use in an optional `## Verification` section (between `## Tests` and
  `## Out of scope`): the commands the builder runs in the sandbox and on which platforms, and for UI changes how the
  UI reviewers start the app in the review sandbox and what they look at. It reads the sandbox with
  `factory_sandbox_info` but cannot run anything in it, and never asks for a sandbox to be enabled or approved.
- **builder** implements the spec on the run branch and commits. The orchestrator pushes the branch
  and opens a pull request after the first build. When the run has an approved sandbox (§5 "Sandboxes"),
  the builder builds and tests in it through `factory_sandbox_exec` (running the spec's Verification commands there,
  when it has some); the sandbox is removed when the build/review loop ends, before the approver runs. Its task and fix prompts name the branch the pull request merges
  into and the rule for migration versions (§6 "Database migrations").
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
  summary "Timed out: …", and the run `failed` with error "<role> failed: Timed out: …" (failure kind
  `time_limit`, §6 "Retrying a run").
  - *Warning.* When 80 % of the limit has passed, a running spec-writer or builder gets one message through the
    same stdin path as an owner's message ("Messages to running agents" below): "Message from Agern: this stage stops
    at its time limit in about <rest>. Commit the work you have now (a work-in-progress commit is fine) so nothing is
    lost. Put a hand-over note in that commit's message: what is done, what is left, and how to verify it — a later
    run may continue from your commits. Then go on if time allows." Its pickup is confirmed like an owner's message
    (a `system` info event), and the transcript gets a `system` warn event when it is sent. Reviewers and the approver
    take no messages and get no warning. The limit itself is unchanged.
  - *Per-project or per-issue limits* are not built: one global limit stays. Decision: with the warning and
    "continue from the commits" a stage that runs out of time no longer loses its work, which was the main cost of a
    limit that is too short for a large issue; a per-project override (e.g. in `.agern/`) can be added later if a real
    project needs one, and is a configuration change only (`AgentManager.runStageAgent` takes the limit from `Config`).
- **approver** answers `{ "verdict": "APPROVE" | "ESCALATE", "summary": string }`. Under the project's merge policy
  `automatic` (the default, §6 "Merge policy") `APPROVE` squash-merges the PR and closes the issue once the checks on
  the pull request's head commit allow it (§6 "Checks before a merge"), unless its database migrations collide with
  the target's (§6 "Database migrations"): then the run goes to `needs_human` instead. `ESCALATE` labels the issue
  `needs-human`, comments, and waits for the owner's `/factory approve`. Under the other policies the verdict is only a
  recommendation: `owner_approves` always waits in `needs_human`, `github` ends the run `ready` and never merges.
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
  server restart, unfinished runs — a run waiting for the network included — are marked `failed` with a clear reason
  (v1; resuming a run in place after a restart is not built); a building release then builds the interrupted issue
  again (§6 "Releases"). `needs_human` runs survive, and so do `waiting_for_checks` runs: their wait is resumed with
  its original deadline (§6 "Checks before a merge"). While the server runs, a failure that comes from the environment
  rather than the work does not fail the run: it waits and retries (§6 "Transient failures").
- **Worktrees and branches.** Rule: **every run has its own branch** (`…-<first 8 hex digits of the run id>`, §5)
  and its own worktree `runs/<runId>`, so no run ever reuses, resets or force-pushes the branch, pull request or
  worktree of an earlier run — a leftover can never collide with a new run. At most one run per issue is active
  (`queued` … `approving`, `waiting_for_checks`, `needs_human`); a second start is 409 `active_run_exists`. `ready`
  (§6 "Merge policy") is not active: the issue may get a new run.
  - *When a run ends* its worktree is removed before its final status is shown: `merged` (approver or owner
    approve) also deletes the local branch; `failed` (any stage, the time limit, no commits, set-up errors) and
    `cancelled` (also from `needs_human`, or through a release) delete the local branch unless it holds commits
    found on no other branch and no remote-tracking branch (never pushed), which are kept rather than lost. A
    `needs_human` run is still alive and keeps its worktree until it is approved or cancelled. `ready` (merge policy
    `github`) removes the worktree like an unmerged end; the pushed branch and the open pull request stay. Submodule commits
    found on no remote-tracking branch of their submodule are saved as git bundles first (§6 "Submodule pointers").
  - *GitHub:* the remote branch and the open pull request of a failed or cancelled run are left untouched — they
    may hold useful commits, and only a human decides to close them. The issue gets a comment "run … failed: <reason>"
    or "… was cancelled by …", which names that pull request. A later run of the issue opens its own pull request,
    whose body lists the earlier runs' unmerged pull requests ("close them if this one supersedes them").
  - *No commits.* When the builder makes no commits, the stage summary and the run error carry the first paragraph of
    the builder's final message (e.g. why the issue cannot be built yet), and so does the issue comment.
  - *Continuing* (§6 "Retrying a run") is the one exception to "never reuses": the continuing run's **new** branch
    and worktree start at the tip of the failed run's branch; the failed run's branch itself is not changed. A step
    retry re-creates the failed run's **own** worktree `runs/<runId>` on its own branch (the run is live again, so the
    reapers keep it).
  - *Leftovers* (e.g. after a crash or a failed clean-up) are reaped: when a run starts, under the per-clone lock,
    every worktree of that user's clone whose run is not live (no coroutine in this server and not in an active
    status) is removed — registered with git or only a directory under `runs/` — and stale entries are pruned
    (`git worktree prune`); at server start, after unfinished runs are failed and leftover sandboxes removed (§5
    "Sandbox before worktree"), the same is done for every clone on disk. The worktree of a live run, `needs_human`
    included, is never touched. A run's sandbox, which bind-mounts its worktree, is always removed first.

#### Checks before a merge

Every run merge — the approver's `APPROVE`, the owner's approval (button, `/factory approve`, `factory_approve_run`)
and the step retry of a failed merge — goes through one gate (issue #100). Single, release and workspace runs behave
alike. Decided by these rules alone, never by an agent.

1. The database migration and submodule guards run first (§6 "Database migrations", "Submodule pointers").
2. The pull request is read. Not open on GitHub (merged or closed there) or not found: `needs_human`, `failedStep`
   `merge`, "Pull request <ref> is no longer open on GitHub (<merged|closed>), so Agern did not merge it."
3. The checks on its current **head commit** are evaluated with the rules of releases (§6 "Releasing", "Checks"):
   commit statuses, check runs and GitHub Actions workflow runs; `failed` > `pending` > `passed` > `none`, and "no
   checks" is `awaiting` until the head commit has been known for `FACTORY_RELEASE_CHECKS_SETTLE_SECONDS`. For a run
   the head is first seen when Agern pushes it (the pipeline's push and the follow-up review's push record the
   worktree HEAD and the time), else at the first evaluation that sees it; persisted, so a restart does not restart
   the period.
4. By state:
   - **`passed` or `none`**: squash-merged with the merge API's expected `sha` set to the evaluated commit. GitHub's
     409 (the head moved meanwhile) evaluates the new head (step 2) instead; a merge never happens for another commit
     than the one whose checks were evaluated. Other merge failures: `needs_human` with `failedStep` `merge`, as before.
   - **`pending` or `awaiting`**: the run goes to `waiting_for_checks` (error cleared) and a job (cancelled by "Cancel
     run") evaluates again from step 1 every 30 s, on the pull request's then-current head: a new head gets a fresh
     settle period and fresh checks. Nobody approves again; it merges as soon as they pass. The run is published when
     the checks' state, commit or detail changes.
   - **`failed`**: no merge; `needs_human` with `failureKind` `checks` and `failedStep` `merge`. The reason names every
     failed check (a Markdown link when GitHub gave an `https://` URL) and the short SHA; when a conclusion is
     `startup_failure`, `cancelled`, `timed_out` or `stale` it adds "Some checks did not run to completion (for example
     a startup failure or a cancellation on GitHub's side). Re-run them on GitHub, then approve again." It goes on the
     issue (the needs-human comment) and on the pull request ("**Agern: not merged.** …").
   - **`unknown`**: a read GitHub refused (401/403/404) is `needs_human` at once (`failureKind` `checks`): "Could not
     read the checks of <ref>: <message>. The run owner's GitHub token needs read access to commit statuses, checks and
     Actions." Other read errors (5xx, I/O, also of the pull request) are waited through like `pending`.
5. **Time limit.** After `FACTORY_RUN_CHECKS_WAIT_MINUTES` (default 60) in `waiting_for_checks`, counted from the first
   transition into it of this merge attempt (a head move does not restart it), the run goes to `needs_human`
   (`failureKind` `checks`, `failedStep` `merge`): "Checks on `<sha7>` of <ref> did not finish within <n> minutes
   (<detail>). Approve again to wait again, or merge anyway on the run page."
6. A later approval or merge retry of a `needs_human` run starts again at step 1 on the then-current head; nothing of
   an earlier evaluation is reused. A `needs_human` run never starts merging by itself.

*The owner's approval* evaluates once before it answers: merged or `needs_human` as before, or the run is answered in
`waiting_for_checks` and its job goes on; the advisor ends, and a release run settles, only when that job reaches
`merged` or `needs_human`. `/factory approve` and `factory_approve_run` then answer "Run <id> is waiting for checks on
`<sha7>` (<detail>); it merges by itself when they pass." Who approved (the owner, or the approver agent) is stored, so
the merge comment is right after a wait or a restart.

*Waiting.* `waiting_for_checks` is active (one active run per issue, lists, cancel, the worktree is kept) but a restart
does not fail it: the server resumes the wait at start with the original deadline, and it does not count as in
progress for `/api/internal/activity`. The advisor and "Review again" are refused while waiting (409
`run_not_waiting`). A waiting release run keeps its issue `running`; the release builds nothing else meanwhile.

*Merge anyway.* The run's owner — nobody else, admins included — can merge although the checks have not passed:
`POST /api/runs/{id}/merge-anyway` with `{ headSha }`, the commit whose checks they saw, from the web UI's confirmation
dialog that names the checks. There is deliberately no MCP tool, MCP argument or `/factory` command for it, so no agent
can override the checks. Allowed from `needs_human` or `waiting_for_checks` when the run has a pull request and its
recorded checks are `failed`, `pending`, `awaiting` or `unknown` (else 409 `run_not_overridable`), and only for
`headSha` equal to the recorded checks' commit (else 409 `checks_changed`); from `needs_human` the conversation gate of
an approval applies (409 `review_required` / `review_in_progress` / `agent_busy`). A waiting job is stopped first (a
run that turned out merged meanwhile is answered as is). The guards still run; the pull request is then squash-merged
with expected `sha` `headSha`, without evaluating the checks — a head that moved since is not merged (`needs_human`:
"The pull request's head changed since you confirmed; approve again to evaluate the new commit."). The decision is
recorded on the run (`checks.overriddenBy`, `checks.overriddenAt`), on the pull request ("**Agern:** @<login> merged
this pull request although its checks had not passed on `<sha7>` (<detail>).") and on the issue (the merged comment
ends "(approved by @<login>, despite checks: <detail>)").

*Untrusted.* Check names, details and URLs come from GitHub: they are escaped as Markdown text in comments and
reasons, a URL is used as a link only when it starts with `https://`, and the UI renders them as text.

#### Retrying a run

A failed run can be retried in three ways (issue #53), depending on where it stopped:

- **Retry the failed step** (`step`): the builder finished, and a mechanical step after it failed — the push, the
  pull request creation, or the merge (also the merge into a release branch). Only that step is retried, **on the same
  run**, and the pipeline goes on from there as usual (reviews, fix rounds, approver); no new spec-writer or builder.
  A failed push or pull request leaves the run `failed` with `failedStep` `push` / `pull_request`. The retry
  re-activates the run (`failed` → `queued`; error, end time and failure cleared — 409 `active_run_exists` when the
  issue got another active run meanwhile), sets its skipped reviewer and approver stages back to `pending`, re-creates
  its worktree on its own branch (the local branch, else the remote one, fetched) and resumes at the push of its current
  round with the stored spec and builder summary; a later fix round resumes the builder conversation of that round
  (`--resume`). The issue gets "**Agern:** retrying the failed step (<step>) of run `<id>` on branch `<branch>`.". A
  failed merge leaves the run in `needs_human` (as before) with `failedStep` `merge`; its step retry merges again — as
  the approver's decision when the approver had approved, else as the owner's — which is what the owner's approval
  also does. It goes through the same gate as the approval: changes from the conversation must have been reviewed
  (409 `review_required` / `review_in_progress` / `agent_busy`, §6 "Conversation at needs human"), and the merge
  checks the database migrations first (§6 "Database migrations") and then the checks on the pull request's head
  commit (§6 "Checks before a merge"). A migration conflict is not a failed step: it clears `failedStep`, so the run
  offers no step retry until its branch is fixed.
- **Continue from the commits** (`continue`): a **new run** whose worktree starts at the tip of the failed run's
  branch (local branch, else the remote one, fetched), on its own new branch (§6 "Worktrees and branches"). It reuses
  the failed run's spec (the spec-writer stage is `skipped` with summary "Spec reused from run <id>"; a failed run
  without a stored spec — from before this feature — gets a new one). Its builder prompt says that it continues run
  `<id>`, why that run stopped (its error), how many commits exist on top of the base, to inspect them (`git log`,
  `git diff`; a commit message may hold a hand-over note) and to finish rather than redo, to merge the base branch in
  if it moved on, and includes the previous builder's final summary when there was one. `continuesRunId` links the
  new run to the old one (both run pages show it); the pull request body says "Continues Agern run `<id>` (branch
  `<branch>`), which stopped: <error>. Its commits are part of this pull request."; the issue gets "**Agern:** run
  `<new>` continues run `<old>` from its branch `<branch>` (<n> commits); the spec of that run is reused.". The
  continuing run ends with the old commits plus the new ones in one pull request. The old branch is left as it is; its
  commits are now also on the new branch, so nothing is lost whichever of the two is cleaned up later.
- **Start over** (`start_over`): a new run from scratch, as before this feature; the old branch, pull request and
  local branch are left as they are (§6 "Worktrees and branches").

*Recommendation.* The options endpoint answers each mode with `available` and a one-line `detail`, plus the
`recommended` mode and a one-line `reason`. Rules, in order: `step` when a step retry is possible; `start_over` when
`continue` is not possible (the branch has no commits of its own, or it is found neither locally nor on the remote);
`start_over` for a cancelled run (continuing stays available); `start_over` when the builder made no commits
(`blocked`, it reported why); `continue` for the interruptions `time_limit`, `usage_limit`, `restart` and
`api_unreachable` (e.g. "Stopped by the stage time limit after 4 commits; continue from them."); `continue` for any
other failure whose branch has commits. Commits are counted in the owner's clone as `git rev-list --count <branch>
--not <base>` (the local branch, else `origin/<branch>` after a best-effort fetch).

*Failure kinds* (`failureKind`, recorded when a run fails): `time_limit`, `usage_limit`, `credentials`,
`api_unreachable` (the stage error or exception matches e.g. "Can't reach the API", "Unable to connect to API",
`ECONNREFUSED`, `ENOTFOUND`, `ETIMEDOUT`, `ConnectException`, or is an I/O exception), `restart` (failed by a server
restart: "Server restarted while the run was in progress; retry it to continue or start over."), `blocked` (no
commits), `step` (with `failedStep`), `other`; and, on a `needs_human` run whose merge was refused,
`submodule_commit_missing` (with `failedStep` `merge`, §6 "Submodule pointers") and `checks` (with `failedStep`
`merge`: the checks failed, did not finish in time or could not be read, §6 "Checks before a merge").
`FailureKind.INTERRUPTED` holds the four kinds of the recommendation rule. A run fails with `api_unreachable` only
after it waited for the network and gave up, reached the limit, or when waiting is switched off (§6 "Transient failures").

*Who.* Options: anyone who can see the run (404 otherwise). Retry: the run's owner only — admins included in the
refusal (403), as for every action that makes agents run on the owner's credentials (§4) — and the owner needs a
Claude credential (422 `claude_credential_required`). A single run must be `failed` or `cancelled` (409
`run_not_retryable`); a release run is retried through its release (409 `release_run`). A mode that is not available
is 409 `retry_mode_unavailable`; an unknown one is 400.

*Default.* A request without a mode (no body, `{}`, or `mode` null) means `start_over`: the release issue retry
behaves exactly as before for clients that send nothing, and the new run endpoint follows the same rule so that both
agree. `recommended` asks for the server's recommendation explicitly; the web UI preselects it.

*In a release.* "Retry #n" on a held or skipped issue takes a mode too. `start_over` is as before (§6 "Releases",
"Retry"). `continue` and `step` keep the release's order and its one-run-at-a-time rule: the issue goes back to
`waiting` at its position with its old run still linked and `retryMode` set (note "Retry: continues from the commits of
run <id>" / "Retry: the failed step of run <id>"), and the build reaches it in its turn; `advance` then starts a
continuing run (`continue`) or resumes the old run's failed step (`step`, the issue is `running` with the **same** run)
instead of a fresh run. For `continue` an old run that is still active (`needs_human`) is cancelled first. If the old
run was approved and merged by its owner while the issue waited, a `step` issue becomes `merged`. If the retry cannot
start, the issue is `held` with "Could not start a run: …" and keeps the old run linked, so it can be retried again.
When a release builds an issue again by itself after an interruption — the usage limit, a rejected credential, a
restart — it continues from the interrupted run's commits when `continue` is recommended for that run, else it starts
over.

#### Transient failures

A failure that comes from the environment rather than the work (issue #57) does not fail a run or hold a release
issue: the run waits for the network and tries the failed operation again.

*Transient* (`pipeline/Transient.kt`), checked along the exception's cause chain (at most 10 levels): an
`IOException` (also as the cause of an `UncheckedIOException`) that is not a `FileSystemException` or
`FileNotFoundException` — `ConnectException`, `UnknownHostException`, `SocketTimeoutException`, `HttpTimeoutException`,
`SSLException`, …; an `UnresolvedAddressException`; a GitHub answer 500–599; git output naming the network ("Could not
resolve host", "Failed to connect to", "Connection timed out/refused/reset", "Network is unreachable", "No route to
host", "Temporary failure in name resolution", "Operation timed out", "The requested URL returned error: 5xx", "remote
end hung up unexpectedly", "early EOF", "RPC failed", TLS handshake errors, "git <cmd> timed out") unless it also says
the push was refused (permission, protected branch, non-fast-forward); a stage agent that failed with the "API
unreachable" text of `FailureKind.looksUnreachable` and was not stopped at the time limit, not on a usage limit and not
for a rejected credential. Never transient: a builder without commits, a reviewer or approver without a valid verdict,
a stage time-out, a usage limit, a rejected credential, GitHub 4xx, a refused push, a cancel.

*What waits.* The run's set-up (repository and issue from GitHub, clone, worktree or workspace checkout — retried as a
whole after removing what the failed attempt checked out), every stage agent, the "spec ready" comment, the push, the
pull request (`findOpenPull` / `createPull`) and the pipeline's own merge after the approver approved: its first
reading of the pull request and its squash merge (before each retry the pull request is read: merged meanwhile — e.g.
the failed answer was lost — counts as merged; §6 "Checks before a merge"). A checks read that fails is not a network
wait: the run waits in `waiting_for_checks` and reads again. The owner's Approve, a step retry's merge, a
`waiting_for_checks` poll's merge, follow-up reviews and advisors do not wait.

*Waiting.* The run keeps its status, worktree, sandbox, branch and stages (a waiting stage stays `running`); its
`waiting` is `{reason: 'network', since, nextAttemptAt, attempt, detail}`. One wait per run is shared by everything
that fails meanwhile (the four reviewers): a failure that arrives during a wait joins it and is not another attempt.
The delay before attempt *n*+1 is 1, 2, 5, 10, 15 minutes, then 15 minutes each time; "Retry now" (owner or admin)
ends the sleep at once. The first success of a retried operation ends the streak; the next failure starts a new one.
A waiting run holds no agent slot and no stage time: each attempt of a stage gets the full time limit. The stage
agent's transcript gets "Lost the connection: <detail>. Waiting for the network; next try at <time> (attempt <n>)."
where <n> is the attempt that comes next. The run (and its release) is published when a wait starts and ends.

*Retrying a stage.* The next attempt is a new session of the same stage row (its parent: the failed attempt's
session). When the failed attempt reported a Claude session id it runs with `--resume` and the prompt "Message from
Agern: the connection to the Claude API was lost while you were working and is available again. Continue the task from
where you stopped; your working directory is as you left it. End with the final answer the task asks for."; otherwise
the stage runs again with its original prompt. A resume that fails without ever starting (no `init`, no result) is
followed at once by one fresh attempt with the original prompt. Reviewers that already gave a verdict are not run
again.

*Limit and give up.* When a new wait would start `FACTORY_NETWORK_RETRY_HOURS` (default 24) after `since`, the run
fails as it would have without waiting — kind `api_unreachable`, or `step` with `failedStep` `push` / `pull_request` —
with " Gave up after waiting for the network for <duration> (<n> attempts)." after the reason. "Give up" (owner or
admin) fails it the same way with " Gave up waiting for the network (by @<user>, after <n> attempt(s))."; the worktree
and sandbox are removed, the issue gets the failure comment and a release run settles as failed. For the pipeline's
merge, giving up or the limit ends as any failed merge does: `needs_human` with `failedStep` `merge`. With the limit
`0` nothing waits. Cancelling works during a wait. A terminal or `needs_human` run never has `waiting`.

*Releases.* While its running run waits, a release stays `building` with the issue `running` (the issue carries the
run's `waiting`), starts nothing else and holds nothing. When the next issue cannot start for a transient reason
(anything that fails between the skip check and the run's creation), the release is `paused` with reason `network`,
note "Could not start #<n>: <detail>", `resumeAt` (the next try, same backoff), `since` and `attempt`; the issue stays
`waiting` ("Waiting for the network; it starts when GitHub and the Claude API can be reached again") with its linked
run and retry mode. The resume ticker resumes it at `resumeAt`; `since` and `attempt` are stored and survive resumes
and restarts, and reset when a run starts, an issue is skipped or held, or the release is cancelled. After the limit
the issue is held as before ("Could not start a run: <detail> Gave up after waiting for the network for <duration>.")
and the build goes on. "Retry now" is the owner's Resume; "Give up" (owner or admin) holds the issue ("… Gave up
waiting for the network (by @<user>).") and goes on with the next one. A due scheduled release whose start fails
transiently stays `scheduled` without a start failure and is started at a later tick.

*Logs and notes* name exceptions as `<Class>: <message>` (or the class alone; ` (caused by <Class>[: <message>])` for a
wrapper), never `null`: the release re-evaluation and its ticker (a non-transient failure there is logged with its
stack trace), the release builder's notes and logs, the run failure log, and the scheduler. A run's error keeps an
exception's own message and is the class name when it has none.

#### GitHub status

When GitHub has problems, Agern shows it on the GitHub status page (issue #99): GitHub's official status combined with
what Agern itself sees, and a verdict "working", "degraded" or "down" *for Agern* — GitHub can fail for Agern while
githubstatus.com says "All Systems Operational".

*Recording* (`github/GitHubHealth.kt`, in memory, lost on restart). `HttpGitHub` sends every request — paged requests
and `getFileBytes` included — through one function that records one outcome per exchange; `CliGitOps` records every
`clone`, `fetch` and `push` (the first argument that is no option and not the value of a `-c`) when it is not
`localOnly`. Not recorded: `submodule update` and the fetch of `remoteHasCommit` (other hosts possible), the fake
GitHub, calls that agents make themselves, webhooks, and the official status fetch. Classes: `ok` (any answer below
500 that is none of the following — 404 and 422 included — and git exit 0); `server_error` (HTTP 500–599, git "The
requested URL returned error: 5xx"); `network` (an `IOException` — timeouts, refused connections, DNS, TLS — or an
`UnresolvedAddressException`, a git timeout, git output `Transient.isNetworkGitMessage` accepts); `rate_limited`
(429; 403 with `x-ratelimit-remaining: 0`, a `retry-after` header or a message containing "rate limit"; git `returned
error: 429`); `auth` (401, any other 403; git "Authentication failed", "could not read Username", `returned error:
401/403`); `other` (git only: any other non-zero exit, e.g. a refused push — a call, never a failure). Kinds, from
the path without the API base, query and `/repos/{org}/{repo}` (first match): `graphql` (`/graphql`), `issues`
(`/issues…`, `/labels…`), `pulls`, `releases`, `actions`, `checks` (`/commits/{sha}/status`, `/commits/{sha}/check-runs`,
`/check-runs…`, `/check-suites…`, `/statuses…`), `contents`, `git_data` (`/git/…`), `repos` (anything else under
`/repos/…`, and `/orgs/…`), `other` (`/user`, `/rate_limit`, …), plus `git` for the git CLI. Storage per kind: a ring
of 1440 one-minute buckets with a counter per class (exact counts for 24 h, bounded memory; a bucket is in a window of
N minutes when its minute start is later than now − N minutes) and the last error `{at, class, status, requestId,
message}` (shown for 24 h). Nothing else: no bodies, tokens, URLs, paths or users. Recording is thread-safe and never
throws into the caller.

*Error texts and scrubbing.* HTTP: `HTTP <status>[: <GitHub's message>]`; exception: `<SimpleClassName>[: <message>]`;
git: the last `fatal:`/`error:` line, else the last non-empty line, else `git <cmd> timed out`. Every stored or
returned text passes `GitHubHealth.scrub`: control characters and whitespace runs become one space; credentials
(`ghp_…`/`gho_…`/…, `github_pat_…`, the value after `Bearer`/`Basic`, a secret-looking value after `token`, an
`Authorization:` header's value, URL userinfo) become `[redacted]`; URLs keep only scheme and host
(`https://github.com/…`); `<org>/<name>` becomes `<org>/[repository]`; IPv4 literals, bracketed IPv6 literals,
`localhost` and host names ending in `.local`, `.internal`, `.lan`, `.corp`, `.home.arpa` or `.localdomain` (with any
port) become `[address]`; absolute paths under `/home/`, `/var/`, `/tmp/`, `/srv/`, `/opt/`, `/root/`, `/data/`,
`/mnt/`, `/usr/`, `/etc/` or `/Users/` become `[path]`; at most 200 characters (`…` when cut). The request id
(`x-github-request-id`) is kept only when it matches `[A-Za-z0-9:._-]{1,100}`.

*Verdict* (every request, all kinds, last 5 minutes; F = `server_error` + `network`, S = `ok`): `down` when F ≥ 3 and
S = 0; `degraded` when F ≥ 2, or when the official status is `ok` and a relevant component (API Requests, Issues, Pull
Requests, Actions, Git Operations, Webhooks) is not operational; `working` otherwise (also without calls, and when the
official status is unreachable or off). Rate-limit, auth and other outcomes never change it. Reasons: "All <F> GitHub
calls by Agern failed in the last 5 minutes (<n> server errors, <m> timeouts or network errors).", "<F> of <T> GitHub
calls by Agern failed in the last 5 minutes (…)." (followed by the official sentence when both apply), "GitHub reports
problems with <names>.", "Agern's calls to GitHub are succeeding." or "Agern has made no GitHub calls in the last 15
minutes.".

*Official status* (`github/GitHubStatusPage.kt`). The server fetches `FACTORY_GITHUB_STATUS_URL` (`GET`, `Accept:
application/json`, `User-Agent: agern`, no credentials, 5 s connect and request timeout, at most 1 MiB), caches the
result — failures too — for 60 s and lets concurrent requests share one fetch; the browser never contacts
githubstatus.com. Kept: `status.indicator` (`none`/`minor`/`major`/`critical`, else `unknown`) and `description`;
components that are not `operational` (at most 50, relevant ones first, `relevant` flag); incidents that are not
`resolved`/`postmortem` (at most 20; `url` only for an `https://` shortlink of at most 500 characters). Strings lose
control characters and are cut (names 200, description 300). `state`: `ok`, `unreachable` (network error, timeout,
non-2xx, oversized or unparsable; `error` is a scrubbed text such as `HTTP 503`) or `disabled` (no URL).

*Rate limits and privacy.* From every answer with `x-ratelimit-limit`/`-remaining`/`-reset` the recorder keeps
`{limit, remaining, resetAt, observedAt}` per token fingerprint (first 16 hex characters of SHA-256 of the token; the
token is never stored, the fingerprint never leaves the server) and resource (`x-ratelimit-resource`, default
`core`); at most 200 fingerprints (least recently observed evicted), dropped after 24 h without an observation. The
caller's own limits come from `GET /rate_limit` with their token (`resources.core` → REST, `resources.graphql`;
cached 60 s per user; falling back to the header observations of their fingerprint; recorded as kind `other`). Other
tokens appear only as a count of fingerprints with a current `core` observation and the lowest remaining/limit among
them — no fingerprint, login or user id.

*Error messages.* A GitHub answer ≥ 500 throws `GitHubException` (with `requestId`) whose message is built in one place
(`GitHubFailure.serverError`): "GitHub failed: <what> returned HTTP <status>[ (request id <id>)][: <GitHub's message,
scrubbed>]. This is a problem at GitHub, not with Agern or your token. See the GitHub status page in Agern
(/github-status)." — so API 502 `github_error` answers, MCP tool errors, run failure reasons, network-wait details and
release notes all carry it. The fake GitHub's 5xx knobs use the same text with request id `FAKE:0000:0000`. Messages
below 500 are unchanged. In `FactoryService.gh` and `ReleaseService.gh` an `IOException` (not `FileSystemException` /
`FileNotFoundException`) or `UnresolvedAddressException` becomes 502 `github_unreachable` "GitHub could not be reached
(<Class>[: <scrubbed message>]). This is a network or GitHub problem, not a problem with your token. See the GitHub
status page in Agern (/github-status)." with the exception as its cause (still transient); over MCP a tool error
instead of JSON-RPC "Internal error". A `GitException` whose output classifies as `server_error` or `network`, and a
git timeout, end with " GitHub or the network failed; see the GitHub status page in Agern (/github-status).".

Run `status`: `queued`, `spec`, `building`, `reviewing`, `approving`, `waiting_for_checks` (approved; merges by itself
when the checks pass, §6 "Checks before a merge"), `merged`, `needs_human`, `failed`, `cancelled`.

Stage `role`: `spec-writer`, `builder`, `security`, `ux`, `ui-design`, `code`, `approver`.
Stage `status`: `pending`, `running`, `done`, `failed`, `skipped`.
Stage `verdict`: `PASS`, `CHANGES`, `APPROVE`, `ESCALATE` or null.

### Workspace runs

A run on an issue of a workspace project (§5 "Workspace projects") changes exactly one member: the **target**.

**Runs of the workspace repository itself (submodule workspaces).** In a workspace whose members come from
`.gitmodules` alone (no `.agern/workspace.yml`, `WorkspaceState.fromSubmodules`), an issue with **no** member label is a
run of the workspace repository, exactly as for a project that is one repository (as before submodule workspaces):
the run's worktree is a worktree of `main/` on the run branch, every submodule is fetched there at the commit the
workspace pins, and branch, pull request and merge are in the workspace repository; no `target_repo`, no pointer
bump. The merge guard and the builder's submodule rules of §6 "Submodule pointers" apply unchanged (never commit inside
a submodule, never move a pointer to an unpublished commit). Its task prompts carry instead of the workspace block:

```
Workspace: <org>/<workspace> — its member repositories are git submodules under your working directory.
Target: the workspace repository itself; submodules are read-only context. Do not commit inside a submodule and do not move a submodule pointer (gitlink): a change to a member needs an issue with that member's label.
Submodules (at the commit the workspace pins):
- `<directory>` — <org>/<repository> (label `<label>`)
- `<path>` (not a member)            ← context-only submodules
The issue and the pull request are in <org>/<workspace>.
```

Several member labels are still refused (409 `member_label_ambiguous`); one member label makes a member run as below.
For a workspace defined by `.agern/workspace.yml` nothing changes: an issue without a member label is refused with 409
`member_label_missing`. Chats are unchanged. `factory_list_issues` marks such issues `[workspace repository]`.

**Routing.** Issues are listed from the workspace repository; each carries the members whose label it has
(case-insensitive exact match, `Issue.workspaceMembers`). Starting a single run (`POST /api/projects/{repo}/runs`,
`factory_start_run`, `/factory run`, retry `start_over`) checks, after the credential, visibility and issue checks and
before `active_run_exists`:

| Condition | Status, code | Message |
|---|---|---|
| definition invalid | 409 `workspace_invalid` | ``The workspace definition .agern/workspace.yml is invalid: <first problem>[ (and N more)]. Fix it on `<default branch>` first.`` |
| no member label (`.agern/workspace.yml` only) | 409 `member_label_missing` | ``Issue #<n> has no member label. Add exactly one of these labels on GitHub to say which repository it belongs to: `<label>` (<repository>), ….`` |
| several member labels | 409 `member_label_ambiguous` | ``Issue #<n> carries the labels of several members (<label>, <label>). A run changes one member: keep exactly one of them.`` |
| target not readable by the caller | 403 `member_not_accessible` | ``Your GitHub token cannot read <org>/<repository> (member `<directory>`), so you cannot run this issue.`` |

A refused start creates no run. The run stores the target's repository (`target_repo`), directory (`target_dir`) and
effective base branch (`base_branch`) when it is created; later edits of the file do not move it. Retry `continue`
copies the target of the run it continues (no label lookup; 403 `member_not_accessible` when the owner can no longer
read it); a step retry uses the stored target. At set-up the stored target must still be a declared member with the
same repository and directory, else the run fails with ``Member `<directory>` (<repository>) is no longer part of the
workspace definition``.

**Branch, pull request and merge.** Branch `factory/issue-<n>-<slug>-<run>` is created in the target's worktree,
pushed and opened as a pull request **in the member repository** against the stored base branch. Its title (and the
squash-merge commit title) is `<issue title> (<org>/<workspace>#<n>)`; its body starts with
`Closes <org>/<workspace>#<n>` (the rest as usual; "Earlier runs … left open" lists only earlier runs with the same
target repository). Issue comments, the `needs-human` label and closing the issue act on the workspace repository;
after the squash merge Agern closes the workspace issue itself, so closing works whatever the member's base branch.
Comments on the pull request (screenshots, UI warning, follow-up review) go to the member repository. Issue comments
that name the pull request use its URL, or `<org>/<repository>#<pr>` ("**Agern: merged** pull request …", merge and
migration failures), never a bare `#<pr>`. The migration check, retry options' commit count, push, changed files,
`.agern/ui-files`, the reviewed `HEAD` of the conversation and the follow-up push use the target's worktree, clone and
base ref; screenshots and images (`.agern-run/`) stay relative to the agents' working directory `runs/<runId>/`.

**Sandbox.** The definition is read from the target's base ref and approved per member repository; the target's
worktree is mounted at `/workspace` (other members are not visible inside it). The sandbox's `repo` and the overview's
visibility keep using the run's project.

**Prompts.** Every task prompt (spec, build, continue, fix, review, approval) and the advisor's `<run_context>` carry:

```
Workspace: <org>/<workspace> — several repositories checked out side by side under your working directory.
Target member directory: `<directory>` (repository <org>/<repository>, base branch `<base>`). Run every git command for this change there, e.g. `git -C <directory> status`. Only commits in `<directory>/` become the pull request.
`<directory>` is a git submodule of <org>/<workspace>, checked out on the run branch from `<base>` (not at the commit the workspace pins). Change only this submodule. Never commit the submodule pointer (gitlink) in the workspace repository and do not run `git submodule update` in the root (it would reset `<directory>`): Agern bumps the pointer in <org>/<workspace> after the pull request is merged.   ← only for a submodule target
Other checkouts are read-only context; do not edit, commit or push there:
- `.` — <org>/<workspace> (workspace root files)
- `<directory>` — <org>/<repository> @ <base>
- `<directory>` — <org>/<repository> @ the commit the workspace pins (submodule)   ← a submodule member
Not checked out: `<directory>` (<reason>)            ← only when there are any
The issue is <org>/<workspace>#<n>; the pull request is opened in <org>/<repository> against `<base>`.
```

and write every git command as `git -C <directory>` (e.g. ``git -C app diff origin/dev...HEAD``); the UI reviewers are
pointed to `<directory>/.agern/preview.md`. `fake-claude`'s builder commits in `<cwd>/<directory>` when its prompt has
the "Target member directory" line, and its advisor diffs there.

The builder's role prompt adds: in a workspace run change only the target member; for a submodule target commit
inside that submodule only, never commit the gitlink in the workspace repository and never run `git submodule update`
there.

**Submodule pointer bump.** After the pull request of a run whose target is a submodule member (looked up in the
current definition) is squash-merged, Agern moves the workspace's gitlink, through the GitHub API with the owner's
token:

1. The merge commit is `merge_commit_sha` of the member pull request. It must exist in the member repository
   (`GET /repos/{org}/{member}/commits/{sha}`), else nothing is changed and the notice says ``**Agern: submodule
   pointer not bumped.** The merge commit `<sha12>` of <org>/<member>#<pr> is not in <org>/<member>, so `<directory>`
   in <org>/<workspace> was left where it is.``
2. When the gitlink at `<directory>` on the default branch is already that commit (contents API, `type: submodule`):
   ``**Agern: submodule pointer** `<directory>` in <org>/<workspace> already points at `<sha12>`.``
3. Otherwise a commit on the default branch's tip whose tree differs only in that gitlink (Git Data API: tree with
   `base_tree` and one `160000` entry, then a commit), on branch `factory/bump-<directory with / → ->-<run8>`
   (created, or moved with `force`), and a pull request against the default branch titled
   ``Bump <directory> to <sha12> (<org>/<member>#<pr>)`` (also the commit message and squash title).
4. A single run merges on approval (the approver's or the owner's), so the bump is squash-merged at once and its
   branch deleted: ``**Agern: submodule pointer** `<directory>` → `<sha12>`: merged pull request <url>.`` Right
   before each merge the bump goes through the runs' merge guard (§6 "Submodule pointers": `changedGitlinks` of the
   bump branch against the default branch in the owner's workspace clone, `remoteHasCommit` for each pointer, failing
   closed); a refusal leaves the pull request open: ``… pull request <url> was not merged. <the guard's text>``. A merge
   that fails (e.g. the default branch moved and changed the same gitlink) is retried once: a new commit on the new
   tip, the branch force-moved, the same pull request merged again. Bumps of different submodules do not conflict.
   After the second failure the pull request is left open: ``… could not be merged (<GitHub's message>); it is left
   open for a human.`` A run of merge policy `github` whose pull request was merged on GitHub (§6 "Merge policy") stops
   after opening the bump pull request: ``… is open for a human to merge.``

Each outcome is a comment on the workspace issue and a `system` event (`info`, or `warn` when nothing was bumped) in
the run's latest stage transcript. A bump that throws is logged and reported the same way (``**Agern: submodule
pointer not bumped.** Moving … failed: <message>. Bump it by hand.``). The bump runs after the worktree's clean-up and
before the run is set to `merged`, so the outcome is on the issue when the run settles; it never fails the run. The workspace clone
itself is not touched: its submodule moves when a chat sync fast-forwards it.

**Releases** are available in every workspace project (§6 "Releases", "Several repositories"). Planning an issue
into a release (`assign`, `factory_assign_issues`, `/factory release add`, the release picker) routes it as a run
would be routed, with the planner's token (`WorkspaceService.memberFor`), all or nothing: 409 `workspace_invalid`,
`member_label_missing` (`.agern/workspace.yml` only), `member_label_ambiguous` and 403 `member_not_accessible` refuse
it with the run's text. An issue with one member label is built in that member; in a submodule workspace an issue
without one is built in the workspace repository. Taking issues out needs no routing. Starting a release of a
workspace whose definition is invalid answers 409 `workspace_invalid`. The owner's token routes the issue again when
its run starts; a label that changed after planning holds it ("Could not start a run: …"). The two refusal texts end
with a hint an agent can act on: ``You can set it with `gh issue edit <n> --repo <org>/<workspace> --add-label
"<label>"`.`` (missing) or the same with `--remove-label` (ambiguous); MCP tools return the message as their error.

A **member release run** is an ordinary workspace run (above) with `base_branch = release/<name>`, whatever the
member's own base branch: its target worktree starts at the tip of `release/<name>` in the member repository (the
root and the other members are as for a single run: a release run does not see other members' unmerged release
work), its branch is `factory/release-<name>/issue-<n>-<slug>-<run>` there, its pull request goes to `release/<name>`
of the member (title `<issue title> (<org>/<workspace>#<n>)`, body starting "Issue <org>/<workspace>#<n> · release
`<name>`", no closing keyword) and is squash-merged there, without a pointer bump and without closing the issue
("**Agern: merged** pull request <org>/<member>#<p> into `release/<name>` of <org>/<member> (approved by …). The issue
stays open until the release is released."). Its prompts carry the workspace block with base branch `release/<name>`;
for a submodule target the last sentence reads "Agern moves the pointer in <org>/<workspace> when the release is
released." Its `defaultBranch` (release note, migration line) is the member's base branch recorded for the release
(`release_repos.base_branch`), and its sandbox definition is read from that branch (fetched), never from
`release/<name>`, and approved per member repository.

Not in this step: runs that change several members at once, runs of the workspace
repository itself in a workspace defined by `.agern/workspace.yml`, choosing the member in Agern, members outside the
organization (such submodules are context only).

### Merge policy

Many teams require that a person reviews and merges every pull request. An admin sets a **merge policy** per project
(`PUT /api/projects/{repo}/settings`, §8); without a stored setting it is `automatic`. For a workspace project the
policy is the workspace repository's, never a member's.

| Policy | After the approver | Who merges |
|---|---|---|
| `automatic` | `APPROVE` merges, `ESCALATE` → `needs_human` (as above) | the approver agent, or the owner's approval |
| `owner_approves` | always `needs_human` | only the run owner's approval |
| `github` | `ready` (terminal) | never Agern: the team, on GitHub |

- **Snapshot.** `Orchestrator.create` stores the policy in force on the run (`runs.merge_policy`, `Run.mergePolicy`);
  it never changes afterwards (also not for a step retry). A new run created by a retry takes the project's current
  policy; a release run always stores `automatic` (releases below); runs from before the setting are `automatic`.
  Changing the setting never affects existing runs.
- **The approver's prompt** carries one paragraph per policy: `automatic` "Merge policy: automatic. `APPROVE`
  squash-merges the pull request and closes the issue immediately, without a human looking at it; `ESCALATE` asks the
  run owner to decide." (a release run: "… into the release branch `<branch>` immediately … (the issue stays open until
  the release is merged) …"); `owner_approves` "Merge policy: the run owner approves every merge. You cannot merge:
  your verdict is a recommendation to the owner. …"; `github` "Merge policy: merged on GitHub by the team. Agern never
  merges this pull request: your verdict is a recommendation that is posted for the human reviewers. …". The role
  prompt (`approver.md`) no longer promises an immediate merge; the schema is unchanged.
- **`owner_approves`.** The stage records the verdict; then `needs_human` with the reason ``The approver recommends
  merging: <summary> This project requires the run owner's approval before every merge.`` (`APPROVE`) or ``The
  approver escalated: <summary>``. The owner's approval (button, `/factory approve`, `factory_approve_run`) merges as
  today ("(approved by @owner)"; a submodule pointer bump is merged too). Anyone else gets 403, admins included; the
  owner may approve their own run (a second-person rule is not built). Everything else at `needs_human` is unchanged.
- **`github`.** With either verdict, in this order: the project's `reviewers`/`teamReviewers` are requested on the
  pull request (`POST /repos/{o}/{r}/pulls/{n}/requested_reviewers`, in the pull request's repository, with the
  owner's token; the owner's own login is left out because GitHub refuses the author; a failure never fails the run);
  the issue gets ``**Agern: ready for review.** Pull request <url> passed all reviewers. Approver's assessment
  (<VERDICT>): <summary>`` + ``This project merges on GitHub: review and merge the pull request there. Agern does not
  merge it and follows it until it is merged or closed.`` + `` Reviewers requested: @a, team `x`.`` or `` Requesting
  reviewers failed: <message>.`` (a workspace member run: the same text on the pull request too); the `needs-human`
  label is removed; the worktree is removed; the run becomes `ready` (error and failure cleared, `endedAt` set). The
  issue stays open and nothing is merged. Agern's pull requests are never drafts, so "ready for review" needs no
  GitHub call. When reviewers still request changes after the last fix round the run goes to `needs_human` as usual;
  its comment then ends ``This project merges on GitHub: the pull request can be reviewed and merged there; Agern
  does not merge it.`` and the conversation and "review again" work, but there is no approval.
- **`ready`** is terminal: not active (a new run of the issue may start; its pull request body lists the open one),
  cancel answers 409 "Run is already ready", retry 409 `run_not_retryable`, the worktree reapers treat it as ended.
- **No path merges a `github` run.** `Orchestrator.approve` answers 409 `merge_on_github` ("This project merges on
  GitHub: Agern does not merge. Review and merge pull request <url> on GitHub.") after the 404 and 403 checks and
  before the status check; `/factory approve` and `factory_approve_run` show that message; a step retry of a merge is
  refused the same way, and so is "Merge anyway" (`POST /api/runs/{id}/merge-anyway`). As defence in depth every
  evaluation before a merge (`mergeStep`, and the merge of "Merge anyway") itself refuses a `github` run and an
  `owner_approves` run without a human approver (logged as an error; the run goes to `needs_human` with ``Not merged: this run's merge
  policy (<policy>) does not allow it.``).
- **Follower.** Every 60 s (`Orchestrator.followMillis`, started with the other tickers) one round reads, with the
  run owner's token, the pull request of every run that is `ready`, or `needs_human` under `github`:
  - open: nothing; a failed read (no owner, no token, GitHub error, not found): logged, tried again next round;
  - merged: `merged`, `mergedOnGitHub: true`, `mergedBy` = GitHub's `merged_by.login` (null if not reported), the
    `needs-human` label removed; a submodule workspace run opens its pointer bump pull request and leaves it for a
    human. Agern does not close the issue (`Closes #N` does that on GitHub) and posts nothing else;
  - closed without merge: `cancelled` with ``Pull request <ref> was closed on GitHub without being merged.``.
  From `ready` the change is a conditional update that keeps `endedAt`; a `needs_human` run is ended like a cancel
  (follow-up review stopped, advisor ended, sandbox and worktree removed, open stages closed) unless an approval or a
  cancel won the race. The rest of #22 (outside merges of other policies) is not built.
- **Releases.** Release runs are exempt (they merge into `release/<name>` as `automatic`); the policy applies to the
  release's merge into the default branch, read live. `owner_approves` and `github` refuse automatic release mode
  (§6 "Release mode"). `github` also refuses Agern's release approval (HTTP, `/factory release approve`,
  `factory_approve_release`: 409 `merge_on_github`, "… Review and merge the release pull request on GitHub."), and the
  ready announcement on the release pull request ends "This project merges on GitHub: review and merge this pull
  request there; Agern follows it." The periodic reconcile already finishes a release merged on GitHub.

### Database migrations

Two branches built independently can each add a Flyway migration with "the next free version". The files have
different names, so git sees no conflict and both merge, but with two migrations of one version the server no longer
starts; and the later one cannot simply be renumbered once an installation has applied the other. So a new migration
takes its version from the branch it merges into, above every migration there, and a migration that exists on that
branch is never changed, renamed or removed (Flyway checks applied migrations by checksum). Three guards apply this:

- **CI, for Agern itself.** `scripts/check-migrations.sh [<base-ref>]` checks `server/src/main/resources/db/migration`
  without a database (bash 3.2 or later, git, awk): every file is named `V<number>__<name>.sql`, no two share a version
  (compared as numbers, `V011` = `V11`), and, against a base, every file not on the base is above the base's highest
  version and every migration on the base is unchanged and still there. Problems are printed one per line naming the
  files, exit 1. The `migrations` job in `ci.yml` runs its self-test (`scripts/check-migrations-test.sh`) and, on a pull
  request, the check of the merge commit against `HEAD^1` (the base branch's tip); on a push, without a base.
  `publish-main` needs it. On startup `Db.connect` lists the bundled migrations before Flyway runs and fails with
  "Duplicate migration version 11: V11__a.sql and V11__b.sql. Renumber the one that was added later to a version above
  every other migration." (`MigrationFilesTest`).
- **Agern, for every project it builds.** The known layout is Flyway's default location: files at or below a
  directory `src/main/resources/db/migration`, at the repository root or in a module (e.g.
  `server/src/main/resources/db/migration`); each such directory is checked on its own. A versioned migration is
  `V<version>__<description>.sql`, the version being digit groups separated by `.` or `_`, compared as Flyway does
  (`1` = `1.0` = `01`, `1_2` < `1.10`). Other files, layouts and projects are never affected. The check
  (`MigrationCheck`) runs with git in the owner's clone, no database and no GitHub API: both branches are fetched, and
  the head's changes since the merge base (`git diff --name-status --no-renames <base>...<head>`; a rename is a removal
  plus an addition) are compared with the files on the base's tip (`git ls-tree`, only under the directories the
  changes touch, and not at all when no change touches a migration). Problems: an added migration with the same
  version as one on the base or another added one, an added migration below the base's highest, and a migration on
  the base that the head changes or removes. Paths in the texts keep only `[A-Za-z0-9._/+-]` (the rest `?`) and at
  most 200 characters; at most 5 problems are listed, then "(and N more)". A comparison that cannot be made (no
  clone, a branch missing, git failing) is logged and blocks nothing: CI is the strict gate.
  - *A run's pull request*, before every squash merge (the approver's `APPROVE`, the owner's approval of a
    `needs_human` run and the step retry of a failed merge, single and release runs alike), compares the run's branch with its target. On a problem
    nothing is merged and the run goes to `needs_human` through the usual path (label, comment, error), with the
    reason "Database migration conflict with `<target>`: <problems>. Pull request #<n> was not merged. Migrations
    already on `<target>` must stay unchanged, and a new migration needs a version above all of them. Fix the
    migration on the pull request's branch and approve again, or build the issue again from the current `<target>`
    (in a release: Retry)." In a release, the issue thereby becomes `held` with that note (§6 "Releases",
    "Outcomes"); approving the run again checks again.
  - *A ready release* compares `release/<name>` with the default branch at every re-evaluation once its pull request
    is open (it becomes ready, "Check again", the periodic re-evaluation, before an approval). Its issues are already
    merged into the release branch, so none can be held: the release stays `ready` with the notice "Database migration
    conflict with `<default>`: <problems>. The release is not merged. A new migration needs a version above every
    migration on `<default>`, and existing ones must stay unchanged. Use "Bring up to date" on the release page (or
    `/factory release sync <name>`), or fix the migrations on `release/<name>` and use "Check again"." (a member
    repository of a release of several repositories: "… Fix the migrations on `release/<name>` (merge `<default>` into
    it), then use "Check again"."), is not announced as ready to release, is not merged automatically, and
    approval answers 409 `release_migration_conflict` with the notice and " Nothing was merged." (§6 "Releasing"). The
    next re-evaluation without a problem clears the notice.
- **The builder's prompt.** `builder.md` and the build, continue (§6 "Retrying a run") and fix prompts tell the builder to take a new migration's
  version from the branch its pull request merges into (named in the prompt, fetched first), above every migration
  there, never to change one that exists there, and to check again before finishing, in every fix round and after
  any rebase or merge of that branch. A release run is told that its version must also be above every migration on
  the default branch, which the release is merged into later.

### Submodule pointers

Agern pushes only a run's own branch. A builder that commits inside a submodule and commits the new pointer (gitlink,
mode `160000`) in the parent produces a pull request that would pin the target branch to a commit nobody can fetch,
and the commit itself lives only in the run's worktree: a worktree's submodule repositories are kept in its
administrative directory (`.git/worktrees/<name>/modules/…`), which goes when the worktree is removed. Three guards:

- **Merge guard.** Before every merge of a run's pull request (the approver's `APPROVE`, the owner's approval of a
  `needs_human` run, the step retry of a failed merge; single, release and workspace runs alike; after the database
  migration check) and before a release is merged (approval and automatic release; for a multi-repository release
  before each member pull request, in the member's clone, and before the project repository's pull request, after
  its pointers were moved and read back), `SubmoduleCheck` checks every
  pointer the head adds or moves relative to the merge base with the target (`GitOps.changedGitlinks`: both branches
  fetched as for §6 "Database migrations", `git diff --raw --no-renames <base>...<head>`, new mode `160000`; a removed
  submodule does not count). The submodule's URL comes from `.gitmodules` on the head (`git config --blob`); a
  relative URL (`../sub.git`, `./x`) is resolved against the clone's `origin` as git does (`resolveSubmoduleUrl`).
  Whether the commit exists there is asked of the remote itself (`GitOps.remoteHasCommit`): a throw-away bare
  repository under the owner's home fetches exactly that commit (`git fetch --depth=1 --filter=tree:0 origin <sha>`
  as a partial clone, so only the commit object where the server supports filters) with the owner's token for the git
  host, whose SSH URLs go over HTTPS as everywhere (§5 "Submodules"), and `GIT_PROTOCOL_FROM_USER=0` as
  `git submodule` uses, so a repository's URL cannot make the server read another local repository. Git (rather than
  the GitHub API) works the same for submodules on any host. "not our ref" and similar answers mean the commit is
  missing; any other failure means it could not be checked. **Fails closed:** a missing commit, a submodule without a
  URL, an unreachable remote and a comparison that cannot be made (no clone, a branch missing, git failing) all
  refuse the merge; there is no override, the fix is to push the commit or reset the pointer.
  - *A run* goes to `needs_human` through the usual path (label, comment, error) with `failedStep` `merge`, so an
    approval or a step retry checks again once the commit is pushed. A missing commit (`failureKind`
    `submodule_commit_missing`): "Submodule commit missing: pull request #<n> moves submodule `<path>` to commit
    `<sha>`, which the submodule's repository does not have, so merging it would leave `<target>` pinned to a commit
    no one can fetch. Nothing was merged. Push that commit to the submodule's repository (or reset the pointer on the
    pull request's branch to a commit that is there), then approve again." (several pointers listed, at most 5, then
    "(and N more)"; paths as in §6 "Database migrations"). A pointer that could not be checked (`failureKind`
    `step`): "Submodule commit not checked: …", or "Submodule pointers not checked: …" when the branches could not be
    compared. The texts never carry URLs or git's output, which go to the log.
  - *A release* is refused with 409 `release_submodule_commit_missing` (or `release_submodule_check_failed`) and the
    same text as its notice; an automatic release counts it as a refused automatic merge (§6 "Releasing").
- **Unpushed commits are saved.** Before a run's checkout is removed (`Workspaces.removeRunCheckout`: when the run
  ends, and when leftovers are reaped), every initialised submodule of each worktree in it (the run's worktree; for a
  workspace run its member worktrees and root) whose HEAD or local branches hold commits found on none of its
  remote-tracking branches (`git rev-list HEAD --branches --not --remotes`) is bundled:
  `git bundle create workspaces/<user>/<repo>/bundles/<runId>/<path with / as _>.bundle HEAD --branches --not
  --remotes`. When the run ends, the transcript of its last builder gets a `warn` event per bundle: "Unpushed
  submodule commits saved: submodule `<path>` had N commit(s) up to `<sha>` that are on none of its remote branches,
  and the run's checkout was removed. They are saved in the git bundle `<file>` on the Agern server. To recover them,
  run `git fetch <bundle>` in a clone of the submodule (`git -C <path> fetch <file>`) and push `FETCH_HEAD` to a
  branch of its repository." Bundles are kept until an administrator removes them.
- **The builder.** `builder.md` tells the builder never to commit inside a submodule, to move a pointer only when the
  task asks for it and only to a commit already on the submodule's remote, and in a workspace to change only the
  target member and report a needed pointer bump in its summary instead — except that a workspace run whose target
  member is a submodule (§6 "Workspace runs") commits in that submodule (never the gitlink; Agern bumps it after the
  merge). After every builder step the same
  detection runs on the run's checkout (root and target worktree); submodules with such commits get a `warn` event in
  the builder's transcript: "Commits inside submodules: `<path>` (N commit(s)) on none of the submodule's remote
  branches. …". A submodule target's own worktree (a submodule path of the root) is left out: its commits are the
  run's, pushed by Agern. Neither is it bundled: it is a worktree of the submodule's clone in `main/`, removed before
  the root, and its unpushed run branch stays in that clone (§5 "Workspace projects"); a context submodule checkout
  of the root is bundled with the root.

The run page makes an approver's escalation harder to override with one click (§10 "Conversation at needs human").

### Conversation at needs human

When a run waits at `needs_human`, its owner can talk to an agent about it — the **advisor** (session kind `advisor`,
title `advisor · #<n>`), a new agent with both the approver's and the builder's context — before deciding to approve
or cancel. Talking never changes the run's status and never approves anything. Messages are not posted on GitHub.

- **Who.** Only the run's owner, through `POST /api/runs/{id}/conversation` (or the session input route). Everyone
  else gets 403, admins included (they may stop the advisor and cancel the run, never drive it); a user who cannot see
  the repository gets 404. The advisor runs on the owner's credentials: agent slot, spend, limit windows. A usage
  limit ends the turn with a `system` warn event (with the reset time when known); nothing is lost and the
  conversation continues with the owner's next message.
- **Session.** The first accepted message creates the run's one advisor session (`Run.conversation.sessionId`) with
  the owner's effective `builder` model, and records the worktree's `HEAD` as the run's *reviewed commit*. One message
  is in flight at a time: a message while the agent answers (or while the run is reconciled after its turn) is 409
  `agent_busy`. Refusals, in this order: 404, 403 `forbidden`, 409 `run_not_waiting`, 400 (blank or over 100 000
  characters), 409 `review_in_progress`, 409 `agent_busy`, 422 `claude_credential_required`, 409 `too_many_agents`
  (no live process and no free slot), 409 `run_worktree_missing`.
- **Context.** Every new process (fresh or resumed) gets, before the owner's first message to it, a `<run_context>`
  block rebuilt from the database and GitHub: run id, repository, issue, why it needs a human (`run.error`), branch,
  base ref, pull request, merge target and the release note; how to inspect the change (`git diff <base>...HEAD`,
  `git log <base>..HEAD`); the issue text (the stored title when GitHub cannot be read); `<spec>` (the spec-writer's
  final text), `<builder_summary>` (the latest builder's), `<reviews>` (each reviewer's latest verdict with round,
  summary and findings), `<approver>` (verdict and summary, or that it did not run), and that the run has no sandbox
  now. Then `Message from the run owner (<username>):` and the text; later messages to the same process carry only
  that prefix. The transcript's `user` event holds the owner's text as written; a `system` info event at each launch
  says the agent was given the run's context. The prompt marks the context as data, not instructions.
- **Changes.** The advisor may change code when asked, and must commit; it never pushes, merges or comments on
  GitHub (by instruction and by having no such MCP tools — like the builder it does have the owner's git and `gh`
  credentials). After every advisor `result` and every exit, the server *reconciles* the run before the session counts
  as idle: if the worktree `HEAD` differs from the reviewed commit, the review state (`Run.conversation.review`)
  becomes `running` and a **follow-up review** starts. It is idempotent and a no-op while a review runs.
- **Follow-up review.** A coroutine of the run (cancel stops it), not the pipeline: it stops the advisor process
  (resumable), pushes the branch with the owner's identity, creates the four reviewer stages of a new round (highest
  round + 1, the owner's current models; `Run.round` becomes it, `followUpFrom` is the first such round) and runs them
  exactly as in the pipeline (read-only, `--json-schema`, screenshots for `ux` and `ui-design`, the stage time limit).
  Their prompt says the round is "after follow-up round N: changes the run owner requested in a conversation" and
  carries the advisor's last result in `<advisor_summary>`. When all four return a verdict: the reviewed commit
  becomes the pushed one, the state `none`, `run.error` becomes the original reason plus
  `**Follow-up review N** (commit `<sha7>`): all four reviewers pass.` (or `<roles> still request changes.`), the
  advisor's transcript gets a `system` event with each verdict, and the pull request one comment. A push failure, a
  failed or timed-out reviewer, an invalid verdict or a usage limit closes the round's open stages and leaves the
  state `required` with a `system` error in the advisor's transcript pointing to "Review again"
  (`POST /api/runs/{id}/review`); the run stays `needs_human` with its worktree. The approver does not run again, and
  nothing is merged: `CHANGES` verdicts do not block the owner's approval (as after exhausted fix rounds).
- **Approval** (button, `/factory approve`, `factory_approve_run`) is refused while a follow-up review runs (409
  `review_in_progress`), while the advisor is mid-turn (409 `agent_busy`), and when changes have not been reviewed
  (409 `review_required`: state `required`, or `HEAD` differs from the reviewed commit — which also catches a commit
  the agent pushed itself or one made before a restart). Otherwise a live advisor process is stopped first; the
  session ends `done` when the run is merged and stays usable when the merge fails.
- **Cancel** (owner, admin or a release) stops a running follow-up review and the advisor (mid-turn too) before the
  worktree is removed; the advisor session ends `stopped`.
- **Restart.** Advisor sessions of `needs_human` runs become `idle` (resumable); a review state `running` becomes
  `required`; open stages of `needs_human` runs are closed. The owner uses "Review again"; nothing restarts by itself.
- **Releases.** A release run in a conversation stays `needs_human`, so its issue stays `held` and the release cannot
  be approved until the owner approves or cancels the run.

### Screenshots

For a change that affects a UI, the **ux** and **ui-design** reviewers run the app and attach captioned screenshots
of the screens they reviewed to their stage with the MCP tool `factory_attach_screenshot` (§7); the builder and the
other roles do not get the tool.

- **Prompts** (`prompts/ux.md`, `prompts/ui-design.md`, section "Screenshots"): the reviewer decides from the diff
  whether the change touches a UI (web components, styles, templates, user-facing text). If it does, it **must** try
  to run the app — reading the diff is not a review of a UI — and attach screenshots of the changed screens in the
  states that matter (empty/filled, error/loading, both themes if the product has them, narrow width). Concretely:
  read the project's preview recipe `.agern/preview.md` first if there is one; start the app from the run's worktree
  on a free port (the four reviewers share the worktree), never on Agern's own port (`FACTORY_PORT`, named in the
  review task) and never against a production service; find a headless browser (`chromium`, `chromium-browser`,
  `google-chrome`, `google-chrome-stable`, or a Playwright install in the project) and wait for rendering
  (`--virtual-time-budget=8000` with Chromium's `--screenshot`, or a Playwright script) — without it a single-page
  app comes out blank; for a login-protected app prefer the project's mock or development mode, seed data or a test
  user from its end-to-end setup; look at each image before attaching it; give each a one-line caption, quote the
  caption in a finding the screenshot supports, delete the files after attaching and never commit them; stop
  everything it started. The summary always says how many it attached, that the change has no UI, or — only after a
  real attempt — the command it ran and the error it got. Missing screenshots are never a reason for `CHANGES` and
  never fail the stage.
- **Review sandbox:** in a run with an approved sandbox the reviewers start the app in the round's read-only review
  sandbox (§5 "Sandboxes") instead of on the host, following the spec's Verification section if there is one: started
  detached with all streams redirected (never `background: true`), checked first whether the other UI reviewer
  already started it, the tree copied to `/tmp` inside the container when the app must write into it. They capture to
  `/run/agern-review/<file>` inside the container, read `.agern-run/review/<file>` on the host and attach it with
  `factory_attach_screenshot { path: ".agern-run/review/<file>" }` — the flow and checks below are unchanged — and
  need not stop the app: the sandbox is removed when the round ends. When it is busy or cannot be created they review
  on the host as before.
- **Preview recipe:** a project can describe how to start its UI for review in `.agern/preview.md` (free text:
  command, port, how to log in, useful pages). Agern's own is in the repository: the web frontend's mock mode
  (`cd web && npm ci && npm run dev:mock -- --host 127.0.0.1 --port <free port> --strictPort`) runs against simulated
  data in the browser and needs no server, database or login.
- **Who:** decided from the MCP grant — the token's session must be the session of a `ux` or `ui-design` stage of the
  token's run, in the token's repository — never from tool arguments.
- **Per round:** a screenshot belongs to the caller's stage row, so to its role and round. Each fix round creates new
  stage rows: screenshots of a fix round belong to that round, earlier rounds keep theirs.
- **Storage:** table `stage_screenshots` in PostgreSQL (bytes included), removed with the stage and with the run
  (`ON DELETE CASCADE`). Nothing goes into the repository or to GitHub.
- **Limits:** only PNG, JPEG, GIF and WebP (type sniffed from the bytes, never the extension, with readable
  dimensions); at most `FACTORY_SCREENSHOT_MAX_MB` per image (checked before reading) and
  `FACTORY_SCREENSHOT_MAX_COUNT` per stage (checked under a per-stage lock); the file must resolve, symlinks
  followed, to a regular file inside the run worktree; the caption is required, made one line (control and format
  characters removed, whitespace collapsed) and at most 200 characters.
- **Shown** on the run page with the stage (§10), live: each attach publishes the run on the feed. Served by
  `GET /api/runs/{id}/screenshots/{sid}` (§8) to whoever may see the run.
- **Pull request note:** after the reviewers of a round have all returned a verdict, if that round's stages have
  screenshots and the run has a pull request, Agern comments on it once (best effort): `**Agern: screenshots** — the
  reviewers attached <n> screenshot(s) of this change (<build | fix round N>). See them on <run page>:` followed by
  `- **<role>:** <caption>` per screenshot (reviewers in pipeline order, each in attach order). The run page is
  linked only when `FACTORY_PUBLIC_URL` is public (`Config.pageRef`), otherwise named. Images are never uploaded.
- **Warning "UI change reviewed without screenshots":** after the reviewers of a round (pipeline and follow-up
  rounds alike) have all returned a verdict, Agern checks the round's diff (`git diff --name-only <baseRef>...HEAD`
  in the worktree). When it touches UI files and neither the ux nor the ui-design stage of that round attached a
  screenshot, it records the warning `UI change reviewed without screenshots: the change touches <n> UI file(s)
  (<first three>, and <k> more), but neither the ux nor the ui-design reviewer attached a screenshot. Their summaries
  should say what they tried and what failed.` on both stages (`stages.warning`, migration V21), shown on the run page
  (`Run.warnings`, `Stage.warning`, §10), and comments it on the pull request (best effort):
  `**Agern: warning — UI change reviewed without screenshots** (<build | fix round N>). …` with the run page reference
  and up to ten of the files. It is a warning only: the run's status, the approver and the merge are unaffected.
  Whether a reviewer's summary names a concrete failed attempt is not judged by the server: the warning shows either
  way, and the summary is where a human reads why. Nothing is checked when the diff cannot be read.
  - *UI files* (`pipeline/UiChanges.kt`, a deliberately simple path heuristic): extensions `tsx jsx vue svelte astro
    css scss sass less styl html htm`, template extensions `hbs handlebars mustache ejs erb njk twig liquid j2 jinja
    jinja2 pug jte ftl cshtml razor heex eex`, `ts js mjs` inside a `components`, `pages`, `views`, `screens`,
    `layouts` or `templates` directory, and other non-code files under `templates/`. Never tests: paths with a
    `test`, `tests`, `__tests__`, `e2e`, `spec`, `specs`, `cypress` or `playwright` directory, or a
    `.test.`/`.spec.`/`.stories.` file name.
  - *Per project:* `.agern/ui-files` on the run's base branch (read from the base ref, so a run cannot switch off its
    own warning) replaces the defaults: one glob per line, `#` comments, `!glob` excludes; a glob without `/`
    matches the file name in any directory, `*`/`?` stay within a path segment, `**` spans segments, a trailing `/`
    means everything under it. Only exclusions: the defaults minus those. No rule at all (empty or comments only):
    no file counts as UI, so no warning.
- **Prerequisite:** reviewers run the app on the factory host, so for projects with a web UI a headless browser must
  be installed there. The Debian package *recommends* `chromium` (`apt install chromium fonts-noto-core`; the fonts
  keep text from rendering as boxes), and `agern doctor` warns when none of `chromium`, `chromium-browser`,
  `google-chrome`, `google-chrome-stable` is on the agents' `PATH` (a project's own Playwright browser is not
  checked), and also when the one it finds is Ubuntu's launcher for the chromium snap. Verified on Debian with Chromium 156: the agent user takes a screenshot with
  `chromium --headless --no-sandbox --disable-gpu --window-size=1280,800 --virtual-time-budget=8000 --screenshot=out.png <url>`.
  Without a browser the reviewers report that they could not take screenshots, the run shows the warning above, and
  it continues as usual.

### Releases

A **release** is a named plan of issues for one project, in the order they will be built. Once **started**, Agern
builds it unattended on the branch `release/<name>`: one issue at a time, each run branching from and squash-merging
into the release branch, without closing its issue. When nothing is left to build the release is **ready to
release**: Agern opens one pull request from `release/<name>` to the default branch, and its owner approves it,
which merges it so that each issue stays one commit, closes the issues and deletes the branch ("Releasing" below).
A release set to **automatic** is merged the same way without the approval, once it is complete, clean and its
checks are green ("Release mode" below). Delivered in steps (issue #13): planning (#17), starting and building
(#19), scheduled starts (#21, "Scheduled start" below), the release pull request, approval and merge (#20), and the
release mode (#41). Single runs are unaffected and still merge into the default branch.

- **Model.** Tables `releases` (`id`, `repo`, `name`, `description`, `status`, `created_by`, timestamps; once
  started also `owner_id`, `branch`, `started_at`, `ended_at`, and while paused `pause_reason`, `pause_note`,
  `resume_at`; for a scheduled start `owner_id` (set when scheduled), `scheduled_at`, `waiting_for` → `releases`
  (`ON DELETE SET NULL`), `start_failed_at`, `start_failed_owner`, `start_failed_reason`, with the partial index
  `releases_due` on `scheduled_at` for `status = 'scheduled'`; migrations V7 and V8; for releasing (V10) `pr_number`,
  `pr_url`, `pr_body_sha`, `target_branch`, `merge_state`, `merge_method`, `notice`, `ready_at`, `ready_announced`,
  `released_at`, `released_by`, `merge_sha`, `cleanup_pending`; for the release mode (V12) `release_mode` (`manual` |
  `automatic`, checked), `released_automatically`, `checks_state`, `checks_sha`, `checks_detail`, `checks_failure_sha`,
  `auto_merge_failed_sha`, `checks_head_seen_at`) and `release_issues` (`release_id`, `repo`, `issue_number`, `position`, `issue_title`, `issue_kind`,
  `added_by`, `added_at`, `state`, `run_id` = the issue's latest release run, `note`, and since V16 `retry_mode`
  (`continue` | `step` | null, checked; §6 "Retrying a run")). `runs` carry `release_id`
  (`ON DELETE SET NULL`) and `base_branch` (null = the default branch); for retries (V16) `continues_run_id` (→
  `runs`, `ON DELETE SET NULL`), `spec`, `builder_summary`, `failure_kind` and `failed_step`. Several releases may exist per project.
  Creating one creates no branch, no run and makes no GitHub write.
- **States.** Release `status`: `planning` (shown as "in preparation"), `scheduled`, `building`, `paused`, `ready`
  (shown as "ready to release"), `released`, `cancelled`. *Planned* means `planning` or `scheduled` (a scheduled
  release is a plan with a start time: the planning rules apply). *Started* means any later state. *Finished*
  (history) means `released` or `cancelled`; both are final. Issue `state` within a release: `waiting`, `running`,
  `merged`, `held`, `skipped`; all issues of a planned release are `waiting`. A `building` release with no `waiting`
  or `running` issue becomes `ready` ("Releasing" below); a `ready` release that gets an issue to build again (added
  or retried) is `building` again. **At most one release per project is `building` or `paused`**, enforced by the
  partial unique index `releases_one_building`; a `ready` release does not hold this slot, so the next release of
  the project can build while one waits for its approval.

  ```
  planning ⇄ scheduled
     │ start      │ due
     ▼            ▼
  building ⇄ paused          (building, paused and ready can be cancelled → cancelled)
     │ nothing left to build
     ▼
  ready ──(an issue added or retried)──▶ building
     │ owner approves, or automatic and green (or the pull request was merged on GitHub)
     ▼
  released
  ```
- **Names** are trimmed, match `^[A-Za-z0-9][A-Za-z0-9._-]{0,49}$`, contain no `..` and do not end with `.` or
  `.lock`, so they are safe as the git branch `release/<name>` and in URLs (400 otherwise). They are unique per
  project, case-insensitively, across all releases including future history (409 `release_name_taken`); renaming
  to another casing of the release's own name is allowed. A started release cannot be renamed (409
  `release_name_locked`). Descriptions are optional, at most 2000 characters. The branch name is only ever built
  from the validated name. **Reuse:** a released release is history and can never be deleted, so its name is never
  used again (409 `release_name_taken`; the next one is e.g. `1.4.1`). A cancelled release can be deleted by its
  owner or an admin, which frees the name; its branch stays on GitHub (cancelling never deletes it), so starting a
  new release of that name answers 409 `release_branch_exists` until a person deletes the old branch. Nothing local
  blocks the reuse: release runs have their own branches and worktrees (§6 "Worktrees and branches"), no worktree is
  ever made for `release/<name>` itself, and the owner's clone forgets the branch when the release is released
  ("After the merge" below).
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
  | `ready` | check again, approve and release | owner only |
  | `planning` | release mode (manual ⇄ automatic) | anyone who can see the project |
  | `scheduled`, started | release mode → automatic | owner only |
  | `scheduled`, started | release mode → manual | owner or admin |
  | `cancelled` | delete | owner or admin |
  | `released` | nothing (read-only history) | — |

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
    the release stays `scheduled` and waits behind it. Neither is a transient failure (§6 "Transient failures": GitHub
    5xx or a connection error, e.g. while creating `release/<name>`): the release stays `scheduled` and due, a warning
    is logged and the next tick tries again. Otherwise, still `scheduled` after the call is a start failure ("The
    release did not start."); any other exception is logged and one with "Internal error while starting the
    release; see the server log." Tests may substitute the starter.
  - *Start failure*: back to `planning` (owner, time and waiting cleared) with `start_failed_at/_owner/_reason`
    set and a feed frame; the owner sees it on the release page, the project page and in `/factory releases`.
    There is no automatic retry; the failure stays until the release is scheduled again. Reasons are fixed
    texts or `ApiException` messages, never tokens or raw exception text.
  - *Restart.* All state is in the database. A time that passed while the server was down is handled at the
    first tick, not skipped; `waiting_for` is recomputed by ticks.
- **Member branches.** In a workspace, `release/<name>` is created in a member repository only when one of its issues
  is about to be built (after the skip check, before the run): resolved once as for a single run, then, unless a
  `release_repos` row for that member exists already, created from the tip of the member's effective base branch with
  the owner's token and recorded (`repo`, `directory`, `submodule`, `base_branch`, `branch_created`). A missing base
  branch ("Branch `<base>` was not found in <org>/<member>"), an existing `release/<name>` there (409
  `release_branch_exists`: "Branch release/<name> already exists in <org>/<member>; delete it or rename the release."),
  a token that cannot create branches and a routing refusal all hold the issue ("Could not start a run: …") and the
  build goes on. A release with at least one such row is a **multi-repository release** ("Several repositories"
  below); one without behaves exactly as a release of a single repository.
- **Building.** Issues are built one at a time, lowest position first among `waiting` issues; no second run of a
  release starts while one of its issues is `running`. The builder (`ReleaseBuilder`) is event-driven — start, a
  run settling, resume, retry, adding an issue, restart and the resume ticker each call `advance`, which runs under
  a per-release mutex, re-reads the database and starts the next run only when the release is `building` and
  nothing runs. *Skip check*, at start and again at each issue's turn: an issue closed or missing on GitHub becomes
  `skipped` ("Closed on GitHub"); one with an active run outside the release becomes `skipped` ("Has an active run
  outside the release"). A skipped issue never blocks the build and can be retried. If a run cannot be started for
  another reason, the issue is `held` with the message and the build goes on.
- **Release runs** (of a member issue: §6 "Workspace runs", "member release run") are created as the release owner, with the owner's preferences as for single runs, with
  `releaseId` and `baseBranch = release/<name>`, and go through the unchanged pipeline except: the branch is
  `factory/release-<name>/issue-<n>-<slug>-<run>`; the worktree is created from the current tip of the release branch
  (fetched first), so it builds on the issues merged before it; the pull request targets the release branch and its
  body starts with "Issue #n · release `<name>`" with no closing keyword; build, review and approver prompts name
  the release branch as the merge target; on merge the issue is **not** closed and the comment reads "**Agern:
  merged** pull request #p into `release/<name>` (approved by …). The issue stays open until the release is merged
  into `<default branch>`." Release runs use the same per-user agent slots as all pipeline agents.
- **Outcomes.** A run that ends `merged` makes its issue `merged`; `needs_human`, `failed` or `cancelled` (also
  through the run's own cancel) makes it `held` with the run's error as note (or "Run failed"). Then the next issue
  starts. A run that waits for its checks (`waiting_for_checks`, §6 "Checks before a merge") keeps its issue `running`,
  and the release builds nothing else meanwhile; failed checks hold the issue like any other `needs_human`, so a broken
  issue is caught at its own run rather than at the release pull request. The orchestrator reports every settled release run (end of its pipeline, approve, cancel — not a
  cancelled coroutine) through a callback that the builder dispatches asynchronously; settling is idempotent and
  ignores runs no longer linked to an issue.
- **Held issues.** The owner can approve the held run (`POST /api/runs/{id}/approve`, `/factory approve`): when it
  merges the issue becomes `merged`, if the merge fails it stays `held` with the new reason (a branch that is behind
  is merged by GitHub's squash merge; a conflict fails; database migrations that collide with the release branch's
  are refused before the merge, §6 "Database migrations"). The owner can retry it, or anyone allowed can take it out.
- **Retry** (`POST /api/releases/{id}/issues/{number}/retry`, owner only): the release must be `building`,
  `paused` or `ready` (409 `release_not_building`; a `ready` one becomes `building` again, 409
  `release_already_building` while another release of the project builds), the issue `held` or `skipped` (409 `release_issue_not_held`). It becomes
  `waiting` at its position with note and run link cleared; a linked run that is still active (`needs_human`) is
  cancelled; the build continues. That is the default (`start_over`); with `{ "mode": "continue" | "step" }` the issue
  is continued from its run's commits or its run's failed step is retried, in its turn (§6 "Retrying a run").
- **Pause** (owner or admin): `building` → `paused`, reason `manual`, note "Paused by <username>" (else 409
  `release_not_building`). The running run is not stopped: it finishes, its outcome is recorded, and no further
  issue starts. **Resume** (owner only, needs a Claude credential): `paused` → `building` (else 409
  `release_not_paused`), clearing the pause, and continues.
- **Cancel** (owner or admin): `building`, `paused` or `ready` → `cancelled` with `ended_at` (else 409
  `release_not_building`; a released release: 409 `release_not_editable`). The `running` issue becomes `held` ("Cancelled with the release") before every active run
  of the release (running or `needs_human`) is cancelled through the orchestrator; `waiting` issues stay `waiting`.
  The release branch and what was merged into it are left alone; nothing starts afterwards. If the release has a
  release pull request, it is left open and gets one comment "**Agern:** release `<name>` was cancelled in Agern by
  @<user>. Agern will not merge this pull request; it and the branch `release/<name>` are left as they are." A
  cancelled release is final: it cannot be restarted and its name stays taken until it is deleted. A
  **multi-repository** release instead comments once on each open release pull request ("**Agern:** release `<name>`
  was cancelled in Agern by @<user>. Agern will not merge this pull request. The branch `release/<name>` is deleted in
  every repository of the release, which closes this pull request; the issues' own pull requests remain as the record
  of what was built.") and deletes `release/<name>` in every member repository where Agern created it and in the
  project repository (whose pointer commits must not stay mergeable once the member branches are gone), recording
  each deletion (`branch_deleted`; a missing branch counts as deleted). Failures do not fail the cancel; they become
  the notice "Cancelled, but could not delete `release/<name>` in <repo>: <message>[; …]", after the "Merged: …" part
  of the report when the release was partly released.
- **Usage limit.** The stage agent recognises the owner's Claude usage limit in the CLI stream: a
  `rate_limit_event` whose `rate_limit_info.status` is `rejected`, an `assistant` line with `error: "rate_limit"`,
  or a failing `result` whose text matches `usage limit|session limit|weekly limit|hit your limit`; the reset time
  comes from any `rate_limit_info.resetsAt` (epoch seconds, epoch milliseconds or ISO-8601; anything else is
  unknown). These names were read from the Claude Code 2.1.328 binary, not observed in a live limit, so parsing is
  tolerant. When a release run fails that way, the run is `failed` as usual but its issue goes back to `waiting`
  ("Interrupted by the Claude usage limit; it is built again"; continued from the run's commits when it has any, §6
  "Retrying a run"), and the release becomes `paused` with reason
  `usage_limit`, a note naming the limit and the reset time when known, and `resumeAt` = reset time + 1 minute (now
  + 30 minutes when unknown or past). The interrupted issue gets one comment, where the owner will look the next
  morning: "**Agern:** release `<name>` is paused. <note> It resumes by itself at <time> and builds this issue
  again; the owner can also resume it on <release page URL>". A ticker (every 30 s) resumes releases whose `resumeAt` has passed; if the
  limit is still reached it pauses again the same way. The owner may resume by hand.
- **Network.** A release whose next issue cannot start because GitHub or the Claude API cannot be reached is `paused`
  with reason `network` and resumes by itself (§6 "Transient failures"). Pause reasons: `manual`, `usage_limit`,
  `credentials`, `network`.
- **Rejected or missing Claude credential.** When the owner's credential is rejected (the existing detection) or
  missing when a run should start, the issue goes back to `waiting` and the release is `paused` with reason
  `credentials`, note "Claude rejected the owner's credential; update it in Settings and resume." and no
  `resumeAt`. (An owner without a GitHub token pauses it the same way, with a note saying so.) If the release is
  already `paused` or `cancelled` when such a failure arrives, only the issue changes.
- **Restart.** Interrupted runs are failed as before (§6). Then each `running` release issue is settled from its
  run: `merged` → `merged`, `needs_human` → `held`, `waiting_for_checks` → stays `running` (the wait is resumed and the
  run settles afterwards), anything else → `waiting` ("Interrupted by a server restart; it is
  built again"; continued from the run's commits when it has any); every `building` release continues, paused releases stay paused, and a due `resumeAt` is honoured.
  A run that failed in the instant before a restart, before its outcome was recorded, is rebuilt rather than held.
  A `building` release found with nothing left to build becomes `ready`. Every `ready` release is re-evaluated (its
  pull request opened if that had failed or not happened yet, reconciled with GitHub — merged there while the
  server was down finishes the release — and checked; an automatic one that is now green is merged), and a `released` release whose clean-up did not finish
  (`cleanup_pending`) is cleaned up again; closing skips issues that are already closed, so nothing is posted twice.
- **Deleting** a release in preparation removes the plan and its assignments only; the issues stay open on GitHub.
  A `scheduled` release cannot be deleted (409 `release_scheduled`, "Cancel the scheduled start before deleting
  this release"). A `building`, `paused` or `ready` release cannot be deleted (409 `release_active`, "Cancel the release first"); a cancelled
  one can (owner or admin): the plan and its assignments go, the branch stays on GitHub and its runs keep existing
  with `releaseId` null; for a multi-repository release every branch its cancel could not delete is first tried once
  more (best effort, logged), and its `release_repos` rows go with it. A released release cannot be deleted (409
  `release_not_editable`).

#### Releasing

- **Ready.** When `advance` finds a `building` release with no `waiting` and no `running` issue — every issue is
  `merged`, `held` or `skipped`, or it has none — the release becomes `ready` (`ready_at`; conditional on `building`,
  so a `paused` or `cancelled` release never becomes ready: a paused one does when it is resumed). This includes a
  release whose last issue was moved out or skipped. A ready release is one of three kinds, derived from its issues:
  **complete** (at least one `merged`, no `held` — only this kind can be approved), **incomplete** (at least one
  `held`) and **empty** (nothing merged, nothing held).
- **Held issues.** #20 says "ready when every issue is merged, held or skipped"; #13 says the pull request opens when
  every issue is merged or moved out, and that a release with held issues is shown as incomplete and cannot be
  approved until they are resolved. Both hold: the release is `ready` as soon as nothing is left to build, but no
  pull request is opened and approval answers 409 `release_incomplete` while an issue is held. The owner resolves a
  held issue in any of #13's ways: approve its held run (it merges into the release branch and the issue becomes
  `merged`), retry it (the release builds again), or move it out (owner or admin). Each of these re-evaluates the
  release; once complete, the pull request is opened. Skipped issues never block and are listed as not included.
- **Re-evaluation** (`ReleaseMerger.evaluate`, under the release's mutex, which the builder shares) runs when the
  release becomes ready, after every change to a ready release (an issue's state, an issue moved out, the
  description edited), on "Check again", at the start of every approval and after a restart. As the owner (every
  GitHub call of a release uses the owner's token), it: reads the repository (its default branch becomes
  `targetBranch`, its merge settings and the pull request's `rebaseable` decide `mergeMethod`); reconciles an existing pull request — merged on GitHub
  (by a person) finishes the release exactly as Agern's own merge would ("After the merge", with `releasedBy` null),
  closed without merging is forgotten and replaced; opens the pull request when the release is complete and has
  none (`findOpenPull(release/<name>)`, else `createPull`); rewrites its title and body when they changed (stored as
  a SHA-256, so it is "kept up to date" — new issues merged, an issue moved out, a new description — without
  needless writes); and checks mergeability. Problems (no token, GitHub refusing) go to `notice` and are retried by
  the next re-evaluation; nothing throws into the build. A change by someone else that the rules allow (an admin
  moving a held issue out or editing the description) can thus make Agern update or open the pull request with the
  owner's token — keeping it in step with the release is the owner's own standing intent — but only the owner can
  trigger a check or a merge. If opening the pull request fails the notice is "Could not
  open the release pull request: <message>".
- **The release pull request.** Title `Release <name>`. Body: "Release **<name>** of <org>/<repo>, built by Agern for
  @<owner's GitHub login>.", the description as written, "## Issues" with one line per merged issue in build order
  ("- #n <title> (pull request #p)"), "Held (not in the release branch yet): …" and "Not included (skipped): …" when
  there are any, and "Approved and merged from Agern by the release owner: <release page URL>. Each issue stays one
  commit on `<default branch>`." — preceded by "Release mode: **manual**." — or, for an automatic release, "Release
  mode: **automatic**. Agern merges this pull request for the release owner once it merges cleanly and all checks on
  it have passed: <release page URL>. Each issue stays one commit on `<default branch>`." Changing the mode rewrites
  the body. It contains no closing keyword ("Closes", "Fixes"): Agern closes the issues itself, only after the merge.
  It is merged by Agern only on the owner's approval or, for an automatic release, under the rules of "Release mode"
  below. Stored as `prNumber`/`prUrl`.
- **Mergeability.** `mergeState` is `unknown` until a pull request exists and has been read; then `clean` when
  GitHub's `mergeable` is true and `conflict` when it is false. While GitHub is still computing (`null`) it is read
  up to four more times, one second apart, then left `unknown` (approval then leaves the decision to GitHub's own
  refusal). It is checked at the moments listed under re-evaluation, and by the periodic re-evaluation ("Release
  mode" below).
- **Conflicts.** If the default branch moved (another release or a single run merged) and the release branch no
  longer merges cleanly, `mergeState` is `conflict`, the release stays `ready`, the release page says so, and the
  pull request gets one comment "**Agern:** `release/<name>` no longer merges cleanly into `<default>`. Agern will not
  merge it. Use "Bring up to date" on the release page (or `/factory release sync <name>`), or resolve the conflicts
  on the release branch and use "Check again": <URL>" (a release of several repositories keeps "Resolve the conflicts
  on the release branch, then use "Check again" on the release page: <URL>"). Approval answers 409 `release_conflict`
  and changes nothing. The owner decides: bring it up to date (below), resolve the conflict on the release branch by
  hand (e.g. merge the default branch into it and push) and "Check again", or cancel.
- **Database migrations.** A release branch whose migrations collide with the default branch's (e.g. another release
  merged the same version first) stays `ready` with the notice of §6 "Database migrations", without the ready
  announcement and without an automatic merge; approval answers 409 `release_migration_conflict`. The owner brings
  it up to date (below), or fixes the release branch by hand (merge the default branch into it, renumber the release's
  new migration) and uses "Check again".
- **Bringing a release up to date** (issue #63). A `ready` release of one repository whose branch conflicts with,
  is behind, or has migrations colliding with the default branch can be brought up to date by an agent run that
  merges the default branch into `release/<name>`. Nothing is ever merged into the default branch by it, and nothing
  is force-pushed.
  - *Model.* Every full evaluation of a `ready` single-repository release (also one without a pull request yet) and
    the periodic reconcile of a manual one compare `release/<name>` with the target in the owner's clone
    (`GitOps.compareBranches`, which also counts `behind`: `git rev-list --count <head>..<base>`) and store
    `releases.behind_by` (commits on the target not on the release branch; `NULL`: not known) and
    `releases.migration_conflict` (`MigrationCheck` found problems). A comparison that cannot be made is logged and
    leaves both unchanged; leaving `ready` resets them. `syncNeeded` = `ready`, not several repositories, a branch,
    and (`mergeState = conflict` or `behindBy > 0` or `migrationConflict`). `Release.sync` describes the release's
    latest sync run: `running` (queued, building, reviewing), `done` (merged), `failed` (failed or cancelled, nothing
    pushed) or `unreviewed` (failed or cancelled after it pushed: `runs.sync_pushed_sha` is set). It is derived from
    the run rather than stored in `releases.notice`, which every evaluation overwrites.
  - *Starting* (`POST /api/releases/{id}/sync`, `/factory release sync <name>`; no MCP tool), in this order: 404
    when the release is not visible; 409 `release_not_ready` unless started and `ready`; 403 unless the caller is the
    release owner or an admin; 409 `release_sync_unsupported` for several repositories ("Bringing a release of several
    repositories up to date is not supported yet; merge the base branch into `release/<name>` in each repository by
    hand."); 422 `claude_credential_required`; the caller's GitHub token (as for every GitHub action); 409
    `release_sync_merge_commits_disallowed` when the repository (read with the caller's token) forbids merge commits
    ("<repo> does not allow merge commits, and a release branch that contains a merge commit cannot be rebased; allow
    merge commits in the repository's GitHub settings first." — `methodFor` would otherwise leave no usable method).
    Then, under the release's mutex (so a start and an approval serialise): still `ready` (409 `release_not_ready`),
    no sync run in progress (409 `release_sync_running`, "Release "<name>" is already being brought up to date (run
    <id>)."; also enforced by an advisory lock in `RunStore.createSync`), and `syncNeeded` (409 `release_up_to_date`,
    "`release/<name>` is not behind `<target>` and has no conflicts. Use "Check again" if that changed."). **The run
    belongs to the caller** and uses only the caller's Claude credential and GitHub token: an admin who presses the
    button runs it as the admin, never on the release owner's credentials. `/factory release sync <name>` answers
    `Bringing release "<name>" up to date with <target>: run <id> merges <target> into release/<name>, resolves
    conflicts and renumbers migrations. Nothing is merged into <target>.`
  - *The sync run* is a row of `runs` with `kind = 'sync'` (every other run: `issue`), `issue_number = 0`, title
    "Bring release/<name> up to date with <target>", `base_branch = release/<name>`, the local working branch
    `factory/sync-<name>-<run8>` (never pushed under that name), the caller's `maxFixRounds`, no pull request. Its
    stages: builder and the four reviewers; spec-writer and approver `skipped` ("Not used when bringing a release up to
    date"). Statuses `queued` → `building` → `reviewing` → `merged` | `failed` | `cancelled` (shown as "up to date"
    when merged). Sync runs belong to no issue: they are left out of `activeRunFor`/`activeRunsByIssue` and the
    one-active-run-per-issue check, never block issue runs, but count as active runs of the project. Their agent
    sessions have no issue number and the titles `<role> · sync <name>`. The pipeline:
    1. *Set-up* (retried as a whole on transient failures): a worktree on the working branch at the up-to-date tip of
       `release/<name>`, the default branch fetched, submodules updated, the start commit remembered. The sandbox
       decision is the default branch's, as for release runs; no image hand-over.
    2. *Builder* (steerable): merge with `git merge --no-ff <defaultRef>`, never rebase, reset, amend or rewrite and
       never push; resolve keeping both sides' behaviour and check semantic combinations; renumber every migration the
       release adds above the default branch's highest (never touching one that is there; `scripts/check-migrations.sh
       <defaultRef>` must pass where it exists); build and test (the sandbox when there is one); when it cannot be done
       safely, put the branch back (`git merge --abort` / `git reset --hard <start>`) and say what is left; end with a
       summary per conflicted file, the renumbered migrations and the checks. The `<release>` block (name, description,
       merged issues) is data.
    3. *Verification by the server* before any push (`GitOps.mergeState`, `GitOps.compareWorktree`): the start commit
       (in a fix round: the commit pushed last) and `<defaultRef>` are ancestors of `HEAD`; no merge in progress, no
       unmerged paths, no uncommitted changes to tracked files; `MigrationCheck.problems` of `HEAD` against
       `<defaultRef>` is empty. Otherwise the builder stage and the run fail (`failureKind` `other`): "Could not bring
       `release/<name>` up to date with `<target>`: <reason>. Nothing was pushed." plus " The agent said: <first
       paragraph, ≤ 1000 characters>"; reasons in this order: "the branch no longer contains the release branch's
       commits (history was rewritten)", "`<target>` was not merged into it", "the merge is not finished (unmerged
       paths, a merge in progress or uncommitted changes)", "database migration conflict: <problems>", and "its
       database migrations could not be compared with `<target>`" (fails closed). A failing builder stage ends the
       same way. `HEAD` equal to the start commit: "Already up to date; nothing to push", reviewers skipped, `merged`.
    4. *Push*: `git push origin HEAD:refs/heads/release/<name>` with neither `--force` nor `--force-with-lease`
       (`GitOps.pushFastForward`; in fake-GitHub mode the local branch is moved by compare-and-swap, only as a
       fast-forward). Transient failures wait for the network; a rejection fails the run: "Pushing to
       `release/<name>` was refused because the branch changed meanwhile: <git output>. Nothing was pushed; use "Bring
       up to date" again." After a push: `sync_pushed_sha`, the release is re-evaluated in the background
       (`onReleaseBranchPushed` → `ReleaseMerger.evaluateLater`), and the release pull request gets "**Agern:** merged
       `<target>` into `release/<name>` (commit `<sha7>`, started by @<login>) to bring the release up to date.
       Reviewers are looking at the conflict resolution: <run page>".
    5. *Review*: the four reviewers, read-only, review only the merge (`git show --remerge-diff` and `--cc` of each
       merge commit in `git rev-list --merges <start>..HEAD`, `git log -p <merge>..HEAD`): both sides kept, semantic
       combinations, migrations renumbered and none of the default branch's touched. No screenshot note or UI warning.
    6. *Fix rounds* on `CHANGES` up to `maxRounds`: the builder conversation is resumed ("commit fixes on top; never
       amend, rebase or reset: the merge is already pushed"), then verification, push and review again. `CHANGES` at
       the limit fail the run: "Reviewers still request changes on the merge of `<target>` into `release/<name>`
       after N fix round(s): <roles>. The merge is already on `release/<name>` (commit `<sha7>`); look at the findings
       before releasing."
    7. *End*: all `PASS` → worktree and working branch removed, `merged`. Every ending re-evaluates and publishes the
       release (`ReleaseBuilder.settle`); a run that ends `failed` or `cancelled` after a push comments on the release
       pull request "**Agern:** the review of the merge of `<target>` into `release/<name>` (commit `<sha7>`) did not
       pass or did not finish: <first line of the error>. <run page>". Nothing is labelled and nothing is commented on
       an issue.
    Cancel: the run's owner or an admin, and cancelling the release cancels it (whoever started it). Retry: 409
    `sync_run` ("A run that brings a release up to date is not retried; use "Bring up to date" on the release
    again."), and every retry option is unavailable with that reason. A restart fails it with "Server restarted while
    the release branch was being brought up to date; use "Bring up to date" again."
  - *Holds.* While the latest sync run is `running`, approval answers 409 `release_sync_running` ("Release "<name>"
    is being brought up to date (run <id>); wait for it to finish or cancel it.", checked under the mutex) and an
    automatic release is not merged (its notice is left alone). While it is `unreviewed`, an automatic release is not
    merged and gets the notice "The merge of `<target>` into `<branch>` (run <id>) was pushed but its review did not
    pass or did not finish, so the release is not merged automatically. Look at the run, then approve by hand.";
    manual approval is allowed. `failed`, `done` or no sync run: as before. After a sync the release branch contains a
    merge commit, so it is merged with a merge commit instead of by rebasing (`methodFor`). The release is published
    when a sync run starts, pushes and ends; `/factory releases` adds under a `ready` release "Sync: running (run
    <id>)", "Sync: failed, nothing pushed: <error>" or "Sync: pushed but not fully reviewed: <error>".
- **Approval** (`POST /api/releases/{id}/approve`, `/factory release approve <name>`, `factory_approve_release`):
  **only the owner**; everyone else, admins included, gets 403 `forbidden` ("Only <owner>, who started this release,
  can do this"); a caller who cannot see the project gets 404 first. Admins may still cancel (they stop, never
  drive). The owner needs a GitHub token (403/422 as for every GitHub action). Then, in this order, each refusal
  changing nothing on GitHub: status `ready` (409 `release_not_ready`, "Release is <status>, not ready to release");
  no held issue (409 `release_incomplete`, "N issue(s) are held: #a, #b. Approve or retry their runs, or move them
  out of the release first."); something merged (409 `release_empty`, "Nothing was merged into the release branch,
  so there is nothing to release."); a re-evaluation; a pull request exists (409 `release_merge_failed` with the
  notice); no conflict (409 `release_conflict`, "`release/<name>` no longer merges cleanly into `<default>`. Nothing
  was merged."); no database migration conflict (409 `release_migration_conflict`, the notice of §6 "Database
  migrations" and " Nothing was merged."); a usable merge method (below); submodule pointers whose commits exist
  on their submodules' remotes (409 `release_submodule_commit_missing` / `release_submodule_check_failed`, §6
  "Submodule pointers", checked right before the merge). The pull request is read once more for its head commit, which is
  passed to GitHub's merge (`sha`), so a release branch that moves after the check is refused rather than merged
  unseen. Approvals of one release are serialised by its mutex; a second, concurrent approval finds it `released`
  and answers 409 `release_not_ready`. Check ("Check again", `POST …/check`) has the same owner rule.
- **Merge method: rebase, so each issue stays one commit.** Every issue reached the release branch as one squash
  commit `<title> (#<issue>)`. A *rebase merge* replays exactly those commits onto the default branch: one commit per
  issue, a linear history like the one single runs produce, and no extra commit. A *merge commit* also keeps them
  (they become reachable through the merge) but adds a merge commit and a non-linear history; a *squash* would
  collapse the release into one commit and is never used (`mergePull` refuses it). So approval uses `rebase`; it uses
  a merge commit titled `Release <name> (#<pr>)` instead if the repository reports `allow_rebase_merge: false`, or if
  GitHub reports the pull request as not rebaseable (`rebaseable: false`; e.g. the default branch was merged into the
  release branch with a merge commit to resolve a conflict) and the repository allows merge commits — a choice shown
  before approval (`mergeMethod`, on the release page and in the confirmation dialog). GitHub may not have computed
  `rebaseable` yet (null counts as rebaseable): if the rebase merge is then refused with 405 "This branch can't be
  rebased", Agern tries **once** more with a merge commit where the repository allows one (its settings are read
  again). The method actually used is stored (`mergeMethod` of the released release) and named in the closing
  comments ("rebased onto …" or "… with a merge commit …"). Not rebaseable and merge commits not allowed: no method
  works, so the release stays `ready` without the ready announcement and without an automatic merge, with the notice
  "GitHub cannot rebase `release/<name>` onto `<default>` (it contains merge commits, …), and <repo> does not allow
  merge commits; Agern never squashes a release. Allow merge commits …, or rebuild the release branch without merge
  commits, then use "Check again"."; approval (and a failed fallback) answers 409 `release_merge_failed` with the same
  text ending "approve again. Nothing was merged.". If the repository allows neither (`mergeMethod: unavailable`), approval answers 409
  `release_merge_method_unavailable` ("This repository only allows squash merges. Allow rebase merging or merge
  commits in its GitHub settings to release."). A setting GitHub leaves out counts as allowed; if GitHub then
  refuses the chosen method ("… merges are not allowed on this repository"), approval answers the same 409 with
  GitHub's message and a notice, and **never retries with another method** (only "can't be rebased" above falls back).
- **Release mode** (#41). Every release has `mode` `manual` (the default) or `automatic`, chosen when it is created
  (`POST …/releases { mode }`, the creation dialog) and changed with `PUT /api/releases/{id}/mode` or `/factory release
  mode <name> manual|automatic` until it is finished (`released`: 409 `release_not_editable`; `cancelled`: 409
  `release_closed`; an unknown value: 400). Who may change it: while `planning`, anyone who can see the project, as
  for every other part of the plan — whoever starts the release becomes its owner and the start dialog says that an
  automatic release will be merged with their token without asking them; once `scheduled` or started, switching to
  automatic is the **owner's alone** (it merges with the owner's token), switching back to manual is the owner's or
  an admin's (it only stops something; admins may stop, never drive). The mode is set under the release's mutex, so
  once a switch to manual has returned no automatic merge starts. **Agents cannot set it:** there is no MCP tool for
  it and `factory_create_release` always creates a manual release, so text in issues or chats can never make a
  release merge itself. `factory_approve_release` stays: it acts only on an explicit instruction in the owner's own
  chat and is the same owner-only approval as the button; the explicit, human-only mode setting is the stronger
  authorisation for unattended merging.
  - *Manual*: as before — the release waits in `ready` for its owner's approval; nothing merges it otherwise.
  - *Automatic*: at the end of every re-evaluation (not the one inside an approval), the server merges the release
    when **all** of these hold: status `ready`; complete (something merged, nothing held); a pull request exists;
    `mergeState` is `clean` (unknown waits, GitHub may still be computing); `mergeMethod` is `rebase` or `merge`; the
    checks on the pull request's head commit are green (`passed`, or `none` after the settle period, see "Checks"); the project allows it
    (`ReleaseModePolicy`, below). The merge is **the approval's own code path** (`mergeLocked`): the owner's token, the
    same method, the head commit the checks were read for passed as `sha` (a branch that moved meanwhile is refused,
    not merged unseen), and the same "After the merge". No language-model agent takes part in the decision.
  - *Blockers are never overridden*: held issues, a conflict, a database migration conflict, no usable merge method, an owner without a GitHub token
    and failed or unreadable checks all leave the release `ready` with the usual banner or notice. The owner can
    still approve by hand at any time (approval is never blocked by checks).
  - *Failed checks*: `notice` "Checks failed on pull request #n (<detail>), so it is not released automatically. …"
    and **one** comment on the pull request per head commit (`checks_failure_sha`): "**Agern:** checks failed on this
    release pull request (<detail>). Release `<name>` is set to release automatically, so Agern will not merge it while
    a check fails. …". Checks that cannot be read (e.g. a fine-grained token without the Checks / Commit statuses read
    permission): notice "Could not read the checks: … It is not released automatically until they can be read."
  - *GitHub refuses the automatic merge* (branch protection, a required review, the head moved): the release stays
    `ready` with the approval's notice, the pull request gets one comment "**Agern:** merging release `<name>`
    automatically failed: … Agern will not try again for this commit; …" (none for a conflict, which has its own), and
    the head commit is remembered (`auto_merge_failed_sha`): later re-evaluations do not try that commit again ("GitHub
    refused the automatic merge of the release branch's current commit; …"). "Check again" by the owner, switching to
    automatic again, or a new commit on the release branch tries again.
  - *Periodic re-evaluation.* GitHub sends Agern no webhook, so `ReleaseMerger.startTicker` re-evaluates every 60 s
    (`periodicMillis`; tests shorten it) each `ready` release that is automatic or whose checks are `pending` (so a
    manual release's page also shows when they finish; likewise `awaiting`), oldest first, each under its mutex; a release is published
    on the feed only when something changed. It runs in the services' scope and stops with them. Each round costs a
    handful of GitHub calls per such release with the owner's token (repository, pull request, combined status, check
    runs), well within the rate limit for the few releases a project has ready at once. Every other `ready` release
    with a pull request (a manual one) is only **reconciled**, with one read of its pull request: merged on GitHub by a
    person finishes it exactly as a check would ("After the merge", `releasedBy` null); closed without merging forgets
    the pull request with the notice "Pull request #n was closed on GitHub without being merged. Use "Check again" to
    open a new one, or cancel the release." (nothing reopens it by itself); open refreshes `mergeState` (a change to
    `conflict` gets its one comment), and a new head commit gets a full re-evaluation (its checks and merge method).
    The periodic round never merges a manual release. A GitHub read that fails during the periodic round (a transient
    network or GitHub error) is only logged and tried again next round: it does not replace the notice, and
    mergeability that GitHub is still computing does not replace a known `mergeState` (so a conflict is not commented
    on again).
  - *Released by.* `released_by` is the owner and `released_automatically` is true; the release page says "Released
    automatically <time> for <owner>", and each closed issue's comment ends "Released automatically by Agern for
    @<owner> (the release was set to automatic)." instead of "Approved by @<owner>.".
  - *Exactly once.* Automatic merges, approvals, checks and the periodic re-evaluation all run under the release's
    mutex and finish through the one conditional `ready` → `released` update, so concurrent triggers merge once.
  - *Human approval required (§6 "Merge policy").* `ReleaseModePolicy.allowsAutomatic(repo)` is true only for a
    project whose merge policy is `automatic` (read live). Otherwise creating or switching a release to automatic
    answers 409 `release_mode_not_allowed`, and an automatic release made before the project became strict is not
    merged automatically (notice "This project requires a human approval: …"); the owner approves it by hand — or,
    under `github`, merges the release pull request on GitHub.
- **Checks.** Each re-evaluation that reads an open pull request also reads the checks on its head commit with the
  owner's token: the combined commit status (`GET /repos/{o}/{r}/commits/{sha}/status`, the latest status per
  context), the latest check runs (`GET …/commits/{sha}/check-runs?filter=latest`, GitHub Actions and other
  apps) and the GitHub Actions workflow runs of the head commit (`GET /repos/{o}/{r}/actions/runs?head_sha=…`, the
  latest run per workflow and event): a workflow run that is queued but never started, or that ended in
  `startup_failure`, has no check runs at all. `checksState`: `failed` if any status is `failure`/`error`, any
  completed check run or workflow run did not end `success`, `neutral` or `skipped` (so `failure`, `cancelled`,
  `timed_out`, `action_required`, `stale`, `startup_failure` fail); otherwise `pending` if any status is `pending` or
  any check run or workflow run is not `completed`; otherwise `passed` (a passing workflow run is one passed check),
  or `none` when there are no statuses, check runs or workflow runs at all. A token that may not read Actions: the
  workflow runs are left out (logged) and the rest decides. These rules are shared with the checks before a run is
  merged (§6 "Checks before a merge"), one function for both (GitHub reports `state: pending` with `total_count: 0` then —
  that is not pending). **Settle period:** GitHub Actions and other CI register their checks only some seconds after
  the push or pull request event, so a head commit with no checks right after Agern opened the pull request (or the
  branch moved) says nothing yet. "No checks" is therefore `awaiting` ("No checks yet; waiting for them to appear
  until <time> …"), not green, until the head commit has had no checks for `FACTORY_RELEASE_CHECKS_SETTLE_SECONDS`
  (default 120), measured from when Agern first saw that SHA as the pull request's head (`checks_head_seen_at`,
  persisted, so a restart does not restart the period; a new head or a new pull request starts again; times use
  `Services.clock`). Only then is it `none`. Checks that appear meanwhile are `pending` and waited for as usual;
  `awaiting` releases are re-evaluated periodically, so the automatic merge follows on its own once the period ends.
  Comparing with the default branch's checks was considered and left out: workflows that run only on pushes, or
  only on pull requests, make it an unreliable signal. `none` and `passed` are green. A read GitHub refuses is `unknown` with the reason. Stored as
  `checks_state`, `checks_sha` and `checks_detail` ("Failed: build, lint", "Pending: ci/e2e", "3 checks passed", "No
  checks") and shown on the release page in both modes; for a manual release they are information only. Agern does
  not read branch protection: a required check that has not reported yet is invisible to it, and GitHub's own
  refusal of the merge covers that case.
- **GitHub refuses the merge** for another reason (branch protection, required checks, the head moved): the release
  stays `ready`, the pull request is read again (not mergeable → `conflict`, its comment, 409 `release_conflict`),
  otherwise `notice` = "Merging pull request #n failed: <GitHub's message>" and 409 `release_merge_failed` (a moved
  head: "…the release branch changed since it was checked…; check again, then approve").
- **After the merge** (Agern's own, or one found by re-evaluation), exactly once: `ready` → `released` with
  `released_at`, `ended_at`, `released_by` (null when merged on GitHub), `merge_sha` (the default branch's commit
  after the merge), `merge_method`, notice cleared and `cleanup_pending` set — one conditional update (`WHERE status
  = 'ready'`), and only its winner goes on. Then, best effort and each independently: every `merged` issue that is
  still open gets the comment "**Agern: released** in release `<name>`: pull request <url> was rebased onto
  `<default>`, one commit per issue (`<default>` at <sha>). Approved by @<login>." (or "…merged into `<default>` with
  a merge commit…", "Released automatically by Agern for @<login> (the release was set to automatic).", or "It was
  merged on GitHub.") and is closed; skipped issues and issues moved out are not
  touched. The branch `release/<name>` is deleted on GitHub (`deleteBranch` only ever gets the stored branch, which
  must be `release/<the release's name>`; an already missing branch is fine) — its commits are all on the default
  branch now, so nothing is lost. The owner's clone forgets the branch (its remote-tracking ref, and a local branch
  of that name unless a worktree uses it or it holds commits found nowhere else). Then `cleanup_pending` is cleared
  and anything that failed becomes the notice ("Released, but could not close #7: …"). The release's runs, their
  `factory/release-<name>/…` branches and pull requests are not touched (their worktrees and local branches went when
  each run merged). Agern does nothing further: no tag, no version; the repository's own GitHub Actions for pushes to
  the default branch run as usual.
- **Several repositories** (a multi-repository release, "Member branches" above). Ready, complete and empty are
  decided from the issues as for any release. A member repository is **touched** when a `merged` issue was built by a
  run with that `target_repo`; the project repository is touched when a `merged` issue was built in it or a touched
  member is a submodule (its pointer must move). Each touched repository gets one release pull request; the member
  ones live on their `release_repos` row (the same columns as the project repository's on `releases`).
  - *Re-evaluation* (all triggers above, owner's token): for each touched, unmerged member in **merge order** (the
    build-order position of its first merged issue) exactly what is done for the single pull request (reconcile,
    open `release/<name>` → the row's base branch, title and body by hash, mergeability, method from that
    repository's settings, checks with the settle period, the migration comparison in the member's clone). Then the
    **pointers**: for each touched submodule member the desired gitlink is its merge commit when merged, else the tip
    of its `release/<name>`; when any differs on the project repository's `release/<name>`, one commit (Git Data API,
    every changed gitlink in one tree) is put on that branch's tip **without force** (`Release <name>: point <dir>[,
    …] at release/<name>`, or `Release <name>: bump <dir> to <sha12>[, …]` when all are merge commits); a refused
    update (the branch moved) is retried once from the new tip, then becomes the notice "Could not move the submodule
    pointers on `release/<name>`: <message>". Then the project repository's pull request, when it is touched (a
    `.agern/workspace.yml` workspace whose touched members are not submodules has none: `prNumber` stays null).
  - *Texts.* A member pull request: title `Release <name>`; body "Release **<name>** of <org>/<workspace>: the part in
    <org>/<member>, built by Agern for @<owner>.", the description, "## Issues" (`- <org>/<workspace>#<n> <title>
    (pull request #<p>)`), "## Other pull requests of this release" (URLs) and the mode paragraph ending "Do not merge
    it by hand: Agern merges the member pull requests first and then moves the submodule pointers." The project
    repository's adds "## Submodule pointers" (`` - `<dir>` → <org>/<member> `release/<name>` (pull request <url>) ``),
    lists member issues as `- #<n> <title> (pull request <org>/<member>#<p>)` and ends the same way. No closing keywords.
  - *Ready to release* only when every touched repository has an open (or merged) release pull request and none has a
    conflict, a migration conflict or an unusable method; an automatic release also needs the checks of every open one
    green. The announcement goes once per transition to every open release pull request; a conflict comment to the
    one that conflicts; "Checks failed on pull request <org>/<repo>#<n> …" names it. The periodic round covers every
    open release pull request.
  - *Approval* (same people, same refusal order): every refusal that changes nothing on GitHub is checked for all
    unmerged release pull requests first (messages name a member's repository, e.g. "`release/<name>` of
    <org>/<member> no longer merges cleanly into `<base>`. Nothing was merged."). Then, under the release's mutex: each
    member pull request in merge order (its submodule guard in the member's clone, then `mergePull` with that
    repository's method and the head read before; stored as merged at once with the commit GitHub reports, so it is
    never merged twice); then, if the project repository is touched, the pointer step with merge commits only, every
    gitlink read back (`gitlinkAt`) and checked to equal the member's merge commit (`commitExists`), the release guard
    (§6 "Submodule pointers"), a fresh read and the merge of its pull request. The project repository's default
    branch is only ever changed by that last merge, so it never points at a commit that is not on a member's base
    branch. Checks on the final pointer commit are not waited for: with required checks GitHub refuses the merge, the
    release stops partly released, and the owner approves again once they are green.
  - *Partly released* (`ready` and at least one of its pull requests merged): any failure after the first merge stops
    at once and merges, retries or reverts nothing else; approval answers 409 `release_partially_merged` with the
    report "Releasing `<name>` stopped partway. Merged: <org>/<a>#<p> (`<base>` at `<sha12>`)[, …]. Not merged:
    <org>/<b>#<q>: <reason>[; <org>/<ws>#<r>: not attempted]. The pull requests that are not merged are left open for a
    human: fix the problem, then approve again on <release page> (Agern continues with what is not merged), or merge
    them by hand.", which is the notice and one comment ("**Agern:** releasing …") on every pull request still open.
    While partly released no issue can be added or retried (409 `release_partially_merged`: "Release "<name>" is
    partly merged; finish releasing it first."), and an automatic release is merged only by an approval or "Check
    again" (`auto_merge_failed_sha` is a SHA-256 over the sorted `<repo>:<head>` of the unmerged pull requests). A
    failure before any merge keeps the codes and "Nothing was merged." texts of a single release.
  - *Merged on GitHub.* A member pull request found merged is recorded with its merge commit; the release finishes
    (`releasedBy` null) only when every touched repository is merged. The project repository's found merged while a
    touched member is not is recorded (`pr_merged_at`) with the notice "Pull request #<n> of <org>/<workspace> was
    merged on GitHub before the member pull requests. Approve to merge those; the submodule pointers must then be
    bumped by hand."; approval then merges the members and finishes, keeps the submodule members' `release/<name>` and
    the notice lists "`<dir>`: bump to `<sha12>`, then delete `release/<name>` in <org>/<member>".
  - *After the merge* each merged issue's closing comment names the release pull request of its repository and that
    repository's base branch and merge commit; `release/<name>` is deleted in the project repository and in every
    member repository where Agern created it (touched or not), and forgotten in the owner's workspace and member clones.
  - *Merge order* in a `.agern/workspace.yml` workspace is the same; there are no pointers to move unless a touched
    member is a submodule.
  - *API.* `Release.repositories` lists the member repositories in merge order (untouched last), then the project
    repository; a viewer who cannot read a member gets `accessible: false` without its pull request link, checks and
    merge commit (HTTP responses and `release` feed frames, per subscriber). `/factory releases` and
    `factory_list_releases` list every release pull request and mark member issues ` [member <dir>]`.
- **History.** Released and cancelled releases stay listed (newest first on the project page, collapsed under
  "History") with their read-only release page: what they contained (issues, their runs and pull requests), who
  started and released them and when, the release pull request and the merge commit.
- **Editing rules.** `ready`: the rules of a started release apply; issues can be added by the owner and are built
  (the release becomes `building` again; 409 `release_already_building` while another release of the project builds
  — #13 lets issues be added to a building release, and keeping that true until approval is simpler than a special
  refusal); held or skipped issues may be moved out by the owner or an admin; merged issues cannot leave (409
  `release_issue_started`). `released`: read-only for everyone — rename, edit, delete, assign into, move out of,
  reorder, retry and cancel answer 409 `release_not_editable` ("Release "<name>" is released and kept as history.").
  The same rules hold for the HTTP API, `/factory` and the MCP tools. A reopened issue of a released release stays
  in it and cannot be planned again; file a follow-up issue.
- **Notifications.** Everything is on the release page and the project list live (feed frames); GitHub comments are
  best effort, use the owner's token, start with `**Agern` (so the pipeline's issue reader ignores them) and are
  posted once per transition (repeated checks that find the same state post nothing):

  | When | Where |
  |---|---|
  | ready and complete (also again after building again) | the pull request: "**Agern: release `<name>` is ready to release.** k issue(s) merged. @<owner> can approve it on <URL> or with `/factory release approve <name>`." — automatic: "… It is set to release automatically: Agern merges it for @<owner> once it merges cleanly and all checks on this pull request have passed. It can be switched to manual approval on <URL>." (not while it conflicts; posted once the conflict is resolved) |
  | automatic, checks failed (once per head commit) | the pull request (see "Release mode") |
  | automatic, GitHub refused the merge (once per head commit) | the pull request (see "Release mode") |
  | ready with held issues | each held issue: "**Agern:** release `<name>` has finished building, but this issue is held. The release cannot be released until its run is approved or retried, or the issue is moved out: <URL>" |
  | paused on the usage limit | the interrupted issue (see "Usage limit") |
  | `mergeState` becomes `conflict` | the pull request (see "Conflicts") |
  | released | each closed issue (see "After the merge") |
  | cancelled with a pull request | the pull request (see "Cancel") |

  There is no e-mail or push notification.
- **Runs and the finished release.** No run of a release is active when it is released (a held run blocks approval;
  moving an issue out cancels its run). Cancelling a ready release cancels a held issue's `needs_human` run as for a
  building one. A single run of an issue that is (or was) in a release behaves as today.
- **Live.** Every change, including every state change of a release or of one of its issues, emits a `release`
  feed frame per affected release (a move: source and target; also set, change and cancel of a schedule, waiting
  set or cleared, a start failure, ready, a new pull request, a mergeability or notice change, released and the
  end of the clean-up), deletion `release_deleted` (§8). `updatedAt`
  strictly increases on every change at millisecond resolution.
- **Fake-GitHub mode.** The local clone has no remote: the release branch is created locally from the clone's
  `HEAD` when first needed, and merges are not applied to it, so each run starts from the same local release
  branch tip. Real mode fetches the merged tip from GitHub. The fake GitHub models what releasing needs: pull
  requests remember head and base; commits per branch (a squash merge adds one, a rebase merge replays the head's
  own commits, a merge commit adds them plus the merge commit), so tests can count commits per issue on `main`;
  `conflicting` head branches (not mergeable, merging fails); mergeability "still computing"; the repository's
  merge settings; deleting a branch closes its open pull requests, as on GitHub; commit statuses and check runs per
  head branch (`setStatus`, `setCheckRun`; none: no checks), and a failure to read them. In development
  (`FACTORY_DEV_FAKE_GITHUB`) `<dataDir>/fake-github-checks/<repo>/<branch>.json` (`{"statuses": {"ci": "pending"},
  "checkRuns": [{"name", "status", "conclusion"}]}`) sets the checks of a branch while the server runs, and
  `"conflict": true` makes its pull requests conflict (reported and refused on merge like `conflicting`) while the
  file says so; the e2e suite uses both.

### Generated images

Agents can have graphics made by an image AI the user chose (issue #11). Claude does not generate images itself.

- **Providers:** `dk.egernet.factory.images.ImageProvider` implementations — `OpenAiImageProvider` (Images API,
  `Authorization: Bearer`), `GeminiImageProvider` (Interactions API, `x-goog-api-key`, no transparent backgrounds) and,
  only in fake-GitHub mode, `DevFakeImageProvider` (`fake`, keys `fake-image-…`, draws PNGs; `fake:refuse` and
  `fake:quota` in a prompt fail). Nothing outside the package knows provider names: `GET /api/config` lists the
  catalogue and the web renders it. The HTTP providers never follow redirects (a key is never sent to another host),
  send the key only in a header, connect within 10 s and wait at most 300 s. Model names are free text
  (`^[A-Za-z0-9._:-]{1,100}$`); the catalogue's models are suggestions.
- **Setting:** one provider per user (`users.image_provider`, `image_model`, `image_key_enc`), set with
  `PUT /api/me/image-provider` after the provider accepted key and model, removed with `DELETE` (images stay). Codex
  is connected by its device login instead (§4 "Codex login"). Each catalogue entry says how it is connected
  (`auth`: `api_key` or `device_login`) and whether it can be used on this server (`available`,
  `unavailableReason`; Codex is unavailable when `FACTORY_CODEX_BIN --version` does not exit 0 within 10 s, checked
  at most once a minute).
- **Key throttle:** a key the provider rejects counts against its user; after 5 within 15 minutes, further `PUT
  /api/me/image-provider` calls answer 429 `too_many_attempts` (`lockedUntil` = when the oldest counted rejection is 15
  minutes old) without calling the provider, so the route cannot be used to test stolen keys. Malformed input does not
  count and a successful save does not reset the count. In memory (`AttemptLimiter`).
- **Tool:** `factory_generate_image` (§7) for the chat agent, the spec-writer and the builder (fix rounds included).
  The service also derives the session kind from the grant and refuses any other, and for a run checks that the run
  belongs to the grant's repository and owner. Order: who may call, arguments, provider configured, transparency
  (JPEG has none; Gemini cannot), limits, provider call (on `Dispatchers.IO`), result checks.
- **Limits:** per call (`FACTORY_IMAGE_MAX_PER_REQUEST`), per chat message (`…_PER_TURN`; the counter resets when the
  owner's next non-command chat input is accepted) and per stage agent (`…_PER_STAGE`), reserved before the call and
  released for images not delivered; edges 256…`FACTORY_IMAGE_MAX_EDGE`. In-memory counters (a restart resets them).
  A returned image over 20 MiB, not PNG/JPEG/WebP by its magic bytes or without readable dimensions is dropped.
  Agern never resizes or converts; the tool's `notes` say when the result differs from what was asked.
- **No provider:** a tool error without a provider call. The chat agent tells the user to add one under
  Settings → Image generation; a pipeline agent continues without images, says so in its summary, and the run does
  not fail for it. Provider failures are tool errors in plain words, with the key redacted from any provider text.
- **Storage:** table `generated_images` (bytes included; `origin` `chat` or `run`; `session_id` without a foreign key
  so a closed chat's selected images keep it). Agent copies: chats under
  `$FACTORY_DATA_DIR/generated-images/<sessionId>/<id8>-<slug>.<ext>` (absolute `path`), runs under
  `<worktree>/.agern-run/images/<id8>-<slug>.<ext>` (relative `path`). Whenever the server creates
  `<worktree>/.agern-run/` it writes `.agern-run/.gitignore` containing `*`, so git ignores the directory without any
  change to the repository. **Symlinks are never followed:** `.agern-run` and `.agern-run/images` must be absent or
  real directories (checked with `NOFOLLOW_LINKS`) whose real path lies inside the worktree's; a file is written by
  removing whatever is at its name (a symlink itself, not its target) and creating it with `CREATE_NEW` and
  `NOFOLLOW_LINKS`. The tool checks this before the limits and the provider call ("Images cannot be written:
  `.agern-run` in this working directory is not a plain directory."); the run hand-over fails the run ("Prepared
  images could not be handed over: …"), which unlocks the selection. Directory removal walks the tree without
  following links. Each generation appends an `images` event to the session (§8) and, in a run, publishes the
  run so the run page updates.
- **Selection:** the owner of a chat image selects it for an open issue under a short name (`PUT
  /api/images/{id}/selection`), moves, renames or unselects it, or discards an unselected one; at most 20 per issue.
  The conditions are part of the SQL statements. A selection belongs to the issue: any user's run of it receives it.
- **Run hand-over:** when a run starts (after the sandbox decision) it takes all selected images of its issue
  (`used_by_run_id`; not those held by another run that is still active or merged), writes them to
  `.agern-run/images/prepared-<nn>-<slug of name>.<ext>` and lists them in an `<images>` block after the issue text of
  the spec-writer's and builder's task prompts (not the fix prompt); those stages also note "Prepared images: …" in
  their transcript. A selection is **locked** while its run is not `failed` or `cancelled`; a retry gets the same
  images. A run for an issue without selected images, in which no agent calls the tool, has byte-identical prompts
  and no `.agern-run` directory.
- **Cleanup:** closing a chat deletes its unselected images and its image directory; discard deletes at once; run
  images go with their run row (cascade) and their files with the worktree. There is no time-based reaper.
- **Prompts:** `chat.md`, `spec-writer.md` and `builder.md` say when to use the tool (whenever graphics are asked for,
  never otherwise), to write the prompt from the project's existing visual style, to read the returned file, and what
  to do without a provider. The spec-writer names the needed images in the spec; the builder uses prepared images,
  generates the rest and adds what it uses where the project keeps assets, never committing `.agern-run/`.

## 7. Chat agent ↔ factory (MCP)

The server exposes an MCP endpoint (`POST /mcp`, streamable HTTP, no sessions). Every agent
process gets its own bearer token in its `--mcp-config`; the token maps to the session and thereby
to the owner and the repo, and dies with the process. These per-process tokens are kept in memory only
(`McpRegistry`). Users can also create personal tokens for Claude on their own computer (below).

### Personal tokens

A user can create a personal MCP token in Settings › "Claude on your computer" and register Agern as an HTTP MCP
server in Claude Code on their own computer (`claude mcp add --transport http <name> <FACTORY_PUBLIC_URL>/mcp
--header "Authorization: Bearer <token>"`). The token acts as that user in one project.

- *Format and storage.* `agern_pat_` followed by 32 random bytes (URL-safe base64, 256 bits). Table `mcp_tokens`
  (migration V23) holds `user_id`, `repo`, `name`, `created_at`, `expires_at` (null: never), `last_used_at`,
  `revoked_at` and `token_hash` = SHA-256 (hex) of the whole token — never the token itself. A salt or a slow hash
  would add nothing with 256 random bits; lookup is by hash equality. The token is shown exactly once, in the answer
  to its creation (`Cache-Control: no-store`); it is never logged, never written to `auth_audit` and never returned
  again. Creating one needs a full (2FA) web session; `/mcp` ignores cookies.
- *Scope.* One user, one project (the token's `repo`), and the tools `McpRegistry.PERSONAL_TOOLS`: the chat tools
  without `factory_generate_image`. Calls behave as for the chat agent of that user in that project, with the same
  permission rules (the same service methods are called): `factory_approve_run` and `factory_send_to_agent` only for
  the user's own runs, `factory_approve_release` only for their own releases, `factory_start_run` without a Claude
  credential answers `Register a Claude credential in Settings first`. There is no session: runs started with a
  personal token have no `startedBySessionId`, messages to agents are recorded with `origin: "direct"`, software
  requests have no session. `factory_approve_run` and `factory_cancel_run` refuse a run of another project (`Run
  belongs to another project`), like `factory_get_run`. Any other tool (sandbox, screenshot, image) is refused as
  `Tool <name> is not available to this agent`.
- *Why no image tool.* `factory_generate_image` belongs to an agent session: files are written on the server for
  that agent, the result is offered for selection in the chat, and the budget is per chat message. A local Claude
  could not read the returned server path.
- *Validity, checked on every request.* The row exists and is not revoked; `expires_at` is null or after now; the user
  row still exists (it cascades on delete); and `Access.canSee(user, repo)` holds. Visibility comes from the user's own
  GitHub token and is cached for 60 s, so losing GitHub access takes effect within a minute; removing the GitHub
  token in Settings invalidates the cache, so it takes effect at once. A refused token gets 401 with JSON-RPC error
  `-32001` and `This token was revoked`, `This token has expired` or `The token's user can no longer see <org>/<repo>`;
  a missing bearer or one that matches nothing gets `Unauthorized`. A used token's `last_used_at` is updated at most
  once a minute.
- *Revocation and expiry.* The user revokes their own tokens in Settings, an admin anyone's in Admin; revoking takes
  effect with the next request. Expired tokens stay listed (marked expired) until revoked. Revoked rows are kept for
  30 days, so a stale client configuration is still "known" and does not count as guessing, then deleted at startup.
  At most 50 tokens that are not revoked per user; names are unique per user and project (case-insensitive).
- *Resolution order and throttle.* A live per-process token is accepted first and is never throttled, so a block on
  `127.0.0.1` cannot stop agents. Otherwise a blocked client address gets 429 with `Retry-After: <seconds>` and
  `Too many failed attempts. Try again after <ISO time>.` before the token is looked up. A failure counts only when a
  bearer was sent and matches neither a per-process token nor any stored row (revoked and expired ones are known);
  20 counted failures from one address (the TCP peer) within 15 minutes block it until the oldest leaves the window.
  The failure that starts a block writes `mcp_token_locked` to `auth_audit` (with `remote`) and a WARN line. State is
  in memory; behind a reverse proxy all clients share the proxy's address, as for logins (`X-Forwarded-For` is not
  trusted).
- *Identification.* `instance` is the host of `FACTORY_PUBLIC_URL` plus `:<port>` when not the default. The suggested
  server name (`McpIdentity.serverName`) is `<instance>-<repo>` in lower case with every run of other characters than
  `[a-z0-9]` replaced by `-`, prefixed with `agern-` unless it starts with `agern`, at most 64 characters, e.g.
  `agern-example-agern`. For a personal token `initialize` answers `serverInfo { name: <server name>, title: "Agern ·
  <org>/<repo> (<instance>)", version }` and instructions naming the public URL, the project and the user, and every
  tool description in `tools/list` is prefixed with `[<org>/<repo> on <instance>] `. So several Agern instances and
  projects can be registered side by side in one Claude. Per-process tokens keep `serverInfo.name: "agern"` and the
  unprefixed descriptions.
- *Attribution.* Every `tools/call` with a personal token writes one INFO log line with the tool, token id and name,
  username, repo and outcome (`ok` / `error`) — never the token, its hash or the arguments. Creation and revocation
  are written to `auth_audit` as `mcp_token_created` and `mcp_token_revoked` (detail `"<name>" for <repo>`, plus ` by
  admin <username>` when an admin revokes another user's token).
- *Transport.* Personal tokens reach `/mcp` through `FACTORY_PUBLIC_URL` (a reverse proxy in front is fine; the proxy
  must pass `POST /mcp` and not buffer its event streams). Requests with an `Origin` header are still refused (403):
  browsers may not call the endpoint. Streaming, progress notifications and the 405 answers below apply unchanged.
  Authentication and the throttle complete before anything is streamed.
- *Workspaces.* For a workspace project the token is bound to the workspace's own repository, whose visibility it
  requires; individual tools keep their member-visibility rules.

### Transport

A request answered within 5 s gets one `application/json` body (notifications: 202; auth and parse errors their own
status). A slower one from a client whose `Accept` contains `text/event-stream` is answered as an event stream, for
every tool: 200, `Content-Type: text/event-stream`, `Cache-Control: no-cache`, and the comment `: agern` flushed at
once (so the client's first-byte budget is met); every 15 s while the call runs a `notifications/progress` event
(`progressToken` as the request's `params._meta.progressToken`, `progress` the elapsed whole seconds, strictly
increasing, `message: "Still running (<n> s)"`) or, without a token or for a batch, the comment `: keep-alive`; and
finally `event: message` with the JSON-RPC response on one line, after which the stream is closed. A slow request
from a client that does not accept a stream gets one JSON body at the end, as before. A client that disconnects only
ends the writing (logged at debug level); a sandbox command the call started runs on. The `Origin` check and the
bearer check happen before anything is streamed. `GET /mcp` and `DELETE /mcp` answer 405 (no server-initiated
streams, no sessions, no resumption).

### Tools

Tools: `factory_list_issues`, `factory_get_issue`, `factory_create_issue`, `factory_start_run`,
`factory_list_runs`, `factory_get_run`, `factory_approve_run`, `factory_cancel_run`,
`factory_send_to_agent`, `factory_request_software`, `factory_list_releases`, `factory_create_release`,
`factory_assign_issues`, `factory_unassign_issues`, `factory_approve_release`. Personal tokens get these
(`PERSONAL_TOOLS`, see above). `factory_approve_run` ("Merge a run that waits in needs_human. Only the run's owner may
do this. The merge waits for the checks on the pull request's head commit: passed checks merge at once, pending ones
leave the run waiting_for_checks until they pass, failed ones leave it in needs_human.") takes only `runId`: no tool
and no argument merges despite the checks (§6 "Checks before a merge"). `factory_approve_run` and
`factory_approve_release` are both refused (tool error with the `merge_on_github` message) for a project that merges
on GitHub (§6 "Merge policy"). Pipeline-stage agents get only `factory_get_issue`
and `factory_request_software`; with sandboxes switched on (`FACTORY_SANDBOX` not `off`) the builder also
gets `factory_sandbox_info`, `factory_sandbox_exec` and `factory_sandbox_wait`, the spec-writer `factory_sandbox_info`
only, and the `ux` and `ui-design` reviewers of pipeline review rounds of an approved run the three sandbox tools for
the review sandbox; the `ux` and `ui-design` reviewers also get `factory_attach_screenshot`. The chat agent, the spec-writer and the builder also get `factory_generate_image`. The advisor (§6 "Conversation at needs human") gets the same two stage tools, bound to its
run, and nothing else: no approve, cancel, start, release, sandbox, screenshot or image tool.

Image tool (§6 "Generated images"), chat, spec-writer and builder only:
- `factory_generate_image { prompt, purpose, size?, format?, count?, transparentBackground?, issueNumber? }` —
  `prompt` 1–4000 characters; `purpose` one line, 1–200 characters (e.g. "app icon"); `size` `WIDTHxHEIGHT` (default
  `1024x1024`, each edge 256…`FACTORY_IMAGE_MAX_EDGE`); `format` `png` (default), `jpeg` or `webp`; `count` 1…
  `FACTORY_IMAGE_MAX_PER_REQUEST` (default 1); `issueNumber` a hint for the chat UI. Answers JSON text
  `{ "images": [{ "id", "path", "mediaType", "width", "height", "bytes" }], "provider", "model", "notes": [...],
  "remaining", "next" }`. Refusals and provider failures are tool errors, e.g. `No image provider is configured for
  <user>. …`, `Limit reached: at most 8 images per chat message (0 left). …`, `<Label> rejected your API key. …`,
  `Only the chat agent, the spec-writer and the builder can generate images`.

Screenshot tool (§6 "Screenshots"), `ux` and `ui-design` reviewers only:
- `factory_attach_screenshot { path, caption }` → text such as `Attached screenshot 2 of at most 12 for this stage:
  "<caption>" (image/png, 1440×900, 212.4 KB).` `path` is relative to the agent's working directory (the run
  worktree) or absolute inside it; the file is copied and left as it is. Refusals are tool errors (`isError`), never
  JSON-RPC errors: `Only the ux and ui-design reviewers can attach screenshots` (also for a hand-built grant whose
  session is not a `ux`/`ui-design` stage of its run, or has no run), `path must be a file inside your working
  directory` (outside, `..`, a symlink pointing out, a directory), `No such file: <path>`, `caption is required: say
  what the screenshot shows`, `caption is too long (at most 200 characters)`, `The image is larger than <size>`,
  `The file is empty`, `Only PNG, JPEG, GIF or WebP images are accepted`, `This stage already has <n> screenshots
  (the maximum)`.

Sandbox tools (§5 "Sandboxes", "Who gets the tools"); they act on the sandbox of the token's run and scope — the
build sandbox for the builder, the review sandbox for the `ux` and `ui-design` reviewers, nothing to run for the
spec-writer (`exec` and `wait` with a plan scope are refused: `The spec-writer cannot run commands in the sandbox; …`)
— and a token without a run (a chat) is refused:
- `factory_sandbox_info {}` → JSON `{ available, state, reason }` and, when the run has a sandbox,
  `image`, `created` (the default container exists), `workdir: "/workspace"`, `user: { name, uid, note }`
  (only with `user`), `platforms: [{ name, image, setup, created }]` and, when there are any,
  `platformNote`; `services: [{ name, host, image, ports, readiness }]` (ports from the image's `EXPOSE`,
  known once created; `readiness`: `unchecked`, `checked on creation` or `ready`) and, when a service has
  no check, `servicesNote` (it may still be starting); `caches`, `env` (names only), `setup` (boolean),
  `limits: { cpus, memory, execTimeoutSeconds, outputTailChars, toolCallTimeoutSeconds }`,
  `capacity: { inUse, max, heldByThisRun }` and, while a command runs, `command: { handle, running: true, elapsedMs }`.
  For the spec-writer it adds `access: "plan"` and a `note` (it cannot run commands; the builder does; the UI reviewers
  get a separate read-only review sandbox) and never creates anything. For a reviewer it describes the review
  sandbox: no `platforms`/`platformNote`, plus `access: "review"`, `readOnly: true`, `outputDir: { container:
  "/run/agern-review", host: ".agern-run/review" }` (or `null` with an `outputNote`) and a `note` on the read-only
  `/workspace`, the output directory, starting an app detached and the other UI reviewer; `created`, `command` and
  `capacity` refer to the review sandbox.
- `factory_sandbox_exec { command, timeoutSeconds?, platform?, background? }` → JSON `{ handle, exitCode, output,
  truncated, durationMs, timedOut }` plus `platform` when the definition has platforms (`exitCode` −1 when timed out;
  `output` is the tail). `platform` omitted or `default`: the top-level image. With `background: true` (after the
  same validation and, if needed, creation) → `{ handle, running: true, timeoutSeconds, note }` plus `platform` at
  once. In the review sandbox `platform` other than `default` is refused (`The review sandbox has only the default
  image; leave platform out.`), and without a free slot the answer is `The server's sandboxes are all in use (<n> of
  <n> containers). No review sandbox was created and nothing was run. Review without it, on the host, as your role
  instructions describe; do not wait for a slot.` No sandbox for the run, an unknown platform, busy, a failed creation, readiness check or setup, an empty
  command, a non-positive timeout, `background` that is not a boolean (`background must be true or false`) and a
  command while a background one runs (`A command is still running in this run's sandbox (handle cmd-…, started
  <n> s ago). Wait for it with factory_sandbox_wait before starting another.`) come back as tool errors.
- `factory_sandbox_wait { handle?, waitSeconds? }` → for a finished command `{ handle, running: false, exitCode,
  output, truncated, durationMs, timedOut }` plus `platform` and `note` as for `exec`, the same on every repeat; for
  one still running when `waitSeconds` is over `{ handle, running: true, elapsedMs, note }`. `handle` omitted or blank:
  the most recently started command; `waitSeconds` omitted: until the command ends, `0`: at once, above the command
  limit: capped. Tool errors: a chat, no sandbox (as `exec`), `No sandbox command has been started in this run yet.`,
  `Unknown handle '<at most 64 characters>'. Handles come from factory_sandbox_exec; older results are dropped after
  20 newer commands.`, `waitSeconds must be a whole number of seconds, 0 or more`, and a command that ended abnormally
  (stopped by an admin, removed with the run, could not be run), with the same message every time.

Release tools (§6 "Releases"), chat agents only; the three mutating ones also refuse run-bound tokens:
`factory_list_releases {}` (the `/factory releases` text, with release ids), `factory_create_release
{ name, description? }`, `factory_assign_issues { release, issueNumbers }` (`release`: a name — case-insensitive —
or id of a release of this project; `issueNumbers`: an array of integers, `"#5"` accepted; assigns or moves) and
`factory_unassign_issues { issueNumbers }`. `factory_list_issues` appends ` [release <name>]` to assigned issues,
and for a workspace project ` [member <directory>]`, ` [no member label]` or ` [several member labels]` to every
issue (§6 "Workspace runs"); `factory_start_run`, `/factory run`, `factory_assign_issues` and `/factory release add`
report the routing refusals as their message, including the `gh issue edit … --add-label` hint.
`factory_approve_release { release }` (a name or id; chat agents only, refused for run-bound tokens) approves a
`ready` release as the chat's owner, with the same checks and refusals as the HTTP endpoint (§6 "Releasing") as tool
errors, and answers `Release "<name>" is released: pull request <url> merged into <default>, N issue(s) closed.`;
the chat prompt tells the agent to call it only when the person explicitly tells it to release that specific release.
There is deliberately no tool for the release mode (§6 "Release mode"), and `factory_create_release` ignores any
`mode` argument: a release created by the chat agent is manual, and only a person can make one release by itself.

`factory_send_to_agent` `{ runId?, role?, sessionId?, message }` passes the person's message to a running
spec-writer or builder of a run in this project (§6 "Messages to running agents"): by `runId` + `role`
(`spec-writer` | `builder`; without `role`, the run's running one of the two) or by the agent's `sessionId`.
The chat's owner must own the run; the same checks and refusals as the HTTP endpoint apply and come back as
tool errors. The transcript entry records `origin: 'chat'` and the chat's session id. The answer says the
message is queued for the agent's next step, never that it was already applied.

Chat input starting with `/factory` is handled by the server and not sent to Claude (with attachments it
is rejected with 422 `attachments_not_allowed`; they stay unsent so the owner can send them in a normal message):
`/factory run <issue>`, `/factory status`, `/factory approve [runId]` (refused with the `merge_on_github` message for a
run of merge policy `github`, §6 "Merge policy"), `/factory cancel <runId>`,
`/factory retry <runId> [step|continue|over|recommended]` (owner only; without a mode it lists the options, the
recommendation and its reason; a release run is retried through its release),
`/factory releases`, `/factory release create <name> [description…]`, `/factory release add <name> <issue>…`,
`/factory release remove <issue>…`, `/factory release approve <name>`, `/factory release sync <name>`,
`/factory release mode <name> manual|automatic`, `/factory help`. The reply is appended to the transcript as a `system` event.
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
`release approve <name>` approves a ready release (owner only, §6 "Releasing"; without a name: "Usage: /factory
release approve <name>") and answers like `factory_approve_release`; refusals are shown as their message.
`release sync <name>` brings a ready release up to date (owner or admin, §6 "Releasing", "Bringing a release up to
date"; without a name: "Usage: /factory release sync <name>") and answers `Bringing release "<name>" up to date with
<target>: run <id> merges <target> into release/<name>, resolves conflicts and renumbers migrations. Nothing is merged
into <target>.`; under a `ready` release `releases` adds the line "Sync: …" of its latest sync run (not when done).
`retry <runId>` of a sync run answers "A run that brings a release up to date is not retried; …"; `status` lists a
sync run as `sync <title>`. In
`releases`, ready and released releases show "ready to release" / "released", the pull request URL, and for ready
ones " — N held" or " — conflicts with <default>" when that applies, plus a notice line when there is one. An
automatic release that is not finished shows ", releases automatically when ready", a released one ", released
automatically". `release mode <name> manual|automatic` sets the release mode with the HTTP rules (§6 "Release mode";
anything else: "Usage: /factory release mode <name> manual|automatic") and answers `Release "<name>" is released
automatically: once it is ready, complete and merges cleanly, and all checks on its pull request have passed, Agern
merges it with the owner's GitHub token.` or `Release "<name>" waits for its owner's approval once it is ready
(manual).`

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
  githubTokenScopes: string[] | null;     // classic tokens: GitHub's X-OAuth-Scopes at registration; null otherwise
  imageProvider: string | null;           // image provider id (§6 "Generated images"); null without a stored key
  imageModel: string | null };            // never the key

type LoginStep =
  | { next: 'totp' }                                             // enter a code
  | { next: 'enroll'; secret: string; otpauthUri: string }       // first login: set up the authenticator
  | { next: 'done'; user: User; recoveryCodes?: string[] };      // recoveryCodes only right after enrolment

type Project = { name: string; fullName: string; description: string | null; private: boolean;
  defaultBranch: string; language: string | null; openIssues: number; pushedAt: string | null;
  htmlUrl: string; activeAgents: number; activeRuns: number;
  iconUrl: string | null;      // `/api/projects/<repo>/icon?v=<version>`; null: no icon (§8 "Project icons")
  workspace: Workspace | null;  // §5 "Workspace projects"; filled by GET /api/projects/{repo} only (null in the list)
  settings: ProjectSettings };  // §6 "Merge policy"; defaults without a stored row, in the list and the detail

type MergePolicy = 'automatic' | 'owner_approves' | 'github';
type ProjectSettings = { mergePolicy: MergePolicy;   // default 'automatic'
  reviewers: string[];         // GitHub logins requested on a `github` run's pull request
  teamReviewers: string[] };   // team slugs of the organisation, likewise

type Workspace = { path: '.agern/workspace.yml' | '.gitmodules'; valid: boolean; // .gitmodules: members from the submodules
  errors: string[];            // every problem, with its path; [] when valid
  members: { directory: string; repository: string;
    branch: string | null;     // the effective base branch; the declared one (possibly null) for an inaccessible member
    label: string;
    accessible: boolean;       // the caller's own token can read it
    submodule: boolean }[];    // a git submodule of the workspace repository; [] when invalid
  contextOnly: string[];       // paths of submodules that are not members (fetched, never a target)
  missingLabels: string[] };   // member labels missing in the workspace repository (§5 "Member labels")

type IssueKind = 'feature' | 'bug' | 'task';      // from labels: bug → bug; enhancement/feature → feature; else task
type Issue = { number: number; title: string; state: 'open' | 'closed'; kind: IssueKind;
  labels: { name: string; color: string }[]; author: string; assignees: string[]; comments: number;
  createdAt: string; updatedAt: string; htmlUrl: string; body?: string; activeRunId: string | null;
  release: { id: string; name: string } | null;  // the release it is planned in (§6 "Releases"); always present
  imageCount: number;    // images selected for it in a chat (§6 "Generated images")
  workspaceMembers: { directory: string; repository: string }[] };  // members whose label it carries; [] outside a workspace

type ReleaseIssue = { number: number; title: string; kind: IssueKind;   // title/kind as when it was assigned
  state: 'waiting' | 'running' | 'merged' | 'held' | 'skipped';
  runId: string | null;                     // the issue's latest release run
  note: string | null;                      // why it is held or skipped, or that it will be built again
  prNumber: number | null; prUrl: string | null;     // that run's pull request into the release branch
  retryMode: 'continue' | 'step' | null;    // a waiting issue retried from runId (§6 "Retrying a run")
  targetRepo: string | null; targetDir: string | null;  // the member its run targets; null: project repository / no run
  waiting: RunWait | null };                // while running and its run waits for the network (§6 "Transient failures")
type RunWait = { reason: 'network'; since: string; nextAttemptAt: string;  // ISO instants
  attempt: number;                          // failed attempts of the current streak (≥ 1)
  detail: string };                         // what failed (≤ 500 characters)
type ReleaseRepo = { repo: string; directory: string | null;   // null: the project repository
  submodule: boolean; baseBranch: string | null; branch: string;
  state: 'none' | 'open' | 'merged';        // its release pull request
  prNumber: number | null; prUrl: string | null; mergeState: 'unknown' | 'clean' | 'conflict';
  mergeMethod: 'rebase' | 'merge' | 'unavailable' | null; checks: Release['checks'];
  mergedAt: string | null; mergeCommit: string | null;
  issues: number[];                         // merged issues built in it
  accessible: boolean };                    // false: the viewer cannot read it (no pull request details)
type Release = { id: string; repo: string; name: string; description: string;
  status: 'planning' | 'scheduled' | 'building' | 'paused' | 'ready' | 'released' | 'cancelled';
                                            // planning: "in preparation"; ready: "ready to release"
  createdBy: { username: string; displayName: string };
  createdAt: string; updatedAt: string;     // updatedAt strictly increases on every change
  issues: ReleaseIssue[];                   // in build order
  owner: { username: string; displayName: string } | null;   // who scheduled or started it
  branch: string | null;                    // 'release/<name>' once started
  startedAt: string | null; endedAt: string | null;
  pause: { reason: 'manual' | 'usage_limit' | 'credentials' | 'network'; note: string | null; resumeAt: string | null;
    since: string | null; attempt: number | null } | null;  // since/attempt: reason 'network' (the streak of failed starts)
  scheduledAt: string | null;               // ISO instant; set exactly while scheduled
  waitingFor: { id: string; name: string } | null;          // only while scheduled and due
  startFailure: { at: string; owner: { username: string; displayName: string }; reason: string } | null;
                                            // the last scheduled start that did not happen
  prNumber: number | null; prUrl: string | null;           // the release pull request release/<name> → targetBranch
  targetBranch: string | null;              // the default branch it merges into
  mergeState: 'unknown' | 'clean' | 'conflict';            // as last checked
  mergeMethod: 'rebase' | 'merge' | 'unavailable' | null;  // how approval will merge (once released: how it did)
  notice: string | null;                    // the last problem (opening or merging the pull request, clean-up)
  readyAt: string | null; releasedAt: string | null;
  releasedBy: { username: string; displayName: string } | null;   // null: merged on GitHub outside Agern; automatic: the owner
  mergeCommit: string | null;               // the default branch's commit after the merge
  mode: 'manual' | 'automatic';             // §6 "Release mode"
  releasedAutomatically: boolean;           // merged by Agern for the owner (automatic mode)
  checks: { state: 'unknown' | 'awaiting' | 'none' | 'pending' | 'passed' | 'failed';   // on the pull request's head commit
            detail: string | null; sha: string | null } | null;           // null until there is a pull request
  mergePolicy: MergePolicy;                 // the project's current policy (§6 "Merge policy")
  repositories: ReleaseRepo[];              // §6 "Several repositories"; [] unless multi-repository
  behindBy: number | null;                  // commits on targetBranch not on the release branch, as last compared
  migrationConflict: boolean;               // its migrations collide with targetBranch's (last evaluation)
  syncNeeded: boolean;                      // "Bring up to date" is offered (§6 "Bringing a release up to date")
  sync: ReleaseSync | null };               // its latest sync run

type ReleaseSync = { runId: string; state: 'running' | 'done' | 'failed' | 'unreviewed';
  by: { username: string; displayName: string }; startedAt: string; endedAt: string | null;
  pushedSha: string | null; error: string | null };

type StageRole = 'spec-writer' | 'builder' | 'security' | 'ux' | 'ui-design' | 'code' | 'approver';
type SessionKind = 'chat' | 'advisor' | StageRole;   // advisor: the agent a run's owner talks to at needs_human
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
// An advisor is `idle` between turns like a chat, `done` when its run was merged, `stopped` when it was cancelled.

type Stage = { id: string; role: StageRole;
  round: number;             // 0 = initial build/review, 1..maxRounds = fix rounds, >= conversation.followUpFrom: follow-up rounds
  model: string;             // the model the stage runs (or ran) with; set when the stage is created
  status: 'pending' | 'running' | 'done' | 'failed' | 'skipped';
  verdict: 'PASS' | 'CHANGES' | 'APPROVE' | 'ESCALATE' | null; summary: string | null;
  findings: { severity: string; file: string | null; detail: string }[];
  sessionId: string | null; startedAt: string | null; endedAt: string | null;
  screenshots: Screenshot[];    // ux / ui-design only (§6 "Screenshots"), attach order; [] for other stages
  images: GeneratedImage[];     // images the stage's agent generated (spec-writer, builder), oldest first
  warning: string | null };     // a warning that never fails the stage, e.g. "UI change reviewed without screenshots: …"

type GeneratedImage = { id: string; repo: string; origin: 'chat' | 'run'; sessionId: string;
  owner: { username: string; displayName: string }; provider: string; model: string;
  prompt: string; purpose: string;                       // untrusted text
  mediaType: string; size: number; width: number; height: number; transparent: boolean; createdAt: string;
  selection: { issueNumber: number; name: string; usedByRunId: string | null; locked: boolean } | null };
// bytes at GET /api/images/{id}

type Screenshot = { id: string; caption: string;   // caption: one line, untrusted (render as text)
  mediaType: 'image/png' | 'image/jpeg' | 'image/gif' | 'image/webp'; size: number; width: number; height: number;
  createdAt: string };

type Run = { id: string; repo: string; issueNumber: number; issueTitle: string; issueKind: IssueKind;
  status: 'queued' | 'spec' | 'building' | 'reviewing' | 'approving' | 'waiting_for_checks' | 'merged' | 'needs_human' | 'failed' | 'cancelled'
    | 'ready';               // terminal: passed under merge policy `github`, the pull request waits on GitHub (§6 "Merge policy")
  round: number;             // fix rounds so far (0 = initial build)
  maxRounds: number;         // the starting user's effective maxFixRounds, fixed when the run is created
  branch: string | null; prNumber: number | null; prUrl: string | null;
  owner: { username: string; displayName: string }; startedBySessionId: string | null;
  error: string | null; createdAt: string; updatedAt: string; endedAt: string | null;
  stages: Stage[];
  sandbox: RunSandbox | null;  // the run's sandbox decision (§5 "Sandboxes"); null until the workspace is ready / older runs
  releaseId: string | null;  // the release this run builds an issue for (null: a single run, or the release was deleted)
  baseBranch: string | null;  // the branch it starts from and merges into; null = the default branch; a workspace run: the member's base
  targetRepo: string | null;       // a workspace run: the member repository it changes (§6 "Workspace runs"); null otherwise
  targetDirectory: string | null;  // … and that member's directory in the workspace
  conversation: RunConversation;  // always present; default { sessionId: null, review: 'none', followUpFrom: null }
  continuesRunId: string | null;   // the failed run this one continues from its commits (§6 "Retrying a run")
  continuedByRunId: string | null; // the newest run that continues this one
  failureKind: 'time_limit' | 'usage_limit' | 'restart' | 'api_unreachable' | 'credentials' | 'blocked' | 'step' | 'other' | 'submodule_commit_missing' | 'checks' | null;
  failedStep: 'push' | 'pull_request' | 'merge' | null;  // the step after the builder that failed
  mergePolicy: MergePolicy;        // the project's policy when the run started; never changes
  mergedOnGitHub: boolean;         // merged on GitHub outside Agern (followed `github` runs)
  mergedBy: string | null;         // the GitHub login that merged it there, when GitHub reports it
  waiting: RunWait | null;  // while it sleeps before retrying an operation that lost the network; it keeps its status
  warnings: { round: number; roles: StageRole[]; text: string }[];  // the stages' warnings per round, oldest first
  checks: RunChecks | null;  // the checks last evaluated before a merge (§6 "Checks before a merge"); null until then
  kind: 'issue' | 'sync';         // sync: brings a release up to date; issueNumber 0, no issue (§6 "Releasing")
  syncPushedSha: string | null };  // a sync run: the commit it last pushed to its release branch

type RunChecks = { state: 'awaiting' | 'pending' | 'passed' | 'failed' | 'none' | 'unknown';
  sha: string; detail: string;                // the head commit they were read for; e.g. "Failed: build, lint"
  items: { name: string; state: 'failed' | 'pending'; conclusion: string | null; url: string | null }[];  // not passed; at most 50; url only https://
  waitingSince: string | null; deadline: string | null;   // set only while waiting_for_checks
  overriddenBy: string | null; overriddenAt: string | null };  // the owner merged anyway

type RetryOptions = { runId: string;       // '' for a release issue without a run
  recommended: 'step' | 'continue' | 'start_over'; reason: string;   // one line
  options: { mode: 'step' | 'continue' | 'start_over'; available: boolean; detail: string }[];
  step: 'push' | 'pull_request' | 'merge' | null;   // what a step retry repeats, when available
  commits: number | null;    // commits of the run's branch not on its base; null: branch not found
  releaseId: string | null };

type RunConversation = {     // §6 "Conversation at needs human"
  sessionId: string | null;                 // the advisor session, once the owner has sent a message
  review: 'none' | 'required' | 'running';  // follow-up review of changes made in the conversation
  followUpFrom: number | null };            // first follow-up round; rounds >= it are follow-up rounds

type RunSandbox = { state: 'disabled' | 'none' | 'invalid' | 'unapproved' | 'approved';  // only 'approved' gives a sandbox
  reason: string;            // human-readable, also shown at the top of the builder's transcript
  sha256: string | null;     // of the definition on the base commit, when there is one
  image: string | null;
  platforms: string[];       // the definition's platforms ('approved' only; [] for older runs)
  platformsUsed: string[];   // where the builder ran commands, 'default' and platform names in first-use order
                             // (filled only when there are platforms)
  reviewUsed: boolean };     // a review sandbox of the run was created (the ux and ui-design reviewers used it)

type SandboxDefinition = { image: string; setup: string | null; env: Record<string, string>;
  services: { name: string; image: string; env: Record<string, string>;
    ready: { command: string; timeoutSeconds: number } | null }[];
  caches: string[];
  limits: { cpus: number; memory: string;                  // effective per container: the request capped by the server
    requestedCpus: number | null; requestedMemory: string | null; capped: boolean };
  platforms: { name: string; image: string;
    setup: string | null;    // effective: the platform's own, or the top-level one when setupInherited
    setupInherited: boolean }[];
  user: { name: string | null; uid: number } | null };    // null: commands run as root; name null: given as a uid
type SandboxApproval = { sha256: string; content: string; approvedBy: { username: string; displayName: string }; approvedAt: string };
type SandboxStatus = { repo: string;
  enabled: boolean;          // FACTORY_SANDBOX is not off
  path: '.agern/sandbox.yml'; branch: string;              // read from the default branch
  state: 'none' | 'invalid' | 'pending' | 'approved';      // pending: valid, this exact content not approved
  sha256: string | null; content: string | null;
  errors: string[];          // state 'invalid'
  definition: SandboxDefinition | null;                    // when valid
  approval: SandboxApproval | null;                        // the project's approval, possibly of other content (diff)
  approvers: { username: string; displayName: string }[] };  // who can approve: the admins (configured or known)

type RunningSandbox = { runId: string; repo: string;
  visible: boolean;          // false when the admin cannot see the repository: issueTitle is null then
  issueNumber: number; issueTitle: string | null; runStatus: Run['status'];
  owner: { username: string; displayName: string };
  image: string; services: string[]; sha256: string;      // the approved definition's hash
  createdAt: string; cpus: number; memory: string;         // per-container limits it runs with
  containers: { platform: string; image: string; createdAt: string;      // command containers now: 'default' and/or
    kind: 'build' | 'review' }[];                                        // platforms (build), the review sandbox's (review, 'default')
  platforms: string[];       // the definition's platforms, existing or not
  slots: number;             // slots of FACTORY_SANDBOX_MAX_CONCURRENT held: one per container
  memoryTotal: string };     // memory × (containers + services of both sandboxes): what the run may use together
type SandboxOverview = { enabled: boolean; runtime: 'off' | 'podman' | 'fake';
  inUse: number;             // slots in use: command containers over all runs
  limits: { maxCpus: number; maxMemory: string; maxConcurrent: number; execTimeoutMinutes: number; registries: string[];
    maxPlatforms: number };
  sandboxes: RunningSandbox[] };                          // the sandboxes that exist right now, oldest first
type PendingSandbox = { repo: string; sha256: string; image: string;
  approvedSha256: string | null };                         // the approved older version, or null when none ever was

type Attachment = { id: string; name: string;   // name: sanitised (no path, control/bidi characters or leading dots; ≤ 120 chars)
  mediaType: string;                 // decided by sniffing the bytes: image/png|jpeg|gif|webp, application/pdf, application/zip,
                                     // text/plain (UTF-8 without NUL) or application/octet-stream — never the client's Content-Type
  size: number; kind: 'image' | 'file';           // image = one of the four raster types
  width: number | null; height: number | null;
  inline: 'original' | 'downscaled' | 'none';     // how the model sees an image (§5); always 'none' for files
  note: string | null };                          // why an image was downscaled / is file only

type ClientConfig = { attachments: { maxFileBytes: number; maxMessageBytes: number; maxCount: number; imageTypes: string[] };
  images: { providers: { id: string; label: string; models: string[]; defaultModel: string; supportsTransparency: boolean;
      keyPlaceholder: string }[];
    limits: { maxPerRequest: number; maxPerTurn: number; maxPerStage: number; maxEdge: number } } };

type Event = { seq: number; sessionId: string; ts: string;
  type: 'user' | 'claude' | 'system' | 'status' | 'images' | 'checkout'; payload: unknown };
//  user:   { text: string, username: string, attachments?: Attachment[] }   // attachments only when there are some
//          A message to a running pipeline agent (§6) adds: messageId: string, origin: 'direct' | 'chat',
//          viaSessionId?: string   // the forwarding chat session when origin is 'chat'
//  claude: one raw stream-json object from the CLI ({type:'system'|'assistant'|'user'|'result'|'stream_event', …})
//  system: { level: 'info' | 'warn' | 'error', text: string, messageId?: string, sandbox?: SandboxLog }
//          messageId: the agent picked that message up (info) or stopped before it did (warn)
//  images: { requestId, provider, providerLabel, model, prompt, purpose, issueNumber?,   // one factory_generate_image
//            images: [{ id, mediaType, width, height, size }] }                          // call (§6 "Generated images")
//  checkout: { branch: string | null,          // a chat's main checkout after a sync (§5 "Workspaces"); null = detached
//              defaultBranch: string, commit: string,  // full HEAD sha after the sync
//              behind: number, ahead: number,  // relative to origin/<defaultBranch>
//              status: 'current' | 'updated' | 'behind',
//              reason: 'other_branch' | 'local_changes' | 'local_commits' | 'update_failed' | null,  // only for 'behind'
//              updatedFrom: string | null }    // the previous HEAD, only for 'updated'
//          sandbox: the full log of a sandbox setup script or command (§5 "Sandboxes"):
//            { kind: 'setup' | 'exec', command, exitCode, timedOut, durationMs, output, outputTruncated,
//              platform: string | null,    // null when the definition has no platforms, else 'default' or the platform
//              review?: true }             // ran in the read-only review sandbox (ux and ui-design reviewers)

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

// The caller's own Claude usage (§5 "Claude usage"). ISO timestamps; days and months are UTC.
type UsageWindow = { type: string;                // five_hour | seven_day | seven_day_opus | seven_day_sonnet |
                                                  // seven_day_overage_included | overage | anything else, as reported
  utilization: number | null;                     // fraction used (1 = 100 %), may exceed 1
  status: string | null;                          // allowed | allowed_warning | rejected | …
  resetsAt: string | null; observedAt: string };  // returned as stored: the client shows a passed resetsAt as reset
type ClaudeUsage = { credentialKind: 'oauth_token' | 'api_key' | null;
  windows: UsageWindow[];                         // ordered by type; [] unless credentialKind is 'oauth_token'
  spend: { todayUsd: number; monthUsd: number; day: string; month: string } };   // 'YYYY-MM-DD', 'YYYY-MM'

type GitHubStatus = {                                       // GET /api/github/status (§6 "GitHub status")
  verdict: { state: 'working' | 'degraded' | 'down'; reason: string };
  generatedAt: string;
  official: { state: 'ok' | 'unreachable' | 'disabled';
    indicator: 'none' | 'minor' | 'major' | 'critical' | 'unknown' | null;   // null unless state = ok
    description: string | null;
    components: { name: string; status: string; relevant: boolean }[];       // non-operational only
    incidents: { title: string; status: string; impact: string | null; updatedAt: string | null; url: string | null }[];
    fetchedAt: string | null; error: string | null };                         // error only for unreachable
  kinds: { kind: string; label: string; last15m: GitHubCallCounts; last24h: GitHubCallCounts;
    lastError: { at: string; class: 'server_error' | 'network' | 'rate_limited' | 'auth';
      status: number | null; requestId: string | null; message: string } | null }[];   // [] with brief
  rateLimits: { own: { rest: GitHubRateLimit | null; graphql: GitHubRateLimit | null } | null;  // null: no token
    otherTokens: number; lowestOther: GitHubRateLimit | null } | null };     // null with brief
type GitHubCallCounts = { calls: number; serverErrors: number; network: number; rateLimited: number; auth: number };
type GitHubRateLimit = { limit: number; remaining: number; resetAt: string; observedAt: string };

type SoftwareRequest = { id: string; name: string; reason: string; repo: string;
  requestedBy: string; sessionId: string | null;
  status: 'pending' | 'installed' | 'denied'; note: string | null; createdAt: string; resolvedAt: string | null };

type McpToken = { id: string; name: string; repo: string;            // a personal MCP token (§7); never the secret
  owner: { username: string; displayName: string }; createdAt: string;
  lastUsedAt: string | null; expiresAt: string | null;               // null: never used / never expires
  expired: boolean };
type CreatedMcpToken = { token: McpToken; secret: string;            // secret: shown once, `agern_pat_…`
  mcpUrl: string;                                                    // FACTORY_PUBLIC_URL without trailing / + "/mcp"
  serverName: string };                                              // suggested name in Claude (§7 "Personal tokens")
```

### Endpoints

| Method & path | Body → Response | Notes |
|---|---|---|
| `GET /api/health` | → `{ status: 'ok', version, name: 'agern' }` | no auth; the web UI shows `version` in the user menu |
| `GET /api/internal/activity` | → `{ idle, activeRuns, busyAgents, version }` | no auth, **host-local only**: answered only when the TCP peer is a loopback address and no proxy header (`Forwarded`, `X-Forwarded-For`, `X-Forwarded-Host`, `X-Real-IP`) is present; anyone else gets the generic 404. `activeRuns`: runs in `queued`/`spec`/`building`/`reviewing`/`approving` (a restart fails them; `waiting_for_checks` runs are resumed after a restart and do not count); `busyAgents`: sessions `starting`/`running` (pipeline agents, chats mid-turn). `needs_human` runs and chats between turns survive a restart and do not count. Used by the upgrade timer (§9) |
| `GET /api/github/status` | → `GitHubStatus` | any logged-in user (no GitHub token needed; 401 otherwise). The same for every user except `rateLimits.own`. `?brief=1` (or `true`): `kinds` is `[]`, `rateLimits` null and no `/rate_limit` call is made — the shell's banner poll (§6 "GitHub status") |
| `GET /api/auth/providers` | → `{ providers: string[], org: string, twoFactor: boolean }` | no auth |
| `POST /api/auth/login` | `{ username, password }` → `LoginStep` | sets cookie (pending until 2FA passes) |
| `POST /api/auth/totp` | `{ code }` → `LoginStep` | pending session; `code` is a TOTP code or a recovery code; 423 when locked |
| `POST /api/auth/totp/enroll` | `{ code }` → `LoginStep` | pending session in `enroll`; `done` step carries `recoveryCodes` |
| `POST /api/me/recovery-codes` | `{ code }` → `{ recoveryCodes: string[] }` | regenerate; needs a current TOTP code |
| `POST /api/admin/users/{id}/reset-2fa` | → `User` | admin |
| `GET /api/me/mcp-tokens` | → `McpToken[]` | the caller's personal MCP tokens that are not revoked, newest first; expired ones included (`expired: true`) |
| `POST /api/me/mcp-tokens` | `{ name, repo, expiresInDays }` → `CreatedMcpToken` | full session. `name` trimmed, 1–60 characters, no control characters (400); unique per user and project, case-insensitive (409 `mcp_token_name_taken`); `repo` visible to the caller (404); `expiresInDays` null or 1…366 (400); at most 50 active tokens (409 `too_many_mcp_tokens`). `Cache-Control: no-store`; the only answer that carries the secret. Audit `mcp_token_created` |
| `DELETE /api/me/mcp-tokens/{id}` | → `McpToken[]` | revokes one of the caller's tokens (404 for an unknown, another user's or an already revoked one); answers the updated list. Audit `mcp_token_revoked` |
| `GET /api/admin/mcp-tokens` | → `McpToken[]` | admin. Every user's tokens that are not revoked, newest first |
| `DELETE /api/admin/mcp-tokens/{id}` | → `McpToken[]` | admin. Revokes any token (404 as above); answers the updated full list. Audit `mcp_token_revoked … by admin <username>` for another user's token |
| `POST /api/auth/logout` | → 204 | |
| `GET /api/me` | → `User` | 401 when not logged in |
| `PUT /api/me/claude-credential` | `{ kind, value }` → `User` | clears the caller's recorded limit windows (spend is kept) and sends them a `usage` frame |
| `DELETE /api/me/claude-credential` | → `User` | same: clears the limit windows, keeps the spend, sends a `usage` frame |
| `GET /api/me/usage` | → `ClaudeUsage` | the caller's own Claude usage (§5 "Claude usage"); there is no way to read another user's, also not for admins |
| `PUT /api/me/github-token` | `{ token }` → `User` | 422 if GitHub rejects it. `GET /user` succeeds with any scope, so the token's kind and (classic) scopes are recorded and shown; Settings warns when a classic token lacks `repo` (public repositories only, no push) and explains the grants a fine-grained token needs |
| `DELETE /api/me/github-token` | → `User` | |
| `PUT /api/me/image-provider` | `{ provider, model, apiKey }` → `User` | §6 "Generated images". 400 unknown provider or bad model name; 409 `codex_login_required` (a `device_login` provider), 409 `image_provider_unavailable_here`; 422 `image_key_invalid` (malformed, or rejected by the provider), 422 `image_model_unknown`, 429 `too_many_attempts` (5 rejected keys per 15 minutes), 502 `image_provider_unavailable`; nothing is stored on any error. The key is never returned |
| `DELETE /api/me/image-provider` | → `User` | idempotent; images stay; cancels a pending Codex login |
| `POST /api/me/image-provider/codex/login` | → `CodexLogin` | §4 "Codex login". Starts a device login (cancels a pending one); answers `{ status: "pending", verificationUrl, userCode, expiresAt }`, or `{ status: "failed", error }` for a refused URL. 409 `image_provider_unavailable_here` (no Codex CLI), 429 `too_many_attempts` (5 starts per 15 minutes), 502 `codex_login_failed` (no code within 20 s) |
| `GET /api/me/image-provider/codex/login` | → `CodexLogin` | `{ status: none\|pending\|connected\|failed\|expired, verificationUrl?, userCode?, expiresAt?, error? }`; URL and code only while `pending` |
| `DELETE /api/me/image-provider/codex/login` | → `CodexLogin` | cancels a pending login (`none`); idempotent |
| `GET /api/images/{id}` | → image bytes | anyone who can see the image's repository (404 otherwise and for unknown ids); the attachment headers, `Content-Disposition: inline` |
| `PUT /api/images/{id}/selection` | `{ issueNumber, name }` → `GeneratedImage` | the image's owner, chat images only: 403 not the owner, 404 unknown image/issue, 400 name empty or over 60, 409 `run_image` / `issue_closed` / `too_many_images` (20 per issue) / `image_in_use` (locked) |
| `DELETE /api/images/{id}/selection` | → `GeneratedImage` | as above; 409 `image_in_use` while locked |
| `DELETE /api/images/{id}` | → 204 | discard an unselected chat image of the caller; 409 `image_selected` / `run_image` |
| `GET /api/sessions/{id}/images` | → `GeneratedImage[]` | visibility of the session; oldest first |
| `GET /api/projects/{repo}/issues/{number}/images` | → `GeneratedImage[]` | images selected for the issue |
| `GET /api/me/preferences` | → `Preferences` | the caller's own model / fix-round choices and the effective values |
| `PUT /api/me/preferences` | `PreferenceOverrides` → `Preferences` | replaces the caller's choices; 400 for an invalid model or unknown role, 422 `max_fix_rounds_out_of_range` |
| `GET /api/projects` | → `Project[]` | repos of the org visible to the user |
| `GET /api/projects/{repo}` | → `Project` | with `workspace` (read with the caller's token; members' `accessible` per caller) |
| `PUT /api/projects/{repo}/icon` | raw image bytes (PNG, JPEG, WebP, GIF or SVG) → `Project` | admin (403 before the body is read; 404 when the admin cannot see the project). Sets or replaces the icon; 400 `empty_icon`, 413 `icon_too_large`, 415 `unsupported_icon_type`, 422 `invalid_icon` (also every refused SVG) / `icon_dimensions_too_large`; see "Project icons" below |
| `POST /api/projects/{repo}/icon/from-repository` | `{path}` → `Project` | admin (403; 404 as above). Sets the icon from a file on the repository's default branch, read once with the admin's own GitHub token; 400 `bad_request` for an invalid path, 404 `icon_file_not_found`, then the upload's 400/413/415/422; see "Project icons" below |
| `PUT /api/projects/{repo}/settings` | `{ mergePolicy, reviewers?, teamReviewers? }` → `Project` | admin (403; 404 when the admin cannot see the project). 400 unknown `mergePolicy`, an entry that is not a GitHub login (`^[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})$`) or team slug (`^[A-Za-z0-9][A-Za-z0-9_.-]{0,99}$`, the message names it), or more than 15 per list. Entries are trimmed, one leading `@` stripped, empty ones dropped and duplicates removed case-insensitively; omitted lists are empty. Applies to runs started from now on (§6 "Merge policy") |
| `DELETE /api/projects/{repo}/icon` | → `Project` | admin (403; 404 as above); removes the icon (also when there is none), `iconUrl: null` |
| `GET /api/projects/{repo}/icon?v=<version>` | → image bytes (`image/png`, `image/jpeg`, `image/webp`, `image/gif` or `image/svg+xml`) | anyone who can see the project; 404 when not visible or no icon is set |
| `GET /api/projects/{repo}/issues?state=open\|closed\|all` | → `Issue[]` | pull requests excluded |
| `GET /api/projects/{repo}/issues/{number}` | → `Issue` (with `body`) | |
| `POST /api/projects/{repo}/issues` | `{ title, body, kind }` → `Issue` | |
| `GET /api/projects/{repo}/releases` | → `Release[]` | newest first (`createdAt` desc), each with its issues in order |
| `POST /api/projects/{repo}/releases` | `{ name, description?, mode? }` → 201 `Release` | 400 invalid name, description or mode (`manual` default, `automatic`); 409 `release_name_taken`; 409 `release_mode_not_allowed` (the project's merge policy is not `automatic`, §6 "Merge policy"). Starts nothing |
| `GET /api/releases/{id}` | → `Release` | 404 unknown or invisible |
| `PUT /api/releases/{id}` | `{ name, description }` → `Release` | rename or edit; same validation as create. Started: owner or admin, and a different name → 409 `release_name_locked`; released: 409 `release_not_editable`. Editing a ready release updates its pull request's body |
| `DELETE /api/releases/{id}` | → 204 | removes the plan and its assignments; the issues are untouched on GitHub. `scheduled` → 409 `release_scheduled`; `building`/`paused`/`ready` → 409 `release_active`; `released` → 409 `release_not_editable`; `cancelled`: owner or admin, the branch stays and its runs keep existing with `releaseId` null |
| `POST /api/releases/{id}/start` | → `Release` | the caller becomes the owner; creates `release/<name>` and builds (§6 "Releases"). 409 `release_not_planning` (also while scheduled), 422 `claude_credential_required`, 409 `release_empty`, 409 `release_already_building`, 409 `release_branch_exists`, 422 `github_push_denied`, 409 `workspace_invalid` for a workspace whose definition is invalid (also for a scheduled start) |
| `PUT /api/releases/{id}/schedule` | `{ startAt: string }` → `Release` | set (`planning`, caller becomes owner) or change (`scheduled`, owner or admin, owner unchanged) the scheduled start. In order: 404 invisible; 400 "startAt must be an ISO-8601 time" (an instant with `Z` or offset), "The start time must be in the future", "The start time is too far ahead (at most a year)" (> 366 days); set: 422 `claude_credential_required`, 403 without a GitHub token; change: 403 "Only <owner> or an admin can change this schedule"; other states: 409 `release_not_schedulable`. Stored at millisecond precision |
| `DELETE /api/releases/{id}/schedule` | → `Release` | cancel: back to `planning`, `owner`/`scheduledAt`/`waitingFor` null, `startFailure` kept. 409 `release_not_scheduled` when not scheduled (also once the start has begun), else 403 unless owner or admin |
| `POST /api/releases/{id}/pause` | → `Release` | owner or admin; `building` → `paused` (else 409 `release_not_building`); the running issue finishes |
| `POST /api/releases/{id}/resume` | → `Release` | owner only; `paused` → `building` (else 409 `release_not_paused`); 422 `claude_credential_required`. For reason `network` this is "Retry now" |
| `POST /api/releases/{id}/network/give-up` | → `Release` | owner or admin; only `paused` with reason `network` (else 409 `release_not_waiting_for_network`): holds the issue that could not start and goes on with the next one (§6 "Transient failures") |
| `POST /api/releases/{id}/cancel` | → `Release` | owner or admin; `building`/`paused`/`ready` → `cancelled` (else 409 `release_not_building`; released: 409 `release_not_editable`); cancels the release's active runs; a release pull request is left open with a comment |
| `POST /api/releases/{id}/approve` | → `Release` | owner only (admins included in the refusal: 403); 409 `merge_on_github` when the project merges on GitHub (§6 "Merge policy"); merges the ready release's pull request (rebase, or a merge commit where rebase merging is disabled; never squash), closes its merged issues and deletes `release/<name>` (§6 "Releasing"). 409 `release_not_ready`, `release_incomplete`, `release_empty`, `release_conflict`, `release_migration_conflict`, `release_submodule_commit_missing`, `release_submodule_check_failed`, `release_merge_method_unavailable`, `release_merge_failed`, `release_sync_running` (while a sync run of it is in progress); nothing changes on GitHub with any of them; a multi-repository release merges its member pull requests first and answers 409 `release_partially_merged` with the per-repository report when it stops after a merge (§6 "Several repositories") |
| `PUT /api/releases/{id}/mode` | `{ mode: 'manual' \| 'automatic' }` → `Release` | §6 "Release mode": `planning` anyone; `scheduled`/started: automatic owner only, manual owner or admin (403); 400 missing or unknown mode; 409 `release_not_editable` (released), `release_closed` (cancelled), `release_mode_not_allowed`. A `ready` release is re-evaluated in the background (switched to automatic and green, it is merged) |
| `POST /api/releases/{id}/sync` | → `Release` | "Bring up to date" (§6 "Bringing a release up to date"): owner or admin; 404 not visible, 409 `release_not_ready`, 403, 409 `release_sync_unsupported`, 422 `claude_credential_required`, GitHub token as everywhere, 409 `release_sync_merge_commits_disallowed`, then under the mutex 409 `release_not_ready`, `release_sync_running`, `release_up_to_date`; starts a sync run owned by the caller and answers the release |
| `POST /api/releases/{id}/check` | → `Release` | owner only; `ready` only (409 `release_not_ready`): re-reads the pull request (opens it when the release is complete and has none, replaces one closed without merging, finishes the release when it was merged on GitHub) and its mergeability |
| `GET /api/releases/{id}/issues/{number}/retry` | → `RetryOptions` | anyone who can see the release; the issue must be `held`/`skipped` (409 `release_issue_not_held`); the options of its linked run, or only `start_over` when it has none |
| `POST /api/releases/{id}/issues/{number}/retry` | `{ mode?: … }` (body optional) → `Release` | no body or no mode = `start_over`, as before; `continue`/`step`/`recommended` as in §6 "Retrying a run" (409 `retry_mode_unavailable`); owner only; release `building`/`paused`/`ready` (else 409 `release_not_building`; released: 409 `release_not_editable`), issue `held`/`skipped` (else 409 `release_issue_not_held`); it is built again (a `ready` release builds again: 409 `release_already_building` while another release of the project builds); a partly released release: 409 `release_partially_merged` |
| `POST /api/projects/{repo}/releases/assign` | `{ issueNumbers: number[], releaseId: string \| null }` → `Release[]` | assigns or moves the issues to that release of `{repo}` (404 otherwise), or takes them out with `releaseId: null` (numbers in no release are ignored, no GitHub calls). 1–100 positive numbers, duplicates collapsed (400 otherwise; an invalid `releaseId` is 400). Every issue is fetched with the caller's token: unknown → 404 "Issue #n not found", closed → 409 `issue_closed`; in a workspace project routed as a run (409 `workspace_invalid`, `member_label_missing`, `member_label_ambiguous`, 403 `member_not_accessible`); all-or-nothing; a partly released target: 409 `release_partially_merged`. Target `building`/`paused`/`ready`: owner only, appended as `waiting` (a `ready` target builds again: 409 `release_already_building` while another release of the project builds); target `cancelled`: 409 `release_closed`; target `released`: 409 `release_not_editable`. An issue leaving a `building`/`paused`/`ready` release: owner or admin, and only `waiting`, `held` or `skipped` (else 409 `release_issue_started`); leaving a `cancelled` one: owner or admin; leaving a `released` one: 409 `release_not_editable`. A still active run of an issue that leaves is cancelled. Answers all releases of the project after the change |
| `PUT /api/releases/{id}/issues/order` | `{ issueNumbers: number[] }` → `Release` | the new complete order; anything but exactly the current issues → 409 `release_issues_changed`, nothing changes. `building`/`paused`/`ready`: owner only, and every issue that is not `waiting` keeps its index (409 `release_issue_started`); `cancelled`: 409 `release_closed`; `released`: 409 `release_not_editable` |
| `GET /api/projects/{repo}/sandbox` | → `SandboxStatus` | anyone who can see the project; the file is read with the caller's token from the default branch |
| `POST /api/projects/{repo}/sandbox/approval` | `{ sha256 }` → `SandboxStatus` | admin (403 otherwise; 404 when the admin cannot see the project or there is no file). Approves exactly the content with that hash, replacing the project's earlier approval; 400 malformed hash, 409 `sandbox_definition_changed` when the file now has another hash, 422 `sandbox_definition_invalid` |
| `DELETE /api/projects/{repo}/sandbox/approval` | → `SandboxStatus` | admin; removes the project's approval (also when there is none). Runs already started keep their decision |
| `GET /api/projects/{repo}/runs` | → `Run[]` | newest first |
| `POST /api/projects/{repo}/workspace/labels` | → `{ created: string[]; missing: string[] }` | creates the missing member labels with the caller's token (§5 "Member labels"); 403 `labels_forbidden`, 409 `not_a_workspace` / `workspace_invalid`, 404 when the caller cannot see the project |
| `POST /api/projects/{repo}/runs` | `{ issueNumber }` → `Run` | needs Claude credential; 409 if the issue has an active run; a workspace project: 409 `workspace_invalid`, `member_label_missing`, `member_label_ambiguous`, 403 `member_not_accessible` (§6 "Workspace runs") |
| `GET /api/runs?active=true&status=<s>` | → `Run[]` | across visible repos, newest first (max 200). `active=true`: not finished, i.e. `queued`, `spec`, `building`, `reviewing`, `approving`, `waiting_for_checks` or `needs_human`. `status`: only these statuses, repeatable and/or comma list (`?status=needs_human`); unknown status → 400. Both given: both apply |
| `GET /api/runs/{id}` | → `Run` | |
| `GET /api/runs/{id}/screenshots/{sid}` | → image bytes | anyone who may see the run (404 otherwise, and for a screenshot of another run). Served with the sniffed type, `nosniff`, a sandboxing CSP, `Cross-Origin-Resource-Policy: same-origin`, `Cache-Control: private, max-age=31536000, immutable` and `Content-Disposition: inline` (`screenshot-<role>-<id8>.<ext>`). There is no upload or delete route |
| `POST /api/runs/{id}/approve` | → `Run` | owner only, only in `needs_human`; 409 `merge_on_github` for a run of merge policy `github`, whatever its status (after 404 and 403; §6 "Merge policy"); 409 `review_in_progress`, `agent_busy`, `review_required` (§6 "Conversation at needs human"); evaluates the checks on the pull request's head first and answers the run merged, `needs_human` or `waiting_for_checks` (§6 "Checks before a merge") |
| `POST /api/runs/{id}/merge-anyway` | `{ headSha }` → `Run` | owner only (403); 409 `merge_on_github` for a run of merge policy `github`; merges although the checks on `headSha` have not passed, bound to that commit; 409 `run_not_overridable` (not `needs_human`/`waiting_for_checks`, no pull request, or checks not blocking), `checks_changed` (`headSha` is not the recorded checks' commit), and from `needs_human` `review_required` / `review_in_progress` / `agent_busy`; web only, no MCP tool or chat command (§6 "Checks before a merge") |
| `POST /api/runs/{id}/conversation` | `{ text }` → 202 `{ status: 'accepted', sessionId }` | owner only (403 for everyone else, admins too), only in `needs_human` (409 `run_not_waiting`). Talks to the run's advisor; the first message creates its session. 400 blank / too long, 409 `review_in_progress`, 409 `agent_busy`, 422 `claude_credential_required`, 409 `too_many_agents`, 409 `run_worktree_missing` |
| `POST /api/runs/{id}/review` | → `Run` | "Review again": owner only (403 admins too), only in `needs_human`; starts a follow-up review of changes from the conversation. 409 `review_in_progress`, `agent_busy`, `nothing_to_review` (no conversation, or nothing changed since the last completed review); 422 `claude_credential_required` |
| `POST /api/runs/{id}/cancel` | → `Run` | owner or admin |
| `POST /api/runs/{id}/network/retry` | → `Run` | owner or admin; a run waiting for the network tries again now (§6 "Transient failures"); 409 `run_not_waiting_for_network` |
| `POST /api/runs/{id}/network/give-up` | → `Run` | owner or admin; the waiting run fails now and is returned once failed; 409 `run_not_waiting_for_network` |
| `GET /api/runs/{id}/retry` | → `RetryOptions` | anyone who can see the run; how it can be retried and what is recommended (§6 "Retrying a run"); for a sync run every option is unavailable with the reason "A run that brings a release up to date is not retried; …" |
| `POST /api/runs/{id}/retry` | `{ mode?: 'step' \| 'continue' \| 'start_over' \| 'recommended' }` (body optional) → `Run` | owner only (admins included: 403), single runs that are `failed` or `cancelled` (409 `run_not_retryable`; a release run: 409 `release_run`; a sync run: 409 `sync_run`); no mode = `start_over`; 409 `retry_mode_unavailable`, `active_run_exists`; 400 unknown mode; 422 `claude_credential_required`. Answers the run now at work: the same run for `step`, a new one otherwise |
| `GET /api/projects/{repo}/sessions?kind=chat` | → `AgentSession[]` | |
| `POST /api/projects/{repo}/sessions` | `{ model? }` → `AgentSession` | new chat; needs Claude credential; without `model` the owner's effective `chatModel` |
| `GET /api/agents?active=true` | → `AgentSession[]` | all kinds, across visible repos. `active=true`: `live` (a process exists, e.g. a chat idling between turns) or `starting`/`running` |
| `GET /api/sessions/{id}` | → `AgentSession` | |
| `GET /api/sessions/{id}/events?after=<seq>&limit=<n>` | → `Event[]` | ascending |
| `GET /api/config` | → `ClientConfig` | limits the frontend checks before uploading; `images.providers[]` with `id, label, models, defaultModel, supportsTransparency, keyPlaceholder, auth, available, unavailableReason` |
| `POST /api/sessions/{id}/input` | `{ text, attachmentIds? }` → 202 | owner only. **Chat:** accepted mid-turn too (delivered after the current turn); a `stopped` or process-less chat resumes. `text` may be empty when `attachmentIds` is not. The attachments are bound to this message when it is accepted, so a message queued mid-turn keeps its own. Answer `{ status: 'accepted' }`. **Pipeline agent:** a text message for a running spec-writer or builder (§6 "Messages to running agents") → 202 `AgentMessage`; 403 for everyone but the run's owner (admins too), 409 `agent_does_not_accept_messages` (reviewers, approver), 409 `agent_not_running`, 422 `attachments_not_allowed`. **Advisor:** exactly as `POST /api/runs/{id}/conversation` (answer `{ status, sessionId }`); 422 `attachments_not_allowed` with attachments |
| `POST /api/sessions/{id}/attachments?name=<file name>` | raw bytes → 201 `Attachment` | owner only, chat only; see "Attachments" below |
| `GET /api/sessions/{id}/attachments/{aid}?preview=1` | → bytes | anyone who may see the session once it is sent; before that only its uploader. `preview=1`: the downscaled copy if there is one |
| `DELETE /api/sessions/{id}/attachments/{aid}` | → 204 | owner only; unsent only (409 `attachment_sent`) |
| `POST /api/sessions/{id}/interrupt` | → 202 | owner only; stops the current turn (chats and advisors; 409 for pipeline agents — cancel the run instead) |
| `POST /api/sessions/{id}/stop` | → `AgentSession` | owner or admin |
| `DELETE /api/sessions/{id}` | → 204 | **Close a chat**: owner or admin (403 otherwise, 404 if the repo is not visible); chat only (409 `not_a_chat` for pipeline sessions — cancel the run instead). A live process is stopped first (as `stop`, up to 10 s; if it has not exited, 409 `agent_not_stopped` and nothing is deleted). Then the session is deleted permanently with its events, attachment rows and the files under `<dataDir>/attachments/<id>/`; its session sockets are closed with `4404` and the feed announces `session_closed`. Runs started from it keep running (their `startedBySessionId` becomes `null`). Afterwards every route for the id answers 404 |
| `GET /api/admin/settings` · `PUT /api/admin/settings` | `Settings` | admin |
| `GET /api/admin/users` | → `User[]` | admin |
| `GET /api/admin/sandboxes` | → `SandboxOverview` | admin. Every sandbox that exists on this server (also of repositories the admin cannot see, without their issue titles) |
| `GET /api/admin/sandboxes/pending` | → `PendingSandbox[]` | admin. The projects the admin can see whose valid `.agern/sandbox.yml` on the default branch is not approved in this version (one GitHub read per project; unreadable ones are left out), by name |
| `DELETE /api/admin/sandboxes/{runId}` | → `SandboxOverview` | admin. Removes that run's sandbox now (platform containers and services included); the run continues (§5 "Stopped by an admin"). 404 when the run has no sandbox right now |
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
- The conversation at needs human (§6): `run_not_waiting`, `review_in_progress`, `agent_busy`, `too_many_agents`,
  `run_worktree_missing`, `nothing_to_review`, `review_required` (409); `claude_credential_required` (422);
  `forbidden` (403) for everyone but the owner. After a restart an advisor of a `needs_human` run is `idle` and
  resumable, and an interrupted follow-up review is `required`.
- Releases: duplicate name → 409 `release_name_taken`; assigning a closed issue → 409 `issue_closed`; a stale
  order → 409 `release_issues_changed`. Every release route answers 404 when the caller cannot see the project,
  also when addressed by release id; then 403 `forbidden` for the owner/admin rules of started releases (§6
  "Releases"). Building: `release_not_planning`, `release_empty`, `release_already_building`,
  `release_branch_exists`, `release_not_building`, `release_not_paused`, `release_issue_not_held`,
  `release_issue_started`, `release_closed`, `release_active`, `release_name_locked` (409);
  `github_push_denied`, `claude_credential_required` (422);
  scheduling: `release_not_schedulable`, `release_not_scheduled`, `release_scheduled` (409);
  releasing: `release_not_ready`, `release_incomplete`, `release_empty`, `release_conflict`,
  `release_migration_conflict`, `release_submodule_commit_missing`, `release_submodule_check_failed`, `release_merge_failed`,
  `release_merge_method_unavailable`, `release_partially_merged`, `release_not_editable` (409).
- Sandbox approval: stale hash → 409 `sandbox_definition_changed`; invalid definition → 422
  `sandbox_definition_invalid`.
- "Needs Claude credential" → 422 `claude_credential_required`; too many agents → 409
  `too_many_agents`; issue already has an active run → 409 `active_run_exists`; workspace projects (§6 "Workspace
  runs"): 409 `workspace_invalid`, `member_label_missing`, `member_label_ambiguous`,
  403 `member_not_accessible`; missing CSRF header
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

**Project icons.** One optional icon per repository, set by an admin on the project page and shown on the project
card and in the project header (§10). Agern stores it, since GitHub has no per-repository icon:
- Storage: table `project_icons` in PostgreSQL, keyed by the canonical repository name (`repo`, the name GitHub
  returns, not the path parameter), with the sniffed media type, size, SHA-256, width, height, the bytes, and
  `updated_by` / `updated_at`. A new upload replaces the row in one statement. Nothing is written to the repository,
  its workspaces or `FACTORY_DATA_DIR`; icons of renamed or deleted repositories are not cleaned up.
- Upload checks, in order: admin (403), project visible to the admin (404), declared `Content-Length` and then the
  streamed body capped at 1 MiB (413 `icon_too_large`), empty (400 `empty_icon`), then the type by content only, never
  by file name or the client's `Content-Type`: the magic bytes of PNG, JPEG, WebP or GIF, otherwise an SVG check
  (`SvgIcons.inspect`). Anything that is neither — HTML, PDF, text, UTF-16 — is refused with 415
  `unsupported_icon_type`. For raster images: header readable (422 `invalid_icon`), at most 2048×2048 px read from the
  header without decoding (422 `icon_dimensions_too_large`). The limits are constants (`ProjectIcons.MAX_BYTES`,
  `MAX_EDGE`). The bytes are stored unchanged (no re-encoding, so animated GIFs and WebP survive); the browser crops
  and scales with `object-fit: cover`.
- SVG: a file is "not an SVG" (415) when it is not strictly valid UTF-8 (a BOM is fine), contains a NUL byte, fails to
  parse before its first element, or its root element's local name is not `svg`. Every document with an `svg` root that
  breaks a rule is refused with 422 `invalid_icon` and the message `The SVG cannot be used: <reason>` (the first
  violation in document order). The rules, checked in one streaming pass of the JDK's StAX parser with DTD support,
  external entities and entity replacement switched off and a resolver that always fails:
  - well-formed to the end; no `<!DOCTYPE>`, no processing instruction (the XML declaration is not one), no entity
    reference other than the predefined ones and character references; the declared encoding, if any, is UTF-8;
  - the root is `<svg xmlns="http://www.w3.org/2000/svg">` with a `viewBox` of four numbers and positive width and
    height, or a positive `width` and `height` in pixels (optional `px`);
  - an element allowlist (structure, shapes, text, gradients, patterns, clipping, masks, markers, `image`, `filter`
    and the filter primitives except `feImage`; not `script`, `foreignObject`, `a`, SMIL animation, `iframe`, media
    and the like); elements of the inert editor-metadata namespaces (Inkscape, sodipodi, RDF, Creative Commons,
    Dublin Core) are allowed, every other namespace is refused;
  - an attribute allowlist (presentation, geometry, text, paint-server and filter attributes, `data-*`, `aria-*`,
    `xml:lang`, `xml:space`, editor-metadata attributes); no `on*` attribute anywhere; no `xml:base`, no other
    `xlink:*` than `xlink:href`; presentation attributes may not contain a backslash (a CSS escape could spell `url`);
  - `href` / `xlink:href` only on `use`, `image`, the gradients, `pattern`, `filter` and `textPath`, and only as an
    in-document fragment (`#id`), except on `image`, where only a Base64 `data:` URL of a PNG, JPEG, WebP or GIF is
    allowed (the payload is decoded and sniffed);
  - every `url(…)` in an attribute or in CSS is `url(#id)` (optionally quoted);
  - CSS in `<style>` (only `type="text/css"`, `media`, `id`) and in `style` attributes has no backslash, `@import`,
    `@font-face`, `javascript:`, `expression(` or `image-set(`.

  All checks run on parser-decoded values (character references resolved, CDATA unwrapped, the text of a `<style>`
  concatenated across comments). Nothing is sanitised or rewritten: an accepted SVG is stored unchanged with
  `media_type` `image/svg+xml` and `width` / `height` 0 (vector, no pixel size); `MAX_EDGE` does not apply. Attachments,
  screenshots and generated images still do not accept SVG.
- From the repository: `POST /api/projects/{repo}/icon/from-repository` with `{path}` copies one file from the
  repository's default branch, read with the acting admin's own GitHub token (`GitHubClient.getFileBytes`, the contents
  API's raw media type, at most `MAX_BYTES + 1` bytes read). Checks, in order: admin (403), project visible (404), path
  (trimmed, one leading `/` removed; 400 when empty, over 1024 characters, or with a backslash, a control character or
  an empty, `.` or `..` segment), file present (404 `icon_file_not_found`), size (413), then the same validation as an
  upload (the file extension is not consulted, so a directory or a Git LFS pointer ends as 415). The icon is stored
  like an upload and does not follow later changes of the file. The path is only ever a contents-API path; no URL
  given by a user is fetched. The fake GitHub serves such files from `setFileBytes` / `setFile` and from
  `<dataDir>/fake-github/<repo>/<path>`.
- `Project.iconUrl` is `/api/projects/<url-encoded repo>/icon?v=<version>`, `<version>` being the first 16 hex
  characters of the bytes' SHA-256, so a replaced icon has a new URL. `GET /api/projects` reads all versions in one
  query.
- Serving: only when the stored type is one of the four raster types or `image/svg+xml` (404 otherwise), with the same
  headers as attachments (`nosniff`, the script-less CSP, same-origin CORP, `Content-Disposition: inline`, file name
  `<repo>-icon.<ext>`, `svg` for SVG). An SVG gets `Content-Type: image/svg+xml` and the stricter CSP
  `default-src 'none'; img-src data:; style-src 'unsafe-inline'; sandbox`: opened directly in a tab it can run no
  script and load nothing from any origin, while embedded raster `data:` images still render. `Cache-Control` is
  `private, max-age=31536000, immutable` only when `v` equals the current version, else `private, no-cache`. The web
  shows icons only through `<img>` (never inline SVG, `<object>`, `<embed>` or `<iframe>`), where browsers run no
  script and load nothing external anyway.

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
  `{ type: 'sandbox_approval', repo: string, approval: SandboxApproval | null }` is sent after an admin approved
  (`approval` set) or revoked (`null`) a project's sandbox definition, with the same visibility filter. Clients apply it
  to a loaded `SandboxStatus` (`approved` when the approval's hash is the file's, else `pending`) and reload after a
  reconnect.
  `{ type: 'sandboxes', runId: string, change: 'created' | 'removed' | 'stopped' }` is sent **to admins only** (of
  every project, like the overview) when a container of a run's sandbox was created (the sandbox or a platform
  container) or removed (one container to free a slot, or the whole sandbox when its run's build/review part ended),
  or an admin stopped a sandbox. It carries no project data; the admin page reloads `GET /api/admin/sandboxes`
  (debounced) and keeps a slow fallback poll for missed frames.
  `{ type: 'usage' }` is **private**: it goes only to the feed connections of the user whose Claude usage changed (limit
  windows recorded, spend added, credential replaced or removed), is not subject to the visibility filter and carries
  no data; the client reloads `GET /api/me/usage` (debounced ~500 ms, and after a reconnect).

## 9. Deployment and release

**Where it runs.** To begin with Agern runs on one machine on a local network, reached by browser
at `FACTORY_PUBLIC_URL` (plain HTTP on the LAN is supported: the session cookie is marked `Secure`
only when that URL is `https`). The server binds `FACTORY_HOST` (default `0.0.0.0`). Agents reach
the MCP endpoint over loopback (`127.0.0.1:<port>`), never through the public URL; when `FACTORY_HOST`
is one specific non-loopback address the server additionally listens on `127.0.0.1:<port>` (logged
at startup) rather than refusing to start. WebSocket and mutating requests are
rejected when their `Origin` does not match `FACTORY_PUBLIC_URL`.

**Sandboxes.** Builds and tests can run in per-run rootless Podman containers (§5 "Sandboxes";
`FACTORY_SANDBOX`, off by default). The containers belong to the service user; Agern and its
PostgreSQL stay native. In this release a sandbox provides a toolchain, sidecars, limits and a clean
environment — it is not a boundary between the agent and the host (see the security boundary below).

*A second unit, not a weaker first one.* Rootless Podman sets up a container's user namespace with
`newuidmap`/`newgidmap`, which get the right to map the user's subordinate ids from file capabilities;
under `agern.service`'s `NoNewPrivileges` and empty `CapabilityBoundingSet` they cannot work. Instead of
relaxing the unit every agent runs in, the package ships `agern-sandbox.socket` + `agern-sandbox.service`
(disabled until `agern sandbox enable`): `podman system service --time=0`, socket-activated, `User=agern`,
with only what rootless Podman needs — no `NoNewPrivileges`, systemd's default bounding set (no
capability is *added*; the unit runs unprivileged), its own `RuntimeDirectory` (`/run/agern-sandbox` as
`XDG_RUNTIME_DIR`, since the user has no login session and no lingering), root-owned Podman configuration
(`CONTAINERS_CONF`, `CONTAINERS_STORAGE_CONF` in `/usr/share/agern/podman`: `cgroupfs` manager, `netavark`,
`crun`, storage in `/var/lib/agern/containers`) and `Delegate=yes`. The unit hides `/run/systemd` (an
empty tmpfs; the journal socket and systemd-resolved's directory, where `/etc/resolv.conf` points on
Ubuntu, are bound back in): netavark starts a network's DNS server, aardvark-dns,
through `systemd-run --user --scope` whenever `/run/systemd/system` exists, which fails without a user
session bus — and then containers cannot reach each other by name. Hidden, netavark starts it directly as
a child of the unit (found on the first real-Podman CI run; netavark 1.4 and 1.14 behave the same). Before Podman starts, the unit's one root step
(`ExecStartPre=+`) creates an empty `/var/lib/cni`: Podman 4.3 (Debian 12) bind-mounts an empty directory over
it in its rootless network namespace — or, when it is missing, over the nearest existing parent, `/var/lib`,
which hides the network definitions under `/var/lib/agern/containers` so that every container on a network
fails with "network not found" (found on a Debian 12 VM; harmless where the directory already exists).
*Rootless networking: slirp4netns on every version.* Podman 5 (Debian 13) defaults to pasta for the rootless
network namespace. Debian 13's AppArmor profile for pasta allows it to write only under `/run/user/<uid>` and
`/tmp`, and has no `local/` include, so pasta cannot create its PID file in `/run/agern-sandbox` and every
container on a run network fails ("setting up Pasta … Permission denied", audit `profile="pasta"
operation="mknod"`). The options were: (a) move the runtime directory to `/run/user/<uid>` — but that directory
belongs to logind, which mounts a fresh tmpfs over it when anybody opens a session as `agern` (`su -`, `runuser
-l`) and removes it when that session ends, under the running sandboxes; (b) extend the profile — impossible
without editing a distribution file, since it has no `local/` include, and an edit would be overwritten by
package upgrades; (c) use slirp4netns, whose AppArmor profile is `flags=(unconfined)` and which confines itself
(Podman starts it with `--enable-sandbox --enable-seccomp`). (c) is chosen: one key in Agern's own
`containers.conf` (`default_rootless_network_cmd = "slirp4netns"`), no host file outside the package changed,
and the same helper on every supported release. Podman 4.3 does not know the key and ignores it (logged at debug
level only) and 4.3 and 4.9 already default to slirp4netns, so Debian 12 and Ubuntu 24.04 behave exactly as
before. The cost is throughput: slirp4netns is a user-space TCP/IP stack, slower than pasta, which matters only
for large downloads during builds. `agern sandbox selftest` and `agern doctor` print the kernel's
`apparmor="DENIED"` lines for the agern user's processes, so a profile that refuses a path again is named. The server keeps every restriction and
reaches Podman as a podman-remote client: `FACTORY_SANDBOX_PODMAN_URL=unix:///run/agern-podman/podman.sock`,
a socket `agern:agern 0600`, so no other user can drive it. `agern.service` is ordered after the socket.

*Limits.* Without a systemd user session Podman uses the `cgroupfs` manager in the unit's delegated cgroup.
`/usr/lib/agern/sandbox-service` (the unit's `ExecStart`) moves itself into the leaf `<unit cgroup>/service`
and enables `cpu`, `memory`, `pids` (and `io`) for the children, then execs Podman; the server passes
`--cgroup-parent <unit cgroup>/containers` (`FACTORY_SANDBOX_CGROUP_PARENT`), so each container gets its own
cgroup with its limits. Without an explicit parent rootless `cgroupfs` creates no cgroup and crun silently
drops the limits (crun 1.8 on Debian 12 always; newer crun falls back to a sibling of its own cgroup), which
is why the option exists. All sandboxes live below the unit: stopping it stops them, `systemctl edit
agern-sandbox` can cap them together (`MemoryMax=`, `CPUQuota=`), and `OOMScoreAdjust=500`, `CPUWeight=50`,
`IOWeight=50` make the kernel sacrifice and throttle sandboxes before the server and PostgreSQL.

*Enabling* (`agern sandbox enable`, root): checks the packages (`podman` ≥ 4.3, `uidmap`, `netavark`,
`aardvark-dns`, `crun`, `slirp4netns` — never installed by Agern;
the missing ones are named in one `apt install` line and nothing is changed), cgroups v2 and systemd;
gives `agern` a subordinate uid and gid range of 65536 with `usermod --add-subuids/--add-subgids` (the first
`100000 + k·65536` block that overlaps no range in `/etc/subuid`/`/etc/subgid` and no uid/gid the name
service lists; an existing range is kept); creates the storage directory *as `agern`* (root never writes
inside the data directory); enables the socket; runs a self-test as `agern` through the socket (pull an
image from an allowed registry, a private network with a sidecar reached by name, a sandbox with the
server's options and a bind-mounted directory under the data directory, limits read back from the
container's cgroup, a 200 MB allocation in a 64 MB container killed, all removed afterwards); only then
writes `FACTORY_SANDBOX=podman`, `FACTORY_SANDBOX_PODMAN_URL` and `FACTORY_SANDBOX_CGROUP_PARENT` into
`agern.env` (as data, like `setup-db`), enables `agern-sandbox-prune.timer` and restarts the server if it
is idle (otherwise it says to restart later). A failed self-test leaves `agern.env` unchanged and the units
off. `disable` (refuses while busy unless `--force`) writes `FACTORY_SANDBOX=off`, removes the two other
keys, restarts the server, removes containers and networks labelled `agern.run` (and self-test leftovers),
and stops and disables the units; images, cache volumes and the subordinate ids are kept. `status` shows
the setting, units, versions, sandboxes, cache volumes and `podman system df`; a user who is neither root
nor `agern` is told what cannot be read. `agern doctor` (sandboxes on) checks packages and version,
subordinate ids, cgroups v2, the URL, socket enabled and listening, that `agern` gets an answer, the unit's
cgroup owned by `agern` with `cpu memory pids` in `cgroup.subtree_control`, the cgroup parent, the prune
timer, the storage directory (`agern`, `0700`) and free disk space (warn < 10 GB, fail < 2 GB); with
sandboxes off it prints one line.

*Disk.* `agern-sandbox-prune.timer` (daily, as `agern`, not root) runs `agern sandbox prune`: images no
container uses whose build time is older than `AGERN_SANDBOX_PRUNE_HOURS` (default 168; Podman records no
"last used", so an old base image is removed once idle and pulled again on the next run), dangling images,
unused volumes of that age that are not per-project caches (label `agern.cache`), and self-test leftovers.
Cache volumes are removed only by `prune --caches`/`--all`. Containers are removed with `--volumes`, so an
image's anonymous volumes (e.g. PostgreSQL's data) go with the run.

*Packaging.* The Podman stack is in `Suggests:` (`podman (>= 4.3), uidmap, netavark, aardvark-dns, crun,
slirp4netns`), not `Recommends:`, so an install without sandboxes gets no container runtime.
Versions checked with `apt-cache` in the distribution images: Debian 12 Podman 4.3.1 (crun 1.8.1, netavark/
aardvark-dns 1.4.0, slirp4netns 1.2.0), Ubuntu 24.04 Podman 4.9.3 (crun 1.14.1, netavark 1.4.0), Debian 13
Podman 5.4.2 (crun 1.21, netavark 1.14.0, slirp4netns 1.2.1). An upgrade only reloads systemd: it never disables
sandboxes, never restarts the sandbox service (that would stop the sandboxes of runs in progress) and never
touches `/etc/subuid`; `postinst` re-enables the socket and timer when `agern.env` has
`FACTORY_SANDBOX=podman` (e.g. after `remove` and reinstall). `prerm remove` stops and disables the sandbox
units; `postrm purge` says so and removes `/var/lib/agern/containers` (`rm -rf --one-file-system`, which
never follows a symlink; the files belong to subordinate ids only root can delete), and `userdel` drops the
subordinate ranges.

*Verified* by `packaging/deb/sandbox-host-test.sh`, which CI runs on a GitHub-hosted Ubuntu 24.04 VM (job
`sandbox`): enable and self-test, doctor, the server under the hardened unit removing a leftover sandbox
through the socket, the `PodmanIntegrationTest` (real rootless Podman: sidecar DNS, bind mount ownership,
host paths invisible, not privileged, cache reuse, exec, timeout and restart, memory limit enforced, labels,
removal, and a command that runs for `AGERN_PODMAN_IT_LONG_SECONDS`, default 300 s, returning its output and exit code) run as `agern` *inside* `agern.service`, upgrade, prune, disable and purge. Debian 12 and 13 run the
same script on a VM by hand. In CI the in-service Gradle runs `--offline`, from a Gradle home seeded from the
runner's cached one, so a Maven Central outage or rate limit cannot fail the job.

**CI.** `.github/workflows/ci.yml` runs on every pull request and every push to `main`: the frontend
(typecheck, lint, build), the server (`./gradlew build`, embedded PostgreSQL), the web end-to-end suite,
shellcheck of the packaging scripts, the migration check (§6 "Database migrations"), the Debian package
and its smoke tests, and the sandbox host test (above). The end-to-end job (`Web (e2e)`) runs the
Playwright suite in `web/e2e` exactly as locally: `web/e2e/server.mjs` builds the server and the frontend
and starts Agern in fake mode (fake GitHub, Claude CLI and sandboxes) with a fresh database in a
`postgres:16` service container, reached through `E2E_PG_CONTAINER_CMD` (a `psql` prefix on the runner)
and `E2E_DB_HOST`/`E2E_DB_HOST_PORT`. A container is fine there: the no-Docker rule is for production.
One worker and no retries — the specs share one server and build on each other's state, and a flaky
check would block Agern's automatic release merges, which wait for every check (§6 "Releases"). Gradle,
npm and Playwright's Chromium headless shell are cached; on failure the HTML report and the traces are
uploaded as the artifact `playwright-report`. A coroutine of `Services.scope` that fails logs its exception at
ERROR (it never reaches the thread's default handler); in the server tests `TestWorld.close()` then fails the test
that owns those services, so the failure is reported by its culprit instead of poisoning a later test. When the
server job fails, its Gradle test report and XML results are uploaded as the artifact `server-test-report`.
`publish-main` needs every job.

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
`git`, `ca-certificates`, `curl`, `passwd`; Recommends `postgresql`, `chromium` (the UI reviewers' headless browser,
§6 "Screenshots"; on Ubuntu apt satisfies it with `chromium-browser`, a launcher for the snap, which `agern doctor`
flags); Suggests `gh`. The `claude`
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
push to `main` after the tests, the migration check (§6 "Database migrations") and the package smoke tests on Debian 12, Debian 13 and Ubuntu 24.04
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
Sandboxes do not move this boundary: their containers are rootless and owned by `agern`, never
privileged and never given more than Podman's default rootless capabilities, so they grant nothing
the `agern` user does not already have — and, since agents run on the host as that user, they also
take nothing away from it (containing the agent is a later step). The one thing the sandbox unit
adds is that a process running as `agern` there may use `newuidmap`/`newgidmap` on the range root
assigned to `agern`: ids that belong to no account (the allocation checks), so mapping them gives
access to nothing but the containers' own files. An agent can reach the socket and the Podman API
(it is the same user), and could run its own rootless containers — exactly what `agern` could do
with `podman` installed and no sandbox unit; no rootful Podman, no `docker` group, no privileged
helper and no sudo rule exist. Root never runs anything the agent can write on this path: the
units, `sandbox-service` and the Podman configuration are root-owned package files, `agern sandbox`
talks to Podman as `agern` (`runuser`), and `agern.env` is parsed as data.

## 10. Frontend

Routes: `/login`, `/` (projects), `/p/:repo` (issues · CLI chat · agents), `/p/:repo/runs/:runId`
(pipeline diagram with live stages; selecting a stage shows that agent's transcript),
`/p/:repo/releases/:releaseId` (a release's plan and build, below),
`/p/:repo/sandbox` (the project's sandbox definition and its approval, below), `/agents` (everything running), `/settings` (own credentials, and "Models & cost": the user's own model per
role and fix rounds, with presets — *Default* clears every choice, *Economy* picks Sonnet for chat and spec,
Haiku for the four reviewers and one fix round), `/admin` ("Factory settings": the default models and the
default and maximum fix rounds; installing software stays admin-only).

**GitHub status.** `/github-status` (every logged-in user; user-menu item "GitHub status" between "Credentials &
security" and "Sign out", suffixed " — degraded" / " — down" while GitHub is not working) shows, polled every 60 s
with a Refresh button: the verdict (`data-verdict`, heading, glyph and explanation of what it means for runs, chats and
releases, the reason and "Updated …"); GitHub's official status (description, non-operational components with the
relevant ones first, open incidents with "Details on githubstatus.com"; a warning when githubstatus.com could not be
reached; a muted line when it is off; a note when the verdict is not working while GitHub says all is well); "What
Agern sees", a table per kind with calls, 5xx, timeouts/network, rate limited and auth for the last 15 minutes and 24
hours and the last error (message, HTTP status, request id, time), scrolling sideways inside its own container; and
the rate limits of the caller's token and, anonymously, of the other tokens in use. The shell polls
`GET /api/github/status?brief=1` on mount and every 60 s (one poll for the menu item and the banner). While the verdict
is degraded or down, a one-line `role="status"` banner between the top bar and `<main>` (`GitHubBanner`, not on
`/github-status`) says "GitHub is degraded/down for Agern." with a "See GitHub status" link and a Dismiss button.
Dismissing stores the verdict in `sessionStorage` (`agern.githubBanner.dismissed`): hidden while the verdict stays the
same, shown again for the other non-working verdict; the stored value is removed when a poll reports `working`. The
banner is `flex: none`, so the project page's app shell stays one viewport tall.

**Claude usage.** For users with a Claude credential, the top bar shows a compact indicator before the active-agents
count on every page (`UsageIndicator`, a link to `/settings#models`, `data-level` `none` | `ok` | `high` | `limit` |
`spend`): for an OAuth token the most constrained window — a `rejected` one (*limit*, with "resets in …" when known),
else the highest utilization, as short label and percentage (`5h 42%`; *high* from 80 % or `allowed_warning`, with a
⚠ glyph and the word "high"); "no usage data" before any agent ran; "usage reset" when every window's reset time has
passed. For an API key it shows the spend this month, and its label says that this is spend through Agern, not the
Anthropic account's balance. States differ by text and glyph, not only colour; the title / aria-label spells out
window, percentage, reset time and age. Settings → "Models & cost" has a "Usage" block (`#usage`): every window with
percentage and bar, a *high* / *limit reached* badge, "resets in …" and "updated … ago" (a window whose reset time has
passed shows "reset"), the note that the values are only refreshed while an agent runs, and "Spent through Agern:
today $X · this month $Y (UTC days)"; for an API key only the spend, with the same "not the remaining balance"
sentence. Both reload on the private `usage` frame, after a feed reconnect, when the credential changes and when the
UTC day rolls over (`useClaudeUsage`, `web/src/lib/usage.ts`); relative times are re-evaluated every 30 s.

**Project icons** (§8). The project card (32 px) and the project header (28 px, between "projects /" and the name)
show the icon as a rounded square tile (`ProjectIcon`), or the project's first letter on the user avatar's tile when
none is set or it fails to load, so cards stay aligned. Admins get the header tile as a "Change project icon" button
with a menu: upload or replace (the file picker accepts the raster types and `.svg`), "Use a file from the
repository…", remove, and the hint "PNG, JPEG, WebP, GIF or SVG, up to 1 MB. Shown as a square."; a file over 1 MB is
refused before uploading, and upload errors appear in a dismissible `role="alert"` line in the header. "Use a file from
the repository…" opens the dialog "Icon from the repository" with a path field (hint: read once from the default
branch, the allowed types and size) and "Cancel" / "Use as icon"; the server's error is shown inside the dialog, which
stays open with the path, and on success or cancel focus returns to the icon button. The mock API accepts SVG uploads
without checking their content and offers a few seeded repository images (e.g. `logo.svg` in `web-shop`). The overview shows a change the next time it loads.

The chat renders the Claude stream the way the terminal does: prompt line, streamed assistant
text, tool calls as `⏺ Tool(args)` with collapsible results, thinking, cost/turn footer. Sessions
owned by someone else are shown read-only with an "observing <user>" banner.
The chat header shows the latest `checkout` event next to the path (`CheckoutBadge`): `~/<repo> · <sha7>`, with the
branch (or `detached`) before the sha when it is not the default branch, and ` · <n> behind` in amber when the checkout
is behind; `title` and an `sr-only` span carry the full sentence (up to date, or how far behind and why it was not
updated). Without a `checkout` event only `~/<repo>` is shown; at the narrow breakpoint it is hidden with the path.
`checkout` events are not transcript items (a malformed one is shown as `unknown`).

**Conversation at needs human** (§6). On the run page the `human` node is selectable when the run is `needs_human` or
has a conversation (`?stage=human`; the default selection of a `needs_human` run with a conversation). Its sub-line
reads "reviewing changes", "changes not reviewed" or "waiting for owner". The owner gets "Talk to the agent" next to
"✓ Approve & merge", which selects the node and focuses the composer; approve is disabled while the follow-up review
is not `none` or the advisor is `starting`/`running`, with a hint linked by `aria-describedby`. When the run's latest
approver verdict is `ESCALATE` (`approverEscalation`), "✓ Approve & merge" does not merge at once: it opens the dialog
"Merge against the approver's advice?" with the approver's summary (Markdown, a `note` "The approver's reason"), which
pull request goes into which branch, and "Don't merge" (focused) / "Merge anyway"; only the latter approves. The panel
(`stage-detail` layout) shows the blurb, model, cost and turns, a `role="status"` line for the review state, "Review
again" for the owner in state `required`, and the transcript (`ChatView` in advisor mode: text only, no `/factory`
commands, Esc interrupts) with the owner-only composer "Message the agent". Follow-up rounds are labelled
"follow-up N" (N = round − `followUpFrom` + 1) in the rounds strip, round tabs, node notes and the header's round
line. On the agents page an advisor links to `?stage=human` and shows "waiting for input" when idle.

On the run page, a selected stage (the selected round's stage row) that has screenshots shows a "Screenshots <n>"
section after its findings: a wrapping grid of lazily loaded thumbnails with their captions as `figcaption`. A
thumbnail is a button ("Open screenshot: <caption>") that opens a dialog with the image scaled to fit, the caption,
its type, size and dimensions and an "Open full size ↗" link. A stage without screenshots shows nothing extra.
Each of the run's `warnings` is a notice under the header (`role="note"`, amber like other warnings): the warning's
title in bold with the round's name, its detail, and a "See the <role> review" button that selects that stage and
round. A stage with a `warning` shows it above its summary.
Screenshots attached while the page is open arrive through the feed (`useLiveRun`), without a reload.
Below them, a stage whose agent generated images (§6 "Generated images") shows "Generated images <n>": thumbnails
("Open image: <purpose>") with the purpose and the prompt in a "Prompt" disclosure, a lightbox with type, size,
dimensions, model and "Open full size ↗"; it also updates live.

**Image generation.** Settings has an "Image generation" section (`#images`) between GitHub and Models: the provider
as a segmented choice from `GET /api/config`, a password field for the API key, a model field with the provider's
models as suggestions, Save/Replace ("Checking with <provider>…") and Remove, and the limits. An unavailable
provider's choice is disabled (its reason shown muted when it is the saved provider or the only one). For OpenAI Codex
the key and model fields are replaced by the device login (`CodexConnect`): an explanation and "Connect ChatGPT";
while pending "1. Open <link>", "2. Enter this code <code>" with a copy button, "Waiting for you to finish signing in…"
and Cancel, polling every 3 s; then "Connected. New image requests will use it." and the status "OpenAI Codex ·
ChatGPT login" with Reconnect and Remove; a failed or expired login shows its error and "Connect ChatGPT" again. In a transcript, an
`images` event renders as a block "Generated N image(s) with <provider> · <model>", the purpose, the prompt in a
disclosure and one tile per image (from `GET /api/sessions/{id}/images`; a discarded image shows a placeholder).
The image's owner, for chat images, gets "Use for issue…" (a dialog with issue number and name) and "Discard", or
`selected for #<n> as "<name>"` with "Remove from #<n>" until a run locks it; others, and stage transcripts, are
read-only. In the issue list an issue with selected images shows an "<n> image(s)" button opening "Images for #<n>"
(thumbnail, name, purpose, dimensions, who selected it, the run using it, Remove for the owner while unlocked).

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
— "in preparation", "scheduled", "building", "paused", "ready to release", "released" or "cancelled" — and "N issues", or for started releases
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
note for held and skipped issues; "Retry #n" (owner, held or skipped), which opens the retry dialog (§10 "Retrying a
run") — a waiting issue retried with `continue` or `step` says "Continues from the commits of its run when its turn
comes." / "Retries the failed step of its run when its turn comes."; in a started release moving is owner-only
among `waiting` issues and removing is for the owner or admins on `waiting`, `held` and `skipped` issues (any issue
when cancelled); "Add an open issue" shows while planning and, for the owner, while building or paused.

Releasing on the release page (§6 "Releasing"). A `ready` release shows the section "Release pull request" (`<h2>`):
the link "#<prNumber>" to the pull request (new tab) and `release/<name> → <targetBranch>`, or "No pull request
yet."; a `role="status"` line — complete and clean "Ready to release. Merges cleanly into <target>."; complete and
unknown "Ready to release. Mergeability has not been confirmed yet."; conflict "`release/<name>` no longer merges
cleanly into <target>. Agern will not merge it. Bring it up to date, or resolve the conflicts on the release branch
and check again.";
incomplete "Incomplete: N issue(s) are held (#a, #b). Approve or retry their runs, or move them out, before
releasing."; empty "Nothing was merged into the release branch, so there is nothing to release."; no usable merge
method "This repository only allows squash merges. …" — and, when it can be approved, a muted line naming the merge
method ("Approval merges it by rebasing: one commit per issue on <target>." or "…with a merge commit…"). The notice,
when set, is in `role="alert"`. The owner gets "Check again" ("Checking…" while it runs) and the primary "Approve and
release", disabled unless the release is complete, has a pull request, does not conflict and has a merge method
(`aria-describedby` → the status line); everyone else sees "Only <owner> can approve this release." The dialog
"Release <name>?" says "This merges pull request #n into <target> by rebasing: N issues, one commit each. The issues
are closed and the branch release/<name> is deleted. This cannot be undone." (or "with a merge commit: …, plus the
merge commit"), with "Cancel" focused and "Approve and release" ("Releasing…"); errors close it and show in the
page's alert line. "Cancel release" (owner or admin) and, for the owner, "Retry #n" and adding issues stay available
in `ready` (adding builds again); there is no Pause. Each issue with a run pull request shows a "PR #n" link. A
`released` release is read-only: no Edit, Delete, Cancel, move, remove, add, retry, check or approve; it shows
"Released <time ago> by <owner> · pull request #n" (or "Merged on GitHub <time ago> · …"), the method in words, the
default branch's commit (7 characters) and that the issues were closed and the branch deleted. The note "This release
is a plan…" is shown only while planning.

Bringing a release up to date on the release page (§6 "Releasing", "Bringing a release up to date"), only for a
single-repository `ready` release (a release of several repositories shows nothing of this): when the branch does not
conflict but is behind, a muted line "`release/<name>` is N commit(s) behind <target>." below the status line.
Directly below the status line (the conflict notice's button, not repeated in the actions row) the release owner and
admins get "Bring up to date" when `syncNeeded` and no sync run is `running`, disabled while any action of the page is
busy; everyone else then sees "Only <owner> or an admin can bring it up to date.". It opens the dialog "Bring
release/<name> up to date?": "An agent run merges <target> into release/<name> with a merge commit (no rebase, no
force-push), resolves the conflicts, renumbers this release's own database migrations above <target>'s, runs the
project's checks and pushes release/<name>. Reviewers then look at the conflict resolution. Nothing is merged into
<target>. It runs with your Claude credential and GitHub token." plus, when the release would be rebased, "Afterwards
the release is merged with a merge commit instead of by rebasing."; "Cancel" (focused) and "Bring up to date"; Escape
closes; an error closes it and shows in the page's alert line. The latest sync run shows as: `running` — a
`role="status"` line "Bringing release/<name> up to date with <target> (started by <user>)." with "Open the run", and
"Approve and release" is disabled ("Check again" stays); `failed` — a `role="alert"` block "Could not bring it up to
date. <error>" with the link (the button is offered again while `syncNeeded`); `unreviewed` — a `role="alert"` block
"<target> was merged into release/<name> (commit <sha7>), but the review of the merge did not pass or did not finish.
<error> Look at the run before releasing." with the link; `done` — a muted "Brought up to date with <target> <time
ago> by <user>." with the link. It updates live from `release` frames.

A sync run (`Run.kind = 'sync'`) never shows `#0`: lists show `sync` where they show `#<n>` (run header and document
title, agents band, home page strip, project runs list, admin sandbox rows and dialog) and its title "Bring
release/<name> up to date with <target>"; its first diagram node is "sync" with the release branch, and its detail
shows the release (name, `release/<name>` → target, a link to the release page, the pushed commit) instead of reading
an issue. Its status pill reads "up to date" when merged; the diagram shows spec-writer and approver skipped, the
outcome node "push release/<name>" and never a human approval. There is no Retry; Cancel as for other runs.

Release mode on the release page (§6 "Release mode"): except when released or cancelled, the header shows the mode —
for whoever may change it a select labelled "Release mode" ("Manual approval" / "Automatic"; "Automatic" is disabled
for an admin who is not the owner), for everyone else "Release mode: Manual approval|Automatic" — with a muted hint
(`#release-mode-hint`): manual "It waits as “ready to release” until <owner, or “whoever starts it”> approves it.";
automatic "Once everything is built, merges cleanly and all checks on the release pull request have passed, Agern
merges it with <owner>'s GitHub token. Held issues, conflicts and failed checks stop it.". The "Release pull request"
section shows the checks as a pill ("checks passed", "checks pending", "checks failed", "no checks", "checks
unknown", "waiting for checks to appear") with the detail ("This pull request has no checks; that counts as green." for none) and, for an automatic
release that could be approved, a line `.release-auto`: "Set to release automatically: Agern merges it once all
checks have passed." (pending, unknown), "…: Agern is merging it." (green) or "…, but a check failed: Agern will not
merge it until the checks pass.". Approval stays available in both modes and is never blocked by checks; the
confirmation dialog warns "Checks failed on this pull request (<detail>). Approving merges it anyway.", "Some checks
have not finished yet (<detail>). Approving merges it without waiting for them." or "The checks on this pull request
could not be read. Approving merges it without them." or, while `awaiting`, "No checks have appeared on this pull request
yet; CI may not have started. Approving merges it without waiting for them.". A released automatic release reads "Released automatically
<time ago> for <owner>". The start dialog adds "It is set to release automatically: … Agern merges it into the
default branch with your GitHub token, without asking you." (manual: "When everything is built, it waits for your
approval before anything reaches the default branch."), and the schedule dialog's hint a similar sentence. The
"New release" dialog has a radio group "When it is ready": "Manual approval" (default) and "Release automatically",
each with a one-line explanation. On the project page an automatic release that is not finished adds "· automatic"
to its row. The mock implements the mode with the server's rules and seeds `1.2` with pending checks and
`cart-fixes` as automatic with failed checks.

Project page, releasing: a `ready` row shows the pill "ready to release" and "k of n merged", plus "· h held" or "·
conflict". Released and cancelled releases leave the list and appear in a collapsed `<details>` "History (N)" below it
(newest first by `releasedAt`, else `updatedAt`; name, pill, "N issue(s)", and for released ones the relative time),
which is not rendered when empty; both update live. Released and cancelled releases are not
offered as targets in the issues panel's selects or the release page's "Add an open issue"; `ready` ones are (owner
rules as for building). The mock seeds `web-shop` with a ready and clean release (`1.2`), a conflicting one
(`cart-fixes`), an incomplete one (`payments`), a released one (`1.1`) and a cancelled one (`promo`), and implements
approve and check with the server's rules and error codes.

**Merge policy** (§6 "Merge policy"). The project page shows everyone a row under the header, "Merge policy:
<label> — <explanation>": "Automatic — the approver agent may merge.", "Owner approves — every merge waits for the run
owner's approval in Agern.", "Merge on GitHub — Agern never merges; the team reviews and merges pull requests on
GitHub." plus "Reviewers requested: @a, team x." when configured. Admins get a "Change" button that opens a dialog: a
radio group (`fieldset`, legend "Merge policy") with the three policies and their explanations, for "Merge on GitHub"
the inputs "Reviewers (GitHub logins, comma-separated)" and "Team reviewers (team slugs, comma-separated)", the note
"Applies to runs started from now on; running runs keep their policy.", Cancel and Save ("Saving…" while saving). A
server error stays in the dialog (`role="alert"`); Escape closes it and focus returns to "Change". The run header
lists "merge policy" for single runs whose policy is not `automatic`. `owner_approves` at `needs_human` shows the note
"This project requires the run owner's approval before every merge." (the approver's recommendation is the
needs-human banner; with `APPROVE` Approve merges in one click, with `ESCALATE` the confirmation applies); others
still read "Waiting for <owner> to approve.". A `ready` run shows the status note (`role="status"`, not an alert)
"Ready for review on GitHub. This project merges on GitHub: Agern does not merge. Review and merge <PR link>; this
page updates when it is merged or closed." and no Approve, Cancel or Retry. A `github` run at `needs_human` has no
Approve button but the note "This project merges on GitHub: Agern does not merge this run. The pull request can be
reviewed and merged there."; "Talk to the agent" and Cancel remain. A run merged on GitHub reads "Merged on GitHub by
@<login>" (or "Merged on GitHub"). The `ready` pill reuses the pass tone. In the diagram a `github` run's merge node is
"merge on GitHub": at `ready` pending with "PR #n · waiting on GitHub" (the human node skipped), merged on GitHub done
with "merged on GitHub"; an `owner_approves` run that merged shows the human node "approved by owner" whatever the
approver said. On the release page a `github` project replaces "Approve and release" with "This project merges on
GitHub: review and merge the release pull request there.", and a non-automatic policy disables the automatic mode
(the release mode select and the "New release" dialog: "this project requires a human approval"). The mock implements
the setting, the snapshot and the outcomes at approval (no follower).

**Checks before a merge** (§6 "Checks before a merge"). A `waiting_for_checks` run shows a status notice (`role="status"`):
"Waiting for checks on `<sha7>`. <detail>. The pull request is merged automatically when they pass; Agern stops waiting at
<local time>.", with the pending checks below it as links (new tab) when GitHub gave an `https://` URL. The owner sees
"Merge anyway…" and "Cancel run"; others "Waiting for checks; it merges by itself." A `needs_human` run whose checks
block the merge shows the reason in the error banner and gives the owner "✓ Approve & merge" (evaluates again) and
"Merge anyway…". That dialog ("Merge without passing checks?", the shared `Dialog`: focus trap, Escape, "Don't merge"
focused) lists each check that has not passed with "failed (<conclusion>)" or "pending" (else the detail), the
approver's reason when it escalated, and what merging does; errors (`checks_changed`, `review_required`, …) appear in
the page's `form-error` alert. After an overridden merge a muted line reads "Merged by <user> despite checks: <detail>".
The diagram's merge node is `running` "waiting for checks", `failed` "checks not passed", or noted "despite checks";
outcome nodes add their sub and note to their accessible label. The status pill reads "waiting for checks".

**Retrying a run** (§6 "Retrying a run"). The run page of a `failed` or `cancelled` run shows the owner a "Retry…"
button; "Retry #n" on the release page opens the same dialog ("Retry run <short id>?" / "Retry #n?"). It loads the
options, shows "Recommended: <label>. <reason>" and one radio per mode — "Retry the failed step", "Continue from the
commits", "Start over" — each with its detail line, unavailable ones disabled, the recommended one preselected and
marked "recommended"; the confirm button is labelled with the chosen mode, errors appear in `role="alert"` in the
dialog. After a retry of a single run the page shows the run now at work (the same run for a step retry, else the new
one); a release run's retry goes through the release and opens the release page. The run header lists "continues
run <short id>" and "continued by run <short id>" as links when set. The mock implements the options (no step retries)
and both retries.

**Waiting for the network** (§6 "Transient failures"). While `run.waiting` is set, the run page shows a banner
(`role="status"`) between the header and the error box: "**Waiting for the network** since <date and time> (<time
ago>). <detail> Next try at <time> (attempt <attempt + 1>)."; the status pill keeps the run's status and is followed by
"waiting for network". The owner and admins get "Retry now" (disabled, "Retrying…", while it runs) and "Give up…",
which confirms in "Give up waiting?" ("The run fails now, as it would have without waiting. Its worktree is removed;
the branch and any pull request stay on GitHub, and you can retry the run afterwards.", buttons "Keep waiting" —
focused — and "Give up"); others see the text only. Errors go to the page's alert line; "Cancel run" stays. On the
release page, a release paused with reason `network` reads "Waiting for the network since <time>. <note> Next try at
<time>.", its Resume button is labelled "Retry now", and the owner or an admin gets "Give up on #<n>…" (confirmation
"Issue #<n> is held and the release goes on with the next issue. You can retry it later."). A building release whose
running issue's run waits reads "#<n> <title> is waiting for the network since <time>; next try at <time>.", the issue
row shows the same as a note next to its run link, and the owner or an admin gets "Retry now" and "Give up…" acting on
that run (the run page's confirmation). The texts come from `lib/networkWait.ts` (unit-tested); times tick with
`useNow`. The mock never waits: its endpoints answer 409 with the documented codes after the permission checks.

On the run page of a release run the merge node is titled "merge to release" and the header shows the base branch
linking to the release page (plain text when the release was deleted); single runs still say "merge to main".

**Workspace projects** (§5 "Workspace projects", §6 "Workspace runs"). The project header shows the tag
"workspace · N members" for a valid workspace. The activity pane gets a "Members" section above "Releases", one row per
member: the directory (monospace), the repository as a link to `/p/<repository>` when the caller can read it, else
plain text with the pill "no access" (title "Your GitHub token cannot read this repository; it is left out of your
checkouts."), a submodule member the tag "submodule" (class `member-submodule`, title "A git submodule of the
workspace repository. After a run's pull request merges, Agern opens a pull request that moves the submodule
pointer."), the base branch (monospace) and the label as a tag. Below the rows, when there are any, a muted line
"Context only (submodules of other organizations, never a run's target): `<path>`, …" (class `member-context`). When
`missingLabels` is not empty, a warning notice (class `member-labels-missing`) "Member labels missing on GitHub" —
"Issues are routed to a member by its label. Missing: `<label>`, …" with the button "Create missing labels"
("Creating…" while it runs) sits above the rows; afterwards it shows what is still missing (hidden when nothing is),
and an error below it (class `member-labels-error`, `role="alert"`). The
header tag's title names the definition's file ("Several repositories checked out side by side (<path>)"). An invalid
definition shows instead a `role="alert"` box "`<path>` is invalid. Runs cannot start until it is fixed." with the
errors as a list.
A workspace project defined by `.agern/workspace.yml` has no "Sandbox" section (sandboxes belong to the member
projects) and no "+ New" release button; without releases the empty text reads "Releases are not available for
workspace projects yet." A submodule workspace (`path` `.gitmodules`) keeps both, for the runs of the workspace
repository itself. Each issue row shows its member's directory as a tag (title "Member <directory> · <repository>",
plus "no access" when the caller cannot read it), else the muted text "no member label" or "several member labels"; in
a submodule workspace an issue without a member label shows the tag "workspace repository" (class
`issue-member--parent`, title "No member label: a run changes the workspace repository itself"). "▶ Start run" is
disabled unless the definition is valid, exactly one member matches (or, in a submodule workspace, none) and the
caller can read it; its title says why ("Add one member label
on GitHub first", "Keep exactly one member label", "Your GitHub token cannot read <repository>", "Fix
<path> first"); server refusals still show in the panel's alert line. The run page of a workspace run
lists "repository" (a link to `/p/<targetRepo>`), "directory" and "base", shows the pull request as
`<targetRepo>#<n> ↗`, titles the merge node "merge to <baseBranch>" and links the sandbox line to
`/p/<targetRepo>/sandbox`. The mock seeds `shop-workspace` with three members (`shop` on `dev`, `libs/design` on the
default branch and a submodule, `ios` that `bob` cannot read), issues with one, no and several member labels, and a merged workspace
run; its `startRun` refuses with the server's codes and messages.

**Sandboxes.** The activity pane has a "Sandbox" row (the state as a pill — no definition, invalid, needs approval,
changed, approved — and a short line) linking to the project's sandbox page `/p/:repo/sandbox`, a normal scrolling page
for everyone who can see the project: the state with an explanation (switched off on this server; no definition, with
an example; the validation errors; waiting for approval; changed since the approved version, by whom and when; approved),
the definition (image, the user commands run as — "setup runs as root" —, setup script, platforms with their image and
whether their setup is their own or the top-level one, environment, services with their readiness check or "no
readiness check", caches, resources with the server's caps and that each platform in use is another container with
these limits), a line diff
between the approved and the current content when they differ, and the raw file. Admins get "Approve…" — the
confirmation names every image with its role (default, platform, service) and shows every setup script that will run
and says plainly that they will run on the factory host for
every run of the project — and "Revoke…" (confirmation); others see who can approve. The page and the row follow
`sandbox_approval` frames. The run page shows one line under the run's facts: "sandbox" with the image and the approved
definition's hash prefix and, when the definition has platforms, each platform (marked ✓ once commands ran in it),
then "review sandbox ✓" when the UI reviewers ran the app in a review sandbox (`reviewUsed`), or
"none — <why>" (switched off, no definition, not approved, invalid), and a link to the
sandbox page. In a builder's or UI reviewer's transcript every `system` event with `payload.sandbox` is a collapsible command block
(a `review` chip when `review` is set — screen readers hear "Review sandbox command:" —, the platform as a chip when `platform` is set, `$ command` or "setup script", exit code or "timed out", duration,
"truncated"; failures in red): opened, it shows the
last 200 lines of the output in the terminal style with "Show all N lines", the full script or multi-line command, and
nothing of the output is rendered while closed. The `factory_sandbox_exec` and `factory_sandbox_wait` tool calls
themselves are summarised as "Exit N in X s · full log below" (or "Timed out …"); an answer that is still running is
"Started in the background" (a background start) or "Still running after N s" (a wait that ran out), and the wait's
arguments show the handle or "latest command". The admin page has a "Sandboxes" card: runtime, containers in use of the maximum (with a note that
every container that runs commands takes a slot and services take none), the per-container caps, the platform cap, the
command time limit and the registries; the running sandboxes (run, project, owner, run status, age, each existing
container as platform chip + image — the review sandbox's as a `review` chip —, services, platforms not created yet,
slots held, per-container limits and the memory the run may use in all, definition hash) with "Stop" (confirmation:
the containers go, the review sandbox's too, the run continues and its next sandbox command creates a new one); and the projects waiting for an approval, linking to their sandbox pages. The card reloads
(debounced) on every `sandboxes` frame and otherwise every 60 s. The software
requests card notes that build and test tools can go in the project's `.agern/sandbox.yml` instead of on the host.

Artwork is served from `web/public/art/` (see `art/README.md`).
