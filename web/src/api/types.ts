// Types mirror docs/ARCHITECTURE.md §8 exactly. Change the contract first, then this file.

export type CredentialKind = 'oauth_token' | 'api_key';

export type User = {
  id: string;
  username: string;
  displayName: string;
  isAdmin: boolean;
  githubLogin: string | null;
  hasGithubToken: boolean;
  hasClaudeCredential: boolean;
  claudeCredentialKind: CredentialKind | null;
  twoFactorEnabled: boolean;
  /** ISO timestamp while the second factor is locked after too many wrong codes, else null. */
  twoFactorLockedUntil: string | null;
  /** `classic` (ghp_…) or `fine_grained` (github_pat_…); null without a token or when unknown. */
  githubTokenKind: 'classic' | 'fine_grained' | null;
  /** Classic tokens: the OAuth scopes GitHub reports for it; null without a token or for fine-grained tokens. */
  githubTokenScopes: string[] | null;
  /** The image provider id (from `ClientConfig.images.providers`) and model; null without a stored key. Never the key. */
  imageProvider: string | null;
  imageModel: string | null;
};

/** Login is multi-step: password → (totp | enroll) → done. See §4 "Two-factor authentication". */
export type LoginStep =
  | { next: 'totp' }
  | { next: 'enroll'; secret: string; otpauthUri: string }
  | { next: 'done'; user: User; recoveryCodes?: string[] };

export type Project = {
  name: string;
  fullName: string;
  description: string | null;
  private: boolean;
  defaultBranch: string;
  language: string | null;
  openIssues: number;
  pushedAt: string | null;
  htmlUrl: string;
  activeAgents: number;
  activeRuns: number;
  /** `/api/projects/<repo>/icon?v=<version>`, or null without an icon (ARCHITECTURE §8 "Project icons"). */
  iconUrl: string | null;
  /**
   * The workspace definition (ARCHITECTURE §5 "Workspace projects"); null for a project that is one repository.
   * Filled by `GET /api/projects/{repo}` only (always null in the project list).
   */
  workspace?: Workspace | null;
  /** Settings an admin sets (ARCHITECTURE §6 "Merge policy"); the defaults without a stored row. */
  settings: ProjectSettings;
};

/**
 * How a project's runs reach the default branch (ARCHITECTURE §6 "Merge policy"): `automatic` = the approver agent may
 * merge; `owner_approves` = every merge waits for the run owner; `github` = Agern never merges, the team merges on GitHub.
 */
export type MergePolicy = 'automatic' | 'owner_approves' | 'github';
export const MERGE_POLICIES: MergePolicy[] = ['automatic', 'owner_approves', 'github'];

export type ProjectSettings = {
  mergePolicy: MergePolicy;
  /** GitHub logins requested as reviewers (policy `github` only). */
  reviewers: string[];
  /** Team slugs of the organisation requested as reviewers (policy `github` only). */
  teamReviewers: string[];
  /**
   * The branch new runs start from, open their pull request against and merge into (ARCHITECTURE §6 "Base branch");
   * null = the repository's default branch.
   */
  baseBranch: string | null;
};

/** A member repository of a workspace, as the caller sees it. */
export type WorkspaceMember = {
  directory: string;
  repository: string;
  /** The effective base branch; for an inaccessible member the declared one (possibly null). */
  branch: string | null;
  label: string;
  /** Whether the caller's own GitHub token can read the repository. */
  accessible: boolean;
  /** The member is a git submodule of the workspace repository; Agern bumps its pointer after a run merges. */
  submodule: boolean;
};

export type Workspace = {
  /** Where the definition comes from: the workspace file, or the submodules (`.gitmodules`) when there is none. */
  path: '.agern/workspace.yml' | '.gitmodules';
  valid: boolean;
  errors: string[];
  /** Empty when the definition is invalid. */
  members: WorkspaceMember[];
  /** Paths of submodules that are not members (another organization): fetched as context, never a run's target. */
  contextOnly: string[];
  /** Member labels that do not exist in the workspace repository (Agern could not create them); [] when all exist. */
  missingLabels: string[];
};

/** `POST /api/projects/{repo}/workspace/labels`: the member labels created now, and those still missing. */
export type MemberLabelsResult = { created: string[]; missing: string[] };

/** Largest project icon the server accepts (1 MiB); used for the client pre-check and by the mock. */
export const PROJECT_ICON_MAX_BYTES = 1_048_576;

/** The types a project icon may be (four raster types and SVG); the server decides by the bytes, this only filters the file picker. */
export const PROJECT_ICON_TYPES = ['image/png', 'image/jpeg', 'image/webp', 'image/gif', 'image/svg+xml'];

export type IssueKind = 'feature' | 'bug' | 'task';

export type Issue = {
  number: number;
  title: string;
  state: 'open' | 'closed';
  kind: IssueKind;
  labels: { name: string; color: string }[];
  author: string;
  assignees: string[];
  comments: number;
  createdAt: string;
  updatedAt: string;
  htmlUrl: string;
  body?: string;
  activeRunId: string | null;
  /** The release the issue is planned in; null when it is in none. */
  release: { id: string; name: string } | null;
  /** Images selected for this issue in a chat. */
  imageCount: number;
  /** Workspace projects: the members whose label the issue carries; empty otherwise. */
  workspaceMembers?: { directory: string; repository: string }[];
};

/**
 * `planning` until started (`scheduled` while a start time is set); then `building` / `paused`; `ready` when nothing
 * is left to build (waits for the owner's approval); `released` once merged into the default branch, or `cancelled`
 * (docs/ARCHITECTURE.md §6 "Releases"). Unknown states (from a newer server) render as text.
 */
export type ReleaseStatus = 'planning' | 'scheduled' | 'building' | 'paused' | 'ready' | 'released' | 'cancelled';

/** Whether the release pull request merges cleanly into the default branch, as last checked. */
export type ReleaseMergeState = 'unknown' | 'clean' | 'conflict';

/** How approval merges the release (never squashed): `unavailable` when the repository only allows squash merges. */
export type ReleaseMergeMethod = 'rebase' | 'merge' | 'unavailable';
/** How a ready release reaches the default branch: the owner's approval, or Agern by itself once it is green. */
export type ReleaseMode = 'manual' | 'automatic';
/**
 * The checks on the release pull request's head commit. `awaiting`: none yet, but the commit is younger than the settle
 * period (CI may not have registered its checks); `none` (no checks for the whole period) counts as green.
 */
