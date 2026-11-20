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
};

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
};

export type ReleasePauseReason = 'manual' | 'usage_limit' | 'credentials';

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
  pause: { reason: ReleasePauseReason; note: string | null; resumeAt: string | null } | null;
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
  /** Who approved it; null when its pull request was merged on GitHub outside Agern. */
  releasedBy: Owner | null;
  /** The default branch's commit after the release merge. */
  mergeCommit: string | null;
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

export type SessionKind = 'chat' | 'spec-writer' | 'builder' | 'security' | 'ux' | 'ui-design' | 'code' | 'approver';
export type StageRole = Exclude<SessionKind, 'chat'>;

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
};

export type RunStatus =
  | 'queued'
  | 'spec'
  | 'building'
  | 'reviewing'
  | 'approving'
  | 'merged'
  | 'needs_human'
  | 'failed'
  | 'cancelled';

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
  /** The branch the run branches from and merges into (`release/<name>`); null for a single run (default branch). */
  baseBranch: string | null;
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

export type SandboxContainer = { platform: string; image: string; createdAt: string };

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
};

export type EventType = 'user' | 'claude' | 'system' | 'status';

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
};

export type UserPayload = { text: string; username: string; attachments?: Attachment[] };
export type SystemPayload = { level: 'info' | 'warn' | 'error'; text: string; messageId?: string; sandbox?: SandboxLog };
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
  | { type: 'sandboxes'; runId: string; change: 'created' | 'removed' | 'stopped' };

/** Agents are working on these runs. */
export const WORKING_RUN_STATUSES: RunStatus[] = ['queued', 'spec', 'building', 'reviewing', 'approving'];
/** Not finished: what `GET /api/runs?active=true` returns (working + needs_human). */
export const ACTIVE_RUN_STATUSES: RunStatus[] = [...WORKING_RUN_STATUSES, 'needs_human'];

export const isRunWorking = (r: Run) => WORKING_RUN_STATUSES.includes(r.status);
export const isRunActive = (r: Run) => ACTIVE_RUN_STATUSES.includes(r.status);
/** Same rule as `GET /api/agents?active=true`: a live process, or starting/running. */
export const isSessionActive = (s: AgentSession) => s.live || s.status === 'starting' || s.status === 'running';
/** Mid-turn (as opposed to `idle`: waiting for input). */
export const isSessionBusy = (s: { status: SessionStatus }) => s.status === 'starting' || s.status === 'running';