export type ReleaseChecksState = 'unknown' | 'awaiting' | 'none' | 'pending' | 'passed' | 'failed';
export type ReleaseChecks = { state: ReleaseChecksState; detail: string | null; sha: string | null };

/** A scheduled start that did not happen (docs/ARCHITECTURE.md §6 "Scheduled start"). */
export type ReleaseStartFailure = { at: string; owner: Owner; reason: string };

/** Where an issue is in its release's build. All issues of a `planning` release are `waiting`. */
export type ReleaseIssueState = 'waiting' | 'running' | 'merged' | 'held' | 'skipped';

/** An issue as planned in a release: title and kind as they were when it was assigned. */
export type ReleaseIssue = {
  number: number;
  title: string;
  kind: IssueKind;
  state: ReleaseIssueState;
  /** The issue's latest release run. */
  runId: string | null;
  /** Why it is held or skipped, or that it will be built again. */
  note: string | null;
  /** The pull request of its run into the release branch. */
  prNumber: number | null;
  prUrl: string | null;
  /** A waiting issue retried with `continue`, `step` or `update` from `runId`; null when it is built from scratch. */
  retryMode?: 'continue' | 'step' | 'update' | null;
  /** The member repository the issue's run targets; null without a run or for the project (workspace) repository. */
  targetRepo: string | null;
  /** That member's directory in the workspace; null like `targetRepo`. */
  targetDir: string | null;
  /** While the issue is `running` and its run waits for the network: that run's wait. */
  waiting?: RunWait | null;
  /** From its run: whether "Update and merge" can start now, the update rounds used and the limit. */
  updateAvailable?: boolean;
  updateRounds?: number;
  maxUpdateRounds?: number;
  /** The one-line summary of its run's latest update round, if it had one. */
  updateSummary?: string | null;
};

/**
 * A run that lost the network (GitHub or the Claude API) and waits before it tries again (docs/ARCHITECTURE.md §6
 * "Transient failures"). `attempt`: failed attempts so far (≥ 1); `detail`: what failed. Instants are ISO-8601.
 */
export type RunWait = {
  reason: 'network';
  since: string;
  nextAttemptAt: string;
  attempt: number;
  detail: string;
};

/** The state of one repository's release pull request in a multi-repository release. */
export type ReleaseRepoState = 'none' | 'open' | 'merged';

/**
 * One repository of a release that spans several (a workspace project): its `release/<name>` branch and release pull
 * request. `accessible` false: the viewer cannot read the repository, so `prNumber`, `prUrl`, `checks` and
 * `mergeCommit` are null.
 */
export type ReleaseRepo = {
  repo: string;
  /** The member's directory in the workspace; null for the project (workspace) repository. */
  directory: string | null;
  submodule: boolean;
  baseBranch: string | null;
  /** `release/<name>`. */
  branch: string;
  state: ReleaseRepoState;
  prNumber: number | null;
  prUrl: string | null;
  mergeState: ReleaseMergeState;
  mergeMethod: ReleaseMergeMethod | null;
  checks: ReleaseChecks | null;
  mergedAt: string | null;
  mergeCommit: string | null;
  /** The merged issues built in this repository. */
  issues: number[];
  accessible: boolean;
};

export type ReleasePauseReason = 'manual' | 'usage_limit' | 'credentials' | 'network';

/** A named plan of issues for one project (docs/ARCHITECTURE.md §6 "Releases"). */
export type Release = {
  id: string;
  repo: string;
  name: string;
  description: string;
  status: ReleaseStatus;
  createdBy: Owner;
  createdAt: string;
  /** Strictly increases on every change. */
  updatedAt: string;
  /** In build order. */
  issues: ReleaseIssue[];
  /** Who scheduled or started the release (its runs use this user's credentials); null while `planning`. */
  owner: Owner | null;
  /** `release/<name>` once started. */
  branch: string | null;
  startedAt: string | null;
  endedAt: string | null;
  /** Set while `paused`. */
  pause: {
    reason: ReleasePauseReason;
    note: string | null;
    resumeAt: string | null;
    /** Reason `network`: when the current streak of failed starts began, and how many failed. */
    since?: string | null;
    attempt?: number | null;
  } | null;
  /** ISO instant of the scheduled start; set exactly while `scheduled`. */
  scheduledAt: string | null;
  /** The release this one waits behind: only while `scheduled` and its time has come. */
  waitingFor: { id: string; name: string } | null;
  /** The last scheduled start that did not happen; kept until the release is scheduled again. */
  startFailure: ReleaseStartFailure | null;
  /** The release pull request (`release/<name>` → `targetBranch`), opened when the release is ready and complete. */
  prNumber: number | null;
  prUrl: string | null;
  /** The default branch the release pull request merges into. */
  targetBranch: string | null;
  mergeState: ReleaseMergeState;
  /** How approval will merge (while `ready`) or how it merged (`released`); null until known. */
  mergeMethod: ReleaseMergeMethod | null;
  /** The last problem to show on the release page. */
  notice: string | null;
  readyAt: string | null;
  releasedAt: string | null;
  /** Who approved it (an automatic release: its owner); null when its pull request was merged on GitHub outside Agern. */
  releasedBy: Owner | null;
  /** The default branch's commit after the release merge. */
  mergeCommit: string | null;
  /** `manual` (default): waits for the owner's approval; `automatic`: merged by Agern once ready, clean and green. */
  mode: ReleaseMode;
  /** True when Agern merged it by itself (automatic mode). */
  releasedAutomatically: boolean;
  /** The checks on the release pull request, as last read; null until it has one. */
  checks: ReleaseChecks | null;
  /** The project's current merge policy: `github` refuses Agern's approval; anything but `automatic` refuses automatic mode. */
  mergePolicy?: MergePolicy;
  /**
   * Empty unless the release spans several repositories: then member repositories in merge order (untouched last),
   * followed by the project repository (`directory` null). The fields above describe the project repository's PR.
   */
  repositories: ReleaseRepo[];
  /** Commits on `targetBranch` that are not on the release branch, as last compared; null = not known. */
  behindBy?: number | null;
  /** The last evaluation found migrations of the release branch that collide with `targetBranch`'s. */
  migrationConflict?: boolean;
  /** "Bring up to date" is offered: ready, one repository, and a conflict, a branch behind or a migration conflict. */
  syncNeeded?: boolean;
  /** The release's latest run that brought it up to date, or null. */
  sync?: ReleaseSync | null;
};

/**
 * `running` (queued, building, reviewing), `done` (merged), `failed` (failed or cancelled, nothing pushed) or
 * `unreviewed` (failed or cancelled after its merge was pushed).
 */
export type ReleaseSyncState = 'running' | 'done' | 'failed' | 'unreviewed';

export type ReleaseSync = {
  runId: string;
  state: ReleaseSyncState;
  by: Owner;
  startedAt: string;
  endedAt?: string | null;
  pushedSha?: string | null;
  error?: string | null;
};

/** Building or paused: at most one per project. */
export const isReleaseActive = (r: Release) => r.status === 'building' || r.status === 'paused';

/** Released or cancelled: kept as history. */
export const isReleaseFinished = (r: Release) => r.status === 'released' || r.status === 'cancelled';

/** Can take issues (owner-only once started): not released or cancelled. A `ready` one builds again for them. */
export const isReleaseOpen = (r: Release) => !isReleaseFinished(r);

/**
 * A `ready` release: `complete` (something merged, nothing held — only this can be approved), `incomplete` (held
 * issues) or `empty` (nothing merged, nothing held).
 */
export function releaseReadiness(r: Release): 'complete' | 'incomplete' | 'empty' {
  if (r.issues.some((i) => i.state === 'held')) return 'incomplete';
  return r.issues.some((i) => i.state === 'merged') ? 'complete' : 'empty';
}

/** The seven pipeline roles. */
export type StageRole = 'spec-writer' | 'builder' | 'security' | 'ux' | 'ui-design' | 'code' | 'approver';
/** `advisor`: the agent the owner talks to while a run needs a human (docs/ARCHITECTURE.md §6 "Conversation"). */
export type SessionKind = 'chat' | 'advisor' | StageRole;

/** Roles whose running agent accepts messages from the run's owner (docs/ARCHITECTURE.md §6). */
export const STEERABLE_ROLES: StageRole[] = ['spec-writer', 'builder'];

/** Answer to a message for a running pipeline agent. Always `queued`; a `system` event with `messageId` confirms pickup. */
export type AgentMessage = {
  status: 'queued';
  messageId: string;
  sessionId: string;
  runId: string | null;
  role: StageRole;
  note: string;
};
export type SessionStatus = 'starting' | 'running' | 'idle' | 'done' | 'failed' | 'stopped';

export type Owner = { username: string; displayName: string };

/** A personal MCP token (docs/ARCHITECTURE.md §7 "Personal tokens"); never carries the secret. */
export type McpToken = {
  id: string;
  name: string;
  repo: string;
  owner: Owner;
  createdAt: string;
  lastUsedAt: string | null;
  /** null: never expires. */
  expiresAt: string | null;
  expired: boolean;
};

/** The answer to creating a token: the only place the secret ever appears. */
export type CreatedMcpToken = { token: McpToken; secret: string; mcpUrl: string; serverName: string };

export type AgentSession = {
  id: string;
  repo: string;
  kind: SessionKind;
  model: string;
  status: SessionStatus;
  owner: Owner;
  title: string | null;
  runId: string | null;
  parentSessionId: string | null;
  issueNumber: number | null;
  createdAt: string;
  updatedAt: string;
  endedAt: string | null;
  costUsd: number | null;
  numTurns: number | null;
  lastActivity: string | null;
  /** True while an agent process exists. A chat is `idle` both with a process (waiting for input) and without. */
  live: boolean;
};

export type StageStatus = 'pending' | 'running' | 'done' | 'failed' | 'skipped';
export type Verdict = 'PASS' | 'CHANGES' | 'APPROVE' | 'ESCALATE';
export type Finding = { severity: string; file: string | null; detail: string };

export type Stage = {
  id: string;
  role: StageRole;
  /** 0 = the initial build; fix rounds are 1..Run.maxRounds. */
  round: number;
  /** The model this stage runs (or ran) with. */
  model: string;
  status: StageStatus;
  verdict: Verdict | null;
  summary: string | null;
  findings: Finding[];
  sessionId: string | null;
  startedAt: string | null;
  endedAt: string | null;
  /** Screenshots the stage's reviewer attached (only `ux` and `ui-design`), in attach order; empty for every other stage. */
  screenshots: Screenshot[];
  /** Images the stage's agent generated (only the spec-writer and the builder can), oldest first. */
  images: GeneratedImage[];
  /**
   * A warning the server recorded on the stage without failing it, e.g. "UI change reviewed without screenshots: …"
   * on the ux and ui-design stages of a round (docs/ARCHITECTURE.md §6 "Screenshots"). Null or absent: none.
   */
  warning?: string | null;
};

/** A warning on a run: the stage warnings of one round, by the roles that carry it. Never fails the run. */
export type RunWarning = { round: number; roles: StageRole[]; text: string };

/** Where a chat image is selected; `locked` while a run that took it is not failed or cancelled. */
export type ImageSelection = { issueNumber: number; name: string; usedByRunId: string | null; locked: boolean };

/** An image an agent generated; the bytes are at `api.imageUrl(id)`. Prompt and purpose are untrusted text. */
export type GeneratedImage = {
  id: string;
  repo: string;
  origin: 'chat' | 'run';
  sessionId: string;
  owner: { username: string; displayName: string };
  provider: string;
  model: string;
  prompt: string;
  purpose: string;
  /** image/png, image/jpeg or image/webp (sniffed by the server). */
  mediaType: string;
  size: number;
  width: number;
  height: number;
  transparent: boolean;
  createdAt: string;
  selection: ImageSelection | null;
};

export type ImageProviderInfo = {
  id: string;
  label: string;
  /** Suggestions; any model name may be saved. */
  models: string[];
  defaultModel: string;
  supportsTransparency: boolean;
  keyPlaceholder: string;
  /** `device_login`: connected by signing in (Codex), not with an API key. */
  auth: 'api_key' | 'device_login';
  /** False when the provider cannot be used on this server (e.g. the CLI is not installed). */
  available: boolean;
  unavailableReason: string | null;
};

/** The caller's Codex device login; the URL and code are present only while `pending`. */
export type CodexLogin = {
  status: 'none' | 'pending' | 'connected' | 'failed' | 'expired';
  verificationUrl?: string | null;
  userCode?: string | null;
  expiresAt?: string | null;
  error?: string | null;
};

/** Payload of an `images` event: one successful `factory_generate_image` call. */
export type ImagesPayload = {
  requestId: string;
  provider: string;
  providerLabel: string;
  model: string;
  prompt: string;
  purpose: string;
  issueNumber?: number;
  images: { id: string; mediaType: string; width: number; height: number; size: number }[];
};

/** A screenshot a UX or UI design reviewer attached to its stage; the image is at `api.screenshotUrl(runId, id)`. */
export type Screenshot = {
  id: string;
  /** One line saying what the screenshot shows (untrusted text: render as plain text). */
  caption: string;
  /** image/png, image/jpeg, image/gif or image/webp (sniffed by the server). */
  mediaType: string;
  size: number;
  width: number;
  height: number;
  createdAt: string;
};

export type RunStatus =
  | 'queued'
  | 'spec'
  | 'building'
  | 'reviewing'
  | 'approving'
  /** Approved; merges by itself once the checks on the pull request's head commit pass (docs/ARCHITECTURE.md §6). */
  | 'waiting_for_checks'
  | 'merged'
  | 'needs_human'
  | 'failed'
  | 'cancelled'
  /** Passed every reviewer under policy `github`: the pull request waits on GitHub (terminal; followed until merged or closed). */
  | 'ready';

export type Run = {
  id: string;
  repo: string;
  issueNumber: number;
  issueTitle: string;
  issueKind: IssueKind;
  status: RunStatus;
  /** Fix rounds so far: 0 while in the initial build/review, 1..maxRounds after reviewers asked for changes. */
  round: number;
  maxRounds: number;
  branch: string | null;
  prNumber: number | null;
  prUrl: string | null;
  owner: Owner;
  startedBySessionId: string | null;
  error: string | null;
  createdAt: string;
  updatedAt: string;
  endedAt: string | null;
  stages: Stage[];
  /** The run's sandbox decision, fixed when it starts; null before that and for runs from before sandboxes. */
  sandbox: RunSandbox | null;
  /** The release this run builds an issue of; null for a single run. */
  releaseId: string | null;
  /**
   * The branch the run branches from and merges into: the project's base branch, `release/<name>` for a release run, or
   * the member's base. Always set on runs started since base branches exist; null on older single runs (default branch).
   */
  baseBranch: string | null;
  /** The owner's conversation with the advisor while the run needs a human. Always present. */
  conversation: RunConversation;
  /** The failed run this one continues from its commits (docs/ARCHITECTURE.md §6 "Retrying a run"). */
  continuesRunId?: string | null;
  /** The newest run that continues this one. */
  continuedByRunId?: string | null;
  /** Why a failed run stopped. */
  failureKind?: FailureKind | null;
  /** The mechanical step after the builder that failed. */
  failedStep?: FailedStep | null;
  /** Warnings that never fail the run, per round, oldest round first (from the stages' `warning`). */
  warnings?: RunWarning[];
  /** A workspace run: the member repository and directory it changes (its `baseBranch` is the member's); null otherwise. */
  targetRepo?: string | null;
  targetDirectory?: string | null;
  /** The project's merge policy when the run started; never changes. */
  mergePolicy?: MergePolicy;
  /** The pull request was merged on GitHub, outside Agern. */
  mergedOnGitHub?: boolean;
  /** Who merged it on GitHub, when GitHub reports it. */
  mergedBy?: string | null;
  /** The checks on the pull request's head commit as last evaluated before a merge; null until the first evaluation. */
  checks?: RunChecks | null;
  /** Set while the run waits for the network before trying again; it keeps its status meanwhile. */
  waiting?: RunWait | null;
  /** `issue`, or `sync` for a run that brings a release up to date (it belongs to no issue: `issueNumber` is 0). */
  kind?: RunKind;
  /** A sync run: the commit it last pushed to its release branch. */
  syncPushedSha?: string | null;
  /** The run's sandbox VM decision and its human preview (docs/SANDBOX-VMS.md); null for sync runs and older runs. */
  sandboxVm?: RunSandboxVm | null;
  /** Update rounds ("Update and merge") started on the run, and its limit. */
  updateRounds?: number;
  maxUpdateRounds?: number;
  /** True when the owner can start an update round now. */
  updateAvailable?: boolean;
  /** The run's update rounds, oldest first. */
  updates?: RunUpdate[];
  /** True when the owner can bring the branch up to date with its target now ("Update from base branch"). */
  updateOnDemand?: boolean;
  /** Other active runs of the project when this run started (a warning). Issue titles and paths are untrusted text. */
  overlaps?: RunOverlap[];
  /** The project files the run got, fixed when it starts; null for older runs. */
  projectFiles?: RunProjectFiles | null;
};

/** Another run that was active in the same project when a run started. */
export type RunOverlap = {
  runId: string;
  issueNumber: number;
  issueTitle: string;
  owner: string;
  /** Its status when this run started. */
  status: RunStatus;
  /** This issue names that run's issue, or that run's spec names this issue. */
  references: boolean;
  /** Paths both specs name (filled in after this run's spec). */
  files: string[];
};

/** A pull request merged into the target that came in with an update round. Title untrusted; `url` only https. */
export type RunUpdatePull = { number: number; title: string; url?: string | null };

/**
 * One update round: Agern merged `target` into the run's branch, the builder resolved what conflicted, and the reviewers
 * and the approver ran again. Subjects, paths, problems and errors come from the repository: render them as text only.
 */
export type RunUpdate = {
  number: number;
  round: number;
  maxRounds: number;
  /** `release`: started automatically by a building release; `base_moved`: started by Agern because the target moved. */
  trigger: 'owner' | 'release' | 'base_moved';
  target: string;
  reason: string | null;
  status: 'running' | 'resolved' | 'failed' | 'cancelled';
  mergeStatus: 'up_to_date' | 'merged' | 'conflict' | null;
  targetSha: string | null;
  incomingCount: number;
  /** Subjects of the first-parent commits that came in, newest first. */
  incoming: string[];
  conflicts: string[];
  migrations: string[];
  /** The builder's commits, `<short sha> <subject>`, oldest first. */
  commits: string[];
  error: string | null;
  startedAt: string;
  endedAt: string | null;
  /** One line, composed by the server. */
  summary: string;
  /** The pull requests merged into the target that came in with the merge (at most 10). */
  pulls?: RunUpdatePull[];
};


export type RunKind = 'issue' | 'sync';

/** A run that brings a release up to date (docs/ARCHITECTURE.md §6 "Bringing a release up to date"). */
export const isSyncRun = (r: Pick<Run, 'kind'>) => r.kind === 'sync';

/** How lists name a run: `#<issue>`, or `sync` for a run that belongs to no issue (never `#0`). */
export const runRef = (r: { kind?: RunKind; issueNumber: number }) => (r.kind === 'sync' || r.issueNumber === 0 ? 'sync' : `#${r.issueNumber}`);

/** One check that has not passed. Name and URL come from GitHub (untrusted); `url` is only ever an `https://` URL. */
export type RunCheckItem = { name: string; state: 'failed' | 'pending'; conclusion: string | null; url: string | null };

/** The checks a run's merge was decided on (docs/ARCHITECTURE.md §6 "Checks before a merge"). */
export type RunChecks = {
  state: 'awaiting' | 'pending' | 'passed' | 'failed' | 'none' | 'unknown';
  /** The head commit they were read for. */
  sha: string;
  detail: string;
  /** At most 50. */
  items: RunCheckItem[];
  /** Set only while `waiting_for_checks`. */
  waitingSince: string | null;
  deadline: string | null;
  /** Set when the owner merged anyway. */
  overriddenBy: string | null;
  overriddenAt: string | null;
};

/** Checks states that block a merge (and that the owner may merge anyway). */
export const BLOCKING_CHECK_STATES: RunChecks['state'][] = ['failed', 'pending', 'awaiting', 'unknown'];

export type FailureKind =
  | 'time_limit' | 'usage_limit' | 'restart' | 'api_unreachable' | 'credentials' | 'blocked' | 'step' | 'other'
  | 'submodule_commit_missing' | 'checks' | 'migration_conflict' | 'merge_conflict'
  /** The builder committed a project file (they are never committed or sent to GitHub). */
  | 'project_file_committed';
export type FailedStep = 'push' | 'pull_request' | 'merge';

/**
 * `update`: merge the target into the branch and let the builder resolve the conflict, then review and merge ("Update and
 * merge"); `step`: retry the failed step on the same run; `continue`: a new run from its commits; `start_over`: from scratch.
 */
export type RetryMode = 'update' | 'step' | 'continue' | 'start_over';
export type RetryOption = { mode: RetryMode; available: boolean; detail: string };
/** How a run (or a held release issue) can be retried, and what is recommended, with a one-line reason. */
export type RetryOptions = {
  runId: string;
  recommended: RetryMode;
  reason: string;
  options: RetryOption[];
  step: FailedStep | null;
  commits: number | null;
  releaseId: string | null;
};

/** Follow-up review of changes made in the conversation: `required` = not reviewed yet, `running` = reviewers at work. */
export type ConversationReview = 'none' | 'required' | 'running';
export type RunConversation = {
  /** The advisor session, once the owner has sent a message. */
  sessionId: string | null;
  review: ConversationReview;
  /** First follow-up round; stage rounds >= it are follow-up rounds. */
  followUpFrom: number | null;
};

/**
 * Whether a run's builder got a sandbox (docs/ARCHITECTURE.md §5 "Sandboxes"). Only `approved` does:
 * `disabled` = FACTORY_SANDBOX is off; `none` = no `.agern/sandbox.yml`; `invalid`; `unapproved` = not approved, or changed since.
 */
export type RunSandboxState = 'disabled' | 'none' | 'invalid' | 'unapproved' | 'approved';
export type RunSandbox = {
  state: RunSandboxState;
  reason: string;
  sha256: string | null;
  image: string | null;
  /** The definition's platforms besides the default image (empty when none, and for older runs). */
  platforms: string[];
  /** Where the builder ran commands: `default` and platform names, in first-use order (only when there are platforms). */
  platformsUsed: string[];
  /** The ux and ui-design reviewers ran the app in a read-only review sandbox at least once (absent for older runs). */
  reviewUsed?: boolean;
  /** Project files the definition requires (`files:`); absent for older runs. */
  files?: string[];
};

/** A sidecar's readiness check: run inside the service until it exits 0, for at most `timeoutSeconds`. */
export type SandboxReady = { command: string; timeoutSeconds: number };

export type SandboxService = { name: string; image: string; env: Record<string, string>; ready: SandboxReady | null };

/** Another image the builder can run commands in. `setupInherited`: it uses the top-level `setup`. */
export type SandboxPlatform = { name: string; image: string; setup: string | null; setupInherited: boolean };

/** `user:` as written (`name` or `uid`) and the uid commands run as inside the container. */
export type SandboxUser = { name: string | null; uid: number };

/** A validated `.agern/sandbox.yml`. `limits`: effective per-container limits (the request, capped by the server). */
export type SandboxDefinition = {
  image: string;
  setup: string | null;
  env: Record<string, string>;
  services: SandboxService[];
  caches: string[];
  limits: { cpus: number; memory: string; requestedCpus: number | null; requestedMemory: string | null; capped: boolean };
  platforms: SandboxPlatform[];
  /** Null: commands run as root inside the container. */
  user: SandboxUser | null;
  /** Project files the sandbox needs (`files:`), by name. */
  files?: string[];
};

export type SandboxApproval = { sha256: string; content: string; approvedBy: Owner; approvedAt: string };

/** `GET /api/projects/{repo}/sandbox`: the definition on the default branch and the project's approval. */
export type SandboxStatus = {
  repo: string;
  /** FACTORY_SANDBOX is not off. Definitions can be approved either way. */
  enabled: boolean;
  path: string;
  /** The branch the file is read from (runs start from it). */
  branch: string;
  /** `pending`: valid, but this exact content is not approved. */
  state: 'none' | 'invalid' | 'pending' | 'approved';
  sha256: string | null;
  content: string | null;
  errors: string[];
  definition: SandboxDefinition | null;
  /** The project's approval, possibly of other content (compare `sha256`; its `content` allows a diff). */
  approval: SandboxApproval | null;
  /** Who can approve (the admins), so a non-admin knows whom to ask. */
  approvers: Owner[];
  /** Names in the definition's `files` that are not uploaded to the project's files. */
  missingFiles?: string[];
};

// ---------------------------------------------------------------- project files

/**
 * A file of the project's file store: uploaded once per project, handed read-only to every chat and run of the project,
 * never committed or sent to GitHub. `sha256` is computed by the server; a replacement keeps the id and bumps `version`.
 */
export type ProjectFile = {
  id: string;
  name: string;
  size: number;
  sha256: string;
  version: number;
  note: string | null;
  /** Whether people who can see the project may download it through the browser (default off). */
  downloadable: boolean;
  uploadedBy: Owner | null;
  uploadedAt: string;
};

/** An open (resumable) upload. `received`: bytes the server has, the offset of the next chunk. */
export type ProjectFileUpload = {
  id: string;
  name: string;
  size: number;
  received: number;
  /** The expected SHA-256 given when it was created. */
  sha256: string | null;
  note: string | null;
  createdBy: Owner | null;
  createdAt: string;
  touchedAt: string;
  expiresAt: string;
  /** The id of the file it replaces (same name). */
  replaces: string | null;
};

export type ProjectFilesUsage = { files: number; projectBytes: number; totalBytes: number };
export type ProjectFilesLimits = { maxFileBytes: number; maxProjectBytes: number; maxTotalBytes: number; maxFiles: number };

/** `GET /api/projects/{repo}/files`. `canManage`: an admin or one of `managers`. */
export type ProjectFiles = {
  repo: string;
  canManage: boolean;
  files: ProjectFile[];
  managers: Owner[];
  usage: ProjectFilesUsage;
  limits: ProjectFilesLimits;
  /** Open uploads (managers only). */
  uploads: ProjectFileUpload[];
};

/** A project file as a run got it. `required`: named by the sandbox definition; `previousSha256`: it changed since the previous run. */
export type RunProjectFile = { name: string; size: number; sha256: string; version: number; required: boolean; previousSha256: string | null };
export type RunProjectFiles = { repo: string; files: RunProjectFile[] };

export type AdminProjectFilesRepo = { repo: string; files: number; bytes: number };
export type AdminProjectFiles = { totalBytes: number; limits: ProjectFilesLimits; repos: AdminProjectFilesRepo[] };

/** A target of a valid `.agern/deploy.yml` (docs/DEPLOY.md). */
export type DeployTarget = {
  name: string;
  host: string;
  port: number;
  user: string;
  /** e.g. `ssh-ed25519`. */
  hostKeyType: string;
  /** OpenSSH style, `SHA256:…`. */
  hostKeyFingerprint: string;
  description: string | null;
};

export type DeployApproval = { sha256: string; content: string; approvedBy: Owner; approvedAt: string };

/** The project's deploy key pair: only the public half ever leaves the server. */
export type DeployKey = { publicKey: string; fingerprint: string; createdBy: Owner; createdAt: string };

/** `GET /api/projects/{repo}/deploy`: the deploy targets on the default branch, their approval and the project's key. */
export type DeployStatus = {
  repo: string;
  /** FACTORY_DEPLOY is not off. Files can be approved and keys generated either way. */
  enabled: boolean;
  /** `fake`: commands only pretend to run (development). */
  mode: 'ssh' | 'fake' | 'off';
  path: string;
  branch: string;
  /** `pending`: valid, but this exact content is not approved. */
  state: 'none' | 'invalid' | 'pending' | 'approved';
  sha256: string | null;
  content: string | null;
  errors: string[];
  /** The targets of a valid file (pending or approved); empty otherwise. */
  targets: DeployTarget[];
  approval: DeployApproval | null;
  approvers: Owner[];
  key: DeployKey | null;
};

/** `POST /api/projects/{repo}/deploy/targets/{name}/check`: connects, verifies the host key, authenticates, disconnects. */
export type DeployCheck = {
  target: string;
  ok: boolean;
  /** Where it stopped: `ok`, or the step that failed. */
  stage: 'connect' | 'host_key' | 'auth' | 'ok';
  message: string;
  durationMs: number;
};

/**
 * A run's sandbox VM decision (docs/SANDBOX-VMS.md), fixed when it starts, plus what happened with the VM since.
 * `disabled` = FACTORY_SANDBOX_VM is off; `none` = no `.agern/sandbox-vm.yml`; `invalid`; `unapproved`; `approved`.
 */
export type RunSandboxVmState = 'disabled' | 'none' | 'invalid' | 'unapproved' | 'approved';
export type RunSandboxVm = {
  state: RunSandboxVmState;
  reason: string;
  sha256: string | null;
  /** The VM's label, host, SSH user and URL (`approved` only). */
  name: string | null;
  host: string | null;
  user: string | null;
  url: string | null;
  /** The builder deployed to the VM at least once. */
  used: boolean;
  /** How long a human preview holds the VM (FACTORY_SANDBOX_VM_PREVIEW_MINUTES). */
  previewMinutes: number;
  /** The latest human preview of the run; null when nobody started one. */
  preview: SandboxVmPreview | null;
};

export type SandboxVmPreviewState = 'deploying' | 'ready' | 'failed' | 'released';

/** A human preview of a run on its sandbox VM (`POST /api/runs/{id}/sandbox-vm/preview`). */
export type SandboxVmPreview = {
  state: SandboxVmPreviewState;
  /** The commit deployed (or being deployed). */
  sha: string | null;
  url: string | null;
  startedBy: Owner | null;
  startedAt: string | null;
  /** `ready`: until when the preview holds the VM. */
  expiresAt: string | null;
  /** `released` / `failed`: when the preview ended. */
  endedAt: string | null;
  /** `released` because the preview time ran out. */
  expired: boolean;
  /** `failed`: `upload`, `checkout`, `setup`, `ready` or `connect`. */
  failedStep: string | null;
  error: string | null;
  /** `failed`: the tail of the failed step's output (at most 20,000 characters). */
  output: string | null;
};

/** The VM of a valid `.agern/sandbox-vm.yml`. */
export type SandboxVm = {
  name: string;
  host: string;
  port: number;
  user: string;
  hostKeyType: string;
  /** OpenSSH style, `SHA256:…`. */
  hostKeyFingerprint: string;
  description: string | null;
  url: string | null;
  checkout: string;
  setup: string | null;
  ready: SandboxReady | null;
  reset: string | null;
};

/** Who holds a sandbox VM now. `runId`, `repo` and `issueNumber` are withheld (`visible` false) from viewers who cannot see the holder's project. */
export type SandboxVmLease = {
  /** `build` (a run's builder) or `preview` (a human). */
  kind: 'build' | 'preview';
  state: 'held' | 'resetting';
  since: string;
  expiresAt: string | null;
  visible: boolean;
  runId: string | null;
  repo: string | null;
  issueNumber: number | null;
};

/** `GET /api/projects/{repo}/sandbox-vm`: the definition on the default branch, its approval, the key and the lease. */
export type SandboxVmStatus = {
  repo: string;
  /** FACTORY_SANDBOX_VM is not off. Definitions can be approved either way. */
  enabled: boolean;
  /** `fake`: commands only pretend to run (development). */
  mode: 'ssh' | 'fake' | 'off';
  path: string;
  branch: string;
  /** `pending`: valid, but this exact content is not approved. */
  state: 'none' | 'invalid' | 'pending' | 'approved';
  sha256: string | null;
  content: string | null;
  errors: string[];
  vm: SandboxVm | null;
  approval: SandboxApproval | null;
  approvers: Owner[];
  /** The project's deploy key (shared with deploy targets); null when it has none. */
  key: DeployKey | null;
  /** The VM's lease (keyed by host and port, so possibly held by another project's run); null when free. */
  lease: SandboxVmLease | null;
  previewMinutes: number;
};

/** `POST /api/projects/{repo}/sandbox-vm/check`: how far a connection got, and the readiness check. */
export type SandboxVmCheck = {
  ok: boolean;
  /** `ok`, or the step that failed. */
  stage: 'connect' | 'host_key' | 'auth' | 'ready' | 'ok';
  message: string;
  durationMs: number;
  /** Stage `ready`: the readiness command's exit code and output tail. */
  exitCode: number | null;
  output: string | null;
};

/** A sandbox that exists right now (`GET /api/admin/sandboxes`). */
export type RunningSandbox = {
  runId: string;
  repo: string;
  /** False when the admin cannot see the repository on GitHub: `issueTitle` is withheld and the UI does not link. */
  visible: boolean;
  issueNumber: number;
  issueTitle: string | null;
  runStatus: RunStatus;
  owner: Owner;
  image: string;
  /** Sidecar service names. */
  services: string[];
  /** The approved definition's hash. */
  sha256: string;
  createdAt: string;
  /** Per-container limits it runs with. */
  cpus: number;
  memory: string;
  /** The containers that run commands right now (`default` = the top-level image, or a platform); one slot each. */
  containers: SandboxContainer[];
  /** The definition's platforms (whether or not they exist right now). */
  platforms: string[];
  /** Slots of the server's cap this run holds (one per container). */
  slots: number;
  /** What all its containers may use together: `memory` × (containers + services). */
  memoryTotal: string;
};

/** `build`: the default image or a platform of the build sandbox; `review`: the review sandbox's container (platform `default`). */
export type SandboxContainer = { platform: string; image: string; createdAt: string; kind: 'build' | 'review' };

/** Admin overview of the sandbox runtime: its limits and the sandboxes that exist (oldest first). */
export type SandboxOverview = {
  enabled: boolean;
  /** `fake` is a development mode that only pretends to run commands. */
  runtime: 'off' | 'podman' | 'fake';
  /** Command containers that exist (slots in use), over all runs. */
  inUse: number;
  limits: { maxCpus: number; maxMemory: string; maxConcurrent: number; execTimeoutMinutes: number; registries: string[]; maxPlatforms: number };
  sandboxes: RunningSandbox[];
};

/** A project whose valid `.agern/sandbox.yml` is not approved in this exact version. */
export type PendingSandbox = {
  repo: string;
  sha256: string;
  image: string;
  /** The approved (older) version's hash, or null when none was ever approved. */
  approvedSha256: string | null;
};

/** On `system` events of sandbox commands: the full log (up to 1,000,000 characters, the tail). */
export type SandboxLog = {
  kind: 'setup' | 'exec';
  command: string;
  exitCode: number;
  timedOut: boolean;
  durationMs: number;
  output: string;
  outputTruncated: boolean;
  /** Where it ran: null when the definition has no platforms, else `default` or the platform's name. */
  platform?: string | null;
  /** Ran in the read-only review sandbox of the ux and ui-design reviewers. */
  review?: boolean;
};

/** On `system` events of deploy commands and copies in a chat: what ran on which target, and its full output. */
export type DeployLog = {
  kind: 'exec' | 'copy';
  target: string;
  host: string;
  port: number;
  user: string;
  command?: string;
  path?: string;
  dest?: string;
  bytes?: number;
  /** null: no exit status (a copy, a connection failure, a signal). */
  exitCode: number | null;
  timedOut: boolean;
  durationMs: number;
  output: string;
  outputTruncated: boolean;
  /** Why the operation failed before or instead of an exit status (connect, host key, authentication, transfer). */
  error: string | null;
};

/** On `system` events of a run's builder: one step of a sandbox VM deploy, a command, or a reset (docs/SANDBOX-VMS.md). */
export type SandboxVmLog = {
  kind: 'deploy' | 'exec' | 'reset';
  /** The deploy step; null for `exec` and `reset`. */
  step: 'upload' | 'checkout' | 'setup' | 'ready' | null;
  vm: string;
  host: string;
  port: number;
  user: string;
  command: string | null;
  sha: string | null;
  /** null: no exit status (an upload, a connection failure, a signal). */
  exitCode: number | null;
  timedOut: boolean;
  durationMs: number;
  output: string;
  outputTruncated: boolean;
  error: string | null;
};

export type EventType = 'user' | 'claude' | 'system' | 'status' | 'images' | 'checkout';

/**
 * Payload of a `checkout` event: the state of the owner's main checkout after the server synced it with
 * `origin/<defaultBranch>` (docs/ARCHITECTURE.md §8), the branch it follows: the project's base branch. `branch` null =
 * detached HEAD.
 */
export type CheckoutInfo = {
  branch: string | null;
  defaultBranch: string;
  commit: string;
  behind: number;
  ahead: number;
  status: 'current' | 'updated' | 'behind';
  reason: 'other_branch' | 'local_changes' | 'local_commits' | 'update_failed' | null;
  updatedFrom: string | null;
  /** The branch Agern switched the checkout from to the project's base branch (`defaultBranch`); absent or null otherwise. */
  switchedFrom?: string | null;
};

export type FactoryEvent = {
  seq: number;
  sessionId: string;
  ts: string;
  type: EventType;
  payload: unknown;
};

/** A file or image attached to a chat message (docs/ARCHITECTURE.md §8 "Attachments"). */
export type Attachment = {
  id: string;
  name: string;
  /** Decided by the server from the bytes, not from the browser's guess. */
  mediaType: string;
  size: number;
  /** `image`: png/jpeg/gif/webp, shown inline. `file`: anything else, download only. */
  kind: 'image' | 'file';
  width: number | null;
  height: number | null;
  /** How the model sees it: the original image, a downscaled copy, or not inline at all (file only). */
  inline: 'original' | 'downscaled' | 'none';
  /** Why an image was downscaled or is file only. */
  note: string | null;
};

export type ClientConfig = {
  attachments: { maxFileBytes: number; maxMessageBytes: number; maxCount: number; imageTypes: string[] };
  images: {
    providers: ImageProviderInfo[];
    limits: { maxPerRequest: number; maxPerTurn: number; maxPerStage: number; maxEdge: number };
  };
};

export type UserPayload = { text: string; username: string; attachments?: Attachment[] };
export type SystemPayload = { level: 'info' | 'warn' | 'error'; text: string; messageId?: string; sandbox?: SandboxLog; deploy?: DeployLog; sandboxVm?: SandboxVmLog };
export type StatusPayload = { status: SessionStatus };

export type Settings = {
  agentsMayInstallSoftware: boolean;
  chatModel: string;
  pipelineModels: Record<StageRole, string>;
  maxFixRounds: number;
};

/** Where an effective value comes from: the user's own choice, or the admin default. */
export type PreferenceSource = 'user' | 'default';

/** A user's own choices; null / missing = use the admin default (docs/ARCHITECTURE.md §6 "Whose models"). */
export type PreferenceOverrides = {
  chatModel: string | null;
  pipelineModels: Partial<Record<StageRole, string>>;
  maxFixRounds: number | null;
};

export type Preferences = {
  overrides: PreferenceOverrides;
  effective: {
    chatModel: { value: string; source: PreferenceSource };
    pipelineModels: Record<StageRole, { value: string; source: PreferenceSource }>;
    /** `max`: the admin default, also the highest allowed. `clamped`: the user's choice is above it and capped. */
    maxFixRounds: { value: number; source: PreferenceSource; max: number; clamped: boolean };
  };
  /** Model aliases to offer; any full model id is accepted too. */
  suggestions: string[];
};

/** One Claude limit window of the caller, as last reported while one of their agents ran (docs/ARCHITECTURE.md §5 "Claude usage"). */
export type UsageWindow = {
  /** `five_hour`, `seven_day`, `seven_day_opus`, …; unknown types arrive as they are. */
  type: string;
  /** Fraction used (1 = 100 %), may exceed 1; null when unknown. */
  utilization: number | null;
  /** `allowed` | `allowed_warning` | `rejected` | something new. */
  status: string | null;
  resetsAt: string | null;
  observedAt: string;
};

/** `GET /api/me/usage`: the caller's own usage. `windows` is empty unless the credential is an OAuth token. */
export type ClaudeUsage = {
  credentialKind: CredentialKind | null;
  windows: UsageWindow[];
  /** What the caller's agents cost through Agern; UTC day (`YYYY-MM-DD`) and month (`YYYY-MM`). */
  spend: { todayUsd: number; monthUsd: number; day: string; month: string };
};

export type SoftwareRequest = {
  id: string;
  name: string;
  reason: string;
  repo: string;
  requestedBy: string;
  sessionId: string | null;
  status: 'pending' | 'installed' | 'denied';
  note: string | null;
  createdAt: string;
  resolvedAt: string | null;
};

export type FeedFrame =
  | { type: 'session'; session: AgentSession }
  | { type: 'run'; run: Run }
  /** An issue created through Agern, in list shape (no body). Event only: not replayed, not sent for changes made on GitHub. */
  | { type: 'issue'; repo: string; issue: Issue }
  /** A chat was closed (deleted) by its owner or an admin. */
  | { type: 'session_closed'; repo: string; sessionId: string }
  /** A release after any change (created, edited, issues added, removed, moved or reordered). */
  | { type: 'release'; repo: string; release: Release }
  | { type: 'release_deleted'; repo: string; releaseId: string }
  /** An admin approved (approval set) or revoked (null) the project's sandbox definition. */
  | { type: 'sandbox_approval'; repo: string; approval: SandboxApproval | null }
  /** Admins only: a container of a run's sandbox was created or removed, or an admin stopped the sandbox. */
  | { type: 'sandboxes'; runId: string; change: 'created' | 'removed' | 'stopped' }
  /** Private to the receiving user: their own Claude usage changed; reload `GET /api/me/usage`. */
  | { type: 'usage' };

/** `GET /api/github/status` (docs/ARCHITECTURE.md §6 "GitHub status"): whether GitHub works for this Agern. */
export type GitHubVerdictState = 'working' | 'degraded' | 'down';
export type GitHubCallCounts = { calls: number; serverErrors: number; network: number; rateLimited: number; auth: number };
export type GitHubRateLimit = { limit: number; remaining: number; resetAt: string; observedAt: string };
export type GitHubErrorClass = 'server_error' | 'network' | 'rate_limited' | 'auth';
export type GitHubKindStatus = {
  kind: string;
  label: string;
  last15m: GitHubCallCounts;
  last24h: GitHubCallCounts;
  lastError: { at: string; class: GitHubErrorClass; status: number | null; requestId: string | null; message: string } | null;
};
export type GitHubOfficialStatus = {
  state: 'ok' | 'unreachable' | 'disabled';
  /** null unless state = ok. */
  indicator: 'none' | 'minor' | 'major' | 'critical' | 'unknown' | null;
  description: string | null;
  /** Non-operational components only; `relevant`: one Agern depends on. */
  components: { name: string; status: string; relevant: boolean }[];
  incidents: { title: string; status: string; impact: string | null; updatedAt: string | null; url: string | null }[];
  /** When the cached answer (or failure) was obtained. */
  fetchedAt: string | null;
  /** Only for unreachable. */
  error: string | null;
};
export type GitHubStatus = {
  verdict: { state: GitHubVerdictState; reason: string };
  generatedAt: string;
  official: GitHubOfficialStatus;
  /** Empty with `brief`. */
  kinds: GitHubKindStatus[];
  /** null with `brief`. */
  rateLimits: {
    /** null: the caller has no GitHub token. */
    own: { rest: GitHubRateLimit | null; graphql: GitHubRateLimit | null } | null;
    /** Other tokens with a current REST observation. */
    otherTokens: number;
    /** The lowest remaining/limit among them (REST). */
    lowestOther: GitHubRateLimit | null;
  } | null;
};

/** Agents are working on these runs. */
export const WORKING_RUN_STATUSES: RunStatus[] = ['queued', 'spec', 'building', 'reviewing', 'approving', 'waiting_for_checks'];
/** Not finished: what `GET /api/runs?active=true` returns (working + needs_human). */
export const ACTIVE_RUN_STATUSES: RunStatus[] = [...WORKING_RUN_STATUSES, 'needs_human'];

export const isRunWorking = (r: Run) => WORKING_RUN_STATUSES.includes(r.status);
export const isRunActive = (r: Run) => ACTIVE_RUN_STATUSES.includes(r.status);
/** Same rule as `GET /api/agents?active=true`: a live process, or starting/running. */
export const isSessionActive = (s: AgentSession) => s.live || s.status === 'starting' || s.status === 'running';
/** Mid-turn (as opposed to `idle`: waiting for input). */
export const isSessionBusy = (s: { status: SessionStatus }) => s.status === 'starting' || s.status === 'running';
